import Foundation
import Testing

@testable import CliampCore

@Suite("radio navigator")
struct RadioNavigatorTests {
    private let a = Station(id: "a", name: "A", url: "https://a.example/stream", source: .directory)
    private let b = Station(id: "b", name: "B", url: "https://b.example/stream", source: .directory)
    private let c = Station(id: "c", name: "C", url: "https://c.example/stream", source: .directory)

    private var ring: [Station] { [a, b, c] }

    private func name(_ decision: RadioNavDecision) -> String? {
        if case .play(let station, _) = decision { return station.name }
        return nil
    }

    @Test("next steps forward and prev wraps the ring backwards")
    func ringWrap() {
        var navigator = RadioNavigator()
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: a, nowMs: 0)) == "B")
        #expect(name(navigator.previous(walk: ring, ring: true, currentIndex: nil, current: a, nowMs: 300)) == "C")
    }

    @Test("a single-item ring has nowhere to go")
    func singleItem() {
        var navigator = RadioNavigator()
        #expect(navigator.next(walk: [a], ring: true, allowRedo: true, currentIndex: nil, current: a, nowMs: 0) == .ignore)
        #expect(navigator.previous(walk: [a], ring: true, currentIndex: nil, current: a, nowMs: 100) == .ignore)
        #expect(navigator.next(walk: [], ring: false, allowRedo: false, currentIndex: nil, current: nil, nowMs: 200) == .ignore)
    }

    @Test("a burst coalesces on the last tap and a later tap applies at once")
    func burstDebounce() {
        var navigator = RadioNavigator()
        // Isolated tap: applies immediately, so next feels instant.
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: a, nowMs: 0)) == "B")
        // Follow-ups inside 180 ms only update the pending target.
        #expect(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: b, nowMs: 50) == .schedule)
        #expect(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: b, nowMs: 120) == .schedule)
        #expect(navigator.takePending(walk: ring)?.station.name == "A")
        #expect(navigator.takePending(walk: ring) == nil)
        // Past the window a tap is leading-edge again.
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: a, nowMs: 400)) == "B")
    }

    @Test("an explicit source is linear, not a ring")
    func linearSource() {
        var navigator = RadioNavigator()
        #expect(name(navigator.next(walk: ring, ring: false, allowRedo: false, currentIndex: nil, current: b, nowMs: 0)) == "C")
        // At the end of a linear list there is nowhere to go.
        #expect(navigator.next(walk: ring, ring: false, allowRedo: false, currentIndex: nil, current: c, nowMs: 300) == .ignore)
        #expect(navigator.previous(walk: ring, ring: false, currentIndex: nil, current: a, nowMs: 600) == .ignore)
        #expect(name(navigator.previous(walk: ring, ring: false, currentIndex: nil, current: b, nowMs: 900)) == "A")
    }

    @Test("prev walks the session log and next redoes it before the ring")
    func sessionPast() {
        var navigator = RadioNavigator()
        navigator.recordPlay(a)
        navigator.recordPlay(b)
        navigator.recordPlay(c)

        #expect(name(navigator.previous(walk: ring, ring: true, currentIndex: nil, current: c, nowMs: 0)) == "B")
        navigator.recordPlay(b)
        #expect(name(navigator.previous(walk: ring, ring: true, currentIndex: nil, current: b, nowMs: 0)) == "A")
        navigator.recordPlay(a)
        // Next redoes what was stepped back rather than restarting the ring.
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: a, nowMs: 0)) == "B")
        navigator.recordPlay(b)
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: b, nowMs: 0)) == "C")
        navigator.recordPlay(c)
        // At the top of the log the ring takes over again.
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: c, nowMs: 0)) == "A")
    }

    @Test("a promoted source advances linearly, not through the redo tail")
    func promotedSourceIgnoresRedo() {
        var navigator = RadioNavigator()
        let d = Station(id: "d", name: "D", url: "https://d.example/stream", source: .directory)
        // A burst from A settles on D: the first tap applies, the rest coalesce.
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: a, nowMs: 0)) == "B")
        navigator.recordPlay(b)
        #expect(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: b, nowMs: 50) == .schedule)
        let walk = [a, b, c, d]
        #expect(navigator.next(walk: walk, ring: true, allowRedo: true, currentIndex: nil, current: b, nowMs: 90) == .schedule)
        #expect(navigator.takePending(walk: walk)?.station.name == "D")
        navigator.recordPlay(d)
        // Previous lands on B; the promoted (frozen) source moves on to C.
        #expect(name(navigator.previous(walk: walk, ring: true, currentIndex: nil, current: d, nowMs: 400)) == "B")
        navigator.recordPlay(b)
        #expect(name(navigator.next(walk: walk, ring: true, allowRedo: false, currentIndex: nil, current: b, nowMs: 800)) == "C")
    }

    @Test("prev at the bottom of the log wraps the ring")
    func bottomWrap() {
        var navigator = RadioNavigator()
        navigator.recordPlay(a)
        navigator.recordPlay(b)
        #expect(name(navigator.previous(walk: ring, ring: true, currentIndex: nil, current: b, nowMs: 0)) == "A")
        #expect(name(navigator.previous(walk: ring, ring: true, currentIndex: nil, current: a, nowMs: 300)) == "C")
    }

    @Test("a fresh play after stepping back forks the redo tail")
    func forkDropsRedo() {
        var navigator = RadioNavigator()
        navigator.recordPlay(a)
        navigator.recordPlay(b)
        #expect(name(navigator.previous(walk: ring, ring: true, currentIndex: nil, current: b, nowMs: 0)) == "A")
        navigator.recordPlay(c)
        #expect(!navigator.canGoForward)
        #expect(name(navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: c, nowMs: 200)) == "A")
    }

    @Test("duplicate URLs resolve by occurrence, not by first match")
    func duplicateOccurrences() {
        var navigator = RadioNavigator()
        let repeated = [a, b, b, c]
        // From A (index 0), Next lands on the first B occurrence.
        #expect(
            navigator.next(walk: repeated, ring: false, allowRedo: false, currentIndex: 0, current: a, nowMs: 0)
                == .play(b, index: 1)
        )
        // From that B, Next lands on the second B occurrence, not back on B
        // or forward to C.
        #expect(
            navigator.next(walk: repeated, ring: false, allowRedo: false, currentIndex: 1, current: b, nowMs: 300)
                == .play(b, index: 2)
        )
        #expect(
            navigator.next(walk: repeated, ring: false, allowRedo: false, currentIndex: 2, current: b, nowMs: 600)
                == .play(c, index: 3)
        )
    }

    @Test("a history jump cancels a pending burst")
    func historyCancelsPending() {
        var navigator = RadioNavigator()
        navigator.recordPlay(a)
        navigator.recordPlay(b)
        // Two rapid Next taps leave a pending target behind.
        #expect(
            navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: b, nowMs: 0)
                != .schedule
        )
        #expect(
            navigator.next(walk: ring, ring: true, allowRedo: true, currentIndex: nil, current: b, nowMs: 50)
                == .schedule
        )
        // A Previous history jump must discard that pending target.
        #expect(
            navigator.previous(walk: ring, ring: true, currentIndex: nil, current: b, nowMs: 80)
                == .play(a, index: 0)
        )
        #expect(navigator.takePending(walk: ring) == nil)
    }

    @Test("a history item outside the walked list reports no occurrence")
    func historyOutsideSource() {
        var navigator = RadioNavigator()
        let d = Station(id: "d", name: "D", url: "https://d.example/stream", source: .directory)
        navigator.recordPlay(d)
        navigator.recordPlay(a)
        // Walk only knows [B, C]; D is a foreign context.
        #expect(
            navigator.previous(
                walk: [b, c], ring: false, currentIndex: nil, current: a, nowMs: 0
            ) == .play(d, index: -1)
        )
    }

    @Test("the session log is capped at 100")
    func pastCap() {
        var navigator = RadioNavigator()
        for index in 0..<130 {
            navigator.recordPlay(
                Station(
                    id: "s\(index)", name: "S\(index)",
                    url: "https://s\(index).example/stream", source: .directory
                )
            )
        }
        var back = 0
        while navigator.canGoBack {
            _ = navigator.previous(walk: ring, ring: true, currentIndex: nil, current: nil, nowMs: Int64(back) * 1000)
            back += 1
        }
        #expect(back == RadioNavigator.pastCapacity - 1)
    }
}
