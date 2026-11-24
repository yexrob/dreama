import Darwin
import Foundation
import Security
import XCTest
@testable import Depth3DHostCore

/// Self-contained loopback HTTPS responder using Apple Security SecureTransport (same stack as ControlHTTPSServer)
/// specifically for emulating Sunshine's `/api/*` endpoints in real `HostRuntime` integration tests.
private final class FakeSunshineAPIServer: @unchecked Sendable {
    private let lock = NSLock()
    private let identity: SecIdentity
    private let stateFileURL: URL
    private var listenFD: Int32 = -1
    private var running = false
    private let acceptQueue = DispatchQueue(label: "dev.depth3d.test.fake-sunshine.accept")
    private let workerQueue = DispatchQueue(label: "dev.depth3d.test.fake-sunshine.worker", attributes: .concurrent)

    private var pendingPairings: [EnginePendingPairing] = []
    private var expectedPinByPairingId: [String: (pin: String, uuid: String, certPEM: String)] = [:]
    private var receivedSubmissions: [(pairingId: String, pin: String, name: String)] = []
    private var unpairedUUIDs: [String] = []
    private var pairedCerts: [[String: String]] = []

    public private(set) var port: UInt16 = 0

    init(identity: SecIdentity, stateFileURL: URL, initialCerts: [[String: String]] = []) throws {
        self.identity = identity
        self.stateFileURL = stateFileURL
        self.pairedCerts = initialCerts
        writeStateLocked()
        try startListening()
    }

    deinit {
        stop()
    }

    func setPendingPairings(
        _ pairings: [EnginePendingPairing],
        expectations: [String: (pin: String, uuid: String, certPEM: String)]
    ) {
        lock.lock()
        pendingPairings = pairings
        expectedPinByPairingId = expectations
        lock.unlock()
    }

    func submissions() -> [(pairingId: String, pin: String, name: String)] {
        lock.lock()
        defer { lock.unlock() }
        return receivedSubmissions
    }

    func unpaired() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return unpairedUUIDs
    }

    func stop() {
        lock.lock()
        let fd = listenFD
        listenFD = -1
        running = false
        lock.unlock()
        if fd >= 0 {
            Darwin.shutdown(fd, SHUT_RDWR)
            Darwin.close(fd)
        }
    }

    private func startListening() throws {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw NSError(domain: "FakeSunshine", code: 1) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = 0
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0, Darwin.listen(fd, 16) == 0 else {
            Darwin.close(fd)
            throw NSError(domain: "FakeSunshine", code: 2)
        }
        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        self.listenFD = fd
        self.port = UInt16(bigEndian: bound.sin_port)
        self.running = true

        acceptQueue.async { [weak self] in
            self?.acceptLoop(listenFD: fd)
        }
    }

    private func acceptLoop(listenFD: Int32) {
        while true {
            var sa = sockaddr_storage()
            var len = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let clientFD = withUnsafeMutablePointer(to: &sa) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.accept(listenFD, $0, &len)
                }
            }
            if clientFD < 0 {
                if errno == EINTR { continue }
                break
            }
            workerQueue.async { [weak self] in
                self?.handle(fd: clientFD)
            }
        }
    }

    @available(macOS, deprecated: 10.15)
    private func handle(fd: Int32) {
        defer { Darwin.close(fd) }
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        guard let ctx = SSLCreateContext(kCFAllocatorDefault, .serverSide, .streamType) else { return }
        defer { SSLClose(ctx) }
        let box = UnsafeMutablePointer<Int32>.allocate(capacity: 1)
        box.initialize(to: fd)
        defer { box.deinitialize(count: 1); box.deallocate() }

        SSLSetIOFuncs(ctx, { conn, data, lenPtr in
            let s = conn.assumingMemoryBound(to: Int32.self).pointee
            let requested = lenPtr.pointee
            var total = 0
            while total < requested {
                let n = Darwin.recv(s, data.advanced(by: total), requested - total, 0)
                if n > 0 { total += n }
                else if n == 0 { lenPtr.pointee = total; return errSSLClosedGraceful }
                else if errno == EINTR { continue }
                else { lenPtr.pointee = total; return errSSLClosedAbort }
            }
            lenPtr.pointee = total
            return noErr
        }, { conn, data, lenPtr in
            let s = conn.assumingMemoryBound(to: Int32.self).pointee
            let requested = lenPtr.pointee
            var total = 0
            while total < requested {
                let n = Darwin.send(s, data.advanced(by: total), requested - total, 0)
                if n > 0 { total += n }
                else if n < 0 && errno == EINTR { continue }
                else { lenPtr.pointee = total; return errSSLClosedAbort }
            }
            lenPtr.pointee = total
            return noErr
        })
        SSLSetConnection(ctx, box)
        SSLSetCertificate(ctx, [identity] as CFArray)

        var hsStatus: OSStatus
        repeat {
            hsStatus = SSLHandshake(ctx)
        } while hsStatus == errSSLWouldBlock
        guard hsStatus == noErr else { return }

        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        var parsed: HTTPParser.ParsedHead?
        while true {
            if parsed == nil {
                parsed = try? HTTPParser.parseHead(from: buffer)
            }
            if let head = parsed, buffer.count >= head.bodyStartIndex + head.contentLength {
                let body = buffer.subdata(in: head.bodyStartIndex..<(head.bodyStartIndex + head.contentLength))
                let resp = route(method: head.method, path: head.target, body: body)
                let wire = resp.serialize()
                wire.withUnsafeBytes { raw in
                    guard let base = raw.baseAddress else { return }
                    var off = 0
                    while off < wire.count {
                        var written = 0
                        let st = SSLWrite(ctx, base.advanced(by: off), wire.count - off, &written)
                        if written > 0 { off += written }
                        if st != noErr && st != errSSLWouldBlock { break }
                    }
                }
                return
            }
            var processed = 0
            let st = SSLRead(ctx, &chunk, chunk.count, &processed)
            if processed > 0 { buffer.append(chunk, count: processed) }
            if st != noErr && st != errSSLWouldBlock { return }
        }
    }

    private func route(method: String, path: String, body: Data) -> HTTPResponse {
        switch (method, path) {
        case ("GET", "/api/csrf-token"):
            return HTTPResponse.json(200, object: ["csrf_token": "test-csrf"])

        case ("GET", "/api/pin"):
            lock.lock()
            let list = pendingPairings.map { p -> [String: Any] in
                var dict: [String: Any] = ["id": p.id]
                if let n = p.name { dict["name"] = n }
                if let a = p.address { dict["address"] = a }
                return dict
            }
            lock.unlock()
            return HTTPResponse.json(200, object: ["pairings": list])

        case ("POST", "/api/pin"):
            guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let pairingId = json["pairing_id"] as? String,
                  let pin = json["pin"] as? String,
                  let name = json["name"] as? String
            else {
                return HTTPResponse.json(400, object: ["status": false])
            }
            lock.lock()
            receivedSubmissions.append((pairingId, pin, name))
            if let exp = expectedPinByPairingId[pairingId], exp.pin == pin {
                pendingPairings.removeAll { $0.id == pairingId }
                pairedCerts.append(["uuid": exp.uuid, "name": name, "cert": exp.certPEM])
                writeStateLocked()
                lock.unlock()
                return HTTPResponse.json(200, object: ["status": true])
            }
            lock.unlock()
            return HTTPResponse.json(200, object: ["status": false])

        case ("GET", "/api/clients/list"):
            lock.lock()
            let named = pairedCerts.map { ["uuid": $0["uuid"] ?? "", "name": $0["name"] ?? ""] }
            lock.unlock()
            return HTTPResponse.json(200, object: ["status": true, "named_certs": named])

        case ("POST", "/api/clients/unpair"):
            if let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
               let uuid = json["uuid"] as? String
            {
                lock.lock()
                unpairedUUIDs.append(uuid)
                pairedCerts.removeAll { $0["uuid"] == uuid }
                writeStateLocked()
                lock.unlock()
            }
            return HTTPResponse.json(200, object: ["status": true])

        default:
            return HTTPResponse.json(404, object: ["status": false])
        }
    }

    private func writeStateLocked() {
        let obj: [String: Any] = [
            "root": [
                "uniqueid": "FAKE-ENGINE-ID",
                "named_certs": pairedCerts
            ]
        ]
        if let data = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted]) {
            try? FileManager.default.createDirectory(at: stateFileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: stateFileURL, options: .atomic)
        }
    }
}

/// Real `HostRuntime` integration tests WITHOUT any mock `ControlProtocolDelegate`!
/// Exercises the actual `HostRuntime.handleApprovedPairing`, `HostRuntime.authorizeMediaClient`,
/// `HostRuntime.removePairedDevice`, `ControlProtocolRouter`, `EngineAPIClient`, `HostStateStore`,
/// and `WebDAVHandler` end-to-end against a real loopback HTTPS Sunshine API stub + real `state.json`.
@MainActor
final class HostRuntimeIntegrationTests: XCTestCase {
    private func makePEM(_ bytes: [UInt8]) -> (pem: String, fingerprint: String) {
        let data = Data(bytes)
        let b64 = data.base64EncodedString()
        let pem = "-----BEGIN CERTIFICATE-----\n\(b64)\n-----END CERTIFICATE-----\n"
        return (pem, TLSIdentityManager.sha256Hex(data))
    }

    func testRealHostRuntimePairingCertBindingMediaAuthAndCredentialRotation() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-runtime-real-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let paths = HostPaths(rootDirectory: tmp)
        let store = try HostStateStore(paths: paths)
        let tlsManager = TLSIdentityManager(tlsDirectory: paths.tlsDirectory)
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "Fake Sunshine")

        // Pre-existing unrelated cert already in engine's state.json (must NEVER be mis-bound!)
        let unrelatedCert = makePEM([9, 9, 9, 9, 1, 2, 3, 4])
        let targetCert = makePEM([1, 2, 3, 4, 5, 6, 7, 8])

        let fakeEngine = try FakeSunshineAPIServer(
            identity: serverId.secIdentity,
            stateFileURL: paths.engineStateFile,
            initialCerts: [["uuid": "OLD-UUID", "name": "OldHeadset", "cert": unrelatedCert.pem]]
        )
        defer { fakeEngine.stop() }

        let adminCreds = try store.loadOrCreateEngineAdminCredentials()
        let apiClient = EngineAPIClient(apiPort: fakeEngine.port, credentials: adminCreds)
        let initialWebDAVCreds = try store.loadOrCreateWebDAVCredentials()
        let webDAVHandler = WebDAVHandler(credentials: initialWebDAVCreds)
        let coordinator = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0)

        let runtime = HostRuntime(overrides: RuntimeOverrides(dataDirectory: tmp))
        runtime.configureForTesting(
            store: store,
            engineAPIClient: apiClient,
            engineState: .ready(encoder: "hevc_videotoolbox"),
            pairingCoordinator: coordinator,
            webDAVHandler: webDAVHandler,
            webDAVCredentials: initialWebDAVCreds,
            webDAVPort: 8890
        )

        // Two pending pairings from DIFFERENT IPs in engine:
        // "other-id" is listed FIRST! HostRuntime MUST NOT blindly submit PIN to pending.first!
        fakeEngine.setPendingPairings(
            [
                EnginePendingPairing(id: "other-id", name: "StrangerHeadset", address: "192.168.1.99"),
                EnginePendingPairing(id: "target-id", name: "PICO 4", address: "192.168.1.50")
            ],
            expectations: [
                "target-id": (pin: "2468", uuid: "TARGET-UUID", certPEM: targetCert.pem)
            ]
        )

        // 1. Wrong PIN must fail (false -> 503) and MUST NOT save any PairedDeviceRecord
        let wrongOK = await runtime.handleApprovedPairing(
            deviceName: "PICO 4",
            deviceId: "pico-device-1",
            pin: "0000",
            clientAddress: "192.168.1.50",
            autoAllowFuture: true
        )
        XCTAssertFalse(wrongOK, "Rejected PIN must return false")
        XCTAssertTrue(runtime.pairedDevices.isEmpty, "Device record must NOT be saved when GameStream pairing fails")

        // 2. Correct PIN from 192.168.1.50 matches ONLY "target-id" (never "other-id") and binds EXACT cert
        let rightOK = await runtime.handleApprovedPairing(
            deviceName: "PICO 4",
            deviceId: "pico-device-1",
            pin: "2468",
            clientAddress: "192.168.1.50",
            autoAllowFuture: true
        )
        XCTAssertTrue(rightOK)

        let submissions = fakeEngine.submissions()
        XCTAssertTrue(submissions.allSatisfy { $0.pairingId == "target-id" }, "PIN must ONLY be submitted to the matching IP's pairing_id, never other-id")

        XCTAssertEqual(runtime.pairedDevices.count, 1)
        let saved = try XCTUnwrap(runtime.pairedDevices.first)
        XCTAssertEqual(saved.deviceId, "pico-device-1")
        XCTAssertEqual(saved.engineUUID, "TARGET-UUID")
        XCTAssertEqual(saved.certFingerprint, targetCert.fingerprint)
        XCTAssertTrue(saved.autoAllow)

        // 3. Real HostRuntime.authorizeMediaClient (no mock delegate!):
        //    Accepts ONLY the exact bound cert; rejects pre-existing unrelated cert & unknown device!
        XCTAssertTrue(runtime.authorizeMediaClient(deviceId: "pico-device-1", peerCertFingerprintSHA256: targetCert.fingerprint))
        XCTAssertFalse(runtime.authorizeMediaClient(deviceId: "pico-device-1", peerCertFingerprintSHA256: unrelatedCert.fingerprint))
        XCTAssertFalse(runtime.authorizeMediaClient(deviceId: "OldHeadset", peerCertFingerprintSHA256: unrelatedCert.fingerprint))

        // 4. Verify real ControlProtocolRouter wired to real HostRuntime auto-allows ONLY with targetCert!
        let realRouter = ControlProtocolRouter(
            pairingCoordinator: coordinator,
            delegate: runtime.makeProtocolDelegateForTesting()
        )
        let mediaResp = await realRouter.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
            headers: ["X-Depth3d-Device": "pico-device-1"],
            peerCertificateDER: Data([1, 2, 3, 4, 5, 6, 7, 8])
        ))
        XCTAssertEqual(mediaResp.statusCode, 200)

        let badMediaResp = await realRouter.handle(HTTPRequest(
            method: "GET",
            rawTarget: "/depth3d/v1/media",
            headers: ["X-Depth3d-Device": "pico-device-1"],
            peerCertificateDER: Data([9, 9, 9, 9, 1, 2, 3, 4])
        ))
        XCTAssertEqual(badMediaResp.statusCode, 401)

        // 5. Removing paired device MUST rotate WebDAV credentials immediately & invalidate old creds!
        let oldAuth = "Basic " + Data("\(initialWebDAVCreds.user):\(initialWebDAVCreds.pass)".utf8).base64EncodedString()
        if case .immediate(let preResp) = webDAVHandler.route(HTTPRequest(method: "OPTIONS", rawTarget: "/", headers: ["Authorization": oldAuth])) {
            XCTAssertEqual(preResp.statusCode, 200)
        } else {
            XCTFail("Expected 200 before removal")
        }

        runtime.removePairedDevice(saved)
        XCTAssertTrue(runtime.pairedDevices.isEmpty)
        XCTAssertEqual(runtime.currentMediaCredentialRevision, 1)

        let newCreds = try XCTUnwrap(runtime.currentWebDAVCredentials)
        XCTAssertNotEqual(newCreds.pass, initialWebDAVCreds.pass, "Removing a device must rotate WebDAV password")

        // Old credentials now immediately return 401 on WebDAVHandler
        if case .immediate(let postOldResp) = webDAVHandler.route(HTTPRequest(method: "OPTIONS", rawTarget: "/", headers: ["Authorization": oldAuth])) {
            XCTAssertEqual(postOldResp.statusCode, 401, "Removed device's old WebDAV credentials must be rejected immediately")
        } else {
            XCTFail("Expected 401 for rotated-out credentials")
        }

        // New credentials succeed
        let newAuth = "Basic " + Data("\(newCreds.user):\(newCreds.pass)".utf8).base64EncodedString()
        if case .immediate(let postNewResp) = webDAVHandler.route(HTTPRequest(method: "OPTIONS", rawTarget: "/", headers: ["Authorization": newAuth])) {
            XCTAssertEqual(postNewResp.statusCode, 200)
        } else {
            XCTFail("Expected 200 for rotated credentials")
        }

        // Removed device is also denied `/media` immediately
        XCTAssertFalse(runtime.authorizeMediaClient(deviceId: "pico-device-1", peerCertFingerprintSHA256: targetCert.fingerprint))
    }

    /// Tests Case B (headset initiates GameStream `/pair` right AFTER receiving `200 OK` from `/depth3d/v1/pair`):
    /// Device record MUST NOT exist before GameStream pairing completes, and MUST bind exact cert once it completes.
    func testRealHostRuntimeDelayedGameStreamPairingOnlySavesAfterCompletion() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-runtime-delayed-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let paths = HostPaths(rootDirectory: tmp)
        let store = try HostStateStore(paths: paths)
        let tlsManager = TLSIdentityManager(tlsDirectory: paths.tlsDirectory)
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "Fake Sunshine 2")
        let fakeEngine = try FakeSunshineAPIServer(identity: serverId.secIdentity, stateFileURL: paths.engineStateFile)
        defer { fakeEngine.stop() }

        let adminCreds = try store.loadOrCreateEngineAdminCredentials()
        let apiClient = EngineAPIClient(apiPort: fakeEngine.port, credentials: adminCreds)
        let runtime = HostRuntime(overrides: RuntimeOverrides(dataDirectory: tmp))
        runtime.configureForTesting(
            store: store,
            engineAPIClient: apiClient,
            engineState: .ready(encoder: "hevc_videotoolbox"),
            pairingCoordinator: PairingCoordinator(timeout: 5.0)
        )

        // Initially NO pending pairing in engine -> handleApprovedPairing arms background waiter and returns true
        let accepted = await runtime.handleApprovedPairing(
            deviceName: "PICO Delayed",
            deviceId: "pico-delayed",
            pin: "7788",
            clientAddress: "192.168.1.77",
            autoAllowFuture: false,
            backgroundWaitSeconds: 5.0
        )
        XCTAssertTrue(accepted)
        XCTAssertTrue(runtime.pairedDevices.isEmpty, "Must NOT persist device record before GameStream pairing actually finishes")

        // Now headset initiates GameStream pairing from 192.168.1.77
        let delayedCert = makePEM([7, 7, 8, 8, 1, 2, 3, 4])
        fakeEngine.setPendingPairings(
            [EnginePendingPairing(id: "delayed-pid", name: "PICO Delayed", address: "192.168.1.77")],
            expectations: ["delayed-pid": (pin: "7788", uuid: "DELAYED-UUID", certPEM: delayedCert.pem)]
        )

        let deadline = Date().addingTimeInterval(4.0)
        while runtime.pairedDevices.isEmpty && Date() < deadline {
            try await Task.sleep(nanoseconds: 100_000_000)
        }

        XCTAssertEqual(runtime.pairedDevices.count, 1)
        XCTAssertEqual(runtime.pairedDevices.first?.engineUUID, "DELAYED-UUID")
        XCTAssertEqual(runtime.pairedDevices.first?.certFingerprint, delayedCert.fingerprint)
        XCTAssertTrue(runtime.authorizeMediaClient(deviceId: "pico-delayed", peerCertFingerprintSHA256: delayedCert.fingerprint))
    }
}
