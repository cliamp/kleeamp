import Foundation
import Testing

@testable import CliampCore

@Suite("icy metadata")
struct IcyMetadataParserTests {
    private func metadataBlock(_ title: String) -> [UInt8] {
        let payload = "StreamTitle='\(title)';"
        let raw = Array(payload.utf8)
        let blocks = (raw.count + 15) / 16
        var bytes = [UInt8(blocks)]
        bytes.append(contentsOf: raw)
        bytes.append(contentsOf: [UInt8](repeating: 0, count: blocks * 16 - raw.count))
        return bytes
    }

    @Test("a block between audio chunks yields its title")
    func singleBlock() {
        let metaint = 4
        var parser = IcyMetadataParser(metaint: metaint)
        var data = [UInt8](repeating: 0, count: metaint)
        data.append(contentsOf: metadataBlock("Ryan R. Hughes - Omakase Machine"))
        data.append(contentsOf: [UInt8](repeating: 0, count: metaint))

        let titles = parser.consume(Data(data))
        #expect(titles == ["Ryan R. Hughes - Omakase Machine"])
    }

    @Test("metadata split across chunks is still parsed")
    func splitBlocks() {
        var parser = IcyMetadataParser(metaint: 8)
        var data = [UInt8](repeating: 1, count: 8)
        data.append(contentsOf: metadataBlock("A - B"))
        let split = data.count / 2
        let first = parser.consume(Data(data[..<split]))
        let second = parser.consume(Data(data[split...]))
        #expect(first.isEmpty)
        #expect(second == ["A - B"])
    }

    @Test("an empty length byte carries no title and the stream continues")
    func emptyBlocks() {
        var parser = IcyMetadataParser(metaint: 3)
        var data = [UInt8](repeating: 0, count: 3)
        data.append(0)
        data.append(contentsOf: [UInt8](repeating: 0, count: 3))
        data.append(contentsOf: metadataBlock("Second"))
        #expect(parser.consume(Data(data)) == ["Second"])
    }

    @Test("a block without a stream title is ignored")
    func noTitle() {
        var parser = IcyMetadataParser(metaint: 2)
        let payload = Array("StreamUrl='x';".utf8)
        var data = [UInt8](repeating: 0, count: 2)
        data.append(1)
        data.append(contentsOf: payload)
        data.append(contentsOf: [UInt8](repeating: 0, count: 16 - payload.count))
        #expect(parser.consume(Data(data)).isEmpty)
    }
}
