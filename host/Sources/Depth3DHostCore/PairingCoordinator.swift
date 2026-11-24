import Foundation

/// A pending headset pairing request awaiting user confirmation on the Mac.
public struct PendingPairingPrompt: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let deviceName: String
    public let deviceId: String
    public let clientAddress: String
    /// True when `deviceId` already has a bound certificate fingerprint, but this request
    /// carries no client certificate or a different fingerprint. UI displays an explicit warning
    /// and approval creates a brand-new record without overwriting or inheriting auto-allow.
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
    case approved(autoAllowFuture: Bool)
    case autoApproved
    case rejected
    case timedOut
    case busy(retryAfterSec: Int)
    case cooldown(retryAfterSec: Int)
    case cancelled
}

/// Manages incoming auto-pairing requests, user approval prompts, certificate-fingerprint
/// auto-allow memory, single-prompt serialization, per-IP cooldown, per-IP prompt fairness,
/// and 60s timeout.
public actor PairingCoordinator {
    private struct ActiveWaiter {
        let prompt: PendingPairingPrompt
        let continuation: CheckedContinuation<PairingDecision, Never>
        let timeoutTask: Task<Void, Never>
    }

    private let timeout: TimeInterval
    private let cooldownDuration: TimeInterval
    private let fairnessYieldDuration: TimeInterval

    /// Uppercase SHA-256 hex fingerprints of bound client certificates that have "以后自动允许" enabled.
    /// Auto-allow NEVER depends on `deviceId`; it trusts ONLY the TLS handshake certificate fingerprint.
    private var autoAllowedFingerprints: Set<String>

    /// Cooldown expiration timestamps keyed STRICTLY by normalized source IP (`ip:<addr>`).
    /// Intentionally NEVER keyed by `deviceId` so a LAN attacker spoofing a victim's `deviceId`
    /// cannot lock out the legitimate headset.
    private var cooldownUntilByIP: [String: Date] = [:]

    /// Per-IP fairness tracking (S-5):
    /// - Records other source IPs that recently got `.busy` while a prompt was occupied.
    /// - When a prompt completes, if another IP was waiting, briefly yields the prompt slot so
    ///   the IP that just occupied the prompt cannot monopolize consecutive prompts back-to-back.
    private var recentBlockedIPs: [String: Date] = [:]
    private var fairnessYieldUntilByIP: [String: Date] = [:]

    /// Only ONE confirmation prompt may be active on screen at a time to prevent prompt flooding.
    private var activeWaiter: ActiveWaiter?
    private var onPendingChanged: (@Sendable ([PendingPairingPrompt]) -> Void)?

    public init(
        timeout: TimeInterval = 60.0,
        cooldownDuration: TimeInterval = 30.0,
        fairnessYieldDuration: TimeInterval = 10.0,
        autoAllowedFingerprints: Set<String> = []
    ) {
        self.timeout = timeout
        self.cooldownDuration = max(0, cooldownDuration)
        self.fairnessYieldDuration = max(0, fairnessYieldDuration)
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
    /// 3. Rejects with `.busy(retryAfterSec:)` if another prompt is active OR if this IP must yield
    ///    the prompt slot to another source IP that was recently blocked by `.busy` (S-5 fairness).
    /// 4. Sets `identityChanged = true` whenever `knownBoundFingerprintsForDeviceId` is non-empty and
    ///    does not contain `peerCertFingerprintSHA256` (including missing cert).
    /// 5. Presents a single confirmation prompt and waits up to `timeout`.
    public func requestApproval(
        deviceName: String,
        deviceId: String,
        clientAddress: String,
        peerCertFingerprintSHA256: String?,
        knownBoundFingerprintsForDeviceId: Set<String> = []
    ) async -> PairingDecision {
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

        // Enforce single active prompt at a time and record blocked peer IPs for fairness.
        if let active = activeWaiter {
            if active.prompt.clientAddress != normalizedIP {
                recentBlockedIPs[normalizedIP] = now
            }
            let remaining = max(1, min(10, Int(ceil(active.prompt.expiresAt.timeIntervalSince(now)))))
            HostLogger.warning("Rejected concurrent pairing request from \(normalizedIP) while another prompt is active", category: "pair")
            return .busy(retryAfterSec: remaining)
        }

        // Per-IP fairness yield: if this IP just finished occupying the prompt while another IP
        // was waiting, yield briefly so the other IP can claim the prompt without starvation.
        if let yieldExp = fairnessYieldUntilByIP[ipKey], yieldExp > now {
            let remaining = max(1, Int(ceil(yieldExp.timeIntervalSince(now))))
            HostLogger.warning("Yielding pairing prompt slot from \(normalizedIP) for fair access (\(remaining)s)", category: "pair")
            return .busy(retryAfterSec: remaining)
        }

        // Claiming the prompt clears any pending fairness yields for other IPs and removes this IP from waiters.
        recentBlockedIPs.removeValue(forKey: normalizedIP)
        fairnessYieldUntilByIP.removeAll()

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

        return await withCheckedContinuation { continuation in
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
    }

    public func approve(promptId: UUID, rememberAutoAllow: Bool) {
        guard let waiter = activeWaiter, waiter.prompt.id == promptId else { return }
        applyFairnessYieldIfNeeded(for: waiter.prompt.clientAddress)
        resolveInternal(decision: .approved(autoAllowFuture: rememberAutoAllow))
    }

    public func reject(promptId: UUID) {
        guard let waiter = activeWaiter, waiter.prompt.id == promptId else { return }
        applyCooldown(for: waiter.prompt)
        applyFairnessYieldIfNeeded(for: waiter.prompt.clientAddress)
        resolveInternal(decision: .rejected)
    }

    public func cancelAll() {
        guard activeWaiter != nil else { return }
        resolveInternal(decision: .cancelled)
    }

    private func handleTimeout(id: UUID) {
        guard let waiter = activeWaiter, waiter.prompt.id == id else { return }
        HostLogger.info("Pairing prompt timed out for \(waiter.prompt.clientAddress)", category: "pair")
        applyCooldown(for: waiter.prompt)
        applyFairnessYieldIfNeeded(for: waiter.prompt.clientAddress)
        resolveInternal(decision: .timedOut)
    }

    private func applyCooldown(for prompt: PendingPairingPrompt) {
        guard cooldownDuration > 0 else { return }
        cooldownUntilByIP["ip:\(prompt.clientAddress)"] = Date().addingTimeInterval(cooldownDuration)
    }

    private func applyFairnessYieldIfNeeded(for clientIP: String) {
        guard fairnessYieldDuration > 0 else { return }
        let now = Date()
        pruneExpiredTimers(now: now)
        let otherIPWaiting = recentBlockedIPs.keys.contains { $0 != clientIP }
        if otherIPWaiting {
            fairnessYieldUntilByIP["ip:\(clientIP)"] = now.addingTimeInterval(fairnessYieldDuration)
        }
    }

    private func pruneExpiredTimers(now: Date) {
        cooldownUntilByIP = cooldownUntilByIP.filter { $0.value > now }
        fairnessYieldUntilByIP = fairnessYieldUntilByIP.filter { $0.value > now }
        let waiterCutoff = now.addingTimeInterval(-max(60.0, timeout))
        recentBlockedIPs = recentBlockedIPs.filter { $0.value > waiterCutoff }
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
