package com.limelight.local.stream

import android.content.Context
import com.limelight.R
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.jni.MoonBridge
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Human-readable Chinese error copy ("what happened + what to do next").
 * All strings are loaded from Android XML resources (res/values/strings_depth3d.xml)
 * and never expose exception class names, numeric error codes, or English technical jargon.
 */
data class UserFacingError(
    val title: String,
    val detail: String,
    val retryable: Boolean = true,
)

/** Abstraction over Android Resources.getString so StreamErrorMapper is 100% JVM unit-testable. */
interface StringLookup {
    fun get(resId: Int): String
    fun get(resId: Int, vararg args: Any): String
}

class AndroidStringLookup(context: Context) : StringLookup {
    private val res = context.applicationContext.resources
    override fun get(resId: Int): String = res.getString(resId)
    override fun get(resId: Int, vararg args: Any): String = res.getString(resId, *args)
}

object StreamErrorMapper {
    fun lookup(context: Context): StringLookup = AndroidStringLookup(context)

    @JvmStatic
    fun forStageFailed(context: Context, stage: String?): UserFacingError =
        forStageFailed(lookup(context), stage)

    @JvmStatic
    fun forConnectionTerminated(context: Context, errorCode: Int): UserFacingError =
        forConnectionTerminated(lookup(context), errorCode)

    @JvmStatic
    fun forVrUnavailable(context: Context): UserFacingError =
        forVrUnavailable(lookup(context))

    @JvmStatic
    fun forStageLabel(context: Context, stage: String?): String =
        forStageLabel(lookup(context), stage)

    fun forServiceStarting(s: StringLookup): UserFacingError = UserFacingError(
        title = s.get(R.string.err_service_starting_title),
        detail = s.get(R.string.err_service_starting_detail),
    )

    fun forComputerOffline(s: StringLookup, name: String): UserFacingError = UserFacingError(
        title = s.get(R.string.err_pc_offline_title, name),
        detail = s.get(R.string.err_pc_offline_detail),
    )

    fun forComputerUnreachable(s: StringLookup, name: String): UserFacingError = UserFacingError(
        title = s.get(R.string.err_pc_unreachable_named_title, name),
        detail = s.get(R.string.err_pc_unreachable_named_detail),
    )

    fun forPairState(s: StringLookup, state: PairState, computerRunningGame: Boolean): UserFacingError = when (state) {
        PairState.PIN_WRONG -> UserFacingError(
            title = s.get(R.string.err_pair_pin_wrong_title),
            detail = s.get(R.string.err_pair_pin_wrong_detail),
        )
        PairState.ALREADY_IN_PROGRESS -> UserFacingError(
            title = s.get(R.string.err_pair_in_progress_title),
            detail = s.get(R.string.err_pair_in_progress_detail),
        )
        PairState.FAILED -> if (computerRunningGame) {
            UserFacingError(
                title = s.get(R.string.err_pair_running_game_title),
                detail = s.get(R.string.err_pair_running_game_detail),
            )
        } else {
            UserFacingError(
                title = s.get(R.string.err_pair_failed_title),
                detail = s.get(R.string.err_pair_failed_detail),
            )
        }
        else -> UserFacingError(
            title = s.get(R.string.err_pair_failed_title),
            detail = s.get(R.string.err_pair_generic_detail),
        )
    }

    fun forAutoPair(s: StringLookup, result: AutoPairResult, streamReason: String? = null): UserFacingError = when (result) {
        AutoPairResult.Rejected -> UserFacingError(
            title = s.get(R.string.err_autopair_rejected_title),
            detail = s.get(R.string.err_autopair_rejected_detail),
        )
        AutoPairResult.TimedOut -> UserFacingError(
            title = s.get(R.string.err_autopair_timeout_title),
            detail = s.get(R.string.err_autopair_timeout_detail),
        )
        AutoPairResult.Busy -> UserFacingError(
            title = s.get(R.string.err_autopair_busy_title),
            detail = s.get(R.string.err_autopair_busy_detail),
        )
        is AutoPairResult.Cooldown -> UserFacingError(
            title = s.get(R.string.err_autopair_cooldown_title),
            detail = if (result.retryAfterSec != null && result.retryAfterSec > 0) {
                s.get(R.string.err_autopair_cooldown_detail_sec, result.retryAfterSec)
            } else {
                s.get(R.string.err_autopair_cooldown_detail)
            },
        )
        AutoPairResult.UpgradeRequired -> UserFacingError(
            title = s.get(R.string.err_host_upgrade_title),
            detail = s.get(R.string.err_host_upgrade_detail),
            retryable = false,
        )
        is AutoPairResult.StreamServiceUnavailable -> UserFacingError(
            title = s.get(R.string.err_host_service_down_title),
            detail = result.reason?.takeIf { it.isNotBlank() }
                ?: streamReason?.takeIf { it.isNotBlank() }
                ?: s.get(R.string.err_host_service_down_detail),
            retryable = true,
        )
        is AutoPairResult.CertificateChanged -> UserFacingError(
            title = s.get(R.string.err_cert_changed_title),
            detail = s.get(R.string.err_cert_changed_detail),
        )
        is AutoPairResult.Failed -> UserFacingError(
            title = s.get(R.string.err_autopair_failed_title),
            detail = s.get(R.string.err_autopair_failed_detail),
        )
        is AutoPairResult.Approved, AutoPairResult.Cancelled -> UserFacingError(
            title = s.get(R.string.err_pair_failed_title),
            detail = s.get(R.string.err_pair_generic_detail),
        )
    }

    fun forPairComplete(s: StringLookup, result: PairCompleteResult): UserFacingError = when (result) {
        PairCompleteResult.NotPaired -> UserFacingError(
            title = s.get(R.string.err_pair_complete_not_paired_title),
            detail = s.get(R.string.err_pair_complete_not_paired_detail),
        )
        PairCompleteResult.TicketExpired -> UserFacingError(
            title = s.get(R.string.err_pair_complete_expired_title),
            detail = s.get(R.string.err_pair_complete_expired_detail),
        )
        PairCompleteResult.Unauthorized -> UserFacingError(
            title = s.get(R.string.err_pair_complete_unauth_title),
            detail = s.get(R.string.err_pair_complete_unauth_detail),
        )
        PairCompleteResult.Forbidden -> UserFacingError(
            title = s.get(R.string.err_pair_complete_forbidden_title),
            detail = s.get(R.string.err_pair_complete_forbidden_detail),
        )
        PairCompleteResult.UpgradeRequired -> UserFacingError(
            title = s.get(R.string.err_host_upgrade_title),
            detail = s.get(R.string.err_host_upgrade_detail),
            retryable = false,
        )
        is PairCompleteResult.CertificateChanged -> UserFacingError(
            title = s.get(R.string.err_cert_changed_title),
            detail = s.get(R.string.err_cert_changed_detail),
        )
        is PairCompleteResult.Failed -> UserFacingError(
            title = s.get(R.string.err_pair_complete_failed_title),
            detail = s.get(R.string.err_pair_complete_failed_detail),
        )
        is PairCompleteResult.Completed, PairCompleteResult.Cancelled -> UserFacingError(
            title = s.get(R.string.err_pair_failed_title),
            detail = s.get(R.string.err_pair_generic_detail),
        )
    }

    fun forHostIdentityMismatch(s: StringLookup): UserFacingError = UserFacingError(
        title = s.get(R.string.err_host_id_mismatch_title),
        detail = s.get(R.string.err_host_id_mismatch_detail),
    )

    fun forHostNotReady(s: StringLookup, reason: String?): UserFacingError = UserFacingError(
        title = s.get(R.string.err_host_not_ready_title),
        detail = reason?.takeIf { it.isNotBlank() } ?: s.get(R.string.err_host_not_ready_detail),
    )

    fun forHostUpgradeRequired(s: StringLookup): UserFacingError = UserFacingError(
        title = s.get(R.string.err_host_upgrade_title),
        detail = s.get(R.string.err_host_upgrade_detail),
        retryable = false,
    )

    fun forStageFailed(s: StringLookup, stage: String?): UserFacingError {
        val normalized = stage.orEmpty().lowercase()
        return when {
            "video" in normalized || "decoder" in normalized -> UserFacingError(
                title = s.get(R.string.err_stage_video_title),
                detail = s.get(R.string.err_stage_video_detail),
            )
            "audio" in normalized -> UserFacingError(
                title = s.get(R.string.err_stage_audio_title),
                detail = s.get(R.string.err_stage_audio_detail),
            )
            "control" in normalized || "rtsp" in normalized || "handshake" in normalized -> UserFacingError(
                title = s.get(R.string.err_stage_control_title),
                detail = s.get(R.string.err_stage_control_detail),
            )
            else -> UserFacingError(
                title = s.get(R.string.err_stage_generic_title),
                detail = s.get(R.string.err_stage_generic_detail),
            )
        }
    }

    fun forConnectionTerminated(s: StringLookup, errorCode: Int): UserFacingError = when (errorCode) {
        MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> UserFacingError(
            title = s.get(R.string.err_term_no_video_title),
            detail = s.get(R.string.err_term_no_video_detail),
        )
        MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> UserFacingError(
            title = s.get(R.string.err_term_no_frame_title),
            detail = s.get(R.string.err_term_no_frame_detail),
        )
        MoonBridge.ML_ERROR_PROTECTED_CONTENT,
        MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION -> UserFacingError(
            title = s.get(R.string.err_term_early_title),
            detail = s.get(R.string.err_term_early_detail),
        )
        MoonBridge.ML_ERROR_FRAME_CONVERSION -> UserFacingError(
            title = s.get(R.string.err_term_encode_title),
            detail = s.get(R.string.err_term_encode_detail),
        )
        else -> UserFacingError(
            title = s.get(R.string.err_term_disconnected_title),
            detail = s.get(R.string.err_term_disconnected_detail),
        )
    }

    fun forVrUnavailable(s: StringLookup): UserFacingError = UserFacingError(
        title = s.get(R.string.err_vr_unavailable_title),
        detail = s.get(R.string.err_vr_unavailable_detail),
    )

    fun forThrowable(s: StringLookup, error: Throwable, contextAction: String): UserFacingError = when (error) {
        is UnknownHostException -> UserFacingError(
            title = s.get(R.string.err_net_unknown_host_title),
            detail = s.get(R.string.err_net_unknown_host_detail),
        )
        is SocketTimeoutException -> UserFacingError(
            title = s.get(R.string.err_net_timeout_title, contextAction),
            detail = s.get(R.string.err_net_timeout_detail),
        )
        is ConnectException, is NoRouteToHostException -> UserFacingError(
            title = s.get(R.string.err_net_connect_title),
            detail = s.get(R.string.err_net_connect_detail),
        )
        is FileNotFoundException -> UserFacingError(
            title = s.get(R.string.err_net_not_ready_title),
            detail = s.get(R.string.err_net_not_ready_detail),
        )
        is HostHttpResponseException -> when (error.errorCode) {
            599 -> UserFacingError(
                title = s.get(R.string.err_host_quit_other_title),
                detail = s.get(R.string.err_host_quit_other_detail),
                retryable = false,
            )
            else -> UserFacingError(
                title = s.get(R.string.err_host_rejected_title),
                detail = s.get(R.string.err_host_rejected_detail),
            )
        }
        else -> UserFacingError(
            title = s.get(R.string.err_action_failed_title, contextAction),
            detail = s.get(R.string.err_action_failed_detail),
        )
    }

    fun forAddComputerFailed(s: StringLookup, address: String): UserFacingError = UserFacingError(
        title = s.get(R.string.err_add_pc_failed_title, address),
        detail = s.get(R.string.err_add_pc_failed_detail),
    )

    fun forInvalidAddress(s: StringLookup): UserFacingError = UserFacingError(
        title = s.get(R.string.err_invalid_addr_title),
        detail = s.get(R.string.err_invalid_addr_detail),
    )

    fun forWakeNoMac(s: StringLookup): UserFacingError = UserFacingError(
        title = s.get(R.string.err_wake_no_mac_title),
        detail = s.get(R.string.err_wake_no_mac_detail),
        retryable = false,
    )

    fun forWakeFailed(s: StringLookup): UserFacingError = UserFacingError(
        title = s.get(R.string.err_wake_failed_title),
        detail = s.get(R.string.err_wake_failed_detail),
    )

    fun forStageLabel(s: StringLookup, stage: String?): String {
        val normalized = stage.orEmpty().lowercase()
        return when {
            "app" in normalized || "launch" in normalized -> s.get(R.string.stream_stage_open_desktop)
            "rtsp" in normalized || "handshake" in normalized -> s.get(R.string.stream_stage_handshake)
            "control" in normalized -> s.get(R.string.stream_stage_control)
            "video" in normalized -> s.get(R.string.stream_stage_video)
            "audio" in normalized -> s.get(R.string.stream_stage_audio)
            "input" in normalized -> s.get(R.string.stream_stage_input)
            else -> s.get(R.string.stream_stage_default)
        }
    }
}
