package com.limelight.local.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetPickTest {
    private fun up(name: String, vararg ip: String) = NetIface(name, ip.toList(), up = true, loopback = false)

    @Test
    fun wifiWinsOverEverythingElse() {
        val pick = NetPick.choose(listOf(
            NetIface("lo", listOf("127.0.0.1"), up = true, loopback = true),
            up("rmnet_data0", "10.33.1.2"),
            up("eth0", "192.168.2.9"),
            up("wlan0", "192.168.1.23"),
        ))
        assertEquals("wlan0" to "192.168.1.23", pick)
    }

    @Test
    fun tunnelsCellularAndLinkLocalAreSkipped() {
        assertNull(NetPick.choose(listOf(
            up("tun0", "10.8.0.2"),
            up("p2p-wlan0-0", "192.168.49.1"),
            up("ccmni0", "10.1.1.1"),
            up("wlan0", "169.254.3.4"),
            NetIface("wlan1", listOf("192.168.1.5"), up = false, loopback = false),
        )))
    }

    @Test
    fun otherPrivateLanIsTheLastChoice() {
        assertEquals("br0" to "172.20.0.4", NetPick.choose(listOf(up("br0", "172.20.0.4"))))
        assertNull(NetPick.choose(listOf(up("br0", "8.8.8.8"))))
        assertEquals("eth0" to "10.0.0.7", NetPick.choose(listOf(up("br0", "172.20.0.4"), up("eth0", "10.0.0.7"))))
    }
}
