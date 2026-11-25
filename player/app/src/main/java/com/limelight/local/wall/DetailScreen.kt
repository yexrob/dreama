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
import androidx.compose.foundation.layout.heightIn
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
                    needsPermissionReconfirm = model.needsPermissionReconfirm(pluginSrc),
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
        val prevProgressMs = item.progressMs
        val next = !prev
        playedBusy = true
        actionNotice = null
        detailed = item.copy(played = next, progressMs = if (next) null else prevProgressMs)
        scope.launch {
            try {
                detailed = DetailStateLogic.applyPlayedToggle(
                    item = item,
                    nextPlayed = next,
                    setRemotePlayed = { played -> pluginSrc.setPlayed(item.id, played) },
                    clearLocalProgress = { model.clearProgress(pluginSrc.source.id, item.id) },
                )
                actionNotice = context.getString(
                    if (next) R.string.detail_played_marked else R.string.detail_played_unmarked,
                )
            } catch (e: CancellationException) {
                detailed = item.copy(played = prev, progressMs = prevProgressMs)
                throw e
            } catch (e: Exception) {
                detailed = item.copy(played = prev, progressMs = prevProgressMs)
                actionNotice = SourceErrorDispatcher.dispatch(
                    error = e,
                    sourceKind = pluginSrc.source.kind,
                    sourceName = pluginSrc.source.name,
                    strings = strings,
                    needsPermissionReconfirm = model.needsPermissionReconfirm(pluginSrc),
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
                val presentation = rememberErrorPresentation(
                    error = detailError,
                    source = source,
                    needsPermissionReconfirm = model.needsPermissionReconfirm(source),
                )
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
    val seasons = remember(item.seasons) { DetailStateLogic.orderSeasons(item.seasons) }
    val hasSeasons = seasons.isNotEmpty()
    val scope = rememberCoroutineScope()
    val localProgressNullable by remember(source?.source?.id) {
        model.progressMapForSource(source?.source?.id.orEmpty())
    }.collectAsState(null)
    val localProgress = localProgressNullable.orEmpty()

    var selectedSeasonId by remember(item.id) {
        mutableStateOf(DetailStateLogic.defaultSeason(seasons)?.id ?: SERIES_DIRECT_SEASON_ID)
    }
    var userSelectedSeason by remember(item.id) { mutableStateOf(false) }
    var initialResolution by remember(item.id) { mutableStateOf<SeriesInitialResolution?>(null) }

    // Cache one SeasonEpisodeLoader per season via SeriesLoaderCoordinator (#8):
    // never creates a loader or triggers browse() before detail() completes, and replaces
    // the direct-episodes loader whenever preloaded item.episodes updates.
    val coordinator = remember(source?.source?.id, item.id) {
        source?.let { SeriesLoaderCoordinator(it) }
    }
    val pageStates = remember(source?.source?.id, item.id) { mutableStateMapOf<String, SeasonEpisodePageState>() }
    var episodeRetryToken by remember(item.id) { mutableIntStateOf(0) }

    suspend fun ensureSeasonEpisodesLoaded(seasonId: String): List<Item> {
        val coord = coordinator ?: return emptyList()
        val loader = coord.loaderFor(
            seasonId = seasonId,
            hasSeasons = true,
            loadingDetail = false,
            preloadedEpisodes = emptyList(),
        ) ?: return emptyList()
        pageStates[seasonId] = loader.state
        if (!loader.state.loadedOnce && loader.state.nextPage != null) {
            pageStates[seasonId] = loader.state.copy(loading = true, error = null)
            try {
                loader.ensureLoaded()
            } finally {
                // Always write back loader.state even on cancellation so loading never sticks at true (#3)
                pageStates[seasonId] = loader.state
            }
        }
        return loader.state.items
    }

    suspend fun loadMoreSeasonEpisodes(seasonId: String): List<Item> {
        val loader = coordinator?.existingLoader(seasonId) ?: return emptyList()
        if (loader.state.nextPage == null) return loader.state.items
        pageStates[seasonId] = loader.state.copy(loading = true, error = null)
        try {
            loader.loadNextPage()
        } finally {
            pageStates[seasonId] = loader.state
        }
        return loader.state.items
    }

    // Cross-season initial default season & play target (#3, #5, #10):
    // matches only parentId hitting this series within a 2s overall timeout, skips Specials by default,
    // and shares mutex-serialized loader.ensureLoaded() so concurrent effects never observe empty items.
    LaunchedEffect(source?.source?.id, item.id, seasons, loadingDetail, localProgressNullable) {
        if (source == null || loadingDetail) return@LaunchedEffect
        if (!hasSeasons) {
            selectedSeasonId = SERIES_DIRECT_SEASON_ID
            initialResolution = null
            return@LaunchedEffect
        }
        val progressMap = localProgressNullable ?: return@LaunchedEffect
        val fallbackSeasonId = DetailStateLogic.defaultSeason(seasons)?.id ?: seasons.first().id
        if (seasons.none { it.id == selectedSeasonId }) {
            selectedSeasonId = fallbackSeasonId
        }
        val resolved = DetailStateLogic.resolveInitialSeriesSeasonAndTarget(
            source = source,
            seasons = seasons,
            localProgress = progressMap,
            loadSeasonEpisodes = { sid -> ensureSeasonEpisodesLoaded(sid) },
            loadMoreInSeason = { sid -> loadMoreSeasonEpisodes(sid) },
            knownParentIds = model.knownParentIdsForSource(source.source.id),
        )
        initialResolution = resolved
        if (resolved != null && !userSelectedSeason) {
            selectedSeasonId = resolved.seasonId
        }
    }

    val activeSeasonIndex = remember(seasons, selectedSeasonId) {
        seasons.indexOfFirst { it.id == selectedSeasonId }.takeIf { it >= 0 }
    }
    val activeSeasonNumber = remember(seasons, activeSeasonIndex) {
        activeSeasonIndex?.let { idx -> DetailStateLogic.seasonNumberFor(seasons, idx) }
    }

    val directEpisodeIds = remember(item.episodes) { item.episodes.map { it.id } }
    LaunchedEffect(source?.source?.id, selectedSeasonId, hasSeasons, loadingDetail, directEpisodeIds, episodeRetryToken) {
        val coord = coordinator ?: return@LaunchedEffect
        if (loadingDetail) return@LaunchedEffect
        val key = if (hasSeasons) selectedSeasonId else SERIES_DIRECT_SEASON_ID
        val loader = coord.loaderFor(
            seasonId = key,
            hasSeasons = hasSeasons,
            loadingDetail = false,
            preloadedEpisodes = if (!hasSeasons) item.episodes else emptyList(),
        ) ?: return@LaunchedEffect
        pageStates[key] = loader.state
        if (!loader.state.loadedOnce && loader.state.nextPage != null) {
            pageStates[key] = loader.state.copy(loading = true, error = null)
            try {
                loader.ensureLoaded()
            } finally {
                pageStates[key] = loader.state
            }
        }
    }

    val activeKey = if (hasSeasons) selectedSeasonId else SERIES_DIRECT_SEASON_ID
    val activeState = pageStates[activeKey] ?: SeasonEpisodePageState(loading = source != null)
    val episodes = activeState.items

    val playTarget = remember(activeKey, activeSeasonNumber, episodes, localProgress, initialResolution) {
        val res = initialResolution
        if (hasSeasons && res != null && res.seasonId == activeKey && res.target != null) {
            res.target
        } else {
            DetailStateLogic.pickPlayTarget(activeSeasonNumber, episodes, localProgress)
        }
    }
    val episodeRowState = rememberLazyListState()

    // Auto-scroll to the next episode with 1 watched episode to its left (series.html)
    var scrolledSeasonKey by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeKey, episodes.isNotEmpty(), playTarget?.episode?.id) {
        if (episodes.isNotEmpty() && scrolledSeasonKey != activeKey) {
            scrolledSeasonKey = activeKey
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
    LaunchedEffect(activeKey, source?.source?.id) {
        snapshotFlow {
            val st = pageStates[activeKey]
            if (nearEnd && st != null && st.nextPage != null && !st.loading && st.error == null && st.items.isNotEmpty()) {
                st.items.size
            } else {
                -1
            }
        }.filter { it >= 0 }.collect {
            val loader = coordinator?.existingLoader(activeKey) ?: return@collect
            pageStates[activeKey] = loader.state.copy(loading = true, error = null)
            try {
                loader.loadNextPage()
            } finally {
                pageStates[activeKey] = loader.state
            }
        }
    }

    val metaText = remember(item, seasons, source?.source?.name) {
        DetailStateLogic.formatSeriesMeta(item, DetailStateLogic.regularSeasonCount(seasons), source?.source?.name, strings)
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

        // series.html places the header at top:112, tabs at 404 and the strip at 488, but those
        // absolute positions only hold for a one-line overview. The three blocks flow in a column
        // so a taller header pushes the tabs and episodes down instead of sitting on top of them.
        val headerHasNotice = (detailError != null && source != null) || actionNotice != null
        Column(Modifier.offset(y = 112.dp).fillMaxWidth()) {
        Column(Modifier.padding(start = 64.dp).width(840.dp).heightIn(min = 268.dp)) {
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
                    // A notice row below the buttons needs the height of the second line.
                    maxLines = if (headerHasNotice) 1 else 2,
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
                            val epWithParent = if (hasSeasons && target.episode.parentId.isNullOrBlank()) {
                                target.episode.copy(parentId = selectedSeasonId)
                            } else {
                                target.episode
                            }
                            nav.play(source, epWithParent, target.resumePositionMs)
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
                val presentation = rememberErrorPresentation(
                    error = detailError,
                    source = source,
                    needsPermissionReconfirm = model.needsPermissionReconfirm(source),
                )
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

        Spacer(Modifier.height(24.dp))
        // Season tabs matching series.html (left:38px, top:404px for a one-line overview)
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasSeasons) {
                itemsIndexed(seasons.distinctBy { it.id }, key = { _, s -> s.id }) { idx, season ->
                    val label = DetailStateLogic.formatSeasonTabLabel(seasons, idx, strings)
                    SeasonTabChip(
                        label = label,
                        selected = season.id == selectedSeasonId,
                        onClick = {
                            if (selectedSeasonId != season.id) {
                                userSelectedSeason = true
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

        Spacer(Modifier.height(24.dp))
        // Episode horizontal strip matching series.html (top:488px for a one-line overview)
        Box(Modifier.fillMaxWidth().height(216.dp)) {
            val epErr = activeState.error
            when {
                source == null -> Unit
                epErr != null && episodes.isEmpty() -> {
                    val presentation = rememberErrorPresentation(
                        error = epErr,
                        source = source,
                        needsPermissionReconfirm = model.needsPermissionReconfirm(source),
                    )
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
                                coordinator?.reset(activeKey)
                                pageStates.remove(activeKey)
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
                        itemsIndexed(episodes.distinctBy { it.id }, key = { _, ep -> ep.id }) { idx, ep ->
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
                                    val epWithParent = if (hasSeasons && ep.parentId.isNullOrBlank()) {
                                        ep.copy(parentId = selectedSeasonId)
                                    } else {
                                        ep
                                    }
                                    nav.play(source, epWithParent, startAt)
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
                        } else if (epErr != null && episodes.isNotEmpty()) {
                            item("load-more-error") {
                                EpisodePageRetryCard(
                                    label = stringResource(R.string.series_episodes_page_error_retry),
                                    onClick = {
                                        val loader = coordinator?.existingLoader(activeKey) ?: return@EpisodePageRetryCard
                                        scope.launch {
                                            pageStates[activeKey] = loader.state.copy(loading = true, error = null)
                                            try {
                                                loader.loadNextPage()
                                            } finally {
                                                pageStates[activeKey] = loader.state
                                            }
                                        }
                                    },
                                )
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
private fun EpisodePageRetryCard(
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(interaction)
    Column(Modifier.width(264.dp).ray(interaction, onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(EpisodeCardShape)
                .background(if (hovered) Wall.FillHi else Wall.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(WallIcons.Refresh, null, Modifier.size(30.dp), tint = Wall.Danger)
                Text(label, style = WallType.Caption, color = Wall.Text, maxLines = 2)
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
