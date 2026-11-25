package com.limelight.local.wall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.MediaSource
import com.limelight.local.media.PluginInternalException
import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.StringLookup
import kotlinx.coroutines.delay

/** Action kind dispatched from `docs/specs/plugin-host-integration.md` §3.2. */
enum class SourceErrorActionKind {
    LOGIN,
    RETRY,
    REQUEST_STORAGE,
    CHECK_SETTINGS,
    PLUGIN_SETUP,
    BACK_AND_REFRESH,
    DISMISS_OR_SWITCH_QUALITY,
    RETRY_AFTER_COOLDOWN,
    SWITCH_SOURCE,
    VIEW_PLUGIN_LOGS,
    DISABLE_PLUGIN,
}

data class SourceErrorAction(
    val kind: SourceErrorActionKind,
    val label: String,
    val cooldownSec: Int? = null,
)

/**
 * Pure presentation model for every source/plugin error across HomeScreen, LibraryScreen,
 * DetailScreen, and LocalPlayerXR, strictly obeying `docs/specs/plugin-host-integration.md` §3.2.
 */
data class SourceErrorPresentation(
    val message: String,
    val hint: String? = null,
    val danger: Boolean,
    val primary: SourceErrorAction,
    val secondary: SourceErrorAction? = null,
)

object SourceErrorDispatcher {
    private val RAW_TECH_PATTERN = Regex(
        """(?i)(exception|error:|stacktrace|java\.|kotlin\.|okhttp|no item\s+\S|http\s+\d{3}|econn|timeout|timed out|unable to resolve|failed to connect|nullpointer)""",
    )

    /**
     * Returns [raw] only if it is non-blank user-facing text (no exception class names,
     * error codes, or raw English stack/network jargon); otherwise returns [fallback].
     */
    fun safeUserMessage(raw: String?, fallback: String): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallback
        if (RAW_TECH_PATTERN.containsMatchIn(trimmed)) return fallback
        // Reject purely ASCII non-empty diagnostic strings like "NeedsAuth" or "Internal"
        if (trimmed.none { it.code > 0x7F }) return fallback
        return trimmed
    }

    fun dispatch(
        error: Throwable,
        sourceKind: SourceKind,
        sourceName: String,
        strings: StringLookup,
        inPlaybackContext: Boolean = false,
    ): SourceErrorPresentation = when (error) {
        is SourceNeedsAuthException -> SourceErrorPresentation(
            message = safeUserMessage(error.message, strings.get(R.string.plugin_error_needs_auth)),
            hint = strings.get(R.string.plugin_login_hint),
            danger = false,
            primary = SourceErrorAction(
                kind = SourceErrorActionKind.LOGIN,
                label = strings.get(R.string.plugin_action_login),
            ),
        )

        is SourceAuthFailedException -> SourceErrorPresentation(
            message = safeUserMessage(error.message, strings.get(R.string.plugin_error_auth_failed)),
            danger = true,
            primary = SourceErrorAction(
                kind = SourceErrorActionKind.RETRY,
                label = strings.get(R.string.action_retry),
            ),
        )

        is SourceNeedsSetupException -> {
            val msg = safeUserMessage(error.message, strings.get(R.string.plugin_error_needs_setup))
            val isPermReconfirm = msg.contains("权限") && sourceKind == SourceKind.PLUGIN
            val primary = when (sourceKind) {
                SourceKind.LOCAL -> SourceErrorAction(
                    kind = SourceErrorActionKind.REQUEST_STORAGE,
                    label = strings.get(R.string.home_source_allow_access),
                )
                SourceKind.WEBDAV -> SourceErrorAction(
                    kind = SourceErrorActionKind.CHECK_SETTINGS,
                    label = strings.get(R.string.plugin_action_check_settings),
                )
                SourceKind.PLUGIN -> SourceErrorAction(
                    kind = SourceErrorActionKind.PLUGIN_SETUP,
                    label = strings.get(
                        if (isPermReconfirm) R.string.plugin_action_confirm_permissions
                        else R.string.plugin_action_setup,
                    ),
                )
            }
            val hint = when (sourceKind) {
                SourceKind.LOCAL -> strings.get(R.string.library_storage_hint)
                SourceKind.WEBDAV -> strings.get(R.string.webdav_setup_hint)
                SourceKind.PLUGIN -> strings.get(
                    if (isPermReconfirm) R.string.plugin_reconfirm_hint else R.string.plugin_setup_hint,
                )
            }
            SourceErrorPresentation(
                message = msg,
                hint = hint,
                danger = false,
                primary = primary,
            )
        }

        is SourceRateLimitedException -> {
            val sec = error.retryAfterSec?.takeIf { it > 0 }
            val fallback = if (sec != null) {
                strings.get(R.string.plugin_error_rate_limited_seconds, sec)
            } else {
                strings.get(R.string.plugin_error_rate_limited)
            }
            SourceErrorPresentation(
                message = safeUserMessage(error.message, fallback),
                danger = false,
                primary = SourceErrorAction(
                    kind = SourceErrorActionKind.RETRY_AFTER_COOLDOWN,
                    label = if (sec != null) {
                        strings.get(R.string.plugin_action_retry_later_seconds, sec)
                    } else {
                        strings.get(R.string.plugin_action_retry_later)
                    },
                    cooldownSec = sec,
                ),
            )
        }

        is SourceForbiddenException -> SourceErrorPresentation(
            message = safeUserMessage(error.message, strings.get(R.string.plugin_error_forbidden)),
            danger = false,
            primary = SourceErrorAction(
                kind = SourceErrorActionKind.DISMISS_OR_SWITCH_QUALITY,
                label = strings.get(
                    if (inPlaybackContext) R.string.plugin_action_switch_quality else R.string.action_got_it,
                ),
            ),
        )

        is ItemNotFoundException -> SourceErrorPresentation(
            message = safeUserMessage(error.message, strings.get(R.string.plugin_error_not_found)),
            danger = true,
            primary = SourceErrorAction(
                kind = SourceErrorActionKind.BACK_AND_REFRESH,
                label = strings.get(R.string.plugin_action_back_and_refresh),
            ),
        )

        is SourceUnsupportedException -> SourceErrorPresentation(
            message = safeUserMessage(error.message, strings.get(R.string.plugin_error_unsupported)),
            danger = false,
            primary = SourceErrorAction(
                kind = SourceErrorActionKind.SWITCH_SOURCE,
                label = strings.get(R.string.plugin_action_switch_source),
            ),
        )

        is PluginInternalException -> SourceErrorPresentation(
            message = safeUserMessage(error.message, strings.get(R.string.plugin_error_internal)),
            danger = true,
            primary = SourceErrorAction(
                kind = SourceErrorActionKind.VIEW_PLUGIN_LOGS,
                label = strings.get(R.string.plugin_action_view_logs),
            ),
            secondary = if (sourceKind == SourceKind.PLUGIN) {
                SourceErrorAction(
                    kind = SourceErrorActionKind.DISABLE_PLUGIN,
                    label = strings.get(R.string.plugin_action_disable_plugin),
                )
            } else {
                null
            },
        )

        is SourceUnavailableException -> {
            val fallback = if (sourceKind == SourceKind.PLUGIN) {
                strings.get(R.string.plugin_error_unavailable)
            } else {
                strings.get(R.string.library_source_unreachable, sourceName)
            }
            SourceErrorPresentation(
                message = safeUserMessage(error.message, fallback),
                hint = if (sourceKind == SourceKind.WEBDAV) strings.get(R.string.library_source_offline_hint) else null,
                danger = true,
                primary = SourceErrorAction(
                    kind = SourceErrorActionKind.RETRY,
                    label = strings.get(R.string.action_retry),
                ),
                secondary = if (sourceKind == SourceKind.PLUGIN || sourceKind == SourceKind.WEBDAV) {
                    SourceErrorAction(
                        kind = SourceErrorActionKind.CHECK_SETTINGS,
                        label = strings.get(R.string.plugin_action_check_settings),
                    )
                } else {
                    null
                },
            )
        }

        else -> SourceErrorPresentation(
            message = if (sourceKind == SourceKind.PLUGIN) {
                strings.get(R.string.plugin_error_unavailable)
            } else {
                strings.get(R.string.library_source_unreachable, sourceName)
            },
            hint = if (sourceKind == SourceKind.WEBDAV) strings.get(R.string.library_source_offline_hint) else null,
            danger = true,
            primary = SourceErrorAction(
                kind = SourceErrorActionKind.RETRY,
                label = strings.get(R.string.action_retry),
            ),
            secondary = if (sourceKind == SourceKind.PLUGIN || sourceKind == SourceKind.WEBDAV) {
                SourceErrorAction(
                    kind = SourceErrorActionKind.CHECK_SETTINGS,
                    label = strings.get(R.string.plugin_action_check_settings),
                )
            } else {
                null
            },
        )
    }
}

@Composable
fun rememberErrorPresentation(
    error: Throwable,
    source: MediaSource,
    inPlaybackContext: Boolean = false,
): SourceErrorPresentation {
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    return remember(error, source.source.id, source.source.kind, source.source.name, inPlaybackContext) {
        SourceErrorDispatcher.dispatch(
            error = error,
            sourceKind = source.source.kind,
            sourceName = source.source.name,
            strings = strings,
            inPlaybackContext = inPlaybackContext,
        )
    }
}

/**
 * Renders primary + optional secondary error action buttons with rate-limit countdown support.
 */
@Composable
fun SourceErrorActionButtons(
    presentation: SourceErrorPresentation,
    source: MediaSource,
    nav: WallNav,
    onRetry: () -> Unit,
    onDismiss: () -> Unit = {},
    small: Boolean = false,
    primaryAsWhite: Boolean = !small,
    modifier: Modifier = Modifier,
) {
    var cooldown by remember(presentation) {
        mutableIntStateOf(presentation.primary.cooldownSec ?: 0)
    }
    LaunchedEffect(presentation) {
        cooldown = presentation.primary.cooldownSec ?: 0
        while (cooldown > 0) {
            delay(1000L)
            cooldown = (cooldown - 1).coerceAtLeast(0)
        }
    }

    fun invokeAction(kind: SourceErrorActionKind) {
        when (kind) {
            SourceErrorActionKind.LOGIN -> nav.pluginLogin(source)
            SourceErrorActionKind.RETRY, SourceErrorActionKind.RETRY_AFTER_COOLDOWN -> onRetry()
            SourceErrorActionKind.REQUEST_STORAGE -> nav.storage()
            SourceErrorActionKind.CHECK_SETTINGS -> {
                if (source.source.kind == SourceKind.WEBDAV) nav.editWebDav()
                else nav.pluginSettings(source)
            }
            SourceErrorActionKind.PLUGIN_SETUP -> nav.pluginSettings(source)
            SourceErrorActionKind.BACK_AND_REFRESH -> nav.backAndRefresh(source.source.id)
            SourceErrorActionKind.DISMISS_OR_SWITCH_QUALITY -> onDismiss()
            SourceErrorActionKind.SWITCH_SOURCE -> nav.tab(Tab.Library)
            SourceErrorActionKind.VIEW_PLUGIN_LOGS -> nav.pluginLogs(source)
            SourceErrorActionKind.DISABLE_PLUGIN -> nav.disablePlugin(source)
        }
    }

    val primaryLabel = if (presentation.primary.kind == SourceErrorActionKind.RETRY_AFTER_COOLDOWN) {
        if (cooldown > 0) stringResource(R.string.plugin_action_retry_later_seconds, cooldown)
        else stringResource(R.string.action_retry)
    } else {
        presentation.primary.label
    }
    val primaryEnabled = presentation.primary.kind != SourceErrorActionKind.RETRY_AFTER_COOLDOWN || cooldown <= 0
    val primaryIcon = when (presentation.primary.kind) {
        SourceErrorActionKind.RETRY, SourceErrorActionKind.RETRY_AFTER_COOLDOWN -> WallIcons.Refresh
        else -> null
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WallButton(
            text = primaryLabel,
            onClick = { invokeAction(presentation.primary.kind) },
            icon = primaryIcon,
            kind = if (primaryAsWhite) ButtonKind.Primary else ButtonKind.Plain,
            small = small,
            enabled = primaryEnabled,
        )
        presentation.secondary?.let { sec ->
            WallButton(
                text = sec.label,
                onClick = { invokeAction(sec.kind) },
                kind = ButtonKind.Plain,
                small = small,
            )
        }
    }
}
