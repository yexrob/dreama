package com.limelight.local.airplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the AirPlay receiver is doing, for the cast page and the player.
 * Only AirPlayService publishes, and only on the main thread, so listeners
 * see the changes in order.
 */
object AirPlayState {
    enum class Phase {
        // The service is not running
        OFF,
        STARTING,
        // Announced on the Wi-Fi, nobody connected
        WAITING,
        // A device asked to mirror; no picture yet
        CONNECTING,
        MIRRORING,
        NO_WIFI,
        // Did not start: problem says why
        FAILED,
    }

    data class Status(
        val phase: Phase = Phase.OFF,
        // The name iPads list
        val name: String = AirPlayService.DEFAULT_NAME,
        // The connected device's own name, while connecting or mirroring
        val client: String? = null,
        // Counts mirror sessions; a player belongs to the one it started in
        val session: Int = 0,
        val problem: String? = null,
    )

    fun interface Listener {
        fun onStatus(status: Status)
    }

    private val state = MutableStateFlow(Status())
    private val listeners = CopyOnWriteArrayList<Listener>()

    val status: StateFlow<Status> = state.asStateFlow()

    @JvmStatic
    fun current(): Status = state.value

    @JvmStatic
    fun publish(status: Status) {
        if (state.value == status) {
            return
        }
        state.value = status
        for (l in listeners) {
            l.onStatus(status)
        }
    }

    @JvmStatic
    fun addListener(l: Listener) {
        listeners.add(l)
    }

    @JvmStatic
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * A device name set into a Chinese sentence, with a space where Latin
     * letters meet Chinese: "正在连接" + "小林的 iPad" + "" reads
     * 正在连接小林的 iPad, and "正在显示" + "iPad" + "的屏幕" reads
     * 正在显示 iPad 的屏幕. No name reads as "iPad".
     */
    @JvmStatic
    fun phrase(before: String, name: String?, after: String): String {
        val n = name?.trim().takeUnless { it.isNullOrEmpty() } ?: "iPad"
        val sb = StringBuilder(before)
        if (before.isNotEmpty() && latin(before.last()) != latin(n.first())) {
            sb.append(' ')
        }
        sb.append(n)
        if (after.isNotEmpty() && latin(n.last()) != latin(after.first())) {
            sb.append(' ')
        }
        return sb.append(after).toString()
    }

    private fun latin(c: Char) = c.code < 0x2E80 && c.isLetterOrDigit()
}
