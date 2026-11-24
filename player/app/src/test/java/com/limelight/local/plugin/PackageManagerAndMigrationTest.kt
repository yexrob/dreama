package com.limelight.local.plugin

import androidx.sqlite.db.SupportSQLiteDatabase
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.WallDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests:
 * 1. Real SQLite execution of Room [WallDatabase.MIGRATION_1_2] verified
 *    against KSP-exported `schemas/com.limelight.local.media.WallDatabase/1.json`
 *    and `2.json`
 * 2. Repository index validation + SHA-256 + manifest id/version verification
 * 3. Full [PluginPackageManager] lifecycle (install, update, permission
 *    expansion reconfirm, enable/disable, multiInstance, encrypted config,
 *    uninstall, zip-slip protection)
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
            // 1. Create exact v1 schema from 1.json
            val v1Entities = v1Schema["database"]!!.jsonObject["entities"]!!.jsonArray
            for (entityEl in v1Entities) {
                val e = entityEl.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                val sql = e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                conn.createStatement().execute(sql)
            }
            // Insert existing v1 watch_progress row
            conn.createStatement().execute(
                "INSERT INTO watch_progress VALUES ('local', 'v:42', '沙丘', 120000, 9000000, 1700000000000)",
            )

            // 2. Run actual Room MIGRATION_1_2 against the SQLite connection
            val supportDb = jdbcSupportDbProxy(conn)
            WallDatabase.MIGRATION_1_2.migrate(supportDb)

            // 3. Verify watch_progress row survived untouched
            conn.createStatement().executeQuery("SELECT title, positionMs FROM watch_progress WHERE itemId = 'v:42'").use { rs ->
                assertTrue(rs.next())
                assertEquals("沙丘", rs.getString(1))
                assertEquals(120000L, rs.getLong(2))
            }

            // 4. Verify every table & column declared in 2.json matches actual SQLite PRAGMA table_info
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

            // 5. Verify index and ON DELETE CASCADE foreign key behavior
            conn.createStatement().execute(
                "INSERT INTO plugin_packages VALUES ('com.test.p','P','1.0.0','A','D','{}','/tmp',NULL,NULL,'abc',1,0,'{}',1,1)",
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
    fun repositoryClientVerifiesIndexSha256AndManifestMatch() = runBlocking {
        val validZip = buildPluginZip(
            manifestJson = """
                {
                  "api": 1,
                  "id": "com.example.repoplug",
                  "name": "Repo Plugin",
                  "version": "1.2.0",
                  "author": "Tester"
                }
            """.trimIndent(),
            mainJs = "export default { home(){return []}, browse(){return {items:[]}}, detail(){return {id:'1',kind:'video',title:'1'}}, playback(){return {url:'https://cdn.example.com/1.mp4'}} };",
        )
        val validSha = PluginRepositoryClient.sha256Hex(validZip)
        val wrongIdZip = buildPluginZip(
            manifestJson = """{"api":1,"id":"com.example.other","name":"Other","version":"1.2.0","author":"Tester"}""",
            mainJs = "export default {};",
        )
        val wrongIdSha = PluginRepositoryClient.sha256Hex(wrongIdZip)

        val server = MockWebServer()
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
                          "url": "${server.url("/valid.d3plugin")}",
                          "sha256": "$validSha"
                        },
                        {
                          "id": "com.example.repoplug",
                          "name": "Tampered",
                          "version": "1.2.0",
                          "url": "${server.url("/valid.d3plugin")}",
                          "sha256": "${"0".repeat(64)}"
                        },
                        {
                          "id": "com.example.repoplug",
                          "name": "MismatchedId",
                          "version": "1.2.0",
                          "url": "${server.url("/wrong-id.d3plugin")}",
                          "sha256": "$wrongIdSha"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                "/valid.d3plugin" -> MockResponse().setBody(Buffer().write(validZip))
                "/wrong-id.d3plugin" -> MockResponse().setBody(Buffer().write(wrongIdZip))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val client = PluginRepositoryClient()
            val index = client.fetchIndex(server.url("/index.json").toString())
            assertEquals("测试插件仓库", index.name)
            assertEquals(3, index.plugins.size)

            // 1. Valid package succeeds
            val ok = client.downloadVerifiedPackage(index.plugins[0])
            assertEquals(validSha, ok.sha256)

            // 2. Tampered sha256 fails
            try {
                client.downloadVerifiedPackage(index.plugins[1])
                fail("Expected SHA-256 mismatch to fail")
            } catch (_: RepositoryVerificationException) {
            }

            // 3. Manifest id mismatch fails
            try {
                client.downloadVerifiedPackage(index.plugins[2])
                fail("Expected manifest id mismatch to fail")
            } catch (_: RepositoryVerificationException) {
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun packageManagerHandlesInstallUpgradePermissionReconfirmDisableAndUninstall() = runBlocking {
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
            val r1 = mgr.installFromBytes(v1Zip)
            assertFalse(r1.isUpdate)
            assertFalse(r1.needsPermissionReconfirm)

            // Create instance + verify encrypted config at rest + single-instance enforcement
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

            val src = mgr.openSource(inst.instanceId)
            assertEquals("默认名", src.home().first().category.title)

            // Downgrade / same version rejected
            try {
                mgr.installFromBytes(v1Zip)
                fail("Same version update must be rejected")
            } catch (_: PackageInstallException) {
            }

            // Upgrade to v1.1.0 with EXPANDED permissions (adds lan: true) -> needsPermissionReconfirm
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
                  "config": [
                    { "key": "label", "type": "text", "label": "名称", "default": "默认名" }
                  ]
                }
                """.trimIndent(),
                minimalJs,
            )
            val r2 = mgr.installFromBytes(v2Zip)
            assertTrue(r2.isUpdate)
            assertTrue(r2.needsPermissionReconfirm)

            // Blocked until user re-confirms expanded permissions
            val src2 = mgr.openSource(inst.instanceId)
            try {
                src2.home()
                fail("Expected SourceNeedsSetupException while awaiting permission confirmation")
            } catch (_: SourceNeedsSetupException) {
            }

            mgr.confirmPermissions("com.example.app")
            assertEquals("默认名", src2.home().first().category.title)

            // Disable blocks calls; re-enable restores
            mgr.setPluginEnabled("com.example.app", false)
            val srcDisabled = mgr.openSource(inst.instanceId)
            try {
                srcDisabled.home()
                fail("Disabled plugin must reject calls")
            } catch (_: SourceNeedsSetupException) {
            }
            mgr.setPluginEnabled("com.example.app", true)
            assertEquals("默认名", mgr.openSource(inst.instanceId).home().first().category.title)

            // Uninstall removes package, instances, and files on disk
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
