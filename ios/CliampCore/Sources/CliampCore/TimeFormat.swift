import Foundation

/// Shared ms -> `h:mm:ss`/`m:ss` clock, and large-number compaction.
public enum TimeFormat {
    /// `83_000` -> `1:23`; `3_700_000` -> `1:01:40`.
    public static func clock(_ ms: Int64) -> String {
        let total = max(0, ms / 1000)
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    /// Android's `durationLabel`: total minutes and seconds, never hours —
    /// `5_700_000` -> `95:00`, unknown -> `0:00`.
    public static func durationLabel(_ ms: Int64) -> String {
        let total = max(0, ms / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// `1_200_000` -> `1.2m`; `50_000` -> `50.0k`; small counts stay plain.
    public static func compact(_ n: Int) -> String {
        if n >= 1_000_000 { return String(format: "%.1fm", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.1fk", Double(n) / 1_000) }
        return String(n)
    }
}
