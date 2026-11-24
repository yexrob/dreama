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

    func testRewriteProxiedMultiStatusXML() throws {
        let upstream = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/</D:href></D:response>
          <D:response><D:href>/Movies/Clip%201.mp4</D:href></D:response>
        </D:multistatus>
        """
        let rewrittenData = WebDAVXMLBuilder.rewriteProxiedMultiStatusXML(Data(upstream.utf8), mountName: "Google Drive")
        let rewritten = try XCTUnwrap(String(data: rewrittenData, encoding: .utf8))
        XCTAssertTrue(rewritten.contains("<D:href>/Google%20Drive/</D:href>"))
        XCTAssertTrue(rewritten.contains("<D:href>/Google%20Drive/Movies/Clip%201.mp4</D:href>"))
    }

    func testWebDAVServerLivePropfindRangeAuthAndReadOnly() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("depth3d-webdav-test-\(UUID().uuidString)", isDirectory: true)
        let moviesDir = tmp.appendingPathComponent("Movies", isDirectory: true)
        let outsideFile = tmp.appendingPathComponent("secret.txt")
        try FileManager.default.createDirectory(at: moviesDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let sampleContent = Data("ABCDEFGHIJKLMNOPQRSTUVWXYZ".utf8) // 26 bytes
        try sampleContent.write(to: moviesDir.appendingPathComponent("样片.mp4"))
        try Data("hidden".utf8).write(to: moviesDir.appendingPathComponent(".DS_Store"))
        try Data("outside".utf8).write(to: outsideFile)
        try FileManager.default.createSymbolicLink(
            at: moviesDir.appendingPathComponent("escape.mp4"),
            withDestinationURL: outsideFile
        )

        let creds = WebDAVCredentials(user: "depth3d", pass: "davpass88")
        let handler = WebDAVHandler(
            credentials: creds,
            localMounts: [WebDAVMount(name: "电影", rootURL: moviesDir)]
        )
        let server = WebDAVServer(handler: handler)
        let port = try server.start(preferredPort: 0)
        defer { server.stop() }

        let auth = "\(creds.user):\(creds.pass)"

        // 1. No auth -> 401
        let (c1, _) = curl(["-s", "http://127.0.0.1:\(port)/"])
        XCTAssertEqual(c1, 401)

        // 2. OPTIONS -> 200
        let (c2, _) = curl(["-s", "-u", auth, "-X", "OPTIONS", "http://127.0.0.1:\(port)/"])
        XCTAssertEqual(c2, 200)

        // 3. PROPFIND / -> 207 listing mounted folder "电影"
        let (c3, body3) = curl(["-s", "-u", auth, "-X", "PROPFIND", "-H", "Depth: 1", "http://127.0.0.1:\(port)/"])
        XCTAssertEqual(c3, 207)
        XCTAssertTrue(body3.contains("<d:displayname>电影</d:displayname>"))
        XCTAssertTrue(body3.contains("<d:collection/>"))

        // 4. PROPFIND /电影/ -> lists 样片.mp4, skips .DS_Store
        let encodedDir = WebDAVPathSanitizer.encodeHref(segments: ["电影"], isDirectory: true)
        let (c4, body4) = curl(["-s", "-u", auth, "-X", "PROPFIND", "-H", "Depth: 1", "http://127.0.0.1:\(port)\(encodedDir)"])
        XCTAssertEqual(c4, 207)
        XCTAssertTrue(body4.contains("<d:displayname>样片.mp4</d:displayname>"))
        XCTAssertTrue(body4.contains("<d:getcontentlength>26</d:getcontentlength>"))
        XCTAssertFalse(body4.contains(".DS_Store"))

        // 5. GET full file -> 200 + exact bytes
        let encodedFile = WebDAVPathSanitizer.encodeHref(segments: ["电影", "样片.mp4"], isDirectory: false)
        let (c5, body5) = curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(encodedFile)"])
        XCTAssertEqual(c5, 200)
        XCTAssertEqual(body5, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")

        // 6. GET Range: bytes=4-11 -> 206 + exact slice "EFGHIJKL"
        let (c6, body6) = curl(["-s", "-u", auth, "-H", "Range: bytes=4-11", "http://127.0.0.1:\(port)\(encodedFile)"])
        XCTAssertEqual(c6, 206)
        XCTAssertEqual(body6, "EFGHIJKL")

        // 7. GET Range out of bounds -> 416
        let (c7, _) = curl(["-s", "-u", auth, "-H", "Range: bytes=100-200", "http://127.0.0.1:\(port)\(encodedFile)"])
        XCTAssertEqual(c7, 416)

        // 8. PUT -> 405 Method Not Allowed (read-only)
        let (c8, _) = curl(["-s", "-u", auth, "-X", "PUT", "-d", "hack", "http://127.0.0.1:\(port)\(encodedFile)"])
        XCTAssertEqual(c8, 405)

        // 9. Symlink escape outside mounted root -> 403
        let escapePath = WebDAVPathSanitizer.encodeHref(segments: ["电影", "escape.mp4"], isDirectory: false)
        let (c9, _) = curl(["-s", "-u", auth, "http://127.0.0.1:\(port)\(escapePath)"])
        XCTAssertEqual(c9, 403)
    }

    private func curl(_ args: [String]) -> (Int, String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
        p.arguments = ["--noproxy", "*", "--max-time", "5"] + args + ["-w", "\n%{http_code}"]
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
