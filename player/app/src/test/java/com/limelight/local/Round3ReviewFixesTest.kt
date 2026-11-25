package com.limelight.local

import android.app.Application
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page as MediaPage
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.Shelf
import com.limelight.local.media.Source
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.WallDatabase
import com.limelight.local.media.WatchProgress
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.PluginStore
import com.limelight.local.plugin.RoomPluginStore
import com.limelight.local.plugin.SemVer
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.DiscoveredLanService
import com.limelight.local.stream.HostDiscovery
import com.limelight.local.stream.NsdServiceBrowser
import com.limelight.local.stream.PhoneFormCopy
import com.limelight.local.stream.PhoneFormField
import com.limelight.local.stream.PhoneFormFieldKind
import com.limelight.local.stream.PhoneFormServer
import com.limelight.local.stream.PhoneSubmitResult
import com.limelight.local.stream.UdpDiscoveryProtocol
import com.limelight.local.stream.UdpLanServerDiscovery
import com.limelight.local.wall.BuiltInPluginInstaller
import com.limelight.local.wall.DetailStateLogic
import com.limelight.local.wall.PLUGIN_HOST_VERSION
import com.limelight.local.wall.PluginInstanceAuthenticator
import com.limelight.local.wall.SeriesLoaderCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Round 3 review fixes suite covering all 12 items + real Jellyfin 12.4.0 UDP reply payload.
 * Every test exercises real production paths (real Room DB, real browser pipeline, real UDP/TCP
 * sockets, real QuickJS runtime) and would fail prior to the corresponding Round 3 fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@androidx.annotation.OptIn(UnstableApi::class)
class Round3ReviewFixesTest {

    private lateinit var app: Application
    private lateinit var db: WallDatabase
    private lateinit var store: RoomPluginStore
    private lateinit var tmpDir: File
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, WallDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomPluginStore(db)
        tmpDir = File(System.getProperty("java.io.tmpdir"), "r3-fixes-${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    // ---- Item #1: Host discovery dedupes by id@addr:port and ignores no-id updates through real browser ----

    @Test
    fun r3_item01_hostDiscoveryPreservesSameIdAcrossTwoIpsAndIgnoresNoIdUpdatesThroughBrowser() {
        val hostDiscovery = HostDiscovery(app)
        val browser = hostDiscovery.browser

        // Multi-homed Mac broadcasting the SAME id on two LAN IPs -> MUST preserve BOTH endpoints!
        val k1 = browser.ingestResolvedEndpoint(
            rawName = "StudioMac",
            addr = "192.168.1.10",
            port = 47990,
            txt = mapOf("id" to "mac-uuid-1", "name" to "Studio Mac", "v" to "1"),
        )
        val k2 = browser.ingestResolvedEndpoint(
            rawName = "StudioMac",
            addr = "192.168.1.11",
            port = 47990,
            txt = mapOf("id" to "mac-uuid-1", "name" to "Studio Mac", "v" to "1"),
        )
        assertNotNull(k1)
        assertNotNull(k2)
        assertEquals(
            "Same Host id across two IPs must keep both endpoints",
            setOf("192.168.1.10", "192.168.1.11"),
            hostDiscovery.hosts.value.map { it.address }.toSet(),
        )

        // Spoofed/imposter announcement copying the same id on another IP must NOT evict real hosts
        browser.ingestResolvedEndpoint(
            rawName = "Spoofer",
            addr = "192.168.1.99",
            port = 47990,
            txt = mapOf("id" to "mac-uuid-1", "name" to "Fake", "v" to "1"),
        )
        assertEquals(3, hostDiscovery.hosts.value.size)

        // Malformed update missing TXT `id` at the same (serviceName, addr, port) MUST be rejected
        // BEFORE entering the registry so it never overwrites or wipes the live Host entry!
        val badKey = browser.ingestResolvedEndpoint(
            rawName = "StudioMac",
            addr = "192.168.1.10",
            port = 47990,
            txt = mapOf("name" to "Corrupt Update Without ID"),
        )
        assertNull("Host update with fromTxt==null must be rejected before entering registry", badKey)
        assertTrue(
            "Existing Host at 192.168.1.10 must remain untouched after no-id update",
            hostDiscovery.hosts.value.any { it.address == "192.168.1.10" && it.name == "Studio Mac" },
        )

        // Meanwhile, plugin mDNS browser DOES deduplicate by serviceType#id across two NICs
        val pluginBrowser = NsdServiceBrowser(app, "_jellyfin._tcp")
        pluginBrowser.ingestResolvedEndpoint("JF", "192.168.1.20", 8096, mapOf("id" to "jf-same-id"))
        pluginBrowser.ingestResolvedEndpoint("JF", "192.168.1.21", 8096, mapOf("id" to "jf-same-id"))
        assertEquals("Plugin mDNS deduplicates same id across two NICs", 1, pluginBrowser.services.value.size)
    }

    // ---- Item #2: mDNS ignores untrusted TXT `address`, enforces private IP, and displays exact prefill URL ----

    @Test
    fun r3_item02_mdnsIgnoresUntrustedTxtAddressRejectsPublicIpAndMatchesDisplayUrl() {
        // Malicious mDNS responder sets TXT address to an attacker's public URL
        val maliciousMdns = DiscoveredLanService(
            serviceType = "_jellyfin._tcp",
            serviceName = "Fake Jellyfin",
            address = "192.168.1.40",
            port = 8096,
            txt = mapOf("address" to "https://evil.attacker.example/steal", "id" to "spoof-1"),
        )
        assertEquals(
            "mDNS must NEVER trust TXT address and must build URL from resolved IP:port",
            "http://192.168.1.40:8096",
            maliciousMdns.suggestedUrl(),
        )

        // Plugin mDNS browser must reject public IPs and accept private RFC1918 LAN IPs
        val pluginBrowser = NsdServiceBrowser(app, "_jellyfin._tcp")
        assertNull(
            "Plugin mDNS browser must reject public IPv4 address",
            pluginBrowser.ingestResolvedEndpoint("PublicBox", "93.184.216.34", 8096, emptyMap()),
        )
        assertTrue(pluginBrowser.services.value.isEmpty())

        assertNotNull(
            "Plugin mDNS browser must accept private LAN IPv4 address",
            pluginBrowser.ingestResolvedEndpoint(
                "NasJellyfin",
                "192.168.1.40",
                8096,
                mapOf("address" to "https://evil.attacker.example/steal"),
            ),
        )
        assertEquals(1, pluginBrowser.services.value.size)
        assertEquals("http://192.168.1.40:8096", pluginBrowser.services.value.first().suggestedUrl())

        // UDP discovery retains validated private LAN Address, while rejecting any non-private address
        val validUdp = DiscoveredLanService(
            serviceType = "udp:jellyfin",
            serviceName = "My JF",
            address = "192.168.1.36",
            port = 8096,
            txt = mapOf("id" to "abc", "address" to "http://192.168.1.36:8096/jellyfin"),
        )
        assertEquals("http://192.168.1.36:8096/jellyfin", validUdp.suggestedUrl())

        val spoofedUdp = validUdp.copy(txt = mapOf("id" to "abc", "address" to "https://8.8.8.8:8096"))
        assertEquals("http://192.168.1.36:8096", spoofedUdp.suggestedUrl())
    }

    // ---- Item #3: Concurrent loader & cross-season resolution race + cancellation leaves loading=false ----

    @Test
    fun r3_item03_concurrentSeriesLoadAndResolveReturnsS2E1AndCancelNeverLeavesLoadingTrue() = runBlocking {
        val seasons = listOf(
            Item("s1", ItemKind.SEASON, "第 1 季", index = 1),
            Item("s2", ItemKind.SEASON, "第 2 季", index = 2),
        )
        val s1Eps = listOf(
            Item("s1e1", ItemKind.EPISODE, "S1E1", index = 1, parentId = "s1", durationMs = 1_000_000L),
            Item("s1e2", ItemKind.EPISODE, "S1E2", index = 2, parentId = "s1", durationMs = 1_000_000L),
        )
        val s2Eps = listOf(
            Item("s2e1", ItemKind.EPISODE, "S2E1", index = 1, parentId = "s2", durationMs = 1_000_000L),
        )

        val s1Entered = CompletableDeferred<Unit>()
        val releaseS1 = CompletableDeferred<Unit>()
        val s1BrowseCount = AtomicInteger(0)
        val s2BrowseCount = AtomicInteger(0)

        val gatedSource = object : MediaSource {
            override val source = Source("plugin:race", "RaceJF", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = when (categoryId) {
                "s1" -> {
                    s1BrowseCount.incrementAndGet()
                    s1Entered.complete(Unit)
                    releaseS1.await()
                    MediaPage(s1Eps, null)
                }
                "s2" -> {
                    s2BrowseCount.incrementAndGet()
                    delay(20L)
                    MediaPage(s2Eps, null)
                }
                else -> MediaPage(emptyList(), null)
            }
            override suspend fun detail(itemId: String): Item =
                (s1Eps + s2Eps).firstOrNull { it.id == itemId } ?: throw ItemNotFoundException(itemId)
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }

        val coordinator = SeriesLoaderCoordinator(gatedSource)
        val s1Loader = coordinator.loaderFor("s1", hasSeasons = true, loadingDetail = false, preloadedEpisodes = emptyList())!!
        val progress = mapOf(
            // Finished last episode of S1 -> must roll over to S2E1 even under concurrent load!
            "s1e2" to WatchProgress("plugin:race", "s1e2", "S1E2", 980_000L, 1_000_000L, 500L, parentId = "s1"),
        )

        // Effect 2 starts loading S1 first and suspends inside browse("s1", 0)
        val effect2 = async { s1Loader.ensureLoaded() }
        s1Entered.await()

        // Effect 1 concurrently runs resolveInitialSeriesSeasonAndTarget sharing the SAME coordinator/loader!
        // Before Fix #3, Effect 1 saw loading==true, received emptyList(), returned target=null, and fell back to S1!
        val effect1 = async {
            DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
                source = gatedSource,
                seasons = seasons,
                localProgress = progress,
                loadSeasonEpisodes = { sid ->
                    val l = coordinator.loaderFor(sid, hasSeasons = true, loadingDetail = false, preloadedEpisodes = emptyList())!!
                    l.ensureLoaded().items
                },
            )
        }
        delay(30L)
        releaseS1.complete(Unit)

        val e2State = effect2.await()
        val resolved = effect1.await()!!

        assertEquals(2, e2State.items.size)
        assertFalse("Effect 2 must not leave loading=true", e2State.loading)
        assertEquals("Concurrent calls must coalesce into 1 browse for S1", 1, s1BrowseCount.get())
        assertEquals(1, s2BrowseCount.get())
        assertEquals("s2", resolved.seasonId)
        assertEquals("s2e1", resolved.target?.episode?.id)
        assertTrue(resolved.target?.isResume == true)

        // Cancellation part: cancel an in-flight loader and verify loading resets cleanly to false
        val hangGate = CompletableDeferred<Unit>()
        val hangStarted = CompletableDeferred<Unit>()
        val hangSrc = object : MediaSource {
            override val source = Source("plugin:hang", "Hang", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> {
                hangStarted.complete(Unit)
                hangGate.await()
                return MediaPage(emptyList(), null)
            }
            override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.EPISODE, itemId)
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val cancelCoord = SeriesLoaderCoordinator(hangSrc)
        val cancelLoader = cancelCoord.loaderFor("s-hang", hasSeasons = true, loadingDetail = false, preloadedEpisodes = emptyList())!!
        val pageStates = HashMap<String, com.limelight.local.wall.SeasonEpisodePageState>()
        val cancelJob = launch {
            pageStates["s-hang"] = cancelLoader.state.copy(loading = true, error = null)
            try {
                cancelLoader.ensureLoaded()
            } finally {
                pageStates["s-hang"] = cancelLoader.state
            }
        }
        hangStarted.await()
        cancelJob.cancelAndJoin()
        assertFalse("Cancelled loader must not remain loading=true", cancelLoader.state.loading)
        assertFalse("pageStates updated in finally must not remain loading=true", pageStates["s-hang"]!!.loading)
    }

    // ---- Item #4: Built-in sync self-heals corrupted DB version & missing directory WITHOUT writing fake version ----

    @Test
    fun r3_item04_builtInInstallerSelfHealsCorruptedVersionAndMissingDirWithoutFakeVersion() = runBlocking {
        val writtenVersions = CopyOnWriteArrayList<String>()
        val spyStore = object : PluginStore by store {
            override suspend fun upsertPackage(entity: InstalledPluginEntity) {
                writtenVersions += entity.version
                store.upsertPackage(entity)
            }
        }
        val pm = PluginPackageManager(
            rootDir = File(tmpDir, "plugins-r3"),
            store = spyStore,
            secretBox = SoftwareAesSecretBox(),
            appVersion = PLUGIN_HOST_VERSION,
            deviceInfo = DeviceInfo.detect(PLUGIN_HOST_VERSION),
        )
        val blobA = buildSimpleBuiltInZip("dev.depth3d.heal", "1.0.0", "marker-A")
        val blobB = buildSimpleBuiltInZip("dev.depth3d.heal", "1.0.0", "marker-B")

        // 1. Initial install + create a user instance that MUST survive every repair/refresh
        val r1 = BuiltInPluginInstaller.syncPackages(pm, spyStore, listOf(blobA))
        assertEquals(listOf("dev.depth3d.heal"), r1.installed)
        val inst = pm.createInstance(
            pluginId = "dev.depth3d.heal",
            displayName = "Keep Me",
            config = mapOf("server" to JsonPrimitive("http://192.168.1.50:8096")),
        )

        // 2. Simulate DB leftover with unparseable bad version ("refreshing") -> next launch self-heals!
        val current = store.getPackage("dev.depth3d.heal")!!
        store.upsertPackage(current.copy(version = "refreshing"))
        writtenVersions.clear()

        val rHealVer = BuiltInPluginInstaller.syncPackages(pm, spyStore, listOf(blobA))
        assertEquals("Corrupted unparseable DB version must self-heal", listOf("dev.depth3d.heal"), rHealVer.upgraded)
        assertEquals("1.0.0", store.getPackage("dev.depth3d.heal")!!.version)
        assertNotNull("User instance must survive DB version self-heal", store.getInstance(inst.instanceId))

        // 3. Simulate missing package directory on disk while DB has matching version + SHA -> next launch self-heals!
        val pkgDir = File(store.getPackage("dev.depth3d.heal")!!.packagePath)
        assertTrue(pkgDir.deleteRecursively())
        assertFalse(pkgDir.exists())
        writtenVersions.clear()

        val rHealDir = BuiltInPluginInstaller.syncPackages(pm, spyStore, listOf(blobA))
        assertEquals("Missing package directory must self-heal", listOf("dev.depth3d.heal"), rHealDir.upgraded)
        val restoredPkg = store.getPackage("dev.depth3d.heal")!!
        assertTrue("Restored manifest.json must exist", File(restoredPkg.packagePath, "manifest.json").isFile)
        assertTrue("Restored main.js must exist", File(restoredPkg.packagePath, "main.js").isFile)
        assertNotNull("User instance must survive dir self-heal", store.getInstance(inst.instanceId))

        // 4. Same-version SHA-256 update -> MUST NEVER write any fake/unparseable version string to DB!
        writtenVersions.clear()
        val rSameVerSha = BuiltInPluginInstaller.syncPackages(pm, spyStore, listOf(blobB))
        assertEquals(listOf("dev.depth3d.heal"), rSameVerSha.upgraded)
        assertTrue("Expected at least one upsert", writtenVersions.isNotEmpty())
        assertTrue(
            "Every DB upsert during same-version refresh must be a valid SemVer, got: $writtenVersions",
            writtenVersions.all { SemVer.parse(it) != null },
        )
        val finalPkg = store.getPackage("dev.depth3d.heal")!!
        assertTrue(File(finalPkg.packagePath, "main.js").readText().contains("marker-B"))
        assertNotNull("User instance must survive same-version SHA refresh", store.getInstance(inst.instanceId))
    }

    // ---- Item #5: Unwatched series sends ZERO detail() & ZERO S2..SN browse(); hits WatchProgress.parentId + 2s timeout + Room v2->v3 ----

    @Test
    fun r3_item05_unwatchedSeriesDoesNotAmplifyRequestsAndFallsBackToFirstSeasonOnTimeout() = runBlocking {
        val seasons = listOf(
            Item("s1", ItemKind.SEASON, "第 1 季", index = 1),
            Item("s2", ItemKind.SEASON, "第 2 季", index = 2),
            Item("s3", ItemKind.SEASON, "第 3 季", index = 3),
            Item("s4", ItemKind.SEASON, "第 4 季", index = 4),
        )
        val detailCalls = AtomicInteger(0)
        val browsedSeasons = CopyOnWriteArrayList<String>()
        val unwatchedSrc = object : MediaSource {
            override val source = Source("plugin:jf", "JF", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> {
                browsedSeasons += categoryId
                return MediaPage(
                    listOf(
                        Item("${categoryId}e1", ItemKind.EPISODE, "E1", index = 1, parentId = categoryId, durationMs = 1_000_000L),
                    ),
                    null,
                )
            }
            override suspend fun detail(itemId: String): Item {
                detailCalls.incrementAndGet()
                return Item(itemId, ItemKind.MOVIE, "Unrelated $itemId", parentId = "unrelated-library")
            }
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val unrelatedProgress = (1..20).associate { i ->
            "other-$i" to WatchProgress(
                "plugin:jf",
                "other-$i",
                "Other $i",
                120_000L,
                600_000L,
                1000L - i,
                parentId = if (i % 2 == 0) "unrelated-season" else null,
            )
        }

        // 1. Unwatched series with 20 unrelated local progress items -> ZERO detail() calls and ONLY S1 browsed!
        val rUnwatched = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = unwatchedSrc,
            seasons = seasons,
            localProgress = unrelatedProgress,
            loadSeasonEpisodes = { sid -> unwatchedSrc.browse(sid, 0).items },
        )!!
        assertEquals("s1", rUnwatched.seasonId)
        assertEquals("Unwatched series must issue ZERO detail() probes", 0, detailCalls.get())
        assertEquals("Unwatched series must ONLY browse default season S1", listOf("s1"), browsedSeasons)

        // 2. Local WatchProgress with parentId="s3" matches directly with ZERO detail() calls and browses ONLY S3!
        browsedSeasons.clear()
        val withHit = unrelatedProgress + (
            "s3e1" to WatchProgress("plugin:jf", "s3e1", "S3E1", 250_000L, 1_000_000L, 2_000L, parentId = "s3")
            )
        val rMatched = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = unwatchedSrc,
            seasons = seasons,
            localProgress = withHit,
            loadSeasonEpisodes = { sid -> unwatchedSrc.browse(sid, 0).items },
        )!!
        assertEquals("s3", rMatched.seasonId)
        assertEquals("s3e1", rMatched.target?.episode?.id)
        assertEquals(0, detailCalls.get())
        assertEquals(listOf("s3"), browsedSeasons)

        // 3. Verify PluginPlayerLaunch round-trips EXTRA_PARENT_ID for WatchProgress persistence
        val epIntent = PluginPlayerLaunch.createIntent(
            context = app,
            instanceId = "inst-1",
            sourceId = "plugin:inst-1",
            itemId = "s3e1",
            title = "S3E1",
            parentId = "s3",
        )
        assertEquals("s3", PluginPlayerLaunch.parseIntent(epIntent)?.parentId)

        // 4. Overall 2s timeout fallback: hung season browse hits timeout and falls back to S1 cleanly!
        val t0 = System.currentTimeMillis()
        val rTimeout = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = unwatchedSrc,
            seasons = seasons,
            localProgress = withHit,
            loadSeasonEpisodes = {
                delay(5_000L)
                emptyList()
            },
            timeoutMs = 200L,
        )!!
        val elapsed = System.currentTimeMillis() - t0
        assertEquals("Timeout must fall back to default first regular season", "s1", rTimeout.seasonId)
        assertTrue("Resolution must enforce overall timeout (took ${elapsed}ms)", elapsed < 1_000L)

        // 5. Real Room v2 -> v3 migration preserves existing watch_progress rows and adds nullable parentId column
        val migDbName = "wall-mig-2-3-${System.nanoTime()}.db"
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            WallDatabase::class.java,
        )
        helper.createDatabase(migDbName, 2).apply {
            execSQL(
                "INSERT INTO watch_progress (sourceId, itemId, title, positionMs, durationMs, updatedAt) " +
                    "VALUES ('plugin:jf', 'legacy-ep', 'Legacy Ep', 60000, 600000, 1700000000000)",
            )
            close()
        }
        helper.runMigrationsAndValidate(migDbName, 3, true, WallDatabase.MIGRATION_2_3).use { migrated ->
            migrated.query("SELECT title, parentId FROM watch_progress WHERE itemId = 'legacy-ep'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Legacy Ep", c.getString(0))
                assertTrue(c.isNull(1))
            }
            migrated.execSQL(
                "INSERT INTO watch_progress (sourceId, itemId, title, positionMs, durationMs, updatedAt, parentId) " +
                    "VALUES ('plugin:jf', 's2e1', 'S2E1', 90000, 600000, 1700000001000, 's2')",
            )
            migrated.query("SELECT parentId FROM watch_progress WHERE itemId = 's2e1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("s2", c.getString(0))
            }
        }
    }

    // ---- Item #6: Short-TTL (<=60s) plugin stops proactive refresh, keeps playing, still refreshes on HTTP 403 ----

    @Test
    fun r3_item06_shortTtlPluginStopsProactiveRefreshKeepsPlayingAndStillRecoversOnHttp403() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("ok")
        }
        val baseUrl = "http://192.168.1.50:${server.port}"
        val nowMs = AtomicLong(1_000_000L)

        // Plugin always issues valid URLs with 45s TTL (valid now, inside 60s lead window!)
        val shortTtlZip = buildPlaybackZip(
            pluginId = "com.example.shortttl45",
            playbackJs = """
                const n = ((await ctx.storage.get('n')) || 0) + 1;
                await ctx.storage.set('n', n);
                return {
                  url: ctx.config.server + '/short-' + n + '.mp4',
                  expiresAt: ${nowMs.get() + 45_000L},
                  session: 'short-sess-' + n
                };
            """.trimIndent(),
        )
        val pm = PluginPackageManager(
            rootDir = File(tmpDir, "pm-short"),
            store = InMemoryPluginStore(),
            secretBox = SoftwareAesSecretBox(),
            appVersion = PLUGIN_HOST_VERSION,
            deviceInfo = DeviceInfo.detect(PLUGIN_HOST_VERSION),
        )
        pm.installFromBytes(shortTtlZip, sourceUrl = "builtin:shortttl45")
        val inst = pm.createInstance("com.example.shortttl45", "Short45", mapOf("server" to JsonPrimitive(baseUrl)))
        val session = PluginPlayerSession(
            openPluginSource = { id -> pm.openSource(id) { listOf(InetAddress.getByName("192.168.1.50")) } },
            clockMs = { nowMs.get() },
        )
        try {
            val loaded = session.load(inst.instanceId, "ep-1", 0L, 600_000L)
            assertEquals("short-sess-1", loaded.sessionId)
            assertFalse("Valid <=60s short-TTL URL must NOT spin proactive refresh", session.shouldProactiveRefresh())

            // When playback actually hits HTTP 403, error refresh MUST succeed & continue playing (NOT throw!)
            assertTrue("HTTP 403 error refresh must stay enabled for short-TTL streams", session.canAttemptAutoRefresh(403))
            val refreshed = session.refreshIfExpiredOrFailed(currentPositionMs = 25_000L, httpStatusCode = 403)
            assertNotNull("Short-TTL refresh must return playable media instead of aborting", refreshed)
            assertEquals("short-sess-2", refreshed!!.sessionId)
            assertEquals(25_000L, refreshed.resumePositionMs)
            assertFalse("Proactive refresh stays disabled after short-TTL refresh", session.shouldProactiveRefresh())

            // Playback becomes ready -> error counter resets so a later real HTTP 401 still recovers
            session.markPlaybackReady()
            assertTrue(session.canAttemptAutoRefresh(401))
            val refreshed2 = session.refreshIfExpiredOrFailed(currentPositionMs = 50_000L, httpStatusCode = 401)
            assertEquals("short-sess-3", refreshed2!!.sessionId)
        } finally {
            session.close()
        }
    }

    // ---- Item #7: PhoneFormServer runs onAcceptedFlushed even if client disconnects + single-use token dedupe ----

    @Test
    fun r3_item07_phoneFormRunsAcceptedCallbackWhenSocketDropsAndPreventsDuplicateSubmit() {
        val copy = PhoneFormCopy.from(app, "Jellyfin")
        val fields = listOf(
            PhoneFormField("server", PhoneFormFieldKind.URL, "Server", required = true),
            PhoneFormField("username", PhoneFormFieldKind.TEXT, "Username", required = true),
        )

        // Part A: client disconnects immediately after sending POST (forcing writeHtml IOException) ->
        // onAcceptedFlushed MUST still fire in finally so headset navigates!
        val acceptedLatch = CountDownLatch(1)
        val submitEntered = CountDownLatch(1)
        val srvDrop = PhoneFormServer(
            copy = copy,
            fields = fields,
            displayHost = "127.0.0.1",
            code = "2222",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onAcceptedFlushed = { acceptedLatch.countDown() },
            onSubmitted = {
                submitEntered.countDown()
                Thread.sleep(120L) // give client time to close socket before writeHtml
                PhoneSubmitResult.Accepted
            },
        ).start()
        try {
            val body = "token=${srvDrop.token}&f_server=" + URLEncoder.encode("http://192.168.1.36:8096", "UTF-8") + "&f_username=alice"
            val req = "POST /submit-qr HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body"
            Socket("127.0.0.1", srvDrop.boundPort).use { raw ->
                raw.getOutputStream().write(req.toByteArray(StandardCharsets.UTF_8))
                raw.getOutputStream().flush()
                assertTrue("Server must receive submission", submitEntered.await(3, TimeUnit.SECONDS))
                // Abruptly close socket BEFORE server writes 200 HTML!
            }
            assertTrue(
                "onAcceptedFlushed must still run in finally even when phone drops socket",
                acceptedLatch.await(3, TimeUnit.SECONDS),
            )
        } finally {
            srvDrop.close()
        }

        // Part B: same form token submitted twice -> onSubmitted called EXACTLY ONCE (no duplicate instances!)
        val submitCalls = AtomicInteger(0)
        val srvIdemp = PhoneFormServer(
            copy = copy,
            fields = fields,
            displayHost = "127.0.0.1",
            code = "3333",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onSubmitted = {
                submitCalls.incrementAndGet()
                PhoneSubmitResult.Accepted
            },
        ).start()
        try {
            val body = "token=${srvIdemp.token}&f_server=" + URLEncoder.encode("http://192.168.1.36:8096", "UTF-8") + "&f_username=alice"
            val (s1, h1) = httpPost("${srvIdemp.shortUrl}/submit-qr", body)
            val (s2, h2) = httpPost("${srvIdemp.shortUrl}/submit-qr", body)
            assertEquals(200, s1)
            assertEquals(200, s2)
            assertTrue(h1.contains(copy.doneTitle))
            assertTrue(h2.contains(copy.doneTitle))
            assertEquals("Same form token must accept only ONE successful submission", 1, submitCalls.get())
        } finally {
            srvIdemp.close()
        }
    }

    // ---- Item #8 + Real Jellyfin 12.4.0 UDP payload: exact payload parse, 32-server cap, cancel race safety ----

    @Test
    fun r3_item08_udpDiscoveryParsesRealJellyfin124PayloadCapsAt32AndHandlesCancelRaceSafely() = runBlocking {
        // 1. Exact real Jellyfin 12.4.0 UDP 7359 response payload (with EndpointAddress: null)
        val realJellyfin124Payload =
            """{"Address":"http://192.168.1.36:8096","Id":"a1b2c3d4e5f60718293a4b5c6d7e8f90","Name":"9f842982031c","EndpointAddress":null}"""
        val parsed = UdpLanServerDiscovery.parseResponsePayload(
            protocol = UdpDiscoveryProtocol.JELLYFIN,
            payload = realJellyfin124Payload,
        )
        assertNotNull("Real Jellyfin 12.4.0 UDP payload with EndpointAddress:null must parse cleanly", parsed)
        assertEquals("9f842982031c", parsed!!.serviceName)
        assertEquals("192.168.1.36", parsed.address)
        assertEquals(8096, parsed.port)
        assertEquals("http://192.168.1.36:8096", parsed.suggestedUrl())
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90", parsed.txt["id"])

        // 2. Cap discovered servers at MAX_DISCOVERED_SERVERS (32) when flooded with 45 unique replies
        val floodServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val floodPort = floodServer.localPort
        val running = AtomicBoolean(true)
        val thread = Thread {
            val buf = ByteArray(512)
            floodServer.soTimeout = 200
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    floodServer.receive(pkt)
                } catch (_: Exception) {
                    continue
                }
                for (i in 1..45) {
                    val reply = """{"Address":"http://192.168.1.${10 + i}:8096","Id":"server-id-$i","Name":"JF $i","EndpointAddress":null}"""
                    val b = reply.toByteArray(StandardCharsets.UTF_8)
                    floodServer.send(DatagramPacket(b, b.size, pkt.address, pkt.port))
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val found = UdpLanServerDiscovery.discover(
                protocol = UdpDiscoveryProtocol.JELLYFIN,
                timeoutMs = 500L,
                targetAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                targetPort = floodPort,
            )
            assertEquals(
                "UDP discovery must cap foundById at ${UdpLanServerDiscovery.MAX_DISCOVERED_SERVERS}",
                UdpLanServerDiscovery.MAX_DISCOVERED_SERVERS,
                found.size,
            )
        } finally {
            running.set(false)
            floodServer.close()
            thread.join(1_000L)
        }

        // 3. Rapid cancellation stress loop must never leak SocketException
        repeat(10) {
            val job = launch {
                UdpLanServerDiscovery.discover(
                    protocol = UdpDiscoveryProtocol.JELLYFIN,
                    timeoutMs = 2_000L,
                    targetAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                    targetPort = floodPort + 1,
                )
            }
            delay(15L)
            job.cancelAndJoin()
        }
    }

    // ---- Item #9: QR / DEVICE edit rollback restores config, displayName, AND authState/authUserJson ----

    @Test
    fun r3_item09_qrDeviceEditRollbackRestoresConfigDisplayNameAndAuthStateInRoom() = runBlocking {
        val pm = PluginPackageManager(
            rootDir = File(tmpDir, "pm-qr"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = PLUGIN_HOST_VERSION,
            deviceInfo = DeviceInfo.detect(PLUGIN_HOST_VERSION),
        )
        pm.installFromBytes(buildSimpleBuiltInZip("dev.depth3d.qrtest", "1.0.0", "qr", auth = "qr"), sourceUrl = "builtin:qrtest")
        val origCfg = mapOf("server" to JsonPrimitive("http://192.168.1.36:8096"))
        val inst = pm.createInstance("dev.depth3d.qrtest", "客厅 QR", origCfg)
        store.updateInstanceAuth(inst.instanceId, "AUTHENTICATED", """{"name":"alice"}""", System.currentTimeMillis())

        val beforeEdit = store.getInstance(inst.instanceId)!!
        val snapshot = PluginInstanceAuthenticator.EditRollbackSnapshot(
            instanceId = inst.instanceId,
            prevDisplayName = beforeEdit.displayName,
            prevConfig = pm.getInstanceConfig(inst.instanceId),
            prevAuthState = beforeEdit.authState,
            prevAuthUserJson = beforeEdit.authUserJson,
        )

        // Simulate QR edit mutating config + displayName + dropping authState before loginQrStart fails
        pm.updateInstanceConfig(inst.instanceId, "坏名字", mapOf("server" to JsonPrimitive("http://192.168.1.99:8096")))
        store.updateInstanceAuth(inst.instanceId, "NEEDS_AUTH", null, System.currentTimeMillis())

        // Rollback via shared helper used by PluginFormScreen QR/DEVICE catch
        PluginInstanceAuthenticator.restoreEditSnapshot(
            packageManager = pm,
            store = store,
            snapshot = snapshot,
        )

        val restored = store.getInstance(inst.instanceId)!!
        assertEquals("客厅 QR", restored.displayName)
        assertEquals("AUTHENTICATED", restored.authState)
        assertEquals("""{"name":"alice"}""", restored.authUserJson)
        assertEquals(JsonPrimitive("http://192.168.1.36:8096"), pm.getInstanceConfig(inst.instanceId)["server"])
    }

    // ---- Item #10: Specials (index == 0) sorted last, labeled "特别篇", never default unless sole season ----

    @Test
    fun r3_item10_specialsSeasonZeroSortsLastLabelsAsSpecialsAndNotDefaultUnlessSoleSeason() = runBlocking {
        val strings = AndroidStringLookup(app)
        val rawSeasons = listOf(
            Item("s0", ItemKind.SEASON, "Specials", index = 0),
            Item("s1", ItemKind.SEASON, "Season 1", index = 1),
            Item("s2", ItemKind.SEASON, "Season 2", index = 2),
        )
        val ordered = DetailStateLogic.orderSeasons(rawSeasons)
        assertEquals(listOf("s1", "s2", "s0"), ordered.map { it.id })
        assertEquals("特别篇", DetailStateLogic.formatSeasonTabLabel(ordered, 2, strings))
        assertEquals(0, DetailStateLogic.seasonNumberFor(ordered, 2))
        assertEquals(1, DetailStateLogic.seasonNumberFor(ordered, 0))
        assertEquals(2, DetailStateLogic.regularSeasonCount(ordered))

        val epsBySeason = mapOf(
            "s0" to listOf(Item("sp1", ItemKind.EPISODE, "OVA 1", index = 1, parentId = "s0", durationMs = 1_000_000L)),
            "s1" to listOf(Item("s1e1", ItemKind.EPISODE, "S1E1", index = 1, parentId = "s1", durationMs = 1_000_000L)),
            "s2" to listOf(Item("s2e1", ItemKind.EPISODE, "S2E1", index = 1, parentId = "s2", durationMs = 1_000_000L)),
        )
        val src = object : MediaSource {
            override val source = Source("plugin:sp", "JF", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> =
                MediaPage(epsBySeason[categoryId].orEmpty(), null)
            override suspend fun detail(itemId: String): Item =
                epsBySeason.values.flatten().first { it.id == itemId }
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }

        // 1. Unwatched series with [Specials, S1, S2] -> default season MUST be S1 (NOT Specials!)
        val rDefault = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = src,
            seasons = rawSeasons,
            localProgress = emptyMap(),
            loadSeasonEpisodes = { epsBySeason[it].orEmpty() },
        )!!
        assertEquals("s1", rDefault.seasonId)
        assertEquals("s1e1", rDefault.target!!.episode.id)
        assertEquals("播放 · 第 1 季第 1 集", DetailStateLogic.formatPrimarySeriesButton(rDefault.target!!, true, strings))

        // 2. Finishing S2's last episode must NOT roll into Specials; falls back to S1E1
        val rAfterS2 = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = src,
            seasons = rawSeasons,
            localProgress = mapOf("s2e1" to WatchProgress("plugin:sp", "s2e1", "S2E1", 980_000L, 1_000_000L, 500L, parentId = "s2")),
            loadSeasonEpisodes = { epsBySeason[it].orEmpty() },
        )!!
        assertEquals("s1", rAfterS2.seasonId)
        assertEquals("s1e1", rAfterS2.target!!.episode.id)
        assertFalse(rAfterS2.target!!.isResume)

        // 3. Specials-only series -> selects Specials and button says "播放 · 特别篇第 1 集" (NOT "第 1 季"!)
        val rSpecialsOnly = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = src,
            seasons = listOf(rawSeasons.first()),
            localProgress = emptyMap(),
            loadSeasonEpisodes = { epsBySeason[it].orEmpty() },
        )!!
        assertEquals("s0", rSpecialsOnly.seasonId)
        assertEquals(0, rSpecialsOnly.seasonNumber)
        assertEquals("播放 · 特别篇第 1 集", DetailStateLogic.formatPrimarySeriesButton(rSpecialsOnly.target!!, true, strings))
    }

    // ---- Item #11: Real Intent resolution path rejects non-zero EXTRA_CAST_ID on plugin Intent ----

    @Test
    fun r3_item11_pluginIntentWithNonZeroCastIdIsRejectedByRealIntentResolutionPath() {
        val pluginIntentWithCast = PluginPlayerLaunch.createIntent(
            context = app,
            instanceId = "inst-1",
            sourceId = "plugin:inst-1",
            itemId = "ep-1",
            title = "Episode 1",
        ).putExtra(LocalPlayerXR.EXTRA_CAST_ID, 77L)

        try {
            LocalPlayerXR.resolvePluginIntentOrThrow(pluginIntentWithCast)
            fail("Plugin intent carrying non-zero EXTRA_CAST_ID must throw UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // expected
        }

        // Normal plugin intent without castId passes cleanly
        val normalPluginIntent = PluginPlayerLaunch.createIntent(
            context = app,
            instanceId = "inst-1",
            sourceId = "plugin:inst-1",
            itemId = "ep-1",
            title = "Episode 1",
        )
        val parsed = LocalPlayerXR.resolvePluginIntentOrThrow(normalPluginIntent)
        assertNotNull(parsed)
        assertEquals("ep-1", parsed!!.itemId)

        // Non-plugin cast intent passes cleanly (returns null plugin args)
        val nonPluginCastIntent = Intent(app, LocalPlayerXR::class.java)
            .putExtra(LocalPlayerXR.EXTRA_CAST_ID, 77L)
        assertNull(LocalPlayerXR.resolvePluginIntentOrThrow(nonPluginCastIntent))
    }

    // ---- Item #12: PluginPlayerSession passes needsPermissionReconfirm to SourceErrorDispatcher ----

    @Test
    fun r3_item12_playerErrorFormattingPropagatesNeedsPermissionReconfirmAndDisablesRetry() = runBlocking {
        val strings = AndroidStringLookup(app)

        // Blank message + needsPermissionReconfirm=true -> surfaces permission reconfirm message and disables retry
        val permUi = PluginPlayerSession.formatPlayerError(
            error = SourceNeedsSetupException(""),
            sourceKind = SourceKind.PLUGIN,
            sourceName = "Jellyfin",
            strings = strings,
            needsPermissionReconfirm = true,
        )
        assertTrue("Expected permission reconfirm message, got '${permUi.reason}'", permUi.reason.contains("权限"))
        assertFalse("Permission reconfirm player error must not offer futile Retry", permUi.canRetry)

        // Same blank message + needsPermissionReconfirm=false -> normal setup error
        val setupUi = PluginPlayerSession.formatPlayerError(
            error = SourceNeedsSetupException(""),
            sourceKind = SourceKind.PLUGIN,
            sourceName = "Jellyfin",
            strings = strings,
            needsPermissionReconfirm = false,
        )
        assertFalse("Normal setup message must not claim permission reconfirm", setupUi.reason.contains("权限"))
        assertTrue(setupUi.canRetry)

        // Session populates needsPermissionReconfirm even when load() fails due to permission gate
        val session = PluginPlayerSession(
            openPluginSource = { throw SourceNeedsSetupException("需要处理") },
            checkNeedsPermissionReconfirm = { true },
        )
        try {
            session.load("inst-1", "m1", 0L, null)
            fail("Expected SourceNeedsSetupException")
        } catch (_: SourceNeedsSetupException) {
            assertTrue("Session must record needsPermissionReconfirm=true before openSource failure", session.needsPermissionReconfirm)
        } finally {
            session.close()
        }
    }

    // ---- Helpers ----

    private fun httpPost(url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return status to text
    }

    private fun buildSimpleBuiltInZip(
        pluginId: String,
        version: String,
        marker: String,
        auth: String = "none",
    ): ByteArray {
        val manifest = """
            {
              "api": 1,
              "id": "$pluginId",
              "name": "Test $pluginId",
              "version": "$version",
              "permissions": { "hosts": [], "userHosts": true, "lan": true },
              "config": [{ "key": "server", "type": "url", "label": "Server", "required": true }],
              "auth": "$auth"
            }
        """.trimIndent()
        val mainJs = """
            // $marker
            export default {
              async home() { return []; },
              async browse() { return { items: [], nextPage: null }; },
              async detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: itemId }; },
              async playback(ctx, { itemId }) { return { url: ctx.config.server + '/' + itemId + '.mp4' }; },
              async loginStart() { return { qrUrl: 'https://example.com/qr', state: 'st' }; },
              async loginPoll() { return { status: 'pending' }; }
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

    private fun buildPlaybackZip(pluginId: String, playbackJs: String): ByteArray {
        val manifest = """
            {
              "api": 1,
              "id": "$pluginId",
              "name": "Playback Test",
              "version": "1.0.0",
              "permissions": { "hosts": [], "userHosts": true, "lan": true },
              "config": [{ "key": "server", "type": "url", "label": "Server", "required": true }],
              "auth": "none"
            }
        """.trimIndent()
        val mainJs = """
            export default {
              async home() { return []; },
              async browse() { return { items: [], nextPage: null }; },
              async detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: itemId }; },
              async playback(ctx, { itemId }) { $playbackJs }
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
}
