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
    val serverCertFingerprint: String? = null,
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

    /**
     * Exact endpoint match `(address, gsPort)` per `docs/specs/host-protocol.md` §发现.
     * Never matches an untrusted mDNS `id` or non-spec `gsid` against `uuid` (S3).
     */
    fun matchesHost(host: DiscoveredHost): Boolean = matchesEndpointAddressAndPort(host)

    /**
     * Matches `(address, gsPort)` strictly on an ONLINE GameStream service.
     * Never matches an OFFLINE record by IP so another machine's stale DHCP IP cannot be
     * swallowed by a new Host (#10).
     */
    fun matchesOnlineEndpoint(host: DiscoveredHost): Boolean =
        state == ComputerDetails.State.ONLINE && matchesEndpointAddressAndPort(host)

    private fun matchesEndpointAddressAndPort(host: DiscoveredHost): Boolean {
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
            serverCertFingerprint = details.serverCert?.let { HttpHostProtocolClient.sha256Hex(it) },
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
    /** True when GameStream is already paired + Host is discovered, but HostPairedTrust is not yet established (#1). */
    val needsHostTrustSetup: Boolean = false,
    /** True when this computer IS paired (GameStream) and its Host is alive, but streaming is temporarily offline (#3). */
    val isPairedOffline: Boolean = false,
    /** True when this computer has persisted Host trust and is connecting/refreshing state (#7 / S2). */
    val isPairedConnecting: Boolean = false,
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
     * records from [HostDiscovery], optional `/depth3d/v1/info` probes, observed TLS fingerprints,
     * and persisted [HostPairedTrust] bindings:
     *
     * - (#7) For any Host with a persisted [HostPairedTrust], ONLY the discovered endpoint whose
     *   observed TLS fingerprint matches `trust.hostFingerprint` is merged into the trusted
     *   computer, and its saved name is preserved; imposters copying `host.id` are NOT merged in.
     * - (#3) When a paired computer's GameStream record (`raw.uuid != host.id`) temporarily polls
     *   as OFFLINE while its Host is alive, keeps it paired with [StreamingComputer.isPairedOffline]
     *   instead of downgrading to ONLINE_UNPAIRED.
     * - (#10 + S3) Never merges an OFFLINE GameStream record solely by IP address; offline merging uses
     *   only verified `trust.computerUuid` (never untrusted mDNS ids).
     * - (#1) Flags online paired computers that have a discovered Host without matching
     *   [HostPairedTrust] via [StreamingComputer.needsHostTrustSetup].
     */
    fun merge(
        computers: Collection<RawComputer>,
        discoveredHosts: Collection<DiscoveredHost>,
        hostInfosByEndpoint: Map<String, HostInfo> = emptyMap(),
        observedFingerprintsByEndpoint: Map<String, String> = emptyMap(),
        pairedTrusts: Map<String, HostPairedTrust> = emptyMap(),
        failedInfoEndpoints: Set<String> = emptySet(),
    ): List<StreamingComputer> {
        val validComputers = computers.filter { it.uuid.isNotBlank() }
        val consumedUuids = HashSet<String>()
        val consumedEndpoints = HashSet<String>()
        val result = ArrayList<StreamingComputer>(validComputers.size + discoveredHosts.size)

        // Normalize pairedTrusts keys case-insensitively
        val trustsByHostIdLower = LinkedHashMap<String, Pair<String, HostPairedTrust>>()
        for ((hostId, trust) in pairedTrusts) {
            trustsByHostIdLower[hostId.lowercase()] = hostId to trust
        }

        // Pass 1 (#7 + #3 + #10 + S2): Resolve every trusted Host strictly by persisted trust.computerUuid.
        // - If a discovered endpoint has observed fingerprint == trust.hostFingerprint, attach it as verified.
        // - (S2) If no verified endpoint is observed yet (cold start before /info returns or /info failed),
        //   merge an unverified same-id endpoint (observedFp == null) onto the trusted computer showing
        //   DISCOVERING ("正在连接…") or OFFLINE + isPairedOffline ("已配对 · 暂时连不上") rather than
        //   creating a duplicate ONLINE_UNPAIRED card!
        // - Only endpoints whose OBSERVED fingerprint is proven DIFFERENT from trust.hostFingerprint are
        //   left for Pass 2 to display isolated (#7).
        for ((hostIdLower, pair) in trustsByHostIdLower) {
            val trust = pair.second
            val raw = validComputers.firstOrNull {
                it.uuid !in consumedUuids && it.uuid.equals(trust.computerUuid, ignoreCase = true)
            } ?: continue

            val verifiedHost = discoveredHosts.firstOrNull { candidate ->
                candidate.endpointKey !in consumedEndpoints &&
                    candidate.id.lowercase() == hostIdLower &&
                    observedFingerprintsByEndpoint[candidate.endpointKey]
                        ?.equals(trust.hostFingerprint, ignoreCase = true) == true
            }

            if (verifiedHost != null) {
                consumedUuids += raw.uuid
                consumedEndpoints += verifiedHost.endpointKey

                val info = hostInfosByEndpoint[verifiedHost.endpointKey]
                val status = computeStatus(raw, hasLiveHostMdns = true)
                val isPairedOffline = status == ComputerStatus.OFFLINE && raw.pairState == PairingManager.PairState.PAIRED
                val displayName = trust.savedName?.takeIf { it.isNotBlank() }
                    ?: raw.name.ifBlank { verifiedHost.name }
                val gsCertMatches = raw.serverCertFingerprint != null &&
                    raw.serverCertFingerprint.equals(trust.gsCertFingerprint, ignoreCase = true)
                val needsSetup = raw.pairState == PairingManager.PairState.PAIRED && raw.hasServerCert && !gsCertMatches

                result += StreamingComputer(
                    id = raw.uuid,
                    name = displayName,
                    address = if (raw.state == ComputerDetails.State.ONLINE) (raw.bestAddress ?: verifiedHost.address) else verifiedHost.address,
                    gsPort = if (raw.state == ComputerDetails.State.ONLINE && raw.bestPort > 0) raw.bestPort else verifiedHost.gsPort,
                    status = status,
                    runningGameId = raw.runningGameId,
                    canWake = !raw.macAddress.isNullOrBlank(),
                    isSaved = true,
                    host = verifiedHost,
                    hostInfo = info,
                    raw = raw,
                    needsHostTrustSetup = needsSetup,
                    isPairedOffline = isPairedOffline,
                )
                continue
            }

            // S2: Cold start or /info transiently failed -> observedFp is null; merge cleanly without
            // spawning a duplicate "未配对" card!
            val unverifiedCandidates = discoveredHosts.filter { candidate ->
                candidate.endpointKey !in consumedEndpoints &&
                    candidate.id.lowercase() == hostIdLower &&
                    observedFingerprintsByEndpoint[candidate.endpointKey] == null
            }
            val pendingHost = unverifiedCandidates.firstOrNull { raw.hasAddress(it.address) }
                ?: unverifiedCandidates.firstOrNull()
                ?: continue

            consumedUuids += raw.uuid
            consumedEndpoints += pendingHost.endpointKey

            val infoFailed = pendingHost.endpointKey in failedInfoEndpoints
            val status = if (infoFailed || raw.state == ComputerDetails.State.OFFLINE) {
                ComputerStatus.OFFLINE
            } else {
                ComputerStatus.DISCOVERING
            }
            val isPairedOffline = status == ComputerStatus.OFFLINE && raw.pairState == PairingManager.PairState.PAIRED
            val displayName = trust.savedName?.takeIf { it.isNotBlank() }
                ?: raw.name.ifBlank { pendingHost.name }

            result += StreamingComputer(
                id = raw.uuid,
                name = displayName,
                address = raw.bestAddress ?: pendingHost.address,
                gsPort = if (raw.state == ComputerDetails.State.ONLINE && raw.bestPort > 0) raw.bestPort else pendingHost.gsPort,
                status = status,
                runningGameId = raw.runningGameId,
                canWake = !raw.macAddress.isNullOrBlank(),
                isSaved = true,
                host = pendingHost,
                hostInfo = null,
                raw = raw,
                needsHostTrustSetup = false,
                isPairedOffline = isPairedOffline,
                isPairedConnecting = status == ComputerStatus.DISCOVERING && raw.pairState == PairingManager.PairState.PAIRED,
            )
        }

        // Pass 2 (#1 + #3 + #7 + #10 + S2 + S3 + R6 #7): Process remaining discovered Hosts.
        for (host in discoveredHosts) {
            if (host.endpointKey in consumedEndpoints) continue
            consumedEndpoints += host.endpointKey

            val info = hostInfosByEndpoint[host.endpointKey]
            val existingTrustForId = trustsByHostIdLower[host.id.lowercase()]?.second

            if (existingTrustForId != null) {
                val observedFp = observedFingerprintsByEndpoint[host.endpointKey]
                // S2: If fingerprint has NOT yet been observed (cold start / /info not returned or failed),
                // do NOT spawn a duplicate ONLINE_UNPAIRED card for a trusted host id!
                if (observedFp == null) {
                    continue
                }
                if (observedFp.equals(existingTrustForId.hostFingerprint, ignoreCase = true)) {
                    // R6 #7: Trusted Host whose TLS fingerprint matches, but whose GameStream RawComputer
                    // record is temporarily missing -> still display this paired computer as connecting!
                    val displayName = existingTrustForId.savedName?.takeIf { it.isNotBlank() }
                        ?: info?.name?.takeIf { it.isNotBlank() }
                        ?: host.name.ifBlank { host.address }
                    val id = existingTrustForId.computerUuid.ifBlank { "host:${host.endpointKey}" }
                    result += StreamingComputer(
                        id = id,
                        name = displayName,
                        address = host.address,
                        gsPort = host.gsPort,
                        status = ComputerStatus.DISCOVERING,
                        runningGameId = 0,
                        canWake = false,
                        isSaved = true,
                        host = host,
                        hostInfo = info,
                        raw = null,
                        needsHostTrustSetup = false,
                        isPairedOffline = false,
                        isPairedConnecting = true,
                    )
                } else {
                    // #7: Fingerprint WAS observed and differs from saved trust -> show isolated without
                    // merging into any saved computer.
                    val displayName = host.name.ifBlank { host.address }
                    result += StreamingComputer(
                        id = "host:${host.endpointKey}",
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
                        needsHostTrustSetup = false,
                        isPairedOffline = false,
                    )
                }
                continue
            }

            // S3 + #10 + host-protocol.md §发现:
            // Match untrusted discovered Hosts ONLY by live ONLINE `(address, gsPort)`.
            // Never match by untrusted mDNS id / non-spec gsid (S3) and never swallow an OFFLINE record by IP (#10).
            val matched = validComputers.filter { raw ->
                raw.uuid !in consumedUuids && raw.matchesOnlineEndpoint(host)
            }
            val best = pickBestRawForHost(matched)

            val displayName = info?.name?.takeIf { it.isNotBlank() }
                ?: host.name.takeIf { it.isNotBlank() }
                ?: best?.name?.takeIf { it.isNotBlank() }
                ?: host.address

            if (best == null) {
                result += StreamingComputer(
                    id = "host:${host.endpointKey}",
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
                    needsHostTrustSetup = false,
                    isPairedOffline = false,
                )
            } else {
                consumedUuids += best.uuid
                val status = computeStatus(best, hasLiveHostMdns = true)
                val isPairedOffline = status == ComputerStatus.OFFLINE && best.pairState == PairingManager.PairState.PAIRED
                // #1: Paired at GameStream layer + live Host discovered + no HostPairedTrust yet!
                val needsSetup = best.pairState == PairingManager.PairState.PAIRED && best.hasServerCert

                result += StreamingComputer(
                    id = best.uuid,
                    name = displayName,
                    address = if (best.state == ComputerDetails.State.ONLINE) (best.bestAddress ?: host.address) else host.address,
                    gsPort = if (best.state == ComputerDetails.State.ONLINE && best.bestPort > 0) best.bestPort else host.gsPort,
                    status = status,
                    runningGameId = best.runningGameId,
                    canWake = !best.macAddress.isNullOrBlank(),
                    isSaved = true,
                    host = host,
                    hostInfo = info,
                    raw = best,
                    needsHostTrustSetup = needsSetup,
                    isPairedOffline = isPairedOffline,
                )
            }
        }

        // Pass 3 (#10): Remaining standalone GameStream computers (never dropped by IP collision!).
        for (raw in validComputers) {
            if (raw.uuid in consumedUuids) continue
            val trustByUuid = pairedTrusts.values.firstOrNull {
                it.computerUuid.equals(raw.uuid, ignoreCase = true)
            }
            val displayName = trustByUuid?.savedName?.takeIf { it.isNotBlank() }
                ?: raw.name.ifBlank { raw.bestAddress ?: raw.uuid }
            result += StreamingComputer(
                id = raw.uuid,
                name = displayName,
                address = raw.bestAddress,
                gsPort = raw.bestPort,
                status = computeStatus(raw, hasLiveHostMdns = false),
                runningGameId = raw.runningGameId,
                canWake = !raw.macAddress.isNullOrBlank(),
                isSaved = true,
                host = null,
                hostInfo = null,
                raw = raw,
                needsHostTrustSetup = false,
                isPairedOffline = false,
            )
        }

        return result.sortedWith(
            compareBy<StreamingComputer> { it.status.sortOrder }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id }
        )
    }

    private fun pickBestRawForHost(candidates: List<RawComputer>): RawComputer? {
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { raw ->
            val isPaired = raw.pairState == PairingManager.PairState.PAIRED
            when {
                raw.state == ComputerDetails.State.ONLINE && isPaired -> 0
                raw.state == ComputerDetails.State.ONLINE -> 1
                else -> 2
            }
        }
    }

    /** Computers eligible for the Home screen bottom 「电脑」 shelf: paired and currently online. */
    fun homeScreenComputers(computers: List<StreamingComputer>): List<StreamingComputer> =
        computers.filter { it.status == ComputerStatus.ONLINE_PAIRED || it.status == ComputerStatus.ONLINE_STREAMING }

    private fun computeStatus(
        raw: RawComputer,
        hasLiveHostMdns: Boolean,
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
        // #3: When GameStream polling reports OFFLINE (service restart / poll failure), NEVER
        // downgrade a paired computer into ONLINE_UNPAIRED; keep OFFLINE (with isPairedOffline=true
        // when paired + Host alive) so the UI shows "已配对 · 暂时连不上".
        ComputerDetails.State.OFFLINE -> ComputerStatus.OFFLINE
    }
}
