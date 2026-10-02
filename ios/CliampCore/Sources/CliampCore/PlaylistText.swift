import Foundation

/// What a stream URL's path points at when it is a playlist rather than the
/// media itself. Query strings are ignored, and the check is case-insensitive,
/// matching Android's `StreamResolver`.
public enum PlaylistKind: Equatable, Sendable {
    case m3u
    case pls
    /// HLS; the player handles it natively, never this parser.
    case hls
    case none

    public init(url: String) {
        let path = url.prefix { $0 != "?" }.lowercased()
        if path.hasSuffix(".m3u8") {
            self = .hls
        } else if path.hasSuffix(".m3u") {
            self = .m3u
        } else if path.hasSuffix(".pls") {
            self = .pls
        } else {
            self = .none
        }
    }
}

/// Directory entries sometimes point at a playlist file rather than the
/// stream. Plain `.m3u` and `.pls` are just text pointing somewhere else, so
/// one hop is followed; `.m3u8` is HLS and passes straight through.
public enum PlaylistText {
    /// The first non-comment, non-blank line carrying a URL.
    public static func firstURL(inM3U body: String) -> String? {
        body
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty && !$0.hasPrefix("#") && $0.contains("://") }
    }

    /// The first `File=` entry, case-insensitively, the way PLS files write it.
    public static func firstURL(inPLS body: String) -> String? {
        for line in body.split(whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let equals = trimmed.firstIndex(of: "=") else { continue }
            let key = trimmed[..<equals].trimmingCharacters(in: .whitespacesAndNewlines)
            guard key.lowercased().hasPrefix("file") else { continue }
            let value = trimmed[trimmed.index(after: equals)...].trimmingCharacters(in: .whitespacesAndNewlines)
            if value.contains("://") { return value }
        }
        return nil
    }
}
