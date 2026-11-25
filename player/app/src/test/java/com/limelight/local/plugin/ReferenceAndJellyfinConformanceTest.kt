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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Full end-to-end conformance tests running the actual repository plugins
 * (`plugins/reference/` and `plugins/jellyfin/`) inside the Android Kotlin
 * host ([PluginPackageManager] -> [PluginRuntime] (QuickJS) -> [PluginMediaSource]
 * -> [PlaybackSessionController]).
 *
 * Covers S7 (`StereoHint.MVHEVC` enum order & detached `SourceNeedsAuthException`),
 * S8 (§11.2 forward-compatible null/enum/range/danmaku-color handling),
 * S10 (Jellyfin h264 mapping, no MVC->mvhevc, no api_key in URLs, no unbounded ms_* storage),
 * S11 (real recorded Jellyfin 12.4.0 Docker responses including Direct Play & HLS transcode),
 * and Small #5 (nullable `PlaybackInfo.title` fallback).
 */
class ReferenceAndJellyfinConformanceTest {

    private val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun s7EnumOrderAndExceptionHierarchyArePreserved() {
        // S7: MVHEVC MUST be appended at the end so existing enum ordinals never shift
        val values = StereoHint.entries
        assertEquals(StereoHint.MONO, values[0])
        assertEquals(StereoHint.SBS, values[1])
        assertEquals(StereoHint.OU, values[2])
        assertEquals(StereoHint.VR180, values[3])
        assertEquals(StereoHint.VR360, values[4])
        assertEquals(StereoHint.MVHEVC, values[5])
        assertEquals(6, values.size)

        // S7: SourceNeedsAuthException MUST NOT inherit from SourceNeedsSetupException
        val authEx: Throwable = SourceNeedsAuthException("请登录")
        assertFalse(authEx is SourceNeedsSetupException)
    }

    @Test
    fun referencePluginPassesFullConformanceAndForwardCompatOnKotlinQuickJsHost(): Unit = runBlocking {
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
            assertEquals("dev.dreama.reference", installed.manifest.id)

            val inst = mgr.createInstance("dev.dreama.reference", "参考插件实例")
            val source = mgr.openSource(inst.instanceId)

            // 1. home() covers all 5 shelf styles & localized metadata
            val shelves = source.home()
            assertEquals(5, shelves.size)
            assertEquals(
                setOf(ShelfStyle.HERO, ShelfStyle.CONTINUE, ShelfStyle.POSTER, ShelfStyle.THUMB, ShelfStyle.CATEGORY),
                shelves.map { it.style }.toSet(),
            )
            assertEquals("cat-library", shelves[2].moreCategoryId)

            // 2. S8 (§11.2): forward-compatible browse with null optional fields, unknown kind dropped,
            // out-of-range year/rating ignored, unknown stereo falls back to MONO
            val compat = source.browseCursor("compat-forward")
            assertEquals("Unknown Item.kind must be dropped without failing whole page", 1, compat.items.size)
            val compatItem = compat.items.single()
            assertEquals("compat-valid", compatItem.id)
            assertNull(compatItem.subtitle)
            assertNull(compatItem.poster)
            assertNull("Out-of-range year 99999 must be ignored", compatItem.year)
            assertNull("Out-of-range rating 120 must be ignored", compatItem.rating)
            assertEquals("Unknown stereo enum must fall back to MONO", StereoHint.MONO, compatItem.stereoHint)

            // 3. browse() pagination + sort/filter
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

            // 4. detail() covers all Item fields, series/season/episodes, and multi-part episodes
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

            // 5. QR login flow (pending -> scanned -> done + expired + cookie set)
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

            // 6. Separated DASH + durationMs + dvb:priority + Referer + Cookie + locked quality + expiring URL refresh
            var clock = 10_000_000L
            val sessionController = source.createPlaybackSessionController(clockMs = { clock })
            val pb1 = sessionController.load("multipart-p1")
            assertEquals(900_000L, pb1.info.durationMs)
            assertEquals("https://api.reference.invalid/video/multipart", pb1.info.headers["Referer"])
            assertTrue(pb1.info.headers["Cookie"].orEmpty().contains("ref_sess=qr_"))
            val synth = pb1.plan.stream as ResolvedStreamPlan.SynthesizedDashMpd
            assertTrue(synth.mpdXml.contains("dvb:priority=\"1\""))
            assertTrue(synth.mpdXml.contains("mediaPresentationDuration=\"PT900.000S\""))
            assertTrue(pb1.info.dash!!.video[0].url.contains("token=v1"))
            assertEquals("需要会员", pb1.info.qualities.first { it.id == "4k-vip" }.locked)
            assertEquals("multipart-p2", pb1.info.nextItemId)

            try {
                sessionController.switchQuality("4k-vip", 10_000L)
                fail("Locked quality must throw SourceForbiddenException")
            } catch (e: SourceForbiddenException) {
                assertEquals("需要会员", e.message)
            }
            clock = (pb1.info.expiresAtMs ?: clock) + 1_000L
            val pb2 = sessionController.refreshIfExpiredOrFailed(currentPositionMs = 33_000L)!!
            assertTrue(pb2.info.dash!!.video[0].url.contains("token=v2"))
            assertEquals(33_000L, pb2.resumePositionMs)

            // 7. Progressive DASH, Inline MPD, Live, live+dash Unsupported, Small #5 nullable title when uncached
            val pbFactory = source.createPlaybackFactory()
            val pbProg = pbFactory.plan(source.playback("dash-progressive"))
            assertTrue(pbProg.stream is ResolvedStreamPlan.MergedProgressiveTracks)
            assertNull("Uncached item without explicit PlaybackInfo.title must yield null title", pbProg.info.title)

            val pbMpd = pbFactory.plan(source.playback("mpd-direct"))
            assertTrue(pbMpd.stream is ResolvedStreamPlan.InlineMpd)

            val pbLive = source.playback("live-1")
            assertTrue(pbLive.isLive)

            assertThrowsType<SourceUnsupportedException> { source.playback("live-dash-invalid") }
            assertThrowsType<SourceUnsupportedException> { source.playback("drm-item") }

            val pbMovie = source.playback("movie-full")
            assertEquals(
                setOf(SubtitleFormat.SRT, SubtitleFormat.VTT, SubtitleFormat.ASS, SubtitleFormat.JSON),
                pbMovie.subtitles.map { it.format }.toSet(),
            )
            assertEquals(StereoHint.SBS, pbMovie.stereoHint)

            // 8. Optional capabilities including S8 integer & hex danmaku colors
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
            assertEquals("#FF6699", dmSeg1[1].color) // parsed from integer 0xFF6699 (§11.2)

            source.setFavorite("episode-1", true)
            assertEquals(true, source.detail("episode-1").favorite)
            source.setPlayed("episode-1", true)
            assertEquals(true, source.detail("episode-1").played)
            source.reportProgress("episode-1", 120_000L, 2_880_000L, ProgressEvent.PROGRESS, "sess-1")

            // 9. All 9 error codes + uncaught error
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

            // 10. Form and Device auth variants
            val deviceManifestJson = File(refDir, "manifest.json").readText()
                .replace("\"id\": \"dev.dreama.reference\"", "\"id\": \"dev.dreama.refdevice\"")
                .replace("\"auth\": \"qr\"", "\"auth\": \"device\"")
            mgr.installFromBytes(
                PackageManagerAndMigrationTest.buildPluginZip(deviceManifestJson, File(refDir, "main.js").readText()),
            )
            val devInst = mgr.createInstance(
                "dev.dreama.refdevice",
                "设备码实例",
                mapOf("authFlow" to JsonPrimitive("device")),
            )
            val devSrc = mgr.openSource(devInst.instanceId)
            val devStart = devSrc.loginDeviceStart()
            assertEquals("D3-8492", devStart.code)
            assertTrue(devStart.url.startsWith("https://"))

            val formManifestJson = File(refDir, "manifest.json").readText()
                .replace("\"id\": \"dev.dreama.reference\"", "\"id\": \"dev.dreama.refform\"")
                .replace("\"auth\": \"qr\"", "\"auth\": \"form\"")
            mgr.installFromBytes(
                PackageManagerAndMigrationTest.buildPluginZip(formManifestJson, File(refDir, "main.js").readText()),
            )
            val formInst = mgr.createInstance(
                "dev.dreama.refform",
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
    fun jellyfinPluginWorksEndToEndAgainstRealRecordedJellyfin12ResponsesS10S11(): Unit = runBlocking {
        val jfDir = File(repoRoot, "plugins/jellyfin")
        val fixtures = json.parseToJsonElement(
            File(jfDir, "fixtures/recorded-responses.json").readText(),
        ).jsonObject
        val ids = fixtures["_meta"]!!.jsonObject["ids"]!!.jsonObject
        val moviesViewId = ids["moviesViewId"]!!.jsonPrimitive.content
        val h264MovieId = ids["h264MovieId"]!!.jsonPrimitive.content
        val hevcMovieId = ids["hevcMovieId"]!!.jsonPrimitive.content
        val seriesId = ids["seriesId"]!!.jsonPrimitive.content
        val seasonId = ids["seasonId"]!!.jsonPrimitive.content
        val expectedToken = fixtures["authSuccess"]!!.jsonObject["AccessToken"]!!.jsonPrimitive.content

        val seenPaths = CopyOnWriteArrayList<String>()
        val seenAuthHeaders = CopyOnWriteArrayList<String>()
        val playbackReqBodies = CopyOnWriteArrayList<String>()
        val progressBodies = CopyOnWriteArrayList<Pair<String, String>>()
        val forceExpiredToken = java.util.concurrent.atomic.AtomicBoolean(false)

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                seenPaths += "${request.method} $path"

                fun okRaw(el: kotlinx.serialization.json.JsonElement) = MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(el.toString())

                if (path == "/System/Info/Public") {
                    return okRaw(fixtures["systemInfoPublic"]!!)
                }
                if (path == "/Users/AuthenticateByName" && request.method == "POST") {
                    val authHdr = request.getHeader("Authorization").orEmpty()
                    seenAuthHeaders += authHdr
                    val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return if (body["Pw"]?.jsonPrimitive?.content == "wrong-pw") {
                        MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}""")
                    } else {
                        okRaw(fixtures["authSuccess"]!!)
                    }
                }
                val authHdr = request.getHeader("Authorization").orEmpty()
                seenAuthHeaders += authHdr
                if (forceExpiredToken.get() || !authHdr.contains("""Token="$expectedToken"""")) {
                    return MockResponse().setResponseCode(401)
                }

                return when {
                    path == "/UserItems/Resume" -> okRaw(fixtures["resume"]!!)
                    path == "/Shows/NextUp" -> okRaw(fixtures["nextUp"]!!)
                    path == "/UserViews" -> okRaw(fixtures["views"]!!)
                    path == "/Items/Latest" -> {
                        val pid = url.queryParameter("ParentId").orEmpty()
                        okRaw(fixtures["latestByParent"]!!.jsonObject[pid] ?: json.parseToJsonElement("[]"))
                    }
                    path == "/Items" && url.queryParameter("SearchTerm") != null -> okRaw(fixtures["searchResults"]!!)
                    path == "/Items" -> {
                        val pid = url.queryParameter("ParentId").orEmpty()
                        okRaw(
                            fixtures["browseByParent"]!!.jsonObject[pid]
                                ?: json.parseToJsonElement("""{"Items":[],"TotalRecordCount":0}"""),
                        )
                    }
                    path.endsWith("/Similar") -> okRaw(fixtures["similar"]!!)
                    path.endsWith("/PlaybackInfo") -> {
                        val reqText = request.body.readUtf8()
                        playbackReqBodies += reqText
                        val reqObj = json.parseToJsonElement(reqText).jsonObject
                        val dp = reqObj["DeviceProfile"]!!.jsonObject["DirectPlayProfiles"]!!.jsonArray
                        val itemId = path.split("/")[2]
                        val key = "$itemId:${if (dp.isEmpty()) "transcode" else "direct"}"
                        okRaw(fixtures["playbackInfo"]!!.jsonObject[key]!!)
                    }
                    Regex("^/Items/[^/]+$").matches(path) -> {
                        val itemId = path.split("/")[2]
                        okRaw(fixtures["itemsById"]!!.jsonObject[itemId]!!)
                    }
                    path == "/Shows/$seriesId/Seasons" -> okRaw(fixtures["seriesSeasons"]!!)
                    path == "/Shows/$seriesId/Episodes" -> okRaw(fixtures["seasonEpisodes"]!!)
                    path == "/Search/Hints" -> okRaw(fixtures["searchHints"]!!)
                    path.startsWith("/Sessions/Playing") -> {
                        progressBodies += path to request.body.readUtf8()
                        MockResponse().setResponseCode(204)
                    }
                    path.startsWith("/UserFavoriteItems/") ||
                        path.startsWith("/UserPlayedItems/") ||
                        path == "/Sessions/Logout" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val rootDir = Files.createTempDirectory("d3-jf-test").toFile()
        val store = InMemoryPluginStore()
        val secretBox = SoftwareAesSecretBox()
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val mgr = PluginPackageManager(
            rootDir = rootDir,
            store = store,
            secretBox = secretBox,
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
                pluginId = "dev.dreama.jellyfin",
                displayName = "客厅 Jellyfin",
                config = mapOf(
                    "server" to JsonPrimitive("http://192.168.1.50:${server.port}"),
                    "username" to JsonPrimitive("d3tester"),
                    "password" to JsonPrimitive("correct-pw"),
                    "quality" to JsonPrimitive("direct"),
                ),
            )
            val source = mgr.openSource(inst.instanceId) { listOf(InetAddress.getByName("192.168.1.50")) }

            // Before login -> NeedsAuth
            assertThrowsType<SourceNeedsAuthException> { source.home() }

            // Login succeeds
            val login = source.loginForm()
            assertTrue(login.ok)
            assertEquals("d3tester", login.user?.name)

            // Home shelves (Resume + Latest Movies + Latest Shows) with NO api_key in image URLs
            val shelves = source.home()
            assertTrue(shelves.any { it.style == ShelfStyle.CONTINUE })
            for (shelf in shelves) {
                for (item in shelf.items) {
                    assertFalse("S10: poster URL must not contain api_key", item.poster?.url.orEmpty().contains("api_key", ignoreCase = true))
                    assertFalse("S10: poster URL must not contain ApiKey", item.poster?.url.orEmpty().contains("apikey", ignoreCase = true))
                    val poster = item.poster
                    if (poster != null) {
                        assertTrue(poster.headers["Authorization"].orEmpty().contains("""Token="$expectedToken""""))
                    }
                }
            }

            // Categories & Browse
            val cats = source.categories()
            assertEquals(2, cats.size)
            val movies = source.browse(moviesViewId, page = 0)
            assertEquals(2, movies.items.size)

            // Series -> Season -> Episodes
            val series = source.detail(seriesId)
            assertEquals(1, series.seasons.size)
            val season = source.detail(seasonId)
            assertEquals(1, season.episodes.size)

            // Direct Play H.264 + SRT subtitle + S10 assertions
            val pbH264 = source.playback(h264MovieId)
            assertTrue(pbH264.url.contains("/stream?"))
            assertFalse("S10: stream URL must not contain api_key", pbH264.url.contains("api_key", ignoreCase = true))
            assertFalse("S10: stream URL must not contain ApiKey", pbH264.url.contains("apikey", ignoreCase = true))
            assertEquals(1, pbH264.subtitles.size)
            assertEquals(SubtitleFormat.SRT, pbH264.subtitles[0].format)
            assertFalse("S10: subtitle URL must not contain api_key", pbH264.subtitles[0].url.contains("apikey", ignoreCase = true))
            assertTrue(pbH264.headers["Authorization"].orEmpty().contains("""Token="$expectedToken""""))
            // S10: DeviceProfile MUST map 'avc' -> 'h264'
            val firstPbReq = json.parseToJsonElement(playbackReqBodies.first()).jsonObject
            val videoCodecs = firstPbReq["DeviceProfile"]!!.jsonObject["DirectPlayProfiles"]!!
                .jsonArray[0].jsonObject["VideoCodec"]!!.jsonPrimitive.content.split(",")
            assertTrue("S10: DeviceProfile VideoCodec must contain h264: $videoCodecs", "h264" in videoCodecs)
            assertFalse("S10: DeviceProfile VideoCodec must not send raw 'avc'", "avc" in videoCodecs)

            // Direct Play HEVC
            val pbHevc = source.playback(hevcMovieId)
            assertTrue(pbHevc.url.contains("/stream?"))
            assertFalse(pbHevc.url.contains("apikey", ignoreCase = true))

            // HLS Transcode 480p -> real Jellyfin master.m3u8 with ApiKey stripped
            val pb480 = source.playback(hevcMovieId, quality = "480p")
            assertEquals("application/x-mpegURL", pb480.mimeType)
            assertTrue(pb480.url.contains("/master.m3u8"))
            assertFalse("S10: transcode URL must not contain ApiKey", pb480.url.contains("apikey", ignoreCase = true))

            // Search, suggest, progress (with PlayMethod DirectPlay & Transcode), favorites, played, logout
            assertTrue(source.search("Test").items.size >= 2)
            assertTrue(source.suggest("Test").isNotEmpty())
            source.reportProgress(h264MovieId, 1500L, 4000L, ProgressEvent.START, pbH264.sessionId)
            source.reportProgress(h264MovieId, 2000L, 4000L, ProgressEvent.PAUSE, pbH264.sessionId)
            source.reportProgress(hevcMovieId, 2500L, 4000L, ProgressEvent.STOP, pb480.sessionId)
            source.setFavorite(h264MovieId, true)
            source.setFavorite(h264MovieId, false)
            source.setPlayed(h264MovieId, true)
            source.setPlayed(h264MovieId, false)

            // Item #10: verify full MediaBrowser Authorization header format on authenticated requests
            val authRegex = Regex(
                """^MediaBrowser Client="depth3d", Device="[^"]+", DeviceId="depth3d-[0-9a-f]{16}", Version="[^"]+", Token="$expectedToken"$""",
            )
            assertTrue(
                "Authenticated requests must match full MediaBrowser Authorization header format",
                seenAuthHeaders.drop(1).all { authRegex.matches(it) },
            )

            // Item #10: verify exact request bodies for reportProgress (including PlayMethod!)
            assertEquals(3, progressBodies.size)
            val startBody = json.parseToJsonElement(progressBodies[0].second).jsonObject
            assertEquals("/Sessions/Playing", progressBodies[0].first)
            assertEquals(h264MovieId, startBody["ItemId"]!!.jsonPrimitive.content)
            assertEquals(h264MovieId, startBody["MediaSourceId"]!!.jsonPrimitive.content)
            assertEquals(15_000_000L, startBody["PositionTicks"]!!.jsonPrimitive.content.toLong())
            assertEquals(false, startBody["IsPaused"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("DirectPlay", startBody["PlayMethod"]!!.jsonPrimitive.content)
            assertEquals(pbH264.sessionId, startBody["PlaySessionId"]!!.jsonPrimitive.content)

            val pauseBody = json.parseToJsonElement(progressBodies[1].second).jsonObject
            assertEquals("/Sessions/Playing/Progress", progressBodies[1].first)
            assertEquals(true, pauseBody["IsPaused"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("DirectPlay", pauseBody["PlayMethod"]!!.jsonPrimitive.content)

            val stopBody = json.parseToJsonElement(progressBodies[2].second).jsonObject
            assertEquals("/Sessions/Playing/Stopped", progressBodies[2].first)
            assertEquals("Transcode", stopBody["PlayMethod"]!!.jsonPrimitive.content)

            // S10: ZERO unbounded ms_* keys written to storage.enc!
            val persistedStorage = InstanceStorage(File(rootDir, "instances/${inst.instanceId}/storage.enc"), secretBox)
            assertEquals(listOf("deviceId", "session"), persistedStorage.keys().sorted())

            // Item #10: 401 on expired token clears session & throws SourceNeedsAuthException
            forceExpiredToken.set(true)
            assertThrowsType<SourceNeedsAuthException> { source.categories() }
            val afterExpired = InstanceStorage(File(rootDir, "instances/${inst.instanceId}/storage.enc"), secretBox)
            assertEquals(null, afterExpired.get("session"))
            forceExpiredToken.set(false)

            // Re-login & logout cleanly
            assertTrue(source.loginForm().ok)
            source.logout()
            assertTrue(seenPaths.contains("GET /System/Info/Public"))
            assertTrue(seenPaths.contains("GET /UserViews"))
            assertTrue(seenPaths.contains("GET /UserItems/Resume"))
            assertTrue(seenPaths.contains("POST /Sessions/Playing"))
            assertTrue(seenPaths.contains("POST /Sessions/Playing/Progress"))
            assertTrue(seenPaths.contains("POST /Sessions/Playing/Stopped"))
            assertTrue(seenPaths.contains("POST /UserFavoriteItems/$h264MovieId"))
            assertTrue(seenPaths.contains("DELETE /UserFavoriteItems/$h264MovieId"))
            assertTrue(seenPaths.contains("POST /UserPlayedItems/$h264MovieId"))
            assertTrue(seenPaths.contains("DELETE /UserPlayedItems/$h264MovieId"))
            assertTrue(seenPaths.contains("POST /Sessions/Logout"))

            // Wrong password -> SourceAuthFailedException
            mgr.updateInstanceConfig(
                inst.instanceId,
                config = mapOf(
                    "server" to JsonPrimitive("http://192.168.1.50:${server.port}"),
                    "username" to JsonPrimitive("d3tester"),
                    "password" to JsonPrimitive("wrong-pw"),
                ),
            )
            val badSrc = mgr.openSource(inst.instanceId) { listOf(InetAddress.getByName("192.168.1.50")) }
            assertThrowsType<SourceAuthFailedException> { badSrc.loginForm() }
        } finally {
            mgr.shutdownAll()
            server.shutdown()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun jellyfinHandlesVersionRoutingPartialHomeFailureCrossOriginUrlsAndDeviceCapabilityFallback(): Unit = runBlocking {
        val jfDir = File(repoRoot, "plugins/jellyfin")
        val fixtures = json.parseToJsonElement(
            File(jfDir, "fixtures/recorded-responses.json").readText(),
        ).jsonObject
        val ids = fixtures["_meta"]!!.jsonObject["ids"]!!.jsonObject
        val userId = ids["userId"]!!.jsonPrimitive.content
        val moviesViewId = ids["moviesViewId"]!!.jsonPrimitive.content

        val serverVersion = java.util.concurrent.atomic.AtomicReference("10.7.7")
        val failNextUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val seenPaths = CopyOnWriteArrayList<String>()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                seenPaths += "${request.method} $path"

                fun okJson(body: String) = MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(body)

                return when {
                    path == "/System/Info/Public" -> okJson("""{"Version":"${serverVersion.get()}","ServerName":"Test"}""")
                    path == "/Users/AuthenticateByName" -> okJson(fixtures["authSuccess"]!!.toString())
                    path == "/Users/$userId/Views" || path == "/UserViews" -> okJson(fixtures["views"]!!.toString())
                    path == "/Users/$userId/Items/Resume" || path == "/UserItems/Resume" -> okJson(fixtures["resume"]!!.toString())
                    path == "/Shows/NextUp" -> {
                        if (failNextUp.get()) MockResponse().setResponseCode(500)
                        else okJson(fixtures["nextUp"]!!.toString())
                    }
                    path == "/Users/$userId/Items/Latest" || path == "/Items/Latest" -> {
                        val pid = url.queryParameter("ParentId").orEmpty()
                        okJson((fixtures["latestByParent"]!!.jsonObject[pid] ?: json.parseToJsonElement("[]")).toString())
                    }
                    path == "/Users/$userId/FavoriteItems/m1" || path == "/Users/$userId/PlayedItems/m1" ->
                        MockResponse().setResponseCode(204)
                    path == "/Items/cross-origin-item/PlaybackInfo" -> okJson(
                        """
                        {
                          "PlaySessionId": "sess-cross",
                          "MediaSources": [{
                            "Id": "ms-cross",
                            "SupportsDirectPlay": false,
                            "TranscodingUrl": "https://other-cdn.example.net/transcode/master.m3u8?ApiKey=leaked",
                            "RunTimeTicks": 600000000,
                            "MediaStreams": [
                              {"Type":"Video","Index":0,"Codec":"h264","Width":1920,"Height":1080},
                              {"Type":"Subtitle","Index":1,"Codec":"srt","Language":"zh","DeliveryUrl":"https://subs-cdn.example.net/1.srt?api_key=leaked"}
                            ]
                          }]
                        }
                        """.trimIndent(),
                    )
                    path == "/Items/over-cap-item/PlaybackInfo" -> okJson(
                        """
                        {
                          "PlaySessionId": "sess-cap",
                          "MediaSources": [{
                            "Id": "ms-cap",
                            "SupportsDirectPlay": true,
                            "RunTimeTicks": 600000000,
                            "MediaStreams": [
                              {"Type":"Video","Index":0,"Codec":"av1","Width":3840,"Height":2160}
                            ]
                          }]
                        }
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val rootDir = Files.createTempDirectory("d3-jf-r3").toFile()
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val zip = PackageManagerAndMigrationTest.buildPluginZip(
            manifestJson = File(jfDir, "manifest.json").readText(),
            mainJs = File(jfDir, "main.js").readText(),
            locales = mapOf("zh-CN" to File(jfDir, "locales/zh-CN.json").readText()),
        )

        try {
            // 1. Server < 10.8.0 (10.7.7) -> SourceUnsupportedException
            val mgr107 = PluginPackageManager(
                rootDir = File(rootDir, "v107"),
                store = InMemoryPluginStore(),
                secretBox = SoftwareAesSecretBox(),
                okHttpClient = okHttp,
            )
            try {
                mgr107.installFromBytes(zip)
                val inst = mgr107.createInstance(
                    "dev.dreama.jellyfin",
                    "JF 10.7",
                    mapOf(
                        "server" to JsonPrimitive("http://192.168.1.50:${server.port}"),
                        "username" to JsonPrimitive("d3tester"),
                        "password" to JsonPrimitive("pw"),
                    ),
                )
                val src = mgr107.openSource(inst.instanceId) { listOf(InetAddress.getByName("192.168.1.50")) }
                val ex = assertThrowsType<SourceUnsupportedException> { src.loginForm() }
                assertTrue("Unsupported message must mention 10.7.7 / 10.8", ex.message.orEmpty().contains("10.8"))
            } finally {
                mgr107.shutdownAll()
            }

            // 2. Server 10.8.13 -> legacy /Users/{uid}/... routes + partial home() failure tolerance + cross-origin URLs + device capability fallback
            serverVersion.set("10.8.13")
            failNextUp.set(true)
            val mgr108 = PluginPackageManager(
                rootDir = File(rootDir, "v108"),
                store = InMemoryPluginStore(),
                secretBox = SoftwareAesSecretBox(),
                okHttpClient = okHttp,
                // Device only supports 1920x1080 H.264/HEVC (NOT 4K AV1)
                deviceInfo = DeviceInfo(maxWidth = 1920, maxHeight = 1080, codecs = listOf("avc", "hevc")),
            )
            try {
                mgr108.installFromBytes(zip)
                val inst = mgr108.createInstance(
                    "dev.dreama.jellyfin",
                    "JF 10.8",
                    mapOf(
                        "server" to JsonPrimitive("http://192.168.1.50:${server.port}"),
                        "username" to JsonPrimitive("d3tester"),
                        "password" to JsonPrimitive("pw"),
                    ),
                )
                val src = mgr108.openSource(inst.instanceId) { listOf(InetAddress.getByName("192.168.1.50")) }
                assertTrue(src.loginForm().ok)

                // home() succeeds even though /Shows/NextUp returned HTTP 500!
                val shelves = src.home()
                assertTrue("Surviving Resume shelf must still render", shelves.any { it.style == ShelfStyle.CONTINUE })
                assertTrue("Surviving Latest shelf must still render", shelves.any { it.category.id == moviesViewId })

                src.setFavorite("m1", true)
                src.setPlayed("m1", true)
                assertTrue("10.8.x must use legacy /Users/{uid}/Views", seenPaths.contains("GET /Users/$userId/Views"))
                assertTrue("10.8.x must use legacy /Users/{uid}/Items/Resume", seenPaths.contains("GET /Users/$userId/Items/Resume"))
                assertTrue("10.8.x must use legacy /Users/{uid}/Items/Latest", seenPaths.contains("GET /Users/$userId/Items/Latest"))
                assertTrue("10.8.x must use legacy /Users/{uid}/FavoriteItems/m1", seenPaths.contains("POST /Users/$userId/FavoriteItems/m1"))
                assertTrue("10.8.x must use legacy /Users/{uid}/PlayedItems/m1", seenPaths.contains("POST /Users/$userId/PlayedItems/m1"))

                // Cross-origin TranscodingUrl & subtitle DeliveryUrl MUST NOT carry Jellyfin Authorization header!
                val pbCross = src.playback("cross-origin-item")
                assertEquals("https://other-cdn.example.net/transcode/master.m3u8", pbCross.url)
                assertFalse("Cross-origin TranscodingUrl must omit Authorization", pbCross.headers.containsKey("Authorization"))
                assertEquals(1, pbCross.subtitles.size)
                assertEquals("https://subs-cdn.example.net/1.srt", pbCross.subtitles[0].url)
                assertFalse("Cross-origin DeliveryUrl must omit Authorization", pbCross.subtitles[0].headers.containsKey("Authorization"))

                // Device capability check: 4K AV1 item on 1080p AVC/HEVC device MUST fall back to HLS transcode!
                val pbOverCap = src.playback("over-cap-item")
                assertEquals("application/x-mpegURL", pbOverCap.mimeType)
                assertTrue("Exceeding device resolution/codec must fall back to HLS transcode", pbOverCap.url.contains("/master.m3u8"))
            } finally {
                mgr108.shutdownAll()
            }
        } finally {
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
