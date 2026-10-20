package stream.kleeamp.mobile.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station

class PodcastShowViewModel(
    private val podcasts: PodcastRepository,
    private val prefs: Prefs,
    private val downloads: DownloadStore,
) : ViewModel() {
    data class UiState(
        val dlStates: Map<String, DownloadState>,
        val dlEntries: Map<String, DownloadEntry>,
        val autoDownload: Boolean,
        val showState: ShowState,
        val progress: Map<String, EpisodeProgress>,
        val resumePodcasts: Boolean = true,
        val subscriptions: List<PodcastShow>,
        val favorites: Set<String>,
    )

    sealed interface Event {
        data class ToggleSubscription(val show: PodcastShow) : Event
        data class ToggleFavorite(val station: Station) : Event
        data object RefreshShow : Event
        data class MarkCompleted(val station: Station) : Event
        data class SetAllPlayed(val played: Boolean) : Event
        data class ClearProgress(val station: Station) : Event
        data class Download(val station: Station) : Event
        data class CancelDownload(val url: String) : Event
        data class PauseDownload(val url: String) : Event
        data class ResumeDownload(val url: String) : Event
        data class RemoveDownload(val url: String) : Event
        data class DownloadStations(val stations: List<Station>) : Event
        data object AutoDownload : Event
    }

    /** Show feed plus the sets rows read: subscriptions and favourited episode urls. */
    private data class ShowBits(
        val show: ShowState,
        val progress: Map<String, EpisodeProgress>,
        val subscriptions: List<PodcastShow>,
        val favorites: Set<String>,
    )

    private data class DlBits(
        val states: Map<String, DownloadState> = emptyMap(),
        val entries: Map<String, DownloadEntry> = emptyMap(),
        val autoDownload: Boolean = false,
    )

    val state: StateFlow<UiState> = combine(
        combine(downloads.states, downloads.entries, prefs.autoDownload) { a, b, c ->
            DlBits(a, b, c)
        },
        combine(
            podcasts.show,
            podcasts.progress,
            podcasts.subscriptions,
            prefs.favorites,
        ) { show, progress, subscriptions, favorites ->
            ShowBits(show, progress, subscriptions, favorites.mapTo(HashSet()) { it.url })
        },
        prefs.resumePodcasts,
    ) { x, y, resume ->
        UiState(
            dlStates = x.states,
            dlEntries = x.entries,
            autoDownload = x.autoDownload,
            showState = y.show,
            progress = y.progress,
            resumePodcasts = resume,
            subscriptions = y.subscriptions,
            favorites = y.favorites,
        )
    }.stateInUi(
        viewModelScope,
        UiState(
            dlStates = downloads.states.value,
            dlEntries = downloads.entries.value,
            autoDownload = false,
            showState = podcasts.show.value,
            progress = emptyMap(),
            subscriptions = emptyList(),
            favorites = emptySet(),
        ),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleSubscription -> viewModelScope.launch {
                podcasts.toggleSubscription(e.show)
            }
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
            is Event.RefreshShow -> podcasts.refreshShow()
            is Event.MarkCompleted -> viewModelScope.launch {
                podcasts.markCompleted(e.station)
                cleanupPlayed(listOf(e.station))
            }
            is Event.SetAllPlayed -> viewModelScope.launch {
                val show = state.value.showState.show ?: return@launch
                val stations = state.value.showState.episodes.map { it.toStation(show) }
                if (stations.isEmpty()) return@launch
                if (e.played) {
                    podcasts.markAllCompleted(stations)
                    cleanupPlayed(stations)
                } else {
                    podcasts.clearAllProgress(stations)
                }
            }
            is Event.ClearProgress -> viewModelScope.launch {
                podcasts.clearProgress(e.station)
            }
            is Event.Download -> downloads.download(e.station)
            is Event.CancelDownload -> downloads.cancel(e.url)
            is Event.PauseDownload -> downloads.pause(e.url)
            is Event.ResumeDownload -> downloads.resume(e.url)
            is Event.RemoveDownload -> downloads.remove(e.url)
            // The picker's done chip: one fire per checked episode. Already
            // fetched URLs are no-ops store-side, so no filtering here.
            is Event.DownloadStations -> e.stations.forEach { downloads.download(it) }
            is Event.AutoDownload -> {
                val s = state.value
                val show = s.showState.show ?: return
                val subscribed = s.subscriptions.any { it.feedUrl == show.feedUrl }
                if (subscribed && s.autoDownload && s.showState.episodes.isNotEmpty()) {
                    downloads.autoDownload(
                        show,
                        s.showState.episodes,
                        s.progress.filterValues { it.completed }.keys,
                    )
                }
            }
        }
    }

    /**
     * Delete-after-listening for explicitly finished episodes. The player
     * path (progress near the end) runs through KleeampApp's progress sink
     * instead; both read the same scope.
     */
    private suspend fun cleanupPlayed(stations: List<Station>) {
        downloads.removePlayed(prefs.cleanupPlayed.first(), stations)
    }
}
