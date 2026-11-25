package com.limelight.local.plugin

import android.content.Context
import com.limelight.local.media.Artwork
import com.limelight.local.media.AudioTrackOption
import com.limelight.local.media.Category
import com.limelight.local.media.DashStream
import com.limelight.local.media.ExtraField
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaTrack
import com.limelight.local.media.Page
import com.limelight.local.media.PersonCredit
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.QualityOption
import com.limelight.local.media.Shelf
import com.limelight.local.media.ShelfStyle
import com.limelight.local.media.SourceKind
import com.limelight.local.media.StereoHint
import com.limelight.local.media.SubtitleFormat
import com.limelight.local.media.SubtitleTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

data class FilterOption(
    val value: String,
    val label: String,
)

data class FilterGroup(
    val key: String,
    val label: String,
    val options: List<FilterOption>,
)

data class FiltersSpec(
    val sorts: List<FilterOption>,
    val filters: List<FilterGroup>,
)

enum class DanmakuMode(val wireName: String) {
    SCROLL("scroll"),
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun fromWire(raw: String?): DanmakuMode =
            entries.firstOrNull { it.wireName == raw } ?: SCROLL
    }
}

data class DanmakuItem(
    val timeMs: Long,
    val text: String,
    val color: String? = null,
    val mode: DanmakuMode = DanmakuMode.SCROLL,
)

enum class ProgressEvent(val wireName: String) {
    START("start"),
    PAUSE("pause"),
    PROGRESS("progress"),
    STOP("stop"),
    FINISH("finish"),
}

data class UserProfile(
    val name: String,
    val avatar: Artwork? = null,
)

data class FormLoginResult(
    val ok: Boolean,
    val user: UserProfile? = null,
)

data class QrLoginStart(
    val qr: String,
    val hint: String? = null,
    val expiresInSec: Int,
    val state: String,
)

data class DeviceLoginStart(
    val url: String,
    val code: String,
    val expiresInSec: Int,
    val state: String,
)

enum class LoginPollStatus(val wireName: String) {
    PENDING("pending"),
    SCANNED("scanned"),
    DONE("done"),
    EXPIRED("expired");

    companion object {
        fun fromWire(raw: String): LoginPollStatus =
            entries.firstOrNull { it.wireName == raw } ?: PENDING
    }
}

data class LoginPollResult(
    val status: LoginPollStatus,
    val user: UserProfile? = null,
)

/**
 * Validates raw JSON results against shared `protocol-types.schema.json` and
 * maps them to depth3d models following §11.2 forward-compatibility rules and
 * §11.3 URL security boundaries:
 * - Optional fields equal to `null` are treated as omitted
 * - Items with unknown `kind` are silently dropped rather than failing the call
 * - Unknown enum values fall back to defaults
 * - Out-of-range numeric fields are ignored (mapped to `null`)
 * - Danmaku `color` accepts `"#RRGGBB"` string or `0xRRGGBB` integer
 * - Image, stream, MPD, and subtitle URLs are checked against [NetworkPolicy]
 */
class PluginProtocolMapper(
    val networkPolicy: NetworkPolicy,
    private val logger: PluginLogger? = null,
    private val androidContext: Context? = null,
) {
    fun mapHome(element: JsonElement): List<Shelf> {
        requireSchema("HomeResult", element)
        val arr = element as JsonArray
        return arr.map { mapShelf(it as JsonObject) }
    }

    fun mapItemPage(element: JsonElement, nextPageNumber: Int? = null): Page<Item> {
        requireSchema("ItemPage", element)
        val obj = element as JsonObject
        val items = (obj["items"] as JsonArray).mapNotNull { mapItemOrNull(it as JsonObject) }
        val nextCursor = obj.optString("next")?.takeIf { it.isNotEmpty() }
        return Page(
            items = items,
            nextPage = if (nextCursor != null) nextPageNumber else null,
            nextCursor = nextCursor,
        )
    }

    fun mapItem(element: JsonElement): Item {
        requireSchema("Item", element)
        return mapItemOrNull(element as JsonObject)
            ?: throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.UNSUPPORTED),
                context = androidContext,
            )
    }

    fun mapPlayback(element: JsonElement, fallbackTitle: String?): PlaybackInfo {
        requireSchema("PlaybackInfo", element)
        val obj = element as JsonObject

        val isLive = obj.optBoolean("live") ?: false
        val dashObj = obj["dash"] as? JsonObject
        if (isLive && dashObj != null) {
            // §11.2: live: true with dash is unsupported in v1
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.UNSUPPORTED),
                context = androidContext,
            )
        }

        val url = obj.optString("url")
        if (url != null) {
            validateResourceUrl(url)
        }

        val dash = dashObj?.let { d ->
            val video = (d["video"] as JsonArray).map { mapTrack(it as JsonObject) }
            val audio = (d["audio"] as JsonArray).map { mapTrack(it as JsonObject) }
            DashStream(video = video, audio = audio)
        }
        val mpd = obj.optString("mpd")
        if (mpd != null) {
            for (u in PlaybackMediaSourceFactory.extractUrlsFromMpd(mpd)) {
                validateResourceUrl(u)
            }
        }

        val headers = mapHeaders(obj["headers"] as? JsonObject)
        val qualities = (obj["qualities"] as? JsonArray)?.map { qEl ->
            val q = qEl as JsonObject
            QualityOption(
                id = (q["id"] as JsonPrimitive).content,
                label = (q["label"] as JsonPrimitive).content,
                selected = q.optBoolean("selected") ?: false,
                locked = q.optString("locked"),
            )
        } ?: emptyList()

        val subtitles = (obj["subtitles"] as? JsonArray)?.mapNotNull { sEl ->
            val s = sEl as JsonObject
            val fmt = when ((s["format"] as JsonPrimitive).content.lowercase(Locale.ROOT)) {
                "srt" -> SubtitleFormat.SRT
                "vtt" -> SubtitleFormat.VTT
                "ass" -> SubtitleFormat.ASS
                "json" -> SubtitleFormat.JSON
                else -> return@mapNotNull null // §11.2: ignore unknown subtitle format gracefully
            }
            val subUrl = (s["url"] as JsonPrimitive).content
            validateResourceUrl(subUrl)
            SubtitleTrack(
                id = (s["id"] as JsonPrimitive).content,
                label = (s["label"] as JsonPrimitive).content,
                lang = s.optString("lang"),
                url = subUrl,
                format = fmt,
                headers = mapHeaders(s["headers"] as? JsonObject),
                isDefault = s.optBoolean("default") ?: false,
            )
        } ?: emptyList()

        val audios = (obj["audios"] as? JsonArray)?.map { aEl ->
            val a = aEl as JsonObject
            AudioTrackOption(
                id = (a["id"] as JsonPrimitive).content,
                label = (a["label"] as JsonPrimitive).content,
                lang = a.optString("lang"),
            )
        } ?: emptyList()

        val durationMs = obj.optDouble("durationMs")?.takeIf { it.isFinite() && it > 0.0 }?.toLong()
        val startMs = obj.optDouble("startMs")?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L
        val expiresAt = obj.optDouble("expiresAt")?.takeIf { it.isFinite() && it > 0.0 }?.toLong()
        val resolvedTitle = obj.optString("title")?.takeIf { it.isNotBlank() } ?: fallbackTitle

        return PlaybackInfo(
            url = url ?: "",
            headers = headers,
            title = resolvedTitle,
            startPositionMs = startMs,
            stereoHint = mapStereo(obj.optString("stereo")),
            dash = dash,
            mpd = mpd,
            mimeType = obj.optString("mime"),
            durationMs = durationMs,
            qualities = qualities,
            subtitles = subtitles,
            audios = audios,
            isLive = isLive,
            expiresAtMs = expiresAt,
            nextItemId = obj.optString("next"),
            sessionId = obj.optString("session"),
            sourceKind = SourceKind.PLUGIN,
        )
    }

    fun mapFilters(element: JsonElement): FiltersSpec {
        requireSchema("FiltersResult", element)
        val obj = element as JsonObject
        val sorts = (obj["sorts"] as JsonArray).map { mapOption(it as JsonObject) }
        val groups = (obj["filters"] as JsonArray).map { gEl ->
            val g = gEl as JsonObject
            FilterGroup(
                key = (g["key"] as JsonPrimitive).content,
                label = (g["label"] as JsonPrimitive).content,
                options = (g["options"] as JsonArray).map { mapOption(it as JsonObject) },
            )
        }
        return FiltersSpec(sorts = sorts, filters = groups)
    }

    fun mapDanmaku(element: JsonElement): List<DanmakuItem> {
        requireSchema("DanmakuResult", element)
        return (element as JsonArray).mapNotNull { el ->
            val o = el as JsonObject
            val timeMs = o.optDouble("timeMs")?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: return@mapNotNull null
            val color = parseDanmakuColor(o["color"])
            DanmakuItem(
                timeMs = timeMs,
                text = (o["text"] as JsonPrimitive).content,
                color = color,
                mode = DanmakuMode.fromWire(o.optString("mode")),
            )
        }
    }

    fun mapCategories(element: JsonElement): List<Category> {
        requireSchema("CategoriesResult", element)
        return (element as JsonArray).map { el ->
            val o = el as JsonObject
            Category(
                id = (o["id"] as JsonPrimitive).content,
                title = (o["title"] as JsonPrimitive).content,
                icon = o.optString("icon"),
            )
        }
    }

    fun mapSuggest(element: JsonElement): List<String> {
        requireSchema("SuggestResult", element)
        return (element as JsonArray).map { (it as JsonPrimitive).content }
    }

    fun mapFormLogin(element: JsonElement): FormLoginResult {
        requireSchema("FormLoginResult", element)
        val obj = element as JsonObject
        return FormLoginResult(
            ok = true,
            user = (obj["user"] as? JsonObject)?.let(::mapUserProfile),
        )
    }

    fun mapQrLoginStart(element: JsonElement): QrLoginStart {
        requireSchema("QrLoginStartResult", element)
        val obj = element as JsonObject
        val exp = obj.optDouble("expiresInSec")?.takeIf { it.isFinite() && it > 0 }?.toInt() ?: 180
        return QrLoginStart(
            qr = (obj["qr"] as JsonPrimitive).content,
            hint = obj.optString("hint"),
            expiresInSec = exp,
            state = (obj["state"] as JsonPrimitive).content,
        )
    }

    fun mapDeviceLoginStart(element: JsonElement): DeviceLoginStart {
        requireSchema("DeviceLoginStartResult", element)
        val obj = element as JsonObject
        val exp = obj.optDouble("expiresInSec")?.takeIf { it.isFinite() && it > 0 }?.toInt() ?: 300
        return DeviceLoginStart(
            url = (obj["url"] as JsonPrimitive).content,
            code = (obj["code"] as JsonPrimitive).content,
            expiresInSec = exp,
            state = (obj["state"] as JsonPrimitive).content,
        )
    }

    fun mapLoginPoll(element: JsonElement): LoginPollResult {
        requireSchema("LoginPollResult", element)
        val obj = element as JsonObject
        return LoginPollResult(
            status = LoginPollStatus.fromWire((obj["status"] as JsonPrimitive).content),
            user = (obj["user"] as? JsonObject)?.let(::mapUserProfile),
        )
    }

    private fun mapShelf(obj: JsonObject): Shelf {
        val id = (obj["id"] as JsonPrimitive).content
        val title = (obj["title"] as JsonPrimitive).content
        val items = (obj["items"] as JsonArray).mapNotNull { mapItemOrNull(it as JsonObject) }
        val style = when (obj.optString("style")) {
            "thumb" -> ShelfStyle.THUMB
            "continue" -> ShelfStyle.CONTINUE
            "hero" -> ShelfStyle.HERO
            "category" -> ShelfStyle.CATEGORY
            else -> ShelfStyle.POSTER
        }
        val more = obj.optString("more")
        return Shelf(
            category = Category(id = more ?: id, title = title, count = items.size),
            items = items,
            style = style,
            moreCategoryId = more,
        )
    }

    /**
     * Maps an Item object, returning `null` if `kind` is unrecognized (§11.2:
     * "`kind` 不认识的条目被丢弃"). Out-of-range numeric fields are ignored.
     */
    private fun mapItemOrNull(obj: JsonObject): Item? {
        val kind = mapKindOrNull((obj["kind"] as JsonPrimitive).content) ?: return null
        val year = obj.optInt("year")?.takeIf { it in 1800..3000 }
        val rating = obj.optFloat("rating")?.takeIf { it.isFinite() && it in 0f..10f }
        val durationMs = obj.optDouble("durationMs")?.takeIf { it.isFinite() && it >= 0.0 }?.toLong()
        val progressMs = obj.optDouble("progressMs")?.takeIf { it.isFinite() && it >= 0.0 }?.toLong()
        val index = obj.optInt("index")?.takeIf { it >= 0 }
        val childCount = obj.optInt("childCount")?.takeIf { it >= 0 }

        return Item(
            id = (obj["id"] as JsonPrimitive).content,
            kind = kind,
            title = (obj["title"] as JsonPrimitive).content,
            poster = obj["poster"]?.let(::mapImage),
            backdrop = obj["backdrop"]?.let(::mapImage),
            year = year,
            overview = obj.optString("overview"),
            rating = rating,
            durationMs = durationMs,
            progressMs = progressMs,
            stereoHint = mapStereo(obj.optString("stereo")),
            subtitle = obj.optString("subtitle"),
            childCount = childCount,
            thumb = obj["thumb"]?.let(::mapImage),
            played = obj.optBoolean("played"),
            favorite = obj.optBoolean("favorite"),
            badges = (obj["badges"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotEmpty() }?.content }
                ?: emptyList(),
            parentId = obj.optString("parentId"),
            index = index,
            seasons = (obj["seasons"] as? JsonArray)?.mapNotNull { mapItemOrNull(it as JsonObject) } ?: emptyList(),
            episodes = (obj["episodes"] as? JsonArray)?.mapNotNull { mapItemOrNull(it as JsonObject) } ?: emptyList(),
            related = (obj["related"] as? JsonArray)?.mapNotNull { mapItemOrNull(it as JsonObject) } ?: emptyList(),
            people = (obj["people"] as? JsonArray)?.map { pEl ->
                val p = pEl as JsonObject
                PersonCredit(
                    name = (p["name"] as JsonPrimitive).content,
                    role = p.optString("role"),
                    image = p["image"]?.let(::mapImage),
                    id = p.optString("id"),
                )
            } ?: emptyList(),
            genres = (obj["genres"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotEmpty() }?.content }
                ?: emptyList(),
            extra = (obj["extra"] as? JsonArray)?.map { eEl ->
                val e = eEl as JsonObject
                ExtraField(
                    label = (e["label"] as JsonPrimitive).content,
                    value = (e["value"] as JsonPrimitive).content,
                )
            } ?: emptyList(),
        )
    }

    private fun mapTrack(obj: JsonObject): MediaTrack {
        val url = (obj["url"] as JsonPrimitive).content
        validateResourceUrl(url)
        val backups = (obj["backupUrls"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.onEach(::validateResourceUrl)
            ?: emptyList()
        val bw = obj.optDouble("bandwidth")?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L
        return MediaTrack(
            id = (obj["id"] as JsonPrimitive).content,
            url = url,
            backupUrls = backups,
            mime = (obj["mime"] as JsonPrimitive).content,
            codecs = (obj["codecs"] as JsonPrimitive).content,
            bandwidth = bw,
            width = obj.optInt("width")?.takeIf { it > 0 },
            height = obj.optInt("height")?.takeIf { it > 0 },
            frameRate = obj.optDouble("frameRate")?.takeIf { it.isFinite() && it > 0.0 },
            quality = obj.optString("quality"),
            initRange = obj.optString("initRange")?.takeIf { RANGE_REGEX.matches(it) },
            indexRange = obj.optString("indexRange")?.takeIf { RANGE_REGEX.matches(it) },
        )
    }

    /**
     * Maps and validates a plugin [Artwork] URL against §11.3 rules:
     * rejects `file:`, `content:`, `data:`, loopback, link-local, reserved, and
     * disallowed private URLs.
     */
    private fun mapImage(el: JsonElement): Artwork? {
        if (el is JsonNull) return null
        val (url, headers, blurhash) = when (el) {
            is JsonPrimitive -> {
                if (!el.isString || el.content.isBlank()) return null
                Triple(el.content, emptyMap(), null)
            }
            is JsonObject -> {
                val u = el.optString("url") ?: return null
                Triple(u, mapHeaders(el["headers"] as? JsonObject), el.optString("blurhash"))
            }
            else -> return null
        }
        return try {
            networkPolicy.validateResourceUrlPrecheck(url)
            Artwork(url = url, headers = headers, blurhash = blurhash, sourceKind = SourceKind.PLUGIN)
        } catch (e: NetworkPolicyException) {
            logger?.warn("Rejected disallowed image URL: ${e.message}")
            null
        }
    }

    private fun mapUserProfile(obj: JsonObject): UserProfile = UserProfile(
        name = (obj["name"] as JsonPrimitive).content,
        avatar = obj["avatar"]?.let(::mapImage),
    )

    private fun mapOption(obj: JsonObject): FilterOption = FilterOption(
        value = (obj["value"] as JsonPrimitive).content,
        label = (obj["label"] as JsonPrimitive).content,
    )

    private fun mapHeaders(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>(obj.size)
        for ((k, v) in obj) {
            if (v is JsonPrimitive && v !is JsonNull) out[k] = v.content
        }
        return out
    }

    private fun parseDanmakuColor(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        if (prim.isString) {
            val s = prim.content.trim()
            return if (HEX_COLOR_REGEX.matches(s)) {
                if (s.startsWith("#")) s.uppercase(Locale.ROOT) else "#${s.uppercase(Locale.ROOT)}"
            } else {
                null
            }
        }
        // §11.2: accept 0xRRGGBB integer
        val intVal = prim.longOrNull ?: return null
        if (intVal !in 0L..0xFFFFFFL) return null
        return String.format(Locale.US, "#%06X", intVal)
    }

    private fun mapKindOrNull(raw: String): ItemKind? = when (raw) {
        "movie" -> ItemKind.MOVIE
        "series" -> ItemKind.SERIES
        "season" -> ItemKind.SEASON
        "episode" -> ItemKind.EPISODE
        "video" -> ItemKind.VIDEO
        "folder" -> ItemKind.FOLDER
        "live" -> ItemKind.LIVE
        "collection" -> ItemKind.COLLECTION
        "person" -> ItemKind.PERSON
        else -> null
    }

    private fun mapStereo(raw: String?): StereoHint = when (raw) {
        "sbs" -> StereoHint.SBS
        "ou" -> StereoHint.OU
        "mvhevc" -> StereoHint.MVHEVC
        "180" -> StereoHint.VR180
        "360" -> StereoHint.VR360
        else -> StereoHint.MONO
    }

    private fun validateResourceUrl(url: String) {
        try {
            networkPolicy.validateResourceUrlPrecheck(url)
        } catch (e: NetworkPolicyException) {
            logger?.error("Rejected PlaybackInfo URL by security policy: ${e.message}")
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.FORBIDDEN, e.message),
                context = androidContext,
                cause = e,
            )
        }
    }

    private fun requireSchema(defName: String, element: JsonElement) {
        val res = JsonSchemaValidator.validateProtocolType(defName, element)
        if (!res.valid) {
            logger?.error("Plugin returned invalid $defName: ${res.errors.joinToString("; ")}")
            throw PluginErrorMapper.toException(
                PluginErrorPayload(PluginErrorCode.INTERNAL),
                context = androidContext,
            )
        }
    }

    private fun JsonObject.optString(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun JsonObject.optBoolean(key: String): Boolean? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.booleanOrNull
    }

    private fun JsonObject.optInt(key: String): Int? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.intOrNull
    }

    private fun JsonObject.optFloat(key: String): Float? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.floatOrNull
    }

    private fun JsonObject.optDouble(key: String): Double? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.doubleOrNull
    }

    companion object {
        private val RANGE_REGEX = Regex("\\A\\d+-\\d+\\z")
        private val HEX_COLOR_REGEX = Regex("\\A#?[0-9a-fA-F]{6}([0-9a-fA-F]{2})?\\z")
    }
}
