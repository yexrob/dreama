package com.limelight.local.stream

import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class StreamErrorMapperTest {

    private val strings = XmlStringLookup

    private val forbiddenTokens = listOf(
        "Exception",
        "Error",
        "null",
        "404",
        "599",
        "Moonlight",
        "Sunshine",
        "GeForce",
        "GFE",
        "OpenXR",
        "HTTP",
        "RTSP",
        "凭证",
        "凭据",
        "来源校验",
        "配对记录",
        "串流配对",
        "完整配对",
        "串流安全标识",
        "证书指纹",
        "安全证书",
    )

    private fun assertCleanChineseError(err: UserFacingError) {
        assertTrue("title should not be blank", err.title.isNotBlank())
        assertTrue("detail should not be blank", err.detail.isNotBlank())
        val combined = "${err.title} ${err.detail}"
        for (token in forbiddenTokens) {
            assertFalse(
                "Error message should not expose '$token': $combined",
                combined.contains(token, ignoreCase = true),
            )
        }
    }

    @Test
    fun allErrorMappingsAreHumanReadableChineseFromXmlWithoutTechnicalLeaks() {
        val samples = listOf(
            StreamErrorMapper.forServiceStarting(strings),
            StreamErrorMapper.forComputerOffline(strings, "书房 Mac"),
            StreamErrorMapper.forComputerUnreachable(strings, "书房 Mac"),
            StreamErrorMapper.forPairState(strings, PairState.PIN_WRONG, false),
            StreamErrorMapper.forPairState(strings, PairState.ALREADY_IN_PROGRESS, false),
            StreamErrorMapper.forPairState(strings, PairState.FAILED, false),
            StreamErrorMapper.forPairState(strings, PairState.FAILED, true),
            StreamErrorMapper.forPairState(strings, PairState.NOT_PAIRED, false),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.ClientCertRequired),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Rejected),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.TimedOut),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Busy()),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Busy(4)),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Cooldown(23)),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Cooldown(null)),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.UpgradeRequired),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.StreamServiceUnavailable()),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.StreamServiceUnavailable("请在电脑上开启屏幕录制权限")),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.StreamServiceUnavailable(), "请在电脑上开启屏幕录制权限"),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.CertificateChanged("id", "aaa", "bbb")),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Failed("java.net.ConnectException: refused")),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.NotPaired),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.TicketExpired),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.ClientCertMismatch),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.MissingComputerIdentity),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.Unauthorized),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.Forbidden),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.UpgradeRequired),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.CertificateChanged("id", "aaa", "bbb")),
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.Failed("socket reset")),
            StreamErrorMapper.forGameStreamPairTimeout(strings),
            StreamErrorMapper.forManualPinPairTimeout(strings),
            StreamErrorMapper.forUnexpectedDoneWithoutVerifiedStream(strings),
            StreamErrorMapper.forHostNotReady(strings, null),
            StreamErrorMapper.forHostNotReady(strings, "请在系统设置中授予屏幕录制权限"),
            StreamErrorMapper.forHostUpgradeRequired(strings),
            StreamErrorMapper.forHostIdentityMismatch(strings),
            StreamErrorMapper.forStageFailed(strings, "video"),
            StreamErrorMapper.forStageFailed(strings, "audio"),
            StreamErrorMapper.forStageFailed(strings, "rtsp handshake"),
            StreamErrorMapper.forStageFailed(strings, "unknown_stage"),
            StreamErrorMapper.forConnectionTerminated(strings, MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC),
            StreamErrorMapper.forConnectionTerminated(strings, MoonBridge.ML_ERROR_NO_VIDEO_FRAME),
            StreamErrorMapper.forConnectionTerminated(strings, MoonBridge.ML_ERROR_PROTECTED_CONTENT),
            StreamErrorMapper.forConnectionTerminated(strings, MoonBridge.ML_ERROR_FRAME_CONVERSION),
            StreamErrorMapper.forConnectionTerminated(strings, -9999),
            StreamErrorMapper.forVrUnavailable(strings),
            StreamErrorMapper.forThrowable(strings, UnknownHostException("bad.host"), "连接电脑"),
            StreamErrorMapper.forThrowable(strings, SocketTimeoutException("timed out"), "连接电脑"),
            StreamErrorMapper.forThrowable(strings, ConnectException("ECONNREFUSED"), "连接电脑"),
            StreamErrorMapper.forThrowable(strings, NoRouteToHostException("no route"), "连接电脑"),
            StreamErrorMapper.forThrowable(strings, FileNotFoundException("404"), "连接电脑"),
            StreamErrorMapper.forThrowable(strings, HostHttpResponseException(599, "Not started by client"), "连接电脑"),
            StreamErrorMapper.forThrowable(strings, HostHttpResponseException(401, "Unauthorized"), "连接电脑"),
            StreamErrorMapper.forThrowable(strings, IOException("broken pipe"), "连接电脑"),
            StreamErrorMapper.forAddComputerFailed(strings, "192.168.1.25"),
            StreamErrorMapper.forInvalidAddress(strings),
            StreamErrorMapper.forWakeNoMac(strings),
            StreamErrorMapper.forWakeFailed(strings),
        )

        samples.forEach(::assertCleanChineseError)
        assertFalse(StreamErrorMapper.forHostUpgradeRequired(strings).retryable)
        assertFalse(StreamErrorMapper.forWakeNoMac(strings).retryable)

        // 503 with stream_not_ready reason preserves the Mac host's Chinese reason and stays retryable
        val err503FromBody = StreamErrorMapper.forAutoPair(
            strings,
            AutoPairResult.StreamServiceUnavailable("请在 Mac 系统设置里开启屏幕录制"),
        )
        assertEquals("请在 Mac 系统设置里开启屏幕录制", err503FromBody.detail)
        assertTrue(err503FromBody.retryable)

        // 429 cooldown with retryAfterSec includes the exact seconds and stays retryable
        val errCooldown = StreamErrorMapper.forAutoPair(strings, AutoPairResult.Cooldown(25))
        assertTrue("Cooldown detail should mention 25 seconds: ${errCooldown.detail}", errCooldown.detail.contains("25"))
        assertTrue(errCooldown.retryable)

        // 429 busy, 409 not_paired, 410 ticket expired are retryable; 401 local missing cert (M3) & 426 upgrade are NOT
        assertTrue(StreamErrorMapper.forAutoPair(strings, AutoPairResult.Busy()).retryable)
        assertTrue(StreamErrorMapper.forPairComplete(strings, PairCompleteResult.NotPaired).retryable)
        assertTrue(StreamErrorMapper.forPairComplete(strings, PairCompleteResult.TicketExpired).retryable)
        assertFalse(
            "M3: Missing local client certificate (401 on /pair) must NOT be retryable",
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.ClientCertRequired).retryable,
        )
        assertFalse(
            "M3: Missing local client certificate (401) must NOT be retryable",
            StreamErrorMapper.forPairComplete(strings, PairCompleteResult.Unauthorized).retryable,
        )
        val err426Complete = StreamErrorMapper.forPairComplete(strings, PairCompleteResult.UpgradeRequired)
        assertEquals("请更新电脑上的 depth3d Host", err426Complete.title)
        assertFalse(err426Complete.retryable)

        // M2: Cooldown/busy retry countdown seconds
        assertEquals(25, RetryCooldown.initialSeconds(AutoPairResult.Cooldown(25)))
        assertEquals(RetryCooldown.DEFAULT_COOLDOWN_SEC, RetryCooldown.initialSeconds(AutoPairResult.Cooldown(null)))
        assertEquals(4, RetryCooldown.initialSeconds(AutoPairResult.Busy(4)))
        assertEquals(RetryCooldown.DEFAULT_BUSY_SEC, RetryCooldown.initialSeconds(AutoPairResult.Busy(null)))
        assertEquals(0, RetryCooldown.initialSeconds(AutoPairResult.Rejected))

        // M5: Third-party networking description has no raw HTTPS / 证书加密 jargon
        val netRole = strings.get(com.limelight.R.string.settings_tp_net_role)
        assertFalse("M5: settings_tp_net_role should not contain HTTPS: $netRole", netRole.contains("HTTPS", ignoreCase = true))
        assertFalse("M5: settings_tp_net_role should not contain 证书加密: $netRole", netRole.contains("证书加密"))

        // R6 #2: Plain manual PIN timeout copy does NOT mention depth3d Host and reflects 5-minute window
        val manualTimeout = StreamErrorMapper.forManualPinPairTimeout(strings)
        assertTrue(manualTimeout.retryable)
        assertFalse("R6 #2: Manual PIN timeout must not mention Host: $manualTimeout", "${manualTimeout.title} ${manualTimeout.detail}".contains("Host", ignoreCase = true))
        assertTrue("R6 #2: Manual PIN timeout should mention 5 分钟: ${manualTimeout.detail}", manualTimeout.detail.contains("5 分钟"))

        // R6 #9a: Local identity error states cause + concrete Settings -> Privacy -> 重置配对信息 action
        val unauthErr = StreamErrorMapper.forPairComplete(strings, PairCompleteResult.Unauthorized)
        assertFalse(unauthErr.retryable)
        assertFalse(unauthErr.title.contains("暂时无法证明身份"))
        assertTrue("R6 #9a: Must guide user to 重置配对信息: ${unauthErr.detail}", unauthErr.detail.contains("重置配对信息"))
        assertTrue(strings.get(com.limelight.R.string.settings_reset_pairing_title).contains("重置配对信息"))

        // R6 #9b: 409 not_paired detail no longer tells user to check if they clicked Allow
        val notPairedErr = StreamErrorMapper.forPairComplete(strings, PairCompleteResult.NotPaired)
        assertFalse("R6 #9b: Must not mention 允许: ${notPairedErr.detail}", notPairedErr.detail.contains("允许"))

        // R6 #9c: Expired title & detail do not repeat; ClientCertMismatch has its own copy
        val expiredErr = StreamErrorMapper.forPairComplete(strings, PairCompleteResult.TicketExpired)
        val certMismatchErr = StreamErrorMapper.forPairComplete(strings, PairCompleteResult.ClientCertMismatch)
        assertFalse("R6 #9c: Expired detail must not repeat title", expiredErr.detail.startsWith(expiredErr.title))
        assertFalse("R6 #9c: ClientCertMismatch must not reuse expired title", certMismatchErr.title == expiredErr.title)
        assertFalse("R6 #9c: ClientCertMismatch must not reuse expired detail", certMismatchErr.detail == expiredErr.detail)
        assertTrue(certMismatchErr.retryable)

        // R6 #9d: Cert confirm page labels and bodies use unified 身份 / 身份编号 vocabulary without jargon
        val certConfirmStrings = listOf(
            strings.get(com.limelight.R.string.pair_cert_title, "书房 Mac"),
            strings.get(com.limelight.R.string.pair_cert_body),
            strings.get(com.limelight.R.string.pair_cert_old_label),
            strings.get(com.limelight.R.string.pair_cert_new_label),
            strings.get(com.limelight.R.string.pair_gs_cert_title, "书房 Mac"),
            strings.get(com.limelight.R.string.pair_gs_cert_body),
            strings.get(com.limelight.R.string.err_cert_changed_title),
        )
        for (text in certConfirmStrings) {
            for (token in forbiddenTokens) {
                assertFalse("R6 #9d: Cert confirm copy must not contain '$token': $text", text.contains(token, ignoreCase = true))
            }
        }
        assertTrue(strings.get(com.limelight.R.string.pair_cert_title, "Mac").contains("身份变了"))
        assertTrue(strings.get(com.limelight.R.string.pair_gs_cert_title, "Mac").contains("身份变了"))
        assertTrue(strings.get(com.limelight.R.string.pair_cert_old_label).contains("身份编号"))
        assertTrue(strings.get(com.limelight.R.string.pair_cert_new_label).contains("身份编号"))

        // R6 #9e: Missing computer UUID gets dedicated accurate copy and retryable=false
        val missingUuidErr = StreamErrorMapper.forPairComplete(strings, PairCompleteResult.MissingComputerIdentity)
        assertFalse("R6 #9e: MissingComputerIdentity must NOT show a useless Retry button", missingUuidErr.retryable)
        assertTrue(missingUuidErr.title.contains("唯一标识"))
    }

    @Test
    fun stageLabelsProduceChineseProgressStrings() {
        for (stage in listOf("app", "rtsp", "control", "video", "audio", "input", null)) {
            val label = StreamErrorMapper.forStageLabel(strings, stage)
            assertTrue(label.isNotBlank())
            assertTrue(label.endsWith("…"))
        }
    }
}
