package com.limelight.local.stream

import android.content.Context
import com.limelight.LimeLog
import com.limelight.R
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Localized strings rendered inside the LAN phone-input HTML page. */
data class PhoneInputCopy(
    val pageTitle: String,
    val heading: String,
    val hint: String,
    val codeLabel: String,
    val codePlaceholder: String,
    val valueLabel: String,
    val valuePlaceholder: String,
    val submitLabel: String,
    val errWrongCode: String,
    val errLockedOut: String,
    val errEmptyOrLong: String,
    val doneTitle: String,
    val doneSubtitle: String,
) {
    companion object {
        fun from(context: Context, mode: PhoneInputServer.Mode): PhoneInputCopy =
            from(AndroidStringLookup(context), mode)

        fun from(s: StringLookup, mode: PhoneInputServer.Mode): PhoneInputCopy = when (mode) {
            PhoneInputServer.Mode.ADD_COMPUTER -> PhoneInputCopy(
                pageTitle = s.get(R.string.phone_html_add_pc_page_title),
                heading = s.get(R.string.phone_html_add_pc_heading),
                hint = s.get(R.string.phone_html_add_pc_hint),
                codeLabel = s.get(R.string.phone_html_code_label),
                codePlaceholder = s.get(R.string.phone_html_code_placeholder),
                valueLabel = s.get(R.string.phone_html_value_label),
                valuePlaceholder = s.get(R.string.phone_html_add_pc_placeholder),
                submitLabel = s.get(R.string.phone_html_add_pc_submit),
                errWrongCode = s.get(R.string.phone_html_err_wrong_code),
                errLockedOut = s.get(R.string.phone_html_err_locked),
                errEmptyOrLong = s.get(R.string.phone_html_err_empty_or_long),
                doneTitle = s.get(R.string.phone_html_done_title),
                doneSubtitle = s.get(R.string.phone_html_done_subtitle),
            )
            PhoneInputServer.Mode.DEVICE_NAME -> PhoneInputCopy(
                pageTitle = s.get(R.string.phone_html_rename_page_title),
                heading = s.get(R.string.phone_html_rename_heading),
                hint = s.get(R.string.phone_html_rename_hint),
                codeLabel = s.get(R.string.phone_html_code_label),
                codePlaceholder = s.get(R.string.phone_html_code_placeholder),
                valueLabel = s.get(R.string.phone_html_value_label),
                valuePlaceholder = s.get(R.string.phone_html_rename_placeholder),
                submitLabel = s.get(R.string.phone_html_rename_submit),
                errWrongCode = s.get(R.string.phone_html_err_wrong_code),
                errLockedOut = s.get(R.string.phone_html_err_locked),
                errEmptyOrLong = s.get(R.string.phone_html_err_empty_or_long),
                doneTitle = s.get(R.string.phone_html_done_title),
                doneSubtitle = s.get(R.string.phone_html_done_subtitle),
            )
            PhoneInputServer.Mode.PLUGIN_REPO -> PhoneInputCopy(
                pageTitle = s.get(R.string.phone_html_repo_page_title),
                heading = s.get(R.string.phone_html_repo_heading),
                hint = s.get(R.string.phone_html_repo_hint),
                codeLabel = s.get(R.string.phone_html_code_label),
                codePlaceholder = s.get(R.string.phone_html_code_placeholder),
                valueLabel = s.get(R.string.phone_input_repo_label),
                valuePlaceholder = s.get(R.string.phone_html_repo_placeholder),
                submitLabel = s.get(R.string.phone_html_repo_submit),
                errWrongCode = s.get(R.string.phone_html_err_wrong_code),
                errLockedOut = s.get(R.string.phone_html_err_locked),
                errEmptyOrLong = s.get(R.string.phone_html_err_empty_or_long),
                doneTitle = s.get(R.string.phone_html_done_title),
                doneSubtitle = s.get(R.string.phone_html_done_subtitle),
            )
            PhoneInputServer.Mode.PLUGIN_DEV_SERVER -> PhoneInputCopy(
                pageTitle = s.get(R.string.phone_html_dev_page_title),
                heading = s.get(R.string.phone_html_dev_heading),
                hint = s.get(R.string.phone_html_dev_hint),
                codeLabel = s.get(R.string.phone_html_code_label),
                codePlaceholder = s.get(R.string.phone_html_code_placeholder),
                valueLabel = s.get(R.string.phone_input_dev_label),
                valuePlaceholder = s.get(R.string.phone_html_dev_placeholder),
                submitLabel = s.get(R.string.phone_html_dev_submit),
                errWrongCode = s.get(R.string.phone_html_err_wrong_code),
                errLockedOut = s.get(R.string.phone_html_err_locked),
                errEmptyOrLong = s.get(R.string.phone_html_err_empty_or_long),
                doneTitle = s.get(R.string.phone_html_done_title),
                doneSubtitle = s.get(R.string.phone_html_done_subtitle),
            )
            PhoneInputServer.Mode.PLUGIN_DIRECT_URL -> PhoneInputCopy(
                pageTitle = s.get(R.string.phone_html_direct_page_title),
                heading = s.get(R.string.phone_html_direct_heading),
                hint = s.get(R.string.phone_html_direct_hint),
                codeLabel = s.get(R.string.phone_html_code_label),
                codePlaceholder = s.get(R.string.phone_html_code_placeholder),
                valueLabel = s.get(R.string.phone_input_direct_label),
                valuePlaceholder = s.get(R.string.phone_html_direct_placeholder),
                submitLabel = s.get(R.string.phone_html_direct_submit),
                errWrongCode = s.get(R.string.phone_html_err_wrong_code),
                errLockedOut = s.get(R.string.phone_html_err_locked),
                errEmptyOrLong = s.get(R.string.phone_html_err_empty_or_long),
                doneTitle = s.get(R.string.phone_html_done_title),
                doneSubtitle = s.get(R.string.phone_html_done_subtitle),
            )
        }
    }
}

/** Pure-JVM localized error copy for PhoneInputSheet timeout/failure handling (M6/S4). */
object PhoneInputErrorHelper {
    fun actionLabel(s: StringLookup, mode: PhoneInputServer.Mode): String = when (mode) {
        PhoneInputServer.Mode.ADD_COMPUTER -> s.get(R.string.stream_action_add_pc)
        PhoneInputServer.Mode.DEVICE_NAME -> s.get(R.string.phone_action_rename_device)
        PhoneInputServer.Mode.PLUGIN_REPO -> s.get(R.string.phone_action_add_repo)
        PhoneInputServer.Mode.PLUGIN_DEV_SERVER -> s.get(R.string.phone_action_dev_server)
        PhoneInputServer.Mode.PLUGIN_DIRECT_URL -> s.get(R.string.phone_action_direct_url)
    }

    fun timeoutError(s: StringLookup, mode: PhoneInputServer.Mode): UserFacingError = UserFacingError(
        title = s.get(R.string.err_net_timeout_title, actionLabel(s, mode)),
        detail = s.get(R.string.err_net_timeout_detail),
    )

    fun failedError(s: StringLookup, mode: PhoneInputServer.Mode): UserFacingError = UserFacingError(
        title = s.get(R.string.err_action_failed_title, actionLabel(s, mode)),
        detail = s.get(R.string.err_action_failed_detail),
    )

    fun formatRejectedReason(s: StringLookup, err: UserFacingError): String =
        s.get(R.string.err_join_colon, err.title, err.detail)
}

/** Result returned synchronously by the headset before the phone sees a success screen. */
sealed interface PhoneSubmitResult {
    data object Accepted : PhoneSubmitResult
    data class Rejected(val reason: String) : PhoneSubmitResult
}

/**
 * Hardened LAN HTTP server so the user can enter a computer IP address or headset name on their
 * phone instead of typing in VR.
 *
 * Security & concurrency properties:
 * - QR URL carries a 128-bit random token (`?t=<22-char base64url>`) and submits to `/submit-qr`,
 *   which ONLY accepts the 128-bit token and rejects the 4-digit fallback code (M8).
 * - Manual short URL submits to `/submit`, which ONLY accepts the 4-digit verification code and
 *   shuts the server down permanently after [MAX_FAILED_ATTEMPTS] wrong attempts.
 * - GET requests NEVER reveal whether a token or code is valid (zero GET oracle).
 * - Every read dynamically clamps `soTimeout` to the remaining request deadline, and when all
 *   slots are saturated the oldest stalled unauthenticated reader is evicted so slow/trickle
 *   clients cannot starve a real phone (M2).
 * - Phone only sees success after [onSubmitted] confirms the headset accepted the value.
 */
class PhoneInputServer(
    private val copy: PhoneInputCopy,
    private val displayHost: String,
    private val code: String = generateCode(),
    private val qrToken: String = generateQrToken(),
    private val bindAddress: InetAddress? = null,
    private val port: Int = 0,
    private val socketTimeoutMs: Int = DEFAULT_SOCKET_TIMEOUT_MS,
    private val requestDeadlineMs: Long = DEFAULT_REQUEST_DEADLINE_MS,
    private val onLockedOut: () -> Unit = {},
    private val onSubmitted: (String) -> PhoneSubmitResult,
) : Closeable {

    enum class Mode {
        ADD_COMPUTER,
        DEVICE_NAME,
        PLUGIN_REPO,
        PLUGIN_DEV_SERVER,
        PLUGIN_DIRECT_URL,
    }

    private val closed = AtomicBoolean(false)
    private val lockedOut = AtomicBoolean(false)
    private val shutdownFinalized = AtomicBoolean(false)
    private val failedAttempts = AtomicInteger(0)
    private val concurrencyGate = Semaphore(MAX_CONCURRENT_CONNECTIONS)
    private val activeReaders = ConcurrentLinkedDeque<Socket>()
    private val workers = Executors.newFixedThreadPool(WORKER_THREADS) { r ->
        Thread(r, "depth3d-phone-worker").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: 0

    val verificationCode: String
        get() = code

    val token: String
        get() = qrToken

    val isClosed: Boolean
        get() = closed.get()

    val isLockedOut: Boolean
        get() = lockedOut.get()

    /** Short URL displayed to the user if they choose to type it manually + enter the 4-digit code. */
    val shortUrl: String
        get() = "http://${HttpHostProtocolClient.formatAuthorityHost(displayHost)}:$boundPort"

    /** Full URL encoded in the QR code carrying the 128-bit random token. */
    val qrUrl: String
        get() = "$shortUrl/?t=$qrToken"

    fun start(): PhoneInputServer {
        val ss = ServerSocket(port, 32, bindAddress)
        serverSocket = ss
        val t = Thread({ acceptLoop(ss) }, "depth3d-phone-accept").apply { isDaemon = true }
        acceptThread = t
        t.start()
        LimeLog.info("depth3d: phone input server listening on $displayHost:${ss.localPort}")
        return this
    }

    override fun close() {
        stopAccepting()
        finalizeShutdown()
    }

    private fun stopAccepting() {
        closed.set(true)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
    }

    private fun finalizeShutdown() {
        if (!shutdownFinalized.compareAndSet(false, true)) return
        while (true) {
            val s = activeReaders.pollFirst() ?: break
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        workers.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            var acquired = concurrencyGate.tryAcquire()
            if (!acquired) {
                // M2: Evict the oldest unauthenticated slow reader so stalled connections cannot
                // occupy all slots and starve a legitimate phone submission.
                val victim = activeReaders.pollFirst()
                if (victim != null) {
                    try {
                        victim.close()
                    } catch (_: Exception) {
                    }
                    acquired = try {
                        concurrencyGate.tryAcquire(150, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        false
                    }
                }
            }
            if (!acquired) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
                continue
            }
            try {
                workers.execute {
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        LimeLog.warning("depth3d: phone input request error: ${e.javaClass.simpleName}")
                    } finally {
                        activeReaders.remove(client)
                        try {
                            client.close()
                        } catch (_: Exception) {
                        }
                        concurrencyGate.release()
                    }
                }
            } catch (_: Exception) {
                concurrencyGate.release()
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val deadline = System.currentTimeMillis() + requestDeadlineMs
        activeReaders.addLast(socket)
        val method: String
        val rawPath: String
        var contentLength = 0
        val input = BufferedInputStream(socket.getInputStream())
        val bodyBytes: ByteArray
        try {
            val requestLine = readAsciiLine(socket, input, deadline) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            method = parts[0].uppercase()
            rawPath = parts[1]

            var headerLines = 0
            while (headerLines < MAX_HEADER_LINES) {
                val line = readAsciiLine(socket, input, deadline) ?: return
                if (line.isEmpty()) break
                headerLines++
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
                }
            }

            if (method == "POST") {
                bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    if (!applyRemainingReadTimeout(socket, deadline)) return
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < contentLength) return
            } else {
                bodyBytes = ByteArray(0)
            }
        } finally {
            // Request reading is done; do not evict a valid request while waiting for headset confirmation.
            activeReaders.remove(socket)
        }

        val pathOnly = rawPath.substringBefore('?')
        val out = socket.getOutputStream()
        when (method) {
            "GET" -> {
                // Never validate token or code on GET: identical 200 form prevents GET probing oracles.
                val query = rawPath.substringAfter('?', "")
                val params = parseForm(query)
                val suppliedToken = params["t"]?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
                writeHtml(out, 200, "OK", renderFormHtml(hiddenToken = suppliedToken, error = null))
            }
            "POST" -> {
                val params = parseForm(String(bodyBytes, StandardCharsets.UTF_8))
                val submittedToken = params["token"].orEmpty().trim()
                val submittedCode = params["code"].orEmpty().trim()
                val submittedValue = params["value"].orEmpty().trim()

                // M8: Strict separation between QR token path (/submit-qr) and manual 4-digit code
                // path (/submit). The QR path NEVER accepts the 4-digit code as a substitute.
                val isQrPath = pathOnly == PATH_SUBMIT_QR
                val isCodePath = pathOnly == PATH_SUBMIT_CODE
                val authorized = when {
                    isQrPath -> submittedToken.isNotEmpty() && constantTimeEquals(submittedToken, qrToken)
                    isCodePath -> submittedCode.isNotEmpty() && constantTimeEquals(submittedCode, code)
                    else -> false
                }

                if (lockedOut.get()) {
                    writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, error = copy.errLockedOut))
                    return
                }

                if (!authorized) {
                    val tries = failedAttempts.incrementAndGet()
                    if (tries >= MAX_FAILED_ATTEMPTS) {
                        LimeLog.warning("depth3d: phone input locked out after $tries failed attempts")
                        val firstLockout = lockedOut.compareAndSet(false, true)
                        stopAccepting()
                        if (firstLockout) {
                            runCatching { onLockedOut() }
                        }
                        try {
                            writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, error = copy.errLockedOut))
                        } finally {
                            finalizeShutdown()
                        }
                    } else {
                        writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, error = copy.errWrongCode))
                    }
                    return
                }

                val keepToken = if (isQrPath) submittedToken else null

                if (submittedValue.isEmpty() || submittedValue.length > MAX_VALUE_LEN) {
                    writeHtml(out, 400, "Bad Request", renderFormHtml(hiddenToken = keepToken, error = copy.errEmptyOrLong))
                    return
                }

                // Wait for the headset to confirm acceptance before telling the phone it succeeded.
                when (val result = onSubmitted(submittedValue)) {
                    PhoneSubmitResult.Accepted -> {
                        writeHtml(out, 200, "OK", renderDoneHtml(submittedValue))
                    }
                    is PhoneSubmitResult.Rejected -> {
                        writeHtml(out, 400, "Bad Request", renderFormHtml(hiddenToken = keepToken, error = result.reason))
                    }
                }
            }
            else -> writeHtml(out, 405, "Method Not Allowed", renderFormHtml(hiddenToken = null, error = null))
        }
    }

    /** Clamps socket SO_TIMEOUT to the remaining request deadline before every blocking read (M2). */
    private fun applyRemainingReadTimeout(socket: Socket, deadlineMs: Long): Boolean {
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0L) return false
        val clamped = minOf(socketTimeoutMs.toLong(), remaining).toInt().coerceAtLeast(1)
        socket.soTimeout = clamped
        return true
    }

    private fun readAsciiLine(socket: Socket, input: InputStream, deadlineMs: Long): String? {
        val sb = StringBuilder(64)
        while (true) {
            if (!applyRemainingReadTimeout(socket, deadlineMs)) return null
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_LINE_LEN) return null
        }
        return sb.toString()
    }

    private fun writeHtml(out: OutputStream, status: Int, reason: String, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        out.write(body)
        out.flush()
    }

    private fun renderFormHtml(hiddenToken: String?, error: String?): String {
        val errorBlock = if (error != null) "<p class=\"err\">${escapeHtml(error)}</p>" else ""
        val actionPath = if (hiddenToken != null) PATH_SUBMIT_QR else PATH_SUBMIT_CODE
        val authField = if (hiddenToken != null) {
            "<input type=\"hidden\" name=\"token\" value=\"${escapeHtml(hiddenToken)}\"/>"
        } else {
            "<label>${escapeHtml(copy.codeLabel)}</label>" +
                "<input name=\"code\" inputmode=\"numeric\" maxlength=\"4\" placeholder=\"${escapeHtml(copy.codePlaceholder)}\" required/>"
        }
        return """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${escapeHtml(copy.pageTitle)}</title>
<style>
body{margin:0;background:#0E0F12;color:#ECECEE;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;padding:28px 20px}
.card{max-width:420px;margin:0 auto;background:#17191D;border-radius:20px;padding:28px 24px}
h1{font-size:22px;margin:0 0 8px;font-weight:600}
p{color:#A6A9B0;font-size:15px;line-height:1.5;margin:0 0 20px}
label{display:block;color:#A6A9B0;font-size:13px;margin:14px 0 6px}
input{width:100%;box-sizing:border-box;background:#202328;color:#ECECEE;border:1px solid rgba(255,255,255,.12);border-radius:12px;padding:14px 16px;font-size:16px}
button{width:100%;margin-top:22px;background:#ECECEE;color:#0E0F12;border:none;border-radius:14px;padding:15px;font-size:16px;font-weight:600}
.err{color:#EE8577;margin-top:12px;margin-bottom:0}
</style></head><body><div class="card">
<h1>${escapeHtml(copy.heading)}</h1>
<p>${escapeHtml(copy.hint)}</p>
<form method="post" action="$actionPath">
$authField
<label>${escapeHtml(copy.valueLabel)}</label>
<input name="value" placeholder="${escapeHtml(copy.valuePlaceholder)}" autocomplete="off" autocorrect="off" autocapitalize="off" required autofocus/>
$errorBlock
<button type="submit">${escapeHtml(copy.submitLabel)}</button>
</form></div></body></html>"""
    }

    private fun renderDoneHtml(value: String): String = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${escapeHtml(copy.pageTitle)}</title>
<style>
body{margin:0;background:#0E0F12;color:#ECECEE;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;padding:28px 20px}
.card{max-width:420px;margin:0 auto;background:#17191D;border-radius:20px;padding:28px 24px;text-align:center}
h1{font-size:22px;margin:0 0 10px}p{color:#A6A9B0;font-size:15px;margin:0}
.val{margin-top:14px;color:#E3A857;font-size:17px;font-weight:600}
</style></head><body><div class="card">
<h1>${escapeHtml(copy.doneTitle)}</h1>
<p>${escapeHtml(copy.doneSubtitle)}</p>
<div class="val">${escapeHtml(value)}</div>
</div></body></html>"""

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        const val QR_TOKEN_BYTES = 16 // 128-bit cryptographic random token
        const val PATH_SUBMIT_QR = "/submit-qr"
        const val PATH_SUBMIT_CODE = "/submit"
        internal const val MAX_CONCURRENT_CONNECTIONS = 8
        private const val WORKER_THREADS = 8
        private const val DEFAULT_SOCKET_TIMEOUT_MS = 2_000
        private const val DEFAULT_REQUEST_DEADLINE_MS = 3_000L
        private const val MAX_HEADER_LINES = 64
        private const val MAX_LINE_LEN = 2048
        private const val MAX_BODY_BYTES = 16_384
        private const val MAX_VALUE_LEN = 512

        private val random = SecureRandom()
        private const val BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun generateCode(): String = (1000 + random.nextInt(9000)).toString()

        /** Generates a 128-bit (16-byte) URL-safe base64 token (22 chars, pure-JVM safe). */
        fun generateQrToken(): String {
            val bytes = ByteArray(QR_TOKEN_BYTES)
            random.nextBytes(bytes)
            val sb = StringBuilder(22)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                val remaining = bytes.size - i
                sb.append(BASE64_URL_ALPHABET[b0 ushr 2])
                sb.append(BASE64_URL_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (remaining > 1) {
                    sb.append(BASE64_URL_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                }
                if (remaining > 2) {
                    sb.append(BASE64_URL_ALPHABET[b2 and 0x3F])
                }
                i += 3
            }
            return sb.toString()
        }

        internal fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(
                a.toByteArray(StandardCharsets.UTF_8),
                b.toByteArray(StandardCharsets.UTF_8),
            )

        internal fun parseForm(encoded: String): Map<String, String> {
            if (encoded.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in encoded.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val k = try {
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    continue
                }
                val v = try {
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    continue
                }
                map[k] = v
            }
            return map
        }

        internal fun escapeHtml(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }
    }
}

/** Field types rendered by [PhoneFormServer] for plugin configuration and login forms. */
enum class PhoneFormFieldKind { TEXT, URL, PASSWORD, NUMBER, SELECT, TOGGLE }

data class PhoneFormOption(
    val value: String,
    val label: String,
)

data class PhoneFormField(
    val key: String,
    val kind: PhoneFormFieldKind,
    val label: String,
    val required: Boolean = false,
    val placeholder: String? = null,
    val initialValue: String = "",
    val options: List<PhoneFormOption> = emptyList(),
)

/** Live non-secret progress reported while the user types on their phone (`已填 n/m`). */
data class PhoneFormProgress(
    val filledCount: Int,
    val totalCount: Int,
)

/** Localized strings rendered inside the LAN multi-field phone form page. */
data class PhoneFormCopy(
    val pageTitle: String,
    val heading: String,
    val hint: String,
    val codeLabel: String,
    val codePlaceholder: String,
    val submitLabel: String,
    val errWrongCode: String,
    val errLockedOut: String,
    val errRequired: String,
    val httpWarning: String,
    val passwordPlaceholder: String,
    val toggleOn: String,
    val toggleOff: String,
    val doneTitle: String,
    val doneSubtitle: String,
) {
    companion object {
        fun from(context: Context, pluginName: String): PhoneFormCopy =
            from(AndroidStringLookup(context), pluginName)

        fun from(s: StringLookup, pluginName: String): PhoneFormCopy = PhoneFormCopy(
            pageTitle = s.get(R.string.phone_form_page_title, pluginName),
            heading = s.get(R.string.phone_form_heading, pluginName),
            hint = s.get(R.string.phone_form_hint),
            codeLabel = s.get(R.string.phone_html_code_label),
            codePlaceholder = s.get(R.string.phone_html_code_placeholder),
            submitLabel = s.get(R.string.phone_form_submit),
            errWrongCode = s.get(R.string.phone_html_err_wrong_code),
            errLockedOut = s.get(R.string.phone_html_err_locked),
            errRequired = s.get(R.string.phone_form_err_required),
            httpWarning = s.get(R.string.phone_form_err_http_warn),
            passwordPlaceholder = s.get(R.string.phone_form_password_placeholder),
            toggleOn = s.get(R.string.phone_form_toggle_on),
            toggleOff = s.get(R.string.phone_form_toggle_off),
            doneTitle = s.get(R.string.phone_form_done_title),
            doneSubtitle = s.get(R.string.phone_form_done_subtitle),
        )
    }
}

/**
 * Multi-field form mode extension of [PhoneInputServer] sharing the exact same security invariants:
 * - 128-bit random QR token (`/submit-qr`) strictly separated from 4-digit fallback code (`/submit`)
 * - Zero GET oracle (GET always renders 200 without validating token/code)
 * - Permanent shutdown after 5 wrong authentication attempts
 * - Slowloris deadline clamping + oldest-reader eviction
 * - `PASSWORD` fields are NEVER prefilled, NEVER echoed on error/done pages, NEVER logged, and
 *   NEVER included in `/progress` payloads (which carry only numeric `filled` / `total` counts)
 */
class PhoneFormServer(
    private val copy: PhoneFormCopy,
    private val fields: List<PhoneFormField>,
    private val displayHost: String,
    private val code: String = PhoneInputServer.generateCode(),
    private val qrToken: String = PhoneInputServer.generateQrToken(),
    private val bindAddress: InetAddress? = null,
    private val port: Int = 0,
    private val socketTimeoutMs: Int = 2_000,
    private val requestDeadlineMs: Long = 3_000L,
    private val onProgress: (PhoneFormProgress) -> Unit = {},
    private val onLockedOut: () -> Unit = {},
    private val onAcceptedFlushed: () -> Unit = {},
    private val onSubmitted: (Map<String, String>) -> PhoneSubmitResult,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val lockedOut = AtomicBoolean(false)
    private val shutdownFinalized = AtomicBoolean(false)
    private val failedAttempts = AtomicInteger(0)
    private val submissionInProgress = AtomicBoolean(false)
    private val submissionAccepted = AtomicBoolean(false)
    private val acceptedSummary = java.util.concurrent.atomic.AtomicReference("")
    private val concurrencyGate = Semaphore(PhoneInputServer.MAX_CONCURRENT_CONNECTIONS)
    private val activeReaders = ConcurrentLinkedDeque<Socket>()
    private val workers = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "depth3d-phone-form-worker").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val boundPort: Int get() = serverSocket?.localPort ?: 0
    val verificationCode: String get() = code
    val token: String get() = qrToken
    val isClosed: Boolean get() = closed.get()
    val isLockedOut: Boolean get() = lockedOut.get()

    val shortUrl: String
        get() = "http://${HttpHostProtocolClient.formatAuthorityHost(displayHost)}:$boundPort"

    val qrUrl: String
        get() = "$shortUrl/?t=$qrToken"

    fun start(): PhoneFormServer {
        val ss = ServerSocket(port, 32, bindAddress)
        serverSocket = ss
        val t = Thread({ acceptLoop(ss) }, "depth3d-phone-form-accept").apply { isDaemon = true }
        acceptThread = t
        t.start()
        LimeLog.info("depth3d: phone form server listening on $displayHost:${ss.localPort} (${fields.size} fields)")
        return this
    }

    override fun close() {
        stopAccepting()
        finalizeShutdown()
    }

    private fun stopAccepting() {
        closed.set(true)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
    }

    private fun finalizeShutdown() {
        if (!shutdownFinalized.compareAndSet(false, true)) return
        while (true) {
            val s = activeReaders.pollFirst() ?: break
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        workers.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            var acquired = concurrencyGate.tryAcquire()
            if (!acquired) {
                val victim = activeReaders.pollFirst()
                if (victim != null) {
                    try {
                        victim.close()
                    } catch (_: Exception) {
                    }
                    acquired = try {
                        concurrencyGate.tryAcquire(150, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        false
                    }
                }
            }
            if (!acquired) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
                continue
            }
            try {
                workers.execute {
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        LimeLog.warning("depth3d: phone form request error: ${e.javaClass.simpleName}")
                    } finally {
                        activeReaders.remove(client)
                        try {
                            client.close()
                        } catch (_: Exception) {
                        }
                        concurrencyGate.release()
                    }
                }
            } catch (_: Exception) {
                concurrencyGate.release()
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val deadline = System.currentTimeMillis() + requestDeadlineMs
        activeReaders.addLast(socket)
        val method: String
        val rawPath: String
        var contentLength = 0
        val input = BufferedInputStream(socket.getInputStream())
        val bodyBytes: ByteArray
        try {
            val requestLine = readAsciiLine(socket, input, deadline) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            method = parts[0].uppercase()
            rawPath = parts[1]

            var headerLines = 0
            while (headerLines < 64) {
                val line = readAsciiLine(socket, input, deadline) ?: return
                if (line.isEmpty()) break
                headerLines++
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                }
            }
            if (method == "POST") {
                bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    if (!applyRemainingReadTimeout(socket, deadline)) return
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < contentLength) return
            } else {
                bodyBytes = ByteArray(0)
            }
        } finally {
            activeReaders.remove(socket)
        }

        val pathOnly = rawPath.substringBefore('?')
        val out = socket.getOutputStream()
        when (method) {
            "GET" -> {
                // Zero GET oracle: always serve 200 without verifying token/code
                val query = rawPath.substringAfter('?', "")
                val params = PhoneInputServer.parseForm(query)
                val suppliedToken = params["t"]?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
                writeHtml(out, 200, "OK", renderFormHtml(hiddenToken = suppliedToken, preserved = emptyMap(), error = null))
            }
            "POST" -> {
                val params = PhoneInputServer.parseForm(String(bodyBytes, StandardCharsets.UTF_8))
                if (pathOnly == PATH_PROGRESS) {
                    val tok = params["token"].orEmpty().trim()
                    val submittedCode = params["code"].orEmpty().trim()
                    val authed = (tok.isNotEmpty() && PhoneInputServer.constantTimeEquals(tok, qrToken)) ||
                        (submittedCode.isNotEmpty() && PhoneInputServer.constantTimeEquals(submittedCode, code))
                    if (authed) {
                        val total = fields.size.coerceAtLeast(1)
                        val filled = params["filled"]?.toIntOrNull()?.coerceIn(0, total) ?: 0
                        onProgress(PhoneFormProgress(filledCount = filled, totalCount = total))
                    }
                    // Always respond 204 to prevent /progress from acting as a brute-force oracle
                    writeEmpty(out, 204, "No Content")
                    return
                }

                val submittedToken = params["token"].orEmpty().trim()
                val submittedCode = params["code"].orEmpty().trim()
                val isQrPath = pathOnly == PhoneInputServer.PATH_SUBMIT_QR
                val isCodePath = pathOnly == PhoneInputServer.PATH_SUBMIT_CODE
                val authorized = when {
                    isQrPath -> submittedToken.isNotEmpty() && PhoneInputServer.constantTimeEquals(submittedToken, qrToken)
                    isCodePath -> submittedCode.isNotEmpty() && PhoneInputServer.constantTimeEquals(submittedCode, code)
                    else -> false
                }

                // Extract ONLY non-password fields upfront so a wrong verification code preserves
                // what the user typed without ever echoing passwords (#14).
                val values = LinkedHashMap<String, String>()
                val safeNonSecret = LinkedHashMap<String, String>()
                var missingRequired = false
                for (field in fields) {
                    val raw = when (field.kind) {
                        PhoneFormFieldKind.TOGGLE -> if (params["f_${field.key}"] == "true") "true" else "false"
                        PhoneFormFieldKind.PASSWORD -> params["f_${field.key}"].orEmpty().take(512)
                        else -> params["f_${field.key}"].orEmpty().trim().take(512)
                    }
                    values[field.key] = raw
                    if (field.kind != PhoneFormFieldKind.PASSWORD) {
                        safeNonSecret[field.key] = raw
                    }
                    if (field.required && field.kind != PhoneFormFieldKind.TOGGLE && raw.isEmpty()) {
                        missingRequired = true
                    }
                }

                if (lockedOut.get()) {
                    writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, preserved = emptyMap(), error = copy.errLockedOut))
                    return
                }

                if (!authorized) {
                    val tries = failedAttempts.incrementAndGet()
                    if (tries >= PhoneInputServer.MAX_FAILED_ATTEMPTS) {
                        LimeLog.warning("depth3d: phone form locked out after $tries failed attempts")
                        val firstLockout = lockedOut.compareAndSet(false, true)
                        // Mark locked/closed and invoke onLockedOut BEFORE writing the 5th 403 response
                        // so the response is still delivered cleanly AND callers observe locked/closed immediately (#3).
                        stopAccepting()
                        if (firstLockout) {
                            runCatching { onLockedOut() }
                        }
                        try {
                            writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, preserved = emptyMap(), error = copy.errLockedOut))
                        } finally {
                            finalizeShutdown()
                        }
                    } else {
                        writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, preserved = safeNonSecret, error = copy.errWrongCode))
                    }
                    return
                }

                val keepToken = if (isQrPath) submittedToken else null

                if (missingRequired) {
                    writeHtml(out, 400, "Bad Request", renderFormHtml(hiddenToken = keepToken, preserved = safeNonSecret, error = copy.errRequired))
                    return
                }

                // Single-use token semantics (#7): once a form submission has been accepted, any
                // duplicate / retried POST with the same token/code returns the Done page without
                // invoking onSubmitted a second time (preventing duplicate instances).
                if (submissionAccepted.get()) {
                    writeHtml(out, 200, "OK", renderDoneHtml(acceptedSummary.get()))
                    return
                }
                if (!submissionInProgress.compareAndSet(false, true)) {
                    if (submissionAccepted.get()) {
                        writeHtml(out, 200, "OK", renderDoneHtml(acceptedSummary.get()))
                    } else {
                        writeHtml(out, 409, "Conflict", renderFormHtml(hiddenToken = keepToken, preserved = safeNonSecret, error = copy.hint))
                    }
                    return
                }

                var acceptedNowSummary: String? = null
                val result = try {
                    if (submissionAccepted.get()) {
                        writeHtml(out, 200, "OK", renderDoneHtml(acceptedSummary.get()))
                        return
                    }
                    val submitRes = onSubmitted(values)
                    if (submitRes == PhoneSubmitResult.Accepted) {
                        // Mark accepted BEFORE releasing submissionInProgress so no concurrent POST
                        // can observe (inProgress=false, accepted=false) and double-submit (#7).
                        val summary = fields.firstOrNull {
                            it.kind != PhoneFormFieldKind.PASSWORD && !safeNonSecret[it.key].isNullOrBlank()
                        }?.let { safeNonSecret[it.key] }.orEmpty()
                        acceptedSummary.set(summary)
                        submissionAccepted.set(true)
                        acceptedNowSummary = summary
                    }
                    submitRes
                } finally {
                    submissionInProgress.set(false)
                }

                when (result) {
                    PhoneSubmitResult.Accepted -> {
                        val summary = acceptedNowSummary.orEmpty()
                        // Always execute onAcceptedFlushed in finally so headset navigation NEVER
                        // stalls if the phone closes the socket before writeHtml completes (#7).
                        try {
                            writeHtml(out, 200, "OK", renderDoneHtml(summary))
                        } finally {
                            runCatching { onAcceptedFlushed() }
                        }
                    }
                    is PhoneSubmitResult.Rejected -> {
                        // Re-render with ONLY non-password fields preserved; passwords are always blank
                        writeHtml(out, 400, "Bad Request", renderFormHtml(hiddenToken = keepToken, preserved = safeNonSecret, error = result.reason))
                    }
                }
            }
            else -> writeHtml(out, 405, "Method Not Allowed", renderFormHtml(hiddenToken = null, preserved = emptyMap(), error = null))
        }
    }

    private fun applyRemainingReadTimeout(socket: Socket, deadlineMs: Long): Boolean {
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0L) return false
        socket.soTimeout = minOf(socketTimeoutMs.toLong(), remaining).toInt().coerceAtLeast(1)
        return true
    }

    private fun readAsciiLine(socket: Socket, input: InputStream, deadlineMs: Long): String? {
        val sb = StringBuilder(64)
        while (true) {
            if (!applyRemainingReadTimeout(socket, deadlineMs)) return null
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 2048) return null
        }
        return sb.toString()
    }

    private fun writeEmpty(out: OutputStream, status: Int, reason: String) {
        val headers = "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        out.flush()
    }

    private fun writeHtml(out: OutputStream, status: Int, reason: String, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        out.write(body)
        out.flush()
    }

    private fun renderFormHtml(
        hiddenToken: String?,
        preserved: Map<String, String>,
        error: String?,
    ): String {
        val esc = PhoneInputServer::escapeHtml
        val errorBlock = if (error != null) "<p class=\"err\">${esc(error)}</p>" else ""
        val actionPath = if (hiddenToken != null) PhoneInputServer.PATH_SUBMIT_QR else PhoneInputServer.PATH_SUBMIT_CODE
        val authField = if (hiddenToken != null) {
            "<input type=\"hidden\" id=\"authToken\" name=\"token\" value=\"${esc(hiddenToken)}\"/>"
        } else {
            "<label>${esc(copy.codeLabel)}</label>" +
                "<input id=\"authCode\" name=\"code\" inputmode=\"numeric\" maxlength=\"4\" placeholder=\"${esc(copy.codePlaceholder)}\" required/>"
        }

        val fieldHtml = buildString {
            for (field in fields) {
                val inputName = "f_${esc(field.key)}"
                // NEVER prefill or echo password fields
                val value = if (field.kind == PhoneFormFieldKind.PASSWORD) {
                    ""
                } else {
                    preserved[field.key] ?: field.initialValue
                }
                val reqAttr = if (field.required) " required" else ""
                val ph = field.placeholder?.let { esc(it) } ?: ""
                append("<label>${esc(field.label)}</label>")
                when (field.kind) {
                    PhoneFormFieldKind.URL -> {
                        append("<input class=\"df\" data-url=\"1\" type=\"url\" name=\"$inputName\" value=\"${esc(value)}\" placeholder=\"$ph\" autocomplete=\"off\" autocorrect=\"off\" autocapitalize=\"off\"$reqAttr/>")
                        append("<p class=\"warn\" data-warn-for=\"$inputName\" style=\"display:none\">${esc(copy.httpWarning)}</p>")
                    }
                    PhoneFormFieldKind.TEXT -> {
                        append("<input class=\"df\" type=\"text\" name=\"$inputName\" value=\"${esc(value)}\" placeholder=\"$ph\" autocomplete=\"off\" autocorrect=\"off\" autocapitalize=\"off\"$reqAttr/>")
                    }
                    PhoneFormFieldKind.PASSWORD -> {
                        val pwdPh = if (ph.isNotEmpty()) ph else esc(copy.passwordPlaceholder)
                        append("<input class=\"df\" type=\"password\" name=\"$inputName\" value=\"\" placeholder=\"$pwdPh\" autocomplete=\"off\"$reqAttr/>")
                    }
                    PhoneFormFieldKind.NUMBER -> {
                        append("<input class=\"df\" type=\"number\" step=\"any\" name=\"$inputName\" value=\"${esc(value)}\" placeholder=\"$ph\"$reqAttr/>")
                    }
                    PhoneFormFieldKind.SELECT -> {
                        append("<select class=\"df\" name=\"$inputName\">")
                        for (opt in field.options) {
                            val sel = if (opt.value == value) " selected" else ""
                            append("<option value=\"${esc(opt.value)}\"$sel>${esc(opt.label)}</option>")
                        }
                        append("</select>")
                    }
                    PhoneFormFieldKind.TOGGLE -> {
                        val checked = if (value.equals("true", ignoreCase = true)) " checked" else ""
                        append("<label class=\"chk\"><input class=\"df\" type=\"checkbox\" name=\"$inputName\" value=\"true\"$checked/><span>${esc(copy.toggleOn)} / ${esc(copy.toggleOff)}</span></label>")
                    }
                }
            }
        }

        return """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${esc(copy.pageTitle)}</title>
<style>
body{margin:0;background:#0E0F12;color:#ECECEE;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;padding:24px 18px}
.card{max-width:440px;margin:0 auto;background:#17191D;border-radius:20px;padding:26px 22px}
h1{font-size:22px;margin:0 0 8px;font-weight:600}
p{color:#A6A9B0;font-size:15px;line-height:1.5;margin:0 0 18px}
label{display:block;color:#A6A9B0;font-size:13px;margin:14px 0 6px}
input,select{width:100%;box-sizing:border-box;background:#202328;color:#ECECEE;border:1px solid rgba(255,255,255,.12);border-radius:12px;padding:13px 15px;font-size:16px}
.chk{display:flex;align-items:center;gap:10px;color:#ECECEE;font-size:15px}
.chk input{width:20px;height:20px;margin:0}
button{width:100%;margin-top:22px;background:#ECECEE;color:#0E0F12;border:none;border-radius:14px;padding:15px;font-size:16px;font-weight:600}
.err{color:#EE8577;margin-top:12px;margin-bottom:0}
.warn{color:#E3A857;font-size:13px;margin:6px 0 0}
</style></head><body><div class="card">
<h1>${esc(copy.heading)}</h1>
<p>${esc(copy.hint)}</p>
<form method="post" action="$actionPath">
$authField
$fieldHtml
$errorBlock
<button type="submit">${esc(copy.submitLabel)}</button>
</form></div>
<script>
(function(){
  var inputs=document.querySelectorAll('.df');
  var timer=null;
  function checkHttp(){
    inputs.forEach(function(el){
      if(el.dataset.url==='1'){
        var w=document.querySelector('[data-warn-for="'+el.name+'"]');
        if(w) w.style.display=/^http:\/\//i.test((el.value||'').trim())?'block':'none';
      }
    });
  }
  function sendProgress(){
    var filled=0;
    inputs.forEach(function(el){
      if(el.type==='checkbox'){filled++;}
      else if((el.value||'').trim().length>0){filled++;}
    });
    var t=document.getElementById('authToken');
    var c=document.getElementById('authCode');
    var body='filled='+filled;
    if(t&&t.value) body+='&token='+encodeURIComponent(t.value);
    else if(c&&c.value) body+='&code='+encodeURIComponent(c.value);
    else return;
    fetch('$PATH_PROGRESS',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body}).catch(function(){});
  }
  function onEdit(){
    checkHttp();
    clearTimeout(timer);
    timer=setTimeout(sendProgress,250);
  }
  inputs.forEach(function(el){el.addEventListener('input',onEdit);el.addEventListener('change',onEdit);});
  checkHttp();
  sendProgress();
})();
</script>
</body></html>"""
    }

    private fun renderDoneHtml(nonSecretSummary: String): String {
        val esc = PhoneInputServer::escapeHtml
        val valBlock = if (nonSecretSummary.isNotBlank()) "<div class=\"val\">${esc(nonSecretSummary)}</div>" else ""
        return """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${esc(copy.pageTitle)}</title>
<style>
body{margin:0;background:#0E0F12;color:#ECECEE;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;padding:28px 20px}
.card{max-width:420px;margin:0 auto;background:#17191D;border-radius:20px;padding:28px 24px;text-align:center}
h1{font-size:22px;margin:0 0 10px}p{color:#A6A9B0;font-size:15px;margin:0}
.val{margin-top:14px;color:#E3A857;font-size:17px;font-weight:600}
</style></head><body><div class="card">
<h1>${esc(copy.doneTitle)}</h1>
<p>${esc(copy.doneSubtitle)}</p>
$valBlock
</div></body></html>"""
    }

    companion object {
        const val PATH_PROGRESS = "/progress"
    }
}
