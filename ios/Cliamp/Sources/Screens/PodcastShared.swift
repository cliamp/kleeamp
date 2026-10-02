import CliampCore
import CliampDesign
import SwiftUI

/// Helper views shared by the podcast screens: a show's artwork as a station,
/// and the date/duration vocabulary of an episode row.
extension PodcastShow {
    /// The artwork keystone: podcast covers are known URLs, which
    /// `StationArtwork` treats as authoritative before any scraping.
    var artStation: Station {
        Station(
            id: id,
            name: title,
            url: feedUrl,
            source: .podcast,
            slug: id,
            cover: artwork,
            artist: author
        )
    }
}

extension PodcastEpisode {
    /// `1h 35m` / `28m`, Android's podcast duration copy. Nil when unknown.
    var durationLabel: String? {
        guard durationMs > 0 else { return nil }
        let total = durationMs / 1000
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        return hours > 0 ? "\(hours)h \(minutes)m" : "\(minutes)m"
    }

    /// `2 sep · 1:04:12`, skipping whatever the feed omitted.
    var listMeta: String {
        var parts: [String] = []
        if publishedAt > 0 {
            parts.append(Self.dateLabel(publishedAt))
        }
        if durationMs > 0 {
            parts.append(TimeFormat.clock(durationMs))
        }
        if !isFull {
            parts.append(type.lowercased())
        }
        return parts.joined(separator: " · ")
    }

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "d MMM"
        return formatter
    }()

    private static let fullFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "d MMM yyyy"
        return formatter
    }()

    /// Same year drops the year, matching the Android episode list.
    static func dateLabel(_ milliseconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        let year = Calendar.current.component(.year, from: date)
        let now = Calendar.current.component(.year, from: Date())
        let formatter = year == now ? dayFormatter : fullFormatter
        return formatter.string(from: date).lowercased()
    }
}

/// A grid tile for one show, the podcast twin of `StationTile`.
struct ShowTile: View {
    @Environment(\.cliampPalette) private var palette
    let show: PodcastShow
    let subscribed: Bool
    let onOpen: () -> Void
    let onToggleSubscribe: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            StationArtSquare(station: show.artStation, fallback: .podcast)
                .overlay(alignment: .topTrailing) {
                    if let onToggleSubscribe {
                        SubscribeStar(subscribed: subscribed, action: onToggleSubscribe)
                            .padding(10)
                    }
                }
            Text(show.title)
                .cliampText(CliampType.rowPrimaryMedium)
                .foregroundStyle(palette.ink)
                .lineLimit(2)
            Text(show.meta)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 2)
        .clampTap(action: onOpen)
    }
}

/// A list row for one show.
struct ShowRow: View {
    @Environment(\.cliampPalette) private var palette
    let show: PodcastShow
    let subscribed: Bool
    let onOpen: () -> Void
    let onToggleSubscribe: (() -> Void)?

    var body: some View {
        ListRow(
            onClick: onOpen,
            leading: {
                StationArtView(station: show.artStation, size: 40, fallback: .podcast)
            },
            trailing: {
                HStack(spacing: 12) {
                    if let onToggleSubscribe {
                        SubscribeStar(subscribed: subscribed, action: onToggleSubscribe)
                    }
                    CliampIcon(CliampIcons.caretRight, size: 9, tint: palette.inkFaint)
                }
            },
            verticalPadding: 9,
            railOffset: cliampGutter
        ) {
            Text(show.title)
                .cliampText(CliampType.rowPrimary)
                .foregroundStyle(palette.ink)
                .lineLimit(1)
            Text(show.meta)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkTertiary)
                .lineLimit(1)
        }
    }
}

/// The subscribe toggle: a star, like a favourite, because a subscription is
/// the podcast's favourite.
struct SubscribeStar: View {
    @Environment(\.cliampPalette) private var palette
    let subscribed: Bool
    let action: () -> Void

    var body: some View {
        CliampIcon(
            subscribed ? CliampIcons.starFilled : CliampIcons.star,
            size: 15,
            tint: subscribed ? palette.accent : palette.inkFaint
        )
        .frame(width: 30, height: 30)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityLabel(subscribed ? "unsubscribe" : "subscribe")
    }
}
