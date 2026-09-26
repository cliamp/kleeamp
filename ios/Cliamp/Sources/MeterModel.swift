import CliampCore
import CliampDesign
import QuartzCore

/// Drives one brick meter at display rate: the real spectrum when the tap has
/// frames, the Android idle stagger when a stream plays without any, and a
/// settle when playback stops.
@MainActor
@Observable
final class MeterModel {
    private let core: MeterCore
    private let player: RadioPlayer
    private var link: CADisplayLink?
    private let linkTarget = DisplayLinkTarget()
    private var idleStart = CACurrentMediaTime()
    private var wasPlaying = false

    private(set) var levels: [Float]
    private(set) var peaks: [Float]

    init(preset: MeterPreset, player: RadioPlayer) {
        core = MeterCore(columns: preset.columns)
        self.player = player
        levels = core.levels
        peaks = core.peaks
        linkTarget.model = self
    }

    func start() {
        guard link == nil else { return }
        idleStart = CACurrentMediaTime()
        let link = CADisplayLink(target: linkTarget, selector: #selector(DisplayLinkTarget.tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
        core.settle()
        levels = core.levels
        peaks = core.peaks
    }

    fileprivate func tick() {
        let playing = player.playing
        if playing {
            if !wasPlaying { idleStart = CACurrentMediaTime() }
            if let real = player.spectrum.latestBands(), !real.isEmpty {
                core.push(real)
            } else {
                core.pushIdle(t: CACurrentMediaTime() - idleStart)
            }
            levels = core.levels
            peaks = core.peaks
        } else if wasPlaying {
            core.settle()
            levels = core.levels
            peaks = core.peaks
        }
        wasPlaying = playing
    }
}

@MainActor
private final class DisplayLinkTarget: NSObject {
    weak var model: MeterModel?

    @objc func tick() {
        model?.tick()
    }
}
