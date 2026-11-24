package com.limelight.local.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every video MediaStore knows about on the headset. There is no metadata
 * beyond the file, so the art is a frame of the video itself, and the
 * library's categories are the folders the files sit in.
 *
 * Ids: "v:<MediaStore id>" for a video, "b:<bucket id>" for a folder, and
 * [ALL] for every video, newest first.
 */
class LocalVideoSource(context: Context) : MediaSource {
    private val app = context.applicationContext

    override val source = Source(ID, "本机", SourceKind.LOCAL, "头显存储")

    private class Row(
        val id: Long,
        val title: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val bucketId: String,
        val bucket: String,
        val modified: Long,
    )

    override suspend fun home(): List<Shelf> {
        val rows = query(null, null)
        if (rows.isEmpty()) {
            return emptyList()
        }
        return listOf(Shelf(Category(ALL, "最近添加", rows.size), rows.take(HOME_ROW).map(::item)))
    }

    override suspend fun browse(categoryId: String, page: Int): Page<Item> {
        if (categoryId == MediaSource.ROOT) {
            return Page(folders(), null)
        }
        val rows = when {
            categoryId == ALL -> query(null, null)
            categoryId.startsWith("b:") -> query(
                "${MediaStore.Video.Media.BUCKET_ID} = ?", arrayOf(categoryId.removePrefix("b:")))
            else -> throw ItemNotFoundException(categoryId)
        }
        val from = page * PAGE
        val slice = rows.drop(from).take(PAGE).map(::item)
        return Page(slice, if (from + PAGE < rows.size) page + 1 else null)
    }

    override suspend fun detail(itemId: String): Item {
        val row = byId(itemId) ?: throw ItemNotFoundException(itemId)
        return item(row)
    }

    override suspend fun playback(itemId: String): PlaybackInfo {
        val row = byId(itemId) ?: throw ItemNotFoundException(itemId)
        return PlaybackInfo(uri(row.id).toString(), title = row.title)
    }

    // A tile per folder plus one for everything, each drawn as a grid of the
    // folder's newest videos since there is nothing else to show for it
    private suspend fun folders(): List<Item> {
        val rows = query(null, null)
        if (rows.isEmpty()) {
            return emptyList()
        }
        val out = ArrayList<Item>()
        out += Item(ALL, ItemKind.FOLDER, "全部视频", childCount = rows.size,
            previews = rows.take(4).map(::art))
        // query() is newest first, so the folder with the newest video leads
        rows.groupBy { it.bucketId }.values.forEach { inBucket ->
            out += Item("b:${inBucket[0].bucketId}", ItemKind.FOLDER, inBucket[0].bucket,
                childCount = inBucket.size, previews = inBucket.take(4).map(::art))
        }
        return out
    }

    private suspend fun byId(itemId: String): Row? {
        val id = itemId.removePrefix("v:").toLongOrNull() ?: return null
        return query("${MediaStore.Video.Media._ID} = ?", arrayOf(id.toString())).firstOrNull()
    }

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    private fun art(r: Row) = Artwork(uri(r.id).toString(), isVideoFrame = true, cacheKey = "local:${r.id}:${r.modified}")

    private fun item(r: Row): Item {
        val size = if (r.width > 0 && r.height > 0) "${r.width}×${r.height}" else null
        return Item(
            id = "v:${r.id}",
            kind = ItemKind.VIDEO,
            title = r.title,
            poster = art(r),
            backdrop = art(r),
            durationMs = r.durationMs.takeIf { it > 0 },
            subtitle = listOfNotNull(r.bucket.takeIf { it.isNotEmpty() }, size).joinToString(" · "),
        )
    }

    private suspend fun query(where: String?, args: Array<String>?): List<Row> = withContext(Dispatchers.IO) {
        if (!hasPermission(app)) {
            throw SourceNeedsSetupException("需要允许访问存储")
        }
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        val out = ArrayList<Row>()
        app.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, where, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Row(
                    id = c.getLong(0),
                    title = name.substringBeforeLast('.', name),
                    durationMs = c.getLong(2),
                    width = c.getInt(3),
                    height = c.getInt(4),
                    bucketId = c.getString(5) ?: "",
                    bucket = c.getString(6) ?: "",
                    modified = c.getLong(7),
                )
            }
        }
        out
    }

    companion object {
        const val ID = "local"
        const val ALL = "all"
        private const val HOME_ROW = 20
        private const val PAGE = 60

        // Android 13 split media access by type; before it, one storage grant
        @JvmStatic
        val permission: String
            get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasPermission(context: Context) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
