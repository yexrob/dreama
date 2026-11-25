package com.limelight.local.danmaku

import com.limelight.local.plugin.DanmakuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/** Result of one segmented danmaku window fetch, distinguishing runtime-busy from errors. */
sealed class DanmakuFetchResult {
    data class Loaded(val items: List<DanmakuItem>) : DanmakuFetchResult()
    data object Busy : DanmakuFetchResult()
}

/** Abstract source of segmented danmaku windows (plugin idle-only capability OR local/WebDAV XML). */
fun interface DanmakuDataSource {
    suspend fun fetchWindow(fromMs: Long, toMs: Long): DanmakuFetchResult

    companion object {
        fun fromList(loader: suspend (fromMs: Long, toMs: Long) -> List<DanmakuItem>): DanmakuDataSource =
            DanmakuDataSource { fromMs, toMs -> DanmakuFetchResult.Loaded(loader(fromMs, toMs)) }
    }
}

/**
 * 60-second segmented danmaku window fetcher with:
 * - 10-second lookahead prefetch and bounded 20-window LRU cache;
 * - Plugin-safe concurrency (default 1 concurrent request for plugins) & non-blocking `Busy`
 *   rescheduling so danmaku NEVER starves `playback` / stream refresh;
 * - Fetch pause gate (`setFetchPaused`) during `refreshInFlight` or `!ready`;
 * - Generation-based seek cancellation discarding stale results;
 * - Exponential retry backoff (`1s/2s/4s`) + automatic retry after [failedCooldownMs] cooldown
 *   (default 30 s) in addition to manual Palette Retry;
 * - §6.10 5-second duplicate merging (`"text ×N"` when count >= 3) & per-window item cap.
 */
class DanmakuWindowFetcher(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = WINDOW_MS,
    private val prefetchLeadMs: Long = PREFETCH_LEAD_MS,
    private val maxCachedWindows: Int = MAX_CACHED_WINDOWS,
    private val defaultMaxConcurrentFetches: Int = MAX_CONCURRENT_PLUGIN_FETCHES,
    private val backoffScheduleMs: LongArray = DEFAULT_BACKOFF_MS,
    private val busyRetryDelayMs: Long = DEFAULT_BUSY_RETRY_DELAY_MS,
    private val failedCooldownMs: Long = DEFAULT_FAILED_COOLDOWN_MS,
    private val maxItemsPerWindow: Int = MAX_ITEMS_PER_WINDOW,
    private val onWindowReady: (windowIndex: Int, items: List<DanmakuRawItem>) -> Unit = { _, _ -> },
    private val onLoadStateChanged: (failed: Boolean) -> Unit = {},
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val nextItemId = AtomicLong(1L)

    private val cache = object : LinkedHashMap<Int, List<DanmakuRawItem>>(maxCachedWindows + 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<DanmakuRawItem>>?): Boolean =
            size > maxCachedWindows
    }
    private val inFlight = HashMap<Int, Job>()
    private val failedAtMs = HashMap<Int, Long>()

    private var dataSource: DanmakuDataSource? = null
    private var maxConcurrentFetches: Int = defaultMaxConcurrentFetches
    private var fetchPaused: Boolean = false
    private var generation: Long = 0L
    private var lastPositionMs: Long = 0L
    private var mergeDuplicates: Boolean = true
    private var lastReportedFailed: Boolean = false
    private var closed: Boolean = false

    val isLoadFailed: Boolean
        get() = synchronized(lock) { lastReportedFailed }

    fun setDataSource(
        source: DanmakuDataSource?,
        mergeDuplicates: Boolean,
        maxConcurrency: Int = defaultMaxConcurrentFetches,
    ) {
        val jobsToCancel: List<Job>
        synchronized(lock) {
            generation++
            this.dataSource = source
            this.mergeDuplicates = mergeDuplicates
            this.maxConcurrentFetches = maxConcurrency.coerceAtLeast(1)
            cache.clear()
            failedAtMs.clear()
            jobsToCancel = inFlight.values.toList()
            inFlight.clear()
            updateFailedStateLocked(false)
        }
        jobsToCancel.forEach { it.cancel() }
    }

    /** Pauses launching new window fetches (e.g. while `refreshInFlight` or `!ready`). */
    fun setFetchPaused(paused: Boolean) {
        val posToResume: Long?
        synchronized(lock) {
            if (this.fetchPaused == paused) return
            this.fetchPaused = paused
            posToResume = if (!paused && !closed && dataSource != null) lastPositionMs else null
        }
        if (posToResume != null) {
            ensureWindowsFor(posToResume)
        }
    }

    fun setMergeDuplicates(enabled: Boolean) {
        synchronized(lock) {
            if (this.mergeDuplicates != enabled) {
                this.mergeDuplicates = enabled
                cache.clear()
            }
        }
    }

    /** Called on seek: invalidates & cancels all in-flight windows so stale data is discarded. */
    fun onSeek(newPositionMs: Long) {
        val pos = newPositionMs.coerceAtLeast(0L)
        val jobsToCancel: List<Job>
        val curWin = windowIndexFor(pos)
        synchronized(lock) {
            generation++
            lastPositionMs = pos
            jobsToCancel = inFlight.values.toList()
            inFlight.clear()
            if (!failedAtMs.containsKey(curWin)) {
                updateFailedStateLocked(false)
            }
        }
        jobsToCancel.forEach { it.cancel() }
        ensureWindowsFor(pos)
    }

    /** Ensures current 60 s window (and next window within 10 s lead) are cached or fetching. */
    fun ensureWindowsFor(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0L)
        val curWin = windowIndexFor(pos)
        val windowEnd = (curWin + 1L) * windowMs
        val wantNext = (windowEnd - pos) <= prefetchLeadMs
        val nowMs = clockMs()
        synchronized(lock) {
            if (closed || dataSource == null) return
            lastPositionMs = pos
            if (fetchPaused) return
            maybeRequestWindowLocked(curWin, isCurrent = true, nowMs = nowMs)
            if (wantNext) {
                maybeRequestWindowLocked(curWin + 1, isCurrent = false, nowMs = nowMs)
            }
        }
    }

    /** Retries the window covering the current playback position immediately (Palette Retry button). */
    fun retryCurrentWindow() {
        val nowMs = clockMs()
        synchronized(lock) {
            if (closed || dataSource == null) return
            val curWin = windowIndexFor(lastPositionMs)
            failedAtMs.remove(curWin)
            cache.remove(curWin)
            updateFailedStateLocked(false)
            if (!fetchPaused) {
                maybeRequestWindowLocked(curWin, isCurrent = true, nowMs = nowMs)
            }
        }
    }

    fun getCachedWindow(windowIndex: Int): List<DanmakuRawItem>? = synchronized(lock) {
        cache[windowIndex]
    }

    fun queryCachedRange(fromMs: Long, toMs: Long): List<DanmakuRawItem> {
        if (toMs <= fromMs) return emptyList()
        val startWin = windowIndexFor(fromMs.coerceAtLeast(0L))
        val endWin = windowIndexFor((toMs - 1L).coerceAtLeast(0L))
        val out = ArrayList<DanmakuRawItem>()
        synchronized(lock) {
            for (w in startWin..endWin) {
                val items = cache[w] ?: continue
                for (it in items) {
                    if (it.timeMs in fromMs until toMs) {
                        out.add(it)
                    }
                }
            }
        }
        return out
    }

    fun windowIndexFor(positionMs: Long): Int =
        (positionMs.coerceAtLeast(0L) / windowMs).toInt()

    private fun maybeRequestWindowLocked(windowIndex: Int, isCurrent: Boolean, nowMs: Long) {
        if (windowIndex < 0) return
        val cached = cache[windowIndex]
        if (cached != null) {
            if (isCurrent) updateFailedStateLocked(false)
            return
        }
        if (inFlight.containsKey(windowIndex)) return
        val prevFailedAt = failedAtMs[windowIndex]
        if (prevFailedAt != null) {
            if (nowMs - prevFailedAt < failedCooldownMs) {
                if (isCurrent) updateFailedStateLocked(true)
                return
            }
            // Cooldown elapsed: automatically retry the previously failed window
            failedAtMs.remove(windowIndex)
            if (isCurrent) updateFailedStateLocked(false)
        }
        if (inFlight.size >= maxConcurrentFetches) return
        val src = dataSource ?: return
        val gen = generation
        val doMerge = mergeDuplicates
        val fromMs = windowIndex.toLong() * windowMs
        val toMs = fromMs + windowMs

        val job = scope.launch {
            fetchWithBackoff(src, gen, windowIndex, fromMs, toMs, doMerge)
        }
        inFlight[windowIndex] = job
    }

    private suspend fun fetchWithBackoff(
        src: DanmakuDataSource,
        gen: Long,
        windowIndex: Int,
        fromMs: Long,
        toMs: Long,
        doMerge: Boolean,
    ) {
        var attempt = 0
        var busyRetries = 0
        while (attempt <= backoffScheduleMs.size) {
            if (!isGenerationActive(gen)) return
            if (isFetchPaused()) {
                // Release in-flight slot cleanly while playback/refresh is busy; resume will re-trigger
                synchronized(lock) {
                    if (generation == gen) inFlight.remove(windowIndex)
                }
                return
            }
            try {
                when (val outcome = src.fetchWindow(fromMs, toMs)) {
                    is DanmakuFetchResult.Busy -> {
                        if (!isGenerationActive(gen)) return
                        if (busyRetries < MAX_BUSY_RESCHEDULES) {
                            busyRetries++
                            if (busyRetryDelayMs > 0L) delay(busyRetryDelayMs)
                            continue
                        }
                        // Still busy after bounded reschedules: release slot without marking failed;
                        // next ensureWindowsFor tick will retry automatically.
                        synchronized(lock) {
                            if (generation == gen) inFlight.remove(windowIndex)
                        }
                        return
                    }
                    is DanmakuFetchResult.Loaded -> {
                        if (!isGenerationActive(gen)) return
                        val normalized = normalizeAndMerge(outcome.items, windowIndex, doMerge)
                        var deliver = false
                        synchronized(lock) {
                            if (!closed && generation == gen) {
                                inFlight.remove(windowIndex)
                                failedAtMs.remove(windowIndex)
                                cache[windowIndex] = normalized
                                if (windowIndex == windowIndexFor(lastPositionMs)) {
                                    updateFailedStateLocked(false)
                                }
                                deliver = true
                            }
                        }
                        if (deliver) {
                            onWindowReady(windowIndex, normalized)
                        }
                        return
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                if (attempt < backoffScheduleMs.size) {
                    val waitMs = backoffScheduleMs[attempt]
                    if (waitMs > 0L) delay(waitMs)
                }
                attempt++
            }
        }
        val failedTime = clockMs()
        synchronized(lock) {
            if (!closed && generation == gen) {
                inFlight.remove(windowIndex)
                failedAtMs[windowIndex] = failedTime
                if (windowIndex == windowIndexFor(lastPositionMs)) {
                    updateFailedStateLocked(true)
                }
            }
        }
    }

    private fun isGenerationActive(gen: Long): Boolean = synchronized(lock) {
        !closed && generation == gen
    }

    private fun isFetchPaused(): Boolean = synchronized(lock) {
        fetchPaused
    }

    private fun updateFailedStateLocked(failed: Boolean) {
        if (lastReportedFailed != failed) {
            lastReportedFailed = failed
            onLoadStateChanged(failed)
        }
    }

    private fun normalizeAndMerge(
        raw: List<DanmakuItem>,
        windowIndex: Int,
        doMerge: Boolean,
    ): List<DanmakuRawItem> {
        if (raw.isEmpty()) return emptyList()
        val cap = minOf(raw.size, maxItemsPerWindow)
        val clean = ArrayList<DanmakuRawItem>(cap)
        for (item in raw) {
            if (clean.size >= maxItemsPerWindow) break
            val text = DanmakuDepthMath.sanitizeText(item.text)
            if (text.isEmpty()) continue
            val rgb = DanmakuDepthMath.parseColorRgb(item.color)
            clean.add(
                DanmakuRawItem(
                    id = nextItemId.getAndIncrement(),
                    timeMs = item.timeMs.coerceAtLeast(0L),
                    text = text,
                    colorRgb = rgb,
                    mode = item.mode,
                    repeatCount = 1,
                    windowIndex = windowIndex,
                ),
            )
        }
        clean.sortWith(compareBy<DanmakuRawItem> { it.timeMs }.thenBy { it.id })
        return if (doMerge) mergeDuplicates(clean) else clean
    }

    override fun close() {
        val jobs: List<Job>
        synchronized(lock) {
            closed = true
            generation++
            jobs = inFlight.values.toList()
            inFlight.clear()
            cache.clear()
            failedAtMs.clear()
            dataSource = null
        }
        jobs.forEach { it.cancel() }
        scope.cancel()
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val PREFETCH_LEAD_MS = 10_000L
        const val MAX_CACHED_WINDOWS = 20
        const val MAX_CONCURRENT_PLUGIN_FETCHES = 1
        const val MAX_CONCURRENT_LOCAL_FETCHES = 2
        const val MAX_ITEMS_PER_WINDOW = 2_000
        const val MERGE_WINDOW_MS = 5_000L
        const val MERGE_MIN_COUNT = 3
        const val DEFAULT_BUSY_RETRY_DELAY_MS = 500L
        const val DEFAULT_FAILED_COOLDOWN_MS = 30_000L
        private const val MAX_BUSY_RESCHEDULES = 8
        val DEFAULT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

        @JvmStatic
        fun mergeDuplicates(
            sortedItems: List<DanmakuRawItem>,
            windowMs: Long = MERGE_WINDOW_MS,
            minCount: Int = MERGE_MIN_COUNT,
        ): List<DanmakuRawItem> {
            if (sortedItems.size < minCount) return sortedItems
            val consumed = BooleanArray(sortedItems.size)
            val out = ArrayList<DanmakuRawItem>(sortedItems.size)
            for (i in sortedItems.indices) {
                if (consumed[i]) continue
                val first = sortedItems[i]
                val matches = ArrayList<Int>(4)
                matches.add(i)
                val limitMs = first.timeMs + windowMs
                var j = i + 1
                while (j < sortedItems.size && sortedItems[j].timeMs <= limitMs) {
                    if (!consumed[j] && sortedItems[j].text == first.text
                        && sortedItems[j].mode == first.mode
                    ) {
                        matches.add(j)
                    }
                    j++
                }
                if (matches.size >= minCount) {
                    for (idx in matches) consumed[idx] = true
                    out.add(first.copy(repeatCount = matches.size))
                } else {
                    consumed[i] = true
                    out.add(first)
                }
            }
            return out
        }
    }
}
