import Darwin
import Foundation

/// Record of a child process spawned by depth3d Host so orphans left by a crash or `SIGKILL`
/// can be safely identified (via BOTH `proc_pidpath` AND kernel process start time) and cleaned
/// up across their entire process group on next launch.
public struct TrackedChildProcess: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case engine
        case rclone
        case childGuard
    }

    public let pid: Int32
    /// Process group ID (`pgid`) if this process owns a dedicated process group; nil when unknown.
    public let pgid: Int32?
    /// Kernel process creation timestamp (`proc_bsdinfo.pbi_start_tvsec`) to guard against PID reuse.
    public let startTimeSec: UInt64?
    public let kind: Kind
    public let executableHint: String
    public let recordedAt: Date

    public init(
        pid: Int32,
        pgid: Int32? = nil,
        startTimeSec: UInt64? = nil,
        kind: Kind,
        executableHint: String,
        recordedAt: Date = Date()
    ) {
        self.pid = pid
        self.pgid = pgid
        self.startTimeSec = startTimeSec
        self.kind = kind
        self.executableHint = executableHint
        self.recordedAt = recordedAt
    }
}

/// Manages a persistent PID ledger (`running-children.json`) and wraps child process spawning
/// with `depth3d-child-guard` (kqueue `NOTE_EXIT` parent-death watchdog + dedicated process group).
public final class ChildProcessLedger: @unchecked Sendable {
    private let ledgerURL: URL
    private let lock = NSLock()
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(ledgerURL: URL) {
        self.ledgerURL = ledgerURL
        self.encoder = JSONEncoder()
        self.encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        self.encoder.dateEncodingStrategy = .iso8601
        self.decoder = JSONDecoder()
        self.decoder.dateDecodingStrategy = .iso8601
    }

    public func register(
        pid: Int32,
        pgid: Int32? = nil,
        startTimeSec: UInt64? = nil,
        kind: TrackedChildProcess.Kind,
        executableHint: String
    ) {
        guard pid > 1 else { return }
        let resolvedStart = startTimeSec ?? Self.processStartTimeSec(pid: pid)
        let resolvedPGID = pgid ?? Self.processGroupID(pid: pid)
        lock.lock()
        defer { lock.unlock() }
        var entries = readLocked()
        entries.removeAll { $0.pid == pid }
        entries.append(TrackedChildProcess(
            pid: pid,
            pgid: resolvedPGID,
            startTimeSec: resolvedStart,
            kind: kind,
            executableHint: executableHint
        ))
        writeLocked(entries)
    }

    public func unregister(pid: Int32) {
        guard pid > 1 else { return }
        lock.lock()
        defer { lock.unlock() }
        var entries = readLocked()
        entries.removeAll { $0.pid == pid }
        writeLocked(entries)
    }

    public func allEntries() -> [TrackedChildProcess] {
        lock.lock()
        defer { lock.unlock() }
        return readLocked()
    }

    /// Terminates any leftover processes recorded by a previous crashed Host instance,
    /// verifying BOTH each PID's executable path (`proc_pidpath`) AND kernel process start time
    /// (`pbi_start_tvsec`) so PID reuse can never kill an unrelated process.
    /// Escalates to process-group `SIGKILL` when a process ignores `SIGTERM`.
    @discardableResult
    public func cleanupStaleChildren(kinds: Set<TrackedChildProcess.Kind>? = nil) -> Int {
        lock.lock()
        let entries = readLocked()
        lock.unlock()

        var terminated = 0
        var surviving: [TrackedChildProcess] = []
        let myPID = getpid()
        let myPGID = getpgrp()

        for entry in entries {
            if let kinds, !kinds.contains(entry.kind) {
                surviving.append(entry)
                continue
            }
            guard entry.pid > 1, entry.pid != myPID else { continue }
            guard kill(entry.pid, 0) == 0 else { continue }

            // 1. Verify executable path matches expected hint.
            guard let actualPath = Self.processExecutablePath(pid: entry.pid),
                  Self.matchesHint(actualPath: actualPath, hint: entry.executableHint)
            else {
                HostLogger.info("Skipping stale ledger pid=\(entry.pid) (executable no longer matches)", category: "watchdog")
                continue
            }

            // 2. Verify kernel start time matches recorded start time (tolerance ±2s) if recorded.
            if let recordedStart = entry.startTimeSec,
               let actualStart = Self.processStartTimeSec(pid: entry.pid)
            {
                let diff = recordedStart > actualStart ? (recordedStart - actualStart) : (actualStart - recordedStart)
                if diff > 2 {
                    HostLogger.info("Skipping stale ledger pid=\(entry.pid) (PID reused; start time mismatch)", category: "watchdog")
                    continue
                }
            }

            HostLogger.warning("Cleaning up orphaned child process pid=\(entry.pid) (\(entry.kind.rawValue))", category: "watchdog")
            let targetPGID: Int32? = {
                if let p = entry.pgid, p > 1, p != myPGID { return p }
                if let live = Self.processGroupID(pid: entry.pid), live > 1, live != myPGID { return live }
                return nil
            }()

            _ = kill(entry.pid, SIGTERM)
            let deadline = Date().addingTimeInterval(1.5)
            while Date() < deadline {
                if kill(entry.pid, 0) != 0 && errno == ESRCH {
                    break
                }
                Thread.sleep(forTimeInterval: 0.05)
            }
            if kill(entry.pid, 0) == 0 {
                if let pgid = targetPGID {
                    _ = kill(-pgid, SIGKILL)
                }
                _ = kill(entry.pid, SIGKILL)
            }
            terminated += 1
        }

        lock.lock()
        writeLocked(surviving)
        lock.unlock()
        return terminated
    }

    /// Terminates a live managed process cleanly with `SIGTERM`, escalating to process-group
    /// `SIGKILL` + explicit real-child `SIGKILL` if the process or child ignores `SIGTERM`.
    public static func terminateManagedProcess(
        process: Process,
        usedGuard: Bool,
        realChildPID: Int32?,
        graceSeconds: TimeInterval = 2.0
    ) {
        let topPID = process.processIdentifier
        let myPGID = getpgrp()

        if process.isRunning {
            process.terminate()
            let deadline = Date().addingTimeInterval(graceSeconds)
            while process.isRunning && Date() < deadline {
                Thread.sleep(forTimeInterval: 0.04)
            }
            if process.isRunning {
                // Escalate: kill the ENTIRE process group so a slow/SIGTERM-ignoring child never becomes an orphan.
                if usedGuard && topPID > 1 && topPID != myPGID {
                    _ = kill(-topPID, SIGKILL)
                } else if let pgid = processGroupID(pid: topPID), pgid > 1, pgid != myPGID {
                    _ = kill(-pgid, SIGKILL)
                }
                _ = kill(topPID, SIGKILL)
                process.waitUntilExit()
            }
        }

        // Belt-and-suspenders: if the real child OR any grandchild in its dedicated process group
        // (`pgid == childPID`) somehow survived guard exit, kill the entire child process group.
        if let childPID = realChildPID, childPID > 1 {
            if usedGuard && childPID != myPGID {
                if kill(-childPID, 0) == 0 || errno == EPERM {
                    _ = kill(-childPID, SIGKILL)
                }
            }
            if kill(childPID, 0) == 0 {
                if let childPGID = processGroupID(pid: childPID), childPGID > 1, childPGID != myPGID {
                    _ = kill(-childPGID, SIGKILL)
                }
                _ = kill(childPID, SIGKILL)
            }
        }
    }

    /// Reads the child PID written by `depth3d-child-guard` into `DEPTH3D_CHILD_PID_FILE`,
    /// waiting briefly for `posix_spawn` to complete.
    public static func waitForGuardedChildPID(pidFileURL: URL, timeout: TimeInterval = 0.8) -> Int32? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let text = try? String(contentsOf: pidFileURL, encoding: .utf8),
               let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)),
               pid > 1
            {
                return pid
            }
            Thread.sleep(forTimeInterval: 0.02)
        }
        return nil
    }

    /// Locates the bundled `depth3d-child-guard` executable if available.
    public static func locateChildGuard(bundle: Bundle = .main) -> URL? {
        let fm = FileManager.default
        let bundleURL = bundle.bundleURL
        let candidates: [URL] = [
            bundleURL.appendingPathComponent("Contents/Helpers/depth3d-child-guard"),
            bundleURL.deletingLastPathComponent().appendingPathComponent("Helpers/depth3d-child-guard"),
            bundleURL.deletingLastPathComponent().appendingPathComponent("depth3d-child-guard")
        ]
        for candidate in candidates where fm.isExecutableFile(atPath: candidate.path) {
            return candidate
        }
        return nil
    }

    /// Configures `process` to run via `depth3d-child-guard` when present, or directly otherwise.
    public static func configureLaunch(
        process: Process,
        executableURL: URL,
        arguments: [String],
        guardURL: URL? = ChildProcessLedger.locateChildGuard()
    ) -> Bool {
        if let guardURL {
            process.executableURL = guardURL
            process.arguments = [String(getpid()), executableURL.path] + arguments
            return true
        } else {
            process.executableURL = executableURL
            process.arguments = arguments
            return false
        }
    }

    public static func processExecutablePath(pid: Int32) -> String? {
        var buf = [CChar](repeating: 0, count: 4096) // PROC_PIDPATHINFO_MAXSIZE
        let len = proc_pidpath(pid, &buf, UInt32(buf.count))
        guard len > 0 else { return nil }
        return String(cString: buf)
    }

    /// Queries kernel BSD info for a PID's creation timestamp in seconds (`pbi_start_tvsec`).
    public static func processStartTimeSec(pid: Int32) -> UInt64? {
        var info = proc_bsdinfo()
        let expected = Int32(MemoryLayout<proc_bsdinfo>.size)
        let n = proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, expected)
        guard n == expected else { return nil }
        return info.pbi_start_tvsec
    }

    public static func processGroupID(pid: Int32) -> Int32? {
        let pgid = getpgid(pid)
        return pgid > 0 ? pgid : nil
    }

    public static func matchesHint(actualPath: String, hint: String) -> Bool {
        guard !hint.isEmpty else { return false }
        let actualName = (actualPath as NSString).lastPathComponent.lowercased()
        let hintName = (hint as NSString).lastPathComponent.lowercased()
        return actualPath == hint || actualName == hintName || actualName.contains(hintName)
    }

    private func readLocked() -> [TrackedChildProcess] {
        guard let data = try? Data(contentsOf: ledgerURL),
              let decoded = try? decoder.decode([TrackedChildProcess].self, from: data)
        else {
            return []
        }
        return decoded
    }

    private func writeLocked(_ entries: [TrackedChildProcess]) {
        let dir = ledgerURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let data = try? encoder.encode(entries) {
            try? data.write(to: ledgerURL, options: .atomic)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: ledgerURL.path)
        }
    }
}
