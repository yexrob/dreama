package com.limelight.local.search

import com.limelight.local.media.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/**
 * Pure-Kotlin cross-source search aggregator (`docs/specs/08-search.md`, `ux-spec.md` §3.9.4).
 *
 * Core invariants enforced & locked by unit tests:
 * 1. **Generation isolation**: starting a new query cancels all in-flight jobs of the
 *    previous generation; any late reply carrying an older generation is discarded.
 * 2. **Row-order stability**: sources finishing within [initialBatchWindowMs] (default 300 ms)
 *    land together sorted by configured source order (result rows before status rows);
 *    every source finishing afterward is strictly appended at the end — already-visible
 *    rows NEVER reorder or shift upward.
 * 3. **Unconstrained concurrent queries with strict per-source wall timeout**: all searchable
 *    sources run concurrently under independent `withTimeoutOrNull(sourceTimeoutMs)` (default 10 s);
 *    one source failing, timing out, or needing auth never blocks or delays any other source.
 * 4. **In-place retry / post-login refresh**: retrying a failed/needs-auth row updates that
 *    exact row index in place — even when retry yields zero items, the row remains in place
 *    showing 「没找到」 so rows below NEVER jump upward.
 * 5. **Local kind fallback, auto-paging & item deduplication**: passes `kind` to plugins AND
 *    filters locally with [SearchKindFilter.matches]. If page 1 is filtered empty or underfilled
 *    while `nextCursor != null`, automatically fetches up to [maxAutoFilterPages] (3) additional
 *    pages within the same 10 s per-source budget before declaring `Empty`.
 * 6. **3 s pending tail delay**: 「还在搜：…」 only becomes visible once a source remains pending
 *    beyond [pendingTailDelayMs] (default 3 000 ms).
 * 7. **5-minute synchronous in-memory cache**: re-running `(normalizedQuery, kindFilter, sourceId)`
 *    within [cacheTtlMs] reuses cached results with zero network wait; [invalidateSourceCache] and
 *    [clearCache] operate synchronously under [cacheLock].
 */
class SearchAggregator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val initialBatchWindowMs: Long = SearchConfig.INITIAL_BATCH_MS,
    private val sourceTimeoutMs: Long = SearchConfig.SOURCE_TIMEOUT_MS,
    private val pendingTailDelayMs: Long = SearchConfig.PENDING_TAIL_DELAY_MS,
    private val cacheTtlMs: Long = SearchConfig.CACHE_TTL_MS,
    private val maxCacheEntries: Int = SearchConfig.MAX_CACHE_ENTRIES,
    private val suggestTimeoutMs: Long = SearchConfig.SUGGEST_TIMEOUT_MS,
    private val minFilteredItems: Int = SearchConfig.MIN_FILTERED_ITEMS_BEFORE_STOP,
    private val maxAutoFilterPages: Int = SearchConfig.MAX_FILTER_AUTO_PAGES,
) {
    private data class CacheKey(
        val query: String,
        val kindFilter: SearchKindFilter,
        val sourceId: String,
    )

    private data class CacheEntry(
        val status: SearchRowStatus,
        val availableKinds: Set<SearchKindFilter>,
        val storedAtMs: Long,
    )

    private val _state = MutableStateFlow(AggregatedSearchState())
    val state: StateFlow<AggregatedSearchState> = _state.asStateFlow()

    private val generationSeq = AtomicLong(0L)
    private val stateMutex = Mutex()
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean =
            size > maxCacheEntries
    }

    private var activeSessionJob: Job? = null
    private var activeSessionScope: CoroutineScope? = null
    private val activeRetryJobs = LinkedHashMap<String, Job>()
    private var activeFallbackSuggestJob: Job? = null
    private var activeSourcesById: Map<String, SearchableSource> = emptyMap()
    private var activeSourceOrder: Map<String, Int> = emptyMap()
    private var activeQueryNormalized: String = ""
    private var activeKindFilter: SearchKindFilter = SearchKindFilter.ALL
    private var activeNonSearchableNames: List<String> = emptyList()
    private var activeAllSearchableNames: List<String> = emptyList()
    private var activeVisibleRows: MutableList<SearchSourceRow> = ArrayList()
    private var activePendingIds: LinkedHashSet<String> = LinkedHashSet()
    private var activeEmptyIds: LinkedHashSet<String> = LinkedHashSet()
    private var activeBatchBuffer: LinkedHashMap<String, SearchRowStatus> = LinkedHashMap()
    private var activeInitialBatchSettled: Boolean = false
    private var activeShowInitialSkeleton: Boolean = false
    private var activeShowPendingTail: Boolean = false
    private var activeAvailableKinds: LinkedHashSet<SearchKindFilter> = linkedSetOf(SearchKindFilter.ALL)
    private var activeFallbackSuggestions: List<String> = emptyList()
    private var lastKnownKindsByQuery: Pair<String, Set<SearchKindFilter>>? = null

    /**
     * Starts a new aggregated search across [sources]. Cancels any previous in-flight session.
     * Returns the assigned generation number, or `0L` if [rawQuery] normalizes to empty.
     */
    fun search(
        rawQuery: String,
        kindFilter: SearchKindFilter = SearchKindFilter.ALL,
        sources: List<SearchableSource>,
        nonSearchableNames: List<String> = emptyList(),
        bypassCache: Boolean = false,
    ): Long {
        val normalized = SearchQueryNormalizer.normalize(rawQuery)
        if (normalized.isEmpty()) return 0L

        val gen = generationSeq.incrementAndGet()
        activeSessionJob?.cancel()
        activeFallbackSuggestJob?.cancel()
        activeRetryJobs.values.forEach { it.cancel() }
        activeRetryJobs.clear()

        // Emit an immediate in-progress snapshot for [gen] synchronously so UI & callers
        // never observe the previous generation's settled state between launch and runSession.
        _state.value = AggregatedSearchState(
            generation = gen,
            query = normalized,
            kindFilter = kindFilter,
            totalSearchableSources = sources.size,
            allSearchableSourceNames = sources.map { it.displayName },
            nonSearchableSourceNames = nonSearchableNames.toList(),
            pendingSourceNames = sources.map { it.displayName },
            availableKinds = LinkedHashSet<SearchKindFilter>().apply {
                add(SearchKindFilter.ALL)
                add(kindFilter)
                lastKnownKindsByQuery?.takeIf { it.first == normalized }?.let { addAll(it.second) }
            },
        )

        val sessionJob = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
        activeSessionJob = sessionJob
        activeSessionScope = sessionScope

        sessionScope.launch {
            runSession(
                gen = gen,
                query = normalized,
                kindFilter = kindFilter,
                sources = sources,
                nonSearchableNames = nonSearchableNames,
                bypassCache = bypassCache,
                sessionScope = sessionScope,
            )
        }
        return gen
    }

    /**
     * Retries a single source IN PLACE (after user taps 「重试」 or returns from 「登录」).
     * Never moves rows above or below it; even if retry yields zero items, keeps that exact
     * row index showing `SearchRowStatus.Empty` so lower rows never jump upward.
     */
    fun retrySource(
        sourceId: String,
        updatedSource: SearchableSource? = null,
    ) {
        val gen = generationSeq.get()
        if (gen == 0L) return
        val sessionJob = activeSessionJob ?: return
        val sessionScope = activeSessionScope ?: return
        if (!sessionJob.isActive) return

        // Replace any previous in-flight retry for the same source without allocating a leaked SupervisorJob
        activeRetryJobs.remove(sourceId)?.cancel()

        val retryJob = sessionScope.launch {
            try {
                val prep = stateMutex.withLock {
                    if (generationSeq.get() != gen) return@withLock null
                    val resolved = updatedSource ?: activeSourcesById[sourceId] ?: return@withLock null
                    if (updatedSource != null) {
                        activeSourcesById = activeSourcesById + (sourceId to updatedSource)
                    }
                    val idx = activeVisibleRows.indexOfFirst { it.sourceId == sourceId }
                    if (idx >= 0) {
                        activeVisibleRows[idx] = activeVisibleRows[idx].copy(
                            displayName = resolved.displayName,
                            typeLabel = resolved.typeLabel,
                            mediaSource = resolved.mediaSource,
                            refreshingInPlace = true,
                        )
                    } else {
                        activeEmptyIds.remove(sourceId)
                        activePendingIds.add(sourceId)
                    }
                    emitStateLocked(gen)
                    Triple(resolved, activeQueryNormalized, activeKindFilter)
                } ?: return@launch
                val (targetSrc, query, filter) = prep

                synchronized(cacheLock) {
                    cache.remove(CacheKey(query, filter, sourceId))
                }

                val (outcome, discoveredKinds) = executeSingleSource(
                    source = targetSrc,
                    query = query,
                    kindFilter = filter,
                    respectKnownNeedsAuth = false,
                )

                var shouldFetchFallback = false
                stateMutex.withLock {
                    if (generationSeq.get() != gen) return@withLock
                    activePendingIds.remove(sourceId)
                    activeAvailableKinds.addAll(discoveredKinds)
                    lastKnownKindsByQuery = query to LinkedHashSet(activeAvailableKinds)

                    if (outcome is SearchRowStatus.Results || outcome is SearchRowStatus.Empty) {
                        synchronized(cacheLock) {
                            cache[CacheKey(query, filter, sourceId)] = CacheEntry(
                                status = outcome,
                                availableKinds = discoveredKinds,
                                storedAtMs = clockMs(),
                            )
                        }
                    }

                    val idx = activeVisibleRows.indexOfFirst { it.sourceId == sourceId }
                    if (idx >= 0) {
                        // Preserve exact row slot even on Empty so rows beneath never shift upward
                        activeEmptyIds.remove(sourceId)
                        activeVisibleRows[idx] = SearchSourceRow(
                            sourceId = targetSrc.sourceId,
                            displayName = targetSrc.displayName,
                            typeLabel = targetSrc.typeLabel,
                            sourceKind = targetSrc.sourceKind,
                            mediaSource = targetSrc.mediaSource,
                            status = outcome,
                            refreshingInPlace = false,
                            arrivedAfterBatch = activeVisibleRows[idx].arrivedAfterBatch,
                        )
                    } else if (outcome is SearchRowStatus.Empty) {
                        activeEmptyIds.add(sourceId)
                    } else {
                        activeVisibleRows.add(
                            SearchSourceRow(
                                sourceId = targetSrc.sourceId,
                                displayName = targetSrc.displayName,
                                typeLabel = targetSrc.typeLabel,
                                sourceKind = targetSrc.sourceKind,
                                mediaSource = targetSrc.mediaSource,
                                status = outcome,
                                refreshingInPlace = false,
                                arrivedAfterBatch = true,
                            ),
                        )
                    }
                    emitStateLocked(gen)
                    shouldFetchFallback = _state.value.isTrueNoResult
                }

                if (shouldFetchFallback) {
                    launchNoResultSuggestions(gen, query)
                }
            } finally {
                val current = activeRetryJobs[sourceId]
                if (current == coroutineContext[Job]) {
                    activeRetryJobs.remove(sourceId)
                }
            }
        }
        activeRetryJobs[sourceId] = retryJob
    }

    /** Cancels all active search work and resets state back to idle. */
    fun resetToIdle() {
        generationSeq.incrementAndGet()
        activeSessionJob?.cancel()
        activeSessionJob = null
        activeSessionScope = null
        activeRetryJobs.clear()
        activeFallbackSuggestJob?.cancel()
        activeFallbackSuggestJob = null
        _state.value = AggregatedSearchState()
    }

    /** Cancels in-flight requests without clearing the in-memory 5-minute result cache. */
    fun cancelActive() {
        generationSeq.incrementAndGet()
        activeSessionJob?.cancel()
        activeSessionJob = null
        activeSessionScope = null
        activeRetryJobs.clear()
        activeFallbackSuggestJob?.cancel()
        activeFallbackSuggestJob = null
    }

    /** Synchronously clears the 5-minute in-memory cache (e.g., when a plugin is reloaded or deleted). */
    fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    /** Synchronously drops cached entries for one source (e.g., after re-authentication). */
    fun invalidateSourceCache(sourceId: String) {
        synchronized(cacheLock) {
            val iter = cache.keys.iterator()
            while (iter.hasNext()) {
                if (iter.next().sourceId == sourceId) iter.remove()
            }
        }
    }

    private suspend fun runSession(
        gen: Long,
        query: String,
        kindFilter: SearchKindFilter,
        sources: List<SearchableSource>,
        nonSearchableNames: List<String>,
        bypassCache: Boolean,
        sessionScope: CoroutineScope,
    ) {
        val now = clockMs()
        val cachedOutcomes = LinkedHashMap<String, CacheEntry>()
        if (!bypassCache) {
            synchronized(cacheLock) {
                for (src in sources) {
                    if (src.knownNeedsAuth) continue
                    val key = CacheKey(query, kindFilter, src.sourceId)
                    val hit = cache[key]
                    if (hit != null && now - hit.storedAtMs < cacheTtlMs) {
                        cachedOutcomes[src.sourceId] = hit
                    } else if (hit != null) {
                        cache.remove(key)
                    }
                }
            }
        }

        val toQuery = ArrayList<SearchableSource>()
        var allAlreadySettled = false

        stateMutex.withLock {
            if (generationSeq.get() != gen) return
            activeSourcesById = sources.associateBy { it.sourceId }
            activeSourceOrder = sources.withIndex().associate { (i, s) -> s.sourceId to i }
            activeQueryNormalized = query
            activeKindFilter = kindFilter
            activeNonSearchableNames = nonSearchableNames.toList()
            activeAllSearchableNames = sources.map { it.displayName }
            activeVisibleRows = ArrayList()
            activePendingIds = LinkedHashSet()
            activeEmptyIds = LinkedHashSet()
            activeBatchBuffer = LinkedHashMap()
            activeInitialBatchSettled = false
            activeShowInitialSkeleton = false
            activeShowPendingTail = false
            activeFallbackSuggestions = emptyList()

            // Preserve discovered available kinds across kind-filter switches on the same query
            // so switching from ALL -> MOVIE never disables SERIES/VIDEO tabs.
            activeAvailableKinds = LinkedHashSet<SearchKindFilter>().apply {
                add(SearchKindFilter.ALL)
                add(kindFilter)
                lastKnownKindsByQuery?.takeIf { it.first == query }?.let { addAll(it.second) }
            }

            for (src in sources) {
                when {
                    src.knownNeedsAuth -> {
                        activeBatchBuffer[src.sourceId] = SearchRowStatus.NeedsAuth()
                    }
                    cachedOutcomes.containsKey(src.sourceId) -> {
                        val hit = cachedOutcomes.getValue(src.sourceId)
                        activeBatchBuffer[src.sourceId] = hit.status
                        activeAvailableKinds.addAll(hit.availableKinds)
                    }
                    else -> {
                        activePendingIds.add(src.sourceId)
                        toQuery += src
                    }
                }
            }

            if (activePendingIds.isEmpty()) {
                flushInitialBatchLocked()
                allAlreadySettled = true
            }
            lastKnownKindsByQuery = query to LinkedHashSet(activeAvailableKinds)
            emitStateLocked(gen)
        }

        if (allAlreadySettled) {
            if (_state.value.isTrueNoResult) {
                launchNoResultSuggestions(gen, query)
            }
            return
        }

        // Launch the 300 ms initial batch deadline timer
        sessionScope.launch {
            delay(initialBatchWindowMs)
            var shouldFetchFallback = false
            stateMutex.withLock {
                if (generationSeq.get() != gen || activeInitialBatchSettled) return@withLock
                flushInitialBatchLocked()
                emitStateLocked(gen)
                shouldFetchFallback = _state.value.isTrueNoResult
            }
            if (shouldFetchFallback) {
                launchNoResultSuggestions(gen, query)
            }
        }

        // Launch the 3 s 「还在搜」 tail line reveal timer (§3.9.4)
        sessionScope.launch {
            delay(pendingTailDelayMs)
            stateMutex.withLock {
                if (generationSeq.get() != gen || activePendingIds.isEmpty()) return@withLock
                activeShowPendingTail = true
                emitStateLocked(gen)
            }
        }

        // Launch independent per-source concurrent search tasks (all sources simultaneously)
        for (src in toQuery) {
            sessionScope.launch {
                val (status, discoveredKinds) = executeSingleSource(
                    source = src,
                    query = query,
                    kindFilter = kindFilter,
                    respectKnownNeedsAuth = true,
                )

                if (status is SearchRowStatus.Results || status is SearchRowStatus.Empty) {
                    synchronized(cacheLock) {
                        cache[CacheKey(query, kindFilter, src.sourceId)] = CacheEntry(
                            status = status,
                            availableKinds = discoveredKinds,
                            storedAtMs = clockMs(),
                        )
                    }
                }

                var shouldFetchFallback = false
                stateMutex.withLock {
                    if (generationSeq.get() != gen) return@withLock
                    if (!activePendingIds.remove(src.sourceId)) return@withLock

                    activeAvailableKinds.addAll(discoveredKinds)
                    lastKnownKindsByQuery = query to LinkedHashSet(activeAvailableKinds)

                    if (!activeInitialBatchSettled) {
                        activeBatchBuffer[src.sourceId] = status
                        if (activePendingIds.isEmpty()) {
                            flushInitialBatchLocked()
                        }
                    } else {
                        appendPostBatchOutcomeLocked(src, status)
                    }
                    emitStateLocked(gen)
                    shouldFetchFallback = _state.value.isTrueNoResult
                }

                if (shouldFetchFallback) {
                    launchNoResultSuggestions(gen, query)
                }
            }
        }
    }

    private suspend fun executeSingleSource(
        source: SearchableSource,
        query: String,
        kindFilter: SearchKindFilter,
        respectKnownNeedsAuth: Boolean,
    ): Pair<SearchRowStatus, Set<SearchKindFilter>> {
        if (respectKnownNeedsAuth && source.knownNeedsAuth) {
            return SearchRowStatus.NeedsAuth() to emptySet()
        }
        return try {
            val outcome = withTimeoutOrNull(sourceTimeoutMs) {
                val seenIds = LinkedHashSet<String>()
                val seenCursors = HashSet<String>()
                val discoveredKinds = LinkedHashSet<SearchKindFilter>()
                val collected = ArrayList<Item>()
                var cursor: String? = null
                var autoExtraPages = 0
                var finalNextCursor: String? = null

                while (true) {
                    val page = withContext(ioDispatcher) {
                        source.search(query = query, cursor = cursor, kind = kindFilter.toPluginKind())
                    }
                    val pageDeduped = deduplicateItems(page.items)
                    for (item in pageDeduped) {
                        discoveredKinds.addAll(SearchKindFilter.filtersMatching(item))
                        if (kindFilter.matches(item) && seenIds.add(item.id)) {
                            collected += item
                        }
                    }

                    val rawNext = page.nextCursor?.takeIf { it.isNotEmpty() }
                    val validNext = if (rawNext != null && rawNext != cursor && seenCursors.add(rawNext)) {
                        rawNext
                    } else {
                        null
                    }
                    finalNextCursor = validNext

                    val pluginIgnoredKind = pageDeduped.any { !kindFilter.matches(it) }
                    val needMore = kindFilter != SearchKindFilter.ALL &&
                        (pluginIgnoredKind || collected.isEmpty()) &&
                        collected.size < minFilteredItems &&
                        validNext != null &&
                        autoExtraPages < maxAutoFilterPages

                    if (!needMore) break
                    cursor = validNext
                    autoExtraPages++
                }

                val status = if (collected.isEmpty()) {
                    SearchRowStatus.Empty
                } else {
                    SearchRowStatus.Results(
                        items = collected.toList(),
                        nextCursor = finalNextCursor,
                        hasMore = finalNextCursor != null || collected.size > SearchConfig.ROW_PREVIEW_LIMIT,
                    )
                }
                status to discoveredKinds.toSet()
            }
            if (outcome == null) {
                val sec = ((sourceTimeoutMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                SearchRowStatus.TimedOut(sec) to emptySet()
            } else {
                outcome
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            SearchErrorClassifier.classifyFailure(
                error = t,
                sourceKind = source.sourceKind,
                sourceName = source.displayName,
                nowMs = clockMs(),
            ) to emptySet()
        }
    }

    /**
     * Flushes all responses collected within the initial batch window in configured
     * source order, placing result rows before one-line status rows (§3.9.4).
     */
    private fun flushInitialBatchLocked() {
        activeInitialBatchSettled = true
        val orderedIds = activeBatchBuffer.keys.sortedBy { activeSourceOrder[it] ?: Int.MAX_VALUE }
        val resultRows = ArrayList<SearchSourceRow>()
        val statusRows = ArrayList<SearchSourceRow>()

        for (id in orderedIds) {
            val src = activeSourcesById[id] ?: continue
            val status = activeBatchBuffer[id] ?: continue
            when (status) {
                is SearchRowStatus.Empty -> activeEmptyIds.add(id)
                is SearchRowStatus.Results -> resultRows += SearchSourceRow(
                    sourceId = src.sourceId,
                    displayName = src.displayName,
                    typeLabel = src.typeLabel,
                    sourceKind = src.sourceKind,
                    mediaSource = src.mediaSource,
                    status = status,
                    arrivedAfterBatch = false,
                )
                else -> statusRows += SearchSourceRow(
                    sourceId = src.sourceId,
                    displayName = src.displayName,
                    typeLabel = src.typeLabel,
                    sourceKind = src.sourceKind,
                    mediaSource = src.mediaSource,
                    status = status,
                    arrivedAfterBatch = false,
                )
            }
        }
        activeBatchBuffer.clear()
        activeVisibleRows.addAll(resultRows)
        activeVisibleRows.addAll(statusRows)
        activeShowInitialSkeleton = activeVisibleRows.isEmpty() && activePendingIds.isNotEmpty()
    }

    /**
     * Strictly appends a post-batch outcome at the tail of [activeVisibleRows] so
     * already-displayed rows NEVER move or reorder (§3.9.1, §3.9.4).
     */
    private fun appendPostBatchOutcomeLocked(src: SearchableSource, status: SearchRowStatus) {
        when (status) {
            is SearchRowStatus.Empty -> activeEmptyIds.add(src.sourceId)
            else -> {
                if (activeVisibleRows.none { it.sourceId == src.sourceId }) {
                    activeVisibleRows.add(
                        SearchSourceRow(
                            sourceId = src.sourceId,
                            displayName = src.displayName,
                            typeLabel = src.typeLabel,
                            sourceKind = src.sourceKind,
                            mediaSource = src.mediaSource,
                            status = status,
                            arrivedAfterBatch = true,
                        ),
                    )
                }
            }
        }
        if (activeVisibleRows.isNotEmpty() || activePendingIds.isEmpty()) {
            activeShowInitialSkeleton = false
        }
    }

    private fun launchNoResultSuggestions(gen: Long, query: String) {
        val sourcesSnapshot = activeSourceOrder.entries
            .sortedBy { it.value }
            .mapNotNull { activeSourcesById[it.key] }
        if (sourcesSnapshot.isEmpty()) return

        activeFallbackSuggestJob?.cancel()
        activeFallbackSuggestJob = scope.launch {
            val remote = SearchSuggestionEngine.fetchRemoteSuggestions(
                rawQuery = query,
                sources = sourcesSnapshot,
                timeoutMs = suggestTimeoutMs,
                ioDispatcher = ioDispatcher,
                maxCount = SearchConfig.MAX_NORESULT_SUGGESTIONS + 2,
            )
            val filtered = remote
                .filter { !it.equals(query, ignoreCase = true) }
                .take(SearchConfig.MAX_NORESULT_SUGGESTIONS)
            if (filtered.isEmpty()) return@launch

            stateMutex.withLock {
                if (generationSeq.get() != gen) return@withLock
                if (!_state.value.isTrueNoResult) return@withLock
                activeFallbackSuggestions = filtered
                emitStateLocked(gen)
            }
        }
    }

    private fun emitStateLocked(gen: Long) {
        val pendingNames = activeSourceOrder.entries
            .sortedBy { it.value }
            .filter { it.key in activePendingIds }
            .mapNotNull { activeSourcesById[it.key]?.displayName }
        val emptyNames = activeSourceOrder.entries
            .sortedBy { it.value }
            .filter { it.key in activeEmptyIds }
            .mapNotNull { activeSourcesById[it.key]?.displayName }

        _state.value = AggregatedSearchState(
            generation = gen,
            query = activeQueryNormalized,
            kindFilter = activeKindFilter,
            totalSearchableSources = activeAllSearchableNames.size,
            allSearchableSourceNames = activeAllSearchableNames.toList(),
            nonSearchableSourceNames = activeNonSearchableNames.toList(),
            initialBatchSettled = activeInitialBatchSettled,
            showInitialSkeleton = activeShowInitialSkeleton,
            showPendingTail = activeShowPendingTail && pendingNames.isNotEmpty(),
            visibleRows = activeVisibleRows.toList(),
            pendingSourceNames = pendingNames,
            emptySourceNames = emptyNames,
            availableKinds = activeAvailableKinds.toSet(),
            fallbackSuggestions = activeFallbackSuggestions.toList(),
        )
    }

    companion object {
        internal fun deduplicateItems(items: List<Item>): List<Item> {
            if (items.size <= 1) return items
            val seen = HashSet<String>(items.size)
            val out = ArrayList<Item>(items.size)
            for (item in items) {
                if (seen.add(item.id)) out += item
            }
            return out
        }
    }
}
