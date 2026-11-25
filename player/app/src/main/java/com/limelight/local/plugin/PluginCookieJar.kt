package com.limelight.local.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

class CookieJarQuotaExceededException(message: String) : IllegalStateException(message)

/**
 * Per-instance encrypted cookie jar backing `ctx.fetch` and `ctx.cookies`
 * (§5, §11.1, S5):
 * - Capped at 256 KB serialized JSON size
 * - Coalesces disk writes (`dirty` flag flushed once per plugin invocation via
 *   [flush], or immediately when [autoFlush] is true)
 * - Unified RFC 6265 parser matching `tools/plugin-kit/src/cookies.js`:
 *   - `Max-Age` ALWAYS takes precedence over `Expires` regardless of attribute order
 *   - Rejects public-suffix `Domain` attributes and domain-mismatched cookies,
 *     ignoring the cookie and emitting a WARN via [onWarning]
 *   - Handles IPv4 and canonical IPv6 literal hosts consistently (exact host match only)
 *   - Identical entry byte estimation (`140 + 2*name + 2*value + domain + path`)
 * - Preserves original file and throws [StorageCorruptedException] if
 *   decryption fails
 */
class PluginCookieJar(
    private val file: File,
    private val secretBox: SecretBox,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxBytes: Int = MAX_COOKIE_JAR_BYTES,
    private val autoFlush: Boolean = false,
    private val onWarning: (String) -> Unit = {},
) : CookieJar {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = ReentrantReadWriteLock()
    private val store = LinkedHashMap<CookieKey, StoredCookie>()
    private var loaded = false
    private var loadFailure: StorageCorruptedException? = null
    private var dirty = false
    private var estimatedBytes: Int = 2 // "[]"

    /** Number of actual encrypted disk writes performed (useful for coalescing tests). */
    @Volatile
    var diskWriteCount: Int = 0
        private set

    private data class CookieKey(
        val name: String,
        val domain: String,
        val path: String,
    )

    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val hostOnly: Boolean,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val expiresAt: Long,
    ) {
        fun matches(url: HttpUrl): Boolean {
            if (secure && !url.isHttps) return false
            val host = NetworkPolicy.canonicalizeHost(url.host)
            val domainMatch = if (hostOnly) {
                host == domain
            } else {
                domainMatches(host, domain)
            }
            if (!domainMatch) return false
            val reqPath = url.encodedPath
            return reqPath == path ||
                (reqPath.startsWith(path) && (path.endsWith("/") || reqPath.getOrNull(path.length) == '/'))
        }

        fun toOkHttpCookie(): Cookie {
            val b = Cookie.Builder()
                .name(name)
                .value(value)
                .path(path)
                .expiresAt(expiresAt.coerceAtMost(MAX_OKHTTP_DATE))
            if (hostOnly) b.hostOnlyDomain(domain) else b.domain(domain)
            if (secure) b.secure()
            if (httpOnly) b.httpOnly()
            return b.build()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val converted = cookies.map { c ->
            StoredCookie(
                name = c.name,
                value = c.value,
                domain = NetworkPolicy.canonicalizeHost(c.domain),
                hostOnly = c.hostOnly,
                path = c.path,
                secure = c.secure,
                httpOnly = c.httpOnly,
                expiresAt = c.expiresAt,
            )
        }
        saveStoredCookies(converted)
    }

    fun saveFromSetCookieHeaders(rawUrl: String, headers: List<String>) {
        if (headers.isEmpty()) return
        val url = rawUrl.trim().toHttpUrlOrNull() ?: return
        val parsed = ArrayList<StoredCookie>(headers.size)
        for (hdr in headers) {
            val cookie = parseSetCookie(url, hdr, strictSyntax = false)
            if (cookie != null) parsed += cookie
        }
        if (parsed.isNotEmpty()) {
            saveStoredCookies(parsed)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        loadStoredForRequest(url).map { it.toOkHttpCookie() }

    private fun loadStoredForRequest(url: HttpUrl): List<StoredCookie> = lock.write {
        ensureLoadedLocked()
        val now = clockMs()
        var evicted = false
        val iter = store.entries.iterator()
        val matched = ArrayList<StoredCookie>()
        while (iter.hasNext()) {
            val c = iter.next().value
            if (c.expiresAt <= now) {
                iter.remove()
                estimatedBytes = (estimatedBytes - estimateEntryBytes(c)).coerceAtLeast(2)
                evicted = true
            } else if (c.matches(url)) {
                matched += c
            }
        }
        if (evicted) {
            dirty = true
            if (autoFlush) flushLocked()
        }
        matched
    }

    fun getHeaderForUrl(rawUrl: String): String {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid cookie URL: $rawUrl")
        val cookies = loadStoredForRequest(url)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun setCookie(rawUrl: String, setCookieHeader: String) {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid cookie URL: $rawUrl")
        val cookie = parseSetCookie(url, setCookieHeader, strictSyntax = true) ?: return
        saveStoredCookies(listOf(cookie))
    }

    fun clear() = lock.write {
        store.clear()
        loaded = true
        loadFailure = null
        dirty = false
        estimatedBytes = 2
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Persists pending cookie mutations in a single atomic encrypted write.
     */
    fun flush() = lock.write {
        if (dirty && loadFailure == null) {
            flushLocked()
        }
    }

    /**
     * Returns all stored cookie values (length >= 3) for diagnostic & log redaction
     * without mutating cookie state or flushing to disk.
     */
    internal fun allCookieValues(): List<String> = lock.write {
        runCatching { ensureLoadedLocked() }.onFailure { return@write emptyList() }
        val out = LinkedHashSet<String>()
        for (cookie in store.values) {
            val v = cookie.value.trim()
            if (v.length >= 3) out += v
        }
        out.toList()
    }

    private fun saveStoredCookies(cookies: List<StoredCookie>) {
        if (cookies.isEmpty()) return
        lock.write {
            ensureLoadedLocked()
            val now = clockMs()
            var changed = false
            for (cookie in cookies) {
                val key = CookieKey(cookie.name, cookie.domain, cookie.path)
                val old = store[key]
                val oldCost = old?.let(::estimateEntryBytes) ?: 0
                if (cookie.expiresAt <= now) {
                    if (store.remove(key) != null) {
                        estimatedBytes = (estimatedBytes - oldCost).coerceAtLeast(2)
                        changed = true
                    }
                } else {
                    val newCost = estimateEntryBytes(cookie)
                    val projected = estimatedBytes - oldCost + newCost
                    if (projected > maxBytes) {
                        val prev = store.put(key, cookie)
                        val exactSize = serializeLocked().size
                        if (exactSize > maxBytes) {
                            if (prev == null) store.remove(key) else store[key] = prev
                            throw CookieJarQuotaExceededException(
                                "插件 Cookie 罐超过大小上限（${maxBytes / 1024} KB）",
                            )
                        }
                        estimatedBytes = exactSize
                    } else {
                        store[key] = cookie
                        estimatedBytes = projected
                    }
                    changed = true
                }
            }
            if (changed) {
                dirty = true
                if (autoFlush) flushLocked()
            }
        }
    }

    private fun parseSetCookie(url: HttpUrl, rawHeader: String, strictSyntax: Boolean): StoredCookie? {
        val parts = rawHeader.trim().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            if (strictSyntax) throw IllegalArgumentException("Invalid Set-Cookie header")
            return null
        }
        val eq = parts[0].indexOf('=')
        if (eq <= 0) {
            if (strictSyntax) throw IllegalArgumentException("Invalid cookie name=value")
            return null
        }
        val name = parts[0].substring(0, eq).trim()
        val value = parts[0].substring(eq + 1).trim()
        if (name.isEmpty()) {
            if (strictSyntax) throw IllegalArgumentException("Invalid cookie name=value")
            return null
        }

        val reqHost = NetworkPolicy.canonicalizeHost(url.host)
        val reqIsIp = NetworkPolicy.parseAnyIpLiteral(reqHost) != null

        var domain = reqHost
        var hostOnly = true
        var cookiePath = defaultPath(url.encodedPath)
        var secure = false
        var httpOnly = false
        var hasMaxAge = false
        var maxAgeExpiresAt: Long? = null
        var parsedExpiresAt: Long? = null

        for (attr in parts.drop(1)) {
            val aeq = attr.indexOf('=')
            val k = (if (aeq >= 0) attr.substring(0, aeq) else attr).trim().lowercase(Locale.ROOT)
            val v = if (aeq >= 0) attr.substring(aeq + 1).trim() else ""
            when (k) {
                "domain" -> {
                    if (v.isEmpty()) continue
                    val rawDomain = v.trimStart('.')
                    val d = NetworkPolicy.canonicalizeHost(rawDomain)
                    if (d.isEmpty()) continue
                    if (isPublicSuffix(d)) {
                        onWarning("Cookie '$name' 的 Domain='$v' 属于公共后缀，已忽略")
                        return null
                    }
                    if (reqIsIp || NetworkPolicy.parseAnyIpLiteral(d) != null) {
                        if (reqHost == d) {
                            domain = reqHost
                            hostOnly = true
                        } else {
                            onWarning("Cookie '$name' 的 Domain='$v' 与请求 IP 主机 '$reqHost' 不匹配，已忽略")
                            return null
                        }
                    } else if (domainMatches(reqHost, d)) {
                        domain = d
                        hostOnly = false
                    } else {
                        onWarning("Cookie '$name' 的 Domain='$v' 与请求主机 '$reqHost' 不匹配，已忽略")
                        return null
                    }
                }
                "path" -> {
                    if (v.startsWith("/")) cookiePath = v
                }
                "secure" -> secure = true
                "httponly" -> httpOnly = true
                "max-age" -> {
                    // RFC 6265 §5.2.2: Max-Age ALWAYS takes precedence over Expires
                    val sec = v.toLongOrNull()
                    if (sec != null) {
                        hasMaxAge = true
                        maxAgeExpiresAt = if (sec <= 0L) {
                            0L
                        } else {
                            val now = clockMs()
                            val delta = if (sec > Long.MAX_VALUE / 1000L) MAX_OKHTTP_DATE - now else sec * 1000L
                            (now + delta).coerceAtMost(MAX_OKHTTP_DATE)
                        }
                    }
                }
                "expires" -> {
                    val ts = parseHttpDate(v)
                    if (ts != null) parsedExpiresAt = ts
                }
            }
        }

        val finalExpiresAt = when {
            hasMaxAge && maxAgeExpiresAt != null -> maxAgeExpiresAt
            parsedExpiresAt != null -> parsedExpiresAt
            else -> MAX_OKHTTP_DATE
        }

        return StoredCookie(
            name = name,
            value = value,
            domain = domain,
            hostOnly = hostOnly,
            path = cookiePath,
            secure = secure,
            httpOnly = httpOnly,
            expiresAt = finalExpiresAt,
        )
    }

    private fun flushLocked() {
        val serialized = serializeLocked()
        if (serialized.size > maxBytes) {
            throw CookieJarQuotaExceededException("插件 Cookie 罐超过大小上限（${maxBytes / 1024} KB）")
        }
        estimatedBytes = serialized.size
        file.parentFile?.mkdirs()
        val encrypted = secretBox.encrypt(serialized)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(file)) {
            file.writeBytes(encrypted)
            tmp.delete()
        }
        dirty = false
        diskWriteCount += 1
    }

    private fun serializeLocked(): ByteArray {
        val arr = buildJsonArray {
            for (c in store.values) {
                add(
                    buildJsonObject {
                        put("name", c.name)
                        put("value", c.value)
                        put("domain", c.domain)
                        put("path", c.path)
                        put("expiresAt", c.expiresAt)
                        put("secure", c.secure)
                        put("httpOnly", c.httpOnly)
                        put("hostOnly", c.hostOnly)
                    },
                )
            }
        }
        return arr.toString().toByteArray(Charsets.UTF_8)
    }

    private fun ensureLoadedLocked() {
        loadFailure?.let { throw it }
        if (loaded) return
        if (!file.exists()) {
            loaded = true
            return
        }
        try {
            val raw = file.readBytes()
            if (raw.isEmpty()) {
                loaded = true
                return
            }
            val plain = secretBox.decrypt(raw)
            val arr = json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonArray
                ?: throw StorageCorruptedException("Cookie 罐文件格式无效")
            val now = clockMs()
            store.clear()
            for (el in arr) {
                val obj = el as? JsonObject ?: continue
                val name = (obj["name"] as? JsonPrimitive)?.content ?: continue
                val value = (obj["value"] as? JsonPrimitive)?.content ?: continue
                val domain = NetworkPolicy.canonicalizeHost((obj["domain"] as? JsonPrimitive)?.content ?: continue)
                val path = (obj["path"] as? JsonPrimitive)?.content ?: "/"
                val expiresAt = (obj["expiresAt"] as? JsonPrimitive)?.longOrNull ?: continue
                if (expiresAt <= now) continue
                val secure = (obj["secure"] as? JsonPrimitive)?.booleanOrNull ?: false
                val httpOnly = (obj["httpOnly"] as? JsonPrimitive)?.booleanOrNull ?: false
                val hostOnly = (obj["hostOnly"] as? JsonPrimitive)?.booleanOrNull ?: false
                val c = StoredCookie(
                    name = name,
                    value = value,
                    domain = domain,
                    hostOnly = hostOnly,
                    path = path,
                    secure = secure,
                    httpOnly = httpOnly,
                    expiresAt = expiresAt,
                )
                store[CookieKey(c.name, c.domain, c.path)] = c
            }
            estimatedBytes = plain.size
            loaded = true
        } catch (e: StorageCorruptedException) {
            loadFailure = e
            throw e
        } catch (e: Exception) {
            val ex = StorageCorruptedException("无法解密插件 Cookie 罐，已保留原文件。", e)
            loadFailure = ex
            throw ex
        }
    }

    private fun estimateEntryBytes(c: StoredCookie): Int =
        140 +
            c.name.toByteArray(Charsets.UTF_8).size * 2 +
            c.value.toByteArray(Charsets.UTF_8).size * 2 +
            c.domain.toByteArray(Charsets.UTF_8).size +
            c.path.toByteArray(Charsets.UTF_8).size

    companion object {
        /** §11.1: cookie jar cap is 256 KB. */
        const val MAX_COOKIE_JAR_BYTES = 256 * 1024
        private const val MAX_OKHTTP_DATE = 253_402_300_799_999L

        private val MULTI_LABEL_PUBLIC_SUFFIXES = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "net.uk",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
            "com.hk", "org.hk", "edu.hk", "gov.hk",
            "com.tw", "org.tw", "edu.tw", "gov.tw",
            "com.sg", "com.br", "com.mx", "co.kr", "co.nz", "co.in",
            "github.io", "pages.dev", "vercel.app", "netlify.app", "herokuapp.com",
        )

        private val HTTP_DATE_FORMATS = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
            "EEE, d MMM yyyy HH:mm:ss zzz",
        )

        fun isPublicSuffix(canonicalDomain: String): Boolean {
            if (canonicalDomain.isEmpty()) return true
            if (NetworkPolicy.parseAnyIpLiteral(canonicalDomain) != null) return false
            if (!canonicalDomain.contains('.')) return true // single-label TLD (com, org, cn, localhost, etc.)
            return canonicalDomain in MULTI_LABEL_PUBLIC_SUFFIXES
        }

        fun domainMatches(canonicalHost: String, canonicalDomain: String): Boolean {
            if (canonicalHost == canonicalDomain) return true
            if (NetworkPolicy.parseAnyIpLiteral(canonicalHost) != null) return false
            return canonicalHost.endsWith(".$canonicalDomain")
        }

        private fun defaultPath(encodedPath: String): String {
            if (encodedPath.isEmpty() || !encodedPath.startsWith("/")) return "/"
            val lastSlash = encodedPath.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else encodedPath.substring(0, lastSlash)
        }

        private fun parseHttpDate(raw: String): Long? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            for (pattern in HTTP_DATE_FORMATS) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                        timeZone = TimeZone.getTimeZone("GMT")
                    }
                    val date = sdf.parse(trimmed)
                    if (date != null) return date.time
                } catch (_: Exception) {
                }
            }
            return null
        }

        /**
         * §11.1: Merges an explicit `Cookie` request header on top of cookies
         * from the instance jar; explicit values win when cookie names collide.
         */
        fun mergeCookieHeaders(jarHeader: String, explicitHeader: String?): String {
            if (explicitHeader.isNullOrBlank()) return jarHeader
            if (jarHeader.isBlank()) return explicitHeader.trim()
            val merged = LinkedHashMap<String, String>()
            fun ingest(raw: String) {
                for (part in raw.split(';')) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val k = trimmed.substring(0, eq).trim()
                    val v = trimmed.substring(eq + 1).trim()
                    if (k.isNotEmpty()) merged[k] = v
                }
            }
            ingest(jarHeader)
            ingest(explicitHeader)
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }
}
