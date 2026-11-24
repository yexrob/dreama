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

    /// Strictly pairs ONE incoming GameStream pairing session matching `clientAddress`, submits `pin`
    /// ONLY to that single `pairing_id`, and returns the exact client certificate written to `stateFileURL`
    /// by that completed pairing handshake.
    ///
    /// Never sprays the PIN to unrelated pending pairings or blindly selects `pairings.first`.
    public func completeMatchedPairing(
        pin: String,
        deviceName: String,
        clientAddress: String,
        stateFileURL: URL,
        existingBoundFingerprint: String? = nil,
        maxWait: TimeInterval = 25.0,
        pollInterval: TimeInterval = 0.15
    ) async -> VerifiedEnginePairing? {
        let targetIP = IPAddressNormalizer.normalize(clientAddress)
        return await serializer.runSerialized { [self] in
            let deadline = Date().addingTimeInterval(maxWait)
            var attemptedIds = Set<String>()

            while Date() < deadline {
                if let pairings = try? await self.fetchPendingPairings() {
                    let matching = pairings.filter { pending in
                        guard !attemptedIds.contains(pending.id) else { return false }
                        guard let addr = pending.normalizedAddress else { return false }
                        return addr == targetIP
                    }

                    // Require an unambiguous single pending pairing from `clientAddress`.
                    if matching.count == 1, let target = matching.first {
                        attemptedIds.insert(target.id)
                        let beforeCerts = EngineConfigGenerator.loadPairedCerts(at: stateFileURL)
                        let beforeUUIDs = Set(beforeCerts.map(\.uuid))

                        let ok = (try? await self.submitPIN(
                            pairingId: target.id,
                            pin: pin,
                            deviceName: deviceName
                        )) ?? false

                        guard ok else {
                            HostLogger.warning("Matched pairing session failed PIN verification for \(targetIP)", category: "pair")
                            return nil
                        }

                        // Sunshine persists `state.json` before returning `status: true`.
                        // Diff `state.json` to bind ONLY the exact cert that consumed this PIN.
                        let afterCerts = EngineConfigGenerator.loadPairedCerts(at: stateFileURL)
                        let newlyAdded = afterCerts.filter { !beforeUUIDs.contains($0.uuid) }
                        if newlyAdded.count == 1, let cert = newlyAdded.first, let fp = cert.fingerprintSHA256 {
                            HostLogger.info("Bound paired device \(deviceName) (\(targetIP)) to exact client certificate", category: "pair")
                            return VerifiedEnginePairing(engineUUID: cert.uuid, certFingerprintSHA256: fp)
                        }

                        // Re-pairing an already-bound device whose existing cert remained in `state.json`.
                        if newlyAdded.isEmpty,
                           let existingFP = existingBoundFingerprint,
                           let matched = afterCerts.first(where: {
                               $0.fingerprintSHA256?.caseInsensitiveCompare(existingFP) == .orderedSame
                           }),
                           let fp = matched.fingerprintSHA256
                        {
                            HostLogger.info("Verified re-pairing of existing cert-bound device \(deviceName)", category: "pair")
                            return VerifiedEnginePairing(engineUUID: matched.uuid, certFingerprintSHA256: fp)
                        }

                        HostLogger.warning("Pairing succeeded in engine but exact certificate diff was ambiguous (added=\(newlyAdded.count))", category: "pair")
                        return nil
                    }
                }

                let sleepNs = UInt64(max(0.05, pollInterval) * 1_000_000_000)
                try? await Task.sleep(nanoseconds: sleepNs)
            }

            HostLogger.warning("Timed out waiting for GameStream pairing from \(targetIP)", category: "pair")
            return nil
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
