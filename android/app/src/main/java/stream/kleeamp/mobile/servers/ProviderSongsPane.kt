package stream.kleeamp.mobile.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.CollectionActions
import stream.kleeamp.mobile.chrome.CollectionHeader
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.library.AlbumGroup
import stream.kleeamp.mobile.library.ArtistGroup
import stream.kleeamp.mobile.library.GroupArt
import stream.kleeamp.mobile.library.LocalSongsTab
import stream.kleeamp.mobile.library.PlaylistMemberRow
import stream.kleeamp.mobile.library.albumsOf
import stream.kleeamp.mobile.library.artistsOf
import stream.kleeamp.mobile.library.matching
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * A provider account's songs, browsed exactly like the local-songs page:
 * pinned songs/albums/artists tabs, the same title/artist/album/recently-added
 * sorts on the songs tab, album rows with their group art, and real artist and
 * album destinations so navigation and predictive back behave like the local
 * library. One pane serves every provider - Plex, Jellyfin/Emby, Subsonic,
 * Audiobookshelf, Lyrion and SSH - because the view model has already
 * flattened each server's responses into [Station]s.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun ProviderSongsPane(
    vm: ProviderSongsViewModel,
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
    /** Non-blank locks the pane to one account with no picker row. */
    accountId: String = "",
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
    /** Opens the artist destination; [coverUrl] is the row's cover song. */
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    /** Opens the album destination, scoped to its album artist. */
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    // A locked pane shows that account only, with no picker: the row it came
    // from already named it.
    val accounts = remember(ui.accounts, accountId) {
        if (accountId.isBlank()) ui.accounts else ui.accounts.filter { it.id == accountId }
    }
    var selected by rememberSaveable(accountId) { mutableStateOf(accounts.firstOrNull()?.id) }
    // A removed account must not leave the filter pointing at nothing.
    LaunchedEffect(accounts) {
        if (accounts.none { it.id == selected }) selected = accounts.firstOrNull()?.id
    }
    var sort by rememberSaveable(accountId) { mutableStateOf(PlaylistSort.Title) }
    var query by rememberSaveable { mutableStateOf("") }
    var tabOrdinal by rememberSaveable { mutableStateOf(0) }
    val tab = LocalSongsTab.entries.getOrElse(tabOrdinal) { LocalSongsTab.Songs }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }

    val account = accounts.firstOrNull { it.id == selected }
    // SSH is the only provider that indexes locally, so its live scan state
    // rides above the list where it can be collected with a stable key.
    val indexState by SftpLibrary.status(account?.id.orEmpty()).collectAsState()
    val libraries = if (account?.providerKey == "plex" || account?.providerKey == "abs") {
        ui.librariesByAccount[selected].orEmpty()
    } else {
        emptyList()
    }
    val showSort = tab == LocalSongsTab.Songs
    val showControls = showSort ||
        account?.providerKey == "ssh" ||
        libraries.size > 1
    val pool = ui.songsByAccount[selected].orEmpty()
    val filtered = remember(pool, query) { pool.matching(query) }
    // The exact local-songs ordering and grouping: one set of algorithms for
    // every provider, not a provider-flavoured copy.
    val ordered = remember(filtered, sort) { sortedStations(filtered, sort) }
    val albumGroups = remember(ordered) { albumsOf(ordered) }
    val artistGroups = remember(ordered) { artistsOf(ordered) }
    val rootTitle = when {
        accountId.isBlank() && accounts.size > 1 -> "providers"
        else -> account?.displayName()?.ifBlank { null } ?: "provider"
    }
    val favorites = ui.favorites.map { it.url }.toSet()
    val failedLabels = ui.failures.keys.mapNotNull { id ->
        accounts.firstOrNull { it.id == id }?.displayName()?.ifBlank { null }
    }

    fun playAll(songs: List<Station>) {
        songs.firstOrNull()?.let { onPlay(it, songs) }
    }
    fun shufflePlay(songs: List<Station>) {
        if (!shuffled) {
            onToggleShuffle()
            songs.randomOrNull()?.let { onPlay(it, songs) }
        } else {
            // Second tap turns it off; the current track keeps playing.
            onToggleShuffle()
        }
    }

    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        MainLayout(
            title = rootTitle,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
            chips = {
                LocalSongsTab.entries.forEach { t ->
                    // The snap lives on the tap, never in an effect: coming
                    // back from an album/artist keeps the position.
                    Chip(t.label, tab == t, onClick = {
                        if (tab != t) {
                            tabOrdinal = t.ordinal
                            query = ""
                            scope.launch { listState.scrollToItem(0) }
                        }
                    })
                }
            },
        ) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                if (accounts.size > 1) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            accounts.forEach { a ->
                                Chip(
                                    a.displayName().ifBlank { "provider" },
                                    selected == a.id,
                                    onClick = {
                                        selected = a.id
                                        query = ""
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    CollectionHeader(
                        title = rootTitle,
                        meta = when {
                            pool.isNotEmpty() -> "${pool.size} songs"
                            ui.loading -> "loading…"
                            else -> ""
                        },
                        actions = {
                            CollectionActions(
                                shuffled = shuffled,
                                onPlayAll = { playAll(ordered) },
                                onShufflePlay = { shufflePlay(ordered) },
                            )
                        },
                        art = {
                            GroupArt(
                                songs = pool.take(4),
                                modifier = Modifier.fillMaxSize(),
                                fallbackKey = account?.id.orEmpty(),
                                fallbackName = rootTitle,
                            )
                        },
                    )
                }
                // Library picker and SSH rescan stay on the root. On the songs
                // tab the sort chips join them; albums and artists are already
                // grouped, exactly like the local-songs page.
                if (showControls) {
                    item {
                        ProviderSortRow(
                            sort = sort,
                            onSort = { sort = it },
                            rescanVisible = account?.providerKey == "ssh",
                            rescanning = indexState.scanning,
                            onRescan = {
                                account?.let { scope.launch { SftpLibrary.rescan(it) } }
                            },
                            libraries = libraries,
                            currentLibraryId = account?.values?.get("library"),
                            onPickLibrary = { libId ->
                                account?.let {
                                    vm.onEvent(
                                        ProviderSongsViewModel.Event.SetLibrary(it.id, libId)
                                    )
                                }
                                query = ""
                            },
                            showSort = showSort,
                        )
                    }
                }
                item { FilterRow(value = query, onValue = { query = it }) }
                when (tab) {
                    LocalSongsTab.Songs -> {
                        if (ordered.isEmpty()) {
                            item { ProviderEmpty(emptyLabel(loading = ui.loading, query = query)) }
                        } else {
                            // The local-songs page leads its rows with the list
                            // count; providers keep the same marker.
                            item { SectionLabel("$rootTitle — ${ordered.size}") }
                            items(ordered, key = { it.id }, contentType = { "provider-song" }) { s ->
                                ProviderSongRow(
                                    s = s,
                                    current = current,
                                    playing = playing,
                                    queue = ordered,
                                    onPlay = onPlay,
                                    onOpenMenu = { menuFor = it },
                                )
                            }
                        }
                    }
                    LocalSongsTab.Albums -> {
                        if (albumGroups.isEmpty()) {
                            item { ProviderEmpty(emptyLabel(loading = ui.loading, query = query)) }
                        } else {
                            items(albumGroups, key = { it.key }, contentType = { "provider-album" }) { group ->
                                ProviderAlbumRow(
                                    group = group,
                                    onOpen = { onOpenAlbum(group.name, group.albumArtist) },
                                )
                            }
                        }
                    }
                    LocalSongsTab.Artists -> {
                        if (artistGroups.isEmpty()) {
                            item { ProviderEmpty(emptyLabel(loading = ui.loading, query = query)) }
                        } else {
                            items(artistGroups, key = { it.key }, contentType = { "provider-artist" }) { group ->
                                ProviderArtistRow(
                                    group = group,
                                    onOpen = {
                                        onOpenArtist(group.name, group.coverSong?.url.orEmpty())
                                    },
                                )
                            }
                        }
                    }
                }
                if (failedLabels.isNotEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = Gutter, vertical = 8.dp)
                                .microPress { vm.onEvent(ProviderSongsViewModel.Event.Refresh) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Mono(
                                "couldn't reach ${failedLabels.joinToString(", ")} — tap to retry",
                                KleeampType.rowSecondary, p.destructiveInk,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }

            // The row menu as a bottom sheet. Provider tracks resolve in
            // info only once favourited, so the info entry is gated on it.
            menuFor?.let { s ->
                val fav = s.url in favorites
                ContextMenuSheet(
                    title = s.name,
                    subtitle = s.artistAlbum.ifBlank { s.meta.ifBlank { "provider" } },
                    art = { StationMenuArt(s) },
                    actions = menuActions(
                        MenuSubject(
                            kind = MenuKind.STATION,
                            favorite = fav,
                            infoAvailable = fav,
                            onPlayNext = { onPlayNext(s) },
                            onQueue = { onAddToQueue(s) },
                            onToggleFavorite = {
                                vm.onEvent(ProviderSongsViewModel.Event.ToggleFavorite(s))
                            },
                            onAddToPlaylist = { onAddToPlaylist(s) },
                            onInfo = if (fav) onInfo?.let { show -> { show(s) } } else null,
                        ),
                    ),
                    onDismiss = { menuFor = null },
                )
            }
        }
    }
}

/** One provider song row: cover, alias/album subtitle, overflow menu. */
@Composable
private fun ProviderSongRow(
    s: Station,
    current: Station?,
    playing: Boolean,
    queue: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenMenu: (Station) -> Unit,
) {
    PlaylistMemberRow(
        s = s,
        subtitle = s.artistAlbum.ifBlank { s.meta.ifBlank { "provider" } },
        current = current,
        playing = playing,
        queue = queue,
        onPlay = onPlay,
        onOpenMenu = onOpenMenu,
    )
}

/** One album row for the albums tab. */
@Composable
private fun ProviderAlbumRow(group: AlbumGroup, onOpen: () -> Unit) {
    val p = LocalPalette.current
    ListRow(
        onClick = onOpen,
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
                Icon(KleeampIcons.CaretRight, "open", Modifier.size(9.dp), tint = p.inkFaint)
            }
        },
    ) {
        Mono(group.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
        Mono(group.displayArtist, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

/** One artist row: the artist's first cover, song count and a drill caret. */
@Composable
private fun ProviderArtistRow(group: ArtistGroup, onOpen: () -> Unit) {
    val p = LocalPalette.current
    ListRow(
        onClick = onOpen,
        verticalPadding = 9.dp,
        leading = {
            GroupArt(
                songs = listOfNotNull(group.coverSong),
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
                Icon(KleeampIcons.CaretRight, "open", Modifier.size(9.dp), tint = p.inkFaint)
            }
        },
    ) {
        Mono(group.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
    }
}

@Composable
private fun ProviderEmpty(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Mono(text, KleeampType.rowSecondary, LocalPalette.current.inkFaint)
    }
}

private fun emptyLabel(loading: Boolean, query: String): String = when {
    query.isNotBlank() -> "nothing matches"
    loading -> "loading provider songs…"
    else -> "nothing here"
}

/**
 * The provider sort row: library picker and SSH rescan, then the songs-tab
 * sort chips. Pure state in, callbacks out, so previews can render it without
 * an account.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun ProviderSortRow(
    sort: PlaylistSort,
    onSort: (PlaylistSort) -> Unit,
    rescanVisible: Boolean,
    rescanning: Boolean,
    onRescan: () -> Unit,
    libraries: List<ProviderLibrary>,
    currentLibraryId: String?,
    onPickLibrary: (String) -> Unit,
    showSort: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rescanVisible) {
            Chip(
                if (rescanning) "scanning" else "rescan",
                selected = false,
                onClick = onRescan,
            )
        }
        if (libraries.size > 1) {
            val current = libraries.firstOrNull { it.id == currentLibraryId } ?: libraries.first()
            ChipDropdown(
                label = current.name,
                selected = currentLibraryId?.isNotBlank() == true,
                options = libraries.map { lib -> ChipOption(lib.name) { onPickLibrary(lib.id) } },
            )
        }
        if (showSort) {
            PlaylistSort.entries.forEach { s ->
                Chip(s.label, sort == s, onClick = { onSort(s) })
            }
        }
    }
}
