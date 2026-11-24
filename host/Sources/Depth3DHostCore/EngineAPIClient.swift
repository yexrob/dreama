import Foundation

/// Pending GameStream pairing request returned by Sunshine `GET /api/pin`
/// (`src/confighttp.cpp`: `{ id, name, address }`).
public struct EnginePendingPairing: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String?
    public let address: String?

    public init(id: String, name: String? = nil, address: String? = nil) {
        self.id = id
        self.name = name
        self.address = address
    }

    public var normalizedAddress: String? {
        address.map(IPAddressNormalizer.normalize)
    }
}

public struct EngineClientRecord: Codable, Equatable, Identifiable, Sendable {
    public let uuid: String
    public let name: String
    public let enabled: Bool?

    public var id: String { uuid }
    public var isEnabled: Bool { enabled ?? true }

    public init(uuid: String, name: String, enabled: Bool? = true) {
        self.uuid = uuid
        self.name = name
        self.enabled = enabled
    }
}

/// Cryptographically verified outcome of submitting a PIN to one specific engine pairing session.
public struct VerifiedEnginePairing: Equatable, Sendable {
    public let engineUUID: String
    public let certFingerprintSHA256: String

    public init(engineUUID: String, certFingerprintSHA256: String) {
        self.engineUUID = engineUUID
        self.certFingerprintSHA256 = certFingerprintSHA256.uppercased()
    }
}

public enum EngineAPIError: Error, LocalizedError, Equatable {
    case invalidResponse
    case unauthorized
    case httpError(Int)
    case noPendingPairing
    case multiplePendingPairings([EnginePendingPairing])
    case pinRejected

    public var errorDescription: String? {
        switch self {
        case .invalidResponse: return "Invalid response from local streaming engine"
        case .unauthorized: return "Local engine authentication failed"
        case .httpError(let code): return "Local engine HTTP \(code)"
        case .noPendingPairing: return "No pending pairing session"
        case .multiplePendingPairings: return "Multiple pending pairing sessions require explicit selection"
        case .pinRejected: return "Pairing PIN was rejected"
        }
    }
}

/// Serializes PIN submission + `state.json` before/after diffing so two pairings can never
/// cross-contaminate which client certificate consumed a given PIN.
private actor PairingCompletionSerializer {
    private var isBusy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func runSerialized<T: Sendable>(_ operation: @Sendable () async -> T) async -> T {
        if isBusy {
            await withCheckedContinuation { continuation in
                waiters.append(continuation)
            }
        }
        isBusy = true
        defer {
            if !waiters.isEmpty {
                let next = waiters.removeFirst()
                next.resume()
            } else {
                isBusy = false
            }
        }
        return await operation()
    }
}

private final class LoopbackSelfSignedDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let host = challenge.protectionSpace.host
        guard (host == "127.0.0.1" || host == "localhost"),
              challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}

/// Client for the built-in streaming engine's localhost-only management API (`https://127.0.0.1:<port+1>/api/*`).
/// Never exposes the engine's web UI to the user and disables system HTTP proxies on loopback requests.
public final class EngineAPIClient: @unchecked Sendable {
    private let session: URLSession
    private let delegate: LoopbackSelfSignedDelegate
    private let lock = NSLock()
    private let serializer = PairingCompletionSerializer()
    private var apiPort: UInt16
    private var credentials: EngineAdminCredentials

    public init(apiPort: UInt16, credentials: EngineAdminCredentials) {
        self.apiPort = apiPort
        self.credentials = credentials
        let del = LoopbackSelfSignedDelegate()
        self.delegate = del
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 25
        cfg.httpShouldSetCookies = false
        cfg.connectionProxyDictionary = [:]
        self.session = URLSession(configuration: cfg, delegate: del, delegateQueue: nil)
    }

    deinit {
        session.invalidateAndCancel()
    }

    public func updateEndpoint(apiPort: UInt16, credentials: EngineAdminCredentials) {
        lock.lock()
        self.apiPort = apiPort
        self.credentials = credentials
        lock.unlock()
    }

    private func endpointConfig() -> (UInt16, EngineAdminCredentials) {
        lock.lock()
        defer { lock.unlock() }
        return (apiPort, credentials)
    }

    private func makeRequest(path: String, method: String = "GET", body: Data? = nil, csrfToken: String? = nil) -> URLRequest {
        let (port, creds) = endpointConfig()
        let url = URL(string: "https://127.0.0.1:\(port)\(path)")!
        var req = URLRequest(url: url)
        req.httpMethod = method
        let rawAuth = "\(creds.username):\(creds.password)"
        let encodedAuth = Data(rawAuth.utf8).base64EncodedString()
        req.setValue("Basic \(encodedAuth)", forHTTPHeaderField: "Authorization")
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let csrfToken {
            req.setValue(csrfToken, forHTTPHeaderField: "X-CSRF-Token")
        }
        return req
    }

    private func fetchCSRFToken() async -> String? {
        struct TokenResponse: Decodable { let csrf_token: String? }
        do {
            let (data, resp) = try await session.data(for: makeRequest(path: "/api/csrf-token"))
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { return nil }
            return try JSONDecoder().decode(TokenResponse.self, from: data).csrf_token
        } catch {
            return nil
        }
    }

    public func fetchPendingPairings() async throws -> [EnginePendingPairing] {
        struct PendingResponse: Decodable { let pairings: [EnginePendingPairing]? }
        let (data, resp) = try await session.data(for: makeRequest(path: "/api/pin"))
        guard let http = resp as? HTTPURLResponse else { throw EngineAPIError.invalidResponse }
        if http.statusCode == 401 { throw EngineAPIError.unauthorized }
        guard http.statusCode == 200 else { throw EngineAPIError.httpError(http.statusCode) }
        return try JSONDecoder().decode(PendingResponse.self, from: data).pairings ?? []
    }

    /// Submits a 4-digit PIN to ONE specific `pairingId`. Sunshine blocks until the full
    /// GameStream 4-step cryptographic handshake completes and returns `status: true` only on success.
    public func submitPIN(pairingId: String, pin: String, deviceName: String) async throws -> Bool {
        struct PinResponse: Decodable { let status: Bool? }
        let csrf = await fetchCSRFToken()
        let body = try JSONSerialization.data(withJSONObject: [
            "pairing_id": pairingId,
            "pin": pin,
            "name": deviceName
        ])
        let (data, resp) = try await session.data(
            for: makeRequest(path: "/api/pin", method: "POST", body: body, csrfToken: csrf)
        )
        guard let http = resp as? HTTPURLResponse else { throw EngineAPIError.invalidResponse }
        if http.statusCode == 401 { throw EngineAPIError.unauthorized }
        guard http.statusCode == 200 else { throw EngineAPIError.httpError(http.statusCode) }
        let parsed = try JSONDecoder().decode(PinResponse.self, from: data)
        return parsed.status == true
    }

    /// Submits a manual 4-digit PIN. If `pairingId` is nil, succeeds ONLY when there is a single
    /// unambiguous pending pairing; when multiple headsets are waiting, throws `.multiplePendingPairings`
    /// so the UI lets the user explicitly choose the matching device/IP.
    public func submitManualPIN(pin: String, deviceName: String, pairingId: String? = nil) async throws {
        let targetId: String
        if let explicit = pairingId, !explicit.isEmpty {
            targetId = explicit
        } else {
            let pairings = try await fetchPendingPairings()
            if pairings.isEmpty {
                throw EngineAPIError.noPendingPairing
            }
            if pairings.count > 1 {
                throw EngineAPIError.multiplePendingPairings(pairings)
            }
            targetId = pairings[0].id
        }
        let ok = try await submitPIN(pairingId: targetId, pin: pin, deviceName: deviceName)
        if !ok {
            throw EngineAPIError.pinRejected
        }
    }

    /// Feeds `pin` in the background to the single GameStream pending pairing session originating
    /// from `clientAddress` within `maxWait` seconds (protocol v1: 60s window after `/pair` returns).
    ///
    /// Safety rules (`docs/specs/host-protocol.md`):
    /// - Never sprays `pin` to unrelated IPs.
    /// - If multiple pending pairing sessions exist from `clientAddress`, cancels ALL of them
    ///   and refuses to submit `pin`.
    /// - On PIN rejection, failure, or timeout, cancels any leftover pending pairing sessions
    ///   from `clientAddress` so an immediate retry never collides with a stale 5-minute session.
    @discardableResult
    public func feedPINForApprovedSession(
        pin: String,
        deviceName: String,
        clientAddress: String,
        maxWait: TimeInterval = 60.0,
        pollInterval: TimeInterval = 0.15
    ) async -> Bool {
        let targetIP = IPAddressNormalizer.normalize(clientAddress)
        let deadline = Date().addingTimeInterval(maxWait)

        while !Task.isCancelled && Date() < deadline {
            if let pairings = try? await fetchPendingPairings() {
                let matching = pairings.filter { $0.normalizedAddress == targetIP }
                if matching.count > 1 {
                    HostLogger.warning("Multiple (\(matching.count)) pending GameStream sessions from \(targetIP); cancelling all", category: "pair")
                    for session in matching {
                        try? await cancelPairing(pairingId: session.id)
                    }
                    return false
                }
                if matching.count == 1, let target = matching.first {
                    let ok = (try? await submitPIN(
                        pairingId: target.id,
                        pin: pin,
                        deviceName: deviceName
                    )) ?? false
                    if ok {
                        HostLogger.info("Background PIN feeder completed GameStream pairing for \(deviceName) (\(targetIP))", category: "pair")
                        return true
                    }
                    if Task.isCancelled {
                        break
                    }
                    HostLogger.warning("Background PIN submission failed for \(targetIP); cancelling pending sessions", category: "pair")
                    await cancelPendingPairings(forIP: targetIP)
                    return false
                }
            }

            let sleepNs = UInt64(max(0.05, pollInterval) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: sleepNs)
        }

        if !Task.isCancelled {
            HostLogger.warning("Background PIN feeder timed out after \(Int(maxWait))s for \(targetIP); cancelling pending sessions", category: "pair")
            await cancelPendingPairings(forIP: targetIP)
        } else {
            // Cancelled (superseded ticket, expired ticket, or shutdown): best-effort cleanup of
            // any pending pairing session left open for this IP using an uncancelled cleanup task.
            let api = self
            await Task.detached {
                await api.cancelPendingPairings(forIP: targetIP)
            }.value
        }
        return false
    }

    /// Cancels all pending GameStream pairing sessions in the streaming engine matching `clientAddress`.
    public func cancelPendingPairings(forIP clientAddress: String) async {
        let targetIP = IPAddressNormalizer.normalize(clientAddress)
        guard let pairings = try? await fetchPendingPairings() else { return }
        let matching = pairings.filter { $0.normalizedAddress == targetIP }
        for session in matching {
            HostLogger.info("Cancelling stale engine pending pairing \(session.id) for \(targetIP)", category: "pair")
            try? await cancelPairing(pairingId: session.id)
        }
    }

    /// Ensures `fingerprintSHA256` exists and is **unique & enabled** inside the streaming engine's
    /// `state.json`. If Sunshine recorded duplicate entries for the same certificate (which causes
    /// Sunshine's `is_client_enabled` to reject the headset!), keeps ONLY the newest enabled entry
    /// and unpairs all older/duplicate entries via `POST /api/clients/unpair`.
    ///
    /// Returns the single kept enabled `EnginePairedCert`, or `nil` if no enabled entry exists yet.
    public func ensureUniqueEnabledCert(
        fingerprintSHA256: String,
        stateFileURL: URL
    ) async -> EnginePairedCert? {
        return await serializer.runSerialized { [self] in
            let all = EngineConfigGenerator.loadAllPairedCerts(at: stateFileURL)
            let matches = all.filter {
                $0.fingerprintSHA256?.caseInsensitiveCompare(fingerprintSHA256) == .orderedSame
            }
            let enabledMatches = matches.filter(\.enabled)
            guard let newestEnabled = enabledMatches.last else {
                return nil
            }

            if matches.count > 1 {
                HostLogger.warning(
                    "Detected \(matches.count) duplicate engine entries for client cert; keeping newest uuid=\(newestEnabled.uuid) and removing \(matches.count - 1) duplicate(s)",
                    category: "pair"
                )
                for other in matches where other.uuid != newestEnabled.uuid {
                    _ = try? await self.unpairClient(uuid: other.uuid)
                }

                // Wait briefly for Sunshine to flush state.json after unpair calls.
                let flushDeadline = Date().addingTimeInterval(1.0)
                while Date() < flushDeadline {
                    if EngineConfigGenerator.isCertificateUniquelyEnabled(
                        fingerprintSHA256: fingerprintSHA256,
                        at: stateFileURL
                    ) {
                        break
                    }
                    try? await Task.sleep(nanoseconds: 50_000_000)
                }
            }

            let finalAll = EngineConfigGenerator.loadAllPairedCerts(at: stateFileURL)
            let finalMatches = finalAll.filter {
                $0.fingerprintSHA256?.caseInsensitiveCompare(fingerprintSHA256) == .orderedSame
            }
            guard finalMatches.count == 1, finalMatches[0].enabled else {
                return nil
            }
            return finalMatches[0]
        }
    }

    /// Removes EVERY entry in the streaming engine matching `fingerprintSHA256` (including any
    /// duplicates or entries without a stored `engineUUID`), plus `fallbackUUID` if provided,
    /// and waits briefly for Sunshine to flush `state.json` so a subsequent fresh pairing
    /// cannot collide with an un-flushed duplicate entry (B-1).
    public func unpairAllEntries(
        forFingerprint fingerprintSHA256: String,
        fallbackUUID: String? = nil,
        stateFileURL: URL
    ) async {
        await serializer.runSerialized { [self] in
            var uuidsToRemove: [String] = []
            let all = EngineConfigGenerator.loadAllPairedCerts(at: stateFileURL)
            for cert in all where cert.fingerprintSHA256?.caseInsensitiveCompare(fingerprintSHA256) == .orderedSame {
                if !uuidsToRemove.contains(cert.uuid) {
                    uuidsToRemove.append(cert.uuid)
                }
            }
            if let fallbackUUID, !fallbackUUID.isEmpty, !uuidsToRemove.contains(fallbackUUID) {
                uuidsToRemove.append(fallbackUUID)
            }
            guard !uuidsToRemove.isEmpty else { return }
            for uuid in uuidsToRemove {
                _ = try? await self.unpairClient(uuid: uuid)
            }

            let flushDeadline = Date().addingTimeInterval(1.0)
            while Date() < flushDeadline {
                let remaining = EngineConfigGenerator.loadAllPairedCerts(at: stateFileURL).filter {
                    $0.fingerprintSHA256?.caseInsensitiveCompare(fingerprintSHA256) == .orderedSame
                }
                if remaining.isEmpty { break }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
    }

    public func cancelPairing(pairingId: String) async throws {
        let csrf = await fetchCSRFToken()
        let body = try JSONSerialization.data(withJSONObject: ["pairing_id": pairingId])
        _ = try await session.data(
            for: makeRequest(path: "/api/pin", method: "DELETE", body: body, csrfToken: csrf)
        )
    }

    /// Fetches paired clients from Sunshine's `GET /api/clients/list`.
    /// Empirically verified against Sunshine v2026.914.233613: returns
    /// `{"named_certs":[{"enabled":true,"name":"...","uuid":"..."}],"status":true}`
    /// (with defensive fallback to `named_devices` if an upstream update renames the API key).
    public func listPairedClients() async throws -> [EngineClientRecord] {
        struct ClientsResponse: Decodable {
            let status: Bool?
            let named_certs: [EngineClientRecord]?
            let named_devices: [EngineClientRecord]?
        }
        let (data, resp) = try await session.data(for: makeRequest(path: "/api/clients/list"))
        guard let http = resp as? HTTPURLResponse else { throw EngineAPIError.invalidResponse }
        if http.statusCode == 401 { throw EngineAPIError.unauthorized }
        guard http.statusCode == 200 else { throw EngineAPIError.httpError(http.statusCode) }
        let decoded = try JSONDecoder().decode(ClientsResponse.self, from: data)
        let list = decoded.named_certs ?? decoded.named_devices ?? []
        return list.filter(\.isEnabled)
    }

    public func unpairClient(uuid: String) async throws -> Bool {
        struct SimpleStatus: Decodable { let status: Bool? }
        let csrf = await fetchCSRFToken()
        let body = try JSONSerialization.data(withJSONObject: ["uuid": uuid])
        let (data, resp) = try await session.data(
            for: makeRequest(path: "/api/clients/unpair", method: "POST", body: body, csrfToken: csrf)
        )
        guard let http = resp as? HTTPURLResponse else { throw EngineAPIError.invalidResponse }
        guard http.statusCode == 200 else { throw EngineAPIError.httpError(http.statusCode) }
        return (try? JSONDecoder().decode(SimpleStatus.self, from: data).status) ?? true
    }

    public func unpairAllClients() async throws -> Bool {
        struct SimpleStatus: Decodable { let status: Bool? }
        let csrf = await fetchCSRFToken()
        let (data, resp) = try await session.data(
            for: makeRequest(path: "/api/clients/unpair-all", method: "POST", body: Data("{}".utf8), csrfToken: csrf)
        )
        guard let http = resp as? HTTPURLResponse else { throw EngineAPIError.invalidResponse }
        guard http.statusCode == 200 else { throw EngineAPIError.httpError(http.statusCode) }
        return (try? JSONDecoder().decode(SimpleStatus.self, from: data).status) ?? true
    }

    public func closeActiveSession() async throws -> Bool {
        struct SimpleStatus: Decodable { let status: Bool? }
        let csrf = await fetchCSRFToken()
        let (data, resp) = try await session.data(
            for: makeRequest(path: "/api/apps/close", method: "POST", body: Data("{}".utf8), csrfToken: csrf)
        )
        guard let http = resp as? HTTPURLResponse else { throw EngineAPIError.invalidResponse }
        guard http.statusCode == 200 else { throw EngineAPIError.httpError(http.statusCode) }
        return (try? JSONDecoder().decode(SimpleStatus.self, from: data).status) ?? true
    }
}
