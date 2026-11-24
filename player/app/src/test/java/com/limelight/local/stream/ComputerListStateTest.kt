package com.limelight.local.stream

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerListStateTest {

    private fun rawComputer(
        uuid: String,
        name: String,
        state: ComputerDetails.State = ComputerDetails.State.ONLINE,
        pairState: PairingManager.PairState = PairingManager.PairState.PAIRED,
        runningGameId: Int = 0,
        activeAddress: String? = "192.168.1.10",
        activePort: Int = NvHTTP.DEFAULT_HTTP_PORT,
        mac: String? = "AA:BB:CC:DD:EE:FF",
    ) = RawComputer(
        uuid = uuid,
        name = name,
        state = state,
        pairState = pairState,
        runningGameId = runningGameId,
        activeAddress = activeAddress,
        activePort = activePort,
        localAddress = activeAddress,
        localPort = activePort,
        manualAddress = null,
        manualPort = 0,
        httpsPort = 47984,
        macAddress = mac,
        hasServerCert = pairState == PairingManager.PairState.PAIRED,
    )

    @Test
    fun discoveredHostFromTxtParsesSpecFields() {
        val host = DiscoveredHost.fromTxt(
            serviceName = "workstation",
            address = "192.168.1.42",
            controlPort = 47990,
            txt = mapOf(
                "v" to "1",
                "id" to "host-uuid-1",
                "name" to "书房电脑",
                "gs" to "47989",
                "pair" to "auto",
                "media" to "8088",
            ),
        )
        assertNotNull(host)
        assertEquals("host-uuid-1", host!!.id)
        assertEquals("书房电脑", host.name)
        assertEquals("192.168.1.42", host.address)
        assertEquals(47990, host.controlPort)
        assertEquals(47989, host.gsPort)
        assertTrue(host.autoPairSupported)
        assertEquals(8088, host.mediaPort)
        assertEquals(1, host.protocolVersion)
    }

    @Test
    fun discoveredHostFromTxtRejectsMissingIdOrInvalidPort() {
        assertNull(DiscoveredHost.fromTxt("pc", "192.168.1.2", 47990, mapOf("v" to "1")))
        assertNull(DiscoveredHost.fromTxt("pc", "", 47990, mapOf("id" to "abc")))
        assertNull(DiscoveredHost.fromTxt("pc", "192.168.1.2", 0, mapOf("id" to "abc")))
        assertNull(DiscoveredHost.fromTxt("pc", "192.168.1.2", 70000, mapOf("id" to "abc")))
    }

    @Test
    fun discoveredHostFallsBackToServiceNameAndDefaultGsPort() {
        val host = DiscoveredHost.fromTxt(
            serviceName = "LivingRoomPC",
            address = "192.168.1.50",
            controlPort = 47990,
            txt = mapOf("id" to "id-50"),
        )
        assertNotNull(host)
        assertEquals("LivingRoomPC", host!!.name)
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, host.gsPort)
        assertFalse(host.autoPairSupported)
        assertNull(host.mediaPort)
    }

    @Test
    fun rawComputerMatchesHostChecksExactPortEvenWhenGsPortIsDefault() {
        val hostDefaultPort = DiscoveredHost(
            id = "host-default",
            name = "Mac",
            address = "192.168.1.10",
            controlPort = 47990,
            gsPort = NvHTTP.DEFAULT_HTTP_PORT,
            pairMode = "auto",
        )
        // Review #13: explicit non-default port 48000 must NOT match gsPort 47989!
        val differentPortPc = rawComputer(
            uuid = "other-uuid",
            name = "VM",
            activeAddress = "192.168.1.10",
            activePort = 48000,
        )
        assertFalse(
            "Port 48000 must not match host gsPort 47989",
            differentPortPc.matchesHost(hostDefaultPort),
        )

        // Exact default port 47989 matches
        val samePortPc = rawComputer(
            uuid = "other-uuid",
            name = "Mac",
            activeAddress = "192.168.1.10",
            activePort = 47989,
        )
        assertTrue(samePortPc.matchesHost(hostDefaultPort))

        // Unspecified port (0 -> treated as default 47989) matches default gsPort 47989
        val zeroPortPc = rawComputer(
            uuid = "other-uuid",
            name = "Mac",
            activeAddress = "192.168.1.10",
            activePort = 0,
        )
        assertTrue(zeroPortPc.matchesHost(hostDefaultPort))

        // Unspecified port (0 -> 47989) must NOT match non-default gsPort 49000
        val hostCustomPort = hostDefaultPort.copy(gsPort = 49000)
        assertFalse(zeroPortPc.matchesHost(hostCustomPort))
    }

    @Test
    fun mergeDeduplicatesByUuidAndByIpPort() {
        val gs1 = rawComputer(
            uuid = "HOST-UUID-1",
            name = "Old Name",
            activeAddress = "192.168.1.10",
            pairState = PairingManager.PairState.PAIRED,
        )
        val gs2 = rawComputer(
            uuid = "gs-other-uuid",
            name = "Gaming Rig",
            activeAddress = "192.168.1.20",
            activePort = 47989,
            pairState = PairingManager.PairState.NOT_PAIRED,
        )
        val host1 = DiscoveredHost(
            id = "host-uuid-1",
            name = "书房 Mac",
            address = "192.168.1.10",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
            mediaPort = 8081,
        )
        val host2 = DiscoveredHost(
            id = "different-host-id",
            name = "游戏电脑",
            address = "192.168.1.20",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
        )
        val hostOnly = DiscoveredHost(
            id = "brand-new-host",
            name = "新发现的电脑",
            address = "192.168.1.30",
            controlPort = 47990,
            pairMode = "auto",
        )
        val hostInfos = mapOf(
            host1.endpointKey to HostInfo(
                version = 1,
                name = "书房 Mac Pro",
                id = "host-uuid-1",
                os = "macos",
                stream = HostStreamStatus(ready = false, reason = "请先在系统设置中开启屏幕录制权限"),
                media = HostMediaConfig(port = 9090),
            ),
        )

        val merged = ComputerListMerger.merge(
            computers = listOf(gs1, gs2),
            discoveredHosts = listOf(host1, host2, hostOnly),
            hostInfosByEndpoint = hostInfos,
        )

        assertEquals(3, merged.size)

        val first = merged[0]
        assertEquals("HOST-UUID-1", first.id)
        assertEquals("书房 Mac Pro", first.name)
        assertEquals(ComputerStatus.ONLINE_PAIRED, first.status)
        assertTrue(first.isDepth3dHost)
        assertTrue(first.autoPairSupported)
        assertFalse(first.streamReady)
        assertEquals("请先在系统设置中开启屏幕录制权限", first.streamNotReadyReason)
        assertEquals(9090, first.mediaPort)

        val second = merged.first { it.id == "gs-other-uuid" }
        assertEquals("游戏电脑", second.name)
        assertEquals(ComputerStatus.ONLINE_UNPAIRED, second.status)
        assertTrue(second.autoPairSupported)

        val third = merged.first { it.id == "host:${hostOnly.endpointKey}" }
        assertEquals("新发现的电脑", third.name)
        assertEquals(ComputerStatus.ONLINE_UNPAIRED, third.status)
        assertFalse(third.isSaved)
        assertTrue(third.autoPairSupported)
    }

    @Test
    fun mergeSortsByStatusAndFiltersHomeScreenShelf() {
        val offline = rawComputer(
            uuid = "u-offline",
            name = "A-Offline",
            state = ComputerDetails.State.OFFLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = "192.168.1.11",
        )
        val discovering = rawComputer(
            uuid = "u-disc",
            name = "B-Discovering",
            state = ComputerDetails.State.UNKNOWN,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = "192.168.1.12",
        )
        val unpaired = rawComputer(
            uuid = "u-unpaired",
            name = "C-Unpaired",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.NOT_PAIRED,
            activeAddress = "192.168.1.13",
        )
        val paired = rawComputer(
            uuid = "u-paired",
            name = "D-Paired",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = "192.168.1.14",
        )
        val streaming = rawComputer(
            uuid = "u-stream",
            name = "E-Streaming",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
            runningGameId = 1001,
            activeAddress = "192.168.1.15",
        )

        val merged = ComputerListMerger.merge(
            computers = listOf(offline, discovering, unpaired, paired, streaming),
            discoveredHosts = emptyList(),
        )
        assertEquals(
            listOf(
                ComputerStatus.ONLINE_STREAMING,
                ComputerStatus.ONLINE_PAIRED,
                ComputerStatus.ONLINE_UNPAIRED,
                ComputerStatus.DISCOVERING,
                ComputerStatus.OFFLINE,
            ),
            merged.map { it.status },
        )

        val homeShelf = ComputerListMerger.homeScreenComputers(merged)
        assertEquals(listOf("u-stream", "u-paired"), homeShelf.map { it.id })
    }

    @Test
    fun hostRetryTrackerAppliesExponentialBackoffAndResetsOnWifiRecovery() {
        val tracker = HostRetryTracker(backoffScheduleMs = longArrayOf(3_000L, 10_000L, 30_000L, 60_000L))
        var now = 1_000L

        // Initially eligible
        assertTrue(tracker.tryAcquire("host-1", now))
        // In-flight prevents duplicate concurrent probes
        assertFalse(tracker.tryAcquire("host-1", now))

        // Failure #1 -> 3s backoff
        val delay1 = tracker.markTransientFailure("host-1", now)
        assertEquals(3_000L, delay1)
        assertFalse(tracker.tryAcquire("host-1", now + 2_999L))
        assertTrue(tracker.tryAcquire("host-1", now + 3_000L))

        // Failure #2 -> 10s backoff
        now += 3_000L
        assertEquals(10_000L, tracker.markTransientFailure("host-1", now))
        assertFalse(tracker.tryAcquire("host-1", now + 9_999L))

        // Wi-Fi recovery resets backoff immediately
        tracker.resetAll()
        assertTrue(tracker.tryAcquire("host-1", now))

        // Completion stops retries until reset
        tracker.markCompleted("host-1")
        assertFalse(tracker.tryAcquire("host-1", now + 1_000_000L))
    }

    @Test
    fun boundedLruCacheEvictsLeastRecentlyUsedBeyondCapacity() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        // Touch "a" so "b" becomes least-recently-used
        assertEquals(1, cache.get("a"))
        cache.put("d", 4)

        assertEquals(3, cache.size())
        assertEquals(1, cache.get("a"))
        assertNull("Least recently used key 'b' should be evicted", cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(4, cache.get("d"))
    }

    @Test
    fun r4_1_existingPairedComputerWithHostAndNoTrustFlagsNeedsHostTrustSetup() {
        val host = DiscoveredHost(
            id = "host-mac-1",
            name = "书房 MacBook",
            address = "192.168.1.50",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
            mediaPort = 8088,
        )
        // Real-world upgrade scenario: GameStream uuid != host.id, already paired with saved serverCert
        val pairedGs = rawComputer(
            uuid = "gs-sunshine-9999",
            name = "MacBook",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = "192.168.1.50",
            activePort = 47989,
        ).copy(serverCertFingerprint = "gs-cert-fp-1")

        // Before HostPairedTrust exists -> merged into 1 row, ONLINE_PAIRED, needsHostTrustSetup = true (#1)
        val beforeTrust = ComputerListMerger.merge(
            computers = listOf(pairedGs),
            discoveredHosts = listOf(host),
            observedFingerprintsByEndpoint = mapOf(host.endpointKey to "host-fp-1"),
            pairedTrusts = emptyMap(),
        )
        assertEquals(1, beforeTrust.size)
        val upgradeItem = beforeTrust.single()
        assertEquals("gs-sunshine-9999", upgradeItem.id)
        assertEquals(ComputerStatus.ONLINE_PAIRED, upgradeItem.status)
        assertTrue("#1: Upgraded paired computer without HostPairedTrust must flag needsHostTrustSetup", upgradeItem.needsHostTrustSetup)
        assertFalse(upgradeItem.isPairedOffline)

        // After HostPairedTrust is saved -> needsHostTrustSetup becomes false!
        val afterTrust = ComputerListMerger.merge(
            computers = listOf(pairedGs),
            discoveredHosts = listOf(host),
            observedFingerprintsByEndpoint = mapOf(host.endpointKey to "host-fp-1"),
            pairedTrusts = mapOf(
                "host-mac-1" to HostPairedTrust("host-fp-1", "gs-sunshine-9999", "gs-cert-fp-1", "书房 MacBook"),
            ),
        )
        assertEquals(1, afterTrust.size)
        assertFalse("#1: After HostPairedTrust is saved, needsHostTrustSetup must be false", afterTrust.single().needsHostTrustSetup)
    }

    @Test
    fun r4_3_pairedComputerWithDifferentUuidShowsPairedOfflineNotUnpairedWhenStreamPollsOffline() {
        // #3: Real-world case where Host id ("host-mac-1111") != GameStream uuid ("gs-sunshine-9999").
        // When streaming service restarts / polls OFFLINE while Host mDNS is live, the card MUST show
        // paired-but-temporarily-unreachable (status=OFFLINE, isPairedOffline=true), NEVER ONLINE_UNPAIRED!
        val liveHost = DiscoveredHost(
            id = "host-mac-1111",
            name = "书房 MacBook",
            address = "192.168.1.50",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
        )
        val offlinePairedStream = rawComputer(
            uuid = "gs-sunshine-9999",
            name = "MacBook",
            state = ComputerDetails.State.OFFLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = null,
            activePort = 0,
            mac = "11:22:33:44:55:66",
        ).copy(localAddress = "192.168.1.50", localPort = 47989, serverCertFingerprint = "gs-fp-real")

        val merged = ComputerListMerger.merge(
            computers = listOf(offlinePairedStream),
            discoveredHosts = listOf(liveHost),
            observedFingerprintsByEndpoint = mapOf(liveHost.endpointKey to "host-fp-real"),
            pairedTrusts = mapOf(
                "host-mac-1111" to HostPairedTrust("host-fp-real", "gs-sunshine-9999", "gs-fp-real", "书房 MacBook"),
            ),
        )
        assertEquals("#3: Trusted Host + offline paired stream record must stay 1 row", 1, merged.size)
        val item = merged.single()
        assertEquals("gs-sunshine-9999", item.id)
        assertEquals("书房 MacBook", item.name)
        assertEquals("#3: Must NOT downgrade to ONLINE_UNPAIRED", ComputerStatus.OFFLINE, item.status)
        assertTrue("#3: Must set isPairedOffline=true so UI shows 已配对 · 暂时连不上", item.isPairedOffline)
        assertTrue(item.isDepth3dHost)
        assertTrue(item.canWake)
    }

    @Test
    fun r4_7_imposterCopyingTrustedHostIdCannotEvictOrRenameRealMac() {
        val realHost = DiscoveredHost(
            id = "host-real-id",
            name = "书房 Mac",
            address = "192.168.1.50",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
        )
        val imposterHost = DiscoveredHost(
            id = "host-real-id",
            name = "恶意名称",
            address = "192.168.1.99",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
        )
        val realRaw = rawComputer(
            uuid = "gs-real-uuid",
            name = "Mac-GS",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = "192.168.1.50",
            activePort = 47989,
        ).copy(serverCertFingerprint = "gs-fp-real")

        val imposterInfo = HostInfo(1, "被冒充者改掉的名字", "host-real-id", "macos", HostStreamStatus(true, null), null)
        val realInfo = HostInfo(1, "Mac 新广播名", "host-real-id", "macos", HostStreamStatus(true, null), null)

        // Even when imposter appears FIRST in discoveredHosts, Pass 1 matches ONLY the verified fingerprint!
        val merged = ComputerListMerger.merge(
            computers = listOf(realRaw),
            discoveredHosts = listOf(imposterHost, realHost),
            hostInfosByEndpoint = mapOf(
                imposterHost.endpointKey to imposterInfo,
                realHost.endpointKey to realInfo,
            ),
            observedFingerprintsByEndpoint = mapOf(
                imposterHost.endpointKey to "fp-imposter",
                realHost.endpointKey to "fp-real",
            ),
            pairedTrusts = mapOf(
                "host-real-id" to HostPairedTrust("fp-real", "gs-real-uuid", "gs-fp-real", savedName = "书房 Mac"),
            ),
        )

        val realCard = merged.firstOrNull { it.id == "gs-real-uuid" }
        assertNotNull("#7: Real Mac must stay in the list and not be evicted by imposter", realCard)
        assertEquals("#7: Trusted host must keep its saved name", "书房 Mac", realCard!!.name)
        assertEquals("#7: Trusted host must attach ONLY to the verified endpoint", "192.168.1.50", realCard.host?.address)
        assertEquals(ComputerStatus.ONLINE_PAIRED, realCard.status)

        // Imposter stays isolated (not merged into gs-real-uuid)
        val imposterCard = merged.firstOrNull { it.address == "192.168.1.99" }
        assertNotNull(imposterCard)
        assertEquals(ComputerStatus.ONLINE_UNPAIRED, imposterCard!!.status)
        assertNull("#7: Imposter must NOT be merged with the real saved computer", imposterCard.raw)
    }

    @Test
    fun r4_10_offlineOtherMachineSharingHostIpIsNeverSwallowedOrHidden() {
        // #10: Another machine's old OFFLINE record happened to hold DHCP IP 192.168.1.50 yesterday;
        // today a new Mac Host got 192.168.1.50. Merging must NOT swallow or hide the offline machine!
        val newMacHost = DiscoveredHost(
            id = "mac-host-uuid",
            name = "新 MacBook",
            address = "192.168.1.50",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
        )
        val otherOfflinePc = rawComputer(
            uuid = "windows-pc-uuid",
            name = "客厅台式机",
            state = ComputerDetails.State.OFFLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = null,
            activePort = 0,
        ).copy(localAddress = "192.168.1.50", localPort = 47989)

        val merged = ComputerListMerger.merge(
            computers = listOf(otherOfflinePc),
            discoveredHosts = listOf(newMacHost),
        )
        assertEquals("#10: Offline other machine at same IP must NOT be swallowed", 2, merged.size)
        val hostCard = merged.firstOrNull { it.host?.id == "mac-host-uuid" }
        val offlineCard = merged.firstOrNull { it.id == "windows-pc-uuid" }
        assertNotNull(hostCard)
        assertNotNull(offlineCard)
        assertEquals(ComputerStatus.ONLINE_UNPAIRED, hostCard!!.status)
        assertEquals(ComputerStatus.OFFLINE, offlineCard!!.status)
        assertNull(offlineCard.host)
    }

    @Test
    fun m4_networkChangeTrackerDetectsBothOfflineRecoveryAndDirectWifiToWifiSwitch() {
        val tracker = NetworkChangeTracker(initialNetworkId = 100L)

        // Initial registration callback for the already-active Wi-Fi does NOT trigger reset
        assertFalse(tracker.onAvailable(100L))

        // Direct switch from Wi-Fi A (100L) to Wi-Fi B (200L) without intermediate offline state -> MUST trigger reset!
        assertTrue(
            "M4: Direct Wi-Fi A -> Wi-Fi B switch must trigger backoff reset",
            tracker.onAvailable(200L),
        )
        assertEquals(200L, tracker.currentNetworkId())
        assertFalse(tracker.onAvailable(200L))

        // Going offline then coming back on Wi-Fi C (300L) -> MUST trigger reset!
        tracker.onLost(200L, fallbackActiveId = null)
        assertNull(tracker.currentNetworkId())
        assertTrue(tracker.onAvailable(300L))
        assertEquals(300L, tracker.currentNetworkId())
    }

    @Test
    fun r5_s2_coldStartTrustedHostWithoutObservedFingerprintAndInfoFailureNeverSpawnsDuplicateCard() {
        val realHost = DiscoveredHost(
            id = "host-mac-1",
            name = "书房 Mac",
            address = "192.168.1.50",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
        )
        val realRaw = rawComputer(
            uuid = "gs-real-uuid",
            name = "Mac-GS",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = "192.168.1.50",
            activePort = 47989,
        ).copy(serverCertFingerprint = "gs-fp-real")
        val trusts = mapOf(
            "host-mac-1" to HostPairedTrust("fp-real", "gs-real-uuid", "gs-fp-real", savedName = "书房 Mac"),
        )

        // Case 1: Cold start — mDNS resolved host-mac-1, trust exists, /info has NOT returned yet (observedFp == null)
        val coldStart = ComputerListMerger.merge(
            computers = listOf(realRaw),
            discoveredHosts = listOf(realHost),
            observedFingerprintsByEndpoint = emptyMap(),
            pairedTrusts = trusts,
            failedInfoEndpoints = emptySet(),
        )
        assertEquals("S2: Cold start must NOT spawn a duplicate ONLINE_UNPAIRED card", 1, coldStart.size)
        val coldCard = coldStart.single()
        assertEquals("gs-real-uuid", coldCard.id)
        assertEquals("书房 Mac", coldCard.name)
        assertEquals("S2: Cold start before /info should show DISCOVERING (正在连接)", ComputerStatus.DISCOVERING, coldCard.status)
        assertFalse(coldCard.isPairedOffline)

        // Case 2: /info probe FAILED (observedFp still null, endpoint marked failed)
        val infoFailed = ComputerListMerger.merge(
            computers = listOf(realRaw),
            discoveredHosts = listOf(realHost),
            observedFingerprintsByEndpoint = emptyMap(),
            pairedTrusts = trusts,
            failedInfoEndpoints = setOf(realHost.endpointKey),
        )
        assertEquals("S2: /info failure must NOT spawn a duplicate ONLINE_UNPAIRED card", 1, infoFailed.size)
        val failedCard = infoFailed.single()
        assertEquals("gs-real-uuid", failedCard.id)
        assertEquals("书房 Mac", failedCard.name)
        assertEquals(ComputerStatus.OFFLINE, failedCard.status)
        assertTrue("S2: /info failure should show paired-offline (已配对 · 暂时连不上)", failedCard.isPairedOffline)

        // Case 3: Once /info actually observes a DIFFERENT fingerprint, isolate it without touching real Mac
        val imposter = realHost.copy(address = "192.168.1.99", name = "冒充者")
        val mismatch = ComputerListMerger.merge(
            computers = listOf(realRaw),
            discoveredHosts = listOf(realHost, imposter),
            observedFingerprintsByEndpoint = mapOf(
                realHost.endpointKey to "fp-real",
                imposter.endpointKey to "fp-imposter",
            ),
            pairedTrusts = trusts,
        )
        assertEquals(2, mismatch.size)
        assertEquals(ComputerStatus.ONLINE_PAIRED, mismatch.first { it.id == "gs-real-uuid" }.status)
        assertEquals(ComputerStatus.ONLINE_UNPAIRED, mismatch.first { it.address == "192.168.1.99" }.status)
    }

    @Test
    fun r5_s3_imposterAnnouncingRealStreamingUuidOrGsidCannotMergeOntoRealComputer() {
        // S3: Imposter at 192.168.1.99 announces id == real Mac's GameStream uuid AND non-spec TXT gsid!
        val imposter = DiscoveredHost.fromTxt(
            serviceName = "EvilMac",
            address = "192.168.1.99",
            controlPort = 47990,
            txt = mapOf(
                "v" to "1",
                "id" to "gs-real-uuid",
                "gsid" to "gs-real-uuid",
                "name" to "伪装 Mac",
                "pair" to "auto",
                "gs" to "47989",
            ),
        )!!
        val realRaw = rawComputer(
            uuid = "gs-real-uuid",
            name = "真实 Mac",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
            activeAddress = "192.168.1.50",
            activePort = 47989,
        ).copy(serverCertFingerprint = "gs-fp-real")

        val merged = ComputerListMerger.merge(
            computers = listOf(realRaw),
            discoveredHosts = listOf(imposter),
        )
        assertEquals("S3: Imposter at another IP announcing real uuid/gsid must NOT merge onto real Mac", 2, merged.size)
        val realCard = merged.first { it.id == "gs-real-uuid" }
        val imposterCard = merged.first { it.address == "192.168.1.99" }
        assertNull("Real Mac must NOT attach to imposter Host", realCard.host)
        assertNull("Imposter must NOT inherit real Mac's RawComputer", imposterCard.raw)
        assertEquals(ComputerStatus.ONLINE_UNPAIRED, imposterCard.status)
    }

    @Test
    fun r6_7_trustedHostWithMatchingFingerprintAndMissingRawStillShowsPairedConnecting() {
        val realHost = DiscoveredHost(
            id = "host-mac-7",
            name = "MacBook Pro",
            address = "192.168.1.77",
            controlPort = 47990,
            gsPort = 47989,
            pairMode = "auto",
        )
        val trusts = mapOf(
            "host-mac-7" to HostPairedTrust(
                hostFingerprint = "fp-real-7",
                computerUuid = "gs-uuid-7",
                gsCertFingerprint = "gs-fp-7",
                savedName = "书房 Mac",
            ),
        )
        val info = HostInfo(1, "MacBook Pro", "host-mac-7", "macos", HostStreamStatus(true, null), HostMediaConfig(8890))

        // GameStream RawComputer list is empty (temporarily missing), but Host trust exists and TLS fp matches!
        val merged = ComputerListMerger.merge(
            computers = emptyList(),
            discoveredHosts = listOf(realHost),
            hostInfosByEndpoint = mapOf(realHost.endpointKey to info),
            observedFingerprintsByEndpoint = mapOf(realHost.endpointKey to "fp-real-7"),
            pairedTrusts = trusts,
        )

        assertEquals("R6 #7: Trusted Host with matching fp and missing raw must still be shown", 1, merged.size)
        val card = merged.single()
        assertEquals("gs-uuid-7", card.id)
        assertEquals("书房 Mac", card.name)
        assertEquals("192.168.1.77", card.address)
        assertEquals(ComputerStatus.DISCOVERING, card.status)
        assertTrue("R6 #7: Card must be flagged isPairedConnecting so UI displays 已配对 · 正在连接…", card.isPairedConnecting)
        assertTrue(card.isSaved)
        assertFalse(card.isPairedOffline)
        assertEquals(realHost, card.host)
    }
}
