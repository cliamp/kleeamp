package stream.kleeamp.mobile.library

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderCatalog
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.servers.ProviderSpec
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.DestructiveAction
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

/**
 * One pinned smart playlist as a navigation pane. Derives its members from
 * the same sources as the library list so the tile collage and the detail
 * agree exactly.
 */

@Composable
fun LibrarySmartPlaylistPane(
    vm: SmartPlaylistViewModel,
    kindName: String,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    favScope: FavScope = FavScope.All,
    onFavScopeChange: (FavScope) -> Unit = {},
    onOpenSongInfo: (Station) -> Unit = {},
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Saved positions by station URL, for the resume readout on local rows. */
    progress: Map<String, EpisodeProgress> = emptyMap(),
    onAddToPlaylist: (Station) -> Unit = {},
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val kind = remember(kindName) {
        SmartKind.entries.firstOrNull { it.name == kindName }
    }
    val songs = ui.songs
    val loading = ui.loading
    val localSort = ui.localSort
    val detailSort = ui.detailSort
    val fetched = ui.fetched
    val favorites = ui.favorites
    // Recently-played re-sorts itself on every tap (the tap pushes history),
    // so a live list would jump under the finger and next/prev would chase a
    // moving order. Freeze the view on entry like a normal playlist - the
    // queue then matches exactly what is on screen, and the fresh order lands
    // on return. Other lists stay live; only history reorders on play.
    // Frozen inside SmartPlaylistViewModel to keep the queue stable.
    val viewRecent = ui.viewRecent
    val showResume = ui.resumeLocal
    var pendingDelete by remember { mutableStateOf<Station?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        pendingDelete?.let { s ->
            if (res.resultCode == Activity.RESULT_OK) {
                vm.onEvent(SmartPlaylistViewModel.Event.DeleteLocal(s))
            }
            pendingDelete = null
        }
    }
    val removeLocalSong: (Station) -> Unit = { s ->
        val start = vm.deleteRequest(s)
        if (start != null) {
            pendingDelete = s
            deleteLauncher.launch(IntentSenderRequest.Builder(start).build())
        } else {
            vm.onEvent(SmartPlaylistViewModel.Event.DeleteLocal(s))
        }
    }
    val smartPlaylists = remember(songs, favorites, viewRecent, localSort, favScope, fetched) {
        val local = sortedStations(songs, localSort)
        val favs = if (favScope == FavScope.All) favorites
        else favorites.filter { s ->
            when (favScope) {
                FavScope.Local -> s.source == StationSource.Local
                FavScope.Stations -> s.source != StationSource.Local && s.source != StationSource.Podcast
                FavScope.Pods -> s.source == StationSource.Podcast
                FavScope.All -> true
            }
        }
        listOf(
            SmartPlaylist(SmartKind.RecentlyPlayed, viewRecent),
            SmartPlaylist(SmartKind.Downloads, sortedStations(fetched.values.map { it.station }, localSort)),
            SmartPlaylist(SmartKind.Favorites, favs),
            SmartPlaylist(SmartKind.LocalSongs, local),
        )
    }
    val pl = smartPlaylists.firstOrNull { it.kind == kind }
    // Local songs filter by folder through a picker dropdown that leads the
    // sort row - one scrollable header row, no mode switching, no drill state.
    val folders = remember(pl?.stations) {
        if (pl?.kind == SmartKind.LocalSongs) foldersOf(pl.stations) else emptyList()
    }
    var folder by rememberSaveable(pl?.key ?: "playlist") { mutableStateOf<String?>(null) }
    // A deleted folder must not leave the filter pointing at nothing.
    LaunchedEffect(folders) {
        if (folder != null && folders.none { it.path == folder }) folder = null
    }
    val members = if (pl?.kind == SmartKind.LocalSongs && folder != null) {
        folders.firstOrNull { it.path == folder }?.songs.orEmpty()
    } else pl?.stations.orEmpty()
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    // The header carries no chip rows; pickers ride the scrolling rows
    // below, on top of the filter.
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        // Favorites grows through the same song picker playlists use; back
        // closes it, writes already landed per tap.
        var adding by rememberSaveable(pl?.key ?: "playlist") { mutableStateOf(false) }
        BackHandler(enabled = adding) { adding = false }
        MainLayout(
            title = pl?.label ?: "playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pl == null) {
                    CenterNote("no such playlist", p.inkTertiary)
                } else if (adding && pl.kind == SmartKind.Favorites) {
                    AddSongsPicker(
                        selected = favorites.map { it.id }.toSet(),
                        playlistName = pl.label,
                        localSongs = songs,
                        radioStations = ui.radioStations,
                        subscribedShows = ui.subscribedShows,
                        showState = ui.showState,
                        onOpenShow = { vm.onEvent(SmartPlaylistViewModel.Event.OpenShow(it)) },
                        onToggle = { vm.onEvent(SmartPlaylistViewModel.Event.ToggleFavorite(it)) },
                    )
                } else {
                    SmartPlaylistDetail(
                        listState = listState,
                        pl = pl,
                        members = members,
                        current = current,
                        playing = playing,
                        onPlay = onPlay,
                        onOpenMenu = { menuFor = it },
                        loading = loading,
                        favScope = favScope,
                        onFavScopeChange = onFavScopeChange,
                        onSortChange = { vm.onEvent(SmartPlaylistViewModel.Event.SetSort(it)) },
                        folders = folders,
                        folder = folder,
                        onFolder = { folder = it },
                        progress = progress,
                        showResume = showResume,
                        sort = detailSort,
                        fetchedBytes = fetched.mapValues { it.value.bytes },
                        onBeginAdd = if (pl.kind == SmartKind.Favorites) {
                            { adding = true }
                        } else null,
                    )
                }
            }
        }

        // The row menu as a bottom sheet: capabilities decide the rows, so
        // local files offer Delete, downloads offer remove-download, and
        // favourites/recent offer neither.
        menuFor?.let { s ->
            val downloaded = pl?.kind == SmartKind.Downloads
            ContextMenuSheet(
                title = s.name,
                subtitle = smartMenuSubtitle(s),
                art = { StationMenuArt(s) },
                actions = menuActions(
                    MenuSubject(
                        kind = MenuKind.STATION,
                        favorite = favorites.any { it.url == s.url },
                        infoAvailable = s.source != StationSource.Podcast,
                        destructive = when {
                            downloaded -> DestructiveAction(
                                "remove download",
                                "delete the fetched file",
                                {
                                    vm.onEvent(SmartPlaylistViewModel.Event.RemoveDownload(s))
                                },
                            )
                            s.source == StationSource.Local &&
                                pl?.kind == SmartKind.LocalSongs -> DestructiveAction(
                                "Delete",
                                "remove from this device",
                                { removeLocalSong(s) },
                            )
                            else -> null
                        },
                        onQueue = { onAddToQueue(s) },
                        onToggleFavorite = {
                            vm.onEvent(SmartPlaylistViewModel.Event.ToggleFavorite(s))
                        },
                        onAddToPlaylist = { onAddToPlaylist(s) },
                        onInfo = { onOpenSongInfo(s) },
                    ),
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

/**
 * The "providers" playlist: one chip per connected account, each showing
 * that account's songs in one flat list, sorted and filtered exactly like
 * the local-songs chips (title / artist / album / recently added, plus a
 * text filter). The + in the section header opens the providers pane to
 * connect another account - the add-playlist button's counterpart for
 * accounts. Rows play exactly like smart-playlist rows, favourites
 * included; provider tracks carry their own cover URLs and fall back to
 * the same plate local songs wear.
 *
 * The artist chip is a drill, not just a sort: it lists distinct artist
 * names first, picking one lists that artist's albums, and picking an
 * album lands on its songs - the same three-level shape as the browse
 * screen's node stack, but over the flat list already loaded for this
 * account rather than fresh server calls. The back chevron pops one
 * level of the drill before it ever reaches [onBack].
 */
@Composable
internal fun GroupList(
    items: List<Station>,
    query: String,
    onQuery: (String) -> Unit,
    picked: Set<String>,
    subtitle: (Station) -> String,
    onToggle: (Station) -> Unit,
    empty: String,
) {
    val p = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { FilterRow(value = query, onValue = onQuery) }
        if (items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(empty, KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            items(items, key = { it.id }) { s ->
                val inPl = s.id in picked
                ListRow(
                    onClick = { onToggle(s) },
                    verticalPadding = 8.dp,
                    leading = {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                .then(if (inPl) Modifier.background(p.accent)
                                      else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inPl) Icon(KleeampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                        }
                    },
                ) {
                    Mono(s.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(subtitle(s), KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}


@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun PodcastGroups(
    shows: List<PodcastShow>,
    query: String,
    onQuery: (String) -> Unit,
    openShow: PodcastShow?,
    onOpenShow: (PodcastShow) -> Unit,
    onBackToShows: () -> Unit,
    showState: ShowState,
    picked: Set<String>,
    onToggle: (Station) -> Unit,
) {
    val p = LocalPalette.current
    val show = openShow
    val q = query.trim().lowercase()
    if (show != null) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { FilterRow(value = query, onValue = onQuery) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Chip("‹ shows", selected = false, onClick = onBackToShows)
                    Spacer(Modifier.width(2.dp))
                    Mono(show.title, KleeampType.chip, p.ink, maxLines = 1)
                }
            }
            if (showState.loading) {
                item {
                    Mono(
                        "loading episodes…",
                        KleeampType.rowSecondary,
                        p.inkFaint,
                        Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                    )
                }
            } else {
                val eps = showState.episodes.filter {
                    it.isFull && (q.isBlank() || it.title.lowercase().contains(q))
                }
                items(eps, key = { "pod:${show.id}:${it.guid}" }) { e ->
                    val s = e.toStation(show)
                    val inPl = s.id in picked
                    ListRow(
                        onClick = { onToggle(s) },
                        verticalPadding = 8.dp,
                        leading = {
                            Box(
                                Modifier.size(24.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                    .then(if (inPl) Modifier.background(p.accent)
                                          else Modifier.border(
                                              1.dp,
                                              p.chipBorder,
                                              RoundedCornerShape(KleeampShape.tiny),
                                          )),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (inPl) Icon(KleeampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                            }
                        },
                    ) {
                        Mono(s.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                        Mono(
                            durationLabel(s.durationMs).ifBlank { "episode" },
                            KleeampType.rowSecondary,
                            p.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { FilterRow(value = query, onValue = onQuery) }
        if (shows.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono("no subscribed podcasts yet", KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            val shown = if (q.isBlank()) shows
            else shows.filter {
                it.title.lowercase().contains(q) || it.author.lowercase().contains(q)
            }
            items(shown, key = { it.feedUrl }) { show ->
                ListRow(
                    onClick = { onOpenShow(show) },
                    verticalPadding = 9.dp,
                    trailing = { Icon(KleeampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary) },
                ) {
                    Mono(show.title, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(show.meta, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** The sheet header line under the title: same words the smart rows wear. */
private fun smartMenuSubtitle(s: Station): String = buildList {
    when (s.source) {
        StationSource.Podcast -> add(s.artist.ifBlank { s.meta.ifBlank { "podcast" } })
        StationSource.Local -> add(s.artistAlbum.ifBlank { s.meta })
        else -> {
            s.meta.takeIf { it.isNotBlank() }?.let { add(it) }
            s.tagList.take(2).forEach { add(it) }
        }
    }
}.joinToString(" · ")

/** Detail view for a pinned smart playlist: every member station, local and radio. */

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
private fun SmartPlaylistDetail(
    listState: LazyListState,
    pl: SmartPlaylist,
    members: List<Station>,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenMenu: (Station) -> Unit = {},
    loading: Boolean = false,
    favScope: FavScope = FavScope.All,
    onFavScopeChange: (FavScope) -> Unit = {},
    onSortChange: (PlaylistSort) -> Unit = {},
    folders: List<SongFolder> = emptyList(),
    folder: String? = null,
    onFolder: (String?) -> Unit = {},
    progress: Map<String, EpisodeProgress> = emptyMap(),
    showResume: Boolean = false,
    sort: PlaylistSort = PlaylistSort.Title,
    fetchedBytes: Map<String, Long> = emptyMap(),
    /** Non-null on lists that can grow: renders the section + button. */
    onBeginAdd: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    // Only the on-device smart lists sort; favourites and recent have their
    // own fixed orders (recent is already time-sorted). Local songs arrive
    // already folder-filtered when a folder chip is active.
    val local = pl.kind == SmartKind.LocalSongs || pl.kind == SmartKind.Downloads
    // Favourites mix local songs, radio stations and podcasts, so they get
    // their own type sub-tabs: all / local / stations / podcasts.
    val isFav = pl.kind == SmartKind.Favorites
    val visible = remember(members, local, sort, isFav, favScope) {
        val base = if (local) sortedStations(members, sort) else members
        if (!isFav || favScope == FavScope.All) base
        else base.filter { s ->
            when (favScope) {
                FavScope.Local -> s.source == StationSource.Local
                FavScope.Stations -> s.source != StationSource.Local && s.source != StationSource.Podcast
                FavScope.Pods -> s.source == StationSource.Podcast
                FavScope.All -> true
            }
        }
    }
    var query by rememberSaveable(pl.key) { mutableStateOf("") }
    val shown = remember(visible, query) { visible.matching(query) }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        // Picker rows scroll with the list, on top of the filter; only the
        // header is fixed.
        if (isFav) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    FavScope.entries.forEach { f ->
                        Chip(f.label, favScope == f, onClick = { onFavScopeChange(f) })
                    }
                }
            }
        }
        if (local) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistSort.entries.forEach { t ->
                        Chip(t.label, sort == t, onClick = { onSortChange(t) })
                    }
                    // Local songs only: the folder picker trails the sort row.
                    if (pl.kind == SmartKind.LocalSongs && folders.isNotEmpty()) {
                        ChipDropdown(
                            label = folders.firstOrNull { it.path == folder }?.name ?: "all folders",
                            selected = folder != null,
                            options = listOf(
                                ChipOption("all folders") { onFolder(null) },
                            ) + folders.map { f ->
                                ChipOption(f.name) { onFolder(f.path) }
                            },
                        )
                    }
                }
            }
        }
        item { FilterRow(value = query, onValue = { query = it }) }
        if (shown.isEmpty()) {
            // Growable lists keep their + on empty too, like playlists do.
            if (onBeginAdd != null) {
                item {
                    SectionLabel("${pl.label} — 0") {
                        AddSongsButton(onClick = onBeginAdd)
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(
                        when {
                            query.isNotBlank() -> "nothing matches"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Local -> "no local favourites yet"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Stations ->
                                "no station favourites yet"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Pods -> "no podcast favourites yet"
                            pl.kind == SmartKind.LocalSongs ->
                                if (loading) "scanning for songs…" else "no songs on the phone yet"
                            pl.kind == SmartKind.Downloads ->
                                if (loading) "scanning for songs…" else "no downloads yet"
                            pl.kind == SmartKind.Favorites -> "no favourites yet"
                            else -> "nothing played recently"
                        },
                        KleeampType.rowSecondary, p.inkFaint,
                    )
                }
            }
        } else {
            item {
                SectionLabel("${pl.label} — ${shown.size}") {
                    onBeginAdd?.let { AddSongsButton(onClick = it) }
                }
            }
            items(shown, key = { it.url }, contentType = { "local-song" }) { s ->
                ListRow(
                    rail = current?.url == s.url,
                    onClick = { onPlay(s, shown) },
                    verticalPadding = 9.dp,
                    leading = {
                        SongCover(s = s, current = current, playing = playing)
                    },
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OverflowButton({ onOpenMenu(s) }, size = 16)
                        }
                    },
                ) {
                    Mono(s.name, KleeampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                    // Local rows wear their saved position like podcast
                    // episodes do, but only while resume is switched on.
                    val resumed = if (local && showResume) {
                        progress[s.url]?.takeIf { !it.completed && it.positionMs > 0 }
                    } else null
                    Mono(
                        buildList {
                            when (s.source) {
                                StationSource.Podcast -> add(s.artist.ifBlank { s.meta.ifBlank { "podcast" } })
                                StationSource.Local -> add(s.artistAlbum.ifBlank { s.meta })
                                else -> {
                                    s.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                                    s.tagList.take(2).forEach { add(it) }
                                }
                            }
                            resumed?.let { add("${(it.fraction * 100).toInt()}% in") }
                            if (pl.kind == SmartKind.Downloads) {
                                fetchedBytes[s.url]?.let { add(downloadSizeLabel(it)) }
                            }
                        }.joinToString(" · "),
                        KleeampType.rowSecondary, if (resumed != null) p.amber else p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
