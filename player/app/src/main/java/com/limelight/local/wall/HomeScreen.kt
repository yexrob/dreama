package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.local.media.Artworks
import com.limelight.local.media.Item
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Shelf
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.stream.ComputerListMerger
import com.limelight.local.stream.StreamingComputer

/** The hero: the last thing watched, or failing that the newest video. */
private data class Hero(val source: MediaSource, val item: Item, val resume: Resume?)

/**
 * 首页 (design-opus home.html). The hero and 继续观看 share the first screen;
 * below them each source gets a row per shelf, followed by paired & online PCs
 * when available so desktop streaming is one click away.
 */
@Composable
fun HomeScreen(model: WallModel, nav: WallNav) {
    val sources by model.sources.collectAsState()
    val watching by model.continueWatching.collectAsState()
    val allComputers by model.streaming.computers.collectAsState()
    val homeComputers = remember(allComputers) { ComputerListMerger.homeScreenComputers(allComputers) }

    val resumes = (watching as? Load.Ready)?.value
    val shelves = sources.map { it to model.shelves[it.source.id] }
    val list = nav.keep(Page.Tabs(Tab.Home), "list") { LazyListState() }
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 120 } }

    val hasContent = shelves.any { (_, s) -> s is Load.Ready && s.value.any { it.items.isNotEmpty() } }
    val settled = shelves.none { (_, s) -> s == null || s is Load.Loading } && resumes != null
    val down = shelves.any { (_, s) -> s is Load.Failed && s.error !is SourceNeedsSetupException }
    val hero = resumes?.firstOrNull()?.let { Hero(it.source, it.item, it) } ?: shelves.firstNotNullOfOrNull { (src, s) ->
        (s as? Load.Ready)?.value?.firstNotNullOfOrNull { sh -> sh.items.firstOrNull { !it.isFolder } }?.let { Hero(src, it, null) }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            settled && !hasContent && !down && resumes.isNullOrEmpty() && homeComputers.isEmpty() ->
                EmptyState(model, nav)
            !settled && !hasContent && resumes.isNullOrEmpty() && homeComputers.isEmpty() ->
                HomeSkeleton()
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 64.dp)) {
                if (hero != null) {
                    item("hero") { HeroBlock(hero, resumes.orEmpty(), nav) }
                } else {
                    item("top") { Spacer(Modifier.height(96.dp)) }
                }
                for ((src, state) in shelves) {
                    when (state) {
                        is Load.Ready -> state.value.filter { it.items.isNotEmpty() }.forEach { shelf ->
                            item("${src.source.id}/${shelf.category.id}") { ShelfRow(src, shelf, nav) }
                        }
                        is Load.Failed -> item("${src.source.id}/failed") { FailedRow(src, state.error, model, nav) }
                        else -> item("${src.source.id}/loading") { SkeletonRow() }
                    }
                }
                if (homeComputers.isNotEmpty()) {
                    item("home-computers") { HomeComputersRow(homeComputers, nav) }
                }
            }
        }
        NavBar(Tab.Home, nav::tab, nav::openSettings, solid = scrolled || hero == null)
    }
}

@Composable
private fun HomeComputersRow(computers: List<StreamingComputer>, nav: WallNav) {
    Column(Modifier.padding(top = 44.dp)) {
        SectionTitle(stringResource(R.string.stream_home_section_title), Modifier.padding(start = Wall.Margin)) {
            nav.tab(Tab.Cast)
        }
        Spacer(Modifier.height(14.dp))
        val row = rememberLazyListState()
        LazyRow(
            state = row,
            flingBehavior = rememberSnapFlingBehavior(row),
            contentPadding = PaddingValues(horizontal = Wall.Margin),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(computers, key = { it.id }) { pc ->
                PcCard(
                    computer = pc,
                    onPrimaryClick = {
                        nav.tab(Tab.Cast)
                        nav.connectComputer(pc)
                    },
                )
            }
        }
    }
}

@Composable
private fun HeroBlock(hero: Hero, resumes: List<Resume>, nav: WallNav) {
    val r = hero.resume
    Box(Modifier.fillMaxWidth().height(if (resumes.isEmpty()) 560.dp else 700.dp)) {
        ArtBox(hero.item.backdrop ?: hero.item.poster, hero.item.title, Artworks.Size.BACKDROP,
            Modifier.fillMaxWidth().height(560.dp)) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Wall.Bg.copy(alpha = 0.88f), 0.34f to Wall.Bg.copy(alpha = 0.62f), 0.66f to Color.Transparent)))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.46f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Wall.Bg))))
            Box(Modifier.fillMaxWidth().height(170.dp)
                .background(Brush.verticalGradient(listOf(Wall.Bg.copy(alpha = 0.62f), Color.Transparent))))
        }
        Column(Modifier.offset(64.dp, 128.dp).width(640.dp)) {
            Text(if (r != null) "上次看到 · 来自 ${hero.source.source.name}" else "最近添加 · 来自 ${hero.source.source.name}",
                style = WallType.Caption, color = Wall.Text2)
            Text(hero.item.title, style = WallType.Display, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            val meta = listOfNotNull(hero.item.subtitle?.takeIf { it.isNotEmpty() },
                r?.let { "剩余 ${minutesLeft(it.progress.remainingMs)} 分钟" }
                    ?: hero.item.durationMs?.let(::duration))
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = WallType.Caption, color = Wall.Text2, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            }
            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WallButton(if (r != null) "继续播放" else "播放", {
                    nav.play(hero.source, hero.item, r?.progress?.positionMs ?: 0)
                }, icon = WallIcons.Play, kind = ButtonKind.Primary)
                WallButton("详情", { nav.detail(hero.source, hero.item) })
            }
        }
        if (resumes.isNotEmpty()) {
            SectionTitle("继续观看", Modifier.offset(64.dp, 424.dp))
            val row = rememberLazyListState()
            LazyRow(Modifier.offset(y = 482.dp).fillMaxWidth(), state = row,
                flingBehavior = rememberSnapFlingBehavior(row),
                contentPadding = PaddingValues(horizontal = Wall.Margin),
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                items(resumes, key = { "${it.source.source.id}/${it.item.id}" }) { c ->
                    ContinueCard(c.item.title, "剩 ${minutesLeft(c.progress.remainingMs)} 分钟",
                        c.item.backdrop ?: c.item.poster,
                        c.progress.positionMs.toFloat() / c.progress.durationMs,
                        { nav.play(c.source, c.item, c.progress.positionMs) })
                }
            }
        }
    }
}

private fun shelfTitle(src: MediaSource, shelf: Shelf) =
    if (shelf.category.title.isEmpty()) src.source.name else "${src.source.name} · ${shelf.category.title}"

@Composable
private fun ShelfRow(src: MediaSource, shelf: Shelf, nav: WallNav) {
    val title = shelfTitle(src, shelf)
    Column(Modifier.padding(top = 44.dp)) {
        SectionTitle(title, Modifier.padding(start = Wall.Margin)) {
            nav.category(src, shelf.category.id, title, shelf.category.count)
        }
        Spacer(Modifier.height(12.dp))
        val row = rememberLazyListState()
        LazyRow(state = row, flingBehavior = rememberSnapFlingBehavior(row),
            contentPadding = PaddingValues(horizontal = Wall.Margin),
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(shelf.items, key = { it.id }) { item ->
                if (item.isFolder) {
                    FolderTile(item.title, item.childCount?.let { "$it 个" } ?: "文件夹", item.previews,
                        { nav.item(src, item) }, Modifier.size(168.dp, 252.dp))
                } else {
                    Poster(item.title, item.poster, { nav.item(src, item) })
                }
            }
        }
    }
}

@Composable
private fun FailedRow(src: MediaSource, error: Throwable, model: WallModel, nav: WallNav) {
    val setup = error is SourceNeedsSetupException
    Row(Modifier.padding(start = Wall.Margin, top = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionTitle(src.source.name)
        if (setup) {
            Notice(error.message ?: "需要设置", danger = false)
            WallButton("允许访问", { if (src.source.id == LocalVideoSource.ID) nav.storage() else nav.editWebDav() },
                small = true)
        } else {
            Notice("连不上 ${src.source.name}")
            WallButton("重试", { model.refresh(src.source.id) }, icon = WallIcons.Refresh, small = true)
        }
    }
}

@Composable
private fun SkeletonRow() {
    Column(Modifier.padding(start = Wall.Margin, top = 44.dp)) {
        Skeleton(Modifier.size(160.dp, 32.dp))
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            repeat(7) { Skeleton(Modifier.size(168.dp, 252.dp), 14.dp) }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Box(Modifier.fillMaxSize()) {
        Skeleton(Modifier.offset(64.dp, 134.dp).size(220.dp, 26.dp))
        Skeleton(Modifier.offset(64.dp, 178.dp).size(300.dp, 62.dp))
        Skeleton(Modifier.offset(64.dp, 256.dp).size(340.dp, 26.dp))
        Skeleton(Modifier.offset(64.dp, 312.dp).size(236.dp, 64.dp), 16.dp)
        Skeleton(Modifier.offset(316.dp, 312.dp).size(120.dp, 64.dp), 16.dp)
        Skeleton(Modifier.offset(64.dp, 430.dp).size(160.dp, 32.dp))
        for (i in 0 until 5) {
            Skeleton(Modifier.offset((64 + i * 288).dp, 482.dp).size(264.dp, 149.dp), 14.dp)
            Skeleton(Modifier.offset((64 + i * 288).dp, 647.dp).size(150.dp, 26.dp))
        }
    }
}

@Composable
private fun EmptyState(model: WallModel, nav: WallNav) {
    val found by model.discovered.collectAsState()
    val storage = LocalVideoSource.hasPermission(LocalContext.current)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Wall.Margin).offset(y = 148.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有可以看的片子", style = WallType.Title, textAlign = TextAlign.Center)
            Text("添加一个播放源、拷进视频，或者直接投屏与连接电脑。", style = WallType.Body, color = Wall.Text2,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        }
        Row(Modifier.offset(88.dp, 318.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            OptionTile("局域网网盘",
                found?.let { "找到 ${it.substringAfter("://")}（WebDAV）" } ?: "WebDAV，比如电脑上的共享目录",
                WallIcons.Server, { nav.editWebDav(found) }, Modifier.size(352.dp, 216.dp))
            OptionTile("头显里的视频", if (storage) "拷进头显的视频会出现在这里" else "需要允许访问存储",
                WallIcons.Headset, { if (!storage) nav.storage() }, Modifier.size(352.dp, 216.dp))
            OptionTile(
                stringResource(R.string.home_empty_cast_title),
                stringResource(R.string.home_empty_cast_sub),
                WallIcons.Cast,
                { nav.tab(Tab.Cast) },
                Modifier.size(352.dp, 216.dp),
                enabled = true,
            )
        }
    }
}
