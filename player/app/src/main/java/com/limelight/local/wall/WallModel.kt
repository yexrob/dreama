package com.limelight.local.wall

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limelight.LimeLog
import com.limelight.local.WebDav
import com.limelight.local.media.Item
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Shelf
import com.limelight.local.media.WallDatabase
import com.limelight.local.media.WatchProgress
import com.limelight.local.media.WebDavSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** One 继续观看 card: what it is, where it came from, and where it was left. */
data class Resume(val source: MediaSource, val item: Item, val progress: WatchProgress)

/**
 * The wall's state across pages: the sources, each one's home shelves, and
 * 继续观看 from Room. Sources are asked once and kept until the user asks
 * again (重试) or comes back from the player, since a WebDAV listing is a
 * network round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallModel(app: Application) : AndroidViewModel(app) {
    private val local = LocalVideoSource(app)
    private val webdav = MutableStateFlow(WebDav.load(app)?.let(::WebDavSource))

    val sources: StateFlow<List<MediaSource>> = webdav.map { listOfNotNull(local, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOfNotNull(local, webdav.value))

    val shelves = mutableStateMapOf<String, Load<List<Shelf>>>()

    /** A WebDAV server found on the network while none is set up yet. */
    val discovered = MutableStateFlow<String?>(null)

    val continueWatching: StateFlow<Load<List<Resume>>> = combine(
        WallDatabase.get(app).progress().recent(40), sources) { rows, srcs -> rows to srcs }
        .mapLatest { (rows, srcs) -> Load.Ready(resolve(rows, srcs)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Load.Loading)

    init {
        refreshAll()
        if (webdav.value == null) {
            viewModelScope.launch(Dispatchers.IO) {
                discovered.value = WebDav.discover(app, 4000)
                LimeLog.info("depth3d: webdav discovery -> ${discovered.value}")
            }
        }
    }

    fun source(id: String): MediaSource? = sources.value.firstOrNull { it.source.id == id }

    fun refreshAll() {
        sources.value.forEach { refresh(it.source.id) }
    }

    fun refresh(sourceId: String) {
        val src = source(sourceId) ?: return
        if (shelves[sourceId] !is Load.Ready) {
            shelves[sourceId] = Load.Loading
        }
        viewModelScope.launch {
            shelves[sourceId] = try {
                Load.Ready(src.home())
            } catch (e: Exception) {
                LimeLog.warning("depth3d: ${src.source.name} home failed: $e")
                Load.Failed(e)
            }
        }
    }

    fun progress(sourceId: String, itemId: String): Flow<WatchProgress?> =
        WallDatabase.get(getApplication()).progress().observe(sourceId, itemId)

    /**
     * Saves a WebDAV server after checking that it answers, so a wrong
     * password shows up here and not as a broken row on the wall.
     */
    suspend fun connectWebDav(base: String, user: String, pass: String) {
        val server = WebDav.Server(base, user, pass)
        withContext(Dispatchers.IO) {
            WebDav.list(server, "/")
            WebDav.save(getApplication(), server)
        }
        webdav.value = WebDavSource(server)
        discovered.value = null
        refresh(WebDavSource.ID)
    }

    fun savedWebDav(): WebDav.Server? = WebDav.load(getApplication())

    // Newest first; an item whose source is gone or whose file was deleted is
    // dropped rather than shown as a card that cannot play
    private suspend fun resolve(rows: List<WatchProgress>, srcs: List<MediaSource>): List<Resume> =
        rows.filter { it.resumable }.mapNotNull { p ->
            val src = srcs.firstOrNull { it.source.id == p.sourceId } ?: return@mapNotNull null
            try {
                Resume(src, src.detail(p.itemId), p)
            } catch (e: Exception) {
                null
            }
        }.take(12)
}
