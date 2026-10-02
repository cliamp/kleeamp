import SwiftUI

private struct CliampPaletteKey: EnvironmentKey {
    static let defaultValue = CliampPalettes.oxide
}

private struct CliampHapticsKey: EnvironmentKey {
    static let defaultValue = true
}

public extension EnvironmentValues {
    var cliampPalette: CliampPalette {
        get { self[CliampPaletteKey.self] }
        set { self[CliampPaletteKey.self] = newValue }
    }

    /// Read by every mechanical control, so one setting silences the app.
    var cliampHapticsEnabled: Bool {
        get { self[CliampHapticsKey.self] }
        set { self[CliampHapticsKey.self] = newValue }
    }
}

private struct CliampTheme: ViewModifier {
    let palette: CliampPalette

    func body(content: Content) -> some View {
        content
            .environment(\.cliampPalette, palette)
            .background(palette.ground)
            .preferredColorScheme(palette.dark ? .dark : .light)
    }
}

public extension View {
    func cliampTheme(_ palette: CliampPalette) -> some View {
        modifier(CliampTheme(palette: palette))
    }
}
