import SwiftUI

/// The palette is a contract, not a colour scheme: components name roles, and
/// every palette fills all thirty-two plus `dark`.
public struct CliampPalette: Equatable, Sendable {
    public let dark: Bool
    // grounds
    public let canvas: Color
    public let ground: Color
    public let groundScope: Color
    public let groundLock: Color
    public let panel: Color
    public let panelRaised: Color
    // lines
    public let hairline: Color
    public let hairlineRegion: Color
    public let frameBorder: Color
    // ink
    public let ink: Color
    public let inkBright: Color
    public let inkSecondary: Color
    public let inkTertiary: Color
    public let inkFaint: Color
    // accent
    public let accent: Color
    public let accentBright: Color
    public let accentBevel: Color
    public let onAccent: Color
    public let accentWash: Color
    // semantic
    public let amber: Color
    public let destructive: Color
    public let destructiveInk: Color
    // controls
    public let keyFace: Color
    public let keyBorder: Color
    public let keyBevel: Color
    public let chipBorder: Color
    public let track: Color
    public let unlit: Color
    public let peak: Color
    // placeholder art stripes
    public let artA: Color
    public let artB: Color
    public let artBorder: Color

    public init(
        dark: Bool,
        canvas: Color, ground: Color, groundScope: Color, groundLock: Color,
        panel: Color, panelRaised: Color,
        hairline: Color, hairlineRegion: Color, frameBorder: Color,
        ink: Color, inkBright: Color, inkSecondary: Color, inkTertiary: Color, inkFaint: Color,
        accent: Color, accentBright: Color, accentBevel: Color, onAccent: Color, accentWash: Color,
        amber: Color, destructive: Color, destructiveInk: Color,
        keyFace: Color, keyBorder: Color, keyBevel: Color, chipBorder: Color,
        track: Color, unlit: Color, peak: Color,
        artA: Color, artB: Color, artBorder: Color
    ) {
        self.dark = dark
        self.canvas = canvas
        self.ground = ground
        self.groundScope = groundScope
        self.groundLock = groundLock
        self.panel = panel
        self.panelRaised = panelRaised
        self.hairline = hairline
        self.hairlineRegion = hairlineRegion
        self.frameBorder = frameBorder
        self.ink = ink
        self.inkBright = inkBright
        self.inkSecondary = inkSecondary
        self.inkTertiary = inkTertiary
        self.inkFaint = inkFaint
        self.accent = accent
        self.accentBright = accentBright
        self.accentBevel = accentBevel
        self.onAccent = onAccent
        self.accentWash = accentWash
        self.amber = amber
        self.destructive = destructive
        self.destructiveInk = destructiveInk
        self.keyFace = keyFace
        self.keyBorder = keyBorder
        self.keyBevel = keyBevel
        self.chipBorder = chipBorder
        self.track = track
        self.unlit = unlit
        self.peak = peak
        self.artA = artA
        self.artB = artB
        self.artBorder = artBorder
    }
}

/// The five hand-built palettes. Values are ported verbatim from
/// `android/.../ui/theme/Palette.kt`; the twenty-two Omarchy palettes arrive
/// with VIS-07.
public enum CliampPalettes {
    public static let dark = CliampPalette(
        dark: true,
        canvas: Color(argb: 0xFF27_2A27), ground: Color(argb: 0xFF0A_0D0A),
        groundScope: Color(argb: 0xFF06_0806), groundLock: Color(argb: 0xFF04_0604),
        panel: Color(argb: 0xFF10_1411), panelRaised: Color(argb: 0xFF10_1511),
        hairline: Color(argb: 0xFF19_1D19), hairlineRegion: Color(argb: 0xFF21_2521),
        frameBorder: Color(argb: 0xFF39_3F3A),
        ink: Color(argb: 0xFFED_F4EE), inkBright: Color(argb: 0xFFF1_F7F2),
        inkSecondary: Color(argb: 0xFFA0_A7A1), inkTertiary: Color(argb: 0xFF87_8E88),
        inkFaint: Color(argb: 0xFF7C_847D),
        accent: Color(argb: 0xFF73_E889), accentBright: Color(argb: 0xFF9B_FFAB),
        accentBevel: Color(argb: 0xFF44_AA5A), onAccent: Color(argb: 0xFF06_1909),
        accentWash: Color(argb: 0xFF13_251A),
        amber: Color(argb: 0xFFEB_B353), destructive: Color(argb: 0xFFBD_423A),
        destructiveInk: Color(argb: 0xFFF2_7166),
        keyFace: Color(argb: 0xFF16_1B17), keyBorder: Color(argb: 0xFF2F_3530),
        keyBevel: Color(argb: 0xFF06_0907), chipBorder: Color(argb: 0xFF25_2B26),
        track: Color(argb: 0xFF26_2A26), unlit: Color(argb: 0xFF1E_2820),
        peak: Color(argb: 0xFFF1_F7F2),
        artA: Color(argb: 0xFF1A_201B), artB: Color(argb: 0xFF14_1815),
        artBorder: Color(argb: 0xFF2A_302B)
    )

    public static let light = CliampPalette(
        dark: false,
        canvas: Color(argb: 0xFFDB_DFDC), ground: Color(argb: 0xFFF4_F8F5),
        groundScope: Color(argb: 0xFFED_F1EE), groundLock: Color(argb: 0xFFE5_E9E6),
        panel: Color(argb: 0xFFE9_EEEA), panelRaised: Color(argb: 0xFFE8_EDE8),
        hairline: Color(argb: 0xFFD5_DBD6), hairlineRegion: Color(argb: 0xFFCB_D1CC),
        frameBorder: Color(argb: 0xFFB9_C0BA),
        ink: Color(argb: 0xFF1B_211C), inkBright: Color(argb: 0xFF11_150F),
        inkSecondary: Color(argb: 0xFF54_5A54), inkTertiary: Color(argb: 0xFF5D_635D),
        inkFaint: Color(argb: 0xFF66_6D67),
        accent: Color(argb: 0xFF00_7C2F), accentBright: Color(argb: 0xFF10_6B2C),
        accentBevel: Color(argb: 0xFF0D_5C25), onAccent: Color(argb: 0xFFF4_F8F5),
        accentWash: Color(argb: 0xFFCF_EED2),
        amber: Color(argb: 0xFFA1_5900), destructive: Color(argb: 0xFFBA_2B28),
        destructiveInk: Color(argb: 0xFFA3_2320),
        keyFace: Color(argb: 0xFFF4_F8F5), keyBorder: Color(argb: 0xFFC5_CCC6),
        keyBevel: Color(argb: 0xFFD5_DBD6), chipBorder: Color(argb: 0xFFC5_CCC6),
        track: Color(argb: 0xFFD5_DBD6), unlit: Color(argb: 0xFFD0_DDD2),
        peak: Color(argb: 0xFF1B_211C),
        artA: Color(argb: 0xFFE2_E8E3), artB: Color(argb: 0xFFED_F2ED),
        artBorder: Color(argb: 0xFFC5_CCC6)
    )

    public static let amber = CliampPalette(
        dark: true,
        canvas: Color(argb: 0xFF2D_2824), ground: Color(argb: 0xFF10_0B07),
        groundScope: Color(argb: 0xFF0A_0704), groundLock: Color(argb: 0xFF08_0502),
        panel: Color(argb: 0xFF17_120D), panelRaised: Color(argb: 0xFF19_120C),
        hairline: Color(argb: 0xFF21_1A15), hairlineRegion: Color(argb: 0xFF29_221D),
        frameBorder: Color(argb: 0xFF44_3B34),
        ink: Color(argb: 0xFFF7_F0EB), inkBright: Color(argb: 0xFFFA_F4EF),
        inkSecondary: Color(argb: 0xFFAA_A39E), inkTertiary: Color(argb: 0xFF92_8B86),
        inkFaint: Color(argb: 0xFF88_807B),
        accent: Color(argb: 0xFFF9_AA60), accentBright: Color(argb: 0xFFFF_C687),
        accentBevel: Color(argb: 0xFFB7_7534), onAccent: Color(argb: 0xFF21_0F01),
        accentWash: Color(argb: 0xFF2B_1D10),
        amber: Color(argb: 0xFFEB_B353), destructive: Color(argb: 0xFFBD_423A),
        destructiveInk: Color(argb: 0xFFF2_7166),
        keyFace: Color(argb: 0xFF1F_1812), keyBorder: Color(argb: 0xFF3A_312A),
        keyBevel: Color(argb: 0xFF0C_0704), chipBorder: Color(argb: 0xFF30_2720),
        track: Color(argb: 0xFF2E_2722), unlit: Color(argb: 0xFF2C_231B),
        peak: Color(argb: 0xFFFA_F4EF),
        artA: Color(argb: 0xFF25_1C15), artB: Color(argb: 0xFF1B_1611),
        artBorder: Color(argb: 0xFF35_2C25)
    )

    public static let oxide = CliampPalette(
        dark: true,
        canvas: Color(argb: 0xFF30_2725), ground: Color(argb: 0xFF12_0A08),
        groundScope: Color(argb: 0xFF0C_0605), groundLock: Color(argb: 0xFF0A_0403),
        panel: Color(argb: 0xFF1A_100E), panelRaised: Color(argb: 0xFF1C_100E),
        hairline: Color(argb: 0xFF24_1816), hairlineRegion: Color(argb: 0xFF2C_201E),
        frameBorder: Color(argb: 0xFF49_3936),
        ink: Color(argb: 0xFFF8_E4D4), inkBright: Color(argb: 0xFFF8_E4D4),
        inkSecondary: Color(argb: 0xFFAB_A39E), inkTertiary: Color(argb: 0xFF93_8A85),
        inkFaint: Color(argb: 0xFF88_807B),
        accent: Color(argb: 0xFFD1_5D4D), accentBright: Color(argb: 0xFFE4_7C6C),
        accentBevel: Color(argb: 0xFF7F_2117), onAccent: Color(argb: 0xFF24_0C09),
        accentWash: Color(argb: 0xFF2E_1B17),
        amber: Color(argb: 0xFFEB_B353), destructive: Color(argb: 0xFFBD_423A),
        destructiveInk: Color(argb: 0xFFF2_7166),
        keyFace: Color(argb: 0xFF23_1614), keyBorder: Color(argb: 0xFF3F_2F2C),
        keyBevel: Color(argb: 0xFF0E_0605), chipBorder: Color(argb: 0xFF35_2522),
        track: Color(argb: 0xFF31_2523), unlit: Color(argb: 0xFF2E_211F),
        peak: Color(argb: 0xFFF8_E4D4),
        artA: Color(argb: 0xFF29_1A17), artB: Color(argb: 0xFF1E_1412),
        artBorder: Color(argb: 0xFF3A_2A27)
    )

    public static let oxideLight = CliampPalette(
        dark: false,
        canvas: Color(argb: 0xFFDF_CBBB), ground: Color(argb: 0xFFF8_E4D4),
        groundScope: Color(argb: 0xFFF1_DDCD), groundLock: Color(argb: 0xFFE9_D5C5),
        panel: Color(argb: 0xFFF2_D9C4), panelRaised: Color(argb: 0xFFF4_D7BF),
        hairline: Color(argb: 0xFFE3_C4AB), hairlineRegion: Color(argb: 0xFFDA_BAA1),
        frameBorder: Color(argb: 0xFFCD_A889),
        ink: Color(argb: 0xFF25_1D1C), inkBright: Color(argb: 0xFF19_1110),
        inkSecondary: Color(argb: 0xFF55_4C4A), inkTertiary: Color(argb: 0xFF5F_5655),
        inkFaint: Color(argb: 0xFF69_5F5F),
        accent: Color(argb: 0xFF7F_2117), accentBright: Color(argb: 0xFF5C_1009),
        accentBevel: Color(argb: 0xFF4B_0703), onAccent: Color(argb: 0xFFEC_E7E6),
        accentWash: Color(argb: 0xFFF5_CAC3),
        amber: Color(argb: 0xFF87_5800), destructive: Color(argb: 0xFFBA_2B28),
        destructiveInk: Color(argb: 0xFFA3_2320),
        keyFace: Color(argb: 0xFFF8_E4D4), keyBorder: Color(argb: 0xFFD9_B495),
        keyBevel: Color(argb: 0xFFE3_C4AB), chipBorder: Color(argb: 0xFFD9_B495),
        track: Color(argb: 0xFFE3_C4AB), unlit: Color(argb: 0xFFD8_C6C3),
        peak: Color(argb: 0xFF25_1D1C),
        artA: Color(argb: 0xFFF0_D1B8), artB: Color(argb: 0xFFF9_DCC4),
        artBorder: Color(argb: 0xFFD9_B495)
    )
}

/// Resolves the stored theme preference the way `paletteFor` does in
/// `Theme.kt`. Unknown keys fall back to oxide rather than throwing.
public func cliampPalette(
    for preference: String,
    systemDark: Bool,
    custom: CliampPalette? = nil
) -> CliampPalette {
    let fallback = systemDark ? CliampPalettes.oxide : CliampPalettes.oxideLight
    switch preference {
    case "system": return fallback
    case "oxide": return CliampPalettes.oxide
    case "oxide-light": return CliampPalettes.oxideLight
    case "dark": return CliampPalettes.dark
    case "light": return CliampPalettes.light
    case "amber": return CliampPalettes.amber
    case "custom": return custom ?? fallback
    default: return OmarchyPalettes.palettes[preference] ?? fallback
    }
}
