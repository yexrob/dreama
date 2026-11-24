package com.limelight.local.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RepoPluginEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val icon: String? = null,
    val url: String,
    val sha256: String,
    val minApp: String? = null,
)

data class PluginRepoIndex(
    val name: String,
    val plugins: List<RepoPluginEntry>,
    val rawJson: String,
)

data class VerifiedPackageDownload(
    val bytes: ByteArray,
    val sha256: String,
    val sourceUrl: String,
)

class RepositoryVerificationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Fetches and verifies plugin repository indices and `.d3plugin` archives
 * according to protocol §8 and §11.4:
 * - Repository URLs and public package downloads require HTTPS and reject any
 *   private, loopback, link-local, or reserved IP (including across redirects)
 * - Enforces total call timeout and 8 MB package size cap
 * - Validates index JSON against shared `repository.schema.json`
 * - Verifies exact SHA-256 digest, zip central-directory/local-header
 *   consistency, and inner `manifest.json` `id` and `version`
 */
class PluginRepositoryClient(
    private val httpClient: OkHttpClient = DEFAULT_CLIENT,
    private val maxIndexBytes: Int = MAX_INDEX_BYTES,
    private val maxPackageBytes: Int = MAX_PACKAGE_BYTES,
    private val publicPolicy: NetworkPolicy = NetworkPolicy.forPublicRepository(),
    private val devPolicyFactory: (String) -> NetworkPolicy = { host -> NetworkPolicy.forDevServer(host) },
    private val totalTimeoutMs: Long = TimeUnit.SECONDS.toMillis(DEFAULT_TOTAL_TIMEOUT_SEC),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchIndex(indexUrl: String): PluginRepoIndex = withContext(Dispatchers.IO) {
        val bytes = downloadBytes(indexUrl, maxIndexBytes, publicPolicy)
        parseAndValidateIndex(String(bytes, Charsets.UTF_8))
    }

    fun parseAndValidateIndex(rawJson: String): PluginRepoIndex {
        val element = try {
            json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            throw RepositoryVerificationException("插件仓库索引不是有效的 JSON", e)
        }
        val res = JsonSchemaValidator.validateRepository(element)
        if (!res.valid) {
            throw RepositoryVerificationException("插件仓库索引格式无效：${res.errors.joinToString("; ")}")
        }
        val obj = element as JsonObject
        val name = (obj["name"] as JsonPrimitive).content
        val plugins = (obj["plugins"] as JsonArray).map { el ->
            val p = el as JsonObject
            RepoPluginEntry(
                id = (p["id"] as JsonPrimitive).content,
                name = (p["name"] as JsonPrimitive).content,
                version = (p["version"] as JsonPrimitive).content,
                description = (p["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                icon = (p["icon"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                url = (p["url"] as JsonPrimitive).content,
                sha256 = (p["sha256"] as JsonPrimitive).content.lowercase(Locale.ROOT),
                minApp = (p["minApp"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
        return PluginRepoIndex(name = name, plugins = plugins, rawJson = rawJson)
    }

    /**
     * Downloads a `.d3plugin` package referenced by [entry] under §11.4 public
     * repository network policy (HTTPS only, no private redirects, 8 MB cap),
     * verifies its SHA-256 hash matches [entry.sha256], and verifies the inner
     * `manifest.json` declares the exact same `id` and `version`.
     */
    suspend fun downloadVerifiedPackage(entry: RepoPluginEntry): VerifiedPackageDownload =
        withContext(Dispatchers.IO) {
            val bytes = downloadBytes(entry.url, maxPackageBytes, publicPolicy)
            verifyPackageBytes(bytes, expectedSha256 = entry.sha256, expectedId = entry.id, expectedVersion = entry.version)
            VerifiedPackageDownload(
                bytes = bytes,
                sha256 = entry.sha256.lowercase(Locale.ROOT),
                sourceUrl = entry.url,
            )
        }

    /**
     * Downloads a single `.d3plugin` archive directly from a public HTTPS [url].
     */
    suspend fun downloadDirectPackage(url: String, expectedSha256: String? = null): VerifiedPackageDownload =
        withContext(Dispatchers.IO) {
            val bytes = downloadBytes(url, maxPackageBytes, publicPolicy)
            val actualSha = sha256Hex(bytes)
            if (expectedSha256 != null && !actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                throw RepositoryVerificationException(
                    "插件包校验失败：SHA-256 不匹配（期望 $expectedSha256，实际 $actualSha）",
                )
            }
            VerifiedPackageDownload(bytes = bytes, sha256 = actualSha, sourceUrl = url)
        }

    /**
     * Developer-mode download for `d3plugin serve` on a LAN host (§9).
     * Allows plain HTTP to the explicitly entered LAN dev host while still
     * enforcing 8 MB cap, timeout, and loopback/link-local/reserved rejections.
     */
    suspend fun downloadDevPackage(url: String): VerifiedPackageDownload =
        withContext(Dispatchers.IO) {
            val parsed = url.trim().toHttpUrlOrNull()
                ?: throw RepositoryVerificationException("无效的开发服务器地址：$url")
            val devPolicy = devPolicyFactory(parsed.host)
            val bytes = downloadBytes(url, maxPackageBytes, devPolicy)
            VerifiedPackageDownload(bytes = bytes, sha256 = sha256Hex(bytes), sourceUrl = url)
        }

    fun verifyPackageBytes(
        bytes: ByteArray,
        expectedSha256: String,
        expectedId: String,
        expectedVersion: String,
    ) {
        if (bytes.size > maxPackageBytes) {
            throw RepositoryVerificationException("插件包超过大小限制（上限 ${maxPackageBytes / (1024 * 1024)} MB）")
        }
        val actualSha = sha256Hex(bytes)
        if (!actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw RepositoryVerificationException(
                "插件包 SHA-256 校验失败：期望 $expectedSha256，实际 $actualSha",
            )
        }
        val archive = PluginPackageArchive.inspect(bytes)
        val manifest = ManifestParser.parseAndValidate(archive.manifestJson)
        if (manifest.id != expectedId) {
            throw RepositoryVerificationException(
                "插件包内 id（${manifest.id}）与仓库索引（$expectedId）不一致",
            )
        }
        if (manifest.version != expectedVersion) {
            throw RepositoryVerificationException(
                "插件包内版本（${manifest.version}）与仓库索引（$expectedVersion）不一致",
            )
        }
    }

    private fun downloadBytes(url: String, maxBytes: Int, policy: NetworkPolicy): ByteArray {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs)
        val executor = SafePluginHttpExecutor(
            networkPolicy = policy,
            baseClient = httpClient,
        )
        val resp = try {
            executor.openStream(
                rawUrl = url,
                pluginHeaders = emptyMap(),
                trustedOrigins = emptySet(),
                totalTimeoutMs = totalTimeoutMs,
            )
        } catch (e: RepositoryVerificationException) {
            throw e
        } catch (e: Exception) {
            throw RepositoryVerificationException(e.message ?: "下载插件仓库或安装包失败", e)
        }

        resp.use { r ->
            if (!r.isSuccessful) {
                throw RepositoryVerificationException("下载失败（HTTP ${r.code}）：$url")
            }
            val len = r.body.contentLength()
            if (len > maxBytes) {
                throw RepositoryVerificationException("下载文件超过大小限制（上限 ${maxBytes / (1024 * 1024)} MB）")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0L
            r.body.byteStream().use { stream ->
                while (true) {
                    if (System.nanoTime() >= deadlineNanos) {
                        throw RepositoryVerificationException("下载总耗时超过 ${totalTimeoutMs / 1000} 秒上限")
                    }
                    val read = stream.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw RepositoryVerificationException("下载文件超过大小限制（上限 ${maxBytes / (1024 * 1024)} MB）")
                    }
                    out.write(buf, 0, read)
                }
            }
            return out.toByteArray()
        }
    }

    companion object {
        const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        /** §11.4: maximum plugin package size is 8 MB. */
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
        const val DEFAULT_TOTAL_TIMEOUT_SEC = 30L

        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_TOTAL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

        fun sha256Hex(bytes: ByteArray): String =
            PluginCrypto.hexEncode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
