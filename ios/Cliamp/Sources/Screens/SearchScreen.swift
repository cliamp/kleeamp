import CliampCore
import CliampDesign
import SwiftUI

/// The app-wide finder: one query across the catalogs that exist, over the
/// permanent chrome so the mini player and tab bar stay visible. Ported from
/// Android's `SearchScreen`.
struct SearchScreen: View {
    @Environment(\.cliampPalette) private var palette
    let model: SearchModel
    let player: RadioPlayer
    let onBack: () -> Void
    let onOpenShow: (PodcastShow) -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
            content
        }
        .background(palette.ground)
        .onAppear {
            model.start()
            model.refresh()
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                BackChevron { onBack() }
                Spacer().frame(width: 8)
                TextField("Search", text: Binding(
                    get: { model.query },
                    set: { model.query = $0 }
                ))
                .cliampText(CliampType.trackTitleCompact)
                .foregroundStyle(palette.ink)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.go)
                .onSubmit { model.submit() }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, cliampGutter)
            .padding(.top, 10)
            .padding(.bottom, 12)
            scopeChips
            HairlineDivider(region: true)
        }
    }

    private var scopeChips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 7) {
                ForEach(SearchModel.Scope.allCases) { scope in
                    Chip(scope.rawValue, selected: model.scope == scope) {
                        model.setScope(scope)
                    }
                }
            }
            .padding(.horizontal, cliampGutter)
        }
        .scrollIndicators(.hidden)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private var content: some View {
        if model.allHits.isEmpty, !model.term.isEmpty {
            VStack {
                Text("no hits anywhere for \"\(model.term)\"")
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.inkFaint)
                    .padding(.horizontal, cliampGutter)
                    .padding(.vertical, 20)
                Spacer()
            }
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let first = model.hits.first {
                        SectionLabel(sectionLabel(first)) { EmptyView() }
                        ForEach(model.hits) { hit in
                            row(hit)
                        }
                    }
                    Spacer().frame(height: 12)
                }
            }
        }
    }

    /// One heading for the whole list, named after the first hit's source.
    private func sectionLabel(_ hit: SearchHit) -> String {
        switch hit {
        case .song, .favorite: "local — global"
        case .station: "radio — global"
        case .show, .episode: "podcasts — global"
        }
    }

    @ViewBuilder
    private func row(_ hit: SearchHit) -> some View {
        let active = hit.playable.map { $0.url == player.station?.url } ?? false
        ListRow(
            onClick: { open(hit) },
            leading: { art(hit, active: active) },
            trailing: {
                Text(hit.origin)
                    .cliampText(CliampType.meta)
                    .foregroundStyle(palette.inkFaint)
            },
            verticalPadding: 9,
            rail: active,
            railOffset: cliampGutter
        ) {
            Text(highlighted(title(hit)))
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(active ? palette.accent : palette.ink)
                .lineLimit(1)
            if !subtitle(hit).isEmpty {
                Text(subtitle(hit))
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.inkTertiary)
                    .lineLimit(1)
            }
        }
    }

    private func title(_ hit: SearchHit) -> String {
        switch hit {
        case .song(let station), .station(let station), .favorite(let station): station.name
        case .episode(let station, _): station.name
        case .show(let show, _): show.title
        }
    }

    private func subtitle(_ hit: SearchHit) -> String {
        switch hit {
        case .song(let station): station.artist
        case .station(let station): station.meta
        case .favorite(let station): station.meta.isEmpty ? station.name : station.meta
        case .episode(_, let showTitle): showTitle
        case .show(let show, _): show.meta
        }
    }

    /// The Android row's art: real cover when there is one, otherwise the
    /// hit type's glyph, with the playing badge on the active station.
    @ViewBuilder
    private func art(_ hit: SearchHit, active: Bool) -> some View {
        if case .show(let show, _) = hit, !show.artwork.hasPrefix("http") {
            // A show without artwork gets the standalone glyph Android draws.
            CliampIcon(CliampIcons.podRow, size: 15, tint: palette.inkTertiary)
                .frame(width: 40, height: 40)
        } else if let station = hit.playable ?? showStation(hit) {
            StationArtView(station: station, size: 40, fallback: fallback(hit))
                .overlay {
                    if active {
                        RoundedRectangle(cornerRadius: CliampShape.tiny)
                            .fill(palette.accent.opacity(0.92))
                            .overlay(
                                CliampIcon(
                                    player.playing ? CliampIcons.pause : CliampIcons.playRow,
                                    size: 11,
                                    tint: palette.onAccent
                                )
                            )
                            .frame(width: 22, height: 22)
                    }
                }
        }
    }

    private func showStation(_ hit: SearchHit) -> Station? {
        if case .show(let show, _) = hit { return show.artStation }
        return nil
    }

    private func fallback(_ hit: SearchHit) -> StationArtFallback {
        switch hit {
        case .song, .favorite: .music
        case .station: .glyph
        case .episode, .show: .podcast
        }
    }

    /// Matched characters wear the accent, the way the Android row highlights.
    private func highlighted(_ text: String) -> AttributedString {
        guard !model.term.isEmpty,
              let positions = Fuzzy.matchedPositions(query: model.term, haystack: text)
        else { return AttributedString(text) }
        var result = AttributedString()
        for (index, character) in text.enumerated() {
            var piece = AttributedString(String(character))
            if positions.contains(index) {
                piece.foregroundColor = palette.accent
                piece.font = CliampType.rowPrimary.font.bold()
            }
            result.append(piece)
        }
        return result
    }

    private func open(_ hit: SearchHit) {
        if let station = hit.playable {
            // Android builds the continuation from every playable hit in the
            // displayed list, in order.
            player.play(
                station,
                from: model.hits.compactMap(\.playable),
                contextKey: "search:\(model.term):\(model.scope.rawValue)"
            )
            return
        }
        if case .show(let show, _) = hit {
            onOpenShow(show)
        }
    }
}
