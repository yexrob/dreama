import CryptoKit
import Foundation

/// A paired client entry stored inside the streaming engine's `state.json`.
public struct EnginePairedCert: Equatable, Sendable {
    public let uuid: String
    public let name: String
    public let certPEM: String
    public let fingerprintSHA256: String?

    public init(uuid: String, name: String, certPEM: String) {
        self.uuid = uuid
        self.name = name
        self.certPEM = certPEM
        self.fingerprintSHA256 = TLSIdentityManager.sha256Hex(fromPEM: certPEM)
    }
}

/// Generates isolated configuration files (`engine.conf`, `apps.json`, and `credentials.json`)
/// for the built-in streaming engine inside `~/Library/Application Support/depth3d Host/engine/`.
/// Never reads or modifies `~/.config/sunshine`.
public enum EngineConfigGenerator {
    public struct Input: Equatable, Sendable {
        public let hostName: String
        public let basePort: UInt16
        public let quality: StreamQualityPreset
        public let displayID: String?
        public let paths: HostPaths

        public init(
            hostName: String,
            basePort: UInt16,
            quality: StreamQualityPreset,
            displayID: String?,
            paths: HostPaths
        ) {
            self.hostName = hostName
            self.basePort = basePort
            self.quality = quality
            self.displayID = displayID
            self.paths = paths
        }
    }

    /// Generates `engine.conf`.
    /// - `log_path` redirects all engine logs and rotated backups strictly into `engineDir`.
    /// - `max_bitrate` & `vt_coder` & `vt_realtime` map directly to VideoToolbox parameters
    ///   (note: `qp` is intentionally omitted because Sunshine's `videotoolbox` encoder ignores `qp`).
    /// - `audio_sink =` (blank) activates native macOS 14.2+ Core Audio Process Tap without BlackHole.
    public static func renderConfig(_ input: Input) -> String {
        let sanitizedName = input.hostName
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let displayName = sanitizedName.isEmpty ? "Mac" : sanitizedName

        var lines: [String] = [
            "# Generated automatically by depth3d Host. Do not edit manually.",
            "sunshine_name = \(displayName)",
            "port = \(input.basePort)",
            "origin_web_ui_allowed = pc",
            "system_tray = disabled",
            "upnp = disabled",
            "notify_pre_releases = disabled",
            "min_log_level = 2",
            "log_path = \(input.paths.engineLogFile.path)",
            "encoder = videotoolbox",
            "hevc_mode = 0",
            "av1_mode = 1",
            "vt_coder = \(input.quality.vtCoder)",
            "vt_software = auto",
            "vt_realtime = \(input.quality.realtimeEncoding ? "enabled" : "disabled")",
            "max_bitrate = \(input.quality.maxBitrateKbps)",
            "stream_audio = enabled",
            "audio_sink =",
            "file_state = \(input.paths.engineStateFile.path)",
            "file_apps = \(input.paths.engineAppsFile.path)",
            "credentials_file = \(input.paths.engineCredentialsFile.path)",
            "pkey = \(input.paths.engineKeyFile.path)",
            "cert = \(input.paths.engineCertFile.path)"
        ]

        if let displayID = input.displayID?.trimmingCharacters(in: .whitespacesAndNewlines),
           !displayID.isEmpty
        {
            lines.append("output_name = \(displayID)")
        }

        return lines.joined(separator: "\n") + "\n"
    }

    public static func renderAppsJSON() -> String {
        """
        {
          "env": {},
          "apps": [
            {
              "name": "Desktop",
              "image-path": "desktop.png"
            }
          ]
        }
        """
    }

    public static func writeFiles(_ input: Input) throws {
        try input.paths.ensureDirectoriesExist()
        let confText = renderConfig(input)
        try confText.write(to: input.paths.engineConfigFile, atomically: true, encoding: .utf8)
        let appsText = renderAppsJSON()
        try appsText.write(to: input.paths.engineAppsFile, atomically: true, encoding: .utf8)
    }

    /// Computes Sunshine's exact `credentials.json` password hash (`util::hex(crypto::hash(password + salt))`
    /// where `util::hex` with `rev = false` emits the 32 SHA-256 digest bytes in reverse order as uppercase hex).
    /// Writing `credentials.json` directly avoids ever passing `--creds <user> <pass>` on the command line.
    public static func sunshinePasswordHash(password: String, salt: String) -> String {
        let digest = SHA256.hash(data: Data((password + salt).utf8))
        return digest.reversed().map { String(format: "%02X", $0) }.joined()
    }

    /// Writes `credentials.json` with `0600` permissions without invoking `--creds`.
    public static func writeCredentialsFile(
        to url: URL,
        credentials: EngineAdminCredentials,
        salt: String = SecureRandom.asciiSalt(length: 16)
    ) throws {
        let hash = sunshinePasswordHash(password: credentials.password, salt: salt)
        let payload: [String: String] = [
            "username": credentials.username,
            "salt": salt,
            "password": hash
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    public static func locateEngineExecutable(override: URL? = nil, bundle: Bundle = .main) -> URL? {
        let fm = FileManager.default
        if let override, fm.isExecutableFile(atPath: override.path) {
            return override
        }
        let bundleURL = bundle.bundleURL
        let candidates: [URL] = [
            bundleURL.appendingPathComponent("Contents/Helpers/depth3d-stream-engine"),
            bundleURL.appendingPathComponent("Contents/Helpers/stream-engine/MacOS/depth3d-stream-engine"),
            bundleURL.deletingLastPathComponent().appendingPathComponent("Helpers/depth3d-stream-engine"),
            bundleURL.deletingLastPathComponent().appendingPathComponent("Helpers/stream-engine/MacOS/depth3d-stream-engine"),
            URL(fileURLWithPath: fm.currentDirectoryPath)
                .appendingPathComponent("build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine"),
            URL(fileURLWithPath: fm.currentDirectoryPath)
                .appendingPathComponent("build/engine-x86_64/stream-engine/MacOS/depth3d-stream-engine")
        ]
        for candidate in candidates where fm.isExecutableFile(atPath: candidate.path) {
            return candidate
        }
        return nil
    }

    public static func parsePairedCerts(fromStateJSON data: Data) -> [EnginePairedCert] {
        guard let rootObj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let root = rootObj["root"] as? [String: Any],
              let namedCerts = root["named_certs"] as? [[String: Any]]
        else {
            return []
        }

        let fallbackName = HostStrings.string("device.default_name")
        return namedCerts.compactMap { entry in
            guard let uuid = entry["uuid"] as? String,
                  let cert = entry["cert"] as? String
            else {
                return nil
            }
            let name = (entry["name"] as? String) ?? fallbackName
            return EnginePairedCert(uuid: uuid, name: name, certPEM: cert)
        }
    }

    public static func loadPairedCerts(at stateFileURL: URL) -> [EnginePairedCert] {
        guard let data = try? Data(contentsOf: stateFileURL) else { return [] }
        return parsePairedCerts(fromStateJSON: data)
    }
}
