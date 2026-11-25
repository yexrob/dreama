package com.limelight.local

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.LocalRedirectSocketFactory
import com.limelight.local.plugin.PluginHostGuards
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.XmlStringLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginPlaybackStage2Test {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val progressCalls = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        progressCalls.clear()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildPlaybackPluginZip(serverBase: String): ByteArray {
        val manifest = """
            {
              "api": 1,
              "id": "dev.depth3d.playtest",
              "name": "PlayTest",
              "version": "1.0.0",
              "minApp": "1.0.0",
              "author": "depth3d",
              "description": "Stage 2 test plugin",
              "license": "GPL-3.0-or-later",
              "multiInstance": true,
              "capabilities": ["progress", "subtitles"],
              "auth": "none",
              "permissions": {
                "hosts": [],
                "userHosts": true,
                "lan": true
              },
              "config": [
                { "key": "server", "type": "url", "label": "Server", "required": true }
              ]
            }
        """.trimIndent()
        val mainJs = """
            let playbackCallCount = 0;
            export default {
              async home(ctx) { return []; },
              async browse(ctx) { return { items: [] }; },
              async detail(ctx, id) { return { id, kind: "movie", title: "Sample Movie", durationMs: 3600000 }; },
              async playback(ctx, id, opts) {
                playbackCallCount += 1;
                const base = ctx.config.server;
                return {
                  url: base + "/video-" + playbackCallCount + ".mp4",
                  headers: { "X-Secret-Token": "tok-" + playbackCallCount },
                  durationMs: 3600000,
                  expiresAt: Date.now() + 120000 * playbackCallCount,
                  session: "sess-" + playbackCallCount,
                  qualities: [
                    { id: "1080p", label: "1080p", selected: true },
                    { id: "720p", label: "720p" }
                  ],
                  subtitles: [
                    {
                      id: "sub-zh",
                      label: "简体中文",
                      lang: "zh",
                      url: base + "/sub.vtt",
                      format: "vtt",
                      default: true
                    }
                  ]
                };
              },
              async reportProgress(ctx, p) {
                await ctx.fetch(ctx.config.server + "/progress", {
                  method: "POST",
                  headers: { "Content-Type": "application/json" },
                  body: JSON.stringify(p)
                });
              }
            };
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("main.js"))
            zos.write(mainJs.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun pluginLaunchIntentCarriesOnlyOpaqueIdsAndNoUrlOrHeaders() {
        val intent = PluginPlayerLaunch.createIntent(
            context = context,
            instanceId = "inst-abc",
            sourceId = "plugin:inst-abc",
            itemId = "movie-42",
            title = "星际穿越",
            startMs = 123_000L,
            durationMs = 6_000_000L,
        )
        assertNull("Plugin launch intent must NEVER carry a data URI", intent.data)
        assertNull("Plugin launch intent must NEVER carry EXTRA_AUTH", intent.getStringExtra(LocalPlayerXR.EXTRA_AUTH))
        assertEquals("inst-abc", intent.getStringExtra(LocalPlayerXR.EXTRA_PLUGIN_INSTANCE_ID))
        assertEquals("movie-42", intent.getStringExtra(LocalPlayerXR.EXTRA_ITEM_ID))
        assertEquals(123_000L, intent.getLongExtra(LocalPlayerXR.EXTRA_START_MS, 0L))

        val parsed = PluginPlayerLaunch.parseIntent(intent)
        assertNotNull(parsed)
        assertEquals("inst-abc", parsed!!.instanceId)
        assertEquals("plugin:inst-abc", parsed.sourceId)
        assertEquals("movie-42", parsed.itemId)
        assertEquals("星际穿越", parsed.title)
        assertEquals(123_000L, parsed.startMs)
        assertEquals(6_000_000L, parsed.durationMs)
    }

    @Test
    fun hostGuardsRejectPluginPlaybackFrameProbeAndCast() {
        val pluginInfo = PlaybackInfo(
            url = "https://cdn.example.com/secret.mp4",
            sourceKind = SourceKind.PLUGIN,
        )
        try {
            PluginHostGuards.requireNonPluginPlayback(pluginInfo)
            fail("Expected UnsupportedOperationException for plugin playback info")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        try {
            PluginHostGuards.requireNonPluginFrameProbe(SourceKind.PLUGIN, null, null)
            fail("Expected UnsupportedOperationException for plugin frame probe")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        try {
            LocalPlayerXR.guardCastAttachment(SourceKind.PLUGIN, 42L)
            fail("Expected UnsupportedOperationException for plugin cast attachment")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        // Non-plugin sources and non-cast plugin playback pass cleanly
        LocalPlayerXR.guardCastAttachment(SourceKind.LOCAL, 42L)
        LocalPlayerXR.guardCastAttachment(SourceKind.PLUGIN, 0L)
        PluginHostGuards.requireNonPluginFrameProbe(SourceKind.WEBDAV, null, null)
    }

    @Test
    fun pluginPlayerSessionLoadsSubtitlesRefreshesOn401AndExpiryAndReportsProgress() = runBlocking {
        val baseUrl = "http://192.168.1.50:${server.port}"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/progress" -> {
                        progressCalls += request.body.readUtf8()
                        MockResponse().setResponseCode(204)
                    }
                    path == "/sub.vtt" -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/vtt")
                            .setBody("WEBVTT\n\n00:00:00.000 --> 00:00:05.000\n你好世界\n")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("fake-video")
                }
            }
        }

        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("plugins"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            okHttpClient = okHttp,
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        pm.installFromBytes(buildPlaybackPluginZip(baseUrl), sourceUrl = "builtin:dev.depth3d.playtest")
        val inst = pm.createInstance(
            pluginId = "dev.depth3d.playtest",
            displayName = "PlayTest Server",
            config = mapOf("server" to JsonPrimitive(baseUrl)),
        )

        val clock = AtomicLong(System.currentTimeMillis())
        val session = PluginPlayerSession(
            openPluginSource = { id -> pm.openSource(id) { listOf(InetAddress.getByName("192.168.1.50")) } },
            clockMs = { clock.get() },
            progressIntervalMs = 10_000L,
            ioDispatcher = Dispatchers.IO,
            callbackDispatcher = Dispatchers.IO,
        )

        val loaded = session.load(
            instanceId = inst.instanceId,
            itemId = "movie-1",
            resumePositionMs = 45_000L,
            fallbackDurationMs = 3_600_000L,
        )
        assertEquals(45_000L, loaded.resumePositionMs)
        assertEquals("1080p", loaded.selectedQualityId)
        assertEquals(1, loaded.subtitleCount)
        assertEquals("sess-1", loaded.sessionId)
        // MergingMediaSource proves the external subtitle source was merged in
        assertTrue(
            "Expected MergingMediaSource when external subtitle is present, got ${loaded.mediaSource.javaClass.simpleName}",
            loaded.mediaSource is MergingMediaSource,
        )

        // Verify ExoPlayer accepts the plugin MediaSource with DefaultTrackSelector without errors
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setPreferredTextLanguage("zh"))
        }
        val exo = ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
        try {
            exo.setMediaSource(loaded.mediaSource)
            exo.prepare()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            exo.release()
        }

        // Verify HTTP 401 triggers refresh & resumes at current position
        assertTrue(session.canAttemptAutoRefresh(401))
        val refreshed401 = session.refreshIfExpiredOrFailed(currentPositionMs = 92_000L, httpStatusCode = 401)
        assertNotNull(refreshed401)
        assertEquals(92_000L, refreshed401!!.resumePositionMs)
        assertEquals("sess-2", refreshed401.sessionId)

        // Mark playback ready resets error counter; test proactive expiresAt refresh
        session.markPlaybackReady()
        assertFalse(session.shouldProactiveRefresh())
        clock.addAndGet(190_000L)
        assertTrue(session.shouldProactiveRefresh())
        val refreshedExpired = session.refreshIfExpiredOrFailed(currentPositionMs = 150_000L, httpStatusCode = null)
        assertNotNull(refreshedExpired)
        assertEquals(150_000L, refreshedExpired!!.resumePositionMs)
        assertEquals("sess-3", refreshedExpired.sessionId)

        // Progress reporting lifecycle: start -> periodic (throttled <10s, fires >=10s) -> pause -> stop
        assertTrue(session.reportStart(45_000L, 3_600_000L))
        assertFalse("Duplicate start must be ignored", session.reportStart(46_000L, 3_600_000L))
        clock.addAndGet(4_000L)
        assertFalse("Periodic report must wait 10s", session.reportPeriodicIfDue(49_000L, 3_600_000L))
        clock.addAndGet(7_000L)
        assertTrue("Periodic report fires after 10s", session.reportPeriodicIfDue(56_000L, 3_600_000L))
        assertTrue(session.reportPause(56_000L, 3_600_000L))
        assertTrue(session.reportStop(56_000L, 3_600_000L))
        assertFalse("Duplicate stop must be ignored", session.reportStop(56_000L, 3_600_000L))

        assertEquals(4, progressCalls.size)
        assertTrue(progressCalls[0].contains("\"event\":\"start\""))
        assertTrue(progressCalls[1].contains("\"event\":\"progress\""))
        assertTrue(progressCalls[2].contains("\"event\":\"pause\""))
        assertTrue(progressCalls[3].contains("\"event\":\"stop\""))

        session.close()
        pm.shutdownAll()
    }

    @Test
    fun playerErrorFormattingNeverLeaksErrorCodesAndTransportBarHitTestsWork() {
        val strings = XmlStringLookup

        val authErr = PluginPlayerSession.formatPlayerError(
            SourceNeedsAuthException("请先登录 Jellyfin"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals("请先登录 Jellyfin", authErr.reason)
        assertTrue(authErr.canRetry)

        val unsupErr = PluginPlayerSession.formatPlayerError(
            SourceUnsupportedException("DRM"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertFalse(unsupErr.canRetry)

        val http403 = PlaybackException(
            "403",
            HttpDataSource.InvalidResponseCodeException(
                403,
                "Forbidden",
                IOException("403"),
                emptyMap(),
                DataSpec(Uri.parse("http://127.0.0.1/a.mp4")),
                ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
        assertEquals(403, PluginPlayerSession.extractHttpStatusCode(http403))
        val ui403 = PluginPlayerSession.formatPlayerError(http403, SourceKind.PLUGIN, "Jellyfin", strings)
        assertFalse("Must never show ERROR_CODE_*", ui403.reason.contains("ERROR_CODE"))
        assertTrue(ui403.canRetry)

        val forbidden = PluginPlayerSession.formatPlayerError(
            SourceForbiddenException("会员专享"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals("会员专享", forbidden.reason)

        // TransportBar error strip drawing & hit-testing
        val bar = TransportBar(1920, 1080)
        val buf = bar.drawError(ui403.reason, "重试", "返回", true, false, false)
        assertEquals(TransportBar.TEX_W * TransportBar.TEX_H * 4, buf.remaining())
        assertTrue(TransportBar.onErrorRetry(TransportBar.TEX_W - 336f))
        assertFalse(TransportBar.onErrorBack(TransportBar.TEX_W - 336f))
        assertTrue(TransportBar.onErrorBack(TransportBar.TEX_W - 120f))
        assertFalse(TransportBar.onErrorRetry(TransportBar.TEX_W - 120f))
        assertFalse(TransportBar.onErrorRetry(Float.NaN))
        assertFalse(TransportBar.onErrorBack(Float.NaN))
    }
}
