import CliampCore
import CliampDesign
import SwiftUI

/// The wizard for one provider: renders whatever the spec lists, tests the
/// live server, and only then saves. Nothing is written until the probe
/// succeeds, so a typo fails here rather than silently at first playback.
struct ProviderWizardScreen: View {
    @Environment(\.cliampPalette) private var palette
    let model: ProvidersModel
    let spec: ProviderSpec
    let accountId: String?
    let onSaved: (ProviderAccount) -> Void
    let onBack: () -> Void

    @State private var values: [String: String] = [:]
    @State private var identity: ProviderIdentity?
    @State private var error: String?
    @State private var testing = false
    @FocusState private var focused: String?

    var body: some View {
        VStack(spacing: 0) {
            CliampHeader(spec.name, onBack: onBack) {
                EmptyView()
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(spec.intro, id: \.self) { line in
                        Text(line)
                            .cliampText(CliampType.rowSecondary)
                            .foregroundStyle(palette.inkTertiary)
                    }
                    if let picker = spec.picker {
                        VStack(alignment: .leading, spacing: 7) {
                            Text(picker.label)
                                .cliampText(CliampType.meta)
                                .foregroundStyle(palette.inkTertiary)
                            HStack(spacing: 7) {
                                ForEach(picker.options) { option in
                                    Chip(option.label, selected: value(picker.key) == option.value) {
                                        set(picker.key, option.value)
                                    }
                                }
                            }
                        }
                    }
                    ForEach(spec.visibleFields(values)) { field in
                        fieldView(field)
                    }
                    actions
                }
                .padding(.horizontal, cliampGutter)
                .padding(.vertical, 12)
            }
        }
        .background(palette.ground)
        .onAppear {
            seed()
            #if DEBUG
            // Screenshot hook: reproduce the keyboard-up form state.
            if ProcessInfo.processInfo.arguments.contains("-cliamp-preview-focus-host") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { focused = "host" }
            }
            #endif
        }
    }

    // MARK: fields

    @ViewBuilder
    private func fieldView(_ field: FieldSpec) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(field.label)
                .cliampText(CliampType.meta)
                .foregroundStyle(palette.inkTertiary)
            Group {
                if field.secret {
                    SecureField(field.label.lowercased(), text: binding(field.key))
                        .focused($focused, equals: field.key)
                } else {
                    TextField(field.label.lowercased(), text: binding(field.key), axis: .vertical)
                        .lineLimit(field.lines, reservesSpace: field.lines > 1)
                        .focused($focused, equals: field.key)
                }
            }
            .cliampText(CliampType.rowPrimary)
            .foregroundStyle(palette.ink)
            .textInputAutocapitalization(field.keyboard == .url ? .never : .sentences)
            .autocorrectionDisabled(field.keyboard != .text)
            .keyboardType(field.keyboard == .number ? .numberPad : .default)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(palette.panel)
            .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
            .overlay(
                RoundedRectangle(cornerRadius: CliampShape.small)
                    .stroke(palette.chipBorder, lineWidth: 1)
            )
            if !field.help.isEmpty {
                Text(field.help)
                    .cliampText(CliampType.meta)
                    .foregroundStyle(palette.inkFaint)
            }
        }
    }

    private var actions: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Chip(testing ? "testing…" : "test", selected: false) {
                    Task { await test() }
                }
                if identity != nil {
                    Chip("save", selected: true) { save() }
                }
                Spacer(minLength: 0)
            }
            if let identity {
                Text("connected · \(identity.detail)")
                    .cliampText(CliampType.meta)
                    .foregroundStyle(palette.accent)
            }
            if let error {
                Text(error)
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.destructiveInk)
            }
        }
    }

    // MARK: state

    private func value(_ key: String) -> String {
        values[key] ?? ""
    }

    private func set(_ key: String, _ value: String) {
        values[key] = value
        // Any edit invalidates the test: nothing is saved on a stale probe.
        identity = nil
        error = nil
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(get: { values[key] ?? "" }, set: { set(key, $0) })
    }

    private func seed() {
        var seeded: [String: String] = [:]
        for field in spec.fields where !field.default.isEmpty {
            seeded[field.key] = field.default
        }
        if let picker = spec.picker {
            seeded[picker.key] = values[picker.key] ?? picker.default
        }
        if let accountId, let account = model.account(id: accountId) {
            seeded.merge(model.resolvedValues(for: account)) { _, stored in stored }
        }
        values = seeded
    }

    private func test() async {
        testing = true
        error = nil
        identity = nil
        let result = await model.probe(spec: spec, values: values)
        testing = false
        switch result {
        case .success(let probed):
            identity = probed
            // The probe's answers (folders it found, the pinned fingerprint)
            // become form values before saving.
            values.merge(probed.values) { current, probed in
                // A fingerprint and discovered folders always win; a field
                // the user typed stays theirs.
                probed
            }
        case .failure(let failure):
            error = (failure as? LocalizedError)?.errorDescription ?? failure.localizedDescription
        }
    }

    private func save() {
        guard let identity else { return }
        guard let account = model.saveAccount(
            id: accountId,
            providerKey: spec.key,
            label: identity.name,
            values: values
        ) else { return }
        onSaved(account)
    }
}

/// The connected accounts: add, edit, remove, and open one to browse.
struct ProvidersConnectPane: View {
    @Environment(\.cliampPalette) private var palette
    let model: ProvidersModel
    let onBack: () -> Void
    let onAdd: () -> Void
    let onEdit: (ProviderAccount) -> Void
    let onOpen: (ProviderAccount) -> Void
    let onOpenSearch: () -> Void
    let onOpenSettings: () -> Void

    var body: some View {
        ScrollViewReader { proxy in
            VStack(spacing: 0) {
                CliampHeader(
                    "providers",
                    onBack: onBack,
                    onSearch: onOpenSearch,
                    onSettings: onOpenSettings,
                    onTitleTap: { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                ) {
                    EmptyView()
                }
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear.frame(height: 0).id(Self.topAnchor)
                        SectionLabel("connected — \(model.accounts.count)") {
                            CliampIcon(CliampIcons.plus, size: 16, tint: palette.accent)
                                .frame(width: 34, height: 34)
                                .background(palette.dark ? palette.keyFace : palette.ground)
                                .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
                                .overlay(
                                    RoundedRectangle(cornerRadius: CliampShape.small)
                                        .stroke(palette.keyBorder, lineWidth: 1)
                                )
                                .microPress(action: onAdd)
                        }
                        if model.accounts.isEmpty {
                            Text("nothing connected yet")
                                .cliampText(CliampType.rowSecondary)
                                .foregroundStyle(palette.inkFaint)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, cliampGutter)
                                .padding(.vertical, 8)
                        }
                        ForEach(model.accounts) { account in
                            row(account)
                        }
                        Spacer().frame(height: 20)
                    }
                }
            }
        }
        .background(palette.ground)
    }

    private static let topAnchor = "providers-connect-top"

    private func row(_ account: ProviderAccount) -> some View {
        let spec = model.spec(for: account)
        let summary = spec?.summary(model.resolvedValues(for: account)) ?? ""
        return ListRow(
            onClick: { onOpen(account) },
            leading: {
                GlyphPlate(account.providerKey == "ssh" ? CliampIcons.server : CliampIcons.server, size: 40)
            },
            trailing: {
                HStack(spacing: 12) {
                    Menu {
                        Button("edit") { onEdit(account) }
                        Button("remove", role: .destructive) {
                            model.remove(accountId: account.id)
                        }
                    } label: {
                        CliampIcon(CliampIcons.more, size: 17, tint: palette.inkTertiary)
                            .frame(width: 36, height: 36)
                    }
                    .menuStyle(.button)
                    .buttonStyle(.plain)
                    CliampIcon(CliampIcons.caretRight, size: 11, tint: palette.inkTertiary)
                }
            },
            verticalPadding: 9
        ) {
            Text(account.label.isEmpty ? (spec?.name ?? "provider") : account.label)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(palette.ink)
                .lineLimit(1)
            Text(summary.isEmpty ? (spec?.name ?? "provider") : summary)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }
}

/// Every configured account's songs in one flat list, the pane the Library's
/// providers row opens. Mirrors Android's `ProviderSongsPane`.
struct ProvidersSongsPane: View {
    @Environment(\.cliampPalette) private var palette
    let model: ProvidersModel
    let player: RadioPlayer
    let app: AppState
    let onBack: () -> Void
    let onOpenConnect: () -> Void
    let onOpenBrowse: (ProviderAccount) -> Void
    let onOpenSearch: () -> Void
    let onOpenSettings: () -> Void

    @State private var selected: String?

    var body: some View {
        ScrollViewReader { proxy in
            VStack(spacing: 0) {
                CliampHeader(
                    "providers",
                    onBack: onBack,
                    onSearch: onOpenSearch,
                    onSettings: onOpenSettings,
                    onTitleTap: { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                ) {
                    if model.accounts.count > 1 {
                        ForEach(model.accounts) { account in
                            Chip(
                                account.label.isEmpty ? "provider" : account.label,
                                selected: selected == account.id
                            ) {
                                selected = account.id
                            }
                        }
                    }
                }
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear.frame(height: 0).id(Self.topAnchor)
                        let account = currentAccount
                        let tracks = visibleTracks
                        SectionLabel("songs — \(tracks.count)") {
                            CliampIcon(CliampIcons.plus, size: 16, tint: palette.accent)
                                .frame(width: 34, height: 34)
                                .background(palette.dark ? palette.keyFace : palette.ground)
                                .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
                                .overlay(
                                    RoundedRectangle(cornerRadius: CliampShape.small)
                                        .stroke(palette.keyBorder, lineWidth: 1)
                                )
                                .microPress(action: onOpenConnect)
                        }
                        if let account, !status(account).text.isEmpty {
                            Text(status(account).text)
                                .cliampText(CliampType.rowSecondary)
                                .foregroundStyle(status(account).scanning ? palette.amber : palette.inkFaint)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, cliampGutter)
                                .padding(.vertical, 8)
                        }
                        if tracks.isEmpty {
                            Text(model.accounts.isEmpty
                                ? "no providers yet — add one with +"
                                : (status(account).scanning ? "scanning…" : "nothing here"))
                                .cliampText(CliampType.rowSecondary)
                                .foregroundStyle(palette.inkFaint)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 24)
                        }
                        if let account {
                            let queue = tracks.map(\.station)
                            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                                songRow(track, queue: queue, index: index, account: account)
                            }
                        }
                        Spacer().frame(height: 20)
                    }
                }
            }
        }
        .background(palette.ground)
        .onAppear {
            if selected == nil { selected = model.accounts.first?.id }
            for account in model.accounts {
                model.ensureIndexed(account)
            }
        }
        .onChange(of: model.accounts) { _, accounts in
            if selected == nil || !accounts.contains(where: { $0.id == selected }) {
                selected = accounts.first?.id
            }
            // Accounts can arrive after the pane appeared (previews and the
            // wizard both do), so indexing follows the list, not just onAppear.
            for account in accounts {
                model.ensureIndexed(account)
            }
        }
    }

    private static let topAnchor = "providers-songs-top"

    private func status(_ account: ProviderAccount?) -> SftpScanStatus {
        guard let account else { return .idle }
        return model.status(accountId: account.id)
    }

    private var currentAccount: ProviderAccount? {
        model.accounts.first { $0.id == selected } ?? model.accounts.first
    }

    private var visibleTracks: [SftpTrack] {
        guard let account = currentAccount else { return [] }
        return model.tracks(accountId: account.id)
            .sorted { $0.title.lowercased() < $1.title.lowercased() }
    }

    @ViewBuilder
    private func songRow(_ track: SftpTrack, queue: [Station], index: Int, account: ProviderAccount) -> some View {
        let station = track.station
        let active = player.station?.url == station.url
        let favorite = app.favoriteURLs.contains(station.url)
        ListRow(
            onClick: {
                let playlist = queue.isEmpty ? [station] : queue
                player.play(
                    station, from: playlist,
                    contextKey: "provider:\(account.id):songs"
                )
            },
            leading: {
                StationArtView(station: station, size: 40, fallback: .music)
                    .overlay {
                        if active {
                            CliampIcon(
                                player.playing ? CliampIcons.pause : CliampIcons.playRow,
                                size: 9,
                                tint: palette.onAccent
                            )
                            .frame(width: 18, height: 18)
                            .background(palette.accent.opacity(0.92))
                            .clipShape(RoundedRectangle(cornerRadius: CliampShape.tiny))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        }
                    }
            },
            trailing: {
                CliampIcon(
                    favorite ? CliampIcons.starFilled : CliampIcons.star,
                    size: 15,
                    tint: favorite ? palette.accent : palette.inkFaint
                )
                .microPress { app.toggleFavorite(station) }
            },
            verticalPadding: 9,
            rail: active
        ) {
            Text(track.title)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(active ? palette.accent : palette.ink)
                .lineLimit(1)
            Text(subtitle(track))
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }

    private func subtitle(_ track: SftpTrack) -> String {
        let line = [track.artist, track.album].filter { !$0.isEmpty }.joined(separator: " · ")
        return line.isEmpty ? "provider" : line
    }
}

/// One provider's library: albums, artists, and an album's tracks. Playing a
/// track queues the whole album; the sort, rescan and filter live in the
/// header. Mirrors Android's `ProviderBrowseScreen`.
struct ProviderBrowseScreen: View {
    @Environment(\.cliampPalette) private var palette
    let model: ProvidersModel
    let player: RadioPlayer
    let account: ProviderAccount
    let onBack: () -> Void
    let onEdit: () -> Void
    let onOpenSearch: () -> Void
    let onOpenSettings: () -> Void

    private enum Node: Hashable {
        case home
        case artist(key: String, name: String)
        case album(key: String, name: String, artist: String)
    }

    @State private var stack: [Node] = [.home]
    @State private var style = "az"
    @State private var filter = ""

    var body: some View {
        ScrollViewReader { proxy in
            VStack(spacing: 0) {
                CliampHeader(
                    title,
                    onBack: pop,
                    onSearch: onOpenSearch,
                    onSettings: onOpenSettings,
                    onTitleTap: { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                ) {
                    Chip("a-z", selected: style == "az") { style = "az" }
                    Chip("newest", selected: style == "newest") { style = "newest" }
                    if case .home = here, model.hasIndex(accountId: account.id) {
                        let status = model.status(accountId: account.id)
                        Chip(status.scanning ? "scanning" : "rescan", selected: false) {
                            Task { await model.rescan(account) }
                        }
                    }
                    Spacer(minLength: 4)
                    Text("EDIT")
                        .cliampText(CliampType.sectionLabel)
                        .foregroundStyle(palette.inkTertiary)
                        .microPress(action: onEdit)
                }
                filterRow
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear.frame(height: 0).id(Self.topAnchor)
                        list
                        Spacer().frame(height: 24)
                    }
                }
            }
        }
        .background(palette.ground)
        .onAppear { model.ensureIndexed(account) }
        .onChange(of: model.indexVersion) { _, _ in }
    }

    private static let topAnchor = "provider-browse-top"

    private var here: Node { stack.last ?? .home }

    private var title: String {
        switch here {
        case .home: account.label.isEmpty ? "provider" : account.label
        case .artist(_, let name): name
        case .album(_, let name, _): name
        }
    }

    private func pop() {
        if stack.count > 1 {
            stack.removeLast()
        } else {
            onBack()
        }
    }

    private var filterRow: some View {
        VStack(alignment: .leading, spacing: 0) {
            TextField("filter", text: $filter)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(palette.panel)
                .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
                .overlay(
                    RoundedRectangle(cornerRadius: CliampShape.small)
                        .stroke(palette.chipBorder, lineWidth: 1)
                )
        }
        .padding(.horizontal, cliampGutter)
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var list: some View {
        let status = model.status(accountId: account.id)
        if !status.text.isEmpty {
            Text(status.text)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(status.scanning ? palette.amber : palette.inkFaint)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, cliampGutter)
                .padding(.vertical, 10)
        }
        switch here {
        case .home:
            homeList
        case .artist(let key, _):
            artistList(key: key)
        case .album(let key, _, _):
            albumList(key: key)
        }
        if isEmpty {
            Text("nothing here")
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkFaint)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
        }
    }

    private var isEmpty: Bool {
        guard !model.status(accountId: account.id).scanning else { return false }
        switch here {
        case .home: return filteredAlbums.isEmpty && filteredArtists.isEmpty
        case .artist(let key, _): return filteredArtistAlbums(key: key).isEmpty
        case .album(let key, _, _): return filteredAlbumTracks(key: key).isEmpty
        }
    }

    private func matches(_ text: String) -> Bool {
        filter.isEmpty || text.lowercased().contains(filter.lowercased())
    }

    private var filteredAlbums: [SftpAlbum] {
        model.albums(accountId: account.id, style: style).filter { matches($0.name) }
    }

    private var filteredArtists: [SftpArtist] {
        model.artists(accountId: account.id).filter { matches($0.name) }
    }

    private func filteredArtistAlbums(key: String) -> [SftpAlbum] {
        model.artistAlbums(accountId: account.id, artistKey: key).filter { matches($0.name) }
    }

    private func filteredAlbumTracks(key: String) -> [SftpTrack] {
        model.albumTracks(accountId: account.id, albumKey: key).filter { matches($0.title) }
    }

    @ViewBuilder
    private var homeList: some View {
        let artists = filteredArtists
        let albums = filteredAlbums
        if !artists.isEmpty {
            SectionLabel("artists — \(artists.count)")
            ForEach(artists) { artist in
                ListRow(
                    onClick: { stack.append(.artist(key: artist.id, name: artist.name)) },
                    trailing: {
                        if artist.albumCount > 0 {
                            Text("\(artist.albumCount)")
                                .cliampText(CliampType.meta)
                                .foregroundStyle(palette.inkFaint)
                        }
                    },
                    verticalPadding: 11
                ) {
                    Text(artist.name)
                        .cliampText(CliampType.rowPrimary)
                        .foregroundStyle(palette.ink)
                        .lineLimit(1)
                }
            }
        }
        if !albums.isEmpty {
            SectionLabel("albums — \(albums.count)")
            ForEach(albums) { album in
                albumRow(album)
            }
        }
    }

    @ViewBuilder
    private func artistList(key: String) -> some View {
        let albums = filteredArtistAlbums(key: key)
        if !albums.isEmpty {
            SectionLabel("albums — \(albums.count)")
            ForEach(albums) { album in
                albumRow(album)
            }
        }
    }

    @ViewBuilder
    private func albumList(key: String) -> some View {
        let tracks = filteredAlbumTracks(key: key)
        if !tracks.isEmpty {
            SectionLabel("tracks — \(tracks.count)")
            let queue = tracks.map(\.station)
            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                trackRow(track, queue: queue, index: index, albumKey: key)
            }
        }
    }

    private func albumRow(_ album: SftpAlbum) -> some View {
        ListRow(
            onClick: {
                stack.append(.album(key: album.id, name: album.name, artist: album.artist))
            },
            trailing: {
                if album.songCount > 0 {
                    Text("\(album.songCount)")
                        .cliampText(CliampType.meta)
                        .foregroundStyle(palette.inkFaint)
                }
            },
            verticalPadding: 11
        ) {
            Text(album.name)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(palette.ink)
                .lineLimit(1)
            Text([album.artist, album.year > 0 ? String(album.year) : ""]
                .filter { !$0.isEmpty }
                .joined(separator: " · "))
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private func trackRow(_ track: SftpTrack, queue: [Station], index: Int, albumKey: String) -> some View {
        let station = track.station
        let active = player.station?.url == station.url
        ListRow(
            onClick: {
                player.play(
                    station, from: queue,
                    contextKey: "provider:\(account.id):\(albumKey)"
                )
            },
            leading: {
                RoundedRectangle(cornerRadius: CliampShape.tiny)
                    .stroke(palette.chipBorder, lineWidth: 1)
                    .overlay(
                        CliampIcon(CliampIcons.playRow, size: 11, tint: palette.inkTertiary)
                    )
                    .frame(width: 28, height: 28)
            },
            verticalPadding: 11,
            rail: active
        ) {
            Text(track.title)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(active ? palette.accent : palette.ink)
                .lineLimit(1)
            Text([track.artist, track.album].filter { !$0.isEmpty }.joined(separator: " · "))
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }
}
