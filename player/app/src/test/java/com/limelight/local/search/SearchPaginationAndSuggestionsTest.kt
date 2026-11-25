package com.limelight.local.search

import com.limelight.local.media.Category
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.Shelf
import com.limelight.local.media.ShelfStyle
import com.limelight.local.media.Source
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.WatchProgress
import com.limelight.local.stream.MapPrefsStore
import com.limelight.local.stream.StreamSettingsMapper
import com.limelight.local.wall.Load
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPaginationAndSuggestionsTest {

    private class FakeSource(
        override val sourceId: String,
        override val displayName: String,
        override val supportsSuggest: Boolean = true,
        override val knownNeedsAuth: Boolean = false,
        val onSearch: suspend (query: String, cursor: String?, kind: ItemKind?) -> Page<Item> = { _, _, _ -> Page(emptyList(), null) },
        val onSuggest: suspend (query: String) -> List<String> = { emptyList() },
    ) : SearchableSource {
        override val typeLabel: String = "插件"
        override val sourceKind: SourceKind = SourceKind.PLUGIN
        val calls = AtomicInteger(0)

        override suspend fun search(query: String, cursor: String?, kind: ItemKind?): Page<Item> {
            calls.incrementAndGet()
            return onSearch(query, cursor, kind)
        }

        override suspend fun suggest(query: String): List<String> = onSuggest(query)
    }

    private class StubMediaSource(
        id: String,
        name: String,
    ) : MediaSource {
        override val source = Source(id, name, SourceKind.PLUGIN, name)
        override suspend fun home(): List<Shelf> = emptyList()
        override suspend fun browse(categoryId: String, page: Int): Page<Item> = Page(emptyList(), null)
        override suspend fun detail(itemId: String): Item = throw SourceUnavailableException("missing $itemId")
        override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("http://example.invalid/$itemId")
    }

    @Test
    fun expandedPaginatorReusesPage1DeduplicatesGuardsCyclesAndRetriesPageErrors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var p2Failures = 0
        val src = FakeSource("s1", "NAS", onSearch = { _, cursor, _ ->
            when (cursor) {
                "c2" -> {
                    p2Failures++
                    if (p2Failures == 1) throw SourceUnavailableException("temp fail")
                    Page(
                        items = listOf(
                            Item("i2", ItemKind.MOVIE, "重复项"),
                            Item("i3", ItemKind.MOVIE, "第3条"),
                        ),
                        nextPage = null,
                        nextCursor = "c3",
                    )
                }
                "c3" -> {
                    // Length-2 cursor cycle back to "c2" -> cycle guard MUST stop pagination
                    Page(
                        items = listOf(Item("i4", ItemKind.MOVIE, "第4条")),
                        nextPage = null,
                        nextCursor = "c2",
                    )
                }
                else -> Page(emptyList(), null)
            }
        })

        val paginator = ExpandedSourcePaginator(
            scope = backgroundScope,
            source = src,
            query = "潮",
            initialKindFilter = SearchKindFilter.ALL,
            initialItems = listOf(
                Item("i1", ItemKind.MOVIE, "第1条"),
                Item("i2", ItemKind.MOVIE, "第2条"),
            ),
            initialNextCursor = "c2",
            ioDispatcher = dispatcher,
            skeletonDelayMs = 300L,
        )

        // Page 1 reused with 0 calls
        assertEquals(0, src.calls.get())
        assertEquals(listOf("i1", "i2"), paginator.state.value.items.map { it.id })

        // Page 2 fails first time -> preserves Page 1 items intact & tracks failedPageNumber = 2
        paginator.loadNextPage()
        runCurrent()
        assertTrue(paginator.state.value.pageError != null)
        assertEquals(listOf("i1", "i2"), paginator.state.value.items.map { it.id })
        assertEquals(2, paginator.state.value.failedPageNumber)
        assertFalse(paginator.state.value.loadingNextPage)

        // Retry Page 2 succeeds -> appends only i3 (deduping i2)
        paginator.retryPage()
        runCurrent()
        assertNull(paginator.state.value.pageError)
        assertEquals(listOf("i1", "i2", "i3"), paginator.state.value.items.map { it.id })
        assertEquals("c3", paginator.state.value.nextCursor)
        assertEquals(2, paginator.state.value.loadedPages)

        // Page 3 returns cycle cursor "c2" -> cycle guard terminates nextCursor to null
        paginator.loadNextPage()
        runCurrent()
        assertEquals(listOf("i1", "i2", "i3", "i4"), paginator.state.value.items.map { it.id })
        assertNull(paginator.state.value.nextCursor)
        assertTrue(paginator.state.value.reachedEnd)
    }

    @Test
    fun expandedPaginatorSurvivesHolderScopeAndResetsLoadingFlagsOnCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val holderScope = CoroutineScope(SupervisorJob() + dispatcher)
        val page2Gate = CompletableDeferred<Unit>()
        var calls = 0
        val src = FakeSource("s1", "NAS", onSearch = { _, cursor, _ ->
            calls++
            if (calls == 1) {
                page2Gate.await()
            }
            Page(
                items = listOf(Item("p-$cursor-$calls", ItemKind.MOVIE, "第2页-$calls")),
                nextPage = null,
                nextCursor = if (cursor == "c2") "c3" else null,
            )
        })

        try {
            // 1. Cancellation while loading resets loadingNextPage & showLoadingSkeleton immediately
            val cancelledScope = CoroutineScope(SupervisorJob() + dispatcher)
            val cancelledPaginator = ExpandedSourcePaginator(
                scope = cancelledScope,
                source = src,
                query = "潮",
                initialKindFilter = SearchKindFilter.ALL,
                initialItems = listOf(Item("p1", ItemKind.MOVIE, "第1页")),
                initialNextCursor = "c2",
                ioDispatcher = dispatcher,
                skeletonDelayMs = 300L,
            )
            cancelledPaginator.loadNextPage()
            advanceTimeBy(300)
            runCurrent()
            assertTrue(cancelledPaginator.state.value.loadingNextPage)
            assertTrue(cancelledPaginator.state.value.showLoadingSkeleton)

            // Cancel mid-flight -> both flags MUST reset to false cleanly (never stuck at true!)
            cancelledScope.cancel()
            runCurrent()
            assertFalse("loadingNextPage must reset on cancellation", cancelledPaginator.state.value.loadingNextPage)
            assertFalse("showLoadingSkeleton must reset on cancellation", cancelledPaginator.state.value.showLoadingSkeleton)

            // 2. Paginator backed by holder-owned scope keeps working across simulated Detail -> Back
            val holderPaginator = ExpandedSourcePaginator(
                scope = holderScope,
                source = src,
                query = "潮",
                initialKindFilter = SearchKindFilter.ALL,
                initialItems = listOf(Item("p1", ItemKind.MOVIE, "第1页")),
                initialNextCursor = "c2",
                ioDispatcher = dispatcher,
                skeletonDelayMs = 300L,
            )
            // First loadNextPage after returning from Detail works seamlessly
            holderPaginator.loadNextPage()
            runCurrent()
            assertEquals(2, holderPaginator.state.value.items.size)
            assertEquals("c3", holderPaginator.state.value.nextCursor)
            // Second loadNextPage also works
            holderPaginator.loadNextPage()
            runCurrent()
            assertEquals(3, holderPaginator.state.value.items.size)
            assertTrue(holderPaginator.state.value.reachedEnd)

            // 3. Explicit cancel() during switchKindFilter resets loadingNextPage & showLoadingSkeleton
            val slowSwitchSrc = FakeSource("s2", "慢源", onSearch = { _, _, _ ->
                delay(5_000)
                Page(emptyList(), null)
            })
            val swPaginator = ExpandedSourcePaginator(
                scope = holderScope,
                source = slowSwitchSrc,
                query = "潮",
                initialKindFilter = SearchKindFilter.ALL,
                initialItems = listOf(Item("p1", ItemKind.MOVIE, "第1页")),
                initialNextCursor = "c2",
                ioDispatcher = dispatcher,
                skeletonDelayMs = 300L,
            )
            swPaginator.switchKindFilter(SearchKindFilter.MOVIE)
            advanceTimeBy(300)
            runCurrent()
            assertTrue(swPaginator.state.value.loadingNextPage)
            assertTrue(swPaginator.state.value.showLoadingSkeleton)
            swPaginator.cancel()
            runCurrent()
            assertFalse(swPaginator.state.value.loadingNextPage)
            assertFalse(swPaginator.state.value.showLoadingSkeleton)
        } finally {
            holderScope.cancel()
        }
    }

    @Test
    fun expandedPaginatorAutoAdvancesAcrossAllDuplicatePagesWithCap() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Page 2 has ONLY duplicate "i1" -> must automatically advance to Page 3 to emit "i2"
        val src = FakeSource("s1", "去重源", onSearch = { _, cursor, _ ->
            when (cursor) {
                "c2" -> Page(listOf(Item("i1", ItemKind.MOVIE, "重复1")), null, nextCursor = "c3")
                "c3" -> Page(listOf(Item("i2", ItemKind.MOVIE, "新条目")), null, nextCursor = null)
                else -> Page(emptyList(), null)
            }
        })
        val paginator = ExpandedSourcePaginator(
            scope = backgroundScope,
            source = src,
            query = "潮",
            initialKindFilter = SearchKindFilter.ALL,
            initialItems = listOf(Item("i1", ItemKind.MOVIE, "第1条")),
            initialNextCursor = "c2",
            ioDispatcher = dispatcher,
        )
        paginator.loadNextPage()
        runCurrent()
        assertEquals(2, src.calls.get())
        assertEquals(listOf("i1", "i2"), paginator.state.value.items.map { it.id })
        assertTrue(paginator.state.value.reachedEnd)

        // Bound test: 6 consecutive all-duplicate pages -> stops after 1 + MAX_DUPLICATE_SKIP_PAGES (4 calls)
        val endlessDupSrc = FakeSource("s2", "全重复源", onSearch = { _, cursor, _ ->
            val n = cursor?.removePrefix("p")?.toIntOrNull() ?: 2
            Page(listOf(Item("i1", ItemKind.MOVIE, "重复")), null, nextCursor = "p${n + 1}")
        })
        val bounded = ExpandedSourcePaginator(
            scope = backgroundScope,
            source = endlessDupSrc,
            query = "潮",
            initialKindFilter = SearchKindFilter.ALL,
            initialItems = listOf(Item("i1", ItemKind.MOVIE, "第1条")),
            initialNextCursor = "p2",
            ioDispatcher = dispatcher,
            maxDuplicateSkipPages = 3,
        )
        bounded.loadNextPage()
        runCurrent()
        assertEquals(4, endlessDupSrc.calls.get())
        assertFalse(bounded.state.value.loadingNextPage)
        assertEquals("p6", bounded.state.value.nextCursor)
    }

    @Test
    fun expandedPaginatorAutoFetchesUpToThreePagesWhenPluginIgnoresKind() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val src = FakeSource("s1", "拾光", onSearch = { _, cursor, _ ->
            val pageIdx = cursor?.toIntOrNull() ?: 0
            val items = listOf(
                Item("m-$pageIdx", ItemKind.MOVIE, "电影 $pageIdx"),
                Item("v-$pageIdx-1", ItemKind.VIDEO, "视频 $pageIdx-1"),
                Item("v-$pageIdx-2", ItemKind.VIDEO, "视频 $pageIdx-2"),
            )
            val next = if (pageIdx < 5) (pageIdx + 1).toString() else null
            Page(items, nextPage = null, nextCursor = next)
        })

        val paginator = ExpandedSourcePaginator(
            scope = backgroundScope,
            source = src,
            query = "潮",
            initialKindFilter = SearchKindFilter.ALL,
            initialItems = emptyList(),
            initialNextCursor = null,
            ioDispatcher = dispatcher,
            minFilteredItems = 10,
            maxAutoFilterPages = 3,
        )

        paginator.switchKindFilter(SearchKindFilter.MOVIE)
        runCurrent()

        assertEquals(4, src.calls.get())
        assertEquals(listOf("m-0", "m-1", "m-2", "m-3"), paginator.state.value.items.map { it.id })
        assertEquals("4", paginator.state.value.nextCursor)
    }

    @Test
    fun expandedPaginatorDelaysSkeletonUntilExact300msThreshold() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val src = FakeSource("s1", "慢源", onSearch = { _, _, _ ->
            gate.await()
            Page(listOf(Item("p2", ItemKind.MOVIE, "第二页")), null)
        })
        val paginator = ExpandedSourcePaginator(
            scope = backgroundScope,
            source = src,
            query = "潮",
            initialKindFilter = SearchKindFilter.ALL,
            initialItems = listOf(Item("p1", ItemKind.MOVIE, "第一页")),
            initialNextCursor = "2",
            ioDispatcher = dispatcher,
            skeletonDelayMs = 300L,
        )

        paginator.loadNextPage()
        runCurrent()
        assertTrue(paginator.state.value.loadingNextPage)
        assertFalse("Skeleton should NOT flash immediately", paginator.state.value.showLoadingSkeleton)

        advanceTimeBy(299)
        runCurrent()
        assertFalse("Skeleton must wait full 300 ms", paginator.state.value.showLoadingSkeleton)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(paginator.state.value.showLoadingSkeleton)

        gate.complete(Unit)
        runCurrent()
        assertFalse(paginator.state.value.loadingNextPage)
        assertFalse(paginator.state.value.showLoadingSkeleton)
    }

    @Test
    fun rowPackerGreedyLeftToRightNeverAltersEarlierRowsWhenAppendingPages() {
        val page1 = listOf(
            Item("1", ItemKind.SERIES, "潮汐表"),          // 128
            Item("2", ItemKind.EPISODE, "退潮之后"),        // 341  -> 128+24+341 = 493
            Item("3", ItemKind.MOVIE, "暗潮"),             // 128  -> 645
            Item("4", ItemKind.MOVIE, "潮声"),             // 128  -> 797
            Item("5", ItemKind.MOVIE, "观潮的人"),          // 128  -> 949
            Item("6", ItemKind.EPISODE, "涨潮时分"),        // 341  -> 949+24+341 = 1314 > 1152 -> row 2!
            Item("7", ItemKind.MOVIE, "潮湿的季节"),        // 128  -> 341+24+128 = 493
        )
        val page2 = listOf(
            Item("8", ItemKind.FOLDER, "潮系列合集"),       // 128
            Item("9", ItemKind.VIDEO, "观潮实拍"),          // 341
            Item("10", ItemKind.MOVIE, "冬潮"),            // 128
            Item("11", ItemKind.EPISODE, "大潮"),          // 341
        )

        val rows1 = SearchRowPacker.pack(page1)
        val rows12 = SearchRowPacker.pack(page1 + page2)

        assertEquals(2, rows1.size)
        assertEquals(listOf("1", "2", "3", "4", "5"), rows1[0].map { it.item.id })
        assertEquals(listOf("6", "7"), rows1[1].map { it.item.id })

        assertEquals(rows1[0].map { it.item.id }, rows12[0].map { it.item.id })
        assertEquals(listOf("6", "7"), rows12[1].take(2).map { it.item.id })
        for (r in rows12) {
            val totalW = r.sumOf { it.shape.widthDp } + (r.size - 1) * SearchConfig.EXPANDED_ITEM_GAP_DP
            assertTrue("Row overflowed 1152dp: $totalW", totalW <= SearchConfig.EXPANDED_ROW_WIDTH_DP)
        }
    }

    @Test
    fun suggestionEngineNormalizesMergesCapsAtFourAndHoverFreezeIgnoresUnchangedQuery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals("潮汐 表", SearchQueryNormalizer.normalize("　潮汐　　表  "))
        assertEquals("ABC 123", SearchQueryNormalizer.normalize("ＡＢＣ　１２３"))
        assertFalse(SearchQueryNormalizer.shouldRequestRemoteSuggest(""))
        assertFalse(SearchQueryNormalizer.shouldRequestRemoteSuggest("a"))
        assertTrue(SearchQueryNormalizer.shouldRequestRemoteSuggest("ab"))
        assertTrue(SearchQueryNormalizer.shouldRequestRemoteSuggest("潮"))

        val src = StubMediaSource("plugin:nas", "NAS")
        val catalog = listOf(
            LocalCatalogEntry(src, Item("s1", ItemKind.SERIES, "潮汐表")),
            LocalCatalogEntry(src, Item("m1", ItemKind.MOVIE, "暗潮")),
            LocalCatalogEntry(src, Item("m2", ItemKind.MOVIE, "潮声")),
        )
        val history = listOf("潮汐表 第 3 季", "潮", "雾港")

        val fullRows = SearchSuggestionEngine.buildRows(
            rawQuery = "潮",
            localCatalog = catalog,
            history = history,
            remoteSuggestions = listOf("远端联想A", "远端联想B"),
        )
        assertEquals(4, fullRows.size)
        assertEquals(SuggestionRow.RawQuery("潮"), fullRows[0])
        assertTrue(fullRows[1] is SuggestionRow.LocalHit)
        assertTrue(fullRows[2] is SuggestionRow.LocalHit)
        assertEquals(SuggestionRow.History("潮汐表 第 3 季"), fullRows[3])

        val partialRows = SearchSuggestionEngine.buildRows(
            rawQuery = "潮",
            localCatalog = listOf(catalog.first()),
            history = listOf("潮汐表 第 3 季"),
            remoteSuggestions = listOf("潮汐表 第 3 季", "潮声", "多余项"),
        )
        assertEquals(4, partialRows.size)
        assertEquals(SuggestionRow.Remote("潮声"), partialRows[3])

        // Remote fetcher: silent on timeout, failure, unsupported, needsAuth
        val sGood = FakeSource("g1", "NAS", onSuggest = { listOf("潮声", "潮汐表") })
        val sFail = FakeSource("f1", "坏源", onSuggest = { throw SourceUnavailableException("err") })
        val sSlow = FakeSource("sl1", "慢源", onSuggest = {
            delay(5_000)
            listOf("太慢的联想")
        })
        val sNoSug = FakeSource("ns1", "无联想源", supportsSuggest = false, onSuggest = {
            throw AssertionError("Should never be called when supportsSuggest == false")
        })
        val sAuth = FakeSource("au1", "需登录", knownNeedsAuth = true, onSuggest = {
            throw AssertionError("Should never be called when knownNeedsAuth == true")
        })

        val remote = SearchSuggestionEngine.fetchRemoteSuggestions(
            rawQuery = "潮",
            sources = listOf(sGood, sFail, sSlow, sNoSug, sAuth),
            timeoutMs = 800L,
            ioDispatcher = dispatcher,
        )
        assertEquals(listOf("潮声", "潮汐表"), remote)
        assertEquals(800L, testScheduler.currentTime)

        // HoverFreezeBuffer: holds update AND unchanged-key onQueryChanged while hovered; resets on real query change
        val buf = HoverFreezeBuffer(listOf("A"))
        assertEquals(listOf("A1"), buf.onQueryChanged("潮", listOf("A1")))
        buf.onHoverChanged(true)
        assertEquals(listOf("A1"), buf.onUpdate("潮", listOf("A1", "Remote")))
        // Unchanged key MUST NOT bypass hover freeze (e.g. when recomposition re-triggers effect)
        assertEquals(listOf("A1"), buf.onQueryChanged("潮", listOf("A1", "Rebuilt")))
        // Leaving hover flushes latest buffered list
        assertEquals(listOf("A1", "Rebuilt"), buf.onHoverChanged(false))
        // Different query immediately replaces even while hovered
        buf.onHoverChanged(true)
        assertEquals(listOf("B1"), buf.onQueryChanged("雾", listOf("B1")))
    }

    @Test
    fun navigationPolicyDirectPlaysVideoWithProgressAndRoutesEpisodesMoviesFolders() {
        val src = StubMediaSource("plugin:v", "拾光视频")

        // 1. VIDEO with local WatchProgress -> PlayDirect using local position (overrides remote)
        val vidWithBoth = Item("v1", ItemKind.VIDEO, "观潮实拍", durationMs = 600_000L, progressMs = 60_000L)
        val localProg = WatchProgress("plugin:v", "v1", "观潮实拍", positionMs = 180_000L, durationMs = 600_000L, updatedAt = 100L)
        val a1 = SearchNavigationPolicy.resolveClickAction(src, vidWithBoth, localProg)
        assertEquals(SearchItemAction.PlayDirect(src, vidWithBoth, 180_000L), a1)

        // 2. VIDEO with only remote progress -> PlayDirect using remote progress
        val a2 = SearchNavigationPolicy.resolveClickAction(src, vidWithBoth, null)
        assertEquals(SearchItemAction.PlayDirect(src, vidWithBoth, 60_000L), a2)

        // 3. VIDEO already finished -> PlayDirect from 0
        val finishedLocal = WatchProgress("plugin:v", "v1", "观潮实拍", positionMs = 590_000L, durationMs = 600_000L, updatedAt = 200L)
        val a3 = SearchNavigationPolicy.resolveClickAction(src, vidWithBoth, finishedLocal)
        assertEquals(SearchItemAction.PlayDirect(src, vidWithBoth, 0L), a3)

        // 4. LIVE -> PlayDirect from 0
        val live = Item("l1", ItemKind.LIVE, "直播间")
        assertEquals(SearchItemAction.PlayDirect(src, live, 0L), SearchNavigationPolicy.resolveClickAction(src, live))

        // 5. EPISODE -> OpenEpisodeInSeries
        val ep = Item("ep1", ItemKind.EPISODE, "第 6 集", parentId = "s1")
        assertEquals(SearchItemAction.OpenEpisodeInSeries(src, ep), SearchNavigationPolicy.resolveClickAction(src, ep))

        // 6. FOLDER -> OpenFolder
        val folder = Item("f1", ItemKind.FOLDER, "潮系列")
        assertEquals(SearchItemAction.OpenFolder(src, folder), SearchNavigationPolicy.resolveClickAction(src, folder))

        // 7. MOVIE, SERIES, and multi-part VIDEO -> OpenDetail
        val movie = Item("m1", ItemKind.MOVIE, "暗潮")
        val series = Item("ser1", ItemKind.SERIES, "潮汐表")
        val multiPart = Item("mp1", ItemKind.VIDEO, "多P视频", episodes = listOf(ep))
        assertEquals(SearchItemAction.OpenDetail(src, movie), SearchNavigationPolicy.resolveClickAction(src, movie))
        assertEquals(SearchItemAction.OpenDetail(src, series), SearchNavigationPolicy.resolveClickAction(src, series))
        assertEquals(SearchItemAction.OpenDetail(src, multiPart), SearchNavigationPolicy.resolveClickAction(src, multiPart))
    }

    @Test
    fun historyStoreEnforcesCapDedupeUndoPrivacyWipeAndQueryRedaction() {
        val historyPrefs = MapPrefsStore()
        val settingsPrefs = MapPrefsStore()
        val store = SearchHistoryStore(historyPrefs, settingsPrefs, maxEntries = 20)

        assertTrue(store.isRememberEnabled())
        assertTrue(StreamSettingsMapper.read(settingsPrefs, false).rememberSearchHistory)

        for (i in 1..22) {
            store.record("  影片 $i ")
        }
        assertEquals(20, store.entries.value.size)
        assertEquals("影片 22", store.entries.value.first())
        assertEquals("影片 3", store.entries.value.last())

        store.record("影片　5")
        assertEquals(20, store.entries.value.size)
        assertEquals("影片 5", store.entries.value.first())
        assertEquals(1, store.entries.value.count { it == "影片 5" })

        store.remove("影片 5")
        assertEquals(19, store.entries.value.size)
        assertFalse("影片 5" in store.entries.value)

        val reloaded = SearchHistoryStore(historyPrefs, settingsPrefs, maxEntries = 20)
        assertEquals(store.entries.value, reloaded.entries.value)

        val snap = store.clearWithUndoSnapshot()
        assertTrue(store.entries.value.isEmpty())
        assertEquals(19, snap.size)
        store.restore(snap)
        assertEquals(snap, store.entries.value)

        store.setRememberEnabled(false)
        assertFalse(store.rememberEnabled.value)
        assertFalse(StreamSettingsMapper.read(settingsPrefs, false).rememberSearchHistory)
        assertTrue(store.entries.value.isEmpty())
        assertNull(historyPrefs.getString(SearchHistoryStore.KEY_HISTORY_JSON, null))

        store.record("不应保存")
        assertTrue(store.entries.value.isEmpty())
        store.restore(snap)
        assertTrue(store.entries.value.isEmpty())

        // Redaction covers raw, URL-encoded (+ and %20), and single CJK char
        val scrubbed = SearchLogSanitizer.redactInText(
            "GET /search?q=%E7%A7%81%E5%AF%86%20%E7%89%87%E5%90%8D term='私密 片名'",
            listOf("私密 片名"),
        )
        assertFalse(scrubbed.contains("私密"))
        assertFalse(scrubbed.contains("%E7%A7%81%E5%AF%86"))
        assertTrue(scrubbed.contains(SearchLogSanitizer.REDACTED_QUERY))
    }

    @Test
    fun idleDataBuilderIncludesWatchedAndResumableLocallyAndBuildsLocalCatalog() {
        val s1 = StubMediaSource("s1", "NAS")
        val s2 = StubMediaSource("s2", "本机")
        val shelves = mapOf<String, Load<List<Shelf>>>(
            "s1" to Load.Ready(
                listOf(
                    Shelf(Category("c", "继续观看"), listOf(Item("w1", ItemKind.MOVIE, "雾港")), ShelfStyle.CONTINUE),
                    Shelf(
                        Category("movies", "电影"),
                        listOf(
                            Item("m1", ItemKind.MOVIE, "冬至以后"),
                            Item("m2", ItemKind.MOVIE, "晚班"),
                        ),
                        ShelfStyle.POSTER,
                    ),
                ),
            ),
            "s2" to Load.Ready(
                listOf(
                    Shelf(Category("local", "全部视频"), listOf(Item("l1", ItemKind.VIDEO, "第七个夏天")), ShelfStyle.THUMB),
                ),
            ),
        )
        val progressRows = listOf(
            WatchProgress("s1", "w1", "雾港", positionMs = 1_200_000L, durationMs = 3_600_000L, updatedAt = 200L),
            WatchProgress("s1", "m2", "晚班", positionMs = 3_550_000L, durationMs = 3_600_000L, updatedAt = 100L),
        )

        val watched = SearchIdleDataBuilder.buildRecentWatched(progressRows, listOf(s1, s2), shelves)
        assertEquals(2, watched.size)
        assertTrue(watched[0].isResumable)
        assertFalse(watched[1].isResumable)
        assertNull(watched[1].progressFraction)

        val catalog = SearchIdleDataBuilder.buildLocalCatalog(listOf(s1, s2), shelves, watched)
        assertEquals(listOf("w1", "m2", "m1", "l1"), catalog.map { it.item.id })
    }
}
