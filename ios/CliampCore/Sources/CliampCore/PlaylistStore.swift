import Foundation

/// A user playlist: a small `Station` (source custom) whose members can come
/// from any source. Local members are remembered by their `local:<path>` id,
/// which survives a rescan; radio and podcast members keep a snapshot
/// `Station` so they resolve without a network round-trip. Ported from
/// Android's `PlaylistStore`.
public struct Playlist: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var slug: String
    public var name: String
    public var cover: String
    public var pinned: Bool
    /// Ordered member ids (`local:<path>` or a station id).
    public var songIds: [String]

    public init(slug: String, name: String, cover: String = "", pinned: Bool = false, songIds: [String] = []) {
        self.slug = slug
        self.name = name
        self.cover = cover
        self.pinned = pinned
        self.songIds = songIds
    }

    public var id: String { "playlist:\(slug)" }

    /// The playlist as the rest of the app plays it: a finite custom source.
    public var station: Station {
        Station(
            id: id,
            name: name,
            url: "cliamp-playlist://\(slug)",
            source: .custom,
            slug: slug,
            cover: cover
        )
    }

    /// Android's slugify: Unicode letters and digits kept, everything else one
    /// dash, trimmed; an empty name falls back to a timestamp slug.
    public static func slugify(_ name: String) -> String {
        let mapped = name.lowercased().map { character -> String in
            // Kotlin's isLetterOrDigit: Unicode letters and decimal digits,
            // so "Mix ١" and "Mix ٢" stay distinct slugs.
            if character.isLetter || character.isNumber {
                return String(character)
            }
            return "-"
        }.joined()
        var slug = mapped
        while slug.contains("--") {
            slug = slug.replacingOccurrences(of: "--", with: "-")
        }
        slug = slug.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        if slug.isEmpty {
            slug = "playlist-\(Int(Date().timeIntervalSince1970 * 1000))"
        }
        return slug
    }
}

/// The persisted playlist set plus the snapshot stations its non-local members
/// need. One JSON file, written atomically on every edit.
public final class PlaylistStore: @unchecked Sendable {
    private struct Payload: Codable {
        var playlists: [Playlist]
        var snapshots: [Station]
    }

    private let file: URL
    private let lock = NSLock()
    private var playlists: [Playlist]
    private var snapshots: [String: Station]

    public init(file: URL) {
        self.file = file
        let payload = (try? Data(contentsOf: file))
            .flatMap { try? JSONDecoder().decode(Payload.self, from: $0) }
        self.playlists = payload?.playlists ?? []
        self.snapshots = Dictionary(
            (payload?.snapshots ?? []).map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
    }

    /// The app's store, beside the other library state.
    public static func appStore() -> PlaylistStore {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        let directory = base.appendingPathComponent("CliampLibrary", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return PlaylistStore(file: directory.appendingPathComponent("playlists.json"))
    }

    // MARK: reading

    public func all() -> [Playlist] {
        lock.withLock { Self.ordered(playlists) }
    }

    /// Android's `ORDER BY position, name` with position 0 everywhere.
    /// SQLite's BINARY collation compares UTF-8 bytes, so comparisons are
    /// case-sensitive and byte-lexicographic, not Swift's Unicode ordering.
    private static func ordered(_ list: [Playlist]) -> [Playlist] {
        list.sorted { $0.name.utf8.lexicographicallyPrecedes($1.name.utf8) }
    }

    public func playlist(slug: String) -> Playlist? {
        lock.withLock { playlists.first { $0.slug == slug } }
    }

    public func pinnedSlugs() -> Set<String> {
        lock.withLock { Set(playlists.filter(\.pinned).map(\.slug)) }
    }

    public func pinned() -> [Playlist] {
        lock.withLock { Self.ordered(playlists.filter(\.pinned)) }
    }

    public func unpinned() -> [Playlist] {
        lock.withLock { Self.ordered(playlists.filter { !$0.pinned }) }
    }

    /// The snapshot stations kept for radio and podcast members.
    public func snapshotStations() -> [Station] {
        lock.withLock { Array(snapshots.values) }
    }

    /// Ordered members resolved against the live library plus the snapshot
    /// stations radio and podcast members kept.
    public func resolve(slug: String, localSongs: [Station]) -> [Station] {
        guard let playlist = playlist(slug: slug) else { return [] }
        let snapshotList = lock.withLock { Array(snapshots.values) }
        return Self.resolveMembers(
            songIds: playlist.songIds, localSongs: localSongs, snapshots: snapshotList
        )
    }

    /// Android's `resolveMembers`: local ids match the live library, everything
    /// else the snapshots; ids that resolve to nothing are dropped in place.
    public static func resolveMembers(
        songIds: [String],
        localSongs: [Station],
        snapshots: [Station]
    ) -> [Station] {
        guard !songIds.isEmpty else { return [] }
        let localById = Dictionary(localSongs.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let snapshotById = Dictionary(snapshots.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return songIds.compactMap { localById[$0] ?? snapshotById[$0] }
    }

    // MARK: editing

    /// Trims the name, refuses blanks and duplicate slugs. Returns the created
    /// playlist, or the existing one when the slug is taken.
    @discardableResult
    public func create(name: String) -> Playlist? {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return nil }
        return lock.withLock {
            let slug = Playlist.slugify(clean)
            if let existing = playlists.first(where: { $0.slug == slug }) { return existing }
            let playlist = Playlist(slug: slug, name: clean)
            playlists.append(playlist)
            saveLocked()
            return playlist
        }
    }

    public func rename(slug: String, name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        lock.withLock {
            guard let index = playlists.firstIndex(where: { $0.slug == slug }) else { return }
            playlists[index].name = clean
            saveLocked()
        }
    }

    public func delete(slug: String) {
        lock.withLock {
            playlists.removeAll { $0.slug == slug }
            saveLocked()
        }
    }

    public func setPinned(slug: String, pinned: Bool) {
        lock.withLock {
            guard let index = playlists.firstIndex(where: { $0.slug == slug }) else { return }
            playlists[index].pinned = pinned
            saveLocked()
        }
    }

    public func setCover(slug: String, cover: String) {
        lock.withLock {
            guard let index = playlists.firstIndex(where: { $0.slug == slug }) else { return }
            playlists[index].cover = cover
            saveLocked()
        }
    }

    /// Adds a member id if it is not already there; `false` means duplicate.
    @discardableResult
    public func addSong(slug: String, songId: String) -> Bool {
        lock.withLock {
            guard let index = playlists.firstIndex(where: { $0.slug == slug }),
                  !playlists[index].songIds.contains(songId)
            else { return false }
            playlists[index].songIds.append(songId)
            saveLocked()
            return true
        }
    }

    /// Adds any station: local songs by id as usual, radio and podcast members
    /// snapshot their full station so they resolve later without a fetch.
    @discardableResult
    public func addStation(slug: String, station: Station) -> Bool {
        if station.source != .local {
            lock.withLock { snapshots[station.id] = station }
        }
        return addSong(slug: slug, songId: station.id)
    }

    public func removeSong(slug: String, songId: String) {
        lock.withLock {
            guard let index = playlists.firstIndex(where: { $0.slug == slug }) else { return }
            playlists[index].songIds.removeAll { $0 == songId }
            saveLocked()
        }
    }

    public func setOrder(slug: String, songIds: [String]) {
        lock.withLock {
            guard let index = playlists.firstIndex(where: { $0.slug == slug }) else { return }
            playlists[index].songIds = songIds
            saveLocked()
        }
    }

    // MARK: storage

    private func saveLocked() {
        let payload = Payload(playlists: playlists, snapshots: Array(snapshots.values))
        guard let data = try? JSONEncoder().encode(payload) else { return }
        try? data.write(to: file, options: .atomic)
    }
}
