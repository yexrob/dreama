package com.limelight.local.plugin

import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class ManifestAndPermissionTest {

    private val repoRoot: File = locateRepoRoot()

    @Test
    fun parsesValidReferenceAndJellyfinManifests() {
        val refJson = File(repoRoot, "plugins/reference/manifest.json").readText()
        val ref = ManifestParser.parseAndValidate(refJson)
        assertEquals(1, ref.api)
        assertEquals("dev.depth3d.reference", ref.id)
        assertEquals(PluginAuthMode.QR, ref.auth)
        assertEquals(9, ref.capabilities.size)
        assertTrue(ref.multiInstance)

        val jfJson = File(repoRoot, "plugins/jellyfin/manifest.json").readText()
        val jf = ManifestParser.parseAndValidate(jfJson)
        assertEquals("dev.depth3d.jellyfin", jf.id)
        assertEquals(PluginAuthMode.FORM, jf.auth)
        assertTrue(jf.permissions.lan)
        assertTrue(jf.permissions.userHosts)
    }

    @Test
    fun rejectsMalformedManifestsAndSemanticViolations() {
        val base = """
            {
              "api": 1,
              "id": "com.example.demo",
              "name": "Demo",
              "version": "1.0.0",
              "author": "Tester"
            }
        """.trimIndent()
        // Baseline + unknown forward-compatible field must pass
        val withExtra = base.replace("\"Tester\"", "\"Tester\", \"futureField\": 123")
        assertEquals("com.example.demo", ManifestParser.parseAndValidate(withExtra).id)

        // Wrong api version
        assertInvalidManifest(base.replace("\"api\": 1", "\"api\": 2"))
        // Bad reverse-domain id
        assertInvalidManifest(base.replace("com.example.demo", "NotValidId"))
        // Bad semver
        assertInvalidManifest(base.replace("1.0.0", "1.0"))
        // Loopback host in permissions.hosts
        assertInvalidManifest(
            base.replace("\"Tester\"", "\"Tester\", \"permissions\": { \"hosts\": [\"localhost\"] }"),
        )
        assertInvalidManifest(
            base.replace("\"Tester\"", "\"Tester\", \"permissions\": { \"hosts\": [\"127.0.0.1\"] }"),
        )
        // Duplicate config keys
        assertInvalidManifest(
            base.replace(
                "\"Tester\"",
                "\"Tester\", \"config\": [{\"key\":\"a\",\"type\":\"text\",\"label\":\"A\"},{\"key\":\"a\",\"type\":\"text\",\"label\":\"B\"}]",
            ),
        )
        // Select without options
        assertInvalidManifest(
            base.replace(
                "\"Tester\"",
                "\"Tester\", \"config\": [{\"key\":\"q\",\"type\":\"select\",\"label\":\"Q\"}]",
            ),
        )
        // Default type mismatch
        assertInvalidManifest(
            base.replace(
                "\"Tester\"",
                "\"Tester\", \"config\": [{\"key\":\"n\",\"type\":\"number\",\"label\":\"N\",\"default\":\"oops\"}]",
            ),
        )
    }

    @Test
    fun detectsPermissionExpansionCorrectly() {
        val prev = PluginPermissions(
            hosts = listOf("api.example.com", "*.cdn.example.com"),
            userHosts = false,
            lan = false,
        )
        // Same or narrower is NOT expansion
        assertFalse(prev.isExpandedFrom(prev))
        assertFalse(
            PluginPermissions(hosts = listOf("api.example.com", "a.cdn.example.com")).isExpandedFrom(prev),
        )
        assertFalse(
            PluginPermissions(hosts = listOf("*.img.cdn.example.com")).isExpandedFrom(prev),
        )
        // Adding new host, widening exact to wildcard, enabling userHosts or lan ARE expansions
        assertTrue(PluginPermissions(hosts = listOf("api.example.com", "other.com")).isExpandedFrom(prev))
        assertTrue(PluginPermissions(hosts = listOf("*.example.com")).isExpandedFrom(prev))
        assertTrue(prev.copy(userHosts = true).isExpandedFrom(prev))
        assertTrue(prev.copy(lan = true).isExpandedFrom(prev))
    }

    @Test
    fun comparesSemVerCorrectly() {
        assertTrue(SemVer.parse("1.0.1")!! > SemVer.parse("1.0.0")!!)
        assertTrue(SemVer.parse("1.1.0")!! > SemVer.parse("1.0.9")!!)
        assertTrue(SemVer.parse("2.0.0")!! > SemVer.parse("1.9.9")!!)
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("1.0.0-beta.1")!!)
        assertTrue(SemVer.parse("1.0.0-beta.2")!! > SemVer.parse("1.0.0-beta.1")!!)
    }

    @Test
    fun networkPolicyEnforcesWhitelistWildcardsLanPrivateAndLoopback() {
        val dns: (String) -> List<InetAddress> = { host ->
            when (host) {
                "api.example.com", "v1.cdn.example.com", "evil.example.org" ->
                    listOf(InetAddress.getByName("93.184.216.34"))
                "rebind.example.com" -> listOf(InetAddress.getByName("127.0.0.1"))
                "priv.example.com" -> listOf(InetAddress.getByName("192.168.1.50"))
                "cgnat.example.com" -> listOf(InetAddress.getByName("100.64.2.3"))
                "ula.example.com" -> listOf(InetAddress.getByName("fd00::1234"))
                "nas.lan" -> listOf(InetAddress.getByName("10.0.0.8"))
                else -> listOf(InetAddress.getByName("93.184.216.34"))
            }
        }

        val publicPolicy = NetworkPolicy(
            permissions = PluginPermissions(
                hosts = listOf("api.example.com", "*.cdn.example.com"),
                userHosts = false,
                lan = false,
            ),
            dnsResolver = dns,
        )

        // Allowed
        assertEquals("api.example.com", publicPolicy.validateFetchUrl("https://api.example.com/v1").url.host)
        assertEquals("v1.cdn.example.com", publicPolicy.validateFetchUrl("https://v1.cdn.example.com/a.m4s").url.host)

        // Rejected
        assertBlocked { publicPolicy.validateFetchUrl("https://evil.example.org/") }
        assertBlocked { publicPolicy.validateFetchUrl("https://cdn.example.com/") }
        assertBlocked { publicPolicy.validateFetchUrl("http://api.example.com/") }
        assertBlocked { publicPolicy.validateFetchUrl("http://127.0.0.1:8080/") }
        assertBlocked { publicPolicy.validateFetchUrl("https://localhost/") }
        assertBlocked { publicPolicy.validateFetchUrl("https://sub.localhost/") }
        assertBlocked { publicPolicy.validateFetchUrl("http://[::1]:8080/") }
        assertBlocked { publicPolicy.validateFetchUrl("https://rebind.example.com/") }
        assertBlocked { publicPolicy.validateFetchUrl("https://priv.example.com/") }
        assertBlocked { publicPolicy.validateFetchUrl("https://cgnat.example.com/") }
        assertBlocked { publicPolicy.validateFetchUrl("https://ula.example.com/") }
        assertBlocked { publicPolicy.validateFetchUrl("file:///etc/passwd") }

        // PlaybackInfo URL allows arbitrary public CDN, still blocks loopback & private without lan
        assertEquals("random-cdn.net", publicPolicy.validatePlaybackUrl("https://random-cdn.net/v.mp4").url.host)
        assertBlocked { publicPolicy.validatePlaybackUrl("http://127.0.0.1/v.mp4") }
        assertBlocked { publicPolicy.validatePlaybackUrl("http://192.168.1.50/v.mp4") }

        // LAN + userHosts allows private HTTP, still rejects loopback and public HTTP
        val lanPolicy = NetworkPolicy(
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
            userConfiguredHosts = setOf("nas.lan"),
            dnsResolver = dns,
        )
        assertEquals("nas.lan", lanPolicy.validateFetchUrl("http://nas.lan:8096/System/Info").url.host)
        assertEquals("192.168.1.50", lanPolicy.validateFetchUrl("http://192.168.1.50:8096/Items").url.host)
        assertBlocked { lanPolicy.validateFetchUrl("http://127.0.0.1:8096/") }
        assertBlocked { lanPolicy.validateFetchUrl("http://0.0.0.0:8096/") }
        assertBlocked { lanPolicy.validateFetchUrl("http://api.example.com/") }
    }

    @Test
    fun fetchGatewayBlocksRedirectEscapeHostOverrideAndOversizedBody() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/set-cookie" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "sid=token123; Path=/")
                        .setBody("hello")
                    "/echo-cookie" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(request.getHeader("Cookie") ?: "none")
                    "/redir-ok" -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://192.168.1.50:${server.port}/set-cookie")
                    "/redir-escape" -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "https://evil.example.org/steal")
                    "/redir-loopback" -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://127.0.0.1:${server.port}/set-cookie")
                    "/too-big" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("A".repeat(2048))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val policy = NetworkPolicy(
                permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
                userConfiguredHosts = setOf("192.168.1.50"),
                dnsResolver = { host ->
                    if (host == "evil.example.org") listOf(InetAddress.getByName("93.184.216.34"))
                    else listOf(InetAddress.getByName(host))
                },
            )
            val tmpFile = File.createTempFile("cookies-test", ".enc").apply { deleteOnExit() }
            val jar = PluginCookieJar(tmpFile, SoftwareAesSecretBox())
            val client = OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
                .build()
            val gw = PluginFetchGateway(policy, jar, baseClient = client, maxResponseBytes = 1024)

            // Valid redirect within policy + cookie jar capture & resend
            val r1 = gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-ok"))
            assertEquals(200, r1.status)
            assertEquals("hello", String(r1.body, Charsets.UTF_8))

            val r2 = gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/echo-cookie"))
            assertEquals("sid=token123", String(r2.body, Charsets.UTF_8))

            // Redirect manual returns 302
            val rManual = gw.executeBlocking(
                FetchRequest("http://192.168.1.50:${server.port}/redir-ok", redirect = "manual"),
            )
            assertEquals(302, rManual.status)

            // Redirect error throws
            try {
                gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-ok", redirect = "error"))
                fail("Expected redirect=error to throw")
            } catch (_: Exception) {
            }

            // Redirect escape to external or loopback rejected
            assertBlocked { gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-escape")) }
            assertBlocked { gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-loopback")) }

            // Host header override rejected
            assertBlocked {
                gw.executeBlocking(
                    FetchRequest("http://192.168.1.50:${server.port}/set-cookie", headers = mapOf("Host" to "evil.com")),
                )
            }

            // Oversized response rejected
            try {
                gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/too-big"))
                fail("Expected oversized response to fail")
            } catch (_: FetchResponseTooLargeException) {
            }
        } finally {
            server.shutdown()
        }
    }

    private fun assertInvalidManifest(raw: String) {
        try {
            ManifestParser.parseAndValidate(raw)
            fail("Expected invalid manifest to fail: $raw")
        } catch (_: ManifestValidationException) {
        }
    }

    private fun assertBlocked(block: () -> Unit) {
        try {
            block()
            fail("Expected NetworkPolicyException")
        } catch (_: NetworkPolicyException) {
        }
    }

    companion object {
        fun locateRepoRoot(): File {
            var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            repeat(5) {
                if (File(dir, "docs/specs/plugin-protocol.md").isFile) return dir
                dir = dir.parentFile ?: return@repeat
            }
            throw IllegalStateException("Could not locate depth3d repository root from ${System.getProperty("user.dir")}")
        }
    }
}

/**
 * Test-only [SocketFactory] that transparently redirects TCP connections aimed
 * at a synthetic LAN IP (e.g. `192.168.1.50`) to a local [MockWebServer] port,
 * allowing [NetworkPolicy] to run with 100% real production rules (no loopback
 * bypass flag anywhere in production code).
 */
class LocalRedirectSocketFactory(
    private val syntheticIp: String,
    private val targetLocalPort: Int,
) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: java.net.SocketAddress?) {
            connect(endpoint, 0)
        }

        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
            val target = if (endpoint is java.net.InetSocketAddress &&
                (endpoint.address?.hostAddress == syntheticIp || endpoint.hostString == syntheticIp)
            ) {
                java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), targetLocalPort)
            } else {
                endpoint
            }
            super.connect(target, timeout)
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), if (host == syntheticIp) targetLocalPort else port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPortNum: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), if (host == syntheticIp) targetLocalPort else port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socket(
            InetAddress.getLoopbackAddress(),
            if (host.hostAddress == syntheticIp) targetLocalPort else port,
        )

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPortNum: Int): Socket =
        Socket(
            InetAddress.getLoopbackAddress(),
            if (address.hostAddress == syntheticIp) targetLocalPort else port,
        )
}
