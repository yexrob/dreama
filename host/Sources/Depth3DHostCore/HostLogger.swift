import Foundation
import os

/// Unified logger with mandatory `depth3d:` prefix.
/// Never log passwords, private keys, tokens, or pairing PINs.
public enum HostLogger {
    private static let subsystem = "dev.depth3d.host"
    private static let logger = Logger(subsystem: subsystem, category: "host")
    private static let lock = NSLock()
    public static var echoToStderr: Bool = ProcessInfo.processInfo.environment["DEPTH3D_HOST_STDERR_LOG"] == "1"

    public static func info(_ message: String, category: String = "host") {
        let formatted = "depth3d: [\(category)] \(message)"
        logger.info("\(formatted, privacy: .public)")
        mirrorIfNeeded(formatted)
    }

    public static func warning(_ message: String, category: String = "host") {
        let formatted = "depth3d: [\(category)] WARN: \(message)"
        logger.warning("\(formatted, privacy: .public)")
        mirrorIfNeeded(formatted)
    }

    public static func error(_ message: String, category: String = "host") {
        let formatted = "depth3d: [\(category)] ERROR: \(message)"
        logger.error("\(formatted, privacy: .public)")
        mirrorIfNeeded(formatted)
    }

    /// Replaces sensitive values with a fixed placeholder before logging.
    public static func redact(_ secret: String) -> String {
        secret.isEmpty ? "<empty>" : "<redacted(\(secret.count))>"
    }

    private static func mirrorIfNeeded(_ line: String) {
        guard echoToStderr else { return }
        lock.lock()
        defer { lock.unlock() }
        if let data = (line + "\n").data(using: .utf8) {
            FileHandle.standardError.write(data)
        }
    }
}
