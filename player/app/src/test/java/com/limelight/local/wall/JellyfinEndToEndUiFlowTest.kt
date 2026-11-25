package com.limelight.local.wall

import androidx.media3.common.util.UnstableApi
import com.limelight.local.PluginPlayerSession
import com.limelight.local.media.ItemKind
import com.limelight.local.media.Shelf
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.LocalRedirectSocketFactory
import com.limelight.local.plugin.ManifestAndPermissionTest
import com.limelight.local.plugin.PluginLocale
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.XmlStringLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end JVM integration test exercising the complete Task D UI pipeline with the real
 * built-in Jellyfin plugin (`plugins/jellyfin`) and real recorded Jellyfin 12.4.0 Docker fixtures:
 *   Built-in sync -> Localized manifest -> Config form validation -> Transactional create + login ->
 *   WallPluginSourceManager reconcile & home() -> Movie & Series detail + lazy season episodes ->
 *   PluginPlayerSession playback() -> Progress reporting (start / progress / pause / stop).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JellyfinEndToEndUiFlowTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val strings = XmlStringLookup

    private fun packBuiltInJellyfin(repoRoot: File): ByteArray {
        val generated = File(
            repoRoot,
            "player/app/build/generated/assets/builtin-plugins/builtin-plugins/dev.dreama.jellyfin.d3plugin",
        )
        if (generated.isFile) return generated.readBytes()
        val dir = File(repoRoot, "plugins/jellyfin")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun add(rel: String, f: File) {
                zos.putNextEntry(ZipEntry(rel))
                zos.write(f.readBytes())
                zos.closeEntry()
            }
            add("manifest.json", File(dir, "manifest.json"))
            add("main.js", File(dir, "main.js"))
            File(dir, "locales").listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.forEach { add("locales/${it.name}", it) }
        }
        return out.toByteArray()
    }

    @Test
    fun fullJellyfinFlowFromBuiltInInstallThroughWallDetailPlaybackAndProgress() = runBlocking {
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val fixtures = json.parseToJsonElement(
            File(repoRoot, "plugins/jellyfin/fixtures/recorded-responses.json").readText(),
        ).jsonObject
        val ids = fixtures["_meta"]!!.jsonObject["ids"]!!.jsonObject
        val userId = ids["userId"]!!.jsonPrimitive.content
        val movieId = ids["h264MovieId"]!!.jsonPrimitive.content
        val seriesId = ids["seriesId"]!!.jsonPrimitive.content
        val seasonId = ids["seasonId"]!!.jsonPrimitive.content

        val progressEndpoints = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                fun okJson(body: String) = MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(body)

                return when {
                    path == "/System/Info/Public" -> okJson(fixtures["systemInfoPublic"]!!.toString())
                    path == "/Users/AuthenticateByName" -> okJson(fixtures["authSuccess"]!!.toString())
                    path == "/UserViews" || path == "/Users/$userId/Views" -> okJson(fixtures["views"]!!.toString())
                    path == "/UserItems/Resume" || path == "/Users/$userId/Items/Resume" -> okJson(fixtures["resume"]!!.toString())
                    path == "/Shows/NextUp" -> okJson(fixtures["nextUp"]!!.toString())
                    path == "/Items/Latest" || path == "/Users/$userId/Items/Latest" -> {
                        val pid = url.queryParameter("ParentId").orEmpty()
                        okJson((fixtures["latestByParent"]!!.jsonObject[pid] ?: json.parseToJsonElement("[]")).toString())
                    }
                    path == "/Items" || path == "/Users/$userId/Items" -> {
                        val pid = url.queryParameter("ParentId").orEmpty()
                        val byParent = fixtures["browseByParent"]!!.jsonObject[pid]
                        okJson(byParent?.toString() ?: """{"Items":[],"TotalRecordCount":0,"StartIndex":0}""")
                    }
                    path == "/Items/$movieId" || path == "/Users/$userId/Items/$movieId" ->
                        okJson(fixtures["itemsById"]!!.jsonObject[movieId]!!.toString())
                    path == "/Items/$seriesId" || path == "/Users/$userId/Items/$seriesId" ->
                        okJson(fixtures["itemsById"]!!.jsonObject[seriesId]!!.toString())
                    path == "/Items/$seasonId" || path == "/Users/$userId/Items/$seasonId" ->
                        okJson(fixtures["itemsById"]!!.jsonObject[seasonId]!!.toString())
                    path == "/Items/$movieId/Similar" || path == "/Items/$seriesId/Similar" ->
                        okJson(fixtures["similar"]!!.toString())
                    path == "/Shows/$seriesId/Seasons" -> okJson(fixtures["seriesSeasons"]!!.toString())
                    path == "/Shows/$seriesId/Episodes" -> okJson(fixtures["seasonEpisodes"]!!.toString())
                    path == "/Items/$movieId/PlaybackInfo" ->
                        okJson(fixtures["playbackInfo"]!!.jsonObject["$movieId:direct"]!!.toString())
                    path.startsWith("/Sessions/Playing") -> {
                        progressEndpoints += "${request.method} $path"
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val dns: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("192.168.1.50")) }
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("plugins-e2e"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            okHttpClient = okHttp,
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )

        try {
            // 1. Built-in Jellyfin silent install
            val sync = BuiltInPluginInstaller.syncPackages(pm, store, listOf(packBuiltInJellyfin(repoRoot)))
            assertEquals(listOf("dev.dreama.jellyfin"), sync.installed)
            val pkg = store.getPackage("dev.dreama.jellyfin")!!
            val rawManifest = com.limelight.local.plugin.ManifestParser.parseAndValidate(pkg.manifestJson)
            val localizedManifest = PluginLocale.fromPackageDir(File(pkg.packagePath), "zh-CN").localizeManifest(rawManifest)
            assertFalse("Localized manifest name must not contain raw \$t: keys", localizedManifest.name.startsWith("\$t:"))

            // 2. Dynamic form validation + transactional create & login
            val formValues = PluginConfigFormCodec.initialValues(
                manifest = localizedManifest,
                prefillServerUrl = "http://192.168.1.50:${server.port}",
                suggestedName = "客厅 Jellyfin",
            ) + mapOf("username" to "d3tester", "password" to "pass123")
            val validForm = PluginConfigFormCodec.validateAndSerialize(localizedManifest, formValues, strings) as ConfigFormResult.Valid
            val inst = PluginInstanceAuthenticator.createAndAuthenticateFormOrNone(
                packageManager = pm,
                manifest = localizedManifest,
                displayName = validForm.displayName,
                config = validForm.config,
                dnsResolver = dns,
            )
            assertEquals("客厅 Jellyfin", inst.displayName)
            assertEquals("AUTHENTICATED", store.getInstance(inst.instanceId)!!.authState)

            // 3. WallPluginSourceManager reconciles & loads home() shelves
            val wallManager = WallPluginSourceManager(
                scope = this,
                openSource = { id -> pm.openSource(id, dnsResolver = dns) },
                ioDispatcher = Dispatchers.IO,
            )
            val reconciled = wallManager.reconcile(store.listPackages(), store.listInstances())
            assertEquals(1, reconciled.sources.size)
            val jfSource = reconciled.sources.first() as PluginMediaSource
            val shelves = ConcurrentHashMap<String, Load<List<Shelf>>>()
            wallManager.refreshSource(jfSource, shelves).join()
            val readyHome = shelves[jfSource.source.id] as? Load.Ready<List<Shelf>>
            assertNotNull("Expected home shelves Ready, got ${shelves[jfSource.source.id]}", readyHome)
            assertTrue("Expected non-empty Jellyfin home shelves", readyHome!!.value.any { it.items.isNotEmpty() })

            // 4. Movie detail + metadata formatting
            val movieDetail = jfSource.detail(movieId)
            assertEquals(ItemKind.MOVIE, movieDetail.kind)
            val movieMeta = DetailStateLogic.formatMovieMeta(movieDetail, jfSource.source.name, strings)
            assertTrue(movieMeta.contains("客厅 Jellyfin"))

            // 5. Series detail + lazy season episode loader (with detail(seasonId) fallback) + smart next episode
            val seriesDetail = jfSource.detail(seriesId)
            assertTrue(DetailStateLogic.isSeriesLayout(seriesDetail))
            assertTrue("Expected at least one season", seriesDetail.seasons.isNotEmpty())
            val seasonLoader = SeasonEpisodeLoader(jfSource, seriesDetail.seasons.first().id)
            val epPage = seasonLoader.loadNextPage()
            assertTrue("Expected episodes from Jellyfin season", epPage.items.isNotEmpty())
            val target = DetailStateLogic.pickPlayTarget(1, epPage.items, emptyMap())
            assertNotNull(target)

            // 6. Playback via PluginPlayerSession (through PluginPlayback.createSessionController) + progress reporting
            val clock = AtomicLong(System.currentTimeMillis())
            val playerSession = PluginPlayerSession(
                openPluginSource = { id -> pm.openSource(id, dnsResolver = dns) },
                clockMs = { clock.get() },
                progressIntervalMs = 10_000L,
                ioDispatcher = Dispatchers.IO,
                callbackDispatcher = Dispatchers.IO,
            )
            val loaded = playerSession.load(
                instanceId = inst.instanceId,
                itemId = movieId,
                resumePositionMs = 12_000L,
                fallbackDurationMs = movieDetail.durationMs,
            )
            assertNotNull(loaded.mediaSource)
            assertEquals(12_000L, loaded.resumePositionMs)

            assertTrue(playerSession.reportStart(12_000L, loaded.durationMs ?: 60_000L))
            clock.addAndGet(11_000L)
            assertTrue(playerSession.reportPeriodicIfDue(23_000L, loaded.durationMs ?: 60_000L))
            assertTrue(playerSession.reportPause(23_000L, loaded.durationMs ?: 60_000L))
            assertTrue(playerSession.reportStop(23_000L, loaded.durationMs ?: 60_000L))

            assertTrue("Expected /Sessions/Playing start report", progressEndpoints.any { it == "POST /Sessions/Playing" })
            assertTrue("Expected /Sessions/Playing/Progress report", progressEndpoints.any { it == "POST /Sessions/Playing/Progress" })
            assertTrue("Expected /Sessions/Playing/Stopped report", progressEndpoints.any { it == "POST /Sessions/Playing/Stopped" })

            playerSession.close()
            wallManager.cancelAll()
        } finally {
            pm.shutdownAll()
            server.shutdown()
        }
    }
}
