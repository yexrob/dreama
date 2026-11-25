package com.limelight.local

import android.app.Application
import android.content.Intent
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.limelight.local.media.SourceKind
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.plugin.DeviceInfo
import com.limelight.local.plugin.InMemoryPluginStore
import com.limelight.local.plugin.ManifestParser
import com.limelight.local.plugin.ManifestValidationException
import com.limelight.local.plugin.PluginPackageManager
import com.limelight.local.plugin.SecretBox
import com.limelight.local.plugin.SoftwareAesSecretBox
import com.limelight.local.stream.AndroidStringLookup
import com.limelight.local.stream.HostDiscovery
import com.limelight.local.stream.PhoneFormCopy
import com.limelight.local.stream.PhoneFormField
import com.limelight.local.stream.PhoneFormFieldKind
import com.limelight.local.stream.PhoneFormServer
import com.limelight.local.stream.PhoneSubmitResult
import com.limelight.local.stream.UdpDiscoveryProtocol
import com.limelight.local.stream.UdpLanServerDiscovery
import com.limelight.local.wall.PLUGIN_HOST_VERSION
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Round 2 review fixes & regression suite covering items #6, #7, #10, #14, #18, #20
 * and accepted-behavior regressions #1 and #2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@androidx.annotation.OptIn(UnstableApi::class)
class Round2PlayerDiscoveryPhoneTest {

    private lateinit var app: Application
    private lateinit var tmpDir: File
    private lateinit var server: MockWebServer

    private val testSecretBox: SecretBox = SoftwareAesSecretBox()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        tmpDir = File(System.getProperty("java.io.tmpdir"), "r2-player-${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        tmpDir.deleteRecursively()
    }

    // ---- Item #6: Retry after mid-playback failure resumes from current position ----

    @Test
    fun item06_retryResumePositionUsesCurrentOrLastKnownPositionInsteadOfIntentStart() {
        val intent = PluginPlayerLaunch.createIntent(
            context = app,
            instanceId = "inst-1",
            sourceId = "plugin:inst-1",
            itemId = "movie-9",
            title = "Movie",
            startMs = 0L,
            durationMs = 3_600_000L,
        )
        val parsed = PluginPlayerLaunch.parseIntent(intent)!!
        assertEquals(0L, parsed.startMs)

        // Mid-playback failure at 145_000ms (ExoPlayer still reports 145_000ms)
        val posLive = PluginPlayerLaunch.computeRetryPositionMs(
            currentPlayerPosMs = 145_000L,
            lastKnownPositionMs = 144_800L,
            fallbackStartMs = parsed.startMs,
        )
        assertEquals(145_000L, posLive)
        assertEquals(145_000L, PluginPlayerLaunch.withResumePosition(parsed, posLive).startMs)

        // Mid-playback failure where ExoPlayer reset getCurrentPosition() to 0 -> uses lastKnownPositionMs!
        val posAfterReset = PluginPlayerLaunch.computeRetryPositionMs(
            currentPlayerPosMs = 0L,
            lastKnownPositionMs = 145_000L,
            fallbackStartMs = parsed.startMs,
        )
        assertEquals(145_000L, posAfterReset)
        assertEquals(145_000L, PluginPlayerLaunch.withResumePosition(parsed, posAfterReset).startMs)

        // Before playback ever became ready -> falls back to initial startMs (60_000ms)
        val posBeforeReady = PluginPlayerLaunch.computeRetryPositionMs(
            currentPlayerPosMs = 0L,
            lastKnownPositionMs = 0L,
            fallbackStartMs = 60_000L,
        )
        assertEquals(60_000L, posBeforeReady)
    }

    // ---- Item #7: Proactive refresh has strict cap and stops + errors if refreshed URL stays expired ----

    @Test
    fun item07_proactiveRefreshCapsAttemptsAndFailsImmediatelyWhenRefreshedUrlStillExpired() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("ok")
        }
        val baseUrl = "http://192.168.1.50:${server.port}"
        val nowMs = AtomicLong(1_000_000L)

        // Part A: server keeps returning an already-expired URL -> stops immediately & throws!
        val shortTtlPluginZip = buildPlaybackPluginZip(
            pluginId = "com.example.shortttl",
            playbackJs = """
                return {
                  url: ctx.config.server + '/stream.mp4',
                  expiresAt: ${nowMs.get() - 5_000L}
                };
            """.trimIndent(),
        )
        val pmA = PluginPackageManager(
            rootDir = File(tmpDir, "pmA"),
            store = InMemoryPluginStore(),
            secretBox = testSecretBox,
            appVersion = PLUGIN_HOST_VERSION,
            deviceInfo = DeviceInfo.detect(PLUGIN_HOST_VERSION),
        )
        pmA.installFromBytes(shortTtlPluginZip, sourceUrl = "builtin:shortttl")
        val instA = pmA.createInstance("com.example.shortttl", "ShortTTL", mapOf("server" to JsonPrimitive(baseUrl)))
        val sessionA = PluginPlayerSession(
            openPluginSource = { id -> pmA.openSource(id) { listOf(InetAddress.getByName("192.168.1.50")) } },
            clockMs = { nowMs.get() },
        )
        try {
            sessionA.load(instA.instanceId, "ep-1", 0L, 600_000L)
            assertTrue("Already-expired URL triggers proactive refresh check", sessionA.shouldProactiveRefresh())
            // markPlaybackReady must NOT clear counter/state when already inside expiry window
            sessionA.markPlaybackReady()
            try {
                sessionA.refreshIfExpiredOrFailed(12_000L, httpStatusCode = null)
                fail("Expected SourceUnavailableException when refreshed URL is still expired")
            } catch (_: SourceUnavailableException) {
                // expected
            }
            assertFalse("After still-expired refresh failure, proactive refresh must stop looping", sessionA.shouldProactiveRefresh())
        } finally {
            sessionA.close()
        }

        // Part B: server returns fresh 5-min URLs, clock advances past expiry repeatedly without becoming ready ->
        // proactive refresh hits MAX_CONSECUTIVE_ERROR_REFRESHES (2) cap and throws on 3rd attempt!
        val dynamicExpiryPluginZip = buildPlaybackPluginZip(
            pluginId = "com.example.expiring",
            playbackJs = """
                const count = (await ctx.storage.get('n')) || 0;
                await ctx.storage.set('n', count + 1);
                const expiries = [1300000, 1600000, 1900000, 2200000];
                return {
                  url: ctx.config.server + '/stream-' + count + '.mp4',
                  expiresAt: expiries[Math.min(count, expiries.length - 1)]
                };
            """.trimIndent(),
        )
        val pmB = PluginPackageManager(
            rootDir = File(tmpDir, "pmB"),
            store = InMemoryPluginStore(),
            secretBox = testSecretBox,
            appVersion = PLUGIN_HOST_VERSION,
            deviceInfo = DeviceInfo.detect(PLUGIN_HOST_VERSION),
        )
        pmB.installFromBytes(dynamicExpiryPluginZip, sourceUrl = "builtin:expiring")
        val instB = pmB.createInstance("com.example.expiring", "Expiring", mapOf("server" to JsonPrimitive(baseUrl)))
        val clockB = AtomicLong(1_000_000L)
        val sessionB = PluginPlayerSession(
            openPluginSource = { id -> pmB.openSource(id) { listOf(InetAddress.getByName("192.168.1.50")) } },
            clockMs = { clockB.get() },
        )
        try {
            sessionB.load(instB.instanceId, "ep-1", 0L, 600_000L)
            assertFalse(sessionB.shouldProactiveRefresh())

            // Advance past expiry #1 (1_300_000) -> refresh #1 succeeds (returns expiry 1_600_000)
            clockB.set(1_250_000L)
            assertTrue(sessionB.shouldProactiveRefresh())
            assertNotNull(sessionB.refreshIfExpiredOrFailed(10_000L, null))

            // Advance past expiry #2 (1_600_000) without markPlaybackReady -> refresh #2 succeeds (returns 1_900_000)
            clockB.set(1_550_000L)
            assertNotNull(sessionB.refreshIfExpiredOrFailed(20_000L, null))

            // Advance past expiry #3 (1_900_000) -> exceeds proactive refresh cap -> MUST throw & stop!
            clockB.set(1_850_000L)
            try {
                sessionB.refreshIfExpiredOrFailed(30_000L, null)
                fail("Expected 3rd consecutive proactive refresh to fail at cap")
            } catch (_: SourceUnavailableException) {
                // expected
            }
            assertFalse("Exhausted proactive refresh must stop looping", sessionB.shouldProactiveRefresh())
        } finally {
            sessionB.close()
        }
    }

    // ---- Item #10: UDP 7359 Jellyfin/Emby LAN discovery, private-IP filter, dedupe, cancel close, unified NSD, schema ----

    @Test
    fun item10_udpJellyfinAndEmbyDiscoveryWithFakeLoopbackServerDedupePrivateIpAndCancelClose() = runBlocking {
        // 1. Verify schema accepts udp:jellyfin / udp:emby / mDNS and rejects arbitrary UDP
        val validManifest = ManifestParser.parseAndValidate(
            """
            {
              "api": 1, "id": "dev.dreama.udpcheck", "name": "UDP Check", "version": "1.0.0",
              "permissions": { "hosts": [], "userHosts": true, "lan": true },
              "config": [
                { "key": "s1", "type": "url", "label": "JF", "discover": "udp:jellyfin" },
                { "key": "s2", "type": "url", "label": "Emby", "discover": "udp:emby" },
                { "key": "s3", "type": "url", "label": "mDNS", "discover": "_jellyfin._tcp" }
              ],
              "auth": "none"
            }
            """.trimIndent(),
        )
        assertEquals("udp:jellyfin", validManifest.config[0].discover)
        assertEquals("udp:emby", validManifest.config[1].discover)
        assertEquals("_jellyfin._tcp", validManifest.config[2].discover)
        try {
            ManifestParser.parseAndValidate(
                """
                {
                  "api": 1, "id": "dev.dreama.badudp", "name": "Bad", "version": "1.0.0",
                  "permissions": { "hosts": [], "userHosts": false, "lan": true },
                  "config": [{ "key": "s1", "type": "url", "label": "Bad", "discover": "udp:arbitrary" }],
                  "auth": "none"
                }
                """.trimIndent(),
            )
            fail("Expected ManifestValidationException for arbitrary udp: discover value")
        } catch (_: ManifestValidationException) {
            // expected
        }

        // 2. Verify HostDiscovery delegates to unified NsdServiceBrowser
        val hostDiscovery = HostDiscovery(app)
        assertEquals("_depth3d-host._tcp", hostDiscovery.browser.serviceType)

        // 3. Verify private-IP security filter
        assertNotNull(UdpLanServerDiscovery.parsePrivateServerAddress("http://192.168.1.25:8096"))
        assertNotNull(UdpLanServerDiscovery.parsePrivateServerAddress("http://10.0.0.5:8096"))
        assertNotNull(UdpLanServerDiscovery.parsePrivateServerAddress("https://172.16.4.2:8920"))
        assertNull("Public IP must be rejected", UdpLanServerDiscovery.parsePrivateServerAddress("http://8.8.8.8:8096"))
        assertNull("Loopback must be rejected in production mode", UdpLanServerDiscovery.parsePrivateServerAddress("http://127.0.0.1:8096"))
        assertNull("Link-local must be rejected", UdpLanServerDiscovery.parsePrivateServerAddress("http://169.254.10.1:8096"))
        assertNull("Public hostname must be rejected", UdpLanServerDiscovery.parsePrivateServerAddress("http://evil.example.com:8096"))

        // 4. Spin up a REAL fake UDP server on 127.0.0.1 and test Jellyfin & Emby probes, dedupe, and filtering!
        val receivedProbes = ArrayList<String>()
        val fakeServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val fakePort = fakeServer.localPort
        val serverRunning = AtomicBoolean(true)
        val serverThread = Thread {
            val buf = ByteArray(1024)
            fakeServer.soTimeout = 200
            while (serverRunning.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    fakeServer.receive(pkt)
                } catch (_: Exception) {
                    continue
                }
                val msg = String(pkt.data, pkt.offset, pkt.length, StandardCharsets.UTF_8)
                synchronized(receivedProbes) { receivedProbes += msg }
                val replies = when (msg) {
                    "who is JellyfinServer?" -> listOf(
                        """{"Address":"http://192.168.1.88:8096","Name":"Living Room Jellyfin","Id":"jf-id-1"}""",
                        // Duplicate Id must be deduplicated
                        """{"Address":"http://192.168.1.88:8096","Name":"Living Room Jellyfin Dup","Id":"jf-id-1"}""",
                        // Public IP must be dropped
                        """{"Address":"http://93.184.216.34:8096","Name":"Evil Public","Id":"jf-public"}""",
                        // Loopback must be dropped (production allowLoopbackForTesting = false)
                        """{"Address":"http://127.0.0.1:8096","Name":"Loopback Spoof","Id":"jf-loop"}""",
                        // Second valid private LAN server
                        """{"Address":"http://10.0.0.12:8096","Name":"Office Jellyfin","Id":"jf-id-2"}""",
                    )
                    "who is EmbyServer?" -> listOf(
                        """{"Address":"http://172.16.0.9:8096","Name":"Home Emby","Id":"emby-1"}""",
                    )
                    else -> emptyList()
                }
                for (r in replies) {
                    val bytes = r.toByteArray(StandardCharsets.UTF_8)
                    fakeServer.send(DatagramPacket(bytes, bytes.size, pkt.address, pkt.port))
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val jfFound = UdpLanServerDiscovery.discover(
                protocol = UdpDiscoveryProtocol.JELLYFIN,
                timeoutMs = 500L,
                targetAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                targetPort = fakePort,
                allowLoopbackForTesting = false,
            )
            assertEquals("Must find exactly 2 valid deduplicated private LAN Jellyfin servers", 2, jfFound.size)
            assertEquals(listOf("Living Room Jellyfin", "Office Jellyfin"), jfFound.map { it.serviceName })
            assertEquals("http://192.168.1.88:8096", jfFound[0].suggestedUrl())
            assertEquals("http://10.0.0.12:8096", jfFound[1].suggestedUrl())

            val embyFound = UdpLanServerDiscovery.discover(
                protocol = UdpDiscoveryProtocol.EMBY,
                timeoutMs = 500L,
                targetAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                targetPort = fakePort,
                allowLoopbackForTesting = false,
            )
            assertEquals(1, embyFound.size)
            assertEquals("Home Emby", embyFound[0].serviceName)
            assertEquals("http://172.16.0.9:8096", embyFound[0].suggestedUrl())
            assertTrue(receivedProbes.contains("who is JellyfinServer?"))
            assertTrue(receivedProbes.contains("who is EmbyServer?"))

            // 5. Cancellation closes socket immediately without waiting for 5s timeout!
            val tCancel0 = System.currentTimeMillis()
            val cancelJob = launch {
                UdpLanServerDiscovery.discover(
                    protocol = UdpDiscoveryProtocol.JELLYFIN,
                    timeoutMs = 5_000L,
                    targetAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                    targetPort = fakePort + 1, // silent port
                )
            }
            delay(80L)
            cancelJob.cancelAndJoin()
            val cancelElapsed = System.currentTimeMillis() - tCancel0
            assertTrue("Cancelling discovery must close socket immediately (took ${cancelElapsed}ms)", cancelElapsed < 1_000L)
        } finally {
            serverRunning.set(false)
            fakeServer.close()
            serverThread.join(1_000L)
        }
    }

    // ---- Item #14: Wrong verification code preserves non-password fields, never passwords ----

    @Test
    fun item14_phoneFormWrongCodePreservesNonPasswordFieldsWithoutEchoingPassword() {
        val copy = PhoneFormCopy.from(app, "Jellyfin")
        val fields = listOf(
            PhoneFormField("server", PhoneFormFieldKind.URL, "Server", required = true),
            PhoneFormField("username", PhoneFormFieldKind.TEXT, "Username", required = true),
            PhoneFormField("password", PhoneFormFieldKind.PASSWORD, "Password", required = true),
        )
        val srv = PhoneFormServer(
            copy = copy,
            fields = fields,
            displayHost = "127.0.0.1",
            code = "4242",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onSubmitted = { PhoneSubmitResult.Accepted },
        ).start()
        try {
            val formBody = listOf(
                "code=9999",
                "f_server=" + URLEncoder.encode("http://192.168.1.77:8096", "UTF-8"),
                "f_username=" + URLEncoder.encode("alice_lan", "UTF-8"),
                "f_password=" + URLEncoder.encode("SuperSecretPw!", "UTF-8"),
            ).joinToString("&")
            val (status, html) = httpPost("${srv.shortUrl}/submit", formBody)
            assertEquals(403, status)
            assertTrue("URL field must be preserved after wrong code", html.contains("http://192.168.1.77:8096"))
            assertTrue("Username field must be preserved after wrong code", html.contains("alice_lan"))
            assertFalse("Password must NEVER be echoed back in HTML", html.contains("SuperSecretPw!"))
        } finally {
            srv.close()
        }
    }

    // ---- Item #18: Phone submit writes & flushes 200 OK HTML BEFORE onAcceptedFlushed closes server ----

    @Test
    fun item18_phoneFormFlushesFull200HtmlBeforeOnAcceptedFlushedClosesServer() {
        val copy = PhoneFormCopy.from(app, "Jellyfin")
        val fields = listOf(
            PhoneFormField("server", PhoneFormFieldKind.URL, "Server", required = true),
            PhoneFormField("username", PhoneFormFieldKind.TEXT, "Username", required = true),
        )
        val serverRef = AtomicReference<PhoneFormServer>()
        val flushedCalled = CountDownLatch(1)
        val srv = PhoneFormServer(
            copy = copy,
            fields = fields,
            displayHost = "127.0.0.1",
            code = "1357",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onAcceptedFlushed = {
                // Simulate immediate screen disposal closing server as soon as flush callback fires
                serverRef.get()?.close()
                flushedCalled.countDown()
            },
            onSubmitted = { PhoneSubmitResult.Accepted },
        ).start()
        serverRef.set(srv)
        try {
            val formBody = "code=1357&f_server=" + URLEncoder.encode("http://192.168.1.10:8096", "UTF-8") + "&f_username=bob"
            val (status, html) = httpPost("${srv.shortUrl}/submit", formBody)
            assertEquals("Client must receive full 200 OK even when onAcceptedFlushed closes server", 200, status)
            assertTrue("Client must receive complete done HTML body", html.contains(copy.doneTitle))
            assertTrue("onAcceptedFlushed must have been invoked", flushedCalled.await(2, TimeUnit.SECONDS))
            assertTrue("Server should be closed by onAcceptedFlushed", srv.isClosed)
        } finally {
            srv.close()
        }
    }

    // ---- Item #20: Cast attachment guard wired at real LocalPlayerXR entry point ----

    @Test
    fun item20_localPlayerCastAttachmentGuardRejectsPluginAndAllowsLocal() {
        try {
            LocalPlayerXR.guardCastAttachment(SourceKind.PLUGIN, 101L)
            fail("Plugin source with active castId must throw UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        LocalPlayerXR.guardCastAttachment(SourceKind.LOCAL, 101L)
        LocalPlayerXR.guardCastAttachment(SourceKind.WEBDAV, 101L)
        LocalPlayerXR.guardCastAttachment(SourceKind.PLUGIN, 0L)
    }

    // ---- Accepted Behavior Regression #1: Unified Chinese playback failure copy for ALL sources ----

    @Test
    fun regression01_nonPluginAndPluginPlaybackErrorsProduceUnifiedChineseReasonWithoutErrorCodes() {
        val strings = AndroidStringLookup(app)
        val netErr = PlaybackException("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", IOException("boom"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val fmtErr = PlaybackException("ERROR_CODE_DECODING_FAILED", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        val drmErr = PlaybackException("ERROR_CODE_DRM_SCHEME_UNSUPPORTED", null, PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED)

        for (kind in listOf(SourceKind.LOCAL, SourceKind.WEBDAV, SourceKind.PLUGIN)) {
            val uiNet = PluginPlayerSession.formatPlayerError(netErr, kind, "Test", strings)
            assertTrue("Must show Chinese error for $kind", uiNet.reason.any { it.code > 0x7F })
            assertFalse("Must never leak ERROR_CODE_ for $kind", uiNet.reason.contains("ERROR_CODE"))
            assertTrue(uiNet.canRetry)

            val uiFmt = PluginPlayerSession.formatPlayerError(fmtErr, kind, "Test", strings)
            assertTrue(uiFmt.reason.any { it.code > 0x7F })
            assertFalse(uiFmt.reason.contains("ERROR_CODE"))
            assertTrue(uiFmt.canRetry)

            val uiDrm = PluginPlayerSession.formatPlayerError(drmErr, kind, "Test", strings)
            assertTrue(uiDrm.reason.any { it.code > 0x7F })
            assertFalse(uiDrm.reason.contains("ERROR_CODE"))
            assertFalse("DRM unsupported error must disable retry", uiDrm.canRetry)
        }
    }

    // ---- Accepted Behavior Regression #2: Shared DefaultTrackSelector prefers Chinese (zh) subtitles ----

    @Test
    fun regression02_localPlayerTrackSelectorPrefersChineseSubtitlesForAllSources() {
        val selector = LocalPlayerXR.createTrackSelector(app)
        val params = selector.parameters
        assertEquals(listOf("zh"), params.preferredTextLanguages)
        assertTrue(params.selectUndeterminedTextLanguage)
    }

    // ---- Helpers ----

    private fun httpPost(url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return status to text
    }

    private fun buildPlaybackPluginZip(pluginId: String, playbackJs: String): ByteArray {
        val manifest = """
            {
              "api": 1,
              "id": "$pluginId",
              "name": "Playback Test",
              "version": "1.0.0",
              "permissions": { "hosts": [], "userHosts": true, "lan": true },
              "config": [{ "key": "server", "type": "url", "label": "Server", "required": true }],
              "auth": "none"
            }
        """.trimIndent()
        val mainJs = """
            export default {
              async home() { return []; },
              async browse() { return { items: [], nextPage: null }; },
              async detail(ctx, { itemId }) { return { id: itemId, kind: 'movie', title: itemId }; },
              async playback(ctx, { itemId }) { $playbackJs }
            };
        """.trimIndent()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("main.js"))
            zos.write(mainJs.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
