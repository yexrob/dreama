package com.limelight.local.stream

import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks ALL cancelled pairing jobs whose `NonCancellable` `/unpair` cleanup is still in flight (#8).
 * Never drops an earlier cleanup job when the user does `cancel -> retry -> cancel while waiting -> retry`.
 */
class PendingCleanupQueue {
    private val pendingJobs = CopyOnWriteArrayList<Job>()

    fun track(job: Job) {
        // NOTE: A job running its `finally { withContext(NonCancellable) { ... } }` cleanup is in
        // state `Cancelling`, where `isActive == false` AND `isCompleted == false`! Must check
        // `isCompleted` (never `isActive`) so cancelling cleanup jobs are never dropped.
        if (job.isCompleted) return
        pendingJobs.add(job)
        job.invokeOnCompletion { pendingJobs.remove(job) }
        if (job.isCompleted) {
            pendingJobs.remove(job)
        }
    }

    fun pendingCount(): Int = pendingJobs.count { !it.isCompleted }

    suspend fun awaitAll(timeoutMs: Long) {
        val snapshot = pendingJobs.filter { !it.isCompleted }
        if (snapshot.isEmpty()) return
        withTimeoutOrNull(timeoutMs) {
            for (job in snapshot) {
                job.join()
            }
        }
    }
}

/** Outcome of executing GameStream [PairingManager.pair] with guaranteed cancel cleanup. */
sealed interface GameStreamPairOutcome {
    data class Paired(val serverCert: X509Certificate?) : GameStreamPairOutcome
    data class FailedState(val state: PairingManager.PairState) : GameStreamPairOutcome
    data object Cancelled : GameStreamPairOutcome
}

/**
 * Runs GameStream pairing with:
 * - Strict HTTPS pinned-certificate verification before ever skipping `pm.pair` (#2): never trusts
 *   an HTTP fallback or CA-signed non-matching cert; if the endpoint at this address cannot prove
 *   possession of [existingServerCert] and [expectedComputerUuid] over HTTPS, forces full `pm.pair`.
 * - Guaranteed best-effort `/unpair` cleanup inside a [NonCancellable] `finally` block (S3).
 */
object GameStreamPairRunner {
    suspend fun run(
        httpFactory: () -> NvHTTP,
        existingServerCert: X509Certificate?,
        expectedComputerUuid: String? = null,
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
                    // #2: Only skip pm.pair if the target endpoint proves over HTTPS (no HTTP fallback,
                    // no CA fallback) that it holds the exact saved paired GameStream serverCert!
                    if (existingServerCert != null && http.verifyPinnedPairedServer(expectedComputerUuid)) {
                        return@withContext if (token.isCancelled) {
                            GameStreamPairOutcome.Cancelled
                        } else {
                            GameStreamPairOutcome.Paired(existingServerCert)
                        }
                    }
                    if (token.isCancelled) return@withContext GameStreamPairOutcome.Cancelled

                    // Verification failed or no existing cert -> force full cryptographic PIN pairing!
                    val serverInfo = http.getServerInfo(true)
                    if (token.isCancelled) return@withContext GameStreamPairOutcome.Cancelled
                    gsPairStarted.set(true)
                    val pm = http.pairingManager
                    val state = pm.pair(serverInfo, pin)
                    if (token.isCancelled) {
                        GameStreamPairOutcome.Cancelled
                    } else if (state == PairingManager.PairState.PAIRED) {
                        GameStreamPairOutcome.Paired(pm.pairedCert)
                    } else {
                        GameStreamPairOutcome.FailedState(state)
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

/** Result of the full depth3d Host pairing orchestration (`/info` -> `/pair` -> GameStream -> commit pin). */
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
 * Pure-JVM orchestrator enforcing B1/B2/B3/S2/#1/#2/#6/#7 end-to-end:
 * 1. Always probes `/info` first and verifies `info.id == hostId` + TLS cert before sending `/pair`.
 * 2. Compares against in-memory [discoveredFingerprint] when unpinned (#6) -> surfaces CertificateChanged
 *    if the cert changed between discovery and user-initiated pairing.
 * 3. Sends `/pair` strictly pinned to the `/info`-verified fingerprint; never sends client cert unless
 *    a matching saved paired trust already exists.
 * 4. Runs GameStream verification/pairing against the Host's own address (`GameStreamPairRunner`).
 * 5. Persists [HostPairedTrust] (including [savedName]) ONLY after all checks succeed.
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
        discoveredFingerprint: String? = null,
        savedName: String? = null,
        token: CallCancelToken,
        onInfoVerified: (HostInfo, String) -> Unit = { _, _ -> },
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
                // #6: If we observed a fingerprint during discovery (unpinned host) and it changed
                // before pairing without user approval, prompt "Certificate Changed"!
                val savedPin = pinStore.getFingerprint(hostId)
                if (savedPin == null &&
                    !discoveredFingerprint.isNullOrBlank() &&
                    !discoveredFingerprint.equals(verifiedFp, ignoreCase = true) &&
                    (approvedNewFingerprint == null || !approvedNewFingerprint.equals(verifiedFp, ignoreCase = true))
                ) {
                    return HostAutoPairOutcome.CertificateChanged(
                        hostId = hostId,
                        expected = discoveredFingerprint,
                        actual = verifiedFp,
                    )
                }
                onInfoVerified(verifiedInfo, verifiedFp)
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
                        val cert = gsOutcome.serverCert
                        if (token.isCancelled || cert == null) {
                            if (token.isCancelled) HostAutoPairOutcome.Cancelled
                            else HostAutoPairOutcome.GameStreamFailed(PairingManager.PairState.FAILED)
                        } else {
                            // B3 & S2: Persist Host TLS pin + pairing binding ONLY after full verification/pairing succeeds!
                            val trustName = existingTrust?.savedName?.takeIf { it.isNotBlank() }
                                ?: savedName?.takeIf { it.isNotBlank() }
                                ?: verifiedInfo.name
                            pinStore.savePairedTrust(
                                hostId = hostId,
                                trust = HostPairedTrust(
                                    hostFingerprint = verifiedFp,
                                    computerUuid = computerUuid,
                                    gsCertFingerprint = HttpHostProtocolClient.sha256Hex(cert),
                                    savedName = trustName,
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
                val reason = (freshInfo as? HostInfoResult.Ok)?.let {
                    onInfoVerified(it.info, it.fingerprint)
                    it.info.stream.reason
                } ?: verifiedInfo.stream.reason
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

/**
 * Manual-PIN pairing orchestrator for depth3d Hosts that do NOT advertise `pair=auto` (#11).
 * When a user confirms a changed certificate (or manually PIN-pairs with a Host), verifies `/info`
 * (`info.id == hostId` and exact fingerprint), runs GameStream pairing, and persists [HostPairedTrust]
 * ONLY after GameStream pairing succeeds so the certificate-change prompt does not repeat forever.
 */
object HostManualPairOrchestrator {
    suspend fun execute(
        hostApi: HostProtocolApi,
        pinStore: HostCertPinStore,
        address: String,
        controlPort: Int,
        hostId: String,
        pin: String,
        computerUuid: String,
        approvedNewFingerprint: String?,
        discoveredFingerprint: String? = null,
        savedName: String? = null,
        token: CallCancelToken,
        onInfoVerified: (HostInfo, String) -> Unit = { _, _ -> },
        onShowPin: () -> Unit = {},
        runGameStreamPair: suspend (pin: String, token: CallCancelToken) -> GameStreamPairOutcome,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): HostAutoPairOutcome {
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
                val savedPin = pinStore.getFingerprint(hostId)
                if (savedPin == null &&
                    !discoveredFingerprint.isNullOrBlank() &&
                    !discoveredFingerprint.equals(verifiedFp, ignoreCase = true) &&
                    (approvedNewFingerprint == null || !approvedNewFingerprint.equals(verifiedFp, ignoreCase = true))
                ) {
                    return HostAutoPairOutcome.CertificateChanged(
                        hostId = hostId,
                        expected = discoveredFingerprint,
                        actual = verifiedFp,
                    )
                }
                onInfoVerified(verifiedInfo, verifiedFp)
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
                return HostAutoPairOutcome.InfoFailed(infoRes.detail)
        }

        onShowPin()
        return when (val gsOutcome = runGameStreamPair(pin, token)) {
            is GameStreamPairOutcome.Paired -> {
                val cert = gsOutcome.serverCert
                if (token.isCancelled || cert == null) {
                    if (token.isCancelled) HostAutoPairOutcome.Cancelled
                    else HostAutoPairOutcome.GameStreamFailed(PairingManager.PairState.FAILED)
                } else {
                    val existingTrust = pinStore.getPairedTrust(hostId)
                    val trustName = existingTrust?.savedName?.takeIf { it.isNotBlank() }
                        ?: savedName?.takeIf { it.isNotBlank() }
                        ?: verifiedInfo.name
                    pinStore.savePairedTrust(
                        hostId = hostId,
                        trust = HostPairedTrust(
                            hostFingerprint = verifiedFp,
                            computerUuid = computerUuid,
                            gsCertFingerprint = HttpHostProtocolClient.sha256Hex(cert),
                            savedName = trustName,
                        ),
                    )
                    HostAutoPairOutcome.Paired(verifiedInfo, verifiedFp, cert)
                }
            }
            is GameStreamPairOutcome.FailedState -> HostAutoPairOutcome.GameStreamFailed(gsOutcome.state)
            GameStreamPairOutcome.Cancelled -> HostAutoPairOutcome.Cancelled
        }
    }
}
