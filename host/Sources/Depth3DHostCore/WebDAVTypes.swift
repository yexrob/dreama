import Foundation

/// Parsed HTTP `Range: bytes=...` specification (single range, RFC 9110 §14.1.2).
public enum ByteRangeResult: Equatable, Sendable {
    case satisfied(start: UInt64, end: UInt64) // inclusive byte offsets
    case unsatisfiable
    case invalid
}

public enum ByteRangeParser {
    public static func parse(_ headerValue: String, fileSize: UInt64) -> ByteRangeResult {
        let trimmed = headerValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix("bytes=") else {
            return .invalid
        }
        let spec = String(trimmed.dropFirst("bytes=".count)).trimmingCharacters(in: .whitespaces)
        // Multiple ranges are not required for video seeking; take the first range if comma-separated.
        let firstRange = spec.split(separator: ",", maxSplits: 1).first.map(String.init) ?? spec
        guard let dash = firstRange.firstIndex(of: "-") else {
            return .invalid
        }

        let startStr = String(firstRange[..<dash]).trimmingCharacters(in: .whitespaces)
        let endStr = String(firstRange[firstRange.index(after: dash)...]).trimmingCharacters(in: .whitespaces)

        guard fileSize > 0 else {
            return .unsatisfiable
        }

        if startStr.isEmpty {
            // Suffix range: bytes=-N (last N bytes)
            guard let suffixLen = UInt64(endStr), suffixLen > 0 else {
                return .invalid
            }
            if suffixLen >= fileSize {
                return .satisfied(start: 0, end: fileSize - 1)
            }
            return .satisfied(start: fileSize - suffixLen, end: fileSize - 1)
        }

        guard let start = UInt64(startStr) else {
            return .invalid
        }
        if start >= fileSize {
            return .unsatisfiable
        }

        if endStr.isEmpty {
            // Open-ended range: bytes=start-
            return .satisfied(start: start, end: fileSize - 1)
        }

        guard let requestedEnd = UInt64(endStr) else {
            return .invalid
        }
        if requestedEnd < start {
            return .invalid
        }
        let clampedEnd = min(requestedEnd, fileSize - 1)
        return .satisfied(start: start, end: clampedEnd)
    }
}

/// HTTP Basic Authentication verifier using constant-time byte comparison.
public enum WebDAVAuth {
    public static func verify(authorizationHeader: String?, credentials: WebDAVCredentials) -> Bool {
        guard let header = authorizationHeader?.trimmingCharacters(in: .whitespacesAndNewlines),
              header.count > 6,
              header.prefix(6).lowercased() == "basic "
        else {
            return false
        }
        let b64 = String(header.dropFirst(6)).trimmingCharacters(in: .whitespaces)
        guard let decoded = Data(base64Encoded: b64) else {
            return false
        }
        let expected = Data("\(credentials.user):\(credentials.pass)".utf8)
        return constantTimeEqual(decoded, expected)
    }

    public static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}

/// Safely decodes and normalizes WebDAV URI paths, rejecting directory traversal attempts.
public enum WebDAVPathSanitizer {
    public struct ResolvedPath: Equatable, Sendable {
        public let segments: [String]
        public var isRoot: Bool { segments.isEmpty }
        public var mountName: String? { segments.first }
        public var relativeSegments: [String] { Array(segments.dropFirst()) }

        public init(segments: [String]) {
            self.segments = segments
        }
    }

    public static func resolve(_ rawPath: String) -> ResolvedPath? {
        guard rawPath.hasPrefix("/") else { return nil }
        // Split raw path BEFORE percent-decoding so `%2F` cannot sneak in path separators.
        let rawParts = rawPath.split(separator: "/", omittingEmptySubsequences: true)
        var decodedSegments: [String] = []
        decodedSegments.reserveCapacity(rawParts.count)

        for raw in rawParts {
            guard let decoded = String(raw).removingPercentEncoding else {
                return nil
            }
            if decoded.isEmpty || decoded == "." || decoded == ".."
                || decoded.contains("/") || decoded.contains("\\") || decoded.contains("\0")
            {
                return nil
            }
            decodedSegments.append(decoded)
        }
        return ResolvedPath(segments: decodedSegments)
    }

    /// Percent-encodes a single path segment for WebDAV `<d:href>`.
    public static func encodeSegment(_ segment: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#%+")
        return segment.addingPercentEncoding(withAllowedCharacters: allowed) ?? segment
    }

    /// Builds a percent-encoded href starting with `/` (and ending with `/` if `isDirectory`).
    public static func encodeHref(segments: [String], isDirectory: Bool) -> String {
        if segments.isEmpty {
            return "/"
        }
        let joined = "/" + segments.map(encodeSegment).joined(separator: "/")
        return isDirectory ? (joined + "/") : joined
    }
}

/// Represents one resource entry in a WebDAV `207 Multi-Status` XML response.
public struct WebDAVEntry: Equatable, Sendable {
    public let href: String
    public let displayName: String
    public let isDirectory: Bool
    public let contentLength: UInt64?
    public let lastModified: Date?
    public let contentType: String?

    public init(
        href: String,
        displayName: String,
        isDirectory: Bool,
        contentLength: UInt64? = nil,
        lastModified: Date? = nil,
        contentType: String? = nil
    ) {
        self.href = href
        self.displayName = displayName
        self.isDirectory = isDirectory
        self.contentLength = contentLength
        self.lastModified = lastModified
        self.contentType = contentType
    }
}

/// Generates RFC 4918 `207 Multi-Status` XML compatible with `player/.../WebDav.java`.
public enum WebDAVXMLBuilder {
    public static func buildMultiStatus(_ entries: [WebDAVEntry]) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"

        var xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        xml += "<d:multistatus xmlns:d=\"DAV:\">\n"
        for entry in entries {
            xml += "  <d:response>\n"
            xml += "    <d:href>\(escapeXML(entry.href))</d:href>\n"
            xml += "    <d:propstat>\n"
            xml += "      <d:prop>\n"
            if entry.isDirectory {
                xml += "        <d:resourcetype><d:collection/></d:resourcetype>\n"
            } else {
                xml += "        <d:resourcetype/>\n"
            }
            xml += "        <d:displayname>\(escapeXML(entry.displayName))</d:displayname>\n"
            if let len = entry.contentLength, !entry.isDirectory {
                xml += "        <d:getcontentlength>\(len)</d:getcontentlength>\n"
            }
            if let ct = entry.contentType, !entry.isDirectory {
                xml += "        <d:getcontenttype>\(escapeXML(ct))</d:getcontenttype>\n"
            }
            if let mod = entry.lastModified {
                xml += "        <d:getlastmodified>\(formatter.string(from: mod))</d:getlastmodified>\n"
            }
            xml += "      </d:prop>\n"
            xml += "      <d:status>HTTP/1.1 200 OK</d:status>\n"
            xml += "    </d:propstat>\n"
            xml += "  </d:response>\n"
        }
        xml += "</d:multistatus>\n"
        return Data(xml.utf8)
    }

    /// Rewrites `<href>` / `<D:href>` / `<d:href>` paths in an upstream rclone WebDAV XML
    /// response so all paths are prefixed with `/<encodedMountName>`.
    public static func rewriteProxiedMultiStatusXML(_ xmlData: Data, mountName: String) -> Data {
        guard let xml = String(data: xmlData, encoding: .utf8) else { return xmlData }
        let prefix = "/" + WebDAVPathSanitizer.encodeSegment(mountName)
        let pattern = "(<[a-zA-Z0-9_-]*:?href>)([^<]*)(</[a-zA-Z0-9_-]*:?href>)"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return xmlData
        }
        let ns = xml as NSString
        let matches = regex.matches(in: xml, range: NSRange(location: 0, length: ns.length))
        var result = xml
        for match in matches.reversed() {
            let hrefRange = match.range(at: 2)
            let rawHref = ns.substring(with: hrefRange)
            let pathOnly: String
            if let schemeRange = rawHref.range(of: "://") {
                if let slashIdx = rawHref[schemeRange.upperBound...].firstIndex(of: "/") {
                    pathOnly = String(rawHref[slashIdx...])
                } else {
                    pathOnly = "/"
                }
            } else {
                pathOnly = rawHref.hasPrefix("/") ? rawHref : ("/" + rawHref)
            }
            let rewritten = (pathOnly == "/") ? (prefix + "/") : (prefix + pathOnly)
            if let swiftRange = Range(hrefRange, in: result) {
                result.replaceSubrange(swiftRange, with: rewritten)
            }
        }
        return Data(result.utf8)
    }

    public static func escapeXML(_ text: String) -> String {
        var out = text
        out = out.replacingOccurrences(of: "&", with: "&amp;")
        out = out.replacingOccurrences(of: "<", with: "&lt;")
        out = out.replacingOccurrences(of: ">", with: "&gt;")
        out = out.replacingOccurrences(of: "\"", with: "&quot;")
        out = out.replacingOccurrences(of: "'", with: "&apos;")
        return out
    }

    public static func mimeType(forPath path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        switch ext {
        case "mp4", "m4v": return "video/mp4"
        case "mkv": return "video/x-matroska"
        case "mov": return "video/quicktime"
        case "webm": return "video/webm"
        case "ts", "m2ts": return "video/mp2t"
        case "avi": return "video/x-msvideo"
        case "3gp": return "video/3gpp"
        case "srt": return "application/x-subrip"
        case "vtt": return "text/vtt"
        case "ass", "ssa": return "text/plain; charset=utf-8"
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        default: return "application/octet-stream"
        }
    }
}
