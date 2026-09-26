import Foundation

public extension Station {
    /// A hand-added station: name plus stream URL, playable like anything else.
    /// Validation matches `customStation()` in the Android source.
    static func custom(name: String, url rawURL: String) -> Station? {
        let trimmed = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let fixed = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard fixed.hasPrefix("http://") || fixed.hasPrefix("https://") else { return nil }
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = trimmedName.isEmpty ? Self.hostLabel(fixed) : trimmedName
        return Station(id: "custom:\(fixed)", name: label, url: fixed, source: .custom)
    }

    private static func hostLabel(_ url: String) -> String {
        guard let host = URL(string: url)?.host, !host.isEmpty else { return url }
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }
}

/// The custom stations, persisted as JSON in the app's defaults. Order is
/// insertion order, which is what the section renders and what the plus form
/// appends to.
public struct CustomStationStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "custom_stations") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> [Station] {
        guard let data = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([Station].self, from: data)) ?? []
    }

    public func contains(url: String) -> Bool {
        load().contains { $0.url == url }
    }

    /// Adding the same URL twice replaces the earlier entry rather than
    /// duplicating it, matching the Android upsert.
    public func add(_ station: Station) {
        var list = load()
        list.removeAll { $0.url == station.url }
        list.append(station)
        save(list)
    }

    public func remove(url: String) {
        save(load().filter { $0.url != url })
    }

    private func save(_ list: [Station]) {
        guard let data = try? JSONEncoder().encode(list) else { return }
        defaults.set(data, forKey: key)
    }
}
