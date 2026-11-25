package com.limelight.local.wall

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.local.media.SourceKind
import com.limelight.local.plugin.ConfigField
import com.limelight.local.plugin.ConfigFieldType
import com.limelight.local.plugin.DeviceLoginStart
import com.limelight.local.plugin.LoginPollStatus
import com.limelight.local.plugin.PluginAuthMode
import com.limelight.local.plugin.PluginManifest
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.QrLoginStart
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.PhoneFormCopy
import com.limelight.local.stream.PhoneFormProgress
import com.limelight.local.stream.PhoneFormServer
import com.limelight.local.stream.PhoneSubmitResult
import com.limelight.local.stream.QrCodeMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private sealed interface InteractiveAuthStep {
    data class Qr(val tempInstanceId: String, val start: QrLoginStart, val status: LoginPollStatus) : InteractiveAuthStep
    data class Device(val tempInstanceId: String, val start: DeviceLoginStart, val status: LoginPollStatus) : InteractiveAuthStep
}

private sealed interface FormSubmitOutcome {
    data class Accepted(val onAfterFlushed: () -> Unit) : FormSubmitOutcome
    data class Rejected(val message: String) : FormSubmitOutcome
}

/**
 * Dynamic manifest-driven plugin configuration and authentication screen matching
 * `design-opus/mockups/sources.html#phone` + explicit headset keyboard fallback:
 * - Renders all 6 [ConfigFieldType]s (`url`, `text`, `password`, `number`, `select`, `toggle`).
 * - Defaults to [PhoneFormServer] QR + 4-digit code + live 「已填 n/m」 progress when typing fields exist.
 * - Provides explicit 「改用头显键盘」 fallback with masked password fields and `http://` warning.
 * - Authenticates BEFORE keeping a new instance; rolls back on failure/cancel, clears passwords,
 *   keeps non-password fields, and on success navigates back to Library selecting the new source.
 */
@Composable
fun PluginFormScreen(model: WallModel, nav: WallNav, page: Page.PluginForm) {
    val context = LocalContext.current
    val strings = remember(context) { AndroidStringLookup(context) }
    val scope = rememberCoroutineScope()

    val installedPlugins by model.installedPlugins.collectAsState()
    val instances by model.pluginInstances.collectAsState()
    val pkg = installedPlugins.firstOrNull { it.pluginId == page.pluginId }
    val manifest: PluginManifest? = remember(pkg?.pluginId, pkg?.version, pkg?.manifestJson) {
        pkg?.let { model.localizedManifestFor(it) }
    }
    val existingInst = page.instanceId?.let { id -> instances.firstOrNull { it.instanceId == id } }
    val isEditing = page.instanceId != null

    if (pkg == null || manifest == null) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.offset(64.dp, 32.dp)) { RoundButton(WallIcons.ChevL, { nav.back() }, filled = true) }
            Box(Modifier.offset(64.dp, 160.dp)) { Notice(stringResource(R.string.library_source_missing)) }
        }
        return
    }

    val lanIp = remember { model.streaming.localLanIp() }
    val hasTypingFields = remember(manifest) {
        manifest.config.any {
            it.type == ConfigFieldType.URL ||
                it.type == ConfigFieldType.TEXT ||
                it.type == ConfigFieldType.PASSWORD ||
                it.type == ConfigFieldType.NUMBER
        }
    }

    var configLoaded by remember(page) { mutableStateOf(!isEditing) }
    var existingConfig by remember(page) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    val fieldValues = remember(page) {
        mutableStateMapOf<String, String>().apply {
            putAll(
                PluginConfigFormCodec.initialValues(
                    manifest = manifest,
                    prefillServerUrl = page.prefillServerUrl,
                    suggestedName = page.suggestedName,
                ),
            )
        }
    }

    LaunchedEffect(page.instanceId, manifest.id) {
        if (page.instanceId != null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { model.pluginHost.packageManager().getInstanceConfig(page.instanceId) }.getOrDefault(emptyMap())
            }
            existingConfig = loaded
            fieldValues.clear()
            fieldValues.putAll(
                PluginConfigFormCodec.initialValues(
                    manifest = manifest,
                    existingConfig = loaded,
                    existingDisplayName = existingInst?.displayName.orEmpty(),
                ),
            )
            configLoaded = true
        }
    }

    var useHeadsetKeyboard by remember(page) { mutableStateOf(!hasTypingFields || lanIp.isNullOrBlank()) }
    var phoneProgress by remember(page) { mutableStateOf<PhoneFormProgress?>(null) }
    var phoneLockedOut by remember(page) { mutableStateOf(false) }
    var serverGen by remember(page) { mutableIntStateOf(0) }
    var busy by remember(page) { mutableStateOf(false) }
    var errorText by remember(page) { mutableStateOf<String?>(null) }
    var authStep by remember(page) { mutableStateOf<InteractiveAuthStep?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    val tempInstanceToRollback = remember(page) { AtomicReference<String?>(null) }
    val editBackupToRollback = remember(page) { AtomicReference<PluginInstanceAuthenticator.EditRollbackSnapshot?>(null) }

    fun rollbackOrphanInBackground(orphanId: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            withContext(NonCancellable) {
                runCatching { model.pluginHost.packageManager().deleteInstance(orphanId) }
                model.pluginHost.unmarkInstancePending(orphanId)
            }
        }
    }

    fun rollbackEditBackupInBackground(backup: PluginInstanceAuthenticator.EditRollbackSnapshot) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val pm = runCatching { model.pluginHost.packageManager() }.getOrNull() ?: return@launch
            PluginInstanceAuthenticator.restoreEditSnapshot(
                packageManager = pm,
                store = model.pluginHost.store,
                snapshot = backup,
            )
        }
    }

    // Cancel any active auth/poll coroutine and rollback temporary instance / unfinished edit on exit
    DisposableEffect(Unit) {
        onDispose {
            activeJob?.cancel()
            val orphan = tempInstanceToRollback.getAndSet(null)
            if (orphan != null && !isEditing) {
                rollbackOrphanInBackground(orphan)
            }
            val editBackup = editBackupToRollback.getAndSet(null)
            if (editBackup != null && isEditing) {
                rollbackEditBackupInBackground(editBackup)
            }
        }
    }

    fun startQrOrDevicePolling(instanceId: String, step: InteractiveAuthStep) {
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val source = withContext(Dispatchers.IO) { model.pluginHost.packageManager().openSource(instanceId) }
                val stateToken = when (step) {
                    is InteractiveAuthStep.Qr -> step.start.state
                    is InteractiveAuthStep.Device -> step.start.state
                }
                while (isActive) {
                    delay(2_000L)
                    val poll = withContext(Dispatchers.IO) { source.loginPoll(stateToken) }
                    when (poll.status) {
                        LoginPollStatus.PENDING, LoginPollStatus.SCANNED -> {
                            authStep = when (step) {
                                is InteractiveAuthStep.Qr -> step.copy(status = poll.status)
                                is InteractiveAuthStep.Device -> step.copy(status = poll.status)
                            }
                        }
                        LoginPollStatus.DONE -> {
                            tempInstanceToRollback.set(null)
                            editBackupToRollback.set(null)
                            model.pluginHost.unmarkInstancePending(instanceId)
                            authStep = null
                            busy = false
                            model.pluginHost.notifyChanged()
                            val display = existingInst?.displayName ?: fieldValues[PluginConfigFormCodec.DISPLAY_NAME_KEY].orEmpty()
                            nav.toast(
                                context.getString(
                                    if (isEditing) R.string.sources_updated_toast else R.string.sources_saved_toast,
                                    display.ifBlank { manifest.name },
                                ),
                            )
                            nav.openLibrarySelecting(PluginMediaSource.sourceIdFor(instanceId))
                            return@launch
                        }
                        LoginPollStatus.EXPIRED -> {
                            authStep = when (step) {
                                is InteractiveAuthStep.Qr -> step.copy(status = LoginPollStatus.EXPIRED)
                                is InteractiveAuthStep.Device -> step.copy(status = LoginPollStatus.EXPIRED)
                            }
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorText = SourceErrorDispatcher.dispatch(
                    error = e,
                    sourceKind = SourceKind.PLUGIN,
                    sourceName = manifest.name,
                    strings = strings,
                    needsPermissionReconfirm = pkg.needsPermissionReconfirm,
                ).message
            }
        }
    }

    suspend fun submitValues(submitted: Map<String, String>): FormSubmitOutcome {
        // Keep non-password values visible even if validation/auth fails; clear passwords on failure
        val validation = PluginConfigFormCodec.validateAndSerialize(
            manifest = manifest,
            values = submitted,
            strings = strings,
            existingConfig = existingConfig,
            isEditingExisting = isEditing,
        )
        if (validation is ConfigFormResult.Invalid) {
            return FormSubmitOutcome.Rejected(validation.message)
        }
        val valid = validation as ConfigFormResult.Valid

        return try {
            val pm = withContext(Dispatchers.IO) { model.pluginHost.packageManager() }
            if (manifest.auth == PluginAuthMode.QR || manifest.auth == PluginAuthMode.DEVICE) {
                val targetId = if (isEditing && page.instanceId != null) {
                    page.instanceId
                } else {
                    UUID.randomUUID().toString()
                }
                if (isEditing && page.instanceId != null) {
                    val prevInst = withContext(Dispatchers.IO) {
                        runCatching { model.pluginHost.store.getInstance(targetId) }.getOrNull()
                    }
                    val prevCfg = withContext(Dispatchers.IO) {
                        runCatching { pm.getInstanceConfig(targetId) }.getOrNull()
                    }
                    if (prevCfg != null) {
                        editBackupToRollback.set(
                            PluginInstanceAuthenticator.EditRollbackSnapshot(
                                instanceId = targetId,
                                prevDisplayName = prevInst?.displayName ?: existingInst?.displayName,
                                prevConfig = prevCfg,
                                prevAuthState = prevInst?.authState,
                                prevAuthUserJson = prevInst?.authUserJson,
                            ),
                        )
                    }
                } else {
                    tempInstanceToRollback.set(targetId)
                    model.pluginHost.markInstancePending(targetId)
                }

                try {
                    val inst = withContext(Dispatchers.IO) {
                        if (isEditing && page.instanceId != null) {
                            pm.updateInstanceConfig(
                                instanceId = targetId,
                                displayName = valid.displayName,
                                config = valid.config,
                            )
                        } else {
                            pm.createInstance(
                                pluginId = manifest.id,
                                displayName = valid.displayName,
                                config = valid.config,
                                instanceId = targetId,
                            )
                        }
                    }
                    val source = withContext(Dispatchers.IO) { pm.openSource(inst.instanceId) }
                    val step = withContext(Dispatchers.IO) {
                        if (manifest.auth == PluginAuthMode.QR) {
                            InteractiveAuthStep.Qr(inst.instanceId, source.loginQrStart(), LoginPollStatus.PENDING)
                        } else {
                            InteractiveAuthStep.Device(inst.instanceId, source.loginDeviceStart(), LoginPollStatus.PENDING)
                        }
                    }
                    FormSubmitOutcome.Accepted {
                        busy = false
                        authStep = step
                        startQrOrDevicePolling(inst.instanceId, step)
                    }
                } catch (t: Throwable) {
                    withContext(NonCancellable) {
                        if (!isEditing) {
                            tempInstanceToRollback.compareAndSet(targetId, null)
                            runCatching { pm.deleteInstance(targetId) }
                            model.pluginHost.unmarkInstancePending(targetId)
                        } else {
                            val backup = editBackupToRollback.getAndSet(null)
                            if (backup != null) {
                                PluginInstanceAuthenticator.restoreEditSnapshot(
                                    packageManager = pm,
                                    store = model.pluginHost.store,
                                    snapshot = backup,
                                )
                            }
                        }
                    }
                    throw t
                }
            } else {
                val savedInst = withContext(Dispatchers.IO) {
                    if (isEditing && page.instanceId != null) {
                        PluginInstanceAuthenticator.updateAndAuthenticateFormOrNone(
                            packageManager = pm,
                            manifest = manifest,
                            instanceId = page.instanceId,
                            displayName = valid.displayName,
                            config = valid.config,
                            store = model.pluginHost.store,
                            prevDisplayName = existingInst?.displayName,
                        )
                    } else {
                        PluginInstanceAuthenticator.createAndAuthenticateFormOrNone(
                            packageManager = pm,
                            manifest = manifest,
                            displayName = valid.displayName,
                            config = valid.config,
                            onInstanceCreated = { id ->
                                tempInstanceToRollback.set(id)
                                model.pluginHost.markInstancePending(id)
                            },
                            onInstanceSettled = { id ->
                                tempInstanceToRollback.compareAndSet(id, null)
                                model.pluginHost.unmarkInstancePending(id)
                            },
                        )
                    }
                }
                model.pluginHost.notifyChanged()
                FormSubmitOutcome.Accepted {
                    busy = false
                    nav.toast(
                        context.getString(
                            if (isEditing) R.string.sources_updated_toast else R.string.sources_saved_toast,
                            savedInst.displayName,
                        ),
                    )
                    nav.openLibrarySelecting(PluginMediaSource.sourceIdFor(savedInst.instanceId))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cleaned = PluginConfigFormCodec.clearPasswordValues(manifest, submitted)
            withContext(Dispatchers.Main.immediate) {
                fieldValues.clear()
                fieldValues.putAll(cleaned)
            }
            FormSubmitOutcome.Rejected(
                SourceErrorDispatcher.dispatch(
                    error = e,
                    sourceKind = SourceKind.PLUGIN,
                    sourceName = manifest.name,
                    strings = strings,
                    needsPermissionReconfirm = pkg.needsPermissionReconfirm,
                ).message,
            )
        }
    }

    // Phone Form Server lifecycle (runs only in Phone Input mode when config is loaded and not in QR/Device poll step)
    var serverInfo by remember { mutableStateOf<Triple<String, String, QrCodeMatrix>?>(null) }
    DisposableEffect(configLoaded, useHeadsetKeyboard, lanIp, serverGen, authStep == null) {
        if (!configLoaded || useHeadsetKeyboard || lanIp.isNullOrBlank() || authStep != null) {
            serverInfo = null
            onDispose { }
        } else {
            val phoneFields = PluginConfigFormCodec.toPhoneFields(
                manifest = manifest,
                currentValues = fieldValues.toMap(),
                isEditingExisting = isEditing,
                strings = strings,
            )
            val copy = PhoneFormCopy.from(context, manifest.name)
            val pendingPostFlush = AtomicReference<(() -> Unit)?>(null)
            val server = try {
                PhoneFormServer(
                    copy = copy,
                    fields = phoneFields,
                    displayHost = lanIp,
                    onProgress = { prog ->
                        scope.launch(Dispatchers.Main.immediate) { phoneProgress = prog }
                    },
                    onLockedOut = {
                        scope.launch(Dispatchers.Main.immediate) {
                            phoneLockedOut = true
                            serverInfo = null
                        }
                    },
                    onAcceptedFlushed = {
                        // Navigate or switch to QR/Device auth strictly AFTER the 200 OK HTML
                        // finishes flushing to the phone so page teardown never cuts off the response (#18).
                        val action = pendingPostFlush.getAndSet(null)
                        if (action != null) {
                            scope.launch(Dispatchers.Main.immediate) { action() }
                        }
                    },
                    onSubmitted = { valuesFromPhone ->
                        try {
                            runBlocking {
                                val alreadyBusy = withContext(Dispatchers.Main.immediate) {
                                    if (busy) {
                                        true
                                    } else {
                                        busy = true
                                        errorText = null
                                        false
                                    }
                                }
                                if (alreadyBusy) {
                                    return@runBlocking PhoneSubmitResult.Rejected(
                                        context.getString(R.string.plugin_form_verifying),
                                    )
                                }
                                var errMsg: String? = null
                                try {
                                    val outcome = withTimeoutOrNull(30_000L) {
                                        submitValues(valuesFromPhone)
                                    }
                                    when (outcome) {
                                        is FormSubmitOutcome.Accepted -> {
                                            pendingPostFlush.set(outcome.onAfterFlushed)
                                            errMsg = null
                                        }
                                        is FormSubmitOutcome.Rejected -> {
                                            errMsg = outcome.message
                                        }
                                        null -> {
                                            errMsg = context.getString(R.string.plugin_error_unavailable)
                                        }
                                    }
                                } finally {
                                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                                        busy = false
                                        errorText = errMsg
                                    }
                                }
                                if (errMsg == null) PhoneSubmitResult.Accepted
                                else PhoneSubmitResult.Rejected(errMsg!!)
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            scope.launch(Dispatchers.Main.immediate) { busy = false }
                            PhoneSubmitResult.Rejected(context.getString(R.string.action_cancel))
                        }
                    },
                ).start()
            } catch (e: Exception) {
                LimeLog.warning("depth3d: phone form server start failed: ${e.javaClass.simpleName}")
                null
            }
            if (server != null) {
                phoneLockedOut = false
                phoneProgress = null
                serverInfo = Triple(server.shortUrl, server.verificationCode, QrCodeMatrix.encode(server.qrUrl))
            } else {
                serverInfo = null
            }
            onDispose {
                server?.close()
            }
        }
    }

    val screenTitle = stringResource(
        when {
            page.reloginOnly -> R.string.plugin_form_relogin_title
            else -> R.string.plugin_form_headset_title
        },
        manifest.name,
    )

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(64.dp, 32.dp)) {
            RoundButton(WallIcons.ChevL, {
                activeJob?.cancel()
                nav.back()
            }, filled = true)
        }
        Text(screenTitle, style = WallType.Title, modifier = Modifier.offset(152.dp, 38.dp))

        val curAuth = authStep
        when {
            curAuth != null -> {
                InteractivePluginAuthCard(
                    pluginName = manifest.name,
                    step = curAuth,
                    errorText = errorText,
                    onRefresh = {
                        val instId = when (curAuth) {
                            is InteractiveAuthStep.Qr -> curAuth.tempInstanceId
                            is InteractiveAuthStep.Device -> curAuth.tempInstanceId
                        }
                        scope.launch {
                            try {
                                val src = withContext(Dispatchers.IO) { model.pluginHost.packageManager().openSource(instId) }
                                val nextStep = withContext(Dispatchers.IO) {
                                    if (curAuth is InteractiveAuthStep.Qr) {
                                        InteractiveAuthStep.Qr(instId, src.loginQrStart(), LoginPollStatus.PENDING)
                                    } else {
                                        InteractiveAuthStep.Device(instId, src.loginDeviceStart(), LoginPollStatus.PENDING)
                                    }
                                }
                                authStep = nextStep
                                startQrOrDevicePolling(instId, nextStep)
                            } catch (e: Exception) {
                                errorText = SourceErrorDispatcher.dispatch(
                                    error = e,
                                    sourceKind = SourceKind.PLUGIN,
                                    sourceName = manifest.name,
                                    strings = strings,
                                    needsPermissionReconfirm = pkg.needsPermissionReconfirm,
                                ).message
                            }
                        }
                    },
                    onCancel = {
                        activeJob?.cancel()
                        authStep = null
                        busy = false
                        val orphan = tempInstanceToRollback.getAndSet(null)
                        if (orphan != null && !isEditing) {
                            rollbackOrphanInBackground(orphan)
                        }
                        val editBackup = editBackupToRollback.getAndSet(null)
                        if (editBackup != null && isEditing) {
                            rollbackEditBackupInBackground(editBackup)
                        }
                    },
                )
            }

            !useHeadsetKeyboard -> {
                // Phone Form Mode card matching design-opus/mockups/sources.html#phone
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier
                            .width(960.dp)
                            .shadow(32.dp, RoundedCornerShape(Wall.RadiusXl))
                            .clip(RoundedCornerShape(Wall.RadiusXl))
                            .background(Wall.Sheet)
                            .padding(horizontal = 48.dp, vertical = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        val info = serverInfo
                        if (info != null && !phoneLockedOut) {
                            QrCodeView(info.third, size = 232.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.plugin_form_phone_title, manifest.name), style = WallType.Head)
                            Text(
                                stringResource(R.string.plugin_form_phone_sub),
                                style = WallType.Body,
                                color = Wall.Text2,
                                modifier = Modifier.padding(top = 8.dp),
                            )

                            when {
                                phoneLockedOut -> {
                                    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Notice(stringResource(R.string.phone_input_locked), danger = true)
                                        WallButton(
                                            text = stringResource(R.string.phone_input_reopen_qr),
                                            onClick = {
                                                phoneLockedOut = false
                                                errorText = null
                                                serverGen++
                                            },
                                            icon = WallIcons.Refresh,
                                            kind = ButtonKind.Primary,
                                            small = true,
                                        )
                                    }
                                }
                                busy -> {
                                    Row(
                                        Modifier.padding(top = 24.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        WallSpinner(size = 26.dp)
                                        Text(stringResource(R.string.plugin_form_verifying), style = WallType.Caption, color = Wall.Text2)
                                    }
                                }
                                info != null -> {
                                    val prog = phoneProgress
                                    Row(
                                        Modifier.padding(top = 24.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        BreathingDot()
                                        Text(
                                            if (prog != null) {
                                                stringResource(R.string.plugin_form_phone_progress, prog.filledCount, prog.totalCount)
                                            } else {
                                                stringResource(R.string.plugin_form_waiting_phone)
                                            },
                                            style = WallType.Caption,
                                            color = Wall.Text2,
                                        )
                                    }
                                    Text(
                                        stringResource(R.string.plugin_form_qr_fallback_hint),
                                        style = WallType.Caption,
                                        color = Wall.Text2,
                                        modifier = Modifier.padding(top = 22.dp),
                                    )
                                    Text(
                                        stringResource(R.string.plugin_form_url_and_code, info.first.removePrefix("http://"), info.second),
                                        style = WallType.Body.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                                        color = Wall.Text,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                else -> {
                                    Box(Modifier.padding(top = 24.dp)) {
                                        Notice(stringResource(R.string.phone_input_no_wifi), WallIcons.Wifi, danger = false)
                                    }
                                }
                            }

                            errorText?.let { err ->
                                Box(Modifier.padding(top = 18.dp)) { Notice(err, danger = true) }
                            }

                            Row(
                                Modifier.padding(top = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                WallButton(
                                    text = stringResource(R.string.phone_input_use_headset_kb),
                                    onClick = {
                                        if (!busy) {
                                            errorText = null
                                            useHeadsetKeyboard = true
                                        }
                                    },
                                    icon = WallIcons.Keyboard,
                                    enabled = !busy,
                                )
                                WallButton(
                                    text = stringResource(R.string.action_cancel),
                                    onClick = {
                                        activeJob?.cancel()
                                        nav.back()
                                    },
                                    kind = ButtonKind.Ghost,
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                // Headset keyboard fallback rendering ALL 6 ConfigFieldTypes natively
                val showHttpWarn = PluginConfigFormCodec.hasCleartextHttpUrl(manifest, fieldValues)
                LazyColumn(
                    Modifier.offset(64.dp, 120.dp).width(920.dp).height(572.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item("display-name") {
                        HeadsetTextFieldRow(
                            label = stringResource(R.string.phone_form_display_name_label),
                            value = fieldValues[PluginConfigFormCodec.DISPLAY_NAME_KEY].orEmpty(),
                            placeholder = stringResource(R.string.phone_form_display_name_placeholder),
                            keyboardType = KeyboardType.Text,
                            isPassword = false,
                            onChange = { fieldValues[PluginConfigFormCodec.DISPLAY_NAME_KEY] = it },
                        )
                    }

                    for (field in manifest.config) {
                        item("field:${field.key}") {
                            DynamicConfigFieldRow(
                                field = field,
                                value = fieldValues[field.key].orEmpty(),
                                isEditing = isEditing,
                                onChange = { fieldValues[field.key] = it },
                            )
                        }
                    }

                    if (showHttpWarn) {
                        item("http-warn") {
                            Notice(stringResource(R.string.phone_form_err_http_warn), danger = false)
                        }
                    }

                    errorText?.let { err ->
                        item("err") { Notice(err, danger = true) }
                    }

                    item("submit-row") {
                        Row(
                            Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WallButton(
                                text = stringResource(if (busy) R.string.plugin_form_verifying else R.string.plugin_form_submit_btn),
                                onClick = {
                                    if (busy) return@WallButton
                                    busy = true
                                    errorText = null
                                    val snapshot = fieldValues.toMap()
                                    activeJob = scope.launch {
                                        PluginInstanceAuthenticator.runWithBusyReset(
                                            setBusy = { b -> withContext(Dispatchers.Main.immediate) { busy = b } },
                                        ) {
                                            when (val outcome = submitValues(snapshot)) {
                                                is FormSubmitOutcome.Accepted -> {
                                                    errorText = null
                                                    outcome.onAfterFlushed()
                                                }
                                                is FormSubmitOutcome.Rejected -> {
                                                    errorText = outcome.message
                                                }
                                            }
                                        }
                                    }
                                },
                                kind = ButtonKind.Primary,
                                enabled = !busy,
                            )
                            if (hasTypingFields && !lanIp.isNullOrBlank()) {
                                WallButton(
                                    text = stringResource(R.string.phone_input_back_to_qr),
                                    onClick = {
                                        if (!busy) {
                                            errorText = null
                                            useHeadsetKeyboard = false
                                        }
                                    },
                                    enabled = !busy,
                                )
                            }
                            WallButton(
                                text = stringResource(R.string.action_cancel),
                                onClick = {
                                    activeJob?.cancel()
                                    busy = false
                                    nav.back()
                                },
                                kind = ButtonKind.Ghost,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicConfigFieldRow(
    field: ConfigField,
    value: String,
    isEditing: Boolean,
    onChange: (String) -> Unit,
) {
    when (field.type) {
        ConfigFieldType.URL -> HeadsetTextFieldRow(
            label = field.label,
            value = value,
            placeholder = field.placeholder.orEmpty(),
            keyboardType = KeyboardType.Uri,
            isPassword = false,
            onChange = onChange,
        )
        ConfigFieldType.TEXT -> HeadsetTextFieldRow(
            label = field.label,
            value = value,
            placeholder = field.placeholder.orEmpty(),
            keyboardType = KeyboardType.Text,
            isPassword = false,
            onChange = onChange,
        )
        ConfigFieldType.PASSWORD -> HeadsetTextFieldRow(
            label = field.label,
            value = value,
            placeholder = if (isEditing) stringResource(R.string.phone_form_password_keep_hint)
            else field.placeholder ?: stringResource(R.string.phone_form_password_placeholder),
            keyboardType = KeyboardType.Password,
            isPassword = true,
            onChange = onChange,
        )
        ConfigFieldType.NUMBER -> HeadsetTextFieldRow(
            label = field.label,
            value = value,
            placeholder = field.placeholder.orEmpty(),
            keyboardType = KeyboardType.Number,
            isPassword = false,
            onChange = onChange,
        )
        ConfigFieldType.SELECT -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(field.label, style = WallType.Caption, color = Wall.Text2)
                SegmentedControl(
                    options = field.options.map { it.value to it.label },
                    selected = value,
                    onSelect = onChange,
                )
            }
        }
        ConfigFieldType.TOGGLE -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Wall.Radius))
                    .background(Wall.Surface)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(field.label, style = WallType.Label, modifier = Modifier.weight(1f))
                WallSwitch(checked = value.equals("true", ignoreCase = true)) {
                    onChange(it.toString())
                }
            }
        }
    }
}

@Composable
private fun HeadsetTextFieldRow(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    isPassword: Boolean,
    onChange: (String) -> Unit,
) {
    Column {
        Text(label, style = WallType.Caption, color = Wall.Text2)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = WallType.Body,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(Wall.Accent),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(Wall.Radius))
                        .background(Wall.Surface)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = WallType.Body, color = Wall.Text3)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun InteractivePluginAuthCard(
    pluginName: String,
    step: InteractiveAuthStep,
    errorText: String?,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .width(920.dp)
                .shadow(32.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .padding(horizontal = 48.dp, vertical = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (step) {
                is InteractiveAuthStep.Qr -> {
                    val qrMatrix = remember(step.start.qr) { QrCodeMatrix.encodeOrNull(step.start.qr) }
                    if (qrMatrix != null) {
                        QrCodeView(qrMatrix, size = 232.dp)
                    } else {
                        Text(
                            stringResource(R.string.plugin_form_qr_too_long),
                            style = WallType.Body,
                            color = Wall.Text2,
                            modifier = Modifier.width(232.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.plugin_form_qr_login_title, pluginName), style = WallType.Head)
                        step.start.hint?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = WallType.Body, color = Wall.Text2)
                        }
                        AuthPollStatusRow(step.status)
                        errorText?.let { Notice(it, danger = true) }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (step.status == LoginPollStatus.EXPIRED) {
                                WallButton(stringResource(R.string.plugin_form_refresh_qr), onRefresh, icon = WallIcons.Refresh, kind = ButtonKind.Primary)
                            }
                            WallButton(stringResource(R.string.action_cancel), onCancel, kind = ButtonKind.Ghost)
                        }
                    }
                }
                is InteractiveAuthStep.Device -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.plugin_form_qr_login_title, pluginName), style = WallType.Head)
                        Text(
                            stringResource(R.string.plugin_form_device_open_url, step.start.url),
                            style = WallType.Body,
                            color = Wall.Text2,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(stringResource(R.string.plugin_form_device_code_label), style = WallType.Caption, color = Wall.Text2)
                            Text(
                                step.start.code,
                                style = WallType.Title.copy(fontSize = 40.sp, letterSpacing = 6.sp, fontFeatureSettings = "tnum"),
                                color = Wall.Accent,
                            )
                        }
                        AuthPollStatusRow(step.status)
                        errorText?.let { Notice(it, danger = true) }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (step.status == LoginPollStatus.EXPIRED) {
                                WallButton(stringResource(R.string.plugin_form_refresh_qr), onRefresh, icon = WallIcons.Refresh, kind = ButtonKind.Primary)
                            }
                            WallButton(stringResource(R.string.action_cancel), onCancel, kind = ButtonKind.Ghost)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthPollStatusRow(status: LoginPollStatus) {
    when (status) {
        LoginPollStatus.PENDING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BreathingDot()
            Text(stringResource(R.string.plugin_form_qr_login_pending), style = WallType.Caption, color = Wall.Text2)
        }
        LoginPollStatus.SCANNED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WallSpinner(size = 24.dp)
            Text(stringResource(R.string.plugin_form_qr_login_scanned), style = WallType.Caption, color = Wall.Accent)
        }
        LoginPollStatus.EXPIRED -> Notice(stringResource(R.string.plugin_form_qr_login_expired), danger = true)
        LoginPollStatus.DONE -> Unit
    }
}
