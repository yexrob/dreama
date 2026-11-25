package com.limelight.local.wall

import com.limelight.R
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.StereoHint
import com.limelight.local.media.WatchProgress
import com.limelight.local.plugin.PluginCapability
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.stream.StringLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Watch state of an episode or movie merged from local Room history and plugin metadata. */
sealed interface WatchState {
    data object Unwatched : WatchState
    data object Watched : WatchState
    data class InProgress(
        val positionMs: Long,
        val durationMs: Long,
    ) : WatchState {
        val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
        val fraction: Float get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0.02f, 1f) else 0f
    }
}

/** Smart primary action target computed for a Series / multi-part detail screen. */
data class SeriesPlayTarget(
    val seasonNumber: Int?,
    val episodeNumber: Int,
    val episode: Item,
    val resumePositionMs: Long,
    val isResume: Boolean,
)

/** Cross-season default selection resolved when opening a Series detail screen (#5). */
data class SeriesInitialResolution(
    val seasonId: String,
    val seasonNumber: Int?,
    val target: SeriesPlayTarget?,
)

/**
 * Resolved detail screen target: when opened from an [ItemKind.EPISODE] (e.g. a search result
 * card per `ux-spec.md` §3.9.7), resolves up through its parent Season/Series (or multi-part video)
 * so the user lands on the series detail page focused directly on that episode.
 */
data class EpisodeTargetResolution(
    val displayItem: Item,
    val focusSeasonId: String? = null,
    val focusEpisodeId: String? = null,
)

/**
 * Pure, JVM-testable helper logic for movie & series detail screens (`movie.html` & `series.html`):
 * - Classifying series vs movie layout
 * - Merging local `WatchProgress` with plugin-provided `progressMs`/`played`
 * - Picking the next-up episode and initial horizontal scroll index
 * - Formatting metadata strips & capability checks
 */
object DetailStateLogic {
    private const val RESUME_MIN_MS = 10_000L
    private const val WATCHED_FRACTION = 0.95
    const val DEFAULT_RESOLVE_TIMEOUT_MS = 2_000L

    fun isSeriesLayout(item: Item): Boolean =
        item.kind == ItemKind.SERIES || item.seasons.isNotEmpty() || item.episodes.isNotEmpty()

    /**
     * Resolves an item opened in [DetailScreen]:
     * - For non-episodes, calls `source.detail(initialItem.id)` directly.
     * - For an [ItemKind.EPISODE], traverses `parentId` (Episode -> Season -> Series, or Episode ->
     *   multi-part video / Series) so the user lands on the owning series page focused on that
     *   exact episode (`ux-spec.md` §3.9.7). Falls back gracefully to the episode's own detail
     *   page if parent metadata is unavailable.
     */
    suspend fun resolveDetailTarget(
        source: MediaSource,
        initialItem: Item,
        requestedFocusSeasonId: String? = null,
        requestedFocusEpisodeId: String? = null,
    ): EpisodeTargetResolution {
        if (initialItem.kind != ItemKind.EPISODE) {
            return EpisodeTargetResolution(
                displayItem = source.detail(initialItem.id),
                focusSeasonId = requestedFocusSeasonId,
                focusEpisodeId = requestedFocusEpisodeId,
            )
        }
        val epDetail = try {
            source.detail(initialItem.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            initialItem
        }
        val parentId = epDetail.parentId?.takeIf { it.isNotBlank() }
            ?: initialItem.parentId?.takeIf { it.isNotBlank() }
        if (parentId != null) {
            val parent = try {
                source.detail(parentId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (parent != null) {
                if (parent.kind == ItemKind.SEASON && !parent.parentId.isNullOrBlank()) {
                    val series = try {
                        source.detail(parent.parentId)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null
                    }
                    if (series != null && isSeriesLayout(series)) {
                        return EpisodeTargetResolution(
                            displayItem = series,
                            focusSeasonId = parent.id,
                            focusEpisodeId = epDetail.id,
                        )
                    }
                }
                if (isSeriesLayout(parent)) {
                    return EpisodeTargetResolution(
                        displayItem = parent,
                        focusSeasonId = parent.id.takeIf { parent.kind == ItemKind.SEASON },
                        focusEpisodeId = epDetail.id,
                    )
                }
            }
        }
        return EpisodeTargetResolution(displayItem = epDetail)
    }

    /** Specials seasons use index == 0 (e.g. Jellyfin Season 0 "Specials") and are sorted last (#10). */
    fun isSpecialsSeason(season: Item): Boolean = season.index == 0

    /** Places regular seasons first (preserving order) and Specials (`index == 0`) at the end (#10). */
    fun orderSeasons(seasons: List<Item>): List<Item> {
        if (seasons.size <= 1) return seasons
        val regular = ArrayList<Item>(seasons.size)
        val specials = ArrayList<Item>()
        for (s in seasons) {
            if (isSpecialsSeason(s)) specials += s else regular += s
        }
        return if (specials.isEmpty()) seasons else regular + specials
    }

    /** Picks the default season when there is no watch history: first regular season, or Specials only if it is the sole season. */
    fun defaultSeason(seasons: List<Item>): Item? {
        val ordered = orderSeasons(seasons)
        return ordered.firstOrNull { !isSpecialsSeason(it) } ?: ordered.firstOrNull()
    }

    /** Computes the display season number (`0` for Specials, positive index or regular ordinal otherwise). */
    fun seasonNumberFor(orderedSeasons: List<Item>, seasonIndex: Int): Int? {
        val season = orderedSeasons.getOrNull(seasonIndex) ?: return null
        if (isSpecialsSeason(season)) return 0
        season.index?.takeIf { it > 0 }?.let { return it }
        var regularOrdinal = 0
        for (i in 0..seasonIndex) {
            if (!isSpecialsSeason(orderedSeasons[i])) regularOrdinal++
        }
        return regularOrdinal.coerceAtLeast(1)
    }

    fun formatSeasonTabLabel(orderedSeasons: List<Item>, seasonIndex: Int, strings: StringLookup): String {
        val season = orderedSeasons[seasonIndex]
        if (isSpecialsSeason(season)) {
            return strings.get(R.string.series_specials_tab)
        }
        val num = seasonNumberFor(orderedSeasons, seasonIndex) ?: (seasonIndex + 1)
        return season.title.takeIf { it.isNotBlank() } ?: strings.get(R.string.series_season_tab, num)
    }

    fun regularSeasonCount(seasons: List<Item>): Int =
        seasons.count { !isSpecialsSeason(it) }.takeIf { it > 0 } ?: seasons.size

    fun supportsFavorites(source: MediaSource?): Boolean =
        (source as? PluginMediaSource)?.capabilities?.contains(PluginCapability.FAVORITES) == true

    fun supportsPlayed(source: MediaSource?): Boolean =
        (source as? PluginMediaSource)?.capabilities?.contains(PluginCapability.PLAYED) == true

    fun computeWatchState(item: Item, local: WatchProgress?): WatchState {
        // Explicit played override from plugin takes priority when no newer local resumable state
        val localResumable = local?.takeIf { it.resumable }
        if (localResumable != null) {
            return WatchState.InProgress(
                positionMs = localResumable.positionMs,
                durationMs = localResumable.durationMs,
            )
        }
        if (local != null && local.durationMs > 0L && local.positionMs >= local.durationMs * WATCHED_FRACTION) {
            return WatchState.Watched
        }
        val remotePos = item.progressMs ?: 0L
        val remoteDur = item.durationMs ?: 0L
        if (remotePos > RESUME_MIN_MS && remoteDur > 0L && remotePos < remoteDur * WATCHED_FRACTION) {
            return WatchState.InProgress(positionMs = remotePos, durationMs = remoteDur)
        }
        if (item.played == true) {
            return WatchState.Watched
        }
        return WatchState.Unwatched
    }

    /**
     * Picks the primary button target for a series/season:
     * 1. In-progress episode (prefers most recently updated local progress)
     * 2. First unwatched episode after any watched episode
     * 3. First episode in the season
     */
    fun pickPlayTarget(
        seasonNumber: Int?,
        episodes: List<Item>,
        localProgress: Map<String, WatchProgress>,
        focusEpisodeId: String? = null,
    ): SeriesPlayTarget? {
        if (episodes.isEmpty()) return null

        if (!focusEpisodeId.isNullOrBlank()) {
            val focusIdx = episodes.indexOfFirst { it.id == focusEpisodeId }
            if (focusIdx >= 0) {
                val ep = episodes[focusIdx]
                val ws = computeWatchState(ep, localProgress[ep.id])
                return SeriesPlayTarget(
                    seasonNumber = seasonNumber,
                    episodeNumber = ep.index?.takeIf { it > 0 } ?: (focusIdx + 1),
                    episode = ep,
                    resumePositionMs = (ws as? WatchState.InProgress)?.positionMs ?: 0L,
                    isResume = ws is WatchState.InProgress,
                )
            }
        }

        val states = episodes.mapIndexed { idx, ep ->
            Triple(idx, ep, computeWatchState(ep, localProgress[ep.id]))
        }

        // 1. In-progress episode (prefer newest local WatchProgress timestamp)
        val inProgress = states.filter { it.third is WatchState.InProgress }
        if (inProgress.isNotEmpty()) {
            val best = inProgress.maxBy { (_, ep, _) -> localProgress[ep.id]?.updatedAt ?: 0L }
            val prog = best.third as WatchState.InProgress
            return SeriesPlayTarget(
                seasonNumber = seasonNumber,
                episodeNumber = best.second.index?.takeIf { it > 0 } ?: (best.first + 1),
                episode = best.second,
                resumePositionMs = prog.positionMs,
                isResume = true,
            )
        }

        // 2. First unwatched episode after at least one watched episode
        val anyWatched = states.any { it.third is WatchState.Watched }
        if (anyWatched) {
            val nextUnwatched = states.firstOrNull { it.third is WatchState.Unwatched }
            if (nextUnwatched != null) {
                return SeriesPlayTarget(
                    seasonNumber = seasonNumber,
                    episodeNumber = nextUnwatched.second.index?.takeIf { it > 0 } ?: (nextUnwatched.first + 1),
                    episode = nextUnwatched.second,
                    resumePositionMs = 0L,
                    isResume = true,
                )
            }
        }

        // 3. Fallback: first episode
        val first = episodes.first()
        return SeriesPlayTarget(
            seasonNumber = seasonNumber,
            episodeNumber = first.index?.takeIf { it > 0 } ?: 1,
            episode = first,
            resumePositionMs = 0L,
            isResume = false,
        )
    }

    /**
     * Resolves the initial default season tab and primary CTA target across a series (#5, #10):
     * - Orders regular seasons first and Specials (`index == 0`) last; default season skips
     *   Specials unless Specials is the only season.
     * - Matches local [WatchProgress] STRICTLY by local `parentId` hitting this series' seasons
     *   (`WatchProgress.parentId`, in-memory [knownParentIds] from shelves, or preloaded
     *   `season.episodes`). Issues ZERO `source.detail()` probes and ZERO extra season `browse()`
     *   calls when nothing matches — unwatched series immediately fall back to Season 1.
     * - Wraps the entire resolution in an overall [timeoutMs] (default 2s); on timeout or no match,
     *   immediately falls back to the default regular season.
     * - If the matched episode is [WatchState.Watched] and is the last episode of its season,
     *   rolls over to episode 1 of the next REGULAR season (never rolling into Specials).
     */
    suspend fun resolveInitialSeriesSeasonAndTarget(
        source: MediaSource,
        seasons: List<Item>,
        localProgress: Map<String, WatchProgress>,
        loadSeasonEpisodes: suspend (seasonId: String) -> List<Item>,
        loadMoreInSeason: (suspend (seasonId: String) -> List<Item>)? = null,
        knownParentIds: Map<String, String?> = emptyMap(),
        timeoutMs: Long = DEFAULT_RESOLVE_TIMEOUT_MS,
        focusSeasonId: String? = null,
        focusEpisodeId: String? = null,
    ): SeriesInitialResolution? {
        if (seasons.isEmpty()) return null
        val ordered = orderSeasons(seasons)
        val defaultIdx = ordered.indexOfFirst { !isSpecialsSeason(it) }.takeIf { it >= 0 } ?: 0
        val defaultSeasonItem = ordered[defaultIdx]
        val defaultSeasonNum = seasonNumberFor(ordered, defaultIdx)

        val resolved = withTimeoutOrNull(timeoutMs) {
            val seasonIndexById = ordered.withIndex().associate { (i, s) -> s.id to i }
            val preloadedSeasonByEpId = HashMap<String, Int>()
            for ((sIdx, season) in ordered.withIndex()) {
                for (ep in season.episodes) {
                    preloadedSeasonByEpId[ep.id] = sIdx
                }
            }

            // Explicit episode focus (e.g. clicking an episode card in Search results, §3.9.7)
            if (!focusEpisodeId.isNullOrBlank()) {
                val explicitSeasonIdx = focusSeasonId?.let { seasonIndexById[it] }
                    ?: preloadedSeasonByEpId[focusEpisodeId]
                    ?: defaultIdx
                val targetSeason = ordered[explicitSeasonIdx]
                val targetSeasonNum = seasonNumberFor(ordered, explicitSeasonIdx)
                var eps = try {
                    loadSeasonEpisodes(targetSeason.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                var epIdx = eps.indexOfFirst { it.id == focusEpisodeId }
                var pagesTried = 0
                while (epIdx < 0 && loadMoreInSeason != null && pagesTried < 5) {
                    pagesTried++
                    val expanded = try {
                        loadMoreInSeason(targetSeason.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        break
                    }
                    if (expanded.size <= eps.size) break
                    eps = expanded
                    epIdx = eps.indexOfFirst { it.id == focusEpisodeId }
                }
                return@withTimeoutOrNull SeriesInitialResolution(
                    seasonId = targetSeason.id,
                    seasonNumber = targetSeasonNum,
                    target = pickPlayTarget(targetSeasonNum, eps, localProgress, focusEpisodeId),
                )
            }

            var matchedSeasonIdx: Int? = null
            var matchedEpisodeId: String? = null
            var matchedProgress: WatchProgress? = null

            // Local-only parentId matching (0 network requests): check WatchProgress.parentId,
            // knownParentIds cache, and preloaded season episodes, preferring newest updatedAt.
            val localSorted = localProgress.values.sortedByDescending { it.updatedAt }
            for (entry in localSorted) {
                val idx = entry.parentId?.let { seasonIndexById[it] }
                    ?: knownParentIds[entry.itemId]?.let { seasonIndexById[it] }
                    ?: preloadedSeasonByEpId[entry.itemId]
                if (idx != null) {
                    matchedSeasonIdx = idx
                    matchedEpisodeId = entry.itemId
                    matchedProgress = entry
                    break
                }
            }

            if (matchedSeasonIdx == null || matchedEpisodeId == null) {
                val eps = try {
                    loadSeasonEpisodes(defaultSeasonItem.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                return@withTimeoutOrNull SeriesInitialResolution(
                    seasonId = defaultSeasonItem.id,
                    seasonNumber = defaultSeasonNum,
                    target = pickPlayTarget(defaultSeasonNum, eps, localProgress),
                )
            }

            val sIdx = matchedSeasonIdx
            val season = ordered[sIdx]
            val seasonNum = seasonNumberFor(ordered, sIdx)
            var episodes = try {
                loadSeasonEpisodes(season.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            var epIdx = episodes.indexOfFirst { it.id == matchedEpisodeId }
            while (epIdx < 0 && loadMoreInSeason != null) {
                val expanded = try {
                    loadMoreInSeason(season.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break
                }
                if (expanded.size <= episodes.size) break
                episodes = expanded
                epIdx = episodes.indexOfFirst { it.id == matchedEpisodeId }
            }
            if (epIdx < 0) {
                return@withTimeoutOrNull SeriesInitialResolution(
                    seasonId = season.id,
                    seasonNumber = seasonNum,
                    target = pickPlayTarget(seasonNum, episodes, localProgress),
                )
            }

            val ep = episodes[epIdx]
            val watchState = computeWatchState(ep, matchedProgress)
            when (watchState) {
                is WatchState.InProgress -> SeriesInitialResolution(
                    seasonId = season.id,
                    seasonNumber = seasonNum,
                    target = SeriesPlayTarget(
                        seasonNumber = seasonNum,
                        episodeNumber = ep.index?.takeIf { it > 0 } ?: (epIdx + 1),
                        episode = ep,
                        resumePositionMs = watchState.positionMs,
                        isResume = true,
                    ),
                )
                WatchState.Watched -> {
                    if (epIdx == episodes.lastIndex && loadMoreInSeason != null) {
                        val expanded = try {
                            loadMoreInSeason(season.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            episodes
                        }
                        if (expanded.size > episodes.size) {
                            episodes = expanded
                        }
                    }
                    val nextRegularIdx = ((sIdx + 1) until ordered.size)
                        .firstOrNull { !isSpecialsSeason(ordered[it]) }
                    if (epIdx + 1 < episodes.size) {
                        val nextEp = episodes[epIdx + 1]
                        SeriesInitialResolution(
                            seasonId = season.id,
                            seasonNumber = seasonNum,
                            target = SeriesPlayTarget(
                                seasonNumber = seasonNum,
                                episodeNumber = nextEp.index?.takeIf { it > 0 } ?: (epIdx + 2),
                                episode = nextEp,
                                resumePositionMs = 0L,
                                isResume = true,
                            ),
                        )
                    } else if (!isSpecialsSeason(season) && nextRegularIdx != null) {
                        val nextSeason = ordered[nextRegularIdx]
                        val nextSeasonNum = seasonNumberFor(ordered, nextRegularIdx)
                        val nextSeasonEps = try {
                            loadSeasonEpisodes(nextSeason.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val nextEp = nextSeasonEps.firstOrNull()
                        SeriesInitialResolution(
                            seasonId = nextSeason.id,
                            seasonNumber = nextSeasonNum,
                            target = nextEp?.let {
                                SeriesPlayTarget(
                                    seasonNumber = nextSeasonNum,
                                    episodeNumber = it.index?.takeIf { n -> n > 0 } ?: 1,
                                    episode = it,
                                    resumePositionMs = 0L,
                                    isResume = true,
                                )
                            },
                        )
                    } else {
                        val firstEps = if (sIdx == defaultIdx) episodes else try {
                            loadSeasonEpisodes(defaultSeasonItem.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val firstEp = firstEps.firstOrNull()
                        SeriesInitialResolution(
                            seasonId = defaultSeasonItem.id,
                            seasonNumber = defaultSeasonNum,
                            target = firstEp?.let {
                                SeriesPlayTarget(
                                    seasonNumber = defaultSeasonNum,
                                    episodeNumber = it.index?.takeIf { n -> n > 0 } ?: 1,
                                    episode = it,
                                    resumePositionMs = 0L,
                                    isResume = false,
                                )
                            },
                        )
                    }
                }
                WatchState.Unwatched -> SeriesInitialResolution(
                    seasonId = season.id,
                    seasonNumber = seasonNum,
                    target = pickPlayTarget(seasonNum, episodes, localProgress),
                )
            }
        }

        // Overall timeout elapsed (default 2s): fall back immediately to default regular season
        return resolved ?: SeriesInitialResolution(
            seasonId = defaultSeasonItem.id,
            seasonNumber = defaultSeasonNum,
            target = null,
        )
    }

    /**
     * Applies a mark-played / unmark-played toggle (#16):
     * - When marking played (`nextPlayed == true`): invokes [clearLocalProgress] and clears
     *   in-memory `progressMs` so the CTA immediately switches from 「继续播放」 to 「播放」.
     * - When unmarking played (`nextPlayed == false`): does NOT restore previously deleted progress.
     */
    suspend fun applyPlayedToggle(
        item: Item,
        nextPlayed: Boolean,
        setRemotePlayed: suspend (Boolean) -> Unit,
        clearLocalProgress: suspend () -> Unit,
    ): Item {
        setRemotePlayed(nextPlayed)
        if (nextPlayed) {
            clearLocalProgress()
        }
        return item.copy(played = nextPlayed, progressMs = null)
    }

    /**
     * Keeps one watched episode visible to the left of the target episode when possible
     * (matching design-opus `series.html`).
     */
    fun initialScrollIndex(episodes: List<Item>, targetEpisodeId: String?): Int {
        if (targetEpisodeId == null || episodes.isEmpty()) return 0
        val idx = episodes.indexOfFirst { it.id == targetEpisodeId }
        if (idx <= 0) return 0
        return (idx - 1).coerceAtLeast(0)
    }

    fun formatDurationText(ms: Long, strings: StringLookup): String {
        val m = (ms / 60_000L).toInt()
        return if (m >= 60) {
            strings.get(R.string.duration_hours_minutes, m / 60, m % 60)
        } else {
            strings.get(R.string.duration_minutes, m.coerceAtLeast(1))
        }
    }

    fun formatMovieMeta(item: Item, sourceName: String?, strings: StringLookup): String {
        val parts = ArrayList<String>()
        item.year?.takeIf { it > 0 }?.let { parts += it.toString() }
        if (item.genres.isNotEmpty()) {
            parts += item.genres.take(2).joinToString(" / ")
        }
        item.durationMs?.takeIf { it > 0L }?.let { parts += formatDurationText(it, strings) }
        item.rating?.takeIf { it > 0f }?.let {
            parts += String.format(Locale.US, "★ %.1f", it)
        }
        if (item.badges.isNotEmpty()) {
            parts += item.badges.take(2).joinToString(" ")
        } else {
            item.subtitle?.trim()?.takeIf { it.isNotEmpty() && parts.size < 4 }?.let { parts += it }
        }
        sourceName?.takeIf { it.isNotBlank() }?.let {
            parts += strings.get(R.string.detail_from_source, it)
        }
        return parts.joinToString(" · ")
    }

    fun formatSeriesMeta(item: Item, seasonCount: Int, sourceName: String?, strings: StringLookup): String {
        val parts = ArrayList<String>()
        parts += strings.get(R.string.series_kind_label)
        if (seasonCount > 0) {
            parts += strings.get(R.string.series_season_count, seasonCount)
        }
        item.year?.takeIf { it > 0 }?.let { parts += it.toString() }
        item.rating?.takeIf { it > 0f }?.let {
            parts += String.format(Locale.US, "★ %.1f", it)
        }
        sourceName?.takeIf { it.isNotBlank() }?.let {
            parts += strings.get(R.string.detail_from_source, it)
        }
        return parts.joinToString(" · ")
    }

    fun formatPrimarySeriesButton(target: SeriesPlayTarget, hasSeasons: Boolean, strings: StringLookup): String {
        val seasonNum = target.seasonNumber
        return when {
            hasSeasons && seasonNum == 0 -> {
                if (target.isResume) {
                    strings.get(R.string.series_resume_specials_ep, target.episodeNumber)
                } else {
                    strings.get(R.string.series_play_specials_ep, target.episodeNumber)
                }
            }
            hasSeasons && seasonNum != null && seasonNum > 0 -> {
                if (target.isResume) {
                    strings.get(R.string.series_resume_season_ep, seasonNum, target.episodeNumber)
                } else {
                    strings.get(R.string.series_play_season_ep, seasonNum, target.episodeNumber)
                }
            }
            else -> {
                if (target.isResume) {
                    strings.get(R.string.series_resume_ep, target.episodeNumber)
                } else {
                    strings.get(R.string.series_play_ep, target.episodeNumber)
                }
            }
        }
    }

    fun stereoLabel(hint: StereoHint, strings: StringLookup): String = when (hint) {
        StereoHint.MONO -> strings.get(R.string.detail_stereo_auto)
        StereoHint.SBS -> strings.get(R.string.detail_stereo_sbs)
        StereoHint.OU -> strings.get(R.string.detail_stereo_ou)
        StereoHint.VR180 -> strings.get(R.string.detail_stereo_vr180)
        StereoHint.VR360 -> strings.get(R.string.detail_stereo_vr360)
        StereoHint.MVHEVC -> strings.get(R.string.detail_stereo_mvhevc)
    }

    /** Builds up to 4 bottom info cards (label -> value) from real item metadata. */
    fun buildMovieInfoCards(item: Item, sourceName: String?, strings: StringLookup): List<Pair<String, String>> {
        val cards = ArrayList<Pair<String, String>>(4)
        cards += strings.get(R.string.detail_info_3d) to stereoLabel(item.stereoHint, strings)
        for (extra in item.extra) {
            if (cards.size >= 4) break
            if (extra.label.isNotBlank() && extra.value.isNotBlank()) {
                cards += extra.label to extra.value
            }
        }
        if (cards.size < 4 && item.people.isNotEmpty()) {
            val names = item.people.take(3).joinToString(" · ") { it.name }
            cards += strings.get(R.string.detail_info_cast) to names
        }
        if (cards.size < 4 && item.badges.isNotEmpty()) {
            cards += strings.get(R.string.detail_info_version) to item.badges.joinToString(" · ")
        }
        if (cards.size < 4 && !sourceName.isNullOrBlank()) {
            cards += strings.get(R.string.detail_info_source) to sourceName
        }
        return cards.take(4)
    }
}

/** Snapshot state for one season's lazily paginated episode list. */
data class SeasonEpisodePageState(
    val items: List<Item> = emptyList(),
    val nextPage: Int? = 0,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val loadedOnce: Boolean = false,
)

/**
 * Pure, JVM-testable paginated loader for a single season's episodes.
 * Loads page-by-page via `source.browse(seasonId, page)` on demand and falls back to
 * `source.detail(seasonId).episodes` if `browse(seasonId, 0)` returns an empty first page
 * (supporting both live Jellyfin paginated `/Items?ParentId=` and fixture-backed `detail(seasonId)`).
 */
class SeasonEpisodeLoader(
    private val source: MediaSource,
    private val seasonId: String,
    preloadedEpisodes: List<Item> = emptyList(),
    private val isPreloadedOnly: Boolean = false,
) {
    private val loadMutex = Mutex()

    @Volatile
    var state: SeasonEpisodePageState = if (isPreloadedOnly || preloadedEpisodes.isNotEmpty()) {
        SeasonEpisodePageState(
            items = preloadedEpisodes,
            nextPage = null,
            loading = false,
            error = null,
            loadedOnce = true,
        )
    } else {
        SeasonEpisodePageState()
    }
        private set

    /**
     * Ensures the initial page has finished loading (#3): serialized via [loadMutex] so concurrent
     * callers wait for an in-flight first-page load and receive the exact same populated result
     * instead of returning an empty list prematurely or firing duplicate `browse` calls.
     */
    suspend fun ensureLoaded(): SeasonEpisodePageState = loadMutex.withLock {
        val cur = state
        if (cur.loadedOnce && cur.error == null) return@withLock cur
        loadNextPageLocked()
    }

    suspend fun loadNextPage(): SeasonEpisodePageState = loadMutex.withLock {
        loadNextPageLocked()
    }

    private suspend fun loadNextPageLocked(): SeasonEpisodePageState {
        val current = state
        val pageToLoad = current.nextPage ?: return current
        state = current.copy(loading = true, error = null)
        return try {
            val page = source.browse(seasonId, pageToLoad)
            val next = if (pageToLoad == 0 && page.items.isEmpty()) {
                val detailItems = runCatching { source.detail(seasonId).episodes }.getOrDefault(emptyList())
                SeasonEpisodePageState(
                    items = detailItems,
                    nextPage = null,
                    loading = false,
                    error = null,
                    loadedOnce = true,
                )
            } else {
                val existingIds = current.items.asSequence().map { it.id }.toHashSet()
                val appended = page.items.filter { it.id !in existingIds }
                SeasonEpisodePageState(
                    items = current.items + appended,
                    nextPage = page.nextPage,
                    loading = false,
                    error = null,
                    loadedOnce = true,
                )
            }
            state = next
            next
        } catch (e: CancellationException) {
            state = current.copy(loading = false)
            throw e
        } catch (e: Exception) {
            val failed = current.copy(loading = false, error = e, loadedOnce = true)
            state = failed
            failed
        }
    }

    fun resetForRetry() {
        if (!isPreloadedOnly) {
            state = SeasonEpisodePageState()
        }
    }
}

/**
 * Coordinates per-season [SeasonEpisodeLoader] instances for a Series / multi-part detail screen (#8):
 * - Never creates loaders or sends `browse()` while `loadingDetail == true`.
 * - For seasonless multi-part items (`hasSeasons == false`), replaces the loader whenever
 *   [preloadedEpisodes] changes and marks it `isPreloadedOnly = true` so no bogus `browse()` is sent.
 */
class SeriesLoaderCoordinator(
    private val source: MediaSource,
) {
    private val loaders = LinkedHashMap<String, SeasonEpisodeLoader>()
    private var lastDirectEpisodeIds: List<String>? = null

    fun loaderFor(
        seasonId: String,
        hasSeasons: Boolean,
        loadingDetail: Boolean,
        preloadedEpisodes: List<Item>,
    ): SeasonEpisodeLoader? {
        if (loadingDetail) return null
        if (!hasSeasons) {
            val ids = preloadedEpisodes.map { it.id }
            val existing = loaders[seasonId]
            if (existing == null || lastDirectEpisodeIds != ids) {
                lastDirectEpisodeIds = ids
                val fresh = SeasonEpisodeLoader(
                    source = source,
                    seasonId = seasonId,
                    preloadedEpisodes = preloadedEpisodes,
                    isPreloadedOnly = true,
                )
                loaders[seasonId] = fresh
                return fresh
            }
            return existing
        }
        return loaders.getOrPut(seasonId) { SeasonEpisodeLoader(source, seasonId) }
    }

    fun existingLoader(seasonId: String): SeasonEpisodeLoader? = loaders[seasonId]

    fun reset(seasonId: String) {
        loaders[seasonId]?.resetForRetry()
    }
}
