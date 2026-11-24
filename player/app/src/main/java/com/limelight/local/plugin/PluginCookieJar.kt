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

/**
 * Per-instance encrypted cookie jar backing both `ctx.fetch` (OkHttp
 * [CookieJar]) and `ctx.cookies` (`get`, `set`, `clear`).
 */
class PluginCookieJar(
    private val file: File,
    private val secretBox: SecretBox,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : CookieJar {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = ReentrantReadWriteLock()
    private val store = LinkedHashMap<CookieKey, Cookie>()
    private var loaded = false

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
                if (cookie.expiresAt <= now) {
                    if (store.remove(key) != null) changed = true
                } else {
                    store[key] = cookie
                    changed = true
                }
            }
            if (changed) persistLocked()
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
                evicted = true
            } else if (c.matches(url)) {
                matched += c
            }
        }
        if (evicted) persistLocked()
        matched
    }

    /**
     * Returns the formatted `Cookie` header string (`a=1; b=2`) matching [rawUrl],
     * suitable for passing directly in `PlaybackInfo.headers["Cookie"]`.
     */
    fun getHeaderForUrl(rawUrl: String): String {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid cookie URL: $rawUrl")
        val cookies = loadForRequest(url)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Parses a `Set-Cookie` header value (or simple `name=value`) for [rawUrl]
     * and stores it in this instance's cookie jar.
     */
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
        if (file.exists()) {
            file.delete()
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val raw = file.readBytes()
        if (raw.isEmpty()) return
        val plain = secretBox.decrypt(raw)
        val arr = json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonArray ?: return
        val now = clockMs()
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
    }

    private fun persistLocked() {
        file.parentFile?.mkdirs()
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
        val encrypted = secretBox.encrypt(arr.toString().toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(file)) {
            file.writeBytes(encrypted)
            tmp.delete()
        }
    }
}
