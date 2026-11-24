package com.limelight.local.stream

import android.content.Context
import com.limelight.LimeLog
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Thread-safe cancellation token that immediately cancels in-flight OkHttp calls and custom hooks. */
class CallCancelToken {
    private val cancelled = AtomicBoolean(false)
    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean get() = cancelled.get()

    fun register(onCancel: () -> Unit): () -> Unit {
        hooks.add(onCancel)
        if (cancelled.get()) {
            hooks.remove(onCancel)
            try {
                onCancel()
            } catch (_: Throwable) {
            }
        }
        return { hooks.remove(onCancel) }
    }

    fun registerCall(call: Call): () -> Unit = register { call.cancel() }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val snapshot = hooks.toList()
        hooks.clear()
        for (hook in snapshot) {
            try {
                hook()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * Cryptographic binding persisted ONLY after a user-initiated pair completes successfully against
 * a verified depth3d Host. Binds the Host TLS certificate fingerprint to the paired GameStream
 * computer UUID and GameStream server certificate fingerprint.
 */
data class HostPairedTrust(
    val hostFingerprint: String,
    val computerUuid: String,
    val gsCertFingerprint: String,
    val savedName: String? = null,
)

/** Persists pinned Host TLS SHA-256 fingerprints and pairing trust per depth3d Host id. */
interface HostCertPinStore {
    fun getFingerprint(hostId: String): String?
    fun getPairedTrust(hostId: String): HostPairedTrust?
    fun getAllPairedTrusts(): Map<String, HostPairedTrust>
    fun savePairedTrust(hostId: String, trust: HostPairedTrust)
    fun clearFingerprint(hostId: String)
}

class InMemoryHostCertPinStore : HostCertPinStore {
    private val trusts = HashMap<String, HostPairedTrust>()

    @Synchronized
    override fun getFingerprint(hostId: String): String? = trusts[hostId]?.hostFingerprint

    @Synchronized
    override fun getPairedTrust(hostId: String): HostPairedTrust? = trusts[hostId]

    @Synchronized
    override fun getAllPairedTrusts(): Map<String, HostPairedTrust> = LinkedHashMap(trusts)

    @Synchronized
    override fun savePairedTrust(hostId: String, trust: HostPairedTrust) {
        require(trust.hostFingerprint.isNotBlank() && trust.computerUuid.isNotBlank() && trust.gsCertFingerprint.isNotBlank()) {
            "HostPairedTrust requires non-blank hostFingerprint, computerUuid, and gsCertFingerprint"
        }
        trusts[hostId] = trust
    }

    @Synchronized
    override fun clearFingerprint(hostId: String) {
        trusts.remove(hostId)
    }
}

class SharedPrefsHostCertPinStore(context: Context) : HostCertPinStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getFingerprint(hostId: String): String? = prefs.getString(hostId, null)

    override fun getPairedTrust(hostId: String): HostPairedTrust? {
        val savedFp = prefs.getString(hostId, null) ?: return null
        val raw = prefs.getString(PAIRED_PREFIX + hostId, null) ?: return null
        val parts = raw.split('|', limit = 4)
        if (parts.size < 3) return null
        val fp = parts[0]
        val uuid = parts[1]
        val gsFp = parts[2]
        val savedName = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        if (fp.isBlank() || uuid.isBlank() || gsFp.isBlank()) return null
        if (!fp.equals(savedFp, ignoreCase = true)) return null
        return HostPairedTrust(
            hostFingerprint = fp,
            computerUuid = uuid,
            gsCertFingerprint = gsFp,
            savedName = savedName,
        )
    }

    override fun getAllPairedTrusts(): Map<String, HostPairedTrust> {
        val out = LinkedHashMap<String, HostPairedTrust>()
        for (key in prefs.all.keys) {
            if (!key.startsWith(PAIRED_PREFIX)) continue
            val hostId = key.removePrefix(PAIRED_PREFIX)
            val trust = getPairedTrust(hostId) ?: continue
            out[hostId] = trust
        }
        return out
    }

    override fun savePairedTrust(hostId: String, trust: HostPairedTrust) {
        if (trust.hostFingerprint.isBlank() || trust.computerUuid.isBlank() || trust.gsCertFingerprint.isBlank()) {
            LimeLog.warning("depth3d: refusing to persist incomplete HostPairedTrust for $hostId")
            return
        }
        val cleanName = trust.savedName.orEmpty().replace('\n', ' ').trim()
        prefs.edit()
            .putString(hostId, trust.hostFingerprint)
            .putString(
                PAIRED_PREFIX + hostId,
                "${trust.hostFingerprint}|${trust.computerUuid}|${trust.gsCertFingerprint}|$cleanName",
            )
            .apply()
    }

    override fun clearFingerprint(hostId: String) {
        prefs.edit()
            .remove(hostId)
            .remove(PAIRED_PREFIX + hostId)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "depth3d_host_cert_pins"
        private const val PAIRED_PREFIX = "paired_trust:"
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
    data class Ok(val info: HostInfo, val fingerprint: String) : HostInfoResult
    data class IdentityMismatch(val expectedId: String, val actualId: String) : HostInfoResult
    data object UpgradeRequired : HostInfoResult
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : HostInfoResult
    data object Cancelled : HostInfoResult
    data class Failed(val detail: String) : HostInfoResult
}

enum class PairNextStep {
    GAMESTREAM,
    DONE,
}

sealed interface AutoPairResult {
    data class Approved(
        val next: PairNextStep,
        val ticket: String,
        val clientCertFingerprint: String = "",
    ) : AutoPairResult

    data object ClientCertRequired : AutoPairResult
    data object Rejected : AutoPairResult
    data object TimedOut : AutoPairResult
    data class Busy(val retryAfterSec: Int? = null) : AutoPairResult
    data class Cooldown(val retryAfterSec: Int?) : AutoPairResult
    data object UpgradeRequired : AutoPairResult
    data class StreamServiceUnavailable(val reason: String? = null) : AutoPairResult
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : AutoPairResult
    data object Cancelled : AutoPairResult
    data class Failed(val detail: String) : AutoPairResult
}

sealed interface PairCompleteResult {
    data class Completed(val mediaPort: Int?) : PairCompleteResult
    data object NotPaired : PairCompleteResult
    data object TicketExpired : PairCompleteResult
    data object Unauthorized : PairCompleteResult
    data object Forbidden : PairCompleteResult
    data object UpgradeRequired : PairCompleteResult
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : PairCompleteResult
    data object Cancelled : PairCompleteResult
    data class Failed(val detail: String) : PairCompleteResult
}

sealed interface MediaCredsResult {
    data class Available(val creds: WebDavCredentials) : MediaCredsResult
    data object Unauthorized : MediaCredsResult
    data object NotConfigured : MediaCredsResult
    data object UpgradeRequired : MediaCredsResult
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : MediaCredsResult
    data object Cancelled : MediaCredsResult
    data class Failed(val detail: String) : MediaCredsResult
}

interface HostProtocolApi {
    /**
     * Probes `/depth3d/v1/info` without ever persisting a certificate pin (B3/S2).
     * Verifies `info.id == hostId` and protocol version, returning the observed TLS cert
     * fingerprint in memory.
     */
    fun getInfo(
        address: String,
        port: Int,
        hostId: String,
        approvedNewFingerprint: String? = null,
        cancelToken: CallCancelToken? = null,
    ): HostInfoResult

    /**
     * Step 1 of two-step Host pairing (`POST /depth3d/v1/pair`, docs/specs/host-protocol.md):
     * ALWAYS presents the headset's pairing TLS client certificate (generating it first if absent)
     * and sends `streamPaired: true|false`. Strictly pinned to [expectedFingerprint] from a verified
     * [getInfo] call. Returns `(next, ticket, clientCertFingerprint)` without waiting for GameStream.
     */
    fun requestAutoPair(
        address: String,
        port: Int,
        hostId: String,
        deviceName: String,
        deviceId: String,
        pin: String,
        expectedFingerprint: String,
        streamPaired: Boolean = false,
        approvedNewFingerprint: String? = null,
        cancelToken: CallCancelToken? = null,
    ): AutoPairResult

    /**
     * Step 2 of two-step Host pairing (`POST /depth3d/v1/pair/complete`, docs/specs/host-protocol.md):
     * called with the SAME pairing TLS client certificate and [ticket] after GameStream pairing succeeds
     * (or immediately when `/pair` returned `next="done"` for a verified upgrade).
     * Never writes [pinStore]; the caller commits [HostPairedTrust] only after receiving `200 OK`.
     */
    fun completeAutoPair(
        address: String,
        port: Int,
        hostId: String,
        ticket: String,
        deviceId: String,
        deviceName: String,
        expectedFingerprint: String,
        expectedClientCertFingerprint: String? = null,
        approvedNewFingerprint: String? = null,
        cancelToken: CallCancelToken? = null,
    ): PairCompleteResult

    /**
     * Requests `/depth3d/v1/media` ONLY if [hostId] has a persisted [HostPairedTrust] matching
     * [expectedComputerUuid] and [expectedGsCertFingerprint], AND the TLS handshake certificate
     * matches the persisted Host fingerprint. Refuses to dial, send client cert, or accept WebDAV
     * credentials otherwise (B1).
     */
    fun getMediaCredentials(
        address: String,
        port: Int,
        hostId: String,
        deviceId: String,
        expectedComputerUuid: String,
        expectedGsCertFingerprint: String,
        cancelToken: CallCancelToken? = null,
    ): MediaCredsResult
}

/**
 * Client for the depth3d Host v1 HTTPS control API (docs/specs/host-protocol.md).
 *
 * Security guarantees:
 * - `/info` never persists a certificate pin; first-use discovery stays in memory only (B3).
 * - `/pair` requires an explicit [expectedFingerprint] from a verified `/info` (`info.id == hostId`),
 *   uses read timeout >= 75 s, and never persists trust on any status code.
 * - `/pair/complete` sends the client certificate + ticket strictly pinned to [expectedFingerprint];
 *   Host trust is only saved by the orchestrator after `/pair/complete` returns `200 OK`.
 * - `/media` strictly requires a persisted [HostPairedTrust] binding (saved Host fingerprint +
 *   paired computer UUID + paired GameStream cert fingerprint) and verifies the TLS server cert
 *   matches that exact saved fingerprint before sending the client certificate (B1).
 * - Never logs PINs, tickets, passwords, or key material.
 */
class HttpHostProtocolClient(
    private val pinStore: HostCertPinStore,
    private val clientIdentityProvider: () -> Pair<X509Certificate, PrivateKey>? = { null },
    private val connectTimeoutMs: Long = 5_000L,
    private val infoReadTimeoutMs: Long = 8_000L,
    private val pairReadTimeoutMs: Long = DEFAULT_PAIR_READ_TIMEOUT_MS,
) : HostProtocolApi {

    internal val configuredPairReadTimeoutMs: Long get() = pairReadTimeoutMs

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MIN_PAIR_READ_TIMEOUT_MS = 75_000L
        const val DEFAULT_PAIR_READ_TIMEOUT_MS = 80_000L
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

        /** Formats a full 64-char SHA-256 hex string into readable 4-char groups across 2 lines. */
        fun formatFingerprint(sha256Hex: String): String {
            val clean = sha256Hex.trim().uppercase(Locale.ROOT)
            if (clean.isEmpty()) return ""
            val groups = clean.chunked(4)
            return if (groups.size > 8) {
                groups.take(8).joinToString(" ") + "\n" + groups.drop(8).joinToString(" ")
            } else {
                groups.joinToString(" ")
            }
        }

        internal fun formatAuthorityHost(address: String): String {
            val trimmed = address.trim()
            return if (trimmed.contains(':') && !trimmed.startsWith('[')) "[$trimmed]" else trimmed
        }

        internal fun formatHostUrl(address: String, port: Int, path: String): String =
            "https://${formatAuthorityHost(address)}:$port$path"

        /** Formats an HTTP base URL for WebDAV, bracketing IPv6 literals per RFC 3986 (M5). */
        fun formatWebDavUrl(address: String, port: Int): String =
            "http://${formatAuthorityHost(address)}:$port"

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

        internal fun parsePairApprovedJson(raw: String): AutoPairResult.Approved? {
            val root = MiniJson.parseObject(raw) ?: return null
            if (root.bool("ok") != true) return null
            val next = when (root.string("next")?.lowercase(Locale.ROOT)) {
                "gamestream" -> PairNextStep.GAMESTREAM
                "done" -> PairNextStep.DONE
                else -> return null
            }
            val ticket = root.string("ticket")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return AutoPairResult.Approved(next = next, ticket = ticket)
        }

        internal fun parsePairCompleteJson(raw: String): PairCompleteResult.Completed? {
            val root = MiniJson.parseObject(raw) ?: return null
            if (root.bool("ok") != true) return null
            val mediaPort = root.obj("media")?.int("port")?.takeIf { it in 1..65535 }
            return PairCompleteResult.Completed(mediaPort = mediaPort)
        }

        internal fun parsePairRateLimitJson(raw: String): AutoPairResult {
            val root = MiniJson.parseObject(raw)
            val err = root?.string("error")?.lowercase(Locale.ROOT)
            val retryAfter = root?.int("retryAfterSec")?.takeIf { it > 0 }
            return when (err) {
                "cooldown" -> AutoPairResult.Cooldown(retryAfterSec = retryAfter)
                "busy" -> AutoPairResult.Busy(retryAfterSec = retryAfter)
                else -> if (retryAfter != null) AutoPairResult.Cooldown(retryAfter) else AutoPairResult.Busy(null)
            }
        }

        internal fun parseStreamNotReadyReason(raw: String): String? {
            val root = MiniJson.parseObject(raw) ?: return null
            return root.string("reason")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    override fun getInfo(
        address: String,
        port: Int,
        hostId: String,
        approvedNewFingerprint: String?,
        cancelToken: CallCancelToken?,
    ): HostInfoResult {
        if (cancelToken?.isCancelled == true) return HostInfoResult.Cancelled
        val url = formatHostUrl(address, port, "/depth3d/v1/info")
        LimeLog.info("depth3d: host GET /depth3d/v1/info -> $address:$port")
        val observed = AtomicReference<String?>(null)
        val savedPin = pinStore.getFingerprint(hostId)
        val policy = CertPinPolicy.ObserveOrPinned(
            savedFingerprint = savedPin,
            approvedNewFingerprint = approvedNewFingerprint,
        )
        return try {
            val trackedSockets = CopyOnWriteArrayList<Socket>()
            val client = buildClient(
                hostId = hostId,
                policy = policy,
                presentClientCert = false,
                readTimeoutMs = infoReadTimeoutMs,
                observedFingerprintOut = observed,
                trackedSockets = trackedSockets,
                cancelToken = cancelToken,
            )
            val call = client.newCall(Request.Builder().url(url).get().build())
            val unregister = cancelToken?.register { closeCallAndSockets(call, client, trackedSockets) }
            try {
                call.execute().use { resp ->
                    when (resp.code) {
                        200 -> {
                            val body = resp.body.string()
                            val info = parseHostInfoJson(body)
                                ?: return HostInfoResult.Failed("invalid info json")
                            if (info.version != PROTOCOL_VERSION) {
                                return HostInfoResult.UpgradeRequired
                            }
                            if (!info.id.equals(hostId, ignoreCase = true)) {
                                LimeLog.warning("depth3d: host /info id mismatch (expected=$hostId actual=${info.id})")
                                return HostInfoResult.IdentityMismatch(hostId, info.id)
                            }
                            val fp = observed.get() ?: return HostInfoResult.Failed("missing server cert")
                            // Never persist pin during /info (B3/S2): return observed fingerprint in memory only.
                            HostInfoResult.Ok(info, fp)
                        }
                        426 -> HostInfoResult.UpgradeRequired
                        else -> HostInfoResult.Failed("HTTP ${resp.code}")
                    }
                }
            } finally {
                unregister?.invoke()
                closeSocketsQuietly(trackedSockets)
                client.connectionPool.evictAll()
            }
        } catch (e: Throwable) {
            if (cancelToken?.isCancelled == true) return HostInfoResult.Cancelled
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
        expectedFingerprint: String,
        streamPaired: Boolean,
        approvedNewFingerprint: String?,
        cancelToken: CallCancelToken?,
    ): AutoPairResult {
        if (cancelToken?.isCancelled == true) return AutoPairResult.Cancelled
        val expected = expectedFingerprint.trim()
        if (expected.isEmpty()) {
            return AutoPairResult.Failed("unverified host fingerprint")
        }

        val savedPin = pinStore.getFingerprint(hostId)
        if (savedPin != null && !savedPin.equals(expected, ignoreCase = true)) {
            if (approvedNewFingerprint == null || !approvedNewFingerprint.equals(expected, ignoreCase = true)) {
                return AutoPairResult.CertificateChanged(hostId, savedPin, expected)
            }
        }

        // Per host-protocol.md (3676b2b): /pair ALWAYS presents the headset's pairing client cert
        // (public identity; provider generates it on first use if absent). Ticket is bound to it.
        val clientIdentity = buildClientKeyManagersWithFingerprint()
        if (clientIdentity == null) {
            LimeLog.warning("depth3d: skipping /depth3d/v1/pair because local pairing client cert is unavailable")
            return AutoPairResult.ClientCertRequired
        }
        val (keyManagers, clientCertFp) = clientIdentity

        val url = formatHostUrl(address, port, "/depth3d/v1/pair")
        val payload = "{\"device\":\"${escapeJson(deviceName)}\",\"deviceId\":\"${escapeJson(deviceId)}\",\"pin\":\"${escapeJson(pin)}\",\"streamPaired\":$streamPaired}"
        LimeLog.info("depth3d: host POST /depth3d/v1/pair -> $address:$port (device=$deviceName, streamPaired=$streamPaired)")
        val observed = AtomicReference<String?>(null)
        val policy = CertPinPolicy.ExactOnly(
            expectedFingerprint = expected,
            reportedExpectedForError = savedPin ?: expected,
        )
        return try {
            val trackedSockets = CopyOnWriteArrayList<Socket>()
            val client = buildClient(
                hostId = hostId,
                policy = policy,
                presentClientCert = true,
                readTimeoutMs = pairReadTimeoutMs,
                observedFingerprintOut = observed,
                precomputedKeyManagers = keyManagers,
                trackedSockets = trackedSockets,
                cancelToken = cancelToken,
            )
            val request = Request.Builder()
                .url(url)
                .header("Connection", "close")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            val call = client.newCall(request)
            // S5: Truly tear down the underlying TCP/TLS socket on cancel so the Mac Host closes its
            // confirmation dialog immediately rather than keeping the connection open and returning busy!
            val unregister = cancelToken?.register { closeCallAndSockets(call, client, trackedSockets) }
            try {
                call.execute().use { resp ->
                    val body = resp.body.string()
                    val fp = observed.get()
                    if (fp == null || !fp.equals(expected, ignoreCase = true)) {
                        return AutoPairResult.Failed("unexpected server cert")
                    }
                    // Never write pinStore here on any response; trust is saved only after /pair/complete 200.
                    when (resp.code) {
                        200 -> parsePairApprovedJson(body)?.copy(clientCertFingerprint = clientCertFp)
                            ?: AutoPairResult.Failed("invalid pair response")
                        401 -> AutoPairResult.ClientCertRequired
                        403 -> AutoPairResult.Rejected
                        408 -> AutoPairResult.TimedOut
                        426 -> AutoPairResult.UpgradeRequired
                        429 -> parsePairRateLimitJson(body)
                        503 -> AutoPairResult.StreamServiceUnavailable(parseStreamNotReadyReason(body))
                        else -> AutoPairResult.Failed("HTTP ${resp.code}")
                    }
                }
            } finally {
                unregister?.invoke()
                closeSocketsQuietly(trackedSockets)
                client.connectionPool.evictAll()
            }
        } catch (e: Throwable) {
            if (cancelToken?.isCancelled == true) return AutoPairResult.Cancelled
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

    override fun completeAutoPair(
        address: String,
        port: Int,
        hostId: String,
        ticket: String,
        deviceId: String,
        deviceName: String,
        expectedFingerprint: String,
        expectedClientCertFingerprint: String?,
        approvedNewFingerprint: String?,
        cancelToken: CallCancelToken?,
    ): PairCompleteResult {
        if (cancelToken?.isCancelled == true) return PairCompleteResult.Cancelled
        val expected = expectedFingerprint.trim()
        if (expected.isEmpty() || ticket.isBlank()) {
            return PairCompleteResult.Failed("missing verified fingerprint or ticket")
        }

        val savedPin = pinStore.getFingerprint(hostId)
        if (savedPin != null && !savedPin.equals(expected, ignoreCase = true)) {
            if (approvedNewFingerprint == null || !approvedNewFingerprint.equals(expected, ignoreCase = true)) {
                return PairCompleteResult.CertificateChanged(hostId, savedPin, expected)
            }
        }

        val clientIdentity = buildClientKeyManagersWithFingerprint()
        if (clientIdentity == null) {
            LimeLog.warning("depth3d: skipping /depth3d/v1/pair/complete because pairing client cert is unavailable")
            return PairCompleteResult.Unauthorized
        }
        val (keyManagers, clientCertFp) = clientIdentity
        if (!expectedClientCertFingerprint.isNullOrBlank() &&
            !expectedClientCertFingerprint.equals(clientCertFp, ignoreCase = true)
        ) {
            // Ticket is bound to the client cert presented on /pair (host-protocol.md 3676b2b).
            LimeLog.warning("depth3d: local client cert changed between /pair and /pair/complete")
            return PairCompleteResult.TicketExpired
        }

        val url = formatHostUrl(address, port, "/depth3d/v1/pair/complete")
        val payload = "{\"ticket\":\"${escapeJson(ticket)}\",\"deviceId\":\"${escapeJson(deviceId)}\",\"device\":\"${escapeJson(deviceName)}\"}"
        LimeLog.info("depth3d: host POST /depth3d/v1/pair/complete -> $address:$port (device=$deviceName)")
        val observed = AtomicReference<String?>(null)
        val policy = CertPinPolicy.ExactOnly(
            expectedFingerprint = expected,
            reportedExpectedForError = savedPin ?: expected,
        )
        return try {
            val trackedSockets = CopyOnWriteArrayList<Socket>()
            val client = buildClient(
                hostId = hostId,
                policy = policy,
                presentClientCert = true,
                readTimeoutMs = infoReadTimeoutMs,
                observedFingerprintOut = observed,
                precomputedKeyManagers = keyManagers,
                trackedSockets = trackedSockets,
                cancelToken = cancelToken,
            )
            val request = Request.Builder()
                .url(url)
                .header("Connection", "close")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            val call = client.newCall(request)
            val unregister = cancelToken?.register { closeCallAndSockets(call, client, trackedSockets) }
            try {
                call.execute().use { resp ->
                    val body = resp.body.string()
                    val fp = observed.get()
                    if (fp == null || !fp.equals(expected, ignoreCase = true)) {
                        return PairCompleteResult.Failed("unexpected server cert")
                    }
                    when (resp.code) {
                        200 -> parsePairCompleteJson(body) ?: PairCompleteResult.Failed("invalid pair/complete response")
                        401 -> PairCompleteResult.Unauthorized
                        403 -> PairCompleteResult.Forbidden
                        409 -> PairCompleteResult.NotPaired
                        410 -> PairCompleteResult.TicketExpired
                        426 -> PairCompleteResult.UpgradeRequired
                        else -> PairCompleteResult.Failed("HTTP ${resp.code}")
                    }
                }
            } finally {
                unregister?.invoke()
                closeSocketsQuietly(trackedSockets)
                client.connectionPool.evictAll()
            }
        } catch (e: Throwable) {
            if (cancelToken?.isCancelled == true) return PairCompleteResult.Cancelled
            val changed = findCertChanged(e)
            if (changed != null) {
                LimeLog.warning("depth3d: host certificate changed during pair/complete for $hostId")
                PairCompleteResult.CertificateChanged(changed.hostId, changed.expectedFingerprint, changed.actualFingerprint)
            } else {
                LimeLog.warning("depth3d: host pair/complete failed ($address:$port): ${e.javaClass.simpleName}")
                PairCompleteResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override fun getMediaCredentials(
        address: String,
        port: Int,
        hostId: String,
        deviceId: String,
        expectedComputerUuid: String,
        expectedGsCertFingerprint: String,
        cancelToken: CallCancelToken?,
    ): MediaCredsResult {
        if (cancelToken?.isCancelled == true) return MediaCredsResult.Cancelled

        // B1: Refuse to open any /media connection unless:
        // 1) hostId has a persisted pinned certificate fingerprint,
        // 2) pairing was completed with this exact fingerprinted host + computerUuid + gsCertFingerprint,
        // 3) we have the pairing client certificate to authenticate with mutual TLS.
        val savedPin = pinStore.getFingerprint(hostId)
        val pairedTrust = pinStore.getPairedTrust(hostId)
        if (savedPin.isNullOrBlank() ||
            pairedTrust == null ||
            !pairedTrust.hostFingerprint.equals(savedPin, ignoreCase = true) ||
            !pairedTrust.computerUuid.equals(expectedComputerUuid, ignoreCase = true) ||
            !pairedTrust.gsCertFingerprint.equals(expectedGsCertFingerprint, ignoreCase = true)
        ) {
            LimeLog.info("depth3d: skipping /depth3d/v1/media for $hostId (missing verified paired cert pin)")
            return MediaCredsResult.Unauthorized
        }

        val keyManagers = buildClientKeyManagers()
        if (keyManagers == null) {
            LimeLog.warning("depth3d: skipping /depth3d/v1/media because pairing client certificate is unavailable")
            return MediaCredsResult.Unauthorized
        }

        val url = formatHostUrl(address, port, "/depth3d/v1/media")
        LimeLog.info("depth3d: host GET /depth3d/v1/media -> $address:$port")
        val observed = AtomicReference<String?>(null)
        val policy = CertPinPolicy.ExactOnly(
            expectedFingerprint = savedPin,
            reportedExpectedForError = savedPin,
        )
        return try {
            val trackedSockets = CopyOnWriteArrayList<Socket>()
            val client = buildClient(
                hostId = hostId,
                policy = policy,
                presentClientCert = true,
                readTimeoutMs = infoReadTimeoutMs,
                observedFingerprintOut = observed,
                precomputedKeyManagers = keyManagers,
                trackedSockets = trackedSockets,
                cancelToken = cancelToken,
            )
            val request = Request.Builder()
                .url(url)
                .header(HEADER_DEVICE, deviceId)
                .get()
                .build()
            val call = client.newCall(request)
            val unregister = cancelToken?.register { closeCallAndSockets(call, client, trackedSockets) }
            try {
                call.execute().use { resp ->
                    if (!savedPin.equals(observed.get(), ignoreCase = true)) {
                        return MediaCredsResult.Unauthorized
                    }
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
            } finally {
                unregister?.invoke()
                closeSocketsQuietly(trackedSockets)
                client.connectionPool.evictAll()
            }
        } catch (e: Throwable) {
            if (cancelToken?.isCancelled == true) return MediaCredsResult.Cancelled
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

    private sealed interface CertPinPolicy {
        /** Used only by `/info`: observes cert if unpinned; verifies against saved/approved pin if pinned. */
        data class ObserveOrPinned(
            val savedFingerprint: String?,
            val approvedNewFingerprint: String?,
        ) : CertPinPolicy

        /** Used by `/pair` and `/media`: strictly requires the server cert to match [expectedFingerprint]. */
        data class ExactOnly(
            val expectedFingerprint: String,
            val reportedExpectedForError: String,
        ) : CertPinPolicy
    }

    private fun buildClient(
        hostId: String,
        policy: CertPinPolicy,
        presentClientCert: Boolean,
        readTimeoutMs: Long,
        observedFingerprintOut: AtomicReference<String?>,
        precomputedKeyManagers: Array<KeyManager>? = null,
        trackedSockets: CopyOnWriteArrayList<Socket>? = null,
        cancelToken: CallCancelToken? = null,
    ): OkHttpClient {
        val trustManager = PinningTrustManager(hostId, policy, observedFingerprintOut)
        val keyManagers = when {
            !presentClientCert -> null
            precomputedKeyManagers != null -> precomputedKeyManagers
            else -> buildClientKeyManagers()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        val socketFactory: SSLSocketFactory = if (trackedSockets != null) {
            TrackingSslSocketFactory(sslContext.socketFactory, trackedSockets, cancelToken)
        } else {
            sslContext.socketFactory
        }
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            // Self-signed LAN host certificates are pinned by SHA-256 fingerprint via PinningTrustManager.
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    private fun closeCallAndSockets(
        call: Call,
        client: OkHttpClient,
        trackedSockets: CopyOnWriteArrayList<Socket>,
    ) {
        try {
            call.cancel()
        } catch (_: Throwable) {
        }
        closeSocketsQuietly(trackedSockets)
        try {
            client.connectionPool.evictAll()
        } catch (_: Throwable) {
        }
    }

    private fun closeSocketsQuietly(trackedSockets: CopyOnWriteArrayList<Socket>) {
        val snapshot = trackedSockets.toList()
        trackedSockets.clear()
        for (sock in snapshot) {
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** Wraps an [SSLSocketFactory] so every created TLS socket can be closed immediately on cancel (S5). */
    private class TrackingSslSocketFactory(
        private val delegate: SSLSocketFactory,
        private val tracked: CopyOnWriteArrayList<Socket>,
        private val cancelToken: CallCancelToken?,
    ) : SSLSocketFactory() {
        private fun register(sock: Socket): Socket {
            tracked.add(sock)
            if (cancelToken?.isCancelled == true) {
                tracked.remove(sock)
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
                throw IOException("Cancelled")
            }
            return sock
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(): Socket = register(delegate.createSocket())
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            register(delegate.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket = register(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            register(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = register(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            register(delegate.createSocket(address, port, localAddress, localPort))
    }

    private fun buildClientKeyManagers(): Array<KeyManager>? =
        buildClientKeyManagersWithFingerprint()?.first

    private fun buildClientKeyManagersWithFingerprint(): Pair<Array<KeyManager>, String>? {
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
            kmf.keyManagers to sha256Hex(identity.first)
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

    private class PinningTrustManager(
        private val hostId: String,
        private val policy: CertPinPolicy,
        private val observedOut: AtomicReference<String?>,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Empty server certificate chain")
            }
            val fingerprint = sha256Hex(chain[0])
            observedOut.set(fingerprint)
            when (policy) {
                is CertPinPolicy.ObserveOrPinned -> {
                    val saved = policy.savedFingerprint
                    when {
                        saved == null -> Unit
                        saved.equals(fingerprint, ignoreCase = true) -> Unit
                        policy.approvedNewFingerprint != null &&
                            policy.approvedNewFingerprint.equals(fingerprint, ignoreCase = true) -> Unit
                        else -> throw HostCertificateChangedException(hostId, saved, fingerprint)
                    }
                }
                is CertPinPolicy.ExactOnly -> {
                    if (!policy.expectedFingerprint.equals(fingerprint, ignoreCase = true)) {
                        throw HostCertificateChangedException(hostId, policy.reportedExpectedForError, fingerprint)
                    }
                }
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
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expect("true", true)
                'f' -> expect("false", false)
                'n' -> expect("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IOException("Unexpected char '${s[pos]}'")
            }
        }

        private fun expect(lit: String, value: Any?): Any? {
            if (!s.regionMatches(pos, lit, 0, lit.length)) throw IOException("Expected $lit")
            pos += lit.length
            return value
        }

        private fun parseObject(): MiniJson {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return MiniJson(map)
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw IOException("Expected key")
                val key = parseString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw IOException("Expected :")
                pos++
                map[key] = parseValue()
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

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
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

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw IOException("Unterminated escape")
                        when (val esc = s[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IOException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IOException("Unknown escape \\$esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IOException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val raw = s.substring(start, pos)
            return if (isFloat) raw.toDouble() else raw.toLong()
        }
    }
}
