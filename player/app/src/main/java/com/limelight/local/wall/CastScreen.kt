package com.limelight.local.wall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.limelight.local.dlna.CastReceiver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 投屏 (design-opus cast.html). Phone video apps cast over DLNA to the name
 * shown here; AirPlay is not built yet. 电脑串流 below is the Moonlight host
 * list.
 */
@Composable
fun CastScreen(nav: WallNav) {
    val dlna by CastReceiver.status.collectAsState()
    val copy = dlnaCastCopy(dlna)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.offset(64.dp, 136.dp).width(1152.dp)) {
            Notice(copy.notice, if (copy.danger) WallIcons.Alert else WallIcons.Cast, danger = copy.danger)
            Text(dlna.name, style = WallType.Display, modifier = Modifier.padding(top = 12.dp))
            Text(copy.body, style = WallType.Body, color = Wall.Text2, modifier = Modifier.padding(top = 12.dp))
        }
        Row(Modifier.offset(64.dp, 372.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            HowTo(WallIcons.Tablet, "iPad / iPhone", "即将支持 · 控制中心 › 屏幕镜像")
            HowTo(WallIcons.Phone, "手机里的视频 App", copy.phoneHowTo)
        }
        Row(Modifier.offset(64.dp, 520.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("电脑串流", style = WallType.Head)
            Text("整个桌面，也能玩游戏", style = WallType.Caption, color = Wall.Text2)
        }
        Row(Modifier.offset(64.dp, 576.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            OptionTile("电脑列表", "Moonlight · 配对和连接", WallIcons.Computer, nav.streaming,
                Modifier.size(368.dp, 96.dp), iconSize = 36.dp, horizontal = true)
            OptionTile("串流设置", "分辨率、码率、3D", WallIcons.Gear, nav.settings,
                Modifier.size(368.dp, 96.dp), iconSize = 36.dp, horizontal = true)
        }
        NavBar(Tab.Cast, nav::tab, nav.settings, solid = true)
    }
}

@Composable
private fun HowTo(icon: ImageVector, title: String, body: String) {
    Row(Modifier.width(540.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = Wall.Text2)
        Column {
            Text(title, style = WallType.Head.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold))
            Text(body, style = WallType.Body, color = Wall.Text2)
        }
    }
}
