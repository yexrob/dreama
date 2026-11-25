package com.limelight.local.search

import com.limelight.local.stream.PrefsStore
import com.limelight.local.stream.StreamSettingsMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Local-only search history store (`ux-spec.md` §3.8, §3.9.5; `docs/specs/08-search.md`).
 *
 * Security & privacy guarantees:
 * - Persisted in a dedicated private [PrefsStore] (`depth3d_search_history`), excluded from
 *   Android cloud backup & device transfer by `res/xml/backup_rules.xml` & `backup_rules_s.xml`.
 * - Never writes raw query strings to logs or crash traces.
 * - Capped at [maxEntries] (default 20), normalized, deduplicated case-insensitively moving the
 *   most recent occurrence to the front.
 * - Respects 「设置 › 隐私 › 记住搜索记录」 (`StreamSettingsMapper.KEY_REMEMBER_SEARCH_HISTORY`,
 *   default `true`): turning it off immediately wipes stored history and blocks new writes.
 */
class SearchHistoryStore(
    private val historyStore: PrefsStore,
    private val settingsStore: PrefsStore,
    private val maxEntries: Int = SearchConfig.MAX_HISTORY_ENTRIES,
) {
    private val lock = Any()

    private val _rememberEnabled = MutableStateFlow(readRememberFlag())
    val rememberEnabled: StateFlow<Boolean> = _rememberEnabled.asStateFlow()

    private val _entries = MutableStateFlow(if (_rememberEnabled.value) readEntries() else emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun isRememberEnabled(): Boolean = synchronized(lock) {
        val enabled = readRememberFlag()
        if (_rememberEnabled.value != enabled) {
            _rememberEnabled.value = enabled
        }
        enabled
    }

    /**
     * Records a submitted search query when 「记住搜索记录」 is enabled.
     * Deduplicates case-insensitively (moving to front) and trims to [maxEntries].
     */
    fun record(rawQuery: String): List<String> = synchronized(lock) {
        val enabled = readRememberFlag()
        _rememberEnabled.value = enabled
        if (!enabled) {
            if (_entries.value.isNotEmpty()) {
                writeEntries(emptyList())
                _entries.value = emptyList()
            }
            return emptyList()
        }
        val normalized = SearchQueryNormalizer.normalize(rawQuery)
        if (normalized.isEmpty()) return _entries.value

        val updated = ArrayList<String>(maxEntries)
        updated += normalized
        for (existing in _entries.value) {
            if (updated.size >= maxEntries) break
            if (!existing.equals(normalized, ignoreCase = true)) {
                updated += existing
            }
        }
        writeEntries(updated)
        _entries.value = updated
        updated
    }

    /** Removes a single query entry (case-insensitive normalized match). */
    fun remove(rawQuery: String): List<String> = synchronized(lock) {
        val normalized = SearchQueryNormalizer.normalize(rawQuery)
        if (normalized.isEmpty()) return _entries.value
        val updated = _entries.value.filterNot { it.equals(normalized, ignoreCase = true) }
        if (updated.size != _entries.value.size) {
            writeEntries(updated)
            _entries.value = updated
        }
        updated
    }

    /** Clears all stored history and returns a snapshot suitable for immediate undo (`restore`). */
    fun clearWithUndoSnapshot(): List<String> = synchronized(lock) {
        val snapshot = _entries.value
        writeEntries(emptyList())
        _entries.value = emptyList()
        snapshot
    }

    /** Restores a previously captured undo snapshot when 「记住搜索记录」 is still enabled. */
    fun restore(snapshot: List<String>): List<String> = synchronized(lock) {
        if (!readRememberFlag()) {
            _rememberEnabled.value = false
            return emptyList()
        }
        val cleaned = sanitizeList(snapshot)
        writeEntries(cleaned)
        _entries.value = cleaned
        cleaned
    }

    /**
     * Updates 「记住搜索记录」 in settings. Turning it off immediately wipes stored history
     * and prevents further writes (`ux-spec.md` §3.8, §3.9.5).
     */
    fun setRememberEnabled(enabled: Boolean) = synchronized(lock) {
        StreamSettingsMapper.setRememberSearchHistory(settingsStore, enabled)
        _rememberEnabled.value = enabled
        if (!enabled) {
            writeEntries(emptyList())
            _entries.value = emptyList()
        }
    }

    /** Reloads flag & entries from backing stores (useful if settings changed externally). */
    fun reload() = synchronized(lock) {
        val enabled = readRememberFlag()
        _rememberEnabled.value = enabled
        if (!enabled) {
            writeEntries(emptyList())
            _entries.value = emptyList()
        } else {
            _entries.value = readEntries()
        }
    }

    private fun readRememberFlag(): Boolean =
        settingsStore.getBoolean(StreamSettingsMapper.KEY_REMEMBER_SEARCH_HISTORY, true)

    private fun readEntries(): List<String> {
        val raw = historyStore.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            json.decodeFromString(listSerializer, raw)
        }.getOrDefault(emptyList())
        return sanitizeList(parsed)
    }

    private fun writeEntries(list: List<String>) {
        if (list.isEmpty()) {
            historyStore.remove(KEY_HISTORY_JSON)
        } else {
            historyStore.putString(KEY_HISTORY_JSON, json.encodeToString(listSerializer, list))
        }
    }

    private fun sanitizeList(raw: List<String>): List<String> {
        val out = ArrayList<String>(minOf(raw.size, maxEntries))
        val seenLower = HashSet<String>()
        for (entry in raw) {
            if (out.size >= maxEntries) break
            val norm = SearchQueryNormalizer.normalize(entry)
            if (norm.isNotEmpty() && seenLower.add(norm.lowercase())) {
                out += norm
            }
        }
        return out
    }

    companion object {
        const val PREFS_NAME = "depth3d_search_history"
        const val KEY_HISTORY_JSON = "recent_queries_v1"

        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(String.serializer())
    }
}
