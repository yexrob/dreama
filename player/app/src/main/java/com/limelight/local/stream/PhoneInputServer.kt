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
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
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
        }
    }
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
 * - QR URL carries a 128-bit random token (`?t=<22-char base64url>`).
 * - Manual short URL requires the 4-digit verification code and shuts the server down permanently
 *   after [MAX_FAILED_ATTEMPTS] wrong attempts.
 * - GET requests NEVER reveal whether a token or code is valid (zero GET oracle).
 * - Each client connection runs on a bounded worker pool with per-socket timeout and request
 *   deadline so a stalled/slow client cannot block a real phone.
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
    }

    private val closed = AtomicBoolean(false)
    private val failedAttempts = AtomicInteger(0)
    private val concurrencyGate = Semaphore(MAX_CONCURRENT_CONNECTIONS)
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

    /** Short URL displayed to the user if they choose to type it manually + enter the 4-digit code. */
    val shortUrl: String
        get() = "http://$displayHost:$boundPort"

    /** Full URL encoded in the QR code carrying the 128-bit random token. */
    val qrUrl: String
        get() = "http://$displayHost:$boundPort/?t=$qrToken"

    fun start(): PhoneInputServer {
        val ss = ServerSocket(port, 16, bindAddress)
        serverSocket = ss
        val t = Thread({ acceptLoop(ss) }, "depth3d-phone-accept").apply { isDaemon = true }
        acceptThread = t
        t.start()
        LimeLog.info("depth3d: phone input server listening on $displayHost:${ss.localPort}")
        return this
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
        workers.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            if (!concurrencyGate.tryAcquire()) {
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
        socket.soTimeout = socketTimeoutMs
        val deadline = System.currentTimeMillis() + requestDeadlineMs
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readAsciiLine(input, deadline) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        var contentLength = 0
        var headerLines = 0
        while (headerLines < MAX_HEADER_LINES) {
            val line = readAsciiLine(input, deadline) ?: return
            if (line.isEmpty()) break
            headerLines++
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            }
        }

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
                val bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    if (System.currentTimeMillis() > deadline) return
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < contentLength) return
                val params = parseForm(String(bodyBytes, 0, read, StandardCharsets.UTF_8))
                val submittedToken = params["token"].orEmpty().trim()
                val submittedCode = params["code"].orEmpty().trim()
                val submittedValue = params["value"].orEmpty().trim()

                val authorized = (submittedToken.isNotEmpty() && constantTimeEquals(submittedToken, qrToken)) ||
                    (submittedCode.isNotEmpty() && constantTimeEquals(submittedCode, code))

                if (!authorized) {
                    val tries = failedAttempts.incrementAndGet()
                    if (tries >= MAX_FAILED_ATTEMPTS) {
                        LimeLog.warning("depth3d: phone input locked out after $tries failed attempts")
                        writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, error = copy.errLockedOut))
                        close()
                        onLockedOut()
                    } else {
                        writeHtml(out, 403, "Forbidden", renderFormHtml(hiddenToken = null, error = copy.errWrongCode))
                    }
                    return
                }

                val keepToken = if (submittedToken.isNotEmpty() && constantTimeEquals(submittedToken, qrToken)) {
                    submittedToken
                } else {
                    null
                }

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

    private fun readAsciiLine(input: InputStream, deadlineMs: Long): String? {
        val sb = StringBuilder(64)
        while (true) {
            if (System.currentTimeMillis() > deadlineMs) return null
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
<form method="post" action="/submit">
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
        private const val WORKER_THREADS = 4
        private const val MAX_CONCURRENT_CONNECTIONS = 8
        private const val DEFAULT_SOCKET_TIMEOUT_MS = 3_000
        private const val DEFAULT_REQUEST_DEADLINE_MS = 4_000L
        private const val MAX_HEADER_LINES = 64
        private const val MAX_LINE_LEN = 2048
        private const val MAX_BODY_BYTES = 4096
        private const val MAX_VALUE_LEN = 128

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

        private fun constantTimeEquals(a: String, b: String): Boolean =
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

        private fun escapeHtml(s: String): String = buildString(s.length) {
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
