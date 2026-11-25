package com.limelight.local

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.MediaSource as ExoMediaSource
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.PluginInternalException
import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.media.StereoHint
import com.limelight.local.plugin.DanmakuItem
import com.limelight.local.plugin.PlaybackSessionController
import com.limelight.local.plugin.PluginCapability
import com.limelight.local.plugin.PluginHostGuards
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginPlayback
import com.limelight.local.plugin.ProgressEvent
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.StringLookup
import com.limelight.local.wall.PluginHost
import com.limelight.local.wall.SourceErrorDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Parameters passed from the wall to [LocalPlayerXR] for a plugin item.
 * Per `docs/specs/plugin-host-integration.md` §1.1 & §2.2, ONLY opaque IDs and resume metadata
 * cross the Intent boundary — never raw stream URLs or auth headers.
 */
data class PluginLaunchArgs(
    val instanceId: String,
    val sourceId: String,
    val itemId: String,
    val title: String?,
    val startMs: Long,
    val durationMs: Long?,
    val parentId: String? = null,
)

/**
 * Helper building/parsing plugin launch intents and enforcing the non-plugin cast/external-open
 * boundary guard (`docs/specs/plugin-host-integration.md` §1.4 & §2.4).
 */
object PluginPlayerLaunch {
    @JvmStatic
    @JvmOverloads
    fun createIntent(
        context: Context,
        instanceId: String,
        sourceId: String,
        itemId: String,
        title: String?,
        startMs: Long = 0L,
        durationMs: Long? = null,
        parentId: String? = null,
    ): Intent {
        val intent = Intent(context, LocalPlayerXR::class.java)
            .putExtra(LocalPlayerXR.EXTRA_PLUGIN_INSTANCE_ID, instanceId)
            .putExtra(LocalPlayerXR.EXTRA_SOURCE_ID, sourceId)
            .putExtra(LocalPlayerXR.EXTRA_ITEM_ID, itemId)
            .putExtra(LocalPlayerXR.EXTRA_START_MS, startMs.coerceAtLeast(0L))
        if (!title.isNullOrBlank()) {
            intent.putExtra(LocalPlayerXR.EXTRA_TITLE, title)
        }
        if (durationMs != null && durationMs > 0L) {
            intent.putExtra(LocalPlayerXR.EXTRA_DURATION_MS, durationMs)
        }
        if (!parentId.isNullOrBlank()) {
            intent.putExtra(LocalPlayerXR.EXTRA_PARENT_ID, parentId)
        }
        return intent
    }

    @JvmStatic
    fun parseIntent(intent: Intent?): PluginLaunchArgs? {
        if (intent == null) return null
        val instanceId = intent.getStringExtra(LocalPlayerXR.EXTRA_PLUGIN_INSTANCE_ID)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val itemId = intent.getStringExtra(LocalPlayerXR.EXTRA_ITEM_ID)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sourceId = intent.getStringExtra(LocalPlayerXR.EXTRA_SOURCE_ID)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: PluginMediaSource.sourceIdFor(instanceId)
        val startMs = intent.getLongExtra(LocalPlayerXR.EXTRA_START_MS, 0L).coerceAtLeast(0L)
        val rawDur = intent.getLongExtra(LocalPlayerXR.EXTRA_DURATION_MS, 0L)
        return PluginLaunchArgs(
            instanceId = instanceId,
            sourceId = sourceId,
            itemId = itemId,
            title = intent.getStringExtra(LocalPlayerXR.EXTRA_TITLE),
            startMs = startMs,
            durationMs = rawDur.takeIf { it > 0L },
            parentId = intent.getStringExtra(LocalPlayerXR.EXTRA_PARENT_ID)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Computes the resume position when the user clicks Retry after a mid-playback failure:
     * prefers the player's current position if > 0, then the last observed playback position
     * before failure, and finally falls back to the initial start position.
     */
    @JvmStatic
    fun computeRetryPositionMs(
        currentPlayerPosMs: Long,
        lastKnownPositionMs: Long,
        fallbackStartMs: Long,
    ): Long = when {
        currentPlayerPosMs > 0L -> currentPlayerPosMs
        lastKnownPositionMs > 0L -> lastKnownPositionMs
        else -> fallbackStartMs.coerceAtLeast(0L)
    }

    @JvmStatic
    fun withResumePosition(args: PluginLaunchArgs, resumePositionMs: Long): PluginLaunchArgs =
        args.copy(startMs = resumePositionMs.coerceAtLeast(0L))
}

/**
 * Resolved Media3 source and non-sensitive playback metadata ready for [LocalPlayerXR].
 */
@OptIn(UnstableApi::class)
data class LoadedPluginMedia(
    val mediaSource: ExoMediaSource,
    val resumePositionMs: Long,
    val durationMs: Long?,
    val isLive: Boolean,
    val subtitleCount: Int,
    val stereoHint: StereoHint,
    val selectedQualityId: String?,
    val sessionId: String?,
    val sourceName: String,
)

/** Localized Chinese error text shown inside [LocalPlayerXR] / [TransportBar]. */
data class PlayerErrorUi(
    val reason: String,
    val canRetry: Boolean,
)

/**
 * Manages one plugin playback lifecycle inside [LocalPlayerXR]:
 * - Loads playback exclusively via [PluginPlayback.createSessionController] + `createMediaSource`
 *   (never exposing or reading raw stream URLs/headers).
 * - Automatically refreshes expired (`expiresAt`) or HTTP 401/403/410 sessions and resumes from
 *   the current playback position.
 * - Reports `START` / periodic `PROGRESS` (every 10s) / `PAUSE` / `FINISH` / `STOP` through
 *   [com.limelight.local.plugin.PluginProgressCapability] when declared by the plugin manifest.
 * - Maps all plugin & ExoPlayer errors to Simplified Chinese copy without raw error codes.
 */
@OptIn(UnstableApi::class)
class PluginPlayerSession(
    private val openPluginSource: suspend (instanceId: String) -> PluginMediaSource,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val progressIntervalMs: Long = PROGRESS_REPORT_INTERVAL_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val checkNeedsPermissionReconfirm: suspend (instanceId: String) -> Boolean = { false },
) : Closeable {

    interface LoadCallback {
        fun onLoaded(media: LoadedPluginMedia)
        fun onFailed(error: Throwable)
    }

    interface RefreshCallback {
        fun onRefreshed(media: LoadedPluginMedia)
        fun onNoRefreshNeeded()
        fun onFailed(error: Throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val closed = AtomicBoolean(false)
    private val consecutiveRefreshes = AtomicInteger(0)
    private val proactiveRefreshExhausted = AtomicBoolean(false)
    private val startReported = AtomicBoolean(false)
    private val finishReported = AtomicBoolean(false)
    private val stopReported = AtomicBoolean(false)
    private val lastPeriodicReportMs = AtomicLong(0L)

    @Volatile
    private var activeSource: PluginMediaSource? = null

    @Volatile
    private var controller: PlaybackSessionController? = null

    @Volatile
    private var activeItemId: String? = null

    @Volatile
    private var activeSessionId: String? = null

    @Volatile
    private var fallbackDurationMs: Long? = null

    @Volatile
    private var currentExpiresAtMs: Long? = null

    @Volatile
    private var permissionReconfirmNeeded: Boolean = false

    val sourceName: String
        get() = activeSource?.source?.name.orEmpty()

    val needsPermissionReconfirm: Boolean
        get() = permissionReconfirmNeeded

    val supportsProgress: Boolean
        get() = activeSource?.capabilities?.contains(PluginCapability.PROGRESS) == true

    val supportsDanmaku: Boolean
        get() = activeSource?.capabilities?.contains(PluginCapability.DANMAKU) == true

    /**
     * Fetches one time window `[fromMs, toMs)` of danmaku strictly through the active plugin's
     * [PluginMediaSource.danmaku] bridge (plugin-protocol.md §4 / G4).
     */
    suspend fun fetchDanmaku(fromMs: Long, toMs: Long): List<DanmakuItem> {
        val src = activeSource ?: return emptyList()
        val id = activeItemId ?: return emptyList()
        if (!src.capabilities.contains(PluginCapability.DANMAKU)) return emptyList()
        return src.danmaku(itemId = id, fromMs = fromMs, toMs = toMs)
    }

    suspend fun load(
        instanceId: String,
        itemId: String,
        resumePositionMs: Long,
        fallbackDurationMs: Long?,
    ): LoadedPluginMedia {
        this.permissionReconfirmNeeded = runCatching { checkNeedsPermissionReconfirm(instanceId) }.getOrDefault(false)
        val pluginSource = openPluginSource(instanceId)
        val ctrl = PluginPlayback.createSessionController(pluginSource, clockMs = clockMs)
        val session = ctrl.load(
            itemId = itemId,
            resumePositionMs = resumePositionMs,
            fallbackDurationMs = fallbackDurationMs,
        )
        val exoMediaSource = ctrl.createMediaSource(session)

        this.activeSource = pluginSource
        this.controller = ctrl
        this.activeItemId = itemId
        this.activeSessionId = session.info.sessionId
        this.fallbackDurationMs = session.info.durationMs ?: fallbackDurationMs
        this.currentExpiresAtMs = session.info.expiresAtMs
        this.consecutiveRefreshes.set(0)
        // If initial URL is valid right now but has <=60s TTL, avoid proactive refresh thrashing;
        // real HTTP 401/403/410 playback errors will still trigger error-based refresh (#6).
        val initExp = session.info.expiresAtMs
        val now = clockMs()
        val isShortTtlValidNow = initExp != null && initExp > now && (initExp - now) <= PROACTIVE_REFRESH_LEAD_MS
        this.proactiveRefreshExhausted.set(isShortTtlValidNow)
        this.startReported.set(false)
        this.finishReported.set(false)
        this.stopReported.set(false)
        this.lastPeriodicReportMs.set(0L)

        return LoadedPluginMedia(
            mediaSource = exoMediaSource,
            resumePositionMs = session.resumePositionMs,
            durationMs = session.info.durationMs ?: fallbackDurationMs,
            isLive = session.info.isLive,
            subtitleCount = session.info.subtitles.size,
            stereoHint = session.info.stereoHint,
            selectedQualityId = session.selectedQualityId,
            sessionId = session.info.sessionId,
            sourceName = pluginSource.source.name,
        )
    }

    fun loadAsync(
        instanceId: String,
        itemId: String,
        resumePositionMs: Long,
        fallbackDurationMs: Long?,
        callback: LoadCallback,
    ) {
        scope.launch {
            try {
                val loaded = load(instanceId, itemId, resumePositionMs, fallbackDurationMs)
                if (!closed.get()) {
                    withContext(callbackDispatcher) { callback.onLoaded(loaded) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!closed.get()) {
                    withContext(callbackDispatcher) { callback.onFailed(e) }
                }
            }
        }
    }

    private fun isLiterallyExpired(nowMs: Long = clockMs()): Boolean {
        val exp = currentExpiresAtMs ?: return false
        return exp <= nowMs
    }

    fun markPlaybackReady() {
        val now = clockMs()
        // Reset error/refresh counter whenever the stream is valid right now (including short-TTL
        // streams that disabled proactive polling so error-based 401/403/410 recovery keeps working).
        if (!isLiterallyExpired(now)) {
            consecutiveRefreshes.set(0)
            val exp = currentExpiresAtMs
            val isShortTtl = exp != null && (exp - now) <= PROACTIVE_REFRESH_LEAD_MS
            if (!isShortTtl && controller?.isExpired(now) != true) {
                proactiveRefreshExhausted.set(false)
            }
        }
    }

    fun shouldProactiveRefresh(): Boolean {
        if (proactiveRefreshExhausted.get()) return false
        return controller?.isExpired(clockMs()) == true
    }

    fun canAttemptAutoRefresh(httpStatusCode: Int?): Boolean {
        val ctrl = controller ?: return false
        val isAuthOrGone = httpStatusCode == 401 || httpStatusCode == 403 || httpStatusCode == 410
        if (!isAuthOrGone && !ctrl.isExpired(clockMs())) return false
        return consecutiveRefreshes.get() < MAX_CONSECUTIVE_ERROR_REFRESHES
    }

    suspend fun refreshIfExpiredOrFailed(
        currentPositionMs: Long,
        httpStatusCode: Int? = null,
    ): LoadedPluginMedia? {
        val ctrl = controller ?: return null
        val src = activeSource ?: return null
        val isHttpTriggered = httpStatusCode == 401 || httpStatusCode == 403 || httpStatusCode == 410
        if (!isHttpTriggered && !ctrl.isExpired(clockMs())) {
            return null
        }
        val attempt = consecutiveRefreshes.incrementAndGet()
        if (attempt > MAX_CONSECUTIVE_ERROR_REFRESHES) {
            if (!isHttpTriggered) {
                proactiveRefreshExhausted.set(true)
                throw SourceUnavailableException("播放地址已过期且多次刷新仍失败，请稍后重试。")
            }
            return null
        }
        val refreshed = ctrl.refreshIfExpiredOrFailed(
            currentPositionMs = currentPositionMs.coerceAtLeast(0L),
            httpStatusCode = httpStatusCode,
            fallbackDurationMs = fallbackDurationMs,
        ) ?: return null

        val now = clockMs()
        val exp = refreshed.info.expiresAtMs
        currentExpiresAtMs = exp
        // Only abort if the refreshed URL is LITERALLY already expired (expiresAt <= now).
        // If it is valid right now but within the <=60s proactive lead window, stop proactive
        // polling and continue playing normally; real HTTP errors still trigger error refresh (#6).
        if (exp != null && exp <= now) {
            proactiveRefreshExhausted.set(true)
            throw SourceUnavailableException("播放地址刷新后仍已过期，请稍后重试。")
        }
        if (exp != null && (exp - now) <= PROACTIVE_REFRESH_LEAD_MS) {
            proactiveRefreshExhausted.set(true)
        }

        val exoMediaSource = ctrl.createMediaSource(refreshed)
        activeSessionId = refreshed.info.sessionId
        fallbackDurationMs = refreshed.info.durationMs ?: fallbackDurationMs
        return LoadedPluginMedia(
            mediaSource = exoMediaSource,
            resumePositionMs = refreshed.resumePositionMs,
            durationMs = refreshed.info.durationMs ?: fallbackDurationMs,
            isLive = refreshed.info.isLive,
            subtitleCount = refreshed.info.subtitles.size,
            stereoHint = refreshed.info.stereoHint,
            selectedQualityId = refreshed.selectedQualityId,
            sessionId = refreshed.info.sessionId,
            sourceName = src.source.name,
        )
    }

    fun refreshAsync(
        currentPositionMs: Long,
        httpStatusCode: Int?,
        callback: RefreshCallback,
    ) {
        scope.launch {
            try {
                val refreshed = refreshIfExpiredOrFailed(currentPositionMs, httpStatusCode)
                if (closed.get()) return@launch
                withContext(callbackDispatcher) {
                    if (refreshed != null) callback.onRefreshed(refreshed)
                    else callback.onNoRefreshNeeded()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!closed.get()) {
                    withContext(callbackDispatcher) { callback.onFailed(e) }
                }
            }
        }
    }

    suspend fun reportStart(positionMs: Long, durationMs: Long): Boolean {
        if (!startReported.compareAndSet(false, true)) return false
        lastPeriodicReportMs.set(clockMs())
        return sendProgress(ProgressEvent.START, positionMs, durationMs)
    }

    suspend fun reportPeriodicIfDue(positionMs: Long, durationMs: Long): Boolean {
        if (!startReported.get() || finishReported.get() || stopReported.get()) return false
        val now = clockMs()
        val prev = lastPeriodicReportMs.get()
        if (now - prev < progressIntervalMs) return false
        if (!lastPeriodicReportMs.compareAndSet(prev, now)) return false
        return sendProgress(ProgressEvent.PROGRESS, positionMs, durationMs)
    }

    suspend fun reportPause(positionMs: Long, durationMs: Long): Boolean {
        if (!startReported.get() || finishReported.get() || stopReported.get()) return false
        lastPeriodicReportMs.set(clockMs())
        return sendProgress(ProgressEvent.PAUSE, positionMs, durationMs)
    }

    suspend fun reportFinish(positionMs: Long, durationMs: Long): Boolean {
        if (!finishReported.compareAndSet(false, true)) return false
        stopReported.set(true)
        val effDur = resolveDuration(durationMs)
        return sendProgress(ProgressEvent.FINISH, if (effDur > 0L) effDur else positionMs, effDur)
    }

    suspend fun reportStop(positionMs: Long, durationMs: Long): Boolean {
        if (!startReported.get() || finishReported.get()) return false
        if (!stopReported.compareAndSet(false, true)) return false
        return sendProgress(ProgressEvent.STOP, positionMs, durationMs)
    }

    fun reportStartAsync(positionMs: Long, durationMs: Long) {
        scope.launch { reportStart(positionMs, durationMs) }
    }

    fun reportPeriodicIfDueAsync(positionMs: Long, durationMs: Long) {
        if (!startReported.get() || finishReported.get() || stopReported.get()) return
        val now = clockMs()
        if (now - lastPeriodicReportMs.get() < progressIntervalMs) return
        scope.launch { reportPeriodicIfDue(positionMs, durationMs) }
    }

    fun reportPauseAsync(positionMs: Long, durationMs: Long) {
        scope.launch { reportPause(positionMs, durationMs) }
    }

    fun reportFinishAsync(positionMs: Long, durationMs: Long) {
        scope.launch { reportFinish(positionMs, durationMs) }
    }

    /**
     * Dispatches the final `stop` progress report on a process-scoped IO coroutine with a 5s
     * timeout so exiting [LocalPlayerXR] neither blocks Activity teardown nor drops the report.
     */
    fun reportStopFireAndForget(positionMs: Long, durationMs: Long) {
        val src = activeSource ?: return
        val id = activeItemId ?: return
        if (!src.capabilities.contains(PluginCapability.PROGRESS)) return
        if (!startReported.get() || finishReported.get()) return
        if (!stopReported.compareAndSet(false, true)) return
        val sessionId = activeSessionId
        val pos = positionMs.coerceAtLeast(0L)
        val dur = resolveDuration(durationMs)
        finalReportScope.launch {
            withTimeoutOrNull(FINAL_STOP_TIMEOUT_MS) {
                try {
                    src.reportProgress(
                        itemId = id,
                        positionMs = pos,
                        durationMs = dur,
                        event = ProgressEvent.STOP,
                        session = sessionId,
                    )
                } catch (e: Exception) {
                    LimeLog.warning("depth3d: plugin progress stop failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun resolveDuration(playerDurationMs: Long): Long =
        when {
            playerDurationMs > 0L -> playerDurationMs
            (fallbackDurationMs ?: 0L) > 0L -> fallbackDurationMs!!
            else -> 0L
        }

    private suspend fun sendProgress(
        event: ProgressEvent,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        val src = activeSource ?: return false
        val id = activeItemId ?: return false
        if (!src.capabilities.contains(PluginCapability.PROGRESS)) return false
        return try {
            src.reportProgress(
                itemId = id,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = resolveDuration(durationMs),
                event = event,
                session = activeSessionId,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LimeLog.warning("depth3d: plugin progress ${event.name.lowercase()} failed: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
        }
    }

    companion object {
        const val PROGRESS_REPORT_INTERVAL_MS = 10_000L
        const val MAX_CONSECUTIVE_ERROR_REFRESHES = 2
        const val PROACTIVE_REFRESH_LEAD_MS = 60_000L
        private const val FINAL_STOP_TIMEOUT_MS = 5_000L

        private val finalReportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @JvmStatic
        fun create(context: Context): PluginPlayerSession {
            val host = PluginHost.get(context.applicationContext)
            return PluginPlayerSession(
                openPluginSource = { id -> host.openSource(id) },
                checkNeedsPermissionReconfirm = { instanceId ->
                    val inst = host.store.getInstance(instanceId)
                    val pkg = inst?.let { host.store.getPackage(it.pluginId) }
                    pkg?.needsPermissionReconfirm == true
                },
            )
        }

        @JvmStatic
        fun extractHttpStatusCode(error: Throwable?): Int? {
            var cur = error
            var depth = 0
            while (cur != null && depth < 12) {
                if (cur is HttpDataSource.InvalidResponseCodeException) {
                    return cur.responseCode
                }
                cur = cur.cause
                depth++
            }
            return null
        }

        @JvmStatic
        @JvmOverloads
        fun formatPlayerError(
            context: Context,
            error: Throwable,
            sourceKind: SourceKind,
            sourceName: String,
            needsPermissionReconfirm: Boolean = false,
        ): PlayerErrorUi = formatPlayerError(
            error = error,
            sourceKind = sourceKind,
            sourceName = sourceName,
            strings = AndroidStringLookup(context),
            needsPermissionReconfirm = needsPermissionReconfirm,
        )

        fun formatPlayerError(
            error: Throwable,
            sourceKind: SourceKind,
            sourceName: String,
            strings: StringLookup,
            needsPermissionReconfirm: Boolean = false,
        ): PlayerErrorUi {
            val domain = findDomainException(error)
            if (domain != null) {
                val pres = SourceErrorDispatcher.dispatch(
                    error = domain,
                    sourceKind = sourceKind,
                    sourceName = sourceName,
                    strings = strings,
                    inPlaybackContext = true,
                    needsPermissionReconfirm = needsPermissionReconfirm,
                )
                return PlayerErrorUi(
                    reason = pres.message,
                    canRetry = domain !is SourceUnsupportedException &&
                        !(domain is SourceNeedsSetupException && needsPermissionReconfirm),
                )
            }

            val httpCode = extractHttpStatusCode(error)
            if (httpCode != null) {
                val msg = when {
                    httpCode == 401 || httpCode == 403 -> strings.get(R.string.plugin_player_error_auth_expired)
                    httpCode == 404 || httpCode == 410 -> strings.get(R.string.plugin_player_error_not_found)
                    httpCode == 429 -> strings.get(R.string.plugin_error_rate_limited)
                    httpCode in 500..599 -> strings.get(R.string.plugin_player_error_server)
                    else -> strings.get(R.string.plugin_player_error_network)
                }
                return PlayerErrorUi(reason = msg, canRetry = true)
            }

            if (error is PlaybackException) {
                val code = error.errorCode
                val msg = when {
                    code in 2000..2999 -> strings.get(R.string.plugin_player_error_network)
                    code in 3000..4999 -> strings.get(R.string.plugin_player_error_format)
                    code in 6000..6999 -> strings.get(R.string.plugin_error_unsupported)
                    else -> strings.get(R.string.plugin_player_error_generic)
                }
                return PlayerErrorUi(reason = msg, canRetry = code !in 6000..6999)
            }

            if (hasCause<IOException>(error)) {
                return PlayerErrorUi(
                    reason = strings.get(R.string.plugin_player_error_network),
                    canRetry = true,
                )
            }

            return PlayerErrorUi(
                reason = strings.get(R.string.plugin_player_error_generic),
                canRetry = true,
            )
        }

        private fun findDomainException(error: Throwable): Throwable? {
            var cur: Throwable? = error
            var depth = 0
            while (cur != null && depth < 12) {
                when (cur) {
                    is SourceNeedsAuthException,
                    is SourceAuthFailedException,
                    is SourceNeedsSetupException,
                    is SourceUnavailableException,
                    is ItemNotFoundException,
                    is SourceForbiddenException,
                    is SourceRateLimitedException,
                    is SourceUnsupportedException,
                    is PluginInternalException,
                    -> return cur
                }
                cur = cur.cause
                depth++
            }
            return null
        }

        private inline fun <reified T : Throwable> hasCause(error: Throwable): Boolean {
            var cur: Throwable? = error
            var depth = 0
            while (cur != null && depth < 12) {
                if (cur is T) return true
                cur = cur.cause
                depth++
            }
            return false
        }
    }
}
