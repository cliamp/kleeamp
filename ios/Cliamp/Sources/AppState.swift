import CliampCore
import Foundation
import Observation

/// Session state that persists across launches. Auto-resume, resume-local,
/// auto-download, mono and the buffer still await their audible behavior, but
/// their storage is real from here on (SET-01/DAT-01).
@MainActor
@Observable
final class AppState {
    var palettePreference: String { didSet { defaults.set(palettePreference, forKey: Keys.palette) } }
    var haptics: Bool { didSet { defaults.set(haptics, forKey: Keys.haptics) } }
    var visualizer: String { didSet { defaults.set(visualizer, forKey: Keys.visualizer) } }
    var cellular: Bool { didSet { defaults.set(cellular, forKey: Keys.cellular) } }
    var mono: Bool { didSet { defaults.set(mono, forKey: Keys.mono) } }
    var bufferSeconds: Double { didSet { defaults.set(bufferSeconds, forKey: Keys.buffer) } }
    var autoResume: Bool { didSet { defaults.set(autoResume, forKey: Keys.autoResume) } }
    var resumeLocalSongs: Bool { didSet { defaults.set(resumeLocalSongs, forKey: Keys.resumeLocal) } }
    var autoDownload: Bool { didSet { defaults.set(autoDownload, forKey: Keys.autoDownload) } }
    var speed: Double { didSet { defaults.set(speed, forKey: Keys.speed) } }
    var subsGrid: Bool { didSet { defaults.set(subsGrid, forKey: Keys.subsGrid) } }
    var podcastDirectoryGrid: Bool { didSet { defaults.set(podcastDirectoryGrid, forKey: Keys.podcastDirectoryGrid) } }

    private(set) var favoriteURLs: Set<String> = []
    /// Newest first; the full stations, not just URLs, so the cold-launch
    /// fallback can navigate them.
    private(set) var favorites: [Station] = []
    /// Most recently played first, trimmed to the Android history limit.
    private(set) var history: [Station] = []
    /// What the mini player shows on a silent cold launch.
    private(set) var lastStation: Station?

    private let defaults: UserDefaults
    private let library: RadioLibrary

    private enum Keys {
        static let palette = "palette"
        static let haptics = "haptics"
        static let visualizer = "visualizer"
        static let cellular = "cellular"
        static let mono = "mono"
        static let buffer = "buffer_seconds"
        static let autoResume = "auto_resume"
        static let resumeLocal = "resume_local_songs"
        static let autoDownload = "auto_download"
        static let speed = "speed"
        static let subsGrid = "subs_grid"
        static let podcastDirectoryGrid = "pod_directory_grid"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        library = RadioLibrary(defaults: defaults)
        palettePreference = defaults.string(forKey: Keys.palette) ?? "system"
        haptics = defaults.object(forKey: Keys.haptics) as? Bool ?? true
        visualizer = defaults.string(forKey: Keys.visualizer) ?? "spectrum"
        cellular = defaults.object(forKey: Keys.cellular) as? Bool ?? true
        mono = defaults.bool(forKey: Keys.mono)
        bufferSeconds = defaults.object(forKey: Keys.buffer) as? Double ?? 30
        autoResume = defaults.bool(forKey: Keys.autoResume)
        resumeLocalSongs = defaults.bool(forKey: Keys.resumeLocal)
        autoDownload = defaults.bool(forKey: Keys.autoDownload)
        speed = PlaybackSpeed.clamped(defaults.object(forKey: Keys.speed) as? Double ?? 1)
        subsGrid = defaults.object(forKey: Keys.subsGrid) as? Bool ?? true
        podcastDirectoryGrid = defaults.object(forKey: Keys.podcastDirectoryGrid) as? Bool ?? true
        favorites = library.favorites()
        favoriteURLs = Set(favorites.map(\.url))
        history = library.history()
        lastStation = library.lastStation()
    }

    func isFavorite(_ station: Station) -> Bool {
        favoriteURLs.contains(station.url)
    }

    func toggleFavorite(_ station: Station) {
        library.toggleFavorite(station)
        favorites = library.favorites()
        favoriteURLs = Set(favorites.map(\.url))
    }

    /// The one place a user-initiated play touches persistence, matching the
    /// Android `persistAndRefresh` choke point: history and last station.
    func recordPlay(_ station: Station) {
        library.pushHistory(station)
        library.setLastStation(station)
        history = library.history()
        lastStation = station
    }

    /// What prev/next walk before anything was played from a real list:
    /// recent history, or favourites when history is empty.
    var fallbackStations: [Station] {
        history.isEmpty ? favorites : history
    }
}
