import CliampCore
import Foundation

/// One process-wide stations model: the Library's add-songs picker needs the
/// directory pages loaded in the Stations tab, and two independent models
/// would page the same directory twice.
@MainActor
final class StationsServices {
    static let shared = StationsServices()

    let model = StationsModel()

    private init() {}
}

/// The Stations tab's state: cliamp's own channels, the custom list, and the
/// paged community directory, kept side by side the way the Android repository
/// keeps them.
@MainActor
@Observable
final class StationsModel {
    private(set) var cliamp: [Station] = []
    private(set) var cliampError: String?
    private(set) var custom: [Station] = []
    private(set) var directory: [Station] = []
    private(set) var directoryQuery: DirectoryQuery = .topVoted
    private(set) var directoryLoading = false
    private(set) var directoryExhausted = false
    private(set) var directoryError: String?
    private(set) var directoryStats: DirectoryStats?
    private(set) var tags: [NameCount] = []
    private(set) var countries: [CountryCount] = []

    var cliampGrid: Bool { didSet { defaults.set(cliampGrid, forKey: Keys.cliampGrid) } }
    var directoryGrid: Bool { didSet { defaults.set(directoryGrid, forKey: Keys.directoryGrid) } }
    var customGrid: Bool { didSet { defaults.set(customGrid, forKey: Keys.customGrid) } }

    private let client: RadioBrowserClient
    private let customStore: CustomStationStore
    private let defaults: UserDefaults
    private var started = false
    private var generation = 0
    private var serverOffset = 0

    private enum Keys {
        static let cliampGrid = "cliamp_grid"
        static let directoryGrid = "directory_grid"
        static let customGrid = "custom_grid"
    }

    init(
        client: RadioBrowserClient = RadioBrowserClient(),
        customStore: CustomStationStore = CustomStationStore(),
        defaults: UserDefaults = .standard
    ) {
        self.client = client
        self.customStore = customStore
        self.defaults = defaults
        cliampGrid = defaults.bool(forKey: Keys.cliampGrid)
        directoryGrid = defaults.bool(forKey: Keys.directoryGrid)
        customGrid = defaults.bool(forKey: Keys.customGrid)
    }

    var countryName: String? {
        if case .country(_, let name) = directoryQuery { return name }
        return nil
    }

    func start() async {
        guard !started else { return }
        started = true
        custom = customStore.load()
        // The directory starts first so a user filter tap during the other
        // startup fetches is a later generation and cannot be overwritten.
        loadDirectory(.topVoted, reset: true)
        async let cliampResult = CliampRadio.fetchStations()
        async let statsResult = client.stats()
        async let tagsResult = client.topTags(limit: 24)
        async let countriesResult = client.topCountries()
        cliamp = await cliampResult
        directoryStats = await statsResult
        tags = await tagsResult
        countries = await countriesResult
    }

    func loadDirectory(_ query: DirectoryQuery, reset: Bool) {
        if !reset, directoryLoading || directoryExhausted || directoryError != nil { return }
        if reset {
            generation += 1
            directoryQuery = query
            directoryExhausted = false
            directoryError = nil
        }
        let token = generation
        // The offset counts server records, not displayed rows: a page full of
        // duplicates must still advance the cursor.
        let offset = reset ? 0 : serverOffset
        directoryLoading = true
        Task {
            do {
                let page = try await client.stations(for: query, offset: offset, limit: 60)
                guard token == generation else { return }
                // A reset never empties what is on screen: the old rows stay
                // put while the new query loads, then the live page replaces
                // them. Only an empty page is the end of the catalogue.
                let base = reset ? [] : directory
                var seen = Set(base.map(\.url))
                directory = base + page.filter { seen.insert($0.url).inserted }
                serverOffset = reset ? page.count : serverOffset + page.count
                directoryExhausted = page.isEmpty
                directoryError = nil
            } catch {
                guard token == generation else { return }
                directoryError = error.localizedDescription
            }
            if token == generation {
                directoryLoading = false
            }
        }
    }

    /// Fetches the next page when the last visible row comes into view.
    func nextPageIfNeeded(current: Station) {
        guard !directoryLoading, !directoryExhausted, directoryError == nil else { return }
        guard let index = directory.firstIndex(of: current), index >= directory.count - 8 else { return }
        loadDirectory(directoryQuery, reset: false)
    }

    @discardableResult
    func addCustom(name: String, url: String) -> Bool {
        guard let station = Station.custom(name: name, url: url) else { return false }
        customStore.add(station)
        custom = customStore.load()
        return true
    }

    func removeCustom(_ station: Station) {
        customStore.remove(url: station.url)
        custom = customStore.load()
    }

    /// The directory ranks stations by clicks, so playing one reports it.
    func reportPlay(_ station: Station) {
        guard station.source == .directory, !station.uuid.isEmpty else { return }
        Task { await client.reportClick(uuid: station.uuid) }
    }
}
