package com.limelight.local.dlna

/** One network interface as seen when choosing where to listen. */
data class NetIface(val name: String, val ipv4: List<String>, val up: Boolean, val loopback: Boolean)

/**
 * Which address the receiver serves on: Wi-Fi first, then Ethernet, then
 * any other private LAN address; never tunnels, cellular, Wi-Fi Direct or
 * link-local addresses, which a phone on the same Wi-Fi cannot reach.
 */
object NetPick {
    private val excluded = listOf("lo", "tun", "ppp", "rmnet", "ccmni", "p2p", "dummy", "ip6tnl", "sit", "v4-")

    fun choose(ifaces: List<NetIface>): Pair<String, String>? {
        var best: Pair<String, String>? = null
        var bestRank = Int.MAX_VALUE
        for (i in ifaces) {
            if (!i.up || i.loopback || excluded.any { i.name.startsWith(it) }) {
                continue
            }
            val ip = i.ipv4.firstOrNull { !it.startsWith("169.254.") && !it.startsWith("127.") } ?: continue
            val rank = when {
                i.name.startsWith("wlan") -> 0
                i.name.startsWith("eth") -> 1
                isPrivate(ip) -> 2
                else -> continue
            }
            if (rank < bestRank) {
                best = i.name to ip
                bestRank = rank
            }
        }
        return best
    }

    private fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) {
            return false
        }
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }
}
