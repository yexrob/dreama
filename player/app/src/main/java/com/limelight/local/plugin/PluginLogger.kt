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
 * - Redacts configured secret values (password config fields) and common
 *   credential/token patterns before emitting to logcat/LimeLog or the
 *   in-memory diagnostic buffer.
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
    private val maxEntries: Int = 200,
) {
    private val ring = ArrayDeque<PluginLogEntry>(maxEntries)
    private val lock = Any()

    fun log(level: PluginLogLevel, rawMessage: String) {
        val scrubbed = redact(rawMessage, knownSecretsProvider())
        val formatted = "depth3d: plugin $pluginId [$instanceId] ${level.name}: $scrubbed"
        synchronized(lock) {
            if (ring.size >= maxEntries) ring.removeFirst()
            ring.addLast(
                PluginLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    level = level,
                    pluginId = pluginId,
                    instanceId = instanceId,
                    message = scrubbed,
                ),
            )
        }
        sink(level, formatted)
    }

    fun debug(msg: String) = log(PluginLogLevel.DEBUG, msg)
    fun info(msg: String) = log(PluginLogLevel.INFO, msg)
    fun warn(msg: String) = log(PluginLogLevel.WARN, msg)
    fun error(msg: String) = log(PluginLogLevel.ERROR, msg)

    fun recentEntries(): List<PluginLogEntry> = synchronized(lock) { ring.toList() }

    companion object {
        private const val REDACTED = "[REDACTED]"

        private val AUTH_HEADER_REGEX = Regex(
            """(?i)\b(authorization\s*[:=]\s*(?:bearer|basic|mediabrowser|emby|token)\s+)([^\r\n;,"']+)""",
        )
        private val COOKIE_HEADER_REGEX = Regex(
            """(?i)\b((?:set-)?cookie\s*[:=]\s*)([^\r\n]+)""",
        )
        private val JSON_SECRET_REGEX = Regex(
            """(?i)("?(?:password|passwd|pwd|secret|token|access_?token|refresh_?token|api_?key|x-emby-token|x-mediabrowser-token|sessdata|bili_jct|pin)"?\s*[:=]\s*["']?)([^"',\s&;}\]]+)""",
        )
        private val URL_USERINFO_REGEX = Regex(
            """(https?://)([^/\s:@]+:[^/\s@]+)(@)""",
        )

        fun redact(input: String, knownSecrets: Collection<String> = emptyList()): String {
            var out = input
            for (secret in knownSecrets) {
                if (secret.length >= 3) {
                    out = out.replace(secret, REDACTED)
                }
            }
            out = URL_USERINFO_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED${m.groupValues[3]}" }
            out = AUTH_HEADER_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
            out = COOKIE_HEADER_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
            out = JSON_SECRET_REGEX.replace(out) { m -> "${m.groupValues[1]}$REDACTED" }
            return out
        }
    }
}
