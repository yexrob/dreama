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

/// Incremental, zero-truncation O(N) streaming rewriter for `<href>` / `<D:href>` / `<d:href>`
/// tags inside proxied `207 Multi-Status` XML responses of arbitrary size (> 8 MB).
public final class WebDAVHrefStreamRewriter {
    private let prefixBytes: [UInt8]
    private var pending = Data()
    private let maxBufferedHrefBytes = 64 * 1024

    public init(mountName: String) {
        let prefix = "/" + WebDAVPathSanitizer.encodeSegment(mountName)
        self.prefixBytes = Array(prefix.utf8)
    }

    /// Feeds the next raw UTF-8 XML chunk and returns rewritten bytes ready to stream downstream.
    public func push(_ chunk: Data) -> Data {
        guard !chunk.isEmpty else { return Data() }
        pending.append(chunk)
        return drain(isFinal: false)
    }

    /// Flushes any trailing buffered bytes at EOF.
    public func finish() -> Data {
        drain(isFinal: true)
    }

    private func drain(isFinal: Bool) -> Data {
        guard !pending.isEmpty else { return Data() }
        let bytes = [UInt8](pending)
        var out = [UInt8]()
        out.reserveCapacity(bytes.count + 128)
        var i = 0
        let count = bytes.count

        while i < count {
            // Find next '<' (0x3C)
            guard let ltOffset = bytes[i..<count].firstIndex(of: 0x3C) else {
                out.append(contentsOf: bytes[i..<count])
                i = count
                break
            }

            if ltOffset > i {
                out.append(contentsOf: bytes[i..<ltOffset])
            }

            // Find matching '>' (0x3E) for this tag
            guard let gtOffset = bytes[ltOffset..<count].firstIndex(of: 0x3E) else {
                if isFinal || (count - ltOffset) > maxBufferedHrefBytes {
                    out.append(contentsOf: bytes[ltOffset..<count])
                    i = count
                } else {
                    i = ltOffset
                }
                break
            }

            // Check whether bytes[(ltOffset + 1)..<gtOffset] is an opening `<...:href...>` tag.
            if isOpeningHrefTag(bytes, start: ltOffset + 1, end: gtOffset) {
                let valueStart = gtOffset + 1
                // Search for closing `<` starting `</...:href>`
                guard let closeLT = bytes[valueStart..<count].firstIndex(of: 0x3C) else {
                    if isFinal || (count - ltOffset) > maxBufferedHrefBytes {
                        out.append(contentsOf: bytes[ltOffset..<count])
                        i = count
                    } else {
                        i = ltOffset
                    }
                    break
                }
                guard let closeGT = bytes[closeLT..<count].firstIndex(of: 0x3E) else {
                    if isFinal || (count - ltOffset) > maxBufferedHrefBytes {
                        out.append(contentsOf: bytes[ltOffset..<count])
                        i = count
                    } else {
                        i = ltOffset
                    }
                    break
                }

                if isClosingHrefTag(bytes, start: closeLT + 1, end: closeGT) {
                    // Emit opening tag
                    out.append(contentsOf: bytes[ltOffset...gtOffset])
                    // Rewrite inner href text
                    let rawHref = String(decoding: bytes[valueStart..<closeLT], as: UTF8.self)
                    let rewritten = rewriteHrefValue(rawHref)
                    out.append(contentsOf: rewritten.utf8)
                    // Emit closing tag
                    out.append(contentsOf: bytes[closeLT...closeGT])
                    i = closeGT + 1
                    continue
                }
            }

            // Not a `<href>...</href>` element: copy opening tag verbatim and advance past '>'.
            out.append(contentsOf: bytes[ltOffset...gtOffset])
            i = gtOffset + 1
        }

        if i >= count {
            pending.removeAll(keepingCapacity: true)
        } else {
            pending = Data(bytes[i..<count])
        }
        return Data(out)
    }

    private func rewriteHrefValue(_ rawHref: String) -> String {
        let prefix = String(decoding: prefixBytes, as: UTF8.self)
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
        return (pathOnly == "/") ? (prefix + "/") : (prefix + pathOnly)
    }

    /// Matches `href` or `<prefix>:href` (case-insensitive) optionally followed by whitespace/attributes,
    /// rejecting closing tags (`/href`) or self-closing tags (`href/`).
    private func isOpeningHrefTag(_ bytes: [UInt8], start: Int, end: Int) -> Bool {
        guard start < end else { return false }
        if bytes[start] == 0x2F || bytes[start] == 0x3F || bytes[start] == 0x21 { return false } // '/', '?', '!'
        if bytes[end - 1] == 0x2F { return false } // self-closing `<href/>`

        // Tag name runs until first whitespace or end
        var nameEnd = start
        while nameEnd < end {
            let b = bytes[nameEnd]
            if b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D { break }
            nameEnd += 1
        }
        return matchesHrefName(bytes, start: start, end: nameEnd)
    }

    private func isClosingHrefTag(_ bytes: [UInt8], start: Int, end: Int) -> Bool {
        guard start + 1 < end, bytes[start] == 0x2F else { return false } // starts with '/'
        var nameEnd = end
        while nameEnd > start + 1 {
            let b = bytes[nameEnd - 1]
            if b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D {
                nameEnd -= 1
            } else {
                break
            }
        }
        return matchesHrefName(bytes, start: start + 1, end: nameEnd)
    }

    private func matchesHrefName(_ bytes: [UInt8], start: Int, end: Int) -> Bool {
        let len = end - start
        guard len >= 4 else { return false }
        // Find optional namespace prefix `:`
        var localStart = start
        for idx in start..<end {
            let b = bytes[idx]
            if b == 0x3A { // ':'
                localStart = idx + 1
            } else if !isXMLNameChar(b) {
                return false
            }
        }
        guard end - localStart == 4 else { return false }
        // Case-insensitive "href"
        return (bytes[localStart] | 0x20) == 0x68     // h
            && (bytes[localStart + 1] | 0x20) == 0x72 // r
            && (bytes[localStart + 2] | 0x20) == 0x65 // e
            && (bytes[localStart + 3] | 0x20) == 0x66 // f
    }

    private func isXMLNameChar(_ b: UInt8) -> Bool {
        (b >= 0x41 && b <= 0x5A) // A-Z
            || (b >= 0x61 && b <= 0x7A) // a-z
            || (b >= 0x30 && b <= 0x39) // 0-9
            || b == 0x2D || b == 0x5F || b == 0x2E // '-', '_', '.'
    }
}

/// Incremental HTTP/1.1 `Transfer-Encoding: chunked` body decoder for streaming proxy responses.
public final class HTTPChunkedStreamDecoder {
    private enum State {
        case readingSizeLine
        case readingData(remaining: Int)
        case readingTrailingCRLF
        case done
    }

    private var state: State = .readingSizeLine
    private var buffer = Data()

    public var isDone: Bool {
        if case .done = state { return true }
        return false
    }

    public init() {}

    public func push(_ incoming: Data) -> Data {
        guard !incoming.isEmpty, !isDone else { return Data() }
        buffer.append(incoming)
        var out = Data()
        let crlf = Data("\r\n".utf8)

        loop: while !buffer.isEmpty {
            switch state {
            case .readingSizeLine:
                guard let lineEnd = buffer.range(of: crlf) else { break loop }
                let sizeStr = String(decoding: buffer[buffer.startIndex..<lineEnd.lowerBound], as: UTF8.self)
                    .split(separator: ";").first?
                    .trimmingCharacters(in: .whitespaces) ?? ""
                buffer.removeSubrange(buffer.startIndex..<lineEnd.upperBound)
                guard let size = Int(sizeStr, radix: 16), size >= 0 else {
                    state = .done
                    break loop
                }
                if size == 0 {
                    state = .done
                    buffer.removeAll()
                    break loop
                }
                state = .readingData(remaining: size)

            case .readingData(let remaining):
                let take = min(remaining, buffer.count)
                if take > 0 {
                    out.append(buffer[buffer.startIndex..<(buffer.startIndex + take)])
                    buffer.removeSubrange(buffer.startIndex..<(buffer.startIndex + take))
                }
                let left = remaining - take
                if left == 0 {
                    state = .readingTrailingCRLF
                } else {
                    state = .readingData(remaining: left)
                    break loop
                }

            case .readingTrailingCRLF:
                guard buffer.count >= 2 else { break loop }
                if buffer[buffer.startIndex] == 0x0D && buffer[buffer.startIndex + 1] == 0x0A {
                    buffer.removeSubrange(buffer.startIndex..<(buffer.startIndex + 2))
                }
                state = .readingSizeLine

            case .done:
                break loop
            }
        }
        return out
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
    /// response in linear O(N) time without any 8 MB size cap.
    public static func rewriteProxiedMultiStatusXML(_ xmlData: Data, mountName: String) -> Data {
        let rewriter = WebDAVHrefStreamRewriter(mountName: mountName)
        var out = rewriter.push(xmlData)
        out.append(rewriter.finish())
        return out
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
