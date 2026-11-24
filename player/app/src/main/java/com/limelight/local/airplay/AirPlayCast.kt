package com.limelight.local.airplay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.limelight.LimeLog
import com.limelight.local.wall.ButtonKind
import com.limelight.local.wall.Notice
import com.limelight.local.wall.Wall
import com.limelight.local.wall.WallButton
import com.limelight.local.wall.WallIcons
import com.limelight.local.wall.WallType

/*
 * The AirPlay half of the cast page (design-opus cast.html): the line over
 * the name for each state of the receiver, and the two states that take
 * the whole page, 连接中 and 没有网络. CastScreen places them.
 */

/** Connecting and no Wi-Fi replace the page's content (the nav stays). */
val AirPlayState.Status.takesPage: Boolean
    get() = phase == AirPlayState.Phase.CONNECTING || phase == AirPlayState.Phase.NO_WIFI

/** The notice over the receiver's name. */
@Composable
fun AirPlayNotice(status: AirPlayState.Status) {
    when (status.phase) {
        AirPlayState.Phase.WAITING -> WaitingNotice("正在等待投屏")
        AirPlayState.Phase.MIRRORING ->
            Notice(AirPlayState.phrase("正在显示", status.client, "的屏幕"), WallIcons.Cast, danger = false)
        AirPlayState.Phase.FAILED ->
            Notice("投屏没有启动" + (status.problem?.let { "：$it" } ?: ""))
        else -> Notice("正在准备投屏…", WallIcons.Cast, danger = false)
    }
}

/** The page for the states [takesPage] is true of. */
@Composable
fun AirPlayTakeover(status: AirPlayState.Status) {
    val context = LocalContext.current
    if (status.phase == AirPlayState.Phase.NO_WIFI) {
        Centered(
            { Icon(WifiIcon, null, Modifier.size(40.dp), tint = Wall.Danger) },
            "还没连上 Wi-Fi",
            "投屏和串流需要头显和 iPad、手机、电脑在同一个 Wi-Fi 里。",
        ) {
            WallButton("打开 Wi-Fi 设置", { openWifiSettings(context) }, kind = ButtonKind.Primary)
        }
    } else {
        Centered(
            { Spinner() },
            AirPlayState.phrase("正在连接", status.client, ""),
            "画面一出来就会自动进入 3D 播放。",
        ) {
            WallButton("取消", { AirPlayService.disconnect() }, kind = ButtonKind.Plain)
        }
    }
}

// .abs at top 214, centred: the 88 px circle, the title, a line, one action
@Composable
private fun Centered(icon: @Composable () -> Unit, title: String, body: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().offset(y = 214.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Wall.Fill), contentAlignment = Alignment.Center) {
            icon()
        }
        Text(title, style = WallType.Title, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
        Text(body, style = WallType.Body, color = Wall.Text2, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.padding(top = 40.dp)) {
            action()
        }
    }
}

// One turn a second, linear (.spin)
@Composable
private fun Spinner() {
    val turn = rememberInfiniteTransition(label = "spin")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spin")
    Icon(WallIcons.Spinner, null, Modifier.size(40.dp).graphicsLayer { rotationZ = angle }, tint = Wall.Text)
}

// The notice with .breath's dot in place of an icon: 14 px, down to 35 %
// and back every 2.4 s
@Composable
private fun WaitingNotice(text: String) {
    val breath = rememberInfiniteTransition(label = "breath")
    val a by breath.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "breath")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Wall.Text2))
        }
        Text(text, style = WallType.Caption, color = Wall.Text2)
    }
}

private fun openWifiSettings(context: Context) {
    for (action in arrayOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            LimeLog.warning("AirPlay: no $action: $e")
        } catch (e: SecurityException) {
            LimeLog.warning("AirPlay: no $action: $e")
        }
    }
}

// i-wifi from the mockups, drawn the way WallIcons draws its set
private val WifiIcon: ImageVector = ImageVector.Builder("wifi", 24.dp, 24.dp, 24f, 24f).apply {
    for (d in arrayOf(
        "M3.5 9.6a12 12 0 0 1 17 0M6.6 12.9a7.6 7.6 0 0 1 10.8 0M9.6 16.1a3.3 3.3 0 0 1 4.8 0",
        "M12 19.4h.01",
    )) {
        addPath(
            pathData = addPathNodes(d),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()
