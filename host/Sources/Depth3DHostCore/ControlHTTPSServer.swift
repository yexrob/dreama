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

/// HTTPS server for `/depth3d/v1/*` with optional TLS client certificate authentication
/// (SecureTransport `.tryAuthenticate`):
///   - `/depth3d/v1/info` and `/depth3d/v1/pair` work without a client certificate
///   - `/depth3d/v1/media` receives the peer's X.509 certificate for mTLS verification
///
/// Using system Security.framework avoids third-party TLS dependencies while supporting
/// optional client certificates on macOS 14+ (where Network.framework's
/// `sec_protocol_options_set_peer_authentication_optional` is unavailable on macOS).
public final class ControlHTTPSServer: @unchecked Sendable {
    private let identity: SecIdentity
    private let router: ControlProtocolRouter
    private let lock = NSLock()
    private var listenFD: Int32 = -1
    private var running = false
    private var boundPort: UInt16 = 0
    private var acceptQueue = DispatchQueue(label: "dev.depth3d.host.control.accept", qos: .userInitiated)
    private var workerQueue = DispatchQueue(label: "dev.depth3d.host.control.worker", qos: .userInitiated, attributes: .concurrent)

    public var port: UInt16 {
        lock.lock()
        defer { lock.unlock() }
        return boundPort
    }

    public init(identity: SecIdentity, router: ControlProtocolRouter) {
        self.identity = identity
        self.router = router
    }

    deinit {
        stop()
    }

    /// Starts listening on `preferredPort` (pass `0` for an ephemeral OS-assigned port).
    /// Returns the actual bound TCP port.
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
        // Dual-stack IPv6 socket accepting both IPv4 and IPv6 LAN connections.
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

        // Fallback to IPv4-only socket if IPv6 is disabled on the host.
        fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else {
            throw ControlHTTPSServerError.socketCreationFailed(errno)
        }
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))

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
            var one: Int32 = 1
            setsockopt(clientFD, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            var timeout = timeval(tv_sec: 15, tv_usec: 0)
            setsockopt(clientFD, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            setsockopt(clientFD, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

            workerQueue.async { [weak self] in
                self?.handleClient(fd: clientFD)
            }
        }
    }

    @available(macOS, deprecated: 10.15)
    private func handleClient(fd: Int32) {
        defer { Darwin.close(fd) }

        guard let ctx = SSLCreateContext(kCFAllocatorDefault, .serverSide, .streamType) else {
            return
        }

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
            return
        }

        defer { _ = SSLClose(ctx) }

        // Extract optional peer client certificate for mTLS endpoints (`/depth3d/v1/media`).
        var peerDER: Data?
        var peerTrust: SecTrust?
        if SSLCopyPeerTrust(ctx, &peerTrust) == noErr,
           let trust = peerTrust,
           let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
           let leaf = chain.first
        {
            peerDER = SecCertificateCopyData(leaf) as Data
        }

        // Read full HTTP request.
        guard let request = readHTTPRequest(ctx: ctx, peerCertDER: peerDER) else {
            let bad = HTTPResponse.json(400, object: ["error": "bad_request"])
            writeAll(ctx: ctx, data: bad.serialize())
            return
        }

        // Extend socket send timeout for `/pair` which can legitimately wait up to 60s for user approval.
        if request.path == "/depth3d/v1/pair" {
            var pairTimeout = timeval(tv_sec: 70, tv_usec: 0)
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &pairTimeout, socklen_t(MemoryLayout<timeval>.size))
        }

        let semaphore = DispatchSemaphore(value: 0)
        var responseBox: HTTPResponse?
        let router = self.router
        Task {
            responseBox = await router.handle(request)
            semaphore.signal()
        }
        semaphore.wait()

        let response = responseBox ?? HTTPResponse.json(500, object: ["error": "internal_error"])
        writeAll(ctx: ctx, data: response.serialize(includeBody: request.method != "HEAD"))
    }

    @available(macOS, deprecated: 10.15)
    private func readHTTPRequest(ctx: SSLContext, peerCertDER: Data?) -> HTTPRequest? {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 8192)
        var parsedHead: HTTPParser.ParsedHead?

        while true {
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
                        peerCertificateDER: peerCertDER
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
                // Connection closed before full request arrived; check one last time.
                if let head = parsedHead, buffer.count >= head.bodyStartIndex + head.contentLength {
                    let body = buffer.subdata(in: head.bodyStartIndex..<(head.bodyStartIndex + head.contentLength))
                    return HTTPRequest(
                        method: head.method,
                        rawTarget: head.target,
                        headers: head.headers,
                        body: body,
                        peerCertificateDER: peerCertDER
                    )
                }
                return nil
            }
        }
    }

    @available(macOS, deprecated: 10.15)
    private func writeAll(ctx: SSLContext, data: Data) {
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
