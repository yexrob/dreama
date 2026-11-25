import CoreAudio
import Darwin
import Foundation
import XCTest
@testable import Depth3DHostCore

private final class MockAudioHAL: AudioHALBackend, @unchecked Sendable {
    var deviceID: AudioDeviceID? = 42
    var muted: Bool = false
    var setCalls: [Bool] = []

    func defaultOutputDeviceID() -> AudioDeviceID? { deviceID }
    func isDeviceMuted(_ deviceID: AudioDeviceID) -> Bool? {
        guard deviceID == self.deviceID else { return nil }
        return muted
    }
    func setDeviceMuted(_ deviceID: AudioDeviceID, muted: Bool) -> Bool {
        guard deviceID == self.deviceID else { return false }
        self.muted = muted
        self.setCalls.append(muted)
        return true
    }
}

private final class MockPermissionProvider: SystemPermissionProvider, @unchecked Sendable {
    var screen = false
    var accessibility = false
    var openedAnchors: [String] = []

    func isScreenRecordingGranted() -> Bool { screen }
    func requestScreenRecording() { openedAnchors.append("Privacy_ScreenCapture") }
    func isAccessibilityGranted() -> Bool { accessibility }
    func requestAccessibility() { openedAnchors.append("Privacy_Accessibility") }
    func openPrivacyPane(_ anchor: String) { openedAnchors.append(anchor) }
}

final class EngineAndSystemTests: XCTestCase {
    func testEngineConfigGenerationIsolationAndVideoToolboxPresets() throws {
        let root = URL(fileURLWithPath: "/tmp/depth3d-unit-app-support", isDirectory: true)
        let paths = HostPaths(rootDirectory: root)

        let smoothConf = EngineConfigGenerator.renderConfig(EngineConfigGenerator.Input(
            hostName: "客厅 MacBook", basePort: 57989, quality: .smooth, displayID: nil, paths: paths
        ))
        XCTAssertTrue(smoothConf.contains("max_bitrate = 20000"))
        XCTAssertTrue(smoothConf.contains("vt_coder = auto"))
        XCTAssertFalse(smoothConf.contains("\nqp ="), "VideoToolbox ignores qp; must use max_bitrate")

        let balancedConf = EngineConfigGenerator.renderConfig(EngineConfigGenerator.Input(
            hostName: "客厅 MacBook", basePort: 57989, quality: .balanced, displayID: nil, paths: paths
        ))
        XCTAssertTrue(balancedConf.contains("max_bitrate = 50000"))
        XCTAssertTrue(balancedConf.contains("vt_coder = cabac"))

        let highConf = EngineConfigGenerator.renderConfig(EngineConfigGenerator.Input(
            hostName: "客厅 MacBook", basePort: 57989, quality: .high, displayID: "2", paths: paths
        ))
        XCTAssertTrue(highConf.contains("sunshine_name = 客厅 MacBook"))
        XCTAssertTrue(highConf.contains("port = 57989"))
        XCTAssertTrue(highConf.contains("log_path = \(paths.engineLogFile.path)"))
        XCTAssertTrue(highConf.contains("encoder = videotoolbox"))
        XCTAssertTrue(highConf.contains("max_bitrate = 0"))
        XCTAssertTrue(highConf.contains("audio_sink =\n"))
        XCTAssertTrue(highConf.contains("output_name = 2"))
        XCTAssertFalse(highConf.contains(".config/sunshine"))

        // Direct credentials.json hashing (no --creds CLI args)
        let tmpCred = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-creds-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: tmpCred) }
        try EngineConfigGenerator.writeCredentialsFile(
            to: tmpCred,
            credentials: EngineAdminCredentials(username: "depth3d_internal", password: "pw"),
            salt: "SALT1234567890AB"
        )
        let attrs = try FileManager.default.attributesOfItem(atPath: tmpCred.path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        let parsed = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: tmpCred)) as? [String: String])
        XCTAssertEqual(parsed["password"], EngineConfigGenerator.sunshinePasswordHash(password: "pw", salt: "SALT1234567890AB"))
    }

    /// B1: Uses the EXACT `root.named_devices` schema emitted by real Sunshine v2026.914.233613
    /// (including string `"enabled": "true"` / `"false"`), plus legacy `named_certs` fallback.
    func testEngineStateJSONRealNamedDevicesSchemaAndDisabledFiltering() throws {
        let pem1 = "-----BEGIN CERTIFICATE-----\nAQIDBAUGBwg=\n-----END CERTIFICATE-----\n"
        let pem2 = "-----BEGIN CERTIFICATE-----\nCQoLDA0ODxA=\n-----END CERTIFICATE-----\n"
        // Exact schema captured from real depth3d-stream-engine (v2026.914.233613) state.json:
        let realSchemaObj: [String: Any] = [
            "root": [
                "uniqueid": "6DA117AB-3B35-0A23-C18C-E866CA9B5441",
                "named_devices": [
                    ["name": "depth3d", "cert": pem1, "uuid": "CLIENT-UUID-ENABLED", "enabled": "true"],
                    ["name": "DisabledHeadset", "cert": pem2, "uuid": "CLIENT-UUID-DISABLED", "enabled": "false"]
                ]
            ]
        ]
        let data = try JSONSerialization.data(withJSONObject: realSchemaObj)
        let certs = EngineConfigGenerator.parsePairedCerts(fromStateJSON: data)
        XCTAssertEqual(certs.count, 1, "Must parse root.named_devices and filter disabled entries")
        XCTAssertEqual(certs[0].uuid, "CLIENT-UUID-ENABLED")
        XCTAssertEqual(certs[0].name, "depth3d")
        XCTAssertEqual(certs[0].fingerprintSHA256, TLSIdentityManager.sha256Hex(Data([1, 2, 3, 4, 5, 6, 7, 8])))
    }

    func testEngineLogParserMultiSessionAccurateCounting() {
        var state = EngineLogState()
        for line in [
            "[2026-11-24 23:12:22.531]: Info: Creating encoder [hevc_videotoolbox]",
            "[2026-11-24 23:12:23.142]: Info: Configuration UI available at [https://localhost:57990]",
            "[2026-11-24 23:12:25.162]: Info: Successfully registered DNS service."
        ] {
            if let ev = EngineLogParser.parse(line: line) { state.apply(ev) }
        }
        XCTAssertTrue(state.apiReady)
        XCTAssertEqual(state.encoderName, "hevc_videotoolbox")

        state.apply(EngineLogParser.parse(line: "Info: New streaming session started [active sessions: 1]")!)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT CONNECTED")!)
        XCTAssertEqual(state.activeSessions, 1)

        state.apply(EngineLogParser.parse(line: "Info: New streaming session started [active sessions: 2]")!)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT CONNECTED")!)
        XCTAssertEqual(state.activeSessions, 2)

        state.apply(EngineLogParser.parse(line: "Info: CLIENT DISCONNECTED")!)
        XCTAssertEqual(state.activeSessions, 1)
        XCTAssertNil(EngineLogParser.parse(line: "Debug: Session ended"))
        state.apply(EngineLogParser.parse(line: "Info: CLIENT DISCONNECTED")!)
        XCTAssertEqual(state.activeSessions, 0)
    }

    /// A client that quits the stream makes the engine stop the app without logging
    /// `CLIENT DISCONNECTED`; the status must not stay on "streaming".
    func testQuitWithoutDisconnectLineEndsStreaming() {
        var state = EngineLogState()
        state.apply(EngineLogParser.parse(line: "Info: New streaming session started [active sessions: 1]")!)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT CONNECTED")!)
        XCTAssertEqual(state.activeSessions, 1)
        state.apply(EngineLogParser.parse(line: "[2026-11-25 07:52:46.877]: Info: Process terminated")!)
        XCTAssertEqual(state.activeSessions, 0)

        // The next session counts from one again.
        state.apply(EngineLogParser.parse(line: "Info: New streaming session started [active sessions: 1]")!)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT CONNECTED")!)
        XCTAssertEqual(state.activeSessions, 1)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT DISCONNECTED")!)
        XCTAssertEqual(state.activeSessions, 0)
    }

    func testSystemAudioControllerMultiSessionAndCrashRecovery() {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-audio-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }
        let recoveryURL = tmpDir.appendingPathComponent("audio-mute-recovery.json")

        let hal = MockAudioHAL()
        hal.muted = false
        let ctrl = SystemAudioController(backend: hal, muteWhileStreaming: true, recoveryFileURL: recoveryURL)

        ctrl.setActiveSessionCount(1)
        XCTAssertTrue(hal.muted)
        ctrl.setActiveSessionCount(2)
        ctrl.setActiveSessionCount(1)
        XCTAssertTrue(hal.muted)
        ctrl.setActiveSessionCount(0)
        XCTAssertFalse(hal.muted)
        XCTAssertFalse(FileManager.default.fileExists(atPath: recoveryURL.path))

        // Crash recovery
        try? FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        try? Data("{\"deviceID\":42,\"priorMuted\":false}".utf8).write(to: recoveryURL)
        hal.muted = true
        let recovered = SystemAudioController(backend: hal, muteWhileStreaming: true, recoveryFileURL: recoveryURL)
        XCTAssertFalse(hal.muted)
        XCTAssertFalse(FileManager.default.fileExists(atPath: recoveryURL.path))
        _ = recovered
    }

    func testPermissionMonitorDistinguishesBonjourErrorFromLocalNetworkDenied() {
        let mock = MockPermissionProvider()
        let monitor = PermissionMonitor(provider: mock)
        monitor.startMonitoring(pollInterval: 60.0) { _, _ in }
        defer { monitor.stopMonitoring() }

        monitor.updateLocalNetworkState(from: .failed(-72001))
        XCTAssertEqual(monitor.currentSnapshot().localNetwork, .checking)
        XCTAssertTrue(monitor.currentSnapshot().bonjourPublishFailed)

        monitor.updateLocalNetworkState(from: .permissionDenied)
        XCTAssertEqual(monitor.currentSnapshot().localNetwork, .denied)
        XCTAssertFalse(monitor.currentSnapshot().bonjourPublishFailed)
    }

    /// S1: Verifies `ChildProcessLedger` checks BOTH executable path AND kernel start time (`pbi_start_tvsec`)
    /// so PID reuse never kills an unrelated process, while matching orphans are terminated.
    func testChildProcessLedgerVerifiesExecutableAndStartTime() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-ledger-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let ledger = ChildProcessLedger(ledgerURL: tmp.appendingPathComponent("running-children.json"))

        let orphan = Process()
        orphan.executableURL = URL(fileURLWithPath: "/bin/sleep")
        orphan.arguments = ["60"]
        let orphanWaiter = ProcessExitWaiter(process: orphan)
        try orphan.run()
        let orphanPID = orphan.processIdentifier
        defer { if orphan.isRunning { _ = orphanWaiter.terminateAndWaitSync(timeout: 2.0) } }
        ledger.register(pid: orphanPID, kind: .engine, executableHint: "sleep")

        // Verify startTimeSec was automatically captured from kernel BSD info
        let recorded = ledger.allEntries().first(where: { $0.pid == orphanPID })
        XCTAssertNotNil(recorded?.startTimeSec)

        // Innocent process with matching executable name BUT different recorded startTime (simulating PID reuse!)
        let reusedPIDProc = Process()
        reusedPIDProc.executableURL = URL(fileURLWithPath: "/bin/sleep")
        reusedPIDProc.arguments = ["60"]
        let reusedWaiter = ProcessExitWaiter(process: reusedPIDProc)
        try reusedPIDProc.run()
        let reusedPID = reusedPIDProc.processIdentifier
        defer { if reusedPIDProc.isRunning { _ = kill(reusedPID, SIGKILL); _ = reusedWaiter.wait(timeout: 2.0) } }
        ledger.register(pid: reusedPID, startTimeSec: 12345, kind: .engine, executableHint: "sleep")

        let killedCount = ledger.cleanupStaleChildren(kinds: [.engine])
        XCTAssertEqual(killedCount, 1)

        XCTAssertTrue(orphanWaiter.wait(timeout: 3.0))
        XCTAssertFalse(orphan.isRunning)
        XCTAssertTrue(reusedPIDProc.isRunning, "PID with mismatched startTimeSec must NEVER be killed")
    }

    /// S1: Tests BOTH (a) `EngineSupervisor.stop()` against a child that IGNORES `SIGTERM`
    /// (`trap '' TERM; sleep 60`), verifying process-group `SIGKILL` leaves ZERO orphans and
    /// `running-children.json` tracks BOTH guard PID and real child PID + start time;
    /// AND (b) `depth3d-child-guard` killing a `SIGTERM`-ignoring grandchild when parent is `SIGKILL`ed!
    func testSIGTERMStubbornChildKilledCleanlyOnStopAndParentSIGKILL() throws {
        guard let guardBin = locateBuiltChildGuard() else {
            throw XCTSkip("depth3d-child-guard executable not built in expected SwiftPM path")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-sigterm-stubborn-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // Child script that traps and IGNORES SIGTERM, writes its own PID, then sleeps 60s.
        let childPidFile = tmp.appendingPathComponent("stubborn.pid")
        let stubbornScript = tmp.appendingPathComponent("stubborn-engine.sh")
        try """
        #!/bin/sh
        trap '' TERM
        echo $$ > "\(childPidFile.path)"
        /bin/sleep 60 &
        wait
        """.write(to: stubbornScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: stubbornScript.path)

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("data", isDirectory: true))
        let ledger = ChildProcessLedger(ledgerURL: paths.childLedgerFile)
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: EngineAdminCredentials(username: "u", password: "pw"),
            engineExecutableOverride: stubbornScript,
            childGuardOverride: guardBin,
            ledger: ledger
        )

        var testPort: UInt16 = 59129
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testPort)).isEmpty {
            testPort += 30
        }
        supervisor.start(config: EngineConfigGenerator.Input(
            hostName: "Stubborn Test", basePort: testPort, quality: .balanced, displayID: nil, paths: paths
        ))

        // Wait for stubborn child PID to appear
        let stubbornPID = waitForPID(at: childPidFile, timeout: 3.0)
        XCTAssertGreaterThan(stubbornPID, 1, "Stubborn child should have started")
        XCTAssertEqual(kill(stubbornPID, 0), 0)
        defer { if stubbornPID > 1 && kill(stubbornPID, 0) == 0 { _ = kill(stubbornPID, SIGKILL) } }

        // Wait briefly for ledger to register both guard and real engine child
        Thread.sleep(forTimeInterval: 0.15)
        let entries = ledger.allEntries()
        XCTAssertTrue(entries.contains(where: { $0.kind == .childGuard && $0.startTimeSec != nil }), "Ledger must track childGuard + startTime")
        XCTAssertTrue(entries.contains(where: { $0.kind == .engine && $0.pid == stubbornPID && $0.startTimeSec != nil }), "Ledger must track real engine child PID + startTime")

        // Now call supervisor.stop(): stubborn child IGNORES SIGTERM, so guard/supervisor MUST escalate to SIGKILL!
        supervisor.stop()

        let deadDeadline = Date().addingTimeInterval(3.0)
        var stubbornDead = false
        while Date() < deadDeadline {
            if kill(stubbornPID, 0) != 0 && errno == ESRCH {
                stubbornDead = true
                break
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertTrue(stubbornDead, "SIGTERM-ignoring child MUST be terminated on stop() with zero orphans")
        XCTAssertTrue(ledger.allEntries().isEmpty, "Ledger must be empty after clean stop")

        // Part B: Verify parent SIGKILL also kills a SIGTERM-ignoring grandchild via depth3d-child-guard!
        let pidFileB = tmp.appendingPathComponent("stubbornB.pid")
        let stubbornB = tmp.appendingPathComponent("stubbornB.sh")
        try "#!/bin/sh\ntrap '' TERM\necho $$ > \"\(pidFileB.path)\"\nwhile true; do /bin/sleep 1; done\n".write(
            to: stubbornB, atomically: true, encoding: .utf8
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: stubbornB.path)

        let parentScript = tmp.appendingPathComponent("parentB.sh")
        try "#!/bin/sh\n\"\(guardBin.path)\" $$ \"\(stubbornB.path)\" &\nexec /bin/sleep 60\n".write(
            to: parentScript, atomically: true, encoding: .utf8
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: parentScript.path)

        let parent = Process()
        parent.executableURL = parentScript
        let parentWaiter = ProcessExitWaiter(process: parent)
        try parent.run()
        let parentPID = parent.processIdentifier
        defer { if parent.isRunning { _ = kill(parentPID, SIGKILL); _ = parentWaiter.wait(timeout: 2.0) } }

        let grandchildPID = waitForPID(at: pidFileB, timeout: 3.0)
        XCTAssertGreaterThan(grandchildPID, 1)
        defer { if grandchildPID > 1 && kill(grandchildPID, 0) == 0 { _ = kill(grandchildPID, SIGKILL) } }

        _ = kill(parentPID, SIGKILL)
        XCTAssertTrue(parentWaiter.wait(timeout: 3.0))

        let exitDeadline = Date().addingTimeInterval(3.5)
        var grandchildDead = false
        while Date() < exitDeadline {
            if kill(grandchildPID, 0) != 0 && errno == ESRCH {
                grandchildDead = true
                break
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertTrue(grandchildDead, "depth3d-child-guard must SIGKILL SIGTERM-ignoring grandchild when parent is SIGKILLed")
    }

    /// S-1: Verifies `EngineSupervisor.restart()` does NOT allow the old exited process's delayed
    /// `terminationHandler` to erase the newly spawned guard/engine PIDs from `running-children.json`
    /// or delete the new child PID file.
    func testEngineSupervisorRestartPreservesNewProcessInLedgerAndPIDFile() throws {
        guard let guardBin = locateBuiltChildGuard() else {
            throw XCTSkip("depth3d-child-guard executable not built in expected SwiftPM path")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-restart-ledger-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let pidLog = tmp.appendingPathComponent("launches.log")
        let script = tmp.appendingPathComponent("sleeper.sh")
        try """
        #!/bin/sh
        echo $$ >> "\(pidLog.path)"
        exec /bin/sleep 60
        """.write(to: script, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: script.path)

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("data", isDirectory: true))
        let ledger = ChildProcessLedger(ledgerURL: paths.childLedgerFile)
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: EngineAdminCredentials(username: "u", password: "pw"),
            engineExecutableOverride: script,
            childGuardOverride: guardBin,
            ledger: ledger
        )
        defer { supervisor.stop() }

        var testPort: UInt16 = 59319
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testPort)).isEmpty {
            testPort += 30
        }
        let cfg = EngineConfigGenerator.Input(
            hostName: "RestartLedger", basePort: testPort, quality: .balanced, displayID: nil, paths: paths
        )

        supervisor.start(config: cfg)
        let firstDeadline = Date().addingTimeInterval(3.0)
        var firstEntries: [TrackedChildProcess] = []
        while Date() < firstDeadline {
            firstEntries = ledger.allEntries()
            if firstEntries.contains(where: { $0.kind == .childGuard }) && firstEntries.contains(where: { $0.kind == .engine }) {
                break
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        let oldGuardPID = try XCTUnwrap(firstEntries.first(where: { $0.kind == .childGuard })?.pid)
        let oldEnginePID = try XCTUnwrap(firstEntries.first(where: { $0.kind == .engine })?.pid)

        // Trigger restart — old process terminates while new process starts in the same queue turn.
        supervisor.restart(with: cfg)
        // Wait generously so the old process's delayed `terminationHandler` definitely finishes running!
        Thread.sleep(forTimeInterval: 0.9)

        let afterEntries = ledger.allEntries()
        let newGuard = afterEntries.first(where: { $0.kind == .childGuard })
        let newEngine = afterEntries.first(where: { $0.kind == .engine })
        XCTAssertNotNil(newGuard, "S-1: Restarted guard PID must remain registered in ledger after old terminationHandler runs")
        XCTAssertNotNil(newEngine, "S-1: Restarted engine PID must remain registered in ledger after old terminationHandler runs")
        XCTAssertNotEqual(newGuard?.pid, oldGuardPID)
        XCTAssertNotEqual(newEngine?.pid, oldEnginePID)
        if let g = newGuard, let e = newEngine {
            XCTAssertEqual(kill(g.pid, 0), 0)
            XCTAssertEqual(kill(e.pid, 0), 0)
            XCTAssertEqual(e.pgid, e.pid, "Guarded engine must run in its own dedicated process group")
        }
    }

    /// m-4: Verifies normal `EngineSupervisor.stop()` terminates grandchildren spawned inside the
    /// child's process group even when the immediate child exits cleanly on `SIGTERM` while its
    /// grandchild ignores `SIGTERM`!
    func testNormalStopKillsGrandchildrenInChildProcessGroup() throws {
        guard let guardBin = locateBuiltChildGuard() else {
            throw XCTSkip("depth3d-child-guard executable not built in expected SwiftPM path")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-grandchild-stop-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let grandchildPidFile = tmp.appendingPathComponent("grandchild.pid")
        let parentEngine = tmp.appendingPathComponent("spawns-grandchild.sh")
        // Immediate child spawns a grandchild that IGNORES SIGTERM, while immediate child exits immediately on SIGTERM!
        try """
        #!/bin/sh
        /bin/sh -c 'trap "" TERM; echo $$ > "\(grandchildPidFile.path)"; exec /bin/sleep 60' &
        trap 'exit 0' TERM
        while true; do /bin/sleep 1; done
        """.write(to: parentEngine, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: parentEngine.path)

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("data", isDirectory: true))
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: EngineAdminCredentials(username: "u", password: "pw"),
            engineExecutableOverride: parentEngine,
            childGuardOverride: guardBin
        )

        var testPort: UInt16 = 59419
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testPort)).isEmpty {
            testPort += 30
        }
        supervisor.start(config: EngineConfigGenerator.Input(
            hostName: "GrandchildTest", basePort: testPort, quality: .balanced, displayID: nil, paths: paths
        ))

        let grandchildPID = waitForPID(at: grandchildPidFile, timeout: 3.0)
        XCTAssertGreaterThan(grandchildPID, 1, "Grandchild should have started")
        XCTAssertEqual(kill(grandchildPID, 0), 0)
        defer { if grandchildPID > 1 && kill(grandchildPID, 0) == 0 { _ = kill(grandchildPID, SIGKILL) } }

        // Normal stop() MUST kill the stubborn grandchild inside the child's process group too!
        supervisor.stop()

        let deadDeadline = Date().addingTimeInterval(3.0)
        var grandchildDead = false
        while Date() < deadDeadline {
            if kill(grandchildPID, 0) != 0 && errno == ESRCH {
                grandchildDead = true
                break
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertTrue(grandchildDead, "m-4: Normal stop must terminate grandchildren in the child's process group")
    }

    /// Round-5 #3: Serializing `stop()` onto the exact same serial queue as `start()`/`restart()`
    /// prevents `stop()` from racing between `proc.run()` and `activeLaunch` assignment and leaking
    /// an untracked running engine.
    func testEngineSupervisorConcurrentStartAndStopNeverLeaksEngine() throws {
        guard let guardBin = locateBuiltChildGuard() else {
            throw XCTSkip("depth3d-child-guard executable not built in expected SwiftPM path")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-start-stop-race-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let spawnedLog = tmp.appendingPathComponent("spawned.pids")
        let script = tmp.appendingPathComponent("engine-race.sh")
        try """
        #!/bin/sh
        echo $$ >> "\(spawnedLog.path)"
        exec /bin/sleep 60
        """.write(to: script, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: script.path)

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("data", isDirectory: true))
        let ledger = ChildProcessLedger(ledgerURL: paths.childLedgerFile)
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: EngineAdminCredentials(username: "u", password: "pw"),
            engineExecutableOverride: script,
            childGuardOverride: guardBin,
            ledger: ledger
        )
        defer { supervisor.stop() }

        var testPort: UInt16 = 59469
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testPort)).isEmpty {
            testPort += 30
        }
        let cfg = EngineConfigGenerator.Input(
            hostName: "RaceTest", basePort: testPort, quality: .balanced, displayID: nil, paths: paths
        )

        // Round-6 minor: Deterministically pause `launchIfDesired()` RIGHT between `try proc.run()`
        // and `activeLaunch` / `ledger` registration, inject `supervisor.stop()` from another thread
        // in that exact window, and prove serialized `stop()` waits for registration and kills the child!
        let enteredRaceWindow = DispatchSemaphore(value: 0)
        let allowRegistration = DispatchSemaphore(value: 0)
        let stopFinished = DispatchSemaphore(value: 0)
        let stopReturnedPrematurely = UnsafeMutablePointer<Bool>.allocate(capacity: 1)
        stopReturnedPrematurely.initialize(to: false)
        defer { stopReturnedPrematurely.deinitialize(count: 1); stopReturnedPrematurely.deallocate() }

        supervisor.onAfterProcessRunForTesting = {
            enteredRaceWindow.signal()
            _ = allowRegistration.wait(timeout: .now() + 5.0)
        }

        supervisor.start(config: cfg)
        XCTAssertEqual(enteredRaceWindow.wait(timeout: .now() + 5.0), .success, "Hook must fire right after proc.run()")

        // Launch stop() concurrently while launchIfDesired() is paused BEFORE `activeLaunch = launchRecord`!
        DispatchQueue.global(qos: .userInitiated).async {
            supervisor.stop()
            stopFinished.signal()
        }

        // Wait 120ms: because `stop()` is serialized on `queue`, it MUST NOT return before registration finishes!
        if stopFinished.wait(timeout: .now() + 0.12) == .success {
            stopReturnedPrematurely.pointee = true
        }
        allowRegistration.signal()
        XCTAssertEqual(stopFinished.wait(timeout: .now() + 5.0), .success, "stop() must finish once launchIfDesired completes")
        XCTAssertFalse(stopReturnedPrematurely.pointee, "Serialized stop() must wait for launchIfDesired instead of racing past activeLaunch == nil")

        // Also run rapid back-to-back start()/stop() cycles without the hook.
        supervisor.onAfterProcessRunForTesting = nil
        for delayMs in [0, 5, 15, 30] {
            supervisor.start(config: cfg)
            if delayMs > 0 {
                Thread.sleep(forTimeInterval: TimeInterval(delayMs) / 1000.0)
            }
            supervisor.stop()
        }

        // Allow kernel brief drain, then confirm ZERO spawned processes survive and ledger is empty!
        Thread.sleep(forTimeInterval: 0.25)
        let rawPIDs = (try? String(contentsOf: spawnedLog, encoding: .utf8)) ?? ""
        let pids = rawPIDs
            .components(separatedBy: .newlines)
            .compactMap { Int32($0.trimmingCharacters(in: .whitespaces)) }
            .filter { $0 > 1 }
        for pid in pids {
            if kill(pid, 0) == 0 {
                _ = kill(pid, SIGKILL)
                XCTFail("Round-5 #3: Engine child PID \(pid) leaked after serialized start()/stop()!")
            }
        }
        XCTAssertTrue(ledger.allEntries().isEmpty, "Ledger must be empty after serialized stop()")
    }

    /// m1: Verifies `EngineSupervisor` always overwrites stale `credentials.json` on launch.
    func testEngineSupervisorAlwaysSynchronizesStaleCredentialsFile() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-stale-creds-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("data", isDirectory: true))
        try paths.ensureDirectoriesExist()

        // Seed stale credentials.json from an older password
        let oldCreds = EngineAdminCredentials(username: "depth3d_internal", password: "OLD_PASSWORD_111")
        try EngineConfigGenerator.writeCredentialsFile(to: paths.engineCredentialsFile, credentials: oldCreds, salt: "OLDSALT123456789")

        // Fake engine script that exits cleanly after 0.1s
        let fakeEngine = tmp.appendingPathComponent("quick.sh")
        try "#!/bin/sh\nsleep 0.2\n".write(to: fakeEngine, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: fakeEngine.path)

        let newCreds = EngineAdminCredentials(username: "depth3d_internal", password: "NEW_ROTATED_PW_999")
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: newCreds,
            engineExecutableOverride: fakeEngine,
            backoffDelays: []
        )
        defer { supervisor.stop() }

        var testPort: UInt16 = 59219
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testPort)).isEmpty {
            testPort += 30
        }
        supervisor.start(config: EngineConfigGenerator.Input(
            hostName: "CredSync", basePort: testPort, quality: .balanced, displayID: nil, paths: paths
        ))
        Thread.sleep(forTimeInterval: 0.35)

        let parsed = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: paths.engineCredentialsFile)) as? [String: String])
        let salt = try XCTUnwrap(parsed["salt"])
        XCTAssertEqual(
            parsed["password"],
            EngineConfigGenerator.sunshinePasswordHash(password: newCreds.password, salt: salt),
            "EngineSupervisor must overwrite stale credentials.json with current adminCredentials on every launch"
        )
    }

    func testEngineSupervisorExponentialBackoffRestartOnCrash() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-backoff-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let fakeEngine = tmp.appendingPathComponent("fake-engine.sh")
        try "#!/bin/sh\necho '[2026-11-25 00:00:00.000]: Error: simulated crash'\nexit 1\n".write(
            to: fakeEngine, atomically: true, encoding: .utf8
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: fakeEngine.path)

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("data", isDirectory: true))
        let creds = EngineAdminCredentials(username: "depth3d_internal", password: "testpassword1234")
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: creds,
            engineExecutableOverride: fakeEngine,
            backoffDelays: [0.05, 0.08]
        )
        defer { supervisor.stop() }

        let failedExp = expectation(description: "Engine enters failed state after exhausting backoff retries")
        supervisor.setHandlers(
            onStateChanged: { state, _ in
                if case .failed = state { failedExp.fulfill() }
            },
            onActiveSessionCountChanged: nil
        )

        var testBasePort: UInt16 = 59189
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testBasePort)).isEmpty {
            testBasePort += 30
        }
        supervisor.start(config: EngineConfigGenerator.Input(
            hostName: "Test Mac", basePort: testBasePort, quality: .balanced, displayID: nil, paths: paths
        ))

        wait(for: [failedExp], timeout: 5.0)
        XCTAssertEqual(supervisor.totalLaunchAttempts(), 3)
        if case .failed(let reason) = supervisor.currentState() {
            XCTAssertEqual(reason, HostStrings.string("engine.error.crash_loop"))
        } else {
            XCTFail("Expected .failed state, got \(supervisor.currentState())")
        }
    }

    func testRealEngineNeverTouchesUserSunshineConfigDirectory() async throws {
        guard let realEngine = locateStagedRealEngine() else {
            throw XCTSkip("Staged depth3d-stream-engine binary not present")
        }

        let userSunshineDir = URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent(".config/sunshine", isDirectory: true)
        let beforeSnapshot = snapshotDirectory(userSunshineDir)

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-real-engine-iso-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        var testBasePort: UInt16 = 59789
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testBasePort)).isEmpty {
            testBasePort += 30
        }

        let paths = HostPaths(rootDirectory: tmp)
        let creds = EngineAdminCredentials(username: "depth3d_internal", password: SecureRandom.hexString(byteCount: 16))
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: creds,
            engineExecutableOverride: realEngine
        )
        defer { supervisor.stop() }

        let apiClient = EngineAPIClient(
            apiPort: HostPortDefaults.engineAPIPort(basePort: testBasePort),
            credentials: creds
        )

        supervisor.start(config: EngineConfigGenerator.Input(
            hostName: "Depth3D Iso Test", basePort: testBasePort, quality: .balanced, displayID: nil, paths: paths
        ))

        let deadline = Date().addingTimeInterval(12.0)
        var authenticated = false
        while Date() < deadline {
            if let _ = try? await apiClient.listPairedClients() {
                authenticated = true
                break
            }
            try await Task.sleep(nanoseconds: 250_000_000)
        }

        XCTAssertTrue(authenticated)
        XCTAssertTrue(FileManager.default.fileExists(atPath: paths.engineLogFile.path))

        supervisor.stop()
        try await Task.sleep(nanoseconds: 300_000_000)

        let afterSnapshot = snapshotDirectory(userSunshineDir)
        XCTAssertEqual(beforeSnapshot, afterSnapshot)
    }

    func testLocalizedCoreStringsResolveInChinese() {
        let requiredKeys = [
            "protocol.version_incompatible",
            "protocol.stream.no_screen_recording",
            "protocol.stream.port_conflict",
            "protocol.stream.paused",
            "protocol.stream.cleanup_failed",
            "engine.error.crash_loop",
            "rclone.error.auth_expired",
            "rclone.error.remote_not_found",
            "status.bonjour_error.title",
            "pair.prompt.source_ip",
            "pair.prompt.identity_changed_warning",
            "devices.needs_reconnect_upgrade",
            "media.cloud.status.paused",
            "media.cloud.status.not_installed"
        ]
        for key in requiredKeys {
            let value = HostStrings.string(key)
            XCTAssertNotEqual(value, key, "Missing localization for key '\(key)'")
            XCTAssertFalse(value.isEmpty)
        }
    }

    func testHostStateStorePersistenceAndCredentialRotation() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-store-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let store = try HostStateStore(paths: HostPaths(rootDirectory: tmp))
        XCTAssertEqual(try store.loadOrCreateIdentity().id, try store.loadOrCreateIdentity().id)

        let davCreds1 = try store.loadOrCreateWebDAVCredentials()
        let rotated = try store.rotateWebDAVCredentials()
        XCTAssertNotEqual(rotated.pass, davCreds1.pass)
        XCTAssertEqual(try store.loadOrCreateWebDAVCredentials(), rotated)

        // Round-5 minor: Legacy paired-devices.json with two entries sharing the same fingerprint
        // (even with different casing / deviceId) merges cleanly keeping the most recent record!
        let olderDate = Date(timeIntervalSince1970: 1_700_000_000)
        let newerDate = Date(timeIntervalSince1970: 1_750_000_000)
        let rawLegacyJSON = """
        [
          {
            "autoAllow": true,
            "certFingerprint": "aabbccdd1122",
            "deviceId": "old-dev-id",
            "engineUUID": "OLD-UUID",
            "lastSeenAt": "2023-11-14T22:13:20Z",
            "name": "Old Name",
            "pairedAt": "2023-11-14T22:13:20Z"
          },
          {
            "autoAllow": false,
            "certFingerprint": "AABBCCDD1122",
            "deviceId": "new-dev-id",
            "engineUUID": "NEW-UUID",
            "lastSeenAt": "2025-06-15T12:26:40Z",
            "name": "Newer Name",
            "pairedAt": "2025-06-15T12:26:40Z"
          }
        ]
        """
        try Data(rawLegacyJSON.utf8).write(to: store.paths.pairedDevicesFile, options: .atomic)
        let migrated = store.loadPairedDevices()
        XCTAssertEqual(migrated.count, 1, "Duplicate-fingerprint legacy records must merge on load")
        XCTAssertEqual(migrated[0].certFingerprint, "AABBCCDD1122")
        XCTAssertEqual(migrated[0].name, "Newer Name")
        XCTAssertEqual(migrated[0].engineUUID, "NEW-UUID")
        XCTAssertGreaterThan(newerDate, olderDate)

        // Round-6 minor: Merging two nil-fingerprint legacy records sharing `deviceId` preserves BOTH engineUUIDs!
        let dupLegacyJSON = """
        [
          {
            "autoAllow": false,
            "deviceId": "legacy-dup-dev",
            "engineUUID": "LEG-UUID-OLD",
            "lastSeenAt": "2023-11-14T22:13:20Z",
            "name": "Old Legacy",
            "pairedAt": "2023-11-14T22:13:20Z"
          },
          {
            "autoAllow": false,
            "deviceId": "legacy-dup-dev",
            "engineUUID": "LEG-UUID-NEW",
            "lastSeenAt": "2025-06-15T12:26:40Z",
            "name": "New Legacy",
            "pairedAt": "2025-06-15T12:26:40Z"
          }
        ]
        """
        try Data(dupLegacyJSON.utf8).write(to: store.paths.pairedDevicesFile, options: .atomic)
        let mergedLegacy = store.loadPairedDevices()
        XCTAssertEqual(mergedLegacy.count, 1)
        XCTAssertNil(mergedLegacy[0].certFingerprint)
        XCTAssertEqual(mergedLegacy[0].name, "New Legacy")
        XCTAssertEqual(Set(mergedLegacy[0].allLegacyEngineUUIDs), Set(["LEG-UUID-NEW", "LEG-UUID-OLD"]))
    }

    /// m-1 (upgraded m6): Verifies `fetch-engine.sh --verify-only` validates against the
    /// version-controlled manifest in `host/scripts/engine-manifest-arm64.json` (NEVER trusting
    /// the writable `ENGINE-MANIFEST.json` inside `build/`), AND verifies symlink targets!
    func testFetchEngineVerifyOnlyUsesVersionControlledManifestAndChecksSymlinks() throws {
        let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        let scriptCandidates = [
            cwd.appendingPathComponent("scripts/fetch-engine.sh"),
            cwd.appendingPathComponent("host/scripts/fetch-engine.sh")
        ]
        let stageCandidates = [
            cwd.appendingPathComponent("build/engine-arm64/stream-engine"),
            cwd.appendingPathComponent("host/build/engine-arm64/stream-engine")
        ]
        guard let scriptURL = scriptCandidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) }),
              let stageURL = stageCandidates.first(where: { FileManager.default.fileExists(atPath: $0.appendingPathComponent("MacOS/depth3d-stream-engine").path) })
        else {
            throw XCTSkip("Staged engine directory or fetch-engine.sh not present")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-m1-verify-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let copiedStage = tmp.appendingPathComponent("stream-engine", isDirectory: true)
        try FileManager.default.copyItem(at: stageURL, to: copiedStage)

        func runVerifyOnly() throws -> Int32 {
            let res = try TestProcessRunner.run(
                executable: "/bin/bash",
                arguments: [scriptURL.path, "--arch", "arm64", "--out", copiedStage.path, "--verify-only"],
                captureStdout: false,
                captureStderr: false,
                timeout: 15.0
            )
            XCTAssertFalse(res.timedOut)
            return res.exitCode
        }

        // 1. Intact staged copy passes full file + symlink verification against repo-pinned manifest
        XCTAssertEqual(try runVerifyOnly(), 0, "Untampered staged engine copy must pass --verify-only")

        // 2. Unexpected injected file -> MUST fail
        let strayURL = copiedStage.appendingPathComponent("injected.dylib")
        try Data("evil".utf8).write(to: strayURL)
        XCTAssertNotEqual(try runVerifyOnly(), 0)
        try FileManager.default.removeItem(at: strayURL)

        // 3. Symlink tampering (replace a Qt framework symlink with a different target) -> MUST fail!
        let symlinkURL = copiedStage.appendingPathComponent("Frameworks/QtCore.framework/QtCore")
        let originalTarget = try FileManager.default.destinationOfSymbolicLink(atPath: symlinkURL.path)
        try FileManager.default.removeItem(at: symlinkURL)
        try FileManager.default.createSymbolicLink(atPath: symlinkURL.path, withDestinationPath: "/tmp/evil-target")
        XCTAssertNotEqual(try runVerifyOnly(), 0, "m-1: Tampered symlink target MUST fail --verify-only")
        try FileManager.default.removeItem(at: symlinkURL)
        try FileManager.default.createSymbolicLink(atPath: symlinkURL.path, withDestinationPath: originalTarget)
        XCTAssertEqual(try runVerifyOnly(), 0)

        // 4. Tamper 1 byte in binary AND rewrite the writable `ENGINE-MANIFEST.json` inside stage dir
        //    to match the tampered hash -> MUST STILL FAIL because `--verify-only` checks repo manifest!
        let binURL = copiedStage.appendingPathComponent("MacOS/depth3d-stream-engine")
        var bytes = try Data(contentsOf: binURL)
        bytes[bytes.count - 1] ^= 0xFF
        try bytes.write(to: binURL, options: .atomic)
        let tamperedHash = TLSIdentityManager.sha256Hex(bytes).lowercased()
        let stageManifestURL = copiedStage.appendingPathComponent("ENGINE-MANIFEST.json")
        if let raw = try? Data(contentsOf: stageManifestURL),
           var obj = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any],
           var files = obj["files"] as? [String: String]
        {
            files["MacOS/depth3d-stream-engine"] = tamperedHash
            obj["files"] = files
            if let forged = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted]) {
                try forged.write(to: stageManifestURL, options: .atomic)
            }
        }
        XCTAssertNotEqual(
            try runVerifyOnly(),
            0,
            "m-1: Tampering BOTH binary and writable stage ENGINE-MANIFEST.json MUST STILL FAIL against repo-pinned manifest"
        )
    }

    private func locateBuiltChildGuard() -> URL? {
        let candidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent(".build/debug/depth3d-child-guard"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent(".build/arm64-apple-macosx/debug/depth3d-child-guard"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent(".build/out/Products/Debug/depth3d-child-guard")
        ]
        return candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) })
    }

    private func locateStagedRealEngine() -> URL? {
        let candidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("host/build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine")
        ]
        return candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) })
    }

    private func waitForPID(at file: URL, timeout: TimeInterval) -> Int32 {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let text = try? String(contentsOf: file, encoding: .utf8),
               let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)), pid > 1
            {
                return pid
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        return -1
    }

    private func snapshotDirectory(_ dir: URL) -> [String: String] {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else {
            return [:]
        }
        var result: [String: String] = [:]
        for item in items {
            let vals = try? item.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            let size = vals?.fileSize ?? -1
            let mtime = vals?.contentModificationDate?.timeIntervalSince1970 ?? 0
            result[item.lastPathComponent] = "\(size):\(String(format: "%.3f", mtime))"
        }
        return result
    }
}
