import CliampCore
import CliampDesign
import SwiftUI

enum StationArtFallback {
    case glyph
    case podcast
    /// The bare muted glyph, no plate, for show headers.
    case podcastStandalone
    case music
    case plate
}

enum StationArtTarget {
    case small
    case full
}

/// A fixed-size station thumbnail: real branding when it exists, otherwise the
/// broadcast mark the app has always drawn.
struct StationArtView: View {
    @Environment(\.cliampPalette) private var palette
    let station: Station
    let size: CGFloat
    var corner: CGFloat = CliampShape.small
    var fallback: StationArtFallback = .glyph
    var target: StationArtTarget = .small
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                switch fallback {
                case .glyph:
                    GlyphPlate(CliampIcons.stationsTab, size: size)
                case .podcast:
                    GlyphPlate(CliampIcons.podRow, size: min(size, 40))
                case .podcastStandalone:
                    CliampIcon(CliampIcons.podsTab, size: min(size, 40), tint: palette.inkFaint)
                case .music:
                    GlyphPlate(CliampIcons.musicNote, size: size)
                case .plate:
                    ArtPlate(radius: corner)
                        .overlay(
                            CliampIcon(CliampIcons.stationsTab, size: size * 0.5, tint: palette.accent)
                        )
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: corner))
        .task(id: station.id) {
            if target == .small, let cached = StationArtwork.shared.cachedSmall(for: station) {
                image = cached
                return
            }
            if target == .full, let cached = StationArtwork.shared.cached(for: station) {
                image = cached
                return
            }
            let loaded = target == .small
                ? await StationArtwork.shared.smallImage(for: station)
                : await StationArtwork.shared.image(for: station)
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}

/// A square that fills whatever width the layout gives it, for grid tiles.
/// Tiles load the full-size image, the way the Android tiles use `bitmapFor`.
struct StationArtSquare: View {
    let station: Station
    var corner: CGFloat = CliampShape.medium
    var fallback: StationArtFallback = .plate

    var body: some View {
        GeometryReader { proxy in
            StationArtView(
                station: station,
                size: proxy.size.width,
                corner: corner,
                fallback: fallback,
                target: .full
            )
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

/// The player's hero plate: glow, soft shadow, slow breath, and the Android
/// rule for how art occupies it. Radio art is often a 1200x630 wordmark, so it
/// stays inset and contained unless the image is square enough to fill.
struct StationArtPlate: View {
    @Environment(\.cliampPalette) private var palette
    let station: Station?
    let side: CGFloat
    @State private var image: UIImage?
    @State private var breathing = false

    var body: some View {
        ZStack {
            ArtGlow()
            ArtPlate(radius: CliampShape.large)
                .shadow(
                    color: .black.opacity(palette.dark ? 0.45 : 0.2),
                    radius: palette.dark ? 26 : 20,
                    y: palette.dark ? 12 : 8
                )
                .overlay { art }
                .overlay(alignment: .bottomLeading) {
                    if image == nil, let station {
                        Text(caption(station))
                            .cliampText(CliampType.meta)
                            .tracking(1.1)
                            .foregroundStyle(palette.inkTertiary)
                            .padding(14)
                    }
                }
                .scaleEffect(breathing ? 1.015 : 1)
                .animation(
                    .easeInOut(duration: 7).repeatForever(autoreverses: true),
                    value: breathing
                )
        }
        .frame(width: side, height: side)
        .onAppear { breathing = true }
        .task(id: station?.id) {
            setImage(nil)
            guard let station else { return }
            let loaded: UIImage?
            if let cached = StationArtwork.shared.cached(for: station) {
                loaded = cached
            } else {
                loaded = await StationArtwork.shared.image(for: station)
            }
            guard !Task.isCancelled else { return }
            setImage(loaded)
        }
    }

    @ViewBuilder
    private var art: some View {
        if let image, let station {
            let fills = station.source == .local
                || station.source == .provider
                || station.source == .podcast
                || Self.isSquarish(image)
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: fills ? .fill : .fit)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(fills ? 0 : 14)
                .clipShape(RoundedRectangle(cornerRadius: CliampShape.large))
        } else if station != nil {
            CliampIcon(CliampIcons.stationsTab, size: side * 0.28, tint: palette.accent)
        }
    }

    /// The plate's slow breathing animation repeats forever, and SwiftUI
    /// hands that animation to any later change in the same subtree — the
    /// artwork swap would then cross-fade in over seven seconds. Swapping
    /// inside a transaction with animations disabled keeps the plate
    /// breathing and the art appearing all at once.
    private func setImage(_ loaded: UIImage?) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { image = loaded }
    }

    private static func isSquarish(_ image: UIImage) -> Bool {
        let width = max(image.size.width, 1)
        let height = max(image.size.height, 1)
        let aspect = width / height
        return aspect >= 0.85 && aspect <= 1.18 && min(width, height) >= 96
    }

    private func caption(_ station: Station) -> String {
        switch station.source {
        case .local:
            return station.album.isEmpty
                ? "[ local file ]"
                : "[ \(station.album.lowercased()) · \(station.artist.lowercased()) ]"
        case .provider:
            return station.album.isEmpty
                ? "[ provider ]"
                : "[ \(station.album.lowercased()) · \(station.artist.lowercased()) ]"
        case .podcast:
            return station.artist.isEmpty
                ? "[ podcast ]"
                : "[ \(station.artist.lowercased()) · podcast ]"
        case .cliamp:
            return "[ \(station.slug) · cliamp radio ]"
        default:
            return station.countryCode.isEmpty
                ? "[ live stream ]"
                : "[ \(station.countryCode.lowercased()) · live stream ]"
        }
    }
}

/// The soft radial wash of accent behind the hero art.
struct ArtGlow: View {
    @Environment(\.cliampPalette) private var palette

    var body: some View {
        Circle()
            .fill(
                RadialGradient(
                    colors: [
                        palette.accent.opacity(palette.dark ? 0.16 : 0.30),
                        palette.accent.opacity(palette.dark ? 0.05 : 0.10),
                        .clear,
                    ],
                    center: .center,
                    startRadius: 0,
                    endRadius: 140
                )
            )
            .scaleEffect(1.45)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
    }
}
