package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.limelight.R
import kotlinx.coroutines.launch

/**
 * The WebDAV server configuration sheet: address, user, password.
 * The server is checked before saving so a wrong password surfaces immediately.
 */
@Composable
fun WebDavSheet(model: WallModel, nav: WallNav, suggested: String) {
    val context = LocalContext.current
    val saved = remember { model.savedWebDav() }
    val base = rememberTextFieldState(suggested)
    val user = rememberTextFieldState(saved?.user ?: "depth3d")
    val pass = rememberTextFieldState(saved?.pass ?: "")
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val found = saved == null && suggested != "http://"

    Box(Modifier.fillMaxSize()
        .background(Wall.Scrim)
        // Pointing past the sheet and pulling the trigger closes it, like the mockups' scrim
        .clickable(remember { MutableInteractionSource() }, indication = null) { if (!busy) nav.closeWebDav() },
        contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp)
            .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
            .clip(RoundedCornerShape(Wall.RadiusXl)).background(Wall.Sheet)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 48.dp, vertical = 40.dp)) {
            Text(
                stringResource(if (found) R.string.webdav_found_mac_title else R.string.webdav_default_title),
                style = WallType.Head,
            )
            Text(
                stringResource(R.string.webdav_setup_hint),
                style = WallType.Caption,
                color = Wall.Text2,
                modifier = Modifier.padding(top = 6.dp),
            )
            Field(stringResource(R.string.webdav_field_address), base, KeyboardType.Uri, Modifier.padding(top = 28.dp))
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Field(stringResource(R.string.webdav_field_user), user, KeyboardType.Ascii, Modifier.weight(1f))
                // Shown in the clear: typed on the headset keyboard, a mistake is otherwise invisible
                Field(stringResource(R.string.webdav_field_pass), pass, KeyboardType.Ascii, Modifier.weight(1f))
            }
            error?.let {
                Box(Modifier.padding(top = 20.dp)) { Notice(it) }
            }
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WallButton(
                    stringResource(if (busy) R.string.webdav_connecting else R.string.webdav_connect),
                    {
                        if (busy) {
                            return@WallButton
                        }
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                model.connectWebDav(
                                    base.text.trim().toString(),
                                    user.text.trim().toString(),
                                    pass.text.trim().toString(),
                                )
                                nav.closeWebDav()
                            } catch (e: Exception) {
                                error = context.getString(R.string.webdav_connect_failed)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    kind = ButtonKind.Primary,
                )
                WallButton(stringResource(R.string.action_cancel), { if (!busy) nav.closeWebDav() })
            }
        }
    }
}

@Composable
private fun Field(label: String, state: TextFieldState, type: KeyboardType, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = WallType.Caption, color = Wall.Text2)
        BasicTextField(state, Modifier.padding(top = 8.dp).fillMaxWidth(), textStyle = WallType.Body,
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(Wall.Accent),
            decorator = TextFieldDecorator { inner ->
                Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(Wall.Radius)).background(Wall.Surface)
                    .padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) { inner() }
            })
    }
}
