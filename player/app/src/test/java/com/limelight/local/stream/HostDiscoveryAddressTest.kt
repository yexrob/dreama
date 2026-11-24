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
    fun scopedLinkLocalIsTheLastResort() {
        val scoped = Inet6Address.getByAddress(null, linkLocal.address, 3)
        assertEquals(scoped.hostAddress, HostDiscovery.chooseAddress(listOf(ip("127.0.0.1"), scoped)))
    }
}
