package com.limelight.local.plugin

import android.content.Context
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.WallDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * Unpacked in-memory representation of a `.d3plugin` archive.
 *
 * Per protocol §11.4 (S9), [inspect] parses BOTH the Zip Central Directory AND
 * every Local File Header sequentially, rejecting archives with:
 * - Size above 8 MB (§11.4)
 * - Missing/corrupt EOCD or trailing data after EOCD
 * - Duplicate filenames (raw or normalized) or zip-slip paths
 * - Any mismatch between Central Directory entries and Local File Headers
 *   (entry count, filename bytes, compression method, CRC-32, sizes, or hidden
 *   unindexed local headers)
 */
data class PluginPackageArchive(
    val manifestJson: String,
    val mainJs: String,
    val iconPng: ByteArray?,
    val locales: Map<String, String>,
) {
    private data class CentralDirEntry(
        val rawName: String,
        val rawNameBytes: ByteArray,
        val normalizedName: String,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compSize: Long,
        val uncompSize: Long,
        val localHeaderOffset: Int,
    )

    companion object {
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_ENTRIES = 64
        private const val MAX_UNPACKED_BYTES = 16 * 1024 * 1024

        private const val SIG_LFH = 0x04034b50L
        private const val SIG_CDH = 0x02014b50L
        private const val SIG_EOCD = 0x06054b50L

        fun inspect(zipBytes: ByteArray): PluginPackageArchive {
            if (zipBytes.size > MAX_PACKAGE_BYTES) {
                throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
            }
            if (zipBytes.size < 22) {
                throw PackageInstallException("插件包不是有效的 zip 文件")
            }

            // 1. Parse End of Central Directory + Central Directory and validate every referenced Local File Header
            val cdEntries = parseAndVerifyCentralDirectory(zipBytes)
            val cdByRawName = LinkedHashMap<String, CentralDirEntry>(cdEntries.size)
            for (cd in cdEntries) {
                cdByRawName[cd.rawName] = cd
            }

            // 2. Walk Local File Headers sequentially via ZipInputStream and cross-check 1-to-1 against Central Directory
            var manifestJson: String? = null
            var mainJs: String? = null
            var iconPng: ByteArray? = null
            val locales = LinkedHashMap<String, String>()
            val seenRawNames = LinkedHashSet<String>()
            var totalBytes = 0L

            try {
                ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val rawName = entry.name
                        if (!seenRawNames.add(rawName)) {
                            throw PackageInstallException("插件包包含重复的 zip 条目：$rawName")
                        }
                        val cd = cdByRawName[rawName]
                            ?: throw PackageInstallException("插件包 zip 中央目录与本地文件头不一致：未登记条目 $rawName")

                        val content = readBounded(zis, MAX_UNPACKED_BYTES - totalBytes)
                        totalBytes += content.size

                        if (entry.isDirectory) {
                            if (content.isNotEmpty() || cd.uncompSize != 0L) {
                                throw PackageInstallException("插件包 zip 目录条目数据异常：$rawName")
                            }
                            continue
                        }

                        if (content.size.toLong() != cd.uncompSize) {
                            throw PackageInstallException("插件包 zip 中央目录与实际内容大小不一致：$rawName")
                        }
                        val crc = CRC32().apply { update(content) }.value
                        if (crc != cd.crc32) {
                            throw PackageInstallException("插件包 zip 校验和不一致：$rawName")
                        }

                        val norm = cd.normalizedName
                        when {
                            norm == "manifest.json" -> manifestJson = String(content, Charsets.UTF_8)
                            norm == "main.js" -> mainJs = String(content, Charsets.UTF_8)
                            norm == "icon.png" -> iconPng = content
                            norm.startsWith("locales/") && norm.endsWith(".json") -> {
                                val tag = norm.removePrefix("locales/").removeSuffix(".json")
                                if (tag.isNotEmpty() && !tag.contains('/')) {
                                    locales[tag] = String(content, Charsets.UTF_8)
                                }
                            }
                        }
                    }
                }
            } catch (e: PackageInstallException) {
                throw e
            } catch (e: Exception) {
                throw PackageInstallException("无法读取插件包 zip 内容：${e.message}", e)
            }

            if (seenRawNames.size != cdByRawName.size) {
                throw PackageInstallException("插件包 zip 中央目录与本地文件头条目数量不一致")
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

        private fun parseAndVerifyCentralDirectory(bytes: ByteArray): List<CentralDirEntry> {
            val minOffset = maxOf(0, bytes.size - 22 - 65535)
            var eocdOffset = -1
            for (pos in (bytes.size - 22) downTo minOffset) {
                if (readU32(bytes, pos) == SIG_EOCD) {
                    val commentLen = readU16(bytes, pos + 20)
                    if (pos + 22 + commentLen == bytes.size) {
                        eocdOffset = pos
                        break
                    }
                }
            }
            if (eocdOffset < 0) {
                throw PackageInstallException("插件包缺少有效的 zip 中央目录结尾记录")
            }

            val diskNo = readU16(bytes, eocdOffset + 4)
            val cdDiskNo = readU16(bytes, eocdOffset + 6)
            val entriesOnDisk = readU16(bytes, eocdOffset + 8)
            val totalEntries = readU16(bytes, eocdOffset + 10)
            val cdSize = readU32(bytes, eocdOffset + 12)
            val cdOffset = readU32(bytes, eocdOffset + 16)

            if (diskNo != 0 || cdDiskNo != 0 || entriesOnDisk != totalEntries) {
                throw PackageInstallException("不支持分卷或条目数异常的 zip 插件包")
            }
            if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                throw PackageInstallException("不支持 Zip64 格式插件包")
            }
            if (totalEntries > MAX_ENTRIES) {
                throw PackageInstallException("插件包内文件数量过多（上限 $MAX_ENTRIES 个）")
            }
            if (cdOffset + cdSize != eocdOffset.toLong()) {
                throw PackageInstallException("插件包 zip 中央目录偏移与长度异常")
            }

            val entries = ArrayList<CentralDirEntry>(totalEntries)
            val seenRaw = HashSet<String>()
            val seenNorm = HashSet<String>()
            var pos = cdOffset.toInt()

            for (i in 0 until totalEntries) {
                if (pos + 46 > eocdOffset || readU32(bytes, pos) != SIG_CDH) {
                    throw PackageInstallException("插件包 zip 中央目录记录损坏")
                }
                val flags = readU16(bytes, pos + 8)
                val method = readU16(bytes, pos + 10)
                val crc32 = readU32(bytes, pos + 16)
                val compSize = readU32(bytes, pos + 20)
                val uncompSize = readU32(bytes, pos + 24)
                val nameLen = readU16(bytes, pos + 28)
                val extraLen = readU16(bytes, pos + 30)
                val commentLen = readU16(bytes, pos + 32)
                val lfhOffset = readU32(bytes, pos + 42)

                if ((flags and 1) != 0) {
                    throw PackageInstallException("不支持加密的插件包")
                }
                if (method != 0 && method != 8) {
                    throw PackageInstallException("不支持的 zip 压缩方式：$method")
                }
                if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || lfhOffset == 0xFFFFFFFFL) {
                    throw PackageInstallException("不支持 Zip64 扩展条目")
                }

                val nameStart = pos + 46
                val nextPos = nameStart + nameLen + extraLen + commentLen
                if (nextPos > eocdOffset) {
                    throw PackageInstallException("插件包 zip 中央目录越界")
                }
                val rawNameBytes = bytes.copyOfRange(nameStart, nameStart + nameLen)
                val rawName = String(rawNameBytes, Charsets.UTF_8)
                val normName = normalizeEntryName(rawName)
                if (!seenRaw.add(rawName) || !seenNorm.add(normName)) {
                    throw PackageInstallException("插件包包含重复文件名：$rawName")
                }
                if (lfhOffset < 0 || lfhOffset + 30 > cdOffset) {
                    throw PackageInstallException("插件包 zip 本地文件头偏移越界：$rawName")
                }

                // Cross-check referenced Local File Header directly
                val lfhPos = lfhOffset.toInt()
                if (readU32(bytes, lfhPos) != SIG_LFH) {
                    throw PackageInstallException("插件包 zip 本地文件头签名无效：$rawName")
                }
                val lfhFlags = readU16(bytes, lfhPos + 6)
                val lfhMethod = readU16(bytes, lfhPos + 8)
                val lfhCrc = readU32(bytes, lfhPos + 14)
                val lfhCompSize = readU32(bytes, lfhPos + 18)
                val lfhUncompSize = readU32(bytes, lfhPos + 22)
                val lfhNameLen = readU16(bytes, lfhPos + 26)
                val lfhExtraLen = readU16(bytes, lfhPos + 28)

                if (lfhPos + 30 + lfhNameLen + lfhExtraLen > cdOffset) {
                    throw PackageInstallException("插件包 zip 本地文件头越界：$rawName")
                }
                val lfhRawNameBytes = bytes.copyOfRange(lfhPos + 30, lfhPos + 30 + lfhNameLen)
                if (!lfhRawNameBytes.contentEquals(rawNameBytes)) {
                    throw PackageInstallException("插件包 zip 中央目录与本地文件头文件名不一致：$rawName")
                }
                if (lfhMethod != method) {
                    throw PackageInstallException("插件包 zip 中央目录与本地文件头压缩方式不一致：$rawName")
                }
                val hasDataDescriptor = (lfhFlags and 0x08) != 0
                if (!hasDataDescriptor) {
                    if (lfhCrc != crc32 || lfhCompSize != compSize || lfhUncompSize != uncompSize) {
                        throw PackageInstallException("插件包 zip 中央目录与本地文件头校验信息不一致：$rawName")
                    }
                }

                entries += CentralDirEntry(
                    rawName = rawName,
                    rawNameBytes = rawNameBytes,
                    normalizedName = normName,
                    flags = flags,
                    method = method,
                    crc32 = crc32,
                    compSize = compSize,
                    uncompSize = uncompSize,
                    localHeaderOffset = lfhPos,
                )
                pos = nextPos
            }

            if (pos != eocdOffset) {
                throw PackageInstallException("插件包 zip 中央目录存在多余未解析字节")
            }
            return entries
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readU32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

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

open class PackageInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Thrown per §11.4 (S1) when an incoming package shares `pluginId` with an
 * already-installed plugin from a DIFFERENT [existingSourceOrigin]. Installing
 * it is a replacement (not an update), requires explicit user confirmation,
 * and wipes all existing instances' config/passwords, storage, and cookies.
 */
class PluginSourceMismatchException(
    val pluginId: String,
    val existingSourceOrigin: String,
    val newSourceOrigin: String,
    message: String = "已安装同 ID 插件但来源不同，替换将清除原插件的所有配置、登录信息和本地数据，请确认后继续。",
) : PackageInstallException(message)

data class InstallResult(
    val entity: InstalledPluginEntity,
    val manifest: PluginManifest,
    val isUpdate: Boolean,
    val needsPermissionReconfirm: Boolean,
    val replacedDifferentSource: Boolean = false,
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
 * - Install / upgrade `.d3plugin` archives (zip consistency check, 8 MB cap,
 *   manifest validation, `(pluginId, sourceOrigin)` identity per §11.4,
 *   semver check, minApp check, permission expansion -> `needsPermissionReconfirm`)
 * - Enable, disable, re-confirm permissions, uninstall (with runtime & data cleanup)
 * - Sync subscribed plugin repositories and install verified packages
 * - Create, configure (encrypted at rest), instantiate ([PluginMediaSource]) with
 *   per-instance mutex guarding against concurrent runtime creation, and delete
 *   plugin instances.
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
    private val instanceLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(instanceId: String): Mutex =
        instanceLocks.computeIfAbsent(instanceId) { Mutex() }

    /**
     * Installs, upgrades (same source), or replaces (different source with
     * explicit [confirmReplaceFromDifferentSource] = true) a `.d3plugin` archive.
     */
    suspend fun installFromBytes(
        zipBytes: ByteArray,
        sourceUrl: String? = null,
        repoUrl: String? = null,
        expectedId: String? = null,
        expectedVersion: String? = null,
        expectedSha256: String? = null,
        confirmReplaceFromDifferentSource: Boolean = false,
    ): InstallResult {
        if (zipBytes.size > PluginPackageArchive.MAX_PACKAGE_BYTES) {
            throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
        }
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

        val newSourceOrigin = canonicalSourceOrigin(repoUrl = repoUrl, sourceUrl = sourceUrl)
        val existing = store.getPackage(manifest.id)
        val now = clockMs()

        val differentSource = existing != null && existing.sourceOrigin != newSourceOrigin
        if (differentSource && !confirmReplaceFromDifferentSource) {
            throw PluginSourceMismatchException(
                pluginId = manifest.id,
                existingSourceOrigin = existing.sourceOrigin,
                newSourceOrigin = newSourceOrigin,
            )
        }

        val isUpdate = existing != null && !differentSource
        var needsReconfirm = false
        var approvedPermsJson = manifest.permissions.toJsonString()

        if (isUpdate) {
            val prev = existing
            val oldVer = SemVer.parse(prev.version)
            val newVer = SemVer.parse(manifest.version)
            if (oldVer != null && newVer != null && newVer <= oldVer) {
                throw PackageInstallException(
                    "更新版本号（${manifest.version}）必须大于已安装版本（${prev.version}）",
                )
            }
            val prevApproved = PluginPermissions.fromJsonString(prev.approvedPermissionsJson)
            if (manifest.permissions.isExpandedFrom(prevApproved)) {
                needsReconfirm = true
                approvedPermsJson = prev.approvedPermissionsJson
            } else {
                needsReconfirm = prev.needsPermissionReconfirm
                if (!needsReconfirm) {
                    approvedPermsJson = manifest.permissions.toJsonString()
                }
            }
        }

        // Write package files into a versioned directory before mutating DB state
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

        if (differentSource) {
            // §11.4 (S1): Replacing a plugin from a different source MUST wipe all
            // existing instances' configs/passwords, storage, and cookies.
            wipeAllInstancesForPlugin(manifest.id)
            store.deletePackage(manifest.id)
        } else {
            // Dispose any running runtimes for this plugin so they reload the updated code
            shutdownRuntimesForPlugin(manifest.id)
        }

        val entity = InstalledPluginEntity(
            pluginId = manifest.id,
            name = manifest.name,
            version = manifest.version,
            author = manifest.author,
            description = manifest.description,
            manifestJson = archive.manifestJson,
            packagePath = targetDir.absolutePath,
            sourceUrl = if (differentSource) sourceUrl else (sourceUrl ?: existing?.sourceUrl),
            repoUrl = if (differentSource) repoUrl else (repoUrl ?: existing?.repoUrl),
            sourceOrigin = newSourceOrigin,
            sha256 = actualSha,
            enabled = if (differentSource) true else (existing?.enabled ?: true),
            needsPermissionReconfirm = needsReconfirm,
            approvedPermissionsJson = approvedPermsJson,
            installedAt = if (differentSource) now else (existing?.installedAt ?: now),
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
            replacedDifferentSource = differentSource,
        )
    }

    suspend fun installFromFile(
        file: File,
        confirmReplaceFromDifferentSource: Boolean = false,
    ): InstallResult {
        if (file.length() > PluginPackageArchive.MAX_PACKAGE_BYTES) {
            throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
        }
        return installFromBytes(
            zipBytes = file.readBytes(),
            sourceUrl = file.toURI().toString(),
            confirmReplaceFromDifferentSource = confirmReplaceFromDifferentSource,
        )
    }

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

    suspend fun installFromRepository(
        repoUrl: String,
        entry: RepoPluginEntry,
        confirmReplaceFromDifferentSource: Boolean = false,
    ): InstallResult {
        val download = repoClient.downloadVerifiedPackage(entry)
        return installFromBytes(
            zipBytes = download.bytes,
            sourceUrl = download.sourceUrl,
            repoUrl = repoUrl,
            expectedId = entry.id,
            expectedVersion = entry.version,
            expectedSha256 = entry.sha256,
            confirmReplaceFromDifferentSource = confirmReplaceFromDifferentSource,
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
        wipeAllInstancesForPlugin(pluginId)
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
    ): PluginInstanceEntity = lockFor(instanceId).withLock {
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
        updated
    }

    suspend fun getInstanceConfig(instanceId: String): Map<String, JsonElement> {
        val inst = store.getInstance(instanceId) ?: return emptyMap()
        val raw = secretBox.decryptFromBase64(inst.encryptedConfig)
        return (json.parseToJsonElement(raw) as? JsonObject)?.toMap() ?: emptyMap()
    }

    suspend fun deleteInstance(instanceId: String) {
        lockFor(instanceId).withLock {
            activeSources.remove(instanceId)?.shutdown()
            store.deleteInstance(instanceId)
            File(instancesDir, instanceId).deleteRecursively()
        }
        instanceLocks.remove(instanceId)
    }

    /**
     * Returns (or lazily instantiates) the [PluginMediaSource] for [instanceId].
     * Serialized per [instanceId] via a coroutine [Mutex] so concurrent callers
     * never instantiate duplicate QuickJS runtimes or leak native memory.
     */
    suspend fun openSource(
        instanceId: String,
        dnsResolver: ((String) -> List<java.net.InetAddress>)? = null,
    ): PluginMediaSource {
        activeSources[instanceId]?.let { return it }
        return lockFor(instanceId).withLock {
            activeSources[instanceId]?.let { return@withLock it }
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
                okHttpClient = okHttpClient,
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
            source
        }
    }

    suspend fun shutdownAll() {
        val ids = activeSources.keys.toList()
        for (id in ids) {
            lockFor(id).withLock {
                activeSources.remove(id)?.shutdown()
            }
        }
    }

    private suspend fun shutdownRuntimesForPlugin(pluginId: String) {
        val instances = store.listInstancesForPlugin(pluginId)
        for (inst in instances) {
            lockFor(inst.instanceId).withLock {
                activeSources.remove(inst.instanceId)?.shutdown()
            }
        }
    }

    private suspend fun wipeAllInstancesForPlugin(pluginId: String) {
        val instances = store.listInstancesForPlugin(pluginId)
        for (inst in instances) {
            lockFor(inst.instanceId).withLock {
                activeSources.remove(inst.instanceId)?.shutdown()
                store.deleteInstance(inst.instanceId)
                File(instancesDir, inst.instanceId).deleteRecursively()
            }
            instanceLocks.remove(inst.instanceId)
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

    companion object {
        /**
         * Computes canonical source identity for `(pluginId, sourceOrigin)` (§11.4):
         * - Repository installs identify by normalized repository index URL
         * - Direct HTTP(S) installs identify by URL origin (`scheme://host:port`)
         * - Local file installs identify as `"local-file"`
         */
        fun canonicalSourceOrigin(repoUrl: String?, sourceUrl: String?): String {
            if (!repoUrl.isNullOrBlank()) {
                val parsed = repoUrl.trim().toHttpUrlOrNull()
                return if (parsed != null) {
                    val portPart = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
                    val path = parsed.encodedPath.trimEnd('/').ifEmpty { "/" }
                    "repo:${parsed.scheme}://${NetworkPolicy.canonicalizeHost(parsed.host)}$portPart$path"
                } else {
                    "repo:${repoUrl.trim()}"
                }
            }
            if (!sourceUrl.isNullOrBlank()) {
                val trimmed = sourceUrl.trim()
                val parsed = trimmed.toHttpUrlOrNull()
                if (parsed != null) {
                    return "url:${NetworkPolicy.originKey(parsed)}"
                }
                if (trimmed.startsWith("file:", ignoreCase = true)) {
                    return "local-file"
                }
                return "other:$trimmed"
            }
            return "local-file"
        }
    }
}
