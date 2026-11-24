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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

class CookieJarQuotaExceededException(message: String) : IllegalStateException(message)

/**
 * Per-instance encrypted cookie jar backing `ctx.fetch` and `ctx.cookies`
 * (§5, §11.1, S5):
 * - Capped at 256 KB serialized JSON size
 * - Coalesces disk writes (`dirty` flag flushed once per plugin invocation via
 *   [flush], or immediately when [autoFlush] is true) so thousands of cookie
 *   updates complete in milliseconds without rewriting the file every time
 * - Preserves original file and throws [StorageCorruptedException] if
 *   decryption fails
 */
class PluginCookieJar(
    private val file: File,
    private val secretBox: SecretBox,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxBytes: Int = MAX_COOKIE_JAR_BYTES,
    private val autoFlush: Boolean = false,
) : CookieJar {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = ReentrantReadWriteLock()
    private val store = LinkedHashMap<CookieKey, Cookie>()
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

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
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
                        // Compute exact serialized byte count before rejecting
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

    override fun loadForRequest(url: HttpUrl): List<Cookie> = lock.write {
        ensureLoadedLocked()
        val now = clockMs()
        var evicted = false
        val iter = store.entries.iterator()
        val matched = ArrayList<Cookie>()
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
        val cookies = loadForRequest(url)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun setCookie(rawUrl: String, setCookieHeader: String) {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid cookie URL: $rawUrl")
        val cookie = Cookie.parse(url, setCookieHeader.trim())
            ?: throw IllegalArgumentException("Invalid Set-Cookie header")
        saveFromResponse(url, listOf(cookie))
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
                val domain = (obj["domain"] as? JsonPrimitive)?.content ?: continue
                val path = (obj["path"] as? JsonPrimitive)?.content ?: "/"
                val expiresAt = (obj["expiresAt"] as? JsonPrimitive)?.longOrNull ?: continue
                if (expiresAt <= now) continue
                val secure = (obj["secure"] as? JsonPrimitive)?.booleanOrNull ?: false
                val httpOnly = (obj["httpOnly"] as? JsonPrimitive)?.booleanOrNull ?: false
                val hostOnly = (obj["hostOnly"] as? JsonPrimitive)?.booleanOrNull ?: false
                val builder = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path(path)
                    .expiresAt(expiresAt)
                if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
                if (secure) builder.secure()
                if (httpOnly) builder.httpOnly()
                val c = builder.build()
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

    private fun estimateEntryBytes(c: Cookie): Int =
        140 +
            c.name.toByteArray(Charsets.UTF_8).size * 2 +
            c.value.toByteArray(Charsets.UTF_8).size * 2 +
            c.domain.toByteArray(Charsets.UTF_8).size +
            c.path.toByteArray(Charsets.UTF_8).size

    companion object {
        /** §11.1: cookie jar cap is 256 KB. */
        const val MAX_COOKIE_JAR_BYTES = 256 * 1024

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
