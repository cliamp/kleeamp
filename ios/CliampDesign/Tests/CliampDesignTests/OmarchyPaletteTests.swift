import SwiftUI
import Testing

@testable import CliampDesign

@Suite("omarchy palettes")
struct OmarchyPaletteTests {
    @Test("the generated map carries every Omarchy theme")
    func count() {
        #expect(OmarchyPalettes.palettes.count == 22)
        #expect(OmarchyPalettes.keys.count == 22)
        #expect(OmarchyPalettes.keys.first == "catppuccin")
        #expect(OmarchyPalettes.keys == OmarchyPalettes.keys.sorted())
    }

    @Test("ported values match the Android source")
    func spotValues() {
        #expect(OmarchyPalettes.palettes["catppuccin"]?.accent == Color(argb: 0xFF89B4FA))
        #expect(OmarchyPalettes.palettes["catppuccin"]?.ground == Color(argb: 0xFF1E1E2E))
        #expect(OmarchyPalettes.palettes["white"]?.ground == Color(argb: 0xFFFFFFFF))
        #expect(OmarchyPalettes.palettes["white"]?.dark == false)
        #expect(OmarchyPalettes.palettes["vantablack"]?.dark == true)
    }

    @Test("the resolver serves Omarchy keys and still falls back safely")
    func resolution() {
        #expect(
            cliampPalette(for: "catppuccin", systemDark: true)
                == OmarchyPalettes.palettes["catppuccin"]
        )
        #expect(cliampPalette(for: "not-a-theme", systemDark: true) == CliampPalettes.oxide)
        #expect(cliampPalette(for: "not-a-theme", systemDark: false) == CliampPalettes.oxideLight)
    }
}
