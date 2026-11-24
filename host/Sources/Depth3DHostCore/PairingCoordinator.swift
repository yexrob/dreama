import Foundation

/// A pending headset pairing request awaiting user confirmation on the Mac.
public struct PendingPairingPrompt: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let deviceName: String
    public let deviceId: String
    public let clientAddress: String
    public let requestedAt: Date
    public let expiresAt: Date

    public init(
        id: UUID = UUID(),
        deviceName: String,
        deviceId: String,
        clientAddress: String,
        requestedAt: Date = Date(),
        timeout: TimeInterval
    ) {
        self.id = id
        self.deviceName = deviceName
        self.deviceId = deviceId
        self.clientAddress = clientAddress
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
    case busy
    case rateLimited
    case cancelled
}

/// Manages incoming auto-pairing requests, user approval prompts, certificate-bound
/// auto-allow memory, single-prompt serialization, per-source cooldown, and 60s timeout.
public actor PairingCoordinator {
    private struct ActiveWaiter {
        let prompt: PendingPairingPrompt
        let continuation: CheckedContinuation<PairingDecision, Never>
        let timeoutTask: Task<Void, Never>
    }

    private let timeout: TimeInterval
    private let cooldownDuration: TimeInterval
    /// Maps `deviceId` -> uppercase SHA-256 hex fingerprint of the paired TLS client certificate.
    /// Auto-allow NEVER trusts `deviceId` alone without a matching TLS client certificate!
    private var autoAllowedCertByDeviceId: [String: String]
    /// Cooldown expiration timestamps keyed by `"ip:<normalized>"` and `"dev:<deviceId>"`.
    private var cooldownUntil: [String: Date] = [:]
    /// Only ONE confirmation prompt may be active on screen at a time to prevent prompt flooding.
    private var activeWaiter: ActiveWaiter?
    private var onPendingChanged: (@Sendable ([PendingPairingPrompt]) -> Void)?

    public init(
        timeout: TimeInterval = 60.0,
        cooldownDuration: TimeInterval = 30.0,
        autoAllowedCertByDeviceId: [String: String] = [:]
    ) {
        self.timeout = timeout
        self.cooldownDuration = max(0, cooldownDuration)
        self.autoAllowedCertByDeviceId = Dictionary(
            uniqueKeysWithValues: autoAllowedCertByDeviceId.map { ($0.key, $0.value.uppercased()) }
        )
    }

    public func setPendingChangeHandler(_ handler: (@Sendable ([PendingPairingPrompt]) -> Void)?) {
        self.onPendingChanged = handler
        notifyPending()
    }

    public func setAutoAllowed(deviceId: String, certFingerprintSHA256: String?) {
        if let fp = certFingerprintSHA256?.trimmingCharacters(in: .whitespacesAndNewlines), !fp.isEmpty {
            autoAllowedCertByDeviceId[deviceId] = fp.uppercased()
        } else {
            autoAllowedCertByDeviceId.removeValue(forKey: deviceId)
        }
    }

    public func replaceAutoAllowedDevices(_ mapping: [String: String]) {
        autoAllowedCertByDeviceId = Dictionary(
            uniqueKeysWithValues: mapping.map { ($0.key, $0.value.uppercased()) }
        )
    }

    public func isAutoAllowed(deviceId: String, peerCertFingerprintSHA256: String?) -> Bool {
        guard let peerFP = peerCertFingerprintSHA256?.trimmingCharacters(in: .whitespacesAndNewlines),
              !peerFP.isEmpty,
              let boundFP = autoAllowedCertByDeviceId[deviceId]
        else {
            return false
        }
        return boundFP.caseInsensitiveCompare(peerFP) == .orderedSame
    }

    public func pendingPrompts() -> [PendingPairingPrompt] {
        if let waiter = activeWaiter {
            return [waiter.prompt]
        }
        return []
    }

    /// Evaluates an incoming pairing request:
    /// 1. Auto-approves ONLY if `deviceId` has auto-allow enabled AND `peerCertFingerprintSHA256`
    ///    matches the certificate bound when that device completed pairing.
    /// 2. Rejects with `.rateLimited` if this IP or deviceId is in cooldown after a reject/timeout.
    /// 3. Rejects with `.busy` if another confirmation prompt is already on screen (one prompt at a time).
    /// 4. Otherwise presents a single confirmation prompt with `clientAddress` and waits up to `timeout`.
    public func requestApproval(
        deviceName: String,
        deviceId: String,
        clientAddress: String,
        peerCertFingerprintSHA256: String?
    ) async -> PairingDecision {
        if isAutoAllowed(deviceId: deviceId, peerCertFingerprintSHA256: peerCertFingerprintSHA256) {
            HostLogger.info("Auto-approved pairing for cert-verified device \(deviceName)", category: "pair")
            return .autoApproved
        }

        let normalizedIP = IPAddressNormalizer.normalize(clientAddress)
        let now = Date()
        pruneExpiredCooldowns(now: now)

        let ipKey = "ip:\(normalizedIP)"
        let devKey = "dev:\(deviceId)"
        if let exp = cooldownUntil[ipKey], exp > now {
            HostLogger.warning("Rate-limited pairing request from \(normalizedIP) during cooldown", category: "pair")
            return .rateLimited
        }
        if let exp = cooldownUntil[devKey], exp > now {
            HostLogger.warning("Rate-limited pairing request from device \(deviceName) during cooldown", category: "pair")
            return .rateLimited
        }

        // Enforce single active prompt at a time.
        if activeWaiter != nil {
            HostLogger.warning("Rejected concurrent pairing request from \(normalizedIP) while another prompt is active", category: "pair")
            return .busy
        }

        let prompt = PendingPairingPrompt(
            deviceName: deviceName,
            deviceId: deviceId,
            clientAddress: normalizedIP,
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
            HostLogger.info("Awaiting user pairing approval for \(deviceName) from \(normalizedIP)", category: "pair")
            notifyPending()
        }
    }

    public func approve(promptId: UUID, rememberAutoAllow: Bool) {
        guard let waiter = activeWaiter, waiter.prompt.id == promptId else { return }
        // Note: Auto-allow is NOT added to `autoAllowedCertByDeviceId` until the GameStream
        // handshake actually completes and produces a bound client certificate fingerprint.
        resolveInternal(decision: .approved(autoAllowFuture: rememberAutoAllow))
    }

    public func reject(promptId: UUID) {
        guard let waiter = activeWaiter, waiter.prompt.id == promptId else { return }
        applyCooldown(for: waiter.prompt)
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
        resolveInternal(decision: .timedOut)
    }

    private func applyCooldown(for prompt: PendingPairingPrompt) {
        guard cooldownDuration > 0 else { return }
        let exp = Date().addingTimeInterval(cooldownDuration)
        cooldownUntil["ip:\(prompt.clientAddress)"] = exp
        cooldownUntil["dev:\(prompt.deviceId)"] = exp
    }

    private func pruneExpiredCooldowns(now: Date) {
        cooldownUntil = cooldownUntil.filter { $0.value > now }
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
