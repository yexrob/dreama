import Darwin
import Foundation

/// Runtime lifecycle state of the supervised built-in streaming engine.
public enum EngineSupervisorState: Equatable, Sendable {
    case stopped
    case paused
    case starting
    case ready(encoder: String?)
    case streaming(activeSessions: Int, encoder: String?)
    case needsScreenRecording
    case portConflict(ports: [UInt16])
    case missingBinary
    case failed(reason: String)

    public var isReadyForClients: Bool {
        switch self {
        case .ready, .streaming: return true
        default: return false
        }
    }

    public var activeSessionCount: Int {
        if case .streaming(let count, _) = self { return count }
        return 0
    }
}

/// Checks whether a local TCP port is already bound by another process (e.g., Homebrew Sunshine).
public enum PortProbe {
    public static func isTCPPortOccupied(_ port: UInt16) -> Bool {
        // Check by attempting a non-reuse bind on IPv4 loopback & wildcard.
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { Darwin.close(fd) }

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian
        addr.sin_port = port.bigEndian

        let res = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return res != 0 && errno == EADDRINUSE
    }

    public static func occupiedTCPPorts(in ports: [UInt16]) -> [UInt16] {
        ports.filter { isTCPPortOccupied($0) }
    }
}

/// Supervises the built-in streaming engine child process:
/// - Writes isolated config inside `Application Support/depth3d Host/engine/`
/// - Checks port conflicts before starting so it never disturbs an existing service
/// - Initializes internal credentials cleanly without logging secrets
/// - Parses stdout/stderr logs in real time to drive host state and audio mute hooks
/// - Automatically restarts on unexpected crashes with exponential backoff
/// - Cleanly terminates the child process on `stop()` / app exit
public final class EngineSupervisor: @unchecked Sendable {
    private let lock = NSLock()
    private let paths: HostPaths
    private let adminCredentials: EngineAdminCredentials
    private let engineExecutableOverride: URL?
    private let queue = DispatchQueue(label: "dev.depth3d.host.engine.supervisor", qos: .userInitiated)

    private var process: Process?
    private var logState = EngineLogState()
    private var currentConfig: EngineConfigGenerator.Input?
    private var shouldBeRunning = false
    private var isPaused = false
    private var consecutiveFailures = 0
    private var lastLaunchDate: Date?
    private var pendingRestartWork: DispatchWorkItem?
    private var state: EngineSupervisorState = .stopped

    private var onStateChanged: (@Sendable (EngineSupervisorState, EngineLogState) -> Void)?
    private var onStreamingActivityChanged: (@Sendable (Bool) -> Void)?
    private var lastStreamingActive = false

    private static let backoffDelays: [TimeInterval] = [1.0, 2.0, 5.0, 10.0, 15.0]

    public init(
        paths: HostPaths,
        adminCredentials: EngineAdminCredentials,
        engineExecutableOverride: URL? = nil
    ) {
        self.paths = paths
        self.adminCredentials = adminCredentials
        self.engineExecutableOverride = engineExecutableOverride
    }

    deinit {
        stop()
    }

    public func setHandlers(
        onStateChanged: (@Sendable (EngineSupervisorState, EngineLogState) -> Void)?,
        onStreamingActivityChanged: (@Sendable (Bool) -> Void)?
    ) {
        lock.lock()
        self.onStateChanged = onStateChanged
        self.onStreamingActivityChanged = onStreamingActivityChanged
        let st = state
        let ls = logState
        lock.unlock()
        onStateChanged?(st, ls)
    }

    public func currentState() -> EngineSupervisorState {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    public func start(config: EngineConfigGenerator.Input) {
        queue.async { [weak self] in
            guard let self else { return }
            self.cancelRestartWork()
            self.lock.lock()
            self.currentConfig = config
            self.shouldBeRunning = true
            self.isPaused = false
            self.consecutiveFailures = 0
            self.lock.unlock()
            self.terminateCurrentProcessSync()
            self.launchIfDesired()
        }
    }

    public func restart(with config: EngineConfigGenerator.Input? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            self.cancelRestartWork()
            self.lock.lock()
            if let config {
                self.currentConfig = config
            }
            let paused = self.isPaused
            self.consecutiveFailures = 0
            self.lock.unlock()

            self.terminateCurrentProcessSync()
            if !paused {
                self.lock.lock()
                self.shouldBeRunning = true
                self.lock.unlock()
                self.launchIfDesired()
            }
        }
    }

    public func pause() {
        queue.async { [weak self] in
            guard let self else { return }
            self.cancelRestartWork()
            self.lock.lock()
            self.isPaused = true
            self.shouldBeRunning = false
            self.lock.unlock()
            self.terminateCurrentProcessSync()
            self.publishState(.paused)
            HostLogger.info("Streaming engine paused by user", category: "engine")
        }
    }

    public func resume() {
        queue.async { [weak self] in
            guard let self else { return }
            self.cancelRestartWork()
            self.lock.lock()
            self.isPaused = false
            self.shouldBeRunning = true
            self.consecutiveFailures = 0
            self.lock.unlock()
            self.launchIfDesired()
            HostLogger.info("Streaming engine resumed by user", category: "engine")
        }
    }

    public func stop() {
        cancelRestartWork()
        lock.lock()
        shouldBeRunning = false
        isPaused = false
        lock.unlock()
        terminateCurrentProcessSync()
        publishState(.stopped)
    }

    private func cancelRestartWork() {
        lock.lock()
        let work = pendingRestartWork
        pendingRestartWork = nil
        lock.unlock()
        work?.cancel()
    }

    private func launchIfDesired() {
        lock.lock()
        let wantRun = shouldBeRunning
        let config = currentConfig
        lock.unlock()

        guard wantRun, let config else { return }

        guard let executableURL = EngineConfigGenerator.locateEngineExecutable(override: engineExecutableOverride) else {
            HostLogger.error("Built-in streaming engine binary not found in bundle", category: "engine")
            publishState(.missingBinary)
            return
        }

        // Pre-flight port check: avoid fighting with another running GameStream service.
        let requiredPorts = HostPortDefaults.engineTCPPorts(basePort: config.basePort)
        let occupied = PortProbe.occupiedTCPPorts(in: requiredPorts)
        if !occupied.isEmpty {
            HostLogger.warning("Streaming engine ports already occupied: \(occupied)", category: "engine")
            publishState(.portConflict(ports: occupied))
            return
        }

        do {
            try EngineConfigGenerator.writeFiles(config)
        } catch {
            HostLogger.error("Failed to write engine configuration: \(error.localizedDescription)", category: "engine")
            publishState(.failed(reason: "无法写入串流服务配置文件"))
            return
        }

        // Initialize engine internal API credentials once if not present yet.
        if !FileManager.default.fileExists(atPath: paths.engineCredentialsFile.path) {
            if !initializeEngineCredentials(executableURL: executableURL, configURL: paths.engineConfigFile) {
                HostLogger.error("Failed to initialize internal engine credentials", category: "engine")
                publishState(.failed(reason: "无法初始化内置串流引擎凭据"))
                return
            }
        }

        lock.lock()
        self.logState = EngineLogState()
        self.lastLaunchDate = Date()
        lock.unlock()
        publishState(.starting)

        let proc = Process()
        proc.executableURL = executableURL
        proc.arguments = [paths.engineConfigFile.path]
        proc.currentDirectoryURL = paths.engineDirectory

        let outputPipe = Pipe()
        proc.standardOutput = outputPipe
        proc.standardError = outputPipe

        let lineBufferLock = NSLock()
        var pendingLineData = Data()

        outputPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let chunk = handle.availableData
            guard !chunk.isEmpty else {
                handle.readabilityHandler = nil
                return
            }
            lineBufferLock.lock()
            pendingLineData.append(chunk)
            var lines: [String] = []
            while let nl = pendingLineData.firstIndex(of: 0x0A) {
                let lineData = pendingLineData.subdata(in: 0..<nl)
                pendingLineData.removeSubrange(0...nl)
                if let str = String(data: lineData, encoding: .utf8) {
                    lines.append(str)
                }
            }
            lineBufferLock.unlock()

            for line in lines {
                self?.handleEngineLogLine(line)
            }
        }

        proc.terminationHandler = { [weak self] finished in
            outputPipe.fileHandleForReading.readabilityHandler = nil
            self?.queue.async {
                self?.handleProcessTerminated(finished)
            }
        }

        do {
            try proc.run()
            lock.lock()
            self.process = proc
            lock.unlock()
            HostLogger.info("Started built-in streaming engine (pid=\(proc.processIdentifier), port=\(config.basePort))", category: "engine")
        } catch {
            HostLogger.error("Failed to launch streaming engine: \(error.localizedDescription)", category: "engine")
            publishState(.failed(reason: "无法启动内置串流引擎"))
        }
    }

    private func initializeEngineCredentials(executableURL: URL, configURL: URL) -> Bool {
        let proc = Process()
        proc.executableURL = executableURL
        proc.arguments = [
            configURL.path,
            "--creds",
            adminCredentials.username,
            adminCredentials.password
        ]
        proc.currentDirectoryURL = paths.engineDirectory
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        do {
            try proc.run()
            proc.waitUntilExit()
            if proc.terminationStatus == 0 {
                try? FileManager.default.setAttributes(
                    [.posixPermissions: 0o600],
                    ofItemAtPath: paths.engineCredentialsFile.path
                )
                HostLogger.info("Initialized internal streaming engine credentials", category: "engine")
                return true
            }
            return false
        } catch {
            return false
        }
    }

    private func handleEngineLogLine(_ line: String) {
        guard let event = EngineLogParser.parse(line: line) else { return }

        lock.lock()
        logState.apply(event)
        let ls = logState
        let activeNow = ls.activeSessions > 0
        let streamingChanged = (activeNow != lastStreamingActive)
        lastStreamingActive = activeNow
        let streamingCB = onStreamingActivityChanged
        lock.unlock()

        if streamingChanged {
            HostLogger.info("Streaming activity changed: active=\(activeNow)", category: "engine")
            streamingCB?(activeNow)
        }

        let nextState = computeState(from: ls)
        publishState(nextState)
    }

    private func computeState(from ls: EngineLogState) -> EngineSupervisorState {
        if let failedPort = ls.failedPort {
            return .portConflict(ports: [failedPort])
        }
        if ls.screenRecordingDenied {
            return .needsScreenRecording
        }
        if ls.activeSessions > 0 {
            return .streaming(activeSessions: ls.activeSessions, encoder: ls.encoderName)
        }
        if ls.apiReady {
            if ls.encoderUnavailable {
                return .failed(reason: "未检测到可用的显示器或硬件编码器")
            }
            return .ready(encoder: ls.encoderName)
        }
        return .starting
    }

    private func handleProcessTerminated(_ terminated: Process) {
        lock.lock()
        let isCurrent = (process === terminated)
        if isCurrent {
            process = nil
        }
        let wantRun = shouldBeRunning
        let launchedAt = lastLaunchDate
        let wasStreaming = lastStreamingActive
        lastStreamingActive = false
        let streamingCB = onStreamingActivityChanged
        lock.unlock()

        if wasStreaming {
            streamingCB?(false)
        }

        guard isCurrent else { return }

        let code = terminated.terminationStatus
        let reason = terminated.terminationReason
        HostLogger.warning("Streaming engine exited (status=\(code), reason=\(reason.rawValue))", category: "engine")

        guard wantRun else { return }

        // If engine ran stably for >30s, reset crash counter.
        lock.lock()
        if let launchedAt, Date().timeIntervalSince(launchedAt) > 30 {
            consecutiveFailures = 0
        }
        consecutiveFailures += 1
        let attempt = consecutiveFailures
        lock.unlock()

        if attempt > Self.backoffDelays.count {
            HostLogger.error("Streaming engine crashed repeatedly (\(attempt) times); halting auto-restart", category: "engine")
            publishState(.failed(reason: "串流引擎多次意外退出，请检查权限后点击重试"))
            return
        }

        let delay = Self.backoffDelays[attempt - 1]
        HostLogger.info("Scheduling streaming engine restart in \(delay)s (attempt \(attempt))", category: "engine")
        publishState(.starting)

        let work = DispatchWorkItem { [weak self] in
            self?.launchIfDesired()
        }
        lock.lock()
        pendingRestartWork = work
        lock.unlock()
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func terminateCurrentProcessSync() {
        lock.lock()
        let proc = process
        process = nil
        let wasStreaming = lastStreamingActive
        lastStreamingActive = false
        let streamingCB = onStreamingActivityChanged
        lock.unlock()

        if wasStreaming {
            streamingCB?(false)
        }

        guard let proc, proc.isRunning else { return }
        let pid = proc.processIdentifier
        proc.terminate() // SIGTERM

        let deadline = Date().addingTimeInterval(2.0)
        while proc.isRunning && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.05)
        }
        if proc.isRunning {
            kill(pid, SIGKILL)
            proc.waitUntilExit()
        }
        HostLogger.info("Terminated streaming engine (pid=\(pid))", category: "engine")
    }

    private func publishState(_ next: EngineSupervisorState) {
        lock.lock()
        let changed = (state != next)
        state = next
        let ls = logState
        let cb = onStateChanged
        lock.unlock()
        if changed {
            cb?(next, ls)
        }
    }
}
