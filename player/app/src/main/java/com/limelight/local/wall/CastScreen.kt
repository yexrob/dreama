package com.limelight.local.wall

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.local.airplay.AirPlayNotice
import com.limelight.local.airplay.AirPlayState
import com.limelight.local.airplay.AirPlayTakeover
import com.limelight.local.stream.CertConfirmKind
import com.limelight.local.airplay.takesPage
import com.limelight.local.dlna.CastReceiver
import com.limelight.local.dlna.ReceiverStatus
import com.limelight.local.stream.ComputerStatus
import com.limelight.local.stream.CrashRecoveryPrompt
import com.limelight.local.stream.HttpHostProtocolClient
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.StreamFlowState
import com.limelight.local.stream.StreamingComputer
import com.limelight.local.stream.StreamingController

/**
 * 投屏 (design-opus/mockups/cast.html):
 * Top half hosts AirPlay + DLNA under one headset name; bottom half hosts
 * integrated PC streaming (discovery, one-click / 4-digit PIN pairing,
 * cancelable connecting flow, WoL, app grid, and phone-QR add computer).
 */
@Composable
fun CastScreen(model: WallModel, nav: WallNav) {
    val context = LocalContext.current
    val streaming = model.streaming
    val dlna by CastReceiver.status.collectAsState()
    val copy = dlnaCastCopy(context, dlna)
    val air by AirPlayState.status.collectAsStateWithLifecycle()
    val computers by streaming.computers.collectAsState()
    val discovering by streaming.discovering.collectAsState()
    val hasWifi by streaming.hasWifi.collectAsState()
    val flow by streaming.flow.collectAsState()
    val banner by streaming.bannerNotice.collectAsState()
    val crashPrompt by streaming.crashPrompt.collectAsState()

    var showAddSheet by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<StreamingComputer?>(null) }

    // Cancel any in-flight connect/pair if the user navigates away from CastScreen
    // so returning to the page can never remain stuck on "正在连接".
    DisposableEffect(streaming) {
        onDispose {
            when (streaming.flow.value) {
                is StreamFlowState.Connecting,
                is StreamFlowState.AutoPairing,
                is StreamFlowState.PinPairing -> streaming.cancelActiveOperation()
                else -> Unit
            }
        }
    }

    // Active pairing / connecting / session failure takes over the page cleanly
    if (flow !is StreamFlowState.Idle) {
        Box(Modifier.fillMaxSize()) {
            StreamFlowTakeover(flow, streaming, nav)
            NavBar(Tab.Cast, {
                streaming.cancelActiveOperation()
                nav.tab(it)
            }, {
                streaming.cancelActiveOperation()
                nav.openSettings()
            }, solid = true)
        }
        return
    }

    // Unified No-Wi-Fi state across casting and PC streaming
    if (!hasWifi && (air.phase == AirPlayState.Phase.NO_WIFI || dlna.phase == ReceiverStatus.Phase.NO_NETWORK)) {
        Box(Modifier.fillMaxSize()) {
            NoWifiTakeover()
            NavBar(Tab.Cast, nav::tab, nav::openSettings, solid = true)
        }
        return
    }

    if (air.takesPage) {
        Box(Modifier.fillMaxSize()) {
            AirPlayTakeover(air)
            NavBar(Tab.Cast, nav::tab, nav::openSettings, solid = true)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 136.dp, bottom = 72.dp),
        ) {
            item("cast-header") {
                Column(Modifier.padding(horizontal = Wall.Margin).width(1152.dp)) {
                    if (copy.danger) {
                        Notice(copy.notice, WallIcons.Alert, danger = true)
                    } else {
                        AirPlayNotice(air)
                    }
                    Text(dlna.name, style = WallType.Display, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        stringResource(R.string.cast_subtitle),
                        style = WallType.Body,
                        color = Wall.Text2,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            item("cast-howto") {
                Row(
                    Modifier.padding(start = Wall.Margin, end = Wall.Margin, top = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                ) {
                    HowTo(
                        WallIcons.Tablet,
                        stringResource(R.string.cast_howto_apple_title),
                        stringResource(R.string.cast_howto_apple_body),
                    )
                    HowTo(
                        WallIcons.Phone,
                        stringResource(R.string.cast_howto_phone_title),
                        stringResource(R.string.cast_howto_phone_body, dlna.name),
                    )
                }
            }

            if (banner != null) {
                item("stream-banner") {
                    Row(
                        Modifier
                            .padding(start = Wall.Margin, end = Wall.Margin, top = 28.dp)
                            .clip(RoundedCornerShape(Wall.Radius))
                            .background(Wall.Surface)
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(banner!!, style = WallType.Caption, color = Wall.Text, modifier = Modifier.weight(1f, fill = false))
                        WallButton(
                            stringResource(R.string.action_close),
                            { streaming.dismissBanner() },
                            kind = ButtonKind.Ghost,
                            small = true,
                        )
                    }
                }
            }

            crashPrompt?.let { prompt ->
                item("stream-crash-recovery") {
                    CrashRecoveryCard(
                        prompt = prompt,
                        onResetDefaults = { streaming.resetStreamingAfterCrash() },
                        onDismiss = { streaming.dismissCrashPrompt() },
                    )
                }
            }

            item("stream-header") {
                Row(
                    Modifier.padding(start = Wall.Margin, end = Wall.Margin, top = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(stringResource(R.string.stream_section_title), style = WallType.Head)
                    Text(stringResource(R.string.stream_section_subtitle), style = WallType.Caption, color = Wall.Text2)
                }
            }

            item("stream-cards") {
                when {
                    discovering && computers.isEmpty() -> {
                        Column(Modifier.padding(start = Wall.Margin, top = 20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BreathingDot()
                                Text(stringResource(R.string.stream_discovering), style = WallType.Caption, color = Wall.Text2)
                            }
                            Row(
                                Modifier.padding(top = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                Skeleton(Modifier.size(368.dp, 96.dp), Wall.Radius)
                                AddComputerCard { showAddSheet = true }
                            }
                        }
                    }
                    computers.isEmpty() -> {
                        Column(
                            Modifier
                                .padding(start = Wall.Margin, end = Wall.Margin, top = 20.dp)
                                .width(1152.dp)
                                .clip(RoundedCornerShape(Wall.Radius))
                                .background(Wall.Surface.copy(alpha = 0.7f))
                                .padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.stream_empty_title),
                                style = WallType.Head.copy(fontSize = 26.sp),
                            )
                            Text(
                                stringResource(R.string.stream_empty_guide),
                                style = WallType.Body,
                                color = Wall.Text2,
                            )
                            Row(
                                Modifier.padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                AddComputerCard { showAddSheet = true }
                                WallButton(
                                    text = stringResource(R.string.action_search_again),
                                    onClick = { streaming.refreshDiscovery() },
                                    icon = WallIcons.Refresh,
                                    kind = ButtonKind.Plain,
                                )
                            }
                        }
                    }
                    else -> {
                        LazyRow(
                            Modifier.padding(top = 20.dp).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = Wall.Margin),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            items(computers, key = { it.id }) { pc ->
                                PcCard(
                                    computer = pc,
                                    onPrimaryClick = {
                                        when (pc.status) {
                                            ComputerStatus.ONLINE_PAIRED,
                                            ComputerStatus.ONLINE_STREAMING -> nav.connectComputer(pc)
                                            ComputerStatus.ONLINE_UNPAIRED -> streaming.startPair(pc)
                                            ComputerStatus.OFFLINE,
                                            ComputerStatus.DISCOVERING -> actionTarget = pc
                                        }
                                    },
                                    onMoreClick = { actionTarget = pc },
                                )
                            }
                            item("add-pc") {
                                AddComputerCard { showAddSheet = true }
                            }
                        }
                    }
                }
            }
        }

        NavBar(Tab.Cast, nav::tab, nav::openSettings, solid = true)

        if (showAddSheet) {
            PhoneInputSheet(
                mode = PhoneInputServer.Mode.ADD_COMPUTER,
                lanIp = streaming.localLanIp(),
                onSubmit = { addr -> streaming.addComputerByAddress(addr) },
                onDismiss = { showAddSheet = false },
            )
        }

        actionTarget?.let { target ->
            PcActionSheet(
                computer = target,
                onConnect = {
                    actionTarget = null
                    nav.connectComputer(target)
                },
                onApps = {
                    actionTarget = null
                    nav.openPcApps(target)
                },
                onQuitApp = {
                    actionTarget = null
                    streaming.quitRunningApp(target)
                },
                onPair = {
                    actionTarget = null
                    streaming.startPair(target)
                },
                onWake = {
                    actionTarget = null
                    streaming.wakeComputer(target)
                },
                onRemove = {
                    actionTarget = null
                    streaming.removeComputer(target)
                },
                onDismiss = { actionTarget = null },
            )
        }
    }
}

/**
 * 368x96 computer card matching design-opus/mockups/cast.html `.pc`.
 * Shared between CastScreen and HomeScreen's bottom 「电脑」 row.
 */
@Composable
fun PcCard(
    computer: StreamingComputer,
    onPrimaryClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val scale by animateFloatAsState(if (hovered) 1.03f else 1f, tween(140, easing = Wall.Ease), label = "pc-s")
    val bg by animateColorAsState(if (hovered) Wall.SurfaceHi else Wall.Surface, tween(140), label = "pc-bg")
    val dimmed = computer.status == ComputerStatus.OFFLINE
    val statusText = when {
        !computer.streamReady && computer.streamNotReadyReason != null ->
            stringResource(R.string.stream_status_not_ready, computer.streamNotReadyReason!!)
        computer.isPairedOffline -> stringResource(R.string.stream_status_paired_offline)
        computer.needsHostTrustSetup && computer.isOnline ->
            stringResource(R.string.stream_status_needs_host_setup)
        computer.status == ComputerStatus.ONLINE_STREAMING -> stringResource(R.string.stream_status_streaming)
        computer.status == ComputerStatus.ONLINE_PAIRED -> stringResource(R.string.stream_status_paired)
        computer.status == ComputerStatus.ONLINE_UNPAIRED ->
            if (computer.autoPairSupported) stringResource(R.string.stream_status_unpaired_auto)
            else stringResource(R.string.stream_status_unpaired)
        computer.status == ComputerStatus.DISCOVERING -> stringResource(R.string.stream_status_discovering)
        else -> stringResource(R.string.stream_status_offline)
    }
    val subColor = when {
        !computer.streamReady -> Wall.Danger
        computer.isPairedOffline -> Wall.Text2
        computer.needsHostTrustSetup && computer.isOnline -> Wall.Accent
        computer.status == ComputerStatus.ONLINE_STREAMING -> Wall.Accent
        hovered -> Wall.Text
        dimmed -> Wall.Text3
        else -> Wall.Text2
    }

    Row(
        modifier
            .width(384.dp)
            .height(96.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (dimmed) 0.72f else 1f
            }
            .clip(RoundedCornerShape(Wall.Radius))
            .background(bg)
            .ray(source, onPrimaryClick)
            .padding(start = 24.dp, end = if (onMoreClick != null) 12.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                WallIcons.Computer,
                null,
                Modifier.size(36.dp),
                tint = if (computer.isOnline) Wall.Text else Wall.Text3,
            )
            if (computer.status == ComputerStatus.ONLINE_STREAMING) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Wall.Accent),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                computer.name,
                style = WallType.Label,
                color = if (dimmed) Wall.Text2 else Wall.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusText,
                style = WallType.Caption.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onMoreClick != null) {
            val moreSource = remember { MutableInteractionSource() }
            val moreHover by rememberRayHover(moreSource)
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (moreHover) Wall.FillHi else Color.Transparent)
                    .ray(moreSource, onMoreClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(WallIcons.More, stringResource(R.string.stream_more_actions), Modifier.size(26.dp), tint = Wall.Text2)
            }
        }
    }
}

@Composable
private fun AddComputerCard(onClick: () -> Unit) {
    OptionTile(
        title = stringResource(R.string.stream_add_pc_title),
        sub = stringResource(R.string.stream_add_pc_sub),
        icon = WallIcons.Plus,
        onClick = onClick,
        modifier = Modifier.size(368.dp, 96.dp),
        iconSize = 32.dp,
        horizontal = true,
    )
}

@Composable
private fun StreamFlowTakeover(flow: StreamFlowState, streaming: StreamingController, nav: WallNav) {
    when (flow) {
        StreamFlowState.Idle -> Unit
        is StreamFlowState.AutoPairing -> {
            CenteredTakeover(
                icon = { WallSpinner() },
                title = stringResource(R.string.pair_auto_title, flow.computer.name),
                body = flow.stageText,
            ) {
                WallButton(stringResource(R.string.action_cancel), { streaming.cancelActiveOperation() })
            }
        }
        is StreamFlowState.PinPairing -> {
            // Matches design-opus/mockups/cast.html #pin
            Column(
                Modifier.fillMaxWidth().offset(y = 186.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    flow.pin,
                    style = WallType.Display.copy(
                        fontSize = 88.sp,
                        lineHeight = 96.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 24.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = Wall.Text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.pair_pin_title, flow.computer.name),
                    style = WallType.Title,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Row(
                    Modifier.padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BreathingDot()
                    Text(stringResource(R.string.pair_pin_waiting), style = WallType.Body, color = Wall.Text2)
                }
                Box(Modifier.padding(top = 44.dp)) {
                    WallButton(stringResource(R.string.action_cancel), { streaming.cancelActiveOperation() })
                }
            }
        }
        is StreamFlowState.CertConfirm -> {
            val oldFp = HttpHostProtocolClient.formatFingerprint(flow.expectedFingerprint)
            val newFp = HttpHostProtocolClient.formatFingerprint(flow.actualFingerprint)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 96.dp).offset(y = 124.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Wall.Fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(WallIcons.Shield, null, Modifier.size(36.dp), tint = Wall.Accent)
                }
                Text(
                    stringResource(
                        if (flow.kind == CertConfirmKind.STREAM_SERVER_CERT) R.string.pair_gs_cert_title
                        else R.string.pair_cert_title,
                        flow.computer.name,
                    ),
                    style = WallType.Title,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    stringResource(
                        if (flow.kind == CertConfirmKind.STREAM_SERVER_CERT) R.string.pair_gs_cert_body
                        else R.string.pair_cert_body,
                    ),
                    style = WallType.Body,
                    color = Wall.Text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Column(
                    Modifier
                        .padding(top = 20.dp)
                        .width(820.dp)
                        .clip(RoundedCornerShape(Wall.Radius))
                        .background(Wall.Surface)
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (oldFp.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.pair_cert_old_label), style = WallType.Caption, color = Wall.Text3)
                            Text(
                                oldFp,
                                style = WallType.Caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 18.sp,
                                    lineHeight = 24.sp,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = Wall.Text2,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.pair_cert_new_label), style = WallType.Caption, color = Wall.Accent)
                        Text(
                            newFp,
                            style = WallType.Caption.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                                fontFeatureSettings = "tnum",
                            ),
                            color = Wall.Text,
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    WallButton(
                        stringResource(R.string.pair_cert_trust),
                        { nav.confirmChangedCert(flow) },
                        kind = ButtonKind.Primary,
                    )
                    WallButton(stringResource(R.string.action_cancel), { streaming.clearFlow() })
                }
            }
        }
        is StreamFlowState.Connecting -> {
            val title = if (!flow.appName.isNullOrBlank()) {
                stringResource(R.string.stream_connecting_app_title, flow.appName)
            } else {
                stringResource(R.string.stream_connecting_title, flow.computer.name)
            }
            CenteredTakeover(
                icon = { WallSpinner() },
                title = title,
                body = flow.stageText,
            ) {
                WallButton(stringResource(R.string.action_cancel), { streaming.cancelActiveOperation() })
            }
        }
        is StreamFlowState.Failed -> {
            var remainingSec by remember(flow) { mutableStateOf(flow.retryCooldownSec) }
            LaunchedEffect(flow) {
                while (remainingSec > 0) {
                    kotlinx.coroutines.delay(1_000L)
                    remainingSec--
                }
            }
            CenteredTakeover(
                icon = { Icon(WallIcons.Alert, null, Modifier.size(40.dp), tint = Wall.Danger) },
                title = flow.error.title,
                body = flow.error.detail,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    flow.retryAction?.let { retry ->
                        WallButton(
                            text = if (remainingSec > 0) {
                                stringResource(R.string.action_retry_countdown, remainingSec)
                            } else {
                                stringResource(R.string.action_retry)
                            },
                            onClick = { nav.retryStream(retry) },
                            icon = WallIcons.Refresh,
                            kind = ButtonKind.Primary,
                            enabled = remainingSec == 0,
                        )
                    }
                    flow.fallbackPinComputer?.let { pc ->
                        WallButton(
                            stringResource(R.string.pair_fallback_manual),
                            { streaming.startPair(pc, forceManualPin = true) },
                            kind = ButtonKind.Plain,
                        )
                    }
                    WallButton(
                        stringResource(R.string.action_back),
                        { streaming.clearFlow() },
                        kind = if (flow.retryAction == null) ButtonKind.Primary else ButtonKind.Plain,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoWifiTakeover() {
    val context = LocalContext.current
    CenteredTakeover(
        icon = { Icon(WallIcons.Wifi, null, Modifier.size(40.dp), tint = Wall.Danger) },
        title = stringResource(R.string.cast_no_wifi_title),
        body = stringResource(R.string.cast_no_wifi_body),
    ) {
        WallButton(
            stringResource(R.string.action_open_wifi),
            { openSystemWifiSettings(context) },
            kind = ButtonKind.Primary,
        )
    }
}

@Composable
private fun CenteredTakeover(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 96.dp).offset(y = 200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(Wall.Fill),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(title, style = WallType.Title, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
        Text(
            body,
            style = WallType.Body,
            color = Wall.Text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Box(Modifier.padding(top = 40.dp)) {
            action()
        }
    }
}

@Composable
private fun PcActionSheet(
    computer: StreamingComputer,
    onConnect: () -> Unit,
    onApps: () -> Unit,
    onQuitApp: () -> Unit,
    onPair: () -> Unit,
    onWake: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingRemove by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Wall.Scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(640.dp)
                .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 44.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (confirmingRemove) {
                Text(stringResource(R.string.pc_action_remove_confirm_title, computer.name), style = WallType.Head)
                Text(
                    stringResource(R.string.pc_action_remove_confirm_body),
                    style = WallType.Body,
                    color = Wall.Text2,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WallButton(stringResource(R.string.pc_action_remove_confirm_btn), onRemove, kind = ButtonKind.Primary)
                    WallButton(stringResource(R.string.action_cancel), { confirmingRemove = false })
                }
            } else {
                Text(computer.name, style = WallType.Head)
                computer.address?.let {
                    Text(it, style = WallType.Caption, color = Wall.Text3)
                }
                Spacer(Modifier.height(10.dp))

                when (computer.status) {
                    ComputerStatus.ONLINE_STREAMING -> {
                        ListRow(stringResource(R.string.pc_action_resume), lead = WallIcons.Play, onClick = onConnect)
                        ListRow(stringResource(R.string.pc_action_apps), lead = WallIcons.Grid, onClick = onApps)
                        if (computer.needsHostTrustSetup) {
                            ListRow(stringResource(R.string.pc_action_complete_host_setup), lead = WallIcons.Computer, onClick = onPair)
                        }
                        ListRow(stringResource(R.string.pc_action_quit_app), lead = WallIcons.Power, onClick = onQuitApp)
                    }
                    ComputerStatus.ONLINE_PAIRED -> {
                        ListRow(stringResource(R.string.pc_action_connect), lead = WallIcons.Play, onClick = onConnect)
                        ListRow(stringResource(R.string.pc_action_apps), lead = WallIcons.Grid, onClick = onApps)
                        if (computer.needsHostTrustSetup) {
                            ListRow(stringResource(R.string.pc_action_complete_host_setup), lead = WallIcons.Computer, onClick = onPair)
                        }
                    }
                    ComputerStatus.ONLINE_UNPAIRED -> {
                        ListRow(stringResource(R.string.pc_action_pair), lead = WallIcons.Computer, onClick = onPair)
                    }
                    ComputerStatus.OFFLINE, ComputerStatus.DISCOVERING -> {
                        if (computer.isPairedOffline || (computer.isSaved && computer.raw?.pairState == com.limelight.nvstream.http.PairingManager.PairState.PAIRED)) {
                            ListRow(stringResource(R.string.pc_action_retry_connect), lead = WallIcons.Refresh, onClick = onConnect)
                        }
                        if (computer.canWake) {
                            ListRow(stringResource(R.string.pc_action_wake), lead = WallIcons.Power, onClick = onWake)
                        }
                    }
                }
                if (computer.isSaved) {
                    ListRow(stringResource(R.string.pc_action_remove), lead = WallIcons.Trash, onClick = { confirmingRemove = true })
                }
                Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WallButton(stringResource(R.string.action_close), onDismiss, kind = ButtonKind.Ghost)
                }
            }
        }
    }
}

@Composable
private fun CrashRecoveryCard(
    prompt: CrashRecoveryPrompt,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .padding(start = Wall.Margin, end = Wall.Margin, top = 28.dp)
            .width(1152.dp)
            .clip(RoundedCornerShape(Wall.Radius))
            .background(Wall.Surface)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Notice(
            text = stringResource(
                if (prompt.autoResetApplied) R.string.stream_crash_auto_reset_title
                else R.string.stream_crash_title,
            ),
            icon = WallIcons.Alert,
            danger = true,
        )
        Text(
            text = stringResource(
                if (prompt.autoResetApplied) R.string.stream_crash_auto_reset_body
                else R.string.stream_crash_body,
            ),
            style = WallType.Caption,
            color = Wall.Text2,
        )
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (prompt.autoResetApplied) {
                WallButton(
                    text = stringResource(R.string.action_got_it),
                    onClick = onDismiss,
                    kind = ButtonKind.Primary,
                    small = true,
                )
            } else {
                WallButton(
                    text = stringResource(R.string.stream_crash_reset_btn),
                    onClick = onResetDefaults,
                    kind = ButtonKind.Primary,
                    small = true,
                )
                WallButton(
                    text = stringResource(R.string.action_ignore),
                    onClick = onDismiss,
                    kind = ButtonKind.Ghost,
                    small = true,
                )
            }
        }
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

private fun openSystemWifiSettings(context: Context) {
    for (action in arrayOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            LimeLog.warning("depth3d: no $action: ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            LimeLog.warning("depth3d: no $action: ${e.javaClass.simpleName}")
        }
    }
}
