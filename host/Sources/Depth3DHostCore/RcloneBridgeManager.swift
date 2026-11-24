import Darwin
import Foundation

/// Status of the optional user-installed `rclone` tool used for cloud drive mounts.
public struct RcloneAvailability: Equatable, Sendable {
    public let executablePath: String?
    public let availableRemotes: [String]

    public var isInstalled: Bool { executablePath != nil }

    public init(executablePath: String?, availableRemotes: [String]) {
        self.executablePath = executablePath
        self.availableRemotes = availableRemotes
    }
}

/// Active loopback `rclone serve webdav` backend managed for a single `CloudRemoteEntry`.
public struct RcloneBackendEndpoint: Sendable {
    public let mountName: String
    public let remoteSpec: String
    public let port: UInt16
    public let authHeader: String
}

/// Detects user-installed `rclone`, enumerates configured remotes (`rclone listremotes`),
/// and supervises loopback-only read-only `rclone serve webdav` instances (matching
/// `scripts/serve-gdrive.sh` streaming cache flags) proxied through Host's unified WebDAV server.
public final class RcloneBridgeManager: @unchecked Sendable {
    private struct RunningBackend {
        let entry: CloudRemoteEntry
        let process: Process
        let endpoint: RcloneBackendEndpoint
    }

    private let lock = NSLock()
    private var backends: [String: RunningBackend] = [:] // keyed by normalized mountName
    private var cachedExecutablePath: String?

    public init() {}

    deinit {
        stopAll()
    }

    public static func locateRclone() -> String? {
        let fm = FileManager.default
        var candidates: [String] = [
            "/opt/homebrew/bin/rclone",
            "/usr/local/bin/rclone",
            (NSHomeDirectory() as NSString).appendingPathComponent(".local/bin/rclone"),
            "/usr/bin/rclone"
        ]
        if let pathEnv = ProcessInfo.processInfo.environment["PATH"] {
            for dir in pathEnv.split(separator: ":") {
                candidates.append((String(dir) as NSString).appendingPathComponent("rclone"))
            }
        }
        var seen = Set<String>()
        for candidate in candidates where seen.insert(candidate).inserted {
            if fm.isExecutableFile(atPath: candidate) {
                return candidate
            }
        }
        return nil
    }

    /// Probes for `rclone` and lists configured remotes without throwing errors if absent.
    public func probeAvailability() -> RcloneAvailability {
        guard let bin = Self.locateRclone() else {
            lock.lock()
            cachedExecutablePath = nil
            lock.unlock()
            return RcloneAvailability(executablePath: nil, availableRemotes: [])
        }
        lock.lock()
        cachedExecutablePath = bin
        lock.unlock()

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: bin)
        proc.arguments = ["listremotes"]
        let out = Pipe()
        proc.standardOutput = out
        proc.standardError = FileHandle.nullDevice

        do {
            try proc.run()
            proc.waitUntilExit()
            guard proc.terminationStatus == 0 else {
                return RcloneAvailability(executablePath: bin, availableRemotes: [])
            }
            let data = out.fileHandleForReading.readDataToEndOfFile()
            let text = String(data: data, encoding: .utf8) ?? ""
            let remotes = text
                .components(separatedBy: .newlines)
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            return RcloneAvailability(executablePath: bin, availableRemotes: remotes)
        } catch {
            return RcloneAvailability(executablePath: bin, availableRemotes: [])
        }
    }

    /// Reconciles active loopback rclone backends with the user's configured `CloudRemoteEntry` list.
    public func syncRemotes(_ remotes: [CloudRemoteEntry]) {
        guard let bin = Self.locateRclone() else {
            stopAll()
            return
        }

        let desiredByName = Dictionary(uniqueKeysWithValues: remotes.map { ($0.displayName, $0) })

        lock.lock()
        let existingKeys = Array(backends.keys)
        var toStop: [RunningBackend] = []
        for key in existingKeys {
            guard let running = backends[key] else { continue }
            if desiredByName[key]?.remoteSpec != running.entry.remoteSpec || !running.process.isRunning {
                backends.removeValue(forKey: key)
                toStop.append(running)
            }
        }
        let alreadyRunningKeys = Set(backends.keys)
        lock.unlock()

        for item in toStop {
            terminateProcess(item.process)
        }

        for remote in remotes where !alreadyRunningKeys.contains(remote.displayName) {
            if let started = startBackend(executablePath: bin, entry: remote) {
                lock.lock()
                backends[remote.displayName] = started
                lock.unlock()
            }
        }
    }

    public func activeEndpoints() -> [RcloneBackendEndpoint] {
        lock.lock()
        defer { lock.unlock() }
        return backends.values
            .filter { $0.process.isRunning }
            .map(\.endpoint)
            .sorted { $0.mountName.localizedStandardCompare($1.mountName) == .orderedAscending }
    }

    public func endpoint(forMountName name: String) -> RcloneBackendEndpoint? {
        lock.lock()
        defer { lock.unlock() }
        guard let backend = backends[name], backend.process.isRunning else { return nil }
        return backend.endpoint
    }

    public func stopAll() {
        lock.lock()
        let all = Array(backends.values)
        backends.removeAll()
        lock.unlock()
        for item in all {
            terminateProcess(item.process)
        }
    }

    private func startBackend(executablePath: String, entry: CloudRemoteEntry) -> RunningBackend? {
        guard let port = allocateEphemeralLoopbackPort() else {
            HostLogger.error("Failed to allocate loopback port for cloud remote \(entry.displayName)", category: "media")
            return nil
        }

        let user = "depth3d_rclone"
        let pass = SecureRandom.hexString(byteCount: 12)
        let authRaw = Data("\(user):\(pass)".utf8).base64EncodedString()
        let authHeader = "Basic \(authRaw)"

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: executablePath)
        // Use the exact tuned read-only streaming flags from scripts/serve-gdrive.sh,
        // bound strictly to 127.0.0.1 and proxied by Host's unified WebDAV server.
        proc.arguments = [
            "serve", "webdav", entry.remoteSpec,
            "--addr", "127.0.0.1:\(port)",
            "--user", user,
            "--pass", pass,
            "--read-only",
            "--vfs-cache-mode", "off",
            "--vfs-read-chunk-size", "16M",
            "--vfs-read-chunk-size-limit", "256M",
            "--buffer-size", "32M",
            "--dir-cache-time", "5m",
            "--poll-interval", "0"
        ]
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice

        do {
            try proc.run()
            HostLogger.info("Started loopback cloud remote backend '\(entry.displayName)' on 127.0.0.1:\(port)", category: "media")
            let endpoint = RcloneBackendEndpoint(
                mountName: entry.displayName,
                remoteSpec: entry.remoteSpec,
                port: port,
                authHeader: authHeader
            )
            return RunningBackend(entry: entry, process: proc, endpoint: endpoint)
        } catch {
            HostLogger.error("Failed to launch cloud remote backend '\(entry.displayName)': \(error.localizedDescription)", category: "media")
            return nil
        }
    }

    private func terminateProcess(_ proc: Process) {
        guard proc.isRunning else { return }
        let pid = proc.processIdentifier
        proc.terminate()
        let deadline = Date().addingTimeInterval(1.5)
        while proc.isRunning && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.05)
        }
        if proc.isRunning {
            kill(pid, SIGKILL)
            proc.waitUntilExit()
        }
    }

    private func allocateEphemeralLoopbackPort() -> UInt16? {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        defer { Darwin.close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = 0
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { return nil }
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let ok = withUnsafeMutablePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        guard ok == 0 else { return nil }
        return UInt16(bigEndian: addr.sin_port)
    }
}
