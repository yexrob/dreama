package com.limelight.local.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

class NetworkPolicyException(message: String) : SecurityException(message)

data class ValidatedUrl(
    val url: HttpUrl,
    val resolvedAddresses: List<InetAddress>,
)

/**
 * Enforces protocol §2.1, §5, and §7 network boundaries for both `ctx.fetch`
 * (host whitelist + redirect re-checking + loopback/private IP blocking) and
 * `PlaybackInfo` URLs (http(s) only + loopback/private IP blocking without
 * host whitelist).
 */
class NetworkPolicy(
    private val permissions: PluginPermissions,
    userConfiguredHosts: Set<String> = emptySet(),
    private val dnsResolver: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
) {
    private val normalizedHosts: List<String> = permissions.hosts.map { it.lowercase(Locale.ROOT) }
    private val normalizedUserHosts: Set<String> = userConfiguredHosts.map { it.lowercase(Locale.ROOT) }.toSet()

    /**
     * Validates a `ctx.fetch` target URL (initial request or any redirect hop).
     * Returns the parsed [HttpUrl] together with the pinned [InetAddress] list
     * that passed validation so OkHttp connects to the exact checked IPs.
     */
    fun validateFetchUrl(rawUrl: String): ValidatedUrl {
        val url = parseHttpUrl(rawUrl)
        val host = url.host.lowercase(Locale.ROOT)

        rejectLoopbackHostName(host)

        val addresses = resolveHost(host)
        if (addresses.isEmpty()) {
            throw NetworkPolicyException("无法解析主机地址：$host")
        }
        val anyLoopback = addresses.any { isLoopbackOrUnspecifiedOrMulticast(it) }
        if (anyLoopback) {
            throw NetworkPolicyException("禁止访问回环或保留地址：$host")
        }
        val anyPrivate = addresses.any { isPrivateOrLanAddress(it) }
        val isLanHost = anyPrivate || isLocalDomain(host)

        if (isLanHost && !permissions.lan) {
            throw NetworkPolicyException("插件未声明局域网权限，禁止访问私有地址：$host")
        }

        // Scheme check: http is only allowed when lan permission is granted and
        // the target is on the local network (protocol §2.1).
        if (url.scheme == "http") {
            if (!permissions.lan) {
                throw NetworkPolicyException("插件未声明局域网权限，仅允许使用 HTTPS：$host")
            }
            if (!isLanHost) {
                throw NetworkPolicyException("公网主机必须使用 HTTPS，禁止明文 HTTP：$host")
            }
        }

        // Host whitelist check:
        // 1. matches permissions.hosts
        // 2. or permissions.userHosts && host is in user-configured URL fields
        // 3. or permissions.lan && host is on the LAN
        val allowed = matchesWhitelist(host, normalizedHosts) ||
            (permissions.userHosts && host in normalizedUserHosts) ||
            (permissions.lan && isLanHost)
        if (!allowed) {
            throw NetworkPolicyException("主机不在插件声明的访问白名单内：$host")
        }

        return ValidatedUrl(url, addresses)
    }

    /**
     * Validates a `PlaybackInfo` stream or subtitle URL (§7):
     * - not restricted by `permissions.hosts` (CDN domains rotate frequently)
     * - must be `http` or `https`
     * - loopback is always rejected
     * - private/LAN addresses require `permissions.lan`
     */
    fun validatePlaybackUrl(rawUrl: String): ValidatedUrl {
        val url = parseHttpUrl(rawUrl)
        val host = url.host.lowercase(Locale.ROOT)

        rejectLoopbackHostName(host)

        val addresses = resolveHost(host)
        if (addresses.isEmpty()) {
            throw NetworkPolicyException("无法解析播放地址主机：$host")
        }
        if (addresses.any { isLoopbackOrUnspecifiedOrMulticast(it) }) {
            throw NetworkPolicyException("播放地址禁止指向回环地址：$host")
        }
        val isLanHost = addresses.any { isPrivateOrLanAddress(it) } || isLocalDomain(host)
        if (isLanHost && !permissions.lan) {
            throw NetworkPolicyException("插件未声明局域网权限，播放地址禁止指向私有网段：$host")
        }
        return ValidatedUrl(url, addresses)
    }

    /**
     * Lightweight syntax-only playback URL validation (does not require DNS,
     * checks scheme, loopback literals, and private IP literals).
     */
    fun validatePlaybackUrlSyntax(rawUrl: String): HttpUrl {
        val url = parseHttpUrl(rawUrl)
        val host = url.host.lowercase(Locale.ROOT)
        rejectLoopbackHostName(host)
        if (isLoopbackLiteral(host)) {
            throw NetworkPolicyException("播放地址禁止指向回环地址：$host")
        }
        if (!permissions.lan && (isPrivateLiteral(host) || isLocalDomain(host))) {
            throw NetworkPolicyException("插件未声明局域网权限，播放地址禁止指向私有网段：$host")
        }
        return url
    }

    private fun parseHttpUrl(rawUrl: String): HttpUrl {
        val parsed = rawUrl.trim().toHttpUrlOrNull()
            ?: throw NetworkPolicyException("不支持或无效的请求地址")
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw NetworkPolicyException("仅允许 http 或 https 协议")
        }
        return parsed
    }

    private fun rejectLoopbackHostName(host: String) {
        if (host == "localhost" || host.endsWith(".localhost") || host == "ip6-localhost" || host == "ip6-loopback") {
            throw NetworkPolicyException("禁止访问回环地址：$host")
        }
    }

    private fun resolveHost(host: String): List<InetAddress> {
        val cleaned = host.trim().removePrefix("[").removeSuffix("]")
        if (looksLikeIpLiteral(cleaned)) {
            return try {
                listOf(InetAddress.getByName(cleaned))
            } catch (e: Exception) {
                throw NetworkPolicyException("无效的 IP 地址 $host：${e.message}")
            }
        }
        return try {
            dnsResolver(host)
        } catch (e: UnknownHostException) {
            throw NetworkPolicyException("无法解析域名 $host：${e.message}")
        }
    }

    companion object {
        fun matchesWhitelist(host: String, patterns: List<String>): Boolean {
            val h = host.lowercase(Locale.ROOT)
            return patterns.any { raw ->
                val p = raw.lowercase(Locale.ROOT)
                if (p.startsWith("*.")) {
                    val suffix = p.removePrefix("*.")
                    h.length > suffix.length + 1 && h.endsWith(".$suffix")
                } else {
                    h == p
                }
            }
        }

        fun extractUserHosts(manifest: PluginManifest, config: Map<String, JsonElement>): Set<String> {
            val out = LinkedHashSet<String>()
            for (key in manifest.urlConfigKeys()) {
                val raw = (config[key] as? JsonPrimitive)?.content?.trim() ?: continue
                if (raw.isEmpty()) continue
                val url = raw.toHttpUrlOrNull() ?: "https://$raw".toHttpUrlOrNull() ?: continue
                out += url.host.lowercase(Locale.ROOT)
            }
            return out
        }

        fun isLocalDomain(host: String): Boolean {
            val h = host.lowercase(Locale.ROOT)
            return h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") ||
                (!h.contains('.') && !h.contains(':'))
        }

        fun isLoopbackLiteral(host: String): Boolean {
            val cleaned = host.trim().removePrefix("[").removeSuffix("]")
            if (!looksLikeIpLiteral(cleaned)) return false
            return try {
                isLoopbackOrUnspecifiedOrMulticast(InetAddress.getByName(cleaned))
            } catch (_: Exception) {
                false
            }
        }

        fun isPrivateLiteral(host: String): Boolean {
            val cleaned = host.trim().removePrefix("[").removeSuffix("]")
            if (!looksLikeIpLiteral(cleaned)) return false
            return try {
                isPrivateOrLanAddress(InetAddress.getByName(cleaned))
            } catch (_: Exception) {
                false
            }
        }

        private fun looksLikeIpLiteral(s: String): Boolean {
            if (s.isEmpty()) return false
            if (s.contains(':')) return true // IPv6 literal
            val parts = s.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }
        }

        fun isLoopbackOrUnspecifiedOrMulticast(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) {
                return true
            }
            val bytes = addr.address
            if (addr is Inet4Address && bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                if (b0 == 0 || b0 == 127) return true
            }
            if (addr is Inet6Address && bytes.size == 16) {
                // IPv4-mapped IPv6 ::ffff:a.b.c.d
                val v4 = extractMappedIpv4(bytes)
                if (v4 != null) {
                    return isLoopbackOrUnspecifiedOrMulticast(v4)
                }
            }
            return false
        }

        fun isPrivateOrLanAddress(addr: InetAddress): Boolean {
            if (addr.isSiteLocalAddress || addr.isLinkLocalAddress) {
                return true
            }
            val bytes = addr.address
            if (addr is Inet4Address && bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // 10.0.0.0/8
                if (b0 == 10) return true
                // 172.16.0.0/12
                if (b0 == 172 && b1 in 16..31) return true
                // 192.168.0.0/16
                if (b0 == 192 && b1 == 168) return true
                // 169.254.0.0/16 (link-local)
                if (b0 == 169 && b1 == 254) return true
                // 100.64.0.0/10 (CGNAT / Tailscale)
                if (b0 == 100 && b1 in 64..127) return true
                // 198.18.0.0/15 (benchmarking / private test ranges)
                if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
            }
            if (addr is Inet6Address && bytes.size == 16) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // ULA fc00::/7
                if ((b0 and 0xFE) == 0xFC) return true
                // Link-local fe80::/10
                if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
                // IPv4-mapped IPv6 ::ffff:a.b.c.d
                val v4 = extractMappedIpv4(bytes)
                if (v4 != null) return isPrivateOrLanAddress(v4)
            }
            return false
        }

        private fun extractMappedIpv4(v6: ByteArray): Inet4Address? {
            for (i in 0 until 10) {
                if (v6[i] != 0.toByte()) return null
            }
            if (v6[10] != 0xFF.toByte() || v6[11] != 0xFF.toByte()) return null
            val v4Bytes = byteArrayOf(v6[12], v6[13], v6[14], v6[15])
            return InetAddress.getByAddress(v4Bytes) as? Inet4Address
        }
    }
}
