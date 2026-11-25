package com.limelight.local.wall

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limelight.LimeLog
import com.limelight.local.WebDav
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Shelf
import com.limelight.local.media.SourceKind
import com.limelight.local.media.WallDatabase
import com.limelight.local.media.WatchProgress
import com.limelight.local.media.WebDavSource
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginLogEntry
import com.limelight.local.plugin.PluginManifest
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginRepoEntity
import com.limelight.local.plugin.PluginRepoIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** One 继续观看 card: what it is, where it came from, and where it was left. */
data class Resume(val source: MediaSource, val item: Item, val progress: WatchProgress)

/**
 * The wall's state across pages: the sources (local, WebDAV, and enabled plugin
 * instances), each one's home shelves, and 继续观看 from Room. Sources are asked
 * once and kept until the user asks again (重试) or comes back from the player,
 * since remote listings are network round trips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallModel(app: Application) : AndroidViewModel(app) {
    private val local = LocalVideoSource(app)
    private val webdav = MutableStateFlow(WebDav.load(app)?.let(::WebDavSource))
    val pluginHost: PluginHost = PluginHost.get(app)
    private val pluginSources = MutableStateFlow<List<MediaSource>>(emptyList())

    private val pluginManager = WallPluginSourceManager(
        scope = viewModelScope,
        openSource = { instanceId -> pluginHost.openSource(instanceId) },
    )

    val sources: StateFlow<List<MediaSource>> = combine(webdav, pluginSources) { wd, plugins ->
        buildList {
            add(local)
            if (wd != null) add(wd)
            addAll(plugins)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOfNotNull(local, webdav.value))

    val installedPlugins: StateFlow<List<InstalledPluginEntity>> =
        pluginHost.database.pluginPackages().observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pluginInstances: StateFlow<List<PluginInstanceEntity>> =
        pluginHost.database.pluginInstances().observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _pluginRepos = MutableStateFlow<List<PluginRepoEntity>>(emptyList())
    val pluginRepos: StateFlow<List<PluginRepoEntity>> get() = _pluginRepos

    val shelves = mutableStateMapOf<String, Load<List<Shelf>>>()

    fun knownParentIdsForSource(sourceId: String): Map<String, String?> {
        val out = HashMap<String, String?>()
        val load = shelves[sourceId] as? Load.Ready
        load?.value?.forEach { shelf ->
            shelf.items.forEach { item ->
                if (!item.parentId.isNullOrBlank()) {
                    out[item.id] = item.parentId
                }
            }
        }
        return out
    }

    /** A WebDAV server found on the network while none is set up yet. */
    val discovered = MutableStateFlow<String?>(null)

    val continueWatching: StateFlow<Load<List<Resume>>> = combine(
        WallDatabase.get(app).progress().recent(40), sources,
    ) { rows, srcs -> rows to srcs }
        .flatMapLatest { (rows, srcs) ->
            val progressDao = WallDatabase.get(app).progress()
            ContinueWatchingResolver.resolveFlow(
                rows = rows,
                sources = srcs,
                cachedItems = snapshotCachedShelfItems(),
                runForSource = { kind, block -> pluginManager.runWithConcurrencyLimit(kind, block) },
                onItemNotFound = { p ->
                    withContext(Dispatchers.IO) { progressDao.delete(p.sourceId, p.itemId) }
                },
            ).map { Load.Ready(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Load.Loading)

    val streaming = com.limelight.local.stream.StreamingController(app) {
        reloadSavedWebDav()
    }

    init {
        refreshAll()
        viewModelScope.launch {
            pluginHost.awaitBuiltInReady()
            refreshPluginRepos()
            combine(
                pluginHost.database.pluginPackages().observeAll(),
                pluginHost.database.pluginInstances().observeAll(),
                pluginHost.pendingInstanceIds,
                pluginHost.changes,
            ) { pkgs, insts, pendingIds, _ -> Triple(pkgs, insts, pendingIds) }
                .collect { (pkgs, insts, pendingIds) ->
                    refreshPluginRepos()
                    val reconciled = pluginManager.reconcile(pkgs, insts, pendingIds)
                    reconciled.removedSourceIds.forEach { shelves.remove(it) }
                    pluginSources.value = reconciled.sources
                    reconciled.sources.forEach { src ->
                        if (src.source.id in reconciled.changedSourceIds || shelves[src.source.id] == null) {
                            pluginManager.refreshSource(src, shelves)
                        }
                    }
                }
        }
        if (webdav.value == null) {
            viewModelScope.launch(Dispatchers.IO) {
                discovered.value = WebDav.discover(app, 4000)
                LimeLog.info("depth3d: webdav discovery -> ${discovered.value}")
            }
        }
    }

    override fun onCleared() {
        pluginManager.cancelAll()
        streaming.onDestroy()
        super.onCleared()
    }

    fun reloadSavedWebDav() {
        val loaded = WebDav.load(getApplication())
        webdav.value = loaded?.let(::WebDavSource)
        if (loaded != null) {
            discovered.value = null
            refresh(WebDavSource.ID)
        } else {
            pluginManager.cancelSource(WebDavSource.ID)
            shelves.remove(WebDavSource.ID)
        }
    }

    fun source(id: String): MediaSource? = sources.value.firstOrNull { it.source.id == id }

    fun refreshAll() {
        sources.value.forEach { refresh(it.source.id) }
    }

    fun refresh(sourceId: String) {
        val src = source(sourceId) ?: return
        pluginManager.refreshSource(src, shelves)
    }

    fun pluginIdForSource(source: MediaSource): String? = pluginManager.pluginIdForSource(source)

    fun needsPermissionReconfirm(source: MediaSource): Boolean {
        val pluginId = pluginIdForSource(source) ?: return false
        return installedPlugins.value.firstOrNull { it.pluginId == pluginId }?.needsPermissionReconfirm == true
    }

    fun pluginDisplayName(pluginId: String): String {
        val entity = installedPlugins.value.firstOrNull { it.pluginId == pluginId } ?: return pluginId
        return runCatching { pluginHost.localizeManifest(entity).name }.getOrDefault(entity.name)
    }

    suspend fun disablePluginForSource(source: MediaSource): String? {
        val pluginId = pluginIdForSource(source) ?: return null
        val name = pluginDisplayName(pluginId)
        withContext(Dispatchers.IO) {
            pluginHost.packageManager().setPluginEnabled(pluginId, false)
        }
        pluginHost.notifyChanged()
        return name
    }

    fun recentLogsForPlugin(pluginId: String): List<PluginLogEntry> = pluginHost.recentLogs(pluginId)

    fun clearLogsForPlugin(pluginId: String) = pluginHost.clearLogs(pluginId)

    fun localizedManifestFor(entity: InstalledPluginEntity): PluginManifest =
        runCatching { pluginHost.localizeManifest(entity) }
            .getOrElse { com.limelight.local.plugin.ManifestParser.parseAndValidate(entity.manifestJson) }

    suspend fun refreshPluginRepos() {
        _pluginRepos.value = withContext(Dispatchers.IO) {
            pluginHost.database.pluginRepos().listAll()
        }
    }

    suspend fun syncPluginRepo(url: String): PluginRepoIndex {
        val index = withContext(Dispatchers.IO) {
            pluginHost.packageManager().syncRepository(url.trim())
        }
        refreshPluginRepos()
        pluginHost.notifyChanged()
        return index
    }

    suspend fun removePluginRepo(url: String) {
        withContext(Dispatchers.IO) {
            pluginHost.database.pluginRepos().delete(url)
        }
        refreshPluginRepos()
        pluginHost.notifyChanged()
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            pluginHost.packageManager().setPluginEnabled(pluginId, enabled)
        }
        pluginHost.notifyChanged()
    }

    suspend fun confirmPluginPermissions(pluginId: String) {
        withContext(Dispatchers.IO) {
            pluginHost.packageManager().confirmPermissions(pluginId)
        }
        pluginHost.notifyChanged()
    }

    suspend fun uninstallPlugin(entity: InstalledPluginEntity) {
        val strings = com.limelight.local.stream.AndroidStringLookup(getApplication())
        withContext(Dispatchers.IO) {
            PluginInstallGuard.uninstallIfAllowed(pluginHost.packageManager(), entity, strings)
        }
        pluginHost.notifyChanged()
    }

    suspend fun deletePluginInstance(instanceId: String) {
        withContext(Dispatchers.IO) {
            pluginHost.packageManager().deleteInstance(instanceId)
        }
        shelves.remove(PluginMediaSource.sourceIdFor(instanceId))
        pluginHost.notifyChanged()
    }

    suspend fun renamePluginInstance(instanceId: String, newName: String) {
        withContext(Dispatchers.IO) {
            val pm = pluginHost.packageManager()
            val cfg = pm.getInstanceConfig(instanceId)
            pm.updateInstanceConfig(
                instanceId = instanceId,
                displayName = newName.trim(),
                config = cfg,
            )
        }
        pluginHost.notifyChanged()
    }

    fun progress(sourceId: String, itemId: String): Flow<WatchProgress?> =
        WallDatabase.get(getApplication()).progress().observe(sourceId, itemId)

    fun progressMapForSource(sourceId: String): Flow<Map<String, WatchProgress>> =
        WallDatabase.get(getApplication()).progress().recent(200).mapLatest { rows ->
            rows.asSequence().filter { it.sourceId == sourceId }.associateBy { it.itemId }
        }.distinctUntilChanged()

    suspend fun clearProgress(sourceId: String, itemId: String) {
        withContext(Dispatchers.IO) {
            WallDatabase.get(getApplication()).progress().delete(sourceId, itemId)
        }
    }

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

    private fun snapshotCachedShelfItems(): Map<Pair<String, String>, Item> {
        val out = HashMap<Pair<String, String>, Item>()
        for ((sourceId, load) in shelves) {
            val list = (load as? Load.Ready)?.value ?: continue
            for (shelf in list) {
                for (item in shelf.items) {
                    out[sourceId to item.id] = item
                }
            }
        }
        return out
    }
}

/**
 * Two-phase parallel resolver for 继续观看 (#4):
 * 1. Emits immediate cards from [WatchProgress] (reusing any already-loaded shelf item metadata
 *    when cached, or title/duration from Room) so Home never spins waiting on offline/slow plugins.
 * 2. Resolves `src.detail(itemId)` concurrently per source with a per-item timeout
 *    ([perItemTimeoutMs]), bounded for plugins via [runForSource], deleting stale rows on
 *    [ItemNotFoundException], and stopping as soon as the top [limit] non-dropped items settle.
 *
 * Note: `WatchProgress` (WallDatabase v2) stores `title` and `durationMs` without an artwork
 * column; cover art is reused from in-memory shelf cache when present and otherwise enriched
 * asynchronously from `detail()`.
 */
object ContinueWatchingResolver {
    const val DEFAULT_LIMIT = 12
    const val DEFAULT_PER_ITEM_TIMEOUT_MS = 3_500L

    private sealed interface EntryState {
        data class Pending(val resume: Resume) : EntryState
        data class Resolved(val resume: Resume) : EntryState
        data class Fallback(val resume: Resume) : EntryState
        data object Dropped : EntryState
    }

    fun resolveFlow(
        rows: List<WatchProgress>,
        sources: List<MediaSource>,
        cachedItems: Map<Pair<String, String>, Item> = emptyMap(),
        runForSource: suspend (SourceKind, suspend () -> Item) -> Item = { _, block -> block() },
        onItemNotFound: suspend (WatchProgress) -> Unit = {},
        perItemTimeoutMs: Long = DEFAULT_PER_ITEM_TIMEOUT_MS,
        limit: Int = DEFAULT_LIMIT,
    ): Flow<List<Resume>> = channelFlow {
        val srcById = sources.associateBy { it.source.id }
        val candidates = rows.mapNotNull { p ->
            if (!p.resumable) return@mapNotNull null
            val src = srcById[p.sourceId] ?: return@mapNotNull null
            src to p
        }
        if (candidates.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }

        val stateMutex = Mutex()
        val states = Array<EntryState>(candidates.size) { idx ->
            val (src, p) = candidates[idx]
            val cached = cachedItems[p.sourceId to p.itemId]
            val initialItem = when {
                cached != null && cached.parentId.isNullOrBlank() && !p.parentId.isNullOrBlank() ->
                    cached.copy(parentId = p.parentId)
                cached != null -> cached
                else -> Item(
                    id = p.itemId,
                    kind = ItemKind.VIDEO,
                    title = p.title.ifBlank { p.itemId },
                    durationMs = p.durationMs.takeIf { it > 0L },
                    parentId = p.parentId,
                )
            }
            EntryState.Pending(Resume(src, initialItem, p))
        }

        fun visibleLocked(): List<Resume> {
            val out = ArrayList<Resume>(limit)
            for (st in states) {
                if (out.size >= limit) break
                when (st) {
                    is EntryState.Pending -> out += st.resume
                    is EntryState.Resolved -> out += st.resume
                    is EntryState.Fallback -> out += st.resume
                    EntryState.Dropped -> Unit
                }
            }
            return out
        }

        fun settledBeforeLocked(index: Int): Int {
            var count = 0
            for (i in 0 until index) {
                when (states[i]) {
                    is EntryState.Resolved, is EntryState.Fallback -> count++
                    is EntryState.Pending, EntryState.Dropped -> Unit
                }
            }
            return count
        }

        fun topSettledLocked(): Boolean {
            var kept = 0
            for (st in states) {
                when (st) {
                    is EntryState.Pending -> return false
                    is EntryState.Resolved, is EntryState.Fallback -> {
                        kept++
                        if (kept >= limit) return true
                    }
                    EntryState.Dropped -> Unit
                }
            }
            return true
        }

        // Phase 1: emit immediate cards from WatchProgress + shelf cache with zero network wait
        var lastSent = stateMutex.withLock { visibleLocked() }
        send(lastSent)

        // Phase 2: resolve details concurrently per source with per-item timeout; stop once top [limit] settle
        val indicesBySource = LinkedHashMap<String, MutableList<Int>>()
        candidates.forEachIndexed { idx, (src, _) ->
            indicesBySource.getOrPut(src.source.id) { ArrayList() }.add(idx)
        }

        val workersJob = Job(coroutineContext[Job])
        val workers = indicesBySource.values.map { indices ->
            launch(workersJob) {
                for (idx in indices) {
                    val skip = stateMutex.withLock {
                        topSettledLocked() || settledBeforeLocked(idx) >= limit
                    }
                    if (skip) break
                    val (src, p) = candidates[idx]
                    val outcome: EntryState = try {
                        val detailed = withTimeoutOrNull(perItemTimeoutMs) {
                            runForSource(src.source.kind) { src.detail(p.itemId) }
                        }
                        if (detailed != null) {
                            val mergedDetail = if (detailed.parentId.isNullOrBlank() && !p.parentId.isNullOrBlank()) {
                                detailed.copy(parentId = p.parentId)
                            } else {
                                detailed
                            }
                            EntryState.Resolved(Resume(src, mergedDetail, p))
                        } else {
                            val prev = (states[idx] as? EntryState.Pending)?.resume
                                ?: Resume(
                                    src,
                                    Item(p.itemId, ItemKind.VIDEO, p.title.ifBlank { p.itemId }, parentId = p.parentId),
                                    p,
                                )
                            EntryState.Fallback(prev)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: ItemNotFoundException) {
                        runCatching { onItemNotFound(p) }
                        EntryState.Dropped
                    } catch (_: Exception) {
                        val prev = (states[idx] as? EntryState.Pending)?.resume
                            ?: Resume(
                                src,
                                Item(p.itemId, ItemKind.VIDEO, p.title.ifBlank { p.itemId }, parentId = p.parentId),
                                p,
                            )
                        EntryState.Fallback(prev)
                    }

                    var toEmit: List<Resume>? = null
                    var allTopDone = false
                    stateMutex.withLock {
                        states[idx] = outcome
                        val nowVisible = visibleLocked()
                        if (nowVisible != lastSent) {
                            lastSent = nowVisible
                            toEmit = nowVisible
                        }
                        allTopDone = topSettledLocked()
                    }
                    if (toEmit != null) {
                        send(toEmit!!)
                    }
                    if (allTopDone) {
                        workersJob.cancel()
                        break
                    }
                }
            }
        }
        workers.forEach { it.join() }
    }
}
