package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.limelight.local.media.Item
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.MediaSource
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsSetupException
import kotlinx.coroutines.flow.filter

private fun MediaSource.icon() = when (source.kind) {
    SourceKind.LOCAL -> WallIcons.Headset
    SourceKind.WEBDAV -> WallIcons.Cloud
    SourceKind.PLUGIN -> WallIcons.Server
}

/** One page of a source's listing as it grows: items so far, what to ask for next. */
private class Listing {
    var items by mutableStateOf<List<Item>>(emptyList())
    var next by mutableStateOf<Int?>(0)
    var error by mutableStateOf<Throwable?>(null)
    var loading by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)

    suspend fun more(src: MediaSource, categoryId: String) {
        val page = next ?: return
        if (loading) {
            return
        }
        loading = true
        try {
            val got = src.browse(categoryId, page)
            items = items + got.items
            next = got.nextPage
            error = null
        } catch (e: Exception) {
            error = e
        } finally {
            loading = false
        }
    }
}

@Composable
private fun rememberListing(nav: WallNav, page: Page, src: MediaSource?, categoryId: String): Listing {
    val listing = nav.keep(page, "listing:${src?.source?.id}:$categoryId") { Listing() }
    LaunchedEffect(listing, listing.attempt) {
        // A kept listing already has its pages; only a new or retried one loads
        if (src != null && listing.items.isEmpty()) {
            listing.more(src, categoryId)
        }
    }
    return listing
}

private fun retry(l: Listing) {
    l.items = emptyList()
    l.next = 0
    l.error = null
    l.attempt++
}

/**
 * 媒体库 (design-opus library.html): sources on the left, the chosen one's
 * folders on the right. Hovering a source does not switch the right side;
 * it takes a click, so the ray passing over the list does not shake it.
 */
@Composable
fun LibraryScreen(model: WallModel, nav: WallNav) {
    val sources by model.sources.collectAsState()
    val found by model.discovered.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf(LocalVideoSource.ID) }
    val selected = sources.firstOrNull { it.source.id == selectedId } ?: sources.firstOrNull()
    val listing = rememberListing(nav, Page.Tabs(Tab.Library), selected, MediaSource.ROOT)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.offset(40.dp, 120.dp).width(392.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in sources) {
                val count = (model.shelves[s.source.id] as? Load.Ready)?.value?.firstOrNull()?.category?.count
                ListRow(s.source.name, sub = s.source.description + (count?.let { " · $it 个" } ?: ""),
                    lead = s.icon(), selected = s == selected) { selectedId = s.source.id }
            }
            if (sources.none { it.source.kind == SourceKind.WEBDAV }) {
                ListRow("添加 WebDAV", sub = found?.let { "找到 ${it.substringAfter("://")}" } ?: "Google Drive 等网盘",
                    lead = WallIcons.Plus) { nav.editWebDav(found) }
            }
        }
        Box(Modifier.offset(64.dp, 620.dp)) {
            WallButton("管理播放源", { nav.editWebDav(found) }, icon = WallIcons.Gear, kind = ButtonKind.Ghost, small = true)
        }
        if (selected != null) {
            Column(Modifier.offset(488.dp, 120.dp)) {
                Text(selected.source.name, style = WallType.Title)
                Text(selected.source.description, style = WallType.Caption, color = Wall.Text2,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Box(Modifier.offset(488.dp, 240.dp).width(728.dp)) {
                FolderPane(selected, listing, nav)
            }
        }
        NavBar(Tab.Library, nav::tab, nav.settings, solid = true)
    }
}

@Composable
private fun FolderPane(src: MediaSource, listing: Listing, nav: WallNav) {
    val error = listing.error
    when {
        error != null && listing.items.isEmpty() -> SourceProblem(src, error, nav) { retry(listing) }
        listing.items.isEmpty() && listing.next != null -> LazyVerticalGrid(GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
            userScrollEnabled = false) {
            items(6) { Skeleton(Modifier.height(148.dp), Wall.Radius) }
        }
        listing.items.isEmpty() -> Text("这里还没有视频。", style = WallType.Body, color = Wall.Text2)
        else -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(480.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 64.dp)) {
            items(listing.items, key = { it.id }) { item ->
                FolderTile(item.title, item.childCount?.let { "$it 个" } ?: item.subtitle?.takeIf { it.isNotEmpty() },
                    item.previews.ifEmpty { listOfNotNull(item.poster) }, { nav.item(src, item) },
                    Modifier.height(148.dp))
            }
        }
    }
}

/** Says what happened and gives one thing to do about it; no error codes (library.html, offline). */
@Composable
private fun SourceProblem(src: MediaSource, error: Throwable, nav: WallNav, retry: () -> Unit) {
    val setup = error is SourceNeedsSetupException
    Column(Modifier.width(660.dp)) {
        Icon(WallIcons.Alert, null, Modifier.size(48.dp), tint = if (setup) Wall.Text2 else Wall.Danger)
        Text(if (setup) error.message ?: "需要设置" else "连不上「${src.source.name}」", style = WallType.Head,
            modifier = Modifier.padding(top = 20.dp))
        Text(if (setup) "头显里的视频要先允许 depth3d 读取。" else "提供它的电脑可能关机了，或者不在这个 Wi-Fi 里。",
            style = WallType.Body, color = Wall.Text2, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (setup) {
                WallButton("允许访问", nav.storage, kind = ButtonKind.Primary)
            } else {
                WallButton("重试", retry, icon = WallIcons.Refresh, kind = ButtonKind.Primary)
                if (src.source.kind == SourceKind.WEBDAV) {
                    WallButton("检查设置", { nav.editWebDav() })
                }
            }
        }
    }
}

/**
 * One category or folder as a 6 column grid of posters (library.html, grid).
 * The poster carries the title, so nothing is repeated under it.
 */
@Composable
fun GridScreen(model: WallModel, nav: WallNav, page: Page.Grid) {
    val src = model.source(page.sourceId)
    val listing = rememberListing(nav, page, src, page.categoryId)
    val grid = nav.keep(page, "grid") { LazyGridState() }
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(listing) {
        // Keyed by the item count so a page that still leaves the end in view asks for the next one
        snapshotFlow { if (nearEnd && listing.next != null && listing.items.isNotEmpty()) listing.items.size else -1 }
            .filter { it >= 0 }.collect {
            if (src != null) {
                listing.more(src, page.categoryId)
            }
        }
    }
    val cellW: Dp = 172.dp
    val cellH: Dp = 258.dp
    Box(Modifier.fillMaxSize()) {
        val error = listing.error
        when {
            src == null -> Box(Modifier.offset(64.dp, 160.dp)) { Notice("这个播放源已经不在了") }
            error != null && listing.items.isEmpty() -> Box(Modifier.offset(64.dp, 160.dp)) {
                SourceProblem(src, error, nav) { retry(listing) }
            }
            listing.items.isEmpty() && listing.next != null -> Row(Modifier.offset(64.dp, 128.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                repeat(6) { Skeleton(Modifier.size(cellW, cellH), 14.dp) }
            }
            listing.items.isEmpty() -> Text("这里还没有视频。", style = WallType.Body, color = Wall.Text2,
                modifier = Modifier.offset(64.dp, 160.dp))
            else -> LazyVerticalGrid(GridCells.Fixed(6), Modifier.fillMaxSize(), state = grid,
                contentPadding = PaddingValues(start = Wall.Margin, end = Wall.Margin, top = 128.dp, bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                items(listing.items, key = { it.id }) { item ->
                    if (item.isFolder) {
                        FolderTile(item.title, item.childCount?.let { "$it 个" }, item.previews,
                            { nav.item(src, item) }, Modifier.size(cellW, cellH))
                    } else {
                        Poster(item.title, item.poster, { nav.item(src, item) }, width = cellW, height = cellH)
                    }
                }
            }
        }
        // The header sits over the grid's top padding so scrolled rows pass under it
        Box(Modifier.fillMaxWidth().height(112.dp).background(Wall.Bg))
        Box(Modifier.offset(64.dp, 32.dp)) { RoundButton(WallIcons.ChevL, { nav.back() }, filled = true) }
        Row(Modifier.offset(152.dp, 32.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(page.title, style = WallType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            val n = page.count ?: listing.items.size.takeIf { listing.next == null && it > 0 }
            src?.let {
                Text(it.source.name + (n?.let { c -> " · $c 个" } ?: ""), style = WallType.Caption, color = Wall.Text2)
            }
        }
    }
}
