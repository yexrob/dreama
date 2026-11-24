package com.limelight.local.stream

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager

enum class ComputerStatus(val sortOrder: Int) {
    ONLINE_STREAMING(0),
    ONLINE_PAIRED(1),
    ONLINE_UNPAIRED(2),
    DISCOVERING(3),
    OFFLINE(4),
}

/**
 * Pure JVM snapshot of a [ComputerDetails] record so merging and state transitions are
 * deterministic and unit-testable without Android dependencies.
 */
data class RawComputer(
    val uuid: String,
    val name: String,
    val state: ComputerDetails.State,
    val pairState: PairingManager.PairState,
    val runningGameId: Int,
    val activeAddress: String?,
    val activePort: Int,
    val localAddress: String?,
    val localPort: Int,
    val manualAddress: String?,
    val manualPort: Int,
    val httpsPort: Int,
    val macAddress: String?,
    val hasServerCert: Boolean,
) {
    val bestAddress: String?
        get() = activeAddress ?: localAddress ?: manualAddress

    val bestPort: Int
        get() = when {
            activeAddress != null && activePort > 0 -> activePort
            localAddress != null && localPort > 0 -> localPort
            manualAddress != null && manualPort > 0 -> manualPort
            else -> NvHTTP.DEFAULT_HTTP_PORT
        }

    fun matchesHost(host: DiscoveredHost): Boolean {
        if (uuid.equals(host.id, ignoreCase = true)) return true
        val candidatePairs = listOfNotNull(
            activeAddress?.let { it to activePort },
            localAddress?.let { it to localPort },
            manualAddress?.let { it to manualPort },
        )
        return candidatePairs.any { (addr, port) ->
            val effectivePort = if (port > 0) port else NvHTTP.DEFAULT_HTTP_PORT
            addr.equals(host.address, ignoreCase = true) && effectivePort == host.gsPort
        }
    }

    companion object {
        fun from(details: ComputerDetails): RawComputer = RawComputer(
            uuid = details.uuid.orEmpty(),
            name = details.name.orEmpty().ifBlank { details.activeAddress?.address ?: details.localAddress?.address ?: "" },
            state = details.state ?: ComputerDetails.State.UNKNOWN,
            pairState = details.pairState ?: PairingManager.PairState.NOT_PAIRED,
            runningGameId = details.runningGameId,
            activeAddress = details.activeAddress?.address,
            activePort = details.activeAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT,
            localAddress = details.localAddress?.address,
            localPort = details.localAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT,
            manualAddress = details.manualAddress?.address,
            manualPort = details.manualAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT,
            httpsPort = details.httpsPort,
            macAddress = details.macAddress,
            hasServerCert = details.serverCert != null,
        )
    }
}

/**
 * Unified computer model shown on both the Cast page and Home screen.
 * Combines GameStream discovery/polling with optional depth3d Host metadata.
 */
data class StreamingComputer(
    val id: String,
    val name: String,
    val address: String?,
    val gsPort: Int,
    val status: ComputerStatus,
    val runningGameId: Int,
    val canWake: Boolean,
    val isSaved: Boolean,
    val host: DiscoveredHost?,
    val hostInfo: HostInfo?,
    val raw: RawComputer?,
) {
    val isOnline: Boolean
        get() = status == ComputerStatus.ONLINE_PAIRED ||
            status == ComputerStatus.ONLINE_STREAMING ||
            status == ComputerStatus.ONLINE_UNPAIRED

    val isPaired: Boolean
        get() = status == ComputerStatus.ONLINE_PAIRED || status == ComputerStatus.ONLINE_STREAMING

    val isDepth3dHost: Boolean
        get() = host != null

    val autoPairSupported: Boolean
        get() = host?.autoPairSupported == true

    val streamReady: Boolean
        get() = hostInfo?.stream?.ready ?: true

    val streamNotReadyReason: String?
        get() = hostInfo?.stream?.takeIf { !it.ready }?.reason

    val mediaPort: Int?
        get() = hostInfo?.media?.port ?: host?.mediaPort
}

object ComputerListMerger {
    /**
     * Merges GameStream computers from [ComputerManagerService] with `_depth3d-host._tcp`
     * records from [HostDiscovery] and optional `/depth3d/v1/info` probes, deduplicating hosts
     * announced under both protocols.
     */
    fun merge(
        computers: Collection<RawComputer>,
        discoveredHosts: Collection<DiscoveredHost>,
        hostInfos: Map<String, HostInfo> = emptyMap(),
    ): List<StreamingComputer> {
        val matchedHostIds = HashSet<String>()
        val result = ArrayList<StreamingComputer>(computers.size + discoveredHosts.size)

        for (raw in computers) {
            if (raw.uuid.isBlank()) continue
            val host = discoveredHosts.firstOrNull { raw.matchesHost(it) }
            if (host != null) {
                matchedHostIds += host.id
            }
            val info = host?.let { hostInfos[it.id] }
            val status = computeStatus(raw, host != null)
            val displayName = info?.name?.takeIf { it.isNotBlank() }
                ?: host?.name?.takeIf { it.isNotBlank() }
                ?: raw.name.ifBlank { raw.bestAddress ?: raw.uuid }
            result += StreamingComputer(
                id = raw.uuid,
                name = displayName,
                address = raw.bestAddress ?: host?.address,
                gsPort = if (raw.bestPort > 0) raw.bestPort else (host?.gsPort ?: NvHTTP.DEFAULT_HTTP_PORT),
                status = status,
                runningGameId = raw.runningGameId,
                canWake = !raw.macAddress.isNullOrBlank(),
                isSaved = true,
                host = host,
                hostInfo = info,
                raw = raw,
            )
        }

        // Hosts discovered over _depth3d-host._tcp before GameStream polling completes
        for (host in discoveredHosts) {
            if (host.id in matchedHostIds) continue
            val info = hostInfos[host.id]
            val displayName = info?.name?.takeIf { it.isNotBlank() } ?: host.name
            result += StreamingComputer(
                id = "host:${host.id}",
                name = displayName,
                address = host.address,
                gsPort = host.gsPort,
                status = ComputerStatus.ONLINE_UNPAIRED,
                runningGameId = 0,
                canWake = false,
                isSaved = false,
                host = host,
                hostInfo = info,
                raw = null,
            )
        }

        return result.sortedWith(
            compareBy<StreamingComputer> { it.status.sortOrder }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id }
        )
    }

    /** Computers eligible for the Home screen bottom 「电脑」 shelf: paired and currently online. */
    fun homeScreenComputers(computers: List<StreamingComputer>): List<StreamingComputer> =
        computers.filter { it.status == ComputerStatus.ONLINE_PAIRED || it.status == ComputerStatus.ONLINE_STREAMING }

    private fun computeStatus(raw: RawComputer, hasLiveHostMdns: Boolean): ComputerStatus = when (raw.state) {
        ComputerDetails.State.ONLINE -> when {
            raw.pairState != PairingManager.PairState.PAIRED -> ComputerStatus.ONLINE_UNPAIRED
            raw.runningGameId != 0 -> ComputerStatus.ONLINE_STREAMING
            else -> ComputerStatus.ONLINE_PAIRED
        }
        ComputerDetails.State.UNKNOWN -> {
            if (hasLiveHostMdns && raw.pairState != PairingManager.PairState.PAIRED) {
                ComputerStatus.ONLINE_UNPAIRED
            } else {
                ComputerStatus.DISCOVERING
            }
        }
        ComputerDetails.State.OFFLINE -> ComputerStatus.OFFLINE
    }
}
