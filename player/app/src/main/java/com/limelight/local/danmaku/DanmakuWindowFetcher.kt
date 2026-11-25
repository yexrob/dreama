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

/** Abstract source of segmented danmaku windows (plugin capability OR local/WebDAV XML file). */
fun interface DanmakuDataSource {
    suspend fun fetchWindow(fromMs: Long, toMs: Long): List<DanmakuItem>
}

/**
 * 60-second segmented danmaku window fetcher with 10-second lookahead prefetch, bounded LRU cache,
 * strict seek cancellation, exponential retry backoff, and §6.10 duplicate merging.
 *
 * All concurrency limits & timing rules are injectable for fast, deterministic JVM unit tests.
 */
class DanmakuWindowFetcher(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val windowMs: Long = WINDOW_MS,
    private val prefetchLeadMs: Long = PREFETCH_LEAD_MS,
    private val maxCachedWindows: Int = MAX_CACHED_WINDOWS,
    private val maxConcurrentFetches: Int = MAX_CONCURRENT_FETCHES,
    private val backoffScheduleMs: LongArray = DEFAULT_BACKOFF_MS,
    private val onWindowReady: (windowIndex: Int, items: List<DanmakuRawItem>) -> Unit = { _, _ -> },
    private val onLoadStateChanged: (failed: Boolean) -> Unit = {},
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val nextItemId = AtomicLong(1L)

    // Access-ordered LRU cache keyed by windowIndex (0 = [0, 60_000), 1 = [60_000, 120_000), ...)
    private val cache = object : LinkedHashMap<Int, List<DanmakuRawItem>>(maxCachedWindows + 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<DanmakuRawItem>>?): Boolean =
            size > maxCachedWindows
    }
    private val inFlight = HashMap<Int, Job>()
    private val failedWindows = HashSet<Int>()

    private var dataSource: DanmakuDataSource? = null
    private var generation: Long = 0L
    private var lastPositionMs: Long = 0L
    private var mergeDuplicates: Boolean = true
    private var lastReportedFailed: Boolean = false
    private var closed: Boolean = false

    val isLoadFailed: Boolean
        get() = synchronized(lock) { lastReportedFailed }

    fun setDataSource(source: DanmakuDataSource?, mergeDuplicates: Boolean) {
        val jobsToCancel: List<Job>
        synchronized(lock) {
            generation++
            this.dataSource = source
            this.mergeDuplicates = mergeDuplicates
            cache.clear()
            failedWindows.clear()
            jobsToCancel = inFlight.values.toList()
            inFlight.clear()
            updateFailedStateLocked(false)
        }
        jobsToCancel.forEach { it.cancel() }
    }

    fun setMergeDuplicates(enabled: Boolean) {
        synchronized(lock) {
            if (this.mergeDuplicates != enabled) {
                this.mergeDuplicates = enabled
                // Clear cache so next windows re-merge cleanly with new setting
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
            // Clear failed flag if the target window is cached or not yet tried
            if (!failedWindows.contains(curWin)) {
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
        synchronized(lock) {
            if (closed || dataSource == null) return
            lastPositionMs = pos
            maybeRequestWindowLocked(curWin, isCurrent = true)
            if (wantNext) {
                maybeRequestWindowLocked(curWin + 1, isCurrent = false)
            }
        }
    }

    /** Retries the window covering the current playback position (wired to Palette "重试" button). */
    fun retryCurrentWindow() {
        val pos: Long
        synchronized(lock) {
            if (closed || dataSource == null) return
            pos = lastPositionMs
            val curWin = windowIndexFor(pos)
            failedWindows.remove(curWin)
            cache.remove(curWin)
            updateFailedStateLocked(false)
            maybeRequestWindowLocked(curWin, isCurrent = true)
        }
    }

    /** Returns cached items for the given window index, or null if not yet loaded. */
    fun getCachedWindow(windowIndex: Int): List<DanmakuRawItem>? = synchronized(lock) {
        cache[windowIndex]
    }

    /** Returns all cached items in `[fromMs, toMs)` ordered by timeMs. */
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

    private fun maybeRequestWindowLocked(windowIndex: Int, isCurrent: Boolean) {
        if (windowIndex < 0) return
        val cached = cache[windowIndex]
        if (cached != null) {
            if (isCurrent) updateFailedStateLocked(false)
            return
        }
        if (inFlight.containsKey(windowIndex)) return
        if (failedWindows.contains(windowIndex)) {
            if (isCurrent) updateFailedStateLocked(true)
            return
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
        var lastError: Throwable? = null
        while (attempt <= backoffScheduleMs.size) {
            if (!isGenerationActive(gen)) return
            try {
                val rawList = src.fetchWindow(fromMs, toMs)
                if (!isGenerationActive(gen)) return
                val normalized = normalizeAndMerge(rawList, doMerge)
                var deliver = false
                synchronized(lock) {
                    if (!closed && generation == gen) {
                        inFlight.remove(windowIndex)
                        failedWindows.remove(windowIndex)
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                lastError = e
                if (attempt < backoffScheduleMs.size) {
                    val waitMs = backoffScheduleMs[attempt]
                    if (waitMs > 0L) delay(waitMs)
                }
                attempt++
            }
        }
        // Exhausted retries: never crash or disrupt video playback; mark window failed for Palette Retry UI
        @Suppress("UNUSED_VARIABLE")
        val ignored = lastError
        synchronized(lock) {
            if (!closed && generation == gen) {
                inFlight.remove(windowIndex)
                failedWindows.add(windowIndex)
                if (windowIndex == windowIndexFor(lastPositionMs)) {
                    updateFailedStateLocked(true)
                }
            }
        }
    }

    private fun isGenerationActive(gen: Long): Boolean = synchronized(lock) {
        !closed && generation == gen
    }

    private fun updateFailedStateLocked(failed: Boolean) {
        if (lastReportedFailed != failed) {
            lastReportedFailed = failed
            onLoadStateChanged(failed)
        }
    }

    private fun normalizeAndMerge(raw: List<DanmakuItem>, doMerge: Boolean): List<DanmakuRawItem> {
        if (raw.isEmpty()) return emptyList()
        val clean = ArrayList<DanmakuRawItem>(raw.size)
        for (item in raw) {
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
            dataSource = null
        }
        jobs.forEach { it.cancel() }
        scope.cancel()
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val PREFETCH_LEAD_MS = 10_000L
        const val MAX_CACHED_WINDOWS = 20
        const val MAX_CONCURRENT_FETCHES = 2
        const val MERGE_WINDOW_MS = 5_000L
        const val MERGE_MIN_COUNT = 3
        val DEFAULT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

        /**
         * Pure §6.10 duplicate merger: within each batch, if the same sanitized text appears
         * `>= 3` times within `5,000 ms` of the first occurrence, collapses all of those
         * occurrences into one item at the earliest timestamp carrying `repeatCount = N`
         * (`"哈哈哈 ×12"`). Decided once per batch and never mutated while on screen.
         */
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
