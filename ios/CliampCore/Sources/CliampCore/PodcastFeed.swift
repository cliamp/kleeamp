import Foundation

/// RSS, pulled rather than loaded. Real feeds run from 460 KB / 59 episodes to
/// 4.4 MB / 865, so this walks with `XMLParser` and stops at `maxEpisodes`:
/// feeds are newest-first by convention, so stopping early drops the oldest,
/// which is the right end. Ported from Android's `PodcastFeed.kt`.
public enum PodcastFeed {
    private static let itunesNamespace = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    /// Newest episodes kept per feed. The oldest of an 865-entry feed is noise.
    public static let maxEpisodes = 300

    public struct Loaded: Sendable {
        public let show: PodcastShow
        public let episodes: [PodcastEpisode]
    }

    /// Fetches then parses `show`'s feed. The fetch is bounded; a feed larger
    /// than that is malformed or hostile.
    public static func load(
        show: PodcastShow, transport: PodcastFeedTransport = URLSessionPodcastFeedTransport()
    ) async throws -> Loaded {
        let data = try await transport.data(show.feedUrl)
        guard data.count <= 8 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
        return try parse(base: show, data: data)
    }

    public static func parse(base: PodcastShow, data: Data) throws -> Loaded {
        let delegate = Delegate(base: base)
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.delegate = delegate
        _ = parser.parse()
        // Aborting at the episode cap is success, not failure.
        if let error = parser.parserError, delegate.episodes.count < maxEpisodes {
            throw error
        }
        return delegate.loaded()
    }

    private final class Delegate: NSObject, XMLParserDelegate {
        private let base: PodcastShow
        var episodes: [PodcastEpisode] = []

        private var chTitle = ""
        private var chAuthor = ""
        private var chArtwork = ""
        private var chDescription = ""
        private var chGenre = ""

        private var inItem = false
        private var inChannelImage = false

        private var guid = ""
        private var title = ""
        private var audio = ""
        private var duration: Int64 = 0
        private var published: Int64 = 0
        private var artwork = ""
        private var itemDescription = ""
        private var number = 0
        private var season = 0
        private var type = "full"
        private var codec = ""

        private var text = ""
        private var elementName = ""
        private var elementNamespace = ""
        private var capturing = false

        init(base: PodcastShow) {
            self.base = base
        }

        func loaded() -> Loaded {
            // Apple's copy of a show is usually better than the feed's, so the
            // feed only fills in what is missing.
            let show = PodcastShow(
                id: base.id,
                title: firstNonBlank(base.title, chTitle, "untitled show"),
                feedUrl: base.feedUrl,
                author: firstNonBlank(base.author, chAuthor),
                artwork: firstNonBlank(base.artwork, chArtwork),
                genre: firstNonBlank(base.genre, chGenre),
                episodeCount: base.episodeCount > 0 ? base.episodeCount : episodes.count,
                description: firstNonBlank(base.description, chDescription)
            )
            return Loaded(show: show, episodes: episodes)
        }

        private func firstNonBlank(_ values: String...) -> String {
            values.first { !$0.isEmpty } ?? ""
        }

        private func resetItem() {
            guid = ""; title = ""; audio = ""; duration = 0; published = 0
            artwork = ""; itemDescription = ""; number = 0; season = 0; type = "full"; codec = ""
        }

        func parser(
            _ parser: XMLParser, didStartElement elementName: String,
            namespaceURI: String?, qualifiedName: String?,
            attributes: [String: String] = [:]
        ) {
            self.elementName = elementName
            elementNamespace = namespaceURI ?? ""
            text = ""
            capturing = true

            if elementName == "item" {
                inItem = true
                resetItem()
                capturing = false
                return
            }
            if inItem {
                if elementNamespace == Self.itunesNamespaceName {
                    switch elementName {
                    case "image":
                        artwork = (attributes["href"] ?? "").trimmingCharacters(in: .whitespaces)
                        capturing = false
                    case "enclosure":
                        capturing = false
                    default:
                        break
                    }
                } else if elementName == "enclosure" {
                    let url = (attributes["url"] ?? "").trimmingCharacters(in: .whitespaces)
                    let mime = attributes["type"] ?? ""
                    if audio.isEmpty, !url.isEmpty, isAudio(mime: mime, url: url) {
                        audio = url
                        codec = codecOf(mime: mime, url: url)
                    }
                    capturing = false
                }
                return
            }
            switch (elementNamespace, elementName) {
            case (Self.itunesNamespaceName, "image"):
                if chArtwork.isEmpty {
                    chArtwork = (attributes["href"] ?? "").trimmingCharacters(in: .whitespaces)
                }
                capturing = false
            case (Self.itunesNamespaceName, "category"):
                if chGenre.isEmpty {
                    chGenre = (attributes["text"] ?? "").trimmingCharacters(in: .whitespaces)
                }
                capturing = false
            case (_, "image"):
                inChannelImage = true
                capturing = false
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if capturing { text += string }
        }

        func parser(
            _ parser: XMLParser, didEndElement elementName: String,
            namespaceURI: String?, qualifiedName: String?
        ) {
            defer {
                capturing = false
                text = ""
            }
            if elementName == "item", inItem {
                inItem = false
                if !audio.isEmpty {
                    episodes.append(
                        PodcastEpisode(
                            // guid is optional and wildly inconsistent between
                            // hosts, so the audio URL stands in.
                            guid: guid.isEmpty ? audio : guid,
                            title: title,
                            audioUrl: audio,
                            durationMs: duration,
                            publishedAt: published,
                            artwork: artwork,
                            description: itemDescription,
                            number: number,
                            season: season,
                            type: type,
                            codec: codec
                        )
                    )
                }
                if episodes.count >= Self.maxEpisodesCount {
                    parser.abortParsing()
                }
                return
            }
            if elementName == "image", !inItem {
                inChannelImage = false
                return
            }
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if inItem {
                if elementNamespace == Self.itunesNamespaceName {
                    switch elementName {
                    case "duration": duration = parseDuration(value)
                    case "episode": number = Int(value) ?? 0
                    case "season": season = Int(value) ?? 0
                    case "episodeType": type = value
                    case "title": if title.isEmpty { title = value }
                    case "summary": if itemDescription.isEmpty { itemDescription = strip(value) }
                    default: break
                    }
                    return
                }
                switch elementName {
                case "title": title = value
                case "guid": guid = value
                case "pubDate": published = parseDate(value)
                case "description": if itemDescription.isEmpty { itemDescription = strip(value) }
                default: break
                }
                return
            }
            if elementNamespace == Self.itunesNamespaceName {
                switch elementName {
                case "author": if chAuthor.isEmpty { chAuthor = value }
                case "summary": if chDescription.isEmpty { chDescription = strip(value) }
                default: break
                }
                return
            }
            switch elementName {
            case "title": if chTitle.isEmpty { chTitle = value }
            case "description": if chDescription.isEmpty { chDescription = strip(value) }
            case "url": if inChannelImage, chArtwork.isEmpty { chArtwork = value }
            default: break
            }
        }

        private static var itunesNamespaceName: String {
            "http://www.itunes.com/dtds/podcast-1.0.dtd"
        }

        private static var maxEpisodesCount: Int { PodcastFeed.maxEpisodes }
    }

    /// `01:35:02`, `36:12` and `2712` all appear in the wild; the spec allows
    /// all three. Bare seconds is the one that would silently become 2712
    /// hours if treated as HH.
    public static func parseDuration(_ raw: String) -> Int64 {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return 0 }
        if !s.contains(":") {
            // A feed can say `nan`, `inf` or a value whose milliseconds leave
            // Int64; none of those may trap.
            guard let seconds = Double(s), seconds.isFinite, seconds >= 0 else { return 0 }
            let milliseconds = seconds * 1000
            guard milliseconds.isFinite, milliseconds <= Double(Int64.max) else { return 0 }
            return Int64(milliseconds)
        }
        let parts = s.split(separator: ":").map { Int64($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.allSatisfy({ $0 != nil && $0! >= 0 }) else { return 0 }
        let values = parts.map { $0! }
        let seconds: Int64
        switch values.count {
        case 3:
            let (hours, o1) = values[0].multipliedReportingOverflow(by: 3600)
            let (minutes, o2) = values[1].multipliedReportingOverflow(by: 60)
            let (hm, o3) = hours.addingReportingOverflow(minutes)
            let (total, o4) = hm.addingReportingOverflow(values[2])
            guard ![o1, o2, o3, o4].contains(true) else { return 0 }
            seconds = total
        case 2:
            let (minutes, o1) = values[0].multipliedReportingOverflow(by: 60)
            let (total, o2) = minutes.addingReportingOverflow(values[1])
            guard !o1, !o2 else { return 0 }
            seconds = total
        case 1:
            seconds = values[0]
        default:
            return 0
        }
        let (milliseconds, overflow) = seconds.multipliedReportingOverflow(by: 1000)
        guard !overflow, milliseconds >= 0 else { return 0 }
        return milliseconds
    }

    /// RFC 822 dates, loosely: feeds differ on padded days and numeric vs named
    /// zones, so a short list of shapes is tried in order.
    public static func parseDate(_ raw: String) -> Int64 {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return 0 }
        for formatter in dateFormatters {
            if let date = formatter.date(from: s) {
                return Int64(date.timeIntervalSince1970 * 1000)
            }
        }
        if let date = ISO8601DateFormatter().date(from: s) {
            return Int64(date.timeIntervalSince1970 * 1000)
        }
        return 0
    }

    private static let dateFormatters: [DateFormatter] = {
        let patterns = [
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss Z",
        ]
        return patterns.map { pattern in
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = pattern
            return formatter
        }
    }()

    private static func isAudio(mime: String, url: String) -> Bool {
        if mime.lowercased().hasPrefix("audio") { return true }
        if !mime.isEmpty { return false }
        let path = url.prefix { $0 != "?" }.lowercased()
        let extensions = [".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"]
        return extensions.contains { path.hasSuffix($0) }
    }

    private static func codecOf(mime: String, url: String) -> String {
        let lowered = mime.lowercased()
        if lowered.contains("mpeg") { return "mp3" }
        if lowered.contains("mp4") || lowered.contains("m4a") { return "m4a" }
        if lowered.contains("aac") { return "aac" }
        if lowered.contains("opus") { return "opus" }
        if lowered.contains("ogg") || lowered.contains("vorbis") { return "ogg" }
        if lowered.contains("wav") { return "wav" }
        if lowered.contains("flac") { return "flac" }
        let path = url.prefix { $0 != "?" }
        return String(path.split(separator: ".").last ?? "").lowercased()
    }

    private static let tagPattern = try? NSRegularExpression(pattern: "<[^>]+>")
    private static let spacePattern = try? NSRegularExpression(pattern: "\\s+")

    /// Show notes are HTML and rendered as one line, so the markup goes.
    /// Deliberately not a parse, the same call `og:image` discovery makes.
    public static func strip(_ raw: String) -> String {
        var value = raw
        if let tagPattern {
            value = tagPattern.stringByReplacingMatches(
                in: value, range: NSRange(value.startIndex..., in: value), withTemplate: " "
            )
        }
        let entities = [
            "&nbsp;": " ", "&amp;": "&", "&lt;": "<", "&gt;": ">",
            "&quot;": "\"", "&#39;": "'", "&apos;": "'",
        ]
        for (entity, replacement) in entities {
            value = value.replacingOccurrences(of: entity, with: replacement)
        }
        if let spacePattern {
            value = spacePattern.stringByReplacingMatches(
                in: value, range: NSRange(value.startIndex..., in: value), withTemplate: " "
            )
        }
        return value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Fetches a feed body; injectable so tests never hit the network.
public protocol PodcastFeedTransport: Sendable {
    func data(_ urlString: String) async throws -> Data
}

public struct URLSessionPodcastFeedTransport: PodcastFeedTransport {
    public init() {}

    public func data(_ urlString: String) async throws -> Data {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        // `data(for:)` would accumulate the whole body before any size check,
        // so the loader cancels at the cap as bytes arrive.
        return try await BoundedDataLoader(limit: 8 * 1024 * 1024).load(request)
    }
}

/// One bounded request: ends with the body when it fits under `limit`, or an
/// error as soon as it does not. A continuously delivering endpoint cannot
/// keep it alive forever because the cap, not the idle timer, ends it.
private final class BoundedDataLoader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let limit: Int
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Data, Error>?
    private var data = Data()
    private var status = 0

    init(limit: Int) {
        self.limit = limit
    }

    func load(_ request: URLRequest) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            lock.withLock { self.continuation = continuation }
            let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
            session.dataTask(with: request).resume()
        }
    }

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.withLock { status = (response as? HTTPURLResponse)?.statusCode ?? 0 }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        let overflow = lock.withLock {
            if data.count + chunk.count > limit { return true }
            data.append(chunk)
            return false
        }
        if overflow {
            session.invalidateAndCancel()
            finish(.failure(URLError(.dataLengthExceedsMaximum)))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            finish(.failure(error))
            return
        }
        let code = lock.withLock { status }
        guard (200..<300).contains(code) else {
            finish(.failure(URLError(.badServerResponse)))
            return
        }
        finish(.success(lock.withLock { data }))
    }

    private func finish(_ result: Result<Data, Error>) {
        let continuation = lock.withLock {
            let held = self.continuation
            self.continuation = nil
            return held
        }
        guard let continuation else { return }
        continuation.resume(with: result)
    }
}
