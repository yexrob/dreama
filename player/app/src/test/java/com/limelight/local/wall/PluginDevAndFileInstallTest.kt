package com.limelight.local.wall

import com.limelight.local.media.PluginInternalException
import com.limelight.local.media.Shelf
import com.limelight.local.media.SourceKind
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.LocalRedirectSocketFactory
import com.limelight.local.plugin.ManifestValidationException
import com.limelight.local.plugin.NetworkPolicy
import com.limelight.local.plugin.NetworkPolicyException
import com.limelight.local.plugin.PackageInstallException
import com.limelight.local.plugin.PluginMediaSource
import com.limelight.local.plugin.PluginPackageArchive
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.PluginRepoEntity
import com.limelight.local.plugin.PluginRepositoryClient
import com.limelight.local.plugin.PluginSourceMismatchException
import com.limelight.local.plugin.RepositoryVerificationException
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.PhoneInputCopy
import com.limelight.local.stream.PhoneInputErrorHelper
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.UdpDiscoveryProtocol
import com.limelight.local.stream.UdpDiscoveryWindow
import com.limelight.local.stream.UdpLanServerDiscovery
import com.limelight.local.stream.XmlStringLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Comprehensive unit + MockWebServer E2E tests for Task E (`docs/specs/05-plugin-dev-install.md`):
 * 1. Dev server address normalization & LAN-only rejection table (RFC1918/ULA vs loopback,
 *    link-local, public, reserved, paths, and cross-host redirects).
 * 2. Same-version different-SHA dev reload, permission expansion vs shrink, offline fallback,
 *    and syntax/`init` raw technical error diagnostics on Developer screen vs Chinese-only elsewhere.
 * 3. Mutual replacement confirmation between dev and built-in/repo plugins + dev update exclusion.
 * 4. Local file install (>8 MB, bad zip, invalid manifest, minApp too high, valid file, scanner)
 *    and direct HTTPS URL install.
 * 5. App-side UDP dev server discovery parsing, private-IP filtering, deduplication, and expiry.
 * 6. MockWebServer JVM E2E simulating `d3plugin serve`: discover/normalize -> preview -> install ->
 *    create instance -> `home()` -> mutate package (same version) -> reload -> `home()` sees new
 *    content while preserving `ctx.storage`.
 */
class PluginDevAndFileInstallTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val strings = XmlStringLookup

    private fun buildZip(manifestJson: String, mainJs: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("main.js"))
            zos.write(mainJs.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun makeManifest(
        id: String = "com.example.devplug",
        name: String = "Dev Plug",
        version: String = "1.0.0",
        minApp: String = "1.0.0",
        hosts: List<String> = emptyList(),
        userHosts: Boolean = false,
        lan: Boolean = false,
    ): String {
        val hostsJson = hosts.joinToString(",") { "\"$it\"" }
        return """
            {
              "api": 1,
              "id": "$id",
              "name": "$name",
              "version": "$version",
              "author": "DevAuthor",
              "description": "Dev test plugin",
              "license": "MIT",
              "minApp": "$minApp",
              "multiInstance": true,
              "permissions": {
                "hosts": [$hostsJson],
                "userHosts": $userHosts,
                "lan": $lan
              },
              "config": [],
              "auth": "none"
            }
        """.trimIndent()
    }

    private fun simpleMainJs(shelfTitle: String): String = """
        export default {
          async home(ctx) {
            return [{ id: 's1', title: '$shelfTitle', style: 'poster', items: [] }];
          },
          async browse() { return { items: [], next: null }; },
          async detail() { return { id: '1', kind: 'movie', title: 'One' }; },
          async playback() { return { url: 'https://cdn.example.com/v.mp4' }; }
        };
    """.trimIndent()

    private fun makeDevIndexJson(
        id: String,
        name: String,
        version: String,
        sha256: String,
        pluginUrl: String,
    ): String = """
        {
          "name": "Dev ($name)",
          "plugins": [
            {
              "id": "$id",
              "name": "$name",
              "version": "$version",
              "description": "Dev test plugin",
              "url": "$pluginUrl",
              "sha256": "$sha256",
              "minApp": "1.0.0"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun devAddressNormalizationAndPolicyRejectionTable() = runBlocking {
        val dnsTable: (String) -> List<InetAddress> = { host ->
            when (host.lowercase()) {
                "localhost" -> listOf(InetAddress.getByName("127.0.0.1"))
                "example.com" -> listOf(InetAddress.getByName("93.184.216.34"))
                "lan-alias.local" -> listOf(InetAddress.getByName("192.168.1.20"))
                else -> listOf(InetAddress.getByName(host))
            }
        }
        val validatorClient = PluginRepositoryClient(
            devPolicyFactory = { host -> NetworkPolicy.forDevServer(host, dnsTable) },
        )

        // 1. Valid RFC1918 & IPv6 ULA forms (with/without scheme, port, path)
        val accepted = listOf(
            "192.168.1.20:8787" to "http://192.168.1.20:8787",
            "http://192.168.1.20:8787" to "http://192.168.1.20:8787",
            "http://10.0.0.5:9000/index.json" to "http://10.0.0.5:9000",
            "172.16.4.2:8787/plugin.d3plugin" to "http://172.16.4.2:8787",
            "172.31.255.1:8080/sub/path?x=1" to "http://172.31.255.1:8080",
            "192.168.1.20" to "http://192.168.1.20:8787",
            "[fd00::1]:8787" to "http://[fd00::1]:8787",
            "http://[fc00::10]:8787/anything" to "http://[fc00::10]:8787",
            "fd12:3456::1" to "http://[fd12:3456::1]:8787",
            "https://192.168.1.20:8443/index.json" to "https://192.168.1.20:8443",
        )
        for ((input, expectedBase) in accepted) {
            val norm = validatorClient.normalizeDevServerAddress(input, dnsTable)
            assertEquals("Failed normalizing $input", expectedBase, norm.baseUrl)
            assertEquals("$expectedBase/index.json", norm.indexUrl)
        }

        // 2. Rejections with clear Chinese reasons per category (and preserved through formatInstallError)
        // Loopback (including decimal integer IPv4 2130706433 == 127.0.0.1)
        val loopbackCases = listOf(
            "127.0.0.1:8787",
            "127.1:8787",
            "0x7f.0.0.1:8787",
            "2130706433:8787",
            "localhost:8787",
            "[::1]:8787",
            "[::ffff:127.0.0.1]:8787",
        )
        for (raw in loopbackCases) {
            try {
                validatorClient.normalizeDevServerAddress(raw, dnsTable)
                fail("Expected loopback rejection for $raw")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese loopback reason for $raw, got: $formatted", formatted.contains("回环"))
            }
        }

        // Link-local
        val linkLocalCases = listOf("169.254.1.1:8787", "[fe80::1]:8787")
        for (raw in linkLocalCases) {
            try {
                validatorClient.normalizeDevServerAddress(raw, dnsTable)
                fail("Expected link-local rejection for $raw")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese link-local reason for $raw, got: $formatted", formatted.contains("链路本地"))
            }
        }

        // Public / non-RFC1918 (including decimal 134744072 == 8.8.8.8, CGNAT 100.64.0.1, 172.32.0.1)
        val publicCases = listOf(
            "8.8.8.8:8787",
            "134744072:8787",
            "100.64.0.1:8787",
            "172.32.0.1:8787",
            "example.com:8787",
            "[2001:4860:4860::8888]:8787",
        )
        for (raw in publicCases) {
            try {
                validatorClient.normalizeDevServerAddress(raw, dnsTable)
                fail("Expected public/non-private rejection for $raw")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue(
                    "Expected Chinese public/private-only reason for $raw, got: $formatted",
                    formatted.contains("公网") || formatted.contains("私有"),
                )
            }
        }

        // Reserved / unspecified / multicast
        val reservedCases = listOf("0.0.0.0:8787", "224.0.0.1:8787", "240.0.0.1:8787")
        for (raw in reservedCases) {
            try {
                validatorClient.normalizeDevServerAddress(raw, dnsTable)
                fail("Expected reserved rejection for $raw")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue(
                    "Expected Chinese reserved reason for $raw, got: $formatted",
                    formatted.contains("保留") || formatted.contains("私有"),
                )
            }
        }

        // Unsupported schemes, user@host userinfo, empty
        for (
            raw in listOf(
                "",
                "   ",
                "ftp://192.168.1.20:8787",
                "file:///tmp/plugin.d3plugin",
                "user@192.168.1.20:8787",
                "http://user:pass@192.168.1.20:8787",
                "user@fd00::1",
            )
        ) {
            try {
                validatorClient.normalizeDevServerAddress(raw, dnsTable)
                fail("Expected rejection for $raw")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese reason for '$raw': $formatted", formatted.any { it.code > 0x7F })
            }
        }

        // Pinned scheme + host + port enforcement directly on NetworkPolicy.forDevServer
        val pinnedPolicy = NetworkPolicy.forDevServer(
            devHost = "192.168.1.20",
            dnsResolver = dnsTable,
            pinnedPort = 8787,
            pinnedScheme = "http",
        )
        pinnedPolicy.validateResourceUrl("http://192.168.1.20:8787/plugin.d3plugin")
        try {
            pinnedPolicy.validateResourceUrl("http://192.168.1.21:8787/plugin.d3plugin")
            fail("Different LAN host must be rejected by pinned dev policy")
        } catch (e: NetworkPolicyException) {
            assertTrue("Expected pinned-host Chinese reason, got: ${e.message}", e.message!!.contains("指定的开发服务器主机"))
        }
        try {
            pinnedPolicy.validateResourceUrl("http://192.168.1.20:9999/plugin.d3plugin")
            fail("Different port on same LAN host must be rejected by pinned dev policy")
        } catch (e: NetworkPolicyException) {
            assertTrue("Expected pinned-port Chinese reason, got: ${e.message}", e.message!!.contains("端口"))
        }
        try {
            pinnedPolicy.validateResourceUrl("https://192.168.1.20:8787/plugin.d3plugin")
            fail("Different scheme on same LAN host:port must be rejected by pinned dev policy")
        } catch (e: NetworkPolicyException) {
            assertTrue("Expected pinned-scheme Chinese reason, got: ${e.message}", e.message!!.contains("协议"))
        }

        // 3. Live MockWebServer: cross-host/port/scheme redirects, malicious index.json URLs, and DNS rebinding
        val redirectServer = MockWebServer()
        val mode = AtomicReference("redirect")
        val redirectTarget = AtomicReference("http://192.168.1.99:8787/index.json")
        val maliciousEntryUrl = AtomicReference("http://192.168.1.99:8787/plugin.d3plugin")
        val validZip = buildZip(makeManifest(), simpleMainJs("Ok"))
        val validSha = PluginRepositoryClient.sha256Hex(validZip)
        redirectServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (mode.get()) {
                "redirect" -> MockResponse().setResponseCode(302).addHeader("Location", redirectTarget.get())
                "bad-index-url" -> if (request.path == "/index.json") {
                    MockResponse().setResponseCode(200).setBody(
                        makeDevIndexJson("com.example.devplug", "Dev Plug", "1.0.0", validSha, maliciousEntryUrl.get()),
                    )
                } else {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(validZip))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        redirectServer.start()
        try {
            val lanHost = "192.168.1.50"
            val okHttp = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .socketFactory(LocalRedirectSocketFactory(lanHost, redirectServer.port))
                .build()
            val repoClient = PluginRepositoryClient(
                httpClient = okHttp,
                devPolicyFactory = { host -> NetworkPolicy.forDevServer(host) { listOf(InetAddress.getByName(it)) } },
            )
            // Redirect to another LAN IP
            redirectTarget.set("http://192.168.1.99:${redirectServer.port}/index.json")
            try {
                repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                fail("Cross-host LAN redirect MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese redirect rejection, got: $formatted", formatted.contains("重定向"))
            }

            // Redirect to a DIFFERENT PORT on the SAME LAN IP
            redirectTarget.set("http://$lanHost:${redirectServer.port + 1}/index.json")
            try {
                repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                fail("Cross-port redirect on same LAN host MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese port-redirect rejection, got: $formatted", formatted.contains("端口"))
            }

            // Redirect changing scheme http -> https on same LAN IP
            redirectTarget.set("https://$lanHost:${redirectServer.port}/index.json")
            try {
                repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                fail("Scheme-switching redirect MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese scheme-redirect rejection, got: $formatted", formatted.contains("协议"))
            }

            // Redirect to a public host
            redirectTarget.set("https://93.184.216.34/index.json")
            try {
                repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                fail("Public redirect MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese redirect/public rejection, got: $formatted", formatted.contains("重定向") || formatted.contains("公网") || formatted.contains("协议"))
            }

            // Redirect to loopback
            redirectTarget.set("http://127.0.0.1:8787/index.json")
            try {
                repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                fail("Loopback redirect MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese loopback/redirect rejection, got: $formatted", formatted.contains("重定向") || formatted.contains("回环"))
            }

            // index.json entry.url pointing to another host / another port / another scheme MUST be rejected
            mode.set("bad-index-url")
            for (
                badUrl in listOf(
                    "http://192.168.1.99:${redirectServer.port}/plugin.d3plugin",
                    "http://$lanHost:${redirectServer.port + 1}/plugin.d3plugin",
                    "https://$lanHost:${redirectServer.port}/plugin.d3plugin",
                )
            ) {
                maliciousEntryUrl.set(badUrl)
                try {
                    repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                    fail("Malicious index.json url '$badUrl' MUST be rejected")
                } catch (e: RepositoryVerificationException) {
                    val formatted = PluginInstallGuard.formatInstallError(e, strings)
                    assertTrue(
                        "Expected Chinese host/port/scheme mismatch for $badUrl, got: $formatted",
                        formatted.contains("主机") || formatted.contains("端口") || formatted.contains("协议"),
                    )
                }
            }

            // DNS rebinding: hostname resolves to private LAN 192.168.1.50 during address check,
            // then flips to public 8.8.8.8 on the subsequent request -> MUST be rejected!
            val dnsLookups = AtomicInteger(0)
            val rebindingClient = PluginRepositoryClient(
                httpClient = okHttp,
                devPolicyFactory = { host ->
                    NetworkPolicy.forDevServer(host) {
                        val n = dnsLookups.incrementAndGet()
                        if (n == 1) listOf(InetAddress.getByName(lanHost))
                        else listOf(InetAddress.getByName("8.8.8.8"))
                    }
                },
            )
            maliciousEntryUrl.set("http://rebind.lan:${redirectServer.port}/plugin.d3plugin")
            try {
                rebindingClient.fetchDevPlugin("rebind.lan:${redirectServer.port}")
                fail("DNS rebinding from private IP to public 8.8.8.8 MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese public-IP rejection on DNS rebind, got: $formatted", formatted.contains("公网") || formatted.contains("私有"))
                assertTrue("Expected multiple DNS resolutions across validate+fetch", dnsLookups.get() >= 2)
            }
        } finally {
            redirectServer.shutdown()
        }
    }

    @Test
    fun devReloadSameVersionPermissionsOfflineAndErrorDiagnostics() = runBlocking {
        val lanHost = "192.168.1.50"
        val servedZip = AtomicReference<ByteArray>()
        val servedVersion = AtomicReference("1.0.0")

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val zip = servedZip.get()
                val sha = PluginRepositoryClient.sha256Hex(zip)
                return when (request.path) {
                    "/index.json" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody(
                            makeDevIndexJson(
                                id = "com.example.devplug",
                                name = "Dev Plug",
                                version = servedVersion.get(),
                                sha256 = sha,
                                pluginUrl = "http://$lanHost:${server.port}/plugin.d3plugin",
                            ),
                        )
                    "/plugin.d3plugin" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(zip))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .socketFactory(LocalRedirectSocketFactory(lanHost, server.port))
            .build()
        val devDns: (String) -> List<InetAddress> = { listOf(InetAddress.getByName(it)) }
        val repoClient = PluginRepositoryClient(
            httpClient = okHttp,
            devPolicyFactory = { host -> NetworkPolicy.forDevServer(host, devDns) },
        )
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("dev-reload"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            okHttpClient = okHttp,
            repoClient = repoClient,
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        val devAddr = "$lanHost:${server.port}"

        try {
            // Initial v1.0.0 install with two hosts
            val v1Js = """
                export default {
                  async home(ctx) {
                    await ctx.storage.set('k', 'saved-v1');
                    await ctx.cookies.set('https://a.example.com/', 'sid=cookie-v1');
                    return [{ id: 's1', title: 'V1-' + ctx.config.tag, style: 'poster', items: [] }];
                  },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: '1', kind: 'movie', title: 'One' }; },
                  async playback() { return { url: 'https://a.example.com/v.mp4' }; }
                };
            """.trimIndent()
            servedZip.set(buildZip(makeManifest(hosts = listOf("a.example.com", "b.example.com")), v1Js))

            val pending1 = PluginInstallGuard.prepareDevInstall(pm, repoClient, devAddr)
            assertTrue(pending1.preview.isDevSource)
            assertEquals(devAddr, pending1.devDisplayAddress)
            val res1 = PluginInstallGuard.confirmInstall(pm, pending1)
            val sha1 = res1.entity.sha256
            assertTrue(PluginPackageManager.isDevOrigin(res1.entity.sourceOrigin))

            val inst = pm.createInstance(
                pluginId = "com.example.devplug",
                displayName = "Dev Instance",
                config = mapOf("tag" to JsonPrimitive("mycfg")),
            )
            val shelvesV1 = pm.openSource(inst.instanceId, dnsResolver = devDns).home()
            assertEquals("V1-mycfg", shelvesV1.first().category.title)

            // 1. Same version (1.0.0) with DIFFERENT SHA-256 and same permissions -> reloads without confirmation,
            //    rebuilds runtime, and preserves config + ctx.storage + ctx.cookies!
            val v2Js = """
                export default {
                  async home(ctx) {
                    const saved = await ctx.storage.get('k');
                    const ck = await ctx.cookies.get('https://a.example.com/');
                    return [{ id: 's1', title: 'V2-' + ctx.config.tag + '-' + saved + '-' + ck, style: 'poster', items: [] }];
                  },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: '1', kind: 'movie', title: 'One' }; },
                  async playback() { return { url: 'https://a.example.com/v.mp4' }; }
                };
            """.trimIndent()
            servedZip.set(buildZip(makeManifest(hosts = listOf("a.example.com", "b.example.com")), v2Js))
            val out2 = PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns)
            assertTrue("Same-permission reload must not ask confirmation: $out2", out2 is DevReloadOutcome.Reloaded)
            val reloaded2 = out2 as DevReloadOutcome.Reloaded
            assertNull(reloaded2.loadTechnicalError)
            assertNotEquals("SHA-256 must update on same-version reload", sha1, reloaded2.result.entity.sha256)
            assertEquals("1.0.0", reloaded2.result.entity.version)

            val shelvesV2 = pm.openSource(inst.instanceId, dnsResolver = devDns).home()
            assertEquals("V2-mycfg-saved-v1-sid=cookie-v1", shelvesV2.first().category.title)

            // 2. Shrinking permissions (dropping b.example.com) -> reloads WITHOUT confirmation
            servedZip.set(buildZip(makeManifest(hosts = listOf("a.example.com")), simpleMainJs("V3-Shrunk")))
            val out3 = PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns)
            assertTrue("Shrinking permissions must not require confirmation", out3 is DevReloadOutcome.Reloaded)
            assertEquals("V3-Shrunk", pm.openSource(inst.instanceId, dnsResolver = devDns).home().first().category.title)

            // 3a. Expanding `hosts` -> MUST require re-confirmation (old version stays until confirmed)
            servedZip.set(
                buildZip(
                    makeManifest(hosts = listOf("a.example.com", "new-host.example.com")),
                    simpleMainJs("V4-ExpandedHosts"),
                ),
            )
            val out4 = PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns)
            assertTrue("Expanding hosts must require confirmation", out4 is DevReloadOutcome.NeedsConfirmation)
            val pending4 = (out4 as DevReloadOutcome.NeedsConfirmation).pending
            assertTrue(pending4.confirmExpandedPermissionsOnInstall)
            // Before confirmation, old V3 is still running
            assertEquals("V3-Shrunk", pm.openSource(inst.instanceId, dnsResolver = devDns).home().first().category.title)
            // After confirmation, V4 takes effect and permissions are marked confirmed
            PluginInstallGuard.confirmInstall(pm, pending4)
            assertFalse(store.getPackage("com.example.devplug")!!.needsPermissionReconfirm)
            assertEquals("V4-ExpandedHosts", pm.openSource(inst.instanceId, dnsResolver = devDns).home().first().category.title)

            // 3b. Expanding `userHosts` -> MUST require re-confirmation
            servedZip.set(
                buildZip(
                    makeManifest(hosts = listOf("a.example.com", "new-host.example.com"), userHosts = true),
                    simpleMainJs("V5-UserHosts"),
                ),
            )
            val out5 = PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns)
            assertTrue("Expanding userHosts must require confirmation", out5 is DevReloadOutcome.NeedsConfirmation)
            PluginInstallGuard.confirmInstall(pm, (out5 as DevReloadOutcome.NeedsConfirmation).pending)

            // 3c. Expanding `lan` -> MUST require re-confirmation
            servedZip.set(
                buildZip(
                    makeManifest(hosts = listOf("a.example.com", "new-host.example.com"), userHosts = true, lan = true),
                    simpleMainJs("V6-Lan"),
                ),
            )
            val out6 = PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns)
            assertTrue("Expanding lan must require confirmation", out6 is DevReloadOutcome.NeedsConfirmation)
            PluginInstallGuard.confirmInstall(pm, (out6 as DevReloadOutcome.NeedsConfirmation).pending)
            assertEquals("V6-Lan", pm.openSource(inst.instanceId, dnsResolver = devDns).home().first().category.title)

            // 4. Syntax error in main.js -> throws DevPluginSyntaxException with raw error,
            //    and leaves the previous working V6 intact!
            val brokenSyntaxJs = "export default {\n  async home() {\n    const bad = ;\n  }\n};\n"
            servedZip.set(
                buildZip(
                    makeManifest(hosts = listOf("a.example.com", "new-host.example.com"), userHosts = true, lan = true),
                    brokenSyntaxJs,
                ),
            )
            try {
                PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns)
                fail("Expected DevPluginSyntaxException for JS syntax error")
            } catch (e: DevPluginSyntaxException) {
                val (summary, tech) = PluginInstallGuard.formatDeveloperError(e, strings)
                assertTrue("Expected Chinese summary, got: $summary", summary.any { it.code > 0x7F })
                assertNotNull("Expected raw technical syntax error on Developer screen", tech)
                assertTrue(
                    "Expected SyntaxError details with line number, got: $tech",
                    (tech!!.contains("SyntaxError", ignoreCase = true) || tech.contains("unexpected", ignoreCase = true)) &&
                        tech.contains(":3"),
                )
            }
            // Previous V6 must still be intact and working!
            assertEquals("V6-Lan", pm.openSource(inst.instanceId, dnsResolver = devDns).home().first().category.title)

            // 5. `init(ctx)` throwing -> returns raw technical error on Developer screen,
            //    while non-dev SourceErrorDispatcher stays strictly Chinese
            val initThrowJs = """
                export default {
                  async init(ctx) {
                    throw new Error('boom-during-dev-init');
                  },
                  async home() { return []; },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: '1', kind: 'movie', title: 'One' }; },
                  async playback() { return { url: 'https://a.example.com/v.mp4' }; }
                };
            """.trimIndent()
            servedZip.set(
                buildZip(
                    makeManifest(hosts = listOf("a.example.com", "new-host.example.com"), userHosts = true, lan = true),
                    initThrowJs,
                ),
            )
            val initOut = PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns) as DevReloadOutcome.Reloaded
            assertNotNull("Expected raw init error", initOut.loadTechnicalError)
            assertTrue(
                "Expected verbatim init error message with line number, got: ${initOut.loadTechnicalError}",
                initOut.loadTechnicalError!!.contains("boom-during-dev-init") &&
                    initOut.loadTechnicalError.contains(":3"),
            )
            val nonDevDisp = SourceErrorDispatcher.dispatch(
                error = PluginInternalException("Error: boom-during-dev-init at main.js:3"),
                sourceKind = SourceKind.PLUGIN,
                sourceName = "Dev Plug",
                strings = strings,
            )
            assertFalse(
                "Non-dev screens must NOT show raw English technical error: ${nonDevDisp.message}",
                nonDevDisp.message.contains("boom-during-dev-init"),
            )

            // Restore clean working V7 before testing offline fallback
            servedZip.set(
                buildZip(
                    makeManifest(hosts = listOf("a.example.com", "new-host.example.com"), userHosts = true, lan = true),
                    simpleMainJs("V7-BeforeOffline"),
                ),
            )
            val out7 = PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns) as DevReloadOutcome.Reloaded
            assertNull(out7.loadTechnicalError)
            assertEquals("V7-BeforeOffline", pm.openSource(inst.instanceId, dnsResolver = devDns).home().first().category.title)

            // 6. Computer goes offline -> reload fails cleanly, previously installed V7 remains intact & usable!
            server.shutdown()
            try {
                PluginInstallGuard.reloadDevPlugin(pm, repoClient, devAddr, dnsResolver = devDns)
                fail("Expected error when dev computer is offline")
            } catch (e: Exception) {
                val msg = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese offline error, got: $msg", msg.any { it.code > 0x7F })
            }
            assertEquals("V7-BeforeOffline", pm.openSource(inst.instanceId, dnsResolver = devDns).home().first().category.title)
        } finally {
            runCatching { server.shutdown() }
            pm.shutdownAll()
        }
    }

    @Test
    fun devMutualReplacementRequiresExplicitConfirmationAndExcludedFromUpdates() = runBlocking {
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("dev-replace"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        val pluginId = "dev.depth3d.sample"
        val builtInZip = buildZip(makeManifest(id = pluginId, name = "Sample", version = "1.0.0"), simpleMainJs("BuiltIn"))
        val devZip = buildZip(makeManifest(id = pluginId, name = "Sample", version = "1.0.0"), simpleMainJs("Dev"))
        val repoZip = buildZip(makeManifest(id = pluginId, name = "Sample", version = "2.0.0"), simpleMainJs("Repo"))
        val repoUrl = "https://repo.example.com/index.json"
        val reposByUrl = mapOf(
            repoUrl to PluginRepoEntity(
                url = repoUrl,
                name = "测试官方仓库",
                lastSyncedAt = 1L,
                lastIndexJson = """
                    {
                      "name": "测试官方仓库",
                      "plugins": [
                        {
                          "id": "$pluginId",
                          "name": "Sample",
                          "version": "2.0.0",
                          "description": "Repo version",
                          "url": "https://repo.example.com/sample-2.0.0.d3plugin",
                          "sha256": "${PluginRepositoryClient.sha256Hex(repoZip)}",
                          "minApp": "1.0.0"
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        // 1. Install built-in plugin + instance
        val sync = BuiltInPluginInstaller.syncPackages(pm, store, listOf(builtInZip))
        assertEquals(listOf(pluginId), sync.installed)
        val builtInInst = pm.createInstance(pluginId, "Built-in Inst", emptyMap())
        assertEquals(1, store.listInstancesForPlugin(pluginId).size)

        // 2. Preview dev install over built-in -> requires explicit confirmation + clear copy
        val devBaseUrl = "http://192.168.1.20:8787"
        val devPreview = pm.previewPackage(devZip, sourceUrl = "$devBaseUrl/plugin.d3plugin", devServerUrl = devBaseUrl)
        assertTrue(devPreview.requiresReplacementConfirmation)
        assertTrue(devPreview.isDevSource)
        val warnBuiltInToDev = PluginPermissionSummary.replacementWarningText(
            existingSourceOrigin = devPreview.existingSourceOrigin,
            newSourceOrigin = devPreview.sourceOrigin,
            reposByUrl = reposByUrl,
            strings = strings,
        )
        assertTrue(
            "Expected built-in->dev replacement copy, got: $warnBuiltInToDev",
            warnBuiltInToDev.contains("内置") && warnBuiltInToDev.contains("开发版"),
        )

        val pendingDev = PendingPluginInstall(
            bytes = devZip,
            sourceUrl = "$devBaseUrl/plugin.d3plugin",
            repoUrl = null,
            expectedId = pluginId,
            expectedVersion = "1.0.0",
            expectedSha256 = PluginRepositoryClient.sha256Hex(devZip),
            preview = devPreview,
            devServerUrl = devBaseUrl,
            devDisplayAddress = "192.168.1.20:8787",
        )
        // Without explicit confirmation -> MUST fail with PluginSourceMismatchException and preserve old instance
        try {
            PluginInstallGuard.confirmInstall(pm, pendingDev, userConfirmedReplacement = false)
            fail("Dev install over built-in MUST fail without explicit confirmation")
        } catch (_: PluginSourceMismatchException) {
        }
        assertNotNull(store.getInstance(builtInInst.instanceId))

        // With explicit confirmation -> replaces built-in, wipes old instance, records dev origin
        val devInstalled = PluginInstallGuard.confirmInstall(pm, pendingDev, userConfirmedReplacement = true)
        assertTrue(devInstalled.replacedDifferentSource)
        assertTrue(PluginPackageManager.isDevOrigin(devInstalled.entity.sourceOrigin))
        assertTrue("Old built-in instances must be wiped on replacement", store.listInstancesForPlugin(pluginId).isEmpty())
        val devLabel = PluginPermissionSummary.sourceLabel(devInstalled.entity, reposByUrl, strings)
        assertTrue(
            "Expected dev badge & address in source label, got: $devLabel",
            devLabel.contains("开发版") && devLabel.contains("192.168.1.20:8787"),
        )

        // 3. Dev plugin MUST NOT appear in repository automatic update checks
        val updates = PluginInstallGuard.findAvailableUpdates(
            installed = store.listPackages(),
            repos = reposByUrl.values.toList(),
            repoClient = PluginRepositoryClient(),
        )
        assertFalse("Dev plugin must be excluded from repo auto-updates", updates.containsKey(pluginId))

        // 4. Reverse direction: repo v2.0.0 replacing dev v1.0.0 MUST also require explicit confirmation
        val repoPreview = pm.previewPackage(
            zipBytes = repoZip,
            sourceUrl = "https://repo.example.com/sample-2.0.0.d3plugin",
            repoUrl = repoUrl,
        )
        assertTrue(repoPreview.requiresReplacementConfirmation)
        val warnDevToRepo = PluginPermissionSummary.replacementWarningText(
            existingSourceOrigin = repoPreview.existingSourceOrigin,
            newSourceOrigin = repoPreview.sourceOrigin,
            reposByUrl = reposByUrl,
            strings = strings,
        )
        assertTrue(
            "Expected dev->release replacement copy, got: $warnDevToRepo",
            warnDevToRepo.contains("开发版") && warnDevToRepo.contains("测试官方仓库") && warnDevToRepo.contains("正式版"),
        )
        val pendingRepo = PendingPluginInstall(
            bytes = repoZip,
            sourceUrl = "https://repo.example.com/sample-2.0.0.d3plugin",
            repoUrl = repoUrl,
            expectedId = pluginId,
            expectedVersion = "2.0.0",
            expectedSha256 = PluginRepositoryClient.sha256Hex(repoZip),
            preview = repoPreview,
        )
        try {
            PluginInstallGuard.confirmInstall(pm, pendingRepo, userConfirmedReplacement = false)
            fail("Repo install over dev plugin MUST fail without explicit confirmation")
        } catch (_: PluginSourceMismatchException) {
        }
        val repoInstalled = PluginInstallGuard.confirmInstall(pm, pendingRepo, userConfirmedReplacement = true)
        assertFalse(PluginPackageManager.isDevOrigin(repoInstalled.entity.sourceOrigin))
        assertEquals("2.0.0", repoInstalled.entity.version)

        // 5. Repo -> Dev direction MUST ALSO require explicit replacement confirmation with clear copy!
        val repoInst = pm.createInstance(pluginId, "Repo Inst", emptyMap())
        val devOverRepoPreview = pm.previewPackage(devZip, sourceUrl = "$devBaseUrl/plugin.d3plugin", devServerUrl = devBaseUrl)
        assertTrue(devOverRepoPreview.requiresReplacementConfirmation)
        val warnRepoToDev = PluginPermissionSummary.replacementWarningText(
            existingSourceOrigin = devOverRepoPreview.existingSourceOrigin,
            newSourceOrigin = devOverRepoPreview.sourceOrigin,
            reposByUrl = reposByUrl,
            strings = strings,
        )
        assertTrue(
            "Expected repo->dev replacement copy, got: $warnRepoToDev",
            warnRepoToDev.contains("测试官方仓库") && warnRepoToDev.contains("正式版") && warnRepoToDev.contains("开发版"),
        )
        val pendingDevOverRepo = PendingPluginInstall(
            bytes = devZip,
            sourceUrl = "$devBaseUrl/plugin.d3plugin",
            repoUrl = null,
            expectedId = pluginId,
            expectedVersion = "1.0.0",
            expectedSha256 = PluginRepositoryClient.sha256Hex(devZip),
            preview = devOverRepoPreview,
            devServerUrl = devBaseUrl,
            devDisplayAddress = "192.168.1.20:8787",
        )
        try {
            PluginInstallGuard.confirmInstall(pm, pendingDevOverRepo, userConfirmedReplacement = false)
            fail("Dev install over repo plugin MUST fail without explicit confirmation")
        } catch (_: PluginSourceMismatchException) {
        }
        assertNotNull("Unconfirmed replacement must keep repo instance intact", store.getInstance(repoInst.instanceId))
        val replacedBackToDev = PluginInstallGuard.confirmInstall(pm, pendingDevOverRepo, userConfirmedReplacement = true)
        assertTrue(PluginPackageManager.isDevOrigin(replacedBackToDev.entity.sourceOrigin))
        assertTrue("Confirmed repo->dev replacement must wipe repo instances", store.listInstancesForPlugin(pluginId).isEmpty())
        pm.shutdownAll()
    }

    @Test
    fun localFileAndDirectHttpsInstallCases() = runBlocking {
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("file-install"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        val dropDir = tempFolder.newFolder("drop-plugins")

        // 1. Over 8 MB file rejected with clear Chinese size error
        val hugeFile = File(dropDir, "huge.d3plugin")
        hugeFile.writeBytes(ByteArray(PluginPackageArchive.MAX_PACKAGE_BYTES + 128))
        try {
            PluginInstallGuard.prepareLocalFileInstall(pm, hugeFile)
            fail("File > 8 MB must be rejected")
        } catch (e: PackageInstallException) {
            val msg = PluginInstallGuard.formatInstallError(e, strings)
            assertTrue("Expected Chinese 8 MB size error, got: $msg", msg.contains("8 MB") || msg.contains("大小"))
        }
        // Bounded stream reader also fails fast > 8 MB
        try {
            LocalPluginFileScanner.readBoundedPackageBytes(
                ByteArrayInputStream(ByteArray(PluginPackageArchive.MAX_PACKAGE_BYTES + 16)),
            )
            fail("Stream > 8 MB must be rejected")
        } catch (e: PackageInstallException) {
            assertTrue(e.message!!.contains("8 MB"))
        }

        // 2. Corrupt / non-zip file rejected with clear Chinese error
        val badZipFile = File(dropDir, "corrupt.d3plugin")
        badZipFile.writeText("not-a-valid-zip-file")
        try {
            PluginInstallGuard.prepareLocalFileInstall(pm, badZipFile)
            fail("Corrupt zip must be rejected")
        } catch (e: PackageInstallException) {
            val msg = PluginInstallGuard.formatInstallError(e, strings)
            assertTrue("Expected Chinese invalid package message, got: $msg", msg.any { it.code > 0x7F })
        }

        // 3. Invalid manifest rejected with clear Chinese error
        val badManifestFile = File(dropDir, "bad-manifest.d3plugin")
        badManifestFile.writeBytes(buildZip("""{"api":1,"id":"INVALID ID WITH SPACES"}""", simpleMainJs("Bad")))
        try {
            PluginInstallGuard.prepareLocalFileInstall(pm, badManifestFile)
            fail("Invalid manifest must be rejected")
        } catch (e: ManifestValidationException) {
            val msg = PluginInstallGuard.formatInstallError(e, strings)
            assertTrue("Expected Chinese invalid manifest message, got: $msg", msg.contains("manifest"))
        }

        // 4. minApp higher than current app version rejected right in previewPackage!
        val highMinAppFile = File(dropDir, "future.d3plugin")
        highMinAppFile.writeBytes(
            buildZip(makeManifest(id = "com.example.future", minApp = "9.9.0"), simpleMainJs("Future")),
        )
        try {
            PluginInstallGuard.prepareLocalFileInstall(pm, highMinAppFile)
            fail("minApp > appVersion must be rejected during preview")
        } catch (e: PackageInstallException) {
            val msg = PluginInstallGuard.formatInstallError(e, strings)
            assertTrue("Expected Chinese minApp error, got: $msg", msg.contains("9.9.0") && msg.contains("及以上版本"))
        }

        // 5. Valid `.d3plugin` file installed strictly from previewed bytes -> local-file origin & 「本地文件」 label
        val validFile = File(dropDir, "good-1.0.0.d3plugin")
        val validFileBytes = buildZip(makeManifest(id = "com.example.localgood", name = "Local Good"), simpleMainJs("Local"))
        validFile.writeBytes(validFileBytes)
        val pendingFile = PluginInstallGuard.prepareLocalFileInstall(pm, validFile)
        assertTrue(pendingFile.preview.isFileSource)
        assertEquals(PluginRepositoryClient.sha256Hex(validFileBytes), pendingFile.expectedSha256)
        val installed = PluginInstallGuard.confirmInstall(pm, pendingFile)
        assertEquals(PluginPackageManager.LOCAL_FILE_ORIGIN, installed.entity.sourceOrigin)
        assertEquals("本地文件", PluginPermissionSummary.sourceLabel(installed.entity, emptyMap(), strings))

        // 6. Scanner handles missing/non-dir gracefully, filters non-.d3plugin & hidden files, sorts newest first
        File(dropDir, ".hidden.d3plugin").writeBytes(validFile.readBytes())
        File(dropDir, "readme.txt").writeText("ignore me")
        val newerFile = File(dropDir, "newer.d3plugin").apply {
            writeBytes(validFile.readBytes())
            setLastModified(System.currentTimeMillis() + 60_000L)
        }
        val missingDir = File(tempFolder.root, "does-not-exist-dir")
        val scanned = LocalPluginFileScanner.scanDirectories(
            listOf(
                "应用目录" to dropDir,
                "下载目录" to missingDir,
                "空" to null,
            ),
        )
        assertTrue(
            "Hidden and non-.d3plugin files must be excluded",
            scanned.none { it.fileName.startsWith(".") || !it.fileName.endsWith(".d3plugin") },
        )
        assertEquals("Newest file must appear first", newerFile.name, scanned.first().fileName)
        assertEquals("1.0 MB", LocalPluginFileScanner.formatFileSize(1024L * 1024L))
        assertEquals("1 KB", LocalPluginFileScanner.formatFileSize(1024L))

        // 7. Direct HTTPS URL rejects plain HTTP & private IP
        val strictRepoClient = PluginRepositoryClient()
        try {
            PluginInstallGuard.prepareDirectUrlInstall(pm, strictRepoClient, "http://example.com/a.d3plugin")
            fail("Direct URL install must reject http://")
        } catch (e: PackageInstallException) {
            assertTrue(PluginInstallGuard.formatInstallError(e, strings).contains("https://"))
        }
        try {
            PluginInstallGuard.prepareDirectUrlInstall(pm, strictRepoClient, "https://192.168.1.20/a.d3plugin")
            fail("Direct URL install must reject private LAN IPs")
        } catch (_: Exception) {
        }

        // Verify PhoneInputCopy & PhoneInputErrorHelper support both new modes without crashing
        for (mode in listOf(PhoneInputServer.Mode.PLUGIN_DEV_SERVER, PhoneInputServer.Mode.PLUGIN_DIRECT_URL)) {
            val copy = PhoneInputCopy.from(strings, mode)
            assertTrue(copy.heading.isNotBlank())
            assertTrue(PhoneInputErrorHelper.timeoutError(strings, mode).detail.any { it.code > 0x7F })
            assertTrue(PhoneInputErrorHelper.failedError(strings, mode).detail.any { it.code > 0x7F })
        }

        pm.shutdownAll()
    }

    @Test
    fun udpDevDiscoveryParsingDedupeAndExpiry() = runBlocking {
        // 1. PLUGIN_DEV must NOT be allowed in manifest.json `config[].discover`
        assertNull(UdpDiscoveryProtocol.fromWire(UdpDiscoveryProtocol.PLUGIN_DEV.wireValue))
        assertEquals(18787, UdpDiscoveryProtocol.PLUGIN_DEV.port)
        assertNotEquals(7359, UdpDiscoveryProtocol.PLUGIN_DEV.port)

        // 2. Payload parsing & private-IP-only filtering
        val validJson = """{"name":"我的片库","id":"com.example.demo","version":"1.2.3","port":8787}"""
        val lanSvc = UdpLanServerDiscovery.parseResponsePayload(
            protocol = UdpDiscoveryProtocol.PLUGIN_DEV,
            payload = validJson,
            allowLoopbackForTesting = false,
            senderAddress = "192.168.1.25",
        )
        assertNotNull(lanSvc)
        assertEquals("我的片库", lanSvc!!.serviceName)
        assertEquals("192.168.1.25", lanSvc.address)
        assertEquals(8787, lanSvc.port)
        assertEquals("com.example.demo", lanSvc.txt["pluginId"])
        assertEquals("1.2.3", lanSvc.txt["version"])
        assertEquals("http://192.168.1.25:8787", lanSvc.suggestedUrl())

        // IPv6 ULA sender accepted
        val ulaSvc = UdpLanServerDiscovery.parseResponsePayload(
            protocol = UdpDiscoveryProtocol.PLUGIN_DEV,
            payload = validJson,
            allowLoopbackForTesting = false,
            senderAddress = "fd00::42",
        )
        assertNotNull(ulaSvc)
        assertEquals("http://[fd00::42]:8787", ulaSvc!!.suggestedUrl())

        // Loopback, link-local, public, and missing sender rejected in production mode
        for (badSender in listOf("127.0.0.1", "::1", "169.254.1.2", "fe80::1", "8.8.8.8", null)) {
            assertNull(
                "Sender $badSender must be rejected",
                UdpLanServerDiscovery.parseResponsePayload(
                    protocol = UdpDiscoveryProtocol.PLUGIN_DEV,
                    payload = validJson,
                    allowLoopbackForTesting = false,
                    senderAddress = badSender,
                ),
            )
        }
        // Malformed / missing required fields rejected
        assertNull(
            UdpLanServerDiscovery.parseResponsePayload(
                protocol = UdpDiscoveryProtocol.PLUGIN_DEV,
                payload = """{"name":"NoId","version":"1.0.0","port":8787}""",
                senderAddress = "192.168.1.25",
            ),
        )

        // 3. Real UDP socket round-trip with duplicate replies -> deduplicates to 1 server
        val receivedProbe = AtomicReference<String>()
        val responderReady = CountDownLatch(1)
        val serverSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        serverSocket.soTimeout = 3000
        val responderThread = Thread {
            responderReady.countDown()
            try {
                val buf = ByteArray(1024)
                val pkt = DatagramPacket(buf, buf.size)
                serverSocket.receive(pkt)
                val probe = String(pkt.data, pkt.offset, pkt.length, StandardCharsets.UTF_8)
                receivedProbe.set(probe)
                if (probe.trim() == UdpDiscoveryProtocol.PLUGIN_DEV.probeMessage) {
                    val replyBytes = validJson.toByteArray(StandardCharsets.UTF_8)
                    // Send two identical replies to verify deduplication
                    repeat(2) {
                        serverSocket.send(DatagramPacket(replyBytes, replyBytes.size, pkt.address, pkt.port))
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { serverSocket.close() }
            }
        }.apply { isDaemon = true; start() }

        assertTrue(responderReady.await(2, TimeUnit.SECONDS))
        val found = UdpLanServerDiscovery.discover(
            protocol = UdpDiscoveryProtocol.PLUGIN_DEV,
            timeoutMs = 500L,
            targetPort = serverSocket.localPort,
            targetAddresses = listOf(InetAddress.getByName("127.0.0.1")),
            allowLoopbackForTesting = true,
        )
        responderThread.join(1500)
        assertEquals(UdpDiscoveryProtocol.PLUGIN_DEV.probeMessage, receivedProbe.get()?.trim())
        assertEquals("Duplicate UDP replies must deduplicate to 1 entry", 1, found.size)
        assertEquals("com.example.demo", found.first().txt["pluginId"])

        // 4. Stale eviction via UdpDiscoveryWindow (2 consecutive misses evicts offline server)
        val window = UdpDiscoveryWindow()
        val r1 = window.onRoundComplete(1, listOf(lanSvc))
        assertEquals(1, r1.size)
        val r2 = window.onRoundComplete(2, emptyList())
        assertEquals("Entry must survive 1 missed round", 1, r2.size)
        val r3 = window.onRoundComplete(3, emptyList())
        assertEquals("Entry must be evicted after 2 consecutive missed rounds", 0, r3.size)
    }

    @Test
    fun endToEndDevServerInstallReloadPreservesStorageAndRefreshesHome() = runBlocking {
        val lanHost = "192.168.1.50"
        val currentZip = AtomicReference<ByteArray>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val zip = currentZip.get()
                val sha = PluginRepositoryClient.sha256Hex(zip)
                return when (request.path) {
                    "/index.json" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody(
                            makeDevIndexJson(
                                id = "com.example.e2edev",
                                name = "E2E Dev Plugin",
                                version = "1.0.0",
                                sha256 = sha,
                                pluginUrl = "http://$lanHost:${server.port}/plugin.d3plugin",
                            ),
                        )
                    "/plugin.d3plugin" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(zip))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .socketFactory(LocalRedirectSocketFactory(lanHost, server.port))
            .build()
        val dns: (String) -> List<InetAddress> = { listOf(InetAddress.getByName(it)) }
        val repoClient = PluginRepositoryClient(
            httpClient = okHttp,
            devPolicyFactory = { host -> NetworkPolicy.forDevServer(host, dns) },
        )
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("e2e-dev"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            okHttpClient = okHttp,
            repoClient = repoClient,
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )

        try {
            // Serve initial v1.0.0 plugin
            val v1Js = """
                export default {
                  async home(ctx) {
                    await ctx.storage.set('sessionToken', 'tok-xyz-789');
                    return [{
                      id: 'recent',
                      title: 'V1 Shelf',
                      style: 'poster',
                      items: [{ id: 'm1', kind: 'movie', title: 'Movie V1' }]
                    }];
                  },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: 'm1', kind: 'movie', title: 'Movie V1' }; },
                  async playback() { return { url: 'https://cdn.example.com/m1.mp4' }; }
                };
            """.trimIndent()
            currentZip.set(buildZip(makeManifest(id = "com.example.e2edev", name = "E2E Dev Plugin", version = "1.0.0"), v1Js))

            // 1. Discover via UDP reply payload -> get suggestedUrl()
            val discovered = UdpLanServerDiscovery.parseResponsePayload(
                protocol = UdpDiscoveryProtocol.PLUGIN_DEV,
                payload = """{"name":"E2E Dev Plugin","id":"com.example.e2edev","version":"1.0.0","port":${server.port}}""",
                senderAddress = lanHost,
            )
            assertNotNull(discovered)
            val discoveredUrl = discovered!!.suggestedUrl()
            assertEquals("http://$lanHost:${server.port}", discoveredUrl)

            // 2. Preview + Install from dev server
            val pending = PluginInstallGuard.prepareDevInstall(pm, repoClient, discoveredUrl)
            assertTrue(pending.preview.isDevSource)
            assertFalse(pending.preview.requiresReplacementConfirmation)
            val installRes = PluginInstallGuard.confirmInstall(pm, pending)
            assertEquals("1.0.0", installRes.entity.version)

            // 3. Create instance + reconcile via WallPluginSourceManager + run home()
            pm.createInstance("com.example.e2edev", "My Dev Source", emptyMap())
            val wallManager = WallPluginSourceManager(
                scope = this,
                openSource = { id -> pm.openSource(id, dnsResolver = dns) },
                ioDispatcher = Dispatchers.IO,
            )
            val shelves = ConcurrentHashMap<String, Load<List<Shelf>>>()
            val rec1 = wallManager.reconcile(store.listPackages(), store.listInstances())
            assertEquals(1, rec1.sources.size)
            val src1 = rec1.sources.first() as PluginMediaSource
            wallManager.refreshSource(src1, shelves).join()
            val ready1 = shelves[src1.source.id] as Load.Ready<List<Shelf>>
            assertEquals("V1 Shelf", ready1.value.first().category.title)
            assertEquals("Movie V1", ready1.value.first().items.first().title)

            // 4. Developer edits main.js on computer (SAME version 1.0.0, new SHA-256)
            val v2Js = """
                export default {
                  async home(ctx) {
                    const tok = await ctx.storage.get('sessionToken');
                    return [{
                      id: 'recent',
                      title: 'V2 Shelf (' + tok + ')',
                      style: 'poster',
                      items: [{ id: 'm1', kind: 'movie', title: 'Movie V2' }]
                    }];
                  },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: 'm1', kind: 'movie', title: 'Movie V2' }; },
                  async playback() { return { url: 'https://cdn.example.com/m1.mp4' }; }
                };
            """.trimIndent()
            currentZip.set(buildZip(makeManifest(id = "com.example.e2edev", name = "E2E Dev Plugin", version = "1.0.0"), v2Js))

            // 5. Reload dev plugin -> same version, different SHA -> succeeds without confirmation
            val reloadOut = PluginInstallGuard.reloadDevPlugin(pm, repoClient, discoveredUrl, dnsResolver = dns)
            assertTrue("Expected direct Reloaded without re-confirm, got $reloadOut", reloadOut is DevReloadOutcome.Reloaded)
            assertNull((reloadOut as DevReloadOutcome.Reloaded).loadTechnicalError)

            // 6. WallPluginSourceManager detects SHA-256 change, marks source changed, refreshes home()
            val rec2 = wallManager.reconcile(store.listPackages(), store.listInstances())
            assertTrue(
                "WallPluginSourceManager must mark reloaded dev instance as changed",
                rec2.changedSourceIds.contains(src1.source.id),
            )
            val src2 = rec2.sources.first() as PluginMediaSource
            wallManager.refreshSource(src2, shelves).join()
            val ready2 = shelves[src2.source.id] as Load.Ready<List<Shelf>>
            assertEquals("V2 Shelf (tok-xyz-789)", ready2.value.first().category.title)
            assertEquals("Movie V2", ready2.value.first().items.first().title)
        } finally {
            server.shutdown()
            pm.shutdownAll()
        }
    }

    @Test
    fun confirmInstallUsesPreviewedBytesWithoutReReadingDiskOrNetworkToctou() = runBlocking {
        val store = InMemoryPluginStore()
        val lanHost = "192.168.1.50"
        val servedZip = AtomicReference<ByteArray>()
        val requestCounter = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCounter.incrementAndGet()
                val zip = servedZip.get()
                val sha = PluginRepositoryClient.sha256Hex(zip)
                return when (request.path) {
                    "/index.json" -> MockResponse().setResponseCode(200).setBody(
                        makeDevIndexJson("com.example.toctoudev", "Dev TOCTOU", "1.0.0", sha, "http://$lanHost:${server.port}/plugin.d3plugin"),
                    )
                    "/plugin.d3plugin" -> MockResponse().setResponseCode(200).setBody(Buffer().write(zip))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .socketFactory(LocalRedirectSocketFactory(lanHost, server.port))
            .build()
        val dns: (String) -> List<InetAddress> = { listOf(InetAddress.getByName(it)) }
        val repoClient = PluginRepositoryClient(
            httpClient = okHttp,
            devPolicyFactory = { host -> NetworkPolicy.forDevServer(host, dns) },
        )
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("toctou-install"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            okHttpClient = okHttp,
            repoClient = repoClient,
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        try {
            // 1. Local file TOCTOU: swap file on disk AFTER preview with a higher-permission package
            val safeZip = buildZip(
                makeManifest(id = "com.example.toctoufile", name = "Safe File", hosts = emptyList(), lan = false),
                simpleMainJs("Safe"),
            )
            val evilZip = buildZip(
                makeManifest(id = "com.example.toctoufile", name = "Evil File", hosts = listOf("evil.example.com"), lan = true),
                simpleMainJs("Evil"),
            )
            val pkgFile = File(tempFolder.newFolder("toctou-disk"), "plug.d3plugin")
            pkgFile.writeBytes(safeZip)

            val pendingFile = PluginInstallGuard.prepareLocalFileInstall(pm, pkgFile)
            assertFalse(pendingFile.preview.manifest.permissions.lan)
            // Attacker swaps the file on disk BEFORE user confirms
            pkgFile.writeBytes(evilZip)
            val installedFile = PluginInstallGuard.confirmInstall(pm, pendingFile)
            assertEquals("Installed SHA-256 MUST equal previewed SHA-256", PluginRepositoryClient.sha256Hex(safeZip), installedFile.entity.sha256)
            assertFalse("Installed permissions MUST stay previewed (lan=false)", installedFile.manifest.permissions.lan)
            assertTrue(installedFile.manifest.permissions.hosts.isEmpty())

            // 2. Dev install TOCTOU: swap package on server AFTER preview -> confirmInstall must NOT re-fetch!
            val devSafe = buildZip(
                makeManifest(id = "com.example.toctoudev", name = "Dev Safe", hosts = emptyList(), lan = false),
                simpleMainJs("DevSafe"),
            )
            val devEvil = buildZip(
                makeManifest(id = "com.example.toctoudev", name = "Dev Evil", hosts = listOf("evil.example.com"), lan = true),
                simpleMainJs("DevEvil"),
            )
            servedZip.set(devSafe)
            val pendingDev = PluginInstallGuard.prepareDevInstall(pm, repoClient, "$lanHost:${server.port}")
            val requestsAfterPreview = requestCounter.get()
            servedZip.set(devEvil)
            val installedDev = PluginInstallGuard.confirmInstall(pm, pendingDev)
            assertEquals("confirmInstall must NOT re-fetch from dev server", requestsAfterPreview, requestCounter.get())
            assertEquals(PluginRepositoryClient.sha256Hex(devSafe), installedDev.entity.sha256)
            assertFalse(installedDev.manifest.permissions.lan)

            // 3. Direct URL TOCTOU: mutate pending bytes -> expectedSha256 mismatch rejects install;
            //    keeping previewed bytes installs strictly without touching network!
            val directSafe = buildZip(
                makeManifest(id = "com.example.toctoudirect", name = "Direct Safe", hosts = emptyList(), lan = false),
                simpleMainJs("DirectSafe"),
            )
            val directEvil = buildZip(
                makeManifest(id = "com.example.toctoudirect", name = "Direct Evil", hosts = listOf("evil.example.com"), lan = true),
                simpleMainJs("DirectEvil"),
            )
            val directPreview = pm.previewPackage(directSafe, sourceUrl = "https://cdn.example.com/pkg.d3plugin")
            val directPending = PendingPluginInstall(
                bytes = directSafe,
                sourceUrl = "https://cdn.example.com/pkg.d3plugin",
                repoUrl = null,
                expectedId = directPreview.manifest.id,
                expectedVersion = directPreview.manifest.version,
                expectedSha256 = directPreview.sha256,
                preview = directPreview,
            )
            // Tampering bytes after preview fails SHA-256 check
            try {
                PluginInstallGuard.confirmInstall(pm, directPending.copy(bytes = directEvil))
                fail("Tampered direct-URL bytes after preview must fail SHA-256 check")
            } catch (_: PackageInstallException) {
            }
            val requestsBeforeDirectConfirm = requestCounter.get()
            val installedDirect = PluginInstallGuard.confirmInstall(pm, directPending)
            assertEquals(requestsBeforeDirectConfirm, requestCounter.get())
            assertEquals(PluginRepositoryClient.sha256Hex(directSafe), installedDirect.entity.sha256)
            assertFalse(installedDirect.manifest.permissions.lan)
        } finally {
            server.shutdown()
            pm.shutdownAll()
        }
    }

    @Test
    fun concurrentOpenSourceDuringReloadNeverCachesStaleRuntimeAndCleanupRemovesOrphans() = runBlocking {
        val rootDir = tempFolder.newFolder("concurrent-reload")
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = rootDir,
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        val devUrl = "http://192.168.1.50:8787"
        val pluginId = "com.example.raceplug"

        try {
            val v0 = buildZip(makeManifest(id = pluginId, version = "1.0.0"), simpleMainJs("Gen-0"))
            pm.installFromBytes(v0, sourceUrl = "$devUrl/plugin.d3plugin", devServerUrl = devUrl)
            val inst = pm.createInstance(pluginId, "Race Inst", emptyMap())
            assertEquals("Gen-0", pm.openSource(inst.instanceId).home().first().category.title)

            // Hammer openSource concurrently while performing 8 consecutive same-version dev reloads
            val stopHammer = AtomicBoolean(false)
            val hammerJobs = (1..4).map {
                async(Dispatchers.IO) {
                    while (!stopHammer.get()) {
                        runCatching { pm.openSource(inst.instanceId) }
                    }
                }
            }
            for (gen in 1..8) {
                val nextZip = buildZip(makeManifest(id = pluginId, version = "1.0.0"), simpleMainJs("Gen-$gen"))
                pm.installFromBytes(nextZip, sourceUrl = "$devUrl/plugin.d3plugin", devServerUrl = devUrl)
            }
            stopHammer.set(true)
            hammerJobs.awaitAll()

            // After the final reload, openSource MUST serve Gen-8 (never a stale runtime cached mid-swap)
            val finalTitle = pm.openSource(inst.instanceId).home().first().category.title
            assertEquals("Concurrent openSource during reload must never leave stale runtime cached", "Gen-8", finalTitle)

            // Orphan cleanup: simulate killed process leaving .probe-*, .staging-*, and orphan version dir
            val orphanProbe = File(rootDir, ".probe-dead-proc").apply { mkdirs(); File(this, "x").writeText("1") }
            val pluginDir = File(rootDir, "packages/$pluginId")
            val orphanStaging = File(pluginDir, ".staging-dead-proc").apply { mkdirs(); File(this, "x").writeText("1") }
            val orphanVersion = File(pluginDir, "1.0.0-deadbeef").apply { mkdirs(); File(this, "x").writeText("1") }
            val activeDir = File(store.getPackage(pluginId)!!.packagePath)
            assertTrue(activeDir.isDirectory)

            pm.cleanupOrphanedFiles()
            assertFalse(".probe-* orphan must be removed", orphanProbe.exists())
            assertFalse(".staging-* orphan must be removed", orphanStaging.exists())
            assertFalse("Unreferenced orphan version dir must be removed", orphanVersion.exists())
            assertTrue("Active package directory must remain intact", activeDir.isDirectory)

            // SingleFlightOpRunner: blocks parallel double-click AND blocks re-trigger while cancelling until job truly finishes
            val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val runner = SingleFlightOpRunner()
                val startedLatch = CountDownLatch(1)
                val allowCleanupExit = CountDownLatch(1)
                val secondDoneLatch = CountDownLatch(1)
                val runCount = AtomicInteger(0)

                val firstLaunched = runner.tryLaunch(runnerScope) {
                    runCount.incrementAndGet()
                    startedLatch.countDown()
                    try {
                        delay(10_000L)
                    } finally {
                        withContext(NonCancellable) {
                            withContext(Dispatchers.IO) {
                                allowCleanupExit.await(3, TimeUnit.SECONDS)
                            }
                        }
                    }
                }
                assertTrue(firstLaunched)
                assertTrue(startedLatch.await(2, TimeUnit.SECONDS))
                // Duplicate click while running -> rejected!
                assertFalse("Duplicate click must be rejected while running", runner.tryLaunch(runnerScope) { runCount.incrementAndGet() })

                // Cancel -> while cleanup is still running, re-trigger MUST still be rejected!
                runner.cancel()
                assertTrue(runner.isCancelling)
                assertFalse("Re-trigger during cancellation cleanup must be rejected", runner.tryLaunch(runnerScope) { runCount.incrementAndGet() })

                // Let cancelled task finish cleanup -> now runner becomes idle and accepts a new task!
                allowCleanupExit.countDown()
                var waited = 0
                while (runner.isBusy && waited < 2000) {
                    delay(20)
                    waited += 20
                }
                assertFalse(runner.isBusy)
                val secondLaunched = runner.tryLaunch(runnerScope) {
                    runCount.incrementAndGet()
                    secondDoneLatch.countDown()
                }
                assertTrue("New task must be allowed once cancelled task truly finishes", secondLaunched)
                assertTrue(secondDoneLatch.await(2, TimeUnit.SECONDS))
                assertEquals(2, runCount.get())
            } finally {
                runnerScope.cancel()
            }
        } finally {
            pm.shutdownAll()
        }
    }

    @Test
    fun devToDevAddressSwitchConfirmsAndPreservesInstancesAndLoadExpandsPermissionsImmediately() = runBlocking {
        val hostA = "192.168.1.50"
        val hostB = "192.168.1.60"
        val zipA = AtomicReference<ByteArray>()
        val zipB = AtomicReference<ByteArray>()
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        fun makeDispatcher(host: String, portProvider: () -> Int, zipRef: AtomicReference<ByteArray>) = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val z = zipRef.get()
                val sha = PluginRepositoryClient.sha256Hex(z)
                return when (request.path) {
                    "/index.json" -> MockResponse().setResponseCode(200).setBody(
                        makeDevIndexJson("com.example.dhcpdev", "DHCP Dev", "1.0.0", sha, "http://$host:${portProvider()}/plugin.d3plugin"),
                    )
                    "/plugin.d3plugin" -> MockResponse().setResponseCode(200).setBody(Buffer().write(z))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        serverA.dispatcher = makeDispatcher(hostA, { serverA.port }, zipA)
        serverB.dispatcher = makeDispatcher(hostB, { serverB.port }, zipB)
        serverA.start()
        serverB.start()

        val clientA = PluginRepositoryClient(
            httpClient = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .socketFactory(LocalRedirectSocketFactory(hostA, serverA.port))
                .build(),
            devPolicyFactory = { h -> NetworkPolicy.forDevServer(h) { listOf(InetAddress.getByName(it)) } },
        )
        val clientB = PluginRepositoryClient(
            httpClient = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .socketFactory(LocalRedirectSocketFactory(hostB, serverB.port))
                .build(),
            devPolicyFactory = { h -> NetworkPolicy.forDevServer(h) { listOf(InetAddress.getByName(it)) } },
        )
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("dev-switch"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )
        val dns: (String) -> List<InetAddress> = { listOf(InetAddress.getByName(it)) }

        try {
            val jsA = """
                export default {
                  async home(ctx) {
                    await ctx.storage.set('keep', 'from-A');
                    await ctx.cookies.set('https://a.example.com/', 'ck=valA');
                    return [{ id: 's1', title: 'A-' + ctx.config.note, style: 'poster', items: [] }];
                  },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: '1', kind: 'movie', title: '1' }; },
                  async playback() { return { url: 'https://a.example.com/1.mp4' }; }
                };
            """.trimIndent()
            zipA.set(buildZip(makeManifest(id = "com.example.dhcpdev", name = "DHCP Dev", hosts = listOf("a.example.com")), jsA))

            // Install from hostA + create instance + persist storage & cookie
            val pA = PluginInstallGuard.prepareDevInstall(pm, clientA, "$hostA:${serverA.port}")
            PluginInstallGuard.confirmInstall(pm, pA)
            val inst = pm.createInstance("com.example.dhcpdev", "My Dev", mapOf("note" to JsonPrimitive("cfg1")))
            assertEquals("A-cfg1", pm.openSource(inst.instanceId, dnsResolver = dns).home().first().category.title)

            // Item 5: Load ("加载" via prepareDevInstall, NOT reloadDevPlugin) an expanded-permission update from hostA
            // -> confirmExpandedPermissionsOnInstall MUST be true so confirming once activates it immediately!
            val jsAExpanded = """
                export default {
                  async home(ctx) {
                    const k = await ctx.storage.get('keep');
                    return [{ id: 's1', title: 'A2-' + k, style: 'poster', items: [] }];
                  },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: '1', kind: 'movie', title: '1' }; },
                  async playback() { return { url: 'https://a.example.com/1.mp4' }; }
                };
            """.trimIndent()
            zipA.set(
                buildZip(
                    makeManifest(id = "com.example.dhcpdev", name = "DHCP Dev", hosts = listOf("a.example.com", "b.example.com"), lan = true),
                    jsAExpanded,
                ),
            )
            val pendingExpand = PluginInstallGuard.prepareDevInstall(pm, clientA, "$hostA:${serverA.port}")
            assertTrue("prepareDevInstall must set confirmExpandedPermissionsOnInstall when permissions expand", pendingExpand.confirmExpandedPermissionsOnInstall)
            PluginInstallGuard.confirmInstall(pm, pendingExpand)
            assertFalse("After user confirms on card, plugin must NOT stay stuck in needsPermissionReconfirm", store.getPackage("com.example.dhcpdev")!!.needsPermissionReconfirm)
            assertEquals("A2-from-A", pm.openSource(inst.instanceId, dnsResolver = dns).home().first().category.title)

            // Item 4: Computer DHCP IP changes from hostA to hostB -> explicit confirmation required,
            // clear A->B copy, AND instances (config, ctx.storage, cookies) MUST BE PRESERVED!
            val jsB = """
                export default {
                  async home(ctx) {
                    const k = await ctx.storage.get('keep');
                    const ck = await ctx.cookies.get('https://a.example.com/');
                    return [{ id: 's1', title: 'B-' + ctx.config.note + '-' + k + '-' + ck, style: 'poster', items: [] }];
                  },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: '1', kind: 'movie', title: '1' }; },
                  async playback() { return { url: 'https://a.example.com/1.mp4' }; }
                };
            """.trimIndent()
            zipB.set(
                buildZip(
                    makeManifest(id = "com.example.dhcpdev", name = "DHCP Dev", hosts = listOf("a.example.com", "b.example.com"), lan = true),
                    jsB,
                ),
            )
            val pendingSwitch = PluginInstallGuard.prepareDevInstall(pm, clientB, "$hostB:${serverB.port}")
            assertTrue(pendingSwitch.preview.requiresReplacementConfirmation)
            assertTrue(pendingSwitch.preview.isDevAddressSwitch)
            val switchMsg = PluginPermissionSummary.replacementWarningText(
                existingSourceOrigin = pendingSwitch.preview.existingSourceOrigin,
                newSourceOrigin = pendingSwitch.preview.sourceOrigin,
                reposByUrl = emptyMap(),
                strings = strings,
            )
            assertTrue(
                "Dev->dev address switch warning must name both addresses and note preserved data, got: $switchMsg",
                switchMsg.contains(hostA) && switchMsg.contains(hostB) && switchMsg.contains("保留"),
            )

            // Without explicit confirmation -> throws PluginSourceMismatchException
            try {
                PluginInstallGuard.confirmInstall(pm, pendingSwitch, userConfirmedReplacement = false)
                fail("Dev->dev address switch must require explicit confirmation")
            } catch (_: PluginSourceMismatchException) {
            }

            // With confirmation -> updates dev origin to hostB AND preserves instance config, storage, and cookies!
            val switched = PluginInstallGuard.confirmInstall(pm, pendingSwitch, userConfirmedReplacement = true)
            assertTrue(switched.entity.sourceOrigin.contains(hostB))
            assertEquals("Instances must be preserved on dev->dev address switch", 1, store.listInstancesForPlugin("com.example.dhcpdev").size)
            assertEquals(
                "B-cfg1-from-A-ck=valA",
                pm.openSource(inst.instanceId, dnsResolver = dns).home().first().category.title,
            )
        } finally {
            serverA.shutdown()
            serverB.shutdown()
            pm.shutdownAll()
        }
    }

    @Test
    fun developerDiagnosticsRedactSecretsProbeHasZeroCapabilitiesAndClassifiesErrorsAndRepoEntries() = runBlocking {
        val store = InMemoryPluginStore()
        val pm = PluginPackageManager(
            rootDir = tempFolder.newFolder("dev-redact-probe"),
            store = store,
            secretBox = SoftwareAesSecretBox(),
            appVersion = "1.0.0",
            deviceInfo = DeviceInfo.detect("1.0.0"),
        )

        try {
            // Item 6: Secret redaction in dev init error (configured password, query tokens, Authorization, Cookie)
            val secretManifest = """
                {
                  "api": 1,
                  "id": "com.example.secretdev",
                  "name": "Secret Dev",
                  "version": "1.0.0",
                  "minApp": "1.0.0",
                  "permissions": { "hosts": [], "userHosts": false, "lan": false },
                  "config": [
                    { "key": "password", "type": "password", "label": "密码", "required": true }
                  ],
                  "auth": "none"
                }
            """.trimIndent()
            val leakyInitJs = """
                export default {
                  async init(ctx) {
                    throw new Error('fail pass=' + ctx.config.password + ' url=https://srv.test/api?token=tokSecret99&api_key=keySecret88 Authorization: Bearer jwtSecret77 Cookie: sid=ckSecret66');
                  },
                  async home() { return []; },
                  async browse() { return { items: [], next: null }; },
                  async detail() { return { id: '1', kind: 'movie', title: '1' }; },
                  async playback() { return { url: 'https://srv.test/v.mp4' }; }
                };
            """.trimIndent()
            val devUrl = "http://192.168.1.50:8787"
            pm.installFromBytes(buildZip(secretManifest, leakyInitJs), sourceUrl = "$devUrl/plugin.d3plugin", devServerUrl = devUrl)
            pm.createInstance(
                pluginId = "com.example.secretdev",
                displayName = "Secret Inst",
                config = mapOf("password" to JsonPrimitive("SuperSecretPwd!23")),
            )
            val initErr = pm.verifyInstalledPluginLoad("com.example.secretdev")
            assertNotNull("Expected redacted init error", initErr)
            assertFalse("Password must be redacted: $initErr", initErr!!.contains("SuperSecretPwd!23"))
            assertFalse("URL query token must be redacted: $initErr", initErr.contains("tokSecret99"))
            assertFalse("URL query api_key must be redacted: $initErr", initErr.contains("keySecret88"))
            assertFalse("Authorization bearer must be redacted: $initErr", initErr.contains("jwtSecret77"))
            assertFalse("Cookie value must be redacted: $initErr", initErr.contains("ckSecret66"))
            assertTrue("Line number must remain visible for developer: $initErr", initErr.contains(":3"))

            // Item 8: probeArchiveModuleLoad runs in a ZERO-capability sandbox (no __d3_host, no ctx, no fetch)
            val probeCapJs = """
                if (typeof __d3_host !== 'undefined' || typeof ctx !== 'undefined' || typeof fetch !== 'undefined') {
                  throw new Error('CAPABILITY_LEAKED');
                }
                export default { home(){return []}, browse(){return {items:[]}}, detail(){return {id:'1',kind:'movie',title:'1'}}, playback(){return {url:'https://a.test/1.mp4'}} };
            """.trimIndent()
            assertNull(
                "Zero-capability probe sandbox must expose no host bridge, ctx, or fetch",
                pm.probeArchiveModuleLoad(buildZip(makeManifest(id = "com.example.probe"), probeCapJs)),
            )

            // Item 7: Offline/IP-change hint appended ONLY on connection errors, NEVER on minApp/policy/manifest errors
            val (connSummary, _) = PluginInstallGuard.formatDevReloadError(ConnectException("Connection refused"), strings)
            assertTrue("Connection failure must include offline/IP-change hint: $connSummary", connSummary.contains("更换了 IP") && connSummary.contains("保留当前安装的版本"))
            val (timeoutSummary, _) = PluginInstallGuard.formatDevReloadError(SocketTimeoutException("timed out"), strings)
            assertTrue("Timeout failure must include offline/IP-change hint: $timeoutSummary", timeoutSummary.contains("保留当前安装的版本"))

            val (minAppSummary, _) = PluginInstallGuard.formatDevReloadError(
                PackageInstallException("此插件需要 depth3d 9.9.0 及以上版本（当前版本 1.0.0）"),
                strings,
            )
            assertFalse("minApp error must NOT append offline hint: $minAppSummary", minAppSummary.contains("电脑暂时连不上"))
            val (policySummary, _) = PluginInstallGuard.formatDevReloadError(
                RepositoryVerificationException("开发模式禁止访问回环地址（127.0.0.1），请填写电脑的局域网私有地址。", NetworkPolicyException("loopback")),
                strings,
            )
            assertFalse("Policy rejection must NOT append offline hint: $policySummary", policySummary.contains("电脑暂时连不上"))
            val (manifestSummary, manifestTech) = PluginInstallGuard.formatDevReloadError(
                ManifestValidationException(listOf("id invalid")),
                strings,
            )
            assertFalse("Manifest error must NOT append offline hint: $manifestSummary", manifestSummary.contains("电脑暂时连不上"))
            assertNotNull(manifestTech)

            // Item 11: RepoBrowse classification keeps built-in same-version as INSTALLED, dev/file as REPLACE
            val repoOrigin = PluginPackageManager.canonicalSourceOrigin(repoUrl = "https://repo.example.com/index.json", sourceUrl = null)
            val baseEntity = store.getPackage("com.example.secretdev")!!
            assertEquals(
                RepoEntryAction.INSTALL,
                PluginInstallGuard.classifyRepoEntryAction(null, repoOrigin, "1.0.0"),
            )
            assertEquals(
                "Built-in plugin at same version must show INSTALLED, not REPLACE",
                RepoEntryAction.INSTALLED,
                PluginInstallGuard.classifyRepoEntryAction(
                    baseEntity.copy(
                        sourceOrigin = BuiltInPluginInstaller.sourceOriginFor("dev.depth3d.jellyfin"),
                        sourceUrl = BuiltInPluginInstaller.sourceUrlFor("dev.depth3d.jellyfin"),
                        version = "1.0.0",
                    ),
                    repoOrigin,
                    "1.0.0",
                ),
            )
            assertEquals(
                "Dev plugin at same version must show REPLACE",
                RepoEntryAction.REPLACE,
                PluginInstallGuard.classifyRepoEntryAction(
                    baseEntity.copy(sourceOrigin = "dev:http://192.168.1.50:8787", version = "1.0.0"),
                    repoOrigin,
                    "1.0.0",
                ),
            )
            assertEquals(
                "Local-file plugin at same version must show REPLACE",
                RepoEntryAction.REPLACE,
                PluginInstallGuard.classifyRepoEntryAction(
                    baseEntity.copy(sourceOrigin = PluginPackageManager.LOCAL_FILE_ORIGIN, version = "1.0.0"),
                    repoOrigin,
                    "1.0.0",
                ),
            )
            assertEquals(
                "Same repo newer version must show UPDATE",
                RepoEntryAction.UPDATE,
                PluginInstallGuard.classifyRepoEntryAction(
                    baseEntity.copy(sourceOrigin = repoOrigin, version = "1.0.0"),
                    repoOrigin,
                    "1.1.0",
                ),
            )
        } finally {
            pm.shutdownAll()
        }
    }
}
