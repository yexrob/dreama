package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.QrCodeMatrix
import com.limelight.local.stream.UserFacingError
import kotlinx.coroutines.launch

/**
 * Modal sheet that lets the user enter a computer IP address or rename the headset from their
 * phone via QR code + short LAN URL + 4-digit code, with an explicit opt-in fallback to the
 * headset keyboard (ux-spec §4.6, 00-quality-bar.md #4).
 */
@Composable
fun PhoneInputSheet(
    mode: PhoneInputServer.Mode,
    lanIp: String?,
    initialValue: String = "",
    onSubmit: suspend (String) -> UserFacingError?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var useHeadsetKeyboard by remember { mutableStateOf(lanIp == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UserFacingError?>(null) }
    val textFieldState = rememberTextFieldState(initialValue)

    var serverInfo by remember { mutableStateOf<Triple<String, String, QrCodeMatrix>?>(null) }

    fun triggerSubmit(raw: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val err = onSubmit(raw)
            busy = false
            if (err == null) {
                onDismiss()
            } else {
                error = err
            }
        }
    }

    DisposableEffect(mode, lanIp, useHeadsetKeyboard) {
        if (useHeadsetKeyboard || lanIp.isNullOrBlank()) {
            serverInfo = null
            onDispose { }
        } else {
            val server = try {
                PhoneInputServer(
                    mode = mode,
                    displayHost = lanIp,
                    onSubmitted = { submitted ->
                        scope.launch { triggerSubmit(submitted) }
                    },
                ).start()
            } catch (e: Exception) {
                LimeLog.warning("depth3d: phone input server failed to start: ${e.javaClass.simpleName}")
                null
            }
            if (server != null) {
                serverInfo = Triple(server.shortUrl, server.verificationCode, QrCodeMatrix.encode(server.qrUrl))
            } else {
                serverInfo = null
            }
            onDispose {
                server?.close()
            }
        }
    }

    val title = stringResource(
        if (mode == PhoneInputServer.Mode.ADD_COMPUTER) R.string.phone_input_add_pc_title
        else R.string.phone_input_device_name_title,
    )
    val sub = stringResource(
        if (mode == PhoneInputServer.Mode.ADD_COMPUTER) R.string.phone_input_add_pc_sub
        else R.string.phone_input_device_name_sub,
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Wall.Scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                if (!busy) onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(840.dp)
                .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 48.dp, vertical = 40.dp),
        ) {
            Text(title, style = WallType.Head)
            Text(sub, style = WallType.Caption, color = Wall.Text2, modifier = Modifier.padding(top = 6.dp))

            if (!useHeadsetKeyboard) {
                val info = serverInfo
                if (info != null) {
                    val (url, code, qr) = info
                    Row(
                        Modifier.padding(top = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QrCodeView(qr, size = 228.dp)
                        Column(Modifier.weight(1f)) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(Wall.Radius))
                                    .background(Wall.Surface)
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                            ) {
                                Text(url, style = WallType.Body.copy(fontFeatureSettings = "tnum"))
                            }
                            Row(
                                Modifier.padding(top = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(stringResource(R.string.phone_input_code_label), style = WallType.Caption, color = Wall.Text2)
                                Text(
                                    code,
                                    style = WallType.Title.copy(
                                        fontSize = 36.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 6.sp,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = Wall.Accent,
                                )
                            }
                            if (busy) {
                                Row(
                                    Modifier.padding(top = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    WallSpinner(size = 24.dp)
                                    Text(stringResource(R.string.phone_input_connecting), style = WallType.Caption, color = Wall.Text2)
                                }
                            }
                        }
                    }
                } else {
                    Box(Modifier.padding(top = 24.dp)) {
                        Notice(stringResource(R.string.phone_input_no_wifi), WallIcons.Wifi, danger = false)
                    }
                }
            } else {
                val fieldLabel = stringResource(
                    if (mode == PhoneInputServer.Mode.ADD_COMPUTER) R.string.phone_input_addr_label
                    else R.string.phone_input_name_label,
                )
                val placeholder = stringResource(
                    if (mode == PhoneInputServer.Mode.ADD_COMPUTER) R.string.phone_input_addr_placeholder
                    else R.string.phone_input_name_placeholder,
                )
                Column(Modifier.padding(top = 28.dp)) {
                    Text(fieldLabel, style = WallType.Caption, color = Wall.Text2)
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                        textStyle = WallType.Body,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (mode == PhoneInputServer.Mode.ADD_COMPUTER) KeyboardType.Uri else KeyboardType.Text,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        cursorBrush = SolidColor(Wall.Accent),
                        decorator = TextFieldDecorator { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .clip(RoundedCornerShape(Wall.Radius))
                                    .background(Wall.Surface)
                                    .padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (textFieldState.text.isEmpty()) {
                                    Text(placeholder, style = WallType.Body, color = Wall.Text3)
                                }
                                inner()
                            }
                        },
                    )
                }
            }

            error?.let { err ->
                Column(Modifier.padding(top = 20.dp)) {
                    Notice(err.title, WallIcons.Alert, danger = true)
                    Text(err.detail, style = WallType.Caption, color = Wall.Text2, modifier = Modifier.padding(start = 38.dp, top = 4.dp))
                }
            }

            Row(
                Modifier.padding(top = 32.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (useHeadsetKeyboard) {
                    WallButton(
                        text = if (busy) stringResource(R.string.phone_input_connecting)
                        else stringResource(if (mode == PhoneInputServer.Mode.ADD_COMPUTER) R.string.action_add else R.string.action_save),
                        onClick = { triggerSubmit(textFieldState.text.toString()) },
                        kind = ButtonKind.Primary,
                    )
                    if (!lanIp.isNullOrBlank()) {
                        WallButton(
                            text = stringResource(R.string.phone_input_back_to_qr),
                            onClick = { if (!busy) { error = null; useHeadsetKeyboard = false } },
                            kind = ButtonKind.Plain,
                        )
                    }
                } else {
                    WallButton(
                        text = stringResource(R.string.phone_input_use_headset_kb),
                        onClick = { if (!busy) { error = null; useHeadsetKeyboard = true } },
                        icon = WallIcons.Keyboard,
                        kind = ButtonKind.Plain,
                    )
                }
                Spacer(Modifier.weight(1f))
                WallButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { if (!busy) onDismiss() },
                    kind = ButtonKind.Ghost,
                )
            }
        }
    }
}
