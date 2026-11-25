package com.limelight.local.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
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

data class NormalizedDevServer(
    val baseUrl: String,
    val indexUrl: String,
    val displayAddress: String,
    val host: String,
    val port: Int,
)

data class DevPluginFetchResult(
    val server: NormalizedDevServer,
    val index: PluginRepoIndex,
    val entry: RepoPluginEntry,
    val download: VerifiedPackageDownload,
)

class RepositoryVerificationException(
    message: String,
    cause: Throwable? = null,
    val technicalDetail: String? = null,
) : IOException(message, cause)

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
     * Normalizes a user-entered developer server address (e.g. `192.168.1.20:8787`,
     * `http://192.168.1.20:8787`, `http://192.168.1.20:8787/any/path`, `[fd00::1]:8787`)
     * into a canonical base URL + `/index.json`, strictly enforcing RFC1918 / IPv6 ULA
     * via [NetworkPolicy.forDevServer].
     */
    fun normalizeDevServerAddress(
        rawInput: String,
        dnsResolver: ((String) -> List<InetAddress>)? = null,
    ): NormalizedDevServer {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            throw RepositoryVerificationException("请输入电脑的开发服务器地址，例如 192.168.1.20:8787")
        }
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) {
            throw RepositoryVerificationException("开发服务器地址不能包含空格或控制字符")
        }

        val candidateUrl = buildDevCandidateUrl(trimmed)
        val parsed = candidateUrl.toHttpUrlOrNull()
            ?: throw RepositoryVerificationException("开发服务器地址格式无效，请检查后重试")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw RepositoryVerificationException("开发服务器地址不支持包含用户名或密码")
        }

        val canonicalHost = NetworkPolicy.canonicalizeHost(parsed.host)
        val policy = if (dnsResolver != null) {
            NetworkPolicy.forDevServer(canonicalHost, dnsResolver)
        } else {
            devPolicyFactory(canonicalHost)
        }
        try {
            // Validate candidateUrl directly so bracketed raw IPv6 forms (e.g. [::ffff:127.0.0.1]) are caught cleanly
            policy.validateResourceUrl(candidateUrl, previousScheme = null)
        } catch (e: NetworkPolicyException) {
            throw RepositoryVerificationException(e.message ?: "不允许访问该开发服务器地址", e)
        }

        val hostForUrl = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
        val portPart = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
        val baseUrl = "${parsed.scheme}://$hostForUrl$portPart"
        val indexUrl = "$baseUrl/index.json"
        val displayAddress = "$hostForUrl:${parsed.port}"
        return NormalizedDevServer(
            baseUrl = baseUrl,
            indexUrl = indexUrl,
            displayAddress = displayAddress,
            host = canonicalHost,
            port = parsed.port,
        )
    }

    /**
     * Developer-mode fetch for `d3plugin serve` on a LAN host:
     * 1. Normalizes & validates the server address (RFC1918 / IPv6 ULA only)
     * 2. Fetches `/index.json` pinned to that single host (rejects redirects to other hosts)
     * 3. Downloads `/plugin.d3plugin` using the SAME single-host dev policy
     * 4. Verifies SHA-256, zip consistency, and inner manifest id/version
     */
    suspend fun fetchDevPlugin(rawServerAddress: String): DevPluginFetchResult =
        withContext(Dispatchers.IO) {
            val normalized = normalizeDevServerAddress(rawServerAddress)
            val devPolicy = devPolicyFactory(normalized.host)
            val indexBytes = downloadBytes(
                url = normalized.indexUrl,
                maxBytes = maxIndexBytes,
                policy = devPolicy,
                extractServerError = true,
            )
            val index = parseAndValidateIndex(String(indexBytes, Charsets.UTF_8))
            val entry = index.plugins.firstOrNull()
                ?: throw RepositoryVerificationException("开发服务器索引中没有可用插件")
            val pkgBytes = downloadBytes(
                url = entry.url,
                maxBytes = maxPackageBytes,
                policy = devPolicy,
                extractServerError = true,
            )
            verifyPackageBytes(
                bytes = pkgBytes,
                expectedSha256 = entry.sha256,
                expectedId = entry.id,
                expectedVersion = entry.version,
            )
            DevPluginFetchResult(
                server = normalized,
                index = index,
                entry = entry,
                download = VerifiedPackageDownload(
                    bytes = pkgBytes,
                    sha256 = entry.sha256.lowercase(Locale.ROOT),
                    sourceUrl = entry.url,
                ),
            )
        }

    /**
     * Developer-mode download for `d3plugin serve` on a LAN host (§9).
     * Allows plain HTTP to the explicitly entered LAN dev host while enforcing
     * RFC1918 / IPv6 ULA only, 8 MB cap, timeout, and no cross-host redirects.
     */
    suspend fun downloadDevPackage(url: String): VerifiedPackageDownload =
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            val parsed = trimmed.toHttpUrlOrNull()
                ?: throw RepositoryVerificationException("无效的开发服务器地址：$url")
            val devPolicy = devPolicyFactory(parsed.host)
            val bytes = downloadBytes(trimmed, maxPackageBytes, devPolicy, extractServerError = true)
            VerifiedPackageDownload(bytes = bytes, sha256 = sha256Hex(bytes), sourceUrl = trimmed)
        }

    private fun buildDevCandidateUrl(trimmed: String): String {
        if (trimmed.contains("://")) {
            val scheme = trimmed.substringBefore("://").lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                throw RepositoryVerificationException("仅支持 http 或 https 协议，请填写局域网开发服务器地址")
            }
            val afterScheme = trimmed.substringAfter("://")
            val authority = afterScheme.substringBefore("/").substringBefore("?").substringBefore("#")
            if (authority.isEmpty() || authority.contains('@')) {
                throw RepositoryVerificationException("开发服务器地址格式无效，请检查后重试")
            }
            return "$scheme://$authority/index.json"
        }
        val colonCount = trimmed.count { it == ':' }
        val isBareIpv6 = !trimmed.startsWith("[") && colonCount >= 2 &&
            !trimmed.substringBefore("/").contains('.')
        if (isBareIpv6) {
            val bareHost = trimmed.substringBefore("/").substringBefore("?").substringBefore("#")
            return "http://[$bareHost]:$DEFAULT_DEV_SERVER_PORT/index.json"
        }
        val nonHttpScheme = NON_HTTP_SCHEME_REGEX.containsMatchIn(trimmed)
        if (nonHttpScheme) {
            throw RepositoryVerificationException("仅支持 http 或 https 协议，请填写局域网开发服务器地址")
        }
        val authority = trimmed.substringBefore("/").substringBefore("?").substringBefore("#")
        if (authority.isEmpty() || authority.contains('@')) {
            throw RepositoryVerificationException("开发服务器地址格式无效，请检查后重试")
        }
        val hasExplicitPort = if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            end > 0 && end + 1 < authority.length && authority[end + 1] == ':'
        } else {
            authority.contains(':')
        }
        val withPort = if (hasExplicitPort) authority else "$authority:$DEFAULT_DEV_SERVER_PORT"
        return "http://$withPort/index.json"
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

    private fun downloadBytes(
        url: String,
        maxBytes: Int,
        policy: NetworkPolicy,
        extractServerError: Boolean = false,
    ): ByteArray {
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
                val detail = if (extractServerError) extractJsonErrorDetail(r) else null
                throw RepositoryVerificationException(
                    message = if (detail != null) "开发服务器返回错误（HTTP ${r.code}）：$detail"
                    else "下载失败（HTTP ${r.code}）：$url",
                    technicalDetail = detail,
                )
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

    private fun extractJsonErrorDetail(resp: okhttp3.Response): String? {
        return try {
            val buf = ByteArray(4096)
            val out = ByteArrayOutputStream()
            resp.body.byteStream().use { stream ->
                var total = 0
                while (total < 4096) {
                    val n = stream.read(buf, 0, minOf(buf.size, 4096 - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            val raw = out.toString(Charsets.UTF_8.name()).trim()
            if (raw.isEmpty()) return null
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            (obj?.get("error") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: raw.take(1024)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        /** §11.4: maximum plugin package size is 8 MB. */
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
        const val DEFAULT_TOTAL_TIMEOUT_SEC = 30L
        const val DEFAULT_DEV_SERVER_PORT = 8787
        private val NON_HTTP_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?![0-9]+(?:/|\\?|#|$))")

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
