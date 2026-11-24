import CryptoKit
import Foundation
import Security

public enum TLSIdentityError: Error, LocalizedError {
    case generationFailed(String)
    case pkcs12ImportFailed(OSStatus)
    case missingIdentity

    public var errorDescription: String? {
        switch self {
        case .generationFailed(let msg): return "TLS certificate generation failed: \(msg)"
        case .pkcs12ImportFailed(let st): return "TLS identity import failed (\(st))"
        case .missingIdentity: return "TLS identity missing from PKCS#12"
        }
    }
}

/// Manages a persistent self-signed X.509 server certificate and SecIdentity for the
/// Host Control HTTPS server (supporting headset TOFU fingerprint pinning).
public final class TLSIdentityManager: Sendable {
    public struct LoadedIdentity: @unchecked Sendable {
        public let secIdentity: SecIdentity
        public let certificateDER: Data
        /// Uppercase SHA-256 hex digest without separators.
        public let fingerprintSHA256: String
        public let certPEMPath: URL
    }

    private let tlsDirectory: URL

    public init(tlsDirectory: URL) {
        self.tlsDirectory = tlsDirectory
    }

    public func loadOrCreateIdentity(commonName: String) throws -> LoadedIdentity {
        let fm = FileManager.default
        try fm.createDirectory(at: tlsDirectory, withIntermediateDirectories: true)
        try? fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: tlsDirectory.path)

        let keyURL = tlsDirectory.appendingPathComponent("server.key.pem")
        let certURL = tlsDirectory.appendingPathComponent("server.cert.pem")
        let p12URL = tlsDirectory.appendingPathComponent("server.p12")
        let passURL = tlsDirectory.appendingPathComponent("server.p12.pass")

        if !fm.fileExists(atPath: p12URL.path)
            || !fm.fileExists(atPath: certURL.path)
            || !fm.fileExists(atPath: passURL.path)
        {
            try generateSelfSigned(
                commonName: commonName,
                keyURL: keyURL,
                certURL: certURL,
                p12URL: p12URL,
                passURL: passURL
            )
        }

        let passphrase = try String(contentsOf: passURL, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let p12Data = try Data(contentsOf: p12URL)

        var importedItems: CFArray?
        let options: [String: Any] = [kSecImportExportPassphrase as String: passphrase]
        let status = SecPKCS12Import(p12Data as CFData, options as CFDictionary, &importedItems)
        guard status == errSecSuccess else {
            throw TLSIdentityError.pkcs12ImportFailed(status)
        }
        guard let array = importedItems as? [[String: Any]],
              let first = array.first,
              let identityRef = first[kSecImportItemIdentity as String]
        else {
            throw TLSIdentityError.missingIdentity
        }
        // swiftlint:disable:next force_cast
        let secIdentity = identityRef as! SecIdentity

        var certRef: SecCertificate?
        SecIdentityCopyCertificate(secIdentity, &certRef)
        let derData: Data
        if let certRef {
            derData = SecCertificateCopyData(certRef) as Data
        } else {
            let pem = try String(contentsOf: certURL, encoding: .utf8)
            derData = Self.pemToDER(pem) ?? Data()
        }

        let fingerprint = Self.sha256Hex(derData)
        return LoadedIdentity(
            secIdentity: secIdentity,
            certificateDER: derData,
            fingerprintSHA256: fingerprint,
            certPEMPath: certURL
        )
    }

    private func generateSelfSigned(
        commonName: String,
        keyURL: URL,
        certURL: URL,
        p12URL: URL,
        passURL: URL
    ) throws {
        let fm = FileManager.default
        let passphrase = SecureRandom.hexString(byteCount: 16)
        try passphrase.write(to: passURL, atomically: true, encoding: .utf8)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: passURL.path)

        let safeCN = commonName
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: "\n", with: " ")
            .prefix(60)
        let subject = "/CN=\(safeCN.isEmpty ? "depth3d Host" : String(safeCN))"

        // Use system /usr/bin/openssl (LibreSSL shipped with macOS) for zero external dependencies.
        let openssl = "/usr/bin/openssl"
        try runProcess(openssl, arguments: [
            "req", "-x509",
            "-newkey", "rsa:2048",
            "-keyout", keyURL.path,
            "-out", certURL.path,
            "-days", "3650",
            "-nodes",
            "-subj", subject
        ])
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: keyURL.path)

        // Pass passphrase via file so it never appears in process argument listings.
        try runProcess(openssl, arguments: [
            "pkcs12", "-export",
            "-out", p12URL.path,
            "-inkey", keyURL.path,
            "-in", certURL.path,
            "-passout", "file:\(passURL.path)"
        ])
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p12URL.path)
        HostLogger.info("Generated persistent self-signed TLS identity", category: "tls")
    }

    private func runProcess(_ executable: String, arguments: [String]) throws {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: executable)
        proc.arguments = arguments
        let stderrPipe = Pipe()
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = stderrPipe
        try proc.run()
        proc.waitUntilExit()
        if proc.terminationStatus != 0 {
            let errData = stderrPipe.fileHandleForReading.readDataToEndOfFile()
            let errMsg = String(data: errData, encoding: .utf8) ?? "exit \(proc.terminationStatus)"
            throw TLSIdentityError.generationFailed(errMsg.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    /// Computes uppercase hex SHA-256 fingerprint of raw X.509 DER bytes.
    public static func sha256Hex(_ data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02X", $0) }.joined()
    }

    /// Converts an X.509 PEM certificate string into raw DER bytes.
    public static func pemToDER(_ pem: String) -> Data? {
        let lines = pem
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && !$0.hasPrefix("-----BEGIN") && !$0.hasPrefix("-----END") }
        let base64 = lines.joined()
        guard !base64.isEmpty else { return nil }
        return Data(base64Encoded: base64)
    }

    /// Computes SHA-256 hex fingerprint from an X.509 PEM certificate string.
    public static func sha256Hex(fromPEM pem: String) -> String? {
        guard let der = pemToDER(pem) else { return nil }
        return sha256Hex(der)
    }
}
