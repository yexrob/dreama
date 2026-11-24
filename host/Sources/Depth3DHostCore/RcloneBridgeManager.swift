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

/// Runtime status of an individual configured `CloudRemoteEntry` mounted via `rclone serve webdav`.
public enum RcloneRemoteStatus: Equatable, Sendable {
    case starting
    case running
    case error(message: String)
}

/// Active loopback `rclone serve webdav` backend managed for a single `CloudRemoteEntry`.
public struct RcloneBackendEndpoint: Sendable {
    public let mountName: String
    public let remoteSpec: String
    public let port: UInt16
    public let authHeader: String

    public init(mountName: String, remoteSpec: String, port: UInt16, authHeader: String) {
        self.mountName = mountName
        self.remoteSpec = remoteSpec
        self.port = port
        self.authHeader = authHeader
    }
}

/// Detects user-installed `rclone`, enumerates configured remotes (`rclone listremotes`),
/// and supervises loopback-only read-only `rclone serve webdav` instances (matching
/// `scripts/serve-gdrive.sh` streaming cache flags) proxied through Host's unified WebDAV server.
///
/// Safety guarantees:
/// - Passes `RCLONE_USER` and `RCLONE_PASS` strictly via child environment (NEVER CLI args)
/// - Wraps child launch with `depth3d-child-guard` + `ChildProcessLedger` to prevent orphan processes
/// - Serializes all `syncRemotes` / restart / stop operations on a dedicated serial queue
/// - Deduplicates duplicate remote display names without crashing
/// - Captures stderr to classify auth expiry, missing remotes, and network failures in Chinese
/// - Automatically restarts crashed backends with exponential backoff
public final class RcloneBridgeManager: @unchecked Sendable {
    private final class StderrBuffer: @unchecked Sendable {
        private let lock = NSLock()
        private var text = ""
        private let maxChars = 8192

        func append(_ chunk: String) -> String {
            lock.lock()
            defer { lock.unlock() }
            text += chunk
            if text.count > maxChars {
                text = String(text.suffix(maxChars))
            }
            return text
        }

        func snapshot() -> String {
            lock.lock()
            defer { lock.unlock() }
            return text
        }
    }

    private final class ManagedRemote {
        let entry: CloudRemoteEntry
        var process: Process?
        var trackedPID: Int32 = -1
        var endpoint: RcloneBackendEndpoint?
        var status: RcloneRemoteStatus = .starting
        var consecutiveFailures: Int = 0
        var lastLaunchDate: Date?
        var pendingRestart: DispatchWorkItem?
        var pendingConfirm: DispatchWorkItem?
        var stderrBuffer = StderrBuffer()
        var generation: UInt64 = 0

        init(entry: CloudRemoteEntry) {
            self.entry = entry
        }
    }

    private let syncQueue = DispatchQueue(label: "dev.depth3d.host.rclone.sync", qos: .utility)
    private let stateLock = NSLock()
    private var remotesByName: [String: ManagedRemote] = [:]
    private var activeEndpointCache: [String: RcloneBackendEndpoint] = [:]
    private var statusCache: [String: RcloneRemoteStatus] = [:]
    private var onStatusesChanged: (@Sendable ([String: RcloneRemoteStatus]) -> Void)?

    private let ledger: ChildProcessLedger?
    private let executableOverride: String?
    private let childGuardOverride: URL?
    private let backoffDelays: [TimeInterval]
    private let stableRunResetSeconds: TimeInterval
    private let startupConfirmDelay: TimeInterval
    private var launchCounter: Int = 0

    public init(
        ledger: ChildProcessLedger? = nil,
        executableOverride: String? = nil,
        childGuardOverride: URL? = nil,
        backoffDelays: [TimeInterval] = [1.0, 2.0, 5.0, 10.0],
        stableRunResetSeconds: TimeInterval = 30.0,
        startupConfirmDelay: TimeInterval = 0.8
    ) {
        self.ledger = ledger
        self.executableOverride = executableOverride
        self.childGuardOverride = childGuardOverride
        self.backoffDelays = backoffDelays
        self.stableRunResetSeconds = stableRunResetSeconds
        self.startupConfirmDelay = startupConfirmDelay
    }

    deinit {
        stopAll()
    }

    public func setStatusesChangedHandler(_ handler: (@Sendable ([String: RcloneRemoteStatus]) -> Void)?) {
        stateLock.lock()
        onStatusesChanged = handler
        let snap = statusCache
        stateLock.unlock()
        handler?(snap)
    }

    public func currentStatuses() -> [String: RcloneRemoteStatus] {
        stateLock.lock()
        defer { stateLock.unlock() }
        return statusCache
    }

    public func totalLaunchAttempts() -> Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        return launchCounter
    }

    /// Cleans up any orphaned `rclone` processes left behind by a previous crashed Host instance.
    @discardableResult
    public func cleanupOrphanedRcloneProcesses() -> Int {
        ledger?.cleanupStaleChildren(kinds: [.rclone]) ?? 0
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

    private func resolvedExecutablePath() -> String? {
        if let override = executableOverride {
            return FileManager.default.isExecutableFile(atPath: override) ? override : nil
        }
        return Self.locateRclone()
    }

    /// Probes for `rclone` and lists configured remotes without throwing errors if absent.
    public func probeAvailability() -> RcloneAvailability {
        guard let bin = resolvedExecutablePath() else {
            return RcloneAvailability(executablePath: nil, availableRemotes: [])
        }

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

    /// Classifies raw `rclone` stderr output into actionable Simplified Chinese copy and indicates
    /// whether the failure is a permanent configuration/auth issue that requires user intervention.
    public static func classifyStderr(_ stderr: String, remoteSpec: String) -> (message: String, isPermanentConfigError: Bool) {
        let lower = stderr.lowercased()
        let remoteName = remoteSpec.split(separator: ":").first.map(String.init) ?? remoteSpec

        if lower.contains("invalid_grant")
            || lower.contains("token expired")
            || lower.contains("couldn't fetch token")
            || lower.contains("oauth2")
            || lower.contains("unauthorized")
            || lower.contains("access_denied")
            || lower.contains("re-authenticate")
            || lower.contains("rclone config reconnect")
        {
            return (HostStrings.format("rclone.error.auth_expired", remoteName), true)
        }

        if lower.contains("didn't find section in config file")
            || lower.contains("config file") && lower.contains("not found")
            || lower.contains("unknown remote")
            || lower.contains("directory not found")
        {
            return (HostStrings.format("rclone.error.remote_not_found", remoteName), true)
        }

        if lower.contains("no such host")
            || lower.contains("connection refused")
            || lower.contains("i/o timeout")
            || lower.contains("tls handshake")
            || lower.contains("network is unreachable")
            || lower.contains("dial tcp")
        {
            return (HostStrings.string("rclone.error.network"), false)
        }

        return (HostStrings.string("rclone.error.generic"), false)
    }

    /// Reconciles active loopback rclone backends with the user's configured `CloudRemoteEntry` list.
    /// Executed strictly serially so concurrent calls can never race or launch duplicate processes.
    public func syncRemotes(_ remotes: [CloudRemoteEntry]) {
        syncQueue.sync {
            self.syncRemotesOnQueue(remotes)
        }
    }

    /// Manually resets backoff and restarts a single cloud remote (e.g., after user re-authenticates).
    public func retryRemote(displayName: String) {
        syncQueue.async { [weak self] in
            guard let self else { return }
            guard let bin = self.resolvedExecutablePath(),
                  let managed = self.remotesByName[displayName]
            else { return }
            managed.consecutiveFailures = 0
            managed.pendingRestart?.cancel()
            managed.pendingRestart = nil
            managed.pendingConfirm?.cancel()
            managed.pendingConfirm = nil
            self.terminateManagedProcess(managed)
            self.launchManaged(managed, executablePath: bin)
            self.publishSnapshots()
        }
    }

    public func activeEndpoints() -> [RcloneBackendEndpoint] {
        stateLock.lock()
        let endpoints = Array(activeEndpointCache.values)
        stateLock.unlock()
        return endpoints.sorted {
            $0.mountName.localizedStandardCompare($1.mountName) == .orderedAscending
        }
    }

    public func endpoint(forMountName name: String) -> RcloneBackendEndpoint? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return activeEndpointCache[name]
    }

    public func stopAll() {
        syncQueue.sync {
            self.stopAllOnQueue()
        }
    }

    // MARK: - Serial Queue Implementation

    private func syncRemotesOnQueue(_ remotes: [CloudRemoteEntry]) {
        guard let bin = resolvedExecutablePath() else {
            stopAllOnQueue()
            return
        }

        // Deduplicate by `displayName` safely so duplicate names in settings NEVER crash the app.
        var desiredOrdered: [CloudRemoteEntry] = []
        var desiredByName: [String: CloudRemoteEntry] = [:]
        for remote in remotes {
            if desiredByName[remote.displayName] == nil {
                desiredByName[remote.displayName] = remote
                desiredOrdered.append(remote)
            } else {
                HostLogger.warning("Ignoring duplicate cloud remote displayName '\(remote.displayName)'", category: "media")
            }
        }

        // Stop and remove backends no longer desired or whose `remoteSpec` changed.
        for key in Array(remotesByName.keys) {
            guard let managed = remotesByName[key] else { continue }
            let desired = desiredByName[key]
            if desired == nil || desired?.remoteSpec != managed.entry.remoteSpec {
                managed.generation &+= 1
                managed.pendingRestart?.cancel()
                managed.pendingRestart = nil
                managed.pendingConfirm?.cancel()
                managed.pendingConfirm = nil
                terminateManagedProcess(managed)
                remotesByName.removeValue(forKey: key)
            }
        }

        // Start newly added remotes or remotes whose process is dead and not awaiting retry.
        for remote in desiredOrdered {
            if let existing = remotesByName[remote.displayName] {
                let running = existing.process?.isRunning ?? false
                if !running && existing.pendingRestart == nil {
                    if case .error = existing.status {
                        // Stay in user-actionable error state until user clicks retry or config changes.
                        continue
                    }
                    launchManaged(existing, executablePath: bin)
                }
            } else {
                let managed = ManagedRemote(entry: remote)
                remotesByName[remote.displayName] = managed
                launchManaged(managed, executablePath: bin)
            }
        }

        publishSnapshots()
    }

    private func stopAllOnQueue() {
        for managed in remotesByName.values {
            managed.generation &+= 1
            managed.pendingRestart?.cancel()
            managed.pendingRestart = nil
            managed.pendingConfirm?.cancel()
            managed.pendingConfirm = nil
            terminateManagedProcess(managed)
        }
        remotesByName.removeAll()
        publishSnapshots()
    }

    private func launchManaged(_ managed: ManagedRemote, executablePath: String) {
        managed.generation &+= 1
        let generation = managed.generation
        managed.pendingRestart?.cancel()
        managed.pendingRestart = nil
        managed.pendingConfirm?.cancel()
        managed.pendingConfirm = nil
        managed.stderrBuffer = StderrBuffer()
        managed.endpoint = nil
        managed.status = .starting

        guard let port = allocateEphemeralLoopbackPort() else {
            HostLogger.error("Failed to allocate loopback port for cloud remote \(managed.entry.displayName)", category: "media")
            managed.status = .error(message: HostStrings.string("rclone.error.generic"))
            return
        }

        let user = "depth3d_rclone"
        let pass = SecureRandom.hexString(byteCount: 12)
        let authRaw = Data("\(user):\(pass)".utf8).base64EncodedString()
        let authHeader = "Basic \(authRaw)"

        let proc = Process()
        let guardURL = childGuardOverride ?? ChildProcessLedger.locateChildGuard()
        // Never pass `--user` or `--pass` on CLI! Supply `RCLONE_USER` / `RCLONE_PASS` via environment
        // so credentials never appear in `ps aux` or crash logs.
        let rcloneArgs = [
            "serve", "webdav", managed.entry.remoteSpec,
            "--addr", "127.0.0.1:\(port)",
            "--read-only",
            "--vfs-cache-mode", "off",
            "--vfs-read-chunk-size", "16M",
            "--vfs-read-chunk-size-limit", "256M",
            "--buffer-size", "32M",
            "--dir-cache-time", "5m",
            "--poll-interval", "0"
        ]
        let usedGuard = ChildProcessLedger.configureLaunch(
            process: proc,
            executableURL: URL(fileURLWithPath: executablePath),
            arguments: rcloneArgs,
            guardURL: guardURL
        )

        var env = ProcessInfo.processInfo.environment
        env["RCLONE_USER"] = user
        env["RCLONE_PASS"] = pass
        proc.environment = env

        let stderrPipe = Pipe()
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = stderrPipe

        let stderrBuf = managed.stderrBuffer
        let displayName = managed.entry.displayName
        let remoteSpec = managed.entry.remoteSpec

        stderrPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty else {
                handle.readabilityHandler = nil
                return
            }
            if let chunk = String(data: data, encoding: .utf8) {
                let full = stderrBuf.append(chunk)
                let classified = Self.classifyStderr(full, remoteSpec: remoteSpec)
                if classified.isPermanentConfigError {
                    self?.syncQueue.async {
                        guard let self,
                              let current = self.remotesByName[displayName],
                              current.generation == generation
                        else { return }
                        current.status = .error(message: classified.message)
                        self.publishSnapshots()
                    }
                }
            }
        }

        proc.terminationHandler = { [weak self] finished in
            stderrPipe.fileHandleForReading.readabilityHandler = nil
            // Drain any remaining stderr bytes buffered in pipe before classifying.
            let remaining = (try? stderrPipe.fileHandleForReading.readToEnd()) ?? Data()
            if !remaining.isEmpty, let tail = String(data: remaining, encoding: .utf8) {
                _ = stderrBuf.append(tail)
            }
            self?.syncQueue.async {
                self?.handleBackendTerminated(
                    displayName: displayName,
                    generation: generation,
                    process: finished
                )
            }
        }

        do {
            try proc.run()
            let pid = proc.processIdentifier
            let endpoint = RcloneBackendEndpoint(
                mountName: managed.entry.displayName,
                remoteSpec: managed.entry.remoteSpec,
                port: port,
                authHeader: authHeader
            )
            managed.process = proc
            managed.trackedPID = pid
            managed.endpoint = endpoint
            managed.lastLaunchDate = Date()

            stateLock.lock()
            launchCounter += 1
            stateLock.unlock()

            ledger?.register(
                pid: pid,
                kind: .rclone,
                executableHint: usedGuard
                    ? (guardURL?.lastPathComponent ?? "depth3d-child-guard")
                    : (executablePath as NSString).lastPathComponent
            )

            // Mark running once process survives the brief startup probe window without permanent stderr errors.
            let confirmWork = DispatchWorkItem { [weak self] in
                guard let self,
                      let current = self.remotesByName[displayName],
                      current.generation == generation,
                      current.process?.isRunning == true
                else { return }
                if case .starting = current.status {
                    current.status = .running
                    self.publishSnapshots()
                }
            }
            managed.pendingConfirm = confirmWork
            syncQueue.asyncAfter(deadline: .now() + startupConfirmDelay, execute: confirmWork)

            HostLogger.info(
                "Started loopback cloud remote backend '\(displayName)' on 127.0.0.1:\(port) (pid=\(pid), guarded=\(usedGuard))",
                category: "media"
            )
        } catch {
            HostLogger.error("Failed to launch cloud remote backend '\(displayName)': \(error.localizedDescription)", category: "media")
            managed.status = .error(message: HostStrings.string("rclone.error.generic"))
        }
    }

    private func handleBackendTerminated(displayName: String, generation: UInt64, process: Process) {
        guard let managed = remotesByName[displayName], managed.generation == generation else {
            return
        }

        let pidToClear = managed.trackedPID
        managed.process = nil
        managed.trackedPID = -1
        managed.endpoint = nil
        managed.pendingConfirm?.cancel()
        managed.pendingConfirm = nil

        if pidToClear > 0 {
            ledger?.unregister(pid: pidToClear)
        }

        let stderrText = managed.stderrBuffer.snapshot()
        let classified = Self.classifyStderr(stderrText, remoteSpec: managed.entry.remoteSpec)
        HostLogger.warning(
            "Cloud remote backend '\(displayName)' exited (status=\(process.terminationStatus), permanent=\(classified.isPermanentConfigError))",
            category: "media"
        )

        if classified.isPermanentConfigError {
            managed.status = .error(message: classified.message)
            publishSnapshots()
            return
        }

        if let launchedAt = managed.lastLaunchDate, Date().timeIntervalSince(launchedAt) > stableRunResetSeconds {
            managed.consecutiveFailures = 0
        }
        managed.consecutiveFailures += 1
        let attempt = managed.consecutiveFailures

        if attempt > backoffDelays.count {
            HostLogger.error("Cloud remote backend '\(displayName)' crashed repeatedly (\(attempt) times); halting auto-restart", category: "media")
            managed.status = .error(message: classified.message)
            publishSnapshots()
            return
        }

        let delay = backoffDelays[attempt - 1]
        managed.status = .starting
        publishSnapshots()

        let work = DispatchWorkItem { [weak self] in
            guard let self,
                  let current = self.remotesByName[displayName],
                  current.generation == generation,
                  let bin = self.resolvedExecutablePath()
            else { return }
            current.pendingRestart = nil
            self.launchManaged(current, executablePath: bin)
            self.publishSnapshots()
        }
        managed.pendingRestart = work
        HostLogger.info("Scheduling cloud remote '\(displayName)' restart in \(delay)s (attempt \(attempt))", category: "media")
        syncQueue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func terminateManagedProcess(_ managed: ManagedRemote) {
        let proc = managed.process
        let pidToClear = managed.trackedPID
        managed.process = nil
        managed.trackedPID = -1
        managed.endpoint = nil

        if let proc, proc.isRunning {
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
        if pidToClear > 0 {
            ledger?.unregister(pid: pidToClear)
        }
    }

    private func publishSnapshots() {
        var endpoints: [String: RcloneBackendEndpoint] = [:]
        var statuses: [String: RcloneRemoteStatus] = [:]
        for (name, managed) in remotesByName {
            statuses[name] = managed.status
            if let ep = managed.endpoint, managed.process?.isRunning == true {
                endpoints[name] = ep
            }
        }

        stateLock.lock()
        activeEndpointCache = endpoints
        let changed = (statusCache != statuses)
        statusCache = statuses
        let cb = onStatusesChanged
        stateLock.unlock()

        if changed {
            cb?(statuses)
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
