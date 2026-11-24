package com.limelight.local.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class HostDiscoveryAddressTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private val linkLocal = ip("fe80::7a:e3f9:c3fa:d2de")

    @Test
    fun unscopedLinkLocalAloneIsNotDialled() {
        assertNull(HostDiscovery.chooseAddress(listOf(linkLocal)))
    }

    @Test
    fun ipv4WinsWhateverTheOrder() {
        assertEquals("192.168.1.142", HostDiscovery.chooseAddress(listOf(linkLocal, ip("2409:8a00::1ec"), ip("192.168.1.142"))))
    }

    @Test
    fun routableIpv6AloneIsNotDialledBecauseHostProtocolRequiresIpv4() {
        // Per docs/specs/host-protocol.md: all connections to a Host (control API + GameStream pairing)
        // must use the same IPv4 address; if only IPv6 has arrived, return null and wait for next update.
        assertNull(HostDiscovery.chooseAddress(listOf(linkLocal, ip("2409:8a00::1ec"))))
    }

    @Test
    fun scopedLinkLocalAndLinkLocalIpv4AreNotDialled() {
        val scoped = Inet6Address.getByAddress(null, linkLocal.address, 3)
        assertNull(HostDiscovery.chooseAddress(listOf(ip("127.0.0.1"), scoped, ip("169.254.10.20"))))
    }

    @Test
    fun fromTxtParsesMrevAndGsPort() {
        val host = DiscoveredHost.fromTxt(
            serviceName = "Study Mac",
            address = "192.168.1.50",
            controlPort = 47990,
            txt = mapOf(
                "v" to "1",
                "id" to "host-abc",
                "name" to "书房 Mac",
                "pair" to "auto",
                "gs" to "47989",
                "media" to "8890",
                "mrev" to "3",
            ),
        )
        assertEquals("host-abc", host?.id)
        assertEquals(47989, host?.gsPort)
        assertEquals(8890, host?.mediaPort)
        assertEquals(3, host?.mediaRevision)
    }

    @Test
    fun r5_m1_registryDistinguishesSameServiceNameByAddressAndPort() {
        val registry = DiscoveredHostRegistry()
        val hostA = DiscoveredHost(id = "host-a", name = "Mac A", address = "192.168.1.50", controlPort = 47990)
        val hostB = DiscoveredHost(id = "host-b", name = "Mac B", address = "192.168.1.60", controlPort = 47990)
        val keyA = registry.put("depth3d-host", hostA)
        registry.put("depth3d-host", hostB)

        val both = registry.snapshot()
        assertEquals("M1: Same serviceName at different addresses must NOT overwrite", 2, both.size)
        assertEquals(setOf("192.168.1.50", "192.168.1.60"), both.map { it.address }.toSet())

        // Removing keyA keeps hostB intact
        registry.removeKey(keyA)
        val remaining = registry.snapshot()
        assertEquals(1, remaining.size)
        assertEquals("192.168.1.60", remaining.single().address)
    }

    @Test
    fun deduplicateEndpointsRetainsImposterAndRealHostWithSameIdAtDifferentAddresses() {
        // #7: An imposter copying a real Host's mDNS id at another IP must NOT evict the real Host.
        val real = DiscoveredHost(id = "host-same", name = "Real Mac", address = "192.168.1.50", controlPort = 47990)
        val imposter = DiscoveredHost(id = "host-same", name = "Evil Mac", address = "192.168.1.99", controlPort = 47990)
        val duplicateReal = DiscoveredHost(id = "host-same", name = "Real Mac", address = "192.168.1.50", controlPort = 47990)

        val deduped = HostDiscovery.deduplicateEndpoints(listOf(imposter, real, duplicateReal))
        assertEquals(2, deduped.size)
        assertEquals(setOf("192.168.1.50", "192.168.1.99"), deduped.map { it.address }.toSet())
    }

    @Test
    fun r6_6_unaddressedServiceLostDoesNotEvictFreshHostSharingServiceName() {
        var nowMs = 1_000L
        val registry = DiscoveredHostRegistry(clockMs = { nowMs })
        val staleHost = DiscoveredHost(id = "host-stale", name = "Old Mac", address = "192.168.1.40", controlPort = 47990)
        val freshHost = DiscoveredHost(id = "host-fresh", name = "Real Mac", address = "192.168.1.50", controlPort = 47990)

        registry.put("depth3d-host", staleHost)
        nowMs = 11_000L
        registry.put("depth3d-host", freshHost)

        // At t = 13_000: staleHost age = 12s (>= 10s stale window), freshHost age = 2s (< 10s).
        // An unaddressed goodbye for "depth3d-host" must prune ONLY staleHost and keep freshHost alive!
        nowMs = 13_000L
        assertTrue(registry.removeStaleByServiceName("depth3d-host", HostDiscovery.LOST_STALE_WINDOW_MS))
        assertTrue(registry.hasEntriesForServiceName("depth3d-host"))
        val afterFirst = registry.snapshot()
        assertEquals(1, afterFirst.size)
        assertEquals("192.168.1.50", afterFirst.single().address)

        // Another unaddressed goodbye while freshHost is still within the stale window must NOT drop it!
        nowMs = 15_000L
        assertFalse(registry.removeStaleByServiceName("depth3d-host", HostDiscovery.LOST_STALE_WINDOW_MS))
        assertEquals(1, registry.snapshot().size)
        assertEquals("192.168.1.50", registry.snapshot().single().address)

        // Once freshHost itself goes stale without being re-resolved, it is pruned cleanly.
        nowMs = 25_000L
        assertTrue(registry.removeStaleByServiceName("depth3d-host", HostDiscovery.LOST_STALE_WINDOW_MS))
        assertFalse(registry.hasEntriesForServiceName("depth3d-host"))
        assertTrue(registry.snapshot().isEmpty())
    }
}
