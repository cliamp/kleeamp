import SwiftUI

public struct CliampVector: Sendable {
    public enum Shape: Sendable {
        case fill(Path)
        case stroke(Path, width: CGFloat)
    }

    public let width: CGFloat
    public let height: CGFloat
    public let shapes: [Shape]
}

private func solid(_ width: CGFloat, _ height: CGFloat, _ paths: String...) -> CliampVector {
    CliampVector(width: width, height: height, shapes: paths.map { .fill(SVGPath.parse($0)) })
}

private func stroked(
    _ width: CGFloat, _ height: CGFloat, _ strokeWidth: CGFloat, _ paths: String...
) -> CliampVector {
    CliampVector(
        width: width, height: height,
        shapes: paths.map { .stroke(SVGPath.parse($0), width: strokeWidth) }
    )
}

private func rect(_ x: CGFloat, _ y: CGFloat, _ width: CGFloat, _ height: CGFloat) -> String {
    "M\(x) \(y)h\(width)v\(height)h\(-width)z"
}

private func rrect(
    _ x: CGFloat, _ y: CGFloat, _ width: CGFloat, _ height: CGFloat, _ radius: CGFloat
) -> String {
    var path = "M\(x + radius) \(y)"
    path += "h\(width - 2 * radius)a\(radius) \(radius) 0 0 1 \(radius) \(radius)"
    path += "v\(height - 2 * radius)a\(radius) \(radius) 0 0 1 \(-radius) \(radius)"
    path += "h\(-(width - 2 * radius))a\(radius) \(radius) 0 0 1 \(-radius) \(-radius)"
    path += "v\(-(height - 2 * radius))a\(radius) \(radius) 0 0 1 \(radius) \(-radius)z"
    return path
}

private func circle(_ cx: CGFloat, _ cy: CGFloat, _ radius: CGFloat) -> String {
    "M\(cx - radius) \(cy)"
        + "a\(radius) \(radius) 0 1 1 \(2 * radius) 0"
        + "a\(radius) \(radius) 0 1 1 \(-2 * radius) 0z"
}

/// Hand-drawn geometry only, ported verbatim from `CliampIcons.kt`: straight
/// paths, rectangles and circles, on 18, 20, 24 and 48 unit viewports.
public enum CliampIcons {
    public static let mark = solid(
        48, 48,
        rect(5, 20, 5, 8), rect(12, 12, 5, 24), rect(19, 4, 5, 40),
        rect(26, 14, 5, 20), rect(33, 18, 5, 12), rect(40, 22, 3, 4)
    )

    public static let playTiny = solid(9, 10, "M0 0l9 5-9 5z")
    public static let playRow = solid(14, 14, "M1 1l12 6-12 6z")
    public static let playTab = solid(18, 18, "M2 1l14 8-14 8z")
    public static let musicNote = solid(
        16, 16,
        circle(4, 12, 2.1), circle(11.5, 12, 2.1),
        rect(4.4, 3, 1.5, 9), rect(11.4, 3, 1.5, 9), rect(4.4, 3, 8.5, 2.4)
    )

    public static let prev = solid(22, 18, "M12 9L22 1v16z", "M2 9L12 1v16z", rect(0, 1, 2.4, 16))
    public static let next = solid(22, 18, "M10 9L0 17V1z", "M20 9L10 17V1z", rect(19.6, 1, 2.4, 16))
    public static let pause = solid(20, 22, rrect(1, 0, 6.5, 22, 1), rrect(12.5, 0, 6.5, 22, 1))

    public static let shuffle = stroked(
        18, 14, 1.8,
        "M1 3h4l8 8h3", "M14.5 9.5l1.5 1.5-1.5 1.5",
        "M1 11h4l8-8h3", "M14.5 2.5l1.5 1.5-1.5 1.5"
    )
    public static let star = stroked(
        16, 16, 1.8,
        "M8 1.5l1.9 4.2 4.6.5-3.4 3.1.9 4.5L8 11.6 4 13.8l.9-4.5L1.5 6.2l4.6-.5z"
    )
    public static let starFilled = solid(
        16, 16, "M8 1.5l1.9 4.2 4.6.5-3.4 3.1.9 4.5L8 11.6 4 13.8l.9-4.5L1.5 6.2l4.6-.5z"
    )

    public static let stationsTab: CliampVector = {
        var shapes: [CliampVector.Shape] = [
            .stroke(SVGPath.parse("M1.5 16.6h15"), width: 1.3),
            .fill(SVGPath.parse(circle(9, 14.9, 1.3))),
        ]
        let domes = [
            "M5.5 14.2L5.97 12.45L7.25 11.17L9 10.7L10.75 11.17L12.03 12.45L12.5 14.2",
            "M3 14.2L3.8 11.2L6 9L9 8.2L12 9L14.2 11.2L15 14.2",
            "M1 14.2L2.07 10.2L5 7.27L9 6.2L13 7.27L15.93 10.2L17 14.2",
        ]
        shapes.append(contentsOf: domes.map { .stroke(SVGPath.parse($0), width: 1.4) })
        return CliampVector(width: 18, height: 18, shapes: shapes)
    }()

    public static let libTab = stroked(
        18, 18, 1.7, rect(1, 1, 5, 16), rect(8, 1, 5, 16), "M15 2l2 15"
    )
    public static let dragHandle = stroked(16, 16, 1.5, "M1 3h14", "M1 8h14", "M1 13h14")
    public static let queueTabLines = stroked(18, 18, 1.7, "M1 4h16", "M1 9h11", "M1 14h11")
    public static let search = stroked(16, 16, 1.5, circle(7.1, 7.1, 3.9), "M9.9 9.9L14 14.2")

    public static let podsTab = stroked(
        18, 18, 1.6,
        rrect(6.2, 1, 5.6, 9.4, 2.8),
        "M3.4 8.4a5.6 5.6 0 0 0 11.2 0",
        "M9 14.1v2.4",
        "M5.8 16.5h6.4"
    )
    public static let podRow = stroked(
        14, 14, 1.5,
        rrect(4.6, 0.8, 4.8, 7.6, 2.4),
        "M2.4 6.6a4.6 4.6 0 0 0 9.2 0",
        "M7 11.2v1.9"
    )
    public static let plus = solid(14, 14, rect(6, 0, 2, 14), rect(0, 6, 14, 2))
    public static let minus = solid(12, 12, rect(0, 5, 12, 2))
    public static let check = stroked(13, 13, 2.2, "M1.5 7l3.2 3.2L11.5 3")
    public static let xmark = stroked(12, 12, 1.8, "M2 2l8 8", "M10 2l-8 8")
    public static let caretDown = solid(10, 10, "M0 2h10L5 8z")
    public static let caretRight = solid(10, 10, "M2 0v10l6-5z")
    public static let down = stroked(16, 10, 1.8, "M1 1l7 8 7-8")
    public static let left = stroked(16, 16, 1.8, "M8 1L1 8l7 7")
    public static let download = stroked(
        16, 16, 1.6, "M8 1v9", "M4.5 6.5L8 10l3.5-3.5", "M1.5 13.5h13"
    )
    public static let more = solid(16, 16, circle(8, 3, 2.2), circle(8, 8, 2.2), circle(8, 13, 2.2))
    public static let lines = solid(16, 14, rect(0, 0, 16, 2), rect(0, 6, 16, 2), rect(0, 12, 16, 2))
    public static let listShort = stroked(16, 16, 1.7, "M1 3h14", "M1 8h9", "M1 13h9")
    public static let grid = stroked(
        16, 16, 1.6,
        rrect(1, 1, 6, 6, 1), rrect(9, 1, 6, 6, 1),
        rrect(1, 9, 6, 6, 1), rrect(9, 9, 6, 6, 1)
    )
    public static let settings = stroked(
        16, 16, 1.7,
        "M2 3.5h12", "M6 1.5v4",
        "M2 8h12", "M11 6v4",
        "M2 12.5h12", "M8 10.5v4"
    )
    public static let gear = stroked(
        16, 16, 1.8,
        circle(8, 8, 4.6),
        "M12.6 8L15 8", "M11.25 11.25L12.95 12.95", "M8 12.6L8 15", "M4.75 11.25L3.05 12.95",
        "M3.4 8L1 8", "M4.75 4.75L3.05 3.05", "M8 3.4L8 1", "M11.25 4.75L12.95 3.05"
    )
    public static let meterSmall = solid(
        14, 14,
        rect(0, 9, 2.4, 5), rect(3.9, 5, 2.4, 9),
        rect(7.8, 1, 2.4, 13), rect(11.6, 6, 2.4, 8)
    )
    public static let speaker = stroked(20, 20, 1.7, "M2 7l5-4v14l-5-4z", "M11 6.5a4 4 0 010 7")
    public static let speakerSolid = solid(20, 20, "M2 7l5-4v14l-5-4z")
    public static let clock = solid(
        20, 20,
        "M4.2 2.8L15.8 2.8L10 9.3z",
        "M10 10.7L15.8 17.2L4.2 17.2z"
    )
    public static let server: CliampVector = {
        let strokes = [
            rrect(1.2, 2.4, 15.6, 5.2, 1.2),
            rrect(1.2, 10.0, 15.6, 5.2, 1.2),
            "M11.8 5.0h3.2",
            "M11.8 12.6h3.2",
        ].map { CliampVector.Shape.stroke(SVGPath.parse($0), width: 1.7) }
        let fills = [
            rect(3.6, 4.2, 1.7, 1.7), rect(3.6, 11.8, 1.7, 1.7),
        ].map { CliampVector.Shape.fill(SVGPath.parse($0)) }
        return CliampVector(width: 18, height: 18, shapes: strokes + fills)
    }()
    public static let signalBars = solid(
        17, 12,
        rect(0, 8, 3, 4), rect(4.6, 5.5, 3, 6.5),
        rect(9.2, 3, 3, 9), rect(13.8, 0, 3, 12)
    )
    public static let battery: CliampVector = CliampVector(
        width: 24, height: 12,
        shapes: [
            .stroke(SVGPath.parse(rrect(0.5, 0.5, 20, 11, 3)), width: 1),
            .fill(SVGPath.parse(rrect(2.5, 2.5, 15, 7, 1.5))),
            .fill(SVGPath.parse(rrect(22, 4, 2, 4, 1))),
        ]
    )
}

/// Draws a vector at any size, preserving its aspect ratio and centring it in
/// the frame, the way a Compose `Icon` scales into its box.
public struct CliampIcon: View {
    private let vector: CliampVector
    private let tint: Color
    private let width: CGFloat?
    private let height: CGFloat?

    public init(_ vector: CliampVector, size: CGFloat, tint: Color) {
        self.vector = vector
        self.tint = tint
        self.width = size
        self.height = size
    }

    public init(_ vector: CliampVector, width: CGFloat, height: CGFloat, tint: Color) {
        self.vector = vector
        self.tint = tint
        self.width = width
        self.height = height
    }

    public init(_ vector: CliampVector, tint: Color) {
        self.vector = vector
        self.tint = tint
        self.width = nil
        self.height = nil
    }

    public var body: some View {
        Canvas { context, size in
            let scale = min(size.width / vector.width, size.height / vector.height)
            let scaledWidth = vector.width * scale
            let scaledHeight = vector.height * scale
            context.translateBy(x: (size.width - scaledWidth) / 2, y: (size.height - scaledHeight) / 2)
            context.scaleBy(x: scale, y: scale)
            for shape in vector.shapes {
                switch shape {
                case .fill(let path):
                    context.fill(path, with: .color(tint))
                case .stroke(let path, let lineWidth):
                    context.stroke(
                        path,
                        with: .color(tint),
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt, lineJoin: .miter)
                    )
                }
            }
        }
        .frame(width: width, height: height)
        .accessibilityHidden(true)
    }
}
