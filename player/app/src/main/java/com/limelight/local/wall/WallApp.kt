package com.limelight.local.wall

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.local.media.Item
import com.limelight.local.media.MediaSource
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.stream.RetryAction
import com.limelight.local.stream.StreamFlowState
import com.limelight.local.stream.StreamingComputer
import com.limelight.nvstream.http.NvApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the wall needs from the Activity: everything that leaves Compose. */
interface WallActions {
    val hostActivity: Activity
    fun launchPlayer(info: PlaybackInfo, sourceId: String, itemId: String)
    fun requestStorage()
    fun toast(text: String)
    fun consumePendingTab()
}

sealed interface Page {
    data class Tabs(val tab: Tab) : Page
    data class Grid(val sourceId: String, val categoryId: String, val title: String, val count: Int?) : Page
    data class Detail(val sourceId: String, val item: Item) : Page
    data class PcApps(val computer: StreamingComputer) : Page
    data object Settings : Page
}

/** The pages and the moves between them, shared by every screen. */
class WallNav(
    private val model: WallModel,
    private val actions: WallActions,
    private val scope: CoroutineScope,
    private val starter: (MediaSource, Item, Long) -> Unit,
) {
    val stack = mutableStateListOf<Page>(Page.Tabs(Tab.Home))
    var webDavSheet by mutableStateOf<String?>(null)
        private set
    var pluginLogsSheet by mutableStateOf<Pair<String, String>?>(null)
        private set
    var refreshSignal by mutableIntStateOf(0)
        private set

    val top: Page get() = stack.last()

    private val kept = HashMap<Pair<Page, String>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(page: Page, name: String, make: () -> T): T = kept.getOrPut(page to name, make) as T

    fun tab(t: Tab) {
        stack.clear()
        kept.clear()
        stack += Page.Tabs(t)
    }

    fun open(p: Page) {
        if (top == p) return
        stack += p
    }

    fun back(): Boolean {
        if (pluginLogsSheet != null) {
            pluginLogsSheet = null
            return true
        }
        if (webDavSheet != null) {
            webDavSheet = null
            return true
        }
        if (model.streaming.flow.value !is StreamFlowState.Idle) {
            model.streaming.cancelActiveOperation()
            return true
        }
        if (stack.size > 1) {
            val gone = stack.removeAt(stack.size - 1)
            if (gone !in stack) {
                kept.keys.removeAll { it.first == gone }
            }
            return true
        }
        if (top != Page.Tabs(Tab.Home)) {
            tab(Tab.Home)
            return true
        }
        return false
    }

    fun backAndRefresh(sourceId: String) {
        back()
        kept.keys.removeAll { (_, key) -> key.startsWith("listing:$sourceId:") }
        refreshSignal++
        model.refresh(sourceId)
    }

    fun detail(source: MediaSource, item: Item) = open(Page.Detail(source.source.id, item))

    fun category(source: MediaSource, categoryId: String, title: String, count: Int? = null) =
        open(Page.Grid(source.source.id, categoryId, title, count))

    fun item(source: MediaSource, item: Item) =
        if (item.isFolder) category(source, item.id, item.title, item.childCount) else detail(source, item)

    fun play(source: MediaSource, item: Item, startMs: Long) = starter(source, item, startMs)

    fun openSettings() = open(Page.Settings)

    fun openPcApps(computer: StreamingComputer) = open(Page.PcApps(computer))

    fun pluginLogin(source: MediaSource) {
        openSettings()
    }

    fun pluginSettings(source: MediaSource) {
        openSettings()
    }

    fun pluginLogs(source: MediaSource) {
        val pluginId = model.pluginIdForSource(source) ?: return
        pluginLogsSheet = pluginId to model.pluginDisplayName(pluginId)
    }

    fun closePluginLogs() {
        pluginLogsSheet = null
    }

    fun disablePlugin(source: MediaSource) {
        scope.launch {
            val name = model.disablePluginForSource(source)
            if (name != null) {
                actions.toast(actions.hostActivity.getString(R.string.plugin_disabled_toast, name))
            }
        }
    }

    fun connectComputer(computer: StreamingComputer, app: NvApp? = null) {
        model.streaming.connect(
            activity = actions.hostActivity,
            computer = computer,
            appOverride = app,
            onOpenAppGrid = { openPcApps(it) },
        )
    }

    fun retryStream(action: RetryAction) {
        model.streaming.retry(actions.hostActivity, action, onOpenAppGrid = { openPcApps(it) })
    }

    fun confirmChangedCert(state: StreamFlowState.CertConfirm) {
        model.streaming.confirmChangedCertificate(actions.hostActivity, state, onOpenAppGrid = { openPcApps(it) })
    }

    fun editWebDav(suggested: String? = null) {
        webDavSheet = suggested ?: model.savedWebDav()?.base ?: "http://"
    }

    fun closeWebDav() {
        webDavSheet = null
    }

    val settings: () -> Unit = ::openSettings
    val storage: () -> Unit = actions::requestStorage
}

@Composable
fun WallApp(model: WallModel, actions: WallActions, pendingTab: StateFlow<Tab?>) {
    val scope = rememberCoroutineScope()
    val nav = remember {
        WallNav(model, actions, scope) { source, item, startMs ->
            scope.launch {
                try {
                    val info = source.playback(item.id).copy(startPositionMs = startMs)
                    actions.launchPlayer(info, source.source.id, item.id)
                } catch (e: Exception) {
                    actions.toast(actions.hostActivity.getString(R.string.wall_play_failed))
                }
            }
        }
    }
    val requestedTab by pendingTab.collectAsState()
    LaunchedEffect(requestedTab) {
        requestedTab?.let { target ->
            nav.tab(target)
            actions.consumePendingTab()
        }
    }

    val flowState by model.streaming.flow.collectAsState()
    BackHandler(
        enabled = nav.stack.size > 1 || nav.top != Page.Tabs(Tab.Home) ||
            nav.webDavSheet != null || nav.pluginLogsSheet != null || flowState !is StreamFlowState.Idle,
    ) {
        nav.back()
    }
    DesignCanvas {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                nav.top,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "page",
            ) { page ->
                when (page) {
                    is Page.Tabs -> when (page.tab) {
                        Tab.Home -> HomeScreen(model, nav)
                        Tab.Library -> LibraryScreen(model, nav)
                        Tab.Cast -> CastScreen(model, nav)
                    }
                    is Page.Grid -> GridScreen(model, nav, page)
                    is Page.Detail -> DetailScreen(model, nav, page)
                    is Page.PcApps -> PcAppsScreen(model, nav, page)
                    Page.Settings -> SettingsScreen(model, nav)
                }
            }
            nav.webDavSheet?.let { WebDavSheet(model, nav, it) }
            nav.pluginLogsSheet?.let { (pluginId, pluginName) ->
                PluginLogsSheet(
                    pluginId = pluginId,
                    pluginName = pluginName,
                    model = model,
                    onDismiss = { nav.closePluginLogs() },
                    onCleared = { actions.toast(actions.hostActivity.getString(R.string.plugin_logs_cleared)) },
                )
            }
        }
    }
}

/** "剩 65 分钟": the time left, in whole minutes, rounded up so it never says 0. */
fun minutesLeft(ms: Long): Int = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)

/** Resource-backed formatted duration for Compose screens. */
@Composable
fun formatDuration(ms: Long): String {
    val m = (ms / 60_000).toInt()
    return if (m >= 60) {
        stringResource(R.string.duration_hours_minutes, m / 60, m % 60)
    } else {
        stringResource(R.string.duration_minutes, m.coerceAtLeast(1))
    }
}
