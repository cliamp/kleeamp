import CliampCore
import Foundation

/// The Library tab's model: the managed local folder (DEC-02), the playlist
/// store and the persisted sort choices. Smart lists are derived from live app
/// state — favourites, history and downloads — so their rows always agree with
/// the rest of the app.
@MainActor
@Observable
final class LibraryModel {
    /// The four pinned smart lists, in Android's order (recency first).
    enum SmartKind: String, CaseIterable, Identifiable, Sendable, Hashable {
        case recentlyPlayed
        case downloads
        case favorites
        case localSongs

        var id: String { rawValue }

        var label: String {
            switch self {
            case .recentlyPlayed: "recently played"
            case .downloads: "downloads"
            case .favorites: "favorites"
            case .localSongs: "local songs"
            }
        }
    }

    /// The favourites sub-tabs: local songs, radio stations and podcasts mix
    /// in one list, so each type can be viewed alone.
    enum FavScope: String, CaseIterable, Identifiable {
        case all
        case local
        case stations
        case pods

        var id: String { rawValue }

        var label: String {
            switch self {
            case .all: "all"
            case .local: "local"
            case .stations: "stations"
            case .pods: "podcasts"
            }
        }
    }

    let root: URL
    private let library: LocalLibrary
    private let store: PlaylistStore
    private let preferences: LibraryPreferences
    private let customStore = CustomStationStore()

    /// Indexed songs plus the raw index they came from, so folder grouping
    /// keeps relative paths.
    private(set) var indexed: [LocalSong] = []
    private(set) var songs: [Station] = []
    private(set) var loading = false
    private(set) var error: String?
    private(set) var playlists: [Playlist] = []
    private(set) var localSort: StationSort
    private(set) var downloadSort: StationSort
    private(set) var scanned = false
    var favScope: FavScope = .all
    /// Observable mirror of the persisted per-playlist sorts: the preference
    /// store is not observable, so a chip tap must touch this map to redraw.
    private(set) var playlistSorts: [String: StationSort]
    /// Invalidates a scan that finished after a newer refresh began.
    private var scanGeneration = 0
    /// The last detached cache write, so writes land in refresh order even
    /// when their scans finish out of order.
    private var pendingSave: Task<Void, Never>?

    /// Android's detail sort key for the downloads list; local songs share the
    /// library's key.
    static let downloadsSortKey = "downloads"

    init(
        store: PlaylistStore? = nil,
        preferences: LibraryPreferences = LibraryPreferences()
    ) {
        let library = LocalLibrary.documentsLibrary()
        self.library = library
        self.root = library.root
        self.store = store ?? .appStore()
        self.preferences = preferences
        self.localSort = preferences.sort(for: LibraryPreferences.localSongs)
        self.downloadSort = preferences.sort(for: Self.downloadsSortKey)
        self.playlistSorts = preferences.allSorts()
        self.playlists = self.store.all()
    }

    // MARK: scanning

    /// The cached index paints first; the scan then replaces it, so a warm
    /// launch never shows an empty library while the disk is walked. Cache
    /// reads and writes run off the main actor; a generation counter makes a
    /// deletion-triggered scan win over any older scan still in flight.
    func start() async {
        scanGeneration += 1
        let token = scanGeneration
        let library = self.library
        if let cached = await Task.detached(priority: .userInitiated, operation: {
            library.cachedSongs()
        }).value, token == scanGeneration {
            apply(cached)
        }
        loading = songs.isEmpty
        await refresh()
        reloadPlaylists()
    }

    func refresh() async {
        scanGeneration += 1
        let token = scanGeneration
        if songs.isEmpty { loading = true }
        error = nil
        let library = self.library
        let found = await library.scan()
        guard token == scanGeneration else { return }
        // Chain the write behind the previous one: a slow older save must not
        // land after a newer snapshot.
        let previous = pendingSave
        let save = Task.detached(priority: .utility) {
            await previous?.value
            library.save(found)
        }
        pendingSave = save
        await save.value
        // The save suspends too: a newer refresh or deletion may have landed
        // while it ran, and its result must win.
        guard token == scanGeneration else { return }
        apply(found)
        loading = false
        scanned = true
    }

    private func apply(_ indexed: [LocalSong]) {
        self.indexed = indexed
        songs = indexed.map { $0.station(in: root) }
    }

    // MARK: sorting

    func sort(for kind: SmartKind) -> StationSort {
        kind == .downloads ? downloadSort : localSort
    }

    func setSort(_ sort: StationSort, for kind: SmartKind) {
        if kind == .downloads {
            downloadSort = sort
            preferences.setSort(sort, for: Self.downloadsSortKey)
        } else {
            localSort = sort
            preferences.setSort(sort, for: LibraryPreferences.localSongs)
        }
    }

    // MARK: smart lists

    /// Favourites narrowed to the active type tab.
    func favorites(_ all: [Station]) -> [Station] {
        switch favScope {
        case .all: all
        case .local: all.filter { $0.source == .local }
        case .stations: all.filter { $0.source != .local && $0.source != .podcast }
        case .pods: all.filter { $0.source == .podcast }
        }
    }

    /// The members of one smart list, in the order it plays: recently played
    /// keeps its time order, the on-device lists follow the library sort.
    func members(
        for kind: SmartKind,
        favorites all: [Station],
        history: [Station],
        downloads: [Station]
    ) -> [Station] {
        switch kind {
        case .recentlyPlayed: history
        case .downloads: localSort.apply(downloads)
        case .favorites: favorites(all)
        case .localSongs: localSort.apply(songs)
        }
    }

    /// On-device folders for the local-songs folder filter.
    func folders() -> [LocalFolder] {
        LocalLibrary.folders(of: indexed)
    }

    /// The songs of one folder, in scan order (the detail applies its sort).
    func folderSongs(_ folder: String) -> [Station] {
        LocalLibrary.folders(of: indexed)
            .first { $0.id == folder }?
            .songs.map { $0.station(in: root) } ?? []
    }

    /// Removes a local file the user copied in and drops it from favourites,
    /// then reindexes; Android deletes through the OS sheet, iOS owns the
    /// container so the confirm-then-delete is the whole story.
    func deleteLocal(_ station: Station, app: AppState) async {
        if let url = URL(string: station.url), url.isFileURL {
            try? FileManager.default.removeItem(at: url)
        }
        if app.favoriteURLs.contains(station.url) {
            app.toggleFavorite(station)
        }
        await refresh()
    }

    // MARK: playlists

    func reloadPlaylists() {
        playlists = store.all()
    }

    @discardableResult
    func createPlaylist(_ name: String) -> Playlist? {
        let created = store.create(name: name)
        reloadPlaylists()
        return created
    }

    func renamePlaylist(slug: String, name: String) {
        store.rename(slug: slug, name: name)
        reloadPlaylists()
    }

    func deletePlaylist(slug: String) {
        store.delete(slug: slug)
        reloadPlaylists()
    }

    /// The playlist pane's own sort choice, keyed by slug like Android's
    /// `playlistSort(slug)`.
    func sort(forPlaylist slug: String) -> StationSort {
        playlistSorts[slug] ?? .title
    }

    func setSort(_ sort: StationSort, forPlaylist slug: String) {
        playlistSorts[slug] = sort
        preferences.setSort(sort, for: slug)
    }

    func setCover(slug: String, cover: String) {
        store.setCover(slug: slug, cover: cover)
        reloadPlaylists()
    }

    /// Copies a picked image into the app's container and points the playlist
    /// at it. Android keeps the picker URI; iOS copies so the cover survives
    /// the picker's temporary grant.
    func setCover(slug: String, data: Data) {
        let support = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        let directory = support.appendingPathComponent("CliampLibrary/covers", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("\(slug).img")
        guard (try? data.write(to: file, options: .atomic)) != nil else { return }
        setCover(slug: slug, cover: file.absoluteString)
    }

    func setPinned(slug: String, pinned: Bool) {
        store.setPinned(slug: slug, pinned: pinned)
        reloadPlaylists()
    }

    @discardableResult
    func addStation(slug: String, station: Station) -> Bool {
        let added = store.addStation(slug: slug, station: station)
        reloadPlaylists()
        return added
    }

    func removeMember(slug: String, songId: String) {
        store.removeSong(slug: slug, songId: songId)
        reloadPlaylists()
    }

    func setOrder(slug: String, songIds: [String]) {
        store.setOrder(slug: slug, songIds: songIds)
        reloadPlaylists()
    }

    /// A playlist's members resolved against the live library plus the
    /// snapshot stations radio and podcast members kept.
    func members(slug: String) -> [Station] {
        store.resolve(slug: slug, localSongs: songs)
    }

    func playlist(slug: String) -> Playlist? {
        playlists.first { $0.slug == slug }
    }

    /// Android's `playlistPreview`: the first names, then a count of the rest.
    func playlistPreview(_ playlist: Playlist) -> String {
        guard !playlist.songIds.isEmpty else { return "" }
        // Android's home preview resolves local names only; radio and podcast
        // members show as "…" until the playlist is opened.
        let byId = Dictionary(songs.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
        let head = 4
        let names = playlist.songIds.prefix(head).map { byId[$0] ?? "…" }.joined(separator: " · ")
        guard playlist.songIds.count > head else { return names }
        return "\(names) · +\(playlist.songIds.count - head) more"
    }

    /// Everything the add-songs picker's stations tab offers: cliamp's own
    /// channels, hand-added stations, the directory pages loaded in the
    /// Stations tab, then radio-only favourites — Android's exact collection,
    /// de-duplicated by id.
    func addableStations(favorites: [Station]) -> [Station] {
        let radioFavorites = favorites.filter { $0.source != .local && $0.source != .podcast }
        var seen = Set<String>()
        var out: [Station] = []
        for station in CliampRadio.builtin
            + customStore.load()
            + StationsServices.shared.model.directory
            + radioFavorites
        where seen.insert(station.id).inserted {
            out.append(station)
        }
        return out
    }
}
