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

        // Prefer master channel (element 0); fall back to stereo channels (1 & 2).
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

/// Manages host-side "串流时静音本机" cleanly alongside the engine's native Core Audio Process Tap.
///
/// The bundled streaming engine captures system audio natively via macOS 14.2+ Core Audio Process Tap
/// (`AudioHardwareCreateProcessTap`), which taps process audio streams prior to the hardware output
/// mute switch. When the user enables "串流时静音本机", this controller ensures the Mac's speakers
/// stay silent during streaming (even if a client requests local audio) and always restores the exact
/// prior mute state when streaming ends, the toggle changes, or the app quits.
public final class SystemAudioController: @unchecked Sendable {
    private let backend: AudioHALBackend
    private let lock = NSLock()

    private var muteWhileStreaming: Bool
    private var isStreamingActive: Bool = false
    private var savedDeviceID: AudioDeviceID?
    private var savedPriorMuteState: Bool?

    public init(backend: AudioHALBackend = CoreAudioHALBackend(), muteWhileStreaming: Bool = true) {
        self.backend = backend
        self.muteWhileStreaming = muteWhileStreaming
    }

    deinit {
        restoreIfNeeded()
    }

    public var isHostMutedByController: Bool {
        lock.lock()
        defer { lock.unlock() }
        return savedDeviceID != nil
    }

    public func setMuteWhileStreaming(_ enabled: Bool) {
        lock.lock()
        muteWhileStreaming = enabled
        let active = isStreamingActive
        lock.unlock()

        if active {
            if enabled {
                applyMuteIfNeeded()
            } else {
                restoreIfNeeded()
            }
        }
    }

    public func setStreamingActive(_ active: Bool) {
        lock.lock()
        let wasActive = isStreamingActive
        isStreamingActive = active
        let shouldMute = muteWhileStreaming
        lock.unlock()

        if active && !wasActive {
            if shouldMute {
                applyMuteIfNeeded()
            }
        } else if !active && wasActive {
            restoreIfNeeded()
        }
    }

    public func restoreIfNeeded() {
        lock.lock()
        let deviceID = savedDeviceID
        let priorMuted = savedPriorMuteState
        savedDeviceID = nil
        savedPriorMuteState = nil
        lock.unlock()

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
            if backend.setDeviceMuted(deviceID, muted: true) {
                lock.lock()
                savedDeviceID = deviceID
                savedPriorMuteState = priorMuted
                lock.unlock()
                HostLogger.info("Muted Mac speakers while streaming (will restore on stop)", category: "audio")
            }
        }
    }
}
