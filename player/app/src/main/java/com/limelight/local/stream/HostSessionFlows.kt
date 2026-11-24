package com.limelight.local.stream

import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

/** Outcome of executing GameStream [PairingManager.pair] with guaranteed cancel cleanup. */
sealed interface GameStreamPairOutcome {
    data class Paired(val serverCert: X509Certificate?) : GameStreamPairOutcome
    data class FailedState(val state: PairingManager.PairState) : GameStreamPairOutcome
    data object Cancelled : GameStreamPairOutcome
}

/**
 * Runs GameStream pairing with real socket cancellation and guaranteed best-effort `/unpair`
 * cleanup inside a [NonCancellable] `finally` block (S3).
 *
 * Because cleanup runs inside the cancelled coroutine's `NonCancellable` `finally`, any immediate
 * retry that joins the previous operation job is guaranteed to wait until `/unpair` has released
 * the host's in-progress pairing lock before starting the new `pm.pair()` call.
 */
object GameStreamPairRunner {
    suspend fun run(
        httpFactory: () -> NvHTTP,
        existingServerCert: X509Certificate?,
        pin: String,
        token: CallCancelToken,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): GameStreamPairOutcome {
        val gsPairStarted = AtomicBoolean(false)
        var pairSucceeded = false
        try {
            val outcome: GameStreamPairOutcome = withContext(ioDispatcher) {
                if (token.isCancelled) return@withContext GameStreamPairOutcome.Cancelled
                val http = httpFactory()
                val unregister = token.register { http.cancel() }
                try {
                    val serverInfo = http.getServerInfo(true)
                    if (token.isCancelled) return@withContext GameStreamPairOutcome.Cancelled
                    val alreadyPaired = http.getPairState(serverInfo) == PairingManager.PairState.PAIRED
                    if (alreadyPaired) {
                        GameStreamPairOutcome.Paired(existingServerCert)
                    } else {
                        gsPairStarted.set(true)
                        val pm = http.pairingManager
                        val state = pm.pair(serverInfo, pin)
                        if (token.isCancelled) {
                            GameStreamPairOutcome.Cancelled
                        } else if (state == PairingManager.PairState.PAIRED) {
                            GameStreamPairOutcome.Paired(pm.pairedCert ?: existingServerCert)
                        } else {
                            GameStreamPairOutcome.FailedState(state)
                        }
                    }
                } finally {
                    unregister()
                }
            }
            if (outcome is GameStreamPairOutcome.Paired && !token.isCancelled) {
                pairSucceeded = true
                return outcome
            }
            return if (token.isCancelled) GameStreamPairOutcome.Cancelled else outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (token.isCancelled) {
                return GameStreamPairOutcome.Cancelled
            }
            throw e
        } finally {
            if (token.isCancelled && gsPairStarted.get() && !pairSucceeded) {
                withContext(NonCancellable + ioDispatcher) {
                    try {
                        httpFactory().unpair()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }
}

/** Result of the full depth3d Host auto-pair orchestration (`/info` -> `/pair` -> GameStream pair -> commit pin). */
sealed interface HostAutoPairOutcome {
    data class Paired(
        val hostInfo: HostInfo,
        val hostFingerprint: String,
        val gsServerCert: X509Certificate,
    ) : HostAutoPairOutcome

    data class InfoIdentityMismatch(val expectedId: String, val actualId: String) : HostAutoPairOutcome
    data object UpgradeRequired : HostAutoPairOutcome
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : HostAutoPairOutcome
    data class InfoFailed(val detail: String) : HostAutoPairOutcome
    data class PairRejectedOrFailed(val result: AutoPairResult, val streamReason: String?) : HostAutoPairOutcome
    data class GameStreamFailed(val state: PairingManager.PairState) : HostAutoPairOutcome
    data object Cancelled : HostAutoPairOutcome
}

/**
 * Pure-JVM orchestrator enforcing B1/B2/B3/S2 end-to-end:
 * 1. Always probes `/info` first and verifies `info.id == hostId` + TLS cert before sending `/pair` (B2/S2).
 * 2. Sends `/pair` strictly pinned to the `/info`-verified fingerprint; never sends client cert unless
 *    a matching saved paired trust already exists (B1).
 * 3. Runs GameStream pairing only after `/pair` returns 200 Approved.
 * 4. Persists [HostPairedTrust] in [pinStore] ONLY after `/info`, `/pair`, and GameStream pairing
 *    all succeed (B3/S2). Never persists on 403/408/500/503, identity mismatch, or failure.
 */
object HostAutoPairOrchestrator {
    suspend fun execute(
        hostApi: HostProtocolApi,
        pinStore: HostCertPinStore,
        address: String,
        controlPort: Int,
        hostId: String,
        deviceName: String,
        deviceId: String,
        pin: String,
        computerUuid: String,
        alreadyPairedGsCert: X509Certificate?,
        approvedNewFingerprint: String?,
        token: CallCancelToken,
        onInfoVerified: (HostInfo) -> Unit = {},
        onWaitingAllow: () -> Unit = {},
        onCompletingGameStream: () -> Unit = {},
        runGameStreamPair: suspend (pin: String, token: CallCancelToken) -> GameStreamPairOutcome,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): HostAutoPairOutcome {
        // Step 1 (B2/S2): Mandatory /info verification BEFORE /pair is ever called.
        val infoRes = withContext(ioDispatcher) {
            hostApi.getInfo(
                address = address,
                port = controlPort,
                hostId = hostId,
                approvedNewFingerprint = approvedNewFingerprint,
                cancelToken = token,
            )
        }
        if (token.isCancelled || infoRes == HostInfoResult.Cancelled) {
            return HostAutoPairOutcome.Cancelled
        }
        val verifiedInfo: HostInfo
        val verifiedFp: String
        when (infoRes) {
            is HostInfoResult.Ok -> {
                verifiedInfo = infoRes.info
                verifiedFp = infoRes.fingerprint
                onInfoVerified(verifiedInfo)
            }
            is HostInfoResult.IdentityMismatch ->
                return HostAutoPairOutcome.InfoIdentityMismatch(infoRes.expectedId, infoRes.actualId)
            HostInfoResult.UpgradeRequired ->
                return HostAutoPairOutcome.UpgradeRequired
            is HostInfoResult.CertificateChanged ->
                return HostAutoPairOutcome.CertificateChanged(infoRes.hostId, infoRes.expected, infoRes.actual)
            HostInfoResult.Cancelled ->
                return HostAutoPairOutcome.Cancelled
            is HostInfoResult.Failed ->
                // B2: Do NOT proceed to /pair or send the PIN when /info failed!
                return HostAutoPairOutcome.InfoFailed(infoRes.detail)
        }

        // Step 2 (B1/#10): Send client cert ONLY if we already have a persisted paired trust
        // matching this exact Host certificate fingerprint and GameStream cert.
        val existingTrust = pinStore.getPairedTrust(hostId)
        val presentClientCert = existingTrust != null &&
            alreadyPairedGsCert != null &&
            existingTrust.hostFingerprint.equals(verifiedFp, ignoreCase = true) &&
            existingTrust.computerUuid.equals(computerUuid, ignoreCase = true) &&
            existingTrust.gsCertFingerprint.equals(HttpHostProtocolClient.sha256Hex(alreadyPairedGsCert), ignoreCase = true)

        onWaitingAllow()
        val pairResp = withContext(ioDispatcher) {
            hostApi.requestAutoPair(
                address = address,
                port = controlPort,
                hostId = hostId,
                deviceName = deviceName,
                deviceId = deviceId,
                pin = pin,
                expectedFingerprint = verifiedFp,
                presentClientCert = presentClientCert,
                approvedNewFingerprint = approvedNewFingerprint,
                cancelToken = token,
            )
        }
        if (token.isCancelled || pairResp == AutoPairResult.Cancelled) {
            return HostAutoPairOutcome.Cancelled
        }
        when (pairResp) {
            AutoPairResult.Approved -> {
                onCompletingGameStream()
                return when (val gsOutcome = runGameStreamPair(pin, token)) {
                    is GameStreamPairOutcome.Paired -> {
                        val cert = gsOutcome.serverCert ?: alreadyPairedGsCert
                        if (token.isCancelled || cert == null) {
                            if (token.isCancelled) HostAutoPairOutcome.Cancelled
                            else HostAutoPairOutcome.GameStreamFailed(PairingManager.PairState.FAILED)
                        } else {
                            // B3 & S2: Persist Host TLS pin + pairing binding ONLY after full pairing succeeds!
                            pinStore.savePairedTrust(
                                hostId = hostId,
                                trust = HostPairedTrust(
                                    hostFingerprint = verifiedFp,
                                    computerUuid = computerUuid,
                                    gsCertFingerprint = HttpHostProtocolClient.sha256Hex(cert),
                                ),
                            )
                            HostAutoPairOutcome.Paired(verifiedInfo, verifiedFp, cert)
                        }
                    }
                    is GameStreamPairOutcome.FailedState -> HostAutoPairOutcome.GameStreamFailed(gsOutcome.state)
                    GameStreamPairOutcome.Cancelled -> HostAutoPairOutcome.Cancelled
                }
            }
            AutoPairResult.StreamServiceUnavailable -> {
                val freshInfo = withContext(ioDispatcher) {
                    hostApi.getInfo(
                        address = address,
                        port = controlPort,
                        hostId = hostId,
                        approvedNewFingerprint = approvedNewFingerprint,
                        cancelToken = token,
                    )
                }
                val reason = (freshInfo as? HostInfoResult.Ok)?.info?.also(onInfoVerified)?.stream?.reason
                    ?: verifiedInfo.stream.reason
                return HostAutoPairOutcome.PairRejectedOrFailed(pairResp, reason)
            }
            is AutoPairResult.CertificateChanged ->
                return HostAutoPairOutcome.CertificateChanged(pairResp.hostId, pairResp.expected, pairResp.actual)
            AutoPairResult.UpgradeRequired ->
                return HostAutoPairOutcome.UpgradeRequired
            AutoPairResult.Cancelled ->
                return HostAutoPairOutcome.Cancelled
            AutoPairResult.Rejected,
            AutoPairResult.TimedOut,
            is AutoPairResult.Failed ->
                return HostAutoPairOutcome.PairRejectedOrFailed(pairResp, null)
        }
    }
}
