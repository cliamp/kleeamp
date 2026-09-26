import SwiftUI
import Testing

@testable import CliampDesign

@Suite("palette contract")
struct CliampPaletteTests {
    @Test("alpha-first eight-digit hex keeps its alpha")
    func alphaFirstHex() {
        let color = Color(cliampHex: "#80445566")
        #expect(color != nil)
        // Compared role-by-role against the six-digit form plus opacity.
        #expect(color != Color(cliampHex: "#445566"))
    }

    @Test("six-digit hex is opaque")
    func sixDigitHex() {
        #expect(Color(cliampHex: "#445566") == Color(argb: 0xFF44_5566))
    }

    @Test("malformed hex is rejected")
    func malformedHex() {
        #expect(Color(cliampHex: "445566") == nil)
        #expect(Color(cliampHex: "#12345") == nil)
        #expect(Color(cliampHex: "#ZZZZZZ") == nil)
    }

    @Test("system resolves to oxide by device mode")
    func systemResolution() {
        #expect(cliampPalette(for: "system", systemDark: true) == CliampPalettes.oxide)
        #expect(cliampPalette(for: "system", systemDark: false) == CliampPalettes.oxideLight)
    }

    @Test("unknown keys fall back to oxide rather than throwing")
    func unknownFallback() {
        #expect(cliampPalette(for: "not-a-theme", systemDark: true) == CliampPalettes.oxide)
        #expect(cliampPalette(for: "custom", systemDark: true) == CliampPalettes.oxide)
        #expect(
            cliampPalette(for: "custom", systemDark: false, custom: CliampPalettes.amber)
                == CliampPalettes.amber
        )
    }

    @Test("ported role values match the Android literals")
    func portedValues() {
        #expect(CliampPalettes.oxide.accent == Color(argb: 0xFFD1_5D4D))
        #expect(CliampPalettes.oxide.ground == Color(argb: 0xFF12_0A08))
        #expect(CliampPalettes.oxideLight.ground == Color(argb: 0xFFF8_E4D4))
        #expect(CliampPalettes.dark.accent == Color(argb: 0xFF73_E889))
        #expect(CliampPalettes.amber.accent == Color(argb: 0xFFF9_AA60))
        #expect(CliampPalettes.light.accent == Color(argb: 0xFF00_7C2F))
    }
}

@Suite("svg path parser")
struct SVGPathTests {
    @Test("absolute rectangle path builds its bounding box")
    func rectangle() {
        let path = SVGPath.parse("M0 0h10v10h-10z")
        let bounds = path.boundingRect
        #expect(abs(bounds.minX) < 0.01)
        #expect(abs(bounds.minY) < 0.01)
        #expect(abs(bounds.width - 10) < 0.01)
        #expect(abs(bounds.height - 10) < 0.01)
    }

    @Test("two half arcs build a full circle")
    func circle() {
        let path = SVGPath.parse("M0 5a5 5 0 1 1 10 0a5 5 0 1 1 -10 0z")
        let bounds = path.boundingRect
        #expect(abs(bounds.minX) < 0.05)
        #expect(abs(bounds.minY) < 0.05)
        #expect(abs(bounds.width - 10) < 0.05)
        #expect(abs(bounds.height - 10) < 0.05)
    }

    @Test("sweep flag chooses the arc side")
    func sweepDirection() {
        let clockwise = SVGPath.parse("M0 5a5 5 0 0 1 10 0").boundingRect
        let counter = SVGPath.parse("M0 5a5 5 0 0 0 10 0").boundingRect
        // sweep=1 arcs over the top; sweep=0 under the bottom.
        #expect(abs(clockwise.minY - 0) < 0.05)
        #expect(abs(counter.minY - 5) < 0.05)
    }

    @Test("numbers may run together without spaces")
    func tightNumbers() {
        let path = SVGPath.parse("M14.5 9.5l1.5 1.5-1.5 1.5")
        #expect(abs(path.boundingRect.width - 1.5) < 0.05)
        #expect(abs(path.boundingRect.maxX - 16) < 0.05)
    }

    @Test("adjacent decimals stay two numbers")
    func adjacentDecimals() {
        let path = SVGPath.parse("M8 1.5l1.9 4.2 4.6.5-3.4 3.1.9 4.5L8 11.6 4 13.8l.9-4.5L1.5 6.2l4.6-.5z")
        let bounds = path.boundingRect
        #expect(abs(bounds.minX - 1.5) < 0.05)
        #expect(abs(bounds.width - 13) < 0.05)
        #expect(abs(bounds.height - 12.3) < 0.05)
    }

    @Test("rounded rect keeps its declared bounds")
    func roundedRect() {
        let path = SVGPath.parse("M3 0h2a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z")
        let bounds = path.boundingRect
        #expect(abs(bounds.width - 4) < 0.05)
        #expect(abs(bounds.height - 4) < 0.05)
    }
}

@Suite("icon set")
struct CliampIconTests {
    @Test("the mark keeps its six spectrum bars")
    func markBars() {
        #expect(CliampIcons.mark.shapes.count == 6)
        #expect(CliampIcons.mark.width == 48)
        #expect(CliampIcons.mark.height == 48)
    }

    @Test("the stations mark carries baseline, transmitter and three domes")
    func stationsMark() {
        #expect(CliampIcons.stationsTab.shapes.count == 5)
    }

    @Test("fill and stroke survive the port")
    func shapeKinds() {
        guard case .fill = CliampIcons.playRow.shapes.first else {
            Issue.record("playRow should fill")
            return
        }
        guard case .stroke(_, let width) = CliampIcons.star.shapes.first else {
            Issue.record("star should stroke")
            return
        }
        #expect(abs(width - 1.8) < 0.001)
    }
}
