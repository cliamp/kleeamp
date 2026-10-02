import SwiftUI

/// A single line that scrolls itself when the text is wider than its frame,
/// pausing at the start first. The Compose `MarqueeLabel` twin: long station
/// names and stream titles stay readable instead of truncating to an ellipsis.
public struct MarqueeText: View {
    private let text: String
    private let style: CliampTextStyle
    /// Points per second while scrolling.
    private let speed: CGFloat = 28

    @State private var textWidth: CGFloat = 0
    @State private var offset: CGFloat = 0

    public init(_ text: String, style: CliampTextStyle) {
        self.text = text
        self.style = style
    }

    public var body: some View {
        GeometryReader { proxy in
            let overflow = max(0, textWidth - proxy.size.width)
            Text(text)
                .cliampText(style)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { textWidth = $0 }
                .offset(x: offset)
                .frame(width: proxy.size.width, alignment: .leading)
                .clipped()
                .task(id: "\(text)|\(Int(overflow))") {
                    withAnimation(.linear(duration: 0)) { offset = 0 }
                    guard overflow > 1 else { return }
                    try? await Task.sleep(for: .milliseconds(1200))
                    guard !Task.isCancelled else { return }
                    withAnimation(
                        .linear(duration: Double(overflow / speed)).repeatForever(autoreverses: true)
                    ) {
                        offset = -overflow
                    }
                }
        }
        .frame(height: style.size * style.lineHeightRatio)
    }
}
