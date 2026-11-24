package com.limelight.local.plugin

import androidx.media3.common.MimeTypes
import com.limelight.local.media.DashStream
import com.limelight.local.media.MediaTrack
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.QualityOption
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SubtitleFormat
import com.limelight.local.media.SubtitleTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PlaybackMediaSourceFactoryTest {

    private val factory = PlaybackMediaSourceFactory()

    @Test
    fun plansDirectUrlTypesAndSubtitlesWithHeaders() {
        val hlsInfo = PlaybackInfo(
            url = "https://cdn.example.com/live/master.m3u8?token=abc",
            headers = mapOf("Referer" to "https://site.example.com/"),
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
                    headers = mapOf("X-Sub" to "1"),
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
        assertFalse(plan.subtitles[0].requiresJsonToVttConversion)
        assertEquals("https://site.example.com/", plan.subtitles[0].effectiveHeaders["Referer"])

        assertEquals(MimeTypes.TEXT_VTT, plan.subtitles[1].media3MimeType)
        assertTrue(plan.subtitles[1].requiresJsonToVttConversion)
        assertEquals("1", plan.subtitles[1].effectiveHeaders["X-Sub"])

        // Progressive MP4 and explicit DASH MIME detection
        val prog = factory.plan(PlaybackInfo(url = "https://cdn.example.com/video.mp4", title = "MP4"))
        assertEquals(DirectStreamType.PROGRESSIVE, (prog.stream as ResolvedStreamPlan.DirectUrl).streamType)

        val dashUrl = factory.plan(
            PlaybackInfo(url = "https://cdn.example.com/play?id=1", title = "DASH", mimeType = "application/dash+xml"),
        )
        assertEquals(DirectStreamType.DASH, (dashUrl.stream as ResolvedStreamPlan.DirectUrl).streamType)
    }

    @Test
    fun synthesizesValidDashMpdWhenSegmentRangesPresent() {
        val dash = DashStream(
            video = listOf(
                MediaTrack(
                    id = "v1080",
                    url = "https://cdn1.example.com/v1080.m4s?a=1&b=2",
                    backupUrls = listOf("https://cdn2.example.com/v1080.m4s?a=1&b=2"),
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
            dash = dash,
            qualities = listOf(
                QualityOption("1080p", "1080P", selected = true),
                QualityOption("720p", "720P", selected = false),
            ),
        )

        val plan = factory.plan(info)
        val synth = plan.stream as ResolvedStreamPlan.SynthesizedDashMpd
        assertTrue(synth.mpdXml.contains("<AdaptationSet id=\"0\" contentType=\"video\""))
        assertTrue(synth.mpdXml.contains("<AdaptationSet id=\"1\" contentType=\"audio\""))
        assertTrue(synth.mpdXml.contains("id=\"v1080\""))
        assertFalse("Quality filter should select only 1080p track", synth.mpdXml.contains("id=\"v720\""))
        assertTrue(synth.mpdXml.contains("https://cdn1.example.com/v1080.m4s?a=1&amp;b=2"))
        assertTrue(synth.mpdXml.contains("https://cdn2.example.com/v1080.m4s?a=1&amp;b=2"))
        assertTrue(synth.mpdXml.contains("<SegmentBase indexRange=\"821-2048\">"))
        assertTrue(synth.mpdXml.contains("<Initialization range=\"0-820\"/>"))
        assertEquals(2, synth.videoBackupChains.single().size)
        assertEquals(2, synth.audioBackupChains.single().size)
    }

    @Test
    fun fallsBackToMergedProgressiveTracksWhenSegmentRangesOmitted() {
        val dash = DashStream(
            video = listOf(
                MediaTrack(
                    id = "v1",
                    url = "https://cdn1.example.com/video.mp4",
                    backupUrls = listOf("https://cdn2.example.com/video.mp4"),
                    mime = "video/mp4",
                    codecs = "avc1.640028",
                    bandwidth = 4_000_000,
                ),
            ),
            audio = listOf(
                MediaTrack(
                    id = "a1",
                    url = "https://cdn1.example.com/audio.m4a",
                    backupUrls = listOf("https://cdn2.example.com/audio.m4a"),
                    mime = "audio/mp4",
                    codecs = "mp4a.40.2",
                    bandwidth = 128_000,
                ),
            ),
        )
        val plan = factory.plan(PlaybackInfo(url = "", title = "Merged", dash = dash))
        val merged = plan.stream as ResolvedStreamPlan.MergedProgressiveTracks
        assertEquals(
            listOf("https://cdn1.example.com/video.mp4", "https://cdn2.example.com/video.mp4"),
            merged.videoUrls,
        )
        assertEquals(
            listOf("https://cdn1.example.com/audio.m4a", "https://cdn2.example.com/audio.m4a"),
            merged.audioUrls,
        )
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

        // Subsequent request starts directly at healthy cdn2
        val tried2 = ArrayList<String>()
        val res2 = selector.executeWithFailover { url ->
            tried2 += url
            "ok2:$url"
        }
        assertEquals("ok2:https://cdn2.example.com/a", res2)
        assertEquals(listOf("https://cdn2.example.com/a"), tried2)

        // All failing throws last IOException
        try {
            selector.executeWithFailover<Unit> { throw IOException("down") }
            fail("Expected IOException when all candidates fail")
        } catch (e: IOException) {
            assertEquals("down", e.message)
        }
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
    fun sessionControllerHandlesExpiryRefreshHttp403AndLockedQualities() = runTest {
        var now = 1_000_000L
        var calls = 0
        val controller = PlaybackSessionController(
            fetchPlayback = { itemId, quality, _ ->
                calls += 1
                PlaybackInfo(
                    url = "https://cdn.example.com/$itemId/${quality ?: "1080p"}.m3u8?gen=$calls",
                    title = itemId,
                    startPositionMs = 5_000L,
                    expiresAtMs = now + 60_000L,
                    qualities = listOf(
                        QualityOption("4k", "4K 会员", locked = "需要大会员"),
                        QualityOption("1080p", "1080P", selected = (quality ?: "1080p") == "1080p"),
                        QualityOption("720p", "720P", selected = quality == "720p"),
                    ),
                )
            },
            factory = factory,
            clockMs = { now },
            refreshLeadMs = 10_000L,
        )

        val s1 = controller.load("ep1")
        assertEquals(1, calls)
        assertEquals(5_000L, s1.resumePositionMs)
        assertTrue(s1.info.url.contains("gen=1"))
        assertFalse(controller.isExpired())

        // Still fresh -> cached without re-fetching
        now += 20_000L
        val s1Cached = controller.load("ep1", quality = "1080p")
        assertEquals(1, calls)
        assertEquals(s1.info.url, s1Cached.info.url)

        // Within refresh lead window -> expired -> auto re-fetch preserving playback position
        now += 35_000L // now = 1_055_000 >= 1_060_000 - 10_000
        assertTrue(controller.isExpired())
        val refreshed = controller.refreshIfExpiredOrFailed(currentPositionMs = 42_500L)
        assertNotNull(refreshed)
        assertEquals(2, calls)
        assertTrue(refreshed!!.info.url.contains("gen=2"))
        assertEquals(42_500L, refreshed.resumePositionMs)

        // HTTP 403 mid-playback forces refresh even before expiresAt
        val refreshed403 = controller.refreshIfExpiredOrFailed(currentPositionMs = 55_000L, httpStatusCode = 403)
        assertNotNull(refreshed403)
        assertEquals(3, calls)
        assertEquals(55_000L, refreshed403!!.resumePositionMs)

        // Non-expiry error (e.g. 500) does not force refresh when still valid
        assertNull(controller.refreshIfExpiredOrFailed(currentPositionMs = 55_000L, httpStatusCode = 500))

        // Locked quality throws SourceForbiddenException with plugin's Chinese reason
        try {
            controller.switchQuality("4k", currentPositionMs = 60_000L)
            fail("Expected locked quality to throw SourceForbiddenException")
        } catch (e: SourceForbiddenException) {
            assertEquals("需要大会员", e.message)
        }

        // Unlocked quality switches cleanly
        val s720 = controller.switchQuality("720p", currentPositionMs = 60_000L)
        assertEquals(4, calls)
        assertEquals("720p", s720.selectedQualityId)
        assertTrue(s720.info.url.contains("/720p.m3u8?gen=4"))
        assertEquals(60_000L, s720.resumePositionMs)
    }

    @Test
    fun factoryRejectsPrivatePlaybackUrlWithoutLanPermission() {
        val restrictedFactory = PlaybackMediaSourceFactory(
            networkPolicy = NetworkPolicy(PluginPermissions(lan = false)),
        )
        try {
            restrictedFactory.plan(PlaybackInfo(url = "http://192.168.1.10:8096/video.mp4", title = "LAN"))
            fail("Expected NetworkPolicyException for private IP playback URL without lan permission")
        } catch (_: NetworkPolicyException) {
        }
    }
}
