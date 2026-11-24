import Foundation
import XCTest
@testable import Depth3DHostCore

private actor MockControlDelegate: ControlProtocolDelegate {
    var snapshot: ControlProtocolSnapshot
    var authorizedClients: [String: String] = [:] // deviceId -> certFingerprintSHA256
    var approvedPairings: [(device: String, deviceId: String, pin: String, autoAllow: Bool)] = []
    var shouldAcceptEngineSubmit: Bool = true

    init(snapshot: ControlProtocolSnapshot) {
        self.snapshot = snapshot
    }

    func setAuthorized(deviceId: String, fingerprint: String) {
        authorizedClients[deviceId] = fingerprint.uppercased()
    }

    func currentSnapshot() async -> ControlProtocolSnapshot {
        snapshot
    }

    func authorizeMediaClient(deviceId: String, peerCertFingerprintSHA256: String) async -> Bool {
        guard let expected = authorizedClients[deviceId] else { return false }
        return expected == peerCertFingerprintSHA256.uppercased()
    }

    func handleApprovedPairing(deviceName: String, deviceId: String, pin: String, autoAllowFuture: Bool) async -> Bool {
        approvedPairings.append((deviceName, deviceId, pin, autoAllowFuture))
        return shouldAcceptEngineSubmit
    }
}

final class ControlProtocolTests: XCTestCase {
    func testHTTPParserParsesHeadersAndContentLength() throws {
        let raw = Data("POST /depth3d/v1/pair?foo=bar HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 12\r\n\r\n{\"pin\":\"12\"}".utf8)
        let head = try XCTUnwrap(try HTTPParser.parseHead(from: raw))
        XCTAssertEqual(head.method, "POST")
        XCTAssertEqual(head.target, "/depth3d/v1/pair?foo=bar")
        XCTAssertEqual(head.headers["content-type"], "application/json")
        XCTAssertEqual(head.contentLength, 12)
        let body = raw.subdata(in: head.bodyStartIndex..<(head.bodyStartIndex + head.contentLength))
        XCTAssertEqual(String(data: body, encoding: .utf8), "{\"pin\":\"12\"}")
    }

    func testHTTPParserRejectsMalformedAndOversized() {
        XCTAssertThrowsError(try HTTPParser.parseHead(from: Data("INVALID\r\n\r\n".utf8)))
        let huge = Data("POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".utf8)
        XCTAssertThrowsError(try HTTPParser.parseHead(from: huge, maxBodyBytes: 1024))
    }

    func testInfoEndpointReadyAndNotReadyJSONFormat() async throws {
        let readySnap = ControlProtocolSnapshot(
            hostName: "书房 Mac",
            hostId: "11111111-2222-3333-4444-555555555555",
            osDescription: "macOS 15.1",
            streamReady: true,
            streamNotReadyReason: nil,
            mediaPort: 8890,
            webDAVCredentials: WebDAVCredentials(user: "depth3d", pass: "secret123")
        )
        let delegate = MockControlDelegate(snapshot: readySnap)
        let router = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: delegate)

        let resp = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/info"))
        XCTAssertEqual(resp.statusCode, 200)

        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: resp.body) as? [String: Any])
        XCTAssertEqual(json["v"] as? Int, 1)
        XCTAssertEqual(json["name"] as? String, "书房 Mac")
        XCTAssertEqual(json["id"] as? String, "11111111-2222-3333-4444-555555555555")
        XCTAssertEqual(json["os"] as? String, "macOS 15.1")

        let stream = try XCTUnwrap(json["stream"] as? [String: Any])
        XCTAssertEqual(stream["ready"] as? Bool, true)
        XCTAssertTrue(stream["reason"] is NSNull, "reason must be explicit JSON null when stream.ready is true")

        let media = try XCTUnwrap(json["media"] as? [String: Any])
        XCTAssertEqual(media["port"] as? Int, 8890)

        // Now verify not-ready state with media disabled (null)
        let notReadySnap = ControlProtocolSnapshot(
            hostName: "书房 Mac",
            hostId: "11111111-2222-3333-4444-555555555555",
            osDescription: "macOS 15.1",
            streamReady: false,
            streamNotReadyReason: "还没有允许屏幕录制",
            mediaPort: nil,
            webDAVCredentials: nil
        )
        let delegate2 = MockControlDelegate(snapshot: notReadySnap)
        let router2 = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: delegate2)
        let resp2 = await router2.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/info"))
        let json2 = try XCTUnwrap(JSONSerialization.jsonObject(with: resp2.body) as? [String: Any])
        let stream2 = try XCTUnwrap(json2["stream"] as? [String: Any])
        XCTAssertEqual(stream2["ready"] as? Bool, false)
        XCTAssertEqual(stream2["reason"] as? String, "还没有允许屏幕录制")
        XCTAssertTrue(json2["media"] is NSNull)
    }

    func testVersionMismatchReturns426() async {
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: true, streamNotReadyReason: nil, mediaPort: nil, webDAVCredentials: nil
        )
        let router = ControlProtocolRouter(
            pairingCoordinator: PairingCoordinator(),
            delegate: MockControlDelegate(snapshot: snap)
        )

        let respV2 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v2/info"))
        XCTAssertEqual(respV2.statusCode, 426)

        let respHeader = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/info",
            headers: ["X-Depth3d-Protocol-Version": "2"]
        ))
        XCTAssertEqual(respHeader.statusCode, 426)
    }

    func testPairingApprovalRejectionTimeoutAndAutoAllow() async throws {
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: true, streamNotReadyReason: nil, mediaPort: nil, webDAVCredentials: nil
        )
        let delegate = MockControlDelegate(snapshot: snap)
        let coordinator = PairingCoordinator(timeout: 0.25)
        let router = ControlProtocolRouter(pairingCoordinator: coordinator, delegate: delegate)

        // 1. Invalid PIN rejected with 400
        let badBody = Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-1\",\"pin\":\"12ab\"}".utf8)
        let badResp = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: badBody))
        XCTAssertEqual(badResp.statusCode, 400)

        // 2. Timeout returns 408
        let validBody = Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-timeout\",\"pin\":\"1234\"}".utf8)
        let timeoutResp = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: validBody))
        XCTAssertEqual(timeoutResp.statusCode, 408)

        // 3. Rejection returns 403
        let rejectCoord = PairingCoordinator(timeout: 5.0)
        let rejectRouter = ControlProtocolRouter(pairingCoordinator: rejectCoord, delegate: delegate)
        let rejectTask = Task {
            await rejectRouter.handle(HTTPRequest(
                method: "POST",
                rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-rej\",\"pin\":\"4321\"}".utf8)
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let promptsRej = await rejectCoord.pendingPrompts()
        XCTAssertEqual(promptsRej.count, 1)
        await rejectCoord.reject(promptId: promptsRej[0].id)
        let rejResp = await rejectTask.value
        XCTAssertEqual(rejResp.statusCode, 403)

        // 4. Approval with rememberAutoAllow=true returns 200, then second request auto-approves immediately
        let approveTask = Task {
            await rejectRouter.handle(HTTPRequest(
                method: "POST",
                rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-ok\",\"pin\":\"5678\"}".utf8)
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let promptsOk = await rejectCoord.pendingPrompts()
        XCTAssertEqual(promptsOk.count, 1)
        await rejectCoord.approve(promptId: promptsOk[0].id, rememberAutoAllow: true)
        let okResp = await approveTask.value
        XCTAssertEqual(okResp.statusCode, 200)

        let autoResp = await rejectRouter.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-ok\",\"pin\":\"9999\"}".utf8)
        ))
        XCTAssertEqual(autoResp.statusCode, 200)
        let approved = await delegate.approvedPairings
        XCTAssertEqual(approved.count, 2)
        XCTAssertEqual(approved[0].pin, "5678")
        XCTAssertEqual(approved[1].pin, "9999")
        XCTAssertTrue(approved[1].autoAllow)
    }

    func testMediaEndpointRequiresPairedDeviceAndClientCert() async throws {
        let creds = WebDAVCredentials(user: "depth3d", pass: "rndpass99")
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: true, streamNotReadyReason: nil, mediaPort: 8890, webDAVCredentials: creds
        )
        let delegate = MockControlDelegate(snapshot: snap)
        let clientCertDER = Data("fake-client-cert-der-bytes".utf8)
        let fp = TLSIdentityManager.sha256Hex(clientCertDER)
        await delegate.setAuthorized(deviceId: "pico-01", fingerprint: fp)

        let router = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: delegate)

        // Missing header & cert -> 401
        let r1 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media"))
        XCTAssertEqual(r1.statusCode, 401)

        // Header without client cert -> 401
        let r2 = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
            headers: ["X-Depth3d-Device": "pico-01"]
        ))
        XCTAssertEqual(r2.statusCode, 401)

        // Wrong cert -> 401
        let r3 = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
            headers: ["X-Depth3d-Device": "pico-01"],
            peerCertificateDER: Data("other-cert".utf8)
        ))
        XCTAssertEqual(r3.statusCode, 401)

        // Valid deviceId + matching client cert -> 200 with WebDAV credentials
        let r4 = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
            headers: ["X-Depth3d-Device": "pico-01"],
            peerCertificateDER: clientCertDER
        ))
        XCTAssertEqual(r4.statusCode, 200)
        let decoded = try JSONDecoder().decode(MediaCredentialsResponse.self, from: r4.body)
        XCTAssertEqual(decoded.webdav?.port, 8890)
        XCTAssertEqual(decoded.webdav?.user, "depth3d")
        XCTAssertEqual(decoded.webdav?.pass, "rndpass99")
    }

    func testBonjourTXTRecordKeys() {
        let withMedia = HostBonjourTXTRecord.makeDictionary(name: "书房 Mac", id: "UUID-1", gsPort: 47989, mediaPort: 8890)
        XCTAssertEqual(String(data: withMedia["v"] ?? Data(), encoding: .utf8), "1")
        XCTAssertEqual(String(data: withMedia["name"] ?? Data(), encoding: .utf8), "书房 Mac")
        XCTAssertEqual(String(data: withMedia["id"] ?? Data(), encoding: .utf8), "UUID-1")
        XCTAssertEqual(String(data: withMedia["pair"] ?? Data(), encoding: .utf8), "auto")
        XCTAssertEqual(String(data: withMedia["gs"] ?? Data(), encoding: .utf8), "47989")
        XCTAssertEqual(String(data: withMedia["media"] ?? Data(), encoding: .utf8), "8890")

        let withoutMedia = HostBonjourTXTRecord.makeDictionary(name: "Mac", id: "UUID-2", gsPort: 57989, mediaPort: nil)
        XCTAssertNil(withoutMedia["media"])
        XCTAssertEqual(String(data: withoutMedia["gs"] ?? Data(), encoding: .utf8), "57989")
    }

    func testControlHTTPSServerLiveWithAndWithoutClientCert() async throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-tls-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let tlsManager = TLSIdentityManager(tlsDirectory: tmpDir.appendingPathComponent("tls"))
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "depth3d Test Host")

        // Generate a client certificate via /usr/bin/openssl to verify real mTLS.
        let clientKey = tmpDir.appendingPathComponent("client.key")
        let clientCert = tmpDir.appendingPathComponent("client.crt")
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        proc.arguments = [
            "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", clientKey.path, "-out", clientCert.path,
            "-days", "1", "-nodes", "-subj", "/CN=PICO4"
        ]
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        try proc.run()
        proc.waitUntilExit()
        XCTAssertEqual(proc.terminationStatus, 0)

        let clientPEM = try String(contentsOf: clientCert, encoding: .utf8)
        let clientFP = try XCTUnwrap(TLSIdentityManager.sha256Hex(fromPEM: clientPEM))

        let snap = ControlProtocolSnapshot(
            hostName: "测试 Mac",
            hostId: "HOST-UUID",
            osDescription: "macOS 15.1",
            streamReady: true,
            streamNotReadyReason: nil,
            mediaPort: 8899,
            webDAVCredentials: WebDAVCredentials(user: "depth3d", pass: "livepass")
        )
        let delegate = MockControlDelegate(snapshot: snap)
        await delegate.setAuthorized(deviceId: "pico-live", fingerprint: clientFP)

        let router = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: delegate)
        let server = ControlHTTPSServer(identity: serverId.secIdentity, router: router)
        let port = try server.start(port: 0, fallbackToEphemeral: false)
        defer { server.stop() }

        // 1. Call GET /depth3d/v1/info WITHOUT client cert
        let (code1, body1) = runCurl(["-sk", "--max-time", "5", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(code1, 200)
        XCTAssertTrue(body1.contains("\"v\":1"))
        XCTAssertTrue(body1.contains("\"ready\":true"))

        // 2. Call GET /depth3d/v1/media WITHOUT client cert -> 401
        let (code2, _) = runCurl([
            "-sk", "--max-time", "5",
            "-H", "X-Depth3d-Device: pico-live",
            "https://127.0.0.1:\(port)/depth3d/v1/media"
        ])
        XCTAssertEqual(code2, 401)

        // 3. Call GET /depth3d/v1/media WITH paired client cert -> 200 + credentials
        let (code3, body3) = runCurl([
            "-sk", "--max-time", "5",
            "--cert", clientCert.path,
            "--key", clientKey.path,
            "-H", "X-Depth3d-Device: pico-live",
            "https://127.0.0.1:\(port)/depth3d/v1/media"
        ])
        XCTAssertEqual(code3, 200)
        XCTAssertTrue(body3.contains("\"pass\":\"livepass\""))
        XCTAssertTrue(body3.contains("\"port\":8899"))
    }

    private func runCurl(_ args: [String]) -> (Int, String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        p.arguments = ["--noproxy", "*"] + args + ["-w", "\n%{http_code}"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try? p.run()
        p.waitUntilExit()
        let raw = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        let lines = raw.components(separatedBy: "\n")
        let code = Int(lines.last ?? "") ?? 0
        let body = lines.dropLast().joined(separator: "\n")
        return (code, body)
    }
}
