package com.limelight.local.danmaku

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.limelight.LimeLog
import com.limelight.binding.video.XrRenderer
import com.limelight.binding.video.XrShared
import com.limelight.local.GlobalPaletteSettings
import com.limelight.local.PluginPlayerSession
import com.limelight.local.StereoSourceFormat
import com.limelight.local.plugin.DanmakuItem
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opens a non-plugin local or WebDAV sidecar `.xml` stream (implemented in audited host layer). */
fun interface SidecarXmlStreamOpener {
    fun open(videoUri: Uri?, xmlUrl: String, headers: Map<String, String>): InputStream?
}

/**
 * Dedicated off-render-thread coordinator owning [DanmakuWindowFetcher], [DanmakuEngine], and
 * [DanmakuRasterizer] for [com.limelight.local.LocalPlayerXR].
 *
 * Key guarantees:
 * - Runs track allocation, collision checks, and premultiplied RGBA8 rasterization on
 *   `HandlerThread("depth3d-danmaku")` at ~20 Hz while native GL interpolates smoothly at 72/90 Hz
 *   using monotonic `(anchorTimelineMs, System.nanoTime())` anchors (zero snapshot jitter).
 * - Sidecar `.xml` network/file I/O runs on a SEPARATE `depth3d-danmaku-io` thread with generation
 *   cancellation and per-60s-window bucketing so slow WebDAV I/O NEVER blocks seek or settings.
 * - Plugin fetches use `fetchDanmakuIfIdle` (`tryLock`, ~5 s timeout, max concurrency 1) and pause
 *   while `refreshInFlight` or `!ready` so danmaku NEVER blocks `playback` or stream refresh.
 * - Throttles per-tick spawns to remaining native upload queue space, retries rejected uploads on
 *   subsequent ticks, and tags every instance with `(slotIndex, generation)` so un-uploaded or
 *   reused slots NEVER render stale/corrupt text.
 */
class DanmakuSessionManager(
    context: Context,
    previewStrings: DanmakuPreviewStrings,
    private val sidecarOpener: SidecarXmlStreamOpener,
    private val onSupportChanged: (supported: Boolean) -> Unit,
    private val onLoadFailedChanged: (failed: Boolean) -> Unit,
) : DanmakuPlaybackSink, Closeable {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("depth3d-danmaku").apply { start() }
    private val handler = Handler(thread.looper)
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "depth3d-danmaku-io").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val sidecarLoadGen = AtomicLong(0L)

    private val rasterizer = DanmakuRasterizer()
    private val engine = DanmakuEngine(
        clock = { SystemClock.elapsedRealtime() },
        measurer = rasterizer,
        previewStrings = previewStrings,
    )
    private val fetcher = DanmakuWindowFetcher(
        onWindowReady = { windowIndex, items ->
            post {
                engine.ingestWindowItems(items, windowIndex)
                tickOnWorker(forcePush = false)
            }
        },
        onLoadStateChanged = { failed ->
            onLoadFailedChanged(failed)
        },
    )

    // Pre-allocated JNI instance transfer buffer (used exclusively on danmaku thread)
    private val packedInstances = FloatArray(XrShared.DANMAKU_MAX_ITEMS * XrShared.DANMAKU_INSTANCE_FLOATS)

    // Latest playback position & renderer published lock-free from main / render threads
    @Volatile private var rendererRef: XrRenderer? = null
    @Volatile private var latestMediaMs: Long = 0L
    @Volatile private var latestContentPhiDeg: Float = 0.0f
    @Volatile private var hasActiveSource: Boolean = false

    private var tickScheduled = false
    private val tickTask = Runnable {
        tickScheduled = false
        if (!closed.get()) {
            tickOnWorker(forcePush = false)
            scheduleNextTickIfNeeded()
        }
    }

    fun attachRenderer(renderer: XrRenderer?) {
        rendererRef = renderer
        post {
            if (renderer != null) {
                renderer.clearDanmakuAtlas()
                engine.atlas.clear()
                engine.onSeek(latestMediaMs)
                tickOnWorker(forcePush = true)
                scheduleNextTickIfNeeded()
            }
        }
    }

    /** Configures a plugin item's danmaku source through non-blocking [PluginPlayerSession.fetchDanmakuIfIdle]. */
    fun bindPluginSource(
        session: PluginPlayerSession?,
        supported: Boolean,
        startMediaMs: Long,
        mergeDuplicates: Boolean,
    ) {
        sidecarLoadGen.incrementAndGet()
        hasActiveSource = supported && session != null
        latestMediaMs = startMediaMs.coerceAtLeast(0L)
        onSupportChanged(hasActiveSource)
        onLoadFailedChanged(false)
        post {
            engine.resetForNewItem(startMediaMs)
            rendererRef?.clearDanmakuAtlas()
            if (supported && session != null) {
                val src = DanmakuDataSource { fromMs, toMs ->
                    val list = session.fetchDanmakuIfIdle(fromMs, toMs)
                    if (list == null) DanmakuFetchResult.Busy else DanmakuFetchResult.Loaded(list)
                }
                fetcher.setDataSource(
                    source = src,
                    mergeDuplicates = mergeDuplicates,
                    maxConcurrency = DanmakuWindowFetcher.MAX_CONCURRENT_PLUGIN_FETCHES,
                )
                fetcher.ensureWindowsFor(startMediaMs)
            } else {
                fetcher.setDataSource(null, mergeDuplicates)
            }
            tickOnWorker(forcePush = true)
            scheduleNextTickIfNeeded()
        }
    }

    /**
     * Probes and binds a local or WebDAV sidecar `.xml` file on the dedicated [ioExecutor]
     * (never blocking seek or settings updates on `depth3d-danmaku`), indexing parsed items by
     * 60-second window bucket so [DanmakuWindowFetcher] serves only active windows.
     */
    fun bindSidecarXmlSource(
        videoUri: Uri?,
        httpHeaders: Map<String, String>,
        startMediaMs: Long,
        mergeDuplicates: Boolean,
    ) {
        val loadGen = sidecarLoadGen.incrementAndGet()
        hasActiveSource = false
        latestMediaMs = startMediaMs.coerceAtLeast(0L)
        onSupportChanged(false)
        onLoadFailedChanged(false)
        post {
            engine.resetForNewItem(startMediaMs)
            rendererRef?.clearDanmakuAtlas()
            fetcher.setDataSource(null, mergeDuplicates)
            tickOnWorker(forcePush = true)
        }
        val xmlUrl = DanmakuXmlParser.sidecarXmlUrlFor(videoUri?.toString()) ?: return
        val headersCopy = HashMap(httpHeaders)
        ioExecutor.execute {
            if (closed.get() || sidecarLoadGen.get() != loadGen) return@execute
            val parsed = loadSidecarXmlBlocking(videoUri, xmlUrl, headersCopy)
            if (closed.get() || sidecarLoadGen.get() != loadGen) return@execute
            if (parsed == null || parsed.isEmpty()) return@execute

            // Bucket once by 60-second window so playback only slices active windows in O(1)
            val buckets = HashMap<Int, ArrayList<DanmakuItem>>()
            for (item in parsed) {
                val w = (item.timeMs / DanmakuWindowFetcher.WINDOW_MS).toInt()
                buckets.getOrPut(w) { ArrayList() }.add(item)
            }
            val totalCount = parsed.size
            post {
                if (closed.get() || sidecarLoadGen.get() != loadGen) return@post
                hasActiveSource = true
                onSupportChanged(true)
                val src = DanmakuDataSource { fromMs, _ ->
                    val w = (fromMs / DanmakuWindowFetcher.WINDOW_MS).toInt()
                    DanmakuFetchResult.Loaded(buckets[w] ?: emptyList())
                }
                fetcher.setDataSource(
                    source = src,
                    mergeDuplicates = mergeDuplicates,
                    maxConcurrency = DanmakuWindowFetcher.MAX_CONCURRENT_LOCAL_FETCHES,
                )
                fetcher.ensureWindowsFor(latestMediaMs)
                LimeLog.info("depth3d: loaded sidecar danmaku XML ($totalCount items in ${buckets.size} windows)")
                tickOnWorker(forcePush = true)
                scheduleNextTickIfNeeded()
            }
        }
    }

    fun updateSettings(global: GlobalPaletteSettings) {
        val dm = DanmakuSettings.fromGlobalPalette(global)
        post {
            fetcher.setMergeDuplicates(dm.mergeDuplicates)
            engine.updateSettings(dm)
            if (dm.on && hasActiveSource) {
                fetcher.ensureWindowsFor(latestMediaMs)
            }
            tickOnWorker(forcePush = true)
            scheduleNextTickIfNeeded()
        }
    }

    fun updateScreenAndStereo(
        fovDeg: Float,
        aspect: Float,
        distM: Float,
        stereoActive: Boolean,
        sourceFormat: StereoSourceFormat,
    ) {
        post {
            rasterizer.setScreenFovDeg(fovDeg)
            engine.updateScreenContext(
                fovDeg = fovDeg,
                aspect = aspect,
                distM = distM,
                stereoActive = stereoActive,
                sourceFormat = sourceFormat,
                contentPhiDeg = latestContentPhiDeg,
            )
            tickOnWorker(forcePush = false)
        }
    }

    /** Called lock-free from XR render thread with latest smoothed 98th-percentile content disparity. */
    fun offerContentPhiFromRenderThread(contentPhiDeg: Float) {
        latestContentPhiDeg = contentPhiDeg
    }

    override fun setFetchPaused(paused: Boolean) {
        post {
            fetcher.setFetchPaused(paused)
        }
    }

    override fun setPausedWithoutPosition() {
        post {
            engine.setPaused(true)
            tickOnWorker(forcePush = false)
        }
    }

    override fun updatePlaybackPosition(mediaTimeMs: Long, playing: Boolean, playbackSpeed: Float) {
        latestMediaMs = mediaTimeMs.coerceAtLeast(0L)
        post {
            engine.setPlaybackSpeed(playbackSpeed)
            engine.setPaused(!playing)
            if (hasActiveSource && engine.currentSettings.on) {
                fetcher.ensureWindowsFor(latestMediaMs)
            }
            tickOnWorker(forcePush = false)
            scheduleNextTickIfNeeded()
        }
    }

    override fun setScrubbing(scrubbing: Boolean) {
        post {
            engine.setScrubbing(scrubbing)
            tickOnWorker(forcePush = true)
        }
    }

    override fun onSeek(targetMs: Long) {
        latestMediaMs = targetMs.coerceAtLeast(0L)
        post {
            rendererRef?.clearDanmakuAtlas()
            engine.atlas.clear()
            engine.onSeek(latestMediaMs)
            if (hasActiveSource && engine.currentSettings.on) {
                fetcher.onSeek(latestMediaMs)
            }
            tickOnWorker(forcePush = true)
            scheduleNextTickIfNeeded()
        }
    }

    fun setPreviewRequested(requested: Boolean) {
        post {
            engine.setPreviewRequested(requested)
            tickOnWorker(forcePush = true)
            scheduleNextTickIfNeeded()
        }
    }

    fun retryFetch() {
        post {
            fetcher.retryCurrentWindow()
        }
    }

    private fun tickOnWorker(forcePush: Boolean) {
        val xr = rendererRef
        engine.updateContentPhi(latestContentPhiDeg)

        // Throttle per-tick spawns against remaining native upload ring headroom so bursts
        // never drop patches or exceed DANMAKU_UPLOAD_QUEUE_CAP.
        val pendingUploads = engine.activeItems.count { !it.rasterized }
        val maxNewSpawns = if (xr == null) {
            0
        } else {
            val freeQueue = xr.getDanmakuUploadQueueFree()
            (freeQueue - pendingUploads).coerceIn(0, MAX_SPAWNS_PER_TICK)
        }

        val res = engine.step(latestMediaMs, maxNewSpawns)
        if (xr == null) return

        // Rasterize and upload ALL un-uploaded active items (retrying any rejected on earlier ticks)
        var anyUploaded = false
        for (item in engine.activeItems) {
            if (item.rasterized) continue
            val slot = item.atlasSlot ?: continue
            val buf = rasterizer.rasterizeToRgbaBuffer(item.displayText, item.colorRgb, slot)
            if (xr.uploadDanmakuAtlasPatch(
                    slot.x, slot.y, slot.width, slot.height,
                    slot.slotIndex, slot.generation, buf,
                )
            ) {
                item.rasterized = true
                anyUploaded = true
            } else {
                // Native queue temporarily full: retry remaining items on next 50 ms tick
                break
            }
        }

        if (res.snapshotChanged || anyUploaded || forcePush) {
            val count = engine.packInstances(packedInstances)
            val s = engine.currentSettings
            val anchorMonoNs = System.nanoTime()
            xr.setDanmakuSnapshot(
                s.on && hasActiveSource,
                engine.isPaused,
                engine.currentTimelineRealMs,
                anchorMonoNs,
                res.clockReset,
                res.areaBotFrac,
                s.opacityPct / 100.0f,
                s.curvatureMode.nativeCode,
                s.customCurvaturePct.toFloat(),
                s.floatAuto,
                s.floatAutoOffsetSteps,
                s.floatFixedSteps,
                s.layerSpreadDeg(),
                res.animateDepthTransition,
                packedInstances,
                count,
            )
        }
    }

    private fun scheduleNextTickIfNeeded() {
        if (closed.get() || tickScheduled) return
        val hasPendingUploads = engine.activeItems.any { !it.rasterized }
        if (hasActiveSource && engine.currentSettings.on
            && (!engine.isPaused || engine.activeItems.isNotEmpty() || hasPendingUploads)
        ) {
            tickScheduled = true
            handler.postDelayed(tickTask, TICK_INTERVAL_MS)
        }
    }

    private fun post(block: () -> Unit) {
        if (!closed.get()) {
            handler.post(block)
        }
    }

    private fun loadSidecarXmlBlocking(
        videoUri: Uri?,
        xmlUrl: String,
        headers: Map<String, String>,
    ): List<DanmakuItem>? {
        return try {
            sidecarOpener.open(videoUri, xmlUrl, headers)?.use { stream ->
                DanmakuXmlParser.parse(stream)
            }
        } catch (e: Exception) {
            LimeLog.info("depth3d: sidecar danmaku XML not available (" + e.javaClass.simpleName + ")")
            null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sidecarLoadGen.incrementAndGet()
        fetcher.close()
        ioExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        handler.post { rasterizer.release() }
        thread.quitSafely()
    }

    companion object {
        private const val TICK_INTERVAL_MS = 50L // 20 Hz worker tick; native GL interpolates at 72/90 Hz
        private const val MAX_SPAWNS_PER_TICK = 12
    }
}
