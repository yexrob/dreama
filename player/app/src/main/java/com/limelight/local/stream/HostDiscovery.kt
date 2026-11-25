package com.limelight.local.stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.limelight.LimeLog
import com.limelight.nvstream.http.NvHTTP
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

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
 * Pure-JVM registry storing discovered mDNS records keyed by `(serviceName, address, port)`
 * so two peers sharing the same mDNS service name at different addresses/ports never overwrite
 * each other (M1), while still pruning only stale entries on unaddressed `onServiceLost` (#6).
 */
internal class DiscoveredEndpointRegistry<T>(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    data class EntryKey(val serviceName: String, val address: String, val port: Int)
    private data class Entry<T>(val value: T, val lastSeenAtMs: Long)

    private val entries = LinkedHashMap<EntryKey, Entry<T>>()

    fun put(serviceName: String, address: String, port: Int, value: T): EntryKey {
        val key = EntryKey(serviceName, RawComputer.normalizeAddress(address), port)
        entries[key] = Entry(value, clockMs())
        return key
    }

    fun removeKey(key: EntryKey): Boolean = entries.remove(key) != null

    /**
     * Removes ONLY stale entries under [serviceName] that have not been freshly resolved within
     * [staleAfterMs] (#6), so an unaddressed mDNS goodbye from another peer sharing the same
     * service name cannot evict a live endpoint.
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

    fun values(): List<T> = entries.values.map { it.value }

    fun clear() = entries.clear()
}

/**
 * Legacy type alias/wrapper preserving [HostDiscoveryAddressTest] API surface on top of the
 * unified [DiscoveredEndpointRegistry].
 */
internal class DiscoveredHostRegistry(
    clockMs: () -> Long = { System.currentTimeMillis() },
) {
    data class EntryKey(val serviceName: String, val address: String, val controlPort: Int)

    private val delegate = DiscoveredEndpointRegistry<DiscoveredHost>(clockMs)

    fun put(serviceName: String, host: DiscoveredHost): EntryKey {
        val k = delegate.put(serviceName, host.address, host.controlPort, host)
        return EntryKey(k.serviceName, k.address, k.port)
    }

    fun removeKey(key: EntryKey): Boolean =
        delegate.removeKey(DiscoveredEndpointRegistry.EntryKey(key.serviceName, key.address, key.controlPort))

    fun removeStaleByServiceName(serviceName: String, staleAfterMs: Long): Boolean =
        delegate.removeStaleByServiceName(serviceName, staleAfterMs)

    fun hasEntriesForServiceName(serviceName: String): Boolean =
        delegate.hasEntriesForServiceName(serviceName)

    fun snapshot(): List<DiscoveredHost> = HostDiscovery.deduplicateEndpoints(delegate.values())

    fun clear() = delegate.clear()
}

/**
 * One generic LAN service discovered over Android NsdManager (mDNS/Bonjour) or built-in UDP
 * server discovery (`udp:jellyfin`, `udp:emby`).
 */
data class DiscoveredLanService(
    val serviceType: String,
    val serviceName: String,
    val address: String,
    val port: Int,
    val txt: Map<String, String?> = emptyMap(),
) {
    val isUdpDiscovery: Boolean
        get() = serviceType.startsWith("udp:", ignoreCase = true)

    /**
     * Deduplication key for plugin discovery services (both UDP and mDNS): uses `serviceType#id`
     * when a server advertises an `id`, falling back to `serviceType@addr:port`.
     * Host discovery (`HostDiscovery`) never uses this key — it passes its own `id@addr:port`
     * `deduplicateKey` to [NsdServiceBrowser] to match `main` exactly (#1).
     */
    val endpointKey: String
        get() {
            val id = txt["id"]?.trim()?.takeIf { it.isNotEmpty() }
            if (id != null) {
                return "${serviceType.lowercase(Locale.ROOT)}#${id.lowercase(Locale.ROOT)}"
            }
            return "${serviceType.lowercase(Locale.ROOT)}@${RawComputer.normalizeAddress(address)}:$port"
        }

    /**
     * Returns the exact URL displayed in the discovery list AND prefilled into the plugin form.
     * - For built-in UDP probes (`udp:*`), uses the validated private-LAN `Address` returned by the probe.
     * - For mDNS services, NEVER trusts untrusted TXT `address` fields; always constructs the URL
     *   strictly from the resolved IPv4 [address] and [port].
     */
    fun suggestedUrl(): String {
        if (isUdpDiscovery) {
            val raw = txt["address"]?.trim()?.takeIf { it.isNotEmpty() }
            if (raw != null && UdpLanServerDiscovery.parsePrivateServerAddress(raw) != null) {
                return raw.trimEnd('/')
            }
        }
        val scheme = if (port == 443 || txt["scheme"].equals("https", ignoreCase = true)) "https" else "http"
        val host = HttpHostProtocolClient.formatAuthorityHost(address)
        val omitPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (omitPort) "$scheme://$host" else "$scheme://$host:$port"
    }
}

/**
 * Single unified Android [NsdManager] browser shared by BOTH [HostDiscovery] (`_depth3d-host._tcp`)
 * and plugin mDNS discovery (`config[].discover`), supporting API 34+ `ServiceInfoCallback`,
 * legacy queued `resolveService`, IPv4 filtering via [HostDiscovery.chooseAddress], and stale-lost
 * protection (#6) so unaddressed mDNS goodbyes never evict live peers sharing a service name.
 */
class NsdServiceBrowser(
    context: Context,
    rawServiceType: String,
    private val serviceTypeFilter: (String) -> Boolean = { true },
    private val requirePrivateLanAddress: Boolean = true,
    private val acceptResolved: (DiscoveredLanService) -> Boolean = { true },
    private val deduplicateKey: (DiscoveredLanService) -> String = { it.endpointKey },
    private val logTag: String = "plugin mDNS",
    private val onServicesChanged: ((List<DiscoveredLanService>) -> Unit)? = null,
    private val onEndpointResolved: ((DiscoveredLanService) -> Unit)? = null,
) {
    val serviceType: String = normalizeServiceType(rawServiceType)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val lock = Any()
    private val registry = DiscoveredEndpointRegistry<DiscoveredLanService>()
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
                    LimeLog.info("depth3d: $logTag discovery started for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceTypeFilter(serviceInfo.serviceType.orEmpty())) return
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
                                DiscoveredEndpointRegistry.EntryKey(
                                    key,
                                    RawComputer.normalizeAddress(lostAddr),
                                    lostPort,
                                ),
                            )
                        } else {
                            // #6: When Android's onServiceLost omits address/port, never wipe freshly
                            // resolved endpoints sharing the same service name; drop only stale
                            // entries and re-resolve if live entries remain.
                            val pruned = registry.removeStaleByServiceName(key, HostDiscovery.LOST_STALE_WINDOW_MS)
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
                    LimeLog.info("depth3d: $logTag lost $key (reResolve=$shouldReResolve)")
                }

                override fun onDiscoveryStopped(regType: String) {
                    LimeLog.info("depth3d: $logTag discovery stopped for $serviceType")
                }

                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    LimeLog.warning("depth3d: $logTag start failed for $serviceType: $errorCode")
                    synchronized(lock) {
                        started = false
                        discoveryListener = null
                    }
                }

                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                    LimeLog.warning("depth3d: $logTag stop failed for $serviceType: $errorCode")
                }
            }
            discoveryListener = listener
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                LimeLog.warning("depth3d: $logTag discoverServices failed for $serviceType: ${e.javaClass.simpleName}")
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
                    @Volatile var lastEntryKey: DiscoveredEndpointRegistry.EntryKey? = null

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        LimeLog.warning("depth3d: $logTag callback failed for $key: $errorCode")
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
                            // never resolved an IPv4 endpoint, never evict other peers sharing the name.
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
                    LimeLog.warning("depth3d: $logTag registerServiceInfoCallback failed: ${e.javaClass.simpleName}")
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
                    LimeLog.warning("depth3d: $logTag resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    synchronized(lock) { drainResolveQueueLocked() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handleResolved(serviceInfo, previousKey = null)
                    synchronized(lock) { drainResolveQueueLocked() }
                }
            })
        } catch (e: RuntimeException) {
            LimeLog.warning("depth3d: $logTag resolveService exception: ${e.javaClass.simpleName}")
            resolving = false
        }
    }

    private fun handleResolved(
        info: NsdServiceInfo,
        previousKey: DiscoveredEndpointRegistry.EntryKey?,
    ): DiscoveredEndpointRegistry.EntryKey? {
        val addr = pickAddress(info) ?: return null
        val txt = LinkedHashMap<String, String?>()
        info.attributes?.forEach { (k, v) ->
            txt[k] = v?.let { String(it, StandardCharsets.UTF_8) }
        }
        return ingestResolvedEndpoint(
            rawName = info.serviceName.orEmpty(),
            addr = addr,
            port = info.port,
            txt = txt,
            previousKey = previousKey,
            requireStarted = true,
        )
    }

    internal fun ingestResolvedEndpoint(
        rawName: String,
        addr: String,
        port: Int,
        txt: Map<String, String?>,
        previousKey: DiscoveredEndpointRegistry.EntryKey? = null,
        requireStarted: Boolean = false,
    ): DiscoveredEndpointRegistry.EntryKey? {
        if (port !in 1..65535) return null
        if (requirePrivateLanAddress && !UdpLanServerDiscovery.isPrivateLanIpLiteral(addr)) return null
        val svc = DiscoveredLanService(
            serviceType = serviceType,
            serviceName = rawName.ifBlank { addr },
            address = addr,
            port = port,
            txt = txt,
        )
        if (!acceptResolved(svc)) return null
        val newKey: DiscoveredEndpointRegistry.EntryKey
        synchronized(lock) {
            if (requireStarted && !started) return null
            val normAddr = RawComputer.normalizeAddress(addr)
            if (previousKey != null && (previousKey.address != normAddr || previousKey.port != port)) {
                registry.removeKey(previousKey)
            }
            newKey = registry.put(rawName, addr, port, svc)
            publishLocked()
        }
        onEndpointResolved?.invoke(svc)
        return newKey
    }

    private fun pickAddress(info: NsdServiceInfo): String? {
        val addrs = ArrayList<InetAddress>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            addrs.addAll(info.hostAddresses)
        }
        @Suppress("DEPRECATION")
        info.host?.let { addrs.add(it) }
        return HostDiscovery.chooseAddress(addrs)
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
        val list = registry.values().distinctBy(deduplicateKey)
        _services.value = list
        onServicesChanged?.invoke(list)
    }

    companion object {
        fun normalizeServiceType(raw: String): String =
            raw.trim().removeSuffix(".local.").removeSuffix(".local").removeSuffix(".")
    }
}

/**
 * Watches the local network for `_depth3d-host._tcp` mDNS announcements by delegating to the
 * single shared [NsdServiceBrowser] engine so Host and plugin mDNS discovery share one NSD
 * implementation while preserving every Host discovery invariant (`id@addr:port` deduplication
 * and dropping records without valid TXT `id` before they enter the registry).
 */
class HostDiscovery(context: Context) {
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> get() = _hosts

    internal val browser: NsdServiceBrowser = NsdServiceBrowser(
        context = context,
        rawServiceType = DiscoveredHost.SERVICE_TYPE_NSD,
        serviceTypeFilter = { it.contains("_depth3d-host") },
        requirePrivateLanAddress = false,
        acceptResolved = { svc ->
            DiscoveredHost.fromTxt(svc.serviceName, svc.address, svc.port, svc.txt) != null
        },
        deduplicateKey = { svc ->
            val id = svc.txt["id"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
            "$id@${RawComputer.normalizeAddress(svc.address)}:${svc.port}"
        },
        logTag = "host mDNS",
        onServicesChanged = { services ->
            val mapped = services.mapNotNull { svc ->
                DiscoveredHost.fromTxt(svc.serviceName, svc.address, svc.port, svc.txt)
            }
            _hosts.value = deduplicateEndpoints(mapped)
        },
        onEndpointResolved = { svc ->
            DiscoveredHost.fromTxt(svc.serviceName, svc.address, svc.port, svc.txt)?.let { host ->
                LimeLog.info(
                    "depth3d: host mDNS resolved ${host.name} at ${host.address}:${host.controlPort} (gs=${host.gsPort}, pair=${host.pairMode})",
                )
            }
        },
    )

    fun start() = browser.start()

    fun stop() = browser.stop()

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
}

/**
 * Host built-in UDP broadcast discovery protocols declared via `config[].discover` (e.g.
 * `"udp:jellyfin"`, `"udp:emby"`). The host performs the UDP 7359 broadcast probe itself;
 * plugins never receive raw UDP socket access.
 */
enum class UdpDiscoveryProtocol(
    val wireValue: String,
    val probeMessage: String,
    val port: Int = 7359,
) {
    JELLYFIN("udp:jellyfin", "who is JellyfinServer?", 7359),
    EMBY("udp:emby", "who is EmbyServer?", 7359),
    ;

    companion object {
        const val DEFAULT_PORT = 7359

        fun fromWire(raw: String?): UdpDiscoveryProtocol? =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * Host-side UDP 7359 LAN server discovery for Jellyfin / Emby (`config[].discover = "udp:jellyfin"`
 * or `"udp:emby"`):
 * - Broadcasts the protocol probe string to every active IPv4 network interface's broadcast address
 *   (plus `255.255.255.255`).
 * - Collects JSON replies (`{"Address":"...","Name":"...","Id":"..."}`) within [timeoutMs] (default 2s).
 * - Deduplicates by server `Id`.
 * - Accepts ONLY private LAN IP addresses (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, ULA `fc00::/7`);
 *   rejects public IPs, link-local, multicast, unspecified, hostnames, and loopback (except when
 *   `allowLoopbackForTesting = true` in loopback unit tests).
 * - Closes its [DatagramSocket] promptly when cancelled or when [onFound] finishes.
 */
object UdpLanServerDiscovery {
    const val DEFAULT_TIMEOUT_MS = 2_000L
    const val REPROBE_INTERVAL_MS = 5_000L
    const val MAX_DISCOVERED_SERVERS = 32
    private const val MAX_PACKET_BYTES = 4096
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ParsedPrivateAddress(
        val scheme: String,
        val host: String,
        val port: Int,
        val normalizedUrl: String,
    )

    suspend fun discover(
        protocol: UdpDiscoveryProtocol,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        targetAddresses: List<InetAddress>? = null,
        targetPort: Int = protocol.port,
        allowLoopbackForTesting: Boolean = false,
        onFound: ((List<DiscoveredLanService>) -> Unit)? = null,
    ): List<DiscoveredLanService> = withContext(Dispatchers.IO) {
        val destinations = targetAddresses ?: collectBroadcastAddresses()
        if (destinations.isEmpty()) return@withContext emptyList()

        val foundById = LinkedHashMap<String, DiscoveredLanService>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            val activeSocket = socket
            coroutineScope {
                // Unblock socket.receive() immediately when the caller coroutine is cancelled (e.g. page exit)
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { activeSocket.close() }
                    }
                }
                try {
                    activeSocket.broadcast = true
                    val probeBytes = protocol.probeMessage.toByteArray(StandardCharsets.UTF_8)
                    for (dest in destinations) {
                        try {
                            activeSocket.send(DatagramPacket(probeBytes, probeBytes.size, dest, targetPort))
                        } catch (_: Exception) {
                        }
                    }

                    val deadlineMs = System.currentTimeMillis() + timeoutMs.coerceAtLeast(100L)
                    val buffer = ByteArray(MAX_PACKET_BYTES)
                    while (coroutineContext.isActive && !activeSocket.isClosed) {
                        val remaining = (deadlineMs - System.currentTimeMillis()).toInt()
                        if (remaining <= 0) break
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            activeSocket.soTimeout = remaining.coerceAtLeast(1)
                            activeSocket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        } catch (_: SocketException) {
                            // Socket closed on cancellation / page exit
                            break
                        }
                        val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                        val parsed = parseResponsePayload(
                            protocol = protocol,
                            payload = payload,
                            allowLoopbackForTesting = allowLoopbackForTesting,
                        ) ?: continue
                        val idKey = parsed.txt["id"]?.lowercase(Locale.ROOT) ?: parsed.endpointKey
                        if (idKey !in foundById) {
                            if (foundById.size >= MAX_DISCOVERED_SERVERS) {
                                continue
                            }
                            foundById[idKey] = parsed
                            onFound?.invoke(foundById.values.toList())
                        }
                    }
                } finally {
                    closer.cancel()
                    runCatching { activeSocket.close() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketException) {
            LimeLog.warning("depth3d: UDP LAN discovery socket error (${protocol.wireValue}): ${e.message}")
        } catch (e: Exception) {
            LimeLog.warning("depth3d: UDP LAN discovery error (${protocol.wireValue}): ${e.javaClass.simpleName}")
        } finally {
            runCatching { socket?.close() }
        }
        foundById.values.toList()
    }

    internal fun parseResponsePayload(
        protocol: UdpDiscoveryProtocol,
        payload: String,
        allowLoopbackForTesting: Boolean = false,
    ): DiscoveredLanService? {
        val obj = runCatching { json.parseToJsonElement(payload.trim()) as? JsonObject }.getOrNull() ?: return null
        val id = (obj["Id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rawAddr = (obj["Address"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsedAddr = parsePrivateServerAddress(rawAddr, allowLoopbackForTesting) ?: return null
        val name = (obj["Name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: parsedAddr.host
        return DiscoveredLanService(
            serviceType = protocol.wireValue,
            serviceName = name,
            address = parsedAddr.host,
            port = parsedAddr.port,
            txt = mapOf(
                "id" to id,
                "scheme" to parsedAddr.scheme,
                "address" to parsedAddr.normalizedUrl,
            ),
        )
    }

    /**
     * Validates that [rawAddress] is an `http://` or `https://` URL targeting a private LAN IP
     * literal (RFC1918 IPv4 or IPv6 ULA). Public IPs, link-local, multicast, unspecified,
     * hostnames, and loopback (unless [allowLoopbackForTesting]) are rejected.
     */
    internal fun parsePrivateServerAddress(
        rawAddress: String,
        allowLoopbackForTesting: Boolean = false,
    ): ParsedPrivateAddress? {
        val uri = runCatching { URI(rawAddress.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val rawHost = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() } ?: return null
        if (!isPrivateLanIpLiteral(rawHost, allowLoopbackForTesting)) return null
        val port = if (uri.port in 1..65535) uri.port else if (scheme == "https") 443 else 80
        val normalized = rawAddress.trim().trimEnd('/')
        return ParsedPrivateAddress(
            scheme = scheme,
            host = rawHost,
            port = port,
            normalizedUrl = normalized,
        )
    }

    internal fun isPrivateLanIpLiteral(host: String, allowLoopbackForTesting: Boolean = false): Boolean {
        val ipv4 = parseIpv4Bytes(host)
        if (ipv4 != null) {
            val b0 = ipv4[0].toInt() and 0xFF
            val b1 = ipv4[1].toInt() and 0xFF
            if (b0 == 127) return allowLoopbackForTesting
            // RFC1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
            return b0 == 10 ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 168)
        }
        // Only allow numeric IPv6 literals (must contain ':'), never trigger DNS for arbitrary hostnames
        if (host.contains(':')) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address ?: return false
            if (addr.isLoopbackAddress) return allowLoopbackForTesting
            if (addr.isLinkLocalAddress || addr.isMulticastAddress || addr.isAnyLocalAddress) return false
            val firstByte = addr.address[0].toInt() and 0xFF
            // IPv6 Unique Local Address (fc00::/7)
            return (firstByte and 0xFE) == 0xFC
        }
        return false
    }

    private fun parseIpv4Bytes(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0..3) {
            val seg = parts[i]
            if (seg.isEmpty() || seg.length > 3 || !seg.all { it in '0'..'9' }) return null
            if (seg.length > 1 && seg[0] == '0') return null
            val v = seg.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    internal fun collectBroadcastAddresses(): List<InetAddress> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val bcast = ia.broadcast ?: continue
                    if (bcast is Inet4Address && seen.add(bcast.hostAddress.orEmpty())) {
                        out += bcast
                    }
                }
            }
        } catch (_: Exception) {
        }
        val limited = runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()
        if (limited != null && seen.add(limited.hostAddress.orEmpty())) {
            out += limited
        }
        return out
    }
}

/**
 * Pure state tracker for periodic UDP LAN discovery rounds:
 * - Accepts incremental partial updates during an active round via [onPartial] (capped at [maxEntries]).
 * - Finalizes each round via [onRoundComplete]: entries missing for [evictAfterConsecutiveMisses]
 *   consecutive rounds (default 2) are evicted BEFORE adding newly discovered servers so offline
 *   hosts never stay stuck or consume the 32-server cap.
 * - Computes remaining sleep delay via [nextDelayMs] so re-probes maintain a fixed cadence measured
 *   from the START of each round (default 5s) instead of `probeDuration + 5s`.
 */
class UdpDiscoveryWindow(
    private val maxEntries: Int = UdpLanServerDiscovery.MAX_DISCOVERED_SERVERS,
    private val evictAfterConsecutiveMisses: Int = DEFAULT_EVICT_AFTER_MISSES,
) {
    private data class Tracked(val service: DiscoveredLanService, val consecutiveMisses: Int)

    private val entries = LinkedHashMap<String, Tracked>()
    private var completedRoundId = 0

    @Synchronized
    fun onPartial(roundId: Int, partial: List<DiscoveredLanService>): List<DiscoveredLanService> {
        if (roundId <= completedRoundId) return snapshotLocked()
        for (svc in partial) {
            val key = svc.endpointKey
            if (key in entries || entries.size < maxEntries) {
                entries[key] = Tracked(svc, consecutiveMisses = 0)
            }
        }
        return snapshotLocked()
    }

    @Synchronized
    fun onRoundComplete(roundId: Int, foundInRound: List<DiscoveredLanService>): List<DiscoveredLanService> {
        if (roundId > completedRoundId) {
            completedRoundId = roundId
        }
        val seenKeys = LinkedHashSet<String>(foundInRound.size)
        for (svc in foundInRound) {
            seenKeys += svc.endpointKey
        }
        // Evict stale entries FIRST so newly discovered servers can reclaim freed slots up to maxEntries.
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in seenKeys) {
                val misses = entry.value.consecutiveMisses + 1
                if (misses >= evictAfterConsecutiveMisses) {
                    iter.remove()
                } else {
                    entry.setValue(entry.value.copy(consecutiveMisses = misses))
                }
            }
        }
        for (svc in foundInRound) {
            val key = svc.endpointKey
            if (key in entries || entries.size < maxEntries) {
                entries[key] = Tracked(svc, consecutiveMisses = 0)
            }
        }
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): List<DiscoveredLanService> = snapshotLocked()

    private fun snapshotLocked(): List<DiscoveredLanService> = entries.values.map { it.service }

    companion object {
        const val DEFAULT_EVICT_AFTER_MISSES = 2

        fun nextDelayMs(
            roundStartMs: Long,
            nowMs: Long,
            intervalMs: Long = UdpLanServerDiscovery.REPROBE_INTERVAL_MS,
        ): Long = (intervalMs - (nowMs - roundStartMs)).coerceAtLeast(0L)
    }
}
