package com.limelight.local.search

import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.WatchProgress
import com.limelight.local.plugin.PluginCapability
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.stream.StringLookup
import com.limelight.local.wall.DetailStateLogic
import com.limelight.local.wall.Load
import com.limelight.local.wall.SourceErrorActionKind
import com.limelight.local.wall.SourceErrorDispatcher
import com.limelight.local.wall.WatchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Centralized search timing, pagination, and cache constants
 * (`design-opus/ux-spec.md` §3.9, §7, §8 #25; `docs/specs/08-search.md`).
 */
object SearchConfig {
    /** Initial batch window before locking the first landed rows in source order (§3.9.4). */
    const val INITIAL_BATCH_MS: Long = 300L

    /** Per-source abandon timeout; slow sources turn into a timeout row after 10 s (§3.9.4). */
    const val SOURCE_TIMEOUT_MS: Long = 10_000L

    /** Delay before showing the bottom 「还在搜：…」 tail line for slow sources (§3.9.4). */
    const val PENDING_TAIL_DELAY_MS: Long = 3_000L

    /** In-memory search result cache TTL for repeated identical queries (§3.9.4, §7). */
    const val CACHE_TTL_MS: Long = 5 * 60 * 1000L

    /** Bounded in-memory cache capacity (per (query, kindFilter, sourceId)). */
    const val MAX_CACHE_ENTRIES: Int = 64

    /** Debounce before querying remote plugin `suggest()` (§3.9.5, §7). */
    const val SUGGEST_DEBOUNCE_MS: Long = 250L

    /** Per-source timeout for `suggest()` (§3.9.5, §7). */
    const val SUGGEST_TIMEOUT_MS: Long = 800L

    /** Maximum remote suggestions collected before capping to suggestion slots. */
    const val MAX_SUGGESTIONS: Int = 8

    /** Maximum visible suggestion rows inside the keyboard safe zone (§3.9.5, §3.9.6). */
    const val MAX_SUGGESTION_ROWS: Int = 4

    /** Maximum local direct-hit rows inside typing suggestions (§3.9.5). */
    const val MAX_LOCAL_DIRECT_HITS: Int = 2

    /** Maximum "是不是要找" chips in the no-result state (§3.9.3). */
    const val MAX_NORESULT_SUGGESTIONS: Int = 3

    /** Maximum items shown per source row before the 「全部 N 个」 tile (§3.9.2). */
    const val ROW_PREVIEW_LIMIT: Int = 12

    /** Maximum search history entries stored locally (§3.9.5, §7). */
    const val MAX_HISTORY_ENTRIES: Int = 20

    /** Maximum history chips shown on the idle screen (§3.9.3). */
    const val MAX_IDLE_HISTORY_CHIPS: Int = 8

    /** Maximum recent watched cards on the idle screen (§3.9.3). */
    const val MAX_IDLE_RECENT_WATCHED: Int = 10

    /** Delay before showing next-page skeleton blocks in the expanded grid (§3.9.4). */
    const val PAGE_SKELETON_DELAY_MS: Long = 300L

    /** Expanded flow layout available width (1216 - 64) and horizontal gap in design dp (§3.9.3). */
    const val EXPANDED_ROW_WIDTH_DP: Int = 1152
    const val EXPANDED_ITEM_GAP_DP: Int = 24

    /** Minimum items before auto-paging stops when a source ignores `kind` (§3.9.4). */
    const val MIN_FILTERED_ITEMS_BEFORE_STOP: Int = 10

    /** Maximum consecutive auto-fetch pages when a source ignores `kind` (§3.9.4). */
    const val MAX_FILTER_AUTO_PAGES: Int = 3

    /** Maximum consecutive duplicate-only pages skipped automatically in expanded pagination. */
    const val MAX_DUPLICATE_SKIP_PAGES: Int = 3
}

/**
 * Type filter tabs on the search results and expanded screens (`ux-spec.md` §3.9.1, §3.9.7).
 *
 * Per §3.9.7: 「剧集筛选同时包括剧集和单集，文件夹只在『全部』里出现。」
 */
enum class SearchKindFilter {
    ALL,
    MOVIE,
    SERIES,
    VIDEO;

    fun toPluginKind(): ItemKind? = when (this) {
        ALL -> null
        MOVIE -> ItemKind.MOVIE
        SERIES -> ItemKind.SERIES
        VIDEO -> ItemKind.VIDEO
    }

    fun matches(item: Item): Boolean = when (this) {
        ALL -> true
        MOVIE -> item.kind == ItemKind.MOVIE
        SERIES -> item.kind == ItemKind.SERIES || item.kind == ItemKind.EPISODE || item.kind == ItemKind.SEASON
        VIDEO -> item.kind == ItemKind.VIDEO || item.kind == ItemKind.LIVE
    }

    companion object {
        fun filtersMatching(item: Item): Set<SearchKindFilter> {
            val out = LinkedHashSet<SearchKindFilter>(2)
            out += ALL
            when (item.kind) {
                ItemKind.MOVIE -> out += MOVIE
                ItemKind.SERIES, ItemKind.EPISODE, ItemKind.SEASON -> out += SERIES
                ItemKind.VIDEO, ItemKind.LIVE -> out += VIDEO
                else -> Unit
            }
            return out
        }
    }
}

/**
 * Query normalization and remote-suggestion gating (`ux-spec.md` §3.9.5):
 * - Strips leading/trailing whitespace (including full-width spaces)
 * - Converts full-width ASCII (`！..～`, `\uFF01..\uFF5E`) to half-width
 * - Collapses consecutive whitespace into a single ASCII space
 * - Gates remote `suggest()` to ≥ 1 Han/CJK character or ≥ 2 alphanumeric characters
 */
object SearchQueryNormalizer {
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            val mapped = when {
                ch == '\u3000' || ch.isWhitespace() -> ' '
                ch in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            if (mapped == ' ') {
                if (sb.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    sb.append(' ')
                    pendingSpace = false
                }
                sb.append(mapped)
            }
        }
        return sb.toString()
    }

    fun shouldRequestRemoteSuggest(raw: String): Boolean {
        val norm = normalize(raw)
        if (norm.isEmpty()) return false
        var alnumCount = 0
        var i = 0
        while (i < norm.length) {
            val cp = norm.codePointAt(i)
            i += Character.charCount(cp)
            if (isCjkIdeograph(cp)) return true
            if (Character.isLetterOrDigit(cp)) {
                alnumCount++
                if (alnumCount >= 2) return true
            }
        }
        return false
    }

    private fun isCjkIdeograph(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x3040..0x30FF) ||
            (cp in 0xAC00..0xD7AF)
}

/**
 * Ensures search query text is never written to logs or diagnostics (`08-search.md` §2).
 * Wired directly into [com.limelight.local.plugin.PluginLogger] during every `search()` and
 * `suggest()` invocation so neither plugin `ctx.log.*` nor host error logs can emit raw or
 * URL-encoded search keywords.
 */
object SearchLogSanitizer {
    const val REDACTED_QUERY = "[REDACTED_QUERY]"

    fun redactQuery(raw: String): String = "<redacted:len=${raw.length}>"

    fun redactInText(text: String, queries: Collection<String>): String {
        if (text.isEmpty() || queries.isEmpty()) return text
        val candidates = LinkedHashSet<String>()
        for (raw in queries) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            candidates += trimmed
            val norm = SearchQueryNormalizer.normalize(trimmed)
            if (norm.isNotEmpty()) candidates += norm
            for (term in listOf(trimmed, norm).filter { it.isNotEmpty() }.distinct()) {
                val encoded = runCatching { java.net.URLEncoder.encode(term, "UTF-8") }.getOrNull()
                if (!encoded.isNullOrEmpty() && encoded != term) {
                    candidates += encoded
                    val pct20 = encoded.replace("+", "%20")
                    if (pct20 != encoded) candidates += pct20
                }
            }
        }
        if (candidates.isEmpty()) return text

        var out = text
        for (term in candidates.sortedByDescending { it.length }) {
            val hasNonAscii = term.any { it.code > 0x7F }
            if (term.length >= 2 || hasNonAscii) {
                out = out.replace(term, REDACTED_QUERY, ignoreCase = !hasNonAscii)
            } else {
                val escaped = Regex.escape(term)
                val quoted = Regex("""(["'「『])$escaped(["'」』])""", RegexOption.IGNORE_CASE)
                out = quoted.replace(out) { m -> "${m.groupValues[1]}$REDACTED_QUERY${m.groupValues[2]}" }
                val param = Regex("""([?&=:]\s*)$escaped(?=(?:[&#\s,"'}\]|]|$))""", RegexOption.IGNORE_CASE)
                out = param.replace(out) { m -> "${m.groupValues[1]}$REDACTED_QUERY" }
                val labeled = Regex("""(\b(?:query|q|term|keyword)\s+)$escaped(?=(?:\s|$))""", RegexOption.IGNORE_CASE)
                out = labeled.replace(out) { m -> "${m.groupValues[1]}$REDACTED_QUERY" }
            }
        }
        return out
    }
}

/** Navigation decision when clicking an item card in Search (`ux-spec.md` §3.9.7). */
sealed interface SearchItemAction {
    data class OpenFolder(val source: MediaSource, val item: Item) : SearchItemAction
    data class PlayDirect(val source: MediaSource, val item: Item, val startMs: Long) : SearchItemAction
    data class OpenEpisodeInSeries(val source: MediaSource, val item: Item) : SearchItemAction
    data class OpenDetail(val source: MediaSource, val item: Item) : SearchItemAction
}

/**
 * Pure navigation policy shared by aggregated results, expanded source view, and local typing hits:
 * - Folder -> open directory (`GridScreen`)
 * - Episode -> open owning series detail focused on that episode
 * - Video / Live (non-multipart) -> play directly with resumable progress (local overrides remote)
 * - Movie / Series / multi-part -> open detail screen
 */
object SearchNavigationPolicy {
    fun resolveClickAction(
        source: MediaSource,
        item: Item,
        localProgress: WatchProgress? = null,
    ): SearchItemAction = when {
        item.isFolder -> SearchItemAction.OpenFolder(source, item)
        item.kind == ItemKind.EPISODE -> SearchItemAction.OpenEpisodeInSeries(source, item)
        (item.kind == ItemKind.VIDEO || item.kind == ItemKind.LIVE) &&
            item.episodes.isEmpty() && item.seasons.isEmpty() -> {
            val ws = DetailStateLogic.computeWatchState(item, localProgress)
            val startMs = (ws as? WatchState.InProgress)?.positionMs ?: 0L
            SearchItemAction.PlayDirect(source, item, startMs)
        }
        else -> SearchItemAction.OpenDetail(source, item)
    }
}

/**
 * Pure abstraction over a source that participates in aggregated search so the
 * aggregation, pagination, and suggestion layers remain 100% JVM-testable without
 * Compose, Android, or QuickJS dependencies.
 */
interface SearchableSource {
    val sourceId: String
    val displayName: String
    val typeLabel: String
    val sourceKind: SourceKind
    val knownNeedsAuth: Boolean get() = false
    val supportsSuggest: Boolean get() = true
    val isWarmedUp: Boolean get() = true
    val isClosed: Boolean get() = false
    val mediaSource: MediaSource? get() = null

    suspend fun search(query: String, cursor: String? = null, kind: ItemKind? = null): Page<Item>
    suspend fun suggest(query: String): List<String> = emptyList()
    suspend fun warmUp() {}
}

/**
 * Production adapter wrapping an enabled [PluginMediaSource] with `SEARCH` capability.
 * Implements structural equality on immutable identity/capability fields so Compose keys
 * (e.g. typing suggestion effects) stay stable across recompositions.
 */
class PluginSearchableSource(
    private val plugin: PluginMediaSource,
    override val typeLabel: String,
    override val knownNeedsAuth: Boolean = false,
) : SearchableSource {
    override val sourceId: String get() = plugin.source.id
    override val displayName: String get() = plugin.source.name
    override val sourceKind: SourceKind get() = plugin.source.kind
    override val supportsSuggest: Boolean get() = !plugin.isSuggestKnownUnsupported
    override val isWarmedUp: Boolean get() = plugin.isRuntimeInitialized
    override val isClosed: Boolean get() = plugin.isClosed
    override val mediaSource: MediaSource get() = plugin

    override suspend fun search(query: String, cursor: String?, kind: ItemKind?): Page<Item> =
        plugin.search(query = query, cursor = cursor, kind = kind)

    override suspend fun suggest(query: String): List<String> =
        if (plugin.isSuggestKnownUnsupported) emptyList() else plugin.suggest(query)

    override suspend fun warmUp() {
        if (!knownNeedsAuth && !plugin.isRuntimeInitialized && !plugin.isClosed) {
            plugin.warmUpIfNeeded()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PluginSearchableSource) return false
        return plugin === other.plugin &&
            sourceId == other.sourceId &&
            displayName == other.displayName &&
            typeLabel == other.typeLabel &&
            knownNeedsAuth == other.knownNeedsAuth
    }

    override fun hashCode(): Int {
        var h = System.identityHashCode(plugin)
        h = 31 * h + sourceId.hashCode()
        h = 31 * h + displayName.hashCode()
        h = 31 * h + typeLabel.hashCode()
        h = 31 * h + knownNeedsAuth.hashCode()
        return h
    }
}

data class SearchSourcePartition(
    val searchable: List<SearchableSource>,
    val nonSearchableNames: List<String>,
)

/**
 * Partitions wall sources in their configured display order into:
 * - Enabled plugin sources declaring `search` capability
 * - Non-searchable sources (Local, WebDAV, and plugins without `search`) named in the tail/empty UI
 */
object SearchSourceClassifier {
    fun typeLabelFor(plugin: PluginMediaSource, genericPluginLabel: String): String {
        val manifestName = plugin.manifest.name.trim()
        return if (manifestName.isNotEmpty() && !manifestName.equals(plugin.source.name.trim(), ignoreCase = true)) {
            manifestName
        } else {
            genericPluginLabel
        }
    }

    fun partition(
        sources: List<MediaSource>,
        pluginInstances: List<PluginInstanceEntity>,
        shelves: Map<String, Load<*>>,
        genericPluginLabel: String,
    ): SearchSourcePartition {
        val instanceById = pluginInstances.associateBy { it.instanceId }
        val searchable = ArrayList<SearchableSource>()
        val nonSearchable = ArrayList<String>()

        for (src in sources) {
            if (src is PluginMediaSource && src.capabilities.contains(PluginCapability.SEARCH)) {
                val inst = instanceById[src.instanceId]
                val shelfErr = (shelves[src.source.id] as? Load.Failed)?.error
                val knownNeedsAuth = inst?.authState == "NEEDS_AUTH" || shelfErr is SourceNeedsAuthException
                val typeLabel = typeLabelFor(src, genericPluginLabel)
                searchable += PluginSearchableSource(
                    plugin = src,
                    typeLabel = typeLabel,
                    knownNeedsAuth = knownNeedsAuth,
                )
            } else {
                nonSearchable += src.source.name
            }
        }
        return SearchSourcePartition(
            searchable = searchable,
            nonSearchableNames = nonSearchable,
        )
    }
}

/**
 * Background cold-VM warm-up coordinator for Search (`docs/specs/08-search.md`):
 * Entering Search warms up each uninitialized, open, non-needs-auth searchable source at most
 * once in the background so typing `suggest()` does not cold-skip it. Warm-up runs on its own
 * coroutine outside the 800 ms suggestion budget and swallows non-cancellation failures silently.
 */
class SearchWarmUpCoordinator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val attemptedKeys = HashSet<String>()

    fun warmUpUninitialized(
        sources: List<SearchableSource>,
        scope: CoroutineScope,
    ): List<String> {
        val scheduled = ArrayList<String>()
        for (src in sources) {
            if (src.isWarmedUp || src.isClosed || src.knownNeedsAuth) continue
            val identity = System.identityHashCode(src.mediaSource ?: src)
            val key = "${src.sourceId}@$identity"
            if (!attemptedKeys.add(key)) continue
            scheduled += src.sourceId
            scope.launch(ioDispatcher) {
                try {
                    src.warmUp()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Silent background warm-up; errors surface only on explicit user actions.
                }
            }
        }
        return scheduled
    }
}

/**
 * Tracks which post-batch rows have already played their 200 ms fade-in animation so scrolling
 * off/on screen or returning from Detail/Expanded never replays the fade-in.
 */
class SearchRowFadeTracker {
    private val completedKeys = HashSet<String>()

    fun shouldFadeIn(generation: Long, sourceId: String, arrivedAfterBatch: Boolean): Boolean {
        if (!arrivedAfterBatch) return false
        return "$generation:$sourceId" !in completedKeys
    }

    fun markFadedIn(generation: Long, sourceId: String) {
        completedKeys.add("$generation:$sourceId")
    }

    fun clear() {
        completedKeys.clear()
    }
}

/**
 * Maps a thrown error into the search row status by delegating auth/rate-limit
 * classification to the shared [SourceErrorDispatcher] used by HomeScreen (`08-search.md`).
 */
object SearchErrorClassifier {
    private val NOOP_STRINGS = object : StringLookup {
        override fun get(resId: Int): String = ""
        override fun get(resId: Int, vararg args: Any): String = ""
    }

    fun classifyFailure(
        error: Throwable,
        sourceKind: SourceKind,
        sourceName: String,
        nowMs: Long,
        needsPermissionReconfirm: Boolean = false,
    ): SearchRowStatus {
        val presentation = SourceErrorDispatcher.dispatch(
            error = error,
            sourceKind = sourceKind,
            sourceName = sourceName,
            strings = NOOP_STRINGS,
            needsPermissionReconfirm = needsPermissionReconfirm,
        )
        return when (presentation.primary.kind) {
            SourceErrorActionKind.LOGIN -> SearchRowStatus.NeedsAuth(error)
            SourceErrorActionKind.RETRY_AFTER_COOLDOWN -> {
                val sec = (error as? SourceRateLimitedException)?.retryAfterSec
                    ?: presentation.primary.cooldownSec
                val effectiveSec = sec?.takeIf { it > 0 } ?: 60
                SearchRowStatus.RateLimited(
                    retryAfterSec = effectiveSec,
                    availableAtMs = nowMs + effectiveSec * 1000L,
                    error = error,
                )
            }
            else -> SearchRowStatus.Failed(error)
        }
    }
}

/** Per-source outcome in an aggregated search session. */
sealed interface SearchRowStatus {
    data object Waiting : SearchRowStatus
    data class Results(
        val items: List<Item>,
        val nextCursor: String?,
        val hasMore: Boolean = nextCursor != null,
    ) : SearchRowStatus
    data object Empty : SearchRowStatus
    data class NeedsAuth(val error: Throwable? = null) : SearchRowStatus
    data class RateLimited(
        val retryAfterSec: Int,
        val availableAtMs: Long,
        val error: Throwable,
    ) : SearchRowStatus
    data class TimedOut(val timeoutSec: Int) : SearchRowStatus
    data class Failed(val error: Throwable) : SearchRowStatus
}

/** One visible row in the aggregated results list. */
data class SearchSourceRow(
    val sourceId: String,
    val displayName: String,
    val typeLabel: String,
    val sourceKind: SourceKind,
    val mediaSource: MediaSource?,
    val status: SearchRowStatus,
    val refreshingInPlace: Boolean = false,
    val arrivedAfterBatch: Boolean = false,
) {
    val isResultsRow: Boolean get() = status is SearchRowStatus.Results && !refreshingInPlace
}

/** Immutable snapshot emitted by [SearchAggregator] for the active query. */
data class AggregatedSearchState(
    val generation: Long = 0L,
    val query: String = "",
    val kindFilter: SearchKindFilter = SearchKindFilter.ALL,
    val totalSearchableSources: Int = 0,
    val allSearchableSourceNames: List<String> = emptyList(),
    val nonSearchableSourceNames: List<String> = emptyList(),
    val initialBatchSettled: Boolean = false,
    val showInitialSkeleton: Boolean = false,
    val showPendingTail: Boolean = false,
    val visibleRows: List<SearchSourceRow> = emptyList(),
    val pendingSourceNames: List<String> = emptyList(),
    val emptySourceNames: List<String> = emptyList(),
    val availableKinds: Set<SearchKindFilter> = setOf(SearchKindFilter.ALL),
    val fallbackSuggestions: List<String> = emptyList(),
) {
    val hasActiveQuery: Boolean get() = query.isNotEmpty()
    val allSettled: Boolean get() = hasActiveQuery && pendingSourceNames.isEmpty() && visibleRows.none { it.refreshingInPlace }
    val hasAnyFailureOrAuth: Boolean
        get() = visibleRows.any { row ->
            row.status is SearchRowStatus.NeedsAuth ||
                row.status is SearchRowStatus.Failed ||
                row.status is SearchRowStatus.TimedOut ||
                row.status is SearchRowStatus.RateLimited
        }

    /**
     * Per `ux-spec.md` §3.9.3: only enter 「无结果」 when every searchable source
     * responded cleanly with zero items. If any source failed or needs login, stay
     * in the list view showing that status line + 「没找到：…」 tail.
     */
    val isTrueNoResult: Boolean
        get() = allSettled &&
            totalSearchableSources > 0 &&
            !hasAnyFailureOrAuth &&
            visibleRows.none { it.status is SearchRowStatus.Results }
}
