import CliampCore
import CliampDesign
import SwiftUI

/// The full player. Portrait stacks art, meta and transport; a wide frame
/// (landscape phone, tablet on its side) splits art on the left from the
/// controls on the right, the same break Android uses.
struct NowPlayingScreen: View {
    @Environment(\.cliampPalette) private var palette
    let player: RadioPlayer
    let app: AppState
    let onClose: () -> Void
    @State private var meter: MeterModel

    init(player: RadioPlayer, app: AppState, onClose: @escaping () -> Void) {
        self.player = player
        self.app = app
        self.onClose = onClose
        _meter = State(initialValue: MeterModel(preset: .nowPlaying, player: player))
    }

    var body: some View {
        GeometryReader { proxy in
            if proxy.size.width > proxy.size.height {
                landscape(proxy: proxy)
            } else {
                portrait(proxy: proxy)
            }
        }
        .background(palette.ground)
        .onAppear {
            if app.visualizer != "off" { meter.start() }
        }
        .onDisappear { meter.stop() }
        .onChange(of: app.visualizer) { _, value in
            // spectrum/off removes the rendering work, not just the view.
            if value == "off" { meter.stop() } else { meter.start() }
        }
    }

    // MARK: portrait

    private func portrait(proxy: GeometryProxy) -> some View {
        let artSide = min(proxy.size.width - cliampGutter * 2, 340)
        return VStack(spacing: 0) {
            // Match Android's portrait header: full-width row inset by the
            // page gutter, so the back key and the queue chip keep the same
            // margin as every other screen's chrome.
            headerRow.padding(.horizontal, cliampGutter)
            VStack(spacing: 0) {
                Spacer(minLength: 8)
                art(side: artSide)
                Spacer().frame(height: 18)
                statusStrip
                Spacer().frame(height: 7)
                meta
                Spacer(minLength: 14)
                transport
                Spacer(minLength: 12)
            }
            .frame(maxWidth: 460)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, cliampGutter)
        }
    }

    // MARK: landscape

    /// Art and status on the left, header, meta and transport on the right.
    private func landscape(proxy: GeometryProxy) -> some View {
        let side = min(proxy.size.height - 76, (proxy.size.width - cliampGutter * 2) * 0.44)
        let artSide = min(max(side, 96), 340)
        return HStack(alignment: .center, spacing: 14) {
            VStack(spacing: 14) {
                art(side: artSide)
                statusStrip
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            VStack(alignment: .leading, spacing: 8) {
                headerRow
                meta
                Spacer(minLength: 0)
                transport
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .padding(.horizontal, cliampGutter)
        .padding(.vertical, 6)
    }

    private var headerRow: some View {
        HStack(spacing: 8) {
            BackChevron(action: onClose)
            Spacer()
            upNextChip
        }
        .frame(height: 48)
    }

    private var upNextChip: some View {
        HStack(spacing: 8) {
            CliampIcon(CliampIcons.queueTabLines, size: 14, tint: palette.accent)
            Text("UP NEXT")
                .cliampText(CliampType.chip)
                .foregroundStyle(palette.ink)
            Text("0")
                .cliampText(CliampType.chip)
                .foregroundStyle(palette.inkFaint)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .overlay(
            RoundedRectangle(cornerRadius: CliampShape.small)
                .stroke(palette.chipBorder, lineWidth: 1)
        )
    }

    private func art(side: CGFloat) -> some View {
        StationArtPlate(station: player.station, side: side)
            .frame(maxWidth: .infinity)
    }

    private var meta: some View {
        VStack(alignment: .leading, spacing: 7) {
            MarqueeText(player.station?.name ?? "pick a station", style: CliampType.trackTitle)
                .foregroundStyle(palette.ink)
            MarqueeText(metaSecondary, style: CliampType.rowPrimary)
                .foregroundStyle(secondaryColor)
            Text(player.station?.playerSourceLine ?? "cliamp radio")
                .cliampText(CliampType.body)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The ICY stream title when the stream sends one, otherwise the error or
    /// the station's tag line.
    private var metaSecondary: String {
        if !player.streamTitle.isEmpty { return player.streamTitle }
        if let error = player.error { return error }
        guard let station = player.station else { return "nothing playing" }
        return station.tagList.prefix(3).joined(separator: " · ")
    }

    private var secondaryColor: Color {
        if player.error != nil, player.streamTitle.isEmpty { return palette.destructiveInk }
        return palette.inkSecondary
    }

    private var statusStrip: some View {
        HStack(spacing: 8) {
            CliampIcon(CliampIcons.playTiny, width: 9, height: 10, tint: statusColor)
                .opacity(player.playing ? 1 : 0.6)
            Text(statusLabel)
                .cliampText(CliampType.nowPlayingLabel)
                .foregroundStyle(statusColor)
            Spacer()
            smallAction(CliampIcons.shuffle, label: "shuffle", enabled: false, tint: palette.inkSecondary) {}
            speedKey
            smallAction(CliampIcons.meterSmall, label: "scope and equaliser", enabled: false, tint: palette.inkSecondary) {}
            smallAction(
                app.isFavorite(player.station ?? placeholder) ? CliampIcons.starFilled : CliampIcons.star,
                label: "favourite",
                enabled: true,
                tint: app.isFavorite(player.station ?? placeholder) ? palette.accent : palette.inkTertiary
            ) {
                if let station = player.station { app.toggleFavorite(station) }
            }
        }
    }

    private var placeholder: Station {
        Station(id: "", name: "", url: "", source: .cliamp)
    }

    private var statusLabel: String {
        if player.reconnecting { return "RECONNECTING · \(player.reconnectAttempt)" }
        if player.error != nil { return "STREAM ERROR" }
        if player.buffering { return "BUFFERING" }
        if player.playing { return "ON AIR" }
        return player.station == nil ? "IDLE" : "PAUSED"
    }

    private var statusColor: Color {
        if player.reconnecting { return palette.amber }
        if player.error != nil { return palette.destructiveInk }
        if player.buffering { return palette.amber }
        return player.playing ? palette.accent : palette.inkSecondary
    }

    /// The playback-speed key: taps step the Android ladder, accent when the
    /// pace is not normal.
    private var speedKey: some View {
        Text(PlaybackSpeed.label(player.speed))
            .cliampText(CliampType.meta)
            .foregroundStyle(player.speed == 1 ? palette.inkSecondary : palette.accent)
            .frame(width: 32, height: 32)
            .microPress { player.cycleSpeed() }
            .accessibilityLabel("playback speed")
    }

    private func smallAction(
        _ icon: CliampVector, label: String, enabled: Bool, tint: Color, action: @escaping () -> Void
    ) -> some View {
        CliampIcon(icon, size: 16, tint: enabled ? tint : palette.inkFaint.opacity(0.5))
            .frame(width: 32, height: 32)
            .microPress(enabled: enabled, action: action)
            .accessibilityLabel(label)
    }

    private var transport: some View {
        VStack(spacing: 11) {
            if app.visualizer != "off" {
                BrickMeter(levels: meter.levels, peaks: meter.peaks, preset: .nowPlaying)
                    .frame(maxWidth: .infinity)
            }
            // What the transport shows follows what the source can do: a
            // finite, seekable item scrubs; ICY radio streams.
            if player.scrubbable, player.error == nil, !player.reconnecting {
                Scrubber(
                    fraction: player.durationMs > 0
                        ? Double(player.elapsedMs) / Double(player.durationMs) : 0
                ) { fraction in
                    player.seek(toFraction: fraction)
                }
                HStack {
                    Text(TimeFormat.clock(player.elapsedMs))
                        .cliampText(CliampType.time)
                        .foregroundStyle(palette.inkSecondary)
                    Spacer()
                    Text("-" + TimeFormat.clock(max(0, player.durationMs - player.elapsedMs)))
                        .cliampText(CliampType.time)
                        .foregroundStyle(palette.inkSecondary)
                }
                .padding(.bottom, 6)
            } else {
                StreamingRule(streamingLabel, color: statusColor, dim: !player.playing && player.error == nil)
                HStack {
                    Text(TimeFormat.clock(player.elapsedMs))
                        .cliampText(CliampType.time)
                        .foregroundStyle(palette.inkSecondary)
                    Spacer()
                    Text(player.playing ? "\(player.bufferedSeconds)s buffered" : "tap the meter for scope · eq")
                        .cliampText(CliampType.timeSmall)
                        .foregroundStyle(palette.inkFaint)
                }
                .padding(.bottom, 6)
            }
            GeometryReader { proxy in
                let spacing = 9.0
                let available = max(0, proxy.size.width - spacing * 2)
                HStack(spacing: spacing) {
                    MechKey(enabled: player.hasPrev, action: { player.goPrevious() }) {
                        CliampIcon(CliampIcons.prev, width: 21, height: 17, tint: palette.ink)
                    }
                    .frame(width: available / 3.7)
                    MechKey(filled: true, height: 64, action: { player.toggle() }) {
                        CliampIcon(
                            player.wantsToPlay ? CliampIcons.pause : CliampIcons.playTab,
                            width: player.wantsToPlay ? 20 : 22,
                            height: player.wantsToPlay ? 22 : 22,
                            tint: palette.onAccent
                        )
                    }
                    .frame(width: available * 1.7 / 3.7)
                    MechKey(enabled: player.hasNext, action: { player.goNext() }) {
                        CliampIcon(CliampIcons.next, width: 21, height: 17, tint: palette.ink)
                    }
                    .frame(width: available / 3.7)
                }
            }
            .frame(height: 64)
        }
    }

    private var streamingLabel: String {
        if player.reconnecting { return "reconnecting" }
        if player.error != nil { return "no signal" }
        if player.buffering { return "buffering" }
        if player.playing { return "streaming" }
        return player.station == nil ? "stopped" : "paused"
    }
}
