import CliampCore
import CliampDesign
import SwiftUI
import UIKit

/// The permanent chrome: mini player plus tabs, never removed for the whole
/// session, with the landscape rail replacing the bottom bar.
struct MainShell: View {
    @Environment(\.cliampPalette) private var palette
    @Binding var tab: AppTab
    let player: RadioPlayer
    let app: AppState
    let onOpenSettings: () -> Void
    let onOpenPlayer: () -> Void
    @Binding var podcastPath: [PodcastShow]
    @Binding var showSearch: Bool
    @Binding var showSettings: Bool
    @State private var library = LibraryModel()
    @State private var libraryPath: [LibraryDestination] = []
    private let providers = ProviderServices.shared.model

    /// The home-indicator inset on its own. `safeAreaInsets` inside the
    /// shell includes the keyboard while it is up, and padding the tab bar by
    /// that squeezes whatever form is focused down to nothing.
    @State private var containerBottomInset: CGFloat = 0

    var body: some View {
        GeometryReader { proxy in
            let rail = proxy.size.width > proxy.size.height
            let bottomInset = containerBottomInset
            if rail {
                HStack(spacing: 0) {
                    VStack(spacing: 0) {
                        content
                        MiniPlayerBar(player: player, app: app, onOpen: onOpenPlayer)
                    }
                    CliampTabRail(current: tab, onSelect: select)
                }
            } else {
                VStack(spacing: 0) {
                    content
                    MiniPlayerBar(player: player, app: app, onOpen: onOpenPlayer)
                    CliampTabBar(current: tab, onSelect: select, bottomInset: bottomInset)
                }
                // The bar paints to the screen edge the way Android's does,
                // with its own padding clearing the home indicator. Ignoring
                // only the container region keeps keyboard insets working.
                .ignoresSafeArea(.container, edges: .bottom)
            }
        }
        .background(
            GeometryReader { proxy in
                let inset = proxy.safeAreaInsets.bottom
                Color.clear
                    .onAppear { containerBottomInset = inset }
                    .onChange(of: inset) { _, value in containerBottomInset = value }
            }
            .ignoresSafeArea(.keyboard, edges: .bottom)
        )
        #if DEBUG
        .onAppear {
            // Screenshot hooks; never compiled into release builds.
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("-cliamp-preview-library") {
                tab = .library
            }
            if arguments.contains("-cliamp-preview-library-local") {
                tab = .library
                libraryPath = [.smart(.localSongs)]
            }
            if arguments.contains("-cliamp-preview-library-providers") {
                tab = .library
                libraryPath = [.providers]
            }
            if arguments.contains("-cliamp-preview-provider-connect") {
                tab = .library
                libraryPath = [.providersConnect]
            }
            if arguments.contains("-cliamp-preview-provider-wizard") {
                tab = .library
                libraryPath = [.providerWizard(nil)]
            }
            if arguments.contains("-cliamp-preview-provider-browse") {
                // The preview account is seeded by RootView's task, which may
                // land after this onAppear.
                tab = .library
                Task { @MainActor in
                    for _ in 0..<40 {
                        if let account = providers.accounts.first {
                            libraryPath = [.providerBrowse(account.id)]
                            return
                        }
                        try? await Task.sleep(for: .milliseconds(250))
                    }
                }
            }
            if arguments.contains("-cliamp-preview-library-playlist") {
                tab = .library
                libraryPath = [.playlist("night-drive")]
            }
        }
        #endif
    }

    @ViewBuilder
    private var content: some View {
        ZStack {
            // Swiping between the three tab pages, keeping each page's state,
            // the way Android's pager behaves.
            TabView(selection: $tab) {
                stationsPage.tag(AppTab.stations)
                podcastsPage.tag(AppTab.pods)
                libraryPage.tag(AppTab.library)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            if !podcastPath.isEmpty {
                // A show detail is a destination above the pager, the way
                // Android pushes it over Home: the pager cannot be swiped
                // underneath while it is open.
                NavigationStack(path: $podcastPath) {
                    Color.clear
                        .navigationDestination(for: PodcastShow.self) { show in
                            PodcastShowScreen(
                                player: player,
                                podcasts: PodcastServices.shared.podcasts,
                                downloads: PodcastServices.shared.downloads,
                                show: show,
                                onOpenSearch: { showSearch = true },
                                onOpenSettings: onOpenSettings
                            )
                            .toolbar(.hidden, for: .navigationBar)
                        }
                }
                .toolbar(.hidden, for: .navigationBar)
            }

            if !libraryPath.isEmpty {
                // Library panes ride above the pager with the chrome visible,
                // the same way Android pushes them over Home.
                NavigationStack(path: $libraryPath) {
                    Color.clear
                        .navigationDestination(for: LibraryDestination.self) { destination in
                            libraryDestination(destination)
                                .toolbar(.hidden, for: .navigationBar)
                        }
                }
                .toolbar(.hidden, for: .navigationBar)
            }

            if showSearch {
                // The finder is a page over the permanent chrome, like
                // Android's Search destination: mini player and tab stay.
                SearchScreen(
                    model: PodcastServices.shared.search,
                    player: player,
                    onBack: { showSearch = false },
                    onOpenShow: { show in
                        showSearch = false
                        tab = .pods
                        // Android resets navigation: the Library stack must
                        // not sit above the show detail.
                        libraryPath = []
                        // Android returns to the Podcasts root, not the
                        // previous show.
                        podcastPath = [show]
                    }
                )
            }

            if showSettings {
                // Settings is a page over the chrome too, like Android.
                SettingsScreen(app: app, onBack: { showSettings = false })
            }
        }
    }

    @ViewBuilder
    private var stationsPage: some View {
        StationsScreen(
            player: player, app: app,
            onOpenSettings: onOpenSettings,
            onOpenSearch: { showSearch = true }
        )
    }

    @ViewBuilder
    private var podcastsPage: some View {
        PodcastsScreen(
            player: player,
            app: app,
            podcasts: PodcastServices.shared.podcasts,
            downloads: PodcastServices.shared.downloads,
            onOpenSettings: onOpenSettings,
            onOpenSearch: { showSearch = true },
            onOpenShow: { podcastPath = [$0] }
        )
    }

    @ViewBuilder
    private var libraryPage: some View {
        LibraryScreen(
            model: library,
            app: app,
            downloads: PodcastServices.shared.downloads,
            onOpenSmart: { libraryPath = [.smart($0)] },
            onOpenPlaylist: { libraryPath = [.playlist($0)] },
            onAddSongs: { libraryPath = [.playlistAdding($0)] },
            onOpenProviders: { libraryPath = [.providers] },
            onOpenSearch: { showSearch = true },
            onOpenSettings: onOpenSettings,
            visible: tab == .library && libraryPath.isEmpty && !showSearch && !showSettings
        )
    }

    @ViewBuilder
    private func libraryDestination(_ destination: LibraryDestination) -> some View {
        switch destination {
        case .smart(let kind):
            SmartDetailScreen(
                model: library,
                kind: kind,
                player: player,
                app: app,
                downloads: PodcastServices.shared.downloads,
                podcasts: PodcastServices.shared.podcasts,
                onBack: { libraryPath = [] },
                onOpenSearch: { showSearch = true },
                onOpenSettings: onOpenSettings
            )
        case .playlist(let slug):
            PlaylistDetailScreen(
                model: library,
                slug: slug,
                player: player,
                app: app,
                downloads: PodcastServices.shared.downloads,
                podcasts: PodcastServices.shared.podcasts,
                adding: false,
                onBack: { libraryPath = [] },
                onAdded: { libraryPath = [.playlist(slug)] },
                onOpenSearch: { showSearch = true },
                onOpenSettings: onOpenSettings
            )
        case .playlistAdding(let slug):
            PlaylistDetailScreen(
                model: library,
                slug: slug,
                player: player,
                app: app,
                downloads: PodcastServices.shared.downloads,
                podcasts: PodcastServices.shared.podcasts,
                adding: true,
                onBack: { libraryPath = [] },
                onAdded: { libraryPath = [.playlist(slug)] },
                onOpenSearch: { showSearch = true },
                onOpenSettings: onOpenSettings
            )
        case .providers:
            ProvidersSongsPane(
                model: providers,
                player: player,
                app: app,
                onBack: { libraryPath = [] },
                onOpenConnect: { libraryPath = [.providersConnect] },
                onOpenBrowse: { libraryPath = [.providerBrowse($0.id)] },
                onOpenSearch: { showSearch = true },
                onOpenSettings: onOpenSettings
            )
        case .providersConnect:
            ProvidersConnectPane(
                model: providers,
                onBack: { libraryPath = [.providers] },
                onAdd: { libraryPath = [.providerWizard(nil)] },
                onEdit: { libraryPath = [.providerWizard($0.id)] },
                onOpen: { libraryPath = [.providerBrowse($0.id)] },
                onOpenSearch: { showSearch = true },
                onOpenSettings: onOpenSettings
            )
        case .providerBrowse(let accountId):
            if let account = providers.account(id: accountId) {
                ProviderBrowseScreen(
                    model: providers,
                    player: player,
                    account: account,
                    onBack: { libraryPath = [.providersConnect] },
                    onEdit: { libraryPath = [.providerWizard(accountId)] },
                    onOpenSearch: { showSearch = true },
                    onOpenSettings: onOpenSettings
                )
            } else {
                ProvidersConnectPane(
                    model: providers,
                    onBack: { libraryPath = [] },
                    onAdd: { libraryPath = [.providerWizard(nil)] },
                    onEdit: { libraryPath = [.providerWizard($0.id)] },
                    onOpen: { libraryPath = [.providerBrowse($0.id)] },
                    onOpenSearch: { showSearch = true },
                    onOpenSettings: onOpenSettings
                )
            }
        case .providerWizard(let accountId):
            if let spec = (accountId.flatMap { providers.account(id: $0) }
                .flatMap { providers.spec(for: $0) }) ?? ProviderCatalog.byKey("ssh") {
                ProviderWizardScreen(
                    model: providers,
                    spec: spec,
                    accountId: accountId,
                    onSaved: { _ in libraryPath = [.providersConnect] },
                    onBack: { libraryPath = [.providersConnect] }
                )
            }
        }
    }

    private func select(_ tab: AppTab) {
        withAnimation(.easeOut(duration: 0.18)) {
            // A tab tap returns to that tab's root, like Android popping to
            // Home before selecting.
            showSearch = false
            showSettings = false
            podcastPath = []
            libraryPath = []
            self.tab = tab
        }
    }
}

/// The lockscreen widget's in-app twin: art (or the meter), title, one transport.
struct MiniPlayerBar: View {
    @Environment(\.cliampPalette) private var palette
    let player: RadioPlayer
    let app: AppState
    let onOpen: () -> Void
    @State private var meter: MeterModel
    @State private var artImage: UIImage?

    init(player: RadioPlayer, app: AppState, onOpen: @escaping () -> Void) {
        self.player = player
        self.app = app
        self.onOpen = onOpen
        _meter = State(initialValue: MeterModel(preset: .mini, player: player))
    }

    var body: some View {
        VStack(spacing: 0) {
            HairlineDivider(region: true)
            HStack(spacing: 12) {
                art
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.station?.name ?? "nothing playing")
                        .cliampText(CliampType.rowPrimaryMedium)
                        .foregroundStyle(palette.ink)
                        .lineLimit(1)
                    Text(statusLine)
                        .cliampText(CliampType.rowSecondary)
                        .foregroundStyle(statusColor)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 6) {
                    miniKey(CliampIcons.prev, label: "previous", enabled: player.hasPrev) {
                        player.goPrevious()
                    }
                    Button {
                        player.toggle()
                    } label: {
                        CliampIcon(
                            player.wantsToPlay ? CliampIcons.pause : CliampIcons.playTab,
                            size: player.wantsToPlay ? 13 : 15,
                            tint: palette.dark ? palette.onAccent : palette.ground
                        )
                        .frame(width: 38, height: 38)
                        .background(palette.dark ? palette.accent : palette.ink)
                        .clipShape(RoundedRectangle(cornerRadius: CliampShape.medium))
                    }
                    .buttonStyle(MicroPressStyle())
                    .disabled(player.station == nil)
                    miniKey(CliampIcons.next, label: "next", enabled: player.hasNext) {
                        player.goNext()
                    }
                }
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
        }
        .background(palette.panel)
        .onAppear {
            if app.visualizer != "off" { meter.start() }
        }
        .onDisappear { meter.stop() }
        .onChange(of: app.visualizer) { _, value in
            if value == "off" { meter.stop() } else { meter.start() }
        }
    }

    private var art: some View {
        Group {
            if let artImage {
                Image(uiImage: artImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: CliampShape.medium))
            } else if player.station != nil, app.visualizer != "off" {
                BrickMeter(levels: meter.levels, peaks: meter.peaks, preset: .mini)
                    .frame(width: 40, height: MeterPreset.mini.height)
            } else {
                ArtPlate()
                    .overlay(
                        CliampIcon(CliampIcons.stationsTab, size: 20, tint: palette.accent)
                    )
                    .frame(width: 40, height: 40)
            }
        }
        .task(id: player.station?.id) {
            artImage = nil
            guard let station = player.station else { return }
            let loaded: UIImage?
            if let cached = StationArtwork.shared.cachedSmall(for: station) {
                loaded = cached
            } else {
                loaded = await StationArtwork.shared.smallImage(for: station)
            }
            guard !Task.isCancelled else { return }
            artImage = loaded
        }
    }

    private var statusLine: String {
        guard let station = player.station else { return "pick a station to start" }
        if player.reconnecting { return "reconnecting…" }
        if let error = player.error { return error }
        if player.buffering { return "buffering…" }
        if !player.streamTitle.isEmpty { return player.streamTitle }
        return station.sourceLine
    }

    private var statusColor: Color {
        player.reconnecting || player.buffering ? palette.amber : palette.inkTertiary
    }

    private func miniKey(
        _ icon: CliampVector, label: String, enabled: Bool, action: @escaping () -> Void
    ) -> some View {
        CliampIcon(icon, width: 14, height: 11, tint: enabled ? palette.ink : palette.inkFaint)
            .frame(width: 28, height: 28)
            .background(palette.keyFace)
            .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
            .overlay(
                RoundedRectangle(cornerRadius: CliampShape.small)
                    .stroke(palette.keyBorder, lineWidth: 1)
            )
            .microPress(enabled: enabled, action: action)
    }
}

struct CliampTabBar: View {
    @Environment(\.cliampPalette) private var palette
    let current: AppTab
    let onSelect: (AppTab) -> Void
    let bottomInset: CGFloat

    var body: some View {
        VStack(spacing: 0) {
            HairlineDivider(region: true)
            HStack(spacing: 0) {
                ForEach(AppTab.allCases) { tab in
                    tabItem(tab).frame(maxWidth: .infinity)
                }
            }
        }
        .background(palette.ground)
    }

    private func tabItem(_ tab: AppTab) -> some View {
        let active = tab == current
        let tint = active ? palette.accent : palette.inkTertiary
        return VStack(spacing: 6) {
            CliampIcon(tab.icon, size: 17, tint: tint)
                .frame(height: 17)
            Text(tab.rawValue)
                .cliampText(CliampType.tabLabel)
                .foregroundStyle(tint)
                .lineLimit(1)
        }
        .padding(.top, 13)
        .padding(.bottom, max(30, bottomInset + 8))
        .overlay(alignment: .top) {
            if active {
                Rectangle()
                    .fill(palette.accent)
                    .frame(height: 2)
                    .offset(y: -1)
            }
        }
        .microPress { onSelect(tab) }
    }
}

struct CliampTabRail: View {
    @Environment(\.cliampPalette) private var palette
    let current: AppTab
    let onSelect: (AppTab) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Rectangle()
                .fill(palette.hairlineRegion)
                .frame(width: 1)
            VStack(spacing: 8) {
                ForEach(AppTab.allCases) { tab in
                    railItem(tab).frame(maxHeight: .infinity)
                }
            }
            .frame(width: cliampTabRailWidth - 1)
            .padding(.vertical, 10)
        }
        .frame(maxHeight: .infinity)
        .background(palette.ground)
    }

    private func railItem(_ tab: AppTab) -> some View {
        let active = tab == current
        let tint = active ? palette.accent : palette.inkTertiary
        return VStack(spacing: 5) {
            Spacer(minLength: 0)
            CliampIcon(tab.icon, size: 17, tint: tint)
                .frame(height: 17)
            Text(tab.rawValue)
                .cliampText(CliampType.tabLabel)
                .foregroundStyle(tint)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .frame(width: 70)
        .overlay(alignment: .trailing) {
            if active {
                Rectangle()
                    .fill(palette.accent)
                    .frame(width: 2)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: CliampShape.medium))
        .microPress { onSelect(tab) }
    }
}
