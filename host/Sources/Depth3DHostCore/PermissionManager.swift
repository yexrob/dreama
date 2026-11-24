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

    public init(
        screenRecording: PermissionState = .checking,
        accessibility: PermissionState = .checking,
        localNetwork: PermissionState = .checking
    ) {
        self.screenRecording = screenRecording
        self.accessibility = accessibility
        self.localNetwork = localNetwork
    }

    public var allGranted: Bool {
        screenRecording == .granted && accessibility == .granted && localNetwork == .granted
    }

    /// Screen recording and local network are mandatory for streaming discovery + video capture.
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
/// Automatically detects newly granted permissions so onboarding advances and the
/// streaming engine restarts without asking the user to quit and relaunch the app.
public final class PermissionMonitor: @unchecked Sendable {
    private let provider: SystemPermissionProvider
    private let lock = NSLock()
    private var snapshot = PermissionSnapshot()
    private var localNetworkState: PermissionState = .checking
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
        switch bonjourState {
        case .published:
            mapped = .granted
        case .permissionDenied:
            mapped = .denied
        case .publishing, .stopped:
            mapped = .checking
        case .failed:
            mapped = .denied
        }

        lock.lock()
        localNetworkState = mapped
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
            localNetwork: localNetworkState
        )
        let changed = (old != next)
        snapshot = next
        let cb = onChange
        lock.unlock()

        if changed {
            HostLogger.info(
                "Permissions updated: screen=\(next.screenRecording.rawValue), ax=\(next.accessibility.rawValue), localNet=\(next.localNetwork.rawValue)",
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
