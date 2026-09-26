import Foundation
import Testing

@testable import CliampCore

private func song(_ id: String, _ title: String, artist: String = "", album: String = "",
                  dateAdded: Int64 = 0) -> Station {
    Station(id: id, name: title, url: "file:///music/\(id).mp3", source: .local,
            artist: artist, album: album, dateAdded: dateAdded)
}

private func store() -> PlaylistStore {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("cliamp-playlists-\(UUID().uuidString).json")
    return PlaylistStore(file: url)
}

@Suite("playlists")
struct PlaylistStoreTests {
    @Test("create trims, refuses blanks and reuses a taken slug")
    func create() {
        let store = store()
        #expect(store.create(name: "   ") == nil)
        let first = store.create(name: " Night Drive ")
        #expect(first?.name == "Night Drive")
        #expect(first?.slug == "night-drive")
        #expect(store.create(name: "night drive")?.slug == "night-drive")
        #expect(store.all().count == 1)
    }

    @Test("rename, delete and pin")
    func edits() {
        let store = store()
        store.create(name: "Mix")
        store.rename(slug: "mix", name: "  Mix Two ")
        #expect(store.playlist(slug: "mix")?.name == "Mix Two")
        store.rename(slug: "mix", name: "   ")
        #expect(store.playlist(slug: "mix")?.name == "Mix Two")

        store.setPinned(slug: "mix", pinned: true)
        #expect(store.pinnedSlugs() == ["mix"])
        #expect(store.pinned().map(\.slug) == ["mix"])
        store.create(name: "Other")
        #expect(store.unpinned().map(\.slug) == ["other"])

        store.delete(slug: "mix")
        #expect(store.all().map(\.slug) == ["other"])
    }

    @Test("members append once and keep their order")
    func members() {
        let store = store()
        store.create(name: "Mix")
        #expect(store.addSong(slug: "mix", songId: "local:a.mp3"))
        #expect(store.addSong(slug: "mix", songId: "local:b.mp3"))
        #expect(store.addSong(slug: "mix", songId: "local:a.mp3") == false)
        #expect(store.playlist(slug: "mix")?.songIds == ["local:a.mp3", "local:b.mp3"])

        store.removeSong(slug: "mix", songId: "local:a.mp3")
        store.setOrder(slug: "mix", songIds: ["local:c.mp3", "local:b.mp3"])
        #expect(store.playlist(slug: "mix")?.songIds == ["local:c.mp3", "local:b.mp3"])
    }

    @Test("radio and podcast members snapshot so they resolve offline")
    func snapshots() {
        let store = store()
        store.create(name: "Mixed")
        let episode = Station(id: "podcast:1", name: "Ep 1", url: "https://feed/1.mp3", source: .podcast)
        let localA = song("local:a.mp3", "A")
        store.addStation(slug: "mixed", station: episode)
        store.addStation(slug: "mixed", station: localA)

        let localB = song("local:b.mp3", "B")
        let resolved = store.resolve(slug: "mixed", localSongs: [localA, localB])
        #expect(resolved.map(\.id) == ["podcast:1", "local:a.mp3"])
        #expect(resolved.first?.name == "Ep 1")

        // Local members follow the live library: a rescan keeps the member.
        let remapped = Station(id: "local:a.mp3", name: "A (retagged)", url: "file:///music/a.mp3", source: .local)
        #expect(store.resolve(slug: "mixed", localSongs: [remapped]).last?.name == "A (retagged)")
    }

    @Test("edits survive a reopen")
    func persistence() {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("cliamp-playlists-\(UUID().uuidString).json")
        let first = PlaylistStore(file: url)
        first.create(name: "Roadtrip")
        first.setPinned(slug: "roadtrip", pinned: true)
        first.addStation(slug: "roadtrip", station: Station(
            id: "radio:1", name: "Jazz", url: "https://jazz", source: .directory
        ))
        first.addSong(slug: "roadtrip", songId: "local:x.mp3")

        let second = PlaylistStore(file: url)
        #expect(second.all().map(\.name) == ["Roadtrip"])
        #expect(second.pinnedSlugs() == ["roadtrip"])
        #expect(second.playlist(slug: "roadtrip")?.songIds == ["radio:1", "local:x.mp3"])
        let resolved = second.resolve(slug: "roadtrip", localSongs: [song("local:x.mp3", "X")])
        #expect(resolved.map(\.id) == ["radio:1", "local:x.mp3"])
    }

    @Test("slugify keeps Unicode letters and digits, collapses punctuation")
    func slugs() {
        #expect(Playlist.slugify("Night Drive!") == "night-drive")
        #expect(Playlist.slugify("  A -- B  ") == "a-b")
        #expect(Playlist.slugify("Björk Mix") == "björk-mix")
        #expect(Playlist.slugify("!!!").hasPrefix("playlist-"))
        // Unicode decimal digits survive, so these stay distinct.
        #expect(Playlist.slugify("Mix ١") == "mix-١")
        #expect(Playlist.slugify("Mix ٢") == "mix-٢")
    }

    @Test("playlists read in SQLite name order, pinned and unpinned alike")
    func ordering() {
        let store = store()
        store.create(name: "Zebra")
        store.create(name: "alpha")
        store.create(name: "Mix")
        // BINARY collation: uppercase names sort before lowercase ones.
        #expect(store.all().map(\.name) == ["Mix", "Zebra", "alpha"])
        store.setPinned(slug: "zebra", pinned: true)
        store.create(name: "Beat")
        #expect(store.pinned().map(\.name) == ["Zebra"])
        #expect(store.unpinned().map(\.name) == ["Beat", "Mix", "alpha"])
        store.rename(slug: "beat", name: "aardvark")
        #expect(store.unpinned().map(\.name) == ["Mix", "aardvark", "alpha"])
    }

    @Test("ordering compares UTF-8 bytes like SQLite BINARY")
    func byteOrdering() {
        let store = store()
        store.create(name: "zebra")
        store.create(name: "e\u{301}clair") // decomposed é: 0x65 < 0x7A
        #expect(store.all().map(\.name) == ["e\u{301}clair", "zebra"])
    }

    @Test("duration labels read total minutes, never hours")
    func durationLabels() {
        #expect(TimeFormat.durationLabel(5_700_000) == "95:00")
        #expect(TimeFormat.durationLabel(0) == "0:00")
        #expect(TimeFormat.durationLabel(83_000) == "1:23")
    }

    @Test("resolveMembers drops members that no longer exist, in place")
    func resolveDropsMissing() {
        let resolved = PlaylistStore.resolveMembers(
            songIds: ["a", "gone", "b"],
            localSongs: [song("a", "A"), song("b", "B")],
            snapshots: []
        )
        #expect(resolved.map(\.id) == ["a", "b"])
        #expect(PlaylistStore.resolveMembers(songIds: [], localSongs: [], snapshots: []).isEmpty)
    }
}

@Suite("station sort")
struct StationSortTests {
    @Test("all four orders are case-insensitive with a title tiebreak")
    func orders() {
        let songs = [
            song("b", "Beta", artist: "Zed", album: "Same", dateAdded: 20),
            song("a", "alpha", artist: "amy", album: "Same", dateAdded: 30),
            song("c", "Gamma", artist: "Amy", album: "First", dateAdded: 10),
        ]
        #expect(StationSort.title.apply(songs).map(\.name) == ["alpha", "Beta", "Gamma"])
        #expect(StationSort.artist.apply(songs).map(\.name) == ["alpha", "Gamma", "Beta"])
        #expect(StationSort.album.apply(songs).map(\.name) == ["Gamma", "alpha", "Beta"])
        #expect(StationSort.recentlyAdded.apply(songs).map(\.name) == ["alpha", "Beta", "Gamma"])
    }

    @Test("the sort choice persists per list")
    func persistedSort() {
        let defaults = UserDefaults(suiteName: "cliamp-sort-tests-\(UUID().uuidString)")!
        let preferences = LibraryPreferences(defaults: defaults)
        #expect(preferences.sort(for: LibraryPreferences.localSongs) == .title)
        preferences.setSort(.recentlyAdded, for: LibraryPreferences.localSongs)
        preferences.setSort(.artist, for: "roadtrip")
        #expect(preferences.sort(for: LibraryPreferences.localSongs) == .recentlyAdded)
        #expect(preferences.sort(for: "roadtrip") == .artist)
        #expect(preferences.sort(for: "unknown") == .title)
    }
}
