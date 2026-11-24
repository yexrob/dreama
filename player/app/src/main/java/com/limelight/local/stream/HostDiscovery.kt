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
import java.net.Inet6Address
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
    val protocolVersion: Int = HttpHostProtocolClient.PROTOCOL_VERSION,
) {
    val autoPairSupported: Boolean
        get() = pairMode.equals("auto", ignoreCase = true)

    companion object {
        const val SERVICE_TYPE = "_depth3d-host._tcp."
        const val SERVICE_TYPE_NSD = "_depth3d-host._tcp"

        /**
         * Parses mDNS TXT key-value pairs into a [DiscoveredHost]. Pure function for unit tests.
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
            return DiscoveredHost(
                id = id,
                name = name,
                address = address,
                controlPort = controlPort,
                gsPort = gsPort,
                pairMode = pairMode,
                mediaPort = mediaPort,
                protocolVersion = version,
            )
        }
    }
}

/**
 * Watches the local network for `_depth3d-host._tcp` mDNS announcements via Android's system
 * NsdManager so it shares the system mDNS daemon cleanly with AirPlay and GameStream discovery.
 */
class HostDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val lock = Any()
    private val byServiceName = LinkedHashMap<String, DiscoveredHost>()
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
                    synchronized(lock) {
                        removeCallbackLocked(key)
                        if (byServiceName.remove(key) != null) {
                            publishLocked()
                        }
                    }
                    LimeLog.info("depth3d: host mDNS lost $key")
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
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        LimeLog.warning("depth3d: host mDNS callback failed for $key: $errorCode")
                    }

                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        handleResolved(updated)
                    }

                    override fun onServiceLost() {
                        synchronized(lock) {
                            if (byServiceName.remove(key) != null) {
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

    private fun handleResolved(info: NsdServiceInfo) {
        val addr = pickAddress(info) ?: return
        val txt = LinkedHashMap<String, String?>()
        info.attributes?.forEach { (k, v) ->
            txt[k] = v?.let { String(it, StandardCharsets.UTF_8) }
        }
        val host = DiscoveredHost.fromTxt(info.serviceName.orEmpty(), addr, info.port, txt) ?: return
        synchronized(lock) {
            if (!started) return
            byServiceName[info.serviceName.orEmpty()] = host
            publishLocked()
        }
        LimeLog.info("depth3d: host mDNS resolved ${host.name} at ${host.address}:${host.controlPort} (gs=${host.gsPort}, pair=${host.pairMode})")
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
        /**
         * The address to dial out of everything mDNS answered with. The system reports addresses
         * as they arrive, and the first answer is often only the IPv6 link-local one, which cannot
         * be dialled without a scope. So: IPv4 first, then a routable IPv6, then a link-local one
         * only when it carries its interface; otherwise null, and the next update is waited for.
         */
        internal fun chooseAddress(addrs: List<InetAddress>): String? {
            val usable = addrs.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
            usable.firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }?.let { return it.hostAddress }
            usable.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress }?.let { return it.hostAddress }
            return usable.firstOrNull { it is Inet6Address && it.isLinkLocalAddress && (it.scopeId != 0 || it.scopedInterface != null) }
                ?.hostAddress
        }
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
        _hosts.value = byServiceName.values.distinctBy { it.id }
    }
}
