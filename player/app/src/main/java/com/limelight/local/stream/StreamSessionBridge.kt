package com.limelight.local.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates streaming session handoff and error reporting between [com.limelight.GameXR]
 * and the 2D Compose UI ([com.limelight.local.wall.WallActivity]), replacing upstream
 * Dialog/Toast/SpinnerDialog popups.
 */
object StreamSessionBridge {
    const val EXTRA_INITIAL_TAB = "depth3d_initial_tab"
    const val TAB_CAST = "cast"

    data class PendingSessionError(
        val computerUuid: String?,
        val computerName: String,
        val appId: Int,
        val appName: String?,
        val error: UserFacingError,
    )

    data class LiveTelemetry(
        val computerName: String = "",
        val resolutionLabel: String = "",
        val fps: Int = 0,
        val bitrateMbps: Int = 0,
        val codecLabel: String = "",
        val rttMs: Int = 0,
        val packetLossPercent: Float = 0f,
        val poorConnection: Boolean = false,
        val gamepadMode: Boolean = false,
    ) {
        val showNetworkWarning: Boolean
            get() = poorConnection || rttMs > 60 || packetLossPercent > 0.5f
    }

    private val _pendingError = MutableStateFlow<PendingSessionError?>(null)
    val pendingError: StateFlow<PendingSessionError?> get() = _pendingError

    private val _liveTelemetry = MutableStateFlow(LiveTelemetry())
    val liveTelemetry: StateFlow<LiveTelemetry> get() = _liveTelemetry

    @JvmStatic
    fun recordError(
        computerUuid: String?,
        computerName: String,
        appId: Int,
        appName: String?,
        error: UserFacingError,
    ) {
        _pendingError.value = PendingSessionError(
            computerUuid = computerUuid,
            computerName = computerName,
            appId = appId,
            appName = appName,
            error = error,
        )
    }

    @JvmStatic
    fun clearError() {
        _pendingError.value = null
    }

    @JvmStatic
    fun updateTelemetry(
        computerName: String,
        resolutionLabel: String,
        fps: Int,
        bitrateMbps: Int,
        codecLabel: String,
        rttMs: Int,
        packetLossPercent: Float,
        poorConnection: Boolean,
        gamepadMode: Boolean,
    ) {
        _liveTelemetry.value = LiveTelemetry(
            computerName = computerName,
            resolutionLabel = resolutionLabel,
            fps = fps,
            bitrateMbps = bitrateMbps,
            codecLabel = codecLabel,
            rttMs = rttMs,
            packetLossPercent = packetLossPercent,
            poorConnection = poorConnection,
            gamepadMode = gamepadMode,
        )
    }

    @JvmStatic
    fun resetTelemetry() {
        _liveTelemetry.value = LiveTelemetry()
    }
}
