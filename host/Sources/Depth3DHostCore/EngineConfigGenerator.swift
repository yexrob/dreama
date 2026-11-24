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

/// Generates isolated configuration files (`engine.conf` and `apps.json`) for the built-in
/// streaming engine inside `~/Library/Application Support/depth3d Host/engine/`.
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

    /// Generates the `engine.conf` text.
    /// Notes:
    /// - `origin_web_ui_allowed = pc` keeps the internal management API bound to localhost only.
    /// - `system_tray = disabled` prevents any upstream menu bar icon or Qt event loop.
    /// - `encoder = videotoolbox` + `hevc_mode = 0` prioritizes VideoToolbox HEVC with automatic H.264 fallback.
    /// - `audio_sink =` (blank) enables native macOS Core Audio Process Tap without BlackHole.
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
            "encoder = videotoolbox",
            "hevc_mode = 0",
            "av1_mode = 1",
            "vt_coder = auto",
            "vt_software = auto",
            "vt_realtime = \(input.quality.realtimeEncoding ? "enabled" : "disabled")",
            "qp = \(input.quality.qp)",
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

    /// Generates a minimal `apps.json` exposing the full desktop as the default stream target.
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

    /// Locates the bundled streaming engine helper executable inside `Contents/Helpers/stream-engine/`
    /// or development build output directories.
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

    /// Parses the streaming engine's `state.json` to extract paired client certificates
    /// for `/depth3d/v1/media` TLS client certificate verification.
    public static func parsePairedCerts(fromStateJSON data: Data) -> [EnginePairedCert] {
        guard let rootObj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let root = rootObj["root"] as? [String: Any],
              let namedCerts = root["named_certs"] as? [[String: Any]]
        else {
            return []
        }

        return namedCerts.compactMap { entry in
            guard let uuid = entry["uuid"] as? String,
                  let cert = entry["cert"] as? String
            else {
                return nil
            }
            let name = (entry["name"] as? String) ?? "头显设备"
            return EnginePairedCert(uuid: uuid, name: name, certPEM: cert)
        }
    }

    public static func loadPairedCerts(at stateFileURL: URL) -> [EnginePairedCert] {
        guard let data = try? Data(contentsOf: stateFileURL) else { return [] }
        return parsePairedCerts(fromStateJSON: data)
    }
}
