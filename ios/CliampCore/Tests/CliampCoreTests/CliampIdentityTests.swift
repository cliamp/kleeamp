import Testing

@testable import CliampCore

@Suite("Cliamp identity")
struct CliampIdentityTests {
    @Test("shares the Android application id")
    func bundleIdentifierMatchesAndroid() {
        #expect(CliampIdentity.bundleIdentifier == "stream.cliamp.mobile")
    }

    @Test("keeps the lower-case product name")
    func displayNameIsLowerCase() {
        #expect(CliampIdentity.displayName == "cliamp")
    }
}
