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
    val canonicalHost: String,
    val resolvedAddresses: List<InetAddress>,
)

/**
 * Enforces protocol §2.1, §5, §7, and §11.3 network boundaries across:
 * 1. `ctx.fetch` (initial request + every redirect hop)
 * 2. Player-fetched resources (`PlaybackInfo` streams, backup CDNs, MPD/HLS
 *    manifests & segments, subtitles) and plugin images (initial request + every
 *    redirect hop)
 * 3. Repository & `.d3plugin` downloads (§11.4 public-only policy)
 *
 * Key §11.3 guarantees:
 * - Always rejects non-http(s) schemes (`file:`, `content:`, `data:`, etc.)
 * - Always rejects loopback, link-local, multicast, unspecified, and reserved
 *   ranges (including 240/4, 255.255.255.255, 169.254/16, fe80::/10, TEST-NET,
 *   IPv4-mapped/compatible IPv6 `::127.0.0.1`, NAT64 `64:ff9b::/96` & `64:ff9b:1::/48`,
 *   6to4 `2002::/16`, shorthand `127.1`/`10.1`, hex `0x7f.0.0.1`, octal `0177.0.0.1`,
 *   trailing dot `localhost.`)
 * - `lan: true` ONLY opens private/LAN addresses for hosts in the plugin's
 *   declared scope (`permissions.hosts` or `permissions.userHosts`), NEVER the
 *   entire LAN
 * - User-entered `url` config hosts (`userHosts: true`) are allowed to use `http`
 * - HTTPS -> HTTP downgrade redirects are unconditionally rejected
 * - Callers receive pinned [ValidatedUrl.resolvedAddresses] and connect strictly
 *   to the validated IP without system proxy bypass
 */
class NetworkPolicy(
    val permissions: PluginPermissions,
    userConfiguredHosts: Set<String> = emptySet(),
    private val dnsResolver: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val requireHttpsAlways: Boolean = false,
) {
    private val normalizedHosts: List<String> = permissions.hosts.map { canonicalizePattern(it) }
    private val normalizedUserHosts: Set<String> = userConfiguredHosts.map { canonicalizeHost(it) }.toSet()
    private val normalizedUserIps: Set<String> = userConfiguredHosts.mapNotNull { raw ->
        parseAnyIpLiteral(canonicalizeHost(raw))?.hostAddress
    }.toSet()

    /**
     * Validates a `ctx.fetch` URL (initial request or redirect hop).
     */
    fun validateFetchUrl(rawUrl: String, previousScheme: String? = null): ValidatedUrl {
        val url = parseAndCheckScheme(rawUrl, previousScheme)
        val host = canonicalizeHost(url.host)
        rejectForbiddenHostName(host)

        val addresses = resolveHost(host)
        checkNoAlwaysBlockedAddresses(host, addresses)

        val inHosts = matchesWhitelist(host, normalizedHosts)
        val inUserHosts = permissions.userHosts && isUserConfiguredHost(host, addresses)
        val inDeclaredScope = inHosts || inUserHosts
        if (!inDeclaredScope) {
            throw NetworkPolicyException("主机不在插件声明的访问白名单内：$host")
        }

        val isPrivateOrLan = addresses.any { isPrivateAddress(it) } || isLocalDomain(host)
        if (isPrivateOrLan && !permissions.lan) {
            throw NetworkPolicyException("插件未声明局域网权限，禁止访问私有地址：$host")
        }

        if (url.scheme == "http") {
            if (requireHttpsAlways) {
                throw NetworkPolicyException("此请求仅允许使用 HTTPS：$host")
            }
            // §11.3: http is allowed for user-entered URL hosts, OR for declared hosts in LAN when lan=true
            val httpAllowed = inUserHosts || (permissions.lan && isPrivateOrLan)
            if (!httpAllowed) {
                throw NetworkPolicyException("公网白名单主机必须使用 HTTPS，禁止明文 HTTP：$host")
            }
        }

        return ValidatedUrl(url, host, addresses)
    }

    /**
     * Validates a player-fetched stream, MPD/HLS sub-URL, segment, subtitle, or
     * plugin image URL (initial request or redirect hop) per §11.3:
     * - Public internet hosts are allowed even outside `permissions.hosts`
     * - Private/LAN addresses are ONLY allowed when `permissions.lan == true`
     *   AND the host belongs to `permissions.hosts` or `permissions.userHosts`
     * - Loopback, link-local, multicast, reserved, and non-http(s) are ALWAYS rejected
     * - HTTPS -> HTTP downgrade redirects are ALWAYS rejected
     */
    fun validateResourceUrl(rawUrl: String, previousScheme: String? = null): ValidatedUrl {
        val url = parseAndCheckScheme(rawUrl, previousScheme)
        val host = canonicalizeHost(url.host)
        rejectForbiddenHostName(host)

        val addresses = resolveHost(host)
        checkNoAlwaysBlockedAddresses(host, addresses)

        val inHosts = matchesWhitelist(host, normalizedHosts)
        val inUserHosts = permissions.userHosts && isUserConfiguredHost(host, addresses)
        val inDeclaredScope = inHosts || inUserHosts
        val isPrivateOrLan = addresses.any { isPrivateAddress(it) } || isLocalDomain(host)

        if (isPrivateOrLan) {
            if (!permissions.lan || !inDeclaredScope) {
                throw NetworkPolicyException("该私有网段地址不在插件声明的局域网访问范围内：$host")
            }
        }
        if (requireHttpsAlways && url.scheme != "https") {
            throw NetworkPolicyException("此请求仅允许使用 HTTPS：$host")
        }

        return ValidatedUrl(url, host, addresses)
    }

    /**
     * Alias for backwards clarity when validating `PlaybackInfo` URLs.
     */
    fun validatePlaybackUrl(rawUrl: String, previousScheme: String? = null): ValidatedUrl =
        validateResourceUrl(rawUrl, previousScheme)

    /**
     * Fast syntax & literal-IP check used during manifest/payload mapping BEFORE
     * the resource is actually fetched. Rejects non-http(s), forbidden names
     * (`localhost.`), all non-standard IP literal notations (`127.1`, `0x7f.0.0.1`,
     * `0177.0.0.1`, `10.1`, `[::127.0.0.1]`, `64:ff9b::...`, `240/4`,
     * `255.255.255.255`, `169.254/16`), and out-of-scope LAN literals.
     * Note: real requests STILL run full DNS resolution via [validateResourceUrl].
     */
    fun validateResourceUrlPrecheck(rawUrl: String): HttpUrl {
        val url = parseAndCheckScheme(rawUrl, previousScheme = null)
        val host = canonicalizeHost(url.host)
        rejectForbiddenHostName(host)

        val parsedIp = parseAnyIpLiteral(host)
        if (parsedIp != null) {
            checkNoAlwaysBlockedAddresses(host, listOf(parsedIp))
            if (isPrivateAddress(parsedIp)) {
                val inDeclared = matchesWhitelist(host, normalizedHosts) ||
                    (permissions.userHosts && isUserConfiguredHost(host, listOf(parsedIp)))
                if (!permissions.lan || !inDeclared) {
                    throw NetworkPolicyException("该私有网段地址不在插件声明的局域网访问范围内：$host")
                }
            }
        } else if (isLocalDomain(host)) {
            val inDeclared = matchesWhitelist(host, normalizedHosts) ||
                (permissions.userHosts && host in normalizedUserHosts)
            if (!permissions.lan || !inDeclared) {
                throw NetworkPolicyException("该局域网域名不在插件声明的访问范围内：$host")
            }
        }
        return url
    }

    private fun parseAndCheckScheme(rawUrl: String, previousScheme: String?): HttpUrl {
        val trimmed = rawUrl.trim()
        // Reject raw schemes before HttpUrl parsing (catches file:, content:, data:, etc.)
        val schemePrefix = trimmed.substringBefore("://", "").lowercase(Locale.ROOT)
        if (schemePrefix != "http" && schemePrefix != "https") {
            throw NetworkPolicyException("仅允许 http 或 https 协议")
        }
        val parsed = trimmed.toHttpUrlOrNull()
            ?: throw NetworkPolicyException("不支持或无效的请求地址")
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw NetworkPolicyException("仅允许 http 或 https 协议")
        }
        if (previousScheme.equals("https", ignoreCase = true) && parsed.scheme == "http") {
            throw NetworkPolicyException("禁止从 HTTPS 降级重定向到 HTTP")
        }
        return parsed
    }

    private fun rejectForbiddenHostName(host: String) {
        if (isForbiddenHostName(host)) {
            throw NetworkPolicyException("禁止访问回环或本地主机名：$host")
        }
    }

    private fun isUserConfiguredHost(canonicalHost: String, resolved: List<InetAddress>): Boolean {
        if (canonicalHost in normalizedUserHosts) return true
        if (normalizedUserIps.isNotEmpty() && resolved.all { addr -> addr.hostAddress?.let { it in normalizedUserIps } == true }) {
            return true
        }
        return false
    }

    private fun resolveHost(host: String): List<InetAddress> {
        parseAnyIpLiteral(host)?.let { return listOf(it) }
        return try {
            val list = dnsResolver(host)
            if (list.isEmpty()) throw NetworkPolicyException("无法解析主机地址：$host")
            list
        } catch (e: NetworkPolicyException) {
            throw e
        } catch (e: UnknownHostException) {
            throw NetworkPolicyException("无法解析域名 $host：${e.message}")
        }
    }

    private fun checkNoAlwaysBlockedAddresses(host: String, addresses: List<InetAddress>) {
        if (addresses.any { isAlwaysBlockedAddress(it) }) {
            throw NetworkPolicyException("禁止访问回环、链路本地或保留地址：$host")
        }
    }

    companion object {
        /**
         * Public-only policy for repository index and `.d3plugin` package
         * downloads (§11.4): HTTPS required, no private/loopback/reserved IPs.
         */
        fun forPublicRepository(
            dnsResolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
        ): NetworkPolicy = NetworkPolicy(
            permissions = PluginPermissions(hosts = emptyList(), userHosts = false, lan = false),
            userConfiguredHosts = emptySet(),
            dnsResolver = dnsResolver,
            requireHttpsAlways = true,
        )

        /**
         * Dev-only policy for `d3plugin serve` on LAN (`lan=true`, allows HTTP
         * on the explicitly entered dev host, still blocks loopback/link-local/reserved).
         */
        fun forDevServer(
            devHost: String,
            dnsResolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
        ): NetworkPolicy = NetworkPolicy(
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
            userConfiguredHosts = setOf(canonicalizeHost(devHost)),
            dnsResolver = dnsResolver,
            requireHttpsAlways = false,
        )

        fun canonicalizeHost(raw: String): String {
            var h = raw.trim().lowercase(Locale.ROOT)
            if (h.startsWith("[") && h.endsWith("]")) {
                h = h.substring(1, h.length - 1)
            }
            // Strip IPv6 zone identifier if any (e.g. fe80::1%eth0)
            val pct = h.indexOf('%')
            if (pct >= 0) h = h.substring(0, pct)
            // Strip trailing DNS root dots (e.g. "localhost." -> "localhost")
            while (h.endsWith(".")) {
                h = h.dropLast(1)
            }
            return h
        }

        private fun canonicalizePattern(raw: String): String {
            val low = raw.trim().lowercase(Locale.ROOT)
            return if (low.startsWith("*.")) {
                "*." + canonicalizeHost(low.removePrefix("*."))
            } else {
                canonicalizeHost(low)
            }
        }

        fun isForbiddenHostName(canonicalHost: String): Boolean {
            if (canonicalHost.isEmpty()) return true
            return canonicalHost == "localhost" ||
                canonicalHost.endsWith(".localhost") ||
                canonicalHost == "ip6-localhost" ||
                canonicalHost == "ip6-loopback"
        }

        fun isAlwaysBlockedLiteral(raw: String): Boolean {
            val ip = parseAnyIpLiteral(canonicalizeHost(raw)) ?: return false
            return isAlwaysBlockedAddress(ip)
        }

        fun matchesWhitelist(canonicalHost: String, patterns: List<String>): Boolean {
            val h = canonicalizeHost(canonicalHost)
            return patterns.any { p ->
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
                val raw = (config[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: continue
                if (raw.isEmpty()) continue
                val url = raw.toHttpUrlOrNull() ?: "https://$raw".toHttpUrlOrNull() ?: continue
                out += canonicalizeHost(url.host)
            }
            return out
        }

        fun isLocalDomain(canonicalHost: String): Boolean {
            val h = canonicalizeHost(canonicalHost)
            if (parseAnyIpLiteral(h) != null) return false
            return h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") ||
                (!h.contains('.') && !h.contains(':'))
        }

        /**
         * Parses standard IPv4, POSIX `inet_aton` non-standard IPv4 (shorthand
         * `127.1`/`10.1`, hex `0x7f.0.0.1`/`0x7f000001`, octal `0177.0.0.1`),
         * and IPv6 literals (including `[::127.0.0.1]`, `[::ffff:127.0.0.1]`,
         * NAT64 `64:ff9b::...`). Returns null for regular domain names.
         */
        fun parseAnyIpLiteral(rawHost: String): InetAddress? {
            val h = canonicalizeHost(rawHost)
            if (h.isEmpty()) return null

            // IPv6 literal (contains ':')
            if (h.contains(':')) {
                return parseIpv6Literal(h)
            }

            // IPv4 literal (decimal, octal, hex, or 1–4 part shorthand)
            return parseFlexibleIpv4(h)
        }

        private fun parseFlexibleIpv4(host: String): Inet4Address? {
            val parts = host.split('.')
            if (parts.isEmpty() || parts.size > 4) return null
            val values = LongArray(parts.size)
            for (i in parts.indices) {
                val p = parts[i]
                if (p.isEmpty()) return null
                val parsed = parseIpv4Part(p) ?: return null
                values[i] = parsed
            }
            val addr32: Long = when (values.size) {
                1 -> {
                    if (values[0] !in 0L..0xFFFFFFFFL) return null
                    values[0]
                }
                2 -> {
                    if (values[0] !in 0L..0xFFL || values[1] !in 0L..0xFFFFFFL) return null
                    (values[0] shl 24) or values[1]
                }
                3 -> {
                    if (values[0] !in 0L..0xFFL || values[1] !in 0L..0xFFL || values[2] !in 0L..0xFFFFL) return null
                    (values[0] shl 24) or (values[1] shl 16) or values[2]
                }
                4 -> {
                    if (values.any { it !in 0L..0xFFL }) return null
                    (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
                }
                else -> return null
            }
            val bytes = byteArrayOf(
                ((addr32 ushr 24) and 0xFF).toByte(),
                ((addr32 ushr 16) and 0xFF).toByte(),
                ((addr32 ushr 8) and 0xFF).toByte(),
                (addr32 and 0xFF).toByte(),
            )
            return InetAddress.getByAddress(bytes) as Inet4Address
        }

        private fun parseIpv4Part(part: String): Long? {
            return when {
                part.startsWith("0x") || part.startsWith("0X") -> {
                    val hex = part.substring(2)
                    if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) null
                    else hex.toLongOrNull(16)
                }
                part.length > 1 && part.startsWith("0") -> {
                    if (!part.all { it in '0'..'7' }) null
                    else part.toLongOrNull(8)
                }
                else -> {
                    if (!part.all { it in '0'..'9' }) null
                    else part.toLongOrNull(10)
                }
            }
        }

        private fun parseIpv6Literal(host: String): InetAddress? {
            // Validate allowed characters strictly so arbitrary strings are never sent to DNS
            if (!host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return null
            }
            // Handle dotted-quad tail manually (e.g. ::127.0.0.1, ::ffff:10.0.0.1, 64:ff9b::192.168.1.1)
            val normalized = if (host.contains('.')) {
                val lastColon = host.lastIndexOf(':')
                if (lastColon < 0) return null
                val v4 = parseFlexibleIpv4(host.substring(lastColon + 1)) ?: return null
                val b = v4.address
                val hi = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                val lo = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
                host.substring(0, lastColon + 1) + String.format(Locale.US, "%x:%x", hi, lo)
            } else {
                host
            }
            val bytes = parsePureIpv6Bytes(normalized) ?: return null
            return InetAddress.getByAddress(bytes)
        }

        private fun parsePureIpv6Bytes(ip: String): ByteArray? {
            val halves = ip.split("::")
            if (halves.size > 2) return null
            val left = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
            val right = if (halves.size == 2 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()
            val missing = if (halves.size == 2) 8 - (left.size + right.size) else 0
            if (missing < 0 || (halves.size == 1 && left.size != 8)) return null

            val groups = IntArray(8)
            var idx = 0
            for (g in left) {
                val v = g.toIntOrNull(16) ?: return null
                if (v !in 0..0xFFFF) return null
                groups[idx++] = v
            }
            repeat(missing) { groups[idx++] = 0 }
            for (g in right) {
                val v = g.toIntOrNull(16) ?: return null
                if (v !in 0..0xFFFF) return null
                groups[idx++] = v
            }
            val out = ByteArray(16)
            for (i in 0 until 8) {
                out[i * 2] = ((groups[i] ushr 8) and 0xFF).toByte()
                out[i * 2 + 1] = (groups[i] and 0xFF).toByte()
            }
            return out
        }

        /**
         * Returns true for addresses that are ALWAYS forbidden regardless of `lan`:
         * loopback, unspecified, link-local, multicast, broadcast, reserved 240/4,
         * documentation/test nets, and IPv6 transition prefixes embedding any of those.
         */
        fun isAlwaysBlockedAddress(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) {
                return true
            }
            val bytes = addr.address
            if (bytes.size == 4) {
                return isAlwaysBlockedIpv4(bytes)
            }
            if (bytes.size == 16) {
                return isAlwaysBlockedIpv6(bytes)
            }
            return true
        }

        /**
         * Returns true for private/LAN ranges (only permitted when `lan == true`
         * AND the host is in the plugin's declared scope per §11.3).
         */
        fun isPrivateAddress(addr: InetAddress): Boolean {
            if (addr.isSiteLocalAddress) return true
            val bytes = addr.address
            if (bytes.size == 4) {
                return isPrivateIpv4(bytes)
            }
            if (bytes.size == 16) {
                return isPrivateIpv6(bytes)
            }
            return false
        }

        private fun isAlwaysBlockedIpv4(b: ByteArray): Boolean {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            // 0.0.0.0/8 (unspecified)
            if (b0 == 0) return true
            // 127.0.0.0/8 (loopback)
            if (b0 == 127) return true
            // 169.254.0.0/16 (link-local / cloud metadata)
            if (b0 == 169 && b1 == 254) return true
            // 192.0.0.0/24 (IETF protocol assignments)
            if (b0 == 192 && b1 == 0 && b2 == 0) return true
            // 192.0.2.0/24 (TEST-NET-1)
            if (b0 == 192 && b1 == 0 && b2 == 2) return true
            // 198.51.100.0/24 (TEST-NET-2)
            if (b0 == 198 && b1 == 51 && b2 == 100) return true
            // 203.0.113.0/24 (TEST-NET-3)
            if (b0 == 203 && b1 == 0 && b2 == 113) return true
            // 224.0.0.0/4 (multicast) and 240.0.0.0/4 (reserved + 255.255.255.255 broadcast)
            if (b0 >= 224) return true
            return false
        }

        private fun isPrivateIpv4(b: ByteArray): Boolean {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 10) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 192 && b1 == 168) return true
            if (b0 == 100 && b1 in 64..127) return true // CGNAT / Tailscale
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true // benchmarking
            return false
        }

        private fun isAlwaysBlockedIpv6(b: ByteArray): Boolean {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // :: (unspecified) or ::1 (loopback)
            val first15Zero = (0 until 15).all { b[it] == 0.toByte() }
            if (first15Zero && (b[15] == 0.toByte() || b[15] == 1.toByte())) return true
            // fe80::/10 (link-local)
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
            // ff00::/8 (multicast)
            if (b0 == 0xFF) return true
            // 100::/64 (discard-only RFC 6666)
            if (b0 == 0x01 && b1 == 0x00 && (2 until 8).all { b[it] == 0.toByte() }) return true
            // 2001:db8::/32 (documentation)
            if (b0 == 0x20 && b1 == 0x01 && (b[2].toInt() and 0xFF) == 0x0D && (b[3].toInt() and 0xFF) == 0xB8) {
                return true
            }
            // Embedded IPv4 in IPv4-mapped, IPv4-compatible, NAT64, or 6to4
            extractEmbeddedIpv4(b)?.let { v4 ->
                if (isAlwaysBlockedIpv4(v4)) return true
            }
            return false
        }

        private fun isPrivateIpv6(b: ByteArray): Boolean {
            val b0 = b[0].toInt() and 0xFF
            // fc00::/7 (ULA)
            if ((b0 and 0xFE) == 0xFC) return true
            // NAT64 local-use 64:ff9b:1::/48 is private-use (RFC 8215)
            if (b0 == 0x00 && (b[1].toInt() and 0xFF) == 0x64 &&
                (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                b[4] == 0.toByte() && b[5] == 1.toByte()
            ) {
                return true
            }
            // Embedded IPv4 in IPv4-mapped, IPv4-compatible, NAT64, or 6to4
            extractEmbeddedIpv4(b)?.let { v4 ->
                if (isPrivateIpv4(v4)) return true
            }
            return false
        }

        private fun extractEmbeddedIpv4(b: ByteArray): ByteArray? {
            // 1. IPv4-mapped ::ffff:a.b.c.d
            if ((0 until 10).all { b[it] == 0.toByte() } &&
                (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
            ) {
                return byteArrayOf(b[12], b[13], b[14], b[15])
            }
            // 2. IPv4-compatible ::a.b.c.d (excluding :: and ::1 handled above)
            if ((0 until 12).all { b[it] == 0.toByte() } &&
                !(b[12] == 0.toByte() && b[13] == 0.toByte() && b[14] == 0.toByte() &&
                    (b[15] == 0.toByte() || b[15] == 1.toByte()))
            ) {
                return byteArrayOf(b[12], b[13], b[14], b[15])
            }
            // 3. NAT64 well-known prefix 64:ff9b::/96 (RFC 6052)
            if (b[0] == 0.toByte() && (b[1].toInt() and 0xFF) == 0x64 &&
                (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                (4 until 12).all { b[it] == 0.toByte() }
            ) {
                return byteArrayOf(b[12], b[13], b[14], b[15])
            }
            // 4. NAT64 local-use prefix 64:ff9b:1::/48 (last 32 bits carry IPv4)
            if (b[0] == 0.toByte() && (b[1].toInt() and 0xFF) == 0x64 &&
                (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                b[4] == 0.toByte() && b[5] == 1.toByte()
            ) {
                return byteArrayOf(b[12], b[13], b[14], b[15])
            }
            // 5. 6to4 2002:aabb:ccdd::/48
            if ((b[0].toInt() and 0xFF) == 0x20 && (b[1].toInt() and 0xFF) == 0x02) {
                return byteArrayOf(b[2], b[3], b[4], b[5])
            }
            return null
        }

        fun originKey(url: HttpUrl): String =
            "${url.scheme.lowercase(Locale.ROOT)}://${canonicalizeHost(url.host)}:${url.port}"
    }
}
