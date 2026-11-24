package com.limelight.local.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.limelight.LimeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Turns an [Artwork] into a bitmap: a frame out of the video when the source
 * has no art, otherwise the image it points at. Frames are slow (a decoder
 * per file), so each is grabbed once and kept on disk under the artwork's
 * cache key, and recent ones stay in memory.
 */
object Artworks {
    /** The long side to grab at. Posters and cards share one, the backdrop is bigger. */
    enum class Size(val px: Int) { CARD(720), BACKDROP(1280) }

    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    // Files that would not give a frame, so a broken one is not retried per redraw
    private val failed = Collections.synchronizedSet(HashSet<String>())
    // Two decoders at once: more only makes every poster arrive later
    private val decoders = Semaphore(2)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    fun cached(art: Artwork, size: Size): Bitmap? = memory.get(key(art, size))

    suspend fun load(context: Context, art: Artwork, size: Size): Bitmap? {
        if (art.sourceKind == SourceKind.PLUGIN) {
            throw UnsupportedOperationException(
                "插件来源图片禁止走 Artworks.load（会绕过 NetworkPolicy），请改用 PluginImages.loadBitmap",
            )
        }
        val key = key(art, size)
        memory.get(key)?.let { return it }
        if (key in failed) {
            return null
        }
        return withContext(Dispatchers.IO) {
            val file = File(dir(context), sha1(key) + ".jpg")
            val bmp = (if (file.exists()) decodeFile(file) else null) ?: decoders.withPermit {
                val fresh = if (art.isVideoFrame) frame(context, art, size) else fetch(art, size)
                fresh?.also { save(it, file) }?.let { it.copy(Bitmap.Config.RGB_565, false) ?: it }
            }
            if (bmp == null) {
                failed += key
            } else {
                memory.put(key, bmp)
            }
            bmp
        }
    }

    // A tenth of the way in, capped at five minutes, skips the black or the
    // studio logo a frame at zero usually is
    private fun frame(context: Context, art: Artwork, size: Size): Bitmap? {
        if (art.sourceKind == SourceKind.PLUGIN) {
            throw UnsupportedOperationException(
                "插件来源禁止走 MediaMetadataRetriever 取帧，请使用 PluginImages.loadBitmap 或占位图",
            )
        }
        val mmr = MediaMetadataRetriever()
        return try {
            if (art.url.startsWith("http")) {
                mmr.setDataSource(art.url, art.headers)
            } else {
                mmr.setDataSource(context, Uri.parse(art.url))
            }
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val atUs = (durationMs / 10).coerceAtMost(300_000) * 1000
            if (Build.VERSION.SDK_INT >= 27) {
                mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.px, size.px)
            } else {
                mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaled(it, size) }
            }
        } catch (e: Exception) {
            LimeLog.warning("depth3d: no frame from ${art.cacheKey}: $e")
            null
        } finally {
            try {
                mmr.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun fetch(art: Artwork, size: Size): Bitmap? = try {
        val req = Request.Builder().url(art.url).apply { art.headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else BitmapFactory.decodeStream(r.body.byteStream())?.let { scaled(it, size) }
        }
    } catch (e: Exception) {
        LimeLog.warning("depth3d: no image from ${art.url}: $e")
        null
    }

    private fun scaled(b: Bitmap, size: Size): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= size.px) {
            return b
        }
        val f = size.px.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun decodeFile(f: File): Bitmap? =
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

    private fun save(b: Bitmap, f: File) {
        val tmp = File(f.path + ".tmp")
        try {
            tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(f)
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    private fun dir(context: Context) = File(context.cacheDir, "frames").apply { mkdirs() }

    private fun key(art: Artwork, size: Size) = "${art.cacheKey}@${size.px}"

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
