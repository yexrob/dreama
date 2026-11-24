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
    @Published public private(set) var network: NetworkSnapshot = NetworkSnapshot(
        isConnected: true,
        interfaceSummary: HostStrings.string("network.wifi"),
        localIPv4: nil
    )
    @Published public private(set) var engineState: EngineSupervisorState = .stopped
    @Published public private(set) var engineLogState: EngineLogState = EngineLogState()
    @Published public private(set) var displays: [DisplayInfo] = []
    @Published public private(set) var pairedDevices: [PairedDeviceRecord] = []
    @Published public private(set) var engineClients: [EngineClientRecord] = []
    @Published public private(set) var pendingPairings: [PendingPairingPrompt] = []
    @Published public private(set) var pendingEnginePairings: [EnginePendingPairing] = []
    @Published public private(set) var rcloneAvailability: RcloneAvailability = RcloneAvailability(executablePath: nil, availableRemotes: [])
    @Published public private(set) var rcloneStatuses: [String: RcloneRemoteStatus] = [:]
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
    private var childLedger: ChildProcessLedger?
    private var webDAVCredentials: WebDAVCredentials?
    private var mediaCredentialRevision: Int = 0
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

    private struct PendingPairingTicket {
        let ticket: String
        let clientIP: String
        let deviceName: String
        let deviceId: String
        let rememberAutoAllow: Bool
        let approvedViaAutoAllow: Bool
        let expiresAt: Date
        var feederTask: Task<Void, Never>?
    }

    private var pendingPairingTickets: [String: PendingPairingTicket] = [:]
    internal var ticketTTLSeconds: TimeInterval = 120.0
    internal var pinFeederWindowSeconds: TimeInterval = 60.0

    internal var currentWebDAVCredentials: WebDAVCredentials? { webDAVCredentials }
    internal var currentMediaCredentialRevision: Int { mediaCredentialRevision }

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
            return .error(message: HostStrings.string("runtime.error.missing_binary"))
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
            return (false, HostStrings.string("protocol.stream.no_network"))
        }
        if permissions.screenRecording == .denied || engineState == .needsScreenRecording {
            return (false, HostStrings.string("protocol.stream.no_screen_recording"))
        }
        if permissions.localNetwork == .denied {
            return (false, HostStrings.string("protocol.stream.no_local_network"))
        }
        switch engineState {
        case .ready, .streaming:
            return (true, nil)
        case .paused:
            return (false, HostStrings.string("protocol.stream.paused"))
        case .portConflict:
            return (false, HostStrings.string("protocol.stream.port_conflict"))
        case .starting, .stopped:
            return (false, HostStrings.string("protocol.stream.starting"))
        case .needsScreenRecording:
            return (false, HostStrings.string("protocol.stream.no_screen_recording"))
        case .missingBinary:
            return (false, HostStrings.string("protocol.stream.missing_binary"))
        case .failed(let reason):
            return (false, reason)
        }
    }

    public init(overrides: RuntimeOverrides = .fromProcess()) {
        self.overrides = overrides
    }

    /// Configures real runtime dependencies for unit tests without mocking `ControlProtocolDelegate`
    /// or launching the full Bonjour / real streaming binary stack.
    internal func configureForTesting(
        store: HostStateStore,
        engineAPIClient: EngineAPIClient,
        engineState: EngineSupervisorState,
        pairingCoordinator: PairingCoordinator,
        webDAVHandler: WebDAVHandler? = nil,
        webDAVCredentials: WebDAVCredentials? = nil,
        webDAVPort: UInt16? = 8890
    ) {
        self.store = store
        self.identity = (try? store.loadOrCreateIdentity()) ?? HostIdentity()
        self.settings = store.loadSettings()
        self.pairedDevices = store.loadPairedDevices()
        self.engineAPIClient = engineAPIClient
        self.engineState = engineState
        self.pairingCoordinator = pairingCoordinator
        self.webDAVHandler = webDAVHandler
        self.webDAVCredentials = webDAVCredentials ?? (try? store.loadOrCreateWebDAVCredentials())
        self.webDAVPort = webDAVPort
        self.permissions = PermissionSnapshot(screenRecording: .granted, accessibility: .granted, localNetwork: .granted)
        self.network = NetworkSnapshot(isConnected: true, interfaceSummary: HostStrings.string("network.wifi"), localIPv4: "127.0.0.1")
        self.isInitialized = true
    }

    internal func makeProtocolDelegateForTesting() -> ControlProtocolDelegate {
        RuntimeControlProtocolAdapter(runtime: self)
    }

    public func start() {
        guard !hasStarted else { return }
        hasStarted = true

        HostLogger.info("Starting depth3d Host runtime", category: "runtime")

        do {
            let paths = HostPaths(rootDirectory: overrides.dataDirectory)
            let store = try HostStateStore(paths: paths)
            self.store = store

            // 0. Clean up any orphaned engine / rclone / child-guard processes left by an earlier crash.
            let ledger = ChildProcessLedger(ledgerURL: paths.childLedgerFile)
            self.childLedger = ledger
            let cleaned = ledger.cleanupStaleChildren()
            if cleaned > 0 {
                HostLogger.warning("Cleaned up \(cleaned) orphaned child process(es) from previous run", category: "runtime")
            }

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

            // 2. Certificate-fingerprint-bound pairing coordinator & control protocol HTTPS server.
            let autoFingerprints = Set(
                pairedDevices.compactMap { dev -> String? in
                    guard dev.autoAllow, let fp = dev.certFingerprint, !fp.isEmpty else { return nil }
                    return fp.uppercased()
                }
            )
            let coordinator = PairingCoordinator(
                timeout: 60.0,
                cooldownDuration: 30.0,
                autoAllowedFingerprints: autoFingerprints
            )
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

            // 3. System audio mute controller (automatically recovers Mac speaker mute from any crash) & displays.
            let audioCtrl = SystemAudioController(
                muteWhileStreaming: loadedSettings.muteMacWhileStreaming,
                recoveryFileURL: paths.audioMuteRecoveryFile
            )
            self.audioController = audioCtrl
            self.displays = DisplayEnumerator.currentDisplays()

            // 4. Media service (Swift read-only WebDAV + optional rclone bridge).
            let rclone = RcloneBridgeManager(ledger: ledger)
            self.rcloneManager = rclone
            rclone.setStatusesChangedHandler { [weak self] statuses in
                Task { @MainActor [weak self] in
                    self?.rcloneStatuses = statuses
                }
            }
            let mounts = Self.makeLocalMounts(from: loadedSettings.mediaFolders)
            let davHandler = WebDAVHandler(credentials: webdavCreds, localMounts: mounts, rcloneManager: rclone)
            let davServer = WebDAVServer(handler: davHandler)
            self.webDAVHandler = davHandler
            self.webDAVServer = davServer
            reconcileMediaService()

            // 5. Bonjour mDNS advertiser (`_depth3d-host._tcp` ONLY; no `_webdav._tcp` LAN broadcast).
            //    `gs` is omitted initially until engine reports `.ready` / `.streaming`.
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
                gsPort: nil,
                mediaPort: self.webDAVPort,
                mediaRevision: self.mediaCredentialRevision
            )

            // 6. Streaming engine API client & supervisor.
            let apiPort = HostPortDefaults.engineAPIPort(basePort: activeEngineBasePort)
            let apiClient = EngineAPIClient(apiPort: apiPort, credentials: adminCreds)
            self.engineAPIClient = apiClient

            let supervisor = EngineSupervisor(
                paths: paths,
                adminCredentials: adminCreds,
                engineExecutableOverride: overrides.engineExecutableURL,
                ledger: ledger
            )
            self.engineSupervisor = supervisor
            supervisor.setHandlers(
                onStateChanged: { [weak self] newState, logState in
                    Task { @MainActor in
                        guard let self else { return }
                        let wasReady = self.engineState.isReadyForClients
                        self.engineState = newState
                        self.engineLogState = logState
                        if wasReady != newState.isReadyForClients {
                            self.updateBonjourTXT()
                        }
                        if case .ready = newState {
                            self.refreshPairedClientsFromEngine()
                        }
                    }
                },
                onActiveSessionCountChanged: { [weak audioCtrl] count in
                    audioCtrl?.setActiveSessionCount(count)
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
            self.fatalStartupError = HostStrings.string("runtime.error.startup")
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
        for entry in pendingPairingTickets.values {
            entry.feederTask?.cancel()
        }
        pendingPairingTickets.removeAll()
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

    public func refreshPendingEnginePairings() {
        guard let api = engineAPIClient, engineState.isReadyForClients else {
            pendingEnginePairings = []
            return
        }
        Task {
            if let list = try? await api.fetchPendingPairings() {
                self.pendingEnginePairings = list
            }
        }
    }

    public func submitManualPIN(
        pin: String,
        deviceName: String,
        pairingId: String? = nil
    ) async -> Result<Void, ManualPINError> {
        let trimmedPIN = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ControlProtocolRouter.isValidPIN(trimmedPIN) else {
            return .failure(.invalidFormat)
        }
        guard let api = engineAPIClient, engineState.isReadyForClients else {
            return .failure(.engineNotReady)
        }
        let cleanName = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = cleanName.isEmpty ? HostStrings.string("device.default_name") : cleanName
        do {
            try await api.submitManualPIN(pin: trimmedPIN, deviceName: label, pairingId: pairingId)
            pendingEnginePairings = []
            refreshPairedClientsFromEngine()
            return .success(())
        } catch EngineAPIError.noPendingPairing {
            pendingEnginePairings = []
            return .failure(.noPendingHeadset)
        } catch EngineAPIError.multiplePendingPairings(let list) {
            pendingEnginePairings = list
            return .failure(.multiplePendingHeadsets)
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
        case multiplePendingHeadsets
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
                self.isRefreshingDevices = false
            } catch {
                self.isRefreshingDevices = false
                if self.engineState.isReadyForClients {
                    self.devicesError = HostStrings.string("runtime.error.devices_load")
                }
            }
        }
    }

    public func setAutoAllow(_ enabled: Bool, forRecordId recordId: String) {
        guard let store else { return }
        var targetFP: String?
        var effective = false
        if var existing = pairedDevices.first(where: { $0.id == recordId }) {
            // Only cert-bound devices can enable auto-allow.
            effective = enabled && (existing.certFingerprint?.isEmpty == false)
            existing.autoAllow = effective
            targetFP = existing.certFingerprint
            if let updated = try? store.upsertPairedDevice(existing) {
                pairedDevices = updated
            }
        }
        if let coordinator = pairingCoordinator, let fp = targetFP {
            Task { await coordinator.setAutoAllowed(certFingerprintSHA256: fp, enabled: effective) }
        }
    }

    public func removePairedDevice(_ device: PairedDeviceRecord) {
        Task { @MainActor in
            await self.removePairedDeviceAndWait(device)
        }
    }

    /// Removes a paired device record by primary key (`device.id`), clears auto-allow, rotates
    /// WebDAV credentials & bumps `mrev`, and unpairs EVERY entry in the engine matching
    /// `device.certFingerprint` (even when `device.engineUUID` is nil or duplicates exist).
    /// Legacy records without a fingerprint are simply deleted locally.
    internal func removePairedDeviceAndWait(_ device: PairedDeviceRecord) async {
        guard let store else { return }
        if let updated = try? store.removePairedDevice(id: device.id) {
            pairedDevices = updated
        }
        if let coordinator = pairingCoordinator, let fp = device.certFingerprint, !fp.isEmpty {
            await coordinator.setAutoAllowed(certFingerprintSHA256: fp, enabled: false)
        }
        // Immediately rotate WebDAV credentials and bump Bonjour TXT revision so the removed
        // device loses media access right away while remaining paired headsets re-fetch `/media`.
        rotateWebDAVCredentialsAndNotify()

        if let fp = device.certFingerprint, !fp.isEmpty, let api = engineAPIClient {
            await api.unpairAllEntries(
                forFingerprint: fp,
                fallbackUUID: device.engineUUID,
                stateFileURL: store.paths.engineStateFile
            )
            refreshPairedClientsFromEngine()
        }
    }

    public func removeEngineOnlyClient(_ client: EngineClientRecord) {
        guard let api = engineAPIClient else { return }
        rotateWebDAVCredentialsAndNotify()
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

    public func retryBonjourPublish() {
        republishBonjour()
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
        let baseName = cleanURL.lastPathComponent.isEmpty
            ? HostStrings.string("runtime.default_folder_name")
            : cleanURL.lastPathComponent
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

    public func retryCloudRemote(_ entry: CloudRemoteEntry) {
        rcloneManager?.retryRemote(displayName: entry.displayName)
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
            launchAtLoginError = HostStrings.string("runtime.error.login_item")
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

    // MARK: - Protocol Adapter Operations (Internal for direct non-mock testing)

    internal func snapshotForProtocol() -> ControlProtocolSnapshot {
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

    /// Returns `true` iff `fingerprintSHA256` corresponds to a persisted paired device record
    /// (used by `ControlHTTPSServer` so arbitrary self-signed certs cannot bypass rate limits).
    internal func isKnownPairedCertificate(fingerprintSHA256: String) -> Bool {
        pairedDevices.contains {
            $0.certFingerprint?.caseInsensitiveCompare(fingerprintSHA256) == .orderedSame
        }
    }

    /// Strict certificate-fingerprint media authorization (`docs/specs/host-protocol.md` v1):
    /// - Does NOT depend on `X-Depth3d-Device` header
    /// - Requires `peerCertFingerprintSHA256` to match a persisted `PairedDeviceRecord`
    /// - Requires that certificate to be currently **enabled and unique** in the engine's `state.json`
    internal func authorizeMediaClient(peerCertFingerprintSHA256: String) -> Bool {
        guard let store else { return false }
        guard let idx = pairedDevices.firstIndex(where: {
            $0.certFingerprint?.caseInsensitiveCompare(peerCertFingerprintSHA256) == .orderedSame
        }) else {
            return false
        }
        var record = pairedDevices[idx]
        guard let boundFP = record.certFingerprint, !boundFP.isEmpty else {
            return false
        }

        guard EngineConfigGenerator.isCertificateUniquelyEnabled(
            fingerprintSHA256: boundFP,
            at: store.paths.engineStateFile
        ) else {
            return false
        }

        record.lastSeenAt = Date()
        if let updated = try? store.upsertPairedDevice(record) {
            pairedDevices = updated
        }
        return true
    }

    internal func knownBoundCertFingerprints(forDeviceId deviceId: String) -> Set<String> {
        Set(
            pairedDevices
                .filter { $0.deviceId == deviceId }
                .compactMap { $0.certFingerprint?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
                .filter { !$0.isEmpty }
        )
    }

    /// Step 1 of two-step pairing (`POST /depth3d/v1/pair`):
    /// - Returns IMMEDIATELY with `next: "done"` (cert already uniquely enabled in engine) or
    ///   `next: "gamestream"` (spawns 60s background PIN feeder for `clientAddress`) plus an opaque
    ///   120s `ticket`. NEVER waits for GameStream pairing to finish here.
    internal func issueApprovedPairingTicket(
        deviceName: String,
        deviceId: String,
        pin: String,
        clientAddress: String,
        peerCertFingerprintSHA256: String?,
        autoAllowFuture: Bool,
        approvedViaAutoAllow: Bool
    ) async -> PairTicketIssueResult {
        pruneExpiredPairingTickets()
        let fallbackReason = streamReadyInfo.reason ?? HostStrings.string("protocol.stream.unavailable")
        guard let store, let api = engineAPIClient, engineState.isReadyForClients else {
            return .streamNotReady(reason: fallbackReason)
        }

        let targetIP = IPAddressNormalizer.normalize(clientAddress)
        let alreadyUniquelyPaired: Bool
        if let peerFP = peerCertFingerprintSHA256?.trimmingCharacters(in: .whitespacesAndNewlines), !peerFP.isEmpty {
            alreadyUniquelyPaired = EngineConfigGenerator.isCertificateUniquelyEnabled(
                fingerprintSHA256: peerFP,
                at: store.paths.engineStateFile
            )
        } else {
            alreadyUniquelyPaired = false
        }

        let nextStep = alreadyUniquelyPaired ? "done" : "gamestream"
        let ticket = SecureRandom.hexString(byteCount: 24)

        // Cancel any earlier superseded feeder from the same source IP so two feeders never collide.
        for (oldKey, oldEntry) in pendingPairingTickets where oldEntry.clientIP == targetIP {
            oldEntry.feederTask?.cancel()
            pendingPairingTickets.removeValue(forKey: oldKey)
        }

        var feederTask: Task<Void, Never>?
        if nextStep == "gamestream" {
            let maxWait = pinFeederWindowSeconds
            feederTask = Task.detached { [api] in
                _ = await api.feedPINForApprovedSession(
                    pin: pin,
                    deviceName: deviceName,
                    clientAddress: targetIP,
                    maxWait: maxWait
                )
            }
        }

        pendingPairingTickets[ticket] = PendingPairingTicket(
            ticket: ticket,
            clientIP: targetIP,
            deviceName: deviceName,
            deviceId: deviceId,
            rememberAutoAllow: autoAllowFuture,
            approvedViaAutoAllow: approvedViaAutoAllow,
            expiresAt: Date().addingTimeInterval(ticketTTLSeconds),
            feederTask: feederTask
        )

        HostLogger.info(
            "Issued pairing ticket for \(deviceName) (\(targetIP), next=\(nextStep))",
            category: "pair"
        )
        return .issued(next: nextStep, ticket: ticket)
    }

    /// Step 2 of two-step pairing (`POST /depth3d/v1/pair/complete`):
    /// - Validates `ticket` existence/expiry (120s) and matching source IP
    /// - Verifies the TLS handshake client certificate fingerprint is enabled in engine `state.json`
    /// - Deduplicates duplicate entries for that certificate (keeps newest enabled, unpairs extras)
    /// - Persists `PairedDeviceRecord` keyed strictly by certificate fingerprint (never overwriting
    ///   a different certificate that shares `deviceId`)
    internal func completePairing(
        ticket: String,
        deviceName: String?,
        deviceId: String?,
        clientAddress: String,
        peerCertFingerprintSHA256: String
    ) async -> PairCompleteOutcome {
        pruneExpiredPairingTickets()
        let normalizedIP = IPAddressNormalizer.normalize(clientAddress)
        let normalizedFP = peerCertFingerprintSHA256.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()

        guard let entry = pendingPairingTickets[ticket] else {
            return .ticketExpired
        }
        guard entry.clientIP == normalizedIP else {
            HostLogger.warning("Pairing complete source mismatch: expected \(entry.clientIP), got \(normalizedIP)", category: "pair")
            return .sourceMismatch
        }
        guard let store, let api = engineAPIClient, engineState.isReadyForClients else {
            let reason = streamReadyInfo.reason ?? HostStrings.string("protocol.stream.unavailable")
            return .streamNotReady(reason: reason)
        }

        guard let verifiedCert = await api.ensureUniqueEnabledCert(
            fingerprintSHA256: normalizedFP,
            stateFileURL: store.paths.engineStateFile
        ) else {
            // Do NOT consume `ticket` or stop the feeder: headset may retry within 5s while engine flushes.
            return .notPaired
        }

        // Ticket verified & certificate uniquely enabled in engine: consume ticket & persist record.
        pendingPairingTickets.removeValue(forKey: ticket)
        entry.feederTask?.cancel()

        let finalName = (deviceName?.isEmpty == false ? deviceName! : entry.deviceName)
        let finalDeviceId = (deviceId?.isEmpty == false ? deviceId! : entry.deviceId)

        await persistVerifiedPairing(
            deviceName: finalName,
            deviceId: finalDeviceId,
            engineUUID: verifiedCert.uuid,
            certFingerprintSHA256: normalizedFP,
            rememberAutoAllow: entry.rememberAutoAllow,
            approvedViaAutoAllow: entry.approvedViaAutoAllow
        )
        refreshPairedClientsFromEngine()
        return .completed(mediaPort: webDAVPort)
    }

    internal func cancelPendingEnginePairings(forClientAddress clientAddress: String) async {
        await engineAPIClient?.cancelPendingPairings(forIP: clientAddress)
    }

    private func pruneExpiredPairingTickets() {
        let now = Date()
        var expiredIPs: [String] = []
        for (key, entry) in pendingPairingTickets where entry.expiresAt <= now {
            entry.feederTask?.cancel()
            pendingPairingTickets.removeValue(forKey: key)
            expiredIPs.append(entry.clientIP)
        }
        if !expiredIPs.isEmpty, let api = engineAPIClient {
            for ip in Set(expiredIPs) {
                Task { await api.cancelPendingPairings(forIP: ip) }
            }
        }
    }

    private func persistVerifiedPairing(
        deviceName: String,
        deviceId: String,
        engineUUID: String,
        certFingerprintSHA256: String,
        rememberAutoAllow: Bool,
        approvedViaAutoAllow: Bool
    ) async {
        guard let store else { return }
        let normalizedFP = certFingerprintSHA256.uppercased()

        // Primary key lookup is STRICTLY by certificate fingerprint (P4):
        // an identity-changed re-pair with a different fingerprint creates a NEW record and NEVER
        // overwrites or modifies the original certificate's record!
        let existingByFP = pairedDevices.first(where: {
            $0.certFingerprint?.caseInsensitiveCompare(normalizedFP) == .orderedSame
        })

        // Upgrade legacy unbound stubs (`certFingerprint == nil`) sharing `deviceId`, if any,
        // without ever touching any record that already has a bound certificate fingerprint.
        if existingByFP == nil,
           let legacyStub = pairedDevices.first(where: { $0.certFingerprint == nil && $0.deviceId == deviceId })
        {
            if let cleaned = try? store.removePairedDevice(id: legacyStub.id) {
                self.pairedDevices = cleaned
            }
        }

        let shouldAutoAllow: Bool
        if let existing = existingByFP {
            shouldAutoAllow = approvedViaAutoAllow ? true : (rememberAutoAllow || existing.autoAllow)
        } else {
            // Brand-new certificate (including identity-changed deviceId): NEVER inherit any old record's autoAllow!
            shouldAutoAllow = rememberAutoAllow
        }

        let record = PairedDeviceRecord(
            deviceId: deviceId,
            name: deviceName,
            engineUUID: engineUUID,
            certFingerprint: normalizedFP,
            autoAllow: shouldAutoAllow,
            pairedAt: existingByFP?.pairedAt ?? Date(),
            lastSeenAt: Date()
        )
        if let saved = try? store.upsertPairedDevice(record) {
            self.pairedDevices = saved
        }
        if let coordinator = pairingCoordinator {
            await coordinator.setAutoAllowed(certFingerprintSHA256: normalizedFP, enabled: shouldAutoAllow)
        }
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
            // Enqueue directly on RcloneBridgeManager's serial FIFO queue so rapid updates
            // never race on a concurrent global queue and overwrite newer lists with stale ones.
            rclone.syncRemotesAsync(settings.cloudRemotes)
            if !davServer.isRunning {
                let desired = overrides.webDAVPort ?? HostPortDefaults.defaultWebDAVPort
                do {
                    let actualPort = try davServer.start(preferredPort: desired)
                    self.webDAVPort = actualPort
                } catch {
                    HostLogger.error("Failed to start WebDAV media server: \(error.localizedDescription)", category: "media")
                    self.webDAVPort = nil
                    self.webDAVError = HostStrings.string("runtime.error.webdav_start")
                }
            } else {
                self.webDAVPort = davServer.port
            }
        } else {
            davServer.stop()
            rclone.pauseAllAsync(configuredRemotes: settings.cloudRemotes)
            self.webDAVPort = nil
        }
    }

    private func rotateWebDAVCredentialsAndNotify() {
        guard let store else { return }
        do {
            let rotated = try store.rotateWebDAVCredentials()
            self.webDAVCredentials = rotated
            self.webDAVHandler?.updateConfiguration(credentials: rotated)
            self.mediaCredentialRevision += 1
            HostLogger.info("Rotated WebDAV credentials after device removal (mrev=\(mediaCredentialRevision))", category: "media")
            updateBonjourTXT()
        } catch {
            HostLogger.error("Failed to rotate WebDAV credentials: \(error.localizedDescription)", category: "media")
        }
    }

    private func republishBonjour() {
        guard controlPort > 0 else { return }
        let gsPort: UInt16? = engineState.isReadyForClients ? activeEngineBasePort : nil
        bonjourAdvertiser?.publishHost(
            name: settings.computerName,
            hostId: identity.id,
            controlPort: controlPort,
            gsPort: gsPort,
            mediaPort: webDAVPort,
            mediaRevision: mediaCredentialRevision
        )
    }

    private func updateBonjourTXT() {
        guard controlPort > 0 else { return }
        let gsPort: UInt16? = engineState.isReadyForClients ? activeEngineBasePort : nil
        bonjourAdvertiser?.updateHostTXT(
            name: settings.computerName,
            hostId: identity.id,
            gsPort: gsPort,
            mediaPort: webDAVPort,
            mediaRevision: mediaCredentialRevision
        )
    }

    private func persistSettings() {
        try? store?.saveSettings(settings)
    }

    private func makeUniqueMountName(_ raw: String) -> String {
        let cleaned = raw
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let base = cleaned.isEmpty ? HostStrings.string("runtime.default_media_name") : cleaned
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
                streamNotReadyReason: HostStrings.string("protocol.stream.not_initialized"),
                mediaPort: nil,
                webDAVCredentials: nil
            )
        }
    }

    func isKnownPairedCertificate(fingerprintSHA256: String) async -> Bool {
        await MainActor.run {
            runtime?.isKnownPairedCertificate(fingerprintSHA256: fingerprintSHA256) ?? false
        }
    }

    func authorizeMediaClient(peerCertFingerprintSHA256: String) async -> Bool {
        await MainActor.run {
            runtime?.authorizeMediaClient(peerCertFingerprintSHA256: peerCertFingerprintSHA256) ?? false
        }
    }

    func knownBoundCertFingerprints(forDeviceId deviceId: String) async -> Set<String> {
        await MainActor.run {
            runtime?.knownBoundCertFingerprints(forDeviceId: deviceId) ?? []
        }
    }

    func issueApprovedPairingTicket(
        deviceName: String,
        deviceId: String,
        pin: String,
        clientAddress: String,
        peerCertFingerprintSHA256: String?,
        autoAllowFuture: Bool,
        approvedViaAutoAllow: Bool
    ) async -> PairTicketIssueResult {
        guard let runtime else {
            return .streamNotReady(reason: HostStrings.string("protocol.stream.not_initialized"))
        }
        return await runtime.issueApprovedPairingTicket(
            deviceName: deviceName,
            deviceId: deviceId,
            pin: pin,
            clientAddress: clientAddress,
            peerCertFingerprintSHA256: peerCertFingerprintSHA256,
            autoAllowFuture: autoAllowFuture,
            approvedViaAutoAllow: approvedViaAutoAllow
        )
    }

    func completePairing(
        ticket: String,
        deviceName: String?,
        deviceId: String?,
        clientAddress: String,
        peerCertFingerprintSHA256: String
    ) async -> PairCompleteOutcome {
        guard let runtime else {
            return .streamNotReady(reason: HostStrings.string("protocol.stream.not_initialized"))
        }
        return await runtime.completePairing(
            ticket: ticket,
            deviceName: deviceName,
            deviceId: deviceId,
            clientAddress: clientAddress,
            peerCertFingerprintSHA256: peerCertFingerprintSHA256
        )
    }

    func cancelPendingEnginePairings(forClientAddress clientAddress: String) async {
        guard let runtime else { return }
        await runtime.cancelPendingEnginePairings(forClientAddress: clientAddress)
    }
}
