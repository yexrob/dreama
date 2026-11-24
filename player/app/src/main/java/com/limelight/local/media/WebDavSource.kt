package com.limelight.local.media

import com.limelight.local.WebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A WebDAV share, in practice Google Drive served by rclone on the Mac
 * (scripts/serve-gdrive.sh). Folders are the categories; an id is the path the
 * server sent, percent-encoded, with folders ending in "/".
 *
 * There is no art: grabbing a frame means pulling part of the file over the
 * network for every poster, so remote items are drawn as placeholders.
 */
class WebDavSource(private val server: WebDav.Server) : MediaSource {

    override val source = Source(ID, "Google Drive", SourceKind.WEBDAV,
        "WebDAV · " + server.base.substringAfter("://"))

    // What each id looked like when last listed, so detail() needs no request
    private val known = ConcurrentHashMap<String, Item>()

    override suspend fun home(): List<Shelf> {
        val items = list("/")
        return if (items.isEmpty()) emptyList()
        else listOf(Shelf(Category("/", "", items.size), items.take(HOME_ROW)))
    }

    override suspend fun browse(categoryId: String, page: Int): Page<Item> {
        val path = if (categoryId == MediaSource.ROOT) "/" else categoryId
        // A share lists a folder in one answer, so there is only ever one page
        return Page(if (page == 0) list(path) else emptyList(), null)
    }

    // Ids outlive the listing that produced them (继续观看 is stored in Room),
    // so an unseen one is rebuilt from the path alone
    override suspend fun detail(itemId: String): Item = known[itemId]
        ?: if (itemId.endsWith("/")) folder(itemId, WebDav.decode(itemId).trimEnd('/').substringAfterLast('/'))
        else video(itemId, WebDav.decode(itemId).substringAfterLast('/'), -1)

    override suspend fun playback(itemId: String): PlaybackInfo {
        if (itemId.endsWith("/")) {
            throw ItemNotFoundException(itemId)
        }
        return PlaybackInfo(server.base + itemId, mapOf("Authorization" to server.auth()),
            detail(itemId).title)
    }

    private suspend fun list(path: String): List<Item> = withContext(Dispatchers.IO) {
        val listed = try {
            WebDav.list(server, path)
        } catch (e: IOException) {
            throw SourceUnavailableException(e.message ?: e.toString(), e)
        }
        listed.map { item(it) }.onEach { known[it.id] = it }
    }

    private fun item(i: WebDav.Item): Item =
        if (i.dir) folder(if (i.href.endsWith("/")) i.href else i.href + "/", i.name)
        else video(i.href, i.name, i.size)

    private fun folder(href: String, name: String) = Item(href, ItemKind.FOLDER, name)

    private fun video(href: String, name: String, bytes: Long): Item {
        val folder = WebDav.decode(href).substringBeforeLast('/', "").substringAfterLast('/')
        val size = if (bytes > 0) String.format(Locale.ROOT, "%.1f GB", bytes / 1e9) else null
        return Item(
            id = href,
            kind = ItemKind.VIDEO,
            title = name.substringBeforeLast('.', name),
            subtitle = listOfNotNull(folder.takeIf { it.isNotEmpty() }, size).joinToString(" · "),
        )
    }

    companion object {
        const val ID = "webdav"
        private const val HOME_ROW = 20
    }
}
