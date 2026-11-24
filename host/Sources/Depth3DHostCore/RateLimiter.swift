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

/// Bounds the maximum number of simultaneous active TCP connections on a server.
public final class ConnectionGuard: @unchecked Sendable {
    public let maxConnections: Int
    private let lock = NSLock()
    private var active: Int = 0

    public init(maxConnections: Int) {
        self.maxConnections = max(1, maxConnections)
    }

    public var activeCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return active
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
