import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

public enum PermissionState: String, Equatable, Sendable {
    case checking
    case granted
    case denied
}

public struct PermissionSnapshot: Equatable, Sendable {
    public var screenRecording: PermissionState
    public var accessibility: PermissionState
    public var localNetwork: PermissionState
    /// Non-permission Bonjour publication error (e.g. name collision/timeout), kept distinct
    /// from Local Network privacy denial so we never mislead the user into Privacy Settings.
    public var bonjourPublishFailed: Bool

    public init(
        screenRecording: PermissionState = .checking,
        accessibility: PermissionState = .checking,
        localNetwork: PermissionState = .checking,
        bonjourPublishFailed: Bool = false
    ) {
        self.screenRecording = screenRecording
        self.accessibility = accessibility
        self.localNetwork = localNetwork
        self.bonjourPublishFailed = bonjourPublishFailed
    }

    public var allGranted: Bool {
        screenRecording == .granted && accessibility == .granted && localNetwork == .granted
    }

    public var streamingCoreGranted: Bool {
        screenRecording == .granted && localNetwork != .denied
    }

    public var missingCount: Int {
        [screenRecording, accessibility, localNetwork].filter { $0 != .granted }.count
    }
}

public protocol SystemPermissionProvider: Sendable {
    func isScreenRecordingGranted() -> Bool
    func requestScreenRecording()
    func isAccessibilityGranted() -> Bool
    func requestAccessibility()
    func openPrivacyPane(_ anchor: String)
}

public struct MacOSSystemPermissionProvider: SystemPermissionProvider {
    public init() {}

    public func isScreenRecordingGranted() -> Bool {
        CGPreflightScreenCaptureAccess()
    }

    public func requestScreenRecording() {
        _ = CGRequestScreenCaptureAccess()
        openPrivacyPane("Privacy_ScreenCapture")
    }

    public func isAccessibilityGranted() -> Bool {
        AXIsProcessTrusted()
    }

    public func requestAccessibility() {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
        openPrivacyPane("Privacy_Accessibility")
    }

    public func openPrivacyPane(_ anchor: String) {
        let primary = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(anchor)")
        let fallback = URL(string: "x-apple.systempreferences:com.apple.preference.security")
        DispatchQueue.main.async {
            if let primary, NSWorkspace.shared.open(primary) {
                return
            }
            if let fallback {
                NSWorkspace.shared.open(fallback)
            }
        }
    }
}

/// Continuously monitors Screen Recording, Accessibility, and Local Network permissions.
public final class PermissionMonitor: @unchecked Sendable {
    private let provider: SystemPermissionProvider
    private let lock = NSLock()
    private var snapshot = PermissionSnapshot()
    private var localNetworkState: PermissionState = .checking
    private var bonjourFailed: Bool = false
    private var timer: DispatchSourceTimer?
    private var onChange: (@Sendable (PermissionSnapshot, PermissionSnapshot) -> Void)?

    public init(provider: SystemPermissionProvider = MacOSSystemPermissionProvider()) {
        self.provider = provider
    }

    deinit {
        stopMonitoring()
    }

    public func currentSnapshot() -> PermissionSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return snapshot
    }

    public func startMonitoring(
        pollInterval: TimeInterval = 1.0,
        onChange: @escaping @Sendable (PermissionSnapshot, PermissionSnapshot) -> Void
    ) {
        stopMonitoring()
        lock.lock()
        self.onChange = onChange
        lock.unlock()

        refreshNow()

        let t = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        t.schedule(deadline: .now() + pollInterval, repeating: pollInterval)
        t.setEventHandler { [weak self] in
            self?.refreshNow()
        }
        lock.lock()
        timer = t
        lock.unlock()
        t.resume()
    }

    public func stopMonitoring() {
        lock.lock()
        let t = timer
        timer = nil
        lock.unlock()
        t?.cancel()
    }

    public func updateLocalNetworkState(from bonjourState: BonjourPublicationState) {
        let mapped: PermissionState
        let failed: Bool
        switch bonjourState {
        case .published:
            mapped = .granted
            failed = false
        case .permissionDenied:
            mapped = .denied
            failed = false
        case .publishing, .stopped:
            mapped = .checking
            failed = false
        case .failed:
            // Non-permission mDNS error: do NOT falsely mark Local Network permission as denied!
            mapped = .checking
            failed = true
        }

        lock.lock()
        localNetworkState = mapped
        bonjourFailed = failed
        lock.unlock()
        refreshNow()
    }

    @discardableResult
    public func refreshNow() -> PermissionSnapshot {
        let screen: PermissionState = provider.isScreenRecordingGranted() ? .granted : .denied
        let ax: PermissionState = provider.isAccessibilityGranted() ? .granted : .denied

        lock.lock()
        let old = snapshot
        let next = PermissionSnapshot(
            screenRecording: screen,
            accessibility: ax,
            localNetwork: localNetworkState,
            bonjourPublishFailed: bonjourFailed
        )
        let changed = (old != next)
        snapshot = next
        let cb = onChange
        lock.unlock()

        if changed {
            HostLogger.info(
                "Permissions updated: screen=\(next.screenRecording.rawValue), ax=\(next.accessibility.rawValue), localNet=\(next.localNetwork.rawValue), mdnsFailed=\(next.bonjourPublishFailed)",
                category: "permissions"
            )
            cb?(old, next)
        }
        return next
    }

    public func requestScreenRecording() {
        provider.requestScreenRecording()
    }

    public func requestAccessibility() {
        provider.requestAccessibility()
    }

    public func openLocalNetworkSettings() {
        provider.openPrivacyPane("Privacy_LocalNetwork")
    }
}
