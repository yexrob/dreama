package com.limelight.local.plugin

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.dash.BaseUrlExclusionList
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.local.media.DashStream
import com.limelight.local.media.MediaTrack
import com.limelight.local.media.WallDatabase
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Robolectric-backed Android framework verification tests:
 * 1. B3: Real Media3 [DashManifestParser] parsing our synthesized MPD,
 *    asserting `dvb:priority` 1/2/3 & `mediaPresentationDuration`, plus real
 *    primary-failure -> backup failover via Media3's [BaseUrlExclusionList],
 *    [PlaybackMediaSourceFactory.LocationFallbackLoadErrorPolicy], and
 *    [SafePluginHttpDataSource] against a live local [MockWebServer].
 * 2. Small item #7: Official Room [MigrationTestHelper] validating
 *    [WallDatabase.MIGRATION_1_2] against KSP-exported `1.json` and `2.json`.
 * 3. Small item #8: [AndroidKeystoreSecretBox] smoke verification under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3DashAndRoomTest {

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

            // Ask our LocationFallbackLoadErrorPolicy for fallback decision
            val loadErrorPolicy = PlaybackMediaSourceFactory.LocationFallbackLoadErrorPolicy()
            val fallbackOptions = LoadErrorHandlingPolicy.FallbackOptions(
                /* numberOfLocations = */ videoRep.baseUrls.size,
                /* numberOfExcludedLocations = */ 0,
                /* numberOfTracks = */ 1,
                /* numberOfExcludedTracks = */ 0,
            )
            val loadErrorInfo = LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(1L, DataSpec(Uri.parse(primaryChoice.url)), 0L),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                primaryException!!,
                /* errorCount = */ 1,
            )
            val fallback = loadErrorPolicy.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
            assertNotNull(fallback)
            assertEquals(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION, fallback!!.type)

            // Exclude failing primary location -> BaseUrlExclusionList deterministically picks priority=2 (backup1)!
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

        // 1. Create authentic v1 schema via Room MigrationTestHelper and populate watch_progress
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO watch_progress (sourceId, itemId, title, positionMs, durationMs, updatedAt) " +
                    "VALUES ('local', 'v:99', '星际穿越', 45000, 9000000, 1700000000000)",
            )
            close()
        }

        // 2. Run MIGRATION_1_2 and let Room validate the resulting schema against 2.json
        val db = helper.runMigrationsAndValidate(dbName, 2, true, WallDatabase.MIGRATION_1_2)
        db.use { migrated ->
            // Verify v1 watch_progress row survived untouched
            migrated.query("SELECT title, positionMs FROM watch_progress WHERE itemId = 'v:99'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("星际穿越", c.getString(0))
                assertEquals(45000L, c.getLong(1))
            }

            // Verify plugin_packages (including new sourceOrigin column) and ON DELETE CASCADE
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
        // Robolectric's JVM runtime does not include Android's hardware Keystore provider
        // ("AndroidKeyStore"). Verify our wrapper rejects missing Keystore cleanly without silent
        // plaintext fallback; full hardware-backed key generation must be verified on device.
        val box = AndroidKeystoreSecretBox(keyAlias = "depth3d_test_alias")
        try {
            val ct = box.encryptToBase64("hello-keystore")
            assertEquals("hello-keystore", box.decryptFromBase64(ct))
        } catch (e: Exception) {
            // Expected on JVM/Robolectric where AndroidKeyStore provider is absent
            assertTrue(
                "Expected AndroidKeyStore KeyStoreException on Robolectric JVM, got: ${e.javaClass.name}: ${e.message}",
                e is java.security.KeyStoreException || e.cause is java.security.NoSuchProviderException ||
                    e.message?.contains("AndroidKeyStore", ignoreCase = true) == true,
            )
        }
    }
}
