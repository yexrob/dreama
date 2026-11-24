package com.limelight.local.plugin

import com.limelight.local.media.ItemKind
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.MediaSource
import com.limelight.local.media.PluginInternalException
import com.limelight.local.media.ShelfStyle
import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.media.StereoHint
import com.limelight.local.media.SubtitleFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Full end-to-end conformance tests running the actual repository plugins
 * (`plugins/reference/` and `plugins/jellyfin/`) inside the Android Kotlin
 * host ([PluginPackageManager] -> [PluginRuntime] (QuickJS) -> [PluginMediaSource]
 * -> [PlaybackSessionController]).
 */
class ReferenceAndJellyfinConformanceTest {

    private val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun referencePluginPassesFullConformanceSuiteOnKotlinQuickJsHost(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("d3-ref-conf").toFile()
        val store = InMemoryPluginStore()
        val mgr = PluginPackageManager(
            rootDir = rootDir,
            store = store,
            secretBox = SoftwareAesSecretBox(),
        )
        try {
            val refDir = File(repoRoot, "plugins/reference")
            val zip = PackageManagerAndMigrationTest.buildPluginZip(
                manifestJson = File(refDir, "manifest.json").readText(),
                mainJs = File(refDir, "main.js").readText(),
                locales = mapOf("zh-CN" to File(refDir, "locales/zh-CN.json").readText()),
            )
            val installed = mgr.installFromBytes(zip)
            assertEquals("dev.depth3d.reference", installed.manifest.id)

            val inst = mgr.createInstance("dev.depth3d.reference", "参考插件实例")
            val source = mgr.openSource(inst.instanceId)

            // 1. home() covers all 5 shelf styles & localized metadata
            val shelves = source.home()
            assertEquals(5, shelves.size)
            assertEquals(
                setOf(ShelfStyle.HERO, ShelfStyle.CONTINUE, ShelfStyle.POSTER, ShelfStyle.THUMB, ShelfStyle.CATEGORY),
                shelves.map { it.style }.toSet(),
            )
            assertEquals("cat-library", shelves[2].moreCategoryId)

            // 2. browse() bridges MediaSource integer page <-> opaque cursor + sort/filter
            val rootPage = source.browse(MediaSource.ROOT, page = 0)
            assertEquals(4, rootPage.items.size)
            assertNull(rootPage.nextPage)

            val libPage0 = source.browse("cat-library", page = 0)
            assertEquals(2, libPage0.items.size)
            assertEquals(1, libPage0.nextPage)
            val libPage1 = source.browse("cat-library", page = 1)
            assertEquals(2, libPage1.items.size)
            assertEquals(2, libPage1.nextPage)
            val libPage2 = source.browse("cat-library", page = 2)
            assertEquals(2, libPage2.items.size)
            assertNull(libPage2.nextPage)

            val filtered = source.browseCursor("cat-library", sort = "rating_desc", filters = mapOf("stereo" to "sbs"))
            assertEquals(1, filtered.items.size)
            assertEquals("movie-full", filtered.items[0].id)

            // 3. detail() covers all Item fields, series/season/episodes, and multi-part episodes (分P 选集)
            val movie = source.detail("movie-full")
            assertEquals(ItemKind.MOVIE, movie.kind)
            assertEquals(StereoHint.SBS, movie.stereoHint)
            assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", movie.poster?.blurhash)
            assertEquals(2, movie.people.size)
            assertEquals(3, movie.genres.size)
            assertEquals(2, movie.extra.size)
            assertEquals(2, movie.related.size)
            assertEquals(listOf("4K", "HDR", "SBS"), movie.badges)

            val series = source.detail("series-1")
            assertEquals(ItemKind.SERIES, series.kind)
            assertEquals(1, series.seasons.size)
            val season = source.detail("season-1")
            assertEquals(2, season.episodes.size)
            assertEquals(StereoHint.OU, season.episodes[0].stereoHint)
            assertEquals(StereoHint.MVHEVC, season.episodes[1].stereoHint)

            val multipart = source.detail("multipart-video")
            assertEquals(ItemKind.VIDEO, multipart.kind)
            assertEquals(3, multipart.episodes.size)
            assertEquals("multipart-p1", multipart.episodes[0].id)

            // 4. QR login flow (pending -> scanned -> done + expired + cookie set)
            val qr = source.loginQrStart()
            assertTrue(qr.qr.startsWith("https://api.reference.invalid/qr/"))
            assertEquals("请使用手机应用扫描二维码确认登录", qr.hint)
            assertEquals(LoginPollStatus.PENDING, source.loginPoll(qr.state).status)
            assertEquals(LoginPollStatus.SCANNED, source.loginPoll(qr.state).status)
            val done = source.loginPoll(qr.state)
            assertEquals(LoginPollStatus.DONE, done.status)
            assertEquals("扫码用户", done.user?.name)
            assertEquals("AUTHENTICATED", store.getInstance(inst.instanceId)?.authState)
            assertEquals(LoginPollStatus.EXPIRED, source.loginPoll("expired-state").status)

            // 5. Separated DASH + Referer + Cookie + locked quality + expiring URL refresh
            var clock = 10_000_000L
            val sessionController = PlaybackSessionController(
                source = source,
                clockMs = { clock },
                refreshLeadMs = 10_000L,
            )
            val pb1 = sessionController.load("multipart-p1")
            assertEquals("https://api.reference.invalid/video/multipart", pb1.info.headers["Referer"])
            assertTrue(pb1.info.headers["Cookie"].orEmpty().contains("ref_sess=qr_"))
            assertTrue(pb1.plan.stream is ResolvedStreamPlan.SynthesizedDashMpd)
            assertTrue(pb1.info.dash!!.video[0].url.contains("token=v1"))
            assertEquals("需要会员", pb1.info.qualities.first { it.id == "4k-vip" }.locked)
            assertEquals("multipart-p2", pb1.info.nextItemId)

            // Locked quality rejected by controller
            try {
                sessionController.switchQuality("4k-vip", 10_000L)
                fail("Locked quality must throw SourceForbiddenException")
            } catch (e: SourceForbiddenException) {
                assertEquals("需要会员", e.message)
            }
            // Expired URL re-fetches cleanly with token=v2
            clock = (pb1.info.expiresAtMs ?: clock) + 1_000L
            val pb2 = sessionController.refreshIfExpiredOrFailed(currentPositionMs = 33_000L)!!
            assertTrue(pb2.info.dash!!.video[0].url.contains("token=v2"))
            assertEquals(33_000L, pb2.resumePositionMs)

            // 6. Progressive DASH (MergingMediaSource), Inline MPD, Live, Direct HLS (all 4 subtitle formats), DRM Unsupported
            val pbProg = PlaybackMediaSourceFactory().plan(source.playback("dash-progressive"))
            assertTrue(pbProg.stream is ResolvedStreamPlan.MergedProgressiveTracks)

            val pbMpd = PlaybackMediaSourceFactory().plan(source.playback("mpd-direct"))
            assertTrue(pbMpd.stream is ResolvedStreamPlan.InlineMpd)

            val pbLive = source.playback("live-1")
            assertTrue(pbLive.isLive)

            val pbMovie = source.playback("movie-full")
            assertEquals(
                setOf(SubtitleFormat.SRT, SubtitleFormat.VTT, SubtitleFormat.ASS, SubtitleFormat.JSON),
                pbMovie.subtitles.map { it.format }.toSet(),
            )
            assertEquals(StereoHint.SBS, pbMovie.stereoHint)

            try {
                source.playback("drm-item")
                fail("DRM item must throw SourceUnsupportedException")
            } catch (_: SourceUnsupportedException) {
            }

            // 7. Optional capabilities: categories, search, suggest, filters, danmaku segments, favorites, played, progress
            assertEquals(3, source.categories().size)
            assertTrue(source.search("星际").items.any { it.id == "movie-full" })
            assertTrue(source.suggest("星").isNotEmpty())
            val filters = source.filters("cat-library")
            assertEquals(3, filters.sorts.size)
            assertEquals(1, filters.filters.size)

            val dmSeg1 = source.danmaku("multipart-p1", 0L, 60_000L)
            val dmSeg2 = source.danmaku("multipart-p1", 60_000L, 120_000L)
            assertEquals(3, dmSeg1.size)
            assertEquals(1, dmSeg2.size)
            assertEquals(DanmakuMode.TOP, dmSeg1[1].mode)

            source.setFavorite("episode-1", true)
            assertEquals(true, source.detail("episode-1").favorite)
            source.setPlayed("episode-1", true)
            assertEquals(true, source.detail("episode-1").played)
            source.reportProgress("episode-1", 120_000L, 2_880_000L, ProgressEvent.PROGRESS, "sess-1")

            // 8. All 9 error codes + uncaught error
            assertThrowsType<SourceNeedsAuthException> { source.detail("err-NeedsAuth") }
            assertEquals("NEEDS_AUTH", store.getInstance(inst.instanceId)?.authState)
            assertThrowsType<SourceAuthFailedException> { source.detail("err-AuthFailed") }
            assertThrowsType<SourceNeedsSetupException> { source.detail("err-NeedsSetup") }
            assertThrowsType<SourceUnavailableException> { source.detail("err-Unavailable") }
            assertThrowsType<ItemNotFoundException> { source.detail("err-NotFound") }
            assertThrowsType<SourceForbiddenException> { source.detail("err-Forbidden") }
            val rl = assertThrowsType<SourceRateLimitedException> { source.detail("err-RateLimited") }
            assertEquals(42, rl.retryAfterSec)
            assertThrowsType<SourceUnsupportedException> { source.detail("err-Unsupported") }
            assertThrowsType<PluginInternalException> { source.detail("err-Internal") }
            assertThrowsType<PluginInternalException> { source.detail("err-Uncaught") }

            // 9. Form and Device auth variants on separate manifest instances
            val deviceManifestJson = File(refDir, "manifest.json").readText()
                .replace("\"id\": \"dev.depth3d.reference\"", "\"id\": \"dev.depth3d.refdevice\"")
                .replace("\"auth\": \"qr\"", "\"auth\": \"device\"")
            mgr.installFromBytes(
                PackageManagerAndMigrationTest.buildPluginZip(deviceManifestJson, File(refDir, "main.js").readText()),
            )
            val devInst = mgr.createInstance(
                "dev.depth3d.refdevice",
                "设备码实例",
                mapOf("authFlow" to JsonPrimitive("device")),
            )
            val devSrc = mgr.openSource(devInst.instanceId)
            val devStart = devSrc.loginDeviceStart()
            assertEquals("D3-8492", devStart.code)
            assertTrue(devStart.url.startsWith("https://"))

            val formManifestJson = File(refDir, "manifest.json").readText()
                .replace("\"id\": \"dev.depth3d.reference\"", "\"id\": \"dev.depth3d.refform\"")
                .replace("\"auth\": \"qr\"", "\"auth\": \"form\"")
            mgr.installFromBytes(
                PackageManagerAndMigrationTest.buildPluginZip(formManifestJson, File(refDir, "main.js").readText()),
            )
            val formInst = mgr.createInstance(
                "dev.depth3d.refform",
                "表单实例",
                mapOf("username" to JsonPrimitive("alice"), "password" to JsonPrimitive("good")),
            )
            val formSrc = mgr.openSource(formInst.instanceId)
            val formRes = formSrc.loginForm()
            assertTrue(formRes.ok)
            assertEquals("alice", formRes.user?.name)
            formSrc.logout()
        } finally {
            mgr.shutdownAll()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun jellyfinPluginWorksEndToEndAgainstRecordedResponses(): Unit = runBlocking {
        val jfDir = File(repoRoot, "plugins/jellyfin")
        val fixtures = json.parseToJsonElement(
            File(jfDir, "fixtures/recorded-responses.json").readText(),
        ).jsonObject
        val seenPaths = CopyOnWriteArrayList<String>()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                seenPaths += "${request.method} $path"

                fun okJson(key: String) = MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(fixtures[key]!!.toString())

                if (path == "/Users/AuthenticateByName" && request.method == "POST") {
                    val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return if (body["Pw"]?.jsonPrimitive?.content == "wrong-pw") {
                        MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}""")
                    } else {
                        okJson("authSuccess")
                    }
                }
                val expectedToken = fixtures["authSuccess"]!!.jsonObject["AccessToken"]!!.jsonPrimitive.content
                if (request.getHeader("X-Emby-Token") != expectedToken) {
                    return MockResponse().setResponseCode(401)
                }

                return when {
                    path == "/Users/user-alice-001/Items/Resume" -> okJson("resume")
                    path == "/Shows/NextUp" -> okJson("nextUp")
                    path == "/Users/user-alice-001/Views" -> okJson("views")
                    path == "/Users/user-alice-001/Items/Latest" -> {
                        if (url.queryParameter("ParentId") == "lib-movies") okJson("latestMovies")
                        else okJson("latestShows")
                    }
                    path == "/Users/user-alice-001/Items" && url.queryParameter("SearchTerm") != null ->
                        okJson("searchResults")
                    path == "/Users/user-alice-001/Items" -> {
                        if (url.queryParameter("StartIndex") == "1") okJson("browseMoviesPage2")
                        else okJson("browseMoviesPage1")
                    }
                    path == "/Users/user-alice-001/Items/mov-dune" -> okJson("movieDetail")
                    path == "/Items/mov-dune/Similar" -> okJson("similarMovies")
                    path == "/Users/user-alice-001/Items/ser-threebody" -> okJson("seriesDetail")
                    path == "/Shows/ser-threebody/Seasons" -> okJson("seriesSeasons")
                    path == "/Users/user-alice-001/Items/sea-threebody-1" -> okJson("seasonDetail")
                    path == "/Shows/ser-threebody/Episodes" -> okJson("seasonEpisodes")
                    path == "/Items/mov-dune/PlaybackInfo" -> okJson("playbackInfo")
                    path == "/Search/Hints" -> okJson("searchHints")
                    path.startsWith("/Sessions/Playing") ||
                        path.contains("/FavoriteItems/") ||
                        path.contains("/PlayedItems/") ||
                        path == "/Sessions/Logout" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val rootDir = Files.createTempDirectory("d3-jf-test").toFile()
        val store = InMemoryPluginStore()
        val okHttp = OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.10", server.port))
            .build()
        val mgr = PluginPackageManager(
            rootDir = rootDir,
            store = store,
            secretBox = SoftwareAesSecretBox(),
            okHttpClient = okHttp,
        )

        try {
            val zip = PackageManagerAndMigrationTest.buildPluginZip(
                manifestJson = File(jfDir, "manifest.json").readText(),
                mainJs = File(jfDir, "main.js").readText(),
                locales = mapOf("zh-CN" to File(jfDir, "locales/zh-CN.json").readText()),
            )
            mgr.installFromBytes(zip)

            val inst = mgr.createInstance(
                pluginId = "dev.depth3d.jellyfin",
                displayName = "客厅 Jellyfin",
                config = mapOf(
                    "server" to JsonPrimitive("http://192.168.1.10:${server.port}"),
                    "username" to JsonPrimitive("alice"),
                    "password" to JsonPrimitive("correct-pw"),
                    "quality" to JsonPrimitive("direct"),
                ),
            )
            val source = mgr.openSource(inst.instanceId) { listOf(InetAddress.getByName("192.168.1.10")) }

            // Before login -> NeedsAuth
            assertThrowsType<SourceNeedsAuthException> { source.home() }

            // Login succeeds
            val login = source.loginForm()
            assertTrue(login.ok)
            assertEquals("Alice", login.user?.name)
            assertNotNull(login.user?.avatar)

            // Home shelves (Resume, NextUp, Latest Movies, Latest Shows)
            val shelves = source.home()
            assertEquals(4, shelves.size)
            assertEquals(ShelfStyle.CONTINUE, shelves[0].style)
            assertEquals("沙丘", shelves[0].items[0].title)
            assertEquals(StereoHint.SBS, shelves[0].items[0].stereoHint)

            // Categories (music filtered out)
            val cats = source.categories()
            assertEquals(2, cats.size)
            assertEquals("lib-movies", cats[0].id)

            // Browse pagination via MediaSource integer page API
            val p0 = source.browse("lib-movies", page = 0)
            assertEquals(1, p0.items.size)
            assertEquals(1, p0.nextPage)
            val p1 = source.browse("lib-movies", page = 1)
            assertEquals("mov-arrival", p1.items[0].id)
            assertNull(p1.nextPage)

            // Movie detail + Series/Season/Episodes hierarchy
            val dune = source.detail("mov-dune")
            assertEquals(2, dune.people.size)
            assertEquals(1, dune.related.size)
            assertTrue(dune.badges.contains("4K"))
            assertEquals(StereoHint.SBS, dune.stereoHint)

            val series = source.detail("ser-threebody")
            assertEquals(1, series.seasons.size)
            val season = source.detail("sea-threebody-1")
            assertEquals(2, season.episodes.size)

            // Direct Play + subtitles (PGS bitmap skipped; SRT/VTT/ASS kept) + audios
            val pbDirect = source.playback("mov-dune")
            assertTrue(pbDirect.url.contains("/Videos/mov-dune/stream?"))
            assertEquals(3, pbDirect.subtitles.size)
            assertEquals(2, pbDirect.audios.size)
            assertEquals(StereoHint.SBS, pbDirect.stereoHint)
            assertEquals(4, pbDirect.qualities.size)

            // Transcode quality switch -> HLS master.m3u8
            val pb720 = source.playback("mov-dune", quality = "720p", audio = "2")
            assertTrue(pb720.url.contains("/master.m3u8?"))
            assertTrue(pb720.url.contains("MaxHeight=720"))
            assertTrue(pb720.url.contains("AudioStreamIndex=2"))
            assertEquals("application/x-mpegURL", pb720.mimeType)

            // Search & suggest
            assertEquals("mov-dune", source.search("沙丘").items[0].id)
            assertEquals(listOf("沙丘", "三体"), source.suggest("沙"))

            // Progress, favorites, played, logout
            source.reportProgress("mov-dune", 180_000L, 9_300_000L, ProgressEvent.START, pbDirect.sessionId)
            source.reportProgress("mov-dune", 200_000L, 9_300_000L, ProgressEvent.PAUSE, pbDirect.sessionId)
            source.reportProgress("mov-dune", 200_000L, 9_300_000L, ProgressEvent.STOP, pbDirect.sessionId)
            source.setFavorite("mov-dune", true)
            source.setPlayed("mov-dune", true)
            source.logout()

            assertTrue(seenPaths.contains("POST /Sessions/Playing"))
            assertTrue(seenPaths.contains("POST /Sessions/Playing/Progress"))
            assertTrue(seenPaths.contains("POST /Sessions/Playing/Stopped"))
            assertTrue(seenPaths.contains("POST /Users/user-alice-001/FavoriteItems/mov-dune"))
            assertTrue(seenPaths.contains("POST /Users/user-alice-001/PlayedItems/mov-dune"))
            assertTrue(seenPaths.contains("POST /Sessions/Logout"))

            // Wrong password produces SourceAuthFailedException
            mgr.updateInstanceConfig(
                inst.instanceId,
                config = mapOf(
                    "server" to JsonPrimitive("http://192.168.1.10:${server.port}"),
                    "username" to JsonPrimitive("alice"),
                    "password" to JsonPrimitive("wrong-pw"),
                ),
            )
            val badSrc = mgr.openSource(inst.instanceId) { listOf(InetAddress.getByName("192.168.1.10")) }
            assertThrowsType<SourceAuthFailedException> { badSrc.loginForm() }
        } finally {
            mgr.shutdownAll()
            server.shutdown()
            rootDir.deleteRecursively()
        }
    }

    private suspend inline fun <reified T : Throwable> assertThrowsType(crossinline block: suspend () -> Unit): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName} to be thrown")
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${t.javaClass.name}: ${t.message}", t)
        }
        throw AssertionError("Unreachable")
    }
}
