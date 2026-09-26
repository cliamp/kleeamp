import Foundation

/// What one prev/next tap should cause. An isolated tap applies immediately;
/// a burst coalesces and settles on the final target after the debounce
/// window, the same leading-edge scheme Android uses.
public enum RadioNavDecision: Equatable, Sendable {
    /// Play the occurrence at `index` of the walked list, so duplicate URLs
    /// resolve to the tapped occurrence instead of the first match.
    case play(Station, index: Int)
    case schedule
    case ignore
}

/// Session navigation for radio: the list prev/next walks (an explicit source
/// after a tap from a list, the launch fallback of recent history or
/// favourites before that) plus the log of what was actually heard. A port of
/// the navigation half of Android's `PlayerConnection` `recordPlay` / `prev` /
/// `next` / `step`, including the 180 ms burst debounce and the 100-entry cap.
public struct RadioNavigator: Sendable {
    /// A burst of taps within this window settles on the last one.
    public static let debounceWindowMs: Int64 = 180
    /// Session log cap; the persisted recents list owns real depth.
    public static let pastCapacity = 100

    private var past: [Station] = []
    private var pastIndex = -1
    private var pendingIndex: Int?
    private var lastTapMs: Int64?

    public init() {}

    public var canGoBack: Bool { pastIndex > 0 }
    public var canGoForward: Bool { pastIndex >= 0 && pastIndex < past.count - 1 }

    /// Records the audible item: consecutive duplicates no-op, a fresh play
    /// after stepping back drops the redo tail, and the log stays capped.
    public mutating func recordPlay(_ station: Station) {
        if past.indices.contains(pastIndex), past[pastIndex].url == station.url { return }
        while past.count - 1 > pastIndex { past.removeLast() }
        past.append(station)
        pastIndex = past.count - 1
        while past.count > Self.pastCapacity {
            past.removeFirst()
            pastIndex -= 1
        }
    }

    /// Next follows the redo tail first while walking the launch fallback,
    /// then the ring. An explicit source is linear and never redoes, exactly
    /// like Android's `_source.isNotEmpty()` shortcut.
    public mutating func next(
        walk: [Station], ring: Bool, allowRedo: Bool,
        currentIndex: Int?, current: Station?, nowMs: Int64
    ) -> RadioNavDecision {
        if allowRedo, ring, canGoForward {
            pastIndex += 1
            cancelPending()
            let station = past[pastIndex]
            return .play(station, index: walk.firstIndex(of: station) ?? -1)
        }
        return step(+1, walk: walk, ring: ring, currentIndex: currentIndex, current: current, nowMs: nowMs)
    }

    /// Previous walks what was actually heard, falling back to the walked list
    /// only at the bottom of the stack.
    public mutating func previous(
        walk: [Station], ring: Bool, currentIndex: Int?, current: Station?, nowMs: Int64
    ) -> RadioNavDecision {
        if canGoBack {
            pastIndex -= 1
            cancelPending()
            let station = past[pastIndex]
            return .play(station, index: walk.firstIndex(of: station) ?? -1)
        }
        return step(-1, walk: walk, ring: ring, currentIndex: currentIndex, current: current, nowMs: nowMs)
    }

    /// The coalesced target once a burst's timer fires, or nil when nothing
    /// is pending.
    public mutating func takePending(walk: [Station]) -> (station: Station, index: Int)? {
        guard let index = pendingIndex, !walk.isEmpty else {
            pendingIndex = nil
            return nil
        }
        pendingIndex = nil
        let wrapped = Self.wrap(index, count: walk.count)
        return (walk[wrapped], wrapped)
    }

    /// A fresh explicit play supersedes any coalescing burst still waiting.
    public mutating func cancelPending() {
        pendingIndex = nil
    }

    private mutating func step(
        _ delta: Int, walk: [Station], ring: Bool,
        currentIndex: Int?, current: Station?, nowMs: Int64
    ) -> RadioNavDecision {
        guard !walk.isEmpty else { return .ignore }
        let here: Int
        let target: Int
        if let pendingIndex {
            here = pendingIndex
            target = Self.ring(wrapIfNeeded: pendingIndex + delta, ring: ring, count: walk.count)
        } else if let currentIndex, walk.indices.contains(currentIndex) {
            here = currentIndex
            target = Self.ring(wrapIfNeeded: currentIndex + delta, ring: ring, count: walk.count)
        } else if let current,
                  let index = walk.firstIndex(where: { $0.url == current.url }) {
            here = index
            target = Self.ring(wrapIfNeeded: index + delta, ring: ring, count: walk.count)
        } else {
            // Nothing audible in this list yet: walk from its first item.
            here = 0
            target = delta > 0 ? min(1, walk.count - 1) : 0
        }
        if target == here, pendingIndex == nil { return .ignore }

        let leadingEdge = lastTapMs.map { nowMs - $0 > Self.debounceWindowMs } ?? true
        lastTapMs = nowMs
        if leadingEdge {
            pendingIndex = nil
            return .play(walk[target], index: target)
        }
        pendingIndex = target
        return .schedule
    }

    private static func ring(wrapIfNeeded index: Int, ring: Bool, count: Int) -> Int {
        ring ? wrap(index, count: count) : min(max(index, 0), count - 1)
    }

    private static func wrap(_ index: Int, count: Int) -> Int {
        ((index % count) + count) % count
    }
}
