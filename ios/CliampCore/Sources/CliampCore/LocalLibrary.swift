import AVFoundation
import Foundation

/// A playable local file, indexed from a folder inside the app's own
/// container. Android reads MediaStore after a permission prompt; iOS has no
/// device-wide audio index, so DEC-02 chose the managed folder: anything the
/// user copies into the app's Documents folder (Finder, Files, AirDrop) is
/// scanned. A local file is just a `Station` whose url is a `file://` URL, so
/// the whole playback pipeline behaves as it does for a stream.
public struct LocalSong: Codable, Sendable, Equatable, Identifiable {
    /// Stable identity: `local:<path relative to the library root>`.
    public let id: String
    public let relativePath: String
    public let title: String
    public let artist: String
    public let album: String
    /// Duration in milliseconds, 0 when the file did not report one.
    public let durationMs: Int64
    /// When the file appeared on device, seconds since epoch.
    public let dateAdded: Int64
    /// Companion cover image beside the file, relative to the root, "" if none.
    public let cover: String

    public init(
        relativePath: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Int64,
        dateAdded: Int64,
        cover: String
    ) {
        self.id = "local:\(relativePath)"
        self.relativePath = relativePath
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
        self.dateAdded = dateAdded
        self.cover = cover
    }

    public func station(in root: URL) -> Station {
        Station(
            id: id,
            name: title,
            url: root.appendingPathComponent(relativePath).absoluteString,
            source: .local,
            cover: cover.isEmpty ? "" : root.appendingPathComponent(cover).absoluteString,
            artist: artist,
            album: album,
            durationMs: durationMs,
            dateAdded: dateAdded
        )
    }

    public func fileURL(in root: URL) -> URL {
        root.appendingPathComponent(relativePath)
    }
}

/// One folder of local songs, used by the smart-list folder chips and rows.
public struct LocalFolder: Sendable, Equatable, Identifiable {
    /// The folder's path relative to the library root.
    public let id: String
    public let name: String
    public let songs: [LocalSong]

    public init(id: String, name: String, songs: [LocalSong]) {
        self.id = id
        self.name = name
        self.songs = songs
    }
}

/// Indexes audio under a root folder and caches the result as JSON so a warm
/// launch paints instantly and rescans in the background. Mirrors Android's
/// `LocalLibrary`, minus MediaStore: identity is the relative path, so a moved
/// file is a new song and a deleted one drops out on the next scan.
public final class LocalLibrary: Sendable {
    /// Container formats AVFoundation plays natively. OGG and Opus are not in
    /// the list — Android indexes them, iOS cannot play them (DEC-02).
    public static let audioExtensions: Set<String> = [
        "mp3", "m4a", "m4b", "aac", "wav", "wave", "aif", "aiff", "aifc", "flac", "caf",
    ]

    private static let coverNames = [
        "cover.jpg", "cover.png", "folder.jpg", "folder.png",
        "albumart.jpg", "albumart.png", "front.jpg", "front.png",
    ]

    public let root: URL
    private let cache: URL

    public init(root: URL, cache: URL) {
        self.root = root
        self.cache = cache
    }

    /// The app's own Documents folder, which File Sharing exposes.
    public static func documentsLibrary() -> LocalLibrary {
        let documents = FileManager.default.urls(
            for: .documentDirectory, in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        let support = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("CliampLibrary", isDirectory: true)
        return LocalLibrary(
            root: documents,
            cache: support.appendingPathComponent("local-songs.json")
        )
    }

    // MARK: reading

    /// The cached index, dropping entries whose file has since disappeared, the
    /// same filter Android's warm launch applies.
    public func cachedSongs() -> [LocalSong]? {
        guard let data = try? Data(contentsOf: cache),
              let songs = try? JSONDecoder().decode([LocalSong].self, from: data)
        else { return nil }
        let existing = songs.filter { Self.isRegularFile(fileURL($0.relativePath)) }
        return existing.isEmpty ? nil : existing
    }

    public func save(_ songs: [LocalSong]) {
        guard let data = try? JSONEncoder().encode(songs) else { return }
        try? FileManager.default.createDirectory(
            at: cache.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try? data.write(to: cache, options: .atomic)
    }

    private func fileURL(_ relativePath: String) -> URL {
        root.appendingPathComponent(relativePath)
    }

    // MARK: scanning

    /// Walks the root for audio files, reads tags and duration for each with a
    /// small concurrency window, and returns them title-sorted like Android's
    /// `COLLATE NOCASE` query.
    public func scan() async -> [LocalSong] {
        let files = audioFiles()
        guard !files.isEmpty else { return [] }
        // One directory listing per folder decides its cover, so a large
        // library is one pass instead of a `listFiles` per song.
        var coverByDirectory: [String: String] = [:]
        for file in files {
            let directory = (file as NSString).deletingLastPathComponent
            if coverByDirectory[directory] == nil {
                coverByDirectory[directory] = companionCover(directory: directory) ?? ""
            }
        }
        var songs: [LocalSong] = []
        songs.reserveCapacity(files.count)
        await withTaskGroup(of: LocalSong?.self) { group in
            var next = 0
            while next < files.count, next < 4 {
                let file = files[next]
                let cover = coverByDirectory[(file as NSString).deletingLastPathComponent] ?? ""
                group.addTask { await Self.read(relativePath: file, cover: cover, root: self.root) }
                next += 1
            }
            for await song in group {
                if let song { songs.append(song) }
                if next < files.count {
                    let file = files[next]
                    let cover = coverByDirectory[(file as NSString).deletingLastPathComponent] ?? ""
                    group.addTask { await Self.read(relativePath: file, cover: cover, root: self.root) }
                    next += 1
                }
            }
        }
        return songs.sorted { $0.title.lowercased() < $1.title.lowercased() }
    }

    /// Relative paths of every audio file under the root, hidden files skipped.
    /// The walk builds each path from the root it was given, so enumerated
    /// URLs never leak symlink-resolved prefixes (`/private/var`).
    public func audioFiles() -> [String] {
        var files: [String] = []
        walk(root.path, relative: "", into: &files)
        return files.sorted()
    }

    private func walk(_ directory: String, relative: String, into files: inout [String]) {
        let manager = FileManager.default
        let contents = (try? manager.contentsOfDirectory(atPath: directory)) ?? []
        for name in contents where !name.hasPrefix(".") {
            let childRelative = relative.isEmpty ? name : relative + "/" + name
            let childURL = URL(fileURLWithPath: directory + "/" + name)
            let values = try? childURL.resourceValues(forKeys: [
                .isHiddenKey, .isPackageKey, .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey,
            ])
            guard let values, values.isSymbolicLink != true, values.isHidden != true else { continue }
            // Finder-hidden names (a `hidden` flag, not a dot prefix) and
            // document packages (.photoslibrary, .rtfd, …) stay out.
            guard values.isPackage != true else { continue }
            if values.isDirectory == true {
                walk(childURL.path, relative: childRelative, into: &files)
            } else if values.isRegularFile == true,
                      Self.audioExtensions.contains((name as NSString).pathExtension.lowercased()) {
                files.append(childRelative)
            }
        }
    }

    /// A regular file, not a directory or symlink, following Android's
    /// `File.isFile` checks.
    private static func isRegularFile(_ url: URL) -> Bool {
        let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        return values?.isRegularFile == true && values?.isSymbolicLink != true
    }

    /// Reads one file's tags, falling back to the filename for the title and
    /// Android's "unknown artist" placeholder, the way MediaStore rows read.
    private static func read(relativePath: String, cover: String, root: URL) async -> LocalSong? {
        let url = root.appendingPathComponent(relativePath)
        let fileName = (relativePath as NSString).lastPathComponent
        let values = try? url.resourceValues(forKeys: [.creationDateKey, .contentModificationDateKey])
        let dateAdded = Int64(
            (values?.creationDate ?? values?.contentModificationDate ?? .distantPast).timeIntervalSince1970
        )
        let asset = AVURLAsset(url: url)
        async let common = try? await asset.load(.commonMetadata)
        async let duration = try? await asset.load(.duration)
        let metadata = await common ?? []
        let title = await metadata.first(id: .commonIdentifierTitle)
        let artist = await metadata.first(id: .commonIdentifierArtist)
        let album = await metadata.first(id: .commonIdentifierAlbumName)
        let seconds = await duration?.seconds ?? 0
        // Anything longer than a few days is a bogus header; the cap also
        // keeps the Int64 conversion from overflowing.
        let durationMs = seconds.isFinite && seconds > 0 && seconds < 400_000
            ? Int64(seconds * 1000)
            : 0
        let base = (fileName as NSString).deletingPathExtension
        return LocalSong(
            relativePath: relativePath,
            title: title?.isEmpty == false ? title! : base,
            artist: artist?.isEmpty == false ? artist! : "unknown artist",
            album: album ?? "",
            durationMs: durationMs,
            dateAdded: max(0, dateAdded),
            cover: cover
        )
    }

    /// A companion image in the track's own folder: a canonical name if one
    /// exists, else any image, matching Android's `nearestCover`.
    private func companionCover(directory: String) -> String? {
        let manager = FileManager.default
        let directoryURL = fileURL(directory)
        for name in Self.coverNames {
            let candidate = directoryURL.appendingPathComponent(name)
            if Self.isRegularFile(candidate) {
                return join(directory, name)
            }
        }
        let contents = (try? manager.contentsOfDirectory(atPath: directoryURL.path)) ?? []
        guard let image = contents.first(where: { name in
            ["jpg", "jpeg", "png"].contains((name as NSString).pathExtension.lowercased())
                && Self.isRegularFile(directoryURL.appendingPathComponent(name))
        }) else { return nil }
        return join(directory, image)
    }

    private func join(_ directory: String, _ name: String) -> String {
        directory.isEmpty ? name : "\(directory)/\(name)"
    }

    // MARK: grouping

    /// Songs grouped by containing folder, folder and song names
    /// case-insensitively sorted — Android's `foldersOf`. Songs that sit in
    /// the root itself get `rootName`, since they have no folder of their own.
    public static func folders(of songs: [LocalSong], rootName: String = "Library") -> [LocalFolder] {
        var byDirectory: [String: [LocalSong]] = [:]
        var order: [String] = []
        for song in songs {
            let directory = (song.relativePath as NSString).deletingLastPathComponent
            if byDirectory[directory] == nil { order.append(directory) }
            byDirectory[directory, default: []].append(song)
        }
        return order
            .map { directory in
                let list = (byDirectory[directory] ?? [])
                    .sorted { $0.title.lowercased() < $1.title.lowercased() }
                let name = (directory as NSString).lastPathComponent
                return LocalFolder(id: directory, name: name.isEmpty ? rootName : name, songs: list)
            }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }
}

private extension Array where Element == AVMetadataItem {
    /// The first non-blank string for a common identifier.
    func first(id: AVMetadataIdentifier) async -> String? {
        let items = AVMetadataItem.metadataItems(from: self, filteredByIdentifier: id)
        for item in items {
            if let value = try? await item.load(.stringValue),
               !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return value
            }
        }
        return nil
    }
}
