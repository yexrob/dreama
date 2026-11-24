import CryptoKit
import Darwin
import Foundation
import Security
import XCTest
@testable import Depth3DHostCore

private actor MockControlDelegate: ControlProtocolDelegate {
    var snapshot: ControlProtocolSnapshot
    var authorizedClients: [String: String] = [:] // deviceId -> certFingerprintSHA256
    var knownBoundCerts: [String: String] = [:]   // deviceId -> certFingerprintSHA256
    var approvedPairings: [(device: String, deviceId: String, pin: String, clientAddress: String, autoAllow: Bool)] = []
    var shouldAcceptEngineSubmit: Bool = true
    var handledRequestCount: Int = 0

    init(snapshot: ControlProtocolSnapshot) {
        self.snapshot = snapshot
    }

    func setAuthorized(deviceId: String, fingerprint: String) {
        let up = fingerprint.uppercased()
        authorizedClients[deviceId] = up
        knownBoundCerts[deviceId] = up
    }

    func currentSnapshot() async -> ControlProtocolSnapshot {
        handledRequestCount += 1
        return snapshot
    }

    func authorizeMediaClient(deviceId: String, peerCertFingerprintSHA256: String) async -> Bool {
        handledRequestCount += 1
        guard let expected = authorizedClients[deviceId] else { return false }
        return expected == peerCertFingerprintSHA256.uppercased()
    }

    func knownBoundCertFingerprint(forDeviceId deviceId: String) async -> String? {
        knownBoundCerts[deviceId]
    }

    func handleApprovedPairing(
        deviceName: String,
        deviceId: String,
        pin: String,
        clientAddress: String,
        autoAllowFuture: Bool
    ) async -> Bool {
        handledRequestCount += 1
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
        XCTAssertTrue(stream["reason"] is NSNull)

        let media = try XCTUnwrap(json["media"] as? [String: Any])
        XCTAssertEqual(media["port"] as? Int, 8890)

        let notReadySnap = ControlProtocolSnapshot(
            hostName: "书房 Mac",
            hostId: "11111111-2222-3333-4444-555555555555",
            osDescription: "macOS 15.1",
            streamReady: false,
            streamNotReadyReason: HostStrings.string("protocol.stream.no_screen_recording"),
            mediaPort: nil,
            webDAVCredentials: nil
        )
        let router2 = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: MockControlDelegate(snapshot: notReadySnap))
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
        XCTAssertTrue(prompts.isEmpty)
    }

    /// Verifies cert-bound auto-allow, single active prompt serialization, IP-only cooldown (m5:
    /// spoofing a real deviceId from an attacker IP must NEVER lock out the legitimate headset IP),
    /// and identity-change warning flag (S3).
    func testPairingApprovalRejectionTimeoutIPOnlyCooldownIdentityChangeAndCertBoundAutoAllow() async throws {
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

        // 2. Timeout returns 408 and starts IP cooldown
        let timeoutBody = Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-timeout\",\"pin\":\"1234\"}".utf8)
        let timeoutResp = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: timeoutBody, remoteAddress: "192.168.1.11"))
        XCTAssertEqual(timeoutResp.statusCode, 408)
        let cooldownAfterTimeout = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: timeoutBody, remoteAddress: "192.168.1.11"))
        XCTAssertEqual(cooldownAfterTimeout.statusCode, 429)

        // 3. Single prompt enforcement + IP-ONLY cooldown (m5: attacker spoofing victim's deviceId cannot lock out victim IP!)
        let coord2 = PairingCoordinator(timeout: 5.0, cooldownDuration: 5.0)
        let router2 = ControlProtocolRouter(pairingCoordinator: coord2, delegate: delegate)

        let attackerTask = Task {
            await router2.handle(HTTPRequest(
                method: "POST",
                rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Spoofer\",\"deviceId\":\"victim-device\",\"pin\":\"4321\"}".utf8),
                remoteAddress: "::ffff:192.168.1.20"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)

        let activePrompts = await coord2.pendingPrompts()
        XCTAssertEqual(activePrompts.count, 1)
        XCTAssertEqual(activePrompts[0].clientAddress, "192.168.1.20")
        XCTAssertFalse(activePrompts[0].identityChanged)

        // Concurrent request while prompt is open -> 429 busy
        let concurrentResp = await router2.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO B\",\"deviceId\":\"dev-other\",\"pin\":\"1111\"}".utf8),
            remoteAddress: "192.168.1.21"
        ))
        XCTAssertEqual(concurrentResp.statusCode, 429)

        // Reject attacker -> 403; attacker IP is now cooled down -> 429
        await coord2.reject(promptId: activePrompts[0].id)
        let attackerResp = await attackerTask.value
        XCTAssertEqual(attackerResp.statusCode, 403)
        let attackerRetry = await router2.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Spoofer\",\"deviceId\":\"other-id\",\"pin\":\"4321\"}".utf8),
            remoteAddress: "192.168.1.20"
        ))
        XCTAssertEqual(attackerRetry.statusCode, 429)

        // Real headset on DIFFERENT IP (192.168.1.50) with the SAME `victim-device` ID is NOT locked out!
        let realHeadsetTask = Task {
            await router2.handle(HTTPRequest(
                method: "POST",
                rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Real PICO\",\"deviceId\":\"victim-device\",\"pin\":\"5678\"}".utf8),
                remoteAddress: "192.168.1.50"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let realPrompts = await coord2.pendingPrompts()
        XCTAssertEqual(realPrompts.count, 1, "IP-only cooldown must NOT lock out real headset on different IP")
        await coord2.approve(promptId: realPrompts[0].id, rememberAutoAllow: false)
        let realHeadsetResp = await realHeadsetTask.value
        XCTAssertEqual(realHeadsetResp.statusCode, 200)

        // 4. Certificate-bound auto-allow + identity-change flag (S3)
        let certADer = Data("real-paired-headset-cert-A".utf8)
        let certAFp = TLSIdentityManager.sha256Hex(certADer)
        let certBDer = Data("attacker-impersonator-cert-B".utf8)
        await delegate.setAuthorized(deviceId: "dev-ok", fingerprint: certAFp)

        let coord3 = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0)
        await coord3.setAutoAllowed(deviceId: "dev-ok", certFingerprintSHA256: certAFp)
        let router3 = ControlProtocolRouter(pairingCoordinator: coord3, delegate: delegate)

        // 4a. Same deviceId WITHOUT client cert -> MUST prompt AND flag `identityChanged == true`!
        let noCertTask = Task {
            await router3.handle(HTTPRequest(
                method: "POST",
                rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Fake PICO\",\"deviceId\":\"dev-ok\",\"pin\":\"5678\"}".utf8),
                peerCertificateDER: nil,
                remoteAddress: "192.168.1.30"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let noCertPrompts = await coord3.pendingPrompts()
        XCTAssertEqual(noCertPrompts.count, 1, "deviceId without cert must prompt")
        XCTAssertTrue(noCertPrompts[0].identityChanged, "Previously bound deviceId without cert must set identityChanged=true")
        await coord3.reject(promptId: noCertPrompts[0].id)
        let noCertResp = await noCertTask.value
        XCTAssertEqual(noCertResp.statusCode, 403)

        // 4b. Same deviceId WITH wrong client cert -> MUST prompt AND flag `identityChanged == true`!
        let wrongCertTask = Task {
            await router3.handle(HTTPRequest(
                method: "POST",
                rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Fake PICO\",\"deviceId\":\"dev-ok\",\"pin\":\"5678\"}".utf8),
                peerCertificateDER: certBDer,
                remoteAddress: "192.168.1.31"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let wrongCertPrompts = await coord3.pendingPrompts()
        XCTAssertEqual(wrongCertPrompts.count, 1)
        XCTAssertTrue(wrongCertPrompts[0].identityChanged, "Mismatched client cert must set identityChanged=true")
        await coord3.reject(promptId: wrongCertPrompts[0].id)
        let wrongCertResp = await wrongCertTask.value
        XCTAssertEqual(wrongCertResp.statusCode, 403)

        // 4c. Same deviceId WITH exact bound TLS client cert -> auto-approves immediately -> 200
        let legitimate = await router3.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-ok\",\"pin\":\"9999\"}".utf8),
            peerCertificateDER: certADer,
            remoteAddress: "192.168.1.32"
        ))
        XCTAssertEqual(legitimate.statusCode, 200)
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
        let r2 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media", headers: ["X-Depth3d-Device": "pico-01"]))
        XCTAssertEqual(r2.statusCode, 401)
        let r3 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media", headers: ["X-Depth3d-Device": "pico-01"], peerCertificateDER: Data("other-cert".utf8)))
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
        XCTAssertEqual(decoded.webdav?.pass, "rndpass99")
    }

    func testBonjourTXTRecordAndErrorClassification() {
        let readyTXT = HostBonjourTXTRecord.makeDictionary(
            name: "书房 Mac", id: "UUID-1", gsPort: 47989, mediaPort: 8890, mediaRevision: 2
        )
        XCTAssertEqual(String(data: readyTXT["v"] ?? Data(), encoding: .utf8), "1")
        XCTAssertEqual(String(data: readyTXT["gs"] ?? Data(), encoding: .utf8), "47989")
        XCTAssertEqual(String(data: readyTXT["media"] ?? Data(), encoding: .utf8), "8890")
        XCTAssertEqual(String(data: readyTXT["mrev"] ?? Data(), encoding: .utf8), "2")

        let engineDownTXT = HostBonjourTXTRecord.makeDictionary(
            name: "Mac", id: "UUID-2", gsPort: nil, mediaPort: nil
        )
        XCTAssertNil(engineDownTXT["gs"])
        XCTAssertNil(engineDownTXT["media"])
        XCTAssertNil(engineDownTXT["mrev"])

        XCTAssertEqual(HostBonjourTXTRecord.classifyPublishError(code: -65570), .permissionDenied)
        XCTAssertEqual(HostBonjourTXTRecord.classifyPublishError(code: -72008), .permissionDenied)
        XCTAssertEqual(HostBonjourTXTRecord.classifyPublishError(code: -72001), .failed(-72001))
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
        try generateSelfSignedCert(keyURL: clientKey, certURL: clientCert, cn: "PICO4")

        let clientPEM = try String(contentsOf: clientCert, encoding: .utf8)
        let clientFP = try XCTUnwrap(TLSIdentityManager.sha256Hex(fromPEM: clientPEM))

        let snap = ControlProtocolSnapshot(
            hostName: "测试 Mac", hostId: "HOST-UUID", osDescription: "macOS 15.1",
            streamReady: true, streamNotReadyReason: nil, mediaPort: 8899,
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

        let (code1, body1) = runCurl(["-sk", "--max-time", "5", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(code1, 200)
        XCTAssertTrue(body1.contains("\"ready\":true"))

        let (code2, _) = runCurl(["-sk", "--max-time", "5", "-H", "X-Depth3d-Device: pico-live", "https://127.0.0.1:\(port)/depth3d/v1/media"])
        XCTAssertEqual(code2, 401)

        let (code3, body3) = runCurl([
            "-sk", "--max-time", "5",
            "--cert", clientCert.path, "--key", clientKey.path,
            "-H", "X-Depth3d-Device: pico-live",
            "https://127.0.0.1:\(port)/depth3d/v1/media"
        ])
        XCTAssertEqual(code3, 200)
        XCTAssertTrue(body3.contains("\"pass\":\"livepass\""))

        // Non-blocking /pair wait
        let pairTask = Task.detached { [self] () -> (Int, String) in
            self.runCurl([
                "-sk", "--max-time", "5", "-X", "POST",
                "-H", "Content-Type: application/json",
                "-d", "{\"device\":\"PICO 4\",\"deviceId\":\"nonblock\",\"pin\":\"1234\"}",
                "https://127.0.0.1:\(port)/depth3d/v1/pair"
            ])
        }
        try await Task.sleep(nanoseconds: 250_000_000)
        let prompts = await coord.pendingPrompts()
        XCTAssertEqual(prompts.count, 1)

        let (infoWhileWaiting, _) = runCurl(["-sk", "--max-time", "2", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(infoWhileWaiting, 200)
        await coord.approve(promptId: prompts[0].id, rememberAutoAllow: false)
        let pairResult = await pairTask.value
        XCTAssertEqual(pairResult.0, 200)

        // Unauthenticated rate limiting
        let rlServer = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 8,
            unauthRateLimitPerWindow: 2,
            unauthRateWindowSeconds: 60.0
        )
        let rlPort = try rlServer.start(port: 0, fallbackToEphemeral: false)
        defer { rlServer.stop() }
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 429)
    }

    /// S5: Verifies per-connection ABSOLUTE handshake deadline defeats slow-drip connections
    /// (sending 1 byte every 150 ms) and releases connection slots promptly.
    func testControlHTTPSServerEnforcesAbsoluteDeadlineAgainstSlowDripClients() async throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-slowloris-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let tlsManager = TLSIdentityManager(tlsDirectory: tmpDir.appendingPathComponent("tls"))
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "Slowloris Host")
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "H1", osDescription: "macOS 15.1",
            streamReady: true, streamNotReadyReason: nil, mediaPort: nil, webDAVCredentials: nil
        )
        let router = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: MockControlDelegate(snapshot: snap))
        let server = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 2,
            handshakeTimeoutSec: 0.7,
            readTimeoutSec: 0.7
        )
        let port = try server.start(port: 0, fallbackToEphemeral: false)
        defer { server.stop() }

        // Open 2 slow-drip TCP connections (filling maxConnections=2) dripping 1 byte every 150 ms
        // of an incomplete TLS ClientHello record header (claiming 200-byte handshake).
        let dripBytes: [UInt8] = [0x16, 0x03, 0x01, 0x00, 0xC8, 0x01, 0x00, 0x00, 0xC4, 0x03, 0x03]
        var slowFDs: [Int32] = []
        for _ in 0..<2 {
            let fd = connectLoopback(port: port)
            XCTAssertGreaterThanOrEqual(fd, 0)
            slowFDs.append(fd)
        }
        defer { for fd in slowFDs { Darwin.close(fd) } }

        // Drip bytes for 1.2s (> 0.7s absolute handshake deadline). Without an absolute deadline,
        // each byte would keep resetting SO_RCVTIMEO and slots would stay occupied!
        let dripTask = Task.detached {
            for b in dripBytes {
                var byte = b
                for fd in slowFDs {
                    _ = Darwin.send(fd, &byte, 1, 0)
                }
                try? await Task.sleep(nanoseconds: 130_000_000)
            }
        }
        _ = await dripTask.value

        // After ~1.4s total, both slow-drip connections MUST have been cut by the absolute deadline
        // and freed their ConnectionGuard slots, so a normal /info succeeds immediately!
        let (code, _) = runCurl(["-sk", "--max-time", "2", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(code, 200, "Slow-drip connections must time out at absolute deadline and free connection slots")
    }

    /// m7: Cryptographically proves `ControlHTTPSServer` (SecureTransport `.tryAuthenticate`)
    /// verifies the client's `CertificateVerify` handshake signature (proof of private-key possession),
    /// NOT merely accepting whatever client certificate is sent!
    func testControlHTTPSServerVerifiesClientPrivateKeyPossessionDuringTLSHandshake() async throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-certverify-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let tlsManager = TLSIdentityManager(tlsDirectory: tmpDir.appendingPathComponent("tls"))
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "CertVerify Host")

        let clientKey = tmpDir.appendingPathComponent("clientA.key")
        let clientCert = tmpDir.appendingPathComponent("clientA.crt")
        try generateSelfSignedCert(keyURL: clientKey, certURL: clientCert, cn: "LegitHeadset")

        let clientPEM = try String(contentsOf: clientCert, encoding: .utf8)
        let clientDER = try XCTUnwrap(pemToDER(clientPEM))
        let clientFP = TLSIdentityManager.sha256Hex(clientDER)

        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "H1", osDescription: "macOS 15.1",
            streamReady: true, streamNotReadyReason: nil, mediaPort: 8890,
            webDAVCredentials: WebDAVCredentials(user: "depth3d", pass: "topsecret")
        )
        let delegate = MockControlDelegate(snapshot: snap)
        await delegate.setAuthorized(deviceId: "legit-dev", fingerprint: clientFP)

        let router = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: delegate)
        let server = ControlHTTPSServer(identity: serverId.secIdentity, router: router)
        let port = try server.start(port: 0, fallbackToEphemeral: false)
        defer { server.stop() }

        // 1. Forged TLS 1.2 handshake: present the AUTHORIZED `clientA.crt` WITHOUT its private key
        //    by sending a forged `CertificateVerify` signature -> server MUST reject with TLS Fatal Alert!
        let alertResult = try sendTLS12HandshakeWithForgedCertificateVerify(port: port, clientCertDER: clientDER)
        XCTAssertTrue(
            alertResult.rejectedByServer,
            "Server MUST reject TLS handshake when client presents authorized cert without matching private key (alert=\(String(describing: alertResult.fatalAlertDescription)))"
        )
        let handledAfterForge = await delegate.handledRequestCount
        XCTAssertEqual(handledAfterForge, 0, "Forged CertificateVerify must abort inside TLS before HTTP routing")

        // 2. Contrast: presenting the EXACT SAME `clientA.crt` WITH its matching private key succeeds -> 200!
        let (okCode, okBody) = runCurl([
            "-sk", "--max-time", "5",
            "--cert", clientCert.path, "--key", clientKey.path,
            "-H", "X-Depth3d-Device: legit-dev",
            "https://127.0.0.1:\(port)/depth3d/v1/media"
        ])
        XCTAssertEqual(okCode, 200)
        XCTAssertTrue(okBody.contains("\"pass\":\"topsecret\""))
    }

    // MARK: - Raw TLS 1.2 Forged CertificateVerify Probe

    private struct TLSForgeProbeResult {
        let rejectedByServer: Bool
        let fatalAlertDescription: UInt8?
    }

    private func sendTLS12HandshakeWithForgedCertificateVerify(
        port: UInt16,
        clientCertDER: Data
    ) throws -> TLSForgeProbeResult {
        let fd = connectLoopback(port: port)
        XCTAssertGreaterThanOrEqual(fd, 0)
        defer { Darwin.close(fd) }
        var tv = timeval(tv_sec: 4, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        // Build TLS 1.2 ClientHello offering RSA key-exchange suites so we can construct a valid
        // ClientKeyExchange using the server's RSA certificate, isolating CertificateVerify verification!
        var chBody = Data([0x03, 0x03]) // TLS 1.2
        chBody.append(Data((0..<32).map { UInt8($0 + 1) })) // 32B client_random
        chBody.append(0x00) // session_id len = 0
        // Cipher suites: TLS_RSA_WITH_AES_128_GCM_SHA256 (0x009C), TLS_RSA_WITH_AES_256_GCM_SHA384 (0x009D),
        // TLS_RSA_WITH_AES_128_CBC_SHA256 (0x003C), TLS_RSA_WITH_AES_128_CBC_SHA (0x002F)
        let suites: [UInt8] = [0x00, 0x9C, 0x00, 0x9D, 0x00, 0x3C, 0x00, 0x2F]
        chBody.append(contentsOf: [0x00, UInt8(suites.count)])
        chBody.append(contentsOf: suites)
        chBody.append(contentsOf: [0x01, 0x00]) // compression methods: null
        // signature_algorithms extension (0x000D): sha256+rsa (0x0401), sha1+rsa (0x0201)
        let sigAlgExt: [UInt8] = [0x00, 0x0D, 0x00, 0x06, 0x00, 0x04, 0x04, 0x01, 0x02, 0x01]
        chBody.append(contentsOf: [0x00, UInt8(sigAlgExt.count)])
        chBody.append(contentsOf: sigAlgExt)

        let chMsg = wrapHandshake(type: 0x01, body: chBody)
        sendRaw(fd: fd, data: wrapTLSRecord(contentType: 0x16, payload: chMsg))

        // Read server records until ServerHelloDone (handshake type 0x0E) and extract server's leaf cert DER.
        var serverCertDER: Data?
        var sawCertificateRequest = false
        var sawServerHelloDone = false

        while !sawServerHelloDone {
            guard let (ctype, payload) = readTLSRecord(fd: fd) else {
                XCTFail("Server closed prematurely before ServerHelloDone")
                return TLSForgeProbeResult(rejectedByServer: false, fatalAlertDescription: nil)
            }
            guard ctype == 0x16 else {
                XCTFail("Unexpected TLS content type \(ctype) before ServerHelloDone")
                return TLSForgeProbeResult(rejectedByServer: false, fatalAlertDescription: nil)
            }
            var off = 0
            while off + 4 <= payload.count {
                let hType = payload[off]
                let hLen = (Int(payload[off + 1]) << 16) | (Int(payload[off + 2]) << 8) | Int(payload[off + 3])
                let bodyStart = off + 4
                let bodyEnd = min(payload.count, bodyStart + hLen)
                if hType == 0x0B && bodyEnd >= bodyStart + 6 {
                    // Server Certificate list: 3B total len, 3B first cert len, cert bytes
                    let firstLen = (Int(payload[bodyStart + 3]) << 16) | (Int(payload[bodyStart + 4]) << 8) | Int(payload[bodyStart + 5])
                    if bodyStart + 6 + firstLen <= bodyEnd {
                        serverCertDER = payload.subdata(in: (bodyStart + 6)..<(bodyStart + 6 + firstLen))
                    }
                } else if hType == 0x0D {
                    sawCertificateRequest = true
                } else if hType == 0x0E {
                    sawServerHelloDone = true
                }
                off = bodyEnd
            }
        }

        XCTAssertTrue(sawCertificateRequest, "Server must request optional client certificate via CertificateRequest")
        let srvDER = try XCTUnwrap(serverCertDER)
        let srvCert = try XCTUnwrap(SecCertificateCreateWithData(nil, srvDER as CFData))
        let srvPubKey = try XCTUnwrap(SecCertificateCopyKey(srvCert))

        // 1. Client Certificate message carrying AUTHORIZED `clientA.crt`
        let cLen = clientCertDER.count
        let listLen = cLen + 3
        var certBody = Data([
            UInt8((listLen >> 16) & 0xFF), UInt8((listLen >> 8) & 0xFF), UInt8(listLen & 0xFF),
            UInt8((cLen >> 16) & 0xFF), UInt8((cLen >> 8) & 0xFF), UInt8(cLen & 0xFF)
        ])
        certBody.append(clientCertDER)
        let certMsg = wrapHandshake(type: 0x0B, body: certBody)

        // 2. Valid RSA ClientKeyExchange (48-byte TLS 1.2 premaster encrypted with server's RSA public key)
        var premaster = Data([0x03, 0x03])
        premaster.append(Data(repeating: 0x42, count: 46))
        var errRef: Unmanaged<CFError>?
        let encPremaster = try XCTUnwrap(
            SecKeyCreateEncryptedData(srvPubKey, .rsaEncryptionPKCS1, premaster as CFData, &errRef) as Data?
        )
        var ckeBody = Data([UInt8((encPremaster.count >> 8) & 0xFF), UInt8(encPremaster.count & 0xFF)])
        ckeBody.append(encPremaster)
        let ckeMsg = wrapHandshake(type: 0x10, body: ckeBody)

        // 3. FORGED CertificateVerify (type 0x0F, SHA256+RSA = 0x0401, 256 bytes of invalid signature!)
        let forgedSig = Data(repeating: 0xAA, count: 256)
        var cvBody = Data([0x04, 0x01, UInt8((forgedSig.count >> 8) & 0xFF), UInt8(forgedSig.count & 0xFF)])
        cvBody.append(forgedSig)
        let cvMsg = wrapHandshake(type: 0x0F, body: cvBody)

        // Send Certificate + ClientKeyExchange + forged CertificateVerify
        var flight = Data()
        flight.append(wrapTLSRecord(contentType: 0x16, payload: certMsg))
        flight.append(wrapTLSRecord(contentType: 0x16, payload: ckeMsg))
        flight.append(wrapTLSRecord(contentType: 0x16, payload: cvMsg))
        sendRaw(fd: fd, data: flight)

        // Expect TLS Fatal Alert (contentType 0x15, level 0x02) or immediate EOF abort!
        if let (ctype, payload) = readTLSRecord(fd: fd) {
            if ctype == 0x15 && payload.count >= 2 && payload[0] == 0x02 {
                return TLSForgeProbeResult(rejectedByServer: true, fatalAlertDescription: payload[1])
            }
            return TLSForgeProbeResult(rejectedByServer: false, fatalAlertDescription: nil)
        }
        return TLSForgeProbeResult(rejectedByServer: true, fatalAlertDescription: nil)
    }

    private func wrapHandshake(type: UInt8, body: Data) -> Data {
        let len = body.count
        var out = Data([type, UInt8((len >> 16) & 0xFF), UInt8((len >> 8) & 0xFF), UInt8(len & 0xFF)])
        out.append(body)
        return out
    }

    private func wrapTLSRecord(contentType: UInt8, payload: Data) -> Data {
        let len = payload.count
        var out = Data([contentType, 0x03, 0x03, UInt8((len >> 8) & 0xFF), UInt8(len & 0xFF)])
        out.append(payload)
        return out
    }

    private func readExact(fd: Int32, count: Int) -> Data? {
        var out = Data()
        var buf = [UInt8](repeating: 0, count: count)
        while out.count < count {
            let n = Darwin.recv(fd, &buf, count - out.count, 0)
            if n > 0 { out.append(buf, count: n) }
            else if n < 0 && errno == EINTR { continue }
            else { return nil }
        }
        return out
    }

    private func readTLSRecord(fd: Int32) -> (UInt8, Data)? {
        guard let hdr = readExact(fd: fd, count: 5) else { return nil }
        let len = (Int(hdr[3]) << 8) | Int(hdr[4])
        guard let payload = readExact(fd: fd, count: len) else { return nil }
        return (hdr[0], payload)
    }

    private func sendRaw(fd: Int32, data: Data) {
        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            var off = 0
            while off < data.count {
                let n = Darwin.send(fd, base.advanced(by: off), data.count - off, 0)
                if n > 0 { off += n } else { break }
            }
        }
    }

    private func connectLoopback(port: UInt16) -> Int32 {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return -1 }
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = port.bigEndian
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if rc != 0 {
            Darwin.close(fd)
            return -1
        }
        return fd
    }

    private func pemToDER(_ pem: String) -> Data? {
        let b64 = pem.components(separatedBy: .newlines)
            .filter { !$0.hasPrefix("-----") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .joined()
        return Data(base64Encoded: b64)
    }

    private func generateSelfSignedCert(keyURL: URL, certURL: URL, cn: String) throws {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        proc.arguments = [
            "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", keyURL.path, "-out", certURL.path,
            "-days", "1", "-nodes", "-subj", "/CN=\(cn)"
        ]
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        try proc.run()
        proc.waitUntilExit()
        XCTAssertEqual(proc.terminationStatus, 0)
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
