package com.limelight.local.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.local.media.Item
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page as MediaPage
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.Shelf
import com.limelight.local.media.Source
import com.limelight.local.media.SourceKind
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginLogEntry
import com.limelight.local.plugin.PluginLogLevel
import com.limelight.local.plugin.PluginMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure, JVM-testable manager coordinating plugin [MediaSource] instances on the wall:
 * - Reconciles `(packages, instances)` from Room into active [MediaSource] objects.
 *   Disabled/uninstalled plugins and deleted instances are removed immediately and any
 *   in-flight home refresh is cancelled.
 * - Caps concurrent plugin network/JS calls on home refresh (and continue-watching
 *   detail resolution) to [maxConcurrentPluginLoads] (default 2) using a [Semaphore],
 *   so slow or failing plugins never stall local/WebDAV shelves or overwhelm the headset.
 */
class WallPluginSourceManager(
    private val scope: CoroutineScope,
    private val openSource: suspend (instanceId: String) -> MediaSource,
    private val maxConcurrentPluginLoads: Int = MAX_CONCURRENT_PLUGIN_LOADS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class InstanceSignature(
        val instanceId: String,
        val pluginId: String,
        val displayName: String,
        val instanceUpdatedAt: Long,
        val authState: String,
        val pluginVersion: String,
        val pluginSha256: String,
        val pluginUpdatedAt: Long,
        val needsPermissionReconfirm: Boolean,
    )

    data class ReconcileResult(
        val sources: List<MediaSource>,
        val changedSourceIds: Set<String>,
        val removedSourceIds: Set<String>,
    )

    private val reconcileMutex = Mutex()
    private val pluginSemaphore = Semaphore(permits = maxConcurrentPluginLoads)
    private val activeBySourceId = LinkedHashMap<String, Pair<InstanceSignature, MediaSource>>()
    private val refreshJobs = ConcurrentHashMap<String, Job>()
    private val instanceToPluginId = ConcurrentHashMap<String, String>()

    fun pluginIdForSource(source: MediaSource): String? {
        if (source is PluginMediaSource) return source.manifest.id
        if (source is FailingPluginSource) return source.pluginId
        if (!source.source.id.startsWith(PluginHost.PLUGIN_SOURCE_PREFIX)) return null
        val instanceId = source.source.id.removePrefix(PluginHost.PLUGIN_SOURCE_PREFIX)
        return instanceToPluginId[instanceId]
    }

    suspend fun reconcile(
        packages: List<InstalledPluginEntity>,
        instances: List<PluginInstanceEntity>,
        pendingInstanceIds: Set<String> = emptySet(),
    ): ReconcileResult = reconcileMutex.withLock {
        val pkgsById = packages.associateBy { it.pluginId }
        val desired = ArrayList<Pair<String, InstanceSignature>>()
        val desiredIds = LinkedHashSet<String>()

        for (inst in instances) {
            instanceToPluginId[inst.instanceId] = inst.pluginId
            if (inst.instanceId in pendingInstanceIds) continue
            val pkg = pkgsById[inst.pluginId] ?: continue
            if (!pkg.enabled || !inst.enabled) continue
            val sourceId = PluginMediaSource.sourceIdFor(inst.instanceId)
            val sig = InstanceSignature(
                instanceId = inst.instanceId,
                pluginId = inst.pluginId,
                displayName = inst.displayName,
                instanceUpdatedAt = inst.updatedAt,
                authState = inst.authState,
                pluginVersion = pkg.version,
                pluginSha256 = pkg.sha256,
                pluginUpdatedAt = pkg.updatedAt,
                needsPermissionReconfirm = pkg.needsPermissionReconfirm,
            )
            desired += sourceId to sig
            desiredIds += sourceId
        }

        val removed = LinkedHashSet<String>()
        val iter = activeBySourceId.keys.iterator()
        while (iter.hasNext()) {
            val existingId = iter.next()
            if (existingId !in desiredIds) {
                iter.remove()
                removed += existingId
                refreshJobs.remove(existingId)?.cancel()
            }
        }

        val changed = LinkedHashSet<String>()
        val ordered = LinkedHashMap<String, Pair<InstanceSignature, MediaSource>>()
        for ((sourceId, sig) in desired) {
            val prev = activeBySourceId[sourceId]
            if (prev != null && prev.first == sig) {
                ordered[sourceId] = prev
            } else {
                val opened = try {
                    withContext(ioDispatcher) { openSource(sig.instanceId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LimeLog.warning("depth3d: failed opening plugin instance ${sig.instanceId}: ${e.javaClass.simpleName}")
                    FailingPluginSource(
                        sourceId = sourceId,
                        pluginId = sig.pluginId,
                        displayName = sig.displayName,
                        failure = e,
                    )
                }
                ordered[sourceId] = sig to opened
                changed += sourceId
            }
        }

        activeBySourceId.clear()
        activeBySourceId.putAll(ordered)

        ReconcileResult(
            sources = ordered.values.map { it.second },
            changedSourceIds = changed,
            removedSourceIds = removed,
        )
    }

    /**
     * Runs [block] bounded by the plugin concurrency semaphore when [kind] is [SourceKind.PLUGIN],
     * and directly without throttling for built-in sources (`LOCAL`, `WEBDAV`).
     */
    suspend fun <T> runWithConcurrencyLimit(kind: SourceKind, block: suspend () -> T): T {
        return if (kind == SourceKind.PLUGIN) {
            pluginSemaphore.withPermit {
                withContext(ioDispatcher) { block() }
            }
        } else {
            withContext(ioDispatcher) { block() }
        }
    }

    fun refreshSource(
        source: MediaSource,
        shelves: MutableMap<String, Load<List<Shelf>>>,
        forceLoadingState: Boolean = false,
    ): Job {
        val sourceId = source.source.id
        refreshJobs.remove(sourceId)?.cancel()
        if (forceLoadingState || shelves[sourceId] !is Load.Ready) {
            shelves[sourceId] = Load.Loading
        }
        val job = scope.launch {
            val outcome: Load<List<Shelf>> = try {
                val list = runWithConcurrencyLimit(source.source.kind) { source.home() }
                Load.Ready(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LimeLog.warning("depth3d: ${source.source.name} home failed: ${e.javaClass.simpleName}")
                Load.Failed(e)
            }
            shelves[sourceId] = outcome
        }
        refreshJobs[sourceId] = job
        return job
    }

    fun cancelSource(sourceId: String) {
        refreshJobs.remove(sourceId)?.cancel()
    }

    fun cancelAll() {
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
    }

    companion object {
        const val MAX_CONCURRENT_PLUGIN_LOADS = 2
    }
}

/**
 * Fallback [MediaSource] stub used if opening a registered plugin instance fails (e.g., corrupted
 * package dir or missing file), so the error surfaces through §3.2 UI instead of silently vanishing.
 */
internal class FailingPluginSource(
    sourceId: String,
    val pluginId: String,
    displayName: String,
    private val failure: Throwable,
) : MediaSource {
    override val source: Source = Source(
        id = sourceId,
        name = displayName,
        kind = SourceKind.PLUGIN,
        description = "",
    )

    override suspend fun home(): List<Shelf> = throw failure
    override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = throw failure
    override suspend fun detail(itemId: String): Item = throw failure
    override suspend fun playback(itemId: String): PlaybackInfo = throw failure
}

/**
 * Modal viewer for recent redacted logs of a plugin, opened from `PluginInternalException`'s
 * 「查看日志」 action and from the Plugins management detail screen.
 */
@Composable
fun PluginLogsSheet(
    pluginId: String,
    pluginName: String,
    model: WallModel,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
) {
    var version by remember(pluginId) { mutableIntStateOf(0) }
    val entries: List<PluginLogEntry> = remember(pluginId, version) {
        model.recentLogsForPlugin(pluginId).asReversed()
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Wall.Scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(860.dp)
                .shadow(40.dp, RoundedCornerShape(Wall.RadiusXl))
                .clip(RoundedCornerShape(Wall.RadiusXl))
                .background(Wall.Sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 44.dp, vertical = 36.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plugin_logs_title), style = WallType.Head)
                    Text(pluginName, style = WallType.Caption, color = Wall.Text2, modifier = Modifier.padding(top = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entries.isNotEmpty()) {
                        WallButton(
                            stringResource(R.string.plugin_logs_clear),
                            onClick = {
                                model.clearLogsForPlugin(pluginId)
                                version++
                                onCleared()
                            },
                            small = true,
                        )
                    }
                    WallButton(
                        stringResource(R.string.plugin_logs_close),
                        onClick = onDismiss,
                        kind = ButtonKind.Primary,
                        small = true,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            PluginLogListPanel(
                entries = entries,
                emptyText = stringResource(R.string.plugin_logs_empty),
                modifier = Modifier.fillMaxWidth().height(380.dp),
            )
        }
    }
}

/**
 * Shared redacted log viewer backed by [PluginLogger.recentEntries], reused by both
 * [PluginLogsSheet] (newest first) and [PluginDeveloperScreen] (chronological + auto-scroll
 * to latest after reload).
 */
@Composable
internal fun PluginLogListPanel(
    entries: List<PluginLogEntry>,
    emptyText: String,
    modifier: Modifier = Modifier,
    autoScrollToLatest: Boolean = false,
    scrollToken: Int = 0,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val listState = rememberLazyListState()

    if (autoScrollToLatest) {
        LaunchedEffect(entries.size, scrollToken) {
            if (entries.isNotEmpty()) {
                listState.scrollToItem(entries.lastIndex)
            }
        }
    }

    if (entries.isEmpty()) {
        Box(
            modifier
                .clip(RoundedCornerShape(Wall.Radius))
                .background(Wall.Surface)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(emptyText, style = WallType.Caption, color = Wall.Text2)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier
                .clip(RoundedCornerShape(Wall.Radius))
                .background(Wall.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries) { entry ->
                val levelColor = when (entry.level) {
                    PluginLogLevel.ERROR -> Wall.Danger
                    PluginLogLevel.WARN -> Wall.Accent
                    else -> Wall.Text2
                }
                val ts = timeFmt.format(Date(entry.timestampMs))
                Text(
                    text = "$ts [${entry.level.name}] ${entry.message}",
                    style = WallType.Caption.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                    ),
                    color = levelColor,
                )
            }
        }
    }
}
