package com.limelight.local.stream

/**
 * Pure-JVM exponential backoff tracker for host `/info` probing and `/media` synchronization.
 * Retries transient failures on a bounded schedule and resets cleanly on Wi-Fi recovery or
 * host re-discovery.
 */
class HostRetryTracker(
    private val backoffScheduleMs: LongArray = DEFAULT_BACKOFF_MS,
) {
    private data class Entry(
        var inFlight: Boolean = false,
        var completed: Boolean = false,
        var failures: Int = 0,
        var nextRetryAtMs: Long = 0L,
    )

    private val entries = HashMap<String, Entry>()

    /** Attempts to acquire an execution slot for [key] at [nowMs]. Returns true if work should start now. */
    @Synchronized
    fun tryAcquire(key: String, nowMs: Long): Boolean {
        val e = entries.getOrPut(key) { Entry() }
        if (e.completed || e.inFlight) return false
        if (nowMs < e.nextRetryAtMs) return false
        e.inFlight = true
        return true
    }

    /** Marks [key] as permanently finished until [clear] or [resetAll]. */
    @Synchronized
    fun markCompleted(key: String) {
        val e = entries.getOrPut(key) { Entry() }
        e.inFlight = false
        e.completed = true
        e.failures = 0
        e.nextRetryAtMs = Long.MAX_VALUE
    }

    /**
     * Records a transient failure for [key] and returns the delay in milliseconds before the next
     * retry should run.
     */
    @Synchronized
    fun markTransientFailure(key: String, nowMs: Long): Long {
        val e = entries.getOrPut(key) { Entry() }
        e.inFlight = false
        val idx = e.failures.coerceIn(0, backoffScheduleMs.lastIndex)
        val delay = backoffScheduleMs[idx]
        e.failures++
        e.nextRetryAtMs = nowMs + delay
        return delay
    }

    /** Clears state for a single host (e.g., lost from mDNS or freshly paired). */
    @Synchronized
    fun clear(key: String) {
        entries.remove(key)
    }

    /** Resets all backoff & completion states (e.g., Wi-Fi recovered). */
    @Synchronized
    fun resetAll() {
        entries.clear()
    }

    companion object {
        val DEFAULT_BACKOFF_MS = longArrayOf(3_000L, 10_000L, 30_000L, 60_000L)
    }
}

/**
 * Simple synchronized bounded LRU cache (pure JVM so it works identically on Android and in unit tests).
 */
class BoundedLruCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "maxEntries must be > 0" }
    }

    private val map = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V): V? = map.put(key, value)

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()
}
