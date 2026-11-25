package com.limelight.local.dlna

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** What the cast page shows about the receiver. */
data class ReceiverStatus(
    val name: String,
    val phase: Phase = Phase.OFF,
    /** The headset's address on the Wi-Fi while it is waiting. */
    val address: String? = null,
    /** Title of what a phone is casting now. */
    val casting: String? = null,
) {
    enum class Phase { OFF, STARTING, WAITING, NO_NETWORK, FAILED }
}

/**
 * Keeps the cast receiver running while any part of the app is in front:
 * the wall holds it from onStart to onStop, the player for its lifetime.
 * When the last one lets go the receiver stops shortly after, so it never
 * stays behind once the app is closed.
 */
object CastReceiver {
    private const val TAG = "depth3d-dlna"
    private const val LINGER_MS = 5000L
    private const val PREFS = "dlna"
    private const val KEY_NAME = "name"
    private const val KEY_UDN = "udn"
    const val DEFAULT_NAME = "Dreama"

    private val main = Handler(Looper.getMainLooper())
    private var holds = 0
    private var app: Context? = null
    private val stopper = Runnable {
        app?.let { it.stopService(Intent(it, DlnaService::class.java)) }
    }

    private val state = MutableStateFlow(ReceiverStatus(DEFAULT_NAME))
    val status: StateFlow<ReceiverStatus> get() = state

    internal fun publish(s: ReceiverStatus) {
        state.value = s
    }

    internal fun update(change: (ReceiverStatus) -> ReceiverStatus) {
        synchronized(state) { state.value = change(state.value) }
    }

    /** Main thread. */
    @JvmStatic
    fun hold(context: Context) {
        val c = context.applicationContext
        app = c
        holds++
        main.removeCallbacks(stopper)
        if (holds == 1) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, DlnaService::class.java))
            } catch (e: Exception) {
                // A start refused from the background; the next hold in front tries again
                Log.w(TAG, "receiver did not start", e)
                update { it.copy(phase = ReceiverStatus.Phase.FAILED) }
            }
        }
    }

    /** Main thread. */
    @JvmStatic
    fun release(context: Context) {
        app = context.applicationContext
        if (holds > 0) {
            holds--
        }
        if (holds == 0) {
            main.removeCallbacks(stopper)
            main.postDelayed(stopper, LINGER_MS)
        }
    }

    /** The name phones list the headset under. */
    @JvmStatic
    fun name(context: Context): String =
        prefs(context).getString(KEY_NAME, null)?.trim()?.ifEmpty { null } ?: DEFAULT_NAME

    /** Updates the shared cast device name and restarts active receivers so it takes effect immediately. */
    @JvmStatic
    fun setName(context: Context, newName: String) {
        val clean = newName.trim().take(48).ifEmpty { DEFAULT_NAME }
        val c = context.applicationContext
        prefs(c).edit().putString(KEY_NAME, clean).apply()
        update { it.copy(name = clean) }
        if (holds > 0) {
            c.stopService(Intent(c, DlnaService::class.java))
            try {
                ContextCompat.startForegroundService(c, Intent(c, DlnaService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "receiver restart after rename", e)
            }
        }
        com.limelight.local.airplay.AirPlayService.reloadName(c)
    }

    /** The device's UPnP identity, made once and kept so phones remember the headset. */
    internal fun udn(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_UDN, null)?.let { return it }
        val udn = UUID.randomUUID().toString()
        p.edit().putString(KEY_UDN, udn).apply()
        return udn
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
