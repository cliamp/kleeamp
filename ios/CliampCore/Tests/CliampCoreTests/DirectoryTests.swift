import Foundation
import Testing

@testable import CliampCore

@Suite("radio-browser decoding")
struct RadioBrowserDecodeTests {
    private let fixture = """
    [
      {"stationuuid":"u1","name":" A ","url":"http://a/stream","url_resolved":"http://a/stream",
       "hls":0,"tags":"jazz","country":"Germany","countrycode":"DE","codec":"MP3","bitrate":128,"votes":10},
      {"stationuuid":"u2","name":"","url":"http://b","url_resolved":"","hls":0},
      {"stationuuid":"u3","name":"HLS ok","url":"http://c","url_resolved":"http://c/listen.m3u8","hls":1},
      {"stationuuid":"u4","name":"HLS bad","url":"http://d","url_resolved":"http://d/live","hls":1},
      {"stationuuid":"u5","name":"Dup","url":"http://a/stream","url_resolved":"http://a/stream","hls":0},
      {"stationuuid":"u6","name":"Blank","url":"","url_resolved":"","hls":0}
    ]
    """

    @Test("bad and duplicate rows are dropped, names and urls normalised")
    func decodeFiltering() throws {
        let stations = try RadioBrowserClient.decode(fixture)
        #expect(stations.count == 3)
        #expect(stations[0].name == "A")
        #expect(stations[0].source == .directory)
        #expect(stations[0].id == "rb:u1")
        #expect(stations[1].name == "unnamed station")
        #expect(stations[1].url == "http://b")
        // hls=1 is kept only when the resolved url ends in .m3u8
        #expect(stations[2].url == "http://c/listen.m3u8")
    }

    @Test("query labels match the source")
    func queryLabels() {
        #expect(DirectoryQuery.topVoted.label == "top")
        #expect(DirectoryQuery.trending.label == "trending")
        #expect(DirectoryQuery.tag("jazz").label == "#jazz")
        #expect(DirectoryQuery.search("fm4").label == "\"fm4\"")
        #expect(DirectoryQuery.country(code: "DE", name: "Germany").label == "germany")
    }
}

private actor StubTransport: RadioBrowserTransport {
    var body: String
    var failingHosts: Set<String>
    var deadProbes: Set<String>
    private(set) var requests: [String] = []
    private(set) var failuresRemaining = 0

    init(body: String = "[]", failingHosts: Set<String> = [], deadProbes: Set<String> = []) {
        self.body = body
        self.failingHosts = failingHosts
        self.deadProbes = deadProbes
    }

    func failFirst(_ count: Int) {
        failuresRemaining = count
    }

    func text(_ url: URL) async throws -> String {
        requests.append(url.host ?? url.absoluteString)
        if failuresRemaining > 0 {
            failuresRemaining -= 1
            throw URLError(.timedOut)
        }
        if let host = url.host, failingHosts.contains(host) {
            throw URLError(.cannotConnectToHost)
        }
        return body
    }

    func probe(_ url: URL) async -> Bool {
        guard let host = url.host else { return false }
        return !deadProbes.contains(host) && !failingHosts.contains(host)
    }

    func fireAndForget(_ url: URL) async {
        requests.append("click:\(url.host ?? "")")
    }
}

private final class MemoryMirrorStore: RadioBrowserMirrorStore, @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?

    init(value: String? = nil) {
        self.value = value
    }

    func load() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func save(_ base: String) {
        lock.lock()
        defer { lock.unlock() }
        value = base
    }
}

@Suite("radio-browser mirrors")
struct RadioBrowserMirrorTests {
    private let page = """
    [{"stationuuid":"u1","name":"One","url":"http://a","url_resolved":"http://a","hls":0}]
    """

    @Test("a dead probe is skipped and a live mirror is used and pinned")
    func discoverySkipsDead() async throws {
        let transport = StubTransport(body: page, deadProbes: ["de1.api.radio-browser.info"])
        let store = MemoryMirrorStore()
        let client = RadioBrowserClient(
            transport: transport,
            mirrorStore: store,
            discovery: { ["https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info"] }
        )
        let stations = try await client.topVoted(offset: 0)
        #expect(stations.count == 1)
        #expect(store.load() == "https://de2.api.radio-browser.info")
    }

    @Test("an unreachable pinned mirror falls back to the discovered field")
    func pinnedFailover() async throws {
        let transport = StubTransport(body: page, failingHosts: ["de1.api.radio-browser.info"])
        let store = MemoryMirrorStore(value: "https://de1.api.radio-browser.info")
        let client = RadioBrowserClient(
            transport: transport,
            mirrorStore: store,
            discovery: { ["https://de2.api.radio-browser.info"] }
        )
        _ = try await client.topVoted(offset: 0)
        #expect(store.load() == "https://de2.api.radio-browser.info")
    }

    @Test("a mirror that burned a request is not tried again this session")
    func deadMirrorSkipped() async throws {
        let transport = StubTransport(body: page, failingHosts: ["de1.api.radio-browser.info"])
        let store = MemoryMirrorStore(value: "https://de1.api.radio-browser.info")
        let client = RadioBrowserClient(
            transport: transport,
            mirrorStore: store,
            discovery: { ["https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info"] }
        )
        _ = try await client.topVoted(offset: 0)
        _ = try await client.topVoted(offset: 60)
        let de1Requests = await transport.requests.filter { $0 == "de1.api.radio-browser.info" }
        #expect(de1Requests.count == 1)
    }

    @Test("a transient failure is retried before surfacing")
    func retriesTransientFailures() async throws {
        let transport = StubTransport(body: page)
        await transport.failFirst(2)
        let client = RadioBrowserClient(
            transport: transport,
            mirrorStore: MemoryMirrorStore(),
            discovery: { ["https://de2.api.radio-browser.info"] }
        )
        let stations = try await client.topVoted(offset: 0)
        #expect(stations.count == 1)
        let attempts = await transport.requests.filter { $0 == "de2.api.radio-browser.info" }.count
        #expect(attempts == 3)
    }

    @Test("play reporting pings the pinned mirror")
    func reportClick() async {
        let transport = StubTransport()
        let client = RadioBrowserClient(
            transport: transport,
            mirrorStore: MemoryMirrorStore(value: "https://de2.api.radio-browser.info"),
            discovery: { [] }
        )
        await client.reportClick(uuid: "abc")
        let clicks = await transport.requests.filter { $0.hasPrefix("click:") }
        #expect(clicks == ["click:de2.api.radio-browser.info"])
    }
}

@Suite("custom stations")
struct CustomStationTests {
    @Test("validation matches the source rules")
    func validation() {
        #expect(Station.custom(name: "", url: "  ") == nil)
        #expect(Station.custom(name: "", url: "ftp://host/stream") == nil)
        let bare = Station.custom(name: "", url: "example.com/live")
        #expect(bare?.url == "https://example.com/live")
        #expect(bare?.name == "example.com")
        #expect(bare?.source == .custom)
        #expect(bare?.id == "custom:https://example.com/live")
        let named = Station.custom(name: "  Jazz FM ", url: "http://jazz.fm/live")
        #expect(named?.name == "Jazz FM")
        #expect(named?.url == "http://jazz.fm/live")
        let www = Station.custom(name: "", url: "https://www.example.com/stream")
        #expect(www?.name == "example.com")
    }

    @Test("the store round-trips and replaces duplicates")
    func store() {
        let defaults = UserDefaults(suiteName: "custom-stations-\(UUID().uuidString)")!
        let store = CustomStationStore(defaults: defaults)
        let first = Station.custom(name: "One", url: "https://one.example/stream")!
        let second = Station.custom(name: "Two", url: "https://two.example/stream")!
        store.add(first)
        store.add(second)
        #expect(store.load().map(\.name) == ["One", "Two"])
        #expect(store.contains(url: first.url))

        let renamed = Station.custom(name: "One again", url: "https://one.example/stream")!
        store.add(renamed)
        #expect(store.load().map(\.name) == ["Two", "One again"])

        store.remove(url: second.url)
        #expect(store.load().map(\.name) == ["One again"])
    }
}
