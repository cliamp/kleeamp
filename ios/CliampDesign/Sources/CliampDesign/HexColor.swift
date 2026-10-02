import SwiftUI

public extension Color {
    /// Alpha-first ARGB, matching the Android palette literals (`0xFF272A27`).
    init(argb: UInt32) {
        self.init(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }

    /// `#RRGGBB` or alpha-first `#AARRGGBB`, the two forms the theme importer
    /// accepts. Returns nil for anything else, including a missing `#`.
    init?(cliampHex hex: String) {
        var value = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.hasPrefix("#") else { return nil }
        value.removeFirst()
        guard value.count == 6 || value.count == 8, let bits = UInt32(value, radix: 16) else {
            return nil
        }
        self.init(argb: value.count == 6 ? 0xFF00_0000 | bits : bits)
    }
}
