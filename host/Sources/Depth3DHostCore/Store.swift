import Foundation

/// Thread-safe JSON store for Host identity, settings, paired devices, and generated credentials.
public final class HostStateStore: @unchecked Sendable {
    public let paths: HostPaths
    private let lock = NSLock()
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(paths: HostPaths) throws {
        self.paths = paths
        self.encoder = JSONEncoder()
        self.encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        self.encoder.dateEncodingStrategy = .iso8601
        self.decoder = JSONDecoder()
        self.decoder.dateDecodingStrategy = .iso8601
        try paths.ensureDirectoriesExist()
    }

    public func loadOrCreateIdentity() throws -> HostIdentity {
        lock.lock()
        defer { lock.unlock() }
        if let existing: HostIdentity = readJSON(paths.identityFile) {
            return existing
        }
        let created = HostIdentity()
        try writeJSON(created, to: paths.identityFile, restricted: false)
        return created
    }

    public func loadSettings() -> HostSettings {
        lock.lock()
        defer { lock.unlock() }
        return readJSON(paths.settingsFile) ?? HostSettings()
    }

    public func saveSettings(_ settings: HostSettings) throws {
        lock.lock()
        defer { lock.unlock() }
        try writeJSON(settings, to: paths.settingsFile, restricted: false)
    }

    public func loadPairedDevices() -> [PairedDeviceRecord] {
        lock.lock()
        defer { lock.unlock() }
        return readJSON(paths.pairedDevicesFile) ?? []
    }

    public func savePairedDevices(_ devices: [PairedDeviceRecord]) throws {
        lock.lock()
        defer { lock.unlock() }
        try writeJSON(devices, to: paths.pairedDevicesFile, restricted: true)
    }

    public func upsertPairedDevice(_ record: PairedDeviceRecord) throws -> [PairedDeviceRecord] {
        lock.lock()
        defer { lock.unlock() }
        var devices: [PairedDeviceRecord] = readJSON(paths.pairedDevicesFile) ?? []
        if let idx = devices.firstIndex(where: { $0.deviceId == record.deviceId }) {
            devices[idx] = record
        } else {
            devices.append(record)
        }
        try writeJSON(devices, to: paths.pairedDevicesFile, restricted: true)
        return devices
    }

    public func removePairedDevice(deviceId: String) throws -> [PairedDeviceRecord] {
        lock.lock()
        defer { lock.unlock() }
        var devices: [PairedDeviceRecord] = readJSON(paths.pairedDevicesFile) ?? []
        devices.removeAll { $0.deviceId == deviceId }
        try writeJSON(devices, to: paths.pairedDevicesFile, restricted: true)
        return devices
    }

    public func loadOrCreateWebDAVCredentials() throws -> WebDAVCredentials {
        lock.lock()
        defer { lock.unlock() }
        if let existing: WebDAVCredentials = readJSON(paths.webDAVCredentialsFile), !existing.pass.isEmpty {
            return existing
        }
        let created = WebDAVCredentials(user: "depth3d", pass: SecureRandom.hexString(byteCount: 12))
        try writeJSON(created, to: paths.webDAVCredentialsFile, restricted: true)
        return created
    }

    public func rotateWebDAVCredentials() throws -> WebDAVCredentials {
        lock.lock()
        defer { lock.unlock() }
        let created = WebDAVCredentials(user: "depth3d", pass: SecureRandom.hexString(byteCount: 12))
        try writeJSON(created, to: paths.webDAVCredentialsFile, restricted: true)
        return created
    }

    public func loadOrCreateEngineAdminCredentials() throws -> EngineAdminCredentials {
        lock.lock()
        defer { lock.unlock() }
        if let existing: EngineAdminCredentials = readJSON(paths.engineAdminCredentialsFile), !existing.password.isEmpty {
            return existing
        }
        let created = EngineAdminCredentials(
            username: "depth3d_internal",
            password: SecureRandom.hexString(byteCount: 16)
        )
        try writeJSON(created, to: paths.engineAdminCredentialsFile, restricted: true)
        return created
    }

    private func readJSON<T: Decodable>(_ url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func writeJSON<T: Encodable>(_ value: T, to url: URL, restricted: Bool) throws {
        try paths.ensureDirectoriesExist()
        let data = try encoder.encode(value)
        try data.write(to: url, options: .atomic)
        if restricted {
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        }
    }
}
