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

/// Per-connection I/O context passed to SecureTransport callbacks enforcing a strict monotonic
/// absolute deadline (using `poll()` + `ProcessInfo.processInfo.systemUptime`) so slow-drip
/// clients can NEVER reset socket timeouts indefinitely during TLS handshake or HTTP read.
private final class TLSIOContext: @unchecked Sendable {
    let fd: Int32
    var deadlineUptime: TimeInterval
    var timedOut: Bool = false

    init(fd: Int32, deadlineUptime: TimeInterval) {
        self.fd = fd
        self.deadlineUptime = deadlineUptime
    }
}

/// Box holding a live SecureTransport SSLContext, its pinned `TLSIOContext`, and client fd so
/// `/depth3d/v1/pair` can suspend asynchronously in a Swift Task without holding a GCD thread.
private final class ActiveTLSConnection: @unchecked Sendable {
    let fd: Int32
    let ctx: SSLContext
    let ioCtx: TLSIOContext
    private var closed = false
    private let lock = NSLock()

    init(fd: Int32, ctx: SSLContext, ioCtx: TLSIOContext) {
        self.fd = fd
        self.ctx = ctx
        self.ioCtx = ioCtx
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
        // Prevent blocking inside SSLClose on a stalled/timed-out peer.
        ioCtx.deadlineUptime = ProcessInfo.processInfo.systemUptime + 0.2
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

/// Coordinates safe single-close + slot-release across the async routing Task and the
/// `DispatchSourceRead` client-disconnect monitor so `fd` is NEVER closed while monitored.
private final class ConnectionLifecycleBox: @unchecked Sendable {
    private let lock = NSLock()
    private let conn: ActiveTLSConnection
    private let connectionGuard: ConnectionGuard
    private let peerIP: String
    private var wasPromotedToPaired: Bool
    private var sourceCancelled = false
    private var routeFinished = false
    private var clientDisconnected = false
    private var finalized = false
    private var routeTask: Task<Void, Never>?

    init(
        conn: ActiveTLSConnection,
        connectionGuard: ConnectionGuard,
        peerIP: String,
        wasPromotedToPaired: Bool,
        monitorDisconnect: Bool = true
    ) {
        self.conn = conn
        self.connectionGuard = connectionGuard
        self.peerIP = peerIP
        self.wasPromotedToPaired = wasPromotedToPaired
        self.sourceCancelled = !monitorDisconnect
    }

    func attachRouteTask(_ task: Task<Void, Never>) {
        lock.lock()
        routeTask = task
        let alreadyDisconnected = clientDisconnected
        lock.unlock()
        if alreadyDisconnected {
            task.cancel()
        }
    }

    func markPromotedToPaired() {
        lock.lock()
        wasPromotedToPaired = true
        lock.unlock()
    }

    func markClientDisconnectedAndCancelRoute() -> Bool {
        lock.lock()
        let first = !clientDisconnected
        clientDisconnected = true
        let taskToCancel = routeTask
        lock.unlock()
        if first {
            taskToCancel?.cancel()
        }
        return first
    }

    var isClientDisconnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return clientDisconnected
    }

    @available(macOS, deprecated: 10.15)
    func markSourceCancelled() {
        lock.lock()
        sourceCancelled = true
        let shouldClose = routeFinished && !finalized
        if shouldClose {
            finalized = true
            routeTask = nil
        }
        let promoted = wasPromotedToPaired
        lock.unlock()
        if shouldClose {
            conn.close()
            connectionGuard.release(forIP: peerIP, wasPromotedToPaired: promoted)
        }
    }

    @available(macOS, deprecated: 10.15)
    func markRouteFinished() {
        lock.lock()
        routeFinished = true
        let shouldClose = sourceCancelled && !finalized
        if shouldClose {
            finalized = true
            routeTask = nil
        }
        let promoted = wasPromotedToPaired
        lock.unlock()
        if shouldClose {
            conn.close()
            connectionGuard.release(forIP: peerIP, wasPromotedToPaired: promoted)
        }
    }
}

/// HTTPS server for `/depth3d/v1/*` with optional TLS client certificate authentication
/// (SecureTransport `.tryAuthenticate`), bound strictly on IPv4 per `docs/specs/host-protocol.md`:
///   - Global connection ceiling (`maxConnections`) with reserved slots (`reservedPairedSlots`)
///     guaranteed for bound-fingerprint paired client certificates
///   - Per-IP concurrent connection cap enforced BEFORE TLS handshake (`maxConnectionsPerIP`)
///   - Strict per-connection monotonic ABSOLUTE deadlines across TLS handshake & request read
///     (with a tighter handshake deadline when operating inside the reserved paired-cert zone)
///   - Per-IP rate limiting for unauthenticated / unknown-cert connections (`IPRateLimiter`)
///   - Event-driven client-disconnect detection (`DispatchSourceRead`) that immediately cancels
///     pending `/depth3d/v1/pair` prompts when a headset disconnects
public final class ControlHTTPSServer: @unchecked Sendable {
    private let identity: SecIdentity
    private let router: ControlProtocolRouter
    private let connectionGuard: ConnectionGuard
    private let unauthRateLimiter: IPRateLimiter
    private let handshakeTimeout: TimeInterval
    private let reservedZoneHandshakeTimeout: TimeInterval
    private let readTimeout: TimeInterval

    private let lock = NSLock()
    private var listenFD: Int32 = -1
    private var running = false
    private var boundPort: UInt16 = 0
    private var acceptQueue = DispatchQueue(label: "dev.depth3d.host.control.accept", qos: .userInitiated)
    private var ioQueue = DispatchQueue(label: "dev.depth3d.host.control.io", qos: .userInitiated, attributes: .concurrent)
    private var disconnectQueue = DispatchQueue(label: "dev.depth3d.host.control.disconnect", qos: .userInitiated)

    public var port: UInt16 {
        lock.lock()
        defer { lock.unlock() }
        return boundPort
    }

    public init(
        identity: SecIdentity,
        router: ControlProtocolRouter,
        maxConnections: Int = 32,
        maxConnectionsPerIP: Int = 8,
        reservedPairedSlots: Int = 8,
        unauthRateLimitPerWindow: Int = 30,
        unauthRateWindowSeconds: TimeInterval = 10.0,
        handshakeTimeoutSec: TimeInterval = 5.0,
        reservedZoneHandshakeTimeoutSec: TimeInterval? = nil,
        readTimeoutSec: TimeInterval = 5.0
    ) {
        self.identity = identity
        self.router = router
        self.connectionGuard = ConnectionGuard(
            maxConnections: maxConnections,
            maxConnectionsPerIP: maxConnectionsPerIP,
            reservedPairedSlots: reservedPairedSlots
        )
        self.unauthRateLimiter = IPRateLimiter(
            maxEvents: unauthRateLimitPerWindow,
            windowSeconds: unauthRateWindowSeconds
        )
        let clampedHS = max(0.2, handshakeTimeoutSec)
        self.handshakeTimeout = clampedHS
        self.reservedZoneHandshakeTimeout = max(0.2, min(clampedHS, reservedZoneHandshakeTimeoutSec ?? min(clampedHS, 1.5)))
        self.readTimeout = max(0.2, readTimeoutSec)
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

        HostLogger.info("Control HTTPS server listening on IPv4 port \(actualPort)", category: "control")

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

    /// Binds strictly on IPv4 (`AF_INET`, `INADDR_ANY`) per `docs/specs/host-protocol.md` (S-3).
    private func openListeningSocket(port: UInt16) throws -> (Int32, UInt16) {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
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

            let peerIP = IPAddressNormalizer.peerAddress(from: &storage)

            // Enforce global + per-IP connection ceilings BEFORE TLS handshake.
            let acquire = connectionGuard.tryAcquireWithZone(forIP: peerIP)
            guard acquire.acquired else {
                Darwin.close(clientFD)
                continue
            }

            var one: Int32 = 1
            setsockopt(clientFD, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            let flags = fcntl(clientFD, F_GETFL, 0)
            if flags >= 0 {
                _ = fcntl(clientFD, F_SETFL, flags | O_NONBLOCK)
            }

            ioQueue.async { [weak self] in
                guard let self else {
                    Darwin.close(clientFD)
                    return
                }
                self.startTLSAndDispatch(fd: clientFD, peerIP: peerIP, inReservedZone: acquire.inReservedZone)
            }
        }
    }

    private func startTLSAndDispatch(fd: Int32, peerIP: String, inReservedZone: Bool) {
        performTLSRead(fd: fd, peerIP: peerIP, inReservedZone: inReservedZone)
    }

    @available(macOS, deprecated: 10.15)
    private func performTLSRead(fd: Int32, peerIP: String, inReservedZone: Bool) {
        guard let ctx = SSLCreateContext(kCFAllocatorDefault, .serverSide, .streamType) else {
            Darwin.close(fd)
            connectionGuard.release(forIP: peerIP, wasPromotedToPaired: false)
            return
        }

        let startUptime = ProcessInfo.processInfo.systemUptime
        // Use a tighter absolute handshake deadline when operating inside the reserved paired-cert zone
        // so unauthenticated connections cannot hold reserved slots open during slow handshakes.
        let effectiveHandshakeTimeout = inReservedZone ? reservedZoneHandshakeTimeout : handshakeTimeout
        let handshakeDeadline = startUptime + effectiveHandshakeTimeout
        let ioCtx = TLSIOContext(fd: fd, deadlineUptime: handshakeDeadline)
        let conn = ActiveTLSConnection(fd: fd, ctx: ctx, ioCtx: ioCtx)

        let readFunc: SSLReadFunc = { connRef, data, dataLength in
            let io = Unmanaged<TLSIOContext>.fromOpaque(connRef).takeUnretainedValue()
            let wanted = dataLength.pointee
            var received = 0
            while received < wanted {
                let remaining = io.deadlineUptime - ProcessInfo.processInfo.systemUptime
                if remaining <= 0 {
                    io.timedOut = true
                    dataLength.pointee = received
                    return errSSLClosedAbort
                }
                var pfd = pollfd(fd: io.fd, events: Int16(POLLIN), revents: 0)
                let waitMs = Int32(max(1, min(ceil(remaining * 1000.0), 60_000.0)))
                let pr = Darwin.poll(&pfd, 1, waitMs)
                if pr == 0 {
                    io.timedOut = true
                    dataLength.pointee = received
                    return errSSLClosedAbort
                } else if pr < 0 {
                    if errno == EINTR { continue }
                    dataLength.pointee = received
                    return errSSLClosedAbort
                }

                let n = Darwin.recv(io.fd, data.advanced(by: received), wanted - received, 0)
                if n > 0 {
                    received += n
                } else if n == 0 {
                    dataLength.pointee = received
                    return errSSLClosedGraceful
                } else if errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK {
                    continue
                } else {
                    dataLength.pointee = received
                    return errSSLClosedAbort
                }
            }
            dataLength.pointee = received
            return noErr
        }

        let writeFunc: SSLWriteFunc = { connRef, data, dataLength in
            let io = Unmanaged<TLSIOContext>.fromOpaque(connRef).takeUnretainedValue()
            let wanted = dataLength.pointee
            var sent = 0
            while sent < wanted {
                let remaining = io.deadlineUptime - ProcessInfo.processInfo.systemUptime
                if remaining <= 0 {
                    io.timedOut = true
                    dataLength.pointee = sent
                    return errSSLClosedAbort
                }
                var pfd = pollfd(fd: io.fd, events: Int16(POLLOUT), revents: 0)
                let waitMs = Int32(max(1, min(ceil(remaining * 1000.0), 60_000.0)))
                let pr = Darwin.poll(&pfd, 1, waitMs)
                if pr == 0 {
                    io.timedOut = true
                    dataLength.pointee = sent
                    return errSSLClosedAbort
                } else if pr < 0 {
                    if errno == EINTR { continue }
                    dataLength.pointee = sent
                    return errSSLClosedAbort
                }

                let n = Darwin.send(io.fd, data.advanced(by: sent), wanted - sent, 0)
                if n > 0 {
                    sent += n
                } else if errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK {
                    continue
                } else {
                    dataLength.pointee = sent
                    return errSSLClosedAbort
                }
            }
            dataLength.pointee = sent
            return noErr
        }

        SSLSetIOFuncs(ctx, readFunc, writeFunc)
        SSLSetConnection(ctx, Unmanaged.passUnretained(ioCtx).toOpaque())
        SSLSetProtocolVersionMin(ctx, .tlsProtocol12)
        SSLSetCertificate(ctx, [identity] as CFArray)
        SSLSetClientSideAuthenticate(ctx, .tryAuthenticate)
        SSLSetSessionOption(ctx, .breakOnClientAuth, true)

        // Strictly enforce the per-connection absolute handshake deadline.
        var handshakeStatus = SSLHandshake(ctx)
        var guardIterations = 0
        while (handshakeStatus == errSSLPeerAuthCompleted || handshakeStatus == errSSLWouldBlock)
            && !ioCtx.timedOut
            && ProcessInfo.processInfo.systemUptime < handshakeDeadline
            && guardIterations < 32
        {
            guardIterations += 1
            handshakeStatus = SSLHandshake(ctx)
        }
        guard handshakeStatus == noErr && !ioCtx.timedOut else {
            conn.close()
            connectionGuard.release(forIP: peerIP, wasPromotedToPaired: false)
            return
        }

        // Extract optional peer client certificate (cryptographically verified via TLS CertificateVerify).
        var peerDER: Data?
        var peerTrust: SecTrust?
        if SSLCopyPeerTrust(ctx, &peerTrust) == noErr,
           let trust = peerTrust,
           let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
           let leaf = chain.first
        {
            peerDER = SecCertificateCopyData(leaf) as Data
        }

        // If this connection landed in the reserved paired-cert zone and presented NO client certificate
        // at all, reject immediately after handshake with 429 busy (never 503, which is stream_not_ready).
        if inReservedZone && peerDER == nil {
            ioCtx.deadlineUptime = ProcessInfo.processInfo.systemUptime + 1.0
            let busy = HTTPResponse.json(
                429,
                object: ["error": "busy", "retryAfterSec": 2],
                extraHeaders: [("Retry-After", "2")]
            )
            writeAll(ctx: ctx, data: busy.serialize())
            conn.close()
            connectionGuard.release(forIP: peerIP, wasPromotedToPaired: false)
            return
        }

        // Set absolute request-read deadline (unauthenticated/reserved-zone connections get tighter read cap).
        let now = ProcessInfo.processInfo.systemUptime
        let effectiveReadTimeout = (inReservedZone || peerDER == nil) ? min(readTimeout, reservedZoneHandshakeTimeout) : readTimeout
        let readDeadline = min(now + effectiveReadTimeout, startUptime + effectiveHandshakeTimeout + effectiveReadTimeout)
        ioCtx.deadlineUptime = readDeadline

        guard let request = readHTTPRequest(
            ctx: ctx,
            ioCtx: ioCtx,
            peerCertDER: peerDER,
            remoteAddress: peerIP
        ) else {
            if !ioCtx.timedOut {
                ioCtx.deadlineUptime = ProcessInfo.processInfo.systemUptime + 2.0
                let bad = HTTPResponse.json(400, object: ["error": "bad_request"])
                writeAll(ctx: ctx, data: bad.serialize())
            }
            conn.close()
            connectionGuard.release(forIP: peerIP, wasPromotedToPaired: false)
            return
        }

        // Hand off paired-cert slot promotion, rate-limit checks, and routing to an async Task.
        // Client-disconnect monitoring (`DispatchSourceRead`) is attached ONLY for `POST /depth3d/v1/pair`
        // (waiting on user confirmation / post-approval issuance), so clients that half-close their
        // write side immediately after sending `/info`, `/media`, or `/pair/complete` still receive
        // their full HTTP response normally.
        let router = self.router
        let ioQueue = self.ioQueue
        let guardRef = self.connectionGuard
        let rateLimiter = self.unauthRateLimiter
        let monitorDisconnect = (request.method == "POST" && request.path == "/depth3d/v1/pair")
        let lifecycle = ConnectionLifecycleBox(
            conn: conn,
            connectionGuard: guardRef,
            peerIP: peerIP,
            wasPromotedToPaired: false,
            monitorDisconnect: monitorDisconnect
        )

        var readSource: DispatchSourceRead?
        if monitorDisconnect {
            // Configure and resume `readSource` strictly BEFORE spawning `routeTask` so no other
            // thread can ever call `readSource.cancel()` concurrently with `setEventHandler`/`resume()`.
            let src = DispatchSource.makeReadSource(fileDescriptor: conn.fd, queue: disconnectQueue)
            src.setCancelHandler {
                lifecycle.markSourceCancelled()
            }
            src.setEventHandler {
                var peekByte: UInt8 = 0
                let n = Darwin.recv(conn.fd, &peekByte, 1, MSG_PEEK | MSG_DONTWAIT)
                if n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) || (n > 0 && peekByte == 0x15) {
                    if lifecycle.markClientDisconnectedAndCancelRoute() {
                        src.cancel()
                    }
                } else if n > 0 {
                    // Unexpected non-alert bytes on a Connection: close stream; stop monitoring to avoid level-triggered spin.
                    src.cancel()
                }
            }
            src.resume()
            readSource = src
        }

        let capturedReadSource = readSource
        let routeTask = Task {
            let isKnownPaired: Bool
            if let fp = request.peerCertificateFingerprintSHA256 {
                isKnownPaired = await router.isKnownPairedCertificate(fingerprintSHA256: fp)
            } else {
                isKnownPaired = false
            }

            if isKnownPaired {
                guardRef.promoteToPaired()
                lifecycle.markPromotedToPaired()
            }

            let response: HTTPResponse
            if !isKnownPaired && !guardRef.allowUnauthenticatedAdmission() {
                // Connection-layer reserved-slot rejection uniformly returns 429 busy (never 503).
                response = HTTPResponse.json(
                    429,
                    object: ["error": "busy", "retryAfterSec": 2],
                    extraHeaders: [("Retry-After", "2")]
                )
            } else if !isKnownPaired && !rateLimiter.allow(key: peerIP) {
                response = HTTPResponse.json(
                    429,
                    object: ["error": "busy", "retryAfterSec": 10],
                    extraHeaders: [("Retry-After", "10")]
                )
            } else {
                response = await router.handle(request)
            }

            ioQueue.async {
                capturedReadSource?.cancel()
                if !lifecycle.isClientDisconnected {
                    conn.ioCtx.timedOut = false
                    conn.ioCtx.deadlineUptime = ProcessInfo.processInfo.systemUptime + 10.0
                    Self.writeAllStatic(ctx: conn.ctx, data: response.serialize(includeBody: request.method != "HEAD"))
                }
                lifecycle.markRouteFinished()
            }
        }
        lifecycle.attachRouteTask(routeTask)
    }

    @available(macOS, deprecated: 10.15)
    private func readHTTPRequest(
        ctx: SSLContext,
        ioCtx: TLSIOContext,
        peerCertDER: Data?,
        remoteAddress: String
    ) -> HTTPRequest? {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 8192)
        var parsedHead: HTTPParser.ParsedHead?

        while !ioCtx.timedOut && ProcessInfo.processInfo.systemUptime < ioCtx.deadlineUptime {
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
                if ioCtx.timedOut || bytesRead == 0 { return nil }
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
