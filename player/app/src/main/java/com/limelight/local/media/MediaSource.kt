package com.limelight.local.media

/**
 * What a source has to answer, and all the wall ever asks it. The QuickJS
 * plugin host will implement this same interface around a script's home(),
 * browse(), detail() and playback(), so it stays to plain data in and out,
 * every call suspends, and a call may be slow or fail.
 *
 * Failures: [SourceUnavailableException] when the source cannot be reached,
 * [SourceNeedsSetupException] when the user has to act first, and
 * [ItemNotFoundException] for an id that no longer exists.
 */
interface MediaSource {
    val source: Source

    /** The rows this source puts on the home wall, best first. */
    suspend fun home(): List<Shelf>

    /**
     * The items of a category, or of a folder item (a folder's id is also a
     * category id). [ROOT] is the source's top level, which the library shows
     * when the source is picked. Pages start at 0.
     */
    suspend fun browse(categoryId: String, page: Int = 0): Page<Item>

    suspend fun detail(itemId: String): Item

    /** Where to stream [itemId] from. Only video like kinds can be played. */
    suspend fun playback(itemId: String): PlaybackInfo

    companion object {
        const val ROOT = ""
    }
}
