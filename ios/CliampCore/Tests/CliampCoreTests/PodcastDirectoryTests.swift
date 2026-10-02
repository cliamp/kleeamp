import Foundation
import Testing

@testable import CliampCore

private struct StubTransport: PodcastDirectoryTransport {
    let responses: [String: String]

    func text(_ urlString: String) async throws -> String {
        for (needle, body) in responses where urlString.contains(needle) {
            return body
        }
        throw URLError(.badURL)
    }
}

@Suite("podcast directory")
struct PodcastDirectoryTests {
    private let searchJSON = """
    {"resultCount":2,"results":[
      {"collectionId":11,"collectionName":" Alpha Show ","artistName":"Ada","feedUrl":"https://x.example/a.xml",
       "artworkUrl600":"https://img/big.jpg","artworkUrl100":"https://img/small.jpg","primaryGenreName":"Technology","trackCount":12},
      {"collectionId":12,"collectionName":"","artistName":"","feedUrl":"https://x.example/b.xml",
       "artworkUrl100":"https://img/b.jpg","primaryGenreName":"News","trackCount":3},
      {"collectionId":13,"collectionName":"No Feed","feedUrl":"","primaryGenreName":"News"}
    ]}
    """

    @Test("search maps, trims and drops shows without a feed")
    func search() async throws {
        let shows = try await PodcastDirectory.search(
            term: "alpha", transport: StubTransport(responses: ["/search": searchJSON])
        )
        #expect(shows.count == 2)
        #expect(shows[0].title == "Alpha Show")
        #expect(shows[0].artwork == "https://img/big.jpg")
        #expect(shows[1].title == "untitled show")
        #expect(shows[1].artwork == "https://img/b.jpg")
    }

    @Test("a blank search never hits the transport")
    func blankSearch() async throws {
        let shows = try await PodcastDirectory.search(
            term: "  ", transport: StubTransport(responses: ["/search": searchJSON])
        )
        #expect(shows.isEmpty)
    }

    @Test("lookup preserves the order the ids were asked in")
    func lookupOrder() async throws {
        let body = """
        {"results":[
          {"collectionId":2,"collectionName":"Two","feedUrl":"https://x.example/2.xml"},
          {"collectionId":1,"collectionName":"One","feedUrl":"https://x.example/1.xml"}
        ]}
        """
        let shows = try await PodcastDirectory.lookup(
            ids: ["1", "2"], transport: StubTransport(responses: ["/lookup": body])
        )
        #expect(shows.map(\.id) == ["1", "2"])
    }

    @Test("charts read im:id in rank order")
    func charts() async throws {
        let body = """
        {"feed":{"entry":[{"id":{"attributes":{"im:id":"111"}}},{"id":{"attributes":{"im:id":"222"}}},{"id":{"attributes":{"im:id":""}}}]}}
        """
        let ids = try await PodcastDirectory.chartIds(
            country: "GB", transport: StubTransport(responses: ["/gb/rss": body])
        )
        #expect(ids == ["111", "222"])
    }

    @Test("a feed URL Apple does not list gets a stable synthetic id")
    func feedId() {
        let first = PodcastDirectory.feedId("https://x.example/feed.xml")
        let second = PodcastDirectory.feedId(" https://x.example/feed.xml ")
        #expect(first == second)
        #expect(first.hasPrefix("feed:"))
    }
}
