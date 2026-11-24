import CryptoKit
import Darwin
import Foundation
import Security
import XCTest
@testable import Depth3DHostCore

private actor MockControlDelegate: ControlProtocolDelegate {
    var snapshot: ControlProtocolSnapshot
    var authorizedFingerprints: Set<String> = []
    var knownBoundByDeviceId: [String: Set<String>] = [:]
    var engineUniquelyPairedFingerprints: Set<String> = []
    var issuedTickets: [String: (clientIP: String, deviceName: String, deviceId: String, autoAllow: Bool)] = [:]
    var cancelledIPs: [String] = []
    var handledRequestCount: Int = 0

    init(snapshot: ControlProtocolSnapshot) {
        self.snapshot = snapshot
    }

    func setAuthorized(deviceId: String, fingerprint: String) {
        let up = fingerprint.uppercased()
        authorizedFingerprints.insert(up)
        engineUniquelyPairedFingerprints.insert(up)
        knownBoundByDeviceId[deviceId, default: []].insert(up)
    }

    func setEnginePairedOnly(fingerprint: String) {
        engineUniquelyPairedFingerprints.insert(fingerprint.uppercased())
    }

    func currentSnapshot() async -> ControlProtocolSnapshot {
        handledRequestCount += 1
        return snapshot
    }

    func isKnownPairedCertificate(fingerprintSHA256: String) async -> Bool {
        authorizedFingerprints.contains(fingerprintSHA256.uppercased())
    }

    func authorizeMediaClient(peerCertFingerprintSHA256: String) async -> Bool {
        handledRequestCount += 1
        return authorizedFingerprints.contains(peerCertFingerprintSHA256.uppercased())
    }

    func knownBoundCertFingerprints(forDeviceId deviceId: String) async -> Set<String> {
        knownBoundByDeviceId[deviceId] ?? []
    }

    func issueApprovedPairingTicket(
        deviceName: String,
        deviceId: String,
        pin: String,
        clientAddress: String,
        peerCertFingerprintSHA256: String?,
        autoAllowFuture: Bool,
        approvedViaAutoAllow: Bool
    ) async -> PairTicketIssueResult {
        handledRequestCount += 1
        guard snapshot.streamReady else {
            return .streamNotReady(reason: snapshot.streamNotReadyReason ?? "不可用")
        }
        let next: String
        if let fp = peerCertFingerprintSHA256?.uppercased(), engineUniquelyPairedFingerprints.contains(fp) {
            next = "done"
        } else {
            next = "gamestream"
        }
        let ticket = "tkt-\(UUID().uuidString.prefix(8))"
        issuedTickets[ticket] = (IPAddressNormalizer.normalize(clientAddress), deviceName, deviceId, autoAllowFuture)
        return .issued(next: next, ticket: ticket)
    }

    func completePairing(
        ticket: String,
        deviceName: String?,
        deviceId: String?,
        clientAddress: String,
        peerCertFingerprintSHA256: String
    ) async -> PairCompleteOutcome {
        handledRequestCount += 1
        guard let entry = issuedTickets[ticket] else { return .ticketExpired }
        guard entry.clientIP == IPAddressNormalizer.normalize(clientAddress) else { return .sourceMismatch }
        let fp = peerCertFingerprintSHA256.uppercased()
        guard engineUniquelyPairedFingerprints.contains(fp) else { return .notPaired }
        issuedTickets.removeValue(forKey: ticket)
        authorizedFingerprints.insert(fp)
        return .completed(mediaPort: snapshot.mediaPort)
    }

    func cancelPendingEnginePairings(forClientAddress clientAddress: String) async {
        cancelledIPs.append(IPAddressNormalizer.normalize(clientAddress))
    }

    func getCancelledIPs() -> [String] { cancelledIPs }
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

    /// P6: Engine not ready returns `503 {"error":"stream_not_ready","reason":"..."}` immediately without prompting.
    func testPairReturns503StreamNotReadyImmediatelyWhenEngineNotReady() async throws {
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
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: resp.body) as? [String: Any])
        XCTAssertEqual(json["error"] as? String, "stream_not_ready")
        XCTAssertNotNil(json["reason"] as? String)
        let prompts = await coord.pendingPrompts()
        XCTAssertTrue(prompts.isEmpty)
    }

    /// Covers P1/P2/P5/P6/S-5:
    /// - Exact protocol error codes (`invalid_request`, `rejected`, `timeout`, `busy`+`retryAfterSec`,
    ///   `cooldown`+`retryAfterSec`, `client_cert_required`, `not_paired`, `ticket_expired`, `source_mismatch`)
    /// - IP-only cooldown & IP prompt fairness (one IP cannot monopolize consecutive prompts when another waits)
    /// - Pending engine pairing cancellation on reject/timeout/error (`P5`)
    /// - Cert-fingerprint-only auto-allow & `identityChanged` flag
    func testTwoStepPairErrorsCancelOnFailureIPFairnessAndCertAutoAllow() async throws {
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: true, streamNotReadyReason: nil, mediaPort: 8890, webDAVCredentials: nil
        )
        let delegate = MockControlDelegate(snapshot: snap)
        let coordinator = PairingCoordinator(timeout: 0.2, cooldownDuration: 2.0, fairnessYieldDuration: 0.0)
        let router = ControlProtocolRouter(pairingCoordinator: coordinator, delegate: delegate)

        // 1. Invalid PIN -> 400 invalid_request
        let badResp = await router.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-1\",\"pin\":\"12ab\"}".utf8),
            remoteAddress: "192.168.1.10"
        ))
        XCTAssertEqual(badResp.statusCode, 400)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: badResp.body) as? [String: Any])?["error"] as? String, "invalid_request")

        // 2. Timeout -> 408 timeout + cancels engine pending pairing for IP + 429 cooldown with retryAfterSec
        let timeoutBody = Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-timeout\",\"pin\":\"1234\"}".utf8)
        let timeoutResp = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: timeoutBody, remoteAddress: "192.168.1.11"))
        XCTAssertEqual(timeoutResp.statusCode, 408)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: timeoutResp.body) as? [String: Any])?["error"] as? String, "timeout")
        let cancelledAfterTimeout = await delegate.getCancelledIPs()
        XCTAssertTrue(cancelledAfterTimeout.contains("192.168.1.11"), "P5: timeout must cancel pending engine pairing for IP")

        let cooldownResp = await router.handle(HTTPRequest(method: "POST", rawTarget: "/depth3d/v1/pair", body: timeoutBody, remoteAddress: "192.168.1.11"))
        XCTAssertEqual(cooldownResp.statusCode, 429)
        let cdJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: cooldownResp.body) as? [String: Any])
        XCTAssertEqual(cdJSON["error"] as? String, "cooldown")
        XCTAssertGreaterThanOrEqual(cdJSON["retryAfterSec"] as? Int ?? 0, 1)

        // 3. Single prompt + busy + IP-only cooldown + reject cancels engine pairing
        let coord2 = PairingCoordinator(timeout: 5.0, cooldownDuration: 5.0, fairnessYieldDuration: 0.0)
        let router2 = ControlProtocolRouter(pairingCoordinator: coord2, delegate: delegate)

        let attackerTask = Task {
            await router2.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Spoofer\",\"deviceId\":\"victim-device\",\"pin\":\"4321\"}".utf8),
                remoteAddress: "::ffff:192.168.1.20"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let activePrompts = await coord2.pendingPrompts()
        XCTAssertEqual(activePrompts.count, 1)
        XCTAssertEqual(activePrompts[0].clientAddress, "192.168.1.20")

        let busyResp = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO B\",\"deviceId\":\"dev-other\",\"pin\":\"1111\"}".utf8),
            remoteAddress: "192.168.1.21"
        ))
        XCTAssertEqual(busyResp.statusCode, 429)
        let busyJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: busyResp.body) as? [String: Any])
        XCTAssertEqual(busyJSON["error"] as? String, "busy")
        XCTAssertGreaterThanOrEqual(busyJSON["retryAfterSec"] as? Int ?? 0, 1)

        await coord2.reject(promptId: activePrompts[0].id)
        let attackerResp = await attackerTask.value
        XCTAssertEqual(attackerResp.statusCode, 403)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: attackerResp.body) as? [String: Any])?["error"] as? String, "rejected")
        let cancelledAfterReject = await delegate.getCancelledIPs()
        XCTAssertTrue(cancelledAfterReject.contains("192.168.1.20"), "P5: reject must cancel pending engine pairing for IP")

        // Real headset on DIFFERENT IP with SAME `victim-device` ID is NOT locked out -> 200 {ok, next:"gamestream", ticket}
        let realHeadsetTask = Task {
            await router2.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Real PICO\",\"deviceId\":\"victim-device\",\"pin\":\"5678\"}".utf8),
                remoteAddress: "192.168.1.50"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let realPrompts = await coord2.pendingPrompts()
        XCTAssertEqual(realPrompts.count, 1)
        await coord2.approve(promptId: realPrompts[0].id, rememberAutoAllow: false)
        let realHeadsetResp = await realHeadsetTask.value
        XCTAssertEqual(realHeadsetResp.statusCode, 200)
        let approved = try JSONDecoder().decode(PairApprovedResponse.self, from: realHeadsetResp.body)
        XCTAssertTrue(approved.ok)
        XCTAssertEqual(approved.next, "gamestream")
        XCTAssertFalse(approved.ticket.isEmpty)

        // 4. `/pair/complete` error codes: 401 no cert, 403 source mismatch, 409 not_paired, 200 completed, 410 expired/reused
        let compNoCert = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\",\"deviceId\":\"victim-device\",\"device\":\"Real PICO\"}".utf8),
            remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compNoCert.statusCode, 401)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: compNoCert.body) as? [String: Any])?["error"] as? String, "client_cert_required")

        let newCertDER = Data("real-headset-fresh-cert".utf8)
        let newCertFP = TLSIdentityManager.sha256Hex(newCertDER)
        let compWrongIP = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\",\"deviceId\":\"victim-device\",\"device\":\"Real PICO\"}".utf8),
            peerCertificateDER: newCertDER,
            remoteAddress: "192.168.1.99"
        ))
        XCTAssertEqual(compWrongIP.statusCode, 403)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: compWrongIP.body) as? [String: Any])?["error"] as? String, "source_mismatch")

        // Before engine finishes pairing -> 409 not_paired (ticket stays valid!)
        let compNotYet = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\",\"deviceId\":\"victim-device\",\"device\":\"Real PICO\"}".utf8),
            peerCertificateDER: newCertDER,
            remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compNotYet.statusCode, 409)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: compNotYet.body) as? [String: Any])?["error"] as? String, "not_paired")

        // Once engine has cert -> 200 {ok:true, media:{port:8890}}
        await delegate.setEnginePairedOnly(fingerprint: newCertFP)
        let compOK = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\",\"deviceId\":\"victim-device\",\"device\":\"Real PICO\"}".utf8),
            peerCertificateDER: newCertDER,
            remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compOK.statusCode, 200)
        let compDecoded = try JSONDecoder().decode(PairCompleteResponse.self, from: compOK.body)
        XCTAssertTrue(compDecoded.ok)
        XCTAssertEqual(compDecoded.media?.port, 8890)

        // Reusing consumed ticket -> 410 ticket_expired
        let compReuse = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\"}".utf8),
            peerCertificateDER: newCertDER,
            remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compReuse.statusCode, 410)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: compReuse.body) as? [String: Any])?["error"] as? String, "ticket_expired")

        // 5. S-5 prompt IP fairness: IP A occupies prompt while IP B gets busy; once IP A finishes,
        //    IP A trying immediately again gets `429 busy` (yields) while IP B immediately gets the prompt!
        let fairCoord = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0, fairnessYieldDuration: 5.0)
        let fairRouter = ControlProtocolRouter(pairingCoordinator: fairCoord, delegate: delegate)

        let ipATask = Task {
            await fairRouter.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Spammer A\",\"deviceId\":\"a1\",\"pin\":\"1234\"}".utf8),
                remoteAddress: "192.168.1.100"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let aPrompts = await fairCoord.pendingPrompts()
        XCTAssertEqual(aPrompts.count, 1)

        // IP B arrives while IP A holds prompt -> 429 busy (IP B recorded as waiter)
        let ipBBlocked = await fairRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Legit B\",\"deviceId\":\"b1\",\"pin\":\"5678\"}".utf8),
            remoteAddress: "192.168.1.200"
        ))
        XCTAssertEqual(ipBBlocked.statusCode, 429)

        // IP A finishes (even approved, so zero reject cooldown)
        await fairCoord.approve(promptId: aPrompts[0].id, rememberAutoAllow: false)
        _ = await ipATask.value

        // IP A tries to monopolize the next prompt immediately -> MUST yield (`429 busy`)!
        let ipAMonopolize = await fairRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Spammer A Again\",\"deviceId\":\"a2\",\"pin\":\"1234\"}".utf8),
            remoteAddress: "192.168.1.100"
        ))
        XCTAssertEqual(ipAMonopolize.statusCode, 429, "S-5: IP A must yield prompt slot when IP B was waiting")

        // IP B immediately claims the prompt!
        let ipBTask = Task {
            await fairRouter.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Legit B\",\"deviceId\":\"b1\",\"pin\":\"5678\"}".utf8),
                remoteAddress: "192.168.1.200"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let bPrompts = await fairCoord.pendingPrompts()
        XCTAssertEqual(bPrompts.count, 1)
        XCTAssertEqual(bPrompts[0].clientAddress, "192.168.1.200")
        await fairCoord.approve(promptId: bPrompts[0].id, rememberAutoAllow: false)
        let ipBResp = await ipBTask.value
        XCTAssertEqual(ipBResp.statusCode, 200)

        // 6. Cert-fingerprint-only auto-allow & identityChanged detection (P4)
        let certADer = Data("real-paired-headset-cert-A".utf8)
        let certAFp = TLSIdentityManager.sha256Hex(certADer)
        let certBDer = Data("impersonator-cert-B".utf8)
        await delegate.setAuthorized(deviceId: "dev-ok", fingerprint: certAFp)

        let coord3 = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0, autoAllowedFingerprints: [certAFp])
        let router3 = ControlProtocolRouter(pairingCoordinator: coord3, delegate: delegate)

        // 6a. Same deviceId WITHOUT cert -> prompts with identityChanged == true
        let noCertTask = Task {
            await router3.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Fake PICO\",\"deviceId\":\"dev-ok\",\"pin\":\"5678\"}".utf8),
                peerCertificateDER: nil, remoteAddress: "192.168.1.30"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let noCertPrompts = await coord3.pendingPrompts()
        XCTAssertEqual(noCertPrompts.count, 1)
        XCTAssertTrue(noCertPrompts[0].identityChanged)
        await coord3.reject(promptId: noCertPrompts[0].id)
        let noCertResp = await noCertTask.value
        XCTAssertEqual(noCertResp.statusCode, 403)

        // 6b. Same deviceId WITH wrong cert -> prompts with identityChanged == true
        let wrongCertTask = Task {
            await router3.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Fake PICO\",\"deviceId\":\"dev-ok\",\"pin\":\"5678\"}".utf8),
                peerCertificateDER: certBDer, remoteAddress: "192.168.1.31"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let wrongCertPrompts = await coord3.pendingPrompts()
        XCTAssertEqual(wrongCertPrompts.count, 1)
        XCTAssertTrue(wrongCertPrompts[0].identityChanged)
        await coord3.reject(promptId: wrongCertPrompts[0].id)
        let wrongCertResp = await wrongCertTask.value
        XCTAssertEqual(wrongCertResp.statusCode, 403)

        // 6c. Exact bound cert -> auto-approves immediately -> 200 with next:"done" (already in engine)
        let legitResp = await router3.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-ok\",\"pin\":\"9999\"}".utf8),
            peerCertificateDER: certADer, remoteAddress: "192.168.1.32"
        ))
        XCTAssertEqual(legitResp.statusCode, 200)
        let legitDecoded = try JSONDecoder().decode(PairApprovedResponse.self, from: legitResp.body)
        XCTAssertEqual(legitDecoded.next, "done")
    }

    /// P3: `/depth3d/v1/media` authenticates strictly by TLS client certificate fingerprint
    /// and does NOT require `X-Depth3d-Device`.
    func testMediaEndpointAuthenticatesByClientCertWithoutDeviceHeader() async throws {
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
        let r3 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media", peerCertificateDER: Data("other-cert".utf8)))
        XCTAssertEqual(r3.statusCode, 401)

        // Valid paired cert WITHOUT X-Depth3d-Device header -> MUST return 200!
        let r4 = await router.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
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

    /// Covers live mTLS, non-blocking `/pair`, S-3 (IPv4-only bind), and S-5 (per-IP connection cap
    /// BEFORE TLS handshake + arbitrary self-signed client cert MUST NOT bypass unauthenticated rate limit!).
    func testControlHTTPSServerIPv4OnlyPerIPCapAndUnknownSelfSignedCertRateLimit() async throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-tls-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let tlsManager = TLSIdentityManager(tlsDirectory: tmpDir.appendingPathComponent("tls"))
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "depth3d Test Host")

        let pairedKey = tmpDir.appendingPathComponent("paired.key")
        let pairedCert = tmpDir.appendingPathComponent("paired.crt")
        try generateSelfSignedCert(keyURL: pairedKey, certURL: pairedCert, cn: "PairedPICO")
        let pairedFP = try XCTUnwrap(TLSIdentityManager.sha256Hex(fromPEM: String(contentsOf: pairedCert, encoding: .utf8)))

        let unknownKey = tmpDir.appendingPathComponent("unknown.key")
        let unknownCert = tmpDir.appendingPathComponent("unknown.crt")
        try generateSelfSignedCert(keyURL: unknownKey, certURL: unknownCert, cn: "RandomAttacker")

        let snap = ControlProtocolSnapshot(
            hostName: "测试 Mac", hostId: "HOST-UUID", osDescription: "macOS 15.1",
            streamReady: true, streamNotReadyReason: nil, mediaPort: 8899,
            webDAVCredentials: WebDAVCredentials(user: "depth3d", pass: "livepass")
        )
        let delegate = MockControlDelegate(snapshot: snap)
        await delegate.setAuthorized(deviceId: "pico-live", fingerprint: pairedFP)

        let coord = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0)
        let router = ControlProtocolRouter(pairingCoordinator: coord, delegate: delegate)
        let server = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 16,
            maxConnectionsPerIP: 8,
            unauthRateLimitPerWindow: 20
        )
        let port = try server.start(port: 0, fallbackToEphemeral: false)
        defer { server.stop() }

        // 1. S-3: IPv4 loopback works (`200`), IPv6 `::1` is refused (strictly IPv4-only!)
        let (code1, body1) = runCurl(["-sk", "--max-time", "5", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(code1, 200)
        XCTAssertTrue(body1.contains("\"ready\":true"))

        let ipv6FD = Darwin.socket(AF_INET6, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(ipv6FD, 0)
        defer { Darwin.close(ipv6FD) }
        var addr6 = sockaddr_in6()
        addr6.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        addr6.sin6_family = sa_family_t(AF_INET6)
        addr6.sin6_addr = in6addr_loopback
        addr6.sin6_port = port.bigEndian
        let v6RC = withUnsafePointer(to: &addr6) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(ipv6FD, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
            }
        }
        XCTAssertNotEqual(v6RC, 0, "S-3: ControlHTTPSServer must bind IPv4-only so IPv6 ::1 connections are refused")

        // 2. `/media` without cert -> 401; with paired cert (no X-Depth3d-Device header!) -> 200
        let (code2, _) = runCurl(["-sk", "--max-time", "5", "https://127.0.0.1:\(port)/depth3d/v1/media"])
        XCTAssertEqual(code2, 401)
        let (code3, body3) = runCurl([
            "-sk", "--max-time", "5",
            "--cert", pairedCert.path, "--key", pairedKey.path,
            "https://127.0.0.1:\(port)/depth3d/v1/media"
        ])
        XCTAssertEqual(code3, 200)
        XCTAssertTrue(body3.contains("\"pass\":\"livepass\""))

        // 3. Non-blocking `/pair` wait
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

        // 4. S-5: Arbitrary self-signed client certificate MUST NOT bypass unauthenticated rate limit,
        //         while a genuinely paired client certificate IS exempt!
        let rlServer = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 16,
            maxConnectionsPerIP: 8,
            unauthRateLimitPerWindow: 2,
            unauthRateWindowSeconds: 60.0
        )
        let rlPort = try rlServer.start(port: 0, fallbackToEphemeral: false)
        defer { rlServer.stop() }

        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "--cert", unknownCert.path, "--key", unknownKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "--cert", unknownCert.path, "--key", unknownKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        XCTAssertEqual(
            runCurl(["-sk", "--max-time", "3", "--cert", unknownCert.path, "--key", unknownKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0,
            429,
            "S-5: Arbitrary self-signed client cert MUST be throttled by unauthenticated rate limiter"
        )
        // Paired client certificate still succeeds cleanly despite the IP's unauth rate limit being full!
        XCTAssertEqual(
            runCurl(["-sk", "--max-time", "3", "--cert", pairedCert.path, "--key", pairedKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/media"]).0,
            200,
            "Genuinely paired client cert must remain exempt from unauthenticated rate limit"
        )

        // 5. S-5: Per-IP concurrent connection cap enforced BEFORE TLS handshake!
        let perIPServer = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 16,
            maxConnectionsPerIP: 2,
            handshakeTimeoutSec: 3.0,
            readTimeoutSec: 3.0
        )
        let perIPPort = try perIPServer.start(port: 0, fallbackToEphemeral: false)
        defer { perIPServer.stop() }

        let hold1 = connectLoopback(port: perIPPort)
        let hold2 = connectLoopback(port: perIPPort)
        XCTAssertGreaterThanOrEqual(hold1, 0)
        XCTAssertGreaterThanOrEqual(hold2, 0)
        defer { Darwin.close(hold1); Darwin.close(hold2) }
        try await Task.sleep(nanoseconds: 150_000_000)

        // 3rd connection from same IP (127.0.0.1) exceeds maxConnectionsPerIP=2 (even though global maxConnections=16!)
        // and MUST be immediately closed before TLS handshake!
        let thirdFD = connectLoopback(port: perIPPort)
        XCTAssertGreaterThanOrEqual(thirdFD, 0)
        defer { Darwin.close(thirdFD) }
        var tv = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(thirdFD, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var probeByte: UInt8 = 0
        let recvN = Darwin.recv(thirdFD, &probeByte, 1, 0)
        XCTAssertEqual(recvN, 0, "S-5: 3rd concurrent connection from same IP must be closed immediately before TLS handshake")
    }

    /// S5: Verifies per-connection ABSOLUTE handshake deadline defeats slow-drip connections.
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
            maxConnectionsPerIP: 2,
            handshakeTimeoutSec: 0.7,
            readTimeoutSec: 0.7
        )
        let port = try server.start(port: 0, fallbackToEphemeral: false)
        defer { server.stop() }

        let dripBytes: [UInt8] = [0x16, 0x03, 0x01, 0x00, 0xC8, 0x01, 0x00, 0x00, 0xC4, 0x03, 0x03]
        var slowFDs: [Int32] = []
        for _ in 0..<2 {
            let fd = connectLoopback(port: port)
            XCTAssertGreaterThanOrEqual(fd, 0)
            slowFDs.append(fd)
        }
        defer { for fd in slowFDs { Darwin.close(fd) } }

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

        let (code, _) = runCurl(["-sk", "--max-time", "2", "https://127.0.0.1:\(port)/depth3d/v1/info"])
        XCTAssertEqual(code, 200, "Slow-drip connections must time out at absolute deadline and free connection slots")
    }

    /// m-2 (upgraded m7): Cryptographically proves `ControlHTTPSServer` verifies `CertificateVerify`
    /// private-key possession by running TWO identical raw TLS 1.2 RSA-AES-128-GCM-SHA256 handshakes:
    ///   1. Valid-signature control handshake (real RSA-SHA256 transcript signature + real encrypted Finished):
    ///      server MUST complete TLS handshake (`ChangeCipherSpec` + server `Finished`, NO alert)!
    ///   2. Forged-signature handshake (identical flight with 1 byte flipped in `CertificateVerify`):
    ///      server MUST respond with an EXPLICIT TLS Fatal Alert (`decrypt_error` = 51) — never accepting
    ///      EOF or handshake timeout as a pass!
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

        // 1. Valid-signature raw TLS 1.2 control handshake: MUST receive server ChangeCipherSpec (0x14), NO fatal alert!
        let validResult = try runRawTLS12Handshake(
            port: port,
            clientCertDER: clientDER,
            clientKeyURL: clientKey,
            forgeCertificateVerify: false
        )
        XCTAssertTrue(
            validResult.receivedServerChangeCipherSpec,
            "m-2 control handshake with valid CertificateVerify signature MUST succeed (alert=\(String(describing: validResult.fatalAlertDescription)))"
        )
        XCTAssertNil(validResult.fatalAlertDescription)

        // 2. Identical raw TLS 1.2 handshake with 1 byte flipped in CertificateVerify signature:
        //    MUST receive an explicit TLS Fatal Alert (`decrypt_error` = 51), NOT timeout/EOF!
        let forgedResult = try runRawTLS12Handshake(
            port: port,
            clientCertDER: clientDER,
            clientKeyURL: clientKey,
            forgeCertificateVerify: true
        )
        XCTAssertFalse(forgedResult.receivedServerChangeCipherSpec)
        XCTAssertEqual(
            forgedResult.fatalAlertDescription,
            51, // RFC 5246 decrypt_error (0x33)
            "m-2: Forged CertificateVerify MUST trigger explicit TLS Fatal Alert decrypt_error (51), got \(String(describing: forgedResult.fatalAlertDescription))"
        )
        let handledAfterForge = await delegate.handledRequestCount
        XCTAssertEqual(handledAfterForge, 0, "Forged CertificateVerify must abort inside TLS before HTTP routing")
    }

    // MARK: - Raw TLS 1.2 RSA-AES-128-GCM-SHA256 Probe (Valid vs 1-Bit-Forged CertificateVerify)

    private struct RawTLS12Result {
        let receivedServerChangeCipherSpec: Bool
        let fatalAlertDescription: UInt8?
    }

    private func runRawTLS12Handshake(
        port: UInt16,
        clientCertDER: Data,
        clientKeyURL: URL,
        forgeCertificateVerify: Bool
    ) throws -> RawTLS12Result {
        let fd = connectLoopback(port: port)
        XCTAssertGreaterThanOrEqual(fd, 0)
        defer { Darwin.close(fd) }
        var tv = timeval(tv_sec: 4, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var transcript = Data()
        let clientRandom = Data((0..<32).map { UInt8(($0 * 7 + 13) & 0xFF) })

        // Offer strictly TLS_RSA_WITH_AES_128_GCM_SHA256 (0x009C) so key derivation & Finished are exact.
        var chBody = Data([0x03, 0x03])
        chBody.append(clientRandom)
        chBody.append(0x00) // session_id len
        chBody.append(contentsOf: [0x00, 0x02, 0x00, 0x9C]) // 1 cipher suite: 0x009C
        chBody.append(contentsOf: [0x01, 0x00]) // null compression
        let sigAlgExt: [UInt8] = [0x00, 0x0D, 0x00, 0x04, 0x00, 0x02, 0x04, 0x01] // sha256+rsa
        chBody.append(contentsOf: [0x00, UInt8(sigAlgExt.count)])
        chBody.append(contentsOf: sigAlgExt)

        let chMsg = wrapHandshake(type: 0x01, body: chBody)
        transcript.append(chMsg)
        sendRaw(fd: fd, data: wrapTLSRecord(contentType: 0x16, payload: chMsg))

        var serverStream = Data()
        var serverRandom: Data?
        var serverCertDER: Data?
        var sawCertificateRequest = false
        var sawServerHelloDone = false

        while !sawServerHelloDone {
            guard let (ctype, payload) = readTLSRecord(fd: fd) else {
                XCTFail("Server closed prematurely before ServerHelloDone")
                return RawTLS12Result(receivedServerChangeCipherSpec: false, fatalAlertDescription: nil)
            }
            if ctype == 0x15 && payload.count >= 2 {
                XCTFail("Unexpected alert before ServerHelloDone: level=\(payload[0]) desc=\(payload[1])")
                return RawTLS12Result(receivedServerChangeCipherSpec: false, fatalAlertDescription: payload[1])
            }
            XCTAssertEqual(ctype, 0x16)
            serverStream.append(payload)

            while serverStream.count >= 4 {
                let s = serverStream.startIndex
                let hType = serverStream[s]
                let hLen = (Int(serverStream[s + 1]) << 16) | (Int(serverStream[s + 2]) << 8) | Int(serverStream[s + 3])
                guard serverStream.count >= 4 + hLen else { break }

                let msg = Data(serverStream[s..<(s + 4 + hLen)])
                let body = Data(serverStream[(s + 4)..<(s + 4 + hLen)])
                serverStream.removeFirst(4 + hLen)
                transcript.append(msg)

                if hType == 0x02 && body.count >= 34 {
                    serverRandom = body.subdata(in: 2..<34)
                } else if hType == 0x0B && body.count >= 6 {
                    let firstLen = (Int(body[3]) << 16) | (Int(body[4]) << 8) | Int(body[5])
                    if 6 + firstLen <= body.count {
                        serverCertDER = body.subdata(in: 6..<(6 + firstLen))
                    }
                } else if hType == 0x0D {
                    sawCertificateRequest = true
                } else if hType == 0x0E {
                    sawServerHelloDone = true
                }
            }
        }

        XCTAssertTrue(sawCertificateRequest, "Server must send CertificateRequest")
        let srvRandom = try XCTUnwrap(serverRandom)
        let srvDER = try XCTUnwrap(serverCertDER)
        let srvCert = try XCTUnwrap(SecCertificateCreateWithData(nil, srvDER as CFData))
        let srvPubKey = try XCTUnwrap(SecCertificateCopyKey(srvCert))

        // 1. Client Certificate
        let cLen = clientCertDER.count
        let listLen = cLen + 3
        var certBody = Data([
            UInt8((listLen >> 16) & 0xFF), UInt8((listLen >> 8) & 0xFF), UInt8(listLen & 0xFF),
            UInt8((cLen >> 16) & 0xFF), UInt8((cLen >> 8) & 0xFF), UInt8(cLen & 0xFF)
        ])
        certBody.append(clientCertDER)
        let certMsg = wrapHandshake(type: 0x0B, body: certBody)
        transcript.append(certMsg)

        // 2. RSA ClientKeyExchange
        var premaster = Data([0x03, 0x03])
        premaster.append(Data(repeating: 0x42, count: 46))
        var errRef: Unmanaged<CFError>?
        let encPremaster = try XCTUnwrap(
            SecKeyCreateEncryptedData(srvPubKey, .rsaEncryptionPKCS1, premaster as CFData, &errRef) as Data?
        )
        var ckeBody = Data([UInt8((encPremaster.count >> 8) & 0xFF), UInt8(encPremaster.count & 0xFF)])
        ckeBody.append(encPremaster)
        let ckeMsg = wrapHandshake(type: 0x10, body: ckeBody)
        transcript.append(ckeMsg)

        // 3. CertificateVerify: sign real `transcript` with `clientKeyURL`, optionally flipping 1 byte!
        var sig = try opensslRSASHA256Sign(data: transcript, keyURL: clientKeyURL)
        if forgeCertificateVerify {
            sig[0] ^= 0xFF
        }
        var cvBody = Data([0x04, 0x01, UInt8((sig.count >> 8) & 0xFF), UInt8(sig.count & 0xFF)])
        cvBody.append(sig)
        let cvMsg = wrapHandshake(type: 0x0F, body: cvBody)
        transcript.append(cvMsg)

        // 4. Derive TLS 1.2 master secret, client AES-128-GCM write key/IV, and encrypted Finished!
        let masterSecret = tls12PRFSHA256(secret: premaster, label: "master secret", seed: clientRandom + srvRandom, length: 48)
        let keyBlock = tls12PRFSHA256(secret: masterSecret, label: "key expansion", seed: srvRandom + clientRandom, length: 40)
        let clientWriteKey = SymmetricKey(data: keyBlock.subdata(in: 0..<16))
        let clientWriteIV = keyBlock.subdata(in: 32..<36)

        let transcriptHash = Data(SHA256.hash(data: transcript))
        let verifyData = tls12PRFSHA256(secret: masterSecret, label: "client finished", seed: transcriptHash, length: 12)
        let finishedPlain = wrapHandshake(type: 0x14, body: verifyData)

        let explicitNonce = Data(repeating: 0, count: 8) // seq_num = 0
        let gcmNonce = try AES.GCM.Nonce(data: clientWriteIV + explicitNonce)
        var aad = explicitNonce
        aad.append(contentsOf: [0x16, 0x03, 0x03, UInt8((finishedPlain.count >> 8) & 0xFF), UInt8(finishedPlain.count & 0xFF)])
        let sealed = try AES.GCM.seal(finishedPlain, using: clientWriteKey, nonce: gcmNonce, authenticating: aad)
        let encFinishedPayload = explicitNonce + sealed.ciphertext + sealed.tag

        var flight = Data()
        flight.append(wrapTLSRecord(contentType: 0x16, payload: certMsg))
        flight.append(wrapTLSRecord(contentType: 0x16, payload: ckeMsg))
        flight.append(wrapTLSRecord(contentType: 0x16, payload: cvMsg))
        flight.append(wrapTLSRecord(contentType: 0x14, payload: Data([0x01]))) // ChangeCipherSpec
        flight.append(wrapTLSRecord(contentType: 0x16, payload: encFinishedPayload))
        sendRaw(fd: fd, data: flight)

        guard let (ctype, payload) = readTLSRecord(fd: fd) else {
            return RawTLS12Result(receivedServerChangeCipherSpec: false, fatalAlertDescription: nil)
        }
        if ctype == 0x15 && payload.count >= 2 && payload[0] == 0x02 {
            return RawTLS12Result(receivedServerChangeCipherSpec: false, fatalAlertDescription: payload[1])
        }
        if ctype == 0x14 {
            return RawTLS12Result(receivedServerChangeCipherSpec: true, fatalAlertDescription: nil)
        }
        return RawTLS12Result(receivedServerChangeCipherSpec: false, fatalAlertDescription: nil)
    }

    private func tls12PRFSHA256(secret: Data, label: String, seed: Data, length: Int) -> Data {
        let key = SymmetricKey(data: secret)
        let labelAndSeed = Data(label.utf8) + seed
        var out = Data()
        var a = Data(HMAC<SHA256>.authenticationCode(for: labelAndSeed, using: key))
        while out.count < length {
            let block = Data(HMAC<SHA256>.authenticationCode(for: a + labelAndSeed, using: key))
            out.append(block)
            a = Data(HMAC<SHA256>.authenticationCode(for: a, using: key))
        }
        return out.prefix(length)
    }

    private func opensslRSASHA256Sign(data: Data, keyURL: URL) throws -> Data {
        let tmpIn = FileManager.default.temporaryDirectory.appendingPathComponent("tls-cv-\(UUID().uuidString).bin")
        try data.write(to: tmpIn)
        defer { try? FileManager.default.removeItem(at: tmpIn) }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        p.arguments = ["dgst", "-sha256", "-sign", keyURL.path, tmpIn.path]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let sig = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        XCTAssertEqual(p.terminationStatus, 0)
        return sig
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
