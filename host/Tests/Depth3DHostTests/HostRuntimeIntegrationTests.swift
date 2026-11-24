import CommonCrypto
import CryptoKit
import Darwin
import Foundation
import Security
import XCTest
@testable import Depth3DHostCore

/// Real GameStream Gen-7+ (SHA-256 + AES-128-ECB + RSA-2048) pairing client used to execute
/// authentic 5-step pairings & HTTPS `/serverinfo` probes against the REAL `depth3d-stream-engine` binary.
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
        let step5XML = try httpsGet(step5URL, certURL: clientCertURL, keyURL: clientKeyURL, timeout: 10).body
        guard xmlTag(step5XML, "paired") == "1" else {
            throw PairError.stepFailed("pairchallenge failed: \(step5XML)")
        }
    }

    /// Queries Sunshine's authenticated HTTPS `/serverinfo` endpoint using `clientCertURL`/`clientKeyURL`
    /// to verify whether the REAL streaming engine accepts or rejects this client certificate!
    static func httpsServerInfoAccepted(
        httpsPort: UInt16,
        clientCertURL: URL,
        clientKeyURL: URL,
        uniqueID: String = "0123456789ABCDEF"
    ) -> Bool {
        let url = "https://127.0.0.1:\(httpsPort)/serverinfo?uniqueid=\(uniqueID)&uuid=\(UUID().uuidString)"
        guard let res = try? httpsGet(url, certURL: clientCertURL, keyURL: clientKeyURL, timeout: 5) else {
            return false
        }
        return res.exitCode == 0 && res.statusCode == 200 && res.body.contains("status_code=\"200\"")
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

    private static func extractX509SignatureBytes(fromCertPEMURL certURL: URL) throws -> Data {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        p.arguments = ["x509", "-in", certURL.path, "-outform", "DER"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let der = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()

        var idx = 0
        guard der.count > 10, der[idx] == 0x30 else { throw PairError.stepFailed("invalid X509 DER") }
        idx += 1; _ = try readDERLength(der, &idx)
        guard idx < der.count else { throw PairError.stepFailed("missing tbs") }
        idx += 1; idx += try readDERLength(der, &idx)
        guard idx < der.count else { throw PairError.stepFailed("missing sigAlg") }
        idx += 1; idx += try readDERLength(der, &idx)
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
        for _ in 0..<numBytes { len = (len << 8) | Int(data[idx]); idx += 1 }
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

    private static func httpsGet(
        _ url: String,
        certURL: URL,
        keyURL: URL,
        timeout: Int
    ) throws -> (exitCode: Int32, statusCode: Int, body: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        p.arguments = [
            "--noproxy", "*", "-sk", "--max-time", "\(timeout)",
            "--cert", certURL.path, "--key", keyURL.path,
            "-w", "\n%{http_code}", url
        ]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        let raw = String(decoding: data, as: UTF8.self)
        let lines = raw.components(separatedBy: "\n")
        let status = Int(lines.last ?? "") ?? 0
        let body = lines.dropLast().joined(separator: "\n")
        return (p.terminationStatus, status, body)
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

/// Loopback HTTPS responder using Sunshine v2026.914.233613's exact `root.named_devices` schema
/// and `/api/pin` (`GET`/`POST`/`DELETE`) + `/api/clients/unpair` for deterministic edge-case tests.
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
    private var cancelledPairingIds: [String] = []
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

    func addPairedDeviceDirectly(uuid: String, name: String, certPEM: String, enabled: String = "true") {
        lock.lock()
        pairedDevices.append(["uuid": uuid, "name": name, "cert": certPEM, "enabled": enabled])
        writeStateLocked()
        lock.unlock()
    }

    func submissions() -> [(pairingId: String, pin: String, name: String)] {
        lock.lock(); defer { lock.unlock() }; return receivedSubmissions
    }

    func cancelledIds() -> [String] {
        lock.lock(); defer { lock.unlock() }; return cancelledPairingIds
    }

    func removedUUIDs() -> [String] {
        lock.lock(); defer { lock.unlock() }; return unpairedUUIDs
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

        case ("DELETE", "/api/pin"):
            if let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
               let pairingId = json["pairing_id"] as? String
            {
                lock.lock()
                cancelledPairingIds.append(pairingId)
                pendingPairings.removeAll { $0.id == pairingId }
                lock.unlock()
            }
            return HTTPResponse.json(200, object: ["status": true])

        case ("GET", "/api/clients/list"):
            lock.lock()
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
    private struct RealEngineHarness {
        let tmp: URL
        let paths: HostPaths
        let store: HostStateStore
        let basePort: UInt16
        let httpsPort: UInt16
        let apiPort: UInt16
        let supervisor: EngineSupervisor
        let apiClient: EngineAPIClient
        let coordinator: PairingCoordinator
        let webDAVServer: WebDAVServer
        let davPort: UInt16
        let runtime: HostRuntime
        let controlServer: ControlHTTPSServer
        let controlPort: UInt16

        func tearDown() {
            controlServer.stop()
            webDAVServer.stop()
            supervisor.stop()
            try? FileManager.default.removeItem(at: tmp)
        }
    }

    private func startRealEngineHarness(portSeed: UInt16) async throws -> RealEngineHarness {
        let engineCandidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("host/build/engine-arm64/stream-engine/MacOS/depth3d-stream-engine")
        ]
        guard let realEngine = engineCandidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) }) else {
            throw XCTSkip("Staged depth3d-stream-engine binary not present")
        }

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-e2e-r4-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)

        var basePort = portSeed
        while !PortProbe.occupiedTCPPorts(in: HostPortDefaults.engineTCPPorts(basePort: basePort)).isEmpty {
            basePort += 30
        }
        let httpsPort = HostPortDefaults.engineHTTPSPort(basePort: basePort)
        let apiPort = HostPortDefaults.engineAPIPort(basePort: basePort)

        let paths = HostPaths(rootDirectory: tmp.appendingPathComponent("host-data", isDirectory: true))
        let store = try HostStateStore(paths: paths)
        let adminCreds = try store.loadOrCreateEngineAdminCredentials()
        let webdavCreds = try store.loadOrCreateWebDAVCredentials()

        let supervisor = EngineSupervisor(
            paths: paths,
            adminCredentials: adminCreds,
            engineExecutableOverride: realEngine
        )
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
        if !engineUp {
            supervisor.stop()
            try? FileManager.default.removeItem(at: tmp)
            XCTFail("Real built-in streaming engine failed to start")
        }

        let tlsManager = TLSIdentityManager(tlsDirectory: paths.tlsDirectory)
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "Depth3D Real E2E")
        let coordinator = PairingCoordinator(timeout: 20.0, cooldownDuration: 0.0)
        let webDAVHandler = WebDAVHandler(credentials: webdavCreds)
        let webDAVServer = WebDAVServer(handler: webDAVHandler)
        let davPort = try webDAVServer.start(preferredPort: 0)

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

        return RealEngineHarness(
            tmp: tmp, paths: paths, store: store,
            basePort: basePort, httpsPort: httpsPort, apiPort: apiPort,
            supervisor: supervisor, apiClient: apiClient, coordinator: coordinator,
            webDAVServer: webDAVServer, davPort: davPort, runtime: runtime,
            controlServer: controlServer, controlPort: controlPort
        )
    }

    private func generateClientCert(in dir: URL, name: String) throws -> (keyURL: URL, certURL: URL, fingerprint: String) {
        let keyURL = dir.appendingPathComponent("\(name).key")
        let certURL = dir.appendingPathComponent("\(name).crt")
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        p.arguments = [
            "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", keyURL.path, "-out", certURL.path,
            "-days", "1", "-nodes", "-subj", "/CN=\(name)"
        ]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try p.run()
        p.waitUntilExit()
        XCTAssertEqual(p.terminationStatus, 0)
        let pem = try String(contentsOf: certURL, encoding: .utf8)
        let fp = try XCTUnwrap(TLSIdentityManager.sha256Hex(fromPEM: pem))
        return (keyURL, certURL, fp)
    }

    /// P7(a): Real-engine first-time pairing using the new two-step protocol sequence:
    ///   1. Headset calls `POST /depth3d/v1/pair` FIRST (no GameStream session yet!)
    ///   2. User approves -> `/pair` IMMEDIATELY returns `200 {"ok":true,"next":"gamestream","ticket":"..."}`
    ///   3. Before GameStream pairing finishes, `/pair/complete` returns `409 not_paired` (keeping ticket valid) and `/media` returns `401`
    ///   4. Headset runs real 5-step GameStream pairing -> background 60s PIN feeder submits PIN -> real engine writes `state.json`
    ///   5. Headset calls `POST /depth3d/v1/pair/complete` with client cert -> `200 {"ok":true,"media":{"port":...}}`
    ///   6. Headset calls `GET /depth3d/v1/media` WITHOUT `X-Depth3d-Device` -> `200` -> WebDAV `OPTIONS 200`!
    func testRealEngineP7aFirstTimeTwoStepPairCompleteAndMedia200() async throws {
        let h = try await startRealEngineHarness(portSeed: 59849)
        defer { h.tearDown() }

        let client = try generateClientCert(in: h.tmp, name: "headset-p7a")
        let testPIN = "4826"

        // Step 1: Headset calls POST /depth3d/v1/pair BEFORE starting GameStream pairing!
        let pairTask = Task.detached { () -> (Int, String) in
            Self.runCurl([
                "-sk", "--max-time", "15", "-X", "POST",
                "-H", "Content-Type: application/json",
                "-d", "{\"device\":\"PICO 4 Ultra\",\"deviceId\":\"pico-p7a\",\"pin\":\"\(testPIN)\"}",
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/pair"
            ])
        }

        // User approves confirmation prompt
        var approved = false
        let promptDeadline = Date().addingTimeInterval(8.0)
        while Date() < promptDeadline {
            if let prompt = (await h.coordinator.pendingPrompts()).first {
                XCTAssertFalse(prompt.identityChanged)
                await h.coordinator.approve(promptId: prompt.id, rememberAutoAllow: true)
                approved = true
                break
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertTrue(approved)

        // Step 2: `/pair` returns IMMEDIATELY with `next: "gamestream"` and `ticket` (NOT waiting for GameStream!)
        let (pairCode, pairBody) = await pairTask.value
        XCTAssertEqual(pairCode, 200, "body=\(pairBody)")
        let pairResp = try JSONDecoder().decode(PairApprovedResponse.self, from: Data(pairBody.utf8))
        XCTAssertTrue(pairResp.ok)
        XCTAssertEqual(pairResp.next, "gamestream")
        XCTAssertFalse(pairResp.ticket.isEmpty)

        // Step 3: Before GameStream completes, device is NOT saved, `/pair/complete` returns 409 not_paired, `/media` returns 401
        XCTAssertTrue(h.runtime.pairedDevices.isEmpty)
        let (earlyCompleteCode, earlyCompleteBody) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "5", "-X", "POST",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "-H", "Content-Type: application/json",
                "-d", "{\"ticket\":\"\(pairResp.ticket)\",\"deviceId\":\"pico-p7a\",\"device\":\"PICO 4 Ultra\"}",
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/pair/complete"
            ])
        }.value
        XCTAssertEqual(earlyCompleteCode, 409, "Before GameStream completes, /pair/complete must return 409; body=\(earlyCompleteBody)")
        XCTAssertTrue(earlyCompleteBody.contains("\"not_paired\""))

        // Step 4: Now headset starts real 5-step GameStream pairing; Host's background PIN feeder supplies `testPIN`!
        let gsErr: Error? = await Task.detached {
            do {
                try RealGameStreamPairingClient.pair(
                    httpPort: h.basePort,
                    httpsPort: h.httpsPort,
                    pin: testPIN,
                    clientCertURL: client.certURL,
                    clientKeyURL: client.keyURL
                )
                return nil
            } catch {
                return error
            }
        }.value
        XCTAssertNil(gsErr, "Real 5-step GameStream pairing failed: \(String(describing: gsErr))")

        // Step 5: Headset calls `POST /depth3d/v1/pair/complete` using the SAME ticket + client cert -> 200!
        let (compCode, compBody) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "5", "-X", "POST",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "-H", "Content-Type: application/json",
                "-d", "{\"ticket\":\"\(pairResp.ticket)\",\"deviceId\":\"pico-p7a\",\"device\":\"PICO 4 Ultra\"}",
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/pair/complete"
            ])
        }.value
        XCTAssertEqual(compCode, 200, "POST /pair/complete must return 200; body=\(compBody)")
        let compResp = try JSONDecoder().decode(PairCompleteResponse.self, from: Data(compBody.utf8))
        XCTAssertTrue(compResp.ok)
        XCTAssertEqual(compResp.media?.port, Int(h.davPort))

        let saved = try XCTUnwrap(h.runtime.pairedDevices.first(where: { $0.id == client.fingerprint }))
        XCTAssertEqual(saved.certFingerprint, client.fingerprint)
        XCTAssertTrue(saved.autoAllow)

        // Step 6: `GET /depth3d/v1/media` with client cert and NO `X-Depth3d-Device` header -> 200 + working WebDAV!
        let (mediaCode, mediaBody) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "5",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/media"
            ])
        }.value
        XCTAssertEqual(mediaCode, 200, "GET /media without X-Depth3d-Device must return 200; body=\(mediaBody)")
        let mediaDecoded = try JSONDecoder().decode(MediaCredentialsResponse.self, from: Data(mediaBody.utf8))
        let dav = try XCTUnwrap(mediaDecoded.webdav)
        let (davCode, _) = await Task.detached {
            Self.runCurl(["-s", "--max-time", "5", "-u", "\(dav.user):\(dav.pass)", "-X", "OPTIONS", "http://127.0.0.1:\(dav.port)/"])
        }.value
        XCTAssertEqual(davCode, 200)
    }

    /// P7(b): Real-engine upgrade path — headset certificate is ALREADY paired in the real engine,
    /// while Host has ZERO device records:
    ///   1. Headset calls `POST /depth3d/v1/pair` WITH client cert -> confirmation prompt appears
    ///   2. User approves -> `/pair` immediately returns `200 {"ok":true,"next":"done","ticket":"..."}` (NO GameStream step!)
    ///   3. Headset calls `POST /depth3d/v1/pair/complete` -> `200` -> `GET /depth3d/v1/media` -> `200`!
    func testRealEngineP7bUpgradePathCertAlreadyInEngineNoHostRecord() async throws {
        let h = try await startRealEngineHarness(portSeed: 59909)
        defer { h.tearDown() }

        let client = try generateClientCert(in: h.tmp, name: "headset-p7b")
        let prePairPIN = "7319"

        // Pre-pair directly in the real streaming engine while Host has 0 pairedDevices!
        let preFeed = Task.detached { [api = h.apiClient] in
            await api.feedPINForApprovedSession(pin: prePairPIN, deviceName: "PrePaired", clientAddress: "127.0.0.1", maxWait: 15.0)
        }
        let preErr: Error? = await Task.detached {
            do {
                try RealGameStreamPairingClient.pair(
                    httpPort: h.basePort, httpsPort: h.httpsPort, pin: prePairPIN,
                    clientCertURL: client.certURL, clientKeyURL: client.keyURL
                )
                return nil
            } catch { return error }
        }.value
        _ = await preFeed.value
        XCTAssertNil(preErr)
        XCTAssertTrue(h.runtime.pairedDevices.isEmpty, "Host must have NO device records before upgrade path")
        XCTAssertTrue(EngineConfigGenerator.isCertificateUniquelyEnabled(fingerprintSHA256: client.fingerprint, at: h.paths.engineStateFile))

        // Headset sends `/pair` WITH its already-paired client certificate
        let pairTask = Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "15", "-X", "POST",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "-H", "Content-Type: application/json",
                "-d", "{\"device\":\"Upgraded PICO\",\"deviceId\":\"pico-p7b\",\"pin\":\"0000\"}",
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/pair"
            ])
        }

        // User approves ONCE on the Mac
        var approved = false
        let deadline = Date().addingTimeInterval(8.0)
        while Date() < deadline {
            if let prompt = (await h.coordinator.pendingPrompts()).first {
                XCTAssertFalse(prompt.identityChanged)
                await h.coordinator.approve(promptId: prompt.id, rememberAutoAllow: false)
                approved = true
                break
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertTrue(approved)

        // MUST receive `next: "done"` so headset skips GameStream pairing!
        let (pairCode, pairBody) = await pairTask.value
        XCTAssertEqual(pairCode, 200, "body=\(pairBody)")
        let pairResp = try JSONDecoder().decode(PairApprovedResponse.self, from: Data(pairBody.utf8))
        XCTAssertEqual(pairResp.next, "done", "P7(b): Cert already uniquely enabled in engine MUST return next:\"done\"")

        // Immediately call `/pair/complete` -> 200
        let (compCode, compBody) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "5", "-X", "POST",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "-H", "Content-Type: application/json",
                "-d", "{\"ticket\":\"\(pairResp.ticket)\",\"deviceId\":\"pico-p7b\",\"device\":\"Upgraded PICO\"}",
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/pair/complete"
            ])
        }.value
        XCTAssertEqual(compCode, 200, "body=\(compBody)")

        // `/media` immediately returns 200!
        let (mediaCode, _) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "5",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/media"
            ])
        }.value
        XCTAssertEqual(mediaCode, 200)
    }

    /// P7(c) (N1 regression test on REAL engine):
    ///   1. Same certificate paired TWICE in real `depth3d-stream-engine` -> real `state.json` has 2 duplicate entries
    ///      (confirming Sunshine's N1 bug where HTTPS `/serverinfo` rejects duplicated cert!)
    ///   2. Headset runs `/pair` -> `/pair/complete`: Host deduplicates down to strictly 1 enabled entry in real engine!
    ///   3. Real engine's HTTPS `/serverinfo` ACCEPTS this client cert again AND `/media` returns `200`!
    ///   4. Host removes the device (even with `engineUUID` cleared -> fingerprint-based removal!):
    ///      real `state.json` has 0 entries for this cert, `/media` returns `401`, AND real engine's HTTPS `/serverinfo` REJECTS this cert!
    func testRealEngineP7cDuplicateCertDedupEngineHTTPSAcceptAndRemovalReject() async throws {
        let h = try await startRealEngineHarness(portSeed: 59969)
        defer { h.tearDown() }

        let client = try generateClientCert(in: h.tmp, name: "headset-p7c")

        // Pair the EXACT SAME client certificate TWICE directly against the real engine -> creates 2 entries in state.json!
        // Note: on pairing #1, Sunshine's N1 bug appends a duplicate cert at Step 4 (`clientpairingsecret`),
        // causing Step 5 (`pairchallenge` HTTPS) itself to fail with 401 `Certificate verification failed`!
        for (idx, pin) in ["1122", "3344"].enumerated() {
            let feed = Task.detached { [api = h.apiClient] in
                await api.feedPINForApprovedSession(pin: pin, deviceName: "Dup-\(idx)", clientAddress: "127.0.0.1", maxWait: 15.0)
            }
            let err: Error? = await Task.detached {
                do {
                    try RealGameStreamPairingClient.pair(
                        httpPort: h.basePort, httpsPort: h.httpsPort, pin: pin,
                        clientCertURL: client.certURL, clientKeyURL: client.keyURL,
                        uniqueID: "0123456789ABCDE\(idx)"
                    )
                    return nil
                } catch { return error }
            }.value
            _ = await feed.value
            if idx == 0 {
                XCTAssertNil(err, "Initial pairing #0 failed: \(String(describing: err))")
            } else {
                // On duplicate pairing #1, Step 4 inserts the duplicate entry and Sunshine's own
                // Step 5 HTTPS check fails with 401 (reproducing the exact N1 bug live!).
                XCTAssertNotNil(err, "Expected Sunshine N1 duplicate-cert bug to reject Step 5 HTTPS on duplicate pairing #1")
            }
        }

        // Confirm real state.json has 2 duplicate entries for the exact same certificate AND real engine HTTPS rejects it!
        let dupEntries = EngineConfigGenerator.loadAllPairedCerts(at: h.paths.engineStateFile)
            .filter { $0.fingerprintSHA256 == client.fingerprint }
        XCTAssertEqual(dupEntries.count, 2, "Real Sunshine must have written 2 duplicate entries for same cert")
        let acceptedWhileDuplicated = await Task.detached {
            RealGameStreamPairingClient.httpsServerInfoAccepted(
                httpsPort: h.httpsPort,
                clientCertURL: client.certURL,
                clientKeyURL: client.keyURL
            )
        }.value
        XCTAssertFalse(acceptedWhileDuplicated, "Before dedup, real Sunshine N1 bug rejects duplicated cert on HTTPS")

        // Now complete pairing through Host `/pair` -> `/pair/complete` (with cert already in engine twice)
        let pairTask = Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "15", "-X", "POST",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "-H", "Content-Type: application/json",
                "-d", "{\"device\":\"Dedup PICO\",\"deviceId\":\"pico-p7c\",\"pin\":\"5566\"}",
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/pair"
            ])
        }
        let deadline = Date().addingTimeInterval(8.0)
        while Date() < deadline {
            if let prompt = (await h.coordinator.pendingPrompts()).first {
                await h.coordinator.approve(promptId: prompt.id, rememberAutoAllow: false)
                break
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        let (pairCode, pairBody) = await pairTask.value
        XCTAssertEqual(pairCode, 200)
        let pairResp = try JSONDecoder().decode(PairApprovedResponse.self, from: Data(pairBody.utf8))

        // Call `/pair/complete` -> Host deduplicates to strictly 1 enabled entry!
        let (compCode, compBody) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "10", "-X", "POST",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "-H", "Content-Type: application/json",
                "-d", "{\"ticket\":\"\(pairResp.ticket)\",\"deviceId\":\"pico-p7c\",\"device\":\"Dedup PICO\"}",
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/pair/complete"
            ])
        }.value
        XCTAssertEqual(compCode, 200, "body=\(compBody)")

        let afterDedup = EngineConfigGenerator.loadAllPairedCerts(at: h.paths.engineStateFile)
            .filter { $0.fingerprintSHA256 == client.fingerprint }
        XCTAssertEqual(afterDedup.count, 1, "P7(c): Host MUST deduplicate down to strictly 1 entry in real engine state.json")
        XCTAssertTrue(afterDedup[0].enabled)

        // Verify REAL engine HTTPS endpoint ACCEPTS this client cert now!
        let acceptedAfterDedup = await Task.detached {
            RealGameStreamPairingClient.httpsServerInfoAccepted(
                httpsPort: h.httpsPort,
                clientCertURL: client.certURL,
                clientKeyURL: client.keyURL
            )
        }.value
        XCTAssertTrue(acceptedAfterDedup, "P7(c): Real engine HTTPS must ACCEPT deduplicated client cert")

        // `/media` returns 200
        let (mediaOK, _) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "5",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/media"
            ])
        }.value
        XCTAssertEqual(mediaOK, 200)

        // Remove device using a record with `engineUUID == nil` to prove P3 fingerprint-based removal!
        var savedDevice = try XCTUnwrap(h.runtime.pairedDevices.first(where: { $0.id == client.fingerprint }))
        savedDevice.engineUUID = nil
        await h.runtime.removePairedDeviceAndWait(savedDevice)
        // Brief pause for Sunshine to flush state.json
        try await Task.sleep(nanoseconds: 250_000_000)

        let afterRemove = EngineConfigGenerator.loadAllPairedCerts(at: h.paths.engineStateFile)
            .filter { $0.fingerprintSHA256 == client.fingerprint }
        XCTAssertEqual(afterRemove.count, 0, "P7(c): Removal by fingerprint must remove ALL entries for this cert in engine")

        // Verify `/media` returns 401 AND REAL engine HTTPS REJECTS this cert!
        let (mediaRevoked, _) = await Task.detached {
            Self.runCurl([
                "-sk", "--max-time", "5",
                "--cert", client.certURL.path, "--key", client.keyURL.path,
                "https://127.0.0.1:\(h.controlPort)/depth3d/v1/media"
            ])
        }.value
        XCTAssertEqual(mediaRevoked, 401)

        let acceptedAfterRemove = await Task.detached {
            RealGameStreamPairingClient.httpsServerInfoAccepted(
                httpsPort: h.httpsPort,
                clientCertURL: client.certURL,
                clientKeyURL: client.keyURL
            )
        }.value
        XCTAssertFalse(acceptedAfterRemove, "P7(c): Real engine HTTPS MUST REJECT removed client certificate")
    }

    /// Covers P4 (identity-changed approval creates NEW record without overwriting original record or breaking
    /// original `/media`), P5 (multiple pending sessions from same IP all cancelled; reject cancels engine session),
    /// P3 (legacy nil-fingerprint removal), and WebDAV credential rotation!
    func testRealHostRuntimeIdentityChangeCreatesNewRecordAndCancelPendingPairings() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-runtime-r4-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let paths = HostPaths(rootDirectory: tmp)
        let store = try HostStateStore(paths: paths)
        let tlsManager = TLSIdentityManager(tlsDirectory: paths.tlsDirectory)
        let serverId = try tlsManager.loadOrCreateIdentity(commonName: "Fake Sunshine")

        func makePEM(_ bytes: [UInt8]) -> (pem: String, fingerprint: String) {
            let data = Data(bytes)
            return ("-----BEGIN CERTIFICATE-----\n\(data.base64EncodedString())\n-----END CERTIFICATE-----\n", TLSIdentityManager.sha256Hex(data))
        }

        let legitCert = makePEM([1, 2, 3, 4, 5, 6, 7, 8])
        let attackerCert = makePEM([9, 8, 7, 6, 5, 4, 3, 2])

        let fakeEngine = try FakeSunshineAPIServer(
            identity: serverId.secIdentity,
            stateFileURL: paths.engineStateFile,
            initialDevices: [
                ["uuid": "LEGIT-UUID", "name": "Legit PICO", "cert": legitCert.pem, "enabled": "true"],
                ["uuid": "ATTACKER-UUID", "name": "Spoofer", "cert": attackerCert.pem, "enabled": "true"]
            ]
        )
        defer { fakeEngine.stop() }

        // Seed 1 legitimate bound device (`autoAllow: true`) + 1 legacy nil-fingerprint device
        _ = try store.upsertPairedDevice(PairedDeviceRecord(
            deviceId: "shared-device-id",
            name: "Legit PICO",
            engineUUID: "LEGIT-UUID",
            certFingerprint: legitCert.fingerprint,
            autoAllow: true
        ))
        let legacyRecord = PairedDeviceRecord(
            deviceId: "legacy-nil-fp",
            name: "Old Round1 PICO",
            engineUUID: nil,
            certFingerprint: nil,
            autoAllow: false
        )
        _ = try store.upsertPairedDevice(legacyRecord)

        let apiClient = EngineAPIClient(
            apiPort: fakeEngine.port,
            credentials: EngineAdminCredentials(username: "u", password: "pw")
        )
        let coordinator = PairingCoordinator(timeout: 5.0, cooldownDuration: 0.0, autoAllowedFingerprints: [legitCert.fingerprint])
        let initialWebDAVCreds = try store.loadOrCreateWebDAVCredentials()
        let webDAVHandler = WebDAVHandler(credentials: initialWebDAVCreds)

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

        // 1. P4: Identity-changed device using the SAME `shared-device-id` with `attackerCert`
        let knownFPs = runtime.knownBoundCertFingerprints(forDeviceId: "shared-device-id")
        XCTAssertEqual(knownFPs, [legitCert.fingerprint])

        let ticketRes = await runtime.issueApprovedPairingTicket(
            deviceName: "Spoofer PICO",
            deviceId: "shared-device-id",
            pin: "1234",
            clientAddress: "192.168.1.55",
            peerCertFingerprintSHA256: attackerCert.fingerprint,
            autoAllowFuture: false,
            approvedViaAutoAllow: false
        )
        guard case .issued(let next, let ticket) = ticketRes else {
            return XCTFail("Expected ticket issued")
        }
        XCTAssertEqual(next, "done")

        let comp = await runtime.completePairing(
            ticket: ticket,
            deviceName: "Spoofer PICO",
            deviceId: "shared-device-id",
            clientAddress: "192.168.1.55",
            peerCertFingerprintSHA256: attackerCert.fingerprint
        )
        XCTAssertEqual(comp, .completed(mediaPort: 8890))

        // CRITICAL (P4): BOTH records exist independently (primary key = certFingerprint)!
        // Original Legit PICO was NEVER overwritten and STILL keeps `autoAllow == true` AND `/media` access!
        let legitAfter = try XCTUnwrap(runtime.pairedDevices.first(where: { $0.id == legitCert.fingerprint }))
        let attackerAfter = try XCTUnwrap(runtime.pairedDevices.first(where: { $0.id == attackerCert.fingerprint }))
        XCTAssertTrue(legitAfter.autoAllow, "P4: Original device's autoAllow must remain untouched")
        XCTAssertFalse(attackerAfter.autoAllow, "P4: Identity-changed record must NOT inherit autoAllow")
        XCTAssertTrue(runtime.authorizeMediaClient(peerCertFingerprintSHA256: legitCert.fingerprint), "P4: Original device /media must stay 200")
        XCTAssertTrue(runtime.authorizeMediaClient(peerCertFingerprintSHA256: attackerCert.fingerprint))

        // Removing the identity-changed record deletes ONLY attackerAfter, leaving legitAfter intact!
        await runtime.removePairedDeviceAndWait(attackerAfter)
        XCTAssertNil(runtime.pairedDevices.first(where: { $0.id == attackerCert.fingerprint }))
        XCTAssertNotNil(runtime.pairedDevices.first(where: { $0.id == legitCert.fingerprint }))
        XCTAssertTrue(runtime.authorizeMediaClient(peerCertFingerprintSHA256: legitCert.fingerprint))
        XCTAssertEqual(fakeEngine.removedUUIDs(), ["ATTACKER-UUID"])
        XCTAssertEqual(runtime.currentMediaCredentialRevision, 1)

        // 2. P3: Removing legacy nil-fingerprint record simply deletes record without touching Legit PICO in engine!
        await runtime.removePairedDeviceAndWait(legacyRecord)
        XCTAssertNil(runtime.pairedDevices.first(where: { $0.id == legacyRecord.id }))
        XCTAssertEqual(fakeEngine.removedUUIDs(), ["ATTACKER-UUID"])

        // 3. P5: Multiple pending sessions from the same IP are ALL cancelled by background PIN feeder (no PIN submitted)!
        fakeEngine.setPendingPairings(
            [
                EnginePendingPairing(id: "dup-sess-1", name: "A", address: "192.168.1.77"),
                EnginePendingPairing(id: "dup-sess-2", name: "B", address: "192.168.1.77")
            ],
            expectations: [:]
        )
        let fed = await apiClient.feedPINForApprovedSession(
            pin: "9999", deviceName: "A", clientAddress: "192.168.1.77", maxWait: 2.0, pollInterval: 0.05
        )
        XCTAssertFalse(fed)
        XCTAssertTrue(fakeEngine.submissions().isEmpty, "Must never submit PIN when same IP has multiple pending sessions")
        XCTAssertEqual(Set(fakeEngine.cancelledIds()), Set(["dup-sess-1", "dup-sess-2"]))
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
