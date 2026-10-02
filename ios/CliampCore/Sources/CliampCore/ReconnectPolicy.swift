import AVFoundation
import Foundation

/// The reconnect ladder for live radio: the same thresholds Android's
/// `Reconnector` uses. A hard failure backs off 1/2/4/8/15 seconds and then
/// every 30; a silent stall trips the 20-second watchdog; coming back into
/// signal retries immediately instead of waiting out the backoff.
public struct ReconnectPolicy: Sendable {
    /// Seconds between retries; the last value repeats.
    public static let backoffSeconds: [Int64] = [1, 2, 4, 8, 15, 30]
    /// A stream that stops delivering without error is retried after this.
    public static let stallTimeoutMs: Int64 = 20_000
    /// How often the watchdog checks for a stall.
    public static let watchdogIntervalMs: Int64 = 2_000

    public private(set) var attempt = 0

    public init() {}

    /// The wait in milliseconds for the next attempt, advancing the ladder.
    public mutating func scheduleRetry() -> Int64 {
        let seconds = Self.backoffSeconds[min(attempt, Self.backoffSeconds.count - 1)]
        attempt += 1
        return seconds * 1000
    }

    /// A stream that actually delivers audio resets the ladder.
    public mutating func reset() {
        attempt = 0
    }

    /// True once playback has been buffer-stalled for the timeout.
    public static func isStalled(bufferingSinceMs: Int64, nowMs: Int64) -> Bool {
        nowMs - bufferingSinceMs >= stallTimeoutMs
    }

    /// Everything except a malformed container or an unsupported codec is
    /// worth another go. Retrying a stream the decoder cannot play would just
    /// spin; Android draws the same line in `Reconnector.recoverable`.
    public static func isRecoverable(_ error: NSError) -> Bool {
        // A known fatal cause poisons the whole chain, wrappers included.
        if isFatal(error) { return false }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError {
            return isRecoverable(underlying)
        }
        return true
    }

    private static func isFatal(_ error: NSError) -> Bool {
        switch (error.domain, error.code) {
        case (AVFoundationErrorDomain, AVError.fileFormatNotRecognized.rawValue),
             (AVFoundationErrorDomain, AVError.fileFailedToParse.rawValue),
             (AVFoundationErrorDomain, AVError.decoderNotFound.rawValue),
             (NSURLErrorDomain, NSURLErrorFileDoesNotExist):
            return true
        default:
            return false
        }
    }
}
