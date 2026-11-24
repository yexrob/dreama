package com.limelight.local.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
)

/**
 * Evaluates the shared JSON Schema (Draft 2020-12) files from
 * `docs/specs/plugin-schema/`, bundled as classpath resources under
 * `plugin-schema/` at build time so the Android host and `tools/plugin-kit`
 * enforce identical structural contracts.
 */
object JsonSchemaValidator {
    private val json = Json { ignoreUnknownKeys = true }
    private val schemaCache = ConcurrentHashMap<String, JsonObject>()
    private val regexCache = ConcurrentHashMap<String, Regex>()

    private fun loadSchema(fileName: String): JsonObject = schemaCache.getOrPut(fileName) {
        val resourcePath = "plugin-schema/$fileName"
        val stream: InputStream = JsonSchemaValidator::class.java.classLoader
            ?.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Missing bundled plugin schema resource: $resourcePath")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.parseToJsonElement(text) as JsonObject
    }

    fun validateManifest(element: JsonElement): ValidationResult {
        val root = loadSchema("manifest.schema.json")
        return validate(element, root, root)
    }

    fun validateRepository(element: JsonElement): ValidationResult {
        val root = loadSchema("repository.schema.json")
        return validate(element, root, root)
    }

    fun validateProtocolType(defName: String, element: JsonElement): ValidationResult {
        val root = loadSchema("protocol-types.schema.json")
        val defs = root["\$defs"] as? JsonObject
            ?: throw IllegalStateException("protocol-types.schema.json missing \$defs")
        val schema = defs[defName] as? JsonObject
            ?: throw IllegalArgumentException("Unknown protocol type definition: $defName")
        return validate(element, schema, root)
    }

    fun validate(value: JsonElement, schema: JsonObject, root: JsonObject): ValidationResult {
        val errors = ArrayList<String>()
        check(value, schema, root, "$", errors)
        return ValidationResult(errors.isEmpty(), errors)
    }

    private fun check(
        value: JsonElement,
        schema: JsonObject,
        root: JsonObject,
        path: String,
        errors: MutableList<String>,
    ) {
        // $ref
        val ref = (schema["\$ref"] as? JsonPrimitive)?.content
        if (ref != null) {
            val target = resolveRef(ref, root)
                ?: run {
                    errors += "$path: unresolved schema ref $ref"
                    return
                }
            check(value, target, root, path, errors)
        }

        // const
        val constVal = schema["const"]
        if (constVal != null && !jsonEquals(value, constVal)) {
            errors += "$path: expected const $constVal, got $value"
            return
        }

        // enum
        val enumVals = schema["enum"] as? JsonArray
        if (enumVals != null && enumVals.none { jsonEquals(value, it) }) {
            errors += "$path: value $value not in enum $enumVals"
            return
        }

        // type
        val typeSpec = schema["type"]
        if (typeSpec != null) {
            val allowedTypes = when (typeSpec) {
                is JsonPrimitive -> listOf(typeSpec.content)
                is JsonArray -> typeSpec.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }
            if (allowedTypes.isNotEmpty() && allowedTypes.none { matchesType(value, it) }) {
                errors += "$path: expected type ${allowedTypes.joinToString("|")}, got ${describeType(value)}"
                return
            }
        }

        // String constraints
        if (value is JsonPrimitive && value.isString) {
            val s = value.content
            (schema["minLength"] as? JsonPrimitive)?.intOrNull?.let { min ->
                if (s.length < min) errors += "$path: string length ${s.length} < minLength $min"
            }
            (schema["maxLength"] as? JsonPrimitive)?.intOrNull?.let { max ->
                if (s.length > max) errors += "$path: string length ${s.length} > maxLength $max"
            }
            (schema["pattern"] as? JsonPrimitive)?.content?.let { pat ->
                val regex = regexCache.getOrPut(pat) { Regex(pat) }
                if (!regex.containsMatchIn(s)) {
                    errors += "$path: string '$s' does not match pattern $pat"
                }
            }
        }

        // Number constraints
        if (value is JsonPrimitive && !value.isString && value !is JsonNull && value.booleanOrNull == null) {
            val d = value.doubleOrNull
            if (d != null) {
                (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { min ->
                    if (d < min) errors += "$path: value $d < minimum $min"
                }
                (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { max ->
                    if (d > max) errors += "$path: value $d > maximum $max"
                }
                (schema["exclusiveMinimum"] as? JsonPrimitive)?.doubleOrNull?.let { min ->
                    if (d <= min) errors += "$path: value $d <= exclusiveMinimum $min"
                }
                (schema["exclusiveMaximum"] as? JsonPrimitive)?.doubleOrNull?.let { max ->
                    if (d >= max) errors += "$path: value $d >= exclusiveMaximum $max"
                }
            }
        }

        // Object constraints
        if (value is JsonObject) {
            val req = schema["required"] as? JsonArray
            req?.forEach { r ->
                val key = (r as? JsonPrimitive)?.content ?: return@forEach
                if (!value.containsKey(key)) {
                    errors += "$path: missing required property '$key'"
                }
            }
            val props = schema["properties"] as? JsonObject
            props?.forEach { (key, propSchema) ->
                val childVal = value[key]
                if (childVal != null && propSchema is JsonObject) {
                    check(childVal, propSchema, root, "$path.$key", errors)
                }
            }
            val addProps = schema["additionalProperties"]
            if (addProps != null) {
                val knownKeys = props?.keys ?: emptySet()
                for ((key, childVal) in value) {
                    if (key in knownKeys) continue
                    when (addProps) {
                        is JsonPrimitive -> {
                            if (addProps.booleanOrNull == false) {
                                errors += "$path: unexpected property '$key'"
                            }
                        }
                        is JsonObject -> {
                            check(childVal, addProps, root, "$path.$key", errors)
                        }
                        else -> Unit
                    }
                }
            }
        }

        // Array constraints
        if (value is JsonArray) {
            (schema["minItems"] as? JsonPrimitive)?.intOrNull?.let { min ->
                if (value.size < min) errors += "$path: array size ${value.size} < minItems $min"
            }
            (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { max ->
                if (value.size > max) errors += "$path: array size ${value.size} > maxItems $max"
            }
            if ((schema["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true) {
                val seen = ArrayList<JsonElement>(value.size)
                for (i in value.indices) {
                    val item = value[i]
                    if (seen.any { jsonEquals(it, item) }) {
                        errors += "$path[$i]: duplicate item in uniqueItems array"
                    } else {
                        seen += item
                    }
                }
            }
            val itemSchema = schema["items"] as? JsonObject
            if (itemSchema != null) {
                for (i in value.indices) {
                    check(value[i], itemSchema, root, "$path[$i]", errors)
                }
            }
        }

        // Combinators: allOf, anyOf, oneOf, not
        (schema["allOf"] as? JsonArray)?.forEach { sub ->
            if (sub is JsonObject) check(value, sub, root, path, errors)
        }
        (schema["anyOf"] as? JsonArray)?.let { subs ->
            val ok = subs.any { sub ->
                sub is JsonObject && validateSub(value, sub, root)
            }
            if (!ok) errors += "$path: value did not match anyOf"
        }
        (schema["oneOf"] as? JsonArray)?.let { subs ->
            val count = subs.count { sub ->
                sub is JsonObject && validateSub(value, sub, root)
            }
            if (count != 1) errors += "$path: value matched $count schemas in oneOf (expected 1)"
        }
        (schema["not"] as? JsonObject)?.let { notSchema ->
            if (validateSub(value, notSchema, root)) {
                errors += "$path: value must not match 'not' schema"
            }
        }

        // Conditional: if / then / else
        val ifSchema = schema["if"] as? JsonObject
        if (ifSchema != null) {
            if (validateSub(value, ifSchema, root)) {
                (schema["then"] as? JsonObject)?.let { check(value, it, root, path, errors) }
            } else {
                (schema["else"] as? JsonObject)?.let { check(value, it, root, path, errors) }
            }
        }
    }

    private fun validateSub(value: JsonElement, schema: JsonObject, root: JsonObject): Boolean {
        val subErrors = ArrayList<String>()
        check(value, schema, root, "$", subErrors)
        return subErrors.isEmpty()
    }

    private fun resolveRef(ref: String, root: JsonObject): JsonObject? {
        if (!ref.startsWith("#/")) return null
        var cur: JsonElement = root
        for (part in ref.removePrefix("#/").split('/')) {
            val unescaped = part.replace("~1", "/").replace("~0", "~")
            cur = (cur as? JsonObject)?.get(unescaped) ?: return null
        }
        return cur as? JsonObject
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value is JsonNull
        "number" -> value is JsonPrimitive && !value.isString && value !is JsonNull &&
            value.booleanOrNull == null && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value !is JsonNull &&
            value.booleanOrNull == null && (value.longOrNull != null || isIntegralDouble(value.doubleOrNull))
        else -> false
    }

    private fun isIntegralDouble(d: Double?): Boolean =
        d != null && d.isFinite() && d == kotlin.math.floor(d)

    private fun describeType(value: JsonElement): String = when {
        value is JsonObject -> "object"
        value is JsonArray -> "array"
        value is JsonNull -> "null"
        value is JsonPrimitive && value.isString -> "string"
        value is JsonPrimitive && value.booleanOrNull != null -> "boolean"
        value is JsonPrimitive && value.doubleOrNull != null -> "number"
        else -> "unknown"
    }

    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean {
        if (a == b) return true
        // Numeric equality (e.g., 1 == 1.0)
        if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
            a !is JsonNull && b !is JsonNull && a.booleanOrNull == null && b.booleanOrNull == null
        ) {
            val da = a.doubleOrNull
            val db = b.doubleOrNull
            if (da != null && db != null) return da == db
        }
        return false
    }
}
