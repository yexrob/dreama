package com.limelight.local.danmaku

import com.limelight.binding.video.XrShared
import com.limelight.local.DanmakuArea
import com.limelight.local.StereoSourceFormat
import com.limelight.local.plugin.DanmakuMode
import kotlin.math.abs
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
    val clockReset: Boolean,
    val activeCount: Int,
    val areaBotFrac: Float,
    val anchorMonoNs: Long = 0L,
)

/**
 * Pure-Kotlin, 100% deterministic danmaku track allocator, collision avoider, timeline engine,
 * and stereoscopic layered-float controller (ux-spec.md §6.10, §6.3, §6.12).
 *
 * Hard invariants:
 * - Forward media jumps exceeding `expectedAdvance + 1.5 s` AND backward jumps exceeding `1.5 s`
 *   are treated as clean seeks with ZERO backfill.
 * - Scheduled items are bounded to `[currentWindow - 1, currentWindow + 1]` and capped at
 *   [MAX_ITEMS_PER_WINDOW] per window, merged in O(N + M) without full re-sorting.
 * - Per-tick spawn count respects [maxNewSpawns] so bursts never overflow the native atlas queue.
 * - [packInstances] emits ONLY items whose atlas patch has already been rasterized & accepted,
 *   tagging each instance with `(slotIndex, generation)` for GPU-side landing verification.
 */
class DanmakuEngine(
    private val clock: DanmakuClock,
    private val measurer: DanmakuTextMeasurer = HeuristicDanmakuMeasurer,
    val atlas: DanmakuAtlasAllocator = DanmakuAtlasAllocator(),
    private val previewStrings: DanmakuPreviewStrings = DanmakuPreviewStrings(),
    private val windowMs: Long = DanmakuWindowFetcher.WINDOW_MS,
    private val monoClockNs: () -> Long = System::nanoTime,
    private val deferSlotRelease: Boolean = false,
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
    private var playbackSpeed: Float = 1.0f

    // Real-time timeline that freezes while paused/buffering so active items stay frozen at exact X
    private var lastWallRealMs: Long = clock.nowRealMs()
    private var timelineRealMs: Long = 0L

    // Last processed video media timestamp (ms) and bounded window anchor
    private var lastMediaMs: Long = 0L
    private var mediaCursorReady: Boolean = false
    private var lastPrunedWindow: Int = Int.MIN_VALUE

    // Pending timeline items from active windows [cur-1 .. cur+1], strictly sorted by (timeMs, id)
    private var scheduled = ArrayList<DanmakuRawItem>()
    private val scheduledIds = HashSet<Long>()
    private val loadedWindowIndices = HashSet<Int>()
    private var scheduledCursor: Int = 0

    // Bounded 1 s wait queue when tracks, density cap, atlas, or upload queue are temporarily full
    private val waitQueue = ArrayDeque<WaitingItem>()

    // Currently live items on screen (or 3 stationary previews)
    private val active = ArrayList<ActiveDanmakuItem>()
    // Slots removed during the current tick when deferSlotRelease is true; recycled only AFTER
    // the new snapshot drops their references so same-tick spawns never overwrite live pixels.
    private val deferredSlotReleases = ArrayList<DanmakuAtlasSlot>()
    private var nextRuntimeId: Long = 1L

    private var pendingDepthAnim: Boolean = false
    private var pendingClockReset: Boolean = true
    private var dirtySnapshot: Boolean = true
    private var droppedCount: Int = 0

    val activeItems: List<ActiveDanmakuItem>
        get() = active

    val scheduledItemCount: Int
        get() = scheduled.size

    val currentTimelineRealMs: Long
        get() = timelineRealMs

    val isPaused: Boolean
        get() = paused

    val totalDroppedCount: Int
        get() = droppedCount

    val currentSettings: DanmakuSettings
        get() = settings

    /** Returns true if [windowIndex] is currently resident in this engine's bounded window set. */
    fun hasWindowLoaded(windowIndex: Int): Boolean = loadedWindowIndices.contains(windowIndex)

    /**
     * Releases atlas slots freed by removals before the snapshot just published to native GL.
     * Calling this strictly AFTER publishing the updated snapshot prevents same-tick reuse
     * from overwriting pixels still referenced by the render thread's previous snapshot.
     */
    fun commitPublishedSnapshot() {
        if (deferredSlotReleases.isEmpty()) return
        for (slot in deferredSlotReleases) {
            atlas.release(slot)
        }
        deferredSlotReleases.clear()
    }

    /** Computes current usable vertical band and track counts matching §6.10 exactly. */
    fun computeLayoutMetrics(): TrackLayoutMetrics {
        val fovW = screenFovDeg.coerceIn(30.0f, 90.0f)
        val aspect = screenAspect.coerceIn(0.5f, 4.0f)
        val vFov = fovW / aspect
        val fontDeg = settings.fontAngularDeg()
        val pitchDeg = fontDeg * TRACK_PITCH_RATIO

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

        val rawSpeedDegPerSec = fovW / settings.transitDurationSec()
        val speedDegPerSec = min(rawSpeedDegPerSec, MAX_SCROLL_DEG_PER_SEC)

        return TrackLayoutMetrics(
            screenFovDeg = fovW,
            screenVFovDeg = vFov,
            fontDeg = fontDeg,
            pitchDeg = pitchDeg,
            topSkipDeg = topSkipDeg,
            areaBottomDeg = areaBottomDeg,
            areaBotFrac = areaBotFrac,
            scrollTrackCount = scrollTracks,
            bottomTrackCount = min(MAX_BOTTOM_ROWS, scrollTracks),
            speedDegPerSec = speedDegPerSec,
            gapDeg = settings.density.tailGapChars * fontDeg,
            maxOnScreen = settings.density.maxOnScreen,
        )
    }

    /**
     * Ingests newly fetched window items in O(N + M) sorted merge order.
     * - Replaces any earlier items from the same [windowIndex] so LRU re-fetch or toggling
     *   duplicate-merge never creates duplicates.
     * - Enforces [MAX_ITEMS_PER_WINDOW] cap and prunes windows outside `[cur - 1, cur + 1]`.
     */
    fun ingestWindowItems(items: List<DanmakuRawItem>, windowIndex: Int = -1) {
        if (windowIndex >= 0 && mediaCursorReady) {
            val curWin = windowIndexFor(lastMediaMs)
            if (abs(windowIndex - curWin) > 1) {
                return
            }
        }
        if (windowIndex >= 0) {
            removeWindowFromScheduled(windowIndex)
            loadedWindowIndices.add(windowIndex)
        }
        if (items.isEmpty()) {
            if (mediaCursorReady) {
                scheduledCursor = lowerBoundTime(lastMediaMs)
            }
            return
        }

        val cappedCount = min(items.size, MAX_ITEMS_PER_WINDOW)
        val incoming = ArrayList<DanmakuRawItem>(cappedCount)
        var alreadySorted = true
        var prevTime = Long.MIN_VALUE
        var prevId = Long.MIN_VALUE
        for (i in 0 until cappedCount) {
            val raw = items[i]
            val tagged = if (windowIndex >= 0 && raw.windowIndex != windowIndex) {
                raw.copy(windowIndex = windowIndex)
            } else {
                raw
            }
            if (scheduledIds.add(tagged.id)) {
                if (tagged.timeMs < prevTime || (tagged.timeMs == prevTime && tagged.id < prevId)) {
                    alreadySorted = false
                }
                prevTime = tagged.timeMs
                prevId = tagged.id
                incoming.add(tagged)
            }
        }
        if (incoming.isEmpty()) return
        if (!alreadySorted) {
            incoming.sortWith(ITEM_COMPARATOR)
        }
        mergeSortedIntoScheduled(incoming)
        if (mediaCursorReady) {
            pruneScheduledAround(lastMediaMs)
            scheduledCursor = lowerBoundTime(lastMediaMs)
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

    /** Updates ONLY the latest smoothed 98th-percentile foreground disparity. */
    fun updateContentPhi(contentPhiDeg: Float) {
        this.contentPhiDeg = contentPhiDeg.coerceIn(0.0f, DanmakuDepthMath.MAX_NEAR_PHI_DEG)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = if (speed.isFinite()) speed.coerceIn(0.25f, 4.0f) else 1.0f
    }

    /** Updates settings live per §6.10 rules (block immediately removes, float/layer animates 200 ms). */
    fun updateSettings(next: DanmakuSettings) {
        val prev = this.settings
        if (next == prev) return
        this.settings = next
        dirtySnapshot = true

        if (!next.on) {
            clearActiveItems()
            waitQueue.clear()
            previewActive = false
            return
        }

        if (next.blockMask != prev.blockMask) {
            removeActiveMatching { !it.isPreview && next.isBlocked(it.mode, it.colorRgb) }
        }

        if (next.area != prev.area || next.sizeTier != prev.sizeTier) {
            if (previewActive) {
                clearActiveItems()
                previewActive = false
            }
            val metrics = computeLayoutMetrics()
            removeActiveMatching {
                !it.isPreview && (
                    (it.mode != DanmakuMode.BOTTOM && it.trackIndex >= metrics.scrollTrackCount)
                        || (it.mode == DanmakuMode.BOTTOM && it.trackIndex >= metrics.bottomTrackCount)
                    )
            }
        }

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
        applySeekInternal(newMediaMs, advanceClock = true)
    }

    private fun applySeekInternal(newMediaMs: Long, advanceClock: Boolean) {
        val pos = newMediaMs.coerceAtLeast(0L)
        if (advanceClock) {
            advanceTimelineClock()
        }
        clearActiveItems()
        waitQueue.clear()
        previewActive = false
        lastMediaMs = pos
        mediaCursorReady = true
        pruneScheduledAround(pos)
        scheduledCursor = lowerBoundTime(pos)
        pendingClockReset = true
        dirtySnapshot = true
    }

    /** Full reset for switching video / episode (clears cache, atlas, active, wait queue, timeline). */
    fun resetForNewItem(startMediaMs: Long = 0L) {
        clearActiveItems()
        deferredSlotReleases.clear()
        waitQueue.clear()
        scheduled.clear()
        scheduledIds.clear()
        loadedWindowIndices.clear()
        scheduledCursor = 0
        lastPrunedWindow = Int.MIN_VALUE
        atlas.clear()
        lastMediaMs = startMediaMs.coerceAtLeast(0L)
        mediaCursorReady = false
        previewActive = false
        scrubbing = false
        droppedCount = 0
        lastWallRealMs = clock.nowRealMs()
        timelineRealMs = 0L
        pendingClockReset = true
        dirtySnapshot = true
    }

    /**
     * Advances the danmaku engine for the current video [mediaTimeMs] and virtual/monotonic clock.
     * [maxNewSpawns] caps newly spawned items in this tick so bursts never overflow the native
     * atlas upload queue. Captures `anchorMonoNs` atomically with `timelineRealMs` BEFORE any
     * worker-thread rasterization runs so publish latency never introduces speed wobble.
     */
    fun step(mediaTimeMs: Long, maxNewSpawns: Int = Int.MAX_VALUE): DanmakuStepResult {
        val newlySpawned = ArrayList<ActiveDanmakuItem>()
        val nowWall = clock.nowRealMs()
        val anchorMonoNs = monoClockNs()
        val dt = if (!paused) {
            (nowWall - lastWallRealMs).coerceIn(0L, MAX_STEP_DT_MS).also { timelineRealMs += it }
        } else {
            0L
        }
        lastWallRealMs = nowWall

        val metrics = computeLayoutMetrics()

        if (!settings.on || scrubbing) {
            if (active.isNotEmpty()) {
                clearActiveItems()
                dirtySnapshot = true
            }
            val changed = dirtySnapshot
            val anim = pendingDepthAnim
            val reset = pendingClockReset
            dirtySnapshot = false
            pendingDepthAnim = false
            pendingClockReset = false
            return DanmakuStepResult(
                changed, newlySpawned, anim, reset, 0, metrics.areaBotFrac, anchorMonoNs,
            )
        }

        val mediaPos = mediaTimeMs.coerceAtLeast(0L)
        if (!mediaCursorReady) {
            mediaCursorReady = true
            lastMediaMs = mediaPos
            pruneScheduledAround(mediaPos)
            scheduledCursor = lowerBoundTime(mediaPos)
        } else if (!paused) {
            val expectedAdvanceMs = (dt * max(1.0f, playbackSpeed)).toLong()
            val jumpedBackward = mediaPos + SEEK_BACKWARD_GUARD_MS < lastMediaMs
            val jumpedForward = mediaPos > lastMediaMs + expectedAdvanceMs + SEEK_FORWARD_GUARD_MS
            if (jumpedBackward || jumpedForward) {
                // Any unexpected backward OR forward jump > (expected + 1.5 s) is treated as a clean
                // seek so resume/refresh/external jumps NEVER backfill skipped danmaku.
                applySeekInternal(mediaPos, advanceClock = false)
            }
        }

        pruneScheduledAround(mediaPos)

        // 1. Reap expired active items and recycle (or defer recycling of) their atlas slots
        if (!previewActive && !paused) {
            val it = active.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.isExpiredAt(timelineRealMs)) {
                    item.atlasSlot?.let(::releaseSlot)
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

        // 3. Collect newly due items in [lastMediaMs, mediaPos] when playing (supports any playback speed)
        val spawnBudget = maxNewSpawns.coerceAtLeast(0)
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

            // 4. Drain wait queue (drop items waiting > 1,000 ms real time; throttle to spawnBudget)
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
                if (newlySpawned.size >= spawnBudget) {
                    retryQueue.add(w)
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

        // 5. §6.3 Preview mode: 3 stationary layered items when Danmaku tab open & (paused or empty)
        val wantPreview = previewRequested && (paused || active.none { !it.isPreview })
        if (wantPreview && !previewActive && active.none { !it.isPreview } && spawnBudget >= 3) {
            spawnPreviewItems(metrics, computeCurrentDepthBands(), newlySpawned)
        } else if (!wantPreview && previewActive) {
            clearActiveItems()
            previewActive = false
            dirtySnapshot = true
        }

        val changed = dirtySnapshot || newlySpawned.isNotEmpty()
        val anim = pendingDepthAnim
        val reset = pendingClockReset
        dirtySnapshot = false
        pendingDepthAnim = false
        pendingClockReset = false
        return DanmakuStepResult(
            changed, newlySpawned, anim, reset, active.size, metrics.areaBotFrac, anchorMonoNs,
        )
    }

    /**
     * Packs ONLY rasterized & upload-accepted active items into [out] (zero heap allocation).
     * Each instance carries 20 floats including `(slotIndex, generation)` so the native render
     * thread skips any item whose GPU atlas patch has not yet finished uploading.
     */
    fun packInstances(out: FloatArray): Int {
        val maxItems = min(active.size, min(XrShared.DANMAKU_MAX_ITEMS, out.size / XrShared.DANMAKU_INSTANCE_FLOATS))
        var count = 0
        for (i in 0 until active.size) {
            if (count >= maxItems) break
            val it = active[i]
            if (!it.rasterized) continue
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
            out[base + 18] = slot.slotIndex.toFloat()
            out[base + 19] = slot.generation.toFloat()
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
                durationRealMs = 0L,
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
            val dt = (nowWall - lastWallRealMs).coerceIn(0L, MAX_STEP_DT_MS)
            timelineRealMs += dt
        }
        lastWallRealMs = nowWall
    }

    private fun releaseSlot(slot: DanmakuAtlasSlot) {
        if (deferSlotRelease) {
            deferredSlotReleases.add(slot)
        } else {
            atlas.release(slot)
        }
    }

    private fun clearActiveItems() {
        for (it in active) {
            it.atlasSlot?.let(::releaseSlot)
        }
        active.clear()
    }

    private fun removeActiveMatching(predicate: (ActiveDanmakuItem) -> Boolean) {
        val it = active.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (predicate(item)) {
                item.atlasSlot?.let(::releaseSlot)
                it.remove()
                dirtySnapshot = true
            }
        }
    }

    private fun windowIndexFor(mediaMs: Long): Int =
        (mediaMs.coerceAtLeast(0L) / windowMs).toInt()

    private fun removeWindowFromScheduled(windowIndex: Int) {
        loadedWindowIndices.remove(windowIndex)
        if (scheduled.isEmpty()) return
        val kept = ArrayList<DanmakuRawItem>(scheduled.size)
        for (it in scheduled) {
            if (it.windowIndex == windowIndex) {
                scheduledIds.remove(it.id)
            } else {
                kept.add(it)
            }
        }
        scheduled = kept
    }

    /** Keeps strictly `[curWindow - 1, curWindow + 1]` in memory so long dense videos stay O(1) RAM. */
    private fun pruneScheduledAround(mediaMs: Long) {
        val curWin = windowIndexFor(mediaMs)
        if (curWin == lastPrunedWindow) return
        lastPrunedWindow = curWin
        val minKeep = curWin - 1
        val maxKeep = curWin + 1
        loadedWindowIndices.removeAll { it < minKeep || it > maxKeep }
        if (scheduled.isEmpty()) return
        var anyDropped = false
        for (it in scheduled) {
            if (it.windowIndex >= 0 && (it.windowIndex < minKeep || it.windowIndex > maxKeep)) {
                anyDropped = true
                break
            }
        }
        if (!anyDropped) return
        val kept = ArrayList<DanmakuRawItem>(scheduled.size)
        for (it in scheduled) {
            if (it.windowIndex >= 0 && (it.windowIndex < minKeep || it.windowIndex > maxKeep)) {
                scheduledIds.remove(it.id)
            } else {
                kept.add(it)
            }
        }
        scheduled = kept
        scheduledCursor = lowerBoundTime(mediaMs)
    }

    /** O(N + M) merge of two already-sorted lists into [scheduled]. */
    private fun mergeSortedIntoScheduled(incoming: List<DanmakuRawItem>) {
        if (scheduled.isEmpty()) {
            scheduled.addAll(incoming)
            return
        }
        val merged = ArrayList<DanmakuRawItem>(scheduled.size + incoming.size)
        var i = 0
        var j = 0
        while (i < scheduled.size && j < incoming.size) {
            val a = scheduled[i]
            val b = incoming[j]
            if (ITEM_COMPARATOR.compare(a, b) <= 0) {
                merged.add(a)
                i++
            } else {
                merged.add(b)
                j++
            }
        }
        while (i < scheduled.size) merged.add(scheduled[i++])
        while (j < incoming.size) merged.add(incoming[j++])
        scheduled = merged
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
        const val MAX_ITEMS_PER_WINDOW = DanmakuWindowFetcher.MAX_ITEMS_PER_WINDOW
        const val SEEK_BACKWARD_GUARD_MS = 1_500L
        const val SEEK_FORWARD_GUARD_MS = 1_500L
        private const val MAX_STEP_DT_MS = 250L
        private val ITEM_COMPARATOR = compareBy<DanmakuRawItem> { it.timeMs }.thenBy { it.id }
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
