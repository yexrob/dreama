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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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

    private suspend fun awaitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition was not met within ${timeoutMs}ms")
            }
            delay(10)
        }
    }

    @Test
    fun rowOrderInvariant_initialBatchSortedByConfiguredOrderThenLateSourcesStrictlyAppended() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateLate = CompletableDeferred<Unit>()
            val s1 = FakeSearchSource("s1", "NAS", onSearch = { _, _, _ ->
                delay(40)
                Page(listOf(Item("a1", ItemKind.MOVIE, "潮声")), nextPage = null, nextCursor = null)
            })
            val s2 = FakeSearchSource("s2", "拾光视频", onSearch = { _, _, _ ->
                gateLate.await()
                Page(listOf(Item("b1", ItemKind.VIDEO, "看潮汐")), nextPage = null, nextCursor = null)
            })
            val s3 = FakeSearchSource("s3", "老片库", onSearch = { _, _, _ ->
                // Finishes BEFORE s1, both within initial 120 ms batch window
                delay(10)
                Page(listOf(Item("c1", ItemKind.MOVIE, "潮")), nextPage = null, nextCursor = null)
            })

            val agg = SearchAggregator(
                scope = scope,
                ioDispatcher = Dispatchers.Default,
                initialBatchWindowMs = 120L,
                sourceTimeoutMs = 2_000L,
            )

            agg.search("潮", SearchKindFilter.ALL, listOf(s1, s2, s3), listOf("本机"))

            // Wait for 120 ms batch window to lock s1 & s3 while s2 is still pending
            awaitUntil { agg.state.value.initialBatchSettled && agg.state.value.visibleRows.size == 2 }
            val mid = agg.state.value
            // Configured source order [s1, s3], NOT arrival order [s3, s1]!
            assertEquals(listOf("s1", "s3"), mid.visibleRows.map { it.sourceId })
            assertEquals(listOf("拾光视频"), mid.pendingSourceNames)
            assertFalse(mid.allSettled)

            // Release late source s2 -> MUST append at end without moving s1 or s3!
            gateLate.complete(Unit)
            awaitUntil { agg.state.value.allSettled }
            val done = agg.state.value
            assertEquals(listOf("s1", "s3", "s2"), done.visibleRows.map { it.sourceId })
            assertTrue(done.pendingSourceNames.isEmpty())
            assertEquals(listOf("本机"), done.nonSearchableSourceNames)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun allFastSourcesFlushImmediatelyWithResultsBeforeStatusRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
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
                scope = scope,
                ioDispatcher = Dispatchers.Default,
                initialBatchWindowMs = 2_000L, // large window; should NOT wait for it
                sourceTimeoutMs = 2_000L,
            )

            val t0 = System.currentTimeMillis()
            agg.search("雾港", SearchKindFilter.ALL, listOf(sAuth, sFail, sOk))
            awaitUntil { agg.state.value.allSettled }
            val elapsed = System.currentTimeMillis() - t0

            assertTrue("Expected immediate flush without waiting 2000ms, took ${elapsed}ms", elapsed < 1_000L)
            // Known NeedsAuth source must NOT have been queried at all!
            assertEquals(0, sAuth.searchCalls.get())
            // Results row lands BEFORE status rows; status rows keep configured order
            val rows = agg.state.value.visibleRows
            assertEquals(listOf("s-ok", "s-auth", "s-fail"), rows.map { it.sourceId })
            assertTrue(rows[0].status is SearchRowStatus.Results)
            assertTrue(rows[1].status is SearchRowStatus.NeedsAuth)
            assertTrue(rows[2].status is SearchRowStatus.Failed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun singleSourceTimeoutRateLimitFailureIsolatedAndInPlaceRetryPreservesRowIndex() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var failAttempts = 0
            val sOk = FakeSearchSource("s-ok", "NAS", onSearch = { _, _, _ ->
                Page(listOf(Item("m1", ItemKind.MOVIE, "潮汐表")), null)
            })
            val sFlaky = FakeSearchSource("s-flaky", "老片库", onSearch = { _, _, _ ->
                failAttempts++
                if (failAttempts == 1) {
                    throw SourceUnavailableException("down")
                }
                Page(listOf(Item("m2", ItemKind.MOVIE, "潮")), null)
            })
            val sRate = FakeSearchSource("s-rate", "限流源", onSearch = { _, _, _ ->
                throw SourceRateLimitedException("slow down", retryAfterSec = 35)
            })
            val sHang = FakeSearchSource("s-hang", "超慢源", onSearch = { _, _, _ ->
                delay(5_000)
                Page(emptyList(), null)
            })

            val agg = SearchAggregator(
                scope = scope,
                ioDispatcher = Dispatchers.Default,
                initialBatchWindowMs = 60L,
                sourceTimeoutMs = 180L,
            )

            agg.search("潮", SearchKindFilter.ALL, listOf(sOk, sFlaky, sRate, sHang))
            awaitUntil { agg.state.value.allSettled }

            val initialRows = agg.state.value.visibleRows
            assertEquals(listOf("s-ok", "s-flaky", "s-rate", "s-hang"), initialRows.map { it.sourceId })
            assertTrue(initialRows[0].status is SearchRowStatus.Results)
            assertTrue(initialRows[1].status is SearchRowStatus.Failed)
            assertEquals(35, (initialRows[2].status as SearchRowStatus.RateLimited).retryAfterSec)
            assertTrue(initialRows[3].status is SearchRowStatus.TimedOut)

            // Retry s-flaky IN PLACE -> stays at index 1, expands into Results without moving s-ok or s-rate
            agg.retrySource("s-flaky")
            awaitUntil {
                agg.state.value.visibleRows.getOrNull(1)?.status is SearchRowStatus.Results
            }
            val afterRetry = agg.state.value.visibleRows
            assertEquals(listOf("s-ok", "s-flaky", "s-rate", "s-hang"), afterRetry.map { it.sourceId })
            assertEquals("m2", (afterRetry[1].status as SearchRowStatus.Results).items.single().id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun newGenerationCancelsOldQueryAndDiscardsLateReplies() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val slowSource = FakeSearchSource("s1", "NAS", onSearch = { q, _, _ ->
                if (q == "旧词") {
                    delay(400)
                    Page(listOf(Item("old-item", ItemKind.MOVIE, "旧结果")), null)
                } else {
                    delay(20)
                    Page(listOf(Item("new-item", ItemKind.MOVIE, "新结果")), null)
                }
            })

            val agg = SearchAggregator(
                scope = scope,
                ioDispatcher = Dispatchers.Default,
                initialBatchWindowMs = 50L,
                sourceTimeoutMs = 2_000L,
            )

            val g1 = agg.search("旧词", SearchKindFilter.ALL, listOf(slowSource))
            delay(30)
            val g2 = agg.search("新词", SearchKindFilter.ALL, listOf(slowSource))
            assertTrue(g2 > g1)

            awaitUntil { agg.state.value.allSettled && agg.state.value.generation == g2 }
            // Give any stray work time to settle and confirm old results never leak in
            delay(150)

            val st = agg.state.value
            assertEquals(g2, st.generation)
            assertEquals("新词", st.query)
            val items = (st.visibleRows.single().status as SearchRowStatus.Results).items
            assertEquals(listOf("new-item"), items.map { it.id })
            assertTrue(slowSource.cancelledCalls.get() >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fiveMinuteCacheHitsWithinTtlAndExpiresAfterTtlAndInvalidatesPerSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var fakeNow = 1_000_000L
            val s1 = FakeSearchSource("s1", "NAS", onSearch = { _, _, _ ->
                Page(listOf(Item("m1", ItemKind.MOVIE, "雾港")), null)
            })
            val s2 = FakeSearchSource("s2", "老片库", onSearch = { _, _, _ ->
                Page(listOf(Item("m2", ItemKind.MOVIE, "雾港2")), null)
            })

            val agg = SearchAggregator(
                scope = scope,
                ioDispatcher = Dispatchers.Default,
                clockMs = { fakeNow },
                initialBatchWindowMs = 50L,
                cacheTtlMs = 300_000L,
            )

            // First search populates cache
            agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
            awaitUntil { agg.state.value.allSettled }
            assertEquals(1, s1.searchCalls.get())
            assertEquals(1, s2.searchCalls.get())

            // Second search 60 s later hits cache -> 0 new calls
            fakeNow += 60_000L
            agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
            awaitUntil { agg.state.value.allSettled }
            assertEquals(1, s1.searchCalls.get())
            assertEquals(1, s2.searchCalls.get())

            // Invalidate s1 only -> only s1 re-fetches
            agg.invalidateSourceCache("s1")
            delay(40)
            agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
            awaitUntil { agg.state.value.allSettled }
            assertEquals(2, s1.searchCalls.get())
            assertEquals(1, s2.searchCalls.get())

            // Advance past 5-minute TTL -> both re-fetch
            fakeNow += 300_001L
            agg.search("雾港", SearchKindFilter.ALL, listOf(s1, s2))
            awaitUntil { agg.state.value.allSettled }
            assertEquals(3, s1.searchCalls.get())
            assertEquals(2, s2.searchCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun trueNoResultTriggersFallbackSuggestionsWhilePartialFailureStaysInListView() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
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
                scope = scope,
                ioDispatcher = Dispatchers.Default,
                initialBatchWindowMs = 40L,
            )

            // 1. All sources cleanly empty -> true no-result + up to 3 fallback suggestions
            agg.search("雾港二", SearchKindFilter.ALL, listOf(sEmpty1, sEmpty2))
            awaitUntil { agg.state.value.isTrueNoResult && agg.state.value.fallbackSuggestions.size == 3 }
            assertEquals(listOf("雾港", "雾港 导演剪辑版", "雾中的港口"), agg.state.value.fallbackSuggestions)

            // 2. One empty + one NeedsAuth -> NOT true no-result; shows NeedsAuth row + emptySourceNames
            val g2 = agg.search("雾港二", SearchKindFilter.ALL, listOf(sEmpty1, sFail), bypassCache = true)
            assertEquals(g2, agg.state.value.generation)
            assertFalse(agg.state.value.allSettled)
            awaitUntil { agg.state.value.generation == g2 && agg.state.value.allSettled }
            assertFalse(agg.state.value.isTrueNoResult)
            assertEquals(listOf("s3"), agg.state.value.visibleRows.map { it.sourceId })
            assertEquals(listOf("NAS"), agg.state.value.emptySourceNames)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun localKindFallbackDeduplicatesIdsAndPreservesAvailableKindsAcrossFilterSwitches() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Buggy plugin ignores `kind` AND returns a duplicate item id
            val mixedItems = listOf(
                Item("m1", ItemKind.MOVIE, "暗潮"),
                Item("m1", ItemKind.MOVIE, "暗潮 重复"),
                Item("s1", ItemKind.SERIES, "潮汐表"),
                Item("e1", ItemKind.EPISODE, "退潮之后"),
                Item("f1", ItemKind.FOLDER, "潮系列合集"),
            )
            val s1 = FakeSearchSource("s1", "NAS", onSearch = { _, _, _ -> Page(mixedItems, null) })

            val agg = SearchAggregator(
                scope = scope,
                ioDispatcher = Dispatchers.Default,
                initialBatchWindowMs = 40L,
            )

            // ALL keeps deduped movie + series + episode + folder; discovers MOVIE & SERIES (not VIDEO)
            agg.search("潮", SearchKindFilter.ALL, listOf(s1))
            awaitUntil { agg.state.value.allSettled }
            val allRes = (agg.state.value.visibleRows.single().status as SearchRowStatus.Results).items
            assertEquals(listOf("m1", "s1", "e1", "f1"), allRes.map { it.id })
            assertTrue(SearchKindFilter.MOVIE in agg.state.value.availableKinds)
            assertTrue(SearchKindFilter.SERIES in agg.state.value.availableKinds)
            assertFalse(SearchKindFilter.VIDEO in agg.state.value.availableKinds)

            // Switch to SERIES -> includes BOTH Series and Episode, excludes Movie and Folder (§3.9.7)
            agg.search("潮", SearchKindFilter.SERIES, listOf(s1))
            awaitUntil { agg.state.value.allSettled && agg.state.value.kindFilter == SearchKindFilter.SERIES }
            val seriesRes = (agg.state.value.visibleRows.single().status as SearchRowStatus.Results).items
            assertEquals(listOf("s1", "e1"), seriesRes.map { it.id })
            // MOVIE remains available so user can switch back to it
            assertTrue(SearchKindFilter.MOVIE in agg.state.value.availableKinds)
        } finally {
            scope.cancel()
        }
    }
}
