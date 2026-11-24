package com.limelight.local.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
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
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
class FetchRequestTooLargeException(message: String) : IOException(message)
class FetchQueueFullException(message: String) : IOException(message)

/**
 * Thread-safe byte budget shared across all concurrent in-flight fetches and
 * buffered responses for one plugin instance (§11.1: 16 MB total).
 */
class InFlightByteBudget(
    val maxBytes: Long = PluginFetchGateway.MAX_INFLIGHT_BYTES.toLong(),
) {
    private val used = AtomicLong(0)

    fun reserve(bytes: Long) {
        if (bytes <= 0) return
        while (true) {
            val cur = used.get()
            val next = cur + bytes
            if (next > maxBytes) {
                throw FetchResponseTooLargeException(
                    "并发请求在途字节超过上限（${maxBytes / (1024 * 1024)} MB）",
                )
            }
            if (used.compareAndSet(cur, next)) return
        }
    }

    fun release(bytes: Long) {
        if (bytes <= 0) return
        while (true) {
            val cur = used.get()
            val next = (cur - bytes).coerceAtLeast(0L)
            if (used.compareAndSet(cur, next)) return
        }
    }

    fun currentUsed(): Long = used.get()
}

/**
 * Sandboxed HTTP gateway backing `ctx.fetch` (protocol §5, §7, §11.1, §11.3):
 * - Enforces [NetworkPolicy] on initial request + every redirect hop
 * - Rejects HTTPS -> HTTP downgrade redirects
 * - Connects strictly to validated pinned IPs with `.proxy(Proxy.NO_PROXY)`
 * - Rejects `Host` request header overrides
 * - Merges explicit `Cookie` header on top of instance [PluginCookieJar]
 *   (explicit wins on name collision, §11.1) and captures `Set-Cookie` per hop
 * - Enforces 8 MB single-response limit, max 4 concurrent fetches per instance,
 *   and 16 MB total in-flight byte budget (§11.1, B2)
 * - Tracks live OkHttp [Call]s so [cancelAllActive] aborts every in-flight
 *   request immediately on call settle or timeout (S3)
 */
class PluginFetchGateway(
    private val policy: NetworkPolicy,
    private val cookieJar: PluginCookieJar,
    baseClient: OkHttpClient = DEFAULT_CLIENT,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val maxConcurrentFetches: Int = MAX_CONCURRENT_FETCHES,
    val inFlightBudget: InFlightByteBudget = InFlightByteBudget(MAX_INFLIGHT_BYTES.toLong()),
    val requestBodyBudget: InFlightByteBudget = InFlightByteBudget(MAX_QUEUED_REQUEST_BODY_BYTES.toLong()),
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val maxQueuedFetches: Int = MAX_QUEUED_FETCHES,
    private val maxRequestBodyBytes: Int = MAX_REQUEST_BODY_BYTES,
    private val maxRequestUrlBytes: Int = MAX_REQUEST_URL_BYTES,
    private val maxRequestHeadersBytes: Int = MAX_REQUEST_HEADERS_BYTES,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val concurrencySemaphore = Semaphore(maxConcurrentFetches)
    private val queuedFetches = AtomicInteger(0)
    private val callSeq = AtomicLong(1)
    private val activeCalls = ConcurrentHashMap<Long, Call>()

    fun currentQueuedCount(): Int = queuedFetches.get()

    fun validateRequestMetadata(url: String, headers: Map<String, String>) {
        if (url.toByteArray(Charsets.UTF_8).size > maxRequestUrlBytes) {
            throw FetchRequestTooLargeException("请求 URL 超过大小限制（上限 ${maxRequestUrlBytes / 1024} KB）")
        }
        var headerBytes = 0L
        for ((k, v) in headers) {
            headerBytes += k.toByteArray(Charsets.UTF_8).size.toLong() +
                v.toByteArray(Charsets.UTF_8).size.toLong() + 4L
            if (headerBytes > maxRequestHeadersBytes) {
                throw FetchRequestTooLargeException(
                    "请求头总大小超过限制（上限 ${maxRequestHeadersBytes / 1024} KB）",
                )
            }
        }
    }

    fun validateRequestBodyBytes(bodyBytes: ByteArray?) {
        if (bodyBytes != null && bodyBytes.size > maxRequestBodyBytes) {
            throw FetchRequestTooLargeException(
                "请求体超过大小限制（上限 ${maxRequestBodyBytes / (1024 * 1024)} MB）",
            )
        }
    }

    /**
     * Atomically enforces the queued-request cap ([maxQueuedFetches] = 16)
     * BEFORE suspending on the 4-permit concurrency semaphore so unawaited
     * request floods fail immediately without holding request bodies on heap.
     */
    suspend fun <T> withSlot(block: suspend () -> T): T {
        if (!concurrencySemaphore.tryAcquire()) {
            while (true) {
                val cur = queuedFetches.get()
                if (cur >= maxQueuedFetches) {
                    throw FetchQueueFullException("排队中的请求数超过上限（最多 $maxQueuedFetches 个）")
                }
                if (queuedFetches.compareAndSet(cur, cur + 1)) break
            }
            val dequeued = AtomicBoolean(false)
            try {
                concurrencySemaphore.acquire()
            } finally {
                if (dequeued.compareAndSet(false, true)) {
                    queuedFetches.decrementAndGet()
                }
            }
        }
        return try {
            block()
        } finally {
            concurrencySemaphore.release()
        }
    }

    /**
     * Enforces URL/header/body/queue limits, suspends respecting the
     * 4-concurrent-fetch limit, and runs the HTTP request on [Dispatchers.IO].
     */
    suspend fun fetch(req: FetchRequest, defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS): FetchResponse {
        validateRequestMetadata(req.url, req.headers)
        validateRequestBodyBytes(req.body)
        val bodyBytes = req.body?.size?.toLong() ?: 0L
        if (bodyBytes > 0L) {
            try {
                requestBodyBudget.reserve(bodyBytes)
            } catch (e: FetchResponseTooLargeException) {
                throw FetchRequestTooLargeException(
                    "排队及在途请求体总量超过上限（${requestBodyBudget.maxBytes / (1024 * 1024)} MB）",
                )
            }
        }
        return try {
            withSlot {
                withContext(Dispatchers.IO) {
                    executeBlocking(req, defaultTimeoutMs)
                }
            }
        } finally {
            if (bodyBytes > 0L) requestBodyBudget.release(bodyBytes)
        }
    }

    fun executeBlocking(req: FetchRequest, defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS): FetchResponse {
        validateRequestMetadata(req.url, req.headers)
        validateRequestBodyBytes(req.body)
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
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)

        var currentUrl = req.url
        var prevScheme: String? = null
        var currentMethod = method
        val currentHeaders = LinkedHashMap(req.headers)
        var currentBody: ByteArray? = req.body
        var currentContentType: String? = req.contentType
        var hops = 0

        while (true) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (remainingMs <= 0L) {
                throw SocketTimeoutException("请求总耗时超过 ${timeout} ms 上限")
            }
            val validated = policy.validateFetchUrl(currentUrl, previousScheme = prevScheme)
            val pinnedDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (NetworkPolicy.canonicalizeHost(hostname) == validated.canonicalHost) {
                        return validated.resolvedAddresses
                    }
                    return policy.validateFetchUrl("https://$hostname/").resolvedAddresses
                }
            }
            val hopClient = client.newBuilder()
                .dns(pinnedDns)
                .connectTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .readTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .writeTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .build()

            // §11.1: merge jar cookies with explicit Cookie header (explicit takes precedence on collision)
            val effectiveHeaders = LinkedHashMap<String, String>()
            var explicitCookie: String? = null
            for ((k, v) in currentHeaders) {
                if (k.equals("Cookie", ignoreCase = true)) {
                    explicitCookie = v
                } else {
                    effectiveHeaders[k] = v
                }
            }
            val jarHeader = cookieJar.getHeaderForUrl(validated.url.toString())
            val mergedCookie = PluginCookieJar.mergeCookieHeaders(jarHeader, explicitCookie)
            if (mergedCookie.isNotEmpty()) {
                effectiveHeaders["Cookie"] = mergedCookie
            }

            val okBody: RequestBody? = when {
                currentMethod == "GET" || currentMethod == "HEAD" -> null
                currentBody != null -> {
                    val ct = currentContentType
                        ?: effectiveHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                    currentBody.toRequestBody(ct?.toMediaTypeOrNull())
                }
                currentMethod in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
                else -> null
            }

            val okReq = Request.Builder()
                .url(validated.url)
                .headers(effectiveHeaders.toHeaders())
                .method(currentMethod, okBody)
                .build()

            val callId = callSeq.getAndIncrement()
            val call = hopClient.newCall(okReq)
            activeCalls[callId] = call
            try {
                call.execute().use { resp ->
                    // Save Set-Cookie headers from every hop with unified RFC 6265 validation & WARN logging
                    val rawSetCookies = resp.headers.values("Set-Cookie")
                    if (rawSetCookies.isNotEmpty()) {
                        cookieJar.saveFromSetCookieHeaders(validated.url.toString(), rawSetCookies)
                    }

                    val code = resp.code
                    val location = resp.header("Location")
                    if (code in REDIRECT_CODES && location != null) {
                        when (redirectMode) {
                            "error" -> throw IOException("请求触发重定向且 redirect=error（状态码 $code）")
                            "manual" -> return buildResponse(resp, validated.url.toString(), deadlineNanos, timeout)
                            "follow" -> {
                                hops += 1
                                if (hops > maxRedirects) {
                                    throw IOException("重定向次数过多（超过 $maxRedirects 次）")
                                }
                                val resolved = validated.url.resolve(location)
                                    ?: throw NetworkPolicyException("无效的重定向目标地址")
                                if (NetworkPolicy.originKey(validated.url) != NetworkPolicy.originKey(resolved)) {
                                    // Keep ONLY safe general headers across cross-origin redirects;
                                    // strip Authorization, Cookie, Referer, and ALL custom plugin headers.
                                    currentHeaders.keys.retainAll { headerName ->
                                        headerName.lowercase(Locale.ROOT) in CROSS_ORIGIN_SAFE_HEADERS
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
                                prevScheme = validated.url.scheme
                                currentUrl = resolved.toString()
                                continue
                            }
                        }
                    }
                    return buildResponse(resp, validated.url.toString(), deadlineNanos, timeout)
                }
            } finally {
                activeCalls.remove(callId)
            }
        }
    }

    /**
     * Immediately cancels every active OkHttp call for this instance (called
     * when a plugin invocation settles, times out, or shuts down — §11.1 / S3).
     */
    fun cancelAllActive() {
        val calls = activeCalls.values.toList()
        activeCalls.clear()
        for (c in calls) {
            runCatching { c.cancel() }
        }
    }

    fun activeCallCount(): Int = activeCalls.size

    private fun buildResponse(
        resp: okhttp3.Response,
        finalUrl: String,
        deadlineNanos: Long,
        totalTimeoutMs: Long,
    ): FetchResponse {
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
        var reserved = 0L
        try {
            resp.body.byteStream().use { stream ->
                while (true) {
                    if (System.nanoTime() >= deadlineNanos) {
                        throw SocketTimeoutException("请求总耗时超过 ${totalTimeoutMs} ms 上限")
                    }
                    val read = stream.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > maxResponseBytes) {
                        throw FetchResponseTooLargeException(
                            "响应体超过大小限制（上限 ${maxResponseBytes / (1024 * 1024)} MB）",
                        )
                    }
                    inFlightBudget.reserve(read.toLong())
                    reserved += read
                    out.write(buf, 0, read)
                }
            }
            val bytes = out.toByteArray()
            // Release download stream reservation; caller (PluginRuntime) retains or releases budget
            inFlightBudget.release(reserved)
            return FetchResponse(
                status = resp.code,
                url = finalUrl,
                headers = headerMap,
                body = bytes,
            )
        } catch (t: Throwable) {
            inFlightBudget.release(reserved)
            throw t
        }
    }

    companion object {
        /** §11.1: single fetch response cap is 8 MB. */
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        /** Request body cap is 2 MB. */
        const val MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024
        /** Total queued + active request body byte budget per instance is 8 MB. */
        const val MAX_QUEUED_REQUEST_BODY_BYTES = 8 * 1024 * 1024
        /** Request URL length cap is 8 KB. */
        const val MAX_REQUEST_URL_BYTES = 8 * 1024
        /** Request headers total byte cap is 32 KB. */
        const val MAX_REQUEST_HEADERS_BYTES = 32 * 1024
        /** Raw JSON bridge envelope cap (2 MB base64 + 32 KB headers + 8 KB URL + framing). */
        const val MAX_FETCH_META_JSON_BYTES = 3 * 1024 * 1024
        /** §11.1: max 4 concurrent fetches per instance. */
        const val MAX_CONCURRENT_FETCHES = 4
        /** Max queued fetches waiting for a concurrency permit. */
        const val MAX_QUEUED_FETCHES = 16
        /** §11.1: max 16 MB total in-flight bytes per instance. */
        const val MAX_INFLIGHT_BYTES = 16 * 1024 * 1024
        const val MAX_REDIRECTS = 10
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MAX_TIMEOUT_MS = 30_000L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val CROSS_ORIGIN_SAFE_HEADERS = setOf("accept", "accept-language", "user-agent", "range")
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
