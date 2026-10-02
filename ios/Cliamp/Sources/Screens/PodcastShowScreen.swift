import CliampCore
import CliampDesign
import SwiftUI

/// One show: header, subscribe toggle, and its episodes newest-first with
/// download controls. Ported from Android's `PodcastShowScreen`.
struct PodcastShowScreen: View {
    @Environment(\.cliampPalette) private var palette
    let player: RadioPlayer
    let podcasts: PodcastsModel
    let downloads: DownloadManager
    let show: PodcastShow
    let onOpenSearch: () -> Void
    let onOpenSettings: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var topTrigger = 0

    var body: some View {
        VStack(spacing: 0) {
            CliampHeader(
                "Podcast",
                onBack: { dismiss() },
                onSearch: onOpenSearch,
                onSettings: onOpenSettings,
                onTitleTap: { topTrigger += 1 }
            )
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        header.id("top")
                        if !podcasts.episodes.isEmpty {
                            SectionLabel("episodes — \(podcasts.episodes.count)") {
                                Text("refresh")
                                    .cliampText(CliampType.meta)
                                    .foregroundStyle(palette.inkTertiary)
                                    .microPress { podcasts.refreshShow() }
                            }
                        }
                        episodesSection
                        Spacer().frame(height: 20)
                    }
                }
                .onChange(of: topTrigger) { _, _ in
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo("top", anchor: .top)
                    }
                }
            }
        }
        .background(palette.ground)
        .task { podcasts.openShow(show) }
    }

    private var currentShow: PodcastShow {
        podcasts.show ?? show
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 14) {
                StationArtSquare(
                    station: currentShow.artStation,
                    corner: CliampShape.small,
                    fallback: .podcastStandalone
                )
                .frame(width: 140, height: 140)
                    .overlay(
                        RoundedRectangle(cornerRadius: CliampShape.small)
                            .stroke(palette.frameBorder, lineWidth: 1)
                    )
                VStack(alignment: .leading, spacing: 5) {
                    Text(currentShow.title)
                        .cliampText(CliampType.rowPrimaryMedium)
                        .foregroundStyle(palette.ink)
                        .lineLimit(2)
                    if !currentShow.author.isEmpty {
                        Text(currentShow.author)
                            .cliampText(CliampType.rowSecondary)
                            .foregroundStyle(palette.inkSecondary)
                            .lineLimit(1)
                    }
                    if !currentShow.meta.isEmpty {
                        Text(currentShow.meta)
                            .cliampText(CliampType.meta)
                            .foregroundStyle(palette.inkTertiary)
                            .lineLimit(1)
                    }
                    Spacer().frame(height: 3)
                    Chip(
                        subscribed ? "subscribed" : "subscribe",
                        selected: subscribed
                    ) {
                        podcasts.toggleSubscription(currentShow)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, cliampGutter)
            .padding(.vertical, 14)
            if !currentShow.description.isEmpty {
                Text(currentShow.description)
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.inkTertiary)
                    .lineLimit(4)
                    .padding(.horizontal, cliampGutter)
                    .padding(.bottom, 14)
            }
            HairlineDivider()
        }
    }

    private var subscribed: Bool {
        podcasts.isSubscribed(feedUrl: currentShow.feedUrl)
    }

    @ViewBuilder
    private var episodesSection: some View {
        if podcasts.showLoading, podcasts.episodes.isEmpty {
            EmptyNote("reading the feed…")
        } else if let error = podcasts.showError, podcasts.episodes.isEmpty {
            RetryNote("couldn't read the feed", prominent: true) {
                _ = error
                podcasts.refreshShow()
            }
        } else if podcasts.episodes.isEmpty {
            EmptyNote("no episodes in this feed")
        } else {
            ForEach(podcasts.episodes, id: \.guid) { episode in
                let station = episode.station(show: currentShow)
                EpisodeRow(
                    episode: episode,
                    station: station,
                    active: player.station?.url == station.url,
                    playing: player.playing && player.station?.url == station.url,
                    progress: podcasts.progressEntry(for: station.url),
                    downloadState: downloads.state(for: station.url),
                    downloadedBytes: downloads.entry(for: station.url)?.bytes ?? 0,
                    onPlay: { play(episode) },
                    onPlayNext: { player.playNext(station) },
                    onAddToQueue: { player.addToQueue(station) },
                    onMarkPlayed: { podcasts.markCompleted(station) },
                    onForget: { podcasts.clearProgress(station) },
                    onDownload: { downloads.download(station) },
                    onCancelDownload: { downloads.cancel(url: station.url) },
                    onRemoveDownload: { downloads.remove(url: station.url) }
                )
            }
        }
    }

    /// Episodes play in the displayed show order; the whole list is the
    /// source, so Next walks to the following episode.
    private func play(_ episode: PodcastEpisode) {
        let stations = podcasts.episodes.map { $0.station(show: currentShow) }
        guard let station = stations.first(where: { $0.url == episode.audioUrl }) else { return }
        // Same-show taps preserve the arranged tail; a different show replaces
        // it, exactly like Android's playFromList.
        player.play(station, from: stations, contextKey: "podcast:\(currentShow.feedUrl)")
    }

    private func toggleDownload(_ episode: PodcastEpisode) {
        let url = episode.audioUrl
        if downloads.isDownloaded(url: url) {
            downloads.remove(url: url)
            return
        }
        if case .active = downloads.state(for: url) {
            downloads.cancel(url: url)
            return
        }
        downloads.download(episode.station(show: currentShow))
    }
}

/// One episode row, ported from Android's `EpisodeRow`: a podRow artwork
/// thumb with the play/pause/check badge bottom-right, the download control
/// left of the overflow, and the metadata line that carries offline size,
/// fetching state and "played".
private struct EpisodeRow: View {
    @Environment(\.cliampPalette) private var palette
    let episode: PodcastEpisode
    let station: Station
    let active: Bool
    let playing: Bool
    let progress: EpisodeProgress?
    let downloadState: DownloadState?
    let downloadedBytes: Int64
    let onPlay: () -> Void
    let onPlayNext: () -> Void
    let onAddToQueue: () -> Void
    let onMarkPlayed: () -> Void
    let onForget: () -> Void
    let onDownload: () -> Void
    let onCancelDownload: () -> Void
    let onRemoveDownload: () -> Void

    private var done: Bool { progress?.completed == true }
    private var fetched: Bool { downloadedBytes > 0 }

    var body: some View {
        ListRow(
            onClick: onPlay,
            leading: { artwork },
            trailing: {
                HStack(spacing: 12) {
                    downloadControl
                    menu
                }
            },
            verticalPadding: 11,
            rail: active,
            railOffset: cliampGutter
        ) {
            Text(station.name)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(active ? palette.accent : (done ? palette.inkTertiary : palette.ink))
                .lineLimit(2)
            Text(metaLine)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(progress != nil && !done ? palette.amber : palette.inkTertiary)
                .lineLimit(1)
        }
    }

    private var artwork: some View {
        StationArtView(station: station, size: 40, fallback: .podcast)
            .overlay(
                RoundedRectangle(cornerRadius: CliampShape.small)
                    .stroke(active ? palette.accent : palette.frameBorder, lineWidth: 1)
            )
            .overlay(alignment: .bottomTrailing) {
                if active || done {
                    Circle()
                        .fill(active ? palette.accent : palette.chipBorder)
                        .frame(width: 17, height: 17)
                        .overlay(
                            CliampIcon(
                                playing ? CliampIcons.pause : (done ? CliampIcons.check : CliampIcons.playRow),
                                size: 9,
                                tint: active ? palette.onAccent : palette.inkFaint
                            )
                        )
                }
            }
    }

    @ViewBuilder
    private var downloadControl: some View {
        switch downloadState {
        case .active(let fraction, let read, _):
            Text(indeterminate(fraction) ? downloadSizeLabel(read) : "\(Int(fraction * 100))%")
                .cliampText(CliampType.meta)
                .foregroundStyle(palette.amber)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .contentShape(Rectangle())
                .onTapGesture(perform: onCancelDownload)
        case .failed:
            Text("retry")
                .cliampText(CliampType.meta)
                .foregroundStyle(palette.destructiveInk)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .contentShape(Rectangle())
                .onTapGesture(perform: onDownload)
        case nil:
            CliampIcon(
                CliampIcons.download,
                size: 15,
                tint: fetched ? palette.accent : palette.inkTertiary
            )
            .frame(width: 30, height: 30)
            .contentShape(Rectangle())
            .onTapGesture {
                // A completed download is passive; removal is the menu's job.
                if !fetched { onDownload() }
            }
            .accessibilityLabel(fetched ? "downloaded" : "download")
        }
    }

    private var menu: some View {
        Menu {
            Button("play next", action: onPlayNext)
            Button("add to queue", action: onAddToQueue)
            if fetched {
                Button("remove download", role: .destructive, action: onRemoveDownload)
            } else if case .active = downloadState {
                Button("cancel download", role: .destructive, action: onCancelDownload)
            } else {
                Button("download", action: onDownload)
            }
            Button(done ? "mark unplayed" : "mark played") {
                if done { onForget() } else { onMarkPlayed() }
            }
        } label: {
            CliampIcon(CliampIcons.more, size: 16, tint: palette.ink)
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
        }
    }

    private func indeterminate(_ fraction: Double) -> Bool { fraction < 0 }

    private var metaLine: String {
        var parts: [String] = []
        if !episode.isFull { parts.append(episode.type.lowercased()) }
        if episode.publishedAt > 0 { parts.append(PodcastEpisode.dateLabel(episode.publishedAt)) }
        if let duration = episode.durationLabel { parts.append(duration) }
        if fetched {
            parts.append("offline · \(downloadSizeLabel(downloadedBytes))")
        } else {
            switch downloadState {
            case .active(let fraction, let read, _):
                parts.append(
                    indeterminate(fraction)
                        ? "fetching \(downloadSizeLabel(read))"
                        : "fetching \(Int(fraction * 100))%"
                )
            case .failed(let reason):
                parts.append(reason)
            case nil:
                break
            }
        }
        if let progress, !progress.completed, progress.positionMs > 0 {
            parts.append("\(Int(progress.fraction * 100))% in")
        }
        if done { parts.append("played") }
        return parts.joined(separator: " · ")
    }
}
