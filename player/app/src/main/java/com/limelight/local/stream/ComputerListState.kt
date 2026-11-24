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

    fun hasAddress(address: String): Boolean {
        val target = normalizeAddress(address)
        if (target.isEmpty()) return false
        return listOfNotNull(activeAddress, localAddress, manualAddress)
            .any { normalizeAddress(it) == target }
    }

    fun matchesHost(host: DiscoveredHost): Boolean {
        if (uuid.equals(host.id, ignoreCase = true)) return true
        val target = normalizeAddress(host.address)
        if (target.isEmpty()) return false
        val candidatePairs = listOfNotNull(
            activeAddress?.let { it to activePort },
            localAddress?.let { it to localPort },
            manualAddress?.let { it to manualPort },
        )
        return candidatePairs.any { (addr, port) ->
            val effectivePort = if (port > 0) port else NvHTTP.DEFAULT_HTTP_PORT
            normalizeAddress(addr) == target && effectivePort == host.gsPort
        }
    }

    /**
     * Returns true when this record represents the same physical machine as [host], including a
     * previously directly-paired GameStream service that is now OFFLINE on the same IP address
     * (S5: collapse old offline direct-paired service + new depth3d Host into one row).
     */
    internal fun belongsToHostForMerge(host: DiscoveredHost): Boolean =
        matchesHost(host) || (state == ComputerDetails.State.OFFLINE && hasAddress(host.address))

    companion object {
        internal fun normalizeAddress(addr: String): String =
            addr.trim().removePrefix("[").removeSuffix("]").lowercase()

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
     * announced under both protocols and collapsing old offline direct-paired services on the
     * same host id or IP address under the live depth3d Host (S5).
     */
    fun merge(
        computers: Collection<RawComputer>,
        discoveredHosts: Collection<DiscoveredHost>,
        hostInfos: Map<String, HostInfo> = emptyMap(),
    ): List<StreamingComputer> {
        val validComputers = computers.filter { it.uuid.isNotBlank() }
        val consumedUuids = HashSet<String>()
        val result = ArrayList<StreamingComputer>(validComputers.size + discoveredHosts.size)

        // Pass 1: Emit at most ONE entry per live depth3d Host, absorbing all matching GameStream
        // records (by host id, exact (address, gsPort), or stale OFFLINE records at the same IP).
        for (host in discoveredHosts) {
            val matched = validComputers.filter { raw ->
                raw.uuid !in consumedUuids && raw.belongsToHostForMerge(host)
            }
            matched.forEach { consumedUuids += it.uuid }

            val info = hostInfos[host.id]
            val canWakeAny = matched.any { !it.macAddress.isNullOrBlank() }
            val best = pickBestRawForHost(matched, host)

            val displayName = info?.name?.takeIf { it.isNotBlank() }
                ?: host.name.takeIf { it.isNotBlank() }
                ?: best?.name?.takeIf { it.isNotBlank() }
                ?: host.address

            // S5: If the only matched record is a legacy OFFLINE direct-paired service with a
            // different UUID, prioritize the live depth3d Host instead of showing an offline card.
            if (best == null || (best.state == ComputerDetails.State.OFFLINE && !best.uuid.equals(host.id, ignoreCase = true))) {
                result += StreamingComputer(
                    id = "host:${host.id}",
                    name = displayName,
                    address = host.address,
                    gsPort = host.gsPort,
                    status = ComputerStatus.ONLINE_UNPAIRED,
                    runningGameId = 0,
                    canWake = canWakeAny,
                    isSaved = false,
                    host = host,
                    hostInfo = info,
                    raw = null,
                )
            } else {
                val status = computeStatus(best, hasLiveHostMdns = true, exactHostId = best.uuid.equals(host.id, ignoreCase = true))
                val address = if (best.state == ComputerDetails.State.ONLINE) {
                    best.bestAddress ?: host.address
                } else {
                    host.address
                }
                val gsPort = if (best.state == ComputerDetails.State.ONLINE && best.bestPort > 0) {
                    best.bestPort
                } else {
                    host.gsPort
                }
                result += StreamingComputer(
                    id = best.uuid,
                    name = displayName,
                    address = address,
                    gsPort = gsPort,
                    status = status,
                    runningGameId = best.runningGameId,
                    canWake = canWakeAny,
                    isSaved = true,
                    host = host,
                    hostInfo = info,
                    raw = best,
                )
            }
        }

        // Pass 2: Remaining standalone GameStream computers (no live depth3d Host).
        // If an offline stale record shares the exact same (address, port) as an online record,
        // keep only the online one so the same machine is never duplicated.
        val remaining = validComputers.filter { it.uuid !in consumedUuids }
        val onlineAddressPorts = remaining
            .filter { it.state == ComputerDetails.State.ONLINE && !it.bestAddress.isNullOrBlank() }
            .map { RawComputer.normalizeAddress(it.bestAddress!!) to it.bestPort }
            .toSet()

        for (raw in remaining) {
            if (raw.state == ComputerDetails.State.OFFLINE && !raw.bestAddress.isNullOrBlank()) {
                val key = RawComputer.normalizeAddress(raw.bestAddress!!) to raw.bestPort
                if (key in onlineAddressPorts) continue
            }
            result += StreamingComputer(
                id = raw.uuid,
                name = raw.name.ifBlank { raw.bestAddress ?: raw.uuid },
                address = raw.bestAddress,
                gsPort = raw.bestPort,
                status = computeStatus(raw, hasLiveHostMdns = false, exactHostId = false),
                runningGameId = raw.runningGameId,
                canWake = !raw.macAddress.isNullOrBlank(),
                isSaved = true,
                host = null,
                hostInfo = null,
                raw = raw,
            )
        }

        return result.sortedWith(
            compareBy<StreamingComputer> { it.status.sortOrder }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id }
        )
    }

    private fun pickBestRawForHost(candidates: List<RawComputer>, host: DiscoveredHost): RawComputer? {
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { raw ->
            val idMatch = raw.uuid.equals(host.id, ignoreCase = true)
            val isPaired = raw.pairState == PairingManager.PairState.PAIRED
            when {
                raw.state == ComputerDetails.State.ONLINE && idMatch && isPaired -> 0
                raw.state == ComputerDetails.State.ONLINE && isPaired -> 1
                raw.state == ComputerDetails.State.ONLINE && idMatch -> 2
                raw.state == ComputerDetails.State.ONLINE -> 3
                raw.state == ComputerDetails.State.UNKNOWN && idMatch -> 4
                raw.state == ComputerDetails.State.OFFLINE && idMatch && isPaired -> 5
                raw.state == ComputerDetails.State.UNKNOWN -> 6
                idMatch -> 7
                else -> 8
            }
        }
    }

    /** Computers eligible for the Home screen bottom 「电脑」 shelf: paired and currently online. */
    fun homeScreenComputers(computers: List<StreamingComputer>): List<StreamingComputer> =
        computers.filter { it.status == ComputerStatus.ONLINE_PAIRED || it.status == ComputerStatus.ONLINE_STREAMING }

    private fun computeStatus(
        raw: RawComputer,
        hasLiveHostMdns: Boolean,
        exactHostId: Boolean,
    ): ComputerStatus = when (raw.state) {
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
        ComputerDetails.State.OFFLINE -> {
            // Prioritize the live depth3d Host when this saved record has the same host id.
            if (hasLiveHostMdns && exactHostId) {
                if (raw.pairState == PairingManager.PairState.PAIRED) {
                    ComputerStatus.ONLINE_PAIRED
                } else {
                    ComputerStatus.ONLINE_UNPAIRED
                }
            } else {
                ComputerStatus.OFFLINE
            }
        }
    }
}
