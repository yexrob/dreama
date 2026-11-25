package com.limelight.local.danmaku

/**
 * Narrow interface bridging [LocalDanmakuPlaybackBridge] to [DanmakuSessionManager] (in production)
 * or a deterministic test double / in-process [DanmakuEngine] (in JVM unit tests).
 */
interface DanmakuPlaybackSink {
    fun setFetchPaused(paused: Boolean)
    fun setPausedWithoutPosition()
    fun updatePlaybackPosition(mediaTimeMs: Long, playing: Boolean, playbackSpeed: Float)
    fun onSeek(targetMs: Long)
    fun setScrubbing(scrubbing: Boolean)
}

/**
 * Pure-Kotlin playback lifecycle bridge used directly by [com.limelight.local.LocalPlayerXR].
 *
 * Enforces all playback-sync invariants that caused review regressions when handled ad-hoc:
 * 1. While `!ready` or `refreshInFlight`, NEVER feeds transient ExoPlayer `position = 0` to the
 *    danmaku engine or fetcher; keeps both timeline & network fetching paused.
 * 2. On transitioning into `STATE_READY` (initial resume or post-refresh seek-back), explicitly
 *    invokes `onSeek(resumePosMs)` BEFORE resuming playback & unpausing fetches (zero backfill).
 * 3. During `STATE_BUFFERING` (`playbackState != STATE_READY`), pauses danmaku in place without
 *    clearing active items (ux-spec.md §6.5).
 * 4. During progress-bar drag (`onScrubStart`..`onScrubEnd`), hides danmaku and cleanly seeks on drop.
 */
class LocalDanmakuPlaybackBridge(
    private val sink: DanmakuPlaybackSink,
) {
    private var ready: Boolean = false
    private var refreshing: Boolean = false
    private var scrubbing: Boolean = false

    val isReady: Boolean get() = ready
    val isRefreshing: Boolean get() = refreshing
    val isScrubbing: Boolean get() = scrubbing

    fun onNewItemLoading() {
        ready = false
        refreshing = false
        scrubbing = false
        sink.setFetchPaused(true)
        sink.setPausedWithoutPosition()
    }

    fun onPluginRefreshStarted() {
        refreshing = true
        ready = false
        sink.setFetchPaused(true)
        sink.setPausedWithoutPosition()
    }

    /**
     * Called once when ExoPlayer enters `STATE_READY` for the current prepare cycle (initial load,
     * episode switch, or post-refresh re-prepare) with the exact resolved resume/start position.
     */
    fun onPlayerReady(resumePosMs: Long, actuallyPlaying: Boolean, playbackSpeed: Float) {
        val pos = resumePosMs.coerceAtLeast(0L)
        ready = true
        refreshing = false
        sink.onSeek(pos)
        sink.setFetchPaused(false)
        sink.updatePlaybackPosition(pos, actuallyPlaying && !scrubbing, playbackSpeed)
    }

    /**
     * Called on `tickBar()`, `onIsPlayingChanged()`, and `onPlaybackStateChanged()`.
     * Strictly requires `playbackState == PLAYBACK_STATE_READY` (matching ExoPlayer `Player.STATE_READY`)
     * so buffering pauses danmaku in place instead of letting `playWhenReady` keep them scrolling.
     */
    fun onPlaybackUpdate(
        positionMs: Long,
        playbackState: Int,
        playWhenReady: Boolean,
        errored: Boolean,
        playbackSpeed: Float,
    ) {
        if (!ready || refreshing) {
            sink.setPausedWithoutPosition()
            return
        }
        val isReadyState = playbackState == PLAYBACK_STATE_READY
        val actuallyPlaying = isReadyState && playWhenReady && !errored && !scrubbing
        sink.updatePlaybackPosition(positionMs.coerceAtLeast(0L), actuallyPlaying, playbackSpeed)
    }

    fun onUserSeek(targetMs: Long) {
        val pos = targetMs.coerceAtLeast(0L)
        if (!ready || refreshing) {
            sink.setPausedWithoutPosition()
            return
        }
        sink.onSeek(pos)
    }

    fun onScrubStart() {
        if (!scrubbing) {
            scrubbing = true
            sink.setScrubbing(true)
        }
    }

    fun onScrubEnd(commitTargetMs: Long?) {
        if (scrubbing) {
            scrubbing = false
            sink.setScrubbing(false)
        }
        if (commitTargetMs != null && ready && !refreshing) {
            sink.onSeek(commitTargetMs.coerceAtLeast(0L))
        }
    }

    companion object {
        // Matches androidx.media3.common.Player.STATE_READY (= 3) without coupling to Android/Media3
        const val PLAYBACK_STATE_IDLE = 1
        const val PLAYBACK_STATE_BUFFERING = 2
        const val PLAYBACK_STATE_READY = 3
        const val PLAYBACK_STATE_ENDED = 4
    }
}
