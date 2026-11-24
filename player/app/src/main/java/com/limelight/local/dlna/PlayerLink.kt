package com.limelight.local.dlna

import android.os.Handler
import android.os.Looper

/**
 * The in-process channel between the cast receiver and the headset player
 * (LocalPlayerXR). The player attaches with the cast it shows and reports
 * what it does; the receiver's commands reach it on the main thread, and
 * only while it still shows the cast they were meant for.
 */
object PlayerLink {
    /** What the player does for the phone. Called on the main thread. */
    interface CastPlayer {
        fun castPlay()
        fun castPause()
        fun castSeek(ms: Long)
        /** The phone stopped casting: leave the player. */
        fun castClose()
    }

    fun interface Listener {
        fun onReport(castId: Long, state: PlayerState, posMs: Long, durMs: Long)
    }

    private val main = Handler(Looper.getMainLooper())

    // Main thread only
    private var player: CastPlayer? = null
    private var playerCast = 0L
    private var closePending = 0L

    @Volatile
    @JvmStatic
    var listener: Listener? = null

    /**
     * The player is up showing [castId] (0 for a film of its own). A newer
     * player instance takes over from an older one still on its way out.
     */
    @JvmStatic
    fun attach(p: CastPlayer, castId: Long) {
        player = p
        playerCast = castId
        if (castId != 0L && castId == closePending) {
            closePending = 0
            main.post { if (player === p && playerCast == castId) p.castClose() }
        }
    }

    /**
     * The player [p] is going. [closed] reports the cast as ended to the
     * phone; a player relaunching itself for the same cast passes false.
     */
    @JvmStatic
    fun detach(p: CastPlayer, castId: Long, posMs: Long, durMs: Long, closed: Boolean) {
        if (player !== p) {
            return
        }
        player = null
        playerCast = 0
        if (closed && castId != 0L) {
            listener?.onReport(castId, PlayerState.CLOSED, posMs, durMs)
        }
    }

    @JvmStatic
    fun report(castId: Long, state: PlayerState, posMs: Long, durMs: Long) {
        if (castId != 0L) {
            listener?.onReport(castId, state, posMs, durMs)
        }
    }

    /** Whether a player is up at all, for or not for a cast. */
    @JvmStatic
    fun playerUp(): Boolean = player != null

    fun play(castId: Long) = on(castId) { it.castPlay() }
    fun pause(castId: Long) = on(castId) { it.castPause() }
    fun seek(castId: Long, ms: Long) = on(castId) { it.castSeek(ms) }

    fun close(castId: Long) {
        main.post {
            val p = player
            if (p != null && playerCast == castId) {
                p.castClose()
            } else {
                // Its player is still starting: it closes as soon as it attaches
                closePending = castId
            }
        }
    }

    private inline fun on(castId: Long, crossinline action: (CastPlayer) -> Unit) {
        main.post {
            val p = player
            if (p != null && playerCast == castId) {
                action(p)
            }
        }
    }
}
