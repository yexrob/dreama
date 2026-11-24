package com.limelight.local.plugin

import androidx.sqlite.db.SupportSQLiteDatabase
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.WallDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.Proxy as JavaProxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests:
 * 1. Real SQLite execution of Room [WallDatabase.MIGRATION_1_2] verified against `1.json` & `2.json`
 *    (plus official Room `MigrationTestHelper` in [Media3DashAndRoomTest])
 * 2. S1 (§11.4): `(pluginId, sourceOrigin)` identity — same id from different source requires explicit
 *    confirmation and wipes all existing instances' configs/passwords, storage, and cookies
 * 3. S1 & §11.4: HTTPS-only repository enforcement, no private/loopback redirects, 8 MB package cap
 * 4. S9 & §11.4: Zip Central Directory vs Local File Header consistency (rejects dual-view & duplicate zips)
 * 5. Small #3: Concurrent `openSource` calls serialize per instance and create only ONE runtime
 * 6. Full [PluginPackageManager] lifecycle
 */
class PackageManagerAndMigrationTest {

    private val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun migratesWallDatabaseFromV1ToV2OnRealSqlitePreservingWatchProgress() {
        val schemasDir = File(repoRoot, "player/app/schemas/com.limelight.local.media.WallDatabase")
        val v1Schema = json.parseToJsonElement(File(schemasDir, "1.json").readText()).jsonObject
        val v2Schema = json.parseToJsonElement(File(schemasDir, "2.json").readText()).jsonObject

        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().execute("PRAGMA foreign_keys = ON")
            val v1Entities = v1Schema["database"]!!.jsonObject["entities"]!!.jsonArray
            for (entityEl in v1Entities) {
                val e = entityEl.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                val sql = e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                conn.createStatement().execute(sql)
            }
            conn.createStatement().execute(
                "INSERT INTO watch_progress VALUES ('local', 'v:42', '沙丘', 120000, 9000000, 1700000000000)",
            )

            val supportDb = jdbcSupportDbProxy(conn)
            WallDatabase.MIGRATION_1_2.migrate(supportDb)

            conn.createStatement().executeQuery("SELECT title, positionMs FROM watch_progress WHERE itemId = 'v:42'").use { rs ->
                assertTrue(rs.next())
                assertEquals("沙丘", rs.getString(1))
                assertEquals(120000L, rs.getLong(2))
            }

            val v2Entities = v2Schema["database"]!!.jsonObject["entities"]!!.jsonArray
            for (entityEl in v2Entities) {
                val e = entityEl.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                val expectedCols = e["fields"]!!.jsonArray.associate { f ->
                    val fo = f.jsonObject
                    fo["columnName"]!!.jsonPrimitive.content to Pair(
                        fo["affinity"]!!.jsonPrimitive.content,
                        (fo["notNull"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                }
                val actualCols = LinkedHashMap<String, Pair<String, Boolean>>()
                conn.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    while (rs.next()) {
                        actualCols[rs.getString("name")] = Pair(rs.getString("type"), rs.getInt("notnull") == 1)
                    }
                }
                assertEquals("Columns mismatch for table $table", expectedCols, actualCols)
            }

            conn.createStatement().execute(
                "INSERT INTO plugin_packages VALUES ('com.test.p','P','1.0.0','A','D','{}','/tmp',NULL,NULL,'local-file','abc',1,0,'{}',1,1)",
            )
            conn.createStatement().execute(
                "INSERT INTO plugin_instances VALUES ('inst-1','com.test.p','My P','enc',1,'NONE',NULL,1,1)",
            )
            conn.createStatement().execute("DELETE FROM plugin_packages WHERE pluginId = 'com.test.p'")
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM plugin_instances").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1))
            }
        }
    }

    @Test
    fun repositoryClientEnforcesHttpsNoPrivateRedirects8MbCapAndSha256Match() = runBlocking {
        val validZip = buildPluginZip(
            manifestJson = """{"api":1,"id":"com.example.repoplug","name":"Repo Plugin","version":"1.2.0","author":"Tester"}""",
            mainJs = "export default { home(){return []}, browse(){return {items:[]}}, detail(){return {id:'1',kind:'video',title:'1'}}, playback(){return {url:'https://cdn.example.com/1.mp4'}} };",
        )
        val validSha = PluginRepositoryClient.sha256Hex(validZip)
        val wrongIdZip = buildPluginZip(
            manifestJson = """{"api":1,"id":"com.example.other","name":"Other","version":"1.2.0","author":"Tester"}""",
            mainJs = "export default {};",
        )
        val wrongIdSha = PluginRepositoryClient.sha256Hex(wrongIdZip)

        // 1. Default §11.4 public repository policy rejects plain HTTP and private/loopback URLs
        val strictClient = PluginRepositoryClient()
        assertEquals(8 * 1024 * 1024, PluginRepositoryClient.MAX_PACKAGE_BYTES)
        try {
            strictClient.fetchIndex("http://repo.example.com/index.json")
            fail("Plain HTTP repo URL MUST be rejected under §11.4")
        } catch (_: RepositoryVerificationException) {
        }
        try {
            strictClient.fetchIndex("https://192.168.1.10/index.json")
            fail("Private IP repo URL MUST be rejected under §11.4")
        } catch (_: RepositoryVerificationException) {
        }

        // 2. Live MockWebServer verification: valid package, tampered SHA-256, manifest id mismatch, private redirect, >8MB
        val server = MockWebServer()
        val fakePublicHost = "93.184.216.34"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/index.json" -> MockResponse().setBody(
                    """
                    {
                      "name": "测试插件仓库",
                      "plugins": [
                        {
                          "id": "com.example.repoplug",
                          "name": "Repo Plugin",
                          "version": "1.2.0",
                          "url": "http://$fakePublicHost:${server.port}/valid.d3plugin",
                          "sha256": "$validSha"
                        },
                        {
                          "id": "com.example.repoplug",
                          "name": "Tampered",
                          "version": "1.2.0",
                          "url": "http://$fakePublicHost:${server.port}/valid.d3plugin",
                          "sha256": "${"0".repeat(64)}"
                        },
                        {
                          "id": "com.example.repoplug",
                          "name": "MismatchedId",
                          "version": "1.2.0",
                          "url": "http://$fakePublicHost:${server.port}/wrong-id.d3plugin",
                          "sha256": "$wrongIdSha"
                        },
                        {
                          "id": "com.example.repoplug",
                          "name": "PrivateRedirect",
                          "version": "1.2.0",
                          "url": "http://$fakePublicHost:${server.port}/redir-private",
                          "sha256": "$validSha"
                        },
                        {
                          "id": "com.example.repoplug",
                          "name": "Oversized",
                          "version": "1.2.0",
                          "url": "http://$fakePublicHost:${server.port}/oversized.d3plugin",
                          "sha256": "$validSha"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                "/valid.d3plugin" -> MockResponse().setBody(Buffer().write(validZip))
                "/wrong-id.d3plugin" -> MockResponse().setBody(Buffer().write(wrongIdZip))
                "/redir-private" -> MockResponse().setResponseCode(302)
                    .addHeader("Location", "http://192.168.1.50:${server.port}/valid.d3plugin")
                "/oversized.d3plugin" -> MockResponse().setBody(Buffer().write(ByteArray(8 * 1024 * 1024 + 1024)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            // Test-injected policy allowing HTTP only for our synthetic MockWebServer host while still blocking private/loopback IPs
            val testPolicy = NetworkPolicy(
                permissions = PluginPermissions(hosts = emptyList(), userHosts = false, lan = false),
                dnsResolver = { host -> listOf(InetAddress.getByName(host)) },
                requireHttpsAlways = false,
            )
            val testHttp = OkHttpClient.Builder()
                .proxy(JavaProxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory(fakePublicHost, server.port))
                .build()
            val client = PluginRepositoryClient(httpClient = testHttp, publicPolicy = testPolicy)
            val index = client.fetchIndex("http://$fakePublicHost:${server.port}/index.json")
            assertEquals("测试插件仓库", index.name)
            assertEquals(5, index.plugins.size)

            val ok = client.downloadVerifiedPackage(index.plugins[0])
            assertEquals(validSha, ok.sha256)

            for (badIdx in 1..4) {
                try {
                    client.downloadVerifiedPackage(index.plugins[badIdx])
                    fail("Expected entry #$badIdx (${index.plugins[badIdx].name}) to fail verification")
                } catch (_: RepositoryVerificationException) {
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsDualViewAndDuplicateZipArchivesS9() {
        val goodZip = buildPluginZip(
            manifestJson = """{"api":1,"id":"com.example.zip","name":"Zip","version":"1.0.0"}""",
            mainJs = "export default { home(){return []}, browse(){return {items:[]}}, detail(){return {id:'1',kind:'video',title:'1'}}, playback(){return {url:'https://cdn.example.com/1.mp4'}} };",
        )
        // Baseline passes
        assertEquals("com.example.zip", ManifestParser.parseAndValidate(PluginPackageArchive.inspect(goodZip).manifestJson).id)

        // 1. Tamper Central Directory filename so it disagrees with Local File Header (dual-view attack)
        val tampered = goodZip.copyOf()
        val cdSigIdx = findBytes(tampered, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        assertTrue("Expected Central Directory signature in zip", cdSigIdx > 0)
        tampered[cdSigIdx + 46] = if (tampered[cdSigIdx + 46] == 'm'.code.toByte()) 'x'.code.toByte() else 'm'.code.toByte()
        try {
            PluginPackageArchive.inspect(tampered)
            fail("Expected CD-vs-LFH filename mismatch to be rejected")
        } catch (_: PackageInstallException) {
        }

        // 2. Duplicate entry (two manifest.json entries) rejected
        val dupZip = buildDuplicateManifestZip()
        try {
            PluginPackageArchive.inspect(dupZip)
            fail("Expected duplicate manifest.json zip to be rejected")
        } catch (_: PackageInstallException) {
        }

        // 3. Oversized > 8 MB archive rejected
        try {
            PluginPackageArchive.inspect(ByteArray(8 * 1024 * 1024 + 64))
            fail("Expected > 8 MB package to be rejected")
        } catch (_: PackageInstallException) {
        }
    }

    @Test
    fun s1BlocksDifferentSourceReplacementWithoutConfirmationAndWipesAllInstanceDataWhenConfirmed() = runBlocking {
        val rootDir = Files.createTempDirectory("d3-s1-test").toFile()
        val store = InMemoryPluginStore()
        val secretBox = SoftwareAesSecretBox()
        val mgr = PluginPackageManager(rootDir = rootDir, store = store, secretBox = secretBox)

        try {
            val v1Js = """
                export default {
                  async home(ctx) {
                    await ctx.storage.set('secretToken', 'tok-from-repo-a');
                    await ctx.cookies.set('https://api.example.com/', 'sid=cookie-from-repo-a; Path=/; Secure');
                    return [{ id: 's1', title: ctx.config.pass || 'none', items: [] }];
                  },
                  browse() { return { items: [] }; },
                  detail() { return { id: '1', kind: 'video', title: '1' }; },
                  playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
                };
            """.trimIndent()
            val v1Zip = buildPluginZip(
                """{"api":1,"id":"com.example.jellyfin","name":"JF Official","version":"1.0.0","permissions":{"hosts":["api.example.com"]},"config":[{"key":"pass","type":"password","label":"密码"}]}""",
                v1Js,
            )
            val v2EvilZip = buildPluginZip(
                """{"api":1,"id":"com.example.jellyfin","name":"JF Hijack","version":"9.9.9","permissions":{"hosts":["evil.example.org"]},"config":[{"key":"pass","type":"password","label":"密码"}]}""",
                v1Js,
            )

            // 1. Install v1.0.0 from Official Repo A and populate instance password, storage, cookies
            val r1 = mgr.installFromBytes(
                zipBytes = v1Zip,
                sourceUrl = "https://repo-a.example.com/jf-1.0.0.d3plugin",
                repoUrl = "https://repo-a.example.com/index.json",
            )
            assertEquals("repo:https://repo-a.example.com/index.json", r1.entity.sourceOrigin)
            val inst = mgr.createInstance(
                pluginId = "com.example.jellyfin",
                displayName = "My JF",
                config = mapOf("pass" to JsonPrimitive("SuperSecretPassword")),
            )
            val src = mgr.openSource(inst.instanceId)
            assertEquals("SuperSecretPassword", src.home().first().category.title)
            val instDir = File(rootDir, "instances/${inst.instanceId}")
            assertTrue(File(instDir, "storage.enc").exists())
            assertTrue(File(instDir, "cookies.enc").exists())

            // 2. S1: Same id + higher version from DIFFERENT Repo B MUST fail without explicit confirmation
            try {
                mgr.installFromBytes(
                    zipBytes = v2EvilZip,
                    sourceUrl = "https://repo-b.example.org/jf-9.9.9.d3plugin",
                    repoUrl = "https://repo-b.example.org/index.json",
                )
                fail("S1: Different-source package MUST be rejected without confirmation")
            } catch (e: PluginSourceMismatchException) {
                assertEquals("com.example.jellyfin", e.pluginId)
                assertEquals("repo:https://repo-a.example.com/index.json", e.existingSourceOrigin)
                assertEquals("repo:https://repo-b.example.org/index.json", e.newSourceOrigin)
            }
            // Existing plugin, instance, password config, storage, and cookies MUST still be intact!
            assertEquals("1.0.0", store.getPackage("com.example.jellyfin")?.version)
            assertNotNull(store.getInstance(inst.instanceId))
            assertTrue(File(instDir, "storage.enc").exists())

            // 3. S1: Same id from local file (different source) ALSO blocked without confirmation
            try {
                mgr.installFromBytes(zipBytes = v2EvilZip, sourceUrl = "file:///sdcard/jf-9.9.9.d3plugin")
                fail("S1: Local file replacing repo plugin MUST require confirmation")
            } catch (_: PluginSourceMismatchException) {
            }

            // 4. S1: Confirmed replacement MUST wipe ALL existing instances, passwords, storage, and cookies!
            val replaced = mgr.installFromBytes(
                zipBytes = v2EvilZip,
                sourceUrl = "https://repo-b.example.org/jf-9.9.9.d3plugin",
                repoUrl = "https://repo-b.example.org/index.json",
                confirmReplaceFromDifferentSource = true,
            )
            assertFalse("Replacement from different source is NOT an update", replaced.isUpdate)
            assertTrue(replaced.replacedDifferentSource)
            assertEquals("9.9.9", replaced.entity.version)
            assertEquals("repo:https://repo-b.example.org/index.json", replaced.entity.sourceOrigin)
            assertTrue(
                "All previous instances MUST be deleted on different-source replacement",
                store.listInstancesForPlugin("com.example.jellyfin").isEmpty(),
            )
            assertFalse("Old instance storage/cookies dir MUST be wiped from disk", instDir.exists())
        } finally {
            mgr.shutdownAll()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun packageManagerHandlesLifecycleAndConcurrentOpenSourceWithoutRace() = runBlocking {
        val rootDir = Files.createTempDirectory("d3-pkgmgr-test").toFile()
        val store = InMemoryPluginStore()
        val secretBox = SoftwareAesSecretBox()
        val mgr = PluginPackageManager(
            rootDir = rootDir,
            store = store,
            secretBox = secretBox,
            appVersion = "1.5.0",
        )
        try {
            val minimalJs = """
                export default {
                  home(ctx) { return [{ id: 's1', title: ctx.config.label || 'v1', items: [] }]; },
                  browse() { return { items: [] }; },
                  detail() { return { id: '1', kind: 'video', title: '1' }; },
                  playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
                };
            """.trimIndent()

            // Zip-slip rejected
            val evilZip = buildRawZip(mapOf("../escape.txt" to "bad".toByteArray()))
            try {
                mgr.installFromBytes(evilZip)
                fail("Expected zip-slip rejection")
            } catch (_: PackageInstallException) {
            }

            // minApp > appVersion rejected
            val futureZip = buildPluginZip(
                """{"api":1,"id":"com.example.future","name":"Future","version":"1.0.0","author":"A","minApp":"2.0.0"}""",
                minimalJs,
            )
            try {
                mgr.installFromBytes(futureZip)
                fail("Expected minApp check to reject")
            } catch (_: PackageInstallException) {
            }

            // Install v1.0.0 (multiInstance=false)
            val v1Zip = buildPluginZip(
                """
                {
                  "api": 1,
                  "id": "com.example.app",
                  "name": "App",
                  "version": "1.0.0",
                  "author": "A",
                  "multiInstance": false,
                  "permissions": { "hosts": ["api.example.com"], "lan": false },
                  "config": [
                    { "key": "label", "type": "text", "label": "名称", "default": "默认名" },
                    { "key": "pass", "type": "password", "label": "密码" }
                  ]
                }
                """.trimIndent(),
                minimalJs,
            )
            val sameRepo = "https://repo.example.com/index.json"
            val r1 = mgr.installFromBytes(v1Zip, repoUrl = sameRepo)
            assertFalse(r1.isUpdate)
            assertFalse(r1.needsPermissionReconfirm)

            val inst = mgr.createInstance(
                pluginId = "com.example.app",
                displayName = "实例一",
                config = mapOf("pass" to JsonPrimitive("MySecretPw123")),
            )
            assertFalse(inst.encryptedConfig.contains("MySecretPw123"))
            val decrypted = mgr.getInstanceConfig(inst.instanceId)
            assertEquals("默认名", decrypted["label"]?.jsonPrimitive?.content)
            assertEquals("MySecretPw123", decrypted["pass"]?.jsonPrimitive?.content)

            try {
                mgr.createInstance("com.example.app", "实例二")
                fail("multiInstance=false must reject second instance")
            } catch (_: PackageInstallException) {
            }

            // Small #3: 10 concurrent openSource calls MUST serialize and return the exact same instance
            val concurrentSources = (1..10).map {
                async(Dispatchers.Default) { mgr.openSource(inst.instanceId) }
            }.awaitAll()
            val first = concurrentSources.first()
            for (other in concurrentSources) {
                assertSame("Concurrent openSource must not instantiate duplicate PluginMediaSource", first, other)
            }
            assertEquals("默认名", first.home().first().category.title)

            // Same-source downgrade / same version rejected
            try {
                mgr.installFromBytes(v1Zip, repoUrl = sameRepo)
                fail("Same version update must be rejected")
            } catch (_: PackageInstallException) {
            }

            // Same-source upgrade v1.1.0 with EXPANDED permissions -> needsPermissionReconfirm
            val v2Zip = buildPluginZip(
                """
                {
                  "api": 1,
                  "id": "com.example.app",
                  "name": "App",
                  "version": "1.1.0",
                  "author": "A",
                  "multiInstance": false,
                  "permissions": { "hosts": ["api.example.com"], "lan": true },
                  "config": [{ "key": "label", "type": "text", "label": "名称", "default": "默认名" }]
                }
                """.trimIndent(),
                minimalJs,
            )
            val r2 = mgr.installFromBytes(v2Zip, repoUrl = sameRepo)
            assertTrue(r2.isUpdate)
            assertTrue(r2.needsPermissionReconfirm)

            val src2 = mgr.openSource(inst.instanceId)
            try {
                src2.home()
                fail("Expected SourceNeedsSetupException while awaiting permission confirmation")
            } catch (_: SourceNeedsSetupException) {
            }

            mgr.confirmPermissions("com.example.app")
            assertEquals("默认名", src2.home().first().category.title)

            mgr.setPluginEnabled("com.example.app", false)
            try {
                mgr.openSource(inst.instanceId).home()
                fail("Disabled plugin must reject calls")
            } catch (_: SourceNeedsSetupException) {
            }
            mgr.setPluginEnabled("com.example.app", true)
            assertEquals("默认名", mgr.openSource(inst.instanceId).home().first().category.title)

            mgr.uninstall("com.example.app")
            assertNull(store.getPackage("com.example.app"))
            assertTrue(store.listInstancesForPlugin("com.example.app").isEmpty())
            assertFalse(File(rootDir, "packages/com.example.app").exists())
            assertFalse(File(rootDir, "instances/${inst.instanceId}").exists())
        } finally {
            mgr.shutdownAll()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun passesAllSharedZipSecurityVectorsIncludingDualZipPoc() {
        // Item #1: exact same zip-vectors.json (including 407-byte dual.zip PoC and 5 variants)
        // verified identically on Android PluginPackageArchive.inspect and Node inspectArchiveBuffer
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val raw = File(repoRoot, "docs/specs/plugin-schema/zip-vectors.json").readText()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val cases = json.parseToJsonElement(raw).let { it as kotlinx.serialization.json.JsonObject }["cases"]!!
            .let { it as kotlinx.serialization.json.JsonArray }
        assertTrue(cases.size >= 8)

        for (el in cases) {
            val obj = el as kotlinx.serialization.json.JsonObject
            val name = (obj["name"] as JsonPrimitive).content
            val valid = (obj["valid"] as JsonPrimitive).content.toBoolean()
            val bytes = PluginCrypto.base64Decode((obj["base64"] as JsonPrimitive).content)
            if (valid) {
                val archive = PluginPackageArchive.inspect(bytes)
                val expectedPrefix = (obj["expectedMainJsPrefix"] as? JsonPrimitive)?.content
                if (expectedPrefix != null) {
                    assertTrue("Valid zip '$name' mainJs mismatch", archive.mainJs.startsWith(expectedPrefix))
                }
            } else {
                try {
                    PluginPackageArchive.inspect(bytes)
                    fail("Crafted malicious zip vector '$name' MUST be rejected by PluginPackageArchive.inspect")
                } catch (_: PackageInstallException) {
                }
            }
        }
    }

    @Test
    fun localFileReinstallAlwaysReplacesUrlUsesFullPathAndPreviewFlagsWildcardLan(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("d3-origin-preview").toFile()
        val store = InMemoryPluginStore()
        val secretBox = SoftwareAesSecretBox()
        val mgr = PluginPackageManager(rootDir = rootDir, store = store, secretBox = secretBox)
        val minimalJs = """
            export default {
              async home(ctx) {
                await ctx.storage.set('secret', 'from-old-file');
                return [];
              },
              browse() { return { items: [] }; },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
            };
        """.trimIndent()
        val v1Zip = buildPluginZip(
            """{"api":1,"id":"com.example.localid","name":"Local","version":"1.0.0","permissions":{"hosts":["*.home.arpa"],"lan":true}}""",
            minimalJs,
        )
        val v2Zip = buildPluginZip(
            """{"api":1,"id":"com.example.localid","name":"Local","version":"2.0.0","permissions":{"hosts":["nas.home.arpa"],"lan":true}}""",
            minimalJs,
        )

        try {
            // Item #12: previewPackage sets allowsWildcardLanAccess=true + explicit Chinese warning for lan:true + wildcard hosts
            val previewWild = mgr.previewPackage(v1Zip, sourceUrl = "file:///sdcard/a.d3plugin")
            assertTrue(previewWild.allowsWildcardLanAccess)
            assertTrue(previewWild.permissionWarnings.contains(PluginPackageManager.WILDCARD_LAN_WARNING))

            val previewExact = mgr.previewPackage(v2Zip, sourceUrl = "file:///sdcard/b.d3plugin")
            assertFalse(previewExact.allowsWildcardLanAccess)
            assertFalse(previewExact.permissionWarnings.contains(PluginPackageManager.WILDCARD_LAN_WARNING))

            // Item #6a: Installing same id from a local file ALWAYS requires replacement confirmation and wipes data!
            mgr.installFromBytes(v1Zip, sourceUrl = "file:///sdcard/a.d3plugin")
            val inst = mgr.createInstance("com.example.localid", "Inst")
            mgr.openSource(inst.instanceId).home()
            assertEquals(1, store.listInstancesForPlugin("com.example.localid").size)

            val replacePreview = mgr.previewPackage(v2Zip, sourceUrl = "file:///sdcard/b.d3plugin")
            assertTrue("Local file install over same id must flag requiresReplacementConfirmation", replacePreview.requiresReplacementConfirmation)
            assertFalse(replacePreview.isUpdate)

            try {
                mgr.installFromBytes(v2Zip, sourceUrl = "file:///sdcard/b.d3plugin")
                fail("Local file install over same id must throw PluginSourceMismatchException without confirmation")
            } catch (_: PluginSourceMismatchException) {
            }

            val replaced = mgr.installFromBytes(
                v2Zip,
                sourceUrl = "file:///sdcard/b.d3plugin",
                confirmReplaceFromDifferentSource = true,
            )
            assertTrue(replaced.replacedDifferentSource)
            assertFalse(replaced.isUpdate)
            assertTrue("Replacement from local file must wipe old instances & data", store.listInstancesForPlugin("com.example.localid").isEmpty())

            // Item #6b: Direct URL installs identify by full URL minus query/fragment (same host + different path != same source!)
            val o1 = PluginPackageManager.canonicalSourceOrigin(null, "https://cdn.example.com/a/plugin.d3plugin?token=1#v1")
            val o2 = PluginPackageManager.canonicalSourceOrigin(null, "https://cdn.example.com/a/plugin.d3plugin?token=2#v2")
            val o3 = PluginPackageManager.canonicalSourceOrigin(null, "https://cdn.example.com/b/plugin.d3plugin")
            assertEquals("Same URL path with different query/fragment must match", o1, o2)
            assertNotEquals("Same host with different path must NOT match as same source", o1, o3)

            mgr.uninstall("com.example.localid")
            mgr.installFromBytes(v1Zip, sourceUrl = "https://cdn.example.com/a/plugin.d3plugin?v=1")
            // Same host, DIFFERENT path -> must reject as different source!
            try {
                mgr.installFromBytes(v2Zip, sourceUrl = "https://cdn.example.com/b/plugin.d3plugin?v=2")
                fail("Different URL path on same host must throw PluginSourceMismatchException")
            } catch (_: PluginSourceMismatchException) {
            }
            // Same URL path with different query -> allowed as same-source update!
            val updated = mgr.installFromBytes(v2Zip, sourceUrl = "https://cdn.example.com/a/plugin.d3plugin?v=2#latest")
            assertTrue(updated.isUpdate)
            assertFalse(updated.replacedDifferentSource)
        } finally {
            mgr.shutdownAll()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun repositoryClientEnforcesSingleTotalTimeoutAcrossAllRedirectHops(): Unit = runBlocking {
        // Item #7: total download timeout across redirect chain (not per-hop 30s * 10 = 300s)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(160)
                return when (request.path) {
                    "/hop1" -> MockResponse().setResponseCode(302).addHeader("Location", "/hop2")
                    "/hop2" -> MockResponse().setResponseCode(302).addHeader("Location", "/hop3")
                    "/hop3" -> MockResponse().setResponseCode(302).addHeader("Location", "/hop4")
                    "/hop4" -> MockResponse().setResponseCode(200).setBody("""{"name":"Slow","plugins":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .proxy(JavaProxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
                .build()
            val repoClient = PluginRepositoryClient(
                httpClient = client,
                devPolicyFactory = { host ->
                    NetworkPolicy.forDevServer(host) { listOf(InetAddress.getByName("192.168.1.50")) }
                },
                totalTimeoutMs = 350L,
            )
            val start = System.currentTimeMillis()
            try {
                repoClient.downloadDevPackage("http://192.168.1.50:${server.port}/hop1")
                fail("Expected total download timeout across 4 slow hops")
            } catch (_: RepositoryVerificationException) {
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue("Total timeout should abort before completing all 4 hops (elapsed=${elapsed}ms)", elapsed < 1000L)
        } finally {
            server.shutdown()
        }
    }

    private fun findBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun buildDuplicateManifestZip(): ByteArray {
        val m1 = """{"api":1,"id":"com.example.a","name":"A","version":"1.0.0"}""".toByteArray()
        val m2 = """{"api":1,"id":"com.example.b","name":"B","version":"1.0.0"}""".toByteArray()
        val js = "export default {};".toByteArray()
        val bos = ByteArrayOutputStream()
        // Java ZipOutputStream rejects duplicate names, so build minimal STORED zip with two manifest.json entries
        val entries = listOf("manifest.json" to m1, "manifest.json" to m2, "main.js" to js)
        val offsets = ArrayList<Int>()
        val crcs = ArrayList<Long>()
        for ((name, data) in entries) {
            offsets += bos.size()
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            crcs += crc
            val nameBytes = name.toByteArray()
            writeU32(bos, 0x04034b50L)
            writeU16(bos, 20); writeU16(bos, 0); writeU16(bos, 0); writeU16(bos, 0); writeU16(bos, 0)
            writeU32(bos, crc); writeU32(bos, data.size.toLong()); writeU32(bos, data.size.toLong())
            writeU16(bos, nameBytes.size); writeU16(bos, 0)
            bos.write(nameBytes); bos.write(data)
        }
        val cdOffset = bos.size()
        entries.forEachIndexed { idx, (name, data) ->
            val nameBytes = name.toByteArray()
            writeU32(bos, 0x02014b50L)
            writeU16(bos, 20); writeU16(bos, 20); writeU16(bos, 0); writeU16(bos, 0); writeU16(bos, 0); writeU16(bos, 0)
            writeU32(bos, crcs[idx]); writeU32(bos, data.size.toLong()); writeU32(bos, data.size.toLong())
            writeU16(bos, nameBytes.size); writeU16(bos, 0); writeU16(bos, 0); writeU16(bos, 0); writeU16(bos, 0)
            writeU32(bos, 0L); writeU32(bos, offsets[idx].toLong())
            bos.write(nameBytes)
        }
        val cdSize = bos.size() - cdOffset
        writeU32(bos, 0x06054b50L)
        writeU16(bos, 0); writeU16(bos, 0); writeU16(bos, entries.size); writeU16(bos, entries.size)
        writeU32(bos, cdSize.toLong()); writeU32(bos, cdOffset.toLong()); writeU16(bos, 0)
        return bos.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }

    private fun jdbcSupportDbProxy(conn: Connection): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.size == 1 && args[0] is String) {
                conn.createStatement().use { it.execute(args[0] as String) }
                null
            } else {
                throw UnsupportedOperationException("Unexpected SupportSQLiteDatabase call: ${method.name}")
            }
        } as SupportSQLiteDatabase
    }

    companion object {
        fun buildPluginZip(
            manifestJson: String,
            mainJs: String,
            locales: Map<String, String> = emptyMap(),
        ): ByteArray {
            val entries = LinkedHashMap<String, ByteArray>()
            entries["manifest.json"] = manifestJson.toByteArray(Charsets.UTF_8)
            entries["main.js"] = mainJs.toByteArray(Charsets.UTF_8)
            locales.forEach { (tag, content) ->
                entries["locales/$tag.json"] = content.toByteArray(Charsets.UTF_8)
            }
            return buildRawZip(entries)
        }

        fun buildRawZip(entries: Map<String, ByteArray>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                for ((name, data) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            return bos.toByteArray()
        }
    }
}
