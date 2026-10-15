package stream.kleeamp.mobile.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
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
 * title (a radio title change is a new song), the active line follows the
 * player clock plus the persisted offset, and a track change cancels an
 * in-flight fetch rather than stamping stale lines.
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
        /** Searched; the service has nothing for this song. */
        data object NotFound : Status
        data object Instrumental : Status
        /** The lookup failed; retryable. */
        data object Offline : Status
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

    val state: StateFlow<UiState> = combine(
        player.state,
        prefs.lyricsOffset,
        _result,
        _loading,
        _query,
    ) { transport, offset, result, loading, query ->
        val (lines, plain, status) = when {
            loading -> Triple(emptyList(), "", Status.Loading)
            query == null -> Triple(emptyList(), "", Status.NoSong)
            result is LyricsRepository.Result.Lines ->
                Triple(result.lines, result.plain, Status.Ready)
            result is LyricsRepository.Result.Instrumental ->
                Triple(emptyList(), "", Status.Instrumental)
            result is LyricsRepository.Result.Offline ->
                Triple(emptyList(), "", Status.Offline)
            else -> Triple(emptyList(), "", Status.NotFound)
        }
        val cursor = transport.positionMs + offset
        UiState(
            lines = lines,
            plain = plain,
            activeIndex = lines.indexOfLast { it.timeMs <= cursor },
            query = query,
            status = status,
            offsetMs = offset,
        )
    }.stateInUi(viewModelScope, UiState())

    init {
        viewModelScope.launch {
            combine(PlaybackBus.station, PlaybackBus.streamTitle) { station, title ->
                station?.let { lyricsQuery(it, title) }
            }
                .distinctUntilChanged()
                .collectLatest { query ->
                    _query.value = query
                    _loading.value = true
                    _result.value = if (query == null) {
                        LyricsRepository.Result.NotFound
                    } else {
                        fetchWithRetry(query)
                    }
                    _loading.value = false
                }
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
            kotlinx.coroutines.delay(pause)
            result = repo.fetch(query)
        }
        return result
    }

    /** Refetch the current song: a network blip should not need a track change. */
    fun retry() {
        val query = _query.value ?: return
        viewModelScope.launch {
            _loading.value = true
            _result.value = fetchWithRetry(query)
            _loading.value = false
        }
    }

    private companion object {
        /** Pauses between auto-retries: 1 initial fetch + 2 retries. */
        val AUTO_RETRY_PAUSES_MS = longArrayOf(2_000, 6_000)
    }

    /** Nudges the sync offset like cliamp's `[`/`]` keys; persists ±10s. */
    fun adjustOffset(deltaMs: Int) {
        viewModelScope.launch {
            val next = (state.value.offsetMs + deltaMs).coerceIn(-10_000, 10_000)
            prefs.setLyricsOffset(next)
        }
    }
}
