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
import java.util.concurrent.atomic.AtomicBoolean

/** Opens a non-plugin local or WebDAV sidecar `.xml` stream (implemented in audited host layer). */
fun interface SidecarXmlStreamOpener {
    fun open(videoUri: Uri?, xmlUrl: String, headers: Map<String, String>): InputStream?
}

/**
 * Dedicated off-render-thread coordinator owning [DanmakuWindowFetcher], [DanmakuEngine], and
 * [DanmakuRasterizer] for [com.limelight.local.LocalPlayerXR].
 *
 * - Runs all track allocation, collision checks, and 2-channel RG8 glyph rasterization on a single
 *   dedicated `HandlerThread("depth3d-danmaku")` at ~20 Hz (50 ms tick) while playing, while the
 *   native XR render thread smoothly advances `x(t)` at 72/90 Hz with zero allocations or waits.
 * - Supports both plugin `danmaku(ctx, { itemId, fromMs, toMs })` via [PluginPlayerSession] and
 *   local / WebDAV sidecar `.xml` files via [DanmakuXmlParser].
 */
class DanmakuSessionManager(
    context: Context,
    previewStrings: DanmakuPreviewStrings,
    private val sidecarOpener: SidecarXmlStreamOpener,
    private val onSupportChanged: (supported: Boolean) -> Unit,
    private val onLoadFailedChanged: (failed: Boolean) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("depth3d-danmaku").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)

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
                // Force re-rasterization of any active preview/items onto fresh GL atlas
                engine.onSeek(latestMediaMs)
                tickOnWorker(forcePush = true)
            }
        }
    }

    /** Configures a plugin item's danmaku source through [PluginPlayerSession]. */
    fun bindPluginSource(
        session: PluginPlayerSession?,
        supported: Boolean,
        startMediaMs: Long,
        mergeDuplicates: Boolean,
    ) {
        hasActiveSource = supported && session != null
        latestMediaMs = startMediaMs.coerceAtLeast(0L)
        onSupportChanged(hasActiveSource)
        onLoadFailedChanged(false)
        post {
            engine.resetForNewItem(startMediaMs)
            rendererRef?.clearDanmakuAtlas()
            if (supported && session != null) {
                val src = DanmakuDataSource { fromMs, toMs ->
                    session.fetchDanmaku(fromMs, toMs)
                }
                fetcher.setDataSource(src, mergeDuplicates)
                fetcher.ensureWindowsFor(startMediaMs)
            } else {
                fetcher.setDataSource(null, mergeDuplicates)
            }
            tickOnWorker(forcePush = true)
            scheduleNextTickIfNeeded()
        }
    }

    /** Probes and binds a local or WebDAV sidecar `.xml` file matching the video path/URL. */
    fun bindSidecarXmlSource(
        videoUri: Uri?,
        httpHeaders: Map<String, String>,
        startMediaMs: Long,
        mergeDuplicates: Boolean,
    ) {
        hasActiveSource = false
        latestMediaMs = startMediaMs.coerceAtLeast(0L)
        onSupportChanged(false)
        onLoadFailedChanged(false)
        post {
            engine.resetForNewItem(startMediaMs)
            rendererRef?.clearDanmakuAtlas()
            fetcher.setDataSource(null, mergeDuplicates)
            val xmlUrl = DanmakuXmlParser.sidecarXmlUrlFor(videoUri?.toString())
            if (xmlUrl == null) {
                tickOnWorker(forcePush = true)
                return@post
            }
            val parsed: List<DanmakuItem>? = loadSidecarXmlBlocking(videoUri, xmlUrl, httpHeaders)
            if (closed.get()) return@post
            if (parsed != null && parsed.isNotEmpty()) {
                hasActiveSource = true
                onSupportChanged(true)
                val src = DanmakuDataSource { fromMs, toMs ->
                    parsed.filter { it.timeMs in fromMs until toMs }
                }
                fetcher.setDataSource(src, mergeDuplicates)
                fetcher.ensureWindowsFor(startMediaMs)
                LimeLog.info("depth3d: loaded sidecar danmaku XML (" + parsed.size + " items)")
            }
            tickOnWorker(forcePush = true)
            scheduleNextTickIfNeeded()
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

    fun updatePlaybackPosition(mediaTimeMs: Long, playing: Boolean) {
        latestMediaMs = mediaTimeMs.coerceAtLeast(0L)
        post {
            engine.setPaused(!playing)
            if (hasActiveSource && engine.currentSettings.on) {
                fetcher.ensureWindowsFor(latestMediaMs)
            }
            tickOnWorker(forcePush = false)
            scheduleNextTickIfNeeded()
        }
    }

    fun setScrubbing(scrubbing: Boolean) {
        post {
            engine.setScrubbing(scrubbing)
            tickOnWorker(forcePush = true)
        }
    }

    fun onSeek(newMediaMs: Long) {
        latestMediaMs = newMediaMs.coerceAtLeast(0L)
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
        // Sync latest measured content depth without clobbering real screen/stereo geometry
        engine.updateContentPhi(latestContentPhiDeg)
        val res = engine.step(latestMediaMs)
        if (xr == null) return

        // Rasterize any newly spawned items on this worker thread and enqueue RG8 patches
        for (item in res.newlySpawned) {
            val slot = item.atlasSlot ?: continue
            if (!item.rasterized) {
                val buf = rasterizer.rasterizeToRgBuffer(item.displayText, slot)
                if (xr.uploadDanmakuAtlasPatch(slot.x, slot.y, slot.width, slot.height, buf)) {
                    item.rasterized = true
                }
            }
        }

        if (res.snapshotChanged || forcePush) {
            val count = engine.packInstances(packedInstances)
            val s = engine.currentSettings
            xr.setDanmakuSnapshot(
                s.on && hasActiveSource,
                engine.isPaused,
                engine.currentTimelineRealMs / 1000.0f,
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
        if (hasActiveSource && engine.currentSettings.on && (!engine.isPaused || engine.activeItems.isNotEmpty())) {
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
        fetcher.close()
        handler.removeCallbacksAndMessages(null)
        handler.post { rasterizer.release() }
        thread.quitSafely()
    }

    companion object {
        private const val TICK_INTERVAL_MS = 50L // 20 Hz worker tick; native GL interpolates at 72/90 Hz
    }
}
