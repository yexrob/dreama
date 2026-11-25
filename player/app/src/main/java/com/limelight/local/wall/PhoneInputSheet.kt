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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.PhoneInputCopy
import com.limelight.local.stream.PhoneInputErrorHelper
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.PhoneSubmitResult
import com.limelight.local.stream.QrCodeMatrix
import com.limelight.local.stream.UserFacingError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Modal sheet that lets the user enter a computer IP address or rename the headset from their
 * phone via QR code (carrying a 128-bit random token) + short LAN URL + 4-digit fallback code,
 * with an explicit opt-in fallback to the headset keyboard (ux-spec §4.6, 00-quality-bar.md #4).
 */
@Composable
fun PhoneInputSheet(
    mode: PhoneInputServer.Mode,
    lanIp: String?,
    initialValue: String = "",
    onSubmit: suspend (String) -> UserFacingError?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var useHeadsetKeyboard by remember { mutableStateOf(lanIp == null) }
    var busy by remember { mutableStateOf(false) }
    var lockedOut by remember { mutableStateOf(false) }
    var serverGeneration by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<UserFacingError?>(null) }
    val textFieldState = rememberTextFieldState(initialValue)

    var serverInfo by remember { mutableStateOf<Triple<String, String, QrCodeMatrix>?>(null) }

    fun triggerSubmitFromKeyboard(raw: String) {
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

    DisposableEffect(mode, lanIp, useHeadsetKeyboard, serverGeneration) {
        if (useHeadsetKeyboard || lanIp.isNullOrBlank()) {
            serverInfo = null
            onDispose { }
        } else {
            val copy = PhoneInputCopy.from(context, mode)
            val strings = AndroidStringLookup(context)
            val server = try {
                PhoneInputServer(
                    copy = copy,
                    displayHost = lanIp,
                    onSubmitted = { submitted ->
                        // Invoked on PhoneInputServer's worker thread: wait for the headset to
                        // actually accept or reject the submitted value before replying to the phone.
                        try {
                            runBlocking {
                                val alreadyBusy = withContext(Dispatchers.Main.immediate) {
                                    if (busy) {
                                        true
                                    } else {
                                        busy = true
                                        error = null
                                        false
                                    }
                                }
                                if (alreadyBusy) {
                                    return@runBlocking PhoneSubmitResult.Rejected(
                                        context.getString(R.string.phone_input_connecting),
                                    )
                                }
                                val err: UserFacingError? = try {
                                    val outcome = withTimeoutOrNull(20_000L) {
                                        Pair(true, onSubmit(submitted))
                                    }
                                    if (outcome == null) {
                                        PhoneInputErrorHelper.timeoutError(strings, mode)
                                    } else {
                                        outcome.second
                                    }
                                } catch (_: Exception) {
                                    PhoneInputErrorHelper.failedError(strings, mode)
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    busy = false
                                    if (err == null) {
                                        onDismiss()
                                    } else {
                                        error = err
                                    }
                                }
                                if (err == null) {
                                    PhoneSubmitResult.Accepted
                                } else {
                                    PhoneSubmitResult.Rejected(
                                        PhoneInputErrorHelper.formatRejectedReason(strings, err),
                                    )
                                }
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            PhoneSubmitResult.Rejected(context.getString(R.string.action_cancel))
                        }
                    },
                    onLockedOut = {
                        scope.launch {
                            lockedOut = true
                            serverInfo = null
                        }
                    },
                ).start()
            } catch (e: Exception) {
                LimeLog.warning("depth3d: phone input server failed to start: ${e.javaClass.simpleName}")
                null
            }
            if (server != null) {
                lockedOut = false
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
        when (mode) {
            PhoneInputServer.Mode.ADD_COMPUTER -> R.string.phone_input_add_pc_title
            PhoneInputServer.Mode.DEVICE_NAME -> R.string.phone_input_device_name_title
            PhoneInputServer.Mode.PLUGIN_REPO -> R.string.phone_input_repo_title
            PhoneInputServer.Mode.PLUGIN_DEV_SERVER -> R.string.phone_input_dev_title
            PhoneInputServer.Mode.PLUGIN_DIRECT_URL -> R.string.phone_input_direct_title
        },
    )
    val sub = stringResource(
        when (mode) {
            PhoneInputServer.Mode.ADD_COMPUTER -> R.string.phone_input_add_pc_sub
            PhoneInputServer.Mode.DEVICE_NAME -> R.string.phone_input_device_name_sub
            PhoneInputServer.Mode.PLUGIN_REPO -> R.string.phone_input_repo_sub
            PhoneInputServer.Mode.PLUGIN_DEV_SERVER -> R.string.phone_input_dev_sub
            PhoneInputServer.Mode.PLUGIN_DIRECT_URL -> R.string.phone_input_direct_sub
        },
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
                when {
                    lockedOut -> {
                        Column(
                            Modifier.padding(top = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Notice(stringResource(R.string.phone_input_locked), WallIcons.Alert, danger = true)
                            WallButton(
                                text = stringResource(R.string.phone_input_reopen_qr),
                                onClick = {
                                    lockedOut = false
                                    error = null
                                    serverGeneration++
                                },
                                icon = WallIcons.Refresh,
                                kind = ButtonKind.Primary,
                            )
                        }
                    }
                    info != null -> {
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
                                    Text(
                                        stringResource(R.string.phone_input_code_label),
                                        style = WallType.Caption,
                                        color = Wall.Text2,
                                    )
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
                                        Text(
                                            stringResource(R.string.phone_input_connecting),
                                            style = WallType.Caption,
                                            color = Wall.Text2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Box(Modifier.padding(top = 24.dp)) {
                            Notice(stringResource(R.string.phone_input_no_wifi), WallIcons.Wifi, danger = false)
                        }
                    }
                }
            } else {
                val fieldLabel = stringResource(
                    when (mode) {
                        PhoneInputServer.Mode.ADD_COMPUTER -> R.string.phone_input_addr_label
                        PhoneInputServer.Mode.DEVICE_NAME -> R.string.phone_input_name_label
                        PhoneInputServer.Mode.PLUGIN_REPO -> R.string.phone_input_repo_label
                        PhoneInputServer.Mode.PLUGIN_DEV_SERVER -> R.string.phone_input_dev_label
                        PhoneInputServer.Mode.PLUGIN_DIRECT_URL -> R.string.phone_input_direct_label
                    },
                )
                val placeholder = stringResource(
                    when (mode) {
                        PhoneInputServer.Mode.ADD_COMPUTER -> R.string.phone_input_addr_placeholder
                        PhoneInputServer.Mode.DEVICE_NAME -> R.string.phone_input_name_placeholder
                        PhoneInputServer.Mode.PLUGIN_REPO -> R.string.phone_input_repo_placeholder
                        PhoneInputServer.Mode.PLUGIN_DEV_SERVER -> R.string.phone_input_dev_placeholder
                        PhoneInputServer.Mode.PLUGIN_DIRECT_URL -> R.string.phone_input_direct_placeholder
                    },
                )
                Column(Modifier.padding(top = 28.dp)) {
                    Text(fieldLabel, style = WallType.Caption, color = Wall.Text2)
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                        textStyle = WallType.Body,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (mode == PhoneInputServer.Mode.DEVICE_NAME) KeyboardType.Text else KeyboardType.Uri,
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
                    Text(
                        err.detail,
                        style = WallType.Caption,
                        color = Wall.Text2,
                        modifier = Modifier.padding(start = 38.dp, top = 4.dp),
                    )
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
                        else stringResource(
                            when (mode) {
                                PhoneInputServer.Mode.DEVICE_NAME -> R.string.action_save
                                PhoneInputServer.Mode.PLUGIN_DEV_SERVER -> R.string.plugins_action_load
                                PhoneInputServer.Mode.PLUGIN_DIRECT_URL -> R.string.plugins_action_download
                                else -> R.string.action_add
                            },
                        ),
                        onClick = { triggerSubmitFromKeyboard(textFieldState.text.toString()) },
                        kind = ButtonKind.Primary,
                    )
                    if (!lanIp.isNullOrBlank()) {
                        WallButton(
                            text = stringResource(R.string.phone_input_back_to_qr),
                            onClick = {
                                if (!busy) {
                                    error = null
                                    lockedOut = false
                                    useHeadsetKeyboard = false
                                }
                            },
                            kind = ButtonKind.Plain,
                        )
                    }
                } else {
                    WallButton(
                        text = stringResource(R.string.phone_input_use_headset_kb),
                        onClick = {
                            if (!busy) {
                                error = null
                                lockedOut = false
                                useHeadsetKeyboard = true
                            }
                        },
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
