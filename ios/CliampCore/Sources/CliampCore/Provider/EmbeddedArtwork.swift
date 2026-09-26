import Foundation

/// Random-access bytes, so one tag parser works for a local file and a remote
/// SFTP track alike.
public protocol ByteRangeReader: Sendable {
    func readRange(offset: Int64, length: Int) async throws -> Data
    func fileSize() async throws -> Int64
}

/// Embedded cover art out of the file itself: ID3v2 `APIC` (MP3), the MP4
/// `covr` atom (M4A/M4B/MP4) and FLAC's `PICTURE` metadata block. This is the
/// Android `LocalArt` behaviour, except the same parser also serves provider
/// tracks by reading ranges over SFTP.
public enum EmbeddedArtwork {
    /// Covers larger than this are ignored rather than shipped to a row.
    public static let maxImageBytes = 2 * 1024 * 1024
    /// Nothing beyond this is read while hunting for a cover: a lying header
    /// costs one capped read, not an unbounded one.
    public static let maxScanBytes = 4 * 1024 * 1024
    /// A tag can declare hundreds of thousands of tiny frames; the walk stops
    /// after this many range requests so a hostile or damaged file cannot turn
    /// extraction into an unbounded stream of reads (each one a round trip
    /// over SFTP).
    public static let maxReads = 128

    /// Per-extraction accounting: every read is counted, and the walk gives up
    /// once the budget is spent.
    final class Budget: @unchecked Sendable {
        private var reads = 0
        private var bytes = 0

        func canRead(_ length: Int) -> Bool {
            reads < maxReads && length >= 0 && length <= maxScanBytes - bytes
        }

        func spend(_ length: Int) {
            reads += 1
            bytes += length
        }
    }

    /// Reads are normalized to zero-based Data: a range request may return a
    /// slice whose indices start at the offset, and the parser indexes from 0.
    /// Cancellation is checked before every request.
    private static func bytes(
        _ reader: ByteRangeReader, offset: Int64, length: Int, budget: Budget
    ) async throws -> Data? {
        if Task.isCancelled { throw CancellationError() }
        guard offset >= 0, length > 0, budget.canRead(length) else { return nil }
        budget.spend(length)
        let raw: Data
        do {
            raw = try await reader.readRange(offset: offset, length: length)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return nil
        }
        return raw.startIndex == 0 ? raw : Data(raw)
    }

    /// Embedded cover bytes, or nil when the file has none. Only cancellation
    /// is thrown; every other failure (malformed tag, short read, budget) is
    /// reported as "no artwork".
    public static func extract(
        from reader: ByteRangeReader, fileExtension: String
    ) async throws -> Data? {
        let budget = Budget()
        switch fileExtension.lowercased() {
        case "mp3":
            return try await fromID3(reader, budget: budget)
        case "m4a", "m4b", "mp4", "aac":
            return try await fromMP4(reader, budget: budget)
        case "flac":
            return try await fromFLAC(reader, budget: budget)
        default:
            return nil
        }
    }

    // MARK: ID3v2 (mp3)

    private static func fromID3(_ reader: ByteRangeReader, budget: Budget) async throws -> Data? {
        guard let header = try await bytes(reader, offset: 0, length: 10, budget: budget),
              header.count == 10, header.starts(with: Array("ID3".utf8))
        else { return nil }
        let major = Int(header[3])
        guard major == 3 || major == 4, let tagSize = syncSafe(header, 6) else { return nil }
        let flags = header[5]
        let tagEnd = Int64(10 + min(tagSize, maxScanBytes))
        guard tagEnd > 10 else { return nil }

        // The tag body (after the ten-byte header) is read in bounded chunks.
        // Only v2.3 unsynchronises the tag as a whole with decoded frame
        // sizes; v2.4 marks unsynchronisation per frame and keeps encoded
        // boundaries, so it is decoded later, once per payload.
        var body = Data()
        var cursor: Int64 = 10
        let chunk = 256 * 1024
        while cursor < tagEnd {
            let want = Int(min(Int64(chunk), tagEnd - cursor))
            guard let part = try await bytes(reader, offset: cursor, length: want, budget: budget),
                  !part.isEmpty
            else { break }
            body.append(part)
            cursor += Int64(part.count)
        }
        if major == 3, flags & 0x80 != 0 {
            body = deunsynchronised(body)
        }

        // Extended header, parsed in the decoded space: v2.3's size excludes
        // its own four bytes, v2.4's is syncsafe and includes the whole
        // extended header.
        var start = 0
        if flags & 0x40 != 0 {
            if major == 4 {
                guard let size = syncSafe(body, 0), size >= 6, size <= body.count else { return nil }
                start = size
            } else {
                guard let size = plainU32(body, 0).map(Int.init),
                      size >= 0, size + 4 <= body.count
                else { return nil }
                start = 4 + size
            }
        }
        return id3Pictures(in: body, from: start, major: major)
    }

    /// Frame walk over the decoded tag body. An unsupported or oversized
    /// picture does not stop the search: a later APIC can still be the cover.
    private static func id3Pictures(in body: Data, from start: Int, major: Int) -> Data? {
        var cursor = start
        var candidates = 0
        while cursor + 10 <= body.count {
            let identifier = String(decoding: body[cursor..<(cursor + 4)], as: UTF8.self)
            guard identifier.allSatisfy({ $0.isLetter || $0.isNumber }) else { return nil }
            let raw = body[(cursor + 4)..<(cursor + 8)]
            let size: Int?
            if major == 4 {
                size = syncSafe(Data(raw), 0)
            } else {
                size = raw.reduce(0) { ($0 << 8) | Int($1) }
            }
            guard let frameSize = size, frameSize > 0 else { return nil }
            var payloadStart = cursor + 10
            var payloadLength = frameSize
            let frameFlags = (Int(body[cursor + 8]) << 8) | Int(body[cursor + 9])

            if major == 4 {
                if frameFlags & 0x01 != 0 { // data length indicator
                    payloadStart += 4
                    payloadLength = max(0, payloadLength - 4)
                }
            } else {
                // Compression and encryption are unsupported: skip the frame.
                if frameFlags & 0xC0 != 0 {
                    cursor = payloadStart + frameSize
                    continue
                }
                if frameFlags & 0x20 != 0 { // grouping identity
                    payloadStart += 1
                    payloadLength = max(0, payloadLength - 1)
                }
            }
            guard payloadStart >= 0,
                  payloadLength >= 0,
                  payloadStart + payloadLength <= body.count
            else { return nil }

            if identifier == "APIC", payloadLength <= maxImageBytes + 1024 {
                candidates += 1
                var payload = body[payloadStart..<(payloadStart + payloadLength)]
                if major == 4, frameFlags & 0x02 != 0 {
                    payload = deunsynchronised(Data(payload))[...]
                }
                if let image = imageFromAPIC(Data(payload)) { return image }
                if candidates >= 8 { return nil }
            }
            cursor = payloadStart + payloadLength
        }
        return nil
    }

    /// Tag-level unsynchronisation inserts a zero byte after every `0xFF`; the
    /// reverse drops a `00` that follows an `FF`, leaving the original bytes.
    static func deunsynchronised(_ data: Data) -> Data {
        var out = Data()
        out.reserveCapacity(data.count)
        var iterator = data.makeIterator()
        var previous: UInt8?
        while let byte = iterator.next() {
            if let last = previous, last == 0xFF, byte == 0x00 {
                // Swallowed: the next byte is the real content. Clearing the
                // mark keeps a legitimate 00 00 pair intact.
                previous = nil
                continue
            }
            out.append(byte)
            previous = byte
        }
        return out
    }

    /// `APIC`: encoding byte, null-terminated mime, picture type, a
    /// null-terminated description in that encoding, then the image bytes.
    private static func imageFromAPIC(_ payload: Data) -> Data? {
        guard payload.count > 4 else { return nil }
        var index = 1 // encoding byte
        guard let mimeEnd = payload[index...].firstIndex(of: 0) else { return nil }
        let mime = String(decoding: payload[index..<mimeEnd], as: UTF8.self).lowercased()
        index = mimeEnd + 1
        guard index < payload.count else { return nil }
        index += 1 // picture type
        if payload[0] == 1 || payload[0] == 2 {
            // UTF-16 description: a sequence of two-byte units whose
            // terminator is one zero unit. Scanning byte-by-byte would stop
            // inside a character's trailing NUL (`72 00 00 00`) and leave a
            // stray byte in front of the image.
            var terminated = false
            while index + 1 < payload.count {
                if payload[index] == 0 && payload[index + 1] == 0 {
                    index += 2
                    terminated = true
                    break
                }
                index += 2
            }
            guard terminated else { return nil }
        } else {
            while index < payload.count, payload[index] != 0 { index += 1 }
            index += 1
        }
        guard index < payload.count else { return nil }
        let image = payload[index...]
        guard image.count >= 64, image.count <= maxImageBytes else { return nil }
        guard mime.isEmpty || mime.hasPrefix("image/") else { return nil }
        // Some encoders leave a stray NUL (or padding) before the image; the
        // real start is the first JPEG/PNG/GIF/WebP signature.
        return Data(normalizedImage(image))
    }

    /// Trims anything ahead of the image's own signature, within reason.
    static func normalizedImage(_ image: Data) -> Data {
        let magics: [[UInt8]] = [
            [0xFF, 0xD8, 0xFF], // jpeg
            [0x89, 0x50, 0x4E, 0x47], // png
            [0x47, 0x49, 0x46, 0x38], // gif
            [0x52, 0x49, 0x46, 0x46], // webp (riff)
        ]
        if magics.contains(where: { Array(image.prefix($0.count)) == $0 }) {
            return image
        }
        let window = image.prefix(64)
        for magic in magics {
            if let range = window.range(of: Data(magic)) {
                return Data(image.dropFirst(window.distance(from: window.startIndex, to: range.lowerBound)))
            }
        }
        return image
    }

    // MARK: MP4 atoms (m4a/m4b/mp4)

    private static func fromMP4(_ reader: ByteRangeReader, budget: Budget) async throws -> Data? {
        guard let size = try? await reader.fileSize(), size > 16 else { return nil }
        // moov > udta > meta > ilst > covr > data, each a direct child of the
        // previous. Headers only: a 500 MB mdat is stepped over, never read.
        guard let moov = try await child(
                  reader, of: AtomRange(start: 0, end: size), type: "moov", budget: budget
              ),
              let udta = try await child(reader, of: moov, type: "udta", budget: budget),
              let meta = try await child(reader, of: udta, type: "meta", budget: budget),
              // `meta` is a full box: four version/flags bytes precede its children.
              let ilst = try await child(
                  reader,
                  of: AtomRange(start: meta.start + 4, end: meta.end),
                  type: "ilst", budget: budget
              ),
              let covr = try await child(reader, of: ilst, type: "covr", budget: budget),
              let data = try await child(reader, of: covr, type: "data", budget: budget)
        else { return nil }
        // `data` payload: four type-flag bytes, four locale bytes, the image.
        let payloadStart = data.start + 8
        guard payloadStart <= data.end else { return nil }
        let length = data.end - payloadStart
        guard length >= 64, length <= maxImageBytes else { return nil }
        guard let payload = try await bytes(
            reader, offset: payloadStart, length: Int(length), budget: budget
        ), payload.count == Int(length)
        else { return nil }
        return Data(normalizedImage(payload))
    }

    struct AtomRange {
        let start: Int64
        let end: Int64
    }

    /// The first direct child of `parent` with the given atom type. Only
    /// headers are read, so a large `mdat` is stepped over, never fetched.
    static func child(
        _ reader: ByteRangeReader,
        of parent: AtomRange,
        type: String,
        budget: Budget
    ) async throws -> AtomRange? {
        var cursor = parent.start
        while cursor < parent.end {
            guard parent.end - cursor >= 8 else { return nil }
            guard let header = try await bytes(reader, offset: cursor, length: 16, budget: budget),
                  header.count >= 8
            else { return nil }
            var size = Int64(plainU32(header, 0) ?? 0)
            let kind = String(decoding: header[4..<8], as: UTF8.self)
            var headerLength: Int64 = 8
            if size == 1 {
                guard header.count >= 16, let big = plainU64(header, 8) else { return nil }
                // A size that cannot fit in Int64, or that overflows when
                // added, is malformed: reject rather than trap.
                guard big <= UInt64(Int64.max) else { return nil }
                size = Int64(big)
                headerLength = 16
            } else if size == 0 {
                size = parent.end - cursor
            }
            // Subtraction-based bounds: never add a hostile size to an offset.
            guard size >= headerLength, size <= parent.end - cursor else { return nil }
            if kind == type {
                return AtomRange(start: cursor + headerLength, end: cursor + size)
            }
            cursor += size
        }
        return nil
    }

    // MARK: FLAC

    private static func fromFLAC(_ reader: ByteRangeReader, budget: Budget) async throws -> Data? {
        guard let marker = try await bytes(reader, offset: 0, length: 4, budget: budget),
              marker.count == 4, marker.starts(with: Array("fLaC".utf8))
        else { return nil }
        var cursor: Int64 = 4
        var candidates = 0
        while true {
            guard let header = try await bytes(reader, offset: cursor, length: 4, budget: budget),
                  header.count == 4
            else { return nil }
            let isLast = header[0] & 0x80 != 0
            let blockType = header[0] & 0x7F
            let length = Int(header[1]) << 16 | Int(header[2]) << 8 | Int(header[3])
            let body = cursor + 4
            if blockType == 6, length >= 32, length <= maxImageBytes + 1024 {
                candidates += 1
                // An unusable picture block does not end the search: a later
                // one can still be the cover.
                if let payload = try await bytes(reader, offset: body, length: length, budget: budget),
                   payload.count == length, let image = imageFromFLACPicture(payload) {
                    return image
                }
                if candidates >= 8 { return nil }
            }
            if isLast { return nil }
            cursor = body + Int64(length)
        }
    }

    /// FLAC `PICTURE`: type, mime length + mime, description length +
    /// description, four dimension fields, data length, then the image.
    private static func imageFromFLACPicture(_ payload: Data) -> Data? {
        guard let mimeLength = plainU32(payload, 4), mimeLength <= 255,
              payload.count >= 8 + Int(mimeLength) + 4
        else { return nil }
        let mime = String(
            decoding: payload[8..<(8 + Int(mimeLength))], as: UTF8.self
        ).lowercased()
        var index = 8 + Int(mimeLength)
        guard let descriptionLength = plainU32(payload, index) else { return nil }
        index += 4 + Int(descriptionLength)
        guard index + 20 <= payload.count, let dataLength = plainU32(payload, index + 16) else {
            return nil
        }
        index += 20
        guard dataLength <= maxImageBytes, index + Int(dataLength) <= payload.count else { return nil }
        let image = payload[index..<(index + Int(dataLength))]
        guard image.count >= 64, mime.isEmpty || mime.hasPrefix("image/") else { return nil }
        return Data(image)
    }

    // MARK: bytes

    static func plainU32(_ data: Data, _ offset: Int) -> UInt32? {
        guard offset >= 0, offset + 4 <= data.count else { return nil }
        return data[offset..<(offset + 4)].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    static func plainU64(_ data: Data, _ offset: Int) -> UInt64? {
        guard offset >= 0, offset + 8 <= data.count else { return nil }
        return data[offset..<(offset + 8)].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }

    /// ID3 sizes are 7-bit per byte ("syncsafe").
    static func syncSafe(_ data: Data, _ offset: Int) -> Int? {
        guard offset >= 0, offset + 4 <= data.count else { return nil }
        var value = 0
        for byte in data[offset..<(offset + 4)] {
            guard byte & 0x80 == 0 else { return nil }
            value = (value << 7) | Int(byte)
        }
        return value
    }
}

/// Local files as a byte-range reader.
public struct LocalFileByteRangeReader: ByteRangeReader {
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func readRange(offset: Int64, length: Int) async throws -> Data {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(max(0, offset)))
        return try handle.read(upToCount: length) ?? Data()
    }

    public func fileSize() async throws -> Int64 {
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values.fileSize ?? 0)
    }
}

/// An SFTP session as a byte-range reader, so provider artwork uses the same
/// parser as local files.
public struct SftpByteRangeReader: ByteRangeReader {
    private let session: SshSession
    private let path: String

    public init(session: SshSession, path: String) {
        self.session = session
        self.path = path
    }

    public func readRange(offset: Int64, length: Int) async throws -> Data {
        try await session.read(
            path, offset: UInt64(max(0, offset)), length: UInt32(clamping: length)
        )
    }

    public func fileSize() async throws -> Int64 {
        guard let entry = try await session.stat(path) else { return 0 }
        return entry.size
    }
}
