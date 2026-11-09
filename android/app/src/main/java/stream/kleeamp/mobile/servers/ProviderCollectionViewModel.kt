package stream.kleeamp.mobile.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.library.albumDisplayArtist
import stream.kleeamp.mobile.library.artistTrackOrder
import stream.kleeamp.mobile.library.browseArtistName
import stream.kleeamp.mobile.library.matchesAlbum
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.Prefs

/**
 * One provider album or artist page, with exactly the local page's scoping:
 * [artist] is blank on an album opened from the albums tab and set when it was
 * opened from an artist page, so a generic album tag cannot leak other singers'
 * tracks in. [album] blank means an artist page. Tracks keep the local
 * disc/track order on an album and the local album-track order on an artist.
 */
class ProviderCollectionViewModel(
    private val accountId: String,
    private val album: String,
    private val albumArtist: String,
    private val artist: String,
    private val store: ProviderStore,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val songs: List<Station> = emptyList(),
        val favorites: List<Station> = emptyList(),
        /** Album pages only: same display rule as the local albums list. */
        val displayArtist: String = "",
        val loading: Boolean = true,
        val failure: String? = null,
    )

    sealed interface Event {
        data class ToggleFavorite(val station: Station) : Event
    }

    private val loaded = MutableStateFlow<List<Station>?>(null)
    private val failure = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        loaded,
        failure,
        prefs.favorites,
    ) { all, error, favorites ->
        val source = all.orEmpty()
        val mine = if (album.isNotBlank()) {
            source.filter { it.matchesAlbum(album, albumArtist, artist) }
                .sortedWith(
                    compareBy<Station> { it.discNo }
                        .thenBy { it.trackNo }
                        .thenBy { it.name.lowercase() },
                )
        } else {
            source
                .filter { it.browseArtistName().equals(artist, ignoreCase = true) }
                .sortedWith(artistTrackOrder)
        }
        UiState(
            songs = mine,
            favorites = favorites,
            displayArtist = if (album.isNotBlank()) albumDisplayArtist(albumArtist, mine) else "",
            loading = all == null && error == null,
            failure = error,
        )
    }.stateInUi(viewModelScope, UiState())

    init {
        viewModelScope.launch {
            val account = store.read().firstOrNull { it.id == accountId }
            if (account == null) {
                failure.value = "provider is no longer connected"
                return@launch
            }
            runCatching { loadProviderSongs(account) }
                .onSuccess { loaded.value = it }
                .onFailure { failure.value = it.message ?: "could not load" }
        }
    }

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
        }
    }
}
