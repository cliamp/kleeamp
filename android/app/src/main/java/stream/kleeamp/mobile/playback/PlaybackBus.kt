package stream.kleeamp.mobile.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.player.vis.StereoMetrics

/**
 * The service and the UI live in the same process, so rather than round-trip
 * the spectrum through a MediaSession custom command sixty times a second we
 * publish it on a plain in-process bus. Transport control still goes through
 * MediaController, which is what keeps the lockscreen and Bluetooth honest.
 */
object PlaybackBus {
    private val _spectrum = MutableStateFlow(FloatArray(0))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    /** False means the columns are synthesised, not measured. */
    private val _spectrumLive = MutableStateFlow(false)
    val spectrumLive: StateFlow<Boolean> = _spectrumLive.asStateFlow()

    /**
     * Raw time-domain samples for the wave oscilloscope, -1..1. The FFT
     * cannot produce these, so they ride their own Visualizer tap. Nobody
     * collects this flow: frames read the current value directly in their
     * tick loop, costing no recomposition.
     */
    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    private val _stereo = MutableStateFlow(StereoMetrics.silent)
    val stereo: StateFlow<StereoMetrics> = _stereo.asStateFlow()

    private val _streamTitle = MutableStateFlow("")
    val streamTitle: StateFlow<String> = _streamTitle.asStateFlow()

    private val _station = MutableStateFlow<Station?>(null)
    val station: StateFlow<Station?> = _station.asStateFlow()

    /**
     * The full play order the current station comes from (local songs, the
     * favourites list, a provider album, a radio directory…). The lockscreen
     * prev/next buttons walk this so they advance through whatever list is
     * actually playing, not a fixed radio list.
     */
    private val _source = MutableStateFlow<List<Station>>(emptyList())
    val source: StateFlow<List<Station>> = _source.asStateFlow()

    private val _format = MutableStateFlow(StreamFormat())
    val format: StateFlow<StreamFormat> = _format.asStateFlow()

    /** Non-zero while a dropped stream is being retried; the value is the attempt. */
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Counts user queue adds: each [publishQueued] bumps it, so observers
     * see every add distinctly even back-to-back. The root pill hosts the
     * "Added to Up Next" confirmation off this.
     */
    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    fun publishSpectrum(v: FloatArray) { _spectrum.value = v }
    fun publishSpectrumLive(v: Boolean) { _spectrumLive.value = v }
    fun publishWaveform(v: FloatArray) { _waveform.value = v }
    fun publishStereo(v: StereoMetrics) { _stereo.value = v }
    fun publishStreamTitle(v: String) { _streamTitle.value = v }
    fun publishStation(v: Station?) { _station.value = v; if (v != null) _streamTitle.value = "" }
    fun publishSource(v: List<Station>) { _source.value = v }
    fun publishFormat(v: StreamFormat) { _format.value = v }
    fun publishError(v: String?) { _error.value = v }
    fun publishReconnect(attempt: Int) { _reconnectAttempt.value = attempt }
    fun publishQueued() { _queuedCount.value += 1 }
}

data class StreamFormat(
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val codec: String = "",
)
