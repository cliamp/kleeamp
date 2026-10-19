package stream.kleeamp.mobile.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.play.QueueContinuation
import stream.kleeamp.mobile.prefs.Prefs

class StationsViewModel(
    private val repository: RadioRepository,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val cliamp: List<Station>,
        val cliampError: String?,
        val cliampChannels: List<CliampChannels.Channel>,
        val custom: List<Station>,
        val directory: DirectoryState,
        val directoryStats: DirectoryStats?,
        val tags: List<NameCount>,
        val countries: List<CountryCount>,
        val listenerGlobe: Boolean,
        val globeHintSeen: Boolean,
    )

    sealed interface Event {
        data object NextPage : Event
        data object RefreshCliamp : Event
        data object RefreshStats : Event
        data object MarkGlobeHintSeen : Event
        data class LoadDirectory(val query: DirectoryQuery, val reset: Boolean = true) : Event
        data class AddCustom(val station: Station) : Event
        data class RemoveCustom(val station: Station) : Event
        data class ToggleFavorite(val station: Station) : Event
    }

    private data class CatalogState(
        val cliamp: List<Station>,
        val cliampError: String?,
        val cliampChannels: List<CliampChannels.Channel>,
        val custom: List<Station>,
        val directory: DirectoryState,
        val directoryStats: DirectoryStats?,
    )

    private data class FacetState(
        val tags: List<NameCount>,
        val countries: List<CountryCount>,
    )

    val state: StateFlow<UiState> = combine(
        combine(
            combine(
                repository.cliamp,
                repository.cliampError,
                prefs.custom,
                repository.directory,
                repository.directoryStats,
            ) { cliamp, cliampError, custom, directory, directoryStats ->
                CatalogState(cliamp, cliampError, emptyList(), custom, directory, directoryStats)
            },
            repository.cliampChannels,
        ) { parts, channels -> parts.copy(cliampChannels = channels) },
        combine(
            repository.tags,
            repository.countries,
            ::FacetState,
        ),
    ) { catalog, facets ->
        UiState(
            cliamp = catalog.cliamp,
            cliampError = catalog.cliampError,
            cliampChannels = catalog.cliampChannels,
            custom = catalog.custom,
            directory = catalog.directory,
            directoryStats = catalog.directoryStats,
            tags = facets.tags,
            countries = facets.countries,
            listenerGlobe = true,
            globeHintSeen = false,
        )
    }.combine(prefs.listenerGlobe) { ui, globe ->
        ui.copy(listenerGlobe = globe)
    }.combine(prefs.globeHintSeen) { ui, seen ->
        ui.copy(globeHintSeen = seen)
    }.stateInUi(
        viewModelScope,
        UiState(
            cliamp = repository.cliamp.value,
            cliampError = repository.cliampError.value,
            cliampChannels = repository.cliampChannels.value,
            custom = emptyList(),
            directory = repository.directory.value,
            directoryStats = repository.directoryStats.value,
            tags = repository.tags.value,
            countries = emptyList(),
            listenerGlobe = true,
            globeHintSeen = false,
        ),
    )

    val cliampStats: StateFlow<CliampStats?> = repository.cliampStats

    fun onEvent(e: Event) {
        when (e) {
            is Event.NextPage -> repository.nextPage()
            is Event.RefreshCliamp -> repository.refreshCliamp()
            is Event.RefreshStats -> repository.refreshCliampStats()
            is Event.MarkGlobeHintSeen -> viewModelScope.launch { prefs.setGlobeHintSeen() }
            is Event.LoadDirectory -> repository.loadDirectory(e.query, e.reset)
            is Event.AddCustom -> viewModelScope.launch {
                prefs.addCustom(e.station)
            }
            is Event.RemoveCustom -> viewModelScope.launch {
                prefs.removeCustom(e.station)
            }
            is Event.ToggleFavorite -> viewModelScope.launch {
                prefs.toggleFavorite(e.station)
            }
        }
    }

    /**
     * Player queue hook: a continuation that keeps fetching the pages after
     * [query]'s already loaded rows. Null only for the favorites filter, which
     * is a local list and has no catalogue page to ask for.
     */
    fun continuation(query: DirectoryQuery, offset: Int): QueueContinuation =
        repository.queueContinuation(query, offset)
}
