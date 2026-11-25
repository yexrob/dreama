package com.limelight.local.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real QuickJS + OkHttp regression tests locking G4 Review Must-Fix #4 and Should-Fix #3:
 * - `PluginMediaSource.danmakuIfIdle` uses `tryLock()` and returns `null` (Busy) IMMEDIATELY while
 *   `playback` holds the plugin instance mutex (never queuing behind or starving playback/refresh).
 * - `PluginMediaSource.danmakuIfIdle` enforces a bounded timeout so a hanging danmaku endpoint
 *   cannot stall subsequent `playback` / refresh calls for ~30 s.
 * - `PluginProtocolMapper.mapDanmaku` validates items individually (bad entries are dropped without
 *   failing the entire window) and enforces `MAX_DANMAKU_PER_WINDOW = 2,000`.
 */
class PluginDanmakuContentionTest {

    private fun buildSource(
        tmpDir: File,
        mainJs: String,
        hostPort: Int,
    ): Pair<PluginRuntime, PluginMediaSource> {
        val manifest = PluginManifest(
            api = 1,
            id = "com.example.danmakurt",
            name = "DanmakuRT",
            version = "1.0.0",
            capabilities = setOf(PluginCapability.DANMAKU),
            permissions = PluginPermissions(
                hosts = listOf("192.168.1.50"),
                userHosts = true,
                lan = true,
            ),
            auth = PluginAuthMode.NONE,
        )
        val box = SoftwareAesSecretBox()
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), box)
        val cookies = PluginCookieJar(File(tmpDir, "cookies.enc"), box)
        val logger = PluginLogger(pluginId = manifest.id, instanceId = "inst-dm")
        val policy = NetworkPolicy(
            permissions = manifest.permissions,
            userConfiguredHosts = setOf("192.168.1.50"),
            dnsResolver = { listOf(InetAddress.getByName("192.168.1.50")) },
        )
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", hostPort))
            .build()
        val gateway = PluginFetchGateway(policy, cookies, okHttp)
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-dm",
            mainJsSource = mainJs,
            config = emptyMap(),
            fetchGateway = gateway,
            storage = storage,
            cookieJar = cookies,
            logger = logger,
        )
        val mapper = PluginProtocolMapper(policy)
        val source = PluginMediaSource(
            instanceId = "inst-dm",
            displayName = "DanmakuRT",
            manifest = manifest,
            runtime = runtime,
            mapper = mapper,
            okHttpClient = okHttp,
        )
        return runtime to source
    }

    @Test
    fun danmakuIfIdleReturnsBusyImmediatelyWhilePlaybackHoldsLockAndSucceedsWhenIdle() = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-dm-contention").toFile()
        val server = MockWebServer()
        val playbackArrived = CountDownLatch(1)
        val releasePlayback = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/playback") {
                    playbackArrived.countDown()
                    releasePlayback.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                }
                return MockResponse().setResponseCode(200).setBody("[]")
            }
        }
        server.start()
        val (runtime, source) = buildSource(
            tmpDir = tmpDir,
            mainJs = """
                export default {
                  async playback(ctx, { itemId }) {
                    await ctx.fetch('http://192.168.1.50/playback');
                    return { url: 'http://192.168.1.50/video.mp4', durationMs: 60000 };
                  },
                  async danmaku(ctx, { itemId, fromMs, toMs }) {
                    return [
                      { timeMs: fromMs + 1000, text: '正常弹幕', color: '#FFFFFF', mode: 'scroll' },
                      { timeMs: -5, text: '负数坏条目' },
                      { timeMs: fromMs + 2000, text: '第二条正常', color: 16737945, mode: 'top' }
                    ];
                  }
                };
            """.trimIndent(),
            hostPort = server.port,
        )
        try {
            // Start slow playback() on Dispatchers.IO so it immediately enters PluginRuntime.callMutex
            val playbackJob = async(Dispatchers.IO) { source.playback("item-1") }
            assertTrue("playback request should reach server", playbackArrived.await(5, TimeUnit.SECONDS))

            // While playback() holds the mutex, danmakuIfIdle MUST return null (Busy) immediately!
            val t0 = System.nanoTime()
            val busyResult = source.danmakuIfIdle("item-1", 0L, 60_000L)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            assertNull("danmakuIfIdle must return null (Busy) instead of queuing behind playback", busyResult)
            assertTrue("danmakuIfIdle must return immediately (took ${elapsedMs}ms)", elapsedMs < 200L)

            // Release playback and wait for it to finish
            releasePlayback.countDown()
            val info = playbackJob.await()
            assertEquals("http://192.168.1.50/video.mp4", info.url)

            // Now that runtime is idle, danmakuIfIdle succeeds AND drops the single bad entry without failing!
            val loaded = source.danmakuIfIdle("item-1", 0L, 60_000L)
            assertNotNull(loaded)
            assertEquals(2, loaded!!.size)
            assertEquals("正常弹幕", loaded[0].text)
            assertEquals("第二条正常", loaded[1].text)
        } finally {
            releasePlayback.countDown()
            runtime.shutdown()
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun mapDanmakuSkipsMalformedItemsWithoutFailingWindowAndEnforces2000ItemCap() {
        val policy = NetworkPolicy(permissions = PluginPermissions(hosts = listOf("example.com")))
        val mapper = PluginProtocolMapper(policy)

        // Mix of valid entries and multiple invalid entry shapes
        val mixed: JsonArray = buildJsonArray {
            add(buildJsonObject { put("timeMs", 1500); put("text", "有效1"); put("color", "#FFFFFF"); put("mode", "scroll") })
            add(JsonPrimitive(42)) // non-object
            add(buildJsonObject { put("timeMs", -100); put("text", "负时间") })
            add(buildJsonObject { put("timeMs", 2000); put("text", 12345) }) // non-string text
            add(buildJsonObject { put("timeMs", 2500); put("text", "   ") }) // blank text
            add(buildJsonObject { put("timeMs", 3000) }) // missing text
            add(buildJsonObject { put("timeMs", 4000); put("text", "有效2"); put("color", 0x00FFAA); put("mode", "top") })
        }
        val mapped = mapper.mapDanmaku(mixed)
        assertEquals(2, mapped.size)
        assertEquals("有效1", mapped[0].text)
        assertEquals("有效2", mapped[1].text)
        assertEquals("#00FFAA", mapped[1].color)
        assertEquals(DanmakuMode.TOP, mapped[1].mode)

        // Oversized window of 2,500 valid entries caps cleanly at MAX_DANMAKU_PER_WINDOW (2,000)
        val huge: JsonArray = buildJsonArray {
            for (i in 0 until 2_500) {
                add(buildJsonObject { put("timeMs", i * 10); put("text", "dm-$i") })
            }
        }
        val capped = mapper.mapDanmaku(huge)
        assertEquals(PluginProtocolMapper.MAX_DANMAKU_PER_WINDOW, capped.size)
    }
}
