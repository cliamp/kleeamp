import Foundation
import Testing

@testable import CliampCore

@Suite("playback speed")
struct PlaybackSpeedTests {
    @Test("the ladder steps and wraps back to normal")
    func ladder() {
        #expect(PlaybackSpeed.next(after: 1) == 1.25)
        #expect(PlaybackSpeed.next(after: 1.75) == 2)
        #expect(PlaybackSpeed.next(after: 2) == 0.5)
        #expect(PlaybackSpeed.next(after: 0.75) == 1)
        // An unknown value (a hand-edited default) restarts at normal.
        #expect(PlaybackSpeed.next(after: 3.5) == 1)
    }

    @Test("speed is clamped to the supported range")
    func clamp() {
        #expect(PlaybackSpeed.clamped(0.1) == 0.5)
        #expect(PlaybackSpeed.clamped(4) == 2)
        #expect(PlaybackSpeed.clamped(1.25) == 1.25)
    }

    @Test("labels drop the decimal on whole multipliers")
    func label() {
        #expect(PlaybackSpeed.label(1) == "1×")
        #expect(PlaybackSpeed.label(1.25) == "1.25×")
        #expect(PlaybackSpeed.label(0.5) == "0.5×")
    }
}

@Suite("seek policy")
struct SeekPolicyTests {
    @Test("seeks at or beyond the end guard are rejected, earlier ones allowed")
    func endGuard() {
        #expect(!SeekPolicy.canSeek(positionMs: 1_000 - 250, durationMs: 1_000))
        #expect(!SeekPolicy.canSeek(positionMs: 990, durationMs: 1_000))
        #expect(SeekPolicy.canSeek(positionMs: 749, durationMs: 1_000))
        #expect(SeekPolicy.canSeek(positionMs: 0, durationMs: 1_000))
    }

    @Test("a source without a length never seeks")
    func noDuration() {
        #expect(!SeekPolicy.canSeek(positionMs: 0, durationMs: 0))
        #expect(!SeekPolicy.canSeek(positionMs: 10, durationMs: -1))
    }
}
