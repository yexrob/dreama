package com.limelight.local.plugin

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.dash.BaseUrlExclusionList
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.local.media.Artwork
import com.limelight.local.media.DashStream
import com.limelight.local.media.MediaTrack
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.WallDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Robolectric-backed Android framework & real ExoPlayer end-to-end tests:
 * 1. B3 / Item #5: Real `ExoPlayer` + `MockWebServer` E2E tests proving:
 *    - Synthesized DASH MPD primary 404 -> backup BaseURL failover -> `Player.STATE_ENDED`
 *    - Synthesized DASH MPD primary mid-stream disconnect -> backup BaseURL failover -> `Player.STATE_ENDED`
 *    - Non-DASH progressive open failure (503) -> backup failover -> `Player.STATE_ENDED`
 *    - Non-DASH progressive mid-stream disconnect -> backup Range resume -> `Player.STATE_ENDED`
 * 2. Item #4: Public `PluginPlayback` and `PluginImages` facade E2E tests.
 * 3. Item #10: Real `ExoPlayer` HLS `Authorization` header propagation across
 *    `master.m3u8` -> `variant.m3u8` -> `hls_seg0.ts` (and cross-origin stripping).
 * 4. Official Room `MigrationTestHelper` validating `WallDatabase.MIGRATION_1_2`.
 * 5. `AndroidKeystoreSecretBox` smoke check under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3DashAndRoomTest {

    /**
     * Lightweight Media3 renderer consuming decoded sample buffers cleanly on JVM/Robolectric
     * so real `ExoPlayer`, `DashMediaSource`, `HlsMediaSource`, `ProgressiveMediaSource`,
     * `SafePluginHttpDataSource`, and `FailoverUrlDataSource` execute end-to-end to `STATE_ENDED`
     * without requiring hardware `MediaCodec` decoders.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private class ConsumingFakeRenderer(trackType: Int) : BaseRenderer(trackType) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false
        var sampleCount: Int = 0
            private set

        override fun getName(): String = "ConsumingFakeRenderer-$trackType"
        override fun supportsFormat(format: Format): Int =
            if (androidx.media3.common.MimeTypes.getTrackType(format.sampleMimeType) == trackType) {
                RendererCapabilities.create(C.FORMAT_HANDLED)
            } else {
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }

        override fun isReady(): Boolean = true
        override fun isEnded(): Boolean = ended

        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            ended = false
        }

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            if (ended) return
            while (true) {
                buffer.clear()
                when (readSource(formatHolder, buffer, 0)) {
                    C.RESULT_BUFFER_READ -> {
                        if (buffer.isEndOfStream) {
                            ended = true
                            return
                        }
                        sampleCount += 1
                    }
                    C.RESULT_FORMAT_READ -> continue
                    else -> return
                }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun loadTestMedia(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("test-media/$name")!!.use { it.readBytes() }

    private fun serveRangeBytes(bytes: ByteArray, rangeHeader: String?): MockResponse {
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val parts = spec.split("-")
            val start = parts[0].toIntOrNull() ?: 0
            val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
            val slice = bytes.copyOfRange(start, end + 1)
            return MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Length", slice.size)
                .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                .setHeader("Accept-Ranges", "bytes")
                .setBody(Buffer().write(slice))
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Length", bytes.size)
            .setHeader("Accept-Ranges", "bytes")
            .setBody(Buffer().write(bytes))
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun runRealExoPlayerToCompletion(
        context: Context,
        mediaSource: androidx.media3.exoplayer.source.MediaSource,
        timeoutMs: Long = 10_000L,
    ): Pair<Int, Int> {
        val videoRenderer = ConsumingFakeRenderer(C.TRACK_TYPE_VIDEO)
        val audioRenderer = ConsumingFakeRenderer(C.TRACK_TYPE_AUDIO)
        val renderersFactory = RenderersFactory {
            _: Handler,
            _: VideoRendererEventListener,
            _: AudioRendererEventListener,
            _: TextOutput,
            _: MetadataOutput,
            ->
            arrayOf<Renderer>(videoRenderer, audioRenderer)
        }
        val player = ExoPlayer.Builder(context, renderersFactory).build()
        val errorRef = AtomicReference<PlaybackException?>(null)
        val stateRef = AtomicInteger(Player.STATE_IDLE)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                stateRef.set(playbackState)
            }
            override fun onPlayerError(error: PlaybackException) {
                errorRef.set(error)
            }
        })
        try {
            player.setMediaSource(mediaSource)
            player.playWhenReady = true
            player.prepare()

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                if (errorRef.get() != null || stateRef.get() == Player.STATE_ENDED) break
                Thread.sleep(10)
            }
            val err = errorRef.get()
            if (err != null) {
                throw AssertionError("Real ExoPlayer failed with PlaybackException: ${err.errorCodeName}: ${err.message}", err)
            }
            assertEquals(
                "Real ExoPlayer must reach STATE_ENDED (state=${stateRef.get()}, pos=${player.currentPosition}, dur=${player.duration}, loading=${player.isLoading}, vSamples=${videoRenderer.sampleCount}, aSamples=${audioRenderer.sampleCount}, vEnded=${videoRenderer.isEnded}, aEnded=${audioRenderer.isEnded})",
                Player.STATE_ENDED,
                stateRef.get(),
            )
            return videoRenderer.sampleCount to audioRenderer.sampleCount
        } finally {
            player.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun openTestPluginSource(
        rootDir: File,
        okHttp: OkHttpClient,
        lan: Boolean = false,
        userHosts: Boolean = false,
        configServer: String? = null,
        dnsIp: String = "93.184.216.34",
    ): Pair<PluginPackageManager, PluginMediaSource> = runBlocking {
        val store = InMemoryPluginStore()
        val mgr = PluginPackageManager(
            rootDir = rootDir,
            store = store,
            secretBox = SoftwareAesSecretBox(),
            okHttpClient = okHttp,
        )
        val configSchema = if (configServer != null) {
            """[{"key":"server","type":"url","label":"Server"}]"""
        } else {
            "[]"
        }
        val manifestJson = """
            {
              "api": 1,
              "id": "dev.depth3d.exotest",
              "name": "ExoTest",
              "version": "1.0.0",
              "permissions": { "hosts": [], "userHosts": $userHosts, "lan": $lan },
              "config": $configSchema
            }
        """.trimIndent()
        val mainJs = """
            export default {
              home() { return []; },
              browse() { return { items: [] }; },
              detail() { return { id: '1', kind: 'video', title: 't' }; },
              playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
            };
        """.trimIndent()
        mgr.installFromBytes(PackageManagerAndMigrationTest.buildPluginZip(manifestJson, mainJs))
        val cfg = if (configServer != null) {
            mapOf("server" to kotlinx.serialization.json.JsonPrimitive(configServer))
        } else {
            emptyMap()
        }
        val inst = mgr.createInstance("dev.depth3d.exotest", "ExoTest", cfg)
        val src = mgr.openSource(inst.instanceId) { listOf(InetAddress.getByName(dnsIp)) }
        mgr to src
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun realExoPlayerDashFailsOverOnPrimary404AndOnMidStreamDisconnect() {
        val videoBytes = loadTestMedia("dash_video.mp4")
        val audioBytes = loadTestMedia("dash_audio.mp4")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rootDir = Files.createTempDirectory("d3-exo-dash").toFile()

        // Scenario A: Primary BaseURL returns HTTP 404 -> ExoPlayer switches to backup BaseURL and reaches STATE_ENDED
        val hits404 = CopyOnWriteArrayList<String>()
        val serverA = MockWebServer()
        serverA.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                hits404 += p
                return when (p) {
                    "/primary/v.mp4" -> MockResponse().setResponseCode(404).setBody("Not Found")
                    "/backup/v.mp4" -> serveRangeBytes(videoBytes, request.getHeader("Range"))
                    "/primary/a.mp4" -> serveRangeBytes(audioBytes, request.getHeader("Range"))
                    "/backup/a.mp4" -> serveRangeBytes(audioBytes, request.getHeader("Range"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        serverA.start()

        // Scenario B: Primary serves initRange (0-790) OK, then disconnects mid-stream on media segment -> ExoPlayer switches to backup!
        val hitsDisconnect = CopyOnWriteArrayList<String>()
        val serverB = MockWebServer()
        serverB.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                val range = request.getHeader("Range")
                hitsDisconnect += "$p|$range"
                return when (p) {
                    "/primary/v.mp4" -> {
                        if (range == "bytes=0-790" || range == "bytes=791-842") {
                            serveRangeBytes(videoBytes, range)
                        } else {
                            // Mid-stream disconnect on the media segment!
                            serveRangeBytes(videoBytes, range)
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        }
                    }
                    "/backup/v.mp4" -> serveRangeBytes(videoBytes, range)
                    "/primary/a.mp4" -> serveRangeBytes(audioBytes, range)
                    "/backup/a.mp4" -> serveRangeBytes(audioBytes, range)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        serverB.start()

        try {
            // --- Baseline: healthy DASH stream to establish exact reference sample counts (Round 4 Item #8) ---
            val clientBase = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", serverA.port))
                .build()
            val (mgrBase, srcBase) = openTestPluginSource(File(rootDir, "base"), clientBase)
            val (baseV, baseA) = try {
                val baseHost = "http://93.184.216.34:${serverA.port}"
                val infoBase = PlaybackInfo(
                    url = "",
                    title = "DASH Healthy Baseline",
                    durationMs = 500L,
                    dash = DashStream(
                        video = listOf(
                            MediaTrack(
                                id = "v1",
                                url = "$baseHost/backup/v.mp4",
                                mime = "video/mp4",
                                codecs = "avc1.42c00a",
                                bandwidth = 80_000,
                                width = 64,
                                height = 64,
                                initRange = "0-790",
                                indexRange = "791-842",
                            ),
                        ),
                        audio = listOf(
                            MediaTrack(
                                id = "a1",
                                url = "$baseHost/backup/a.mp4",
                                mime = "audio/mp4",
                                codecs = "mp4a.40.2",
                                bandwidth = 32_000,
                                initRange = "0-728",
                                indexRange = "729-780",
                            ),
                        ),
                    ),
                )
                runRealExoPlayerToCompletion(context, PluginPlayback.createMediaSource(context, srcBase, infoBase))
            } finally {
                runBlocking { mgrBase.shutdownAll() }
            }
            assertTrue("Baseline DASH must decode video & audio samples", baseV > 0 && baseA > 0)
            hits404.clear()

            // --- Run Scenario A ---
            val clientA = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", serverA.port))
                .build()
            val (mgrA, srcA) = openTestPluginSource(File(rootDir, "a"), clientA)
            try {
                val baseAUrl = "http://93.184.216.34:${serverA.port}"
                val infoA = PlaybackInfo(
                    url = "",
                    title = "DASH 404 Failover",
                    durationMs = 500L,
                    dash = DashStream(
                        video = listOf(
                            MediaTrack(
                                id = "v1",
                                url = "$baseAUrl/primary/v.mp4",
                                backupUrls = listOf("$baseAUrl/backup/v.mp4"),
                                mime = "video/mp4",
                                codecs = "avc1.42c00a",
                                bandwidth = 80_000,
                                width = 64,
                                height = 64,
                                initRange = "0-790",
                                indexRange = "791-842",
                            ),
                        ),
                        audio = listOf(
                            MediaTrack(
                                id = "a1",
                                url = "$baseAUrl/primary/a.mp4",
                                backupUrls = listOf("$baseAUrl/backup/a.mp4"),
                                mime = "audio/mp4",
                                codecs = "mp4a.40.2",
                                bandwidth = 32_000,
                                initRange = "0-728",
                                indexRange = "729-780",
                            ),
                        ),
                    ),
                )
                // Use public PluginPlayback facade (Item #4)
                val mediaSourceA = PluginPlayback.createMediaSource(context, srcA, infoA)
                val (vSamplesA, aSamplesA) = runRealExoPlayerToCompletion(context, mediaSourceA)
                assertEquals("DASH 404 failover must decode exact same video sample count as healthy baseline", baseV, vSamplesA)
                assertEquals("DASH 404 failover must decode exact same audio sample count as healthy baseline", baseA, aSamplesA)
                assertTrue("Primary must be attempted first: $hits404", hits404.contains("/primary/v.mp4"))
                assertTrue("Backup BaseURL must take over after primary 404: $hits404", hits404.contains("/backup/v.mp4"))
            } finally {
                runBlocking { mgrA.shutdownAll() }
            }

            // --- Run Scenario B ---
            val clientB = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", serverB.port))
                .build()
            val (mgrB, srcB) = openTestPluginSource(File(rootDir, "b"), clientB)
            try {
                val baseB = "http://93.184.216.34:${serverB.port}"
                val infoB = PlaybackInfo(
                    url = "",
                    title = "DASH Mid-Stream Disconnect Failover",
                    durationMs = 500L,
                    dash = DashStream(
                        video = listOf(
                            MediaTrack(
                                id = "v1",
                                url = "$baseB/primary/v.mp4",
                                backupUrls = listOf("$baseB/backup/v.mp4"),
                                mime = "video/mp4",
                                codecs = "avc1.42c00a",
                                bandwidth = 80_000,
                                width = 64,
                                height = 64,
                                initRange = "0-790",
                                indexRange = "791-842",
                            ),
                        ),
                        audio = listOf(
                            MediaTrack(
                                id = "a1",
                                url = "$baseB/primary/a.mp4",
                                backupUrls = listOf("$baseB/backup/a.mp4"),
                                mime = "audio/mp4",
                                codecs = "mp4a.40.2",
                                bandwidth = 32_000,
                                initRange = "0-728",
                                indexRange = "729-780",
                            ),
                        ),
                    ),
                )
                val mediaSourceB = PluginPlayback.createMediaSource(context, srcB, infoB)
                val (vSamplesB, aSamplesB) = runRealExoPlayerToCompletion(context, mediaSourceB)
                assertEquals("DASH mid-stream disconnect failover must match healthy baseline video samples", baseV, vSamplesB)
                assertEquals("DASH mid-stream disconnect failover must match healthy baseline audio samples", baseA, aSamplesB)
                assertTrue("Primary init/segment must be tried first: $hitsDisconnect", hitsDisconnect.any { it.startsWith("/primary/v.mp4") })
                assertTrue("Backup BaseURL must take over after primary disconnect: $hitsDisconnect", hitsDisconnect.any { it.startsWith("/backup/v.mp4") })
            } finally {
                runBlocking { mgrB.shutdownAll() }
            }
        } finally {
            serverA.shutdown()
            serverB.shutdown()
            rootDir.deleteRecursively()
        }
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun realExoPlayerNonDashProgressiveFailsOverOnOpenErrorAndMidStreamDisconnect() {
        val progBytes = loadTestMedia("progressive.mp4")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rootDir = Files.createTempDirectory("d3-exo-prog").toFile()

        // Scenario A: Primary returns 503 on open -> FailoverUrlDataSource switches to backup -> ExoPlayer STATE_ENDED
        val hitsOpen = CopyOnWriteArrayList<String>()
        val serverOpen = MockWebServer()
        serverOpen.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                hitsOpen += p
                return when (p) {
                    "/primary.mp4" -> MockResponse().setResponseCode(503).setBody("Down")
                    "/backup.mp4" -> serveRangeBytes(progBytes, request.getHeader("Range"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        serverOpen.start()

        // Scenario B: Primary disconnects mid-body -> FailoverUrlDataSource resumes from backup via Range -> ExoPlayer STATE_ENDED
        val hitsMid = CopyOnWriteArrayList<String>()
        val serverMid = MockWebServer()
        serverMid.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                val range = request.getHeader("Range")
                hitsMid += "$p|$range"
                return when (p) {
                    "/primary.mp4" -> serveRangeBytes(progBytes, range)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    "/backup.mp4" -> serveRangeBytes(progBytes, range)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        serverMid.start()

        try {
            // --- Healthy progressive baseline for exact sample count comparison (Round 4 Item #8) ---
            val clientBase = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", serverOpen.port))
                .build()
            val (mgrBase, srcBase) = openTestPluginSource(File(rootDir, "base"), clientBase)
            val (baseV, baseA) = try {
                val baseHost = "http://93.184.216.34:${serverOpen.port}"
                val infoBase = PlaybackInfo(
                    url = "",
                    title = "Non-DASH Healthy Baseline",
                    dash = DashStream(
                        video = listOf(
                            MediaTrack(
                                id = "v1",
                                url = "$baseHost/backup.mp4",
                                mime = "video/mp4",
                                codecs = "avc1.42c00a",
                                bandwidth = 80_000,
                            ),
                        ),
                    ),
                )
                runRealExoPlayerToCompletion(context, PluginPlayback.createMediaSource(context, srcBase, infoBase))
            } finally {
                runBlocking { mgrBase.shutdownAll() }
            }
            assertTrue("Baseline progressive must decode video & audio samples", baseV > 0 && baseA > 0)
            hitsOpen.clear()

            // --- Scenario A: open-time 503 failover ---
            val clientA = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", serverOpen.port))
                .build()
            val (mgrA, srcA) = openTestPluginSource(File(rootDir, "open"), clientA)
            try {
                val baseAUrl = "http://93.184.216.34:${serverOpen.port}"
                // Non-DASH progressive separated tracks (no initRange/indexRange -> MergedProgressiveTracks + FailoverUrlDataSource)
                val infoA = PlaybackInfo(
                    url = "",
                    title = "Non-DASH Open Failover",
                    dash = DashStream(
                        video = listOf(
                            MediaTrack(
                                id = "v1",
                                url = "$baseAUrl/primary.mp4",
                                backupUrls = listOf("$baseAUrl/backup.mp4"),
                                mime = "video/mp4",
                                codecs = "avc1.42c00a",
                                bandwidth = 80_000,
                            ),
                        ),
                    ),
                )
                val (vSamples, aSamples) = runRealExoPlayerToCompletion(
                    context,
                    PluginPlayback.createMediaSource(context, srcA, infoA),
                )
                assertEquals("Non-DASH open failover must decode exact same video sample count as baseline", baseV, vSamples)
                assertEquals("Non-DASH open failover must decode exact same audio sample count as baseline", baseA, aSamples)
                assertTrue(hitsOpen.contains("/primary.mp4"))
                assertTrue(hitsOpen.contains("/backup.mp4"))
            } finally {
                runBlocking { mgrA.shutdownAll() }
            }

            // --- Scenario B: mid-stream disconnect failover ---
            val clientB = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", serverMid.port))
                .build()
            val (mgrB, srcB) = openTestPluginSource(File(rootDir, "mid"), clientB)
            try {
                val baseB = "http://93.184.216.34:${serverMid.port}"
                val infoB = PlaybackInfo(
                    url = "",
                    title = "Non-DASH Mid-Stream Disconnect Failover",
                    dash = DashStream(
                        video = listOf(
                            MediaTrack(
                                id = "v1",
                                url = "$baseB/primary.mp4",
                                backupUrls = listOf("$baseB/backup.mp4"),
                                mime = "video/mp4",
                                codecs = "avc1.42c00a",
                                bandwidth = 80_000,
                            ),
                        ),
                    ),
                )
                val (vSamples, aSamples) = runRealExoPlayerToCompletion(
                    context,
                    PluginPlayback.createMediaSource(context, srcB, infoB),
                )
                assertEquals("Mid-stream failover must match healthy baseline video sample count", baseV, vSamples)
                assertEquals("Mid-stream failover must match healthy baseline audio sample count", baseA, aSamples)
                assertTrue("Primary must be attempted first: $hitsMid", hitsMid.any { it.startsWith("/primary.mp4") })
                assertTrue(
                    "Backup must resume via Range after primary mid-stream disconnect: $hitsMid",
                    hitsMid.any { it.startsWith("/backup.mp4|bytes=") },
                )
            } finally {
                runBlocking { mgrB.shutdownAll() }
            }
        } finally {
            serverOpen.shutdown()
            serverMid.shutdown()
            rootDir.deleteRecursively()
        }
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun realExoPlayerHlsPropagatesAuthHeaderToSubPlaylistAndSegmentsAndPluginImagesFacadeWorks() {
        val tsBytes = loadTestMedia("hls_seg0.ts")
        val validPngBytes = run {
            val bmp = android.graphics.Bitmap.createBitmap(160, 120, android.graphics.Bitmap.Config.ARGB_8888)
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()
            baos.toByteArray()
        }
        // Crafted 20000x20000 PNG IHDR header (67 bytes) to verify inJustDecodeBounds pixel-bomb rejection
        val pixelBombPngBytes = buildPngHeaderBytes(width = 20_000, height = 20_000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rootDir = Files.createTempDirectory("d3-exo-hls").toFile()
        val expectedAuth = """MediaBrowser Client="depth3d", Device="Headset", DeviceId="d1", Version="1.0.0", Token="hls-tok-99""""
        val authByPath = java.util.Collections.synchronizedMap(LinkedHashMap<String, String?>())

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                val auth = request.getHeader("Authorization")
                authByPath[p] = auth
                if (p.startsWith("/Videos/") && auth != expectedAuth) {
                    return MockResponse().setResponseCode(401).setBody("Missing Authorization")
                }
                return when (p) {
                    "/Videos/item1/master.m3u8" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            "#EXTM3U\n" +
                                "#EXT-X-STREAM-INF:BANDWIDTH=120000,RESOLUTION=64x64\n" +
                                "variant.m3u8\n",
                        )
                    "/Videos/item1/variant.m3u8" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            "#EXTM3U\n" +
                                "#EXT-X-VERSION:3\n" +
                                "#EXT-X-TARGETDURATION:1\n" +
                                "#EXT-X-MEDIA-SEQUENCE:0\n" +
                                "#EXTINF:0.500,\n" +
                                "seg0.ts\n" +
                                "#EXT-X-ENDLIST\n",
                        )
                    "/Videos/item1/seg0.ts" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "video/mp2t")
                        .setHeader("Content-Length", tsBytes.size)
                        .setBody(Buffer().write(tsBytes))
                    "/cross/seg0.ts" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "video/mp2t")
                        .setHeader("Content-Length", tsBytes.size)
                        .setBody(Buffer().write(tsBytes))
                    "/Videos/item1/cross-variant.m3u8" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            "#EXTM3U\n" +
                                "#EXT-X-VERSION:3\n" +
                                "#EXT-X-TARGETDURATION:1\n" +
                                "#EXT-X-MEDIA-SEQUENCE:0\n" +
                                "#EXTINF:0.500,\n" +
                                "http://93.184.216.35:${server.port}/cross/seg0.ts\n" +
                                "#EXT-X-ENDLIST\n",
                        )
                    "/Items/item1/Images/Primary" -> {
                        if (auth != expectedAuth) MockResponse().setResponseCode(401)
                        else MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(validPngBytes))
                    }
                    "/Items/item1/Images/PixelBomb" -> {
                        MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(pixelBombPngBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(MultiIpRedirectSocketFactory(setOf("192.168.1.50", "93.184.216.35"), server.port))
                .build()
            val lanBase = "http://192.168.1.50:${server.port}"
            val (mgr, src) = openTestPluginSource(
                rootDir = rootDir,
                okHttp = client,
                lan = true,
                userHosts = true,
                configServer = lanBase,
                dnsIp = "192.168.1.50",
            )
            try {
                // 1. Same-origin HLS master -> variant -> segment ALL receive Authorization header!
                val hlsInfo = PlaybackInfo(
                    url = "$lanBase/Videos/item1/master.m3u8",
                    headers = mapOf("Authorization" to expectedAuth),
                    mimeType = "application/x-mpegURL",
                )
                val (vSamples, aSamples) = runRealExoPlayerToCompletion(
                    context,
                    PluginPlayback.createMediaSource(context, src, hlsInfo),
                )
                assertTrue("HLS video samples decoded", vSamples > 0)
                assertTrue("HLS audio samples decoded", aSamples > 0)
                assertEquals(expectedAuth, authByPath["/Videos/item1/master.m3u8"])
                assertEquals(expectedAuth, authByPath["/Videos/item1/variant.m3u8"])
                assertEquals(expectedAuth, authByPath["/Videos/item1/seg0.ts"])

                // 2. Cross-origin HLS segment in playlist MUST NOT receive Jellyfin Authorization header!
                val crossInfo = PlaybackInfo(
                    url = "$lanBase/Videos/item1/cross-variant.m3u8",
                    headers = mapOf("Authorization" to expectedAuth),
                    mimeType = "application/x-mpegURL",
                )
                val (cv, ca) = runRealExoPlayerToCompletion(
                    context,
                    PluginPlayback.createMediaSource(context, src, crossInfo),
                )
                assertTrue(cv > 0 && ca > 0)
                assertEquals(expectedAuth, authByPath["/Videos/item1/cross-variant.m3u8"])
                assertNull("Cross-origin HLS segment must NOT receive Authorization header", authByPath["/cross/seg0.ts"])

                // 3. Round 4 Item #3: Public PluginImages.loadBitmap enforces NetworkPolicy, auth header,
                // inJustDecodeBounds pixel-bomb protection, downsampling, and rejects isVideoFrame / file:// / unlisted LAN
                val decodedBmp = runBlocking {
                    PluginImages.loadBitmap(
                        src,
                        Artwork(
                            url = "$lanBase/Items/item1/Images/Primary",
                            headers = mapOf("Authorization" to expectedAuth),
                        ),
                        maxEdgePx = 80,
                    )
                }
                assertNotNull("Valid PNG must decode to Bitmap", decodedBmp)
                assertTrue("Decoded Bitmap must respect maxEdgePx=80 (got ${decodedBmp!!.width}x${decodedBmp.height})", maxOf(decodedBmp.width, decodedBmp.height) <= 80)
                decodedBmp.recycle()

                // Pixel-bomb PNG (20000x20000 in 67 bytes) MUST be rejected at inJustDecodeBounds before allocating pixels
                runBlocking {
                    try {
                        PluginImages.loadBitmap(src, Artwork(url = "$lanBase/Items/item1/Images/PixelBomb"))
                        fail("20000x20000 pixel-bomb PNG must be rejected by PluginImages.loadBitmap")
                    } catch (_: IOException) {
                    }
                }

                // isVideoFrame=true MUST be rejected (MediaMetadataRetriever forbidden for plugin URLs)
                runBlocking {
                    try {
                        PluginImages.loadBitmap(src, Artwork(url = "$lanBase/Items/item1/Images/Primary", isVideoFrame = true))
                        fail("isVideoFrame=true must be rejected by PluginImages.loadBitmap")
                    } catch (_: IllegalArgumentException) {
                    }
                }

                // PluginImages MUST reject unlisted private IP and file://
                runBlocking {
                    for (bad in listOf("file:///etc/passwd", "http://192.168.1.99:${server.port}/x.jpg")) {
                        try {
                            PluginImages.loadBitmap(src, Artwork(url = bad))
                            fail("PluginImages must reject $bad")
                        } catch (_: Exception) {
                        }
                    }
                }

                // Round 5 Item #2: Artworks.load MUST directly reject plugin-stamped Artwork at runtime
                // even if UI code accidentally routes a plugin Artwork into Artworks.load.
                val pluginStampedArt = Artwork(
                    url = "$lanBase/Items/item1/Images/Primary",
                    headers = mapOf("Authorization" to expectedAuth),
                    sourceKind = com.limelight.local.media.SourceKind.PLUGIN,
                )
                runBlocking {
                    try {
                        com.limelight.local.media.Artworks.load(
                            context,
                            pluginStampedArt,
                            com.limelight.local.media.Artworks.Size.CARD,
                        )
                        fail("Artworks.load must throw UnsupportedOperationException for SourceKind.PLUGIN Artwork")
                    } catch (_: UnsupportedOperationException) {
                    }
                }
            } finally {
                runBlocking { mgr.shutdownAll() }
            }
        } finally {
            server.shutdown()
            rootDir.deleteRecursively()
        }
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun realMedia3DashManifestParserHonorsDvbPriorityDurationAndFailsOverFromFailingPrimaryToBackup() {
        val hitPaths = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hitPaths += request.path.orEmpty()
                return when (request.path) {
                    "/primary/v1080.m4s" -> MockResponse().setResponseCode(503).setBody("Primary CDN Down")
                    "/backup1/v1080.m4s" -> MockResponse().setResponseCode(200).setBody("BACKUP_1_STREAM_PAYLOAD")
                    "/backup2/v1080.m4s" -> MockResponse().setResponseCode(200).setBody("BACKUP_2_STREAM_PAYLOAD")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val baseHost = "http://93.184.216.34:${server.port}"
            val dash = DashStream(
                video = listOf(
                    MediaTrack(
                        id = "v1080",
                        url = "$baseHost/primary/v1080.m4s",
                        backupUrls = listOf(
                            "$baseHost/backup1/v1080.m4s",
                            "$baseHost/backup2/v1080.m4s",
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
                ),
                audio = listOf(
                    MediaTrack(
                        id = "a192",
                        url = "$baseHost/primary/a192.m4s",
                        backupUrls = listOf("$baseHost/backup1/a192.m4s"),
                        mime = "audio/mp4",
                        codecs = "mp4a.40.2",
                        bandwidth = 192_000,
                        initRange = "0-512",
                        indexRange = "513-1024",
                    ),
                ),
            )

            // 1. Synthesize MPD and parse with REAL Media3 DashManifestParser
            val mpdXml = DashMpdSynthesizer.synthesize(dash, durationMs = 900_000L)
            val manifest = DashManifestParser().parse(
                Uri.parse("$baseHost/manifest.mpd"),
                ByteArrayInputStream(mpdXml.toByteArray(Charsets.UTF_8)),
            )

            assertFalse("Static MPD must not be dynamic", manifest.dynamic)
            assertEquals("mediaPresentationDuration must be parsed by Media3", 900_000L, manifest.durationMs)
            assertEquals(1, manifest.periodCount)
            val period = manifest.getPeriod(0)
            assertEquals(2, period.adaptationSets.size)

            val videoRep = period.adaptationSets[0].representations.single()
            assertEquals(3, videoRep.baseUrls.size)
            assertEquals(1, videoRep.baseUrls[0].priority)
            assertEquals(2, videoRep.baseUrls[1].priority)
            assertEquals(3, videoRep.baseUrls[2].priority)
            assertEquals("$baseHost/primary/v1080.m4s", videoRep.baseUrls[0].url)
            assertEquals("$baseHost/backup1/v1080.m4s", videoRep.baseUrls[1].url)
            assertEquals("$baseHost/backup2/v1080.m4s", videoRep.baseUrls[2].url)

            val audioRep = period.adaptationSets[1].representations.single()
            assertEquals(listOf(1, 2), audioRep.baseUrls.map { it.priority })

            // 2. Media3 BaseUrlExclusionList MUST deterministically pick priority=1 (primary) every single time
            val exclusionList = BaseUrlExclusionList()
            repeat(20) {
                val chosen = exclusionList.selectBaseUrl(videoRep.baseUrls)
                assertNotNull(chosen)
                assertEquals(1, chosen!!.priority)
                assertEquals("$baseHost/primary/v1080.m4s", chosen.url)
            }

            // 3. Drive real SafePluginHttpDataSource against primary -> HTTP 503 -> Media3 fallback -> backup1 200!
            val policy = NetworkPolicy(
                permissions = PluginPermissions(lan = false),
                dnsResolver = { host -> listOf(InetAddress.getByName(host)) },
            )
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", server.port))
                .build()
            val factory = PlaybackMediaSourceFactory(networkPolicy = policy, okHttpClient = client)
            val trustedOrigins = setOf(baseHost)
            val dsFactory = factory.safeDataSourceFactory(
                pluginHeaders = mapOf("Referer" to "https://site.example.com/"),
                trustedOrigins = trustedOrigins,
            )

            val primaryChoice = exclusionList.selectBaseUrl(videoRep.baseUrls)!!
            val dsPrimary = dsFactory.createDataSource()
            var primaryException: HttpDataSource.InvalidResponseCodeException? = null
            try {
                dsPrimary.open(DataSpec(Uri.parse(primaryChoice.url)))
                fail("Expected HTTP 503 from primary BaseURL")
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                primaryException = e
                assertEquals(503, e.responseCode)
            } finally {
                dsPrimary.close()
            }

            val loadErrorPolicy = PlaybackMediaSourceFactory.LocationFallbackLoadErrorPolicy()
            val fallbackOptions = LoadErrorHandlingPolicy.FallbackOptions(
                videoRep.baseUrls.size,
                0,
                1,
                0,
            )
            val loadErrorInfo = LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(1L, DataSpec(Uri.parse(primaryChoice.url)), 0L),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                primaryException!!,
                1,
            )
            val fallback = loadErrorPolicy.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
            assertNotNull(fallback)
            assertEquals(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION, fallback!!.type)

            exclusionList.exclude(primaryChoice, fallback.exclusionDurationMs)
            val backupChoice = exclusionList.selectBaseUrl(videoRep.baseUrls)!!
            assertEquals(2, backupChoice.priority)
            assertEquals("$baseHost/backup1/v1080.m4s", backupChoice.url)

            val dsBackup = dsFactory.createDataSource()
            val received = try {
                dsBackup.open(DataSpec(Uri.parse(backupChoice.url)))
                val out = ByteArrayOutputStream()
                val buf = ByteArray(1024)
                while (true) {
                    val n = dsBackup.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT || n < 0) break
                    out.write(buf, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            } finally {
                dsBackup.close()
            }
            assertEquals("BACKUP_1_STREAM_PAYLOAD", received)
            assertEquals(listOf("/primary/v1080.m4s", "/backup1/v1080.m4s"), hitPaths.toList())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun roomOfficialMigrationTestHelperValidatesMigration1To2AndPreservesData() {
        val dbName = "wall-migration-test.db"
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            WallDatabase::class.java,
        )

        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO watch_progress (sourceId, itemId, title, positionMs, durationMs, updatedAt) " +
                    "VALUES ('local', 'v:99', '星际穿越', 45000, 9000000, 1700000000000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, WallDatabase.MIGRATION_1_2)
        db.use { migrated ->
            migrated.query("SELECT title, positionMs FROM watch_progress WHERE itemId = 'v:99'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("星际穿越", c.getString(0))
                assertEquals(45000L, c.getLong(1))
            }

            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL(
                "INSERT INTO plugin_packages VALUES " +
                    "('dev.depth3d.ref','Ref','1.0.0','depth3d','desc','{}','/pkg',NULL,'https://repo.example/index.json'," +
                    "'repo:https://repo.example/index.json','deadbeef',1,0,'{}',1000,1000)",
            )
            migrated.execSQL(
                "INSERT INTO plugin_instances VALUES ('inst-1','dev.depth3d.ref','My Ref','cipher',1,'NONE',NULL,1000,1000)",
            )
            migrated.execSQL("DELETE FROM plugin_packages WHERE pluginId = 'dev.depth3d.ref'")
            migrated.query("SELECT COUNT(*) FROM plugin_instances").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun androidKeystoreSecretBoxBehaviorUnderRobolectric() {
        val box = AndroidKeystoreSecretBox(keyAlias = "depth3d_test_alias")
        try {
            val ct = box.encryptToBase64("hello-keystore")
            assertEquals("hello-keystore", box.decryptFromBase64(ct))
        } catch (e: Exception) {
            assertTrue(
                "Expected AndroidKeyStore KeyStoreException on Robolectric JVM, got: ${e.javaClass.name}: ${e.message}",
                e is java.security.KeyStoreException || e.cause is java.security.NoSuchProviderException ||
                    e.message?.contains("AndroidKeyStore", ignoreCase = true) == true,
            )
        }
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun failoverDataSourceRecoversFromMidStreamDisconnectViaRangeResume() {
        val fullPayload = ByteArray(16_384) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val backupRangeHeaders = ArrayList<String?>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/primary.bin" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", fullPayload.size)
                    .setBody(Buffer().write(fullPayload))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                "/backup.bin" -> {
                    val rangeHdr = request.getHeader("Range")
                    backupRangeHeaders += rangeHdr
                    val start = rangeHdr?.removePrefix("bytes=")?.substringBefore("-")?.toIntOrNull() ?: 0
                    val slice = fullPayload.copyOfRange(start, fullPayload.size)
                    MockResponse()
                        .setResponseCode(if (rangeHdr != null) 206 else 200)
                        .setHeader("Content-Length", slice.size)
                        .setHeader("Content-Range", "bytes $start-${fullPayload.size - 1}/${fullPayload.size}")
                        .setBody(Buffer().write(slice))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val policy = NetworkPolicy(
                permissions = PluginPermissions(lan = false),
                dnsResolver = { host -> listOf(InetAddress.getByName(host)) },
            )
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("93.184.216.34", server.port))
                .build()
            val baseHost = "http://93.184.216.34:${server.port}"
            val executor = SafePluginHttpExecutor(policy, client)
            val upstream = androidx.media3.datasource.DataSource.Factory {
                SafePluginHttpDataSource(executor, emptyMap(), setOf(baseHost))
            }
            val selector = FailoverUrlSelector(listOf("$baseHost/primary.bin", "$baseHost/backup.bin"))
            val ds = FailoverUrlDataSource(upstream, selector)

            val received = java.io.ByteArrayOutputStream()
            try {
                ds.open(androidx.media3.datasource.DataSpec(Uri.parse("$baseHost/primary.bin")))
                val buf = ByteArray(1024)
                while (true) {
                    val n = ds.read(buf, 0, buf.size)
                    if (n < 0) break
                    received.write(buf, 0, n)
                }
            } finally {
                ds.close()
            }

            assertTrue("Full payload must be reassembled across mid-stream failover", fullPayload.contentEquals(received.toByteArray()))
            assertEquals("$baseHost/backup.bin", selector.currentPreferredUrl())
            assertTrue("Backup request must include Range resume header: $backupRangeHeaders", backupRangeHeaders.any { it != null && it.startsWith("bytes=") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun jsonSubtitleVttDataSourceRejectsOversizedSubtitleAbove4Mb() {
        // Round 4 Item #6: JsonSubtitleVttDataSource.open must cap subtitle reads at 4 MB
        val bytesServed = AtomicInteger(0)
        val hugeStreamFactory = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.BaseDataSource(true) {
                private var remaining = 6 * 1024 * 1024
                private var uri: Uri? = null
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    uri = dataSpec.uri
                    return androidx.media3.common.C.LENGTH_UNSET.toLong()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return androidx.media3.common.C.RESULT_END_OF_INPUT
                    val n = minOf(length, remaining)
                    java.util.Arrays.fill(buffer, offset, offset + n, 'a'.code.toByte())
                    remaining -= n
                    bytesServed.addAndGet(n)
                    return n
                }
                override fun getUri(): Uri? = uri
                override fun close() {}
            }
        }
        val ds = JsonSubtitleVttDataSource(hugeStreamFactory)
        try {
            ds.open(androidx.media3.datasource.DataSpec(Uri.parse("https://cdn.example.com/huge.bcc.json")))
            fail("JsonSubtitleVttDataSource must reject subtitles larger than 4 MB")
        } catch (e: IOException) {
            assertTrue("Error message must mention size cap: ${e.message}", e.message.orEmpty().contains("4 MB"))
        } finally {
            ds.close()
        }
        // Must have aborted right after crossing 4 MB rather than buffering all 6 MB
        assertTrue(
            "Must stop reading right after 4 MB limit, served ${bytesServed.get()} bytes",
            bytesServed.get() <= JsonSubtitleVttDataSource.MAX_SUBTITLE_BYTES + 16_384,
        )
    }

    private fun buildPngHeaderBytes(width: Int, height: Int): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        // PNG signature
        baos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun writeChunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            val bb = java.nio.ByteBuffer.allocate(4).putInt(data.size).array()
            baos.write(bb)
            baos.write(typeBytes)
            baos.write(data)
            baos.write(java.nio.ByteBuffer.allocate(4).putInt(crc.toInt()).array())
        }
        val ihdr = java.nio.ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8) // bit depth
            .put(2) // color type RGB
            .put(0)
            .put(0)
            .put(0)
            .array()
        writeChunk("IHDR", ihdr)
        writeChunk("IEND", ByteArray(0))
        return baos.toByteArray()
    }
}
