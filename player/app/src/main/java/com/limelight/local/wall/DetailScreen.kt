package com.limelight.local.wall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.limelight.R
import com.limelight.local.media.Artworks
import com.limelight.local.media.Item
import com.limelight.local.media.MediaSource
import com.limelight.local.media.WatchProgress
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.stream.AndroidStringLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Movie & Series Detail screens strictly aligned with `design-opus/mockups/movie.html` and
 * `design-opus/mockups/series.html`:
 * - Movies / standalone videos: full-bleed backdrop, title, metadata, overview, Play/Resume/Restart,
 *   optional Favorite & Played toggles (gated by plugin manifest capabilities), progress strip, and
 *   bottom metadata cards.
 * - Series / multi-part items: 480dp backdrop, smart primary CTA ("继续播放 · 第 X 季第 Y 集"),
 *   season tabs with immediate visual switch + episode skeleton, lazily paginated horizontal episode
 *   cards with watched/in-progress/unwatched states.
 * - Folders continue routing to `GridScreen` via `WallNav.item(...)`.
 */
@Composable
fun DetailScreen(model: WallModel, nav: WallNav, page: Page.Detail) {
    val src = model.source(page.sourceId)
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()

    var detailed by remember(page.sourceId, page.item.id) { mutableStateOf(page.item) }
    var loadingDetail by remember(page.sourceId, page.item.id) { mutableStateOf(src != null) }
    var detailError by remember(page.sourceId, page.item.id) { mutableStateOf<Throwable?>(null) }
    var retryToken by remember(page.sourceId, page.item.id) { mutableIntStateOf(0) }
    var actionNotice by remember(page.sourceId, page.item.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(src?.source?.id, page.item.id, retryToken) {
        if (src == null) {
            loadingDetail = false
            return@LaunchedEffect
        }
        loadingDetail = true
        detailError = null
        try {
            detailed = src.detail(page.item.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            detailError = e
        } finally {
            loadingDetail = false
        }
    }

    val item = detailed
    val canFavorite = remember(src) { DetailStateLogic.supportsFavorites(src) }
    val canPlayed = remember(src) { DetailStateLogic.supportsPlayed(src) }
    var favoriteBusy by remember(page.sourceId, page.item.id) { mutableStateOf(false) }
    var playedBusy by remember(page.sourceId, page.item.id) { mutableStateOf(false) }

    fun toggleFavorite() {
        val pluginSrc = src as? PluginMediaSource ?: return
        if (favoriteBusy) return
        val prev = item.favorite == true
        val next = !prev
        favoriteBusy = true
        actionNotice = null
        detailed = item.copy(favorite = next)
        scope.launch {
            try {
                pluginSrc.setFavorite(item.id, next)
                actionNotice = context.getString(
                    if (next) R.string.detail_favorite_added else R.string.detail_favorite_removed,
                )
            } catch (e: CancellationException) {
                detailed = item.copy(favorite = prev)
                throw e
            } catch (e: Exception) {
                detailed = item.copy(favorite = prev)
                actionNotice = SourceErrorDispatcher.dispatch(
                    error = e,
                    sourceKind = pluginSrc.source.kind,
                    sourceName = pluginSrc.source.name,
                    strings = strings,
                ).message
            } finally {
                favoriteBusy = false
            }
        }
    }

    fun togglePlayed() {
        val pluginSrc = src as? PluginMediaSource ?: return
        if (playedBusy) return
        val prev = item.played == true
        val next = !prev
        playedBusy = true
        actionNotice = null
        detailed = item.copy(played = next)
        scope.launch {
            try {
                pluginSrc.setPlayed(item.id, next)
                actionNotice = context.getString(
                    if (next) R.string.detail_played_marked else R.string.detail_played_unmarked,
                )
            } catch (e: CancellationException) {
                detailed = item.copy(played = prev)
                throw e
            } catch (e: Exception) {
                detailed = item.copy(played = prev)
                actionNotice = SourceErrorDispatcher.dispatch(
                    error = e,
                    sourceKind = pluginSrc.source.kind,
                    sourceName = pluginSrc.source.name,
                    strings = strings,
                ).message
            } finally {
                playedBusy = false
            }
        }
    }

    CompositionLocalProvider(LocalMediaSource provides src) {
        if (DetailStateLogic.isSeriesLayout(item)) {
            SeriesDetailContent(
                item = item,
                source = src,
                model = model,
                nav = nav,
                loadingDetail = loadingDetail,
                detailError = detailError,
                actionNotice = actionNotice,
                canFavorite = canFavorite,
                canPlayed = canPlayed,
                favoriteBusy = favoriteBusy,
                playedBusy = playedBusy,
                onToggleFavorite = ::toggleFavorite,
                onTogglePlayed = ::togglePlayed,
                onRetryDetail = { retryToken++ },
                onDismissError = { detailError = null },
                strings = strings,
            )
        } else {
            MovieDetailContent(
                item = item,
                source = src,
                model = model,
                nav = nav,
                loadingDetail = loadingDetail,
                detailError = detailError,
                actionNotice = actionNotice,
                canFavorite = canFavorite,
                canPlayed = canPlayed,
                favoriteBusy = favoriteBusy,
                playedBusy = playedBusy,
                onToggleFavorite = ::toggleFavorite,
                onTogglePlayed = ::togglePlayed,
                onRetryDetail = { retryToken++ },
                onDismissError = { detailError = null },
                strings = strings,
            )
        }
    }
}

@Composable
private fun MovieDetailContent(
    item: Item,
    source: MediaSource?,
    model: WallModel,
    nav: WallNav,
    loadingDetail: Boolean,
    detailError: Throwable?,
    actionNotice: String?,
    canFavorite: Boolean,
    canPlayed: Boolean,
    favoriteBusy: Boolean,
    playedBusy: Boolean,
    onToggleFavorite: () -> Unit,
    onTogglePlayed: () -> Unit,
    onRetryDetail: () -> Unit,
    onDismissError: () -> Unit,
    strings: AndroidStringLookup,
) {
    val localProgress by remember(source?.source?.id, item.id) {
        model.progress(source?.source?.id.orEmpty(), item.id)
    }.collectAsState(null)
    val watchState = remember(item, localProgress) {
        DetailStateLogic.computeWatchState(item, localProgress)
    }
    val inProgress = watchState as? WatchState.InProgress
    val metaText = remember(item, source?.source?.name) {
        DetailStateLogic.formatMovieMeta(item, source?.source?.name, strings)
    }
    val infoCards = remember(item, source?.source?.name) {
        DetailStateLogic.buildMovieInfoCards(item, source?.source?.name, strings)
    }

    Box(Modifier.fillMaxSize()) {
        ArtBox(
            art = item.backdrop ?: item.poster ?: item.thumb,
            seed = item.title,
            size = Artworks.Size.BACKDROP,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Wall.Bg.copy(alpha = 0.88f),
                            0.34f to Wall.Bg.copy(alpha = 0.62f),
                            0.66f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Wall.Bg))),
            )
        }

        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }

        Column(Modifier.offset(64.dp, 140.dp).width(720.dp)) {
            Text(
                item.title,
                style = WallType.Display,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaText.isNotEmpty()) {
                Text(
                    metaText,
                    style = WallType.Caption,
                    color = Wall.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    overview,
                    style = WallType.Body,
                    color = Color(0xFFC4C6CB),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            if (source == null) {
                Box(Modifier.padding(top = 32.dp)) {
                    Notice(stringResource(R.string.library_source_missing))
                }
            } else {
                Row(
                    Modifier.padding(top = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loadingDetail && detailError == null) {
                        WallButton(
                            text = stringResource(R.string.detail_preparing),
                            onClick = {},
                            kind = ButtonKind.Primary,
                            enabled = false,
                        )
                    } else if (inProgress != null) {
                        WallButton(
                            text = stringResource(R.string.home_action_resume),
                            onClick = { nav.play(source, item, inProgress.positionMs) },
                            icon = WallIcons.Play,
                            kind = ButtonKind.Primary,
                        )
                        WallButton(
                            text = stringResource(R.string.detail_restart),
                            onClick = { nav.play(source, item, 0L) },
                            icon = WallIcons.Restart,
                        )
                    } else {
                        WallButton(
                            text = stringResource(R.string.home_action_play),
                            onClick = { nav.play(source, item, 0L) },
                            icon = WallIcons.Play,
                            kind = ButtonKind.Primary,
                        )
                    }

                    if (canFavorite) {
                        FavoriteButton(
                            favorited = item.favorite == true,
                            enabled = !favoriteBusy,
                            onClick = onToggleFavorite,
                        )
                    }
                    if (canPlayed) {
                        val isPlayed = item.played == true || watchState is WatchState.Watched
                        WallButton(
                            text = stringResource(if (isPlayed) R.string.detail_marked_played else R.string.detail_mark_played),
                            onClick = onTogglePlayed,
                            icon = WallIcons.Check,
                            kind = if (isPlayed) ButtonKind.Plain else ButtonKind.Ghost,
                            small = true,
                            enabled = !playedBusy,
                        )
                    }
                }
            }

            if (inProgress != null) {
                Row(
                    Modifier.padding(top = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier
                            .width(200.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.22f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(inProgress.fraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Wall.Accent),
                        )
                    }
                    Text(
                        stringResource(R.string.home_hero_remaining_min, minutesLeft(inProgress.remainingMs)),
                        style = WallType.Caption,
                        color = Wall.Text2,
                    )
                }
            }

            if (detailError != null && source != null) {
                val presentation = rememberErrorPresentation(detailError, source)
                Row(
                    Modifier.padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Notice(presentation.message, danger = presentation.danger)
                    SourceErrorActionButtons(
                        presentation = presentation,
                        source = source,
                        nav = nav,
                        onRetry = onRetryDetail,
                        onDismiss = onDismissError,
                        small = true,
                        primaryAsWhite = false,
                    )
                }
            } else if (actionNotice != null) {
                Box(Modifier.padding(top = 18.dp)) {
                    Notice(actionNotice, icon = WallIcons.Check, danger = false)
                }
            }
        }

        // Bottom metadata bar matching movie.html (.vbtn row at top: 584px)
        Row(
            Modifier
                .offset(64.dp, 584.dp)
                .width(1152.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loadingDetail && infoCards.size <= 1) {
                repeat(4) {
                    Skeleton(Modifier.weight(1f).height(96.dp), Wall.Radius)
                }
            } else {
                for ((label, value) in infoCards) {
                    Column(
                        Modifier
                            .weight(1f)
                            .height(96.dp)
                            .clip(RoundedCornerShape(Wall.Radius))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 22.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(label, style = WallType.Caption, color = Wall.Text2, maxLines = 1)
                        Text(
                            value,
                            style = WallType.Label,
                            color = Wall.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailContent(
    item: Item,
    source: MediaSource?,
    model: WallModel,
    nav: WallNav,
    loadingDetail: Boolean,
    detailError: Throwable?,
    actionNotice: String?,
    canFavorite: Boolean,
    canPlayed: Boolean,
    favoriteBusy: Boolean,
    playedBusy: Boolean,
    onToggleFavorite: () -> Unit,
    onTogglePlayed: () -> Unit,
    onRetryDetail: () -> Unit,
    onDismissError: () -> Unit,
    strings: AndroidStringLookup,
) {
    val seasons = item.seasons
    val hasSeasons = seasons.isNotEmpty()
    val localProgress by remember(source?.source?.id) {
        model.progressMapForSource(source?.source?.id.orEmpty())
    }.collectAsState(emptyMap())

    var selectedSeasonId by remember(item.id, seasons.size) {
        mutableStateOf(seasons.firstOrNull()?.id ?: SERIES_DIRECT_SEASON_ID)
    }
    LaunchedEffect(seasons) {
        if (seasons.isNotEmpty() && seasons.none { it.id == selectedSeasonId }) {
            selectedSeasonId = seasons.first().id
        }
    }

    // Cache one SeasonEpisodeLoader per season so switching back is instant while new seasons load lazily
    val loaders = remember(source?.source?.id, item.id) { mutableStateMapOf<String, SeasonEpisodeLoader>() }
    val pageStates = remember(source?.source?.id, item.id) { mutableStateMapOf<String, SeasonEpisodePageState>() }
    var episodeRetryToken by remember(item.id) { mutableIntStateOf(0) }

    val activeSeasonIndex = remember(seasons, selectedSeasonId) {
        seasons.indexOfFirst { it.id == selectedSeasonId }.takeIf { it >= 0 }
    }
    val activeSeasonNumber = remember(seasons, activeSeasonIndex) {
        activeSeasonIndex?.let { idx ->
            seasons[idx].index?.takeIf { it > 0 } ?: (idx + 1)
        }
    }

    LaunchedEffect(source?.source?.id, selectedSeasonId, item.episodes.size, episodeRetryToken) {
        if (source == null) return@LaunchedEffect
        val key = selectedSeasonId
        val loader = loaders.getOrPut(key) {
            val preloaded = if (!hasSeasons) item.episodes else emptyList()
            SeasonEpisodeLoader(source, key, preloaded)
        }
        pageStates[key] = loader.state
        if (!loader.state.loadedOnce && loader.state.nextPage != null) {
            pageStates[key] = loader.state.copy(loading = true, error = null)
            pageStates[key] = loader.loadNextPage()
        }
    }

    val activeState = pageStates[selectedSeasonId] ?: SeasonEpisodePageState(loading = source != null)
    val episodes = activeState.items

    val playTarget = remember(activeSeasonNumber, episodes, localProgress) {
        DetailStateLogic.pickPlayTarget(activeSeasonNumber, episodes, localProgress)
    }
    val episodeRowState = rememberLazyListState()

    // Auto-scroll to the next episode with 1 watched episode to its left (series.html)
    var scrolledSeasonKey by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedSeasonId, episodes.isNotEmpty(), playTarget?.episode?.id) {
        if (episodes.isNotEmpty() && scrolledSeasonKey != selectedSeasonId) {
            scrolledSeasonKey = selectedSeasonId
            val targetIdx = DetailStateLogic.initialScrollIndex(episodes, playTarget?.episode?.id)
            if (targetIdx > 0) {
                episodeRowState.scrollToItem(targetIdx)
            } else {
                episodeRowState.scrollToItem(0)
            }
        }
    }

    // Lazy pagination trigger as user scrolls near the right end of the episode strip
    val nearEnd by remember(episodeRowState) {
        derivedStateOf {
            val last = episodeRowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = episodeRowState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(selectedSeasonId, source?.source?.id) {
        snapshotFlow {
            val st = pageStates[selectedSeasonId]
            if (nearEnd && st != null && st.nextPage != null && !st.loading && st.items.isNotEmpty()) {
                st.items.size
            } else {
                -1
            }
        }.filter { it >= 0 }.collect {
            val loader = loaders[selectedSeasonId] ?: return@collect
            pageStates[selectedSeasonId] = loader.state.copy(loading = true, error = null)
            pageStates[selectedSeasonId] = loader.loadNextPage()
        }
    }

    val metaText = remember(item, seasons.size, source?.source?.name) {
        DetailStateLogic.formatSeriesMeta(item, seasons.size, source?.source?.name, strings)
    }

    Box(Modifier.fillMaxSize()) {
        // 480dp backdrop matching series.html
        ArtBox(
            art = item.backdrop ?: item.poster ?: item.thumb,
            seed = item.title,
            size = Artworks.Size.BACKDROP,
            modifier = Modifier.fillMaxWidth().height(480.dp),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Wall.Bg.copy(alpha = 0.88f),
                            0.34f to Wall.Bg.copy(alpha = 0.62f),
                            0.66f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Wall.Bg))),
            )
        }

        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }

        // Header metadata & primary CTA matching series.html (left:64px, top:112px, width:820px)
        Column(Modifier.offset(64.dp, 112.dp).width(840.dp)) {
            Text(
                item.title,
                style = WallType.Display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaText.isNotEmpty()) {
                Text(
                    metaText,
                    style = WallType.Caption,
                    color = Wall.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    overview,
                    style = WallType.Body,
                    color = Color(0xFFC4C6CB),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (source == null) {
                Box(Modifier.padding(top = 26.dp)) {
                    Notice(stringResource(R.string.library_source_missing))
                }
            } else {
                Row(
                    Modifier.padding(top = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val primaryText = playTarget?.let {
                        DetailStateLogic.formatPrimarySeriesButton(it, hasSeasons, strings)
                    } ?: stringResource(if (loadingDetail) R.string.detail_preparing else R.string.home_action_play)

                    WallButton(
                        text = primaryText,
                        onClick = {
                            val target = playTarget ?: return@WallButton
                            nav.play(source, target.episode, target.resumePositionMs)
                        },
                        icon = WallIcons.Play,
                        kind = ButtonKind.Primary,
                        enabled = playTarget != null,
                    )

                    if (canFavorite) {
                        FavoriteButton(
                            favorited = item.favorite == true,
                            enabled = !favoriteBusy,
                            onClick = onToggleFavorite,
                        )
                    }
                    if (canPlayed) {
                        val isPlayed = item.played == true
                        WallButton(
                            text = stringResource(if (isPlayed) R.string.detail_marked_played else R.string.detail_mark_played),
                            onClick = onTogglePlayed,
                            icon = WallIcons.Check,
                            kind = if (isPlayed) ButtonKind.Plain else ButtonKind.Ghost,
                            small = true,
                            enabled = !playedBusy,
                        )
                    }
                }
            }

            if (detailError != null && source != null) {
                val presentation = rememberErrorPresentation(detailError, source)
                Row(
                    Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Notice(presentation.message, danger = presentation.danger)
                    SourceErrorActionButtons(
                        presentation = presentation,
                        source = source,
                        nav = nav,
                        onRetry = onRetryDetail,
                        onDismiss = onDismissError,
                        small = true,
                        primaryAsWhite = false,
                    )
                }
            } else if (actionNotice != null) {
                Box(Modifier.padding(top = 14.dp)) {
                    Notice(actionNotice, icon = WallIcons.Check, danger = false)
                }
            }
        }

        // Season tabs matching series.html (left:38px, top:404px)
        LazyRow(
            Modifier.offset(y = 404.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasSeasons) {
                itemsIndexed(seasons, key = { _, s -> s.id }) { idx, season ->
                    val label = season.title.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.series_season_tab, season.index ?: (idx + 1))
                    SeasonTabChip(
                        label = label,
                        selected = season.id == selectedSeasonId,
                        onClick = {
                            if (selectedSeasonId != season.id) {
                                selectedSeasonId = season.id
                            }
                        },
                    )
                }
            } else {
                item("parts") {
                    SeasonTabChip(
                        label = stringResource(R.string.series_parts_tab),
                        selected = true,
                        onClick = {},
                    )
                }
            }
        }

        // Episode horizontal strip matching series.html (top:488px)
        Box(Modifier.offset(y = 488.dp).fillMaxWidth().height(216.dp)) {
            val epErr = activeState.error
            when {
                source == null -> Unit
                epErr != null && episodes.isEmpty() -> {
                    val presentation = rememberErrorPresentation(epErr, source)
                    Row(
                        Modifier.padding(horizontal = Wall.Margin, vertical = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Notice(presentation.message, danger = presentation.danger)
                        SourceErrorActionButtons(
                            presentation = presentation,
                            source = source,
                            nav = nav,
                            onRetry = {
                                loaders[selectedSeasonId]?.resetForRetry()
                                pageStates.remove(selectedSeasonId)
                                episodeRetryToken++
                            },
                            small = true,
                            primaryAsWhite = false,
                        )
                    }
                }
                episodes.isEmpty() && (activeState.loading || loadingDetail) -> {
                    Row(
                        Modifier.padding(horizontal = Wall.Margin),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        repeat(5) {
                            Column(Modifier.width(264.dp)) {
                                Skeleton(Modifier.fillMaxWidth().height(149.dp), Wall.RadiusPoster)
                                Spacer(Modifier.height(16.dp))
                                Skeleton(Modifier.size(160.dp, 26.dp), 12.dp)
                            }
                        }
                    }
                }
                episodes.isEmpty() -> {
                    Text(
                        stringResource(R.string.series_episodes_empty),
                        style = WallType.Body,
                        color = Wall.Text2,
                        modifier = Modifier.padding(horizontal = Wall.Margin, vertical = 28.dp),
                    )
                }
                else -> {
                    LazyRow(
                        state = episodeRowState,
                        flingBehavior = rememberSnapFlingBehavior(episodeRowState),
                        contentPadding = PaddingValues(horizontal = Wall.Margin),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(episodes, key = { _, ep -> ep.id }) { idx, ep ->
                            val state = DetailStateLogic.computeWatchState(ep, localProgress[ep.id])
                            val epNum = ep.index?.takeIf { it > 0 } ?: (idx + 1)
                            val metaLabel = when (state) {
                                WatchState.Watched -> stringResource(R.string.series_episode_watched)
                                is WatchState.InProgress -> stringResource(
                                    R.string.home_continue_left_min,
                                    minutesLeft(state.remainingMs),
                                )
                                WatchState.Unwatched -> ep.durationMs?.takeIf { it > 0L }?.let { formatDuration(it) }.orEmpty()
                            }
                            EpisodeCard(
                                indexNumber = epNum,
                                title = ep.title,
                                meta = metaLabel,
                                art = ep.thumb ?: ep.backdrop ?: ep.poster ?: item.backdrop,
                                progressFraction = (state as? WatchState.InProgress)?.fraction,
                                onClick = {
                                    val startAt = (state as? WatchState.InProgress)?.positionMs ?: 0L
                                    nav.play(source, ep, startAt)
                                },
                            )
                        }
                        if (activeState.loading && activeState.nextPage != null) {
                            item("loading-more") {
                                Column(Modifier.width(264.dp)) {
                                    Skeleton(Modifier.fillMaxWidth().height(149.dp), Wall.RadiusPoster)
                                    Spacer(Modifier.height(16.dp))
                                    Skeleton(Modifier.size(140.dp, 26.dp), 12.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteButton(favorited: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(interaction)
    val scale by animateFloatAsState(
        if (hovered && enabled) 1.06f else 1f,
        tween(120, easing = Wall.Ease),
        label = "fav-btn",
    )
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .size(64.dp)
            .clip(CircleShape)
            .background(if (hovered && enabled) Wall.FillHi else if (favorited) Wall.Sel else Wall.Fill)
            .then(if (enabled) Modifier.ray(interaction, onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (favorited) WallIcons.HeartFilled else WallIcons.Heart,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = if (favorited) Wall.Accent else Wall.Text,
        )
    }
}

@Composable
private fun SeasonTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(interaction)
    val bg = when {
        selected -> Wall.Sel
        hovered -> Wall.Fill
        else -> Color.Transparent
    }
    Box(
        Modifier
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(bg)
            .ray(interaction, onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = WallType.Label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected || hovered) Wall.Text else Wall.Text2,
            maxLines = 1,
        )
    }
}

private val EpisodeCardShape = RoundedCornerShape(Wall.RadiusPoster)

@Composable
private fun EpisodeCard(
    indexNumber: Int,
    title: String,
    meta: String,
    art: com.limelight.local.media.Artwork?,
    progressFraction: Float?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(interaction)
    val scale by animateFloatAsState(
        if (hovered) 1.06f else 1f,
        tween(140, easing = Wall.Ease),
        label = "ep-card",
    )
    Column(Modifier.width(264.dp).ray(interaction, onClick)) {
        ArtBox(
            art = art,
            seed = "$indexNumber-$title",
            size = Artworks.Size.CARD,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .zIndex(if (scale > 1f) 1f else 0f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = (scale - 1f) / 0.06f * 24.dp.toPx()
                    shape = EpisodeCardShape
                    clip = true
                },
        ) {
            if (progressFraction != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x990E0F12)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction.coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(Wall.Accent),
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = hovered,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(140)),
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xEBECECEE)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(WallIcons.Play, null, Modifier.size(28.dp).offset(x = 2.dp), tint = Wall.Bg)
                }
            }
        }
        Row(
            Modifier.padding(top = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Wall.Text2, fontWeight = FontWeight.Medium)) {
                        append("$indexNumber  ")
                    }
                    withStyle(SpanStyle(color = Wall.Text, fontWeight = FontWeight.SemiBold)) {
                        append(title)
                    }
                },
                style = WallType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (meta.isNotEmpty()) {
                Text(meta, style = WallType.Caption, color = Wall.Text2, maxLines = 1)
            }
        }
    }
}

private const val SERIES_DIRECT_SEASON_ID = "__direct_episodes__"
