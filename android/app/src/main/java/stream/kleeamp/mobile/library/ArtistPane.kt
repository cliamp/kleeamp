package stream.kleeamp.mobile.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import stream.kleeamp.mobile.model.Station

/** One artist as a navigation page: every track of theirs in one list. */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun LibraryArtistPane(
    vm: ArtistViewModel,
    artist: String,
    /** The row's first song, so the header wears the image the artist row did. */
    coverUrl: String = "",
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onInfo: ((Station) -> Unit)? = null,
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
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
        onToggleFavorite = { vm.onEvent(ArtistViewModel.Event.ToggleFavorite(it)) },
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
    )
}
