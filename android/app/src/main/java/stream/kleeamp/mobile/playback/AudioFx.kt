package stream.kleeamp.mobile.playback

import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Real EQ attached to the ExoPlayer audio session.
 *
 * Spectrum/waveform analysis no longer uses `android.media.audiofx.Visualizer`
 * (8-bit 1024-pt FFT, RECORD_AUDIO-gated, device-dependent capture rate).
 * All visualization now comes from [StereoMeterTap] → [PcmRing] →
 * [AudioAnalyzer]: real decoded PCM from the Media3 sink chain, 4096-pt FFT,
 * 64 log bands, ~30 Hz — source-agnostic across local, podcast, radio,
 * provider, SFTP and playlist sources, with no duration dependence.
 *
 * [attach] keeps its spectrum parameters for call-site compatibility but only
 * manages the EQ; spectrum liveness is owned by [AudioAnalyzer].
 */
class AudioFx(private val bands: Int = 64) {

    private var equalizer: Equalizer? = null
    private var sessionId = 0

    /**
     * Session whose effect creation already failed. Re-attempting every couple
     * of seconds on a device without an equalizer only burns main-thread time
     * and logs; a new audio session is the only thing that can change the
     * answer. Reset by [release] so a service restart tries once more.
     */
    private var failedSessionId = 0

    val bandLabels: List<String> get() = _bandLabels
    private var _bandLabels: List<String> = emptyList()

    /** 7 sliders in the UI mapped onto however many bands the device gives us. */
    val uiBands = listOf(60, 150, 400, 1_000, 3_000, 8_000, 16_000)

    /** Always false now; kept so callers need no flag-day. Analyzer owns liveness. */
    var spectrumLive: Boolean = false
        private set

    fun attach(
        audioSessionId: Int,
        @Suppress("UNUSED_PARAMETER") spectrumEnabled: Boolean,
        onSpectrum: (FloatArray) -> Unit = {},
        onWaveform: (FloatArray) -> Unit = {},
        onLiveChanged: (Boolean) -> Unit = {},
    ) {
        if (audioSessionId == 0) return
        if (audioSessionId == sessionId && equalizer != null) return
        if (audioSessionId == failedSessionId) return
        release()
        sessionId = audioSessionId

        runCatching {
            equalizer = Equalizer(0, audioSessionId).apply {
                // Pretend-off: DSP stays engaged, OFF means flat bands.
                // Flipping enabled=false re-routes the audio path on some
                // phones and pops/jumps. Flat is sonically off.
                enabled = true
                _bandLabels = (0 until numberOfBands).map { b ->
                    val hz = getCenterFreq(b.toShort()) / 1000
                    if (hz >= 1000) "${hz / 1000}k" else "$hz"
                }
            }
            failedSessionId = 0
        }.onFailure {
            failedSessionId = audioSessionId
            equalizer = null
            Log.w(TAG, "equalizer unavailable: ${it.message}")
        }
    }

    /**
     * Pretend-off: the DSP is never disabled (disabling re-routes audio and
     * pops on some phones). OFF is flat bands; ON restores the user curve.
     * The [on] flag is kept for call-site compat and always leaves the
     * effect enabled — callers must follow with [applyEffectiveBands].
     */
    fun setEqEnabled(@Suppress("UNUSED_PARAMETER") on: Boolean) {
        runCatching { equalizer?.enabled = true }
    }

    /** Slider values are -1..1; the device reports its own millibel range. */
    fun setBand(uiIndex: Int, value: Float) {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            val target = uiBands.getOrNull(uiIndex) ?: return
            val band = eq.getBand(target * 1000)
            if (band < 0) return
            val level = (((value + 1f) / 2f) * (max - min) + min).toInt().toShort()
            eq.setBandLevel(band, level)
        }
    }

    fun applyBands(values: List<Float>) = values.forEachIndexed { i, v -> setBand(i, v) }

    /** OFF applies [flat] with DSP still enabled; ON applies the user [bands]. */
    fun applyEffectiveBands(enabled: Boolean, bands: List<Float>, flat: List<Float> = EqPresets.flat) {
        runCatching { equalizer?.enabled = true }
        applyBands(if (enabled) bands else flat)
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        sessionId = 0
        failedSessionId = 0
        spectrumLive = false
    }

    private companion object {
        const val TAG = "kleeamp/fx"
    }
}

/**
 * The presets the concept names (FLAT / ROCK / HEADPHONE) plus the CLI's
 * sixteen, folded from its 10 bands (70Hz–16kHz, dB) onto these 7 by
 * nearest band (400Hz and 8kHz average their neighbours), scaled ÷12.
 */
object EqPresets {
    val flat = List(7) { 0f }
    val rock = listOf(0.42f, 0.33f, 0.04f, -0.17f, 0.17f, 0.38f, 0.42f)
    val pop = listOf(-0.08f, 0.17f, 0.38f, 0.33f, 0.08f, -0.08f, 0.17f)
    val jazz = listOf(0.25f, 0.33f, 0.12f, -0.08f, -0.08f, 0.12f, 0.33f)
    val classical = listOf(0.25f, 0.17f, 0.04f, -0.08f, -0.08f, 0.08f, 0.33f)
    val bass = listOf(0.67f, 0.5f, 0.25f, 0f, 0f, 0f, 0f)
    val treble = listOf(0f, 0f, 0f, 0f, 0.08f, 0.33f, 0.58f)
    val vocal = listOf(-0.17f, -0.08f, 0.21f, 0.42f, 0.33f, 0.08f, -0.17f)
    val electronic = listOf(0.5f, 0.33f, 0f, -0.17f, 0.08f, 0.29f, 0.5f)
    val acoustic = listOf(0.25f, 0.25f, 0.08f, 0.08f, 0.17f, 0.25f, 0.08f)
    val hiphop = listOf(0.58f, 0.42f, 0.17f, -0.08f, -0.08f, 0.17f, 0.25f)
    val rnb = listOf(0.33f, 0.5f, 0.17f, -0.08f, 0.08f, 0.17f, 0f)
    val loudness = listOf(0.5f, 0.33f, 0.04f, -0.17f, -0.08f, 0.21f, 0.42f)
    val latenight = listOf(0.42f, 0.25f, 0.04f, -0.17f, -0.08f, 0.08f, 0.25f)
    val podcast = listOf(-0.25f, -0.08f, 0.25f, 0.33f, 0.25f, 0f, -0.25f)
    val speakers = listOf(0.58f, 0.42f, 0.25f, 0.08f, 0f, -0.04f, 0.17f)
    val headphone = listOf(0.35f, 0.18f, 0f, 0.1f, 0.16f, 0.3f, 0.45f)

    val names = listOf(
        "flat", "rock", "pop", "jazz", "classical", "bass", "treble",
        "vocal", "electronic", "acoustic", "hip-hop", "r&b", "loudness",
        "late night", "podcast", "speakers", "headphone",
    )

    private val byNameMap = mapOf(
        "flat" to flat,
        "rock" to rock,
        "pop" to pop,
        "jazz" to jazz,
        "classical" to classical,
        "bass" to bass,
        "treble" to treble,
        "vocal" to vocal,
        "electronic" to electronic,
        "acoustic" to acoustic,
        "hip-hop" to hiphop,
        "r&b" to rnb,
        "loudness" to loudness,
        "late night" to latenight,
        "podcast" to podcast,
        "speakers" to speakers,
        "headphone" to headphone,
    )

    fun byName(name: String): List<Float>? = byNameMap[name]
}
