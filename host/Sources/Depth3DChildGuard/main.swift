import Darwin
import Foundation

/// Parent-death & clean-shutdown watchdog helper (`depth3d-child-guard`).
///
/// Usage:
///   depth3d-child-guard <parent-pid> <executable-path> [args...]
///
/// Design:
/// - Calls `setpgid(0, 0)` so this guard and its spawned child (plus grandchildren) share
///   process group `pgid == guardPID`, allowing the host to atomically terminate the entire
///   process group on escalation (`kill(-guardPID, SIGKILL)`).
/// - If `DEPTH3D_CHILD_PID_FILE` is set in the environment, writes the real child's PID to it
///   immediately after `posix_spawn` so the host's `ChildProcessLedger` tracks BOTH the guard
///   and the real child process (with kernel start times).
/// - Uses BSD `kqueue` (`EVFILT_PROC` `NOTE_EXIT` + `EVFILT_SIGNAL` `SIGTERM`/`SIGINT`) with
///   zero busy-polling. On parent exit OR termination signal, sends `SIGTERM` to the child,
///   waits up to 1.2s, and escalates to `SIGKILL` on the child (and process group) if the child
///   ignores `SIGTERM`.

private func terminateChildAndGroup(childPID: pid_t, guardPGID: pid_t) {
    guard childPID > 1 else { return }

    // Forward SIGTERM to child (and child's own group if it changed pgid).
    _ = kill(childPID, SIGTERM)
    let childPGID = getpgid(childPID)
    if childPGID > 1 && childPGID != guardPGID {
        _ = kill(-childPGID, SIGTERM)
    }

    let deadline = Date().addingTimeInterval(1.2)
    while Date() < deadline {
        var status: Int32 = 0
        let r = waitpid(childPID, &status, WNOHANG)
        if r == childPID || (r < 0 && errno == ECHILD) {
            return
        }
        usleep(30_000)
    }

    // Child ignored SIGTERM or failed to exit promptly: force-kill child and its group.
    _ = kill(childPID, SIGKILL)
    if childPGID > 1 && childPGID != guardPGID {
        _ = kill(-childPGID, SIGKILL)
    }
    var status: Int32 = 0
    _ = waitpid(childPID, &status, 0)
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

    // Become our own process group leader so our spawned child inherits `pgid == getpid()`.
    _ = setpgid(0, 0)
    let guardPGID = getpid()

    // If parent is already gone before we even spawn, exit immediately.
    if kill(parentPID, 0) != 0 && errno == ESRCH {
        return 0
    }

    // Ignore default SIGTERM/SIGINT disposition so kqueue EVFILT_SIGNAL delivers them safely.
    signal(SIGTERM, SIG_IGN)
    signal(SIGINT, SIG_IGN)

    // Spawn child inheriting stdin/stdout/stderr, environment, and process group (`guardPGID`).
    // Restore default SIGTERM/SIGINT disposition in child via posix_spawnattr so child can handle them normally.
    var attr: posix_spawnattr_t?
    posix_spawnattr_init(&attr)
    defer { posix_spawnattr_destroy(&attr) }
    var defSigs = sigset_t()
    sigemptyset(&defSigs)
    sigaddset(&defSigs, SIGTERM)
    sigaddset(&defSigs, SIGINT)
    posix_spawnattr_setsigdefault(&attr, &defSigs)
    posix_spawnattr_setflags(&attr, Int16(POSIX_SPAWN_SETSIGDEF))

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
            terminateChildAndGroup(childPID: childPID, guardPGID: guardPGID)
            return 0
        }
    }

    // Re-check parent liveness after kqueue registration to close any race window.
    if kill(parentPID, 0) != 0 && errno == ESRCH {
        terminateChildAndGroup(childPID: childPID, guardPGID: guardPGID)
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
                terminateChildAndGroup(childPID: childPID, guardPGID: guardPGID)
                return 0
            }
            var st: Int32 = 0
            if waitpid(childPID, &st, WNOHANG) == childPID {
                return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : (128 + (st & 0x7f))
            }
            continue
        }

        if event.filter == Int16(EVFILT_SIGNAL) {
            terminateChildAndGroup(childPID: childPID, guardPGID: guardPGID)
            return 0
        }

        if event.filter == Int16(EVFILT_PROC) {
            if pid_t(event.ident) == parentPID {
                terminateChildAndGroup(childPID: childPID, guardPGID: guardPGID)
                return 0
            }
            if pid_t(event.ident) == childPID {
                var st: Int32 = 0
                _ = waitpid(childPID, &st, 0)
                return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : (128 + (st & 0x7f))
            }
        }
    }

    terminateChildAndGroup(childPID: childPID, guardPGID: guardPGID)
    return 1
}

exit(main())
