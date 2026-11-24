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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.Executors
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
 * Isolated per-instance QuickJS runtime hosting one plugin instance (protocol §4.4, §5, §6, §7).
 *
 * Guarantees:
 * - One dedicated QuickJS runtime, storage, cookie jar, and network policy per instance
 * - Memory cap 32 MB (`memoryLimit`), stack cap 512 KB (`maxStackSize`)
 * - Call timeout 15 s default, 30 s for `playback`, mapped to `Unavailable`
 * - Automatic destroy + clean rebuild (re-running `init(ctx)`) after timeout or OOM
 * - Scoped `setTimeout` strictly within a single call; cancelled when the call ends
 * - Clean resource shutdown on [close] / [dispose] (no leaked threads or runtimes)
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
    private val deviceInfo: DeviceInfo = DeviceInfo(),
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
    private var initialized = false
    @Volatile
    private var closed = false
    @Volatile
    private var activeCallScope: CoroutineScope? = null
    private val callGeneration = AtomicInteger(0)

    /** Count of how many times the underlying QuickJS runtime was created (useful for testing recovery). */
    @Volatile
    var creationCount: Int = 0
        private set

    /**
     * Invokes an exported method on the plugin's default export object.
     * Returns the parsed [JsonElement] (or [JsonNull] for void/undefined returns).
     */
    suspend fun invoke(
        method: String,
        arg: JsonElement? = null,
        itemIdForErrors: String? = null,
        timeoutMsOverride: Long? = null,
    ): JsonElement = callMutex.withLock {
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
        domParser.clear()

        val qjs = quickJs ?: throw PluginErrorMapper.toException(
            PluginErrorPayload(PluginErrorCode.INTERNAL),
            itemId = itemIdForErrors,
            context = androidContext,
        )
        qjs.evaluationTimeoutMillis = timeoutMs

        val argJson = arg?.toString() ?: "null"
        val hasArg = arg != null
        val invokeScript = "await __d3_invoke(${JsonPrimitive(method)}, ${JsonPrimitive(argJson)}, $hasArg, $gen)"

        try {
            val rawResult = withContext(dispatcher) {
                withTimeout(timeoutMs) {
                    qjs.evaluate<String?>(invokeScript, filename = "__d3_call.js")
                }
            }
            if (rawResult.isNullOrEmpty() || rawResult == "undefined") {
                JsonNull
            } else {
                json.parseToJsonElement(rawResult)
            }
        } catch (t: Throwable) {
            handleInvocationFailureLocked(qjs, method, t, itemIdForErrors)
        } finally {
            callGeneration.incrementAndGet()
            activeCallScope = null
            callScope.cancel()
            domParser.clear()
        }
    }

    /**
     * Calls optional `dispose(ctx)` best-effort and destroys the QuickJS runtime.
     * The [PluginRuntime] can still be re-used later unless [close] is called.
     */
    suspend fun reset() = callMutex.withLock {
        destroyRuntimeLocked(runDispose = true)
    }

    /**
     * Best-effort `dispose(ctx)` followed by permanent shutdown of the QuickJS
     * runtime and its dedicated worker thread.
     */
    suspend fun shutdown() = callMutex.withLock {
        if (closed) return@withLock
        closed = true
        destroyRuntimeLocked(runDispose = true)
        executor.shutdownNow()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            quickJs?.interruptEvaluation()
        } catch (_: Throwable) {
        }
        try {
            quickJs?.close()
        } catch (_: Throwable) {
        }
        quickJs = null
        initialized = false
        activeCallScope?.cancel()
        activeCallScope = null
        domParser.clear()
        executor.shutdownNow()
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
        creationCount += 1

        try {
            bindHostBridge(qjs)
            withContext(dispatcher) {
                withTimeout(timeoutMs) {
                    qjs.evaluate<Unit>(BOOTSTRAP_JS, filename = "__d3_bootstrap.js")
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
            // Run optional init(ctx) inside its own scoped invocation
            val gen = callGeneration.incrementAndGet()
            val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            activeCallScope = initScope
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
                callGeneration.incrementAndGet()
                activeCallScope = null
                initScope.cancel()
                domParser.clear()
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
                    callGeneration.incrementAndGet()
                    activeCallScope = null
                    scope.cancel()
                    domParser.clear()
                }
            } catch (_: Throwable) {
                // Best-effort dispose; always proceed to free native runtime
            }
        }
        try {
            existing.interruptEvaluation()
        } catch (_: Throwable) {
        }
        try {
            existing.close()
        } catch (_: Throwable) {
        }
        quickJs = null
        initialized = false
    }

    private suspend fun handleInvocationFailureLocked(
        qjs: QuickJs?,
        method: String,
        t: Throwable,
        itemId: String?,
    ): Nothing {
        // 1. Timeout or interrupted -> destroy & rebuild next time, map to Unavailable (§7)
        if (t is TimeoutCancellationException || t is QuickJsInterruptedException ||
            (t is QuickJsException && t.message?.contains("interrupted", ignoreCase = true) == true)
        ) {
            logger.warn("Method '$method' timed out; destroying QuickJS runtime for rebuild")
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

        // 2. Structured ctx.Error thrown by plugin (§6)
        val parsed = PluginErrorMapper.parseWireError(t.message)
        if (parsed != null) {
            throw PluginErrorMapper.toException(
                parsed,
                itemId = itemId,
                context = androidContext,
                cause = t,
            )
        }

        // 3. Out of memory -> destroy immediately & rebuild on next call (§7)
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

        // 4. All other JS/host errors -> Internal (§6)
        logger.error("Unhandled error in '$method': ${t.message ?: t.javaClass.simpleName}")
        throw PluginErrorMapper.toException(
            PluginErrorPayload(PluginErrorCode.INTERNAL),
            itemId = itemId,
            context = androidContext,
            cause = t,
        )
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

    private fun bindHostBridge(qjs: QuickJs) {
        val configJson = JsonObject(config).toString()
        val deviceJson = deviceInfo.toJsonObject().toString()

        qjs.define("__d3_host") {
            function("getConfigJson") { configJson }
            function("getInstanceId") { instanceId }
            function("getDeviceJson") { deviceJson }
            function("getErrorMarker") { PluginErrorMapper.WIRE_MARKER }

            // Scoped timer support for setTimeout (single-call only)
            asyncFunction("sleep") { args ->
                val ms = (args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0L, 60_000L) ?: 0L
                val gen = (args.getOrNull(1) as? Number)?.toInt() ?: -1
                if (gen != callGeneration.get()) return@asyncFunction false
                val scope = activeCallScope ?: return@asyncFunction false
                withContext(scope.coroutineContext) {
                    if (ms > 0) delay(ms)
                }
                gen == callGeneration.get()
            }

            // ctx.fetch
            asyncFunction("fetch") { args ->
                val reqJson = args.getOrNull(0)?.toString()
                    ?: throw IllegalArgumentException("Missing fetch request")
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
                buildJsonObject {
                    put("status", resp.status)
                    put("url", resp.url)
                    put(
                        "headers",
                        buildJsonObject {
                            resp.headers.forEach { (k, v) -> put(k, v) }
                        },
                    )
                    put("bodyBase64", PluginCrypto.base64Encode(resp.body))
                }.toString()
            }

            // ctx.storage
            function("storageGet") { args ->
                val key = args.getOrNull(0)?.toString() ?: return@function null
                storage.get(key)?.toString()
            }
            function("storageSet") { args ->
                val key = args.getOrNull(0)?.toString()
                    ?: throw IllegalArgumentException("storage.set requires key")
                val valJson = args.getOrNull(1)?.toString() ?: "null"
                val parsed = json.parseToJsonElement(valJson)
                storage.set(key, parsed)
                Unit
            }
            function("storageRemove") { args ->
                val key = args.getOrNull(0)?.toString() ?: return@function Unit
                storage.remove(key)
                Unit
            }
            function("storageKeys") {
                buildJsonArray {
                    storage.keys().forEach { add(JsonPrimitive(it)) }
                }.toString()
            }

            // ctx.cookies
            function("cookiesGet") { args ->
                val url = args.getOrNull(0)?.toString()
                    ?: throw IllegalArgumentException("cookies.get requires url")
                cookieJar.getHeaderForUrl(url)
            }
            function("cookiesSet") { args ->
                val url = args.getOrNull(0)?.toString()
                    ?: throw IllegalArgumentException("cookies.set requires url")
                val cookie = args.getOrNull(1)?.toString()
                    ?: throw IllegalArgumentException("cookies.set requires cookie")
                cookieJar.setCookie(url, cookie)
                Unit
            }
            function("cookiesClear") {
                cookieJar.clear()
                Unit
            }

            // ctx.crypto
            function("cryptoHash") { args ->
                val algo = args.getOrNull(0)?.toString() ?: ""
                val dataB64 = args.getOrNull(1)?.toString() ?: ""
                val data = PluginCrypto.base64Decode(dataB64)
                when (algo) {
                    "md5" -> PluginCrypto.md5(data)
                    "sha1" -> PluginCrypto.sha1(data)
                    "sha256" -> PluginCrypto.sha256(data)
                    else -> throw IllegalArgumentException("Unsupported hash: $algo")
                }
            }
            function("cryptoHmac") { args ->
                val algo = args.getOrNull(0)?.toString() ?: "sha256"
                val key = PluginCrypto.base64Decode(args.getOrNull(1)?.toString() ?: "")
                val data = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                PluginCrypto.hmac(algo, key, data)
            }
            function("cryptoAes") { args ->
                val encrypt = args.getOrNull(0) as? Boolean ?: true
                val mode = args.getOrNull(1)?.toString() ?: "GCM"
                val key = PluginCrypto.base64Decode(args.getOrNull(2)?.toString() ?: "")
                val iv = PluginCrypto.base64Decode(args.getOrNull(3)?.toString() ?: "")
                val data = PluginCrypto.base64Decode(args.getOrNull(4)?.toString() ?: "")
                val aadB64 = args.getOrNull(5)?.toString()?.takeIf { it.isNotEmpty() }
                val aad = aadB64?.let(PluginCrypto::base64Decode)
                val out = if (encrypt) {
                    PluginCrypto.aesEncrypt(mode, key, iv, data, aad)
                } else {
                    PluginCrypto.aesDecrypt(mode, key, iv, data, aad)
                }
                PluginCrypto.base64Encode(out)
            }
            function("cryptoRsaEncrypt") { args ->
                val pem = args.getOrNull(0)?.toString() ?: ""
                val data = PluginCrypto.base64Decode(args.getOrNull(1)?.toString() ?: "")
                val padding = args.getOrNull(2)?.toString()?.ifBlank { "PKCS1" } ?: "PKCS1"
                PluginCrypto.base64Encode(PluginCrypto.rsaEncrypt(pem, data, padding))
            }
            function("cryptoRandomBytes") { args ->
                val n = (args.getOrNull(0) as? Number)?.toInt() ?: 0
                PluginCrypto.base64Encode(PluginCrypto.randomBytes(n))
            }
            function("cryptoBase64Encode") { args ->
                val data = PluginCrypto.base64Decode(args.getOrNull(0)?.toString() ?: "")
                val urlSafe = args.getOrNull(1) as? Boolean ?: false
                PluginCrypto.base64Encode(data, urlSafe)
            }
            function("cryptoBase64Decode") { args ->
                val str = args.getOrNull(0)?.toString() ?: ""
                PluginCrypto.base64Encode(PluginCrypto.base64Decode(str))
            }
            function("cryptoHexEncode") { args ->
                val data = PluginCrypto.base64Decode(args.getOrNull(0)?.toString() ?: "")
                PluginCrypto.hexEncode(data)
            }
            function("cryptoHexDecode") { args ->
                val hex = args.getOrNull(0)?.toString() ?: ""
                PluginCrypto.base64Encode(PluginCrypto.hexDecode(hex))
            }

            // ctx.parse
            function("parseHtml") { args ->
                val text = args.getOrNull(0)?.toString() ?: ""
                val id = domParser.parseHtml(text)
                domParser.nodeInfo(id).toString()
            }
            function("parseXml") { args ->
                val text = args.getOrNull(0)?.toString() ?: ""
                val id = domParser.parseXml(text)
                domParser.nodeInfo(id).toString()
            }
            function("domSelectFirst") { args ->
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@function null
                val sel = args.getOrNull(1)?.toString() ?: return@function null
                domParser.selectFirst(id, sel)?.toString()
            }
            function("domSelectAll") { args ->
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@function "[]"
                val sel = args.getOrNull(1)?.toString() ?: return@function "[]"
                domParser.selectAll(id, sel).toString()
            }
            function("domChildren") { args ->
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@function "[]"
                domParser.children(id).toString()
            }

            // ctx.log
            function("log") { args ->
                val levelName = args.getOrNull(0)?.toString()?.uppercase(Locale.ROOT) ?: "INFO"
                val msg = args.getOrNull(1)?.toString() ?: ""
                val level = PluginLogLevel.entries.firstOrNull { it.name == levelName } ?: PluginLogLevel.INFO
                logger.log(level, msg)
                Unit
            }

            // ctx.t
            function("translate") { args ->
                val key = args.getOrNull(0)?.toString() ?: ""
                val varsJson = args.getOrNull(1)?.toString()
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
        const val DEFAULT_MEMORY_LIMIT_BYTES = 32L * 1024 * 1024
        const val MAX_STACK_BYTES = 512L * 1024
        const val DEFAULT_CALL_TIMEOUT_MS = 15_000L
        const val PLAYBACK_CALL_TIMEOUT_MS = 30_000L
        private const val DISPOSE_TIMEOUT_MS = 2_000L

        /**
         * Pure-JS sandbox bootstrap executed before loading `plugin/main.js`.
         * Captures and hides `__d3_host`, defines `ctx` per protocol §5,
         * installs single-call `setTimeout`/`clearTimeout`, and registers
         * standard UTF-8 `TextEncoder`/`TextDecoder` + `URLSearchParams`.
         */
        private val BOOTSTRAP_JS = """
            (function() {
              const host = globalThis.__d3_host;
              delete globalThis.__d3_host;
              const ERR_MARKER = host.getErrorMarker();

              function deepFreeze(obj) {
                if (obj === null || typeof obj !== 'object') return obj;
                Object.freeze(obj);
                for (const key of Object.keys(obj)) {
                  deepFreeze(obj[key]);
                }
                return obj;
              }

              // UTF-8 helpers (no external dependencies)
              function utf8Encode(str) {
                const s = String(str);
                const bytes = [];
                for (let i = 0; i < s.length; i++) {
                  let cp = s.charCodeAt(i);
                  if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < s.length) {
                    const lo = s.charCodeAt(++i);
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                  }
                  if (cp < 0x80) {
                    bytes.push(cp);
                  } else if (cp < 0x800) {
                    bytes.push(0xC0 | (cp >> 6), 0x80 | (cp & 0x3F));
                  } else if (cp < 0x10000) {
                    bytes.push(0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
                  } else {
                    bytes.push(
                      0xF0 | (cp >> 18),
                      0x80 | ((cp >> 12) & 0x3F),
                      0x80 | ((cp >> 6) & 0x3F),
                      0x80 | (cp & 0x3F)
                    );
                  }
                }
                return new Uint8Array(bytes);
              }

              function utf8Decode(bytes) {
                const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
                let out = '';
                let i = 0;
                while (i < u8.length) {
                  const b0 = u8[i++];
                  if (b0 < 0x80) {
                    out += String.fromCharCode(b0);
                  } else if ((b0 & 0xE0) === 0xC0 && i < u8.length) {
                    const b1 = u8[i++];
                    out += String.fromCharCode(((b0 & 0x1F) << 6) | (b1 & 0x3F));
                  } else if ((b0 & 0xF0) === 0xE0 && i + 1 < u8.length) {
                    const b1 = u8[i++];
                    const b2 = u8[i++];
                    out += String.fromCharCode(((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F));
                  } else if ((b0 & 0xF8) === 0xF0 && i + 2 < u8.length) {
                    const b1 = u8[i++];
                    const b2 = u8[i++];
                    const b3 = u8[i++];
                    const cp = (((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)) - 0x10000;
                    out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
                  } else {
                    out += '\uFFFD';
                  }
                }
                return out;
              }

              const B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
              const B64_LOOKUP = new Int16Array(128).fill(-1);
              for (let i = 0; i < B64_CHARS.length; i++) B64_LOOKUP[B64_CHARS.charCodeAt(i)] = i;
              B64_LOOKUP['-'.charCodeAt(0)] = 62;
              B64_LOOKUP['_'.charCodeAt(0)] = 63;

              function bytesToBase64(u8) {
                let out = '';
                for (let i = 0; i < u8.length; i += 3) {
                  const a = u8[i];
                  const b = i + 1 < u8.length ? u8[i + 1] : 0;
                  const c = i + 2 < u8.length ? u8[i + 2] : 0;
                  const n = (a << 16) | (b << 8) | c;
                  out += B64_CHARS[(n >> 18) & 63];
                  out += B64_CHARS[(n >> 12) & 63];
                  out += i + 1 < u8.length ? B64_CHARS[(n >> 6) & 63] : '=';
                  out += i + 2 < u8.length ? B64_CHARS[n & 63] : '=';
                }
                return out;
              }

              function base64ToBytes(str) {
                const s = String(str).replace(/[\r\n\s=]+/g, '');
                const out = new Uint8Array(Math.floor(s.length * 3 / 4));
                let p = 0;
                for (let i = 0; i < s.length; i += 4) {
                  const a = B64_LOOKUP[s.charCodeAt(i)] ?? 0;
                  const b = B64_LOOKUP[s.charCodeAt(i + 1)] ?? 0;
                  const c = i + 2 < s.length ? (B64_LOOKUP[s.charCodeAt(i + 2)] ?? 0) : 0;
                  const d = i + 3 < s.length ? (B64_LOOKUP[s.charCodeAt(i + 3)] ?? 0) : 0;
                  out[p++] = (a << 2) | (b >> 4);
                  if (i + 2 < s.length) out[p++] = ((b & 15) << 4) | (c >> 2);
                  if (i + 3 < s.length) out[p++] = ((c & 3) << 6) | d;
                }
                return out.subarray(0, p);
              }

              function toInputBase64(input) {
                if (input instanceof Uint8Array) return bytesToBase64(input);
                if (ArrayBuffer.isView(input)) {
                  return bytesToBase64(new Uint8Array(input.buffer, input.byteOffset, input.byteLength));
                }
                if (input instanceof ArrayBuffer) return bytesToBase64(new Uint8Array(input));
                return bytesToBase64(utf8Encode(input ?? ''));
              }

              if (typeof globalThis.TextEncoder === 'undefined') {
                globalThis.TextEncoder = class TextEncoder {
                  encode(s) { return utf8Encode(s); }
                };
              }
              if (typeof globalThis.TextDecoder === 'undefined') {
                globalThis.TextDecoder = class TextDecoder {
                  decode(b) { return utf8Decode(b); }
                };
              }
              if (typeof globalThis.URLSearchParams === 'undefined') {
                globalThis.URLSearchParams = class URLSearchParams {
                  constructor(init) {
                    this._pairs = [];
                    if (typeof init === 'string') {
                      const q = init.startsWith('?') ? init.slice(1) : init;
                      if (q) {
                        for (const part of q.split('&')) {
                          if (!part) continue;
                          const idx = part.indexOf('=');
                          const k = decodeURIComponent((idx >= 0 ? part.slice(0, idx) : part).replace(/\+/g, ' '));
                          const v = decodeURIComponent((idx >= 0 ? part.slice(idx + 1) : '').replace(/\+/g, ' '));
                          this._pairs.push([k, v]);
                        }
                      }
                    } else if (init && typeof init === 'object') {
                      for (const [k, v] of Object.entries(init)) {
                        if (v !== undefined && v !== null) this._pairs.push([String(k), String(v)]);
                      }
                    }
                  }
                  append(k, v) { this._pairs.push([String(k), String(v)]); }
                  set(k, v) {
                    const ks = String(k);
                    this._pairs = this._pairs.filter(([key]) => key !== ks);
                    this._pairs.push([ks, String(v)]);
                  }
                  get(k) {
                    const found = this._pairs.find(([key]) => key === String(k));
                    return found ? found[1] : null;
                  }
                  delete(k) { this._pairs = this._pairs.filter(([key]) => key !== String(k)); }
                  toString() {
                    return this._pairs
                      .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
                      .join('&');
                  }
                };
              }

              // Single-call scoped setTimeout / clearTimeout (§5)
              let currentGen = 0;
              let nextTimerId = 1;
              const cancelledTimers = new Set();

              globalThis.setTimeout = function(fn, delayMs, ...args) {
                if (typeof fn !== 'function') throw new TypeError('setTimeout requires a function');
                const id = nextTimerId++;
                const gen = currentGen;
                const ms = Number(delayMs) || 0;
                host.sleep(ms, gen).then((stillActive) => {
                  if (stillActive && gen === currentGen && !cancelledTimers.has(id)) {
                    cancelledTimers.delete(id);
                    fn(...args);
                  }
                }).catch(() => {});
                return id;
              };
              globalThis.clearTimeout = function(id) {
                cancelledTimers.add(id);
              };

              // DOM wrapper for ctx.parse
              function wrapElement(info) {
                if (!info) return null;
                const el = {
                  tag: info.tag,
                  text: info.text,
                  html: info.html,
                  outerHtml: info.outerHtml,
                  attrs: deepFreeze(Object.assign({}, info.attrs || {})),
                  attr(name) {
                    const v = info.attrs ? info.attrs[String(name)] : undefined;
                    return v === undefined ? null : v;
                  },
                  querySelector(selector) {
                    const raw = host.domSelectFirst(info.id, String(selector));
                    return raw ? wrapElement(JSON.parse(raw)) : null;
                  },
                  selectFirst(selector) { return el.querySelector(selector); },
                  querySelectorAll(selector) {
                    const raw = host.domSelectAll(info.id, String(selector));
                    return JSON.parse(raw).map(wrapElement);
                  },
                  select(selector) { return el.querySelectorAll(selector); },
                  get children() {
                    const raw = host.domChildren(info.id);
                    return JSON.parse(raw).map(wrapElement);
                  },
                };
                return Object.freeze(el);
              }

              // Protocol §6 ctx.Error
              class PluginError extends Error {
                constructor(code, message, options) {
                  super(message === undefined || message === null ? '' : String(message));
                  this.name = 'PluginError';
                  this.code = String(code || 'Internal');
                  this.__d3Error = true;
                  if (options && typeof options === 'object' && options.retryAfterSec !== undefined) {
                    this.retryAfterSec = Number(options.retryAfterSec);
                  }
                }
              }

              function formatLogArgs(args) {
                return args.map((a) => {
                  if (typeof a === 'string') return a;
                  try { return JSON.stringify(a); } catch (_) { return String(a); }
                }).join(' ');
              }

              function normalizeCryptoKeyOrIv(val) {
                if (typeof val === 'string') {
                  return toInputBase64(val);
                }
                return toInputBase64(val);
              }

              const ctx = Object.freeze({
                Error: PluginError,
                config: deepFreeze(JSON.parse(host.getConfigJson())),
                instanceId: host.getInstanceId(),
                device: deepFreeze(JSON.parse(host.getDeviceJson())),

                async fetch(url, opts) {
                  const o = opts || {};
                  const req = {
                    url: String(url),
                    method: o.method ? String(o.method) : 'GET',
                    headers: {},
                    redirect: o.redirect ? String(o.redirect) : 'follow',
                  };
                  if (o.timeoutMs !== undefined && o.timeoutMs !== null) {
                    req.timeoutMs = Number(o.timeoutMs);
                  }
                  if (o.headers && typeof o.headers === 'object') {
                    for (const [k, v] of Object.entries(o.headers)) {
                      if (v !== undefined && v !== null) req.headers[String(k)] = String(v);
                    }
                  }
                  if (o.body !== undefined && o.body !== null) {
                    if (o.body instanceof Uint8Array || ArrayBuffer.isView(o.body) || o.body instanceof ArrayBuffer) {
                      req.bodyBase64 = toInputBase64(o.body);
                    } else if (typeof o.body === 'string') {
                      req.bodyText = o.body;
                    } else {
                      req.bodyText = JSON.stringify(o.body);
                      if (!Object.keys(req.headers).some((k) => k.toLowerCase() === 'content-type')) {
                        req.contentType = 'application/json; charset=utf-8';
                      }
                    }
                  }
                  const rawResp = await host.fetch(JSON.stringify(req));
                  const parsed = JSON.parse(rawResp);
                  let cachedBytes = null;
                  let cachedText = null;
                  const headers = deepFreeze(parsed.headers || {});
                  const resp = {
                    status: parsed.status,
                    ok: parsed.status >= 200 && parsed.status < 300,
                    url: parsed.url,
                    headers,
                    bytes() {
                      if (cachedBytes === null) cachedBytes = base64ToBytes(parsed.bodyBase64 || '');
                      return new Uint8Array(cachedBytes);
                    },
                    text() {
                      if (cachedText === null) cachedText = utf8Decode(resp.bytes());
                      return cachedText;
                    },
                    json() {
                      return JSON.parse(resp.text());
                    },
                  };
                  return Object.freeze(resp);
                },

                storage: Object.freeze({
                  get(key) {
                    const raw = host.storageGet(String(key));
                    return raw === null || raw === undefined ? null : JSON.parse(raw);
                  },
                  set(key, value) {
                    host.storageSet(String(key), JSON.stringify(value === undefined ? null : value));
                  },
                  remove(key) {
                    host.storageRemove(String(key));
                  },
                  keys() {
                    return JSON.parse(host.storageKeys());
                  },
                }),

                cookies: Object.freeze({
                  get(url) { return host.cookiesGet(String(url)); },
                  set(url, cookie) { host.cookiesSet(String(url), String(cookie)); },
                  clear() { host.cookiesClear(); },
                }),

                crypto: Object.freeze({
                  md5(data) { return host.cryptoHash('md5', toInputBase64(data)); },
                  sha1(data) { return host.cryptoHash('sha1', toInputBase64(data)); },
                  sha256(data) { return host.cryptoHash('sha256', toInputBase64(data)); },
                  hmac(algo, key, data) {
                    return host.cryptoHmac(String(algo), toInputBase64(key), toInputBase64(data));
                  },
                  aes: Object.freeze({
                    encrypt(modeOrOpts, key, iv, data, aad) {
                      if (modeOrOpts && typeof modeOrOpts === 'object') {
                        const o = modeOrOpts;
                        const b64 = host.cryptoAes(
                          true,
                          String(o.mode || 'GCM'),
                          normalizeCryptoKeyOrIv(o.key),
                          normalizeCryptoKeyOrIv(o.iv),
                          toInputBase64(o.data),
                          o.aad !== undefined ? toInputBase64(o.aad) : ''
                        );
                        return base64ToBytes(b64);
                      }
                      const b64 = host.cryptoAes(
                        true,
                        String(modeOrOpts),
                        normalizeCryptoKeyOrIv(key),
                        normalizeCryptoKeyOrIv(iv),
                        toInputBase64(data),
                        aad !== undefined ? toInputBase64(aad) : ''
                      );
                      return base64ToBytes(b64);
                    },
                    decrypt(modeOrOpts, key, iv, data, aad) {
                      if (modeOrOpts && typeof modeOrOpts === 'object') {
                        const o = modeOrOpts;
                        const b64 = host.cryptoAes(
                          false,
                          String(o.mode || 'GCM'),
                          normalizeCryptoKeyOrIv(o.key),
                          normalizeCryptoKeyOrIv(o.iv),
                          toInputBase64(o.data),
                          o.aad !== undefined ? toInputBase64(o.aad) : ''
                        );
                        return base64ToBytes(b64);
                      }
                      const b64 = host.cryptoAes(
                        false,
                        String(modeOrOpts),
                        normalizeCryptoKeyOrIv(key),
                        normalizeCryptoKeyOrIv(iv),
                        toInputBase64(data),
                        aad !== undefined ? toInputBase64(aad) : ''
                      );
                      return base64ToBytes(b64);
                    },
                  }),
                  rsa: Object.freeze({
                    encrypt(publicKeyPem, data, padding) {
                      if (publicKeyPem && typeof publicKeyPem === 'object') {
                        const o = publicKeyPem;
                        return base64ToBytes(
                          host.cryptoRsaEncrypt(String(o.publicKey), toInputBase64(o.data), String(o.padding || 'PKCS1'))
                        );
                      }
                      return base64ToBytes(
                        host.cryptoRsaEncrypt(String(publicKeyPem), toInputBase64(data), String(padding || 'PKCS1'))
                      );
                    },
                  }),
                  randomBytes(n) {
                    return base64ToBytes(host.cryptoRandomBytes(Number(n)));
                  },
                  base64: Object.freeze({
                    encode(data, urlSafe) {
                      return host.cryptoBase64Encode(toInputBase64(data), Boolean(urlSafe));
                    },
                    decode(str) {
                      return base64ToBytes(host.cryptoBase64Decode(String(str)));
                    },
                  }),
                  hex: Object.freeze({
                    encode(data) {
                      return host.cryptoHexEncode(toInputBase64(data));
                    },
                    decode(str) {
                      return base64ToBytes(host.cryptoHexDecode(String(str)));
                    },
                  }),
                }),

                parse: Object.freeze({
                  html(text) { return wrapElement(JSON.parse(host.parseHtml(String(text ?? '')))); },
                  xml(text) { return wrapElement(JSON.parse(host.parseXml(String(text ?? '')))); },
                }),

                log: Object.freeze({
                  debug(...args) { host.log('DEBUG', formatLogArgs(args)); },
                  info(...args) { host.log('INFO', formatLogArgs(args)); },
                  warn(...args) { host.log('WARN', formatLogArgs(args)); },
                  error(...args) { host.log('ERROR', formatLogArgs(args)); },
                }),

                t(key, vars) {
                  return host.translate(String(key), vars ? JSON.stringify(vars) : 'null');
                },
              });

              let pluginExport = null;

              globalThis.__d3_register = function(mod) {
                if (!mod || typeof mod !== 'object') {
                  throw new Error('Plugin main.js must export a default object');
                }
                pluginExport = mod;
              };

              async function callPlugin(method, argJson, hasArg, gen, optional) {
                currentGen = gen;
                cancelledTimers.clear();
                if (!pluginExport) throw new Error('Plugin not loaded');
                const fn = pluginExport[method];
                if (typeof fn !== 'function') {
                  if (optional) return 'undefined';
                  throw new Error('Plugin does not implement method: ' + method);
                }
                try {
                  const res = hasArg ? await fn.call(pluginExport, ctx, JSON.parse(argJson)) : await fn.call(pluginExport, ctx);
                  return res === undefined ? 'undefined' : JSON.stringify(res);
                } catch (err) {
                  if (err && err.__d3Error) {
                    const payload = { code: err.code || 'Internal' };
                    if (err.message) payload.message = String(err.message);
                    if (typeof err.retryAfterSec === 'number' && Number.isFinite(err.retryAfterSec)) {
                      payload.retryAfterSec = Math.round(err.retryAfterSec);
                    }
                    throw new Error(ERR_MARKER + JSON.stringify(payload));
                  }
                  throw err;
                }
              }

              globalThis.__d3_invoke = function(method, argJson, hasArg, gen) {
                return callPlugin(method, argJson, hasArg, gen, false);
              };
              globalThis.__d3_invoke_optional = function(method, argJson, hasArg, gen) {
                return callPlugin(method, argJson, hasArg, gen, true);
              };
            })();
        """.trimIndent()
    }
}
