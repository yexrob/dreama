import CommonCrypto
import CryptoKit
import Darwin
import Foundation
import Security
import XCTest
@testable import Depth3DHostCore

/// Real GameStream Gen-7+ (SHA-256 + AES-128-ECB + RSA-2048) pairing client used to execute
/// an authentic 5-step pairing against the REAL built-in `depth3d-stream-engine` binary (B1).
private enum RealGameStreamPairingClient {
    enum PairError: Error, CustomStringConvertible {
        case stepFailed(String)
        var description: String {
            switch self { case .stepFailed(let msg): return msg }
        }
    }

    static func pair(
        httpPort: UInt16,
        httpsPort: UInt16,
        pin: String,
        clientCertURL: URL,
        clientKeyURL: URL,
        uniqueID: String = "0123456789ABCDEF"
    ) throws {
        let clientPEMData = try Data(contentsOf: clientCertURL)
        let clientCertSig = try extractX509SignatureBytes(fromCertPEMURL: clientCertURL)

        var salt = Data(count: 16)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        let aesKey = Data(SHA256.hash(data: salt + Data(pin.utf8)).prefix(16))

        // Step 1: getservercert (blocks on Sunshine until Host submits PIN via /api/pin!)
        let step1URL = "http://127.0.0.1:\(httpPort)/pair?uniqueid=\(uniqueID)&devicename=roth&updateState=1&phrase=getservercert&salt=\(hex(salt))&clientcert=\(hex(clientPEMData))"
        let step1XML = try httpGet(step1URL, timeout: 20)
        guard xmlTag(step1XML, "paired") == "1", let plainCertHex = xmlTag(step1XML, "plaincert"), !plainCertHex.isEmpty else {
            throw PairError.stepFailed("getservercert failed: \(step1XML)")
        }
        let serverCertPEMData = try unhex(plainCertHex)
        let tmpServerCert = FileManager.default.temporaryDirectory.appendingPathComponent("gs-srv-\(UUID().uuidString).pem")
        try serverCertPEMData.write(to: tmpServerCert)
        defer { try? FileManager.default.removeItem(at: tmpServerCert) }
        let serverCertSig = try extractX509SignatureBytes(fromCertPEMURL: tmpServerCert)

        // Step 2: clientchallenge
        var randomChallenge = Data(count: 16)
        _ = randomChallenge.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        let encChallenge = try aes128ECB(encrypt: true, data: randomChallenge, key: aesKey)
        let step2URL = "http://127.0.0.1:\(httpPort)/pair?uniqueid=\(uniqueID)&devicename=roth&updateState=1&clientchallenge=\(hex(encChallenge))"
        let step2XML = try httpGet(step2URL, timeout: 10)
        guard xmlTag(step2XML, "paired") == "1", let chalRespHex = xmlTag(step2XML, "challengeresponse") else {
            throw PairError.stepFailed("clientchallenge failed: \(step2XML)")
        }
        let decResp = try aes128ECB(encrypt: false, data: try unhex(chalRespHex), key: aesKey)
        guard decResp.count >= 48 else { throw PairError.stepFailed("short challengeresponse") }
        let serverResponseHash = decResp.subdata(in: 0..<32)
        let serverChallenge = decResp.subdata(in: 32..<48)

        // Step 3: serverchallengeresp
        var clientSecret = Data(count: 16)
        _ = clientSecret.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        let clientHash = Data(SHA256.hash(data: serverChallenge + clientCertSig + clientSecret))
        let encClientHash = try aes128ECB(encrypt: true, data: clientHash, key: aesKey)
        let step3URL = "http://127.0.0.1:\(httpPort)/pair?uniqueid=\(uniqueID)&devicename=roth&updateState=1&serverchallengeresp=\(hex(encClientHash))"
        let step3XML = try httpGet(step3URL, timeout: 10)
        guard xmlTag(step3XML, "paired") == "1", let pairingSecretHex = xmlTag(step3XML, "pairingsecret") else {
            throw PairError.stepFailed("serverchallengeresp failed: \(step3XML)")
        }
        let serverPairingSecret = try unhex(pairingSecretHex)
        guard serverPairingSecret.count >= 16 else { throw PairError.stepFailed("short pairingsecret") }
        let serverSecret = serverPairingSecret.subdata(in: 0..<16)
        let expectedServerHash = Data(SHA256.hash(data: randomChallenge + serverCertSig + serverSecret))
        guard expectedServerHash == serverResponseHash else {
            throw PairError.stepFailed("server challenge hash mismatch (PIN mismatch)")
        }

        // Step 4: clientpairingsecret (clientSecret + RSA-SHA256 signature over clientSecret)
        let clientSig = try rsaSHA256Sign(data: clientSecret, privateKeyURL: clientKeyURL)
        let clientPairingSecret = clientSecret + clientSig
        let step4URL = "http://127.0.0.1:\(httpPort)/pair?uniqueid=\(uniqueID)&devicename=roth&updateState=1&clientpairingsecret=\(hex(clientPairingSecret))"
        let step4XML = try httpGet(step4URL, timeout: 10)
        guard xmlTag(step4XML, "paired") == "1" else {
            throw PairError.stepFailed("clientpairingsecret failed: \(step4XML)")
        }

        // Step 5: HTTPS mTLS pairchallenge (finalizes pairing & triggers Sunshine to persist state.json!)
        let step5URL = "https://127.0.0.1:\(httpsPort)/pair?uniqueid=\(uniqueID)&devicename=roth&updateState=1&phrase=pairchallenge"
        let step5XML = try httpsGet(step5URL, certURL: clientCertURL, keyURL: clientKeyURL, timeout: 10)
        guard xmlTag(step5XML, "paired") == "1" else {
            throw PairError.stepFailed("pairchallenge failed: \(step5XML)")
        }
    }

    private static func aes128ECB(encrypt: Bool, data: Data, key: Data) throws -> Data {
        var out = Data(count: data.count + kCCBlockSizeAES128)
        var outLen: Int = 0
        let status = out.withUnsafeMutableBytes { outPtr in
            data.withUnsafeBytes { dataPtr in
                key.withUnsafeBytes { keyPtr in
                    CCCrypt(
                        CCOperation(encrypt ? kCCEncrypt : kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionECBMode),
                        keyPtr.baseAddress, key.count,
                        nil,
                        dataPtr.baseAddress, data.count,
                        outPtr.baseAddress, outPtr.count,
                        &outLen
                    )
                }
            }
        }
        guard status == kCCSuccess else { throw PairError.stepFailed("CCCrypt failed: \(status)") }
        return out.prefix(outLen)
    }

    /// Extracts the X.509 certificate signature BIT STRING bytes (matching Java `X509Certificate.getSignature()`).
    private static func extractX509SignatureBytes(fromCertPEMURL certURL: URL) throws -> Data {
        // Use openssl asn1parse to read the final BIT STRING signature cleanly, or parse DER directly!
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        p.arguments = ["x509", "-in", certURL.path, "-outform", "DER"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let der = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()

        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue BIT STRING }
        var idx = 0
        guard der.count > 10, der[idx] == 0x30 else { throw PairError.stepFailed("invalid X509 DER") }
        idx += 1
        _ = try readDERLength(der, &idx)
        // Skip tbsCertificate
        guard idx < der.count else { throw PairError.stepFailed("missing tbs") }
        idx += 1
        let tbsLen = try readDERLength(der, &idx)
        idx += tbsLen
        // Skip signatureAlgorithm
        guard idx < der.count else { throw PairError.stepFailed("missing sigAlg") }
        idx += 1
        let algLen = try readDERLength(der, &idx)
        idx += algLen
        // Read signatureValue BIT STRING (tag 0x03; first content byte is unused-bits count = 0x00)
        guard idx < der.count, der[idx] == 0x03 else { throw PairError.stepFailed("missing BIT STRING signature") }
        idx += 1
        let bitLen = try readDERLength(der, &idx)
        guard bitLen > 1, idx + bitLen <= der.count else { throw PairError.stepFailed("invalid BIT STRING length") }
        return der.subdata(in: (idx + 1)..<(idx + bitLen))
    }

    private static func readDERLength(_ data: Data, _ idx: inout Int) throws -> Int {
        guard idx < data.count else { throw PairError.stepFailed("DER truncated") }
        let first = Int(data[idx]); idx += 1
        if (first & 0x80) == 0 { return first }
        let numBytes = first & 0x7F
        guard numBytes > 0, numBytes <= 4, idx + numBytes <= data.count else {
            throw PairError.stepFailed("DER invalid length")
        }
        var len = 0
        for _ in 0..<numBytes {
            len = (len << 8) | Int(data[idx]); idx += 1
        }
        return len
    }

    private static func rsaSHA256Sign(data: Data, privateKeyURL: URL) throws -> Data {
        let tmpIn = FileManager.default.temporaryDirectory.appendingPathComponent("gs-sign-\(UUID().uuidString).bin")
        try data.write(to: tmpIn)
        defer { try? FileManager.default.removeItem(at: tmpIn) }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        p.arguments = ["dgst", "-sha256", "-sign", privateKeyURL.path, tmpIn.path]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let sig = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        guard p.terminationStatus == 0, !sig.isEmpty else { throw PairError.stepFailed("openssl sign failed") }
        return sig
    }

    private static func httpGet(_ url: String, timeout: Int) throws -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        p.arguments = ["--noproxy", "*", "-s", "--max-time", "\(timeout)", url]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }

    private static func httpsGet(_ url: String, certURL: URL, keyURL: URL, timeout: Int) throws -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        p.arguments = ["--noproxy", "*", "-sk", "--max-time", "\(timeout)", "--cert", certURL.path, "--key", keyURL.path, url]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }

    private static func xmlTag(_ xml: String, _ tag: String) -> String? {
        guard let start = xml.range(of: "<\(tag)>"),
              let end = xml.range(of: "</\(tag)>", range: start.upperBound..<xml.endIndex)
        else { return nil }
        return String(xml[start.upperBound..<end.lowerBound])
    }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private static func unhex(_ str: String) throws -> Data {
        let clean = str.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count % 2 == 0 else { throw PairError.stepFailed("odd hex length") }
        var out = Data(capacity: clean.count / 2)
        var idx = clean.startIndex
        while idx < clean.endIndex {
            let next = clean.index(idx, offsetBy: 2)
            guard let b = UInt8(clean[idx..<next], radix: 16) else { throw PairError.stepFailed("bad hex") }
            out.append(b)
            idx = next
        }
        return out
    }
}

/// Loopback HTTPS responder using the EXACT `root.named_devices` schema emitted by real
/// Sunshine v2026.914.233613 (`{name, cert, uuid, enabled: "true"}`) for deterministic
/// edge-case testing (S2, S3, S7, PIN IP isolation, credential rotation).
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
    private var pairedDevices: [[String: String]] = []

    public private(set) var port: UInt16 = 0

    init(identity: SecIdentity, stateFileURL: URL, initialDevices: [[String: String]] = []) throws {
        self.identity = identity
        self.stateFileURL = stateFileURL
        self.pairedDevices = initialDevices
        writeStateLocked()
        try startListening()
    }

    deinit { stop() }

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
                self?.dispatchHandle(fd: clientFD)
            }
        }
    }

    private func dispatchHandle(fd: Int32) {
        handle(fd: fd)
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
        repeat { hsStatus = SSLHandshake(ctx) } while hsStatus == errSSLWouldBlock
        guard hsStatus == noErr else { return }

        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        var parsed: HTTPParser.ParsedHead?
        while true {
            if parsed == nil { parsed = try? HTTPParser.parseHead(from: buffer) }
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
                pairedDevices.append(["uuid": exp.uuid, "name": name, "cert": exp.certPEM, "enabled": "true"])
                writeStateLocked()
                lock.unlock()
                return HTTPResponse.json(200, object: ["status": true])
            }
            lock.unlock()
            return HTTPResponse.json(200, object: ["status": false])

        case ("GET", "/api/clients/list"):
            lock.lock()
            // Matches real Sunshine v2026.914.233613 `/api/clients/list` response format!
            let named: [[String: Any]] = pairedDevices.map {
                ["uuid": $0["uuid"] ?? "", "name": $0["name"] ?? "", "enabled": ($0["enabled"] != "false")]
            }
            lock.unlock()
            return HTTPResponse.json(200, object: ["status": true, "named_certs": named])

        case ("POST", "/api/clients/unpair"):
            if let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
               let uuid = json["uuid"] as? String
            {
                lock.lock()
                unpairedUUIDs.append(uuid)
                pairedDevices.removeAll { $0["uuid"] == uuid }
                writeStateLocked()
                lock.unlock()
            }
            return HTTPResponse.json(200, object: ["status": true])

        default:
            return HTTPResponse.json(404, object: ["status": false])
        }
    }

    private func writeStateLocked() {
        // Real Sunshine v2026.914.233613 state.json format: `root.named_devices`!
        let obj: [String: Any] = [
            "root": [
                "uniqueid": "FAKE-ENGINE-ID",
                "named_devices": pairedDevices
            ]
        ]
        if let data = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted]) {
            try? FileManager.default.createDirectory(at: stateFileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: stateFileURL, options: .atomic)
        }
    }
}

/// Real `HostRuntime` integration tests WITHOUT any mock `ControlProtocolDelegate`!
@MainActor
final class HostRuntimeIntegrationTests: XCTestCase {
    private func makePEM(_ bytes: [UInt8]) -> (pem: String, fingerprint: String) {
        let data = Data(bytes)
        let b64 = data.base64EncodedString()
        let pem = "-----BEGIN CERTIFICATE-----\n\(b64)\n-----END CERTIFICATE-----\n"
        return (pem, TLSIdentityManager.sha256Hex(data))
    }

    /// B1 FULL REAL-ENGINE END-TO-END INTEGRATION TEST (ZERO FAKE STATE.JSON, ZERO MOCK DELEGATE):
    /// Runs the ACTUAL `depth3d-stream-engine` binary + real `EngineSupervisor` + real `EngineAPIClient`
    /// + real `HostRuntime` + real `ControlProtocolRouter` + real `ControlHTTPSServer` + real `WebDAVServer`
    /// + real 5-step GameStream pairing client:
    ///   `/depth3d/v1/pair` + GameStream `/pair` -> PIN -> real `state.json` (`root.named_devices`) ->
    ///   cert fingerprint binding -> mTLS `/depth3d/v1/media` returns `200` -> WebDAV `200`!
    func testRealEngineEndToEndPairPINRealStateJSONCertBindingAndMedia200() async throws {
        let engineCandidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("host/build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine")
        ]
        guard let realEngine = engineCandidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) }) else {
            throw XCTSkip("Staged depth3d-stream-engine binary not present")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-e2e-real-engine-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // Pick unoccupied high port group for real engine
        var basePort: UInt16 = 59849
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: basePort)).isEmpty {
            basePort += 30
        }
        let httpsPort = HostPortDefaults.engineHTTPSPort(basePort: basePort)
        let apiPort = HostPortDefaults.engineAPIPort(basePort: basePort)

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("host-data", isDirectory: true))
        let store = try HostStateStore(paths: paths)
        let adminCreds = try store.loadOrCreateEngineAdminCredentials()
        let webdavCreds = try store.loadOrCreateWebDAVCredentials()

        // 1. Start REAL built-in `depth3d-stream-engine`
        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: adminCreds,
            engineExecutableOverride: realEngine
        )
        defer { supervisor.stop() }

        let apiClient = EngineAPIClient(apiPort: apiPort, credentials: adminCreds)
        supervisor.start(config: EngineConfigGenerator.Input(
            hostName: "Depth3D Real E2E",
            basePort: basePort,
            quality: .balanced,
            displayID: nil,
            paths: paths
        ))

        let readyDeadline = Date().addingTimeInterval(15.0)
        var engineUp = false
        while Date() < readyDeadline {
            if let _ = try? await apiClient.listPairedClients() {
                engineUp = true
                break
            }
            try await Task.sleep(nanoseconds: 250_000_000)
        }
        XCTAssertTrue(engineUp, "Real built-in streaming engine failed to start")

        // 2. Wire REAL HostRuntime + real ControlHTTPSServer + real WebDAVServer
        let tlsManager = TLSIdentityManager(tlsDirectory: paths.tlsDirectory)
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "Depth3D Real E2E")
        let coordinator = PairingCoordinator(timeout: 20.0, cooldownDuration: 0.0)
        let webDAVHandler = WebDAVHandler(credentials: webdavCreds)
        let webDAVServer = WebDAVServer(handler: webDAVHandler)
        let davPort = try webDAVServer.start(preferredPort: 0)
        defer { webDAVServer.stop() }

        let runtime = HostRuntime(overrides: RuntimeOverrides(dataDirectory: paths.rootDirectory))
        runtime.configureForTesting(
            store: store,
            engineAPIClient: apiClient,
            engineState: .ready(encoder: "hevc_videotoolbox"),
            pairingCoordinator: coordinator,
            webDAVHandler: webDAVHandler,
            webDAVCredentials: webdavCreds,
            webDAVPort: davPort
        )
        let router = ControlProtocolRouter(
            pairingCoordinator: coordinator,
            delegate: runtime.makeProtocolDelegateForTesting()
        )
        let controlServer = ControlHTTPSServer(identity: serverId.secIdentity, router: router)
        let controlPort = try controlServer.start(port: 0, fallbackToEphemeral: false)
        defer { controlServer.stop() }

        // 3. Generate a real headset RSA-2048 key + X.509 certificate
        let clientKeyURL = tmp.appendingPathComponent("headset.key")
        let clientCertURL = tmp.appendingPathComponent("headset.crt")
        let genProc = Process()
        genProc.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        genProc.arguments = [
            "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", clientKeyURL.path, "-out", clientCertURL.path,
            "-days", "1", "-nodes", "-subj", "/CN=RealTestHeadset"
        ]
        genProc.standardOutput = FileHandle.nullDevice
        genProc.standardError = FileHandle.nullDevice
        try genProc.run()
        genProc.waitUntilExit()
        XCTAssertEqual(genProc.terminationStatus, 0)

        let clientPEM = try String(contentsOf: clientCertURL, encoding: .utf8)
        let expectedClientFP = try XCTUnwrap(TLSIdentityManager.sha256Hex(fromPEM: clientPEM))
        let testPIN = "4826"

        // 4. Concurrently run:
        //    (a) Real 5-step GameStream pairing client against REAL `depth3d-stream-engine`
        //    (b) `POST /depth3d/v1/pair` over HTTPS to REAL `ControlHTTPSServer`
        let gsPairTask = Task.detached { () -> Error? in
            do {
                try RealGameStreamPairingClient.pair(
                    httpPort: basePort,
                    httpsPort: httpsPort,
                    pin: testPIN,
                    clientCertURL: clientCertURL,
                    clientKeyURL: clientKeyURL
                )
                return nil
            } catch {
                return error
            }
        }

        let controlPairTask = Task.detached { () -> (Int, String) in
            Self.runCurl([
                "-sk", "--max-time", "25", "-X", "POST",
                "-H", "Content-Type: application/json",
                "-d", "{\"device\":\"PICO 4 Ultra\",\"deviceId\":\"real-pico-e2e\",\"pin\":\"\(testPIN)\"}",
                "https://127.0.0.1:\(controlPort)/depth3d/v1/pair"
            ])
        }

        // Wait for confirmation prompt to appear, then user approves with rememberAutoAllow=true
        var approvedPrompt = false
        let promptDeadline = Date().addingTimeInterval(10.0)
        while Date() < promptDeadline {
            let prompts = await coordinator.pendingPrompts()
            if let prompt = prompts.first {
                XCTAssertFalse(prompt.identityChanged)
                await coordinator.approve(promptId: prompt.id, rememberAutoAllow: true)
                approvedPrompt = true
                break
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertTrue(approvedPrompt, "Pairing prompt should appear for first-time headset")

        // 5. Verify BOTH GameStream pairing AND `/depth3d/v1/pair` completed with 200!
        let gsErr = await gsPairTask.value
        XCTAssertNil(gsErr, "Real GameStream 5-step pairing against real engine failed: \(String(describing: gsErr))")

        let (pairStatus, pairBody) = await controlPairTask.value
        XCTAssertEqual(pairStatus, 200, "POST /depth3d/v1/pair must return 200 after real engine completes pairing; body=\(pairBody)")

        // 6. Verify REAL state.json written by real engine uses `root.named_devices` AND HostRuntime bound the exact cert!
        let realStateJSON = try String(contentsOf: paths.engineStateFile, encoding: .utf8)
        XCTAssertTrue(realStateJSON.contains("\"named_devices\""), "Real Sunshine state.json writes root.named_devices")

        let savedDevice = try XCTUnwrap(runtime.pairedDevices.first(where: { $0.deviceId == "real-pico-e2e" }))
        XCTAssertNotNil(savedDevice.engineUUID)
        XCTAssertEqual(savedDevice.certFingerprint, expectedClientFP, "HostRuntime must bind exact SHA-256 of client cert from real state.json")
        XCTAssertTrue(savedDevice.autoAllow)

        // Verify real `/api/clients/list` also parses this device cleanly
        let engineClients = try await apiClient.listPairedClients()
        XCTAssertTrue(engineClients.contains(where: { $0.uuid == savedDevice.engineUUID }))

        // 7. Call `GET /depth3d/v1/media` over real mTLS with the headset's cert & key -> MUST return 200 + live WebDAV creds!
        //    Run `curl` off the MainActor so `RuntimeControlProtocolAdapter` can hop onto MainActor cleanly.
        let (mediaCode, mediaBody) = await Task.detached { () -> (Int, String) in
            Self.runCurl([
                "-sk", "--max-time", "5",
                "--cert", clientCertURL.path, "--key", clientKeyURL.path,
                "-H", "X-Depth3d-Device: real-pico-e2e",
                "https://127.0.0.1:\(controlPort)/depth3d/v1/media"
            ])
        }.value
        XCTAssertEqual(mediaCode, 200, "Authenticated paired headset must receive 200 from /depth3d/v1/media; body=\(mediaBody)")
        let mediaDecoded = try JSONDecoder().decode(MediaCredentialsResponse.self, from: Data(mediaBody.utf8))
        let davInfo = try XCTUnwrap(mediaDecoded.webdav)
        XCTAssertEqual(davInfo.port, Int(davPort))

        // 8. Verify returned WebDAV credentials actually authenticate against live WebDAVServer -> 200!
        let (davCode, _) = await Task.detached { () -> (Int, String) in
            Self.runCurl([
                "-s", "--max-time", "5",
                "-u", "\(davInfo.user):\(davInfo.pass)",
                "-X", "OPTIONS",
                "http://127.0.0.1:\(davInfo.port)/"
            ])
        }.value
        XCTAssertEqual(davCode, 200)
    }

    /// Real `HostRuntime` test covering B1 (`named_devices`), PIN IP matching, S2 deterministic failure/completion,
    /// S3 identity-changed re-pair NOT inheriting `autoAllow`, S7 legacy unbound upgrade, and WebDAV rotation.
    func testRealHostRuntimePairingCertBindingIdentityChangeLegacyUpgradeAndRotation() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-runtime-real-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let paths = HostPaths(rootDirectory: tmp)
        let store = try HostStateStore(paths: paths)
        let tlsManager = TLSIdentityManager(tlsDirectory: paths.tlsDirectory)
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "Fake Sunshine")

        let unrelatedCert = makePEM([9, 9, 9, 9, 1, 2, 3, 4])
        let targetCert = makePEM([1, 2, 3, 4, 5, 6, 7, 8])
        let replacementCert = makePEM([5, 5, 5, 5, 6, 6, 6, 6])
        let legacyUpgradedCert = makePEM([7, 7, 8, 8, 1, 2, 3, 4])

        let fakeEngine = try FakeSunshineAPIServer(
            identity: serverId.secIdentity,
            stateFileURL: paths.engineStateFile,
            initialDevices: [["uuid": "OLD-UUID", "name": "OldHeadset", "cert": unrelatedCert.pem, "enabled": "true"]]
        )
        defer { fakeEngine.stop() }

        // Seed a Round-1 legacy device record WITHOUT certFingerprint (S7)
        _ = try store.upsertPairedDevice(PairedDeviceRecord(
            deviceId: "legacy-dev",
            name: "Legacy PICO",
            engineUUID: nil,
            certFingerprint: nil,
            autoAllow: true
        ))

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

        // S2: When NO GameStream pairing appears before deadline, handleApprovedPairing MUST return false (never speculative 200!)
        let noSessionOK = await runtime.handleApprovedPairing(
            deviceName: "Ghost",
            deviceId: "ghost-dev",
            pin: "1234",
            clientAddress: "192.168.1.88",
            autoAllowFuture: false,
            pairingWaitSeconds: 0.4
        )
        XCTAssertFalse(noSessionOK, "S2: Must NOT return 200 when GameStream pairing never occurs")
        XCTAssertNil(runtime.pairedDevices.first(where: { $0.deviceId == "ghost-dev" }))

        // Two pending pairings from DIFFERENT IPs: "other-id" is first; PIN MUST go ONLY to "target-id"
        fakeEngine.setPendingPairings(
            [
                EnginePendingPairing(id: "other-id", name: "StrangerHeadset", address: "192.168.1.99"),
                EnginePendingPairing(id: "target-id", name: "PICO 4", address: "192.168.1.50")
            ],
            expectations: ["target-id": (pin: "2468", uuid: "TARGET-UUID", certPEM: targetCert.pem)]
        )

        // Wrong PIN fails and saves nothing
        let wrongPinOK = await runtime.handleApprovedPairing(
            deviceName: "PICO 4", deviceId: "pico-device-1", pin: "0000",
            clientAddress: "192.168.1.50", autoAllowFuture: true, pairingWaitSeconds: 1.0
        )
        XCTAssertFalse(wrongPinOK)
        XCTAssertNil(runtime.pairedDevices.first(where: { $0.deviceId == "pico-device-1" }))

        // Correct PIN binds exact cert & sets autoAllow = true
        let correctPinOK = await runtime.handleApprovedPairing(
            deviceName: "PICO 4", deviceId: "pico-device-1", pin: "2468",
            clientAddress: "192.168.1.50", autoAllowFuture: true, pairingWaitSeconds: 2.0
        )
        XCTAssertTrue(correctPinOK)
        XCTAssertTrue(fakeEngine.submissions().allSatisfy { $0.pairingId == "target-id" })

        let saved = try XCTUnwrap(runtime.pairedDevices.first(where: { $0.deviceId == "pico-device-1" }))
        XCTAssertEqual(saved.certFingerprint, targetCert.fingerprint)
        XCTAssertTrue(saved.autoAllow)
        XCTAssertTrue(runtime.authorizeMediaClient(deviceId: "pico-device-1", peerCertFingerprintSHA256: targetCert.fingerprint))
        XCTAssertFalse(runtime.authorizeMediaClient(deviceId: "pico-device-1", peerCertFingerprintSHA256: unrelatedCert.fingerprint))

        // S3: Re-pairing SAME `deviceId` ("pico-device-1") with a CHANGED certificate fingerprint
        //     MUST NOT inherit `autoAllow: true`!
        fakeEngine.setPendingPairings(
            [EnginePendingPairing(id: "rebind-id", name: "Spoofed/Reset PICO", address: "192.168.1.55")],
            expectations: ["rebind-id": (pin: "3344", uuid: "REPLACED-UUID", certPEM: replacementCert.pem)]
        )
        let rebindOK = await runtime.handleApprovedPairing(
            deviceName: "Spoofed/Reset PICO",
            deviceId: "pico-device-1",
            pin: "3344",
            clientAddress: "192.168.1.55",
            autoAllowFuture: false, // user did NOT check auto-allow on identity-changed prompt
            pairingWaitSeconds: 2.0
        )
        XCTAssertTrue(rebindOK)
        let replaced = try XCTUnwrap(runtime.pairedDevices.first(where: { $0.deviceId == "pico-device-1" }))
        XCTAssertEqual(replaced.certFingerprint, replacementCert.fingerprint)
        XCTAssertFalse(replaced.autoAllow, "S3: Changed certificate fingerprint MUST NOT inherit previous autoAllow!")
        let stillAuto = await coordinator.isAutoAllowed(deviceId: "pico-device-1", peerCertFingerprintSHA256: replacementCert.fingerprint)
        XCTAssertFalse(stillAuto, "S3: PairingCoordinator must clear autoAllow on identity change")

        // S7: Legacy unbound record (`certFingerprint == nil`) fails `/media`, then cleanly upgrades on re-pair
        XCTAssertFalse(runtime.authorizeMediaClient(deviceId: "legacy-dev", peerCertFingerprintSHA256: legacyUpgradedCert.fingerprint))
        fakeEngine.setPendingPairings(
            [EnginePendingPairing(id: "legacy-pid", name: "Legacy PICO", address: "192.168.1.60")],
            expectations: ["legacy-pid": (pin: "5566", uuid: "LEGACY-UPGRADED-UUID", certPEM: legacyUpgradedCert.pem)]
        )
        let legacyOK = await runtime.handleApprovedPairing(
            deviceName: "Legacy PICO",
            deviceId: "legacy-dev",
            pin: "5566",
            clientAddress: "192.168.1.60",
            autoAllowFuture: false,
            pairingWaitSeconds: 2.0
        )
        XCTAssertTrue(legacyOK)
        let upgraded = try XCTUnwrap(runtime.pairedDevices.first(where: { $0.deviceId == "legacy-dev" }))
        XCTAssertEqual(upgraded.engineUUID, "LEGACY-UPGRADED-UUID")
        XCTAssertEqual(upgraded.certFingerprint, legacyUpgradedCert.fingerprint)
        XCTAssertFalse(upgraded.autoAllow, "S7: Legacy upgrade must not silently inherit unverified autoAllow")
        XCTAssertTrue(runtime.authorizeMediaClient(deviceId: "legacy-dev", peerCertFingerprintSHA256: legacyUpgradedCert.fingerprint))

        // Removing device rotates WebDAV credentials & revokes old credentials immediately
        let oldAuth = "Basic " + Data("\(initialWebDAVCreds.user):\(initialWebDAVCreds.pass)".utf8).base64EncodedString()
        runtime.removePairedDevice(replaced)
        XCTAssertEqual(runtime.currentMediaCredentialRevision, 1)
        let newCreds = try XCTUnwrap(runtime.currentWebDAVCredentials)
        XCTAssertNotEqual(newCreds.pass, initialWebDAVCreds.pass)
        if case .immediate(let r) = webDAVHandler.route(HTTPRequest(method: "OPTIONS", rawTarget: "/", headers: ["Authorization": oldAuth])) {
            XCTAssertEqual(r.statusCode, 401)
        } else {
            XCTFail("Expected 401 for old credentials")
        }
    }

    private nonisolated static func runCurl(_ args: [String]) -> (Int, String) {
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
