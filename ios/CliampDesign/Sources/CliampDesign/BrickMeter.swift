import SwiftUI

/// Column count and geometry presets, straight from the concept.
public enum MeterPreset: Sendable {
    case nowPlaying
    case scope
    case mini

    public var columns: Int {
        switch self {
        case .nowPlaying: 24
        case .scope: 32
        case .mini: 14
        }
    }

    public var brick: CGFloat {
        switch self {
        case .nowPlaying: 4
        case .scope: 6
        case .mini: 3
        }
    }

    public var gap: CGFloat {
        switch self {
        case .nowPlaying: 3
        case .scope: 4
        case .mini: 2
        }
    }

    public var columnGap: CGFloat {
        switch self {
        case .mini: 2
        default: 3
        }
    }

    public var height: CGFloat {
        switch self {
        case .nowPlaying: 66
        case .scope: 200
        case .mini: 22
        }
    }

    public var showsPeaks: Bool {
        self != .mini
    }
}

/// The signature element. Each column is three layers, all anchored to the
/// bottom so brick phase never shifts as the level animates:
///
///  1. unlit grid, full height
///  2. lit grid, height = level
///  3. peak cap, one brick tall, floating above and lagging behind
public struct BrickMeter: View {
    @Environment(\.cliampPalette) private var palette
    private let levels: [Float]
    private let peaks: [Float]
    private let preset: MeterPreset
    private let lit: Color?
    private let unlit: Color?
    private let peakColor: Color?

    public init(
        levels: [Float],
        peaks: [Float],
        preset: MeterPreset,
        lit: Color? = nil,
        unlit: Color? = nil,
        peak: Color? = nil
    ) {
        self.levels = levels
        self.peaks = peaks
        self.preset = preset
        self.lit = lit
        self.unlit = unlit
        self.peakColor = peak
    }

    public var body: some View {
        Canvas { context, size in
            let columnCount = min(preset.columns, min(levels.count, peaks.count))
            guard columnCount > 0 else { return }
            let brick = preset.brick
            let gap = preset.gap
            let columnGap = preset.columnGap
            let step = brick + gap
            let columnWidth = (size.width - columnGap * CGFloat(columnCount - 1)) / CGFloat(columnCount)
            guard columnWidth > 0 else { return }
            let rows = max(1, Int((size.height + gap) / step))
            let litColor = lit ?? palette.accent
            let unlitColor = unlit ?? palette.unlit
            let peakFill = peakColor ?? palette.peak

            for column in 0..<columnCount {
                let x = CGFloat(column) * (columnWidth + columnGap)
                let level = min(max(levels[column], 0), 1)
                let litRows = Int(Float(rows) * level)
                for row in 0..<rows {
                    let y = size.height - CGFloat(row + 1) * step + gap
                    let rect = CGRect(x: x, y: y, width: columnWidth, height: brick)
                    context.fill(
                        Path(rect),
                        with: .color(row < litRows ? litColor : unlitColor)
                    )
                }
                if preset.showsPeaks {
                    let peakRow = min(
                        max(Int(min(max(peaks[column], 0), 1) * Float(rows)), 0),
                        rows - 1
                    )
                    let rect = CGRect(
                        x: x,
                        y: size.height - CGFloat(peakRow + 1) * step + gap,
                        width: columnWidth,
                        height: brick
                    )
                    context.fill(Path(rect), with: .color(peakFill))
                }
            }
        }
        .frame(height: preset.height)
        .accessibilityHidden(true)
    }
}
