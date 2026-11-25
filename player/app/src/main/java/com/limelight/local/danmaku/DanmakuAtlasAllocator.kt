package com.limelight.local.danmaku

import com.limelight.binding.video.XrShared

/**
 * Pure-Kotlin shelf allocator for the 2048x2048 2-channel GL_RG8 danmaku glyph atlas
 * (ux-spec.md §6.10, §7).
 *
 * - Partitions the atlas into horizontal shelves sized to the 3 font tiers (56 / 72 / 92 px)
 *   plus generous extra medium shelves (the default 1.4° tier).
 * - Each shelf maintains a coalescing free-span list so exited danmaku immediately return
 *   space with zero fragmentation blow-up and zero full-atlas re-uploads.
 * - Returns `null` when full, which [DanmakuEngine] treats identically to "no free track"
 *   (wait up to 1 s, then drop).
 */
class DanmakuAtlasAllocator(
    val atlasWidth: Int = XrShared.DANMAKU_ATLAS_W,
    val atlasHeight: Int = XrShared.DANMAKU_ATLAS_H,
    private val gutterPx: Int = 2,
) {
    private data class Span(var x: Int, var width: Int)

    private class Shelf(
        val index: Int,
        val y: Int,
        val height: Int,
        atlasWidth: Int,
    ) {
        val freeSpans = ArrayList<Span>(8).apply { add(Span(0, atlasWidth)) }
    }

    private val shelves = ArrayList<Shelf>()
    private val activeSlots = HashMap<Int, DanmakuAtlasSlot>()
    private var nextSlotId = 1

    init {
        buildShelves()
    }

    val activeSlotCount: Int
        get() = activeSlots.size

    private fun buildShelves() {
        shelves.clear()
        // Partition 2048 px height across font tiers:
        // - 8 shelves x 56 px  = 448 px  (Small 1.2°)
        // - 14 shelves x 72 px = 1008 px (Medium 1.4° default)
        // - 6 shelves x 92 px  = 552 px  (Large 1.7°; total 2008 <= 2048)
        val tierHeights = intArrayOf(56, 72, 92)
        val tierCounts = intArrayOf(8, 14, 6)
        var y = 0
        var idx = 0
        for (t in tierHeights.indices) {
            val h = tierHeights[t]
            val count = tierCounts[t]
            repeat(count) {
                if (y + h <= atlasHeight) {
                    shelves.add(Shelf(idx++, y, h, atlasWidth))
                    y += h
                }
            }
        }
    }

    /** Allocates a `[width x height]` rectangle inside the smallest fitting shelf, or `null` if full. */
    fun allocate(width: Int, height: Int): DanmakuAtlasSlot? {
        if (width <= 0 || height <= 0) return null
        val needW = width + gutterPx
        val needH = height + gutterPx
        if (needW > atlasWidth || needH > 92) return null

        // First-fit in exact-tier shelves, then fall back to taller shelves if exact tier is full
        for (pass in 0..1) {
            for (shelf in shelves) {
                if (shelf.height < needH) continue
                val isExactTier = when {
                    needH <= 56 -> shelf.height == 56
                    needH <= 72 -> shelf.height == 72
                    else -> shelf.height == 92
                }
                if (pass == 0 && !isExactTier) continue
                if (pass == 1 && isExactTier) continue

                val spans = shelf.freeSpans
                for (i in spans.indices) {
                    val sp = spans[i]
                    if (sp.width >= needW) {
                        val allocX = sp.x
                        sp.x += needW
                        sp.width -= needW
                        if (sp.width == 0) {
                            spans.removeAt(i)
                        }
                        val slot = DanmakuAtlasSlot(
                            slotId = nextSlotId++,
                            x = allocX,
                            y = shelf.y,
                            width = width,
                            height = height,
                            shelfIndex = shelf.index,
                        )
                        activeSlots[slot.slotId] = slot
                        return slot
                    }
                }
            }
        }
        return null
    }

    /** Releases a previously allocated slot back into its shelf and coalesces adjacent free spans. */
    fun release(slot: DanmakuAtlasSlot) {
        val removed = activeSlots.remove(slot.slotId) ?: return
        val shelf = shelves.getOrNull(removed.shelfIndex) ?: return
        val freedX = removed.x
        val freedW = removed.width + gutterPx
        val spans = shelf.freeSpans

        var insertAt = 0
        while (insertAt < spans.size && spans[insertAt].x < freedX) {
            insertAt++
        }
        spans.add(insertAt, Span(freedX, freedW))

        // Coalesce with right neighbor
        if (insertAt + 1 < spans.size) {
            val cur = spans[insertAt]
            val next = spans[insertAt + 1]
            if (cur.x + cur.width == next.x) {
                cur.width += next.width
                spans.removeAt(insertAt + 1)
            }
        }
        // Coalesce with left neighbor
        if (insertAt > 0) {
            val prev = spans[insertAt - 1]
            val cur = spans[insertAt]
            if (prev.x + prev.width == cur.x) {
                prev.width += cur.width
                spans.removeAt(insertAt)
            }
        }
    }

    /** Resets all shelves to empty (called on seek/episode reset alongside native atlas clear). */
    fun clear() {
        activeSlots.clear()
        for (shelf in shelves) {
            shelf.freeSpans.clear()
            shelf.freeSpans.add(Span(0, atlasWidth))
        }
    }
}
