import Foundation

/// Parses ICY metadata out of a shoutcast-style stream: audio bytes separated
/// by `icy-metaint`, then a length byte and the metadata block. AVPlayer does
/// not expose any of this, so the app reads it from its own connection.
public struct IcyMetadataParser {
    private let metaint: Int
    private var remainingAudio: Int
    private var remainingMetadata = 0
    private var metadata: [UInt8] = []
    private var buffer: [UInt8] = []

    public init(metaint: Int) {
        self.metaint = max(1, metaint)
        remainingAudio = max(1, metaint)
    }

    /// Feeds a chunk; returns any stream titles completed by it.
    public mutating func consume(_ data: Data) -> [String] {
        buffer.append(contentsOf: data)
        var titles: [String] = []
        var index = 0
        while index < buffer.count {
            if remainingAudio > 0 {
                let take = min(remainingAudio, buffer.count - index)
                remainingAudio -= take
                index += take
                continue
            }
            if remainingMetadata == 0 {
                let lengthByte = Int(buffer[index])
                index += 1
                remainingMetadata = lengthByte * 16
                if remainingMetadata == 0 {
                    remainingAudio = metaint
                    continue
                }
                metadata.removeAll(keepingCapacity: true)
                continue
            }
            let take = min(remainingMetadata, buffer.count - index)
            metadata.append(contentsOf: buffer[index..<(index + take)])
            remainingMetadata -= take
            index += take
            if remainingMetadata == 0 {
                if let title = Self.streamTitle(from: metadata) {
                    titles.append(title)
                }
                remainingAudio = metaint
            }
        }
        buffer.removeAll(keepingCapacity: true)
        return titles
    }

    static func streamTitle(from bytes: [UInt8]) -> String? {
        let text = String(decoding: bytes, as: UTF8.self)
        guard let start = text.range(of: "StreamTitle='") else { return nil }
        let rest = text[start.upperBound...]
        guard let end = rest.range(of: "';") else { return nil }
        let title = rest[..<end.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? nil : title
    }
}
