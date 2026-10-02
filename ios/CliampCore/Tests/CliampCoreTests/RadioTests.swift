import Testing

@testable import CliampCore

@Suite("cliamp radio")
struct CliampRadioTests {
    @Test("the seed list is the twelve channels")
    func seedList() {
        #expect(CliampRadio.builtin.count == 12)
        #expect(CliampRadio.builtin.first?.id == "cliamp:lofi")
        #expect(CliampRadio.builtin.first?.url == "https://radio.cliamp.stream/lofi/stream")
        #expect(CliampRadio.builtin.allSatisfy { $0.source == .cliamp })
    }

    @Test("m3u parsing reads names, comments and urls")
    func parsePlaylist() {
        let body = """
        #EXTM3U
        # a comment
        #EXTINF:-1,Lofi
        https://radio.cliamp.stream/lofi/stream

        #EXTINF:-1,NCS Drum & Bass
        https://radio.cliamp.stream/ncs-dnb/stream
        https://radio.cliamp.stream/no-name/stream
        """
        let stations = CliampRadio.parseM3u(body)
        #expect(stations.count == 3)
        #expect(stations[0].name == "Lofi")
        #expect(stations[0].slug == "lofi")
        #expect(stations[1].name == "NCS Drum & Bass")
        #expect(stations[1].slug == "ncs-dnb")
        #expect(stations[2].name == "no name")
        #expect(stations[2].slug == "no-name")
    }

    @Test("empty and garbage playlists parse to nothing")
    func parseGarbage() {
        #expect(CliampRadio.parseM3u("").isEmpty)
        #expect(CliampRadio.parseM3u("#EXTM3U\n#EXTINF:-1,Only a name").isEmpty)
    }
}

@Suite("station model")
struct StationTests {
    private var stream: Station {
        Station(
            id: "dir:1", name: "Radio", url: "https://example.com/stream",
            source: .directory, tags: "jazz, Chill out", country: "Germany",
            codec: "MP3", bitrate: 128
        )
    }

    @Test("meta joins codec, bitrate and country")
    func meta() {
        #expect(stream.meta == "mp3 · 128k · germany")
    }

    @Test("tags are lowercased, split and deduplicated")
    func tags() {
        #expect(stream.tagList == ["jazz", "chill", "out"])
    }

    @Test("only finite sources are tracks")
    func isTrack() {
        #expect(!stream.isTrack)
        #expect(
            Station(id: "l:1", name: "Song", url: "file:///a.mp3", source: .local).isTrack
        )
    }

    @Test("source lines follow the source")
    func sourceLine() {
        #expect(stream.sourceLine == "mp3 · 128k · germany")
        #expect(CliampRadio.builtin[0].sourceLine == "cliamp radio")
    }

    @Test("the player source line names origin, country and votes")
    func playerSourceLine() {
        #expect(stream.playerSourceLine == "directory · germany")
        let voted = Station(
            id: "dir:2", name: "Radio", url: "https://example.com/s",
            source: .directory, country: "France", votes: 12_345
        )
        #expect(voted.playerSourceLine == "directory · france · 12.3k votes")
        // A cliamp channel has no country or votes to show.
        #expect(CliampRadio.builtin[0].playerSourceLine == "cliamp radio")
    }
}

@Suite("time formatting")
struct TimeFormatTests {
    @Test("clock formats with and without hours")
    func clock() {
        #expect(TimeFormat.clock(83_000) == "1:23")
        #expect(TimeFormat.clock(3_700_000) == "1:01:40")
        #expect(TimeFormat.clock(-5) == "0:00")
    }

    @Test("compact compresses large counts")
    func compact() {
        #expect(TimeFormat.compact(999) == "999")
        #expect(TimeFormat.compact(50_000) == "50.0k")
        #expect(TimeFormat.compact(1_200_000) == "1.2m")
    }
}
