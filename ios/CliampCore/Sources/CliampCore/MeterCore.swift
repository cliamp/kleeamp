import Foundation

/// Pure attack/release smoothing plus a lagging peak cap, ported from the
/// Android `MeterCore`. It is the one source of the lit levels and peak rows
/// that every visualizer host draws.
///
/// Each live spectrum frame is `push`ed (or `pushIdle` when there is no
/// signal, `settle` when nothing is playing) and callers read `levels`/`peaks`
/// as 0...1 per column.
public final class MeterCore {
    public let columns: Int
    public private(set) var levels: [Float]
    public private(set) var peaks: [Float]

    private let attack: Float = 0.55
    private let release: Float = 0.14
    private let peakFall: Float = 0.010

    public init(columns: Int) {
        self.columns = max(1, columns)
        levels = Array(repeating: 0.05, count: self.columns)
        peaks = Array(repeating: 0.07, count: self.columns)
    }

    /// A fixed analyser band count feeds meters of any width; pooling here
    /// stops a narrow meter silently falling back to the idle animation.
    public func push(_ source: [Float]) {
        guard !source.isEmpty else { return }
        for i in 0..<columns {
            let target = min(max(band(source, i), 0), 1)
            let k = target > levels[i] ? attack : release
            levels[i] += (target - levels[i]) * k
            peaks[i] = levels[i] >= peaks[i]
                ? levels[i]
                : max(peaks[i] - peakFall, levels[i])
        }
    }

    private func band(_ source: [Float], _ index: Int) -> Float {
        if source.count == columns { return source[index] }
        if source.count < columns {
            let mapped = index * source.count / columns
            return source[min(max(mapped, 0), source.count - 1)]
        }
        let low = index * source.count / columns
        let high = max((index + 1) * source.count / columns, low + 1)
        var peak: Float = 0
        for k in low..<min(high, source.count) where source[k] > peak {
            peak = source[k]
        }
        return peak
    }

    /// Per-column variety comes from staggered period and phase, never from
    /// randomised colour.
    public func pushIdle(t: Double) {
        for i in 0..<columns {
            let period = 0.85 + Double(i % 7) * 0.11
            let phase = Double(i % 6) * 0.07
            let s = (sin(2 * .pi * (t / period + phase)) + 1) / 2
            let bias = 0.34 + 0.5 * Double((i * 37) % 13) / 13
            levels[i] = Float(min(max(0.12 + s * bias, 0), 0.96))
            peaks[i] = Float(min(max(Double(levels[i]) + 0.08, 0), 0.99))
        }
    }

    public func settle() {
        for i in 0..<columns {
            levels[i] = 0.04
            peaks[i] = 0.06
        }
    }
}

/// Folds linear FFT magnitudes into log-spaced bands on a dB scale. A linear
/// axis puts most of the columns above 5 kHz where music has almost no energy,
/// so each band covers a constant frequency ratio instead.
public enum SpectrumBands {
    /// - Parameters:
    ///   - magnitudes: the first half of the FFT, DC included.
    ///   - bands: how many columns the meter wants.
    ///   - scale: multiplies magnitudes so a full-scale sine lands near 1.
    ///   - dynamicRangeDb: how many dB below full scale the floor sits.
    public static func fold(
        magnitudes: [Float],
        bands: Int,
        scale: Float,
        dynamicRangeDb: Float
    ) -> [Float] {
        guard bands > 0 else { return [] }
        var out = [Float](repeating: 0, count: bands)
        out.withUnsafeMutableBufferPointer { buffer in
            fold(magnitudes: magnitudes, bands: bands, scale: scale, dynamicRangeDb: dynamicRangeDb, into: buffer)
        }
        return out
    }

    /// The allocation-free form used by the audio thread; `output` must hold
    /// exactly `bands` elements.
    public static func fold(
        magnitudes: [Float],
        bands: Int,
        scale: Float,
        dynamicRangeDb: Float,
        into output: UnsafeMutableBufferPointer<Float>
    ) {
        output.update(repeating: 0)
        guard bands > 0, output.count >= bands else { return }
        let bins = magnitudes.count
        guard bins >= 4 else { return }
        var low = 1
        for bandIndex in 0..<bands {
            let hi = Int(pow(Double(bins), Double(bandIndex + 1) / Double(bands)))
            let high = min(max(hi, low + 1), bins)
            var peak: Float = 0
            for bin in low..<high where magnitudes[bin] > peak {
                peak = magnitudes[bin]
            }
            let normalized = min(max(peak * scale, 1e-4), 1)
            let db = 20 * log10(normalized)
            output[bandIndex] = min(max((db + dynamicRangeDb) / dynamicRangeDb, 0), 1)
            low = high
        }
    }
}
