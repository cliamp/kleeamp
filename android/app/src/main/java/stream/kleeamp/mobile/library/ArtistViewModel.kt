package stream.kleeamp.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.Prefs

/**
 * Every track of one artist within the list the artist was opened from.
 * Context "local-songs" scopes to the on-device library; otherwise the
 * source is exactly that user playlist's resolved members. Songs by the
 * same artist outside that list are never merged in. One song lives under
 * exactly one artist (album artist, else primary track artist, else unknown),
 * so a track never shows under two names.
 */
class ArtistViewModel(
    val artist: String,
    private val context: String,
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val songs: List<Station> = emptyList(),
        val favorites: List<Station> = emptyList(),
    )

    sealed interface Event {
        data class ToggleFavorite(val station: Station) : Event
    }

    val state: StateFlow<UiState> = combine(
        localLibrary.songs,
        playlists.playlists,
        prefs.favorites,
    ) { songs, all, favorites ->
        val members = all.firstOrNull { it.station.slug == context }?.let {
            playlists.resolveMembers(it.songIds, songs)
        }.orEmpty()
        // Scope strictly to the list the artist was opened from: the
        // device library for "local-songs", otherwise that playlist's
        // own members. Never union the two. Case-insensitive so "daft punk"
        // and "Daft Punk" stay one artist, like the albums list. Single
        // attribution (album artist, else primary track artist, else unknown)
        // so one song never lands under two names.
        val source = if (context == "local-songs") songs else members
        val want = artist.ifBlank { "unknown artist" }
        val mine = source.distinctBy { it.id }
            .filter { it.browseArtistName().equals(want, ignoreCase = true) }
            .sortedWith(artistTrackOrder)
        UiState(songs = mine, favorites = favorites)
    }.stateInUi(viewModelScope, UiState())

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
        }
    }
}
