package stream.kleeamp.mobile.lyrics

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerConnection

/**
 * Synced lyrics for what is audible. The lookup follows station + stream
 * title (a radio title change is a new song), the active line follows a
 * smoothed player clock plus the persisted offset, and a track change
 * cancels an in-flight fetch rather than stamping stale lines.
 *
 * The clock is smoothed because the transport only reports a position twice a
 * second: extrapolating between samples makes a line turn on time instead of
 * up to half a second late.
 */
@UnstableApi
class LyricsViewModel(
    val player: PlayerConnection,
    private val prefs: Prefs,
    private val repo: LyricsRepository,
) : ViewModel() {

    sealed interface Status {
        data object Loading : Status
        /** Nothing to search: idle, or a stream naming no title. */
        data object NoSong : Status
        /** Searched; every source answered that it has nothing. */
        data object NotFound : Status
        data object Instrumental : Status
        /** The lookup failed; retryable. */
        data object Offline : Status
        /** Cellular is off on a metered network, so nothing was sent. */
        data object Restricted : Status
        data object Ready : Status
    }

    data class UiState(
        val lines: List<LrcLine> = emptyList(),
        val plain: String = "",
        val activeIndex: Int = -1,
        /** The song the states above describe; null while there is none. */
        val query: LyricsQuery? = null,
        val status: Status = Status.Loading,
        val offsetMs: Int = 0,
    )

    private val _result =
        MutableStateFlow<LyricsRepository.Result>(LyricsRepository.Result.NotFound)
    private val _loading = MutableStateFlow(true)
    private val _query = MutableStateFlow<LyricsQuery?>(null)
    private val _activeIndex = MutableStateFlow(-1)
    private val _offset = MutableStateFlow(0)

    /** True only while the lyrics page is on screen: the one lookup trigger. */
    private val open = MutableStateFlow(false)

    /** The song [result][_result] belongs to, so a track change can drop it. */
    private var fetchedFor: LyricsQuery? = null

    private var ticker: Job? = null

    /** The pending debounced offset write; one per settled gesture. */
    private var offsetWrite: Job? = null

    val state: StateFlow<UiState> = combine(
        _result,
        _loading,
        _query,
        _activeIndex,
        _offset,
    ) { result, loading, query, active, offset ->
        val (lines, plain, status) = when {
            loading -> Triple(emptyList(), "", Status.Loading)
            query == null -> Triple(emptyList(), "", Status.NoSong)
            result is LyricsRepository.Result.Lines ->
                Triple(result.lines, result.plain, Status.Ready)
            result is LyricsRepository.Result.Instrumental ->
                Triple(emptyList(), "", Status.Instrumental)
            result is LyricsRepository.Result.Offline ->
                Triple(emptyList(), "", Status.Offline)
            result is LyricsRepository.Result.Restricted ->
                Triple(emptyList(), "", Status.Restricted)
            else -> Triple(emptyList(), "", Status.NotFound)
        }
        UiState(
            lines = lines,
            plain = plain,
            activeIndex = active,
            query = query,
            status = status,
            offsetMs = offset,
        )
    }.stateInUi(viewModelScope, UiState())

    init {
        observe()
    }

    /**
     * One watcher for the life of the process, so it can follow a track
     * change while the player is closed: the stale result is dropped and the
     * badge dims. Lookups run only between [start] and [stop] - the player
     * page drives them, so a dismissed player never keeps hitting the network
     * for every later track.
     */
    private fun observe() {
        viewModelScope.launch {
            prefs.lyricsOffset.collect { _offset.value = it }
        }
        viewModelScope.launch {
            combine(PlaybackBus.station, PlaybackBus.streamTitle, open) { station, title, visible ->
                station?.let { lyricsQuery(it, title) } to visible
            }
                .distinctUntilChanged()
                .collectLatest { (query, visible) ->
                    _query.value = query
                    when {
                        !visible -> {
                            _loading.value = false
                            // Keep a result that belongs to this song so the
                            // badge stays lit; drop one from a past track.
                            if (query != fetchedFor) {
                                _result.value = LyricsRepository.Result.NotFound
                                _activeIndex.value = -1
                            }
                        }
                        query == null -> {
                            _loading.value = false
                            _result.value = LyricsRepository.Result.NotFound
                        }
                        else -> {
                            // Already fetched for this exact song: reopening
                            // the player must not dim the key again. Anything
                            // else (absence, offline, restricted) retries.
                            val have = query == fetchedFor && hasResult()
                            if (!have) {
                                _loading.value = true
                                _result.value = fetchWithRetry(query)
                                fetchedFor = query
                                _loading.value = false
                            }
                        }
                    }
                }
        }
    }

    private fun hasResult(): Boolean =
        _result.value is LyricsRepository.Result.Lines ||
            _result.value is LyricsRepository.Result.Instrumental

    /** The player page is visible: follow the track and look up its lyrics. */
    fun start() {
        if (open.value) return
        open.value = true
        ticker = viewModelScope.launch { tick() }
    }

    /** The player page is gone: stop the clock and the lookups until [start]. */
    fun stop() {
        open.value = false
        ticker?.cancel()
        ticker = null
        _activeIndex.value = -1
    }

    /**
     * Extrapolates the clock between the transport's half-second samples. A
     * new sample (seek, pause, track change) re-bases; otherwise the elapsed
     * time at playback speed is added. Line turns are driven from here, so
     * only a change of line touches [UiState].
     */
    private suspend fun tick() {
        var baseMs = player.state.value.positionMs
        var baseAt = SystemClock.elapsedRealtime()
        var lastSeen = baseMs
        while (true) {
            delay(TICK_MS)
            val transport = player.state.value
            val now = SystemClock.elapsedRealtime()
            if (transport.positionMs != lastSeen || !transport.playing) {
                lastSeen = transport.positionMs
                baseMs = transport.positionMs
                baseAt = now
            }
            val raw = if (transport.playing) {
                baseMs + ((now - baseAt) * transport.speed).toLong()
            } else {
                baseMs
            }
            val cursor = raw + _offset.value
            val lines = (_result.value as? LyricsRepository.Result.Lines)?.lines.orEmpty()
            _activeIndex.value = activeLineIndex(lines, cursor)
        }
    }

    /**
     * Offline is transient far more often than it is real, so a failed
     * lookup retries itself with backoff instead of parking on the offline
     * note until the user taps retry. Bounded: absence stays absence, and a
     * track change cancels the whole loop through collectLatest.
     */
    private suspend fun fetchWithRetry(query: LyricsQuery): LyricsRepository.Result {
        var result = repo.fetch(query)
        for (pause in AUTO_RETRY_PAUSES_MS) {
            if (result !is LyricsRepository.Result.Offline) break
            delay(pause)
            result = repo.fetch(query)
        }
        return result
    }

    /**
     * Refetch the current song: a network blip should not need a track change,
     * and an explicit retry also overrides the cached miss and the cellular
     * rail, since the user just said it is worth a request.
     */
    fun retry() {
        val query = _query.value ?: return
        viewModelScope.launch {
            _loading.value = true
            _result.value = repo.fetch(query, refresh = true)
            fetchedFor = query
            _loading.value = false
        }
    }

    /** Seeks to a tapped line, so the sheet doubles as a scrubber. */
    fun seekTo(lineMs: Long) {
        player.seekToMs(lineMs)
    }

    /**
     * Long-press a line to declare it "what is playing now": the offset
     * becomes whatever puts that line under the cursor, which is faster and
     * more precise than dragging the slider until it looks right.
     */
    fun syncTo(lineMs: Long) {
        val at = player.state.value.positionMs
        if (at <= 0) return
        setOffset((lineMs - at).toInt())
    }

    /**
     * Sets the offset from the sync panel. The page moves immediately, but the
     * write is debounced: a drag settles into one DataStore update instead of
     * one per frame of the gesture.
     */
    fun setOffset(ms: Int) {
        val next = ms.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        _offset.value = next
        offsetWrite?.cancel()
        offsetWrite = viewModelScope.launch {
            delay(OFFSET_WRITE_DELAY_MS)
            prefs.setLyricsOffset(next)
        }
    }

    fun resetOffset() = setOffset(0)

    private companion object {
        /** Pauses between auto-retries: 1 initial fetch + 2 retries. */
        val AUTO_RETRY_PAUSES_MS = longArrayOf(2_000, 6_000)

        /** Fast enough that a line turns on time, slow enough to be free. */
        const val TICK_MS = 60L

        /** Same clamp the panel and the pref use. */
        const val MAX_OFFSET_MS = 10_000

        /** Long enough to swallow a drag, short enough to survive a quick close. */
        const val OFFSET_WRITE_DELAY_MS = 150L
    }
}
