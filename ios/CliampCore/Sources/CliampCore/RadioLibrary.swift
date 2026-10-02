import Foundation

/// Favourites, play history and the last tuned station, persisted as JSON in
/// the app's defaults. Mirrors `Prefs` in the Android source: favourites are
/// newest-first, history keeps the 60 most recent plays, and the last station
/// is what a silent cold launch shows in the mini player.
public struct RadioLibrary: @unchecked Sendable {
    /// Android trims history to the 60 most recent plays (`HistoryDao.trim`).
    public static let historyLimit = 60

    private let defaults: UserDefaults
    private let favoritesKey: String
    private let historyKey: String
    private let lastStationKey: String

    public init(
        defaults: UserDefaults = .standard,
        favoritesKey: String = "radio_favorites",
        historyKey: String = "radio_history",
        lastStationKey: String = "last_station"
    ) {
        self.defaults = defaults
        self.favoritesKey = favoritesKey
        self.historyKey = historyKey
        self.lastStationKey = lastStationKey
    }

    // MARK: favourites

    /// Newest first, the order Android stores (`nextTopPosition` counts down).
    public func favorites() -> [Station] {
        decode([Station].self, forKey: favoritesKey) ?? []
    }

    public func isFavorite(url: String) -> Bool {
        favorites().contains { $0.url == url }
    }

    /// Toggles and returns the new state: `true` means it is now a favourite.
    @discardableResult
    public func toggleFavorite(_ station: Station) -> Bool {
        var list = favorites()
        if let index = list.firstIndex(where: { $0.url == station.url }) {
            list.remove(at: index)
            save(list, forKey: favoritesKey)
            return false
        }
        list.insert(station, at: 0)
        save(list, forKey: favoritesKey)
        return true
    }

    // MARK: history

    /// Most recently played first.
    public func history() -> [Station] {
        decode([Station].self, forKey: historyKey) ?? []
    }

    /// A fresh play re-stamps an already-seen station instead of duplicating
    /// it, the same upsert `HistoryDao.touch` performs.
    public func pushHistory(_ station: Station) {
        var list = history()
        list.removeAll { $0.url == station.url }
        list.insert(station, at: 0)
        if list.count > Self.historyLimit {
            list.removeLast(list.count - Self.historyLimit)
        }
        save(list, forKey: historyKey)
    }

    // MARK: last station

    public func lastStation() -> Station? {
        decode(Station.self, forKey: lastStationKey)
    }

    public func setLastStation(_ station: Station) {
        save(station, forKey: lastStationKey)
    }

    // MARK: storage

    private func decode<T: Decodable>(_ type: T.Type, forKey key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, forKey key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: key)
    }
}
