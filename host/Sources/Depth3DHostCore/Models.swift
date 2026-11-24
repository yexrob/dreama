import Foundation
import SystemConfiguration

/// Video quality presets mapped to parameters that genuinely affect VideoToolbox encoding
/// in Sunshine (`src/video.cpp`: `max_bitrate` caps `ctx->bit_rate` in kbps; `vt_coder`
/// selects H.264 entropy coding; `vt_realtime` controls realtime scheduling).
public enum StreamQualityPreset: String, Codable, CaseIterable, Identifiable, Sendable {
    case smooth
    case balanced
    case high

    public var id: String { rawValue }

    /// Maximum VideoToolbox bitrate cap in kbps (`0` = unlimited, follows headset request).
    public var maxBitrateKbps: Int {
        switch self {
        case .smooth: return 20_000   // 20 Mbps ceiling for weaker Wi-Fi / low latency
        case .balanced: return 50_000 // 50 Mbps ceiling (good for 1440p72 HEVC)
        case .high: return 0          // Unlimited (follows client-requested bitrate, e.g. 80-150 Mbps)
        }
    }

    /// VideoToolbox entropy coder option (`auto`, `cabac`, `cavlc`).
    public var vtCoder: String {
        switch self {
        case .smooth: return "auto"
        case .balanced, .high: return "cabac"
        }
    }

    /// VideoToolbox realtime encoding flag.
    public var realtimeEncoding: Bool { true }
}

/// Persistent identity of this Mac host (stable UUID across launches).
public struct HostIdentity: Codable, Equatable, Sendable {
    public let id: String
    public let createdAt: Date

    public init(id: String = UUID().uuidString.uppercased(), createdAt: Date = Date()) {
        self.id = id
        self.createdAt = createdAt
    }
}

/// A local macOS folder shared over the built-in read-only WebDAV server.
public struct MediaFolderEntry: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public var name: String
    public var path: String
    public var bookmarkData: Data?

    public init(id: UUID = UUID(), name: String, path: String, bookmarkData: Data? = nil) {
        self.id = id
        self.name = name
        self.path = path
        self.bookmarkData = bookmarkData
    }
}

/// A configured rclone cloud remote mounted read-only into the unified WebDAV tree.
public struct CloudRemoteEntry: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public var displayName: String
    public var remoteSpec: String

    public init(id: UUID = UUID(), displayName: String, remoteSpec: String) {
        self.id = id
        self.displayName = displayName
        self.remoteSpec = remoteSpec
    }
}

/// A paired headset record persisted by depth3d Host ONLY after `/depth3d/v1/pair/complete`
/// verifies the TLS client certificate is enabled in the streaming engine.
///
/// Primary key is `certFingerprint` (uppercase SHA-256 hex digest of the headset's X.509 client
/// certificate); `deviceId` is only a display/lookup label and never a credential. Legacy records
/// migrated from older builds without `certFingerprint` fall back to `"legacy:\(deviceId)"`.
public struct PairedDeviceRecord: Codable, Equatable, Identifiable, Sendable {
    public var deviceId: String
    public var name: String
    public var engineUUID: String?
    /// Uppercase SHA-256 hex digest of the exact X.509 client certificate bound during pairing.
    public var certFingerprint: String?
    public var autoAllow: Bool
    public let pairedAt: Date
    public var lastSeenAt: Date

    public var id: String {
        if let fp = certFingerprint?.trimmingCharacters(in: .whitespacesAndNewlines), !fp.isEmpty {
            return fp.uppercased()
        }
        return "legacy:\(deviceId)"
    }

    public init(
        deviceId: String,
        name: String,
        engineUUID: String? = nil,
        certFingerprint: String? = nil,
        autoAllow: Bool = false,
        pairedAt: Date = Date(),
        lastSeenAt: Date = Date()
    ) {
        self.deviceId = deviceId
        self.name = name
        self.engineUUID = engineUUID
        if let fp = certFingerprint?.trimmingCharacters(in: .whitespacesAndNewlines), !fp.isEmpty {
            self.certFingerprint = fp.uppercased()
        } else {
            self.certFingerprint = nil
        }
        self.autoAllow = autoAllow
        self.pairedAt = pairedAt
        self.lastSeenAt = lastSeenAt
    }
}

/// User-configurable host settings persisted in Application Support.
public struct HostSettings: Codable, Equatable, Sendable {
    public var computerName: String
    /// CGDirectDisplayID decimal string; nil means primary display.
    public var selectedDisplayID: String?
    public var qualityPreset: StreamQualityPreset
    public var muteMacWhileStreaming: Bool
    public var launchAtLogin: Bool
    public var mediaServiceEnabled: Bool
    /// When true, shifts the built-in streaming engine port family to alternate ports (57989)
    /// to avoid conflict with another GameStream host occupying 47989.
    public var useAlternateEnginePorts: Bool
    public var onboardingCompleted: Bool
    public var mediaFolders: [MediaFolderEntry]
    public var cloudRemotes: [CloudRemoteEntry]

    public init(
        computerName: String = HostSettings.defaultComputerName(),
        selectedDisplayID: String? = nil,
        qualityPreset: StreamQualityPreset = .balanced,
        muteMacWhileStreaming: Bool = true,
        launchAtLogin: Bool = false,
        mediaServiceEnabled: Bool = true,
        useAlternateEnginePorts: Bool = false,
        onboardingCompleted: Bool = false,
        mediaFolders: [MediaFolderEntry] = [],
        cloudRemotes: [CloudRemoteEntry] = []
    ) {
        self.computerName = computerName
        self.selectedDisplayID = selectedDisplayID
        self.qualityPreset = qualityPreset
        self.muteMacWhileStreaming = muteMacWhileStreaming
        self.launchAtLogin = launchAtLogin
        self.mediaServiceEnabled = mediaServiceEnabled
        self.useAlternateEnginePorts = useAlternateEnginePorts
        self.onboardingCompleted = onboardingCompleted
        self.mediaFolders = mediaFolders
        self.cloudRemotes = cloudRemotes
    }

    public static func defaultComputerName() -> String {
        if let name = SCDynamicStoreCopyComputerName(nil, nil) as String?,
           !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            return name
        }
        return Host.current().localizedName ?? "Mac"
    }
}

/// Port constants for the built-in streaming engine and host services.
public enum HostPortDefaults {
    public static let defaultEngineBasePort: UInt16 = 47989
    public static let alternateEngineBasePort: UInt16 = 57989
    public static let defaultControlPort: UInt16 = 48090
    public static let defaultWebDAVPort: UInt16 = 8890

    public static func engineTCPPorts(basePort: UInt16) -> [UInt16] {
        let base = Int(basePort)
        return [base - 5, base, base + 1, base + 21]
            .filter { $0 >= 1024 && $0 <= 65535 }
            .map { UInt16($0) }
    }

    public static func engineHTTPSPort(basePort: UInt16) -> UInt16 {
        UInt16(max(1024, Int(basePort) - 5))
    }

    public static func engineAPIPort(basePort: UInt16) -> UInt16 {
        UInt16(min(65535, Int(basePort) + 1))
    }

    public static func engineRTSPPort(basePort: UInt16) -> UInt16 {
        UInt16(min(65535, Int(basePort) + 21))
    }
}

/// Dependency-injected runtime overrides for unit tests and DEBUG builds.
/// In Release builds, `fromProcess()` ignores all debug environment variables and CLI flags.
public struct RuntimeOverrides: Equatable, Sendable {
    public var dataDirectory: URL?
    public var engineBasePort: UInt16?
    public var controlPort: UInt16?
    public var webDAVPort: UInt16?
    public var engineExecutableURL: URL?

    public init(
        dataDirectory: URL? = nil,
        engineBasePort: UInt16? = nil,
        controlPort: UInt16? = nil,
        webDAVPort: UInt16? = nil,
        engineExecutableURL: URL? = nil
    ) {
        self.dataDirectory = dataDirectory
        self.engineBasePort = engineBasePort
        self.controlPort = controlPort
        self.webDAVPort = webDAVPort
        self.engineExecutableURL = engineExecutableURL
    }

    public static func fromProcess(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        arguments: [String] = CommandLine.arguments
    ) -> RuntimeOverrides {
        #if DEBUG
        var result = RuntimeOverrides()

        if let dir = environment["DEPTH3D_HOST_DATA_DIR"], !dir.isEmpty {
            result.dataDirectory = URL(fileURLWithPath: dir, isDirectory: true)
        }
        if let p = environment["DEPTH3D_HOST_ENGINE_PORT"].flatMap(UInt16.init), p >= 1024 {
            result.engineBasePort = p
        }
        if let p = environment["DEPTH3D_HOST_CONTROL_PORT"].flatMap(UInt16.init), p >= 1024 {
            result.controlPort = p
        }
        if let p = environment["DEPTH3D_HOST_WEBDAV_PORT"].flatMap(UInt16.init), p >= 1024 {
            result.webDAVPort = p
        }
        if let bin = environment["DEPTH3D_HOST_ENGINE_BIN"], !bin.isEmpty {
            result.engineExecutableURL = URL(fileURLWithPath: bin)
        }

        var idx = 1
        while idx < arguments.count {
            let arg = arguments[idx]
            let next = idx + 1 < arguments.count ? arguments[idx + 1] : nil
            switch arg {
            case "--data-dir":
                if let next { result.dataDirectory = URL(fileURLWithPath: next, isDirectory: true); idx += 1 }
            case "--engine-port":
                if let next, let p = UInt16(next), p >= 1024 { result.engineBasePort = p; idx += 1 }
            case "--control-port":
                if let next, let p = UInt16(next), p >= 1024 { result.controlPort = p; idx += 1 }
            case "--webdav-port":
                if let next, let p = UInt16(next), p >= 1024 { result.webDAVPort = p; idx += 1 }
            case "--engine-bin":
                if let next { result.engineExecutableURL = URL(fileURLWithPath: next); idx += 1 }
            default:
                break
            }
            idx += 1
        }
        return result
        #else
        return RuntimeOverrides()
        #endif
    }
}

/// Manages all file paths inside `~/Library/Application Support/depth3d Host/`.
/// Never touches `~/.config/sunshine`.
public struct HostPaths: Equatable, Sendable {
    public let rootDirectory: URL

    public init(rootDirectory: URL? = nil) {
        if let rootDirectory {
            self.rootDirectory = rootDirectory
        } else {
            let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
            self.rootDirectory = appSupport.appendingPathComponent("depth3d Host", isDirectory: true)
        }
    }

    public var identityFile: URL { rootDirectory.appendingPathComponent("host-identity.json") }
    public var settingsFile: URL { rootDirectory.appendingPathComponent("settings.json") }
    public var pairedDevicesFile: URL { rootDirectory.appendingPathComponent("paired-devices.json") }
    public var webDAVCredentialsFile: URL { rootDirectory.appendingPathComponent("webdav-credentials.json") }
    public var engineAdminCredentialsFile: URL { rootDirectory.appendingPathComponent("engine-admin.json") }
    public var childLedgerFile: URL { rootDirectory.appendingPathComponent("running-children.json") }
    public var audioMuteRecoveryFile: URL { rootDirectory.appendingPathComponent("audio-mute-recovery.json") }

    public var tlsDirectory: URL { rootDirectory.appendingPathComponent("tls", isDirectory: true) }
    public var engineDirectory: URL { rootDirectory.appendingPathComponent("engine", isDirectory: true) }
    public var engineHomeDirectory: URL { engineDirectory.appendingPathComponent("home", isDirectory: true) }
    public var engineIsolatedAppdataDirectory: URL {
        engineHomeDirectory.appendingPathComponent(".config/sunshine", isDirectory: true)
    }
    public var engineCredentialsDirectory: URL { engineDirectory.appendingPathComponent("credentials", isDirectory: true) }
    public var engineConfigFile: URL { engineDirectory.appendingPathComponent("engine.conf") }
    public var engineLogFile: URL { engineDirectory.appendingPathComponent("engine.log") }
    public var engineAppsFile: URL { engineDirectory.appendingPathComponent("apps.json") }
    public var engineStateFile: URL { engineDirectory.appendingPathComponent("state.json") }
    public var engineCredentialsFile: URL { engineDirectory.appendingPathComponent("credentials.json") }
    public var engineKeyFile: URL { engineCredentialsDirectory.appendingPathComponent("cakey.pem") }
    public var engineCertFile: URL { engineCredentialsDirectory.appendingPathComponent("cacert.pem") }

    public func ensureDirectoriesExist() throws {
        let fm = FileManager.default
        for dir in [
            rootDirectory,
            tlsDirectory,
            engineDirectory,
            engineHomeDirectory,
            engineIsolatedAppdataDirectory,
            engineCredentialsDirectory
        ] {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: tlsDirectory.path)
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: engineCredentialsDirectory.path)
    }
}

/// Randomly generated WebDAV credentials shared only with paired headsets via `/depth3d/v1/media`.
public struct WebDAVCredentials: Codable, Equatable, Sendable {
    public let user: String
    public let pass: String

    public init(user: String = "depth3d", pass: String) {
        self.user = user
        self.pass = pass
    }
}

/// Internal localhost-only credentials used by Host to manage the built-in streaming engine.
public struct EngineAdminCredentials: Codable, Equatable, Sendable {
    public let username: String
    public let password: String

    public init(username: String = "depth3d_internal", password: String) {
        self.username = username
        self.password = password
    }
}

/// Secure random token generator using `SecRandomCopyBytes`.
public enum SecureRandom {
    public static func hexString(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        if status != errSecSuccess {
            for i in 0..<byteCount {
                bytes[i] = UInt8.random(in: 0...255)
            }
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    public static func asciiSalt(length: Int = 16) -> String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        var bytes = [UInt8](repeating: 0, count: length)
        let status = SecRandomCopyBytes(kSecRandomDefault, length, &bytes)
        if status != errSecSuccess {
            for i in 0..<length { bytes[i] = UInt8.random(in: 0...255) }
        }
        return String(bytes.map { alphabet[Int($0) % alphabet.count] })
    }
}
