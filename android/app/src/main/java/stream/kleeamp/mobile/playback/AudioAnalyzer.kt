package stream.kleeamp.mobile.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Centralized audio analysis engine — the Kotlin equivalent of CLIamp's
 * `ui.Visualizer.Analyze` + per-mode `AnalysisSpec`.
 *
 * Pipeline (all from the SAME decoded PCM that produces sound):
 *
 * ```
 * Media3 decoded PCM (TeeAudioProcessor tap, all sources)
 *   → PcmRing (mono mix, lock-free ring)
 *   → Hann window → radix-2 FFT (4096, ported from cliamp ui/fft.go)
 *   → power spectrum → log bands 20 Hz–20 kHz → dB normalize 0..1
 *   → silence gate → attack/release smoothing (0.6 / 0.25)
 *   → PlaybackBus.spectrum (64 bands) + PlaybackBus.waveform (time domain)
 *   → Compose Canvas renderers (60 fps read latest frame)
 * ```
 *
 * Stereo levels arrive on the same tap via [StereoCore.metrics] (real L/R
 * RMS + peak, -48 dB floor) and ride [PlaybackBus.stereo]; this engine does
 * not re-derive stereo from FFT bands.
 *
 * Cadence follows CLIamp: expensive FFT work at ~30 Hz ([ANALYZE_MS]),
 * animation at display rate by reading the latest published frame. All
 * buffers are preallocated; the loop allocates nothing per iteration.
 */
class AudioAnalyzer(
    private val bands: Int = SPECTRUM_BANDS,
    private val fftSize: Int = FFT_SIZE,
) {
    private var job: Job? = null

    // Reusable buffers — never reallocated after construction.
    private val window = FloatArray(fftSize)
    private val windowBuf = FloatArray(fftSize)
    private val sampleBuf = FloatArray(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val powers = DoubleArray(fftSize / 2)
    private val edges = DoubleArray(bands + 1)
    private val prev = FloatArray(bands)
    private val out = FloatArray(bands)
    private val waveScratch = FloatArray(WAVEFORM_SIZE)

    private var lastWritten = -1L
    private var lastFftMs = 0L

    /**
     * Wall-clock wave cursor: absolute ring position the waveform window
     * ends at. Advanced by elapsed time every wave tick (never past what
     * was written), so raw modes slide continuously at 60 Hz exactly like
     * cliamp's wall-clock-anchored WaveformSamplesInto — even when the
     * decoder delivers PCM in bursts with quiet gaps between them.
     */
    private var waveEnd = -1L
    private var lastWaveNs = 0L

    init {
        buildHann()
        buildLogEdges()
    }

    /**
     * Start the analysis loop. Idempotent. Two cadences like cliamp:
     * waveform at ~60 Hz (raw modes sample every TickWave tick) and the
     * expensive FFT at ~30 Hz (TickAnalyze). Both publish without
     * allocating beyond the copied output arrays.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            lastWaveNs = System.nanoTime()
            while (isActive) {
                publishWave()
                val now = System.currentTimeMillis()
                if (now - lastFftMs >= ANALYZE_MS) {
                    analyzeSpectrum()
                    lastFftMs = now
                }
                delay(WAVE_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Clear smoothing state for a new source (station/track change). Called
     * on media-item transition so stale peaks never carry over. ICY/stream-
     * title changes do NOT call this — animation continues.
     *
     * The PCM ring is deliberately not cleared here: it is written by the
     * audio thread, and rewinding its cursor from the main thread can erase
     * the first samples of the new track. Resetting the read cursors below
     * makes the analyzer re-anchor on the newest audio instead.
     */
    fun reset() {
        prev.fill(0f)
        out.fill(0f)
        lastWritten = -1L
        waveEnd = -1L
        lastWaveNs = System.nanoTime()
        PlaybackBus.publishSpectrum(FloatArray(bands))
        PlaybackBus.publishWaveform(FloatArray(WAVEFORM_SIZE))
        PlaybackBus.publishGeneration(PlaybackBus.generation.value + 1)
    }

    /** One analysis step: read latest PCM, FFT, publish. Pure + testable. */
    fun analyzeSamples(samples: FloatArray, count: Int, sampleRate: Int): FloatArray {
        val have = count.coerceAtMost(samples.size)
        if (have <= 0) {
            decaySilence()
            return out
        }
        var maxAbs = 0f
        for (i in 0 until have) {
            val a = abs(samples[i])
            if (a > maxAbs) maxAbs = a
        }
        if (maxAbs < SILENCE_GATE) {
            decaySilence()
            return out
        }
        // Window the latest fftSize samples (zero-pad short history).
        windowBuf.fill(0f)
        val copy = min(have, fftSize)
        samples.copyInto(windowBuf, fftSize - copy, have - copy, have)
        for (i in 0 until fftSize) windowBuf[i] *= window[i]

        fft(windowBuf, re, im)
        val half = fftSize / 2
        powers[0] = 0.0
        for (i in 1 until half) powers[i] = re[i] * re[i] + im[i] * im[i]

        val binHz = sampleRate.toDouble() / fftSize.toDouble()
        for (b in 0 until bands) {
            val sum = averageRange(powers, edges[b] / binHz, edges[b + 1] / binHz)
            var v = 0f
            if (sum > 0) v = ((10 * log10(sum) + 10) / 50).toFloat()
            v = v.coerceIn(0f, 1f)
            val k = if (v > prev[b]) RISE_BLEND else FALL_BLEND
            v = prev[b] + (v - prev[b]) * k
            prev[b] = v
            out[b] = v
        }
        return out
    }

    /**
     * Wave tick (~60 Hz): slide the window forward by elapsed wall-clock
     * time, clamped to what was actually written. Fresh PCM pulls the
     * cursor along; a stall parks it — the trace holds instead of jumping.
     */
    fun publishWave() {
        val written = PcmRing.written()
        val now = System.nanoTime()
        val sr = PcmRing.sampleRateHz.takeIf { it > 0 } ?: 44_100
        if (waveEnd < 0) {
            waveEnd = written
            lastWaveNs = now
        } else {
            val dtSec = ((now - lastWaveNs).coerceAtLeast(0L)) / 1_000_000_000.0
            lastWaveNs = now
            if (dtSec > 0) {
                waveEnd = minOf(written, waveEnd + (dtSec * sr).toLong())
            }
        }
        PcmRing.readWindow(waveEnd, waveScratch, WAVEFORM_SIZE)
        PlaybackBus.publishWaveform(waveScratch.copyOf())
        if (written > 0 && !PlaybackBus.spectrumLive.value) {
            PlaybackBus.publishSpectrumLive(true)
        }
    }

    private fun analyzeSpectrum() {
        val written = PcmRing.written()
        val have = PcmRing.readLatest(sampleBuf, fftSize)
        if (written == lastWritten && written >= 0) {
            // No new PCM (paused/buffering): decay toward silence so meters
            // settle naturally instead of freezing mid-peak.
            decaySilence()
            PlaybackBus.publishSpectrum(out.copyOf())
            return
        }
        lastWritten = written
        val sr = PcmRing.sampleRateHz.takeIf { it > 0 } ?: 44_100
        analyzeSamples(sampleBuf, have, sr)
        PlaybackBus.publishSpectrum(out.copyOf())
        if (!PlaybackBus.spectrumLive.value) PlaybackBus.publishSpectrumLive(true)
    }

    private fun decaySilence() {
        for (b in 0 until bands) {
            val v = prev[b] * SILENCE_DECAY
            prev[b] = v
            out[b] = v
        }
    }

    private fun buildHann() {
        for (i in 0 until fftSize) {
            window[i] = (0.5 * (1 - cos(2 * PI * i / fftSize))).toFloat()
        }
    }

    private fun buildLogEdges() {
        val lo = log10(MIN_HZ)
        val hi = log10(MAX_HZ)
        for (i in 0..bands) edges[i] = Math.pow(10.0, lo + (hi - lo) * i / bands)
    }

    companion object {
        const val SPECTRUM_BANDS = 64

        /** ClassicPeak-grade FFT: 4096 like cliamp's classicPeakFFTSize. */
        const val FFT_SIZE = 4096

        /** ~30 Hz FFT cadence (cliamp TickAnalyze = 33 ms). */
        const val ANALYZE_MS = 33L

        /** ~60 Hz waveform cadence (cliamp TickWave = TickAnim = 16 ms). */
        const val WAVE_MS = 16L

        const val WAVEFORM_SIZE = 1024

        private const val MIN_HZ = 20.0
        private const val MAX_HZ = 20_000.0
        private const val SILENCE_GATE = 1e-5f
        private const val SILENCE_DECAY = 0.8f
        private const val RISE_BLEND = 0.6f
        private const val FALL_BLEND = 0.25f

        /**
         * Average power over a fractional bin range — mirrors CLIamp's
         * averageSpectrumRangeLinear (4–32 samples per band).
         */
        fun averageRange(powers: DoubleArray, loBin: Double, hiBin: Double): Double {
            val lo = loBin.coerceAtLeast(0.0)
            val hi = hiBin.coerceAtMost(powers.size.toDouble()).coerceAtLeast(lo)
            val i0 = lo.toInt().coerceIn(0, powers.size)
            val i1 = hi.toInt().coerceIn(0, powers.size)
            if (i1 <= i0) {
                val idx = i0.coerceIn(0, powers.size - 1)
                return powers[idx]
            }
            var sum = 0.0
            var weight = 0.0
            for (i in i0 until i1) {
                val wLo = maxOf(lo, i.toDouble())
                val wHi = minOf(hi, (i + 1).toDouble())
                val w = (wHi - wLo).coerceAtLeast(0.0)
                sum += powers[i] * w
                weight += w
            }
            return if (weight > 0) sum / weight else 0.0
        }

        /**
         * In-place radix-2 Cooley-Tukey FFT on real input — a direct port of
         * cliamp's `ui/fft.go` (bit-reversal + butterfly stages, no allocs
         * beyond the caller-owned [re]/[im] buffers).
         */
        fun fft(input: FloatArray, re: DoubleArray, im: DoubleArray) {
            val n = input.size
            require(n == re.size && n == im.size) { "buffer mismatch" }
            require(n >= 2 && n and (n - 1) == 0) { "fft size must be power of 2" }
            for (i in 0 until n) {
                re[i] = input[i].toDouble()
                im[i] = 0.0
            }
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var size = 2
            while (size <= n) {
                val half = size shr 1
                val step = n / size
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val angle = -2 * PI * (k * step) / n
                        val wr = cos(angle)
                        val wi = sin(angle)
                        val tr = wr * re[start + k + half] - wi * im[start + k + half]
                        val ti = wr * im[start + k + half] + wi * re[start + k + half]
                        val ur = re[start + k]
                        val ui = im[start + k]
                        re[start + k] = ur + tr
                        im[start + k] = ui + ti
                        re[start + k + half] = ur - tr
                        im[start + k + half] = ui - ti
                    }
                    start += size
                }
                size = size shl 1
            }
        }
    }
}
