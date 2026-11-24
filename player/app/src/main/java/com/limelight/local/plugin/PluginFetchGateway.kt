package com.limelight.local.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

data class FetchRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val timeoutMs: Long? = null,
    val redirect: String = "follow",
)

data class FetchResponse(
    val status: Int,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

class FetchResponseTooLargeException(message: String) : IOException(message)

/**
 * Sandboxed HTTP gateway backing `ctx.fetch` (protocol §5 & §7):
 * - Enforces [NetworkPolicy] on the initial URL and on every redirect hop
 * - Pins the validated DNS resolution to prevent DNS rebinding
 * - Rejects `Host` request header overrides
 * - Uses the instance's isolated [PluginCookieJar]
 * - Caps response body size at 16 MB while streaming
 */
class PluginFetchGateway(
    private val policy: NetworkPolicy,
    private val cookieJar: PluginCookieJar,
    baseClient: OkHttpClient = DEFAULT_CLIENT,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .build()

    suspend fun fetch(req: FetchRequest, defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS): FetchResponse =
        withContext(Dispatchers.IO) {
            executeBlocking(req, defaultTimeoutMs)
        }

    fun executeBlocking(req: FetchRequest, defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS): FetchResponse {
        val redirectMode = req.redirect.lowercase(Locale.ROOT)
        require(redirectMode in setOf("follow", "manual", "error")) {
            "Unsupported redirect mode: ${req.redirect}"
        }
        val method = req.method.uppercase(Locale.ROOT)
        require(method in ALLOWED_METHODS) { "Unsupported HTTP method: $method" }

        for (name in req.headers.keys) {
            if (name.equals("Host", ignoreCase = true)) {
                throw NetworkPolicyException("禁止设置 Host 请求头")
            }
        }

        val timeout = (req.timeoutMs ?: defaultTimeoutMs).coerceIn(100L, MAX_TIMEOUT_MS)

        var currentUrl = req.url
        var currentMethod = method
        var currentHeaders = LinkedHashMap(req.headers)
        var currentBody: ByteArray? = req.body
        var currentContentType: String? = req.contentType
        var hops = 0

        while (true) {
            val validated = policy.validateFetchUrl(currentUrl)
            val pinnedDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname.equals(validated.url.host, ignoreCase = true)) {
                        return validated.resolvedAddresses
                    }
                    return policy.validateFetchUrl("https://$hostname/").resolvedAddresses
                }
            }
            val hopClient = client.newBuilder()
                .dns(pinnedDns)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()

            val okBody: RequestBody? = when {
                currentMethod == "GET" || currentMethod == "HEAD" -> null
                currentBody != null -> {
                    val ct = currentContentType
                        ?: currentHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                    currentBody.toRequestBody(ct?.toMediaTypeOrNull())
                }
                currentMethod in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
                else -> null
            }

            val okReq = Request.Builder()
                .url(validated.url)
                .headers(currentHeaders.toHeaders())
                .method(currentMethod, okBody)
                .build()

            hopClient.newCall(okReq).execute().use { resp ->
                val code = resp.code
                val location = resp.header("Location")
                if (code in REDIRECT_CODES && location != null) {
                    when (redirectMode) {
                        "error" -> throw IOException("请求触发重定向且 redirect=error（状态码 $code）")
                        "manual" -> return buildResponse(resp, validated.url.toString())
                        "follow" -> {
                            hops += 1
                            if (hops > maxRedirects) {
                                throw IOException("重定向次数过多（超过 $maxRedirects 次）")
                            }
                            val resolved = validated.url.resolve(location)
                                ?: throw NetworkPolicyException("无效的重定向目标地址")
                            val prevHost = validated.url.host
                            val nextHost = resolved.host
                            // Strip sensitive headers on cross-origin redirect
                            if (!prevHost.equals(nextHost, ignoreCase = true) ||
                                validated.url.scheme != resolved.scheme ||
                                validated.url.port != resolved.port
                            ) {
                                currentHeaders.keys.removeAll {
                                    it.equals("Authorization", ignoreCase = true) ||
                                        it.equals("Cookie", ignoreCase = true)
                                }
                            }
                            if (code == 303 || ((code == 301 || code == 302) && currentMethod == "POST")) {
                                currentMethod = "GET"
                                currentBody = null
                                currentContentType = null
                                currentHeaders.keys.removeAll {
                                    it.equals("Content-Type", ignoreCase = true) ||
                                        it.equals("Content-Length", ignoreCase = true)
                                }
                            }
                            currentUrl = resolved.toString()
                            continue
                        }
                    }
                }
                return buildResponse(resp, validated.url.toString())
            }
        }
    }

    private fun buildResponse(resp: okhttp3.Response, finalUrl: String): FetchResponse {
        val headerMap = LinkedHashMap<String, String>()
        for (i in 0 until resp.headers.size) {
            val k = resp.headers.name(i).lowercase(Locale.ROOT)
            val v = resp.headers.value(i)
            val existing = headerMap[k]
            headerMap[k] = if (existing == null) v else "$existing, $v"
        }

        val contentLength = resp.body.contentLength()
        if (contentLength > maxResponseBytes) {
            throw FetchResponseTooLargeException(
                "响应体超过大小限制（上限 ${maxResponseBytes / (1024 * 1024)} MB）",
            )
        }

        val out = ByteArrayOutputStream(
            if (contentLength in 1..65_536) contentLength.toInt() else 4096,
        )
        val buf = ByteArray(16_384)
        var total = 0L
        resp.body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                total += read
                if (total > maxResponseBytes) {
                    throw FetchResponseTooLargeException(
                        "响应体超过大小限制（上限 ${maxResponseBytes / (1024 * 1024)} MB）",
                    )
                }
                out.write(buf, 0, read)
            }
        }
        return FetchResponse(
            status = resp.code,
            url = finalUrl,
            headers = headerMap,
            body = out.toByteArray(),
        )
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        const val MAX_REDIRECTS = 10
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MAX_TIMEOUT_MS = 30_000L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
