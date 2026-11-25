package com.limelight.local.wall

import com.limelight.R
import com.limelight.local.media.SourceKind
import com.limelight.local.plugin.ConfigField
import com.limelight.local.plugin.ConfigFieldType
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.PackageInstallException
import com.limelight.local.plugin.PluginAuthMode
import com.limelight.local.plugin.PluginInstallPreview
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginManifest
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.PluginPermissions
import com.limelight.local.plugin.PluginRepoEntity
import com.limelight.local.plugin.PluginRepoIndex
import com.limelight.local.plugin.PluginRepositoryClient
import com.limelight.local.plugin.PluginSourceMismatchException
import com.limelight.local.plugin.RepoPluginEntry
import com.limelight.local.plugin.SemVer
import com.limelight.local.stream.PhoneFormField
import com.limelight.local.stream.PhoneFormFieldKind
import com.limelight.local.stream.PhoneFormOption
import com.limelight.local.stream.StringLookup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.Locale

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
    ): PluginInstanceEntity {
        val created = packageManager.createInstance(
            pluginId = manifest.id,
            displayName = displayName,
            config = config,
        )
        try {
            val source = packageManager.openSource(created.instanceId, dnsResolver = dnsResolver)
            when (manifest.auth) {
                PluginAuthMode.FORM -> source.loginForm()
                PluginAuthMode.NONE -> source.home()
                PluginAuthMode.QR, PluginAuthMode.DEVICE -> Unit
            }
            return created
        } catch (e: CancellationException) {
            runCatching { packageManager.deleteInstance(created.instanceId) }
            throw e
        } catch (e: Throwable) {
            runCatching { packageManager.deleteInstance(created.instanceId) }
            throw e
        }
    }

    suspend fun updateAndAuthenticateFormOrNone(
        packageManager: PluginPackageManager,
        manifest: PluginManifest,
        instanceId: String,
        displayName: String,
        config: Map<String, JsonElement>,
        dnsResolver: ((String) -> List<java.net.InetAddress>)? = null,
    ): PluginInstanceEntity {
        val prevConfig = runCatching { packageManager.getInstanceConfig(instanceId) }.getOrNull()
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
        } catch (e: CancellationException) {
            if (prevConfig != null) {
                runCatching { packageManager.updateInstanceConfig(instanceId, config = prevConfig) }
            }
            throw e
        } catch (e: Throwable) {
            throw e
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
        val repo = entity.repoUrl?.let { reposByUrl[it] }
        if (repo != null && repo.name.isNotBlank()) return repo.name
        val host = entity.sourceUrl?.let { runCatching { URI(it).host }.getOrNull() }
        return host?.takeIf { it.isNotBlank() } ?: strings.get(R.string.plugins_origin_direct)
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
)

/** Available update discovered in a subscribed repository for an already-installed plugin. */
data class AvailablePluginUpdate(
    val pluginId: String,
    val repoUrl: String,
    val entry: RepoPluginEntry,
)

/**
 * Pure, JVM-testable coordinator for repository install preview, strict different-source
 * replacement confirmation (NEVER auto-confirming [PluginSourceMismatchException]),
 * built-in uninstall protection, and update detection.
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

    /**
     * Installs a previously previewed package.
     * Security invariant: NEVER sets `confirmReplaceFromDifferentSource = true` unless
     * [userConfirmedReplacement] is explicitly true (the user clicked the replacement warning CTA).
     */
    suspend fun confirmInstall(
        packageManager: PluginPackageManager,
        pending: PendingPluginInstall,
        userConfirmedReplacement: Boolean = false,
    ) = packageManager.installFromBytes(
        zipBytes = pending.bytes,
        sourceUrl = pending.sourceUrl,
        repoUrl = pending.repoUrl,
        expectedId = pending.expectedId,
        expectedVersion = pending.expectedVersion,
        expectedSha256 = pending.expectedSha256,
        confirmReplaceFromDifferentSource = userConfirmedReplacement,
    )

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
        else -> SourceErrorDispatcher.safeUserMessage(
            error.message,
            strings.get(R.string.plugins_install_failed_generic),
        )
    }
}
