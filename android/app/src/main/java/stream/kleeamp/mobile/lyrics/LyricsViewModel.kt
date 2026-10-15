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
        data object Missing : Status
        data object Instrumental : Status
        data object Ready : Status
    }

    data class UiState(
        val lines: List<LrcLine> = emptyList(),
        val plain: String = "",
        val activeIndex: Int = -1,
        val status: Status = Status.Loading,
        val offsetMs: Int = 0,
    )

    private val _result =
        MutableStateFlow<LyricsRepository.Result>(LyricsRepository.Result.Missing)
    private val _loading = MutableStateFlow(true)

    val state: StateFlow<UiState> = combine(
        player.state,
        prefs.lyricsOffset,
        _result,
        _loading,
    ) { transport, offset, result, loading ->
        val (lines, plain, status) = when {
            loading -> Triple(emptyList(), "", Status.Loading)
            result is LyricsRepository.Result.Lines ->
                Triple(result.lines, result.plain, Status.Ready)
            result is LyricsRepository.Result.Instrumental ->
                Triple(emptyList(), "", Status.Instrumental)
            else -> Triple(emptyList(), "", Status.Missing)
        }
        val cursor = transport.positionMs + offset
        UiState(
            lines = lines,
            plain = plain,
            activeIndex = lines.indexOfLast { it.timeMs <= cursor },
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
                    _loading.value = true
                    _result.value = if (query == null) {
                        LyricsRepository.Result.Missing
                    } else {
                        repo.fetch(query)
                    }
                    _loading.value = false
                }
        }
    }

    /** Nudges the sync offset like cliamp's `[`/`]` keys; persists ±10s. */
    fun adjustOffset(deltaMs: Int) {
        viewModelScope.launch {
            val next = (state.value.offsetMs + deltaMs).coerceIn(-10_000, 10_000)
            prefs.setLyricsOffset(next)
        }
    }
}
