import Darwin
import Foundation
import XCTest
@testable import Depth3DHostCore

final class WebDAVTests: XCTestCase {
    func testByteRangeParserCases() {
        XCTAssertEqual(ByteRangeParser.parse("bytes=0-9", fileSize: 100), .satisfied(start: 0, end: 9))
        XCTAssertEqual(ByteRangeParser.parse("bytes=90-500", fileSize: 100), .satisfied(start: 90, end: 99))
        XCTAssertEqual(ByteRangeParser.parse("bytes=50-", fileSize: 100), .satisfied(start: 50, end: 99))
        XCTAssertEqual(ByteRangeParser.parse("bytes=-10", fileSize: 100), .satisfied(start: 90, end: 99))
        XCTAssertEqual(ByteRangeParser.parse("bytes=-500", fileSize: 100), .satisfied(start: 0, end: 99))
        XCTAssertEqual(ByteRangeParser.parse("bytes=100-120", fileSize: 100), .unsatisfiable)
        XCTAssertEqual(ByteRangeParser.parse("bytes=0-0", fileSize: 0), .unsatisfiable)
        XCTAssertEqual(ByteRangeParser.parse("bytes=20-10", fileSize: 100), .invalid)
        XCTAssertEqual(ByteRangeParser.parse("items=0-5", fileSize: 100), .invalid)
    }

    func testWebDAVAuthVerification() {
        let creds = WebDAVCredentials(user: "depth3d", pass: "s3cr3t")
        let valid = "Basic " + Data("depth3d:s3cr3t".utf8).base64EncodedString()
        let wrong = "Basic " + Data("depth3d:wrong".utf8).base64EncodedString()

        XCTAssertTrue(WebDAVAuth.verify(authorizationHeader: valid, credentials: creds))
        XCTAssertFalse(WebDAVAuth.verify(authorizationHeader: wrong, credentials: creds))
        XCTAssertFalse(WebDAVAuth.verify(authorizationHeader: nil, credentials: creds))
        XCTAssertFalse(WebDAVAuth.verify(authorizationHeader: "Bearer abc", credentials: creds))
    }

    func testPathSanitizerRejectsTraversal() {
        XCTAssertEqual(WebDAVPathSanitizer.resolve("/")?.segments, [])
        XCTAssertEqual(
            WebDAVPathSanitizer.resolve("/%E7%94%B5%E5%BD%B1/test%20video.mp4")?.segments,
            ["电影", "test video.mp4"]
        )
        XCTAssertNil(WebDAVPathSanitizer.resolve("/movies/../etc/passwd"))
        XCTAssertNil(WebDAVPathSanitizer.resolve("/movies/%2e%2e/secret"))
        XCTAssertNil(WebDAVPathSanitizer.resolve("/movies/a%2Fb"))
        XCTAssertNil(WebDAVPathSanitizer.resolve("relative/path"))
    }

    func testStreamingRewriteProxiedMultiStatusXMLOver8MBWithoutTruncation() throws {
        // Generate > 9 MB XML to guarantee zero 8 MB truncation and test small chunk splits!
        let rewriter = WebDAVHrefStreamRewriter(mountName: "Google Drive")
        var output = Data()

        let header = Data("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n".utf8)
        output.append(rewriter.push(header))

        // ~40,000 entries * ~250 bytes ~= 10 MB
        let entryCount = 40_000
        var batch = Data()
        for idx in 0..<entryCount {
            let line = "  <D:response><D:href>/Movies/Subfolder/Clip_\(idx)_12345678901234567890.mp4</D:href><D:propstat><D:prop><D:displayname>Clip_\(idx).mp4</D:displayname><D:getcontentlength>1048576</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n"
            batch.append(contentsOf: line.utf8)
            if batch.count >= 13_337 { // intentionally awkward prime chunk size splitting tags mid-stream
                output.append(rewriter.push(batch))
                batch.removeAll(keepingCapacity: true)
            }
        }
        batch.append(contentsOf: "</D:multistatus>\n".utf8)
        output.append(rewriter.push(batch))
        output.append(rewriter.finish())

        XCTAssertGreaterThan(output.count, 9 * 1024 * 1024, "Rewritten XML must exceed 9 MB without 8 MB truncation")
        let headStr = String(decoding: output.prefix(512), as: UTF8.self)
        let tailStr = String(decoding: output.suffix(512), as: UTF8.self)
        XCTAssertTrue(headStr.contains("<D:href>/Google%20Drive/Movies/Subfolder/Clip_0_"))
        XCTAssertTrue(tailStr.contains("<D:href>/Google%20Drive/Movies/Subfolder/Clip_\(entryCount - 1)_"))
        XCTAssertTrue(tailStr.contains("</D:multistatus>"))
    }

    func testWebDAVServerLivePropfindRangeAuthHiddenFilesEncodedTraversalAndRateLimit() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-webdav-test-\(UUID().uuidString)", isDirectory: true)
        let moviesDir = tmp.appendingPathComponent("Movies", isDirectory: true)
        let outsideFile = tmp.appendingPathComponent("secret.txt")
        try FileManager.default.createDirectory(at: moviesDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let sampleContent = Data("ABCDEFGHIJKLMNOPQRSTUVWXYZ".utf8)
        try sampleContent.write(to: moviesDir.appendingPathComponent("样片.mp4"))
        try Data("hidden-secret".utf8).write(to: moviesDir.appendingPathComponent(".DS_Store"))
        try Data("hidden-video".utf8).write(to: moviesDir.appendingPathComponent(".secret.mp4"))
        try Data("outside-secret".utf8).write(to: outsideFile)
        try FileManager.default.createSymbolicLink(
            at: moviesDir.appendingPathComponent("escape.mp4"),
            withDestinationURL: outsideFile
        )

        let creds = WebDAVCredentials(user: "depth3d", pass: "davpass88")
        let handler = WebDAVHandler(
            credentials: creds,
            localMounts: [WebDAVMount(name: "电影", rootURL: moviesDir)]
        )
        let server = WebDAVServer(handler: handler, maxConnections: 16, maxUnauthRequestsPerMinute: 10)
        let port = try server.start(preferredPort: 0)
        defer { server.stop() }

        let auth = "\(creds.user):\(creds.pass)"
        let basicHeader = "Basic " + Data(auth.utf8).base64EncodedString()

        // 1. No auth -> 401
        XCTAssertEqual(curl(["-s", "http://127.0.0.1:\(port)/"]).0, 401)

        // 2. OPTIONS -> 200
        XCTAssertEqual(curl(["-s", "-u", auth, "-X", "OPTIONS", "http://127.0.0.1:\(port)/"]).0, 200)

        // 3. PROPFIND / -> 207 listing mounted folder "电影"
        let (c3, body3) = curl(["-s", "-u", auth, "-X", "PROPFIND", "-H", "Depth: 1", "http://127.0.0.1:\(port)/"])
        XCTAssertEqual(c3, 207)
        XCTAssertTrue(body3.contains("<d:displayname>电影</d:displayname>"))

        // 4. PROPFIND /电影/ -> lists 样片.mp4, skips .DS_Store and .secret.mp4
        let encodedDir = WebDAVPathSanitizer.encodeHref(segments: ["电影"], isDirectory: true)
        let (c4, body4) = curl(["-s", "-u", auth, "-X", "PROPFIND", "-H", "Depth: 1", "http://127.0.0.1:\(port)\(encodedDir)"])
        XCTAssertEqual(c4, 207)
        XCTAssertTrue(body4.contains("<d:displayname>样片.mp4</d:displayname>"))
        XCTAssertFalse(body4.contains(".DS_Store"))
        XCTAssertFalse(body4.contains(".secret.mp4"))

        // 5. Direct GET and HEAD on hidden files MUST return 404 (not readable!)
        let hiddenDS = WebDAVPathSanitizer.encodeHref(segments: ["电影", ".DS_Store"], isDirectory: false)
        let hiddenMP4 = WebDAVPathSanitizer.encodeHref(segments: ["电影", ".secret.mp4"], isDirectory: false)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(hiddenDS)"]).0, 404)
        XCTAssertEqual(curl(["-s", "-I", "-u", auth, "http://127.0.0.1:\(port)\(hiddenDS)"]).0, 404)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(hiddenMP4)"]).0, 404)

        // 6. GET full file & Range 206 & 416 & read-only PUT 405
        let encodedFile = WebDAVPathSanitizer.encodeHref(segments: ["电影", "样片.mp4"], isDirectory: false)
        let (c5, body5) = curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(encodedFile)"])
        XCTAssertEqual(c5, 200)
        XCTAssertEqual(body5, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")

        let (c6, body6) = curl(["-s", "-u", auth, "-H", "Range: bytes=4-11", "http://127.0.0.1:\(port)\(encodedFile)"])
        XCTAssertEqual(c6, 206)
        XCTAssertEqual(body6, "EFGHIJKL")

        XCTAssertEqual(curl(["-s", "-u", auth, "-H", "Range: bytes=100-200", "http://127.0.0.1:\(port)\(encodedFile)"]).0, 416)
        XCTAssertEqual(curl(["-s", "-u", auth, "-X", "PUT", "-d", "hack", "http://127.0.0.1:\(port)\(encodedFile)"]).0, 405)

        // 7. Symlink escape outside mounted root -> 403
        let escapePath = WebDAVPathSanitizer.encodeHref(segments: ["电影", "escape.mp4"], isDirectory: false)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(escapePath)"]).0, 403)

        // 8. Encoded AND raw `../` traversal sent directly over raw HTTP socket (bypassing curl path normalization!)
        let rawEncodedTraversal = sendRawHTTP(
            port: port,
            request: "GET /%E7%94%B5%E5%BD%B1/%2e%2e/secret.txt HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: \(basicHeader)\r\nConnection: close\r\n\r\n"
        )
        XCTAssertTrue(rawEncodedTraversal.hasPrefix("HTTP/1.1 400"), "Encoded %2e%2e traversal over live HTTP must return 400, got: \(rawEncodedTraversal.prefix(80))")
        XCTAssertFalse(rawEncodedTraversal.contains("outside-secret"))

        let rawDotDotTraversal = sendRawHTTP(
            port: port,
            request: "GET /%E7%94%B5%E5%BD%B1/../secret.txt HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: \(basicHeader)\r\nConnection: close\r\n\r\n"
        )
        XCTAssertTrue(rawDotDotTraversal.hasPrefix("HTTP/1.1 400"), "Raw ../ traversal over live HTTP must return 400")
        XCTAssertFalse(rawDotDotTraversal.contains("outside-secret"))

        // 9. Unauthenticated / failed Basic Auth rate limit -> 429
        let rlServer = WebDAVServer(handler: handler, maxConnections: 8, maxUnauthRequestsPerMinute: 2)
        let rlPort = try rlServer.start(preferredPort: 0)
        defer { rlServer.stop() }
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong1", "http://127.0.0.1:\(rlPort)/"]).0, 401)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong2", "http://127.0.0.1:\(rlPort)/"]).0, 401)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong3", "http://127.0.0.1:\(rlPort)/"]).0, 429)
    }

    func testWebDAVServerPortFallbackWhenPreferredPortOccupied() throws {
        // Occupy a specific port with a dummy listening socket
        let blockerFD = Darwin.socket(AF_INET6, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(blockerFD, 0)
        defer { Darwin.close(blockerFD) }
        var zero: Int32 = 0
        setsockopt(blockerFD, IPPROTO_IPV6, IPV6_V6ONLY, &zero, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in6()
        addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        addr.sin6_family = sa_family_t(AF_INET6)
        addr.sin6_addr = in6addr_any
        addr.sin6_port = 0
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(blockerFD, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
            }
        }
        XCTAssertEqual(bound, 0)
        XCTAssertEqual(Darwin.listen(blockerFD, 4), 0)
        var boundAddr = sockaddr_in6()
        var len = socklen_t(MemoryLayout<sockaddr_in6>.size)
        _ = withUnsafeMutablePointer(to: &boundAddr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(blockerFD, $0, &len)
            }
        }
        let occupiedPort = UInt16(bigEndian: boundAddr.sin6_port)

        let creds = WebDAVCredentials(user: "depth3d", pass: "fallbackpass")
        let server = WebDAVServer(handler: WebDAVHandler(credentials: creds))
        let actualPort = try server.start(preferredPort: occupiedPort)
        defer { server.stop() }

        XCTAssertNotEqual(actualPort, occupiedPort, "WebDAVServer must fall back to a free port when preferredPort is occupied")
        XCTAssertEqual(curl(["-s", "-u", "depth3d:fallbackpass", "-X", "OPTIONS", "http://127.0.0.1:\(actualPort)/"]).0, 200)
    }

    /// Tests live `WebDAVServer` connection limit (`503`) AND live chunked `PROPFIND` proxy
    /// streaming > 9 MB XML from a loopback upstream without 8 MB truncation.
    func testWebDAVServerLiveConnectionCapAndLargeProxiedPropfind() throws {
        // 1. Connection cap test (maxConnections = 1): hold 1 raw TCP connection open, verify 2nd gets 503
        let creds = WebDAVCredentials(user: "depth3d", pass: "captest99")
        let capServer = WebDAVServer(handler: WebDAVHandler(credentials: creds), maxConnections: 1, readTimeoutSeconds: 3)
        let capPort = try capServer.start(preferredPort: 0)
        defer { capServer.stop() }

        let holdFD = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(holdFD, 0)
        defer { Darwin.close(holdFD) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = capPort.bigEndian
        let connRC = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(holdFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        XCTAssertEqual(connRC, 0)
        Thread.sleep(forTimeInterval: 0.1)

        // Second connection while first is held must receive 503 Service Unavailable
        let (codeOverCap, _) = curl(["-s", "-u", "depth3d:captest99", "-X", "OPTIONS", "http://127.0.0.1:\(capPort)/"])
        XCTAssertEqual(codeOverCap, 503, "Connections exceeding maxConnections must receive 503")
        Darwin.close(holdFD)

        // 2. Live streaming proxied PROPFIND > 9 MB over chunked transfer encoding
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-large-proxy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // Tiny Python upstream HTTP server acting as `rclone serve webdav`, serving > 9 MB chunked 207 XML
        let fakeUpstream = tmp.appendingPathComponent("fake-rclone-9mb.py")
        try """
        #!/usr/bin/env python3
        import http.server, os, sys
        addr = None
        for i, a in enumerate(sys.argv):
            if a == '--addr' and i + 1 < len(sys.argv):
                addr = sys.argv[i + 1]
        host, port = addr.split(':')
        port = int(port)
        class H(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *args): pass
            def do_PROPFIND(self):
                self.send_response(207, "Multi-Status")
                self.send_header("Content-Type", "application/xml; charset=utf-8")
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Connection", "close")
                self.end_headers()
                def send_chunk(b):
                    self.wfile.write(f"{len(b):x}\\r\\n".encode() + b + b"\\r\\n")
                send_chunk(b'<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">\\n')
                batch = []
                for idx in range(38000):
                    batch.append(f'<D:response><D:href>/BigFolder/Video_{idx:05d}_PaddingPaddingPaddingPaddingPaddingPadding.mp4</D:href><D:propstat><D:prop><D:displayname>V_{idx}.mp4</D:displayname></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\\n')
                    if len(batch) >= 200:
                        send_chunk(''.join(batch).encode())
                        batch.clear()
                if batch:
                    send_chunk(''.join(batch).encode())
                send_chunk(b'</D:multistatus>\\n')
                self.wfile.write(b'0\\r\\n\\r\\n')
                self.wfile.flush()
        http.server.HTTPServer((host, port), H).serve_forever()
        """.write(to: fakeUpstream, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: fakeUpstream.path)

        let rclone = RcloneBridgeManager(executableOverride: fakeUpstream.path, startupConfirmDelay: 0.1)
        defer { rclone.stopAll() }
        rclone.syncRemotes([CloudRemoteEntry(displayName: "BigCloud", remoteSpec: "big:")])
        Thread.sleep(forTimeInterval: 0.35)

        let davHandler = WebDAVHandler(credentials: creds, localMounts: [], rcloneManager: rclone)
        let davServer = WebDAVServer(handler: davHandler)
        let davPort = try davServer.start(preferredPort: 0)
        defer { davServer.stop() }

        let (status, body) = curl([
            "-s", "--max-time", "15",
            "-u", "depth3d:captest99",
            "-X", "PROPFIND", "-H", "Depth: 1",
            "http://127.0.0.1:\(davPort)/BigCloud/"
        ])
        XCTAssertEqual(status, 207)
        XCTAssertGreaterThan(body.utf8.count, 8 * 1024 * 1024, "Proxied PROPFIND must stream >8 MiB intact without 8 MB truncation")
        XCTAssertTrue(body.contains("<D:href>/BigCloud/BigFolder/Video_00000_"))
        XCTAssertTrue(body.contains("<D:href>/BigCloud/BigFolder/Video_37999_"))
        XCTAssertTrue(body.contains("</D:multistatus>"))
    }

    func testRcloneBridgeDuplicateNamesSerialSyncStderrClassificationAndCrashRestart() throws {
        // 1. Pure stderr error classification checks (Chinese actionable copy)
        let authErr = RcloneBridgeManager.classifyStderr(
            "2026/11/25 ERROR : Failed to create file system: couldn't fetch token: invalid_grant",
            remoteSpec: "gdrive:"
        )
        XCTAssertTrue(authErr.isPermanentConfigError)
        XCTAssertTrue(authErr.message.contains("rclone config reconnect gdrive"))

        let missingErr = RcloneBridgeManager.classifyStderr(
            "2026/11/25 CRITICAL: Failed to create file system for \"onedrive:\": didn't find section in config file",
            remoteSpec: "onedrive:"
        )
        XCTAssertTrue(missingErr.isPermanentConfigError)
        XCTAssertTrue(missingErr.message.contains("onedrive"))

        let netErr = RcloneBridgeManager.classifyStderr(
            "dial tcp: lookup www.googleapis.com: no such host",
            remoteSpec: "gdrive:"
        )
        XCTAssertFalse(netErr.isPermanentConfigError)
        XCTAssertEqual(netErr.message, HostStrings.string("rclone.error.network"))

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-rclone-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // 2. Fake healthy rclone script: records CLI args & verifies RCLONE_PASS comes via env (NEVER CLI!)
        let argsLog = tmp.appendingPathComponent("args.txt")
        let envCheck = tmp.appendingPathComponent("env-ok.txt")
        let healthyScript = tmp.appendingPathComponent("fake-rclone-ok.sh")
        try """
        #!/bin/sh
        echo "$@" >> "\(argsLog.path)"
        if [ -n "$RCLONE_USER" ] && [ -n "$RCLONE_PASS" ]; then
          echo "ENV_CREDS_PRESENT" > "\(envCheck.path)"
        fi
        sleep 30
        """.write(to: healthyScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: healthyScript.path)

        let manager = RcloneBridgeManager(
            ledger: ChildProcessLedger(ledgerURL: tmp.appendingPathComponent("ledger.json")),
            executableOverride: healthyScript.path,
            startupConfirmDelay: 0.1
        )
        defer { manager.stopAll() }

        // Duplicate displayName entries must NEVER crash and must only spawn 1 process
        let dupA = CloudRemoteEntry(displayName: "我的云盘", remoteSpec: "gdrive:")
        let dupB = CloudRemoteEntry(displayName: "我的云盘", remoteSpec: "gdrive2:")

        // Concurrent syncRemotes from 6 parallel queues must serialize cleanly and launch only once
        let group = DispatchGroup()
        for _ in 0..<6 {
            group.enter()
            DispatchQueue.global().async {
                manager.syncRemotes([dupA, dupB])
                group.leave()
            }
        }
        group.wait()

        Thread.sleep(forTimeInterval: 0.25)
        XCTAssertEqual(manager.totalLaunchAttempts(), 1, "Concurrent syncRemotes + duplicate names must only start 1 backend")
        XCTAssertEqual(manager.activeEndpoints().count, 1)
        XCTAssertEqual(manager.currentStatuses()["我的云盘"], .running)

        // Confirm secrets were passed via environment and NEVER on CLI args
        let capturedArgs = (try? String(contentsOf: argsLog, encoding: .utf8)) ?? ""
        XCTAssertFalse(capturedArgs.contains("--pass"), "rclone password must NEVER appear in CLI arguments")
        XCTAssertFalse(capturedArgs.contains("--user"), "rclone username must NEVER appear in CLI arguments")
        XCTAssertEqual((try? String(contentsOf: envCheck, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines), "ENV_CREDS_PRESENT")
        manager.stopAll()

        // 3. Crash auto-restart test with exponential backoff
        let crashingScript = tmp.appendingPathComponent("fake-rclone-crash.sh")
        try """
        #!/bin/sh
        echo "transient crash" >&2
        exit 1
        """.write(to: crashingScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: crashingScript.path)

        let crashManager = RcloneBridgeManager(
            executableOverride: crashingScript.path,
            backoffDelays: [0.05, 0.08],
            startupConfirmDelay: 0.2
        )
        defer { crashManager.stopAll() }

        let crashDoneExp = expectation(description: "Crashing rclone exhausts retries and reports error")
        crashManager.setStatusesChangedHandler { statuses in
            if case .error = statuses["云盘A"] {
                crashDoneExp.fulfill()
            }
        }
        crashManager.syncRemotes([CloudRemoteEntry(displayName: "云盘A", remoteSpec: "gdrive:")])
        wait(for: [crashDoneExp], timeout: 5.0)
        XCTAssertEqual(crashManager.totalLaunchAttempts(), 3, "Must auto-restart crashed rclone across backoff schedule")

        // 4. Permanent auth-expired error surfaces Chinese guidance immediately without endless restart loop
        let authExpiredScript = tmp.appendingPathComponent("fake-rclone-auth.sh")
        try """
        #!/bin/sh
        echo "ERROR : couldn't fetch token: invalid_grant" >&2
        exit 1
        """.write(to: authExpiredScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: authExpiredScript.path)

        let authManager = RcloneBridgeManager(
            executableOverride: authExpiredScript.path,
            backoffDelays: [0.05, 0.08]
        )
        defer { authManager.stopAll() }

        let authErrExp = expectation(description: "Auth-expired error reported immediately")
        authManager.setStatusesChangedHandler { statuses in
            if case .error(let msg) = statuses["Google Drive"], msg.contains("rclone config reconnect gdrive") {
                authErrExp.fulfill()
            }
        }
        authManager.syncRemotes([CloudRemoteEntry(displayName: "Google Drive", remoteSpec: "gdrive:")])
        wait(for: [authErrExp], timeout: 5.0)
        XCTAssertEqual(authManager.totalLaunchAttempts(), 1, "Permanent OAuth error should stop immediately and guide user")
    }

    private func sendRawHTTP(port: UInt16, request: String) -> String {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return "" }
        defer { Darwin.close(fd) }
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
        guard rc == 0 else { return "" }
        let reqData = Data(request.utf8)
        reqData.withUnsafeBytes { raw in
            if let base = raw.baseAddress {
                _ = Darwin.send(fd, base, reqData.count, 0)
            }
        }
        var resp = Data()
        var buf = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = Darwin.recv(fd, &buf, buf.count, 0)
            if n > 0 {
                resp.append(buf, count: n)
            } else {
                break
            }
        }
        return String(decoding: resp, as: UTF8.self)
    }

    private func curl(_ args: [String]) -> (Int, String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        p.arguments = ["--noproxy", "*", "--max-time", "15"] + args + ["-w", "\n%{http_code}"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try? p.run()
        // Drain stdout BEFORE waitUntilExit to avoid 64 KB kernel pipe deadlock on >9 MB responses!
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        let raw = String(data: data, encoding: .utf8) ?? ""
        let lines = raw.components(separatedBy: "\n")
        let code = Int(lines.last ?? "") ?? 0
        let body = lines.dropLast().joined(separator: "\n")
        return (code, body)
    }
}
