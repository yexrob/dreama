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
import com.limelight.R
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
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.DesktopLaunch
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.cert.X509Certificate
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

private data class ConnectAfterPairRequest(
    val activity: Activity,
    val appOverride: NvApp?,
    val onOpenAppGrid: (StreamingComputer) -> Unit,
)

/**
 * Owns PC streaming discovery, depth3d Host control protocol calls, pairing, app list loading,
 * Wake-on-LAN, decoder crash recovery, and launching [com.limelight.GameXR] for the Compose UI.
 */
class StreamingController(
    private val app: Application,
    private val onAutoWebDavConfigured: () -> Unit = {},
) {
    private val strings: StringLookup = AndroidStringLookup(app)
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
    // Keyed by DiscoveredHost.endpointKey so an imposter copying host.id cannot overwrite a real Host (#7).
    private val hostInfosByEndpoint = ConcurrentHashMap<String, HostInfo>()
    // In-memory observed fingerprints per endpoint: used by #6 (detect cert change between discovery & pair)
    // and #7 (match trusted Host endpoints strictly by TLS fingerprint).
    private val observedFingerprintsByEndpoint = ConcurrentHashMap<String, String>()
    private val infoRetry = HostRetryTracker()
    private val mediaRetry = HostRetryTracker()
    private val lastMediaRevisionByEndpoint = ConcurrentHashMap<String, Int>()
    private val forceRefreshMediaEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val retryJobs = ConcurrentHashMap<String, Job>()
    private val boxArtCache = BoundedLruCache<String, Bitmap>(MAX_BOX_ART_ENTRIES)
    private val networkTracker = NetworkChangeTracker(currentLanNetworkId())
    // #8: Track ALL cancelled pairing cleanup jobs so rapid cancel -> retry -> cancel never drops one.
    private val pendingCleanups = PendingCleanupQueue()

    @Volatile
    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private var serviceBound = false
    private var foregroundActive = false
    private var activeOperationJob: Job? = null
    private var activeCancelToken: CallCancelToken? = null
    private var appListJob: Job? = null
    private var discoveryTimeoutJob: Job? = null
    private var knownEndpointKeys = emptySet<String>()

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

    private val _crashPrompt = MutableStateFlow<CrashRecoveryPrompt?>(null)
    val crashPrompt: StateFlow<CrashRecoveryPrompt?> get() = _crashPrompt

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
            cancelDiscoveryTimeout()
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
                val currentEndpoints = hosts.map { it.endpointKey }.toSet()
                val lostEndpoints = knownEndpointKeys - currentEndpoints
                for (lost in lostEndpoints) {
                    infoRetry.clear(lost)
                    mediaRetry.clear(lost)
                    hostInfosByEndpoint.remove(lost)
                    observedFingerprintsByEndpoint.remove(lost)
                    lastMediaRevisionByEndpoint.remove(lost)
                    forceRefreshMediaEndpoints.remove(lost)
                    retryJobs.remove("info:$lost")?.cancel()
                    retryJobs.remove("media:$lost")?.cancel()
                }
                for (host in hosts) {
                    val rev = host.mediaRevision
                    if (rev != null) {
                        val prev = lastMediaRevisionByEndpoint.put(host.endpointKey, rev)
                        if (prev != null && prev != rev) {
                            mediaRetry.clear(host.endpointKey)
                            forceRefreshMediaEndpoints.add(host.endpointKey)
                        }
                    }
                }
                knownEndpointKeys = currentEndpoints
                if (hosts.isNotEmpty()) {
                    cancelDiscoveryTimeout()
                    _discovering.value = false
                }
                republishComputers()
                hosts.forEach { scheduleHostInfoProbe(it) }
                synchronized(rawComputers) { rawComputers.values.toList() }
                    .forEach { maybeSyncHostMedia(it) }
            }
        }
        scope.launch {
            StreamSessionBridge.pendingError.collect { pending ->
                if (pending != null) {
                    val computer = pending.computerUuid?.let { uuid ->
                        _computers.value.firstOrNull { it.id == uuid }
                    }
                    val defaultDesktop = app.getString(R.string.stream_default_desktop_name)
                    val retry = when {
                        computer != null && pending.error.retryable -> RetryAction.Connect(
                            computer,
                            if (pending.appId > 0) NvApp(pending.appName ?: defaultDesktop, pending.appId, false) else null,
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
        if (_flow.value is StreamFlowState.Connecting && activeOperationJob?.isActive != true) {
            _flow.value = StreamFlowState.Idle
        }
        checkDecoderCrashRecovery()
        watchNetworks()
        hostDiscovery.start()
        if (_computers.value.isEmpty()) {
            startDiscoveryTimeout()
        }
        if (!serviceBound) {
            serviceBound = app.bindService(
                Intent(app, ComputerManagerService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        } else {
            binder?.startPolling(computerListener)
        }
        hostDiscovery.hosts.value.forEach { scheduleHostInfoProbe(it) }
    }

    fun onStop() {
        foregroundActive = false
        cancelDiscoveryTimeout()
        unwatchNetworks()
        hostDiscovery.stop()
        binder?.stopPolling()
    }

    fun onDestroy() {
        cancelActiveOperation()
        appListJob?.cancel()
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
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

    /** Re-runs discovery spinner for 8 seconds and resets transient host probe backoffs. */
    fun refreshDiscovery() {
        if (_computers.value.isEmpty()) {
            _discovering.value = true
            startDiscoveryTimeout()
        }
        infoRetry.resetAll()
        mediaRetry.resetAll()
        if (foregroundActive) {
            hostDiscovery.stop()
            hostDiscovery.start()
            binder?.startPolling(computerListener)
            hostDiscovery.hosts.value.forEach { scheduleHostInfoProbe(it) }
        }
    }

    fun checkDecoderCrashRecovery() {
        val tombstone = AndroidPrefsStore.namedPrefs(app, DecoderCrashRecovery.TOMBSTONE_PREFS_NAME)
        val streamingPrefs = AndroidPrefsStore.defaultPrefs(app)
        val isGen1 = PreferenceConfiguration.isXr2Gen1Headset()
        val prompt = DecoderCrashRecovery.checkAndPrepare(tombstone, streamingPrefs, isGen1)
        if (prompt != null) {
            _crashPrompt.value = prompt
        }
    }

    fun resetStreamingAfterCrash() {
        val tombstone = AndroidPrefsStore.namedPrefs(app, DecoderCrashRecovery.TOMBSTONE_PREFS_NAME)
        val streamingPrefs = AndroidPrefsStore.defaultPrefs(app)
        val isGen1 = PreferenceConfiguration.isXr2Gen1Headset()
        DecoderCrashRecovery.resetDefaultsAndClear(tombstone, streamingPrefs, isGen1)
        _crashPrompt.value = null
        _bannerNotice.value = app.getString(R.string.stream_crash_reset_done)
    }

    fun dismissCrashPrompt() {
        val prompt = _crashPrompt.value ?: return
        val tombstone = AndroidPrefsStore.namedPrefs(app, DecoderCrashRecovery.TOMBSTONE_PREFS_NAME)
        DecoderCrashRecovery.acknowledge(tombstone, prompt.crashCount)
        _crashPrompt.value = null
    }

    fun dismissBanner() {
        _bannerNotice.value = null
    }

    fun clearFlow() {
        cancelActiveOperation()
        _flow.value = StreamFlowState.Idle
    }

    fun cancelActiveOperation() {
        val job = activeOperationJob
        activeOperationJob = null
        if (job != null && !job.isCompleted) {
            pendingCleanups.track(job)
        }
        activeCancelToken?.cancel()
        activeCancelToken = null
        job?.cancel()
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
        approvedNewFingerprint: String? = null,
    ) {
        startPairInternal(computer, forceManualPin, approvedNewFingerprint, connectAfterPair = null)
    }

    private fun startPairInternal(
        computer: StreamingComputer,
        forceManualPin: Boolean,
        approvedNewFingerprint: String?,
        connectAfterPair: ConnectAfterPairRequest?,
    ) {
        cancelActiveOperation()
        val b = binder
        if (b == null) {
            _flow.value = StreamFlowState.Failed(
                computer,
                StreamErrorMapper.forServiceStarting(strings),
                RetryAction.Pair(computer, forceManualPin),
            )
            return
        }

        val token = CallCancelToken()
        activeCancelToken = token
        val useAutoPair = !forceManualPin && computer.isDepth3dHost && computer.autoPairSupported && computer.host != null

        // #8: Show immediate UI feedback before awaiting any in-flight cancel cleanup jobs (up to 3s).
        _flow.value = if (useAutoPair) {
            StreamFlowState.AutoPairing(computer, app.getString(R.string.pair_stage_preparing))
        } else {
            StreamFlowState.Connecting(computer, null, app.getString(R.string.pair_stage_preparing))
        }

        activeOperationJob = scope.launch {
            try {
                // #8 / S3: Wait for ALL previously cancelled pairing jobs' NonCancellable /unpair cleanups.
                pendingCleanups.awaitAll(CANCEL_CLEANUP_JOIN_TIMEOUT_MS)
                if (token.isCancelled) return@launch

                b.stopPolling()
                withContext(Dispatchers.IO) { b.waitForPollingStopped() }
                if (token.isCancelled) return@launch

                val pin = PairingManager.generatePinString()
                val details = ensureDetailsForComputer(computer, b)
                if (token.isCancelled) return@launch
                if (details == null || details.activeAddress == null) {
                    _flow.value = StreamFlowState.Failed(
                        computer,
                        StreamErrorMapper.forStageFailed(strings, "control"),
                        RetryAction.Pair(computer, forceManualPin),
                    )
                    resumePollingIfForeground()
                    return@launch
                }

                val host = computer.host
                if (host != null && !forceManualPin && computer.isDepth3dHost && computer.autoPairSupported) {
                    val alreadyPairedCert = if (details.pairState == PairingManager.PairState.PAIRED) {
                        details.serverCert
                    } else {
                        null
                    }
                    _flow.value = StreamFlowState.AutoPairing(
                        computer = computer,
                        stageText = app.getString(R.string.stream_stage_check_host),
                    )
                    val outcome = HostAutoPairOrchestrator.execute(
                        hostApi = hostApi,
                        pinStore = pinStore,
                        address = host.address,
                        controlPort = host.controlPort,
                        hostId = host.id,
                        deviceName = CastReceiver.name(app),
                        deviceId = b.uniqueId,
                        pin = pin,
                        computerUuid = details.uuid.orEmpty(),
                        alreadyPairedGsCert = alreadyPairedCert,
                        approvedNewFingerprint = approvedNewFingerprint,
                        discoveredFingerprint = observedFingerprintsByEndpoint[host.endpointKey],
                        savedName = computer.name,
                        token = token,
                        onInfoVerified = { info, fp ->
                            hostInfosByEndpoint[host.endpointKey] = info
                            observedFingerprintsByEndpoint[host.endpointKey] = fp
                            infoRetry.markCompleted(host.endpointKey)
                            republishComputers()
                        },
                        onWaitingAllow = {
                            _flow.value = StreamFlowState.AutoPairing(
                                computer = computer,
                                stageText = app.getString(R.string.pair_auto_wait_allow),
                            )
                        },
                        onCompletingGameStream = {
                            _flow.value = StreamFlowState.AutoPairing(
                                computer = computer,
                                stageText = app.getString(R.string.pair_auto_completing),
                            )
                        },
                        onCompletingPairRecord = {
                            _flow.value = StreamFlowState.AutoPairing(
                                computer = computer,
                                stageText = app.getString(R.string.pair_auto_finalizing),
                            )
                        },
                        verifyExistingGameStreamPair = { cancelToken ->
                            verifyExistingGameStreamPair(host, details, b, cancelToken)
                        },
                        runGameStreamPair = { activePin, cancelToken ->
                            runFullGameStreamPair(host, details, b, activePin, cancelToken)
                        },
                    )
                    handleHostPairOutcome(computer, host, details, b, outcome, connectAfterPair, allowManualFallback = true)
                } else if (host != null) {
                    // #11: Host present without pair=auto (or user chose manual PIN) — verify /info id +
                    // approved cert and persist HostPairedTrust when pairing succeeds so cert prompts don't repeat.
                    val outcome = HostManualPairOrchestrator.execute(
                        hostApi = hostApi,
                        pinStore = pinStore,
                        address = host.address,
                        controlPort = host.controlPort,
                        hostId = host.id,
                        pin = pin,
                        computerUuid = details.uuid.orEmpty(),
                        approvedNewFingerprint = approvedNewFingerprint,
                        discoveredFingerprint = observedFingerprintsByEndpoint[host.endpointKey],
                        savedName = computer.name,
                        token = token,
                        onInfoVerified = { info, fp ->
                            hostInfosByEndpoint[host.endpointKey] = info
                            observedFingerprintsByEndpoint[host.endpointKey] = fp
                            infoRetry.markCompleted(host.endpointKey)
                            republishComputers()
                        },
                        onShowPin = {
                            _flow.value = StreamFlowState.PinPairing(computer, pin)
                        },
                        runGameStreamPair = { activePin, cancelToken ->
                            runGameStreamPair(host, details, b, activePin, cancelToken)
                        },
                    )
                    handleHostPairOutcome(computer, host, details, b, outcome, connectAfterPair, allowManualFallback = false)
                } else {
                    _flow.value = StreamFlowState.PinPairing(computer, pin)
                    when (val gsOutcome = runGameStreamPair(null, details, b, pin, token)) {
                        is GameStreamPairOutcome.Paired -> {
                            finalizePairedComputer(computer, details, b, gsOutcome.serverCert)
                        }
                        is GameStreamPairOutcome.FailedState -> {
                            _flow.value = StreamFlowState.Failed(
                                computer = computer,
                                error = StreamErrorMapper.forPairState(strings, gsOutcome.state, details.runningGameId != 0),
                                retryAction = RetryAction.Pair(computer, forceManualPin = true),
                            )
                            resumePollingIfForeground()
                        }
                        GameStreamPairOutcome.Cancelled -> resumePollingIfForeground()
                    }
                }
            } catch (_: CancellationException) {
                resumePollingIfForeground()
            } catch (e: Exception) {
                if (token.isCancelled) {
                    resumePollingIfForeground()
                    return@launch
                }
                LimeLog.warning("depth3d: pair error: ${e.javaClass.simpleName}")
                _flow.value = StreamFlowState.Failed(
                    computer,
                    StreamErrorMapper.forThrowable(strings, e, app.getString(R.string.stream_action_pair)),
                    RetryAction.Pair(computer, forceManualPin),
                )
                resumePollingIfForeground()
            }
        }
    }

    private fun handleHostPairOutcome(
        computer: StreamingComputer,
        host: DiscoveredHost,
        details: ComputerDetails,
        b: ComputerManagerService.ComputerManagerBinder,
        outcome: HostAutoPairOutcome,
        connectAfterPair: ConnectAfterPairRequest?,
        allowManualFallback: Boolean,
    ) {
        when (outcome) {
            is HostAutoPairOutcome.Paired -> {
                observedFingerprintsByEndpoint[host.endpointKey] = outcome.hostFingerprint
                hostInfosByEndpoint[host.endpointKey] = outcome.hostInfo
                finalizePairedComputer(computer, details, b, outcome.gsServerCert)
                if (connectAfterPair != null) {
                    val updated = _computers.value.firstOrNull { it.id == details.uuid }
                        ?: computer.copy(needsHostTrustSetup = false)
                    connect(
                        activity = connectAfterPair.activity,
                        computer = updated,
                        appOverride = connectAfterPair.appOverride,
                        onOpenAppGrid = connectAfterPair.onOpenAppGrid,
                    )
                }
            }
            is HostAutoPairOutcome.InfoIdentityMismatch -> {
                _flow.value = StreamFlowState.Failed(
                    computer = computer,
                    error = StreamErrorMapper.forHostIdentityMismatch(strings),
                    retryAction = RetryAction.Pair(computer, forceManualPin = !allowManualFallback),
                )
                resumePollingIfForeground()
            }
            HostAutoPairOutcome.UpgradeRequired -> {
                _flow.value = StreamFlowState.Failed(
                    computer = computer,
                    error = StreamErrorMapper.forHostUpgradeRequired(strings),
                    retryAction = null,
                )
                resumePollingIfForeground()
            }
            is HostAutoPairOutcome.CertificateChanged -> {
                _flow.value = StreamFlowState.CertConfirm(
                    computer = computer,
                    hostId = outcome.hostId,
                    expectedFingerprint = outcome.expected,
                    actualFingerprint = outcome.actual,
                    resumeAction = if (connectAfterPair != null) CertResumeAction.CONNECT else CertResumeAction.AUTO_PAIR,
                )
                resumePollingIfForeground()
            }
            is HostAutoPairOutcome.InfoFailed -> {
                _flow.value = StreamFlowState.Failed(
                    computer = computer,
                    error = StreamErrorMapper.forAutoPair(strings, AutoPairResult.Failed(outcome.detail)),
                    retryAction = RetryAction.Pair(computer, forceManualPin = !allowManualFallback),
                    fallbackPinComputer = if (allowManualFallback) computer else null,
                )
                resumePollingIfForeground()
            }
            is HostAutoPairOutcome.PairRejectedOrFailed -> {
                val pairResp = outcome.result
                val err = StreamErrorMapper.forAutoPair(strings, pairResp, streamReason = outcome.streamReason)
                _flow.value = StreamFlowState.Failed(
                    computer = computer,
                    error = err,
                    retryAction = if (err.retryable) {
                        RetryAction.Pair(computer, forceManualPin = false)
                    } else {
                        null
                    },
                    fallbackPinComputer = if (allowManualFallback && (pairResp is AutoPairResult.Failed || pairResp == AutoPairResult.TimedOut)) {
                        computer
                    } else {
                        null
                    },
                )
                resumePollingIfForeground()
            }
            is HostAutoPairOutcome.PairCompleteFailed -> {
                val err = StreamErrorMapper.forPairComplete(strings, outcome.result)
                _flow.value = StreamFlowState.Failed(
                    computer = computer,
                    error = err,
                    retryAction = if (err.retryable) RetryAction.Pair(computer, forceManualPin = false) else null,
                )
                resumePollingIfForeground()
            }
            is HostAutoPairOutcome.GameStreamFailed -> {
                _flow.value = StreamFlowState.Failed(
                    computer = computer,
                    error = StreamErrorMapper.forPairState(strings, outcome.state, details.runningGameId != 0),
                    retryAction = RetryAction.Pair(computer, forceManualPin = !allowManualFallback),
                    fallbackPinComputer = if (allowManualFallback) computer else null,
                )
                resumePollingIfForeground()
            }
            HostAutoPairOutcome.Cancelled -> resumePollingIfForeground()
        }
    }

    private suspend fun verifyExistingGameStreamPair(
        host: DiscoveredHost,
        details: ComputerDetails,
        b: ComputerManagerService.ComputerManagerBinder,
        token: CallCancelToken,
    ): Boolean = GameStreamPairRunner.verifyExistingPair(
        httpFactory = {
            // Always dial the Host's own IPv4 address + gsPort (host-protocol.md §控制接口 & §升级路径).
            NvHTTP(
                ComputerDetails.AddressTuple(host.address, host.gsPort),
                0,
                b.uniqueId,
                details.serverCert,
                PlatformBinding.getCryptoProvider(app),
            )
        },
        existingServerCert = details.serverCert,
        expectedComputerUuid = details.uuid,
        token = token,
    )

    private suspend fun runFullGameStreamPair(
        host: DiscoveredHost,
        details: ComputerDetails,
        b: ComputerManagerService.ComputerManagerBinder,
        pin: String,
        token: CallCancelToken,
    ): GameStreamPairOutcome = GameStreamPairRunner.runFullPair(
        httpFactory = {
            // Always dial the Host's own IPv4 address + gsPort so GameStream pairing shares the
            // exact same IPv4 as POST /depth3d/v1/pair and POST /depth3d/v1/pair/complete.
            NvHTTP(
                ComputerDetails.AddressTuple(host.address, host.gsPort),
                0,
                b.uniqueId,
                details.serverCert,
                PlatformBinding.getCryptoProvider(app),
            )
        },
        pin = pin,
        token = token,
    )

    private suspend fun runGameStreamPair(
        host: DiscoveredHost?,
        details: ComputerDetails,
        b: ComputerManagerService.ComputerManagerBinder,
        pin: String,
        token: CallCancelToken,
    ): GameStreamPairOutcome = GameStreamPairRunner.run(
        httpFactory = {
            // #2: When pairing with a depth3d Host, ALWAYS dial the Host's own address and gsPort
            // (never a cached address pointing to another machine) so verifyPinnedPairedServer
            // proves THAT exact endpoint holds details.serverCert!
            val targetAddress = if (host != null) {
                ComputerDetails.AddressTuple(host.address, host.gsPort)
            } else {
                ServerHelper.getCurrentAddressFromComputer(details)
            }
            val targetHttpsPort = if (host != null) 0 else details.httpsPort
            NvHTTP(
                targetAddress,
                targetHttpsPort,
                b.uniqueId,
                details.serverCert,
                PlatformBinding.getCryptoProvider(app),
            )
        },
        existingServerCert = details.serverCert,
        expectedComputerUuid = details.uuid,
        pin = pin,
        token = token,
    )

    private fun finalizePairedComputer(
        computer: StreamingComputer,
        details: ComputerDetails,
        b: ComputerManagerService.ComputerManagerBinder,
        cert: X509Certificate?,
    ) {
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
        computer.host?.endpointKey?.let { mediaRetry.clear(it) }
        republishComputers()
        maybeSyncHostMedia(details, forceRefresh = true)
        _flow.value = StreamFlowState.Idle
        _bannerNotice.value = app.getString(R.string.stream_notice_paired, computer.name)
        resumePollingIfForeground()
    }

    fun confirmChangedCertificate(
        activity: Activity,
        state: StreamFlowState.CertConfirm,
        onOpenAppGrid: (StreamingComputer) -> Unit,
    ) {
        // S2 + #11: Both AUTO_PAIR and CONNECT cert-change confirmations go through startPairInternal
        // (supporting both auto-pair and manual-PIN Hosts) so /info id + pairing succeed before saving.
        val connectAfter = if (state.resumeAction == CertResumeAction.CONNECT) {
            ConnectAfterPairRequest(activity, null, onOpenAppGrid)
        } else {
            null
        }
        startPairInternal(
            computer = state.computer,
            forceManualPin = false,
            approvedNewFingerprint = state.actualFingerprint,
            connectAfterPair = connectAfter,
        )
    }

    fun connect(
        activity: Activity,
        computer: StreamingComputer,
        appOverride: NvApp? = null,
        onOpenAppGrid: (StreamingComputer) -> Unit = {},
    ) {
        // #1: Seamless upgrade path — if this computer is already paired at GameStream layer and
        // has a discovered depth3d Host with pair=auto, but lacks HostPairedTrust, run Host /pair
        // -> verify GameStream cert -> persist trust -> sync /media -> then launch stream!
        if (computer.needsHostTrustSetup && computer.host != null && computer.autoPairSupported) {
            startPairInternal(
                computer = computer,
                forceManualPin = false,
                approvedNewFingerprint = null,
                connectAfterPair = ConnectAfterPairRequest(activity, appOverride, onOpenAppGrid),
            )
            return
        }

        cancelActiveOperation()
        val b = binder
        if (b == null) {
            _flow.value = StreamFlowState.Failed(
                computer,
                StreamErrorMapper.forServiceStarting(strings),
                RetryAction.Connect(computer, appOverride),
            )
            return
        }
        val details = findDetails(computer, b)
        if (details == null || details.state == ComputerDetails.State.OFFLINE || details.activeAddress == null) {
            _flow.value = StreamFlowState.Failed(
                computer,
                StreamErrorMapper.forComputerOffline(strings, computer.name),
                RetryAction.Connect(computer, appOverride),
            )
            return
        }

        val token = CallCancelToken()
        activeCancelToken = token
        _flow.value = StreamFlowState.Connecting(
            computer = computer,
            appName = appOverride?.appName,
            stageText = app.getString(R.string.stream_stage_check_host),
        )
        activeOperationJob = scope.launch {
            try {
                val host = computer.host
                if (host != null) {
                    val infoResult = withContext(Dispatchers.IO) {
                        hostApi.getInfo(
                            address = host.address,
                            port = host.controlPort,
                            hostId = host.id,
                            cancelToken = token,
                        )
                    }
                    if (token.isCancelled || infoResult == HostInfoResult.Cancelled) return@launch
                    when (infoResult) {
                        is HostInfoResult.Ok -> {
                            hostInfosByEndpoint[host.endpointKey] = infoResult.info
                            observedFingerprintsByEndpoint[host.endpointKey] = infoResult.fingerprint
                            infoRetry.markCompleted(host.endpointKey)
                            republishComputers()
                            if (!infoResult.info.stream.ready) {
                                _flow.value = StreamFlowState.Failed(
                                    computer,
                                    StreamErrorMapper.forHostNotReady(strings, infoResult.info.stream.reason),
                                    RetryAction.Connect(computer, appOverride),
                                )
                                return@launch
                            }
                        }
                        is HostInfoResult.IdentityMismatch -> {
                            _flow.value = StreamFlowState.Failed(
                                computer,
                                StreamErrorMapper.forHostIdentityMismatch(strings),
                                RetryAction.Connect(computer, appOverride),
                            )
                            return@launch
                        }
                        HostInfoResult.UpgradeRequired -> {
                            _flow.value = StreamFlowState.Failed(
                                computer,
                                StreamErrorMapper.forHostUpgradeRequired(strings),
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
                        HostInfoResult.Cancelled -> return@launch
                        is HostInfoResult.Failed -> {
                            LimeLog.warning("depth3d: host /info unreachable, continuing direct stream connect")
                        }
                    }
                }

                val targetApp: NvApp = if (appOverride != null) {
                    appOverride
                } else if (details.runningGameId != 0) {
                    NvApp(computer.name, details.runningGameId, false)
                } else {
                    _flow.value = StreamFlowState.Connecting(
                        computer,
                        null,
                        app.getString(R.string.stream_stage_fetch_desktop),
                    )
                    val apps = withContext(Dispatchers.IO) {
                        val http = NvHTTP(
                            ServerHelper.getCurrentAddressFromComputer(details),
                            details.httpsPort,
                            b.uniqueId,
                            details.serverCert,
                            PlatformBinding.getCryptoProvider(app),
                        )
                        val unregister = token.register { http.cancel() }
                        try {
                            DesktopLaunch.parse(http.appListRaw)
                        } finally {
                            unregister()
                        }
                    }
                    if (token.isCancelled) return@launch
                    val chosen = DesktopLaunch.choose(apps, details.runningGameId)
                        ?: apps.singleOrNull()
                    if (chosen == null) {
                        _flow.value = StreamFlowState.Idle
                        onOpenAppGrid(computer)
                        return@launch
                    }
                    chosen
                }

                if (token.isCancelled) return@launch
                _flow.value = StreamFlowState.Connecting(
                    computer = computer,
                    appName = targetApp.appName,
                    stageText = app.getString(R.string.stream_stage_enter_vr),
                )
                ServerHelper.doStartVrStream(activity, targetApp, details, b)
                _flow.value = StreamFlowState.Idle
            } catch (_: CancellationException) {
                // User cancelled
            } catch (e: Exception) {
                if (token.isCancelled) return@launch
                LimeLog.warning("depth3d: connect failed: ${e.javaClass.simpleName}")
                _flow.value = StreamFlowState.Failed(
                    computer,
                    StreamErrorMapper.forThrowable(strings, e, app.getString(R.string.stream_action_connect)),
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
                    val defaultDesktop = app.getString(R.string.stream_default_desktop_name)
                    val appObj = if (action.appId > 0) NvApp(action.appName ?: defaultDesktop, action.appId, false) else null
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
                StreamErrorMapper.forComputerUnreachable(strings, computer.name),
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
                _pcApps.value = PcAppsState.Failed(
                    computer,
                    StreamErrorMapper.forThrowable(strings, e, app.getString(R.string.stream_action_fetch_apps)),
                )
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
                        boxArtCache.put(key, bmp)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun quitRunningApp(computer: StreamingComputer, onDone: (Boolean) -> Unit = {}) {
        val b = binder
        val details = if (b != null) findDetails(computer, b) else null
        if (b == null || details == null) {
            onDone(false)
            return
        }
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
                    _bannerNotice.value = app.getString(R.string.stream_notice_quit_ok, computer.name)
                } else {
                    _bannerNotice.value = app.getString(R.string.stream_notice_quit_fail)
                }
                onDone(ok)
            } catch (e: Exception) {
                val err = StreamErrorMapper.forThrowable(strings, e, app.getString(R.string.stream_action_quit_app))
                _bannerNotice.value = app.getString(R.string.err_join_colon, err.title, err.detail)
                onDone(false)
            }
        }
    }

    fun wakeComputer(computer: StreamingComputer) {
        val b = binder ?: return
        val details = findDetails(computer, b)
        if (details == null || details.macAddress.isNullOrBlank()) {
            val err = StreamErrorMapper.forWakeNoMac(strings)
            _bannerNotice.value = app.getString(R.string.err_join_comma, err.title, err.detail)
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
                app.getString(R.string.stream_notice_wake_sent, computer.name)
            } else {
                val err = StreamErrorMapper.forWakeFailed(strings)
                app.getString(R.string.err_join_comma, err.title, err.detail)
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
        // Clear any HostPairedTrust bound to this computer UUID or attached Host
        computer.host?.let { h ->
            pinStore.clearFingerprint(h.id)
            observedFingerprintsByEndpoint.remove(h.endpointKey)
            infoRetry.clear(h.endpointKey)
            mediaRetry.clear(h.endpointKey)
        }
        for ((hostId, trust) in pinStore.getAllPairedTrusts()) {
            if (trust.computerUuid.equals(details.uuid, ignoreCase = true)) {
                pinStore.clearFingerprint(hostId)
            }
        }
        republishComputers()
        _bannerNotice.value = app.getString(R.string.stream_notice_removed, computer.name)
    }

    /** Adds a PC by IP/hostname (from phone QR form or optional headset keyboard). */
    suspend fun addComputerByAddress(rawInput: String): UserFacingError? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return StreamErrorMapper.forInvalidAddress(strings)
        val b = binder ?: return StreamErrorMapper.forServiceStarting(strings)
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
                    StreamErrorMapper.forAddComputerFailed(strings, trimmed)
                }
            } catch (e: Exception) {
                StreamErrorMapper.forThrowable(strings, e, app.getString(R.string.stream_action_add_pc))
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
            rawComputers.values.firstOrNull {
                val r = RawComputer.from(it)
                r.matchesHostExplicitId(host) || r.matchesOnlineEndpoint(host)
            }
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
        val trust = pinStore.getPairedTrust(host.id)
        val observedFp = observedFingerprintsByEndpoint[host.endpointKey]
        val fingerprintMatchesTrust = trust != null &&
            !observedFp.isNullOrBlank() &&
            observedFp.equals(trust.hostFingerprint, ignoreCase = true)
        synchronized(rawComputers) {
            if (trust != null && fingerprintMatchesTrust && trust.computerUuid.isNotBlank()) {
                rawComputers[trust.computerUuid]?.let { return it }
            }
            return rawComputers.values.firstOrNull {
                val r = RawComputer.from(it)
                if (trust != null && !fingerprintMatchesTrust &&
                    r.uuid.equals(trust.computerUuid, ignoreCase = true) &&
                    !r.matchesOnlineEndpoint(host)
                ) {
                    return@firstOrNull false
                }
                r.matchesHostExplicitId(host) || r.matchesOnlineEndpoint(host)
            }
        }
    }

    private fun startDiscoveryTimeout() {
        cancelDiscoveryTimeout()
        discoveryTimeoutJob = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (_computers.value.isEmpty()) {
                _discovering.value = false
            }
        }
    }

    private fun cancelDiscoveryTimeout() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
    }

    private fun scheduleHostInfoProbe(host: DiscoveredHost) {
        val key = host.endpointKey
        val now = System.currentTimeMillis()
        if (!infoRetry.tryAcquire(key, now)) return
        scope.launch(Dispatchers.IO) {
            val res = hostApi.getInfo(host.address, host.controlPort, host.id)
            withContext(Dispatchers.Main.immediate) {
                when (res) {
                    is HostInfoResult.Ok -> {
                        infoRetry.markCompleted(key)
                        hostInfosByEndpoint[key] = res.info
                        // B3 / #6 / #7: Store observed fingerprint in memory per endpoint (never persisted here).
                        observedFingerprintsByEndpoint[key] = res.fingerprint
                        republishComputers()
                        synchronized(rawComputers) { rawComputers.values.toList() }
                            .forEach { maybeSyncHostMedia(it) }
                    }
                    is HostInfoResult.IdentityMismatch,
                    is HostInfoResult.CertificateChanged,
                    HostInfoResult.UpgradeRequired -> {
                        infoRetry.markCompleted(key)
                    }
                    HostInfoResult.Cancelled -> Unit
                    is HostInfoResult.Failed -> {
                        val waitMs = infoRetry.markTransientFailure(key, System.currentTimeMillis())
                        if (foregroundActive && key in knownEndpointKeys) {
                            retryJobs["info:$key"]?.cancel()
                            retryJobs["info:$key"] = scope.launch {
                                delay(waitMs)
                                val latest = hostDiscovery.hosts.value.firstOrNull { it.endpointKey == key }
                                if (foregroundActive && latest != null) {
                                    scheduleHostInfoProbe(latest)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun maybeSyncHostMedia(details: ComputerDetails, forceRefresh: Boolean = false) {
        // B1 + #7: Strictly refuse to query /media unless pairing was completed with this exact
        // fingerprinted Host AND the discovered endpoint's observed fingerprint matches the trust!
        val serverCert = details.serverCert ?: return
        val detailsUuid = details.uuid?.takeIf { it.isNotBlank() } ?: return
        if (details.pairState != PairingManager.PairState.PAIRED) return

        val gsCertFp = HttpHostProtocolClient.sha256Hex(serverCert)
        val trusts = pinStore.getAllPairedTrusts()
        val matchedEntry = trusts.entries.firstOrNull { (_, t) ->
            t.computerUuid.equals(detailsUuid, ignoreCase = true) &&
                t.gsCertFingerprint.equals(gsCertFp, ignoreCase = true)
        } ?: return
        val (hostId, pairedTrust) = matchedEntry
        val savedPin = pinStore.getFingerprint(hostId) ?: return
        if (!pairedTrust.hostFingerprint.equals(savedPin, ignoreCase = true)) return

        // Find the specific discovered endpoint whose observed fingerprint matches savedPin (#7)
        val host = hostDiscovery.hosts.value.firstOrNull { candidate ->
            candidate.id.equals(hostId, ignoreCase = true) &&
                observedFingerprintsByEndpoint[candidate.endpointKey]?.equals(savedPin, ignoreCase = true) == true
        } ?: return

        val mediaPort = hostInfosByEndpoint[host.endpointKey]?.media?.port ?: host.mediaPort ?: return
        if (mediaPort <= 0) return
        val b = binder ?: return
        val key = host.endpointKey
        val shouldForce = forceRefresh || forceRefreshMediaEndpoints.remove(key)
        if (!shouldForce && WebDav.load(app) != null) {
            mediaRetry.markCompleted(key)
            return
        }
        val now = System.currentTimeMillis()
        if (!mediaRetry.tryAcquire(key, now)) return
        scope.launch(Dispatchers.IO) {
            val res = hostApi.getMediaCredentials(
                address = host.address,
                port = host.controlPort,
                hostId = host.id,
                deviceId = b.uniqueId,
                expectedComputerUuid = detailsUuid,
                expectedGsCertFingerprint = gsCertFp,
            )
            withContext(Dispatchers.Main.immediate) {
                when (res) {
                    is MediaCredsResult.Available -> {
                        mediaRetry.markCompleted(key)
                        val base = HttpHostProtocolClient.formatWebDavUrl(host.address, res.creds.port)
                        WebDav.save(app, WebDav.Server(base, res.creds.user, res.creds.pass))
                        LimeLog.info("depth3d: auto-configured WebDAV media source from host ${host.name}")
                        onAutoWebDavConfigured()
                    }
                    MediaCredsResult.NotConfigured,
                    MediaCredsResult.Unauthorized,
                    MediaCredsResult.UpgradeRequired,
                    is MediaCredsResult.CertificateChanged -> {
                        mediaRetry.markCompleted(key)
                    }
                    MediaCredsResult.Cancelled -> Unit
                    is MediaCredsResult.Failed -> {
                        val waitMs = mediaRetry.markTransientFailure(key, System.currentTimeMillis())
                        if (foregroundActive && key in knownEndpointKeys) {
                            retryJobs["media:$key"]?.cancel()
                            retryJobs["media:$key"] = scope.launch {
                                delay(waitMs)
                                if (foregroundActive) {
                                    maybeSyncHostMedia(details, forceRefresh = shouldForce)
                                }
                            }
                        }
                    }
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
            hostInfosByEndpoint = hostInfosByEndpoint,
            observedFingerprintsByEndpoint = observedFingerprintsByEndpoint,
            pairedTrusts = pinStore.getAllPairedTrusts(),
        )
    }

    private fun currentLanNetworkId(): Long? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return if (isLan) active.networkHandle else null
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
                    _hasWifi.value = true
                    val changed = networkTracker.onAvailable(network.networkHandle)
                    if (changed && foregroundActive) {
                        infoRetry.resetAll()
                        mediaRetry.resetAll()
                        retryJobs.values.forEach { it.cancel() }
                        retryJobs.clear()
                        if (_computers.value.isEmpty()) {
                            _discovering.value = true
                            startDiscoveryTimeout()
                        }
                        hostDiscovery.stop()
                        hostDiscovery.start()
                        binder?.startPolling(computerListener)
                    }
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    val remaining = currentLanNetworkId()
                    networkTracker.onLost(network.networkHandle, remaining)
                    _hasWifi.value = remaining != null
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
        const val DISCOVERY_TIMEOUT_MS = 8_000L
        const val MAX_BOX_ART_ENTRIES = 48
        private const val CANCEL_CLEANUP_JOIN_TIMEOUT_MS = 3_000L

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
