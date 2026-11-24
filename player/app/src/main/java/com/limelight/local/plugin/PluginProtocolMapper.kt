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
 * Validates raw JSON results returned by a plugin against the shared
 * `protocol-types.schema.json` and maps them into strongly-typed depth3d
 * models. Also enforces playback URL safety rules (protocol §7).
 */
class PluginProtocolMapper(
    private val networkPolicy: NetworkPolicy,
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
        val items = (obj["items"] as JsonArray).map { mapItem(it as JsonObject) }
        val nextCursor = (obj["next"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        return Page(
            items = items,
            nextPage = if (nextCursor != null) nextPageNumber else null,
            nextCursor = nextCursor,
        )
    }

    fun mapItem(element: JsonElement): Item {
        requireSchema("Item", element)
        return mapItem(element as JsonObject)
    }

    fun mapPlayback(element: JsonElement, fallbackTitle: String): PlaybackInfo {
        requireSchema("PlaybackInfo", element)
        val obj = element as JsonObject

        val url = (obj["url"] as? JsonPrimitive)?.content
        if (url != null) {
            validatePlaybackUrl(url)
        }

        val dash = (obj["dash"] as? JsonObject)?.let { dashObj ->
            val video = (dashObj["video"] as JsonArray).map { mapTrack(it as JsonObject) }
            val audio = (dashObj["audio"] as JsonArray).map { mapTrack(it as JsonObject) }
            DashStream(video = video, audio = audio)
        }
        val mpd = (obj["mpd"] as? JsonPrimitive)?.content

        val headers = mapHeaders(obj["headers"] as? JsonObject)
        val qualities = (obj["qualities"] as? JsonArray)?.map { qEl ->
            val q = qEl as JsonObject
            QualityOption(
                id = (q["id"] as JsonPrimitive).content,
                label = (q["label"] as JsonPrimitive).content,
                selected = (q["selected"] as? JsonPrimitive)?.booleanOrNull ?: false,
                locked = (q["locked"] as? JsonPrimitive)?.content,
            )
        } ?: emptyList()

        val subtitles = (obj["subtitles"] as? JsonArray)?.map { sEl ->
            val s = sEl as JsonObject
            val subUrl = (s["url"] as JsonPrimitive).content
            validatePlaybackUrl(subUrl)
            val fmt = when ((s["format"] as JsonPrimitive).content.lowercase(Locale.ROOT)) {
                "srt" -> SubtitleFormat.SRT
                "vtt" -> SubtitleFormat.VTT
                "ass" -> SubtitleFormat.ASS
                "json" -> SubtitleFormat.JSON
                else -> SubtitleFormat.VTT
            }
            SubtitleTrack(
                id = (s["id"] as JsonPrimitive).content,
                label = (s["label"] as JsonPrimitive).content,
                lang = (s["lang"] as? JsonPrimitive)?.content,
                url = subUrl,
                format = fmt,
                headers = mapHeaders(s["headers"] as? JsonObject),
                isDefault = (s["default"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        } ?: emptyList()

        val audios = (obj["audios"] as? JsonArray)?.map { aEl ->
            val a = aEl as JsonObject
            AudioTrackOption(
                id = (a["id"] as JsonPrimitive).content,
                label = (a["label"] as JsonPrimitive).content,
                lang = (a["lang"] as? JsonPrimitive)?.content,
            )
        } ?: emptyList()

        return PlaybackInfo(
            url = url ?: "",
            headers = headers,
            title = fallbackTitle,
            startPositionMs = (obj["startMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            stereoHint = mapStereo((obj["stereo"] as? JsonPrimitive)?.content),
            dash = dash,
            mpd = mpd,
            mimeType = (obj["mime"] as? JsonPrimitive)?.content,
            qualities = qualities,
            subtitles = subtitles,
            audios = audios,
            isLive = (obj["live"] as? JsonPrimitive)?.booleanOrNull ?: false,
            expiresAtMs = (obj["expiresAt"] as? JsonPrimitive)?.longOrNull,
            nextItemId = (obj["next"] as? JsonPrimitive)?.content,
            sessionId = (obj["session"] as? JsonPrimitive)?.content,
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
        return (element as JsonArray).map { el ->
            val o = el as JsonObject
            DanmakuItem(
                timeMs = (o["timeMs"] as JsonPrimitive).doubleOrNull?.toLong() ?: 0L,
                text = (o["text"] as JsonPrimitive).content,
                color = (o["color"] as? JsonPrimitive)?.content,
                mode = DanmakuMode.fromWire((o["mode"] as? JsonPrimitive)?.content),
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
                icon = (o["icon"] as? JsonPrimitive)?.content,
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
        return QrLoginStart(
            qr = (obj["qr"] as JsonPrimitive).content,
            hint = (obj["hint"] as? JsonPrimitive)?.content,
            expiresInSec = (obj["expiresInSec"] as JsonPrimitive).doubleOrNull?.toInt() ?: 180,
            state = (obj["state"] as JsonPrimitive).content,
        )
    }

    fun mapDeviceLoginStart(element: JsonElement): DeviceLoginStart {
        requireSchema("DeviceLoginStartResult", element)
        val obj = element as JsonObject
        return DeviceLoginStart(
            url = (obj["url"] as JsonPrimitive).content,
            code = (obj["code"] as JsonPrimitive).content,
            expiresInSec = (obj["expiresInSec"] as JsonPrimitive).doubleOrNull?.toInt() ?: 300,
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
        val items = (obj["items"] as JsonArray).map { mapItem(it as JsonObject) }
        val style = when ((obj["style"] as? JsonPrimitive)?.content) {
            "thumb" -> ShelfStyle.THUMB
            "continue" -> ShelfStyle.CONTINUE
            "hero" -> ShelfStyle.HERO
            "category" -> ShelfStyle.CATEGORY
            else -> ShelfStyle.POSTER
        }
        val more = (obj["more"] as? JsonPrimitive)?.content
        return Shelf(
            category = Category(id = more ?: id, title = title, count = items.size),
            items = items,
            style = style,
            moreCategoryId = more,
        )
    }

    private fun mapItem(obj: JsonObject): Item {
        return Item(
            id = (obj["id"] as JsonPrimitive).content,
            kind = mapKind((obj["kind"] as JsonPrimitive).content),
            title = (obj["title"] as JsonPrimitive).content,
            poster = obj["poster"]?.let(::mapImage),
            backdrop = obj["backdrop"]?.let(::mapImage),
            year = (obj["year"] as? JsonPrimitive)?.intOrNull,
            overview = (obj["overview"] as? JsonPrimitive)?.content,
            rating = (obj["rating"] as? JsonPrimitive)?.floatOrNull,
            durationMs = (obj["durationMs"] as? JsonPrimitive)?.doubleOrNull?.toLong(),
            progressMs = (obj["progressMs"] as? JsonPrimitive)?.doubleOrNull?.toLong(),
            stereoHint = mapStereo((obj["stereo"] as? JsonPrimitive)?.content),
            subtitle = (obj["subtitle"] as? JsonPrimitive)?.content,
            childCount = (obj["childCount"] as? JsonPrimitive)?.intOrNull,
            thumb = obj["thumb"]?.let(::mapImage),
            played = (obj["played"] as? JsonPrimitive)?.booleanOrNull,
            favorite = (obj["favorite"] as? JsonPrimitive)?.booleanOrNull,
            badges = (obj["badges"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            parentId = (obj["parentId"] as? JsonPrimitive)?.content,
            index = (obj["index"] as? JsonPrimitive)?.intOrNull,
            seasons = (obj["seasons"] as? JsonArray)?.map { mapItem(it as JsonObject) } ?: emptyList(),
            episodes = (obj["episodes"] as? JsonArray)?.map { mapItem(it as JsonObject) } ?: emptyList(),
            related = (obj["related"] as? JsonArray)?.map { mapItem(it as JsonObject) } ?: emptyList(),
            people = (obj["people"] as? JsonArray)?.map { pEl ->
                val p = pEl as JsonObject
                PersonCredit(
                    name = (p["name"] as JsonPrimitive).content,
                    role = (p["role"] as? JsonPrimitive)?.content,
                    image = p["image"]?.let(::mapImage),
                    id = (p["id"] as? JsonPrimitive)?.content,
                )
            } ?: emptyList(),
            genres = (obj["genres"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
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
        validatePlaybackUrl(url)
        val backups = (obj["backupUrls"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.onEach(::validatePlaybackUrl)
            ?: emptyList()
        return MediaTrack(
            id = (obj["id"] as JsonPrimitive).content,
            url = url,
            backupUrls = backups,
            mime = (obj["mime"] as JsonPrimitive).content,
            codecs = (obj["codecs"] as JsonPrimitive).content,
            bandwidth = (obj["bandwidth"] as JsonPrimitive).doubleOrNull?.toLong() ?: 0L,
            width = (obj["width"] as? JsonPrimitive)?.intOrNull,
            height = (obj["height"] as? JsonPrimitive)?.intOrNull,
            frameRate = (obj["frameRate"] as? JsonPrimitive)?.doubleOrNull,
            quality = (obj["quality"] as? JsonPrimitive)?.content,
            initRange = (obj["initRange"] as? JsonPrimitive)?.content,
            indexRange = (obj["indexRange"] as? JsonPrimitive)?.content,
        )
    }

    private fun mapImage(el: JsonElement): Artwork? {
        if (el is JsonNull) return null
        return when (el) {
            is JsonPrimitive -> Artwork(url = el.content)
            is JsonObject -> {
                val url = (el["url"] as JsonPrimitive).content
                val headers = mapHeaders(el["headers"] as? JsonObject)
                val blurhash = (el["blurhash"] as? JsonPrimitive)?.content
                Artwork(url = url, headers = headers, blurhash = blurhash)
            }
            else -> null
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
            if (v is JsonPrimitive) out[k] = v.content
        }
        return out
    }

    private fun mapKind(raw: String): ItemKind = when (raw) {
        "movie" -> ItemKind.MOVIE
        "series" -> ItemKind.SERIES
        "season" -> ItemKind.SEASON
        "episode" -> ItemKind.EPISODE
        "folder" -> ItemKind.FOLDER
        "live" -> ItemKind.LIVE
        "collection" -> ItemKind.COLLECTION
        "person" -> ItemKind.PERSON
        else -> ItemKind.VIDEO
    }

    private fun mapStereo(raw: String?): StereoHint = when (raw) {
        "sbs" -> StereoHint.SBS
        "ou" -> StereoHint.OU
        "mvhevc" -> StereoHint.MVHEVC
        "180" -> StereoHint.VR180
        "360" -> StereoHint.VR360
        else -> StereoHint.MONO
    }

    private fun validatePlaybackUrl(url: String) {
        try {
            networkPolicy.validatePlaybackUrlSyntax(url)
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
}
