package com.limelight.local.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.Locale

enum class PluginCapability(val wireName: String) {
    SEARCH("search"),
    PROGRESS("progress"),
    FAVORITES("favorites"),
    PLAYED("played"),
    FILTERS("filters"),
    SUBTITLES("subtitles"),
    DANMAKU("danmaku"),
    LIVE("live"),
    CATEGORIES("categories");

    companion object {
        fun fromWire(value: String): PluginCapability? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PluginAuthMode(val wireName: String) {
    NONE("none"),
    FORM("form"),
    QR("qr"),
    DEVICE("device");

    companion object {
        fun fromWire(value: String?): PluginAuthMode =
            entries.firstOrNull { it.wireName == value } ?: NONE
    }
}

enum class ConfigFieldType(val wireName: String) {
    TEXT("text"),
    PASSWORD("password"),
    URL("url"),
    NUMBER("number"),
    SELECT("select"),
    TOGGLE("toggle");

    companion object {
        fun fromWire(value: String): ConfigFieldType? = entries.firstOrNull { it.wireName == value }
    }
}

data class ConfigOption(
    val value: String,
    val label: String,
)

data class ConfigField(
    val key: String,
    val type: ConfigFieldType,
    val label: String,
    val required: Boolean = false,
    val discover: String? = null,
    val placeholder: String? = null,
    val defaultValue: JsonElement? = null,
    val options: List<ConfigOption> = emptyList(),
)

data class PluginPermissions(
    val hosts: List<String> = emptyList(),
    val userHosts: Boolean = false,
    val lan: Boolean = false,
) {
    /**
     * Returns true when this permission set grants strictly more capability
     * than [previous] (requiring explicit user re-confirmation on plugin update
     * per protocol §7).
     */
    fun isExpandedFrom(previous: PluginPermissions): Boolean {
        if (lan && !previous.lan) return true
        if (userHosts && !previous.userHosts) return true
        val prevNormalized = previous.hosts.map { it.lowercase(Locale.ROOT) }
        for (rawHost in hosts) {
            val host = rawHost.lowercase(Locale.ROOT)
            if (!isHostCoveredByAny(host, prevNormalized)) {
                return true
            }
        }
        return false
    }

    fun toJsonString(): String = buildJsonObject {
        put("hosts", buildJsonArray { hosts.forEach { add(JsonPrimitive(it)) } })
        put("userHosts", userHosts)
        put("lan", lan)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): PluginPermissions {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return PluginPermissions()
            val hosts = (obj["hosts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            val userHosts = (obj["userHosts"] as? JsonPrimitive)?.booleanOrNull ?: false
            val lan = (obj["lan"] as? JsonPrimitive)?.booleanOrNull ?: false
            return PluginPermissions(hosts, userHosts, lan)
        }

        private fun isHostCoveredByAny(pattern: String, existingPatterns: List<String>): Boolean {
            return existingPatterns.any { existing ->
                if (existing == pattern) {
                    true
                } else if (existing.startsWith("*.")) {
                    val existingSuffix = existing.removePrefix("*.")
                    if (pattern.startsWith("*.")) {
                        val subSuffix = pattern.removePrefix("*.")
                        subSuffix == existingSuffix || subSuffix.endsWith(".$existingSuffix")
                    } else {
                        pattern.endsWith(".$existingSuffix")
                    }
                } else {
                    false
                }
            }
        }
    }
}

data class PluginManifest(
    val api: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String = "",
    val homepage: String? = null,
    val license: String? = null,
    val minApp: String? = null,
    val multiInstance: Boolean = false,
    val capabilities: Set<PluginCapability> = emptySet(),
    val permissions: PluginPermissions = PluginPermissions(),
    val config: List<ConfigField> = emptyList(),
    val auth: PluginAuthMode = PluginAuthMode.NONE,
) {
    fun hasCapability(cap: PluginCapability): Boolean = cap in capabilities
    fun passwordConfigKeys(): Set<String> = config.filter { it.type == ConfigFieldType.PASSWORD }.map { it.key }.toSet()
    fun urlConfigKeys(): Set<String> = config.filter { it.type == ConfigFieldType.URL }.map { it.key }.toSet()
}

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (preRelease == other.preRelease) return 0
        if (preRelease == null) return 1
        if (other.preRelease == null) return -1
        val aParts = preRelease.split('.')
        val bParts = other.preRelease.split('.')
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            val aInt = aParts[i].toIntOrNull()
            val bInt = bParts[i].toIntOrNull()
            val cmp = when {
                aInt != null && bInt != null -> aInt.compareTo(bInt)
                aInt != null -> -1
                bInt != null -> 1
                else -> aParts[i].compareTo(bParts[i])
            }
            if (cmp != 0) return cmp
        }
        return aParts.size.compareTo(bParts.size)
    }

    companion object {
        private val REGEX = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(raw: String): SemVer? {
            val m = REGEX.matchEntire(raw.trim()) ?: return null
            return SemVer(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
                preRelease = m.groupValues[4].takeIf { it.isNotEmpty() },
            )
        }
    }
}

class ManifestValidationException(val errors: List<String>) :
    IllegalArgumentException("Invalid plugin manifest: ${errors.joinToString("; ")}")

object ManifestParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseAndValidate(rawJson: String): PluginManifest {
        val element = try {
            json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            throw ManifestValidationException(listOf("manifest.json is not valid JSON: ${e.message}"))
        }
        val schemaResult = JsonSchemaValidator.validateManifest(element)
        if (!schemaResult.valid) {
            throw ManifestValidationException(schemaResult.errors)
        }
        val obj = element as JsonObject
        val semanticErrors = ArrayList<String>()

        val permissionsObj = obj["permissions"] as? JsonObject
        val hosts = (permissionsObj?.get("hosts") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
        for (host in hosts) {
            val normalized = host.lowercase(Locale.ROOT)
            val bare = normalized.removePrefix("*.")
            if (bare == "localhost" || bare.endsWith(".localhost") || NetworkPolicy.isLoopbackLiteral(bare)) {
                semanticErrors += "permissions.hosts must not include loopback host '$host'"
            }
        }
        val permissions = PluginPermissions(
            hosts = hosts,
            userHosts = (permissionsObj?.get("userHosts") as? JsonPrimitive)?.booleanOrNull ?: false,
            lan = (permissionsObj?.get("lan") as? JsonPrimitive)?.booleanOrNull ?: false,
        )

        val configFields = ArrayList<ConfigField>()
        val seenKeys = HashSet<String>()
        (obj["config"] as? JsonArray)?.forEachIndexed { idx, fieldEl ->
            val f = fieldEl as JsonObject
            val key = (f["key"] as JsonPrimitive).content
            if (!seenKeys.add(key)) {
                semanticErrors += "$.config[$idx]: duplicate config key '$key'"
            }
            val type = ConfigFieldType.fromWire((f["type"] as JsonPrimitive).content)!!
            val label = (f["label"] as JsonPrimitive).content
            val required = (f["required"] as? JsonPrimitive)?.booleanOrNull ?: false
            val discover = (f["discover"] as? JsonPrimitive)?.content
            val placeholder = (f["placeholder"] as? JsonPrimitive)?.content
            val defaultEl = f["default"]
            val options = (f["options"] as? JsonArray)?.map { optEl ->
                val opt = optEl as JsonObject
                ConfigOption(
                    value = (opt["value"] as JsonPrimitive).content,
                    label = (opt["label"] as JsonPrimitive).content,
                )
            } ?: emptyList()

            if (defaultEl != null && defaultEl !is JsonNull) {
                val prim = defaultEl as? JsonPrimitive
                when (type) {
                    ConfigFieldType.TEXT, ConfigFieldType.PASSWORD, ConfigFieldType.URL -> {
                        if (prim == null || !prim.isString) {
                            semanticErrors += "$.config[$idx].default must be a string for type '${type.wireName}'"
                        }
                    }
                    ConfigFieldType.NUMBER -> {
                        if (prim == null || prim.isString || prim.doubleOrNull == null) {
                            semanticErrors += "$.config[$idx].default must be a number for type 'number'"
                        }
                    }
                    ConfigFieldType.TOGGLE -> {
                        if (prim == null || prim.isString || prim.booleanOrNull == null) {
                            semanticErrors += "$.config[$idx].default must be a boolean for type 'toggle'"
                        }
                    }
                    ConfigFieldType.SELECT -> {
                        if (prim == null || !prim.isString || options.none { it.value == prim.content }) {
                            semanticErrors += "$.config[$idx].default must match one of options[].value"
                        }
                    }
                }
            }

            configFields += ConfigField(
                key = key,
                type = type,
                label = label,
                required = required,
                discover = discover,
                placeholder = placeholder,
                defaultValue = defaultEl,
                options = options,
            )
        }

        if (semanticErrors.isNotEmpty()) {
            throw ManifestValidationException(semanticErrors)
        }

        val capabilities = (obj["capabilities"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.let(PluginCapability::fromWire) }
            ?.toSet()
            ?: emptySet()

        return PluginManifest(
            api = (obj["api"] as JsonPrimitive).intOrNull ?: 1,
            id = (obj["id"] as JsonPrimitive).content,
            name = (obj["name"] as JsonPrimitive).content,
            version = (obj["version"] as JsonPrimitive).content,
            author = (obj["author"] as JsonPrimitive).content,
            description = (obj["description"] as? JsonPrimitive)?.content ?: "",
            homepage = (obj["homepage"] as? JsonPrimitive)?.content,
            license = (obj["license"] as? JsonPrimitive)?.content,
            minApp = (obj["minApp"] as? JsonPrimitive)?.content,
            multiInstance = (obj["multiInstance"] as? JsonPrimitive)?.booleanOrNull ?: false,
            capabilities = capabilities,
            permissions = permissions,
            config = configFields,
            auth = PluginAuthMode.fromWire((obj["auth"] as? JsonPrimitive)?.content),
        )
    }
}
