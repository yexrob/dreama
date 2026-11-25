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
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.PackageManagerAndMigrationTest
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.MapPrefsStore
import com.limelight.local.stream.StreamSettingsMapper
import com.limelight.local.wall.DetailStateLogic
import com.limelight.local.wall.Load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

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
        private val details: Map<String, Item> = emptyMap(),
        private val browsePages: Map<String, List<Item>> = emptyMap(),
    ) : MediaSource {
        override val source = Source(id, name, SourceKind.PLUGIN, name)
        override suspend fun home(): List<Shelf> = emptyList()
        override suspend fun browse(categoryId: String, page: Int): Page<Item> =
            Page(if (page == 0) browsePages[categoryId].orEmpty() else emptyList(), null)
        override suspend fun detail(itemId: String): Item =
            details[itemId] ?: throw SourceUnavailableException("missing $itemId")
        override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("http://example.invalid/$itemId")
    }

    private suspend fun awaitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timeout waiting for condition")
            delay(10)
        }
    }

    @Test
    fun expandedPaginatorReusesPage1DeduplicatesGuardsCyclesAndRetriesPageErrors() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var p2Failures = 0
            val src = FakeSource("s1", "NAS", onSearch = { _, cursor, _ ->
                when (cursor) {
                    "c2" -> {
                        p2Failures++
                        if (p2Failures == 1) throw SourceUnavailableException("temp fail")
                        // Includes duplicate "i2" from page 1 and returns looping cursor "c2" on page 3 test
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
                        // Buggy plugin returns repeated cursor "c3" -> cycle guard MUST stop pagination
                        Page(
                            items = listOf(Item("i4", ItemKind.MOVIE, "第4条")),
                            nextPage = null,
                            nextCursor = "c3",
                        )
                    }
                    else -> Page(emptyList(), null)
                }
            })

            val paginator = ExpandedSourcePaginator(
                scope = scope,
                source = src,
                query = "潮",
                initialKindFilter = SearchKindFilter.ALL,
                initialItems = listOf(
                    Item("i1", ItemKind.MOVIE, "第1条"),
                    Item("i2", ItemKind.MOVIE, "第2条"),
                ),
                initialNextCursor = "c2",
                ioDispatcher = Dispatchers.Default,
                skeletonDelayMs = 40L,
            )

            // Page 1 reused with 0 calls
            assertEquals(0, src.calls.get())
            assertEquals(listOf("i1", "i2"), paginator.state.value.items.map { it.id })

            // Page 2 fails first time -> preserves Page 1 items intact
            paginator.loadNextPage()
            awaitUntil { paginator.state.value.pageError != null }
            assertEquals(listOf("i1", "i2"), paginator.state.value.items.map { it.id })
            assertEquals(2, paginator.state.value.failedPageNumber)

            // Retry Page 2 succeeds -> appends only i3 (deduping i2)
            paginator.retryPage()
            awaitUntil { !paginator.state.value.loadingNextPage && paginator.state.value.loadedPages == 2 }
            assertNull(paginator.state.value.pageError)
            assertEquals(listOf("i1", "i2", "i3"), paginator.state.value.items.map { it.id })
            assertEquals("c3", paginator.state.value.nextCursor)

            // Page 3 returns repeated cursor "c3" -> cycle guard terminates nextCursor to null
            paginator.loadNextPage()
            awaitUntil { !paginator.state.value.loadingNextPage && paginator.state.value.loadedPages == 3 }
            assertEquals(listOf("i1", "i2", "i3", "i4"), paginator.state.value.items.map { it.id })
            assertNull(paginator.state.value.nextCursor)
            assertTrue(paginator.state.value.reachedEnd)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun expandedPaginatorAutoFetchesUpToThreePagesWhenPluginIgnoresKind() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Plugin ignores `kind` and returns 1 MOVIE + 3 VIDEO per page across 6 pages
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
                scope = scope,
                source = src,
                query = "潮",
                initialKindFilter = SearchKindFilter.ALL,
                initialItems = emptyList(),
                initialNextCursor = null,
                ioDispatcher = Dispatchers.Default,
                minFilteredItems = 10,
                maxAutoFilterPages = 3,
            )

            paginator.switchKindFilter(SearchKindFilter.MOVIE)
            awaitUntil { !paginator.state.value.loadingNextPage }

            // Page 1 + max 3 extra pages = 4 total calls, yielding 4 matching movies and keeping nextCursor = "4"
            assertEquals(4, src.calls.get())
            assertEquals(listOf("m-0", "m-1", "m-2", "m-3"), paginator.state.value.items.map { it.id })
            assertEquals("4", paginator.state.value.nextCursor)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun expandedPaginatorDelaysSkeletonUntil300msThreshold() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val src = FakeSource("s1", "慢源", onSearch = { _, _, _ ->
                gate.await()
                Page(listOf(Item("p2", ItemKind.MOVIE, "第二页")), null)
            })
            val paginator = ExpandedSourcePaginator(
                scope = scope,
                source = src,
                query = "潮",
                initialKindFilter = SearchKindFilter.ALL,
                initialItems = listOf(Item("p1", ItemKind.MOVIE, "第一页")),
                initialNextCursor = "2",
                ioDispatcher = Dispatchers.Default,
                skeletonDelayMs = 80L,
            )

            paginator.loadNextPage()
            awaitUntil { paginator.state.value.loadingNextPage }
            assertFalse("Skeleton should NOT flash immediately", paginator.state.value.showLoadingSkeleton)

            awaitUntil { paginator.state.value.showLoadingSkeleton }
            assertTrue(paginator.state.value.showLoadingSkeleton)

            gate.complete(Unit)
            awaitUntil { !paginator.state.value.loadingNextPage }
            assertFalse(paginator.state.value.showLoadingSkeleton)
        } finally {
            scope.cancel()
        }
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

        // Earlier row 0 MUST remain 100% identical after appending page 2
        assertEquals(rows1[0].map { it.item.id }, rows12[0].map { it.item.id })
        // Row 1 keeps its existing prefix ["6", "7"] and only extends rightward
        assertEquals(listOf("6", "7"), rows12[1].take(2).map { it.item.id })
        // Every row respects 1152 dp width budget
        for (r in rows12) {
            val totalW = r.sumOf { it.shape.widthDp } + (r.size - 1) * SearchConfig.EXPANDED_ITEM_GAP_DP
            assertTrue("Row overflowed 1152dp: $totalW", totalW <= SearchConfig.EXPANDED_ROW_WIDTH_DP)
        }
    }

    @Test
    fun suggestionEngineNormalizesMergesCapsAtFourAndFreezesOnHover() = runBlocking {
        // Normalization & min query length
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
            LocalCatalogEntry(src, ItemKind.MOVIE.let { Item("m2", it, "潮声") }),
        )
        val history = listOf("潮汐表 第 3 季", "潮", "雾港")

        // Build rows: Row 1 verbatim, Row 2 max 2 local (prefix first: 潮声/潮汐表 before 暗潮), Row 3 history -> already 4 rows!
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

        // When only 1 local + 1 history -> remote fills only trailing empty slot(s)
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
            delay(500)
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
            timeoutMs = 80L,
            ioDispatcher = Dispatchers.Default,
        )
        assertEquals(listOf("潮声", "潮汐表"), remote)

        // HoverFreezeBuffer: holds update while hovered, flushes on leave, resets on query change
        val buf = HoverFreezeBuffer(listOf("A"))
        assertEquals(listOf("A1"), buf.onQueryChanged("潮", listOf("A1")))
        buf.onHoverChanged(true)
        assertEquals(listOf("A1"), buf.onUpdate("潮", listOf("A1", "Remote")))
        assertEquals(listOf("A1", "Remote"), buf.onHoverChanged(false))
        buf.onHoverChanged(true)
        assertEquals(listOf("B1"), buf.onQueryChanged("雾", listOf("B1")))
    }

    @Test
    fun historyStoreEnforcesCapDedupeUndoPrivacyWipeAndLogSanitization() {
        val historyPrefs = MapPrefsStore()
        val settingsPrefs = MapPrefsStore()
        val store = SearchHistoryStore(historyPrefs, settingsPrefs, maxEntries = 20)

        assertTrue(store.isRememberEnabled())
        assertTrue(StreamSettingsMapper.read(settingsPrefs, false).rememberSearchHistory)

        // Record 22 distinct queries -> capped at 20, most recent first
        for (i in 1..22) {
            store.record("  影片 $i ")
        }
        assertEquals(20, store.entries.value.size)
        assertEquals("影片 22", store.entries.value.first())
        assertEquals("影片 3", store.entries.value.last())

        // Re-recording an existing entry (with full-width space / case diff) moves it to front without duplicating
        store.record("影片　5")
        assertEquals(20, store.entries.value.size)
        assertEquals("影片 5", store.entries.value.first())
        assertEquals(1, store.entries.value.count { it == "影片 5" })

        // Per-item delete works
        store.remove("影片 5")
        assertEquals(19, store.entries.value.size)
        assertFalse("影片 5" in store.entries.value)

        // Persistence across restart
        val reloaded = SearchHistoryStore(historyPrefs, settingsPrefs, maxEntries = 20)
        assertEquals(store.entries.value, reloaded.entries.value)

        // Clear + Undo restore
        val snap = store.clearWithUndoSnapshot()
        assertTrue(store.entries.value.isEmpty())
        assertEquals(19, snap.size)
        store.restore(snap)
        assertEquals(snap, store.entries.value)

        // Turning off 「记住搜索记录」 immediately wipes history AND blocks new writes & restores
        store.setRememberEnabled(false)
        assertFalse(store.rememberEnabled.value)
        assertFalse(StreamSettingsMapper.read(settingsPrefs, false).rememberSearchHistory)
        assertTrue(store.entries.value.isEmpty())
        assertNull(historyPrefs.getString(SearchHistoryStore.KEY_HISTORY_JSON, null))

        store.record("不应保存")
        assertTrue(store.entries.value.isEmpty())
        store.restore(snap)
        assertTrue(store.entries.value.isEmpty())

        // Log sanitizer never leaks raw query
        val redacted = SearchLogSanitizer.redactQuery("私密片名")
        assertFalse(redacted.contains("私密"))
        assertTrue(redacted.contains("len=4"))
    }

    @Test
    fun idleDataBuilderIncludesWatchedAndResumableLocallyWithZeroNetworkCalls() {
        val s1 = StubMediaSource("s1", "NAS")
        val s2 = StubMediaSource("s2", "本机")
        val shelves = mapOf<String, Load<List<Shelf>>>(
            "s1" to Load.Ready(
                listOf(
                    Shelf(Category("c", "继续观看"), listOf(Item("w1", ItemKind.MOVIE, "雾港")), ShelfStyle.CONTINUE),
                    Shelf(
                        Category("latest", "最近添加"),
                        listOf(
                            Item("f1", ItemKind.FOLDER, "目录跳过"),
                            Item("m1", ItemKind.MOVIE, "冬至以后"),
                            Item("m2", ItemKind.MOVIE, "晚班"),
                        ),
                        ShelfStyle.POSTER,
                    ),
                ),
            ),
            "s2" to Load.Ready(
                listOf(
                    Shelf(Category("local", "最近添加"), listOf(Item("l1", ItemKind.VIDEO, "第七个夏天")), ShelfStyle.THUMB),
                ),
            ),
        )
        val progressRows = listOf(
            // Resumable
            WatchProgress("s1", "w1", "雾港", positionMs = 1_200_000L, durationMs = 3_600_000L, updatedAt = 200L),
            // Finished (watched) -> MUST STILL appear in idle 「最近看过」 (§3.9.3)
            WatchProgress("s1", "m2", "晚班", positionMs = 3_550_000L, durationMs = 3_600_000L, updatedAt = 100L),
        )

        val watched = SearchIdleDataBuilder.buildRecentWatched(progressRows, listOf(s1, s2), shelves)
        assertEquals(2, watched.size)
        assertTrue(watched[0].isResumable)
        assertFalse(watched[1].isResumable)
        assertNull(watched[1].progressFraction)

        val added = SearchIdleDataBuilder.buildRecentAdded(listOf(s1, s2), shelves)
        // Interleaves s1 & s2, skips folders & CONTINUE shelf
        assertEquals(listOf("m1", "l1", "m2"), added.map { it.item.id })
    }

    @Test
    fun pluginRuntimeHandlesMissingOptionalSuggestSilentlyAndCancelsSearchCleanly() = runBlocking {
        val rootDir = Files.createTempDirectory("d3-search-rt").toFile()
        val mgr = PluginPackageManager(
            rootDir = rootDir,
            store = InMemoryPluginStore(),
            secretBox = SoftwareAesSecretBox(),
        )
        var openedSource: com.limelight.local.plugin.PluginMediaSource? = null
        try {
            val manifest = """
                {
                  "api": 1,
                  "id": "com.example.searchnosuggest",
                  "name": "NoSuggestPlugin",
                  "version": "1.0.0",
                  "capabilities": ["search"],
                  "auth": "none"
                }
            """.trimIndent()
            val mainJs = """
                export default {
                  home() { return []; },
                  browse() { return { items: [], next: null }; },
                  detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: 'Movie' }; },
                  playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
                  async search(ctx, { query }) {
                    if (query === 'slow') {
                      await new Promise((r) => setTimeout(r, 2000));
                    }
                    if (!String(query || '').trim()) return { items: [], next: null };
                    return { items: [{ id: 'hit-1', kind: 'movie', title: '命中 ' + query }], next: null };
                  }
                };
            """.trimIndent()
            val zip = PackageManagerAndMigrationTest.buildPluginZip(manifest, mainJs)
            val pkg = mgr.installFromBytes(zip)
            val inst = mgr.createInstance(pkg.manifest.id, "测试无联想源", emptyMap())
            val source = mgr.openSource(inst.instanceId)
            openedSource = source

            // Search works
            val page = source.search("潮")
            assertEquals(listOf("hit-1"), page.items.map { it.id })

            // Optional suggest() is omitted -> returns emptyList silently without throwing or crashing runtime
            assertTrue(source.isSuggestKnownUnsupported)
            val sug = source.suggest("潮")
            assertTrue(sug.isEmpty())

            // Cancelling a slow search propagates CancellationException cleanly without throwing PluginInternalException
            var caughtCancel = false
            val slowJob = launch(Dispatchers.IO) {
                try {
                    source.search("slow")
                } catch (ce: CancellationException) {
                    caughtCancel = true
                    throw ce
                }
            }
            delay(80)
            slowJob.cancel()
            slowJob.join()
            assertTrue("Expected CancellationException on cancelled search", caughtCancel)

            // Runtime recovers cleanly after cancellation
            val afterCancel = source.search("雾港")
            assertEquals(listOf("hit-1"), afterCancel.items.map { it.id })
        } finally {
            runCatching { openedSource?.close() }
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun detailStateLogicResolvesEpisodeClickToOwningSeriesAndFocusesTargetEpisode() = runBlocking {
        val ep1 = Item("ep-1", ItemKind.EPISODE, "第 1 集", parentId = "season-2", index = 1)
        val ep2 = Item("ep-2", ItemKind.EPISODE, "第 2 集", parentId = "season-2", index = 2)
        val ep3 = Item("ep-3", ItemKind.EPISODE, "退潮之后", parentId = "season-2", index = 3)
        val season1 = Item("season-1", ItemKind.SEASON, "第 1 季", parentId = "series-1", index = 1)
        val season2 = Item("season-2", ItemKind.SEASON, "第 2 季", parentId = "series-1", index = 2)
        val series = Item("series-1", ItemKind.SERIES, "潮汐表", seasons = listOf(season1, season2))

        val src = StubMediaSource(
            id = "plugin:nas",
            name = "NAS",
            details = mapOf(
                "ep-3" to ep3,
                "season-2" to season2,
                "series-1" to series,
            ),
            browsePages = mapOf(
                "season-2" to listOf(ep1, ep2, ep3),
            ),
        )

        val target = DetailStateLogic.resolveDetailTarget(src, ep3)
        assertEquals("series-1", target.displayItem.id)
        assertEquals("season-2", target.focusSeasonId)
        assertEquals("ep-3", target.focusEpisodeId)

        val initial = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = src,
            seasons = series.seasons,
            localProgress = emptyMap(),
            loadSeasonEpisodes = { sid -> src.browse(sid, 0).items },
            focusSeasonId = target.focusSeasonId,
            focusEpisodeId = target.focusEpisodeId,
        )
        assertNotNull(initial)
        assertEquals("season-2", initial!!.seasonId)
        assertEquals("ep-3", initial.target?.episode?.id)
        assertEquals(2, initial.target?.seasonNumber)
        assertEquals(3, initial.target?.episodeNumber)
        // Scroll index leaves 1 episode to the left of ep-3 (index 2 -> scroll 1)
        assertEquals(1, DetailStateLogic.initialScrollIndex(listOf(ep1, ep2, ep3), initial.target?.episode?.id))
    }
}
