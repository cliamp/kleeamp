package stream.kleeamp.mobile.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import stream.kleeamp.mobile.chrome.CollectionActions
import stream.kleeamp.mobile.chrome.CollectionHeader
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * One album page, shared by the local library and every provider. The caller
 * supplies the already-scoped, already-ordered songs; everything below - the
 * header, filter, rows and menu - is the exact same composable for both, so
 * the two can never drift apart.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun CollectionAlbumPage(
    title: String,
    displayArtist: String,
    songs: List<Station>,
    favorites: List<Station>,
    onToggleFavorite: (Station) -> Unit,
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
    loading: Boolean = false,
    artKey: String = title.lowercase(),
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val titleText = title.ifBlank { "unknown album" }
    var query by rememberSaveable(titleText) { mutableStateOf("") }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    val shown = remember(songs, query) { songs.matching(query).distinctBy { it.url } }
    Box(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        MainLayout(
            title = titleText,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item {
                        CollectionHeader(
                            title = titleText,
                            meta = when {
                                displayArtist.isNotBlank() -> "$displayArtist · ${songs.size} songs"
                                else -> "${songs.size} songs"
                            },
                            actions = {
                                // Always on, even empty: the taps no-op on nothing.
                                CollectionActions(
                                    shuffled = shuffled,
                                    onPlayAll = { shown.firstOrNull()?.let { onPlay(it, shown) } },
                                    onShufflePlay = {
                                        if (!shuffled) {
                                            onToggleShuffle()
                                            shown.randomOrNull()?.let { onPlay(it, shown) }
                                        } else {
                                            // Second tap turns it off; the current track keeps playing.
                                            onToggleShuffle()
                                        }
                                    },
                                )
                            },
                            art = {
                                GroupArt(
                                    songs = songs,
                                    modifier = Modifier.fillMaxSize(),
                                    fallbackKey = artKey,
                                    fallbackName = titleText,
                                )
                            },
                        )
                    }
                    item { FilterRow(value = query, onValue = { query = it }) }
                    if (shown.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Mono(
                                    when {
                                        loading -> "loading…"
                                        query.isNotBlank() -> "nothing matches"
                                        else -> "no songs"
                                    },
                                    KleeampType.rowSecondary, p.inkFaint,
                                )
                            }
                        }
                    }
                    items(shown, key = { it.id }) { s ->
                        PlaylistMemberRow(
                            s = s,
                            subtitle = s.artist,
                            current = current,
                            playing = playing,
                            queue = shown,
                            onPlay = onPlay,
                            onOpenMenu = { menuFor = it },
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }

                menuFor?.let { s ->
                    CollectionSongMenu(
                        s = s,
                        favorites = favorites,
                        onToggleFavorite = onToggleFavorite,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onInfo = onInfo,
                        onDismiss = { menuFor = null },
                    )
                }
            }
        }
    }
}

/**
 * One artist page, shared by the local library and every provider: the same
 * header, album drill and song list. [coverUrl] is the song the artist row
 * was wearing, so the header opens with the same image.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun CollectionArtistPage(
    artist: String,
    coverUrl: String,
    songs: List<Station>,
    favorites: List<Station>,
    onToggleFavorite: (Station) -> Unit,
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
    loading: Boolean = false,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val headerSongs = remember(songs, coverUrl) { artistHeaderSongs(songs, coverUrl) }
    var query by rememberSaveable(artist) { mutableStateOf("") }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    val shown = remember(songs, query) { songs.matching(query).distinctBy { it.url } }
    // The same album grouping the local albums tab uses, scoped to this
    // artist's tracks. Tapping a row opens that album's song list.
    val albums = remember(shown) { albumsOf(shown) }
    Box(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        MainLayout(
            title = artist,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item {
                        CollectionHeader(
                            title = artist,
                            meta = "${songs.size} songs",
                            actions = {
                                // Always on, even empty: the taps no-op on nothing.
                                CollectionActions(
                                    shuffled = shuffled,
                                    onPlayAll = { shown.firstOrNull()?.let { onPlay(it, shown) } },
                                    onShufflePlay = {
                                        if (!shuffled) {
                                            onToggleShuffle()
                                            shown.randomOrNull()?.let { onPlay(it, shown) }
                                        } else {
                                            // Second tap turns it off; the current track keeps playing.
                                            onToggleShuffle()
                                        }
                                    },
                                )
                            },
                            art = {
                                GroupArt(
                                    songs = headerSongs,
                                    modifier = Modifier.fillMaxSize(),
                                    fallbackKey = artist,
                                    fallbackName = artist,
                                )
                            },
                        )
                    }
                    item { FilterRow(value = query, onValue = { query = it }) }
                    if (albums.isNotEmpty()) {
                        item { SectionLabel("albums — ${albums.size}") }
                        items(albums, key = { "album:${it.key}" }) { group ->
                            ListRow(
                                onClick = { onOpenAlbum(group.name, group.albumArtist) },
                                verticalPadding = 9.dp,
                                leading = {
                                    GroupArt(
                                        songs = group.songs,
                                        modifier = Modifier.size(40.dp),
                                        fallbackKey = group.key,
                                        fallbackName = group.name,
                                    )
                                },
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Mono("${group.songs.size}", KleeampType.meta, p.inkFaint)
                                        Icon(
                                            KleeampIcons.CaretRight,
                                            "open",
                                            Modifier.size(9.dp),
                                            tint = p.inkFaint,
                                        )
                                    }
                                },
                            ) {
                                Mono(group.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                                Mono(
                                    group.displayArtist,
                                    KleeampType.rowSecondary,
                                    p.inkTertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Mono(
                                    when {
                                        loading -> "loading…"
                                        query.isNotBlank() -> "nothing matches"
                                        else -> "no songs"
                                    },
                                    KleeampType.rowSecondary, p.inkFaint,
                                )
                            }
                        }
                    } else {
                        item { SectionLabel("songs — ${shown.size}") }
                    }
                    items(shown, key = { it.id }) { s ->
                        PlaylistMemberRow(
                            s = s,
                            subtitle = s.albumArtistAlbum,
                            current = current,
                            playing = playing,
                            queue = shown,
                            onPlay = onPlay,
                            onOpenMenu = { menuFor = it },
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }

                menuFor?.let { s ->
                    CollectionSongMenu(
                        s = s,
                        favorites = favorites,
                        onToggleFavorite = onToggleFavorite,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onInfo = onInfo,
                        onDismiss = { menuFor = null },
                    )
                }
            }
        }
    }
}

/** The row menu both collection pages wear; same capabilities, same order. */
@Composable
@Suppress("LongParameterList")
private fun CollectionSongMenu(
    s: Station,
    favorites: List<Station>,
    onToggleFavorite: (Station) -> Unit,
    onPlayNext: (Station) -> Unit,
    onAddToQueue: (Station) -> Unit,
    onAddToPlaylist: (Station) -> Unit,
    onInfo: ((Station) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val fav = favorites.any { it.url == s.url }
    val info = if (s.source != StationSource.Custom || fav) onInfo else null
    ContextMenuSheet(
        title = s.name,
        subtitle = s.artistAlbum.ifBlank {
            when {
                s.source == StationSource.Local -> durationLabel(s.durationMs)
                s.source == StationSource.Podcast -> "podcast"
                else -> s.meta
            }
        },
        art = { StationMenuArt(s) },
        actions = menuActions(
            MenuSubject(
                kind = MenuKind.STATION,
                favorite = fav,
                infoAvailable = info != null,
                onPlayNext = { onPlayNext(s) },
                onQueue = { onAddToQueue(s) },
                onToggleFavorite = { onToggleFavorite(s) },
                onAddToPlaylist = { onAddToPlaylist(s) },
                onInfo = info?.let { show -> { show(s) } },
            ),
        ),
        onDismiss = onDismiss,
    )
}
