package stream.kleeamp.mobile.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.playback.effectiveBoostDb
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_BACK_SEC
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_FWD_SEC
import stream.kleeamp.mobile.prefs.playSectionFor
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerConnection
import stream.kleeamp.mobile.playback.upNextIndices
import stream.kleeamp.mobile.playback.PlayerState

@UnstableApi
class NowPlayingViewModel(
    val player: PlayerConnection,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val playerState: PlayerState = PlayerState(),
        val shownStation: Station? = null,
        val previousStation: Station? = null,
        val nextStation: Station? = null,
        val streamTitle: String = "",
        val upNextCount: Int = 0,
        val reconnect: Int = 0,
        val error: String? = null,
        val isFav: Boolean = false,
        val shuffled: Boolean = false,
        val visualizer: String = "spectrum",
        val outputDevice: Int = -1,
        val speedSections: Boolean = true,
        val boostDb: Int = 0,
        val boostSupported: Boolean = false,
        val boostSections: Boolean = true,
        val showSkipKeys: Boolean = false,
        val skipBackSec: Int = DEFAULT_SKIP_BACK_SEC,
        val skipFwdSec: Int = DEFAULT_SKIP_FWD_SEC,
    )

    sealed interface Event {
        data object ToggleFavorite : Event
        data class SetSpeed(val value: Float) : Event
        data class SetBoostDb(val db: Int) : Event
        data class SetOutputDevice(val id: Int) : Event
        data class SetVisualizer(val id: String) : Event
    }

    /** Whether this device ships the loudness-enhancer effect; static per device. */
    private val boostSupported: Boolean =
        runCatching { stream.kleeamp.mobile.playback.VolumeBoost().isSupported() }.getOrDefault(false)

    /** Live transport and tune state: what is playing and what it says. */
    private data class BusState(
        val playerState: PlayerState = PlayerState(),
        val station: Station? = null,
        val streamTitle: String = "",
        val error: String? = null,
        val reconnect: Int = 0,
    )

    /** Skip-key durations, chained onto [LibState] below. */
    private data class SkipKeysState(
        val backSec: Int = DEFAULT_SKIP_BACK_SEC,
        val fwdSec: Int = DEFAULT_SKIP_FWD_SEC,
    )

    /** Library prefs and derived flags that dress the transport. */
    private data class LibState(
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val visualizer: String = "spectrum",
        val shuffled: Boolean = false,
        val outputDevice: Int = -1,
        val speedSections: Boolean = true,
        val boostSections: Boolean = true,
        val boostMaps: Map<String, Int> = emptyMap(),
        val skipBackSec: Int = DEFAULT_SKIP_BACK_SEC,
        val skipFwdSec: Int = DEFAULT_SKIP_FWD_SEC,
    )

    val state: StateFlow<UiState> = combine(
        combine(
            player.state,
            PlaybackBus.station,
            PlaybackBus.streamTitle,
            PlaybackBus.error,
            PlaybackBus.reconnectAttempt,
            ::BusState,
        ),
        // Spectrum and stereo ride the bus straight into the meter frame
        // loops; routing them through here would recompose the whole player
        // on every analyser callback. Only the output choice is needed.
        combine(
            prefs.outputDevice,
            prefs.favorites,
            prefs.history,
            prefs.visualizer,
            combine(player.shuffle, prefs.speedPerSection) { s, sec -> s to sec },
        ) { output, favorites, history, visualizer, shuffleSections ->
            val (shuffled, sections) = shuffleSections
            LibState(
                favorites = favorites,
                recent = history,
                visualizer = visualizer,
                shuffled = shuffled,
                outputDevice = output,
                speedSections = sections,
            )
        }.combine(
            combine(prefs.skipBackSec, prefs.skipFwdSec, ::SkipKeysState),
        ) { lib, skip ->
            lib.copy(skipBackSec = skip.backSec, skipFwdSec = skip.fwdSec)
        }.combine(
            prefs.boostPerSection,
        ) { lib, per ->
            lib.copy(boostSections = per)
        }.combine(
            prefs.boostSections,
        ) { lib, maps ->
            lib.copy(boostMaps = maps)
        },
        player.panelList,
        player.panelIndex,
        prefs.boostDb,
    ) { bus, lib, upNext, upNextIndex, boost ->
        // Before anything has been played this session the live bus carries no
        // station, so fall back to the last-played station from history - the same
        // fallback the mini bar uses - rather than showing an empty "no track".
        val shownStation = bus.station ?: lib.recent.firstOrNull()
        val activeIndex = upNextIndex
            .takeIf { bus.station != null && upNext.getOrNull(it)?.url == bus.station.url }
            ?: -1
        // Cover previews follow the keys, not the bounded Up Next window: the
        // window holds one item for lone plays and a capped run for huge
        // lists, while prev/next walk the whole source, the launch fallback
        // ring and the heard trail. peekAdjacent mirrors that walk read-only.
        val (peekPrev, peekNext) = player.peekAdjacent()
        UiState(
            playerState = bus.playerState,
            shownStation = shownStation,
            previousStation = peekPrev,
            nextStation = peekNext,
            streamTitle = bus.streamTitle,
            upNextCount = upNextIndices(upNext.size, activeIndex).count(),
            reconnect = bus.reconnect,
            error = bus.error,
            isFav = shownStation != null && lib.favorites.any { it.url == shownStation.url },
            shuffled = lib.shuffled,
            visualizer = lib.visualizer,
            outputDevice = lib.outputDevice,
            speedSections = lib.speedSections,
            boostDb = effectiveBoostDb(boost, lib.boostMaps, lib.boostSections, shownStation),
            boostSupported = boostSupported,
            boostSections = lib.boostSections,
            // Skip keys dress tracks only (podcasts, audiobooks, local
            // files): live stations keep the plain 3-key row.
            showSkipKeys = shownStation?.isTrack == true,
            skipBackSec = lib.skipBackSec,
            skipFwdSec = lib.skipFwdSec,
        )
    }.stateInUi(viewModelScope, UiState())

    fun onEvent(e: Event) {
        when (e) {
            Event.ToggleFavorite -> viewModelScope.launch {
                state.value.shownStation?.let { prefs.toggleFavorite(it) }
            }
            // Pure player writes with no logic (seek/prev/next/shuffle/play-pause)
            // stay as direct vm.player.* calls in the composable; only writes
            // that go through prefs or need an event are routed here.
            is Event.SetSpeed -> player.setSpeed(e.value)
            is Event.SetBoostDb -> viewModelScope.launch {
                // Section-aware like speed: the playing tab keeps its own
                // gain while the master is on, else the one global value.
                val station = state.value.shownStation
                if (station != null) {
                    prefs.setBoostFor(playSectionFor(station.source), e.db)
                } else {
                    prefs.setBoostDb(e.db)
                }
            }
            is Event.SetOutputDevice -> viewModelScope.launch { prefs.setOutputDevice(e.id) }
            is Event.SetVisualizer -> viewModelScope.launch { prefs.setVisualizer(e.id) }
        }
    }
}
