package com.limelight.local.wall

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.limelight.R
import com.limelight.local.media.Artwork
import com.limelight.local.media.Artworks
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.WatchProgress
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.search.AggregatedSearchState
import com.limelight.local.search.ExpandedSourcePaginator
import com.limelight.local.search.ExpandedSourceState
import com.limelight.local.search.HoverFreezeBuffer
import com.limelight.local.search.IdleWatchedEntry
import com.limelight.local.search.LocalCatalogEntry
import com.limelight.local.search.PackedSearchItem
import com.limelight.local.search.SearchCardShape
import com.limelight.local.search.SearchConfig
import com.limelight.local.search.SearchIdleDataBuilder
import com.limelight.local.search.SearchItemAction
import com.limelight.local.search.SearchKindFilter
import com.limelight.local.search.SearchNavigationPolicy
import com.limelight.local.search.SearchQueryNormalizer
import com.limelight.local.search.SearchRowFadeTracker
import com.limelight.local.search.SearchRowPacker
import com.limelight.local.search.SearchRowStatus
import com.limelight.local.search.SearchSourceClassifier
import com.limelight.local.search.SearchSourceRow
import com.limelight.local.search.SearchSuggestionEngine
import com.limelight.local.search.SearchWarmUpCoordinator
import com.limelight.local.search.SearchableSource
import com.limelight.local.search.SuggestionRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.Closeable
import kotlin.math.ceil

/**
 * UI state retained inside [WallNav.keep] while navigating from Search into Detail or Login
 * so returning via Back lands on the exact same query, filter, expanded view, and scroll offsets
 * (vertical + per-row horizontal).
 *
 * Implements [Closeable] so [WallNav] automatically cancels [paginatorScope] when leaving
 * `Page.Search`, while keeping it alive across `ExpandedSourceView -> DetailScreen -> Back`.
 */
private class SearchUiHolder : Closeable {
    val paginatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val warmUpCoordinator = SearchWarmUpCoordinator()
    val fadeTracker = SearchRowFadeTracker()

    var draftText by mutableStateOf("")
    var savedDraftText by mutableStateOf("")
    var isEditing by mutableStateOf(false)
    var kindFilter by mutableStateOf(SearchKindFilter.ALL)
    var undoHistorySnapshot by mutableStateOf<List<String>?>(null)
    var expandedPaginator by mutableStateOf<ExpandedSourcePaginator?>(null)
    var expandedMediaSource by mutableStateOf<MediaSource?>(null)
    val idleListState = LazyListState()
    var resultsListState by mutableStateOf(LazyListState())
    var expandedListState by mutableStateOf(LazyListState())
    private val rowListStates = HashMap<String, LazyListState>()

    fun rowListState(sourceId: String): LazyListState =
        rowListStates.getOrPut(sourceId) { LazyListState() }

    fun clearRowListStates() {
        rowListStates.clear()
    }

    fun closeExpanded() {
        expandedPaginator?.cancel()
        expandedPaginator = null
        expandedMediaSource = null
        expandedListState = LazyListState()
    }

    override fun close() {
        closeExpanded()
        paginatorScope.cancel()
    }
}

/**
 * Cross-source aggregated search page implementing all 8 states of `design-opus/mockups/search.html`
 * and `design-opus/ux-spec.md` §3.9 (`idle`, `typing`, `streaming`, `done`, `expanded`, `noresult`,
 * `trouble`, `nosource`).
 */
@Composable
fun SearchScreen(model: WallModel, nav: WallNav) {
    val holder = nav.keep(Page.Search, "ui") { SearchUiHolder() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val genericPluginLabel = stringResource(R.string.search_source_generic_label)
    val listSep = stringResource(R.string.search_list_sep)

    val allSources by model.sources.collectAsState()
    val pluginInstances by model.pluginInstances.collectAsState()
    val history by model.searchHistory.entries.collectAsState()
    val recentProgress by model.recentWatchProgress.collectAsState()
    val progressMap by model.recentWatchProgressMap.collectAsState()
    val aggState by model.searchAggregator.state.collectAsState()

    val partition by remember(allSources, pluginInstances, genericPluginLabel) {
        derivedStateOf {
            SearchSourceClassifier.partition(
                sources = allSources,
                pluginInstances = pluginInstances,
                shelves = model.shelves,
                genericPluginLabel = genericPluginLabel,
            )
        }
    }
    val searchableSources = partition.searchable
    val nonSearchableNames = partition.nonSearchableNames
    val hasSearchableSources = searchableSources.isNotEmpty()

    val recentWatched by remember(recentProgress, allSources) {
        derivedStateOf {
            SearchIdleDataBuilder.buildRecentWatched(recentProgress, allSources, model.shelves)
        }
    }
    val localCatalog by remember(allSources, recentWatched) {
        derivedStateOf {
            SearchIdleDataBuilder.buildLocalCatalog(allSources, model.shelves, recentWatched)
        }
    }

    // Background one-shot warm-up for cold searchable plugin VMs so typing `suggest()` does not
    // cold-skip them; runs outside the 800 ms suggestion budget and ignores failures silently.
    LaunchedEffect(searchableSources) {
        holder.warmUpCoordinator.warmUpUninitialized(
            sources = searchableSources,
            scope = holder.paginatorScope,
        )
    }

    val resolveFreshSource: suspend (String) -> SearchableSource? = { id ->
        model.resolveFreshSearchableSource(id, genericPluginLabel)
    }

    // Returning from post-login flow re-searches ONLY that source in place (§3.9.3).
    // Always resolves the live PluginMediaSource from PluginHost in case async reconcile
    // has not yet published the newly authenticated instance to `model.sources`.
    val authSignal = nav.lastAuthenticatedSource
    LaunchedEffect(authSignal) {
        if (authSignal != null) {
            val authedSourceId = nav.consumeAuthenticatedSource()
            if (authedSourceId != null && aggState.hasActiveQuery) {
                val fresh = searchableSources.firstOrNull { it.sourceId == authedSourceId }
                model.searchAggregator.retrySource(
                    sourceId = authedSourceId,
                    updatedSource = fresh,
                    resolveFreshSource = resolveFreshSource,
                    forceResolveFresh = true,
                )
            }
        }
    }

    fun exitEditing(restoreSubmittedQuery: Boolean, clearSavedDraft: Boolean = false) {
        holder.isEditing = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (aggState.hasActiveQuery) {
            if (restoreSubmittedQuery) {
                holder.draftText = aggState.query
            }
        } else {
            holder.savedDraftText = if (clearSavedDraft) "" else holder.draftText
            holder.draftText = ""
        }
    }

    fun submitSearch(raw: String, filter: SearchKindFilter = SearchKindFilter.ALL) {
        val norm = SearchQueryNormalizer.normalize(raw)
        if (norm.isEmpty() || !hasSearchableSources) return
        holder.closeExpanded()
        holder.clearRowListStates()
        holder.fadeTracker.clear()
        holder.undoHistorySnapshot = null
        holder.savedDraftText = ""
        holder.draftText = norm
        holder.kindFilter = filter
        holder.isEditing = false
        holder.resultsListState = LazyListState()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        model.searchHistory.record(norm)
        model.searchAggregator.search(
            rawQuery = norm,
            kindFilter = filter,
            sources = searchableSources,
            nonSearchableNames = nonSearchableNames,
            resolveFreshSource = resolveFreshSource,
        )
    }

    fun switchAggregatedKind(newFilter: SearchKindFilter) {
        if (!aggState.hasActiveQuery || holder.kindFilter == newFilter) return
        holder.clearRowListStates()
        holder.fadeTracker.clear()
        holder.kindFilter = newFilter
        model.searchAggregator.search(
            rawQuery = aggState.query,
            kindFilter = newFilter,
            sources = searchableSources,
            nonSearchableNames = nonSearchableNames,
            resolveFreshSource = resolveFreshSource,
        )
    }

    fun liveMediaSource(candidate: MediaSource?): MediaSource? {
        if (candidate == null) return null
        if (candidate is PluginMediaSource && candidate.isClosed) {
            return model.source(candidate.source.id)?.takeUnless { (it as? PluginMediaSource)?.isClosed == true }
                ?: candidate
        }
        return candidate
    }

    fun openExpandedSource(row: SearchSourceRow) {
        val results = row.status as? SearchRowStatus.Results ?: return
        val liveSource = liveMediaSource(row.mediaSource)
        val src = searchableSources.firstOrNull { it.sourceId == row.sourceId && !it.isClosed }
            ?: (liveSource as? PluginMediaSource)?.takeIf { !it.isClosed }?.let { plugin ->
                com.limelight.local.search.PluginSearchableSource(
                    plugin = plugin,
                    typeLabel = SearchSourceClassifier.typeLabelFor(plugin, genericPluginLabel),
                    knownNeedsAuth = false,
                )
            }
            ?: searchableSources.firstOrNull { it.sourceId == row.sourceId }
            ?: return
        holder.closeExpanded()
        holder.expandedMediaSource = liveSource ?: src.mediaSource
        holder.expandedPaginator = ExpandedSourcePaginator(
            scope = holder.paginatorScope,
            source = src,
            query = aggState.query,
            initialKindFilter = aggState.kindFilter,
            initialItems = results.items,
            initialNextCursor = results.nextCursor,
        )
    }

    fun openResultItem(mediaSource: MediaSource?, item: Item) {
        val src = liveMediaSource(mediaSource) ?: return
        val localProgress = progressMap[src.source.id to item.id]
        when (val action = SearchNavigationPolicy.resolveClickAction(src, item, localProgress)) {
            is SearchItemAction.OpenFolder -> nav.category(action.source, action.item.id, action.item.title, action.item.childCount)
            is SearchItemAction.PlayDirect -> nav.play(action.source, action.item, action.startMs)
            is SearchItemAction.OpenEpisodeInSeries -> nav.openSearchEpisode(action.source, action.item)
            is SearchItemAction.OpenDetail -> nav.detail(action.source, action.item)
        }
    }

    fun handleBack() {
        when {
            holder.expandedPaginator != null -> holder.closeExpanded()
            holder.isEditing -> exitEditing(restoreSubmittedQuery = true)
            else -> nav.back()
        }
    }

    BackHandler(enabled = holder.expandedPaginator != null || holder.isEditing) {
        handleBack()
    }

    val activePaginator = holder.expandedPaginator
    if (activePaginator != null) {
        ExpandedSourceView(
            paginator = activePaginator,
            mediaSource = holder.expandedMediaSource,
            progressMap = progressMap,
            listState = holder.expandedListState,
            onBack = { holder.closeExpanded() },
            onItemClick = { item -> openResultItem(holder.expandedMediaSource, item) },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Wall.Bg)) {
        // Top bar: Back button (64, 32) + isolated SearchInputBar (152..1216, 32)
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, ::handleBack, filled = true)
        }
        SearchInputBar(
            text = holder.draftText,
            isEditing = holder.isEditing && hasSearchableSources,
            enabled = hasSearchableSources,
            placeholder = stringResource(
                if (hasSearchableSources) R.string.search_placeholder else R.string.search_placeholder_nosource,
            ),
            onEnterEdit = {
                if (hasSearchableSources) {
                    if (!aggState.hasActiveQuery && holder.draftText.isEmpty() && holder.savedDraftText.isNotEmpty()) {
                        holder.draftText = holder.savedDraftText
                    }
                    holder.isEditing = true
                }
            },
            onTextChange = { holder.draftText = it },
            onClear = {
                holder.draftText = ""
                holder.savedDraftText = ""
                if (hasSearchableSources) {
                    holder.isEditing = true
                }
            },
            onSubmit = { submitSearch(holder.draftText) },
            onCancelEdit = { exitEditing(restoreSubmittedQuery = true) },
            modifier = Modifier.offset(152.dp, 32.dp).width(1064.dp),
        )

        when {
            !hasSearchableSources -> {
                NoSearchableSourcesView(
                    nonSearchableNames = nonSearchableNames,
                    listSep = listSep,
                    onOpenLibrary = { nav.tab(Tab.Library) },
                    onAddSource = { nav.openAddSource() },
                )
            }

            holder.isEditing -> {
                TypingSuggestionsView(
                    rawQuery = holder.draftText,
                    searchableSources = searchableSources,
                    localCatalog = localCatalog,
                    history = history,
                    onSubmitQuery = { submitSearch(it) },
                    onFillQuery = { holder.draftText = it },
                    onDirectHit = { entry ->
                        val termToRecord = entry.item.title.ifBlank { holder.draftText }
                        if (termToRecord.isNotBlank()) {
                            model.searchHistory.record(termToRecord)
                        }
                        exitEditing(restoreSubmittedQuery = true, clearSavedDraft = true)
                        openResultItem(entry.source, entry.item)
                    },
                )
            }

            !aggState.hasActiveQuery -> {
                SearchIdleView(
                    listState = holder.idleListState,
                    history = history,
                    undoSnapshot = holder.undoHistorySnapshot,
                    recentWatched = recentWatched,
                    onSelectHistory = { submitSearch(it) },
                    onClearHistory = {
                        holder.undoHistorySnapshot = model.searchHistory.clearWithUndoSnapshot()
                    },
                    onUndoClearHistory = {
                        holder.undoHistorySnapshot?.let { snap ->
                            model.searchHistory.restore(snap)
                        }
                        holder.undoHistorySnapshot = null
                    },
                    onClickWatched = { entry ->
                        if (entry.isResumable) {
                            nav.play(entry.source, entry.item, entry.progress.positionMs)
                        } else {
                            openResultItem(entry.source, entry.item)
                        }
                    },
                    onPromptFocus = {
                        if (holder.draftText.isEmpty() && holder.savedDraftText.isNotEmpty()) {
                            holder.draftText = holder.savedDraftText
                        }
                        holder.isEditing = true
                    },
                )
            }

            else -> {
                // Fixed kind filter strip + status counter (y = 112..172)
                SearchKindFilterStrip(
                    selected = aggState.kindFilter,
                    availableKinds = aggState.availableKinds,
                    onSelect = ::switchAggregatedKind,
                    modifier = Modifier.offset(64.dp, 112.dp),
                )
                SearchStatusCounter(
                    state = aggState,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 112.dp, end = 64.dp),
                )

                // Clipped results viewport (y = 184..720, height = 536 dp)
                Box(
                    Modifier
                        .offset(0.dp, 184.dp)
                        .fillMaxWidth()
                        .height(536.dp)
                        .clipToBounds(),
                ) {
                    if (aggState.isTrueNoResult) {
                        SearchNoResultView(
                            state = aggState,
                            listSep = listSep,
                            onSelectSuggestion = { submitSearch(it) },
                            onGoLibrary = { nav.tab(Tab.Library) },
                        )
                    } else {
                        AggregatedResultsList(
                            state = aggState,
                            listState = holder.resultsListState,
                            rowListStateFor = holder::rowListState,
                            fadeTracker = holder.fadeTracker,
                            progressMap = progressMap,
                            listSep = listSep,
                            onExpandSource = ::openExpandedSource,
                            onItemClick = ::openResultItem,
                            onRetrySource = { row ->
                                val fresh = searchableSources.firstOrNull { it.sourceId == row.sourceId }
                                model.searchAggregator.retrySource(
                                    sourceId = row.sourceId,
                                    updatedSource = fresh,
                                    resolveFreshSource = resolveFreshSource,
                                )
                            },
                            onLoginSource = { row ->
                                liveMediaSource(row.mediaSource)?.let { nav.pluginLogin(it, returnAfterSuccess = true) }
                                    ?: nav.openSources()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Isolated top search input bar (`ux-spec.md` §3.9.2, `08-search.md`).
 * Designed as a single swap point for Task F (phone-assisted search input) without touching
 * any of the suggestion or result layers below.
 *
 * Deliberately renders plain non-focusable text when [isEditing] is `false` so entering the
 * Search screen or returning from Detail never auto-pops the system keyboard.
 */
@Composable
internal fun SearchInputBar(
    text: String,
    isEditing: Boolean,
    enabled: Boolean,
    placeholder: String,
    onEnterEdit: () -> Unit,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barInteraction = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(barInteraction)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val shape = RoundedCornerShape(32.dp)
    val bg by animateColorAsState(
        when {
            !enabled -> Wall.Surface
            isEditing -> Wall.Sel
            hovered -> Wall.FillHi
            else -> Wall.Fill
        },
        tween(120),
        label = "search-bar-bg",
    )

    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (isEditing) {
                    Modifier.border(2.dp, Color.White.copy(alpha = 0.35f), shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (enabled && !isEditing) {
                    Modifier.ray(barInteraction, onEnterEdit)
                } else {
                    Modifier
                },
            )
            .padding(start = 24.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            WallIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (enabled) Wall.Text2 else Wall.Text3,
        )

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (isEditing && enabled) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = WallType.Label.copy(color = Wall.Text),
                    cursorBrush = SolidColor(Wall.Text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyUp) {
                                when (ev.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        onSubmit()
                                        true
                                    }
                                    Key.Escape -> {
                                        onCancelEdit()
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                placeholder,
                                style = WallType.Body,
                                color = Wall.Text2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    },
                )
            } else if (text.isNotEmpty()) {
                Text(
                    text,
                    style = WallType.Label,
                    color = Wall.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    placeholder,
                    style = WallType.Body,
                    color = if (enabled) Wall.Text2 else Wall.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (enabled && text.isNotEmpty()) {
            val clearSrc = remember { MutableInteractionSource() }
            val clearHovered by rememberRayHover(clearSrc)
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (clearHovered) Wall.FillHi else Color.Transparent)
                    .ray(clearSrc, onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(WallIcons.Close, null, Modifier.size(26.dp), tint = Wall.Text2)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// State 1: nosource
// ---------------------------------------------------------------------------

@Composable
private fun NoSearchableSourcesView(
    nonSearchableNames: List<String>,
    listSep: String,
    onOpenLibrary: () -> Unit,
    onAddSource: () -> Unit,
) {
    Column(
        Modifier
            .offset(64.dp, 176.dp)
            .width(1152.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(Wall.Fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WallIcons.Search, null, Modifier.size(44.dp), tint = Wall.Text2)
        }
        Text(
            stringResource(R.string.search_nosource_title),
            style = WallType.Title,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp),
        )
        val body = if (nonSearchableNames.isNotEmpty()) {
            stringResource(R.string.search_nosource_body, nonSearchableNames.joinToString(listSep))
        } else {
            stringResource(R.string.search_nosource_body_none)
        }
        Text(
            body,
            style = WallType.Body,
            color = Wall.Text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp).widthIn(max = 820.dp),
        )
        Row(
            Modifier.padding(top = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WallButton(
                text = stringResource(R.string.search_nosource_go_library),
                onClick = onOpenLibrary,
                kind = ButtonKind.Primary,
            )
            WallButton(
                text = stringResource(R.string.search_nosource_add_source),
                onClick = onAddSource,
                kind = ButtonKind.Plain,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// State 2: idle
// ---------------------------------------------------------------------------

@Composable
private fun SearchIdleView(
    listState: LazyListState,
    history: List<String>,
    undoSnapshot: List<String>?,
    recentWatched: List<IdleWatchedEntry>,
    onSelectHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onUndoClearHistory: () -> Unit,
    onClickWatched: (IdleWatchedEntry) -> Unit,
    onPromptFocus: () -> Unit,
) {
    val showHistorySection = history.isNotEmpty() || undoSnapshot != null
    val allEmpty = !showHistorySection && recentWatched.isEmpty()

    if (allEmpty) {
        Box(
            Modifier
                .offset(0.dp, 112.dp)
                .fillMaxWidth()
                .height(608.dp),
            contentAlignment = Alignment.Center,
        ) {
            WallButton(
                text = stringResource(R.string.search_idle_empty_hint),
                onClick = onPromptFocus,
                icon = WallIcons.Search,
                kind = ButtonKind.Ghost,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .offset(0.dp, 108.dp)
            .fillMaxWidth()
            .height(612.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 56.dp),
    ) {
        if (showHistorySection) {
            item("idle-history") {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Wall.Margin)
                            .height(60.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.search_idle_recent_queries), style = WallType.Head)
                        Spacer(Modifier.weight(1f))
                        if (history.isNotEmpty()) {
                            WallButton(
                                text = stringResource(R.string.search_idle_clear_history),
                                onClick = onClearHistory,
                                kind = ButtonKind.Ghost,
                                small = true,
                            )
                        } else if (undoSnapshot != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.search_idle_history_cleared),
                                    style = WallType.Caption,
                                    color = Wall.Text2,
                                )
                                WallButton(
                                    text = stringResource(R.string.search_idle_history_undo),
                                    onClick = onUndoClearHistory,
                                    kind = ButtonKind.Ghost,
                                    small = true,
                                )
                            }
                        }
                    }

                    if (history.isNotEmpty()) {
                        SingleRowWholeChipsLayout(
                            horizontalGap = 16.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = Wall.Margin, end = Wall.Margin, top = 8.dp),
                        ) {
                            for (term in history.take(SearchConfig.MAX_IDLE_HISTORY_CHIPS)) {
                                SearchPillChip(
                                    text = term,
                                    icon = WallIcons.Clock,
                                    onClick = { onSelectHistory(term) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (recentWatched.isNotEmpty()) {
            item("idle-watched") {
                Column(Modifier.padding(top = if (showHistorySection) 32.dp else 8.dp)) {
                    Text(
                        stringResource(R.string.search_idle_recent_watched),
                        style = WallType.Head,
                        modifier = Modifier.padding(horizontal = Wall.Margin),
                    )
                    Spacer(Modifier.height(14.dp))
                    val rowState = remember { LazyListState() }
                    LazyRow(
                        state = rowState,
                        flingBehavior = rememberSnapFlingBehavior(rowState),
                        contentPadding = PaddingValues(horizontal = Wall.Margin),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(
                            recentWatched,
                            key = { idx, e -> "w:${e.source.source.id}/$idx/${e.item.id}" },
                        ) { _, entry ->
                            CompositionLocalProvider(LocalMediaSource provides entry.source) {
                                IdleWatchedCard(entry = entry, onClick = { onClickWatched(entry) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Places chips on a single horizontal row and drops any chip that would not fit completely
 * inside the available width (`ux-spec.md` §3.9.3: 「超出整条不画，不截半个芯片」).
 */
@Composable
private fun SingleRowWholeChipsLayout(
    horizontalGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = horizontalGap.roundToPx()
        val maxW = constraints.maxWidth
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = ArrayList<androidx.compose.ui.layout.Placeable>(measurables.size)
        var usedW = 0
        var maxH = 0
        for (m in measurables) {
            val p = m.measure(childConstraints)
            val needed = if (placed.isEmpty()) p.width else usedW + gapPx + p.width
            if (needed > maxW) break
            placed += p
            usedW = needed
            if (p.height > maxH) maxH = p.height
        }
        layout(maxW, maxH) {
            var x = 0
            for (p in placed) {
                p.placeRelative(x, (maxH - p.height) / 2)
                x += p.width + gapPx
            }
        }
    }
}

@Composable
private fun IdleWatchedCard(entry: IdleWatchedEntry, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val scale by animateFloatAsState(if (hovered) 1.06f else 1f, tween(140, easing = Wall.Ease), label = "idle-w")
    val progress = entry.progressFraction
    val meta = if (entry.isResumable) {
        val rem = (entry.progress.durationMs - entry.progress.positionMs).coerceAtLeast(1L)
        stringResource(R.string.home_continue_left_min, minutesLeft(rem))
    } else {
        stringResource(R.string.search_item_watched)
    }
    Column(Modifier.width(264.dp).ray(source, onClick)) {
        val cardShape = RoundedCornerShape(Wall.RadiusPoster)
        ArtBox(
            art = entry.item.backdrop ?: entry.item.thumb ?: entry.item.poster,
            seed = entry.item.title,
            size = Artworks.Size.CARD,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .zIndex(if (scale > 1f) 1f else 0f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = (scale - 1f) / 0.06f * 24.dp.toPx()
                    shape = cardShape
                    clip = true
                },
        ) {
            if (progress != null) {
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
                            .fillMaxWidth(progress)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Wall.Accent),
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                hovered && entry.isResumable,
                Modifier.align(Alignment.Center),
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(140)),
            ) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color(0xEBECECEE)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(WallIcons.Play, null, Modifier.size(28.dp).offset(x = 2.dp), tint = Wall.Bg)
                }
            }
        }
        Row(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                entry.item.title,
                style = WallType.Caption.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(meta, style = WallType.Caption, color = Wall.Text2, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------
// State 3: typing (upper keyboard safe zone y = 112..440, max 4 rows)
// ---------------------------------------------------------------------------

@Composable
private fun TypingSuggestionsView(
    rawQuery: String,
    searchableSources: List<SearchableSource>,
    localCatalog: List<LocalCatalogEntry>,
    history: List<String>,
    onSubmitQuery: (String) -> Unit,
    onFillQuery: (String) -> Unit,
    onDirectHit: (LocalCatalogEntry) -> Unit,
) {
    val freezeBuffer = remember { HoverFreezeBuffer<List<SuggestionRow>>(emptyList()) }
    var displayedRows by remember { mutableStateOf<List<SuggestionRow>>(emptyList()) }

    val listHoverSource = remember { MutableInteractionSource() }
    val listHovered by listHoverSource.collectIsHoveredAsState()

    LaunchedEffect(listHovered) {
        displayedRows = freezeBuffer.onHoverChanged(listHovered)
    }

    LaunchedEffect(rawQuery, localCatalog, history, searchableSources) {
        val norm = SearchQueryNormalizer.normalize(rawQuery)
        val localRows = SearchSuggestionEngine.buildRows(
            rawQuery = rawQuery,
            localCatalog = localCatalog,
            history = history,
            remoteSuggestions = emptyList(),
        )
        displayedRows = freezeBuffer.onQueryChanged(norm, localRows)

        if (localRows.size < SearchConfig.MAX_SUGGESTION_ROWS &&
            SearchQueryNormalizer.shouldRequestRemoteSuggest(rawQuery)
        ) {
            delay(SearchConfig.SUGGEST_DEBOUNCE_MS)
            val remote = SearchSuggestionEngine.fetchRemoteSuggestions(
                rawQuery = rawQuery,
                sources = searchableSources,
            )
            if (remote.isNotEmpty()) {
                val merged = SearchSuggestionEngine.buildRows(
                    rawQuery = rawQuery,
                    localCatalog = localCatalog,
                    history = history,
                    remoteSuggestions = remote,
                )
                displayedRows = freezeBuffer.onUpdate(norm, merged)
            }
        }
    }

    Column(
        Modifier
            .offset(40.dp, 112.dp)
            .width(1200.dp)
            .height(312.dp)
            .hoverable(listHoverSource),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in displayedRows.take(SearchConfig.MAX_SUGGESTION_ROWS)) {
            TypingSuggestionItemRow(
                row = row,
                onSubmitQuery = onSubmitQuery,
                onFillQuery = onFillQuery,
                onDirectHit = onDirectHit,
            )
        }
    }
}

@Composable
private fun TypingSuggestionItemRow(
    row: SuggestionRow,
    onSubmitQuery: (String) -> Unit,
    onFillQuery: (String) -> Unit,
    onDirectHit: (LocalCatalogEntry) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val bg by animateColorAsState(if (hovered) Wall.SurfaceHi else Color.Transparent, tween(120), label = "sg-row")

    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(Wall.Radius))
            .background(bg)
            .ray(source) {
                when (row) {
                    is SuggestionRow.RawQuery -> onSubmitQuery(row.query)
                    is SuggestionRow.LocalHit -> onDirectHit(row.entry)
                    is SuggestionRow.History -> onSubmitQuery(row.term)
                    is SuggestionRow.Remote -> onSubmitQuery(row.term)
                }
            }
            .padding(start = 24.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            when (row) {
                is SuggestionRow.RawQuery, is SuggestionRow.Remote ->
                    Icon(WallIcons.Search, null, Modifier.size(28.dp), tint = Wall.Text2)
                is SuggestionRow.History ->
                    Icon(WallIcons.Clock, null, Modifier.size(28.dp), tint = Wall.Text2)
                is SuggestionRow.LocalHit -> {
                    CompositionLocalProvider(LocalMediaSource provides row.entry.source) {
                        ArtBox(
                            art = row.entry.item.poster ?: row.entry.item.thumb ?: row.entry.item.backdrop,
                            seed = row.entry.item.title,
                            size = Artworks.Size.CARD,
                            modifier = Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when (row) {
                is SuggestionRow.RawQuery -> {
                    val prefix = stringResource(R.string.search_typing_prefix)
                    val verbatim = stringResource(R.string.search_typing_verbatim, row.query)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Wall.Text2, fontWeight = FontWeight.Normal)) {
                                append(prefix)
                            }
                            withStyle(SpanStyle(color = Wall.Text, fontWeight = FontWeight.Medium)) {
                                append(verbatim)
                            }
                        },
                        style = WallType.Label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is SuggestionRow.LocalHit -> {
                    Text(row.entry.item.title, style = WallType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                is SuggestionRow.History -> {
                    Text(row.term, style = WallType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                is SuggestionRow.Remote -> {
                    Text(row.term, style = WallType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        when (row) {
            is SuggestionRow.RawQuery -> Unit
            is SuggestionRow.LocalHit -> {
                Row(
                    Modifier.padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.search_typing_local_meta,
                            shortKindLabel(row.entry.item.kind),
                            row.entry.source.source.name,
                        ),
                        style = WallType.Caption,
                        color = Wall.Text2,
                        maxLines = 1,
                    )
                    Icon(WallIcons.ChevR, null, Modifier.size(24.dp), tint = Wall.Text2)
                }
            }
            is SuggestionRow.History -> {
                FillInSuggestionButton { onFillQuery(row.term) }
            }
            is SuggestionRow.Remote -> {
                FillInSuggestionButton { onFillQuery(row.term) }
            }
        }
    }
}

@Composable
private fun FillInSuggestionButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(src)
    Box(
        Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(if (hovered) Wall.FillHi else Color.Transparent)
            .ray(src, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(WallIcons.FillIn, null, Modifier.size(26.dp), tint = Wall.Text2)
    }
}

// ---------------------------------------------------------------------------
// Shared kind filter & status strip (y = 112..172)
// ---------------------------------------------------------------------------

@Composable
private fun SearchKindFilterStrip(
    selected: SearchKindFilter,
    availableKinds: Set<SearchKindFilter>?,
    onSelect: (SearchKindFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        SearchKindFilter.ALL to stringResource(R.string.search_kind_all),
        SearchKindFilter.MOVIE to stringResource(R.string.search_kind_movie),
        SearchKindFilter.SERIES to stringResource(R.string.search_kind_series),
        SearchKindFilter.VIDEO to stringResource(R.string.search_kind_video),
    )
    Row(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Wall.Surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((kind, label) in options) {
            val isSel = kind == selected
            val enabled = availableKinds == null || kind == SearchKindFilter.ALL || isSel || kind in availableKinds
            val src = remember { MutableInteractionSource() }
            val hovered by rememberRayHover(src)
            val bg by animateColorAsState(
                when {
                    isSel -> Wall.Sel
                    enabled && hovered -> Wall.Fill
                    else -> Color.Transparent
                },
                tween(120),
                label = "kind-tab",
            )
            val fg = when {
                !enabled -> Wall.Text3
                isSel || hovered -> Wall.Text
                else -> Wall.Text2
            }
            Box(
                Modifier
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .then(if (enabled) Modifier.ray(src) { onSelect(kind) } else Modifier)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = WallType.ButtonSmall.copy(
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SearchStatusCounter(
    state: AggregatedSearchState,
    modifier: Modifier = Modifier,
) {
    val busy = !state.allSettled
    Row(
        modifier.height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            BreathingDot()
        }
        Text(
            stringResource(
                if (busy) R.string.search_stat_searching else R.string.search_stat_searched,
                state.totalSearchableSources,
            ),
            style = WallType.Caption,
            color = Wall.Text2,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// States 4, 5, 7: streaming / done / trouble
// ---------------------------------------------------------------------------

@Composable
private fun PostBatchFadeIn(
    generation: Long,
    sourceId: String,
    arrivedAfterBatch: Boolean,
    fadeTracker: SearchRowFadeTracker,
    content: @Composable () -> Unit,
) {
    val shouldAnimate = remember(generation, sourceId) {
        fadeTracker.shouldFadeIn(generation, sourceId, arrivedAfterBatch)
    }
    val alpha = remember(generation, sourceId) { Animatable(if (shouldAnimate) 0f else 1f) }
    LaunchedEffect(generation, sourceId, shouldAnimate) {
        if (shouldAnimate) {
            try {
                if (alpha.value < 1f) {
                    alpha.animateTo(1f, tween(200))
                }
            } finally {
                fadeTracker.markFadedIn(generation, sourceId)
            }
        }
    }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) {
        content()
    }
}

@Composable
private fun AggregatedResultsList(
    state: AggregatedSearchState,
    listState: LazyListState,
    rowListStateFor: (String) -> LazyListState,
    fadeTracker: SearchRowFadeTracker,
    progressMap: Map<Pair<String, String>, WatchProgress>,
    listSep: String,
    onExpandSource: (SearchSourceRow) -> Unit,
    onItemClick: (MediaSource?, Item) -> Unit,
    onRetrySource: (SearchSourceRow) -> Unit,
    onLoginSource: (SearchSourceRow) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp),
    ) {
        if (state.visibleRows.isEmpty() && state.showInitialSkeleton && !state.allSettled) {
            item("initial-skeleton") {
                Column(
                    Modifier.padding(start = Wall.Margin, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    repeat(2) {
                        Column {
                            SearchSkeletonBox(Modifier.size(160.dp, 28.dp), 10.dp)
                            Spacer(Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                repeat(5) {
                                    SearchSkeletonBox(Modifier.size(128.dp, 192.dp), Wall.RadiusPoster)
                                }
                            }
                        }
                    }
                }
            }
        }

        itemsIndexed(state.visibleRows, key = { _, row -> "src:${row.sourceId}" }) { index, row ->
            val topGap = if (index == 0) 0.dp else 18.dp
            PostBatchFadeIn(
                generation = state.generation,
                sourceId = row.sourceId,
                arrivedAfterBatch = row.arrivedAfterBatch,
                fadeTracker = fadeTracker,
            ) {
                if (row.refreshingInPlace) {
                    Box(Modifier.padding(top = topGap)) {
                        SearchStatusLine(
                            icon = null,
                            busyDot = true,
                            text = stringResource(R.string.search_err_retrying, row.displayName),
                            danger = false,
                            action = {},
                        )
                    }
                } else {
                    when (val st = row.status) {
                        is SearchRowStatus.Results -> {
                            SearchResultsSourceSection(
                                row = row,
                                results = st,
                                rowListState = rowListStateFor(row.sourceId),
                                progressMap = progressMap,
                                modifier = Modifier.padding(top = topGap),
                                onExpand = { onExpandSource(row) },
                                onItemClick = { item -> onItemClick(row.mediaSource, item) },
                            )
                        }
                        is SearchRowStatus.Empty -> {
                            Box(Modifier.padding(top = topGap)) {
                                SearchStatusLine(
                                    icon = WallIcons.Search,
                                    text = stringResource(R.string.search_err_empty_after_retry, row.displayName),
                                    danger = false,
                                    action = {},
                                )
                            }
                        }
                        is SearchRowStatus.NeedsAuth -> {
                            Box(Modifier.padding(top = topGap)) {
                                SearchStatusLine(
                                    icon = WallIcons.User,
                                    text = stringResource(R.string.search_err_needs_auth, row.displayName),
                                    danger = false,
                                    action = {
                                        WallButton(
                                            text = stringResource(R.string.search_err_action_login),
                                            onClick = { onLoginSource(row) },
                                            small = true,
                                        )
                                    },
                                )
                            }
                        }
                        is SearchRowStatus.Failed -> {
                            Box(Modifier.padding(top = topGap)) {
                                SearchStatusLine(
                                    icon = WallIcons.Alert,
                                    text = stringResource(R.string.search_err_unavailable, row.displayName),
                                    danger = true,
                                    action = {
                                        WallButton(
                                            text = stringResource(R.string.action_retry),
                                            onClick = { onRetrySource(row) },
                                            icon = WallIcons.Refresh,
                                            small = true,
                                        )
                                    },
                                )
                            }
                        }
                        is SearchRowStatus.TimedOut -> {
                            Box(Modifier.padding(top = topGap)) {
                                SearchStatusLine(
                                    icon = WallIcons.Alert,
                                    text = stringResource(R.string.search_err_timeout, row.displayName, st.timeoutSec),
                                    danger = true,
                                    action = {
                                        WallButton(
                                            text = stringResource(R.string.action_retry),
                                            onClick = { onRetrySource(row) },
                                            icon = WallIcons.Refresh,
                                            small = true,
                                        )
                                    },
                                )
                            }
                        }
                        is SearchRowStatus.RateLimited -> {
                            Box(Modifier.padding(top = topGap)) {
                                SearchRateLimitedLine(
                                    displayName = row.displayName,
                                    availableAtMs = st.availableAtMs,
                                    onRetry = { onRetrySource(row) },
                                )
                            }
                        }
                        SearchRowStatus.Waiting -> Unit
                    }
                }
            }
        }

        if (state.showPendingTail && state.pendingSourceNames.isNotEmpty()) {
            item("tail-pending") {
                SearchTailLine(
                    text = stringResource(
                        R.string.search_tail_still_searching,
                        state.pendingSourceNames.joinToString(listSep),
                    ),
                    busy = true,
                )
            }
        }

        if (state.allSettled && !state.isTrueNoResult &&
            (state.emptySourceNames.isNotEmpty() || state.nonSearchableSourceNames.isNotEmpty())
        ) {
            item("tail-summary") {
                val emptyPart = if (state.emptySourceNames.isNotEmpty()) {
                    stringResource(R.string.search_tail_not_found_in, state.emptySourceNames.joinToString(listSep))
                } else {
                    null
                }
                val unsupportedPart = if (state.nonSearchableSourceNames.isNotEmpty()) {
                    stringResource(R.string.search_tail_unsupported, state.nonSearchableSourceNames.joinToString(listSep))
                } else {
                    null
                }
                val mergedTail = listOfNotNull(emptyPart, unsupportedPart).joinToString("  ")
                if (mergedTail.isNotEmpty()) {
                    SearchTailLine(text = mergedTail, busy = false)
                }
            }
        }
    }
}

@Composable
private fun SearchResultsSourceSection(
    row: SearchSourceRow,
    results: SearchRowStatus.Results,
    rowListState: LazyListState,
    progressMap: Map<Pair<String, String>, WatchProgress>,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onItemClick: (Item) -> Unit,
) {
    val headSrc = remember { MutableInteractionSource() }
    val headHovered by rememberRayHover(headSrc)
    val headBg by animateColorAsState(if (headHovered) Wall.Fill else Color.Transparent, tween(120), label = "rhd")
    val subText = stringResource(
        if (results.hasMore) R.string.search_row_sub_more else R.string.search_row_sub_exact,
        row.typeLabel,
        results.items.size,
    )
    val previewItems = results.items.take(SearchConfig.ROW_PREVIEW_LIMIT)
    val showMoreTile = results.items.size > SearchConfig.ROW_PREVIEW_LIMIT || results.hasMore

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(start = 48.dp, end = 48.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(Wall.Radius))
                .background(headBg)
                .ray(headSrc, onExpand)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.displayName,
                style = WallType.Label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subText, style = WallType.Caption, color = Wall.Text2, maxLines = 1)
            Icon(WallIcons.ChevR, null, Modifier.size(24.dp), tint = if (headHovered) Wall.Text else Wall.Text2)
        }

        Spacer(Modifier.height(4.dp))

        CompositionLocalProvider(LocalMediaSource provides row.mediaSource) {
            LazyRow(
                state = rowListState,
                flingBehavior = rememberSnapFlingBehavior(rowListState),
                contentPadding = PaddingValues(horizontal = Wall.Margin),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(
                    previewItems,
                    key = { idx, item -> "${row.sourceId}/$idx/${item.id}" },
                ) { _, item ->
                    SearchResultCard(
                        item = item,
                        shape = SearchCardShape.forItem(item),
                        localProgress = progressMap[row.sourceId to item.id],
                        onClick = { onItemClick(item) },
                    )
                }
                if (showMoreTile) {
                    item("more:${row.sourceId}") {
                        SearchMoreTile(
                            count = results.items.size,
                            hasMore = results.hasMore,
                            onClick = onExpand,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchStatusLine(
    icon: ImageVector?,
    text: String,
    danger: Boolean,
    busyDot: Boolean = false,
    action: @Composable () -> Unit,
) {
    Row(
        Modifier
            .padding(horizontal = 40.dp)
            .width(1200.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(Wall.Radius))
            .background(Wall.Surface)
            .padding(start = 24.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (busyDot) {
            BreathingDot()
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(28.dp), tint = if (danger) Wall.Danger else Wall.Text2)
        }
        Text(
            text,
            style = WallType.Label,
            color = Wall.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

@Composable
private fun SearchRateLimitedLine(
    displayName: String,
    availableAtMs: Long,
    onRetry: () -> Unit,
) {
    var nowMs by remember(availableAtMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(availableAtMs) {
        while (System.currentTimeMillis() < availableAtMs) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
        nowMs = System.currentTimeMillis()
    }
    val remainingSec = ceil(((availableAtMs - nowMs).coerceAtLeast(0L)) / 1000.0).toInt()
    val canRetry = remainingSec <= 0
    val statusText = when {
        canRetry -> stringResource(R.string.search_err_rate_limited_ready, displayName)
        remainingSec >= 60 -> {
            val mins = ceil(remainingSec / 60.0).toInt().coerceAtLeast(1)
            stringResource(R.string.search_err_rate_limited_min, displayName, mins)
        }
        else -> stringResource(R.string.search_err_rate_limited, displayName, remainingSec)
    }
    SearchStatusLine(
        icon = WallIcons.Alert,
        text = statusText,
        danger = true,
        action = {
            WallButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                icon = if (canRetry) WallIcons.Refresh else null,
                small = true,
                enabled = canRetry,
            )
        },
    )
}

@Composable
private fun SearchTailLine(text: String, busy: Boolean) {
    Row(
        Modifier
            .padding(start = Wall.Margin, end = Wall.Margin, top = 16.dp)
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) {
            BreathingDot()
        }
        Text(text, style = WallType.Caption, color = Wall.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// State 6: noresult
// ---------------------------------------------------------------------------

@Composable
private fun SearchNoResultView(
    state: AggregatedSearchState,
    listSep: String,
    onSelectSuggestion: (String) -> Unit,
    onGoLibrary: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 40.dp, start = Wall.Margin, end = Wall.Margin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.search_noresult_title, state.query),
            style = WallType.Title,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.allSearchableSourceNames.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.search_noresult_body,
                    state.allSearchableSourceNames.joinToString(listSep),
                ),
                style = WallType.Body,
                color = Wall.Text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).widthIn(max = 960.dp),
            )
        }
        if (state.fallbackSuggestions.isNotEmpty()) {
            Text(
                stringResource(R.string.search_noresult_suggest_heading),
                style = WallType.Caption,
                color = Wall.Text2,
                modifier = Modifier.padding(top = 36.dp),
            )
            Row(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (sug in state.fallbackSuggestions.take(SearchConfig.MAX_NORESULT_SUGGESTIONS)) {
                    SearchPillChip(
                        text = sug,
                        icon = WallIcons.Search,
                        onClick = { onSelectSuggestion(sug) },
                    )
                }
            }
        }
        if (state.nonSearchableSourceNames.isNotEmpty()) {
            Row(
                Modifier.padding(top = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(
                        R.string.search_noresult_unsupported,
                        state.nonSearchableSourceNames.joinToString(listSep),
                    ),
                    style = WallType.Caption,
                    color = Wall.Text2,
                )
                WallButton(
                    text = stringResource(R.string.search_noresult_go_library),
                    onClick = onGoLibrary,
                    kind = ButtonKind.Ghost,
                    trailing = WallIcons.ChevR,
                    small = true,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// State 5: expanded single-source view
// ---------------------------------------------------------------------------

@Composable
private fun ExpandedSourceView(
    paginator: ExpandedSourcePaginator,
    mediaSource: MediaSource?,
    progressMap: Map<Pair<String, String>, WatchProgress>,
    listState: LazyListState,
    onBack: () -> Unit,
    onItemClick: (Item) -> Unit,
) {
    val state by paginator.state.collectAsState()
    val packedRows = remember(state.items) { SearchRowPacker.pack(state.items) }

    // Preload next page when within ~1.5 screens (3 packed rows) of bottom (§3.9.4)
    LaunchedEffect(listState, paginator, state.kindFilter) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            Triple(lastIdx, total, state.loadedPages)
        }
            .distinctUntilChanged()
            .collect { (lastIdx, total, _) ->
                val cur = paginator.state.value
                if (cur.hasMore && !cur.loadingNextPage && cur.pageError == null) {
                    val nearBottom = total == 0 || lastIdx >= (total - 3).coerceAtLeast(0)
                    if (nearBottom) {
                        paginator.loadNextPage()
                    }
                }
            }
    }

    val subText = stringResource(
        if (state.hasMore) R.string.search_row_sub_more else R.string.search_row_sub_exact,
        state.typeLabel,
        state.items.size,
    )

    Box(Modifier.fillMaxSize().background(Wall.Bg)) {
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, onBack, filled = true)
        }
        Row(
            Modifier
                .offset(152.dp, 32.dp)
                .width(1064.dp)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.search_expanded_title, state.displayName, state.query),
                style = WallType.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(subText, style = WallType.Caption, color = Wall.Text2, maxLines = 1)
        }

        SearchKindFilterStrip(
            selected = state.kindFilter,
            availableKinds = null,
            onSelect = {
                paginator.switchKindFilter(it)
            },
            modifier = Modifier.offset(64.dp, 112.dp),
        )

        Box(
            Modifier
                .offset(0.dp, 184.dp)
                .fillMaxWidth()
                .height(536.dp)
                .clipToBounds(),
        ) {
            CompositionLocalProvider(LocalMediaSource provides mediaSource) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    if (packedRows.isEmpty() && !state.loadingNextPage && state.pageError == null) {
                        item("expanded-empty") {
                            Box(
                                Modifier.fillMaxWidth().padding(top = 80.dp, start = Wall.Margin, end = Wall.Margin),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(
                                        R.string.search_expanded_kind_empty,
                                        state.displayName,
                                        kindFilterLabel(state.kindFilter),
                                    ),
                                    style = WallType.Body,
                                    color = Wall.Text2,
                                )
                            }
                        }
                    }

                    itemsIndexed(packedRows, key = { rowIdx, _ -> "exp-row:$rowIdx" }) { _, packedRow ->
                        Row(
                            Modifier.padding(horizontal = Wall.Margin),
                            horizontalArrangement = Arrangement.spacedBy(SearchConfig.EXPANDED_ITEM_GAP_DP.dp),
                        ) {
                            for (entry: PackedSearchItem in packedRow) {
                                SearchResultCard(
                                    item = entry.item,
                                    shape = entry.shape,
                                    localProgress = progressMap[state.sourceId to entry.item.id],
                                    onClick = { onItemClick(entry.item) },
                                )
                            }
                        }
                    }

                    if (state.loadingNextPage && state.showLoadingSkeleton) {
                        item("exp-skeleton") {
                            Column {
                                Row(
                                    Modifier.padding(horizontal = Wall.Margin),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                ) {
                                    repeat(3) {
                                        SearchSkeletonBox(Modifier.size(128.dp, 192.dp), Wall.RadiusPoster)
                                    }
                                }
                                SearchTailLine(
                                    text = stringResource(R.string.search_expanded_loading_page, state.loadingPageNumber),
                                    busy = true,
                                )
                            }
                        }
                    }

                    if (state.pageError != null) {
                        item("exp-error") {
                            val failedPage = state.failedPageNumber ?: (state.loadedPages + 1)
                            SearchStatusLine(
                                icon = WallIcons.Alert,
                                text = stringResource(R.string.search_expanded_page_error, failedPage),
                                danger = true,
                                action = {
                                    WallButton(
                                        text = stringResource(R.string.action_retry),
                                        onClick = { paginator.retryPage() },
                                        icon = WallIcons.Refresh,
                                        small = true,
                                    )
                                },
                            )
                        }
                    } else if (state.reachedEnd && state.items.isNotEmpty()) {
                        item("exp-end") {
                            SearchTailLine(
                                text = stringResource(R.string.search_expanded_end_no_more, state.items.size),
                                busy = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared cards, chips, skeletons & metadata formatters
// ---------------------------------------------------------------------------

@Composable
private fun SearchResultCard(
    item: Item,
    shape: SearchCardShape,
    onClick: () -> Unit,
    localProgress: WatchProgress? = null,
    metaOverride: String? = null,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(src)
    val scale by animateFloatAsState(if (hovered) 1.06f else 1f, tween(140, easing = Wall.Ease), label = "ri-scale")
    val cardShape = RoundedCornerShape(Wall.RadiusPoster)
    val widthDp = shape.widthDp.dp

    val watchState = remember(item, localProgress) {
        DetailStateLogic.computeWatchState(item, localProgress)
    }
    val inProgress = watchState as? WatchState.InProgress
    val progressFraction = inProgress?.fraction
    val isDirectPlayVideo = (item.kind == ItemKind.VIDEO || item.kind == ItemKind.LIVE) &&
        item.episodes.isEmpty() && item.seasons.isEmpty()
    val metaText = metaOverride ?: formatSearchItemMeta(item, inProgress)

    Column(Modifier.width(widthDp).ray(src, onClick)) {
        val boxMod = Modifier
            .width(widthDp)
            .height(SearchCardShape.IMAGE_HEIGHT_DP.dp)
            .zIndex(if (scale > 1f) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = (scale - 1f) / 0.06f * 24.dp.toPx()
                this.shape = cardShape
                clip = true
            }

        if (shape == SearchCardShape.FOLDER) {
            Box(boxMod.background(Wall.SurfaceHi), contentAlignment = Alignment.Center) {
                Icon(WallIcons.Folder, null, Modifier.size(48.dp), tint = Wall.Text2)
            }
        } else {
            val art: Artwork? = if (shape == SearchCardShape.LANDSCAPE) {
                item.thumb ?: item.backdrop ?: item.poster
            } else {
                item.poster ?: item.thumb ?: item.backdrop
            }
            ArtBox(art = art, seed = item.title, size = Artworks.Size.CARD, modifier = boxMod) {
                if (progressFraction != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x990E0F12)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Wall.Accent),
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    hovered && isDirectPlayVideo,
                    Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(140)),
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color(0xEBECECEE)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(WallIcons.Play, null, Modifier.size(24.dp).offset(x = 2.dp), tint = Wall.Bg)
                    }
                }
            }
        }

        Text(
            item.title,
            style = WallType.Caption.copy(fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
            color = Wall.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (metaText.isNotEmpty()) {
            Text(
                metaText,
                style = WallType.Caption.copy(lineHeight = 34.sp),
                color = Wall.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchMoreTile(
    count: Int,
    hasMore: Boolean,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(src)
    val scale by animateFloatAsState(if (hovered) 1.06f else 1f, tween(140, easing = Wall.Ease), label = "more-scale")
    val bg by animateColorAsState(if (hovered) Wall.SurfaceHi else Wall.Surface, tween(120), label = "more-bg")
    val cardShape = RoundedCornerShape(Wall.RadiusPoster)

    Column(Modifier.width(128.dp).ray(src, onClick)) {
        Column(
            Modifier
                .size(128.dp, SearchCardShape.IMAGE_HEIGHT_DP.dp)
                .zIndex(if (scale > 1f) 1f else 0f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = (scale - 1f) / 0.06f * 24.dp.toPx()
                    shape = cardShape
                    clip = true
                }
                .background(bg),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.search_more_all), style = WallType.Label.copy(fontWeight = FontWeight.SemiBold))
            Text(
                stringResource(if (hasMore) R.string.search_more_count_plus else R.string.search_more_count, count),
                style = WallType.Caption,
                color = Wall.Text2,
            )
            Icon(WallIcons.ChevR, null, Modifier.size(24.dp), tint = Wall.Text2)
        }
    }
}

@Composable
private fun SearchPillChip(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(src)
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f, tween(120, easing = Wall.Ease), label = "chip-s")
    val bg by animateColorAsState(if (hovered) Wall.FillHi else Wall.Fill, tween(120), label = "chip-bg")
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(bg)
            .ray(src, onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = Wall.Text2)
        Text(text, style = WallType.Caption, color = Wall.Text, maxLines = 1)
    }
}

@Composable
private fun SearchSkeletonBox(modifier: Modifier, radius: Dp = 12.dp) {
    val pulse = rememberInfiniteTransition(label = "search-sk")
    val alpha by pulse.animateFloat(
        1f,
        0.55f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "search-sk",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(radius))
            .background(Wall.Surface),
    )
}

@Composable
private fun formatSearchItemMeta(item: Item, inProgress: WatchState.InProgress?): String {
    if (inProgress != null) {
        return stringResource(R.string.home_continue_left_min, minutesLeft(inProgress.remainingMs.coerceAtLeast(1L)))
    }
    return when (item.kind) {
        ItemKind.FOLDER -> {
            val c = item.childCount
            if (c != null && c >= 0) {
                stringResource(R.string.search_kind_folder_count, c)
            } else {
                item.subtitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search_kind_folder)
            }
        }
        ItemKind.MOVIE -> {
            val y = item.year?.takeIf { it > 0 }
            if (y != null) stringResource(R.string.search_kind_movie_year, y)
            else stringResource(R.string.search_kind_movie_label)
        }
        ItemKind.SERIES -> {
            val y = item.year?.takeIf { it > 0 }
            if (y != null) stringResource(R.string.search_kind_series_year, y)
            else stringResource(R.string.search_kind_series_label)
        }
        ItemKind.EPISODE -> {
            item.subtitle?.takeIf { it.isNotBlank() }
                ?: item.durationMs?.takeIf { it > 0L }?.let { formatDuration(it) }
                ?: stringResource(R.string.search_kind_episode_label)
        }
        ItemKind.VIDEO -> {
            item.subtitle?.takeIf { it.isNotBlank() }
                ?: item.durationMs?.takeIf { it > 0L }?.let { formatDuration(it) }
                ?: stringResource(R.string.search_kind_video_label)
        }
        ItemKind.LIVE -> item.subtitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search_kind_live_label)
        ItemKind.COLLECTION -> item.subtitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search_kind_collection_label)
        ItemKind.PERSON -> item.subtitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search_kind_person_label)
        ItemKind.SEASON -> item.subtitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search_kind_series_label)
    }
}

@Composable
private fun shortKindLabel(kind: ItemKind): String = when (kind) {
    ItemKind.MOVIE -> stringResource(R.string.search_kind_movie_label)
    ItemKind.SERIES, ItemKind.SEASON -> stringResource(R.string.search_kind_series_label)
    ItemKind.EPISODE -> stringResource(R.string.search_kind_episode_label)
    ItemKind.VIDEO -> stringResource(R.string.search_kind_video_label)
    ItemKind.LIVE -> stringResource(R.string.search_kind_live_label)
    ItemKind.FOLDER -> stringResource(R.string.search_kind_folder)
    ItemKind.COLLECTION -> stringResource(R.string.search_kind_collection_label)
    ItemKind.PERSON -> stringResource(R.string.search_kind_person_label)
}

@Composable
private fun kindFilterLabel(filter: SearchKindFilter): String = when (filter) {
    SearchKindFilter.ALL -> stringResource(R.string.search_kind_all)
    SearchKindFilter.MOVIE -> stringResource(R.string.search_kind_movie)
    SearchKindFilter.SERIES -> stringResource(R.string.search_kind_series)
    SearchKindFilter.VIDEO -> stringResource(R.string.search_kind_video)
}
