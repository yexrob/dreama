package com.limelight.local.media

/**
 * The media model every source speaks, from docs/product-plan.md 2.4:
 * Source → Category → Item → PlaybackInfo. The built-in sources (this
 * headset, WebDAV) and the later QuickJS plugins all hand the wall these, so
 * nothing in here may assume where the bytes come from.
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
)

/** One row on the home wall: a category and the first few of its items. */
data class Shelf(
    val category: Category,
    val items: List<Item>,
)

enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE, FOLDER, VIDEO, LIVE }

/** How the picture is laid out, so native 3D can skip the depth model later. */
enum class StereoHint { MONO, SBS, OU, VR180, VR360 }

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
) {
    val isFolder: Boolean get() = kind == ItemKind.FOLDER
}

data class Page<T>(
    val items: List<T>,
    /** The page to ask for next, or null at the end. */
    val nextPage: Int?,
)

data class PlaybackInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String,
    val startPositionMs: Long = 0,
    val stereoHint: StereoHint = StereoHint.MONO,
)

/** The source cannot be reached right now; the wall shows it as "连不上". */
class SourceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The source works but the user has to do something first (a permission, a login). */
class SourceNeedsSetupException(message: String) : Exception(message)

/** The item is gone, for example a deleted file. */
class ItemNotFoundException(itemId: String) : Exception("no item $itemId")
