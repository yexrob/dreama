package com.limelight.local.stream

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HostProtocolClientTest {

    private data class CertIdentity(val cert: X509Certificate, val key: PrivateKey)

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val clientCert: X509Certificate?,
    )

    private data class MockResponse(val code: Int, val body: String)

    private fun generateSelfSigned(cn: String): CertIdentity {
        val provider = BouncyCastleProvider()
        val kpg = KeyPairGenerator.getInstance("RSA", provider)
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val builder = X509v3CertificateBuilder(
            X500Name("CN=$cn"),
            BigInteger(64, SecureRandom()).abs(),
            Date(now - 60_000L),
            Date(now + 86_400_000L),
            X500Name("CN=$cn"),
            SubjectPublicKeyInfo.getInstance(kp.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(kp.private)
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
        return CertIdentity(cert, kp.private)
    }

    private class LocalTlsServer(
        identity: CertIdentity,
        private val handler: (RecordedRequest) -> MockResponse,
    ) : Closeable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val closed = AtomicBoolean(false)
        private val serverSocket: SSLServerSocket
        private val workers = Executors.newCachedThreadPool()
        private val thread: Thread

        val port: Int get() = serverSocket.localPort

        init {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("server", identity.key, CharArray(0), arrayOf(identity.cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, CharArray(0))
            val acceptAnyClient = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, arrayOf<TrustManager>(acceptAnyClient), SecureRandom())
            serverSocket = ctx.serverSocketFactory.createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket
            serverSocket.wantClientAuth = true
            thread = Thread(::loop, "test-host-tls").apply {
                isDaemon = true
                start()
            }
        }

        private fun loop() {
            while (!closed.get()) {
                val sock = try {
                    serverSocket.accept() as SSLSocket
                } catch (_: Exception) {
                    break
                }
                workers.execute {
                    try {
                        handle(sock)
                    } catch (_: Exception) {
                    } finally {
                        try {
                            sock.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        private fun readAsciiLine(input: java.io.InputStream): String? {
            val sb = StringBuilder(64)
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        private fun handle(sock: SSLSocket) {
            sock.soTimeout = 5000
            sock.startHandshake()
            val peerCert = try {
                sock.session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (_: Exception) {
                null
            }
            val input = java.io.BufferedInputStream(sock.inputStream)
            val reqLine = readAsciiLine(input) ?: return
            val parts = reqLine.split(' ')
            val method = parts[0]
            val path = parts[1]
            val headers = LinkedHashMap<String, String>()
            var contentLength = 0
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
            }
            val bodyBytes = ByteArray(contentLength)
            var got = 0
            while (got < contentLength) {
                val n = input.read(bodyBytes, got, contentLength - got)
                if (n <= 0) break
                got += n
            }
            val recorded = RecordedRequest(
                method,
                path,
                headers,
                String(bodyBytes, 0, got, StandardCharsets.UTF_8),
                peerCert,
            )
            requests += recorded
            val resp = handler(recorded)
            val bytes = resp.body.toByteArray(StandardCharsets.UTF_8)
            val head = "HTTP/1.1 ${resp.code} Status\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = sock.outputStream
            out.write(head.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                serverSocket.close()
            } catch (_: Exception) {
            }
            thread.interrupt()
            workers.shutdownNow()
        }
    }

    @Test
    fun getInfoParsesReadyNotReadyUpgradeRequiredAndRejectsIdentityMismatchWithoutPinning() {
        val serverId = generateSelfSigned("depth3d-host-info")
        val pinStore = InMemoryHostCertPinStore()
        var mode = "mismatch"

        LocalTlsServer(serverId) { req ->
            assertEquals("GET", req.method)
            assertEquals("/depth3d/v1/info", req.path)
            when (mode) {
                "mismatch" -> MockResponse(
                    200,
                    """{"v":1,"name":"假冒电脑","id":"spoofed-id","os":"macos","stream":{"ready":true}}""",
                )
                "ready" -> MockResponse(
                    200,
                    """{"v":1,"name":"书房 Mac","id":"host-1","os":"macos","stream":{"ready":true},"media":{"port":8088}}""",
                )
                "not_ready" -> MockResponse(
                    200,
                    """{"v":1,"name":"书房 Mac","id":"host-1","os":"macos","stream":{"ready":false,"reason":"请开启屏幕录制权限"},"media":null}""",
                )
                else -> MockResponse(426, """{"error":"upgrade_required"}""")
            }
        }.use { srv ->
            val client = HttpHostProtocolClient(pinStore)

            // Review #5: mismatched /info id must be rejected AND must NOT save TOFU pin!
            val mismatch = client.getInfo("127.0.0.1", srv.port, "host-1")
            assertTrue("Expected IdentityMismatch, got $mismatch", mismatch is HostInfoResult.IdentityMismatch)
            assertNull(
                "Certificate pin must NOT be saved when /info id mismatches mDNS hostId",
                pinStore.getFingerprint("host-1"),
            )

            // Valid matching id -> accepted and pinned
            mode = "ready"
            val ok = client.getInfo("127.0.0.1", srv.port, "host-1")
            assertTrue(ok is HostInfoResult.Ok)
            val info = (ok as HostInfoResult.Ok).info
            assertEquals(1, info.version)
            assertEquals("书房 Mac", info.name)
            assertEquals("host-1", info.id)
            assertTrue(info.stream.ready)
            assertEquals(8088, info.media?.port)
            assertEquals(HttpHostProtocolClient.sha256Hex(serverId.cert), pinStore.getFingerprint("host-1"))

            mode = "not_ready"
            val notReady = client.getInfo("127.0.0.1", srv.port, "host-1") as HostInfoResult.Ok
            assertFalse(notReady.info.stream.ready)
            assertEquals("请开启屏幕录制权限", notReady.info.stream.reason)

            mode = "upgrade"
            assertEquals(HostInfoResult.UpgradeRequired, client.getInfo("127.0.0.1", srv.port, "host-1"))
        }
    }

    @Test
    fun requestAutoPairFollowsProtocolV1ClientCertRulesAnd503HandlingAndNeverLogsPin() {
        val serverId = generateSelfSigned("depth3d-host-pair")
        val clientId = generateSelfSigned("depth3d-headset-pair-cert")
        val pinStore = InMemoryHostCertPinStore()
        var statusToReturn = 200
        val secretPin = "7391"

        val capturedLogs = CopyOnWriteArrayList<String>()
        val logger = Logger.getLogger("com.limelight")
        val logHandler = object : Handler() {
            override fun publish(record: LogRecord) {
                capturedLogs += record.message.orEmpty()
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(logHandler)

        try {
            LocalTlsServer(serverId) { req ->
                assertEquals("POST", req.method)
                assertEquals("/depth3d/v1/pair", req.path)
                val json = MiniJson.parseObject(req.body)
                assertNotNull(json)
                assertEquals("我的头显", json!!.string("device"))
                assertEquals("dev-abc", json.string("deviceId"))
                assertEquals(secretPin, json.string("pin"))
                when (statusToReturn) {
                    200 -> MockResponse(200, """{"ok":true}""")
                    403 -> MockResponse(403, """{"error":"rejected"}""")
                    408 -> MockResponse(408, """{"error":"timeout"}""")
                    426 -> MockResponse(426, """{"error":"upgrade"}""")
                    else -> MockResponse(503, """{"error":"stream_not_running"}""")
                }
            }.use { srv ->
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )

                // Review #10: First-time pairing MUST NOT send client certificate
                statusToReturn = 200
                assertEquals(
                    AutoPairResult.Approved,
                    client.requestAutoPair(
                        address = "127.0.0.1",
                        port = srv.port,
                        hostId = "host-pair",
                        deviceName = "我的头显",
                        deviceId = "dev-abc",
                        pin = secretPin,
                        presentClientCert = false,
                    ),
                )
                assertNull(
                    "First-time /pair must NOT present client certificate",
                    srv.requests.last().clientCert,
                )

                // Review #10: Subsequent /pair after pairing MUST present pairing client certificate
                assertEquals(
                    AutoPairResult.Approved,
                    client.requestAutoPair(
                        address = "127.0.0.1",
                        port = srv.port,
                        hostId = "host-pair",
                        deviceName = "我的头显",
                        deviceId = "dev-abc",
                        pin = secretPin,
                        presentClientCert = true,
                    ),
                )
                assertNotNull(
                    "Re-pair /pair must present pairing client certificate",
                    srv.requests.last().clientCert,
                )
                assertEquals(clientId.cert.serialNumber, srv.requests.last().clientCert!!.serialNumber)

                // Review #10: 503 means Mac streaming service is not running
                statusToReturn = 503
                assertEquals(
                    AutoPairResult.StreamServiceUnavailable,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin),
                )

                statusToReturn = 403
                assertEquals(
                    AutoPairResult.Rejected,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin),
                )
                statusToReturn = 408
                assertEquals(
                    AutoPairResult.TimedOut,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin),
                )
                statusToReturn = 426
                assertEquals(
                    AutoPairResult.UpgradeRequired,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin),
                )
            }
        } finally {
            logger.removeHandler(logHandler)
        }

        assertTrue(
            "PIN must never be written to logs",
            capturedLogs.none { it.contains(secretPin) },
        )
    }

    @Test
    fun getMediaCredentialsRequiresClientCertAndSendsMutualTlsCert() {
        val serverId = generateSelfSigned("depth3d-host-media")
        val clientId = generateSelfSigned("depth3d-headset-client")
        val pinStore = InMemoryHostCertPinStore()
        var code = 200

        LocalTlsServer(serverId) { req ->
            assertEquals("GET", req.method)
            assertEquals("/depth3d/v1/media", req.path)
            assertEquals("headset-42", req.headers["x-depth3d-device"])
            assertNotNull("Expected mutual TLS client certificate", req.clientCert)
            assertEquals(clientId.cert.serialNumber, req.clientCert!!.serialNumber)
            when (code) {
                200 -> MockResponse(200, """{"webdav":{"port":8088,"user":"depth3d","pass":"s3cr3t"}}""")
                401 -> MockResponse(401, """{"error":"unpaired"}""")
                else -> MockResponse(404, """{"error":"no_media"}""")
            }
        }.use { srv ->
            // Without client identity provider, /media refuses to call the host at all
            val noCertClient = HttpHostProtocolClient(pinStore = pinStore, clientIdentityProvider = { null })
            assertEquals(
                MediaCredsResult.Unauthorized,
                noCertClient.getMediaCredentials("127.0.0.1", srv.port, "host-m", "headset-42"),
            )
            assertEquals(0, srv.requests.size)

            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )

            val ok = client.getMediaCredentials("127.0.0.1", srv.port, "host-m", "headset-42")
            assertTrue(ok is MediaCredsResult.Available)
            val creds = (ok as MediaCredsResult.Available).creds
            assertEquals(8088, creds.port)
            assertEquals("depth3d", creds.user)
            assertEquals("s3cr3t", creds.pass)

            code = 401
            assertEquals(
                MediaCredsResult.Unauthorized,
                client.getMediaCredentials("127.0.0.1", srv.port, "host-m", "headset-42"),
            )

            code = 404
            assertEquals(
                MediaCredsResult.NotConfigured,
                client.getMediaCredentials("127.0.0.1", srv.port, "host-m", "headset-42"),
            )
        }
    }

    @Test
    fun tofuCertPinningTrustsOnlyTheExactApprovedFingerprint() {
        val certA = generateSelfSigned("depth3d-host-A")
        val certB = generateSelfSigned("depth3d-host-B")
        val certC = generateSelfSigned("depth3d-host-C")
        val fpA = HttpHostProtocolClient.sha256Hex(certA.cert)
        val fpB = HttpHostProtocolClient.sha256Hex(certB.cert)
        val fpC = HttpHostProtocolClient.sha256Hex(certC.cert)

        // Full grouped fingerprint check (review #5)
        val formatted = HttpHostProtocolClient.formatFingerprint(fpB)
        assertEquals(fpB.uppercase(), formatted.replace(Regex("\\s+"), ""))
        assertTrue("Expected readable 2-line grouping", formatted.contains("\n"))

        val pinStore = InMemoryHostCertPinStore()
        val client = HttpHostProtocolClient(pinStore)

        // First connection pins certA
        LocalTlsServer(certA) {
            MockResponse(200, """{"v":1,"name":"PC","id":"host-pin","os":"windows","stream":{"ready":true}}""")
        }.use { srvA ->
            assertTrue(client.getInfo("127.0.0.1", srvA.port, "host-pin") is HostInfoResult.Ok)
            assertEquals(fpA, pinStore.getFingerprint("host-pin"))
        }

        // Same host id now presents certB -> rejected with CertificateChanged(fpA, fpB)
        LocalTlsServer(certB) {
            MockResponse(200, """{"v":1,"name":"PC","id":"host-pin","os":"windows","stream":{"ready":true}}""")
        }.use { srvB ->
            val changed = client.getInfo("127.0.0.1", srvB.port, "host-pin")
            assertTrue("Expected CertificateChanged, got $changed", changed is HostInfoResult.CertificateChanged)
            val details = changed as HostInfoResult.CertificateChanged
            assertEquals(fpA, details.expected)
            assertEquals(fpB, details.actual)
        }

        // Review #5: user approved fpB, NOT fpC — if an attacker swaps in certC, it MUST still be rejected!
        LocalTlsServer(certC) {
            MockResponse(200, """{"v":1,"name":"PC","id":"host-pin","os":"windows","stream":{"ready":true}}""")
        }.use { srvC ->
            val stillRejected = client.getInfo(
                address = "127.0.0.1",
                port = srvC.port,
                hostId = "host-pin",
                approvedNewFingerprint = fpB,
            )
            assertTrue("Must reject unapproved certC even when fpB was approved", stillRejected is HostInfoResult.CertificateChanged)
            val details = stillRejected as HostInfoResult.CertificateChanged
            assertEquals(fpA, details.expected)
            assertEquals(fpC, details.actual)
            assertEquals("Original pin must remain unchanged", fpA, pinStore.getFingerprint("host-pin"))
        }

        // Now connect to certB with exact approvedNewFingerprint = fpB -> succeeds & updates pin to fpB
        LocalTlsServer(certB) {
            MockResponse(200, """{"v":1,"name":"PC","id":"host-pin","os":"windows","stream":{"ready":true}}""")
        }.use { srvB ->
            val trusted = client.getInfo(
                address = "127.0.0.1",
                port = srvB.port,
                hostId = "host-pin",
                approvedNewFingerprint = fpB,
            )
            assertTrue(trusted is HostInfoResult.Ok)
            assertEquals(fpB, pinStore.getFingerprint("host-pin"))

            // Subsequent normal call now trusts certB
            assertTrue(client.getInfo("127.0.0.1", srvB.port, "host-pin") is HostInfoResult.Ok)
        }
    }

    @Test
    fun cancelInterruptsInFlightPairCallAndImmediateRetrySucceeds() {
        val serverId = generateSelfSigned("depth3d-host-cancel")
        val pinStore = InMemoryHostCertPinStore()
        val attempt = AtomicInteger(0)
        val firstReqArrived = CountDownLatch(1)
        val releaseFirstReq = CountDownLatch(1)

        LocalTlsServer(serverId) { _ ->
            val n = attempt.incrementAndGet()
            if (n == 1) {
                firstReqArrived.countDown()
                // Simulate Mac waiting on user dialog for a long time
                releaseFirstReq.await(15, TimeUnit.SECONDS)
                MockResponse(408, """{"error":"timeout"}""")
            } else {
                MockResponse(200, """{"ok":true}""")
            }
        }.use { srv ->
            val client = HttpHostProtocolClient(pinStore)
            val cancelToken = CallCancelToken()

            val bg = Executors.newSingleThreadExecutor()
            try {
                val startedAt = System.nanoTime()
                val future = bg.submit<AutoPairResult> {
                    client.requestAutoPair(
                        address = "127.0.0.1",
                        port = srv.port,
                        hostId = "host-cancel",
                        deviceName = "depth3d",
                        deviceId = "dev-1",
                        pin = "1234",
                        cancelToken = cancelToken,
                    )
                }

                assertTrue("First request should reach server", firstReqArrived.await(3, TimeUnit.SECONDS))
                cancelToken.cancel()

                val cancelled = future.get(3, TimeUnit.SECONDS)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                assertEquals(AutoPairResult.Cancelled, cancelled)
                assertTrue("Cancel should abort quickly (< 3000ms), took ${elapsedMs}ms", elapsedMs < 3000L)

                // Immediate retry with a fresh token succeeds cleanly
                val retried = client.requestAutoPair(
                    address = "127.0.0.1",
                    port = srv.port,
                    hostId = "host-cancel",
                    deviceName = "depth3d",
                    deviceId = "dev-1",
                    pin = "5678",
                    cancelToken = CallCancelToken(),
                )
                assertEquals(AutoPairResult.Approved, retried)
            } finally {
                releaseFirstReq.countDown()
                bg.shutdownNow()
            }
        }
    }

    @Test
    fun nvHttpCancelAbortsBlockedPairingSocketAndImmediateRetrySucceeds() {
        val attempt = AtomicInteger(0)
        val firstHit = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val workers = Executors.newCachedThreadPool()
        val running = AtomicBoolean(true)

        val acceptThread = Thread {
            while (running.get()) {
                val sock: Socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                workers.execute {
                    try {
                        sock.soTimeout = 5000
                        val input = sock.inputStream.bufferedReader()
                        var line = input.readLine()
                        while (line != null && line.isNotEmpty()) {
                            line = input.readLine()
                        }
                        if (attempt.incrementAndGet() == 1) {
                            firstHit.countDown()
                            releaseFirst.await(15, TimeUnit.SECONDS)
                        } else {
                            val xml = """<root status_code="200"><paired>1</paired></root>"""
                            val body = xml.toByteArray(StandardCharsets.UTF_8)
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            sock.outputStream.write(resp.toByteArray(StandardCharsets.US_ASCII))
                            sock.outputStream.write(body)
                            sock.outputStream.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            sock.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val bg = Executors.newSingleThreadExecutor()
        try {
            val clientId = generateSelfSigned("depth3d-nvhttp-client")
            val cryptoProvider = object : com.limelight.nvstream.http.LimelightCryptoProvider {
                override fun getClientCertificate() = clientId.cert
                override fun getClientPrivateKey() = clientId.key
                override fun getPemEncodedClientCertificate() = ByteArray(0)
                override fun encodeBase64String(data: ByteArray) =
                    java.util.Base64.getEncoder().encodeToString(data)
            }
            val addr = ComputerDetails.AddressTuple("127.0.0.1", server.localPort)
            val http1 = NvHTTP(addr, 0, "0123456789ABCDEF", null, cryptoProvider)

            val startedAt = System.nanoTime()
            val blocked = bg.submit<Exception?> {
                try {
                    http1.getServerInfo(true)
                    null
                } catch (e: IOException) {
                    e
                }
            }
            assertTrue("NvHTTP request should reach mock server", firstHit.await(3, TimeUnit.SECONDS))
            http1.cancel()

            val ex = blocked.get(3, TimeUnit.SECONDS)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertNotNull("Expected IOException when NvHTTP.cancel() aborts socket", ex)
            assertTrue(http1.isCancelled)
            assertTrue("NvHTTP.cancel() should abort quickly, took ${elapsedMs}ms", elapsedMs < 3000L)

            // Immediate retry on a fresh NvHTTP succeeds
            val http2 = NvHTTP(addr, 0, "0123456789ABCDEF", null, cryptoProvider)
            val xml = http2.getServerInfo(true)
            assertTrue(xml.contains("<paired>1</paired>"))
        } finally {
            running.set(false)
            releaseFirst.countDown()
            try {
                server.close()
            } catch (_: Exception) {
            }
            acceptThread.interrupt()
            workers.shutdownNow()
            bg.shutdownNow()
        }
    }
}
