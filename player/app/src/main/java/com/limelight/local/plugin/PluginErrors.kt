package com.limelight.local.plugin

import android.content.Context
import com.limelight.R
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.PluginInternalException
import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.SourceUnsupportedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Protocol v1 §6 error codes.
 */
enum class PluginErrorCode(val wireName: String) {
    NEEDS_AUTH("NeedsAuth"),
    AUTH_FAILED("AuthFailed"),
    NEEDS_SETUP("NeedsSetup"),
    UNAVAILABLE("Unavailable"),
    NOT_FOUND("NotFound"),
    FORBIDDEN("Forbidden"),
    RATE_LIMITED("RateLimited"),
    UNSUPPORTED("Unsupported"),
    INTERNAL("Internal");

    companion object {
        fun fromWire(code: String?): PluginErrorCode =
            entries.firstOrNull { it.wireName == code } ?: INTERNAL
    }
}

data class PluginErrorPayload(
    val code: PluginErrorCode,
    val message: String? = null,
    val retryAfterSec: Int? = null,
)

/**
 * Maps protocol error codes to depth3d media exceptions with Simplified Chinese
 * user-facing fallback messages (no exception class names, error codes, or
 * technical English jargon).
 */
object PluginErrorMapper {
    const val WIRE_MARKER = "__D3_PLUGIN_ERR__:"
    private val json = Json { ignoreUnknownKeys = true }

    fun defaultMessage(
        code: PluginErrorCode,
        retryAfterSec: Int? = null,
        context: Context? = null,
    ): String {
        if (context != null) {
            return when (code) {
                PluginErrorCode.NEEDS_AUTH -> context.getString(R.string.plugin_error_needs_auth)
                PluginErrorCode.AUTH_FAILED -> context.getString(R.string.plugin_error_auth_failed)
                PluginErrorCode.NEEDS_SETUP -> context.getString(R.string.plugin_error_needs_setup)
                PluginErrorCode.UNAVAILABLE -> context.getString(R.string.plugin_error_unavailable)
                PluginErrorCode.NOT_FOUND -> context.getString(R.string.plugin_error_not_found)
                PluginErrorCode.FORBIDDEN -> context.getString(R.string.plugin_error_forbidden)
                PluginErrorCode.RATE_LIMITED -> {
                    if (retryAfterSec != null && retryAfterSec > 0) {
                        context.getString(R.string.plugin_error_rate_limited_seconds, retryAfterSec)
                    } else {
                        context.getString(R.string.plugin_error_rate_limited)
                    }
                }
                PluginErrorCode.UNSUPPORTED -> context.getString(R.string.plugin_error_unsupported)
                PluginErrorCode.INTERNAL -> context.getString(R.string.plugin_error_internal)
            }
        }
        return when (code) {
            PluginErrorCode.NEEDS_AUTH -> "需要重新登录，请点此进入登录页。"
            PluginErrorCode.AUTH_FAILED -> "登录失败，请检查账号或凭据后重试。"
            PluginErrorCode.NEEDS_SETUP -> "尚未完成配置，请先填写连接设置。"
            PluginErrorCode.UNAVAILABLE -> "暂时连不上这个来源，请检查网络或服务器状态后重试。"
            PluginErrorCode.NOT_FOUND -> "这个内容已经不在了，请返回刷新列表。"
            PluginErrorCode.FORBIDDEN -> "暂时无法访问这个内容，请检查账号权限或地区限制。"
            PluginErrorCode.RATE_LIMITED -> {
                if (retryAfterSec != null && retryAfterSec > 0) {
                    "请求太频繁，请等待 ${retryAfterSec} 秒后再试。"
                } else {
                    "请求太频繁，请稍后再试。"
                }
            }
            PluginErrorCode.UNSUPPORTED -> "这个内容类型暂不支持播放，请尝试其他片源。"
            PluginErrorCode.INTERNAL -> "这个插件出错了，可以查看日志或停用插件。"
        }
    }

    fun toException(
        payload: PluginErrorPayload,
        itemId: String? = null,
        context: Context? = null,
        cause: Throwable? = null,
    ): Exception {
        val msg = payload.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultMessage(payload.code, payload.retryAfterSec, context)
        return when (payload.code) {
            PluginErrorCode.NEEDS_AUTH -> SourceNeedsAuthException(msg)
            PluginErrorCode.AUTH_FAILED -> SourceAuthFailedException(msg)
            PluginErrorCode.NEEDS_SETUP -> SourceNeedsSetupException(msg)
            PluginErrorCode.UNAVAILABLE -> SourceUnavailableException(msg, cause)
            PluginErrorCode.NOT_FOUND -> ItemNotFoundException(itemId ?: "", msg)
            PluginErrorCode.FORBIDDEN -> SourceForbiddenException(msg)
            PluginErrorCode.RATE_LIMITED -> SourceRateLimitedException(msg, payload.retryAfterSec)
            PluginErrorCode.UNSUPPORTED -> SourceUnsupportedException(msg)
            PluginErrorCode.INTERNAL -> PluginInternalException(msg, cause)
        }
    }

    fun parseWireError(rawMessage: String?): PluginErrorPayload? {
        if (rawMessage == null) return null
        val idx = rawMessage.indexOf(WIRE_MARKER)
        if (idx < 0) return null
        val jsonPart = rawMessage.substring(idx + WIRE_MARKER.length).lineSequence().firstOrNull()?.trim()
            ?: return null
        return try {
            val obj = json.parseToJsonElement(jsonPart) as? JsonObject ?: return null
            val codeStr = (obj["code"] as? JsonPrimitive)?.content
            val message = (obj["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
            val retryAfterSec = (obj["retryAfterSec"] as? JsonPrimitive)?.intOrNull
            PluginErrorPayload(
                code = PluginErrorCode.fromWire(codeStr),
                message = message,
                retryAfterSec = retryAfterSec,
            )
        } catch (_: Exception) {
            null
        }
    }
}
