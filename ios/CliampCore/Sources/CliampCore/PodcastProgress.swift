import Foundation

/// Listening-position rules shared by playback and the episode list, ported
/// from `PodcastRepository.resumePosition` / `saveProgress`.
public enum PodcastProgress {
    /// Close enough to the end to call it listened.
    public static let nearEndMs: Int64 = 30_000

    /// Where playback should start: zero for a completed or nearly finished
    /// episode, otherwise the saved position. Live radio is not a track and
    /// always starts at zero.
    public static func resumePosition(_ progress: EpisodeProgress?) -> Int64 {
        guard let progress else { return 0 }
        if progress.completed { return 0 }
        if progress.durationMs > 0, progress.positionMs >= progress.durationMs - nearEndMs {
            return 0
        }
        return max(0, progress.positionMs)
    }

    /// A position within the near-end window means the episode is finished,
    /// so replaying it starts from zero instead of two seconds before credits.
    public static func isCompleted(positionMs: Int64, durationMs: Int64) -> Bool {
        durationMs > 0 && positionMs >= durationMs - nearEndMs
    }
}
