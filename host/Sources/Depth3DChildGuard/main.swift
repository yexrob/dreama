import Darwin
import Foundation

/// Lightweight parent-death watchdog helper (`depth3d-child-guard`).
///
/// Usage:
///   depth3d-child-guard <parent-pid> <executable-path> [args...]
///
/// Spawns `<executable-path>` as a direct child and monitors BOTH `<parent-pid>` and the child
/// via BSD `kqueue` `EVFILT_PROC` (`NOTE_EXIT`) — zero polling. If the parent process crashes or
/// is killed with `SIGKILL`, this guard immediately terminates the child (`SIGTERM`, then `SIGKILL`
/// after 1.5 s) and exits so no orphan streaming engine or rclone process is ever left behind.

private var gChildPID: pid_t = -1

private func terminateChild(_ pid: pid_t) {
    guard pid > 0 else { return }
    _ = kill(pid, SIGTERM)
    let deadline = Date().addingTimeInterval(1.5)
    while Date() < deadline {
        var status: Int32 = 0
        let r = waitpid(pid, &status, WNOHANG)
        if r == pid || r < 0 {
            return
        }
        usleep(50_000)
    }
    _ = kill(pid, SIGKILL)
    var status: Int32 = 0
    _ = waitpid(pid, &status, 0)
}

private func main() -> Int32 {
    let args = CommandLine.arguments
    guard args.count >= 3, let parentPID = pid_t(args[1]), parentPID > 1 else {
        fputs("Usage: depth3d-child-guard <parent-pid> <executable> [args...]\n", stderr)
        return 64
    }

    let execPath = args[2]
    let childArgs = Array(args.dropFirst(2))

    // If parent is already gone before we even spawn, exit immediately.
    if kill(parentPID, 0) != 0 && errno == ESRCH {
        return 0
    }

    // Forward SIGTERM/SIGINT cleanly to the child.
    signal(SIGTERM) { _ in
        if gChildPID > 0 {
            _ = kill(gChildPID, SIGTERM)
        }
    }
    signal(SIGINT) { _ in
        if gChildPID > 0 {
            _ = kill(gChildPID, SIGTERM)
        }
    }

    // Spawn child inheriting stdin/stdout/stderr and environment.
    var cArgs: [UnsafeMutablePointer<CChar>?] = childArgs.map { strdup($0) }
    cArgs.append(nil)
    defer {
        for ptr in cArgs {
            if let ptr { free(ptr) }
        }
    }

    var childPID: pid_t = 0
    let spawnErr = posix_spawn(&childPID, execPath, nil, nil, &cArgs, environ)
    guard spawnErr == 0, childPID > 0 else {
        fputs("depth3d: child-guard failed to spawn \(execPath): \(spawnErr)\n", stderr)
        return 127
    }
    gChildPID = childPID

    let kq = kqueue()
    guard kq >= 0 else {
        // Fallback: wait on child directly
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
        )
    ]

    let reg = kevent(kq, &changes, Int32(changes.count), nil, 0, nil)
    if reg < 0 {
        // If parent already vanished while registering, terminate child immediately.
        if kill(parentPID, 0) != 0 && errno == ESRCH {
            terminateChild(childPID)
            return 0
        }
    }

    // Re-check parent liveness after registration to close any tiny race window.
    if kill(parentPID, 0) != 0 && errno == ESRCH {
        terminateChild(childPID)
        return 0
    }

    while true {
        var event = kevent()
        var ts = timespec(tv_sec: 1, tv_nsec: 0)
        let n = kevent(kq, nil, 0, &event, 1, &ts)
        if n < 0 {
            if errno == EINTR {
                // Check if child exited due to forwarded signal.
                var st: Int32 = 0
                if waitpid(childPID, &st, WNOHANG) == childPID {
                    return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : 1
                }
                continue
            }
            break
        }
        if n == 0 {
            // Periodic liveness safety check in case NOTE_EXIT wasn't attached.
            if getppid() != parentPID || (kill(parentPID, 0) != 0 && errno == ESRCH) {
                terminateChild(childPID)
                return 0
            }
            var st: Int32 = 0
            if waitpid(childPID, &st, WNOHANG) == childPID {
                return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : 1
            }
            continue
        }

        if pid_t(event.ident) == parentPID {
            terminateChild(childPID)
            return 0
        }
        if pid_t(event.ident) == childPID {
            var st: Int32 = 0
            _ = waitpid(childPID, &st, 0)
            return (st & 0x7f) == 0 ? ((st >> 8) & 0xff) : (128 + (st & 0x7f))
        }
    }

    terminateChild(childPID)
    return 1
}

exit(main())
