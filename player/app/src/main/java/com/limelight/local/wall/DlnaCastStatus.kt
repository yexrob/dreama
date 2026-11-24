package com.limelight.local.wall

import com.limelight.local.dlna.ReceiverStatus

/** What the 投屏 page says about the DLNA receiver (phone video apps), from its status. */
internal data class DlnaCastCopy(
    val notice: String,
    val danger: Boolean,
    val body: String,
    val phoneHowTo: String,
)

internal fun dlnaCastCopy(s: ReceiverStatus): DlnaCastCopy {
    val pick = "播放时点「投屏」› 选「${s.name}」"
    val body = "在同一个 Wi-Fi 里，从手机上的视频 App 选择这个名字。"
    return when {
        s.casting != null -> DlnaCastCopy("正在播放手机投屏 · ${s.casting}", false, body, pick)
        s.phase == ReceiverStatus.Phase.WAITING -> DlnaCastCopy("正在等待投屏", false, body, pick)
        s.phase == ReceiverStatus.Phase.NO_NETWORK -> DlnaCastCopy("还没连上 Wi-Fi", true,
            "投屏需要头显和手机在同一个 Wi-Fi 里。", pick)
        s.phase == ReceiverStatus.Phase.FAILED -> DlnaCastCopy("投屏接收没能启动", true,
            "关掉 depth3d 再打开试试；还不行就重启头显的 Wi-Fi。", pick)
        else -> DlnaCastCopy("正在打开投屏接收…", false, body, pick)
    }
}
