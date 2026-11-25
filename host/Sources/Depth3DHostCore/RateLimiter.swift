import Darwin
import Foundation

/// Normalizes IPv4 / IPv4-mapped IPv6 / bracketed peer addresses for reliable comparison.
public enum IPAddressNormalizer {
    public static func normalize(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("[") && s.hasSuffix("]") {
            s = String(s.dropFirst().dropLast())
        }
        if let percent = s.firstIndex(of: "%") {
            s = String(s[..<percent])
        }
        let lower = s.lowercased()
        if lower.hasPrefix("::ffff:") {
            s = String(s.dropFirst("::ffff:".count))
        }
        if s == "::1" {
            return "127.0.0.1"
        }
        return s
    }

    public static func peerAddress(from storage: inout sockaddr_storage) -> String {
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let len: socklen_t = storage.ss_family == sa_family_t(AF_INET6)
            ? socklen_t(MemoryLayout<sockaddr_in6>.size)
            : socklen_t(MemoryLayout<sockaddr_in>.size)
        let res = withUnsafePointer(to: &storage) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getnameinfo($0, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            }
        }
        guard res == 0 else { return "unknown" }
        return normalize(String(cString: host))
    }
}

/// Bounds the maximum number of simultaneous active TCP connections globally, per source IP,
/// and reserves a guaranteed pool of slots exclusively for authenticated paired client certificates.
public final class ConnectionGuard: @unchecked Sendable {
    public let maxConnections: Int
    public let maxConnectionsPerIP: Int?
    public let reservedPairedSlots: Int
    private let lock = NSLock()
    private var active: Int = 0
    private var activeUnauthenticated: Int = 0
    private var activeByIP: [String: Int] = [:]

    public init(maxConnections: Int, maxConnectionsPerIP: Int? = nil, reservedPairedSlots: Int = 0) {
        let total = max(1, maxConnections)
        self.maxConnections = total
        self.maxConnectionsPerIP = maxConnectionsPerIP.map { max(1, $0) }
        self.reservedPairedSlots = min(max(0, reservedPairedSlots), max(0, total - 1))
    }

    public var unauthenticatedCeiling: Int {
        max(1, maxConnections - reservedPairedSlots)
    }

    public var activeCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return active
    }

    public var activeUnauthenticatedCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return activeUnauthenticated
    }

    /// True when the unauthenticated pool is already full and only reserved paired slots remain.
    public var isInReservedPairedZone: Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeUnauthenticated >= unauthenticatedCeiling
    }

    public func activeCount(forIP rawIP: String) -> Int {
        let ip = IPAddressNormalizer.normalize(rawIP)
        lock.lock()
        defer { lock.unlock() }
        return activeByIP[ip] ?? 0
    }

    public func tryAcquire() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard active < maxConnections else { return false }
        active += 1
        return true
    }

    public func release() {
        lock.lock()
        active = max(0, active - 1)
        lock.unlock()
    }

    /// Acquires a pre-handshake slot (counted as unauthenticated until TLS identifies a paired cert).
    /// Returns `(acquired: true, inReservedZone: Bool)` when allowed.
    public func tryAcquireWithZone(forIP rawIP: String) -> (acquired: Bool, inReservedZone: Bool) {
        let ip = IPAddressNormalizer.normalize(rawIP)
        lock.lock()
        defer { lock.unlock() }
        guard active < maxConnections else { return (false, false) }
        if let perIPLimit = maxConnectionsPerIP {
            let currentIP = activeByIP[ip] ?? 0
            guard currentIP < perIPLimit else { return (false, false) }
            activeByIP[ip] = currentIP + 1
        }
        let inReserved = activeUnauthenticated >= unauthenticatedCeiling
        active += 1
        activeUnauthenticated += 1
        return (true, inReserved)
    }

    public func tryAcquire(forIP rawIP: String) -> Bool {
        tryAcquireWithZone(forIP: rawIP).acquired
    }

    /// Promotes an already-acquired connection from the unauthenticated pool into the paired pool
    /// after TLS verifies its client certificate matches a bound device fingerprint.
    public func promoteToPaired() {
        lock.lock()
        activeUnauthenticated = max(0, activeUnauthenticated - 1)
        lock.unlock()
    }

    /// Checks whether an unauthenticated connection may stay open (false when it landed inside the
    /// reserved paired-certificate zone because `unauthenticatedCeiling` is already saturated).
    public func allowUnauthenticatedAdmission() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeUnauthenticated <= unauthenticatedCeiling
    }

    public func release(forIP rawIP: String, wasPromotedToPaired: Bool = false) {
        let ip = IPAddressNormalizer.normalize(rawIP)
        lock.lock()
        defer { lock.unlock() }
        active = max(0, active - 1)
        if !wasPromotedToPaired {
            activeUnauthenticated = max(0, activeUnauthenticated - 1)
        }
        if maxConnectionsPerIP != nil, let current = activeByIP[ip] {
            if current <= 1 {
                activeByIP.removeValue(forKey: ip)
            } else {
                activeByIP[ip] = current - 1
            }
        }
    }
}

/// Per-IP sliding-window rate limiter used to throttle unauthenticated requests / auth failures.
public final class IPRateLimiter: @unchecked Sendable {
    private let maxEvents: Int
    private let windowSeconds: TimeInterval
    private let lock = NSLock()
    private var timestampsByKey: [String: [TimeInterval]] = [:]
    private var lastPrune: TimeInterval = 0

    public init(maxEvents: Int, windowSeconds: TimeInterval) {
        self.maxEvents = max(1, maxEvents)
        self.windowSeconds = max(0.1, windowSeconds)
    }

    /// Records an attempt for `key` and returns `true` if allowed, `false` if rate-limited.
    public func allow(key rawKey: String, now: TimeInterval = Date().timeIntervalSinceReferenceDate) -> Bool {
        let key = IPAddressNormalizer.normalize(rawKey)
        lock.lock()
        defer { lock.unlock() }

        let cutoff = now - windowSeconds
        if now - lastPrune > windowSeconds {
            for (k, times) in timestampsByKey {
                let kept = times.filter { $0 > cutoff }
                if kept.isEmpty {
                    timestampsByKey.removeValue(forKey: k)
                } else {
                    timestampsByKey[k] = kept
                }
            }
            lastPrune = now
        }

        var list = (timestampsByKey[key] ?? []).filter { $0 > cutoff }
        if list.count >= maxEvents {
            timestampsByKey[key] = list
            return false
        }
        list.append(now)
        timestampsByKey[key] = list
        return true
    }

    /// Checks whether `key` is already at or over the limit without recording a new event.
    public func isLimited(key rawKey: String, now: TimeInterval = Date().timeIntervalSinceReferenceDate) -> Bool {
        let key = IPAddressNormalizer.normalize(rawKey)
        lock.lock()
        defer { lock.unlock() }
        let cutoff = now - windowSeconds
        let count = (timestampsByKey[key] ?? []).filter { $0 > cutoff }.count
        return count >= maxEvents
    }

    /// Explicitly records a failure event (e.g., invalid WebDAV Basic Auth).
    public func recordEvent(key rawKey: String, now: TimeInterval = Date().timeIntervalSinceReferenceDate) {
        _ = allow(key: rawKey, now: now)
    }

    /// Clears failure history for `key` (e.g., after a client successfully authenticates).
    public func reset(key rawKey: String) {
        let key = IPAddressNormalizer.normalize(rawKey)
        lock.lock()
        timestampsByKey.removeValue(forKey: key)
        lock.unlock()
    }
}
