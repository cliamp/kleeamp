import Foundation
import Testing

@testable import CliampCore

@Suite("playlist text")
struct PlaylistTextTests {
    @Test("an m3u yields its first URL, skipping comments and blanks")
    func m3u() {
        let body = """
        #EXTM3U
        #EXTINF:-1,Lofi Radio

        http://streams.example/lofi
        http://streams.example/backup
        """
        #expect(PlaylistText.firstURL(inM3U: body) == "http://streams.example/lofi")
        #expect(PlaylistText.firstURL(inM3U: "#EXTM3U\n\n") == nil)
        #expect(PlaylistText.firstURL(inM3U: "not-a-url") == nil)
    }

    @Test("CRLF files parse the same as LF ones")
    func m3uCRLF() {
        let body = "#EXTM3U\r\nhttp://streams.example/lofi\r\n"
        #expect(PlaylistText.firstURL(inM3U: body) == "http://streams.example/lofi")
    }

    @Test("a pls yields its first File entry, case-insensitively")
    func pls() {
        let body = """
        [playlist]
        NumberOfEntries=2
        File1=http://streams.example/one
        Title1=One
        file2=https://streams.example/two
        """
        #expect(PlaylistText.firstURL(inPLS: body) == "http://streams.example/one")

        let lowercase = "[playlist]\nfile1=https://streams.example/only\n"
        #expect(PlaylistText.firstURL(inPLS: lowercase) == "https://streams.example/only")
        #expect(PlaylistText.firstURL(inPLS: "[playlist]\nTitle1=x\n") == nil)
    }

    @Test("the kind reads the path, ignoring the query and case")
    func kind() {
        #expect(PlaylistKind(url: "http://x.example/radio.m3u") == .m3u)
        #expect(PlaylistKind(url: "HTTP://x.example/RADIO.M3U?token=1") == .m3u)
        #expect(PlaylistKind(url: "https://x.example/a.pls") == .pls)
        #expect(PlaylistKind(url: "https://x.example/live.m3u8") == .hls)
        #expect(PlaylistKind(url: "https://x.example/song.mp3") == .none)
        #expect(PlaylistKind(url: "https://x.example/stream") == .none)
    }
}
