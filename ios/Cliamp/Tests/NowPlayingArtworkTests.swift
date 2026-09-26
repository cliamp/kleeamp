import MediaPlayer
import Testing
import UIKit

@testable import Cliamp

@Suite("now playing artwork")
struct NowPlayingArtworkTests {
    /// The regression guard for the iPad crash: MediaPlayer serializes
    /// now-playing info on its own queue and calls the artwork request
    /// handler there. A MainActor-isolated handler traps with
    /// `dispatch_assert_queue` the moment a directory station has real art.
    @Test("the artwork handler can run off the main thread")
    @MainActor
    func handlerIsNonisolated() async {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64)).image { context in
            UIColor.systemRed.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 64, height: 64))
        }
        let artwork = SystemPlayback.artwork(for: image)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: "regression",
            MPMediaItemPropertyArtwork: artwork,
        ]
        // Give MediaPlayer's serialization queue time to call the handler.
        try? await Task.sleep(for: .milliseconds(600))
        #expect(MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == "regression")
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
