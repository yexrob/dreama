import CoreAudio
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
    func testEngineConfigGenerationIsolationAndVideoToolboxDefaults() {
        let root = URL(fileURLWithPath: "/tmp/depth3d-unit-app-support", isDirectory: true)
        let paths = HostPaths(rootDirectory: root)
        let input = EngineConfigGenerator.Input(
            hostName: "客厅 MacBook",
            basePort: 57989,
            quality: .high,
            displayID: "2",
            paths: paths
        )

        let conf = EngineConfigGenerator.renderConfig(input)
        XCTAssertTrue(conf.contains("sunshine_name = 客厅 MacBook"))
        XCTAssertTrue(conf.contains("port = 57989"))
        XCTAssertTrue(conf.contains("origin_web_ui_allowed = pc"))
        XCTAssertTrue(conf.contains("system_tray = disabled"))
        XCTAssertTrue(conf.contains("encoder = videotoolbox"))
        XCTAssertTrue(conf.contains("hevc_mode = 0"))
        XCTAssertTrue(conf.contains("vt_realtime = enabled"))
        XCTAssertTrue(conf.contains("qp = 18"))
        XCTAssertTrue(conf.contains("stream_audio = enabled"))
        XCTAssertTrue(conf.contains("audio_sink =\n"))
        XCTAssertTrue(conf.contains("output_name = 2"))
        XCTAssertTrue(conf.contains("file_state = \(paths.engineStateFile.path)"))
        XCTAssertFalse(conf.contains(".config/sunshine"), "Must never reference user ~/.config/sunshine")

        let apps = EngineConfigGenerator.renderAppsJSON()
        XCTAssertTrue(apps.contains("\"Desktop\""))
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

    func testEngineLogParserCoversLifecycleEvents() {
        var state = EngineLogState()

        let lines = [
            "[2026-11-24 23:12:22.531]: Info: Creating encoder [hevc_videotoolbox]",
            "[2026-11-24 23:12:23.142]: Info: Configuration UI available at [https://localhost:57990]",
            "[2026-11-24 23:12:25.162]: Info: Successfully registered DNS service.",
            "[2026-11-24 23:13:01.000]: Info: Using macOS system audio tap for capture.",
            "[2026-11-24 23:13:01.010]: Info: Core Audio tap: Host audio disabled (muted)",
            "[2026-11-24 23:13:01.050]: Info: New streaming session started [active sessions: 1]"
        ]

        for line in lines {
            if let ev = EngineLogParser.parse(line: line) {
                state.apply(ev)
            }
        }

        XCTAssertTrue(state.apiReady)
        XCTAssertTrue(state.mdnsRegistered)
        XCTAssertEqual(state.encoderName, "hevc_videotoolbox")
        XCTAssertTrue(state.systemAudioTapActive)
        XCTAssertEqual(state.systemAudioTapHostMuted, true)
        XCTAssertEqual(state.activeSessions, 1)

        if let stopEv = EngineLogParser.parse(line: "[2026-11-24 23:14:00.000]: Info: CLIENT DISCONNECTED") {
            state.apply(stopEv)
        }
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

    func testSystemAudioControllerMutesAndRestoresCleanly() {
        let hal = MockAudioHAL()
        hal.muted = false
        let ctrl = SystemAudioController(backend: hal, muteWhileStreaming: true)

        // Start streaming -> mutes Mac speakers
        ctrl.setStreamingActive(true)
        XCTAssertTrue(hal.muted)
        XCTAssertTrue(ctrl.isHostMutedByController)

        // Duplicate start event -> no-op
        ctrl.setStreamingActive(true)
        XCTAssertEqual(hal.setCalls, [true])

        // User disables mute mid-stream -> immediately restores
        ctrl.setMuteWhileStreaming(false)
        XCTAssertFalse(hal.muted)
        XCTAssertFalse(ctrl.isHostMutedByController)

        // User re-enables mute mid-stream -> mutes again
        ctrl.setMuteWhileStreaming(true)
        XCTAssertTrue(hal.muted)

        // Streaming stops -> restores to unmuted
        ctrl.setStreamingActive(false)
        XCTAssertFalse(hal.muted)
        XCTAssertFalse(ctrl.isHostMutedByController)

        // If Mac was ALREADY muted before streaming, stopping streaming must keep it muted!
        hal.muted = true
        hal.setCalls.removeAll()
        ctrl.setStreamingActive(true)
        XCTAssertTrue(hal.muted)
        XCTAssertEqual(hal.setCalls, [])
        ctrl.setStreamingActive(false)
        XCTAssertTrue(hal.muted)
        XCTAssertEqual(hal.setCalls, [])
    }

    func testPermissionMonitorTracksTransitions() {
        let mock = MockPermissionProvider()
        let monitor = PermissionMonitor(provider: mock)

        var transitions: [PermissionSnapshot] = []
        monitor.startMonitoring(pollInterval: 60.0) { _, next in
            transitions.append(next)
        }
        defer { monitor.stopMonitoring() }

        XCTAssertEqual(monitor.currentSnapshot().screenRecording, .denied)
        XCTAssertEqual(monitor.currentSnapshot().accessibility, .denied)
        XCTAssertEqual(monitor.currentSnapshot().localNetwork, .checking)

        mock.screen = true
        monitor.refreshNow()
        XCTAssertEqual(monitor.currentSnapshot().screenRecording, .granted)
        XCTAssertFalse(monitor.currentSnapshot().allGranted)

        mock.accessibility = true
        monitor.updateLocalNetworkState(from: .published)
        XCTAssertTrue(monitor.currentSnapshot().allGranted)
        XCTAssertGreaterThanOrEqual(transitions.count, 2)
    }

    func testHostStateStorePersistenceAndPermissions() throws {
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

        let attrs = try FileManager.default.attributesOfItem(atPath: store.paths.webDAVCredentialsFile.path)
        let perms = (attrs[.posixPermissions] as? NSNumber)?.intValue
        XCTAssertEqual(perms, 0o600)

        let dev = PairedDeviceRecord(deviceId: "pico-a", name: "PICO 4", autoAllow: true)
        _ = try store.upsertPairedDevice(dev)
        XCTAssertEqual(store.loadPairedDevices().count, 1)
        _ = try store.removePairedDevice(deviceId: "pico-a")
        XCTAssertTrue(store.loadPairedDevices().isEmpty)
    }
}
