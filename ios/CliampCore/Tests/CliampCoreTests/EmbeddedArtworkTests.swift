import Foundation
import Testing

@testable import CliampCore

/// A reader over an in-memory blob, with optional truncation limits.
private struct BlobReader: ByteRangeReader {
    let data: Data
    let maxRead: Int?

    init(_ data: Data, maxRead: Int? = nil) {
        self.data = data
        self.maxRead = maxRead
    }

    func readRange(offset: Int64, length: Int) async throws -> Data {
        guard offset >= 0, offset < Int64(data.count) else { return Data() }
        let wanted = maxRead.map { min(length, $0) } ?? length
        let end = min(Int(offset) + wanted, data.count)
        // Zero-based, like the readers the app actually uses.
        return Data(data[Int(offset)..<end])
    }

    func fileSize() async throws -> Int64 {
        Int64(data.count)
    }
}

private let pngBytes = Data((0..<256).map { UInt8($0 % 251) })

private func be32(_ value: Int) -> Data {
    Data([
        UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF),
        UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF),
    ])
}

private func syncSafe(_ value: Int) -> Data {
    Data([
        UInt8((value >> 21) & 0x7F), UInt8((value >> 14) & 0x7F),
        UInt8((value >> 7) & 0x7F), UInt8(value & 0x7F),
    ])
}

private func atom(_ type: String, _ payload: Data) -> Data {
    be32(payload.count + 8) + Data(type.utf8) + payload
}

/// An ID3v2.3 tag holding one APIC frame with a PNG cover.
private func id3Fixture(image: Data = pngBytes, version: UInt8 = 3) -> Data {
    var frame = Data([0]) // latin1
    frame.append(contentsOf: Array("image/png".utf8))
    frame.append(0)
    frame.append(3) // front cover
    frame.append(contentsOf: Array("cover".utf8))
    frame.append(0)
    frame.append(image)
    var tag = Data([UInt8(ascii: "A"), UInt8(ascii: "P"), UInt8(ascii: "I"), UInt8(ascii: "C")])
    tag.append(version == 4 ? syncSafe(frame.count) : be32(frame.count))
    tag.append(contentsOf: [0, 0]) // frame flags
    tag.append(frame)
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), version, 0, 0])
    header.append(syncSafe(tag.count))
    return header + tag
}

/// An APIC frame whose description is UTF-16 with a BOM — the shape iTunes
/// and most taggers write, where a byte-wise terminator scan goes wrong.
private func id3UnicodeDescriptionFixture(image: Data) -> Data {
    var frame = Data([1]) // UTF-16 with BOM
    frame.append(contentsOf: Array("image/jpeg".utf8))
    frame.append(0)
    frame.append(3) // front cover
    frame.append(contentsOf: [0xFF, 0xFE]) // BOM
    for unit in Array("Cover".utf16) {
        frame.append(UInt8(unit & 0xFF))
        frame.append(UInt8(unit >> 8))
    }
    frame.append(contentsOf: [0, 0]) // terminator
    frame.append(image)
    var tag = Data([UInt8(ascii: "A"), UInt8(ascii: "P"), UInt8(ascii: "I"), UInt8(ascii: "C")])
    tag.append(be32(frame.count))
    tag.append(contentsOf: [0, 0])
    tag.append(frame)
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0])
    header.append(syncSafe(tag.count))
    return header + tag
}

/// An MP4 with ftyp, a large mdat to skip, then moov > udta > meta > ilst >
/// covr > data around the cover.
private func m4aFixture(image: Data = pngBytes) -> Data {
    let ftyp = atom("ftyp", Data("M4A isom".utf8))
    let mdat = atom("mdat", Data(count: 4096))
    var data = Data([0, 0, 0, 13, 0, 0, 0, 0]) // type flag 13 (jpeg), locale 0
    data.append(image)
    let covr = atom("covr", atom("data", data))
    let ilst = atom("ilst", covr)
    let meta = atom("meta", Data([0, 0, 0, 0]) + ilst)
    let udta = atom("udta", meta)
    let moov = atom("moov", udta)
    return ftyp + mdat + moov
}

/// A FLAC stream with one PICTURE block.
private func flacFixture(image: Data = pngBytes) -> Data {
    let streamInfo = Data([0x00]) + Data([0, 0, 34]) + Data(count: 34)
    var picture = Data()
    picture.append(be32(3)) // front cover
    let mime = Data("image/png".utf8)
    picture.append(be32(mime.count))
    picture.append(mime)
    picture.append(be32(0)) // description
    picture.append(be32(0)) // width
    picture.append(be32(0)) // height
    picture.append(be32(0)) // depth
    picture.append(be32(0)) // colors
    picture.append(be32(image.count))
    picture.append(image)
    var pictureBlock = Data([0x86]) // last block, type 6
    pictureBlock.append(Data([
        UInt8((picture.count >> 16) & 0xFF),
        UInt8((picture.count >> 8) & 0xFF),
        UInt8(picture.count & 0xFF),
    ]))
    pictureBlock.append(picture)
    return Data("fLaC".utf8) + streamInfo + pictureBlock
}

/// Opt-in check against a real file:
/// `CLIAMP_ART_FILE=/path/to.mp3 swift test --filter RealFileArtworkTests`
@Suite("real file artwork", .enabled(if: ProcessInfo.processInfo.environment["CLIAMP_ART_FILE"] != nil))
struct RealFileArtworkTests {
    @Test("extracts the cover from a real file")
    func realFile() async throws {
        let path = ProcessInfo.processInfo.environment["CLIAMP_ART_FILE"] ?? ""
        let image = try await EmbeddedArtwork.extract(
            from: LocalFileByteRangeReader(url: URL(fileURLWithPath: path)),
            fileExtension: (path as NSString).pathExtension
        )
        #expect(image != nil, "no artwork found in \(path)")
        #expect((image?.count ?? 0) > 1024)
        #expect(image?.prefix(3).elementsEqual([0xFF, 0xD8, 0xFF]) == true)
    }
}

/// A v2.3 tag with an extended header before the APIC frame.
private func id3ExtendedHeaderFixture(image: Data) -> Data {
    let extended = be32(6) + Data([0, 0]) + be32(0) // v2.3: size excludes itself
    let apic = id3Fixture(image: image).dropFirst(10)
    var tag = extended
    tag.append(apic)
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0x40])
    header.append(syncSafe(tag.count))
    return header + tag
}

/// A v2.3 tag assembled with the decoded frame size and then unsynchronised
/// as a whole, exactly as the v2.3 spec describes.
private func id3UnsynchronisedFixture(image: Data) -> Data {
    var payload = Data([0]) // latin1
    payload.append(contentsOf: Array("image/jpeg".utf8))
    payload.append(0)
    payload.append(3)
    payload.append(0) // empty description
    payload.append(image)
    var frame = Data([UInt8(ascii: "A"), UInt8(ascii: "P"), UInt8(ascii: "I"), UInt8(ascii: "C")])
    frame.append(be32(payload.count)) // decoded size, before unsynchronisation
    frame.append(contentsOf: [0, 0])
    frame.append(payload)
    var unsynchronised = Data()
    for byte in frame {
        unsynchronised.append(byte)
        if byte == 0xFF { unsynchronised.append(0) }
    }
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0x80])
    header.append(syncSafe(unsynchronised.count))
    return header + unsynchronised
}

/// A v2.4 tag with both tag-level and frame-level unsynchronisation flags:
/// the frame's encoded size covers the inserted bytes, so the payload must be
/// decoded exactly once after locating it.
private func id3v24UnsynchronisedFixture(image: Data) -> Data {
    var payload = Data([0]) // latin1
    payload.append(contentsOf: Array("image/jpeg".utf8))
    payload.append(0)
    payload.append(3)
    payload.append(0)
    payload.append(image)
    var encoded = Data()
    for byte in payload {
        encoded.append(byte)
        if byte == 0xFF { encoded.append(0) }
    }
    var frame = Data([UInt8(ascii: "A"), UInt8(ascii: "P"), UInt8(ascii: "I"), UInt8(ascii: "C")])
    frame.append(syncSafe(encoded.count)) // encoded on-disk size
    frame.append(contentsOf: [0, 0x02]) // frame unsynchronisation
    frame.append(encoded)
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 4, 0, 0x80])
    header.append(syncSafe(frame.count))
    return header + frame
}

/// A v2.3 tag that is unsynchronised as a whole *and* carries an extended
/// header whose CRC can itself contain 0xFF runs.
private func id3UnsynchronisedExtendedFixture(image: Data) -> Data {
    let apic = id3Fixture(image: image).dropFirst(10)
    // v2.3 extended header: size excludes its own four bytes; CRC optional.
    // flags = CRC present, padding = 0, CRC = FF F3 00 00 (an FF run).
    var extended = be32(10) + Data([0x80, 0x00]) + be32(0) + Data([0xFF, 0xF3, 0x00, 0x00])
    extended.append(apic)
    var encoded = Data()
    for byte in extended {
        encoded.append(byte)
        if byte == 0xFF { encoded.append(0) }
    }
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0xC0])
    header.append(syncSafe(encoded.count))
    return header + encoded
}

/// A v2.4 tag with a syncsafe extended header (size includes itself).
private func id3v24ExtendedHeaderFixture(image: Data) -> Data {
    let apic = id3Fixture(image: image, version: 4).dropFirst(10)
    let extended = syncSafe(12) + Data(count: 8) // size includes these 4 bytes
    var tag = Data(extended)
    tag.append(apic)
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 4, 0, 0x40])
    header.append(syncSafe(tag.count))
    return header + tag
}

/// A v2.3 APIC frame with the grouping-identity flag, whose extra byte must
/// not leak into the image.
private func id3GroupedFrameFixture(image: Data) -> Data {
    var payload = Data([0]) // latin1
    payload.append(contentsOf: Array("image/png".utf8))
    payload.append(0)
    payload.append(3)
    payload.append(0)
    payload.append(image)
    var frame = Data([UInt8(ascii: "A"), UInt8(ascii: "P"), UInt8(ascii: "I"), UInt8(ascii: "C")])
    frame.append(be32(payload.count + 1))
    frame.append(contentsOf: [0, 0x20]) // grouping identity
    frame.append(0x7F) // group byte
    frame.append(payload)
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0])
    header.append(syncSafe(frame.count))
    return header + frame
}

/// Two APIC frames: the first carries a link (unusable), the second a JPEG.
private func twoPictureFixture(image: Data) -> Data {
    var tag = id3Fixture(image: image).dropFirst(10)
    // First frame: an APIC whose mime is a link.
    var linked = Data([0])
    linked.append(contentsOf: Array("-->link".utf8))
    linked.append(0)
    linked.append(3)
    linked.append(0)
    linked.append(Data(count: 256))
    var linkFrame = Data([UInt8(ascii: "A"), UInt8(ascii: "P"), UInt8(ascii: "I"), UInt8(ascii: "C")])
    linkFrame.append(be32(linked.count))
    linkFrame.append(contentsOf: [0, 0])
    linkFrame.append(linked)
    tag = linkFrame + tag
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0])
    header.append(syncSafe(tag.count))
    return header + tag
}

/// A reader that counts requests, so a budget test can assert the bound.
private final class CountingReader: ByteRangeReader, @unchecked Sendable {
    private let data: Data
    private let lock = NSLock()
    private(set) var reads = 0

    init(_ data: Data) {
        self.data = data
    }

    func readRange(offset: Int64, length: Int) async throws -> Data {
        lock.withLock { reads += 1 }
        guard offset >= 0, offset < Int64(data.count) else { return Data() }
        let end = min(Int(offset) + length, data.count)
        return Data(data[Int(offset)..<end])
    }

    func fileSize() async throws -> Int64 {
        Int64(data.count)
    }
}

/// Many tiny non-picture frames, then nothing: the walk must stay bounded.
private func manyFramesFixture(count: Int) -> Data {
    var tag = Data()
    for index in 0..<count {
        var frame = Data([UInt8(ascii: "T"), UInt8(ascii: "X"), UInt8(ascii: "X"), UInt8(ascii: "X")])
        let payload = be32(index)
        frame.append(be32(payload.count))
        frame.append(contentsOf: [0, 0])
        frame.append(payload)
        tag.append(frame)
    }
    var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0])
    header.append(syncSafe(tag.count))
    return header + tag
}

private func extendedSizeMP4Fixture() -> Data {
    // free atom, then a moov declaring an impossible extended size.
    let free = atom("free", Data(count: 8))
    var moov = Data([0, 0, 0, 1]) + Data("moov".utf8)
    moov.append(contentsOf: [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF])
    return free + moov
}

@Suite("embedded artwork")
struct EmbeddedArtworkTests {
    @Test("ID3v2.3 APIC is extracted")
    func id3v23() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3Fixture()), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }

    @Test("ID3v2.4 syncsafe frame sizes are extracted")
    func id3v24() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3Fixture(version: 4)), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }

    @Test("a UTF-16 APIC description does not leak a byte into the image")
    func unicodeDescription() async throws {
        // A real JPEG header, so the parser's own magic check applies.
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(count: 252)
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3UnicodeDescriptionFixture(image: jpeg)), fileExtension: "mp3"
        )
        #expect(image == jpeg)
    }

    @Test("a v2.3 extended header does not hide the cover")
    func extendedHeader() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3ExtendedHeaderFixture(image: pngBytes)), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }

    @Test("tag-level unsynchronisation is reversed before parsing")
    func unsynchronised() async throws {
        // A JPEG whose bytes contain 0xFF runs, so unsynchronisation actually
        // inserts zeros into the image data.
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xFF, 0x00, 0xFF, 0xE0]) + Data(count: 128)
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3UnsynchronisedFixture(image: jpeg)), fileExtension: "mp3"
        )
        #expect(image == jpeg)
    }

    @Test("a v2.4 extended header is syncsafe and does not hide the cover")
    func extendedHeaderV24() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3v24ExtendedHeaderFixture(image: pngBytes)), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }

    @Test("v2.4 tag and frame unsynchronisation decode the payload once")
    func unsynchronisedV24() async throws {
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xFF, 0x00, 0xFF, 0xE0]) + Data(count: 128)
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3v24UnsynchronisedFixture(image: jpeg)), fileExtension: "mp3"
        )
        #expect(image == jpeg)
    }

    @Test("an unsynchronised v2.3 extended header does not misalign frames")
    func unsynchronisedExtendedHeader() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3UnsynchronisedExtendedFixture(image: pngBytes)), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }

    @Test("a grouped v2.3 frame keeps its group byte out of the image")
    func groupedFrame() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(id3GroupedFrameFixture(image: pngBytes)), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }

    @Test("an unusable first picture does not hide a later one")
    func laterPictureWins() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(twoPictureFixture(image: pngBytes)), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }

    @Test("a payload crossing the declared tag end is refused")
    func payloadBeyondTag() async throws {
        var base = id3Fixture()
        base.replaceSubrange(6..<10, with: syncSafe(20)) // tag far too small
        #expect(try await EmbeddedArtwork.extract(from: BlobReader(base), fileExtension: "mp3") == nil)
    }

    @Test("a huge frame walk stays within the read budget")
    func budgetBounded() async throws {
        let reader = CountingReader(manyFramesFixture(count: 5_000))
        #expect(try await EmbeddedArtwork.extract(from: reader, fileExtension: "mp3") == nil)
        #expect(reader.reads <= EmbeddedArtwork.maxReads + 2)
    }

    @Test("a malformed extended MP4 size cannot trap or be accepted")
    func mp4Overflow() async throws {
        #expect(try await EmbeddedArtwork.extract(
            from: BlobReader(extendedSizeMP4Fixture()), fileExtension: "m4a"
        ) == nil)
    }

    @Test("cancellation propagates out of extraction")
    func cancellation() async {
        struct CancellingReader: ByteRangeReader {
            func readRange(offset: Int64, length: Int) async throws -> Data {
                throw CancellationError()
            }
            func fileSize() async throws -> Int64 { 4096 }
        }
        await #expect(throws: CancellationError.self) {
            try await EmbeddedArtwork.extract(from: CancellingReader(), fileExtension: "mp3")
        }
    }

    @Test("an MP4 covr atom is found past a large mdat")
    func mp4() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(m4aFixture()), fileExtension: "m4a"
        )
        #expect(image == pngBytes)
    }

    @Test("a FLAC PICTURE block is extracted")
    func flac() async throws {
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(flacFixture()), fileExtension: "flac"
        )
        #expect(image == pngBytes)
    }

    @Test("an oversized declared cover is refused, not read")
    func oversized() async throws {
        var huge = Data([0])
        huge.append(contentsOf: Array("image/png".utf8))
        huge.append(0)
        huge.append(3)
        huge.append(0)
        huge.append(Data(count: EmbeddedArtwork.maxImageBytes + 2048))
        var tag = Data([UInt8(ascii: "A"), UInt8(ascii: "P"), UInt8(ascii: "I"), UInt8(ascii: "C")])
        tag.append(be32(huge.count))
        tag.append(contentsOf: [0, 0])
        tag.append(huge)
        var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0])
        header.append(syncSafe(tag.count))
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(header + tag), fileExtension: "mp3"
        )
        #expect(image == nil)
    }

    @Test("junk, wrong extensions and truncation all yield nothing")
    func rejects() async throws {
        #expect(try await EmbeddedArtwork.extract(from: BlobReader(Data(count: 64)), fileExtension: "mp3") == nil)
        #expect(try await EmbeddedArtwork.extract(from: BlobReader(id3Fixture()), fileExtension: "wav") == nil)
        let truncated = BlobReader(id3Fixture(), maxRead: 8)
        #expect(try await EmbeddedArtwork.extract(from: truncated, fileExtension: "mp3") == nil)
    }

    @Test("a second APIC is reached when the first frame is something else")
    func skipsOtherFrames() async throws {
        var tag = Data([UInt8(ascii: "T"), UInt8(ascii: "I"), UInt8(ascii: "T"), UInt8(ascii: "2")])
        let title = Data([0]) + Array("Hello".utf8)
        tag.append(be32(title.count))
        tag.append(contentsOf: [0, 0])
        tag.append(title)
        let apic = id3Fixture().dropFirst(10)
        tag.append(apic)
        var header = Data([UInt8(ascii: "I"), UInt8(ascii: "D"), UInt8(ascii: "3"), 3, 0, 0])
        header.append(syncSafe(tag.count))
        let image = try await EmbeddedArtwork.extract(
            from: BlobReader(header + tag), fileExtension: "mp3"
        )
        #expect(image == pngBytes)
    }
}
