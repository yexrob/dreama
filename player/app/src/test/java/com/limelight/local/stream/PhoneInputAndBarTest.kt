package com.limelight.local.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PhoneInputAndBarTest {

    @Test
    fun qrCodeMatrixEncodesUrlWithValidFinderAndTimingPatterns() {
        val url = "http://192.168.1.15:54321/?code=4829"
        val qr = QrCodeMatrix.encode(url)
        assertEquals(29, qr.size) // Version 3 (34 bytes fits V3 ECC L capacity 53)

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
        QrCodeMatrix.encode("a".repeat(200))
    }

    @Test
    fun phoneInputServerValidatesCodeAndDeliversSubmission() {
        val received = AtomicReference<String>()
        val latch = CountDownLatch(1)
        PhoneInputServer(
            mode = PhoneInputServer.Mode.ADD_COMPUTER,
            displayHost = "127.0.0.1",
            code = "4289",
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0,
            onSubmitted = { value ->
                received.set(value)
                latch.countDown()
            },
        ).start().use { server ->
            assertTrue(server.boundPort > 0)
            assertEquals("http://127.0.0.1:${server.boundPort}/?code=4289", server.qrUrl)

            // 1. GET with valid query code renders form with prefilled hidden code
            val getConn = (URL(server.qrUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
            }
            assertEquals(200, getConn.responseCode)
            val getBody = getConn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(getBody.contains("添加电脑到头显"))
            assertTrue(getBody.contains("type=\"hidden\" name=\"code\" value=\"4289\""))

            // 2. POST with wrong verification code -> 403
            val wrongCode = postForm("http://127.0.0.1:${server.boundPort}/submit", "code=0000&value=192.168.1.50")
            assertEquals(403, wrongCode)
            assertNull(received.get())

            // 3. POST with empty value -> 400
            val emptyVal = postForm("http://127.0.0.1:${server.boundPort}/submit", "code=4289&value=")
            assertEquals(400, emptyVal)
            assertNull(received.get())

            // 4. POST with valid code + UTF-8 value -> 200 and callback fires
            val ok = postForm("http://127.0.0.1:${server.boundPort}/submit", "code=4289&value=%E5%AE%A2%E5%8E%85%E7%9A%84+depth3d")
            assertEquals(200, ok)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("客厅的 depth3d", received.get())
        }
    }

    @Test
    fun streamControlBarNetworkWarningThresholdsMatchSpec() {
        // Healthy network: <= 60ms and < 0.5% loss -> no warning
        assertFalse(StreamControlBar.shouldWarnNetwork(18, 0f, false))
        assertNull(StreamControlBar.formatNetworkWarning(18, 0f, false))
        assertFalse(StreamControlBar.shouldWarnNetwork(60, 0.4f, false))
        assertNull(StreamControlBar.formatNetworkWarning(60, 0.4f, false))

        // Latency > 60ms -> red warning naming latency
        assertTrue(StreamControlBar.shouldWarnNetwork(61, 0f, false))
        val highRtt = StreamControlBar.formatNetworkWarning(78, 0f, false)
        assertNotNull(highRtt)
        assertTrue(highRtt!!.contains("78 ms"))

        // Packet loss >= 0.5% -> red warning naming loss
        assertTrue(StreamControlBar.shouldWarnNetwork(25, 1.4f, false))
        val lossOnly = StreamControlBar.formatNetworkWarning(25, 1.4f, false)
        assertNotNull(lossOnly)
        assertTrue(lossOnly!!.contains("1%"))

        // Both latency and packet loss
        val both = StreamControlBar.formatNetworkWarning(95, 3.2f, false)
        assertNotNull(both)
        assertTrue(both!!.contains("95 ms"))
        assertTrue(both.contains("3%"))

        // Poor connection callback alone
        assertTrue(StreamControlBar.shouldWarnNetwork(0, 0f, true))
        assertNotNull(StreamControlBar.formatNetworkWarning(0, 0f, true))
    }

    private fun postForm(url: String, body: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return conn.responseCode
    }
}
