package com.limelight.local.search

import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Shelf
import com.limelight.local.media.ShelfStyle
import com.limelight.local.media.WatchProgress
import com.limelight.local.wall.Load

/** Visual card shape in search results: all images share a fixed 192 dp height (`ux-spec.md` §3.9.2). */
enum class SearchCardShape(val widthDp: Int) {
    PORTRAIT(128),
    LANDSCAPE(341),
    FOLDER(128);

    companion object {
        const val IMAGE_HEIGHT_DP = 192

        fun forItem(item: Item): SearchCardShape = when (item.kind) {
            ItemKind.FOLDER -> FOLDER
            ItemKind.EPISODE, ItemKind.VIDEO, ItemKind.LIVE -> LANDSCAPE
            else -> PORTRAIT
        }
    }
}

data class PackedSearchItem(
    val globalIndex: Int,
    val item: Item,
    val shape: SearchCardShape,
)

/**
 * Pure greedy left-to-right row packer for the expanded single-source screen (`ux-spec.md` §3.9.3, §7):
 * - All items keep their exact intrinsic widths (128 dp portrait/folder, 341 dp landscape)
 *   and fixed 192 dp height.
 * - Items never stretch and never reorder to fill gaps.
 * - Appending new items from later pages ONLY extends the final row or appends new rows;
 *   every earlier row remains 100% identical.
 */
object SearchRowPacker {
    fun pack(
        items: List<Item>,
        maxWidthDp: Int = SearchConfig.EXPANDED_ROW_WIDTH_DP,
        gapDp: Int = SearchConfig.EXPANDED_ITEM_GAP_DP,
    ): List<List<PackedSearchItem>> {
        if (items.isEmpty()) return emptyList()
        val rows = ArrayList<MutableList<PackedSearchItem>>()
        var currentRow = ArrayList<PackedSearchItem>()
        var currentWidth = 0

        items.forEachIndexed { idx, item ->
            val shape = SearchCardShape.forItem(item)
            val w = shape.widthDp
            val needed = if (currentRow.isEmpty()) w else currentWidth + gapDp + w
            if (currentRow.isNotEmpty() && needed > maxWidthDp) {
                rows += currentRow
                currentRow = ArrayList()
                currentWidth = 0
            }
            currentRow += PackedSearchItem(globalIndex = idx, item = item, shape = shape)
            currentWidth = if (currentRow.size == 1) w else currentWidth + gapDp + w
        }
        if (currentRow.isNotEmpty()) {
            rows += currentRow
        }
        return rows
    }
}

/** One entry in the idle screen's 「最近看过」 strip (16:9 card, resumable or finished). */
data class IdleWatchedEntry(
    val source: MediaSource,
    val item: Item,
    val progress: WatchProgress,
) {
    val isResumable: Boolean get() = progress.resumable
    val progressFraction: Float?
        get() = if (progress.resumable && progress.durationMs > 0L) {
            (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0.02f, 1f)
        } else {
            null
        }
}

/** One entry in the idle screen's 「最近添加」 strip or local typing catalog. */
data class LocalCatalogEntry(
    val source: MediaSource,
    val item: Item,
)

/**
 * Builds the idle screen's 「最近看过」, 「最近添加」, and local typing catalog strictly from
 * local Room watch history and already-loaded home shelves — zero network requests (`ux-spec.md` §3.9.3).
 */
object SearchIdleDataBuilder {
    fun buildRecentWatched(
        progressRows: List<WatchProgress>,
        sources: List<MediaSource>,
        shelves: Map<String, Load<List<Shelf>>>,
        limit: Int = SearchConfig.MAX_IDLE_RECENT_WATCHED,
    ): List<IdleWatchedEntry> {
        if (progressRows.isEmpty() || sources.isEmpty()) return emptyList()
        val srcById = sources.associateBy { it.source.id }
        val cachedItems = indexCachedShelfItems(shelves)
        val out = ArrayList<IdleWatchedEntry>(limit.coerceAtMost(progressRows.size))
        val seen = HashSet<String>()

        for (p in progressRows) {
            if (out.size >= limit) break
            val src = srcById[p.sourceId] ?: continue
            val key = "${p.sourceId}/${p.itemId}"
            if (!seen.add(key)) continue
            val cached = cachedItems[p.sourceId to p.itemId]
            val item = when {
                cached != null && cached.parentId.isNullOrBlank() && !p.parentId.isNullOrBlank() ->
                    cached.copy(parentId = p.parentId)
                cached != null -> cached
                else -> Item(
                    id = p.itemId,
                    kind = ItemKind.VIDEO,
                    title = p.title.ifBlank { p.itemId },
                    durationMs = p.durationMs.takeIf { it > 0L },
                    parentId = p.parentId,
                )
            }
            out += IdleWatchedEntry(source = src, item = item, progress = p)
        }
        return out
    }

    fun buildRecentAdded(
        sources: List<MediaSource>,
        shelves: Map<String, Load<List<Shelf>>>,
        limit: Int = SearchConfig.MAX_IDLE_RECENT_ADDED,
    ): List<LocalCatalogEntry> {
        if (sources.isEmpty()) return emptyList()
        // Collect per-source non-continue, non-folder candidates, prioritizing shelves with
        // "最近" / POSTER style, then interleave round-robin across sources so every source shows up.
        val perSourceQueues = ArrayList<ArrayDeque<LocalCatalogEntry>>()
        for (src in sources) {
            val loaded = (shelves[src.source.id] as? Load.Ready)?.value ?: continue
            val orderedShelves = loaded
                .filter { it.style != ShelfStyle.CONTINUE && it.items.isNotEmpty() }
                .sortedByDescending { sh ->
                    var score = 0
                    if (sh.category.title.contains("最近") || sh.category.id.contains("latest", ignoreCase = true) ||
                        sh.category.id.contains("recent", ignoreCase = true)
                    ) score += 2
                    if (sh.style == ShelfStyle.POSTER || sh.style == ShelfStyle.THUMB) score += 1
                    score
                }
            val q = ArrayDeque<LocalCatalogEntry>()
            val seenInSource = HashSet<String>()
            for (sh in orderedShelves) {
                for (item in sh.items) {
                    if (item.isFolder) continue
                    if (seenInSource.add(item.id)) {
                        q.addLast(LocalCatalogEntry(src, item))
                    }
                }
            }
            if (q.isNotEmpty()) perSourceQueues += q
        }

        val out = ArrayList<LocalCatalogEntry>(limit)
        while (out.size < limit) {
            var addedAny = false
            for (q in perSourceQueues) {
                if (out.size >= limit) break
                val next = q.removeFirstOrNull() ?: continue
                out += next
                addedAny = true
            }
            if (!addedAny) break
        }
        return out
    }

    /** Full local catalog from cached home shelves + recent watched items for instant typing matches. */
    fun buildLocalCatalog(
        sources: List<MediaSource>,
        shelves: Map<String, Load<List<Shelf>>>,
        recentWatched: List<IdleWatchedEntry>,
    ): List<LocalCatalogEntry> {
        val out = ArrayList<LocalCatalogEntry>()
        val seen = HashSet<String>()
        for (w in recentWatched) {
            val key = "${w.source.source.id}/${w.item.id}"
            if (seen.add(key)) {
                out += LocalCatalogEntry(w.source, w.item)
            }
        }
        for (src in sources) {
            val loaded = (shelves[src.source.id] as? Load.Ready)?.value ?: continue
            for (sh in loaded) {
                for (item in sh.items) {
                    val key = "${src.source.id}/${item.id}"
                    if (seen.add(key)) {
                        out += LocalCatalogEntry(src, item)
                    }
                }
            }
        }
        return out
    }

    private fun indexCachedShelfItems(shelves: Map<String, Load<List<Shelf>>>): Map<Pair<String, String>, Item> {
        val out = HashMap<Pair<String, String>, Item>()
        for ((sourceId, load) in shelves) {
            val list = (load as? Load.Ready)?.value ?: continue
            for (shelf in list) {
                for (item in shelf.items) {
                    out.putIfAbsent(sourceId to item.id, item)
                }
            }
        }
        return out
    }
}
