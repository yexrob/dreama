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
import com.limelight.local.plugin.PluginHostGuards
import com.limelight.local.plugin.PluginMediaSource
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
    fun launchPlayer(info: PlaybackInfo, sourceId: String, itemId: String, parentId: String? = null)
    fun launchPluginPlayer(
        instanceId: String,
        sourceId: String,
        itemId: String,
        title: String?,
        startMs: Long,
        durationMs: Long?,
        parentId: String? = null,
    )
    fun requestStorage()
    fun toast(text: String)
    fun consumePendingTab()
}

sealed interface Page {
    data class Tabs(val tab: Tab) : Page
    data class Grid(val sourceId: String, val categoryId: String, val title: String, val count: Int?) : Page
    data class Detail(
        val sourceId: String,
        val item: Item,
        val focusSeasonId: String? = null,
        val focusEpisodeId: String? = null,
    ) : Page
    data object Search : Page
    data class PcApps(val computer: StreamingComputer) : Page
    data object Settings : Page
    data class Sources(val initialTab: SourcesTab = SourcesTab.SOURCES) : Page
    data object AddSource : Page
    data class PluginForm(
        val pluginId: String,
        val instanceId: String? = null,
        val prefillServerUrl: String? = null,
        val suggestedName: String? = null,
        val reloginOnly: Boolean = false,
        val returnAfterSuccess: Boolean = false,
    ) : Page
    data class SourceEdit(val instanceId: String) : Page
    data class PluginDetail(val pluginId: String) : Page
    data class RepoBrowse(val repoUrl: String) : Page
    data object PluginDeveloper : Page
    data object PluginFileInstall : Page
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
    var pendingLibrarySourceId by mutableStateOf<String?>(null)
        private set
    private var authReturnSeq = 0
    var lastAuthenticatedSource by mutableStateOf<Pair<String, Int>?>(null)
        private set

    val top: Page get() = stack.last()

    private val kept = HashMap<Pair<Page, String>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(page: Page, name: String, make: () -> T): T = kept.getOrPut(page to name, make) as T

    fun tab(t: Tab) {
        if (Page.Search in stack) {
            model.searchAggregator.resetToIdle()
        }
        stack.clear()
        kept.clear()
        stack += Page.Tabs(t)
    }

    fun notifyPluginAuthenticated(sourceId: String) {
        model.searchAggregator.invalidateSourceCache(sourceId)
        lastAuthenticatedSource = sourceId to (++authReturnSeq)
    }

    fun consumeAuthenticatedSource(): String? {
        val current = lastAuthenticatedSource ?: return null
        lastAuthenticatedSource = null
        return current.first
    }

    fun openLibrarySelecting(sourceId: String) {
        pendingLibrarySourceId = sourceId
        tab(Tab.Library)
    }

    fun consumePendingLibrarySourceId(): String? {
        val v = pendingLibrarySourceId
        pendingLibrarySourceId = null
        return v
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
                if (gone == Page.Search) {
                    model.searchAggregator.resetToIdle()
                }
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

    fun openSearch() = open(Page.Search)

    fun openSettings() = open(Page.Settings)

    fun openSources(initialTab: SourcesTab = SourcesTab.SOURCES) = open(Page.Sources(initialTab))

    fun openAddSource() = open(Page.AddSource)

    fun openPluginForm(
        pluginId: String,
        instanceId: String? = null,
        prefillServerUrl: String? = null,
        suggestedName: String? = null,
        reloginOnly: Boolean = false,
        returnAfterSuccess: Boolean = false,
    ) = open(Page.PluginForm(pluginId, instanceId, prefillServerUrl, suggestedName, reloginOnly, returnAfterSuccess))

    fun openSourceEdit(instanceId: String) = open(Page.SourceEdit(instanceId))

    fun openPluginDetail(pluginId: String) = open(Page.PluginDetail(pluginId))

    fun openRepoBrowse(repoUrl: String) = open(Page.RepoBrowse(repoUrl))

    fun openPluginDeveloper() = open(Page.PluginDeveloper)

    fun openPluginFileInstall() = open(Page.PluginFileInstall)

    fun openPcApps(computer: StreamingComputer) = open(Page.PcApps(computer))

    fun pluginLogin(source: MediaSource, returnAfterSuccess: Boolean = false) {
        val pluginId = model.pluginIdForSource(source)
        val instanceId = (source as? PluginMediaSource)?.instanceId
            ?: source.source.id.removePrefix(PluginHost.PLUGIN_SOURCE_PREFIX).takeIf { it != source.source.id }
        if (pluginId != null && instanceId != null) {
            openPluginForm(
                pluginId = pluginId,
                instanceId = instanceId,
                reloginOnly = true,
                returnAfterSuccess = returnAfterSuccess,
            )
        } else {
            openSources()
        }
    }

    fun pluginSettings(source: MediaSource) {
        val pluginId = model.pluginIdForSource(source)
        val pkg = pluginId?.let { id -> model.installedPlugins.value.firstOrNull { it.pluginId == id } }
        if (pkg != null && pkg.needsPermissionReconfirm) {
            openPluginDetail(pkg.pluginId)
            return
        }
        val instanceId = (source as? PluginMediaSource)?.instanceId
            ?: source.source.id.removePrefix(PluginHost.PLUGIN_SOURCE_PREFIX).takeIf { it != source.source.id }
        if (instanceId != null) {
            openSourceEdit(instanceId)
        } else {
            openSources()
        }
    }

    fun pluginLogs(source: MediaSource) {
        val pluginId = model.pluginIdForSource(source) ?: return
        openPluginLogs(pluginId)
    }

    fun openPluginLogs(pluginId: String) {
        pluginLogsSheet = pluginId to model.pluginDisplayName(pluginId)
    }

    fun closePluginLogs() {
        pluginLogsSheet = null
    }

    fun toast(text: String) = actions.toast(text)

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
            if (PluginHostGuards.isPluginSource(source.source.kind)) {
                val instanceId = (source as? PluginMediaSource)?.instanceId
                    ?: source.source.id.removePrefix(PluginHost.PLUGIN_SOURCE_PREFIX)
                actions.launchPluginPlayer(
                    instanceId = instanceId,
                    sourceId = source.source.id,
                    itemId = item.id,
                    title = item.title,
                    startMs = startMs,
                    durationMs = item.durationMs,
                    parentId = item.parentId,
                )
            } else {
                scope.launch {
                    try {
                        val info = source.playback(item.id).copy(startPositionMs = startMs)
                        actions.launchPlayer(info, source.source.id, item.id, item.parentId)
                    } catch (e: Exception) {
                        actions.toast(actions.hostActivity.getString(R.string.wall_play_failed))
                    }
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
                    Page.Search -> SearchScreen(model, nav)
                    is Page.PcApps -> PcAppsScreen(model, nav, page)
                    Page.Settings -> SettingsScreen(model, nav)
                    is Page.Sources -> SourcesScreen(model, nav, page.initialTab)
                    Page.AddSource -> AddSourceScreen(model, nav)
                    is Page.PluginForm -> PluginFormScreen(model, nav, page)
                    is Page.SourceEdit -> SourceEditScreen(model, nav, page)
                    is Page.PluginDetail -> PluginDetailScreen(model, nav, page)
                    is Page.RepoBrowse -> RepoBrowseScreen(model, nav, page)
                    Page.PluginDeveloper -> PluginDeveloperScreen(model, nav)
                    Page.PluginFileInstall -> PluginFileInstallScreen(model, nav)
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
