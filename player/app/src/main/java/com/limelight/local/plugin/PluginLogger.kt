package com.limelight.local.plugin

import com.limelight.LimeLog
import java.util.ArrayDeque

enum class PluginLogLevel { DEBUG, INFO, WARN, ERROR }

data class PluginLogEntry(
    val timestampMs: Long,
    val level: PluginLogLevel,
    val pluginId: String,
    val instanceId: String,
    val message: String,
)

/**
 * Plugin logger backing `ctx.log.{debug,info,warn,error}` (protocol §5 &
 * quality bar):
 * - Unified prefix `depth3d: plugin <pluginId>`
 * - Truncates every message to [MAX_MESSAGE_BYTES] (4 KB) BEFORE regex redaction
 *   so hostile plugins cannot trigger multi-megabyte regex scans or OOM the host
 * - Redacts configured secret values (password config fields) and common
 *   credential/token patterns before emitting to logcat/LimeLog or the ring buffer
 * - Caps the in-memory ring buffer by BOTH entry count ([maxEntries] = 200) AND
 *   total UTF-8 byte size ([maxBufferBytes] = 256 KB)
 */
class PluginLogger(
    private val pluginId: String,
    private val instanceId: String,
    private val knownSecretsProvider: () -> Collection<String> = { emptyList() },
    private val sink: (PluginLogLevel, String) -> Unit = { level, line ->
        when (level) {
            PluginLogLevel.DEBUG, PluginLogLevel.INFO -> LimeLog.info(line)
            PluginLogLevel.WARN -> LimeLog.warning(line)
            PluginLogLevel.ERROR -> LimeLog.severe(line)
        }
    },
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxMessageBytes: Int = MAX_MESSAGE_BYTES,
    private val maxBufferBytes: Int = MAX_BUFFER_BYTES,
) {
    private data class StoredEntry(
        val entry: PluginLogEntry,
        val byteSize: Int,
    )

    private val ring = ArrayDeque<StoredEntry>(maxEntries)
    private var totalBufferedBytes: Int = 0
    private val lock = Any()

    fun log(level: PluginLogLevel, rawMessage: String) {
        val truncated = truncateUtf8(rawMessage, maxMessageBytes)
        val scrubbed = redact(truncated, knownSecretsProvider())
        val finalMsg = truncateUtf8(scrubbed, maxMessageBytes)
        val msgBytes = finalMsg.toByteArray(Charsets.UTF_8).size
        val formatted = "depth3d: plugin $pluginId [$instanceId] ${level.name}: $finalMsg"
        synchronized(lock) {
            while (ring.size >= maxEntries && ring.isNotEmpty()) {
                totalBufferedBytes -= ring.removeFirst().byteSize
            }
            while (ring.isNotEmpty() && totalBufferedBytes + msgBytes > maxBufferBytes) {
                totalBufferedBytes -= ring.removeFirst().byteSize
            }
            val entry = PluginLogEntry(
                timestampMs = System.currentTimeMillis(),
                level = level,
                pluginId = pluginId,
                instanceId = instanceId,
                message = finalMsg,
            )
            ring.addLast(StoredEntry(entry, msgBytes))
            totalBufferedBytes += msgBytes
        }
        sink(level, formatted)
    }

    fun debug(msg: String) = log(PluginLogLevel.DEBUG, msg)
    fun info(msg: String) = log(PluginLogLevel.INFO, msg)
    fun warn(msg: String) = log(PluginLogLevel.WARN, msg)
    fun error(msg: String) = log(PluginLogLevel.ERROR, msg)

    fun recentEntries(): List<PluginLogEntry> = synchronized(lock) { ring.map { it.entry } }
    fun currentBufferedBytes(): Int = synchronized(lock) { totalBufferedBytes }

    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_MESSAGE_BYTES = 4 * 1024
        const val MAX_BUFFER_BYTES = 256 * 1024
        private const val TRUNCATION_SUFFIX = "...[truncated]"
        private const val REDACTED = "[REDACTED]"

        private val AUTH_HEADER_REGEX = Regex(
            """(?i)\b(authorization\s*[:=]\s*(?:bearer|basic|mediabrowser|emby|token)\s+)([^\r\n;,"']+)""",
        )
        private val COOKIE_HEADER_REGEX = Regex(
            """(?i)\b((?:set-)?cookie\s*[:=]\s*)([^\r\n]+)""",
        )
        private val JSON_SECRET_REGEX = Regex(
            """(?i)("?(?:password|passwd|pwd|secret|token|access_?token|refresh_?token|api_?key|x-emby-token|x-mediabrowser-token|sessdata|bili_jct|pin)"?\s*[:=]\s*["']?)(?!\[REDACTED\])([^"',\s&;}\]]+)""",
        )
        private val URL_USERINFO_REGEX = Regex(
            """(https?://)([^/\s:@]+:[^/\s@]+)(@)""",
        )
        private val URL_QUERY_SECRET_REGEX = Regex(
            """(?i)([?&](?:api_?key|token|access_?token|refresh_?token|auth|password|passwd|pwd|secret|key|sig|signature|session(?:_?id)?|jwt|x-emby-token|x-mediabrowser-token|sessdata|bili_jct|cookie)=)(?!\[REDACTED\])([^&#\s"'<>]+)""",
        )

        fun truncateUtf8(input: String, maxBytes: Int): String {
            if (maxBytes <= 0) return ""
            // Fast path: UTF-8 takes at most 3 bytes per BMP char (4 bytes per surrogate pair)
            if (input.length <= maxBytes / 4) return input
            val suffixBytes = TRUNCATION_SUFFIX.length
            val limit = (maxBytes - suffixBytes).coerceAtLeast(0)
            var bytes = 0
            var cutIdx = -1
            var i = 0
            val len = input.length
            while (i < len) {
                val cp = Character.codePointAt(input, i)
                val charCount = Character.charCount(cp)
                val cpBytes = when {
                    cp <= 0x7F -> 1
                    cp <= 0x7FF -> 2
                    cp <= 0xFFFF -> 3
                    else -> 4
                }
                if (bytes + cpBytes > maxBytes) {
                    return if (cutIdx >= 0) input.substring(0, cutIdx) + TRUNCATION_SUFFIX else input.substring(0, i)
                }
                bytes += cpBytes
                i += charCount
                if (bytes <= limit) cutIdx = i
            }
            return input
        }

        fun redact(input: String, knownSecrets: Collection<String> = emptyList()): String {
            var out = input
            for (secret in knownSecrets) {
                if (secret.length >= 3) {
                    out = out.replace(secret, REDACTED)
                    val encoded = runCatching { java.net.URLEncoder.encode(secret, "UTF-8") }.getOrNull()
                    if (!encoded.isNullOrEmpty() && encoded != secret) {
                        out = out.replace(encoded, REDACTED)
                        val pct20 = encoded.replace("+", "%20")
                        if (pct20 != encoded) out = out.replace(pct20, REDACTED)
                    }
                }
            }
            out = URL_USERINFO_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED${m.groupValues[3]}" }
            out = URL_QUERY_SECRET_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
            out = AUTH_HEADER_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
            out = COOKIE_HEADER_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
            out = JSON_SECRET_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
            return out
        }
    }
}
