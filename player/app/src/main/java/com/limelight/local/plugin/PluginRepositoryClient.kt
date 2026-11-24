package com.limelight.local.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
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
 * according to protocol §8:
 * - Validates index JSON against shared `repository.schema.json`
 * - Downloads `.d3plugin` with size cap and verifies exact SHA-256 digest
 * - Verifies package `manifest.json` `id` and `version` match the index entry
 */
class PluginRepositoryClient(
    private val httpClient: OkHttpClient = DEFAULT_CLIENT,
    private val maxIndexBytes: Int = MAX_INDEX_BYTES,
    private val maxPackageBytes: Int = MAX_PACKAGE_BYTES,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchIndex(indexUrl: String): PluginRepoIndex = withContext(Dispatchers.IO) {
        val bytes = downloadBytes(indexUrl, maxIndexBytes)
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
                description = (p["description"] as? JsonPrimitive)?.content ?: "",
                icon = (p["icon"] as? JsonPrimitive)?.content,
                url = (p["url"] as JsonPrimitive).content,
                sha256 = (p["sha256"] as JsonPrimitive).content.lowercase(Locale.ROOT),
                minApp = (p["minApp"] as? JsonPrimitive)?.content,
            )
        }
        return PluginRepoIndex(name = name, plugins = plugins, rawJson = rawJson)
    }

    /**
     * Downloads a `.d3plugin` package referenced by [entry], verifies its
     * SHA-256 hash matches [entry.sha256], and verifies the inner
     * `manifest.json` declares the exact same `id` and `version`.
     */
    suspend fun downloadVerifiedPackage(entry: RepoPluginEntry): VerifiedPackageDownload =
        withContext(Dispatchers.IO) {
            val bytes = downloadBytes(entry.url, maxPackageBytes)
            verifyPackageBytes(bytes, expectedSha256 = entry.sha256, expectedId = entry.id, expectedVersion = entry.version)
            VerifiedPackageDownload(
                bytes = bytes,
                sha256 = entry.sha256.lowercase(Locale.ROOT),
                sourceUrl = entry.url,
            )
        }

    /**
     * Downloads a single `.d3plugin` archive directly from [url] (or from
     * `d3plugin serve` during development, protocol §8 & §9).
     */
    suspend fun downloadDirectPackage(url: String, expectedSha256: String? = null): VerifiedPackageDownload =
        withContext(Dispatchers.IO) {
            val bytes = downloadBytes(url, maxPackageBytes)
            val actualSha = sha256Hex(bytes)
            if (expectedSha256 != null && !actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                throw RepositoryVerificationException(
                    "插件包校验失败：SHA-256 不匹配（期望 $expectedSha256，实际 $actualSha）",
                )
            }
            VerifiedPackageDownload(bytes = bytes, sha256 = actualSha, sourceUrl = url)
        }

    fun verifyPackageBytes(
        bytes: ByteArray,
        expectedSha256: String,
        expectedId: String,
        expectedVersion: String,
    ) {
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

    private fun downloadBytes(url: String, maxBytes: Int): ByteArray {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RepositoryVerificationException("下载失败（HTTP ${resp.code}）：$url")
            }
            val len = resp.body.contentLength()
            if (len > maxBytes) {
                throw RepositoryVerificationException("下载文件超过大小限制（上限 ${maxBytes / (1024 * 1024)} MB）")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0L
            resp.body.byteStream().use { stream ->
                while (true) {
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
        const val MAX_PACKAGE_BYTES = 16 * 1024 * 1024

        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun sha256Hex(bytes: ByteArray): String =
            PluginCrypto.hexEncode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
