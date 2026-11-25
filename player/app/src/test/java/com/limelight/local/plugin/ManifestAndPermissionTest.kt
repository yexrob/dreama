package com.limelight.local.plugin

import com.limelight.local.media.Artwork
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

class ManifestAndPermissionTest {

    private val repoRoot: File = locateRepoRoot()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesValidReferenceAndJellyfinManifestsAndOptionalAuthor() {
        val refJson = File(repoRoot, "plugins/reference/manifest.json").readText()
        val ref = ManifestParser.parseAndValidate(refJson)
        assertEquals(1, ref.api)
        assertEquals("dev.dreama.reference", ref.id)
        assertEquals(PluginAuthMode.QR, ref.auth)
        assertEquals(9, ref.capabilities.size)
        assertTrue(ref.multiInstance)

        val jfJson = File(repoRoot, "plugins/jellyfin/manifest.json").readText()
        val jf = ManifestParser.parseAndValidate(jfJson)
        assertEquals("dev.dreama.jellyfin", jf.id)
        assertEquals(PluginAuthMode.FORM, jf.auth)
        assertTrue(jf.permissions.lan)
        assertTrue(jf.permissions.userHosts)

        // S8 (§11.2): author is optional, optional fields allow null, unknown capabilities tolerated
        val minimal = """
            {
              "api": 1,
              "id": "com.example.noauthor",
              "name": "No Author",
              "version": "1.0.0",
              "description": null,
              "homepage": null,
              "capabilities": ["search", "future-cap-v2"]
            }
        """.trimIndent()
        val parsed = ManifestParser.parseAndValidate(minimal)
        assertEquals("", parsed.author)
        assertEquals(setOf(PluginCapability.SEARCH), parsed.capabilities)
    }

    @Test
    fun rejectsMalformedManifestsTrailingNewlineRegexS2AndSemanticViolations() {
        val base = """
            {
              "api": 1,
              "id": "com.example.demo",
              "name": "Demo",
              "version": "1.0.0",
              "author": "Tester"
            }
        """.trimIndent()
        val withExtra = base.replace("\"Tester\"", "\"Tester\", \"futureField\": 123")
        assertEquals("com.example.demo", ManifestParser.parseAndValidate(withExtra).id)

        // S2 (§11.4): full-string regex matching MUST reject trailing newline in id / version / hosts
        assertInvalidManifest(base.replace("com.example.demo", "com.example.demo\\n"))
        assertInvalidManifest(base.replace("1.0.0", "1.0.0\\n"))
        assertInvalidManifest(
            base.replace("\"Tester\"", "\"Tester\", \"permissions\": { \"hosts\": [\"api.example.com\\n\"] }"),
        )

        // Wrong api, bad id, bad semver
        assertInvalidManifest(base.replace("\"api\": 1", "\"api\": 2"))
        assertInvalidManifest(base.replace("com.example.demo", "NotValidId"))
        assertInvalidManifest(base.replace("1.0.0", "1.0"))

        // Forbidden loopback & non-standard IP literal notations in permissions.hosts
        for (badHost in listOf("localhost", "localhost.", "127.0.0.1", "127.1", "0x7f.0.0.1", "0177.0.0.1", "[::127.0.0.1]")) {
            assertInvalidManifest(
                base.replace("\"Tester\"", "\"Tester\", \"permissions\": { \"hosts\": [\"$badHost\"] }"),
            )
        }

        // Duplicate config keys, select without options, default type mismatch
        assertInvalidManifest(
            base.replace(
                "\"Tester\"",
                "\"Tester\", \"config\": [{\"key\":\"a\",\"type\":\"text\",\"label\":\"A\"},{\"key\":\"a\",\"type\":\"text\",\"label\":\"B\"}]",
            ),
        )
        assertInvalidManifest(
            base.replace(
                "\"Tester\"",
                "\"Tester\", \"config\": [{\"key\":\"q\",\"type\":\"select\",\"label\":\"Q\"}]",
            ),
        )
        assertInvalidManifest(
            base.replace(
                "\"Tester\"",
                "\"Tester\", \"config\": [{\"key\":\"n\",\"type\":\"number\",\"label\":\"N\",\"default\":\"oops\"}]",
            ),
        )
    }

    @Test
    fun passesAllSharedNetworkPolicyVectorsFromCanonicalJson() {
        // Small item #12: test vector JSONs must NOT be bundled into APK resources!
        assertEquals(
            "network-policy-vectors.json must not be bundled into APK resources",
            null,
            JsonSchemaValidator::class.java.classLoader?.getResource("plugin-schema/network-policy-vectors.json"),
        )
        // B1 & S9 (§11.3): exact same vector file run by BOTH Android host and Node plugin-kit
        val raw = File(repoRoot, "docs/specs/plugin-schema/network-policy-vectors.json").readText()
        val root = json.parseToJsonElement(raw).jsonObject
        val dnsJson = root["dns"]!!.jsonObject
        val dnsMap: Map<String, List<InetAddress>> = dnsJson.entries.associate { (host, arr) ->
            host to arr.jsonArray.map { ipEl ->
                NetworkPolicy.parseAnyIpLiteral(ipEl.jsonPrimitive.content)
                    ?: InetAddress.getByName(ipEl.jsonPrimitive.content)
            }
        }
        val mockDns: (String) -> List<InetAddress> = { host ->
            dnsMap[host] ?: throw java.net.UnknownHostException("Unknown test host: $host")
        }

        val fetchCases = root["fetchCases"]!!.jsonArray
        assertTrue(fetchCases.size >= 15)
        for (caseEl in fetchCases) {
            val c = caseEl.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val polObj = c["policy"]!!.jsonObject
            val perms = PluginPermissions(
                hosts = polObj["hosts"]!!.jsonArray.map { it.jsonPrimitive.content },
                userHosts = polObj["userHosts"]!!.jsonPrimitive.boolean,
                lan = polObj["lan"]!!.jsonPrimitive.boolean,
            )
            val userHosts = c["userHosts"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val url = c["url"]!!.jsonPrimitive.content
            val allow = c["allow"]!!.jsonPrimitive.boolean
            val policy = NetworkPolicy(perms, userHosts, mockDns)
            if (allow) {
                policy.validateFetchUrl(url)
            } else {
                try {
                    policy.validateFetchUrl(url)
                    fail("fetchCase '$name' ($url) MUST be rejected")
                } catch (_: NetworkPolicyException) {
                }
            }
        }

        val resourceCases = root["resourceCases"]!!.jsonArray
        assertTrue(resourceCases.size >= 12)
        for (caseEl in resourceCases) {
            val c = caseEl.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val polObj = c["policy"]!!.jsonObject
            val perms = PluginPermissions(
                hosts = polObj["hosts"]!!.jsonArray.map { it.jsonPrimitive.content },
                userHosts = polObj["userHosts"]!!.jsonPrimitive.boolean,
                lan = polObj["lan"]!!.jsonPrimitive.boolean,
            )
            val userHosts = c["userHosts"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val url = c["url"]!!.jsonPrimitive.content
            val allow = c["allow"]!!.jsonPrimitive.boolean
            val policy = NetworkPolicy(perms, userHosts, mockDns)
            if (allow) {
                policy.validateResourceUrl(url)
            } else {
                try {
                    policy.validateResourceUrl(url)
                    fail("resourceCase '$name' ($url) MUST be rejected")
                } catch (_: NetworkPolicyException) {
                }
            }
        }

        // Also explicitly verify HTTPS -> HTTP downgrade rejection (§11.3)
        val userHostPolicy = NetworkPolicy(
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
            userConfiguredHosts = setOf("jellyfin.home.lan"),
            dnsResolver = mockDns,
        )
        assertBlocked { userHostPolicy.validateFetchUrl("http://jellyfin.home.lan/a", previousScheme = "https") }
        assertBlocked { userHostPolicy.validateResourceUrl("http://jellyfin.home.lan/a", previousScheme = "https") }
    }

    @Test
    fun detectsPermissionExpansionAndComparesSemVer() {
        val prev = PluginPermissions(
            hosts = listOf("api.example.com", "*.cdn.example.com"),
            userHosts = false,
            lan = false,
        )
        assertFalse(prev.isExpandedFrom(prev))
        assertFalse(PluginPermissions(hosts = listOf("api.example.com", "a.cdn.example.com")).isExpandedFrom(prev))
        assertFalse(PluginPermissions(hosts = listOf("*.img.cdn.example.com")).isExpandedFrom(prev))
        assertTrue(PluginPermissions(hosts = listOf("api.example.com", "other.com")).isExpandedFrom(prev))
        assertTrue(PluginPermissions(hosts = listOf("*.example.com")).isExpandedFrom(prev))
        assertTrue(prev.copy(userHosts = true).isExpandedFrom(prev))
        assertTrue(prev.copy(lan = true).isExpandedFrom(prev))

        assertTrue(SemVer.parse("1.0.1")!! > SemVer.parse("1.0.0")!!)
        assertTrue(SemVer.parse("1.1.0")!! > SemVer.parse("1.0.9")!!)
        assertTrue(SemVer.parse("2.0.0")!! > SemVer.parse("1.9.9")!!)
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("1.0.0-beta.1")!!)
        assertTrue(SemVer.parse("1.0.0-beta.2")!! > SemVer.parse("1.0.0-beta.1")!!)
    }

    @Test
    fun fetchGatewayBlocksRedirectEscapeDowngradeAndMergesExplicitAndJarCookies() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/set-cookie" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "sid=fromJar; Path=/")
                        .addHeader("Set-Cookie", "jarOnly=keepMe; Path=/")
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
                    "/redir-unlisted-lan" -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://192.168.1.99:${server.port}/set-cookie")
                    "/redir-loopback" -> MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://127.1:${server.port}/set-cookie")
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
                .proxy(Proxy.NO_PROXY)
                .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
                .build()
            val gw = PluginFetchGateway(policy, jar, baseClient = client, maxResponseBytes = 1024)

            // Valid redirect + cookie capture
            val r1 = gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-ok"))
            assertEquals(200, r1.status)

            // §11.1 (S9): Explicit Cookie merges with jar; explicit wins on name collision, jar-only preserved
            val rMerged = gw.executeBlocking(
                FetchRequest(
                    "http://192.168.1.50:${server.port}/echo-cookie",
                    headers = mapOf("Cookie" to "sid=fromExplicit; extra=9"),
                ),
            )
            assertEquals("sid=fromExplicit; jarOnly=keepMe; extra=9", String(rMerged.body, Charsets.UTF_8))

            // Redirect escape, unlisted LAN (even with lan=true!), and shorthand loopback 127.1 rejected
            assertBlocked { gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-escape")) }
            assertBlocked { gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-unlisted-lan")) }
            assertBlocked { gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/redir-loopback")) }
            assertBlocked {
                gw.executeBlocking(
                    FetchRequest("http://192.168.1.50:${server.port}/set-cookie", headers = mapOf("Host" to "evil.com")),
                )
            }

            try {
                gw.executeBlocking(FetchRequest("http://192.168.1.50:${server.port}/too-big"))
                fail("Expected oversized response to fail")
            } catch (_: FetchResponseTooLargeException) {
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetchGatewayStripsCustomHeadersOnCrossOriginRedirectAndEnforcesTotalTimeoutAcrossHops() {
        val server = MockWebServer()
        var targetHeaders: okhttp3.Headers? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/cross-start" -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://93.184.216.35:${server.port}/cross-end")
                "/cross-end" -> {
                    targetHeaders = request.headers
                    MockResponse().setResponseCode(200).setBody("OK")
                }
                "/slow-hop-1" -> {
                    Thread.sleep(160)
                    MockResponse().setResponseCode(302).addHeader("Location", "/slow-hop-2")
                }
                "/slow-hop-2" -> {
                    Thread.sleep(160)
                    MockResponse().setResponseCode(302).addHeader("Location", "/slow-hop-3")
                }
                "/slow-hop-3" -> {
                    Thread.sleep(160)
                    MockResponse().setResponseCode(200).setBody("TOO_LATE")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val policy = NetworkPolicy(
                permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
                userConfiguredHosts = setOf("192.168.1.50", "93.184.216.35"),
                dnsResolver = { host -> listOf(InetAddress.getByName(host)) },
            )
            val tmpFile = File.createTempFile("cookies-cross", ".enc").apply { deleteOnExit() }
            val jar = PluginCookieJar(tmpFile, SoftwareAesSecretBox())
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(MultiIpRedirectSocketFactory(setOf("192.168.1.50", "93.184.216.35"), server.port))
                .build()
            val gw = PluginFetchGateway(policy, jar, baseClient = client)

            // Item #9: cross-origin redirect keeps ONLY safe general headers (Accept, Accept-Language, User-Agent, Range)
            // and strips Authorization, Cookie, Referer, and ALL custom plugin headers.
            val resp = gw.executeBlocking(
                FetchRequest(
                    url = "http://192.168.1.50:${server.port}/cross-start",
                    headers = mapOf(
                        "Authorization" to "Bearer secret",
                        "Cookie" to "sid=topsecret",
                        "Referer" to "https://private.internal/page",
                        "X-Plugin-Secret" to "leak-me",
                        "X-Custom-Header" to "also-strip",
                        "Accept" to "application/json",
                        "Accept-Language" to "zh-CN",
                        "User-Agent" to "depth3d-custom/1",
                        "Range" to "bytes=0-10",
                    ),
                ),
            )
            assertEquals(200, resp.status)
            val hdrs = targetHeaders!!
            assertEquals(null, hdrs["Authorization"])
            assertEquals(null, hdrs["Cookie"])
            assertEquals(null, hdrs["Referer"])
            assertEquals(null, hdrs["X-Plugin-Secret"])
            assertEquals(null, hdrs["X-Custom-Header"])
            assertEquals("application/json", hdrs["Accept"])
            assertEquals("zh-CN", hdrs["Accept-Language"])
            assertEquals("depth3d-custom/1", hdrs["User-Agent"])
            assertEquals("bytes=0-10", hdrs["Range"])

            // Item #8: fetch timeout applies as a single TOTAL deadline across ALL redirect hops
            val startMs = System.currentTimeMillis()
            try {
                gw.executeBlocking(
                    FetchRequest(
                        url = "http://192.168.1.50:${server.port}/slow-hop-1",
                        timeoutMs = 280L,
                    ),
                )
                fail("Expected total timeout across slow redirect hops")
            } catch (_: IOException) {
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            assertTrue("Total timeout across redirects should fail quickly, elapsed=${elapsedMs}ms", elapsedMs < 900L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun imageFetcherAndMapperEnforceNetworkPolicyAndStripCrossOriginHeaders() = runBlocking {
        val server = MockWebServer()
        var lastAuthOnRedirectTarget: String? = "NOT_CALLED"
        var lastCustomOnRedirectTarget: String? = "NOT_CALLED"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/img/ok.jpg" -> MockResponse()
                    .setResponseCode(200)
                    .setBody("JPG:${request.getHeader("Authorization") ?: "none"}")
                "/img/redir-cross-origin" -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://93.184.216.35:${server.port}/img/other-origin")
                "/img/other-origin" -> {
                    lastAuthOnRedirectTarget = request.getHeader("Authorization")
                    lastCustomOnRedirectTarget = request.getHeader("X-Secret")
                    MockResponse().setResponseCode(200).setBody("OTHER_OK")
                }
                "/img/redir-private" -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://192.168.1.99:${server.port}/img/ok.jpg")
                "/img/redir-loopback" -> MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://127.1:${server.port}/img/ok.jpg")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val policy = NetworkPolicy(
                permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
                userConfiguredHosts = setOf("192.168.1.50"),
                dnsResolver = { host -> listOf(InetAddress.getByName(host)) },
            )
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(MultiIpRedirectSocketFactory(setOf("192.168.1.50", "93.184.216.35"), server.port))
                .build()
            val fetcher = PluginImageFetcher(policy, client)

            // 1. Authorized LAN userHost image fetches cleanly with Authorization header
            val okBytes = fetcher.fetchBytes(
                Artwork(
                    url = "http://192.168.1.50:${server.port}/img/ok.jpg",
                    headers = mapOf("Authorization" to "Bearer secret-img-tok"),
                ),
            )
            assertEquals("JPG:Bearer secret-img-tok", String(okBytes, Charsets.UTF_8))

            // 2. Cross-origin redirect strips Authorization & custom headers (§11.3, B1)
            val redirBytes = fetcher.fetchBytes(
                Artwork(
                    url = "http://192.168.1.50:${server.port}/img/redir-cross-origin",
                    headers = mapOf("Authorization" to "Bearer secret", "X-Secret" to "leak"),
                ),
            )
            assertEquals("OTHER_OK", String(redirBytes, Charsets.UTF_8))
            assertEquals(null, lastAuthOnRedirectTarget)
            assertEquals(null, lastCustomOnRedirectTarget)

            // 3. Rejects file://, content://, shorthand 127.1, unlisted LAN, and redirect to private/loopback
            for (badUrl in listOf(
                "file:///etc/passwd",
                "content://media/external/images/1",
                "http://127.1/a.jpg",
                "http://0x7f.0.0.1/a.jpg",
                "http://localhost./a.jpg",
                "http://192.168.1.99:${server.port}/img/ok.jpg",
                "http://192.168.1.50:${server.port}/img/redir-private",
                "http://192.168.1.50:${server.port}/img/redir-loopback",
            )) {
                try {
                    fetcher.fetchBytes(Artwork(url = badUrl))
                    fail("Expected image URL to be rejected: $badUrl")
                } catch (_: IOException) {
                } catch (_: NetworkPolicyException) {
                }
            }

            // 4. PluginProtocolMapper rejects file://, content://, and malicious inline MPD URLs during mapping
            val mapper = PluginProtocolMapper(policy, PluginLogger("test", "i1", sink = { _, _ -> }))
            for (badImg in listOf("file:///etc/passwd", "content://media/1", "http://127.1/a.png")) {
                val rawItem = json.parseToJsonElement(
                    """{"id":"1","kind":"movie","title":"Bad","poster":"$badImg"}""",
                )
                val mapped = mapper.mapItem(rawItem)
                assertEquals("Disallowed image URL ($badImg) must be dropped by mapper", null, mapped.poster)
            }

            val badMpd = json.parseToJsonElement(
                """{"mpd":"<MPD><Period><AdaptationSet><Representation><BaseURL>http://127.1/secret.m4s</BaseURL></Representation></AdaptationSet></Period></MPD>"}""",
            )
            try {
                mapper.mapPlayback(badMpd, fallbackTitle = null)
                fail("Expected inline MPD pointing at 127.1 to be rejected")
            } catch (_: Exception) {
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

class LocalRedirectSocketFactory(
    private val syntheticIp: String,
    private val targetLocalPort: Int,
) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: java.net.SocketAddress?) = connect(endpoint, 0)
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
        Socket(InetAddress.getLoopbackAddress(), if (host.hostAddress == syntheticIp) targetLocalPort else port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPortNum: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), if (address.hostAddress == syntheticIp) targetLocalPort else port)
}

class MultiIpRedirectSocketFactory(
    private val syntheticIps: Set<String>,
    private val targetLocalPort: Int,
) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: java.net.SocketAddress?) = connect(endpoint, 0)
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
            val target = if (endpoint is java.net.InetSocketAddress &&
                (endpoint.address?.hostAddress in syntheticIps || endpoint.hostString in syntheticIps)
            ) {
                java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), targetLocalPort)
            } else {
                endpoint
            }
            super.connect(target, timeout)
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), if (host in syntheticIps) targetLocalPort else port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPortNum: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), if (host in syntheticIps) targetLocalPort else port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), if (host.hostAddress?.let { it in syntheticIps } == true) targetLocalPort else port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPortNum: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), if (address.hostAddress?.let { it in syntheticIps } == true) targetLocalPort else port)
}
