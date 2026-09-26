import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// One entry in the type scale. Sizes, tracking and line-height ratios are
/// lifted from `Type.kt`; tracking is stored in em and applied in points.
public struct CliampTextStyle: Sendable {
    public let fontName: String
    public let size: CGFloat
    public let trackingEm: CGFloat
    public let lineHeightRatio: CGFloat

    public var font: Font { .custom(fontName, size: size) }

    /// SwiftUI's `lineSpacing` adds on top of the font's own line height, so
    /// the target line box has the font's measured height subtracted first.
    public var lineSpacing: CGFloat {
        let target = size * lineHeightRatio
        #if canImport(UIKit)
        let base = UIFont(name: fontName, size: size)?.lineHeight ?? size * 1.2
        #else
        let base = size * 1.2
        #endif
        return max(0, target - base)
    }
}

private struct CliampTextModifier: ViewModifier {
    let style: CliampTextStyle

    func body(content: Content) -> some View {
        content
            .font(style.font)
            .tracking(style.trackingEm * style.size)
            .lineSpacing(style.lineSpacing)
    }
}

public extension View {
    func cliampText(_ style: CliampTextStyle) -> some View {
        modifier(CliampTextModifier(style: style))
    }
}

/// Two families, each with a job: Poppins for everything editorial, JetBrains
/// Mono only for readouts that tick.
public enum CliampType {
    public static let screenTitle = CliampTextStyle(
        fontName: "Poppins-Bold", size: 24, trackingEm: -0.02, lineHeightRatio: 1.15
    )
    public static let trackTitle = CliampTextStyle(
        fontName: "Poppins-Bold", size: 28, trackingEm: -0.015, lineHeightRatio: 1.14
    )
    public static let trackTitleCompact = CliampTextStyle(
        fontName: "Poppins-Bold", size: 19, trackingEm: -0.012, lineHeightRatio: 1.2
    )
    public static let trackTitleSmall = CliampTextStyle(
        fontName: "Poppins-Bold", size: 16, trackingEm: -0.01, lineHeightRatio: 1.2
    )
    public static let rowPrimary = CliampTextStyle(
        fontName: "Poppins-Regular", size: 15, trackingEm: 0, lineHeightRatio: 1.3
    )
    public static let rowPrimaryMedium = CliampTextStyle(
        fontName: "Poppins-Medium", size: 15, trackingEm: 0, lineHeightRatio: 1.3
    )
    public static let rowSecondary = CliampTextStyle(
        fontName: "Poppins-Regular", size: 12, trackingEm: 0, lineHeightRatio: 1.35
    )
    public static let meta = CliampTextStyle(
        fontName: "Poppins-Regular", size: 11, trackingEm: 0, lineHeightRatio: 1.35
    )
    public static let body = CliampTextStyle(
        fontName: "Poppins-Regular", size: 13, trackingEm: 0, lineHeightRatio: 1.6
    )
    public static let sectionLabel = CliampTextStyle(
        fontName: "Poppins-Medium", size: 11, trackingEm: 0.14, lineHeightRatio: 1.3
    )
    public static let chip = CliampTextStyle(
        fontName: "Poppins-Medium", size: 11, trackingEm: 0.08, lineHeightRatio: 1.2
    )
    public static let tabLabel = CliampTextStyle(
        fontName: "Poppins-Medium", size: 10, trackingEm: 0.10, lineHeightRatio: 1.2
    )
    public static let nowPlayingLabel = CliampTextStyle(
        fontName: "Poppins-Medium", size: 11, trackingEm: 0.16, lineHeightRatio: 1.2
    )
    public static let time = CliampTextStyle(
        fontName: "JetBrainsMono-Medium", size: 13, trackingEm: 0, lineHeightRatio: 1.1
    )
    public static let timeSmall = CliampTextStyle(
        fontName: "JetBrainsMono-Regular", size: 11, trackingEm: 0, lineHeightRatio: 1.1
    )
    public static let datum = CliampTextStyle(
        fontName: "JetBrainsMono-Regular", size: 11, trackingEm: 0, lineHeightRatio: 1.35
    )
}
