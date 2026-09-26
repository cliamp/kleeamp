import CliampCore
import Testing

@testable import Cliamp

@Suite("Cliamp app")
struct CliampAppTests {
    @Test("app target links CliampCore")
    func linksCorePackage() {
        #expect(CliampIdentity.displayName == "cliamp")
    }

    @Test("root shell is constructible")
    @MainActor
    func rootViewExists() {
        _ = RootView()
    }
}
