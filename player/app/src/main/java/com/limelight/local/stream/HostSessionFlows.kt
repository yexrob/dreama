package com.limelight.local.stream

import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** Checks whether an already-paired computer's newly obtained GameStream serverCert requires user confirmation (S3). */
object StreamCertChangeGuard {
    data class Mismatch(val expectedFingerprint: String, val actualFingerprint: String)

    fun detectChange(
        existingServerCert: X509Certificate?,
        newServerCert: X509Certificate,
        approvedNewGsCertFingerprint: String?,
    ): Mismatch? {
        val existing = existingServerCert ?: return null
        val oldFp = HttpHostProtocolClient.sha256Hex(existing)
        val newFp = HttpHostProtocolClient.sha256Hex(newServerCert)
        if (oldFp.equals(newFp, ignoreCase = true)) return null
        if (!approvedNewGsCertFingerprint.isNullOrBlank() &&
            approvedNewGsCertFingerprint.equals(newFp, ignoreCase = true)
        ) {
            return null
        }
        return Mismatch(expectedFingerprint = oldFp, actualFingerprint = newFp)
    }
}

/** Computes retry button cooldown seconds for 429 cooldown/busy responses (M2). */
object RetryCooldown {
    const val DEFAULT_COOLDOWN_SEC = 10
    const val DEFAULT_BUSY_SEC = 3

    fun initialSeconds(result: AutoPairResult): Int = when (result) {
        is AutoPairResult.Cooldown -> (result.retryAfterSec ?: DEFAULT_COOLDOWN_SEC).coerceIn(1, 300)
        is AutoPairResult.Busy -> (result.retryAfterSec ?: DEFAULT_BUSY_SEC).coerceIn(1, 60)
        else -> 0
    }
}

/**
 * Snapshot of a completed-so-far pairing state awaiting `POST /depth3d/v1/pair/complete` (S4).
 * Allows retrying ONLY `/pair/complete` while the Host's 120-second `ticket` is still valid,
 * preserving `approvedNewFingerprint` and `gsServerCert` without re-triggering a Mac popup.
 */
data class PendingPairComplete(
    val address: String,
    val controlPort: Int,
    val hostId: String,
    val hostInfo: HostInfo,
    val hostFingerprint: String,
    val ticket: String,
    val issuedAtMs: Long,
    val deviceId: String,
    val deviceName: String,
    val computerUuid: String,
    val gsServerCert: X509Certificate,
    val approvedNewFingerprint: String?,
    val savedName: String?,
) {
    fun isTicketLikelyValid(nowMs: Long): Boolean =
        nowMs - issuedAtMs < TICKET_LIFETIME_MS - TICKET_SAFETY_MARGIN_MS

    companion object {
        const val TICKET_LIFETIME_MS = 120_000L
        const val TICKET_SAFETY_MARGIN_MS = 5_000L
    }
}

/** Outcome of executing GameStream [PairingManager.pair] with guaranteed cancel/timeout cleanup. */
sealed interface GameStreamPairOutcome {
    data class Paired(val serverCert: X509Certificate?) : GameStreamPairOutcome
    data class FailedState(val state: PairingManager.PairState) : GameStreamPairOutcome
    data object TimedOut : GameStreamPairOutcome
    data object Cancelled : GameStreamPairOutcome
}

/**
 * Runs GameStream verification and pairing with:
 * - Strict HTTPS pinned-certificate verification (`verifyExistingPair`, #2 & host-protocol.md §升级路径):
 *   never trusts HTTP fallback or CA-signed non-matching cert; never calls `/unpair` on cancel (M7).
 * - Overall 75-second timeout cap on `runFullPair` (S1) that immediately closes the blocking socket
 *   and runs best-effort `/unpair` cleanup inside a [NonCancellable] `finally` block (S3).
 */
object GameStreamPairRunner {
    const val DEFAULT_GAMESTREAM_PAIR_TIMEOUT_MS = 75_000L

    /**
     * Strictly verifies over HTTPS-only (exact [existingServerCert] pinning, no HTTP fallback,
     * no CA fallback) that the endpoint created by [httpFactory] is already paired with this
     * headset and matches [expectedComputerUuid]. Never calls `/unpair` (M7).
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
     * enforcing an overall [pairTimeoutMs] cap (default 75 s, S1) and guaranteed [NonCancellable]
     * `/unpair` cleanup on cancel or timeout (S3).
     */
    suspend fun runFullPair(
        httpFactory: () -> NvHTTP,
        pin: String,
        token: CallCancelToken,
        pairTimeoutMs: Long = DEFAULT_GAMESTREAM_PAIR_TIMEOUT_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): GameStreamPairOutcome {
        val gsPairStarted = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        var pairSucceeded = false
        val internalToken = CallCancelToken()
        val unlinkOuter = token.register { internalToken.cancel() }
        try {
            val outcome: GameStreamPairOutcome = coroutineScope {
                val watchdog = launch {
                    delayFn(pairTimeoutMs)
                    if (!token.isCancelled) {
                        timedOut.set(true)
                        internalToken.cancel()
                    }
                }
                try {
                    withContext(ioDispatcher) {
                        if (token.isCancelled) return@withContext GameStreamPairOutcome.Cancelled
                        if (timedOut.get()) return@withContext GameStreamPairOutcome.TimedOut
                        val http = httpFactory()
                        val unregister = internalToken.register { http.cancel() }
                        try {
                            val serverInfo = http.getServerInfo(true)
                            if (token.isCancelled) return@withContext GameStreamPairOutcome.Cancelled
                            if (timedOut.get()) return@withContext GameStreamPairOutcome.TimedOut
                            gsPairStarted.set(true)
                            val pm = http.pairingManager
                            val state = pm.pair(serverInfo, pin)
                            when {
                                token.isCancelled -> GameStreamPairOutcome.Cancelled
                                timedOut.get() -> GameStreamPairOutcome.TimedOut
                                state == PairingManager.PairState.PAIRED -> GameStreamPairOutcome.Paired(pm.pairedCert)
                                else -> GameStreamPairOutcome.FailedState(state)
                            }
                        } finally {
                            unregister()
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
            if (outcome is GameStreamPairOutcome.Paired && !token.isCancelled && !timedOut.get()) {
                pairSucceeded = true
                return outcome
            }
            return when {
                token.isCancelled -> GameStreamPairOutcome.Cancelled
                timedOut.get() -> GameStreamPairOutcome.TimedOut
                else -> outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return when {
                token.isCancelled -> GameStreamPairOutcome.Cancelled
                timedOut.get() -> GameStreamPairOutcome.TimedOut
                else -> throw e
            }
        } finally {
            unlinkOuter()
            if ((token.isCancelled || timedOut.get()) && gsPairStarted.get() && !pairSucceeded) {
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
     * verification proves the endpoint holds [existingServerCert], else runs full `pm.pair`
     * with the 75-second timeout cap.
     */
    suspend fun run(
        httpFactory: () -> NvHTTP,
        existingServerCert: X509Certificate?,
        expectedComputerUuid: String? = null,
        pin: String,
        token: CallCancelToken,
        pairTimeoutMs: Long = DEFAULT_GAMESTREAM_PAIR_TIMEOUT_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
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
        return runFullPair(httpFactory, pin, token, pairTimeoutMs, delayFn, ioDispatcher)
    }
}

/** Result of the full two-step depth3d Host pairing orchestration (`/info` -> `/pair` -> GameStream -> `/pair/complete` -> commit pin). */
sealed interface HostAutoPairOutcome {
    data class Paired(
        val hostInfo: HostInfo,
        val hostFingerprint: String,
        val gsServerCert: X509Certificate,
    ) : HostAutoPairOutcome

    /**
     * Manual PIN GameStream pairing succeeded on a v1 Host, but registering the headset on the Host
     * (`/pair` + `/pair/complete`) did not finish (B1). Trust is NOT saved; caller keeps GameStream
     * pairing so the card shows "完成设置后可以看这台电脑上的影片".
     */
    data class GameStreamPairedHostSetupIncomplete(
        val hostInfo: HostInfo,
        val hostFingerprint: String,
        val gsServerCert: X509Certificate,
        val hostFailure: HostAutoPairOutcome,
    ) : HostAutoPairOutcome

    data class InfoIdentityMismatch(val expectedId: String, val actualId: String) : HostAutoPairOutcome
    data object UpgradeRequired : HostAutoPairOutcome
    data class CertificateChanged(val hostId: String, val expected: String, val actual: String) : HostAutoPairOutcome

    /**
     * An already-paired computer presented a DIFFERENT GameStream server certificate during re-pair (S3).
     * Trust and `details.serverCert` are NOT overwritten until the user explicitly confirms!
     */
    data class StreamServerCertChanged(
        val expectedGsFingerprint: String,
        val actualGsFingerprint: String,
        val newGsCert: X509Certificate,
        val pendingComplete: PendingPairComplete?,
    ) : HostAutoPairOutcome

    data class InfoFailed(val detail: String) : HostAutoPairOutcome
    data class PairRejectedOrFailed(val result: AutoPairResult, val streamReason: String?) : HostAutoPairOutcome
    data object UnexpectedDoneWithoutVerifiedStream : HostAutoPairOutcome
    data class GameStreamFailed(val state: PairingManager.PairState) : HostAutoPairOutcome
    data object GameStreamTimedOut : HostAutoPairOutcome
    data class PairCompleteFailed(
        val result: PairCompleteResult,
        val pendingComplete: PendingPairComplete? = null,
    ) : HostAutoPairOutcome

    data object Cancelled : HostAutoPairOutcome
}

/**
 * Pure-JVM orchestrator implementing `docs/specs/host-protocol.md` v1 two-step pairing:
 * 1. Probes `/info` first and verifies `info.id == hostId` + TLS cert before ever calling `/pair` (B2/#6).
 * 2. If already paired at GameStream layer (`alreadyPairedGsCert != null`), runs strict HTTPS pinned-cert
 *    verification against the same IPv4's GameStream port FIRST (§已经在串流层面配过对的头显, #2/#3, M7).
 *    Only if this succeeds is the client cert presented on `/pair`.
 * 3. Calls `POST /depth3d/v1/pair` (consent only, returns immediately with `next` + `ticket`).
 *    On immediate post-cancel `429 busy`, waits `retryAfterSec` and retries ONCE automatically (S5).
 * 4. If `next == DONE`: NEVER enters blocking GameStream `pm.pair` (S1)! Uses verified `alreadyPairedGsCert`
 *    if step 2 succeeded; otherwise retries `/pair` without client cert (if sent) or fails immediately!
 *    If `next == GAMESTREAM`: runs standard GameStream `pm.pair` with 75-second cap (S1) and checks
 *    `StreamCertChangeGuard` before overwriting any existing `serverCert` (S3).
 * 5. Calls `POST /depth3d/v1/pair/complete` via [completeWithRetries] (S4), retrying `409 not_paired`
 *    within 5 s. This is the SOLE place in the entire codebase where `savePairedTrust` is called (B1),
 *    and only when `computerUuid` is non-blank (M4)!
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
        approvedNewGsCertFingerprint: String? = null,
        discoveredFingerprint: String? = null,
        savedName: String? = null,
        justCancelledPreviousPair: Boolean = false,
        token: CallCancelToken,
        onInfoVerified: (HostInfo, String) -> Unit = { _, _ -> },
        onWaitingAllow: () -> Unit = {},
        onCompletingGameStream: () -> Unit = {},
        onCompletingPairRecord: () -> Unit = {},
        verifyExistingGameStreamPair: suspend (token: CallCancelToken) -> Boolean = { false },
        runGameStreamPair: suspend (pin: String, token: CallCancelToken) -> GameStreamPairOutcome,
        notPairedRetryDelaysMs: List<Long> = DEFAULT_NOT_PAIRED_RETRY_DELAYS_MS,
        busyRetryDelayMs: (Int?) -> Long = { sec -> ((sec ?: 1).coerceIn(1, 5)) * 1_000L },
        clockMs: () -> Long = { System.currentTimeMillis() },
        delayFn: suspend (Long) -> Unit = { delay(it) },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): HostAutoPairOutcome {
        // M4: Refuse to pair if computerUuid is missing (persisting blank uuid produces unreadable trust).
        if (computerUuid.isBlank()) {
            return HostAutoPairOutcome.PairCompleteFailed(PairCompleteResult.Failed("missing_computer_uuid"))
        }

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

        // Step 2 (host-protocol.md §已经在串流层面配过对的头显, M7):
        // If we already hold a paired GameStream server cert, strictly verify over HTTPS against
        // this exact IPv4 endpoint BEFORE calling /pair. Never calls /unpair on cancel!
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

        // S1 + §升级路径: Only send client cert on /pair when we have a verified GameStream pair on
        // this exact IPv4 endpoint (prevents Host from returning next="done" when local stream isn't verified).
        var sendClientCert = verifiedExistingGsPair && (hasMatchingSavedTrust || alreadyPairedGsCert != null)
        var verifiedUpgradeFlag = verifiedExistingGsPair

        suspend fun invokePair(presentCert: Boolean, verifiedUpgrade: Boolean): AutoPairResult {
            onWaitingAllow()
            var resp = withContext(ioDispatcher) {
                hostApi.requestAutoPair(
                    address = address,
                    port = controlPort,
                    hostId = hostId,
                    deviceName = deviceName,
                    deviceId = deviceId,
                    pin = pin,
                    expectedFingerprint = verifiedFp,
                    presentClientCert = presentCert,
                    verifiedGameStreamUpgrade = verifiedUpgrade,
                    approvedNewFingerprint = approvedNewFingerprint,
                    cancelToken = token,
                )
            }
            // S5: If the previous /pair was just cancelled and Host briefly returns 429 busy while
            // closing its confirmation window, wait retryAfterSec and retry ONCE automatically!
            if (justCancelledPreviousPair && resp is AutoPairResult.Busy && !token.isCancelled) {
                val waitMs = busyRetryDelayMs(resp.retryAfterSec)
                delayFn(waitMs)
                if (token.isCancelled) return AutoPairResult.Cancelled
                onWaitingAllow()
                resp = withContext(ioDispatcher) {
                    hostApi.requestAutoPair(
                        address = address,
                        port = controlPort,
                        hostId = hostId,
                        deviceName = deviceName,
                        deviceId = deviceId,
                        pin = pin,
                        expectedFingerprint = verifiedFp,
                        presentClientCert = presentCert,
                        verifiedGameStreamUpgrade = verifiedUpgrade,
                        approvedNewFingerprint = approvedNewFingerprint,
                        cancelToken = token,
                    )
                }
            }
            return resp
        }

        // Step 3: POST /depth3d/v1/pair (consent only; returns immediately with next + ticket)
        var pairResp = invokePair(sendClientCert, verifiedUpgradeFlag)
        if (token.isCancelled || pairResp == AutoPairResult.Cancelled) {
            return HostAutoPairOutcome.Cancelled
        }

        // S1: If Host returned next="done" while we had sent client cert without local verified pair,
        // re-send /pair WITHOUT client cert so Host enters full pairing (next="gamestream")!
        if (pairResp is AutoPairResult.Approved &&
            pairResp.next == PairNextStep.DONE &&
            !verifiedExistingGsPair &&
            sendClientCert
        ) {
            sendClientCert = false
            verifiedUpgradeFlag = false
            pairResp = invokePair(presentCert = false, verifiedUpgrade = false)
            if (token.isCancelled || pairResp == AutoPairResult.Cancelled) {
                return HostAutoPairOutcome.Cancelled
            }
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
            is AutoPairResult.Busy,
            is AutoPairResult.Cooldown,
            is AutoPairResult.Failed ->
                return HostAutoPairOutcome.PairRejectedOrFailed(pairResp, null)
        }
        val ticketIssuedAtMs = clockMs()

        // Step 4: Branch strictly on approved.next (S1)!
        val gsCert: X509Certificate = when (approved.next) {
            PairNextStep.DONE -> {
                // S1: NEVER run blocking GameStream pm.pair when Host said next="done" (Host did NOT
                // submit PIN to the engine!). Either we already verified the stream cert in step 2,
                // or we fail cleanly immediately without hanging!
                if (verifiedExistingGsPair && alreadyPairedGsCert != null) {
                    alreadyPairedGsCert
                } else {
                    return HostAutoPairOutcome.UnexpectedDoneWithoutVerifiedStream
                }
            }
            PairNextStep.GAMESTREAM -> {
                onCompletingGameStream()
                val newCert = when (val gsOutcome = runGameStreamPair(pin, token)) {
                    is GameStreamPairOutcome.Paired -> {
                        val cert = gsOutcome.serverCert
                        if (token.isCancelled) return HostAutoPairOutcome.Cancelled
                        cert ?: return HostAutoPairOutcome.GameStreamFailed(PairingManager.PairState.FAILED)
                    }
                    is GameStreamPairOutcome.FailedState ->
                        return HostAutoPairOutcome.GameStreamFailed(gsOutcome.state)
                    GameStreamPairOutcome.TimedOut ->
                        return HostAutoPairOutcome.GameStreamTimedOut
                    GameStreamPairOutcome.Cancelled ->
                        return HostAutoPairOutcome.Cancelled
                }

                // S3: If this computer ALREADY had a saved GameStream serverCert and the new cert differs,
                // require explicit user confirmation before completing or overwriting!
                val mismatch = StreamCertChangeGuard.detectChange(
                    existingServerCert = alreadyPairedGsCert,
                    newServerCert = newCert,
                    approvedNewGsCertFingerprint = approvedNewGsCertFingerprint,
                )
                if (mismatch != null) {
                    val pending = PendingPairComplete(
                        address = address,
                        controlPort = controlPort,
                        hostId = hostId,
                        hostInfo = verifiedInfo,
                        hostFingerprint = verifiedFp,
                        ticket = approved.ticket,
                        issuedAtMs = ticketIssuedAtMs,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        computerUuid = computerUuid,
                        gsServerCert = newCert,
                        approvedNewFingerprint = approvedNewFingerprint,
                        savedName = savedName,
                    )
                    return HostAutoPairOutcome.StreamServerCertChanged(
                        expectedGsFingerprint = mismatch.expectedFingerprint,
                        actualGsFingerprint = mismatch.actualFingerprint,
                        newGsCert = newCert,
                        pendingComplete = pending,
                    )
                }
                newCert
            }
        }
        if (token.isCancelled) return HostAutoPairOutcome.Cancelled

        val pending = PendingPairComplete(
            address = address,
            controlPort = controlPort,
            hostId = hostId,
            hostInfo = verifiedInfo,
            hostFingerprint = verifiedFp,
            ticket = approved.ticket,
            issuedAtMs = ticketIssuedAtMs,
            deviceId = deviceId,
            deviceName = deviceName,
            computerUuid = computerUuid,
            gsServerCert = gsCert,
            approvedNewFingerprint = approvedNewFingerprint,
            savedName = savedName,
        )

        onCompletingPairRecord()
        return completeWithRetries(
            hostApi = hostApi,
            pinStore = pinStore,
            pending = pending,
            token = token,
            notPairedRetryDelaysMs = notPairedRetryDelaysMs,
            delayFn = delayFn,
            ioDispatcher = ioDispatcher,
        )
    }

    /**
     * Executes Step 5 (`POST /depth3d/v1/pair/complete`) with 5-second `409 not_paired` retries (S4).
     * This is the SOLE place in the entire codebase where [HostCertPinStore.savePairedTrust] is called (B1)!
     */
    suspend fun completeWithRetries(
        hostApi: HostProtocolApi,
        pinStore: HostCertPinStore,
        pending: PendingPairComplete,
        token: CallCancelToken,
        notPairedRetryDelaysMs: List<Long> = DEFAULT_NOT_PAIRED_RETRY_DELAYS_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): HostAutoPairOutcome {
        // M4: Never persist a HostPairedTrust with a blank computerUuid!
        if (pending.computerUuid.isBlank()) {
            return HostAutoPairOutcome.PairCompleteFailed(PairCompleteResult.Failed("missing_computer_uuid"))
        }

        val existingTrust = pinStore.getPairedTrust(pending.hostId)
        var lastCompleteResult: PairCompleteResult = PairCompleteResult.Failed("pair/complete not attempted")
        val maxAttempts = notPairedRetryDelaysMs.size + 1
        for (attempt in 0 until maxAttempts) {
            if (token.isCancelled) return HostAutoPairOutcome.Cancelled
            val completeRes = withContext(ioDispatcher) {
                hostApi.completeAutoPair(
                    address = pending.address,
                    port = pending.controlPort,
                    hostId = pending.hostId,
                    ticket = pending.ticket,
                    deviceId = pending.deviceId,
                    deviceName = pending.deviceName,
                    expectedFingerprint = pending.hostFingerprint,
                    approvedNewFingerprint = pending.approvedNewFingerprint,
                    cancelToken = token,
                )
            }
            if (token.isCancelled || completeRes == PairCompleteResult.Cancelled) {
                return HostAutoPairOutcome.Cancelled
            }
            when (completeRes) {
                is PairCompleteResult.Completed -> {
                    // B1: THE ONLY CALL SITE FOR savePairedTrust — strictly after /pair/complete 200!
                    val trustName = existingTrust?.savedName?.takeIf { it.isNotBlank() }
                        ?: pending.savedName?.takeIf { it.isNotBlank() }
                        ?: pending.hostInfo.name
                    pinStore.savePairedTrust(
                        hostId = pending.hostId,
                        trust = HostPairedTrust(
                            hostFingerprint = pending.hostFingerprint,
                            computerUuid = pending.computerUuid,
                            gsCertFingerprint = HttpHostProtocolClient.sha256Hex(pending.gsServerCert),
                            savedName = trustName,
                        ),
                    )
                    val finalInfo = if (completeRes.mediaPort != null) {
                        pending.hostInfo.copy(media = HostMediaConfig(completeRes.mediaPort))
                    } else {
                        pending.hostInfo
                    }
                    return HostAutoPairOutcome.Paired(finalInfo, pending.hostFingerprint, pending.gsServerCert)
                }
                PairCompleteResult.NotPaired -> {
                    lastCompleteResult = completeRes
                    if (attempt < notPairedRetryDelaysMs.size) {
                        delayFn(notPairedRetryDelaysMs[attempt])
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
                PairCompleteResult.Forbidden ->
                    // Ticket invalid / unauthorized / IP changed: cannot reuse this ticket on retry.
                    return HostAutoPairOutcome.PairCompleteFailed(completeRes, pendingComplete = null)
                is PairCompleteResult.Failed ->
                    // S4: Network failure while ticket is still valid -> attach pendingComplete for direct retry!
                    return HostAutoPairOutcome.PairCompleteFailed(completeRes, pendingComplete = pending)
            }
        }
        // S4: 409 not_paired retries exhausted while ticket is still valid -> attach pendingComplete for direct retry!
        return HostAutoPairOutcome.PairCompleteFailed(lastCompleteResult, pendingComplete = pending)
    }
}

/**
 * Manual-PIN pairing orchestrator for v1 depth3d Hosts (B1 + #11 + S3):
 * 1. Verifies `/info` (`info.id == hostId` + TLS cert).
 * 2. Shows PIN and runs standard GameStream pairing (with 75s cap & S3 serverCert change guard).
 * 3. NEVER persists [HostPairedTrust] directly (B1)! Instead, runs the v1 Host upgrade registration
 *    (`POST /pair` with client cert -> `next:"done"` -> `POST /pair/complete` -> 200 -> `savePairedTrust`).
 * 4. If that Host registration step fails, returns [HostAutoPairOutcome.GameStreamPairedHostSetupIncomplete]
 *    without saving trust so the computer card keeps showing "完成设置后可以看这台电脑上的影片".
 */
object HostManualPairOrchestrator {
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
        alreadyPairedGsCert: X509Certificate? = null,
        approvedNewFingerprint: String?,
        approvedNewGsCertFingerprint: String? = null,
        discoveredFingerprint: String? = null,
        savedName: String? = null,
        token: CallCancelToken,
        onInfoVerified: (HostInfo, String) -> Unit = { _, _ -> },
        onShowPin: () -> Unit = {},
        onWaitingAllow: () -> Unit = {},
        onCompletingPairRecord: () -> Unit = {},
        runGameStreamPair: suspend (pin: String, token: CallCancelToken) -> GameStreamPairOutcome,
        notPairedRetryDelaysMs: List<Long> = HostAutoPairOrchestrator.DEFAULT_NOT_PAIRED_RETRY_DELAYS_MS,
        clockMs: () -> Long = { System.currentTimeMillis() },
        delayFn: suspend (Long) -> Unit = { delay(it) },
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
        val newGsCert = when (val gsOutcome = runGameStreamPair(pin, token)) {
            is GameStreamPairOutcome.Paired -> {
                val cert = gsOutcome.serverCert
                if (token.isCancelled) return HostAutoPairOutcome.Cancelled
                cert ?: return HostAutoPairOutcome.GameStreamFailed(PairingManager.PairState.FAILED)
            }
            is GameStreamPairOutcome.FailedState ->
                return HostAutoPairOutcome.GameStreamFailed(gsOutcome.state)
            GameStreamPairOutcome.TimedOut ->
                return HostAutoPairOutcome.GameStreamTimedOut
            GameStreamPairOutcome.Cancelled ->
                return HostAutoPairOutcome.Cancelled
        }
        if (token.isCancelled) return HostAutoPairOutcome.Cancelled

        // S3: Confirm before overwriting an already-paired computer's GameStream serverCert!
        val mismatch = StreamCertChangeGuard.detectChange(
            existingServerCert = alreadyPairedGsCert,
            newServerCert = newGsCert,
            approvedNewGsCertFingerprint = approvedNewGsCertFingerprint,
        )
        if (mismatch != null) {
            return HostAutoPairOutcome.StreamServerCertChanged(
                expectedGsFingerprint = mismatch.expectedFingerprint,
                actualGsFingerprint = mismatch.actualFingerprint,
                newGsCert = newGsCert,
                pendingComplete = null,
            )
        }

        // B1: GameStream manual PIN succeeded! Now register with v1 Host via the upgrade path:
        // POST /pair (with client cert) -> expect next="done" -> POST /pair/complete -> 200 -> savePairedTrust.
        // If this Host step fails, NEVER save HostPairedTrust so card keeps "完成设置后可以看这台电脑上的影片"!
        return finishHostRegistrationAfterManualPin(
            hostApi = hostApi,
            pinStore = pinStore,
            address = address,
            controlPort = controlPort,
            hostId = hostId,
            hostInfo = verifiedInfo,
            hostFingerprint = verifiedFp,
            deviceName = deviceName,
            deviceId = deviceId,
            pin = pin,
            computerUuid = computerUuid,
            gsServerCert = newGsCert,
            approvedNewFingerprint = approvedNewFingerprint,
            savedName = savedName,
            token = token,
            onWaitingAllow = onWaitingAllow,
            onCompletingPairRecord = onCompletingPairRecord,
            notPairedRetryDelaysMs = notPairedRetryDelaysMs,
            clockMs = clockMs,
            delayFn = delayFn,
            ioDispatcher = ioDispatcher,
        )
    }

    /**
     * Completes v1 Host registration after manual-PIN GameStream pairing succeeded (B1).
     * Reused when user confirms [HostAutoPairOutcome.StreamServerCertChanged] after manual PIN.
     */
    suspend fun finishHostRegistrationAfterManualPin(
        hostApi: HostProtocolApi,
        pinStore: HostCertPinStore,
        address: String,
        controlPort: Int,
        hostId: String,
        hostInfo: HostInfo,
        hostFingerprint: String,
        deviceName: String,
        deviceId: String,
        pin: String,
        computerUuid: String,
        gsServerCert: X509Certificate,
        approvedNewFingerprint: String?,
        savedName: String?,
        token: CallCancelToken,
        onWaitingAllow: () -> Unit = {},
        onCompletingPairRecord: () -> Unit = {},
        notPairedRetryDelaysMs: List<Long> = HostAutoPairOrchestrator.DEFAULT_NOT_PAIRED_RETRY_DELAYS_MS,
        clockMs: () -> Long = { System.currentTimeMillis() },
        delayFn: suspend (Long) -> Unit = { delay(it) },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): HostAutoPairOutcome {
        if (computerUuid.isBlank()) {
            return HostAutoPairOutcome.GameStreamPairedHostSetupIncomplete(
                hostInfo = hostInfo,
                hostFingerprint = hostFingerprint,
                gsServerCert = gsServerCert,
                hostFailure = HostAutoPairOutcome.PairCompleteFailed(PairCompleteResult.Failed("missing_computer_uuid")),
            )
        }
        onWaitingAllow()
        val pairResp = withContext(ioDispatcher) {
            hostApi.requestAutoPair(
                address = address,
                port = controlPort,
                hostId = hostId,
                deviceName = deviceName,
                deviceId = deviceId,
                pin = pin,
                expectedFingerprint = hostFingerprint,
                presentClientCert = true,
                verifiedGameStreamUpgrade = true,
                approvedNewFingerprint = approvedNewFingerprint,
                cancelToken = token,
            )
        }
        if (token.isCancelled || pairResp == AutoPairResult.Cancelled) {
            return HostAutoPairOutcome.GameStreamPairedHostSetupIncomplete(
                hostInfo = hostInfo,
                hostFingerprint = hostFingerprint,
                gsServerCert = gsServerCert,
                hostFailure = HostAutoPairOutcome.Cancelled,
            )
        }
        if (pairResp !is AutoPairResult.Approved || pairResp.next != PairNextStep.DONE) {
            val failure = when (pairResp) {
                AutoPairResult.UpgradeRequired -> HostAutoPairOutcome.UpgradeRequired
                is AutoPairResult.CertificateChanged ->
                    HostAutoPairOutcome.CertificateChanged(pairResp.hostId, pairResp.expected, pairResp.actual)
                is AutoPairResult.Approved ->
                    HostAutoPairOutcome.PairCompleteFailed(PairCompleteResult.NotPaired)
                else -> HostAutoPairOutcome.PairRejectedOrFailed(pairResp, null)
            }
            return HostAutoPairOutcome.GameStreamPairedHostSetupIncomplete(
                hostInfo = hostInfo,
                hostFingerprint = hostFingerprint,
                gsServerCert = gsServerCert,
                hostFailure = failure,
            )
        }

        val pending = PendingPairComplete(
            address = address,
            controlPort = controlPort,
            hostId = hostId,
            hostInfo = hostInfo,
            hostFingerprint = hostFingerprint,
            ticket = pairResp.ticket,
            issuedAtMs = clockMs(),
            deviceId = deviceId,
            deviceName = deviceName,
            computerUuid = computerUuid,
            gsServerCert = gsServerCert,
            approvedNewFingerprint = approvedNewFingerprint,
            savedName = savedName,
        )
        onCompletingPairRecord()
        val completeOutcome = HostAutoPairOrchestrator.completeWithRetries(
            hostApi = hostApi,
            pinStore = pinStore,
            pending = pending,
            token = token,
            notPairedRetryDelaysMs = notPairedRetryDelaysMs,
            delayFn = delayFn,
            ioDispatcher = ioDispatcher,
        )
        return if (completeOutcome is HostAutoPairOutcome.Paired) {
            completeOutcome
        } else {
            HostAutoPairOutcome.GameStreamPairedHostSetupIncomplete(
                hostInfo = hostInfo,
                hostFingerprint = hostFingerprint,
                gsServerCert = gsServerCert,
                hostFailure = completeOutcome,
            )
        }
    }
}
