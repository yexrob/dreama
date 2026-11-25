package com.limelight.local.wall

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limelight.LimeLog
import com.limelight.local.WebDav
import com.limelight.local.media.Item
import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.LocalVideoSource
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Shelf
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    /** A WebDAV server found on the network while none is set up yet. */
    val discovered = MutableStateFlow<String?>(null)

    val continueWatching: StateFlow<Load<List<Resume>>> = combine(
        WallDatabase.get(app).progress().recent(40), sources,
    ) { rows, srcs -> rows to srcs }
        .mapLatest { (rows, srcs) -> Load.Ready(resolve(rows, srcs)) }
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
                pluginHost.changes,
            ) { pkgs, insts, _ -> pkgs to insts }
                .collect { (pkgs, insts) ->
                    refreshPluginRepos()
                    val reconciled = pluginManager.reconcile(pkgs, insts)
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

    fun pluginDisplayName(pluginId: String): String {
        val entity = installedPlugins.value.firstOrNull { it.pluginId == pluginId } ?: return pluginId
        return runCatching { pluginHost.localizeManifest(entity).name }.getOrDefault(entity.name)
    }

    suspend fun disablePluginForSource(source: MediaSource): String? {
        val pluginId = pluginIdForSource(source) ?: return null
        val name = pluginDisplayName(pluginId)
        withContext(Dispatchers.IO) {
            pluginHost.packageManager.setPluginEnabled(pluginId, false)
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
            pluginHost.packageManager.syncRepository(url.trim())
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
            pluginHost.packageManager.setPluginEnabled(pluginId, enabled)
        }
        pluginHost.notifyChanged()
    }

    suspend fun confirmPluginPermissions(pluginId: String) {
        withContext(Dispatchers.IO) {
            pluginHost.packageManager.confirmPermissions(pluginId)
        }
        pluginHost.notifyChanged()
    }

    suspend fun uninstallPlugin(entity: InstalledPluginEntity) {
        val strings = com.limelight.local.stream.AndroidStringLookup(getApplication())
        withContext(Dispatchers.IO) {
            PluginInstallGuard.uninstallIfAllowed(pluginHost.packageManager, entity, strings)
        }
        pluginHost.notifyChanged()
    }

    suspend fun deletePluginInstance(instanceId: String) {
        withContext(Dispatchers.IO) {
            pluginHost.packageManager.deleteInstance(instanceId)
        }
        shelves.remove(PluginMediaSource.sourceIdFor(instanceId))
        pluginHost.notifyChanged()
    }

    suspend fun renamePluginInstance(instanceId: String, newName: String) {
        withContext(Dispatchers.IO) {
            val cfg = pluginHost.packageManager.getInstanceConfig(instanceId)
            pluginHost.packageManager.updateInstanceConfig(
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

    // Newest first; an item whose source is gone or whose file was deleted is
    // dropped rather than shown as a card that cannot play. ItemNotFound cleans
    // up the stale WatchProgress row per plugin-host-integration.md §3.2.
    private suspend fun resolve(rows: List<WatchProgress>, srcs: List<MediaSource>): List<Resume> {
        val progressDao = WallDatabase.get(getApplication()).progress()
        return rows.filter { it.resumable }.mapNotNull { p ->
            val src = srcs.firstOrNull { it.source.id == p.sourceId } ?: return@mapNotNull null
            try {
                val item = pluginManager.runWithConcurrencyLimit(src.source.kind) {
                    src.detail(p.itemId)
                }
                Resume(src, item, p)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ItemNotFoundException) {
                runCatching {
                    withContext(Dispatchers.IO) { progressDao.delete(p.sourceId, p.itemId) }
                }
                null
            } catch (_: Exception) {
                null
            }
        }.take(12)
    }
}
