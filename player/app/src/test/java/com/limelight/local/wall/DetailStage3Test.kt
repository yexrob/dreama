package com.limelight.local.wall

import com.limelight.local.media.ExtraField
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page as MediaPage
import com.limelight.local.media.PersonCredit
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.Shelf
import com.limelight.local.media.Source
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.StereoHint
import com.limelight.local.media.WatchProgress
import com.limelight.local.stream.XmlStringLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailStage3Test {
    private val strings = XmlStringLookup

    @Test
    fun classifiesSeriesMovieAndFolderLayouts() {
        val movie = Item("m1", ItemKind.MOVIE, "雾港")
        val video = Item("v1", ItemKind.VIDEO, "Demo")
        val series = Item("s1", ItemKind.SERIES, "潮汐表")
        val multiPart = Item(
            id = "mp1",
            kind = ItemKind.VIDEO,
            title = "合集",
            episodes = listOf(Item("p1", ItemKind.EPISODE, "第一部分", index = 1)),
        )
        assertFalse(DetailStateLogic.isSeriesLayout(movie))
        assertFalse(DetailStateLogic.isSeriesLayout(video))
        assertTrue(DetailStateLogic.isSeriesLayout(series))
        assertTrue(DetailStateLogic.isSeriesLayout(multiPart))
    }

    @Test
    fun picksNextEpisodeAcrossInProgressWatchedAndUnwatchedStates() {
        val ep1 = Item("e1", ItemKind.EPISODE, "灯塔", index = 1, durationMs = 3_000_000L, played = true)
        val ep2 = Item("e2", ItemKind.EPISODE, "退潮之后", index = 2, durationMs = 3_000_000L, played = true)
        val ep3 = Item("e3", ItemKind.EPISODE, "夜航", index = 3, durationMs = 3_000_000L, progressMs = 1_500_000L)
        val ep4 = Item("e4", ItemKind.EPISODE, "迷雾", index = 4, durationMs = 3_000_000L)
        val episodes = listOf(ep1, ep2, ep3, ep4)

        // 1. Remote in-progress episode is chosen with resume position and scroll index keeping 1 watched on left
        val t1 = DetailStateLogic.pickPlayTarget(2, episodes, emptyMap())!!
        assertEquals("e3", t1.episode.id)
        assertEquals(1_500_000L, t1.resumePositionMs)
        assertTrue(t1.isResume)
        assertEquals("继续播放 · 第 2 季第 3 集", DetailStateLogic.formatPrimarySeriesButton(t1, true, strings))
        assertEquals(1, DetailStateLogic.initialScrollIndex(episodes, t1.episode.id))

        // 2. Newer local WatchProgress overrides remote progress
        val local = mapOf(
            "e4" to WatchProgress("plugin:1", "e4", "迷雾", 600_000L, 3_000_000L, updatedAt = 9_999L),
        )
        val t2 = DetailStateLogic.pickPlayTarget(2, episodes, local)!!
        assertEquals("e4", t2.episode.id)
        assertEquals(600_000L, t2.resumePositionMs)

        // 3. When none in progress, picks first unwatched after watched episodes
        val watchedOnly = listOf(
            ep1,
            ep2,
            Item("e3", ItemKind.EPISODE, "夜航", index = 3, durationMs = 3_000_000L),
        )
        val t3 = DetailStateLogic.pickPlayTarget(1, watchedOnly, emptyMap())!!
        assertEquals("e3", t3.episode.id)
        assertEquals(0L, t3.resumePositionMs)
        assertTrue(t3.isResume)
        assertEquals("继续播放 · 第 1 季第 3 集", DetailStateLogic.formatPrimarySeriesButton(t3, true, strings))

        // 4. All unwatched picks episode 1 with "播放"
        val fresh = listOf(
            Item("e1", ItemKind.EPISODE, "开端", index = 1, durationMs = 2_400_000L),
            Item("e2", ItemKind.EPISODE, "继续", index = 2, durationMs = 2_400_000L),
        )
        val t4 = DetailStateLogic.pickPlayTarget(1, fresh, emptyMap())!!
        assertEquals("e1", t4.episode.id)
        assertFalse(t4.isResume)
        assertEquals("播放 · 第 1 季第 1 集", DetailStateLogic.formatPrimarySeriesButton(t4, true, strings))
        assertEquals("播放 · 第 1 集", DetailStateLogic.formatPrimarySeriesButton(t4, false, strings))
    }

    @Test
    fun seasonEpisodeLoaderPaginatesLazilyHandlesFallbackAndRetriesErrors() = runBlocking {
        var browseCalls = 0
        val pagedSource = object : MediaSource {
            override val source = Source("plugin:1", "Jellyfin", SourceKind.PLUGIN, "test")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> {
                browseCalls++
                return when (page) {
                    0 -> MediaPage(
                        items = listOf(Item("e1", ItemKind.EPISODE, "第1集", index = 1)),
                        nextPage = 1,
                    )
                    1 -> MediaPage(
                        items = listOf(Item("e2", ItemKind.EPISODE, "第2集", index = 2)),
                        nextPage = null,
                    )
                    else -> MediaPage(emptyList(), null)
                }
            }

            override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.SEASON, itemId)
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("http://example.invalid/a.mp4")
        }

        val loader = SeasonEpisodeLoader(pagedSource, "season-1")
        // Initial page loads ONLY page 0 (never eagerly loads page 1)
        val s1 = loader.loadNextPage()
        assertEquals(1, browseCalls)
        assertEquals(1, s1.items.size)
        assertEquals(1, s1.nextPage)

        // Second call loads page 1 and completes
        val s2 = loader.loadNextPage()
        assertEquals(2, browseCalls)
        assertEquals(2, s2.items.size)
        assertNull(s2.nextPage)

        // Third call is a no-op
        loader.loadNextPage()
        assertEquals(2, browseCalls)

        // Fallback to detail(seasonId).episodes when browse returns empty page 0 (Jellyfin fixture style)
        val detailFallbackSource = object : MediaSource {
            override val source = Source("plugin:2", "Jellyfin", SourceKind.PLUGIN, "test")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item = Item(
                id = itemId,
                kind = ItemKind.SEASON,
                title = "第 1 季",
                episodes = listOf(
                    Item("ep-a", ItemKind.EPISODE, "Pilot", index = 1),
                    Item("ep-b", ItemKind.EPISODE, "Second", index = 2),
                ),
            )
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("http://example.invalid/a.mp4")
        }
        val fallbackLoader = SeasonEpisodeLoader(detailFallbackSource, "s-1")
        val fallbackState = fallbackLoader.loadNextPage()
        assertEquals(2, fallbackState.items.size)
        assertEquals("ep-a", fallbackState.items[0].id)
        assertNull(fallbackState.nextPage)

        // Error followed by resetForRetry recovers cleanly
        var failOnce = true
        val flakySource = object : MediaSource {
            override val source = Source("plugin:3", "Jellyfin", SourceKind.PLUGIN, "test")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> {
                if (failOnce) {
                    failOnce = false
                    throw SourceUnavailableException("网络断开")
                }
                return MediaPage(listOf(Item("ok-1", ItemKind.EPISODE, "恢复", index = 1)), null)
            }

            override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.SEASON, itemId)
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("http://example.invalid/a.mp4")
        }
        val flakyLoader = SeasonEpisodeLoader(flakySource, "s-flaky")
        val errState = flakyLoader.loadNextPage()
        assertNotNull(errState.error)
        assertTrue(errState.items.isEmpty())

        flakyLoader.resetForRetry()
        val recovered = flakyLoader.loadNextPage()
        assertNull(recovered.error)
        assertEquals(1, recovered.items.size)
    }

    @Test
    fun formatsMovieAndSeriesMetadataAndBottomInfoCards() {
        val movie = Item(
            id = "m1",
            kind = ItemKind.MOVIE,
            title = "雾港",
            year = 2025,
            genres = listOf("剧情", "悬疑", "文艺"),
            durationMs = 6_720_000L, // 1h 52m
            rating = 8.6f,
            badges = listOf("4K", "HDR"),
            stereoHint = StereoHint.SBS,
            people = listOf(PersonCredit("张导", "导演"), PersonCredit("李主演", "主演")),
            extra = listOf(ExtraField("音轨", "Dolby Atmos")),
        )
        val movieMeta = DetailStateLogic.formatMovieMeta(movie, "客厅 Jellyfin", strings)
        assertTrue(movieMeta.contains("2025"))
        assertTrue(movieMeta.contains("剧情 / 悬疑"))
        assertTrue(movieMeta.contains("1 小时 52 分"))
        assertTrue(movieMeta.contains("★ 8.6"))
        assertTrue(movieMeta.contains("4K HDR"))
        assertTrue(movieMeta.contains("来自 客厅 Jellyfin"))

        val cards = DetailStateLogic.buildMovieInfoCards(movie, "客厅 Jellyfin", strings)
        assertEquals(4, cards.size)
        assertEquals("左右 3D", cards[0].second)
        assertEquals("音轨", cards[1].first)
        assertEquals("Dolby Atmos", cards[1].second)
        assertTrue(cards[2].second.contains("张导"))

        val series = Item("s1", ItemKind.SERIES, "潮汐表", year = 2024, rating = 9.1f)
        val seriesMeta = DetailStateLogic.formatSeriesMeta(series, 3, "NAS", strings)
        assertEquals("剧集 · 3 季 · 2024 · ★ 9.1 · 来自 NAS", seriesMeta)
    }
}
