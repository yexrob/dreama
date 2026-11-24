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
        XCTAssertTrue(highConf.contains("origin_web_ui_allowed = pc"))
        XCTAssertTrue(highConf.contains("system_tray = disabled"))
        XCTAssertTrue(highConf.contains("encoder = videotoolbox"))
        XCTAssertTrue(highConf.contains("hevc_mode = 0"))
        XCTAssertTrue(highConf.contains("vt_realtime = enabled"))
        XCTAssertTrue(highConf.contains("max_bitrate = 0"))
        XCTAssertTrue(highConf.contains("stream_audio = enabled"))
        XCTAssertTrue(highConf.contains("audio_sink =\n"))
        XCTAssertTrue(highConf.contains("output_name = 2"))
        XCTAssertTrue(highConf.contains("file_state = \(paths.engineStateFile.path)"))
        XCTAssertFalse(highConf.contains(".config/sunshine"), "Must never reference user ~/.config/sunshine")

        // Verify direct credentials.json hashing (no --creds CLI args)
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
        XCTAssertEqual(parsed["username"], "depth3d_internal")
        XCTAssertEqual(parsed["salt"], "SALT1234567890AB")
        XCTAssertEqual(parsed["password"], EngineConfigGenerator.sunshinePasswordHash(password: "pw", salt: "SALT1234567890AB"))
        XCTAssertEqual(parsed["password"]?.count, 64)
    }

    func testEngineStateJSONCertParsing() throws {
        let pem = """
        -----BEGIN CERTIFICATE-----
        AQIDBAUGBwg=
        -----END CERTIFICATE-----
        """
        let stateObj: [String: Any] = [
            "root": [
                "uniqueid": "HOST-123",
                "named_certs": [
                    ["uuid": "CLIENT-UUID-1", "name": "PICO 4", "cert": pem]
                ]
            ]
        ]
        let data = try JSONSerialization.data(withJSONObject: stateObj)
        let certs = EngineConfigGenerator.parsePairedCerts(fromStateJSON: data)
        XCTAssertEqual(certs.count, 1)
        XCTAssertEqual(certs[0].uuid, "CLIENT-UUID-1")
        XCTAssertEqual(certs[0].name, "PICO 4")
        XCTAssertEqual(certs[0].fingerprintSHA256, TLSIdentityManager.sha256Hex(Data([1, 2, 3, 4, 5, 6, 7, 8])))
    }

    /// Verifies multi-device session counting never double-counts `active sessions` + `CLIENT CONNECTED`
    /// and never double-decrements `CLIENT DISCONNECTED` + debug `Session ended`.
    func testEngineLogParserMultiSessionAccurateCounting() {
        var state = EngineLogState()

        let startupLines = [
            "[2026-11-24 23:12:22.531]: Info: Creating encoder [hevc_videotoolbox]",
            "[2026-11-24 23:12:23.142]: Info: Configuration UI available at [https://localhost:57990]",
            "[2026-11-24 23:12:25.162]: Info: Successfully registered DNS service.",
            "[2026-11-24 23:13:01.000]: Info: Using macOS system audio tap for capture.",
            "[2026-11-24 23:13:01.010]: Info: Core Audio tap: Host audio disabled (muted)"
        ]
        for line in startupLines {
            if let ev = EngineLogParser.parse(line: line) { state.apply(ev) }
        }
        XCTAssertTrue(state.apiReady)
        XCTAssertEqual(state.encoderName, "hevc_videotoolbox")
        XCTAssertEqual(state.activeSessions, 0)

        // Device 1 connects: RTSP reports active sessions: 1, then stream logs CLIENT CONNECTED
        state.apply(EngineLogParser.parse(line: "Info: New streaming session started [active sessions: 1]")!)
        XCTAssertEqual(state.activeSessions, 1)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT CONNECTED")!)
        XCTAssertEqual(state.activeSessions, 1, "CLIENT CONNECTED immediately after RTSP launch must not double-count")

        // Device 2 connects concurrently
        state.apply(EngineLogParser.parse(line: "Info: New streaming session started [active sessions: 2]")!)
        XCTAssertEqual(state.activeSessions, 2)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT CONNECTED")!)
        XCTAssertEqual(state.activeSessions, 2)

        // Device 1 disconnects (with debug "Session ended" ignored so no double-decrement!)
        state.apply(EngineLogParser.parse(line: "Info: CLIENT DISCONNECTED")!)
        XCTAssertEqual(state.activeSessions, 1)
        XCTAssertNil(EngineLogParser.parse(line: "Debug: Session ended"), "Debug Session ended must not double-decrement")

        // Device 2 disconnects -> now 0
        state.apply(EngineLogParser.parse(line: "Info: CLIENT DISCONNECTED")!)
        XCTAssertEqual(state.activeSessions, 0)

        XCTAssertEqual(
            EngineLogParser.parse(line: "[2026-11-24 23:12:22.531]: Error: No screen capture permission!"),
            .screenRecordingDenied
        )
        XCTAssertEqual(
            EngineLogParser.parse(line: "Error: Couldn't bind Video server to port [47998]"),
            .portBindFailed(47998)
        )
        XCTAssertEqual(
            EngineLogParser.parse(line: "Error: Failed to setup system audio tap."),
            .audioTapFailed
        )
    }

    func testSystemAudioControllerMultiSessionAndCrashRecovery() {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-audio-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }
        let recoveryURL = tmpDir.appendingPathComponent("audio-mute-recovery.json")

        let hal = MockAudioHAL()
        hal.muted = false
        let ctrl = SystemAudioController(backend: hal, muteWhileStreaming: true, recoveryFileURL: recoveryURL)

        // Multi-session: 0 -> 1 mutes; 1 -> 2 stays muted; 2 -> 1 stays muted; 1 -> 0 unmutes
        ctrl.setActiveSessionCount(1)
        XCTAssertTrue(hal.muted)
        XCTAssertTrue(FileManager.default.fileExists(atPath: recoveryURL.path))
        ctrl.setActiveSessionCount(2)
        XCTAssertTrue(hal.muted)
        XCTAssertEqual(hal.setCalls, [true])
        ctrl.setActiveSessionCount(1)
        XCTAssertTrue(hal.muted, "Must stay muted while 1 session is still active")
        ctrl.setActiveSessionCount(0)
        XCTAssertFalse(hal.muted, "Must restore speakers when last session disconnects")
        XCTAssertFalse(FileManager.default.fileExists(atPath: recoveryURL.path))

        // Mid-stream toggle
        ctrl.setActiveSessionCount(1)
        XCTAssertTrue(hal.muted)
        ctrl.setMuteWhileStreaming(false)
        XCTAssertFalse(hal.muted)
        ctrl.setMuteWhileStreaming(true)
        XCTAssertTrue(hal.muted)
        ctrl.setActiveSessionCount(0)
        XCTAssertFalse(hal.muted)

        // Already muted before stream -> stays muted after stream ends
        hal.muted = true
        hal.setCalls.removeAll()
        ctrl.setActiveSessionCount(1)
        ctrl.setActiveSessionCount(0)
        XCTAssertTrue(hal.muted)
        XCTAssertEqual(hal.setCalls, [])

        // Crash recovery test: leave recovery marker on disk with hal still muted, then launch new controller
        hal.muted = false
        hal.setCalls.removeAll()
        let crashMarker = Data("{\"deviceID\":42,\"priorMuted\":false}".utf8)
        try? FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        try? crashMarker.write(to: recoveryURL)
        hal.muted = true // simulating Mac still muted after crash

        let recoveredCtrl = SystemAudioController(backend: hal, muteWhileStreaming: true, recoveryFileURL: recoveryURL)
        XCTAssertFalse(hal.muted, "Next startup must restore Mac speaker mute state after unclean shutdown")
        XCTAssertFalse(FileManager.default.fileExists(atPath: recoveryURL.path))
        _ = recoveredCtrl
    }

    func testPermissionMonitorDistinguishesBonjourErrorFromLocalNetworkDenied() {
        let mock = MockPermissionProvider()
        let monitor = PermissionMonitor(provider: mock)

        monitor.startMonitoring(pollInterval: 60.0) { _, _ in }
        defer { monitor.stopMonitoring() }

        // Non-permission Bonjour failure must NOT mark localNetwork as denied
        monitor.updateLocalNetworkState(from: .failed(-72001))
        XCTAssertEqual(monitor.currentSnapshot().localNetwork, .checking)
        XCTAssertTrue(monitor.currentSnapshot().bonjourPublishFailed)

        // Policy denial marks localNetwork as denied and clears generic bonjourPublishFailed
        monitor.updateLocalNetworkState(from: .permissionDenied)
        XCTAssertEqual(monitor.currentSnapshot().localNetwork, .denied)
        XCTAssertFalse(monitor.currentSnapshot().bonjourPublishFailed)

        // Published clears both
        mock.screen = true
        mock.accessibility = true
        monitor.updateLocalNetworkState(from: .published)
        XCTAssertTrue(monitor.currentSnapshot().allGranted)
        XCTAssertFalse(monitor.currentSnapshot().bonjourPublishFailed)
    }

    func testChildProcessLedgerCleansOrphansSafelyByExecutableHint() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-ledger-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let ledger = ChildProcessLedger(ledgerURL: tmp.appendingPathComponent("running-children.json"))

        // 1. Spawn a real child `/bin/sleep 60` recorded with matching hint -> must be terminated
        let orphan = Process()
        orphan.executableURL = URL(fileURLWithPath: "/bin/sleep")
        orphan.arguments = ["60"]
        try orphan.run()
        let orphanPID = orphan.processIdentifier
        defer { if orphan.isRunning { kill(orphanPID, SIGKILL) } }
        ledger.register(pid: orphanPID, kind: .engine, executableHint: "sleep")

        // 2. Spawn another `/bin/sleep 60` recorded with WRONG hint (simulating PID reuse) -> must NOT be killed
        let innocent = Process()
        innocent.executableURL = URL(fileURLWithPath: "/bin/sleep")
        innocent.arguments = ["60"]
        try innocent.run()
        let innocentPID = innocent.processIdentifier
        defer {
            if innocent.isRunning {
                kill(innocentPID, SIGKILL)
                innocent.waitUntilExit()
            }
        }
        ledger.register(pid: innocentPID, kind: .engine, executableHint: "definitely-unrelated-binary")

        let killedCount = ledger.cleanupStaleChildren(kinds: [.engine])
        XCTAssertEqual(killedCount, 1)

        orphan.waitUntilExit()
        XCTAssertFalse(orphan.isRunning, "Matching orphaned child must be terminated")
        XCTAssertTrue(innocent.isRunning, "Non-matching PID must NEVER be terminated")
        XCTAssertTrue(ledger.allEntries().isEmpty)
    }

    /// Real `depth3d-child-guard` kqueue `NOTE_EXIT` watchdog test:
    /// Spawns a sub-parent that launches `depth3d-child-guard <parent_pid> /bin/sleep 60`,
    /// then sends `SIGKILL` to the sub-parent and verifies both guard & grandchild exit promptly.
    func testChildGuardTerminatesGrandchildOnParentSIGKILL() throws {
        let guardCandidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent(".build/debug/depth3d-child-guard"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent(".build/arm64-apple-macosx/debug/depth3d-child-guard"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent(".build/out/Products/Debug/depth3d-child-guard")
        ]
        guard let guardBin = guardCandidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) }) else {
            throw XCTSkip("depth3d-child-guard executable not built in expected SwiftPM build path")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-guard-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let childPidFile = tmp.appendingPathComponent("child.pid")
        let launcherScript = tmp.appendingPathComponent("parent.sh")
        // Sub-parent launches depth3d-child-guard watching $$, wrapping a wrapper that records grandchild PID then execs sleep 60
        let grandchildScript = tmp.appendingPathComponent("grandchild.sh")
        try "#!/bin/sh\necho $$ > \"\(childPidFile.path)\"\nexec /bin/sleep 60\n".write(
            to: grandchildScript, atomically: true, encoding: .utf8
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: grandchildScript.path)

        try "#!/bin/sh\n\"\(guardBin.path)\" $$ \"\(grandchildScript.path)\" &\nexec /bin/sleep 60\n".write(
            to: launcherScript, atomically: true, encoding: .utf8
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: launcherScript.path)

        let parent = Process()
        parent.executableURL = launcherScript
        try parent.run()
        let parentPID = parent.processIdentifier
        defer {
            if parent.isRunning { kill(parentPID, SIGKILL) }
        }

        // Wait up to 3s for grandchild to record its PID
        var grandchildPID: Int32 = -1
        let startDeadline = Date().addingTimeInterval(3.0)
        while Date() < startDeadline {
            if let text = try? String(contentsOf: childPidFile, encoding: .utf8),
               let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)), pid > 1
            {
                grandchildPID = pid
                break
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertGreaterThan(grandchildPID, 1, "Grandchild should have launched under depth3d-child-guard")
        XCTAssertEqual(kill(grandchildPID, 0), 0, "Grandchild should be running before parent SIGKILL")
        defer {
            if grandchildPID > 1 && kill(grandchildPID, 0) == 0 {
                _ = kill(grandchildPID, SIGKILL)
            }
        }

        // Simulate abrupt App crash / SIGKILL
        _ = kill(parentPID, SIGKILL)
        parent.waitUntilExit()

        // Verify depth3d-child-guard kills grandchild promptly on kqueue NOTE_EXIT
        let exitDeadline = Date().addingTimeInterval(3.0)
        var grandchildExited = false
        while Date() < exitDeadline {
            if kill(grandchildPID, 0) != 0 && errno == ESRCH {
                grandchildExited = true
                break
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertTrue(grandchildExited, "depth3d-child-guard must terminate child when parent dies with SIGKILL")
    }

    func testEngineSupervisorExponentialBackoffRestartOnCrash() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-backoff-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // Fake engine script that immediately crashes with exit code 1
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
                if case .failed = state {
                    failedExp.fulfill()
                }
            },
            onActiveSessionCountChanged: nil
        )

        var testBasePort: UInt16 = 59189
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: testBasePort)).isEmpty {
            testBasePort += 30
        }

        let config = EngineConfigGenerator.Input(
            hostName: "Test Mac",
            basePort: testBasePort,
            quality: .balanced,
            displayID: nil,
            paths: paths
        )
        supervisor.start(config: config)

        wait(for: [failedExp], timeout: 5.0)
        // Initial launch + 2 backoff retries = 3 attempts
        XCTAssertEqual(supervisor.totalLaunchAttempts(), 3)
        if case .failed(let reason) = supervisor.currentState() {
            XCTAssertEqual(reason, HostStrings.string("engine.error.crash_loop"))
        } else {
            XCTFail("Expected .failed state, got \(supervisor.currentState())")
        }
    }

    /// REAL ENGINE ISOLATION TEST (Review Item #3):
    /// Launches the actual built-in streaming engine binary on an isolated port & data directory,
    /// authenticates via directly written `credentials.json` (zero `--creds`), verifies logs land
    /// strictly inside `paths.engineLogFile`, and verifies `~/.config/sunshine` is completely unchanged!
    func testRealEngineNeverTouchesUserSunshineConfigDirectory() async throws {
        // Locate staged or bundled real engine binary in this workspace
        let candidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent("build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent("build/depth3d Host.app/Contents/Helpers/depth3d-stream-engine"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent("host/build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine")
        ]
        guard let realEngine = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) }) else {
            throw XCTSkip("Staged depth3d-stream-engine binary not present; run host/scripts/fetch-engine.sh first")
        }

        // Snapshot user's real ~/.config/sunshine before launching engine
        let userSunshineDir = URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent(".config/sunshine", isDirectory: true)
        let beforeSnapshot = snapshotDirectory(userSunshineDir)

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-real-engine-iso-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // Pick an unoccupied high port group so we never interfere with any running instance
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
            hostName: "Depth3D Iso Test",
            basePort: testBasePort,
            quality: .balanced,
            displayID: nil,
            paths: paths
        ))

        // Wait up to 12s for the real engine's management HTTPS API to accept our directly written credentials.json
        let deadline = Date().addingTimeInterval(12.0)
        var authenticated = false
        while Date() < deadline {
            if let _ = try? await apiClient.listPairedClients() {
                authenticated = true
                break
            }
            try await Task.sleep(nanoseconds: 250_000_000)
        }

        XCTAssertTrue(authenticated, "Real engine must start and authenticate using directly written credentials.json (no --creds)")
        XCTAssertTrue(FileManager.default.fileExists(atPath: paths.engineLogFile.path), "Engine log must be written inside isolated engine directory")
        let logContent = (try? String(contentsOf: paths.engineLogFile, encoding: .utf8)) ?? ""
        XCTAssertFalse(logContent.isEmpty, "Isolated engine.log must contain actual engine output")

        supervisor.stop()
        try await Task.sleep(nanoseconds: 300_000_000)

        let afterSnapshot = snapshotDirectory(userSunshineDir)
        XCTAssertEqual(
            beforeSnapshot,
            afterSnapshot,
            "Real built-in engine must NEVER create, modify, or rotate files in user's ~/.config/sunshine!"
        )
    }

    func testLocalizedCoreStringsResolveInChinese() {
        let requiredKeys = [
            "protocol.version_incompatible",
            "protocol.stream.no_screen_recording",
            "protocol.stream.port_conflict",
            "protocol.stream.paused",
            "engine.error.crash_loop",
            "rclone.error.auth_expired",
            "rclone.error.remote_not_found",
            "status.bonjour_error.title",
            "pair.prompt.source_ip"
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
        let id1 = try store.loadOrCreateIdentity()
        let id2 = try store.loadOrCreateIdentity()
        XCTAssertEqual(id1.id, id2.id)

        var settings = store.loadSettings()
        settings.computerName = "测试工作站"
        settings.qualityPreset = .high
        try store.saveSettings(settings)
        XCTAssertEqual(store.loadSettings().computerName, "测试工作站")
        XCTAssertEqual(store.loadSettings().qualityPreset, .high)

        let davCreds1 = try store.loadOrCreateWebDAVCredentials()
        let davCreds2 = try store.loadOrCreateWebDAVCredentials()
        XCTAssertEqual(davCreds1, davCreds2)
        XCTAssertEqual(davCreds1.user, "depth3d")
        XCTAssertEqual(davCreds1.pass.count, 24)

        let rotated = try store.rotateWebDAVCredentials()
        XCTAssertNotEqual(rotated.pass, davCreds1.pass)
        XCTAssertEqual(try store.loadOrCreateWebDAVCredentials(), rotated)

        let attrs = try FileManager.default.attributesOfItem(atPath: store.paths.webDAVCredentialsFile.path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)

        let dev = PairedDeviceRecord(deviceId: "pico-a", name: "PICO 4", certFingerprint: "AABB", autoAllow: true)
        _ = try store.upsertPairedDevice(dev)
        XCTAssertEqual(store.loadPairedDevices().count, 1)
        _ = try store.removePairedDevice(deviceId: "pico-a")
        XCTAssertTrue(store.loadPairedDevices().isEmpty)
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
