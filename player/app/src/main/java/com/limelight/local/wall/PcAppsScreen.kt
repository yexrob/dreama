package com.limelight.local.wall

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.limelight.R
import com.limelight.local.stream.PcAppEntry
import com.limelight.local.stream.PcAppsState
import com.limelight.local.stream.StreamingComputer
import com.limelight.local.stream.StreamingController

/**
 * 6-column poster grid of streamable PC apps, matching [GridScreen] geometry and hover lift.
 */
@Composable
fun PcAppsScreen(model: WallModel, nav: WallNav, page: Page.PcApps) {
    val streaming = model.streaming
    val state by streaming.pcApps.collectAsState()
    LaunchedEffect(page.computer.id) {
        streaming.loadApps(page.computer)
    }

    val cellW = 172.dp
    val cellH = 258.dp
    val readyApps = (state as? PcAppsState.Ready)?.apps
    val hasRunning = readyApps?.any { it.isRunning } == true

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            PcAppsState.Loading -> {
                Row(
                    Modifier.offset(64.dp, 128.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    repeat(6) { Skeleton(Modifier.size(cellW, cellH), Wall.RadiusPoster) }
                }
            }
            is PcAppsState.Failed -> {
                Column(Modifier.offset(64.dp, 160.dp).width(660.dp)) {
                    Icon(WallIcons.Alert, null, Modifier.size(48.dp), tint = Wall.Danger)
                    Text(s.error.title, style = WallType.Head, modifier = Modifier.padding(top = 20.dp))
                    Text(s.error.detail, style = WallType.Body, color = Wall.Text2, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WallButton(
                            stringResource(R.string.action_retry),
                            { streaming.loadApps(page.computer) },
                            icon = WallIcons.Refresh,
                            kind = ButtonKind.Primary,
                        )
                        WallButton(stringResource(R.string.action_back), { nav.back() })
                    }
                }
            }
            is PcAppsState.Ready -> {
                if (s.apps.isEmpty()) {
                    Text(
                        stringResource(R.string.pc_apps_empty),
                        style = WallType.Body,
                        color = Wall.Text2,
                        modifier = Modifier.offset(64.dp, 160.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(6),
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Wall.Margin, end = Wall.Margin, top = 128.dp, bottom = 64.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(26.dp),
                    ) {
                        items(s.apps, key = { it.app.appId }) { entry ->
                            PcAppPoster(
                                computer = s.computer,
                                entry = entry,
                                streaming = streaming,
                                onClick = {
                                    nav.tab(Tab.Cast)
                                    nav.connectComputer(s.computer, entry.app)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Solid header matching GridScreen
        Box(Modifier.fillMaxWidth().height(112.dp).background(Wall.Bg))
        Box(Modifier.offset(64.dp, 32.dp)) { RoundButton(WallIcons.ChevL, { nav.back() }, filled = true) }
        Row(
            Modifier.offset(152.dp, 32.dp).height(64.dp).width(760.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                page.computer.name,
                style = WallType.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            readyApps?.let {
                Text(stringResource(R.string.pc_apps_count, it.size), style = WallType.Caption, color = Wall.Text2)
            }
        }
        if (hasRunning) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = Wall.Margin)) {
                WallButton(
                    stringResource(R.string.pc_apps_quit_running),
                    {
                        streaming.quitRunningApp(page.computer) {
                            streaming.loadApps(page.computer)
                        }
                    },
                    icon = WallIcons.Power,
                    small = true,
                )
            }
        }
    }
}

private val AppPosterShape = RoundedCornerShape(Wall.RadiusPoster)
private val APP_PALETTES = listOf(
    listOf(Color(0xFF283542), Color(0xFF1A232D)),
    listOf(Color(0xFF3A2D46), Color(0xFF221A2B)),
    listOf(Color(0xFF1F3832), Color(0xFF132420)),
    listOf(Color(0xFF473128), Color(0xFF2B1D17)),
    listOf(Color(0xFF2B3249), Color(0xFF181D2B)),
)

@Composable
private fun PcAppPoster(
    computer: StreamingComputer,
    entry: PcAppEntry,
    streaming: StreamingController,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val scale by animateFloatAsState(if (hovered) 1.06f else 1f, tween(140, easing = Wall.Ease), label = "app-p")
    val bmp by produceState<Bitmap?>(null, computer.id, entry.app.appId) {
        value = streaming.loadBoxArt(computer, entry.app)
    }
    val palette = APP_PALETTES[Math.floorMod(entry.app.appName.hashCode(), APP_PALETTES.size)]

    Box(
        Modifier
            .width(172.dp)
            .height(258.dp)
            .zIndex(if (scale > 1f) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = (scale - 1f) / 0.06f * 24.dp.toPx()
                shape = AppPosterShape
                clip = true
            }
            .background(Brush.verticalGradient(palette))
            .ray(source, onClick),
    ) {
        AnimatedVisibility(bmp != null, enter = fadeIn(tween(240)), exit = fadeOut(tween(0))) {
            bmp?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Wall.Bg.copy(alpha = 0.86f)),
            ),
        )
        if (entry.isRunning) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Wall.Accent)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.pc_apps_running_badge),
                    style = WallType.Caption.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                    color = Wall.Bg,
                )
            }
        }
        Text(
            entry.app.appName,
            style = WallType.Head.copy(fontSize = 24.sp, lineHeight = 30.sp),
            color = Wall.PosterText,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 18.dp),
        )
    }
}
