import Foundation

/// A pending headset pairing request awaiting user confirmation on the Mac.
public struct PendingPairingPrompt: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let deviceName: String
    public let deviceId: String
    public let clientAddress: String
    /// True when `deviceId` already has a bound certificate fingerprint, but this request
    /// carries a different fingerprint. UI displays an explicit warning and approval creates
    /// a brand-new record without overwriting or inheriting auto-allow.
    public let identityChanged: Bool
    public let requestedAt: Date
    public let expiresAt: Date

    public init(
        id: UUID = UUID(),
        deviceName: String,
        deviceId: String,
        clientAddress: String,
        identityChanged: Bool = false,
        requestedAt: Date = Date(),
        timeout: TimeInterval
    ) {
        self.id = id
        self.deviceName = deviceName
        self.deviceId = deviceId
        self.clientAddress = clientAddress
        self.identityChanged = identityChanged
        self.requestedAt = requestedAt
        self.expiresAt = requestedAt.addingTimeInterval(timeout)
    }
}

/// Outcome of the user confirmation step for `/depth3d/v1/pair`.
public enum PairingDecision: Equatable, Sendable {
    case approved(autoAllowFuture: Bool, identityChanged: Bool)
    case autoApproved
    case rejected
    case timedOut
    case busy(retryAfterSec: Int)
    case cooldown(retryAfterSec: Int)
    case cancelled
}

/// Manages incoming auto-pairing requests, user approval prompts, certificate-fingerprint
/// auto-allow memory, single-prompt serialization, per-IP cooldown, multi-IP prompt fairness,
/// client-disconnect cancellation (`withTaskCancellationHandler`), and 60s timeout.
public actor PairingCoordinator {
    private struct ActiveWaiter {
        let prompt: PendingPairingPrompt
        let continuation: CheckedContinuation<PairingDecision, Never>
        let timeoutTask: Task<Void, Never>
    }

    private struct ReservedTurn {
        let ip: String
        let expiresAt: Date
    }

    private let timeout: TimeInterval
    private let cooldownDuration: TimeInterval
    private let fairnessYieldDuration: TimeInterval
    private let recentFailurePenaltyWindow: TimeInterval

    /// Uppercase SHA-256 hex fingerprints of bound client certificates with "以后自动允许" enabled.
    /// Auto-allow NEVER depends on `deviceId`; it trusts ONLY the TLS handshake certificate fingerprint.
    private var autoAllowedFingerprints: Set<String>

    /// Cooldown expiration timestamps keyed STRICTLY by normalized source IP (`ip:<addr>`).
    private var cooldownUntilByIP: [String: Date] = [:]

    /// Multi-IP fairness state (Round-5 #2):
    /// - `waitingIPs`: distinct source IPs that recently got `.busy` while a prompt was occupied,
    ///   mapped to their earliest waiting timestamp (FIFO).
    /// - `recentRejectOrTimeoutAt`: IPs that recently got rejected or timed out (penalized behind
    ///   clean waiting IPs so multiple collaborating attacker IPs cannot starve a legitimate IP).
    /// - `reservedNextTurn`: short priority reservation given to the highest-priority waiting IP
    ///   after a prompt ends (NOT wiped when an unrelated IP tries to cut in line).
    /// - `fairnessYieldUntilByIP`: brief yield window for an IP that just held the prompt when
    ///   another IP is waiting.
    private var waitingIPs: [String: Date] = [:]
    private var recentRejectOrTimeoutAt: [String: Date] = [:]
    private var reservedNextTurn: ReservedTurn?
    private var fairnessYieldUntilByIP: [String: Date] = [:]

    /// Only ONE confirmation prompt may be active on screen at a time to prevent prompt flooding.
    private var activeWaiter: ActiveWaiter?
    private var onPendingChanged: (@Sendable ([PendingPairingPrompt]) -> Void)?

    public init(
        timeout: TimeInterval = 60.0,
        cooldownDuration: TimeInterval = 30.0,
        fairnessYieldDuration: TimeInterval = 10.0,
        recentFailurePenaltyWindow: TimeInterval = 120.0,
        autoAllowedFingerprints: Set<String> = []
    ) {
        self.timeout = timeout
        self.cooldownDuration = max(0, cooldownDuration)
        self.fairnessYieldDuration = max(0, fairnessYieldDuration)
        self.recentFailurePenaltyWindow = max(cooldownDuration, recentFailurePenaltyWindow)
        self.autoAllowedFingerprints = Set(
            autoAllowedFingerprints
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
                .filter { !$0.isEmpty }
        )
    }

    public func setPendingChangeHandler(_ handler: (@Sendable ([PendingPairingPrompt]) -> Void)?) {
        self.onPendingChanged = handler
        notifyPending()
    }

    public func setAutoAllowed(certFingerprintSHA256: String?, enabled: Bool) {
        guard let fp = certFingerprintSHA256?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              !fp.isEmpty
        else { return }
        if enabled {
            autoAllowedFingerprints.insert(fp)
        } else {
            autoAllowedFingerprints.remove(fp)
        }
    }

    public func replaceAutoAllowedFingerprints(_ fingerprints: Set<String>) {
        autoAllowedFingerprints = Set(
            fingerprints
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
                .filter { !$0.isEmpty }
        )
    }

    public func isAutoAllowed(peerCertFingerprintSHA256: String?) -> Bool {
        guard let peerFP = peerCertFingerprintSHA256?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              !peerFP.isEmpty
        else {
            return false
        }
        return autoAllowedFingerprints.contains(peerFP)
    }

    public func pendingPrompts() -> [PendingPairingPrompt] {
        if let waiter = activeWaiter {
            return [waiter.prompt]
        }
        return []
    }

    /// Evaluates an incoming pairing request:
    /// 1. Auto-approves ONLY if `peerCertFingerprintSHA256` matches a bound certificate with auto-allow enabled.
    /// 2. Rejects with `.cooldown(retryAfterSec:)` if this source IP is in cooldown after reject/timeout.
    /// 3. Enforces single active prompt + multi-IP fairness (clean waiting IPs take priority over
    ///    recently-rejected/timed-out IPs, and FIFO turn reservation prevents multi-IP starvation).
    /// 4. Sets `identityChanged = true` whenever `knownBoundFingerprintsForDeviceId` is non-empty and
    ///    does not contain `peerCertFingerprintSHA256`.
    /// 5. Presents a single confirmation prompt and immediately cancels & closes it if the caller's
    ///    Task is cancelled (e.g., headset TCP disconnect while prompt is open).
    public func requestApproval(
        deviceName: String,
        deviceId: String,
        clientAddress: String,
        peerCertFingerprintSHA256: String?,
        knownBoundFingerprintsForDeviceId: Set<String> = []
    ) async -> PairingDecision {
        if Task.isCancelled {
            return .cancelled
        }

        if isAutoAllowed(peerCertFingerprintSHA256: peerCertFingerprintSHA256) {
            HostLogger.info("Auto-approved pairing for cert-verified device \(deviceName)", category: "pair")
            return .autoApproved
        }

        let normalizedIP = IPAddressNormalizer.normalize(clientAddress)
        let now = Date()
        pruneExpiredTimers(now: now)

        let ipKey = "ip:\(normalizedIP)"
        if let exp = cooldownUntilByIP[ipKey], exp > now {
            let remaining = max(1, Int(ceil(exp.timeIntervalSince(now))))
            HostLogger.warning("Rate-limited pairing request from \(normalizedIP) during IP cooldown (\(remaining)s)", category: "pair")
            return .cooldown(retryAfterSec: remaining)
        }

        // Enforce single active prompt at a time and record distinct blocked IPs in FIFO order.
        if let active = activeWaiter {
            if active.prompt.clientAddress != normalizedIP, waitingIPs[normalizedIP] == nil {
                waitingIPs[normalizedIP] = now
            }
            let remaining = max(1, min(10, Int(ceil(active.prompt.expiresAt.timeIntervalSince(now)))))
            HostLogger.warning("Rejected concurrent pairing request from \(normalizedIP) while another prompt is active", category: "pair")
            return .busy(retryAfterSec: remaining)
        }

        // Multi-IP fairness reservation: if a priority waiting IP holds the next turn, let it claim
        // the prompt before other IPs (especially recently-rejected/timed-out IPs) can cut in line.
        if let reserved = reservedNextTurn, reserved.expiresAt > now {
            if normalizedIP != reserved.ip {
                if waitingIPs[normalizedIP] == nil {
                    waitingIPs[normalizedIP] = now
                }
                let remaining = max(1, Int(ceil(reserved.expiresAt.timeIntervalSince(now))))
                HostLogger.warning("Holding pairing prompt turn for waiting source \(reserved.ip); deferring \(normalizedIP)", category: "pair")
                return .busy(retryAfterSec: remaining)
            } else {
                reservedNextTurn = nil
            }
        } else {
            reservedNextTurn = nil
        }

        // Even if no reservation is currently pinned, a recently-rejected/timed-out IP yields
        // whenever any clean (non-penalized, non-cooling) waiting IP is queued.
        if recentRejectOrTimeoutAt[normalizedIP] != nil {
            let hasCleanWaiter = waitingIPs.keys.contains { ip in
                ip != normalizedIP
                    && recentRejectOrTimeoutAt[ip] == nil
                    && (cooldownUntilByIP["ip:\(ip)"] ?? .distantPast) <= now
            }
            if hasCleanWaiter {
                let remaining = max(1, Int(ceil(max(1.0, fairnessYieldDuration))))
                HostLogger.warning("Deferring recently rejected/timed-out source \(normalizedIP) behind clean waiting source", category: "pair")
                return .busy(retryAfterSec: remaining)
            }
        }

        // Single-IP yield check if this IP just finished occupying the prompt while others are waiting.
        if let yieldExp = fairnessYieldUntilByIP[ipKey], yieldExp > now {
            let otherWaiting = waitingIPs.keys.contains { $0 != normalizedIP }
            if otherWaiting {
                let remaining = max(1, Int(ceil(yieldExp.timeIntervalSince(now))))
                HostLogger.warning("Yielding pairing prompt slot from \(normalizedIP) for fair access (\(remaining)s)", category: "pair")
                return .busy(retryAfterSec: remaining)
            }
        }

        // Only clear THIS IP's waiter/yield entry; preserve other IPs' fairness state!
        waitingIPs.removeValue(forKey: normalizedIP)
        fairnessYieldUntilByIP.removeValue(forKey: ipKey)

        let normalizedKnown = Set(
            knownBoundFingerprintsForDeviceId
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
                .filter { !$0.isEmpty }
        )
        let identityChanged: Bool
        if !normalizedKnown.isEmpty {
            if let peerFP = peerCertFingerprintSHA256?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
               !peerFP.isEmpty
            {
                identityChanged = !normalizedKnown.contains(peerFP)
            } else {
                identityChanged = true
            }
        } else {
            identityChanged = false
        }

        let prompt = PendingPairingPrompt(
            deviceName: deviceName,
            deviceId: deviceId,
            clientAddress: normalizedIP,
            identityChanged: identityChanged,
            requestedAt: now,
            timeout: timeout
        )
        let promptId = prompt.id

        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                if Task.isCancelled {
                    continuation.resume(returning: .cancelled)
                    return
                }
                let timeoutNs = UInt64(max(0.05, timeout) * 1_000_000_000)
                let timeoutTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: timeoutNs)
                    await self?.handleTimeout(id: promptId)
                }
                activeWaiter = ActiveWaiter(
                    prompt: prompt,
                    continuation: continuation,
                    timeoutTask: timeoutTask
                )
                HostLogger.info(
                    "Awaiting user pairing approval for \(deviceName) from \(normalizedIP) (identityChanged=\(identityChanged))",
                    category: "pair"
                )
                notifyPending()
            }
        } onCancel: {
            Task { [weak self] in
                await self?.cancelPrompt(id: promptId)
            }
        }
    }

    public func approve(promptId: UUID, rememberAutoAllow: Bool) {
        guard let waiter = activeWaiter, waiter.prompt.id == promptId else { return }
        let ip = waiter.prompt.clientAddress
        let idChanged = waiter.prompt.identityChanged
        scheduleNextFairTurn(afterFinishingIP: ip)
        resolveInternal(decision: .approved(autoAllowFuture: rememberAutoAllow, identityChanged: idChanged))
    }

    public func reject(promptId: UUID) {
        guard let waiter = activeWaiter, waiter.prompt.id == promptId else { return }
        let ip = waiter.prompt.clientAddress
        applyCooldown(for: waiter.prompt)
        recentRejectOrTimeoutAt[ip] = Date()
        scheduleNextFairTurn(afterFinishingIP: ip)
        resolveInternal(decision: .rejected)
    }

    public func cancelPrompt(id: UUID) {
        guard let waiter = activeWaiter, waiter.prompt.id == id else { return }
        let ip = waiter.prompt.clientAddress
        HostLogger.info("Pairing request cancelled (client disconnected) for \(ip); closing confirmation prompt", category: "pair")
        scheduleNextFairTurn(afterFinishingIP: ip)
        resolveInternal(decision: .cancelled)
    }

    public func cancelAll() {
        guard activeWaiter != nil else { return }
        resolveInternal(decision: .cancelled)
    }

    private func handleTimeout(id: UUID) {
        guard let waiter = activeWaiter, waiter.prompt.id == id else { return }
        let ip = waiter.prompt.clientAddress
        HostLogger.info("Pairing prompt timed out for \(ip)", category: "pair")
        applyCooldown(for: waiter.prompt)
        recentRejectOrTimeoutAt[ip] = Date()
        scheduleNextFairTurn(afterFinishingIP: ip)
        resolveInternal(decision: .timedOut)
    }

    private func applyCooldown(for prompt: PendingPairingPrompt) {
        guard cooldownDuration > 0 else { return }
        cooldownUntilByIP["ip:\(prompt.clientAddress)"] = Date().addingTimeInterval(cooldownDuration)
    }

    /// Selects the highest-priority waiting IP (clean IPs without recent reject/timeout strictly
    /// ahead of recently-rejected/timed-out IPs; FIFO within the same tier) and reserves the next
    /// prompt slot for `fairnessYieldDuration`.
    private func scheduleNextFairTurn(afterFinishingIP finishedIP: String) {
        guard fairnessYieldDuration > 0 else { return }
        let now = Date()
        pruneExpiredTimers(now: now)

        let candidates = waitingIPs.filter { ip, _ in
            guard ip != finishedIP else { return false }
            if let cd = cooldownUntilByIP["ip:\(ip)"], cd > now { return false }
            return true
        }
        guard !candidates.isEmpty else { return }

        fairnessYieldUntilByIP["ip:\(finishedIP)"] = now.addingTimeInterval(fairnessYieldDuration)

        let sorted = candidates.sorted { lhs, rhs in
            let lhsPenalized = (recentRejectOrTimeoutAt[lhs.key] != nil)
            let rhsPenalized = (recentRejectOrTimeoutAt[rhs.key] != nil)
            if lhsPenalized != rhsPenalized {
                return !lhsPenalized // clean IPs always beat recently-rejected/timed-out IPs
            }
            return lhs.value < rhs.value // FIFO order among equal-priority IPs
        }

        if let next = sorted.first {
            reservedNextTurn = ReservedTurn(ip: next.key, expiresAt: now.addingTimeInterval(fairnessYieldDuration))
        }
    }

    private func pruneExpiredTimers(now: Date) {
        cooldownUntilByIP = cooldownUntilByIP.filter { $0.value > now }
        fairnessYieldUntilByIP = fairnessYieldUntilByIP.filter { $0.value > now }
        if let reserved = reservedNextTurn, reserved.expiresAt <= now {
            reservedNextTurn = nil
        }
        let waiterCutoff = now.addingTimeInterval(-max(60.0, timeout))
        waitingIPs = waitingIPs.filter { $0.value > waiterCutoff }
        let penaltyCutoff = now.addingTimeInterval(-recentFailurePenaltyWindow)
        recentRejectOrTimeoutAt = recentRejectOrTimeoutAt.filter { $0.value > penaltyCutoff }
    }

    private func resolveInternal(decision: PairingDecision) {
        guard let waiter = activeWaiter else { return }
        activeWaiter = nil
        waiter.timeoutTask.cancel()
        waiter.continuation.resume(returning: decision)
        notifyPending()
    }

    private func notifyPending() {
        let current = pendingPrompts()
        onPendingChanged?(current)
    }
}
