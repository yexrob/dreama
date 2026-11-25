package com.limelight.local.danmaku

import com.limelight.binding.video.XrShared
import com.limelight.local.DanmakuArea
import com.limelight.local.StereoSourceFormat
import com.limelight.local.plugin.DanmakuMode
import kotlin.math.max
import kotlin.math.min

/** Localized preview text shown on the 3 depth bands when opening Danmaku tab while paused/empty (§6.3). */
data class DanmakuPreviewStrings(
    val farLabel: String = "Preview · Far",
    val midLabel: String = "Preview · Mid",
    val nearLabel: String = "Preview · Near",
)

/** Summary returned by each [DanmakuEngine.step] call telling the worker what needs rasterization/upload. */
data class DanmakuStepResult(
    val snapshotChanged: Boolean,
    val newlySpawned: List<ActiveDanmakuItem>,
    val animateDepthTransition: Boolean,
    val activeCount: Int,
    val areaBotFrac: Float,
)

/**
 * Pure-Kotlin, 100% deterministic danmaku track allocator, collision avoider, timeline engine,
 * and stereoscopic layered-float controller (ux-spec.md §6.10, §6.3, §6.12).
 *
 * Guarantees:
 * - Same input items, settings, screen geometry, and virtual [clock] always produce identical
 *   tracks, depth bands, spawn times, and positions.
 * - Scroll danmaku share one angular velocity (Slow 10s / Normal 8s / Fast 6s, capped at 12°/s)
 *   so same-track collisions are mathematically impossible once tail clearance >= gapChars * fontDeg.
 * - Top/Bottom stay 4 s in real time on the NEAR band with 150 ms fade; Bottom stays above the
 *   6.5° subtitle zone with max 3 rows.
 * - Tracks never rise above +18° above eye level; scroll tracks never enter the subtitle zone.
 * - Overflow items wait up to 1,000 ms real time, then drop cleanly (no backfill).
 * - Pause freezes real timeline; playback speed spawns by mediaTimeMs while scrolling in real time;
 *   scrubbing hides and seek resets cleanly with zero backfill.
 */
class DanmakuEngine(
    private val clock: DanmakuClock,
    private val measurer: DanmakuTextMeasurer = HeuristicDanmakuMeasurer,
    val atlas: DanmakuAtlasAllocator = DanmakuAtlasAllocator(),
    private val previewStrings: DanmakuPreviewStrings = DanmakuPreviewStrings(),
) {
    private data class WaitingItem(
        val raw: DanmakuRawItem,
        val enqueuedRealMs: Long,
    )

    private var settings: DanmakuSettings = DanmakuSettings()
    private var screenFovDeg: Float = 50.0f
    private var screenAspect: Float = 16.0f / 9.0f
    private var screenDistM: Float = 3.0f
    private var stereoActive: Boolean = false
    private var sourceFormat: StereoSourceFormat = StereoSourceFormat.AUTO
    private var contentPhiDeg: Float = 0.0f

    private var paused: Boolean = false
    private var scrubbing: Boolean = false
    private var previewRequested: Boolean = false
    private var previewActive: Boolean = false

    // Real-time timeline that freezes while paused so paused items stay frozen at exact X
    private var lastWallRealMs: Long = clock.nowRealMs()
    private var timelineRealMs: Long = 0L

    // Last processed video media timestamp (ms)
    private var lastMediaMs: Long = 0L
    private var mediaCursorReady: Boolean = false

    // Pending timeline items from fetched windows, sorted by (timeMs, id)
    private val scheduled = ArrayList<DanmakuRawItem>()
    private val scheduledIds = HashSet<Long>()
    private var scheduledCursor: Int = 0

    // Bounded 1 s wait queue when tracks, density cap, or atlas are temporarily full
    private val waitQueue = ArrayDeque<WaitingItem>()

    // Currently live items on screen (or 3 stationary previews)
    private val active = ArrayList<ActiveDanmakuItem>()
    private var nextRuntimeId: Long = 1L

    private var pendingDepthAnim: Boolean = false
    private var dirtySnapshot: Boolean = true
    private var droppedCount: Int = 0

    val activeItems: List<ActiveDanmakuItem>
        get() = active

    val currentTimelineRealMs: Long
        get() = timelineRealMs

    val isPaused: Boolean
        get() = paused

    val totalDroppedCount: Int
        get() = droppedCount

    val currentSettings: DanmakuSettings
        get() = settings

    /** Computes current usable vertical band and track counts matching §6.10 exactly. */
    fun computeLayoutMetrics(): TrackLayoutMetrics {
        val fovW = screenFovDeg.coerceIn(30.0f, 90.0f)
        val aspect = screenAspect.coerceIn(0.5f, 4.0f)
        val vFov = fovW / aspect
        val fontDeg = settings.fontAngularDeg()
        val pitchDeg = fontDeg * TRACK_PITCH_RATIO

        // Top ceiling: never above +18 deg above eye level (for giant screen where vFov/2 > 18 deg)
        val topSkipDeg = max(0.0f, vFov * 0.5f - MAX_ELEVATION_DEG)
        val maxNonSubDeg = max(pitchDeg, vFov - SUBTITLE_ZONE_DEG)

        val areaBottomDeg = when (settings.area) {
            DanmakuArea.QUARTER -> min(vFov * 0.25f, maxNonSubDeg)
            DanmakuArea.HALF -> min(vFov * 0.50f, maxNonSubDeg)
            DanmakuArea.FULL -> maxNonSubDeg
        }
        val usableDeg = max(0.0f, areaBottomDeg - topSkipDeg)
        val scrollTracks = DanmakuDepthMath.computeTrackCount(usableDeg, fontDeg)
        val areaBotFrac = (areaBottomDeg / vFov).coerceIn(0.15f, 1.0f)
        // Bottom fixed danmaku sit above subtitle zone (within full non-sub height, max 3 rows)
        val bottomAreaBotFrac = if (settings.area == DanmakuArea.FULL) {
            areaBotFrac
        } else {
            areaBotFrac
        }

        // Scroll angular speed: screenFovDeg / transitSec, capped at 12 deg/s on giant screens (§6.10)
        val rawSpeedDegPerSec = fovW / settings.transitDurationSec()
        val speedDegPerSec = min(rawSpeedDegPerSec, MAX_SCROLL_DEG_PER_SEC)

        return TrackLayoutMetrics(
            screenFovDeg = fovW,
            screenVFovDeg = vFov,
            fontDeg = fontDeg,
            pitchDeg = pitchDeg,
            topSkipDeg = topSkipDeg,
            areaBottomDeg = areaBottomDeg,
            areaBotFrac = bottomAreaBotFrac,
            scrollTrackCount = scrollTracks,
            bottomTrackCount = min(MAX_BOTTOM_ROWS, scrollTracks),
            speedDegPerSec = speedDegPerSec,
            gapDeg = settings.density.tailGapChars * fontDeg,
            maxOnScreen = settings.density.maxOnScreen,
        )
    }

    /**
     * Ingests newly fetched window items. When [windowIndex] >= 0, replaces any earlier items from
     * that same window so LRU eviction + seek-back or toggling duplicate-merge never duplicates items.
     */
    fun ingestWindowItems(items: List<DanmakuRawItem>, windowIndex: Int = -1) {
        var changed = false
        if (windowIndex >= 0) {
            val iter = scheduled.iterator()
            while (iter.hasNext()) {
                val existing = iter.next()
                if (existing.windowIndex == windowIndex) {
                    scheduledIds.remove(existing.id)
                    iter.remove()
                    changed = true
                }
            }
        }
        for (it in items) {
            val tagged = if (windowIndex >= 0 && it.windowIndex != windowIndex) {
                it.copy(windowIndex = windowIndex)
            } else {
                it
            }
            if (scheduledIds.add(tagged.id)) {
                scheduled.add(tagged)
                changed = true
            }
        }
        if (changed) {
            scheduled.sortWith(compareBy<DanmakuRawItem> { it.timeMs }.thenBy { it.id })
            // Re-sync cursor to current media position without backfilling already-passed items
            if (mediaCursorReady) {
                scheduledCursor = lowerBoundTime(lastMediaMs)
            }
        }
    }

    /** Updates screen & stereo geometry from renderer/palette. */
    fun updateScreenContext(
        fovDeg: Float,
        aspect: Float,
        distM: Float,
        stereoActive: Boolean,
        sourceFormat: StereoSourceFormat,
        contentPhiDeg: Float,
    ) {
        val f = fovDeg.coerceIn(30.0f, 90.0f)
        val a = aspect.coerceIn(0.5f, 4.0f)
        val d = distM.coerceIn(1.5f, 8.0f)
        if (f != this.screenFovDeg || a != this.screenAspect || d != this.screenDistM
            || stereoActive != this.stereoActive || sourceFormat != this.sourceFormat
        ) {
            dirtySnapshot = true
        }
        this.screenFovDeg = f
        this.screenAspect = a
        this.screenDistM = d
        this.stereoActive = stereoActive
        this.sourceFormat = sourceFormat
        this.contentPhiDeg = contentPhiDeg.coerceIn(0.0f, DanmakuDepthMath.MAX_NEAR_PHI_DEG)
    }

    /** Updates ONLY the latest smoothed 98th-percentile foreground disparity without altering screen/stereo state. */
    fun updateContentPhi(contentPhiDeg: Float) {
        this.contentPhiDeg = contentPhiDeg.coerceIn(0.0f, DanmakuDepthMath.MAX_NEAR_PHI_DEG)
    }

    /** Updates settings live per §6.10 rules (block immediately removes, float/layer animates 200 ms). */
    fun updateSettings(next: DanmakuSettings) {
        val prev = this.settings
        if (next == prev) return
        this.settings = next
        dirtySnapshot = true

        // Turning OFF clears screen immediately
        if (!next.on) {
            clearActiveItems()
            waitQueue.clear()
            previewActive = false
            return
        }

        // Changing block mask removes matching active items immediately (no fade-out per §6.10)
        if (next.blockMask != prev.blockMask) {
            removeActiveMatching { !it.isPreview && next.isBlocked(it.mode, it.colorRgb) }
        }

        // Changing area or font size resets preview so it re-lays out at new size/area
        if (next.area != prev.area || next.sizeTier != prev.sizeTier) {
            if (previewActive) {
                clearActiveItems()
                previewActive = false
            }
            // Remove any active items whose track is now outside the shrunken area
            val metrics = computeLayoutMetrics()
            removeActiveMatching {
                !it.isPreview && (
                    (it.mode != DanmakuMode.BOTTOM && it.trackIndex >= metrics.scrollTrackCount)
                        || (it.mode == DanmakuMode.BOTTOM && it.trackIndex >= metrics.bottomTrackCount)
                    )
            }
        }

        // §6.10: ONLY when the user drags Float or Layer do existing on-screen items animate (200 ms)
        val depthChanged = next.floatAuto != prev.floatAuto
            || next.floatAutoOffsetSteps != prev.floatAutoOffsetSteps
            || next.floatFixedSteps != prev.floatFixedSteps
            || next.depthLayer != prev.depthLayer
        if (depthChanged && active.isNotEmpty()) {
            val bands = computeCurrentDepthBands()
            for (it in active) {
                it.fixedPhiDeg = bands.phiFor(it.layerBand)
            }
            pendingDepthAnim = true
        }
    }

    fun setPaused(isPaused: Boolean) {
        if (this.paused == isPaused) return
        // Advance timeline right up to pause boundary before freezing
        advanceTimelineClock()
        this.paused = isPaused
        dirtySnapshot = true
    }

    fun setScrubbing(isScrubbing: Boolean) {
        if (this.scrubbing == isScrubbing) return
        this.scrubbing = isScrubbing
        if (isScrubbing) {
            clearActiveItems()
            waitQueue.clear()
            previewActive = false
        }
        dirtySnapshot = true
    }

    /** Controls §6.3 stationary 3-band preview when Danmaku tab is open & paused or empty. */
    fun setPreviewRequested(requested: Boolean) {
        if (this.previewRequested == requested) return
        this.previewRequested = requested
        if (!requested && previewActive) {
            clearActiveItems()
            previewActive = false
            dirtySnapshot = true
        }
    }

    /** Seek handler (§6.10): drops active/waiting items and jumps cursor with ZERO backfill. */
    fun onSeek(newMediaMs: Long) {
        val pos = newMediaMs.coerceAtLeast(0L)
        advanceTimelineClock()
        clearActiveItems()
        waitQueue.clear()
        previewActive = false
        lastMediaMs = pos
        mediaCursorReady = true
        scheduledCursor = lowerBoundTime(pos)
        dirtySnapshot = true
    }

    /** Full reset for switching video / episode (clears cache, atlas, active, wait queue, timeline). */
    fun resetForNewItem(startMediaMs: Long = 0L) {
        clearActiveItems()
        waitQueue.clear()
        scheduled.clear()
        scheduledIds.clear()
        scheduledCursor = 0
        atlas.clear()
        lastMediaMs = startMediaMs.coerceAtLeast(0L)
        mediaCursorReady = false
        previewActive = false
        scrubbing = false
        droppedCount = 0
        lastWallRealMs = clock.nowRealMs()
        timelineRealMs = 0L
        dirtySnapshot = true
    }

    /**
     * Advances the danmaku engine for the current video `mediaTimeMs` and virtual/monotonic clock.
     * Call at ~20 Hz (or on state changes) on the dedicated danmaku worker thread.
     */
    fun step(mediaTimeMs: Long): DanmakuStepResult {
        val newlySpawned = ArrayList<ActiveDanmakuItem>()
        val nowWall = clock.nowRealMs()
        if (!paused) {
            val dt = (nowWall - lastWallRealMs).coerceIn(0L, 250L)
            timelineRealMs += dt
        }
        lastWallRealMs = nowWall

        val metrics = computeLayoutMetrics()

        if (!settings.on || scrubbing) {
            if (active.isNotEmpty()) {
                clearActiveItems()
                dirtySnapshot = true
            }
            val changed = dirtySnapshot
            dirtySnapshot = false
            val anim = pendingDepthAnim
            pendingDepthAnim = false
            return DanmakuStepResult(changed, newlySpawned, anim, 0, metrics.areaBotFrac)
        }

        val mediaPos = mediaTimeMs.coerceAtLeast(0L)
        if (!mediaCursorReady) {
            mediaCursorReady = true
            lastMediaMs = mediaPos
            scheduledCursor = lowerBoundTime(mediaPos)
        } else if (mediaPos + SEEK_BACKWARD_GUARD_MS < lastMediaMs) {
            // Unexpected backward jump (e.g. loop repeat or external seek): treat as clean seek
            onSeek(mediaPos)
        }

        // 1. Reap expired active items and immediately recycle their atlas slots
        if (!previewActive && !paused) {
            val it = active.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.isExpiredAt(timelineRealMs)) {
                    item.atlasSlot?.let { slot -> atlas.release(slot) }
                    it.remove()
                    dirtySnapshot = true
                }
            }
        }

        // 2. If playing and real danmaku exist, exit preview mode
        if (!paused && previewActive && (scheduled.isNotEmpty() || !previewRequested)) {
            clearActiveItems()
            previewActive = false
            dirtySnapshot = true
        }

        // 3. Collect newly due items in [lastMediaMs, mediaPos] when playing (supports any playback speed!)
        if (!paused) {
            if (scheduledCursor < scheduled.size && scheduled[scheduledCursor].timeMs < lastMediaMs - 50L) {
                scheduledCursor = lowerBoundTime(lastMediaMs)
            }
            while (scheduledCursor < scheduled.size && scheduled[scheduledCursor].timeMs <= mediaPos) {
                val raw = scheduled[scheduledCursor++]
                if (!settings.isBlocked(raw.mode, raw.colorRgb)) {
                    waitQueue.addLast(WaitingItem(raw, timelineRealMs))
                }
            }
            lastMediaMs = mediaPos

            // 4. Drain wait queue (drop items waiting > 1,000 ms real time per §6.10)
            val depthBands = computeCurrentDepthBands()
            val retryQueue = ArrayList<WaitingItem>()
            while (waitQueue.isNotEmpty()) {
                val w = waitQueue.removeFirst()
                if (timelineRealMs - w.enqueuedRealMs > MAX_WAIT_REAL_MS) {
                    droppedCount++
                    continue
                }
                if (settings.isBlocked(w.raw.mode, w.raw.colorRgb)) {
                    continue
                }
                val placed = tryPlaceItem(w.raw, metrics, depthBands)
                if (placed != null) {
                    if (previewActive) {
                        clearActiveItems()
                        previewActive = false
                    }
                    active.add(placed)
                    newlySpawned.add(placed)
                    dirtySnapshot = true
                } else {
                    retryQueue.add(w)
                }
            }
            for (r in retryQueue) {
                waitQueue.addLast(r)
            }
        }

        // 5. §6.3 Preview mode: 3 stationary layered items when Danmaku tab open & (paused or screen empty)
        val wantPreview = previewRequested && (paused || active.none { !it.isPreview })
        if (wantPreview && !previewActive && active.none { !it.isPreview }) {
            spawnPreviewItems(metrics, computeCurrentDepthBands(), newlySpawned)
        } else if (!wantPreview && previewActive) {
            clearActiveItems()
            previewActive = false
            dirtySnapshot = true
        }

        val changed = dirtySnapshot || newlySpawned.isNotEmpty()
        val anim = pendingDepthAnim
        dirtySnapshot = false
        pendingDepthAnim = false
        return DanmakuStepResult(changed, newlySpawned, anim, active.size, metrics.areaBotFrac)
    }

    /** Packs all rasterized active items into `out` (zero heap allocation) for JNI upload. */
    fun packInstances(out: FloatArray): Int {
        val maxItems = min(active.size, min(XrShared.DANMAKU_MAX_ITEMS, out.size / XrShared.DANMAKU_INSTANCE_FLOATS))
        var count = 0
        for (i in 0 until maxItems) {
            val it = active[i]
            val slot = it.atlasSlot ?: continue
            val base = count * XrShared.DANMAKU_INSTANCE_FLOATS
            out[base + 0] = it.xStartNorm
            out[base + 1] = it.yNormInArea
            out[base + 2] = it.wNorm
            out[base + 3] = it.hNormInArea
            out[base + 4] = it.velNormPerSec
            out[base + 5] = slot.u0
            out[base + 6] = slot.v0
            out[base + 7] = slot.du
            out[base + 8] = slot.dv
            out[base + 9] = ((it.colorRgb ushr 16) and 0xFF) / 255.0f
            out[base + 10] = ((it.colorRgb ushr 8) and 0xFF) / 255.0f
            out[base + 11] = (it.colorRgb and 0xFF) / 255.0f
            out[base + 12] = 1.0f
            out[base + 13] = it.spawnRealMs / 1000.0f
            out[base + 14] = if (it.durationRealMs > 0L) it.durationRealMs / 1000.0f else 0.0f
            out[base + 15] = it.fadeMs / 1000.0f
            val modeCode = when (it.mode) {
                DanmakuMode.SCROLL -> XrShared.DANMAKU_MODE_SCROLL
                DanmakuMode.TOP -> XrShared.DANMAKU_MODE_TOP
                DanmakuMode.BOTTOM -> XrShared.DANMAKU_MODE_BOTTOM
            }
            out[base + 16] = (modeCode or (it.layerBand.nativeCode shl 8)).toFloat()
            out[base + 17] = it.fixedPhiDeg
            count++
        }
        return count
    }

    fun computeCurrentDepthBands(): DanmakuDepthBands {
        val effContent = DanmakuDepthMath.resolveContentPhi(stereoActive, sourceFormat, contentPhiDeg)
        return DanmakuDepthMath.computeDepthBands(effContent, settings, screenDistM)
    }

    private fun tryPlaceItem(
        raw: DanmakuRawItem,
        m: TrackLayoutMetrics,
        depthBands: DanmakuDepthBands,
    ): ActiveDanmakuItem? {
        // Density cap (§6.10: Sparse 20 / Medium 40 / Dense 80)
        val liveRealCount = active.count { !it.isPreview }
        if (liveRealCount >= m.maxOnScreen) return null

        val maxWidthDeg = m.screenFovDeg * MAX_WIDTH_FRAC
        val (displayText, widthDeg) = DanmakuDepthMath.fitToMaxWidthDeg(
            raw.displayText, m.fontDeg, maxWidthDeg, measurer,
        )
        if (displayText.isEmpty()) return null

        val trackIdx = when (raw.mode) {
            DanmakuMode.SCROLL -> findFreeScrollTrack(m)
            DanmakuMode.TOP -> findFreeTopTrack(m)
            DanmakuMode.BOTTOM -> findFreeBottomTrack(m)
        }
        if (trackIdx < 0) return null

        // Allocate atlas space BEFORE committing track (full atlas behaves like no free track per §6.10)
        val (patchW, patchH) = estimateAtlasPatchPx(widthDeg, m)
        val slot = atlas.allocate(patchW, patchH) ?: return null

        val band = when (raw.mode) {
            DanmakuMode.SCROLL -> DanmakuLayerBand.forScrollTrack(trackIdx)
            DanmakuMode.TOP, DanmakuMode.BOTTOM -> DanmakuLayerBand.NEAR
        }
        val fixedPhi = depthBands.phiFor(band)
        val wNorm = (widthDeg / m.screenFovDeg).coerceIn(0.01f, MAX_WIDTH_FRAC)
        val hNormInArea = (m.pitchDeg / m.areaBottomDeg).coerceIn(0.02f, 0.5f)
        val yNormInArea = when (raw.mode) {
            DanmakuMode.BOTTOM -> {
                // Bottom danmaku stack upward from bottom of non-subtitle area (max 3 rows)
                val rowFromBottom = trackIdx + 1
                val yDeg = m.areaBottomDeg - rowFromBottom * m.pitchDeg
                (yDeg / m.areaBottomDeg).coerceIn(0.0f, 1.0f - hNormInArea)
            }
            else -> {
                val yDeg = m.topSkipDeg + 0.25f + trackIdx * m.pitchDeg
                (yDeg / m.areaBottomDeg).coerceIn(0.0f, 1.0f - hNormInArea)
            }
        }

        return if (raw.mode == DanmakuMode.SCROLL) {
            // Constant angular speed across all scroll danmaku -> zero mid-flight overtaking!
            val velNormPerSec = -(m.speedDegPerSec / m.screenFovDeg)
            val totalTravelNorm = 1.0f + wNorm + 0.04f
            val durationMs = ((totalTravelNorm / (-velNormPerSec)) * 1000.0f).toLong().coerceAtLeast(1000L)
            ActiveDanmakuItem(
                runtimeId = nextRuntimeId++,
                displayText = displayText,
                colorRgb = raw.colorRgb,
                mode = DanmakuMode.SCROLL,
                trackIndex = trackIdx,
                layerBand = band,
                fixedPhiDeg = fixedPhi,
                spawnRealMs = timelineRealMs,
                durationRealMs = durationMs,
                fadeMs = 0L,
                xStartNorm = 1.0f,
                velNormPerSec = velNormPerSec,
                yNormInArea = yNormInArea,
                wNorm = wNorm,
                hNormInArea = hNormInArea,
                widthDeg = widthDeg,
                isPreview = false,
                atlasSlot = slot,
                rasterized = false,
            )
        } else {
            ActiveDanmakuItem(
                runtimeId = nextRuntimeId++,
                displayText = displayText,
                colorRgb = raw.colorRgb,
                mode = raw.mode,
                trackIndex = trackIdx,
                layerBand = DanmakuLayerBand.NEAR,
                fixedPhiDeg = fixedPhi,
                spawnRealMs = timelineRealMs,
                durationRealMs = FIXED_STAY_REAL_MS,
                fadeMs = FIXED_FADE_REAL_MS,
                xStartNorm = ((1.0f - wNorm) * 0.5f).coerceIn(0.0f, 1.0f - wNorm),
                velNormPerSec = 0.0f,
                yNormInArea = yNormInArea,
                wNorm = wNorm,
                hNormInArea = hNormInArea,
                widthDeg = widthDeg,
                isPreview = false,
                atlasSlot = slot,
                rasterized = false,
            )
        }
    }

    /**
     * Top-down scroll track search (§6.10): track `k` is free iff every active scroll item on `k`
     * has its trailing edge at least `gapDeg` inside the right edge (`tailNorm <= 1.0 - gapNorm`).
     */
    private fun findFreeScrollTrack(m: TrackLayoutMetrics): Int {
        val gapNorm = m.gapDeg / m.screenFovDeg
        val maxAllowedTail = 1.0f - gapNorm
        for (track in 0 until m.scrollTrackCount) {
            var free = true
            for (it in active) {
                if (!it.isPreview && it.mode == DanmakuMode.SCROLL && it.trackIndex == track) {
                    if (it.tailNormAt(timelineRealMs) > maxAllowedTail) {
                        free = false
                        break
                    }
                }
            }
            if (free) return track
        }
        return -1
    }

    private fun findFreeTopTrack(m: TrackLayoutMetrics): Int {
        for (track in 0 until m.scrollTrackCount) {
            if (active.none { !it.isPreview && it.mode == DanmakuMode.TOP && it.trackIndex == track }) {
                return track
            }
        }
        return -1
    }

    private fun findFreeBottomTrack(m: TrackLayoutMetrics): Int {
        for (track in 0 until m.bottomTrackCount) {
            if (active.none { !it.isPreview && it.mode == DanmakuMode.BOTTOM && it.trackIndex == track }) {
                return track
            }
        }
        return -1
    }

    private fun spawnPreviewItems(
        m: TrackLayoutMetrics,
        depthBands: DanmakuDepthBands,
        newlySpawned: MutableList<ActiveDanmakuItem>,
    ) {
        val labels = arrayOf(previewStrings.farLabel, previewStrings.midLabel, previewStrings.nearLabel)
        val bands = arrayOf(DanmakuLayerBand.FAR, DanmakuLayerBand.MID, DanmakuLayerBand.NEAR)
        val colors = intArrayOf(0xFFFFFF, 0xFFD966, 0x7FE7FF)
        val xStarts = floatArrayOf(0.14f, 0.34f, 0.54f)
        val maxTracks = max(1, m.scrollTrackCount)
        var spawnedAny = false

        for (i in 0 until 3) {
            val trackIdx = min(i, maxTracks - 1)
            val (txt, widthDeg) = DanmakuDepthMath.fitToMaxWidthDeg(
                labels[i], m.fontDeg, m.screenFovDeg * MAX_WIDTH_FRAC, measurer,
            )
            val (patchW, patchH) = estimateAtlasPatchPx(widthDeg, m)
            val slot = atlas.allocate(patchW, patchH) ?: continue
            val wNorm = (widthDeg / m.screenFovDeg).coerceIn(0.05f, MAX_WIDTH_FRAC)
            val hNormInArea = (m.pitchDeg / m.areaBottomDeg).coerceIn(0.02f, 0.33f)
            val yDeg = m.topSkipDeg + 0.25f + trackIdx * m.pitchDeg
            val yNormInArea = (yDeg / m.areaBottomDeg).coerceIn(0.0f, 1.0f - hNormInArea)
            val xNorm = xStarts[i].coerceIn(0.04f, max(0.04f, 0.96f - wNorm))
            val item = ActiveDanmakuItem(
                runtimeId = nextRuntimeId++,
                displayText = txt,
                colorRgb = colors[i],
                mode = DanmakuMode.TOP,
                trackIndex = trackIdx,
                layerBand = bands[i],
                fixedPhiDeg = depthBands.phiFor(bands[i]),
                spawnRealMs = timelineRealMs,
                durationRealMs = 0L, // stays up while preview active
                fadeMs = 0L,
                xStartNorm = xNorm,
                velNormPerSec = 0.0f,
                yNormInArea = yNormInArea,
                wNorm = wNorm,
                hNormInArea = hNormInArea,
                widthDeg = widthDeg,
                isPreview = true,
                atlasSlot = slot,
                rasterized = false,
            )
            active.add(item)
            newlySpawned.add(item)
            spawnedAny = true
        }
        if (spawnedAny) {
            previewActive = true
            dirtySnapshot = true
        }
    }

    private fun estimateAtlasPatchPx(widthDeg: Float, m: TrackLayoutMetrics): Pair<Int, Int> {
        val hPx = when (settings.sizeTier) {
            com.limelight.local.TextSizeTier.SMALL -> 52
            com.limelight.local.TextSizeTier.MEDIUM -> 68
            com.limelight.local.TextSizeTier.LARGE -> 88
        }
        val pxPerDeg = XrShared.DANMAKU_TEX_W.toFloat() / m.screenFovDeg
        val wPx = (widthDeg * pxPerDeg).toInt().coerceIn(32, 1024)
        return wPx to hPx
    }

    private fun advanceTimelineClock() {
        val nowWall = clock.nowRealMs()
        if (!paused) {
            val dt = (nowWall - lastWallRealMs).coerceIn(0L, 250L)
            timelineRealMs += dt
        }
        lastWallRealMs = nowWall
    }

    private fun clearActiveItems() {
        for (it in active) {
            it.atlasSlot?.let { slot -> atlas.release(slot) }
        }
        active.clear()
    }

    private fun removeActiveMatching(predicate: (ActiveDanmakuItem) -> Boolean) {
        val it = active.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (predicate(item)) {
                item.atlasSlot?.let { slot -> atlas.release(slot) }
                it.remove()
                dirtySnapshot = true
            }
        }
    }

    private fun lowerBoundTime(targetMs: Long): Int {
        var lo = 0
        var hi = scheduled.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (scheduled[mid].timeMs < targetMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val TRACK_PITCH_RATIO = 1.3f
        const val SUBTITLE_ZONE_DEG = 6.5f
        const val MAX_ELEVATION_DEG = 18.0f
        const val MAX_SCROLL_DEG_PER_SEC = 12.0f
        const val MAX_WIDTH_FRAC = 0.60f
        const val MAX_WAIT_REAL_MS = 1_000L
        const val FIXED_STAY_REAL_MS = 4_000L
        const val FIXED_FADE_REAL_MS = 150L
        const val MAX_BOTTOM_ROWS = 3
        private const val SEEK_BACKWARD_GUARD_MS = 1_500L
    }
}

/** Computed track layout snapshot for inspection & deterministic testing. */
data class TrackLayoutMetrics(
    val screenFovDeg: Float,
    val screenVFovDeg: Float,
    val fontDeg: Float,
    val pitchDeg: Float,
    val topSkipDeg: Float,
    val areaBottomDeg: Float,
    val areaBotFrac: Float,
    val scrollTrackCount: Int,
    val bottomTrackCount: Int,
    val speedDegPerSec: Float,
    val gapDeg: Float,
    val maxOnScreen: Int,
)
