package stream.kleeamp.mobile.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import stream.kleeamp.mobile.library.CollectionAlbumPage
import stream.kleeamp.mobile.library.CollectionArtistPage
import stream.kleeamp.mobile.model.Station

/**
 * One provider artist, rendered by the exact same page the local library
 * uses. The view model scopes and orders the tracks; the shared composable
 * owns every pixel, so a provider artist can never drift from a local one.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun ProviderArtistPane(
    vm: ProviderCollectionViewModel,
    artist: String,
    coverUrl: String,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
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
    CollectionArtistPage(
        artist = artist,
        coverUrl = coverUrl,
        songs = ui.songs,
        favorites = ui.favorites,
        onToggleFavorite = {
            vm.onEvent(ProviderCollectionViewModel.Event.ToggleFavorite(it))
        },
        current = current,
        playing = playing,
        onPlay = onPlay,
        onOpenAlbum = onOpenAlbum,
        onAddToQueue = onAddToQueue,
        onPlayNext = onPlayNext,
        onAddToPlaylist = onAddToPlaylist,
        onInfo = onInfo,
        onBack = onBack,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        shuffled = shuffled,
        onToggleShuffle = onToggleShuffle,
        loading = ui.loading,
    )
}

/** One provider album, on the same shared album page as the local library. */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun ProviderAlbumPane(
    vm: ProviderCollectionViewModel,
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
        onToggleFavorite = {
            vm.onEvent(ProviderCollectionViewModel.Event.ToggleFavorite(it))
        },
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
        loading = ui.loading,
        artKey = "${albumArtist.lowercase()}\u0001${album.ifBlank { "unknown album" }.lowercase()}",
    )
}
