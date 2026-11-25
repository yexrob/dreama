package com.limelight.local.wall

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.local.plugin.PackageInstallException
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local file & direct HTTPS `.d3plugin` installer (`docs/specs/05-plugin-dev-install.md` §2):
 * - Scans `Download/` and `Android/data/<package>/files/plugins/` (the latter needs zero storage
 *   permissions and serves as the universal `adb push` drop folder).
 * - Silently ignores unreadable `Download/` on Android 11+ Scoped Storage (`targetSdk 36`) and
 *   provides `ACTION_OPEN_DOCUMENT` as the universal system file picker.
 * - Also supports direct HTTPS `.d3plugin` URL install via [PhoneInputSheet] (`PLUGIN_DIRECT_URL`).
 * - Every path runs `previewPackage` and shows the shared [PluginInstallConfirmSheet].
 */
@Composable
fun PluginFileInstallScreen(model: WallModel, nav: WallNav) {
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val downloadDir = remember {
        runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
    }
    val appPluginsDir = remember(context) {
        runCatching {
            context.getExternalFilesDir("plugins")?.also { it.mkdirs() }
        }.getOrNull()
    }
    val downloadDisplayPath = remember(downloadDir) {
        downloadDir?.absolutePath ?: "/storage/emulated/0/Download"
    }
    val appDisplayPath = remember(appPluginsDir, context) {
        appPluginsDir?.absolutePath ?: "/storage/emulated/0/Android/data/${context.packageName}/files/plugins"
    }
    val downloadLabel = stringResource(R.string.file_install_dir_download)
    val appDirLabel = stringResource(R.string.file_install_dir_app)

    var refreshToken by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var scannedFiles by remember { mutableStateOf<List<LocalPluginFileEntry>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busyJob by remember { mutableStateOf<Job?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var showDirectUrlSheet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { busyJob?.cancel() }
    }

    LaunchedEffect(refreshToken, downloadDir, appPluginsDir) {
        scanning = true
        val results = withContext(Dispatchers.IO) {
            appPluginsDir?.let { runCatching { it.mkdirs() } }
            LocalPluginFileScanner.scanDirectories(
                listOf(
                    appDirLabel to appPluginsDir,
                    downloadLabel to downloadDir,
                ),
            )
        }
        scannedFiles = results
        scanning = false
    }

    fun startPrepareLocalFile(file: File) {
        if (busyJob?.isActive == true) return
        errorText = null
        busyMessage = context.getString(R.string.file_install_reading)
        busyJob = scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    PluginInstallGuard.prepareLocalFileInstall(
                        packageManager = model.pluginHost.packageManager(),
                        file = file,
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

    fun startPrepareDocumentUri(uri: Uri) {
        if (busyJob?.isActive == true) return
        errorText = null
        busyMessage = context.getString(R.string.file_install_reading)
        busyJob = scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val name = queryDocumentDisplayName(context.contentResolver, uri)
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "plugin.d3plugin"
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw PackageInstallException(context.getString(R.string.plugins_err_file_unreadable))
                    PluginInstallGuard.prepareLocalStreamInstall(
                        packageManager = model.pluginHost.packageManager(),
                        inputStream = stream,
                        displayName = name,
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

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            startPrepareDocumentUri(uri)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Header
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, { nav.back() }, filled = true)
        }
        Text(
            stringResource(R.string.file_install_title),
            style = WallType.Title,
            modifier = Modifier.offset(152.dp, 38.dp),
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WallButton(
                text = stringResource(R.string.file_install_refresh_btn),
                onClick = {
                    errorText = null
                    refreshToken++
                },
                icon = WallIcons.Refresh,
                kind = ButtonKind.Ghost,
            )
            WallButton(
                text = stringResource(R.string.file_install_direct_url_btn),
                onClick = {
                    errorText = null
                    showDirectUrlSheet = true
                },
                icon = WallIcons.Cloud,
                kind = ButtonKind.Plain,
            )
            WallButton(
                text = stringResource(R.string.file_install_pick_btn),
                onClick = {
                    errorText = null
                    try {
                        documentPicker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        errorText = context.getString(R.string.file_install_picker_unavailable)
                    } catch (_: Exception) {
                        errorText = context.getString(R.string.file_install_picker_unavailable)
                    }
                },
                icon = WallIcons.Folder,
                kind = ButtonKind.Primary,
            )
        }

        // Main content
        Column(
            Modifier.offset(64.dp, 120.dp).width(1152.dp).height(564.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Directory paths info card
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Wall.Radius))
                    .background(Wall.Surface)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.file_install_paths_title), style = WallType.Label)
                Text(
                    stringResource(R.string.file_install_path_download, downloadDisplayPath),
                    style = WallType.Caption,
                    color = Wall.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.file_install_path_app, appDisplayPath),
                    style = WallType.Caption,
                    color = Wall.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Text(
                        stringResource(R.string.file_install_scoped_hint),
                        style = WallType.Caption,
                        color = Wall.Text3,
                    )
                }
            }

            errorText?.let { err ->
                Notice(err, danger = true)
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    scanning -> {
                        item("scanning") {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                BreathingDot()
                                Text(
                                    stringResource(R.string.file_install_scanning),
                                    style = WallType.Body,
                                    color = Wall.Text2,
                                )
                            }
                        }
                    }
                    scannedFiles.isEmpty() -> {
                        item("empty") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Wall.Radius))
                                    .background(Wall.Surface)
                                    .padding(28.dp),
                            ) {
                                Text(
                                    stringResource(R.string.file_install_empty),
                                    style = WallType.Body,
                                    color = Wall.Text2,
                                )
                            }
                        }
                    }
                    else -> {
                        items(scannedFiles, key = { it.file.absolutePath }) { entry ->
                            val sizeText = LocalPluginFileScanner.formatFileSize(entry.sizeBytes)
                            val modText = if (entry.lastModifiedMs > 0L) {
                                dateFmt.format(Date(entry.lastModifiedMs))
                            } else {
                                ""
                            }
                            val sub = listOf(entry.directoryLabel, sizeText, modText)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                            ManagementRow(
                                icon = WallIcons.Cube,
                                title = entry.fileName,
                                sub = sub,
                                trailingAction = {
                                    WallButton(
                                        text = stringResource(R.string.plugins_repo_install),
                                        onClick = { startPrepareLocalFile(entry.file) },
                                        kind = ButtonKind.Plain,
                                        small = true,
                                    )
                                },
                                onClick = { startPrepareLocalFile(entry.file) },
                            )
                        }
                    }
                }
            }
        }

        if (showDirectUrlSheet) {
            PhoneInputSheet(
                mode = PhoneInputServer.Mode.PLUGIN_DIRECT_URL,
                lanIp = model.streaming.localLanIp(),
                initialValue = "https://",
                onSubmit = { entered ->
                    try {
                        val prepared = withContext(Dispatchers.IO) {
                            PluginInstallGuard.prepareDirectUrlInstall(
                                packageManager = model.pluginHost.packageManager(),
                                repoClient = model.pluginHost.repoClient,
                                rawUrl = entered,
                                localize = { b, p -> model.pluginHost.localizePreview(b, p) },
                            )
                        }
                        pendingInstall = prepared
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        UserFacingError(
                            context.getString(R.string.phone_action_direct_url),
                            PluginInstallGuard.formatInstallError(e, strings),
                        )
                    }
                },
                onDismiss = { showDirectUrlSheet = false },
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

private fun queryDocumentDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()
