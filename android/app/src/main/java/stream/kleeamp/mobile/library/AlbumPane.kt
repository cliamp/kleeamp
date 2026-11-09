package stream.kleeamp.mobile.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import stream.kleeamp.mobile.model.Station

/** One album as a navigation page: every track in disc/track order. */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun LibraryAlbumPane(
    vm: AlbumViewModel,
    album: String,
    albumArtist: String,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onInfo: ((Station) -> Unit)? = null,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
) {
    val ui by vm.state.collectAsState()
    CollectionAlbumPage(
        title = album,
        displayArtist = ui.displayArtist,
        songs = ui.songs,
        favorites = ui.favorites,
        onToggleFavorite = { vm.onEvent(AlbumViewModel.Event.ToggleFavorite(it)) },
        current = current,
        playing = playing,
        onPlay = onPlay,
        onAddToQueue = onAddToQueue,
        onPlayNext = onPlayNext,
        onAddToPlaylist = onAddToPlaylist,
        onInfo = onInfo,
        onBack = onBack,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        shuffled = shuffled,
        onToggleShuffle = onToggleShuffle,
        artKey = "${albumArtist.lowercase()}\u0001${album.ifBlank { "unknown album" }.lowercase()}",
    )
}
