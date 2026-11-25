package com.limelight.local.plugin

import android.content.Context
import androidx.room.withTransaction
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.WallDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Unpacked in-memory representation of a `.d3plugin` archive.
 *
 * Per protocol §11.4 (S9), [inspect] parses the Zip Central Directory AND walks
 * continuously from byte offset `0` to `cdOffset` with ZERO gaps and ZERO
 * unindexed Local File Headers, matching every LFH 1-to-1 by exact byte offset
 * against the Central Directory and decompressing ONLY from Central Directory
 * offsets/sizes (never via `ZipInputStream`).
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

    private data class ParsedCentralDirectory(
        val cdOffset: Int,
        val entriesByOffset: Map<Int, CentralDirEntry>,
    )

    companion object {
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_ENTRIES = 64
        private const val MAX_UNPACKED_BYTES = 16 * 1024 * 1024

        private const val SIG_LFH = 0x04034b50L
        private const val SIG_DD = 0x08074b50L
        private const val SIG_CDH = 0x02014b50L
        private const val SIG_EOCD = 0x06054b50L

        fun inspect(zipBytes: ByteArray): PluginPackageArchive {
            if (zipBytes.size > MAX_PACKAGE_BYTES) {
                throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
            }
            if (zipBytes.size < 22) {
                throw PackageInstallException("插件包不是有效的 zip 文件")
            }

            // 1. Parse End of Central Directory + Central Directory strictly (zero trailing bytes)
            val parsedCd = parseCentralDirectory(zipBytes)
            val cdOffset = parsedCd.cdOffset
            val cdByOffset = parsedCd.entriesByOffset

            // 2. Walk EVERY Local File Header continuously from byte 0 to cdOffset with zero gaps
            var manifestJson: String? = null
            var mainJs: String? = null
            var iconPng: ByteArray? = null
            val locales = LinkedHashMap<String, String>()
            var lfhPos = 0
            var walkedCount = 0
            var totalUnpacked = 0L

            while (lfhPos < cdOffset) {
                if (lfhPos + 30 > cdOffset || readU32(zipBytes, lfhPos) != SIG_LFH) {
                    throw PackageInstallException("插件包 zip 本地文件头流存在非法间隙或损坏字节")
                }
                val cd = cdByOffset[lfhPos]
                    ?: throw PackageInstallException("插件包 zip 存在未在中央目录登记的本地文件头（偏移 $lfhPos）")

                val lfhFlags = readU16(zipBytes, lfhPos + 6)
                val lfhMethod = readU16(zipBytes, lfhPos + 8)
                val lfhCrc = readU32(zipBytes, lfhPos + 14)
                val lfhCompSize = readU32(zipBytes, lfhPos + 18)
                val lfhUncompSize = readU32(zipBytes, lfhPos + 22)
                val lfhNameLen = readU16(zipBytes, lfhPos + 26)
                val lfhExtraLen = readU16(zipBytes, lfhPos + 28)

                val dataStartLong = lfhPos.toLong() + 30L + lfhNameLen.toLong() + lfhExtraLen.toLong()
                if (dataStartLong > cdOffset.toLong()) {
                    throw PackageInstallException("插件包 zip 本地文件头越界：${cd.rawName}")
                }
                val dataStart = dataStartLong.toInt()
                val lfhNameBytes = zipBytes.copyOfRange(lfhPos + 30, lfhPos + 30 + lfhNameLen)
                if (!lfhNameBytes.contentEquals(cd.rawNameBytes)) {
                    throw PackageInstallException("插件包 zip 中央目录与本地文件头文件名不一致：${cd.rawName}")
                }
                if (lfhMethod != cd.method) {
                    throw PackageInstallException("插件包 zip 中央目录与本地文件头压缩方式不一致：${cd.rawName}")
                }

                val hasDataDescriptor = (lfhFlags and 0x08) != 0
                if (!hasDataDescriptor) {
                    if (lfhCrc != cd.crc32 || lfhCompSize != cd.compSize || lfhUncompSize != cd.uncompSize) {
                        throw PackageInstallException("插件包 zip 中央目录与本地文件头校验信息不一致：${cd.rawName}")
                    }
                }

                val dataEndLong = dataStartLong + cd.compSize
                if (dataEndLong > cdOffset.toLong()) {
                    throw PackageInstallException("插件包 zip 条目压缩数据越界：${cd.rawName}")
                }
                val dataEnd = dataEndLong.toInt()

                var nextLfhPos = dataEnd
                if (hasDataDescriptor) {
                    if (nextLfhPos + 12 > cdOffset) {
                        throw PackageInstallException("插件包 zip 数据描述符不完整：${cd.rawName}")
                    }
                    if (readU32(zipBytes, nextLfhPos) == SIG_DD) {
                        nextLfhPos += 4
                    }
                    if (nextLfhPos + 12 > cdOffset) {
                        throw PackageInstallException("插件包 zip 数据描述符不完整：${cd.rawName}")
                    }
                    val ddCrc = readU32(zipBytes, nextLfhPos)
                    val ddComp = readU32(zipBytes, nextLfhPos + 4)
                    val ddUncomp = readU32(zipBytes, nextLfhPos + 8)
                    if (ddCrc != cd.crc32 || ddComp != cd.compSize || ddUncomp != cd.uncompSize) {
                        throw PackageInstallException("插件包 zip 数据描述符与中央目录不一致：${cd.rawName}")
                    }
                    nextLfhPos += 12
                }

                val remaining = MAX_UNPACKED_BYTES - totalUnpacked
                if (cd.uncompSize > remaining) {
                    throw PackageInstallException("插件包解压后超过大小限制")
                }

                // Decompress strictly from Central Directory offset/size
                val content = when (cd.method) {
                    0 -> zipBytes.copyOfRange(dataStart, dataEnd)
                    8 -> inflateRawBounded(zipBytes, dataStart, cd.compSize.toInt(), remaining, cd.rawName)
                    else -> throw PackageInstallException("不支持的 zip 压缩方式：${cd.method}")
                }
                totalUnpacked += content.size

                if (cd.rawName.endsWith("/")) {
                    if (content.isNotEmpty() || cd.uncompSize != 0L) {
                        throw PackageInstallException("插件包 zip 目录条目数据异常：${cd.rawName}")
                    }
                } else {
                    if (content.size.toLong() != cd.uncompSize) {
                        throw PackageInstallException("插件包 zip 中央目录与实际内容大小不一致：${cd.rawName}")
                    }
                    val crc = CRC32().apply { update(content) }.value
                    if (crc != cd.crc32) {
                        throw PackageInstallException("插件包 zip 校验和不一致：${cd.rawName}")
                    }
                    val norm = cd.normalizedName
                    when {
                        norm == "manifest.json" -> manifestJson = decodeStrictUtf8(content, "manifest.json")
                        norm == "main.js" -> mainJs = decodeStrictUtf8(content, "main.js")
                        norm == "icon.png" -> iconPng = content
                        norm.startsWith("locales/") && norm.endsWith(".json") -> {
                            val tag = norm.removePrefix("locales/").removeSuffix(".json")
                            if (tag.isNotEmpty() && !tag.contains('/')) {
                                locales[tag] = decodeStrictUtf8(content, norm)
                            }
                        }
                    }
                }

                walkedCount += 1
                lfhPos = nextLfhPos
            }

            if (walkedCount != cdByOffset.size) {
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

        private fun decodeStrictUtf8(bytes: ByteArray, label: String): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw PackageInstallException("插件包包含非法 UTF-8 字节（$label）", e)
            }
        }

        private fun parseCentralDirectory(bytes: ByteArray): ParsedCentralDirectory {
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

            val entriesByOffset = LinkedHashMap<Int, CentralDirEntry>(totalEntries)
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
                val rawName = decodeStrictUtf8(rawNameBytes, "zip entry name")
                val normName = normalizeEntryName(rawName)
                if (!seenRaw.add(rawName) || !seenNorm.add(normName)) {
                    throw PackageInstallException("插件包包含重复文件名：$rawName")
                }
                if (lfhOffset < 0 || lfhOffset + 30 > cdOffset) {
                    throw PackageInstallException("插件包 zip 本地文件头偏移越界：$rawName")
                }
                val lfhPos = lfhOffset.toInt()
                if (entriesByOffset.containsKey(lfhPos)) {
                    throw PackageInstallException("插件包 zip 多个中央目录条目指向同一本地文件头：$rawName")
                }

                entriesByOffset[lfhPos] = CentralDirEntry(
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
            return ParsedCentralDirectory(cdOffset = cdOffset.toInt(), entriesByOffset = entriesByOffset)
        }

        private fun inflateRawBounded(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            maxOutputBytes: Long,
            entryName: String,
        ): ByteArray {
            val inflater = Inflater(/* nowrap = */ true)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            try {
                inflater.setInput(bytes, offset, length)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count > 0) {
                        total += count
                        if (total > maxOutputBytes) {
                            throw PackageInstallException("插件包解压后超过大小限制")
                        }
                        out.write(buf, 0, count)
                    } else if (inflater.needsInput() || inflater.needsDictionary()) {
                        break
                    }
                }
                if (!inflater.finished() || inflater.remaining != 0) {
                    throw PackageInstallException("插件包 zip 压缩数据损坏或存在尾随字节：$entryName")
                }
                return out.toByteArray()
            } catch (e: DataFormatException) {
                throw PackageInstallException("无法解压插件包条目 $entryName：${e.message}", e)
            } finally {
                inflater.end()
            }
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
 * Pre-install confirmation summary returned by [PluginPackageManager.previewPackage]
 * so the install confirmation UI can show requested permissions, source-change
 * replacement warnings, and the explicit wildcard-LAN access warning (§7, §11.4).
 */
data class PluginInstallPreview(
    val manifest: PluginManifest,
    val sha256: String,
    val sizeBytes: Int,
    val sourceOrigin: String,
    val isUpdate: Boolean,
    val requiresReplacementConfirmation: Boolean,
    val needsPermissionReconfirm: Boolean,
    val allowsWildcardLanAccess: Boolean,
    val permissionWarnings: List<String>,
    val existingSourceOrigin: String? = null,
) {
    val isDevSource: Boolean get() = PluginPackageManager.isDevOrigin(sourceOrigin)
    val isFileSource: Boolean get() = PluginPackageManager.isFileOrigin(sourceOrigin)
    val isDevAddressSwitch: Boolean
        get() = existingSourceOrigin != null &&
            PluginPackageManager.isDevOrigin(existingSourceOrigin) &&
            PluginPackageManager.isDevOrigin(sourceOrigin) &&
            existingSourceOrigin != sourceOrigin
}

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

    /**
     * Atomically deletes the existing package row (cascading all its instances) and inserts the
     * replacement [entity] within a single database transaction so cross-source replacement never
     * leaves a missing-plugin state on crash or cancellation.
     */
    suspend fun replacePackageAtomic(pluginId: String, entity: InstalledPluginEntity)

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
    override suspend fun replacePackageAtomic(pluginId: String, entity: InstalledPluginEntity) {
        db.withTransaction {
            db.pluginPackages().delete(pluginId)
            db.pluginPackages().upsert(entity)
        }
    }

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
    override suspend fun replacePackageAtomic(pluginId: String, entity: InstalledPluginEntity) = synchronized(this) {
        packages.remove(pluginId)
        instances.entries.removeAll { it.value.pluginId == pluginId }
        packages[entity.pluginId] = entity
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
    private val pluginLocks = ConcurrentHashMap<String, Mutex>()
    private val instanceLocks = ConcurrentHashMap<String, Mutex>()
    private val activeStagingDirs = ConcurrentHashMap.newKeySet<String>()
    private val activeProbeDirs = ConcurrentHashMap.newKeySet<String>()

    /** Deterministic cancellation-injection hooks used strictly by unit tests. */
    @Volatile
    internal var afterUpsertBeforeShutdownHookForTesting: (suspend () -> Unit)? = null
    @Volatile
    internal var afterWipeBeforeReplaceHookForTesting: (suspend () -> Unit)? = null

    /**
     * Lock ordering invariant (deadlock-free): ALWAYS acquire [pluginLockFor] BEFORE [lockFor].
     * Never acquire [pluginLockFor] while holding [lockFor].
     */
    private fun pluginLockFor(pluginId: String): Mutex =
        pluginLocks.computeIfAbsent(pluginId) { Mutex() }

    private fun lockFor(instanceId: String): Mutex =
        instanceLocks.computeIfAbsent(instanceId) { Mutex() }

    /**
     * Startup / maintenance sweep cleaning leftover `.probe-*` directories, orphaned `.staging-*`
     * directories from killed processes, and unreferenced version directories left if the process
     * died between staging rename and Room upsert. Never touches active in-flight directories.
     */
    suspend fun cleanupOrphanedFiles() {
        rootDir.listFiles { f -> f.isDirectory && f.name.startsWith(".probe-") }?.forEach { probeDir ->
            if (probeDir.name !in activeProbeDirs) {
                runCatching { probeDir.deleteRecursively() }
            }
        }
        val pluginDirs = packagesDir.listFiles { f -> f.isDirectory } ?: return
        for (pluginDir in pluginDirs) {
            val pluginId = pluginDir.name
            pluginLockFor(pluginId).withLock {
                val pkg = store.getPackage(pluginId)
                if (pkg == null) {
                    val hasActiveStaging = pluginDir.listFiles()?.any { it.name in activeStagingDirs } == true
                    if (!hasActiveStaging) {
                        runCatching { pluginDir.deleteRecursively() }
                    }
                } else {
                    val activePath = runCatching { File(pkg.packagePath).canonicalPath }
                        .getOrDefault(File(pkg.packagePath).absolutePath)
                    pluginDir.listFiles()?.forEach { child ->
                        if (child.name in activeStagingDirs) return@forEach
                        val childPath = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                        if (childPath != activePath) {
                            runCatching { child.deleteRecursively() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Inspects a `.d3plugin` archive before installation and returns a
     * [PluginInstallPreview] for the install confirmation UI (including
     * [PluginInstallPreview.allowsWildcardLanAccess] and human-readable
     * permission warnings).
     */
    suspend fun previewPackage(
        zipBytes: ByteArray,
        sourceUrl: String? = null,
        repoUrl: String? = null,
        devServerUrl: String? = null,
    ): PluginInstallPreview {
        if (zipBytes.size > PluginPackageArchive.MAX_PACKAGE_BYTES) {
            throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
        }
        val actualSha = PluginRepositoryClient.sha256Hex(zipBytes)
        val archive = PluginPackageArchive.inspect(zipBytes)
        val manifest = ManifestParser.parseAndValidate(archive.manifestJson)
        if (manifest.minApp != null) {
            val reqApp = SemVer.parse(manifest.minApp)
            val curApp = SemVer.parse(appVersion)
            if (reqApp != null && curApp != null && curApp < reqApp) {
                throw PackageInstallException("此插件需要 depth3d ${manifest.minApp} 及以上版本（当前版本 $appVersion）")
            }
        }
        val newSourceOrigin = canonicalSourceOrigin(
            repoUrl = repoUrl,
            sourceUrl = sourceUrl,
            devServerUrl = devServerUrl,
        )
        val existing = store.getPackage(manifest.id)
        val differentSource = existing != null && isDifferentSource(existing.sourceOrigin, newSourceOrigin)
        val devAddressSwitch = existing != null &&
            isDevOrigin(existing.sourceOrigin) &&
            isDevOrigin(newSourceOrigin) &&
            existing.sourceOrigin != newSourceOrigin
        val isUpdate = existing != null && !differentSource
        val needsReconfirm = if (isUpdate || devAddressSwitch) {
            val prevApproved = PluginPermissions.fromJsonString(existing.approvedPermissionsJson)
            manifest.permissions.isExpandedFrom(prevApproved) || existing.needsPermissionReconfirm
        } else {
            false
        }
        val warnings = buildList {
            if (manifest.permissions.allowsWildcardLanAccess) {
                add(WILDCARD_LAN_WARNING)
            }
            if (devAddressSwitch) {
                add("开发服务器地址已变化，确认后将切换来源地址并保留现有播放源、配置和本地数据；已保存的登录信息将交给新地址提供的代码，请确认该地址可信。")
            } else if (differentSource) {
                add("已安装同 ID 插件但来源不同，继续安装将替换原插件并清除其所有配置、登录信息和本地数据。")
            }
        }
        return PluginInstallPreview(
            manifest = manifest,
            sha256 = actualSha,
            sizeBytes = zipBytes.size,
            sourceOrigin = newSourceOrigin,
            isUpdate = isUpdate,
            requiresReplacementConfirmation = differentSource,
            needsPermissionReconfirm = needsReconfirm,
            allowsWildcardLanAccess = manifest.permissions.allowsWildcardLanAccess,
            permissionWarnings = warnings,
            existingSourceOrigin = existing?.sourceOrigin,
        )
    }

    /**
     * Installs, upgrades (same source), reloads (same dev server, allowing same version with
     * new SHA-256), or replaces (different source with explicit [confirmReplaceFromDifferentSource] = true)
     * a `.d3plugin` archive.
     *
     * When [confirmExpandedPermissions] is true (user already reviewed & confirmed expanded
     * permissions on the install card), permissions are approved atomically inside the same
     * per-plugin lock and DB commit so no TOCTOU gap exists between install and permission approval.
     */
    suspend fun installFromBytes(
        zipBytes: ByteArray,
        sourceUrl: String? = null,
        repoUrl: String? = null,
        expectedId: String? = null,
        expectedVersion: String? = null,
        expectedSha256: String? = null,
        confirmReplaceFromDifferentSource: Boolean = false,
        devServerUrl: String? = null,
        confirmExpandedPermissions: Boolean = false,
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
                throw PackageInstallException("此插件需要 depth3d ${manifest.minApp} 及以上版本（当前版本 $appVersion）")
            }
        }

        val newSourceOrigin = canonicalSourceOrigin(
            repoUrl = repoUrl,
            sourceUrl = sourceUrl,
            devServerUrl = devServerUrl,
        )
        val isDevSource = isDevOrigin(newSourceOrigin)

        return pluginLockFor(manifest.id).withLock {
            val existing = store.getPackage(manifest.id)
            val now = clockMs()

            val differentSource = existing != null && isDifferentSource(existing.sourceOrigin, newSourceOrigin)
            val devAddressSwitch = existing != null &&
                isDevOrigin(existing.sourceOrigin) &&
                isDevOrigin(newSourceOrigin) &&
                existing.sourceOrigin != newSourceOrigin
            if (differentSource && !confirmReplaceFromDifferentSource) {
                throw PluginSourceMismatchException(
                    pluginId = manifest.id,
                    existingSourceOrigin = existing.sourceOrigin,
                    newSourceOrigin = newSourceOrigin,
                )
            }

            val shouldWipeInstances = differentSource && !devAddressSwitch
            val isUpdate = existing != null && !differentSource
            var needsReconfirm = false
            var approvedPermsJson = manifest.permissions.toJsonString()

            if (isUpdate || devAddressSwitch) {
                val prev = existing
                if (isUpdate && !isDevSource) {
                    val oldVer = SemVer.parse(prev.version)
                    val newVer = SemVer.parse(manifest.version)
                    if (oldVer != null && newVer != null && newVer <= oldVer) {
                        throw PackageInstallException(
                            "更新版本号（${manifest.version}）必须大于已安装版本（${prev.version}）",
                        )
                    }
                }
                val prevApproved = PluginPermissions.fromJsonString(prev.approvedPermissionsJson)
                if (manifest.permissions.isExpandedFrom(prevApproved)) {
                    if (confirmExpandedPermissions) {
                        needsReconfirm = false
                        approvedPermsJson = manifest.permissions.toJsonString()
                    } else {
                        needsReconfirm = true
                        approvedPermsJson = prev.approvedPermissionsJson
                    }
                } else {
                    needsReconfirm = if (confirmExpandedPermissions) false else prev.needsPermissionReconfirm
                    if (!needsReconfirm) {
                        approvedPermsJson = manifest.permissions.toJsonString()
                    }
                }
            }

            // Critical section from staging write through DB commit, runtime invalidation, and
            // post-commit cleanup runs under NonCancellable so cancelling a busy dialog or screen
            // scope can NEVER split DB state, cached runtimes, or on-disk package directories.
            withContext(NonCancellable) {
                val pluginRootDir = File(packagesDir, manifest.id)
                pluginRootDir.mkdirs()

                val existingPath = existing?.packagePath?.let { File(it).absolutePath }
                val baseDir = File(pluginRootDir, manifest.version)
                val targetDir = if (existingPath != null && baseDir.absolutePath == existingPath) {
                    val sha8 = actualSha.take(8).lowercase(java.util.Locale.ROOT)
                    val shaDir = File(pluginRootDir, "${manifest.version}-$sha8")
                    if (shaDir.absolutePath == existingPath) {
                        File(pluginRootDir, "${manifest.version}-$sha8-${UUID.randomUUID().toString().take(6)}")
                    } else {
                        shaDir
                    }
                } else {
                    baseDir
                }

                val stagingName = ".staging-${UUID.randomUUID()}"
                val stagingDir = File(pluginRootDir, stagingName)
                activeStagingDirs.add(stagingName)
                try {
                    writeArchiveToDir(archive, stagingDir)
                    if (targetDir.exists() && targetDir.absolutePath != existingPath) {
                        targetDir.deleteRecursively()
                    }
                    if (!stagingDir.renameTo(targetDir)) {
                        stagingDir.copyRecursively(targetDir, overwrite = true)
                        stagingDir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    runCatching { stagingDir.deleteRecursively() }
                    throw PackageInstallException("写入插件文件失败：${e.message}", e)
                } finally {
                    activeStagingDirs.remove(stagingName)
                }

                val effectiveSourceUrl = devServerUrl ?: sourceUrl
                val entity = InstalledPluginEntity(
                    pluginId = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    author = manifest.author,
                    description = manifest.description,
                    manifestJson = archive.manifestJson,
                    packagePath = targetDir.absolutePath,
                    sourceUrl = if (differentSource) effectiveSourceUrl else (effectiveSourceUrl ?: existing?.sourceUrl),
                    repoUrl = if (differentSource) repoUrl else (repoUrl ?: existing?.repoUrl),
                    sourceOrigin = newSourceOrigin,
                    sha256 = actualSha,
                    enabled = if (shouldWipeInstances) true else (existing?.enabled ?: true),
                    needsPermissionReconfirm = needsReconfirm,
                    approvedPermissionsJson = approvedPermsJson,
                    installedAt = if (shouldWipeInstances) now else (existing?.installedAt ?: now),
                    updatedAt = now,
                )

                if (shouldWipeInstances) {
                    // §11.4 (S1): Replacing a plugin from a different source (non dev<->dev) MUST wipe
                    // all existing instances' runtimes, configs/passwords, storage, and cookies.
                    // Shutdown active runtimes first, then execute delete (CASCADE instances) + upsert
                    // atomically inside ONE Room transaction, then delete old instance directories.
                    val oldInstances = store.listInstancesForPlugin(manifest.id)
                    for (inst in oldInstances) {
                        lockFor(inst.instanceId).withLock {
                            activeSources.remove(inst.instanceId)?.shutdown()
                        }
                    }
                    afterWipeBeforeReplaceHookForTesting?.invoke()
                    store.replacePackageAtomic(manifest.id, entity)
                    for (inst in oldInstances) {
                        lockFor(inst.instanceId).withLock {
                            runCatching { File(instancesDir, inst.instanceId).deleteRecursively() }
                        }
                        instanceLocks.remove(inst.instanceId)
                    }
                } else {
                    // Commit new package metadata FIRST, THEN destroy cached runtimes — all serialized
                    // under pluginLockFor(manifest.id) + NonCancellable so concurrent openSource() or
                    // caller cancellation can NEVER leave an obsolete runtime pointing at old code.
                    store.upsertPackage(entity)
                    afterUpsertBeforeShutdownHookForTesting?.invoke()
                    shutdownRuntimesForPlugin(manifest.id)
                }

                // Clean up superseded version dirs and any orphaned staging/version dirs AFTER commit
                val keepCanonical = runCatching { targetDir.canonicalPath }.getOrDefault(targetDir.absolutePath)
                pluginRootDir.listFiles()?.forEach { child ->
                    if (child.name in activeStagingDirs) return@forEach
                    val childCanonical = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                    if (childCanonical != keepCanonical) {
                        runCatching { child.deleteRecursively() }
                    }
                }

                InstallResult(
                    entity = entity,
                    manifest = manifest,
                    isUpdate = isUpdate,
                    needsPermissionReconfirm = needsReconfirm,
                    replacedDifferentSource = differentSource,
                )
            }
        }
    }

    /**
     * Collects known secret values for [pluginId] across all its instances:
     * 1. Configured `password` fields declared in `manifest.json`
     * 2. All values stored in each instance's cookie jar (live in-memory + persisted `cookies.enc`)
     * 3. Token/credential-like values stored in each instance's `ctx.storage` (live + `storage.enc`)
     */
    internal suspend fun collectKnownSecretsForPlugin(pluginId: String): List<String> {
        val pkg = store.getPackage(pluginId) ?: return emptyList()
        return collectKnownSecretsForPluginWithPackage(pluginId, pkg)
    }

    private suspend fun collectKnownSecretsForPluginWithPackage(
        pluginId: String,
        pkg: InstalledPluginEntity,
    ): List<String> {
        val manifest = runCatching { ManifestParser.parseAndValidate(pkg.manifestJson) }.getOrNull()
        val passwordKeys = manifest?.passwordConfigKeys().orEmpty()
        val out = LinkedHashSet<String>()
        for (inst in store.listInstancesForPlugin(pluginId)) {
            if (passwordKeys.isNotEmpty()) {
                val cfg = runCatching { getInstanceConfig(inst.instanceId) }.getOrNull()
                if (cfg != null) {
                    for (k in passwordKeys) {
                        val v = (cfg[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!v.isNullOrEmpty()) out += v
                    }
                }
            }
            activeSources[inst.instanceId]?.let { live ->
                runCatching { out += live.collectLiveSecretValues() }
            }
            val instDir = File(instancesDir, inst.instanceId)
            val cookieFile = File(instDir, "cookies.enc")
            if (cookieFile.isFile) {
                runCatching { out += PluginCookieJar(cookieFile, secretBox).allCookieValues() }
            }
            val storageFile = File(instDir, "storage.enc")
            if (storageFile.isFile) {
                runCatching { out += InstanceStorage(storageFile, secretBox).allSecretLikeValues() }
            }
        }
        return out.toList()
    }

    /**
     * Redacts a raw technical error string for Developer Mode using the shared [PluginLogger.redact]
     * rules plus all known secrets (password fields, cookies, and token-like `ctx.storage` values)
     * for [pluginId] (or across all installed plugins when [pluginId] is null).
     */
    suspend fun redactDeveloperError(pluginId: String?, rawError: String): String {
        val secrets = if (!pluginId.isNullOrBlank()) {
            collectKnownSecretsForPlugin(pluginId)
        } else {
            val all = LinkedHashSet<String>()
            for (pkg in store.listPackages()) {
                all += collectKnownSecretsForPluginWithPackage(pkg.pluginId, pkg)
            }
            all.toList()
        }
        return PluginLogger.redact(rawError, secrets)
    }

    /**
     * Evaluates `main.js` ES module loading (syntax + top-level statements + default export) in
     * a ZERO-capability QuickJS probe sandbox (no `ctx`, no host bridge, no network, no storage,
     * no cookies, no files on disk) without running `init(ctx)`.
     *
     * **Security note**: ES module import executes module top-level code, so callers MUST invoke
     * this ONLY AFTER explicit user confirmation (or during a same-origin, non-permission-expanded
     * dev reload) and NEVER during unconfirmed package preview.
     *
     * Returns the redacted raw technical error (with line number when available) for Developer
     * Mode, or `null` on success.
     */
    suspend fun probeArchiveModuleLoad(zipBytes: ByteArray): String? {
        val archive = PluginPackageArchive.inspect(zipBytes)
        val manifest = ManifestParser.parseAndValidate(archive.manifestJson)
        val secrets = collectKnownSecretsForPlugin(manifest.id)
        val logger = PluginLogger(
            pluginId = manifest.id,
            instanceId = "dev-check",
            knownSecretsProvider = { secrets },
        )
        return try {
            PluginRuntime.probeModuleLoadNoCapabilities(archive.mainJs)
            null
        } catch (t: Throwable) {
            val redacted = PluginLogger.redact(extractRawTechnicalError(t), secrets)
            logger.error("main.js load error: $redacted")
            redacted
        }
    }

    /**
     * Verifies an installed plugin loads and initializes cleanly (`main.js` + optional `init(ctx)`).
     * Reads package metadata & archive files under [pluginLockFor] so concurrent reloads cannot
     * delete `pkgDir` mid-read and trigger spurious `FileNotFoundException`s.
     * When instances exist, verifies the first enabled instance with its real config/storage;
     * otherwise probes in a temporary runtime (ignoring `NeedsSetup` / `NeedsAuth` since no config
     * is filled yet). Returns `null` on success or the redacted raw technical error string for
     * Developer Mode (using [PluginLogger.redact] + instance password/cookie/storage secrets).
     */
    suspend fun verifyInstalledPluginLoad(
        pluginId: String,
        dnsResolver: ((String) -> List<java.net.InetAddress>)? = null,
    ): String? {
        data class ProbeSnapshot(
            val manifest: PluginManifest,
            val mainJs: String,
            val locale: PluginLocale,
            val secrets: List<String>,
        )

        var firstInstanceId: String? = null
        var probeSnapshot: ProbeSnapshot? = null

        pluginLockFor(pluginId).withLock {
            val pkg = store.getPackage(pluginId) ?: return null
            if (!pkg.enabled || pkg.needsPermissionReconfirm) return null
            val secrets = collectKnownSecretsForPluginWithPackage(pluginId, pkg)
            val instances = store.listInstancesForPlugin(pluginId).filter { it.enabled }
            if (instances.isNotEmpty()) {
                firstInstanceId = instances.first().instanceId
            } else {
                val manifest = ManifestParser.parseAndValidate(pkg.manifestJson)
                val pkgDir = File(pkg.packagePath)
                val mainJs = File(pkgDir, "main.js").readText(Charsets.UTF_8)
                val locale = PluginLocale.fromPackageDir(pkgDir, deviceInfo.locale)
                probeSnapshot = ProbeSnapshot(manifest, mainJs, locale, secrets)
            }
        }

        if (firstInstanceId != null) {
            var lastNotFound: java.io.FileNotFoundException? = null
            for (attempt in 0..1) {
                val targetId = if (attempt == 0) {
                    firstInstanceId
                } else {
                    pluginLockFor(pluginId).withLock {
                        val pkg = store.getPackage(pluginId) ?: return null
                        if (!pkg.enabled || pkg.needsPermissionReconfirm) return null
                        store.listInstancesForPlugin(pluginId).firstOrNull { it.enabled }?.instanceId
                    } ?: return null
                }
                try {
                    val src = openSource(targetId, dnsResolver = dnsResolver)
                    src.verifyInitialized()
                    return null
                } catch (e: SourceNeedsSetupException) {
                    return null
                } catch (e: com.limelight.local.media.SourceNeedsAuthException) {
                    return null
                } catch (e: java.io.FileNotFoundException) {
                    lastNotFound = e
                } catch (t: Throwable) {
                    // Re-collect secrets after init() in case it wrote cookies/storage before throwing
                    val freshSecrets = collectKnownSecretsForPlugin(pluginId)
                    return PluginLogger.redact(extractRawTechnicalError(t), freshSecrets)
                }
            }
            val freshSecrets = collectKnownSecretsForPlugin(pluginId)
            return PluginLogger.redact(
                extractRawTechnicalError(lastNotFound ?: IOException("Plugin load failed")),
                freshSecrets,
            )
        }

        val snap = probeSnapshot ?: return null
        val manifest = snap.manifest
        val mainJs = snap.mainJs
        val locale = snap.locale
        val secrets = snap.secrets
        val logger = PluginLogger(
            pluginId = manifest.id,
            instanceId = "dev-probe",
            knownSecretsProvider = { secrets },
        )
        val probeName = ".probe-${UUID.randomUUID()}"
        val tmpDir = File(rootDir, probeName)
        activeProbeDirs.add(probeName)
        val policy = if (dnsResolver != null) {
            NetworkPolicy(manifest.permissions, emptySet(), dnsResolver)
        } else {
            NetworkPolicy(manifest.permissions, emptySet())
        }
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)
        val runtime = PluginRuntime(
            manifest = locale.localizeManifest(manifest),
            instanceId = "dev-probe",
            mainJsSource = mainJs,
            config = emptyMap(),
            fetchGateway = PluginFetchGateway(policy, cookieJar, okHttpClient),
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
            locale = locale,
            deviceInfo = deviceInfo,
            androidContext = androidContext,
        )
        return try {
            runtime.verifyInitialized()
            null
        } catch (e: SourceNeedsSetupException) {
            null
        } catch (e: com.limelight.local.media.SourceNeedsAuthException) {
            null
        } catch (t: Throwable) {
            val allSecrets = (secrets + cookieJar.allCookieValues() + storage.allSecretLikeValues()).distinct()
            val redacted = PluginLogger.redact(extractRawTechnicalError(t), allSecrets)
            logger.error("init error: $redacted")
            redacted
        } finally {
            runCatching { runtime.close() }
            runCatching { tmpDir.deleteRecursively() }
            activeProbeDirs.remove(probeName)
        }
    }

    suspend fun installFromFile(
        file: File,
        confirmReplaceFromDifferentSource: Boolean = false,
    ): InstallResult {
        if (file.length() > PluginPackageArchive.MAX_PACKAGE_BYTES) {
            throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
        }
        val bytes = file.inputStream().use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > PluginPackageArchive.MAX_PACKAGE_BYTES) {
                    throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        return installFromBytes(
            zipBytes = bytes,
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

    suspend fun confirmPermissions(
        pluginId: String,
        expectedSha256: String? = null,
    ) = pluginLockFor(pluginId).withLock {
        val pkg = store.getPackage(pluginId)
            ?: throw PackageInstallException("找不到已安装的插件：$pluginId")
        if (expectedSha256 != null && !pkg.sha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw PackageInstallException("插件版本或内容已变更，请重新核对权限后再确认。")
        }
        val manifest = ManifestParser.parseAndValidate(pkg.manifestJson)
        store.confirmPackagePermissions(pluginId, manifest.permissions.toJsonString(), clockMs())
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) = pluginLockFor(pluginId).withLock {
        store.setPackageEnabled(pluginId, enabled, clockMs())
        if (!enabled) {
            shutdownRuntimesForPlugin(pluginId)
        }
    }

    suspend fun uninstall(pluginId: String) = pluginLockFor(pluginId).withLock {
        withContext(NonCancellable) {
            wipeAllInstancesForPlugin(pluginId)
            store.deletePackage(pluginId)
            File(packagesDir, pluginId).deleteRecursively()
        }
    }

    suspend fun createInstance(
        pluginId: String,
        displayName: String,
        config: Map<String, JsonElement> = emptyMap(),
        instanceId: String = UUID.randomUUID().toString(),
    ): PluginInstanceEntity = pluginLockFor(pluginId).withLock {
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
        entity
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
        val initialInst = store.getInstance(instanceId)
            ?: throw SourceNeedsSetupException("找不到这个播放源的配置，请重新添加。")
        return pluginLockFor(initialInst.pluginId).withLock {
            lockFor(instanceId).withLock {
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

                val passwordKeys = manifest.passwordConfigKeys()
                val passwordValues = passwordKeys.mapNotNull { k -> (config[k] as? JsonPrimitive)?.content }
                val instDir = File(instancesDir, instanceId)
                val storage = InstanceStorage(File(instDir, "storage.enc"), secretBox)
                var cookieJarRef: PluginCookieJar? = null
                val logger = PluginLogger(
                    pluginId = manifest.id,
                    instanceId = instanceId,
                    knownSecretsProvider = {
                        val out = LinkedHashSet<String>()
                        out += passwordValues
                        cookieJarRef?.allCookieValues()?.let { out += it }
                        out += storage.allSecretLikeValues()
                        out
                    },
                )
                val cookieJar = PluginCookieJar(
                    file = File(instDir, "cookies.enc"),
                    secretBox = secretBox,
                    onWarning = { msg -> logger.warn(msg) },
                )
                cookieJarRef = cookieJar
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
        const val LOCAL_FILE_ORIGIN = "local-file"
        const val DEV_ORIGIN_PREFIX = "dev:"
        const val WILDCARD_LAN_WARNING = "可访问你局域网里的任意设备"

        fun isDevOrigin(sourceOrigin: String): Boolean =
            sourceOrigin.startsWith(DEV_ORIGIN_PREFIX)

        fun isFileOrigin(sourceOrigin: String): Boolean =
            sourceOrigin == LOCAL_FILE_ORIGIN

        fun devServerBaseUrlFromOrigin(sourceOrigin: String): String? =
            if (isDevOrigin(sourceOrigin)) sourceOrigin.removePrefix(DEV_ORIGIN_PREFIX).takeIf { it.isNotBlank() } else null

        fun devDisplayAddressFromOrigin(sourceOrigin: String): String? {
            val base = devServerBaseUrlFromOrigin(sourceOrigin) ?: return null
            val parsed = base.toHttpUrlOrNull() ?: return base
            val hostFmt = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
            return "$hostFmt:${parsed.port}"
        }

        fun extractRawTechnicalError(error: Throwable): String {
            var cur: Throwable? = error
            var deepest: Throwable = error
            while (cur != null) {
                if (cur is RepositoryVerificationException && !cur.technicalDetail.isNullOrBlank()) {
                    return cur.technicalDetail
                }
                if (cur is ManifestValidationException) {
                    return cur.message ?: cur.errors.joinToString("\n")
                }
                if (cur is com.dokar.quickjs.QuickJsException) {
                    return cur.message ?: cur.toString()
                }
                deepest = cur
                cur = cur.cause
            }
            return deepest.message?.takeIf { it.isNotBlank() }
                ?: error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
        }

        /**
         * Returns true when installing from [newSourceOrigin] over an already
         * installed plugin from [existingSourceOrigin] MUST be treated as a
         * replacement (requiring explicit confirmation and wiping all instances,
         * configs, storage, and cookies).
         *
         * Per §11.4 and Round 3 hardening: local-file installs have no stable
         * source identity independent of content, so installing the same `id`
         * from a local file ALWAYS requires replacement confirmation and never
         * inherits existing state.
         */
        fun isDifferentSource(existingSourceOrigin: String, newSourceOrigin: String): Boolean {
            if (newSourceOrigin == LOCAL_FILE_ORIGIN || existingSourceOrigin == LOCAL_FILE_ORIGIN) {
                return true
            }
            return existingSourceOrigin != newSourceOrigin
        }

        /**
         * Computes canonical source identity for `(pluginId, sourceOrigin)` (§11.4):
         * - Dev server installs identify as `dev:<scheme>://<host>:<port>` (distinct from repo/builtin/file)
         * - Repository installs identify by normalized repository index URL (query/fragment stripped)
         * - Direct HTTP(S) URL installs identify by full normalized URL stripped of query & fragment
         * - Local file installs identify as [LOCAL_FILE_ORIGIN] (never treated as same-source update)
         */
        fun canonicalSourceOrigin(
            repoUrl: String?,
            sourceUrl: String?,
            devServerUrl: String? = null,
        ): String {
            if (!devServerUrl.isNullOrBlank()) {
                val parsed = devServerUrl.trim().toHttpUrlOrNull()
                return if (parsed != null) {
                    val portPart = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
                    val host = NetworkPolicy.canonicalizeHost(parsed.host)
                    val hostFormatted = if (host.contains(':')) "[$host]" else host
                    "$DEV_ORIGIN_PREFIX${parsed.scheme.lowercase()}://$hostFormatted$portPart"
                } else {
                    "$DEV_ORIGIN_PREFIX${devServerUrl.trim()}"
                }
            }
            if (!repoUrl.isNullOrBlank()) {
                val parsed = repoUrl.trim().toHttpUrlOrNull()
                return if (parsed != null) {
                    "repo:${canonicalUrlWithoutQueryOrFragment(parsed)}"
                } else {
                    "repo:${repoUrl.trim()}"
                }
            }
            if (!sourceUrl.isNullOrBlank()) {
                val trimmed = sourceUrl.trim()
                val parsed = trimmed.toHttpUrlOrNull()
                if (parsed != null) {
                    return "url:${canonicalUrlWithoutQueryOrFragment(parsed)}"
                }
                if (trimmed.startsWith("file:", ignoreCase = true)) {
                    return LOCAL_FILE_ORIGIN
                }
                return "other:$trimmed"
            }
            return LOCAL_FILE_ORIGIN
        }

        private fun canonicalUrlWithoutQueryOrFragment(url: HttpUrl): String {
            val portPart = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            val host = NetworkPolicy.canonicalizeHost(url.host)
            val hostFormatted = if (host.contains(':')) "[$host]" else host
            val path = url.encodedPath.trimEnd('/').ifEmpty { "/" }
            return "${url.scheme.lowercase()}://$hostFormatted$portPart$path"
        }
    }
}
