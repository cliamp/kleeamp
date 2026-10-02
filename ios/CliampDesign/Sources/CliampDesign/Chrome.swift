import SwiftUI

public struct HairlineDivider: View {
    @Environment(\.cliampPalette) private var palette
    private let region: Bool

    public init(region: Bool = false) {
        self.region = region
    }

    public var body: some View {
        Rectangle()
            .fill(region ? palette.hairlineRegion : palette.hairline)
            .frame(height: 1)
    }
}

/// A section header: a short accent tick, the label in small caps, and an
/// optional trailing control.
public struct SectionLabel<Trailing: View>: View {
    @Environment(\.cliampPalette) private var palette
    private let text: String
    private let gutter: CGFloat
    private let trailing: () -> Trailing

    public init(
        _ text: String,
        gutter: CGFloat = cliampGutter,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.text = text
        self.gutter = gutter
        self.trailing = trailing
    }

    public var body: some View {
        HStack(spacing: 0) {
            HStack(spacing: 7) {
                Rectangle()
                    .fill(palette.accent)
                    .frame(width: 3, height: 11)
                Text(text.uppercased())
                    .cliampText(CliampType.sectionLabel)
                    .foregroundStyle(palette.inkTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, gutter)
        .padding(.vertical, 10)
    }
}

public extension SectionLabel where Trailing == EmptyView {
    init(_ text: String, gutter: CGFloat = cliampGutter) {
        self.init(text, gutter: gutter) { EmptyView() }
    }
}

/// 34-point square layout toggle; the glyph shows the mode you switch into.
public struct GridListToggle: View {
    @Environment(\.cliampPalette) private var palette
    private let gridMode: Bool
    private let action: () -> Void

    public init(gridMode: Bool, action: @escaping () -> Void) {
        self.gridMode = gridMode
        self.action = action
    }

    public var body: some View {
        CliampIcon(gridMode ? CliampIcons.listShort : CliampIcons.grid, size: 16, tint: palette.accent)
            .frame(width: 34, height: 34)
            .background(palette.dark ? palette.keyFace : palette.ground)
            .clipShape(RoundedRectangle(cornerRadius: CliampShape.small))
            .overlay(
                RoundedRectangle(cornerRadius: CliampShape.small)
                    .stroke(palette.keyBorder, lineWidth: 1)
            )
            .microPress(action: action)
    }
}

public struct BackChevron: View {
    @Environment(\.cliampPalette) private var palette
    private let action: () -> Void

    public init(action: @escaping () -> Void) {
        self.action = action
    }

    public var body: some View {
        CliampIcon(CliampIcons.left, size: 16, tint: palette.ink)
            .frame(width: 28, height: 28)
            .microPress(action: action)
    }
}

/// The shared page header: title row with its corner actions, an optional chip
/// row, and the region divider. Every main page wears the same one.
public struct CliampHeader<Chips: View>: View {
    @Environment(\.cliampPalette) private var palette
    private let title: String
    private let onBack: (() -> Void)?
    private let onSearch: (() -> Void)?
    private let onSettings: (() -> Void)?
    private let onTitleTap: (() -> Void)?
    private let showsChips: Bool
    private let chips: () -> Chips

    public init(
        _ title: String,
        onBack: (() -> Void)? = nil,
        onSearch: (() -> Void)? = nil,
        onSettings: (() -> Void)? = nil,
        onTitleTap: (() -> Void)? = nil,
        showsChips: Bool = true,
        @ViewBuilder chips: @escaping () -> Chips
    ) {
        self.title = title
        self.onBack = onBack
        self.onSearch = onSearch
        self.onSettings = onSettings
        self.onTitleTap = onTitleTap
        self.showsChips = showsChips
        self.chips = chips
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                if let onBack {
                    BackChevron(action: onBack)
                    Spacer().frame(width: 4)
                }
                Text(title)
                    .cliampText(CliampType.screenTitle)
                    .foregroundStyle(palette.ink)
                    .lineLimit(1)
                    .clampTap(action: onTitleTap)
                Spacer(minLength: 12)
                HStack(spacing: 20) {
                    if let onSearch {
                        CliampIcon(CliampIcons.search, size: 22, tint: palette.accent)
                            .microPress(action: onSearch)
                    }
                    if let onSettings {
                        CliampIcon(CliampIcons.gear, size: 22, tint: palette.accent)
                            .microPress(action: onSettings)
                    }
                }
            }
            .padding(.horizontal, cliampGutter)
            .padding(.top, 8)
            .padding(.bottom, 6)

            if showsChips {
                ChipsRow(content: chips)
            }

            HairlineDivider(region: true)
        }
        .background(palette.ground)
    }
}

public extension CliampHeader where Chips == EmptyView {
    init(
        _ title: String,
        onBack: (() -> Void)? = nil,
        onSearch: (() -> Void)? = nil,
        onSettings: (() -> Void)? = nil,
        onTitleTap: (() -> Void)? = nil
    ) {
        self.init(
            title, onBack: onBack, onSearch: onSearch,
            onSettings: onSettings, onTitleTap: onTitleTap,
            showsChips: false
        ) { EmptyView() }
    }
}

private struct ChipsRow<Content: View>: View {
    private let content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 7) {
                content()
            }
            .padding(.leading, cliampGutter)
            .padding(.trailing, cliampGutter)
            .padding(.top, 6)
            .padding(.bottom, 12)
        }
        .scrollIndicators(.hidden)
    }
}

/// A hairline-separated list row. Cards are for objects with state, not lists.
public struct ListRow<Leading: View, Trailing: View, Content: View>: View {
    @Environment(\.cliampPalette) private var palette
    private let onClick: (() -> Void)?
    private let leading: () -> Leading
    private let trailing: () -> Trailing
    private let divider: Bool
    private let verticalPadding: CGFloat
    private let gutter: CGFloat
    private let rail: Bool
    private let railOffset: CGFloat
    private let content: () -> Content

    public init(
        onClick: (() -> Void)? = nil,
        @ViewBuilder leading: @escaping () -> Leading,
        @ViewBuilder trailing: @escaping () -> Trailing,
        divider: Bool = true,
        verticalPadding: CGFloat = 12,
        gutter: CGFloat = cliampGutter,
        rail: Bool = false,
        railOffset: CGFloat = 0,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.onClick = onClick
        self.leading = leading
        self.trailing = trailing
        self.divider = divider
        self.verticalPadding = verticalPadding
        self.gutter = gutter
        self.rail = rail
        self.railOffset = railOffset
        self.content = content
    }

    public var body: some View {
        VStack(spacing: 0) {
            rowBody
                .padding(.horizontal, gutter)
                .padding(.vertical, verticalPadding)
                .clampTap(action: onClick)
            if divider {
                HairlineDivider()
                    .padding(.leading, gutter)
            }
        }
        .overlay(alignment: .leading) {
            if rail {
                Rectangle()
                    .fill(palette.accent)
                    .frame(width: 2)
                    .offset(x: -railOffset)
            }
        }
    }

    private var rowBody: some View {
        HStack(spacing: 0) {
            leading()
            if !(Leading.self is EmptyView.Type) {
                Spacer().frame(width: 12)
            }
            VStack(alignment: .leading, spacing: 3, content: content)
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
    }
}

public extension ListRow where Trailing == EmptyView {
    init(
        onClick: (() -> Void)? = nil,
        @ViewBuilder leading: @escaping () -> Leading,
        divider: Bool = true,
        verticalPadding: CGFloat = 12,
        gutter: CGFloat = cliampGutter,
        rail: Bool = false,
        railOffset: CGFloat = 0,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(
            onClick: onClick, leading: leading, trailing: { EmptyView() },
            divider: divider, verticalPadding: verticalPadding,
            gutter: gutter, rail: rail, railOffset: railOffset, content: content
        )
    }
}

public extension ListRow where Leading == EmptyView {
    init(
        onClick: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing,
        divider: Bool = true,
        verticalPadding: CGFloat = 12,
        gutter: CGFloat = cliampGutter,
        rail: Bool = false,
        railOffset: CGFloat = 0,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(
            onClick: onClick, leading: { EmptyView() }, trailing: trailing,
            divider: divider, verticalPadding: verticalPadding,
            gutter: gutter, rail: rail, railOffset: railOffset, content: content
        )
    }
}

public extension ListRow where Leading == EmptyView, Trailing == EmptyView {
    init(
        onClick: (() -> Void)? = nil,
        divider: Bool = true,
        verticalPadding: CGFloat = 12,
        gutter: CGFloat = cliampGutter,
        rail: Bool = false,
        railOffset: CGFloat = 0,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(
            onClick: onClick, leading: { EmptyView() }, trailing: { EmptyView() },
            divider: divider, verticalPadding: verticalPadding,
            gutter: gutter, rail: rail, railOffset: railOffset, content: content
        )
    }
}

/// A full-width muted note plus divider: empty states and transient notices.
public struct EmptyNote: View {
    @Environment(\.cliampPalette) private var palette
    private let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        VStack(spacing: 0) {
            Text(text)
                .cliampText(CliampType.rowSecondary)
                .foregroundStyle(palette.inkFaint)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, cliampGutter)
                .padding(.vertical, 18)
            HairlineDivider()
        }
    }
}

/// A fetch that auto-retried and gave up: a note with a manual try again.
public struct RetryNote: View {
    @Environment(\.cliampPalette) private var palette
    private let message: String?
    private let prominent: Bool
    private let onRetry: () -> Void

    public init(_ message: String?, prominent: Bool = false, onRetry: @escaping () -> Void) {
        self.message = message
        self.prominent = prominent
        self.onRetry = onRetry
    }

    public var body: some View {
        VStack(spacing: 14) {
            if let message, !message.isEmpty {
                Text(message)
                    .cliampText(CliampType.rowSecondary)
                    .foregroundStyle(palette.destructiveInk)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
            Chip("try again", selected: false, accent: palette.ink, action: onRetry)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, cliampGutter)
        .padding(.vertical, prominent ? 96 : 18)
    }
}

/// The plate that stands in for missing cover art: flat, hairline bordered.
public struct ArtPlate: View {
    @Environment(\.cliampPalette) private var palette
    private let radius: CGFloat

    public init(radius: CGFloat = CliampShape.medium) {
        self.radius = radius
    }

    public var body: some View {
        RoundedRectangle(cornerRadius: radius)
            .fill(palette.artA)
            .overlay(
                RoundedRectangle(cornerRadius: radius)
                    .stroke(palette.artBorder, lineWidth: 1)
            )
    }
}

/// A static themed glyph plate for rows that identify by icon.
public struct GlyphPlate: View {
    @Environment(\.cliampPalette) private var palette
    private let icon: CliampVector
    private let size: CGFloat
    private let iconSize: CGFloat

    public init(_ icon: CliampVector, size: CGFloat, iconSize: CGFloat = 18) {
        self.icon = icon
        self.size = size
        self.iconSize = iconSize
    }

    public var body: some View {
        RoundedRectangle(cornerRadius: CliampShape.small)
            .fill(palette.panel)
            .overlay(
                RoundedRectangle(cornerRadius: CliampShape.small)
                    .stroke(palette.chipBorder, lineWidth: 1)
            )
            .overlay(CliampIcon(icon, size: iconSize, tint: palette.accent))
            .frame(width: size, height: size)
    }
}
