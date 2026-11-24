package com.limelight.local.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

/**
 * Resolves plugin locale bundles (`locales/<tag>.json`), `ctx.t(key, vars?)`,
 * and `$t:key` translation markers in `manifest.json` (protocol §2 & §5).
 */
class PluginLocale(
    private val strings: Map<String, String>,
) {
    fun translate(key: String, vars: Map<String, String> = emptyMap()): String {
        val template = strings[key] ?: return key
        if (vars.isEmpty()) return template
        var result = template
        for ((k, v) in vars) {
            result = result.replace("{{$k}}", v).replace("{$k}", v)
        }
        return result
    }

    fun resolveManifestString(raw: String): String {
        return if (raw.startsWith(PREFIX)) {
            translate(raw.removePrefix(PREFIX))
        } else {
            raw
        }
    }

    fun localizeManifest(manifest: PluginManifest): PluginManifest = manifest.copy(
        name = resolveManifestString(manifest.name),
        description = resolveManifestString(manifest.description),
        config = manifest.config.map { f ->
            f.copy(
                label = resolveManifestString(f.label),
                placeholder = f.placeholder?.let(::resolveManifestString),
                options = f.options.map { opt ->
                    opt.copy(label = resolveManifestString(opt.label))
                },
            )
        },
    )

    companion object {
        private const val PREFIX = "\$t:"
        private val json = Json { ignoreUnknownKeys = true }

        val EMPTY = PluginLocale(emptyMap())

        fun fromPackageDir(packageDir: File, localeTag: String = Locale.getDefault().toLanguageTag()): PluginLocale {
            val localesDir = File(packageDir, "locales")
            if (!localesDir.isDirectory) return EMPTY
            val candidates = buildList {
                val clean = localeTag.replace('_', '-')
                if (clean.isNotEmpty()) add(clean)
                val lang = clean.substringBefore('-')
                if (lang.isNotEmpty() && lang != clean) add(lang)
                if ("zh-CN" !in this) add("zh-CN")
                if ("en" !in this) add("en")
            }
            val merged = LinkedHashMap<String, String>()
            // Load fallbacks first, then preferred locale overrides
            for (tag in candidates.reversed()) {
                val file = File(localesDir, "$tag.json")
                if (file.isFile) {
                    parseLocaleJson(file.readText(Charsets.UTF_8)).forEach { (k, v) -> merged[k] = v }
                }
            }
            return PluginLocale(merged)
        }

        fun fromMap(bundles: Map<String, String>, localeTag: String = "zh-CN"): PluginLocale {
            val candidates = buildList {
                val clean = localeTag.replace('_', '-')
                if (clean.isNotEmpty()) add(clean)
                val lang = clean.substringBefore('-')
                if (lang.isNotEmpty() && lang != clean) add(lang)
                if ("zh-CN" !in this) add("zh-CN")
                if ("en" !in this) add("en")
            }
            val merged = LinkedHashMap<String, String>()
            for (tag in candidates.reversed()) {
                val raw = bundles[tag] ?: continue
                parseLocaleJson(raw).forEach { (k, v) -> merged[k] = v }
            }
            return PluginLocale(merged)
        }

        private fun parseLocaleJson(raw: String): Map<String, String> {
            return try {
                val obj = json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
                obj.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.content?.let { k to it }
                }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
