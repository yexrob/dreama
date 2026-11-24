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

    /// Loads paired devices, automatically migrating legacy files that contain duplicate records
    /// sharing the same certificate fingerprint (keeping the most recently seen/paired record).
    public func loadPairedDevices() -> [PairedDeviceRecord] {
        lock.lock()
        defer { lock.unlock() }
        let raw: [PairedDeviceRecord] = readJSON(paths.pairedDevicesFile) ?? []
        let (deduped, changed) = Self.deduplicatePairedDevices(raw)
        if changed {
            try? writeJSON(deduped, to: paths.pairedDevicesFile, restricted: true)
        }
        return deduped
    }

    /// Merges duplicate records that share the same normalized `certFingerprint` (keeping the record
    /// with the latest `lastSeenAt`, tie-broken by `pairedAt`). Legacy nil-fingerprint records are
    /// deduplicated by `deviceId`, preserving ALL distinct `engineUUID`s from merged records for cleanup.
    internal static func deduplicatePairedDevices(_ devices: [PairedDeviceRecord]) -> (records: [PairedDeviceRecord], didMerge: Bool) {
        var byKey: [String: (index: Int, record: PairedDeviceRecord)] = [:]
        var ordered: [PairedDeviceRecord] = []
        var didMerge = false

        for item in devices {
            // Normalize fingerprint casing for migrated records loaded from older JSON.
            let normalized = PairedDeviceRecord(
                deviceId: item.deviceId,
                name: item.name,
                engineUUID: item.engineUUID,
                legacyEngineUUIDs: item.legacyEngineUUIDs,
                certFingerprint: item.certFingerprint,
                autoAllow: item.autoAllow,
                pairedAt: item.pairedAt,
                lastSeenAt: item.lastSeenAt
            )
            let key = normalized.id
            if let existing = byKey[key] {
                didMerge = true
                let keepNew = normalized.lastSeenAt > existing.record.lastSeenAt
                    || (normalized.lastSeenAt == existing.record.lastSeenAt && normalized.pairedAt > existing.record.pairedAt)
                var winner = keepNew ? normalized : existing.record
                let loser = keepNew ? existing.record : normalized
                if winner.certFingerprint == nil {
                    // Preserve engineUUIDs from BOTH merged nil-fingerprint legacy records for later unpair cleanup.
                    var combinedUUIDs = winner.allLegacyEngineUUIDs
                    for uuid in loser.allLegacyEngineUUIDs where !combinedUUIDs.contains(uuid) {
                        combinedUUIDs.append(uuid)
                    }
                    let primary = combinedUUIDs.first
                    let extras = Array(combinedUUIDs.dropFirst())
                    winner.engineUUID = primary
                    winner.legacyEngineUUIDs = extras.isEmpty ? nil : extras
                }
                ordered[existing.index] = winner
                byKey[key] = (existing.index, winner)
            } else {
                byKey[key] = (ordered.count, normalized)
                ordered.append(normalized)
            }
        }
        return (ordered, didMerge)
    }

    public func savePairedDevices(_ devices: [PairedDeviceRecord]) throws {
        lock.lock()
        defer { lock.unlock() }
        try writeJSON(devices, to: paths.pairedDevicesFile, restricted: true)
    }

    /// Inserts or updates a paired device keyed strictly by primary key `record.id`
    /// (its certificate SHA-256 fingerprint, or `"legacy:<deviceId>"` for unmigrated records).
    /// Never overwrites a distinct certificate that happens to share the same `deviceId` label.
    public func upsertPairedDevice(_ record: PairedDeviceRecord) throws -> [PairedDeviceRecord] {
        lock.lock()
        defer { lock.unlock() }
        var devices: [PairedDeviceRecord] = readJSON(paths.pairedDevicesFile) ?? []
        if let idx = devices.firstIndex(where: { $0.id == record.id }) {
            devices[idx] = record
        } else {
            devices.append(record)
        }
        try writeJSON(devices, to: paths.pairedDevicesFile, restricted: true)
        return devices
    }

    /// Removes a single paired device record by its primary key (`PairedDeviceRecord.id`).
    public func removePairedDevice(id: String) throws -> [PairedDeviceRecord] {
        lock.lock()
        defer { lock.unlock() }
        var devices: [PairedDeviceRecord] = readJSON(paths.pairedDevicesFile) ?? []
        devices.removeAll { $0.id == id }
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
