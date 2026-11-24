import Darwin
import Foundation

/// A local folder mount exposed under `/<mountName>/` in the unified WebDAV server.
public struct WebDAVMount: Equatable, Sendable {
    public let name: String
    public let rootURL: URL

    public init(name: String, rootURL: URL) {
        self.name = name
        self.rootURL = rootURL.standardizedFileURL
    }
}

/// Pure, unit-testable handler for read-only WebDAV requests (`OPTIONS`, `PROPFIND`, `HEAD`, `GET` + `Range`).
/// Handles virtual root listing, local folders (strict symlink containment, dot-file & `UF_HIDDEN`
/// blocking across every resolved segment, and filtering escaping symlinks from `PROPFIND`),
/// and optional proxied rclone backends.
public final class WebDAVHandler: @unchecked Sendable {
    public enum StreamPlan: Sendable {
        case immediate(HTTPResponse)
        case localFileRange(
            statusCode: Int,
            headers: [(String, String)],
            fileURL: URL,
            offset: UInt64,
            length: UInt64,
            includeBody: Bool
        )
        case proxyRclone(
            endpoint: RcloneBackendEndpoint,
            mountName: String,
            subpath: String,
            method: String,
            headers: [String: String],
            body: Data
        )
    }

    private let lock = NSLock()
    private var credentials: WebDAVCredentials
    private var localMounts: [WebDAVMount]
    private var rcloneManager: RcloneBridgeManager?

    public init(
        credentials: WebDAVCredentials,
        localMounts: [WebDAVMount] = [],
        rcloneManager: RcloneBridgeManager? = nil
    ) {
        self.credentials = credentials
        self.localMounts = localMounts
        self.rcloneManager = rcloneManager
    }

    public func updateConfiguration(credentials: WebDAVCredentials? = nil, localMounts: [WebDAVMount]? = nil) {
        lock.lock()
        if let credentials { self.credentials = credentials }
        if let localMounts { self.localMounts = localMounts }
        lock.unlock()
    }

    public func verifyAuth(_ request: HTTPRequest) -> Bool {
        lock.lock()
        let creds = credentials
        lock.unlock()
        return WebDAVAuth.verify(authorizationHeader: request.header("authorization"), credentials: creds)
    }

    public func route(_ request: HTTPRequest) -> StreamPlan {
        lock.lock()
        let creds = credentials
        let mounts = localMounts
        let rclone = rcloneManager
        lock.unlock()

        // Enforce HTTP Basic Auth on every request.
        guard WebDAVAuth.verify(authorizationHeader: request.header("authorization"), credentials: creds) else {
            return .immediate(Self.unauthorizedResponse())
        }

        switch request.method {
        case "OPTIONS":
            return .immediate(HTTPResponse(
                statusCode: 200,
                headers: [
                    ("DAV", "1"),
                    ("Allow", "OPTIONS, PROPFIND, GET, HEAD"),
                    ("Accept-Ranges", "bytes")
                ]
            ))
        case "PROPFIND", "GET", "HEAD":
            break
        default:
            return .immediate(HTTPResponse(
                statusCode: 405,
                headers: [("Allow", "OPTIONS, PROPFIND, GET, HEAD")],
                body: Data("Read-only WebDAV server".utf8)
            ))
        }

        guard let resolved = WebDAVPathSanitizer.resolve(request.path) else {
            return .immediate(HTTPResponse(statusCode: 400, body: Data("Invalid path".utf8)))
        }

        // Virtual root `/`
        if resolved.isRoot {
            if request.method == "PROPFIND" {
                return .immediate(handleVirtualRootPropfind(
                    depthHeader: request.header("depth"),
                    localMounts: mounts,
                    cloudEndpoints: rclone?.activeEndpoints() ?? []
                ))
            }
            if request.method == "HEAD" {
                return .immediate(HTTPResponse(statusCode: 200, headers: [("Content-Type", "text/plain; charset=utf-8")]))
            }
            return .immediate(HTTPResponse(
                statusCode: 200,
                headers: [("Content-Type", "text/plain; charset=utf-8")],
                body: Data("depth3d Media WebDAV\n".utf8)
            ))
        }

        guard let mountName = resolved.mountName else {
            return .immediate(HTTPResponse(statusCode: 404))
        }

        // 1. Check local folder mounts first
        if let mount = mounts.first(where: { $0.name == mountName }) {
            return handleLocalMount(
                mount: mount,
                relativeSegments: resolved.relativeSegments,
                request: request
            )
        }

        // 2. Check active rclone cloud remote mounts
        if let endpoint = rclone?.endpoint(forMountName: mountName) {
            if resolved.relativeSegments.contains(where: { $0.hasPrefix(".") }) {
                return .immediate(HTTPResponse(statusCode: 404))
            }
            let encodedSub = resolved.relativeSegments.isEmpty
                ? "/"
                : "/" + resolved.relativeSegments.map(WebDAVPathSanitizer.encodeSegment).joined(separator: "/")
                    + (request.path.hasSuffix("/") ? "/" : "")
            return .proxyRclone(
                endpoint: endpoint,
                mountName: mountName,
                subpath: encodedSub,
                method: request.method,
                headers: request.headers,
                body: request.body
            )
        }

        return .immediate(HTTPResponse(statusCode: 404))
    }

    public static func unauthorizedResponse() -> HTTPResponse {
        HTTPResponse(
            statusCode: 401,
            headers: [
                ("WWW-Authenticate", "Basic realm=\"depth3d\""),
                ("Content-Type", "text/plain; charset=utf-8")
            ],
            body: Data("Unauthorized".utf8)
        )
    }

    private func handleVirtualRootPropfind(
        depthHeader: String?,
        localMounts: [WebDAVMount],
        cloudEndpoints: [RcloneBackendEndpoint]
    ) -> HTTPResponse {
        let depth = (depthHeader ?? "1").trimmingCharacters(in: .whitespaces)
        var entries: [WebDAVEntry] = [
            WebDAVEntry(href: "/", displayName: "", isDirectory: true, lastModified: Date())
        ]
        if depth != "0" {
            for mount in localMounts {
                entries.append(WebDAVEntry(
                    href: WebDAVPathSanitizer.encodeHref(segments: [mount.name], isDirectory: true),
                    displayName: mount.name,
                    isDirectory: true,
                    lastModified: Date()
                ))
            }
            for cloud in cloudEndpoints {
                entries.append(WebDAVEntry(
                    href: WebDAVPathSanitizer.encodeHref(segments: [cloud.mountName], isDirectory: true),
                    displayName: cloud.mountName,
                    isDirectory: true,
                    lastModified: Date()
                ))
            }
        }
        let xml = WebDAVXMLBuilder.buildMultiStatus(entries)
        return HTTPResponse(
            statusCode: 207,
            headers: [("Content-Type", "application/xml; charset=utf-8")],
            body: xml
        )
    }

    /// Validates that `targetURL` (and every intermediate component strictly inside `rootReal`
    /// both before and after symlink resolution) neither escapes `rootReal` nor traverses any
    /// hidden file/directory (dot-prefixed OR macOS `UF_HIDDEN` / `isHiddenKey`).
    private enum ContainmentCheck {
        case allowed(resolvedURL: URL)
        case escapedRoot
        case hidden
    }

    private func checkContainmentAndVisibility(
        rootReal: URL,
        relativeSegments: [String]
    ) -> ContainmentCheck {
        let rootPrefix = rootReal.path.hasSuffix("/") ? rootReal.path : rootReal.path + "/"

        // 1. Check requested (un-resolved) segments below rootReal for dot-prefix or UF_HIDDEN.
        var walkRaw = rootReal
        for seg in relativeSegments {
            if seg.hasPrefix(".") { return .hidden }
            walkRaw.appendPathComponent(seg, isDirectory: false)
            if Self.hasHiddenFlag(at: walkRaw) { return .hidden }
        }

        // 2. Resolve symlinks and verify strict containment inside rootReal.
        let resolvedTarget = walkRaw.resolvingSymlinksInPath()
        guard resolvedTarget.path == rootReal.path || resolvedTarget.path.hasPrefix(rootPrefix) else {
            return .escapedRoot
        }

        // 3. Check EVERY resolved segment strictly inside rootReal (catches symlinks targeting
        //    files inside a `.hidden_dir` OR inside a `UF_HIDDEN` directory/file!).
        if resolvedTarget.path != rootReal.path {
            let relResolved = String(resolvedTarget.path.dropFirst(rootPrefix.count))
            let resolvedSegments = relResolved.split(separator: "/").map(String.init)
            var walkResolved = rootReal
            for seg in resolvedSegments {
                if seg.hasPrefix(".") { return .hidden }
                walkResolved.appendPathComponent(seg, isDirectory: false)
                if Self.hasHiddenFlag(at: walkResolved) { return .hidden }
            }
        }

        return .allowed(resolvedURL: resolvedTarget)
    }

    private static func hasHiddenFlag(at url: URL) -> Bool {
        var st = stat()
        if lstat(url.path, &st) == 0 {
            if (st.st_flags & UInt32(UF_HIDDEN)) != 0 {
                return true
            }
        }
        if let vals = try? url.resourceValues(forKeys: [.isHiddenKey]), vals.isHidden == true {
            return true
        }
        return false
    }

    private func handleLocalMount(
        mount: WebDAVMount,
        relativeSegments: [String],
        request: HTTPRequest
    ) -> StreamPlan {
        let fm = FileManager.default
        let rootReal = mount.rootURL.resolvingSymlinksInPath()

        let resolvedTarget: URL
        switch checkContainmentAndVisibility(rootReal: rootReal, relativeSegments: relativeSegments) {
        case .escapedRoot:
            return .immediate(HTTPResponse(statusCode: 403))
        case .hidden:
            return .immediate(HTTPResponse(statusCode: 404))
        case .allowed(let url):
            resolvedTarget = url
        }

        var isDirObjC: ObjCBool = false
        guard fm.fileExists(atPath: resolvedTarget.path, isDirectory: &isDirObjC) else {
            return .immediate(HTTPResponse(statusCode: 404))
        }
        let isDir = isDirObjC.boolValue

        if request.method == "PROPFIND" {
            return .immediate(handleLocalPropfind(
                mount: mount,
                rootReal: rootReal,
                relativeSegments: relativeSegments,
                targetURL: resolvedTarget,
                isDirectory: isDir,
                depthHeader: request.header("depth")
            ))
        }

        guard !isDir else {
            return .immediate(HTTPResponse(statusCode: 405, headers: [("Allow", "OPTIONS, PROPFIND")]))
        }

        let attrs = (try? fm.attributesOfItem(atPath: resolvedTarget.path)) ?? [:]
        let fileSize = (attrs[.size] as? NSNumber)?.uint64Value ?? 0
        let contentType = WebDAVXMLBuilder.mimeType(forPath: resolvedTarget.path)
        let includeBody = (request.method == "GET")

        if let rangeHeader = request.header("range") {
            switch ByteRangeParser.parse(rangeHeader, fileSize: fileSize) {
            case .satisfied(let start, let end):
                let length = (end - start) + 1
                return .localFileRange(
                    statusCode: 206,
                    headers: [
                        ("Content-Type", contentType),
                        ("Accept-Ranges", "bytes"),
                        ("Content-Range", "bytes \(start)-\(end)/\(fileSize)"),
                        ("Content-Length", String(length))
                    ],
                    fileURL: resolvedTarget,
                    offset: start,
                    length: length,
                    includeBody: includeBody
                )
            case .unsatisfiable:
                return .immediate(HTTPResponse(
                    statusCode: 416,
                    headers: [
                        ("Content-Range", "bytes */\(fileSize)"),
                        ("Accept-Ranges", "bytes")
                    ]
                ))
            case .invalid:
                break
            }
        }

        return .localFileRange(
            statusCode: 200,
            headers: [
                ("Content-Type", contentType),
                ("Accept-Ranges", "bytes"),
                ("Content-Length", String(fileSize))
            ],
            fileURL: resolvedTarget,
            offset: 0,
            length: fileSize,
            includeBody: includeBody
        )
    }

    private func handleLocalPropfind(
        mount: WebDAVMount,
        rootReal: URL,
        relativeSegments: [String],
        targetURL: URL,
        isDirectory: Bool,
        depthHeader: String?
    ) -> HTTPResponse {
        let fm = FileManager.default
        let depth = (depthHeader ?? "1").trimmingCharacters(in: .whitespaces)
        let fullSegments = [mount.name] + relativeSegments

        let selfAttrs = (try? fm.attributesOfItem(atPath: targetURL.path)) ?? [:]
        let selfSize = (selfAttrs[.size] as? NSNumber)?.uint64Value
        let selfMod = selfAttrs[.modificationDate] as? Date
        let selfName = relativeSegments.last ?? mount.name

        var entries: [WebDAVEntry] = [
            WebDAVEntry(
                href: WebDAVPathSanitizer.encodeHref(segments: fullSegments, isDirectory: isDirectory),
                displayName: selfName,
                isDirectory: isDirectory,
                contentLength: isDirectory ? nil : selfSize,
                lastModified: selfMod,
                contentType: isDirectory ? nil : WebDAVXMLBuilder.mimeType(forPath: targetURL.path)
            )
        ]

        if isDirectory && depth != "0" {
            let children = (try? fm.contentsOfDirectory(
                at: targetURL,
                includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey, .isHiddenKey],
                options: [.skipsHiddenFiles]
            )) ?? []

            let sorted = children.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
            for child in sorted {
                let name = child.lastPathComponent
                if name.hasPrefix(".") { continue }

                // Enforce identical containment & hidden-target filtering as GET/HEAD:
                // never list symlinks that escape rootReal or point into hidden files/directories!
                let childResolvedURL: URL
                switch checkContainmentAndVisibility(rootReal: rootReal, relativeSegments: relativeSegments + [name]) {
                case .allowed(let resolved):
                    childResolvedURL = resolved
                case .escapedRoot, .hidden:
                    continue
                }

                var childIsDirObjC: ObjCBool = false
                guard fm.fileExists(atPath: childResolvedURL.path, isDirectory: &childIsDirObjC) else {
                    continue
                }
                let childIsDir = childIsDirObjC.boolValue
                let attrs = (try? fm.attributesOfItem(atPath: childResolvedURL.path)) ?? [:]
                let childSize = (attrs[.size] as? NSNumber)?.uint64Value
                let childMod = attrs[.modificationDate] as? Date
                entries.append(WebDAVEntry(
                    href: WebDAVPathSanitizer.encodeHref(segments: fullSegments + [name], isDirectory: childIsDir),
                    displayName: name,
                    isDirectory: childIsDir,
                    contentLength: childIsDir ? nil : childSize,
                    lastModified: childMod,
                    contentType: childIsDir ? nil : WebDAVXMLBuilder.mimeType(forPath: childResolvedURL.path)
                ))
            }
        }

        let xml = WebDAVXMLBuilder.buildMultiStatus(entries)
        return HTTPResponse(
            statusCode: 207,
            headers: [("Content-Type", "application/xml; charset=utf-8")],
            body: xml
        )
    }
}

/// Embedded read-only HTTP/1.1 WebDAV server with:
/// - Connection concurrency limit (`ConnectionGuard`)
/// - Per-connection absolute request-read deadline & socket write timeouts
/// - Smart Basic Auth brute-force rate limiting: counts ONLY requests carrying wrong credentials,
///   never penalizes headerless challenge probes, and resets failure count on successful auth
/// - Streaming `Range` support for local video files
/// - Zero-truncation streaming `PROPFIND` `<href>` rewriting and byte-for-byte streaming proxy for rclone
public final class WebDAVServer: @unchecked Sendable {
    public let handler: WebDAVHandler
    private let connectionGuard: ConnectionGuard
    private let authFailureLimiter: IPRateLimiter
    private let readTimeoutSeconds: Int
    private let writeTimeoutSeconds: Int

    private let lock = NSLock()
    private var listenFD: Int32 = -1
    private var boundPort: UInt16 = 0
    private var running = false
    private let acceptQueue = DispatchQueue(label: "dev.depth3d.host.webdav.accept", qos: .userInitiated)
    private let workerQueue = DispatchQueue(label: "dev.depth3d.host.webdav.worker", qos: .userInitiated, attributes: .concurrent)

    public var port: UInt16 {
        lock.lock()
        defer { lock.unlock() }
        return boundPort
    }

    public var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return running
    }

    public init(
        handler: WebDAVHandler,
        maxConnections: Int = 64,
        maxUnauthRequestsPerMinute: Int = 30,
        readTimeoutSeconds: Int = 10,
        writeTimeoutSeconds: Int = 30
    ) {
        self.handler = handler
        self.connectionGuard = ConnectionGuard(maxConnections: maxConnections)
        self.authFailureLimiter = IPRateLimiter(maxEvents: maxUnauthRequestsPerMinute, windowSeconds: 60.0)
        self.readTimeoutSeconds = max(1, readTimeoutSeconds)
        self.writeTimeoutSeconds = max(1, writeTimeoutSeconds)
    }

    deinit {
        stop()
    }

    /// Starts the WebDAV server on `preferredPort` (default 8890). If `preferredPort` is already
    /// occupied (e.g., an existing `serve-gdrive.sh` instance), automatically falls back to the
    /// next available port so Host never fails to start or interferes with external services.
    @discardableResult
    public func start(preferredPort: UInt16 = HostPortDefaults.defaultWebDAVPort) throws -> UInt16 {
        lock.lock()
        if running {
            let p = boundPort
            lock.unlock()
            return p
        }
        lock.unlock()

        var candidates: [UInt16] = [preferredPort]
        if preferredPort != 0 {
            for offset: UInt16 in 1...15 {
                let candidate = preferredPort &+ offset
                if candidate >= 1024 {
                    candidates.append(candidate)
                }
            }
            candidates.append(0)
        }

        var lastError: Error = ControlHTTPSServerError.bindFailed(preferredPort, EADDRINUSE)
        for candidate in candidates {
            do {
                let (fd, actualPort) = try openListeningSocket(port: candidate)
                lock.lock()
                self.listenFD = fd
                self.boundPort = actualPort
                self.running = true
                lock.unlock()

                if candidate != preferredPort && preferredPort != 0 {
                    HostLogger.warning("WebDAV port \(preferredPort) occupied; listening on fallback port \(actualPort)", category: "media")
                } else {
                    HostLogger.info("WebDAV media server listening on port \(actualPort)", category: "media")
                }

                acceptQueue.async { [weak self] in
                    self?.acceptLoop(listenFD: fd)
                }
                return actualPort
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    public func stop() {
        lock.lock()
        let fd = listenFD
        listenFD = -1
        boundPort = 0
        running = false
        lock.unlock()

        if fd >= 0 {
            Darwin.shutdown(fd, SHUT_RDWR)
            Darwin.close(fd)
            HostLogger.info("WebDAV media server stopped", category: "media")
        }
    }

    private func openListeningSocket(port: UInt16) throws -> (Int32, UInt16) {
        var fd = Darwin.socket(AF_INET6, SOCK_STREAM, 0)
        if fd >= 0 {
            var one: Int32 = 1
            var zero: Int32 = 0
            if port != 0 {
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
            }
            setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &zero, socklen_t(MemoryLayout<Int32>.size))
            var addr = sockaddr_in6()
            addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
            addr.sin6_family = sa_family_t(AF_INET6)
            addr.sin6_addr = in6addr_any
            addr.sin6_port = port.bigEndian
            let res = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
                }
            }
            if res == 0, Darwin.listen(fd, 64) == 0 {
                var bound = sockaddr_in6()
                var len = socklen_t(MemoryLayout<sockaddr_in6>.size)
                _ = withUnsafeMutablePointer(to: &bound) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        Darwin.getsockname(fd, $0, &len)
                    }
                }
                return (fd, UInt16(bigEndian: bound.sin6_port))
            }
            let err = errno
            Darwin.close(fd)
            if err == EADDRINUSE {
                throw ControlHTTPSServerError.bindFailed(port, err)
            }
        }

        fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw ControlHTTPSServerError.socketCreationFailed(errno) }
        var one: Int32 = 1
        if port != 0 {
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian
        addr.sin_port = port.bigEndian
        let res = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard res == 0 else {
            let err = errno
            Darwin.close(fd)
            throw ControlHTTPSServerError.bindFailed(port, err)
        }
        guard Darwin.listen(fd, 64) == 0 else {
            let err = errno
            Darwin.close(fd)
            throw ControlHTTPSServerError.listenFailed(err)
        }
        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        return (fd, UInt16(bigEndian: bound.sin_port))
    }

    private func acceptLoop(listenFD: Int32) {
        while true {
            var storage = sockaddr_storage()
            var len = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let clientFD = withUnsafeMutablePointer(to: &storage) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.accept(listenFD, $0, &len)
                }
            }
            if clientFD < 0 {
                if errno == EINTR { continue }
                break
            }

            var one: Int32 = 1
            setsockopt(clientFD, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            var rcvTimeout = timeval(tv_sec: readTimeoutSeconds, tv_usec: 0)
            var sndTimeout = timeval(tv_sec: writeTimeoutSeconds, tv_usec: 0)
            setsockopt(clientFD, SOL_SOCKET, SO_RCVTIMEO, &rcvTimeout, socklen_t(MemoryLayout<timeval>.size))
            setsockopt(clientFD, SOL_SOCKET, SO_SNDTIMEO, &sndTimeout, socklen_t(MemoryLayout<timeval>.size))

            let peerIP = IPAddressNormalizer.peerAddress(from: &storage)

            guard connectionGuard.tryAcquire() else {
                HostLogger.warning("WebDAV server connection cap reached; rejecting \(peerIP)", category: "media")
                let busyResp = HTTPResponse(
                    statusCode: 503,
                    headers: [("Retry-After", "5")],
                    body: Data("Server busy".utf8)
                )
                _ = writeAll(fd: clientFD, data: busyResp.serialize())
                Darwin.close(clientFD)
                continue
            }

            workerQueue.async { [weak self] in
                guard let self else {
                    Darwin.close(clientFD)
                    return
                }
                defer {
                    Darwin.close(clientFD)
                    self.connectionGuard.release()
                }
                self.handleClient(fd: clientFD, peerIP: peerIP)
            }
        }
    }

    private func handleClient(fd: Int32, peerIP: String) {
        guard let request = readPlainHTTPRequest(fd: fd, peerIP: peerIP) else {
            _ = writeAll(fd: fd, data: HTTPResponse(statusCode: 400).serialize())
            return
        }

        // Smart Basic Auth rate limiting (S4):
        // - Missing `Authorization` header is a normal HTTP Basic Auth challenge probe -> return 401 WITHOUT counting.
        // - Valid credentials ALWAYS succeed and clear prior failure history for `peerIP`.
        // - Wrong credentials increment the per-IP counter; exceeding the threshold returns 429.
        let rawAuth = request.header("authorization")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if rawAuth.isEmpty {
            _ = writeAll(fd: fd, data: WebDAVHandler.unauthorizedResponse().serialize(includeBody: request.method != "HEAD"))
            return
        }

        if handler.verifyAuth(request) {
            authFailureLimiter.reset(key: peerIP)
        } else {
            if !authFailureLimiter.allow(key: peerIP) {
                let limited = HTTPResponse(
                    statusCode: 429,
                    headers: [("Retry-After", "60"), ("Content-Type", "text/plain; charset=utf-8")],
                    body: Data("Too Many Requests".utf8)
                )
                _ = writeAll(fd: fd, data: limited.serialize(includeBody: request.method != "HEAD"))
                return
            }
            _ = writeAll(fd: fd, data: WebDAVHandler.unauthorizedResponse().serialize(includeBody: request.method != "HEAD"))
            return
        }

        switch handler.route(request) {
        case .immediate(let response):
            _ = writeAll(fd: fd, data: response.serialize(includeBody: request.method != "HEAD"))

        case .localFileRange(let statusCode, let headers, let fileURL, let offset, let length, let includeBody):
            streamLocalFile(
                fd: fd,
                statusCode: statusCode,
                headers: headers,
                fileURL: fileURL,
                offset: offset,
                length: length,
                includeBody: includeBody
            )

        case .proxyRclone(let endpoint, let mountName, let subpath, let method, let reqHeaders, let body):
            proxyToRclone(
                clientFD: fd,
                endpoint: endpoint,
                mountName: mountName,
                subpath: subpath,
                method: method,
                headers: reqHeaders,
                body: body
            )
        }
    }

    private func streamLocalFile(
        fd: Int32,
        statusCode: Int,
        headers: [(String, String)],
        fileURL: URL,
        offset: UInt64,
        length: UInt64,
        includeBody: Bool
    ) {
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else {
            _ = writeAll(fd: fd, data: HTTPResponse(statusCode: 404).serialize())
            return
        }
        defer { try? handle.close() }

        let headResp = HTTPResponse(statusCode: statusCode, headers: headers)
        guard writeAll(fd: fd, data: headResp.serialize(includeBody: false)) else { return }
        guard includeBody && length > 0 else { return }

        do {
            try handle.seek(toOffset: offset)
        } catch {
            return
        }

        var remaining = length
        let chunkSize = 64 * 1024
        while remaining > 0 {
            let toRead = Int(min(UInt64(chunkSize), remaining))
            guard let chunk = try? handle.read(upToCount: toRead), !chunk.isEmpty else {
                break
            }
            if !writeAll(fd: fd, data: chunk) {
                break
            }
            remaining -= UInt64(chunk.count)
        }
    }

    private func proxyToRclone(
        clientFD: Int32,
        endpoint: RcloneBackendEndpoint,
        mountName: String,
        subpath: String,
        method: String,
        headers: [String: String],
        body: Data
    ) {
        let upFD = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard upFD >= 0 else {
            _ = writeAll(fd: clientFD, data: HTTPResponse(statusCode: 502).serialize())
            return
        }
        defer { Darwin.close(upFD) }

        var one: Int32 = 1
        setsockopt(upFD, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: writeTimeoutSeconds, tv_usec: 0)
        setsockopt(upFD, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(upFD, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = endpoint.port.bigEndian

        let connected = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(upFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard connected == 0 else {
            _ = writeAll(fd: clientFD, data: HTTPResponse(statusCode: 502).serialize())
            return
        }

        // Forward request with internal loopback auth and `Connection: close`.
        var reqHead = "\(method) \(subpath) HTTP/1.1\r\n"
        reqHead += "Host: 127.0.0.1:\(endpoint.port)\r\n"
        reqHead += "Authorization: \(endpoint.authHeader)\r\n"
        reqHead += "Connection: close\r\n"
        for key in ["depth", "range", "content-type", "accept"] {
            if let val = headers[key] {
                reqHead += "\(key): \(val)\r\n"
            }
        }
        if !body.isEmpty {
            reqHead += "Content-Length: \(body.count)\r\n"
        }
        reqHead += "\r\n"

        var reqData = Data(reqHead.utf8)
        reqData.append(body)
        guard writeAll(fd: upFD, data: reqData) else { return }

        if method == "PROPFIND" {
            streamRewrittenPropfind(upFD: upFD, clientFD: clientFD, mountName: mountName)
        } else {
            var buf = [UInt8](repeating: 0, count: 65536)
            while true {
                let n = Darwin.recv(upFD, &buf, buf.count, 0)
                if n > 0 {
                    if !writeAll(fd: clientFD, data: Data(bytes: buf, count: n)) {
                        break
                    }
                } else if n == 0 {
                    break
                } else if errno == EINTR {
                    continue
                } else {
                    break
                }
            }
        }
    }

    private func streamRewrittenPropfind(upFD: Int32, clientFD: Int32, mountName: String) {
        let delim = Data("\r\n\r\n".utf8)
        var headBuffer = Data()
        var buf = [UInt8](repeating: 0, count: 32768)
        var headerEndRange: Range<Data.Index>?

        while headerEndRange == nil {
            let n = Darwin.recv(upFD, &buf, buf.count, 0)
            if n > 0 {
                headBuffer.append(buf, count: n)
                headerEndRange = headBuffer.range(of: delim)
                if headerEndRange == nil && headBuffer.count > 64 * 1024 {
                    _ = writeAll(fd: clientFD, data: HTTPResponse(statusCode: 502).serialize())
                    return
                }
            } else if n < 0 && errno == EINTR {
                continue
            } else {
                break
            }
        }

        guard let split = headerEndRange else {
            _ = writeAll(fd: clientFD, data: HTTPResponse(statusCode: 502).serialize())
            return
        }

        let headStr = String(data: headBuffer.subdata(in: 0..<split.lowerBound), encoding: .utf8) ?? ""
        let initialBody = headBuffer.subdata(in: split.upperBound..<headBuffer.count)
        let isChunkedUpstream = headStr.lowercased().contains("transfer-encoding: chunked")
        let statusLine = headStr.components(separatedBy: "\r\n").first ?? "HTTP/1.1 207 Multi-Status"
        let statusCode = statusLine.split(separator: " ").dropFirst().first.flatMap { Int($0) } ?? 207

        let responseHead =
            "HTTP/1.1 \(statusCode) \(HTTPResponse.defaultReason(for: statusCode))\r\n" +
            "Content-Type: application/xml; charset=utf-8\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n"
        guard writeAll(fd: clientFD, data: Data(responseHead.utf8)) else { return }

        let chunkDecoder = isChunkedUpstream ? HTTPChunkedStreamDecoder() : nil
        let rewriter = WebDAVHrefStreamRewriter(mountName: mountName)

        func forwardRawUpstream(_ data: Data) -> Bool {
            guard !data.isEmpty else { return true }
            let xmlPart = chunkDecoder?.push(data) ?? data
            guard !xmlPart.isEmpty else { return true }
            let rewritten = rewriter.push(xmlPart)
            return writeHTTPChunk(fd: clientFD, payload: rewritten)
        }

        if !forwardRawUpstream(initialBody) { return }

        while !(chunkDecoder?.isDone ?? false) {
            let n = Darwin.recv(upFD, &buf, buf.count, 0)
            if n > 0 {
                if !forwardRawUpstream(Data(bytes: buf, count: n)) {
                    return
                }
            } else if n == 0 {
                break
            } else if errno == EINTR {
                continue
            } else {
                break
            }
        }

        let tail = rewriter.finish()
        if !tail.isEmpty {
            guard writeHTTPChunk(fd: clientFD, payload: tail) else { return }
        }
        _ = writeAll(fd: clientFD, data: Data("0\r\n\r\n".utf8))
    }

    private func writeHTTPChunk(fd: Int32, payload: Data) -> Bool {
        guard !payload.isEmpty else { return true }
        var frame = Data("\(String(payload.count, radix: 16))\r\n".utf8)
        frame.append(payload)
        frame.append(contentsOf: [0x0D, 0x0A])
        return writeAll(fd: fd, data: frame)
    }

    private func readPlainHTTPRequest(fd: Int32, peerIP: String) -> HTTPRequest? {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 8192)
        var parsedHead: HTTPParser.ParsedHead?
        let deadline = ProcessInfo.processInfo.systemUptime + TimeInterval(readTimeoutSeconds)

        while true {
            if parsedHead == nil {
                do {
                    parsedHead = try HTTPParser.parseHead(from: buffer)
                } catch {
                    return nil
                }
            }
            if let head = parsedHead {
                let needed = head.bodyStartIndex + head.contentLength
                if buffer.count >= needed {
                    let body = buffer.subdata(in: head.bodyStartIndex..<needed)
                    return HTTPRequest(
                        method: head.method,
                        rawTarget: head.target,
                        headers: head.headers,
                        body: body,
                        remoteAddress: peerIP
                    )
                }
            }

            let remaining = deadline - ProcessInfo.processInfo.systemUptime
            if remaining <= 0 {
                return nil
            }
            var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let waitMs = Int32(max(1, min(ceil(remaining * 1000.0), 60_000.0)))
            let pr = Darwin.poll(&pfd, 1, waitMs)
            if pr == 0 {
                return nil
            } else if pr < 0 {
                if errno == EINTR { continue }
                return nil
            }

            let n = Darwin.recv(fd, &chunk, chunk.count, 0)
            if n > 0 {
                buffer.append(chunk, count: n)
            } else if n == 0 {
                if let head = parsedHead, buffer.count >= head.bodyStartIndex + head.contentLength {
                    return HTTPRequest(
                        method: head.method,
                        rawTarget: head.target,
                        headers: head.headers,
                        body: buffer.subdata(in: head.bodyStartIndex..<(head.bodyStartIndex + head.contentLength)),
                        remoteAddress: peerIP
                    )
                }
                return nil
            } else if errno == EINTR {
                continue
            } else {
                return nil
            }
        }
    }

    private func writeAll(fd: Int32, data: Data) -> Bool {
        data.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress else { return true }
            var offset = 0
            while offset < data.count {
                let n = Darwin.send(fd, base.advanced(by: offset), data.count - offset, 0)
                if n > 0 {
                    offset += n
                } else if n < 0 && errno == EINTR {
                    continue
                } else {
                    return false
                }
            }
            return true
        }
    }
}
