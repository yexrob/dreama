package com.limelight.local.danmaku

import com.limelight.binding.video.XrShared
import com.limelight.local.DanmakuArea
import com.limelight.local.DanmakuBlockFlags
import com.limelight.local.DanmakuSpeed
import com.limelight.local.DepthLayerLevel
import com.limelight.local.GlobalPaletteSettings
import com.limelight.local.PaletteStore
import com.limelight.local.StereoSourceFormat
import com.limelight.local.TextSizeTier
import com.limelight.local.plugin.DanmakuItem
import com.limelight.local.plugin.DanmakuMode
import com.limelight.local.stream.MapPrefsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuEngineAndFetcherTest {

    private class VirtualClock(var nowMs: Long = 0L) : DanmakuClock {
        override fun nowRealMs(): Long = nowMs
        fun advance(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    // ---- 1. XML Sidecar Parser (local / WebDAV Bilibili format + XXE hardening) ----

    @Test
    fun xmlParserParsesValidBilibiliXmlSanitizesSortsAndSkipsMalformed() {
        val longText = "A".repeat(48)
        val xml = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<i>
            |  <d p="12.500,5,25,16737945,0,0,0,0">Top   Alert</d>
            |  <d p="1.250,1,25,16777215,0,0,0,0">First
            |Line	Here</d>
            |  <d p="8.000,4,25,65535,0,0,0,0">$longText</d>
            |  <d p="5.000,7,25,16777215,0,0,0,0">SpecialModeSkipped</d>
            |  <d p="bad,1,25,16777215">MalformedTime</d>
            |  <d p="2.000,1">TooFewFields</d>
            |  <d p="3.000,1,25,16777215">   </d>
            |</i>
        """.trimMargin()

        val items = DanmakuXmlParser.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(3, items.size)

        assertEquals(1_250L, items[0].timeMs)
        assertEquals("First Line Here", items[0].text)
        assertEquals("#FFFFFF", items[0].color)
        assertEquals(DanmakuMode.SCROLL, items[0].mode)

        assertEquals(8_000L, items[1].timeMs)
        assertEquals("A".repeat(40) + "…", items[1].text)
        assertEquals("#00FFFF", items[1].color)
        assertEquals(DanmakuMode.BOTTOM, items[1].mode)

        assertEquals(12_500L, items[2].timeMs)
        assertEquals("Top Alert", items[2].text)
        assertEquals("#FF6699", items[2].color)
        assertEquals(DanmakuMode.TOP, items[2].mode)
    }

    @Test
    fun xmlParserRejectsXxeEnforcesCapsHandlesTruncationAndDerivesSidecarUrl() {
        val xxe = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <i><d p="1.0,1,25,16777215">&xxe;</d></i>
        """.trimIndent()
        assertTrue(DanmakuXmlParser.parse(ByteArrayInputStream(xxe.toByteArray())).isEmpty())

        // Truncated trailing XML still recovers earlier valid items without throwing
        val truncated = """
            <i>
              <d p="1.0,1,25,16777215">Valid1</d>
              <d p="2.0,1,25,16777215">Valid2</d>
              <d p="3.0,1,25,16777215">Unterminated
        """.trimIndent()
        val recovered = DanmakuXmlParser.parse(ByteArrayInputStream(truncated.toByteArray()))
        assertEquals(2, recovered.size)
        assertEquals("Valid1", recovered[0].text)
        assertEquals("Valid2", recovered[1].text)

        // Hard item cap stops streaming early
        val many = buildString {
            append("<i>")
            for (i in 0 until 30) {
                append("<d p=\"${i}.0,1,25,16777215\">M$i</d>")
            }
            append("</i>")
        }
        val capped = DanmakuXmlParser.parse(ByteArrayInputStream(many.toByteArray()), maxItems = 7)
        assertEquals(7, capped.size)

        // Hard byte cap
        val byteCapped = DanmakuXmlParser.parse(ByteArrayInputStream(many.toByteArray()), maxBytes = 64)
        assertTrue(byteCapped.size < 3)

        // Sidecar URL derivation
        assertEquals("file:///sdcard/Movies/demo.xml", DanmakuXmlParser.sidecarXmlUrlFor("file:///sdcard/Movies/demo.mkv"))
        assertEquals(
            "https://nas.local/webdav/ep01.xml?token=abc",
            DanmakuXmlParser.sidecarXmlUrlFor("https://nas.local/webdav/ep01.mp4?token=abc"),
        )
        assertNull(DanmakuXmlParser.sidecarXmlUrlFor("https://nas.local/webdav/ep01.xml"))
        assertNull(DanmakuXmlParser.sidecarXmlUrlFor("https://nas.local/webdav/noext"))
    }

    // ---- 2. Atlas Shelf Allocator (3 tiers, release coalescing, null on full) ----

    @Test
    fun atlasAllocatorCoalescesReleasedSpansAndReturnsNullWhenFull() {
        val atlas = DanmakuAtlasAllocator(atlasWidth = 512, atlasHeight = 256, gutterPx = 2)
        val s1 = atlas.allocate(200, 50)
        val s2 = atlas.allocate(200, 50)
        assertNotNull(s1)
        assertNotNull(s2)
        assertEquals(s1!!.shelfIndex, s2!!.shelfIndex)
        assertEquals(202, s2.x)

        // Release both adjacent slots and verify coalescing permits a single 450px allocation on the same shelf
        atlas.release(s1)
        atlas.release(s2)
        assertEquals(0, atlas.activeSlotCount)

        val merged = atlas.allocate(450, 50)
        assertNotNull(merged)
        assertEquals(s1.shelfIndex, merged!!.shelfIndex)
        assertEquals(0, merged.x)

        // Exhaust all shelves cleanly -> returns null without crashing
        val smallAtlas = DanmakuAtlasAllocator(atlasWidth = 256, atlasHeight = 60, gutterPx = 2)
        assertNotNull(smallAtlas.allocate(250, 50))
        assertNull(smallAtlas.allocate(250, 50))
        smallAtlas.clear()
        assertEquals(0, smallAtlas.activeSlotCount)
        assertNotNull(smallAtlas.allocate(250, 50))
    }

    // ---- 3. Segmented Window Fetcher (60 s windows, 10 s prefetch, LRU, seek cancel, retry, merge) ----

    @Test
    fun windowFetcherSegmentedPrefetchLruSeekCancelFailureRetryAndDuplicateMerge() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = ArrayList<Pair<Long, Long>>()
        val delivered = ArrayList<Pair<Int, List<DanmakuRawItem>>>()
        val failEvents = ArrayList<Boolean>()

        val fetcher = DanmakuWindowFetcher(
            ioDispatcher = dispatcher,
            windowMs = 60_000L,
            prefetchLeadMs = 10_000L,
            maxCachedWindows = 2,
            maxConcurrentFetches = 2,
            backoffScheduleMs = longArrayOf(100L, 200L),
            onWindowReady = { w, items -> delivered.add(w to items) },
            onLoadStateChanged = { f -> failEvents.add(f) },
        )

        val failWindow2Count = AtomicInteger(0)
        val gateWindow5 = CompletableDeferred<Unit>()
        fetcher.setDataSource(
            source = DanmakuDataSource { fromMs, toMs ->
                calls.add(fromMs to toMs)
                val w = (fromMs / 60_000L).toInt()
                if (w == 2 && failWindow2Count.getAndIncrement() < 3) {
                    throw RuntimeException("simulated network error")
                }
                if (w == 5) {
                    gateWindow5.await()
                }
                listOf(
                    DanmakuItem(fromMs + 1_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                    DanmakuItem(fromMs + 2_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                    DanmakuItem(fromMs + 4_500L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                    DanmakuItem(fromMs + 5_500L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL), // 4th inside 5s of 1st (1000..6000)
                    DanmakuItem(fromMs + 12_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL), // >5s later: separate
                    DanmakuItem(fromMs + 13_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL), // only 2 -> unmerged
                )
            },
            mergeDuplicates = true,
        )

        // At t=10s: only window 0 fetched (not yet within 10s lead of window 1)
        fetcher.ensureWindowsFor(10_000L)
        advanceUntilIdle()
        assertEquals(listOf(0L to 60_000L), calls)
        val win0 = fetcher.getCachedWindow(0)!!
        assertEquals(3, win0.size)
        assertEquals("哈哈哈 ×4", win0[0].displayText)
        assertEquals(4, win0[0].repeatCount)
        assertEquals("哈哈哈", win0[1].displayText)
        assertEquals(1, win0[1].repeatCount)

        // At t=49s: still 11s left -> no prefetch yet
        fetcher.ensureWindowsFor(49_000L)
        advanceUntilIdle()
        assertEquals(1, calls.size)

        // At t=50s: 10s lead triggers prefetch of window 1 [60_000, 120_000)
        fetcher.ensureWindowsFor(50_000L)
        advanceUntilIdle()
        assertEquals(2, calls.size)
        assertEquals(60_000L to 120_000L, calls[1])
        assertNotNull(fetcher.getCachedWindow(1))

        // Move to window 2 (fails all 3 attempts -> isLoadFailed = true, playback unaffected)
        fetcher.ensureWindowsFor(125_000L)
        advanceUntilIdle()
        assertTrue(fetcher.isLoadFailed)
        assertTrue(failEvents.last())

        // Retry succeeds on next call -> isLoadFailed flips back to false & LRU evicts window 0 (cap=2)
        fetcher.retryCurrentWindow()
        advanceUntilIdle()
        assertFalse(fetcher.isLoadFailed)
        assertNotNull(fetcher.getCachedWindow(2))
        assertNull(fetcher.getCachedWindow(0)) // evicted by 2-window LRU

        // Seek-cancellation test: start window 5 (suspended), seek to window 3, then release window 5
        fetcher.onSeek(305_000L) // starts window 5
        testScheduler.runCurrent()
        fetcher.onSeek(185_000L) // cancels window 5, fetches window 3
        gateWindow5.complete(Unit)
        advanceUntilIdle()
        assertNull(fetcher.getCachedWindow(5))
        assertNotNull(fetcher.getCachedWindow(3))

        fetcher.close()
    }

    // ---- 4. Engine Track Geometry, +18° Ceiling, 6.5° Subtitle Zone & 12°/s Speed Cap ----

    @Test
    fun engineTrackCountsCeilingSubtitleZoneAndSpeedCapMatchSpec610() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = false, StereoSourceFormat.AUTO, 0.0f)

        // §6.10 exact table on 50° 16:9 medium (1.4°): FULL = 11, HALF = 7, QUARTER = 3
        engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, sizeTier = TextSizeTier.MEDIUM))
        val full = engine.computeLayoutMetrics()
        assertEquals(11, full.scrollTrackCount)
        assertEquals(3, full.bottomTrackCount)
        assertEquals(0.0f, full.topSkipDeg, 1e-4f)
        // Bottom of FULL area strictly excludes 6.5° subtitle zone
        assertEquals(full.screenVFovDeg - 6.5f, full.areaBottomDeg, 1e-3f)

        engine.updateSettings(DanmakuSettings(area = DanmakuArea.HALF, sizeTier = TextSizeTier.MEDIUM))
        val half = engine.computeLayoutMetrics()
        assertEquals(7, half.scrollTrackCount)
        assertEquals(0.50f, half.areaBotFrac, 1e-3f)

        engine.updateSettings(DanmakuSettings(area = DanmakuArea.QUARTER, sizeTier = TextSizeTier.MEDIUM))
        val quarter = engine.computeLayoutMetrics()
        assertEquals(3, quarter.scrollTrackCount)
        assertEquals(0.25f, quarter.areaBotFrac, 1e-3f)

        // Giant screen (80° 16:9, vFov = 45°, half = 22.5° > 18° ceiling) + FAST speed (80/6 = 13.33 -> capped 12°/s)
        engine.updateScreenContext(80.0f, 16.0f / 9.0f, 3.0f, stereoActive = false, StereoSourceFormat.AUTO, 0.0f)
        engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, speed = DanmakuSpeed.FAST))
        val giant = engine.computeLayoutMetrics()
        assertEquals(4.5f, giant.topSkipDeg, 1e-3f)
        assertEquals(12.0f, giant.speedDegPerSec, 1e-4f)

        // 60% width truncation guard
        val ultraLong = "弹".repeat(38)
        val (fitText, fitW) = DanmakuDepthMath.fitToMaxWidthDeg(
            ultraLong, 1.4f, 50.0f * 0.60f, HeuristicDanmakuMeasurer,
        )
        assertTrue(fitText.endsWith("…"))
        assertTrue(fitW <= 50.0f * 0.60f + 1e-3f)
    }

    // ---- 5. Collision Avoidance, Density Cap, 1 s Wait Queue & Drop, Full Atlas Degradation ----

    @Test
    fun engineEnforcesZeroSameTrackCollisionDensityCapWaitQueueAndFullAtlas() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = false, StereoSourceFormat.AUTO, 0.0f)
        engine.updateSettings(
            DanmakuSettings(
                area = DanmakuArea.FULL,
                density = DanmakuDensity.SPARSE, // maxOnScreen = 20, gap = 4 chars
                speed = DanmakuSpeed.NORMAL,
            ),
        )

        // Inject 35 simultaneous scroll items across 11 tracks -> at most 11 fit immediately,
        // remaining wait up to 1,000 ms and fill freed tracks or drop cleanly with zero overlap.
        val burst = (1..35).map { i ->
            DanmakuRawItem(id = i.toLong(), timeMs = 100L, text = "测试弹幕$i", mode = DanmakuMode.SCROLL)
        }
        engine.ingestWindowItems(burst, windowIndex = 0)
        engine.step(0L)

        val metrics = engine.computeLayoutMetrics()
        val gapNorm = metrics.gapDeg / metrics.screenFovDeg

        // Step 30 times at 100 ms intervals (3 seconds real time)
        for (stepIdx in 1..30) {
            clock.advance(100L)
            engine.step(stepIdx * 100L)

            // Density cap strictly respected
            assertTrue("active ${engine.activeItems.size} > 20", engine.activeItems.size <= 20)

            // Zero collision on every track: sorted by xStart (head), each older item's tail + gap <= newer item's head
            val byTrack = engine.activeItems
                .filter { it.mode == DanmakuMode.SCROLL }
                .groupBy { it.trackIndex }
            for ((track, itemsOnTrack) in byTrack) {
                val sorted = itemsOnTrack.sortedBy { it.xNormAt(clock.nowMs) }
                for (k in 0 until sorted.size - 1) {
                    val prevTail = sorted[k].tailNormAt(clock.nowMs)
                    val nextHead = sorted[k + 1].xNormAt(clock.nowMs)
                    assertTrue(
                        "Collision on track $track at t=${clock.nowMs}: prevTail=$prevTail, nextHead=$nextHead, gap=$gapNorm",
                        prevTail + gapNorm - 1e-3f <= nextHead,
                    )
                }
            }
        }
        assertTrue("Overflow items waiting > 1s must drop cleanly", engine.totalDroppedCount > 0)

        // Verify track -> depth band cycle: 0=FAR, 1=MID, 2=NEAR, 3=MID, 4=FAR
        assertEquals(DanmakuLayerBand.FAR, DanmakuLayerBand.forScrollTrack(0))
        assertEquals(DanmakuLayerBand.MID, DanmakuLayerBand.forScrollTrack(1))
        assertEquals(DanmakuLayerBand.NEAR, DanmakuLayerBand.forScrollTrack(2))
        assertEquals(DanmakuLayerBand.MID, DanmakuLayerBand.forScrollTrack(3))
        assertEquals(DanmakuLayerBand.FAR, DanmakuLayerBand.forScrollTrack(4))

        // Full atlas degradation: tiny 1-shelf SMALL atlas holds 1 slot, 2nd item waits and drops without crashing
        val tinyClock = VirtualClock(0L)
        val tinyEngine = DanmakuEngine(tinyClock, atlas = DanmakuAtlasAllocator(atlasWidth = 650, atlasHeight = 60))
        tinyEngine.updateSettings(DanmakuSettings(sizeTier = TextSizeTier.SMALL))
        tinyEngine.ingestWindowItems(
            listOf(
                DanmakuRawItem(1L, 100L, "第一条很长的弹幕文本", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(2L, 100L, "第二条很长的弹幕文本", mode = DanmakuMode.SCROLL),
            ),
            windowIndex = 0,
        )
        tinyEngine.step(0L)
        tinyClock.advance(100L)
        tinyEngine.step(100L)
        assertEquals(1, tinyEngine.activeItems.size)
        repeat(6) { idx ->
            tinyClock.advance(200L)
            tinyEngine.step(100L + (idx + 1) * 200L)
        }
        assertEquals(1, tinyEngine.totalDroppedCount)
    }

    // ---- 6. Top / Bottom Fixed Danmaku (4 s stay, 150 ms fade, NEAR band, max 3 bottom rows) ----

    @Test
    fun engineTopAndBottomStay4sOnNearBandWith150msFadeAndMax3BottomRows() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL))
        engine.ingestWindowItems(
            listOf(
                DanmakuRawItem(1L, 100L, "顶部置顶", mode = DanmakuMode.TOP),
                DanmakuRawItem(2L, 100L, "底部1", mode = DanmakuMode.BOTTOM),
                DanmakuRawItem(3L, 100L, "底部2", mode = DanmakuMode.BOTTOM),
                DanmakuRawItem(4L, 100L, "底部3", mode = DanmakuMode.BOTTOM),
                DanmakuRawItem(5L, 100L, "底部4溢出", mode = DanmakuMode.BOTTOM),
            ),
            windowIndex = 0,
        )
        engine.step(0L)
        clock.advance(100L)
        engine.step(100L)

        val bottoms = engine.activeItems.filter { it.mode == DanmakuMode.BOTTOM }
        val tops = engine.activeItems.filter { it.mode == DanmakuMode.TOP }
        assertEquals(1, tops.size)
        assertEquals(3, bottoms.size) // strictly capped at 3 rows
        assertEquals(DanmakuLayerBand.NEAR, tops[0].layerBand)
        assertEquals(DanmakuLayerBand.NEAR, bottoms[0].layerBand)
        assertEquals(4_000L, tops[0].durationRealMs)
        assertEquals(150L, tops[0].fadeMs)
        assertEquals(0.0f, tops[0].velNormPerSec, 1e-6f)
        // Bottom rows stack upward: track 0 is lowest (largest yNorm), track 2 is highest
        assertTrue(bottoms[0].yNormInArea > bottoms[1].yNormInArea)
        assertTrue(bottoms[1].yNormInArea > bottoms[2].yNormInArea)

        // At t=4,050ms still alive; at t=4,150ms expired & recycled
        for (i in 1..19) {
            clock.advance(200L)
            engine.step(100L + i * 200L)
        }
        assertTrue(engine.activeItems.any { it.mode == DanmakuMode.TOP })
        clock.advance(250L)
        engine.step(4_200L)
        assertTrue(engine.activeItems.isEmpty())
        assertEquals(0, engine.atlas.activeSlotCount)
    }

    // ---- 7. Pause, Playback Speed (2x), Seek (Zero Backfill), Episode Reset & Quality Refresh ----

    @Test
    fun engineHandlesPause2xSpeedSeekZeroBackfillEpisodeSwitchAndQualityRefresh() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.updateSettings(DanmakuSettings(area = DanmakuArea.HALF, speed = DanmakuSpeed.NORMAL))
        engine.ingestWindowItems(
            listOf(
                DanmakuRawItem(1L, 1_000L, "第一条", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(2L, 3_000L, "2倍速触发", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(3L, 20_000L, "跳过不补发", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(4L, 60_000L, "跳转后第一条", mode = DanmakuMode.SCROLL),
            ),
            windowIndex = 0,
        )
        engine.step(0L)

        // Advance 1s wall clock, 1s media -> item 1 spawns
        for (i in 1..5) {
            clock.advance(200L)
            engine.step(i * 200L)
        }
        assertEquals(1, engine.activeItems.size)
        val xBeforePause = engine.activeItems[0].xNormAt(engine.currentTimelineRealMs)

        // Pause for 10 seconds real time -> item X & timelineRealMs must stay completely frozen
        engine.setPaused(true)
        clock.advance(10_000L)
        engine.step(1_000L)
        assertEquals(1, engine.activeItems.size)
        assertEquals(xBeforePause, engine.activeItems[0].xNormAt(engine.currentTimelineRealMs), 1e-6f)

        // Resume at 2x playback speed: 1,000 ms wall clock -> +2,000 ms media (reaches 3,000 ms)
        engine.setPaused(false)
        for (i in 1..5) {
            clock.advance(200L)
            engine.step(1_000L + i * 400L)
        }
        assertEquals(2, engine.activeItems.size) // item 2 spawned at media 3,000 ms
        // Item 1 scrolled at normal real-time velocity (1/8 screen width in 1s), NOT 2x speed!
        val expectedXAfter1s = xBeforePause - (1.0f / 8.0f)
        assertEquals(expectedXAfter1s, engine.activeItems[0].xNormAt(engine.currentTimelineRealMs), 0.01f)

        // Quality refresh (no reset): re-ingesting same windowIndex does NOT duplicate items
        engine.ingestWindowItems(
            listOf(
                DanmakuRawItem(101L, 1_000L, "第一条", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(104L, 60_000L, "跳转后第一条", mode = DanmakuMode.SCROLL),
            ),
            windowIndex = 0,
        )
        assertEquals(2, engine.activeItems.size) // current active items uninterrupted

        // Seek to 59,000 ms: clears active items, ZERO backfill of 20,000 ms item, plays 60,000 ms item next
        engine.onSeek(59_000L)
        assertTrue(engine.activeItems.isEmpty())
        assertEquals(0, engine.atlas.activeSlotCount)
        clock.advance(200L)
        engine.step(60_100L)
        assertEquals(1, engine.activeItems.size)
        assertEquals("跳转后第一条", engine.activeItems[0].displayText)

        // Episode switch clears everything completely
        engine.resetForNewItem(0L)
        assertTrue(engine.activeItems.isEmpty())
        assertEquals(0, engine.atlas.activeSlotCount)
        clock.advance(200L)
        engine.step(60_500L)
        assertTrue(engine.activeItems.isEmpty())
    }

    // ---- 8. Block Mask Immediate Removal, Layered Depth (2D/SBS/AI-3D), User Drag Anim & Preview ----

    @Test
    fun engineBlockMaskLayeredDepthAndPreviewWorkAcross2dSbsAndAi3d() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock, previewStrings = DanmakuPreviewStrings("远", "中", "近"))
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = false, StereoSourceFormat.AUTO, 0.0f)
        engine.updateSettings(DanmakuSettings(floatAuto = true, depthLayer = DepthLayerLevel.MEDIUM))

        // 2D mode: contentPhi = 0 -> Far=0.10°, Mid=0.25°, Near=0.40°
        val bands2d = engine.computeCurrentDepthBands()
        assertEquals(0.10f, bands2d.farPhiDeg, 1e-4f)
        assertEquals(0.25f, bands2d.midPhiDeg, 1e-4f)
        assertEquals(0.40f, bands2d.nearPhiDeg, 1e-4f)

        // Native SBS: contentPhi = 0.25 -> Far=0.35°, Mid=0.50°, Near=0.65°
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = true, StereoSourceFormat.SBS, 0.0f)
        val bandsSbs = engine.computeCurrentDepthBands()
        assertEquals(0.35f, bandsSbs.farPhiDeg, 1e-4f)
        assertEquals(0.50f, bandsSbs.midPhiDeg, 1e-4f)
        assertEquals(0.65f, bandsSbs.nearPhiDeg, 1e-4f)

        // AI 2D-to-3D with measured contentPhi = 0.70° at 3.0 m:
        // farRaw = 0.80°, delta = 0.30°, maxPhi(3.0m) = 1.00° -> delta compressed to 0.20° -> Far=0.80, Mid=0.90, Near=1.00
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = true, StereoSourceFormat.AUTO, 0.70f)
        val bandsAi = engine.computeCurrentDepthBands()
        assertEquals(0.80f, bandsAi.farPhiDeg, 1e-3f)
        assertEquals(0.90f, bandsAi.midPhiDeg, 1e-3f)
        assertEquals(1.00f, bandsAi.nearPhiDeg, 1e-3f)
        assertTrue(bandsAi.nearDistM >= 1.599f)

        // Spawn 2 items (white scroll + colored top) and verify:
        // - contentPhi change alone does NOT mutate existing item fixedPhiDeg
        // - user dragging Float/Layer triggers animateDepthTransition=true and updates fixedPhiDeg
        // - toggling COLOR block mask removes colored item immediately
        engine.ingestWindowItems(
            listOf(
                DanmakuRawItem(1L, 100L, "白色滚动", colorRgb = 0xFFFFFF, mode = DanmakuMode.SCROLL),
                DanmakuRawItem(2L, 100L, "彩色顶部", colorRgb = 0xFF6699, mode = DanmakuMode.TOP),
            ),
            windowIndex = 0,
        )
        engine.step(0L)
        clock.advance(100L)
        engine.step(100L)
        assertEquals(2, engine.activeItems.size)
        val origScrollPhi = engine.activeItems[0].fixedPhiDeg

        // Scene depth change does NOT push live items around
        engine.updateContentPhi(0.10f)
        val resNoAnim = engine.step(150L)
        assertFalse(resNoAnim.animateDepthTransition)
        assertEquals(origScrollPhi, engine.activeItems[0].fixedPhiDeg, 1e-5f)

        // User drags Float to fixed flat (0°) -> triggers 200 ms animation on existing items
        engine.updateSettings(
            engine.currentSettings.copy(floatAuto = false, floatFixedSteps = 0, depthLayer = DepthLayerLevel.OFF),
        )
        val resAnim = engine.step(150L)
        assertTrue(resAnim.animateDepthTransition)
        assertEquals(0.0f, engine.activeItems[0].fixedPhiDeg, 1e-5f)

        // Block COLOR removes colored top immediately
        engine.updateSettings(engine.currentSettings.copy(blockMask = DanmakuBlockFlags.COLOR))
        assertEquals(1, engine.activeItems.size)
        assertEquals("白色滚动", engine.activeItems[0].displayText)

        // Pack instances produces valid flat array matching DANMAKU_INSTANCE_FLOATS
        val packed = FloatArray(XrShared.DANMAKU_MAX_ITEMS * XrShared.DANMAKU_INSTANCE_FLOATS)
        assertEquals(1, engine.packInstances(packed))

        // Preview mode when paused/empty spawns 3 stationary FAR/MID/NEAR items and clears on close
        engine.onSeek(10_000L)
        engine.setPaused(true)
        engine.setPreviewRequested(true)
        val prevStep = engine.step(10_000L)
        assertEquals(3, prevStep.activeCount)
        assertEquals(
            listOf(DanmakuLayerBand.FAR, DanmakuLayerBand.MID, DanmakuLayerBand.NEAR),
            engine.activeItems.map { it.layerBand },
        )
        engine.setPreviewRequested(false)
        assertTrue(engine.activeItems.isEmpty())
    }

    // ---- 9. PaletteStore Persistence of New G4 Danmaku Prefs ----

    @Test
    fun paletteStoreRoundTripsG4DanmakuDensityMergeAndCurvaturePrefs() {
        val store = MapPrefsStore()
        val saved = GlobalPaletteSettings(
            danmakuOn = true,
            danmakuArea = DanmakuArea.FULL,
            danmakuOpacityPct = 65,
            danmakuSize = TextSizeTier.LARGE,
            danmakuSpeed = DanmakuSpeed.FAST,
            danmakuBlockMask = DanmakuBlockFlags.TOP or DanmakuBlockFlags.COLOR,
            danmakuDensity = DanmakuDensity.DENSE,
            danmakuMergeDuplicates = false,
            danmakuCurvatureMode = DanmakuCurvatureMode.CUSTOM,
            danmakuCustomCurvaturePct = 37, // ScreenReadout.CURVATURE_STEP = 5 -> quantizes to 35
        )
        PaletteStore.saveGlobal(store, saved)
        val loaded = PaletteStore.readGlobal(store)

        assertEquals(DanmakuArea.FULL, loaded.danmakuArea)
        assertEquals(65, loaded.danmakuOpacityPct)
        assertEquals(TextSizeTier.LARGE, loaded.danmakuSize)
        assertEquals(DanmakuSpeed.FAST, loaded.danmakuSpeed)
        assertEquals(DanmakuBlockFlags.TOP or DanmakuBlockFlags.COLOR, loaded.danmakuBlockMask)
        assertEquals(DanmakuDensity.DENSE, loaded.danmakuDensity)
        assertFalse(loaded.danmakuMergeDuplicates)
        assertEquals(DanmakuCurvatureMode.CUSTOM, loaded.danmakuCurvatureMode)
        assertEquals(35, loaded.danmakuCustomCurvaturePct)
    }

    @Test
    fun engineIs100PercentDeterministicUnderVirtualClock() {
        fun runSim(): Pair<Int, FloatArray> {
            val clock = VirtualClock(0L)
            val engine = DanmakuEngine(clock)
            engine.updateScreenContext(55.0f, 16.0f / 9.0f, 3.0f, stereoActive = true, StereoSourceFormat.AUTO, 0.35f)
            engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, density = DanmakuDensity.DENSE))
            val items = (1..40).map { i ->
                DanmakuRawItem(
                    id = i.toLong(),
                    timeMs = (i * 80L),
                    text = "弹幕条目#$i",
                    colorRgb = if (i % 4 == 0) 0xFFAA00 else 0xFFFFFF,
                    mode = when {
                        i % 11 == 0 -> DanmakuMode.TOP
                        i % 13 == 0 -> DanmakuMode.BOTTOM
                        else -> DanmakuMode.SCROLL
                    },
                )
            }
            engine.ingestWindowItems(items, windowIndex = 0)
            engine.step(0L)
            for (t in 1..25) {
                clock.advance(100L)
                engine.step(t * 100L)
            }
            val buf = FloatArray(XrShared.DANMAKU_MAX_ITEMS * XrShared.DANMAKU_INSTANCE_FLOATS)
            val count = engine.packInstances(buf)
            return count to buf
        }

        val (countA, bufA) = runSim()
        val (countB, bufB) = runSim()
        assertTrue(countA > 10)
        assertEquals(countA, countB)
        for (i in 0 until countA * XrShared.DANMAKU_INSTANCE_FLOATS) {
            assertEquals("Mismatch at float[$i]", bufA[i], bufB[i], 0.0f)
        }
    }
}
