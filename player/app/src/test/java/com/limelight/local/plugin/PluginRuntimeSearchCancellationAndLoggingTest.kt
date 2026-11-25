package com.limelight.local.plugin

import com.limelight.local.media.PluginInternalException
import com.limelight.local.search.SearchLogSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real QuickJS + OkHttp runtime tests locking Round 1 Must-Fix #2 and #6:
 * - Cooperative cancellation during async `ctx.fetch` / timer does NOT destroy or rebuild the VM
 *   (`creationCount` stays 1) and keeps `home`/`detail`/`playback`/`search` unaffected.
 * - Cancellation during a tight synchronous JS loop (`while (true) {}`) genuinely interrupts JS
 *   and destroys/rebuilds the VM (`creationCount` increments to 2) cleanly.
 * - `suggest()` never cold-starts `init(ctx)` when VM is uninitialized and skips immediately
 *   when another call holds `callMutex` without cancelling the in-flight call.
 * - `ensureInitializedLocked` cancellation rethrows `CancellationException` (not `INTERNAL`)
 *   with ZERO error logs.
 * - Active `search`/`suggest` queries (raw, CJK, URL-encoded) are scrubbed from real
 *   `PluginLogger` ring entries and sink output — covering both plugin `ctx.log.*` and host
 *   `logger.error("Unhandled error in '...'")` lines.
 */
class PluginRuntimeSearchCancellationAndLoggingTest {

    private fun buildRuntimeAndSource(
        tmpDir: File,
        mainJs: String,
        hostPort: Int? = null,
        sinkLines: MutableList<String>? = null,
    ): Pair<PluginRuntime, PluginMediaSource> {
        val manifest = PluginManifest(
            api = 1,
            id = "com.example.searchrt",
            name = "SearchRT",
            version = "1.0.0",
            capabilities = setOf(PluginCapability.SEARCH),
            permissions = PluginPermissions(
                hosts = if (hostPort != null) listOf("192.168.1.50") else listOf("cdn.example.com"),
                userHosts = hostPort != null,
                lan = hostPort != null,
            ),
            auth = PluginAuthMode.NONE,
        )
        val box = SoftwareAesSecretBox()
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), box)
        val cookies = PluginCookieJar(File(tmpDir, "cookies.enc"), box)
        val logger = PluginLogger(
            pluginId = manifest.id,
            instanceId = "inst-1",
            sink = { _, formatted -> sinkLines?.add(formatted) },
        )
        val policy = NetworkPolicy(
            permissions = manifest.permissions,
            userConfiguredHosts = if (hostPort != null) setOf("192.168.1.50") else emptySet(),
            dnsResolver = { host ->
                when (host) {
                    "192.168.1.50" -> listOf(InetAddress.getByName("192.168.1.50"))
                    else -> listOf(InetAddress.getByName("93.184.216.34"))
                }
            },
        )
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .apply {
                if (hostPort != null) {
                    socketFactory(LocalRedirectSocketFactory("192.168.1.50", hostPort))
                }
            }
            .build()
        val gateway = PluginFetchGateway(policy, cookies, okHttp)
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-1",
            mainJsSource = mainJs,
            config = emptyMap(),
            fetchGateway = gateway,
            storage = storage,
            cookieJar = cookies,
            logger = logger,
        )
        val mapper = PluginProtocolMapper(policy)
        val source = PluginMediaSource(
            instanceId = "inst-1",
            displayName = "SearchRT",
            manifest = manifest,
            runtime = runtime,
            mapper = mapper,
            okHttpClient = okHttp,
        )
        return runtime to source
    }

    @Test
    fun asyncFetchCancellationKeepsVmAliveAndPreservesHomeDetailPlayback() = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-rt-cancel-async").toFile()
        val server = MockWebServer()
        val slowRequestArrived = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path?.contains("/slow") == true) {
                    slowRequestArrived.countDown()
                    MockResponse().setBody("""{"ok":true}""").setBodyDelay(10, TimeUnit.SECONDS)
                } else {
                    MockResponse().setBody("""{"ok":true}""")
                }
            }
        }
        server.start()
        val baseUrl = "http://192.168.1.50:${server.port}"
        val mainJs = """
            let initRuns = 0;
            export default {
              async init(ctx) {
                initRuns += 1;
                await ctx.storage.set('initRuns', initRuns);
              },
              async home(ctx) {
                const n = await ctx.storage.get('initRuns');
                return [{ id: 's1', title: '首页-' + n, style: 'poster', items: [{ id: 'm1', kind: 'movie', title: '电影1' }] }];
              },
              async browse() { return { items: [], next: null }; },
              async detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: '详情-' + itemId }; },
              async playback() { return { url: '$baseUrl/video.mp4' }; },
              async search(ctx, { query }) {
                if (query === 'slow') {
                  await ctx.fetch('$baseUrl/slow');
                }
                return { items: [{ id: 'hit-' + query, kind: 'movie', title: '命中 ' + query }], next: null };
              },
              async suggest(ctx, { query }) {
                if (query === 'slowsug') {
                  await ctx.fetch('$baseUrl/slow');
                }
                return [query + ' 联想'];
              }
            };
        """.trimIndent()

        val (runtime, source) = buildRuntimeAndSource(tmpDir, mainJs, hostPort = server.port)
        try {
            // Warm up via home() -> creationCount == 1, initRuns == 1
            val shelves1 = source.home()
            assertEquals("首页-1", shelves1.single().category.title)
            assertEquals(1, runtime.creationCount)

            // Start slow search awaiting ctx.fetch(), then cancel mid-flight
            var caughtCancel = false
            val job = launch(Dispatchers.IO) {
                try {
                    source.search("slow")
                } catch (ce: CancellationException) {
                    caughtCancel = true
                    throw ce
                }
            }
            assertTrue("Slow fetch never reached server", slowRequestArrived.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue("Expected CancellationException", caughtCancel)

            // CRITICAL: VM was NOT destroyed or rebuilt (creationCount still 1, initRuns still 1!)
            assertEquals(1, runtime.creationCount)
            assertTrue(runtime.isInitialized)

            // Home, detail, playback, search, and suggest all continue working on the SAME warm VM
            assertEquals("首页-1", source.home().single().category.title)
            assertEquals("详情-m1", source.detail("m1").title)
            assertEquals("$baseUrl/video.mp4", source.playback("m1").url)
            assertEquals(listOf("hit-潮"), source.search("潮").items.map { it.id })
            assertEquals(listOf("潮 联想"), source.suggest("潮"))
            assertEquals(1, runtime.creationCount)
            assertTrue(
                "Zero ERROR logs expected after cooperative cancellation, got: ${runtime.logger.recentEntries()}",
                runtime.logger.recentEntries().none { it.level == PluginLogLevel.ERROR },
            )
        } finally {
            runCatching { source.close() }
            runCatching { server.shutdown() }
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun tightJsLoopCancellationInterruptsAndRebuildsVmCleanly() = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-rt-cancel-spin").toFile()
        val mainJs = """
            export default {
              home() { return [{ id: 's1', title: '首页', style: 'poster', items: [] }]; },
              browse() { return { items: [], next: null }; },
              detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: '详情' }; },
              playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
              search(ctx, { query }) {
                if (query === 'spin') {
                  while (true) {}
                }
                return { items: [{ id: 'ok', kind: 'movie', title: query }], next: null };
              }
            };
        """.trimIndent()

        val (runtime, source) = buildRuntimeAndSource(tmpDir, mainJs)
        try {
            source.home()
            assertEquals(1, runtime.creationCount)

            var caughtCancel = false
            val job = launch(Dispatchers.IO) {
                try {
                    source.search("spin")
                } catch (ce: CancellationException) {
                    caughtCancel = true
                    throw ce
                }
            }
            delay(80)
            job.cancel()
            job.join()
            assertTrue("Expected CancellationException", caughtCancel)
            // Stuck synchronous JS loop MUST have been interrupted & destroyed
            assertFalse("Interrupted VM must be torn down for rebuild", runtime.isInitialized)

            // Next call rebuilds VM cleanly (creationCount increments from 1 -> 2) and succeeds
            val res = source.search("恢复")
            assertEquals(2, runtime.creationCount)
            assertEquals(listOf("ok"), res.items.map { it.id })
            assertEquals("首页", source.home().single().category.title)
        } finally {
            runCatching { source.close() }
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun suggestSkipsColdVmAndSkipsBusyVmWithoutBlockingOrCancellingActiveCall() = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-rt-suggest-guard").toFile()
        val server = MockWebServer()
        val inFlightArrived = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val initFetchCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/init" -> {
                        initFetchCount.incrementAndGet()
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    "/home-slow" -> {
                        inFlightArrived.countDown()
                        allowFinish.await(5, TimeUnit.SECONDS)
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    else -> MockResponse().setBody("""{"ok":true}""")
                }
            }
        }
        server.start()
        val baseUrl = "http://192.168.1.50:${server.port}"
        val mainJs = """
            export default {
              async init(ctx) {
                await ctx.fetch('$baseUrl/init');
              },
              async home(ctx) {
                await ctx.fetch('$baseUrl/home-slow');
                return [{ id: 's1', title: '已加载', style: 'poster', items: [] }];
              },
              browse() { return { items: [], next: null }; },
              detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: '详情' }; },
              playback() { return { url: '$baseUrl/a.mp4' }; },
              search() { return { items: [], next: null }; },
              suggest(ctx, { query }) { return [query + ' A', query + ' B']; }
            };
        """.trimIndent()

        val (runtime, source) = buildRuntimeAndSource(tmpDir, mainJs, hostPort = server.port)
        try {
            // 1. Cold VM: suggest() MUST skip without triggering bootstrap/init(ctx)
            assertFalse(runtime.isInitialized)
            val coldSug = source.suggest("潮")
            assertTrue(coldSug.isEmpty())
            assertEquals(0, runtime.creationCount)
            assertEquals(0, initFetchCount.get())
            assertFalse(runtime.isInitialized)

            // 2. Start in-flight home() call -> while busy, suggest() skips immediately without cancelling home()
            var homeTitle: String? = null
            val homeJob = launch(Dispatchers.IO) {
                homeTitle = source.home().single().category.title
            }
            assertTrue(inFlightArrived.await(5, TimeUnit.SECONDS))

            val busySug = source.suggest("潮")
            assertTrue("Suggest must skip when another call holds callMutex", busySug.isEmpty())

            // Allow home() to finish cleanly
            allowFinish.countDown()
            homeJob.join()
            assertEquals("已加载", homeTitle)
            assertEquals(1, runtime.creationCount)

            // 3. Now that VM is warm & idle, suggest() executes and returns real suggestions
            val warmSug = source.suggest("潮")
            assertEquals(listOf("潮 A", "潮 B"), warmSug)
            assertEquals(1, runtime.creationCount)
        } finally {
            allowFinish.countDown()
            runCatching { source.close() }
            runCatching { server.shutdown() }
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun ensureInitializedCancellationPropagatesCancellationExceptionWithZeroErrorLogs() = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-rt-init-cancel").toFile()
        val server = MockWebServer()
        val initHit = CountDownLatch(1)
        val firstInit = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (firstInit.incrementAndGet() == 1) {
                    initHit.countDown()
                    return MockResponse().setBody("""{"ok":true}""").setBodyDelay(10, TimeUnit.SECONDS)
                }
                return MockResponse().setBody("""{"ok":true}""")
            }
        }
        server.start()
        val baseUrl = "http://192.168.1.50:${server.port}"
        val mainJs = """
            export default {
              async init(ctx) {
                await ctx.fetch('$baseUrl/init');
              },
              home() { return [{ id: 's1', title: '正常首页', style: 'poster', items: [] }]; },
              browse() { return { items: [], next: null }; },
              detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: '详情' }; },
              playback() { return { url: '$baseUrl/a.mp4' }; },
              search() { return { items: [], next: null }; }
            };
        """.trimIndent()

        val (runtime, source) = buildRuntimeAndSource(tmpDir, mainJs, hostPort = server.port)
        try {
            var caught: Throwable? = null
            val job = launch(Dispatchers.IO) {
                try {
                    source.home()
                } catch (t: Throwable) {
                    caught = t
                    if (t is CancellationException) throw t
                }
            }
            assertTrue("init fetch never reached server", initHit.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()

            assertTrue(
                "Expected CancellationException (not wrapped SourceException), got: $caught",
                caught is CancellationException,
            )
            assertFalse(runtime.isInitialized)
            assertTrue(
                "Cancellation during init must log ZERO errors, got: ${runtime.logger.recentEntries()}",
                runtime.logger.recentEntries().none { it.level == PluginLogLevel.ERROR },
            )

            // Subsequent call initializes cleanly and succeeds
            val shelves = source.home()
            assertEquals("正常首页", shelves.single().category.title)
            assertTrue(runtime.isInitialized)
        } finally {
            runCatching { source.close() }
            runCatching { server.shutdown() }
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun realPluginAndHostLogsRedactSearchAndSuggestQueriesIncludingUrlEncodedForms() = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-rt-log-redact").toFile()
        val sinkLines = CopyOnWriteArrayList<String>()
        val mainJs = """
            export default {
              home(ctx) {
                ctx.log.info('home loaded marker=normal_home_log');
                return [];
              },
              browse() { return { items: [], next: null }; },
              detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: 'T' }; },
              playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
              search(ctx, { query }) {
                ctx.log.info('plugin searching raw=' + query + ' encoded=' + encodeURIComponent(query));
                throw new Error('search crashed for query=' + query + ' url=https://ex.test/?q=' + encodeURIComponent(query));
              },
              suggest(ctx, { query }) {
                ctx.log.warn('suggest lookup: ' + query + ' (' + encodeURIComponent(query) + ')');
                throw new Error('suggest exploded on ' + query);
              }
            };
        """.trimIndent()

        val (runtime, source) = buildRuntimeAndSource(tmpDir, mainJs, sinkLines = sinkLines)
        try {
            // Warm up VM
            source.home()

            val queries = listOf("私密影片 SecretKey", "潮")
            for (q in queries) {
                val encodedPlus = URLEncoder.encode(q, "UTF-8")
                val encodedPct20 = encodedPlus.replace("+", "%20")

                var searchThrew: Throwable? = null
                try {
                    source.search(q)
                } catch (e: PluginInternalException) {
                    searchThrew = e
                }
                assertNotNull("Expected search() to throw PluginInternalException", searchThrew)

                var suggestThrew: Throwable? = null
                try {
                    runtime.invokeOptionalIfReadyAndIdle("suggest", buildJsonObject { put("query", q) })
                } catch (e: PluginInternalException) {
                    suggestThrew = e
                }
                assertNotNull("Expected suggest() to throw PluginInternalException", suggestThrew)

                // Assert EVERY real log entry (both plugin ctx.log.* AND host Unhandled error) scrubbed q & encoded forms!
                val allMessages = runtime.logger.recentEntries().map { it.message } + sinkLines.toList()
                assertTrue("Expected log entries to be recorded", allMessages.isNotEmpty())
                for (line in allMessages) {
                    assertFalse("Raw query '$q' leaked into log: $line", line.contains(q, ignoreCase = true))
                    assertFalse("URL-encoded query '$encodedPlus' leaked into log: $line", line.contains(encodedPlus, ignoreCase = true))
                    assertFalse("Percent-20 query '$encodedPct20' leaked into log: $line", line.contains(encodedPct20, ignoreCase = true))
                }
                assertTrue(
                    "Expected redacted placeholder in recorded logs",
                    runtime.logger.recentEntries().any { it.message.contains(SearchLogSanitizer.REDACTED_QUERY) },
                )
            }

            // Confirm query redaction was cleared after search/suggest returned
            source.home()
            assertTrue(runtime.logger.recentEntries().last().message.contains("normal_home_log"))
        } finally {
            runCatching { source.close() }
            tmpDir.deleteRecursively()
        }
    }
}
