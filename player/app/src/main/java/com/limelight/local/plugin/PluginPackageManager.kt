package com.limelight.local.plugin

import android.content.Context
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.WallDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Unpacked in-memory representation of a `.d3plugin` archive.
 */
data class PluginPackageArchive(
    val manifestJson: String,
    val mainJs: String,
    val iconPng: ByteArray?,
    val locales: Map<String, String>,
) {
    companion object {
        private const val MAX_ENTRIES = 64
        private const val MAX_UNPACKED_BYTES = 16 * 1024 * 1024

        fun inspect(zipBytes: ByteArray): PluginPackageArchive {
            var manifestJson: String? = null
            var mainJs: String? = null
            var iconPng: ByteArray? = null
            val locales = LinkedHashMap<String, String>()
            var entryCount = 0
            var totalBytes = 0L

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > MAX_ENTRIES) {
                        throw PackageInstallException("插件包内文件数量过多（上限 $MAX_ENTRIES 个）")
                    }
                    val name = normalizeEntryName(entry.name)
                    if (entry.isDirectory) continue

                    val content = readBounded(zis, MAX_UNPACKED_BYTES - totalBytes)
                    totalBytes += content.size

                    when {
                        name == "manifest.json" -> manifestJson = String(content, Charsets.UTF_8)
                        name == "main.js" -> mainJs = String(content, Charsets.UTF_8)
                        name == "icon.png" -> iconPng = content
                        name.startsWith("locales/") && name.endsWith(".json") -> {
                            val tag = name.removePrefix("locales/").removeSuffix(".json")
                            if (tag.isNotEmpty() && !tag.contains('/')) {
                                locales[tag] = String(content, Charsets.UTF_8)
                            }
                        }
                    }
                }
            }

            if (manifestJson == null) {
                throw PackageInstallException("插件包缺少必需的 manifest.json")
            }
            if (mainJs == null) {
                throw PackageInstallException("插件包缺少必需的 main.js")
            }
            return PluginPackageArchive(
                manifestJson = manifestJson,
                mainJs = mainJs,
                iconPng = iconPng,
                locales = locales,
            )
        }

        private fun normalizeEntryName(raw: String): String {
            val cleaned = raw.replace('\\', '/').removePrefix("./")
            if (cleaned.startsWith("/") || cleaned.contains("..") || cleaned.contains('\u0000')) {
                throw PackageInstallException("插件包包含非法路径：$raw")
            }
            return cleaned
        }

        private fun readBounded(stream: ZipInputStream, remainingBytes: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var count = 0L
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                count += read
                if (count > remainingBytes) {
                    throw PackageInstallException("插件包解压后超过大小限制")
                }
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }
    }
}

class PackageInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class InstallResult(
    val entity: InstalledPluginEntity,
    val manifest: PluginManifest,
    val isUpdate: Boolean,
    val needsPermissionReconfirm: Boolean,
)

/**
 * Persistence abstraction backed by Room ([RoomPluginStore]) in production and
 * replaceable by [InMemoryPluginStore] in JVM unit tests.
 */
interface PluginStore {
    suspend fun listPackages(): List<InstalledPluginEntity>
    suspend fun getPackage(pluginId: String): InstalledPluginEntity?
    suspend fun upsertPackage(entity: InstalledPluginEntity)
    suspend fun setPackageEnabled(pluginId: String, enabled: Boolean, updatedAt: Long)
    suspend fun confirmPackagePermissions(pluginId: String, approvedPermissionsJson: String, updatedAt: Long)
    suspend fun deletePackage(pluginId: String)

    suspend fun listInstances(): List<PluginInstanceEntity>
    suspend fun listInstancesForPlugin(pluginId: String): List<PluginInstanceEntity>
    suspend fun getInstance(instanceId: String): PluginInstanceEntity?
    suspend fun upsertInstance(entity: PluginInstanceEntity)
    suspend fun updateInstanceAuth(instanceId: String, authState: String, authUserJson: String?, updatedAt: Long)
    suspend fun deleteInstance(instanceId: String)

    suspend fun listRepos(): List<PluginRepoEntity>
    suspend fun upsertRepo(entity: PluginRepoEntity)
    suspend fun deleteRepo(url: String)
}

class RoomPluginStore(
    private val db: WallDatabase,
) : PluginStore {
    override suspend fun listPackages() = db.pluginPackages().listAll()
    override suspend fun getPackage(pluginId: String) = db.pluginPackages().get(pluginId)
    override suspend fun upsertPackage(entity: InstalledPluginEntity) = db.pluginPackages().upsert(entity)
    override suspend fun setPackageEnabled(pluginId: String, enabled: Boolean, updatedAt: Long) =
        db.pluginPackages().setEnabled(pluginId, enabled, updatedAt)
    override suspend fun confirmPackagePermissions(pluginId: String, approvedPermissionsJson: String, updatedAt: Long) =
        db.pluginPackages().confirmPermissions(pluginId, approvedPermissionsJson, updatedAt)
    override suspend fun deletePackage(pluginId: String) = db.pluginPackages().delete(pluginId)

    override suspend fun listInstances() = db.pluginInstances().listAll()
    override suspend fun listInstancesForPlugin(pluginId: String) = db.pluginInstances().listForPlugin(pluginId)
    override suspend fun getInstance(instanceId: String) = db.pluginInstances().get(instanceId)
    override suspend fun upsertInstance(entity: PluginInstanceEntity) = db.pluginInstances().upsert(entity)
    override suspend fun updateInstanceAuth(
        instanceId: String,
        authState: String,
        authUserJson: String?,
        updatedAt: Long,
    ) = db.pluginInstances().updateAuth(instanceId, authState, authUserJson, updatedAt)
    override suspend fun deleteInstance(instanceId: String) = db.pluginInstances().delete(instanceId)

    override suspend fun listRepos() = db.pluginRepos().listAll()
    override suspend fun upsertRepo(entity: PluginRepoEntity) = db.pluginRepos().upsert(entity)
    override suspend fun deleteRepo(url: String) = db.pluginRepos().delete(url)
}

class InMemoryPluginStore : PluginStore {
    private val packages = LinkedHashMap<String, InstalledPluginEntity>()
    private val instances = LinkedHashMap<String, PluginInstanceEntity>()
    private val repos = LinkedHashMap<String, PluginRepoEntity>()

    override suspend fun listPackages(): List<InstalledPluginEntity> = synchronized(this) { packages.values.toList() }
    override suspend fun getPackage(pluginId: String): InstalledPluginEntity? = synchronized(this) { packages[pluginId] }
    override suspend fun upsertPackage(entity: InstalledPluginEntity) = synchronized(this) { packages[entity.pluginId] = entity }
    override suspend fun setPackageEnabled(pluginId: String, enabled: Boolean, updatedAt: Long) = synchronized(this) {
        packages[pluginId]?.let { packages[pluginId] = it.copy(enabled = enabled, updatedAt = updatedAt) }
        Unit
    }
    override suspend fun confirmPackagePermissions(pluginId: String, approvedPermissionsJson: String, updatedAt: Long) =
        synchronized(this) {
            packages[pluginId]?.let {
                packages[pluginId] = it.copy(
                    needsPermissionReconfirm = false,
                    approvedPermissionsJson = approvedPermissionsJson,
                    updatedAt = updatedAt,
                )
            }
            Unit
        }
    override suspend fun deletePackage(pluginId: String) = synchronized(this) {
        packages.remove(pluginId)
        instances.entries.removeAll { it.value.pluginId == pluginId }
        Unit
    }

    override suspend fun listInstances(): List<PluginInstanceEntity> = synchronized(this) { instances.values.toList() }
    override suspend fun listInstancesForPlugin(pluginId: String): List<PluginInstanceEntity> = synchronized(this) {
        instances.values.filter { it.pluginId == pluginId }
    }
    override suspend fun getInstance(instanceId: String): PluginInstanceEntity? = synchronized(this) { instances[instanceId] }
    override suspend fun upsertInstance(entity: PluginInstanceEntity) = synchronized(this) { instances[entity.instanceId] = entity }
    override suspend fun updateInstanceAuth(
        instanceId: String,
        authState: String,
        authUserJson: String?,
        updatedAt: Long,
    ) = synchronized(this) {
        instances[instanceId]?.let {
            instances[instanceId] = it.copy(authState = authState, authUserJson = authUserJson, updatedAt = updatedAt)
        }
        Unit
    }
    override suspend fun deleteInstance(instanceId: String) = synchronized(this) {
        instances.remove(instanceId)
        Unit
    }

    override suspend fun listRepos(): List<PluginRepoEntity> = synchronized(this) { repos.values.toList() }
    override suspend fun upsertRepo(entity: PluginRepoEntity) = synchronized(this) { repos[entity.url] = entity }
    override suspend fun deleteRepo(url: String) = synchronized(this) {
        repos.remove(url)
        Unit
    }
}

/**
 * Manages the full plugin package & instance lifecycle:
 * - Install / upgrade `.d3plugin` archives (manifest validation, semver check,
 *   minApp check, permission expansion -> `needsPermissionReconfirm`)
 * - Enable, disable, re-confirm permissions, uninstall (with runtime & data cleanup)
 * - Sync subscribed plugin repositories and install verified packages
 * - Create, configure (encrypted at rest), instantiate ([PluginMediaSource]),
 *   and delete plugin instances.
 */
class PluginPackageManager(
    private val rootDir: File,
    private val store: PluginStore,
    private val secretBox: SecretBox,
    private val repoClient: PluginRepositoryClient = PluginRepositoryClient(),
    private val appVersion: String = "1.0.0",
    private val deviceInfo: DeviceInfo = DeviceInfo(appVersion = appVersion),
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val androidContext: Context? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val packagesDir = File(rootDir, "packages")
    private val instancesDir = File(rootDir, "instances")
    private val activeSources = ConcurrentHashMap<String, PluginMediaSource>()

    /**
     * Installs or upgrades a `.d3plugin` archive from raw bytes.
     */
    suspend fun installFromBytes(
        zipBytes: ByteArray,
        sourceUrl: String? = null,
        repoUrl: String? = null,
        expectedId: String? = null,
        expectedVersion: String? = null,
        expectedSha256: String? = null,
    ): InstallResult {
        val actualSha = PluginRepositoryClient.sha256Hex(zipBytes)
        if (expectedSha256 != null && !actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw PackageInstallException("插件包 SHA-256 校验失败")
        }
        val archive = PluginPackageArchive.inspect(zipBytes)
        val manifest = ManifestParser.parseAndValidate(archive.manifestJson)

        if (expectedId != null && manifest.id != expectedId) {
            throw PackageInstallException("插件 id 不一致：期望 $expectedId，实际 ${manifest.id}")
        }
        if (expectedVersion != null && manifest.version != expectedVersion) {
            throw PackageInstallException("插件版本不一致：期望 $expectedVersion，实际 ${manifest.version}")
        }
        if (manifest.minApp != null) {
            val reqApp = SemVer.parse(manifest.minApp)
            val curApp = SemVer.parse(appVersion)
            if (reqApp != null && curApp != null && curApp < reqApp) {
                throw PackageInstallException("此插件需要 depth3d ${manifest.minApp} 及以上版本")
            }
        }

        val existing = store.getPackage(manifest.id)
        val isUpdate = existing != null
        val now = clockMs()

        var needsReconfirm = false
        var approvedPermsJson = manifest.permissions.toJsonString()

        if (existing != null) {
            val oldVer = SemVer.parse(existing.version)
            val newVer = SemVer.parse(manifest.version)
            if (oldVer != null && newVer != null && newVer <= oldVer) {
                throw PackageInstallException(
                    "更新版本号（${manifest.version}）必须大于已安装版本（${existing.version}）",
                )
            }
            val prevApproved = PluginPermissions.fromJsonString(existing.approvedPermissionsJson)
            if (manifest.permissions.isExpandedFrom(prevApproved)) {
                needsReconfirm = true
                approvedPermsJson = existing.approvedPermissionsJson
            } else {
                needsReconfirm = existing.needsPermissionReconfirm
                if (!needsReconfirm) {
                    approvedPermsJson = manifest.permissions.toJsonString()
                }
            }
        }

        // Write package files into a versioned directory, then clean older versions
        val targetDir = File(File(packagesDir, manifest.id), manifest.version)
        val stagingDir = File(File(packagesDir, manifest.id), ".staging-${UUID.randomUUID()}")
        try {
            writeArchiveToDir(archive, stagingDir)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.parentFile?.mkdirs()
            if (!stagingDir.renameTo(targetDir)) {
                stagingDir.copyRecursively(targetDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw PackageInstallException("写入插件文件失败：${e.message}", e)
        }

        // Dispose any running runtimes for this plugin so they reload the updated code
        shutdownRuntimesForPlugin(manifest.id)

        val entity = InstalledPluginEntity(
            pluginId = manifest.id,
            name = manifest.name,
            version = manifest.version,
            author = manifest.author,
            description = manifest.description,
            manifestJson = archive.manifestJson,
            packagePath = targetDir.absolutePath,
            sourceUrl = sourceUrl ?: existing?.sourceUrl,
            repoUrl = repoUrl ?: existing?.repoUrl,
            sha256 = actualSha,
            enabled = existing?.enabled ?: true,
            needsPermissionReconfirm = needsReconfirm,
            approvedPermissionsJson = approvedPermsJson,
            installedAt = existing?.installedAt ?: now,
            updatedAt = now,
        )
        store.upsertPackage(entity)

        // Remove superseded version directories on disk
        if (existing != null && existing.packagePath != targetDir.absolutePath) {
            runCatching { File(existing.packagePath).deleteRecursively() }
        }

        return InstallResult(
            entity = entity,
            manifest = manifest,
            isUpdate = isUpdate,
            needsPermissionReconfirm = needsReconfirm,
        )
    }

    suspend fun installFromFile(file: File): InstallResult =
        installFromBytes(file.readBytes(), sourceUrl = file.toURI().toString())

    suspend fun syncRepository(repoUrl: String): PluginRepoIndex {
        val index = repoClient.fetchIndex(repoUrl)
        store.upsertRepo(
            PluginRepoEntity(
                url = repoUrl,
                name = index.name,
                lastSyncedAt = clockMs(),
                lastIndexJson = index.rawJson,
            ),
        )
        return index
    }

    suspend fun installFromRepository(repoUrl: String, entry: RepoPluginEntry): InstallResult {
        val download = repoClient.downloadVerifiedPackage(entry)
        return installFromBytes(
            zipBytes = download.bytes,
            sourceUrl = download.sourceUrl,
            repoUrl = repoUrl,
            expectedId = entry.id,
            expectedVersion = entry.version,
            expectedSha256 = entry.sha256,
        )
    }

    suspend fun confirmPermissions(pluginId: String) {
        val pkg = store.getPackage(pluginId)
            ?: throw PackageInstallException("找不到已安装的插件：$pluginId")
        val manifest = ManifestParser.parseAndValidate(pkg.manifestJson)
        store.confirmPackagePermissions(pluginId, manifest.permissions.toJsonString(), clockMs())
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        store.setPackageEnabled(pluginId, enabled, clockMs())
        if (!enabled) {
            shutdownRuntimesForPlugin(pluginId)
        }
    }

    suspend fun uninstall(pluginId: String) {
        val instances = store.listInstancesForPlugin(pluginId)
        for (inst in instances) {
            activeSources.remove(inst.instanceId)?.shutdown()
            File(instancesDir, inst.instanceId).deleteRecursively()
        }
        store.deletePackage(pluginId)
        File(packagesDir, pluginId).deleteRecursively()
    }

    suspend fun createInstance(
        pluginId: String,
        displayName: String,
        config: Map<String, JsonElement> = emptyMap(),
        instanceId: String = UUID.randomUUID().toString(),
    ): PluginInstanceEntity {
        val pkg = store.getPackage(pluginId)
            ?: throw PackageInstallException("插件尚未安装：$pluginId")
        val manifest = ManifestParser.parseAndValidate(pkg.manifestJson)
        if (!manifest.multiInstance && store.listInstancesForPlugin(pluginId).isNotEmpty()) {
            throw PackageInstallException("插件「${manifest.name}」不支持添加多个实例")
        }
        val mergedConfig = applyConfigDefaults(manifest, config)
        val encryptedConfig = secretBox.encryptToBase64(JsonObject(mergedConfig).toString())
        val now = clockMs()
        val entity = PluginInstanceEntity(
            instanceId = instanceId,
            pluginId = pluginId,
            displayName = displayName.ifBlank { manifest.name },
            encryptedConfig = encryptedConfig,
            enabled = true,
            authState = if (manifest.auth == PluginAuthMode.NONE) "NONE" else "NEEDS_AUTH",
            authUserJson = null,
            createdAt = now,
            updatedAt = now,
        )
        store.upsertInstance(entity)
        return entity
    }

    suspend fun updateInstanceConfig(
        instanceId: String,
        displayName: String? = null,
        config: Map<String, JsonElement>,
    ): PluginInstanceEntity {
        val existing = store.getInstance(instanceId)
            ?: throw PackageInstallException("找不到插件实例：$instanceId")
        val pkg = store.getPackage(existing.pluginId)
            ?: throw PackageInstallException("找不到对应插件：${existing.pluginId}")
        val manifest = ManifestParser.parseAndValidate(pkg.manifestJson)
        val mergedConfig = applyConfigDefaults(manifest, config)
        val encryptedConfig = secretBox.encryptToBase64(JsonObject(mergedConfig).toString())
        val updated = existing.copy(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: existing.displayName,
            encryptedConfig = encryptedConfig,
            updatedAt = clockMs(),
        )
        store.upsertInstance(updated)
        // Restart runtime so new frozen ctx.config takes effect
        activeSources.remove(instanceId)?.shutdown()
        return updated
    }

    suspend fun getInstanceConfig(instanceId: String): Map<String, JsonElement> {
        val inst = store.getInstance(instanceId) ?: return emptyMap()
        val raw = secretBox.decryptFromBase64(inst.encryptedConfig)
        return (json.parseToJsonElement(raw) as? JsonObject)?.toMap() ?: emptyMap()
    }

    suspend fun deleteInstance(instanceId: String) {
        activeSources.remove(instanceId)?.shutdown()
        store.deleteInstance(instanceId)
        File(instancesDir, instanceId).deleteRecursively()
    }

    /**
     * Returns (or lazily instantiates) the [PluginMediaSource] for [instanceId].
     */
    suspend fun openSource(
        instanceId: String,
        dnsResolver: ((String) -> List<java.net.InetAddress>)? = null,
    ): PluginMediaSource {
        activeSources[instanceId]?.let { return it }
        val inst = store.getInstance(instanceId)
            ?: throw SourceNeedsSetupException("找不到这个播放源的配置，请重新添加。")
        val pkg = store.getPackage(inst.pluginId)
            ?: throw SourceNeedsSetupException("这个播放源对应的插件已卸载。")
        val manifest = ManifestParser.parseAndValidate(pkg.manifestJson)
        val pkgDir = File(pkg.packagePath)
        val mainJs = File(pkgDir, "main.js").readText(Charsets.UTF_8)
        val locale = PluginLocale.fromPackageDir(pkgDir, deviceInfo.locale)
        val localizedManifest = locale.localizeManifest(manifest)
        val config = getInstanceConfig(instanceId)

        val instDir = File(instancesDir, instanceId)
        val storage = InstanceStorage(File(instDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(instDir, "cookies.enc"), secretBox)

        val passwordKeys = manifest.passwordConfigKeys()
        val logger = PluginLogger(
            pluginId = manifest.id,
            instanceId = instanceId,
            knownSecretsProvider = {
                passwordKeys.mapNotNull { k -> (config[k] as? JsonPrimitive)?.content }
            },
        )
        val userHosts = NetworkPolicy.extractUserHosts(manifest, config)
        val policy = if (dnsResolver != null) {
            NetworkPolicy(manifest.permissions, userHosts, dnsResolver)
        } else {
            NetworkPolicy(manifest.permissions, userHosts)
        }
        val fetchGateway = PluginFetchGateway(
            policy = policy,
            cookieJar = cookieJar,
            baseClient = okHttpClient,
        )
        val runtime = PluginRuntime(
            manifest = localizedManifest,
            instanceId = instanceId,
            mainJsSource = mainJs,
            config = config,
            fetchGateway = fetchGateway,
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
            locale = locale,
            deviceInfo = deviceInfo,
            androidContext = androidContext,
        )
        val mapper = PluginProtocolMapper(policy, logger, androidContext)
        val source = PluginMediaSource(
            instanceId = instanceId,
            displayName = inst.displayName,
            manifest = localizedManifest,
            runtime = runtime,
            mapper = mapper,
            stateGuard = {
                val latestPkg = store.getPackage(manifest.id)
                if (latestPkg == null || !latestPkg.enabled) {
                    throw SourceNeedsSetupException("这个插件已停用，请先在插件管理中启用。")
                }
                if (latestPkg.needsPermissionReconfirm) {
                    throw SourceNeedsSetupException("插件更新后申请了更多权限，请先重新确认后再使用。")
                }
            },
            onNeedsAuth = {
                store.updateInstanceAuth(instanceId, "NEEDS_AUTH", null, clockMs())
            },
            onAuthSuccess = { user ->
                val userJson = user?.let {
                    buildJsonObject {
                        put("name", it.name)
                        it.avatar?.let { av -> put("avatar", av.url) }
                    }.toString()
                }
                store.updateInstanceAuth(instanceId, "AUTHENTICATED", userJson, clockMs())
            },
            onLoggedOut = {
                store.updateInstanceAuth(instanceId, "NEEDS_AUTH", null, clockMs())
            },
            androidContext = androidContext,
        )
        activeSources[instanceId] = source
        return source
    }

    suspend fun shutdownAll() {
        val list = activeSources.values.toList()
        activeSources.clear()
        list.forEach { it.shutdown() }
    }

    private suspend fun shutdownRuntimesForPlugin(pluginId: String) {
        val instances = store.listInstancesForPlugin(pluginId)
        for (inst in instances) {
            activeSources.remove(inst.instanceId)?.shutdown()
        }
    }

    private fun applyConfigDefaults(
        manifest: PluginManifest,
        provided: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (field in manifest.config) {
            val value = provided[field.key] ?: field.defaultValue
            if (value != null) {
                out[field.key] = value
            }
        }
        // Preserve any forward-compatible extra keys
        for ((k, v) in provided) {
            if (k !in out) out[k] = v
        }
        return out
    }

    private fun writeArchiveToDir(archive: PluginPackageArchive, dir: File) {
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
}
