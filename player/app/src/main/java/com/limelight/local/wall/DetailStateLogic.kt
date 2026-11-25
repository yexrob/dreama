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
 * Pure, JVM-testable helper logic for movie & series detail screens (`movie.html` & `series.html`):
 * - Classifying series vs movie layout
 * - Merging local `WatchProgress` with plugin-provided `progressMs`/`played`
 * - Picking the next-up episode and initial horizontal scroll index
 * - Formatting metadata strips & capability checks
 */
object DetailStateLogic {
    private const val RESUME_MIN_MS = 10_000L
    private const val WATCHED_FRACTION = 0.95

    fun isSeriesLayout(item: Item): Boolean =
        item.kind == ItemKind.SERIES || item.seasons.isNotEmpty() || item.episodes.isNotEmpty()

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
    ): SeriesPlayTarget? {
        if (episodes.isEmpty()) return null

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
     * Resolves the initial default season tab and primary CTA target across ALL seasons of a
     * series (#5):
     * 1. Inspects local [WatchProgress] (newest `updatedAt` first) to find the most recently
     *    watched episode belonging to this series (preloaded episodes -> `detail(epId).parentId`
     *    fast path -> season episode list scan fallback).
     * 2. If that episode is [WatchState.InProgress]: selects its season and resumes that episode.
     * 3. If that episode is [WatchState.Watched]: advances to the next episode in the same season,
     *    or rolls over to episode 1 of the NEXT season if it was the last episode of its season
     *    (keeping `isResume = true` so the primary CTA reads "继续播放 · 第 (X+1) 季第 1 集" and
     *    matches the default selected season tab).
     * 4. If no watch record exists for any season: selects the first season and delegates to
     *    [pickPlayTarget] (which falls back to S1E1).
     */
    suspend fun resolveInitialSeriesSeasonAndTarget(
        source: MediaSource,
        seasons: List<Item>,
        localProgress: Map<String, WatchProgress>,
        loadSeasonEpisodes: suspend (seasonId: String) -> List<Item>,
        loadMoreInSeason: (suspend (seasonId: String) -> List<Item>)? = null,
    ): SeriesInitialResolution? {
        if (seasons.isEmpty()) return null

        fun seasonNumberAt(idx: Int): Int = seasons[idx].index?.takeIf { it > 0 } ?: (idx + 1)

        val seasonIndexById = seasons.withIndex().associate { (i, s) -> s.id to i }
        val localSorted = localProgress.values.sortedByDescending { it.updatedAt }

        var matchedSeasonIdx: Int? = null
        var matchedEpisodeId: String? = null
        var matchedProgress: WatchProgress? = null

        if (localSorted.isNotEmpty()) {
            // Fast path 1: check preloaded episodes already attached to season items
            var bestTs = Long.MIN_VALUE
            for ((sIdx, season) in seasons.withIndex()) {
                for (ep in season.episodes) {
                    val prog = localProgress[ep.id] ?: continue
                    if (prog.updatedAt > bestTs) {
                        bestTs = prog.updatedAt
                        matchedSeasonIdx = sIdx
                        matchedEpisodeId = ep.id
                        matchedProgress = prog
                    }
                }
            }

            // Fast path 2: inspect `detail(epId).parentId` on the newest local progress entries
            if (matchedSeasonIdx == null) {
                for (entry in localSorted.take(8)) {
                    val detailItem = try {
                        source.detail(entry.itemId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val idx = detailItem?.parentId?.let { seasonIndexById[it] }
                    if (idx != null) {
                        matchedSeasonIdx = idx
                        matchedEpisodeId = entry.itemId
                        matchedProgress = entry
                        break
                    }
                }
            }

            // Fallback path: scan seasons' episodes to locate the most recently watched episode
            if (matchedSeasonIdx == null) {
                var fallbackTs = Long.MIN_VALUE
                for ((sIdx, season) in seasons.withIndex()) {
                    val eps = try {
                        loadSeasonEpisodes(season.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    for (ep in eps) {
                        val prog = localProgress[ep.id] ?: continue
                        if (prog.updatedAt > fallbackTs) {
                            fallbackTs = prog.updatedAt
                            matchedSeasonIdx = sIdx
                            matchedEpisodeId = ep.id
                            matchedProgress = prog
                        }
                    }
                }
            }
        }

        if (matchedSeasonIdx == null || matchedEpisodeId == null) {
            val firstSeason = seasons.first()
            val eps = try {
                loadSeasonEpisodes(firstSeason.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            return SeriesInitialResolution(
                seasonId = firstSeason.id,
                seasonNumber = seasonNumberAt(0),
                target = pickPlayTarget(seasonNumberAt(0), eps, localProgress),
            )
        }

        val sIdx = matchedSeasonIdx
        val season = seasons[sIdx]
        val seasonNum = seasonNumberAt(sIdx)
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
            return SeriesInitialResolution(
                seasonId = season.id,
                seasonNumber = seasonNum,
                target = pickPlayTarget(seasonNum, episodes, localProgress),
            )
        }

        val ep = episodes[epIdx]
        val watchState = computeWatchState(ep, matchedProgress)
        return when (watchState) {
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
                } else if (sIdx + 1 < seasons.size) {
                    val nextSeasonIdx = sIdx + 1
                    val nextSeason = seasons[nextSeasonIdx]
                    val nextSeasonNum = seasonNumberAt(nextSeasonIdx)
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
                    val firstSeason = seasons.first()
                    val firstEps = if (sIdx == 0) episodes else try {
                        loadSeasonEpisodes(firstSeason.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val firstEp = firstEps.firstOrNull()
                    SeriesInitialResolution(
                        seasonId = firstSeason.id,
                        seasonNumber = seasonNumberAt(0),
                        target = firstEp?.let {
                            SeriesPlayTarget(
                                seasonNumber = seasonNumberAt(0),
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
        return if (hasSeasons && seasonNum != null) {
            if (target.isResume) {
                strings.get(R.string.series_resume_season_ep, seasonNum, target.episodeNumber)
            } else {
                strings.get(R.string.series_play_season_ep, seasonNum, target.episodeNumber)
            }
        } else {
            if (target.isResume) {
                strings.get(R.string.series_resume_ep, target.episodeNumber)
            } else {
                strings.get(R.string.series_play_ep, target.episodeNumber)
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

    suspend fun loadNextPage(): SeasonEpisodePageState {
        val current = state
        val pageToLoad = current.nextPage ?: return current
        if (current.loading) return current

        state = current.copy(loading = true, error = null)
        return try {
            val page = source.browse(seasonId, pageToLoad)
            if (pageToLoad == 0 && page.items.isEmpty()) {
                val detailItems = runCatching { source.detail(seasonId).episodes }.getOrDefault(emptyList())
                state = SeasonEpisodePageState(
                    items = detailItems,
                    nextPage = null,
                    loading = false,
                    error = null,
                    loadedOnce = true,
                )
            } else {
                val existingIds = current.items.asSequence().map { it.id }.toHashSet()
                val appended = page.items.filter { it.id !in existingIds }
                state = SeasonEpisodePageState(
                    items = current.items + appended,
                    nextPage = page.nextPage,
                    loading = false,
                    error = null,
                    loadedOnce = true,
                )
            }
            state
        } catch (e: CancellationException) {
            state = current.copy(loading = false)
            throw e
        } catch (e: Exception) {
            state = current.copy(loading = false, error = e, loadedOnce = true)
            state
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
