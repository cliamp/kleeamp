import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// One tick, the way `VirtualKey` feels on Android.
@MainActor
public enum CliampHaptics {
    public static func tick() {
        #if canImport(UIKit)
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred(intensity: 0.7)
        #endif
    }
}

/// A quick springing scale-down with a slight dim while pressed, and a light
/// tick on release; no ripple, nothing Material.
public struct MicroPressStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.955 : 1)
            .opacity(configuration.isPressed ? 0.82 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

private struct MicroPressModifier: ViewModifier {
    @Environment(\.cliampHapticsEnabled) private var hapticsEnabled
    let enabled: Bool
    let action: () -> Void

    func body(content: Content) -> some View {
        Button {
            if hapticsEnabled { CliampHaptics.tick() }
            action()
        } label: {
            content.contentShape(Rectangle())
        }
        .buttonStyle(MicroPressStyle())
        .disabled(!enabled)
    }
}

public extension View {
    func microPress(enabled: Bool = true, action: @escaping () -> Void) -> some View {
        modifier(MicroPressModifier(enabled: enabled, action: action))
    }

    /// Applies [microPress] only when the action exists, so optional row taps
    /// stay plain views.
    @ViewBuilder
    func clampTap(action: (() -> Void)?) -> some View {
        if let action {
            microPress(action: action)
        } else {
            self
        }
    }
}

/// 11-point uppercase filter chip. Selected fills accent (dark) or ink (light).
public struct Chip: View {
    @Environment(\.cliampPalette) private var palette
    private let label: String
    private let selected: Bool
    private let accent: Color?
    private let action: () -> Void

    public init(_ label: String, selected: Bool, accent: Color? = nil, action: @escaping () -> Void) {
        self.label = label
        self.selected = selected
        self.accent = accent
        self.action = action
    }

    public var body: some View {
        let fill = accent ?? (palette.dark ? palette.accent : palette.ink)
        let onFill = palette.dark ? palette.onAccent : palette.ground
        Text(label.uppercased())
            .cliampText(CliampType.chip)
            .foregroundStyle(selected ? onFill : palette.inkTertiary)
            .lineLimit(1)
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(selected ? fill : .clear)
            .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
            .overlay {
                if !selected {
                    RoundedRectangle(cornerRadius: CliampShape.small)
                        .stroke(palette.chipBorder, lineWidth: 1)
                }
            }
            .microPress(action: action)
    }
}

/// 44 x 26 pill, 20-point knob, accent when on.
public struct CliampToggle: View {
    @Environment(\.cliampPalette) private var palette
    private let on: Bool
    private let onChange: (Bool) -> Void

    public init(on: Bool, onChange: @escaping (Bool) -> Void) {
        self.on = on
        self.onChange = onChange
    }

    public var body: some View {
        let knobColor = on
            ? (palette.dark ? palette.onAccent : palette.ground)
            : (palette.dark ? palette.inkTertiary : palette.ground)
        RoundedRectangle(cornerRadius: 13)
            .fill(on ? palette.accent : (palette.dark ? Color(argb: 0xFF26_2A26) : palette.hairlineRegion))
            .frame(width: 44, height: 26)
            .overlay(alignment: .leading) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(knobColor)
                    .frame(width: 20, height: 20)
                    .offset(x: on ? 21 : 3)
                    .animation(.spring(response: 0.3, dampingFraction: 0.8), value: on)
            }
            .microPress(action: { onChange(!on) })
    }
}

/// Square-ish handle with the same bevel as the keys; never a circle.
public struct MechSlider: View {
    @Environment(\.cliampPalette) private var palette
    private let value: Double
    private let range: ClosedRange<Double>
    private let onChange: (Double) -> Void

    public init(value: Double, range: ClosedRange<Double>, onChange: @escaping (Double) -> Void) {
        self.value = value
        self.range = range
        self.onChange = onChange
    }

    public var body: some View {
        GeometryReader { proxy in
            let handle = 20.0
            let span = max(1, proxy.size.width - handle)
            let fraction = ((value - range.lowerBound) / (range.upperBound - range.lowerBound))
                .clamped(to: 0...1)
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(palette.track)
                    .frame(height: 6)
                    .frame(maxHeight: .infinity, alignment: .center)
                Rectangle()
                    .fill(palette.accent)
                    .frame(width: proxy.size.width * fraction, height: 6)
                    .frame(maxHeight: .infinity, alignment: .center)
                handleView
                    .offset(x: fraction * span)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { drag in
                        let position = (drag.location.x - handle / 2) / span
                        let clamped = position.clamped(to: 0...1)
                        onChange(range.lowerBound + clamped * (range.upperBound - range.lowerBound))
                    }
            )
        }
        .frame(height: 34)
    }

    private var handleView: some View {
        RoundedRectangle(cornerRadius: CliampShape.tiny)
            .fill(palette.dark ? palette.keyFace : palette.ground)
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(palette.dark ? palette.keyBevel : palette.hairline)
                    .frame(height: 3)
            }
            .clipShape(RoundedRectangle(cornerRadius: CliampShape.tiny))
            .overlay(
                RoundedRectangle(cornerRadius: CliampShape.tiny)
                    .stroke(palette.keyBorder, lineWidth: 1)
            )
            .frame(width: 20, height: 20)
    }
}

/// The playback timeline, ported from Android's `Scrubber`: a 4-point track
/// with a 3-point playhead, and a small key thumb that only appears while a
/// finger is down so a drag reads as grabbing the timeline. A tap seeks where
/// it landed; a drag follows the finger and commits on release.
///
/// The position shown while dragging is local — the player's own clock lags
/// the gesture (and over SFTP a seek is a round trip), so following it would
/// make the playhead stutter backwards under the finger.
public struct Scrubber: View {
    @Environment(\.cliampPalette) private var palette
    private let fraction: Double
    private let onSeek: (Double) -> Void

    @State private var dragging = false
    @State private var dragFraction: Double = 0

    public init(fraction: Double, onSeek: @escaping (Double) -> Void) {
        self.fraction = fraction
        self.onSeek = onSeek
    }

    public var body: some View {
        GeometryReader { proxy in
            let width = max(1, proxy.size.width)
            let shown = min(max(dragging ? dragFraction : fraction, 0), 1)
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(palette.track)
                    .frame(height: 4)
                    .frame(maxHeight: .infinity, alignment: .center)
                Rectangle()
                    .fill(palette.accent)
                    .frame(width: width * shown, height: 4)
                    .frame(maxHeight: .infinity, alignment: .center)
                Rectangle()
                    .fill(palette.peak)
                    .frame(width: 3)
                    .offset(x: min(max(width * shown - 1.5, 0), width - 3))
                if dragging {
                    RoundedRectangle(cornerRadius: CliampShape.tiny)
                        .fill(palette.keyFace)
                        .overlay(
                            RoundedRectangle(cornerRadius: CliampShape.tiny)
                                .stroke(palette.keyBorder, lineWidth: 1)
                        )
                        .frame(width: 10)
                        .offset(x: min(max(width * shown - 5, 0), width - 10))
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { drag in
                        dragging = true
                        dragFraction = min(max(drag.location.x / width, 0), 1)
                    }
                    .onEnded { _ in
                        dragging = false
                        onSeek(min(max(dragFraction, 0), 1))
                    }
            )
        }
        .frame(height: 24)
        .accessibilityElement()
        .accessibilityLabel("playback position")
        .accessibilityValue("\(Int(min(max(fraction, 0), 1) * 100))%")
    }
}

/// A key with real mechanical travel: dark gets a recessed bevel, light gets a
/// drop shelf, and pressing collapses the travel either way.
public struct MechKey<Content: View>: View {
    @Environment(\.cliampPalette) private var palette
    @Environment(\.cliampHapticsEnabled) private var hapticsEnabled
    private let action: () -> Void
    private let filled: Bool
    private let enabled: Bool
    private let height: CGFloat
    private let content: () -> Content

    public init(
        filled: Bool = false,
        enabled: Bool = true,
        height: CGFloat = 64,
        action: @escaping () -> Void,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.action = action
        self.filled = filled
        self.enabled = enabled
        self.height = height
        self.content = content
    }

    public var body: some View {
        Button {
            if hapticsEnabled { CliampHaptics.tick() }
            action()
        } label: {
            content().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(MechKeyStyle(palette: palette, filled: filled, height: height))
        .disabled(!enabled)
    }
}

private struct MechKeyStyle: ButtonStyle {
    let palette: CliampPalette
    let filled: Bool
    let height: CGFloat

    private var face: Color {
        switch (filled, palette.dark) {
        case (true, true): palette.accent
        case (true, false): palette.ink
        case (false, true): palette.keyFace
        case (false, false): palette.ground
        }
    }

    private var bevel: Color {
        switch (filled, palette.dark) {
        case (true, true): palette.accentBevel
        case (true, false): palette.hairlineRegion
        case (false, true): palette.keyBevel
        case (false, false): palette.hairline
        }
    }

    private var foreground: Color {
        switch (filled, palette.dark) {
        case (true, true): palette.onAccent
        case (true, false): palette.ground
        case (false, true): Color(argb: 0xFFDA_E0DA)
        case (false, false): palette.ink
        }
    }

    func makeBody(configuration: Configuration) -> some View {
        let travel = 3.0
        let pressed = configuration.isPressed
        ZStack(alignment: .top) {
            if !palette.dark {
                RoundedRectangle(cornerRadius: CliampShape.key)
                    .fill(bevel)
                    .frame(height: height)
                    .offset(y: travel)
            }
            RoundedRectangle(cornerRadius: CliampShape.key)
                .fill(face)
                .overlay(alignment: .bottom) {
                    if palette.dark {
                        Rectangle()
                            .fill(bevel)
                            .frame(height: pressed ? 2 : 5)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: CliampShape.key))
                .overlay {
                    if !filled {
                        RoundedRectangle(cornerRadius: CliampShape.key)
                            .stroke(palette.keyBorder, lineWidth: 1)
                    }
                }
                .overlay {
                    configuration.label.foregroundStyle(foreground)
                }
                .offset(y: pressed ? travel : 0)
        }
        .frame(height: palette.dark ? height : height + travel)
        .animation(.spring(response: 0.18, dampingFraction: 0.65), value: pressed)
    }
}

/// Radio has no timeline, so the seek rule becomes `━━ STREAMING ━━`.
public struct StreamingRule: View {
    @Environment(\.cliampPalette) private var palette
    private let label: String
    private let color: Color?
    private let dim: Bool

    public init(_ label: String, color: Color? = nil, dim: Bool = false) {
        self.label = label
        self.color = color
        self.dim = dim
    }

    public var body: some View {
        let rule = dim ? palette.track : (color ?? palette.accent)
        HStack(spacing: 10) {
            Rectangle().fill(rule).frame(maxWidth: .infinity).frame(height: 4)
            Text(label.uppercased())
                .cliampText(CliampType.chip)
                .foregroundStyle(dim ? palette.inkFaint : (color ?? palette.accent))
                .lineLimit(1)
            Rectangle().fill(rule).frame(maxWidth: .infinity).frame(height: 4)
        }
        .frame(height: 24)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
