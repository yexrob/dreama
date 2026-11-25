package com.limelight.local.wall

import android.content.Context
import com.limelight.R
import com.limelight.local.dlna.ReceiverStatus
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.StringLookup

/** What the Cast page says about the DLNA receiver (phone video apps), from its status. */
internal data class DlnaCastCopy(
    val notice: String,
    val danger: Boolean,
    val body: String,
    val phoneHowTo: String,
)

internal fun dlnaCastCopy(context: Context, s: ReceiverStatus): DlnaCastCopy =
    dlnaCastCopy(AndroidStringLookup(context), s)

internal fun dlnaCastCopy(strings: StringLookup, s: ReceiverStatus): DlnaCastCopy {
    val pick = strings.get(R.string.cast_howto_phone_body, s.name)
    val body = strings.get(R.string.dlna_body)
    return when {
        s.casting != null -> DlnaCastCopy(strings.get(R.string.dlna_casting_title, s.casting), false, body, pick)
        s.phase == ReceiverStatus.Phase.WAITING -> DlnaCastCopy(strings.get(R.string.cast_waiting), false, body, pick)
        s.phase == ReceiverStatus.Phase.NO_NETWORK -> DlnaCastCopy(
            strings.get(R.string.cast_no_wifi_title),
            true,
            strings.get(R.string.dlna_no_network_body),
            pick,
        )
        s.phase == ReceiverStatus.Phase.FAILED -> DlnaCastCopy(
            strings.get(R.string.dlna_failed_title),
            true,
            strings.get(R.string.dlna_failed_body),
            pick,
        )
        else -> DlnaCastCopy(strings.get(R.string.dlna_starting), false, body, pick)
    }
}
