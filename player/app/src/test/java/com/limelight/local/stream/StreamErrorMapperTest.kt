package com.limelight.local.stream

import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.jni.MoonBridge
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
    fun allErrorMappingsAreHumanReadableChineseWithoutTechnicalLeaks() {
        val samples = listOf(
            StreamErrorMapper.forPairState(PairState.PIN_WRONG, false),
            StreamErrorMapper.forPairState(PairState.ALREADY_IN_PROGRESS, false),
            StreamErrorMapper.forPairState(PairState.FAILED, false),
            StreamErrorMapper.forPairState(PairState.FAILED, true),
            StreamErrorMapper.forPairState(PairState.NOT_PAIRED, false),
            StreamErrorMapper.forAutoPair(AutoPairResult.Rejected),
            StreamErrorMapper.forAutoPair(AutoPairResult.TimedOut),
            StreamErrorMapper.forAutoPair(AutoPairResult.UpgradeRequired),
            StreamErrorMapper.forAutoPair(AutoPairResult.CertificateChanged("id", "aaa", "bbb")),
            StreamErrorMapper.forAutoPair(AutoPairResult.Failed("java.net.ConnectException: refused")),
            StreamErrorMapper.forHostNotReady(null),
            StreamErrorMapper.forHostNotReady("请在系统设置中授予屏幕录制权限"),
            StreamErrorMapper.forHostUpgradeRequired(),
            StreamErrorMapper.forStageFailed("video"),
            StreamErrorMapper.forStageFailed("audio"),
            StreamErrorMapper.forStageFailed("rtsp handshake"),
            StreamErrorMapper.forStageFailed("unknown_stage"),
            StreamErrorMapper.forConnectionTerminated(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC),
            StreamErrorMapper.forConnectionTerminated(MoonBridge.ML_ERROR_NO_VIDEO_FRAME),
            StreamErrorMapper.forConnectionTerminated(MoonBridge.ML_ERROR_PROTECTED_CONTENT),
            StreamErrorMapper.forConnectionTerminated(MoonBridge.ML_ERROR_FRAME_CONVERSION),
            StreamErrorMapper.forConnectionTerminated(-9999),
            StreamErrorMapper.forVrUnavailable(),
            StreamErrorMapper.forThrowable(UnknownHostException("bad.host")),
            StreamErrorMapper.forThrowable(SocketTimeoutException("timed out")),
            StreamErrorMapper.forThrowable(ConnectException("ECONNREFUSED")),
            StreamErrorMapper.forThrowable(NoRouteToHostException("no route")),
            StreamErrorMapper.forThrowable(FileNotFoundException("404")),
            StreamErrorMapper.forThrowable(HostHttpResponseException(599, "Not started by client")),
            StreamErrorMapper.forThrowable(HostHttpResponseException(401, "Unauthorized")),
            StreamErrorMapper.forThrowable(IOException("broken pipe")),
            StreamErrorMapper.forAddComputerFailed("192.168.1.25"),
            StreamErrorMapper.forInvalidAddress(),
            StreamErrorMapper.forWakeNoMac(),
            StreamErrorMapper.forWakeFailed(),
        )

        samples.forEach(::assertCleanChineseError)
        assertFalse(StreamErrorMapper.forHostUpgradeRequired().retryable)
        assertFalse(StreamErrorMapper.forWakeNoMac().retryable)
    }

    @Test
    fun stageLabelsProduceChineseProgressStrings() {
        for (stage in listOf("app", "rtsp", "control", "video", "audio", "input", null)) {
            val label = StreamErrorMapper.forStageLabel(stage)
            assertTrue(label.isNotBlank())
            assertTrue(label.endsWith("…"))
        }
    }
}
