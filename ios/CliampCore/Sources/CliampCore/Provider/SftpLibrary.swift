import Foundation

/// One indexed track from an SSH account. Filename-derived, like Android's
/// `SftpTrackEntity`; duration and codec are read by the player when it plays.
public struct SftpTrack: Codable, Sendable, Hashable, Identifiable {
    public var accountId: String
    public var path: String
    public var title: String
    public var artist: String
    public var album: String
    public var albumKey: String
    public var artistKey: String
    public var track: Int
    public var year: Int
    public var size: Int64
    public var mtime: Int64
    public var ext: String

    public var id: String { path }

    public init(accountId: String, scanned: ScannedTrack) {
        self.accountId = accountId
        self.path = scanned.path
        self.title = scanned.title
        self.artist = scanned.artist
        self.album = scanned.album
        self.albumKey = scanned.albumKey
        self.artistKey = scanned.artistKey
        self.track = scanned.track
        self.year = scanned.year
        self.size = scanned.size
        self.mtime = scanned.mtime
        self.ext = scanned.ext
    }

    /// The player-facing station: identity is the path, and the URL carries
    /// the account plus the remote path so nothing replayable is stored.
    public var station: Station {
        Station(
            id: "prov:\(accountId):\(path)",
            name: title.isEmpty ? (path as NSString).lastPathComponent : title,
            url: SftpURI.url(accountId: accountId, path: path),
            source: .provider,
            codec: ext,
            artist: artist,
            album: album
        )
    }
}

/// The `cliamp-sftp://` URL shape: account id in the authority, percent-encoded
/// absolute path in the path, so spaces and `#` survive the round trip.
public enum SftpURI {
    public static let scheme = "cliamp-sftp"

    public static func url(accountId: String, path: String) -> String {
        // Slashes stay literal; everything a URL would misread (spaces, `#`)
        // is percent-encoded.
        let encoded = path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? path
        return "\(scheme)://\(accountId)\(encoded.hasPrefix("/") ? encoded : "/" + encoded)"
    }

    public static func parse(_ url: String) -> (accountId: String, path: String)? {
        let prefix = "\(scheme)://"
        guard url.hasPrefix(prefix) else { return nil }
        let rest = String(url.dropFirst(prefix.count))
        guard let slash = rest.firstIndex(of: "/") else { return nil }
        let accountId = String(rest[rest.startIndex..<slash])
        let encoded = String(rest[slash...])
        let path = encoded.removingPercentEncoding ?? encoded
        guard !accountId.isEmpty, path.hasPrefix("/") else { return nil }
        return (accountId, path)
    }
}

/// One album as the browse screen shows it.
public struct SftpAlbum: Sendable, Hashable, Identifiable {
    public let id: String
    public let name: String
    public let artist: String
    public let songCount: Int
    public let year: Int
}

public struct SftpArtist: Sendable, Hashable, Identifiable {
    public let id: String
    public let name: String
    public let albumCount: Int
}

/// The scanned contents of every SSH account, persisted as JSON like the other
/// library stores. A scan builds its index in memory and commits only on
/// success, so a failed or cancelled walk leaves the previous index intact.
public final class SftpIndexStore: @unchecked Sendable {
    public struct AccountIndex: Codable, Sendable {
        public var folders: String
        public var scannedAt: Int64
        public var tracks: [SftpTrack]
    }

    private struct Payload: Codable {
        var accounts: [String: AccountIndex]
    }

    private let file: URL
    private let lock = NSLock()
    private var accounts: [String: AccountIndex]

    public init(file: URL) {
        self.file = file
        let payload = (try? Data(contentsOf: file))
            .flatMap { try? JSONDecoder().decode(Payload.self, from: $0) }
        self.accounts = payload?.accounts ?? [:]
    }

    public static func appStore() -> SftpIndexStore {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        let directory = base.appendingPathComponent("CliampLibrary", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return SftpIndexStore(file: directory.appendingPathComponent("sftp-index.json"))
    }

    // MARK: reading

    public func index(accountId: String) -> AccountIndex? {
        lock.withLock { accounts[accountId] }
    }

    /// Never indexed, or the configured folders changed since the last scan.
    public func isStale(accountId: String, folders: [String]) -> Bool {
        let recorded = lock.withLock { accounts[accountId] }
        return recorded == nil || recorded?.folders != folders.joined(separator: "\n")
    }

    public func tracks(accountId: String) -> [SftpTrack] {
        lock.withLock { accounts[accountId]?.tracks ?? [] }
    }

    public func albums(accountId: String, style: String) -> [SftpAlbum] {
        let tracks = self.tracks(accountId: accountId)
        var byKey: [String: [SftpTrack]] = [:]
        for track in tracks {
            byKey[track.albumKey, default: []].append(track)
        }
        let albums = byKey.map { key, list -> SftpAlbum in
            let first = list[0]
            return SftpAlbum(
                id: key,
                name: first.album.isEmpty ? (key as NSString).lastPathComponent : first.album,
                artist: first.artist,
                songCount: list.count,
                year: list.map(\.year).max() ?? 0
            )
        }
        if style == "newest" {
            var newest: [String: Int64] = [:]
            for (key, list) in byKey {
                newest[key] = list.map(\.mtime).max() ?? 0
            }
            // Android's query: newest first, then name ascending — the
            // tie-breaker does not flip with the timestamp.
            return albums.sorted {
                let left = newest[$0.id] ?? 0
                let right = newest[$1.id] ?? 0
                if left != right { return left > right }
                return $0.name.lowercased() < $1.name.lowercased()
            }
        }
        return albums.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    public func artists(accountId: String) -> [SftpArtist] {
        let tracks = self.tracks(accountId: accountId)
        var byKey: [String: (name: String, albums: Set<String>)] = [:]
        for track in tracks where !track.artistKey.isEmpty {
            var entry = byKey[track.artistKey] ?? (track.artist, Set<String>())
            entry.name = track.artist
            entry.albums.insert(track.albumKey)
            byKey[track.artistKey] = entry
        }
        return byKey
            .map { SftpArtist(id: $0.key, name: $0.value.name, albumCount: $0.value.albums.count) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    public func artistAlbums(accountId: String, artistKey: String) -> [SftpAlbum] {
        let all = self.tracks(accountId: accountId)
        return albums(accountId: accountId, style: "az")
            .filter { album in
                all.contains { $0.albumKey == album.id && $0.artistKey == artistKey }
            }
            // Android orders an artist's shelf by ascending year, then name.
            .sorted {
                if $0.year != $1.year { return $0.year < $1.year }
                return $0.name.lowercased() < $1.name.lowercased()
            }
    }

    /// One album's tracks in playing order: the track number when the filenames
    /// carried one, then the title.
    public func albumTracks(accountId: String, albumKey: String) -> [SftpTrack] {
        tracks(accountId: accountId)
            .filter { $0.albumKey == albumKey }
            .sorted {
                let left = ($0.track, $0.title.lowercased(), $0.path)
                let right = ($1.track, $1.title.lowercased(), $1.path)
                return left < right
            }
    }

    // MARK: writing

    /// Commits a completed scan. Replacing the whole account prunes anything
    /// the walk no longer found, the way Android's `pruneOlderThan` does after
    /// a successful scan.
    public func commit(accountId: String, folders: [String], scannedAt: Int64, tracks: [SftpTrack]) {
        lock.withLock {
            accounts[accountId] = AccountIndex(
                folders: folders.joined(separator: "\n"),
                scannedAt: scannedAt,
                tracks: tracks
            )
            saveLocked()
        }
    }

    /// Drops an account's index; called when the account itself goes away.
    public func forget(accountId: String) {
        lock.withLock {
            accounts.removeValue(forKey: accountId)
            saveLocked()
        }
    }

    private func saveLocked() {
        guard let data = try? JSONEncoder().encode(Payload(accounts: accounts)) else { return }
        try? data.write(to: file, options: .atomic)
    }
}
