import CliampCore
import Foundation
import CliampDesign
import SwiftUI

/// The first tab: cliamp's own channels, hand-added stations, and the paged
/// community directory with its order, tag and country filters.
struct StationsScreen: View {
    @Environment(\.cliampPalette) private var palette
    let player: RadioPlayer
    let app: AppState
    let onOpenSettings: () -> Void
    let onOpenSearch: () -> Void

    private let model = StationsServices.shared.model
    @State private var filter: StationFilter = .all
    @State private var addingCustom = false
    @State private var customName = ""
    @State private var customURL = ""

    enum StationFilter: String, CaseIterable, Identifiable {
        case all
        case cliamp
        case directory
        case custom

        var id: String { rawValue }
    }

    var body: some View {
        VStack(spacing: 0) {
            CliampHeader("Stations", onSearch: onOpenSearch, onSettings: onOpenSettings) {
                chips
            }
            ScrollView {
                LazyVStack(spacing: 0) {
                    if filter == .all || filter == .cliamp {
                        cliampSection
                    }
                    if filter == .all || filter == .custom {
                        customSection
                    }
                    if filter == .all || filter == .directory {
                        directorySection
                    }
                    Spacer().frame(height: 20)
                }
            }
        }
        .background(palette.ground)
        .task {
            await model.start()
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-cliamp-preview-directory") {
                filter = .directory
            }
            if ProcessInfo.processInfo.arguments.contains("-cliamp-preview-custom") {
                filter = .custom
                addingCustom = true
            }
            #endif
        }
    }

    @ViewBuilder
    private var chips: some View {
        ForEach(StationFilter.allCases) { option in
            Chip(option.rawValue, selected: filter == option) {
                filter = option
            }
        }
        Spacer().frame(width: 4)
        countryMenu
    }

    private var countryMenu: some View {
        Menu {
            Button("all countries") {
                model.loadDirectory(.topVoted, reset: true)
            }
            ForEach(model.countries, id: \.iso3166) { country in
                Button(country.name) {
                    model.loadDirectory(
                        .country(code: country.iso3166, name: country.name), reset: true
                    )
                }
            }
        } label: {
            HStack(spacing: 5) {
                Text((model.countryName ?? "all countries").uppercased())
                    .cliampText(CliampType.chip)
                    .foregroundStyle(model.countryName == nil ? palette.inkTertiary : palette.onAccent)
                    .lineLimit(1)
                CliampIcon(
                    CliampIcons.caretDown, size: 8,
                    tint: model.countryName == nil ? palette.inkTertiary : palette.onAccent
                )
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(model.countryName == nil ? .clear : (palette.dark ? palette.accent : palette.ink))
            .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
            .overlay {
                if model.countryName == nil {
                    RoundedRectangle(cornerRadius: CliampShape.small)
                        .stroke(palette.chipBorder, lineWidth: 1)
                }
            }
        }
    }

    // MARK: cliamp

    @ViewBuilder
    private var cliampSection: some View {
        SectionLabel("cliamp radio — \(model.cliamp.count)") {
            GridListToggle(gridMode: model.cliampGrid) { model.cliampGrid.toggle() }
        }
        if model.cliamp.isEmpty {
            EmptyNote("loading…")
        } else if model.cliampGrid {
            grid(model.cliamp, contextKey: "cliamp")
        } else {
            ForEach(model.cliamp) { station in
                StationRow(
                    station: station,
                    active: player.station?.url == station.url,
                    playing: player.playing,
                    favorite: app.isFavorite(station),
                    action: { play(station, from: model.cliamp, contextKey: "cliamp") },
                    onToggleFavorite: { app.toggleFavorite(station) }
                )
            }
        }
    }

    // MARK: custom

    @ViewBuilder
    private var customSection: some View {
        SectionLabel("custom — \(model.custom.count)") {
            HStack(spacing: 12) {
                if !addingCustom {
                    CliampIcon(CliampIcons.plus, size: 16, tint: palette.accent)
                        .frame(width: 34, height: 34)
                        .background(palette.dark ? palette.keyFace : palette.ground)
                        .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
                        .overlay(
                            RoundedRectangle(cornerRadius: CliampShape.small)
                                .stroke(palette.keyBorder, lineWidth: 1)
                        )
                        .microPress { addingCustom = true }
                }
                GridListToggle(gridMode: model.customGrid) { model.customGrid.toggle() }
            }
        }
        if addingCustom {
            CustomAddForm(
                name: $customName,
                url: $customURL,
                onCancel: {
                    addingCustom = false
                    customName = ""
                    customURL = ""
                },
                onSave: {
                    if model.addCustom(name: customName, url: customURL) {
                        addingCustom = false
                        customName = ""
                        customURL = ""
                    }
                }
            )
        }
        if model.customGrid {
            grid(model.custom, contextKey: "custom", removable: true)
        } else {
            ForEach(model.custom) { station in
                CustomStationRow(
                    station: station,
                    active: player.station?.url == station.url,
                    playing: player.playing,
                    favorite: app.isFavorite(station),
                    action: { play(station, from: model.custom, contextKey: "custom") },
                    onToggleFavorite: { app.toggleFavorite(station) },
                    onRemove: { model.removeCustom(station) }
                )
            }
        }
    }

    // MARK: directory

    @ViewBuilder
    private var directorySection: some View {
        SectionLabel("directory — \(directoryCount)", gutter: cliampGutter) {
            HStack(spacing: 12) {
                Text(model.directoryQuery.label)
                    .cliampText(CliampType.meta)
                    .foregroundStyle(palette.inkTertiary)
                GridListToggle(gridMode: model.directoryGrid) { model.directoryGrid.toggle() }
            }
        }
        directoryFilters
        if model.directory.isEmpty, model.directoryLoading {
            EmptyNote("loading…")
        } else if model.directoryGrid {
            grid(model.directory, contextKey: "directory:\(model.directoryQuery)", paginate: true)
        } else {
            ForEach(model.directory) { station in
                StationRow(
                    station: station,
                    active: player.station?.url == station.url,
                    playing: player.playing,
                    favorite: app.isFavorite(station),
                    action: { play(station, from: model.directory, contextKey: "directory:\(model.directoryQuery)") },
                    onToggleFavorite: { app.toggleFavorite(station) }
                )
                .onAppear { model.nextPageIfNeeded(current: station) }
            }
        }
        directoryFooter
    }

    private var directoryCount: String {
        guard let stats = model.directoryStats else { return "loading" }
        return stats.playable.formatted(.number.grouping(.automatic))
    }

    private var directoryFilters: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 7) {
                Chip("top", selected: model.directoryQuery == .topVoted) {
                    model.loadDirectory(.topVoted, reset: true)
                }
                Chip("trending", selected: model.directoryQuery == .trending) {
                    model.loadDirectory(.trending, reset: true)
                }
                ForEach(model.tags, id: \.name) { tag in
                    Chip(
                        tag.name,
                        selected: model.directoryQuery == .tag(tag.name)
                    ) {
                        model.loadDirectory(.tag(tag.name), reset: true)
                    }
                }
            }
            .padding(.leading, cliampGutter)
            .padding(.trailing, cliampGutter)
        }
        .scrollIndicators(.hidden)
        .padding(.top, 4)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private var directoryFooter: some View {
        if let error = model.directoryError {
            RetryNote("couldn't fetch the directory", prominent: model.directory.isEmpty) {
                _ = error
                model.loadDirectory(model.directoryQuery, reset: true)
            }
        } else if model.directoryLoading, !model.directory.isEmpty {
            EmptyNote("loading more…")
        } else if model.directoryExhausted, !model.directory.isEmpty {
            EmptyNote("end of \(model.directoryQuery.label)")
        }
    }

    // MARK: shared pieces

    @ViewBuilder
    private func grid(
        _ stations: [Station],
        contextKey: String,
        removable: Bool = false,
        paginate: Bool = false
    ) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
            ForEach(stations) { station in
                StationTile(
                    station: station,
                    active: player.station?.url == station.url,
                    playing: player.playing,
                    favorite: app.isFavorite(station),
                    subtitle: station.source == .custom ? "custom" : "cliamp",
                    action: { play(station, from: stations, contextKey: contextKey) },
                    onToggleFavorite: { app.toggleFavorite(station) },
                    onRemove: removable ? { model.removeCustom(station) } : nil
                )
                .onAppear {
                    if paginate { model.nextPageIfNeeded(current: station) }
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 2)
    }

    private func play(_ station: Station, from list: [Station], contextKey: String? = nil) {
        model.reportPlay(station)
        player.play(station, from: list, contextKey: contextKey)
    }
}

private struct StationRow: View {
    @Environment(\.cliampPalette) private var palette
    let station: Station
    let active: Bool
    let playing: Bool
    let favorite: Bool
    let action: () -> Void
    let onToggleFavorite: () -> Void

    var body: some View {
        ListRow(
            onClick: action,
            leading: { StationThumb(station: station, active: active, playing: playing) },
            trailing: {
                HStack(spacing: 12) {
                    if station.votes > 0 {
                        Text(TimeFormat.compact(station.votes))
                            .cliampText(CliampType.meta)
                            .foregroundStyle(palette.inkFaint)
                    }
                    FavoriteStar(favorite: favorite, action: onToggleFavorite)
                }
            },
            verticalPadding: 9,
            rail: active,
            railOffset: cliampGutter
        ) {
            Text(station.name)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(active ? palette.accent : palette.ink)
                .lineLimit(1)
            Text(subtitle)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }

    private var subtitle: String {
        var parts: [String] = []
        switch station.source {
        case .cliamp: parts.append("cliamp radio")
        case .custom: parts.append("custom station")
        default: break
        }
        if !station.meta.isEmpty { parts.append(station.meta) }
        parts.append(contentsOf: station.tagList.prefix(2))
        return parts.joined(separator: " · ")
    }
}

private struct CustomStationRow: View {
    @Environment(\.cliampPalette) private var palette
    let station: Station
    let active: Bool
    let playing: Bool
    let favorite: Bool
    let action: () -> Void
    let onToggleFavorite: () -> Void
    let onRemove: () -> Void

    var body: some View {
        ListRow(
            onClick: action,
            leading: { StationThumb(station: station, active: active, playing: playing) },
            trailing: {
                HStack(spacing: 12) {
                    FavoriteStar(favorite: favorite, action: onToggleFavorite)
                    Menu {
                        Button("remove station", role: .destructive, action: onRemove)
                    } label: {
                        CliampIcon(CliampIcons.more, size: 16, tint: palette.ink)
                            .frame(width: 30, height: 30)
                            .contentShape(Rectangle())
                    }
                }
            },
            verticalPadding: 9,
            rail: active,
            railOffset: cliampGutter
        ) {
            Text(station.name)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(active ? palette.accent : palette.ink)
                .lineLimit(1)
            Text("custom station")
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }
}

private struct StationTile: View {
    @Environment(\.cliampPalette) private var palette
    let station: Station
    let active: Bool
    let playing: Bool
    let favorite: Bool
    let subtitle: String
    let action: () -> Void
    let onToggleFavorite: () -> Void
    var onRemove: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            StationArtSquare(station: station)
                .overlay(alignment: .topTrailing) {
                    FavoriteStar(favorite: favorite, action: onToggleFavorite)
                        .padding(10)
                }
                .overlay(alignment: .topLeading) {
                    if let onRemove {
                        Menu {
                            Button("remove station", role: .destructive, action: onRemove)
                        } label: {
                            CliampIcon(CliampIcons.more, size: 16, tint: palette.ink)
                                .frame(width: 30, height: 30)
                                .contentShape(Rectangle())
                        }
                        .padding(10)
                    }
                }
                .overlay {
                    if active {
                        RoundedRectangle(cornerRadius: CliampShape.medium)
                            .stroke(palette.accent, lineWidth: 2)
                    }
                }
            Text(station.name)
                .cliampText(CliampType.rowPrimaryMedium)
                .foregroundStyle(palette.ink)
                .lineLimit(2)
            Text(subtitle)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 2)
        .clampTap(action: action)
    }
}

/// A station row's leading thumbnail: the broadcast mark on a themed plate.
private struct StationThumb: View {
    @Environment(\.cliampPalette) private var palette
    let station: Station
    let active: Bool
    let playing: Bool

    var body: some View {
        StationArtView(station: station, size: 40, fallback: .glyph)
            .overlay {
                if active {
                    RoundedRectangle(cornerRadius: CliampShape.small)
                        .stroke(palette.accent, lineWidth: 1)
                }
            }
            .overlay {
                if active {
                    RoundedRectangle(cornerRadius: CliampShape.tiny)
                        .fill(palette.accent.opacity(0.92))
                        .overlay(
                            CliampIcon(
                                playing ? CliampIcons.pause : CliampIcons.playRow,
                                size: 9,
                                tint: palette.onAccent
                            )
                        )
                        .frame(width: 18, height: 18)
                }
            }
    }
}

private struct FavoriteStar: View {
    @Environment(\.cliampPalette) private var palette
    let favorite: Bool
    let action: () -> Void

    var body: some View {
        CliampIcon(
            favorite ? CliampIcons.starFilled : CliampIcons.star,
            size: 15,
            tint: favorite ? palette.accent : palette.inkFaint
        )
        .frame(width: 30, height: 30)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityLabel(favorite ? "remove favourite" : "favourite")
    }
}

/// Name + URL form for a hand-added station, in the provider wizard's field
/// styling: label row, entry, and a hairline underline that accents on focus.
private struct CustomAddForm: View {
    @Environment(\.cliampPalette) private var palette
    @Binding var name: String
    @Binding var url: String
    let onCancel: () -> Void
    let onSave: () -> Void

    @FocusState private var focused: Field?

    private enum Field {
        case name
        case url
    }

    var body: some View {
        VStack(spacing: 0) {
            field(
                label: "name",
                optional: true,
                text: $name,
                placeholder: "name",
                field: .name,
                submitLabel: .next,
                keyboard: .default
            ) {
                focused = .url
            }
            field(
                label: "stream url",
                optional: false,
                text: $url,
                placeholder: "stream url",
                field: .url,
                submitLabel: .go,
                keyboard: .URL
            ) {
                onSave()
            }
            HStack(spacing: 7) {
                Spacer()
                Chip("cancel", selected: false, action: onCancel)
                Chip("save", selected: !url.trimmingCharacters(in: .whitespaces).isEmpty, action: onSave)
            }
            .padding(.vertical, 10)
        }
        .padding(.horizontal, cliampGutter)
    }

    private func field(
        label: String,
        optional: Bool,
        text: Binding<String>,
        placeholder: String,
        field: Field,
        submitLabel: SubmitLabel,
        keyboard: UIKeyboardType,
        onSubmit: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                Text(label)
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(focused == field ? palette.accent : palette.inkTertiary)
                if optional {
                    Text("optional")
                        .cliampText(CliampType.meta)
                        .foregroundStyle(palette.inkFaint)
                }
            }
            TextField(placeholder, text: text)
                .cliampText(CliampType.trackTitleSmall)
                .foregroundStyle(palette.ink)
                .textFieldStyle(.plain)
                .keyboardType(keyboard)
                .submitLabel(submitLabel)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .focused($focused, equals: field)
                .onSubmit(onSubmit)
            Rectangle()
                .fill(focused == field ? palette.accent : palette.hairline)
                .frame(height: focused == field ? 2 : 1)
        }
        .padding(.vertical, 12)
    }
}
