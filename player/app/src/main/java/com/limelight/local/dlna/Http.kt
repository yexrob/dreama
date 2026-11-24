package com.limelight.local.dlna

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** One request to the receiver's HTTP server: description fetches, control calls, subscriptions. */
data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    val bodyText: String get() = String(body, Charsets.UTF_8)

    override fun equals(other: Any?) = other is HttpRequest && method == other.method && path == other.path &&
        headers == other.headers && body.contentEquals(other.body)

    override fun hashCode() = (method.hashCode() * 31 + path.hashCode()) * 31 + headers.hashCode()
}

data class HttpResponse(val status: Int, val reason: String, val headers: List<Pair<String, String>> = emptyList(),
                        val body: ByteArray = ByteArray(0)) {
    companion object {
        fun xml(text: String, status: Int = 200, reason: String = "OK") = HttpResponse(status, reason,
            listOf("Content-Type" to "text/xml; charset=\"utf-8\""), text.toByteArray(Charsets.UTF_8))

        fun empty(status: Int, reason: String, headers: List<Pair<String, String>> = emptyList()) =
            HttpResponse(status, reason, headers)
    }

    override fun equals(other: Any?) = other is HttpResponse && status == other.status &&
        headers == other.headers && body.contentEquals(other.body)

    override fun hashCode() = status * 31 + headers.hashCode()
}

/**
 * HTTP/1.1 just far enough for UPnP: one request per connection, lines ended
 * by CRLF or a bare LF (senders write both), the body by Content-Length or
 * chunks, and a cap so a stray client cannot fill the memory.
 */
object Http {
    const val MAX_BODY = 256 * 1024
    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 100

    @Throws(IOException::class)
    fun read(input: InputStream): HttpRequest? {
        val start = readLine(input) ?: return null
        val parts = start.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            throw IOException("bad request line")
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) {
                break
            }
            if (headers.size >= MAX_HEADERS) {
                throw IOException("too many headers")
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val n = headers["content-length"]!!.trim().toIntOrNull() ?: throw IOException("bad length")
                if (n < 0 || n > MAX_BODY) {
                    throw IOException("body too large")
                }
                readFully(input, n)
            }
            else -> ByteArray(0)
        }
        return HttpRequest(parts[0].uppercase(Locale.US), parts[1], headers, body)
    }

    // A line in ISO-8859-1 (headers), without its line end; null at end of stream with nothing read
    private fun readLine(input: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) {
                return if (b.isEmpty()) null else b.toString()
            }
            if (c == '\n'.code) {
                if (b.isNotEmpty() && b[b.length - 1] == '\r') {
                    b.setLength(b.length - 1)
                }
                return b.toString()
            }
            if (b.length >= MAX_LINE) {
                throw IOException("line too long")
            }
            b.append(c.toChar())
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) {
                // A short body is kept: a few senders send fewer bytes than they declared
                return out.copyOf(got)
            }
            got += r
        }
        return out
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk")
            if (size == 0) {
                // Trailers, up to the empty line
                while (true) {
                    val t = readLine(input)
                    if (t.isNullOrEmpty()) {
                        break
                    }
                }
                break
            }
            if (out.size() + size > MAX_BODY) {
                throw IOException("body too large")
            }
            out.write(readFully(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    fun encode(r: HttpResponse, server: String, date: String): ByteArray {
        val b = StringBuilder()
        b.append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason).append("\r\n")
        for ((k, v) in r.headers) {
            b.append(k).append(": ").append(v).append("\r\n")
        }
        b.append("Server: ").append(server).append("\r\n")
        b.append("Date: ").append(date).append("\r\n")
        b.append("Content-Length: ").append(r.body.size).append("\r\n")
        b.append("Connection: close\r\n\r\n")
        return b.toString().toByteArray(Charsets.ISO_8859_1) + r.body
    }

    fun write(out: OutputStream, r: HttpResponse, server: String, date: String) {
        out.write(encode(r, server, date))
        out.flush()
    }

    /** The path without the scheme, host and query, as control points send either form. */
    fun pathOf(target: String): String {
        var t = target
        val scheme = t.indexOf("://")
        if (scheme >= 0) {
            val slash = t.indexOf('/', scheme + 3)
            t = if (slash >= 0) t.substring(slash) else "/"
        }
        return t.substringBefore('?').substringBefore('#')
    }
}
