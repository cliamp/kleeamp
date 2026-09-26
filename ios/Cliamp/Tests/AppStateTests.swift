import CliampCore
import Foundation
import Testing

@testable import Cliamp

@Suite("app state persistence")
struct AppStateTests {
    @Test("settings survive a relaunch")
    @MainActor
    func persistenceRoundTrip() {
        let suite = "app-state-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!

        let first = AppState(defaults: defaults)
        first.palettePreference = "catppuccin"
        first.haptics = false
        first.visualizer = "off"
        first.bufferSeconds = 45
        first.autoResume = true
        first.speed = 1.25

        let second = AppState(defaults: defaults)
        #expect(second.palettePreference == "catppuccin")
        #expect(second.haptics == false)
        #expect(second.visualizer == "off")
        #expect(second.bufferSeconds == 45)
        #expect(second.autoResume)
        #expect(second.cellular)
        #expect(!second.mono)
        #expect(second.speed == 1.25)
    }

    @Test("a stored speed outside the range is clamped on load")
    @MainActor
    func speedClamp() {
        let suite = "app-state-speed-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.set(3.0, forKey: "speed")
        #expect(AppState(defaults: defaults).speed == 2)
    }

    @Test("favourites, history and the last station survive a relaunch")
    @MainActor
    func radioLibraryRoundTrip() {
        let suite = "app-state-radio-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let a = Station(id: "a", name: "A", url: "https://a.example/stream", source: .directory)
        let b = Station(id: "b", name: "B", url: "https://b.example/stream", source: .directory)

        let first = AppState(defaults: defaults)
        first.toggleFavorite(a)
        first.recordPlay(a)
        first.recordPlay(b)

        let second = AppState(defaults: defaults)
        #expect(second.isFavorite(a))
        #expect(second.favorites.map(\.name) == ["A"])
        #expect(second.history.map(\.name) == ["B", "A"])
        #expect(second.lastStation?.name == "B")
        #expect(second.fallbackStations.map(\.name) == ["B", "A"])

        second.toggleFavorite(a)
        #expect(!AppState(defaults: defaults).isFavorite(a))
    }

    @Test("favourites are the fallback when history is empty")
    @MainActor
    func favouritesFallback() {
        let suite = "app-state-fallback-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let a = Station(id: "a", name: "A", url: "https://a.example/stream", source: .directory)

        let state = AppState(defaults: defaults)
        #expect(state.fallbackStations.isEmpty)
        state.toggleFavorite(a)
        #expect(state.fallbackStations.map(\.name) == ["A"])
    }
}
