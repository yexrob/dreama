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

/// Checks whether a local TCP port is already bound by another process.
public enum PortProbe {
    public static func isTCPPortOccupied(_ port: UInt16) -> Bool {
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
/// - Writes isolated config & always-synced hashed credentials (`0600`) inside
///   `Application Support/depth3d Host/engine/` (never passes `--creds`, never touches `~/.config/sunshine`)
/// - Sets isolated `HOME` & `XDG_CONFIG_HOME` so all engine logs and appdata stay inside `engineDir`
/// - Wraps child launch with `depth3d-child-guard` (kqueue `NOTE_EXIT` + dedicated process group)
///   and records BOTH guard PID and real engine PID + kernel start time in `ChildProcessLedger`
/// - Escalates to process-group `SIGKILL` if the child ignores `SIGTERM`
/// - Parses logs accurately across multi-device streaming sessions
/// - Automatically restarts on unexpected crashes with exponential backoff
public final class EngineSupervisor: @unchecked Sendable {
    /// Immutable per-launch tracking record so delayed `terminationHandler` callbacks from a
    /// restarted/stopped old process can NEVER unregister or delete a newer launch's PIDs or pidfile (S-1).
    private final class LaunchRecord: @unchecked Sendable {
        let process: Process
        let usedGuard: Bool
        let pidFileURL: URL?
        var guardPID: Int32 = -1
        var childPID: Int32 = -1
        var cleanedUp: Bool = false

        init(process: Process, usedGuard: Bool, pidFileURL: URL?) {
            self.process = process
            self.usedGuard = usedGuard
            self.pidFileURL = pidFileURL
        }
    }

    private let lock = NSLock()
    private let paths: HostPaths
    private let adminCredentials: EngineAdminCredentials
    private let engineExecutableOverride: URL?
    private let childGuardOverride: URL?
    private let ledger: ChildProcessLedger
    private let backoffDelays: [TimeInterval]
    private let stableRunResetSeconds: TimeInterval
    private let queue = DispatchQueue(label: "dev.depth3d.host.engine.supervisor", qos: .userInitiated)
    private let queueKey = DispatchSpecificKey<UInt8>()

    private var activeLaunch: LaunchRecord?
    private var logState = EngineLogState()
    private var currentConfig: EngineConfigGenerator.Input?
    private var shouldBeRunning = false
    private var isPaused = false
    private var consecutiveFailures = 0
    private var launchCount = 0
    private var lastLaunchDate: Date?
    private var pendingRestartWork: DispatchWorkItem?
    private var state: EngineSupervisorState = .stopped

    private var onStateChanged: (@Sendable (EngineSupervisorState, EngineLogState) -> Void)?
    private var onActiveSessionCountChanged: (@Sendable (Int) -> Void)?
    private var lastReportedSessionCount = 0

    /// Deterministic test hook invoked on `queue` immediately after `try proc.run()` and BEFORE
    /// `activeLaunch` / `ledger` registration, allowing tests to inject a concurrent `stop()` in
    /// that exact race window and verify serialization prevents process leaks.
    internal var onAfterProcessRunForTesting: (() -> Void)?

    public init(
        paths: HostPaths,
        adminCredentials: EngineAdminCredentials,
        engineExecutableOverride: URL? = nil,
        childGuardOverride: URL? = nil,
        ledger: ChildProcessLedger? = nil,
        backoffDelays: [TimeInterval] = [1.0, 2.0, 5.0, 10.0, 15.0],
        stableRunResetSeconds: TimeInterval = 30.0
    ) {
        self.paths = paths
        self.adminCredentials = adminCredentials
        self.engineExecutableOverride = engineExecutableOverride
        self.childGuardOverride = childGuardOverride
        self.ledger = ledger ?? ChildProcessLedger(ledgerURL: paths.childLedgerFile)
        self.backoffDelays = backoffDelays
        self.stableRunResetSeconds = stableRunResetSeconds
        self.queue.setSpecific(key: queueKey, value: 1)
    }

    deinit {
        stop()
    }

    public func setHandlers(
        onStateChanged: (@Sendable (EngineSupervisorState, EngineLogState) -> Void)?,
        onActiveSessionCountChanged: (@Sendable (Int) -> Void)?
    ) {
        lock.lock()
        self.onStateChanged = onStateChanged
        self.onActiveSessionCountChanged = onActiveSessionCountChanged
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

    public func totalLaunchAttempts() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return launchCount
    }

    /// Cleans up any orphaned streaming engine or guard left behind by a previous crashed instance.
    @discardableResult
    public func cleanupOrphanedEngineProcesses() -> Int {
        ledger.cleanupStaleChildren(kinds: [.engine, .childGuard])
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

    /// Serializes `stop()` onto the exact same serial `queue` as `start()`/`restart()`/`pause()`/`resume()`
    /// so `stop()` can NEVER race between `proc.run()` and `activeLaunch` assignment and leak a running engine.
    public func stop() {
        let stopWork = {
            self.cancelRestartWork()
            self.lock.lock()
            self.shouldBeRunning = false
            self.isPaused = false
            self.lock.unlock()
            self.terminateCurrentProcessSync()
            self.publishState(.stopped)
        }
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            stopWork()
        } else {
            queue.sync(execute: stopWork)
        }
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
            publishState(.failed(reason: HostStrings.string("engine.error.write_config")))
            return
        }

        // Always synchronize `credentials.json` (`0600`) with `adminCredentials` on every launch
        // so rotated credentials never leave a stale hash on disk, and NEVER pass `--creds` on CLI.
        do {
            try EngineConfigGenerator.writeCredentialsFile(
                to: paths.engineCredentialsFile,
                credentials: adminCredentials
            )
        } catch {
            HostLogger.error("Failed to write internal engine credentials: \(error.localizedDescription)", category: "engine")
            publishState(.failed(reason: HostStrings.string("engine.error.write_creds")))
            return
        }

        lock.lock()
        self.logState = EngineLogState()
        self.lastLaunchDate = Date()
        self.launchCount += 1
        lock.unlock()
        publishState(.starting)

        let proc = Process()
        let guardURL = childGuardOverride ?? ChildProcessLedger.locateChildGuard()
        let usedGuard = ChildProcessLedger.configureLaunch(
            process: proc,
            executableURL: executableURL,
            arguments: [paths.engineConfigFile.path],
            guardURL: guardURL
        )
        proc.currentDirectoryURL = paths.engineDirectory

        let childPIDFileURL = paths.engineDirectory.appendingPathComponent("engine-child-\(UUID().uuidString).pid")
        try? FileManager.default.removeItem(at: childPIDFileURL)

        // Isolate `HOME` & `XDG_CONFIG_HOME` into `Application Support/depth3d Host/engine/home`
        // so the engine NEVER reads, writes, or rotates files in the user's `~/.config/sunshine`.
        var env = ProcessInfo.processInfo.environment
        env["HOME"] = paths.engineHomeDirectory.path
        env["XDG_CONFIG_HOME"] = paths.engineHomeDirectory.appendingPathComponent(".config").path
        if usedGuard {
            env["DEPTH3D_CHILD_PID_FILE"] = childPIDFileURL.path
        }
        proc.environment = env

        let outputPipe = Pipe()
        proc.standardOutput = outputPipe
        proc.standardError = outputPipe

        let launchRecord = LaunchRecord(
            process: proc,
            usedGuard: usedGuard,
            pidFileURL: usedGuard ? childPIDFileURL : nil
        )

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
                let lineData = pendingLineData[pendingLineData.startIndex..<nl]
                pendingLineData.removeSubrange(pendingLineData.startIndex...nl)
                if let str = String(data: lineData, encoding: .utf8) {
                    lines.append(str)
                }
            }
            lineBufferLock.unlock()

            for line in lines {
                self?.handleEngineLogLine(line, from: launchRecord)
            }
        }

        proc.terminationHandler = { [weak self] finished in
            outputPipe.fileHandleForReading.readabilityHandler = nil
            self?.queue.async {
                self?.handleProcessTerminated(finished, record: launchRecord)
            }
        }

        do {
            try proc.run()
            onAfterProcessRunForTesting?()
            let topPID = proc.processIdentifier
            let realChildPID: Int32
            if usedGuard {
                realChildPID = ChildProcessLedger.waitForGuardedChildPID(pidFileURL: childPIDFileURL) ?? -1
            } else {
                realChildPID = topPID
            }

            lock.lock()
            launchRecord.guardPID = usedGuard ? topPID : -1
            launchRecord.childPID = realChildPID
            self.activeLaunch = launchRecord
            lock.unlock()

            if usedGuard {
                ledger.register(
                    pid: topPID,
                    pgid: topPID,
                    kind: .childGuard,
                    executableHint: guardURL?.lastPathComponent ?? "depth3d-child-guard"
                )
                if realChildPID > 1 {
                    ledger.register(
                        pid: realChildPID,
                        pgid: realChildPID,
                        kind: .engine,
                        executableHint: executableURL.lastPathComponent
                    )
                }
            } else {
                ledger.register(
                    pid: topPID,
                    kind: .engine,
                    executableHint: executableURL.lastPathComponent
                )
            }

            HostLogger.info(
                "Started built-in streaming engine (pid=\(topPID), childPid=\(realChildPID), guarded=\(usedGuard), port=\(config.basePort))",
                category: "engine"
            )
        } catch {
            try? FileManager.default.removeItem(at: childPIDFileURL)
            HostLogger.error("Failed to launch streaming engine: \(error.localizedDescription)", category: "engine")
            publishState(.failed(reason: HostStrings.string("engine.error.launch")))
        }
    }

    private func handleEngineLogLine(_ line: String, from record: LaunchRecord) {
        guard let event = EngineLogParser.parse(line: line) else { return }

        lock.lock()
        guard activeLaunch === record else {
            lock.unlock()
            return
        }
        logState.apply(event)
        let ls = logState
        let sessionCount = ls.activeSessions
        let countChanged = (sessionCount != lastReportedSessionCount)
        lastReportedSessionCount = sessionCount
        let sessionCB = onActiveSessionCountChanged
        lock.unlock()

        if countChanged {
            HostLogger.info("Streaming active session count changed: \(sessionCount)", category: "engine")
            sessionCB?(sessionCount)
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
                return .failed(reason: HostStrings.string("engine.error.no_encoder"))
            }
            return .ready(encoder: ls.encoderName)
        }
        return .starting
    }

    private func handleProcessTerminated(_ terminated: Process, record: LaunchRecord) {
        lock.lock()
        let isCurrent = (activeLaunch === record)
        let alreadyCleaned = record.cleanedUp
        record.cleanedUp = true
        let guardPIDToClear = record.guardPID
        let childPIDToClear = record.childPID
        let pidFileToRemove = record.pidFileURL
        var hadSessions = false
        var sessionCB: (@Sendable (Int) -> Void)?
        if isCurrent {
            activeLaunch = nil
            hadSessions = lastReportedSessionCount > 0
            lastReportedSessionCount = 0
            sessionCB = onActiveSessionCountChanged
        }
        let wantRun = shouldBeRunning
        let launchedAt = lastLaunchDate
        lock.unlock()

        if !alreadyCleaned {
            if guardPIDToClear > 1 { ledger.unregister(pid: guardPIDToClear) }
            if childPIDToClear > 1 { ledger.unregister(pid: childPIDToClear) }
            if let pidFileToRemove { try? FileManager.default.removeItem(at: pidFileToRemove) }
        }
        if hadSessions {
            sessionCB?(0)
        }

        // Critical (S-1): If a restart already replaced `activeLaunch`, return immediately
        // without mutating any state or ledger entries belonging to the new process!
        guard isCurrent else { return }

        let code = terminated.terminationStatus
        let reason = terminated.terminationReason
        HostLogger.warning("Streaming engine exited (status=\(code), reason=\(reason.rawValue))", category: "engine")

        guard wantRun else { return }

        lock.lock()
        if let launchedAt, Date().timeIntervalSince(launchedAt) > stableRunResetSeconds {
            consecutiveFailures = 0
        }
        consecutiveFailures += 1
        let attempt = consecutiveFailures
        lock.unlock()

        if attempt > backoffDelays.count {
            HostLogger.error("Streaming engine crashed repeatedly (\(attempt) times); halting auto-restart", category: "engine")
            publishState(.failed(reason: HostStrings.string("engine.error.crash_loop")))
            return
        }

        let delay = backoffDelays[attempt - 1]
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
        let record = activeLaunch
        activeLaunch = nil
        var guardPIDToClear: Int32 = -1
        var childPIDToClear: Int32 = -1
        var pidFileToRemove: URL?
        var proc: Process?
        var usedGuard = false
        if let record {
            record.cleanedUp = true
            proc = record.process
            usedGuard = record.usedGuard
            guardPIDToClear = record.guardPID
            childPIDToClear = record.childPID
            pidFileToRemove = record.pidFileURL
        }
        let hadSessions = lastReportedSessionCount > 0
        lastReportedSessionCount = 0
        let sessionCB = onActiveSessionCountChanged
        lock.unlock()

        if hadSessions {
            sessionCB?(0)
        }

        // Re-check pidfile in case child PID finished writing right before stop().
        if childPIDToClear <= 1, let pidFileToRemove,
           let text = try? String(contentsOf: pidFileToRemove, encoding: .utf8),
           let parsed = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)), parsed > 1
        {
            childPIDToClear = parsed
        }

        if let proc {
            ChildProcessLedger.terminateManagedProcess(
                process: proc,
                usedGuard: usedGuard,
                realChildPID: childPIDToClear > 1 ? childPIDToClear : nil,
                graceSeconds: 2.0
            )
            HostLogger.info("Terminated streaming engine (pid=\(proc.processIdentifier), childPid=\(childPIDToClear))", category: "engine")
        }

        if guardPIDToClear > 1 { ledger.unregister(pid: guardPIDToClear) }
        if childPIDToClear > 1 { ledger.unregister(pid: childPIDToClear) }
        if let pidFileToRemove { try? FileManager.default.removeItem(at: pidFileToRemove) }
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
