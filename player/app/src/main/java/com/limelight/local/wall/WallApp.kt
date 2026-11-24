package com.limelight.local.wall

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.limelight.local.media.Item
import com.limelight.local.media.MediaSource
import com.limelight.local.media.PlaybackInfo
import kotlinx.coroutines.launch

/** What the wall needs from the Activity: everything that leaves Compose. */
interface WallActions {
    fun launchPlayer(info: PlaybackInfo, sourceId: String, itemId: String)
    fun openStreaming()
    fun openSettings()
    fun requestStorage()
    fun toast(text: String)
}

sealed interface Page {
    data class Tabs(val tab: Tab) : Page
    data class Grid(val sourceId: String, val categoryId: String, val title: String, val count: Int?) : Page
    data class Detail(val sourceId: String, val item: Item) : Page
}

/** The pages and the moves between them, shared by every screen. */
class WallNav(private val model: WallModel, private val actions: WallActions,
              private val starter: (MediaSource, Item, Long) -> Unit) {
    val stack = mutableStateListOf<Page>(Page.Tabs(Tab.Home))
    var webDavSheet by mutableStateOf<String?>(null)
        private set

    val top: Page get() = stack.last()

    // Scroll positions and loaded listings, kept while a page is on the
    // stack so coming back from a detail page does not start it over
    private val kept = HashMap<Pair<Page, String>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(page: Page, name: String, make: () -> T): T = kept.getOrPut(page to name, make) as T

    fun tab(t: Tab) {
        stack.clear()
        kept.clear()
        stack += Page.Tabs(t)
    }

    fun open(p: Page) {
        stack += p
    }

    fun back(): Boolean {
        if (webDavSheet != null) {
            webDavSheet = null
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

    fun detail(source: MediaSource, item: Item) = open(Page.Detail(source.source.id, item))

    fun category(source: MediaSource, categoryId: String, title: String, count: Int? = null) =
        open(Page.Grid(source.source.id, categoryId, title, count))

    /** Opens an item: a folder shows its contents, anything else its details. */
    fun item(source: MediaSource, item: Item) =
        if (item.isFolder) category(source, item.id, item.title, item.childCount) else detail(source, item)

    fun play(source: MediaSource, item: Item, startMs: Long) = starter(source, item, startMs)

    /** The WebDAV form, prefilled with [suggested] when discovery found a server. */
    fun editWebDav(suggested: String? = null) {
        webDavSheet = suggested ?: model.savedWebDav()?.base ?: "http://"
    }

    fun closeWebDav() {
        webDavSheet = null
    }

    val streaming: () -> Unit = actions::openStreaming
    val settings: () -> Unit = actions::openSettings
    val storage: () -> Unit = actions::requestStorage
}

@Composable
fun WallApp(model: WallModel, actions: WallActions) {
    val scope = rememberCoroutineScope()
    val nav = remember {
        WallNav(model, actions) { source, item, startMs ->
            scope.launch {
                try {
                    val info = source.playback(item.id).copy(startPositionMs = startMs)
                    actions.launchPlayer(info, source.source.id, item.id)
                } catch (e: Exception) {
                    actions.toast("不能播放：" + (e.message ?: e.toString()))
                }
            }
        }
    }
    BackHandler(enabled = nav.stack.size > 1 || nav.top != Page.Tabs(Tab.Home) || nav.webDavSheet != null) {
        nav.back()
    }
    DesignCanvas {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(nav.top, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "page") { page ->
                when (page) {
                    is Page.Tabs -> when (page.tab) {
                        Tab.Home -> HomeScreen(model, nav)
                        Tab.Library -> LibraryScreen(model, nav)
                        Tab.Cast -> CastScreen(nav)
                    }
                    is Page.Grid -> GridScreen(model, nav, page)
                    is Page.Detail -> DetailScreen(model, nav, page)
                }
            }
            nav.webDavSheet?.let { WebDavSheet(model, nav, it) }
        }
    }
}

/** "剩 65 分钟": the time left, in whole minutes, rounded up so it never says 0. */
fun minutesLeft(ms: Long): Int = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)

/** "1 小时 52 分" or "48 分钟". */
fun duration(ms: Long): String {
    val m = (ms / 60_000).toInt()
    return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "${m.coerceAtLeast(1)} 分钟"
}
