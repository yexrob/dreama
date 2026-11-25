package com.limelight.local.danmaku

import com.limelight.binding.video.XrShared

/**
 * Pure-Kotlin shelf allocator for the 2048x2048 premultiplied GL_RGBA8 danmaku atlas
 * (ux-spec.md §6.10, §7).
 *
 * - Partitions the atlas into horizontal shelves sized to the 3 font tiers (56 / 72 / 92 px).
 * - Assigns each live allocation a bounded [DanmakuAtlasSlot.slotIndex] in `[0, DANMAKU_MAX_SLOTS)`
 *   plus a strictly monotonic [DanmakuAtlasSlot.generation] (`>= 1`) so native GPU rendering skips
 *   any instance until that exact `(slotIndex, generation)` upload has landed.
 * - Coalesces freed spans immediately on release; returns `null` when shelves or slot pool are full.
 */
class DanmakuAtlasAllocator(
    val atlasWidth: Int = XrShared.DANMAKU_ATLAS_W,
    val atlasHeight: Int = XrShared.DANMAKU_ATLAS_H,
    private val gutterPx: Int = 2,
    private val maxSlots: Int = XrShared.DANMAKU_MAX_SLOTS,
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
    private val freeSlotIndices = ArrayDeque<Int>(maxSlots)
    private var nextSlotId = 1
    private var nextGeneration = 1

    init {
        buildShelves()
        resetSlotPool()
    }

    val activeSlotCount: Int
        get() = activeSlots.size

    private fun resetSlotPool() {
        freeSlotIndices.clear()
        for (i in 0 until maxSlots) {
            freeSlotIndices.addLast(i)
        }
    }

    private fun nextGen(): Int {
        val g = nextGeneration
        nextGeneration = if (g == Int.MAX_VALUE) 1 else g + 1
        return g
    }

    private fun buildShelves() {
        shelves.clear()
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

    /** Allocates a `[width x height]` rectangle with a fresh `(slotIndex, generation)`, or `null` if full. */
    fun allocate(width: Int, height: Int): DanmakuAtlasSlot? {
        if (width <= 0 || height <= 0 || freeSlotIndices.isEmpty()) return null
        val needW = width + gutterPx
        val needH = height + gutterPx
        if (needW > atlasWidth || needH > 92) return null

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
                        val slotIdx = freeSlotIndices.removeFirst()
                        val slot = DanmakuAtlasSlot(
                            slotId = nextSlotId++,
                            slotIndex = slotIdx,
                            generation = nextGen(),
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

    /** Releases a previously allocated slot back into its shelf and free slot-index pool. */
    fun release(slot: DanmakuAtlasSlot) {
        val removed = activeSlots.remove(slot.slotId) ?: return
        freeSlotIndices.addLast(removed.slotIndex)
        val shelf = shelves.getOrNull(removed.shelfIndex) ?: return
        val freedX = removed.x
        val freedW = removed.width + gutterPx
        val spans = shelf.freeSpans

        var insertAt = 0
        while (insertAt < spans.size && spans[insertAt].x < freedX) {
            insertAt++
        }
        spans.add(insertAt, Span(freedX, freedW))

        if (insertAt + 1 < spans.size) {
            val cur = spans[insertAt]
            val next = spans[insertAt + 1]
            if (cur.x + cur.width == next.x) {
                cur.width += next.width
                spans.removeAt(insertAt + 1)
            }
        }
        if (insertAt > 0) {
            val prev = spans[insertAt - 1]
            val cur = spans[insertAt]
            if (prev.x + prev.width == cur.x) {
                prev.width += cur.width
                spans.removeAt(insertAt)
            }
        }
    }

    /** Resets shelves & free slot pool while keeping [nextGeneration] monotonically advancing. */
    fun clear() {
        activeSlots.clear()
        resetSlotPool()
        for (shelf in shelves) {
            shelf.freeSpans.clear()
            shelf.freeSpans.add(Span(0, atlasWidth))
        }
    }
}
