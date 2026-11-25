import CoreAudio
import Foundation

/// Abstraction over macOS Core Audio HAL for muting/unmuting the default output device.
public protocol AudioHALBackend: Sendable {
    func defaultOutputDeviceID() -> AudioDeviceID?
    func isDeviceMuted(_ deviceID: AudioDeviceID) -> Bool?
    @discardableResult
    func setDeviceMuted(_ deviceID: AudioDeviceID, muted: Bool) -> Bool
}

public struct CoreAudioHALBackend: AudioHALBackend {
    public init() {}

    public func defaultOutputDeviceID() -> AudioDeviceID? {
        var deviceID = AudioDeviceID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let status = AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            0,
            nil,
            &size,
            &deviceID
        )
        guard status == noErr, deviceID != kAudioObjectUnknown else { return nil }
        return deviceID
    }

    public func isDeviceMuted(_ deviceID: AudioDeviceID) -> Bool? {
        var address = muteAddress(element: kAudioObjectPropertyElementMain)
        if !AudioObjectHasProperty(deviceID, &address) {
            address = muteAddress(element: 1)
            guard AudioObjectHasProperty(deviceID, &address) else { return nil }
        }
        var muted: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        let status = AudioObjectGetPropertyData(deviceID, &address, 0, nil, &size, &muted)
        guard status == noErr else { return nil }
        return muted != 0
    }

    @discardableResult
    public func setDeviceMuted(_ deviceID: AudioDeviceID, muted: Bool) -> Bool {
        var value: UInt32 = muted ? 1 : 0
        let size = UInt32(MemoryLayout<UInt32>.size)

        var master = muteAddress(element: kAudioObjectPropertyElementMain)
        if AudioObjectHasProperty(deviceID, &master) {
            var settable: DarwinBoolean = false
            if AudioObjectIsPropertySettable(deviceID, &master, &settable) == noErr, settable.boolValue {
                return AudioObjectSetPropertyData(deviceID, &master, 0, nil, size, &value) == noErr
            }
        }

        var succeeded = false
        for ch: UInt32 in [1, 2] {
            var chAddr = muteAddress(element: ch)
            if AudioObjectHasProperty(deviceID, &chAddr) {
                var settable: DarwinBoolean = false
                if AudioObjectIsPropertySettable(deviceID, &chAddr, &settable) == noErr, settable.boolValue {
                    if AudioObjectSetPropertyData(deviceID, &chAddr, 0, nil, size, &value) == noErr {
                        succeeded = true
                    }
                }
            }
        }
        return succeeded
    }

    private func muteAddress(element: UInt32) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyMute,
            mScope: kAudioDevicePropertyScopeOutput,
            mElement: element
        )
    }
}

private struct AudioMuteRecoveryMarker: Codable {
    let deviceID: UInt32
    let priorMuted: Bool
}

/// Manages host-side "串流时静音本机" alongside the engine's native Core Audio Process Tap:
/// - Tracks exact concurrent session count and ONLY restores speakers when the LAST session ends
/// - Persists a recovery marker before muting so if Host crashes or is `SIGKILL`ed while muted,
///   the next startup automatically restores the Mac's previous speaker mute state
public final class SystemAudioController: @unchecked Sendable {
    private let backend: AudioHALBackend
    private let recoveryFileURL: URL?
    private let lock = NSLock()

    private var muteWhileStreaming: Bool
    private var activeSessionCount: Int = 0
    private var savedDeviceID: AudioDeviceID?
    private var savedPriorMuteState: Bool?

    public init(
        backend: AudioHALBackend = CoreAudioHALBackend(),
        muteWhileStreaming: Bool = true,
        recoveryFileURL: URL? = nil
    ) {
        self.backend = backend
        self.recoveryFileURL = recoveryFileURL
        self.muteWhileStreaming = muteWhileStreaming
        recoverFromPreviousCrashIfNeeded()
    }

    deinit {
        restoreIfNeeded()
    }

    public var isHostMutedByController: Bool {
        lock.lock()
        defer { lock.unlock() }
        return savedDeviceID != nil
    }

    public var currentActiveSessionCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return activeSessionCount
    }

    public func setMuteWhileStreaming(_ enabled: Bool) {
        lock.lock()
        muteWhileStreaming = enabled
        let hasSessions = activeSessionCount > 0
        lock.unlock()

        if hasSessions {
            if enabled {
                applyMuteIfNeeded()
            } else {
                restoreIfNeeded()
            }
        }
    }

    /// Updates the count of concurrently streaming clients.
    /// Mutes when moving `0 -> >0` (if enabled), stays muted while `>0`, and ONLY unmutes on `0`.
    public func setActiveSessionCount(_ count: Int) {
        let clamped = max(0, count)
        lock.lock()
        let prev = activeSessionCount
        activeSessionCount = clamped
        let shouldMute = muteWhileStreaming
        lock.unlock()

        if clamped > 0 && prev == 0 {
            if shouldMute {
                applyMuteIfNeeded()
            }
        } else if clamped == 0 && prev > 0 {
            restoreIfNeeded()
        }
    }

    public func setStreamingActive(_ active: Bool) {
        setActiveSessionCount(active ? max(1, currentActiveSessionCount) : 0)
    }

    public func restoreIfNeeded() {
        lock.lock()
        let deviceID = savedDeviceID
        let priorMuted = savedPriorMuteState
        savedDeviceID = nil
        savedPriorMuteState = nil
        lock.unlock()

        clearRecoveryMarker()

        guard let deviceID, let priorMuted else { return }
        _ = backend.setDeviceMuted(deviceID, muted: priorMuted)
        HostLogger.info("Restored Mac speaker mute state to \(priorMuted)", category: "audio")
    }

    private func applyMuteIfNeeded() {
        lock.lock()
        let alreadySaved = (savedDeviceID != nil)
        lock.unlock()
        guard !alreadySaved else { return }

        guard let deviceID = backend.defaultOutputDeviceID(),
              let priorMuted = backend.isDeviceMuted(deviceID)
        else {
            return
        }

        if !priorMuted {
            // Persist recovery marker BEFORE muting so a sudden crash/SIGKILL can be undone on next launch.
            writeRecoveryMarker(AudioMuteRecoveryMarker(deviceID: deviceID, priorMuted: priorMuted))
            if backend.setDeviceMuted(deviceID, muted: true) {
                lock.lock()
                savedDeviceID = deviceID
                savedPriorMuteState = priorMuted
                lock.unlock()
                HostLogger.info("Muted Mac speakers while streaming (will restore when last session ends)", category: "audio")
            } else {
                clearRecoveryMarker()
            }
        }
    }

    private func recoverFromPreviousCrashIfNeeded() {
        guard let url = recoveryFileURL,
              let data = try? Data(contentsOf: url),
              let marker = try? JSONDecoder().decode(AudioMuteRecoveryMarker.self, from: data)
        else {
            return
        }
        _ = backend.setDeviceMuted(AudioDeviceID(marker.deviceID), muted: marker.priorMuted)
        clearRecoveryMarker()
        HostLogger.warning("Recovered Mac speaker mute state from previous unclean shutdown", category: "audio")
    }

    private func writeRecoveryMarker(_ marker: AudioMuteRecoveryMarker) {
        guard let url = recoveryFileURL else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(marker) {
            try? data.write(to: url, options: .atomic)
        }
    }

    private func clearRecoveryMarker() {
        guard let url = recoveryFileURL else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
