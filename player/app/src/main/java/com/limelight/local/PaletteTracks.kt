package com.limelight.local

import java.util.Locale

/** Localized labels for track formatting, injected from Android string resources. */
data class TrackLabelStrings(
    val subtitleOrdinalFmt: String = "字幕 %1\$d",
    val audioOrdinalFmt: String = "音轨 %1\$d",
    val langZhHant: String = "繁體中文",
    val langZhHans: String = "简体中文",
    val langEn: String = "英语",
    val langJa: String = "日语",
    val langKo: String = "韩语",
    val langFr: String = "法语",
    val langDe: String = "德语",
    val langEs: String = "西班牙语",
    val langRu: String = "俄语",
)

/** Pure metadata snapshot extracted from an ExoPlayer Tracks.Group entry (100% JVM-testable). */
data class RawTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val formatId: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val channelCount: Int = 0,
    val isSelected: Boolean = false,
    val isDefaultFlag: Boolean = false,
)

/** Result of transforming raw ExoPlayer tracks into stable-ID palette tracks + natural defaults. */
data class BuiltTrackSnapshot(
    val subtitles: List<PaletteSubtitleTrack>,
    val audios: List<PaletteAudioTrack>,
    val naturalDefaultSubId: String,
    val naturalDefaultAudId: String?,
)

/** Pure declarative instruction for updating ExoPlayer DefaultTrackSelector parameters. */
data class TrackSelectorPlan(
    val resetAll: Boolean,
    val disableText: Boolean,
    val textOverrideGroup: Int,
    val textOverrideTrack: Int,
    val audioOverrideGroup: Int,
    val audioOverrideTrack: Int,
)

/**
 * Pure JVM-testable track identity, formatting, single-capture default logic, and
 * ExoPlayer track-selector planner for the VR Quick Palette (G2 review #2, #3, #5, #7).
 */
object PaletteTrackManager {
    private const val MIME_PGS = "application/pgs"
    private const val MIME_VOBSUB = "application/vobsub"
    private const val MIME_DVBSUBS = "application/dvbsubs"

    @JvmStatic
    fun isBitmapSubtitleMime(mime: String?): Boolean {
        if (mime == null) return false
        return mime == MIME_PGS || mime == MIME_VOBSUB || mime == MIME_DVBSUBS || mime.startsWith("image/")
    }

    @JvmStatic
    fun friendlyLanguageName(code: String?, strings: TrackLabelStrings): String {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("und", ignoreCase = true)) return ""
        val c = trimmed.lowercase(Locale.US)
        if (c.startsWith("zh-tw") || c.startsWith("zh-hk") || c.startsWith("zh-hant") || c == "cht") {
            return strings.langZhHant
        }
        if (c.startsWith("zh") || c == "chi" || c == "zho" || c == "chs") {
            return strings.langZhHans
        }
        if (c.startsWith("en") || c == "eng") return strings.langEn
        if (c.startsWith("ja") || c == "jpn") return strings.langJa
        if (c.startsWith("ko") || c == "kor") return strings.langKo
        if (c.startsWith("fr") || c == "fra" || c == "fre") return strings.langFr
        if (c.startsWith("de") || c == "deu" || c == "ger") return strings.langDe
        if (c.startsWith("es") || c == "spa") return strings.langEs
        if (c.startsWith("ru") || c == "rus") return strings.langRu
        return trimmed
    }

    @JvmStatic
    fun formatSubtitleLabel(raw: RawTrackInfo, ordinal: Int, strings: TrackLabelStrings): String {
        val lang = friendlyLanguageName(raw.language, strings)
        val label = raw.label?.trim().orEmpty()
        if (label.isNotEmpty() && lang.isNotEmpty() && !label.contains(lang)) {
            return "$lang · $label"
        }
        if (label.isNotEmpty()) return label
        if (lang.isNotEmpty()) return lang
        return String.format(Locale.getDefault(), strings.subtitleOrdinalFmt, ordinal)
    }

    @JvmStatic
    fun formatAudioLabel(raw: RawTrackInfo, ordinal: Int, strings: TrackLabelStrings): String {
        val lang = friendlyLanguageName(raw.language, strings)
        val label = raw.label?.trim().orEmpty()
        val base = when {
            label.isNotEmpty() -> label
            lang.isNotEmpty() -> lang
            else -> String.format(Locale.getDefault(), strings.audioOrdinalFmt, ordinal)
        }
        val ch = when {
            raw.channelCount >= 8 -> "7.1"
            raw.channelCount >= 6 -> "5.1"
            raw.channelCount == 2 -> "2.0"
            else -> ""
        }
        return if (ch.isNotEmpty() && !base.contains(ch)) "$base · $ch" else base
    }

    /**
     * Builds a stable track identifier based on `Format.id` + `language` + `label` (plus audio
     * channel count), disambiguating identical metadata via occurrence ordinal. Falls back to
     * group/track indices ONLY when all metadata fields are empty.
     */
    @JvmStatic
    fun buildStableTrackId(kind: String, raw: RawTrackInfo, duplicateOrdinal: Int): String {
        val fmtId = raw.formatId?.trim().orEmpty()
        val lang = raw.language?.trim()?.lowercase(Locale.US).orEmpty()
        val lbl = raw.label?.trim().orEmpty()
        if (fmtId.isEmpty() && lang.isEmpty() && lbl.isEmpty()) {
            return "$kind|idx:${raw.groupIndex}:${raw.trackIndex}"
        }
        val base = buildString {
            append(kind)
            append("|id=").append(fmtId)
            append("|lang=").append(lang)
            append("|lbl=").append(lbl)
            if (kind == "aud" && raw.channelCount > 0) {
                append("|ch=").append(raw.channelCount)
            }
        }
        return if (duplicateOrdinal > 0) "$base#$duplicateOrdinal" else base
    }

    /**
     * Builds stable-ID subtitle/audio track lists and extracts the natural default track IDs.
     * Note: callers must capture defaults ONCE per media item (before applying user overrides),
     * while still calling [buildTracks] on later `onTracksChanged` callbacks to refresh group indices.
     */
    @JvmStatic
    fun buildTracks(
        rawSubtitles: List<RawTrackInfo>,
        rawAudios: List<RawTrackInfo>,
        strings: TrackLabelStrings = TrackLabelStrings(),
    ): BuiltTrackSnapshot {
        val subSeen = HashMap<String, Int>()
        val subs = ArrayList<PaletteSubtitleTrack>(rawSubtitles.size)
        var selectedSubId: String? = null
        var flagDefaultSubId: String? = null
        for (raw in rawSubtitles) {
            val baseKey = buildStableTrackId("sub", raw, 0)
            val dup = subSeen[baseKey] ?: 0
            subSeen[baseKey] = dup + 1
            val id = if (dup == 0) baseKey else buildStableTrackId("sub", raw, dup)
            val bitmap = isBitmapSubtitleMime(raw.sampleMimeType)
            val label = formatSubtitleLabel(raw, subs.size + 1, strings)
            subs.add(PaletteSubtitleTrack(id, label, bitmap, raw.groupIndex, raw.trackIndex))
            if (raw.isSelected && selectedSubId == null) selectedSubId = id
            if (raw.isDefaultFlag && flagDefaultSubId == null) flagDefaultSubId = id
        }

        val audSeen = HashMap<String, Int>()
        val auds = ArrayList<PaletteAudioTrack>(rawAudios.size)
        var selectedAudId: String? = null
        var flagDefaultAudId: String? = null
        for (raw in rawAudios) {
            val baseKey = buildStableTrackId("aud", raw, 0)
            val dup = audSeen[baseKey] ?: 0
            audSeen[baseKey] = dup + 1
            val id = if (dup == 0) baseKey else buildStableTrackId("aud", raw, dup)
            val label = formatAudioLabel(raw, auds.size + 1, strings)
            auds.add(PaletteAudioTrack(id, label, raw.groupIndex, raw.trackIndex))
            if (raw.isSelected && selectedAudId == null) selectedAudId = id
            if (raw.isDefaultFlag && flagDefaultAudId == null) flagDefaultAudId = id
        }

        val naturalSub = selectedSubId ?: flagDefaultSubId ?: PaletteController.SUBTITLE_OFF_ID
        val naturalAud = selectedAudId ?: flagDefaultAudId ?: auds.firstOrNull()?.id
        return BuiltTrackSnapshot(
            subtitles = subs,
            audios = auds,
            naturalDefaultSubId = naturalSub,
            naturalDefaultAudId = naturalAud,
        )
    }

    /** Plan applied before loading every new media item so disabled subtitles never leak across videos. */
    @JvmStatic
    fun planNewItemReset(): TrackSelectorPlan = TrackSelectorPlan(
        resetAll = true,
        disableText = false,
        textOverrideGroup = -1,
        textOverrideTrack = -1,
        audioOverrideGroup = -1,
        audioOverrideTrack = -1,
    )

    /** Computes ExoPlayer DefaultTrackSelector override instructions from current palette selection. */
    @JvmStatic
    fun planSelection(
        hasSubtitles: Boolean,
        selectedSubtitle: PaletteSubtitleTrack?,
        selectedAudio: PaletteAudioTrack?,
    ): TrackSelectorPlan {
        val disableText = hasSubtitles && selectedSubtitle == null
        val textG = if (hasSubtitles && selectedSubtitle != null) selectedSubtitle.groupIndex else -1
        val textT = if (hasSubtitles && selectedSubtitle != null) selectedSubtitle.trackIndex else -1
        val audG = selectedAudio?.groupIndex ?: -1
        val audT = selectedAudio?.trackIndex ?: -1
        return TrackSelectorPlan(
            resetAll = false,
            disableText = disableText,
            textOverrideGroup = textG,
            textOverrideTrack = textT,
            audioOverrideGroup = audG,
            audioOverrideTrack = audT,
        )
    }

    /**
     * Splits the full single-line gesture hint string on '：' (or ':') into two lines for the
     * 1024x160 VR Toast card so neither line is truncated or ellipsized.
     */
    @JvmStatic
    fun splitGestureHintForToast(fullHint: String): Array<String?> {
        val sepIdx = fullHint.indexOf('：').let { if (it >= 0) it else fullHint.indexOf(':') }
        if (sepIdx <= 0 || sepIdx >= fullHint.length - 1) {
            return arrayOf(fullHint, null)
        }
        val line1 = fullHint.substring(0, sepIdx).trim()
        val line2 = fullHint.substring(sepIdx + 1).trim()
        return arrayOf(line1, line2.ifEmpty { null })
    }
}
