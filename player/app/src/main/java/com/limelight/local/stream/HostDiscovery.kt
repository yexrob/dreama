package com.limelight.local.stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.limelight.LimeLog
import com.limelight.nvstream.http.NvHTTP
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * One depth3d Host discovered over mDNS (_depth3d-host._tcp, docs/specs/host-protocol.md).
 */
data class DiscoveredHost(
    val id: String,
    val name: String,
    val address: String,
    val controlPort: Int,
    val gsPort: Int = NvHTTP.DEFAULT_HTTP_PORT,
    val pairMode: String? = null,
    val mediaPort: Int? = null,
    val mediaRevision: Int? = null,
    val protocolVersion: Int = HttpHostProtocolClient.PROTOCOL_VERSION,
) {
    val autoPairSupported: Boolean
        get() = pairMode.equals("auto", ignoreCase = true)

    /** Unique key per announced endpoint so a spoofed mDNS id at another IP cannot evict a real Host (#7). */
    val endpointKey: String
        get() = "${id.lowercase()}@${RawComputer.normalizeAddress(address)}:$controlPort"

    companion object {
        const val SERVICE_TYPE = "_depth3d-host._tcp."
        const val SERVICE_TYPE_NSD = "_depth3d-host._tcp"

        /**
         * Parses mDNS TXT key-value pairs into a [DiscoveredHost]. Pure function for unit tests.
         * Strictly adheres to `docs/specs/host-protocol.md` TXT keys (`v`, `name`, `id`, `pair`,
         * `media`, `mrev`, `gs`) — no non-spec keys (S3).
         */
        fun fromTxt(
            serviceName: String,
            address: String,
            controlPort: Int,
            txt: Map<String, String?>,
        ): DiscoveredHost? {
            if (address.isBlank() || controlPort !in 1..65535) return null
            val id = txt["id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val version = txt["v"]?.trim()?.toIntOrNull() ?: HttpHostProtocolClient.PROTOCOL_VERSION
            val name = txt["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: serviceName.ifBlank { address }
            val gsPort = txt["gs"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: NvHTTP.DEFAULT_HTTP_PORT
            val pairMode = txt["pair"]?.trim()?.takeIf { it.isNotEmpty() }
            val mediaPort = txt["media"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            val mediaRevision = txt["mrev"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            return DiscoveredHost(
                id = id,
                name = name,
                address = address,
                controlPort = controlPort,
                gsPort = gsPort,
                pairMode = pairMode,
                mediaPort = mediaPort,
                mediaRevision = mediaRevision,
                protocolVersion = version,
            )
        }
    }
}

/**
 * Pure-JVM registry storing discovered mDNS records keyed by `(serviceName, address, controlPort)`
 * so two hosts sharing the same mDNS service name at different addresses/ports never overwrite each
 * other (M1), while still deduplicating exact duplicate endpoints (#7).
 */
internal class DiscoveredHostRegistry(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    data class EntryKey(val serviceName: String, val address: String, val controlPort: Int)
    private data class Entry(val host: DiscoveredHost, val lastSeenAtMs: Long)

    private val entries = LinkedHashMap<EntryKey, Entry>()

    fun put(serviceName: String, host: DiscoveredHost): EntryKey {
        val key = EntryKey(serviceName, RawComputer.normalizeAddress(host.address), host.controlPort)
        entries[key] = Entry(host, clockMs())
        return key
    }

    fun removeKey(key: EntryKey): Boolean = entries.remove(key) != null

    /**
     * Removes ONLY stale entries under [serviceName] that have not been freshly resolved within
     * [staleAfterMs] (#6), so an unaddressed mDNS goodbye from another peer sharing the same
     * service name cannot evict a live Host.
     */
    fun removeStaleByServiceName(serviceName: String, staleAfterMs: Long): Boolean {
        val now = clockMs()
        val toRemove = entries.entries
            .filter { (k, v) -> k.serviceName == serviceName && now - v.lastSeenAtMs >= staleAfterMs }
            .map { it.key }
        if (toRemove.isEmpty()) return false
        toRemove.forEach { entries.remove(it) }
        return true
    }

    fun hasEntriesForServiceName(serviceName: String): Boolean =
        entries.keys.any { it.serviceName == serviceName }

    fun snapshot(): List<DiscoveredHost> = HostDiscovery.deduplicateEndpoints(entries.values.map { it.host })

    fun clear() = entries.clear()
}

/**
 * Watches the local network for `_depth3d-host._tcp` mDNS announcements via Android's system
 * NsdManager so it shares the system mDNS daemon cleanly with AirPlay and GameStream discovery.
 */
class HostDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val lock = Any()
    private val registry = DiscoveredHostRegistry()
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private val activeCallbacks = LinkedHashMap<String, Any>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "depth3d-host-mdns").apply { isDaemon = true }
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var started = false

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> get() = _hosts

    fun start() {
        val manager = nsd ?: return
        synchronized(lock) {
            if (started) return
            started = true
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    LimeLog.info("depth3d: host mDNS discovery started")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("_depth3d-host")) return
                    resolve(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val key = serviceInfo.serviceName ?: return
                    val lostAddr = pickAddress(serviceInfo)
                    val lostPort = serviceInfo.port
                    var shouldReResolve = false
                    synchronized(lock) {
                        val removed = if (lostAddr != null && lostPort in 1..65535) {
                            removeCallbackLocked(key)
                            registry.removeKey(
                                DiscoveredHostRegistry.EntryKey(key, RawComputer.normalizeAddress(lostAddr), lostPort),
                            )
                        } else {
                            // #6: When Android's onServiceLost omits address/port, never wipe freshly
                            // resolved hosts sharing the same service name; drop only stale entries and
                            // re-resolve if live entries remain.
                            val pruned = registry.removeStaleByServiceName(key, LOST_STALE_WINDOW_MS)
                            if (registry.hasEntriesForServiceName(key)) {
                                shouldReResolve = true
                            } else {
                                removeCallbackLocked(key)
                            }
                            pruned
                        }
                        if (removed) {
                            publishLocked()
                        }
                    }
                    if (shouldReResolve) {
                        resolve(serviceInfo)
                    }
                    LimeLog.info("depth3d: host mDNS lost $key (reResolve=$shouldReResolve)")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    LimeLog.info("depth3d: host mDNS discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    LimeLog.warning("depth3d: host mDNS start failed: $errorCode")
                    synchronized(lock) {
                        started = false
                        discoveryListener = null
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    LimeLog.warning("depth3d: host mDNS stop failed: $errorCode")
                }
            }
            discoveryListener = listener
            try {
                manager.discoverServices(DiscoveredHost.SERVICE_TYPE_NSD, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                LimeLog.warning("depth3d: host mDNS discoverServices failed: ${e.javaClass.simpleName}")
                started = false
                discoveryListener = null
            }
        }
    }

    fun stop() {
        val manager = nsd ?: return
        synchronized(lock) {
            if (!started) return
            started = false
            pendingResolves.clear()
            resolving = false
            val keys = activeCallbacks.keys.toList()
            keys.forEach { removeCallbackLocked(it) }
            val listener = discoveryListener
            discoveryListener = null
            if (listener != null) {
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (_: RuntimeException) {
                }
            }
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        val manager = nsd ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val key = info.serviceName ?: return
            synchronized(lock) {
                if (!started) return
                removeCallbackLocked(key)
                val cb = object : NsdManager.ServiceInfoCallback {
                    @Volatile var lastEntryKey: DiscoveredHostRegistry.EntryKey? = null

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        LimeLog.warning("depth3d: host mDNS callback failed for $key: $errorCode")
                    }

                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        val newKey = handleResolved(updated, previousKey = lastEntryKey)
                        if (newKey != null) {
                            lastEntryKey = newKey
                        }
                    }

                    override fun onServiceLost() {
                        synchronized(lock) {
                            // #6: Remove ONLY this callback's exact resolved endpoint; if this callback
                            // never resolved an IPv4 endpoint, never evict other hosts sharing the name!
                            val removed = lastEntryKey?.let { registry.removeKey(it) } ?: false
                            if (removed) {
                                publishLocked()
                            }
                        }
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                try {
                    manager.registerServiceInfoCallback(info, executor, cb)
                    activeCallbacks[key] = cb
                } catch (e: RuntimeException) {
                    LimeLog.warning("depth3d: host mDNS registerServiceInfoCallback failed: ${e.javaClass.simpleName}")
                }
            }
        } else {
            synchronized(lock) {
                if (!started) return
                pendingResolves.addLast(info)
                if (!resolving) {
                    drainResolveQueueLocked()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun drainResolveQueueLocked() {
        val manager = nsd ?: return
        if (!started || pendingResolves.isEmpty()) {
            resolving = false
            return
        }
        val next = pendingResolves.removeFirst()
        resolving = true
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    LimeLog.warning("depth3d: host mDNS resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    synchronized(lock) { drainResolveQueueLocked() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handleResolved(serviceInfo)
                    synchronized(lock) { drainResolveQueueLocked() }
                }
            })
        } catch (e: RuntimeException) {
            LimeLog.warning("depth3d: host mDNS resolveService exception: ${e.javaClass.simpleName}")
            resolving = false
        }
    }

    private fun handleResolved(
        info: NsdServiceInfo,
        previousKey: DiscoveredHostRegistry.EntryKey? = null,
    ): DiscoveredHostRegistry.EntryKey? {
        val addr = pickAddress(info) ?: return null
        val txt = LinkedHashMap<String, String?>()
        info.attributes?.forEach { (k, v) ->
            txt[k] = v?.let { String(it, StandardCharsets.UTF_8) }
        }
        val serviceName = info.serviceName.orEmpty()
        val host = DiscoveredHost.fromTxt(serviceName, addr, info.port, txt) ?: return null
        val newKey: DiscoveredHostRegistry.EntryKey
        synchronized(lock) {
            if (!started) return null
            if (previousKey != null &&
                (previousKey.address != RawComputer.normalizeAddress(host.address) || previousKey.controlPort != host.controlPort)
            ) {
                registry.removeKey(previousKey)
            }
            newKey = registry.put(serviceName, host)
            publishLocked()
        }
        LimeLog.info("depth3d: host mDNS resolved ${host.name} at ${host.address}:${host.controlPort} (gs=${host.gsPort}, pair=${host.pairMode})")
        return newKey
    }

    private fun pickAddress(info: NsdServiceInfo): String? {
        val addrs = ArrayList<InetAddress>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            addrs.addAll(info.hostAddresses)
        }
        @Suppress("DEPRECATION")
        info.host?.let { addrs.add(it) }
        return chooseAddress(addrs)
    }

    companion object {
        internal const val LOST_STALE_WINDOW_MS = 10_000L

        /**
         * Selects the IPv4 address to dial out of everything mDNS answered with.
         * Per `docs/specs/host-protocol.md`, all control API and GameStream pairing connections to
         * a depth3d Host must share the same IPv4 address; if mDNS has only reported IPv6 so far,
         * return null and wait for the next update containing IPv4.
         */
        internal fun chooseAddress(addrs: List<InetAddress>): String? =
            addrs.firstOrNull {
                it is Inet4Address &&
                    !it.isLoopbackAddress &&
                    !it.isAnyLocalAddress &&
                    !it.isMulticastAddress &&
                    !it.isLinkLocalAddress
            }?.hostAddress

        /**
         * Deduplicates announced services by `(id, address, controlPort)` instead of `id` alone so
         * an imposter copying a real Host's mDNS `id` at another IP cannot evict the real Host (#7).
         */
        internal fun deduplicateEndpoints(hosts: Collection<DiscoveredHost>): List<DiscoveredHost> =
            hosts.distinctBy { it.endpointKey }
    }

    private fun removeCallbackLocked(key: String) {
        val cb = activeCallbacks.remove(key) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                nsd?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback)
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun publishLocked() {
        _hosts.value = registry.snapshot()
    }
}

/**
 * One generic LAN service discovered over Android NsdManager (mDNS/Bonjour), reusing
 * [HostDiscovery.chooseAddress] and the same API 34+ `ServiceInfoCallback` / legacy resolve queue.
 */
data class DiscoveredLanService(
    val serviceType: String,
    val serviceName: String,
    val address: String,
    val port: Int,
    val txt: Map<String, String?> = emptyMap(),
) {
    val endpointKey: String
        get() = "${serviceType.lowercase()}@${RawComputer.normalizeAddress(address)}:$port"

    fun suggestedUrl(): String {
        val scheme = if (port == 443 || txt["scheme"].equals("https", ignoreCase = true)) "https" else "http"
        val host = HttpHostProtocolClient.formatAuthorityHost(address)
        val omitPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (omitPort) "$scheme://$host" else "$scheme://$host:$port"
    }
}

/**
 * Reusable NsdManager browser for any mDNS service type (e.g. `_jellyfin._tcp`), sharing the
 * same system NSD lifecycle and IPv4 address filtering as [HostDiscovery].
 */
class NsdServiceBrowser(
    context: Context,
    rawServiceType: String,
) {
    val serviceType: String = normalizeServiceType(rawServiceType)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val lock = Any()
    private val byEndpoint = LinkedHashMap<String, DiscoveredLanService>()
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private val activeCallbacks = LinkedHashMap<String, Any>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "depth3d-nsd-$serviceType").apply { isDaemon = true }
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var started = false

    private val _services = MutableStateFlow<List<DiscoveredLanService>>(emptyList())
    val services: StateFlow<List<DiscoveredLanService>> get() = _services

    fun start() {
        val manager = nsd ?: return
        synchronized(lock) {
            if (started) return
            started = true
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    LimeLog.info("depth3d: plugin mDNS discovery started for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    resolve(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val name = serviceInfo.serviceName ?: return
                    synchronized(lock) {
                        val changed = byEndpoint.entries.removeIf { it.value.serviceName == name }
                        removeCallbackLocked(name)
                        if (changed) publishLocked()
                    }
                }

                override fun onDiscoveryStopped(regType: String) = Unit

                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    LimeLog.warning("depth3d: plugin mDNS start failed for $serviceType: $errorCode")
                    synchronized(lock) {
                        started = false
                        discoveryListener = null
                    }
                }

                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) = Unit
            }
            discoveryListener = listener
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                LimeLog.warning("depth3d: plugin mDNS discoverServices failed for $serviceType: ${e.javaClass.simpleName}")
                started = false
                discoveryListener = null
            }
        }
    }

    fun stop() {
        val manager = nsd ?: return
        synchronized(lock) {
            if (!started) return
            started = false
            pendingResolves.clear()
            resolving = false
            activeCallbacks.keys.toList().forEach { removeCallbackLocked(it) }
            val listener = discoveryListener
            discoveryListener = null
            if (listener != null) {
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (_: RuntimeException) {
                }
            }
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        val manager = nsd ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val key = info.serviceName ?: return
            synchronized(lock) {
                if (!started) return
                removeCallbackLocked(key)
                val cb = object : NsdManager.ServiceInfoCallback {
                    @Volatile var lastEndpoint: String? = null

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        val ep = handleResolved(updated, lastEndpoint)
                        if (ep != null) lastEndpoint = ep
                    }

                    override fun onServiceLost() {
                        synchronized(lock) {
                            val ep = lastEndpoint
                            if (ep != null && byEndpoint.remove(ep) != null) {
                                publishLocked()
                            }
                        }
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                try {
                    manager.registerServiceInfoCallback(info, executor, cb)
                    activeCallbacks[key] = cb
                } catch (_: RuntimeException) {
                }
            }
        } else {
            synchronized(lock) {
                if (!started) return
                pendingResolves.addLast(info)
                if (!resolving) drainResolveQueueLocked()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun drainResolveQueueLocked() {
        val manager = nsd ?: return
        if (!started || pendingResolves.isEmpty()) {
            resolving = false
            return
        }
        val next = pendingResolves.removeFirst()
        resolving = true
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { drainResolveQueueLocked() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handleResolved(serviceInfo, null)
                    synchronized(lock) { drainResolveQueueLocked() }
                }
            })
        } catch (_: RuntimeException) {
            resolving = false
        }
    }

    private fun handleResolved(info: NsdServiceInfo, previousEndpoint: String?): String? {
        val addrs = ArrayList<InetAddress>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            addrs.addAll(info.hostAddresses)
        }
        @Suppress("DEPRECATION")
        info.host?.let { addrs.add(it) }
        val addr = HostDiscovery.chooseAddress(addrs) ?: return null
        val port = info.port
        if (port !in 1..65535) return null
        val txt = LinkedHashMap<String, String?>()
        info.attributes?.forEach { (k, v) ->
            txt[k] = v?.let { String(it, StandardCharsets.UTF_8) }
        }
        val svc = DiscoveredLanService(
            serviceType = serviceType,
            serviceName = info.serviceName?.takeIf { it.isNotBlank() } ?: addr,
            address = addr,
            port = port,
            txt = txt,
        )
        synchronized(lock) {
            if (!started) return null
            if (previousEndpoint != null && previousEndpoint != svc.endpointKey) {
                byEndpoint.remove(previousEndpoint)
            }
            byEndpoint[svc.endpointKey] = svc
            publishLocked()
        }
        return svc.endpointKey
    }

    private fun removeCallbackLocked(key: String) {
        val cb = activeCallbacks.remove(key) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                nsd?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback)
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun publishLocked() {
        _services.value = byEndpoint.values.toList()
    }

    companion object {
        fun normalizeServiceType(raw: String): String =
            raw.trim().removeSuffix(".local.").removeSuffix(".local").removeSuffix(".")
    }
}
