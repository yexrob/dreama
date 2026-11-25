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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.IOException
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
    internal val logger: PluginLogger,
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

    @Volatile
    private var quickJs: QuickJs? = null
    private var bridgeToken: String = ""
    @Volatile
    private var initialized = false
    @Volatile
    private var cachedExportedMethods: Set<String>? = null
    @Volatile
    private var closed = false
    @Volatile
    private var activeCallScope: CoroutineScope? = null
    @Volatile
    private var storageCorruptedInCall: Boolean = false
    @Volatile
    private var domTimedOutInCall: Boolean = false
    @Volatile
    private var hostOomInCall: Boolean = false
    @Volatile
    private var callDeadlineNanos: Long = Long.MAX_VALUE
    @Volatile
    internal var bridgeFaultInjectorForTesting: ((String) -> Unit)? = null
    private val callGeneration = AtomicInteger(0)

    private class ActiveTimerHandle(
        val job: Job,
        private val releaseOnce: () -> Unit,
    ) {
        fun cancelAndRelease() {
            releaseOnce()
            job.cancel()
        }
    }

    // Per-call live timers and buffered fetch response bodies (all released on settle/timeout)
    private val activeTimers = ConcurrentHashMap<Int, ActiveTimerHandle>()
    private val activeTimerCount = AtomicInteger(0)
    private val responseHandleSeq = AtomicInteger(1)
    private val responseBodies = ConcurrentHashMap<Int, ByteArray>()

    internal fun currentActiveTimerCount(): Int = activeTimerCount.get()

    /**
     * Collects live in-memory secret values from this runtime's cookie jar and `ctx.storage`
     * (covering values written in-flight before disk flush) for log & diagnostic redaction.
     */
    internal fun collectLiveSecretValues(): List<String> {
        val out = LinkedHashSet<String>()
        out += cookieJar.allCookieValues()
        out += storage.allSecretLikeValues()
        return out.toList()
    }

    internal fun tryReserveTimerSlot(): Boolean {
        while (true) {
            val cur = activeTimerCount.get()
            if (cur >= MAX_ACTIVE_TIMERS) return false
            if (activeTimerCount.compareAndSet(cur, cur + 1)) return true
        }
    }

    internal fun releaseTimerSlot() {
        while (true) {
            val cur = activeTimerCount.get()
            if (cur <= 0) return
            if (activeTimerCount.compareAndSet(cur, cur - 1)) return
        }
    }

    /** Count of how many times the underlying QuickJS runtime was created. */
    @Volatile
    var creationCount: Int = 0
        private set

    /** True when the QuickJS VM is already warm and initialized (no cold-start `init(ctx)` required). */
    val isInitialized: Boolean
        get() = initialized && quickJs != null && !closed

    /**
     * Lock-free hint returning `true` once the plugin module has been loaded and confirmed
     * NOT to export [method]. Returns `false` if the runtime is not yet initialized or
     * [method] is exported.
     */
    fun isMethodKnownMissing(method: String): Boolean =
        cachedExportedMethods?.let { method !in it } == true

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
        invokeLocked(method, arg, itemIdForErrors, timeoutMsOverride, optional = false) ?: JsonNull
    }

    /**
     * Invokes an optional exported method (e.g. `suggest`). If the plugin does not export
     * [method], returns `null` silently without logging an error or throwing.
     */
    suspend fun invokeOptional(
        method: String,
        arg: JsonElement? = null,
        itemIdForErrors: String? = null,
        timeoutMsOverride: Long? = null,
    ): JsonElement? {
        if (isMethodKnownMissing(method)) return null
        return callMutex.withLock {
            if (isMethodKnownMissing(method)) return@withLock null
            invokeLocked(method, arg, itemIdForErrors, timeoutMsOverride, optional = true)
        }
    }

    /**
     * Best-effort optional invocation for latency-sensitive background calls such as `suggest`:
     * - Returns `null` immediately when the VM is not yet initialized (never triggers cold-start
     *   bootstrap or `init(ctx)`).
     * - Returns `null` immediately when another call (`home`, `search`, `detail`, `playback`, ...)
     *   is running on this instance (never queues behind or cancels active calls).
     * - Returns `null` silently if [method] is not exported.
     */
    suspend fun invokeOptionalIfReadyAndIdle(
        method: String,
        arg: JsonElement? = null,
        itemIdForErrors: String? = null,
        timeoutMsOverride: Long? = null,
    ): JsonElement? {
        if (closed || !initialized || quickJs == null || isMethodKnownMissing(method)) return null
        if (!callMutex.tryLock()) return null
        return try {
            if (closed || !initialized || quickJs == null || isMethodKnownMissing(method)) {
                null
            } else {
                invokeLocked(method, arg, itemIdForErrors, timeoutMsOverride, optional = true)
            }
        } finally {
            callMutex.unlock()
        }
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
            invokeLocked(method, arg, itemIdForErrors, timeoutMsOverride, optional = false)
            return true
        } finally {
            callMutex.unlock()
        }
    }

    private class InternalCallTimeoutException(cause: Throwable) : Exception(cause)

    private fun extractSensitiveQueryTerms(method: String, arg: JsonElement?): List<String> {
        if (method != "search" && method != "suggest") return emptyList()
        val query = ((arg as? JsonObject)?.get("query") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            .orEmpty()
        return if (query.isEmpty()) emptyList() else listOf(query)
    }

    private fun isVmDamagedError(t: Throwable): Boolean =
        t is QuickJsInterruptedException ||
            hasCause<QuickJsInterruptedException>(t) ||
            domTimedOutInCall ||
            hasCause<DomTimeoutException>(t) ||
            hostOomInCall ||
            isOutOfMemoryError(t) ||
            (t is QuickJsException && t.message?.contains("InternalError: interrupted", ignoreCase = true) == true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun invokeLocked(
        method: String,
        arg: JsonElement?,
        itemIdForErrors: String?,
        timeoutMsOverride: Long?,
        optional: Boolean,
    ): JsonElement? {
        if (closed) {
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                itemId = itemIdForErrors,
                context = androidContext,
            )
        }
        val timeoutMs = timeoutMsOverride
            ?: if (method == "playback") playbackTimeoutMs else defaultTimeoutMs

        val queryRedactions = extractSensitiveQueryTerms(method, arg)
        if (queryRedactions.isNotEmpty()) {
            logger.setActiveQueryRedactions(queryRedactions)
        }
        return try {
            ensureInitializedLocked(timeoutMs)
            if (optional && isMethodKnownMissing(method)) {
                return null
            }

            val gen = callGeneration.incrementAndGet()
            val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val evalScope = CoroutineScope(SupervisorJob() + dispatcher)
            activeCallScope = callScope
            storageCorruptedInCall = false
            domTimedOutInCall = false
            hostOomInCall = false
            domParser.clear()
            releaseAllResponseBodies()

            val qjs = quickJs ?: throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                itemId = itemIdForErrors,
                context = androidContext,
            )
            qjs.evaluationTimeoutMillis = timeoutMs
            val dl = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            callDeadlineNanos = dl
            domParser.setCallDeadlineNanos(dl)

            val argJson = arg?.toString() ?: "null"
            val hasArg = arg != null
            val invokeFn = if (optional) "__d3_invoke_optional" else "__d3_invoke"
            val invokeScript = "await $invokeFn(${JsonPrimitive(method)}, ${JsonPrimitive(argJson)}, $hasArg, $gen)"

            val evalDeferred = evalScope.async {
                qjs.evaluate<String?>(invokeScript, filename = "__d3_call.js")
            }

            try {
                val rawResult = try {
                    withTimeout(timeoutMs) {
                        evalDeferred.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    if (currentCoroutineContext().isActive) {
                        withContext(NonCancellable) {
                            cleanupActiveCall(callScope)
                            try {
                                qjs.interruptEvaluation()
                            } catch (_: Throwable) {
                            }
                            evalDeferred.cancel()
                            withTimeoutOrNull(INTERRUPT_JOIN_WAIT_MS) { evalDeferred.join() }
                        }
                        throw InternalCallTimeoutException(e)
                    } else {
                        throw e
                    }
                }
                if (rawResult.isNullOrEmpty() || rawResult == "undefined") {
                    if (optional) null else JsonNull
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
            } catch (ce: CancellationException) {
                // Cooperative caller cancellation (e.g. suggest 800 ms timeout, next keystroke,
                // new query, leaving screen): cancel in-flight IO/timers first so awaiting JS
                // unwinds cleanly without tearing down the QuickJS VM. ONLY interrupt and destroy
                // the VM if JS refuses to settle (CPU loop / hung promise) or was damaged.
                withContext(NonCancellable) {
                    cleanupActiveCall(callScope)
                    val settledGracefully = withTimeoutOrNull(GRACEFUL_CANCEL_WAIT_MS) {
                        evalDeferred.join()
                        true
                    } == true
                    val evalError = if (settledGracefully && evalDeferred.isCompleted && !evalDeferred.isCancelled) {
                        runCatching { evalDeferred.getCompletionExceptionOrNull() }.getOrNull()
                    } else {
                        null
                    }
                    val mustRebuildVm = !settledGracefully || (evalError != null && isVmDamagedError(evalError))
                    if (mustRebuildVm) {
                        try {
                            qjs.interruptEvaluation()
                        } catch (_: Throwable) {
                        }
                        evalDeferred.cancel()
                        withTimeoutOrNull(INTERRUPT_JOIN_WAIT_MS) { evalDeferred.join() }
                        destroyRuntimeLocked(runDispose = false)
                    }
                }
                throw ce
            } catch (t: Throwable) {
                handleInvocationFailureLocked(qjs, method, t, itemIdForErrors)
            } finally {
                cleanupActiveCall(callScope)
                evalScope.cancel()
                runCatching { cookieJar.flush() }
            }
        } finally {
            if (queryRedactions.isNotEmpty()) {
                logger.clearActiveQueryRedactions()
            }
        }
    }

    /**
     * Ensures `main.js` is compiled, registered, and optional `init(ctx)` has run.
     * Used by developer-mode reload verification to surface syntax or `init` failures immediately.
     */
    suspend fun verifyInitialized(timeoutMsOverride: Long? = null) = callMutex.withLock {
        if (closed) {
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                context = androidContext,
            )
        }
        ensureInitializedLocked(timeoutMsOverride ?: defaultTimeoutMs)
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
        domParser.clear()
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
        val dl = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        callDeadlineNanos = dl
        domParser.setCallDeadlineNanos(dl)
        bridgeToken = PluginCrypto.hexEncode(PluginCrypto.randomBytes(16))
        creationCount += 1

        val evalScope = CoroutineScope(SupervisorJob() + dispatcher)
        var pendingEval: Deferred<*>? = null
        try {
            bindHostBridge(qjs, bridgeToken)
            val bootDeferred = evalScope.async {
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
                val exportsJson = qjs.evaluate<String>("__d3_exports()", filename = "__d3_exports.js")
                val parsedExports = runCatching {
                    (json.parseToJsonElement(exportsJson) as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?.toSet()
                }.getOrNull()
                if (parsedExports != null) {
                    cachedExportedMethods = parsedExports
                }
            }
            pendingEval = bootDeferred
            try {
                withTimeout(timeoutMs) { bootDeferred.await() }
            } catch (e: TimeoutCancellationException) {
                if (currentCoroutineContext().isActive) {
                    throw InternalCallTimeoutException(e)
                } else {
                    throw e
                }
            }
            pendingEval = null
            quickJs = qjs
            // Run optional init(ctx) inside its own scoped invocation (§11.1: failure == call failure)
            val gen = callGeneration.incrementAndGet()
            val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            activeCallScope = initScope
            storageCorruptedInCall = false
            domTimedOutInCall = false
            hostOomInCall = false
            try {
                val initDeferred = evalScope.async {
                    qjs.evaluate<String?>(
                        "await __d3_invoke_optional('init', 'null', false, $gen)",
                        filename = "__d3_init.js",
                    )
                }
                pendingEval = initDeferred
                try {
                    withTimeout(timeoutMs) { initDeferred.await() }
                } catch (e: TimeoutCancellationException) {
                    if (currentCoroutineContext().isActive) {
                        throw InternalCallTimeoutException(e)
                    } else {
                        throw e
                    }
                }
                pendingEval = null
            } finally {
                cleanupActiveCall(initScope)
                runCatching { cookieJar.flush() }
            }
            initialized = true
        } catch (ce: CancellationException) {
            // Caller cancelled during init: silently release the half-initialized VM without
            // logging an error and rethrow cooperative CancellationException directly.
            withContext(NonCancellable) {
                cancelAllCallResources()
                try {
                    qjs.interruptEvaluation()
                } catch (_: Throwable) {
                }
                pendingEval?.cancel()
                withTimeoutOrNull(INTERRUPT_JOIN_WAIT_MS) { pendingEval?.join() }
                evalScope.cancel()
                try {
                    withContext(dispatcher) { qjs.close() }
                } catch (_: Throwable) {
                    runCatching { qjs.close() }
                }
                quickJs = null
                initialized = false
            }
            throw ce
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                cancelAllCallResources()
                try {
                    qjs.interruptEvaluation()
                } catch (_: Throwable) {
                }
                pendingEval?.cancel()
                withTimeoutOrNull(INTERRUPT_JOIN_WAIT_MS) { pendingEval?.join() }
                evalScope.cancel()
                try {
                    withContext(dispatcher) { qjs.close() }
                } catch (_: Throwable) {
                    runCatching { qjs.close() }
                }
                quickJs = null
                initialized = false
            }
            handleInvocationFailureLocked(null, "init", t, null)
        } finally {
            evalScope.cancel()
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
        callDeadlineNanos = Long.MAX_VALUE
        cancelAllTimers()
        fetchGateway.cancelAllActive()
        scope.cancel()
        domParser.clear()
        releaseAllResponseBodies()
    }

    private fun cancelAllCallResources() {
        callGeneration.incrementAndGet()
        callDeadlineNanos = Long.MAX_VALUE
        cancelAllTimers()
        fetchGateway.cancelAllActive()
        activeCallScope?.cancel()
        activeCallScope = null
        domParser.clear()
        releaseAllResponseBodies()
    }

    private fun cancelAllTimers() {
        val iter = activeTimers.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            iter.remove()
            entry.value.cancelAndRelease()
        }
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
        if (t is InternalCallTimeoutException || t is TimeoutCancellationException || t is QuickJsInterruptedException ||
            domTimedOutInCall || hasCause<DomTimeoutException>(t) ||
            (t is QuickJsException && t.message?.contains("InternalError: interrupted", ignoreCase = true) == true)
        ) {
            domTimedOutInCall = false
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
        if (hostOomInCall || isOutOfMemoryError(t)) {
            hostOomInCall = false
            logger.error("QuickJS runtime or host bridge hit memory limit during '$method'; destroying runtime for rebuild")
            destroyRuntimeLocked(runDispose = false)
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                itemId = itemId,
                context = androidContext,
                cause = t,
            )
        }

        // 5. StackOverflowError (never allowed to escape host/bridge) -> Internal (§6)
        if (hasCause<StackOverflowError>(t)) {
            logger.error("StackOverflowError trapped during '$method'")
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                itemId = itemId,
                context = androidContext,
                cause = t,
            )
        }

        // 6. All other JS/host errors -> Internal (§6)
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

    private class HostBridgeRuntimeException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    private inline fun <T> guardBridgeSync(opName: String, block: () -> T): T {
        return try {
            if (opName != "finishCall" && opName != "cancelTimer" && opName != "domCloseDoc") {
                val dl = callDeadlineNanos
                if (dl != Long.MAX_VALUE && System.nanoTime() > dl) {
                    domTimedOutInCall = true
                    throw DomTimeoutException("宿主调用已超时（$opName）")
                }
            }
            bridgeFaultInjectorForTesting?.invoke(opName)
            block()
        } catch (e: DomTimeoutException) {
            domTimedOutInCall = true
            throw e
        } catch (e: StackOverflowError) {
            logger.error("Host bridge '$opName' trapped StackOverflowError")
            throw HostBridgeRuntimeException("宿主函数调用栈溢出（$opName）", e)
        } catch (e: OutOfMemoryError) {
            hostOomInCall = true
            logger.error("Host bridge '$opName' trapped OutOfMemoryError")
            throw HostBridgeRuntimeException("宿主函数内存不足（$opName）", e)
        }
    }

    private suspend inline fun <T> guardBridgeAsync(opName: String, block: suspend () -> T): T {
        return try {
            val dl = callDeadlineNanos
            if (dl != Long.MAX_VALUE && System.nanoTime() > dl) {
                domTimedOutInCall = true
                throw DomTimeoutException("宿主调用已超时（$opName）")
            }
            bridgeFaultInjectorForTesting?.invoke(opName)
            block()
        } catch (e: DomTimeoutException) {
            domTimedOutInCall = true
            throw e
        } catch (e: StackOverflowError) {
            logger.error("Host bridge '$opName' trapped StackOverflowError")
            throw HostBridgeRuntimeException("宿主函数调用栈溢出（$opName）", e)
        } catch (e: OutOfMemoryError) {
            hostOomInCall = true
            logger.error("Host bridge '$opName' trapped OutOfMemoryError")
            throw HostBridgeRuntimeException("宿主函数内存不足（$opName）", e)
        }
    }

    private fun bindHostBridge(qjs: QuickJs, expectedToken: String) {
        val configJson = JsonObject(config).toString()
        val deviceJson = deviceInfo.toJsonObject().toString()

        qjs.define("__d3_host") {
            fun safeFunction(name: String, block: (Array<Any?>) -> Any?) {
                function(name) { args ->
                    guardBridgeSync(name) {
                        requireToken(expectedToken, args)
                        block(args)
                    }
                }
            }

            fun safeAsyncFunction(name: String, block: suspend (Array<Any?>) -> Any?) {
                asyncFunction(name) { args ->
                    guardBridgeAsync(name) {
                        requireToken(expectedToken, args)
                        block(args)
                    }
                }
            }

            safeFunction("getConfigJson") { configJson }
            safeFunction("getInstanceId") { instanceId }
            safeFunction("getDeviceJson") { deviceJson }
            safeFunction("getErrorMarker") { PluginErrorMapper.WIRE_MARKER }
            safeFunction("isStrictAwaitTracking") { false }
            safeFunction("reportUnawaitedOps") { Unit }

            // §11.1 / S3: true cancellable timers & immediate settle cleanup (atomic 256 active timer cap)
            safeAsyncFunction("sleep") { args ->
                val timerId = (args.getOrNull(1) as? Number)?.toInt() ?: return@safeAsyncFunction false
                val ms = (args.getOrNull(2) as? Number)?.toLong()?.coerceIn(0L, 60_000L) ?: 0L
                val gen = (args.getOrNull(3) as? Number)?.toInt() ?: -1
                if (gen != callGeneration.get()) return@safeAsyncFunction false
                val scope = activeCallScope ?: return@safeAsyncFunction false
                if (!tryReserveTimerSlot()) {
                    throw IllegalStateException("同时活跃的定时器数量超过上限（最多 $MAX_ACTIVE_TIMERS 个）")
                }

                val slotReleased = java.util.concurrent.atomic.AtomicBoolean(false)
                val releaseOnce = {
                    if (slotReleased.compareAndSet(false, true)) {
                        releaseTimerSlot()
                    }
                }
                val done = CompletableDeferred<Boolean>()
                val job = try {
                    scope.launch {
                        try {
                            if (ms > 0) delay(ms)
                            done.complete(gen == callGeneration.get())
                        } catch (_: CancellationException) {
                            done.complete(false)
                        } finally {
                            activeTimers.remove(timerId)
                            releaseOnce()
                        }
                    }
                } catch (t: Throwable) {
                    releaseOnce()
                    throw t
                }
                val handle = ActiveTimerHandle(job, releaseOnce)
                activeTimers.put(timerId, handle)?.cancelAndRelease()
                if (gen != callGeneration.get()) {
                    activeTimers.remove(timerId, handle)
                    handle.cancelAndRelease()
                    return@safeAsyncFunction false
                }
                done.await()
            }

            safeFunction("cancelTimer") { args ->
                val timerId = (args.getOrNull(1) as? Number)?.toInt() ?: return@safeFunction Unit
                activeTimers.remove(timerId)?.cancelAndRelease()
                Unit
            }

            safeFunction("finishCall") { args ->
                val gen = (args.getOrNull(1) as? Number)?.toInt() ?: -1
                if (gen == callGeneration.get()) {
                    cancelAllTimers()
                    fetchGateway.cancelAllActive()
                }
                Unit
            }

            // §11.1 / B2: binary fetch bridge with pre-parse envelope/body/header/URL caps
            // and atomic 16-request queue cap BEFORE decoding request body bytes.
            safeAsyncFunction("fetchMeta") { args ->
                val reqJson = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("Missing fetch request")
                val gen = (args.getOrNull(2) as? Number)?.toInt() ?: -1
                if (gen != callGeneration.get()) {
                    throw IOException("Call already finished")
                }
                if (reqJson.length > PluginFetchGateway.MAX_FETCH_META_JSON_BYTES ||
                    reqJson.toByteArray(Charsets.UTF_8).size > PluginFetchGateway.MAX_FETCH_META_JSON_BYTES
                ) {
                    throw FetchRequestTooLargeException("请求内容超过大小限制（请求体上限 2 MB）")
                }
                val obj = json.parseToJsonElement(reqJson) as JsonObject
                val url = (obj["url"] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("Missing fetch url")
                val method = (obj["method"] as? JsonPrimitive)?.content ?: "GET"
                val headersObj = obj["headers"] as? JsonObject
                val headers = headersObj?.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.content ?: v.toString()
                } ?: emptyMap()
                fetchGateway.validateRequestMetadata(url, headers)

                val bodyBase64 = (obj["bodyBase64"] as? JsonPrimitive)?.content
                val bodyText = (obj["bodyText"] as? JsonPrimitive)?.content
                if (bodyBase64 != null) {
                    val approxBytes = (bodyBase64.length.toLong() * 3L) / 4L
                    if (approxBytes > PluginFetchGateway.MAX_REQUEST_BODY_BYTES + 3L) {
                        throw FetchRequestTooLargeException("请求体超过大小限制（上限 2 MB）")
                    }
                } else if (bodyText != null) {
                    if (bodyText.length > PluginFetchGateway.MAX_REQUEST_BODY_BYTES ||
                        bodyText.toByteArray(Charsets.UTF_8).size > PluginFetchGateway.MAX_REQUEST_BODY_BYTES
                    ) {
                        throw FetchRequestTooLargeException("请求体超过大小限制（上限 2 MB）")
                    }
                }
                val contentType = (obj["contentType"] as? JsonPrimitive)?.content
                val timeoutMs = (obj["timeoutMs"] as? JsonPrimitive)?.longOrNull
                val redirect = (obj["redirect"] as? JsonPrimitive)?.content ?: "follow"

                // Cap total queued + active request body memory across concurrent calls at 8 MB
                val reservedReqBodyBytes = when {
                    bodyBase64 != null -> bodyBase64.length.toLong()
                    bodyText != null -> bodyText.toByteArray(Charsets.UTF_8).size.toLong()
                    else -> 0L
                }
                if (reservedReqBodyBytes > 0L) {
                    try {
                        fetchGateway.requestBodyBudget.reserve(reservedReqBodyBytes)
                    } catch (_: FetchResponseTooLargeException) {
                        throw FetchRequestTooLargeException(
                            "排队及在途请求体总量超过上限（${PluginFetchGateway.MAX_QUEUED_REQUEST_BODY_BYTES / (1024 * 1024)} MB）",
                        )
                    }
                }
                val resp = try {
                    fetchGateway.withSlot {
                        if (gen != callGeneration.get()) {
                            throw IOException("Call already finished")
                        }
                        // Decode request body ONLY while holding one of the 4 active execution permits
                        val bodyBytes = when {
                            bodyBase64 != null -> PluginCrypto.base64Decode(bodyBase64)
                            bodyText != null -> bodyText.toByteArray(Charsets.UTF_8)
                            else -> null
                        }
                        fetchGateway.validateRequestBodyBytes(bodyBytes)
                        withContext(Dispatchers.IO) {
                            fetchGateway.executeBlocking(
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
                        }
                    }
                } finally {
                    if (reservedReqBodyBytes > 0L) {
                        fetchGateway.requestBodyBudget.release(reservedReqBodyBytes)
                    }
                }
                // Reserve buffer bytes against the 16 MB in-flight budget until JS reads or call ends
                if (gen != callGeneration.get()) {
                    throw IOException("Call already finished")
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

            safeFunction("fetchReadBytes") { args ->
                val handleId = (args.getOrNull(1) as? Number)?.toInt() ?: return@safeFunction ByteArray(0)
                val bytes = responseBodies.remove(handleId) ?: return@safeFunction ByteArray(0)
                fetchGateway.inFlightBudget.release(bytes.size.toLong())
                bytes
            }

            safeFunction("fetchReadText") { args ->
                val handleId = (args.getOrNull(1) as? Number)?.toInt() ?: return@safeFunction ""
                val bytes = responseBodies.remove(handleId) ?: return@safeFunction ""
                fetchGateway.inFlightBudget.release(bytes.size.toLong())
                String(bytes, Charsets.UTF_8)
            }

            // §11.1: async ctx.storage.*
            safeAsyncFunction("storageGet") { args ->
                val key = args.getOrNull(1)?.toString() ?: return@safeAsyncFunction null
                guardStorage { withContext(Dispatchers.IO) { storage.get(key)?.toString() } }
            }
            safeAsyncFunction("storageSet") { args ->
                val key = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("storage.set requires key")
                val valJson = args.getOrNull(2)?.toString() ?: "null"
                // Check UTF-8 byte quota BEFORE parsing JSON to protect host heap
                guardStorage { withContext(Dispatchers.IO) { storage.setRawJson(key, valJson) } }
                Unit
            }
            safeAsyncFunction("storageRemove") { args ->
                val key = args.getOrNull(1)?.toString() ?: return@safeAsyncFunction Unit
                guardStorage { withContext(Dispatchers.IO) { storage.remove(key) } }
                Unit
            }
            safeAsyncFunction("storageKeys") {
                guardStorage {
                    withContext(Dispatchers.IO) {
                        buildJsonArray {
                            storage.keys().forEach { add(JsonPrimitive(it)) }
                        }.toString()
                    }
                }
            }

            // §11.1: async ctx.cookies.*
            safeAsyncFunction("cookiesGet") { args ->
                val url = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("cookies.get requires url")
                guardStorage { withContext(Dispatchers.IO) { cookieJar.getHeaderForUrl(url) } }
            }
            safeAsyncFunction("cookiesSet") { args ->
                val url = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("cookies.set requires url")
                val cookie = args.getOrNull(2)?.toString()
                    ?: throw IllegalArgumentException("cookies.set requires cookie")
                guardStorage { withContext(Dispatchers.IO) { cookieJar.setCookie(url, cookie) } }
                Unit
            }
            safeAsyncFunction("cookiesClear") {
                withContext(Dispatchers.IO) { cookieJar.clear() }
                Unit
            }

            // ctx.crypto
            safeFunction("cryptoHash") { args ->
                val algo = args.getOrNull(1)?.toString() ?: ""
                val data = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                when (algo) {
                    "md5" -> PluginCrypto.md5(data)
                    "sha1" -> PluginCrypto.sha1(data)
                    "sha256" -> PluginCrypto.sha256(data)
                    else -> throw IllegalArgumentException("Unsupported hash: $algo")
                }
            }
            safeFunction("cryptoHmac") { args ->
                val algo = args.getOrNull(1)?.toString() ?: "sha256"
                val key = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                val data = PluginCrypto.base64Decode(args.getOrNull(3)?.toString() ?: "")
                PluginCrypto.hmac(algo, key, data)
            }
            safeFunction("cryptoAes") { args ->
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
            safeFunction("cryptoRsaEncrypt") { args ->
                val pem = args.getOrNull(1)?.toString() ?: ""
                val data = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                val padding = args.getOrNull(3)?.toString()?.ifBlank { "PKCS1" } ?: "PKCS1"
                PluginCrypto.base64Encode(PluginCrypto.rsaEncrypt(pem, data, padding))
            }
            safeFunction("cryptoRandomBytes") { args ->
                val n = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                PluginCrypto.base64Encode(PluginCrypto.randomBytes(n))
            }
            safeFunction("cryptoBase64Encode") { args ->
                val data = PluginCrypto.base64Decode(args.getOrNull(1)?.toString() ?: "")
                val urlSafe = args.getOrNull(2) as? Boolean ?: false
                PluginCrypto.base64Encode(data, urlSafe)
            }
            safeFunction("cryptoBase64Decode") { args ->
                val str = args.getOrNull(1)?.toString() ?: ""
                PluginCrypto.base64Encode(PluginCrypto.base64Decode(str))
            }
            safeFunction("cryptoHexEncode") { args ->
                val data = PluginCrypto.base64Decode(args.getOrNull(1)?.toString() ?: "")
                PluginCrypto.hexEncode(data)
            }
            safeFunction("cryptoHexDecode") { args ->
                val hex = args.getOrNull(1)?.toString() ?: ""
                PluginCrypto.base64Encode(PluginCrypto.hexDecode(hex))
            }

            // §11.1 / S4: lazy handle-based ctx.parse (deduplicated monotonic Long handles + doc.close())
            safeFunction("parseHtml") { args ->
                domParser.parseHtml(args.getOrNull(1)?.toString() ?: "").toDouble()
            }
            safeFunction("parseXml") { args ->
                domParser.parseXml(args.getOrNull(1)?.toString() ?: "").toDouble()
            }
            safeFunction("domCloseDoc") { args ->
                val docId = (args.getOrNull(1) as? Number)?.toLong() ?: return@safeFunction false
                domParser.closeDoc(docId)
            }
            safeFunction("domSelectFirstId") { args ->
                val id = (args.getOrNull(1) as? Number)?.toLong() ?: return@safeFunction 0.0
                val sel = args.getOrNull(2)?.toString() ?: return@safeFunction 0.0
                domParser.selectFirstId(id, sel).toDouble()
            }
            safeFunction("domSelectAllIds") { args ->
                val id = (args.getOrNull(1) as? Number)?.toLong() ?: return@safeFunction "[]"
                val sel = args.getOrNull(2)?.toString() ?: return@safeFunction "[]"
                domParser.selectAllIds(id, sel).toString()
            }
            safeFunction("domChildrenIds") { args ->
                val id = (args.getOrNull(1) as? Number)?.toLong() ?: return@safeFunction "[]"
                domParser.childrenIds(id).toString()
            }
            safeFunction("domTag") { args ->
                domParser.tag((args.getOrNull(1) as? Number)?.toLong() ?: 0L)
            }
            safeFunction("domText") { args ->
                domParser.text((args.getOrNull(1) as? Number)?.toLong() ?: 0L)
            }
            safeFunction("domHtml") { args ->
                domParser.html((args.getOrNull(1) as? Number)?.toLong() ?: 0L)
            }
            safeFunction("domOuterHtml") { args ->
                domParser.outerHtml((args.getOrNull(1) as? Number)?.toLong() ?: 0L)
            }
            safeFunction("domAttr") { args ->
                val id = (args.getOrNull(1) as? Number)?.toLong() ?: return@safeFunction null
                val name = args.getOrNull(2)?.toString() ?: return@safeFunction null
                domParser.attr(id, name)
            }
            safeFunction("domAttrs") { args ->
                domParser.attrsJson((args.getOrNull(1) as? Number)?.toLong() ?: 0L)
            }

            // ctx.log (truncate to 4 KB BEFORE regex redaction to protect host CPU & heap)
            safeFunction("log") { args ->
                val levelName = args.getOrNull(1)?.toString()?.uppercase(Locale.ROOT) ?: "INFO"
                val rawMsg = args.getOrNull(2)?.toString() ?: ""
                val msg = PluginLogger.truncateUtf8(rawMsg, PluginLogger.MAX_MESSAGE_BYTES)
                val level = PluginLogLevel.entries.firstOrNull { it.name == levelName } ?: PluginLogLevel.INFO
                logger.log(level, msg)
                Unit
            }

            // ctx.t
            safeFunction("translate") { args ->
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
        private const val GRACEFUL_CANCEL_WAIT_MS = 250L
        private const val INTERRUPT_JOIN_WAIT_MS = 500L

        private val SHARED_BOOTSTRAP_JS: String by lazy {
            JsonSchemaValidator.loadBundledResourceText("runtime-bootstrap.js")
        }

        /**
         * Evaluates ES module syntax, top-level module statements, and default export shape
         * of [mainJsSource] in a **zero-capability** QuickJS sandbox:
         * - NO host bridge bindings (`__d3_host` is absent)
         * - NO `ctx`, NO network gateway, NO storage, NO cookies, NO filesystem access
         *
         * Because ES module evaluation executes top-level module code, callers MUST only invoke
         * this AFTER explicit user confirmation (or during a same-origin, non-permission-expanded
         * developer reload) and NEVER during unconfirmed package preview.
         */
        internal suspend fun probeModuleLoadNoCapabilities(
            mainJsSource: String,
            memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT_BYTES,
            timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
        ) {
            val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "depth3d-qjs-probe").apply { isDaemon = true }
            }
            val probeDispatcher = probeExecutor.asCoroutineDispatcher()
            val loader = moduleLoader {
                load { name ->
                    when (name) {
                        "plugin/main.js", "./main.js", "main.js" -> ModuleContent.Source(mainJsSource)
                        else -> null
                    }
                }
            }
            val qjs = QuickJs.create(jobDispatcher = probeDispatcher, moduleLoader = loader)
            qjs.memoryLimit = memoryLimitBytes
            qjs.maxStackSize = MAX_STACK_BYTES
            qjs.evaluationTimeoutMillis = timeoutMs
            try {
                withContext(probeDispatcher) {
                    withTimeout(timeoutMs) {
                        qjs.evaluate<Unit>(
                            """
                            globalThis.__d3_probe_register = function(mod) {
                              if (!mod || typeof mod !== 'object') {
                                throw new TypeError('Plugin module must export a default object');
                              }
                            };
                            undefined;
                            """.trimIndent(),
                            filename = "__d3_probe.js",
                        )
                        qjs.evaluate<Unit>(
                            """
                            import __plugin from 'plugin/main.js';
                            globalThis.__d3_probe_register(__plugin);
                            """.trimIndent(),
                            filename = "__d3_entry.js",
                            asModule = true,
                        )
                    }
                }
            } finally {
                try { qjs.interruptEvaluation() } catch (_: Throwable) {}
                try {
                    withContext(probeDispatcher) { qjs.close() }
                } catch (_: Throwable) {
                    runCatching { qjs.close() }
                }
                probeExecutor.shutdown()
                try {
                    probeExecutor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                probeExecutor.shutdownNow()
            }
        }
    }
}
