package com.limelight.local

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Reusable mutable container drained on the palette worker thread (zero per-frame allocations). */
class PaletteDrainedInput {
    @JvmField var open: Boolean = false
    @JvmField var pinned: Boolean = false
    @JvmField var handTrackingMode: Boolean = false
    @JvmField var dimFallback: Boolean = false
    @JvmField var hoverX: Float = -1f
    @JvmField var hoverY: Float = -1f
    @JvmField var clickState: Int = 0
    @JvmField var clickX: Float = -1f
    @JvmField var clickY: Float = -1f
    @JvmField var rowStep: Int = 0
    @JvmField var rowReset: Boolean = false
    @JvmField var tabStep: Int = 0
    @JvmField var pageStep: Int = 0
}

/**
 * Lock-free, zero-allocation handoff between the XR render thread and the dedicated palette
 * worker thread (G2 review #5).
 *
 * - Idle frames touch only render-thread-private primitive shadows and perform ZERO atomics,
 *   ZERO allocations, ZERO locks, and ZERO Handler posts.
 * - Continuous visual/hover state uses latest-value overwrite; discrete one-shot edges
 *   (trigger clicks, stick row/tab/page steps, stick-click resets) accumulate atomically so
 *   no input event is lost while the worker renders a 1024x768 Canvas sheet.
 */
class PaletteInputMailbox {
    // Render-thread-only shadow values for zero-cost idle gating
    private var lastOpen = false
    private var lastPinned = false
    private var lastHandMode = false
    private var lastDimFallback = false
    private var lastHoverX = -1f
    private var lastHoverY = -1f
    private var lastSliderHeld = false

    // Shared volatile continuous state (written by render thread, read by worker)
    @Volatile private var curOpen = false
    @Volatile private var curPinned = false
    @Volatile private var curHandMode = false
    @Volatile private var curDimFallback = false
    @Volatile private var curHoverX = -1f
    @Volatile private var curHoverY = -1f
    @Volatile private var curSliderHeld = false

    // Atomic accumulators for discrete events
    private val pendingClickEdge = AtomicInteger(0)
    private val packedClickCoords = AtomicLong(packCoords(-1f, -1f))
    private val pendingRowSteps = AtomicInteger(0)
    private val pendingRowReset = AtomicBoolean(false)
    private val pendingTabSteps = AtomicInteger(0)
    private val pendingPageSteps = AtomicInteger(0)
    private val continuousDirty = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)

    /**
     * Called on the XR render thread every frame. Never blocks, never allocates.
     * Returns `true` iff the caller should post its preallocated drain Runnable to the worker.
     */
    fun offerFromRenderThread(
        open: Boolean,
        pinned: Boolean,
        handTrackingMode: Boolean,
        dimFallback: Boolean,
        hoverX: Float,
        hoverY: Float,
        clickState: Int,
        rowStep: Int,
        rowReset: Boolean,
        tabStep: Int,
        pageStep: Int,
    ): Boolean {
        val sliderHeld = clickState == 2
        val clickEdge = clickState == 1
        val stateChanged = open != lastOpen
            || pinned != lastPinned
            || handTrackingMode != lastHandMode
            || dimFallback != lastDimFallback
            || sliderHeld != lastSliderHeld
            || abs(hoverX - lastHoverX) >= 0.5f
            || abs(hoverY - lastHoverY) >= 0.5f
        val hasDiscrete = clickEdge || rowStep != 0 || rowReset || tabStep != 0 || pageStep != 0
        if (!stateChanged && !hasDiscrete && !sliderHeld) {
            return false
        }

        lastOpen = open
        lastPinned = pinned
        lastHandMode = handTrackingMode
        lastDimFallback = dimFallback
        lastHoverX = hoverX
        lastHoverY = hoverY
        lastSliderHeld = sliderHeld

        curOpen = open
        curPinned = pinned
        curHandMode = handTrackingMode
        curDimFallback = dimFallback
        curHoverX = hoverX
        curHoverY = hoverY
        curSliderHeld = sliderHeld

        if (clickEdge) {
            packedClickCoords.set(packCoords(hoverX, hoverY))
            pendingClickEdge.set(1)
        }
        if (rowStep != 0) pendingRowSteps.addAndGet(rowStep)
        if (rowReset) pendingRowReset.set(true)
        if (tabStep != 0) pendingTabSteps.addAndGet(tabStep)
        if (pageStep != 0) pendingPageSteps.addAndGet(pageStep)
        if (stateChanged || sliderHeld) continuousDirty.set(true)

        return drainScheduled.compareAndSet(false, true)
    }

    /**
     * Called exclusively on the palette worker thread. Drains all accumulated inputs into [out].
     * Returns `true` if any pending state or event was drained.
     */
    fun drainOnWorker(out: PaletteDrainedInput): Boolean {
        drainScheduled.set(false)
        val clickEdge = pendingClickEdge.getAndSet(0) != 0
        val rowStep = pendingRowSteps.getAndSet(0)
        val rowReset = pendingRowReset.getAndSet(false)
        val tabStep = pendingTabSteps.getAndSet(0)
        val pageStep = pendingPageSteps.getAndSet(0)
        val cont = continuousDirty.getAndSet(false)

        if (!cont && !clickEdge && rowStep == 0 && !rowReset && tabStep == 0 && pageStep == 0) {
            return false
        }

        out.open = curOpen
        out.pinned = curPinned
        out.handTrackingMode = curHandMode
        out.dimFallback = curDimFallback
        out.hoverX = curHoverX
        out.hoverY = curHoverY
        val held = curSliderHeld
        if (clickEdge) {
            val packed = packedClickCoords.get()
            out.clickX = unpackX(packed)
            out.clickY = unpackY(packed)
            out.clickState = 1
        } else {
            out.clickX = curHoverX
            out.clickY = curHoverY
            out.clickState = if (held) 2 else 0
        }
        out.rowStep = rowStep
        out.rowReset = rowReset
        out.tabStep = tabStep
        out.pageStep = pageStep
        return true
    }

    /** Resets all pending/shadow state when switching media items or restarting sessions. */
    fun reset() {
        lastOpen = false
        lastPinned = false
        lastHandMode = false
        lastDimFallback = false
        lastHoverX = -1f
        lastHoverY = -1f
        lastSliderHeld = false
        curOpen = false
        curPinned = false
        curHandMode = false
        curDimFallback = false
        curHoverX = -1f
        curHoverY = -1f
        curSliderHeld = false
        pendingClickEdge.set(0)
        packedClickCoords.set(packCoords(-1f, -1f))
        pendingRowSteps.set(0)
        pendingRowReset.set(false)
        pendingTabSteps.set(0)
        pendingPageSteps.set(0)
        continuousDirty.set(false)
        drainScheduled.set(false)
    }

    companion object {
        private fun packCoords(x: Float, y: Float): Long {
            val hi = java.lang.Float.floatToRawIntBits(x).toLong() shl 32
            val lo = java.lang.Float.floatToRawIntBits(y).toLong() and 0xFFFFFFFFL
            return hi or lo
        }

        private fun unpackX(packed: Long): Float =
            java.lang.Float.intBitsToFloat((packed ushr 32).toInt())

        private fun unpackY(packed: Long): Float =
            java.lang.Float.intBitsToFloat((packed and 0xFFFFFFFFL).toInt())
    }
}

/**
 * Deferred persistence coordinator for the palette worker thread (G2 review #6):
 * - Marks settings dirty during slider drags (`clickState == 2`) or continuous stick repeat
 *   without touching SharedPreferences on every frame.
 * - Flushes in a single batched `apply()` when the drag ends, on discrete chip/button clicks,
 *   when the palette closes, when the debounce timer expires, or on lifecycle pause/destroy.
 */
class PaletteSaveDebouncer {
    var globalDirty: Boolean = false
        private set
    var videoDirty: Boolean = false
        private set
    private var wasSliderDragging: Boolean = false
    private var wasOpen: Boolean = false

    fun onFrameResult(
        isOpen: Boolean,
        clickState: Int,
        saveGlobal: Boolean,
        saveVideo: Boolean,
    ): Int {
        if (saveGlobal) globalDirty = true
        if (saveVideo) videoDirty = true

        val isSliderDrag = clickState == 2
        val dragEnded = wasSliderDragging && !isSliderDrag
        val closed = wasOpen && !isOpen
        wasSliderDragging = isSliderDrag
        wasOpen = isOpen

        if (!globalDirty && !videoDirty) return 0

        // Never flush during an active slider drag; wait for release/close/debounce
        if (isSliderDrag) {
            return FLAG_SCHEDULE_DEBOUNCE
        }

        // Immediate single-batch flush on drag release, palette close, or discrete click
        if (dragEnded || closed || clickState == 1) {
            return consumeDirtyFlags()
        }

        // Stick-X repeat or hand-row drag: debounce so 8 Hz stepping writes once at rest
        return FLAG_SCHEDULE_DEBOUNCE
    }

    fun onDebounceTimeout(): Int = consumeDirtyFlags()

    fun onForceFlush(): Int = consumeDirtyFlags()

    fun reset() {
        globalDirty = false
        videoDirty = false
        wasSliderDragging = false
        wasOpen = false
    }

    private fun consumeDirtyFlags(): Int {
        var flags = 0
        if (globalDirty) {
            globalDirty = false
            flags = flags or FLAG_FLUSH_GLOBAL
        }
        if (videoDirty) {
            videoDirty = false
            flags = flags or FLAG_FLUSH_VIDEO
        }
        return flags
    }

    companion object {
        const val FLAG_FLUSH_GLOBAL = 1 shl 0
        const val FLAG_FLUSH_VIDEO = 1 shl 1
        const val FLAG_SCHEDULE_DEBOUNCE = 1 shl 2
        const val DEBOUNCE_MS = 250L
    }
}

/**
 * Immutable lock-free snapshot of dirty palette settings published by the palette worker so
 * `Activity.onDestroy()` can commit pending edits via non-blocking `SharedPreferences.Editor.apply()`
 * in 0 ms without blocking the main thread on the worker HandlerThread.
 */
data class PendingPaletteSaveSnapshot(
    val global: GlobalPaletteSettings?,
    val videoKey: String?,
    val video: VideoPaletteOverrides?,
)

class PalettePendingSaveBuffer {
    private val ref = java.util.concurrent.atomic.AtomicReference<PendingPaletteSaveSnapshot?>(null)

    fun publish(
        globalDirty: Boolean,
        global: GlobalPaletteSettings,
        videoDirty: Boolean,
        videoKey: String?,
        video: VideoPaletteOverrides,
    ) {
        if (!globalDirty && !videoDirty) {
            ref.set(null)
            return
        }
        ref.set(
            PendingPaletteSaveSnapshot(
                global = if (globalDirty) global else null,
                videoKey = if (videoDirty) videoKey else null,
                video = if (videoDirty) video else null,
            ),
        )
    }

    fun clear() {
        ref.set(null)
    }

    fun drainForDestroy(): PendingPaletteSaveSnapshot? = ref.getAndSet(null)
}
