import Foundation

/// A pending headset pairing request awaiting user confirmation on the Mac.
public struct PendingPairingPrompt: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let deviceName: String
    public let deviceId: String
    public let requestedAt: Date
    public let expiresAt: Date

    public init(
        id: UUID = UUID(),
        deviceName: String,
        deviceId: String,
        requestedAt: Date = Date(),
        timeout: TimeInterval
    ) {
        self.id = id
        self.deviceName = deviceName
        self.deviceId = deviceId
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
    case cancelled
}

/// Manages incoming auto-pairing requests, user approval prompts, auto-allow memory,
/// and the 60-second confirmation timeout required by `host-protocol.md`.
public actor PairingCoordinator {
    private struct ActiveWaiter {
        let prompt: PendingPairingPrompt
        let continuation: CheckedContinuation<PairingDecision, Never>
        let timeoutTask: Task<Void, Never>
    }

    private let timeout: TimeInterval
    private var autoAllowedDeviceIds: Set<String>
    private var waiters: [UUID: ActiveWaiter] = [:]
    private var onPendingChanged: (@Sendable ([PendingPairingPrompt]) -> Void)?

    public init(timeout: TimeInterval = 60.0, autoAllowedDeviceIds: Set<String> = []) {
        self.timeout = timeout
        self.autoAllowedDeviceIds = autoAllowedDeviceIds
    }

    public func setPendingChangeHandler(_ handler: (@Sendable ([PendingPairingPrompt]) -> Void)?) {
        self.onPendingChanged = handler
        notifyPending()
    }

    public func setAutoAllowed(_ allowed: Bool, for deviceId: String) {
        if allowed {
            autoAllowedDeviceIds.insert(deviceId)
        } else {
            autoAllowedDeviceIds.remove(deviceId)
        }
    }

    public func replaceAutoAllowedDevices(_ ids: Set<String>) {
        self.autoAllowedDeviceIds = ids
    }

    public func isAutoAllowed(deviceId: String) -> Bool {
        autoAllowedDeviceIds.contains(deviceId)
    }

    public func pendingPrompts() -> [PendingPairingPrompt] {
        waiters.values.map(\.prompt).sorted { $0.requestedAt < $1.requestedAt }
    }

    /// Evaluates an incoming pairing request. Returns immediately if `deviceId` has
    /// previously been granted "以后自动允许"; otherwise presents a confirmation prompt
    /// and awaits user action or timeout (default 60s).
    public func requestApproval(deviceName: String, deviceId: String) async -> PairingDecision {
        if autoAllowedDeviceIds.contains(deviceId) {
            HostLogger.info("Auto-approved pairing for remembered device \(deviceName)", category: "pair")
            return .autoApproved
        }

        // Cancel any superseded pending prompt for the same deviceId.
        for (existingId, existing) in waiters where existing.prompt.deviceId == deviceId {
            resolveInternal(id: existingId, decision: .cancelled)
        }

        let prompt = PendingPairingPrompt(deviceName: deviceName, deviceId: deviceId, timeout: timeout)
        let promptId = prompt.id

        return await withCheckedContinuation { continuation in
            let timeoutNs = UInt64(max(0.05, timeout) * 1_000_000_000)
            let timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: timeoutNs)
                await self?.handleTimeout(id: promptId)
            }
            waiters[promptId] = ActiveWaiter(
                prompt: prompt,
                continuation: continuation,
                timeoutTask: timeoutTask
            )
            HostLogger.info("Awaiting user pairing approval for \(deviceName)", category: "pair")
            notifyPending()
        }
    }

    public func approve(promptId: UUID, rememberAutoAllow: Bool) {
        guard let waiter = waiters[promptId] else { return }
        if rememberAutoAllow {
            autoAllowedDeviceIds.insert(waiter.prompt.deviceId)
        }
        resolveInternal(id: promptId, decision: .approved(autoAllowFuture: rememberAutoAllow))
    }

    public func reject(promptId: UUID) {
        resolveInternal(id: promptId, decision: .rejected)
    }

    public func cancelAll() {
        let ids = Array(waiters.keys)
        for id in ids {
            resolveInternal(id: id, decision: .cancelled)
        }
    }

    private func handleTimeout(id: UUID) {
        guard waiters[id] != nil else { return }
        HostLogger.info("Pairing prompt timed out", category: "pair")
        resolveInternal(id: id, decision: .timedOut)
    }

    private func resolveInternal(id: UUID, decision: PairingDecision) {
        guard let waiter = waiters.removeValue(forKey: id) else { return }
        waiter.timeoutTask.cancel()
        waiter.continuation.resume(returning: decision)
        notifyPending()
    }

    private func notifyPending() {
        let current = pendingPrompts()
        onPendingChanged?(current)
    }
}
