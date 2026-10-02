import Accelerate
import AVFoundation
import CliampCore
import Foundation
import Synchronization
import os

/// Real playback FFT from AVPlayer's decoded output. A post-effects
/// `MTAudioProcessingTap` hands over the PCM that is actually playing, vDSP
/// folds it into 64 log-spaced bands, and `SpectrumStore` publishes the most
/// recent frame for the meters. No synthetic animation is ever published.
final class SpectrumTap {
    static let bandCount = 64
    private static let log2n = vDSP_Length(10)
    private static let fftSize = 1 << 10

    private let store: SpectrumStore
    private let fft: vDSP.FFT<DSPSplitComplex>?
    private let window: [Float]
    private var ring: [Float]
    private var writeIndex = 0
    private var samplesSinceTransform = 0
    private var splitReal: [Float]
    private var splitImag: [Float]
    private var magnitudes: [Float]
    private var windowed: [Float]
    private var bands: [Float]
    private var format: AudioStreamBasicDescription?
    private var formatSupported = false
    private let logger = Logger(subsystem: "stream.cliamp.mobile", category: "spectrum")

    init(store: SpectrumStore) {
        self.store = store
        fft = vDSP.FFT(log2n: Self.log2n, radix: .radix2, ofType: DSPSplitComplex.self)
        var hann = [Float](repeating: 0, count: Self.fftSize)
        vDSP_hann_window(&hann, vDSP_Length(Self.fftSize), Int32(vDSP_HANN_DENORM))
        window = hann
        ring = [Float](repeating: 0, count: Self.fftSize)
        splitReal = [Float](repeating: 0, count: Self.fftSize / 2)
        splitImag = [Float](repeating: 0, count: Self.fftSize / 2)
        magnitudes = [Float](repeating: 0, count: Self.fftSize / 2)
        windowed = [Float](repeating: 0, count: Self.fftSize)
        bands = [Float](repeating: 0, count: Self.bandCount)
    }

    func makeProcessingTap() -> MTAudioProcessingTap? {
        // The tap keeps a raw pointer to this processor for the callbacks;
        // retain here and release in finalize so station switches can never
        // leave a callback reading a deallocated object.
        let storage = Unmanaged.passRetained(self).toOpaque()
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: storage,
            init: spectrumTapInit,
            finalize: spectrumTapFinalize,
            prepare: spectrumTapPrepare,
            unprepare: spectrumTapUnprepare,
            process: spectrumTapProcess
        )
        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap
        )
        guard status == noErr else {
            Unmanaged<SpectrumTap>.fromOpaque(storage).release()
            logger.error("tap create failed: \(status, privacy: .public)")
            return nil
        }
        return tap
    }

    fileprivate func prepare(_ format: UnsafePointer<AudioStreamBasicDescription>) {
        self.format = format.pointee
        let asbd = format.pointee
        // Only 32-bit float PCM is interpreted; anything else leaves the
        // meters on their idle stagger instead of misreading samples.
        formatSupported = asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0
            && asbd.mBitsPerChannel == 32
        logger.info(
            "tap prepared: \(asbd.mSampleRate, privacy: .public) Hz, \(asbd.mChannelsPerFrame, privacy: .public) ch, bits \(asbd.mBitsPerChannel, privacy: .public), supported \(self.formatSupported, privacy: .public)"
        )
    }

    fileprivate func process(_ bufferList: UnsafeMutablePointer<AudioBufferList>, frames: Int) {
        guard formatSupported, let asbd = format, frames > 0 else { return }
        let channels = max(1, Int(asbd.mChannelsPerFrame))
        let interleaved = asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0
        let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
        guard let firstBuffer = buffers.first, let firstData = firstBuffer.mData else { return }
        let firstCount = min(frames, Int(firstBuffer.mDataByteSize) / MemoryLayout<Float>.size)
        guard firstCount > 0, channels > 0 else { return }
        let first = firstData.assumingMemoryBound(to: Float.self)
        // Average the front channels so panning never moves the meter, in
        // both the planar and interleaved layouts.
        let second: UnsafeMutablePointer<Float>? = if !interleaved, buffers.count > 1,
            let data = buffers[1].mData
        {
            data.assumingMemoryBound(to: Float.self)
        } else {
            nil
        }
        let hasSecondInterleaved = interleaved && channels > 1

        for frame in 0..<firstCount {
            let value: Float
            if interleaved {
                if hasSecondInterleaved {
                    value = (first[frame * channels] + first[frame * channels + 1]) * 0.5
                } else {
                    value = first[frame * channels]
                }
            } else if let second {
                value = (first[frame] + second[frame]) * 0.5
            } else {
                value = first[frame]
            }
            ring[writeIndex] = value
            writeIndex = (writeIndex + 1) % Self.fftSize
            samplesSinceTransform += 1

            if samplesSinceTransform >= Self.fftSize / 2 {
                samplesSinceTransform = 0
                transform()
            }
        }
    }

    private func transform() {
        for i in 0..<Self.fftSize {
            windowed[i] = ring[(writeIndex + i) % Self.fftSize] * window[i]
        }
        windowed.withUnsafeBufferPointer { samples in
            splitReal.withUnsafeMutableBufferPointer { real in
                splitImag.withUnsafeMutableBufferPointer { imag in
                    magnitudes.withUnsafeMutableBufferPointer { magnitude in
                        var split = DSPSplitComplex(
                            realp: real.baseAddress!,
                            imagp: imag.baseAddress!
                        )
                        samples.baseAddress!.withMemoryRebound(
                            to: DSPComplex.self, capacity: Self.fftSize / 2
                        ) { complex in
                            vDSP_ctoz(complex, 2, &split, 1, vDSP_Length(Self.fftSize / 2))
                        }
                        fft?.forward(input: split, output: &split)
                        vDSP_zvabs(
                            &split, 1, magnitude.baseAddress!, 1,
                            vDSP_Length(Self.fftSize / 2)
                        )
                    }
                }
            }
        }
        // vDSP's real forward transform doubles the magnitudes, so a Hann
        // windowed full-scale sine peaks at N/2; 2/N references it, and no
        // allocation or lock is taken on this audio thread.
        bands.withUnsafeMutableBufferPointer { output in
            SpectrumBands.fold(
                magnitudes: magnitudes,
                bands: Self.bandCount,
                scale: 2 / Float(Self.fftSize),
                dynamicRangeDb: 54,
                into: output
            )
        }
        store.publish(bands)
    }
}

/// Handoff from the audio thread to the meters. The audio thread publishes
/// with a non-blocking lock: if the main thread is mid-copy the frame is
/// dropped rather than stalling playback.
final class SpectrumStore: Sendable {
    private struct Snapshot {
        var bands: [Float]
        var live: Bool
    }

    private let state: Mutex<Snapshot>

    init(bandCount: Int = SpectrumTap.bandCount) {
        state = Mutex(Snapshot(bands: [Float](repeating: 0, count: bandCount), live: false))
    }

    func publish(_ bands: [Float]) {
        _ = state.withLockIfAvailable { snapshot in
            snapshot.bands.withUnsafeMutableBufferPointer { destination in
                bands.withUnsafeBufferPointer { source in
                    let count = min(destination.count, source.count)
                    if count > 0 {
                        destination.baseAddress!.update(from: source.baseAddress!, count: count)
                    }
                }
            }
            snapshot.live = true
        }
    }

    func latestBands() -> [Float]? {
        state.withLock { $0.live ? Array($0.bands) : nil }
    }

    func clear() {
        state.withLock { snapshot in
            for index in snapshot.bands.indices {
                snapshot.bands[index] = 0
            }
            snapshot.live = false
        }
    }
}

private let spectrumTapInit: MTAudioProcessingTapInitCallback = { _, clientInfo, storageOut in
    storageOut.pointee = clientInfo
}

private let spectrumTapFinalize: MTAudioProcessingTapFinalizeCallback = { tap in
    Unmanaged<SpectrumTap>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
}

private let spectrumTapPrepare: MTAudioProcessingTapPrepareCallback = { tap, _, format in
    let processor = Unmanaged<SpectrumTap>
        .fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
    processor.prepare(format)
}

private let spectrumTapUnprepare: MTAudioProcessingTapUnprepareCallback = { _ in }

private let spectrumTapProcess: MTAudioProcessingTapProcessCallback = {
    tap, numberFrames, _, bufferList, framesOut, flagsOut in
    let status = MTAudioProcessingTapGetSourceAudio(
        tap, numberFrames, bufferList, flagsOut, nil, framesOut
    )
    guard status == noErr else { return }
    let processor = Unmanaged<SpectrumTap>
        .fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
    processor.process(bufferList, frames: Int(framesOut.pointee))
}
