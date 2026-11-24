package com.limelight.local.media

/**
 * The media model every source speaks, from docs/product-plan.md 2.4 and
 * docs/specs/plugin-protocol.md: Source → Category → Item → PlaybackInfo.
 * The built-in sources (this headset, WebDAV) and the QuickJS plugins all
 * hand the wall these, so nothing in here may assume where the bytes come from.
 */

/** One configured source, such as "this headset" or "Google Drive". */
data class Source(
    val id: String,
    val name: String,
    val kind: SourceKind,
    /** One line under the name, such as "WebDAV · 192.168.1.10:8890". */
    val description: String,
)

enum class SourceKind { LOCAL, WEBDAV, PLUGIN }

/** A browsable group inside a source: a library, a folder, "recently added". */
data class Category(
    val id: String,
    val title: String,
    val count: Int? = null,
    val icon: String? = null,
)

enum class ShelfStyle { POSTER, THUMB, CONTINUE, HERO, CATEGORY }

/** One row on the home wall: a category and the first few of its items. */
data class Shelf(
    val category: Category,
    val items: List<Item>,
    val style: ShelfStyle = ShelfStyle.POSTER,
    val moreCategoryId: String? = null,
)

enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE, FOLDER, VIDEO, LIVE, COLLECTION, PERSON }

/** How the picture is laid out, so native 3D can skip the depth model later. */
enum class StereoHint { MONO, SBS, OU, VR180, VR360, MVHEVC }

/**
 * Something to draw for an item. A source with no artwork points at the
 * video itself and the app grabs a frame; [cacheKey] must change when the
 * file does, since frames are cached on disk under it.
 */
data class Artwork(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isVideoFrame: Boolean = false,
    val cacheKey: String = url,
    val blurhash: String? = null,
)

data class PersonCredit(
    val name: String,
    val role: String? = null,
    val image: Artwork? = null,
    val id: String? = null,
)

data class ExtraField(
    val label: String,
    val value: String,
)

data class Item(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val poster: Artwork? = null,
    val backdrop: Artwork? = null,
    val year: Int? = null,
    val overview: String? = null,
    val rating: Float? = null,
    val durationMs: Long? = null,
    /** Where the source itself says playback was left (Jellyfin keeps one). */
    val progressMs: Long? = null,
    val stereoHint: StereoHint = StereoHint.MONO,
    /** Source specific detail for the meta line: folder, size, resolution. */
    val subtitle: String? = null,
    /** For a folder without art: a few of its videos to draw as a grid. */
    val previews: List<Artwork> = emptyList(),
    val childCount: Int? = null,
    val thumb: Artwork? = null,
    val played: Boolean? = null,
    val favorite: Boolean? = null,
    val badges: List<String> = emptyList(),
    val parentId: String? = null,
    val index: Int? = null,
    val seasons: List<Item> = emptyList(),
    val episodes: List<Item> = emptyList(),
    val related: List<Item> = emptyList(),
    val people: List<PersonCredit> = emptyList(),
    val genres: List<String> = emptyList(),
    val extra: List<ExtraField> = emptyList(),
) {
    val isFolder: Boolean get() = kind == ItemKind.FOLDER
}

data class Page<T>(
    val items: List<T>,
    /** The page to ask for next, or null at the end. */
    val nextPage: Int?,
    /** Opaque pagination cursor from plugin protocol v1. */
    val nextCursor: String? = null,
)

data class MediaTrack(
    val id: String,
    val url: String,
    val backupUrls: List<String> = emptyList(),
    val mime: String,
    val codecs: String,
    val bandwidth: Long,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val quality: String? = null,
    val initRange: String? = null,
    val indexRange: String? = null,
)

data class DashStream(
    val video: List<MediaTrack>,
    val audio: List<MediaTrack> = emptyList(),
)

data class QualityOption(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val locked: String? = null,
)

enum class SubtitleFormat { SRT, VTT, ASS, JSON }

data class SubtitleTrack(
    val id: String,
    val label: String,
    val lang: String? = null,
    val url: String,
    val format: SubtitleFormat,
    val headers: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false,
)

data class AudioTrackOption(
    val id: String,
    val label: String,
    val lang: String? = null,
)

data class PlaybackInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    val startPositionMs: Long = 0,
    val stereoHint: StereoHint = StereoHint.MONO,
    val dash: DashStream? = null,
    val mpd: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val qualities: List<QualityOption> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val audios: List<AudioTrackOption> = emptyList(),
    val isLive: Boolean = false,
    val expiresAtMs: Long? = null,
    val nextItemId: String? = null,
    val sessionId: String? = null,
)

/** The source cannot be reached right now; the wall shows it as "连不上". */
open class SourceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The source works but the user has to do something first (a permission, initial configuration). */
open class SourceNeedsSetupException(message: String) : Exception(message)

/**
 * Credentials are missing or expired and the user must sign in again.
 * Intentionally does NOT extend [SourceNeedsSetupException] so legacy wall
 * screens do not mislabel re-authentication as storage permission setup.
 */
class SourceNeedsAuthException(message: String) : Exception(message)

/** Login attempt failed with a user-facing reason. */
class SourceAuthFailedException(message: String) : Exception(message)

/** The item is gone, for example a deleted file. */
class ItemNotFoundException(
    val itemId: String,
    message: String = "no item $itemId",
) : Exception(message)

/** Access was denied (region lock, membership required, purchase required). */
class SourceForbiddenException(message: String) : Exception(message)

/** Source is rate-limiting requests; callers may retry after [retryAfterSec]. */
class SourceRateLimitedException(
    message: String,
    val retryAfterSec: Int? = null,
) : Exception(message)

/** Content cannot be played (for example DRM-protected streams in protocol v1). */
class SourceUnsupportedException(message: String) : Exception(message)

/** Plugin crashed, returned malformed data, or raised an internal error. */
class PluginInternalException(message: String, cause: Throwable? = null) : Exception(message, cause)
