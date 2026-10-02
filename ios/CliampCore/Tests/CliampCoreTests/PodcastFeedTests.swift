import Foundation
import Testing

@testable import CliampCore

@Suite("podcast feed")
struct PodcastFeedTests {
    private let show = PodcastShow(
        id: "42", title: "", feedUrl: "https://x.example/feed.xml",
        author: "", artwork: "", genre: "", episodeCount: 0
    )

    private let rss = """
    <?xml version="1.0" encoding="utf-8"?>
    <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
      <channel>
        <title>Feed Title</title>
        <description>Channel &amp; notes with &lt;b&gt;markup&lt;/b&gt;</description>
        <itunes:author>Channel Author</itunes:author>
        <itunes:image href="https://img/channel.jpg"/>
        <itunes:category text="Technology"/>
        <image><url>https://img/itunes.jpg</url></image>
        <item>
          <title>Episode One</title>
          <guid>guid-1</guid>
          <pubDate>Wed, 2 Sep 2026 10:00:00 +0200</pubDate>
          <description>First &amp; best</description>
          <itunes:duration>01:02:03</itunes:duration>
          <itunes:episode>7</itunes:episode>
          <itunes:season>2</itunes:season>
          <itunes:episodeType>full</itunes:episodeType>
          <enclosure url="https://cdn/one.mp3" type="audio/mpeg" length="1"/>
        </item>
        <item>
          <title>Episode Two</title>
          <itunes:title>Ignored Title</itunes:title>
          <guid></guid>
          <pubDate>not a date</pubDate>
          <itunes:duration>2712</itunes:duration>
          <itunes:episodeType>trailer</itunes:episodeType>
          <enclosure url="https://cdn/cover.jpg" type="image/jpeg" length="1"/>
          <enclosure url="https://cdn/two.m4a?token=1" type="audio/mp4" length="1"/>
        </item>
        <item>
          <title>No Audio</title>
          <description>drops</description>
        </item>
      </channel>
    </rss>
    """

    @Test("the feed fills only what the directory did not know")
    func channelFallback() throws {
        let loaded = try PodcastFeed.parse(base: show, data: Data(rss.utf8))
        #expect(loaded.show.title == "Feed Title")
        #expect(loaded.show.author == "Channel Author")
        #expect(loaded.show.artwork == "https://img/channel.jpg")
        #expect(loaded.show.genre == "Technology")
        #expect(loaded.show.episodeCount == 2)
        #expect(loaded.show.description == "Channel & notes with markup")
    }

    @Test("items map to episodes; the first audio enclosure wins")
    func episodes() throws {
        let loaded = try PodcastFeed.parse(base: show, data: Data(rss.utf8))
        #expect(loaded.episodes.count == 2)
        let first = loaded.episodes[0]
        #expect(first.title == "Episode One")
        #expect(first.guid == "guid-1")
        #expect(first.audioUrl == "https://cdn/one.mp3")
        #expect(first.durationMs == 3_723_000)
        #expect(first.publishedAt == 1_788_336_000_000)
        #expect(first.description == "First & best")
        #expect(first.number == 7)
        #expect(first.season == 2)
        #expect(first.isFull)
        #expect(first.codec == "mp3")

        let second = loaded.episodes[1]
        // An empty guid falls back to the audio URL, and a non-audio
        // enclosure (cover art) is skipped.
        #expect(second.guid == "https://cdn/two.m4a?token=1")
        #expect(second.audioUrl == "https://cdn/two.m4a?token=1")
        #expect(second.durationMs == 2_712_000)
        #expect(second.publishedAt == 0)
        #expect(!second.isFull)
        #expect(second.codec == "m4a")
    }

    @Test("an episode becomes a podcast Station with show artwork as cover")
    func stationMapping() throws {
        let loaded = try PodcastFeed.parse(base: show, data: Data(rss.utf8))
        let station = loaded.episodes[0].station(show: loaded.show)
        #expect(station.source == .podcast)
        #expect(station.url == "https://cdn/one.mp3")
        #expect(station.name == "Episode One")
        #expect(station.artist == "Feed Title")
        #expect(station.cover == "https://img/channel.jpg")
        #expect(station.durationMs == 3_723_000)
        #expect(station.isTrack)
    }

    @Test("duration shapes in the wild")
    func durations() {
        #expect(PodcastFeed.parseDuration("01:02:03") == 3_723_000)
        #expect(PodcastFeed.parseDuration("36:12") == 2_172_000)
        #expect(PodcastFeed.parseDuration("2712") == 2_712_000)
        #expect(PodcastFeed.parseDuration("") == 0)
        #expect(PodcastFeed.parseDuration("nonsense") == 0)
        // A hostile feed must not trap: NaN, infinity, huge values, negatives.
        #expect(PodcastFeed.parseDuration("nan") == 0)
        #expect(PodcastFeed.parseDuration("inf") == 0)
        #expect(PodcastFeed.parseDuration("1e300") == 0)
        #expect(PodcastFeed.parseDuration("999999999999999:59:59") == 0)
        #expect(PodcastFeed.parseDuration("-5") == 0)
    }

    @Test("markup and entities are stripped to one line")
    func strip() {
        #expect(PodcastFeed.strip("<p>Hello&nbsp;<b>world</b></p>") == "Hello world")
        #expect(PodcastFeed.strip("  a\n b  ") == "a b")
    }
}
