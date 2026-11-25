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
    var issuedTickets: [String: (clientIP: String, certFP: String, deviceName: String, deviceId: String, autoAllow: Bool)] = [:]
    var cancelledIPs: [String] = []
    var revokedTickets: [String] = []
    var handledRequestCount: Int = 0
    var issuedTicketCount: Int = 0
    var issueDelayNs: UInt64 = 0
    var sawTaskCancelledDuringIssue: Bool = false

    init(snapshot: ControlProtocolSnapshot) {
        self.snapshot = snapshot
    }

    func setIssueDelay(nanoseconds: UInt64) {
        issueDelayNs = nanoseconds
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
        streamPaired: Bool,
        clientAddress: String,
        peerCertFingerprintSHA256: String,
        autoAllowFuture: Bool,
        approvedViaAutoAllow: Bool,
        identityChanged: Bool
    ) async -> PairTicketIssueResult {
        if issueDelayNs > 0 {
            try? await Task.sleep(nanoseconds: issueDelayNs)
        }
        if Task.isCancelled {
            sawTaskCancelledDuringIssue = true
        }
        handledRequestCount += 1
        issuedTicketCount += 1
        guard snapshot.streamReady else {
            return .streamNotReady(reason: snapshot.streamNotReadyReason ?? "不可用")
        }
        let fp = peerCertFingerprintSHA256.uppercased()
        let next = (streamPaired && engineUniquelyPairedFingerprints.contains(fp)) ? "done" : "gamestream"
        let ticket = "tkt-\(UUID().uuidString.prefix(8))"
        issuedTickets[ticket] = (IPAddressNormalizer.normalize(clientAddress), fp, deviceName, deviceId, autoAllowFuture)
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
        let fp = peerCertFingerprintSHA256.uppercased()
        guard entry.clientIP == IPAddressNormalizer.normalize(clientAddress), entry.certFP == fp else {
            return .sourceMismatch
        }
        guard engineUniquelyPairedFingerprints.contains(fp) else { return .notPaired }
        issuedTickets.removeValue(forKey: ticket)
        authorizedFingerprints.insert(fp)
        return .completed(mediaPort: snapshot.mediaPort)
    }

    func cancelPendingEnginePairings(forClientAddress clientAddress: String) async {
        cancelledIPs.append(IPAddressNormalizer.normalize(clientAddress))
    }

    func revokeIssuedPairingTicket(_ ticket: String, forClientAddress clientAddress: String) async {
        revokedTickets.append(ticket)
        issuedTickets.removeValue(forKey: ticket)
        cancelledIPs.append(IPAddressNormalizer.normalize(clientAddress))
    }

    func getCancelledIPs() -> [String] { cancelledIPs }
    func getRevokedTickets() -> [String] { revokedTickets }
    func getIssuedTicketCount() -> Int { issuedTicketCount }
    func getSawTaskCancelledDuringIssue() -> Bool { sawTaskCancelledDuringIssue }
    func clearCancelledIPs() { cancelledIPs.removeAll() }
}

final class ControlProtocolTests: XCTestCase {
    private let sampleCertA = Data("sample-headset-cert-A".utf8)
    private let sampleCertB = Data("sample-headset-cert-B".utf8)
    private let sampleCertC = Data("sample-headset-cert-C".utf8)

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

    /// B-1 + P6: `/pair` without client cert returns `401 client_cert_required`;
    /// with cert when engine not ready returns `503 stream_not_ready` without prompting.
    func testPairRequiresClientCertAndReturns503StreamNotReadyWhenEngineDown() async throws {
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: false,
            streamNotReadyReason: HostStrings.string("protocol.stream.port_conflict"),
            mediaPort: nil, webDAVCredentials: nil
        )
        let coord = PairingCoordinator(timeout: 5.0)
        let router = ControlProtocolRouter(pairingCoordinator: coord, delegate: MockControlDelegate(snapshot: snap))

        // No client cert -> 401 client_cert_required
        let noCertResp = await router.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-1\",\"pin\":\"1234\",\"streamPaired\":false}".utf8),
            peerCertificateDER: nil,
            remoteAddress: "192.168.1.40"
        ))
        XCTAssertEqual(noCertResp.statusCode, 401)
        let noCertJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: noCertResp.body) as? [String: Any])
        XCTAssertEqual(noCertJSON["error"] as? String, "client_cert_required")

        // With client cert while engine down -> 503 stream_not_ready
        let resp = await router.handle(HTTPRequest(
            method: "POST",
            rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-1\",\"pin\":\"1234\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertA,
            remoteAddress: "192.168.1.40"
        ))
        XCTAssertEqual(resp.statusCode, 503)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: resp.body) as? [String: Any])
        XCTAssertEqual(json["error"] as? String, "stream_not_ready")
        XCTAssertNotNil(json["reason"] as? String)
        let prompts = await coord.pendingPrompts()
        XCTAssertTrue(prompts.isEmpty)
    }

    /// Covers B-1/P1-P6 + Round-5 #2 multi-IP prompt fairness:
    /// - Exact protocol error codes + ticket bound to BOTH IP and client cert fingerprint
    /// - `streamPaired: true` vs `false` decision for `next` (`done` vs `gamestream`)
    /// - Multi-IP fairness: two collaborating attacker IPs (A & B) that get rejected CANNOT starve clean IP C!
    func testTwoStepPairErrorsTicketBindingMultiIPFairnessAndCertAutoAllow() async throws {
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
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-1\",\"pin\":\"12ab\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.10"
        ))
        XCTAssertEqual(badResp.statusCode, 400)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: badResp.body) as? [String: Any])?["error"] as? String, "invalid_request")

        // 2. Timeout -> 408 timeout + cancels engine pending pairing + 429 cooldown with retryAfterSec
        let timeoutBody = Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-timeout\",\"pin\":\"1234\",\"streamPaired\":false}".utf8)
        let timeoutResp = await router.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: timeoutBody, peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.11"
        ))
        XCTAssertEqual(timeoutResp.statusCode, 408)
        let cancelledAfterTimeout = await delegate.getCancelledIPs()
        XCTAssertTrue(cancelledAfterTimeout.contains("192.168.1.11"))

        let cooldownResp = await router.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: timeoutBody, peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.11"
        ))
        XCTAssertEqual(cooldownResp.statusCode, 429)
        let cdJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: cooldownResp.body) as? [String: Any])
        XCTAssertEqual(cdJSON["error"] as? String, "cooldown")
        XCTAssertGreaterThanOrEqual(cdJSON["retryAfterSec"] as? Int ?? 0, 1)

        // 3. Single prompt + busy + IP-only cooldown + ticket bound to BOTH IP and cert fingerprint
        let coord2 = PairingCoordinator(timeout: 5.0, cooldownDuration: 5.0, fairnessYieldDuration: 0.0)
        let router2 = ControlProtocolRouter(pairingCoordinator: coord2, delegate: delegate)

        let attackerTask = Task {
            await router2.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Spoofer\",\"deviceId\":\"victim-device\",\"pin\":\"4321\",\"streamPaired\":false}".utf8),
                peerCertificateDER: sampleCertB, remoteAddress: "::ffff:192.168.1.20"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let activePrompts = await coord2.pendingPrompts()
        XCTAssertEqual(activePrompts.count, 1)

        let busyResp = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO B\",\"deviceId\":\"dev-other\",\"pin\":\"1111\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertC, remoteAddress: "192.168.1.21"
        ))
        XCTAssertEqual(busyResp.statusCode, 429)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: busyResp.body) as? [String: Any])?["error"] as? String, "busy")

        await coord2.reject(promptId: activePrompts[0].id)
        let attackerResp = await attackerTask.value
        XCTAssertEqual(attackerResp.statusCode, 403)

        // Real headset on different IP with same deviceId succeeds -> ticket bound to IP + sampleCertA
        let realHeadsetTask = Task {
            await router2.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Real PICO\",\"deviceId\":\"victim-device\",\"pin\":\"5678\",\"streamPaired\":false}".utf8),
                peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.50"
            ))
        }
        try await Task.sleep(nanoseconds: 50_000_000)
        let realPrompts = await coord2.pendingPrompts()
        XCTAssertEqual(realPrompts.count, 1)
        await coord2.approve(promptId: realPrompts[0].id, rememberAutoAllow: false)
        let realHeadsetResp = await realHeadsetTask.value
        XCTAssertEqual(realHeadsetResp.statusCode, 200)
        let approved = try JSONDecoder().decode(PairApprovedResponse.self, from: realHeadsetResp.body)
        XCTAssertEqual(approved.next, "gamestream")

        // `/pair/complete` with DIFFERENT cert from SAME IP -> 403 source_mismatch (ticket bound to cert FP!)
        let compWrongCert = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\",\"deviceId\":\"victim-device\",\"device\":\"Real PICO\"}".utf8),
            peerCertificateDER: sampleCertB, remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compWrongCert.statusCode, 403)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: compWrongCert.body) as? [String: Any])?["error"] as? String, "source_mismatch")

        // Same IP + same cert before engine paired -> 409 not_paired; after paired -> 200; reuse -> 410
        let compNotYet = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\",\"deviceId\":\"victim-device\",\"device\":\"Real PICO\"}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compNotYet.statusCode, 409)

        await delegate.setEnginePairedOnly(fingerprint: TLSIdentityManager.sha256Hex(sampleCertA))
        let compOK = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\",\"deviceId\":\"victim-device\",\"device\":\"Real PICO\"}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compOK.statusCode, 200)

        let compReuse = await router2.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"\(approved.ticket)\"}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.50"
        ))
        XCTAssertEqual(compReuse.statusCode, 410)

        // 4. Multi-IP prompt fairness (Round-5 #2):
        //    Attacker A & Attacker B (with short 0.05s cooldown) try to alternate occupying the prompt
        //    while clean Legit C is waiting -> rejected A & B MUST yield priority to clean Legit C!
        let multiCoord = PairingCoordinator(
            timeout: 5.0,
            cooldownDuration: 0.05,
            fairnessYieldDuration: 5.0,
            recentFailurePenaltyWindow: 60.0
        )
        let multiRouter = ControlProtocolRouter(pairingCoordinator: multiCoord, delegate: delegate)

        let taskA1 = Task {
            await multiRouter.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Attacker A\",\"deviceId\":\"a1\",\"pin\":\"1111\",\"streamPaired\":false}".utf8),
                peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.101"
            ))
        }
        try await Task.sleep(nanoseconds: 40_000_000)
        let pendingA1 = await multiCoord.pendingPrompts()
        let promptA1 = try XCTUnwrap(pendingA1.first)

        // While A holds prompt, B (102) arrives -> busy (waiter #1)
        let busyB = await multiRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Attacker B\",\"deviceId\":\"b1\",\"pin\":\"2222\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertB, remoteAddress: "192.168.1.102"
        ))
        XCTAssertEqual(busyB.statusCode, 429)

        // Reject A -> A is now penalized; B takes turn
        await multiCoord.reject(promptId: promptA1.id)
        _ = await taskA1.value
        try await Task.sleep(nanoseconds: 70_000_000) // A's short cooldown expired, but A stays in recentRejectOrTimeoutAt!

        let taskB1 = Task {
            await multiRouter.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Attacker B\",\"deviceId\":\"b1\",\"pin\":\"2222\",\"streamPaired\":false}".utf8),
                peerCertificateDER: sampleCertB, remoteAddress: "192.168.1.102"
            ))
        }
        try await Task.sleep(nanoseconds: 40_000_000)
        let pendingB1 = await multiCoord.pendingPrompts()
        let promptB1 = try XCTUnwrap(pendingB1.first)

        // While B holds prompt: A (penalized!) arrives first, THEN clean C (103) arrives second!
        let busyA2 = await multiRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Attacker A\",\"deviceId\":\"a2\",\"pin\":\"1111\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.101"
        ))
        XCTAssertEqual(busyA2.statusCode, 429)

        let busyC = await multiRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Legit C\",\"deviceId\":\"c1\",\"pin\":\"3333\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertC, remoteAddress: "192.168.1.103"
        ))
        XCTAssertEqual(busyC.statusCode, 429)

        // Reject B -> even though A arrived earlier than C, A was recently rejected while C is clean,
        // so priority turn MUST be reserved for clean Legit C!
        await multiCoord.reject(promptId: promptB1.id)
        _ = await taskB1.value

        // A trying to cut in immediately MUST be deferred with 429 busy!
        let cutInA = await multiRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Attacker A\",\"deviceId\":\"a3\",\"pin\":\"1111\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.101"
        ))
        XCTAssertEqual(cutInA.statusCode, 429, "Round-5 #2: Penalized IP A must yield to waiting clean IP C")

        // Clean Legit C immediately claims the prompt!
        let taskC = Task {
            await multiRouter.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"Legit C\",\"deviceId\":\"c1\",\"pin\":\"3333\",\"streamPaired\":false}".utf8),
                peerCertificateDER: sampleCertC, remoteAddress: "192.168.1.103"
            ))
        }
        try await Task.sleep(nanoseconds: 40_000_000)
        let pendingC = await multiCoord.pendingPrompts()
        let promptC = try XCTUnwrap(pendingC.first)
        XCTAssertEqual(promptC.clientAddress, "192.168.1.103")
        await multiCoord.approve(promptId: promptC.id, rememberAutoAllow: false)
        let respC = await taskC.value
        XCTAssertEqual(respC.statusCode, 200)

        // 5. Auto-allow + `streamPaired: true` vs `false` decision
        let certAFp = TLSIdentityManager.sha256Hex(sampleCertA)
        await delegate.setAuthorized(deviceId: "dev-ok", fingerprint: certAFp)
        let coord3 = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0, autoAllowedFingerprints: [certAFp])
        let router3 = ControlProtocolRouter(pairingCoordinator: coord3, delegate: delegate)

        // Auto-allowed cert with `streamPaired: true` -> next:"done"
        let autoDone = await router3.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-ok\",\"pin\":\"9999\",\"streamPaired\":true}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.32"
        ))
        XCTAssertEqual(autoDone.statusCode, 200)
        XCTAssertEqual(try JSONDecoder().decode(PairApprovedResponse.self, from: autoDone.body).next, "done")

        // Same cert with `streamPaired: false` -> next:"gamestream"!
        let autoGS = await router3.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"PICO 4\",\"deviceId\":\"dev-ok\",\"pin\":\"9999\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.32"
        ))
        XCTAssertEqual(autoGS.statusCode, 200)
        XCTAssertEqual(try JSONDecoder().decode(PairApprovedResponse.self, from: autoGS.body).next, "gamestream")

        // 6. Round-6 #4: Repeated disconnect-cancellations from same IP (3rd within 60s) trigger IP cooldown!
        let cancelCoord = PairingCoordinator(
            timeout: 10.0, cooldownDuration: 30.0, fairnessYieldDuration: 0.0,
            cancelWindowSeconds: 60.0, cancelCooldownThreshold: 3
        )
        let cancelRouter = ControlProtocolRouter(pairingCoordinator: cancelCoord, delegate: delegate)
        let floodIP = "192.168.1.210"
        for attempt in 1...3 {
            let t = Task {
                await cancelRouter.handle(HTTPRequest(
                    method: "POST", rawTarget: "/depth3d/v1/pair",
                    body: Data("{\"device\":\"Flasher\",\"deviceId\":\"fl-\(attempt)\",\"pin\":\"1234\",\"streamPaired\":false}".utf8),
                    peerCertificateDER: sampleCertC, remoteAddress: floodIP
                ))
            }
            var opened = false
            let dl = Date().addingTimeInterval(2.0)
            while Date() < dl {
                if !(await cancelCoord.pendingPrompts()).isEmpty { opened = true; break }
                try await Task.sleep(nanoseconds: 20_000_000)
            }
            XCTAssertTrue(opened, "Attempt \(attempt) before cooldown threshold must open prompt")
            t.cancel()
            _ = await t.value
        }
        // 4th attempt after 3rd cancel within 60s MUST immediately hit 429 cooldown!
        let after3Cancels = await cancelRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair",
            body: Data("{\"device\":\"Flasher\",\"deviceId\":\"fl-4\",\"pin\":\"1234\",\"streamPaired\":false}".utf8),
            peerCertificateDER: sampleCertC, remoteAddress: floodIP
        ))
        XCTAssertEqual(after3Cancels.statusCode, 429, "Round-6 #4: 3rd disconnect-cancel in 60s must trigger IP cooldown")
        let cancelCDJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: after3Cancels.body) as? [String: Any])
        XCTAssertEqual(cancelCDJSON["error"] as? String, "cooldown")

        // 7. Round-6 #1: Post-approval issuance runs uncancellable, then revokes ticket if client cancelled mid-issue!
        let slowDelegate = MockControlDelegate(snapshot: snap)
        await slowDelegate.setIssueDelay(nanoseconds: 150_000_000)
        await slowDelegate.setAuthorized(deviceId: "slow-dev", fingerprint: certAFp)
        let slowCoord = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0, autoAllowedFingerprints: [certAFp])
        let slowRouter = ControlProtocolRouter(pairingCoordinator: slowCoord, delegate: slowDelegate)
        let cancelDuringIssue = Task {
            await slowRouter.handle(HTTPRequest(
                method: "POST", rawTarget: "/depth3d/v1/pair",
                body: Data("{\"device\":\"PICO\",\"deviceId\":\"slow-dev\",\"pin\":\"1234\",\"streamPaired\":false}".utf8),
                peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.211"
            ))
        }
        try await Task.sleep(nanoseconds: 40_000_000)
        cancelDuringIssue.cancel()
        let cancelIssueResp = await cancelDuringIssue.value
        XCTAssertEqual(cancelIssueResp.statusCode, 403)
        let sawCancelInsideIssue = await slowDelegate.getSawTaskCancelledDuringIssue()
        XCTAssertFalse(sawCancelInsideIssue, "Round-6 #1: issueApprovedPairingTicket must run inside uncancellable Task")
        let revoked = await slowDelegate.getRevokedTickets()
        XCTAssertEqual(revoked.count, 1, "Round-6 #1: Ticket issued for already-disconnected request must be revoked immediately")

        // 8. Round-6 minor: 410 ticket_expired on `/pair/complete` MUST NOT cancel pending engine pairings on that IP!
        await slowDelegate.clearCancelledIPs()
        let expiredComplete = await slowRouter.handle(HTTPRequest(
            method: "POST", rawTarget: "/depth3d/v1/pair/complete",
            body: Data("{\"ticket\":\"already-consumed-ticket\"}".utf8),
            peerCertificateDER: sampleCertA, remoteAddress: "192.168.1.212"
        ))
        XCTAssertEqual(expiredComplete.statusCode, 410)
        let cancelledAfter410 = await slowDelegate.getCancelledIPs()
        XCTAssertFalse(cancelledAfter410.contains("192.168.1.212"), "Round-6 minor: 410 on /pair/complete must not cancel active pairing session on that IP")
    }

    /// P3 + B-1: `/depth3d/v1/media` authenticates strictly by TLS client certificate fingerprint,
    /// does NOT require `X-Depth3d-Device`, and returns `{"webdav":null}` when media service is disabled.
    func testMediaEndpointAuthWithoutDeviceHeaderAndNullWhenDisabled() async throws {
        let creds = WebDAVCredentials(user: "depth3d", pass: "rndpass99")
        let snap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: true, streamNotReadyReason: nil, mediaPort: 8890, webDAVCredentials: creds
        )
        let delegate = MockControlDelegate(snapshot: snap)
        let fp = TLSIdentityManager.sha256Hex(sampleCertA)
        await delegate.setAuthorized(deviceId: "pico-01", fingerprint: fp)

        let router = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: delegate)
        let r1 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media"))
        XCTAssertEqual(r1.statusCode, 401)
        let r2 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media", peerCertificateDER: sampleCertB))
        XCTAssertEqual(r2.statusCode, 401)

        let r3 = await router.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media", peerCertificateDER: sampleCertA))
        XCTAssertEqual(r3.statusCode, 200)
        let decoded = try JSONDecoder().decode(MediaCredentialsResponse.self, from: r3.body)
        XCTAssertEqual(decoded.webdav?.port, 8890)
        XCTAssertEqual(decoded.webdav?.pass, "rndpass99")

        // When media service is off -> 200 {"webdav":null}
        let offSnap = ControlProtocolSnapshot(
            hostName: "Mac", hostId: "id", osDescription: "macOS 15.0",
            streamReady: true, streamNotReadyReason: nil, mediaPort: nil, webDAVCredentials: nil
        )
        let offDelegate = MockControlDelegate(snapshot: offSnap)
        await offDelegate.setAuthorized(deviceId: "pico-01", fingerprint: fp)
        let offRouter = ControlProtocolRouter(pairingCoordinator: PairingCoordinator(), delegate: offDelegate)
        let rOff = await offRouter.handle(HTTPRequest(method: "GET", rawTarget: "/depth3d/v1/media", peerCertificateDER: sampleCertA))
        XCTAssertEqual(rOff.statusCode, 200)
        let offJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: rOff.body) as? [String: Any])
        XCTAssertTrue(offJSON["webdav"] is NSNull, "B-1: /media must return {\"webdav\":null} when media service is disabled")
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

    /// Covers live mTLS, S-3 (IPv4-only), S-5 (per-IP cap + unknown self-signed cert rate limit),
    /// B-1 (client disconnect immediately closes confirmation prompt without issuing a ticket!),
    /// and Round-5 #2 (reserved connection slots for paired client certificates!).
    func testControlHTTPSServerIPv4DisconnectPromptDismissalReservedPairedSlotsAndRateLimit() async throws {
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

        let coord = PairingCoordinator(timeout: 20.0, cooldownDuration: 0.0)
        let router = ControlProtocolRouter(pairingCoordinator: coord, delegate: delegate)
        let server = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 16,
            maxConnectionsPerIP: 8,
            reservedPairedSlots: 4,
            unauthRateLimitPerWindow: 20
        )
        let port = try server.start(port: 0, fallbackToEphemeral: false)
        defer { server.stop() }

        // 1. S-3: IPv4 succeeds, IPv6 ::1 refused
        XCTAssertEqual(runCurl(["-sk", "--max-time", "5", "https://127.0.0.1:\(port)/depth3d/v1/info"]).0, 200)
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
        XCTAssertNotEqual(v6RC, 0, "S-3: ControlHTTPSServer must bind IPv4-only")

        // 1b. Round-6 minor: Client half-closing its write side (`SHUT_WR`) right after sending `/info`
        //     MUST still receive the full HTTP 200 response (disconnect monitor is ONLY for `/pair`)!
        let halfCloseResponse = try sendHTTPSGetAndHalfCloseWrite(port: port, path: "/depth3d/v1/info")
        XCTAssertTrue(halfCloseResponse.hasPrefix("HTTP/1.1 200 OK"), "Half-closed /info must still return 200, got: \(halfCloseResponse)")
        XCTAssertTrue(halfCloseResponse.contains("\"v\":1"))

        // 2. B-1 + Round-6 #3: Client disconnects while confirmation prompt is open -> prompt MUST close
        //    immediately & issue NO ticket! Uses ProcessExitWaiter (terminationHandler + timeout, never waitUntilExit).
        let ticketsBeforeDisconnect = await delegate.getIssuedTicketCount()
        let disconnectProc = Process()
        disconnectProc.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        disconnectProc.arguments = [
            "--noproxy", "*", "-sk", "--max-time", "20", "-X", "POST",
            "--cert", unknownCert.path, "--key", unknownKey.path,
            "-H", "Content-Type: application/json",
            "-d", "{\"device\":\"Dropping PICO\",\"deviceId\":\"drop-dev\",\"pin\":\"1234\",\"streamPaired\":false}",
            "https://127.0.0.1:\(port)/depth3d/v1/pair"
        ]
        disconnectProc.standardOutput = FileHandle.nullDevice
        disconnectProc.standardError = FileHandle.nullDevice
        let disconnectWaiter = ProcessExitWaiter(process: disconnectProc)
        try disconnectProc.run()

        // Wait until confirmation prompt opens
        var promptOpened = false
        let openDeadline = Date().addingTimeInterval(4.0)
        while Date() < openDeadline {
            if !(await coord.pendingPrompts()).isEmpty {
                promptOpened = true
                break
            }
            try await Task.sleep(nanoseconds: 40_000_000)
        }
        XCTAssertTrue(promptOpened, "Confirmation prompt should open for dropping client")

        // Kill client (TCP FIN/RST) while prompt is still waiting, waiting via terminationHandler + timeout!
        let exitedCleanly = await disconnectWaiter.terminateAndWaitAsync(timeout: 3.0)
        XCTAssertTrue(exitedCleanly, "disconnectProc should exit cleanly within timeout")

        // Prompt MUST dismiss promptly (well before 20s timeout!) and NO ticket may be issued!
        var promptDismissed = false
        let dismissDeadline = Date().addingTimeInterval(3.0)
        while Date() < dismissDeadline {
            if (await coord.pendingPrompts()).isEmpty {
                promptDismissed = true
                break
            }
            try await Task.sleep(nanoseconds: 40_000_000)
        }
        XCTAssertTrue(promptDismissed, "B-1: Closing TCP connection while prompt is open must immediately dismiss prompt")
        let ticketsAfterDisconnect = await delegate.getIssuedTicketCount()
        XCTAssertEqual(ticketsAfterDisconnect, ticketsBeforeDisconnect, "B-1: Disconnected request must NEVER issue a ticket")

        // 3. S-5 + Round-6 #5: Unknown self-signed cert is rate-limited with 429 {"error":"busy","retryAfterSec":...}; paired cert is exempt
        let rlServer = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 16,
            maxConnectionsPerIP: 8,
            reservedPairedSlots: 4,
            unauthRateLimitPerWindow: 2,
            unauthRateWindowSeconds: 60.0
        )
        let rlPort = try rlServer.start(port: 0, fallbackToEphemeral: false)
        defer { rlServer.stop() }
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "--cert", unknownCert.path, "--key", unknownKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "--cert", unknownCert.path, "--key", unknownKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/info"]).0, 200)
        let (rlCode, rlBody) = runCurl(["-sk", "--max-time", "3", "--cert", unknownCert.path, "--key", unknownKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/info"])
        XCTAssertEqual(rlCode, 429)
        let rlJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(rlBody.utf8)) as? [String: Any])
        XCTAssertEqual(rlJSON["error"] as? String, "busy")
        XCTAssertNotNil(rlJSON["retryAfterSec"] as? Int)
        XCTAssertEqual(runCurl(["-sk", "--max-time", "3", "--cert", pairedCert.path, "--key", pairedKey.path, "https://127.0.0.1:\(rlPort)/depth3d/v1/media"]).0, 200)

        // 4. Round-5 #2 + Round-6 #5: Reserved paired-cert connection slots!
        //    maxConnections=4, reservedPairedSlots=2 => unauthCeiling=2.
        //    Fill 2 unauthenticated slots with raw idle TCP connections -> 3rd unauth `/info` gets 429 {"error":"busy","retryAfterSec":...}
        //    (NEVER 503, which is reserved for stream_not_ready), while paired client cert `/media` STILL gets 200!
        let resServer = ControlHTTPSServer(
            identity: serverId.secIdentity,
            router: router,
            maxConnections: 4,
            maxConnectionsPerIP: 4,
            reservedPairedSlots: 2,
            handshakeTimeoutSec: 5.0,
            reservedZoneHandshakeTimeoutSec: 1.5,
            readTimeoutSec: 5.0
        )
        let resPort = try resServer.start(port: 0, fallbackToEphemeral: false)
        defer { resServer.stop() }

        let u1 = connectLoopback(port: resPort)
        let u2 = connectLoopback(port: resPort)
        XCTAssertGreaterThanOrEqual(u1, 0)
        XCTAssertGreaterThanOrEqual(u2, 0)
        defer { Darwin.close(u1); Darwin.close(u2) }
        try await Task.sleep(nanoseconds: 150_000_000)

        // 3rd unauthenticated client hits the reserved zone -> 429 {"error":"busy","retryAfterSec":...}!
        let (unauthOverCode, unauthOverBody) = runCurl([
            "-sk", "--max-time", "3",
            "--cert", unknownCert.path, "--key", unknownKey.path,
            "https://127.0.0.1:\(resPort)/depth3d/v1/info"
        ])
        XCTAssertEqual(unauthOverCode, 429, "Round-6 #5: Reserved-slot rejection must return 429 busy (not 503)")
        let overJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(unauthOverBody.utf8)) as? [String: Any])
        XCTAssertEqual(overJSON["error"] as? String, "busy")
        XCTAssertGreaterThanOrEqual(overJSON["retryAfterSec"] as? Int ?? 0, 1)

        // Bound-fingerprint paired client STILL succeeds with 200 via reserved paired slot!
        let (pairedReservedCode, _) = runCurl([
            "-sk", "--max-time", "3",
            "--cert", pairedCert.path, "--key", pairedKey.path,
            "https://127.0.0.1:\(resPort)/depth3d/v1/media"
        ])
        XCTAssertEqual(pairedReservedCode, 200, "Round-5 #2: Paired client cert must succeed using reserved connection slots")
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
            reservedPairedSlots: 0,
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
        XCTAssertEqual(code, 200)
    }

    /// m-2: Cryptographically proves `ControlHTTPSServer` verifies `CertificateVerify` private-key
    /// possession via valid vs 1-byte-forged raw TLS 1.2 RSA-AES-128-GCM-SHA256 handshakes.
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

        let validResult = try runRawTLS12Handshake(
            port: port, clientCertDER: clientDER, clientKeyURL: clientKey, forgeCertificateVerify: false
        )
        XCTAssertTrue(validResult.receivedServerChangeCipherSpec)
        XCTAssertNil(validResult.fatalAlertDescription)

        let forgedResult = try runRawTLS12Handshake(
            port: port, clientCertDER: clientDER, clientKeyURL: clientKey, forgeCertificateVerify: true
        )
        XCTAssertFalse(forgedResult.receivedServerChangeCipherSpec)
        XCTAssertEqual(forgedResult.fatalAlertDescription, 51) // decrypt_error
        let handledAfterForge = await delegate.handledRequestCount
        XCTAssertEqual(handledAfterForge, 0)
    }

    // MARK: - Raw TLS 1.2 RSA-AES-128-GCM-SHA256 Probe

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

        var chBody = Data([0x03, 0x03])
        chBody.append(clientRandom)
        chBody.append(0x00)
        chBody.append(contentsOf: [0x00, 0x02, 0x00, 0x9C])
        chBody.append(contentsOf: [0x01, 0x00])
        let sigAlgExt: [UInt8] = [0x00, 0x0D, 0x00, 0x04, 0x00, 0x02, 0x04, 0x01]
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
                    if 6 + firstLen <= body.count { serverCertDER = body.subdata(in: 6..<(6 + firstLen)) }
                } else if hType == 0x0D {
                    sawCertificateRequest = true
                } else if hType == 0x0E {
                    sawServerHelloDone = true
                }
            }
        }

        XCTAssertTrue(sawCertificateRequest)
        let srvRandom = try XCTUnwrap(serverRandom)
        let srvDER = try XCTUnwrap(serverCertDER)
        let srvCert = try XCTUnwrap(SecCertificateCreateWithData(nil, srvDER as CFData))
        let srvPubKey = try XCTUnwrap(SecCertificateCopyKey(srvCert))

        let cLen = clientCertDER.count
        let listLen = cLen + 3
        var certBody = Data([
            UInt8((listLen >> 16) & 0xFF), UInt8((listLen >> 8) & 0xFF), UInt8(listLen & 0xFF),
            UInt8((cLen >> 16) & 0xFF), UInt8((cLen >> 8) & 0xFF), UInt8(cLen & 0xFF)
        ])
        certBody.append(clientCertDER)
        let certMsg = wrapHandshake(type: 0x0B, body: certBody)
        transcript.append(certMsg)

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

        var sig = try opensslRSASHA256Sign(data: transcript, keyURL: clientKeyURL)
        if forgeCertificateVerify { sig[0] ^= 0xFF }
        var cvBody = Data([0x04, 0x01, UInt8((sig.count >> 8) & 0xFF), UInt8(sig.count & 0xFF)])
        cvBody.append(sig)
        let cvMsg = wrapHandshake(type: 0x0F, body: cvBody)
        transcript.append(cvMsg)

        let masterSecret = tls12PRFSHA256(secret: premaster, label: "master secret", seed: clientRandom + srvRandom, length: 48)
        let keyBlock = tls12PRFSHA256(secret: masterSecret, label: "key expansion", seed: srvRandom + clientRandom, length: 40)
        let clientWriteKey = SymmetricKey(data: keyBlock.subdata(in: 0..<16))
        let clientWriteIV = keyBlock.subdata(in: 32..<36)

        let transcriptHash = Data(SHA256.hash(data: transcript))
        let verifyData = tls12PRFSHA256(secret: masterSecret, label: "client finished", seed: transcriptHash, length: 12)
        let finishedPlain = wrapHandshake(type: 0x14, body: verifyData)

        let explicitNonce = Data(repeating: 0, count: 8)
        let gcmNonce = try AES.GCM.Nonce(data: clientWriteIV + explicitNonce)
        var aad = explicitNonce
        aad.append(contentsOf: [0x16, 0x03, 0x03, UInt8((finishedPlain.count >> 8) & 0xFF), UInt8(finishedPlain.count & 0xFF)])
        let sealed = try AES.GCM.seal(finishedPlain, using: clientWriteKey, nonce: gcmNonce, authenticating: aad)
        let encFinishedPayload = explicitNonce + sealed.ciphertext + sealed.tag

        var flight = Data()
        flight.append(wrapTLSRecord(contentType: 0x16, payload: certMsg))
        flight.append(wrapTLSRecord(contentType: 0x16, payload: ckeMsg))
        flight.append(wrapTLSRecord(contentType: 0x16, payload: cvMsg))
        flight.append(wrapTLSRecord(contentType: 0x14, payload: Data([0x01])))
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
        let res = try TestProcessRunner.run(
            executable: "/usr/bin/openssl",
            arguments: ["dgst", "-sha256", "-sign", keyURL.path, tmpIn.path],
            captureStdout: true,
            captureStderr: false,
            timeout: 10.0
        )
        XCTAssertFalse(res.timedOut)
        XCTAssertEqual(res.exitCode, 0)
        return res.stdout
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

    private func sendHTTPSGetAndHalfCloseWrite(port: UInt16, path: String) throws -> String {
        try performHalfCloseHTTPSGet(port: port, path: path)
    }

    @available(macOS, deprecated: 10.15)
    private func performHalfCloseHTTPSGet(port: UInt16, path: String) throws -> String {
        let fd = connectLoopback(port: port)
        XCTAssertGreaterThanOrEqual(fd, 0)
        defer { Darwin.close(fd) }
        var tv = timeval(tv_sec: 4, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        let ctx = try XCTUnwrap(SSLCreateContext(kCFAllocatorDefault, .clientSide, .streamType))
        let fdBox = UnsafeMutablePointer<Int32>.allocate(capacity: 1)
        fdBox.initialize(to: fd)
        defer { fdBox.deinitialize(count: 1); fdBox.deallocate() }

        SSLSetIOFuncs(ctx, { conn, data, lenPtr in
            let s = conn.assumingMemoryBound(to: Int32.self).pointee
            let wanted = lenPtr.pointee
            var total = 0
            while total < wanted {
                let n = Darwin.recv(s, data.advanced(by: total), wanted - total, 0)
                if n > 0 { total += n }
                else if n == 0 { lenPtr.pointee = total; return errSSLClosedGraceful }
                else if errno == EINTR { continue }
                else { lenPtr.pointee = total; return errSSLClosedAbort }
            }
            lenPtr.pointee = total
            return noErr
        }, { conn, data, lenPtr in
            let s = conn.assumingMemoryBound(to: Int32.self).pointee
            let wanted = lenPtr.pointee
            var total = 0
            while total < wanted {
                let n = Darwin.send(s, data.advanced(by: total), wanted - total, 0)
                if n > 0 { total += n }
                else if n < 0 && errno == EINTR { continue }
                else { lenPtr.pointee = total; return errSSLClosedAbort }
            }
            lenPtr.pointee = total
            return noErr
        })
        SSLSetConnection(ctx, fdBox)
        SSLSetProtocolVersionMin(ctx, .tlsProtocol12)
        SSLSetSessionOption(ctx, .breakOnServerAuth, true)

        var hs = SSLHandshake(ctx)
        while hs == errSSLPeerAuthCompleted || hs == errSSLWouldBlock {
            hs = SSLHandshake(ctx)
        }
        XCTAssertEqual(hs, noErr)

        let req = Data("GET \(path) HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".utf8)
        req.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            var written = 0
            _ = SSLWrite(ctx, base, req.count, &written)
        }
        // Half-close TCP write end immediately after sending request!
        _ = Darwin.shutdown(fd, SHUT_WR)

        var response = Data()
        var buf = [UInt8](repeating: 0, count: 4096)
        while true {
            var readCount = 0
            let st = SSLRead(ctx, &buf, buf.count, &readCount)
            if readCount > 0 { response.append(buf, count: readCount) }
            if st != noErr && st != errSSLWouldBlock { break }
        }
        return String(decoding: response, as: UTF8.self)
    }

    private func generateSelfSignedCert(keyURL: URL, certURL: URL, cn: String) throws {
        let res = try TestProcessRunner.run(
            executable: "/usr/bin/openssl",
            arguments: [
                "req", "-x509", "-newkey", "rsa:2048",
                "-keyout", keyURL.path, "-out", certURL.path,
                "-days", "1", "-nodes", "-subj", "/CN=\(cn)"
            ],
            captureStdout: false,
            captureStderr: false,
            timeout: 15.0
        )
        XCTAssertFalse(res.timedOut)
        XCTAssertEqual(res.exitCode, 0)
    }

    private func runCurl(_ args: [String]) -> (Int, String) {
        TestProcessRunner.runCurl(args, timeout: 15.0)
    }
}
