import Foundation

/// A show, as opposed to an episode.
///
/// Episodes reach the player as `Station`s, like radio does; a show is what
/// you subscribe to, what a feed is fetched for, and what the directory
/// returns. Ported from Android's `Podcast.kt` and `PodcastDirectory.kt`.
public struct PodcastShow: Codable, Identifiable, Hashable, Sendable {
    /// Apple's collection id, or `feed:<hash>` for a feed added by URL.
    public let id: String
    public var title: String
    public var feedUrl: String
    public var author: String
    public var artwork: String
    public var genre: String
    public var episodeCount: Int
    public var description: String

    public init(
        id: String, title: String, feedUrl: String,
        author: String = "", artwork: String = "", genre: String = "",
        episodeCount: Int = 0, description: String = ""
    ) {
        self.id = id
        self.title = title
        self.feedUrl = feedUrl
        self.author = author
        self.artwork = artwork
        self.genre = genre
        self.episodeCount = episodeCount
        self.description = description
    }

    /// `jack rhysider · technology · 198 eps`, skipping what the directory omitted.
    public var meta: String {
        var parts: [String] = []
        if !author.isEmpty { parts.append(author.lowercased()) }
        if !genre.isEmpty { parts.append(genre.lowercased()) }
        if episodeCount > 0 { parts.append("\(episodeCount) eps") }
        return parts.joined(separator: " · ")
    }
}

/// One entry out of a feed. Everything here comes from the RSS item; artwork
/// falls back to the channel's when an episode carries none.
public struct PodcastEpisode: Codable, Hashable, Sendable {
    public var guid: String
    public var title: String
    public var audioUrl: String
    public var durationMs: Int64
    public var publishedAt: Int64
    public var artwork: String
    public var description: String
    public var number: Int
    public var season: Int
    /// `full`, `trailer` or `bonus`, per `itunes:episodeType`.
    public var type: String
    public var codec: String

    public init(
        guid: String, title: String, audioUrl: String, durationMs: Int64 = 0,
        publishedAt: Int64 = 0, artwork: String = "", description: String = "",
        number: Int = 0, season: Int = 0, type: String = "full", codec: String = ""
    ) {
        self.guid = guid
        self.title = title
        self.audioUrl = audioUrl
        self.durationMs = durationMs
        self.publishedAt = publishedAt
        self.artwork = artwork
        self.description = description
        self.number = number
        self.season = season
        self.type = type
        self.codec = codec
    }

    public var isFull: Bool {
        type.isEmpty || type.caseInsensitiveCompare("full") == .orderedSame
    }

    /// The episode as the player sees it. `cover` is authoritative here:
    /// a feed hands real artwork, so there is nothing to scrape.
    public func station(show: PodcastShow) -> Station {
        Station(
            id: "pod:\(show.id):\(guid)",
            name: title.isEmpty ? "untitled episode" : title,
            url: audioUrl,
            source: .podcast,
            slug: show.id,
            tags: show.genre,
            codec: codec,
            cover: artwork.isEmpty ? show.artwork : artwork,
            artist: show.title,
            durationMs: durationMs
        )
    }
}

/// Saved listening position for one episode, keyed by its audio URL.
public struct EpisodeProgress: Codable, Hashable, Sendable {
    public var url: String
    public var positionMs: Int64
    public var durationMs: Int64
    public var completed: Bool
    public var updatedAt: Int64

    public init(
        url: String, positionMs: Int64, durationMs: Int64,
        completed: Bool, updatedAt: Int64 = 0
    ) {
        self.url = url
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.completed = completed
        self.updatedAt = updatedAt
    }

    public var fraction: Double {
        durationMs <= 0 ? 0 : min(max(Double(positionMs) / Double(durationMs), 0), 1)
    }
}

/// A file cliamp fetched, by remote audio URL, plus the station snapshot that
/// plays it. Keyed by URL everywhere so download, resume and queue agree.
public struct DownloadEntry: Codable, Hashable, Sendable {
    public var url: String
    public var path: String
    public var bytes: Int64
    public var station: Station
    public var downloadedAt: Int64
    /// Fetched by auto-download rather than a tap; retention only sweeps these.
    public var auto: Bool

    public init(
        url: String, path: String, bytes: Int64 = 0, station: Station,
        downloadedAt: Int64 = 0, auto: Bool = false
    ) {
        self.url = url
        self.path = path
        self.bytes = bytes
        self.station = station
        self.downloadedAt = downloadedAt
        self.auto = auto
    }
}

/// One of Apple's podcast categories. The ids are Apple's own.
public struct PodcastGenre: Identifiable, Hashable, Sendable {
    public let id: Int
    public let name: String

    public init(id: Int, name: String) {
        self.id = id
        self.name = name
    }
}

/// Apple's podcast directory: open, no key, no account. Search has no offset
/// so a query is one request paged in the UI; charts return ids only and are
/// resolved with `lookup`; a category browse is the genre name narrowed by id.
public enum PodcastDirectory {
    public static let base = "https://itunes.apple.com"
    /// What a search actually returns at most, whatever limit is asked for.
    public static let searchLimit = 100
    /// The whole top chart; the feed generator refuses more.
    public static let chartLimit = 200

    public static let genres: [PodcastGenre] = [
        PodcastGenre(id: 1489, name: "News"),
        PodcastGenre(id: 1318, name: "Technology"),
        PodcastGenre(id: 1488, name: "True Crime"),
        PodcastGenre(id: 1303, name: "Comedy"),
        PodcastGenre(id: 1324, name: "Society & Culture"),
        PodcastGenre(id: 1487, name: "History"),
        PodcastGenre(id: 1533, name: "Science"),
        PodcastGenre(id: 1512, name: "Health & Fitness"),
        PodcastGenre(id: 1321, name: "Business"),
        PodcastGenre(id: 1310, name: "Music"),
        PodcastGenre(id: 1545, name: "Sports"),
        PodcastGenre(id: 1301, name: "Arts"),
        PodcastGenre(id: 1304, name: "Education"),
        PodcastGenre(id: 1483, name: "Fiction"),
        PodcastGenre(id: 1309, name: "TV & Film"),
        PodcastGenre(id: 1314, name: "Religion & Spirituality"),
        PodcastGenre(id: 1305, name: "Kids & Family"),
        PodcastGenre(id: 1502, name: "Leisure"),
        PodcastGenre(id: 1511, name: "Government"),
    ]

    public static func search(
        term: String, genreId: Int = 0, transport: PodcastDirectoryTransport = URLSessionPodcastTransport()
    ) async throws -> [PodcastShow] {
        let trimmed = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let genre = genreId > 0 ? "&genreId=\(genreId)" : ""
        let body = try await transport.text(
            "\(base)/search?media=podcast&entity=podcast&term=\(encoded(trimmed))\(genre)&limit=\(searchLimit)"
        )
        return decode(body)
    }

    /// A category browse: the genre's own name, narrowed by its id.
    public static func byGenre(
        _ genre: PodcastGenre, transport: PodcastDirectoryTransport = URLSessionPodcastTransport()
    ) async throws -> [PodcastShow] {
        try await search(term: genre.name, genreId: genre.id, transport: transport)
    }

    /// Apple's top shows for `country`, the full chart and no further, in rank
    /// order. A chart carries no feed URLs; resolve with `lookup`.
    public static func chartIds(
        country: String = "us", genreId: Int? = nil,
        transport: PodcastDirectoryTransport = URLSessionPodcastTransport()
    ) async throws -> [String] {
        let genre = genreId.map { "/genre=\($0)" } ?? ""
        let body = try await transport.text(
            "\(base)/\(country.lowercased())/rss/toppodcasts/limit=\(chartLimit)\(genre)/json"
        )
        return try decodeCharts(body)
    }

    /// Resolve Apple ids to full shows, feed URL included, in one request,
    /// preserving the asked order.
    public static func lookup(
        ids: [String], transport: PodcastDirectoryTransport = URLSessionPodcastTransport()
    ) async throws -> [PodcastShow] {
        guard !ids.isEmpty else { return [] }
        let body = try await transport.text("\(base)/lookup?id=\(ids.joined(separator: ","))&entity=podcast")
        let found = Dictionary(decode(body).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return ids.compactMap { found[$0] }
    }

    /// A show for a feed URL the user typed. Apple is asked first so the entry
    /// gets real artwork and a genre; failing that the feed speaks for itself.
    public static func byFeedUrl(
        _ feedUrl: String, transport: PodcastDirectoryTransport = URLSessionPodcastTransport()
    ) async -> PodcastShow? {
        let clean = feedUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return nil }
        if let body = try? await transport.text(
            "\(base)/search?media=podcast&entity=podcast&term=\(encoded(clean))&limit=5"
        ), let match = decode(body).first(where: { $0.feedUrl.caseInsensitiveCompare(clean) == .orderedSame }) {
            return match
        }
        return PodcastShow(id: feedId(clean), title: clean, feedUrl: clean)
    }

    /// Stable synthetic id for a feed Apple does not list.
    public static func feedId(_ feedUrl: String) -> String {
        var hash = UInt32(truncatingIfNeeded: 5381)
        for byte in feedUrl.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().utf8 {
            hash = (hash &* 33) &+ UInt32(byte)
        }
        return "feed:\(hash)"
    }

    private static func encoded(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? value
    }

    private static func decode(_ body: String) -> [PodcastShow] {
        guard let data = body.data(using: .utf8),
              let response = try? JSONDecoder().decode(ItunesResponse.self, from: data)
        else { return [] }
        var seen = Set<String>()
        return response.results.compactMap { show -> PodcastShow? in
            guard !show.feedUrl.isEmpty, seen.insert(show.feedUrl).inserted else { return nil }
            return PodcastShow(
                id: String(show.collectionId),
                title: show.collectionName.trimmingCharacters(in: .whitespaces).isEmpty
                    ? "untitled show" : show.collectionName.trimmingCharacters(in: .whitespaces),
                feedUrl: show.feedUrl,
                author: show.artistName.trimmingCharacters(in: .whitespaces),
                artwork: show.artworkUrl600.isEmpty ? show.artworkUrl100 : show.artworkUrl600,
                genre: show.primaryGenreName,
                episodeCount: show.trackCount
            )
        }
    }

    private static func decodeCharts(_ body: String) throws -> [String] {
        struct Response: Decodable { let feed: Feed }
        struct Feed: Decodable { let entry: [Entry] }
        struct Entry: Decodable { let id: EntryId }
        struct EntryId: Decodable { let attributes: Attributes }
        struct Attributes: Decodable {
            let imId: String
            enum CodingKeys: String, CodingKey { case imId = "im:id" }
        }
        guard let data = body.data(using: .utf8) else { return [] }
        let response = try JSONDecoder().decode(Response.self, from: data)
        return response.feed.entry.map(\.id.attributes.imId).filter { !$0.isEmpty }
    }
}

/// Fetches one directory response as text; injectable so tests never hit Apple.
public protocol PodcastDirectoryTransport: Sendable {
    func text(_ urlString: String) async throws -> String
}

public struct URLSessionPodcastTransport: PodcastDirectoryTransport {
    public init() {}

    public func text(_ urlString: String) async throws -> String {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
    }
}

private struct ItunesResponse: Decodable {
    let results: [ItunesShow]
    struct ItunesShow: Decodable {
        let collectionId: Int64
        let collectionName: String
        let artistName: String
        let feedUrl: String
        let artworkUrl600: String
        let artworkUrl100: String
        let primaryGenreName: String
        let trackCount: Int

        enum CodingKeys: String, CodingKey {
            case collectionId, collectionName, artistName, feedUrl
            case artworkUrl600, artworkUrl100, primaryGenreName, trackCount
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            collectionId = (try? c.decode(Int64.self, forKey: .collectionId)) ?? 0
            collectionName = (try? c.decode(String.self, forKey: .collectionName)) ?? ""
            artistName = (try? c.decode(String.self, forKey: .artistName)) ?? ""
            feedUrl = (try? c.decode(String.self, forKey: .feedUrl)) ?? ""
            artworkUrl600 = (try? c.decode(String.self, forKey: .artworkUrl600)) ?? ""
            artworkUrl100 = (try? c.decode(String.self, forKey: .artworkUrl100)) ?? ""
            primaryGenreName = (try? c.decode(String.self, forKey: .primaryGenreName)) ?? ""
            trackCount = (try? c.decode(Int.self, forKey: .trackCount)) ?? 0
        }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        results = (try? c.decode([ItunesShow].self, forKey: .results)) ?? []
    }

    enum CodingKeys: String, CodingKey { case results }
}
