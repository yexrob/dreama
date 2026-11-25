package com.limelight.local.wall

import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.plugin.ConfigField
import com.limelight.local.plugin.ConfigFieldType
import com.limelight.local.plugin.ConfigOption
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.PackageInstallException
import com.limelight.local.plugin.PluginAuthMode
import com.limelight.local.plugin.PluginManifest
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.PluginPermissions
import com.limelight.local.plugin.PluginRepoEntity
import com.limelight.local.plugin.PluginRepositoryClient
import com.limelight.local.plugin.PluginSourceMismatchException
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.XmlStringLookup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginConfigFormAndRepoTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val strings = XmlStringLookup

    private fun buildZip(manifestJson: String, mainJs: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("main.js"))
            zos.write(mainJs.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun configFormCodecHandlesAllSixFieldTypesHttpWarningAndPasswordHandling() {
        val manifest = PluginManifest(
            api = 1,
            id = "dev.dreama.allfields",
            name = "AllFields",
            version = "1.0.0",
            auth = PluginAuthMode.FORM,
            config = listOf(
                ConfigField("server", ConfigFieldType.URL, "服务器", required = true),
                ConfigField("username", ConfigFieldType.TEXT, "用户名", required = true),
                ConfigField("password", ConfigFieldType.PASSWORD, "密码", required = true),
                ConfigField("port", ConfigFieldType.NUMBER, "端口", defaultValue = JsonPrimitive(8096)),
                ConfigField(
                    "quality",
                    ConfigFieldType.SELECT,
                    "画质",
                    defaultValue = JsonPrimitive("direct"),
                    options = listOf(ConfigOption("direct", "原画"), ConfigOption("720p", "720p")),
                ),
                ConfigField("strict", ConfigFieldType.TOGGLE, "严格模式", defaultValue = JsonPrimitive(true)),
            ),
        )

        val init = PluginConfigFormCodec.initialValues(
            manifest = manifest,
            prefillServerUrl = "http://192.168.1.20:8096",
            suggestedName = "客厅 NAS",
        )
        assertEquals("客厅 NAS", init[PluginConfigFormCodec.DISPLAY_NAME_KEY])
        assertEquals("http://192.168.1.20:8096", init["server"])
        assertEquals("", init["password"])
        assertEquals("8096", init["port"])
        assertEquals("direct", init["quality"])
        assertEquals("true", init["strict"])
        assertTrue("http:// URL must trigger cleartext warning", PluginConfigFormCodec.hasCleartextHttpUrl(manifest, init))

        // Invalid URL & invalid number rejected cleanly
        val badUrl = PluginConfigFormCodec.validateAndSerialize(
            manifest,
            init + mapOf("server" to "ftp://bad", "username" to "u", "password" to "p"),
            strings,
        )
        assertTrue(badUrl is ConfigFormResult.Invalid)

        val badNum = PluginConfigFormCodec.validateAndSerialize(
            manifest,
            init + mapOf("username" to "u", "password" to "p", "port" to "not-a-num"),
            strings,
        )
        assertTrue(badNum is ConfigFormResult.Invalid)

        // Valid values serialize with proper JSON primitive types
        val ok = PluginConfigFormCodec.validateAndSerialize(
            manifest,
            init + mapOf(
                "username" to "alice",
                "password" to "secret123",
                "port" to "9000",
                "quality" to "720p",
                "strict" to "false",
            ),
            strings,
        ) as ConfigFormResult.Valid
        assertEquals("客厅 NAS", ok.displayName)
        assertEquals("http://192.168.1.20:8096", (ok.config["server"] as JsonPrimitive).content)
        assertEquals("alice", (ok.config["username"] as JsonPrimitive).content)
        assertEquals("secret123", (ok.config["password"] as JsonPrimitive).content)
        assertEquals(9000L, (ok.config["port"] as JsonPrimitive).long)
        assertEquals("720p", (ok.config["quality"] as JsonPrimitive).content)
        assertEquals(false, (ok.config["strict"] as JsonPrimitive).boolean)

        // Editing existing instance preserves existing password when left blank, while never prefilling it
        val editInit = PluginConfigFormCodec.initialValues(manifest, existingConfig = ok.config, existingDisplayName = "客厅 NAS")
        assertEquals("Password must never be prefilled on edit", "", editInit["password"])
        val editRes = PluginConfigFormCodec.validateAndSerialize(
            manifest = manifest,
            values = editInit,
            strings = strings,
            existingConfig = ok.config,
            isEditingExisting = true,
        ) as ConfigFormResult.Valid
        assertEquals("secret123", (editRes.config["password"] as JsonPrimitive).content)

        // Clear password helper clears ONLY password keys and keeps non-password keys
        val cleared = PluginConfigFormCodec.clearPasswordValues(
            manifest,
            mapOf("server" to "http://192.168.1.20:8096", "username" to "alice", "password" to "secret123"),
        )
        assertEquals("http://192.168.1.20:8096", cleared["server"])
        assertEquals("alice", cleared["username"])
        assertEquals("", cleared["password"])
    }

    @Test
    fun authenticatorRollsBackNewInstanceOnLoginFailure() = runBlocking {
        val manifestJson = """
            {
              "api": 1,
              "id": "dev.dreama.authfail",
              "name": "AuthFail",
              "version": "1.0.0",
              "minApp": "1.0.0",
              "author": "depth3d",
              "description": "Test",
              "license": "GPL-3.0-or-later",
              "multiInstance": true,
              "capabilities": [],
              "auth": "form",
              "permissions": { "hosts": [], "userHosts": true, "lan": true },
              "config": [
                { "key": "username", "type": "text", "label": "User", "required": true },
                { "key": "password", "type": "password", "label": "Pass", "required": true }
              ]
            }
        """.trimIndent()
        val mainJs = """
            export default {
              async login(ctx) {
                throw new ctx.Error("AuthFailed", "用户名或密码错误");
              },
              async home() { return []; },
              async browse() { return { items: [] }; },
              async detail(ctx, id) { return { id, kind: "video", title: id }; },
              async playback() { return { url: "https://example.com/a.mp4" }; }
            };
        """.trimIndent()
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("plugins-auth"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        val installed = pm.installFromBytes(buildZip(manifestJson, mainJs), sourceUrl = "builtin:dev.dreama.authfail")

        try {
            PluginInstanceAuthenticator.createAndAuthenticateFormOrNone(
                packageManager = pm,
                manifest = installed.manifest,
                displayName = "Bad Login",
                config = mapOf("username" to JsonPrimitive("alice"), "password" to JsonPrimitive("wrong")),
            )
            fail("Expected SourceAuthFailedException")
        } catch (_: SourceAuthFailedException) {
            // expected
        }
        assertTrue("Failed login must roll back temp instance completely", store.listInstances().isEmpty())
        pm.shutdownAll()
    }

    @Test
    fun installGuardNeverAutoConfirmsSourceMismatchSurfacesWildcardLanAndProtectsBuiltIn() = runBlocking {
        val wildcardManifest = """
            {
              "api": 1,
              "id": "dev.dreama.lanwild",
              "name": "LanWild",
              "version": "1.0.0",
              "minApp": "1.0.0",
              "author": "community",
              "description": "Wildcard LAN test",
              "license": "GPL-3.0-or-later",
              "multiInstance": true,
              "capabilities": [],
              "auth": "none",
              "permissions": {
                "hosts": ["*.home.arpa"],
                "userHosts": false,
                "lan": true
              }
            }
        """.trimIndent()
        val simpleJs = "export default { async home(){return [];}, async browse(){return {items:[]};}, async detail(c,id){return {id,kind:'video',title:id};}, async playback(){return {url:'https://a.home.arpa/1.mp4'};} };"
        val zipA = buildZip(wildcardManifest, simpleJs)
        val zipB = buildZip(wildcardManifest.replace("\"version\": \"1.0.0\"", "\"version\": \"1.1.0\""), simpleJs)

        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("plugins-guard"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )

        // 1. Preview surfaces wildcard-LAN warning explicitly
        val preview1 = pm.previewPackage(zipA, sourceUrl = "https://repo-a.example.com/a.d3plugin", repoUrl = "https://repo-a.example.com/repo.json")
        assertTrue(preview1.allowsWildcardLanAccess)
        assertTrue(
            PluginPermissionSummary.summarizeOneLine(preview1.manifest.permissions, strings)
                .contains("局域网里的任意设备"),
        )

        val pending1 = PendingPluginInstall(zipA, "https://repo-a.example.com/a.d3plugin", "https://repo-a.example.com/repo.json", "dev.dreama.lanwild", "1.0.0", null, preview1)
        PluginInstallGuard.confirmInstall(pm, pending1, userConfirmedReplacement = false)

        // 2. Installing same ID from different repository MUST throw PluginSourceMismatchException when userConfirmedReplacement=false
        val preview2 = pm.previewPackage(zipB, sourceUrl = "https://repo-b.example.com/b.d3plugin", repoUrl = "https://repo-b.example.com/repo.json")
        assertTrue("Different source must require replacement confirmation", preview2.requiresReplacementConfirmation)
        val pending2 = PendingPluginInstall(zipB, "https://repo-b.example.com/b.d3plugin", "https://repo-b.example.com/repo.json", "dev.dreama.lanwild", "1.1.0", null, preview2)
        try {
            PluginInstallGuard.confirmInstall(pm, pending2, userConfirmedReplacement = false)
            fail("Must NEVER auto-confirm PluginSourceMismatchException")
        } catch (_: PluginSourceMismatchException) {
            // expected
        }
        assertEquals("1.0.0", store.getPackage("dev.dreama.lanwild")!!.version)

        // Explicit user confirmation succeeds
        PluginInstallGuard.confirmInstall(pm, pending2, userConfirmedReplacement = true)
        assertEquals("1.1.0", store.getPackage("dev.dreama.lanwild")!!.version)

        // 3. Built-in plugins cannot be uninstalled
        val builtInZip = buildZip(wildcardManifest.replace("dev.dreama.lanwild", "dev.dreama.builtin"), simpleJs)
        BuiltInPluginInstaller.syncPackages(pm, store, listOf(builtInZip))
        val builtInEntity = store.getPackage("dev.dreama.builtin")!!
        try {
            PluginInstallGuard.uninstallIfAllowed(pm, builtInEntity)
            fail("Built-in plugin uninstall must be blocked")
        } catch (_: PackageInstallException) {
            // expected
        }
        assertNotNull(store.getPackage("dev.dreama.builtin"))

        // 4. Update detection matches ONLY same-source newer SemVer
        val repoIndexJson = """
            {
              "name": "Repo B",
              "plugins": [
                {
                  "id": "dev.dreama.lanwild",
                  "name": "LanWild",
                  "version": "1.2.0",
                  "url": "https://repo-b.example.com/lanwild-1.2.0.d3plugin",
                  "sha256": "${"a".repeat(64)}"
                },
                {
                  "id": "dev.dreama.builtin",
                  "name": "BuiltIn Hijack",
                  "version": "9.9.9",
                  "url": "https://repo-b.example.com/hijack.d3plugin",
                  "sha256": "${"b".repeat(64)}"
                }
              ]
            }
        """.trimIndent()
        val updates = PluginInstallGuard.findAvailableUpdates(
            installed = store.listPackages(),
            repos = listOf(PluginRepoEntity("https://repo-b.example.com/repo.json", "Repo B", 1L, repoIndexJson)),
            repoClient = PluginRepositoryClient(),
        )
        assertTrue(updates.containsKey("dev.dreama.lanwild"))
        assertEquals("1.2.0", updates["dev.dreama.lanwild"]!!.entry.version)
        assertFalse("Different-origin repo must not propose silent in-place update for built-in", updates.containsKey("dev.dreama.builtin"))
        pm.shutdownAll()
    }
}
