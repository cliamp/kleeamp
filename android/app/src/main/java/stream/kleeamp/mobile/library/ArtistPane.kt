package stream.kleeamp.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.CollectionActions
import stream.kleeamp.mobile.chrome.CollectionHeader
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** One artist as a navigation page: every track of theirs in one list. */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun LibraryArtistPane(
    vm: ArtistViewModel,
    artist: String,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onInfo: ((Station) -> Unit)? = null,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val ui by vm.state.collectAsState()
    val songs = ui.songs
    var query by rememberSaveable(artist) { mutableStateOf("") }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    val shown = remember(songs, query) { songs.matching(query).distinctBy { it.url } }
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
                                    songs = songs,
                                    modifier = Modifier.fillMaxSize(),
                                    fallbackKey = artist,
                                    fallbackName = artist,
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
                                    if (query.isNotBlank()) "nothing matches" else "no songs",
                                    KleeampType.rowSecondary, p.inkFaint,
                                )
                            }
                        }
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

                // The row menu as a bottom sheet: favourites, queue and info
                // where it resolves.
                menuFor?.let { s ->
                    val fav = ui.favorites.any { it.url == s.url }
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
                                onQueue = { onAddToQueue(s) },
                                onToggleFavorite = {
                                    vm.onEvent(ArtistViewModel.Event.ToggleFavorite(s))
                                },
                                onAddToPlaylist = { onAddToPlaylist(s) },
                                onInfo = info?.let { show -> { show(s) } },
                            ),
                        ),
                        onDismiss = { menuFor = null },
                    )
                }
            }
        }
    }
}
