import CliampCore
import CliampDesign
import SwiftUI

/// A destination above the Library pager, the way Android pushes its panes:
/// the mini player and tab bar stay visible underneath.
enum LibraryDestination: Hashable {
    case smart(LibraryModel.SmartKind)
    case playlist(String)
    case playlistAdding(String)
    case providers
    case providersConnect
    case providerBrowse(String)
    case providerWizard(String?)
}

/// The third tab: pinned smart lists, connected providers and the user's
/// playlists. Mirrors Android's `LocalScreen` home.
struct LibraryScreen: View {
    @Environment(\.cliampPalette) private var palette
    let model: LibraryModel
    let app: AppState
    let downloads: DownloadManager
    let onOpenSmart: (LibraryModel.SmartKind) -> Void
    let onOpenPlaylist: (String) -> Void
    let onAddSongs: (String) -> Void
    let onOpenProviders: () -> Void
    let onOpenSearch: () -> Void
    let onOpenSettings: () -> Void
    /// False while another tab or a pane covers the page: Android drops any
    /// half-finished naming when the page goes away.
    let visible: Bool

    @State private var creating = false
    @State private var renamingSlug: String?
    @State private var nameText = ""
    @FocusState private var nameFocused: Bool

    var body: some View {
        ScrollViewReader { proxy in
            VStack(spacing: 0) {
                CliampHeader(
                    "Library",
                    onSearch: onOpenSearch,
                    onSettings: onOpenSettings,
                    onTitleTap: { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                )
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear.frame(height: 0).id(Self.topAnchor)
                        pinnedSection
                        if !unpinnedPlaylists.isEmpty {
                            SectionLabel("playlists — \(unpinnedPlaylists.count)")
                        }
                        ForEach(unpinnedPlaylists) { playlist in
                            playlistRow(playlist)
                        }
                        Spacer().frame(height: 20)
                    }
                }
            }
        }
        .background(palette.ground)
        .task { await model.start() }
        .onChange(of: visible) { _, shown in
            if !shown {
                creating = false
                renamingSlug = nil
            }
        }
    }

    private static let topAnchor = "library-top"

    /// Pinned rows render above; the ordinary section is unpinned only, the
    /// way Android splits them.
    private var unpinnedPlaylists: [Playlist] {
        model.playlists.filter { !$0.pinned }
    }

    // MARK: pinned

    private var pinnedCount: Int {
        LibraryModel.SmartKind.allCases.count + 1 + model.playlists.filter(\.pinned).count
    }

    @ViewBuilder
    private var pinnedSection: some View {
        SectionLabel("pinned — \(pinnedCount)") {
            if !creating {
                CliampIcon(CliampIcons.plus, size: 16, tint: palette.accent)
                    .frame(width: 34, height: 34)
                    .background(palette.dark ? palette.keyFace : palette.ground)
                    .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
                    .overlay(
                        RoundedRectangle(cornerRadius: CliampShape.small)
                            .stroke(palette.keyBorder, lineWidth: 1)
                    )
                    .microPress {
                        creating = true
                        renamingSlug = nil
                        nameText = ""
                        nameFocused = true
                    }
            }
        }
        if creating {
            NameField(
                text: $nameText,
                placeholder: "name this playlist",
                focused: $nameFocused,
                onSave: { name in
                    model.createPlaylist(name)
                    creating = false
                },
                onCancel: { creating = false }
            )
        }
        // Recently played keeps the top spot for daily muscle memory, the
        // servers row follows it, then the other smart lists.
        smartRow(.recentlyPlayed)
        providersRow
        smartRow(.downloads)
        smartRow(.favorites)
        smartRow(.localSongs)
        ForEach(model.playlists.filter(\.pinned)) { playlist in
            playlistRow(playlist)
        }
    }

    private func count(_ kind: LibraryModel.SmartKind) -> Int {
        model.members(
            for: kind,
            favorites: app.favorites,
            history: app.history,
            downloads: downloadStations
        ).count
    }

    private var downloadStations: [Station] {
        downloads.entries.values.map(\.station)
    }

    @ViewBuilder
    private func smartRow(_ kind: LibraryModel.SmartKind) -> some View {
        let empty = count(kind) == 0
        ListRow(
            onClick: { onOpenSmart(kind) },
            leading: { GlyphPlate(icon(kind), size: 44) },
            trailing: {
                HStack(spacing: 12) {
                    Text(empty ? "empty" : "\(count(kind)) items")
                        .cliampText(CliampType.meta)
                        .foregroundStyle(palette.inkFaint)
                    CliampIcon(CliampIcons.caretRight, size: 11, tint: palette.inkTertiary)
                }
            },
            verticalPadding: 8
        ) {
            Text(kind.label)
                .cliampText(CliampType.rowPrimaryMedium)
                .foregroundStyle(palette.ink)
                .lineLimit(1)
            if empty {
                Text("nothing here yet")
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.inkTertiary)
                    .lineLimit(1)
            }
        }
    }

    private func icon(_ kind: LibraryModel.SmartKind) -> CliampVector {
        switch kind {
        case .localSongs: CliampIcons.musicNote
        case .downloads: CliampIcons.download
        case .favorites: CliampIcons.star
        case .recentlyPlayed: CliampIcons.clock
        }
    }

    /// One row standing in for every connected account. Accounts arrive with
    /// the provider slice; until then the count is honest: none yet.
    private var providersRow: some View {
        ListRow(
            onClick: onOpenProviders,
            leading: { GlyphPlate(CliampIcons.server, size: 44) },
            trailing: {
                HStack(spacing: 12) {
                    Text("none yet")
                        .cliampText(CliampType.meta)
                        .foregroundStyle(palette.inkFaint)
                    CliampIcon(CliampIcons.caretRight, size: 11, tint: palette.inkTertiary)
                }
            },
            verticalPadding: 8
        ) {
            Text("providers")
                .cliampText(CliampType.rowPrimaryMedium)
                .foregroundStyle(palette.ink)
                .lineLimit(1)
            Text("connect one to fill this")
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }

    // MARK: playlists

    @ViewBuilder
    private func playlistRow(_ playlist: Playlist) -> some View {
        if playlist.slug == renamingSlug {
            NameField(
                text: $nameText,
                placeholder: "rename playlist",
                focused: $nameFocused,
                onSave: { name in
                    model.renamePlaylist(slug: playlist.slug, name: name)
                    renamingSlug = nil
                },
                onCancel: { renamingSlug = nil }
            )
        } else {
            ListRow(
                onClick: { onOpenPlaylist(playlist.slug) },
                leading: { GlyphPlate(CliampIcons.listShort, size: 44) },
                trailing: {
                    HStack(spacing: 12) {
                        Text("\(playlist.songIds.count) songs")
                            .cliampText(CliampType.meta)
                            .foregroundStyle(palette.inkFaint)
                        playlistMenu(playlist)
                        CliampIcon(CliampIcons.caretRight, size: 11, tint: palette.inkTertiary)
                    }
                },
                verticalPadding: 9
            ) {
                Text(playlist.name)
                    .cliampText(CliampType.rowPrimaryMedium)
                    .foregroundStyle(palette.ink)
                    .lineLimit(1)
                Text(preview(playlist))
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.inkTertiary)
                    .lineLimit(1)
            }
        }
    }

    private func preview(_ playlist: Playlist) -> String {
        let text = model.playlistPreview(playlist)
        return text.isEmpty ? "empty playlist" : text
    }

    private func playlistMenu(_ playlist: Playlist) -> some View {
        Menu {
            Button(playlist.pinned ? "unpin" : "pin") {
                model.setPinned(slug: playlist.slug, pinned: !playlist.pinned)
            }
            Button("add") { onAddSongs(playlist.slug) }
            Button("edit name") {
                renamingSlug = playlist.slug
                nameText = playlist.name
                nameFocused = true
            }
            Button("remove playlist", role: .destructive) {
                model.deletePlaylist(slug: playlist.slug)
            }
        } label: {
            CliampIcon(CliampIcons.more, size: 17, tint: palette.inkTertiary)
                .frame(width: 36, height: 36)
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
    }
}

/// The inline playlist naming field: a monospace input with SAVE and CANCEL,
/// rendered in the list where the row would be. Mirrors Android's
/// `InlineNameField`.
struct NameField: View {
    @Environment(\.cliampPalette) private var palette
    @Binding var text: String
    let placeholder: String
    var focused: FocusState<Bool>.Binding
    let onSave: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            TextField(placeholder, text: Binding(
                get: { text },
                set: { text = String($0.prefix(48)) }
            ))
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(palette.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused(focused)
                .onSubmit { onSave(text) }
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("SAVE")
                .cliampText(CliampType.tabLabel)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, 9)
                .padding(.vertical, 7)
                .background(palette.accent.opacity(0.14))
                .clipShape(RoundedRectangle(cornerRadius: CliampShape.tiny))
                .microPress { onSave(text) }
            Text("CANCEL")
                .cliampText(CliampType.tabLabel)
                .foregroundStyle(palette.inkTertiary)
                .padding(.horizontal, 9)
                .padding(.vertical, 7)
                .overlay(
                    RoundedRectangle(cornerRadius: CliampShape.tiny)
                        .stroke(palette.chipBorder, lineWidth: 1)
                )
                .microPress(action: onCancel)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(palette.panel)
        .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
        .overlay(
            RoundedRectangle(cornerRadius: CliampShape.small)
                .stroke(palette.chipBorder, lineWidth: 1)
        )
        .padding(.horizontal, cliampGutter)
        .padding(.vertical, 4)
    }
}
