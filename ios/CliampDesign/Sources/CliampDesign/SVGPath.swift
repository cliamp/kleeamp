import SwiftUI

/// Parses the straight-line and arc commands the cliamp icon set uses, so the
/// geometry strings port verbatim from the Android vectors. Arcs become cubic
/// curves; there is no stroke or fill decision in here.
public enum SVGPath {
    public static func parse(_ d: String) -> Path {
        let tokens = tokenize(d)
        var path = Path()
        var current = CGPoint.zero
        var start = CGPoint.zero
        var command: Character?
        var index = 0

        func nextNumber() -> CGFloat? {
            guard index < tokens.count, case .number(let value) = tokens[index] else { return nil }
            index += 1
            return value
        }

        func nextFlag() -> Bool? {
            nextNumber().map { $0 != 0 }
        }

        while index < tokens.count {
            if case .command(let letter) = tokens[index] {
                command = letter
                index += 1
            }
            guard let letter = command else {
                index += 1
                continue
            }
            let lower = Character(letter.lowercased())
            let relative = letter.isLowercase
            switch lower {
            case "m":
                guard let x = nextNumber(), let y = nextNumber() else { return path }
                let point = relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
                path.move(to: point)
                current = point
                start = point
                command = relative ? "l" : "L"
            case "l":
                guard let x = nextNumber(), let y = nextNumber() else { return path }
                let point = relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
                path.addLine(to: point)
                current = point
            case "h":
                guard let x = nextNumber() else { return path }
                let point = CGPoint(x: relative ? current.x + x : x, y: current.y)
                path.addLine(to: point)
                current = point
            case "v":
                guard let y = nextNumber() else { return path }
                let point = CGPoint(x: current.x, y: relative ? current.y + y : y)
                path.addLine(to: point)
                current = point
            case "a":
                guard let rx = nextNumber(), let ry = nextNumber(), let rotation = nextNumber(),
                      let large = nextFlag(), let sweep = nextFlag(),
                      let x = nextNumber(), let y = nextNumber()
                else { return path }
                let point = relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
                addArc(
                    &path, from: current, to: point,
                    rx: rx, ry: ry, rotationDegrees: rotation, largeArc: large, sweep: sweep
                )
                current = point
            case "z":
                path.closeSubpath()
                current = start
            default:
                return path
            }
        }
        return path
    }

    private enum Token {
        case command(Character)
        case number(CGFloat)
    }

    private static let commandLetters: Set<Character> = [
        "M", "m", "L", "l", "H", "h", "V", "v", "A", "a", "Z", "z",
    ]

    private static func tokenize(_ d: String) -> [Token] {
        var tokens: [Token] = []
        var buffer = ""

        func flush() {
            if !buffer.isEmpty, let value = Double(buffer) {
                tokens.append(.number(CGFloat(value)))
            }
            buffer = ""
        }

        for character in d {
            if commandLetters.contains(character) {
                flush()
                tokens.append(.command(character))
            } else if character == "-" || character == "+" {
                if !buffer.isEmpty, buffer.last != "e", buffer.last != "E" { flush() }
                buffer.append(character)
            } else if character == "." {
                // `4.6.5` is two numbers: the second decimal point starts one.
                if buffer.contains(".") { flush() }
                buffer.append(character)
            } else if character.isNumber || character == "e" || character == "E" {
                buffer.append(character)
            } else {
                flush()
            }
        }
        flush()
        return tokens
    }

    /// Endpoint-parameterised SVG arc (spec F.6.5), emitted as cubic segments
    /// of at most ninety degrees so no arc ever needs more than four curves.
    private static func addArc(
        _ path: inout Path,
        from start: CGPoint,
        to end: CGPoint,
        rx inputRx: CGFloat,
        ry inputRy: CGFloat,
        rotationDegrees: CGFloat,
        largeArc: Bool,
        sweep: Bool
    ) {
        var rx = abs(inputRx)
        var ry = abs(inputRy)
        guard rx != 0, ry != 0 else {
            path.addLine(to: end)
            return
        }
        guard start != end else { return }

        let phi = rotationDegrees * .pi / 180
        let cosPhi = cos(phi)
        let sinPhi = sin(phi)
        let dx = (start.x - end.x) / 2
        let dy = (start.y - end.y) / 2
        let x1p = cosPhi * dx + sinPhi * dy
        let y1p = -sinPhi * dx + cosPhi * dy

        let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if lambda > 1 {
            let scale = sqrt(lambda)
            rx *= scale
            ry *= scale
        }

        let sign: CGFloat = largeArc != sweep ? 1 : -1
        let numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        let denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        let coefficient = sign * sqrt(max(0, numerator / denominator))
        let cxp = coefficient * rx * y1p / ry
        let cyp = coefficient * -ry * x1p / rx
        let cx = cosPhi * cxp - sinPhi * cyp + (start.x + end.x) / 2
        let cy = sinPhi * cxp + cosPhi * cyp + (start.y + end.y) / 2

        func vectorAngle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let dot = ux * vx + uy * vy
            let length = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            let angle = acos(max(-1, min(1, dot / length)))
            return ux * vy - uy * vx < 0 ? -angle : angle
        }

        let ux = (x1p - cxp) / rx
        let uy = (y1p - cyp) / ry
        let vx = (-x1p - cxp) / rx
        let vy = (-y1p - cyp) / ry
        let theta1 = vectorAngle(1, 0, ux, uy)
        var deltaTheta = vectorAngle(ux, uy, vx, vy)
        if !sweep, deltaTheta > 0 { deltaTheta -= 2 * .pi }
        if sweep, deltaTheta < 0 { deltaTheta += 2 * .pi }

        let segments = max(1, Int(ceil(abs(deltaTheta) / (.pi / 2))))
        let delta = deltaTheta / CGFloat(segments)

        func point(at theta: CGFloat) -> CGPoint {
            let x = rx * cos(theta)
            let y = ry * sin(theta)
            return CGPoint(x: cx + cosPhi * x - sinPhi * y, y: cy + sinPhi * x + cosPhi * y)
        }

        func derivative(at theta: CGFloat) -> CGPoint {
            let x = -rx * sin(theta)
            let y = ry * cos(theta)
            return CGPoint(x: cosPhi * x - sinPhi * y, y: sinPhi * x + cosPhi * y)
        }

        var theta = theta1
        for _ in 0..<segments {
            let next = theta + delta
            let alpha = 4.0 / 3.0 * tan(delta / 4)
            let from = point(at: theta)
            let to = point(at: next)
            let d1 = derivative(at: theta)
            let d2 = derivative(at: next)
            path.addCurve(
                to: to,
                control1: CGPoint(x: from.x + alpha * d1.x, y: from.y + alpha * d1.y),
                control2: CGPoint(x: to.x - alpha * d2.x, y: to.y - alpha * d2.y)
            )
            theta = next
        }
    }
}
