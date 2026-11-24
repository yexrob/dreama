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
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Rejected),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.TimedOut),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.UpgradeRequired),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.StreamServiceUnavailable),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.StreamServiceUnavailable, "请在电脑上开启屏幕录制权限"),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.CertificateChanged("id", "aaa", "bbb")),
            StreamErrorMapper.forAutoPair(strings, AutoPairResult.Failed("java.net.ConnectException: refused")),
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

        // 503 with stream.reason preserves the Mac host's reason and stays retryable
        val err503 = StreamErrorMapper.forAutoPair(
            strings,
            AutoPairResult.StreamServiceUnavailable,
            streamReason = "请在 Mac 系统设置里开启屏幕录制",
        )
        assertEquals("请在 Mac 系统设置里开启屏幕录制", err503.detail)
        assertTrue(err503.retryable)
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
