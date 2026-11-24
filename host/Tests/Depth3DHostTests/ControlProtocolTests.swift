import Darwin
import Foundation
import XCTest
@testable import Depth3DHostCore

private actor MockControlDelegate: ControlProtocolDelegate {
    var snapshot: ControlProtocolSnapshot
    var authorizedClients: [String: String] = [:] // deviceId -> certFingerprintSHA256
    var approvedPairings: [(device: String, deviceId: String, pin: String, clientAddress: String, autoAllow: Bool)] = []
    var shouldAcceptEngineSubmit: Bool = true

    init(snapshot: ControlProtocolSnapshot) {
        self.snapshot = snapshot
    }

    func setAuthorized(deviceId: String, fingerprint: String) {
        authorizedClients[deviceId] = fingerprint.uppercased()
    }

    func setSnapshot(_ next: ControlProtocolSnapshot) {
        snapshot = next
    }

    func currentSnapshot() async -> ControlProtocolSnapshot {
        snapshot
    }

    func authorizeMediaClient(deviceId: String, peerCertFingerprintSHA256: String) async -> Bool {
        guard let expected = authorizedClients[deviceId] else { return false }
        return expected == peerCertFingerprintSHA256.uppercased()
    }

    func handleApprovedPairing(
        deviceName: String,
        deviceId: String,
        pin: String,
        clientAddress: String,
        autoAllowFuture: Bool
    ) async -> Bool {
        approvedPairings.append((deviceName, deviceId, pin, clientAddress, autoAllowFuture))
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

        // Not-ready state with localized reason and media disabled (null)
        let notReadySnap = ControlProtocolSnapshot(
            hostName: "书房 Mac",
            hostId: "11111111-2222-3333-4444-555555555555",
            osDescription: "macOS 15.1",
            streamReady: false,
            streamNotReadyReason: HostStrings.string("protocol.stream.no_screen_recording"),
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

    func testVersionMismatchReturns426WithLocalizedMessage() async throws {
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
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: respV2.body) as? [String: Any])
        XCTAssertEqual(json["message"] as? String, "请更新电脑上的 depth3d Host")

        let respHeader = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/info",
            headers: ["X-Depth3d-Protocol-Version": "2"]
        ))
        XCTAssertEqual(respHeader.statusCode, 426)
    }

    func testPairReturns503ImmediatelyWhenEngineNotReady() async {
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: false,
            streamNotReadyReason: HostStrings.string("protocol.stream.port_conflict"),
            mediaPort: nil, webDAVCredentials: nil
        )
        let coord = PairingCoordinator(timeout: 5.0)
        let router = ControlProtocolRouter(pairingCoordinator: coord, delegate: MockControlDelegate(snapshot: snap))

        let resp = await router.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-1\",\"pin\":\"1234\"}".utf8),
            remoteAddress: "192.168.1.40"
        ))
        XCTAssertEqual(resp.statusCode, 503)
        let prompts = await coord.pendingPrompts()
        XCTAssertTrue(prompts.isEmpty, "Must not display a pairing prompt when engine is unavailable")
    }

    /// Verifies cert-bound auto-allow, single active prompt serialization, IP recording,
    /// rejection/timeout cooldown, and that spoofed `deviceId` without matching TLS cert MUST prompt.
    func testPairingApprovalRejectionTimeoutCooldownAndCertBoundAutoAllow() async throws {
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: true, streamNotReadyReason: nil, mediaPort: nil, webDAVCredentials: nil
        )
        let delegate = MockControlDelegate(snapshot: snap)
        let coordinator = PairingCoordinator(timeout: 0.2, cooldownDuration: 2.0)
        let router = ControlProtocolRouter(pairingCoordinator: coordinator, delegate: delegate)

        // 1. Invalid PIN rejected with 400
        let badBody = Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-1\",\"pin\":\"12ab\"}".utf8)
        let badResp = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: badBody, remoteAddress: "192.168.1.10"))
        XCTAssertEqual(badResp.statusCode, 400)

        // 2. Timeout returns 408 and starts per-source cooldown (next immediate attempt -> 429)
        let timeoutBody = Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-timeout\",\"pin\":\"1234\"}".utf8)
        let timeoutResp = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: timeoutBody, remoteAddress: "192.168.1.11"))
        XCTAssertEqual(timeoutResp.statusCode, 408)

        let cooldownAfterTimeout = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: timeoutBody, remoteAddress: "192.168.1.11"))
        XCTAssertEqual(cooldownAfterTimeout.statusCode, 429)

        // 3. Single prompt enforcement + rejection cooldown
        let coord2 = PairingCoordinator(timeout: 5.0, cooldownDuration: 5.0)
        let router2 = ControlProtocolRouter(pairingCoordinator: coord2, delegate: delegate)

        let firstTask = Task {
            await router2.handle(HTTPRequest(
                method: "POST",
                rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"PICO A\",\"deviceId\":\"dev-rej\",\"pin\":\"4321\"}".utf8),
                remoteAddress: "::ffff:192.168.1.20"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)

        let activePrompts = await coord2.pendingPrompts()
        XCTAssertEqual(activePrompts.count, 1)
        XCTAssertEqual(activePrompts[0].clientAddress, "192.168.1.20")

        // Second concurrent pairing request from a different IP gets 429 busy (only 1 prompt on screen at a time)
        let concurrentResp = await router2.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO B\",\"deviceId\":\"dev-other\",\"pin\":\"1111\"}".utf8),
            remoteAddress: "192.168.1.21"
        ))
        XCTAssertEqual(concurrentResp.statusCode, 429)

        // Reject first request -> 403, then immediate retry from same IP -> 429 cooldown
        await coord2.reject(promptId: activePrompts[0].id)
        let rejResp = await firstTask.value
        XCTAssertEqual(rejResp.statusCode, 403)

        let spamResp = await router2.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO A\",\"deviceId\":\"dev-new\",\"pin\":\"4321\"}".utf8),
            remoteAddress: "192.168.1.20"
        ))
        XCTAssertEqual(spamResp.statusCode, 429)

        // 4. Certificate-bound auto-allow verification
        let certADer = Data("real-paired-headset-cert-A".utf8)
        let certAFp = TLSIdentityManager.sha256Hex(certADer)
        let certBDer = Data("attacker-impersonator-cert-B".utf8)

        let coord3 = PairingCoordinator(timeout: 0.25, cooldownDuration: 0.0)
        await coord3.setAutoAllowed(deviceId: "dev-ok", certFingerprintSHA256: certAFp)
        let router3 = ControlProtocolRouter(pairingCoordinator: coord3, delegate: delegate)

        // 4a. Same deviceId WITHOUT client cert MUST NOT auto-approve (prompts and times out -> 408)
        let spoofNoCert = await router3.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Fake PICO\",\"deviceId\":\"dev-ok\",\"pin\":\"5678\"}".utf8),
            peerCertificateDER: nil,
            remoteAddress: "192.168.1.30"
        ))
        XCTAssertEqual(spoofNoCert.statusCode, 408, "deviceId alone without TLS client cert must never auto-approve")

        // 4b. Same deviceId WITH wrong client cert MUST NOT auto-approve (prompts and times out -> 408)
        let spoofWrongCert = await router3.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Fake PICO\",\"deviceId\":\"dev-ok\",\"pin\":\"5678\"}".utf8),
            peerCertificateDER: certBDer,
            remoteAddress: "192.168.1.31"
        ))
        XCTAssertEqual(spoofWrongCert.statusCode, 408, "Mismatched TLS client cert must never auto-approve")

        // 4c. Same deviceId WITH exact bound TLS client cert auto-approves immediately -> 200
        let legitimate = await router3.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-ok\",\"pin\":\"9999\"}".utf8),
            peerCertificateDER: certADer,
            remoteAddress: "192.168.1.32"
        ))
        XCTAssertEqual(legitimate.statusCode, 200)
        let approved = await delegate.approvedPairings
        XCTAssertEqual(approved.last?.pin, "9999")
        XCTAssertEqual(approved.last?.clientAddress, "192.168.1.32")
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

        let r1 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media"))
        XCTAssertEqual(r1.statusCode, 401)

        let r2 = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
            headers: ["X-Depth3d-Device": "pico-01"]
        ))
        XCTAssertEqual(r2.statusCode, 401)

        let r3 = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
            headers: ["X-Depth3d-Device": "pico-01"],
            peerCertificateDER: Data("other-cert".utf8)
        ))
        XCTAssertEqual(r3.statusCode, 401)

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

    func testBonjourTXTRecordAndErrorClassification() {
        let readyTXT = HostBonjourTXTRecord.makeDictionary(
            name: "书房 Mac", id: "UUID-1", gsPort: 47989, mediaPort: 8890, mediaRevision: 2
        )
        XCTAssertEqual(String(data: readyTXT["v"] ?? Data(), encoding: .utf8), "1")
        XCTAssertEqual(String(data: readyTXT["name"] ?? Data(), encoding: .utf8), "书房 Mac")
        XCTAssertEqual(String(data: readyTXT["id"] ?? Data(), encoding: .utf8), "UUID-1")
        XCTAssertEqual(String(data: readyTXT["pair"] ?? Data(), encoding: .utf8), "auto")
        XCTAssertEqual(String(data: readyTXT["gs"] ?? Data(), encoding: .utf8), "47989")
        XCTAssertEqual(String(data: readyTXT["media"] ?? Data(), encoding: .utf8), "8890")
        XCTAssertEqual(String(data: readyTXT["mrev"] ?? Data(), encoding: .utf8), "2")

        // When engine is NOT ready, `gs` must be omitted from Bonjour TXT
        let engineDownTXT = HostBonjourTXTRecord.makeDictionary(
            name: "Mac", id: "UUID-2", gsPort: nil, mediaPort: nil
        )
        XCTAssertNil(engineDownTXT["gs"])
        XCTAssertNil(engineDownTXT["media"])
        XCTAssertNil(engineDownTXT["mrev"])

        // Error classification: only policy denial codes map to .permissionDenied
        XCTAssertEqual(HostBonjourTXTRecord.classifyPublishError(code: -65570), .permissionDenied)
        XCTAssertEqual(HostBonjourTXTRecord.classifyPublishError(code: -72008), .permissionDenied)
        XCTAssertEqual(HostBonjourTXTRecord.classifyPublishError(code: -72001), .failed(-72001))
        XCTAssertEqual(HostBonjourTXTRecord.classifyPublishError(code: -72007), .failed(-72007))
    }

    func testControlHTTPSServerLiveMTLSNonBlockingPairAndRateLimit() async throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-tls-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let tlsManager = TLSIdentityManager(tlsDirectory: tmpDir.appendingPathComponent("tls"))
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "depth3d Test Host")

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

        let coord = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0)
        let router = ControlProtocolRouter(pairingCoordinator: coord, delegate: delegate)
        let server = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 16,
            unauthRateLimitPerWindow: 20
        )
        let port = try server.start(port: 0, fallbackToEphemeral: false)
        defer { server.stop() }

        // 1. Live /info without client cert
        let (code1, body1) = runCurl(["-sk", "--max-time", "5", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(code1, 200)
        XCTAssertTrue(body1.contains("\"v\":1"))
        XCTAssertTrue(body1.contains("\"ready\":true"))

        // 2. Live /media without client cert -> 401
        let (code2, _) = runCurl([
            "-sk", "--max-time", "5",
            "-H", "X-Depth3d-Device: pico-live",
            "https://127.0.0.1:\(port)/depth3d/v1/media"
        ])
        XCTAssertEqual(code2, 401)

        // 3. Live /media WITH paired client cert -> 200 + credentials
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

        // 4. Non-blocking /pair wait: while a /pair waits on user confirmation, concurrent /info
        //    requests must still complete promptly without blocking worker threads!
        let pairTask = Task.detached { [self] () -> (Int, String) in
            self.runCurl([
                "-sk", "--max-time", "5",
                "-X", "POST",
                "-H", "Content-Type: application/json",
                "-d", "{\"device\":\"PICO 4\",\"deviceId\":\"nonblock\",\"pin\":\"1234\"}",
                "https://127.0.0.1:\(port)/depth3d/v1/pair"
            ])
        }
        try await Task.sleep(nanoseconds: 250_000_000)
        let prompts = await coord.pendingPrompts()
        XCTAssertEqual(prompts.count, 1)

        let (infoWhileWaiting, _) = runCurl(["-sk", "--max-time", "2", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(infoWhileWaiting, 200, "/info must stay responsive while /pair waits for user")

        await coord.approve(promptId: prompts[0].id, rememberAutoAllow: false)
        let (pairCode, _) = await pairTask.value
        XCTAssertEqual(pairCode, 200)

        // 5. Unauthenticated rate limiting on ControlHTTPSServer
        let rateLimitedServer = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 8,
            unauthRateLimitPerWindow: 2,
            unauthRateWindowSeconds: 60.0
        )
        let rlPort = try rateLimitedServer.start(port: 0, fallbackToEphemeral: false)
        defer { rateLimitedServer.stop() }

        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        XCTAssertEqual(
            runCurl(["-sk", "--max-time", "3", "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0,
            429,
            "Unauthenticated requests exceeding per-IP limit must receive 429"
        )
    }

    private func runCurl(_ args: [String]) -> (Int, String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        p.arguments = ["--noproxy", "*"] + args + ["-w", "\n%{http_code}"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try? p.run()
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        let raw = String(data: data, encoding: .utf8) ?? ""
        let lines = raw.components(separatedBy: "\n")
        let code = Int(lines.last ?? "") ?? 0
        let body = lines.dropLast().joined(separator: "\n")
        return (code, body)
    }
}
