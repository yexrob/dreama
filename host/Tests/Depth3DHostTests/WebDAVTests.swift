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
        let rewriter = WebDAVHrefStreamRewriter(mountName: "Google Drive")
        var output = Data()

        output.append(rewriter.push(Data("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n".utf8)))
        let entryCount = 40_000
        var batch = Data()
        for idx in 0..<entryCount {
            let line = "  <D:response><D:href>/Movies/Subfolder/Clip_\(idx)_12345678901234567890.mp4</D:href><D:propstat><D:prop><D:displayname>Clip_\(idx).mp4</D:displayname><D:getcontentlength>1048576</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n"
            batch.append(contentsOf: line.utf8)
            if batch.count >= 13_337 {
                output.append(rewriter.push(batch))
                batch.removeAll(keepingCapacity: true)
            }
        }
        batch.append(contentsOf: "</D:multistatus>\n".utf8)
        output.append(rewriter.push(batch))
        output.append(rewriter.finish())

        XCTAssertGreaterThan(output.count, 9 * 1024 * 1024)
        let tailStr = String(decoding: output.suffix(512), as: UTF8.self)
        XCTAssertTrue(tailStr.contains("<D:href>/Google%20Drive/Movies/Subfolder/Clip_\(entryCount - 1)_"))
        XCTAssertTrue(tailStr.contains("</D:multistatus>"))
    }

    /// Covers local WebDAV operations + m2 (symlink into `.hidden_dir`, `UF_HIDDEN` file, escaping
    /// symlink omitted from PROPFIND) + encoded `../` over raw TCP + S4 (headerless 401 never counts
    /// toward rate limit, valid auth resets failure count, only wrong credentials trigger 429!).
    func testWebDAVServerLivePropfindRangeHiddenSymlinksUFHiddenTraversalAndSmartAuthRateLimit() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-webdav-test-\(UUID().uuidString)", isDirectory: true)
        let moviesDir = tmp.appendingPathComponent("Movies", isDirectory: true)
        let hiddenDir = moviesDir.appendingPathComponent(".hidden_vault", isDirectory: true)
        let outsideFile = tmp.appendingPathComponent("secret.txt")
        try FileManager.default.createDirectory(at: hiddenDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let sampleContent = Data("ABCDEFGHIJKLMNOPQRSTUVWXYZ".utf8)
        try sampleContent.write(to: moviesDir.appendingPathComponent("样片.mp4"))
        try Data("hidden-secret".utf8).write(to: moviesDir.appendingPathComponent(".DS_Store"))
        try Data("vault-movie".utf8).write(to: hiddenDir.appendingPathComponent("vault.mp4"))
        try Data("outside-secret".utf8).write(to: outsideFile)

        // m2 case 1: symlink inside root pointing INTO a `.hidden_vault` directory inside root
        try FileManager.default.createSymbolicLink(
            at: moviesDir.appendingPathComponent("sneaky_link.mp4"),
            withDestinationURL: hiddenDir.appendingPathComponent("vault.mp4")
        )

        // m2 case 2: file marked with macOS `UF_HIDDEN` flag (`chflags hidden`)
        let ufHiddenFile = moviesDir.appendingPathComponent("uf_hidden.mp4")
        try Data("uf-hidden-content".utf8).write(to: ufHiddenFile)
        XCTAssertEqual(chflags(ufHiddenFile.path, UInt32(UF_HIDDEN)), 0)

        // m2 case 3: symlink escaping outside the shared root
        try FileManager.default.createSymbolicLink(
            at: moviesDir.appendingPathComponent("escape.mp4"),
            withDestinationURL: outsideFile
        )

        let creds = WebDAVCredentials(user: "depth3d", pass: "davpass88")
        let handler = WebDAVHandler(
            credentials: creds,
            localMounts: [WebDAVMount(name: "电影", rootURL: moviesDir)]
        )
        let server = WebDAVServer(handler: handler, maxConnections: 16, maxUnauthRequestsPerMinute: 3)
        let port = try server.start(preferredPort: 0)
        defer { server.stop() }

        let auth = "\(creds.user):\(creds.pass)"
        let basicHeader = "Basic " + Data(auth.utf8).base64EncodedString()

        // S4: Sending 8 headerless requests (normal HTTP Basic Auth probes -> 401) must NEVER trigger 429!
        for _ in 0..<8 {
            XCTAssertEqual(curl(["-s", "http://127.0.0.1:\(port)/"]).0, 401)
        }
        // Legitimate auth succeeds immediately -> 200
        XCTAssertEqual(curl(["-s", "-u", auth, "-X", "OPTIONS", "http://127.0.0.1:\(port)/"]).0, 200)

        // S4: Wrong credentials count, and a successful auth RESETS the failure counter!
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong1", "http://127.0.0.1:\(port)/"]).0, 401)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong2", "http://127.0.0.1:\(port)/"]).0, 401)
        // Valid auth resets failure counter to 0
        XCTAssertEqual(curl(["-s", "-u", auth, "-X", "OPTIONS", "http://127.0.0.1:\(port)/"]).0, 200)
        // Another 3 wrong attempts allowed before the 4th hits 429 (limit = 3)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong3", "http://127.0.0.1:\(port)/"]).0, 401)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong4", "http://127.0.0.1:\(port)/"]).0, 401)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong5", "http://127.0.0.1:\(port)/"]).0, 401)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:wrong6", "http://127.0.0.1:\(port)/"]).0, 429)
        // Even after 429 on wrong creds, a legitimate client presenting VALID credentials succeeds AND resets!
        XCTAssertEqual(curl(["-s", "-u", auth, "-X", "OPTIONS", "http://127.0.0.1:\(port)/"]).0, 200)

        // m2: PROPFIND /电影/ must list `样片.mp4` and MUST OMIT `.DS_Store`, `sneaky_link.mp4`, `uf_hidden.mp4`, AND `escape.mp4`!
        let encodedDir = WebDAVPathSanitizer.encodeHref(segments: ["电影"], isDirectory: true)
        let (c4, body4) = curl(["-s", "-u", auth, "-X", "PROPFIND", "-H", "Depth: 1", "http://127.0.0.1:\(port)\(encodedDir)"])
        XCTAssertEqual(c4, 207)
        XCTAssertTrue(body4.contains("<d:displayname>样片.mp4</d:displayname>"))
        XCTAssertFalse(body4.contains(".DS_Store"))
        XCTAssertFalse(body4.contains("sneaky_link.mp4"), "PROPFIND must omit symlink pointing into hidden directory")
        XCTAssertFalse(body4.contains("uf_hidden.mp4"), "PROPFIND must omit UF_HIDDEN files")
        XCTAssertFalse(body4.contains("escape.mp4"), "PROPFIND must omit symlinks escaping shared root")

        // m2: Direct GET/HEAD on dotfile, symlink-to-hidden-dir, and UF_HIDDEN file must all return 404!
        let hiddenDS = WebDAVPathSanitizer.encodeHref(segments: ["电影", ".DS_Store"], isDirectory: false)
        let sneakyLink = WebDAVPathSanitizer.encodeHref(segments: ["电影", "sneaky_link.mp4"], isDirectory: false)
        let ufHiddenPath = WebDAVPathSanitizer.encodeHref(segments: ["电影", "uf_hidden.mp4"], isDirectory: false)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(hiddenDS)"]).0, 404)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(sneakyLink)"]).0, 404)
        XCTAssertEqual(curl(["-s", "-I", "-u", auth, "http://127.0.0.1:\(port)\(sneakyLink)"]).0, 404)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(ufHiddenPath)"]).0, 404)
        XCTAssertEqual(curl(["-s", "-I", "-u", auth, "http://127.0.0.1:\(port)\(ufHiddenPath)"]).0, 404)

        // Escaping symlink -> 403
        let escapePath = WebDAVPathSanitizer.encodeHref(segments: ["电影", "escape.mp4"], isDirectory: false)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(escapePath)"]).0, 403)

        // GET & Range 206 & 416 & read-only PUT 405
        let encodedFile = WebDAVPathSanitizer.encodeHref(segments: ["电影", "样片.mp4"], isDirectory: false)
        XCTAssertEqual(curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(encodedFile)"]).1, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        XCTAssertEqual(curl(["-s", "-u", auth, "-H", "Range: bytes=4-11", "http://127.0.0.1:\(port)\(encodedFile)"]).1, "EFGHIJKL")
        XCTAssertEqual(curl(["-s", "-u", auth, "-H", "Range: bytes=100-200", "http://127.0.0.1:\(port)\(encodedFile)"]).0, 416)
        XCTAssertEqual(curl(["-s", "-u", auth, "-X", "PUT", "-d", "hack", "http://127.0.0.1:\(port)\(encodedFile)"]).0, 405)

        // Encoded & raw `../` traversal over live raw TCP socket
        let rawEncodedTraversal = sendRawHTTP(
            port: port,
            request: "GET /%E7%94%B5%E5%BD%B1/%2e%2e/secret.txt HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: \(basicHeader)\r\nConnection: close\r\n\r\n"
        )
        XCTAssertTrue(rawEncodedTraversal.hasPrefix("HTTP/1.1 400"))
        XCTAssertFalse(rawEncodedTraversal.contains("outside-secret"))

        let rawDotDotTraversal = sendRawHTTP(
            port: port,
            request: "GET /%E7%94%B5%E5%BD%B1/../secret.txt HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: \(basicHeader)\r\nConnection: close\r\n\r\n"
        )
        XCTAssertTrue(rawDotDotTraversal.hasPrefix("HTTP/1.1 400"))
    }

    func testWebDAVServerPortFallbackWhenPreferredPortOccupied() throws {
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
        XCTAssertEqual(withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(blockerFD, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
            }
        }, 0)
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

        XCTAssertNotEqual(actualPort, occupiedPort)
        XCTAssertEqual(curl(["-s", "-u", "depth3d:fallbackpass", "-X", "OPTIONS", "http://127.0.0.1:\(actualPort)/"]).0, 200)
    }

    func testWebDAVServerLiveConnectionCapAndLargeProxiedPropfind() throws {
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
        XCTAssertEqual(withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(holdFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }, 0)
        Thread.sleep(forTimeInterval: 0.1)

        XCTAssertEqual(curl(["-s", "-u", "depth3d:captest99", "-X", "OPTIONS", "http://127.0.0.1:\(capPort)/"]).0, 503)
        Darwin.close(holdFD)

        // Live streaming proxied PROPFIND > 8 MiB over chunked transfer encoding
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-large-proxy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let fakeUpstream = tmp.appendingPathComponent("fake-rclone-9mb.py")
        try """
        #!/usr/bin/env python3
        import http.server, sys
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

        // Wait until the fake python3 upstream actually binds its allocated loopback port.
        let readyDeadline = Date().addingTimeInterval(4.0)
        var upstreamReady = false
        while Date() < readyDeadline {
            if let ep = rclone.endpoint(forMountName: "BigCloud") {
                let probe = Darwin.socket(AF_INET, SOCK_STREAM, 0)
                if probe >= 0 {
                    var upAddr = sockaddr_in()
                    upAddr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                    upAddr.sin_family = sa_family_t(AF_INET)
                    upAddr.sin_addr.s_addr = inet_addr("127.0.0.1")
                    upAddr.sin_port = ep.port.bigEndian
                    let rc = withUnsafePointer(to: &upAddr) {
                        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            Darwin.connect(probe, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                    Darwin.close(probe)
                    if rc == 0 {
                        upstreamReady = true
                        break
                    }
                }
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertTrue(upstreamReady, "Fake rclone upstream failed to bind loopback port; statuses=\(rclone.currentStatuses())")

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
        XCTAssertGreaterThan(body.utf8.count, 8 * 1024 * 1024)
        XCTAssertTrue(body.contains("<D:href>/BigCloud/BigFolder/Video_00000_"))
        XCTAssertTrue(body.contains("<D:href>/BigCloud/BigFolder/Video_37999_"))
        XCTAssertTrue(body.contains("</D:multistatus>"))
    }

    /// S6 + S1: Covers rclone explicit states (`.rcloneNotInstalled`, `.paused`, `.starting`, `.running`, `.error`),
    /// runtime 404/401 stderr NOT falsely poisoning healthy remote, serial FIFO `syncRemotesAsync`,
    /// duplicate displayName safety, env-only credentials, crash backoff, and SIGTERM-ignoring cleanup.
    func testRcloneBridgeExplicitStatesRuntimeLogSafetySerialSyncAndStubbornCleanup() throws {
        // 1. Strict stderr classification: runtime 404 ("directory not found") or bare "unauthorized" must NOT be permanent config error!
        let authErr = RcloneBridgeManager.classifyStderr(
            "2026/11/25 ERROR : Failed to create file system: couldn't fetch token: invalid_grant",
            remoteSpec: "gdrive:"
        )
        XCTAssertTrue(authErr.isPermanentConfigError)
        XCTAssertTrue(authErr.message.contains("rclone config reconnect gdrive"))

        let missingErr = RcloneBridgeManager.classifyStderr(
            "CRITICAL: didn't find section in config file",
            remoteSpec: "onedrive:"
        )
        XCTAssertTrue(missingErr.isPermanentConfigError)

        let runtime404 = RcloneBridgeManager.classifyStderr(
            "ERROR : missing.mp4: directory not found; 401 unauthorized probe",
            remoteSpec: "gdrive:"
        )
        XCTAssertFalse(runtime404.isPermanentConfigError, "Runtime 404/401 logs must NOT permanently disable auto-restart")

        // 2. S6: Missing rclone MUST publish `.rcloneNotInstalled` for every configured remote (never empty/nil!)
        let missingRclone = RcloneBridgeManager(executableOverride: "")
        let cloudA = CloudRemoteEntry(displayName: "Google Drive", remoteSpec: "gdrive:")
        missingRclone.syncRemotes([cloudA])
        XCTAssertEqual(missingRclone.currentStatuses()["Google Drive"], .rcloneNotInstalled)

        // 3. S6: pauseAllAsync publishes `.paused` explicitly
        let pausedExp = expectation(description: "Paused status emitted")
        missingRclone.setStatusesChangedHandler { statuses in
            if statuses["Google Drive"] == .paused { pausedExp.fulfill() }
        }
        missingRclone.pauseAllAsync(configuredRemotes: [cloudA])
        wait(for: [pausedExp], timeout: 2.0)

        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-rclone-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        // 4. Healthy running rclone that prints runtime "directory not found" & "401 unauthorized" to stderr
        //    AND traps/ignores SIGTERM (tests BOTH S6 runtime stderr safety AND S1 process-group SIGKILL!)
        let argsLog = tmp.appendingPathComponent("args.txt")
        let envCheck = tmp.appendingPathComponent("env-ok.txt")
        let childPidFile = tmp.appendingPathComponent("rclone-stubborn.pid")
        let noisyHealthyScript = tmp.appendingPathComponent("fake-rclone-noisy.sh")
        try """
        #!/bin/sh
        trap '' TERM
        echo $$ > "\(childPidFile.path)"
        echo "$@" >> "\(argsLog.path)"
        if [ -n "$RCLONE_USER" ] && [ -n "$RCLONE_PASS" ]; then
          echo "ENV_CREDS_PRESENT" > "\(envCheck.path)"
        fi
        echo "2026/11/25 INFO : /missing: directory not found (404)" >&2
        echo "2026/11/25 INFO : HTTP 401 unauthorized probe" >&2
        /bin/sleep 60 &
        wait
        """.write(to: noisyHealthyScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: noisyHealthyScript.path)

        let guardBin = locateBuiltChildGuard()
        let ledger = ChildProcessLedger(ledgerURL: tmp.appendingPathComponent("ledger.json"))
        let manager = RcloneBridgeManager(
            ledger: ledger,
            executableOverride: noisyHealthyScript.path,
            childGuardOverride: guardBin,
            startupConfirmDelay: 0.15
        )
        defer { manager.stopAll() }

        // Duplicate displayNames + concurrent syncRemotes
        let dupA = CloudRemoteEntry(displayName: "我的云盘", remoteSpec: "gdrive:")
        let dupB = CloudRemoteEntry(displayName: "我的云盘", remoteSpec: "gdrive2:")
        let group = DispatchGroup()
        for _ in 0..<6 {
            group.enter()
            DispatchQueue.global().async {
                manager.syncRemotes([dupA, dupB])
                group.leave()
            }
        }
        group.wait()

        Thread.sleep(forTimeInterval: 0.3)
        XCTAssertEqual(manager.totalLaunchAttempts(), 1)
        // Must remain `.running` despite runtime "directory not found" and "unauthorized" on stderr!
        XCTAssertEqual(manager.currentStatuses()["我的云盘"], .running)

        let capturedArgs = (try? String(contentsOf: argsLog, encoding: .utf8)) ?? ""
        XCTAssertFalse(capturedArgs.contains("--pass"))
        XCTAssertFalse(capturedArgs.contains("--user"))
        XCTAssertEqual((try? String(contentsOf: envCheck, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines), "ENV_CREDS_PRESENT")

        // Verify SIGTERM-ignoring rclone child is killed cleanly on stopAll()!
        let stubbornPID = Int32(((try? String(contentsOf: childPidFile, encoding: .utf8)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)) ?? -1
        XCTAssertGreaterThan(stubbornPID, 1)
        manager.stopAll()
        let deadDeadline = Date().addingTimeInterval(3.0)
        var dead = false
        while Date() < deadDeadline {
            if kill(stubbornPID, 0) != 0 && errno == ESRCH { dead = true; break }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertTrue(dead, "SIGTERM-ignoring rclone child must be force-killed via process group on stopAll()")

        // 5. Serial FIFO `syncRemotesAsync`: [A] followed by [B] always settles on ONLY [B]
        let simpleScript = tmp.appendingPathComponent("fake-rclone-simple.sh")
        try "#!/bin/sh\nsleep 30\n".write(to: simpleScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: simpleScript.path)
        let fifoMgr = RcloneBridgeManager(executableOverride: simpleScript.path, startupConfirmDelay: 0.1)
        defer { fifoMgr.stopAll() }
        fifoMgr.syncRemotesAsync([CloudRemoteEntry(displayName: "RemoteA", remoteSpec: "a:")])
        fifoMgr.syncRemotesAsync([CloudRemoteEntry(displayName: "RemoteB", remoteSpec: "b:")])
        Thread.sleep(forTimeInterval: 0.35)
        XCTAssertEqual(fifoMgr.activeEndpoints().map(\.mountName), ["RemoteB"])
        fifoMgr.stopAll()

        // 6. Crash auto-restart backoff
        let crashingScript = tmp.appendingPathComponent("fake-rclone-crash.sh")
        try "#!/bin/sh\necho 'transient crash' >&2\nexit 1\n".write(to: crashingScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: crashingScript.path)
        let crashManager = RcloneBridgeManager(executableOverride: crashingScript.path, backoffDelays: [0.05, 0.08], startupConfirmDelay: 0.2)
        defer { crashManager.stopAll() }
        let crashExp = expectation(description: "Crash backoff exhausted")
        crashManager.setStatusesChangedHandler { if case .error = $0["云盘A"] { crashExp.fulfill() } }
        crashManager.syncRemotes([CloudRemoteEntry(displayName: "云盘A", remoteSpec: "gdrive:")])
        wait(for: [crashExp], timeout: 5.0)
        XCTAssertEqual(crashManager.totalLaunchAttempts(), 3)

        // 7. Permanent auth-expired exit surfaces Chinese reconnect instructions immediately
        let authScript = tmp.appendingPathComponent("fake-rclone-auth.sh")
        try "#!/bin/sh\necho \"ERROR : couldn't fetch token: invalid_grant\" >&2\nexit 1\n".write(to: authScript, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: authScript.path)
        let authManager = RcloneBridgeManager(executableOverride: authScript.path, backoffDelays: [0.05, 0.08])
        defer { authManager.stopAll() }
        let authExp = expectation(description: "Auth error reported")
        authManager.setStatusesChangedHandler {
            if case .error(let msg) = $0["Google Drive"], msg.contains("rclone config reconnect gdrive") {
                authExp.fulfill()
            }
        }
        authManager.syncRemotes([CloudRemoteEntry(displayName: "Google Drive", remoteSpec: "gdrive:")])
        wait(for: [authExp], timeout: 5.0)
        XCTAssertEqual(authManager.totalLaunchAttempts(), 1)
    }

    private func locateBuiltChildGuard() -> URL? {
        let candidates = [
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent(".build/debug/depth3d-child-guard"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent(".build/arm64-apple-macosx/debug/depth3d-child-guard"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent(".build/out/Products/Debug/depth3d-child-guard")
        ]
        return candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) })
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
            if let base = raw.baseAddress { _ = Darwin.send(fd, base, reqData.count, 0) }
        }
        var resp = Data()
        var buf = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = Darwin.recv(fd, &buf, buf.count, 0)
            if n > 0 { resp.append(buf, count: n) } else { break }
        }
        return String(decoding: resp, as: UTF8.self)
    }

    private func curl(_ args: [String]) -> (Int, String) {
        TestProcessRunner.runCurl(["--max-time", "15"] + args, timeout: 20.0)
    }
}
