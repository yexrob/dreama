package com.limelight.local.stream

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
 * Never includes exception class names, numeric error codes, or English technical jargon.
 */
data class UserFacingError(
    val title: String,
    val detail: String,
    val retryable: Boolean = true,
)

object StreamErrorMapper {
    fun forPairState(state: PairState, computerRunningGame: Boolean): UserFacingError = when (state) {
        PairState.PIN_WRONG -> UserFacingError(
            title = "配对码不对",
            detail = "电脑上输入的数字和头显不一致，点重试重新生成配对码。",
        )
        PairState.ALREADY_IN_PROGRESS -> UserFacingError(
            title = "电脑正在处理其他配对",
            detail = "请先在电脑上完成或取消当前的配对弹窗，再试一次。",
        )
        PairState.FAILED -> if (computerRunningGame) {
            UserFacingError(
                title = "电脑正在运行串流会话",
                detail = "请先在电脑上结束正在运行的应用，再重新配对。",
            )
        } else {
            UserFacingError(
                title = "配对没有完成",
                detail = "请确认电脑上的串流服务已经打开，再点重试。",
            )
        }
        else -> UserFacingError(
            title = "配对没有完成",
            detail = "请检查电脑和头显是否连在同一个 Wi-Fi，再试一次。",
        )
    }

    fun forAutoPair(result: AutoPairResult): UserFacingError = when (result) {
        AutoPairResult.Rejected -> UserFacingError(
            title = "电脑拒绝了这次配对",
            detail = "如果想连接这台电脑，请在电脑弹出提示时点「允许」。",
        )
        AutoPairResult.TimedOut -> UserFacingError(
            title = "等待电脑确认超时",
            detail = "请留意电脑屏幕上的连接提示，点重试再发一次请求。",
        )
        AutoPairResult.UpgradeRequired -> UserFacingError(
            title = "请更新电脑上的 depth3d Host",
            detail = "电脑端版本和当前头显不兼容，更新电脑端后再试。",
            retryable = false,
        )
        is AutoPairResult.CertificateChanged -> UserFacingError(
            title = "电脑的安全证书变了",
            detail = "如果电脑刚重装过系统或服务，点「信任并继续」；否则请取消。",
        )
        is AutoPairResult.Failed -> UserFacingError(
            title = "没有连上电脑的快速配对服务",
            detail = "可以点重试，或者改用 4 位配对码手动配对。",
        )
        AutoPairResult.Approved -> UserFacingError(
            title = "配对没有完成",
            detail = "请点重试再试一次。",
        )
    }

    fun forHostNotReady(reason: String?): UserFacingError = UserFacingError(
        title = "电脑还没准备好串流",
        detail = reason?.takeIf { it.isNotBlank() } ?: "请先在电脑上完成串流权限设置，再点重试。",
    )

    fun forHostUpgradeRequired(): UserFacingError = UserFacingError(
        title = "请更新电脑上的 depth3d Host",
        detail = "电脑端版本和当前头显不兼容，更新电脑端后再连接。",
        retryable = false,
    )

    fun forStageFailed(stage: String?): UserFacingError {
        val normalized = stage.orEmpty().lowercase()
        return when {
            "video" in normalized || "decoder" in normalized -> UserFacingError(
                title = "画面解码没有启动",
                detail = "当前分辨率或帧率可能超出设备支持范围，请到设置里调低画质后再试。",
            )
            "audio" in normalized -> UserFacingError(
                title = "声音通道没有连上",
                detail = "请确认电脑的音频服务正常，然后点重试。",
            )
            "control" in normalized || "rtsp" in normalized || "handshake" in normalized -> UserFacingError(
                title = "连不上这台电脑",
                detail = "电脑可能休眠了，或者被防火墙拦住了，请检查后点重试。",
            )
            else -> UserFacingError(
                title = "没能开始串流",
                detail = "请确认电脑开着且和头显在同一个 Wi-Fi 里，然后点重试。",
            )
        }
    }

    fun forConnectionTerminated(errorCode: Int): UserFacingError = when (errorCode) {
        MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> UserFacingError(
            title = "没有收到电脑传来的画面",
            detail = "电脑的防火墙可能拦住了画面传输，请允许串流服务通过防火墙后重试。",
        )
        MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> UserFacingError(
            title = "当前网络太卡，画面中断了",
            detail = "请靠近路由器，或在设置里把码率调到「流畅」后再试。",
        )
        MoonBridge.ML_ERROR_PROTECTED_CONTENT,
        MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION -> UserFacingError(
            title = "电脑端提前结束了串流",
            detail = "请确认电脑上没有受版权保护的全屏播放窗口，再重新连接。",
        )
        MoonBridge.ML_ERROR_FRAME_CONVERSION -> UserFacingError(
            title = "电脑端画面编码出错",
            detail = "请在设置里换一个分辨率或编码格式，再重新连接。",
        )
        else -> UserFacingError(
            title = "和电脑的连接断开了",
            detail = "电脑可能休眠、关机了，或者网络波动了一下，点重试重新连接。",
        )
    }

    fun forVrUnavailable(): UserFacingError = UserFacingError(
        title = "无法进入沉浸式 3D 画面",
        detail = "头显的 3D 显示服务没有响应，请退出应用重新打开后再试。",
    )

    fun forThrowable(error: Throwable, contextAction: String = "连接"): UserFacingError = when (error) {
        is UnknownHostException -> UserFacingError(
            title = "找不到这台电脑",
            detail = "请检查输入的地址有没有填错，以及头显和电脑是否在同一个 Wi-Fi。",
        )
        is SocketTimeoutException -> UserFacingError(
            title = "${contextAction}超时了",
            detail = "电脑没有及时回应，请确认电脑醒着且串流服务正在运行。",
        )
        is ConnectException, is NoRouteToHostException -> UserFacingError(
            title = "连不上这台电脑",
            detail = "请确认电脑上的串流服务已经启动，并且和头显在同一个 Wi-Fi。",
        )
        is FileNotFoundException -> UserFacingError(
            title = "电脑上的串流服务没有就绪",
            detail = "请在电脑上重新打开串流服务后再试。",
        )
        is HostHttpResponseException -> when (error.errorCode) {
            599 -> UserFacingError(
                title = "无法从这台头显结束应用",
                detail = "这个应用不是由当前头显启动的，请直接在电脑上关闭它。",
                retryable = false,
            )
            else -> UserFacingError(
                title = "电脑拒绝了这次操作",
                detail = "请确认头显已经和电脑配对，再点重试。",
            )
        }
        else -> UserFacingError(
            title = "${contextAction}没有成功",
            detail = "请检查电脑和头显是否连在同一个 Wi-Fi，稍后再试。",
        )
    }

    fun forAddComputerFailed(address: String): UserFacingError = UserFacingError(
        title = "连不上 $address",
        detail = "请确认地址无误、电脑已经开机并且启动了串流服务，再试一次。",
    )

    fun forInvalidAddress(): UserFacingError = UserFacingError(
        title = "请输入正确的电脑地址",
        detail = "例如局域网地址 192.168.1.20，不能留空。",
    )

    fun forWakeNoMac(): UserFacingError = UserFacingError(
        title = "无法远程唤醒这台电脑",
        detail = "还没有记录到这台电脑的网卡信息，先手动开机连接一次就可以支持唤醒。",
        retryable = false,
    )

    fun forWakeFailed(): UserFacingError = UserFacingError(
        title = "唤醒请求没发出去",
        detail = "请检查头显的 Wi-Fi 连接是否正常，再试一次。",
    )

    fun forStageLabel(stage: String?): String {
        val normalized = stage.orEmpty().lowercase()
        return when {
            "app" in normalized || "launch" in normalized -> "正在打开电脑桌面…"
            "rtsp" in normalized || "handshake" in normalized -> "正在和电脑握手…"
            "control" in normalized -> "正在建立控制通道…"
            "video" in normalized -> "正在建立画面传输…"
            "audio" in normalized -> "正在建立音频传输…"
            "input" in normalized -> "正在准备手柄和指针…"
            else -> "正在连接电脑…"
        }
    }
}
