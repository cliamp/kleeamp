import Foundation
import Testing

@testable import CliampCore

@Suite("radio library")
struct RadioLibraryTests {
    private func makeDefaults() -> UserDefaults {
        UserDefaults(suiteName: "radio-library-\(UUID().uuidString)")!
    }

    private func station(_ name: String, _ url: String) -> Station {
        Station(id: url, name: name, url: url, source: .directory)
    }

    @Test("favourites are newest first and toggle both ways")
    func favoriteRoundTrip() {
        let library = RadioLibrary(defaults: makeDefaults())
        let a = station("A", "https://a.example/stream")
        let b = station("B", "https://b.example/stream")

        #expect(library.toggleFavorite(a))
        #expect(library.toggleFavorite(b))
        #expect(library.favorites().map(\.name) == ["B", "A"])
        #expect(library.isFavorite(url: a.url))

        #expect(!library.toggleFavorite(a))
        #expect(library.favorites().map(\.name) == ["B"])
    }

    @Test("history re-stamps a repeat instead of duplicating it")
    func historyTouch() {
        let library = RadioLibrary(defaults: makeDefaults())
        let a = station("A", "https://a.example/stream")
        let b = station("B", "https://b.example/stream")

        library.pushHistory(a)
        library.pushHistory(b)
        library.pushHistory(a)
        #expect(library.history().map(\.name) == ["A", "B"])
    }

    @Test("history trims to the Android limit")
    func historyTrim() {
        let library = RadioLibrary(defaults: makeDefaults())
        for index in 0..<(RadioLibrary.historyLimit + 5) {
            library.pushHistory(station("S\(index)", "https://s\(index).example/stream"))
        }
        let history = library.history()
        #expect(history.count == RadioLibrary.historyLimit)
        #expect(history.first?.name == "S64")
        #expect(history.last?.name == "S5")
    }

    @Test("last station survives a relaunch")
    func lastStationRoundTrip() {
        let defaults = makeDefaults()
        let first = RadioLibrary(defaults: defaults)
        first.setLastStation(station("A", "https://a.example/stream"))

        let second = RadioLibrary(defaults: defaults)
        #expect(second.lastStation()?.name == "A")
    }

    @Test("corrupt stored JSON falls back to empty")
    func corruptData() {
        let defaults = makeDefaults()
        defaults.set(Data("not json".utf8), forKey: "radio_favorites")
        defaults.set(Data("not json".utf8), forKey: "last_station")
        let library = RadioLibrary(defaults: defaults)
        #expect(library.favorites().isEmpty)
        #expect(library.lastStation() == nil)
    }
}
