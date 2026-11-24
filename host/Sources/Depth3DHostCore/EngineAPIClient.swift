import Foundation

public struct EnginePendingPairing: Codable, Equatable, Sendable {
    public let id: String
    public let clientAddress: String?

    enum CodingKeys: String, CodingKey {
        case id
        case clientAddress = "client_address"
    }

    public init(id: String, clientAddress: String? = nil) {
        self.id = id
        self.clientAddress = clientAddress
    }
}

public struct EngineClientRecord: Codable, Equatable, Identifiable, Sendable {
    public let uuid: String
    public let name: String

    public var id: String { uuid }

    public init(uuid: String, name: String) {
        self.uuid = uuid
        self.name = name
    }
}

public enum EngineAPIError: Error, LocalizedError {
    case invalidResponse
    case unauthorized
    case httpError(Int)
    case noPendingPairing
    case pinRejected

    public var errorDescription: String? {
        switch self {
        case .invalidResponse: return "Invalid response from local streaming engine"
        case .unauthorized: return "Local engine authentication failed"
        case .httpError(let code): return "Local engine HTTP \(code)"
        case .noPendingPairing: return "No pending pairing session"
        case .pinRejected: return "Pairing PIN was rejected"
        }
    }
}

/// Loopback-only URLSession delegate that accepts the local streaming engine's self-signed
/// HTTPS certificate strictly on `127.0.0.1` / `localhost`.
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
/// Never exposes the engine's web UI to the user.
public final class EngineAPIClient: @unchecked Sendable {
    private let session: URLSession
    private let delegate: LoopbackSelfSignedDelegate
    private let lock = NSLock()
    private var apiPort: UInt16
    private var credentials: EngineAdminCredentials

    public init(apiPort: UInt16, credentials: EngineAdminCredentials) {
        self.apiPort = apiPort
        self.credentials = credentials
        let del = LoopbackSelfSignedDelegate()
        self.delegate = del
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 5
        cfg.timeoutIntervalForResource = 10
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

    /// Submits a 4-digit PIN for a specific pending pairing session. Never logs the PIN.
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

    /// Submits a 4-digit PIN to the first currently pending pairing (for manual PIN UI).
    public func submitManualPIN(pin: String, deviceName: String) async throws {
        let pairings = try await fetchPendingPairings()
        guard let first = pairings.first else {
            throw EngineAPIError.noPendingPairing
        }
        let ok = try await submitPIN(pairingId: first.id, pin: pin, deviceName: deviceName)
        if !ok {
            throw EngineAPIError.pinRejected
        }
    }

    /// Waits for a pending GameStream pairing session to appear (or uses one already queued)
    /// and submits the approved PIN. Supports both headset orderings cleanly:
    /// - Headset started GameStream `/pair` before `/depth3d/v1/pair` was approved
    /// - Headset starts GameStream `/pair` immediately after `/depth3d/v1/pair` returns 200
    public func submitPINWhenReady(
        pin: String,
        deviceName: String,
        maxWait: TimeInterval = 25.0,
        pollInterval: TimeInterval = 0.15
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(maxWait)
        while Date() < deadline {
            if let pairings = try? await fetchPendingPairings(), !pairings.isEmpty {
                for pending in pairings {
                    if (try? await submitPIN(pairingId: pending.id, pin: pin, deviceName: deviceName)) == true {
                        HostLogger.info("Submitted approved pairing PIN to streaming engine for \(deviceName)", category: "pair")
                        return true
                    }
                }
            }
            let sleepNs = UInt64(max(0.05, pollInterval) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: sleepNs)
        }
        HostLogger.warning("No incoming GameStream pairing session arrived before timeout for \(deviceName)", category: "pair")
        return false
    }

    public func cancelPairing(pairingId: String) async throws {
        let csrf = await fetchCSRFToken()
        let body = try JSONSerialization.data(withJSONObject: ["pairing_id": pairingId])
        _ = try await session.data(
            for: makeRequest(path: "/api/pin", method: "DELETE", body: body, csrfToken: csrf)
        )
    }

    public func listPairedClients() async throws -> [EngineClientRecord] {
        struct ClientsResponse: Decodable {
            let status: Bool?
            let named_certs: [EngineClientRecord]?
        }
        let (data, resp) = try await session.data(for: makeRequest(path: "/api/clients/list"))
        guard let http = resp as? HTTPURLResponse else { throw EngineAPIError.invalidResponse }
        if http.statusCode == 401 { throw EngineAPIError.unauthorized }
        guard http.statusCode == 200 else { throw EngineAPIError.httpError(http.statusCode) }
        return try JSONDecoder().decode(ClientsResponse.self, from: data).named_certs ?? []
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
