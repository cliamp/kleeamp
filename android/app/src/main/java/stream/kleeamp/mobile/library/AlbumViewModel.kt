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
 * Every track of one album within the list the album was opened from.
 * Context "local-songs" scopes to the on-device library; otherwise the
 * source is exactly that user playlist's resolved members. The album is
 * keyed by album + album artist so compilations stay one album while
 * same-named albums from different album artists stay apart. A non-blank
 * [artist] narrows it further when the album was opened from an artist page,
 * so a generic album tag shared by many singers cannot leak their tracks in.
 * Matching is case-insensitive so the detail agrees with the lowercased list
 * key. Songs play in disc/track order.
 */
class AlbumViewModel(
    val album: String,
    val albumArtist: String,
    private val context: String,
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
    private val artist: String = "",
) : ViewModel() {
    data class UiState(
        val songs: List<Station> = emptyList(),
        val favorites: List<Station> = emptyList(),
        /** Shown under the album title, same rule as the albums list. */
        val displayArtist: String = "",
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
        val source = if (context == "local-songs") songs else members
        val mine = source.distinctBy { it.id }
            .filter { it.matchesAlbum(album, albumArtist, artist) }
            .sortedWith(
                compareBy<Station> { it.discNo }.thenBy { it.trackNo }.thenBy { it.name.lowercase() },
            )
        UiState(
            songs = mine,
            favorites = favorites,
            displayArtist = albumDisplayArtist(albumArtist, mine),
        )
    }.stateInUi(viewModelScope, UiState())

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
        }
    }
}

/**
 * Whether a song belongs to the album page's key. A non-blank [artist] also
 * requires that browse-artist attribution, so an album opened from an artist
 * page cannot pull in other singers that share a generic album tag.
 */
internal fun Station.matchesAlbum(album: String, albumArtist: String, artist: String = ""): Boolean =
    this.album.ifBlank { "unknown album" }.equals(album.ifBlank { "unknown album" }, ignoreCase = true) &&
        this.albumArtist.equals(albumArtist, ignoreCase = true) &&
        (artist.isBlank() || browseArtistName().equals(artist, ignoreCase = true))
