package com.limelight.local.stream

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.crypto.engines.AESLightEngine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Base64
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

    private data class CertIdentity(val cert: X509Certificate, val key: PrivateKey) {
        val pemBytes: ByteArray
            get() {
                val b64 = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII))
                    .encodeToString(cert.encoded)
                return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
                    .toByteArray(StandardCharsets.US_ASCII)
            }
    }

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

    private fun cryptoProviderFor(id: CertIdentity): LimelightCryptoProvider = object : LimelightCryptoProvider {
        override fun getClientCertificate(): X509Certificate = id.cert
        override fun getClientPrivateKey(): PrivateKey = id.key
        override fun getPemEncodedClientCertificate(): ByteArray = id.pemBytes
        override fun encodeBase64String(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    }

    private class LocalTlsServer(
        identity: CertIdentity,
        private val handler: (RecordedRequest) -> MockResponse,
    ) : Closeable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val acceptedConnections = AtomicInteger(0)
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
                acceptedConnections.incrementAndGet()
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

        private fun readAsciiLine(input: InputStream): String? {
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
            val input = BufferedInputStream(sock.inputStream)
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

    /**
     * Stateful Gen-7 GameStream mock server implementing the real PairingManager cryptographic
     * handshake and retaining an "in-progress pairing" lock across socket disconnects until
     * `/unpair` explicitly clears it (S3).
     */
    private class StatefulMockGameStreamServer(
        private val serverId: CertIdentity,
        private val expectedPin: String,
        private val blockFirstGetServerCert: Boolean,
        private val uniqueId: String = "gs-server-uuid-1",
        private val httpPairStatus: Int = 0,
        private val httpsPairStatus: Int = 0,
    ) : Closeable {
        val pairingInProgress = AtomicBoolean(false)
        val unpairCount = AtomicInteger(0)
        val busyRejectionCount = AtomicInteger(0)
        val firstPairBlocked = CountDownLatch(1)

        private val getCertAttempts = AtomicInteger(0)
        private val releaseFirstBlock = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private val httpSocket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val httpsSocket: SSLServerSocket
        private val workers = Executors.newCachedThreadPool()
        private val httpThread: Thread
        private val httpsThread: Thread

        // Active pairing session cryptographic state
        @Volatile private var aesKey: ByteArray? = null
        @Volatile private var clientCert: X509Certificate? = null
        @Volatile private var serverChallenge: ByteArray? = null
        @Volatile private var serverSecret: ByteArray? = null

        val httpPort: Int get() = httpSocket.localPort
        val httpsPort: Int get() = httpsSocket.localPort

        init {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("gs", serverId.key, CharArray(0), arrayOf(serverId.cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, CharArray(0))
            val acceptAny = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, arrayOf<TrustManager>(acceptAny), SecureRandom())
            httpsSocket = ctx.serverSocketFactory.createServerSocket(0, 16, InetAddress.getLoopbackAddress()) as SSLServerSocket
            httpsSocket.wantClientAuth = true

            httpThread = Thread({ acceptLoop(httpSocket, false) }, "mock-gs-http").apply {
                isDaemon = true
                start()
            }
            httpsThread = Thread({ acceptLoop(httpsSocket, true) }, "mock-gs-https").apply {
                isDaemon = true
                start()
            }
        }

        private fun acceptLoop(ss: ServerSocket, isHttps: Boolean) {
            while (!closed.get()) {
                val sock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                workers.execute {
                    try {
                        if (isHttps) (sock as SSLSocket).startHandshake()
                        handleSocket(sock, isHttps)
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

        private fun handleSocket(sock: Socket, isHttps: Boolean) {
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.inputStream)
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            val reqLine = sb.toString()
            // Drain headers
            while (true) {
                val line = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                    if (b != '\r'.code) line.append(b.toChar())
                }
                if (line.isEmpty()) break
            }

            val rawTarget = reqLine.split(' ').getOrNull(1) ?: "/"
            val path = rawTarget.substringBefore('?')
            val query = parseQuery(rawTarget.substringAfter('?', ""))
            val xml = when (path) {
                "/serverinfo" -> {
                    val ps = if (isHttps) httpsPairStatus else httpPairStatus
                    """<root status_code="200"><appversion>7.1.431.-1</appversion><uniqueid>$uniqueId</uniqueid><PairStatus>$ps</PairStatus><HttpsPort>$httpsPort</HttpsPort></root>"""
                }
                "/unpair" -> {
                    unpairCount.incrementAndGet()
                    pairingInProgress.set(false)
                    releaseFirstBlock.countDown()
                    """<root status_code="200"/>"""
                }
                "/pair" -> handlePair(query, isHttps)
                else -> """<root status_code="404"/>"""
            }
            val body = xml.toByteArray(StandardCharsets.UTF_8)
            val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            sock.outputStream.write(resp.toByteArray(StandardCharsets.US_ASCII))
            sock.outputStream.write(body)
            sock.outputStream.flush()
        }

        private fun handlePair(q: Map<String, String>, isHttps: Boolean): String {
            val phrase = q["phrase"]
            if (phrase == "getservercert") {
                if (!pairingInProgress.compareAndSet(false, true)) {
                    // Exact real GFE/Sunshine busy response: paired=1 WITHOUT <plaincert>
                    // -> PairingManager.pair() returns PairState.ALREADY_IN_PROGRESS!
                    busyRejectionCount.incrementAndGet()
                    return """<root status_code="200"><paired>1</paired></root>"""
                }
                val attempt = getCertAttempts.incrementAndGet()
                if (blockFirstGetServerCert && attempt == 1) {
                    firstPairBlocked.countDown()
                    // Keep pairingInProgress=true even if client disconnects; ONLY /unpair clears it!
                    releaseFirstBlock.await(15, TimeUnit.SECONDS)
                    return """<root status_code="200"><paired>0</paired></root>"""
                }
                val salt = hexToBytes(q["salt"]!!)
                val clientPem = hexToBytes(q["clientcert"]!!)
                clientCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(clientPem)) as X509Certificate
                val salted = ByteArray(salt.size + expectedPin.length)
                System.arraycopy(salt, 0, salted, 0, salt.size)
                System.arraycopy(expectedPin.toByteArray(StandardCharsets.UTF_8), 0, salted, salt.size, expectedPin.length)
                aesKey = Arrays.copyOf(sha256(salted), 16)
                return """<root status_code="200"><paired>1</paired><plaincert>${bytesToHex(serverId.pemBytes)}</plaincert></root>"""
            }
            q["clientchallenge"]?.let { encHex ->
                val key = aesKey!!
                val clientChallenge = aesBlock(false, hexToBytes(encHex), key).copyOf(16)
                val sSecret = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val sChallenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
                serverSecret = sSecret
                serverChallenge = sChallenge
                val serverRespHash = sha256(concat(concat(clientChallenge, serverId.cert.signature), sSecret))
                val plain = concat(serverRespHash, sChallenge)
                val enc = aesBlock(true, plain, key)
                return """<root status_code="200"><paired>1</paired><challengeresponse>${bytesToHex(enc)}</challengeresponse></root>"""
            }
            q["serverchallengeresp"]?.let {
                val sSecret = serverSecret!!
                val sig = Signature.getInstance("SHA256withRSA").apply {
                    initSign(serverId.key)
                    update(sSecret)
                }.sign()
                val secretWithSig = concat(sSecret, sig)
                return """<root status_code="200"><paired>1</paired><pairingsecret>${bytesToHex(secretWithSig)}</pairingsecret></root>"""
            }
            q["clientpairingsecret"]?.let {
                return """<root status_code="200"><paired>1</paired></root>"""
            }
            if (phrase == "pairchallenge" && isHttps) {
                pairingInProgress.set(false)
                return """<root status_code="200"><paired>1</paired></root>"""
            }
            return """<root status_code="200"><paired>0</paired></root>"""
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseFirstBlock.countDown()
            try { httpSocket.close() } catch (_: Exception) {}
            try { httpsSocket.close() } catch (_: Exception) {}
            httpThread.interrupt()
            httpsThread.interrupt()
            workers.shutdownNow()
        }

        private fun parseQuery(q: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (part in q.split('&')) {
                val eq = part.indexOf('=')
                if (eq > 0) out[part.substring(0, eq)] = part.substring(eq + 1)
            }
            return out
        }

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun concat(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size + b.size).also {
            System.arraycopy(a, 0, it, 0, a.size)
            System.arraycopy(b, 0, it, a.size, b.size)
        }

        private fun aesBlock(encrypt: Boolean, input: ByteArray, key: ByteArray): ByteArray {
            val engine = AESLightEngine()
            engine.init(encrypt, KeyParameter(key))
            val blockSize = engine.blockSize
            val rounded = (input.size + (blockSize - 1)) and (blockSize - 1).inv()
            val inp = input.copyOf(rounded)
            val out = ByteArray(rounded)
            var off = 0
            while (off < rounded) {
                engine.processBlock(inp, off, out, off)
                off += blockSize
            }
            return out
        }

        private fun hexToBytes(s: String): ByteArray {
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
            }
            return out
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val chars = "0123456789ABCDEF"
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(chars[v ushr 4]).append(chars[v and 0x0F])
            }
            return sb.toString()
        }
    }

    @Test
    fun b3_getInfoObservesFingerprintInMemoryOnlyAndNeverPersistsPinOnDiscovery() {
        val serverId = generateSelfSigned("depth3d-host-info")
        val pinStore = InMemoryHostCertPinStore()
        var mode = "mismatch"

        LocalTlsServer(serverId) { req ->
            assertEquals("GET", req.method)
            assertEquals("/depth3d/v1/info", req.path)
            assertNull("Discovery /info must never present a client cert", req.clientCert)
            when (mode) {
                "mismatch" -> MockResponse(
                    200,
                    """{"v":1,"name":"Spoofed PC","id":"spoofed-id","os":"macos","stream":{"ready":true}}""",
                )
                "ready" -> MockResponse(
                    200,
                    """{"v":1,"name":"Study Mac","id":"host-1","os":"macos","stream":{"ready":true},"media":{"port":8088}}""",
                )
                "not_ready" -> MockResponse(
                    200,
                    """{"v":1,"name":"Study Mac","id":"host-1","os":"macos","stream":{"ready":false,"reason":"请开启屏幕录制权限"},"media":null}""",
                )
                else -> MockResponse(426, """{"error":"upgrade_required"}""")
            }
        }.use { srv ->
            val client = HttpHostProtocolClient(pinStore)

            val mismatch = client.getInfo("127.0.0.1", srv.port, "host-1")
            assertTrue("Expected IdentityMismatch, got $mismatch", mismatch is HostInfoResult.IdentityMismatch)
            assertNull(pinStore.getFingerprint("host-1"))

            // B3: Even after valid /info 200 OK with matching id, pinStore MUST remain empty!
            mode = "ready"
            val ok = client.getInfo("127.0.0.1", srv.port, "host-1")
            assertTrue(ok is HostInfoResult.Ok)
            val okRes = ok as HostInfoResult.Ok
            assertEquals(HttpHostProtocolClient.sha256Hex(serverId.cert), okRes.fingerprint)
            assertEquals("Study Mac", okRes.info.name)
            assertEquals(8088, okRes.info.media?.port)
            assertNull(
                "B3: Discovery /info must keep fingerprint in memory only and NEVER persist to pinStore",
                pinStore.getFingerprint("host-1"),
            )

            mode = "not_ready"
            val notReady = client.getInfo("127.0.0.1", srv.port, "host-1") as HostInfoResult.Ok
            assertFalse(notReady.info.stream.ready)
            assertEquals("请开启屏幕录制权限", notReady.info.stream.reason)

            mode = "upgrade"
            assertEquals(HostInfoResult.UpgradeRequired, client.getInfo("127.0.0.1", srv.port, "host-1"))
        }
    }

    @Test
    fun b2_autoPairOrchestratorNeverCallsPairOrSendsPinWhenInfoFailsOrMismatches() = runBlocking {
        val serverId = generateSelfSigned("depth3d-host-b2")
        val pinStore = InMemoryHostCertPinStore()
        var infoStatus = 500
        var infoId = "host-b2"
        val pairCalls = AtomicInteger(0)

        LocalTlsServer(serverId) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> {
                    if (infoStatus != 200) {
                        MockResponse(infoStatus, """{"error":"internal"}""")
                    } else {
                        MockResponse(200, """{"v":1,"name":"Mac","id":"$infoId","os":"macos","stream":{"ready":true}}""")
                    }
                }
                "/depth3d/v1/pair" -> {
                    pairCalls.incrementAndGet()
                    MockResponse(200, """{"ok":true}""")
                }
                else -> MockResponse(404, "{}")
            }
        }.use { srv ->
            val client = HttpHostProtocolClient(pinStore)

            // Case 1: /info returns HTTP 500 -> MUST abort and NEVER send /pair or PIN!
            infoStatus = 500
            val failedOutcome = HostAutoPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-b2",
                deviceName = "depth3d",
                deviceId = "dev-1",
                pin = "9999",
                computerUuid = "uuid-b2",
                alreadyPairedGsCert = null,
                approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(serverId.cert) },
            )
            assertTrue("Expected InfoFailed, got $failedOutcome", failedOutcome is HostAutoPairOutcome.InfoFailed)
            assertEquals("B2: /pair must NOT be called when /info fails", 0, pairCalls.get())
            assertNull(pinStore.getFingerprint("host-b2"))

            // Case 2: /info returns mismatched host id -> MUST abort and NEVER send /pair or PIN!
            infoStatus = 200
            infoId = "attacker-host-id"
            val mismatchOutcome = HostAutoPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-b2",
                deviceName = "depth3d",
                deviceId = "dev-1",
                pin = "9999",
                computerUuid = "uuid-b2",
                alreadyPairedGsCert = null,
                approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(serverId.cert) },
            )
            assertTrue("Expected InfoIdentityMismatch", mismatchOutcome is HostAutoPairOutcome.InfoIdentityMismatch)
            assertEquals("B2: /pair must NOT be called when /info id mismatches", 0, pairCalls.get())
            assertNull(pinStore.getFingerprint("host-b2"))
        }
    }

    @Test
    fun b1_mediaRefusesUnpinnedOrUnpairedOrImposterHostsWithZeroClientCertOrDeviceHeaderLeak() {
        val realHostCert = generateSelfSigned("depth3d-real-host")
        val imposterCert = generateSelfSigned("depth3d-imposter-host")
        val gsCert = generateSelfSigned("depth3d-gs-cert")
        val otherGsCert = generateSelfSigned("depth3d-other-gs")
        val clientId = generateSelfSigned("depth3d-headset-client")
        val realFp = HttpHostProtocolClient.sha256Hex(realHostCert.cert)
        val gsFp = HttpHostProtocolClient.sha256Hex(gsCert.cert)
        val otherGsFp = HttpHostProtocolClient.sha256Hex(otherGsCert.cert)

        // 1) Imposter host with NO saved pin: must NEVER dial, send client cert, send X-Depth3d-Device, or trust WebDAV!
        LocalTlsServer(imposterCert) { _ ->
            MockResponse(200, """{"webdav":{"port":9999,"user":"evil","pass":"evil"}}""")
        }.use { imposterSrv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )
            val res = client.getMediaCredentials(
                address = "127.0.0.1",
                port = imposterSrv.port,
                hostId = "host-victim",
                deviceId = "headset-42",
                expectedComputerUuid = "uuid-victim",
                expectedGsCertFingerprint = gsFp,
            )
            assertEquals(MediaCredsResult.Unauthorized, res)
            assertEquals("B1: Unpinned host must receive ZERO connections", 0, imposterSrv.acceptedConnections.get())
            assertEquals("B1: Unpinned host must receive ZERO HTTP requests", 0, imposterSrv.requests.size)

            // 2) Pin exists for host-victim, paired with realHostCert (realFp) + uuid-victim + gsFp,
            //    but imposterSrv presents imposterCert -> TLS handshake aborts BEFORE any HTTP request!
            pinStore.savePairedTrust("host-victim", HostPairedTrust(realFp, "uuid-victim", gsFp))
            val certChanged = client.getMediaCredentials(
                address = "127.0.0.1",
                port = imposterSrv.port,
                hostId = "host-victim",
                deviceId = "headset-42",
                expectedComputerUuid = "uuid-victim",
                expectedGsCertFingerprint = gsFp,
            )
            assertTrue("Expected CertificateChanged, got $certChanged", certChanged is MediaCredsResult.CertificateChanged)
            assertEquals(
                "B1: TLS mismatch must abort before HTTP request / X-Depth3d-Device is sent",
                0,
                imposterSrv.requests.size,
            )

            // 3) Even against the real host, if computerUuid or gsCertFp doesn't match the paired trust -> ZERO connections!
            val wrongUuidRes = client.getMediaCredentials(
                address = "127.0.0.1",
                port = imposterSrv.port,
                hostId = "host-victim",
                deviceId = "headset-42",
                expectedComputerUuid = "uuid-other",
                expectedGsCertFingerprint = gsFp,
            )
            assertEquals(MediaCredsResult.Unauthorized, wrongUuidRes)
            val wrongGsCertRes = client.getMediaCredentials(
                address = "127.0.0.1",
                port = imposterSrv.port,
                hostId = "host-victim",
                deviceId = "headset-42",
                expectedComputerUuid = "uuid-victim",
                expectedGsCertFingerprint = otherGsFp,
            )
            assertEquals(MediaCredsResult.Unauthorized, wrongGsCertRes)
        }

        // 4) Verified host WITH matching persisted HostPairedTrust + matching TLS cert -> succeeds with mutual TLS!
        var code = 200
        LocalTlsServer(realHostCert) { req ->
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
        }.use { realSrv ->
            val pinStore = InMemoryHostCertPinStore()
            pinStore.savePairedTrust("host-real", HostPairedTrust(realFp, "uuid-real", gsFp))

            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )
            val ok = client.getMediaCredentials("127.0.0.1", realSrv.port, "host-real", "headset-42", "uuid-real", gsFp)
            assertTrue(ok is MediaCredsResult.Available)
            val creds = (ok as MediaCredsResult.Available).creds
            assertEquals(8088, creds.port)
            assertEquals("depth3d", creds.user)
            assertEquals("s3cr3t", creds.pass)

            code = 401
            assertEquals(
                MediaCredsResult.Unauthorized,
                client.getMediaCredentials("127.0.0.1", realSrv.port, "host-real", "headset-42", "uuid-real", gsFp),
            )
            code = 404
            assertEquals(
                MediaCredsResult.NotConfigured,
                client.getMediaCredentials("127.0.0.1", realSrv.port, "host-real", "headset-42", "uuid-real", gsFp),
            )
        }
    }

    @Test
    fun b3_s2_firstPairAndCertChangeOnlyPersistPinWhenInfoIdMatchesAndPairCompletes() = runBlocking {
        val certA = generateSelfSigned("depth3d-host-A")
        val certB = generateSelfSigned("depth3d-host-B")
        val certC = generateSelfSigned("depth3d-host-C")
        val gsCertA = generateSelfSigned("depth3d-gs-A")
        val gsCertB = generateSelfSigned("depth3d-gs-B")
        val clientId = generateSelfSigned("depth3d-client")
        val fpA = HttpHostProtocolClient.sha256Hex(certA.cert)
        val fpB = HttpHostProtocolClient.sha256Hex(certB.cert)
        val fpC = HttpHostProtocolClient.sha256Hex(certC.cert)

        // Full grouped fingerprint check
        val formatted = HttpHostProtocolClient.formatFingerprint(fpB)
        assertEquals(fpB.uppercase(), formatted.replace(Regex("\\s+"), ""))
        assertTrue("Expected readable 2-line grouping", formatted.contains("\n"))

        val pinStore = InMemoryHostCertPinStore()
        val client = HttpHostProtocolClient(
            pinStore = pinStore,
            clientIdentityProvider = { clientId.cert to clientId.key },
        )

        // Step 1 (B3): First-time pairing with certA — discovery /info does NOT pin;
        // /pair 403 does NOT pin; /pair 200 + GameStream FAILED does NOT pin;
        // ONLY /pair 200 + GameStream PAIRED persists fpA!
        var pairCodeA = 403
        LocalTlsServer(certA) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"PC","id":"host-pin","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    assertNull("First-time /pair must NOT present client cert", req.clientCert)
                    if (pairCodeA == 200) MockResponse(200, """{"ok":true}""")
                    else MockResponse(pairCodeA, """{"error":"denied"}""")
                }
                else -> MockResponse(404, "{}")
            }
        }.use { srvA ->
            // /pair 403 -> rejected, pin NOT saved
            val rejected = HostAutoPairOrchestrator.execute(
                hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvA.port,
                hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "1111",
                computerUuid = "uuid-a", alreadyPairedGsCert = null, approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCertA.cert) },
            )
            assertTrue(rejected is HostAutoPairOutcome.PairRejectedOrFailed)
            assertNull("B3: Rejected /pair must NOT persist pin", pinStore.getFingerprint("host-pin"))

            // /pair 200 but GameStream pairing fails -> pin NOT saved!
            pairCodeA = 200
            val gsFail = HostAutoPairOrchestrator.execute(
                hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvA.port,
                hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "1111",
                computerUuid = "uuid-a", alreadyPairedGsCert = null, approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.FailedState(PairingManager.PairState.FAILED) },
            )
            assertTrue(gsFail is HostAutoPairOutcome.GameStreamFailed)
            assertNull("B3: Failed GameStream pair must NOT persist pin", pinStore.getFingerprint("host-pin"))

            // /pair 200 + GameStream PAIRED -> NOW fpA is persisted!
            val paired = HostAutoPairOrchestrator.execute(
                hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvA.port,
                hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "1111",
                computerUuid = "uuid-a", alreadyPairedGsCert = null, approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCertA.cert) },
            )
            assertTrue(paired is HostAutoPairOutcome.Paired)
            assertEquals(fpA, pinStore.getFingerprint("host-pin"))
            assertEquals("uuid-a", pinStore.getPairedTrust("host-pin")?.computerUuid)
        }

        // Step 2 (S2): Cert changed to certB. Approving fpB must NEVER save on 403/408/500/503 or /info id mismatch!
        var infoIdB = "wrong-id"
        var pairCodeB = 403
        LocalTlsServer(certB) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"PC","id":"$infoIdB","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    assertNull("Cert-change /pair must NOT send client cert before new cert is paired", req.clientCert)
                    if (pairCodeB == 200) MockResponse(200, """{"ok":true}""")
                    else MockResponse(pairCodeB, """{"error":"status_$pairCodeB"}""")
                }
                else -> MockResponse(404, "{}")
            }
        }.use { srvB ->
            // Unapproved -> CertificateChanged(fpA, fpB)
            val changed = client.getInfo("127.0.0.1", srvB.port, "host-pin")
            assertTrue(changed is HostInfoResult.CertificateChanged)
            assertEquals(fpA, (changed as HostInfoResult.CertificateChanged).expected)
            assertEquals(fpB, changed.actual)

            // Approved fpB, but /info returns wrong id -> rejected, pin stays fpA!
            infoIdB = "wrong-id"
            val idFail = HostAutoPairOrchestrator.execute(
                hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvB.port,
                hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "2222",
                computerUuid = "uuid-a", alreadyPairedGsCert = gsCertA.cert, approvedNewFingerprint = fpB,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCertB.cert) },
            )
            assertTrue(idFail is HostAutoPairOutcome.InfoIdentityMismatch)
            assertEquals("S2: /info id mismatch must NOT update pin", fpA, pinStore.getFingerprint("host-pin"))

            // Approved fpB, /info id matches, but /pair returns 403, 408, 500, 503 -> pin MUST stay fpA!
            infoIdB = "host-pin"
            for (errStatus in listOf(403, 408, 500, 503)) {
                pairCodeB = errStatus
                val res = HostAutoPairOrchestrator.execute(
                    hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvB.port,
                    hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "2222",
                    computerUuid = "uuid-a", alreadyPairedGsCert = gsCertA.cert, approvedNewFingerprint = fpB,
                    token = CallCancelToken(),
                    runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCertB.cert) },
                )
                assertTrue("Expected PairRejectedOrFailed for HTTP $errStatus, got $res", res is HostAutoPairOutcome.PairRejectedOrFailed)
                assertEquals("S2: HTTP $errStatus must NOT persist new cert pin", fpA, pinStore.getFingerprint("host-pin"))
            }

            // Attacker swaps in certC even though user approved fpB -> rejected, pin stays fpA!
            LocalTlsServer(certC) {
                MockResponse(200, """{"v":1,"name":"PC","id":"host-pin","os":"macos","stream":{"ready":true}}""")
            }.use { srvC ->
                val rejectedC = client.getInfo("127.0.0.1", srvC.port, "host-pin", approvedNewFingerprint = fpB)
                assertTrue(rejectedC is HostInfoResult.CertificateChanged)
                assertEquals(fpC, (rejectedC as HostInfoResult.CertificateChanged).actual)
                assertEquals(fpA, pinStore.getFingerprint("host-pin"))
            }

            // Now /info id matches AND /pair returns 200 AND GameStream pairs -> pin updates to fpB!
            pairCodeB = 200
            val okB = HostAutoPairOrchestrator.execute(
                hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvB.port,
                hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "2222",
                computerUuid = "uuid-a", alreadyPairedGsCert = gsCertA.cert, approvedNewFingerprint = fpB,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCertB.cert) },
            )
            assertTrue(okB is HostAutoPairOutcome.Paired)
            assertEquals("S2: Pin must update to fpB after /info + /pair + pairing succeed", fpB, pinStore.getFingerprint("host-pin"))
        }
    }

    @Test
    fun requestAutoPairFollowsProtocolV1ClientCertRulesAnd503HandlingAndNeverLogsPin() {
        val serverId = generateSelfSigned("depth3d-host-pair")
        val gsCert = generateSelfSigned("depth3d-gs")
        val clientId = generateSelfSigned("depth3d-headset-pair-cert")
        val serverFp = HttpHostProtocolClient.sha256Hex(serverId.cert)
        val gsFp = HttpHostProtocolClient.sha256Hex(gsCert.cert)
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

                // B1 + #10: Unpinned host NEVER receives client cert even if presentClientCert = true!
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
                        expectedFingerprint = serverFp,
                        presentClientCert = true,
                    ),
                )
                assertNull(
                    "B1: Unpinned host must NOT receive client certificate",
                    srv.requests.last().clientCert,
                )

                // Once paired & pinned, re-pair DOES present the pairing client certificate
                pinStore.savePairedTrust("host-pair", HostPairedTrust(serverFp, "uuid-pair", gsFp))
                assertEquals(
                    AutoPairResult.Approved,
                    client.requestAutoPair(
                        address = "127.0.0.1",
                        port = srv.port,
                        hostId = "host-pair",
                        deviceName = "我的头显",
                        deviceId = "dev-abc",
                        pin = secretPin,
                        expectedFingerprint = serverFp,
                        presentClientCert = true,
                    ),
                )
                assertNotNull("Paired re-pair /pair must present client certificate", srv.requests.last().clientCert)
                assertEquals(clientId.cert.serialNumber, srv.requests.last().clientCert!!.serialNumber)

                // 503 means Mac streaming service is not running
                statusToReturn = 503
                assertEquals(
                    AutoPairResult.StreamServiceUnavailable,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 403
                assertEquals(
                    AutoPairResult.Rejected,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 408
                assertEquals(
                    AutoPairResult.TimedOut,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 426
                assertEquals(
                    AutoPairResult.UpgradeRequired,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
            }
        } finally {
            logger.removeHandler(logHandler)
        }

        assertTrue("PIN must never be written to logs", capturedLogs.none { it.contains(secretPin) })
    }

    @Test
    fun cancelInterruptsInFlightPairCallAndImmediateRetrySucceeds() {
        val serverId = generateSelfSigned("depth3d-host-cancel")
        val serverFp = HttpHostProtocolClient.sha256Hex(serverId.cert)
        val pinStore = InMemoryHostCertPinStore()
        val attempt = AtomicInteger(0)
        val firstReqArrived = CountDownLatch(1)
        val releaseFirstReq = CountDownLatch(1)

        LocalTlsServer(serverId) { _ ->
            val n = attempt.incrementAndGet()
            if (n == 1) {
                firstReqArrived.countDown()
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
                        expectedFingerprint = serverFp,
                        cancelToken = cancelToken,
                    )
                }

                assertTrue("First request should reach server", firstReqArrived.await(3, TimeUnit.SECONDS))
                cancelToken.cancel()

                val cancelled = future.get(3, TimeUnit.SECONDS)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                assertEquals(AutoPairResult.Cancelled, cancelled)
                assertTrue("Cancel should abort quickly (< 3000ms), took ${elapsedMs}ms", elapsedMs < 3000L)

                val retried = client.requestAutoPair(
                    address = "127.0.0.1",
                    port = srv.port,
                    hostId = "host-cancel",
                    deviceName = "depth3d",
                    deviceId = "dev-1",
                    pin = "5678",
                    expectedFingerprint = serverFp,
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
    fun s3_statefulPairingServerCancelRunsUnpairCleanupSoImmediateRetryNeverReturnsAlreadyInProgress() = runBlocking {
        val serverId = generateSelfSigned("depth3d-gs-server")
        val clientId = generateSelfSigned("depth3d-gs-client")
        val crypto = cryptoProviderFor(clientId)
        val pin = "4826"

        // First prove our stateful mock server actually rejects concurrent pairing with ALREADY_IN_PROGRESS
        // if /unpair is NOT sent after an abandoned attempt.
        StatefulMockGameStreamServer(serverId, pin, blockFirstGetServerCert = false).use { sanitySrv ->
            sanitySrv.pairingInProgress.set(true)
            val http = NvHTTP(
                ComputerDetails.AddressTuple("127.0.0.1", sanitySrv.httpPort),
                sanitySrv.httpsPort,
                "0123456789ABCDEF",
                null,
                crypto,
            )
            val info = http.getServerInfo(true)
            val state = http.pairingManager.pair(info, pin)
            assertEquals(
                "Mock server must report ALREADY_IN_PROGRESS when pairing lock is still held",
                PairingManager.PairState.ALREADY_IN_PROGRESS,
                state,
            )
        }

        // Now exercise the REAL GameStreamPairRunner + real PairingManager.pair() cancel -> unpair -> immediate retry!
        StatefulMockGameStreamServer(serverId, pin, blockFirstGetServerCert = true).use { srv ->
            val httpFactory = {
                NvHTTP(
                    ComputerDetails.AddressTuple("127.0.0.1", srv.httpPort),
                    srv.httpsPort,
                    "0123456789ABCDEF",
                    null,
                    crypto,
                )
            }

            val token1 = CallCancelToken()
            val startedAt = System.nanoTime()
            val job1 = launch(Dispatchers.IO) {
                GameStreamPairRunner.run(
                    httpFactory = httpFactory,
                    existingServerCert = null,
                    pin = pin,
                    token = token1,
                    ioDispatcher = Dispatchers.IO,
                )
            }

            // Wait until real PairingManager.pair() is blocked inside phrase=getservercert
            assertTrue("PairingManager.pair() should reach server", srv.firstPairBlocked.await(3, TimeUnit.SECONDS))
            assertTrue("Server should hold active pairing lock", srv.pairingInProgress.get())

            // Simulate user pressing Cancel (mirrors StreamingController.cancelActiveOperation):
            // cancel token + cancel coroutine job, then IMMEDIATELY retry by joining job1!
            token1.cancel()
            job1.cancel()

            // Mirror StreamingController.startPair() joining previousOperationJob before retrying:
            val joined = withTimeoutOrNull(3_000L) { job1.join(); true }
            assertEquals("Cancelled job + NonCancellable unpair cleanup must complete promptly", true, joined)
            val cancelElapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue("Cancel + cleanup should finish quickly, took ${cancelElapsedMs}ms", cancelElapsedMs < 3_000L)
            assertTrue("S3: NonCancellable finally must have sent /unpair", srv.unpairCount.get() >= 1)
            assertFalse("Server pairing lock must be released by /unpair", srv.pairingInProgress.get())

            // Immediate retry runs real PairingManager.pair() to full cryptographic PAIRED completion!
            val retryOutcome = GameStreamPairRunner.run(
                httpFactory = httpFactory,
                existingServerCert = null,
                pin = pin,
                token = CallCancelToken(),
                ioDispatcher = Dispatchers.IO,
            )
            assertTrue("S3: Immediate retry must succeed with Paired, got $retryOutcome", retryOutcome is GameStreamPairOutcome.Paired)
            assertEquals("S3: Immediate retry must NEVER hit ALREADY_IN_PROGRESS", 0, srv.busyRejectionCount.get())
            val pairedCert = (retryOutcome as GameStreamPairOutcome.Paired).serverCert
            assertNotNull(pairedCert)
            assertEquals(serverId.cert.serialNumber, pairedCert!!.serialNumber)
        }
    }

    @Test
    fun m5_r4_4_formatUrlsBracketIpv6AndParseCleanlyWithOkHttp() {
        val v4Dav = HttpHostProtocolClient.formatWebDavUrl("192.168.1.50", 8088)
        assertEquals("http://192.168.1.50:8088", v4Dav)
        assertNotNull("IPv4 WebDAV URL must parse in OkHttp", v4Dav.toHttpUrlOrNull())

        val v6Dav = HttpHostProtocolClient.formatWebDavUrl("2409:8a00::1ec", 8088)
        assertEquals("http://[2409:8a00::1ec]:8088", v6Dav)
        assertNotNull("Routable IPv6 WebDAV URL must parse in OkHttp", v6Dav.toHttpUrlOrNull())

        val v6Host = HttpHostProtocolClient.formatHostUrl("2409:8a00::1ec", 47990, "/depth3d/v1/info")
        assertEquals("https://[2409:8a00::1ec]:47990/depth3d/v1/info", v6Host)
        assertNotNull("Routable IPv6 Host URL must parse in OkHttp", v6Host.toHttpUrlOrNull())

        assertEquals("http://[2409:8a00::1ec]:8088", HttpHostProtocolClient.formatWebDavUrl("[2409:8a00::1ec]", 8088))

        // Confirm why #4 dropped scoped link-local in HostDiscovery.chooseAddress: OkHttp rejects %scope
        assertNull(HttpHostProtocolClient.formatWebDavUrl("fe80::7a:e3f9:c3fa:d2de%3", 8088).toHttpUrlOrNull())
    }

    @Test
    fun r4_1_2_skipPairRequiresStrictHttpsPinnedCertAndRejectsImposterLyingOverHttp() = runBlocking {
        val realGsId = generateSelfSigned("real-mac-gs")
        val imposterGsId = generateSelfSigned("imposter-gs")
        val realHostId = generateSelfSigned("real-mac-host")
        val imposterHostId = generateSelfSigned("imposter-host")
        val clientId = generateSelfSigned("headset-client")
        val crypto = cryptoProviderFor(clientId)
        val realHostFp = HttpHostProtocolClient.sha256Hex(realHostId.cert)
        val imposterHostFp = HttpHostProtocolClient.sha256Hex(imposterHostId.cert)

        // Attack (#2): Imposter runs a fake Host returning 200 on /pair + fake GameStream server that
        // lies <PairStatus>1</PairStatus> over plain HTTP AND serves imposterGsId (NOT realGsId) on HTTPS.
        // Because imposter does NOT possess realGsId's private key, verifyPinnedPairedServer MUST return false,
        // forcing full pm.pair (which fails with wrong PIN), and HostPairedTrust MUST NOT be persisted!
        StatefulMockGameStreamServer(
            serverId = imposterGsId,
            expectedPin = "0000", // imposter doesn't know the real PIN on GS
            blockFirstGetServerCert = false,
            uniqueId = "real-gs-uuid",
            httpPairStatus = 1, // lies over HTTP!
            httpsPairStatus = 1, // even lies over HTTPS, but with wrong cert!
        ).use { imposterGs ->
            LocalTlsServer(imposterHostId) { req ->
                when (req.path) {
                    "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac","id":"host-real","os":"macos","stream":{"ready":true}}""")
                    "/depth3d/v1/pair" -> MockResponse(200, """{"ok":true}""")
                    else -> MockResponse(404, "{}")
                }
            }.use { imposterHost ->
                val pinStore = InMemoryHostCertPinStore()
                // Suppose user previously trusted realHostFp, then clicked "Trust new cert" for imposterHostFp
                pinStore.savePairedTrust(
                    "host-real",
                    HostPairedTrust(realHostFp, "real-gs-uuid", HttpHostProtocolClient.sha256Hex(realGsId.cert), "书房 Mac"),
                )
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )

                val outcome = HostAutoPairOrchestrator.execute(
                    hostApi = client,
                    pinStore = pinStore,
                    address = "127.0.0.1",
                    controlPort = imposterHost.port,
                    hostId = "host-real",
                    deviceName = "depth3d",
                    deviceId = "dev-1",
                    pin = "7777",
                    computerUuid = "real-gs-uuid",
                    alreadyPairedGsCert = realGsId.cert,
                    approvedNewFingerprint = imposterHostFp,
                    token = CallCancelToken(),
                    runGameStreamPair = { pin, token ->
                        GameStreamPairRunner.run(
                            httpFactory = {
                                NvHTTP(
                                    ComputerDetails.AddressTuple("127.0.0.1", imposterGs.httpPort),
                                    0,
                                    "0123456789ABCDEF",
                                    realGsId.cert,
                                    crypto,
                                )
                            },
                            existingServerCert = realGsId.cert,
                            expectedComputerUuid = "real-gs-uuid",
                            pin = pin,
                            token = token,
                        )
                    },
                )
                assertTrue(
                    "#2: Imposter without realGsId private key must fail full PIN pair, got $outcome",
                    outcome is HostAutoPairOutcome.GameStreamFailed,
                )
                assertEquals(
                    "#2: Pin must stay realHostFp and NEVER be overwritten with imposterHostFp!",
                    realHostFp,
                    pinStore.getFingerprint("host-real"),
                )
            }
        }

        // Upgrade path (#1 + #2): Real Mac is ALREADY paired at GameStream (presents exact realGsId.cert
        // on HTTPS with PairStatus=1 and uniqueid=real-gs-uuid), user clicks card -> /info -> /pair 200 ->
        // verifyPinnedPairedServer succeeds -> skips PIN entry -> saves HostPairedTrust -> /media succeeds!
        StatefulMockGameStreamServer(
            serverId = realGsId,
            expectedPin = "unused-because-verified-over-https",
            blockFirstGetServerCert = false,
            uniqueId = "real-gs-uuid",
            httpPairStatus = 0,
            httpsPairStatus = 1,
        ).use { realGs ->
            LocalTlsServer(realHostId) { req ->
                when (req.path) {
                    "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"书房 Mac","id":"host-real","os":"macos","stream":{"ready":true},"media":{"port":8088}}""")
                    "/depth3d/v1/pair" -> MockResponse(200, """{"ok":true}""")
                    "/depth3d/v1/media" -> {
                        assertNotNull("Expected client cert on /media", req.clientCert)
                        MockResponse(200, """{"webdav":{"port":8088,"user":"depth3d","pass":"secret"}}""")
                    }
                    else -> MockResponse(404, "{}")
                }
            }.use { realHost ->
                val pinStore = InMemoryHostCertPinStore()
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )

                val outcome = HostAutoPairOrchestrator.execute(
                    hostApi = client,
                    pinStore = pinStore,
                    address = "127.0.0.1",
                    controlPort = realHost.port,
                    hostId = "host-real",
                    deviceName = "depth3d",
                    deviceId = "dev-1",
                    pin = "1234",
                    computerUuid = "real-gs-uuid",
                    alreadyPairedGsCert = realGsId.cert,
                    approvedNewFingerprint = null,
                    savedName = "书房 Mac",
                    token = CallCancelToken(),
                    runGameStreamPair = { pin, token ->
                        GameStreamPairRunner.run(
                            httpFactory = {
                                NvHTTP(
                                    ComputerDetails.AddressTuple("127.0.0.1", realGs.httpPort),
                                    0,
                                    "0123456789ABCDEF",
                                    realGsId.cert,
                                    crypto,
                                )
                            },
                            existingServerCert = realGsId.cert,
                            expectedComputerUuid = "real-gs-uuid",
                            pin = pin,
                            token = token,
                        )
                    },
                )
                assertTrue("#1/#2: Real Mac upgrade must succeed via HTTPS pinned verification, got $outcome", outcome is HostAutoPairOutcome.Paired)
                assertEquals(realHostFp, pinStore.getFingerprint("host-real"))
                assertEquals("real-gs-uuid", pinStore.getPairedTrust("host-real")?.computerUuid)

                // Immediately after trust is saved, /media succeeds with mutual TLS!
                val mediaRes = client.getMediaCredentials(
                    address = "127.0.0.1",
                    port = realHost.port,
                    hostId = "host-real",
                    deviceId = "dev-1",
                    expectedComputerUuid = "real-gs-uuid",
                    expectedGsCertFingerprint = HttpHostProtocolClient.sha256Hex(realGsId.cert),
                )
                assertTrue("#1: /media must succeed right after upgrade setup", mediaRes is MediaCredsResult.Available)
            }
        }
    }

    @Test
    fun r4_6_certChangeBetweenDiscoveryAndPairPromptsCertificateChanged() = runBlocking {
        val certDiscovered = generateSelfSigned("host-discovered")
        val certAtPair = generateSelfSigned("host-changed-before-pair")
        val gsCert = generateSelfSigned("gs-cert")
        val fpDiscovered = HttpHostProtocolClient.sha256Hex(certDiscovered.cert)
        val fpAtPair = HttpHostProtocolClient.sha256Hex(certAtPair.cert)
        val pairCalls = AtomicInteger(0)

        LocalTlsServer(certAtPair) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac","id":"host-r6","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    pairCalls.incrementAndGet()
                    MockResponse(200, """{"ok":true}""")
                }
                else -> MockResponse(404, "{}")
            }
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(pinStore)

            // #6: Unpinned host observed with fpDiscovered during discovery now presents certAtPair -> CertificateChanged!
            val changed = HostAutoPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-r6",
                deviceName = "depth3d",
                deviceId = "dev-1",
                pin = "1234",
                computerUuid = "uuid-r6",
                alreadyPairedGsCert = null,
                approvedNewFingerprint = null,
                discoveredFingerprint = fpDiscovered,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
            )
            assertTrue("#6: Must surface CertificateChanged when cert changed since discovery", changed is HostAutoPairOutcome.CertificateChanged)
            val cc = changed as HostAutoPairOutcome.CertificateChanged
            assertEquals(fpDiscovered, cc.expected)
            assertEquals(fpAtPair, cc.actual)
            assertEquals("Must not send /pair before user confirms", 0, pairCalls.get())

            // After user explicitly approves fpAtPair -> succeeds and persists fpAtPair!
            val approved = HostAutoPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-r6",
                deviceName = "depth3d",
                deviceId = "dev-1",
                pin = "1234",
                computerUuid = "uuid-r6",
                alreadyPairedGsCert = null,
                approvedNewFingerprint = fpAtPair,
                discoveredFingerprint = fpDiscovered,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
            )
            assertTrue(approved is HostAutoPairOutcome.Paired)
            assertEquals(fpAtPair, pinStore.getFingerprint("host-r6"))
        }
    }

    @Test
    fun r4_8_pendingCleanupQueueAwaitsAllCancelledJobsAcrossCancelRetryCancel() = runBlocking {
        val queue = PendingCleanupQueue()
        val job1Entered = CountDownLatch(1)
        val cleanup1Started = CountDownLatch(1)
        val allowCleanup1Finish = CountDownLatch(1)
        val cleanup1Done = AtomicBoolean(false)

        val job1 = launch(Dispatchers.IO) {
            try {
                job1Entered.countDown()
                kotlinx.coroutines.delay(60_000L)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    cleanup1Started.countDown()
                    allowCleanup1Finish.await(5, TimeUnit.SECONDS)
                    cleanup1Done.set(true)
                }
            }
        }
        assertTrue(job1Entered.await(2, TimeUnit.SECONDS))
        // Cancel job1 first, then track it (simulating Cancelling state where isActive==false, isCompleted==false!)
        job1.cancel()
        queue.track(job1)
        assertTrue(cleanup1Started.await(2, TimeUnit.SECONDS))

        // Retry (job2) starts waiting on job1, then user cancels job2 while still waiting!
        val job2Waiting = CountDownLatch(1)
        val job2 = launch(Dispatchers.IO) {
            job2Waiting.countDown()
            queue.awaitAll(3_000L)
        }
        assertTrue(job2Waiting.await(2, TimeUnit.SECONDS))
        job2.cancel()
        queue.track(job2)

        // Retry #2 (job3) MUST still wait for job1 (not dropped by job2's cancel or Cancelling state)!
        val job3PassedWait = AtomicBoolean(false)
        val job3 = launch(Dispatchers.IO) {
            queue.awaitAll(3_000L)
            job3PassedWait.set(true)
        }
        Thread.sleep(150)
        assertFalse("#8: job3 must still be waiting on job1's cleanup", job3PassedWait.get())
        assertFalse(cleanup1Done.get())

        allowCleanup1Finish.countDown()
        job3.join()
        assertTrue("#8: job1 cleanup must have completed before job3 proceeded", cleanup1Done.get())
        assertTrue(job3PassedWait.get())
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun r4_11_manualPinHostCertChangeConfirmVerifiesInfoAndPersistsApprovedPin() = runBlocking {
        val certOld = generateSelfSigned("host-manual-old")
        val certNew = generateSelfSigned("host-manual-new")
        val gsCert = generateSelfSigned("gs-manual")
        val fpOld = HttpHostProtocolClient.sha256Hex(certOld.cert)
        val fpNew = HttpHostProtocolClient.sha256Hex(certNew.cert)
        val gsFp = HttpHostProtocolClient.sha256Hex(gsCert.cert)

        var infoId = "wrong-id"
        LocalTlsServer(certNew) { req ->
            assertEquals("/depth3d/v1/info", req.path)
            MockResponse(200, """{"v":1,"name":"Manual Mac","id":"$infoId","os":"macos","stream":{"ready":true}}""")
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            pinStore.savePairedTrust("host-manual", HostPairedTrust(fpOld, "uuid-m", gsFp, "Manual Mac"))
            val client = HttpHostProtocolClient(pinStore)

            // Mismatched /info id must reject and NOT save fpNew
            val badId = HostManualPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-manual",
                pin = "1111",
                computerUuid = "uuid-m",
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
            )
            assertTrue(badId is HostAutoPairOutcome.InfoIdentityMismatch)
            assertEquals(fpOld, pinStore.getFingerprint("host-manual"))

            // Valid /info id + failed manual GameStream pair -> still does NOT save fpNew
            infoId = "host-manual"
            val gsFail = HostManualPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-manual",
                pin = "1111",
                computerUuid = "uuid-m",
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.FailedState(PairingManager.PairState.PIN_WRONG) },
            )
            assertTrue(gsFail is HostAutoPairOutcome.GameStreamFailed)
            assertEquals(fpOld, pinStore.getFingerprint("host-manual"))

            // Valid /info id + successful manual PIN pairing -> persists fpNew so next getInfo succeeds!
            val ok = HostManualPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-manual",
                pin = "1111",
                computerUuid = "uuid-m",
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
            )
            assertTrue("#11: Manual-PIN cert confirm must succeed, got $ok", ok is HostAutoPairOutcome.Paired)
            assertEquals("#11: Approved fingerprint must now be saved", fpNew, pinStore.getFingerprint("host-manual"))
            assertTrue(
                "#11: Subsequent /info without approvedNewFingerprint must succeed without re-prompting",
                client.getInfo("127.0.0.1", srv.port, "host-manual") is HostInfoResult.Ok,
            )
        }
    }
}
