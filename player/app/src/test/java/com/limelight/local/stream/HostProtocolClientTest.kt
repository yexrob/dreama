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
        val awaitPeerDisconnect: (timeoutMs: Long) -> Boolean = { false },
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
                awaitPeerDisconnect = { timeoutMs ->
                    try {
                        sock.soTimeout = timeoutMs.toInt().coerceAtLeast(50)
                        val b = input.read()
                        b == -1
                    } catch (_: java.net.SocketTimeoutException) {
                        false
                    } catch (_: Exception) {
                        true
                    }
                },
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
        private val requirePairUnlockBeforeAcceptingPin: Boolean = false,
        private val rejectDuplicateClientCert: Boolean = false,
        private val onPairStarted: () -> Unit = {},
        private val onPairFinished: () -> Unit = {},
    ) : Closeable {
        val pairingInProgress = AtomicBoolean(false)
        val unpairCount = AtomicInteger(0)
        val busyRejectionCount = AtomicInteger(0)
        val firstPairBlocked = CountDownLatch(1)
        val pairedClientCertFps = CopyOnWriteArrayList<String>()

        @Volatile private var unlockedPin: String? = null
        private val pinUnlockedLatch = CountDownLatch(1)
        private val getCertAttempts = AtomicInteger(0)
        private val releaseFirstBlock = CountDownLatch(1)

        fun unlockPairingForPin(pin: String) {
            unlockedPin = pin
            pinUnlockedLatch.countDown()
        }

        fun isClientCertPaired(cert: X509Certificate): Boolean =
            pairedClientCertFps.any { it.equals(HttpHostProtocolClient.sha256Hex(cert), ignoreCase = true) }

        fun removeClientCertEntries(cert: X509Certificate) {
            val fp = HttpHostProtocolClient.sha256Hex(cert)
            pairedClientCertFps.removeIf { it.equals(fp, ignoreCase = true) }
        }
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
                    pinUnlockedLatch.countDown()
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
                onPairStarted()
                if (!pairingInProgress.compareAndSet(false, true)) {
                    // Exact real GFE/Sunshine busy response: paired=1 WITHOUT <plaincert>
                    // -> PairingManager.pair() returns PairState.ALREADY_IN_PROGRESS!
                    busyRejectionCount.incrementAndGet()
                    return """<root status_code="200"><paired>1</paired></root>"""
                }
                val activePin = if (requirePairUnlockBeforeAcceptingPin) {
                    // M6: Realistic Sunshine behavior — getservercert BLOCKS until PIN is submitted
                    // (or /unpair / close cancels it), rather than failing immediately!
                    pinUnlockedLatch.await(120, TimeUnit.SECONDS)
                    unlockedPin ?: return """<root status_code="200"><paired>0</paired></root>"""
                } else {
                    expectedPin
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
                val salted = ByteArray(salt.size + activePin.length)
                System.arraycopy(salt, 0, salted, 0, salt.size)
                System.arraycopy(activePin.toByteArray(StandardCharsets.UTF_8), 0, salted, salt.size, activePin.length)
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
                val cert = clientCert
                if (rejectDuplicateClientCert && cert != null && isClientCertPaired(cert)) {
                    // Simulates built-in engine rejecting duplicate client cert entries at final step (3676b2b)
                    pairingInProgress.set(false)
                    return """<root status_code="200"><paired>0</paired></root>"""
                }
                cert?.let { pairedClientCertFps.add(HttpHostProtocolClient.sha256Hex(it)) }
                pairingInProgress.set(false)
                onPairFinished()
                return """<root status_code="200"><paired>1</paired></root>"""
            }
            return """<root status_code="200"><paired>0</paired></root>"""
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseFirstBlock.countDown()
            pinUnlockedLatch.countDown()
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
                    MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-b2"}""")
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
                201 -> MockResponse(200, """{"webdav":null}""")
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

            code = 201 // 200 {"webdav":null} when media service is disabled (host-protocol.md 3676b2b)
            assertEquals(
                MediaCredsResult.NotConfigured,
                client.getMediaCredentials("127.0.0.1", realSrv.port, "host-real", "headset-42", "uuid-real", gsFp),
            )
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
        // /pair/complete 410 does NOT pin; ONLY /pair/complete 200 persists fpA!
        var pairCodeA = 403
        var completeCodeA = 410
        LocalTlsServer(certA) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"PC","id":"host-pin","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    assertNotNull("/pair must ALWAYS present client cert (host-protocol.md 3676b2b)", req.clientCert)
                    assertEquals(false, MiniJson.parseObject(req.body)!!.bool("streamPaired"))
                    if (pairCodeA == 200) MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-a"}""")
                    else MockResponse(pairCodeA, """{"error":"rejected"}""")
                }
                "/depth3d/v1/pair/complete" -> {
                    assertNotNull("/pair/complete must present client cert", req.clientCert)
                    if (completeCodeA == 200) MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                    else MockResponse(completeCodeA, """{"error":"ticket_expired"}""")
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

            // /pair 200 + GameStream PAIRED + /pair/complete 410 -> pin STILL NOT saved!
            completeCodeA = 410
            val completeFail = HostAutoPairOrchestrator.execute(
                hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvA.port,
                hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "1111",
                computerUuid = "uuid-a", alreadyPairedGsCert = null, approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCertA.cert) },
            )
            assertTrue(completeFail is HostAutoPairOutcome.PairCompleteFailed)
            assertNull("Failed /pair/complete must NOT persist pin", pinStore.getFingerprint("host-pin"))

            // /pair 200 + GameStream PAIRED + /pair/complete 200 -> NOW fpA is persisted!
            completeCodeA = 200
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

        // Step 2 (S2): Cert changed to certB. Approving fpB must NEVER save on 403/408/429/500/503 or /info id mismatch!
        var infoIdB = "wrong-id"
        var pairCodeB = 403
        LocalTlsServer(certB) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"PC","id":"$infoIdB","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    assertNotNull("/pair must ALWAYS present client cert (host-protocol.md 3676b2b)", req.clientCert)
                    assertEquals(false, MiniJson.parseObject(req.body)!!.bool("streamPaired"))
                    if (pairCodeB == 200) MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-b"}""")
                    else MockResponse(pairCodeB, """{"error":"status_$pairCodeB"}""")
                }
                "/depth3d/v1/pair/complete" -> {
                    assertNotNull("/pair/complete must present client cert", req.clientCert)
                    MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
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

            // Approved fpB, /info id matches, but /pair returns 403, 408, 429, 500, 503 -> pin MUST stay fpA!
            infoIdB = "host-pin"
            for (errStatus in listOf(403, 408, 429, 500, 503)) {
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

            // Now /info id matches AND /pair returns 200 AND GameStream pairs with new gsCertB:
            // S3: Because gsCertB != gsCertA, MUST prompt StreamServerCertChanged before /pair/complete or saving!
            pairCodeB = 200
            val gsChanged = HostAutoPairOrchestrator.execute(
                hostApi = client, pinStore = pinStore, address = "127.0.0.1", controlPort = srvB.port,
                hostId = "host-pin", deviceName = "depth3d", deviceId = "dev-1", pin = "2222",
                computerUuid = "uuid-a", alreadyPairedGsCert = gsCertA.cert, approvedNewFingerprint = fpB,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCertB.cert) },
            )
            assertTrue("S3: Changed GameStream serverCert must require confirmation, got $gsChanged", gsChanged is HostAutoPairOutcome.StreamServerCertChanged)
            assertEquals("S3: Pin must stay fpA until user confirms stream server cert change", fpA, pinStore.getFingerprint("host-pin"))

            // Once user confirms the changed stream cert (via pendingComplete -> /pair/complete 200), pin updates to fpB!
            val okB = HostAutoPairOrchestrator.completeWithRetries(
                hostApi = client,
                pinStore = pinStore,
                pending = (gsChanged as HostAutoPairOutcome.StreamServerCertChanged).pendingComplete!!,
                token = CallCancelToken(),
            )
            assertTrue(okB is HostAutoPairOutcome.Paired)
            assertEquals("S2: Pin must update to fpB after /info + /pair + pairing + confirm + /pair/complete succeed", fpB, pinStore.getFingerprint("host-pin"))
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
        var pairBody = """{"ok":true,"next":"gamestream","ticket":"tk-secret-1"}"""
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
                MockResponse(statusToReturn, pairBody)
            }.use { srv ->
                val clientFp = HttpHostProtocolClient.sha256Hex(clientId.cert)
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )
                // Spec requirement #1: /pair read timeout must be >= 75 s
                assertTrue(
                    "/pair read timeout must be >= 75s, was ${client.configuredPairReadTimeoutMs}",
                    client.configuredPairReadTimeoutMs >= HttpHostProtocolClient.MIN_PAIR_READ_TIMEOUT_MS,
                )

                // Missing local client cert returns ClientCertRequired without dialing /pair
                val noCertClient = HttpHostProtocolClient(pinStore = pinStore, clientIdentityProvider = { null })
                assertEquals(
                    AutoPairResult.ClientCertRequired,
                    noCertClient.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )

                // Per host-protocol.md (3676b2b): /pair ALWAYS presents client cert + streamPaired:false when unpaired!
                statusToReturn = 200
                pairBody = """{"ok":true,"next":"gamestream","ticket":"tk-secret-1"}"""
                assertEquals(
                    AutoPairResult.Approved(PairNextStep.GAMESTREAM, "tk-secret-1", clientFp),
                    client.requestAutoPair(
                        address = "127.0.0.1",
                        port = srv.port,
                        hostId = "host-pair",
                        deviceName = "我的头显",
                        deviceId = "dev-abc",
                        pin = secretPin,
                        expectedFingerprint = serverFp,
                        streamPaired = false,
                    ),
                )
                assertNotNull(
                    "Spec 3676b2b: /pair must ALWAYS present client certificate",
                    srv.requests.last().clientCert,
                )
                assertEquals(clientId.cert.serialNumber, srv.requests.last().clientCert!!.serialNumber)
                assertEquals(false, MiniJson.parseObject(srv.requests.last().body)!!.bool("streamPaired"))

                // Verified upgrade passes streamPaired=true and receives next="done"
                pinStore.savePairedTrust("host-pair", HostPairedTrust(serverFp, "uuid-pair", gsFp))
                pairBody = """{"ok":true,"next":"done","ticket":"tk-done-2"}"""
                assertEquals(
                    AutoPairResult.Approved(PairNextStep.DONE, "tk-done-2", clientFp),
                    client.requestAutoPair(
                        address = "127.0.0.1",
                        port = srv.port,
                        hostId = "host-pair",
                        deviceName = "我的头显",
                        deviceId = "dev-abc",
                        pin = secretPin,
                        expectedFingerprint = serverFp,
                        streamPaired = true,
                    ),
                )
                assertNotNull("/pair must present client certificate", srv.requests.last().clientCert)
                assertEquals(true, MiniJson.parseObject(srv.requests.last().body)!!.bool("streamPaired"))

                // Server 401 client_cert_required maps to ClientCertRequired
                statusToReturn = 401
                pairBody = """{"error":"client_cert_required"}"""
                assertEquals(
                    AutoPairResult.ClientCertRequired,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )

                // Spec requirement #5: verify EVERY /pair error status & JSON payload
                statusToReturn = 503
                pairBody = """{"error":"stream_not_ready","reason":"请在系统设置中授予屏幕录制权限"}"""
                assertEquals(
                    AutoPairResult.StreamServiceUnavailable("请在系统设置中授予屏幕录制权限"),
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 429
                pairBody = """{"error":"busy"}"""
                assertEquals(
                    AutoPairResult.Busy(null),
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 429
                pairBody = """{"error":"busy","retryAfterSec":4}"""
                assertEquals(
                    AutoPairResult.Busy(4),
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 429
                pairBody = """{"error":"cooldown","retryAfterSec":27}"""
                assertEquals(
                    AutoPairResult.Cooldown(27),
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 403
                pairBody = """{"error":"rejected"}"""
                assertEquals(
                    AutoPairResult.Rejected,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 408
                pairBody = """{"error":"timeout"}"""
                assertEquals(
                    AutoPairResult.TimedOut,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
                statusToReturn = 426
                pairBody = """{"error":"upgrade"}"""
                assertEquals(
                    AutoPairResult.UpgradeRequired,
                    client.requestAutoPair("127.0.0.1", srv.port, "host-pair", "我的头显", "dev-abc", secretPin, serverFp),
                )
            }
        } finally {
            logger.removeHandler(logHandler)
        }

        assertTrue("PIN must never be written to logs", capturedLogs.none { it.contains(secretPin) })
        assertTrue("Ticket must never be written to logs", capturedLogs.none { it.contains("tk-secret-1") })
    }

    @Test
    fun cancelInterruptsInFlightPairCallAndImmediateRetrySucceeds() {
        val serverId = generateSelfSigned("depth3d-host-cancel")
        val clientId = generateSelfSigned("depth3d-client-cancel")
        val serverFp = HttpHostProtocolClient.sha256Hex(serverId.cert)
        val clientFp = HttpHostProtocolClient.sha256Hex(clientId.cert)
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
                MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-retry"}""")
            }
        }.use { srv ->
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )
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
                assertEquals(AutoPairResult.Approved(PairNextStep.GAMESTREAM, "tk-retry", clientFp), retried)
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
    fun r4_1_2_upgradePathPreVerifiesHttpsCertBeforeClientCertAndRejectsImposter() = runBlocking {
        val realGsId = generateSelfSigned("real-mac-gs")
        val imposterGsId = generateSelfSigned("imposter-gs")
        val realHostId = generateSelfSigned("real-mac-host")
        val imposterHostId = generateSelfSigned("imposter-host")
        val clientId = generateSelfSigned("headset-client")
        val crypto = cryptoProviderFor(clientId)
        val realHostFp = HttpHostProtocolClient.sha256Hex(realHostId.cert)
        val imposterHostFp = HttpHostProtocolClient.sha256Hex(imposterHostId.cert)

        // Attack (#2 + spec §升级路径): Imposter copies host.id at another IPv4, lies <PairStatus>1</PairStatus>
        // over HTTP, serves imposterGsId (NOT realGsId) on HTTPS, AND lies next="done" on /pair!
        // Step 1 strict HTTPS pinned-cert verify MUST fail -> NO client cert sent on /pair ->
        // headset REFUSES next="done" and forces full pm.pair (which fails) -> /pair/complete NEVER called
        // and HostPairedTrust is NEVER overwritten!
        val imposterCompleteCalls = AtomicInteger(0)
        StatefulMockGameStreamServer(
            serverId = imposterGsId,
            expectedPin = "0000",
            blockFirstGetServerCert = false,
            uniqueId = "real-gs-uuid",
            httpPairStatus = 1,
            httpsPairStatus = 1,
        ).use { imposterGs ->
            LocalTlsServer(imposterHostId) { req ->
                when (req.path) {
                    "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac","id":"host-real","os":"macos","stream":{"ready":true}}""")
                    "/depth3d/v1/pair" -> {
                        assertNotNull("/pair always presents client cert (3676b2b)", req.clientCert)
                        assertEquals("Unverified imposter must receive streamPaired=false", false, MiniJson.parseObject(req.body)!!.bool("streamPaired"))
                        MockResponse(200, """{"ok":true,"next":"done","ticket":"tk-evil"}""")
                    }
                    "/depth3d/v1/pair/complete" -> {
                        imposterCompleteCalls.incrementAndGet()
                        MockResponse(200, """{"ok":true,"media":{"port":9999}}""")
                    }
                    else -> MockResponse(404, "{}")
                }
            }.use { imposterHost ->
                val pinStore = InMemoryHostCertPinStore()
                pinStore.savePairedTrust(
                    "host-real",
                    HostPairedTrust(realHostFp, "real-gs-uuid", HttpHostProtocolClient.sha256Hex(realGsId.cert), "书房 Mac"),
                )
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )
                val imposterHttpFactory = {
                    NvHTTP(
                        ComputerDetails.AddressTuple("127.0.0.1", imposterGs.httpPort),
                        0,
                        "0123456789ABCDEF",
                        realGsId.cert,
                        crypto,
                    )
                }

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
                    verifyExistingGameStreamPair = { token ->
                        GameStreamPairRunner.verifyExistingPair(
                            httpFactory = imposterHttpFactory,
                            existingServerCert = realGsId.cert,
                            expectedComputerUuid = "real-gs-uuid",
                            token = token,
                        )
                    },
                    runGameStreamPair = { pin, token ->
                        GameStreamPairRunner.runFullPair(
                            httpFactory = imposterHttpFactory,
                            pin = pin,
                            token = token,
                        )
                    },
                )
                assertTrue(
                    "#2 + S1: Imposter lying next=done without verified HTTPS GameStream cert must fail immediately without hanging in pm.pair, got $outcome",
                    outcome is HostAutoPairOutcome.UnexpectedDoneWithoutVerifiedStream,
                )
                assertEquals("Imposter must never reach /pair/complete", 0, imposterCompleteCalls.get())
                assertEquals(
                    "#2: Pin must stay realHostFp and NEVER be overwritten with imposterHostFp!",
                    realHostFp,
                    pinStore.getFingerprint("host-real"),
                )
            }
        }

        // Upgrade path (#1 + #2 + spec §已经在串流层面配过对的头显):
        // Real Mac is ALREADY paired at GameStream (exact realGsId.cert on HTTPS, PairStatus=1, uniqueid=real-gs-uuid)
        // and Host has NO record yet:
        // 1) /info -> 2) strict HTTPS pinned-cert verify passes -> 3) /pair WITH client cert returns next="done" ->
        // 4) GameStream pm.pair SKIPPED -> 5) /pair/complete WITH client cert returns 200 -> 6) trust saved -> /media succeeds!
        val events = CopyOnWriteArrayList<String>()
        val gsPairCalls = AtomicInteger(0)
        val registeredClientFps = CopyOnWriteArrayList<String>()
        val pinStore = InMemoryHostCertPinStore()

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
                    "/depth3d/v1/info" -> {
                        events += "info"
                        assertNull("Discovery/pre-pair /info must not send client cert", req.clientCert)
                        MockResponse(200, """{"v":1,"name":"书房 Mac","id":"host-real","os":"macos","stream":{"ready":true},"media":{"port":8890}}""")
                    }
                    "/depth3d/v1/pair" -> {
                        events += "pair"
                        assertNotNull("Verified upgrade /pair MUST present client cert", req.clientCert)
                        assertEquals(clientId.cert.serialNumber, req.clientCert!!.serialNumber)
                        assertEquals("Verified upgrade must send streamPaired=true", true, MiniJson.parseObject(req.body)!!.bool("streamPaired"))
                        assertNull("Trust must NOT be saved yet at /pair", pinStore.getFingerprint("host-real"))
                        MockResponse(200, """{"ok":true,"next":"done","ticket":"tk-upg-1"}""")
                    }
                    "/depth3d/v1/pair/complete" -> {
                        events += "pair_complete"
                        assertNotNull("/pair/complete MUST present client cert", req.clientCert)
                        val body = MiniJson.parseObject(req.body)!!
                        assertEquals("tk-upg-1", body.string("ticket"))
                        assertEquals("dev-1", body.string("deviceId"))
                        assertEquals("depth3d", body.string("device"))
                        assertNull("Trust must NOT be saved until /pair/complete returns 200", pinStore.getFingerprint("host-real"))
                        registeredClientFps += HttpHostProtocolClient.sha256Hex(req.clientCert!!)
                        MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                    }
                    "/depth3d/v1/media" -> {
                        events += "media"
                        // Spec #6: Host authenticates purely by client cert fingerprint, does NOT require X-Depth3d-Device
                        val fp = req.clientCert?.let { HttpHostProtocolClient.sha256Hex(it) }
                        if (fp != null && registeredClientFps.contains(fp)) {
                            MockResponse(200, """{"webdav":{"port":8890,"user":"depth3d","pass":"secret"}}""")
                        } else {
                            MockResponse(401, """{"error":"unpaired"}""")
                        }
                    }
                    else -> MockResponse(404, "{}")
                }
            }.use { realHost ->
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )
                val realHttpFactory = {
                    NvHTTP(
                        ComputerDetails.AddressTuple("127.0.0.1", realGs.httpPort),
                        0,
                        "0123456789ABCDEF",
                        realGsId.cert,
                        crypto,
                    )
                }

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
                    verifyExistingGameStreamPair = { token ->
                        events += "gs_https_verify"
                        GameStreamPairRunner.verifyExistingPair(
                            httpFactory = realHttpFactory,
                            existingServerCert = realGsId.cert,
                            expectedComputerUuid = "real-gs-uuid",
                            token = token,
                        )
                    },
                    runGameStreamPair = { pin, token ->
                        gsPairCalls.incrementAndGet()
                        GameStreamPairRunner.runFullPair(
                            httpFactory = realHttpFactory,
                            pin = pin,
                            token = token,
                        )
                    },
                )
                assertTrue("#1/#2: Real Mac upgrade must succeed via HTTPS verify -> /pair(next=done) -> /pair/complete, got $outcome", outcome is HostAutoPairOutcome.Paired)
                assertEquals("GameStream pm.pair must be skipped when next=done and HTTPS verify passed", 0, gsPairCalls.get())
                assertEquals(realHostFp, pinStore.getFingerprint("host-real"))
                assertEquals("real-gs-uuid", pinStore.getPairedTrust("host-real")?.computerUuid)

                val mediaRes = client.getMediaCredentials(
                    address = "127.0.0.1",
                    port = realHost.port,
                    hostId = "host-real",
                    deviceId = "dev-1",
                    expectedComputerUuid = "real-gs-uuid",
                    expectedGsCertFingerprint = HttpHostProtocolClient.sha256Hex(realGsId.cert),
                )
                assertTrue("#1: /media must succeed right after upgrade setup", mediaRes is MediaCredsResult.Available)
                assertEquals(
                    listOf("info", "gs_https_verify", "pair", "pair_complete", "media"),
                    events.toList(),
                )
            }
        }
    }

    @Test
    fun r4_6_certChangeBetweenDiscoveryAndPairPromptsCertificateChanged() = runBlocking {
        val certDiscovered = generateSelfSigned("host-discovered")
        val certAtPair = generateSelfSigned("host-changed-before-pair")
        val gsCert = generateSelfSigned("gs-cert")
        val clientId = generateSelfSigned("headset-r6")
        val fpDiscovered = HttpHostProtocolClient.sha256Hex(certDiscovered.cert)
        val fpAtPair = HttpHostProtocolClient.sha256Hex(certAtPair.cert)
        val pairCalls = AtomicInteger(0)

        LocalTlsServer(certAtPair) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac","id":"host-r6","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    pairCalls.incrementAndGet()
                    MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-r6"}""")
                }
                "/depth3d/v1/pair/complete" -> MockResponse(200, """{"ok":true,"media":null}""")
                else -> MockResponse(404, "{}")
            }
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )

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
    fun r4_11_b1_manualPinHostRunsUpgradeAndOnlyPersistsTrustAfterPairComplete200() = runBlocking {
        val certOld = generateSelfSigned("host-manual-old")
        val certNew = generateSelfSigned("host-manual-new")
        val gsCert = generateSelfSigned("gs-manual")
        val clientId = generateSelfSigned("headset-manual")
        val fpOld = HttpHostProtocolClient.sha256Hex(certOld.cert)
        val fpNew = HttpHostProtocolClient.sha256Hex(certNew.cert)
        val gsFp = HttpHostProtocolClient.sha256Hex(gsCert.cert)

        var infoId = "wrong-id"
        var pairCode = 403
        var completeCode = 409
        LocalTlsServer(certNew) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Manual Mac","id":"$infoId","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    assertNotNull("B1: Post-PIN upgrade /pair must present client cert", req.clientCert)
                    assertEquals("B1: Post-PIN upgrade must send streamPaired=true", true, MiniJson.parseObject(req.body)!!.bool("streamPaired"))
                    if (pairCode == 200) MockResponse(200, """{"ok":true,"next":"done","ticket":"tk-manual"}""")
                    else MockResponse(pairCode, """{"error":"rejected"}""")
                }
                "/depth3d/v1/pair/complete" -> {
                    assertNotNull("B1: /pair/complete must present client cert", req.clientCert)
                    if (completeCode == 200) MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                    else MockResponse(completeCode, """{"error":"not_paired"}""")
                }
                else -> MockResponse(404, "{}")
            }
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            pinStore.savePairedTrust("host-manual", HostPairedTrust(fpOld, "uuid-m", gsFp, "Manual Mac"))
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )

            // Mismatched /info id must reject and NOT save fpNew
            val badId = HostManualPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-manual",
                deviceName = "depth3d",
                deviceId = "dev-m",
                pin = "1111",
                computerUuid = "uuid-m",
                alreadyPairedGsCert = gsCert.cert,
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
                notPairedRetryDelaysMs = listOf(10L),
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
                deviceName = "depth3d",
                deviceId = "dev-m",
                pin = "1111",
                computerUuid = "uuid-m",
                alreadyPairedGsCert = gsCert.cert,
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.FailedState(PairingManager.PairState.PIN_WRONG) },
                notPairedRetryDelaysMs = listOf(10L),
            )
            assertTrue(gsFail is HostAutoPairOutcome.GameStreamFailed)
            assertEquals(fpOld, pinStore.getFingerprint("host-manual"))

            // B1: Manual PIN succeeds, but Host rejects /pair -> returns GameStreamPairedHostSetupIncomplete & NEVER updates pinStore!
            pairCode = 403
            val hostRejected = HostManualPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-manual",
                deviceName = "depth3d",
                deviceId = "dev-m",
                pin = "1111",
                computerUuid = "uuid-m",
                alreadyPairedGsCert = gsCert.cert,
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
                notPairedRetryDelaysMs = listOf(10L),
            )
            assertTrue("B1: Failed Host registration after manual PIN must NOT save trust, got $hostRejected", hostRejected is HostAutoPairOutcome.GameStreamPairedHostSetupIncomplete)
            assertEquals("B1: Pin must stay fpOld when Host /pair fails", fpOld, pinStore.getFingerprint("host-manual"))

            // B1: Manual PIN succeeds, /pair returns next="done", /pair/complete returns 409 -> STILL does NOT save fpNew!
            pairCode = 200
            completeCode = 409
            val completeFailed = HostManualPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-manual",
                deviceName = "depth3d",
                deviceId = "dev-m",
                pin = "1111",
                computerUuid = "uuid-m",
                alreadyPairedGsCert = gsCert.cert,
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
                notPairedRetryDelaysMs = listOf(10L),
            )
            assertTrue(completeFailed is HostAutoPairOutcome.GameStreamPairedHostSetupIncomplete)
            assertEquals("B1: Pin must stay fpOld when /pair/complete fails", fpOld, pinStore.getFingerprint("host-manual"))

            // B1 + #11: Manual PIN succeeds + Host /pair(next=done) + /pair/complete 200 -> NOW persists fpNew!
            completeCode = 200
            val ok = HostManualPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-manual",
                deviceName = "depth3d",
                deviceId = "dev-m",
                pin = "1111",
                computerUuid = "uuid-m",
                alreadyPairedGsCert = gsCert.cert,
                approvedNewFingerprint = fpNew,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsCert.cert) },
                notPairedRetryDelaysMs = listOf(10L),
            )
            assertTrue("#11 + B1: Manual-PIN + Host /pair/complete 200 must succeed, got $ok", ok is HostAutoPairOutcome.Paired)
            assertEquals("#11 + B1: Approved fingerprint must now be saved", fpNew, pinStore.getFingerprint("host-manual"))
            assertTrue(
                "#11: Subsequent /info without approvedNewFingerprint must succeed without re-prompting",
                client.getInfo("127.0.0.1", srv.port, "host-manual") is HostInfoResult.Ok,
            )
        }
    }

    @Test
    fun spec_twoStepPairingNonDeadlockingTimingAndNotPairedRetryAndMediaSync() = runBlocking {
        // Exercises the EXACT two-step protocol timing from docs/specs/host-protocol.md (51306f9):
        // 1) /info -> 2) /pair returns 200 {"ok":true,"next":"gamestream","ticket":"..."} IMMEDIATELY
        //    and hands the PIN to the GameStream server ONLY after /pair approves ->
        // 3) ONLY AFTER /pair returns does headset start GameStream pm.pair with the SAME PIN ->
        // 4) /pair/complete (with client cert + ticket) returns 409 not_paired twice, retries within 5s,
        //    then returns 200 {"ok":true,"media":{"port":8890}} once engine reflects the paired cert ->
        // 5) ONLY AFTER /pair/complete 200 is HostPairedTrust saved -> /media succeeds via client cert!
        val hostIdCert = generateSelfSigned("spec-mac-host")
        val gsIdCert = generateSelfSigned("spec-mac-gs")
        val clientId = generateSelfSigned("spec-headset-client")
        val crypto = cryptoProviderFor(clientId)
        val hostFp = HttpHostProtocolClient.sha256Hex(hostIdCert.cert)
        val pinStore = InMemoryHostCertPinStore()

        val events = CopyOnWriteArrayList<String>()
        val completeAttempts = AtomicInteger(0)
        val savedHostDevices = CopyOnWriteArrayList<String>()
        val activeTicket = "tk-live-987654"

        StatefulMockGameStreamServer(
            serverId = gsIdCert,
            expectedPin = "UNUSED_UNTIL_HOST_PAIR_APPROVES",
            blockFirstGetServerCert = false,
            uniqueId = "spec-gs-uuid",
            requirePairUnlockBeforeAcceptingPin = true,
            onPairStarted = { events += "gs_pair_started" },
            onPairFinished = { events += "gs_pair_finished" },
        ).use { gsSrv ->
            LocalTlsServer(hostIdCert) { req ->
                when (req.path) {
                    "/depth3d/v1/info" -> {
                        events += "info"
                        assertNull(req.clientCert)
                        MockResponse(200, """{"v":1,"name":"工作室 Mac","id":"host-spec","os":"macOS 15.1","stream":{"ready":true},"media":null}""")
                    }
                    "/depth3d/v1/pair" -> {
                        val body = MiniJson.parseObject(req.body)!!
                        val pin = body.string("pin")!!
                        assertNotNull("/pair must ALWAYS present client cert (3676b2b)", req.clientCert)
                        assertEquals("Unpaired headset must send streamPaired=false", false, body.bool("streamPaired"))
                        // Hand PIN to GameStream engine and return IMMEDIATELY (no deadlock!)
                        gsSrv.unlockPairingForPin(pin)
                        events += "pair_returned_gamestream"
                        MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"$activeTicket"}""")
                    }
                    "/depth3d/v1/pair/complete" -> {
                        val cert = req.clientCert
                        if (cert == null) {
                            MockResponse(401, """{"error":"no_client_cert"}""")
                        } else {
                            val body = MiniJson.parseObject(req.body)!!
                            assertEquals(activeTicket, body.string("ticket"))
                            assertEquals("headset-spec-1", body.string("deviceId"))
                            assertEquals("客厅的 depth3d", body.string("device"))
                            assertTrue("GameStream pairing must have completed before /pair/complete", gsSrv.isClientCertPaired(cert))
                            assertNull("Trust must NOT be persisted before /pair/complete returns 200", pinStore.getFingerprint("host-spec"))
                            val attempt = completeAttempts.incrementAndGet()
                            if (attempt <= 2) {
                                events += "complete_409_$attempt"
                                MockResponse(409, """{"error":"not_paired"}""")
                            } else {
                                events += "complete_200"
                                savedHostDevices += HttpHostProtocolClient.sha256Hex(cert)
                                MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                            }
                        }
                    }
                    "/depth3d/v1/media" -> {
                        // Spec #6: authenticates purely via mutual TLS client cert, does not require X-Depth3d-Device
                        val fp = req.clientCert?.let { HttpHostProtocolClient.sha256Hex(it) }
                        if (fp != null && savedHostDevices.contains(fp)) {
                            events += "media_200"
                            MockResponse(200, """{"webdav":{"port":8890,"user":"depth3d","pass":"pw-live"}}""")
                        } else {
                            MockResponse(401, """{"error":"unpaired"}""")
                        }
                    }
                    else -> MockResponse(404, "{}")
                }
            }.use { hostSrv ->
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )
                assertTrue(
                    "Default /pair read timeout must be >= 75s per host-protocol.md",
                    client.configuredPairReadTimeoutMs >= HttpHostProtocolClient.MIN_PAIR_READ_TIMEOUT_MS,
                )

                val outcome = HostAutoPairOrchestrator.execute(
                    hostApi = client,
                    pinStore = pinStore,
                    address = "127.0.0.1",
                    controlPort = hostSrv.port,
                    hostId = "host-spec",
                    deviceName = "客厅的 depth3d",
                    deviceId = "headset-spec-1",
                    pin = "6284",
                    computerUuid = "spec-gs-uuid",
                    alreadyPairedGsCert = null,
                    approvedNewFingerprint = null,
                    savedName = "工作室 Mac",
                    token = CallCancelToken(),
                    runGameStreamPair = { pin, token ->
                        GameStreamPairRunner.runFullPair(
                            httpFactory = {
                                NvHTTP(
                                    ComputerDetails.AddressTuple("127.0.0.1", gsSrv.httpPort),
                                    0,
                                    "0123456789ABCDEF",
                                    null,
                                    crypto,
                                )
                            },
                            pin = pin,
                            token = token,
                        )
                    },
                    notPairedRetryDelaysMs = listOf(20L, 30L, 40L),
                )

                assertTrue("Two-step pairing must succeed, got $outcome", outcome is HostAutoPairOutcome.Paired)
                val paired = outcome as HostAutoPairOutcome.Paired
                assertEquals("Media port from /pair/complete 200 must populate HostInfo", 8890, paired.hostInfo.media?.port)
                assertEquals(hostFp, pinStore.getFingerprint("host-spec"))
                assertEquals(HttpHostProtocolClient.sha256Hex(gsIdCert.cert), pinStore.getPairedTrust("host-spec")?.gsCertFingerprint)

                val media = client.getMediaCredentials(
                    address = "127.0.0.1",
                    port = hostSrv.port,
                    hostId = "host-spec",
                    deviceId = "headset-spec-1",
                    expectedComputerUuid = "spec-gs-uuid",
                    expectedGsCertFingerprint = HttpHostProtocolClient.sha256Hex(gsIdCert.cert),
                )
                assertTrue("Media sync must succeed right after /pair/complete 200, got $media", media is MediaCredsResult.Available)
                assertEquals(8890, (media as MediaCredsResult.Available).creds.port)
                assertEquals(
                    listOf(
                        "info",
                        "pair_returned_gamestream",
                        "gs_pair_started",
                        "gs_pair_finished",
                        "complete_409_1",
                        "complete_409_2",
                        "complete_200",
                        "media_200",
                    ),
                    events.toList(),
                )
            }
        }
    }

    @Test
    fun spec_pairCompleteErrorsNeverPersistTrustAndNotPairedExhaustsRetries() = runBlocking {
        val hostIdCert = generateSelfSigned("spec-err-host")
        val gsIdCert = generateSelfSigned("spec-err-gs")
        val clientId = generateSelfSigned("spec-err-client")
        var completeCode = 409
        var completeBody = """{"error":"not_paired"}"""
        val completeCalls = AtomicInteger(0)

        LocalTlsServer(hostIdCert) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac","id":"host-err","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-err"}""")
                "/depth3d/v1/pair/complete" -> {
                    completeCalls.incrementAndGet()
                    MockResponse(completeCode, completeBody)
                }
                else -> MockResponse(404, "{}")
            }
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )
            val retries = listOf(10L, 10L, 10L)

            suspend fun runOnce(): HostAutoPairOutcome = HostAutoPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-err",
                deviceName = "depth3d",
                deviceId = "dev-1",
                pin = "1234",
                computerUuid = "uuid-err",
                alreadyPairedGsCert = null,
                approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsIdCert.cert) },
                notPairedRetryDelaysMs = retries,
            )

            // 1) Persistent 409 not_paired retries 1 + retries.size times then fails without saving trust
            //    and attaches pendingComplete so Retry can re-run ONLY /pair/complete while ticket is valid (S4)!
            completeCalls.set(0)
            completeCode = 409
            completeBody = """{"error":"not_paired"}"""
            val notPairedOut = runOnce()
            assertTrue(notPairedOut is HostAutoPairOutcome.PairCompleteFailed)
            assertEquals(PairCompleteResult.NotPaired, (notPairedOut as HostAutoPairOutcome.PairCompleteFailed).result)
            assertNotNull("S4: Exhausted 409 must keep pendingComplete for direct /pair/complete retry", notPairedOut.pendingComplete)
            assertEquals(1 + retries.size, completeCalls.get())
            assertNull(pinStore.getFingerprint("host-err"))

            // 2) 410 ticket expired fails immediately (1 call) without saving trust
            completeCalls.set(0)
            completeCode = 410
            completeBody = """{"error":"ticket_expired"}"""
            assertEquals(HostAutoPairOutcome.PairCompleteFailed(PairCompleteResult.TicketExpired), runOnce())
            assertEquals(1, completeCalls.get())
            assertNull(pinStore.getFingerprint("host-err"))

            // 3) 401 unauthorized, 403 forbidden, 426 upgrade required all fail without saving trust
            completeCode = 401
            completeBody = """{"error":"no_cert"}"""
            assertEquals(HostAutoPairOutcome.PairCompleteFailed(PairCompleteResult.Unauthorized), runOnce())
            assertNull(pinStore.getFingerprint("host-err"))

            completeCode = 403
            completeBody = """{"error":"ip_mismatch"}"""
            assertEquals(HostAutoPairOutcome.PairCompleteFailed(PairCompleteResult.Forbidden), runOnce())
            assertNull(pinStore.getFingerprint("host-err"))

            completeCode = 426
            completeBody = """{"error":"upgrade_required"}"""
            assertEquals(HostAutoPairOutcome.UpgradeRequired, runOnce())
            assertNull(pinStore.getFingerprint("host-err"))
        }
    }

    /** Deterministic virtual clock so 45s / 60s / 75s timing tests run in milliseconds with zero real sleeping (M6). */
    private class VirtualClock(initialMs: Long = 100_000L) {
        private val now = java.util.concurrent.atomic.AtomicLong(initialMs)
        private val waiters = CopyOnWriteArrayList<Pair<Long, kotlinx.coroutines.CompletableDeferred<Unit>>>()

        fun nowMs(): Long = now.get()

        suspend fun delay(ms: Long) {
            if (ms <= 0L) return
            val target = now.get() + ms
            val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            waiters.add(target to deferred)
            if (now.get() >= target) {
                deferred.complete(Unit)
                waiters.remove(target to deferred)
            }
            try {
                deferred.await()
            } finally {
                waiters.remove(target to deferred)
            }
        }

        fun advanceBy(ms: Long) {
            val current = now.addAndGet(ms)
            val ready = waiters.filter { it.first <= current }
            for (item in ready) {
                waiters.remove(item)
                item.second.complete(Unit)
            }
        }

        fun awaitWaiter(timeoutMs: Long = 2_000L): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (waiters.isNotEmpty()) return true
                Thread.sleep(5)
            }
            return waiters.isNotEmpty()
        }
    }

    @Test
    fun r5_s1_m6_gameStream75sTimeoutCleansUpBlockingGetServerCertAndNextDoneNeverHangs() = runBlocking {
        val gsIdCert = generateSelfSigned("s1-gs")
        val clientId = generateSelfSigned("s1-client")
        val crypto = cryptoProviderFor(clientId)
        val clock = VirtualClock()

        // M6 + S1: Realistic mock GameStream server BLOCKS getservercert indefinitely when PIN is never submitted!
        // Virtual clock advances 75_000 ms -> GameStreamPairRunner.runFullPair MUST interrupt the blocking
        // socket, run NonCancellable /unpair cleanup, and return TimedOut instead of hanging forever!
        val pairStarted = CountDownLatch(1)
        StatefulMockGameStreamServer(
            serverId = gsIdCert,
            expectedPin = "NEVER_SUBMITTED",
            blockFirstGetServerCert = false,
            requirePairUnlockBeforeAcceptingPin = true,
            onPairStarted = { pairStarted.countDown() },
        ).use { gsSrv ->
            var outcome: GameStreamPairOutcome? = null
            val job = launch(Dispatchers.IO) {
                outcome = GameStreamPairRunner.runFullPair(
                    httpFactory = {
                        NvHTTP(
                            ComputerDetails.AddressTuple("127.0.0.1", gsSrv.httpPort),
                            0,
                            "0123456789ABCDEF",
                            null,
                            crypto,
                        )
                    },
                    pin = "1234",
                    token = CallCancelToken(),
                    pairTimeoutMs = GameStreamPairRunner.DEFAULT_GAMESTREAM_PAIR_TIMEOUT_MS,
                    delayFn = { clock.delay(it) },
                )
            }

            assertTrue("getservercert must reach server and block", pairStarted.await(3, TimeUnit.SECONDS))
            assertTrue("75s timeout watchdog should be waiting on virtual clock", clock.awaitWaiter())

            // Advancing 60s (< 75s) must still keep pairing active
            clock.advanceBy(60_000L)
            Thread.sleep(50)
            assertTrue("Should still be waiting at 60s (< 75s)", job.isActive)

            // Advancing another 15s (total 75s) triggers the 75s cap!
            clock.advanceBy(15_000L)
            val finished = withTimeoutOrNull(3_000L) { job.join(); true }
            assertEquals("S1: 75s timeout must abort blocking getservercert immediately", true, finished)
            assertEquals(GameStreamPairOutcome.TimedOut, outcome)
            assertTrue("S1: Timeout must send NonCancellable /unpair cleanup", gsSrv.unpairCount.get() >= 1)
            assertFalse("Server pairing lock must be cleared after timeout /unpair", gsSrv.pairingInProgress.get())
        }
    }

    @Test
    fun r5_s4_retryAfterNotPairedReusesValidTicketWithoutReCallingPair() = runBlocking {
        val hostIdCert = generateSelfSigned("s4-host")
        val gsIdCert = generateSelfSigned("s4-gs")
        val clientId = generateSelfSigned("s4-client")
        val hostFp = HttpHostProtocolClient.sha256Hex(hostIdCert.cert)
        val clock = VirtualClock(500_000L)
        val pairCalls = AtomicInteger(0)
        val completeCalls = AtomicInteger(0)
        var completeCode = 409

        LocalTlsServer(hostIdCert) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac S4","id":"host-s4","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    pairCalls.incrementAndGet()
                    MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-s4-reusable"}""")
                }
                "/depth3d/v1/pair/complete" -> {
                    completeCalls.incrementAndGet()
                    val body = MiniJson.parseObject(req.body)!!
                    assertEquals("tk-s4-reusable", body.string("ticket"))
                    if (completeCode == 200) MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                    else MockResponse(completeCode, """{"error":"not_paired"}""")
                }
                else -> MockResponse(404, "{}")
            }
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )

            // Initial run: /pair succeeds (1 call), GameStream succeeds, /pair/complete gets 409 exhausting retries
            val first = HostAutoPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-s4",
                deviceName = "depth3d",
                deviceId = "dev-s4",
                pin = "1234",
                computerUuid = "uuid-s4",
                alreadyPairedGsCert = null,
                approvedNewFingerprint = hostFp,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsIdCert.cert) },
                notPairedRetryDelaysMs = listOf(10L, 10L),
                clockMs = { clock.nowMs() },
            )
            assertTrue(first is HostAutoPairOutcome.PairCompleteFailed)
            val failed = first as HostAutoPairOutcome.PairCompleteFailed
            assertEquals(PairCompleteResult.NotPaired, failed.result)
            val pending = failed.pendingComplete
            assertNotNull("S4: Must return PendingPairComplete while ticket is valid", pending)
            assertEquals("S4: Must preserve approvedNewFingerprint", hostFp, pending!!.approvedNewFingerprint)
            assertTrue("S4: Ticket must still be valid 10s later", pending.isTicketLikelyValid(clock.nowMs() + 10_000L))
            assertFalse("S4: Ticket must be considered expired after 120s", pending.isTicketLikelyValid(clock.nowMs() + 120_000L))
            assertEquals(1, pairCalls.get())
            assertNull(pinStore.getFingerprint("host-s4"))

            // Now user taps Retry -> ONLY calls /pair/complete (does NOT re-call /pair or re-popup Mac!)
            completeCode = 200
            val retried = HostAutoPairOrchestrator.completeWithRetries(
                hostApi = client,
                pinStore = pinStore,
                pending = pending,
                token = CallCancelToken(),
                notPairedRetryDelaysMs = listOf(10L),
            )
            assertTrue("S4: Direct /pair/complete retry must succeed, got $retried", retried is HostAutoPairOutcome.Paired)
            assertEquals("S4: Retry with valid ticket MUST NOT call /pair again", 1, pairCalls.get())
            assertEquals(hostFp, pinStore.getFingerprint("host-s4"))
        }
    }

    @Test
    fun r5_s5_cancelDisconnectsServerSocketImmediatelyAndPostCancelBusyAutoRetriesOnce() = runBlocking {
        val hostIdCert = generateSelfSigned("s5-host")
        val gsIdCert = generateSelfSigned("s5-gs")
        val clientId = generateSelfSigned("s5-client")
        val hostFp = HttpHostProtocolClient.sha256Hex(hostIdCert.cert)
        val pairReqArrived = CountDownLatch(1)
        val serverObservedDisconnect = AtomicBoolean(false)
        val disconnectCheckDone = CountDownLatch(1)

        // Part A: Verify cancelling an in-flight /pair truly tears down the TCP/TLS socket on the server!
        LocalTlsServer(hostIdCert) { req ->
            assertEquals("/depth3d/v1/pair", req.path)
            pairReqArrived.countDown()
            // Wait up to 3s to observe TCP FIN/RST from headset's cancel!
            val disconnected = req.awaitPeerDisconnect(3_000L)
            serverObservedDisconnect.set(disconnected)
            disconnectCheckDone.countDown()
            MockResponse(408, """{"error":"timeout"}""")
        }.use { srv ->
            val client = HttpHostProtocolClient(
                pinStore = InMemoryHostCertPinStore(),
                clientIdentityProvider = { clientId.cert to clientId.key },
            )
            val token = CallCancelToken()
            val bg = Executors.newSingleThreadExecutor()
            try {
                val fut = bg.submit<AutoPairResult> {
                    client.requestAutoPair("127.0.0.1", srv.port, "host-s5", "depth3d", "dev-1", "1234", hostFp, cancelToken = token)
                }
                assertTrue(pairReqArrived.await(3, TimeUnit.SECONDS))
                val t0 = System.nanoTime()
                token.cancel()
                assertEquals(AutoPairResult.Cancelled, fut.get(3, TimeUnit.SECONDS))
                assertTrue(disconnectCheckDone.await(3, TimeUnit.SECONDS))
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                assertTrue("S5: Host server MUST observe real socket disconnect on cancel", serverObservedDisconnect.get())
                assertTrue("S5: Socket teardown must be immediate (< 1500ms), took ${elapsedMs}ms", elapsedMs < 1500L)
            } finally {
                bg.shutdownNow()
            }
        }

        // Part B: Immediate retry after cancel gets transient 429 busy -> waits retryAfterSec (virtual clock)
        // and automatically retries /pair ONCE and succeeds!
        val clock = VirtualClock()
        val pairAttempts = AtomicInteger(0)
        LocalTlsServer(hostIdCert) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac S5","id":"host-s5","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    if (pairAttempts.incrementAndGet() == 1) {
                        MockResponse(429, """{"error":"busy","retryAfterSec":2}""")
                    } else {
                        MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-s5-ok"}""")
                    }
                }
                "/depth3d/v1/pair/complete" -> MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                else -> MockResponse(404, "{}")
            }
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )
            var outcome: HostAutoPairOutcome? = null
            val job = launch(Dispatchers.IO) {
                outcome = HostAutoPairOrchestrator.execute(
                    hostApi = client,
                    pinStore = pinStore,
                    address = "127.0.0.1",
                    controlPort = srv.port,
                    hostId = "host-s5",
                    deviceName = "depth3d",
                    deviceId = "dev-1",
                    pin = "5555",
                    computerUuid = "uuid-s5",
                    alreadyPairedGsCert = null,
                    approvedNewFingerprint = null,
                    justCancelledPreviousPair = true,
                    token = CallCancelToken(),
                    runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsIdCert.cert) },
                    delayFn = { clock.delay(it) },
                )
            }
            assertTrue("Should pause on virtual clock waiting for busy retryAfterSec=2s", clock.awaitWaiter())
            clock.advanceBy(2_000L)
            job.join()
            assertTrue("S5: Post-cancel busy must auto-retry once and succeed, got $outcome", outcome is HostAutoPairOutcome.Paired)
            assertEquals("Expected exactly 2 /pair attempts (1 busy + 1 auto retry)", 2, pairAttempts.get())
        }
    }

    @Test
    fun r5_m4_blankComputerUuidRefusesToPersistTrust() = runBlocking {
        val hostIdCert = generateSelfSigned("m4-host")
        val gsIdCert = generateSelfSigned("m4-gs")
        val clientId = generateSelfSigned("m4-client")
        val pairCalls = AtomicInteger(0)

        LocalTlsServer(hostIdCert) { req ->
            if (req.path == "/depth3d/v1/pair") pairCalls.incrementAndGet()
            MockResponse(200, """{"v":1,"name":"Mac","id":"host-m4","os":"macos","stream":{"ready":true}}""")
        }.use { srv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(
                pinStore = pinStore,
                clientIdentityProvider = { clientId.cert to clientId.key },
            )
            val res = HostAutoPairOrchestrator.execute(
                hostApi = client,
                pinStore = pinStore,
                address = "127.0.0.1",
                controlPort = srv.port,
                hostId = "host-m4",
                deviceName = "depth3d",
                deviceId = "dev-1",
                pin = "1234",
                computerUuid = "",
                alreadyPairedGsCert = null,
                approvedNewFingerprint = null,
                token = CallCancelToken(),
                runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsIdCert.cert) },
            )
            assertTrue("M4: Blank computerUuid must fail cleanly, got $res", res is HostAutoPairOutcome.PairCompleteFailed)
            assertEquals(0, pairCalls.get())
            assertNull("M4: Blank computerUuid must never persist trust", pinStore.getFingerprint("host-m4"))
        }
    }

    @Test
    fun r5_m6_virtualClockPairBlocks45sAndLateGameStreamStartAnd60sWindowExpiry() = runBlocking {
        val hostIdCert = generateSelfSigned("m6-host")
        val gsIdCert = generateSelfSigned("m6-gs")
        val clientId = generateSelfSigned("m6-client")
        val crypto = cryptoProviderFor(clientId)
        val clock = VirtualClock(1_000_000L)

        // Case A + B: /pair blocks 45 virtual seconds waiting for user to click Allow on Mac -> returns next="gamestream";
        // then GameStream pairing starts 30 virtual seconds late (still inside the 60s Host PIN window) -> succeeds!
        val pairRequestEntered = CountDownLatch(1)
        val releasePairAfter45s = CountDownLatch(1)
        StatefulMockGameStreamServer(
            serverId = gsIdCert,
            expectedPin = "UNLOCK_LATER",
            blockFirstGetServerCert = false,
            uniqueId = "uuid-m6",
            requirePairUnlockBeforeAcceptingPin = true,
        ).use { gsSrv ->
            var pinWindowExpiresAtMs = 0L
            LocalTlsServer(hostIdCert) { req ->
                when (req.path) {
                    "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac M6","id":"host-m6","os":"macos","stream":{"ready":true}}""")
                    "/depth3d/v1/pair" -> {
                        val pin = MiniJson.parseObject(req.body)!!.string("pin")!!
                        pairRequestEntered.countDown()
                        releasePairAfter45s.await(5, TimeUnit.SECONDS)
                        pinWindowExpiresAtMs = clock.nowMs() + 60_000L
                        gsSrv.unlockPairingForPin(pin)
                        MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"tk-m6"}""")
                    }
                    "/depth3d/v1/pair/complete" -> {
                        val validCert = req.clientCert != null && gsSrv.isClientCertPaired(req.clientCert)
                        if (validCert) MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                        else MockResponse(409, """{"error":"not_paired"}""")
                    }
                    else -> MockResponse(404, "{}")
                }
            }.use { hostSrv ->
                val pinStore = InMemoryHostCertPinStore()
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )
                var outcome: HostAutoPairOutcome? = null
                val job = launch(Dispatchers.IO) {
                    outcome = HostAutoPairOrchestrator.execute(
                        hostApi = client,
                        pinStore = pinStore,
                        address = "127.0.0.1",
                        controlPort = hostSrv.port,
                        hostId = "host-m6",
                        deviceName = "depth3d",
                        deviceId = "dev-m6",
                        pin = "4242",
                        computerUuid = "uuid-m6",
                        alreadyPairedGsCert = null,
                        approvedNewFingerprint = null,
                        token = CallCancelToken(),
                        runGameStreamPair = { pin, token ->
                            // Simulate GameStream pairing starting 30 virtual seconds late (still within 60s window!)
                            clock.delay(30_000L)
                            assertTrue("Must still be inside 60s Host PIN window", clock.nowMs() < pinWindowExpiresAtMs)
                            GameStreamPairRunner.runFullPair(
                                httpFactory = {
                                    NvHTTP(
                                        ComputerDetails.AddressTuple("127.0.0.1", gsSrv.httpPort),
                                        0,
                                        "0123456789ABCDEF",
                                        null,
                                        crypto,
                                    )
                                },
                                pin = pin,
                                token = token,
                                delayFn = { clock.delay(it) },
                            )
                        },
                        clockMs = { clock.nowMs() },
                        delayFn = { clock.delay(it) },
                    )
                }

                // Wait for /pair to arrive, advance virtual clock by 45s (simulating slow user), then allow!
                assertTrue(pairRequestEntered.await(3, TimeUnit.SECONDS))
                clock.advanceBy(45_000L)
                releasePairAfter45s.countDown()

                // Now GameStream starts 30 virtual seconds late -> advance virtual clock by 30s!
                assertTrue(clock.awaitWaiter())
                clock.advanceBy(30_000L)
                job.join()

                assertTrue("M6: 45s /pair wait + 30s late GameStream start must succeed, got $outcome", outcome is HostAutoPairOutcome.Paired)
                assertNotNull(pinStore.getFingerprint("host-m6"))
            }
        }

        // Case C: 60-second Host confirmation window expires -> Host replies 408 timeout -> fails cleanly without trust!
        val timeoutEntered = CountDownLatch(1)
        val releaseTimeoutAt60s = CountDownLatch(1)
        LocalTlsServer(hostIdCert) { req ->
            when (req.path) {
                "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac M6","id":"host-m6-exp","os":"macos","stream":{"ready":true}}""")
                "/depth3d/v1/pair" -> {
                    timeoutEntered.countDown()
                    releaseTimeoutAt60s.await(5, TimeUnit.SECONDS)
                    MockResponse(408, """{"error":"timeout"}""")
                }
                else -> MockResponse(404, "{}")
            }
        }.use { hostSrv ->
            val pinStore = InMemoryHostCertPinStore()
            val client = HttpHostProtocolClient(pinStore = pinStore, clientIdentityProvider = { clientId.cert to clientId.key })
            var outcome: HostAutoPairOutcome? = null
            val job = launch(Dispatchers.IO) {
                outcome = HostAutoPairOrchestrator.execute(
                    hostApi = client,
                    pinStore = pinStore,
                    address = "127.0.0.1",
                    controlPort = hostSrv.port,
                    hostId = "host-m6-exp",
                    deviceName = "depth3d",
                    deviceId = "dev-m6",
                    pin = "9999",
                    computerUuid = "uuid-m6",
                    alreadyPairedGsCert = null,
                    approvedNewFingerprint = null,
                    token = CallCancelToken(),
                    runGameStreamPair = { _, _ -> GameStreamPairOutcome.Paired(gsIdCert.cert) },
                    clockMs = { clock.nowMs() },
                    delayFn = { clock.delay(it) },
                )
            }
            assertTrue(timeoutEntered.await(3, TimeUnit.SECONDS))
            clock.advanceBy(60_000L)
            releaseTimeoutAt60s.countDown()
            job.join()

            assertEquals(HostAutoPairOutcome.PairRejectedOrFailed(AutoPairResult.TimedOut, null), outcome)
            assertNull("60s timeout must never persist trust", pinStore.getFingerprint("host-m6-exp"))
        }
    }

    @Test
    fun r5_m7_cancelDuringUpgradePathNeverCallsGameStreamUnpair() = runBlocking {
        val realGsId = generateSelfSigned("m7-gs")
        val realHostId = generateSelfSigned("m7-host")
        val clientId = generateSelfSigned("m7-client")
        val crypto = cryptoProviderFor(clientId)
        val pairBlocked = CountDownLatch(1)
        val releasePair = CountDownLatch(1)
        var mode = "block_pair"

        StatefulMockGameStreamServer(
            serverId = realGsId,
            expectedPin = "unused",
            blockFirstGetServerCert = false,
            uniqueId = "real-gs-uuid",
            httpPairStatus = 0,
            httpsPairStatus = 1,
        ).use { realGs ->
            LocalTlsServer(realHostId) { req ->
                when (req.path) {
                    "/depth3d/v1/info" -> MockResponse(200, """{"v":1,"name":"Mac M7","id":"host-m7","os":"macos","stream":{"ready":true}}""")
                    "/depth3d/v1/pair" -> {
                        if (mode == "block_pair") {
                            pairBlocked.countDown()
                            releasePair.await(5, TimeUnit.SECONDS)
                            MockResponse(408, """{"error":"timeout"}""")
                        } else {
                            MockResponse(200, """{"ok":true,"next":"done","ticket":"tk-m7"}""")
                        }
                    }
                    "/depth3d/v1/pair/complete" -> MockResponse(409, """{"error":"not_paired"}""")
                    else -> MockResponse(404, "{}")
                }
            }.use { realHost ->
                val pinStore = InMemoryHostCertPinStore()
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )
                val httpFactory = {
                    NvHTTP(
                        ComputerDetails.AddressTuple("127.0.0.1", realGs.httpPort),
                        0,
                        "0123456789ABCDEF",
                        realGsId.cert,
                        crypto,
                    )
                }

                // Cancel Case 1: Cancel while waiting for Mac Allow on /pair during upgrade!
                val token1 = CallCancelToken()
                var out1: HostAutoPairOutcome? = null
                val job1 = launch(Dispatchers.IO) {
                    out1 = HostAutoPairOrchestrator.execute(
                        hostApi = client,
                        pinStore = pinStore,
                        address = "127.0.0.1",
                        controlPort = realHost.port,
                        hostId = "host-m7",
                        deviceName = "depth3d",
                        deviceId = "dev-m7",
                        pin = "1234",
                        computerUuid = "real-gs-uuid",
                        alreadyPairedGsCert = realGsId.cert,
                        approvedNewFingerprint = null,
                        token = token1,
                        verifyExistingGameStreamPair = { t ->
                            GameStreamPairRunner.verifyExistingPair(httpFactory, realGsId.cert, "real-gs-uuid", t)
                        },
                        runGameStreamPair = { pin, t -> GameStreamPairRunner.runFullPair(httpFactory, pin, t) },
                    )
                }
                assertTrue(pairBlocked.await(3, TimeUnit.SECONDS))
                token1.cancel()
                job1.join()
                releasePair.countDown()
                assertEquals(HostAutoPairOutcome.Cancelled, out1)
                assertEquals("M7: Cancelling upgrade during /pair must NEVER call /unpair on GameStream!", 0, realGs.unpairCount.get())

                // Cancel Case 2: Cancel while retrying 409 on /pair/complete during upgrade!
                mode = "complete_409"
                val clock = VirtualClock()
                val token2 = CallCancelToken()
                var out2: HostAutoPairOutcome? = null
                val job2 = launch(Dispatchers.IO) {
                    out2 = HostAutoPairOrchestrator.execute(
                        hostApi = client,
                        pinStore = pinStore,
                        address = "127.0.0.1",
                        controlPort = realHost.port,
                        hostId = "host-m7",
                        deviceName = "depth3d",
                        deviceId = "dev-m7",
                        pin = "1234",
                        computerUuid = "real-gs-uuid",
                        alreadyPairedGsCert = realGsId.cert,
                        approvedNewFingerprint = null,
                        token = token2,
                        verifyExistingGameStreamPair = { t ->
                            GameStreamPairRunner.verifyExistingPair(httpFactory, realGsId.cert, "real-gs-uuid", t)
                        },
                        runGameStreamPair = { pin, t -> GameStreamPairRunner.runFullPair(httpFactory, pin, t) },
                        notPairedRetryDelaysMs = listOf(10_000L),
                        delayFn = { clock.delay(it) },
                    )
                }
                assertTrue(clock.awaitWaiter())
                token2.cancel()
                job2.cancel()
                job2.join()
                assertEquals("M7: Cancelling upgrade during /pair/complete must NEVER call /unpair on GameStream!", 0, realGs.unpairCount.get())
                assertNull(pinStore.getFingerprint("host-m7"))
            }
        }
    }

    @Test
    fun r5_3676b2b_locallyRemovedComputerStreamPairedFalseClearsDuplicateEngineCertAndTicketBindsClientCert() = runBlocking {
        // Exercises docs/specs/host-protocol.md (3676b2b):
        // 1) Built-in streaming engine rejects duplicate client certs at pairchallenge (`rejectDuplicateClientCert=true`).
        // 2) Headset previously paired (so engine ALREADY has clientId.cert), then locally removed the computer.
        // 3) Re-pairing sends clientId.cert + `streamPaired: false` on /pair.
        // 4) Host deletes all prior entries for clientId.cert in engine, binds ticket to sha256(clientId.cert),
        //    and returns next="gamestream" -> full GameStream pairing succeeds cleanly without duplicate rejection!
        // 5) /pair/complete uses the SAME client cert -> Host verifies ticket + cert binding -> 200 -> trust saved!
        // 6) Also verifies client rejects /pair/complete if client cert changed after /pair (ticket cert binding).
        val hostIdCert = generateSelfSigned("spec3676-host")
        val gsIdCert = generateSelfSigned("spec3676-gs")
        val clientId = generateSelfSigned("spec3676-client")
        val otherClientId = generateSelfSigned("spec3676-other-client")
        val crypto = cryptoProviderFor(clientId)
        val hostFp = HttpHostProtocolClient.sha256Hex(hostIdCert.cert)
        val clientFp = HttpHostProtocolClient.sha256Hex(clientId.cert)

        val ticketBindings = java.util.concurrent.ConcurrentHashMap<String, String>()
        val usedTickets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val savedDevices = CopyOnWriteArrayList<String>()

        StatefulMockGameStreamServer(
            serverId = gsIdCert,
            expectedPin = "UNLOCK_BY_HOST",
            blockFirstGetServerCert = false,
            uniqueId = "gs-uuid-3676",
            requirePairUnlockBeforeAcceptingPin = true,
            rejectDuplicateClientCert = true,
        ).use { gsSrv ->
            // Pre-populate engine with clientId.cert (headset removed PC locally, Mac engine still has old entry!)
            gsSrv.pairedClientCertFps.add(clientFp)
            assertTrue(gsSrv.isClientCertPaired(clientId.cert))

            LocalTlsServer(hostIdCert) { req ->
                when (req.path) {
                    "/depth3d/v1/info" -> MockResponse(
                        200,
                        """{"v":1,"name":"Mac 3676","id":"host-3676","os":"macOS 15.1","stream":{"ready":true},"media":{"port":8890}}""",
                    )
                    "/depth3d/v1/pair" -> {
                        val cert = req.clientCert
                            ?: return@LocalTlsServer MockResponse(401, """{"error":"client_cert_required"}""")
                        val body = MiniJson.parseObject(req.body)!!
                        val pin = body.string("pin")!!
                        val streamPaired = body.bool("streamPaired")
                            ?: return@LocalTlsServer MockResponse(400, """{"error":"missing_stream_paired"}""")
                        val fp = HttpHostProtocolClient.sha256Hex(cert)
                        val ticket = "tk-3676-${ticketBindings.size + 1}"
                        ticketBindings[ticket] = fp

                        if (streamPaired && gsSrv.isClientCertPaired(cert)) {
                            MockResponse(200, """{"ok":true,"next":"done","ticket":"$ticket"}""")
                        } else {
                            // Spec 3676b2b: Host deletes ALL existing entries for this client cert in engine
                            // BEFORE returning gamestream so re-pairing won't fail with duplicate cert!
                            gsSrv.removeClientCertEntries(cert)
                            gsSrv.unlockPairingForPin(pin)
                            MockResponse(200, """{"ok":true,"next":"gamestream","ticket":"$ticket"}""")
                        }
                    }
                    "/depth3d/v1/pair/complete" -> {
                        val cert = req.clientCert
                            ?: return@LocalTlsServer MockResponse(401, """{"error":"client_cert_required"}""")
                        val body = MiniJson.parseObject(req.body)!!
                        val ticket = body.string("ticket")!!
                        val boundFp = ticketBindings[ticket]
                        val fp = HttpHostProtocolClient.sha256Hex(cert)
                        when {
                            boundFp == null || !usedTickets.add(ticket) -> MockResponse(410, """{"error":"ticket_expired"}""")
                            !boundFp.equals(fp, ignoreCase = true) -> MockResponse(403, """{"error":"cert_mismatch"}""")
                            !gsSrv.isClientCertPaired(cert) -> MockResponse(409, """{"error":"not_paired"}""")
                            else -> {
                                savedDevices += fp
                                MockResponse(200, """{"ok":true,"media":{"port":8890}}""")
                            }
                        }
                    }
                    "/depth3d/v1/media" -> {
                        val fp = req.clientCert?.let { HttpHostProtocolClient.sha256Hex(it) }
                        if (fp != null && savedDevices.contains(fp)) {
                            MockResponse(200, """{"webdav":{"port":8890,"user":"depth3d","pass":"pw3676"}}""")
                        } else {
                            MockResponse(401, """{"error":"unpaired"}""")
                        }
                    }
                    else -> MockResponse(404, "{}")
                }
            }.use { hostSrv ->
                val pinStore = InMemoryHostCertPinStore()
                val client = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { clientId.cert to clientId.key },
                )

                // Part 1: Locally removed computer (`alreadyPairedGsCert = null`) sends `streamPaired: false`
                // with `clientId.cert`; Host removes duplicate engine entry -> full GameStream pair succeeds!
                val outcome = HostAutoPairOrchestrator.execute(
                    hostApi = client,
                    pinStore = pinStore,
                    address = "127.0.0.1",
                    controlPort = hostSrv.port,
                    hostId = "host-3676",
                    deviceName = "depth3d",
                    deviceId = "dev-3676",
                    pin = "8392",
                    computerUuid = "gs-uuid-3676",
                    alreadyPairedGsCert = null,
                    approvedNewFingerprint = null,
                    token = CallCancelToken(),
                    runGameStreamPair = { pin, token ->
                        GameStreamPairRunner.runFullPair(
                            httpFactory = {
                                NvHTTP(
                                    ComputerDetails.AddressTuple("127.0.0.1", gsSrv.httpPort),
                                    0,
                                    "0123456789ABCDEF",
                                    null,
                                    crypto,
                                )
                            },
                            pin = pin,
                            token = token,
                        )
                    },
                )
                assertTrue("Re-pair after local remove must succeed when Host clears duplicate cert, got $outcome", outcome is HostAutoPairOutcome.Paired)
                assertEquals(hostFp, pinStore.getFingerprint("host-3676"))
                assertTrue(
                    client.getMediaCredentials("127.0.0.1", hostSrv.port, "host-3676", "dev-3676", "gs-uuid-3676", HttpHostProtocolClient.sha256Hex(gsIdCert.cert)) is MediaCredsResult.Available,
                )

                // Part 2: Ticket cert-binding guard — if client identity changes between /pair and /pair/complete,
                // completeAutoPair rejects with TicketExpired (and Host would also reject cert mismatch)!
                val swappedClient = HttpHostProtocolClient(
                    pinStore = pinStore,
                    clientIdentityProvider = { otherClientId.cert to otherClientId.key },
                )
                val mismatchRes = swappedClient.completeAutoPair(
                    address = "127.0.0.1",
                    port = hostSrv.port,
                    hostId = "host-3676",
                    ticket = "tk-3676-1",
                    deviceId = "dev-3676",
                    deviceName = "depth3d",
                    expectedFingerprint = hostFp,
                    expectedClientCertFingerprint = clientFp,
                )
                assertEquals(PairCompleteResult.TicketExpired, mismatchRes)
            }
        }
    }
}
