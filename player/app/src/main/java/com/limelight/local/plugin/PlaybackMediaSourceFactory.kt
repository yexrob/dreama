package com.limelight.local.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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
import com.limelight.local.media.Artwork
import com.limelight.local.media.DashStream
import com.limelight.local.media.Item
import com.limelight.local.media.MediaTrack
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceUnsupportedException
import com.limelight.local.media.SubtitleFormat
import com.limelight.local.media.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The ONLY public playback entry point for plugin sources (§11.3).
 *
 * Player code (`LocalPlayerXR` / future plugin playback wiring) MUST build
 * Media3 [ExoMediaSource]s exclusively through [PluginPlayback] so every
 * manifest, segment, backup URL, redirect hop, and subtitle request is forced
 * through [SafePluginHttpDataSource], [FailoverUrlDataSource], and the
 * instance's [NetworkPolicy]. Callers must NEVER read `PlaybackInfo.url` /
 * `PlaybackInfo.headers` to construct a raw `DefaultHttpDataSource`.
 */
@OptIn(UnstableApi::class)
object PluginPlayback {
    fun createMediaSource(
        context: Context? = null,
        source: PluginMediaSource,
        playbackInfo: PlaybackInfo,
        selectedQualityId: String? = null,
        selectedAudioId: String? = null,
        fallbackDurationMs: Long? = null,
    ): ExoMediaSource {
        // context reserved for future local cache / Media3 integration; all network I/O
        // goes strictly through source's policy-bound factory.
        val factory = source.createPlaybackFactory()
        return factory.createMediaSource(
            info = playbackInfo,
            selectedQualityId = selectedQualityId,
            selectedAudioId = selectedAudioId,
            fallbackDurationMs = fallbackDurationMs,
        )
    }

    fun createSessionController(
        source: PluginMediaSource,
        clockMs: () -> Long = System::currentTimeMillis,
    ): PlaybackSessionController = source.createPlaybackSessionController(clockMs = clockMs)
}

/**
 * The ONLY public image loading entry point for plugin artwork (`poster`,
 * `thumb`, `backdrop`, `avatar`) (§11.3).
 *
 * Wall UI code MUST load plugin [Artwork] exclusively through [PluginImages.loadBitmap]
 * (NEVER via `Artworks.load`, raw `OkHttpClient`, or `MediaMetadataRetriever`).
 * Every image URL and redirect hop is enforced against the instance's
 * [NetworkPolicy] with DNS pinning, HTTPS downgrade blocking, cross-origin
 * header stripping, 16 MB download cap, and `inJustDecodeBounds` pixel-count
 * limiting BEFORE decoding pixels into memory.
 */
object PluginImages {
    const val DEFAULT_MAX_EDGE_PX: Int = 1280
    const val MAX_DECODED_PIXELS: Long = 16_000_000L
    const val MAX_RAW_EDGE_PX: Int = 16_384
    const val MAX_RAW_SOURCE_PIXELS: Long = 64_000_000L

    suspend fun loadBitmap(
        source: PluginMediaSource,
        artwork: Artwork,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
        maxPixels: Long = MAX_DECODED_PIXELS,
    ): Bitmap? {
        if (artwork.isVideoFrame) {
            throw IllegalArgumentException(
                "插件图片来源禁止使用 isVideoFrame / MediaMetadataRetriever 路径，请使用普通图片地址或占位图",
            )
        }
        val bytes = source.createImageFetcher().fetchBytes(artwork)
        return decodeBoundedBitmap(bytes, maxEdgePx = maxEdgePx, maxPixels = maxPixels)
    }

    internal fun decodeBoundedBitmap(
        bytes: ByteArray,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
        maxPixels: Long = MAX_DECODED_PIXELS,
    ): Bitmap? {
        require(maxEdgePx > 0) { "maxEdgePx must be positive" }
        require(maxPixels > 0L) { "maxPixels must be positive" }
        if (bytes.isEmpty()) return null

        // Fast header guard for PNG IHDR bombs before touching platform decoders
        peekPngDimensions(bytes)?.let { (pngW, pngH) ->
            val pngPixels = pngW.toLong() * pngH.toLong()
            if (pngW <= 0 || pngH <= 0 || pngW > MAX_RAW_EDGE_PX || pngH > MAX_RAW_EDGE_PX || pngPixels > MAX_RAW_SOURCE_PIXELS) {
                throw IOException("图片尺寸过大（${pngW}x${pngH}），已拒绝解码以保护内存")
            }
        }

        // Pass 1: inspect dimensions via inJustDecodeBounds BEFORE allocating pixel buffers
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null
        val rawPixels = rawW.toLong() * rawH.toLong()
        if (rawW > MAX_RAW_EDGE_PX || rawH > MAX_RAW_EDGE_PX || rawPixels > MAX_RAW_SOURCE_PIXELS) {
            throw IOException("图片尺寸过大（${rawW}x${rawH}），已拒绝解码以保护内存")
        }

        // Compute power-of-2 inSampleSize so decoded dimensions fit BOTH maxEdgePx and maxPixels
        var sampleSize = 1
        while (true) {
            val estW = (rawW + sampleSize - 1) / sampleSize
            val estH = (rawH + sampleSize - 1) / sampleSize
            val estPixels = estW.toLong() * estH.toLong()
            if (estW <= maxEdgePx && estH <= maxEdgePx && estPixels <= maxPixels) {
                break
            }
            val next = sampleSize * 2
            if (next <= 0 || next > 64) break
            sampleSize = next
        }

        // Pass 2: decode downsampled bitmap with RGB_565 to cap memory footprint
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        val longEdge = maxOf(decoded.width, decoded.height)
        val decodedPixels = decoded.width.toLong() * decoded.height.toLong()
        if (longEdge <= maxEdgePx && decodedPixels <= maxPixels) {
            return decoded
        }
        val edgeScale = maxEdgePx.toDouble() / longEdge.toDouble()
        val pixelScale = kotlin.math.sqrt(maxPixels.toDouble() / decodedPixels.toDouble())
        val scale = minOf(edgeScale, pixelScale).coerceAtMost(1.0)
        val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
        val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled !== decoded) {
            decoded.recycle()
        }
        return scaled
    }

    private fun peekPngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        // 8-byte PNG signature + 4-byte len + 4-byte "IHDR" + 4-byte width + 4-byte height = 24 bytes
        if (bytes.size < 24) return null
        if (bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte() || bytes[2] != 0x4E.toByte() || bytes[3] != 0x47.toByte() ||
            bytes[4] != 0x0D.toByte() || bytes[5] != 0x0A.toByte() || bytes[6] != 0x1A.toByte() || bytes[7] != 0x0A.toByte()
        ) {
            return null
        }
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val w = ((bytes[16].toInt() and 0xFF) shl 24) or
            ((bytes[17].toInt() and 0xFF) shl 16) or
            ((bytes[18].toInt() and 0xFF) shl 8) or
            (bytes[19].toInt() and 0xFF)
        val h = ((bytes[20].toInt() and 0xFF) shl 24) or
            ((bytes[21].toInt() and 0xFF) shl 16) or
            ((bytes[22].toInt() and 0xFF) shl 8) or
            (bytes[23].toInt() and 0xFF)
        return w to h
    }
}

/**
 * Runtime boundary guards for host UI / player / DLNA entry points outside
 * `com.limelight.local.plugin` (`docs/specs/plugin-host-integration.md`).
 *
 * Host screens (`Components.kt:298`, `WallActivity.kt:113-124`,
 * `LocalPlayerXR.java:145,450-475`, `local/dlna/`, `local/wall/CastScreen.kt`)
 * MUST invoke these guards at their entry points (or branch on `isPlugin*` to
 * dispatch to [PluginImages.loadBitmap] / [PluginPlayback]) so a plugin item
 * can NEVER silently fall into raw HTTP, `DefaultHttpDataSource`,
 * `MediaMetadataRetriever`, or DLNA cast paths.
 */
object PluginHostGuards {
    @JvmStatic
    fun isPluginSource(sourceKind: SourceKind?): Boolean = sourceKind == SourceKind.PLUGIN

    @JvmStatic
    fun isPluginArtwork(artwork: Artwork): Boolean = artwork.sourceKind == SourceKind.PLUGIN

    @JvmStatic
    fun isPluginPlayback(info: PlaybackInfo): Boolean = info.sourceKind == SourceKind.PLUGIN

    @JvmStatic
    fun requireNonPluginArtwork(artwork: Artwork, sourceKind: SourceKind? = artwork.sourceKind) {
        if (sourceKind == SourceKind.PLUGIN || artwork.sourceKind == SourceKind.PLUGIN) {
            throw UnsupportedOperationException(
                "插件来源图片禁止走 Artworks.load / 裸 HTTP 加载，请改用 PluginImages.loadBitmap(source, artwork, maxEdgePx)",
            )
        }
    }

    @JvmStatic
    fun requireNonPluginPlayback(info: PlaybackInfo, sourceKind: SourceKind? = info.sourceKind) {
        if (sourceKind == SourceKind.PLUGIN || info.sourceKind == SourceKind.PLUGIN) {
            throw UnsupportedOperationException(
                "插件来源播放禁止走 WallActivity.launchPlayer / DefaultHttpDataSource，请改用 PluginPlayback.createMediaSource 或 PluginPlayback.createSessionController",
            )
        }
    }

    @JvmStatic
    fun requireNonPluginFrameProbe(sourceKind: SourceKind?, artwork: Artwork? = null, info: PlaybackInfo? = null) {
        if (sourceKind == SourceKind.PLUGIN ||
            artwork?.sourceKind == SourceKind.PLUGIN ||
            info?.sourceKind == SourceKind.PLUGIN
        ) {
            throw UnsupportedOperationException(
                "插件来源禁止走 MediaMetadataRetriever / MediaExtractor 远程探测或取帧，请改用 PluginImages.loadBitmap（或占位图）与 ExoPlayer Format 回调",
            )
        }
    }

    @JvmStatic
    fun requireNonPluginCast(sourceKind: SourceKind?, info: PlaybackInfo? = null, item: Item? = null) {
        val itemHasPluginArt = item != null && listOfNotNull(item.poster, item.thumb, item.backdrop)
            .any { it.sourceKind == SourceKind.PLUGIN }
        if (sourceKind == SourceKind.PLUGIN || info?.sourceKind == SourceKind.PLUGIN || itemHasPluginArt) {
            throw UnsupportedOperationException(
                "插件来源禁止交给 DLNA 投屏（local/dlna/、CastScreen.kt）或任何包外组件，仅允许通过 PluginPlayback 在应用内播放",
            )
        }
    }
}

internal sealed interface ResolvedStreamPlan {
    data class DirectUrl(
        val url: String,
        val streamType: DirectStreamType,
        val mimeType: String?,
        val headers: Map<String, String>,
        val trustedOrigins: Set<String>,
    ) : ResolvedStreamPlan

    data class SynthesizedDashMpd(
        val mpdXml: String,
        val baseUrl: String,
        val headers: Map<String, String>,
        val trustedOrigins: Set<String>,
        val videoBackupChains: List<List<String>>,
        val audioBackupChains: List<List<String>>,
    ) : ResolvedStreamPlan

    data class MergedProgressiveTracks(
        val videoUrls: List<String>,
        val audioUrls: List<String>,
        val videoMime: String,
        val audioMime: String?,
        val headers: Map<String, String>,
        val trustedOrigins: Set<String>,
    ) : ResolvedStreamPlan

    data class InlineMpd(
        val mpdXml: String,
        val baseUrl: String,
        val headers: Map<String, String>,
        val trustedOrigins: Set<String>,
    ) : ResolvedStreamPlan
}

internal enum class DirectStreamType { PROGRESSIVE, HLS, DASH }

internal data class ResolvedSubtitlePlan(
    val track: SubtitleTrack,
    val effectiveHeaders: Map<String, String>,
    val trustedOrigins: Set<String>,
    val media3MimeType: String,
    val requiresJsonToVttConversion: Boolean,
)

internal data class PlaybackPlan(
    val info: PlaybackInfo,
    val stream: ResolvedStreamPlan,
    val subtitles: List<ResolvedSubtitlePlan>,
)

/**
 * Synthesizes a standards-compliant DVB-DASH MPD XML document from protocol §3
 * `dash: { video: Track[], audio: Track[] }` streams with explicit ascending
 * `dvb:priority` (1 = primary, 2+ = backups) and `mediaPresentationDuration`
 * so Media3 deterministically prefers the primary CDN and fails over to backups
 * in order (B3, S6, §11.2).
 */
object DashMpdSynthesizer {
    fun hasSegmentRanges(dash: DashStream): Boolean {
        val allTracks = dash.video + dash.audio
        return allTracks.isNotEmpty() && allTracks.all {
            !it.initRange.isNullOrBlank() && !it.indexRange.isNullOrBlank()
        }
    }

    fun synthesize(dash: DashStream, durationMs: Long): String {
        require(dash.video.isNotEmpty()) { "DASH stream must contain at least one video track" }
        require(durationMs > 0L) { "DASH stream requires positive durationMs (§11.2)" }
        val sb = StringBuilder(2048)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
                "xmlns:dvb=\"urn:dvb:dash:profile:dvb-dash:2014\" " +
                "type=\"static\" mediaPresentationDuration=\"${formatIsoDuration(durationMs)}\" " +
                "minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n",
        )
        sb.append("  <Period id=\"0\" duration=\"${formatIsoDuration(durationMs)}\">\n")

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

            val urls = (listOf(track.url) + track.backupUrls).distinct()
            urls.forEachIndexed { idx, candidate ->
                val priority = idx + 1
                sb.append(
                    "        <BaseURL dvb:priority=\"$priority\" dvb:weight=\"1\" " +
                        "serviceLocation=\"cdn-$idx\">${xmlEscape(candidate)}</BaseURL>\n",
                )
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
 * Converts JSON subtitle payloads (`format: "json"`) into WebVTT.
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
 * Shared OkHttp executor used by BOTH [SafePluginHttpDataSource] (for Media3
 * streams, manifests, segments, subtitles) and [PluginImageFetcher] (for
 * plugin artwork).
 *
 * Enforces protocol §11.3 on EVERY request and EVERY redirect hop:
 * - Resolves and validates via [NetworkPolicy.validateResourceUrl]
 * - Connects strictly to the validated IP with `.proxy(Proxy.NO_PROXY)`
 * - Rejects HTTPS -> HTTP downgrade redirects
 * - Sends plugin-supplied headers ONLY to origins explicitly trusted for this
 *   resource; strips `Authorization`, `Cookie`, and all plugin custom headers on
 *   any untrusted origin or cross-origin redirect
 */
internal class SafePluginHttpExecutor(
    private val networkPolicy: NetworkPolicy,
    baseClient: OkHttpClient = DEFAULT_CLIENT,
    private val userAgent: String = PlaybackMediaSourceFactory.DEFAULT_USER_AGENT,
    private val maxRedirects: Int = 10,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun openStream(
        rawUrl: String,
        pluginHeaders: Map<String, String>,
        trustedOrigins: Set<String>,
        rangeHeader: String? = null,
        totalTimeoutMs: Long? = null,
    ): Response {
        var currentUrl = rawUrl
        var prevScheme: String? = null
        var hops = 0
        val activePluginHeaders = LinkedHashMap(pluginHeaders)
        val deadlineNanos = totalTimeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) }

        while (true) {
            val remainingMs = deadlineNanos?.let {
                val rem = TimeUnit.NANOSECONDS.toMillis(it - System.nanoTime())
                if (rem <= 0L) {
                    throw SocketTimeoutException("下载总耗时超过 ${totalTimeoutMs / 1000} 秒上限")
                }
                rem
            }
            val validated = try {
                networkPolicy.validateResourceUrl(currentUrl, previousScheme = prevScheme)
            } catch (e: NetworkPolicyException) {
                throw IOException(e.message, e)
            }

            val currentOrigin = NetworkPolicy.originKey(validated.url)
            val sendPluginHeaders = currentOrigin in trustedOrigins

            val pinnedDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (NetworkPolicy.canonicalizeHost(hostname) == validated.canonicalHost) {
                        return validated.resolvedAddresses
                    }
                    return networkPolicy.validateResourceUrl("https://$hostname/").resolvedAddresses
                }
            }
            val hopClientBuilder = client.newBuilder().dns(pinnedDns)
            if (remainingMs != null) {
                hopClientBuilder
                    .connectTimeout(minOf(remainingMs, 10_000L), TimeUnit.MILLISECONDS)
                    .readTimeout(remainingMs, TimeUnit.MILLISECONDS)
                    .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
            }
            val hopClient = hopClientBuilder.build()

            val reqBuilder = Request.Builder().url(validated.url).get()
            var hasUa = false
            if (sendPluginHeaders) {
                for ((k, v) in activePluginHeaders) {
                    if (k.equals("Host", ignoreCase = true)) continue
                    if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                    reqBuilder.header(k, v)
                }
            }
            if (!hasUa) reqBuilder.header("User-Agent", userAgent)
            if (rangeHeader != null) reqBuilder.header("Range", rangeHeader)

            val resp = hopClient.newCall(reqBuilder.build()).execute()
            val code = resp.code
            val location = resp.header("Location")
            if (code in REDIRECT_CODES && location != null) {
                resp.close()
                hops += 1
                if (hops > maxRedirects) {
                    throw IOException("重定向次数过多（超过 $maxRedirects 次）")
                }
                val resolved = validated.url.resolve(location)
                    ?: throw IOException("无效的重定向目标地址")
                val nextOrigin = NetworkPolicy.originKey(resolved)
                if (nextOrigin != currentOrigin) {
                    // §11.3: strip Authorization, Cookie, and ALL plugin custom headers on cross-origin redirect
                    activePluginHeaders.clear()
                }
                prevScheme = validated.url.scheme
                currentUrl = resolved.toString()
                continue
            }
            return resp
        }
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Media3 [DataSource] enforcing [NetworkPolicy] on every request and redirect
 * hop (MPD BaseURL/SegmentTemplate, HLS sub-playlists & segments, progressive
 * streams, backup URLs, subtitles) and scoping `PlaybackInfo.headers` strictly
 * to plugin-declared origins (§11.3, B1).
 */
@OptIn(UnstableApi::class)
internal class SafePluginHttpDataSource(
    private val executor: SafePluginHttpExecutor,
    private val pluginHeaders: Map<String, String>,
    private val trustedOrigins: Set<String>,
) : BaseDataSource(/* isNetwork = */ true) {

    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesRead: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val rawUrl = dataSpec.uri.toString()
        val range = buildRangeHeader(dataSpec.position, dataSpec.length)
        val resp = executor.openStream(rawUrl, pluginHeaders, trustedOrigins, range)
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            val headersMap = resp.headers.toMultimap()
            resp.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                msg,
                null,
                headersMap,
                dataSpec,
                Util.EMPTY_BYTE_ARRAY,
            )
        }
        val stream = resp.body.byteStream()
        var contentLength = resp.body.contentLength()
        // If we asked for Range starting > 0 but the server ignored Range and returned 200 OK,
        // skip the prefix bytes so mid-stream failover resume stays byte-accurate.
        if (dataSpec.position > 0L && resp.code == 200) {
            try {
                skipFully(stream, dataSpec.position)
                if (contentLength >= 0L) {
                    contentLength = (contentLength - dataSpec.position).coerceAtLeast(0L)
                }
            } catch (e: IOException) {
                runCatching { stream.close() }
                runCatching { resp.close() }
                throw e
            }
        }
        response = resp
        inputStream = stream
        openedUri = runCatching { Uri.parse(resp.request.url.toString()) }.getOrNull() ?: dataSpec.uri
        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            if (contentLength >= 0L) minOf(dataSpec.length, contentLength) else dataSpec.length
        } else if (contentLength >= 0L) {
            contentLength
        } else {
            C.LENGTH_UNSET.toLong()
        }
        bytesRead = 0
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0L) return C.RESULT_END_OF_INPUT
            val capped = minOf(length.toLong(), remaining).toInt()
            val n = inputStream?.read(buffer, offset, capped) ?: return C.RESULT_END_OF_INPUT
            if (n < 0) {
                // Server closed the stream before delivering declared Content-Length -> signal I/O error
                // so DASH / FailoverUrlDataSource can fail over to a backup URL mid-stream!
                throw EOFException("Unexpected EOF after $bytesRead of $bytesToRead bytes")
            }
            bytesRead += n
            bytesTransferred(n)
            return n
        }
        val n = inputStream?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesRead += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun close() {
        val stream = inputStream
        val resp = response
        inputStream = null
        response = null
        openedUri = null
        if (stream != null || resp != null) {
            try {
                stream?.close()
            } finally {
                resp?.close()
                transferEnded()
            }
        }
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buf = ByteArray(8192)
        while (remaining > 0L) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("Unexpected EOF while skipping to resume offset $bytesToSkip")
            remaining -= n
        }
    }

    private fun buildRangeHeader(position: Long, length: Long): String? {
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
        return if (length == C.LENGTH_UNSET.toLong()) {
            "bytes=$position-"
        } else {
            "bytes=$position-${position + length - 1}"
        }
    }
}

/**
 * Safe image fetcher enforcing the exact same §11.3 [NetworkPolicy], DNS
 * pinning, redirect-hop validation, HTTPS downgrade block, and cross-origin
 * header stripping for plugin artwork (`poster`, `thumb`, `backdrop`, `avatar`).
 */
internal class PluginImageFetcher(
    networkPolicy: NetworkPolicy,
    baseClient: OkHttpClient = OkHttpClient(),
    private val maxImageBytes: Int = MAX_IMAGE_BYTES,
) {
    private val executor = SafePluginHttpExecutor(networkPolicy, baseClient)

    suspend fun fetchBytes(artwork: Artwork): ByteArray = withContext(Dispatchers.IO) {
        val parsed = artwork.url.trim().toHttpUrlOrNull()
            ?: throw NetworkPolicyException("不支持或无效的图片地址")
        val trustedOrigins = setOf(NetworkPolicy.originKey(parsed))
        executor.openStream(artwork.url, artwork.headers, trustedOrigins).use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("图片请求失败（HTTP ${resp.code}）")
            }
            val len = resp.body.contentLength()
            if (len > maxImageBytes) {
                throw IOException("图片超过大小限制（上限 ${maxImageBytes / (1024 * 1024)} MB）")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0L
            resp.body.byteStream().use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxImageBytes) {
                        throw IOException("图片超过大小限制（上限 ${maxImageBytes / (1024 * 1024)} MB）")
                    }
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    }
}

/**
 * Stateful candidate-URL failover selector used by [FailoverUrlDataSource]
 * (for plain separated progressive video/audio tracks without DASH byte ranges).
 */
internal class FailoverUrlSelector(
    urls: List<String>,
) {
    private val candidates: List<String> = urls.distinct().also {
        require(it.isNotEmpty()) { "At least one candidate URL is required" }
    }
    @Volatile
    private var preferredIndex: Int = 0

    fun <T> executeWithFailover(
        excludeIndices: Set<Int> = emptySet(),
        attempt: (index: Int, url: String) -> T,
    ): Pair<Int, T> {
        val start = preferredIndex
        var lastError: IOException? = null
        for (offset in candidates.indices) {
            val idx = (start + offset) % candidates.size
            if (idx in excludeIndices) continue
            val candidate = candidates[idx]
            try {
                val result = attempt(idx, candidate)
                preferredIndex = idx
                return idx to result
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All candidate URLs failed")
    }

    fun <T> executeWithFailover(attempt: (url: String) -> T): T =
        executeWithFailover(emptySet()) { _, url -> attempt(url) }.second

    fun currentPreferredUrl(): String = candidates[preferredIndex]
    fun allCandidates(): List<String> = candidates
}

/**
 * Failover [DataSource] supporting BOTH open-time failure failover AND
 * mid-stream disconnect/EOF failover (reopening the next candidate URL at
 * `baseDataSpec.position + bytesReadFromBase` using a `Range` request).
 */
@OptIn(UnstableApi::class)
internal class FailoverUrlDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val selector: FailoverUrlSelector,
) : BaseDataSource(/* isNetwork = */ true) {

    private var activeDelegate: DataSource? = null
    private var activeUri: Uri? = null
    private var activeCandidateIndex: Int = -1
    private var baseDataSpec: DataSpec? = null
    private var bytesReadFromBase: Long = 0L
    private val failedIndices = LinkedHashSet<Int>()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        failedIndices.clear()
        bytesReadFromBase = 0L
        baseDataSpec = dataSpec

        val (chosenIdx, opened) = selector.executeWithFailover(failedIndices) { idx, candidateUrl ->
            val targetUri = Uri.parse(candidateUrl)
            val delegate = upstreamFactory.createDataSource()
            try {
                val rewritten = dataSpec.buildUpon().setUri(targetUri).build()
                val len = delegate.open(rewritten)
                Triple(delegate, targetUri, len)
            } catch (e: IOException) {
                failedIndices += idx
                runCatching { delegate.close() }
                throw e
            }
        }
        activeCandidateIndex = chosenIdx
        activeDelegate = opened.first
        activeUri = opened.second
        transferStarted(dataSpec)
        return opened.third
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val delegate = activeDelegate ?: return C.RESULT_END_OF_INPUT
            try {
                val read = delegate.read(buffer, offset, length)
                if (read > 0) {
                    bytesReadFromBase += read
                    bytesTransferred(read)
                }
                return read
            } catch (e: IOException) {
                if (activeCandidateIndex >= 0) {
                    failedIndices += activeCandidateIndex
                }
                runCatching { delegate.close() }
                activeDelegate = null
                activeUri = null

                val spec = baseDataSpec ?: throw e
                val resumePosition = spec.position + bytesReadFromBase
                val remainingLength = if (spec.length != C.LENGTH_UNSET.toLong()) {
                    val rem = spec.length - bytesReadFromBase
                    if (rem <= 0L) return C.RESULT_END_OF_INPUT
                    rem
                } else {
                    C.LENGTH_UNSET.toLong()
                }

                // Attempt mid-stream failover to next untried candidate URL at exact byte offset
                val (nextIdx, reopened) = try {
                    selector.executeWithFailover(failedIndices) { idx, candidateUrl ->
                        val targetUri = Uri.parse(candidateUrl)
                        val nextDelegate = upstreamFactory.createDataSource()
                        try {
                            val resumeSpec = spec.buildUpon()
                                .setUri(targetUri)
                                .setPosition(resumePosition)
                                .setLength(remainingLength)
                                .build()
                            nextDelegate.open(resumeSpec)
                            nextDelegate to targetUri
                        } catch (openErr: IOException) {
                            failedIndices += idx
                            runCatching { nextDelegate.close() }
                            throw openErr
                        }
                    }
                } catch (_: IOException) {
                    throw e
                }
                activeCandidateIndex = nextIdx
                activeDelegate = reopened.first
                activeUri = reopened.second
            }
        }
    }

    override fun getUri(): Uri? = activeUri ?: activeDelegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        activeDelegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val delegate = activeDelegate
        val wasOpened = baseDataSpec != null
        activeDelegate = null
        activeUri = null
        activeCandidateIndex = -1
        baseDataSpec = null
        bytesReadFromBase = 0L
        failedIndices.clear()
        if (delegate != null) {
            try {
                delegate.close()
            } finally {
                transferEnded()
            }
        } else if (wasOpened) {
            transferEnded()
        }
    }
}

@OptIn(UnstableApi::class)
internal class JsonSubtitleVttDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val maxSubtitleBytes: Int = MAX_SUBTITLE_BYTES,
) : BaseDataSource(/* isNetwork = */ true) {
    private var converted: ByteArray? = null
    private var offset: Int = 0
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        openedUri = dataSpec.uri
        if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length > maxSubtitleBytes) {
            throw IOException("字幕文件超过大小上限（${maxSubtitleBytes / (1024 * 1024)} MB）")
        }
        val delegate = upstreamFactory.createDataSource()
        val raw = try {
            val contentLen = delegate.open(dataSpec)
            if (contentLen != C.LENGTH_UNSET.toLong() && contentLen > maxSubtitleBytes) {
                throw IOException("字幕文件超过大小上限（${maxSubtitleBytes / (1024 * 1024)} MB）")
            }
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var totalRead = 0L
            while (true) {
                val n = delegate.read(buf, 0, buf.size)
                if (n < 0) break
                totalRead += n
                if (totalRead > maxSubtitleBytes) {
                    throw IOException("字幕文件超过大小上限（${maxSubtitleBytes / (1024 * 1024)} MB）")
                }
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

    companion object {
        const val MAX_SUBTITLE_BYTES: Int = 4 * 1024 * 1024
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
 * `qualities`, `subtitles`, `expiresAt`, `durationMs`) into a Media3
 * [ExoMediaSource] with mandatory [NetworkPolicy] enforcement on every network
 * request and redirect hop (§11.3, B1, B3, S6).
 */
@OptIn(UnstableApi::class)
internal class PlaybackMediaSourceFactory(
    private val networkPolicy: NetworkPolicy,
    private val deviceCodecs: List<String> = listOf("avc", "hevc"),
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val httpExecutor = SafePluginHttpExecutor(networkPolicy, okHttpClient, userAgent)

    fun plan(
        info: PlaybackInfo,
        selectedQualityId: String? = null,
        selectedAudioId: String? = null,
        fallbackDurationMs: Long? = null,
    ): PlaybackPlan {
        // §11.2: live: true combined with dash is unsupported in v1
        if (info.isLive && info.dash != null) {
            throw SourceUnsupportedException("直播暂不支持音视频分离的 DASH 模式，请使用 HLS 等单地址流。")
        }

        val effectiveQuality = selectedQualityId
            ?: info.qualities.firstOrNull { it.selected && it.locked == null }?.id
        val effectiveDurationMs = info.durationMs ?: fallbackDurationMs

        val streamPlan: ResolvedStreamPlan = when {
            info.dash != null -> planDash(
                dash = info.dash,
                headers = info.headers,
                selectedQualityId = effectiveQuality,
                selectedAudioId = selectedAudioId,
                durationMs = effectiveDurationMs,
            )
            !info.mpd.isNullOrBlank() -> planInlineMpd(info.mpd, info.url, info.headers)
            info.url.isNotBlank() -> {
                val validated = networkPolicy.validateResourceUrlPrecheck(info.url)
                val streamType = detectDirectStreamType(info.url, info.mimeType)
                ResolvedStreamPlan.DirectUrl(
                    url = info.url,
                    streamType = streamType,
                    mimeType = info.mimeType ?: defaultMimeFor(streamType),
                    headers = info.headers,
                    trustedOrigins = setOf(NetworkPolicy.originKey(validated)),
                )
            }
            else -> throw SourceUnsupportedException("这个内容没有可用的播放地址，请尝试切换清晰度或片源。")
        }

        val mainStreamOrigins: Set<String> = when (streamPlan) {
            is ResolvedStreamPlan.DirectUrl -> streamPlan.trustedOrigins
            is ResolvedStreamPlan.SynthesizedDashMpd -> streamPlan.trustedOrigins
            is ResolvedStreamPlan.MergedProgressiveTracks -> streamPlan.trustedOrigins
            is ResolvedStreamPlan.InlineMpd -> streamPlan.trustedOrigins
        }

        val subtitlePlans = info.subtitles.map { sub ->
            val validatedSub = networkPolicy.validateResourceUrlPrecheck(sub.url)
            val subOrigin = NetworkPolicy.originKey(validatedSub)
            // §11.2 + §11.3: ONLY inherit PlaybackInfo.headers when the subtitle shares
            // the same origin as the main stream; cross-origin subtitles receive ONLY
            // their own track-declared `sub.headers`.
            val mergedHeaders = LinkedHashMap<String, String>()
            if (subOrigin in mainStreamOrigins) {
                mergedHeaders.putAll(info.headers)
            }
            mergedHeaders.putAll(sub.headers)
            val (mime, needsJsonConv) = when (sub.format) {
                SubtitleFormat.SRT -> MimeTypes.APPLICATION_SUBRIP to false
                SubtitleFormat.VTT -> MimeTypes.TEXT_VTT to false
                SubtitleFormat.ASS -> MimeTypes.TEXT_SSA to false
                SubtitleFormat.JSON -> MimeTypes.TEXT_VTT to true
            }
            ResolvedSubtitlePlan(
                track = sub,
                effectiveHeaders = mergedHeaders,
                trustedOrigins = setOf(subOrigin),
                media3MimeType = mime,
                requiresJsonToVttConversion = needsJsonConv,
            )
        }

        return PlaybackPlan(info = info, stream = streamPlan, subtitles = subtitlePlans)
    }

    fun createMediaSource(
        info: PlaybackInfo,
        selectedQualityId: String? = null,
        selectedAudioId: String? = null,
        fallbackDurationMs: Long? = null,
    ): ExoMediaSource = createMediaSource(plan(info, selectedQualityId, selectedAudioId, fallbackDurationMs))

    @Suppress("DEPRECATION")
    fun createMediaSource(plan: PlaybackPlan): ExoMediaSource {
        val primarySource: ExoMediaSource = when (val s = plan.stream) {
            is ResolvedStreamPlan.DirectUrl -> buildDirectUrlSource(s)
            is ResolvedStreamPlan.SynthesizedDashMpd -> buildMpdSource(s.mpdXml, s.baseUrl, s.headers, s.trustedOrigins)
            is ResolvedStreamPlan.InlineMpd -> buildMpdSource(s.mpdXml, s.baseUrl, s.headers, s.trustedOrigins)
            is ResolvedStreamPlan.MergedProgressiveTracks -> buildMergedTrackSource(s)
        }

        if (plan.subtitles.isEmpty()) {
            return primarySource
        }

        val sources = ArrayList<ExoMediaSource>(1 + plan.subtitles.size)
        sources += primarySource
        for (sub in plan.subtitles) {
            val baseSubFactory = safeDataSourceFactory(sub.effectiveHeaders, sub.trustedOrigins)
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

    /** Exposed for unit tests to verify real `SafePluginHttpDataSource` behavior. */
    fun safeDataSourceFactory(
        pluginHeaders: Map<String, String>,
        trustedOrigins: Set<String>,
    ): DataSource.Factory = DataSource.Factory {
        SafePluginHttpDataSource(httpExecutor, pluginHeaders, trustedOrigins)
    }

    private fun planDash(
        dash: DashStream,
        headers: Map<String, String>,
        selectedQualityId: String?,
        selectedAudioId: String?,
        durationMs: Long?,
    ): ResolvedStreamPlan {
        if (dash.video.isEmpty()) {
            throw SourceUnsupportedException("这个视频缺少可用的视频轨，无法播放。")
        }
        val trustedOrigins = LinkedHashSet<String>()
        for (track in dash.video + dash.audio) {
            trustedOrigins += NetworkPolicy.originKey(networkPolicy.validateResourceUrlPrecheck(track.url))
            for (backup in track.backupUrls) {
                trustedOrigins += NetworkPolicy.originKey(networkPolicy.validateResourceUrlPrecheck(backup))
            }
        }

        val matchingVideo = if (selectedQualityId != null) {
            dash.video.filter { it.quality == selectedQualityId }.ifEmpty { dash.video }
        } else {
            dash.video
        }
        val codecCompatibleVideo = matchingVideo.filter { isCodecSupported(it.codecs) }.ifEmpty { matchingVideo }
        val matchingAudio = if (selectedAudioId != null) {
            dash.audio.filter { it.id == selectedAudioId }.ifEmpty { dash.audio }
        } else {
            dash.audio
        }
        val filteredDash = DashStream(video = codecCompatibleVideo, audio = matchingAudio)

        return if (DashMpdSynthesizer.hasSegmentRanges(filteredDash)) {
            if (durationMs == null || durationMs <= 0L) {
                throw SourceUnsupportedException("DASH 音视频分离流缺少有效的视频时长信息。")
            }
            val mpdXml = DashMpdSynthesizer.synthesize(filteredDash, durationMs = durationMs)
            ResolvedStreamPlan.SynthesizedDashMpd(
                mpdXml = mpdXml,
                baseUrl = codecCompatibleVideo.first().url,
                headers = headers,
                trustedOrigins = trustedOrigins,
                videoBackupChains = codecCompatibleVideo.map { (listOf(it.url) + it.backupUrls).distinct() },
                audioBackupChains = matchingAudio.map { (listOf(it.url) + it.backupUrls).distinct() },
            )
        } else {
            // S6: select highest bandwidth among device-supported video codecs, and respect selectedAudioId
            val chosenVideo = codecCompatibleVideo.maxByOrNull { it.bandwidth } ?: codecCompatibleVideo.first()
            val chosenAudio = if (selectedAudioId != null) {
                dash.audio.firstOrNull { it.id == selectedAudioId } ?: dash.audio.maxByOrNull { it.bandwidth }
            } else {
                dash.audio.maxByOrNull { it.bandwidth }
            }
            ResolvedStreamPlan.MergedProgressiveTracks(
                videoUrls = (listOf(chosenVideo.url) + chosenVideo.backupUrls).distinct(),
                audioUrls = chosenAudio?.let { (listOf(it.url) + it.backupUrls).distinct() } ?: emptyList(),
                videoMime = chosenVideo.mime,
                audioMime = chosenAudio?.mime,
                headers = headers,
                trustedOrigins = trustedOrigins,
            )
        }
    }

    private fun planInlineMpd(
        mpdXml: String,
        fallbackBaseUrl: String,
        headers: Map<String, String>,
    ): ResolvedStreamPlan.InlineMpd {
        val extractedUrls = extractUrlsFromMpd(mpdXml)
        val trustedOrigins = LinkedHashSet<String>()
        for (u in extractedUrls) {
            val validated = networkPolicy.validateResourceUrlPrecheck(u)
            trustedOrigins += NetworkPolicy.originKey(validated)
        }
        val baseUrl = if (fallbackBaseUrl.isNotBlank()) {
            val v = networkPolicy.validateResourceUrlPrecheck(fallbackBaseUrl)
            trustedOrigins += NetworkPolicy.originKey(v)
            fallbackBaseUrl
        } else {
            extractedUrls.firstOrNull() ?: "https://inline.depth3d.invalid/manifest.mpd"
        }
        return ResolvedStreamPlan.InlineMpd(
            mpdXml = mpdXml,
            baseUrl = baseUrl,
            headers = headers,
            trustedOrigins = trustedOrigins,
        )
    }

    private fun buildDirectUrlSource(plan: ResolvedStreamPlan.DirectUrl): ExoMediaSource {
        val dsFactory = safeDataSourceFactory(plan.headers, plan.trustedOrigins)
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
        trustedOrigins: Set<String>,
    ): ExoMediaSource {
        val dsFactory = safeDataSourceFactory(headers, trustedOrigins)
        val manifest = DashManifestParser().parse(
            Uri.parse(baseUrl),
            ByteArrayInputStream(mpdXml.toByteArray(Charsets.UTF_8)),
        )
        return DashMediaSource.Factory(dsFactory)
            .setLoadErrorHandlingPolicy(LocationFallbackLoadErrorPolicy())
            .createMediaSource(manifest)
    }

    private fun buildMergedTrackSource(plan: ResolvedStreamPlan.MergedProgressiveTracks): ExoMediaSource {
        val baseFactory = safeDataSourceFactory(plan.headers, plan.trustedOrigins)
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

    private fun isCodecSupported(codecsStr: String): Boolean {
        val lower = codecsStr.lowercase(Locale.ROOT)
        val family = when {
            lower.startsWith("avc") || lower.contains("h264") -> "avc"
            lower.startsWith("hvc") || lower.startsWith("hev") || lower.contains("hevc") -> "hevc"
            lower.startsWith("av01") || lower.contains("av1") -> "av1"
            else -> return true
        }
        return deviceCodecs.any { it.equals(family, ignoreCase = true) }
    }

    class LocationFallbackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
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

        private val MPD_BASE_URL_REGEX = Regex("<BaseURL\\b[^>]*>\\s*([^<]+?)\\s*</BaseURL>", RegexOption.IGNORE_CASE)
        private val MPD_ATTR_URL_REGEX = Regex("""\b(?:media|initialization|sourceURL)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

        fun extractUrlsFromMpd(mpdXml: String): List<String> {
            val out = LinkedHashSet<String>()
            for (m in MPD_BASE_URL_REGEX.findAll(mpdXml)) {
                val u = unescapeXml(m.groupValues[1].trim())
                if (u.contains("://") || u.startsWith("file:") || u.startsWith("content:")) out += u
            }
            for (m in MPD_ATTR_URL_REGEX.findAll(mpdXml)) {
                val u = unescapeXml(m.groupValues[1].trim())
                if (u.contains("://") || u.startsWith("file:") || u.startsWith("content:")) out += u
            }
            return out.toList()
        }

        private fun unescapeXml(s: String): String = s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

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

@ConsistentCopyVisibility
data class ResolvedPlaybackSession internal constructor(
    val info: PlaybackInfo,
    internal val plan: PlaybackPlan,
    val resumePositionMs: Long,
    val selectedQualityId: String?,
    val selectedAudioId: String?,
)

/**
 * Manages playback URL expiration (`expiresAt` with §11.2 60-second lead time
 * and short-TTL non-looping guard), automatic re-fetching on expiry or HTTP
 * 401/403/410 failure, and quality/audio switching.
 *
 * Obtain instances via [PluginPlayback.createSessionController] and build
 * Media3 sources via [createMediaSource].
 */
@OptIn(UnstableApi::class)
class PlaybackSessionController internal constructor(
    private val fetchPlayback: suspend (itemId: String, quality: String?, audio: String?) -> PlaybackInfo,
    private val factory: PlaybackMediaSourceFactory,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val refreshLeadMs: Long = DEFAULT_REFRESH_LEAD_MS,
) {
    internal constructor(
        source: PluginMediaSource,
        factory: PlaybackMediaSourceFactory = source.createPlaybackFactory(),
        clockMs: () -> Long = System::currentTimeMillis,
        refreshLeadMs: Long = DEFAULT_REFRESH_LEAD_MS,
    ) : this(
        fetchPlayback = { id, q, a -> source.playback(id, quality = q, audio = a) },
        factory = factory,
        clockMs = clockMs,
        refreshLeadMs = refreshLeadMs,
    )

    /** Builds a policy-enforced Media3 [ExoMediaSource] from a resolved session. */
    fun createMediaSource(session: ResolvedPlaybackSession): ExoMediaSource =
        factory.createMediaSource(session.plan)

    @Volatile
    private var currentItemId: String? = null
    @Volatile
    private var currentQuality: String? = null
    @Volatile
    private var currentAudio: String? = null
    @Volatile
    private var currentInfo: PlaybackInfo? = null
    @Volatile
    private var nextRefreshAtMs: Long? = null

    fun isExpired(nowMs: Long = clockMs()): Boolean {
        val threshold = nextRefreshAtMs ?: return false
        return nowMs >= threshold
    }

    suspend fun load(
        itemId: String,
        quality: String? = null,
        audio: String? = null,
        resumePositionMs: Long? = null,
        fallbackDurationMs: Long? = null,
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
            val now = clockMs()
            val fresh = fetchPlayback(itemId, quality, audio)
            currentItemId = itemId
            currentQuality = quality ?: fresh.qualities.firstOrNull { it.selected && it.locked == null }?.id
            currentAudio = audio
            currentInfo = fresh
            // §11.2: refresh 60s before expiry; if freshly returned URL is ALREADY < 60s
            // from expiry, use it directly until actual expiresAt instead of loop-refetching.
            nextRefreshAtMs = fresh.expiresAtMs?.let { exp ->
                val remaining = exp - now
                if (remaining <= refreshLeadMs) exp else exp - refreshLeadMs
            }
            fresh
        }

        val plan = factory.plan(
            info = info,
            selectedQualityId = currentQuality,
            selectedAudioId = currentAudio,
            fallbackDurationMs = fallbackDurationMs,
        )
        return ResolvedPlaybackSession(
            info = info,
            plan = plan,
            resumePositionMs = resumePositionMs ?: info.startPositionMs,
            selectedQualityId = currentQuality,
            selectedAudioId = currentAudio,
        )
    }

    suspend fun switchQuality(
        qualityId: String,
        currentPositionMs: Long,
        fallbackDurationMs: Long? = null,
    ): ResolvedPlaybackSession {
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
            fallbackDurationMs = fallbackDurationMs,
            forceRefresh = true,
        )
    }

    suspend fun refreshIfExpiredOrFailed(
        currentPositionMs: Long,
        httpStatusCode: Int? = null,
        fallbackDurationMs: Long? = null,
    ): ResolvedPlaybackSession? {
        val itemId = currentItemId ?: return null
        val shouldRefetch = isExpired() || httpStatusCode == 401 || httpStatusCode == 403 || httpStatusCode == 410
        if (!shouldRefetch) return null
        return load(
            itemId = itemId,
            quality = currentQuality,
            audio = currentAudio,
            resumePositionMs = currentPositionMs,
            fallbackDurationMs = fallbackDurationMs,
            forceRefresh = true,
        )
    }

    companion object {
        /** §11.2: re-fetch 60 seconds before expiry. */
        const val DEFAULT_REFRESH_LEAD_MS = 60_000L
    }
}
