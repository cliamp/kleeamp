import Foundation

/// One audio file found on a server, with what its path says about it. A port
/// of Android's `ScannedTrack`.
public struct ScannedTrack: Sendable, Hashable {
    public let path: String
    public let title: String
    public let artist: String
    public let album: String
    /// The album's directory: stable, unique, and what tracks group by.
    public let albumKey: String
    /// Case-folded artist name, so `Boards of Canada` and `boards of canada`
    /// are one.
    public let artistKey: String
    public let track: Int
    public let year: Int
    public let size: Int64
    public let mtime: Int64
    public let ext: String

    public init(
        path: String, title: String, artist: String, album: String,
        albumKey: String, artistKey: String, track: Int, year: Int,
        size: Int64, mtime: Int64, ext: String
    ) {
        self.path = path
        self.title = title
        self.artist = artist
        self.album = album
        self.albumKey = albumKey
        self.artistKey = artistKey
        self.track = track
        self.year = year
        self.size = size
        self.mtime = mtime
        self.ext = ext
    }
}

/// What AVFoundation can decode, which is narrower than what people keep in a
/// music folder. Android also indexes OGG/Opus/MKA/3GP/TS; iOS cannot play
/// them, so they are skipped rather than indexed into an entry that fails the
/// moment it is tapped (recorded in DEC-05/DEC-02).
public let sftpAudioExtensions: Set<String> = [
    "mp3", "flac", "m4a", "m4b", "aac", "wav", "wave", "aif", "aiff", "aifc", "caf", "mp4",
]

/// Walks an account's configured folders and reports every audio file it
/// finds. There are no tags here: reading them would mean pulling the header
/// of every file over the wire, so the layout is treated as the metadata —
/// exactly what `Artist/Album/01 - Title.flac` already encodes. The real
/// metadata is read by the player when the track is played.
public struct SftpScan: Sendable {
    public static let maxDepth = 8
    public static let maxFiles = 40_000
    public static let maxDirectories = 20_000
    public static let batchSize = 250

    public let folders: [String]
    /// Handed tracks as they are found, so the library fills in while a large
    /// tree is still being walked.
    public let onBatch: @Sendable ([ScannedTrack]) -> Void
    public let onProgress: @Sendable (Int, String) -> Void

    public init(
        folders: [String],
        onBatch: @escaping @Sendable ([ScannedTrack]) -> Void,
        onProgress: @escaping @Sendable (Int, String) -> Void = { _, _ in }
    ) {
        self.folders = folders
        self.onBatch = onBatch
        self.onProgress = onProgress
    }

    private struct State {
        var found = 0
        var directories = 0
        var visited = Set<String>()
        var batch: [ScannedTrack] = []
        /// The first failure to list a configured root; a subdirectory's
        /// failure is normal, a root's is why the whole scan came up empty.
        var rootError: Error?
    }

    /// Total tracks found. Throws whatever the connection threw when no root
    /// at all could be walked.
    public func run(_ tree: RemoteFileTree) async throws -> Int {
        var state = State()
        var walked = 0
        var lastError: Error?
        for folder in folders {
            try Task.checkCancellation()
            let root = ((try? await tree.canonicalize(folder)) ?? folder).trimmingTrailingSlashes()
            if !state.visited.insert(root).inserted { continue }
            do {
                try await walk(
                    tree, root: root, directory: root, depth: 0, state: &state
                )
                walked += 1
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                // A fatal failure (auth, transport) ends the scan even when an
                // earlier root succeeded; only a recoverable root failure is
                // remembered for the "nothing found" report.
                if !Self.isRecoverable(error) { throw error }
                lastError = error
            }
        }
        // A cancelled walk must not publish a partial batch or a count.
        try Task.checkCancellation()
        flush(&state)
        if state.found == 0, let rootError = state.rootError { throw rootError }
        if walked == 0, let lastError { throw lastError }
        return state.found
    }

    private func walk(
        _ tree: RemoteFileTree,
        root: String,
        directory: String,
        depth: Int,
        state: inout State
    ) async throws {
        if depth > Self.maxDepth || state.found >= Self.maxFiles
            || state.directories >= Self.maxDirectories {
            return
        }
        state.directories += 1
        onProgress(state.found, directory)

        let entries: [RemoteEntry]
        do {
            entries = try await tree.list(directory)
        } catch {
            // Cancellation and a broken connection are not "this folder was
            // unreadable": propagating them is what keeps a cancelled or
            // refused scan from committing an empty index over a good one.
            if error is CancellationError || Task.isCancelled { throw CancellationError() }
            if !Self.isRecoverable(error) { throw error }
            // An unreadable subdirectory is normal (permissions, a stale
            // mount) and is not a reason to abandon the rest of the library.
            // A configured root that cannot be listed is different: report it
            // when nothing else was found.
            if depth == 0, state.rootError == nil { state.rootError = error }
            return
        }

        var subdirectories: [String] = []
        for entry in entries.sorted(by: { $0.name.lowercased() < $1.name.lowercased() }) {
            if entry.name.hasPrefix(".") { continue }
            switch await kind(tree, entry: entry) {
            case .directory:
                subdirectories.append(entry.path)
            case .file:
                let ext = (entry.name as NSString).pathExtension.lowercased()
                guard sftpAudioExtensions.contains(ext) else { continue }
                if state.found >= Self.maxFiles { return }
                state.found += 1
                state.batch.append(describe(
                    root: root, path: entry.path, size: entry.size, mtime: entry.modifiedAt
                ))
                if state.batch.count >= Self.batchSize { flush(&state) }
            case .other:
                continue
            }
        }
        for child in subdirectories {
            let canonical = (try? await tree.canonicalize(child)) ?? child
            // Symlinks make a music tree a graph; without this, one pointing
            // at an ancestor walks forever.
            if !state.visited.insert(canonical).inserted { continue }
            try await walk(tree, root: root, directory: child, depth: depth + 1, state: &state)
        }
    }

    /// A directory the server refused or that vanished is normal; a refused
    /// credential, an unreachable host, a bad host key, or any error the
    /// session did not classify is not: unknown errors fail the scan rather
    /// than silently producing an empty index.
    static func isRecoverable(_ error: Error) -> Bool {
        guard let ssh = error as? SshError else { return false }
        switch ssh {
        case .missing, .directoryUnreadable: return true
        default: return false
        }
    }

    private enum Kind {
        case directory
        case file
        case other
    }

    private func kind(_ tree: RemoteFileTree, entry: RemoteEntry) async -> Kind {
        switch entry.kind {
        case .directory: return .directory
        case .file: return .file
        case .other: return .other
        case .symlink:
            // `list` reports what lstat saw, so a symlinked album folder is
            // neither until it is followed.
            guard let target = try? await tree.stat(entry.path) else { return .other }
            switch target.kind {
            case .directory: return .directory
            case .file: return .file
            default: return .other
            }
        }
    }

    private func flush(_ state: inout State) {
        guard !state.batch.isEmpty else { return }
        onBatch(state.batch)
        state.batch = []
    }
}

/// Reads a file's path as `<root>/<artist>/<album>/<track> - <title>.<ext>`,
/// degrading to whatever of that is actually present — Android's `describe`.
public func describe(root: String, path: String, size: Int64 = 0, mtime: Int64 = 0) -> ScannedTrack {
    let directory = (path as NSString).deletingLastPathComponent
    let filename = (path as NSString).lastPathComponent
    let ext = (filename as NSString).pathExtension.lowercased()
    let stem = (filename as NSString).deletingPathExtension

    let (track, titleFromName) = splitTrackNumber(stem)
    let segments = String(directory.dropFirst(root.count))
        .split(separator: "/")
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }

    var artist = ""
    var albumSource = segments.isEmpty
        ? ((root as NSString).lastPathComponent.isEmpty ? "music" : (root as NSString).lastPathComponent)
        : segments[segments.count - 1]
    if segments.count >= 2 {
        artist = segments[segments.count - 2].trimmingCharacters(in: .whitespaces)
    } else if segments.count == 1 {
        // Kotlin splits with limit = 2: only the first separator names the
        // artist, so `Pink Floyd - 1973 - Dark Side` keeps both later parts
        // for the year pass below.
        if let separator = albumSource.range(of: " - ") {
            let first = String(albumSource[..<separator.lowerBound])
                .trimmingCharacters(in: .whitespaces)
            let rest = String(albumSource[separator.upperBound...])
                .trimmingCharacters(in: .whitespaces)
            // `1973 - Dark Side` is a year and an album, not an artist and an
            // album, and it is punctuated identically. The year split owns it.
            let leadsWithYear = Int(first) != nil
            if !leadsWithYear, !first.isEmpty, !rest.isEmpty {
                artist = first
                albumSource = rest
            }
        }
    }

    let (album, year) = splitYear(albumSource)
    let title = titleFromName.trimmingCharacters(in: .whitespaces)
    return ScannedTrack(
        path: path,
        title: title.isEmpty ? stem : title,
        artist: artist,
        album: album,
        albumKey: directory,
        artistKey: artist.lowercased(),
        track: track,
        year: year,
        size: size,
        mtime: mtime,
        ext: ext
    )
}

/// `07 - Money` and `07. Money` and `07 Money` all mean the same thing. Capped
/// at three digits so a title that opens with a year — `1999 Party` — keeps it.
private let trackNumberPattern = try? NSRegularExpression(
    pattern: #"^(\d{1,3})(?:\s*[-–—._)\]]+\s*|\s+)(\S.*)$"#
)

private func splitTrackNumber(_ stem: String) -> (Int, String) {
    let trimmed = stem.trimmingCharacters(in: .whitespaces)
    guard let pattern = trackNumberPattern,
          let match = pattern.firstMatch(
              in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)
          ),
          match.numberOfRanges == 3,
          let numberRange = Range(match.range(at: 1), in: trimmed),
          let titleRange = Range(match.range(at: 2), in: trimmed),
          let number = Int(trimmed[numberRange])
    else { return (0, stem) }
    return (number, String(trimmed[titleRange]))
}

/// A bracketed year is unambiguous, so the separator after it is optional. A
/// bare one is not — `2001 A Space Odyssey` is a title — so it has to be
/// punctuated as a prefix before it counts as a year.
private let yearBracketed = try? NSRegularExpression(
    pattern: #"^[(\[]((?:19|20)\d{2})[)\]]\s*[-–—._]?\s*(\S.*)$"#
)
private let yearLeading = try? NSRegularExpression(
    pattern: #"^((?:19|20)\d{2})\s*[-–—._]\s*(\S.*)$"#
)
private let yearTrailing = try? NSRegularExpression(
    pattern: #"^(.+?)\s*[(\[]((?:19|20)\d{2})[)\]]\s*$"#
)

private func splitYear(_ name: String) -> (String, Int) {
    let trimmed = name.trimmingCharacters(in: .whitespaces)
    for (pattern, nameGroup, yearGroup) in [
        (yearBracketed, 2, 1),
        (yearLeading, 2, 1),
        (yearTrailing, 1, 2),
    ] {
        guard let pattern,
              let match = pattern.firstMatch(
                  in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)
              ),
              let nameRange = Range(match.range(at: nameGroup), in: trimmed),
              let yearRange = Range(match.range(at: yearGroup), in: trimmed),
              let year = Int(trimmed[yearRange])
        else { continue }
        return (String(trimmed[nameRange]).trimmingCharacters(in: .whitespaces), year)
    }
    return (trimmed, 0)
}

/// Whether a configured folder is actually a folder on the far end.
public func isDirectory(_ tree: RemoteFileTree, path: String) async -> Bool {
    guard let entry = try? await tree.stat(path) else { return false }
    return entry.kind == .directory
}

/// Directories worth offering when the folder field was left empty: a first
/// connection is the worst moment to ask for an absolute path someone has not
/// thought about in years, so the probe looks where music actually lives.
public func suggestMusicFolders(_ tree: RemoteFileTree) async -> [String] {
    let home = ((try? await tree.canonicalize(".")) ?? "").trimmingTrailingSlashes()
    var candidates: [String] = []
    if !home.isEmpty {
        candidates += ["Music", "music", "Musik", "Musique", "Media/Music", "media/music"]
            .map { "\(home)/\($0)" }
    }
    candidates += ["/srv/music", "/mnt/music", "/media/music", "/data/music", "/music", "/var/lib/music"]

    var found: [String] = []
    var seen = Set<String>()
    for candidate in candidates {
        if await holdsAudio(tree, path: candidate), seen.insert(candidate).inserted {
            found.append(candidate)
        }
    }
    return found
}

/// Cheap two-level look for anything playable, so an empty folder is not
/// offered.
private func holdsAudio(_ tree: RemoteFileTree, path: String) async -> Bool {
    guard await isDirectory(tree, path: path) else { return false }
    // Hidden entries are skipped by the scan, so they must not make a folder
    // look worth offering (or eat the twelve-directory allowance).
    let top = ((try? await tree.list(path)) ?? []).filter { !$0.name.hasPrefix(".") }
    if top.contains(where: { $0.kind == .file && isAudio($0.name) }) { return true }
    for child in top.filter({ $0.kind == .directory }).prefix(12) {
        let inner = ((try? await tree.list(child.path)) ?? [])
            .filter { !$0.name.hasPrefix(".") }
        if inner.contains(where: { $0.kind == .file && isAudio($0.name) }) { return true }
    }
    return false
}

private func isAudio(_ name: String) -> Bool {
    sftpAudioExtensions.contains((name as NSString).pathExtension.lowercased())
}

private extension String {
    func trimmingTrailingSlashes() -> String {
        var trimmed = self
        while trimmed.count > 1, trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        return trimmed
    }
}
