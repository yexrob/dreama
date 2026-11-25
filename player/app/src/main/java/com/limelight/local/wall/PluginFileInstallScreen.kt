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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.limelight.R
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.plugin.PackageInstallException
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local file & direct HTTPS `.d3plugin` installer (`docs/specs/05-plugin-dev-install.md` §2):
 * - Scans `Android/data/<package>/files/plugins/` (needs zero storage permissions; universal
 *   `adb push` drop folder) and `Download/` on `Dispatchers.IO` (zero disk I/O in composition).
 * - On Android 10 (API <= 29), listing `Download/` requires `READ_EXTERNAL_STORAGE`: when not
 *   yet granted, surfaces an explicit 「允许访问」 action (and auto-rescans on resume) alongside
 *   「选择文件」.
 * - On Android 11+ (`targetSdk 36` Scoped Storage), silently ignores unreadable `Download/` and
 *   provides `ACTION_OPEN_DOCUMENT` as the universal system file picker.
 * - Also supports direct HTTPS `.d3plugin` URL install via [PhoneInputSheet] (`PLUGIN_DIRECT_URL`).
 * - Every path reads bounded bytes (<= 8 MB) ONCE during preview and installs strictly from those
 *   previewed bytes (bound to preview SHA-256) after user confirmation on [PluginInstallConfirmSheet].
 */
@Composable
fun PluginFileInstallScreen(model: WallModel, nav: WallNav) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val downloadLabel = stringResource(R.string.file_install_dir_download)
    val appDirLabel = stringResource(R.string.file_install_dir_app)

    var downloadDisplayPath by remember { mutableStateOf("/storage/emulated/0/Download") }
    var appDisplayPath by remember(context.packageName) {
        mutableStateOf("/storage/emulated/0/Android/data/${context.packageName}/files/plugins")
    }
    var hasLegacyStoragePerm by remember {
        mutableStateOf(Build.VERSION.SDK_INT > Build.VERSION_CODES.Q || LocalVideoSource.hasPermission(context))
    }

    var refreshToken by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var scannedFiles by remember { mutableStateOf<List<LocalPluginFileEntry>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val opRunner = remember { SingleFlightOpRunner() }
    var opBusy by remember { mutableStateOf(false) }
    var opCancelling by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var showDirectUrlSheet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { opRunner.cancel() }
    }

    // Re-check Android 10 storage permission and rescan when returning from permission dialog
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                val grantedNow = LocalVideoSource.hasPermission(context)
                if (grantedNow != hasLegacyStoragePerm) {
                    hasLegacyStoragePerm = grantedNow
                    refreshToken++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshToken) {
        scanning = true
        val scanResult = withContext(Dispatchers.IO) {
            val dlDir = runCatching {
                @Suppress("DEPRECATION")
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }.getOrNull()
            val appDir = runCatching {
                context.getExternalFilesDir("plugins")?.also { it.mkdirs() }
            }.getOrNull()
            val files = LocalPluginFileScanner.scanDirectories(
                listOf(
                    appDirLabel to appDir,
                    downloadLabel to dlDir,
                ),
            )
            Triple(dlDir?.absolutePath, appDir?.absolutePath, files)
        }
        scanResult.first?.let { downloadDisplayPath = it }
        scanResult.second?.let { appDisplayPath = it }
        scannedFiles = scanResult.third
        scanning = false
    }

    fun startPrepareLocalFile(file: File) {
        opRunner.tryLaunch(
            scope = scope,
            onStateChanged = { b, c ->
                opBusy = b
                opCancelling = c
                if (!b) busyMessage = null
            },
        ) {
            errorText = null
            busyMessage = context.getString(R.string.file_install_reading)
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
            }
        }
    }

    fun startPrepareDocumentUri(uri: Uri) {
        opRunner.tryLaunch(
            scope = scope,
            onStateChanged = { b, c ->
                opBusy = b
                opCancelling = c
                if (!b) busyMessage = null
            },
        ) {
            errorText = null
            busyMessage = context.getString(R.string.file_install_reading)
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
                    if (!opBusy) {
                        errorText = null
                        showDirectUrlSheet = true
                    }
                },
                icon = WallIcons.Cloud,
                kind = ButtonKind.Plain,
                enabled = !opBusy,
            )
            WallButton(
                text = stringResource(R.string.file_install_pick_btn),
                onClick = {
                    if (opBusy) return@WallButton
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
                enabled = !opBusy,
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
                } else if (!hasLegacyStoragePerm) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.file_install_storage_perm_hint),
                            style = WallType.Caption,
                            color = Wall.Accent,
                            modifier = Modifier.weight(1f),
                        )
                        WallButton(
                            text = stringResource(R.string.file_install_allow_access_btn),
                            onClick = { nav.storage() },
                            kind = ButtonKind.Plain,
                            small = true,
                        )
                    }
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
                                        enabled = !opBusy,
                                    )
                                },
                                onClick = { if (!opBusy) startPrepareLocalFile(entry.file) },
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

        if (opBusy) {
            BusyModal(
                message = if (opCancelling) {
                    stringResource(R.string.dev_cancelling_op)
                } else {
                    busyMessage ?: stringResource(R.string.file_install_reading)
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
