package com.limelight.local.wall

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.WebDavSource
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginManifest
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginRepoEntity
import com.limelight.local.plugin.PluginSourceMismatchException
import com.limelight.local.plugin.RepoPluginEntry
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.DiscoveredLanService
import com.limelight.local.stream.NsdServiceBrowser
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SourcesTab { SOURCES, PLUGINS }

/**
 * Main Sources & Plugins management screen matching `design-opus/mockups/sources.html`:
 * - Sources tab: Local, WebDAV, and all configured plugin instances (problem-only red status)
 * - Plugins tab: installed plugins (version, source, permissions, update CTA, detail) + subscribed repos
 */
@Composable
fun SourcesScreen(model: WallModel, nav: WallNav, initialTab: SourcesTab = SourcesTab.SOURCES) {
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()

    var activeTab by rememberSaveable { mutableStateOf(initialTab) }
    val sources by model.sources.collectAsState()
    val installedPlugins by model.installedPlugins.collectAsState()
    val pluginInstances by model.pluginInstances.collectAsState()
    val pluginRepos by model.pluginRepos.collectAsState()
    val reposByUrl = remember(pluginRepos) { pluginRepos.associateBy { it.url } }
    val pluginsById = remember(installedPlugins) { installedPlugins.associateBy { it.pluginId } }
    val availableUpdates = remember(installedPlugins, pluginRepos) {
        PluginInstallGuard.findAvailableUpdates(installedPlugins, pluginRepos, model.pluginHost.repoClient)
    }

    var showAddRepoSheet by remember { mutableStateOf(false) }
    var busyJob by remember { mutableStateOf<Job?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { busyJob?.cancel() }
    }

    fun startPrepareUpdate(update: AvailablePluginUpdate) {
        if (busyJob?.isActive == true) return
        installError = null
        busyMessage = context.getString(R.string.plugins_repo_downloading)
        busyJob = scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    PluginInstallGuard.prepareRepoInstall(
                        packageManager = model.pluginHost.packageManager,
                        repoClient = model.pluginHost.repoClient,
                        repoUrl = update.repoUrl,
                        entry = update.entry,
                        localize = { b, p -> model.pluginHost.localizePreview(b, p) },
                    )
                }
                pendingInstall = prepared
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                installError = PluginInstallGuard.formatInstallError(e, strings)
            } finally {
                busyMessage = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Header (back button, title, primary action button)
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }
        Text(
            stringResource(R.string.sources_title),
            style = WallType.Title,
            modifier = Modifier.offset(152.dp, 38.dp),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 64.dp)) {
            if (activeTab == SourcesTab.SOURCES) {
                WallButton(
                    text = stringResource(R.string.sources_add_source_btn),
                    onClick = { nav.openAddSource() },
                    icon = WallIcons.Plus,
                    kind = ButtonKind.Primary,
                )
            } else {
                WallButton(
                    text = stringResource(R.string.sources_add_repo_btn),
                    onClick = { showAddRepoSheet = true },
                    icon = WallIcons.Plus,
                    kind = ButtonKind.Primary,
                )
            }
        }

        // Tab pills (left: 38dp, top: 118dp)
        Row(
            Modifier.offset(38.dp, 118.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourcesTabPill(
                label = stringResource(R.string.sources_tab_sources),
                selected = activeTab == SourcesTab.SOURCES,
                onClick = { activeTab = SourcesTab.SOURCES },
            )
            SourcesTabPill(
                label = stringResource(R.string.sources_tab_plugins),
                selected = activeTab == SourcesTab.PLUGINS,
                onClick = { activeTab = SourcesTab.PLUGINS },
            )
        }

        installError?.let { err ->
            Box(Modifier.offset(320.dp, 132.dp)) {
                Notice(err, danger = true)
            }
        }

        // Main list (left: 40dp, top: 206dp, width: 1200dp)
        LazyColumn(
            Modifier.offset(40.dp, 206.dp).width(1200.dp).height(494.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (activeTab == SourcesTab.SOURCES) {
                // Local source
                item("local") {
                    val count = (model.shelves[LocalVideoSource.ID] as? Load.Ready)?.value?.firstOrNull()?.category?.count
                    val sub = count?.let {
                        stringResource(R.string.library_label_with_count, stringResource(R.string.sources_local_desc), it)
                    } ?: stringResource(R.string.sources_local_desc)
                    ManagementRow(
                        icon = WallIcons.Headset,
                        title = stringResource(R.string.source_local_name),
                        sub = sub,
                        onClick = { nav.openLibrarySelecting(LocalVideoSource.ID) },
                    )
                }

                // WebDAV source (when configured)
                val wd = sources.firstOrNull { it.source.kind == SourceKind.WEBDAV }
                if (wd != null) {
                    item("webdav") {
                        val shelfState = model.shelves[WebDavSource.ID]
                        val problem = (shelfState as? Load.Failed)?.let {
                            stringResource(R.string.plugin_status_unreachable)
                        }
                        ManagementRow(
                            icon = WallIcons.Cloud,
                            title = wd.source.name,
                            sub = wd.source.description,
                            dangerStatus = problem,
                            onClick = { nav.editWebDav() },
                        )
                    }
                }

                // Plugin instances (show ALL instances including disabled/needs-reconfirm so user can manage them)
                items(pluginInstances, key = { it.instanceId }) { inst ->
                    val pkg = pluginsById[inst.pluginId]
                    val pluginName = pkg?.let { model.localizedManifestFor(it).name } ?: inst.pluginId
                    val sourceId = PluginMediaSource.sourceIdFor(inst.instanceId)
                    val shelfState = model.shelves[sourceId]
                    val itemCount = (shelfState as? Load.Ready)?.value?.sumOf { it.items.size }?.takeIf { it > 0 }
                    val sub = if (itemCount != null) {
                        stringResource(R.string.library_label_with_count, pluginName, itemCount)
                    } else {
                        pluginName
                    }
                    // Normal sources show NO status text; only problems speak up (sources.html)
                    val problem: String? = when {
                        pkg != null && !pkg.enabled -> stringResource(R.string.plugins_state_disabled)
                        pkg != null && pkg.needsPermissionReconfirm -> stringResource(R.string.sources_status_reconfirm)
                        inst.authState == "NEEDS_AUTH" -> stringResource(R.string.plugin_status_needs_auth)
                        shelfState is Load.Failed -> when (shelfState.error) {
                            is SourceNeedsAuthException -> stringResource(R.string.plugin_status_needs_auth)
                            is SourceNeedsSetupException -> stringResource(R.string.plugin_status_needs_setup)
                            else -> stringResource(R.string.plugin_status_unreachable)
                        }
                        else -> null
                    }
                    ManagementRow(
                        icon = WallIcons.Server,
                        title = inst.displayName,
                        sub = sub,
                        dangerStatus = problem,
                        onClick = { nav.openSourceEdit(inst.instanceId) },
                    )
                }
            } else {
                // Installed plugins
                items(installedPlugins, key = { it.pluginId }) { pkg ->
                    val manifest = remember(pkg.pluginId, pkg.version, pkg.manifestJson) {
                        model.localizedManifestFor(pkg)
                    }
                    val origin = PluginPermissionSummary.sourceLabel(pkg, reposByUrl, strings)
                    val perm = PluginPermissionSummary.summarizeOneLine(manifest.permissions, strings)
                    val sub = "$origin · ${pkg.version} · $perm"
                    val problem = when {
                        pkg.needsPermissionReconfirm -> stringResource(R.string.sources_status_reconfirm)
                        !pkg.enabled -> stringResource(R.string.plugins_state_disabled)
                        else -> null
                    }
                    val update = availableUpdates[pkg.pluginId]
                    ManagementRow(
                        icon = WallIcons.Cube,
                        title = manifest.name,
                        sub = sub,
                        dangerStatus = if (pkg.needsPermissionReconfirm) problem else null,
                        mutedStatus = if (!pkg.enabled && !pkg.needsPermissionReconfirm) problem else null,
                        trailingAction = update?.let { upd ->
                            {
                                WallButton(
                                    text = stringResource(R.string.plugins_update_to, upd.entry.version),
                                    onClick = { startPrepareUpdate(upd) },
                                    small = true,
                                )
                            }
                        },
                        onClick = { nav.openPluginDetail(pkg.pluginId) },
                    )
                }

                // Subscribed repositories
                if (pluginRepos.isNotEmpty()) {
                    item("repos-header") {
                        Text(
                            stringResource(R.string.plugins_repos_section),
                            style = WallType.Head,
                            modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 6.dp),
                        )
                    }
                    items(pluginRepos, key = { "repo:${it.url}" }) { repo ->
                        ManagementRow(
                            icon = WallIcons.Cloud,
                            title = repo.name.ifBlank { repo.url },
                            sub = repo.url,
                            trailingAction = {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    WallButton(
                                        text = stringResource(R.string.plugins_repo_browse),
                                        onClick = { nav.openRepoBrowse(repo.url) },
                                        small = true,
                                    )
                                    WallButton(
                                        text = stringResource(R.string.plugins_repo_remove),
                                        onClick = {
                                            scope.launch {
                                                model.removePluginRepo(repo.url)
                                                nav.toast(context.getString(R.string.plugins_repo_removed_toast))
                                            }
                                        },
                                        kind = ButtonKind.Ghost,
                                        small = true,
                                    )
                                }
                            },
                            onClick = { nav.openRepoBrowse(repo.url) },
                        )
                    }
                }
            }
        }

        if (showAddRepoSheet) {
            PhoneInputSheet(
                mode = PhoneInputServer.Mode.PLUGIN_REPO,
                lanIp = model.streaming.localLanIp(),
                initialValue = "https://",
                onSubmit = { enteredUrl ->
                    val clean = enteredUrl.trim()
                    try {
                        model.syncPluginRepo(clean)
                        showAddRepoSheet = false
                        nav.openRepoBrowse(clean)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        UserFacingError(
                            title = context.getString(R.string.sources_add_repo_btn),
                            detail = PluginInstallGuard.formatInstallError(e, strings),
                        )
                    }
                },
                onDismiss = { showAddRepoSheet = false },
            )
        }

        busyMessage?.let { msg ->
            BusyModal(
                message = msg,
                onCancel = {
                    busyJob?.cancel()
                    busyJob = null
                    busyMessage = null
                },
            )
        }

        pendingInstall?.let { pending ->
            PluginInstallConfirmSheet(
                pending = pending,
                model = model,
                strings = strings,
                onInstalled = { name ->
                    pendingInstall = null
                    nav.toast(context.getString(R.string.plugins_installed_toast, name))
                },
                onDismiss = { pendingInstall = null },
            )
        }
    }
}

/**
 * Add Source screen matching `design-opus/mockups/sources.html#add`:
 * - Automatic LAN discovery reusing [NsdServiceBrowser] from `HostDiscovery.kt`
 * - Manual grid of enabled plugins (respecting `multiInstance`) + WebDAV
 */
@Composable
fun AddSourceScreen(model: WallModel, nav: WallNav) {
    val context = LocalContext.current
    val installedPlugins by model.installedPlugins.collectAsState()
    val pluginInstances by model.pluginInstances.collectAsState()
    val discoveredWebDav by model.discovered.collectAsState()

    val enabledManifests = remember(installedPlugins) {
        installedPlugins
            .filter { it.enabled && !it.needsPermissionReconfirm }
            .map { it to model.localizedManifestFor(it) }
    }
    val instanceCounts = remember(pluginInstances) {
        pluginInstances.groupingBy { it.pluginId }.eachCount()
    }

    // Collect every unique mDNS service type declared by enabled plugins' config[].discover
    val serviceTypeToPlugin = remember(enabledManifests) {
        val map = LinkedHashMap<String, Pair<InstalledPluginEntity, PluginManifest>>()
        for ((pkg, manifest) in enabledManifests) {
            for (field in manifest.config) {
                val rawType = field.discover?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val norm = NsdServiceBrowser.normalizeServiceType(rawType)
                if (norm !in map) {
                    map[norm] = pkg to manifest
                }
            }
        }
        map
    }

    // Track already-configured server URLs so LAN list marks them with ✓ 已添加
    val configuredUrls = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(pluginInstances) {
        val urls = withContext(Dispatchers.IO) {
            val out = HashSet<String>()
            for (inst in pluginInstances) {
                val cfg = runCatching { model.pluginHost.packageManager.getInstanceConfig(inst.instanceId) }.getOrNull()
                    ?: continue
                for (v in cfg.values) {
                    val s = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.trimEnd('/')
                    if (!s.isNullOrEmpty() && (s.startsWith("http://") || s.startsWith("https://"))) {
                        out += s.lowercase()
                    }
                }
            }
            out
        }
        configuredUrls.clear()
        urls.forEach { configuredUrls[it] = true }
    }

    val discoveredServices = remember { mutableStateMapOf<String, List<DiscoveredLanService>>() }
    val browsers = remember(serviceTypeToPlugin.keys.toList()) {
        serviceTypeToPlugin.keys.map { svcType -> NsdServiceBrowser(context, svcType) }
    }
    DisposableEffect(browsers) {
        browsers.forEach { it.start() }
        onDispose { browsers.forEach { it.stop() } }
    }
    for (browser in browsers) {
        val list by browser.services.collectAsState()
        LaunchedEffect(browser.serviceType, list) {
            discoveredServices[browser.serviceType] = list
        }
    }

    val allLanItems = discoveredServices.entries.flatMap { (svcType, list) ->
        val pair = serviceTypeToPlugin[svcType] ?: return@flatMap emptyList()
        list.map { svc -> Triple(svc, pair.first, pair.second) }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }
        Text(
            stringResource(R.string.add_source_title),
            style = WallType.Title,
            modifier = Modifier.offset(152.dp, 38.dp),
        )

        // Section 1: LAN auto-discovered servers (top: 126dp)
        SectionTitle(stringResource(R.string.add_source_lan_section), Modifier.offset(64.dp, 126.dp))
        LazyColumn(
            Modifier.offset(40.dp, 180.dp).width(1200.dp).height(200.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (allLanItems.isEmpty() && discoveredWebDav == null) {
                item("searching") {
                    Row(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BreathingDot()
                        Text(
                            stringResource(R.string.add_source_lan_searching),
                            style = WallType.Body,
                            color = Wall.Text2,
                        )
                    }
                }
            }
            items(allLanItems, key = { it.first.endpointKey }) { (svc, pkg, manifest) ->
                val url = svc.suggestedUrl()
                val alreadyAdded = configuredUrls.containsKey(url.trimEnd('/').lowercase())
                ManagementRow(
                    icon = WallIcons.Server,
                    title = svc.serviceName,
                    sub = "${manifest.name} · ${svc.address}:${svc.port}",
                    trailingAction = {
                        if (alreadyAdded) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(WallIcons.Check, null, Modifier.size(24.dp), tint = Wall.Text2)
                                Text(stringResource(R.string.add_source_lan_added), style = WallType.Caption, color = Wall.Text2)
                            }
                        } else {
                            WallButton(
                                text = stringResource(R.string.action_add),
                                onClick = {
                                    nav.openPluginForm(
                                        pluginId = pkg.pluginId,
                                        prefillServerUrl = url,
                                        suggestedName = svc.serviceName,
                                    )
                                },
                                small = true,
                            )
                        }
                    },
                    onClick = {
                        if (!alreadyAdded) {
                            nav.openPluginForm(
                                pluginId = pkg.pluginId,
                                prefillServerUrl = url,
                                suggestedName = svc.serviceName,
                            )
                        }
                    },
                )
            }
            discoveredWebDav?.let { wdUrl ->
                item("lan-webdav") {
                    ManagementRow(
                        icon = WallIcons.Cloud,
                        title = wdUrl.substringAfter("://"),
                        sub = stringResource(R.string.add_source_webdav_tile),
                        trailingAction = {
                            WallButton(
                                text = stringResource(R.string.action_add),
                                onClick = { nav.editWebDav(wdUrl) },
                                small = true,
                            )
                        },
                        onClick = { nav.editWebDav(wdUrl) },
                    )
                }
            }
        }

        // Section 2: Manual add grid (top: 400dp)
        SectionTitle(stringResource(R.string.add_source_manual_section), Modifier.offset(64.dp, 400.dp))
        val manualPlugins = enabledManifests.filter { (pkg, manifest) ->
            manifest.multiInstance || (instanceCounts[pkg.pluginId] ?: 0) == 0
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.offset(64.dp, 452.dp).width(1152.dp).height(148.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(manualPlugins, key = { it.first.pluginId }) { (pkg, manifest) ->
                ManualSourceTile(
                    label = manifest.name,
                    icon = WallIcons.Server,
                    onClick = { nav.openPluginForm(pluginId = pkg.pluginId) },
                )
            }
            item("manual-webdav") {
                ManualSourceTile(
                    label = stringResource(R.string.add_source_webdav_tile),
                    icon = WallIcons.Cloud,
                    onClick = { nav.editWebDav() },
                )
            }
        }

        Text(
            stringResource(R.string.add_source_lan_hint),
            style = WallType.Caption,
            color = Wall.Text2,
            modifier = Modifier.offset(64.dp, 622.dp),
        )
    }
}

/**
 * Edit / re-login / rename / 2-step delete screen for a configured plugin instance.
 */
@Composable
fun SourceEditScreen(model: WallModel, nav: WallNav, page: Page.SourceEdit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instances by model.pluginInstances.collectAsState()
    val plugins by model.installedPlugins.collectAsState()
    val inst = instances.firstOrNull { it.instanceId == page.instanceId }
    val pkg = inst?.let { i -> plugins.firstOrNull { it.pluginId == i.pluginId } }
    val manifest = pkg?.let { model.localizedManifestFor(it) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameSheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }
        Text(
            inst?.displayName ?: stringResource(R.string.sources_edit_title),
            style = WallType.Title,
            modifier = Modifier.offset(152.dp, 38.dp),
        )

        if (inst == null) {
            Box(Modifier.offset(64.dp, 160.dp)) {
                Notice(stringResource(R.string.library_source_missing))
            }
        } else {
            Column(
                Modifier.offset(64.dp, 136.dp).width(920.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    manifest?.name ?: inst.pluginId,
                    style = WallType.Caption,
                    color = Wall.Text2,
                )
                if (pkg != null && pkg.needsPermissionReconfirm) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Wall.Radius))
                            .background(Wall.Surface)
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Notice(stringResource(R.string.plugins_detail_reconfirm_title), danger = true)
                            Text(
                                stringResource(R.string.plugins_detail_reconfirm_body),
                                style = WallType.Caption,
                                color = Wall.Text2,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        WallButton(
                            text = stringResource(R.string.plugin_action_confirm_permissions),
                            onClick = { nav.openPluginDetail(pkg.pluginId) },
                            kind = ButtonKind.Primary,
                            small = true,
                        )
                    }
                }
                ManagementRow(
                    icon = WallIcons.Server,
                    title = stringResource(R.string.sources_edit_config_btn),
                    sub = stringResource(R.string.phone_form_hint),
                    onClick = { nav.openPluginForm(pluginId = inst.pluginId, instanceId = inst.instanceId) },
                )
                ManagementRow(
                    icon = WallIcons.Keyboard,
                    title = stringResource(R.string.sources_rename_btn),
                    sub = inst.displayName,
                    onClick = { showRenameSheet = true },
                )
                ManagementRow(
                    icon = WallIcons.Trash,
                    title = stringResource(R.string.sources_delete_btn),
                    sub = stringResource(R.string.sources_delete_confirm_body),
                    dangerStatus = null,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }

        if (showRenameSheet && inst != null) {
            PhoneInputSheet(
                mode = PhoneInputServer.Mode.DEVICE_NAME,
                lanIp = model.streaming.localLanIp(),
                initialValue = inst.displayName,
                onSubmit = { entered ->
                    val trimmed = entered.trim()
                    if (trimmed.isEmpty()) {
                        UserFacingError(
                            context.getString(R.string.err_device_name_empty_title),
                            context.getString(R.string.err_device_name_empty_detail),
                        )
                    } else {
                        model.renamePluginInstance(inst.instanceId, trimmed)
                        nav.toast(context.getString(R.string.sources_renamed_toast, trimmed))
                        null
                    }
                },
                onDismiss = { showRenameSheet = false },
            )
        }

        if (showDeleteConfirm && inst != null) {
            TwoStepConfirmSheet(
                title = stringResource(R.string.sources_delete_confirm_title, inst.displayName),
                body = stringResource(R.string.sources_delete_confirm_body),
                confirmLabel = stringResource(R.string.sources_delete_confirm_btn),
                busy = busy,
                onConfirm = {
                    if (busy) return@TwoStepConfirmSheet
                    busy = true
                    scope.launch {
                        try {
                            val name = inst.displayName
                            model.deletePluginInstance(inst.instanceId)
                            showDeleteConfirm = false
                            nav.toast(context.getString(R.string.sources_deleted_toast, name))
                            nav.back()
                        } finally {
                            busy = false
                        }
                    }
                },
                onDismiss = { if (!busy) showDeleteConfirm = false },
            )
        }
    }
}

/**
 * Plugin detail screen: version, source, permissions, permission re-confirmation, enable/disable,
 * logs viewer + clear, and 2-step uninstall (hidden for built-in plugins).
 */
@Composable
fun PluginDetailScreen(model: WallModel, nav: WallNav, page: Page.PluginDetail) {
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()
    val installedPlugins by model.installedPlugins.collectAsState()
    val pluginRepos by model.pluginRepos.collectAsState()
    val reposByUrl = remember(pluginRepos) { pluginRepos.associateBy { it.url } }

    val entity = installedPlugins.firstOrNull { it.pluginId == page.pluginId }
    val manifest = entity?.let { model.localizedManifestFor(it) }
    val isBuiltIn = entity?.let { BuiltInPluginInstaller.isBuiltIn(it) } == true

    var showUninstallConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }
        Text(
            manifest?.name ?: stringResource(R.string.plugins_detail_title),
            style = WallType.Title,
            modifier = Modifier.offset(152.dp, 38.dp),
        )

        if (entity == null || manifest == null) {
            Box(Modifier.offset(64.dp, 160.dp)) {
                Notice(stringResource(R.string.library_source_missing))
            }
        } else {
            val origin = PluginPermissionSummary.sourceLabel(entity, reposByUrl, strings)
            val permLines = PluginPermissionSummary.detailLines(manifest.permissions, strings)

            LazyColumn(
                Modifier.offset(64.dp, 128.dp).width(1152.dp).height(560.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item("meta") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Wall.Radius))
                            .background(Wall.Surface)
                            .padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(manifest.description.ifBlank { manifest.name }, style = WallType.Body)
                        val meta = listOfNotNull(
                            stringResource(R.string.plugins_detail_version, entity.version),
                            entity.author.takeIf { it.isNotBlank() }?.let { stringResource(R.string.plugins_detail_author, it) },
                            stringResource(R.string.plugins_detail_source, origin),
                        )
                        Text(meta.joinToString(" · "), style = WallType.Caption, color = Wall.Text2)
                    }
                }

                item("permissions") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Wall.Radius))
                            .background(Wall.Surface)
                            .padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.plugins_detail_permissions), style = WallType.Head)
                        for (line in permLines) {
                            Text("· $line", style = WallType.Body, color = Wall.Text2)
                        }
                        if (manifest.permissions.allowsWildcardLanAccess) {
                            Notice(stringResource(R.string.plugins_perm_wildcard_lan), danger = true)
                        }
                    }
                }

                if (entity.needsPermissionReconfirm) {
                    item("reconfirm") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Wall.Radius))
                                .background(Wall.SurfaceHi)
                                .padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Notice(stringResource(R.string.plugins_detail_reconfirm_title), danger = true)
                                Text(
                                    stringResource(R.string.plugins_detail_reconfirm_body),
                                    style = WallType.Caption,
                                    color = Wall.Text2,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            WallButton(
                                text = stringResource(R.string.plugins_detail_reconfirm_btn),
                                onClick = {
                                    if (busy) return@WallButton
                                    busy = true
                                    scope.launch {
                                        try {
                                            model.confirmPluginPermissions(entity.pluginId)
                                            nav.toast(context.getString(R.string.plugins_detail_reconfirmed_toast))
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                kind = ButtonKind.Primary,
                                enabled = !busy,
                            )
                        }
                    }
                }

                item("enable-toggle") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 88.dp)
                            .clip(RoundedCornerShape(Wall.Radius))
                            .background(Wall.Surface)
                            .padding(horizontal = 28.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.plugins_detail_enable_title), style = WallType.Label)
                            Text(
                                stringResource(R.string.plugins_detail_enable_sub),
                                style = WallType.Caption,
                                color = Wall.Text2,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        WallSwitch(checked = entity.enabled) { nextEnabled ->
                            scope.launch { model.setPluginEnabled(entity.pluginId, nextEnabled) }
                        }
                    }
                }

                item("actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WallButton(
                            text = stringResource(R.string.plugin_action_view_logs),
                            onClick = { nav.openPluginLogs(entity.pluginId) },
                            icon = WallIcons.Info,
                        )
                        if (!isBuiltIn) {
                            WallButton(
                                text = stringResource(R.string.plugins_detail_uninstall),
                                onClick = { showUninstallConfirm = true },
                                icon = WallIcons.Trash,
                                kind = ButtonKind.Ghost,
                            )
                        }
                    }
                }
            }
        }

        if (showUninstallConfirm && entity != null && manifest != null && !isBuiltIn) {
            TwoStepConfirmSheet(
                title = stringResource(R.string.plugins_detail_uninstall_confirm_title, manifest.name),
                body = stringResource(R.string.plugins_detail_uninstall_confirm_body),
                confirmLabel = stringResource(R.string.plugins_detail_uninstall_confirm_btn),
                busy = busy,
                onConfirm = {
                    if (busy) return@TwoStepConfirmSheet
                    busy = true
                    scope.launch {
                        try {
                            model.uninstallPlugin(entity)
                            showUninstallConfirm = false
                            nav.toast(context.getString(R.string.plugins_uninstalled_toast, manifest.name))
                            nav.back()
                        } catch (e: Exception) {
                            nav.toast(PluginInstallGuard.formatInstallError(e, strings))
                        } finally {
                            busy = false
                        }
                    }
                },
                onDismiss = { if (!busy) showUninstallConfirm = false },
            )
        }
    }
}

/**
 * Browse plugins in a subscribed repository and install/update them through the explicit
 * [PluginInstallConfirmSheet] confirmation card.
 */
@Composable
fun RepoBrowseScreen(model: WallModel, nav: WallNav, page: Page.RepoBrowse) {
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()
    val repos by model.pluginRepos.collectAsState()
    val installedPlugins by model.installedPlugins.collectAsState()
    val installedById = remember(installedPlugins) { installedPlugins.associateBy { it.pluginId } }

    val repo = repos.firstOrNull { it.url == page.repoUrl }
    val index = remember(repo?.lastIndexJson) {
        repo?.lastIndexJson?.let {
            runCatching { model.pluginHost.repoClient.parseAndValidateIndex(it) }.getOrNull()
        }
    }

    var busyJob by remember { mutableStateOf<Job?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PendingPluginInstall?>(null) }

    DisposableEffect(Unit) {
        onDispose { busyJob?.cancel() }
    }

    fun startDownload(entry: RepoPluginEntry) {
        if (busyJob?.isActive == true) return
        errorText = null
        busyMessage = context.getString(R.string.plugins_repo_downloading)
        busyJob = scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    PluginInstallGuard.prepareRepoInstall(
                        packageManager = model.pluginHost.packageManager,
                        repoClient = model.pluginHost.repoClient,
                        repoUrl = page.repoUrl,
                        entry = entry,
                        localize = { b, p -> model.pluginHost.localizePreview(b, p) },
                    )
                }
                pendingInstall = prepared
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorText = PluginInstallGuard.formatInstallError(e, strings)
            } finally {
                busyMessage = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }
        Column(Modifier.offset(152.dp, 28.dp)) {
            Text(repo?.name?.ifBlank { page.repoUrl } ?: page.repoUrl, style = WallType.Title)
            Text(page.repoUrl, style = WallType.Caption, color = Wall.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        errorText?.let { err ->
            Box(Modifier.offset(64.dp, 126.dp)) { Notice(err, danger = true) }
        }

        val entries = index?.plugins.orEmpty()
        LazyColumn(
            Modifier.offset(40.dp, 176.dp).width(1200.dp).height(520.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                item("empty") {
                    Text(
                        stringResource(R.string.plugins_repo_empty),
                        style = WallType.Body,
                        color = Wall.Text2,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    val existing = installedById[entry.id]
                    val alreadyLatest = existing != null && existing.version == entry.version
                    val sub = buildString {
                        append(entry.version)
                        if (entry.description.isNotBlank()) append(" · ").append(entry.description)
                    }
                    ManagementRow(
                        icon = WallIcons.Cube,
                        title = entry.name,
                        sub = sub,
                        trailingAction = {
                            if (alreadyLatest) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(WallIcons.Check, null, Modifier.size(24.dp), tint = Wall.Text2)
                                    Text(stringResource(R.string.plugins_repo_installed), style = WallType.Caption, color = Wall.Text2)
                                }
                            } else {
                                WallButton(
                                    text = if (existing != null) {
                                        stringResource(R.string.plugins_update_to, entry.version)
                                    } else {
                                        stringResource(R.string.plugins_repo_install)
                                    },
                                    onClick = { startDownload(entry) },
                                    kind = ButtonKind.Primary,
                                    small = true,
                                )
                            }
                        },
                        onClick = { if (!alreadyLatest) startDownload(entry) },
                    )
                }
            }
        }

        busyMessage?.let { msg ->
            BusyModal(
                message = msg,
                onCancel = {
                    busyJob?.cancel()
                    busyJob = null
                    busyMessage = null
                },
            )
        }

        pendingInstall?.let { pending ->
            PluginInstallConfirmSheet(
                pending = pending,
                model = model,
                strings = strings,
                onInstalled = { name ->
                    pendingInstall = null
                    nav.toast(context.getString(R.string.plugins_installed_toast, name))
                },
                onDismiss = { pendingInstall = null },
            )
        }
    }
}

/**
 * Explicit plugin install/update/replacement confirmation card (`plugin-protocol.md` §7 & §11.4):
 * - Displays plugin name, version, author, and all requested network permissions
 * - Explicitly surfaces wildcard-LAN warning (`可访问你局域网里的任意设备`) when present
 * - Explicitly warns and requires separate user confirmation when replacing a different-source
 *   plugin of the same ID — NEVER auto-confirms `PluginSourceMismatchException`!
 */
@Composable
fun PluginInstallConfirmSheet(
    pending: PendingPluginInstall,
    model: WallModel,
    strings: AndroidStringLookup,
    onInstalled: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val preview = pending.preview
    val manifest = preview.manifest
    val permLines = remember(manifest) { PluginPermissionSummary.detailLines(manifest.permissions, strings) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requireExplicitReplace by remember(pending) { mutableStateOf(preview.requiresReplacementConfirmation) }

    val titleRes = when {
        requireExplicitReplace -> R.string.plugins_install_replace_title
        preview.isUpdate -> R.string.plugins_install_update_title
        else -> R.string.plugins_install_confirm_title
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Wall.Scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (!busy) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(820.dp)
                .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 44.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(titleRes), style = WallType.Head)
            Text(
                "${manifest.name} · v${manifest.version}" +
                    if (manifest.author.isNotBlank()) " · ${manifest.author}" else "",
                style = WallType.Body,
                color = Wall.Text2,
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Wall.Radius))
                    .background(Wall.Surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.plugins_detail_permissions), style = WallType.Label)
                for (line in permLines) {
                    Text("· $line", style = WallType.Caption, color = Wall.Text2)
                }
                if (preview.allowsWildcardLanAccess) {
                    Notice(stringResource(R.string.plugins_perm_wildcard_lan), danger = true)
                }
                for (warn in preview.permissionWarnings) {
                    if (!warn.contains(com.limelight.local.plugin.PluginPackageManager.WILDCARD_LAN_WARNING) || !preview.allowsWildcardLanAccess) {
                        Notice(warn, danger = true)
                    }
                }
            }

            if (requireExplicitReplace) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Wall.Radius))
                        .background(Wall.Danger.copy(alpha = 0.14f))
                        .padding(18.dp),
                ) {
                    Notice(stringResource(R.string.plugins_install_replace_warning), danger = true)
                }
            }

            error?.let { Notice(it, danger = true) }

            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WallButton(
                    text = stringResource(
                        if (requireExplicitReplace) R.string.plugins_install_replace_btn
                        else R.string.plugins_install_confirm_btn,
                    ),
                    onClick = {
                        if (busy) return@WallButton
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    PluginInstallGuard.confirmInstall(
                                        packageManager = model.pluginHost.packageManager,
                                        pending = pending,
                                        userConfirmedReplacement = requireExplicitReplace,
                                    )
                                }
                                model.pluginHost.notifyChanged()
                                onInstalled(manifest.name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: PluginSourceMismatchException) {
                                // Never auto-confirm: escalate UI to explicit replacement warning
                                requireExplicitReplace = true
                                error = PluginInstallGuard.formatInstallError(e, strings)
                            } catch (e: Exception) {
                                error = PluginInstallGuard.formatInstallError(e, strings)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    kind = ButtonKind.Primary,
                    enabled = !busy,
                )
                WallButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { if (!busy) onDismiss() },
                    kind = ButtonKind.Plain,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
internal fun SourcesTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val bg = when {
        selected -> Wall.Sel
        hovered -> Wall.Fill
        else -> Color.Transparent
    }
    Box(
        Modifier
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(bg)
            .ray(source, onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = WallType.Label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected || hovered) Wall.Text else Wall.Text2,
        )
    }
}

@Composable
private fun ManagementRow(
    icon: ImageVector,
    title: String,
    sub: String,
    dangerStatus: String? = null,
    mutedStatus: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(interaction)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(Wall.Radius))
            .background(if (hovered) Wall.SurfaceHi else Color.Transparent)
            .ray(interaction, onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Icon(icon, null, Modifier.size(36.dp), tint = if (hovered) Wall.Text else Wall.Text2)
        Column(Modifier.weight(1f)) {
            Text(title, style = WallType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = WallType.Caption.copy(lineHeight = 30.sp),
                    color = Wall.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (dangerStatus != null) {
            Notice(dangerStatus, danger = true)
        } else if (mutedStatus != null) {
            Text(mutedStatus, style = WallType.Caption, color = Wall.Text3)
        }
        if (trailingAction != null) {
            trailingAction()
        } else {
            Icon(WallIcons.ChevR, null, Modifier.size(26.dp), tint = Wall.Text2)
        }
    }
}

@Composable
private fun ManualSourceTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(interaction)
    Column(
        Modifier
            .height(132.dp)
            .graphicsLayer {
                val s = if (hovered) 1.04f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(Wall.Radius))
            .background(if (hovered) Wall.SurfaceHi else Wall.Surface)
            .ray(interaction, onClick)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(36.dp), tint = if (hovered) Wall.Text else Wall.Text2)
        Text(label, style = WallType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun TwoStepConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    busy: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Wall.Scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (!busy) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(680.dp)
                .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 44.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = WallType.Head)
            Text(body, style = WallType.Body, color = Wall.Text2)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WallButton(confirmLabel, onConfirm, kind = ButtonKind.Primary, enabled = !busy)
                WallButton(stringResource(R.string.action_cancel), onDismiss, enabled = !busy)
            }
        }
    }
}

@Composable
internal fun BusyModal(message: String, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Wall.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(540.dp)
                .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .padding(horizontal = 44.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            WallSpinner(size = 44.dp)
            Text(message, style = WallType.Body)
            WallButton(stringResource(R.string.action_cancel), onCancel, small = true)
        }
    }
}
