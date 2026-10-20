package stream.kleeamp.mobile.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.radio.CountryCount
import stream.kleeamp.mobile.prefs.Prefs

class PodcastsViewModel(
    private val podcasts: PodcastRepository,
    private val prefs: Prefs,
    private val downloads: DownloadStore,
    countries: StateFlow<List<CountryCount>>,
) : ViewModel() {
    data class UiState(
        val subsGrid: Boolean,
        val podDirectoryGrid: Boolean,
        val countries: List<CountryCount>,
        val directory: PodcastDirectoryState,
        val subscriptions: List<PodcastShow>,
    )

    sealed interface Event {
        data object ToggleSubsGrid : Event
        data object TogglePodDirectoryGrid : Event
        data object NextPage : Event
        data class Load(val query: PodcastQuery) : Event
        data class ToggleSubscription(val show: PodcastShow) : Event
        data class DownloadAll(val show: PodcastShow) : Event
        data class LoadShowPlayed(val show: PodcastShow) : Event
        data class SetAllPlayed(val show: PodcastShow, val played: Boolean) : Event
    }

    val state: StateFlow<UiState> = combine(
        prefs.subsGrid,
        prefs.podDirectoryGrid,
        countries,
        podcasts.directory,
        podcasts.subscriptions,
    ) { subsGrid, podDirectoryGrid, countryList, directory, subscriptions ->
        UiState(
            subsGrid = subsGrid,
            podDirectoryGrid = podDirectoryGrid,
            countries = countryList,
            directory = directory,
            subscriptions = subscriptions,
        )
    }.stateInUi(
        viewModelScope,
        UiState(
            subsGrid = prefs.subsGrid.value,
            podDirectoryGrid = prefs.podDirectoryGrid.value,
            countries = emptyList(),
            directory = podcasts.directory.value,
            subscriptions = emptyList(),
        ),
    )

    /** Shows whose cached episodes are all finished, for the show-menu toggle. */
    private val _playedShows = MutableStateFlow<Set<String>>(emptySet())
    val playedShows: StateFlow<Set<String>> = _playedShows.asStateFlow()

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleSubsGrid -> viewModelScope.launch {
                prefs.setSubsGrid(!state.value.subsGrid)
            }
            is Event.TogglePodDirectoryGrid -> viewModelScope.launch {
                prefs.setPodDirectoryGrid(!state.value.podDirectoryGrid)
            }
            is Event.NextPage -> podcasts.nextPage()
            is Event.Load -> podcasts.load(e.query, reset = true)
            is Event.ToggleSubscription -> viewModelScope.launch {
                podcasts.toggleSubscription(e.show)
            }
            is Event.DownloadAll -> viewModelScope.launch {
                downloads.trackResolving {
                    // In hand first: the open show or a cached feed queues
                    // immediately. An unloaded show loads its feed first and
                    // queues whatever lands, instead of silently doing nothing.
                    val live = podcasts.show.value.let {
                        if (it.show?.feedUrl == e.show.feedUrl) it.episodes else emptyList()
                    }
                    if (live.isNotEmpty()) {
                        downloads.downloadAll(e.show, live)
                        return@trackResolving
                    }
                    val cached = podcasts.allEpisodes(e.show)
                    if (cached.isNotEmpty()) {
                        downloads.downloadAll(e.show, cached)
                        return@trackResolving
                    }
                    podcasts.openShow(e.show)
                    val landed = withTimeoutOrNull(30_000) {
                        podcasts.show.first {
                            it.show?.feedUrl == e.show.feedUrl && (!it.loading || it.episodes.isNotEmpty())
                        }
                    }?.episodes.orEmpty()
                    if (landed.isNotEmpty()) downloads.downloadAll(e.show, landed)
                }
            }
            // The show menu opens without network: cached episodes are enough
            // to decide whether every mark is already there.
            is Event.LoadShowPlayed -> viewModelScope.launch {
                val episodes = podcasts.cachedEpisodesFor(e.show)
                val progress = podcasts.progress.first()
                val all = episodes.isNotEmpty() && episodes.all {
                    progress[it.toStation(e.show).url]?.completed == true
                }
                _playedShows.update { urls ->
                    if (all) urls + e.show.feedUrl else urls - e.show.feedUrl
                }
            }
            is Event.SetAllPlayed -> viewModelScope.launch {
                val episodes = podcasts.allEpisodes(e.show)
                if (episodes.isEmpty()) return@launch
                val stations = episodes.map { it.toStation(e.show) }
                if (e.played) {
                    podcasts.markAllCompleted(stations)
                    downloads.removePlayed(prefs.cleanupPlayed.first(), stations)
                } else {
                    podcasts.clearAllProgress(stations)
                }
                _playedShows.update { urls ->
                    if (e.played) urls + e.show.feedUrl else urls - e.show.feedUrl
                }
            }
        }
    }
}
