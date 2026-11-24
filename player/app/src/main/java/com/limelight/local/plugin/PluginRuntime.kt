package com.limelight.local.plugin

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.moduleLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardware/device capability descriptor exposed to plugins via `ctx.device` (§5).
 */
data class DeviceInfo(
    val appVersion: String = "1.0.0",
    val locale: String = "zh-CN",
    val model: String = "Headset",
    val maxWidth: Int = 3840,
    val maxHeight: Int = 2160,
    val codecs: List<String> = listOf("avc", "hevc"),
    val hdr: Boolean = false,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("appVersion", appVersion)
        put("locale", locale)
        put("model", model)
        put("maxWidth", maxWidth)
        put("maxHeight", maxHeight)
        put("codecs", buildJsonArray { codecs.forEach { add(JsonPrimitive(it)) } })
        put("hdr", hdr)
    }

    companion object {
        fun detect(appVersion: String): DeviceInfo {
            val locale = Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
            val model = try {
                Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
            } catch (_: Throwable) {
                "Android"
            }
            val detectedCodecs = LinkedHashSet<String>()
            var hdr = false
            try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    if (info.isEncoder) continue
                    for (type in info.supportedTypes) {
                        when (type.lowercase(Locale.ROOT)) {
                            "video/avc" -> detectedCodecs += "avc"
                            "video/hevc" -> {
                                detectedCodecs += "hevc"
                                val caps = info.getCapabilitiesForType(type)
                                if (caps.profileLevels.any { pl ->
                                        pl.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                            pl.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                            pl.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                                    }
                                ) {
                                    hdr = true
                                }
                            }
                            "video/av01" -> detectedCodecs += "av1"
                        }
                    }
                }
            } catch (_: Throwable) {
                detectedCodecs += "avc"
                detectedCodecs += "hevc"
            }
            if (detectedCodecs.isEmpty()) {
                detectedCodecs += "avc"
            }
            return DeviceInfo(
                appVersion = appVersion,
                locale = locale,
                model = model,
                maxWidth = 3840,
                maxHeight = 2160,
                codecs = detectedCodecs.toList(),
                hdr = hdr,
            )
        }
    }
}

/**
 * Isolated per-instance QuickJS runtime hosting one plugin instance (§4.4, §5,
 * §6, §7, §11.1):
 * - Heap cap 64 MB (§11.1), stack cap 512 KB
 * - Loads shared `docs/specs/plugin-schema/runtime-bootstrap.js` sealed with an
 *   unforgeable per-runtime capability token so plugins cannot call raw host
 *   bindings even when `globalThis.__d3_host` is non-configurable
 * - Direct binary `ByteArray` transfer for `ctx.fetch` (`bytes()` -> `ArrayBuffer`,
 *   no base64 JSON inflation), 8 MB single response, 4 concurrent fetches, 16 MB
 *   in-flight byte budget
 * - Lazy handle-backed `ctx.parse` (max 8 docs × 4 MB per call)
 * - Async `ctx.storage.*` & `ctx.cookies.*` with coalesced cookie flush at call end
 * - True host-side cancellation for `clearTimeout`, leftover timers, and active
 *   OkHttp calls the instant the plugin's Promise settles or times out
 * - Safe [close] interrupts active work and waits for evaluation exit before
 *   releasing native QuickJS memory
 */
class PluginRuntime(
    val manifest: PluginManifest,
    val instanceId: String,
    private val mainJsSource: String,
    private val config: Map<String, JsonElement>,
    private val fetchGateway: PluginFetchGateway,
    private val storage: InstanceStorage,
    private val cookieJar: PluginCookieJar,
    private val logger: PluginLogger,
    private val locale: PluginLocale = PluginLocale.EMPTY,
    val deviceInfo: DeviceInfo = DeviceInfo(),
    private val androidContext: Context? = null,
    private val memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT_BYTES,
    private val defaultTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val playbackTimeoutMs: Long = PLAYBACK_CALL_TIMEOUT_MS,
) : Closeable {

    private val json = Json { ignoreUnknownKeys = true }
    private val callMutex = Mutex()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "depth3d-qjs-${manifest.id}-$instanceId").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val domParser = PluginDomParser()

    private var quickJs: QuickJs? = null
    private var bridgeToken: String = ""
    private var initialized = false
    @Volatile
    private var closed = false
    @Volatile
    private var activeCallScope: CoroutineScope? = null
    @Volatile
    private var storageCorruptedInCall: Boolean = false
    private val callGeneration = AtomicInteger(0)

    // Per-call live timers and buffered fetch response bodies (all released on settle/timeout)
    private val activeTimers = ConcurrentHashMap<Int, Job>()
    private val responseHandleSeq = AtomicInteger(1)
    private val responseBodies = ConcurrentHashMap<Int, ByteArray>()

    /** Count of how many times the underlying QuickJS runtime was created. */
    @Volatile
    var creationCount: Int = 0
        private set

    /**
     * Invokes an exported method on the plugin's default export object.
     * Calls on the same instance execute serially (§11.1).
     */
    suspend fun invoke(
        method: String,
        arg: JsonElement? = null,
        itemIdForErrors: String? = null,
        timeoutMsOverride: Long? = null,
    ): JsonElement = callMutex.withLock {
        invokeLocked(method, arg, itemIdForErrors, timeoutMsOverride)
    }

    /**
     * Best-effort invocation used by `reportProgress` (§11.1): if this instance
     * is currently busy with another call, immediately drops the call and
     * returns `false` without blocking playback.
     */
    suspend fun invokeIfNotBusy(
        method: String,
        arg: JsonElement? = null,
        itemIdForErrors: String? = null,
        timeoutMsOverride: Long? = null,
    ): Boolean {
        if (!callMutex.tryLock()) return false
        try {
            invokeLocked(method, arg, itemIdForErrors, timeoutMsOverride)
            return true
        } finally {
            callMutex.unlock()
        }
    }

    private suspend fun invokeLocked(
        method: String,
        arg: JsonElement?,
        itemIdForErrors: String?,
        timeoutMsOverride: Long?,
    ): JsonElement {
        if (closed) {
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                itemId = itemIdForErrors,
                context = androidContext,
            )
        }
        val timeoutMs = timeoutMsOverride
            ?: if (method == "playback") playbackTimeoutMs else defaultTimeoutMs

        ensureInitializedLocked(timeoutMs)

        val gen = callGeneration.incrementAndGet()
        val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        activeCallScope = callScope
        storageCorruptedInCall = false
        domParser.clear()
        releaseAllResponseBodies()

        val qjs = quickJs ?: throw PluginErrorMapper.toException(
            PluginErrorPayload(PluginErrorCode.INTERNAL),
            itemId = itemIdForErrors,
            context = androidContext,
        )
        qjs.evaluationTimeoutMillis = timeoutMs

        val argJson = arg?.toString() ?: "null"
        val hasArg = arg != null
        val invokeScript = "await __d3_invoke(${JsonPrimitive(method)}, ${JsonPrimitive(argJson)}, $hasArg, $gen)"

        return try {
            val rawResult = withContext(dispatcher) {
                withTimeout(timeoutMs) {
                    qjs.evaluate<String?>(invokeScript, filename = "__d3_call.js")
                }
            }
            if (rawResult.isNullOrEmpty() || rawResult == "undefined") {
                JsonNull
            } else {
                if (rawResult.length > MAX_RETURN_BYTES ||
                    rawResult.toByteArray(Charsets.UTF_8).size > MAX_RETURN_BYTES
                ) {
                    throw PluginErrorMapper.toException(
                        PluginErrorPayload(PluginErrorCode.INTERNAL),
                        itemId = itemIdForErrors,
                        context = androidContext,
                    )
                }
                json.parseToJsonElement(rawResult)
            }
        } catch (t: Throwable) {
            handleInvocationFailureLocked(qjs, method, t, itemIdForErrors)
        } finally {
            cleanupActiveCall(callScope)
            runCatching { cookieJar.flush() }
        }
    }

    suspend fun reset() = callMutex.withLock {
        destroyRuntimeLocked(runDispose = true)
    }

    suspend fun shutdown() = callMutex.withLock {
        if (closed) return@withLock
        closed = true
        destroyRuntimeLocked(runDispose = true)
        runCatching { cookieJar.flush() }
        executor.shutdown()
        executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        executor.shutdownNow()
    }

    /**
     * Synchronous close safe to call even while an evaluation may be running:
     * interrupts JS evaluation & network calls first, then shuts down the
     * single-thread executor and waits up to [SHUTDOWN_WAIT_MS] for the active
     * evaluation thread to exit before freeing the native QuickJS runtime.
     */
    override fun close() {
        if (closed) return
        closed = true
        val existing = quickJs
        try {
            existing?.interruptEvaluation()
        } catch (_: Throwable) {
        }
        cancelAllCallResources()
        // Wait for the QuickJS worker thread to finish exiting native evaluation before freeing
        executor.shutdown()
        val drained = try {
            executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!drained) {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(500, TimeUnit.MILLISECONDS) }
        }
        try {
            existing?.close()
        } catch (_: Throwable) {
        }
        quickJs = null
        initialized = false
        runCatching { cookieJar.flush() }
    }

    private suspend fun ensureInitializedLocked(timeoutMs: Long) {
        if (quickJs != null && initialized) return
        destroyRuntimeLocked(runDispose = false)

        val loader = moduleLoader {
            load { name ->
                when (name) {
                    "plugin/main.js", "./main.js", "main.js" -> ModuleContent.Source(mainJsSource)
                    else -> null
                }
            }
        }
        val qjs = QuickJs.create(jobDispatcher = dispatcher, moduleLoader = loader)
        qjs.memoryLimit = memoryLimitBytes
        qjs.maxStackSize = MAX_STACK_BYTES
        qjs.evaluationTimeoutMillis = timeoutMs
        bridgeToken = PluginCrypto.hexEncode(PluginCrypto.randomBytes(16))
        creationCount += 1

        try {
            bindHostBridge(qjs, bridgeToken)
            withContext(dispatcher) {
                withTimeout(timeoutMs) {
                    qjs.evaluate<Unit>(SHARED_BOOTSTRAP_JS, filename = "__d3_bootstrap.js")
                    qjs.evaluate<Unit>(
                        "__d3_seal_bootstrap(${JsonPrimitive(bridgeToken)});",
                        filename = "__d3_seal.js",
                    )
                    qjs.evaluate<Unit>(
                        """
                        import __plugin from 'plugin/main.js';
                        globalThis.__d3_register(__plugin);
                        """.trimIndent(),
                        filename = "__d3_entry.js",
                        asModule = true,
                    )
                }
            }
            quickJs = qjs
            // Run optional init(ctx) inside its own scoped invocation (§11.1: failure == call failure)
            val gen = callGeneration.incrementAndGet()
            val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            activeCallScope = initScope
            storageCorruptedInCall = false
            try {
                withContext(dispatcher) {
                    withTimeout(timeoutMs) {
                        qjs.evaluate<String?>(
                            "await __d3_invoke_optional('init', 'null', false, $gen)",
                            filename = "__d3_init.js",
                        )
                    }
                }
            } finally {
                cleanupActiveCall(initScope)
                runCatching { cookieJar.flush() }
            }
            initialized = true
        } catch (t: Throwable) {
            try {
                qjs.interruptEvaluation()
            } catch (_: Throwable) {
            }
            try {
                qjs.close()
            } catch (_: Throwable) {
            }
            quickJs = null
            initialized = false
            handleInvocationFailureLocked(null, "init", t, null)
        }
    }

    private suspend fun destroyRuntimeLocked(runDispose: Boolean) {
        val existing = quickJs ?: return
        if (runDispose && initialized) {
            try {
                existing.evaluationTimeoutMillis = DISPOSE_TIMEOUT_MS
                val gen = callGeneration.incrementAndGet()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                activeCallScope = scope
                try {
                    withContext(dispatcher) {
                        withTimeout(DISPOSE_TIMEOUT_MS) {
                            existing.evaluate<String?>(
                                "await __d3_invoke_optional('dispose', 'null', false, $gen)",
                                filename = "__d3_dispose.js",
                            )
                        }
                    }
                } finally {
                    cleanupActiveCall(scope)
                }
            } catch (_: Throwable) {
                // Best-effort dispose
            }
        }
        cancelAllCallResources()
        try {
            existing.interruptEvaluation()
        } catch (_: Throwable) {
        }
        try {
            withContext(dispatcher) {
                existing.close()
            }
        } catch (_: Throwable) {
            runCatching { existing.close() }
        }
        quickJs = null
        initialized = false
    }

    private fun cleanupActiveCall(scope: CoroutineScope) {
        callGeneration.incrementAndGet()
        activeCallScope = null
        cancelAllTimers()
        fetchGateway.cancelAllActive()
        scope.cancel()
        domParser.clear()
        releaseAllResponseBodies()
    }

    private fun cancelAllCallResources() {
        callGeneration.incrementAndGet()
        cancelAllTimers()
        fetchGateway.cancelAllActive()
        activeCallScope?.cancel()
        activeCallScope = null
        domParser.clear()
        releaseAllResponseBodies()
    }

    private fun cancelAllTimers() {
        val jobs = activeTimers.values.toList()
        activeTimers.clear()
        for (j in jobs) j.cancel()
    }

    private fun releaseAllResponseBodies() {
        val iter = responseBodies.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            iter.remove()
            fetchGateway.inFlightBudget.release(entry.value.size.toLong())
        }
    }

    private suspend fun handleInvocationFailureLocked(
        qjs: QuickJs?,
        method: String,
        t: Throwable,
        itemId: String?,
    ): Nothing {
        // 1. Timeout or interrupted -> cancel OkHttp + destroy & rebuild next time, map to Unavailable (§7 & S3)
        // Note: plain plugin `throw new Error("...timeout...")` or `throw new Error("interrupted")` MUST map to Internal,
        // NOT Unavailable; only genuine host timeout/QuickJS interruption triggers Unavailable & VM rebuild.
        if (t is TimeoutCancellationException || t is QuickJsInterruptedException ||
            (t is QuickJsException && t.message?.contains("InternalError: interrupted", ignoreCase = true) == true)
        ) {
            logger.warn("Method '$method' timed out; cancelling active requests and rebuilding runtime")
            fetchGateway.cancelAllActive()
            try {
                qjs?.interruptEvaluation()
            } catch (_: Throwable) {
            }
            destroyRuntimeLocked(runDispose = false)
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.UNAVAILABLE),
                itemId = itemId,
                context = androidContext,
                cause = t,
            )
        }

        // 2. Storage decryption corruption -> map to NeedsAuth (preserving original file on disk)
        if (storageCorruptedInCall || hasCause<StorageCorruptedException>(t)) {
            storageCorruptedInCall = false
            logger.error("Encrypted storage/cookies could not be decrypted during '$method'")
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.NEEDS_AUTH, "本地加密凭据无法读取，请重新登录。"),
                itemId = itemId,
                context = androidContext,
                cause = t,
            )
        }

        // 3. Structured ctx.Error thrown by plugin (§6)
        val parsed = PluginErrorMapper.parseWireError(t.message)
        if (parsed != null) {
            throw PluginErrorMapper.toException(
                parsed,
                itemId = itemId,
                context = androidContext,
                cause = t,
            )
        }

        // 4. Out of memory -> destroy immediately & rebuild on next call (§7, §11.1)
        if (isOutOfMemoryError(t)) {
            logger.error("QuickJS runtime hit memory limit during '$method'; destroying runtime for rebuild")
            destroyRuntimeLocked(runDispose = false)
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                itemId = itemId,
                context = androidContext,
                cause = t,
            )
        }

        // 5. All other JS/host errors -> Internal (§6)
        logger.error("Unhandled error in '$method': ${t.message ?: t.javaClass.simpleName}")
        throw PluginErrorMapper.toException(
            PluginErrorPayload(PluginErrorCode.INTERNAL),
            itemId = itemId,
            context = androidContext,
            cause = t,
        )
    }

    private inline fun <reified E : Throwable> hasCause(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is E) return true
            cur = cur.cause
        }
        return false
    }

    private fun isOutOfMemoryError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is OutOfMemoryError) return true
            val msg = cur.message?.lowercase(Locale.ROOT)
            if (msg != null && (msg.contains("out of memory") || msg.contains("memory limit"))) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private suspend inline fun <T> guardStorage(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: StorageCorruptedException) {
            storageCorruptedInCall = true
            throw e
        }
    }

    private fun requireToken(expected: String, args: Array<Any?>) {
        val provided = args.getOrNull(0) as? String
        if (provided != expected) {
            throw SecurityException("Unauthorized access to internal host bridge")
        }
    }

    private fun bindHostBridge(qjs: QuickJs, expectedToken: String) {
        val configJson = JsonObject(config).toString()
        val deviceJson = deviceInfo.toJsonObject().toString()

        qjs.define("__d3_host") {
            function("getConfigJson") { args ->
                requireToken(expectedToken, args)
                configJson
            }
            function("getInstanceId") { args ->
                requireToken(expectedToken, args)
                instanceId
            }
            function("getDeviceJson") { args ->
                requireToken(expectedToken, args)
                deviceJson
            }
            function("getErrorMarker") { args ->
                requireToken(expectedToken, args)
                PluginErrorMapper.WIRE_MARKER
            }
            function("isStrictAwaitTracking") { args ->
                requireToken(expectedToken, args)
                false
            }
            function("reportUnawaitedOps") { args ->
                requireToken(expectedToken, args)
                Unit
            }

            // §11.1 / S3: true cancellable timers & immediate settle cleanup (max 256 active timers)
            asyncFunction("sleep") { args ->
                requireToken(expectedToken, args)
                val timerId = (args.getOrNull(1) as? Number)?.toInt() ?: return@asyncFunction false
                val ms = (args.getOrNull(2) as? Number)?.toLong()?.coerceIn(0L, 60_000L) ?: 0L
                val gen = (args.getOrNull(3) as? Number)?.toInt() ?: -1
                if (gen != callGeneration.get()) return@asyncFunction false
                val scope = activeCallScope ?: return@asyncFunction false
                if (activeTimers.size >= MAX_ACTIVE_TIMERS) {
                    throw IllegalStateException("同时活跃的定时器数量超过上限（最多 $MAX_ACTIVE_TIMERS 个）")
                }

                val done = CompletableDeferred<Boolean>()
                val job = scope.launch {
                    try {
                        if (ms > 0) delay(ms)
                        done.complete(gen == callGeneration.get())
                    } catch (_: CancellationException) {
                        done.complete(false)
                    } finally {
                        activeTimers.remove(timerId)
                    }
                }
                activeTimers[timerId] = job
                if (gen != callGeneration.get()) {
                    job.cancel()
                    activeTimers.remove(timerId)
                    return@asyncFunction false
                }
                done.await()
            }

            function("cancelTimer") { args ->
                requireToken(expectedToken, args)
                val timerId = (args.getOrNull(1) as? Number)?.toInt() ?: return@function Unit
                activeTimers.remove(timerId)?.cancel()
                Unit
            }

            function("finishCall") { args ->
                requireToken(expectedToken, args)
                val gen = (args.getOrNull(1) as? Number)?.toInt() ?: -1
                if (gen == callGeneration.get()) {
                    cancelAllTimers()
                    fetchGateway.cancelAllActive()
                }
                Unit
            }

            // §11.1 / B2: binary fetch bridge (no base64 JSON inflation, 8 MB body / 16 MB in-flight cap)
            asyncFunction("fetchMeta") { args ->
                requireToken(expectedToken, args)
                val reqJson = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("Missing fetch request")
                val gen = (args.getOrNull(2) as? Number)?.toInt() ?: -1
                if (gen != callGeneration.get()) {
                    throw CancellationException("Call already finished")
                }
                val obj = json.parseToJsonElement(reqJson) as JsonObject
                val url = (obj["url"] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("Missing fetch url")
                val method = (obj["method"] as? JsonPrimitive)?.content ?: "GET"
                val headersObj = obj["headers"] as? JsonObject
                val headers = headersObj?.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.content ?: v.toString()
                } ?: emptyMap()
                val bodyBase64 = (obj["bodyBase64"] as? JsonPrimitive)?.content
                val bodyText = (obj["bodyText"] as? JsonPrimitive)?.content
                val bodyBytes = when {
                    bodyBase64 != null -> PluginCrypto.base64Decode(bodyBase64)
                    bodyText != null -> bodyText.toByteArray(Charsets.UTF_8)
                    else -> null
                }
                val contentType = (obj["contentType"] as? JsonPrimitive)?.content
                val timeoutMs = (obj["timeoutMs"] as? JsonPrimitive)?.longOrNull
                val redirect = (obj["redirect"] as? JsonPrimitive)?.content ?: "follow"

                val resp = fetchGateway.fetch(
                    FetchRequest(
                        url = url,
                        method = method,
                        headers = headers,
                        body = bodyBytes,
                        contentType = contentType,
                        timeoutMs = timeoutMs,
                        redirect = redirect,
                    ),
                )
                // Reserve buffer bytes against the 16 MB in-flight budget until JS reads or call ends
                if (gen != callGeneration.get()) {
                    throw CancellationException("Call already finished")
                }
                fetchGateway.inFlightBudget.reserve(resp.body.size.toLong())
                val handleId = responseHandleSeq.getAndIncrement()
                responseBodies[handleId] = resp.body

                buildJsonObject {
                    put("handleId", handleId)
                    put("status", resp.status)
                    put("url", resp.url)
                    put(
                        "headers",
                        buildJsonObject {
                            resp.headers.forEach { (k, v) -> put(k, v) }
                        },
                    )
                }.toString()
            }

            function("fetchReadBytes") { args ->
                requireToken(expectedToken, args)
                val handleId = (args.getOrNull(1) as? Number)?.toInt() ?: return@function ByteArray(0)
                val bytes = responseBodies.remove(handleId) ?: return@function ByteArray(0)
                fetchGateway.inFlightBudget.release(bytes.size.toLong())
                bytes
            }

            function("fetchReadText") { args ->
                requireToken(expectedToken, args)
                val handleId = (args.getOrNull(1) as? Number)?.toInt() ?: return@function ""
                val bytes = responseBodies.remove(handleId) ?: return@function ""
                fetchGateway.inFlightBudget.release(bytes.size.toLong())
                String(bytes, Charsets.UTF_8)
            }

            // §11.1: async ctx.storage.*
            asyncFunction("storageGet") { args ->
                requireToken(expectedToken, args)
                val key = args.getOrNull(1)?.toString() ?: return@asyncFunction null
                guardStorage { withContext(Dispatchers.IO) { storage.get(key)?.toString() } }
            }
            asyncFunction("storageSet") { args ->
                requireToken(expectedToken, args)
                val key = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("storage.set requires key")
                val valJson = args.getOrNull(2)?.toString() ?: "null"
                // Check UTF-8 byte quota BEFORE parsing JSON to protect host heap
                guardStorage { withContext(Dispatchers.IO) { storage.setRawJson(key, valJson) } }
                Unit
            }
            asyncFunction("storageRemove") { args ->
                requireToken(expectedToken, args)
                val key = args.getOrNull(1)?.toString() ?: return@asyncFunction Unit
                guardStorage { withContext(Dispatchers.IO) { storage.remove(key) } }
                Unit
            }
            asyncFunction("storageKeys") { args ->
                requireToken(expectedToken, args)
                guardStorage {
                    withContext(Dispatchers.IO) {
                        buildJsonArray {
                            storage.keys().forEach { add(JsonPrimitive(it)) }
                        }.toString()
                    }
                }
            }

            // §11.1: async ctx.cookies.*
            asyncFunction("cookiesGet") { args ->
                requireToken(expectedToken, args)
                val url = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("cookies.get requires url")
                guardStorage { withContext(Dispatchers.IO) { cookieJar.getHeaderForUrl(url) } }
            }
            asyncFunction("cookiesSet") { args ->
                requireToken(expectedToken, args)
                val url = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("cookies.set requires url")
                val cookie = args.getOrNull(2)?.toString()
                    ?: throw IllegalArgumentException("cookies.set requires cookie")
                guardStorage { withContext(Dispatchers.IO) { cookieJar.setCookie(url, cookie) } }
                Unit
            }
            asyncFunction("cookiesClear") { args ->
                requireToken(expectedToken, args)
                withContext(Dispatchers.IO) { cookieJar.clear() }
                Unit
            }

            // ctx.crypto
            function("cryptoHash") { args ->
                requireToken(expectedToken, args)
                val algo = args.getOrNull(1)?.toString() ?: ""
                val data = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                when (algo) {
                    "md5" -> PluginCrypto.md5(data)
                    "sha1" -> PluginCrypto.sha1(data)
                    "sha256" -> PluginCrypto.sha256(data)
                    else -> throw IllegalArgumentException("Unsupported hash: $algo")
                }
            }
            function("cryptoHmac") { args ->
                requireToken(expectedToken, args)
                val algo = args.getOrNull(1)?.toString() ?: "sha256"
                val key = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                val data = PluginCrypto.base64Decode(args.getOrNull(3)?.toString() ?: "")
                PluginCrypto.hmac(algo, key, data)
            }
            function("cryptoAes") { args ->
                requireToken(expectedToken, args)
                val encrypt = args.getOrNull(1) as? Boolean ?: true
                val mode = args.getOrNull(2)?.toString() ?: "GCM"
                val key = PluginCrypto.base64Decode(args.getOrNull(3)?.toString() ?: "")
                val iv = PluginCrypto.base64Decode(args.getOrNull(4)?.toString() ?: "")
                val data = PluginCrypto.base64Decode(args.getOrNull(5)?.toString() ?: "")
                val aad = args.getOrNull(6)?.toString()?.takeIf { it.isNotEmpty() }?.let(PluginCrypto::base64Decode)
                val out = if (encrypt) {
                    PluginCrypto.aesEncrypt(mode, key, iv, data, aad)
                } else {
                    PluginCrypto.aesDecrypt(mode, key, iv, data, aad)
                }
                PluginCrypto.base64Encode(out)
            }
            function("cryptoRsaEncrypt") { args ->
                requireToken(expectedToken, args)
                val pem = args.getOrNull(1)?.toString() ?: ""
                val data = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                val padding = args.getOrNull(3)?.toString()?.ifBlank { "PKCS1" } ?: "PKCS1"
                PluginCrypto.base64Encode(PluginCrypto.rsaEncrypt(pem, data, padding))
            }
            function("cryptoRandomBytes") { args ->
                requireToken(expectedToken, args)
                val n = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                PluginCrypto.base64Encode(PluginCrypto.randomBytes(n))
            }
            function("cryptoBase64Encode") { args ->
                requireToken(expectedToken, args)
                val data = PluginCrypto.base64Decode(args.getOrNull(1)?.toString() ?: "")
                val urlSafe = args.getOrNull(2) as? Boolean ?: false
                PluginCrypto.base64Encode(data, urlSafe)
            }
            function("cryptoBase64Decode") { args ->
                requireToken(expectedToken, args)
                val str = args.getOrNull(1)?.toString() ?: ""
                PluginCrypto.base64Encode(PluginCrypto.base64Decode(str))
            }
            function("cryptoHexEncode") { args ->
                requireToken(expectedToken, args)
                val data = PluginCrypto.base64Decode(args.getOrNull(1)?.toString() ?: "")
                PluginCrypto.hexEncode(data)
            }
            function("cryptoHexDecode") { args ->
                requireToken(expectedToken, args)
                val hex = args.getOrNull(1)?.toString() ?: ""
                PluginCrypto.base64Encode(PluginCrypto.hexDecode(hex))
            }

            // §11.1 / S4: lazy handle-based ctx.parse
            function("parseHtml") { args ->
                requireToken(expectedToken, args)
                domParser.parseHtml(args.getOrNull(1)?.toString() ?: "")
            }
            function("parseXml") { args ->
                requireToken(expectedToken, args)
                domParser.parseXml(args.getOrNull(1)?.toString() ?: "")
            }
            function("domSelectFirstId") { args ->
                requireToken(expectedToken, args)
                val id = (args.getOrNull(1) as? Number)?.toInt() ?: return@function 0
                val sel = args.getOrNull(2)?.toString() ?: return@function 0
                domParser.selectFirstId(id, sel)
            }
            function("domSelectAllIds") { args ->
                requireToken(expectedToken, args)
                val id = (args.getOrNull(1) as? Number)?.toInt() ?: return@function "[]"
                val sel = args.getOrNull(2)?.toString() ?: return@function "[]"
                domParser.selectAllIds(id, sel).toString()
            }
            function("domChildrenIds") { args ->
                requireToken(expectedToken, args)
                val id = (args.getOrNull(1) as? Number)?.toInt() ?: return@function "[]"
                domParser.childrenIds(id).toString()
            }
            function("domTag") { args ->
                requireToken(expectedToken, args)
                domParser.tag((args.getOrNull(1) as? Number)?.toInt() ?: 0)
            }
            function("domText") { args ->
                requireToken(expectedToken, args)
                domParser.text((args.getOrNull(1) as? Number)?.toInt() ?: 0)
            }
            function("domHtml") { args ->
                requireToken(expectedToken, args)
                domParser.html((args.getOrNull(1) as? Number)?.toInt() ?: 0)
            }
            function("domOuterHtml") { args ->
                requireToken(expectedToken, args)
                domParser.outerHtml((args.getOrNull(1) as? Number)?.toInt() ?: 0)
            }
            function("domAttr") { args ->
                requireToken(expectedToken, args)
                val id = (args.getOrNull(1) as? Number)?.toInt() ?: return@function null
                val name = args.getOrNull(2)?.toString() ?: return@function null
                domParser.attr(id, name)
            }
            function("domAttrs") { args ->
                requireToken(expectedToken, args)
                domParser.attrsJson((args.getOrNull(1) as? Number)?.toInt() ?: 0)
            }

            // ctx.log (truncate to 4 KB BEFORE regex redaction to protect host CPU & heap)
            function("log") { args ->
                requireToken(expectedToken, args)
                val levelName = args.getOrNull(1)?.toString()?.uppercase(Locale.ROOT) ?: "INFO"
                val rawMsg = args.getOrNull(2)?.toString() ?: ""
                val msg = PluginLogger.truncateUtf8(rawMsg, PluginLogger.MAX_MESSAGE_BYTES)
                val level = PluginLogLevel.entries.firstOrNull { it.name == levelName } ?: PluginLogLevel.INFO
                logger.log(level, msg)
                Unit
            }

            // ctx.t
            function("translate") { args ->
                requireToken(expectedToken, args)
                val key = args.getOrNull(1)?.toString() ?: ""
                val varsJson = args.getOrNull(2)?.toString()
                val vars = if (!varsJson.isNullOrEmpty() && varsJson != "null") {
                    val obj = json.parseToJsonElement(varsJson) as? JsonObject
                    obj?.mapValues { (_, v) ->
                        (v as? JsonPrimitive)?.content ?: v.toString()
                    } ?: emptyMap()
                } else {
                    emptyMap()
                }
                locale.translate(key, vars)
            }
        }
    }

    companion object {
        /** §11.1: QuickJS heap limit is 64 MB. */
        const val DEFAULT_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
        const val MAX_STACK_BYTES = 512L * 1024
        const val MAX_RETURN_BYTES = 8 * 1024 * 1024
        const val MAX_ACTIVE_TIMERS = 256
        const val DEFAULT_CALL_TIMEOUT_MS = 15_000L
        const val PLAYBACK_CALL_TIMEOUT_MS = 30_000L
        private const val DISPOSE_TIMEOUT_MS = 2_000L
        private const val SHUTDOWN_WAIT_MS = 2_000L

        private val SHARED_BOOTSTRAP_JS: String by lazy {
            JsonSchemaValidator.loadBundledResourceText("runtime-bootstrap.js")
        }
    }
}
