package com.limelight.local.plugin

import android.content.Context
import com.limelight.local.media.Category
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.Shelf
import com.limelight.local.media.Source
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceUnsupportedException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Optional capability interface for plugins declaring `search`. */
interface PluginSearchCapability {
    suspend fun search(query: String, cursor: String? = null, kind: ItemKind? = null): Page<Item>
    suspend fun suggest(query: String): List<String>
}

/** Optional capability interface for plugins declaring `progress`. */
interface PluginProgressCapability {
    suspend fun reportProgress(
        itemId: String,
        positionMs: Long,
        durationMs: Long,
        event: ProgressEvent,
        session: String? = null,
    )
}

/** Optional capability interface for plugins declaring `favorites`. */
interface PluginFavoritesCapability {
    suspend fun setFavorite(itemId: String, favorite: Boolean)
}

/** Optional capability interface for plugins declaring `played`. */
interface PluginPlayedCapability {
    suspend fun setPlayed(itemId: String, played: Boolean)
}

/** Optional capability interface for plugins declaring `filters`. */
interface PluginFiltersCapability {
    suspend fun filters(categoryId: String): FiltersSpec
}

/** Optional capability interface for plugins declaring `danmaku`. */
interface PluginDanmakuCapability {
    suspend fun danmaku(itemId: String, fromMs: Long, toMs: Long): List<DanmakuItem>
}

/** Optional capability interface for plugins declaring `categories`. */
interface PluginCategoriesCapability {
    suspend fun categories(): List<Category>
}

/** Login lifecycle interface covering protocol §4.3 (`form`, `qr`, `device`, `logout`). */
interface PluginAuthCapability {
    val authMode: PluginAuthMode
    suspend fun loginForm(): FormLoginResult
    suspend fun loginQrStart(): QrLoginStart
    suspend fun loginDeviceStart(): DeviceLoginStart
    suspend fun loginPoll(state: String): LoginPollResult
    suspend fun logout()
}

/**
 * Adapts one installed plugin instance to depth3d's standard [MediaSource]
 * interface (home / browse / detail / playback) and exposes all protocol v1
 * optional capabilities and auth flows for the UI layer.
 */
class PluginMediaSource(
    val instanceId: String,
    displayName: String,
    val manifest: PluginManifest,
    private val runtime: PluginRuntime,
    private val mapper: PluginProtocolMapper,
    private val stateGuard: suspend () -> Unit = {},
    private val onNeedsAuth: (suspend () -> Unit)? = null,
    private val onAuthSuccess: (suspend (UserProfile?) -> Unit)? = null,
    private val onLoggedOut: (suspend () -> Unit)? = null,
    private val androidContext: Context? = null,
) : MediaSource,
    PluginSearchCapability,
    PluginProgressCapability,
    PluginFavoritesCapability,
    PluginPlayedCapability,
    PluginFiltersCapability,
    PluginDanmakuCapability,
    PluginCategoriesCapability,
    PluginAuthCapability,
    Closeable {

    override val source: Source = Source(
        id = sourceIdFor(instanceId),
        name = displayName,
        kind = SourceKind.PLUGIN,
        description = manifest.description.ifBlank { manifest.name },
    )

    override val authMode: PluginAuthMode = manifest.auth
    val capabilities: Set<PluginCapability> = manifest.capabilities

    // Bridges integer page numbers (MediaSource) to opaque protocol cursors.
    // Key: categoryId -> (pageIndex -> cursor passed for that page).
    private val pageCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()
    // Remembers item titles seen in home/browse/detail so playback() can fill PlaybackInfo.title.
    private val knownTitles = ConcurrentHashMap<String, String>()

    override suspend fun home(): List<Shelf> = guarded {
        val raw = runtime.invoke("home")
        mapper.mapHome(raw).also { shelves ->
            for (shelf in shelves) {
                for (item in shelf.items) knownTitles[item.id] = item.title
            }
        }
    }

    override suspend fun browse(categoryId: String, page: Int): Page<Item> {
        val cursorMap = pageCursors.computeIfAbsent(categoryId) { ConcurrentHashMap() }
        val cursor = if (page <= 0) {
            cursorMap.clear()
            null
        } else {
            cursorMap[page] ?: return Page(emptyList(), nextPage = null, nextCursor = null)
        }
        val result = browseCursor(categoryId = categoryId, cursor = cursor)
        val nextCursor = result.nextCursor
        if (nextCursor != null) {
            cursorMap[page + 1] = nextCursor
            return result.copy(nextPage = page + 1)
        }
        return result.copy(nextPage = null)
    }

    suspend fun browseCursor(
        categoryId: String,
        cursor: String? = null,
        sort: String? = null,
        filters: Map<String, String> = emptyMap(),
    ): Page<Item> = guarded {
        val arg = buildJsonObject {
            put("categoryId", categoryId)
            if (cursor != null) put("cursor", cursor)
            if (sort != null) put("sort", sort)
            if (filters.isNotEmpty()) {
                put(
                    "filters",
                    buildJsonObject {
                        filters.forEach { (k, v) -> put(k, v) }
                    },
                )
            }
        }
        val raw = runtime.invoke("browse", arg)
        mapper.mapItemPage(raw).also { page ->
            page.items.forEach { knownTitles[it.id] = it.title }
        }
    }

    override suspend fun detail(itemId: String): Item = guarded {
        val arg = buildJsonObject { put("itemId", itemId) }
        val raw = runtime.invoke("detail", arg, itemIdForErrors = itemId)
        mapper.mapItem(raw).also { item ->
            knownTitles[item.id] = item.title
            item.seasons.forEach { knownTitles[it.id] = it.title }
            item.episodes.forEach { knownTitles[it.id] = it.title }
        }
    }

    override suspend fun playback(itemId: String): PlaybackInfo =
        playback(itemId = itemId, quality = null, audio = null)

    suspend fun playback(
        itemId: String,
        quality: String? = null,
        audio: String? = null,
    ): PlaybackInfo = guarded {
        val arg = buildJsonObject {
            put("itemId", itemId)
            if (quality != null) put("quality", quality)
            if (audio != null) put("audio", audio)
        }
        val raw = runtime.invoke("playback", arg, itemIdForErrors = itemId)
        val title = knownTitles[itemId] ?: itemId
        mapper.mapPlayback(raw, fallbackTitle = title)
    }

    override suspend fun search(query: String, cursor: String?, kind: ItemKind?): Page<Item> = guarded {
        requireCapability(PluginCapability.SEARCH)
        val arg = buildJsonObject {
            put("query", query)
            if (cursor != null) put("cursor", cursor)
            if (kind != null) put("kind", kind.name.lowercase(Locale.ROOT))
        }
        val raw = runtime.invoke("search", arg)
        mapper.mapItemPage(raw).also { page ->
            page.items.forEach { knownTitles[it.id] = it.title }
        }
    }

    override suspend fun suggest(query: String): List<String> = guarded {
        requireCapability(PluginCapability.SEARCH)
        val arg = buildJsonObject { put("query", query) }
        val raw = runtime.invoke("suggest", arg)
        mapper.mapSuggest(raw)
    }

    override suspend fun reportProgress(
        itemId: String,
        positionMs: Long,
        durationMs: Long,
        event: ProgressEvent,
        session: String?,
    ) = guarded {
        requireCapability(PluginCapability.PROGRESS)
        val arg = buildJsonObject {
            put("itemId", itemId)
            put("positionMs", positionMs.coerceAtLeast(0L))
            put("durationMs", durationMs.coerceAtLeast(0L))
            put("event", event.wireName)
            if (session != null) put("session", session)
        }
        runtime.invoke("reportProgress", arg, itemIdForErrors = itemId)
        Unit
    }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) = guarded {
        requireCapability(PluginCapability.FAVORITES)
        val arg = buildJsonObject {
            put("itemId", itemId)
            put("favorite", favorite)
        }
        runtime.invoke("setFavorite", arg, itemIdForErrors = itemId)
        Unit
    }

    override suspend fun setPlayed(itemId: String, played: Boolean) = guarded {
        requireCapability(PluginCapability.PLAYED)
        val arg = buildJsonObject {
            put("itemId", itemId)
            put("played", played)
        }
        runtime.invoke("setPlayed", arg, itemIdForErrors = itemId)
        Unit
    }

    override suspend fun filters(categoryId: String): FiltersSpec = guarded {
        requireCapability(PluginCapability.FILTERS)
        val arg = buildJsonObject { put("categoryId", categoryId) }
        val raw = runtime.invoke("filters", arg)
        mapper.mapFilters(raw)
    }

    override suspend fun danmaku(itemId: String, fromMs: Long, toMs: Long): List<DanmakuItem> = guarded {
        requireCapability(PluginCapability.DANMAKU)
        val arg = buildJsonObject {
            put("itemId", itemId)
            put("fromMs", fromMs.coerceAtLeast(0L))
            put("toMs", toMs.coerceAtLeast(0L))
        }
        val raw = runtime.invoke("danmaku", arg, itemIdForErrors = itemId)
        mapper.mapDanmaku(raw)
    }

    override suspend fun categories(): List<Category> = guarded {
        if (manifest.hasCapability(PluginCapability.CATEGORIES)) {
            val raw = runtime.invoke("categories")
            mapper.mapCategories(raw)
        } else {
            // Protocol §4.2: fallback to browse('') when categories is not implemented
            browseCursor(MediaSource.ROOT).items.map { item ->
                Category(id = item.id, title = item.title, count = item.childCount)
            }
        }
    }

    override suspend fun loginForm(): FormLoginResult = guarded {
        if (manifest.auth != PluginAuthMode.FORM) {
            throw SourceUnsupportedException(
                PluginErrorMapper.defaultMessage(PluginErrorCode.UNSUPPORTED, context = androidContext),
            )
        }
        val raw = runtime.invoke("login")
        mapper.mapFormLogin(raw).also { res ->
            if (res.ok) onAuthSuccess?.invoke(res.user)
        }
    }

    override suspend fun loginQrStart(): QrLoginStart = guarded {
        if (manifest.auth != PluginAuthMode.QR) {
            throw SourceUnsupportedException(
                PluginErrorMapper.defaultMessage(PluginErrorCode.UNSUPPORTED, context = androidContext),
            )
        }
        val raw = runtime.invoke("loginStart")
        mapper.mapQrLoginStart(raw)
    }

    override suspend fun loginDeviceStart(): DeviceLoginStart = guarded {
        if (manifest.auth != PluginAuthMode.DEVICE) {
            throw SourceUnsupportedException(
                PluginErrorMapper.defaultMessage(PluginErrorCode.UNSUPPORTED, context = androidContext),
            )
        }
        val raw = runtime.invoke("loginStart")
        mapper.mapDeviceLoginStart(raw)
    }

    override suspend fun loginPoll(state: String): LoginPollResult = guarded {
        if (manifest.auth != PluginAuthMode.QR && manifest.auth != PluginAuthMode.DEVICE) {
            throw SourceUnsupportedException(
                PluginErrorMapper.defaultMessage(PluginErrorCode.UNSUPPORTED, context = androidContext),
            )
        }
        val arg = buildJsonObject { put("state", state) }
        val raw = runtime.invoke("loginPoll", arg)
        mapper.mapLoginPoll(raw).also { res ->
            if (res.status == LoginPollStatus.DONE) {
                onAuthSuccess?.invoke(res.user)
            }
        }
    }

    override suspend fun logout() = guarded {
        try {
            runtime.invoke("logout")
        } catch (_: Exception) {
            // logout(ctx) is optional per protocol §4.3
        }
        onLoggedOut?.invoke() ?: Unit
    }

    suspend fun shutdown() {
        runtime.shutdown()
    }

    override fun close() {
        runtime.close()
    }

    private fun requireCapability(cap: PluginCapability) {
        if (!manifest.hasCapability(cap)) {
            throw SourceUnsupportedException(
                PluginErrorMapper.defaultMessage(PluginErrorCode.UNSUPPORTED, context = androidContext),
            )
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        stateGuard()
        return try {
            block()
        } catch (e: SourceNeedsAuthException) {
            onNeedsAuth?.invoke()
            throw e
        }
    }

    companion object {
        fun sourceIdFor(instanceId: String): String = "plugin:$instanceId"
    }
}
