import Foundation

/// The playback-speed ladder: one tap steps to the next entry and wraps back
/// to normal, matching Android's `NowPlayingViewModel.SpeedSteps`.
public enum PlaybackSpeed {
    public static let steps: [Double] = [1, 1.25, 1.5, 1.75, 2, 0.5, 0.75]

    public static func next(after current: Double) -> Double {
        guard let index = steps.firstIndex(where: { abs($0 - current) < 0.01 }) else {
            return 1
        }
        return steps[(index + 1) % steps.count]
    }

    public static func clamped(_ value: Double) -> Double {
        min(max(value, 0.5), 2)
    }

    /// `1×`, `1.25×`, `0.5×` - whole numbers lose the decimal.
    public static func label(_ value: Double) -> String {
        value.rounded() == value ? "\(Int(value))×" : "\(value)×"
    }
}

/// Android's seek guards in `PlayerConnection`: a request at or beyond
/// `duration - 250 ms` returns without seeking; the position is never clamped
/// to the tail.
public enum SeekPolicy {
    public static let endGuardMs: Int64 = 250

    public static func canSeek(positionMs: Int64, durationMs: Int64) -> Bool {
        guard durationMs > 0 else { return false }
        return positionMs < durationMs - endGuardMs
    }
}
