import Foundation

/// How the directory list is currently ordered or filtered.
public enum DirectoryQuery: Equatable, Sendable {
    case topVoted
    case trending
    case search(String)
    case tag(String)
    case country(code: String, name: String)

    public var label: String {
        switch self {
        case .topVoted: "top"
        case .trending: "trending"
        case .search(let text): "\"\(text)\""
        case .tag(let tag): "#\(tag)"
        case .country(_, let name): name.lowercased()
        }
    }
}

public struct DirectoryStats: Codable, Equatable, Sendable {
    public var stations = 0
    public var stationsBroken = 0
    public var tags = 0
    public var countries = 0
    public var languages = 0
    public var clicksLastHour = 0
    public var clicksLastDay = 0

    public var playable: Int { max(0, stations - stationsBroken) }

    enum CodingKeys: String, CodingKey {
        case stations
        case stationsBroken = "stations_broken"
        case tags, countries, languages
        case clicksLastHour = "clicks_last_hour"
        case clicksLastDay = "clicks_last_day"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        stations = try container.decodeIfPresent(Int.self, forKey: .stations) ?? 0
        stationsBroken = try container.decodeIfPresent(Int.self, forKey: .stationsBroken) ?? 0
        tags = try container.decodeIfPresent(Int.self, forKey: .tags) ?? 0
        countries = try container.decodeIfPresent(Int.self, forKey: .countries) ?? 0
        languages = try container.decodeIfPresent(Int.self, forKey: .languages) ?? 0
        clicksLastHour = try container.decodeIfPresent(Int.self, forKey: .clicksLastHour) ?? 0
        clicksLastDay = try container.decodeIfPresent(Int.self, forKey: .clicksLastDay) ?? 0
    }

    public init() {}
}

public struct NameCount: Codable, Equatable, Sendable {
    public var name = ""
    public var stationcount = 0

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        stationcount = try container.decodeIfPresent(Int.self, forKey: .stationcount) ?? 0
    }

    public init() {}

    enum CodingKeys: String, CodingKey {
        case name, stationcount
    }
}

public struct CountryCount: Codable, Equatable, Sendable {
    public var name = ""
    public var iso3166 = ""
    public var stationcount = 0

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        iso3166 = try container.decodeIfPresent(String.self, forKey: .iso3166) ?? ""
        stationcount = try container.decodeIfPresent(Int.self, forKey: .stationcount) ?? 0
    }

    public init() {}

    enum CodingKeys: String, CodingKey {
        case name
        case iso3166 = "iso_3166_1"
        case stationcount
    }
}

/// What the client needs from the network. Injected so mirror failover can be
/// tested without touching radio-browser.
public protocol RadioBrowserTransport: Sendable {
    func text(_ url: URL) async throws -> String
    func probe(_ url: URL) async -> Bool
    func fireAndForget(_ url: URL) async
}

public struct URLSessionTransport: RadioBrowserTransport {
    public init() {}

    public func text(_ url: URL) async throws -> String {
        var request = URLRequest(url: url)
        request.setValue(RadioBrowserClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return String(decoding: data, as: UTF8.self)
    }

    public func probe(_ url: URL) async -> Bool {
        var request = URLRequest(url: url)
        request.setValue(RadioBrowserClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 2
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }

    public func fireAndForget(_ url: URL) async {
        var request = URLRequest(url: url)
        request.setValue(RadioBrowserClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 5
        _ = try? await URLSession.shared.data(for: request)
    }
}

/// Where the last live mirror is remembered for the next launch.
public protocol RadioBrowserMirrorStore: Sendable {
    func load() -> String?
    func save(_ base: String)
}

public struct UserDefaultsMirrorStore: RadioBrowserMirrorStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "rb_mirror"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func load() -> String? {
        defaults.string(forKey: key)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .takeIf { $0.hasPrefix("https://") && $0.contains("api.radio-browser.info") }
    }

    public func save(_ base: String) {
        defaults.set(base, forKey: key)
    }
}

private extension String {
    func takeIf(_ predicate: (String) -> Bool) -> String? {
        predicate(self) ? self : nil
    }
}

/// The 50k+ community directory at radio-browser.info: a pool of mirrors
/// behind `all.api.radio-browser.info`, resolved once, pinned when one answers,
/// and failed over on error.
public actor RadioBrowserClient {
    public static let userAgent = "cliamp-mobile/0.0.1 (+https://cliamp.stream)"
    public static let fallbackMirrors = [
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
    ]

    public typealias MirrorDiscovery = @Sendable () async -> [String]

    private let transport: RadioBrowserTransport
    private let mirrorStore: RadioBrowserMirrorStore
    private let discovery: MirrorDiscovery
    private var mirrors: [String]
    private var dead: Set<String> = []
    private var discoveryTask: Task<[String], Never>?

    public init(
        transport: RadioBrowserTransport = URLSessionTransport(),
        mirrorStore: RadioBrowserMirrorStore = UserDefaultsMirrorStore(),
        discovery: MirrorDiscovery? = nil
    ) {
        self.transport = transport
        self.mirrorStore = mirrorStore
        self.discovery = discovery ?? { await DNSMirrors.discover() }
        mirrors = mirrorStore.load().map { [$0] } ?? []
    }

    public func stats() async -> DirectoryStats? {
        guard let body = try? await withRetries({ try await self.get("/json/stats") }) else {
            return nil
        }
        return try? JSONDecoder().decode(DirectoryStats.self, from: Data(body.utf8))
    }

    public func topVoted(offset: Int, limit: Int = 60) async throws -> [Station] {
        try await page(
            query: [
                URLQueryItem(name: "order", value: "votes"),
                URLQueryItem(name: "reverse", value: "true"),
            ],
            offset: offset, limit: limit
        )
    }

    public func trending(offset: Int, limit: Int = 60) async throws -> [Station] {
        try await page(
            query: [
                URLQueryItem(name: "order", value: "clicktrend"),
                URLQueryItem(name: "reverse", value: "true"),
            ],
            offset: offset, limit: limit
        )
    }

    public func searchByName(_ text: String, offset: Int = 0, limit: Int = 60) async throws -> [Station] {
        try await page(
            query: [
                URLQueryItem(name: "name", value: text),
                URLQueryItem(name: "order", value: "votes"),
                URLQueryItem(name: "reverse", value: "true"),
            ],
            offset: offset, limit: limit
        )
    }

    public func byTag(_ tag: String, offset: Int = 0, limit: Int = 60) async throws -> [Station] {
        try await page(
            query: [
                URLQueryItem(name: "tag", value: tag),
                URLQueryItem(name: "order", value: "votes"),
                URLQueryItem(name: "reverse", value: "true"),
            ],
            offset: offset, limit: limit
        )
    }

    public func byCountryCode(_ code: String, offset: Int = 0, limit: Int = 60) async throws -> [Station] {
        try await page(
            query: [
                URLQueryItem(name: "countrycode", value: code.uppercased()),
                URLQueryItem(name: "order", value: "votes"),
                URLQueryItem(name: "reverse", value: "true"),
            ],
            offset: offset, limit: limit
        )
    }

    public func stations(for query: DirectoryQuery, offset: Int, limit: Int = 60) async throws -> [Station] {
        switch query {
        case .topVoted: try await topVoted(offset: offset, limit: limit)
        case .trending: try await trending(offset: offset, limit: limit)
        case .search(let text): try await searchByName(text, offset: offset, limit: limit)
        case .tag(let tag): try await byTag(tag, offset: offset, limit: limit)
        case .country(let code, _): try await byCountryCode(code, offset: offset, limit: limit)
        }
    }

    public func topTags(limit: Int = 60) async -> [NameCount] {
        guard let body = try? await withRetries({
            try await self.get("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=\(limit)")
        }) else { return [] }
        return (try? JSONDecoder().decode([NameCount].self, from: Data(body.utf8))) ?? []
    }

    public func topCountries(limit: Int? = nil) async -> [CountryCount] {
        let suffix = limit.map { "&limit=\($0)" } ?? ""
        guard let body = try? await withRetries({
            try await self.get("/json/countries?order=stationcount&reverse=true\(suffix)")
        }) else { return [] }
        return (try? JSONDecoder().decode([CountryCount].self, from: Data(body.utf8))) ?? []
    }

    /// The directory ranks stations by clicks, so skipping this would quietly
    /// free-ride on everyone else's votes.
    public func reportClick(uuid: String) async {
        guard !uuid.isEmpty else { return }
        if mirrors.isEmpty { await discover() }
        guard let base = mirrors.first, let url = URL(string: "\(base)/json/url/\(uuid)") else { return }
        await transport.fireAndForget(url)
    }

    private func page(
        query: [URLQueryItem],
        offset: Int,
        limit: Int
    ) async throws -> [Station] {
        var items = query
        items.append(URLQueryItem(name: "hidebroken", value: "true"))
        items.append(URLQueryItem(name: "limit", value: String(limit)))
        items.append(URLQueryItem(name: "offset", value: String(offset)))
        var components = URLComponents()
        components.path = "/json/stations/search"
        components.queryItems = items
        let path = components.string ?? "/json/stations/search"
        // Decode inside the retry so a truncated 200 is retried like any
        // other failed fetch, the way the Android repository does.
        return try await withRetries {
            let body = try await self.get(path)
            return try Self.decode(body)
        }
    }

    private func get(_ path: String) async throws -> String {
        if mirrors.isEmpty { await discover() }
        var lastError: Error?
        for pass in 0..<2 {
            for base in mirrors where !dead.contains(base) {
                guard let url = URL(string: base + path) else { continue }
                do {
                    let body = try await transport.text(url)
                    mirrorStore.save(base)
                    return body
                } catch {
                    lastError = error
                    dead.insert(base)
                }
            }
            // The first pass may have been a single pinned mirror that went
            // stale since the last session; probe the field once before giving up.
            if pass == 0 { await discover() }
        }
        throw lastError ?? URLError(.cannotConnectToHost)
    }

    private func discover() async {
        // Reentrant callers share one sweep instead of racing it: without
        // this, overlapping startup requests each probed the field and a
        // later completion could overwrite newer mirror state.
        if let inFlight = discoveryTask {
            _ = await inFlight.value
            return
        }
        let pinned = mirrorStore.load()
        let discoverFn = discovery
        let probeTransport = transport
        let task = Task { () -> [String] in
            var found = await discoverFn()
            if found.isEmpty { found = Self.fallbackMirrors }
            var candidates: [String] = []
            if let pinned { candidates.append(pinned) }
            candidates.append(contentsOf: found)
            var reachable: [String] = []
            await withTaskGroup(of: (String, Bool).self) { group in
                for candidate in Set(candidates) {
                    group.addTask {
                        (candidate, await probeTransport.probe(URL(string: candidate + "/json/stats")!))
                    }
                }
                for await (candidate, alive) in group where alive {
                    reachable.append(candidate)
                }
            }
            return reachable
        }
        discoveryTask = task
        let reachable = await task.value
        discoveryTask = nil
        if reachable.isEmpty {
            mirrors = []
        } else {
            var ordered: [String] = []
            if let pinned, reachable.contains(pinned) { ordered.append(pinned) }
            ordered.append(contentsOf: reachable.shuffled())
            var seen = Set<String>()
            mirrors = ordered.filter { seen.insert($0).inserted }
        }
        dead = []
    }

    private func withRetries<T>(_ block: () async throws -> T) async throws -> T {
        var attempt = 0
        while true {
            attempt += 1
            do {
                return try await block()
            } catch {
                if attempt >= 3 { throw error }
                try? await Task.sleep(nanoseconds: UInt64(300_000_000 * attempt))
            }
        }
    }

    private struct RbStation: Decodable {
        var stationuuid = ""
        var name = ""
        var url = ""
        var urlResolved = ""
        var homepage = ""
        var favicon = ""
        var tags = ""
        var country = ""
        var countrycode = ""
        var language = ""
        var votes = 0
        var codec = ""
        var bitrate = 0
        var hls = 0
        var clickcount = 0

        enum CodingKeys: String, CodingKey {
            case stationuuid, name, url, homepage, favicon, tags, country, countrycode
            case language, votes, codec, bitrate, hls, clickcount
            case urlResolved = "url_resolved"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            stationuuid = try container.decodeIfPresent(String.self, forKey: .stationuuid) ?? ""
            name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
            url = try container.decodeIfPresent(String.self, forKey: .url) ?? ""
            urlResolved = try container.decodeIfPresent(String.self, forKey: .urlResolved) ?? ""
            homepage = try container.decodeIfPresent(String.self, forKey: .homepage) ?? ""
            favicon = try container.decodeIfPresent(String.self, forKey: .favicon) ?? ""
            tags = try container.decodeIfPresent(String.self, forKey: .tags) ?? ""
            country = try container.decodeIfPresent(String.self, forKey: .country) ?? ""
            countrycode = try container.decodeIfPresent(String.self, forKey: .countrycode) ?? ""
            language = try container.decodeIfPresent(String.self, forKey: .language) ?? ""
            votes = try container.decodeIfPresent(Int.self, forKey: .votes) ?? 0
            codec = try container.decodeIfPresent(String.self, forKey: .codec) ?? ""
            bitrate = try container.decodeIfPresent(Int.self, forKey: .bitrate) ?? 0
            hls = try container.decodeIfPresent(Int.self, forKey: .hls) ?? 0
            clickcount = try container.decodeIfPresent(Int.self, forKey: .clickcount) ?? 0
        }

        func toStation() -> Station {
            Station(
                id: "rb:\(stationuuid)",
                name: name.trimmingCharacters(in: .whitespaces).isEmpty
                    ? "unnamed station"
                    : name.trimmingCharacters(in: .whitespaces),
                url: urlResolved.isEmpty ? url : urlResolved,
                source: .directory,
                tags: tags,
                country: country,
                countryCode: countrycode,
                codec: codec,
                bitrate: bitrate,
                votes: votes,
                homepage: homepage,
                favicon: favicon,
                uuid: stationuuid
            )
        }
    }

    /// The source HLS filter: accept unflagged entries, or entries whose
    /// resolved URL actually ends in `.m3u8`.
    static func decode(_ body: String) throws -> [Station] {
        let rows = try JSONDecoder().decode([RbStation].self, from: Data(body.utf8))
        var seen = Set<String>()
        return rows.compactMap { row in
            guard !row.urlResolved.isEmpty || !row.url.isEmpty else { return nil }
            guard row.hls == 0 || row.urlResolved.hasSuffix(".m3u8") else { return nil }
            let station = row.toStation()
            guard seen.insert(station.url).inserted else { return nil }
            return station
        }
    }
}

/// Resolves `all.api.radio-browser.info` to the mirror hostnames behind it.
public enum DNSMirrors {
    public static func discover() async -> [String] {
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async {
                continuation.resume(returning: lookup())
            }
        }
    }

    private static func lookup() -> [String] {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo("all.api.radio-browser.info", "443", &hints, &result) == 0,
              let first = result
        else { return [] }
        defer { freeaddrinfo(first) }
        var hosts = Set<String>()
        var node: UnsafeMutablePointer<addrinfo>? = first
        while let current = node {
            if let address = current.pointee.ai_addr {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(
                    address, current.pointee.ai_addrlen,
                    &host, socklen_t(host.count), nil, 0, NI_NAMEREQD
                ) == 0 {
                    let bytes = host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }
                    let name = String(decoding: bytes, as: UTF8.self)
                    if name.hasSuffix("api.radio-browser.info") {
                        hosts.insert("https://\(name)")
                    }
                }
            }
            node = current.pointee.ai_next
        }
        return Array(hosts)
    }
}
