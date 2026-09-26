import CliampCore
import Foundation
import CliampDesign
import SwiftUI

struct RootView: View {
    @Environment(\.colorScheme) private var systemScheme
    @State private var app = AppState()
    @State private var player = RadioPlayer.shared
    @State private var tab: AppTab = .stations
    @State private var showSettings = false
    @State private var showPlayer = false
    @State private var podcastPath: [PodcastShow] = []
    @State private var showSearch = false

    var body: some View {
        let palette = cliampPalette(for: app.palettePreference, systemDark: systemScheme == .dark)
        MainShell(
            tab: $tab,
            player: player,
            app: app,
            onOpenSettings: { showSettings = true },
            onOpenPlayer: { showPlayer = true },
            podcastPath: $podcastPath,
            showSearch: $showSearch,
            showSettings: $showSettings
        )
        .cliampTheme(palette)
        .environment(\.cliampHapticsEnabled, app.haptics)
        .onChange(of: app.fallbackStations) { _, _ in
            player.refreshNavigation()
        }
        .fullScreenCover(isPresented: $showPlayer) {
            NowPlayingScreen(player: player, app: app, onClose: { showPlayer = false })
                .cliampTheme(palette)
                .environment(\.cliampHapticsEnabled, app.haptics)
        }

        .task {
            player.onRecordPlay = { [app] station in app.recordPlay(station) }
            player.fallbackProvider = { [app] in app.fallbackStations }
            player.onSpeedChange = { [app] value in app.speed = value }
            player.setSpeed(app.speed)
            // Episode playback: resume where it stopped, commit progress on
            // the podcast cadence, and prefer a downloaded file on disk.
            let services = PodcastServices.shared
            player.resumeProvider = { [app, podcasts = services.podcasts] station in
                // Local files start at zero unless Resume local songs is on;
                // episodes always resume (PlayerConnection.kt:941).
                if station.source == .local, !app.resumeLocalSongs { return 0 }
                return podcasts.resumePosition(station)
            }
            player.progressSink = { [podcasts = services.podcasts] station, position, duration in
                podcasts.saveProgress(station, positionMs: position, durationMs: duration)
            }
            player.downloadLookup = { [downloads = services.downloads] url in
                downloads.localPath(url: url)
            }
            // Provider tracks play over SFTP through the resource loader;
            // the player resolves accounts through the shared model.
            let providers = ProviderServices.shared.model
            // Cover art fallback for files with no companion image: read the
            // file's own tags, locally or over SFTP.
            StationArtwork.shared.installEmbeddedProvider { [providers] station in
                switch station.source {
                case .local:
                    guard let url = URL(string: station.url), url.isFileURL else { return nil }
                    return try await EmbeddedArtwork.extract(
                        from: LocalFileByteRangeReader(url: url),
                        fileExtension: url.pathExtension
                    )
                case .provider:
                    guard let ref = SftpURI.parse(station.url),
                          let session = await providers.session(forAccountId: ref.accountId)
                    else { return nil }
                    return try await EmbeddedArtwork.extract(
                        from: SftpByteRangeReader(session: session, path: ref.path),
                        fileExtension: (ref.path as NSString).pathExtension
                    )
                default:
                    return nil
                }
            }
            player.sftpSessionProvider = { @Sendable accountId in
                await providers.session(forAccountId: accountId)
            }
            services.search.favoritesProvider = { [app] in app.favorites }
            services.search.historyProvider = { [app] in app.history }
            // Android restores the last station to the bus but never plays it
            // unless auto-resume is on: a radio app that starts making noise
            // on launch is a bad neighbour (RAD-12).
            if let last = app.lastStation {
                if app.autoResume {
                    player.play(last)
                } else {
                    player.restore(last)
                }
            }
            #if DEBUG
            // Preview hooks for screenshots and UI tests; never compiled into
            // release builds.
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("-cliamp-preview-player") {
                player.play(CliampRadio.builtin[0])
                showPlayer = true
            }
            if arguments.contains("-cliamp-preview-playing") {
                player.play(CliampRadio.builtin[0])
            }
            if let index = arguments.firstIndex(of: "-cliamp-preview-provider"),
               index + 1 < arguments.count {
                // "<host>,<port>,<user>,<password>[,<folders>]" against a
                // local test server: seeds the account so the UI and the
                // player can be exercised without typing.
                let parts = arguments[index + 1]
                    .split(separator: ",", omittingEmptySubsequences: false)
                    .map(String.init)
                if parts.count >= 4 {
                    let folders = parts.count > 4 ? parts[4] : "/tmp/cliamp-sftp/music"
                    let existing = providers.accounts.first {
                        $0.values["host"] == parts[0] && $0.values["user"] == parts[2]
                    }
                    let account = providers.saveAccount(
                        id: existing?.id, providerKey: "ssh",
                        label: "\(parts[2])@\(parts[0]):\(parts[1])",
                        values: [
                            "host": parts[0], "port": parts[1], "user": parts[2],
                            "_auth": "password", "password": parts[3], "folders": folders,
                        ]
                    )
                    if arguments.contains("-cliamp-preview-provider-play"), let account {
                        let wanted = arguments.firstIndex(of: "-cliamp-preview-play-title")
                            .flatMap { index in
                                index + 1 < arguments.count ? arguments[index + 1] : nil
                            }
                        Task {
                            await providers.rescan(account)
                            let tracks = providers.tracks(accountId: account.id)
                            let track = wanted.flatMap { needle in
                                tracks.first { $0.title.localizedCaseInsensitiveContains(needle) }
                            } ?? tracks.first
                            if let track {
                                player.play(track.station)
                                showPlayer = true
                                // Optional scrub check: jump mid-track once
                                // the item is playing, exercising the same
                                // call the scrubber makes on release.
                                if let index = arguments.firstIndex(of: "-cliamp-preview-seek"),
                                   index + 1 < arguments.count,
                                   let fraction = Double(arguments[index + 1]) {
                                    try? await Task.sleep(for: .seconds(4))
                                    player.seek(toFraction: fraction)
                                }
                            }
                        }
                    }
                }
            }
            if let index = arguments.firstIndex(of: "-cliamp-preview-url"), index + 1 < arguments.count,
               let station = Station.custom(name: "preview", url: arguments[index + 1])
            {
                player.play(station)
                showPlayer = true
            }
            if arguments.contains("-cliamp-preview-settings") {
                showSettings = true
            }
            if arguments.contains("-cliamp-preview-pods") {
                tab = .pods
            }
            if arguments.contains("-cliamp-preview-search") {
                showSearch = true
            }
            if let index = arguments.firstIndex(of: "-cliamp-preview-search-query"),
               index + 1 < arguments.count
            {
                showSearch = true
                PodcastServices.shared.search.query = arguments[index + 1]
            }
            if let index = arguments.firstIndex(of: "-cliamp-preview-podcast"),
               index + 1 < arguments.count
            {
                let reference = arguments[index + 1]
                let play = arguments.contains("-cliamp-preview-podcast-play")
                let download = arguments.contains("-cliamp-preview-podcast-download")
                Task {
                    let resolved: PodcastShow?
                    if reference.contains("://") {
                        resolved = await PodcastDirectory.byFeedUrl(reference)
                    } else {
                        resolved = try? await PodcastDirectory.lookup(ids: [reference]).first
                    }
                    guard let show = resolved else { return }
                    tab = .pods
                    podcastPath.append(show)
                    let services = PodcastServices.shared
                    services.podcasts.openShow(show, force: true)
                    for _ in 0..<40 where services.podcasts.episodes.isEmpty {
                        try? await Task.sleep(for: .milliseconds(250))
                    }
                    guard let first = services.podcasts.episodes.first else { return }
                    let stations = services.podcasts.episodes.map { $0.station(show: services.podcasts.show ?? show) }
                    if download {
                        services.downloads.download(first.station(show: services.podcasts.show ?? show))
                    }
                    if play, let station = stations.first(where: { $0.url == first.audioUrl }) {
                        player.play(station, from: stations)
                    }
                }
            }
            if let index = arguments.firstIndex(of: "-cliamp-preview-palette"), index + 1 < arguments.count {
                app.palettePreference = arguments[index + 1]
            }
            #endif
        }
    }
}

#Preview {
    RootView()
}
