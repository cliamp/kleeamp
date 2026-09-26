import CliampCore
import Foundation
import Observation
import os

/// A single fuzzy result from any source, labelled with where it came from.
/// Ported from Android's `SearchResults.kt`: keys, origins and haystacks are
/// the same strings, because they decide ranking and deduplication.
enum SearchHit: Identifiable, Hashable {
    case song(Station)
    case station(Station)
    case favorite(Station)
    case episode(Station, showTitle: String)
    case show(PodcastShow, subscribed: Bool)

    var id: String { key }

    var playable: Station? {
        switch self {
        case .song(let station), .station(let station), .favorite(let station): station
        case .episode(let station, _): station
        case .show: nil
        }
    }

    var key: String {
        switch self {
        case .song(let station): "song:\(station.id)"
        case .station(let station): "station:\(station.url)"
        case .favorite(let station): "fav:\(station.url)"
        case .episode(let station, _): "episode:\(station.id)"
        case .show(let show, _): "show:\(show.feedUrl)"
        }
    }

    var origin: String {
        switch self {
        case .song: "local"
        case .station: "radio"
        case .favorite: "fav"
        case .episode: "episode"
        case .show(_, let subscribed): subscribed ? "subscribed" : "podcast"
        }
    }

    var haystack: String {
        switch self {
        case .song(let station):
            [station.name, station.artist].filter { !$0.isEmpty }.joined(separator: " ")
        case .station(let station), .favorite(let station):
            ([station.name] + station.tagList).joined(separator: " ")
        case .episode(let station, let showTitle):
            [station.name, showTitle].filter { !$0.isEmpty }.joined(separator: " ")
        case .show(let show, _):
            [show.title, show.author].filter { !$0.isEmpty }.joined(separator: " ")
        }
    }
}

/// The app-wide fuzzy finder's state: one query spanning whatever catalogs are
/// available, with the Android `SearchScope` chips. Resident matches publish
/// before any network answer, and a later remote page merges in.
@MainActor
@Observable
final class SearchModel {
    enum Scope: String, CaseIterable, Identifiable {
        case all
        case local
        case radio
        case podcasts
        case tags
        case providers

        var id: String { rawValue }

        func includes(_ hit: SearchHit) -> Bool {
            switch self {
            case .all: true
            case .local:
                if case .song = hit { true } else if case .favorite = hit { true } else { false }
            case .radio:
                if case .station = hit { true } else { false }
            case .podcasts:
                switch hit {
                case .show, .episode: true
                default: false
                }
            case .tags:
                // Radio tag destinations arrive with the search phase; the
                // chip yields nothing until then.
                false
            case .providers: false
            }
        }
    }

    var query = "" {
        didSet { schedule() }
    }

    private(set) var scope: Scope = .all
    /// The filtered list the screen renders.
    private(set) var hits: [SearchHit] = []
    /// Everything the query found, before the scope filter, so the empty-state
    /// claim can be scoped correctly.
    private(set) var allHits: [SearchHit] = []
    private(set) var total = 0
    private(set) var term = ""
    private(set) var searching = false

    /// Favourites and history live in AppState; injected so this model stays
    /// store-free.
    var favoritesProvider: (() -> [Station])?
    var historyProvider: (() -> [Station])?

    private let podcasts: PodcastsModel
    private let customStore = CustomStationStore()
    private let radioClient = RadioBrowserClient()
    private let log = Logger(subsystem: "stream.cliamp.mobile", category: "search")
    private var remoteShows: [PodcastShow] = []
    private var remoteStations: [Station] = []
    private var remoteTerm = ""
    private var generation = 0
    private var queryTask: Task<Void, Never>?

    init(podcasts: PodcastsModel) {
        self.podcasts = podcasts
    }

    func start() {}

    /// Resident collections changed (favourites, subscriptions, history):
    /// recompute without a new query.
    func refresh() {
        schedule()
    }

    func setScope(_ scope: Scope) {
        self.scope = scope
        publish()
    }

    func clear() {
        generation += 1
        queryTask?.cancel()
        query = ""
        term = ""
        remoteShows = []
        remoteStations = []
        remoteTerm = ""
        publish()
    }

    /// The keyboard's search action; also a retry for a failed remote page.
    func submit() {
        schedule(immediate: true)
    }

    private func schedule(immediate: Bool = false) {
        queryTask?.cancel()
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        generation += 1
        let token = generation
        guard !text.isEmpty else {
            term = ""
            searching = false
            publish()
            return
        }
        term = text
        searching = true
        // Resident hits answer on the next runloop turn, before any await.
        publish()
        queryTask = Task { [weak self] in
            if !immediate {
                try? await Task.sleep(for: .milliseconds(250))
            }
            guard let self, !Task.isCancelled, token == self.generation else { return }
            self.publish()
            await self.fetchRemote(term: text, token: token)
        }
    }

    private func fetchRemote(term text: String, token: Int) async {
        async let shows = try? PodcastDirectory.search(term: text)
        async let stations = try? radioClient.stations(for: .search(text), offset: 0, limit: 60)
        let (showPage, stationPage) = await (shows, stations)
        guard token == generation else { return }
        remoteShows = showPage ?? []
        remoteStations = stationPage ?? []
        remoteTerm = text
        searching = false
        publish()
        log.info("search \(text, privacy: .public) total \(self.total, privacy: .public)")
    }

    private func publish() {
        allHits = ranked(term: term)
        hits = allHits.filter { scope.includes($0) }
        total = allHits.count
    }

    private var residentRadio: [Station] {
        CliampRadio.builtin + customStore.load()
    }

    private func ranked(term text: String) -> [SearchHit] {
        if text.isEmpty {
            // Idle suggestions, in the Android order: tags, subscribed shows,
            // then the radio wall, deduped.
            var idle: [SearchHit] = []
            var seenFeeds = Set<String>()
            for show in podcasts.subscriptions.prefix(8) where seenFeeds.insert(show.feedUrl).inserted {
                idle.append(.show(show, subscribed: true))
            }
            idle += residentRadio.prefix(20).map { .station($0) }
            var seen = Set<String>()
            return idle.filter { seen.insert($0.key).inserted }
        }

        var scored: [(score: Int, origin: String, hit: SearchHit)] = []
        func consider(_ hit: SearchHit) {
            let score = Fuzzy.score(query: text, haystack: hit.haystack)
            if score != Int.max { scored.append((score, hit.origin, hit)) }
        }

        // A url can sit in several buckets (a favourite that is also history);
        // keep the most specific label.
        var byUrl: [String: SearchHit] = [:]
        func remember(_ hit: SearchHit) {
            guard let station = hit.playable else { return }
            if byUrl[station.url] == nil { byUrl[station.url] = hit }
        }
        (favoritesProvider?() ?? []).forEach { remember(.favorite($0)) }
        (historyProvider?() ?? []).forEach { remember(.favorite($0)) }
        byUrl.values.forEach(consider)

        residentRadio.forEach { consider(.station($0)) }
        if remoteTerm == text { remoteStations.forEach { consider(.station($0)) } }

        // Deduped by feed; a subscribed show ranks ahead of the same show in
        // the directory page.
        var seenFeeds = Set<String>()
        let shows = podcasts.subscriptions + (remoteTerm == text ? remoteShows : [])
        for show in shows.sorted(by: {
            podcasts.isSubscribed(feedUrl: $1.feedUrl) && !podcasts.isSubscribed(feedUrl: $0.feedUrl)
        }) where seenFeeds.insert(show.feedUrl).inserted {
            consider(.show(show, subscribed: podcasts.isSubscribed(feedUrl: show.feedUrl)))
        }

        scored.sort { ($0.score, $0.origin) < ($1.score, $1.origin) }

        // Subscribed episodes answer "which installment" once shows and
        // stations have spoken, so they rank after everything else.
        let knownUrls = Set(byUrl.keys)
        var episodes: [(score: Int, hit: SearchHit)] = []
        for (show, episode) in podcasts.store.subscribedEpisodes() {
            let station = episode.station(show: show)
            if knownUrls.contains(station.url) { continue }
            let score = Fuzzy.score(
                query: text,
                haystack: "\(episode.title) \(episode.description) \(show.title)"
            )
            if score != Int.max {
                episodes.append((score, .episode(station, showTitle: show.title)))
            }
        }
        episodes.sort { $0.score < $1.score }

        var seen = Set<String>()
        return (scored.map(\.hit) + episodes.map(\.hit))
            .filter { seen.insert($0.key).inserted }
            .prefix(240)
            .map { $0 }
    }
}
