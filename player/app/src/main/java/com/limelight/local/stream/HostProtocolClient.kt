package com.limelight.local.stream

import android.content.Context
import com.limelight.LimeLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Persists TOFU (Trust On First Use) SHA-256 certificate fingerprints per depth3d Host id. */
interface HostCertPinStore {
    fun getFingerprint(hostId: String): String?
    fun saveFingerprint(hostId: String, sha256Hex: String)
    fun clearFingerprint(hostId: String)
}

class InMemoryHostCertPinStore : HostCertPinStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun getFingerprint(hostId: String): String? = map[hostId]
    @Synchronized override fun saveFingerprint(hostId: String, sha256Hex: String) { map[hostId] = sha256Hex }
    @Synchronized override fun clearFingerprint(hostId: String) { map.remove(hostId) }
}

class SharedPrefsHostCertPinStore(context: Context) : HostCertPinStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override fun getFingerprint(hostId: String): String? = prefs.getString(hostId, null)
    override fun saveFingerprint(hostId: String, sha256Hex: String) {
        prefs.edit().putString(hostId, sha256Hex).apply()
    }
    override fun clearFingerprint(hostId: String) {
        prefs.edit().remove(hostId).apply()
    }
    companion object {
        private const val PREFS_NAME = "depth3d_host_cert_pins"
    }
}

class HostCertificateChangedException(
    val hostId: String,
    val expectedFingerprint: String,
    val actualFingerprint: String,
) : CertificateException("Host certificate changed for $hostId")

data class HostStreamStatus(
    val ready: Boolean,
    val reason: String?,
)

data class HostMediaConfig(
    val port: Int,
)

data class HostInfo(
    val version: Int,
    val name: String,
    val id: String,
    val os: String,
    val stream: HostStreamStatus,
    val media: HostMediaConfig?,
)

data class WebDavCredentials(
    val port: Int,
    val user: String,
    val pass: String,
)

sealed interface HostInfoResult {
    data class Ok(val info: HostInfo) : HostInfoResult
    data object UpgradeRequired : HostInfoResult
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : HostInfoResult
    data class Failed(val detail: String) : HostInfoResult
}

sealed interface AutoPairResult {
    data object Approved : AutoPairResult
    data object Rejected : AutoPairResult
    data object TimedOut : AutoPairResult
    data object UpgradeRequired : AutoPairResult
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : AutoPairResult
    data class Failed(val detail: String) : AutoPairResult
}

sealed interface MediaCredsResult {
    data class Available(val creds: WebDavCredentials) : MediaCredsResult
    data object Unauthorized : MediaCredsResult
    data object NotConfigured : MediaCredsResult
    data object UpgradeRequired : MediaCredsResult
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : MediaCredsResult
    data class Failed(val detail: String) : MediaCredsResult
}

interface HostProtocolApi {
    fun getInfo(
        address: String,
        port: Int,
        hostId: String,
        trustChangedCert: Boolean = false,
    ): HostInfoResult

    fun requestAutoPair(
        address: String,
        port: Int,
        hostId: String,
        deviceName: String,
        deviceId: String,
        pin: String,
        trustChangedCert: Boolean = false,
    ): AutoPairResult

    fun getMediaCredentials(
        address: String,
        port: Int,
        hostId: String,
        deviceId: String,
        trustChangedCert: Boolean = false,
    ): MediaCredsResult
}

/**
 * Client for the depth3d Host v1 HTTPS control API (docs/specs/host-protocol.md).
 *
 * Uses self-signed TLS with TOFU certificate fingerprint pinning per host id, and presents
 * the headset's client certificate (when available) so /depth3d/v1/media can authenticate
 * paired headsets. Never logs PINs, passwords, or key material.
 */
class HttpHostProtocolClient(
    private val pinStore: HostCertPinStore,
    private val clientIdentityProvider: () -> Pair<X509Certificate, PrivateKey>? = { null },
    private val connectTimeoutMs: Long = 5_000L,
    private val infoReadTimeoutMs: Long = 8_000L,
    private val pairReadTimeoutMs: Long = 70_000L,
) : HostProtocolApi {

    companion object {
        const val PROTOCOL_VERSION = 1
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val HEADER_DEVICE = "X-Depth3d-Device"

        internal fun sha256Hex(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }

        internal fun formatHostUrl(address: String, port: Int, path: String): String {
            val host = if (address.contains(':') && !address.startsWith('[')) "[$address]" else address
            return "https://$host:$port$path"
        }

        internal fun escapeJson(value: String): String {
            val sb = StringBuilder(value.length + 8)
            for (ch in value) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            return sb.toString()
        }

        internal fun parseHostInfoJson(raw: String): HostInfo? {
            val root = MiniJson.parseObject(raw) ?: return null
            val version = root.int("v") ?: return null
            val name = root.string("name") ?: return null
            val id = root.string("id") ?: return null
            val os = root.string("os") ?: ""
            val streamObj = root.obj("stream")
            val streamReady = streamObj?.bool("ready") ?: true
            val streamReason = streamObj?.string("reason")?.takeIf { it.isNotBlank() }
            val mediaObj = root.obj("media")
            val mediaPort = mediaObj?.int("port")?.takeIf { it in 1..65535 }
            return HostInfo(
                version = version,
                name = name,
                id = id,
                os = os,
                stream = HostStreamStatus(streamReady, streamReason),
                media = mediaPort?.let { HostMediaConfig(it) },
            )
        }

        internal fun parseMediaCredsJson(raw: String): WebDavCredentials? {
            val root = MiniJson.parseObject(raw) ?: return null
            val webdav = root.obj("webdav") ?: return null
            val port = webdav.int("port")?.takeIf { it in 1..65535 } ?: return null
            val user = webdav.string("user") ?: return null
            val pass = webdav.string("pass") ?: return null
            return WebDavCredentials(port = port, user = user, pass = pass)
        }

        internal fun parsePairOkJson(raw: String): Boolean {
            val root = MiniJson.parseObject(raw) ?: return false
            return root.bool("ok") == true
        }
    }

    override fun getInfo(
        address: String,
        port: Int,
        hostId: String,
        trustChangedCert: Boolean,
    ): HostInfoResult {
        val url = formatHostUrl(address, port, "/depth3d/v1/info")
        LimeLog.info("depth3d: host GET /depth3d/v1/info -> $address:$port")
        return try {
            val client = buildClient(hostId, trustChangedCert, infoReadTimeoutMs)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val body = resp.body.string()
                        val info = parseHostInfoJson(body)
                            ?: return HostInfoResult.Failed("电脑返回的信息格式无法识别")
                        if (info.version != PROTOCOL_VERSION) {
                            HostInfoResult.UpgradeRequired
                        } else {
                            HostInfoResult.Ok(info)
                        }
                    }
                    426 -> HostInfoResult.UpgradeRequired
                    else -> HostInfoResult.Failed("HTTP ${resp.code}")
                }
            }
        } catch (e: Throwable) {
            val changed = findCertChanged(e)
            if (changed != null) {
                LimeLog.warning("depth3d: host certificate changed for $hostId")
                HostInfoResult.CertificateChanged(changed.hostId, changed.expectedFingerprint, changed.actualFingerprint)
            } else {
                LimeLog.warning("depth3d: host info failed ($address:$port): ${e.javaClass.simpleName}")
                HostInfoResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override fun requestAutoPair(
        address: String,
        port: Int,
        hostId: String,
        deviceName: String,
        deviceId: String,
        pin: String,
        trustChangedCert: Boolean,
    ): AutoPairResult {
        val url = formatHostUrl(address, port, "/depth3d/v1/pair")
        val payload = "{\"device\":\"${escapeJson(deviceName)}\",\"deviceId\":\"${escapeJson(deviceId)}\",\"pin\":\"${escapeJson(pin)}\"}"
        LimeLog.info("depth3d: host POST /depth3d/v1/pair -> $address:$port (device=$deviceName)")
        return try {
            val client = buildClient(hostId, trustChangedCert, pairReadTimeoutMs)
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                when (resp.code) {
                    200 -> if (parsePairOkJson(body)) AutoPairResult.Approved else AutoPairResult.Failed("pair response not ok")
                    403 -> AutoPairResult.Rejected
                    408 -> AutoPairResult.TimedOut
                    426 -> AutoPairResult.UpgradeRequired
                    else -> AutoPairResult.Failed("HTTP ${resp.code}")
                }
            }
        } catch (e: Throwable) {
            val changed = findCertChanged(e)
            if (changed != null) {
                LimeLog.warning("depth3d: host certificate changed during pair for $hostId")
                AutoPairResult.CertificateChanged(changed.hostId, changed.expectedFingerprint, changed.actualFingerprint)
            } else {
                LimeLog.warning("depth3d: host pair failed ($address:$port): ${e.javaClass.simpleName}")
                AutoPairResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override fun getMediaCredentials(
        address: String,
        port: Int,
        hostId: String,
        deviceId: String,
        trustChangedCert: Boolean,
    ): MediaCredsResult {
        val url = formatHostUrl(address, port, "/depth3d/v1/media")
        LimeLog.info("depth3d: host GET /depth3d/v1/media -> $address:$port")
        return try {
            val client = buildClient(hostId, trustChangedCert, infoReadTimeoutMs)
            val request = Request.Builder()
                .url(url)
                .header(HEADER_DEVICE, deviceId)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val creds = parseMediaCredsJson(resp.body.string())
                        if (creds != null) MediaCredsResult.Available(creds) else MediaCredsResult.NotConfigured
                    }
                    401 -> MediaCredsResult.Unauthorized
                    404 -> MediaCredsResult.NotConfigured
                    426 -> MediaCredsResult.UpgradeRequired
                    else -> MediaCredsResult.Failed("HTTP ${resp.code}")
                }
            }
        } catch (e: Throwable) {
            val changed = findCertChanged(e)
            if (changed != null) {
                LimeLog.warning("depth3d: host certificate changed during media query for $hostId")
                MediaCredsResult.CertificateChanged(changed.hostId, changed.expectedFingerprint, changed.actualFingerprint)
            } else {
                LimeLog.warning("depth3d: host media query failed ($address:$port): ${e.javaClass.simpleName}")
                MediaCredsResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun buildClient(hostId: String, trustChangedCert: Boolean, readTimeoutMs: Long): OkHttpClient {
        val trustManager = TofuTrustManager(pinStore, hostId, trustChangedCert)
        val keyManagers = buildClientKeyManagers()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Self-signed LAN host certificates are pinned by SHA-256 fingerprint via TofuTrustManager.
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    private fun buildClientKeyManagers(): Array<KeyManager>? {
        val identity = try {
            clientIdentityProvider()
        } catch (e: Exception) {
            LimeLog.warning("depth3d: client identity unavailable for host TLS: ${e.javaClass.simpleName}")
            null
        } ?: return null

        return try {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("client", identity.second, CharArray(0), arrayOf(identity.first))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, CharArray(0))
            kmf.keyManagers
        } catch (e: Exception) {
            LimeLog.warning("depth3d: failed to build client KeyManager: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun findCertChanged(e: Throwable): HostCertificateChangedException? {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is HostCertificateChangedException) {
                return cur
            }
            cur = cur.cause
        }
        return null
    }

    private class TofuTrustManager(
        private val store: HostCertPinStore,
        private val hostId: String,
        private val trustChangedCert: Boolean,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Empty server certificate chain")
            }
            val fingerprint = sha256Hex(chain[0])
            val saved = store.getFingerprint(hostId)
            when {
                saved == null -> store.saveFingerprint(hostId, fingerprint)
                saved.equals(fingerprint, ignoreCase = true) -> Unit
                trustChangedCert -> store.saveFingerprint(hostId, fingerprint)
                else -> throw HostCertificateChangedException(hostId, saved, fingerprint)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/**
 * Minimal pure-JVM JSON object reader so HostProtocolClient runs identically on Android
 * and in pure JVM unit tests without depending on Android's stubbed org.json.
 */
internal class MiniJson private constructor(private val entries: Map<String, Any?>) {
    fun string(key: String): String? = entries[key] as? String
    fun bool(key: String): Boolean? = entries[key] as? Boolean
    fun int(key: String): Int? = (entries[key] as? Number)?.toInt()
    fun obj(key: String): MiniJson? = entries[key] as? MiniJson

    companion object {
        fun parseObject(src: String): MiniJson? {
            return try {
                val p = Parser(src)
                val v = p.parseValue()
                p.skipWs()
                if (p.pos != src.length) null else v as? MiniJson
            } catch (_: Exception) {
                null
            }
        }
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (pos >= s.length) throw IOException("EOF")
            return when (s[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNum()
            }
        }

        private fun parseObj(): MiniJson {
            pos++ // '{'
            skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return MiniJson(map)
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw IOException("Expected key")
                val k = parseStr()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw IOException("Expected :")
                pos++
                val v = parseValue()
                map[k] = v
                skipWs()
                if (pos >= s.length) throw IOException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return MiniJson(map)
                    }
                    else -> throw IOException("Expected , or }")
                }
            }
        }

        private fun parseArr(): List<Any?> {
            pos++ // '['
            skipWs()
            val list = ArrayList<Any?>()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list += parseValue()
                skipWs()
                if (pos >= s.length) throw IOException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IOException("Expected , or ]")
                }
            }
        }

        private fun parseStr(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw IOException("Bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IOException("Bad unicode")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IOException("Unknown escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IOException("Unterminated string")
        }

        private fun parseBool(): Boolean = when {
            s.startsWith("true", pos) -> { pos += 4; true }
            s.startsWith("false", pos) -> { pos += 5; false }
            else -> throw IOException("Bad boolean")
        }

        private fun parseNull(): Any? {
            if (!s.startsWith("null", pos)) throw IOException("Bad null")
            pos += 4
            return null
        }

        private fun parseNum(): Number {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) {
                if ((s[pos] == '+' || s[pos] == '-') && pos > start && s[pos - 1] != 'e' && s[pos - 1] != 'E') break
                pos++
            }
            val token = s.substring(start, pos)
            return if (token.contains('.') || token.contains('e') || token.contains('E')) {
                token.toDouble()
            } else {
                token.toLong()
            }
        }
    }
}
