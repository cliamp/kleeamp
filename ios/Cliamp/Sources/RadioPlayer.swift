import AVFoundation
import CliampCore
import Foundation
import Network
import Observation
import os

/// One AVPlayer owns the session, so nothing can ever produce two streams.
/// The engine is deliberately small until FND-03 picks the final audio stack;
/// what it proves today is live radio with a real transport, a reconnect
/// ladder and the lock screen.
///
/// A process-wide singleton: SwiftUI can rebuild the root view as often as it
/// likes without standing up a second AVPlayer, remote-command set, watchdog
/// or network monitor.
@MainActor
@Observable
final class RadioPlayer {
    static let shared = RadioPlayer()

    private let player = AVPlayer()
    private let errorLog = Logger(subsystem: "stream.cliamp.mobile", category: "player")
    private let icy = IcyMetadataReader()
    private var timeControlObservation: NSKeyValueObservation?
    private var itemStatusObservation: NSKeyValueObservation?
    private var tap: SpectrumTap?
    private var ticker: Timer?
    private var sessionConfigured = false
    private var navigator = RadioNavigator()
    private var navTask: Task<Void, Never>?
    private var system: SystemPlayback?
    private var policy = ReconnectPolicy()
    private var retryTask: Task<Void, Never>?
    private var watchdog: Timer?
    private var bufferingSinceMs: Int64?
    private var pathMonitor: NWPathMonitor?
    /// The session's active list: what a tap from a screen set, or the frozen
    /// fallback the first navigation promoted. Empty until then.
    private var source: [Station] = []
    /// The occurrence of the audible station inside `source`; duplicates are
    /// resolved by index, so "play next" of an existing URL cannot trap Next.
    private var sourceIndex: Int?
    /// Identity of the list the source came from, so a same-list tap preserves
    /// the arranged tail while a different list replaces it.
    private var sourceContextKey: String?
    /// True while navigation is walking the launch fallback as a ring.
    private var ringFallback = false
    /// Set when an audio interruption pauses something worth resuming.
    private var resumeAfterInterruption = false
    /// The URL actually handed to AVPlayer: a playlist link resolves to this
    /// before playback and retries reuse it instead of re-fetching.
    private var streamURL: URL?
    /// Distinguishes newer plays from playlist resolutions that finish late.
    private var playGeneration = 0
    private var resolveTask: Task<Void, Never>?
    /// True while a playlist hop is in flight; the loaded item still belongs
    /// to the previous selection until it completes.
    private var resolving = false
    /// Saved episode position to seek to once the item is ready.
    private var pendingStartMs: Int64 = 0
    private var lastProgressWriteMs: Int64 = 0
    private var endObserver: NSObjectProtocol?

    /// How often a playing track's position reaches the podcast store.
    private static let progressIntervalMs: Int64 = 5_000

    /// The latest 64-band FFT frame from the audio thread, empty when nothing
    /// is flowing. The meters read it; nothing else should.
    let spectrum = SpectrumStore()

    private(set) var station: Station?
    private(set) var streamTitle = ""
    private(set) var bufferedSeconds = 0
    private(set) var playing = false
    private(set) var buffering = false
    private(set) var error: String?
    private(set) var elapsedMs: Int64 = 0
    /// Known only once a finite source is ready; live radio stays at 0.
    private(set) var durationMs: Int64 = 0
    private(set) var seekable = false
    /// Playback speed multiplier, 0.5-2.0; applied live and persisted.
    private(set) var speed = 1.0
    /// Amber recovery state: the first attempt starts at 1.
    private(set) var reconnecting = false
    private(set) var reconnectAttempt = 0
    /// The user's intent, the iOS counterpart of Android's `playWhenReady`:
    /// true through buffering and failures until pause. The transport glyphs
    /// follow this, not audibility.
    private(set) var wantsToPlay = false

    /// Called on every user-initiated play so history and the last station
    /// reach persistence at one choke point (RAD-12).
    var onRecordPlay: ((Station) -> Void)?

    /// Where prev/next walk before an explicit source exists: recent history,
    /// or favourites when history is empty.
    var fallbackProvider: (() -> [Station])?

    /// The speed choice is persisted by the app state, the same split as
    /// history and favourites.
    var onSpeedChange: ((Double) -> Void)?

    /// Where a track should start (saved episode position), and where its
    /// progress goes. Both are wired to the podcast store; radio ignores them.
    var resumeProvider: ((Station) -> Int64)?
    var progressSink: ((Station, Int64, Int64) -> Void)?
    /// Resolves an SSH account for `cliamp-sftp://` playback; set at launch.
    var sftpSessionProvider: (@Sendable (String) async -> SshSession?)?
    /// Loaders are retained per item: AVPlayer asks through a delegate the
    /// asset holds weakly, so the player must keep it alive.
    private var sftpLoaders: [ObjectIdentifier: SftpResourceLoader] = [:]

    /// A downloaded episode's local path, preferred over the network when the
    /// same remote URL has a file on disk.
    var downloadLookup: ((String) -> String?)?

    private(set) var hasPrev = false
    private(set) var hasNext = false

    /// A scrubber is only honest when the loaded item has a finite length and
    /// real seekable ranges; the source's origin does not decide it.
    var scrubbable: Bool {
        seekable && durationMs > 0
    }

    /// What the system should treat as a live stream: anything without a
    /// finite, seekable end.
    var isLive: Bool {
        !scrubbable
    }

    private init() {
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor [weak self] in
                self?.apply(status)
            }
        }
        system = SystemPlayback(player: self)
        startWatchdog()
        startNetworkMonitor()
    }

    /// A play tapped on a screen hands navigation to that screen's list, the
    /// same source identity Android's `playFromList` establishes. A tap from
    /// the same list plays now but keeps every pending entry in its existing
    /// order; a tap from a different list replaces it.
    func play(_ station: Station, from list: [Station] = [], contextKey: String? = nil) {
        navigator.cancelPending()
        navTask?.cancel()
        navTask = nil
        let sameList = contextKey != nil && contextKey == sourceContextKey && !source.isEmpty
        if sameList,
           let currentIndex = sourceIndex ?? source.firstIndex(where: { $0.url == self.station?.url }) {
            // Insert the tapped occurrence right after the current one; the
            // arranged tail keeps its order.
            if source.indices.contains(currentIndex), source[currentIndex].url != station.url {
                source.insert(station, at: currentIndex + 1)
                sourceIndex = currentIndex + 1
            }
        } else {
            source = list.isEmpty ? [station] : list
            sourceContextKey = contextKey
            ringFallback = false
            sourceIndex = source.firstIndex { $0.url == station.url } ?? 0
        }
        begin(station)
    }

    private func begin(_ station: Station) {
        configureSessionIfNeeded()
        cancelRecovery()
        // A finished track's position is committed before the next one starts.
        saveProgressIfNeeded(force: true)
        self.station = station
        error = nil
        elapsedMs = 0
        bufferedSeconds = 0
        streamTitle = ""
        wantsToPlay = true
        resumeAfterInterruption = false
        pendingStartMs = resumeProvider?(station) ?? 0
        lastProgressWriteMs = nowMs()
        guard let url = URL(string: station.url) else {
            error = "couldn't play that stream"
            updateNavigationAvailability()
            system?.refresh()
            return
        }
        onRecordPlay?(station)
        navigator.recordPlay(station)
        updateNavigationAvailability()
        system?.refresh()
        playGeneration += 1
        streamURL = nil
        resolveStream(station: station, url: url, generation: playGeneration)
    }

    /// Directory entries sometimes point at an .m3u/.pls file rather than the
    /// stream; resolve one hop off the main actor, then play. HLS and direct
    /// URLs pass through untouched. While it resolves, the player reports
    /// buffering so the transport is honest about the wait.
    private func resolveStream(station: Station, url: URL, generation: Int) {
        // A downloaded episode plays from disk, same identity everywhere.
        if let local = downloadLookup?(station.url) {
            errorLog.info("playing downloaded file \(local, privacy: .public)")
            resolveTask?.cancel()
            resolveTask = nil
            resolving = false
            buffering = false
            streamURL = URL(fileURLWithPath: local)
            startStream(station: station, url: URL(fileURLWithPath: local), generation: generation)
            return
        }
        buffering = true
        resolving = true
        resolveTask?.cancel()
        resolveTask = Task { [weak self] in
            let resolved = await StreamResolver.resolve(url.absoluteString)
            guard !Task.isCancelled, let self, generation == self.playGeneration,
                  let target = URL(string: resolved)
            else { return }
            self.resolving = false
            self.buffering = false
            self.streamURL = target
            self.startStream(station: station, url: target, generation: generation)
        }
    }

    /// Builds a fresh item for [station] and plays it: the one path that ever
    /// touches AVPlayer, used by explicit plays, navigation and reconnects.
    /// [generation] keeps a stale item's callbacks from touching a newer
    /// selection while a playlist hop is still in flight.
    private func startStream(station: Station, url: URL, generation: Int) {
        icy.stop()
        spectrum.clear()
        // A replacement stream gets a fresh stall deadline; if the previous
        // status was buffering, no status change will re-arm it.
        bufferingSinceMs = wantsToPlay ? nowMs() : nil
        let item: AVPlayerItem
        if url.scheme == SftpURI.scheme,
           let ref = SftpURI.parse(url.absoluteString),
           let sessions = sftpSessionProvider {
            // A custom scheme has to be served by a resource loader; the
            // delegate answers range reads straight off the SSH session.
            let asset = AVURLAsset(url: url)
            let loader = SftpResourceLoader(
                accountId: ref.accountId, path: ref.path, sessions: sessions
            )
            asset.resourceLoader.setDelegate(
                loader,
                queue: DispatchQueue(label: "stream.cliamp.sftp.loader", qos: .userInitiated)
            )
            item = AVPlayerItem(asset: asset)
            sftpLoaders = [ObjectIdentifier(item): loader]
        } else {
            item = AVPlayerItem(url: url)
        }
        // A post-effects tap gives the meters the PCM that is actually
        // playing, for the real FFT. If the tap cannot attach, the meter
        // falls back to its idle stagger and audio is unaffected.
        #if DEBUG
        let tapDisabled = ProcessInfo.processInfo.arguments.contains("-cliamp-no-tap")
        #else
        let tapDisabled = false
        #endif
        let spectrumTap = SpectrumTap(store: spectrum)
        if !tapDisabled, let processor = spectrumTap.makeProcessingTap() {
            let mix = AVMutableAudioMix()
            let parameters = AVMutableAudioMixInputParameters()
            parameters.audioTapProcessor = processor
            mix.inputParameters = [parameters]
            item.audioMix = mix
            tap = spectrumTap
        }
        let identity = ObjectIdentifier(item)
        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let status = item.status
            let message = item.error?.localizedDescription
            let error = item.error as NSError?
            Task { @MainActor [weak self] in
                guard let self, generation == self.playGeneration,
                      let current = self.player.currentItem,
                      ObjectIdentifier(current) == identity
                else { return }
                if status == .failed, let error {
                    self.errorLog.error(
                        "item failed code=\(error.code, privacy: .public) domain=\(error.domain, privacy: .public) underlying=\(String(describing: error.userInfo[NSUnderlyingErrorKey]), privacy: .public)"
                    )
                    self.handleStreamFailure(error, message: message)
                } else {
                    // Duration and seekable ranges arrive with readiness.
                    self.refreshCapabilities()
                    // An episode resumes where it stopped, once it can seek.
                    if status == .readyToPlay, self.pendingStartMs > 0 {
                        let position = self.pendingStartMs
                        self.pendingStartMs = 0
                        self.player.currentItem?.seek(
                            to: CMTime(value: CMTimeValue(position), timescale: 1000),
                            toleranceBefore: .zero,
                            toleranceAfter: .zero,
                            completionHandler: nil
                        )
                    }
                }
            }
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.handleItemEnd(identity: identity, generation: generation)
            }
        }
        player.replaceCurrentItem(with: item)
        player.defaultRate = Float(speed)
        // A stream resolved after the user already paused loads silently.
        if wantsToPlay {
            player.play()
            if speed != 1 {
                player.rate = Float(speed)
            }
            // The ICY reader is a second connection; it follows intent too.
            icy.start(url: url) { [weak self] title in
                Task { @MainActor [weak self] in
                    self?.streamTitle = title
                    self?.system?.refresh()
                }
            }
        }
    }

    /// A failed item either enters the backoff ladder or surfaces a stable
    /// error: malformed containers and unsupported codecs are not retried.
    private func handleStreamFailure(_ failure: NSError, message: String?) {
        if wantsToPlay, ReconnectPolicy.isRecoverable(failure) {
            scheduleRetry(reason: "error \(failure.code)")
            return
        }
        error = message ?? "couldn't play that stream"
        playing = false
        cancelRecovery()
        system?.refresh()
    }

    /// Shows the last station without making a sound, matching the Android
    /// service restoring `last_station` for the mini player while auto-resume
    /// is off. A station already on screen wins.
    func restore(_ station: Station) {
        guard self.station == nil else { return }
        self.station = station
        sourceIndex = nil
        updateNavigationAvailability()
    }

    /// Insert a station right after the current occurrence, the queue's
    /// "play next". The active list keeps its order; the playback position is
    /// untouched.
    func playNext(_ station: Station) {
        establishCurrentOccurrence()
        let index = sourceIndex ?? 0
        source.insert(station, at: min(index + 1, source.count))
        updateNavigationAvailability()
    }

    /// Append a station to the active list's end, the queue's "add to queue".
    func addToQueue(_ station: Station) {
        establishCurrentOccurrence()
        source.append(station)
        updateNavigationAvailability()
    }

    /// A restored cold-launch station has no source yet; queuing must include
    /// the audible item so Next still has somewhere to go.
    private func establishCurrentOccurrence() {
        guard let station else { return }
        if source.isEmpty {
            source = [station]
            sourceIndex = 0
            ringFallback = false
            return
        }
        if sourceIndex == nil {
            sourceIndex = source.firstIndex { $0.url == station.url } ?? 0
        }
    }

    /// Steps forward: the redo tail first when walking the fallback, then the
    /// walked list. An isolated tap lands immediately; a burst settles on the
    /// final target.
    func goNext() {
        let walk = currentWalk()
        guard !walk.isEmpty else { return }
        let ring = isRing(walk)
        // Redo only exists while walking the launch fallback; once a source
        // owns navigation it is linear, exactly like Android.
        switch navigator.next(
            walk: walk, ring: ring, allowRedo: source.isEmpty,
            currentIndex: sourceIndex, current: station, nowMs: nowMs()
        ) {
        case .play(let target, let index):
            navTask?.cancel()
            navTask = nil
            commitNavigation(target, index: index, walk: walk)
        case .schedule: schedulePending()
        case .ignore: break
        }
    }

    /// Steps back through what was heard this session, then the walked list.
    func goPrevious() {
        let walk = currentWalk()
        guard !walk.isEmpty else { return }
        let ring = isRing(walk)
        switch navigator.previous(
            walk: walk, ring: ring, currentIndex: sourceIndex, current: station, nowMs: nowMs()
        ) {
        case .play(let target, let index):
            navTask?.cancel()
            navTask = nil
            commitNavigation(target, index: index, walk: walk)
        case .schedule: schedulePending()
        case .ignore: break
        }
    }

    /// The fallback list changed (a new favourite, a play recorded): recompute
    /// whether the transport keys can go anywhere.
    func refreshNavigation() {
        updateNavigationAvailability()
    }

    /// A navigation target is committed: the launch fallback is promoted into
    /// a frozen source the moment a step lands, exactly as Android's
    /// `startPlayback(..., preserveOrder = true)` does, so recency updates
    /// cannot reshuffle the walk under the next tap.
    private func commitNavigation(_ target: Station, index: Int, walk: [Station]) {
        // A history jump to something outside the walked list is a foreign
        // context: start it as a fresh single, not an index-0 alias.
        if index < 0 {
            source = [target]
            sourceIndex = 0
            sourceContextKey = nil
            ringFallback = false
            begin(target)
            return
        }
        if source.isEmpty {
            source = walk
            ringFallback = true
        }
        // The navigator chose an occurrence; when it was walking the active
        // source that index is authoritative even with duplicate URLs.
        sourceIndex = (walk.count == source.count) ? index : source.firstIndex(of: target)
        begin(target)
    }

    private func schedulePending() {
        navTask?.cancel()
        navTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Int(RadioNavigator.debounceWindowMs)))
            guard !Task.isCancelled, let self else { return }
            self.navTask = nil
            let walk = self.currentWalk()
            guard let pending = self.navigator.takePending(walk: walk) else { return }
            self.commitNavigation(pending.station, index: pending.index, walk: walk)
        }
    }

    private func currentWalk() -> [Station] {
        source.isEmpty ? fallback() : source
    }

    private func isRing(_ walk: [Station]) -> Bool {
        walk.count > 1 && (source.isEmpty || ringFallback)
    }

    private func updateNavigationAvailability() {
        let walk = currentWalk()
        let ring = isRing(walk)
        let index = sourceIndex ?? station.flatMap { current in
            walk.firstIndex { $0.url == current.url }
        } ?? -1
        hasPrev = ring || navigator.canGoBack || (index > 0)
        hasNext = ring || (source.isEmpty && navigator.canGoForward)
            || (index >= 0 && index < walk.count - 1)
    }

    private func fallback() -> [Station] {
        fallbackProvider?() ?? []
    }

    /// Monotonic milliseconds, the iOS counterpart of Android's uptimeMillis.
    private func nowMs() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    func toggle() {
        if wantsToPlay {
            pause()
        } else {
            resume()
        }
    }

    /// An explicit pause: the lock screen and the app's transport land here.
    func pause() {
        guard station != nil else { return }
        wantsToPlay = false
        resumeAfterInterruption = false
        player.pause()
        saveProgressIfNeeded(force: true)
        icy.stop()
        navigator.cancelPending()
        navTask?.cancel()
        navTask = nil
        cancelRecovery()
        system?.refresh()
    }

    /// Pauses for an audio interruption, remembering whether audio was wanted
    /// so it can come back when the interruption ends. Any explicit pause
    /// in the meantime clears that intent.
    func pauseFromInterruption() {
        let wasPlaying = wantsToPlay
        pause()
        resumeAfterInterruption = wasPlaying
    }

    /// Consumes the interruption-resume intent.
    func takeInterruptionResumeWanted() -> Bool {
        defer { resumeAfterInterruption = false }
        return resumeAfterInterruption
    }

    /// Resumes the loaded item, rebuilding it when the last one failed or the
    /// station only exists as restored cold-launch state.
    func resume() {
        guard station != nil else { return }
        error = nil
        wantsToPlay = true
        if resolving {
            // A playlist hop is still in flight; its completion sees the
            // restored intent and starts the new stream playing. The loaded
            // item still belongs to the previous selection.
            system?.refresh()
            return
        }
        if let current = player.currentItem, current.status != .failed {
            player.play()
            resumeMetadata()
        } else if let station, let url = URL(string: station.url) {
            configureSessionIfNeeded()
            // A restored station has no item yet: go through resolution so the
            // saved position and any downloaded file are honoured.
            pendingStartMs = resumeProvider?(station) ?? pendingStartMs
            resolveStream(station: station, url: url, generation: playGeneration)
        }
        system?.refresh()
    }

    private func resumeMetadata() {
        // The resolved URL, not the playlist link it came from.
        guard let station, let url = streamURL ?? URL(string: station.url) else { return }
        icy.start(url: url) { [weak self] title in
            Task { @MainActor [weak self] in
                self?.streamTitle = title
                self?.system?.refresh()
            }
        }
    }

    // MARK: seek and speed

    /// Seeks to an absolute position. Requests at or beyond the near-end
    /// guard are ignored rather than clamped, matching Android. On success
    /// the published position follows immediately, so a paused scrub updates
    /// the thumb and the lock screen.
    func seek(toPositionMs positionMs: Int64) {
        guard scrubbable, let item = player.currentItem else { return }
        guard SeekPolicy.canSeek(positionMs: positionMs, durationMs: durationMs) else { return }
        let identity = ObjectIdentifier(item)
        item.seek(
            to: CMTime(value: CMTimeValue(positionMs), timescale: 1000),
            toleranceBefore: .zero,
            toleranceAfter: .zero,
            completionHandler: { [weak self] finished in
                guard finished else { return }
                Task { @MainActor [weak self] in
                    guard let self, let current = self.player.currentItem,
                          ObjectIdentifier(current) == identity
                    else { return }
                    self.syncPosition()
                    self.system?.refresh()
                }
            }
        )
    }

    func seek(toFraction fraction: Double) {
        guard durationMs > 0 else { return }
        let clamped = min(max(fraction, 0), 1)
        seek(toPositionMs: Int64(clamped * Double(durationMs)))
    }

    // MARK: progress

    /// A finite item reached its end: commit the completion and advance, or
    /// stop at the end of the queue. Live radio never fires this.
    private func handleItemEnd(identity: ObjectIdentifier, generation: Int) {
        guard generation == playGeneration,
              let current = player.currentItem, ObjectIdentifier(current) == identity,
              let station, station.isTrack
        else { return }
        let duration = durationMs > 0 ? durationMs : station.durationMs
        progressSink?(station, duration, duration)
        if hasNext {
            goNext()
        } else {
            wantsToPlay = false
            playing = false
            icy.stop()
            system?.refresh()
        }
    }

    /// Commits the audible position on the podcast cadence: every five
    /// seconds while playing, and immediately on pause or track change.
    private func saveProgressIfNeeded(force: Bool) {
        guard let station, station.isTrack, elapsedMs > 0 else { return }
        let now = nowMs()
        guard force || now - lastProgressWriteMs >= Self.progressIntervalMs else { return }
        lastProgressWriteMs = now
        progressSink?(station, elapsedMs, durationMs > 0 ? durationMs : station.durationMs)
    }

    /// Applies a speed without persisting: the launch restore from the stored
    /// default. One tap on the key goes through `cycleSpeed`.
    func setSpeed(_ value: Double) {
        speed = PlaybackSpeed.clamped(value)
        player.defaultRate = Float(speed)
        if wantsToPlay {
            player.rate = Float(speed)
        }
        system?.refresh()
    }

    /// One tap on the speed key: step the ladder, persist, apply live.
    func cycleSpeed() {
        let next = PlaybackSpeed.next(after: speed)
        onSpeedChange?(next)
        setSpeed(next)
    }

    private func apply(_ status: AVPlayer.TimeControlStatus) {
        buffering = status == .waitingToPlayAtSpecifiedRate
        playing = status == .playing
        refreshCapabilities()
        if buffering {
            if bufferingSinceMs == nil { bufferingSinceMs = nowMs() }
        } else {
            bufferingSinceMs = nil
        }
        if playing {
            // A stream that actually delivers audio resets the ladder and
            // disarms any retry that was still waiting.
            finishRecovery()
        }
        updateTicker()
        system?.refresh()
    }

    // MARK: reconnect

    /// Waits out the backoff ladder, then rebuilds the stream. Pausing,
    /// network return and a fresh play all cancel it first.
    private func scheduleRetry(reason: String) {
        guard retryTask == nil, station != nil else { return }
        let waitMs = policy.scheduleRetry()
        reconnectAttempt = policy.attempt
        reconnecting = true
        error = nil
        errorLog.info("reconnect #\(self.reconnectAttempt, privacy: .public) in \(waitMs)ms (\(reason, privacy: .public))")
        system?.refresh()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(waitMs))
            guard !Task.isCancelled, let self else { return }
            self.retryTask = nil
            guard self.wantsToPlay, let station = self.station,
                  let url = self.streamURL ?? URL(string: station.url)
            else {
                self.finishRecovery()
                return
            }
            // A replacement item starts at zero; keep the audible position.
            if station.isTrack, self.elapsedMs > 0 {
                self.pendingStartMs = self.elapsedMs
            }
            self.startStream(station: station, url: url, generation: self.playGeneration)
        }
    }

    /// The 20-second stall watchdog: a stream that stops delivering without
    /// any error looks identical to a slow buffer until the timeout. A pending
    /// retry is already handling it; a replacement stream gets its own
    /// deadline from `startStream`.
    private func startWatchdog() {
        guard watchdog == nil else { return }
        let interval = Double(ReconnectPolicy.watchdogIntervalMs) / 1000
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.checkStall()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        watchdog = timer
    }

    private func checkStall() {
        guard wantsToPlay, retryTask == nil, error == nil else { return }
        guard player.timeControlStatus == .waitingToPlayAtSpecifiedRate,
              let since = bufferingSinceMs
        else { return }
        guard ReconnectPolicy.isStalled(bufferingSinceMs: since, nowMs: nowMs()) else { return }
        bufferingSinceMs = nil
        scheduleRetry(reason: "stalled with no error")
    }

    /// Coming back into signal retries now instead of waiting out the ladder.
    private func startNetworkMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor [weak self] in
                self?.networkReturned()
            }
        }
        monitor.start(queue: DispatchQueue(label: "stream.cliamp.mobile.network"))
        pathMonitor = monitor
    }

    private func networkReturned() {
        guard wantsToPlay, station != nil else { return }
        let failed = player.currentItem?.status == .failed
        guard reconnecting || failed else { return }
        retryTask?.cancel()
        retryTask = nil
        policy.reset()
        reconnectAttempt = 0
        reconnecting = false
        error = nil
        system?.refresh()
        guard let station, let url = streamURL ?? URL(string: station.url) else { return }
        if station.isTrack, elapsedMs > 0 {
            pendingStartMs = elapsedMs
        }
        startStream(station: station, url: url, generation: playGeneration)
    }

    private func cancelRecovery() {
        retryTask?.cancel()
        retryTask = nil
        finishRecovery()
    }

    private func finishRecovery() {
        retryTask?.cancel()
        retryTask = nil
        policy.reset()
        reconnecting = false
        reconnectAttempt = 0
    }

    /// Reads what the loaded item can actually do. Duration and seekable
    /// ranges only exist once a finite source is ready; live radio stays at 0.
    private func refreshCapabilities() {
        guard let item = player.currentItem else {
            durationMs = 0
            seekable = false
            return
        }
        let seconds = CMTimeGetSeconds(item.duration)
        durationMs = seconds.isFinite && seconds > 0 ? Int64(seconds * 1000) : 0
        seekable = durationMs > 0 && !item.seekableTimeRanges.isEmpty
    }

    /// Publishes the player's clock and buffered depth to the UI.
    private func syncPosition() {
        guard let item = player.currentItem else { return }
        let seconds = CMTimeGetSeconds(item.currentTime())
        if seconds.isFinite, seconds >= 0 {
            elapsedMs = Int64(seconds * 1000)
        }
        let ahead = item.loadedTimeRanges
            .map { CMTimeGetSeconds($0.timeRangeValue.end) }
            .max() ?? 0
        if ahead.isFinite, seconds.isFinite {
            bufferedSeconds = max(0, Int(ahead - seconds))
        }
    }

    private func updateTicker() {
        if playing, ticker == nil {
            let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.syncPosition()
                    self.refreshCapabilities()
                    self.saveProgressIfNeeded(force: false)
                }
            }
            RunLoop.main.add(timer, forMode: .common)
            ticker = timer
        } else if !playing, let ticker {
            ticker.invalidate()
            self.ticker = nil
        }
    }

    private func configureSessionIfNeeded() {
        guard !sessionConfigured else { return }
        sessionConfigured = true
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
    }
}
