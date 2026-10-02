import CliampCore
import Foundation
import Testing

@testable import Cliamp

@Suite("podcasts model")
@MainActor
struct PodcastsModelTests {
    private struct StubDirectory: PodcastDirectoryTransport {
        func text(_ urlString: String) async throws -> String {
            try await Task.sleep(for: .milliseconds(20))
            if urlString.contains("/lookup") {
                return """
                {"results":[{"collectionId":1,"collectionName":"One","feedUrl":"https://x.example/one.xml"}]}
                """
            }
            if urlString.contains("/rss/toppodcasts") {
                return #"{"feed":{"entry":[{"id":{"attributes":{"im:id":"1"}}}]}}"#
            }
            return "{}"
        }
    }

    private func makeModel() -> PodcastsModel {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("podcasts-model-\(UUID().uuidString)", isDirectory: true)
        return PodcastsModel(store: PodcastStore(root: root), directoryTransport: StubDirectory())
    }

    /// The guard for the simulator crash: eight overlapping resets used to
    /// over-consume the chart cursor (`removeFirst` fatal); generation tokens
    /// and clamped removals must let the newest one win cleanly.
    @Test("overlapping directory resets settle on the newest query")
    func overlappingResets() async {
        let model = makeModel()
        for _ in 0..<8 {
            model.load(.top(country: "us"), reset: true)
        }
        try? await Task.sleep(for: .seconds(1))
        #expect(model.shows.map(\.id) == ["1"])
        #expect(!model.loading)
        #expect(model.error == nil)
    }

    @Test("a page cannot land after a newer reset")
    func stalePageDrops() async {
        let model = makeModel()
        model.load(.top(country: "us"), reset: true)
        try? await Task.sleep(for: .milliseconds(10))
        model.load(.category(PodcastGenre(id: 1318, name: "Technology")), reset: true)
        try? await Task.sleep(for: .seconds(1))
        // The category stub returns no shows; the first query's page must not
        // reappear over it.
        #expect(model.query == .category(PodcastGenre(id: 1318, name: "Technology")))
        #expect(model.shows.isEmpty)
    }
}
