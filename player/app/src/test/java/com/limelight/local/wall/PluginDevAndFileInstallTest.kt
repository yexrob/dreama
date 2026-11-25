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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        // Loopback
        val loopbackCases = listOf(
            "127.0.0.1:8787",
            "127.1:8787",
            "0x7f.0.0.1:8787",
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

        // Public / non-RFC1918 (including CGNAT 100.64.0.1 and 172.32.0.1)
        val publicCases = listOf(
            "8.8.8.8:8787",
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

        // Unsupported schemes / empty
        for (raw in listOf("", "   ", "ftp://192.168.1.20:8787", "file:///tmp/plugin.d3plugin")) {
            try {
                validatorClient.normalizeDevServerAddress(raw, dnsTable)
                fail("Expected rejection for $raw")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese reason for '$raw': $formatted", formatted.any { it.code > 0x7F })
            }
        }

        // Pinned host enforcement directly on NetworkPolicy.forDevServer
        val pinnedPolicy = NetworkPolicy.forDevServer("192.168.1.20", dnsTable)
        pinnedPolicy.validateResourceUrl("http://192.168.1.20:8787/plugin.d3plugin")
        try {
            pinnedPolicy.validateResourceUrl("http://192.168.1.21:8787/plugin.d3plugin")
            fail("Different LAN host must be rejected by pinned dev policy")
        } catch (e: NetworkPolicyException) {
            assertTrue("Expected pinned-host Chinese reason, got: ${e.message}", e.message!!.contains("指定的开发服务器主机"))
        }

        // 3. Live MockWebServer HTTP redirect to another host MUST be rejected
        val redirectServer = MockWebServer()
        val redirectTarget = AtomicReference("http://192.168.1.99:8787/index.json")
        redirectServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(302).addHeader("Location", redirectTarget.get())
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
            redirectTarget.set("http://192.168.1.99:8787/index.json")
            try {
                repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                fail("Cross-host LAN redirect MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese redirect rejection, got: $formatted", formatted.contains("重定向"))
            }

            // Redirect to a public host
            redirectTarget.set("https://93.184.216.34/index.json")
            try {
                repoClient.fetchDevPlugin("$lanHost:${redirectServer.port}")
                fail("Public redirect MUST be rejected")
            } catch (e: RepositoryVerificationException) {
                val formatted = PluginInstallGuard.formatInstallError(e, strings)
                assertTrue("Expected Chinese redirect/public rejection, got: $formatted", formatted.contains("重定向") || formatted.contains("公网"))
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
                    initOut.loadTechnicalError!!.contains(":3"),
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

        // 5. Valid `.d3plugin` file installed via installFromFile -> local-file origin & 「本地文件」 label
        val validFile = File(dropDir, "good-1.0.0.d3plugin")
        validFile.writeBytes(
            buildZip(makeManifest(id = "com.example.localgood", name = "Local Good"), simpleMainJs("Local")),
        )
        val pendingFile = PluginInstallGuard.prepareLocalFileInstall(pm, validFile)
        assertTrue(pendingFile.preview.isFileSource)
        assertEquals(validFile, pendingFile.localFile)
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
}
