package com.limelight.local.plugin

import androidx.media3.common.MimeTypes
import com.limelight.local.media.DashStream
import com.limelight.local.media.MediaTrack
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.QualityOption
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.media.SubtitleFormat
import com.limelight.local.media.SubtitleTrack
import kotlinx.coroutines.test.runTest
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
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy

class PlaybackMediaSourceFactoryTest {

    private val defaultPolicy = NetworkPolicy(PluginPermissions(lan = false))
    private val factory = PlaybackMediaSourceFactory(
        networkPolicy = defaultPolicy,
        deviceCodecs = listOf("avc", "hevc"),
    )

    @Test
    fun plansDirectUrlTypesAndOverlaysSubtitleHeaders() {
        val hlsInfo = PlaybackInfo(
            url = "https://cdn.example.com/live/master.m3u8?token=abc",
            headers = mapOf("Referer" to "https://site.example.com/", "Authorization" to "Bearer base"),
            title = "HLS Video",
            subtitles = listOf(
                SubtitleTrack(
                    id = "s1",
                    label = "中文 SRT",
                    lang = "zh-CN",
                    url = "https://cdn.example.com/sub.srt",
                    format = SubtitleFormat.SRT,
                    isDefault = true,
                ),
                SubtitleTrack(
                    id = "s2",
                    label = "JSON 字幕",
                    lang = "zh-CN",
                    url = "https://cdn.example.com/sub.json",
                    format = SubtitleFormat.JSON,
                    headers = mapOf("Authorization" to "Bearer sub-override", "X-Sub" to "1"),
                ),
            ),
        )
        val plan = factory.plan(hlsInfo)
        val stream = plan.stream as ResolvedStreamPlan.DirectUrl
        assertEquals(DirectStreamType.HLS, stream.streamType)
        assertEquals(MimeTypes.APPLICATION_M3U8, stream.mimeType)
        assertEquals("https://site.example.com/", stream.headers["Referer"])

        assertEquals(2, plan.subtitles.size)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, plan.subtitles[0].media3MimeType)
        assertEquals("https://site.example.com/", plan.subtitles[0].effectiveHeaders["Referer"])
        assertEquals("Bearer base", plan.subtitles[0].effectiveHeaders["Authorization"])

        // §11.2: subtitle headers overlay PlaybackInfo.headers (same key overrides, others inherited)
        assertEquals(MimeTypes.TEXT_VTT, plan.subtitles[1].media3MimeType)
        assertTrue(plan.subtitles[1].requiresJsonToVttConversion)
        assertEquals("https://site.example.com/", plan.subtitles[1].effectiveHeaders["Referer"])
        assertEquals("Bearer sub-override", plan.subtitles[1].effectiveHeaders["Authorization"])
        assertEquals("1", plan.subtitles[1].effectiveHeaders["X-Sub"])

        val prog = factory.plan(PlaybackInfo(url = "https://cdn.example.com/video.mp4", title = "MP4"))
        assertEquals(DirectStreamType.PROGRESSIVE, (prog.stream as ResolvedStreamPlan.DirectUrl).streamType)

        val dashUrl = factory.plan(
            PlaybackInfo(url = "https://cdn.example.com/play?id=1", title = "DASH", mimeType = "application/dash+xml"),
        )
        assertEquals(DirectStreamType.DASH, (dashUrl.stream as ResolvedStreamPlan.DirectUrl).streamType)
    }

    @Test
    fun synthesizesDvbPriorityDashMpdWithDurationAndRejectsLiveDashAndMissingDuration() {
        val dash = DashStream(
            video = listOf(
                MediaTrack(
                    id = "v1080",
                    url = "https://cdn1.example.com/v1080.m4s?a=1&b=2",
                    backupUrls = listOf(
                        "https://cdn2.example.com/v1080.m4s?a=1&b=2",
                        "https://cdn3.example.com/v1080.m4s?a=1&b=2",
                    ),
                    mime = "video/mp4",
                    codecs = "avc1.640028",
                    bandwidth = 6_000_000,
                    width = 1920,
                    height = 1080,
                    frameRate = 60.0,
                    quality = "1080p",
                    initRange = "0-820",
                    indexRange = "821-2048",
                ),
                MediaTrack(
                    id = "v720",
                    url = "https://cdn1.example.com/v720.m4s",
                    mime = "video/mp4",
                    codecs = "avc1.64001f",
                    bandwidth = 3_000_000,
                    width = 1280,
                    height = 720,
                    quality = "720p",
                    initRange = "0-700",
                    indexRange = "701-1800",
                ),
            ),
            audio = listOf(
                MediaTrack(
                    id = "a192",
                    url = "https://cdn1.example.com/a192.m4s",
                    backupUrls = listOf("https://cdn2.example.com/a192.m4s"),
                    mime = "audio/mp4",
                    codecs = "mp4a.40.2",
                    bandwidth = 192_000,
                    initRange = "0-640",
                    indexRange = "641-1200",
                ),
            ),
        )
        val info = PlaybackInfo(
            url = "",
            headers = mapOf("Referer" to "https://site.example.com/"),
            title = "Separated DASH",
            durationMs = 900_000L,
            dash = dash,
            qualities = listOf(
                QualityOption("1080p", "1080P", selected = true),
                QualityOption("720p", "720P", selected = false),
            ),
        )

        val plan = factory.plan(info)
        val synth = plan.stream as ResolvedStreamPlan.SynthesizedDashMpd
        assertTrue(synth.mpdXml.contains("xmlns:dvb=\"urn:dvb:dash:profile:dvb-dash:2014\""))
        assertTrue(synth.mpdXml.contains("mediaPresentationDuration=\"PT900.000S\""))
        assertTrue(synth.mpdXml.contains("dvb:priority=\"1\""))
        assertTrue(synth.mpdXml.contains("dvb:priority=\"2\""))
        assertTrue(synth.mpdXml.contains("dvb:priority=\"3\""))
        assertTrue(synth.mpdXml.contains("<AdaptationSet id=\"0\" contentType=\"video\""))
        assertTrue(synth.mpdXml.contains("<AdaptationSet id=\"1\" contentType=\"audio\""))
        assertTrue(synth.mpdXml.contains("id=\"v1080\""))
        assertFalse("Quality filter should select only 1080p track", synth.mpdXml.contains("id=\"v720\""))
        assertEquals(3, synth.videoBackupChains.single().size)

        // S6 (§11.2): live + dash MUST fail with SourceUnsupportedException
        try {
            factory.plan(info.copy(isLive = true))
            fail("Expected live + dash to throw SourceUnsupportedException")
        } catch (_: SourceUnsupportedException) {
        }

        // S6 (§11.2): missing durationMs for segmented DASH MUST fail with SourceUnsupportedException
        try {
            factory.plan(info.copy(durationMs = null))
            fail("Expected missing durationMs on segmented DASH to throw SourceUnsupportedException")
        } catch (_: SourceUnsupportedException) {
        }
    }

    @Test
    fun mergedProgressiveFallbackFiltersUnsupportedVideoCodecsAndRespectsSelectedAudio() {
        // S6: deviceCodecs = ["avc", "hevc"]; AV1 track has higher bitrate but is NOT supported!
        val dash = DashStream(
            video = listOf(
                MediaTrack(
                    id = "v-av1-high",
                    url = "https://cdn1.example.com/av1.mp4",
                    mime = "video/mp4",
                    codecs = "av01.0.08M.08",
                    bandwidth = 12_000_000,
                ),
                MediaTrack(
                    id = "v-hevc-ok",
                    url = "https://cdn1.example.com/hevc.mp4",
                    backupUrls = listOf("https://cdn2.example.com/hevc.mp4"),
                    mime = "video/mp4",
                    codecs = "hev1.1.6.L120.90",
                    bandwidth = 6_000_000,
                ),
            ),
            audio = listOf(
                MediaTrack(
                    id = "a-high-en",
                    url = "https://cdn1.example.com/en.m4a",
                    mime = "audio/mp4",
                    codecs = "mp4a.40.2",
                    bandwidth = 320_000,
                ),
                MediaTrack(
                    id = "a-zh",
                    url = "https://cdn1.example.com/zh.m4a",
                    backupUrls = listOf("https://cdn2.example.com/zh.m4a"),
                    mime = "audio/mp4",
                    codecs = "mp4a.40.2",
                    bandwidth = 128_000,
                ),
            ),
        )
        val plan = factory.plan(
            info = PlaybackInfo(url = "", title = "Merged", dash = dash),
            selectedAudioId = "a-zh",
        )
        val merged = plan.stream as ResolvedStreamPlan.MergedProgressiveTracks
        assertEquals(
            listOf("https://cdn1.example.com/hevc.mp4", "https://cdn2.example.com/hevc.mp4"),
            merged.videoUrls,
        )
        assertEquals(
            listOf("https://cdn1.example.com/zh.m4a", "https://cdn2.example.com/zh.m4a"),
            merged.audioUrls,
        )
    }

    @Test
    fun safeHttpExecutorScopesHeadersToTrustedOriginsAndBlocksBadRedirects() {
        val server = MockWebServer()
        val received = LinkedHashMap<String, String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/trusted" -> {
                    received["trusted_auth"] = request.getHeader("Authorization")
                    received["trusted_custom"] = request.getHeader("X-Plugin-Custom")
                    MockResponse().setResponseCode(200).setBody("OK_TRUSTED")
                }
                "/untrusted" -> {
                    received["untrusted_auth"] = request.getHeader("Authorization")
                    received["untrusted_custom"] = request.getHeader("X-Plugin-Custom")
                    MockResponse().setResponseCode(200).setBody("OK_UNTRUSTED")
                }
                "/redir-cross" -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://93.184.216.35:${server.port}/after-cross")
                "/after-cross" -> {
                    received["cross_auth"] = request.getHeader("Authorization")
                    received["cross_cookie"] = request.getHeader("Cookie")
                    received["cross_custom"] = request.getHeader("X-Plugin-Custom")
                    MockResponse().setResponseCode(200).setBody("OK_CROSS")
                }
                "/redir-loopback" -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://127.1:${server.port}/trusted")
                "/redir-unlisted-lan" -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://192.168.1.99:${server.port}/trusted")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val policy = NetworkPolicy(
                permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
                userConfiguredHosts = setOf("192.168.1.50"),
                dnsResolver = { host -> listOf(InetAddress.getByName(host)) },
            )
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(
                    MultiIpRedirectSocketFactory(setOf("192.168.1.50", "93.184.216.35", "93.184.216.36"), server.port),
                )
                .build()
            val executor = SafePluginHttpExecutor(policy, client)
            val pluginHeaders = mapOf(
                "Authorization" to "Bearer secret-playback-token",
                "Cookie" to "sid=topsecret",
                "X-Plugin-Custom" to "custom-val",
            )
            val trustedOrigins = setOf("http://192.168.1.50:${server.port}")

            // 1. Trusted origin receives PlaybackInfo.headers
            executor.openStream("http://192.168.1.50:${server.port}/trusted", pluginHeaders, trustedOrigins).use { r ->
                assertEquals(200, r.code)
            }
            assertEquals("Bearer secret-playback-token", received["trusted_auth"])
            assertEquals("custom-val", received["trusted_custom"])

            // 2. Untrusted third-party CDN does NOT receive PlaybackInfo.headers (B1 / §11.3)
            executor.openStream("http://93.184.216.36:${server.port}/untrusted", pluginHeaders, trustedOrigins).use { r ->
                assertEquals(200, r.code)
            }
            assertNull(received["untrusted_auth"])
            assertNull(received["untrusted_custom"])

            // 3. Cross-origin redirect strips Authorization, Cookie, and custom headers (B1 / §11.3)
            executor.openStream("http://192.168.1.50:${server.port}/redir-cross", pluginHeaders, trustedOrigins).use { r ->
                assertEquals(200, r.code)
            }
            assertNull(received["cross_auth"])
            assertNull(received["cross_cookie"])
            assertNull(received["cross_custom"])

            // 4. Redirect to loopback (127.1) or unlisted LAN (192.168.1.99) rejected
            try {
                executor.openStream("http://192.168.1.50:${server.port}/redir-loopback", pluginHeaders, trustedOrigins).close()
                fail("Expected redirect to 127.1 to be rejected")
            } catch (_: IOException) {
            }
            try {
                executor.openStream("http://192.168.1.50:${server.port}/redir-unlisted-lan", pluginHeaders, trustedOrigins).close()
                fail("Expected redirect to unlisted LAN IP to be rejected")
            } catch (_: IOException) {
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun failoverSelectorSwitchesToHealthyBackupAndRemembersIt() {
        val selector = FailoverUrlSelector(
            listOf("https://cdn1.example.com/a", "https://cdn2.example.com/a", "https://cdn3.example.com/a"),
        )
        val tried = ArrayList<String>()
        val result = selector.executeWithFailover { url ->
            tried += url
            if (url.contains("cdn1")) throw IOException("503 from cdn1")
            "ok:$url"
        }
        assertEquals("ok:https://cdn2.example.com/a", result)
        assertEquals(listOf("https://cdn1.example.com/a", "https://cdn2.example.com/a"), tried)
        assertEquals("https://cdn2.example.com/a", selector.currentPreferredUrl())

        val tried2 = ArrayList<String>()
        val res2 = selector.executeWithFailover { url ->
            tried2 += url
            "ok2:$url"
        }
        assertEquals("ok2:https://cdn2.example.com/a", res2)
        assertEquals(listOf("https://cdn2.example.com/a"), tried2)
    }

    @Test
    fun convertsJsonSubtitleFormatsToWebVtt() {
        val bccJson = """
            {"body":[{"from":1.25,"to":3.5,"content":"第一句字幕"},{"from":61.0,"to":64.2,"content":"第二句"}]}
        """.trimIndent()
        val vtt = JsonSubtitleConverter.toWebVtt(bccJson)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.250 --> 00:00:03.500\n第一句字幕"))
        assertTrue(vtt.contains("00:01:01.000 --> 00:01:04.200\n第二句"))
    }

    @Test
    fun sessionControllerUses60sRefreshLeadAndAvoidsLoopingOnShortTtlUrls() = runTest {
        var now = 1_000_000L
        var calls = 0
        var ttlMs = 180_000L
        val controller = PlaybackSessionController(
            fetchPlayback = { itemId, quality, _ ->
                calls += 1
                PlaybackInfo(
                    url = "https://cdn.example.com/$itemId/${quality ?: "1080p"}.m3u8?gen=$calls",
                    title = itemId,
                    startPositionMs = 5_000L,
                    expiresAtMs = now + ttlMs,
                    qualities = listOf(
                        QualityOption("4k", "4K 会员", locked = "需要大会员"),
                        QualityOption("1080p", "1080P", selected = (quality ?: "1080p") == "1080p"),
                        QualityOption("720p", "720P", selected = quality == "720p"),
                    ),
                )
            },
            factory = factory,
            clockMs = { now },
        )

        assertEquals(60_000L, PlaybackSessionController.DEFAULT_REFRESH_LEAD_MS)

        // 1. Fresh 180s TTL -> refreshes 60s before expiry (at +120s)
        val s1 = controller.load("ep1")
        assertEquals(1, calls)
        assertFalse(controller.isExpired())
        now += 119_000L
        assertFalse(controller.isExpired())
        now += 1_000L // exactly 60s before expiry
        assertTrue(controller.isExpired())

        val refreshed = controller.refreshIfExpiredOrFailed(currentPositionMs = 42_500L)
        assertNotNull(refreshed)
        assertEquals(2, calls)
        assertEquals(42_500L, refreshed!!.resumePositionMs)

        // 2. §11.2 short-TTL guard: freshly returned URL with < 60s TTL (30s) MUST NOT immediately expire & loop!
        ttlMs = 30_000L
        val shortSess = controller.load("ep-short")
        assertEquals(3, calls)
        assertFalse("Short-TTL URL (<60s) must be used directly without loop-refetching", controller.isExpired())
        assertNull(controller.refreshIfExpiredOrFailed(currentPositionMs = 10_000L))
        now += 29_000L
        assertFalse(controller.isExpired())
        now += 1_000L
        assertTrue("Short-TTL URL expires at actual expiresAt", controller.isExpired())
        assertNotNull(shortSess)

        // 3. HTTP 403 mid-playback forces refresh, locked quality throws SourceForbiddenException
        val refreshed403 = controller.refreshIfExpiredOrFailed(currentPositionMs = 55_000L, httpStatusCode = 403)
        assertNotNull(refreshed403)
        assertEquals(4, calls)

        try {
            controller.switchQuality("4k", currentPositionMs = 60_000L)
            fail("Expected locked quality to throw SourceForbiddenException")
        } catch (e: SourceForbiddenException) {
            assertEquals("需要大会员", e.message)
        }
        val s720 = controller.switchQuality("720p", currentPositionMs = 60_000L)
        assertEquals("720p", s720.selectedQualityId)
    }

    @Test
    fun factoryRejectsPrivateAndNonStandardPlaybackUrls() {
        val restrictedFactory = PlaybackMediaSourceFactory(
            networkPolicy = NetworkPolicy(PluginPermissions(lan = false)),
        )
        for (bad in listOf(
            "http://192.168.1.10:8096/video.mp4",
            "http://127.1/video.mp4",
            "http://0x7f.0.0.1/video.mp4",
            "http://0177.0.0.1/video.mp4",
            "http://10.1/video.mp4",
            "http://[::127.0.0.1]/video.mp4",
            "http://localhost./video.mp4",
            "https://240.0.0.1/video.mp4",
            "https://255.255.255.255/video.mp4",
            "file:///etc/passwd",
            "content://media/external/video/1",
        )) {
            try {
                restrictedFactory.plan(PlaybackInfo(url = bad, title = "Bad"))
                fail("Expected NetworkPolicyException for playback URL: $bad")
            } catch (_: NetworkPolicyException) {
            }
        }
    }
}
