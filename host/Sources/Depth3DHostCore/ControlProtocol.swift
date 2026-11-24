import Foundation

/// Minimal HTTP/1.1 request representation shared by the control HTTPS server and WebDAV server.
public struct HTTPRequest: Equatable, Sendable {
    public let method: String
    public let rawTarget: String
    public let path: String
    public let query: String?
    public let headers: [String: String] // lowercase keys
    public let body: Data
    public let peerCertificateDER: Data?
    public let remoteAddress: String

    public init(
        method: String,
        rawTarget: String,
        headers: [String: String] = [:],
        body: Data = Data(),
        peerCertificateDER: Data? = nil,
        remoteAddress: String = "127.0.0.1"
    ) {
        self.method = method.uppercased()
        self.rawTarget = rawTarget
        if let qIdx = rawTarget.firstIndex(of: "?") {
            self.path = String(rawTarget[..<qIdx])
            self.query = String(rawTarget[rawTarget.index(after: qIdx)...])
        } else {
            self.path = rawTarget
            self.query = nil
        }
        var lower: [String: String] = [:]
        for (k, v) in headers {
            lower[k.lowercased()] = v
        }
        self.headers = lower
        self.body = body
        self.peerCertificateDER = peerCertificateDER
        self.remoteAddress = IPAddressNormalizer.normalize(remoteAddress)
    }

    public func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }

    public var peerCertificateFingerprintSHA256: String? {
        guard let der = peerCertificateDER, !der.isEmpty else { return nil }
        return TLSIdentityManager.sha256Hex(der)
    }
}

/// Minimal HTTP/1.1 response representation.
public struct HTTPResponse: Equatable, Sendable {
    public let statusCode: Int
    public let reasonPhrase: String
    public var headers: [(String, String)]
    public let body: Data

    public init(
        statusCode: Int,
        reasonPhrase: String? = nil,
        headers: [(String, String)] = [],
        body: Data = Data()
    ) {
        self.statusCode = statusCode
        self.reasonPhrase = reasonPhrase ?? HTTPResponse.defaultReason(for: statusCode)
        self.headers = headers
        self.body = body
    }

    public static func == (lhs: HTTPResponse, rhs: HTTPResponse) -> Bool {
        guard lhs.statusCode == rhs.statusCode,
              lhs.reasonPhrase == rhs.reasonPhrase,
              lhs.body == rhs.body,
              lhs.headers.count == rhs.headers.count
        else { return false }
        for (l, r) in zip(lhs.headers, rhs.headers) {
            if l.0 != r.0 || l.1 != r.1 { return false }
        }
        return true
    }

    public static func json(_ statusCode: Int, object: Any, extraHeaders: [(String, String)] = []) -> HTTPResponse {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
        return HTTPResponse(
            statusCode: statusCode,
            headers: [("Content-Type", "application/json; charset=utf-8")] + extraHeaders,
            body: data
        )
    }

    public static func jsonEncodable<T: Encodable>(_ statusCode: Int, _ value: T) -> HTTPResponse {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        let data = (try? enc.encode(value)) ?? Data("{}".utf8)
        return HTTPResponse(
            statusCode: statusCode,
            headers: [("Content-Type", "application/json; charset=utf-8")],
            body: data
        )
    }

    public func serialize(includeBody: Bool = true) -> Data {
        var out = "HTTP/1.1 \(statusCode) \(reasonPhrase)\r\n"
        var hasContentLength = false
        var hasConnection = false
        for (k, v) in headers {
            let lower = k.lowercased()
            if lower == "content-length" { hasContentLength = true }
            if lower == "connection" { hasConnection = true }
            out += "\(k): \(v)\r\n"
        }
        if !hasContentLength {
            out += "Content-Length: \(body.count)\r\n"
        }
        if !hasConnection {
            out += "Connection: close\r\n"
        }
        out += "\r\n"
        var data = Data(out.utf8)
        if includeBody {
            data.append(body)
        }
        return data
    }

    public static func defaultReason(for code: Int) -> String {
        switch code {
        case 200: return "OK"
        case 206: return "Partial Content"
        case 207: return "Multi-Status"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 408: return "Request Timeout"
        case 409: return "Conflict"
        case 410: return "Gone"
        case 413: return "Payload Too Large"
        case 416: return "Range Not Satisfiable"
        case 426: return "Upgrade Required"
        case 429: return "Too Many Requests"
        case 500: return "Internal Server Error"
        case 502: return "Bad Gateway"
        case 503: return "Service Unavailable"
        default: return "Status \(code)"
        }
    }
}

/// Parses an HTTP/1.1 request head (and validates Content-Length).
public enum HTTPParser {
    public struct ParsedHead: Sendable {
        public let method: String
        public let target: String
        public let headers: [String: String]
        public let contentLength: Int
        public let bodyStartIndex: Int
    }

    public enum ParseError: Error, Equatable {
        case malformedRequestLine
        case headersTooLarge
        case payloadTooLarge
    }

    public static let maxHeaderBytes = 32 * 1024

    public static func parseHead(from buffer: Data, maxBodyBytes: Int = 256 * 1024) throws -> ParsedHead? {
        let delimiter = Data("\r\n\r\n".utf8)
        guard let range = buffer.range(of: delimiter) else {
            if buffer.count > maxHeaderBytes {
                throw ParseError.headersTooLarge
            }
            return nil
        }

        let headData = buffer.subdata(in: 0..<range.lowerBound)
        guard let headString = String(data: headData, encoding: .utf8) else {
            throw ParseError.malformedRequestLine
        }
        let lines = headString.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            throw ParseError.malformedRequestLine
        }
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 2 else {
            throw ParseError.malformedRequestLine
        }
        let method = String(parts[0]).uppercased()
        let target = String(parts[1])

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = String(line[..<colon]).trimmingCharacters(in: .whitespaces).lowercased()
            let val = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            if !key.isEmpty {
                headers[key] = val
            }
        }

        let contentLength = headers["content-length"].flatMap { Int($0) } ?? 0
        if contentLength < 0 || contentLength > maxBodyBytes {
            throw ParseError.payloadTooLarge
        }

        return ParsedHead(
            method: method,
            target: target,
            headers: headers,
            contentLength: contentLength,
            bodyStartIndex: range.upperBound
        )
    }
}

// MARK: - Protocol v1 DTOs (`docs/specs/host-protocol.md`)

public struct HostInfoResponse: Codable, Equatable, Sendable {
    public struct StreamStatus: Codable, Equatable, Sendable {
        public let ready: Bool
        public let reason: String?

        public init(ready: Bool, reason: String?) {
            self.ready = ready
            self.reason = reason
        }
    }

    public struct MediaStatus: Codable, Equatable, Sendable {
        public let port: Int

        public init(port: Int) {
            self.port = port
        }
    }

    public let v: Int
    public let name: String
    public let id: String
    public let os: String
    public let stream: StreamStatus
    public let media: MediaStatus?

    public init(v: Int = 1, name: String, id: String, os: String, stream: StreamStatus, media: MediaStatus?) {
        self.v = v
        self.name = name
        self.id = id
        self.os = os
        self.stream = stream
        self.media = media
    }
}

public struct PairRequestPayload: Codable, Equatable, Sendable {
    public let device: String
    public let deviceId: String
    public let pin: String

    public init(device: String, deviceId: String, pin: String) {
        self.device = device
        self.deviceId = deviceId
        self.pin = pin
    }
}

public struct PairApprovedResponse: Codable, Equatable, Sendable {
    public let ok: Bool
    /// `"gamestream"` (headset runs GameStream pairing next) or `"done"` (cert already uniquely enabled in engine).
    public let next: String
    public let ticket: String

    public init(ok: Bool = true, next: String, ticket: String) {
        self.ok = ok
        self.next = next
        self.ticket = ticket
    }
}

public struct PairCompleteRequestPayload: Codable, Equatable, Sendable {
    public let ticket: String
    public let deviceId: String?
    public let device: String?

    public init(ticket: String, deviceId: String? = nil, device: String? = nil) {
        self.ticket = ticket
        self.deviceId = deviceId
        self.device = device
    }
}

public struct PairCompleteResponse: Codable, Equatable, Sendable {
    public let ok: Bool
    public let media: HostInfoResponse.MediaStatus?

    public init(ok: Bool = true, media: HostInfoResponse.MediaStatus?) {
        self.ok = ok
        self.media = media
    }
}

public struct MediaCredentialsResponse: Codable, Equatable, Sendable {
    public struct WebDAVInfo: Codable, Equatable, Sendable {
        public let port: Int
        public let user: String
        public let pass: String

        public init(port: Int, user: String, pass: String) {
            self.port = port
            self.user = user
            self.pass = pass
        }
    }

    public let webdav: WebDAVInfo?

    public init(webdav: WebDAVInfo?) {
        self.webdav = webdav
    }
}

public enum PairTicketIssueResult: Equatable, Sendable {
    case issued(next: String, ticket: String)
    case streamNotReady(reason: String)
}

public enum PairCompleteOutcome: Equatable, Sendable {
    case completed(mediaPort: UInt16?)
    case notPaired
    case ticketExpired
    case sourceMismatch
    case streamNotReady(reason: String)
}

/// Snapshot of current host runtime status supplied to `ControlProtocolRouter`.
public struct ControlProtocolSnapshot: Sendable {
    public let hostName: String
    public let hostId: String
    public let osDescription: String
    public let streamReady: Bool
    public let streamNotReadyReason: String?
    public let mediaPort: UInt16?
    public let webDAVCredentials: WebDAVCredentials?

    public init(
        hostName: String,
        hostId: String,
        osDescription: String,
        streamReady: Bool,
        streamNotReadyReason: String?,
        mediaPort: UInt16?,
        webDAVCredentials: WebDAVCredentials?
    ) {
        self.hostName = hostName
        self.hostId = hostId
        self.osDescription = osDescription
        self.streamReady = streamReady
        self.streamNotReadyReason = streamNotReadyReason
        self.mediaPort = mediaPort
        self.webDAVCredentials = webDAVCredentials
    }
}

/// Delegate supplying live state, certificate authorization, and two-step pairing lifecycle
/// operations to `ControlProtocolRouter`.
public protocol ControlProtocolDelegate: Sendable {
    func currentSnapshot() async -> ControlProtocolSnapshot
    func isKnownPairedCertificate(fingerprintSHA256: String) async -> Bool
    func authorizeMediaClient(peerCertFingerprintSHA256: String) async -> Bool
    func knownBoundCertFingerprints(forDeviceId deviceId: String) async -> Set<String>
    func issueApprovedPairingTicket(
        deviceName: String,
        deviceId: String,
        pin: String,
        clientAddress: String,
        peerCertFingerprintSHA256: String?,
        autoAllowFuture: Bool,
        approvedViaAutoAllow: Bool
    ) async -> PairTicketIssueResult
    func completePairing(
        ticket: String,
        deviceName: String?,
        deviceId: String?,
        clientAddress: String,
        peerCertFingerprintSHA256: String
    ) async -> PairCompleteOutcome
    func cancelPendingEnginePairings(forClientAddress clientAddress: String) async
}

public extension ControlProtocolDelegate {
    func isKnownPairedCertificate(fingerprintSHA256: String) async -> Bool { false }
    func knownBoundCertFingerprints(forDeviceId deviceId: String) async -> Set<String> { [] }
    func cancelPendingEnginePairings(forClientAddress clientAddress: String) async {}
}

/// Pure, unit-testable HTTP router implementing `docs/specs/host-protocol.md` (v1).
public final class ControlProtocolRouter: Sendable {
    public static let supportedVersion = 1

    private let pairingCoordinator: PairingCoordinator
    private let delegate: ControlProtocolDelegate

    public init(pairingCoordinator: PairingCoordinator, delegate: ControlProtocolDelegate) {
        self.pairingCoordinator = pairingCoordinator
        self.delegate = delegate
    }

    /// Used by `ControlHTTPSServer` so ONLY genuinely paired client certificates (NOT arbitrary
    /// attacker-generated self-signed certs) bypass unauthenticated connection rate limiting.
    public func isKnownPairedCertificate(fingerprintSHA256: String) async -> Bool {
        await delegate.isKnownPairedCertificate(fingerprintSHA256: fingerprintSHA256)
    }

    public func handle(_ request: HTTPRequest) async -> HTTPResponse {
        if let headerVersion = request.header("x-depth3d-protocol-version"),
           headerVersion != String(Self.supportedVersion)
        {
            return upgradeRequiredResponse()
        }
        if let pathVersion = Self.extractVersion(fromPath: request.path),
           pathVersion != Self.supportedVersion
        {
            return upgradeRequiredResponse()
        }

        switch request.path {
        case "/depth3d/v1/info":
            guard request.method == "GET" else {
                return HTTPResponse(statusCode: 405, headers: [("Allow", "GET")])
            }
            return await handleInfo()

        case "/depth3d/v1/pair":
            guard request.method == "POST" else {
                return HTTPResponse(statusCode: 405, headers: [("Allow", "POST")])
            }
            return await handlePair(request)

        case "/depth3d/v1/pair/complete":
            guard request.method == "POST" else {
                return HTTPResponse(statusCode: 405, headers: [("Allow", "POST")])
            }
            return await handlePairComplete(request)

        case "/depth3d/v1/media":
            guard request.method == "GET" else {
                return HTTPResponse(statusCode: 405, headers: [("Allow", "GET")])
            }
            return await handleMedia(request)

        default:
            return HTTPResponse.json(404, object: ["error": "not_found"])
        }
    }

    private func handleInfo() async -> HTTPResponse {
        let snap = await delegate.currentSnapshot()
        let fallbackReason = HostStrings.string("protocol.stream.unavailable")
        let response = HostInfoResponse(
            v: Self.supportedVersion,
            name: snap.hostName,
            id: snap.hostId,
            os: snap.osDescription,
            stream: .init(
                ready: snap.streamReady,
                reason: snap.streamReady ? nil : (snap.streamNotReadyReason ?? fallbackReason)
            ),
            media: snap.mediaPort.map { .init(port: Int($0)) }
        )
        return encodeInfoPreservingNulls(statusCode: 200, response)
    }

    private func handlePair(_ request: HTTPRequest) async -> HTTPResponse {
        guard let payload = try? JSONDecoder().decode(PairRequestPayload.self, from: request.body) else {
            return HTTPResponse.json(400, object: ["error": "invalid_request"])
        }
        let device = payload.device.trimmingCharacters(in: .whitespacesAndNewlines)
        let deviceId = payload.deviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        let pin = payload.pin.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !device.isEmpty, !deviceId.isEmpty, Self.isValidPIN(pin) else {
            return HTTPResponse.json(400, object: ["error": "invalid_request"])
        }

        let preflight = await delegate.currentSnapshot()
        guard preflight.streamReady else {
            return HTTPResponse.json(503, object: [
                "error": "stream_not_ready",
                "reason": preflight.streamNotReadyReason ?? HostStrings.string("protocol.stream.unavailable")
            ])
        }

        let knownBoundFPs = await delegate.knownBoundCertFingerprints(forDeviceId: deviceId)
        let decision = await pairingCoordinator.requestApproval(
            deviceName: device,
            deviceId: deviceId,
            clientAddress: request.remoteAddress,
            peerCertFingerprintSHA256: request.peerCertificateFingerprintSHA256,
            knownBoundFingerprintsForDeviceId: knownBoundFPs
        )

        switch decision {
        case .approved(let rememberAutoAllow):
            return await issueTicketResponse(
                device: device,
                deviceId: deviceId,
                pin: pin,
                request: request,
                autoAllowFuture: rememberAutoAllow,
                approvedViaAutoAllow: false
            )

        case .autoApproved:
            return await issueTicketResponse(
                device: device,
                deviceId: deviceId,
                pin: pin,
                request: request,
                autoAllowFuture: true,
                approvedViaAutoAllow: true
            )

        case .rejected:
            await delegate.cancelPendingEnginePairings(forClientAddress: request.remoteAddress)
            return HTTPResponse.json(403, object: ["error": "rejected"])

        case .timedOut:
            await delegate.cancelPendingEnginePairings(forClientAddress: request.remoteAddress)
            return HTTPResponse.json(408, object: ["error": "timeout"])

        case .busy(let retryAfterSec):
            return HTTPResponse.json(
                429,
                object: ["error": "busy", "retryAfterSec": retryAfterSec],
                extraHeaders: [("Retry-After", String(retryAfterSec))]
            )

        case .cooldown(let retryAfterSec):
            return HTTPResponse.json(
                429,
                object: ["error": "cooldown", "retryAfterSec": retryAfterSec],
                extraHeaders: [("Retry-After", String(retryAfterSec))]
            )

        case .cancelled:
            await delegate.cancelPendingEnginePairings(forClientAddress: request.remoteAddress)
            return HTTPResponse.json(403, object: ["error": "rejected"])
        }
    }

    private func issueTicketResponse(
        device: String,
        deviceId: String,
        pin: String,
        request: HTTPRequest,
        autoAllowFuture: Bool,
        approvedViaAutoAllow: Bool
    ) async -> HTTPResponse {
        let result = await delegate.issueApprovedPairingTicket(
            deviceName: device,
            deviceId: deviceId,
            pin: pin,
            clientAddress: request.remoteAddress,
            peerCertFingerprintSHA256: request.peerCertificateFingerprintSHA256,
            autoAllowFuture: autoAllowFuture,
            approvedViaAutoAllow: approvedViaAutoAllow
        )
        switch result {
        case .issued(let next, let ticket):
            return HTTPResponse.jsonEncodable(200, PairApprovedResponse(ok: true, next: next, ticket: ticket))
        case .streamNotReady(let reason):
            await delegate.cancelPendingEnginePairings(forClientAddress: request.remoteAddress)
            return HTTPResponse.json(503, object: ["error": "stream_not_ready", "reason": reason])
        }
    }

    private func handlePairComplete(_ request: HTTPRequest) async -> HTTPResponse {
        guard let certFP = request.peerCertificateFingerprintSHA256 else {
            return HTTPResponse.json(401, object: ["error": "client_cert_required"])
        }
        guard let payload = try? JSONDecoder().decode(PairCompleteRequestPayload.self, from: request.body) else {
            return HTTPResponse.json(400, object: ["error": "invalid_request"])
        }
        let ticket = payload.ticket.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !ticket.isEmpty else {
            return HTTPResponse.json(400, object: ["error": "invalid_request"])
        }

        let outcome = await delegate.completePairing(
            ticket: ticket,
            deviceName: payload.device?.trimmingCharacters(in: .whitespacesAndNewlines),
            deviceId: payload.deviceId?.trimmingCharacters(in: .whitespacesAndNewlines),
            clientAddress: request.remoteAddress,
            peerCertFingerprintSHA256: certFP
        )

        switch outcome {
        case .completed(let mediaPort):
            let mediaValue: Any = mediaPort.map { ["port": Int($0)] } ?? NSNull()
            return HTTPResponse.json(200, object: ["ok": true, "media": mediaValue])

        case .notPaired:
            return HTTPResponse.json(409, object: ["error": "not_paired"])

        case .ticketExpired:
            await delegate.cancelPendingEnginePairings(forClientAddress: request.remoteAddress)
            return HTTPResponse.json(410, object: ["error": "ticket_expired"])

        case .sourceMismatch:
            await delegate.cancelPendingEnginePairings(forClientAddress: request.remoteAddress)
            return HTTPResponse.json(403, object: ["error": "source_mismatch"])

        case .streamNotReady(let reason):
            await delegate.cancelPendingEnginePairings(forClientAddress: request.remoteAddress)
            return HTTPResponse.json(503, object: ["error": "stream_not_ready", "reason": reason])
        }
    }

    private func handleMedia(_ request: HTTPRequest) async -> HTTPResponse {
        guard let certFP = request.peerCertificateFingerprintSHA256 else {
            return HTTPResponse.json(401, object: ["error": "unauthorized"])
        }

        let allowed = await delegate.authorizeMediaClient(peerCertFingerprintSHA256: certFP)
        guard allowed else {
            return HTTPResponse.json(401, object: ["error": "unauthorized"])
        }

        let snap = await delegate.currentSnapshot()
        if let port = snap.mediaPort, let creds = snap.webDAVCredentials {
            let payload = MediaCredentialsResponse(
                webdav: .init(port: Int(port), user: creds.user, pass: creds.pass)
            )
            return HTTPResponse.jsonEncodable(200, payload)
        } else {
            return HTTPResponse.json(200, object: ["webdav": NSNull()])
        }
    }

    private func upgradeRequiredResponse() -> HTTPResponse {
        HTTPResponse.json(426, object: [
            "error": "version_incompatible",
            "supportedVersion": Self.supportedVersion,
            "message": HostStrings.string("protocol.version_incompatible")
        ])
    }

    private func encodeInfoPreservingNulls(statusCode: Int, _ info: HostInfoResponse) -> HTTPResponse {
        let streamDict: [String: Any] = [
            "ready": info.stream.ready,
            "reason": info.stream.reason ?? NSNull()
        ]
        let mediaValue: Any = info.media.map { ["port": $0.port] } ?? NSNull()
        let root: [String: Any] = [
            "v": info.v,
            "name": info.name,
            "id": info.id,
            "os": info.os,
            "stream": streamDict,
            "media": mediaValue
        ]
        return HTTPResponse.json(statusCode, object: root)
    }

    public static func isValidPIN(_ pin: String) -> Bool {
        pin.count == 4 && pin.unicodeScalars.allSatisfy { $0.value >= 48 && $0.value <= 57 }
    }

    public static func extractVersion(fromPath path: String) -> Int? {
        guard path.hasPrefix("/depth3d/v") else { return nil }
        let rest = path.dropFirst("/depth3d/v".count)
        let digits = rest.prefix { $0.isASCII && $0.isNumber }
        guard !digits.isEmpty else { return nil }
        let after = rest.dropFirst(digits.count)
        guard after.isEmpty || after.first == "/" else { return nil }
        return Int(digits)
    }
}
