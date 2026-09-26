import Testing

@testable import CliampCore

@Suite("meter core")
struct MeterCoreTests {
    @Test("attack rises toward the source and leaves the peak up")
    func attack() {
        let core = MeterCore(columns: 2)
        core.push([1, 0.5])
        // 0.05 + (1 - 0.05) * 0.55
        #expect(abs(core.levels[0] - 0.5725) < 0.001)
        #expect(abs(core.peaks[0] - 0.5725) < 0.001)
    }

    @Test("release falls slower than attack and the peak lags")
    func release() {
        let core = MeterCore(columns: 1)
        core.push([1])
        let lit = core.levels[0]
        core.push([0])
        #expect(core.levels[0] < lit)
        #expect(abs(core.levels[0] - lit * (1 - 0.14)) < 0.001)
        #expect(core.peaks[0] > core.levels[0])
    }

    @Test("peak caps fall at a fixed rate and never below the level")
    func peakFall() {
        let core = MeterCore(columns: 1)
        core.push([1])
        let top = core.peaks[0]
        for _ in 0..<5 { core.push([0]) }
        #expect(abs(core.peaks[0] - (top - 0.05)) < 0.001)
        #expect(core.peaks[0] >= core.levels[0])
    }

    @Test("a wider source pools by peak, a narrower one upsamples")
    func pooling() {
        let down = MeterCore(columns: 2)
        down.push([0.1, 0.9, 0.2, 0.3])
        #expect(abs(down.levels[0] - (0.05 + (0.9 - 0.05) * 0.55)) < 0.001)

        let up = MeterCore(columns: 4)
        up.push([0.5, 1])
        #expect(up.levels[2] > 0)
    }

    @Test("idle follows a bounded stagger, settle returns to rest")
    func idleAndSettle() {
        let core = MeterCore(columns: 8)
        core.pushIdle(t: 0.4)
        #expect(core.levels.allSatisfy { $0 >= 0 && $0 <= 0.96 })
        core.settle()
        #expect(core.levels.allSatisfy { $0 == 0.04 })
        #expect(core.peaks.allSatisfy { $0 == 0.06 })
    }
}

@Suite("spectrum folding")
struct SpectrumBandsTests {
    @Test("bands bucket bins geometrically")
    func logBuckets() {
        var magnitudes = [Float](repeating: 0, count: 8)
        magnitudes[1] = 1
        let bands = SpectrumBands.fold(magnitudes: magnitudes, bands: 2, scale: 1, dynamicRangeDb: 48)
        #expect(bands[0] == 1)
        #expect(bands[1] == 0)
    }

    @Test("a bin in the upper range lights the upper band")
    func upperBand() {
        var magnitudes = [Float](repeating: 0, count: 8)
        magnitudes[5] = 1
        let bands = SpectrumBands.fold(magnitudes: magnitudes, bands: 2, scale: 1, dynamicRangeDb: 48)
        #expect(bands[0] == 0)
        #expect(bands[1] == 1)
    }

    @Test("scale maps full scale to one and silence to zero")
    func scaling() {
        var magnitudes = [Float](repeating: 0, count: 8)
        magnitudes[7] = 0.25
        let quiet = SpectrumBands.fold(magnitudes: magnitudes, bands: 1, scale: 4, dynamicRangeDb: 48)
        #expect(quiet[0] == 1)
        let silent = SpectrumBands.fold(magnitudes: [Float](repeating: 0, count: 8), bands: 1, scale: 4, dynamicRangeDb: 48)
        #expect(silent[0] == 0)
    }

    @Test("degenerate input stays safe")
    func degenerate() {
        #expect(SpectrumBands.fold(magnitudes: [], bands: 4, scale: 1, dynamicRangeDb: 48) == [0, 0, 0, 0])
        #expect(SpectrumBands.fold(magnitudes: [1, 2], bands: 4, scale: 1, dynamicRangeDb: 48) == [0, 0, 0, 0])
        #expect(SpectrumBands.fold(magnitudes: [1, 2, 3, 4], bands: 0, scale: 1, dynamicRangeDb: 48) == [])
    }
}
