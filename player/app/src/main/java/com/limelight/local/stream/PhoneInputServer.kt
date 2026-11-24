package com.limelight.local.stream

import com.limelight.LimeLog
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny LAN HTTP server so the user can enter a computer IP address or headset name on their
 * phone (scanning a QR code or opening a short URL + 4-digit code) instead of typing in VR.
 */
class PhoneInputServer(
    private val mode: Mode,
    private val displayHost: String,
    private val code: String = generateCode(),
    private val bindAddress: InetAddress? = null,
    private val port: Int = 0,
    private val onSubmitted: (String) -> Unit,
) : Closeable {

    enum class Mode(
        val pageTitle: String,
        val heading: String,
        val hint: String,
        val placeholder: String,
        val submitLabel: String,
    ) {
        ADD_COMPUTER(
            pageTitle = "depth3d · 添加电脑",
            heading = "添加电脑到头显",
            hint = "输入电脑在当前 Wi-Fi 里的局域网地址",
            placeholder = "例如 192.168.1.20",
            submitLabel = "发送到头显",
        ),
        DEVICE_NAME(
            pageTitle = "depth3d · 设备名称",
            heading = "修改头显投屏名称",
            hint = "投屏列表和电脑配对提示里会显示这个名字",
            placeholder = "例如 客厅的 depth3d",
            submitLabel = "保存名称",
        ),
    }

    private val closed = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: 0

    val verificationCode: String
        get() = code

    /** Short URL displayed to the user if they choose to type it instead of scanning. */
    val shortUrl: String
        get() = "http://$displayHost:$boundPort"

    /** Full URL encoded in the QR code so scanning pre-fills the verification code. */
    val qrUrl: String
        get() = "http://$displayHost:$boundPort/?code=$code"

    fun start(): PhoneInputServer {
        val ss = ServerSocket(port, 8, bindAddress)
        serverSocket = ss
        val t = Thread({ acceptLoop(ss) }, "depth3d-phone-input").apply { isDaemon = true }
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
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            try {
                handleClient(client)
            } catch (e: Exception) {
                LimeLog.warning("depth3d: phone input request error: ${e.javaClass.simpleName}")
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 5000
        val input = java.io.BufferedInputStream(socket.getInputStream())
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        var contentLength = 0
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, 4096) ?: 0
            }
        }

        val out = socket.getOutputStream()
        when (method) {
            "GET" -> {
                val query = rawPath.substringAfter('?', "")
                val params = parseForm(query)
                val prefilledCode = params["code"]?.takeIf { it == code }
                writeHtml(out, 200, "OK", renderFormHtml(prefilledCode, error = null))
            }
            "POST" -> {
                val bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                val params = parseForm(String(bodyBytes, 0, read, StandardCharsets.UTF_8))
                val submittedCode = params["code"].orEmpty().trim()
                val submittedValue = params["value"].orEmpty().trim()
                when {
                    submittedCode != code -> {
                        writeHtml(out, 403, "Forbidden", renderFormHtml(null, error = "4 位验证码不对，请核对头显上的数字。"))
                    }
                    submittedValue.isEmpty() || submittedValue.length > 128 -> {
                        writeHtml(out, 400, "Bad Request", renderFormHtml(code, error = "内容不能为空，也不能超过 128 个字符。"))
                    }
                    else -> {
                        writeHtml(out, 200, "OK", renderDoneHtml(submittedValue))
                        onSubmitted(submittedValue)
                    }
                }
            }
            else -> writeHtml(out, 405, "Method Not Allowed", renderFormHtml(null, error = null))
        }
    }

    private fun readAsciiLine(input: java.io.InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 2048) break
        }
        return sb.toString()
    }

    private fun writeHtml(out: java.io.OutputStream, status: Int, reason: String, html: String) {
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

    private fun renderFormHtml(prefilledCode: String?, error: String?): String {
        val errorBlock = if (error != null) "<p class=\"err\">${escapeHtml(error)}</p>" else ""
        val codeField = if (prefilledCode != null) {
            "<input type=\"hidden\" name=\"code\" value=\"${escapeHtml(prefilledCode)}\"/>"
        } else {
            "<label>头显上的 4 位数字</label>" +
                "<input name=\"code\" inputmode=\"numeric\" maxlength=\"4\" placeholder=\"4 位验证码\" required/>"
        }
        return """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${escapeHtml(mode.pageTitle)}</title>
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
<h1>${escapeHtml(mode.heading)}</h1>
<p>${escapeHtml(mode.hint)}</p>
<form method="post" action="/submit">
$codeField
<label>内容</label>
<input name="value" placeholder="${escapeHtml(mode.placeholder)}" autocomplete="off" autocorrect="off" autocapitalize="off" required autofocus/>
$errorBlock
<button type="submit">${escapeHtml(mode.submitLabel)}</button>
</form></div></body></html>"""
    }

    private fun renderDoneHtml(value: String): String = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${escapeHtml(mode.pageTitle)}</title>
<style>
body{margin:0;background:#0E0F12;color:#ECECEE;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;padding:28px 20px}
.card{max-width:420px;margin:0 auto;background:#17191D;border-radius:20px;padding:28px 24px;text-align:center}
h1{font-size:22px;margin:0 0 10px}p{color:#A6A9B0;font-size:15px;margin:0}
.val{margin-top:14px;color:#E3A857;font-size:17px;font-weight:600}
</style></head><body><div class="card">
<h1>已发送到头显</h1>
<p>现在可以戴回头显继续操作，这个页面可以直接关闭。</p>
<div class="val">${escapeHtml(value)}</div>
</div></body></html>"""

    companion object {
        private val random = SecureRandom()

        fun generateCode(): String = (1000 + random.nextInt(9000)).toString()

        internal fun parseForm(encoded: String): Map<String, String> {
            if (encoded.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in encoded.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name())
                val v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name())
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
