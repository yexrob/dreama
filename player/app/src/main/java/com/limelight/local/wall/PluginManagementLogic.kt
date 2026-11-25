package com.limelight.local.wall

import com.limelight.R
import com.limelight.local.media.SourceKind
import com.limelight.local.plugin.ConfigField
import com.limelight.local.plugin.ConfigFieldType
import com.limelight.local.plugin.InstallResult
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.ManifestValidationException
import com.limelight.local.plugin.NetworkPolicyException
import com.limelight.local.plugin.PackageInstallException
import com.limelight.local.plugin.PluginAuthMode
import com.limelight.local.plugin.PluginInstallPreview
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginManifest
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginPackageArchive
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.PluginPermissions
import com.limelight.local.plugin.PluginRepoEntity
import com.limelight.local.plugin.PluginRepoIndex
import com.limelight.local.plugin.PluginRepositoryClient
import com.limelight.local.plugin.PluginSourceMismatchException
import com.limelight.local.plugin.PluginStore
import com.limelight.local.plugin.RepoPluginEntry
import com.limelight.local.plugin.RepositoryVerificationException
import com.limelight.local.plugin.SemVer
import com.limelight.local.stream.PhoneFormField
import com.limelight.local.stream.PhoneFormFieldKind
import com.limelight.local.stream.PhoneFormOption
import com.limelight.local.stream.StringLookup
import com.limelight.local.stream.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID

/** Validation + serialization result for a dynamic manifest config form. */
sealed interface ConfigFormResult {
    data class Valid(
        val displayName: String,
        val config: Map<String, JsonElement>,
    ) : ConfigFormResult

    data class Invalid(val message: String) : ConfigFormResult
}

/**
 * Pure, JVM-testable codec bridging `PluginManifest.config` (all 6 [ConfigFieldType]s),
 * [PhoneFormField] definitions, form validation, `http://` cleartext detection (§11.3),
 * and password stripping/preservation.
 */
object PluginConfigFormCodec {
    const val DISPLAY_NAME_KEY = "__display_name__"

    fun initialValues(
        manifest: PluginManifest,
        existingConfig: Map<String, JsonElement> = emptyMap(),
        existingDisplayName: String = "",
        prefillServerUrl: String? = null,
        suggestedName: String? = null,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[DISPLAY_NAME_KEY] = existingDisplayName.ifBlank { suggestedName.orEmpty() }
        var prefillUsed = false
        for (field in manifest.config) {
            if (field.type == ConfigFieldType.PASSWORD) {
                // Never prefill passwords into UI or HTML forms
                out[field.key] = ""
                continue
            }
            val existing = (existingConfig[field.key] as? JsonPrimitive)?.contentOrNull
            val defaultStr = (field.defaultValue as? JsonPrimitive)?.contentOrNull
            val prefill = if (!prefillUsed && field.type == ConfigFieldType.URL && !prefillServerUrl.isNullOrBlank()) {
                prefillUsed = true
                prefillServerUrl
            } else {
                null
            }
            out[field.key] = when (field.type) {
                ConfigFieldType.SELECT -> existing ?: defaultStr ?: field.options.firstOrNull()?.value.orEmpty()
                ConfigFieldType.TOGGLE -> {
                    val boolVal = (existingConfig[field.key] as? JsonPrimitive)?.booleanOrNull
                        ?: (field.defaultValue as? JsonPrimitive)?.booleanOrNull
                        ?: false
                    boolVal.toString()
                }
                else -> prefill ?: existing ?: defaultStr.orEmpty()
            }
        }
        return out
    }

    fun toPhoneFields(
        manifest: PluginManifest,
        currentValues: Map<String, String>,
        isEditingExisting: Boolean,
        strings: StringLookup,
    ): List<PhoneFormField> {
        val out = ArrayList<PhoneFormField>(manifest.config.size + 1)
        out += PhoneFormField(
            key = DISPLAY_NAME_KEY,
            kind = PhoneFormFieldKind.TEXT,
            label = strings.get(R.string.phone_form_display_name_label),
            required = false,
            placeholder = strings.get(R.string.phone_form_display_name_placeholder),
            initialValue = currentValues[DISPLAY_NAME_KEY].orEmpty(),
        )
        for (field in manifest.config) {
            val kind = when (field.type) {
                ConfigFieldType.URL -> PhoneFormFieldKind.URL
                ConfigFieldType.TEXT -> PhoneFormFieldKind.TEXT
                ConfigFieldType.PASSWORD -> PhoneFormFieldKind.PASSWORD
                ConfigFieldType.NUMBER -> PhoneFormFieldKind.NUMBER
                ConfigFieldType.SELECT -> PhoneFormFieldKind.SELECT
                ConfigFieldType.TOGGLE -> PhoneFormFieldKind.TOGGLE
            }
            val placeholder = if (field.type == ConfigFieldType.PASSWORD && isEditingExisting) {
                strings.get(R.string.phone_form_password_keep_hint)
            } else {
                field.placeholder
            }
            out += PhoneFormField(
                key = field.key,
                kind = kind,
                label = field.label,
                required = field.required && !(field.type == ConfigFieldType.PASSWORD && isEditingExisting),
                placeholder = placeholder,
                initialValue = if (field.type == ConfigFieldType.PASSWORD) "" else currentValues[field.key].orEmpty(),
                options = field.options.map { PhoneFormOption(it.value, it.label) },
            )
        }
        return out
    }

    fun clearPasswordValues(manifest: PluginManifest, values: Map<String, String>): Map<String, String> {
        val passwordKeys = manifest.passwordConfigKeys()
        return values.mapValues { (k, v) -> if (k in passwordKeys) "" else v }
    }

    fun hasCleartextHttpUrl(manifest: PluginManifest, values: Map<String, String>): Boolean =
        manifest.config.any { field ->
            field.type == ConfigFieldType.URL &&
                values[field.key]?.trim()?.lowercase(Locale.ROOT)?.startsWith("http://") == true
        }

    fun validateAndSerialize(
        manifest: PluginManifest,
        values: Map<String, String>,
        strings: StringLookup,
        existingConfig: Map<String, JsonElement> = emptyMap(),
        isEditingExisting: Boolean = false,
    ): ConfigFormResult {
        val result = LinkedHashMap<String, JsonElement>()
        var fallbackHostName: String? = null

        for (field in manifest.config) {
            val raw = values[field.key].orEmpty()
            val trimmed = if (field.type == ConfigFieldType.PASSWORD) raw else raw.trim()

            when (field.type) {
                ConfigFieldType.URL -> {
                    if (trimmed.isEmpty()) {
                        if (field.required) return ConfigFormResult.Invalid(strings.get(R.string.phone_form_err_required))
                        continue
                    }
                    val parsed = parseHttpUri(trimmed)
                        ?: return ConfigFormResult.Invalid(strings.get(R.string.phone_form_err_invalid_url))
                    if (fallbackHostName == null) fallbackHostName = parsed.host
                    result[field.key] = JsonPrimitive(trimmed.trimEnd('/'))
                }
                ConfigFieldType.TEXT -> {
                    if (trimmed.isEmpty()) {
                        if (field.required) return ConfigFormResult.Invalid(strings.get(R.string.phone_form_err_required))
                        continue
                    }
                    result[field.key] = JsonPrimitive(trimmed)
                }
                ConfigFieldType.PASSWORD -> {
                    if (trimmed.isEmpty()) {
                        val kept = existingConfig[field.key]
                        if (isEditingExisting && kept != null) {
                            result[field.key] = kept
                        } else if (field.required) {
                            return ConfigFormResult.Invalid(strings.get(R.string.phone_form_err_required))
                        } else {
                            result[field.key] = JsonPrimitive("")
                        }
                    } else {
                        result[field.key] = JsonPrimitive(trimmed)
                    }
                }
                ConfigFieldType.NUMBER -> {
                    if (trimmed.isEmpty()) {
                        if (field.required) return ConfigFormResult.Invalid(strings.get(R.string.phone_form_err_required))
                        continue
                    }
                    val num = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: return ConfigFormResult.Invalid(strings.get(R.string.phone_form_err_invalid_number))
                    val asLong = num.toLong()
                    result[field.key] = if (asLong.toDouble() == num) JsonPrimitive(asLong) else JsonPrimitive(num)
                }
                ConfigFieldType.SELECT -> {
                    val chosen = trimmed.ifEmpty {
                        (field.defaultValue as? JsonPrimitive)?.contentOrNull
                            ?: field.options.firstOrNull()?.value.orEmpty()
                    }
                    if (field.options.isNotEmpty() && field.options.none { it.value == chosen }) {
                        return ConfigFormResult.Invalid(strings.get(R.string.phone_form_err_required))
                    }
                    if (chosen.isNotEmpty()) {
                        result[field.key] = JsonPrimitive(chosen)
                    }
                }
                ConfigFieldType.TOGGLE -> {
                    val b = trimmed.equals("true", ignoreCase = true)
                    result[field.key] = JsonPrimitive(b)
                }
            }
        }

        val explicitName = values[DISPLAY_NAME_KEY]?.trim().orEmpty()
        val displayName = when {
            explicitName.isNotEmpty() -> explicitName
            !fallbackHostName.isNullOrBlank() -> "${manifest.name} ($fallbackHostName)"
            else -> manifest.name
        }
        return ConfigFormResult.Valid(displayName = displayName, config = result)
    }

    private fun parseHttpUri(raw: String): URI? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }
}

/**
 * Transactional create/update + authenticate helper (`04-plugin-ui.md` Stage 4):
 * Creating a new instance authenticates BEFORE keeping it — any failure or cancellation
 * rolls back (`deleteInstance`) so no broken ghost instance ever appears on the wall.
 */
object PluginInstanceAuthenticator {
    suspend fun createAndAuthenticateFormOrNone(
        packageManager: PluginPackageManager,
        manifest: PluginManifest,
        displayName: String,
        config: Map<String, JsonElement>,
        dnsResolver: ((String) -> List<java.net.InetAddress>)? = null,
        onInstanceCreated: (String) -> Unit = {},
        onInstanceSettled: (String) -> Unit = {},
    ): PluginInstanceEntity {
        val instanceId = UUID.randomUUID().toString()
        onInstanceCreated(instanceId)
        try {
            val created = packageManager.createInstance(
                pluginId = manifest.id,
                displayName = displayName,
                config = config,
                instanceId = instanceId,
            )
            val source = packageManager.openSource(created.instanceId, dnsResolver = dnsResolver)
            when (manifest.auth) {
                PluginAuthMode.FORM -> source.loginForm()
                PluginAuthMode.NONE -> source.home()
                PluginAuthMode.QR, PluginAuthMode.DEVICE -> Unit
            }
            return created
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching { packageManager.deleteInstance(instanceId) }
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                onInstanceSettled(instanceId)
            }
        }
    }

    /** Snapshot of an existing plugin instance prior to edit, restored on any failure or cancellation. */
    data class EditRollbackSnapshot(
        val instanceId: String,
        val prevDisplayName: String?,
        val prevConfig: Map<String, JsonElement>,
        val prevAuthState: String?,
        val prevAuthUserJson: String?,
    )

    suspend fun restoreEditSnapshot(
        packageManager: PluginPackageManager,
        store: PluginStore?,
        snapshot: EditRollbackSnapshot,
        dnsResolver: ((String) -> List<java.net.InetAddress>)? = null,
    ) {
        withContext(NonCancellable) {
            runCatching {
                packageManager.updateInstanceConfig(
                    instanceId = snapshot.instanceId,
                    displayName = snapshot.prevDisplayName,
                    config = snapshot.prevConfig,
                )
            }
            if (snapshot.prevAuthState != null && store != null) {
                runCatching {
                    store.updateInstanceAuth(
                        instanceId = snapshot.instanceId,
                        authState = snapshot.prevAuthState,
                        authUserJson = snapshot.prevAuthUserJson,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            // Restore runtime bound to the previous working config
            runCatching {
                packageManager.openSource(snapshot.instanceId, dnsResolver = dnsResolver)
            }
        }
    }

    suspend fun updateAndAuthenticateFormOrNone(
        packageManager: PluginPackageManager,
        manifest: PluginManifest,
        instanceId: String,
        displayName: String,
        config: Map<String, JsonElement>,
        dnsResolver: ((String) -> List<java.net.InetAddress>)? = null,
        store: PluginStore? = null,
        prevDisplayName: String? = null,
    ): PluginInstanceEntity {
        val prevInst = runCatching { store?.getInstance(instanceId) }.getOrNull()
        val prevConfig = runCatching { packageManager.getInstanceConfig(instanceId) }.getOrNull()
        val snapshot = prevConfig?.let {
            EditRollbackSnapshot(
                instanceId = instanceId,
                prevDisplayName = prevInst?.displayName ?: prevDisplayName,
                prevConfig = it,
                prevAuthState = prevInst?.authState,
                prevAuthUserJson = prevInst?.authUserJson,
            )
        }
        val updated = packageManager.updateInstanceConfig(
            instanceId = instanceId,
            displayName = displayName,
            config = config,
        )
        try {
            val source = packageManager.openSource(instanceId, dnsResolver = dnsResolver)
            when (manifest.auth) {
                PluginAuthMode.FORM -> source.loginForm()
                PluginAuthMode.NONE -> source.home()
                PluginAuthMode.QR, PluginAuthMode.DEVICE -> Unit
            }
            return updated
        } catch (e: Throwable) {
            if (snapshot != null) {
                restoreEditSnapshot(
                    packageManager = packageManager,
                    store = store,
                    snapshot = snapshot,
                    dnsResolver = dnsResolver,
                )
            }
            throw e
        }
    }

    /**
     * Runs a form submission body (FORM/NONE/QR/DEVICE) while guaranteeing [setBusy] resets to
     * `false` on EVERY exit path (success, QR/DEVICE transition, validation error, network error,
     * timeout, and cancellation) inside [NonCancellable].
     */
    suspend fun <T> runWithBusyReset(
        setBusy: suspend (Boolean) -> Unit,
        block: suspend () -> T,
    ): T {
        setBusy(true)
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                setBusy(false)
            }
        }
    }
}

/** Human-readable Simplified Chinese summaries of plugin permissions, sources, and updates. */
object PluginPermissionSummary {
    fun summarizeOneLine(permissions: PluginPermissions, strings: StringLookup): String {
        val parts = ArrayList<String>()
        if (permissions.allowsWildcardLanAccess) {
            parts += strings.get(R.string.plugins_perm_wildcard_lan)
        } else if (permissions.lan) {
            parts += strings.get(R.string.plugins_perm_lan)
        }
        if (permissions.hosts.isNotEmpty()) {
            parts += strings.get(R.string.plugins_perm_hosts, permissions.hosts.take(2).joinToString("、"))
        } else if (permissions.userHosts && !permissions.lan) {
            parts += strings.get(R.string.plugins_perm_user_hosts)
        }
        return if (parts.isEmpty()) strings.get(R.string.plugins_perm_none) else parts.joinToString(" · ")
    }

    fun detailLines(permissions: PluginPermissions, strings: StringLookup): List<String> {
        val out = ArrayList<String>()
        if (permissions.userHosts) {
            out += strings.get(R.string.plugins_perm_user_hosts)
        }
        if (permissions.lan) {
            out += strings.get(R.string.plugins_perm_lan)
        }
        if (permissions.hosts.isNotEmpty()) {
            out += strings.get(R.string.plugins_perm_hosts, permissions.hosts.joinToString("、"))
        }
        if (out.isEmpty()) {
            out += strings.get(R.string.plugins_perm_none)
        }
        return out
    }

    fun sourceLabel(
        entity: InstalledPluginEntity,
        reposByUrl: Map<String, PluginRepoEntity>,
        strings: StringLookup,
    ): String {
        if (BuiltInPluginInstaller.isBuiltIn(entity)) {
            return strings.get(R.string.plugins_origin_builtin)
        }
        if (PluginPackageManager.isDevOrigin(entity.sourceOrigin)) {
            val addr = PluginPackageManager.devDisplayAddressFromOrigin(entity.sourceOrigin)
            return if (!addr.isNullOrBlank()) {
                strings.get(R.string.plugins_origin_dev_with_addr, addr)
            } else {
                strings.get(R.string.plugins_origin_dev)
            }
        }
        if (PluginPackageManager.isFileOrigin(entity.sourceOrigin)) {
            return strings.get(R.string.plugins_origin_file)
        }
        val repo = entity.repoUrl?.let { reposByUrl[it] }
        if (repo != null && repo.name.isNotBlank()) return repo.name
        val host = entity.sourceUrl?.let { runCatching { URI(it).host }.getOrNull() }
        return host?.takeIf { it.isNotBlank() } ?: strings.get(R.string.plugins_origin_direct)
    }

    fun sourceOriginLabel(
        sourceOrigin: String,
        reposByUrl: Map<String, PluginRepoEntity>,
        strings: StringLookup,
    ): String {
        if (sourceOrigin.startsWith("other:${BuiltInPluginInstaller.SOURCE_PREFIX}")) {
            return strings.get(R.string.plugins_origin_builtin)
        }
        if (PluginPackageManager.isDevOrigin(sourceOrigin)) {
            return PluginPackageManager.devDisplayAddressFromOrigin(sourceOrigin)
                ?: strings.get(R.string.plugins_origin_dev)
        }
        if (PluginPackageManager.isFileOrigin(sourceOrigin)) {
            return strings.get(R.string.plugins_origin_file)
        }
        if (sourceOrigin.startsWith("repo:")) {
            val matchedRepo = reposByUrl.values.firstOrNull {
                PluginPackageManager.canonicalSourceOrigin(repoUrl = it.url, sourceUrl = null) == sourceOrigin
            }
            if (matchedRepo != null && matchedRepo.name.isNotBlank()) {
                return matchedRepo.name
            }
            val rawUrl = sourceOrigin.removePrefix("repo:")
            val host = runCatching { URI(rawUrl).host }.getOrNull()
            return host?.takeIf { it.isNotBlank() } ?: rawUrl
        }
        if (sourceOrigin.startsWith("url:")) {
            val rawUrl = sourceOrigin.removePrefix("url:")
            val host = runCatching { URI(rawUrl).host }.getOrNull()
            return host?.takeIf { it.isNotBlank() } ?: strings.get(R.string.plugins_origin_direct)
        }
        return strings.get(R.string.plugins_origin_direct)
    }

    /**
     * Explicit source-mismatch replacement copy distinguishing dev <-> release replacements
     * (spec §1.4: "会把来自 X 的正式版换成开发版" and vice versa).
     */
    fun replacementWarningText(
        existingSourceOrigin: String?,
        newSourceOrigin: String,
        reposByUrl: Map<String, PluginRepoEntity>,
        strings: StringLookup,
    ): String {
        if (existingSourceOrigin.isNullOrBlank()) {
            return strings.get(R.string.plugins_install_replace_warning)
        }
        val oldIsDev = PluginPackageManager.isDevOrigin(existingSourceOrigin)
        val newIsDev = PluginPackageManager.isDevOrigin(newSourceOrigin)
        val oldLabel = sourceOriginLabel(existingSourceOrigin, reposByUrl, strings)
        val newLabel = sourceOriginLabel(newSourceOrigin, reposByUrl, strings)
        return when {
            !oldIsDev && newIsDev -> {
                if (PluginPackageManager.isFileOrigin(existingSourceOrigin)) {
                    strings.get(R.string.plugins_install_replace_file_to_dev, oldLabel)
                } else {
                    strings.get(R.string.plugins_install_replace_to_dev, oldLabel)
                }
            }
            oldIsDev && newIsDev -> {
                strings.get(R.string.plugins_install_dev_switch_warning, oldLabel, newLabel)
            }
            oldIsDev && !newIsDev -> {
                strings.get(R.string.plugins_install_replace_dev_to_release, oldLabel, newLabel)
            }
            else -> {
                strings.get(R.string.plugins_install_replace_between_sources, oldLabel, newLabel)
            }
        }
    }
}

/** Downloaded and verified package awaiting explicit user confirmation on the Install Card. */
data class PendingPluginInstall(
    val bytes: ByteArray,
    val sourceUrl: String,
    val repoUrl: String?,
    val expectedId: String?,
    val expectedVersion: String?,
    val expectedSha256: String?,
    val preview: PluginInstallPreview,
    val devServerUrl: String? = null,
    val devDisplayAddress: String? = null,
    val confirmExpandedPermissionsOnInstall: Boolean = false,
)

/**
 * Single-flight UI task runner preventing parallel installs/reloads on repeated taps AND
 * refusing a new launch after cancel until the previous coroutine has completely finished.
 */
class SingleFlightOpRunner {
    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var cancellingFlag: Boolean = false

    /** True while the job is either actively running OR still winding down its cancellation cleanup. */
    val isBusy: Boolean
        get() = activeJob?.isCompleted == false

    val isCancelling: Boolean
        get() = cancellingFlag && activeJob?.isCompleted == false

    fun tryLaunch(
        scope: CoroutineScope,
        onStateChanged: (busy: Boolean, cancelling: Boolean) -> Unit = { _, _ -> },
        block: suspend () -> Unit,
    ): Boolean {
        if (activeJob?.isCompleted == false) return false
        cancellingFlag = false
        onStateChanged(true, false)
        var createdJob: Job? = null
        val job = scope.launch {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    if (activeJob === createdJob) {
                        activeJob = null
                        cancellingFlag = false
                        onStateChanged(false, false)
                    }
                }
            }
        }
        createdJob = job
        activeJob = job
        if (job.isCompleted) {
            activeJob = null
            cancellingFlag = false
            onStateChanged(false, false)
        }
        return true
    }

    fun cancel(onStateChanged: (busy: Boolean, cancelling: Boolean) -> Unit = { _, _ -> }) {
        val job = activeJob ?: return
        if (!job.isCompleted) {
            cancellingFlag = true
            onStateChanged(true, true)
            job.cancel()
        }
    }
}

/** UI action classification for a plugin entry listed in a subscribed repository. */
enum class RepoEntryAction {
    INSTALL,
    INSTALLED,
    UPDATE,
    REPLACE,
}

/** Available update discovered in a subscribed repository for an already-installed plugin. */
data class AvailablePluginUpdate(
    val pluginId: String,
    val repoUrl: String,
    val entry: RepoPluginEntry,
)

/** Raised during developer-mode install/reload when `main.js` fails QuickJS compilation. */
class DevPluginSyntaxException(
    val technicalError: String,
) : PackageInstallException(technicalError)

/**
 * Unified developer-screen & phone-input error result:
 * - [devSummary] & [devTechnicalError] are shown ONLY on the headset Developer screen
 *   (with [devTechnicalError] redacted via [PluginPackageManager.redactDeveloperError]).
 * - [phoneError] is returned to the phone web page and NEVER contains raw technical
 *   diagnostics, stack traces, or instance secrets.
 */
data class DevScreenErrorResult(
    val devSummary: String,
    val devTechnicalError: String?,
    val phoneError: UserFacingError,
)

/** Outcome of a developer-mode reload attempt. */
sealed interface DevReloadOutcome {
    data class Reloaded(
        val result: InstallResult,
        val displayName: String,
        val loadTechnicalError: String? = null,
    ) : DevReloadOutcome

    data class NeedsConfirmation(
        val pending: PendingPluginInstall,
    ) : DevReloadOutcome
}

/** One `.d3plugin` candidate discovered on headset storage. */
data class LocalPluginFileEntry(
    val file: File,
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val directoryLabel: String,
)

/** Pure, JVM-testable scanner & bounded reader for local `.d3plugin` files. */
object LocalPluginFileScanner {
    fun scanDirectories(directories: List<Pair<String, File?>>): List<LocalPluginFileEntry> {
        val out = ArrayList<LocalPluginFileEntry>()
        val seenPaths = HashSet<String>()
        for ((label, dir) in directories) {
            if (dir == null) continue
            val listed = try {
                if (!dir.exists() || !dir.isDirectory || !dir.canRead()) continue
                dir.listFiles()
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            } ?: continue

            for (f in listed) {
                try {
                    if (!f.isFile || f.name.startsWith(".")) continue
                    if (!f.name.endsWith(".d3plugin", ignoreCase = true)) continue
                    val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                    if (!seenPaths.add(canonical)) continue
                    out += LocalPluginFileEntry(
                        file = f,
                        fileName = f.name,
                        sizeBytes = f.length().coerceAtLeast(0L),
                        lastModifiedMs = f.lastModified().coerceAtLeast(0L),
                        directoryLabel = label,
                    )
                } catch (_: Exception) {
                    // Ignore unreadable individual entries
                }
            }
        }
        return out.sortedWith(
            compareByDescending<LocalPluginFileEntry> { it.lastModifiedMs }
                .thenBy { it.fileName.lowercase(Locale.ROOT) },
        )
    }

    /**
     * Reads up to [maxBytes] (8 MB) from [input] and fails fast with a clear Chinese
     * [PackageInstallException] if the stream exceeds the cap so huge files never cause OOM.
     */
    fun readBoundedPackageBytes(
        input: InputStream,
        maxBytes: Int = PluginPackageArchive.MAX_PACKAGE_BYTES,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw PackageInstallException("插件包超过大小限制（上限 ${maxBytes / (1024 * 1024)} MB）")
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    fun formatFileSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
            b >= 1024L -> "${(b + 512L) / 1024L} KB"
            else -> "$b B"
        }
    }
}

/**
 * Pure, JVM-testable coordinator for repository, developer-mode, local-file, and direct-URL
 * install previews, strict different-source replacement confirmation (NEVER auto-confirming
 * [PluginSourceMismatchException]), built-in uninstall protection, and update detection.
 */
object PluginInstallGuard {
    suspend fun prepareRepoInstall(
        packageManager: PluginPackageManager,
        repoClient: PluginRepositoryClient,
        repoUrl: String,
        entry: RepoPluginEntry,
        localize: (ByteArray, PluginInstallPreview) -> PluginInstallPreview = { _, p -> p },
    ): PendingPluginInstall {
        val download = repoClient.downloadVerifiedPackage(entry)
        val rawPreview = packageManager.previewPackage(
            zipBytes = download.bytes,
            sourceUrl = download.sourceUrl,
            repoUrl = repoUrl,
        )
        val localized = localize(download.bytes, rawPreview)
        return PendingPluginInstall(
            bytes = download.bytes,
            sourceUrl = download.sourceUrl,
            repoUrl = repoUrl,
            expectedId = entry.id,
            expectedVersion = entry.version,
            expectedSha256 = entry.sha256,
            preview = localized,
        )
    }

    suspend fun prepareDevInstall(
        packageManager: PluginPackageManager,
        repoClient: PluginRepositoryClient,
        rawServerAddress: String,
        localize: (ByteArray, PluginInstallPreview) -> PluginInstallPreview = { _, p -> p },
    ): PendingPluginInstall {
        val fetched = repoClient.fetchDevPlugin(rawServerAddress)
        val rawPreview = packageManager.previewPackage(
            zipBytes = fetched.download.bytes,
            sourceUrl = fetched.download.sourceUrl,
            devServerUrl = fetched.server.baseUrl,
        )
        val localized = localize(fetched.download.bytes, rawPreview)
        return PendingPluginInstall(
            bytes = fetched.download.bytes,
            sourceUrl = fetched.download.sourceUrl,
            repoUrl = null,
            expectedId = fetched.entry.id,
            expectedVersion = fetched.entry.version,
            expectedSha256 = fetched.entry.sha256,
            preview = localized,
            devServerUrl = fetched.server.baseUrl,
            devDisplayAddress = fetched.server.displayAddress,
            confirmExpandedPermissionsOnInstall = localized.needsPermissionReconfirm,
        )
    }

    /**
     * Reloads an already-installed dev plugin from [rawServerAddress]:
     * - Same dev origin + unchanged/shrunk permissions: runs zero-capability module-load probe
     *   first (so a syntax/top-level error never breaks the working version), installs in-place
     *   (preserving instances/storage/cookies), and verifies `init(ctx)` to report any redacted
     *   technical error to Developer Mode.
     * - Expanded permissions or different source (including dev address switch): returns
     *   [DevReloadOutcome.NeedsConfirmation] so the user explicitly reviews and confirms before
     *   anything is executed or modified.
     */
    suspend fun reloadDevPlugin(
        packageManager: PluginPackageManager,
        repoClient: PluginRepositoryClient,
        rawServerAddress: String,
        localize: (ByteArray, PluginInstallPreview) -> PluginInstallPreview = { _, p -> p },
        verifySyntaxBeforeSwap: Boolean = true,
        dnsResolver: ((String) -> List<InetAddress>)? = null,
    ): DevReloadOutcome {
        val pending = prepareDevInstall(
            packageManager = packageManager,
            repoClient = repoClient,
            rawServerAddress = rawServerAddress,
            localize = localize,
        )
        if (!pending.preview.isUpdate || pending.preview.requiresReplacementConfirmation) {
            return DevReloadOutcome.NeedsConfirmation(pending)
        }
        if (pending.preview.needsPermissionReconfirm) {
            return DevReloadOutcome.NeedsConfirmation(
                pending.copy(confirmExpandedPermissionsOnInstall = true),
            )
        }
        if (verifySyntaxBeforeSwap) {
            val syntaxErr = packageManager.probeArchiveModuleLoad(pending.bytes)
            if (syntaxErr != null) {
                throw DevPluginSyntaxException(syntaxErr)
            }
        }
        val result = confirmInstall(
            packageManager = packageManager,
            pending = pending,
            userConfirmedReplacement = false,
            verifyDevSyntax = false,
        )
        val loadErr = packageManager.verifyInstalledPluginLoad(
            pluginId = result.entity.pluginId,
            dnsResolver = dnsResolver,
        )
        return DevReloadOutcome.Reloaded(
            result = result,
            displayName = pending.preview.manifest.name,
            loadTechnicalError = loadErr,
        )
    }

    suspend fun prepareLocalFileInstall(
        packageManager: PluginPackageManager,
        file: File,
        localize: (ByteArray, PluginInstallPreview) -> PluginInstallPreview = { _, p -> p },
    ): PendingPluginInstall {
        if (!file.isFile || !file.canRead()) {
            throw PackageInstallException("无法读取所选插件文件，请确认文件存在且未损坏。")
        }
        if (file.length() > PluginPackageArchive.MAX_PACKAGE_BYTES) {
            throw PackageInstallException("插件包超过大小限制（上限 8 MB）")
        }
        val bytes = file.inputStream().use { LocalPluginFileScanner.readBoundedPackageBytes(it) }
        val fileUri = file.toURI().toString()
        val rawPreview = packageManager.previewPackage(zipBytes = bytes, sourceUrl = fileUri)
        val localized = localize(bytes, rawPreview)
        return PendingPluginInstall(
            bytes = bytes,
            sourceUrl = fileUri,
            repoUrl = null,
            expectedId = localized.manifest.id,
            expectedVersion = localized.manifest.version,
            expectedSha256 = rawPreview.sha256,
            preview = localized,
        )
    }

    suspend fun prepareLocalStreamInstall(
        packageManager: PluginPackageManager,
        inputStream: InputStream,
        displayName: String,
        localize: (ByteArray, PluginInstallPreview) -> PluginInstallPreview = { _, p -> p },
    ): PendingPluginInstall {
        val bytes = inputStream.use { LocalPluginFileScanner.readBoundedPackageBytes(it) }
        val safeName = displayName.ifBlank { "plugin.d3plugin" }.replace('/', '_')
        val fileUri = "file:///document/$safeName"
        val rawPreview = packageManager.previewPackage(zipBytes = bytes, sourceUrl = fileUri)
        val localized = localize(bytes, rawPreview)
        return PendingPluginInstall(
            bytes = bytes,
            sourceUrl = fileUri,
            repoUrl = null,
            expectedId = localized.manifest.id,
            expectedVersion = localized.manifest.version,
            expectedSha256 = rawPreview.sha256,
            preview = localized,
        )
    }

    suspend fun prepareDirectUrlInstall(
        packageManager: PluginPackageManager,
        repoClient: PluginRepositoryClient,
        rawUrl: String,
        localize: (ByteArray, PluginInstallPreview) -> PluginInstallPreview = { _, p -> p },
    ): PendingPluginInstall {
        val trimmed = rawUrl.trim()
        if (!trimmed.lowercase(Locale.ROOT).startsWith("https://")) {
            throw PackageInstallException("直链安装仅支持以 https:// 开头的地址。")
        }
        val download = repoClient.downloadDirectPackage(trimmed)
        val rawPreview = packageManager.previewPackage(
            zipBytes = download.bytes,
            sourceUrl = download.sourceUrl,
            repoUrl = null,
        )
        val localized = localize(download.bytes, rawPreview)
        return PendingPluginInstall(
            bytes = download.bytes,
            sourceUrl = download.sourceUrl,
            repoUrl = null,
            expectedId = localized.manifest.id,
            expectedVersion = localized.manifest.version,
            expectedSha256 = download.sha256,
            preview = localized,
        )
    }

    /**
     * Installs a previously previewed package strictly from [PendingPluginInstall.bytes]
     * (bound to [PendingPluginInstall.expectedSha256] / preview SHA-256) so neither local files
     * nor network endpoints are ever re-read after the user approves the confirmation card.
     *
     * Security invariant: NEVER sets `confirmReplaceFromDifferentSource = true` unless
     * [userConfirmedReplacement] is explicitly true (the user clicked the replacement warning CTA).
     */
    suspend fun confirmInstall(
        packageManager: PluginPackageManager,
        pending: PendingPluginInstall,
        userConfirmedReplacement: Boolean = false,
        verifyDevSyntax: Boolean = true,
    ): InstallResult {
        if (verifyDevSyntax && pending.devServerUrl != null) {
            val syntaxErr = packageManager.probeArchiveModuleLoad(pending.bytes)
            if (syntaxErr != null) {
                throw DevPluginSyntaxException(syntaxErr)
            }
        }
        return packageManager.installFromBytes(
            zipBytes = pending.bytes,
            sourceUrl = pending.sourceUrl,
            repoUrl = pending.repoUrl,
            expectedId = pending.expectedId,
            expectedVersion = pending.expectedVersion,
            expectedSha256 = pending.expectedSha256 ?: pending.preview.sha256,
            confirmReplaceFromDifferentSource = userConfirmedReplacement,
            devServerUrl = pending.devServerUrl,
            confirmExpandedPermissions = pending.confirmExpandedPermissionsOnInstall,
        )
    }

    /**
     * Classifies the button state for an entry in [RepoBrowseScreen]:
     * - Same version from the SAME repo OR already installed as a built-in plugin -> [RepoEntryAction.INSTALLED]
     * - Installed as dev or local-file (or different non-builtin origin) -> [RepoEntryAction.REPLACE]
     * - Same repo with a newer version -> [RepoEntryAction.UPDATE]
     */
    fun classifyRepoEntryAction(
        existing: InstalledPluginEntity?,
        repoOrigin: String,
        entryVersion: String,
    ): RepoEntryAction {
        if (existing == null) return RepoEntryAction.INSTALL
        val sameOrigin = existing.sourceOrigin == repoOrigin
        if (existing.version == entryVersion && (sameOrigin || BuiltInPluginInstaller.isBuiltIn(existing))) {
            return RepoEntryAction.INSTALLED
        }
        if (sameOrigin) {
            val curVer = SemVer.parse(existing.version)
            val newVer = SemVer.parse(entryVersion)
            return if (curVer != null && newVer != null && newVer > curVer) {
                RepoEntryAction.UPDATE
            } else {
                RepoEntryAction.INSTALLED
            }
        }
        return RepoEntryAction.REPLACE
    }

    suspend fun uninstallIfAllowed(
        packageManager: PluginPackageManager,
        entity: InstalledPluginEntity,
        strings: StringLookup? = null,
    ) {
        if (BuiltInPluginInstaller.isBuiltIn(entity)) {
            throw PackageInstallException(
                strings?.get(R.string.plugins_builtin_uninstall_blocked) ?: "built-in plugin cannot be uninstalled",
            )
        }
        packageManager.uninstall(entity.pluginId)
    }

    fun findAvailableUpdates(
        installed: List<InstalledPluginEntity>,
        repos: List<PluginRepoEntity>,
        repoClient: PluginRepositoryClient,
    ): Map<String, AvailablePluginUpdate> {
        val installedById = installed.associateBy { it.pluginId }
        val updates = LinkedHashMap<String, AvailablePluginUpdate>()
        for (repo in repos) {
            val raw = repo.lastIndexJson ?: continue
            val index: PluginRepoIndex = runCatching { repoClient.parseAndValidateIndex(raw) }.getOrNull() ?: continue
            val repoOrigin = PluginPackageManager.canonicalSourceOrigin(repoUrl = repo.url, sourceUrl = null)
            for (entry in index.plugins) {
                val cur = installedById[entry.id] ?: continue
                // Dev plugins never participate in repository automatic update checks
                if (PluginPackageManager.isDevOrigin(cur.sourceOrigin)) continue
                // Only offer one-click in-place update when coming from the same repository origin
                if (cur.sourceOrigin != repoOrigin) continue
                val curVer = SemVer.parse(cur.version) ?: continue
                val newVer = SemVer.parse(entry.version) ?: continue
                if (newVer > curVer) {
                    val prevBest = updates[entry.id]?.entry?.version?.let { SemVer.parse(it) }
                    if (prevBest == null || newVer > prevBest) {
                        updates[entry.id] = AvailablePluginUpdate(entry.id, repo.url, entry)
                    }
                }
            }
        }
        return updates
    }

    fun formatInstallError(error: Throwable, strings: StringLookup): String = when (error) {
        is PluginSourceMismatchException -> strings.get(R.string.plugins_install_replace_warning)
        is ManifestValidationException -> strings.get(R.string.plugins_err_invalid_manifest)
        is NetworkPolicyException -> SourceErrorDispatcher.safeUserMessage(
            error.message,
            strings.get(R.string.plugins_install_failed_generic),
        )
        is PackageInstallException -> {
            val raw = error.message.orEmpty()
            when {
                raw.contains("超过大小限制") -> strings.get(R.string.plugins_err_file_too_large)
                else -> SourceErrorDispatcher.safeUserMessage(
                    raw,
                    strings.get(R.string.plugins_err_invalid_package),
                )
            }
        }
        is RepositoryVerificationException -> {
            val raw = error.message.orEmpty()
            when {
                raw.contains("超过大小限制") -> strings.get(R.string.plugins_err_file_too_large)
                raw.contains("HTTP ") -> strings.get(R.string.plugins_err_http_status)
                raw.contains("超时") || raw.contains("总耗时") ||
                    raw.contains("Failed to connect", ignoreCase = true) ||
                    raw.contains("Unable to resolve", ignoreCase = true) ||
                    raw.contains("timed out", ignoreCase = true) ||
                    raw.contains("Connection refused", ignoreCase = true) ->
                    strings.get(R.string.plugins_err_connect_failed)
                else -> SourceErrorDispatcher.safeUserMessage(
                    raw,
                    strings.get(R.string.plugins_install_failed_generic),
                )
            }
        }
        else -> SourceErrorDispatcher.safeUserMessage(
            error.message,
            strings.get(R.string.plugins_install_failed_generic),
        )
    }

    /**
     * Returns true ONLY for transport/connectivity failures (unreachable host, refused connection,
     * timeout, broken socket) — NEVER for policy rejections, `minApp` mismatch, invalid zip/manifest,
     * or HTTP 4xx/5xx responses.
     */
    fun isConnectionFailure(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            when (cur) {
                is NetworkPolicyException,
                is ManifestValidationException,
                is PackageInstallException,
                is DevPluginSyntaxException,
                -> return false
                is ConnectException,
                is SocketTimeoutException,
                is UnknownHostException,
                is NoRouteToHostException,
                is SocketException,
                is EOFException,
                -> return true
            }
            cur = cur.cause
        }
        if (error is RepositoryVerificationException) {
            val msg = error.message.orEmpty()
            if (msg.contains("HTTP ") || msg.contains("SHA-256") || msg.contains("超过大小限制") ||
                msg.contains("格式无效") || msg.contains("不是有效的") || msg.contains("开发模式") ||
                msg.contains("仅支持") || msg.contains("不一致") || msg.contains("没有可用插件")
            ) {
                return false
            }
            return msg.contains("超时") ||
                msg.contains("总耗时") ||
                msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("Unable to resolve", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true)
        }
        return error is IOException
    }

    /**
     * Developer-screen-only error formatter: returns `(chineseSummary, redactedRawTechnicalDetail)`.
     * Preserves line numbers & technical details for syntax errors, manifest validation errors,
     * `init` failures, and `d3plugin serve` errors (redacted via [com.limelight.local.plugin.PluginLogger.redact]),
     * while keeping network/address policy rejections readable in Simplified Chinese.
     */
    fun formatDeveloperError(error: Throwable, strings: StringLookup): Pair<String, String?> = when (error) {
        is DevPluginSyntaxException -> strings.get(R.string.dev_tech_error_title) to
            com.limelight.local.plugin.PluginLogger.redact(error.technicalError)
        is ManifestValidationException -> strings.get(R.string.plugins_err_invalid_manifest) to
            com.limelight.local.plugin.PluginLogger.redact(error.message ?: error.errors.joinToString("\n"))
        is RepositoryVerificationException -> {
            if (!error.technicalDetail.isNullOrBlank()) {
                strings.get(R.string.dev_tech_error_title) to
                    com.limelight.local.plugin.PluginLogger.redact(error.technicalDetail)
            } else {
                formatInstallError(error, strings) to null
            }
        }
        is com.limelight.local.media.PluginInternalException -> {
            strings.get(R.string.dev_tech_error_title) to
                com.limelight.local.plugin.PluginLogger.redact(PluginPackageManager.extractRawTechnicalError(error))
        }
        else -> formatInstallError(error, strings) to null
    }

    /**
     * Formats an error raised during 「重新加载」: appends the offline / IP-changed hint ONLY
     * when [isConnectionFailure] is true (never for `minApp`, policy rejection, or bad manifest).
     */
    fun formatDevReloadError(error: Throwable, strings: StringLookup): Pair<String, String?> {
        val (summary, tech) = formatDeveloperError(error, strings)
        val finalSummary = if (tech == null && isConnectionFailure(error)) {
            "$summary ${strings.get(R.string.dev_reload_offline_hint)}"
        } else {
            summary
        }
        return finalSummary to tech
    }

    /**
     * Single entry point used by [PluginDeveloperScreen] (both headset actions and phone-input
     * submission) to produce:
     * 1. Redacted headset-only Developer diagnostics ([DevScreenErrorResult.devSummary] +
     *    [DevScreenErrorResult.devTechnicalError]) scrubbed with password configs, cookie jar
     *    values, and token-like `ctx.storage` values via [PluginPackageManager.redactDeveloperError].
     * 2. Safe Simplified Chinese [DevScreenErrorResult.phoneError] for the phone web page that
     *    NEVER contains raw technical errors, stack traces, or instance secrets.
     */
    suspend fun formatDeveloperScreenError(
        error: Throwable,
        packageManager: PluginPackageManager,
        pluginId: String?,
        isReload: Boolean,
        strings: StringLookup,
    ): DevScreenErrorResult {
        val (summary, tech) = if (isReload) {
            formatDevReloadError(error, strings)
        } else {
            formatDeveloperError(error, strings)
        }
        val redactedTech = if (tech != null) {
            packageManager.redactDeveloperError(pluginId, tech)
        } else {
            null
        }
        return DevScreenErrorResult(
            devSummary = summary,
            devTechnicalError = redactedTech,
            phoneError = UserFacingError(
                title = strings.get(R.string.phone_action_dev_server),
                detail = summary,
            ),
        )
    }
}
