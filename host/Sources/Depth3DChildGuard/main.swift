import Darwin
import Foundation

/// Parent-death & clean-shutdown watchdog helper (`depth3d-child-guard`).
///
/// Usage:
///   depth3d-child-guard <parent-pid> <executable-path> [args...]
///
/// Design:
/// - Places the spawned child in its OWN dedicated process group (`POSIX_SPAWN_SETPGROUP`,
///   `pgroup = 0` -> `childPGID == childPID != guardPGID`), so every grandchild spawned by
///   the child inherits `childPGID` while this guard remains outside `childPGID`.
/// - Writes the real child PID to `DEPTH3D_CHILD_PID_FILE` immediately after `posix_spawn`
///   so the host's `ChildProcessLedger` tracks BOTH the guard and real child (with `pgid = childPID`
///   and kernel start time).
/// - Uses BSD `kqueue` (`EVFILT_PROC` `NOTE_EXIT` + `EVFILT_SIGNAL` `SIGTERM`/`SIGINT`) with
///   zero busy-polling.
/// - On normal stop (`SIGTERM`/`SIGINT`), parent exit (`NOTE_EXIT`), OR child exit:
///   signals the ENTIRE child process group (`-childPGID`) with `SIGTERM`, waits up to 1.2s
///   until BOTH the child is reaped AND zero processes remain in `childPGID` (catching any
///   grandchildren!), then escalates `-childPGID` to `SIGKILL` if any process survives.

private func isProcessGroupAlive(_ pgid: pid_t) -> Bool {
    guard pgid > 1 else { return false }
    if kill(-pgid, 0) == 0 { return true }
    return errno != ESRCH
}

private func terminateChildProcessGroup(childPID: pid_t, alreadyReaped: Bool = false) {
    guard childPID > 1 else { return }
    let childPGID = childPID
    var reaped = alreadyReaped

    if !reaped {
        var st: Int32 = 0
        let r = waitpid(childPID, &st, WNOHANG)
        if r == childPID || (r < 0 && errno == ECHILD) {
            reaped = true
        }
    }

    if reaped && !isProcessGroupAlive(childPGID) {
        return
    }

    // Send SIGTERM to the ENTIRE child process group (child + all grandchildren).
    _ = kill(-childPGID, SIGTERM)
    if !reaped {
        _ = kill(childPID, SIGTERM)
    }

    let deadline = Date().addingTimeInterval(1.2)
    while Date() < deadline {
        if !reaped {
            var status: Int32 = 0
            let r = waitpid(childPID, &status, WNOHANG)
            if r == childPID || (r < 0 && errno == ECHILD) {
                reaped = true
            }
        }
        if reaped && !isProcessGroupAlive(childPGID) {
            return
        }
        usleep(30_000)
    }

    // Child or any grandchild ignored SIGTERM / failed to exit promptly: force-kill entire group.
    _ = kill(-childPGID, SIGKILL)
    if !reaped {
        _ = kill(childPID, SIGKILL)
        var status: Int32 = 0
        _ = waitpid(childPID, &status, 0)
    }

    // Brief drain so kernel finishes reaping killed grandchildren before guard returns.
    let killDrain = Date().addingTimeInterval(0.3)
    while Date() < killDrain && isProcessGroupAlive(childPGID) {
        usleep(20_000)
    }
}

private func writeChildPIDFileIfConfigured(_ childPID: pid_t) {
    guard let rawPath = getenv("DEPTH3D_CHILD_PID_FILE") else { return }
    let path = String(cString: rawPath)
    guard !path.isEmpty else { return }
    let url = URL(fileURLWithPath: path)
    let text = "\(childPID)\n"
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? Data(text.utf8).write(to: url, options: .atomic)
}

private func main() -> Int32 {
    let args = CommandLine.arguments
    guard args.count >= 3, let parentPID = pid_t(args[1]), parentPID > 1 else {
        fputs("Usage: depth3d-child-guard <parent-pid> <executable> [args...]\n", stderr)
        return 64
    }

    let execPath = args[2]
    let childArgs = Array(args.dropFirst(2))

    // Guard owns its own process group (`guardPGID`), separate from the child's process group (`childPID`).
    _ = setpgid(0, 0)

    // If parent is already gone before we even spawn, exit immediately.
    if kill(parentPID, 0) != 0 && errno == ESRCH {
        return 0
    }

    // Ignore default SIGTERM/SIGINT disposition so kqueue EVFILT_SIGNAL delivers them safely.
    signal(SIGTERM, SIG_IGN)
    signal(SIGINT, SIG_IGN)

    // Spawn child atomically in its OWN process group (`pgroup = 0` -> `childPGID == childPID`)
    // and restore default SIGTERM/SIGINT disposition so child handles normal termination cleanly.
    var attr: posix_spawnattr_t?
    posix_spawnattr_init(&attr)
    defer { posix_spawnattr_destroy(&attr) }
    var defSigs = sigset_t()
    sigemptyset(&defSigs)
    sigaddset(&defSigs, SIGTERM)
    sigaddset(&defSigs, SIGINT)
    posix_spawnattr_setsigdefault(&attr, &defSigs)
    posix_spawnattr_setpgroup(&attr, 0)
    posix_spawnattr_setflags(&attr, Int16(POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETPGROUP))

    var cArgs: [UnsafeMutablePointer<CChar>?] = childArgs.map { strdup($0) }
    cArgs.append(nil)
    defer {
        for ptr in cArgs {
            if let ptr { free(ptr) }
        }
    }

    var childPID: pid_t = 0
    let spawnErr = posix_spawn(&childPID, execPath, nil, &attr, &cArgs, environ)
    guard spawnErr == 0, childPID > 0 else {
        fputs("depth3d: child-guard failed to spawn \(execPath): \(spawnErr)\n", stderr)
        return 127
    }

    writeChildPIDFileIfConfigured(childPID)

    let kq = kqueue()
    guard kq >= 0 else {
        var st: Int32 = 0
        _ = waitpid(childPID, &st, 0)
        terminateChildProcessGroup(childPID: childPID, alreadyReaped: true)
        return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : 1
    }
    defer { close(kq) }

    var changes = [
        kevent(
            ident: UInt(parentPID),
            filter: Int16(EVFILT_PROC),
            flags: UInt16(EV_ADD | EV_ENABLE | EV_ONESHOT),
            fflags: UInt32(NOTE_EXIT),
            data: 0,
            udata: nil
        ),
        kevent(
            ident: UInt(childPID),
            filter: Int16(EVFILT_PROC),
            flags: UInt16(EV_ADD | EV_ENABLE | EV_ONESHOT),
            fflags: UInt32(NOTE_EXIT),
            data: 0,
            udata: nil
        ),
        kevent(
            ident: UInt(SIGTERM),
            filter: Int16(EVFILT_SIGNAL),
            flags: UInt16(EV_ADD | EV_ENABLE),
            fflags: 0,
            data: 0,
            udata: nil
        ),
        kevent(
            ident: UInt(SIGINT),
            filter: Int16(EVFILT_SIGNAL),
            flags: UInt16(EV_ADD | EV_ENABLE),
            fflags: 0,
            data: 0,
            udata: nil
        )
    ]

    let reg = kevent(kq, &changes, Int32(changes.count), nil, 0, nil)
    if reg < 0 {
        if kill(parentPID, 0) != 0 && errno == ESRCH {
            terminateChildProcessGroup(childPID: childPID)
            return 0
        }
    }

    // Re-check parent liveness after kqueue registration to close any race window.
    if kill(parentPID, 0) != 0 && errno == ESRCH {
        terminateChildProcessGroup(childPID: childPID)
        return 0
    }

    while true {
        var event = kevent()
        var ts = timespec(tv_sec: 1, tv_nsec: 0)
        let n = kevent(kq, nil, 0, &event, 1, &ts)
        if n < 0 {
            if errno == EINTR { continue }
            break
        }
        if n == 0 {
            if getppid() != parentPID || (kill(parentPID, 0) != 0 && errno == ESRCH) {
                terminateChildProcessGroup(childPID: childPID)
                return 0
            }
            var st: Int32 = 0
            if waitpid(childPID, &st, WNOHANG) == childPID {
                terminateChildProcessGroup(childPID: childPID, alreadyReaped: true)
                return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : (128 + (st & 0x7f))
            }
            continue
        }

        if event.filter == Int16(EVFILT_SIGNAL) {
            terminateChildProcessGroup(childPID: childPID)
            return 0
        }

        if event.filter == Int16(EVFILT_PROC) {
            if pid_t(event.ident) == parentPID {
                terminateChildProcessGroup(childPID: childPID)
                return 0
            }
            if pid_t(event.ident) == childPID {
                var st: Int32 = 0
                _ = waitpid(childPID, &st, 0)
                terminateChildProcessGroup(childPID: childPID, alreadyReaped: true)
                return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : (128 + (st & 0x7f))
            }
        }
    }

    terminateChildProcessGroup(childPID: childPID)
    return 1
}

exit(main())
