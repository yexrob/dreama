package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.local.media.PluginInternalException
import com.limelight.local.plugin.PluginLogEntry
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.DiscoveredLanService
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.UdpDiscoveryProtocol
import com.limelight.local.stream.UdpDiscoveryWindow
import com.limelight.local.stream.UdpLanServerDiscovery
import com.limelight.local.stream.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Developer Mode screen (`docs/specs/05-plugin-dev-install.md` §1):
 * - Auto-discovers `d3plugin serve` instances on the same Wi-Fi via [UdpLanServerDiscovery]
 *   ([UdpDiscoveryProtocol.PLUGIN_DEV]) with periodic re-probe & stale eviction.
 * - Accepts manual LAN addresses via [PhoneInputSheet] (`PLUGIN_DEV_SERVER`) or headset keyboard.
 * - Serializes load/reload operations via [SingleFlightOpRunner] so duplicate clicks never run in
 *   parallel and a cancelled task must finish completely before another operation can start.
 * - Displays inline recent logs (auto-scrolling to the latest entry after reload) and redacted
 *   raw technical load errors (syntax / manifest / `init` errors with line numbers) — the ONLY
 *   screen permitted to surface English technical diagnostics (and never sent to the phone web page).
 */
@Composable
fun PluginDeveloperScreen(model: WallModel, nav: WallNav) {
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()

    val installedPlugins by model.installedPlugins.collectAsState()
    val pluginInstances by model.pluginInstances.collectAsState()
    val hostChanges by model.pluginHost.changes.collectAsState()

    val devPlugins = remember(installedPlugins) {
        installedPlugins.filter { PluginPackageManager.isDevOrigin(it.sourceOrigin) }
    }
    val devPluginsById = remember(devPlugins) { devPlugins.associateBy { it.pluginId } }

    var lanIp by remember { mutableStateOf(model.streaming.localLanIp()) }
    var rescanToken by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<DiscoveredLanService>>(emptyList()) }

    var selectedLogPluginId by remember { mutableStateOf<String?>(null) }
    var logVersion by remember { mutableIntStateOf(0) }
    var logScrollToken by remember { mutableIntStateOf(0) }

    var showManualInput by remember { mutableStateOf(false) }
    val opRunner = remember { SingleFlightOpRunner() }
    var opBusy by remember { mutableStateOf(false) }
    var opCancelling by remember { mutableStateOf(false) }
    var verifyJob by remember { mutableStateOf<Job?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var devErrorSummary by remember { mutableStateOf<String?>(null) }
    var devTechError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            opRunner.cancel()
            verifyJob?.cancel()
        }
    }

    // Auto-select the first installed dev plugin for the inline log panel
    LaunchedEffect(devPlugins) {
        if (selectedLogPluginId == null || devPlugins.none { it.pluginId == selectedLogPluginId }) {
            selectedLogPluginId = devPlugins.firstOrNull()?.pluginId
        }
    }

    // Periodic LAN UDP discovery for `d3plugin serve`
    LaunchedEffect(rescanToken) {
        lanIp = model.streaming.localLanIp()
        if (lanIp.isNullOrBlank()) {
            discoveredServers = emptyList()
            searching = false
            return@LaunchedEffect
        }
        val window = UdpDiscoveryWindow()
        var roundId = 0
        while (isActive) {
            roundId++
            val currentRound = roundId
            val roundStartMs = System.currentTimeMillis()
            if (currentRound == 1) searching = true
            val found = UdpLanServerDiscovery.discover(
                protocol = UdpDiscoveryProtocol.PLUGIN_DEV,
                onFound = { partial ->
                    discoveredServers = window.onPartial(currentRound, partial)
                },
            )
            discoveredServers = window.onRoundComplete(currentRound, found)
            if (currentRound == 1) searching = false
            val sleepMs = UdpDiscoveryWindow.nextDelayMs(roundStartMs, System.currentTimeMillis())
            if (sleepMs > 0L) delay(sleepMs)
        }
    }

    // Refresh inline logs smoothly while viewing Developer Mode (so background home() reloads appear)
    LaunchedEffect(selectedLogPluginId, hostChanges) {
        if (selectedLogPluginId == null) return@LaunchedEffect
        logVersion++
        while (isActive) {
            delay(1_500L)
            logVersion++
        }
    }

    // Also surface redacted PluginInternalException from post-reload shelf refresh of the selected dev plugin
    val selectedPluginInstanceIds = remember(pluginInstances, selectedLogPluginId) {
        val pid = selectedLogPluginId ?: return@remember emptyList()
        pluginInstances.filter { it.pluginId == pid }.map { "${PluginHost.PLUGIN_SOURCE_PREFIX}${it.instanceId}" }
    }
    val shelfFailureForSelected = selectedPluginInstanceIds
        .mapNotNull { (model.shelves[it] as? Load.Failed)?.error as? PluginInternalException }
        .firstOrNull()
    LaunchedEffect(shelfFailureForSelected, selectedLogPluginId) {
        if (shelfFailureForSelected != null) {
            val (summary, tech) = PluginInstallGuard.formatDeveloperError(shelfFailureForSelected, strings)
            val redactedTech = if (tech != null) {
                withContext(Dispatchers.IO) {
                    model.pluginHost.packageManager().redactDeveloperError(selectedLogPluginId, tech)
                }
            } else {
                null
            }
            if (devTechError == null) {
                devErrorSummary = summary
                devTechError = redactedTech
            }
            logVersion++
            logScrollToken++
        }
    }

    val logEntries: List<PluginLogEntry> = remember(selectedLogPluginId, logVersion, hostChanges) {
        val pid = selectedLogPluginId ?: return@remember emptyList()
        model.recentLogsForPlugin(pid)
    }

    fun clearErrors() {
        devErrorSummary = null
        devTechError = null
    }

    suspend fun recordDeveloperFailure(error: Throwable, pluginId: String?, isReload: Boolean) {
        val (summary, tech) = if (isReload) {
            PluginInstallGuard.formatDevReloadError(error, strings)
        } else {
            PluginInstallGuard.formatDeveloperError(error, strings)
        }
        val redactedTech = if (tech != null) {
            withContext(Dispatchers.IO) {
                model.pluginHost.packageManager().redactDeveloperError(pluginId, tech)
            }
        } else {
            null
        }
        devErrorSummary = summary
        devTechError = redactedTech
        logVersion++
        logScrollToken++
    }

    fun verifyLoadedAfterInstall(pluginId: String) {
        selectedLogPluginId = pluginId
        verifyJob?.cancel()
        verifyJob = scope.launch {
            val loadErr = withContext(Dispatchers.IO) {
                model.pluginHost.packageManager().verifyInstalledPluginLoad(pluginId)
            }
            if (loadErr != null) {
                devErrorSummary = context.getString(R.string.dev_tech_error_title)
                devTechError = loadErr
            }
            logVersion++
            logScrollToken++
        }
    }

    fun startDevLoad(rawAddress: String, hintPluginId: String? = null) {
        opRunner.tryLaunch(
            scope = scope,
            onStateChanged = { b, c ->
                opBusy = b
                opCancelling = c
                if (!b) busyMessage = null
            },
        ) {
            clearErrors()
            if (!hintPluginId.isNullOrBlank()) selectedLogPluginId = hintPluginId
            busyMessage = context.getString(R.string.dev_loading_pkg)
            try {
                val prepared = withContext(Dispatchers.IO) {
                    PluginInstallGuard.prepareDevInstall(
                        packageManager = model.pluginHost.packageManager(),
                        repoClient = model.pluginHost.repoClient,
                        rawServerAddress = rawAddress,
                        localize = { b, p -> model.pluginHost.localizePreview(b, p) },
                    )
                }
                pendingInstall = prepared
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordDeveloperFailure(e, hintPluginId ?: selectedLogPluginId, isReload = false)
            }
        }
    }

    fun startDevReload(rawAddress: String, pluginId: String) {
        opRunner.tryLaunch(
            scope = scope,
            onStateChanged = { b, c ->
                opBusy = b
                opCancelling = c
                if (!b) busyMessage = null
            },
        ) {
            clearErrors()
            selectedLogPluginId = pluginId
            busyMessage = context.getString(R.string.dev_reloading_pkg)
            try {
                val outcome = withContext(Dispatchers.IO) {
                    PluginInstallGuard.reloadDevPlugin(
                        packageManager = model.pluginHost.packageManager(),
                        repoClient = model.pluginHost.repoClient,
                        rawServerAddress = rawAddress,
                        localize = { b, p -> model.pluginHost.localizePreview(b, p) },
                    )
                }
                when (outcome) {
                    is DevReloadOutcome.Reloaded -> {
                        model.pluginHost.notifyChanged()
                        selectedLogPluginId = outcome.result.entity.pluginId
                        if (outcome.loadTechnicalError != null) {
                            devErrorSummary = context.getString(R.string.dev_tech_error_title)
                            devTechError = outcome.loadTechnicalError
                        } else {
                            nav.toast(context.getString(R.string.dev_reloaded_toast, outcome.displayName))
                        }
                        logVersion++
                        logScrollToken++
                    }
                    is DevReloadOutcome.NeedsConfirmation -> {
                        pendingInstall = outcome.pending
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordDeveloperFailure(e, pluginId, isReload = true)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Header
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }
        Text(
            stringResource(R.string.dev_screen_title),
            style = WallType.Title,
            modifier = Modifier.offset(152.dp, 38.dp),
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WallButton(
                text = stringResource(R.string.dev_rescan_btn),
                onClick = { rescanToken++ },
                icon = WallIcons.Refresh,
                kind = ButtonKind.Plain,
            )
            WallButton(
                text = stringResource(R.string.dev_manual_addr_btn),
                onClick = { if (!opBusy) showManualInput = true },
                icon = WallIcons.Phone,
                kind = ButtonKind.Primary,
                enabled = !opBusy,
            )
        }

        // Top 1-sentence guide (§1.7)
        Text(
            stringResource(R.string.dev_screen_guide),
            style = WallType.Caption,
            color = Wall.Text2,
            modifier = Modifier.offset(64.dp, 112.dp).width(1152.dp),
        )

        // Two-column workspace: left = discovered + installed dev plugins, right = diagnostics + recent logs
        Row(
            Modifier.offset(64.dp, 160.dp).width(1152.dp).height(524.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Left column: discovered servers & installed dev plugins
            LazyColumn(
                Modifier.weight(1.08f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("discovered-header") {
                    Text(
                        stringResource(R.string.dev_discovered_section),
                        style = WallType.Label,
                        color = Wall.Text2,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                when {
                    lanIp.isNullOrBlank() -> {
                        item("no-wifi") {
                            Notice(stringResource(R.string.dev_no_wifi), WallIcons.Wifi, danger = false)
                        }
                    }
                    discoveredServers.isEmpty() && searching -> {
                        item("searching") {
                            Row(
                                Modifier.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BreathingDot()
                                Text(
                                    stringResource(R.string.dev_searching),
                                    style = WallType.Caption,
                                    color = Wall.Text2,
                                )
                            }
                        }
                    }
                    discoveredServers.isEmpty() -> {
                        item("empty-discovered") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Wall.Radius))
                                    .background(Wall.Surface)
                                    .padding(20.dp),
                            ) {
                                Text(
                                    stringResource(R.string.dev_discovered_empty),
                                    style = WallType.Caption,
                                    color = Wall.Text2,
                                )
                            }
                        }
                    }
                    else -> {
                        items(discoveredServers, key = { "disc:${it.endpointKey}" }) { svc ->
                            val pluginId = svc.txt["pluginId"].orEmpty()
                            val version = svc.txt["version"].orEmpty()
                            val addr = "${svc.address}:${svc.port}"
                            val serverUrl = svc.suggestedUrl()
                            val existingDev = devPluginsById[pluginId]
                            val expectedOrigin = PluginPackageManager.canonicalSourceOrigin(
                                repoUrl = null,
                                sourceUrl = null,
                                devServerUrl = serverUrl,
                            )
                            val isSameDevInstalled = existingDev != null && existingDev.sourceOrigin == expectedOrigin
                            val ctaLabel = stringResource(
                                if (isSameDevInstalled) R.string.dev_reload_btn else R.string.plugins_action_load,
                            )
                            val onTrigger = {
                                if (!opBusy) {
                                    if (isSameDevInstalled) {
                                        startDevReload(serverUrl, pluginId)
                                    } else {
                                        startDevLoad(serverUrl, pluginId)
                                    }
                                }
                            }
                            ManagementRow(
                                icon = WallIcons.Computer,
                                title = svc.serviceName,
                                sub = stringResource(R.string.dev_server_entry_sub, version, pluginId, addr),
                                trailingAction = {
                                    WallButton(
                                        text = ctaLabel,
                                        onClick = onTrigger,
                                        icon = if (isSameDevInstalled) WallIcons.Refresh else null,
                                        kind = ButtonKind.Plain,
                                        small = true,
                                        enabled = !opBusy,
                                    )
                                },
                                onClick = onTrigger,
                            )
                        }
                    }
                }

                item("installed-header") {
                    Text(
                        stringResource(R.string.dev_installed_section),
                        style = WallType.Label,
                        color = Wall.Text2,
                        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                    )
                }

                if (devPlugins.isEmpty()) {
                    item("installed-empty") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Wall.Radius))
                                .background(Wall.Surface)
                                .padding(20.dp),
                        ) {
                            Text(
                                stringResource(R.string.dev_installed_empty),
                                style = WallType.Caption,
                                color = Wall.Text2,
                            )
                        }
                    }
                } else {
                    items(devPlugins, key = { "dev:${it.pluginId}" }) { entity ->
                        val manifest = model.localizedManifestFor(entity)
                        val devAddr = PluginPackageManager.devDisplayAddressFromOrigin(entity.sourceOrigin).orEmpty()
                        val devUrl = PluginPackageManager.devServerBaseUrlFromOrigin(entity.sourceOrigin)
                        val isSelected = selectedLogPluginId == entity.pluginId
                        ManagementRow(
                            icon = WallIcons.Cube,
                            title = manifest.name,
                            badgeText = stringResource(R.string.plugins_origin_dev),
                            selected = isSelected,
                            sub = stringResource(R.string.dev_installed_entry_sub, entity.version, devAddr),
                            trailingAction = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (devUrl != null) {
                                        WallButton(
                                            text = stringResource(R.string.dev_reload_btn),
                                            onClick = { startDevReload(devUrl, entity.pluginId) },
                                            icon = WallIcons.Refresh,
                                            kind = ButtonKind.Plain,
                                            small = true,
                                            enabled = !opBusy,
                                        )
                                    }
                                    WallButton(
                                        text = stringResource(R.string.dev_detail_btn),
                                        onClick = { nav.openPluginDetail(entity.pluginId) },
                                        kind = ButtonKind.Ghost,
                                        small = true,
                                    )
                                }
                            },
                            onClick = {
                                selectedLogPluginId = entity.pluginId
                                logVersion++
                                logScrollToken++
                            },
                        )
                    }
                }
            }

            // Right column: developer error details (redacted technical errors) + inline recent logs
            Column(
                Modifier.weight(0.92f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (devErrorSummary != null || devTechError != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Wall.Radius))
                            .background(Wall.Danger.copy(alpha = 0.14f))
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.weight(1f)) {
                                Notice(
                                    devErrorSummary ?: stringResource(R.string.dev_tech_error_title),
                                    danger = true,
                                )
                            }
                            WallButton(
                                text = stringResource(R.string.plugin_logs_close),
                                onClick = { clearErrors() },
                                kind = ButtonKind.Ghost,
                                small = true,
                            )
                        }
                        devTechError?.let { rawTech ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 156.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Wall.Bg.copy(alpha = 0.72f))
                                    .verticalScroll(rememberScrollState())
                                    .padding(14.dp),
                            ) {
                                Text(
                                    text = rawTech,
                                    style = WallType.Caption.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 18.sp,
                                        lineHeight = 24.sp,
                                    ),
                                    color = Wall.Danger,
                                )
                            }
                        }
                    }
                }

                // Inline recent logs panel reusing PluginLogListPanel
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val logTitle = selectedLogPluginId?.let { pid ->
                        stringResource(R.string.dev_logs_section, model.pluginDisplayName(pid))
                    } ?: stringResource(R.string.plugin_logs_title)
                    Text(
                        logTitle,
                        style = WallType.Label,
                        color = Wall.Text2,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedLogPluginId != null) {
                        WallButton(
                            text = stringResource(R.string.dev_logs_refresh),
                            onClick = {
                                logVersion++
                                logScrollToken++
                            },
                            kind = ButtonKind.Ghost,
                            small = true,
                        )
                        if (logEntries.isNotEmpty()) {
                            WallButton(
                                text = stringResource(R.string.plugin_logs_clear),
                                onClick = {
                                    selectedLogPluginId?.let { model.clearLogsForPlugin(it) }
                                    logVersion++
                                },
                                kind = ButtonKind.Ghost,
                                small = true,
                            )
                        }
                    }
                }

                PluginLogListPanel(
                    entries = logEntries,
                    emptyText = stringResource(R.string.dev_logs_empty),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    autoScrollToLatest = true,
                    scrollToken = logScrollToken,
                )
            }
        }

        if (showManualInput) {
            PhoneInputSheet(
                mode = PhoneInputServer.Mode.PLUGIN_DEV_SERVER,
                lanIp = model.streaming.localLanIp(),
                initialValue = "",
                onSubmit = { entered ->
                    try {
                        val prepared = withContext(Dispatchers.IO) {
                            PluginInstallGuard.prepareDevInstall(
                                packageManager = model.pluginHost.packageManager(),
                                repoClient = model.pluginHost.repoClient,
                                rawServerAddress = entered,
                                localize = { b, p -> model.pluginHost.localizePreview(b, p) },
                            )
                        }
                        clearErrors()
                        pendingInstall = prepared
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val (summary, tech) = PluginInstallGuard.formatDeveloperError(e, strings)
                        val redactedTech = if (tech != null) {
                            withContext(Dispatchers.IO) {
                                model.pluginHost.packageManager().redactDeveloperError(selectedLogPluginId, tech)
                            }
                        } else {
                            null
                        }
                        devErrorSummary = summary
                        devTechError = redactedTech
                        // Security invariant (#6): NEVER send raw technical errors back to the phone web page;
                        // return only the Simplified Chinese summary while showing redacted details on headset.
                        UserFacingError(
                            context.getString(R.string.phone_action_dev_server),
                            summary,
                        )
                    }
                },
                onDismiss = { showManualInput = false },
            )
        }

        if (opBusy) {
            BusyModal(
                message = if (opCancelling) {
                    stringResource(R.string.dev_cancelling_op)
                } else {
                    busyMessage ?: stringResource(R.string.dev_loading_pkg)
                },
                onCancel = {
                    if (!opCancelling) {
                        opRunner.cancel { b, c ->
                            opBusy = b
                            opCancelling = c
                        }
                    }
                },
            )
        }

        pendingInstall?.let { pending ->
            val targetPluginId = pending.preview.manifest.id
            PluginInstallConfirmSheet(
                pending = pending,
                model = model,
                strings = strings,
                onInstalled = { name ->
                    pendingInstall = null
                    clearErrors()
                    nav.toast(
                        context.getString(
                            if (pending.preview.isUpdate || pending.preview.isDevAddressSwitch) {
                                R.string.dev_reloaded_toast
                            } else {
                                R.string.plugins_installed_toast
                            },
                            name,
                        ),
                    )
                    verifyLoadedAfterInstall(targetPluginId)
                },
                onDismiss = { pendingInstall = null },
                onDevTechnicalError = { rawTech ->
                    devErrorSummary = context.getString(R.string.dev_tech_error_title)
                    devTechError = rawTech
                    selectedLogPluginId = targetPluginId
                    logVersion++
                    logScrollToken++
                },
            )
        }
    }
}
