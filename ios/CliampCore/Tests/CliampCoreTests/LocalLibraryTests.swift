import Foundation
import Testing

@testable import CliampCore

private func temporaryRoot() -> URL {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("cliamp-library-\(UUID().uuidString)", isDirectory: true)
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// A minimal, valid 16-bit mono WAV so AVFoundation reports a real duration.
private func writeWav(at url: URL, seconds: Double = 1.0, sampleRate: Double = 8000) throws {
    let frames = Int(seconds * sampleRate)
    let dataSize = frames * 2
    var data = Data()
    func append(_ string: String) { data.append(contentsOf: Array(string.utf8)) }
    func append32(_ value: Int) {
        var v = UInt32(value)
        for _ in 0..<4 { data.append(UInt8(v & 0xff)); v >>= 8 }
    }
    func append16(_ value: Int) {
        var v = UInt16(value)
        for _ in 0..<2 { data.append(UInt8(v & 0xff)); v >>= 8 }
    }
    append("RIFF")
    append32(36 + dataSize)
    append("WAVE")
    append("fmt ")
    append32(16)
    append16(1) // PCM
    append16(1) // mono
    append32(Int(sampleRate))
    append32(Int(sampleRate) * 2)
    append16(2)
    append16(16)
    append("data")
    append32(dataSize)
    data.append(Data(count: dataSize))
    try FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(), withIntermediateDirectories: true
    )
    try data.write(to: url)
}

private func library(in root: URL) -> LocalLibrary {
    LocalLibrary(root: root, cache: root.appendingPathComponent(".cache/local-songs.json"))
}

@Suite("local library")
struct LocalLibraryTests {
    @Test("scan indexes audio, skips other files and reads durations")
    func scanIndexes() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("Music/One.wav"))
        try writeWav(at: root.appendingPathComponent("Music/Two.wav"), seconds: 2.0)
        try writeWav(at: root.appendingPathComponent("Other/Three.flac"), seconds: 0.5)
        try Data("notes".utf8).write(to: root.appendingPathComponent("Music/readme.txt"))
        try writeWav(at: root.appendingPathComponent("Music/skip.ogg"))
        try Data("x".utf8).write(to: root.appendingPathComponent(".hidden.wav"))

        let songs = await library(in: root).scan()

        #expect(songs.count == 3)
        #expect(songs.map(\.title) == ["One", "Three", "Two"])
        #expect(songs.allSatisfy { $0.artist == "unknown artist" })
        #expect(songs.allSatisfy { $0.id == "local:\($0.relativePath)" })
        let one = try #require(songs.first { $0.title == "One" })
        #expect(abs(one.durationMs - 1000) < 60)
        #expect(one.relativePath == "Music/One.wav")
        #expect(one.fileURL(in: root).lastPathComponent == "One.wav")
    }

    @Test("a rescan keeps identity and drops removed files")
    func rescanDropsMissing() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("a/Keep.wav"))
        try writeWav(at: root.appendingPathComponent("a/Gone.wav"))
        let local = library(in: root)
        let first = await local.scan()
        #expect(first.count == 2)
        #expect(Set(first.map(\.id)) == ["local:a/Keep.wav", "local:a/Gone.wav"])

        try FileManager.default.removeItem(at: root.appendingPathComponent("a/Gone.wav"))
        let second = await local.scan()
        #expect(second.map(\.id) == ["local:a/Keep.wav"])
    }

    @Test("the cache round-trips and filters vanished files")
    func cacheFiltersMissing() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("Keep.wav"))
        try writeWav(at: root.appendingPathComponent("Gone.wav"))
        let local = library(in: root)
        let songs = await local.scan()
        local.save(songs)

        #expect(local.cachedSongs()?.count == 2)
        try FileManager.default.removeItem(at: root.appendingPathComponent("Gone.wav"))
        #expect(local.cachedSongs()?.map(\.id) == ["local:Keep.wav"])
        try FileManager.default.removeItem(at: root.appendingPathComponent("Keep.wav"))
        #expect(local.cachedSongs() == nil)
    }

    @Test("hidden files and document packages stay out of the scan")
    func hiddenAndPackages() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("Music/Real.wav"))
        try writeWav(at: root.appendingPathComponent("Deck.rtfd/Inside.wav"))
        try writeWav(at: root.appendingPathComponent("Take.band/Session.wav"))
        try writeWav(at: root.appendingPathComponent("Music/.hidden.wav"))
        // A Finder-hidden file has flags, not a dot name.
        let flagged = root.appendingPathComponent("Music/flagged.wav")
        try writeWav(at: flagged)
        try (flagged as NSURL).setResourceValue(true, forKey: .isHiddenKey)

        let songs = await library(in: root).scan()
        #expect(songs.map(\.title) == ["Real"])
    }

    @Test("a directory standing in for a cached file is dropped")
    func cacheRejectsDirectories() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("Song.wav"))
        let local = library(in: root)
        local.save(await local.scan())
        try FileManager.default.removeItem(at: root.appendingPathComponent("Song.wav"))
        try FileManager.default.createDirectory(
            at: root.appendingPathComponent("Song.wav"), withIntermediateDirectories: true
        )
        #expect(local.cachedSongs() == nil)
    }

    @Test("a directory named cover.jpg cannot win over a real folder.png")
    func coverRejectsDirectories() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("Album/Song.wav"))
        try FileManager.default.createDirectory(
            at: root.appendingPathComponent("Album/cover.jpg"), withIntermediateDirectories: true
        )
        try Data("png".utf8).write(to: root.appendingPathComponent("Album/folder.png"))

        let songs = await library(in: root).scan()
        #expect(songs.first?.cover == "Album/folder.png")
    }

    @Test("folder covers prefer canonical names and fall back to any image")
    func folderCovers() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("Album Song.wav"))
        try writeWav(at: root.appendingPathComponent("Plain/Plain.wav"))
        try writeWav(at: root.appendingPathComponent("Any/Any.wav"))
        try Data("jpg".utf8).write(to: root.appendingPathComponent("cover.jpg"))
        try Data("png".utf8).write(to: root.appendingPathComponent("Any/random.png"))
        try Data("txt".utf8).write(to: root.appendingPathComponent("Any/notes.txt"))

        let songs = await library(in: root).scan()
        let byTitle = Dictionary(uniqueKeysWithValues: songs.map { ($0.title, $0) })
        #expect(byTitle["Album Song"]?.cover == "cover.jpg")
        #expect(byTitle["Plain"]?.cover == "")
        #expect(byTitle["Any"]?.cover == "Any/random.png")
    }

    @Test("grouping sorts folders and songs by name, case-insensitively")
    func grouping() async throws {
        let root = temporaryRoot()
        try writeWav(at: root.appendingPathComponent("zeta/B.wav"))
        try writeWav(at: root.appendingPathComponent("zeta/a.wav"))
        try writeWav(at: root.appendingPathComponent("Alpha/one.wav"))
        try writeWav(at: root.appendingPathComponent("loose.wav"))
        let songs = await library(in: root).scan()

        let folders = LocalLibrary.folders(of: songs)
        #expect(folders.map(\.name) == ["Alpha", "Library", "zeta"])
        #expect(folders.first?.songs.map(\.title) == ["one"])
        #expect(folders.last?.songs.map(\.title) == ["a", "B"])
        #expect(folders[1].id == "")
        #expect(folders[1].songs.map(\.title) == ["loose"])
    }

    @Test("a station carries the absolute file URL, tags and duration")
    func stationMapping() throws {
        let root = temporaryRoot()
        let song = LocalSong(relativePath: "Music/One.wav", title: "One", artist: "A",
                             album: "B", durationMs: 1000, dateAdded: 5, cover: "Music/cover.jpg")
        let station = song.station(in: root)
        #expect(station.id == "local:Music/One.wav")
        #expect(station.source == .local)
        #expect(station.url == root.appendingPathComponent("Music/One.wav").absoluteString)
        #expect(station.cover == root.appendingPathComponent("Music/cover.jpg").absoluteString)
        #expect(station.artist == "A")
        #expect(station.album == "B")
        #expect(station.isTrack)
        #expect(station.sourceLine == "A · B")
    }
}
