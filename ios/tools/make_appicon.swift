import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// Renders the iOS app icon from the same geometry as Android's adaptive icon:
// oxide bevel ground, six-bar cliamp mark at 1.4x on the 48 grid, centred in
// the 108-unit tile.
let size = 1024
let unit = CGFloat(size) / 108.0

func makeColor(_ hex: UInt32) -> CGColor {
    CGColor(
        srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
        green: CGFloat((hex >> 8) & 0xFF) / 255,
        blue: CGFloat(hex & 0xFF) / 255,
        alpha: 1
    )
}

let bars: [(x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat)] = [
    (5, 20, 5, 8),
    (12, 12, 5, 24),
    (19, 4, 5, 40),
    (26, 14, 5, 20),
    (33, 18, 5, 12),
    (40, 22, 3, 4),
]

guard let context = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
) else {
    FileHandle.standardError.write(Data("no context\n".utf8))
    exit(1)
}

context.setFillColor(makeColor(0x7F2117))
context.fill(CGRect(x: 0, y: 0, width: size, height: size))
context.scaleBy(x: unit, y: unit)
// CoreGraphics is bottom-left; the vector's y runs down from the top.
context.setFillColor(makeColor(0xF8E4D4))
for bar in bars {
    let x = 20.4 + bar.x * 1.4
    let y = 108 - (20.4 + (bar.y + bar.h) * 1.4)
    context.fill(CGRect(x: x, y: y, width: bar.w * 1.4, height: bar.h * 1.4))
}

guard let image = context.makeImage() else {
    FileHandle.standardError.write(Data("no image\n".utf8))
    exit(1)
}

let output = URL(fileURLWithPath: CommandLine.arguments[1])
guard let destination = CGImageDestinationCreateWithURL(
    output as CFURL, UTType.png.identifier as CFString, 1, nil
) else {
    FileHandle.standardError.write(Data("no destination\n".utf8))
    exit(1)
}
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else {
    FileHandle.standardError.write(Data("write failed\n".utf8))
    exit(1)
}
print("wrote \(output.lastPathComponent)")
