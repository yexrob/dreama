import Darwin
import Foundation

/// Record of a child process spawned by depth3d Host so orphans left by a crash or `SIGKILL`
/// can be safely identified (via `proc_pidpath`) and cleaned up on next launch.
public struct TrackedChildProcess: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case engine
        case rclone
        case childGuard
    }

    public let pid: Int32
    public let kind: Kind
    public let executableHint: String
    public let recordedAt: Date

    public init(pid: Int32, kind: Kind, executableHint: String, recordedAt: Date = Date()) {
        self.pid = pid
        self.kind = kind
        self.executableHint = executableHint
        self.recordedAt = recordedAt
    }
}

/// Manages a persistent PID ledger (`running-children.json`) and wraps child process spawning
/// with `depth3d-child-guard` (kqueue `NOTE_EXIT` parent-death watchdog) when bundled.
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

    public func register(pid: Int32, kind: TrackedChildProcess.Kind, executableHint: String) {
        guard pid > 1 else { return }
        lock.lock()
        defer { lock.unlock() }
        var entries = readLocked()
        entries.removeAll { $0.pid == pid }
        entries.append(TrackedChildProcess(pid: pid, kind: kind, executableHint: executableHint))
        writeLocked(entries)
    }

    public func unregister(pid: Int32) {
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
    /// verifying each PID's executable path via `proc_pidpath` so PID reuse never kills an unrelated process.
    @discardableResult
    public func cleanupStaleChildren(kinds: Set<TrackedChildProcess.Kind>? = nil) -> Int {
        lock.lock()
        let entries = readLocked()
        lock.unlock()

        var terminated = 0
        var surviving: [TrackedChildProcess] = []
        let myPID = getpid()

        for entry in entries {
            if let kinds, !kinds.contains(entry.kind) {
                surviving.append(entry)
                continue
            }
            guard entry.pid > 1, entry.pid != myPID else { continue }
            guard kill(entry.pid, 0) == 0 else { continue }

            // Verify process path matches expected executable hint before signalling.
            guard let actualPath = Self.processExecutablePath(pid: entry.pid),
                  Self.matchesHint(actualPath: actualPath, hint: entry.executableHint)
            else {
                HostLogger.info("Skipping stale ledger pid=\(entry.pid) (executable no longer matches)", category: "watchdog")
                continue
            }

            HostLogger.warning("Cleaning up orphaned child process pid=\(entry.pid) (\(entry.kind.rawValue))", category: "watchdog")
            _ = kill(entry.pid, SIGTERM)
            let deadline = Date().addingTimeInterval(1.5)
            while Date() < deadline {
                if kill(entry.pid, 0) != 0 && errno == ESRCH {
                    break
                }
                Thread.sleep(forTimeInterval: 0.05)
            }
            if kill(entry.pid, 0) == 0 {
                _ = kill(entry.pid, SIGKILL)
            }
            terminated += 1
        }

        lock.lock()
        writeLocked(surviving)
        lock.unlock()
        return terminated
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
