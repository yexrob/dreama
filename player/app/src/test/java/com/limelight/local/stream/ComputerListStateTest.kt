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
            "host-uuid-1" to HostInfo(
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
            hostInfos = hostInfos,
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

        val third = merged.first { it.id == "host:brand-new-host" }
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
        )
        val discovering = rawComputer(
            uuid = "u-disc",
            name = "B-Discovering",
            state = ComputerDetails.State.UNKNOWN,
            pairState = PairingManager.PairState.PAIRED,
        )
        val unpaired = rawComputer(
            uuid = "u-unpaired",
            name = "C-Unpaired",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.NOT_PAIRED,
        )
        val paired = rawComputer(
            uuid = "u-paired",
            name = "D-Paired",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
        )
        val streaming = rawComputer(
            uuid = "u-stream",
            name = "E-Streaming",
            state = ComputerDetails.State.ONLINE,
            pairState = PairingManager.PairState.PAIRED,
            runningGameId = 1001,
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
}
