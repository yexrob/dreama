package com.limelight.local.search

import com.limelight.local.media.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Immutable UI state for the expanded single-source view (`search.html#expanded`). */
data class ExpandedSourceState(
    val sourceId: String,
    val displayName: String,
    val typeLabel: String,
    val query: String,
    val kindFilter: SearchKindFilter,
    val items: List<Item>,
    val nextCursor: String?,
    val loadedPages: Int,
    val loadingNextPage: Boolean = false,
    val showLoadingSkeleton: Boolean = false,
    val loadingPageNumber: Int = 2,
    val pageError: Throwable? = null,
    val failedPageNumber: Int? = null,
) {
    val hasMore: Boolean get() = !nextCursor.isNullOrEmpty()
    val reachedEnd: Boolean get() = !hasMore && !loadingNextPage && pageError == null
}

/**
 * Pure-Kotlin single-source paginator for the expanded search screen (`ux-spec.md` §3.9.4):
 * - Reuses page 1 already fetched by [SearchAggregator] with zero duplicate request.
 * - Single-flight pagination appending strictly at the end, deduplicating by `item.id`,
 *   guarding against repeated cursors (cycles of any length), and auto-advancing across
 *   all-duplicate pages (up to [maxDuplicateSkipPages]) so pagination never stalls.
 * - Shows loading skeletons only when a page load exceeds [skeletonDelayMs] (300 ms).
 * - Resets `loadingNextPage` and `showLoadingSkeleton` reliably on cancellation so leaving
 *   and returning to the view never deadlocks retry/next-page triggers.
 * - Preserves already-loaded items on page error and lets the user retry that exact page.
 * - Switching [SearchKindFilter] passes `kind` to the plugin and filters locally; if the
 *   plugin ignores `kind` and leaves fewer than [minFilteredItems] items, automatically
 *   fetches up to [maxAutoFilterPages] (3) additional pages.
 */
class ExpandedSourcePaginator(
    private val scope: CoroutineScope,
    private val source: SearchableSource,
    private val query: String,
    initialKindFilter: SearchKindFilter,
    initialItems: List<Item>,
    initialNextCursor: String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sourceTimeoutMs: Long = SearchConfig.SOURCE_TIMEOUT_MS,
    private val skeletonDelayMs: Long = SearchConfig.PAGE_SKELETON_DELAY_MS,
    private val minFilteredItems: Int = SearchConfig.MIN_FILTERED_ITEMS_BEFORE_STOP,
    private val maxAutoFilterPages: Int = SearchConfig.MAX_FILTER_AUTO_PAGES,
    private val maxDuplicateSkipPages: Int = SearchConfig.MAX_DUPLICATE_SKIP_PAGES,
) {
    private val mutex = Mutex()
    private val jobLock = Any()
    private val generationSeq = AtomicLong(1L)
    private val seenIds = LinkedHashSet<String>()
    private val seenCursors = HashSet<String>()

    private var activeJob: Job? = null

    private val _state: MutableStateFlow<ExpandedSourceState>
    val state: StateFlow<ExpandedSourceState> get() = _state.asStateFlow()

    init {
        val deduped = ArrayList<Item>(initialItems.size)
        for (it in initialItems) {
            if (initialKindFilter.matches(it) && seenIds.add(it.id)) {
                deduped += it
            }
        }
        val cleanCursor = initialNextCursor?.takeIf { it.isNotEmpty() }
        _state = MutableStateFlow(
            ExpandedSourceState(
                sourceId = source.sourceId,
                displayName = source.displayName,
                typeLabel = source.typeLabel,
                query = query,
                kindFilter = initialKindFilter,
                items = deduped,
                nextCursor = cleanCursor,
                loadedPages = 1,
            ),
        )
    }

    /** Requests the next page if available and not already loading or errored. */
    fun loadNextPage() {
        val cur = _state.value
        if (cur.loadingNextPage || cur.pageError != null || cur.nextCursor.isNullOrEmpty()) return
        val gen = generationSeq.get()
        synchronized(jobLock) {
            if (activeJob?.isActive == true) return
            activeJob = launchPageFetch(
                gen = gen,
                initialCursor = cur.nextCursor,
                initialPageNum = cur.loadedPages + 1,
                filter = cur.kindFilter,
            )
        }
    }

    /** Retries the failed page (or initial page after a filter switch). */
    fun retryPage() {
        val cur = _state.value
        if (cur.loadingNextPage) return
        val failedPage = cur.failedPageNumber ?: (cur.loadedPages + 1)
        val gen = generationSeq.get()
        if (cur.loadedPages == 0) {
            switchKindFilter(cur.kindFilter, forceReload = true)
        } else {
            val cursor = cur.nextCursor ?: return
            synchronized(jobLock) {
                if (activeJob?.isActive == true) return
                activeJob = launchPageFetch(
                    gen = gen,
                    initialCursor = cursor,
                    initialPageNum = failedPage,
                    filter = cur.kindFilter,
                )
            }
        }
    }

    /**
     * Switches [SearchKindFilter]: passes `kind` to the plugin for page 1, applies local
     * fallback filtering, and if underfilled auto-fetches up to [maxAutoFilterPages] pages.
     */
    fun switchKindFilter(newFilter: SearchKindFilter, forceReload: Boolean = false) {
        if (!forceReload && _state.value.kindFilter == newFilter && _state.value.pageError == null) return
        val gen = generationSeq.incrementAndGet()
        synchronized(jobLock) {
            activeJob?.cancel()
            activeJob = launchFilterSwitch(gen = gen, newFilter = newFilter)
        }
    }

    fun cancel() {
        generationSeq.incrementAndGet()
        synchronized(jobLock) {
            activeJob?.cancel()
            activeJob = null
        }
        val cur = _state.value
        if (cur.loadingNextPage || cur.showLoadingSkeleton) {
            _state.value = cur.copy(
                loadingNextPage = false,
                showLoadingSkeleton = false,
            )
        }
    }

    private fun launchFilterSwitch(gen: Long, newFilter: SearchKindFilter): Job = scope.launch {
        mutex.withLock {
            if (generationSeq.get() != gen) return@launch
            seenIds.clear()
            seenCursors.clear()
            _state.value = _state.value.copy(
                kindFilter = newFilter,
                items = emptyList(),
                nextCursor = null,
                loadedPages = 0,
                loadingNextPage = true,
                showLoadingSkeleton = false,
                loadingPageNumber = 1,
                pageError = null,
                failedPageNumber = null,
            )
        }

        val skeletonTimer = launch {
            delay(skeletonDelayMs)
            mutex.withLock {
                if (generationSeq.get() == gen && _state.value.loadingNextPage) {
                    _state.value = _state.value.copy(showLoadingSkeleton = true)
                }
            }
        }

        try {
            var cursor: String? = null
            var pageNum = 1
            var autoExtraPages = 0
            val collected = ArrayList<Item>()

            while (true) {
                val page = withTimeoutOrNull(sourceTimeoutMs) {
                    withContext(ioDispatcher) {
                        source.search(query = query, cursor = cursor, kind = newFilter.toPluginKind())
                    }
                } ?: throw SearchPageTimeoutException(pageNum)

                var validNext: String? = null
                var shouldContinue = false
                mutex.withLock {
                    if (generationSeq.get() != gen) return@launch
                    val filtered = ArrayList<Item>()
                    for (item in page.items) {
                        if (newFilter.matches(item) && seenIds.add(item.id)) {
                            filtered += item
                        }
                    }
                    collected.addAll(filtered)

                    val rawNext = page.nextCursor?.takeIf { it.isNotEmpty() }
                    validNext = if (rawNext != null && rawNext != cursor && seenCursors.add(rawNext)) {
                        rawNext
                    } else {
                        null
                    }

                    val pluginIgnoredKind = page.items.any { !newFilter.matches(it) }
                    val needMore = newFilter != SearchKindFilter.ALL &&
                        (pluginIgnoredKind || collected.isEmpty()) &&
                        collected.size < minFilteredItems &&
                        validNext != null &&
                        autoExtraPages < maxAutoFilterPages

                    if (!needMore) {
                        _state.value = _state.value.copy(
                            items = collected.toList(),
                            nextCursor = validNext,
                            loadedPages = pageNum,
                            loadingNextPage = false,
                            showLoadingSkeleton = false,
                            pageError = null,
                            failedPageNumber = null,
                        )
                    } else {
                        shouldContinue = true
                        _state.value = _state.value.copy(
                            items = collected.toList(),
                            nextCursor = validNext,
                            loadedPages = pageNum,
                            loadingPageNumber = pageNum + 1,
                        )
                    }
                }

                if (!shouldContinue) break
                cursor = validNext
                pageNum++
                autoExtraPages++
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generationSeq.get() == gen) {
                        val cur = _state.value
                        if (cur.loadingNextPage || cur.showLoadingSkeleton) {
                            _state.value = cur.copy(
                                loadingNextPage = false,
                                showLoadingSkeleton = false,
                            )
                        }
                    }
                }
            }
            throw ce
        } catch (t: Throwable) {
            mutex.withLock {
                if (generationSeq.get() != gen) return@withLock
                val cur = _state.value
                _state.value = cur.copy(
                    loadingNextPage = false,
                    showLoadingSkeleton = false,
                    pageError = t,
                    failedPageNumber = cur.loadingPageNumber,
                )
            }
        } finally {
            skeletonTimer.cancel()
        }
    }

    private fun launchPageFetch(
        gen: Long,
        initialCursor: String,
        initialPageNum: Int,
        filter: SearchKindFilter,
    ): Job = scope.launch {
        mutex.withLock {
            val cur = _state.value
            if (generationSeq.get() != gen || cur.loadingNextPage) return@launch
            _state.value = cur.copy(
                loadingNextPage = true,
                showLoadingSkeleton = false,
                loadingPageNumber = initialPageNum,
                pageError = null,
                failedPageNumber = null,
            )
        }

        val skeletonTimer = launch {
            delay(skeletonDelayMs)
            mutex.withLock {
                if (generationSeq.get() == gen && _state.value.loadingNextPage) {
                    _state.value = _state.value.copy(showLoadingSkeleton = true)
                }
            }
        }

        var currentCursor = initialCursor
        var currentPageNum = initialPageNum
        var skippedDuplicatePages = 0

        try {
            while (true) {
                val page = withTimeoutOrNull(sourceTimeoutMs) {
                    withContext(ioDispatcher) {
                        source.search(query = query, cursor = currentCursor, kind = filter.toPluginKind())
                    }
                } ?: throw SearchPageTimeoutException(currentPageNum)

                var continueSkipping = false
                var nextCursorToFetch: String? = null

                mutex.withLock {
                    if (generationSeq.get() != gen) return@launch
                    seenCursors.add(currentCursor)
                    val cur = _state.value
                    val appended = ArrayList<Item>()
                    for (item in page.items) {
                        if (filter.matches(item) && seenIds.add(item.id)) {
                            appended += item
                        }
                    }
                    val rawNext = page.nextCursor?.takeIf { it.isNotEmpty() }
                    val validNext = if (rawNext != null && rawNext != currentCursor && rawNext !in seenCursors) {
                        rawNext
                    } else {
                        null
                    }

                    // If a page yielded zero new items after deduplication/filtering and a fresh
                    // cursor remains, advance automatically (bounded) so UI scroll pagination does
                    // not stall with an unchanged items.size.
                    if (appended.isEmpty() && validNext != null && skippedDuplicatePages < maxDuplicateSkipPages) {
                        continueSkipping = true
                        nextCursorToFetch = validNext
                        _state.value = cur.copy(
                            nextCursor = validNext,
                            loadedPages = currentPageNum,
                            loadingPageNumber = currentPageNum + 1,
                        )
                    } else {
                        _state.value = cur.copy(
                            items = cur.items + appended,
                            nextCursor = validNext,
                            loadedPages = currentPageNum,
                            loadingNextPage = false,
                            showLoadingSkeleton = false,
                            pageError = null,
                            failedPageNumber = null,
                        )
                    }
                }

                val nextCursor = nextCursorToFetch
                if (!continueSkipping || nextCursor == null) break
                currentCursor = nextCursor
                currentPageNum++
                skippedDuplicatePages++
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generationSeq.get() == gen) {
                        val cur = _state.value
                        if (cur.loadingNextPage || cur.showLoadingSkeleton) {
                            _state.value = cur.copy(
                                loadingNextPage = false,
                                showLoadingSkeleton = false,
                            )
                        }
                    }
                }
            }
            throw ce
        } catch (t: Throwable) {
            mutex.withLock {
                if (generationSeq.get() != gen) return@withLock
                val cur = _state.value
                _state.value = cur.copy(
                    nextCursor = currentCursor,
                    loadingNextPage = false,
                    showLoadingSkeleton = false,
                    pageError = t,
                    failedPageNumber = currentPageNum,
                )
            }
        } finally {
            skeletonTimer.cancel()
        }
    }
}

class SearchPageTimeoutException(val pageNumber: Int) : Exception("Page $pageNumber timed out")
