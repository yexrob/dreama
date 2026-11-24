package com.limelight.local.stream

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import com.limelight.LimeLog
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.local.WebDav
import com.limelight.local.dlna.CastReceiver
import com.limelight.local.dlna.NetIface
import com.limelight.local.dlna.NetPick
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.utils.DesktopLaunch
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** Full-screen takeover state for pairing / connecting / session failure on the Cast tab. */
sealed interface StreamFlowState {
    data object Idle : StreamFlowState

    data class AutoPairing(
        val computer: StreamingComputer,
        val stageText: String,
    ) : StreamFlowState

    data class PinPairing(
        val computer: StreamingComputer,
        val pin: String,
    ) : StreamFlowState

    data class CertConfirm(
        val computer: StreamingComputer,
        val hostId: String,
        val expectedFingerprint: String,
        val actualFingerprint: String,
        val resumeAction: CertResumeAction,
    ) : StreamFlowState

    data class Connecting(
        val computer: StreamingComputer,
        val appName: String?,
        val stageText: String,
    ) : StreamFlowState

    data class Failed(
        val computer: StreamingComputer?,
        val error: UserFacingError,
        val retryAction: RetryAction?,
        val fallbackPinComputer: StreamingComputer? = null,
    ) : StreamFlowState
}

enum class CertResumeAction {
    AUTO_PAIR,
    CONNECT,
}

sealed interface RetryAction {
    data class Pair(val computer: StreamingComputer, val forceManualPin: Boolean = false) : RetryAction
    data class Connect(val computer: StreamingComputer, val app: NvApp? = null) : RetryAction
    data class ConnectByUuid(val uuid: String, val appId: Int, val appName: String?) : RetryAction
}

data class PcAppEntry(
    val app: NvApp,
    val isRunning: Boolean,
)

sealed interface PcAppsState {
    data object Loading : PcAppsState
    data class Ready(val computer: StreamingComputer, val apps: List<PcAppEntry>) : PcAppsState
    data class Failed(val computer: StreamingComputer, val error: UserFacingError) : PcAppsState
}

/**
 * Owns PC streaming discovery, depth3d Host control protocol calls, pairing, app list loading,
 * Wake-on-LAN, and launching [com.limelight.GameXR] for the Compose UI.
 */
class StreamingController(
    private val app: Application,
    private val onAutoWebDavConfigured: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hostDiscovery = HostDiscovery(app)
    private val pinStore = SharedPrefsHostCertPinStore(app)
    private val hostApi: HostProtocolApi = HttpHostProtocolClient(
        pinStore = pinStore,
        clientIdentityProvider = {
            val crypto = PlatformBinding.getCryptoProvider(app)
            val cert = crypto.clientCertificate
            val key = crypto.clientPrivateKey
            if (cert != null && key != null) cert to key else null
        },
    )

    private val rawComputers = LinkedHashMap<String, ComputerDetails>()
    private val hostInfos = ConcurrentHashMap<String, HostInfo>()
    private val probedHosts = HashSet<String>()
    private val mediaSyncedHosts = HashSet<String>()
    private val boxArtCache = ConcurrentHashMap<String, Bitmap>()

    @Volatile
    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private var serviceBound = false
    private var foregroundActive = false
    private var activeOperationJob: Job? = null
    private var appListJob: Job? = null

    private val _computers = MutableStateFlow<List<StreamingComputer>>(emptyList())
    val computers: StateFlow<List<StreamingComputer>> get() = _computers

    private val _discovering = MutableStateFlow(true)
    val discovering: StateFlow<Boolean> get() = _discovering

    private val _hasWifi = MutableStateFlow(checkLanAvailable())
    val hasWifi: StateFlow<Boolean> get() = _hasWifi

    private val _flow = MutableStateFlow<StreamFlowState>(StreamFlowState.Idle)
    val flow: StateFlow<StreamFlowState> get() = _flow

    private val _bannerNotice = MutableStateFlow<String?>(null)
    val bannerNotice: StateFlow<String?> get() = _bannerNotice

    private val _pcApps = MutableStateFlow<PcAppsState>(PcAppsState.Loading)
    val pcApps: StateFlow<PcAppsState> get() = _pcApps

    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val computerListener = ComputerManagerListener { details ->
        if (details == null || details.uuid.isNullOrBlank()) return@ComputerManagerListener
        scope.launch {
            synchronized(rawComputers) {
                rawComputers[details.uuid] = details
            }
            _discovering.value = false
            republishComputers()
            maybeSyncHostMedia(details)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val b = service as ComputerManagerService.ComputerManagerBinder
            binder = b
            scope.launch(Dispatchers.IO) {
                b.waitForReady()
                withContext(Dispatchers.Main.immediate) {
                    if (foregroundActive && binder === b) {
                        b.startPolling(computerListener)
                        LimeLog.info("depth3d: ComputerManagerService polling started")
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }
    }

    init {
        scope.launch {
            hostDiscovery.hosts.collect { hosts ->
                if (hosts.isNotEmpty()) {
                    _discovering.value = false
                }
                republishComputers()
                hosts.forEach { probeHostIfNeeded(it) }
            }
        }
        scope.launch {
            StreamSessionBridge.pendingError.collect { pending ->
                if (pending != null) {
                    val computer = pending.computerUuid?.let { uuid ->
                        _computers.value.firstOrNull { it.id == uuid }
                    }
                    val retry = when {
                        computer != null && pending.error.retryable -> RetryAction.Connect(
                            computer,
                            if (pending.appId > 0) NvApp(pending.appName ?: "Desktop", pending.appId, false) else null,
                        )
                        !pending.computerUuid.isNullOrBlank() && pending.error.retryable -> RetryAction.ConnectByUuid(
                            pending.computerUuid,
                            pending.appId,
                            pending.appName,
                        )
                        else -> null
                    }
                    _flow.value = StreamFlowState.Failed(computer, pending.error, retry)
                    StreamSessionBridge.clearError()
                }
            }
        }
    }

    fun onStart() {
        foregroundActive = true
        _hasWifi.value = checkLanAvailable()
        watchNetworks()
        hostDiscovery.start()
        if (!serviceBound) {
            serviceBound = app.bindService(
                Intent(app, ComputerManagerService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        } else {
            binder?.startPolling(computerListener)
        }
    }

    fun onStop() {
        foregroundActive = false
        unwatchNetworks()
        hostDiscovery.stop()
        binder?.stopPolling()
    }

    fun onDestroy() {
        cancelActiveOperation()
        appListJob?.cancel()
        onStop()
        if (serviceBound) {
            try {
                app.unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
            }
            serviceBound = false
            binder = null
        }
    }

    fun dismissBanner() {
        _bannerNotice.value = null
    }

    fun clearFlow() {
        cancelActiveOperation()
        _flow.value = StreamFlowState.Idle
    }

    fun cancelActiveOperation() {
        activeOperationJob?.cancel()
        activeOperationJob = null
        if (_flow.value !is StreamFlowState.Idle) {
            _flow.value = StreamFlowState.Idle
        }
        resumePollingIfForeground()
    }

    fun localLanIp(): String? {
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { i ->
                try {
                    NetIface(
                        i.name,
                        i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
                        i.isUp,
                        i.isLoopback,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        return NetPick.choose(ifaces)?.second
    }

    fun startPair(
        computer: StreamingComputer,
        forceManualPin: Boolean = false,
        trustChangedCert: Boolean = false,
    ) {
        cancelActiveOperation()
        val b = binder
        if (b == null) {
            _flow.value = StreamFlowState.Failed(
                computer,
                UserFacingError("串流服务正在准备中", "请稍等一两秒后再点重试。"),
                RetryAction.Pair(computer, forceManualPin),
            )
            return
        }

        val useAutoPair = !forceManualPin && computer.isDepth3dHost && computer.autoPairSupported && computer.host != null
        activeOperationJob = scope.launch {
            try {
                b.stopPolling()
                withContext(Dispatchers.IO) { b.waitForPollingStopped() }

                val pin = PairingManager.generatePinString()
                val details = ensureDetailsForComputer(computer, b)
                if (details == null || details.activeAddress == null) {
                    _flow.value = StreamFlowState.Failed(
                        computer,
                        UserFacingError("连不上这台电脑", "请确认电脑开着且和头显在同一个 Wi-Fi，再点重试。"),
                        RetryAction.Pair(computer, forceManualPin),
                    )
                    resumePollingIfForeground()
                    return@launch
                }

                if (useAutoPair) {
                    val host = computer.host
                    _flow.value = StreamFlowState.AutoPairing(
                        computer = computer,
                        stageText = "请在电脑上点「允许」",
                    )
                    val pairResp = withContext(Dispatchers.IO) {
                        hostApi.requestAutoPair(
                            address = host.address,
                            port = host.controlPort,
                            hostId = host.id,
                            deviceName = CastReceiver.name(app),
                            deviceId = b.uniqueId,
                            pin = pin,
                            trustChangedCert = trustChangedCert,
                        )
                    }
                    when (pairResp) {
                        AutoPairResult.Approved -> {
                            _flow.value = StreamFlowState.AutoPairing(
                                computer = computer,
                                stageText = "电脑已允许，正在完成配对…",
                            )
                            completeGameStreamPair(computer, details, b, pin, allowManualFallback = true)
                        }
                        is AutoPairResult.CertificateChanged -> {
                            _flow.value = StreamFlowState.CertConfirm(
                                computer = computer,
                                hostId = pairResp.hostId,
                                expectedFingerprint = pairResp.expected,
                                actualFingerprint = pairResp.actual,
                                resumeAction = CertResumeAction.AUTO_PAIR,
                            )
                            resumePollingIfForeground()
                        }
                        is AutoPairResult.Failed -> {
                            _flow.value = StreamFlowState.Failed(
                                computer = computer,
                                error = StreamErrorMapper.forAutoPair(pairResp),
                                retryAction = RetryAction.Pair(computer, forceManualPin = false),
                                fallbackPinComputer = computer,
                            )
                            resumePollingIfForeground()
                        }
                        else -> {
                            _flow.value = StreamFlowState.Failed(
                                computer = computer,
                                error = StreamErrorMapper.forAutoPair(pairResp),
                                retryAction = if (pairResp != AutoPairResult.UpgradeRequired) {
                                    RetryAction.Pair(computer, forceManualPin = false)
                                } else null,
                                fallbackPinComputer = if (pairResp == AutoPairResult.TimedOut) computer else null,
                            )
                            resumePollingIfForeground()
                        }
                    }
                } else {
                    _flow.value = StreamFlowState.PinPairing(computer, pin)
                    completeGameStreamPair(computer, details, b, pin, allowManualFallback = false)
                }
            } catch (_: CancellationException) {
                resumePollingIfForeground()
            } catch (e: Exception) {
                LimeLog.warning("depth3d: pair error: ${e.javaClass.simpleName}")
                _flow.value = StreamFlowState.Failed(
                    computer,
                    StreamErrorMapper.forThrowable(e, "配对"),
                    RetryAction.Pair(computer, forceManualPin),
                )
                resumePollingIfForeground()
            }
        }
    }

    private suspend fun completeGameStreamPair(
        computer: StreamingComputer,
        details: ComputerDetails,
        b: ComputerManagerService.ComputerManagerBinder,
        pin: String,
        allowManualFallback: Boolean,
    ) {
        val result = withContext(Dispatchers.IO) {
            val http = NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(details),
                details.httpsPort,
                b.uniqueId,
                details.serverCert,
                PlatformBinding.getCryptoProvider(app),
            )
            val serverInfo = http.getServerInfo(true)
            val alreadyPaired = http.getPairState(serverInfo) == PairingManager.PairState.PAIRED
            if (alreadyPaired) {
                PairingManager.PairState.PAIRED to details.serverCert
            } else {
                val pm = http.pairingManager
                val state = pm.pair(serverInfo, pin)
                state to pm.pairedCert
            }
        }
        val (pairState, cert) = result
        if (pairState == PairingManager.PairState.PAIRED) {
            val managed = b.getComputer(details.uuid)
            if (managed != null && cert != null) {
                managed.serverCert = cert
                managed.pairState = PairingManager.PairState.PAIRED
            }
            if (!details.uuid.isNullOrBlank()) {
                b.invalidateStateForComputer(details.uuid)
            }
            details.pairState = PairingManager.PairState.PAIRED
            if (cert != null) {
                details.serverCert = cert
            }
            synchronized(rawComputers) {
                if (!details.uuid.isNullOrBlank()) {
                    rawComputers[details.uuid] = details
                }
            }
            republishComputers()
            maybeSyncHostMedia(details)
            _flow.value = StreamFlowState.Idle
            _bannerNotice.value = "已和「${computer.name}」配对，点一下即可连接"
            resumePollingIfForeground()
        } else {
            _flow.value = StreamFlowState.Failed(
                computer = computer,
                error = StreamErrorMapper.forPairState(pairState, details.runningGameId != 0),
                retryAction = RetryAction.Pair(computer, forceManualPin = !allowManualFallback),
                fallbackPinComputer = if (allowManualFallback) computer else null,
            )
            resumePollingIfForeground()
        }
    }

    fun confirmChangedCertificate(
        activity: Activity,
        state: StreamFlowState.CertConfirm,
        onOpenAppGrid: (StreamingComputer) -> Unit,
    ) {
        when (state.resumeAction) {
            CertResumeAction.AUTO_PAIR -> startPair(state.computer, forceManualPin = false, trustChangedCert = true)
            CertResumeAction.CONNECT -> connect(activity, state.computer, appOverride = null, trustChangedCert = true, onOpenAppGrid = onOpenAppGrid)
        }
    }

    fun connect(
        activity: Activity,
        computer: StreamingComputer,
        appOverride: NvApp? = null,
        trustChangedCert: Boolean = false,
        onOpenAppGrid: (StreamingComputer) -> Unit = {},
    ) {
        cancelActiveOperation()
        val b = binder
        if (b == null) {
            _flow.value = StreamFlowState.Failed(
                computer,
                UserFacingError("串流服务正在准备中", "请稍等一两秒后再点重试。"),
                RetryAction.Connect(computer, appOverride),
            )
            return
        }
        val details = findDetails(computer, b)
        if (details == null || details.state == ComputerDetails.State.OFFLINE || details.activeAddress == null) {
            _flow.value = StreamFlowState.Failed(
                computer,
                UserFacingError("「${computer.name}」当前离线", "请先唤醒或打开电脑，再点重试。"),
                RetryAction.Connect(computer, appOverride),
            )
            return
        }

        _flow.value = StreamFlowState.Connecting(
            computer = computer,
            appName = appOverride?.appName,
            stageText = "正在检查电脑状态…",
        )
        activeOperationJob = scope.launch {
            try {
                // 1. If this is a depth3d Host, verify readiness via /depth3d/v1/info
                val host = computer.host
                if (host != null) {
                    val infoResult = withContext(Dispatchers.IO) {
                        hostApi.getInfo(host.address, host.controlPort, host.id, trustChangedCert)
                    }
                    when (infoResult) {
                        is HostInfoResult.Ok -> {
                            hostInfos[host.id] = infoResult.info
                            republishComputers()
                            if (!infoResult.info.stream.ready) {
                                _flow.value = StreamFlowState.Failed(
                                    computer,
                                    StreamErrorMapper.forHostNotReady(infoResult.info.stream.reason),
                                    RetryAction.Connect(computer, appOverride),
                                )
                                return@launch
                            }
                        }
                        HostInfoResult.UpgradeRequired -> {
                            _flow.value = StreamFlowState.Failed(
                                computer,
                                StreamErrorMapper.forHostUpgradeRequired(),
                                retryAction = null,
                            )
                            return@launch
                        }
                        is HostInfoResult.CertificateChanged -> {
                            _flow.value = StreamFlowState.CertConfirm(
                                computer = computer,
                                hostId = infoResult.hostId,
                                expectedFingerprint = infoResult.expected,
                                actualFingerprint = infoResult.actual,
                                resumeAction = CertResumeAction.CONNECT,
                            )
                            return@launch
                        }
                        is HostInfoResult.Failed -> {
                            // Control port unreachable; proceed with GameStream connect directly if reachable.
                            LimeLog.warning("depth3d: host /info unreachable, continuing direct stream connect")
                        }
                    }
                }

                // 2. Pick app to start: explicit app > running game > Desktop > single app > app grid
                val targetApp: NvApp = if (appOverride != null) {
                    appOverride
                } else if (details.runningGameId != 0) {
                    NvApp(computer.name, details.runningGameId, false)
                } else {
                    _flow.value = StreamFlowState.Connecting(computer, null, "正在获取桌面信息…")
                    val apps = withContext(Dispatchers.IO) {
                        val http = NvHTTP(
                            ServerHelper.getCurrentAddressFromComputer(details),
                            details.httpsPort,
                            b.uniqueId,
                            details.serverCert,
                            PlatformBinding.getCryptoProvider(app),
                        )
                        DesktopLaunch.parse(http.appListRaw)
                    }
                    val chosen = DesktopLaunch.choose(apps, details.runningGameId)
                        ?: apps.singleOrNull()
                    if (chosen == null) {
                        _flow.value = StreamFlowState.Idle
                        onOpenAppGrid(computer)
                        return@launch
                    }
                    chosen
                }

                _flow.value = StreamFlowState.Connecting(
                    computer = computer,
                    appName = targetApp.appName,
                    stageText = "正在进入 3D 画面…",
                )
                ServerHelper.doStartVrStream(activity, targetApp, details, b)
            } catch (_: CancellationException) {
                // User canceled
            } catch (e: Exception) {
                LimeLog.warning("depth3d: connect failed: ${e.javaClass.simpleName}")
                _flow.value = StreamFlowState.Failed(
                    computer,
                    StreamErrorMapper.forThrowable(e, "连接"),
                    RetryAction.Connect(computer, appOverride),
                )
            }
        }
    }

    fun retry(activity: Activity, action: RetryAction, onOpenAppGrid: (StreamingComputer) -> Unit) {
        when (action) {
            is RetryAction.Pair -> startPair(action.computer, forceManualPin = action.forceManualPin)
            is RetryAction.Connect -> connect(activity, action.computer, action.app, onOpenAppGrid = onOpenAppGrid)
            is RetryAction.ConnectByUuid -> {
                val found = _computers.value.firstOrNull { it.id == action.uuid }
                if (found != null) {
                    val appObj = if (action.appId > 0) NvApp(action.appName ?: "Desktop", action.appId, false) else null
                    connect(activity, found, appObj, onOpenAppGrid = onOpenAppGrid)
                } else {
                    _flow.value = StreamFlowState.Idle
                }
            }
        }
    }

    fun loadApps(computer: StreamingComputer) {
        appListJob?.cancel()
        val b = binder
        val details = if (b != null) findDetails(computer, b) else null
        if (b == null || details == null || details.activeAddress == null) {
            _pcApps.value = PcAppsState.Failed(
                computer,
                UserFacingError("连不上「${computer.name}」", "请确认电脑在线并且和头显在同一个 Wi-Fi。"),
            )
            return
        }
        _pcApps.value = PcAppsState.Loading
        appListJob = scope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val http = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(details),
                        details.httpsPort,
                        b.uniqueId,
                        details.serverCert,
                        PlatformBinding.getCryptoProvider(app),
                    )
                    http.appList
                }
                val entries = apps.map { nvApp ->
                    PcAppEntry(nvApp, isRunning = details.runningGameId != 0 && nvApp.appId == details.runningGameId)
                }.sortedWith(compareByDescending<PcAppEntry> { it.isRunning }.thenBy { it.app.appName.lowercase() })
                _pcApps.value = PcAppsState.Ready(computer, entries)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                LimeLog.warning("depth3d: app list failed: ${e.javaClass.simpleName}")
                _pcApps.value = PcAppsState.Failed(computer, StreamErrorMapper.forThrowable(e, "获取应用列表"))
            }
        }
    }

    suspend fun loadBoxArt(computer: StreamingComputer, nvApp: NvApp): Bitmap? {
        val key = "${computer.id}:${nvApp.appId}"
        boxArtCache[key]?.let { return it }
        val b = binder ?: return null
        val details = findDetails(computer, b) ?: return null
        if (details.activeAddress == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val http = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(details),
                    details.httpsPort,
                    b.uniqueId,
                    details.serverCert,
                    PlatformBinding.getCryptoProvider(app),
                )
                http.getBoxArt(nvApp).use { stream ->
                    BitmapFactory.decodeStream(stream)?.also { bmp ->
                        boxArtCache[key] = bmp
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun quitRunningApp(computer: StreamingComputer, onDone: (Boolean) -> Unit = {}) {
        val b = binder ?: return
        val details = findDetails(computer, b) ?: return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    val http = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(details),
                        details.httpsPort,
                        b.uniqueId,
                        details.serverCert,
                        PlatformBinding.getCryptoProvider(app),
                    )
                    http.quitApp()
                }
                if (ok) {
                    details.runningGameId = 0
                    b.invalidateStateForComputer(details.uuid)
                    republishComputers()
                    _bannerNotice.value = "已结束「${computer.name}」上正在运行的应用"
                } else {
                    _bannerNotice.value = "没能结束正在运行的应用，请在电脑上关闭"
                }
                onDone(ok)
            } catch (e: Exception) {
                val err = StreamErrorMapper.forThrowable(e, "结束应用")
                _bannerNotice.value = "${err.title}：${err.detail}"
                onDone(false)
            }
        }
    }

    fun wakeComputer(computer: StreamingComputer) {
        val b = binder ?: return
        val details = findDetails(computer, b)
        if (details == null || details.macAddress.isNullOrBlank()) {
            val err = StreamErrorMapper.forWakeNoMac()
            _bannerNotice.value = "${err.title}，${err.detail}"
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    WakeOnLanSender.sendWolPacket(details)
                    true
                } catch (e: Exception) {
                    LimeLog.warning("depth3d: WoL failed: ${e.javaClass.simpleName}")
                    false
                }
            }
            _bannerNotice.value = if (ok) {
                "已向「${computer.name}」发送唤醒请求，请稍等几秒"
            } else {
                val err = StreamErrorMapper.forWakeFailed()
                "${err.title}，${err.detail}"
            }
        }
    }

    fun removeComputer(computer: StreamingComputer) {
        val b = binder ?: return
        val details = findDetails(computer, b) ?: return
        b.removeComputer(details)
        synchronized(rawComputers) {
            rawComputers.remove(details.uuid)
        }
        computer.host?.id?.let { pinStore.clearFingerprint(it) }
        republishComputers()
        _bannerNotice.value = "已移除「${computer.name}」"
    }

    /** Adds a PC by IP/hostname (from phone QR form or optional headset keyboard). */
    suspend fun addComputerByAddress(rawInput: String): UserFacingError? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return StreamErrorMapper.forInvalidAddress()
        val b = binder ?: return UserFacingError("串流服务正在准备中", "请稍后再试。")
        val (host, port) = parseHostPort(trimmed)
        val fake = ComputerDetails().apply {
            manualAddress = ComputerDetails.AddressTuple(host, port)
        }
        return withContext(Dispatchers.IO) {
            try {
                val added = b.addComputerBlocking(fake)
                if (added) {
                    null
                } else {
                    StreamErrorMapper.forAddComputerFailed(trimmed)
                }
            } catch (e: Exception) {
                StreamErrorMapper.forThrowable(e, "添加电脑")
            }
        }
    }

    private suspend fun ensureDetailsForComputer(
        computer: StreamingComputer,
        b: ComputerManagerService.ComputerManagerBinder,
    ): ComputerDetails? {
        findDetails(computer, b)?.let { return it }
        val host = computer.host ?: return null
        val fake = ComputerDetails().apply {
            localAddress = ComputerDetails.AddressTuple(host.address, host.gsPort)
            activeAddress = localAddress
            name = host.name
        }
        withContext(Dispatchers.IO) {
            try {
                b.addComputerBlocking(fake)
            } catch (_: Exception) {
            }
        }
        return findDetails(computer, b) ?: synchronized(rawComputers) {
            rawComputers.values.firstOrNull { RawComputer.from(it).matchesHost(host) }
        }
    }

    private fun findDetails(
        computer: StreamingComputer,
        b: ComputerManagerService.ComputerManagerBinder,
    ): ComputerDetails? {
        if (!computer.id.startsWith("host:")) {
            b.getComputer(computer.id)?.let { return it }
            synchronized(rawComputers) {
                rawComputers[computer.id]?.let { return it }
            }
        }
        val host = computer.host ?: return null
        synchronized(rawComputers) {
            return rawComputers.values.firstOrNull { RawComputer.from(it).matchesHost(host) }
        }
    }

    private fun probeHostIfNeeded(host: DiscoveredHost) {
        if (!probedHosts.add(host.id)) return
        scope.launch(Dispatchers.IO) {
            val res = hostApi.getInfo(host.address, host.controlPort, host.id, trustChangedCert = false)
            if (res is HostInfoResult.Ok) {
                hostInfos[host.id] = res.info
                withContext(Dispatchers.Main.immediate) {
                    republishComputers()
                }
            }
        }
    }

    private fun maybeSyncHostMedia(details: ComputerDetails) {
        if (details.pairState != PairingManager.PairState.PAIRED) return
        val raw = RawComputer.from(details)
        val host = hostDiscovery.hosts.value.firstOrNull { raw.matchesHost(it) } ?: return
        val mediaPort = hostInfos[host.id]?.media?.port ?: host.mediaPort ?: return
        if (mediaPort <= 0 || !mediaSyncedHosts.add(host.id)) return
        val b = binder ?: return
        // Only auto-configure WebDAV if none is saved yet, so we don't overwrite a user's manual setup
        if (WebDav.load(app) != null) return
        scope.launch(Dispatchers.IO) {
            val res = hostApi.getMediaCredentials(host.address, host.controlPort, host.id, b.uniqueId)
            if (res is MediaCredsResult.Available) {
                val base = "http://${host.address}:${res.creds.port}"
                WebDav.save(app, WebDav.Server(base, res.creds.user, res.creds.pass))
                LimeLog.info("depth3d: auto-configured WebDAV media source from host ${host.name}")
                withContext(Dispatchers.Main.immediate) {
                    onAutoWebDavConfigured()
                }
            }
        }
    }

    private fun resumePollingIfForeground() {
        if (foregroundActive) {
            binder?.startPolling(computerListener)
        }
    }

    private fun republishComputers() {
        val rawList = synchronized(rawComputers) {
            rawComputers.values.map { RawComputer.from(it) }
        }
        _computers.value = ComputerListMerger.merge(
            computers = rawList,
            discoveredHosts = hostDiscovery.hosts.value,
            hostInfos = hostInfos,
        )
    }

    private fun checkLanAvailable(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun watchNetworks() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = cm
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val wasOffline = !_hasWifi.value
                    _hasWifi.value = true
                    if (wasOffline && foregroundActive) {
                        hostDiscovery.stop()
                        hostDiscovery.start()
                        binder?.startPolling(computerListener)
                    }
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    _hasWifi.value = checkLanAvailable()
                }
            }
        }
        networkCallback = cb
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
        } catch (e: RuntimeException) {
            networkCallback = null
            LimeLog.warning("depth3d: network watcher unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun unwatchNetworks() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            connectivity?.unregisterNetworkCallback(cb)
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        internal fun parseHostPort(input: String): Pair<String, Int> {
            val clean = input.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
            if (clean.startsWith('[')) {
                val close = clean.indexOf(']')
                if (close > 0) {
                    val host = clean.substring(1, close)
                    val rest = clean.substring(close + 1)
                    val port = if (rest.startsWith(':')) rest.substring(1).toIntOrNull() else null
                    return host to (port?.takeIf { it in 1..65535 } ?: NvHTTP.DEFAULT_HTTP_PORT)
                }
            }
            val firstColon = clean.indexOf(':')
            val lastColon = clean.lastIndexOf(':')
            if (firstColon > 0 && firstColon == lastColon) {
                val port = clean.substring(lastColon + 1).toIntOrNull()
                if (port != null && port in 1..65535) {
                    return clean.substring(0, lastColon) to port
                }
            }
            return clean to NvHTTP.DEFAULT_HTTP_PORT
        }
    }
}
