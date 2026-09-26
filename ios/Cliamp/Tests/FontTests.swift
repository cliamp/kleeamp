import Testing
import UIKit

@testable import Cliamp

@Suite("bundled fonts")
struct FontTests {
    @Test("all six faces register with the app")
    func fontsRegister() {
        let faces = [
            "Poppins-Regular", "Poppins-Medium", "Poppins-Bold",
            "JetBrainsMono-Regular", "JetBrainsMono-Medium", "JetBrainsMono-Bold",
        ]
        for face in faces {
            #expect(UIFont(name: face, size: 12) != nil, "\(face) missing from the bundle")
        }
    }
}
