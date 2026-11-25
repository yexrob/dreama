package com.limelight.local.stream

import com.limelight.binding.video.TransportBufferQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PhoneInputAndBarTest {

    private val addPcCopy = XmlStringLookup.phoneInputCopy(PhoneInputServer.Mode.ADD_COMPUTER)

    @Test
    fun qrCodeMatrixEncodesUrlWithValidFinderAndTimingPatterns() {
        val url = "http://192.168.1.15:54321/?t=AbCdEfGhIjKlMnOpQrStUv"
        val qr = QrCodeMatrix.encode(url)
        assertTrue(qr.size >= 29)

        // Top-left, top-right, bottom-left 7x7 finder outer borders & 3x3 centers
        val corners = listOf(0 to 0, (qr.size - 7) to 0, 0 to (qr.size - 7))
        for ((ox, oy) in corners) {
            for (i in 0..6) {
                assertTrue(qr[ox + i, oy])
                assertTrue(qr[ox + i, oy + 6])
                assertTrue(qr[ox, oy + i])
                assertTrue(qr[ox + 6, oy + i])
            }
            for (dy in 2..4) {
                for (dx in 2..4) {
                    assertTrue(qr[ox + dx, oy + dy])
                }
            }
        }
        // Timing pattern on row 6 & col 6 between separators
        for (i in 8 until qr.size - 8) {
            assertEquals(i % 2 == 0, qr[i, 6])
            assertEquals(i % 2 == 0, qr[6, i])
        }
        // Dark module at (8, n - 8)
        assertTrue(qr[8, qr.size - 8])
    }

    @Test(expected = IllegalArgumentException::class)
    fun qrCodeMatrixRejectsOversizedPayload() {
        QrCodeMatrix.encode("a".repeat(300))
    }

    @Test
    fun phoneInputServerUses128BitQrTokenHasNoGetOracleAndWaitsForHeadsetConfirmation() {
        val received = AtomicReference<String>()
        val acceptNext = AtomicBoolean(false)

        PhoneInputServer(
            displayHost = "127.0.0.1",
            copy = addPcCopy,
            code = "4289",
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0,
            onSubmitted = { value ->
                received.set(value)
                if (acceptNext.get()) {
                    PhoneSubmitResult.Accepted
                } else {
                    PhoneSubmitResult.Rejected("连不上该电脑：请确认地址无误")
                }
            },
        ).start().use { server ->
            assertTrue(server.boundPort > 0)
            // Review #3: QR token is >= 128 bits (22 base64url chars) and QR URL uses ?t=
            assertTrue("QR token must be at least 22 chars (128 bits)", server.token.length >= 22)
            assertEquals("http://127.0.0.1:${server.boundPort}/?t=${server.token}", server.qrUrl)
            assertFalse("QR URL must not leak 4-digit fallback code", server.qrUrl.contains("4289"))

            // Review #3: Zero GET oracle — wrong token vs right token both return 200 form
            // targeting /submit-qr without indicating whether the token is valid.
            val getWrongToken = httpGet("http://127.0.0.1:${server.boundPort}/?t=wrong_token_value")
            val getRightToken = httpGet(server.qrUrl)
            assertEquals(200, getWrongToken.first)
            assertEquals(200, getRightToken.first)
            assertTrue(getWrongToken.second.contains("action=\"/submit-qr\""))
            assertTrue(getRightToken.second.contains("action=\"/submit-qr\""))
            assertTrue(getWrongToken.second.contains("name=\"token\" value=\"wrong_token_value\""))
            assertTrue(getRightToken.second.contains("name=\"token\" value=\"${server.token}\""))

            // Zero GET oracle on ?code=: wrong code vs right code produce IDENTICAL 200 response targeting /submit!
            val getWrongCode = httpGet("http://127.0.0.1:${server.boundPort}/?code=0000")
            val getRightCode = httpGet("http://127.0.0.1:${server.boundPort}/?code=4289")
            assertEquals(200, getWrongCode.first)
            assertEquals(200, getRightCode.first)
            assertTrue(getWrongCode.second.contains("action=\"/submit\""))
            assertEquals("GET must not differ by ?code= validity", getWrongCode.second, getRightCode.second)

            // M8: QR submission path (/submit-qr) MUST reject the 4-digit code as a substitute for token!
            acceptNext.set(true)
            val qrWithCodeOnly = postFormWithBody(
                "http://127.0.0.1:${server.boundPort}/submit-qr",
                "code=4289&value=192.168.1.99",
            )
            assertEquals("M8: /submit-qr must reject 4-digit code", 403, qrWithCodeOnly.first)
            assertNull("onSubmitted must not be invoked when /submit-qr receives code", received.get())

            val qrWithWrongTokenAndValidCode = postFormWithBody(
                "http://127.0.0.1:${server.boundPort}/submit-qr",
                "token=wrong&code=4289&value=192.168.1.99",
            )
            assertEquals("M8: /submit-qr must reject wrong token even with valid 4-digit code", 403, qrWithWrongTokenAndValidCode.first)
            assertNull(received.get())

            // Review #3: Phone must NOT see success page if headset rejects the submission!
            acceptNext.set(false)
            val rejected = postFormWithBody(
                "http://127.0.0.1:${server.boundPort}/submit-qr",
                "token=${server.token}&value=192.168.1.250",
            )
            assertEquals(400, rejected.first)
            assertTrue(rejected.second.contains("连不上该电脑"))
            assertFalse("Must not show success when headset rejected", rejected.second.contains("头显已收下"))
            assertEquals("192.168.1.250", received.get())

            // Now headset confirms acceptance -> phone sees 200 success page
            acceptNext.set(true)
            val accepted = postFormWithBody(
                "http://127.0.0.1:${server.boundPort}/submit-qr",
                "token=${server.token}&value=%E5%AE%A2%E5%8E%85%E7%9A%84+depth3d",
            )
            assertEquals(200, accepted.first)
            assertTrue(accepted.second.contains("头显已收下"))
            assertEquals("客厅的 depth3d", received.get())
        }
    }

    @Test
    fun phoneInputServerLocksOutAndClosesAfterFiveWrongCodes() {
        val lockedOut = CountDownLatch(1)
        PhoneInputServer(
            displayHost = "127.0.0.1",
            copy = addPcCopy,
            code = "9182",
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0,
            onSubmitted = { PhoneSubmitResult.Accepted },
            onLockedOut = { lockedOut.countDown() },
        ).start().use { server ->
            val submitUrl = "http://127.0.0.1:${server.boundPort}/submit"
            for (attempt in 1..4) {
                val (status, _) = postFormWithBody(submitUrl, "code=000$attempt&value=192.168.1.50")
                assertEquals(403, status)
                assertFalse("Should still be open after attempt $attempt", server.isClosed)
            }
            // 5th failure locks out and shuts the server down
            val (status5, body5) = postFormWithBody(submitUrl, "code=9999&value=192.168.1.50")
            assertEquals(403, status5)
            assertTrue(body5.contains("通道已关闭"))
            assertTrue("onLockedOut callback must fire", lockedOut.await(2, TimeUnit.SECONDS))
            assertTrue("Server must close on 5th failed attempt", server.isClosed)
        }
    }

    @Test
    fun m2_eightSlowlorisTrickleClientsCannotStarveRealPhoneAndAreKilledAtDeadline() {
        PhoneInputServer(
            displayHost = "127.0.0.1",
            copy = addPcCopy,
            code = "5566",
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0,
            socketTimeoutMs = 300,
            requestDeadlineMs = 450L,
            onSubmitted = { PhoneSubmitResult.Accepted },
        ).start().use { server ->
            // Open 8 slowloris clients (equal to MAX_CONCURRENT_CONNECTIONS) that trickle 1 byte
            // every 80ms (< socketTimeoutMs) to defeat naive per-read timeouts!
            val slowSockets = ArrayList<Socket>(8)
            val trickleThreads = ArrayList<Thread>(8)
            val closedByServer = CountDownLatch(8)
            try {
                repeat(PhoneInputServer.MAX_CONCURRENT_CONNECTIONS) { idx ->
                    val s = Socket(InetAddress.getLoopbackAddress(), server.boundPort)
                    slowSockets += s
                    val t = Thread({
                        try {
                            val out = s.outputStream
                            out.write("POST /submit HTTP/1.1\r\nHost: x\r\nX-Slow: ".toByteArray(StandardCharsets.US_ASCII))
                            out.flush()
                            // Trickle bytes slowly past requestDeadlineMs; server must close us!
                            var i = 0
                            while (i < 40) {
                                Thread.sleep(80)
                                out.write('a'.code)
                                out.flush()
                                // Probe for server-side EOF
                                s.soTimeout = 10
                                try {
                                    if (s.inputStream.read() == -1) break
                                } catch (_: java.net.SocketTimeoutException) {
                                }
                                i++
                            }
                        } catch (_: Exception) {
                        } finally {
                            closedByServer.countDown()
                        }
                    }, "slowloris-$idx").apply {
                        isDaemon = true
                        start()
                    }
                    trickleThreads += t
                }

                // Wait briefly so all 8 slow clients have established connections
                Thread.sleep(100)

                // Even while all 8 slow sockets are active, a real phone submission succeeds quickly!
                val startNs = System.nanoTime()
                val (status, body) = postFormWithBody(
                    "http://127.0.0.1:${server.boundPort}/submit",
                    "code=5566&value=192.168.1.20",
                )
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                assertEquals(200, status)
                assertTrue(body.contains("头显已收下"))
                assertTrue("M2: Real phone must not be starved by 8 slow sockets (${elapsedMs}ms)", elapsedMs < 1500L)

                // All 8 slowloris trickle sockets must be terminated by the server at/near requestDeadlineMs
                assertTrue(
                    "M2: All 8 slowloris clients must be closed by requestDeadlineMs",
                    closedByServer.await(2_000L, TimeUnit.MILLISECONDS),
                )
            } finally {
                slowSockets.forEach {
                    try { it.close() } catch (_: Exception) {}
                }
                trickleThreads.forEach { it.interrupt() }
            }
        }
    }

    @Test
    fun m6_s4_phoneInputErrorsMatchModeAndDlnaCastCopyLoadsFromResources() {
        val strings = XmlStringLookup

        // M6: Timeout & failure copy must say "添加电脑" in ADD_COMPUTER mode and "修改设备名" in DEVICE_NAME mode
        val addTimeout = PhoneInputErrorHelper.timeoutError(strings, PhoneInputServer.Mode.ADD_COMPUTER)
        assertTrue(addTimeout.title.contains("添加电脑"))
        assertFalse(addTimeout.title.contains("修改设备名"))

        val renameTimeout = PhoneInputErrorHelper.timeoutError(strings, PhoneInputServer.Mode.DEVICE_NAME)
        assertTrue("M6: Rename timeout must say 修改设备名, got: ${renameTimeout.title}", renameTimeout.title.contains("修改设备名"))
        assertFalse("M6: Rename timeout must NOT say 添加电脑", renameTimeout.title.contains("添加电脑"))

        val renameFailed = PhoneInputErrorHelper.failedError(strings, PhoneInputServer.Mode.DEVICE_NAME)
        assertTrue(renameFailed.title.contains("修改设备名"))
        assertFalse(renameFailed.title.contains("添加电脑"))

        val formatted = PhoneInputErrorHelper.formatRejectedReason(strings, renameTimeout)
        assertTrue(formatted.contains(renameTimeout.title))
        assertTrue(formatted.contains(renameTimeout.detail))

        // S4: DlnaCastStatus uses XML string resources cleanly across all phases
        val waiting = com.limelight.local.wall.dlnaCastCopy(
            strings,
            com.limelight.local.dlna.ReceiverStatus("我的 depth3d", com.limelight.local.dlna.ReceiverStatus.Phase.WAITING),
        )
        assertFalse(waiting.danger)
        assertTrue(waiting.notice.contains("等待投屏"))
        assertTrue(waiting.phoneHowTo.contains("我的 depth3d"))

        val noWifi = com.limelight.local.wall.dlnaCastCopy(
            strings,
            com.limelight.local.dlna.ReceiverStatus("我的 depth3d", com.limelight.local.dlna.ReceiverStatus.Phase.NO_NETWORK),
        )
        assertTrue(noWifi.danger)
        assertTrue(noWifi.notice.contains("Wi-Fi"))

        val casting = com.limelight.local.wall.dlnaCastCopy(
            strings,
            com.limelight.local.dlna.ReceiverStatus("我的 depth3d", com.limelight.local.dlna.ReceiverStatus.Phase.WAITING, casting = "测试电影"),
        )
        assertTrue(casting.notice.contains("测试电影"))

        // #5: Full label-with-count format keeps exact space on BOTH sides of '·' (aapt2 cannot strip it)
        assertEquals(
            "头显存储 · 12 个",
            strings.get(com.limelight.R.string.library_label_with_count, "头显存储", 12),
        )

        // #13: AirPlay no-Wi-Fi body retains "iPad"
        assertTrue(
            "#13: airplay_no_wifi_body must mention iPad",
            strings.get(com.limelight.R.string.airplay_no_wifi_body).contains("iPad"),
        )
    }

    @Test
    fun streamControlBarNetworkWarningThresholdsMatchSpec() {
        val strings = XmlStringLookup
        // Healthy network: <= 60ms and < 0.5% loss -> no warning
        assertFalse(StreamControlBar.shouldWarnNetwork(18, 0f, false))
        assertNull(StreamControlBar.formatNetworkWarning(strings, 18, 0f, false))
        assertFalse(StreamControlBar.shouldWarnNetwork(60, 0.4f, false))
        assertNull(StreamControlBar.formatNetworkWarning(strings, 60, 0.4f, false))

        // Latency > 60ms -> red warning naming latency
        assertTrue(StreamControlBar.shouldWarnNetwork(61, 0f, false))
        val highRtt = StreamControlBar.formatNetworkWarning(strings, 78, 0f, false)
        assertNotNull(highRtt)
        assertTrue(highRtt!!.contains("78 ms"))

        // Packet loss >= 0.5% -> red warning naming loss
        assertTrue(StreamControlBar.shouldWarnNetwork(25, 1.4f, false))
        val lossOnly = StreamControlBar.formatNetworkWarning(strings, 25, 1.4f, false)
        assertNotNull(lossOnly)
        assertTrue(lossOnly!!.contains("1%"))

        // Both latency and packet loss
        val both = StreamControlBar.formatNetworkWarning(strings, 95, 3.2f, false)
        assertNotNull(both)
        assertTrue(both!!.contains("95 ms"))
        assertTrue(both.contains("3%"))

        // Poor connection callback alone
        assertTrue(StreamControlBar.shouldWarnNetwork(0, 0f, true))
        assertNotNull(StreamControlBar.formatNetworkWarning(strings, 0, 0f, true))
    }

    @Test
    fun transportBufferQueueNeverDropsLatestFrameAndIsRaceFreeUnderConcurrentAccess() {
        val size = 256
        val queue = TransportBufferQueue(size)

        // Rapid consecutive posts before consumer polls: latest frame MUST win (never dropped!)
        val producerBuf = ByteBuffer.allocateDirect(size)
        for (frameId in 1..5) {
            fillUniform(producerBuf, frameId.toByte())
            assertTrue(queue.post(producerBuf))
        }
        // Mutating producerBuf immediately after post() must NOT affect queued frame
        fillUniform(producerBuf, 99.toByte())

        val firstPoll = queue.pollForUpload()
        assertNotNull(firstPoll)
        assertUniform(firstPoll!!, 5.toByte())
        assertNull("Second poll with no new post must return null", queue.pollForUpload())

        // While consumer holds firstPoll, producer posts many frames -> firstPoll stays untouched!
        for (frameId in 10..25) {
            fillUniform(producerBuf, frameId.toByte())
            assertTrue(queue.post(producerBuf))
        }
        assertUniform(firstPoll, 5.toByte())

        val secondPoll = queue.pollForUpload()
        assertNotNull(secondPoll)
        assertUniform(secondPoll!!, 25.toByte())

        // Concurrent producer + consumer stress check for zero partial writes / tearing
        val stop = AtomicBoolean(false)
        val tearDetected = AtomicBoolean(false)
        val producer = Thread {
            val src = ByteBuffer.allocateDirect(size)
            var v = 1
            while (!stop.get()) {
                fillUniform(src, (v and 0x7F).toByte())
                queue.post(src)
                v++
                if (v > 120) v = 1
            }
        }.apply { start() }

        try {
            var checks = 0
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400)
            while (System.nanoTime() < deadline && checks < 500) {
                val frame = queue.pollForUpload()
                if (frame != null) {
                    val expected = frame.get(0)
                    for (i in 1 until size) {
                        if (frame.get(i) != expected) {
                            tearDetected.set(true)
                            break
                        }
                    }
                    checks++
                }
            }
            assertTrue("Expected concurrent frames polled", checks > 0)
            assertFalse("Torn/raced transport frame detected!", tearDetected.get())
        } finally {
            stop.set(true)
            producer.join(2000)
        }
    }

    private fun fillUniform(buf: ByteBuffer, value: Byte) {
        buf.clear()
        for (i in 0 until buf.capacity()) {
            buf.put(i, value)
        }
        buf.position(0).limit(buf.capacity())
    }

    private fun assertUniform(buf: ByteBuffer, expected: Byte) {
        assertEquals(buf.capacity(), buf.remaining())
        for (i in 0 until buf.capacity()) {
            assertEquals("Mismatch at byte $i", expected, buf.get(i))
        }
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 2000
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
        return code to body
    }

    private fun postFormWithBody(url: String, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 2000
            readTimeout = 2000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
        return code to text
    }

    @Test
    fun qrEncodesLongLoginPayloadsUpToVersionNine() {
        // 145 bytes is the real-world QR login URL length that used to crash the login card.
        val sizes = mapOf(145 to 45, 190 to 49, 230 to 53)
        val dir = java.io.File("build/qr-samples").apply { mkdirs() }
        for ((len, expectedSize) in sizes) {
            val text = "https://example.com/qr?" + "k".repeat(len - 23)
            val qr = QrCodeMatrix.encode(text)
            org.junit.Assert.assertEquals(expectedSize, qr.size)
            val sb = StringBuilder("P1\n${qr.size + 8} ${qr.size + 8}\n")
            for (y in -4 until qr.size + 4) {
                for (x in -4 until qr.size + 4) {
                    val dark = x in 0 until qr.size && y in 0 until qr.size && qr[x, y]
                    sb.append(if (dark) "1 " else "0 ")
                }
                sb.append('\n')
            }
            java.io.File(dir, "qr-$len.pbm").writeText(sb.toString())
            java.io.File(dir, "qr-$len.txt").writeText(text)
        }
        org.junit.Assert.assertNull(QrCodeMatrix.encodeOrNull("a".repeat(QrCodeMatrix.MAX_PAYLOAD_BYTES + 1)))
        org.junit.Assert.assertNotNull(QrCodeMatrix.encodeOrNull("a".repeat(QrCodeMatrix.MAX_PAYLOAD_BYTES)))
    }
}
