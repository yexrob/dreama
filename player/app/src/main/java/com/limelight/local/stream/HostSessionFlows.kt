package com.limelight.local.stream

import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
 * Runs GameStream verification and pairing with:
 * - Strict HTTPS pinned-certificate verification (`verifyExistingPair`, #2 & host-protocol.md §升级路径):
 *   never trusts HTTP fallback or CA-signed non-matching cert; proves the endpoint at this IPv4
 *   holds the exact saved [existingServerCert] and [expectedComputerUuid].
 * - Guaranteed best-effort `/unpair` cleanup inside a [NonCancellable] `finally` block (S3).
 */
object GameStreamPairRunner {
    /**
     * Strictly verifies over HTTPS-only (exact [existingServerCert] pinning, no HTTP fallback,
     * no CA fallback) that the endpoint created by [httpFactory] is already paired with this
     * headset and matches [expectedComputerUuid].
     */
    suspend fun verifyExistingPair(
        httpFactory: () -> NvHTTP,
        existingServerCert: X509Certificate?,
        expectedComputerUuid: String? = null,
        token: CallCancelToken,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean {
        if (existingServerCert == null || token.isCancelled) return false
        return try {
            withContext(ioDispatcher) {
                if (token.isCancelled) return@withContext false
                val http = httpFactory()
                val unregister = token.register { http.cancel() }
                try {
                    if (token.isCancelled) false else http.verifyPinnedPairedServer(expectedComputerUuid)
                } finally {
                    unregister()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Always executes full cryptographic `PairingManager.pair(serverInfo, pin)` (no skip),
     * with guaranteed [NonCancellable] `/unpair` cleanup on cancel (S3).
     */
    suspend fun runFullPair(
        httpFactory: () -> NvHTTP,
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

    /**
     * Used by manual-PIN / non-Host flows: skips `pm.pair` ONLY if strict HTTPS pinned-cert
     * verification proves the endpoint holds [existingServerCert], else runs full `pm.pair`.
     */
    suspend fun run(
        httpFactory: () -> NvHTTP,
        existingServerCert: X509Certificate?,
        expectedComputerUuid: String? = null,
        pin: String,
        token: CallCancelToken,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): GameStreamPairOutcome {
        if (existingServerCert != null &&
            verifyExistingPair(httpFactory, existingServerCert, expectedComputerUuid, token, ioDispatcher)
        ) {
            return if (token.isCancelled) {
                GameStreamPairOutcome.Cancelled
            } else {
                GameStreamPairOutcome.Paired(existingServerCert)
            }
        }
        if (token.isCancelled) return GameStreamPairOutcome.Cancelled
        return runFullPair(httpFactory, pin, token, ioDispatcher)
    }
}

/** Result of the full two-step depth3d Host pairing orchestration (`/info` -> `/pair` -> GameStream -> `/pair/complete` -> commit pin). */
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
    data class PairCompleteFailed(val result: PairCompleteResult) : HostAutoPairOutcome
    data object Cancelled : HostAutoPairOutcome
}

/**
 * Pure-JVM orchestrator implementing `docs/specs/host-protocol.md` v1 two-step pairing:
 * 1. Probes `/info` first and verifies `info.id == hostId` + TLS cert before ever calling `/pair` (B2/#6).
 * 2. If already paired at GameStream layer (`alreadyPairedGsCert != null`), runs strict HTTPS pinned-cert
 *    verification against the same IPv4's GameStream port FIRST (§已经在串流层面配过对的头显, #2/#3).
 *    Only if this succeeds (or a matching persisted `HostPairedTrust` already exists) is the client cert
 *    presented on `/pair`.
 * 3. Calls `POST /depth3d/v1/pair` (consent only, returns immediately with `next` + `ticket`).
 * 4. If `next == DONE` AND step 2 verified the existing GameStream cert, skips GameStream pairing;
 *    otherwise executes standard GameStream `pm.pair` with the exact same `pin`.
 * 5. Calls `POST /depth3d/v1/pair/complete` with client cert + `ticket`, retrying `409 not_paired`
 *    within 5 s. Persists [HostPairedTrust] ONLY after `/pair/complete` returns `200 OK`!
 */
object HostAutoPairOrchestrator {
    val DEFAULT_NOT_PAIRED_RETRY_DELAYS_MS: List<Long> = listOf(400L, 700L, 1_000L, 1_200L, 1_500L)

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
        onCompletingPairRecord: () -> Unit = {},
        verifyExistingGameStreamPair: suspend (token: CallCancelToken) -> Boolean = { false },
        runGameStreamPair: suspend (pin: String, token: CallCancelToken) -> GameStreamPairOutcome,
        notPairedRetryDelaysMs: List<Long> = DEFAULT_NOT_PAIRED_RETRY_DELAYS_MS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): HostAutoPairOutcome {
        // Step 1 (B2/S2/#6): Mandatory /info verification BEFORE /pair is ever called.
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

        // Step 2 (host-protocol.md §已经在串流层面配过对的头显):
        // If we already hold a paired GameStream server cert, strictly verify over HTTPS against
        // this exact IPv4 endpoint BEFORE calling /pair. Only if this succeeds (or we already hold
        // a matching persisted HostPairedTrust) may we present our client certificate on /pair.
        val verifiedExistingGsPair = if (alreadyPairedGsCert != null) {
            verifyExistingGameStreamPair(token)
        } else {
            false
        }
        if (token.isCancelled) return HostAutoPairOutcome.Cancelled

        val existingTrust = pinStore.getPairedTrust(hostId)
        val hasMatchingSavedTrust = existingTrust != null &&
            alreadyPairedGsCert != null &&
            existingTrust.hostFingerprint.equals(verifiedFp, ignoreCase = true) &&
            existingTrust.computerUuid.equals(computerUuid, ignoreCase = true) &&
            existingTrust.gsCertFingerprint.equals(HttpHostProtocolClient.sha256Hex(alreadyPairedGsCert), ignoreCase = true)
        val presentClientCert = hasMatchingSavedTrust || verifiedExistingGsPair

        // Step 3: POST /depth3d/v1/pair (consent only; returns immediately with next + ticket)
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
                verifiedGameStreamUpgrade = verifiedExistingGsPair,
                approvedNewFingerprint = approvedNewFingerprint,
                cancelToken = token,
            )
        }
        if (token.isCancelled || pairResp == AutoPairResult.Cancelled) {
            return HostAutoPairOutcome.Cancelled
        }
        val approved = when (pairResp) {
            is AutoPairResult.Approved -> pairResp
            is AutoPairResult.StreamServiceUnavailable -> {
                val reason = pairResp.reason?.takeIf { it.isNotBlank() } ?: run {
                    val freshInfo = withContext(ioDispatcher) {
                        hostApi.getInfo(
                            address = address,
                            port = controlPort,
                            hostId = hostId,
                            approvedNewFingerprint = approvedNewFingerprint,
                            cancelToken = token,
                        )
                    }
                    (freshInfo as? HostInfoResult.Ok)?.let {
                        onInfoVerified(it.info, it.fingerprint)
                        it.info.stream.reason
                    } ?: verifiedInfo.stream.reason
                }
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
            AutoPairResult.Busy,
            is AutoPairResult.Cooldown,
            is AutoPairResult.Failed ->
                return HostAutoPairOutcome.PairRejectedOrFailed(pairResp, null)
        }

        // Step 4: Run GameStream pairing unless Host returned next="done" AND step 2 already
        // cryptographically verified the existing GameStream cert on this exact IPv4 endpoint!
        val gsCert: X509Certificate = if (
            approved.next == PairNextStep.DONE &&
            verifiedExistingGsPair &&
            alreadyPairedGsCert != null
        ) {
            alreadyPairedGsCert
        } else {
            onCompletingGameStream()
            when (val gsOutcome = runGameStreamPair(pin, token)) {
                is GameStreamPairOutcome.Paired -> {
                    val cert = gsOutcome.serverCert
                    if (token.isCancelled) return HostAutoPairOutcome.Cancelled
                    cert ?: return HostAutoPairOutcome.GameStreamFailed(PairingManager.PairState.FAILED)
                }
                is GameStreamPairOutcome.FailedState ->
                    return HostAutoPairOutcome.GameStreamFailed(gsOutcome.state)
                GameStreamPairOutcome.Cancelled ->
                    return HostAutoPairOutcome.Cancelled
            }
        }
        if (token.isCancelled) return HostAutoPairOutcome.Cancelled

        // Step 5: POST /depth3d/v1/pair/complete with client cert + ticket.
        // Retry 409 not_paired within 5 s in case the streaming engine hasn't flushed the new cert yet.
        onCompletingPairRecord()
        var lastCompleteResult: PairCompleteResult = PairCompleteResult.Failed("pair/complete not attempted")
        val maxAttempts = notPairedRetryDelaysMs.size + 1
        for (attempt in 0 until maxAttempts) {
            if (token.isCancelled) return HostAutoPairOutcome.Cancelled
            val completeRes = withContext(ioDispatcher) {
                hostApi.completeAutoPair(
                    address = address,
                    port = controlPort,
                    hostId = hostId,
                    ticket = approved.ticket,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    expectedFingerprint = verifiedFp,
                    approvedNewFingerprint = approvedNewFingerprint,
                    cancelToken = token,
                )
            }
            if (token.isCancelled || completeRes == PairCompleteResult.Cancelled) {
                return HostAutoPairOutcome.Cancelled
            }
            when (completeRes) {
                is PairCompleteResult.Completed -> {
                    // ONLY NOW persist Host trust (host-protocol.md §头显如何信任 Host & §pair/complete)!
                    val trustName = existingTrust?.savedName?.takeIf { it.isNotBlank() }
                        ?: savedName?.takeIf { it.isNotBlank() }
                        ?: verifiedInfo.name
                    pinStore.savePairedTrust(
                        hostId = hostId,
                        trust = HostPairedTrust(
                            hostFingerprint = verifiedFp,
                            computerUuid = computerUuid,
                            gsCertFingerprint = HttpHostProtocolClient.sha256Hex(gsCert),
                            savedName = trustName,
                        ),
                    )
                    val finalInfo = if (completeRes.mediaPort != null) {
                        verifiedInfo.copy(media = HostMediaConfig(completeRes.mediaPort))
                    } else {
                        verifiedInfo
                    }
                    return HostAutoPairOutcome.Paired(finalInfo, verifiedFp, gsCert)
                }
                PairCompleteResult.NotPaired -> {
                    lastCompleteResult = completeRes
                    if (attempt < notPairedRetryDelaysMs.size) {
                        delay(notPairedRetryDelaysMs[attempt])
                    }
                }
                PairCompleteResult.UpgradeRequired ->
                    return HostAutoPairOutcome.UpgradeRequired
                is PairCompleteResult.CertificateChanged ->
                    return HostAutoPairOutcome.CertificateChanged(completeRes.hostId, completeRes.expected, completeRes.actual)
                PairCompleteResult.Cancelled ->
                    return HostAutoPairOutcome.Cancelled
                PairCompleteResult.TicketExpired,
                PairCompleteResult.Unauthorized,
                PairCompleteResult.Forbidden,
                is PairCompleteResult.Failed ->
                    return HostAutoPairOutcome.PairCompleteFailed(completeRes)
            }
        }
        return HostAutoPairOutcome.PairCompleteFailed(lastCompleteResult)
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
