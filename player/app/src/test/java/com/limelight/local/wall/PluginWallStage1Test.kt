package com.limelight.local.wall

import com.limelight.local.media.Category
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page as MediaPage
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.PluginInternalException
import com.limelight.local.media.Shelf
import com.limelight.local.media.Source
import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.ManifestAndPermissionTest
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginLogLevel
import com.limelight.local.plugin.PluginPackageArchive
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.XmlStringLookup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginWallStage1Test {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val strings = XmlStringLookup

    private fun packDirectoryToZip(dir: File): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun add(rel: String, file: File) {
                val entry = ZipEntry(rel).apply { time = 1_767_225_600_000L }
                zos.putNextEntry(entry)
                zos.write(file.readBytes())
                zos.closeEntry()
            }
            add("manifest.json", File(dir, "manifest.json"))
            add("main.js", File(dir, "main.js"))
            val icon = File(dir, "icon.png")
            if (icon.isFile) add("icon.png", icon)
            val localesDir = File(dir, "locales")
            if (localesDir.isDirectory) {
                localesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                    ?.sortedBy { it.name }
                    ?.forEach { add("locales/${it.name}", it) }
            }
        }
        return out.toByteArray()
    }

    private fun packSimplePlugin(
        id: String,
        version: String,
        extraPermissions: String = "",
    ): ByteArray {
        val manifest = """
            {
              "api": 1,
              "id": "$id",
              "name": "Test Plugin",
              "version": "$version",
              "minApp": "1.0.0",
              "author": "depth3d",
              "description": "Test description",
              "license": "GPL-3.0-or-later",
              "multiInstance": true,
              "capabilities": [],
              "auth": "none",
              "permissions": {
                "hosts": [],
                "userHosts": true$extraPermissions
              },
              "config": [
                { "key": "server", "type": "url", "label": "Server", "required": true }
              ]
            }
        """.trimIndent()
        val mainJs = """
            module.exports = {
              home: async () => [],
              browse: async () => ({ items: [] }),
              detail: async (id) => ({ id, kind: "video", title: id }),
              playback: async () => ({ url: "https://example.com/a.mp4" }),
            };
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("main.js"))
            zos.write(mainJs.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun newManager(store: InMemoryPluginStore): PluginPackageManager {
        return PluginPackageManager(
            rootDir = tempFolder.newFolder("plugins"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = PluginHost.normalizePluginAppVersion("12.1-xr0.4.3"),
            deviceInfo = DeviceInfo.detect(PluginHost.normalizePluginAppVersion("12.1-xr0.4.3")),
        )
    }

    @Test
    fun builtInJellyfinPluginInstallsUpgradesAndRespectsForeignOrigin() = runBlocking {
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val jellyfinDir = File(repoRoot, "plugins/jellyfin")
        assertTrue("Expected plugins/jellyfin directory", jellyfinDir.isDirectory)

        // Verify Gradle-generated asset when assemble has run, and always verify packed archive validity
        val generatedAsset = File(
            repoRoot,
            "player/app/build/generated/assets/builtin-plugins/builtin-plugins/dev.depth3d.jellyfin.d3plugin",
        )
        val jellyfinZip = if (generatedAsset.isFile) {
            generatedAsset.readBytes()
        } else {
            packDirectoryToZip(jellyfinDir)
        }
        val inspected = PluginPackageArchive.inspect(jellyfinZip)
        assertTrue(inspected.locales.containsKey("zh-CN"))

        val store = InMemoryPluginStore()
        val pm = newManager(store)

        // First launch: silent install
        val r1 = BuiltInPluginInstaller.syncPackages(pm, store, listOf(jellyfinZip))
        assertEquals(listOf("dev.depth3d.jellyfin"), r1.installed)
        assertTrue(r1.failed.isEmpty())
        val pkg1 = store.getPackage("dev.depth3d.jellyfin")
        assertNotNull(pkg1)
        assertTrue(BuiltInPluginInstaller.isBuiltIn(pkg1!!))

        // Second launch with same version: skipped cleanly
        val r2 = BuiltInPluginInstaller.syncPackages(pm, store, listOf(jellyfinZip))
        assertEquals(listOf("dev.depth3d.jellyfin"), r2.skipped)
        assertTrue(r2.installed.isEmpty())
        assertTrue(r2.upgraded.isEmpty())

        // Built-in upgrade with higher version and expanded permissions auto-confirms and preserves instances
        val v1Zip = packSimplePlugin("dev.depth3d.sample", "1.0.0")
        val r3 = BuiltInPluginInstaller.syncPackages(pm, store, listOf(v1Zip))
        assertEquals(listOf("dev.depth3d.sample"), r3.installed)
        val inst = pm.createInstance(
            pluginId = "dev.depth3d.sample",
            displayName = "My Sample",
            config = mapOf("server" to JsonPrimitive("http://192.168.1.9:8096")),
        )

        val v2Zip = packSimplePlugin(
            id = "dev.depth3d.sample",
            version = "1.1.0",
            extraPermissions = """, "lan": true""",
        )
        val r4 = BuiltInPluginInstaller.syncPackages(pm, store, listOf(v2Zip))
        assertEquals(listOf("dev.depth3d.sample"), r4.upgraded)
        val pkg2 = store.getPackage("dev.depth3d.sample")!!
        assertEquals("1.1.0", pkg2.version)
        assertFalse("Built-in upgrade must auto-confirm permissions", pkg2.needsPermissionReconfirm)
        assertNotNull("Existing instance must be preserved across built-in upgrade", store.getInstance(inst.instanceId))

        // Foreign-origin plugin with same ID must NOT be overwritten by built-in sync
        val foreignZip = packSimplePlugin("dev.depth3d.foreign", "1.0.0")
        pm.installFromBytes(foreignZip, sourceUrl = "https://repo.example.com/foreign.d3plugin")
        val foreignV2 = packSimplePlugin("dev.depth3d.foreign", "2.0.0")
        val r5 = BuiltInPluginInstaller.syncPackages(pm, store, listOf(foreignV2))
        assertEquals(listOf("dev.depth3d.foreign"), r5.skippedDifferentSource)
        assertEquals("1.0.0", store.getPackage("dev.depth3d.foreign")!!.version)
    }

    @Test
    fun pluginSourceManagerCapsConcurrencyToTwoAndNeverBlocksLocal() = runBlocking {
        val activePlugins = AtomicInteger(0)
        val maxObservedPlugins = AtomicInteger(0)
        val localCompletedWhilePluginsBlocked = CompletableDeferred<Boolean>()
        val releasePlugins = CompletableDeferred<Unit>()

        val fakePluginSources = (1..5).associate { i ->
            val instId = "inst-$i"
            instId to object : MediaSource {
                override val source = Source("plugin:$instId", "Plugin $i", SourceKind.PLUGIN, "test")
                override suspend fun home(): List<Shelf> {
                    val now = activePlugins.incrementAndGet()
                    maxObservedPlugins.updateAndGet { prev -> maxOf(prev, now) }
                    try {
                        releasePlugins.await()
                        delay(15)
                        return listOf(Shelf(Category("c$i", "Shelf $i"), emptyList()))
                    } finally {
                        activePlugins.decrementAndGet()
                    }
                }

                override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
                override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.VIDEO, itemId)
                override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("http://127.0.0.1/x.mp4")
            }
        }

        val localSource = object : MediaSource {
            override val source = Source("local", "本机", SourceKind.LOCAL, "local")
            override suspend fun home(): List<Shelf> {
                localCompletedWhilePluginsBlocked.complete(true)
                return listOf(Shelf(Category("all", "全部"), emptyList()))
            }

            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item = Item(itemId, ItemKind.VIDEO, itemId)
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("file:///a.mp4")
        }

        val manager = WallPluginSourceManager(
            scope = this,
            openSource = { id -> fakePluginSources.getValue(id) },
            maxConcurrentPluginLoads = 2,
            ioDispatcher = Dispatchers.IO,
        )

        val pkg = InstalledPluginEntity(
            pluginId = "dev.depth3d.test",
            name = "Test",
            version = "1.0.0",
            author = "t",
            description = "t",
            manifestJson = "{}",
            packagePath = "/tmp",
            sourceUrl = null,
            repoUrl = null,
            sourceOrigin = "other:builtin:dev.depth3d.test",
            sha256 = "00",
            enabled = true,
            needsPermissionReconfirm = false,
            approvedPermissionsJson = "{}",
            installedAt = 1L,
            updatedAt = 1L,
        )
        val instances = (1..5).map { i ->
            PluginInstanceEntity(
                instanceId = "inst-$i",
                pluginId = "dev.depth3d.test",
                displayName = "Plugin $i",
                encryptedConfig = "",
                enabled = true,
                authState = "AUTHENTICATED",
                authUserJson = null,
                createdAt = i.toLong(),
                updatedAt = 1L,
            )
        }

        val rec1 = manager.reconcile(listOf(pkg), instances)
        assertEquals(5, rec1.sources.size)

        val shelves = ConcurrentHashMap<String, Load<List<Shelf>>>()
        val pluginJobs = rec1.sources.map { manager.refreshSource(it, shelves) }
        val localJob = manager.refreshSource(localSource, shelves)

        // Local MUST finish even while all 5 plugin calls are parked on releasePlugins
        localJob.join()
        assertTrue(localCompletedWhilePluginsBlocked.isCompleted)
        assertTrue(shelves["local"] is Load.Ready)

        // Wait briefly to let queued plugin coroutines attempt to enter the semaphore
        delay(50)
        assertEquals("At most 2 plugin home() calls may run concurrently", 2, activePlugins.get())

        releasePlugins.complete(Unit)
        pluginJobs.forEach { it.join() }

        assertTrue("Max observed concurrency must be <= 2, was ${maxObservedPlugins.get()}", maxObservedPlugins.get() <= 2)
        assertEquals(2, maxObservedPlugins.get())
        assertTrue(rec1.sources.all { shelves[it.source.id] is Load.Ready })

        // Disabling plugin removes all its instances immediately
        val recDisabled = manager.reconcile(listOf(pkg.copy(enabled = false)), instances)
        assertTrue(recDisabled.sources.isEmpty())
        assertEquals(5, recDisabled.removedSourceIds.size)
        manager.cancelAll()
    }

    @Test
    fun errorDispatcherCoversEveryRowOfHostIntegrationSection3_2() {
        val needsAuth = SourceErrorDispatcher.dispatch(
            SourceNeedsAuthException("请重新登录 Jellyfin"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.LOGIN, needsAuth.primary.kind)
        assertFalse(needsAuth.danger)
        assertEquals("请重新登录 Jellyfin", needsAuth.message)

        val authFailed = SourceErrorDispatcher.dispatch(
            SourceAuthFailedException("账号或密码不正确"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.RETRY, authFailed.primary.kind)
        assertTrue(authFailed.danger)

        val setupLocal = SourceErrorDispatcher.dispatch(
            SourceNeedsSetupException("需要存储权限"),
            SourceKind.LOCAL,
            "本机",
            strings,
        )
        assertEquals(SourceErrorActionKind.REQUEST_STORAGE, setupLocal.primary.kind)

        val setupWebDav = SourceErrorDispatcher.dispatch(
            SourceNeedsSetupException("WebDAV 未配置"),
            SourceKind.WEBDAV,
            "WebDAV",
            strings,
        )
        assertEquals(SourceErrorActionKind.CHECK_SETTINGS, setupWebDav.primary.kind)

        val setupPluginPerm = SourceErrorDispatcher.dispatch(
            SourceNeedsSetupException("插件更新后申请了更多权限，请先重新确认后再使用。"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.PLUGIN_SETUP, setupPluginPerm.primary.kind)
        assertTrue(setupPluginPerm.primary.label.contains("权限"))

        val unavailablePlugin = SourceErrorDispatcher.dispatch(
            SourceUnavailableException("java.net.ConnectException: failed to connect"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.RETRY, unavailablePlugin.primary.kind)
        assertEquals(SourceErrorActionKind.CHECK_SETTINGS, unavailablePlugin.secondary?.kind)
        assertFalse("Raw Java exception must never leak to UI", unavailablePlugin.message.contains("ConnectException"))

        val notFound = SourceErrorDispatcher.dispatch(
            ItemNotFoundException("item-1", "no item item-1"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.BACK_AND_REFRESH, notFound.primary.kind)
        assertFalse("Raw 'no item' English message must be sanitized", notFound.message.contains("no item"))

        val forbiddenWall = SourceErrorDispatcher.dispatch(
            SourceForbiddenException("仅会员可看"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
            inPlaybackContext = false,
        )
        assertEquals(SourceErrorActionKind.DISMISS_OR_SWITCH_QUALITY, forbiddenWall.primary.kind)

        val forbiddenPlayer = SourceErrorDispatcher.dispatch(
            SourceForbiddenException("仅会员可看"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
            inPlaybackContext = true,
        )
        assertTrue(forbiddenPlayer.primary.label.contains("清晰度"))

        val rateLimited = SourceErrorDispatcher.dispatch(
            SourceRateLimitedException("Rate limit", retryAfterSec = 15),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.RETRY_AFTER_COOLDOWN, rateLimited.primary.kind)
        assertEquals(15, rateLimited.primary.cooldownSec)
        assertTrue(rateLimited.message.contains("15"))

        val unsupported = SourceErrorDispatcher.dispatch(
            SourceUnsupportedException("DRM"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.SWITCH_SOURCE, unsupported.primary.kind)

        val internal = SourceErrorDispatcher.dispatch(
            PluginInternalException("TypeError: undefined is not a function"),
            SourceKind.PLUGIN,
            "Jellyfin",
            strings,
        )
        assertEquals(SourceErrorActionKind.VIEW_PLUGIN_LOGS, internal.primary.kind)
        assertEquals(SourceErrorActionKind.DISABLE_PLUGIN, internal.secondary?.kind)
        assertFalse(internal.message.contains("TypeError"))
    }

    @Test
    fun pluginLogBufferParsesCapsAndClearsPerPlugin() {
        val buf = PluginLogBuffer(maxEntriesPerPlugin = 3, maxBytesPerPlugin = 1024)
        assertFalse(buf.recordLine("depth3d: unrelated log line"))
        assertTrue(buf.recordLine("depth3d: plugin dev.depth3d.jellyfin [inst-1] INFO: hello 1", 1000L))
        assertTrue(buf.recordLine("depth3d: plugin dev.depth3d.jellyfin [inst-1] WARN: hello 2", 2000L))
        assertTrue(buf.recordLine("depth3d: plugin dev.depth3d.jellyfin [inst-1] ERROR: hello 3", 3000L))
        assertTrue(buf.recordLine("depth3d: plugin dev.depth3d.jellyfin [inst-1] INFO: hello 4", 4000L))
        assertTrue(buf.recordLine("depth3d: plugin dev.depth3d.other [inst-2] DEBUG: other", 5000L))

        val jellyfinLogs = buf.recentEntries("dev.depth3d.jellyfin")
        assertEquals(3, jellyfinLogs.size)
        assertEquals("hello 2", jellyfinLogs[0].message)
        assertEquals(PluginLogLevel.ERROR, jellyfinLogs[1].level)
        assertEquals("hello 4", jellyfinLogs[2].message)

        buf.clear("dev.depth3d.jellyfin")
        assertTrue(buf.recentEntries("dev.depth3d.jellyfin").isEmpty())
        assertEquals(1, buf.recentEntries("dev.depth3d.other").size)
    }

    @Test
    fun normalizePluginAppVersionProducesValidSemVer() {
        assertEquals("1.0.0", PluginHost.normalizePluginAppVersion("12.1-xr0.4.3"))
        assertEquals("1.0.0", PluginHost.normalizePluginAppVersion(null))
        assertEquals("2.3.4", PluginHost.normalizePluginAppVersion("2.3.4"))
    }
}
