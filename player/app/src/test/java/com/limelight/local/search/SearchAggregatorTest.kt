package com.limelight.local.search

import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.Page
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.SourceUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SearchAggregatorTest {

    private class FakeSearchSource(
        override val sourceId: String,
        override val displayName: String,
        override val typeLabel: String = "插件",
        override val knownNeedsAuth: Boolean = false,
        override val supportsSuggest: Boolean = true,
        val onSearch: suspend (query: String, cursor: String?, kind: ItemKind?) -> Page<Item>,
        val onSuggest: suspend (query: String) -> List<String> = { emptyList() },
    ) : SearchableSource {
        override val sourceKind: SourceKind = SourceKind.PLUGIN
        val searchCalls = AtomicInteger(0)
        val cancelledCalls = AtomicInteger(0)

        override suspend fun search(query: String, cursor: String?, kind: ItemKind?): Page<Item> {
            searchCalls.incrementAndGet()
            return try {
                onSearch(query, cursor, kind)
            } catch (ce: CancellationException) {
                cancelledCalls.incrementAndGet()
                throw ce
            }
        }

        override suspend fun suggest(query: String): List<String> = onSuggest(query)
    }

    @Test
    fun rowOrderInvariant_initialBatchSortedByConfiguredOrderThenLateSourcesStrictlyAppended() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateLate = CompletableDeferred<Unit>()
        val s1 = FakeSearchSource("s1", "NAS", onSearch = { _, _, _ ->
            delay(150)
            Page(listOf(Item("a1", ItemKind.MOVIE, "潮声")), nextPage = null, nextCursor = null)
        })
        val s2 = FakeSearchSource("s2", "拾光视频", onSearch = { _, _, _ ->
            gateLate.await()
            Page(listOf(Item("b1", ItemKind.VIDEO, "看潮汐")), nextPage = null, nextCursor = null)
        })
        val s3 = FakeSearchSource("s3", "老片库", onSearch = { _, _, _ ->
            // Finishes BEFORE s1, both within initial 300 ms batch window
            delay(50)
            Page(listOf(Item("c1", ItemKind.MOVIE, "潮")), nextPage = null, nextCursor = null)
        })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
            sourceTimeoutMs = 10_000L,
        )

        agg.search("潮", SearchKindFilter.ALL, listOf(s1, s2, s3), listOf("本机"))

        // Before 300 ms window closes, rows are buffered
        advanceTimeBy(299)
        runCurrent()
        assertFalse(agg.state.value.initialBatchSettled)
        assertTrue(agg.state.value.visibleRows.isEmpty())

        // At exactly 300 ms, s1 & s3 lock in configured order [s1, s3], NOT arrival order [s3, s1]
        advanceTimeBy(1)
        runCurrent()
        val mid = agg.state.value
        assertTrue(mid.initialBatchSettled)
        assertEquals(listOf("s1", "s3"), mid.visibleRows.map { it.sourceId })
        assertTrue(mid.visibleRows.all { !it.arrivedAfterBatch })
        assertEquals(listOf("拾光视频"), mid.pendingSourceNames)
        assertFalse(mid.showPendingTail)
        assertFalse(mid.allSettled)

        // Release late source s2 -> MUST append at end with arrivedAfterBatch = true without moving s1 or s3
        gateLate.complete(Unit)
        runCurrent()
        val done = agg.state.value
        assertTrue(done.allSettled)
        assertEquals(listOf("s1", "s3", "s2"), done.visibleRows.map { it.sourceId })
        assertTrue(done.visibleRows[2].arrivedAfterBatch)
        assertTrue(done.pendingSourceNames.isEmpty())
        assertEquals(listOf("本机"), done.nonSearchableSourceNames)
    }

    @Test
    fun rowOrderInvariant_allPostBatchArrivalsWithSkeletonAndMixedStatusRowsNeverReorder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Configured order: [s1, s2, s3, s4] — ALL arrive AFTER the 300 ms batch window in reverse/mixed order
        val s1 = FakeSearchSource("s1", "源1", onSearch = { _, _, _ ->
            delay(900)
            Page(listOf(Item("i1", ItemKind.MOVIE, "最慢结果")), null)
        })
        val s2 = FakeSearchSource("s2", "源2-失败", onSearch = { _, _, _ ->
            delay(500)
            throw SourceUnavailableException("offline")
        })
        val s3 = FakeSearchSource("s3", "源3-结果", onSearch = { _, _, _ ->
            delay(700)
            Page(listOf(Item("i3", ItemKind.MOVIE, "中速结果")), null)
        })
        val s4 = FakeSearchSource("s4", "源4-登录", onSearch = { _, _, _ ->
            delay(400)
            throw SourceNeedsAuthException("need login")
        })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
            sourceTimeoutMs = 10_000L,
        )

        agg.search("潮", SearchKindFilter.ALL, listOf(s1, s2, s3, s4))

        // At 300 ms nobody has arrived -> initial skeleton activates
        advanceTimeBy(300)
        runCurrent()
        assertTrue(agg.state.value.initialBatchSettled)
        assertTrue(agg.state.value.showInitialSkeleton)
        assertTrue(agg.state.value.visibleRows.isEmpty())

        // At 400 ms: s4 (NeedsAuth) lands first -> clears skeleton, takes index 0
        advanceTimeBy(100)
        runCurrent()
        assertFalse(agg.state.value.showInitialSkeleton)
        assertEquals(listOf("s4"), agg.state.value.visibleRows.map { it.sourceId })
        assertTrue(agg.state.value.visibleRows[0].arrivedAfterBatch)

        // At 500 ms: s2 (Failed) appends at index 1
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("s4", "s2"), agg.state.value.visibleRows.map { it.sourceId })

        // At 700 ms: s3 (Results) appends at index 2 — NEVER jumps above status rows s4/s2!
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("s4", "s2", "s3"), agg.state.value.visibleRows.map { it.sourceId })

        // At 900 ms: s1 (Results) appends at index 3
        advanceTimeBy(200)
        runCurrent()
        assertTrue(agg.state.value.allSettled)
        assertEquals(listOf("s4", "s2", "s3", "s1"), agg.state.value.visibleRows.map { it.sourceId })
    }

    @Test
    fun moreThanFourSourcesRunConcurrentlyAndAllTimeOutAtExactTenSecondsWith3sPendingTail() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fast = FakeSearchSource("s0", "快速源", onSearch = { _, _, _ ->
            delay(50)
            Page(listOf(Item("m0", ItemKind.MOVIE, "秒出")), null)
        })
        // 5 additional slow sources (total = 6 > old 4-permit cap): every one MUST time out at exact t = 10_000 ms
        val slowSources = (1..5).map { idx ->
            FakeSearchSource("s$idx", "慢源$idx", onSearch = { _, _, _ ->
                delay(30_000)
                Page(listOf(Item("m$idx", ItemKind.MOVIE, "超时结果$idx")), null)
            })
        }

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
            sourceTimeoutMs = 10_000L,
            pendingTailDelayMs = 3_000L,
        )

        agg.search("潮", SearchKindFilter.ALL, listOf(fast) + slowSources)

        // At 2 999 ms: fast source settled, 5 slow sources pending, 「还在搜」 tail NOT shown yet
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(listOf("s0"), agg.state.value.visibleRows.map { it.sourceId })
        assertEquals(5, agg.state.value.pendingSourceNames.size)
        assertFalse("Pending tail must wait 3 000 ms", agg.state.value.showPendingTail)

        // At 3 000 ms: 「还在搜」 tail turns visible
        advanceTimeBy(1)
        runCurrent()
        assertTrue("Pending tail must appear at 3 000 ms", agg.state.value.showPendingTail)

        // At 9 999 ms: still waiting
        advanceTimeBy(6_999)
        runCurrent()
        assertFalse(agg.state.value.allSettled)
        assertEquals(5, agg.state.value.pendingSourceNames.size)

        // At exact 10 000 ms: ALL 5 slow sources time out simultaneously (zero queuing delay!)
        advanceTimeBy(1)
        runCurrent()
        val done = agg.state.value
        assertTrue(done.allSettled)
        assertFalse(done.showPendingTail)
        assertEquals(6, done.visibleRows.size)
        assertEquals("s0", done.visibleRows[0].sourceId)
        for (i in 1..5) {
            assertEquals(SearchRowStatus.TimedOut(10), done.visibleRows[i].status)
        }
        // Every slow source was actually started concurrently (not queued behind 4)
        for (src in slowSources) {
            assertEquals(1, src.searchCalls.get())
        }
    }

    @Test
    fun kindFilterEmptyFirstPageWithNextCursorAutoPagesInsteadOfFalseEmpty() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Source A ignores `kind`: page 1 has only VIDEOs + next="p2", page 2 has MOVIEs -> must NOT report Empty!
        val srcHasMatchOnPage2 = FakeSearchSource("sa", "源A", onSearch = { _, cursor, _ ->
            when (cursor) {
                null -> Page(
                    items = listOf(Item("v1", ItemKind.VIDEO, "纯视频1"), Item("v2", ItemKind.VIDEO, "纯视频2")),
                    nextPage = null,
                    nextCursor = "p2",
                )
                "p2" -> Page(
                    items = listOf(Item("m1", ItemKind.MOVIE, "第2页电影"), Item("v3", ItemKind.VIDEO, "视频3")),
                    nextPage = null,
                    nextCursor = "p3",
                )
                "p3" -> Page(
                    items = listOf(Item("m2", ItemKind.MOVIE, "第3页电影")),
                    nextPage = null,
                    nextCursor = null,
                )
                else -> Page(emptyList(), null)
            }
        })

        // Source B ignores `kind`: all 6 pages have ONLY VIDEOs -> auto-pages page 1 + 3 extra = 4 calls, then reports Empty
        val srcAlwaysVideos = FakeSearchSource("sb", "源B", onSearch = { _, cursor, _ ->
            val p = cursor?.toIntOrNull() ?: 1
            Page(
                items = listOf(Item("bv-$p", ItemKind.VIDEO, "视频$p")),
                nextPage = null,
                nextCursor = if (p < 6) (p + 1).toString() else null,
            )
        })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
            minFilteredItems = 10,
            maxAutoFilterPages = 3,
        )

        agg.search("潮", SearchKindFilter.MOVIE, listOf(srcHasMatchOnPage2, srcAlwaysVideos))
        runCurrent()

        val done = agg.state.value
        assertTrue(done.allSettled)
        assertEquals(listOf("sa"), done.visibleRows.map { it.sourceId })
        val movies = (done.visibleRows.single().status as SearchRowStatus.Results).items
        assertEquals(listOf("m1", "m2"), movies.map { it.id })
        // Source B exhausted its 1 + 3 = 4 page cap and only then reported Empty
        assertEquals(4, srcAlwaysVideos.searchCalls.get())
        assertEquals(listOf("源B"), done.emptySourceNames)
    }

    @Test
    fun allFastSourcesFlushImmediatelyWithResultsBeforeStatusRows() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sAuth = FakeSearchSource("s-auth", "拾光视频", knownNeedsAuth = true, onSearch = { _, _, _ ->
            Page(emptyList(), null)
        })
        val sFail = FakeSearchSource("s-fail", "坏源", onSearch = { _, _, _ ->
            throw SourceUnavailableException("offline")
        })
        val sOk = FakeSearchSource("s-ok", "NAS", onSearch = { _, _, _ ->
            Page(listOf(Item("m1", ItemKind.MOVIE, "雾港")), null)
        })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 2_000L,
            sourceTimeoutMs = 10_000L,
        )

        agg.search("雾港", SearchKindFilter.ALL, listOf(sAuth, sFail, sOk))
        runCurrent()

        assertTrue(agg.state.value.allSettled)
        assertEquals("Flushed immediately at t = 0 without waiting 2000ms", 0L, testScheduler.currentTime)
        assertEquals(0, sAuth.searchCalls.get())
        val rows = agg.state.value.visibleRows
        assertEquals(listOf("s-ok", "s-auth", "s-fail"), rows.map { it.sourceId })
        assertTrue(rows[0].status is SearchRowStatus.Results)
        assertTrue(rows[1].status is SearchRowStatus.NeedsAuth)
        assertTrue(rows[2].status is SearchRowStatus.Failed)
    }

    @Test
    fun retryInPlacePreservesRowIndexOnResultsEmptyAndMidFlightCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var flakyAttempt = 0
        val retryGate = CompletableDeferred<Unit>()
        val sOk = FakeSearchSource("s-ok", "NAS", onSearch = { _, _, _ ->
            Page(listOf(Item("m1", ItemKind.MOVIE, "潮汐表")), null)
        })
        val sFlaky = FakeSearchSource("s-flaky", "老片库", onSearch = { _, _, _ ->
            flakyAttempt++
            when (flakyAttempt) {
                1 -> throw SourceUnavailableException("down")
                2 -> Page(emptyList(), null) // Retry returns EMPTY -> must keep row in place!
                3 -> {
                    retryGate.await() // Cancelled mid-flight!
                    Page(listOf(Item("stale", ItemKind.MOVIE, "旧重试")), null)
                }
                else -> Page(listOf(Item("m2", ItemKind.MOVIE, "最终结果")), null)
            }
        })
        val sRate = FakeSearchSource("s-rate", "限流源", onSearch = { _, _, _ ->
            throw SourceRateLimitedException("slow down", retryAfterSec = 60)
        })
        val sHang = FakeSearchSource("s-hang", "超慢源", onSearch = { _, _, _ ->
            delay(20_000)
            Page(emptyList(), null)
        })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
            sourceTimeoutMs = 10_000L,
        )

        agg.search("潮", SearchKindFilter.ALL, listOf(sOk, sFlaky, sRate, sHang))
        advanceTimeBy(10_000)
        runCurrent()

        val initialRows = agg.state.value.visibleRows
        assertEquals(listOf("s-ok", "s-flaky", "s-rate", "s-hang"), initialRows.map { it.sourceId })
        assertTrue(initialRows[1].status is SearchRowStatus.Failed)
        assertEquals(60, (initialRows[2].status as SearchRowStatus.RateLimited).retryAfterSec)
        assertEquals(SearchRowStatus.TimedOut(10), initialRows[3].status)

        // 1. Retry s-flaky returning EMPTY -> row stays at index 1 showing Empty so s-rate & s-hang NEVER jump upward!
        agg.retrySource("s-flaky")
        runCurrent()
        val afterEmptyRetry = agg.state.value
        assertEquals(listOf("s-ok", "s-flaky", "s-rate", "s-hang"), afterEmptyRetry.visibleRows.map { it.sourceId })
        assertEquals(SearchRowStatus.Empty, afterEmptyRetry.visibleRows[1].status)
        assertFalse(afterEmptyRetry.emptySourceNames.contains("老片库"))
        assertFalse(afterEmptyRetry.isTrueNoResult)

        // 2. Start another retry (attempt 3 blocked on retryGate), then cancel mid-flight via a newer retry (attempt 4)
        agg.retrySource("s-flaky")
        runCurrent()
        assertTrue(agg.state.value.visibleRows[1].refreshingInPlace)

        agg.retrySource("s-flaky")
        runCurrent()
        // Attempt 3 was cancelled, attempt 4 succeeded in place at exact index 1
        assertEquals(1, sFlaky.cancelledCalls.get())
        val afterFinalRetry = agg.state.value.visibleRows
        assertEquals(listOf("s-ok", "s-flaky", "s-rate", "s-hang"), afterFinalRetry.map { it.sourceId })
        assertEquals("m2", (afterFinalRetry[1].status as SearchRowStatus.Results).items.single().id)
        assertFalse(afterFinalRetry[1].refreshingInPlace)

        // Completing the old gate afterwards must have zero effect
        retryGate.complete(Unit)
        runCurrent()
        assertEquals("m2", (agg.state.value.visibleRows[1].status as SearchRowStatus.Results).items.single().id)
    }

    @Test
    fun newGenerationCancelsOldQueryAndInFlightRetryAndDiscardsLateReplies() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val slowSource = FakeSearchSource("s1", "NAS", onSearch = { q, _, _ ->
            if (q == "旧词") {
                delay(1_000)
                Page(listOf(Item("old-item", ItemKind.MOVIE, "旧结果")), null)
            } else {
                delay(50)
                Page(listOf(Item("new-item", ItemKind.MOVIE, "新结果")), null)
            }
        })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
            sourceTimeoutMs = 10_000L,
        )

        val g1 = agg.search("旧词", SearchKindFilter.ALL, listOf(slowSource))
        advanceTimeBy(100)
        runCurrent()

        val g2 = agg.search("新词", SearchKindFilter.ALL, listOf(slowSource))
        assertTrue(g2 > g1)
        advanceTimeBy(2_000)
        runCurrent()

        val st = agg.state.value
        assertEquals(g2, st.generation)
        assertEquals("新词", st.query)
        val items = (st.visibleRows.single().status as SearchRowStatus.Results).items
        assertEquals(listOf("new-item"), items.map { it.id })
        assertTrue(slowSource.cancelledCalls.get() >= 1)
    }

    @Test
    fun fiveMinuteCacheHitsWithinTtlExpiresAfterTtlAndSynchronouslyInvalidatesPerSource() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s1 = FakeSearchSource("s1", "NAS", onSearch = { _, _, _ ->
            Page(listOf(Item("m1", ItemKind.MOVIE, "雾港")), null)
        })
        val s2 = FakeSearchSource("s2", "老片库", onSearch = { _, _, _ ->
            Page(listOf(Item("m2", ItemKind.MOVIE, "雾港2")), null)
        })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
            cacheTtlMs = 300_000L,
        )

        // First search populates cache
        agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
        runCurrent()
        assertEquals(1, s1.searchCalls.get())
        assertEquals(1, s2.searchCalls.get())

        // Second search 60 s later hits cache -> 0 new calls
        advanceTimeBy(60_000L)
        agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
        runCurrent()
        assertEquals(1, s1.searchCalls.get())
        assertEquals(1, s2.searchCalls.get())

        // Synchronous invalidateSourceCache("s1") takes effect IMMEDIATELY with ZERO delay!
        agg.invalidateSourceCache("s1")
        agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
        runCurrent()
        assertEquals(2, s1.searchCalls.get())
        assertEquals(1, s2.searchCalls.get())

        // Advance past 5-minute TTL -> both re-fetch
        advanceTimeBy(300_001L)
        agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
        runCurrent()
        assertEquals(3, s1.searchCalls.get())
        assertEquals(2, s2.searchCalls.get())

        // Synchronous clearCache() drops all entries immediately
        agg.clearCache()
        agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
        runCurrent()
        assertEquals(4, s1.searchCalls.get())
        assertEquals(3, s2.searchCalls.get())
    }

    @Test
    fun trueNoResultTriggersFallbackSuggestionsWhilePartialFailureStaysInListView() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sEmpty1 = FakeSearchSource(
            "s1", "NAS",
            onSearch = { _, _, _ -> Page(emptyList(), null) },
            onSuggest = { listOf("雾港", "雾港 导演剪辑版") },
        )
        val sEmpty2 = FakeSearchSource(
            "s2", "拾光视频",
            onSearch = { _, _, _ -> Page(emptyList(), null) },
            onSuggest = { listOf("雾港", "雾中的港口", "多余的第四条") },
        )
        val sFail = FakeSearchSource(
            "s3", "老片库",
            onSearch = { _, _, _ -> throw SourceNeedsAuthException("请登录") },
        )

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
        )

        // 1. All sources cleanly empty -> true no-result + up to 3 fallback suggestions
        agg.search("雾港二", SearchKindFilter.ALL, listOf(sEmpty1, sEmpty2))
        runCurrent()
        assertTrue(agg.state.value.isTrueNoResult)
        assertEquals(listOf("雾港", "雾港 导演剪辑版", "雾中的港口"), agg.state.value.fallbackSuggestions)

        // 2. One empty + one NeedsAuth -> NOT true no-result; shows NeedsAuth row + emptySourceNames
        val g2 = agg.search("雾港二", SearchKindFilter.ALL, listOf(sEmpty1, sFail), bypassCache = true)
        assertEquals(g2, agg.state.value.generation)
        assertFalse(agg.state.value.allSettled)
        runCurrent()
        assertFalse(agg.state.value.isTrueNoResult)
        assertEquals(listOf("s3"), agg.state.value.visibleRows.map { it.sourceId })
        assertEquals(listOf("NAS"), agg.state.value.emptySourceNames)
    }

    @Test
    fun localKindFallbackDeduplicatesIdsAndPreservesAvailableKindsAcrossFilterSwitches() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mixedItems = listOf(
            Item("m1", ItemKind.MOVIE, "暗潮"),
            Item("m1", ItemKind.MOVIE, "暗潮 重复"),
            Item("s1", ItemKind.SERIES, "潮汐表"),
            Item("e1", ItemKind.EPISODE, "退潮之后"),
            Item("f1", ItemKind.FOLDER, "潮系列合集"),
        )
        val s1 = FakeSearchSource("s1", "NAS", onSearch = { _, _, _ -> Page(mixedItems, null) })

        val agg = SearchAggregator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMs = { testScheduler.currentTime },
            initialBatchWindowMs = 300L,
        )

        // ALL keeps deduped movie + series + episode + folder; discovers MOVIE & SERIES (not VIDEO)
        agg.search("潮", SearchKindFilter.ALL, listOf(s1))
        runCurrent()
        val allRes = (agg.state.value.visibleRows.single().status as SearchRowStatus.Results).items
        assertEquals(listOf("m1", "s1", "e1", "f1"), allRes.map { it.id })
        assertTrue(SearchKindFilter.MOVIE in agg.state.value.availableKinds)
        assertTrue(SearchKindFilter.SERIES in agg.state.value.availableKinds)
        assertFalse(SearchKindFilter.VIDEO in agg.state.value.availableKinds)

        // Switch to SERIES -> includes BOTH Series and Episode, excludes Movie and Folder (§3.9.7)
        agg.search("潮", SearchKindFilter.SERIES, listOf(s1))
        runCurrent()
        val seriesRes = (agg.state.value.visibleRows.single().status as SearchRowStatus.Results).items
        assertEquals(listOf("s1", "e1"), seriesRes.map { it.id })
        assertTrue(SearchKindFilter.MOVIE in agg.state.value.availableKinds)
    }
}
