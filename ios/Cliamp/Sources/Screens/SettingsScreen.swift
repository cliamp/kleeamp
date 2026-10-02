import CliampCore
import CliampDesign
import SwiftUI

struct SettingsScreen: View {
    @Environment(\.cliampPalette) private var palette
    @Environment(\.colorScheme) private var systemScheme
    let app: AppState
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            CliampHeader("Settings", onBack: onBack)
            ScrollView {
                VStack(spacing: 0) {
                    SectionLabel("playback")
                    ToggleRow(title: "Auto-resume", checked: app.autoResume) {
                        app.autoResume = $0
                    }
                    ToggleRow(title: "Resume local songs", checked: app.resumeLocalSongs) {
                        app.resumeLocalSongs = $0
                    }
                    ToggleRow(title: "Auto-download episodes", checked: app.autoDownload) {
                        app.autoDownload = $0
                    }
                    ToggleRow(title: "Stream over cellular", checked: app.cellular) {
                        app.cellular = $0
                    }
                    ToggleRow(title: "Mono downmix", checked: app.mono) {
                        app.mono = $0
                    }
                    bufferRow

                    SectionLabel("scrobble")
                    InfoCaretRow(title: "ListenBrainz", value: "off")
                        .opacity(0.5)

                    SectionLabel("feel")
                    ToggleRow(title: "Key haptics", checked: app.haptics) {
                        app.haptics = $0
                    }
                    ChoiceRow(
                        title: "Visualizer",
                        options: ["spectrum", "off"],
                        selected: app.visualizer
                    ) { app.visualizer = $0 }

                    SectionLabel("themes — \(themeRows.count)")
                    ForEach(themeRows, id: \.key) { row in
                        ThemeRow(
                            key: row.key,
                            theme: row.palette,
                            selected: app.palettePreference == row.key,
                            subtitle: row.key == "system" ? "follows the device" : nil
                        ) {
                            app.palettePreference = row.key
                        }
                    }

                    SectionLabel("storage")
                    InfoRow(title: "Favourites", value: favouriteSummary)
                    InfoRow(title: "History", value: "0 entries")
                    PurgeRow()

                    versionRow
                }
            }
        }
        .background(palette.ground)
    }

    private var favouriteSummary: String {
        let count = app.favoriteURLs.count
        return "\(count) \(count == 1 ? "station" : "stations")"
    }

    private var themeRows: [(key: String, palette: CliampPalette)] {
        var rows: [(key: String, palette: CliampPalette)] = [
            ("system", systemScheme == .dark ? CliampPalettes.oxide : CliampPalettes.oxideLight),
            ("oxide", CliampPalettes.oxide),
            ("oxide-light", CliampPalettes.oxideLight),
            ("amber", CliampPalettes.amber),
            ("dark", CliampPalettes.dark),
            ("light", CliampPalettes.light),
        ]
        rows.append(
            contentsOf: OmarchyPalettes.keys.compactMap { key in
                OmarchyPalettes.palettes[key].map { (key, $0) }
            }
        )
        return rows
    }

    private var bufferRow: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Buffer")
                        .cliampText(CliampType.rowPrimary)
                        .foregroundStyle(palette.ink)
                    Spacer()
                    Text("\(Int(app.bufferSeconds))s")
                        .cliampText(CliampType.rowSecondary)
                        .foregroundStyle(palette.accent)
                }
                MechSlider(value: app.bufferSeconds, range: 5...60) { app.bufferSeconds = $0 }
                Text("deeper buffers survive a bad tunnel, at the cost of latency")
                    .cliampText(CliampType.meta)
                    .foregroundStyle(palette.inkFaint)
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 12)
            HairlineDivider()
        }
    }

    private var versionRow: some View {
        HStack(spacing: 12) {
            CliampIcon(CliampIcons.mark, size: 34, tint: palette.accent)
            VStack(alignment: .leading, spacing: 3) {
                Text("cliamp \(Bundle.main.shortVersion) · all rights reserved")
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.inkSecondary)
                Text("cliamp.stream · radio-browser.info")
                    .cliampText(CliampType.meta)
                    .foregroundStyle(palette.inkFaint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, cliampGutter)
        .padding(.vertical, 18)
    }
}

private struct ToggleRow: View {
    @Environment(\.cliampPalette) private var palette
    let title: String
    let checked: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .cliampText(CliampType.rowPrimaryMedium)
                    .foregroundStyle(palette.ink)
                Spacer()
                CliampToggle(on: checked, onChange: onChange)
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 12)
            HairlineDivider().padding(.leading, cliampGutter)
        }
    }
}

private struct ChoiceRow: View {
    @Environment(\.cliampPalette) private var palette
    let title: String
    let options: [String]
    let selected: String
    let onSelect: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .cliampText(CliampType.rowPrimary)
                    .foregroundStyle(palette.ink)
                Spacer()
                HStack(spacing: 6) {
                    ForEach(options, id: \.self) { option in
                        Chip(option, selected: selected == option) {
                            onSelect(option)
                        }
                    }
                }
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 12)
            HairlineDivider()
        }
    }
}

private struct ThemeRow: View {
    @Environment(\.cliampPalette) private var palette
    let key: String
    let theme: CliampPalette
    let selected: Bool
    let subtitle: String?
    let action: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                HStack(spacing: 10) {
                    HStack(spacing: 3) {
                        ForEach([theme.accent, theme.ink, theme.amber], id: \.self) { color in
                            RoundedRectangle(cornerRadius: 2)
                                .fill(color)
                                .frame(width: 8, height: 8)
                        }
                    }
                    .padding(4)
                    .background(theme.ground)
                    .clipShape(RoundedRectangle(cornerRadius: CliampShape.tiny))
                    .overlay(
                        RoundedRectangle(cornerRadius: CliampShape.tiny)
                            .stroke(theme.frameBorder, lineWidth: 1)
                    )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(key.replacingOccurrences(of: "-", with: " "))
                            .cliampText(selected ? CliampType.rowPrimaryMedium : CliampType.rowPrimary)
                            .foregroundStyle(selected ? palette.accent : palette.ink)
                            .lineLimit(1)
                        Text(subtitle ?? (theme.dark ? "dark" : "light"))
                            .cliampText(CliampType.meta)
                            .foregroundStyle(palette.inkFaint)
                    }
                }
                Spacer()
                if selected {
                    Text("ACTIVE")
                        .cliampText(CliampType.tabLabel)
                        .foregroundStyle(palette.accent)
                }
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
            .onTapGesture(perform: action)
            HairlineDivider().padding(.leading, cliampGutter)
        }
    }
}

private struct InfoRow: View {
    @Environment(\.cliampPalette) private var palette
    let title: String
    let value: String

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .cliampText(CliampType.rowPrimary)
                    .foregroundStyle(palette.ink)
                Spacer()
                Text(value)
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.inkTertiary)
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 13)
            HairlineDivider().padding(.leading, cliampGutter)
        }
    }
}

private struct InfoCaretRow: View {
    @Environment(\.cliampPalette) private var palette
    let title: String
    let value: String

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .cliampText(CliampType.rowPrimaryMedium)
                    .foregroundStyle(palette.ink)
                Spacer()
                HStack(spacing: 8) {
                    Text(value)
                        .cliampText(CliampType.meta)
                        .foregroundStyle(palette.inkFaint)
                    CliampIcon(CliampIcons.caretRight, size: 11, tint: palette.inkTertiary)
                }
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 13)
            HairlineDivider()
        }
    }
}

private struct PurgeRow: View {
    @Environment(\.cliampPalette) private var palette

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Purge history")
                    .cliampText(CliampType.rowPrimary)
                    .foregroundStyle(palette.destructiveInk)
                Spacer()
                Text("▸")
                    .cliampText(CliampType.rowPrimary)
                    .foregroundStyle(palette.destructiveInk)
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 13)
            .opacity(0.4)
            .contentShape(Rectangle())
            HairlineDivider()
        }
    }
}

extension Bundle {
    var shortVersion: String {
        infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.1"
    }
}
