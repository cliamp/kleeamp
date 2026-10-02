import Foundation
import Testing

@testable import CliampCore

@Suite("podcast store")
struct PodcastStoreTests {
    private func makeStore() -> PodcastStore {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("podcast-store-\(UUID().uuidString)", isDirectory: true)
        return PodcastStore(root: root)
    }

    private let show = PodcastShow(
        id: "42", title: "Show", feedUrl: "https://x.example/feed.xml",
        author: "Ada", artwork: "https://img/a.jpg", genre: "Technology", episodeCount: 3
    )

    private let episode = PodcastEpisode(
        guid: "g1", title: "One", audioUrl: "https://cdn/one.mp3", durationMs: 60_000
    )

    @Test("subscriptions toggle newest-first and update in place")
    func subscriptions() {
        let store = makeStore()
        #expect(store.toggleSubscription(show))
        #expect(store.isSubscribed(feedUrl: show.feedUrl))
        #expect(store.subscriptions().map(\.title) == ["Show"])

        var richer = show
        richer.episodeCount = 99
        store.updateSubscription(richer)
        #expect(store.subscriptions().first?.episodeCount == 99)

        #expect(!store.toggleSubscription(show))
        #expect(store.subscriptions().isEmpty)
    }

    @Test("a cached feed round-trips and can go stale")
    func feedCache() {
        let store = makeStore()
        store.saveFeed(show, episodes: [episode])
        #expect(store.freshFeed(feedUrl: show.feedUrl)?.episodes.count == 1)
        #expect(store.cachedFeed(feedUrl: show.feedUrl)?.show.title == "Show")

        // A snapshot older than the TTL is still readable but no longer fresh.
        store.saveFeed(show, episodes: [episode])
        let stale = PodcastStore.CachedFeed(
            show: show, episodes: [episode],
            savedAt: Date().timeIntervalSince1970 - PodcastStore.feedTTL - 1
        )
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("podcast-store-stale-\(UUID().uuidString)", isDirectory: true)
        let other = PodcastStore(root: root)
        other.saveFeed(show, episodes: [episode])
        #expect(stale.savedAt < Date().timeIntervalSince1970 - PodcastStore.feedTTL)
        #expect(other.freshFeed(feedUrl: show.feedUrl) != nil)
    }

    @Test("subscribed episodes flatten the cached feeds")
    func subscribedEpisodes() {
        let store = makeStore()
        store.toggleSubscription(show)
        store.saveFeed(show, episodes: [episode])
        let flat = store.subscribedEpisodes()
        #expect(flat.count == 1)
        #expect(flat.first?.episode.title == "One")
    }

    @Test("progress saves, overwrites and clears")
    func progress() {
        let store = makeStore()
        store.saveProgress(
            EpisodeProgress(url: "https://cdn/one.mp3", positionMs: 5_000, durationMs: 60_000, completed: false)
        )
        #expect(store.progress()["https://cdn/one.mp3"]?.positionMs == 5_000)
        store.saveProgress(
            EpisodeProgress(url: "https://cdn/one.mp3", positionMs: 58_000, durationMs: 60_000, completed: true)
        )
        #expect(store.progress()["https://cdn/one.mp3"]?.completed == true)
        store.clearProgress(url: "https://cdn/one.mp3")
        #expect(store.progress().isEmpty)
    }

    @Test("download entries add and remove by URL")
    func downloads() {
        let store = makeStore()
        let station = episode.station(show: show)
        store.addDownload(
            DownloadEntry(url: station.url, path: "/tmp/one.mp3", bytes: 10, station: station)
        )
        #expect(store.downloads().count == 1)
        store.removeDownload(url: station.url)
        #expect(store.downloads().isEmpty)
    }
}

@Suite("podcast progress")
struct PodcastProgressTests {
    @Test("a completed or nearly finished episode restarts from zero")
    func resumeRules() {
        #expect(PodcastProgress.resumePosition(nil) == 0)
        #expect(
            PodcastProgress.resumePosition(
                EpisodeProgress(url: "u", positionMs: 12_000, durationMs: 60_000, completed: false)
            ) == 12_000
        )
        #expect(
            PodcastProgress.resumePosition(
                EpisodeProgress(url: "u", positionMs: 12_000, durationMs: 60_000, completed: true)
            ) == 0
        )
        #expect(
            PodcastProgress.resumePosition(
                EpisodeProgress(url: "u", positionMs: 35_000, durationMs: 60_000, completed: false)
            ) == 0
        )
    }

    @Test("crossing the near-end window marks completion")
    func completion() {
        #expect(!PodcastProgress.isCompleted(positionMs: 29_000, durationMs: 60_000))
        #expect(PodcastProgress.isCompleted(positionMs: 30_000, durationMs: 60_000))
        #expect(!PodcastProgress.isCompleted(positionMs: 10, durationMs: 0))
    }
}
