package com.limelight.local.plugin

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource as ExoMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.limelight.local.media.DashStream
import com.limelight.local.media.MediaTrack
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.media.SubtitleFormat
import com.limelight.local.media.SubtitleTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.Locale

/**
 * Resolved playback strategy derived from [PlaybackInfo]:
 * - [DirectUrl]: progressive, HLS, or DASH manifest URL
 * - [SynthesizedDashMpd]: separated DASH video/audio tracks carrying fMP4
 *   `initRange`/`indexRange` synthesized into a standard DASH MPD XML (supports
 *   byte-range seeking, multi-track selection, and multi-BaseURL CDN failover)
 * - [MergedProgressiveTracks]: separated video/audio tracks without DASH byte
 *   ranges, combined via Media3 [MergingMediaSource] with per-track backup URL
 *   failover
 * - [InlineMpd]: plugin-supplied raw DASH MPD XML document
 */
sealed interface ResolvedStreamPlan {
    data class DirectUrl(
        val url: String,
        val streamType: DirectStreamType,
        val mimeType: String?,
        val headers: Map<String, String>,
    ) : ResolvedStreamPlan

    data class SynthesizedDashMpd(
        val mpdXml: String,
        val baseUrl: String,
        val headers: Map<String, String>,
        val videoBackupChains: List<List<String>>,
        val audioBackupChains: List<List<String>>,
    ) : ResolvedStreamPlan

    data class MergedProgressiveTracks(
        val videoUrls: List<String>,
        val audioUrls: List<String>,
        val videoMime: String,
        val audioMime: String?,
        val headers: Map<String, String>,
    ) : ResolvedStreamPlan

    data class InlineMpd(
        val mpdXml: String,
        val baseUrl: String,
        val headers: Map<String, String>,
    ) : ResolvedStreamPlan
}

enum class DirectStreamType { PROGRESSIVE, HLS, DASH }

data class ResolvedSubtitlePlan(
    val track: SubtitleTrack,
    val effectiveHeaders: Map<String, String>,
    val media3MimeType: String,
    val requiresJsonToVttConversion: Boolean,
)

data class PlaybackPlan(
    val info: PlaybackInfo,
    val stream: ResolvedStreamPlan,
    val subtitles: List<ResolvedSubtitlePlan>,
)

/**
 * Synthesizes a standards-compliant DASH MPD XML document from protocol §3
 * `dash: { video: Track[], audio: Track[] }` streams.
 *
 * Design rationale:
 * When tracks provide `initRange`/`indexRange` (standard fMP4 SegmentBase used
 * by modern video platforms), synthesizing an MPD XML gives Media3's
 * [DashMediaSource] native byte-range seeking, adaptive/manual track switching,
 * and automatic `<BaseURL>` failover across `backupUrls`.
 * When tracks omit byte ranges (plain separate progressive files), the factory
 * falls back to [MergingMediaSource] with [FailoverUrlDataSource].
 */
object DashMpdSynthesizer {
    fun hasSegmentRanges(dash: DashStream): Boolean {
        val allTracks = dash.video + dash.audio
        return allTracks.isNotEmpty() && allTracks.all {
            !it.initRange.isNullOrBlank() && !it.indexRange.isNullOrBlank()
        }
    }

    fun synthesize(dash: DashStream, isLive: Boolean = false, durationMs: Long? = null): String {
        require(dash.video.isNotEmpty()) { "DASH stream must contain at least one video track" }
        val typeAttr = if (isLive) "dynamic" else "static"
        val durationAttr = if (!isLive && durationMs != null && durationMs > 0) {
            " mediaPresentationDuration=\"${formatIsoDuration(durationMs)}\""
        } else {
            ""
        }
        val sb = StringBuilder(2048)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"$typeAttr\"$durationAttr ")
        sb.append("minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
        sb.append("  <Period id=\"0\">\n")

        appendAdaptationSet(sb, id = 0, contentType = "video", tracks = dash.video)
        if (dash.audio.isNotEmpty()) {
            appendAdaptationSet(sb, id = 1, contentType = "audio", tracks = dash.audio)
        }

        sb.append("  </Period>\n")
        sb.append("</MPD>\n")
        return sb.toString()
    }

    private fun appendAdaptationSet(
        sb: StringBuilder,
        id: Int,
        contentType: String,
        tracks: List<MediaTrack>,
    ) {
        sb.append("    <AdaptationSet id=\"$id\" contentType=\"$contentType\" segmentAlignment=\"true\">\n")
        for (track in tracks) {
            sb.append("      <Representation id=\"${xmlEscape(track.id)}\" ")
            sb.append("mimeType=\"${xmlEscape(track.mime)}\" ")
            sb.append("codecs=\"${xmlEscape(track.codecs)}\" ")
            sb.append("bandwidth=\"${track.bandwidth.coerceAtLeast(1L)}\"")
            track.width?.let { sb.append(" width=\"$it\"") }
            track.height?.let { sb.append(" height=\"$it\"") }
            track.frameRate?.let { sb.append(" frameRate=\"${formatFrameRate(it)}\"") }
            sb.append(">\n")

            val urls = listOf(track.url) + track.backupUrls
            urls.forEachIndexed { idx, candidate ->
                sb.append("        <BaseURL serviceLocation=\"cdn-$idx\">${xmlEscape(candidate)}</BaseURL>\n")
            }

            if (!track.initRange.isNullOrBlank() && !track.indexRange.isNullOrBlank()) {
                sb.append("        <SegmentBase indexRange=\"${xmlEscape(track.indexRange)}\">\n")
                sb.append("          <Initialization range=\"${xmlEscape(track.initRange)}\"/>\n")
                sb.append("        </SegmentBase>\n")
            }
            sb.append("      </Representation>\n")
        }
        sb.append("    </AdaptationSet>\n")
    }

    private fun formatFrameRate(fps: Double): String {
        val rounded = fps.toLong()
        return if (fps == rounded.toDouble()) rounded.toString() else String.format(Locale.US, "%.3f", fps)
    }

    private fun formatIsoDuration(durationMs: Long): String {
        val secs = durationMs / 1000.0
        return String.format(Locale.US, "PT%.3fS", secs)
    }

    private fun xmlEscape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

/**
 * Converts JSON subtitle payloads (`format: "json"`, supporting both BCC
 * `{ body: [{ from, to, content }] }` seconds format and generic
 * `[{ startMs, endMs, text }]` arrays) into standard WebVTT for Media3.
 */
object JsonSubtitleConverter {
    private val json = Json { ignoreUnknownKeys = true }

    fun toWebVtt(rawJson: String): String {
        val root = json.parseToJsonElement(rawJson)
        val cues: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["body"] as? JsonArray) ?: (root["cues"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val sb = StringBuilder("WEBVTT\n\n")
        var idx = 1
        for (el in cues) {
            val obj = el as? JsonObject ?: continue
            val text = (obj["content"] as? JsonPrimitive)?.content
                ?: (obj["text"] as? JsonPrimitive)?.content
                ?: continue
            val startMs = (obj["startMs"] as? JsonPrimitive)?.longOrNull
                ?: ((obj["from"] as? JsonPrimitive)?.doubleOrNull?.times(1000.0)?.toLong())
                ?: continue
            val endMs = (obj["endMs"] as? JsonPrimitive)?.longOrNull
                ?: ((obj["to"] as? JsonPrimitive)?.doubleOrNull?.times(1000.0)?.toLong())
                ?: (startMs + 2000L)
            sb.append(idx++).append('\n')
            sb.append(formatVttTime(startMs.coerceAtLeast(0L)))
                .append(" --> ")
                .append(formatVttTime(endMs.coerceAtLeast(startMs)))
                .append('\n')
            sb.append(text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatVttTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val millis = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, millis)
    }
}

/**
 * Stateful candidate-URL failover selector used both by [FailoverUrlDataSource]
 * and directly in unit tests. Remembers the last healthy index so subsequent
 * range requests stay on the working CDN until another error occurs.
 */
class FailoverUrlSelector(
    urls: List<String>,
) {
    private val candidates: List<String> = urls.distinct().also {
        require(it.isNotEmpty()) { "At least one candidate URL is required" }
    }
    @Volatile
    private var preferredIndex: Int = 0

    fun <T> executeWithFailover(attempt: (url: String) -> T): T {
        val start = preferredIndex
        var lastError: IOException? = null
        for (offset in candidates.indices) {
            val idx = (start + offset) % candidates.size
            val candidate = candidates[idx]
            try {
                val result = attempt(candidate)
                preferredIndex = idx
                return result
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All candidate URLs failed")
    }

    fun currentPreferredUrl(): String = candidates[preferredIndex]
    fun allCandidates(): List<String> = candidates
}

/**
 * Media3 [DataSource] that tries a primary URL followed by backup URLs on
 * connection or HTTP errors, preserving byte ranges and request headers.
 */
@OptIn(UnstableApi::class)
class FailoverUrlDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val selector: FailoverUrlSelector,
) : BaseDataSource(/* isNetwork = */ true) {

    private var activeDelegate: DataSource? = null
    private var activeUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (delegate, uri, length) = selector.executeWithFailover { candidateUrl ->
            val targetUri = Uri.parse(candidateUrl)
            val delegate = upstreamFactory.createDataSource()
            try {
                val rewritten = dataSpec.buildUpon().setUri(targetUri).build()
                val len = delegate.open(rewritten)
                Triple(delegate, targetUri, len)
            } catch (e: IOException) {
                runCatching { delegate.close() }
                throw e
            }
        }
        activeDelegate = delegate
        activeUri = uri
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val delegate = activeDelegate ?: return C.RESULT_END_OF_INPUT
        val read = delegate.read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = activeUri ?: activeDelegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        activeDelegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val delegate = activeDelegate
        activeDelegate = null
        activeUri = null
        if (delegate != null) {
            try {
                delegate.close()
            } finally {
                transferEnded()
            }
        }
    }
}

/**
 * Media3 [DataSource] that fetches a `format: "json"` external subtitle and
 * transparently converts it to WebVTT bytes for ExoPlayer's subtitle decoder.
 */
@OptIn(UnstableApi::class)
class JsonSubtitleVttDataSource(
    private val upstreamFactory: DataSource.Factory,
) : BaseDataSource(/* isNetwork = */ true) {
    private var converted: ByteArray? = null
    private var offset: Int = 0
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        openedUri = dataSpec.uri
        val delegate = upstreamFactory.createDataSource()
        val raw = try {
            delegate.open(dataSpec)
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val n = delegate.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            runCatching { delegate.close() }
        }
        val vttBytes = JsonSubtitleConverter.toWebVtt(raw).toByteArray(Charsets.UTF_8)
        converted = vttBytes
        offset = 0
        transferStarted(dataSpec)
        return vttBytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = converted ?: return C.RESULT_END_OF_INPUT
        val remaining = bytes.size - this.offset
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val toCopy = minOf(length, remaining)
        System.arraycopy(bytes, this.offset, buffer, offset, toCopy)
        this.offset += toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        if (converted != null) {
            converted = null
            offset = 0
            openedUri = null
            transferEnded()
        }
    }
}

/**
 * Translates protocol v1 [PlaybackInfo] (`url` / `dash` / `mpd`, `headers`,
 * `qualities`, `subtitles`, `expiresAt`) into a Media3 [ExoMediaSource].
 */
@OptIn(UnstableApi::class)
class PlaybackMediaSourceFactory(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val httpFactoryProvider: (Map<String, String>) -> DataSource.Factory = { headers ->
        DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
    },
    private val networkPolicy: NetworkPolicy? = null,
) {
    /**
     * Pure-JVM planning step: validates URLs, selects stream mode (DirectUrl,
     * SynthesizedDashMpd, MergedProgressiveTracks, or InlineMpd), selects
     * tracks matching the active quality, and resolves subtitle MIME & headers.
     */
    fun plan(info: PlaybackInfo, selectedQualityId: String? = null, durationMs: Long? = null): PlaybackPlan {
        val effectiveQuality = selectedQualityId
            ?: info.qualities.firstOrNull { it.selected && it.locked == null }?.id

        val streamPlan: ResolvedStreamPlan = when {
            info.dash != null -> planDash(info.dash, info.headers, info.isLive, effectiveQuality, durationMs)
            !info.mpd.isNullOrBlank() -> {
                val baseUrl = info.url.ifBlank { "https://inline.depth3d.invalid/manifest.mpd" }
                ResolvedStreamPlan.InlineMpd(
                    mpdXml = info.mpd,
                    baseUrl = baseUrl,
                    headers = info.headers,
                )
            }
            info.url.isNotBlank() -> {
                validateUrlIfNeeded(info.url)
                val streamType = detectDirectStreamType(info.url, info.mimeType)
                ResolvedStreamPlan.DirectUrl(
                    url = info.url,
                    streamType = streamType,
                    mimeType = info.mimeType ?: defaultMimeFor(streamType),
                    headers = info.headers,
                )
            }
            else -> throw SourceUnsupportedException("这个内容没有可用的播放地址，请尝试切换清晰度或片源。")
        }

        val subtitlePlans = info.subtitles.map { sub ->
            validateUrlIfNeeded(sub.url)
            val headers = if (sub.headers.isNotEmpty()) sub.headers else info.headers
            val (mime, needsJsonConv) = when (sub.format) {
                SubtitleFormat.SRT -> MimeTypes.APPLICATION_SUBRIP to false
                SubtitleFormat.VTT -> MimeTypes.TEXT_VTT to false
                SubtitleFormat.ASS -> MimeTypes.TEXT_SSA to false
                SubtitleFormat.JSON -> MimeTypes.TEXT_VTT to true
            }
            ResolvedSubtitlePlan(
                track = sub,
                effectiveHeaders = headers,
                media3MimeType = mime,
                requiresJsonToVttConversion = needsJsonConv,
            )
        }

        return PlaybackPlan(info = info, stream = streamPlan, subtitles = subtitlePlans)
    }

    /**
     * Builds the runnable Media3 [ExoMediaSource] from a [PlaybackInfo].
     */
    fun createMediaSource(
        info: PlaybackInfo,
        selectedQualityId: String? = null,
        durationMs: Long? = null,
    ): ExoMediaSource {
        val plan = plan(info, selectedQualityId, durationMs)
        return createMediaSource(plan)
    }

    fun createMediaSource(plan: PlaybackPlan): ExoMediaSource {
        val primarySource: ExoMediaSource = when (val s = plan.stream) {
            is ResolvedStreamPlan.DirectUrl -> buildDirectUrlSource(s)
            is ResolvedStreamPlan.SynthesizedDashMpd -> buildMpdSource(s.mpdXml, s.baseUrl, s.headers)
            is ResolvedStreamPlan.InlineMpd -> buildMpdSource(s.mpdXml, s.baseUrl, s.headers)
            is ResolvedStreamPlan.MergedProgressiveTracks -> buildMergedTrackSource(s)
        }

        if (plan.subtitles.isEmpty()) {
            return primarySource
        }

        val sources = ArrayList<ExoMediaSource>(1 + plan.subtitles.size)
        sources += primarySource
        for (sub in plan.subtitles) {
            val baseSubFactory = httpFactoryProvider(sub.effectiveHeaders)
            val subDataSourceFactory = if (sub.requiresJsonToVttConversion) {
                DataSource.Factory { JsonSubtitleVttDataSource(baseSubFactory) }
            } else {
                baseSubFactory
            }
            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.track.url))
                .setId(sub.track.id)
                .setLabel(sub.track.label)
                .setMimeType(sub.media3MimeType)
                .apply {
                    sub.track.lang?.let(::setLanguage)
                    if (sub.track.isDefault) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                }
                .build()
            sources += SingleSampleMediaSource.Factory(subDataSourceFactory)
                .createMediaSource(subConfig, C.TIME_UNSET)
        }
        return MergingMediaSource(*sources.toTypedArray())
    }

    private fun planDash(
        dash: DashStream,
        headers: Map<String, String>,
        isLive: Boolean,
        selectedQualityId: String?,
        durationMs: Long?,
    ): ResolvedStreamPlan {
        if (dash.video.isEmpty()) {
            throw SourceUnsupportedException("这个视频缺少可用的视频轨，无法播放。")
        }
        for (track in dash.video + dash.audio) {
            validateUrlIfNeeded(track.url)
            track.backupUrls.forEach(::validateUrlIfNeeded)
        }

        val matchingVideo = if (selectedQualityId != null) {
            dash.video.filter { it.quality == selectedQualityId }.ifEmpty { dash.video }
        } else {
            dash.video
        }
        val filteredDash = DashStream(video = matchingVideo, audio = dash.audio)

        return if (DashMpdSynthesizer.hasSegmentRanges(filteredDash)) {
            val mpdXml = DashMpdSynthesizer.synthesize(filteredDash, isLive = isLive, durationMs = durationMs)
            ResolvedStreamPlan.SynthesizedDashMpd(
                mpdXml = mpdXml,
                baseUrl = matchingVideo.first().url,
                headers = headers,
                videoBackupChains = matchingVideo.map { listOf(it.url) + it.backupUrls },
                audioBackupChains = dash.audio.map { listOf(it.url) + it.backupUrls },
            )
        } else {
            val chosenVideo = matchingVideo.maxByOrNull { it.bandwidth } ?: matchingVideo.first()
            val chosenAudio = dash.audio.maxByOrNull { it.bandwidth }
            ResolvedStreamPlan.MergedProgressiveTracks(
                videoUrls = listOf(chosenVideo.url) + chosenVideo.backupUrls,
                audioUrls = chosenAudio?.let { listOf(it.url) + it.backupUrls } ?: emptyList(),
                videoMime = chosenVideo.mime,
                audioMime = chosenAudio?.mime,
                headers = headers,
            )
        }
    }

    private fun buildDirectUrlSource(plan: ResolvedStreamPlan.DirectUrl): ExoMediaSource {
        val dsFactory = httpFactoryProvider(plan.headers)
        val item = MediaItem.Builder()
            .setUri(Uri.parse(plan.url))
            .apply { plan.mimeType?.let(::setMimeType) }
            .build()
        return when (plan.streamType) {
            DirectStreamType.HLS -> HlsMediaSource.Factory(dsFactory).createMediaSource(item)
            DirectStreamType.DASH -> DashMediaSource.Factory(dsFactory)
                .setLoadErrorHandlingPolicy(LocationFallbackLoadErrorPolicy())
                .createMediaSource(item)
            DirectStreamType.PROGRESSIVE -> ProgressiveMediaSource.Factory(dsFactory).createMediaSource(item)
        }
    }

    private fun buildMpdSource(
        mpdXml: String,
        baseUrl: String,
        headers: Map<String, String>,
    ): ExoMediaSource {
        val dsFactory = httpFactoryProvider(headers)
        val manifest = DashManifestParser().parse(
            Uri.parse(baseUrl),
            ByteArrayInputStream(mpdXml.toByteArray(Charsets.UTF_8)),
        )
        return DashMediaSource.Factory(dsFactory)
            .setLoadErrorHandlingPolicy(LocationFallbackLoadErrorPolicy())
            .createMediaSource(manifest)
    }

    private fun buildMergedTrackSource(plan: ResolvedStreamPlan.MergedProgressiveTracks): ExoMediaSource {
        val baseFactory = httpFactoryProvider(plan.headers)
        val videoSelector = FailoverUrlSelector(plan.videoUrls)
        val videoSource = ProgressiveMediaSource.Factory(
            DataSource.Factory { FailoverUrlDataSource(baseFactory, videoSelector) },
        ).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(plan.videoUrls.first())).setMimeType(plan.videoMime).build(),
        )
        if (plan.audioUrls.isEmpty()) {
            return videoSource
        }
        val audioSelector = FailoverUrlSelector(plan.audioUrls)
        val audioSource = ProgressiveMediaSource.Factory(
            DataSource.Factory { FailoverUrlDataSource(baseFactory, audioSelector) },
        ).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(plan.audioUrls.first()))
                .apply { plan.audioMime?.let(::setMimeType) }
                .build(),
        )
        return MergingMediaSource(videoSource, audioSource)
    }

    private fun validateUrlIfNeeded(url: String) {
        networkPolicy?.validatePlaybackUrlSyntax(url)
    }

    private class LocationFallbackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getFallbackSelectionFor(
            fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ): LoadErrorHandlingPolicy.FallbackSelection? {
            if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)) {
                return LoadErrorHandlingPolicy.FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                    DEFAULT_LOCATION_EXCLUSION_MS,
                )
            }
            return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "depth3d/1.0 (Android; OpenXR)"

        fun detectDirectStreamType(url: String, mimeHint: String?): DirectStreamType {
            val mime = mimeHint?.lowercase(Locale.ROOT)?.trim()
            if (mime != null) {
                if (mime.contains("mpegurl") || mime == MimeTypes.APPLICATION_M3U8) return DirectStreamType.HLS
                if (mime.contains("dash+xml") || mime == MimeTypes.APPLICATION_MPD) return DirectStreamType.DASH
            }
            val path = try {
                URI(url).path?.lowercase(Locale.ROOT) ?: url.lowercase(Locale.ROOT)
            } catch (_: Exception) {
                url.lowercase(Locale.ROOT)
            }
            return when {
                path.endsWith(".m3u8") || path.contains(".m3u8?") -> DirectStreamType.HLS
                path.endsWith(".mpd") || path.contains(".mpd?") -> DirectStreamType.DASH
                else -> DirectStreamType.PROGRESSIVE
            }
        }

        private fun defaultMimeFor(type: DirectStreamType): String? = when (type) {
            DirectStreamType.HLS -> MimeTypes.APPLICATION_M3U8
            DirectStreamType.DASH -> MimeTypes.APPLICATION_MPD
            DirectStreamType.PROGRESSIVE -> null
        }
    }
}

/**
 * Result of resolving or refreshing a playback session, carrying the resolved
 * [PlaybackPlan] (and optionally a built Media3 source) plus resume position.
 */
data class ResolvedPlaybackSession(
    val info: PlaybackInfo,
    val plan: PlaybackPlan,
    val resumePositionMs: Long,
    val selectedQualityId: String?,
    val selectedAudioId: String?,
)

/**
 * Manages playback URL expiration (`expiresAt`), automatic re-fetching on
 * expiry or CDN 403/410 failure, and quality/audio switching (including locked
 * quality guards per protocol §3).
 */
class PlaybackSessionController(
    private val fetchPlayback: suspend (itemId: String, quality: String?, audio: String?) -> PlaybackInfo,
    private val factory: PlaybackMediaSourceFactory = PlaybackMediaSourceFactory(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val refreshLeadMs: Long = DEFAULT_REFRESH_LEAD_MS,
) {
    constructor(
        source: PluginMediaSource,
        factory: PlaybackMediaSourceFactory = PlaybackMediaSourceFactory(),
        clockMs: () -> Long = System::currentTimeMillis,
        refreshLeadMs: Long = DEFAULT_REFRESH_LEAD_MS,
    ) : this(
        fetchPlayback = { id, q, a -> source.playback(id, quality = q, audio = a) },
        factory = factory,
        clockMs = clockMs,
        refreshLeadMs = refreshLeadMs,
    )

    @Volatile
    private var currentItemId: String? = null
    @Volatile
    private var currentQuality: String? = null
    @Volatile
    private var currentAudio: String? = null
    @Volatile
    private var currentInfo: PlaybackInfo? = null

    fun isExpired(nowMs: Long = clockMs()): Boolean {
        val exp = currentInfo?.expiresAtMs ?: return false
        return nowMs >= exp - refreshLeadMs
    }

    /**
     * Loads playback info for [itemId], or reuses the cached one if it belongs
     * to the same item/quality/audio and has not expired.
     */
    suspend fun load(
        itemId: String,
        quality: String? = null,
        audio: String? = null,
        resumePositionMs: Long? = null,
        forceRefresh: Boolean = false,
    ): ResolvedPlaybackSession {
        val sameRequest = !forceRefresh &&
            currentInfo != null &&
            currentItemId == itemId &&
            currentQuality == quality &&
            currentAudio == audio &&
            !isExpired()

        val info = if (sameRequest) {
            currentInfo!!
        } else {
            val fresh = fetchPlayback(itemId, quality, audio)
            currentItemId = itemId
            currentQuality = quality ?: fresh.qualities.firstOrNull { it.selected && it.locked == null }?.id
            currentAudio = audio
            currentInfo = fresh
            fresh
        }

        val plan = factory.plan(info, selectedQualityId = currentQuality)
        return ResolvedPlaybackSession(
            info = info,
            plan = plan,
            resumePositionMs = resumePositionMs ?: info.startPositionMs,
            selectedQualityId = currentQuality,
            selectedAudioId = currentAudio,
        )
    }

    /**
     * Switches to [qualityId], rejecting locked qualities with
     * [SourceForbiddenException] carrying the plugin's `locked` reason.
     */
    suspend fun switchQuality(qualityId: String, currentPositionMs: Long): ResolvedPlaybackSession {
        val itemId = currentItemId ?: throw SourceUnsupportedException("当前没有正在播放的内容。")
        val option = currentInfo?.qualities?.firstOrNull { it.id == qualityId }
        if (option?.locked != null) {
            throw SourceForbiddenException(option.locked)
        }
        return load(
            itemId = itemId,
            quality = qualityId,
            audio = currentAudio,
            resumePositionMs = currentPositionMs,
            forceRefresh = true,
        )
    }

    /**
     * Refreshes an expired URL (or recovers from an HTTP 403/410 CDN expiry
     * mid-playback) while preserving [currentPositionMs].
     */
    suspend fun refreshIfExpiredOrFailed(
        currentPositionMs: Long,
        httpStatusCode: Int? = null,
    ): ResolvedPlaybackSession? {
        val itemId = currentItemId ?: return null
        val shouldRefetch = isExpired() || httpStatusCode == 401 || httpStatusCode == 403 || httpStatusCode == 410
        if (!shouldRefetch) return null
        return load(
            itemId = itemId,
            quality = currentQuality,
            audio = currentAudio,
            resumePositionMs = currentPositionMs,
            forceRefresh = true,
        )
    }

    companion object {
        const val DEFAULT_REFRESH_LEAD_MS = 30_000L
    }
}
