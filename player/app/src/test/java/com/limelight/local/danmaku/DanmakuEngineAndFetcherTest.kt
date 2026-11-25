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
import java.util.concurrent.atomic.AtomicLong

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

        val many = buildString {
            append("<i>")
            for (i in 0 until 30) {
                append("<d p=\"${i}.0,1,25,16777215\">M$i</d>")
            }
            append("</i>")
        }
        assertEquals(7, DanmakuXmlParser.parse(ByteArrayInputStream(many.toByteArray()), maxItems = 7).size)
        assertTrue(DanmakuXmlParser.parse(ByteArrayInputStream(many.toByteArray()), maxBytes = 64).size < 3)

        assertEquals("file:///sdcard/Movies/demo.xml", DanmakuXmlParser.sidecarXmlUrlFor("file:///sdcard/Movies/demo.mkv"))
        assertEquals(
            "https://nas.local/webdav/ep01.xml?token=abc",
            DanmakuXmlParser.sidecarXmlUrlFor("https://nas.local/webdav/ep01.mp4?token=abc"),
        )
        assertNull(DanmakuXmlParser.sidecarXmlUrlFor("https://nas.local/webdav/ep01.xml"))
        assertNull(DanmakuXmlParser.sidecarXmlUrlFor("https://nas.local/webdav/noext"))
    }

    // ---- 2. Text Sanitization (C0/C1/BiDi/ZWSP/FFFC) & Grapheme-Safe ZWJ Emoji Truncation ----

    @Test
    fun sanitizeTextStripsControlBidiZeroWidthAndTruncatesSafelyOnGraphemeClusters() {
        // Strips C0 (\u0000, \u0007), DEL (\u007F), C1 (\u0085), BiDi RLO/LRI (\u202E, \u2066), ZWSP (\u200B), BOM (\uFEFF), U+FFFC
        val dirty = "\uFEFF\u0000Hello\u202E\u2066\u200B\u0007\u007F\u0085\uFFFC World\u200D"
        assertEquals("Hello World", DanmakuDepthMath.sanitizeText(dirty))

        // 45 ZWJ family emojis: truncating at 40 graphemes MUST NOT split any surrogate pair or ZWJ cluster!
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val manyEmojis = family.repeat(45)
        val sanitized = DanmakuDepthMath.sanitizeText(manyEmojis, maxGraphemes = 40)
        assertTrue(sanitized.endsWith("…"))
        val body = sanitized.dropLast(1)
        // Verify ZERO unpaired surrogates anywhere in truncated string
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (Character.isHighSurrogate(ch)) {
                assertTrue("Unpaired high surrogate at $i", i + 1 < body.length && Character.isLowSurrogate(body[i + 1]))
                i += 2
            } else {
                assertFalse("Unpaired low surrogate at $i", Character.isLowSurrogate(ch))
                i++
            }
        }
        // Width truncation via fitToMaxWidthDeg must also stay on grapheme boundaries
        val skinThumbs = "\uD83D\uDC4D\uD83C\uDFFD".repeat(25)
        val (fit, _) = DanmakuDepthMath.fitToMaxWidthDeg(skinThumbs, 1.4f, 15.0f, HeuristicDanmakuMeasurer)
        assertTrue(fit.endsWith("…"))
        val fitBody = fit.dropLast(1)
        assertFalse("Last char before ellipsis must not be unpaired high surrogate", Character.isHighSurrogate(fitBody.last()))
    }

    // ---- 3. Atlas Shelf Allocator (slotIndex + generation + half-texel UV inset + coalescing) ----

    @Test
    fun atlasAllocatorCoalescesSpansIncrementsGenerationsAndInsetsUvsByHalfTexel() {
        val atlas = DanmakuAtlasAllocator(atlasWidth = 512, atlasHeight = 256, gutterPx = 2, maxSlots = 4)
        val s1 = atlas.allocate(200, 50)
        val s2 = atlas.allocate(200, 50)
        assertNotNull(s1)
        assertNotNull(s2)
        assertEquals(s1!!.shelfIndex, s2!!.shelfIndex)
        assertEquals(202, s2.x)
        assertTrue(s1.slotIndex in 0 until 4)
        assertTrue(s2.slotIndex in 0 until 4)
        assertTrue(s2.generation > s1.generation)

        // Verify half-texel UV inset prevents bilinear neighbor bleed
        val invW = 1.0f / XrShared.DANMAKU_ATLAS_W.toFloat()
        val invH = 1.0f / XrShared.DANMAKU_ATLAS_H.toFloat()
        assertEquals((s1.x + 0.5f) * invW, s1.u0, 1e-7f)
        assertEquals((s1.width - 1.0f) * invW, s1.du, 1e-7f)
        assertEquals((s1.y + 0.5f) * invH, s1.v0, 1e-7f)
        assertEquals((s1.height - 1.0f) * invH, s1.dv, 1e-7f)

        // Release both and verify coalescing + strictly higher generation on slot reuse
        atlas.release(s1)
        atlas.release(s2)
        assertEquals(0, atlas.activeSlotCount)

        val merged = atlas.allocate(450, 50)
        assertNotNull(merged)
        assertEquals(s1.shelfIndex, merged!!.shelfIndex)
        assertEquals(0, merged.x)
        assertTrue("Reused slot must carry strictly newer generation", merged.generation > s2.generation)

        // Exhaust shelves cleanly -> returns null without crashing
        val smallAtlas = DanmakuAtlasAllocator(atlasWidth = 256, atlasHeight = 60, gutterPx = 2)
        assertNotNull(smallAtlas.allocate(250, 50))
        assertNull(smallAtlas.allocate(250, 50))
        smallAtlas.clear()
        assertEquals(0, smallAtlas.activeSlotCount)
        val afterClear = smallAtlas.allocate(250, 50)
        assertNotNull(afterClear)
        assertTrue("Generation keeps advancing monotonically across clear()", afterClear!!.generation > 1)
    }

    // ---- 4. Must-Fix #1 Regression: Un-uploaded / Rejected Patch Gating & Per-Tick Spawn Throttling ----

    @Test
    fun packInstancesExcludesUnrasterizedItemsAndStepThrottlesBurstSpawnsToQueueCapacity() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, density = DanmakuDensity.DENSE))

        // Inject a 40-item burst at t=100ms, throttle per-tick spawns to 6 (matching native upload ring)
        val burst = (1..40).map { i ->
            DanmakuRawItem(id = i.toLong(), timeMs = 100L, text = "突发弹幕#$i", mode = DanmakuMode.SCROLL)
        }
        engine.ingestWindowItems(burst, windowIndex = 0)
        engine.step(0L)

        clock.advance(100L)
        val step1 = engine.step(100L, maxNewSpawns = 6)
        assertEquals("Per-tick spawn cap must throttle burst to 6", 6, step1.newlySpawned.size)
        assertEquals(6, engine.activeItems.size)

        // Before worker uploads any patches (rasterized == false), packInstances MUST emit 0 items!
        val buf = FloatArray(XrShared.DANMAKU_MAX_ITEMS * XrShared.DANMAKU_INSTANCE_FLOATS)
        assertEquals(0, engine.packInstances(buf))

        // Simulate native upload queue accepting only first 4 patches and rejecting #5 & #6:
        for (i in 0 until 4) {
            engine.activeItems[i].rasterized = true
        }
        assertEquals("Only accepted/uploaded items may enter GPU snapshot", 4, engine.packInstances(buf))
        // Verify slotIndex & slotGen are packed at indices [18] and [19]
        val slot0 = engine.activeItems[0].atlasSlot!!
        assertEquals(slot0.slotIndex.toFloat(), buf[18], 0.0f)
        assertEquals(slot0.generation.toFloat(), buf[19], 0.0f)

        // Next tick retries rejected #5 & #6 and spawns next throttled batch
        for (item in engine.activeItems) {
            if (!item.rasterized) item.rasterized = true
        }
        clock.advance(50L)
        val step2 = engine.step(150L, maxNewSpawns = 5)
        assertEquals(5, step2.newlySpawned.size)
        assertEquals(6, engine.packInstances(buf)) // newly spawned 5 are still unrasterized until uploaded
    }

    // ---- 5. Window Fetcher: 60 s Windows, 10 s Prefetch, LRU, Seek Cancel, Busy, Pause, Cooldown Auto-Retry ----

    @Test
    fun windowFetcherHandlesPrefetchLruSeekCancelBusyPauseCooldownAutoRetryAndMerge() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeClockMs = AtomicLong(1_000L)
        val calls = ArrayList<Pair<Long, Long>>()
        val failEvents = ArrayList<Boolean>()

        val fetcher = DanmakuWindowFetcher(
            ioDispatcher = dispatcher,
            clockMs = { fakeClockMs.get() },
            windowMs = 60_000L,
            prefetchLeadMs = 10_000L,
            maxCachedWindows = 2,
            defaultMaxConcurrentFetches = 1,
            backoffScheduleMs = longArrayOf(50L, 100L),
            busyRetryDelayMs = 25L,
            failedCooldownMs = 30_000L,
            onLoadStateChanged = { f -> failEvents.add(f) },
        )

        val busyOnce = AtomicInteger(0)
        val failWindow2Count = AtomicInteger(0)
        val gateWindow5 = CompletableDeferred<Unit>()
        fetcher.setDataSource(
            source = DanmakuDataSource { fromMs, toMs ->
                calls.add(fromMs to toMs)
                val w = (fromMs / 60_000L).toInt()
                if (w == 0 && busyOnce.getAndIncrement() == 0) {
                    return@DanmakuDataSource DanmakuFetchResult.Busy
                }
                if (w == 2 && failWindow2Count.getAndIncrement() < 3) {
                    throw RuntimeException("simulated network error")
                }
                if (w == 5) {
                    gateWindow5.await()
                }
                DanmakuFetchResult.Loaded(
                    listOf(
                        DanmakuItem(fromMs + 1_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                        DanmakuItem(fromMs + 2_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                        DanmakuItem(fromMs + 4_500L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                        DanmakuItem(fromMs + 5_500L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                        DanmakuItem(fromMs + 12_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                        DanmakuItem(fromMs + 13_000L, "哈哈哈", "#FFFFFF", DanmakuMode.SCROLL),
                    ),
                )
            },
            mergeDuplicates = true,
            maxConcurrency = 1,
        )

        // Fetch paused during !ready / refreshInFlight -> MUST NOT launch any fetch!
        fetcher.setFetchPaused(true)
        fetcher.ensureWindowsFor(10_000L)
        advanceUntilIdle()
        assertTrue("No fetch allowed while fetchPaused", calls.isEmpty())

        // Unpause -> window 0 gets Busy once (must NOT mark failed!), reschedules, and loads cleanly
        fetcher.setFetchPaused(false)
        advanceUntilIdle()
        assertFalse("Busy must never mark load failed", fetcher.isLoadFailed)
        val win0 = fetcher.getCachedWindow(0)!!
        assertEquals(3, win0.size)
        assertEquals("哈哈哈 ×4", win0[0].displayText)

        // At t=49s no prefetch; at t=50s prefetches window 1
        val callsBeforePrefetch = calls.size
        fetcher.ensureWindowsFor(49_000L)
        advanceUntilIdle()
        assertEquals(callsBeforePrefetch, calls.size)

        fetcher.ensureWindowsFor(50_000L)
        advanceUntilIdle()
        assertNotNull(fetcher.getCachedWindow(1))

        // Window 2 fails 3 attempts -> isLoadFailed = true
        fetcher.ensureWindowsFor(125_000L)
        advanceUntilIdle()
        assertTrue(fetcher.isLoadFailed)
        assertTrue(failEvents.last())

        // Before 30 s cooldown elapses: stays failed without hammering plugin
        fakeClockMs.addAndGet(15_000L)
        val callsWhileCooling = calls.size
        fetcher.ensureWindowsFor(126_000L)
        advanceUntilIdle()
        assertEquals(callsWhileCooling, calls.size)
        assertTrue(fetcher.isLoadFailed)

        // After 30 s cooldown elapses: AUTOMATICALLY retries & recovers + LRU evicts window 0!
        fakeClockMs.addAndGet(20_000L)
        fetcher.ensureWindowsFor(127_000L)
        advanceUntilIdle()
        assertFalse("Must auto-recover after cooldown", fetcher.isLoadFailed)
        assertNotNull(fetcher.getCachedWindow(2))
        assertNull(fetcher.getCachedWindow(0))

        // Seek cancels in-flight window 5 and discards late result
        fetcher.onSeek(305_000L)
        testScheduler.runCurrent()
        fetcher.onSeek(185_000L)
        gateWindow5.complete(Unit)
        advanceUntilIdle()
        assertNull(fetcher.getCachedWindow(5))
        assertNotNull(fetcher.getCachedWindow(3))

        fetcher.close()
    }

    // ---- 6. Must-Fix #2 & #3 Regression: Forward Jump Zero Backfill + LocalDanmakuPlaybackBridge ----

    @Test
    fun engineUnannouncedForwardJumpTreatsAsSeekWithZeroBackfill() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.ingestWindowItems(
            listOf(
                DanmakuRawItem(1L, 1_000L, "正常第1秒", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(2L, 10_000L, "跳过第10秒", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(3L, 25_000L, "跳过第25秒", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(4L, 40_000L, "跳过第40秒", mode = DanmakuMode.SCROLL),
                DanmakuRawItem(5L, 46_000L, "跳转后正常出现", mode = DanmakuMode.SCROLL),
            ),
            windowIndex = 0,
        )
        engine.step(0L)
        for (i in 1..10) {
            clock.advance(200L)
            engine.step(i * 200L)
        }
        assertEquals(1, engine.activeItems.size)

        // Sudden unannounced forward jump from 2,000 ms to 45,000 ms (WITHOUT calling onSeek!)
        clock.advance(200L)
        val jumpStep = engine.step(45_000L)
        assertTrue("Forward jump must reset native clock anchor", jumpStep.clockReset)
        assertTrue("Forward jump must drop old active items and NEVER backfill 10s/25s/40s!", engine.activeItems.isEmpty())

        // Normal playback after 45,000 ms spawns the 46,000 ms item cleanly
        for (i in 1..6) {
            clock.advance(200L)
            engine.step(45_000L + i * 200L)
        }
        assertEquals(1, engine.activeItems.size)
        assertEquals("跳转后正常出现", engine.activeItems[0].displayText)
    }

    @Test
    fun playbackBridgeHandlesResume40m30sBufferingPauseInPlaceRefreshZeroJumpAndScrubbing() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        var fetchPaused = false
        val seekLog = ArrayList<Long>()

        val sink = object : DanmakuPlaybackSink {
            override fun setFetchPaused(paused: Boolean) {
                fetchPaused = paused
            }
            override fun setPausedWithoutPosition() {
                engine.setPaused(true)
            }
            override fun updatePlaybackPosition(mediaTimeMs: Long, playing: Boolean, playbackSpeed: Float) {
                engine.setPlaybackSpeed(playbackSpeed)
                engine.setPaused(!playing)
                engine.step(mediaTimeMs)
            }
            override fun onSeek(targetMs: Long) {
                seekLog.add(targetMs)
                engine.onSeek(targetMs)
            }
            override fun setScrubbing(scrubbing: Boolean) {
                engine.setScrubbing(scrubbing)
            }
        }
        val bridge = LocalDanmakuPlaybackBridge(sink)

        // Pre-populate window 0 (0:00-1:00) AND window 40 (40:00-41:00, 2_400_000..2_460_000)
        val win0Items = listOf(
            DanmakuRawItem(1L, 1_000L, "片头0:01不该补发", windowIndex = 0),
            DanmakuRawItem(2L, 5_000L, "片头0:05不该补发", windowIndex = 0),
        )
        val win40Items = listOf(
            DanmakuRawItem(10L, 2_405_000L, "40:05不该补发", windowIndex = 40),
            DanmakuRawItem(11L, 2_420_000L, "40:20不该补发", windowIndex = 40),
            DanmakuRawItem(12L, 2_431_000L, "40:31续播后第一条", windowIndex = 40),
            DanmakuRawItem(13L, 2_436_000L, "40:36刷新后第一条", windowIndex = 40),
        )
        engine.ingestWindowItems(win0Items, windowIndex = 0)
        engine.ingestWindowItems(win40Items, windowIndex = 40)

        // 1. Loading resume at 40:30 (2,430,000 ms): before STATE_READY, tickBar fires with position=0!
        bridge.onNewItemLoading()
        assertTrue("Fetcher must pause while !ready", fetchPaused)
        assertTrue(engine.isPaused)
        repeat(5) {
            clock.advance(200L)
            bridge.onPlaybackUpdate(
                positionMs = 0L,
                playbackState = LocalDanmakuPlaybackBridge.PLAYBACK_STATE_BUFFERING,
                playWhenReady = true,
                errored = false,
                playbackSpeed = 1.0f,
            )
        }
        assertTrue("Must NEVER spawn window 0 items while !ready!", engine.activeItems.isEmpty())

        // 2. ExoPlayer reaches STATE_READY & seeks to resume point 40:30 (2,430,000 ms)
        bridge.onPlayerReady(2_430_000L, actuallyPlaying = true, playbackSpeed = 1.0f)
        assertFalse("Fetcher unpauses on READY", fetchPaused)
        assertEquals(listOf(2_430_000L), seekLog)
        assertTrue("Must NEVER backfill 40:05 or 40:20 on resume!", engine.activeItems.isEmpty())

        // Advance to 40:31.200 -> 40:31 item spawns normally
        for (i in 1..6) {
            clock.advance(200L)
            bridge.onPlaybackUpdate(
                positionMs = 2_430_000L + i * 200L,
                playbackState = LocalDanmakuPlaybackBridge.PLAYBACK_STATE_READY,
                playWhenReady = true,
                errored = false,
                playbackSpeed = 1.0f,
            )
        }
        assertEquals(1, engine.activeItems.size)
        assertEquals("40:31续播后第一条", engine.activeItems[0].displayText)

        // 3. Mid-playback BUFFERING with playWhenReady=true (§6.5): danmaku MUST freeze in place & NOT disappear!
        bridge.onPlaybackUpdate(
            positionMs = 2_431_200L,
            playbackState = LocalDanmakuPlaybackBridge.PLAYBACK_STATE_BUFFERING,
            playWhenReady = true, // ExoPlayer keeps playWhenReady=true during buffering!
            errored = false,
            playbackSpeed = 1.0f,
        )
        assertTrue("Danmaku engine MUST pause during buffering even when playWhenReady=true", engine.isPaused)
        val xBeforeBuffer = engine.activeItems[0].xNormAt(engine.currentTimelineRealMs)
        repeat(10) {
            clock.advance(200L)
            bridge.onPlaybackUpdate(
                positionMs = 2_431_200L,
                playbackState = LocalDanmakuPlaybackBridge.PLAYBACK_STATE_BUFFERING,
                playWhenReady = true,
                errored = false,
                playbackSpeed = 1.0f,
            )
        }
        assertTrue("Danmaku engine MUST remain paused during buffering", engine.isPaused)
        assertEquals("Buffering must keep active danmaku on screen (§6.5)", 1, engine.activeItems.size)
        assertEquals(xBeforeBuffer, engine.activeItems[0].xNormAt(engine.currentTimelineRealMs), 1e-6f)

        // Resume READY after buffering -> stays at exact frozen X on resume boundary, then moves smoothly leftward
        bridge.onPlaybackUpdate(2_431_200L, LocalDanmakuPlaybackBridge.PLAYBACK_STATE_READY, true, false, 1.0f)
        assertFalse(engine.isPaused)
        assertEquals(xBeforeBuffer, engine.activeItems[0].xNormAt(engine.currentTimelineRealMs), 1e-6f)
        clock.advance(200L)
        bridge.onPlaybackUpdate(2_431_400L, LocalDanmakuPlaybackBridge.PLAYBACK_STATE_READY, true, false, 1.0f)
        assertTrue(engine.activeItems[0].xNormAt(engine.currentTimelineRealMs) < xBeforeBuffer)

        // 4. Real plugin URL/quality refresh simulation: setMediaSource + prepare() drops pos to 0, then seeks back to 40:35!
        bridge.onPluginRefreshStarted()
        assertTrue("Fetcher must pause during refreshInFlight", fetchPaused)
        repeat(4) {
            clock.advance(200L)
            bridge.onPlaybackUpdate(0L, LocalDanmakuPlaybackBridge.PLAYBACK_STATE_BUFFERING, true, false, 1.0f)
        }
        // Re-prepare finishes and seeks back to 40:35 (2,435,000 ms)
        bridge.onPlayerReady(2_435_000L, actuallyPlaying = true, playbackSpeed = 1.0f)
        assertFalse(fetchPaused)
        assertTrue("Refresh 0->40:35 must NEVER backfill 0:00 or 40:05..40:31!", engine.activeItems.isEmpty())
        for (i in 1..6) {
            clock.advance(200L)
            bridge.onPlaybackUpdate(2_435_000L + i * 200L, LocalDanmakuPlaybackBridge.PLAYBACK_STATE_READY, true, false, 1.0f)
        }
        assertEquals(1, engine.activeItems.size)
        assertEquals("40:36刷新后第一条", engine.activeItems[0].displayText)

        // 5. Progress bar drag scrubbing hides active danmaku immediately, then commits clean seek on release
        bridge.onScrubStart()
        assertTrue("Scrubbing must hide active danmaku immediately", engine.activeItems.isEmpty())
        bridge.onScrubEnd(commitTargetMs = 2_435_500L)
        assertEquals(2_435_500L, seekLog.last())
    }

    // ---- 7. Bounded Engine Memory & Per-Window Cap (Dense Video O(1) Memory) ----

    @Test
    fun enginePrunesOutOfRangeWindowsAndCapsItemsPerWindow() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.step(0L)

        // Window 0 with 2,500 items -> capped strictly at MAX_ITEMS_PER_WINDOW (2,000)
        val denseWin0 = (0 until 2_500).map { i ->
            DanmakuRawItem(id = i.toLong() + 1L, timeMs = (i * 20L), text = "w0-$i", windowIndex = 0)
        }
        engine.ingestWindowItems(denseWin0, windowIndex = 0)
        assertEquals(DanmakuEngine.MAX_ITEMS_PER_WINDOW, engine.scheduledItemCount)

        // Advance through windows 1, 2, 3, 4; engine must NEVER retain more than [cur-1 .. cur+1]
        for (w in 1..4) {
            val baseMs = w * 60_000L
            engine.onSeek(baseMs)
            val winItems = (0 until 500).map { i ->
                DanmakuRawItem(id = w * 10_000L + i, timeMs = baseMs + i * 100L, text = "w$w-$i", windowIndex = w)
            }
            engine.ingestWindowItems(winItems, windowIndex = w)
            assertTrue(
                "Scheduled count ${engine.scheduledItemCount} exceeded 3-window bound at window $w",
                engine.scheduledItemCount <= 3 * DanmakuEngine.MAX_ITEMS_PER_WINDOW,
            )
        }
        // At window 4, windows 0, 1, 2 MUST be pruned (only window 3 and 4 = 1,000 items remain!)
        assertEquals(1_000, engine.scheduledItemCount)
    }

    // ---- 8. Track Geometry, Collision, Top/Bottom, Pause/2x, Block Mask, Depth Bands, Store, Determinism ----

    @Test
    fun engineTrackCountsCeilingSubtitleZoneAndSpeedCapMatchSpec610() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = false, StereoSourceFormat.AUTO, 0.0f)

        engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, sizeTier = TextSizeTier.MEDIUM))
        val full = engine.computeLayoutMetrics()
        assertEquals(11, full.scrollTrackCount)
        assertEquals(3, full.bottomTrackCount)
        assertEquals(0.0f, full.topSkipDeg, 1e-4f)
        assertEquals(full.screenVFovDeg - 6.5f, full.areaBottomDeg, 1e-3f)

        engine.updateSettings(DanmakuSettings(area = DanmakuArea.HALF, sizeTier = TextSizeTier.MEDIUM))
        val half = engine.computeLayoutMetrics()
        assertEquals(7, half.scrollTrackCount)
        assertEquals(0.50f, half.areaBotFrac, 1e-3f)

        engine.updateSettings(DanmakuSettings(area = DanmakuArea.QUARTER, sizeTier = TextSizeTier.MEDIUM))
        val quarter = engine.computeLayoutMetrics()
        assertEquals(3, quarter.scrollTrackCount)
        assertEquals(0.25f, quarter.areaBotFrac, 1e-3f)

        engine.updateScreenContext(80.0f, 16.0f / 9.0f, 3.0f, stereoActive = false, StereoSourceFormat.AUTO, 0.0f)
        engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, speed = DanmakuSpeed.FAST))
        val giant = engine.computeLayoutMetrics()
        assertEquals(4.5f, giant.topSkipDeg, 1e-3f)
        assertEquals(12.0f, giant.speedDegPerSec, 1e-4f)
    }

    @Test
    fun engineEnforcesZeroSameTrackCollisionDensityCapWaitQueueAndFullAtlas() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock)
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = false, StereoSourceFormat.AUTO, 0.0f)
        engine.updateSettings(
            DanmakuSettings(area = DanmakuArea.FULL, density = DanmakuDensity.SPARSE, speed = DanmakuSpeed.NORMAL),
        )
        val burst = (1..35).map { i ->
            DanmakuRawItem(id = i.toLong(), timeMs = 100L, text = "测试弹幕$i", mode = DanmakuMode.SCROLL)
        }
        engine.ingestWindowItems(burst, windowIndex = 0)
        engine.step(0L)

        val metrics = engine.computeLayoutMetrics()
        val gapNorm = metrics.gapDeg / metrics.screenFovDeg
        for (stepIdx in 1..30) {
            clock.advance(100L)
            engine.step(stepIdx * 100L)
            assertTrue(engine.activeItems.size <= 20)
            val byTrack = engine.activeItems.filter { it.mode == DanmakuMode.SCROLL }.groupBy { it.trackIndex }
            for ((track, itemsOnTrack) in byTrack) {
                val sorted = itemsOnTrack.sortedBy { it.xNormAt(clock.nowMs) }
                for (k in 0 until sorted.size - 1) {
                    assertTrue(
                        "Collision on track $track",
                        sorted[k].tailNormAt(clock.nowMs) + gapNorm - 1e-3f <= sorted[k + 1].xNormAt(clock.nowMs),
                    )
                }
            }
        }
        assertTrue(engine.totalDroppedCount > 0)

        // Full atlas degradation: tiny 1-shelf SMALL atlas holds 1 slot, 2nd waits then drops cleanly
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

    @Test
    fun engineTopBottomFixedStayFade2xSpeedBlockMaskDepthBandsAndPreview() {
        val clock = VirtualClock(0L)
        val engine = DanmakuEngine(clock, previewStrings = DanmakuPreviewStrings("远", "中", "近"))
        engine.updateScreenContext(50.0f, 16.0f / 9.0f, 3.0f, stereoActive = true, StereoSourceFormat.AUTO, 0.70f)
        engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, floatAuto = true, depthLayer = DepthLayerLevel.MEDIUM))

        // Ceiling compression: content=0.70 -> far=0.80, delta compressed 0.30->0.20 -> near=1.00
        val bandsAi = engine.computeCurrentDepthBands()
        assertEquals(0.80f, bandsAi.farPhiDeg, 1e-3f)
        assertEquals(0.90f, bandsAi.midPhiDeg, 1e-3f)
        assertEquals(1.00f, bandsAi.nearPhiDeg, 1e-3f)
        assertTrue(bandsAi.nearDistM >= 1.599f)

        engine.ingestWindowItems(
            listOf(
                DanmakuRawItem(1L, 100L, "顶部置顶", colorRgb = 0xFF6699, mode = DanmakuMode.TOP),
                DanmakuRawItem(2L, 100L, "底部1", mode = DanmakuMode.BOTTOM),
                DanmakuRawItem(3L, 100L, "底部2", mode = DanmakuMode.BOTTOM),
                DanmakuRawItem(4L, 100L, "底部3", mode = DanmakuMode.BOTTOM),
                DanmakuRawItem(5L, 100L, "底部4溢出", mode = DanmakuMode.BOTTOM),
                DanmakuRawItem(6L, 2_000L, "2倍速触发", mode = DanmakuMode.SCROLL),
            ),
            windowIndex = 0,
        )
        engine.step(0L)
        clock.advance(100L)
        engine.step(100L)
        assertEquals(3, engine.activeItems.count { it.mode == DanmakuMode.BOTTOM })
        assertEquals(1, engine.activeItems.count { it.mode == DanmakuMode.TOP })

        // Block COLOR removes colored top immediately
        engine.updateSettings(engine.currentSettings.copy(blockMask = DanmakuBlockFlags.COLOR))
        assertEquals(0, engine.activeItems.count { it.mode == DanmakuMode.TOP })

        // 2x playback speed advances media 2x faster while NOT tripping forward-jump guard
        engine.setPlaybackSpeed(2.0f)
        for (i in 1..5) {
            clock.advance(200L)
            engine.step(100L + i * 400L)
        }
        assertTrue(engine.activeItems.any { it.displayText == "2倍速触发" })

        // Preview mode when paused/empty spawns 3 stationary FAR/MID/NEAR items
        engine.onSeek(10_000L)
        engine.setPaused(true)
        engine.setPreviewRequested(true)
        val prev = engine.step(10_000L)
        assertEquals(3, prev.activeCount)
        assertEquals(
            listOf(DanmakuLayerBand.FAR, DanmakuLayerBand.MID, DanmakuLayerBand.NEAR),
            engine.activeItems.map { it.layerBand },
        )
        engine.setPreviewRequested(false)
        assertTrue(engine.activeItems.isEmpty())
    }

    @Test
    fun paletteStoreRoundTripsG4DanmakuPrefsAndEngineIsDeterministic() {
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
            danmakuCustomCurvaturePct = 37,
        )
        PaletteStore.saveGlobal(store, saved)
        val loaded = PaletteStore.readGlobal(store)
        assertEquals(DanmakuDensity.DENSE, loaded.danmakuDensity)
        assertFalse(loaded.danmakuMergeDuplicates)
        assertEquals(DanmakuCurvatureMode.CUSTOM, loaded.danmakuCurvatureMode)
        assertEquals(35, loaded.danmakuCustomCurvaturePct)

        fun runSim(): Pair<Int, FloatArray> {
            val clock = VirtualClock(0L)
            val engine = DanmakuEngine(clock)
            engine.updateScreenContext(55.0f, 16.0f / 9.0f, 3.0f, stereoActive = true, StereoSourceFormat.AUTO, 0.35f)
            engine.updateSettings(DanmakuSettings(area = DanmakuArea.FULL, density = DanmakuDensity.DENSE))
            val items = (1..30).map { i ->
                DanmakuRawItem(id = i.toLong(), timeMs = i * 80L, text = "弹幕#$i", mode = DanmakuMode.SCROLL)
            }
            engine.ingestWindowItems(items, windowIndex = 0)
            engine.step(0L)
            for (t in 1..25) {
                clock.advance(100L)
                engine.step(t * 100L)
                engine.activeItems.forEach { it.rasterized = true }
            }
            val buf = FloatArray(XrShared.DANMAKU_MAX_ITEMS * XrShared.DANMAKU_INSTANCE_FLOATS)
            return engine.packInstances(buf) to buf
        }

        val (cA, bA) = runSim()
        val (cB, bB) = runSim()
        assertTrue(cA > 10)
        assertEquals(cA, cB)
        for (i in 0 until cA * XrShared.DANMAKU_INSTANCE_FLOATS) {
            assertEquals(bA[i], bB[i], 0.0f)
        }
    }
}
