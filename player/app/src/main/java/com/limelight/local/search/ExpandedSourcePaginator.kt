package com.limelight.local.search

import com.limelight.local.media.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 *   and guarding against repeated cursors so buggy plugins cannot loop infinitely.
 * - Shows loading skeletons only when a page load exceeds [skeletonDelayMs] (300 ms).
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
) {
    private val mutex = Mutex()
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
        launchPageFetch(gen = gen, cursor = cur.nextCursor, pageNum = cur.loadedPages + 1, filter = cur.kindFilter, replaceAll = false)
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
            launchPageFetch(gen = gen, cursor = cursor, pageNum = failedPage, filter = cur.kindFilter, replaceAll = false)
        }
    }

    /**
     * Switches [SearchKindFilter]: passes `kind` to the plugin for page 1, applies local
     * fallback filtering, and if underfilled auto-fetches up to [maxAutoFilterPages] pages.
     */
    fun switchKindFilter(newFilter: SearchKindFilter, forceReload: Boolean = false) {
        if (!forceReload && _state.value.kindFilter == newFilter && _state.value.pageError == null) return
        val gen = generationSeq.incrementAndGet()
        activeJob?.cancel()
        activeJob = scope.launch {
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

                    val filtered = ArrayList<Item>()
                    for (item in page.items) {
                        if (newFilter.matches(item) && seenIds.add(item.id)) {
                            filtered += item
                        }
                    }
                    collected.addAll(filtered)

                    val rawNext = page.nextCursor?.takeIf { it.isNotEmpty() }
                    val validNext = if (rawNext != null && rawNext != cursor && seenCursors.add(rawNext)) {
                        rawNext
                    } else {
                        null
                    }

                    val pluginIgnoredKind = page.items.any { !newFilter.matches(it) }
                    val needMore = pluginIgnoredKind &&
                        newFilter != SearchKindFilter.ALL &&
                        collected.size < minFilteredItems &&
                        validNext != null &&
                        autoExtraPages < maxAutoFilterPages

                    if (!needMore) {
                        mutex.withLock {
                            if (generationSeq.get() != gen) return@withLock
                            _state.value = _state.value.copy(
                                items = collected.toList(),
                                nextCursor = validNext,
                                loadedPages = pageNum,
                                loadingNextPage = false,
                                showLoadingSkeleton = false,
                                pageError = null,
                                failedPageNumber = null,
                            )
                        }
                        break
                    }

                    cursor = validNext
                    pageNum++
                    autoExtraPages++
                    mutex.withLock {
                        if (generationSeq.get() != gen) return@withLock
                        _state.value = _state.value.copy(
                            items = collected.toList(),
                            nextCursor = validNext,
                            loadedPages = pageNum - 1,
                            loadingPageNumber = pageNum,
                        )
                    }
                }
            } catch (ce: CancellationException) {
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
    }

    fun cancel() {
        generationSeq.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
    }

    private fun launchPageFetch(
        gen: Long,
        cursor: String,
        pageNum: Int,
        filter: SearchKindFilter,
        replaceAll: Boolean,
    ) {
        activeJob = scope.launch {
            mutex.withLock {
                val cur = _state.value
                if (generationSeq.get() != gen || cur.loadingNextPage) return@launch
                _state.value = cur.copy(
                    loadingNextPage = true,
                    showLoadingSkeleton = false,
                    loadingPageNumber = pageNum,
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
                val page = withTimeoutOrNull(sourceTimeoutMs) {
                    withContext(ioDispatcher) {
                        source.search(query = query, cursor = cursor, kind = filter.toPluginKind())
                    }
                } ?: throw SearchPageTimeoutException(pageNum)

                mutex.withLock {
                    if (generationSeq.get() != gen) return@withLock
                    seenCursors.add(cursor)
                    val cur = _state.value
                    val appended = ArrayList<Item>()
                    for (item in page.items) {
                        if (filter.matches(item) && seenIds.add(item.id)) {
                            appended += item
                        }
                    }
                    val rawNext = page.nextCursor?.takeIf { it.isNotEmpty() }
                    val validNext = if (rawNext != null && rawNext != cursor && rawNext !in seenCursors) {
                        rawNext
                    } else {
                        null
                    }
                    _state.value = cur.copy(
                        items = if (replaceAll) appended else cur.items + appended,
                        nextCursor = validNext,
                        loadedPages = pageNum,
                        loadingNextPage = false,
                        showLoadingSkeleton = false,
                        pageError = null,
                        failedPageNumber = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                mutex.withLock {
                    if (generationSeq.get() != gen) return@withLock
                    val cur = _state.value
                    _state.value = cur.copy(
                        loadingNextPage = false,
                        showLoadingSkeleton = false,
                        pageError = t,
                        failedPageNumber = pageNum,
                    )
                }
            } finally {
                skeletonTimer.cancel()
            }
        }
    }
}

class SearchPageTimeoutException(val pageNumber: Int) : Exception("Page $pageNumber timed out")
