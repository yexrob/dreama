package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.local.media.Artworks
import com.limelight.local.media.Item

/**
 * 电影详情 (design-opus movie.html), the part this step needs: what it is,
 * how far in it was left, and play from there or from the start. Full series
 * and rich movie detail expand here in Stage 3.
 */
@Composable
fun DetailScreen(model: WallModel, nav: WallNav, page: Page.Detail) {
    val src = model.source(page.sourceId)
    var detailed by remember(page.sourceId, page.item.id) { mutableStateOf<Item>(page.item) }
    var detailError by remember(page.sourceId, page.item.id) { mutableStateOf<Throwable?>(null) }
    var retryToken by remember(page.sourceId, page.item.id) { mutableIntStateOf(0) }

    LaunchedEffect(src?.source?.id, page.item.id, retryToken) {
        if (src == null) return@LaunchedEffect
        detailError = null
        try {
            detailed = src.detail(page.item.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            detailError = e
        }
    }

    val item = detailed
    val progress by remember(page) { model.progress(page.sourceId, item.id) }.collectAsState(null)
    val resume = progress?.takeIf { it.resumable }

    CompositionLocalProvider(LocalMediaSource provides src) {
        Box(Modifier.fillMaxSize()) {
            ArtBox(item.backdrop ?: item.poster, item.title, Artworks.Size.BACKDROP, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    0f to Wall.Bg.copy(alpha = 0.88f), 0.34f to Wall.Bg.copy(alpha = 0.62f), 0.66f to Color.Transparent)))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Wall.Bg))))
            }
            Box(Modifier.offset(64.dp, 32.dp)) { RoundButton(WallIcons.ChevL, { nav.back() }, filled = true) }
            Column(Modifier.offset(64.dp, 140.dp).width(640.dp)) {
                Text(item.title, style = WallType.Display, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val durText = item.durationMs?.let { formatDuration(it) }
                val meta = listOfNotNull(src?.source?.name, item.year?.toString(), durText,
                    item.subtitle?.takeIf { it.isNotEmpty() })
                Text(meta.joinToString(" · "), style = WallType.Caption, color = Wall.Text2, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                item.overview?.let {
                    Text(it, style = WallType.Body, color = Color(0xFFC4C6CB), maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 14.dp))
                }
                val err = detailError
                when {
                    src == null -> Box(Modifier.padding(top = 32.dp)) { Notice(stringResource(R.string.library_source_missing)) }
                    err != null -> {
                        val presentation = rememberErrorPresentation(err, src)
                        Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Notice(presentation.message, danger = presentation.danger)
                            SourceErrorActionButtons(
                                presentation = presentation,
                                source = src,
                                nav = nav,
                                onRetry = { retryToken++ },
                                onDismiss = { detailError = null },
                            )
                        }
                    }
                    else -> {
                        Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (resume != null) {
                                WallButton(stringResource(R.string.home_action_resume), { nav.play(src, item, resume.positionMs) },
                                    icon = WallIcons.Play, kind = ButtonKind.Primary)
                                WallButton(stringResource(R.string.detail_restart), { nav.play(src, item, 0) }, icon = WallIcons.Restart)
                            } else {
                                WallButton(stringResource(R.string.home_action_play), { nav.play(src, item, 0) }, icon = WallIcons.Play, kind = ButtonKind.Primary)
                            }
                        }
                    }
                }
                if (resume != null) {
                    Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.width(200.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.22f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(resume.positionMs.toFloat() / resume.durationMs)
                                .clip(RoundedCornerShape(3.dp)).background(Wall.Accent))
                        }
                        Text(
                            stringResource(R.string.home_hero_remaining_min, minutesLeft(resume.remainingMs)),
                            style = WallType.Caption,
                            color = Wall.Text2,
                        )
                    }
                }
            }
        }
    }
}
