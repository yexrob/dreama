package com.limelight.local.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One row inside the typing suggestion list (`ux-spec.md` §3.9.5, max 4 rows). */
sealed interface SuggestionRow {
    val stableKey: String

    /** Row 1: always verbatim search 「搜索「{query}」」. */
    data class RawQuery(val query: String) : SuggestionRow {
        override val stableKey: String get() = "raw:$query"
    }

    /** Row 2 (up to 2): local direct hit from cached shelves / watch history. */
    data class LocalHit(val entry: LocalCatalogEntry) : SuggestionRow {
        override val stableKey: String get() = "local:${entry.source.source.id}/${entry.item.id}"
    }

    /** Row 3: matching local search history term (with ↖ fill-in action). */
    data class History(val term: String) : SuggestionRow {
        override val stableKey: String get() = "hist:$term"
    }

    /** Row 4: remote plugin suggestion (with ↖ fill-in action, only fills empty trailing slots). */
    data class Remote(val term: String) : SuggestionRow {
        override val stableKey: String get() = "remote:$term"
    }
}

/**
 * Pure-Kotlin suggestion composer & silent concurrent remote fetcher (`ux-spec.md` §3.9.5, `08-search.md`):
 * - Row 1 is always verbatim search when query is non-empty.
 * - Rows 2–3 are computed synchronously from local catalog (max 2) and local search history.
 * - Remote plugin suggestions are fetched concurrently with per-source 800 ms timeout; any
 *   failure, timeout, or missing `suggest()` is completely silent; results are merged in
 *   configured source order, deduplicated, and ONLY fill remaining slots up to [maxRows] (4).
 */
object SearchSuggestionEngine {
    fun buildRows(
        rawQuery: String,
        localCatalog: List<LocalCatalogEntry>,
        history: List<String>,
        remoteSuggestions: List<String> = emptyList(),
        maxRows: Int = SearchConfig.MAX_SUGGESTION_ROWS,
        maxLocalHits: Int = SearchConfig.MAX_LOCAL_DIRECT_HITS,
    ): List<SuggestionRow> {
        val norm = SearchQueryNormalizer.normalize(rawQuery)
        if (norm.isEmpty()) {
            return history.take(maxRows).map { SuggestionRow.History(it) }
        }

        val out = ArrayList<SuggestionRow>(maxRows)
        val seenTermsLower = HashSet<String>()
        val normLower = norm.lowercase()
        seenTermsLower += normLower

        // 1. Always verbatim search
        out += SuggestionRow.RawQuery(norm)
        if (out.size >= maxRows) return out

        // 2. Local direct hits (prefix matches first, then substring matches), max 2
        val localMatches = localCatalog
            .filter { it.item.title.contains(norm, ignoreCase = true) }
            .sortedWith(
                compareByDescending<LocalCatalogEntry> { it.item.title.startsWith(norm, ignoreCase = true) }
                    .thenBy { it.item.title.length },
            )
            .take(maxLocalHits)
        for (hit in localMatches) {
            if (out.size >= maxRows) return out
            out += SuggestionRow.LocalHit(hit)
        }

        // 3. Matching search history entries
        for (term in history) {
            if (out.size >= maxRows) return out
            val clean = SearchQueryNormalizer.normalize(term)
            if (clean.isEmpty()) continue
            val lower = clean.lowercase()
            if (lower == normLower) continue
            if (clean.contains(norm, ignoreCase = true) && seenTermsLower.add(lower)) {
                out += SuggestionRow.History(clean)
            }
        }

        // 4. Remote suggestions strictly filling remaining trailing slots
        for (term in remoteSuggestions) {
            if (out.size >= maxRows) return out
            val clean = SearchQueryNormalizer.normalize(term)
            if (clean.isEmpty()) continue
            val lower = clean.lowercase()
            if (seenTermsLower.add(lower)) {
                out += SuggestionRow.Remote(clean)
            }
        }
        return out
    }

    /**
     * Fetches remote suggestions concurrently from all eligible sources in [sources] order.
     * Enforces §3.9.5 minimum query length, per-source [timeoutMs] (800 ms), deduplication,
     * and complete silence on any source error/timeout/missing `suggest()`.
     */
    suspend fun fetchRemoteSuggestions(
        rawQuery: String,
        sources: List<SearchableSource>,
        timeoutMs: Long = SearchConfig.SUGGEST_TIMEOUT_MS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        maxCount: Int = SearchConfig.MAX_SUGGESTIONS,
    ): List<String> {
        if (!SearchQueryNormalizer.shouldRequestRemoteSuggest(rawQuery)) return emptyList()
        val norm = SearchQueryNormalizer.normalize(rawQuery)
        val eligible = sources.filter { it.supportsSuggest && !it.knownNeedsAuth }
        if (eligible.isEmpty()) return emptyList()

        val perSourceResults: List<List<String>> = coroutineScope {
            eligible.map { src ->
                async {
                    try {
                        withTimeoutOrNull(timeoutMs) {
                            withContext(ioDispatcher) { src.suggest(norm) }
                        }.orEmpty()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val seen = LinkedHashSet<String>()
        val merged = ArrayList<String>(maxCount)
        for (list in perSourceResults) {
            for (raw in list) {
                val clean = SearchQueryNormalizer.normalize(raw)
                if (clean.isEmpty()) continue
                if (seen.add(clean.lowercase())) {
                    merged += clean
                    if (merged.size >= maxCount) return merged
                }
            }
        }
        return merged
    }
}

/**
 * Freezes suggestion list updates while the VR ray hovers over the list (`ux-spec.md` §3.9.5, §7):
 * - When hovered, incoming updates for the same query are buffered instead of replacing rows
 *   underneath the user's pointer.
 * - Immediately flushes when hover ends, or resets immediately whenever the query changes.
 */
class HoverFreezeBuffer<T>(initialValue: T) {
    var displayed: T = initialValue
        private set
    private var buffered: T? = null
    private var hovered: Boolean = false
    private var currentKey: String? = null

    fun onQueryChanged(key: String, immediateValue: T): T {
        if (key == currentKey) {
            return onUpdate(key, immediateValue)
        }
        currentKey = key
        buffered = null
        displayed = immediateValue
        return displayed
    }

    fun onUpdate(key: String, newValue: T): T {
        if (key != currentKey) {
            return onQueryChanged(key, newValue)
        }
        if (hovered) {
            buffered = newValue
        } else {
            buffered = null
            displayed = newValue
        }
        return displayed
    }

    fun onHoverChanged(isHovered: Boolean): T {
        hovered = isHovered
        if (!hovered) {
            buffered?.let {
                displayed = it
                buffered = null
            }
        }
        return displayed
    }
}
