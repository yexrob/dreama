import Foundation

/// Events extracted from the built-in streaming engine's stdout/stderr log lines.
public enum EngineLogEvent: Equatable, Sendable {
    case ready(apiEndpoint: String)
    case mdnsRegistered
    case screenRecordingDenied
    case encoderSelected(String)
    case encoderUnavailable
    case portBindFailed(UInt16?)
    case streamingStarted(activeSessions: Int)
    case streamingStopped
    case audioTapActive(hostMuted: Bool?)
    case audioTapFailed
    case pairingExpired
}

/// Aggregated state derived purely from engine log lines.
public struct EngineLogState: Equatable, Sendable {
    public var apiReady: Bool = false
    public var mdnsRegistered: Bool = false
    public var screenRecordingDenied: Bool = false
    public var encoderName: String?
    public var encoderUnavailable: Bool = false
    public var failedPort: UInt16?
    public var activeSessions: Int = 0
    public var systemAudioTapActive: Bool = false
    public var systemAudioTapHostMuted: Bool?
    public var systemAudioTapFailed: Bool = false

    public init() {}

    public mutating func apply(_ event: EngineLogEvent) {
        switch event {
        case .ready:
            apiReady = true
        case .mdnsRegistered:
            mdnsRegistered = true
        case .screenRecordingDenied:
            screenRecordingDenied = true
        case .encoderSelected(let name):
            encoderName = name
            encoderUnavailable = false
        case .encoderUnavailable:
            encoderUnavailable = true
        case .portBindFailed(let port):
            failedPort = port
        case .streamingStarted(let count):
            activeSessions = max(1, count)
        case .streamingStopped:
            activeSessions = max(0, activeSessions - 1)
        case .audioTapActive(let muted):
            systemAudioTapActive = true
            systemAudioTapFailed = false
            if let muted {
                systemAudioTapHostMuted = muted
            }
        case .audioTapFailed:
            systemAudioTapFailed = true
            systemAudioTapActive = false
        case .pairingExpired:
            break
        }
    }
}

/// Stateless parser turning raw streaming engine log lines into typed `EngineLogEvent` values.
public enum EngineLogParser {
    public static func parse(line rawLine: String) -> EngineLogEvent? {
        let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !line.isEmpty else { return nil }

        if let range = line.range(of: "Configuration UI available at [") {
            let tail = line[range.upperBound...]
            let endpoint = tail.prefix { $0 != "]" }
            return .ready(apiEndpoint: String(endpoint))
        }

        if line.contains("Successfully registered DNS service") {
            return .mdnsRegistered
        }

        if line.contains("No screen capture permission!") {
            return .screenRecordingDenied
        }

        if let range = line.range(of: "Creating encoder [") {
            let tail = line[range.upperBound...]
            let name = tail.prefix { $0 != "]" }
            if !name.isEmpty {
                return .encoderSelected(String(name))
            }
        }

        if line.contains("Video failed to find working encoder")
            || line.contains("Unable to find display or encoder during startup")
        {
            return .encoderUnavailable
        }

        if line.contains("Couldn't bind") || line.contains("Couldn't start Configuration HTTPS server")
            || line.contains("Couldn't start http server")
        {
            return .portBindFailed(extractPort(from: line))
        }

        if let range = line.range(of: "New streaming session started [active sessions:") {
            let tail = line[range.upperBound...].trimmingCharacters(in: .whitespaces)
            let digits = tail.prefix { $0.isNumber }
            return .streamingStarted(activeSessions: Int(digits) ?? 1)
        }

        if line.contains("CLIENT CONNECTED") {
            return .streamingStarted(activeSessions: 1)
        }

        if line.contains("CLIENT DISCONNECTED") || line.contains("Session ended") {
            return .streamingStopped
        }

        if line.contains("Core Audio tap: Host audio disabled (muted)") {
            return .audioTapActive(hostMuted: true)
        }

        if line.contains("Core Audio tap: Host audio enabled (unmuted)") {
            return .audioTapActive(hostMuted: false)
        }

        if line.contains("Using macOS system audio tap for capture")
            || line.contains("System tap setup completed")
        {
            return .audioTapActive(hostMuted: nil)
        }

        if line.contains("Failed to setup system audio tap")
            || line.contains("AudioHardwareCreateProcessTap failed")
        {
            return .audioTapFailed
        }

        if line.contains("Pairing session expired") {
            return .pairingExpired
        }

        return nil
    }

    private static func extractPort(from line: String) -> UInt16? {
        guard let open = line.range(of: "port ["),
              let close = line[open.upperBound...].firstIndex(of: "]")
        else {
            return nil
        }
        let inside = line[open.upperBound..<close]
        let digits = inside.prefix { $0.isNumber }
        return UInt16(digits)
    }
}
