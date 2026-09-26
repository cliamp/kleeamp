import Foundation

/// The persisted per-list sort choice, matching Android's `playlist_sorts`
/// preference: one slug -> sort map, title by default. The library's own list
/// uses the key `local-songs`.
public struct LibraryPreferences: @unchecked Sendable {
    public static let localSongs = "local-songs"

    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "playlist_sorts") {
        self.defaults = defaults
        self.key = key
    }

    public func sort(for slug: String) -> StationSort {
        sorts()[slug] ?? .title
    }

    /// Every stored choice, so a model can keep an observable copy.
    public func allSorts() -> [String: StationSort] {
        sorts()
    }

    public func setSort(_ sort: StationSort, for slug: String) {
        var map = sorts()
        map[slug] = sort
        guard let data = try? JSONEncoder().encode(map) else { return }
        defaults.set(data, forKey: key)
    }

    private func sorts() -> [String: StationSort] {
        guard let data = defaults.data(forKey: key),
              let map = try? JSONDecoder().decode([String: StationSort].self, from: data)
        else { return [:] }
        return map
    }
}
