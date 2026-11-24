import AppKit
import Combine
import Foundation

/// High-level summary status displayed in the menu bar and Status page.
public enum HostOverallStatus: Equatable, Sendable {
    case loading
    case noNetwork
    case needsPermissions(missingCount: Int)
    case portConflict(ports: [UInt16])
    case paused
    case error(message: String)
    case streaming(activeSessions: Int)
    case ready
}

/// Central runtime controller uniting all depth3d Host services and publishing observable
/// state for the SwiftUI menu bar and main window.
@MainActor
public final class HostRuntime: ObservableObject {
    @Published public private(set) var isInitialized: Bool = false
    @Published public private(set) var fatalStartupError: String?
    @Published public private(set) var identity: HostIdentity = HostIdentity()
    @Published public var settings: HostSettings = HostSettings()
    @Published public private(set) var permissions: PermissionSnapshot = PermissionSnapshot()
    @Published public private(set) var network: NetworkSnapshot = NetworkSnapshot(isConnected: true, interfaceSummary: "Wi-Fi", localIPv4: nil)
    @Published public private(set) var engineState: EngineSupervisorState = .stopped
    @Published public private(set) var engineLogState: EngineLogState = EngineLogState()
    @Published public private(set) var displays: [DisplayInfo] = []
    @Published public private(set) var pairedDevices: [PairedDeviceRecord] = []
    @Published public private(set) var engineClients: [EngineClientRecord] = []
    @Published public private(set) var pendingPairings: [PendingPairingPrompt] = []
    @Published public private(set) var rcloneAvailability: RcloneAvailability = RcloneAvailability(executablePath: nil, availableRemotes: [])
    @Published public private(set) var controlPort: UInt16 = 0
    @Published public private(set) var webDAVPort: UInt16? = nil
    @Published public private(set) var webDAVError: String? = nil
    @Published public private(set) var tlsFingerprint: String = ""
    @Published public private(set) var isRefreshingDevices: Bool = false
    @Published public private(set) var devicesError: String? = nil
    @Published public private(set) var isRefreshingCloudRemotes: Bool = false
    @Published public private(set) var launchAtLoginError: String? = nil

    public let overrides: RuntimeOverrides

    private var store: HostStateStore?
    private var webDAVCredentials: WebDAVCredentials?
    private var adminCredentials: EngineAdminCredentials?
    private var pairingCoordinator: PairingCoordinator?
    private var controlRouter: ControlProtocolRouter?
    private var controlServer: ControlHTTPSServer?
    private var bonjourAdvertiser: HostBonjourAdvertiser?
    private var engineSupervisor: EngineSupervisor?
    private var engineAPIClient: EngineAPIClient?
    private var permissionMonitor: PermissionMonitor?
    private var audioController: SystemAudioController?
    private var networkMonitor: NetworkMonitor?
    private var rcloneManager: RcloneBridgeManager?
    private var webDAVHandler: WebDAVHandler?
    private var webDAVServer: WebDAVServer?
    private var workspaceObservers: [NSObjectProtocol] = []
    private var hasStarted = false

    public var activeEngineBasePort: UInt16 {
        if let override = overrides.engineBasePort {
            return override
        }
        return settings.useAlternateEnginePorts
            ? HostPortDefaults.alternateEngineBasePort
            : HostPortDefaults.defaultEngineBasePort
    }

    public var overallStatus: HostOverallStatus {
        if !isInitialized {
            return .loading
        }
        if let fatal = fatalStartupError {
            return .error(message: fatal)
        }
        if !network.isConnected {
            return .noNetwork
        }
        if case .paused = engineState {
            return .paused
        }
        if case .portConflict(let ports) = engineState {
            return .portConflict(ports: ports)
        }
        if case .missingBinary = engineState {
            return .error(message: "应用包缺少内置串流组件，请重新安装 depth3d Host。")
        }
        if !permissions.streamingCoreGranted || engineState == .needsScreenRecording {
            return .needsPermissions(missingCount: max(1, permissions.missingCount))
        }
        if case .failed(let reason) = engineState {
            return .error(message: reason)
        }
        if case .streaming(let count, _) = engineState {
            return .streaming(activeSessions: count)
        }
        if case .ready = engineState {
            return .ready
        }
        return .loading
    }

    public var streamReadyInfo: (ready: Bool, reason: String?) {
        if !network.isConnected {
            return (false, "这台电脑还没有连上局域网")
        }
        if permissions.screenRecording == .denied || engineState == .needsScreenRecording {
            return (false, "还没有允许屏幕录制")
        }
        if permissions.localNetwork == .denied {
            return (false, "还没有允许本地网络")
        }
        switch engineState {
        case .ready, .streaming:
            return (true, nil)
        case .paused:
            return (false, "电脑端已暂停串流")
        case .portConflict:
            return (false, "串流端口被本机其他程序占用")
        case .starting, .stopped:
            return (false, "串流服务正在启动")
        case .needsScreenRecording:
            return (false, "还没有允许屏幕录制")
        case .missingBinary:
            return (false, "内置串流组件缺失")
        case .failed(let reason):
            return (false, reason)
        }
    }

    public init(overrides: RuntimeOverrides = .fromProcess()) {
        self.overrides = overrides
    }

    public func start() {
        guard !hasStarted else { return }
        hasStarted = true

        HostLogger.info("Starting depth3d Host runtime", category: "runtime")

        do {
            let paths = HostPaths(rootDirectory: overrides.dataDirectory)
            let store = try HostStateStore(paths: paths)
            self.store = store

            let identity = try store.loadOrCreateIdentity()
            var loadedSettings = store.loadSettings()
            loadedSettings.launchAtLogin = LaunchAtLoginManager.isEnabled
            self.identity = identity
            self.settings = loadedSettings
            self.pairedDevices = store.loadPairedDevices()

            let webdavCreds = try store.loadOrCreateWebDAVCredentials()
            let adminCreds = try store.loadOrCreateEngineAdminCredentials()
            self.webDAVCredentials = webdavCreds
            self.adminCredentials = adminCreds

            // 1. Load or create persistent self-signed TLS identity for Control HTTPS server.
            let tlsManager = TLSIdentityManager(tlsDirectory: paths.tlsDirectory)
            let tlsId = try tlsManager.loadOrCreateIdentity(commonName: loadedSettings.computerName)
            self.tlsFingerprint = tlsId.fingerprintSHA256

            // 2. Pairing coordinator & control protocol HTTPS server.
            let autoIds = Set(pairedDevices.filter(\.autoAllow).map(\.deviceId))
            let coordinator = PairingCoordinator(timeout: 60.0, autoAllowedDeviceIds: autoIds)
            self.pairingCoordinator = coordinator
            Task { [weak self] in
                await coordinator.setPendingChangeHandler { [weak self] prompts in
                    Task { @MainActor [weak self] in
                        self?.pendingPairings = prompts
                    }
                }
            }

            let adapter = RuntimeControlProtocolAdapter(runtime: self)
            let router = ControlProtocolRouter(pairingCoordinator: coordinator, delegate: adapter)
            self.controlRouter = router

            let controlServer = ControlHTTPSServer(identity: tlsId.secIdentity, router: router)
            let desiredControlPort = overrides.controlPort ?? HostPortDefaults.defaultControlPort
            let boundControlPort = try controlServer.start(port: desiredControlPort, fallbackToEphemeral: true)
            self.controlServer = controlServer
            self.controlPort = boundControlPort

            // 3. System audio mute controller & display enumeration.
            let audioCtrl = SystemAudioController(muteWhileStreaming: loadedSettings.muteMacWhileStreaming)
            self.audioController = audioCtrl
            self.displays = DisplayEnumerator.currentDisplays()

            // 4. Media service (Swift read-only WebDAV + optional rclone bridge).
            let rclone = RcloneBridgeManager()
            self.rcloneManager = rclone
            let mounts = Self.makeLocalMounts(from: loadedSettings.mediaFolders)
            let davHandler = WebDAVHandler(credentials: webdavCreds, localMounts: mounts, rcloneManager: rclone)
            let davServer = WebDAVServer(handler: davHandler)
            self.webDAVHandler = davHandler
            self.webDAVServer = davServer
            reconcileMediaService()

            // 5. Bonjour mDNS advertiser.
            let advertiser = HostBonjourAdvertiser()
            self.bonjourAdvertiser = advertiser
            let permMonitor = PermissionMonitor()
            self.permissionMonitor = permMonitor

            advertiser.setStateHandler { [weak permMonitor] state in
                permMonitor?.updateLocalNetworkState(from: state)
            }
            advertiser.publishHost(
                name: loadedSettings.computerName,
                hostId: identity.id,
                controlPort: boundControlPort,
                gsPort: activeEngineBasePort,
                mediaPort: self.webDAVPort
            )
            advertiser.publishWebDAV(computerName: loadedSettings.computerName, port: self.webDAVPort)

            // 6. Streaming engine API client & supervisor.
            let apiPort = HostPortDefaults.engineAPIPort(basePort: activeEngineBasePort)
            let apiClient = EngineAPIClient(apiPort: apiPort, credentials: adminCreds)
            self.engineAPIClient = apiClient

            let supervisor = EngineSupervisor(
                paths: paths,
                adminCredentials: adminCreds,
                engineExecutableOverride: overrides.engineExecutableURL
            )
            self.engineSupervisor = supervisor
            supervisor.setHandlers(
                onStateChanged: { [weak self] newState, logState in
                    Task { @MainActor in
                        guard let self else { return }
                        self.engineState = newState
                        self.engineLogState = logState
                        if case .ready = newState {
                            self.refreshPairedClientsFromEngine()
                        }
                    }
                },
                onStreamingActivityChanged: { [weak audioCtrl] active in
                    audioCtrl?.setStreamingActive(active)
                }
            )

            // 7. Start permission monitor — restarts engine automatically when Screen Recording or
            //    Accessibility transitions to granted without requiring an app restart.
            permMonitor.startMonitoring(pollInterval: 1.0) { [weak self] oldSnap, newSnap in
                Task { @MainActor in
                    guard let self else { return }
                    self.permissions = newSnap
                    let screenJustGranted = (oldSnap.screenRecording != .granted && newSnap.screenRecording == .granted)
                    let axJustGranted = (oldSnap.accessibility != .granted && newSnap.accessibility == .granted)
                    if screenJustGranted || axJustGranted {
                        HostLogger.info("Permission newly granted; automatically restarting streaming engine", category: "permissions")
                        self.restartEngineWithCurrentSettings()
                    }
                }
            }
            self.permissions = permMonitor.currentSnapshot()

            // 8. Network monitor (Wi-Fi / Ethernet changes).
            let netMon = NetworkMonitor()
            self.networkMonitor = netMon
            netMon.start { [weak self] snap in
                Task { @MainActor in
                    guard let self else { return }
                    let wasDisconnected = !self.network.isConnected
                    self.network = snap
                    if wasDisconnected && snap.isConnected {
                        self.republishBonjour()
                    }
                }
            }
            self.network = netMon.currentSnapshot()

            // 9. Sleep/wake and display hotplug observers.
            observeSystemLifecycle()

            // 10. Start streaming engine and probe rclone availability in background.
            startEngineWithCurrentSettings()
            refreshCloudRemotes()

            self.isInitialized = true
        } catch {
            HostLogger.error("Runtime startup failed: \(error.localizedDescription)", category: "runtime")
            self.fatalStartupError = "服务启动失败，请退出后重新打开 depth3d Host。"
            self.isInitialized = true
        }
    }

    public func shutdown() {
        HostLogger.info("Shutting down depth3d Host runtime", category: "runtime")
        for token in workspaceObservers {
            NSWorkspace.shared.notificationCenter.removeObserver(token)
            NotificationCenter.default.removeObserver(token)
        }
        workspaceObservers.removeAll()

        audioController?.restoreIfNeeded()
        permissionMonitor?.stopMonitoring()
        networkMonitor?.stop()
        bonjourAdvertiser?.stop()
        controlServer?.stop()
        webDAVServer?.stop()
        rcloneManager?.stopAll()
        engineSupervisor?.stop()
        if let coordinator = pairingCoordinator {
            Task { await coordinator.cancelAll() }
        }
    }

    // MARK: - Pairing & Devices

    public func approvePairing(promptId: UUID, rememberAutoAllow: Bool) {
        guard let coordinator = pairingCoordinator else { return }
        Task {
            await coordinator.approve(promptId: promptId, rememberAutoAllow: rememberAutoAllow)
        }
    }

    public func rejectPairing(promptId: UUID) {
        guard let coordinator = pairingCoordinator else { return }
        Task {
            await coordinator.reject(promptId: promptId)
        }
    }

    public func submitManualPIN(pin: String, deviceName: String) async -> Result<Void, ManualPINError> {
        let trimmedPIN = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ControlProtocolRouter.isValidPIN(trimmedPIN) else {
            return .failure(.invalidFormat)
        }
        guard let api = engineAPIClient, engineState.isReadyForClients else {
            return .failure(.engineNotReady)
        }
        let cleanName = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = cleanName.isEmpty ? "头显设备" : cleanName
        do {
            try await api.submitManualPIN(pin: trimmedPIN, deviceName: label)
            refreshPairedClientsFromEngine()
            return .success(())
        } catch EngineAPIError.noPendingPairing {
            return .failure(.noPendingHeadset)
        } catch EngineAPIError.pinRejected {
            return .failure(.pinRejected)
        } catch {
            return .failure(.unexpected)
        }
    }

    public enum ManualPINError: Error, Equatable {
        case invalidFormat
        case engineNotReady
        case noPendingHeadset
        case pinRejected
        case unexpected
    }

    public func refreshPairedClientsFromEngine() {
        guard let api = engineAPIClient else { return }
        isRefreshingDevices = true
        devicesError = nil
        Task {
            do {
                let clients = try await api.listPairedClients()
                self.engineClients = clients
                self.syncPairedRecordsWithEngineClients(clients)
                self.isRefreshingDevices = false
            } catch {
                self.isRefreshingDevices = false
                if self.engineState.isReadyForClients {
                    self.devicesError = "暂时无法读取已配对设备，请稍后重试。"
                }
            }
        }
    }

    public func setAutoAllow(_ enabled: Bool, for deviceId: String) {
        guard let store else { return }
        if var existing = pairedDevices.first(where: { $0.deviceId == deviceId }) {
            existing.autoAllow = enabled
            if let updated = try? store.upsertPairedDevice(existing) {
                pairedDevices = updated
            }
        }
        if let coordinator = pairingCoordinator {
            Task { await coordinator.setAutoAllowed(enabled, for: deviceId) }
        }
    }

    public func removePairedDevice(_ device: PairedDeviceRecord) {
        guard let store else { return }
        if let updated = try? store.removePairedDevice(deviceId: device.deviceId) {
            pairedDevices = updated
        }
        if let coordinator = pairingCoordinator {
            Task { await coordinator.setAutoAllowed(false, for: device.deviceId) }
        }
        if let uuid = device.engineUUID, let api = engineAPIClient {
            Task {
                _ = try? await api.unpairClient(uuid: uuid)
                self.refreshPairedClientsFromEngine()
            }
        }
    }

    public func removeEngineOnlyClient(_ client: EngineClientRecord) {
        guard let api = engineAPIClient else { return }
        Task {
            _ = try? await api.unpairClient(uuid: client.uuid)
            self.refreshPairedClientsFromEngine()
        }
    }

    // MARK: - Streaming Controls

    public func togglePauseStreaming() {
        guard let supervisor = engineSupervisor else { return }
        if case .paused = engineState {
            supervisor.resume()
        } else {
            supervisor.pause()
        }
    }

    public func disconnectActiveStream() {
        guard let api = engineAPIClient else { return }
        Task {
            _ = try? await api.closeActiveSession()
        }
    }

    public func retryEngine() {
        restartEngineWithCurrentSettings()
    }

    public func switchToAlternateEnginePorts() {
        settings.useAlternateEnginePorts = true
        persistSettings()
        restartEngineWithCurrentSettings()
        republishBonjour()
    }

    public func switchToDefaultEnginePorts() {
        settings.useAlternateEnginePorts = false
        persistSettings()
        restartEngineWithCurrentSettings()
        republishBonjour()
    }

    // MARK: - Permissions

    public func requestScreenRecordingPermission() {
        permissionMonitor?.requestScreenRecording()
    }

    public func requestAccessibilityPermission() {
        permissionMonitor?.requestAccessibility()
    }

    public func openLocalNetworkSettings() {
        permissionMonitor?.openLocalNetworkSettings()
    }

    public func refreshPermissionsNow() {
        if let snap = permissionMonitor?.refreshNow() {
            permissions = snap
        }
    }

    public func openNetworkSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Network-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: - Media Sources

    public func setMediaServiceEnabled(_ enabled: Bool) {
        settings.mediaServiceEnabled = enabled
        persistSettings()
        reconcileMediaService()
        republishBonjour()
    }

    public func addMediaFolder(url: URL) {
        let cleanURL = url.standardizedFileURL
        guard !settings.mediaFolders.contains(where: { $0.path == cleanURL.path }) else { return }
        let baseName = cleanURL.lastPathComponent.isEmpty ? "共享文件夹" : cleanURL.lastPathComponent
        let uniqueName = makeUniqueMountName(baseName)
        let bookmark = try? cleanURL.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: nil, relativeTo: nil)
        settings.mediaFolders.append(MediaFolderEntry(name: uniqueName, path: cleanURL.path, bookmarkData: bookmark))
        persistSettings()
        reconcileMediaService()
        republishBonjour()
    }

    public func removeMediaFolder(_ folder: MediaFolderEntry) {
        settings.mediaFolders.removeAll { $0.id == folder.id }
        persistSettings()
        reconcileMediaService()
        republishBonjour()
    }

    public func refreshCloudRemotes() {
        guard let rclone = rcloneManager else { return }
        isRefreshingCloudRemotes = true
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let avail = rclone.probeAvailability()
            Task { @MainActor in
                self?.rcloneAvailability = avail
                self?.isRefreshingCloudRemotes = false
            }
        }
    }

    public func addCloudRemote(remoteSpec: String, displayName: String? = nil) {
        let trimmed = remoteSpec.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let normalizedSpec = trimmed.contains(":") ? trimmed : (trimmed + ":")
        guard !settings.cloudRemotes.contains(where: { $0.remoteSpec == normalizedSpec }) else { return }
        let defaultName = normalizedSpec.replacingOccurrences(of: ":", with: "")
        let name = makeUniqueMountName((displayName?.isEmpty == false ? displayName! : defaultName))
        settings.cloudRemotes.append(CloudRemoteEntry(displayName: name, remoteSpec: normalizedSpec))
        persistSettings()
        reconcileMediaService()
        republishBonjour()
    }

    public func removeCloudRemote(_ entry: CloudRemoteEntry) {
        settings.cloudRemotes.removeAll { $0.id == entry.id }
        persistSettings()
        reconcileMediaService()
        republishBonjour()
    }

    // MARK: - Settings Updates

    public func setMuteMacWhileStreaming(_ enabled: Bool) {
        settings.muteMacWhileStreaming = enabled
        audioController?.setMuteWhileStreaming(enabled)
        persistSettings()
    }

    public func setLaunchAtLogin(_ enabled: Bool) {
        launchAtLoginError = nil
        do {
            try LaunchAtLoginManager.setEnabled(enabled)
            settings.launchAtLogin = LaunchAtLoginManager.isEnabled
            persistSettings()
        } catch {
            HostLogger.warning("Launch at login update failed: \(error.localizedDescription)", category: "settings")
            settings.launchAtLogin = LaunchAtLoginManager.isEnabled
            launchAtLoginError = "未能更改开机启动设置，请在“系统设置 › 通用 › 登录项”中手动开启。"
        }
    }

    public func setSelectedDisplay(_ displayID: String?) {
        guard settings.selectedDisplayID != displayID else { return }
        settings.selectedDisplayID = displayID
        persistSettings()
        restartEngineWithCurrentSettings()
    }

    public func setQualityPreset(_ preset: StreamQualityPreset) {
        guard settings.qualityPreset != preset else { return }
        settings.qualityPreset = preset
        persistSettings()
        restartEngineWithCurrentSettings()
    }

    public func setComputerName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalName = trimmed.isEmpty ? HostSettings.defaultComputerName() : trimmed
        guard settings.computerName != finalName else { return }
        settings.computerName = finalName
        persistSettings()
        restartEngineWithCurrentSettings()
        republishBonjour()
    }

    public func completeOnboarding() {
        settings.onboardingCompleted = true
        persistSettings()
    }

    public func resetOnboarding() {
        settings.onboardingCompleted = false
        persistSettings()
    }

    public func refreshDisplays() {
        displays = DisplayEnumerator.currentDisplays()
    }

    // MARK: - Internal Helpers for Protocol Adapter

    fileprivate func snapshotForProtocol() -> ControlProtocolSnapshot {
        let osVer = ProcessInfo.processInfo.operatingSystemVersion
        let osStr = "macOS \(osVer.majorVersion).\(osVer.minorVersion).\(osVer.patchVersion)"
        let streamInfo = streamReadyInfo
        return ControlProtocolSnapshot(
            hostName: settings.computerName,
            hostId: identity.id,
            osDescription: osStr,
            streamReady: streamInfo.ready,
            streamNotReadyReason: streamInfo.reason,
            mediaPort: webDAVPort,
            webDAVCredentials: webDAVCredentials
        )
    }

    fileprivate func authorizeMediaClient(deviceId: String, peerCertFingerprintSHA256: String) -> Bool {
        guard let store else { return false }
        guard let idx = pairedDevices.firstIndex(where: { $0.deviceId == deviceId }) else {
            return false
        }
        var record = pairedDevices[idx]

        // 1. If device already bound a certificate fingerprint, require exact match.
        if let boundFP = record.certFingerprint {
            return boundFP.caseInsensitiveCompare(peerCertFingerprintSHA256) == .orderedSame
        }

        // 2. Otherwise check the engine's paired client certificates (`state.json`) and bind on first verified use.
        let engineCerts = EngineConfigGenerator.loadPairedCerts(at: store.paths.engineStateFile)
        if let matched = engineCerts.first(where: {
            $0.fingerprintSHA256?.caseInsensitiveCompare(peerCertFingerprintSHA256) == .orderedSame
        }) {
            record.certFingerprint = peerCertFingerprintSHA256.uppercased()
            record.engineUUID = matched.uuid
            record.lastSeenAt = Date()
            if let updated = try? store.upsertPairedDevice(record) {
                pairedDevices = updated
            }
            return true
        }
        return false
    }

    fileprivate func handleApprovedPairing(
        deviceName: String,
        deviceId: String,
        pin: String,
        autoAllowFuture: Bool
    ) async -> Bool {
        guard let store, let api = engineAPIClient else { return false }
        let existing = pairedDevices.first(where: { $0.deviceId == deviceId })
        let updatedRecord = PairedDeviceRecord(
            deviceId: deviceId,
            name: deviceName,
            engineUUID: existing?.engineUUID,
            certFingerprint: existing?.certFingerprint,
            autoAllow: autoAllowFuture || (existing?.autoAllow ?? false),
            pairedAt: existing?.pairedAt ?? Date(),
            lastSeenAt: Date()
        )
        if let saved = try? store.upsertPairedDevice(updatedRecord) {
            self.pairedDevices = saved
        }

        // If a GameStream pairing session is already pending in the engine, submit immediately.
        // Otherwise arm a background waiter so returning 200 allows the headset to initiate
        // GameStream `/pair` right away and be paired seamlessly.
        if let pending = try? await api.fetchPendingPairings(), let first = pending.first {
            let ok = (try? await api.submitPIN(pairingId: first.id, pin: pin, deviceName: deviceName)) ?? false
            if ok {
                HostLogger.info("Immediately paired pending GameStream session for \(deviceName)", category: "pair")
                self.refreshPairedClientsFromEngine()
                return true
            }
        }

        Task.detached { [weak self, api] in
            let ok = await api.submitPINWhenReady(pin: pin, deviceName: deviceName, maxWait: 30.0)
            if ok {
                await MainActor.run { [weak self] in
                    self?.refreshPairedClientsFromEngine()
                }
            }
        }
        return true
    }

    // MARK: - Private Coordination

    private func startEngineWithCurrentSettings() {
        guard let store, let supervisor = engineSupervisor else { return }
        let basePort = activeEngineBasePort
        if let creds = adminCredentials {
            engineAPIClient?.updateEndpoint(
                apiPort: HostPortDefaults.engineAPIPort(basePort: basePort),
                credentials: creds
            )
        }
        let input = EngineConfigGenerator.Input(
            hostName: settings.computerName,
            basePort: basePort,
            quality: settings.qualityPreset,
            displayID: settings.selectedDisplayID,
            paths: store.paths
        )
        supervisor.start(config: input)
    }

    private func restartEngineWithCurrentSettings() {
        guard let store, let supervisor = engineSupervisor else { return }
        let basePort = activeEngineBasePort
        if let creds = adminCredentials {
            engineAPIClient?.updateEndpoint(
                apiPort: HostPortDefaults.engineAPIPort(basePort: basePort),
                credentials: creds
            )
        }
        let input = EngineConfigGenerator.Input(
            hostName: settings.computerName,
            basePort: basePort,
            quality: settings.qualityPreset,
            displayID: settings.selectedDisplayID,
            paths: store.paths
        )
        supervisor.restart(with: input)
    }

    private func reconcileMediaService() {
        guard let davServer = webDAVServer,
              let davHandler = webDAVHandler,
              let rclone = rcloneManager
        else { return }

        webDAVError = nil
        let mounts = Self.makeLocalMounts(from: settings.mediaFolders)
        davHandler.updateConfiguration(localMounts: mounts)

        if settings.mediaServiceEnabled {
            let remotes = settings.cloudRemotes
            DispatchQueue.global(qos: .utility).async {
                rclone.syncRemotes(remotes)
            }
            if !davServer.isRunning {
                let desired = overrides.webDAVPort ?? HostPortDefaults.defaultWebDAVPort
                do {
                    let actualPort = try davServer.start(preferredPort: desired)
                    self.webDAVPort = actualPort
                } catch {
                    HostLogger.error("Failed to start WebDAV media server: \(error.localizedDescription)", category: "media")
                    self.webDAVPort = nil
                    self.webDAVError = "媒体服务启动失败，请稍后重新开启。"
                }
            } else {
                self.webDAVPort = davServer.port
            }
        } else {
            davServer.stop()
            rclone.stopAll()
            self.webDAVPort = nil
        }
    }

    private func republishBonjour() {
        guard controlPort > 0 else { return }
        bonjourAdvertiser?.publishHost(
            name: settings.computerName,
            hostId: identity.id,
            controlPort: controlPort,
            gsPort: activeEngineBasePort,
            mediaPort: webDAVPort
        )
        bonjourAdvertiser?.publishWebDAV(computerName: settings.computerName, port: webDAVPort)
    }

    private func syncPairedRecordsWithEngineClients(_ clients: [EngineClientRecord]) {
        guard let store else { return }
        let engineCerts = EngineConfigGenerator.loadPairedCerts(at: store.paths.engineStateFile)
        var changed = false
        var updated = pairedDevices
        for idx in updated.indices {
            if updated[idx].engineUUID == nil {
                if let match = clients.first(where: { $0.name == updated[idx].name }) {
                    updated[idx].engineUUID = match.uuid
                    if let cert = engineCerts.first(where: { $0.uuid == match.uuid }) {
                        updated[idx].certFingerprint = cert.fingerprintSHA256
                    }
                    changed = true
                }
            }
        }
        if changed, let saved = try? store.savePairedDevices(updated) {
            _ = saved
            pairedDevices = updated
        }
    }

    private func persistSettings() {
        try? store?.saveSettings(settings)
    }

    private func makeUniqueMountName(_ raw: String) -> String {
        let cleaned = raw
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let base = cleaned.isEmpty ? "媒体文件夹" : cleaned
        let existing = Set(settings.mediaFolders.map(\.name) + settings.cloudRemotes.map(\.displayName))
        if !existing.contains(base) { return base }
        var idx = 2
        while existing.contains("\(base) \(idx)") {
            idx += 1
        }
        return "\(base) \(idx)"
    }

    private static func makeLocalMounts(from entries: [MediaFolderEntry]) -> [WebDAVMount] {
        entries.map { entry in
            WebDAVMount(name: entry.name, rootURL: URL(fileURLWithPath: entry.path, isDirectory: true))
        }
    }

    private func observeSystemLifecycle() {
        let wsCenter = NSWorkspace.shared.notificationCenter
        let wakeObs = wsCenter.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                HostLogger.info("Mac woke from sleep; refreshing displays and Bonjour advertisement", category: "runtime")
                self?.refreshDisplays()
                self?.republishBonjour()
            }
        }
        let sleepObs = wsCenter.addObserver(
            forName: NSWorkspace.willSleepNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                HostLogger.info("Mac entering sleep; restoring Mac speaker mute state if active", category: "runtime")
                self?.audioController?.restoreIfNeeded()
            }
        }
        let displayObs = NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.refreshDisplays()
            }
        }
        workspaceObservers = [wakeObs, sleepObs, displayObs]
    }
}

private final class RuntimeControlProtocolAdapter: ControlProtocolDelegate, @unchecked Sendable {
    private weak var runtime: HostRuntime?

    init(runtime: HostRuntime) {
        self.runtime = runtime
    }

    func currentSnapshot() async -> ControlProtocolSnapshot {
        await MainActor.run {
            runtime?.snapshotForProtocol() ?? ControlProtocolSnapshot(
                hostName: "Mac",
                hostId: "",
                osDescription: "macOS",
                streamReady: false,
                streamNotReadyReason: "服务尚未初始化",
                mediaPort: nil,
                webDAVCredentials: nil
            )
        }
    }

    func authorizeMediaClient(deviceId: String, peerCertFingerprintSHA256: String) async -> Bool {
        await MainActor.run {
            runtime?.authorizeMediaClient(deviceId: deviceId, peerCertFingerprintSHA256: peerCertFingerprintSHA256) ?? false
        }
    }

    func handleApprovedPairing(deviceName: String, deviceId: String, pin: String, autoAllowFuture: Bool) async -> Bool {
        guard let runtime else { return false }
        return await runtime.handleApprovedPairing(
            deviceName: deviceName,
            deviceId: deviceId,
            pin: pin,
            autoAllowFuture: autoAllowFuture
        )
    }
}
