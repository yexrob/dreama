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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.local.media.Item
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.MediaSource
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
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
    LaunchedEffect(listing, listing.attempt, nav.refreshSignal) {
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
    val pendingSelected = nav.pendingLibrarySourceId
    LaunchedEffect(pendingSelected) {
        if (pendingSelected != null) {
            selectedId = nav.consumePendingLibrarySourceId() ?: selectedId
        }
    }
    val selected = sources.firstOrNull { it.source.id == selectedId } ?: sources.firstOrNull()
    val listing = rememberListing(nav, Page.Tabs(Tab.Library), selected, MediaSource.ROOT)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.offset(40.dp, 120.dp).width(392.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in sources) {
                val shelfState = model.shelves[s.source.id]
                val failedErr = (shelfState as? Load.Failed)?.error
                val count = (shelfState as? Load.Ready)?.value?.firstOrNull()?.category?.count
                val sub = when {
                    failedErr is SourceNeedsAuthException -> stringResource(R.string.plugin_status_needs_auth)
                    failedErr is SourceNeedsSetupException -> stringResource(R.string.plugin_status_needs_setup)
                    failedErr != null -> stringResource(R.string.plugin_status_unreachable)
                    count != null -> stringResource(R.string.library_label_with_count, s.source.description, count)
                    else -> s.source.description
                }
                ListRow(
                    label = s.source.name,
                    sub = sub,
                    subDanger = failedErr != null,
                    lead = s.icon(),
                    selected = s == selected,
                ) { selectedId = s.source.id }
            }
            if (sources.none { it.source.kind == SourceKind.WEBDAV }) {
                val webDavSub = found?.let { stringResource(R.string.library_found_webdav, it.substringAfter("://")) }
                    ?: stringResource(R.string.library_webdav_desc)
                ListRow(stringResource(R.string.library_add_webdav), sub = webDavSub,
                    lead = WallIcons.Plus) { nav.editWebDav(found) }
            }
        }
        Row(
            Modifier.offset(64.dp, 620.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WallButton(
                stringResource(R.string.library_add_source),
                { nav.openAddSource() },
                icon = WallIcons.Plus,
                kind = ButtonKind.Plain,
                small = true,
            )
            RoundButton(WallIcons.Gear, { nav.openSources() }, filled = true)
        }
        if (selected != null) {
            Column(Modifier.offset(488.dp, 120.dp)) {
                Text(selected.source.name, style = WallType.Title)
                Text(selected.source.description, style = WallType.Caption, color = Wall.Text2,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Box(Modifier.offset(488.dp, 240.dp).width(728.dp)) {
                CompositionLocalProvider(LocalMediaSource provides selected) {
                    FolderPane(selected, listing, model, nav)
                }
            }
        }
        NavBar(Tab.Library, nav::tab, nav.settings, solid = true)
    }
}

@Composable
private fun FolderPane(src: MediaSource, listing: Listing, model: WallModel, nav: WallNav) {
    val error = listing.error
    when {
        error != null && listing.items.isEmpty() -> SourceProblem(src, error, model, nav) { retry(listing) }
        listing.items.isEmpty() && listing.next != null -> LazyVerticalGrid(GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
            userScrollEnabled = false) {
            items(6) { Skeleton(Modifier.height(148.dp), Wall.Radius) }
        }
        listing.items.isEmpty() -> Text(stringResource(R.string.library_empty_videos), style = WallType.Body, color = Wall.Text2)
        else -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(480.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 64.dp)) {
            items(listing.items, key = { it.id }) { item ->
                val countLabel = item.childCount?.let { stringResource(R.string.home_folder_count, it) }
                    ?: item.subtitle?.takeIf { it.isNotEmpty() }
                FolderTile(item.title, countLabel,
                    item.previews.ifEmpty { listOfNotNull(item.poster, item.backdrop, item.thumb) }, { nav.item(src, item) },
                    Modifier.height(148.dp))
            }
        }
    }
}

/** Says what happened and gives one thing to do about it; no error codes (library.html, offline, §3.2). */
@Composable
private fun SourceProblem(src: MediaSource, error: Throwable, model: WallModel, nav: WallNav, retry: () -> Unit) {
    val presentation = rememberErrorPresentation(
        error = error,
        source = src,
        needsPermissionReconfirm = model.needsPermissionReconfirm(src),
    )
    Column(Modifier.width(660.dp)) {
        Icon(WallIcons.Alert, null, Modifier.size(48.dp), tint = if (presentation.danger) Wall.Danger else Wall.Text2)
        Text(
            presentation.message,
            style = WallType.Head,
            modifier = Modifier.padding(top = 20.dp),
        )
        presentation.hint?.let { hintText ->
            Text(
                hintText,
                style = WallType.Body,
                color = Wall.Text2,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        SourceErrorActionButtons(
            presentation = presentation,
            source = src,
            nav = nav,
            onRetry = retry,
            onDismiss = { nav.back() },
            primaryAsWhite = true,
            modifier = Modifier.padding(top = 36.dp),
        )
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
    CompositionLocalProvider(LocalMediaSource provides src) {
        Box(Modifier.fillMaxSize()) {
            val error = listing.error
            when {
                src == null -> Box(Modifier.offset(64.dp, 160.dp)) { Notice(stringResource(R.string.library_source_missing)) }
                error != null && listing.items.isEmpty() -> Box(Modifier.offset(64.dp, 160.dp)) {
                    SourceProblem(src, error, model, nav) { retry(listing) }
                }
                listing.items.isEmpty() && listing.next != null -> Row(Modifier.offset(64.dp, 128.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    repeat(6) { Skeleton(Modifier.size(cellW, cellH), 14.dp) }
                }
                listing.items.isEmpty() -> Text(stringResource(R.string.library_empty_videos), style = WallType.Body, color = Wall.Text2,
                    modifier = Modifier.offset(64.dp, 160.dp))
                else -> LazyVerticalGrid(GridCells.Fixed(6), Modifier.fillMaxSize(), state = grid,
                    contentPadding = PaddingValues(start = Wall.Margin, end = Wall.Margin, top = 128.dp, bottom = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    items(listing.items, key = { it.id }) { item ->
                        if (item.isFolder) {
                            val countLabel = item.childCount?.let { stringResource(R.string.home_folder_count, it) }
                            FolderTile(item.title, countLabel, item.previews.ifEmpty { listOfNotNull(item.poster, item.thumb) },
                                { nav.item(src, item) }, Modifier.size(cellW, cellH))
                        } else {
                            Poster(item.title, item.poster ?: item.thumb ?: item.backdrop, { nav.item(src, item) }, width = cellW, height = cellH)
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
                    val label = n?.let { c -> stringResource(R.string.library_label_with_count, it.source.name, c) }
                        ?: it.source.name
                    Text(label, style = WallType.Caption, color = Wall.Text2)
                }
            }
        }
    }
}
