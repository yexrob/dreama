package com.limelight.local.wall

import android.content.Context
import com.limelight.LimeLog
import com.limelight.local.media.WallDatabase
import com.limelight.local.plugin.AndroidKeystoreSecretBox
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.ManifestParser
import com.limelight.local.plugin.PluginInstallPreview
import com.limelight.local.plugin.PluginLocale
import com.limelight.local.plugin.PluginLogEntry
import com.limelight.local.plugin.PluginLogLevel
import com.limelight.local.plugin.PluginLogger
import com.limelight.local.plugin.PluginManifest
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginPackageArchive
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.PluginRepositoryClient
import com.limelight.local.plugin.PluginStore
import com.limelight.local.plugin.RoomPluginStore
import com.limelight.local.plugin.SecretBox
import com.limelight.local.plugin.SemVer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * SemVer host version advertised to plugins via `ctx.device.appVersion` and checked against
 * `manifest.minApp`. Intentionally decoupled from Android's marketing `BuildConfig.VERSION_NAME`
 * (which may not be strict SemVer).
 *
 * When to increment:
 * - Patch (`1.0.x`): bug fixes in host/UI behavior with no new plugin-facing contract.
 * - Minor (`1.x.0`): backward-compatible additions to host capabilities or manifest support
 *   (e.g. a new built-in `config[].discover` protocol or optional UI capability).
 * - Major (`x.0.0`): breaking changes to plugin host integration.
 */
const val PLUGIN_HOST_VERSION: String = "1.0.0"

/**
 * Result of synchronizing bundled `.d3plugin` assets into [PluginPackageManager].
 */
data class BuiltInSyncReport(
    val installed: List<String> = emptyList(),
    val upgraded: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val skippedDifferentSource: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
)

/**
 * Pure, unit-testable installer/upgrader for bundled `.d3plugin` packages shipped
 * inside APK assets (`builtin-plugins/`). First launch installs them silently;
 * App upgrades bump built-in plugins in place without prompting and without
 * overwriting plugins a user installed from a different source.
 */
object BuiltInPluginInstaller {
    const val ASSETS_DIR = "builtin-plugins"
    const val SOURCE_PREFIX = "builtin:"

    fun sourceUrlFor(pluginId: String): String = "$SOURCE_PREFIX$pluginId"

    fun sourceOriginFor(pluginId: String): String =
        PluginPackageManager.canonicalSourceOrigin(repoUrl = null, sourceUrl = sourceUrlFor(pluginId))

    fun isBuiltIn(entity: InstalledPluginEntity): Boolean =
        entity.sourceOrigin.startsWith("other:$SOURCE_PREFIX") ||
            entity.sourceUrl?.startsWith(SOURCE_PREFIX) == true

    suspend fun syncPackages(
        packageManager: PluginPackageManager,
        store: PluginStore,
        zipBlobs: List<ByteArray>,
    ): BuiltInSyncReport {
        val installed = ArrayList<String>()
        val upgraded = ArrayList<String>()
        val skipped = ArrayList<String>()
        val skippedDifferentSource = ArrayList<String>()
        val failed = ArrayList<String>()

        for (bytes in zipBlobs) {
            try {
                val archive = PluginPackageArchive.inspect(bytes)
                val manifest = ManifestParser.parseAndValidate(archive.manifestJson)
                val srcUrl = sourceUrlFor(manifest.id)
                val expectedOrigin = sourceOriginFor(manifest.id)
                // Always verify package through previewPackage before installation
                packageManager.previewPackage(bytes, sourceUrl = srcUrl)

                val existing = store.getPackage(manifest.id)
                when {
                    existing == null -> {
                        packageManager.installFromBytes(bytes, sourceUrl = srcUrl)
                        installed += manifest.id
                    }
                    existing.sourceOrigin != expectedOrigin -> {
                        LimeLog.warning(
                            "depth3d: skipping built-in plugin ${manifest.id}; installed from non-builtin origin ${existing.sourceOrigin}",
                        )
                        skippedDifferentSource += manifest.id
                    }
                    else -> {
                        val oldVer = SemVer.parse(existing.version)
                        val newVer = SemVer.parse(manifest.version)
                        val actualSha = PluginRepositoryClient.sha256Hex(bytes)
                        val existingDir = File(existing.packagePath)
                        val filesIntact = existingDir.isDirectory &&
                            File(existingDir, "manifest.json").isFile &&
                            File(existingDir, "main.js").isFile
                        val isNewerVersion = oldVer != null && newVer != null && newVer > oldVer
                        val needsRepairOrContentRefresh =
                            oldVer == null ||
                                !filesIntact ||
                                (newVer != null && newVer == oldVer && !actualSha.equals(existing.sha256, ignoreCase = true))
                        if (isNewerVersion) {
                            val res = packageManager.installFromBytes(bytes, sourceUrl = srcUrl)
                            if (res.needsPermissionReconfirm) {
                                packageManager.confirmPermissions(manifest.id)
                            }
                            upgraded += manifest.id
                        } else if (needsRepairOrContentRefresh) {
                            // Crash-safe in-place repair / same-version content refresh without writing
                            // any fake version to Room and without deleting existingDir before swap (#4):
                            // 1) Write unpacked archive to a sibling staging dir.
                            // 2) Atomically rename staging dir to a fresh `<version>-<sha8>` sibling dir.
                            // 3) Commit real SemVer + new packagePath + new sha256 in one atomic Room upsert.
                            // 4) Shut down cached runtimes and best-effort prune superseded dirs.
                            repairOrRefreshBuiltInInPlace(
                                packageManager = packageManager,
                                store = store,
                                existing = existing,
                                archive = archive,
                                manifest = manifest,
                                actualSha = actualSha,
                                srcUrl = srcUrl,
                                expectedOrigin = expectedOrigin,
                            )
                            upgraded += manifest.id
                        } else {
                            skipped += manifest.id
                        }
                    }
                }
            } catch (e: Exception) {
                LimeLog.warning("depth3d: failed to sync built-in plugin asset: ${e.javaClass.simpleName}: ${e.message}")
                failed += e.message ?: e.javaClass.simpleName
            }
        }
        return BuiltInSyncReport(
            installed = installed,
            upgraded = upgraded,
            skipped = skipped,
            skippedDifferentSource = skippedDifferentSource,
            failed = failed,
        )
    }

    private suspend fun repairOrRefreshBuiltInInPlace(
        packageManager: PluginPackageManager,
        store: PluginStore,
        existing: InstalledPluginEntity,
        archive: PluginPackageArchive,
        manifest: PluginManifest,
        actualSha: String,
        srcUrl: String,
        expectedOrigin: String,
    ) {
        val oldDir = File(existing.packagePath)
        val pluginRootDir = oldDir.parentFile ?: error("Built-in plugin packagePath has no parent directory")
        pluginRootDir.mkdirs()

        // Clean up any abandoned staging directories from prior interrupted launches
        pluginRootDir.listFiles { f -> f.isDirectory && f.name.startsWith(".staging-") }
            ?.forEach { runCatching { it.deleteRecursively() } }

        val shaPrefix = actualSha.take(8).lowercase(Locale.ROOT)
        val desiredDirName = "${manifest.version}-$shaPrefix"
        val targetDir = if (oldDir.name == desiredDirName) {
            File(pluginRootDir, "$desiredDirName-${UUID.randomUUID().toString().take(6)}")
        } else {
            File(pluginRootDir, desiredDirName)
        }
        val stagingDir = File(pluginRootDir, ".staging-${UUID.randomUUID()}")
        try {
            writeArchiveFiles(archive, stagingDir)
            if (targetDir.exists() && targetDir.absolutePath != oldDir.absolutePath) {
                targetDir.deleteRecursively()
            }
            moveDirAtomically(stagingDir, targetDir)
        } catch (t: Throwable) {
            runCatching { stagingDir.deleteRecursively() }
            throw t
        }

        val now = System.currentTimeMillis()
        val updatedEntity = InstalledPluginEntity(
            pluginId = manifest.id,
            name = manifest.name,
            version = manifest.version,
            author = manifest.author,
            description = manifest.description,
            manifestJson = archive.manifestJson,
            packagePath = targetDir.absolutePath,
            sourceUrl = srcUrl,
            repoUrl = null,
            sourceOrigin = expectedOrigin,
            sha256 = actualSha,
            enabled = existing.enabled,
            needsPermissionReconfirm = false,
            approvedPermissionsJson = manifest.permissions.toJsonString(),
            installedAt = existing.installedAt,
            updatedAt = now,
        )
        store.upsertPackage(updatedEntity)
        runCatching { packageManager.shutdownAll() }

        // Only remove superseded directory AFTER Room has atomically committed the new packagePath
        if (oldDir.absolutePath != targetDir.absolutePath) {
            runCatching { oldDir.deleteRecursively() }
        }
    }

    private fun writeArchiveFiles(archive: PluginPackageArchive, dir: File) {
        dir.mkdirs()
        File(dir, "manifest.json").writeText(archive.manifestJson, Charsets.UTF_8)
        File(dir, "main.js").writeText(archive.mainJs, Charsets.UTF_8)
        archive.iconPng?.let { File(dir, "icon.png").writeBytes(it) }
        if (archive.locales.isNotEmpty()) {
            val locDir = File(dir, "locales").apply { mkdirs() }
            archive.locales.forEach { (tag, text) ->
                File(locDir, "$tag.json").writeText(text, Charsets.UTF_8)
            }
        }
    }

    private fun moveDirAtomically(src: File, dst: File) {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (!src.renameTo(dst)) {
                src.copyRecursively(dst, overwrite = true)
                src.deleteRecursively()
            }
        }
    }
}

/**
 * Process-level per-plugin log ring buffer capturing every redacted log line emitted by
 * [PluginLogger] through [LimeLog] (`depth3d: plugin <pluginId> [<instanceId>] <LEVEL>: <msg>`).
 * Supports both viewing recent entries and clearing per plugin without altering runtime internals.
 */
class PluginLogBuffer(
    private val maxEntriesPerPlugin: Int = PluginLogger.MAX_ENTRIES,
    private val maxBytesPerPlugin: Int = PluginLogger.MAX_BUFFER_BYTES,
) {
    private data class Stored(val entry: PluginLogEntry, val bytes: Int)

    private class PluginRing(
        private val maxEntries: Int,
        private val maxBytes: Int,
    ) {
        private val deque = ArrayDeque<Stored>(maxEntries)
        private var totalBytes = 0

        fun add(entry: PluginLogEntry) {
            val size = entry.message.toByteArray(Charsets.UTF_8).size
            synchronized(this) {
                while (deque.size >= maxEntries && deque.isNotEmpty()) {
                    totalBytes -= deque.removeFirst().bytes
                }
                while (deque.isNotEmpty() && totalBytes + size > maxBytes) {
                    totalBytes -= deque.removeFirst().bytes
                }
                deque.addLast(Stored(entry, size))
                totalBytes += size
            }
        }

        fun entries(): List<PluginLogEntry> = synchronized(this) { deque.map { it.entry } }

        fun clear() = synchronized(this) {
            deque.clear()
            totalBytes = 0
        }
    }

    private val rings = ConcurrentHashMap<String, PluginRing>()

    fun recordLine(rawLine: String, timestampMs: Long = System.currentTimeMillis()): Boolean {
        val parsed = parseLine(rawLine, timestampMs) ?: return false
        rings.computeIfAbsent(parsed.pluginId) {
            PluginRing(maxEntriesPerPlugin, maxBytesPerPlugin)
        }.add(parsed)
        return true
    }

    fun recentEntries(pluginId: String): List<PluginLogEntry> =
        rings[pluginId]?.entries().orEmpty()

    fun clear(pluginId: String) {
        rings[pluginId]?.clear()
    }

    fun clearAll() {
        rings.values.forEach { it.clear() }
        rings.clear()
    }

    companion object {
        private val LINE_REGEX = Regex(
            """^depth3d: plugin (\S+) \[([^\]]+)\] (DEBUG|INFO|WARN|ERROR): (.*)$""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        fun parseLine(rawLine: String, timestampMs: Long = System.currentTimeMillis()): PluginLogEntry? {
            val m = LINE_REGEX.matchEntire(rawLine) ?: return null
            val level = runCatching { PluginLogLevel.valueOf(m.groupValues[3]) }.getOrNull() ?: return null
            return PluginLogEntry(
                timestampMs = timestampMs,
                level = level,
                pluginId = m.groupValues[1],
                instanceId = m.groupValues[2],
                message = m.groupValues[4],
            )
        }
    }
}

/**
 * Process-level holder owning the single [PluginPackageManager] (Room store + Android Keystore
 * encryption), repository client, built-in plugin installation, and plugin log ring buffer.
 */
class PluginHost private constructor(
    private val appContext: Context,
    val database: WallDatabase,
    val store: PluginStore,
    val repoClient: PluginRepositoryClient,
    val logBuffer: PluginLogBuffer,
    private val localeTag: String,
    private val secretBoxFactory: () -> SecretBox,
    private val detectDeviceInfo: (String) -> DeviceInfo,
    private val packagesRootDir: File,
    private val loadAssets: (Context) -> List<ByteArray>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val packageManagerDeferred = CompletableDeferred<PluginPackageManager>()
    private val builtInDone = CompletableDeferred<BuiltInSyncReport>()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> get() = _changes

    private val _pendingInstanceIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingInstanceIds: StateFlow<Set<String>> get() = _pendingInstanceIds

    private fun startBackgroundInit() {
        scope.launch {
            val pm = try {
                val deviceInfo = detectDeviceInfo(PLUGIN_HOST_VERSION)
                PluginPackageManager(
                    rootDir = packagesRootDir,
                    store = store,
                    secretBox = secretBoxFactory(),
                    repoClient = repoClient,
                    appVersion = PLUGIN_HOST_VERSION,
                    deviceInfo = deviceInfo,
                    androidContext = appContext,
                )
            } catch (e: CancellationException) {
                packageManagerDeferred.cancel(e)
                builtInDone.cancel(e)
                throw e
            } catch (t: Throwable) {
                packageManagerDeferred.completeExceptionally(t)
                builtInDone.complete(BuiltInSyncReport(failed = listOf(t.javaClass.simpleName)))
                return@launch
            }
            runCatching { pm.cleanupOrphanedFiles() }
            packageManagerDeferred.complete(pm)

            val report = try {
                val blobs = loadAssets(appContext)
                BuiltInPluginInstaller.syncPackages(pm, store, blobs)
            } catch (e: CancellationException) {
                builtInDone.cancel(e)
                throw e
            } catch (e: Exception) {
                LimeLog.warning("depth3d: built-in plugin sync error: ${e.javaClass.simpleName}")
                BuiltInSyncReport(failed = listOf(e.javaClass.simpleName))
            }
            if (report.installed.isNotEmpty() || report.upgraded.isNotEmpty()) {
                LimeLog.info("depth3d: built-in plugins synced installed=${report.installed} upgraded=${report.upgraded}")
                notifyChanged()
            }
            builtInDone.complete(report)
        }
    }

    /** Suspends until background [DeviceInfo.detect] + [PluginPackageManager] init completes. */
    suspend fun packageManager(): PluginPackageManager = packageManagerDeferred.await()

    suspend fun awaitBuiltInReady(): BuiltInSyncReport = builtInDone.await()

    fun markInstancePending(instanceId: String) {
        _pendingInstanceIds.update { it + instanceId }
    }

    fun unmarkInstancePending(instanceId: String) {
        _pendingInstanceIds.update { it - instanceId }
    }

    fun notifyChanged() {
        _changes.value = _changes.value + 1L
    }

    suspend fun openSource(instanceId: String): PluginMediaSource =
        withContext(Dispatchers.IO) { packageManager().openSource(instanceId) }

    suspend fun openSourceBySourceId(sourceId: String): PluginMediaSource? {
        if (!sourceId.startsWith(PLUGIN_SOURCE_PREFIX)) return null
        val instanceId = sourceId.removePrefix(PLUGIN_SOURCE_PREFIX)
        if (instanceId.isBlank()) return null
        return openSource(instanceId)
    }

    fun localizeManifest(entity: InstalledPluginEntity): PluginManifest {
        val raw = ManifestParser.parseAndValidate(entity.manifestJson)
        val locale = PluginLocale.fromPackageDir(File(entity.packagePath), localeTag)
        return locale.localizeManifest(raw)
    }

    fun localizePreview(zipBytes: ByteArray, preview: PluginInstallPreview): PluginInstallPreview {
        val archive = runCatching { PluginPackageArchive.inspect(zipBytes) }.getOrNull() ?: return preview
        val locale = PluginLocale.fromMap(archive.locales, localeTag)
        return preview.copy(manifest = locale.localizeManifest(preview.manifest))
    }

    fun recentLogs(pluginId: String): List<PluginLogEntry> = logBuffer.recentEntries(pluginId)

    fun clearLogs(pluginId: String) = logBuffer.clear(pluginId)

    companion object {
        const val PLUGIN_SOURCE_PREFIX = "plugin:"

        @Volatile
        private var instance: PluginHost? = null

        // Strong reference so java.util.logging.LogManager never GCs the hooked logger
        @Volatile
        private var hookedLimeLogger: Logger? = null

        @JvmStatic
        fun initialize(context: Context): PluginHost = get(context)

        @JvmStatic
        fun get(context: Context): PluginHost {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return@synchronized it }
                val app = context.applicationContext
                val db = WallDatabase.get(app)
                val store = RoomPluginStore(db)
                val repoClient = PluginRepositoryClient()
                val logBuffer = PluginLogBuffer()
                hookPluginLogs(logBuffer)
                val localeTag = Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
                PluginHost(
                    appContext = app,
                    database = db,
                    store = store,
                    repoClient = repoClient,
                    logBuffer = logBuffer,
                    localeTag = localeTag,
                    secretBoxFactory = { AndroidKeystoreSecretBox() },
                    detectDeviceInfo = { ver -> DeviceInfo.detect(ver) },
                    packagesRootDir = File(app.filesDir, "plugins"),
                    loadAssets = { ctx -> loadBuiltInAssets(ctx) },
                ).also { host ->
                    instance = host
                    host.startBackgroundInit()
                }
            }
        }

        internal fun createForTesting(
            context: Context,
            database: WallDatabase,
            store: PluginStore,
            packagesRootDir: File,
            secretBoxFactory: () -> SecretBox,
            detectDeviceInfo: (String) -> DeviceInfo,
            loadAssets: (Context) -> List<ByteArray> = { emptyList() },
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PluginHost = PluginHost(
            appContext = context.applicationContext,
            database = database,
            store = store,
            repoClient = PluginRepositoryClient(),
            logBuffer = PluginLogBuffer(),
            localeTag = "zh-CN",
            secretBoxFactory = secretBoxFactory,
            detectDeviceInfo = detectDeviceInfo,
            packagesRootDir = packagesRootDir,
            loadAssets = loadAssets,
            ioDispatcher = ioDispatcher,
        ).also { it.startBackgroundInit() }

        private fun loadBuiltInAssets(context: Context): List<ByteArray> {
            val names = try {
                context.assets.list(BuiltInPluginInstaller.ASSETS_DIR)?.sorted().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            val out = ArrayList<ByteArray>()
            for (name in names) {
                if (!name.endsWith(".d3plugin")) continue
                try {
                    context.assets.open("${BuiltInPluginInstaller.ASSETS_DIR}/$name").use { stream ->
                        out += stream.readBytes()
                    }
                } catch (e: Exception) {
                    LimeLog.warning("depth3d: failed reading built-in asset $name: ${e.javaClass.simpleName}")
                }
            }
            return out
        }

        private fun hookPluginLogs(buffer: PluginLogBuffer) {
            val logger = Logger.getLogger(LimeLog::class.java.name)
            hookedLimeLogger = logger
            logger.addHandler(
                object : Handler() {
                    override fun publish(record: LogRecord?) {
                        val msg = record?.message ?: return
                        buffer.recordLine(msg, record.millis)
                    }

                    override fun flush() = Unit
                    override fun close() = Unit
                },
            )
        }
    }
}
