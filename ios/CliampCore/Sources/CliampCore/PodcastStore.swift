import Foundation

/// Podcast persistence in plain JSON files: subscriptions, cached feeds,
/// listening progress and download entries. The Android client keeps these in
/// SQLite; on iOS the sets are small and the shape is simple, so files with
/// atomic rewrites are honest and testable. All operations are serialized
/// behind one lock, because the download manager writes from a background
/// task while the UI reads.
public final class PodcastStore: @unchecked Sendable {
    /// How long a cached feed is worth showing while it refreshes.
    public static let feedTTL: TimeInterval = 12 * 60 * 60

    private let root: URL
    private let lock = NSLock()

    private let subscriptionsFile: URL
    private let progressFile: URL
    private let downloadsFile: URL
    private let feedsDirectory: URL
    private let snapshotsDirectory: URL

    public init(root: URL) {
        self.root = root
        subscriptionsFile = root.appendingPathComponent("subscriptions.json")
        progressFile = root.appendingPathComponent("progress.json")
        downloadsFile = root.appendingPathComponent("downloads.json")
        feedsDirectory = root.appendingPathComponent("feeds", isDirectory: true)
        snapshotsDirectory = root.appendingPathComponent("directory", isDirectory: true)
        try? FileManager.default.createDirectory(at: feedsDirectory, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: snapshotsDirectory, withIntermediateDirectories: true)
    }

    public static func applicationDefault() -> PodcastStore {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return PodcastStore(root: base.appendingPathComponent("CliampPodcasts", isDirectory: true))
    }

    // MARK: subscriptions

    /// Newest first, matching Android's descending positions.
    public func subscriptions() -> [PodcastShow] {
        lock.withLock { decode([PodcastShow].self, from: subscriptionsFile) ?? [] }
    }

    public func isSubscribed(feedUrl: String) -> Bool {
        subscriptions().contains { $0.feedUrl == feedUrl }
    }

    /// Toggles and returns the new state: `true` means it is now subscribed.
    @discardableResult
    public func toggleSubscription(_ show: PodcastShow) -> Bool {
        lock.withLock {
            var list = decode([PodcastShow].self, from: subscriptionsFile) ?? []
            if let index = list.firstIndex(where: { $0.feedUrl == show.feedUrl }) {
                list.remove(at: index)
                save(list, to: subscriptionsFile)
                return false
            }
            list.insert(show, at: 0)
            save(list, to: subscriptionsFile)
            return true
        }
    }

    /// A feed refresh can hand back a richer show than the directory did, so a
    /// subscribed show is updated in place without moving its position.
    public func updateSubscription(_ show: PodcastShow) {
        lock.withLock {
            var list = decode([PodcastShow].self, from: subscriptionsFile) ?? []
            guard let index = list.firstIndex(where: { $0.feedUrl == show.feedUrl }) else { return }
            list[index] = show
            save(list, to: subscriptionsFile)
        }
    }

    // MARK: feed cache

    public struct CachedFeed: Codable, Sendable {
        public let show: PodcastShow
        public let episodes: [PodcastEpisode]
        public let savedAt: TimeInterval
    }

    public func cachedFeed(feedUrl: String) -> CachedFeed? {
        lock.withLock { decode(CachedFeed.self, from: feedFile(feedUrl)) }
    }

    /// The cached feed only when it is still fresh enough to paint first.
    public func freshFeed(feedUrl: String) -> CachedFeed? {
        guard let cached = cachedFeed(feedUrl: feedUrl),
              Date().timeIntervalSince1970 - cached.savedAt <= Self.feedTTL
        else { return nil }
        return cached
    }

    public func saveFeed(_ show: PodcastShow, episodes: [PodcastEpisode]) {
        lock.withLock {
            let cached = CachedFeed(show: show, episodes: episodes, savedAt: Date().timeIntervalSince1970)
            save(cached, to: feedFile(show.feedUrl))
        }
    }

    /// Every cached episode of every subscribed show, flattened. A show never
    /// opened contributes nothing; there is no server episode index.
    public func subscribedEpisodes() -> [(show: PodcastShow, episode: PodcastEpisode)] {
        var out: [(show: PodcastShow, episode: PodcastEpisode)] = []
        for show in subscriptions() {
            guard let cached = cachedFeed(feedUrl: show.feedUrl) else { continue }
            let live = cached.show.title == show.title ? show : cached.show
            out.append(contentsOf: cached.episodes.map { (live, $0) })
        }
        return out
    }

    // MARK: directory snapshot

    /// The last first page of a directory query, so a reopened tab paints
    /// instantly while the network answers.
    public func directorySnapshot(key: String) -> [PodcastShow]? {
        lock.withLock { decode([PodcastShow].self, from: snapshotFile(key)) }
    }

    public func saveDirectorySnapshot(_ shows: [PodcastShow], key: String) {
        lock.withLock { save(shows, to: snapshotFile(key)) }
    }

    // MARK: progress

    /// Every saved position, keyed by episode URL, for badging a list at once.
    public func progress() -> [String: EpisodeProgress] {
        lock.withLock { decode([String: EpisodeProgress].self, from: progressFile) ?? [:] }
    }

    public func saveProgress(_ progress: EpisodeProgress) {
        lock.withLock {
            var map = decode([String: EpisodeProgress].self, from: progressFile) ?? [:]
            map[progress.url] = progress
            save(map, to: progressFile)
        }
    }

    public func clearProgress(url: String) {
        lock.withLock {
            var map = decode([String: EpisodeProgress].self, from: progressFile) ?? [:]
            map.removeValue(forKey: url)
            save(map, to: progressFile)
        }
    }

    // MARK: downloads

    public func downloads() -> [String: DownloadEntry] {
        lock.withLock { decode([String: DownloadEntry].self, from: downloadsFile) ?? [:] }
    }

    public func setDownloads(_ entries: [String: DownloadEntry]) {
        lock.withLock { save(entries, to: downloadsFile) }
    }

    public func addDownload(_ entry: DownloadEntry) {
        lock.withLock {
            var map = decode([String: DownloadEntry].self, from: downloadsFile) ?? [:]
            map[entry.url] = entry
            save(map, to: downloadsFile)
        }
    }

    public func removeDownload(url: String) {
        lock.withLock {
            var map = decode([String: DownloadEntry].self, from: downloadsFile) ?? [:]
            map.removeValue(forKey: url)
            save(map, to: downloadsFile)
        }
    }

    // MARK: storage

    private func feedFile(_ feedUrl: String) -> URL {
        feedsDirectory.appendingPathComponent("\(Self.stableName(feedUrl)).json")
    }

    private func snapshotFile(_ key: String) -> URL {
        snapshotsDirectory.appendingPathComponent("\(Self.stableName(key)).json")
    }

    /// A deterministic file name for a feed URL, filesystem-safe on every OS.
    static func stableName(_ value: String) -> String {
        var hash = UInt64(5381)
        for byte in value.utf8 {
            hash = (hash &* 33) &+ UInt64(byte)
        }
        return String(hash, radix: 16)
    }

    private func decode<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
