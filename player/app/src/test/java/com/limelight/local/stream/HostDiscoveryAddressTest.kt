package com.limelight.local.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun routableIpv6BeforeLinkLocal() {
        assertEquals(ip("2409:8a00::1ec").hostAddress, HostDiscovery.chooseAddress(listOf(linkLocal, ip("2409:8a00::1ec"))))
    }

    @Test
    fun scopedLinkLocalIsNotDialledBecauseOkHttpRejectsZoneIds() {
        // #4: OkHttp 5.5.0 HttpUrl.parse returns null for [fe80::...%3] (and %25),
        // so chooseAddress only returns IPv4 or routable (non-link-local) IPv6.
        val scoped = Inet6Address.getByAddress(null, linkLocal.address, 3)
        assertNull(HostDiscovery.chooseAddress(listOf(ip("127.0.0.1"), scoped)))
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
}
