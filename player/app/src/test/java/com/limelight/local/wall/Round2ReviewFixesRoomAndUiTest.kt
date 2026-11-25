package com.limelight.local.wall

import android.app.Application
import android.graphics.Bitmap
import androidx.room.Room
import com.limelight.local.media.Artwork
import com.limelight.local.media.Artworks
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page as MediaPage
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.Shelf
import com.limelight.local.media.Source
import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.WallDatabase
import com.limelight.local.media.WatchProgress
import com.limelight.local.media.WebDavSource
import com.limelight.local.WebDav
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.LocalRedirectSocketFactory
import com.limelight.local.plugin.ManifestAndPermissionTest
import com.limelight.local.plugin.ManifestParser
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.RoomPluginStore
import com.limelight.local.plugin.SecretBox
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.AndroidStringLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Round 2 review fixes & regression suite covering items #1-#5, #8-#9, #11-#13, #15-#17, #19
 * and accepted-behavior regressions #3 and #4, backed by a REAL Room in-memory [WallDatabase].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Round2ReviewFixesRoomAndUiTest {

    private lateinit var app: Application
    private lateinit var db: WallDatabase
    private lateinit var store: RoomPluginStore
    private lateinit var tmpDir: File
    private lateinit var server: MockWebServer

    private val testSecretBox: SecretBox = SoftwareAesSecretBox()

    private fun testDnsResolver(): (String) -> List<InetAddress> =
        { _ -> listOf(InetAddress.getByName("192.168.1.50")) }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, WallDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomPluginStore(db)
        tmpDir = File(System.getProperty("java.io.tmpdir"), "r2-room-ui-${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun newPackageManager(): PluginPackageManager {
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        return PluginPackageManager(
            rootDir = File(tmpDir, "plugins-${System.nanoTime()}"),
            store = store,
            secretBox = testSecretBox,
            okHttpClient = okHttp,
            appVersion = PLUGIN_HOST_VERSION,
            deviceInfo = DeviceInfo.detect(PLUGIN_HOST_VERSION),
        )
    }

    private fun buildFormPluginZip(
        pluginId: String = "com.example.r2test",
        version: String = "1.0.0",
        extraMarker: String = "v1",
    ): ByteArray {
        val manifest = """
            {
              "api": 1,
              "id": "$pluginId",
              "name": "R2 Test Plugin",
              "version": "$version",
              "author": "tester",
              "description": "Round 2 test ($extraMarker)",
              "multiInstance": true,
              "permissions": { "hosts": [], "userHosts": true, "lan": true },
              "config": [
                { "key": "server", "type": "url", "label": "Server", "required": true },
                { "key": "username", "type": "text", "label": "User", "required": true },
                { "key": "password", "type": "password", "label": "Pass", "required": true }
              ],
              "auth": "form"
            }
        """.trimIndent()
        val mainJs = """
            // marker: $extraMarker
            export default {
              async login(ctx) {
                const resp = await ctx.fetch(ctx.config.server + '/login', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ u: ctx.config.username, p: ctx.config.password })
                });
                if (resp.status === 401) throw new ctx.Error('AuthFailed', '账号或密码错误');
                if (resp.status !== 200) throw new ctx.Error('Unavailable', '服务器不可用');
                const data = JSON.parse(resp.text());
                await ctx.storage.set('token', data.token);
                return { ok: true, user: { name: ctx.config.username } };
              },
              async home(ctx) {
                const tok = await ctx.storage.get('token');
                const resp = await ctx.fetch(ctx.config.server + '/home?u=' + encodeURIComponent(ctx.config.username), {
                  headers: { Authorization: 'Bearer ' + tok }
                });
                if (resp.status !== 200) throw new ctx.Error('Unavailable', '加载失败');
                return JSON.parse(resp.text());
              },
              async browse() { return { items: [], nextPage: null }; },
              async detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: 'Detail ' + itemId }; },
              async playback(ctx, { itemId }) { return { url: ctx.config.server + '/v/' + itemId + '.mp4' }; }
            };
        """.trimIndent()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("main.js"))
            zos.write(mainJs.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    // ---- Item #1: Cancellation rollback MUST succeed in REAL Room DB (NonCancellable) ----

    @Test
    fun item01_cancelDuringCreateRollsBackGhostInstanceInRealRoom() = runBlocking {
        val loginStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/login") {
                    loginStarted.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
                return MockResponse().setResponseCode(200).setBody("""{"token":"t1"}""")
            }
        }

        val pm = newPackageManager()
        val installed = pm.installFromBytes(buildFormPluginZip(), sourceUrl = "builtin:r2test")
        val baseUrl = "http://192.168.1.50:${server.port}"
        val createdId = AtomicReference<String?>(null)
        val settledId = AtomicReference<String?>(null)

        val job = launch(Dispatchers.IO) {
            try {
                PluginInstanceAuthenticator.createAndAuthenticateFormOrNone(
                    packageManager = pm,
                    manifest = installed.manifest,
                    displayName = "Ghost Source",
                    config = mapOf(
                        "server" to JsonPrimitive(baseUrl),
                        "username" to JsonPrimitive("alice"),
                        "password" to JsonPrimitive("secret"),
                    ),
                    dnsResolver = testDnsResolver(),
                    onInstanceCreated = { createdId.set(it) },
                    onInstanceSettled = { settledId.set(it) },
                )
            } catch (_: Throwable) {
                // Cancellation (or PluginRuntime wrapping cancellation while coroutine is cancelled)
            }
        }

        assertTrue("Expected login HTTP call to start", loginStarted.await(5, TimeUnit.SECONDS))
        // Cancel while login is in flight — before the fix, Room suspend DAO threw CancellationException
        // inside runCatching and left a ghost row behind in wall_database!
        job.cancel()
        releaseServer.countDown()
        job.join()

        assertNotNull("Expected pre-generated instance id callback", createdId.get())
        assertEquals("Expected settled callback to fire in NonCancellable finally", createdId.get(), settledId.get())
        val remaining = db.pluginInstances().listAll()
        assertTrue("Real Room DB must have ZERO ghost instances after cancel, got: $remaining", remaining.isEmpty())
    }

    @Test
    fun item01_cancelDuringEditRestoresPreviousConfigAndDisplayNameInRealRoom() = runBlocking {
        val allowLogin = AtomicInteger(1)
        val secondLoginEntered = CountDownLatch(1)
        val releaseSecondLogin = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/login") {
                    if (allowLogin.getAndDecrement() > 0) {
                        return MockResponse().setResponseCode(200).setBody("""{"token":"good-token"}""")
                    }
                    secondLoginEntered.countDown()
                    releaseSecondLogin.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("""{"token":"new-token"}""")
                }
                return MockResponse().setResponseCode(200).setBody("[]")
            }
        }

        val pm = newPackageManager()
        val installed = pm.installFromBytes(buildFormPluginZip(), sourceUrl = "builtin:r2test")
        val baseUrl = "http://192.168.1.50:${server.port}"
        val originalConfig = mapOf(
            "server" to JsonPrimitive(baseUrl),
            "username" to JsonPrimitive("alice"),
            "password" to JsonPrimitive("goodpass"),
        )
        val created = PluginInstanceAuthenticator.createAndAuthenticateFormOrNone(
            packageManager = pm,
            manifest = installed.manifest,
            displayName = "Original Name",
            config = originalConfig,
            dnsResolver = testDnsResolver(),
        )

        val editJob = launch(Dispatchers.IO) {
            try {
                PluginInstanceAuthenticator.updateAndAuthenticateFormOrNone(
                    packageManager = pm,
                    manifest = installed.manifest,
                    instanceId = created.instanceId,
                    displayName = "Cancelled Name",
                    config = mapOf(
                        "server" to JsonPrimitive(baseUrl),
                        "username" to JsonPrimitive("bob"),
                        "password" to JsonPrimitive("other"),
                    ),
                    dnsResolver = testDnsResolver(),
                    store = store,
                    prevDisplayName = "Original Name",
                )
            } catch (_: Throwable) {
                // Cancellation (or PluginRuntime wrapping cancellation while coroutine is cancelled)
            }
        }

        assertTrue("Expected edit login to begin", secondLoginEntered.await(5, TimeUnit.SECONDS))
        editJob.cancel()
        releaseSecondLogin.countDown()
        editJob.join()

        val after = db.pluginInstances().get(created.instanceId)
        assertNotNull(after)
        assertEquals("Display name must be rolled back in Room on cancel", "Original Name", after!!.displayName)
        val restoredCfg = pm.getInstanceConfig(created.instanceId)
        assertEquals(JsonPrimitive("alice"), restoredCfg["username"])
        assertEquals(JsonPrimitive("goodpass"), restoredCfg["password"])
    }

    // ---- Item #2: Editing working source with bad password/URL rolls back and keeps source working ----

    @Test
    fun item02_editFailureWithWrongPasswordOrUrlRollsBackAndKeepsSourceWorking() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path == "/login") {
                    val body = request.body.readUtf8()
                    return if (body.contains("\"goodpass\"")) {
                        MockResponse().setResponseCode(200).setBody("""{"token":"valid-tok"}""")
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                }
                if (path.startsWith("/home")) {
                    val auth = request.getHeader("Authorization")
                    if (auth == "Bearer valid-tok" && path.contains("u=alice")) {
                        return MockResponse().setResponseCode(200).setBody(
                            """[{"id":"s1","title":"Movies","items":[{"id":"m1","kind":"movie","title":"Demo"}]}]""",
                        )
                    }
                    return MockResponse().setResponseCode(401)
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val pm = newPackageManager()
        val installed = pm.installFromBytes(buildFormPluginZip(), sourceUrl = "builtin:r2test")
        val baseUrl = "http://192.168.1.50:${server.port}"
        val originalConfig = mapOf(
            "server" to JsonPrimitive(baseUrl),
            "username" to JsonPrimitive("alice"),
            "password" to JsonPrimitive("goodpass"),
        )
        val created = PluginInstanceAuthenticator.createAndAuthenticateFormOrNone(
            packageManager = pm,
            manifest = installed.manifest,
            displayName = "客厅服务器",
            config = originalConfig,
            dnsResolver = testDnsResolver(),
        )
        // Confirm source works initially
        val initialShelves = pm.openSource(created.instanceId, dnsResolver = testDnsResolver()).home()
        assertEquals(1, initialShelves.size)

        // Now edit with WRONG password and changed displayName -> must fail AND roll back!
        try {
            PluginInstanceAuthenticator.updateAndAuthenticateFormOrNone(
                packageManager = pm,
                manifest = installed.manifest,
                instanceId = created.instanceId,
                displayName = "错误名称",
                config = mapOf(
                    "server" to JsonPrimitive(baseUrl),
                    "username" to JsonPrimitive("wronguser"),
                    "password" to JsonPrimitive("badpass"),
                ),
                dnsResolver = testDnsResolver(),
                store = store,
                prevDisplayName = "客厅服务器",
            )
            fail("Expected SourceAuthFailedException for wrong password")
        } catch (_: SourceAuthFailedException) {
            // expected
        }

        // Verify Room restored BOTH displayName and config, AND runtime works immediately!
        val entity = db.pluginInstances().get(created.instanceId)!!
        assertEquals("客厅服务器", entity.displayName)
        val restored = pm.getInstanceConfig(created.instanceId)
        assertEquals(JsonPrimitive("alice"), restored["username"])
        assertEquals(JsonPrimitive("goodpass"), restored["password"])
        val shelvesAfterRollback = pm.openSource(created.instanceId, dnsResolver = testDnsResolver()).home()
        assertEquals("Previously working source must keep working after failed edit", 1, shelvesAfterRollback.size)
        assertEquals("Demo", shelvesAfterRollback.first().items.first().title)
    }

    // ---- Item #3: Plugin artwork decoding runs off Main thread & propagates CancellationException ----

    @Test
    fun item03_pluginArtworkLoadsOffMainThreadAndRethrowsCancellation() = runBlocking {
        val fakeMain = Executors.newSingleThreadExecutor { r -> Thread(r, "test-ui-main") }.asCoroutineDispatcher()
        val fakeIo = Executors.newSingleThreadExecutor { r -> Thread(r, "test-art-io") }.asCoroutineDispatcher()
        try {
            val loaderThread = AtomicReference<String>()
            val art = Artwork(url = "https://cdn.example/poster.jpg", sourceKind = SourceKind.PLUGIN)
            val key = "plugin:test-inst:r2-art-test@300"

            val bmp = withContext(fakeMain) {
                loadPluginArtBitmap(
                    art = art,
                    maxEdgePx = 300,
                    cacheKey = key,
                    ioDispatcher = fakeIo,
                    loader = { _, _ ->
                        loaderThread.set(Thread.currentThread().name)
                        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                    },
                )
            }
            assertNotNull(bmp)
            assertTrue(
                "Loader & decode must execute on IO dispatcher, not UI main (got ${loaderThread.get()})",
                loaderThread.get().startsWith("test-art-io"),
            )
            assertNotNull("Decoded bitmap should be cached", Artworks.cachedByKey(key))

            // Must NOT swallow CancellationException
            var caughtCancel = false
            try {
                withContext(fakeMain) {
                    loadPluginArtBitmap(
                        art = art,
                        maxEdgePx = 300,
                        cacheKey = "plugin:test-inst:cancel@300",
                        ioDispatcher = fakeIo,
                        loader = { _, _ -> throw CancellationException("navigated away") },
                    )
                }
            } catch (_: CancellationException) {
                caughtCancel = true
            }
            assertTrue("loadPluginArtBitmap must propagate CancellationException", caughtCancel)
        } finally {
            fakeMain.close()
            fakeIo.close()
        }
    }

    // ---- Item #4: Continue watching emits immediately, resolves per-source in parallel, respects timeout & 12-item cap ----

    @Test
    fun item04_continueWatchingEmitsImmediatelyAndNeverBlocksLocalOrWebDavOnOfflinePlugin() = runBlocking {
        val hungPluginCalls = AtomicInteger(0)
        val hungPlugin = object : MediaSource {
            override val source = Source("plugin:offline", "Offline Jellyfin", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item {
                hungPluginCalls.incrementAndGet()
                delay(10_000L) // simulates offline hung server
                return Item(itemId, ItemKind.MOVIE, "Late $itemId")
            }
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val fastLocal = object : MediaSource {
            override val source = Source("local", "本机", SourceKind.LOCAL, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item =
                Item(itemId, ItemKind.VIDEO, "Resolved Local $itemId", poster = Artwork("content://local/$itemId"))
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val fastWebDav = object : MediaSource {
            override val source = Source("webdav", "WebDAV", SourceKind.WEBDAV, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item =
                Item(itemId, ItemKind.VIDEO, "Resolved WebDAV $itemId")
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }

        // Case A: hung plugin row is FIRST (newest), followed by fast local + WebDAV rows
        val rowsA = listOf(
            WatchProgress("plugin:offline", "p1", "Offline Movie", 120_000L, 600_000L, 300L),
            WatchProgress("local", "l1", "Local Clip", 60_000L, 300_000L, 200L),
            WatchProgress("webdav", "w1", "NAS Film", 90_000L, 400_000L, 100L),
        )
        val localResolvedAt = CompletableDeferred<Long>()
        val startMs = System.currentTimeMillis()
        val emissions = ArrayList<List<Resume>>()
        val collector = launch {
            ContinueWatchingResolver.resolveFlow(
                rows = rowsA,
                sources = listOf(hungPlugin, fastLocal, fastWebDav),
                perItemTimeoutMs = 400L,
            ).collect { list ->
                emissions += list
                if (list.any { it.item.title == "Resolved Local l1" } &&
                    list.any { it.item.title == "Resolved WebDAV w1" } &&
                    !localResolvedAt.isCompleted
                ) {
                    localResolvedAt.complete(System.currentTimeMillis() - startMs)
                }
            }
        }

        val elapsedForLocalAndWebDav = withTimeout(1_000L) { localResolvedAt.await() }
        assertTrue(
            "Local & WebDAV cards must resolve without waiting for offline plugin timeout (took ${elapsedForLocalAndWebDav}ms)",
            elapsedForLocalAndWebDav < 350L,
        )
        collector.join()
        assertTrue("Phase 1 must emit immediate WatchProgress cards first", emissions.first().size == 3)
        assertEquals("Offline Movie", emissions.first()[0].item.title)

        // Case B: 12 fast local items precede 4 hung plugin items -> stops at 12 immediately without waiting!
        val tailCalls = AtomicInteger(0)
        val slowTail = object : MediaSource {
            override val source = Source("plugin:tail", "Slow Tail", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item {
                tailCalls.incrementAndGet()
                delay(5_000L)
                return Item(itemId, ItemKind.MOVIE, itemId)
            }
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val rowsB = (1..12).map { i ->
            WatchProgress("local", "l$i", "Local $i", 60_000L, 300_000L, 1000L - i)
        } + (1..4).map { i ->
            WatchProgress("plugin:tail", "t$i", "Tail $i", 60_000L, 300_000L, 500L - i)
        }
        val t0 = System.currentTimeMillis()
        val finalB = ContinueWatchingResolver.resolveFlow(
            rows = rowsB,
            sources = listOf(fastLocal, slowTail),
            perItemTimeoutMs = 3_000L,
            limit = 12,
        ).toList().last()
        val elapsedB = System.currentTimeMillis() - t0
        assertEquals(12, finalB.size)
        assertTrue("Flow must stop once top 12 settle (took ${elapsedB}ms)", elapsedB < 1_000L)
    }

    // ---- Item #5: Cross-season default season & next-episode rollover ----

    @Test
    fun item05_seriesResolvesCrossSeasonResumeAndNextSeasonRollover() = runBlocking {
        val seasons = listOf(
            Item("s1", ItemKind.SEASON, "第 1 季", index = 1),
            Item("s2", ItemKind.SEASON, "第 2 季", index = 2),
            Item("s3", ItemKind.SEASON, "第 3 季", index = 3),
        )
        val epsBySeason = mapOf(
            "s1" to listOf(
                Item("s1e1", ItemKind.EPISODE, "S1E1", index = 1, parentId = "s1", durationMs = 1_000_000L),
                Item("s1e2", ItemKind.EPISODE, "S1E2", index = 2, parentId = "s1", durationMs = 1_000_000L),
            ),
            "s2" to listOf(
                Item("s2e1", ItemKind.EPISODE, "S2E1", index = 1, parentId = "s2", durationMs = 1_000_000L),
                Item("s2e2", ItemKind.EPISODE, "S2E2", index = 2, parentId = "s2", durationMs = 1_000_000L),
            ),
            "s3" to listOf(
                Item("s3e1", ItemKind.EPISODE, "S3E1", index = 1, parentId = "s3", durationMs = 1_000_000L),
            ),
        )
        val epById = epsBySeason.values.flatten().associateBy { it.id }
        val fakeSrc = object : MediaSource {
            override val source = Source("plugin:jf", "Jellyfin", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> =
                MediaPage(epsBySeason[categoryId].orEmpty(), null)
            override suspend fun detail(itemId: String): Item = epById[itemId] ?: throw ItemNotFoundException(itemId)
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val strings = AndroidStringLookup(app)

        // 1. No watch history -> S1E1, "播放 · 第 1 季第 1 集"
        val rNone = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = fakeSrc,
            seasons = seasons,
            localProgress = emptyMap(),
            loadSeasonEpisodes = { epsBySeason[it].orEmpty() },
        )!!
        assertEquals("s1", rNone.seasonId)
        assertEquals("s1e1", rNone.target!!.episode.id)
        assertFalse(rNone.target!!.isResume)
        assertEquals("播放 · 第 1 季第 1 集", DetailStateLogic.formatPrimarySeriesButton(rNone.target!!, true, strings))

        // 2. Most recently watched is S2E1 in progress -> default season S2, resume S2E1
        val rMidS2 = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = fakeSrc,
            seasons = seasons,
            localProgress = mapOf(
                "s1e1" to WatchProgress("plugin:jf", "s1e1", "S1E1", 990_000L, 1_000_000L, 100L, parentId = "s1"),
                "s2e1" to WatchProgress("plugin:jf", "s2e1", "S2E1", 300_000L, 1_000_000L, 200L, parentId = "s2"),
            ),
            loadSeasonEpisodes = { epsBySeason[it].orEmpty() },
        )!!
        assertEquals("s2", rMidS2.seasonId)
        assertEquals("s2e1", rMidS2.target!!.episode.id)
        assertEquals(300_000L, rMidS2.target!!.resumePositionMs)
        assertTrue(rMidS2.target!!.isResume)
        assertEquals("继续播放 · 第 2 季第 1 集", DetailStateLogic.formatPrimarySeriesButton(rMidS2.target!!, true, strings))

        // 3. Finished last episode of S1 (S1E2 >= 95%) -> rolls over to S2E1 and selects S2 tab!
        val rCrossSeason = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = fakeSrc,
            seasons = seasons,
            localProgress = mapOf(
                "s1e2" to WatchProgress("plugin:jf", "s1e2", "S1E2", 980_000L, 1_000_000L, 500L, parentId = "s1"),
            ),
            loadSeasonEpisodes = { epsBySeason[it].orEmpty() },
        )!!
        assertEquals("s2", rCrossSeason.seasonId)
        assertEquals(2, rCrossSeason.seasonNumber)
        assertEquals("s2e1", rCrossSeason.target!!.episode.id)
        assertEquals(0L, rCrossSeason.target!!.resumePositionMs)
        assertTrue(rCrossSeason.target!!.isResume)
        assertEquals("继续播放 · 第 2 季第 1 集", DetailStateLogic.formatPrimarySeriesButton(rCrossSeason.target!!, true, strings))

        // 4. Finished S2E1 -> stays on S2, advances to S2E2
        val rNextSameSeason = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = fakeSrc,
            seasons = seasons,
            localProgress = mapOf(
                "s2e1" to WatchProgress("plugin:jf", "s2e1", "S2E1", 970_000L, 1_000_000L, 600L, parentId = "s2"),
            ),
            loadSeasonEpisodes = { epsBySeason[it].orEmpty() },
        )!!
        assertEquals("s2", rNextSameSeason.seasonId)
        assertEquals("s2e2", rNextSameSeason.target!!.episode.id)
        assertEquals("继续播放 · 第 2 季第 2 集", DetailStateLogic.formatPrimarySeriesButton(rNextSameSeason.target!!, true, strings))
    }

    // ---- Item #8: Seasonless multi-part items never create premature empty loader or send bogus browse ----

    @Test
    fun item08_seasonlessMultiPartWaitsForDetailAndReplacesPreloadedLoaderWithoutBogusBrowse() = runBlocking {
        val browseCalls = AtomicInteger(0)
        val spySrc = object : MediaSource {
            override val source = Source("plugin:parts", "Parts", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> {
                browseCalls.incrementAndGet()
                return MediaPage(emptyList(), null)
            }
            override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.SERIES, "Parts")
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val coordinator = SeriesLoaderCoordinator(spySrc)

        // While initial detail() is still loading -> returns null, zero browse calls!
        assertNull(
            coordinator.loaderFor(
                seasonId = "__direct_episodes__",
                hasSeasons = false,
                loadingDetail = true,
                preloadedEpisodes = emptyList(),
            ),
        )
        assertEquals(0, browseCalls.get())

        // Detail arrives with 2 episodes -> loader exposes both without calling browse()
        val firstBatch = listOf(
            Item("p1", ItemKind.EPISODE, "Part 1", index = 1),
            Item("p2", ItemKind.EPISODE, "Part 2", index = 2),
        )
        val loader1 = coordinator.loaderFor(
            seasonId = "__direct_episodes__",
            hasSeasons = false,
            loadingDetail = false,
            preloadedEpisodes = firstBatch,
        )!!
        loader1.loadNextPage()
        assertEquals(listOf("p1", "p2"), loader1.state.items.map { it.id })
        assertNull(loader1.state.nextPage)
        assertEquals(0, browseCalls.get())

        // Preloaded list updates to 3 episodes -> loader MUST be replaced instead of sticking to old list
        val secondBatch = firstBatch + Item("p3", ItemKind.EPISODE, "Part 3", index = 3)
        val loader2 = coordinator.loaderFor(
            seasonId = "__direct_episodes__",
            hasSeasons = false,
            loadingDetail = false,
            preloadedEpisodes = secondBatch,
        )!!
        assertEquals(listOf("p1", "p2", "p3"), loader2.state.items.map { it.id })
        assertEquals(0, browseCalls.get())
    }

    // ---- Item #9: Pending creation instance is hidden from wall until auth finishes ----

    @Test
    fun item09_wallPluginSourceManagerHidesPendingInstanceUntilAuthSucceeds() = runBlocking {
        val opened = ArrayList<String>()
        val manager = WallPluginSourceManager(
            scope = this,
            openSource = { id ->
                opened += id
                object : MediaSource {
                    override val source = Source("plugin:$id", "JF", SourceKind.PLUGIN, "")
                    override suspend fun home(): List<Shelf> = emptyList()
                    override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
                    override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.MOVIE, itemId)
                    override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
                }
            },
        )
        val pkg = InstalledPluginEntity(
            pluginId = "dev.dreama.jellyfin",
            name = "Jellyfin",
            version = "1.0.1",
            author = "depth3d",
            description = "",
            manifestJson = "{}",
            packagePath = "/tmp/p",
            sourceUrl = "builtin:dev.dreama.jellyfin",
            repoUrl = null,
            sourceOrigin = "other:builtin:dev.dreama.jellyfin",
            sha256 = "abc",
            enabled = true,
            needsPermissionReconfirm = false,
            approvedPermissionsJson = "{}",
            installedAt = 1L,
            updatedAt = 1L,
        )
        val inst = PluginInstanceEntity(
            instanceId = "inst-adding",
            pluginId = "dev.dreama.jellyfin",
            displayName = "Adding...",
            encryptedConfig = "{}",
            enabled = true,
            authState = "NEEDS_AUTH",
            authUserJson = null,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val whileAdding = manager.reconcile(listOf(pkg), listOf(inst), pendingInstanceIds = setOf("inst-adding"))
        assertTrue("Pending instance must not appear on wall", whileAdding.sources.isEmpty())
        assertTrue("openSource must not be called while instance is pending", opened.isEmpty())

        val afterAuth = manager.reconcile(listOf(pkg), listOf(inst.copy(authState = "AUTHENTICATED", updatedAt = 2L)), pendingInstanceIds = emptySet())
        assertEquals(1, afterAuth.sources.size)
        assertEquals(listOf("inst-adding"), opened)
        manager.cancelAll()
    }

    // ---- Item #11 & #12: Explicit PLUGIN_HOST_VERSION & background DeviceInfo.detect ----

    @Test
    fun item11_and_12_pluginHostDefersDeviceInfoDetectOffCallingThreadWithHostVersionConstant() = runBlocking {
        val detectThread = AtomicReference<String>()
        val detectVersion = AtomicReference<String>()
        val detectEntered = CountDownLatch(1)
        val allowDetectFinish = CountDownLatch(1)
        val bgExec = Executors.newSingleThreadExecutor { r -> Thread(r, "test-host-bg") }.asCoroutineDispatcher()
        try {
            val callerThreadName = Thread.currentThread().name
            val host = PluginHost.createForTesting(
                context = app,
                database = db,
                store = store,
                packagesRootDir = File(tmpDir, "host-plugins"),
                secretBoxFactory = { testSecretBox },
                detectDeviceInfo = { ver ->
                    detectThread.set(Thread.currentThread().name)
                    detectVersion.set(ver)
                    detectEntered.countDown()
                    allowDetectFinish.await(5, TimeUnit.SECONDS)
                    DeviceInfo.detect(ver)
                },
                ioDispatcher = bgExec,
            )
            // Must return immediately on caller thread WITHOUT blocking on DeviceInfo.detect!
            assertTrue("Background DeviceInfo.detect should start", detectEntered.await(3, TimeUnit.SECONDS))
            allowDetectFinish.countDown()

            val pm = host.packageManager()
            assertNotNull(pm)
            assertTrue(
                "DeviceInfo.detect must run on background dispatcher (got ${detectThread.get()})",
                detectThread.get().startsWith("test-host-bg"),
            )
            assertNotEquals("DeviceInfo.detect must never run on calling/main thread", callerThreadName, detectThread.get())
            assertEquals(PLUGIN_HOST_VERSION, detectVersion.get())
        } finally {
            bgExec.close()
        }
    }

    // ---- Item #13: Built-in plugin upgrades when SHA-256 changes even at identical SemVer ----

    @Test
    fun item13_builtInInstallerUpgradesSameVersionWhenSha256ChangesPreservingInstances() = runBlocking {
        val pm = newPackageManager()
        val blob1 = buildFormPluginZip(pluginId = "dev.dreama.samever", version = "1.0.0", extraMarker = "build-A")
        val blob2 = buildFormPluginZip(pluginId = "dev.dreama.samever", version = "1.0.0", extraMarker = "build-B")

        val r1 = BuiltInPluginInstaller.syncPackages(pm, store, listOf(blob1))
        assertEquals(listOf("dev.dreama.samever"), r1.installed)
        val sha1 = store.getPackage("dev.dreama.samever")!!.sha256

        // Create a user instance that must survive the in-place SHA-256 refresh
        val inst = pm.createInstance(
            pluginId = "dev.dreama.samever",
            displayName = "My Server",
            config = mapOf("server" to JsonPrimitive("http://192.168.1.9:8096"), "username" to JsonPrimitive("u"), "password" to JsonPrimitive("p")),
        )

        // Identical blob -> skipped
        val rSame = BuiltInPluginInstaller.syncPackages(pm, store, listOf(blob1))
        assertEquals(listOf("dev.dreama.samever"), rSame.skipped)

        // Same version (1.0.0) but changed content / SHA-256 -> MUST upgrade in place!
        val rUpgraded = BuiltInPluginInstaller.syncPackages(pm, store, listOf(blob2))
        assertEquals(listOf("dev.dreama.samever"), rUpgraded.upgraded)
        assertTrue(rUpgraded.failed.isEmpty())

        val pkgAfter = store.getPackage("dev.dreama.samever")!!
        assertNotEquals("SHA-256 must update on same-version content change", sha1, pkgAfter.sha256)
        assertEquals("1.0.0", pkgAfter.version)
        assertTrue(File(pkgAfter.packagePath, "main.js").readText().contains("build-B"))
        assertNotNull("Existing user instance must be preserved", store.getInstance(inst.instanceId))
        assertEquals(JsonPrimitive("http://192.168.1.9:8096"), pm.getInstanceConfig(inst.instanceId)["server"])
    }

    // ---- Item #15: Page 2+ pagination failure keeps page 1 items and recovers on retry ----

    @Test
    fun item15_seasonPaginationPage2FailurePreservesPage1AndRecoversOnRetry() = runBlocking {
        val page1Attempts = AtomicInteger(0)
        val flakySrc = object : MediaSource {
            override val source = Source("plugin:p", "P", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = when (page) {
                0 -> MediaPage(
                    items = listOf(Item("e1", ItemKind.EPISODE, "E1", index = 1), Item("e2", ItemKind.EPISODE, "E2", index = 2)),
                    nextPage = 1,
                )
                1 -> {
                    if (page1Attempts.incrementAndGet() == 1) {
                        throw SourceUnavailableException("网络抖动")
                    }
                    MediaPage(
                        items = listOf(Item("e3", ItemKind.EPISODE, "E3", index = 3)),
                        nextPage = null,
                    )
                }
                else -> MediaPage(emptyList(), null)
            }
            override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.EPISODE, itemId)
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }

        val loader = SeasonEpisodeLoader(flakySrc, "season-1")
        val st0 = loader.loadNextPage()
        assertEquals(listOf("e1", "e2"), st0.items.map { it.id })
        assertEquals(1, st0.nextPage)
        assertNull(st0.error)

        // Page 2 fails -> page 1 items remain, error is populated so trailing retry card renders, nextPage stays 1
        val stFail = loader.loadNextPage()
        assertEquals(listOf("e1", "e2"), stFail.items.map { it.id })
        assertNotNull("Page 2 failure must expose error for trailing retry card", stFail.error)
        assertEquals(1, stFail.nextPage)

        // Clicking retry card invokes loadNextPage() -> appends e3 and clears error
        val stOk = loader.loadNextPage()
        assertEquals(listOf("e1", "e2", "e3"), stOk.items.map { it.id })
        assertNull(stOk.error)
        assertNull(stOk.nextPage)
    }

    // ---- Item #16: Marking played clears Room WatchProgress; unmarking does NOT restore ----

    @Test
    fun item16_markPlayedClearsLocalRoomProgressAndUnmarkDoesNotRestore() = runBlocking {
        val dao = db.progress()
        dao.upsert(WatchProgress("plugin:jf", "movie-1", "Interstellar", 600_000L, 3_600_000L, 1_000L))

        val movie = Item(id = "movie-1", kind = ItemKind.MOVIE, title = "Interstellar", durationMs = 3_600_000L, progressMs = 600_000L, played = false)
        assertTrue(
            "Pre-condition: movie with local progress must be InProgress",
            DetailStateLogic.computeWatchState(movie, dao.get("plugin:jf", "movie-1")) is WatchState.InProgress,
        )

        // Mark as played -> deletes Room WatchProgress row so button switches from 「继续播放」 to 「播放」
        val marked = DetailStateLogic.applyPlayedToggle(
            item = movie,
            nextPlayed = true,
            setRemotePlayed = {},
            clearLocalProgress = { dao.delete("plugin:jf", "movie-1") },
        )
        assertNull("Local WatchProgress must be deleted when marked played", dao.get("plugin:jf", "movie-1"))
        assertEquals(WatchState.Watched, DetailStateLogic.computeWatchState(marked, dao.get("plugin:jf", "movie-1")))

        // Unmark played -> must NOT restore previously cleared WatchProgress
        val unmarked = DetailStateLogic.applyPlayedToggle(
            item = marked,
            nextPlayed = false,
            setRemotePlayed = {},
            clearLocalProgress = { dao.delete("plugin:jf", "movie-1") },
        )
        assertNull("Unmarking played must NOT restore local WatchProgress", dao.get("plugin:jf", "movie-1"))
        assertEquals(WatchState.Unwatched, DetailStateLogic.computeWatchState(unmarked, dao.get("plugin:jf", "movie-1")))
    }

    // ---- Item #17: Structured needsPermissionReconfirm flag instead of msg.contains("权限") ----

    @Test
    fun item17_errorDispatcherUsesStructuredPermissionFlagNotTextMatching() {
        val strings = AndroidStringLookup(app)

        // Message contains "权限" but structured flag is FALSE -> must NOT show permission reconfirm CTA
        val textOnly = SourceErrorDispatcher.dispatch(
            error = SourceNeedsSetupException("目录权限配置不完整"),
            sourceKind = SourceKind.PLUGIN,
            sourceName = "Jellyfin",
            strings = strings,
            needsPermissionReconfirm = false,
        )
        assertEquals(SourceErrorActionKind.PLUGIN_SETUP, textOnly.primary.kind)
        assertFalse(
            "Must not match '权限' text when needsPermissionReconfirm=false; got '${textOnly.primary.label}'",
            textOnly.primary.label.contains("权限"),
        )

        // Message does NOT contain "权限", structured flag is TRUE -> MUST show permission reconfirm CTA
        val structured = SourceErrorDispatcher.dispatch(
            error = SourceNeedsSetupException("插件更新后需要重新确认后再使用。"),
            sourceKind = SourceKind.PLUGIN,
            sourceName = "Jellyfin",
            strings = strings,
            needsPermissionReconfirm = true,
        )
        assertEquals(SourceErrorActionKind.PLUGIN_SETUP, structured.primary.kind)
        assertTrue(
            "Must show permission confirmation CTA when needsPermissionReconfirm=true; got '${structured.primary.label}'",
            structured.primary.label.contains("权限"),
        )
    }

    // ---- Jellyfin 1.0.1 manifest validation via real ManifestParser ----

    @Test
    fun jellyfinManifestValidatesVersionAndUdpDiscover() {
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val jfManifestText = File(repoRoot, "plugins/jellyfin/manifest.json").readText()
        val manifest = ManifestParser.parseAndValidate(jfManifestText)
        assertEquals("1.0.1", manifest.version)
        assertEquals("udp:jellyfin", manifest.config.first { it.key == "server" }.discover)
    }

    // ---- Accepted Behavior Regression #3: ItemNotFoundException deletes stale WatchProgress in real Room ----

    @Test
    fun regression03_itemNotFoundInContinueWatchingDeletesStaleRoomProgress() = runBlocking {
        val dao = db.progress()
        dao.upsert(WatchProgress("plugin:jf", "gone-item", "Deleted Film", 120_000L, 600_000L, 200L))
        dao.upsert(WatchProgress("plugin:jf", "live-item", "Live Film", 120_000L, 600_000L, 100L))

        val src = object : MediaSource {
            override val source = Source("plugin:jf", "Jellyfin", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item {
                if (itemId == "gone-item") throw ItemNotFoundException(itemId)
                return Item(itemId, ItemKind.MOVIE, "Live Film Full")
            }
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }

        val rows = listOf(dao.get("plugin:jf", "gone-item")!!, dao.get("plugin:jf", "live-item")!!)
        val finalList = ContinueWatchingResolver.resolveFlow(
            rows = rows,
            sources = listOf(src),
            onItemNotFound = { p -> dao.delete(p.sourceId, p.itemId) },
        ).toList().last()

        assertEquals(listOf("live-item"), finalList.map { it.item.id })
        assertNull("Stale WatchProgress row must be deleted from Room on ItemNotFoundException", dao.get("plugin:jf", "gone-item"))
        assertNotNull("Valid WatchProgress row must stay in Room", dao.get("plugin:jf", "live-item"))
    }

    // ---- Accepted Behavior Regression #4: WebDavSource.detail() never stalls offline ----

    @Test
    fun regression04_webDavDetailIsInstantOfflineWithoutNetworkHit() = runBlocking {
        // 192.0.2.1 (RFC 5737 TEST-NET-1) is non-routable; any real network attempt would hang/fail!
        val offlineWebDav = WebDavSource(WebDav.Server("http://192.0.2.1:9", "user", "pass"))
        val t0 = System.currentTimeMillis()
        val item = offlineWebDav.detail("/movies/2026/My Film.mp4")
        val elapsed = System.currentTimeMillis() - t0
        assertEquals("/movies/2026/My Film.mp4", item.id)
        assertEquals("My Film", item.title)
        assertTrue("WebDavSource.detail() must complete offline immediately without network I/O (${elapsed}ms)", elapsed < 200L)
    }

    // ---- Item #18 (part 2): QR/DEVICE & error/cancel exit paths always reset busy to false ----

    @Test
    fun item18_formSubmitBusyGuardResetsBusyOnQrTransitionErrorAndCancellation() = runBlocking {
        var busy = false
        // 1. Normal QR transition
        val qrResult = PluginInstanceAuthenticator.runWithBusyReset(setBusy = { busy = it }) {
            assertTrue(busy)
            "qr-step"
        }
        assertEquals("qr-step", qrResult)
        assertFalse("busy must reset after QR transition", busy)

        // 2. Auth/network failure
        try {
            PluginInstanceAuthenticator.runWithBusyReset(setBusy = { busy = it }) {
                assertTrue(busy)
                throw SourceUnavailableException("离线")
            }
            fail("Expected exception")
        } catch (_: SourceUnavailableException) {
        }
        assertFalse("busy must reset on exception", busy)

        // 3. Cancellation mid-flight
        val started = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            PluginInstanceAuthenticator.runWithBusyReset(setBusy = { busy = it }) {
                started.countDown()
                delay(10_000L)
            }
        }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertFalse("busy must reset in NonCancellable finally on cancellation", busy)
    }

    @Test
    fun round3_roomReplacePackageAtomicCascadesInstancesAndSurvivesCancellation() = runBlocking {
        val pm = newPackageManager()
        val pluginId = "dev.dreama.roomreplace"
        val repoZip = buildFormPluginZip(pluginId = pluginId, version = "1.0.0", extraMarker = "repo-orig")
        val devZip = buildFormPluginZip(pluginId = pluginId, version = "1.0.0", extraMarker = "dev-new")

        pm.installFromBytes(
            zipBytes = repoZip,
            sourceUrl = "https://repo.example.com/pkg-1.0.0.d3plugin",
            repoUrl = "https://repo.example.com/index.json",
        )
        pm.createInstance(pluginId, "Room Inst", emptyMap())
        assertEquals(1, db.pluginInstances().listForPlugin(pluginId).size)

        val jobRef = AtomicReference<kotlinx.coroutines.Job?>()
        pm.afterWipeBeforeReplaceHookForTesting = {
            jobRef.get()?.cancel()
            kotlinx.coroutines.yield()
        }
        val job = launch(Dispatchers.IO) {
            pm.installFromBytes(
                zipBytes = devZip,
                sourceUrl = "http://192.168.1.50:8787/plugin.d3plugin",
                devServerUrl = "http://192.168.1.50:8787",
                confirmReplaceFromDifferentSource = true,
            )
        }
        jobRef.set(job)
        job.join()
        pm.afterWipeBeforeReplaceHookForTesting = null

        val replaced = db.pluginPackages().get(pluginId)
        assertNotNull("Room atomic replacement must commit new package even under cancellation", replaced)
        assertTrue(PluginPackageManager.isDevOrigin(replaced!!.sourceOrigin))
        assertTrue(
            "Room ON DELETE CASCADE inside replacePackageAtomic must wipe old instances",
            db.pluginInstances().listForPlugin(pluginId).isEmpty(),
        )
        assertTrue(File(replaced.packagePath, "main.js").isFile)
        pm.shutdownAll()
    }
}
