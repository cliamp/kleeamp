import AVFoundation
import Foundation
import Testing

@testable import CliampCore

@Suite("reconnect policy")
struct ReconnectPolicyTests {
    @Test("the ladder backs off 1/2/4/8/15 and then holds at 30 seconds")
    func ladder() {
        var policy = ReconnectPolicy()
        #expect(policy.scheduleRetry() == 1_000)
        #expect(policy.scheduleRetry() == 2_000)
        #expect(policy.scheduleRetry() == 4_000)
        #expect(policy.scheduleRetry() == 8_000)
        #expect(policy.scheduleRetry() == 15_000)
        #expect(policy.scheduleRetry() == 30_000)
        #expect(policy.scheduleRetry() == 30_000)
        #expect(policy.attempt == 7)
    }

    @Test("delivered audio resets the ladder")
    func reset() {
        var policy = ReconnectPolicy()
        _ = policy.scheduleRetry()
        _ = policy.scheduleRetry()
        policy.reset()
        #expect(policy.attempt == 0)
        #expect(policy.scheduleRetry() == 1_000)
    }

    @Test("a stall trips only at the 20-second timeout")
    func stall() {
        #expect(!ReconnectPolicy.isStalled(bufferingSinceMs: 0, nowMs: 19_999))
        #expect(ReconnectPolicy.isStalled(bufferingSinceMs: 0, nowMs: 20_000))
    }

    @Test("malformed containers and codecs are fatal, network errors are not")
    func recoverability() {
        let network = NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
        #expect(ReconnectPolicy.isRecoverable(network))

        let wrappedNetwork = NSError(
            domain: AVFoundationErrorDomain,
            code: AVError.unknown.rawValue,
            userInfo: [NSUnderlyingErrorKey: network]
        )
        #expect(ReconnectPolicy.isRecoverable(wrappedNetwork))

        let malformed = NSError(
            domain: AVFoundationErrorDomain, code: AVError.fileFormatNotRecognized.rawValue
        )
        #expect(!ReconnectPolicy.isRecoverable(malformed))

        let wrappedMalformed = NSError(
            domain: AVFoundationErrorDomain,
            code: AVError.unknown.rawValue,
            userInfo: [NSUnderlyingErrorKey: malformed]
        )
        #expect(!ReconnectPolicy.isRecoverable(wrappedMalformed))

        // A fatal outer error is fatal even when it wraps a transient one.
        let fatalOuter = NSError(
            domain: AVFoundationErrorDomain,
            code: AVError.fileFormatNotRecognized.rawValue,
            userInfo: [NSUnderlyingErrorKey: network]
        )
        #expect(!ReconnectPolicy.isRecoverable(fatalOuter))

        let unsupportedCodec = NSError(
            domain: AVFoundationErrorDomain, code: AVError.decoderNotFound.rawValue
        )
        #expect(!ReconnectPolicy.isRecoverable(unsupportedCodec))

        let missingFile = NSError(domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist)
        #expect(!ReconnectPolicy.isRecoverable(missingFile))
    }
}
