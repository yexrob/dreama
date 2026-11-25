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
) {
    var state: SeasonEpisodePageState = if (preloadedEpisodes.isNotEmpty()) {
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
        state = SeasonEpisodePageState()
    }
}
