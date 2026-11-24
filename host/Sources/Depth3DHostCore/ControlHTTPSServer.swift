import Darwin
import Foundation
import Security

public enum ControlHTTPSServerError: Error, LocalizedError {
    case socketCreationFailed(Int32)
    case bindFailed(UInt16, Int32)
    case listenFailed(Int32)

    public var errorDescription: String? {
        switch self {
        case .socketCreationFailed(let err): return "Socket creation failed (\(err))"
        case .bindFailed(let port, let err): return "Failed to bind port \(port) (\(err))"
        case .listenFailed(let err): return "Listen failed (\(err))"
        }
    }
}

/// Box holding a live SecureTransport SSLContext + client fd so `/depth3d/v1/pair` can suspend
/// asynchronously in a Swift Task without holding a GCD thread-pool thread during the 60s wait.
private final class ActiveTLSConnection: @unchecked Sendable {
    let fd: Int32
    let ctx: SSLContext
    private var closed = false
    private let lock = NSLock()

    init(fd: Int32, ctx: SSLContext) {
        self.fd = fd
        self.ctx = ctx
    }

    @available(macOS, deprecated: 10.15)
    func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        lock.unlock()
        _ = SSLClose(ctx)
        Darwin.close(fd)
    }

    deinit {
        lock.lock()
        let needsClose = !closed
        closed = true
        lock.unlock()
        if needsClose {
            Darwin.close(fd)
        }
    }
}

/// HTTPS server for `/depth3d/v1/*` with optional TLS client certificate authentication
/// (SecureTransport `.tryAuthenticate`):
///   - Connection cap (`ConnectionGuard`) to prevent socket/memory exhaustion
///   - Strict handshake & read timeouts (`SO_RCVTIMEO` / `SO_SNDTIMEO`)
///   - Per-IP rate limiting for unauthenticated connections (`IPRateLimiter`)
///   - Non-blocking async dispatch so `/depth3d/v1/pair` waits never tie up worker threads
public final class ControlHTTPSServer: @unchecked Sendable {
    private let identity: SecIdentity
    private let router: ControlProtocolRouter
    private let connectionGuard: ConnectionGuard
    private let unauthRateLimiter: IPRateLimiter
    private let handshakeTimeoutSec: Int
    private let readTimeoutSec: Int

    private let lock = NSLock()
    private var listenFD: Int32 = -1
    private var running = false
    private var boundPort: UInt16 = 0
    private var acceptQueue = DispatchQueue(label: "dev.depth3d.host.control.accept", qos: .userInitiated)
    private var ioQueue = DispatchQueue(label: "dev.depth3d.host.control.io", qos: .userInitiated, attributes: .concurrent)

    public var port: UInt16 {
        lock.lock()
        defer { lock.unlock() }
        return boundPort
    }

    public init(
        identity: SecIdentity,
        router: ControlProtocolRouter,
        maxConnections: Int = 32,
        unauthRateLimitPerWindow: Int = 30,
        unauthRateWindowSeconds: TimeInterval = 10.0,
        handshakeTimeoutSec: Int = 5,
        readTimeoutSec: Int = 5
    ) {
        self.identity = identity
        self.router = router
        self.connectionGuard = ConnectionGuard(maxConnections: maxConnections)
        self.unauthRateLimiter = IPRateLimiter(
            maxEvents: unauthRateLimitPerWindow,
            windowSeconds: unauthRateWindowSeconds
        )
        self.handshakeTimeoutSec = max(1, handshakeTimeoutSec)
        self.readTimeoutSec = max(1, readTimeoutSec)
    }

    deinit {
        stop()
    }

    @discardableResult
    public func start(port preferredPort: UInt16 = HostPortDefaults.defaultControlPort, fallbackToEphemeral: Bool = true) throws -> UInt16 {
        lock.lock()
        if running {
            let existing = boundPort
            lock.unlock()
            return existing
        }
        lock.unlock()

        let (fd, actualPort): (Int32, UInt16)
        do {
            (fd, actualPort) = try openListeningSocket(port: preferredPort)
        } catch {
            if fallbackToEphemeral && preferredPort != 0 {
                HostLogger.warning("Control HTTPS port \(preferredPort) busy; binding ephemeral port", category: "control")
                (fd, actualPort) = try openListeningSocket(port: 0)
            } else {
                throw error
            }
        }

        lock.lock()
        self.listenFD = fd
        self.boundPort = actualPort
        self.running = true
        lock.unlock()

        HostLogger.info("Control HTTPS server listening on port \(actualPort)", category: "control")

        acceptQueue.async { [weak self] in
            self?.acceptLoop(listenFD: fd)
        }

        return actualPort
    }

    public func stop() {
        lock.lock()
        let fd = listenFD
        listenFD = -1
        running = false
        boundPort = 0
        lock.unlock()

        if fd >= 0 {
            Darwin.shutdown(fd, SHUT_RDWR)
            Darwin.close(fd)
            HostLogger.info("Control HTTPS server stopped", category: "control")
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

            let bindResult = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
                }
            }
            if bindResult == 0, Darwin.listen(fd, 32) == 0 {
                var boundAddr = sockaddr_in6()
                var len = socklen_t(MemoryLayout<sockaddr_in6>.size)
                _ = withUnsafeMutablePointer(to: &boundAddr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        Darwin.getsockname(fd, $0, &len)
                    }
                }
                return (fd, UInt16(bigEndian: boundAddr.sin6_port))
            }
            let err = errno
            Darwin.close(fd)
            if err == EADDRINUSE {
                throw ControlHTTPSServerError.bindFailed(port, err)
            }
        }

        fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else {
            throw ControlHTTPSServerError.socketCreationFailed(errno)
        }
        var one: Int32 = 1
        if port != 0 {
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        }

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian
        addr.sin_port = port.bigEndian

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else {
            let err = errno
            Darwin.close(fd)
            throw ControlHTTPSServerError.bindFailed(port, err)
        }
        guard Darwin.listen(fd, 32) == 0 else {
            let err = errno
            Darwin.close(fd)
            throw ControlHTTPSServerError.listenFailed(err)
        }

        var boundAddr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &boundAddr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        return (fd, UInt16(bigEndian: boundAddr.sin_port))
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

            // Enforce strict connection ceiling before allocating TLS state or threads.
            guard connectionGuard.tryAcquire() else {
                Darwin.close(clientFD)
                continue
            }

            let peerIP = IPAddressNormalizer.peerAddress(from: &storage)
            var one: Int32 = 1
            setsockopt(clientFD, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            var hsTimeout = timeval(tv_sec: handshakeTimeoutSec, tv_usec: 0)
            setsockopt(clientFD, SOL_SOCKET, SO_RCVTIMEO, &hsTimeout, socklen_t(MemoryLayout<timeval>.size))
            setsockopt(clientFD, SOL_SOCKET, SO_SNDTIMEO, &hsTimeout, socklen_t(MemoryLayout<timeval>.size))

            ioQueue.async { [weak self] in
                guard let self else {
                    Darwin.close(clientFD)
                    return
                }
                self.startTLSAndDispatch(fd: clientFD, peerIP: peerIP)
            }
        }
    }

    private func startTLSAndDispatch(fd: Int32, peerIP: String) {
        performTLSRead(fd: fd, peerIP: peerIP)
    }

    @available(macOS, deprecated: 10.15)
    private func performTLSRead(fd: Int32, peerIP: String) {
        guard let ctx = SSLCreateContext(kCFAllocatorDefault, .serverSide, .streamType) else {
            Darwin.close(fd)
            connectionGuard.release()
            return
        }
        let conn = ActiveTLSConnection(fd: fd, ctx: ctx)

        let readFunc: SSLReadFunc = { connRef, data, dataLength in
            let sock = Int32(Int(bitPattern: connRef))
            let wanted = dataLength.pointee
            var received = 0
            while received < wanted {
                let n = Darwin.recv(sock, data.advanced(by: received), wanted - received, 0)
                if n > 0 {
                    received += n
                } else if n == 0 {
                    dataLength.pointee = received
                    return errSSLClosedGraceful
                } else if errno == EINTR {
                    continue
                } else if errno == EAGAIN || errno == EWOULDBLOCK {
                    dataLength.pointee = received
                    return errSSLWouldBlock
                } else {
                    dataLength.pointee = received
                    return errSSLClosedAbort
                }
            }
            dataLength.pointee = received
            return noErr
        }

        let writeFunc: SSLWriteFunc = { connRef, data, dataLength in
            let sock = Int32(Int(bitPattern: connRef))
            let wanted = dataLength.pointee
            var sent = 0
            while sent < wanted {
                let n = Darwin.send(sock, data.advanced(by: sent), wanted - sent, 0)
                if n > 0 {
                    sent += n
                } else if errno == EINTR {
                    continue
                } else if errno == EAGAIN || errno == EWOULDBLOCK {
                    dataLength.pointee = sent
                    return errSSLWouldBlock
                } else {
                    dataLength.pointee = sent
                    return errSSLClosedAbort
                }
            }
            dataLength.pointee = sent
            return noErr
        }

        SSLSetIOFuncs(ctx, readFunc, writeFunc)
        SSLSetConnection(ctx, UnsafeRawPointer(bitPattern: Int(fd)))
        SSLSetProtocolVersionMin(ctx, .tlsProtocol12)
        SSLSetCertificate(ctx, [identity] as CFArray)
        SSLSetClientSideAuthenticate(ctx, .tryAuthenticate)
        SSLSetSessionOption(ctx, .breakOnClientAuth, true)

        var handshakeStatus = SSLHandshake(ctx)
        var guardIterations = 0
        while (handshakeStatus == errSSLPeerAuthCompleted || handshakeStatus == errSSLWouldBlock) && guardIterations < 16 {
            guardIterations += 1
            handshakeStatus = SSLHandshake(ctx)
        }
        guard handshakeStatus == noErr else {
            conn.close()
            connectionGuard.release()
            return
        }

        // Apply request read timeout.
        var rTimeout = timeval(tv_sec: readTimeoutSec, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rTimeout, socklen_t(MemoryLayout<timeval>.size))

        // Extract optional peer client certificate.
        var peerDER: Data?
        var peerTrust: SecTrust?
        if SSLCopyPeerTrust(ctx, &peerTrust) == noErr,
           let trust = peerTrust,
           let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
           let leaf = chain.first
        {
            peerDER = SecCertificateCopyData(leaf) as Data
        }

        // Rate-limit unauthenticated connections (clients not presenting a client certificate).
        if peerDER == nil && !unauthRateLimiter.allow(key: peerIP) {
            let limited = HTTPResponse.json(
                429,
                object: ["error": "rate_limited"],
                extraHeaders: [("Retry-After", "10")]
            )
            writeAll(ctx: ctx, data: limited.serialize())
            conn.close()
            connectionGuard.release()
            return
        }

        guard let request = readHTTPRequest(ctx: ctx, peerCertDER: peerDER, remoteAddress: peerIP) else {
            let bad = HTTPResponse.json(400, object: ["error": "bad_request"])
            writeAll(ctx: ctx, data: bad.serialize())
            conn.close()
            connectionGuard.release()
            return
        }

        // Hand off routing to an async Swift Task so long-running `/depth3d/v1/pair`
        // waits (up to 60s) suspend cooperatively without occupying ANY GCD thread-pool thread!
        let router = self.router
        let ioQueue = self.ioQueue
        let guardRef = self.connectionGuard
        Task {
            let response = await router.handle(request)
            ioQueue.async {
                var writeTimeout = timeval(tv_sec: 10, tv_usec: 0)
                setsockopt(conn.fd, SOL_SOCKET, SO_SNDTIMEO, &writeTimeout, socklen_t(MemoryLayout<timeval>.size))
                Self.writeAllStatic(ctx: conn.ctx, data: response.serialize(includeBody: request.method != "HEAD"))
                conn.close()
                guardRef.release()
            }
        }
    }

    @available(macOS, deprecated: 10.15)
    private func readHTTPRequest(ctx: SSLContext, peerCertDER: Data?, remoteAddress: String) -> HTTPRequest? {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 8192)
        var parsedHead: HTTPParser.ParsedHead?
        let deadline = Date().addingTimeInterval(Double(readTimeoutSec))

        while Date() < deadline {
            if parsedHead == nil {
                do {
                    parsedHead = try HTTPParser.parseHead(from: buffer)
                } catch {
                    return nil
                }
            }
            if let head = parsedHead {
                let totalNeeded = head.bodyStartIndex + head.contentLength
                if buffer.count >= totalNeeded {
                    let body = buffer.subdata(in: head.bodyStartIndex..<totalNeeded)
                    return HTTPRequest(
                        method: head.method,
                        rawTarget: head.target,
                        headers: head.headers,
                        body: body,
                        peerCertificateDER: peerCertDER,
                        remoteAddress: remoteAddress
                    )
                }
            }

            var bytesRead = 0
            let status = SSLRead(ctx, &chunk, chunk.count, &bytesRead)
            if bytesRead > 0 {
                buffer.append(chunk, count: bytesRead)
            }
            if status == noErr {
                continue
            } else if status == errSSLWouldBlock {
                if bytesRead == 0 { return nil }
                continue
            } else {
                if let head = parsedHead, buffer.count >= head.bodyStartIndex + head.contentLength {
                    let body = buffer.subdata(in: head.bodyStartIndex..<(head.bodyStartIndex + head.contentLength))
                    return HTTPRequest(
                        method: head.method,
                        rawTarget: head.target,
                        headers: head.headers,
                        body: body,
                        peerCertificateDER: peerCertDER,
                        remoteAddress: remoteAddress
                    )
                }
                return nil
            }
        }
        return nil
    }

    @available(macOS, deprecated: 10.15)
    private func writeAll(ctx: SSLContext, data: Data) {
        Self.writeAllStatic(ctx: ctx, data: data)
    }

    @available(macOS, deprecated: 10.15)
    private static func writeAllStatic(ctx: SSLContext, data: Data) {
        data.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress else { return }
            var offset = 0
            while offset < data.count {
                var written = 0
                let status = SSLWrite(ctx, base.advanced(by: offset), data.count - offset, &written)
                if written > 0 {
                    offset += written
                }
                if status != noErr && status != errSSLWouldBlock {
                    break
                }
                if status == errSSLWouldBlock && written == 0 {
                    break
                }
            }
        }
    }
}
