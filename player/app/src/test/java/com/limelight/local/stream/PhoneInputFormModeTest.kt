package com.limelight.local.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PhoneInputFormModeTest {

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, params: Map<String, String>): Pair<Int, String> {
        val encoded = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(encoded.toByteArray(StandardCharsets.UTF_8)) }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun formModeEnforcesZeroGetOracleProgressReportingAndNeverEchoesPasswords() {
        val copy = PhoneFormCopy.from(XmlStringLookup, "Jellyfin")
        val fields = listOf(
            PhoneFormField("server", PhoneFormFieldKind.URL, "服务器地址", required = true, initialValue = "http://192.168.1.20:8096"),
            PhoneFormField("username", PhoneFormFieldKind.TEXT, "用户名", required = true),
            PhoneFormField("password", PhoneFormFieldKind.PASSWORD, "密码", required = false, initialValue = "SHOULD_NEVER_PREFILL"),
            PhoneFormField(
                "quality",
                PhoneFormFieldKind.SELECT,
                "画质",
                initialValue = "direct",
                options = listOf(PhoneFormOption("direct", "原画"), PhoneFormOption("1080p", "1080p")),
            ),
            PhoneFormField("tls", PhoneFormFieldKind.TOGGLE, "校验证书", initialValue = "true"),
        )
        val progressEvents = CopyOnWriteArrayList<PhoneFormProgress>()
        val submitted = AtomicReference<Map<String, String>>()
        val acceptNext = AtomicBoolean(false)

        PhoneFormServer(
            copy = copy,
            fields = fields,
            displayHost = "127.0.0.1",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onProgress = { progressEvents += it },
            onSubmitted = { values ->
                submitted.set(values)
                if (acceptNext.get()) PhoneSubmitResult.Accepted
                else PhoneSubmitResult.Rejected("账号或密码不正确")
            },
        ).start().use { srv ->
            // 1. GET with wrong token still returns 200 (zero GET oracle) and NEVER prefills password
            val (badGetCode, badGetBody) = httpGet("${srv.shortUrl}/?t=invalid-token-123")
            assertEquals(200, badGetCode)
            assertFalse("Password initialValue must never be prefilled in HTML", badGetBody.contains("SHOULD_NEVER_PREFILL"))
            assertTrue(badGetBody.contains("type=\"password\""))

            // 2. Progress endpoint reports live n/m count without needing field values
            val (progCode, _) = httpPost(
                "${srv.shortUrl}${PhoneFormServer.PATH_PROGRESS}",
                mapOf("token" to srv.token, "filled" to "3"),
            )
            assertEquals(204, progCode)
            assertEquals(1, progressEvents.size)
            assertEquals(3, progressEvents[0].filledCount)
            assertEquals(5, progressEvents[0].totalCount)

            // 3. QR path rejects 4-digit code (strict path separation)
            val (wrongPathCode, _) = httpPost(
                "${srv.shortUrl}${PhoneInputServer.PATH_SUBMIT_QR}",
                mapOf("code" to srv.verificationCode, "f_server" to "http://192.168.1.20:8096", "f_username" to "alice"),
            )
            assertEquals(403, wrongPathCode)

            // 4. Rejected submission preserves non-password fields and NEVER echoes password
            val secretPassword = "MySuperSecretPassword!99"
            val (rejCode, rejHtml) = httpPost(
                "${srv.shortUrl}${PhoneInputServer.PATH_SUBMIT_QR}",
                mapOf(
                    "token" to srv.token,
                    "f_server" to "http://192.168.1.20:8096",
                    "f_username" to "alice",
                    "f_password" to secretPassword,
                    "f_quality" to "1080p",
                    "f_tls" to "true",
                ),
            )
            assertEquals(400, rejCode)
            assertTrue("Non-password field should be preserved on error", rejHtml.contains("alice"))
            assertFalse("Password MUST NEVER be echoed on error page", rejHtml.contains(secretPassword))
            assertEquals(secretPassword, submitted.get()["password"])

            // 5. Accepted submission via 4-digit code on /submit NEVER echoes password on done page
            acceptNext.set(true)
            val (okCode, okHtml) = httpPost(
                "${srv.shortUrl}${PhoneInputServer.PATH_SUBMIT_CODE}",
                mapOf(
                    "code" to srv.verificationCode,
                    "f_server" to "http://192.168.1.20:8096",
                    "f_username" to "alice",
                    "f_password" to secretPassword,
                    "f_quality" to "direct",
                ),
            )
            assertEquals(200, okCode)
            assertFalse("Password MUST NEVER be echoed on done page", okHtml.contains(secretPassword))
            assertTrue(okHtml.contains("已发送到头显"))
        }
    }

    @Test
    fun formModeLocksOutPermanentlyAfterFiveFailedAttempts() {
        val copy = PhoneFormCopy.from(XmlStringLookup, "Jellyfin")
        val locked = AtomicBoolean(false)
        val fields = listOf(PhoneFormField("server", PhoneFormFieldKind.URL, "Server", required = true))

        PhoneFormServer(
            copy = copy,
            fields = fields,
            displayHost = "127.0.0.1",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onLockedOut = { locked.set(true) },
            onSubmitted = { PhoneSubmitResult.Accepted },
        ).start().use { srv ->
            for (attempt in 1..PhoneInputServer.MAX_FAILED_ATTEMPTS) {
                val (code, _) = httpPost(
                    "${srv.shortUrl}${PhoneInputServer.PATH_SUBMIT_CODE}",
                    mapOf("code" to "0000", "f_server" to "http://192.168.1.10:8096"),
                )
                assertEquals(403, code)
            }
            assertTrue("Server must lock out after 5 wrong attempts", locked.get())
            assertTrue("Server must shut down permanently on lockout", srv.isClosed)
        }
    }

    @Test
    fun pluginRepoModeCopyAndSubmissionWork() {
        val copy = PhoneInputCopy.from(XmlStringLookup, PhoneInputServer.Mode.PLUGIN_REPO)
        assertTrue(copy.heading.contains("插件仓库"))
        val got = AtomicReference<String>()
        PhoneInputServer(
            copy = copy,
            displayHost = "127.0.0.1",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onSubmitted = { v ->
                got.set(v)
                PhoneSubmitResult.Accepted
            },
        ).start().use { srv ->
            val (code, _) = httpPost(
                "${srv.shortUrl}${PhoneInputServer.PATH_SUBMIT_QR}",
                mapOf("token" to srv.token, "value" to "https://plugins.example.com/repo.json"),
            )
            assertEquals(200, code)
            assertNotNull(got.get())
            assertEquals("https://plugins.example.com/repo.json", got.get())
        }
    }
}
