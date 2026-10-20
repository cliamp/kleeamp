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
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.podcasts.DownloadQueueItem
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.DownloadEntry
import stream.kleeamp.mobile.podcasts.sortedByQueueOrder
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderCatalog
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.servers.ProviderSpec
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.CollectionActions
import stream.kleeamp.mobile.chrome.CollectionHeader
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.LoadingNote
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
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
    /** Opens the add-songs sheet over this page; the sheet writes per tap. */
    onOpenAddSongs: () -> Unit = {},
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
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
    val members = pl?.stations.orEmpty()
    // Local-songs categories live pinned in the fixed header below the title,
    // so they never scroll away. Hoisted here (not in the detail) because the
    // header owns that row.
    val isLocalSongsPage = pl?.kind == SmartKind.LocalSongs
    var tabOrdinal by rememberSaveable(pl?.key ?: "playlist") { mutableStateOf(0) }
    val tab = LocalSongsTab.entries.getOrElse(tabOrdinal) { LocalSongsTab.Songs }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    // The header carries the category chips on local songs; sorts ride the
    // scrolling row below, on top of the filter.
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        MainLayout(
            title = pl?.label ?: "playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
            chips = if (!isLocalSongsPage) null else ({
                LocalSongsTab.entries.forEach { t ->
                    // The snap lives on the tap, never in an effect: coming
                    // back from an album/artist/song keeps the position.
                    Chip(t.label, tab == t, onClick = {
                        if (tab != t) {
                            tabOrdinal = t.ordinal
                            scope.launch { listState.scrollToItem(0) }
                        }
                    })
                }
            }),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pl == null) {
                    CenterNote("no such playlist", p.inkTertiary)
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
                        progress = progress,
                        showResume = showResume,
                        sort = detailSort,
                        tab = tab,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                        fetchedBytes = fetched.mapValues { it.value.bytes },
                        dlEntries = fetched,
                        queue = ui.queue,
                        onRetryDownload = { s, auto ->
                            vm.onEvent(SmartPlaylistViewModel.Event.RetryDownload(s, auto))
                        },
                        onPauseDownload = {
                            vm.onEvent(SmartPlaylistViewModel.Event.PauseDownload(it))
                        },
                        onResumeDownload = {
                            vm.onEvent(SmartPlaylistViewModel.Event.ResumeDownload(it))
                        },
                        onCancelDownload = {
                            vm.onEvent(SmartPlaylistViewModel.Event.CancelDownload(it))
                        },
                        onCancelAllDownloads = {
                            vm.onEvent(SmartPlaylistViewModel.Event.CancelAllDownloads)
                        },
                        onRemoveDownloads = { urls ->
                            vm.onEvent(SmartPlaylistViewModel.Event.RemoveDownloads(urls))
                        },
                        onBeginAdd = if (pl.kind == SmartKind.Favorites) {
                            onOpenAddSongs
                        } else null,
                        shuffled = shuffled,
                        onToggleShuffle = onToggleShuffle,
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
                    // The real row's cover stays; the box rides trailing.
                    leading = {
                        SongCover(s = s, current = null, playing = false)
                    },
                    trailing = {
                        PickBox(
                            checked = inPl,
                            description = if (inPl) "remove ${s.name}" else "add ${s.name}",
                            onToggle = { onToggle(s) },
                        )
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
                        // The real row's cover stays; the box rides trailing.
                        leading = {
                            SongCover(s = s, current = null, playing = false)
                        },
                        trailing = {
                            PickBox(
                                checked = inPl,
                                description = if (inPl) "remove ${s.name}" else "add ${s.name}",
                                onToggle = { onToggle(s) },
                            )
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

/** The downloads trash key: same 40dp target and 15dp icon as the shuffle key. It opens the remove picker instead of dropping everything. */
@Composable
private fun PickDownloadsAction(picking: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            KleeampIcons.Trash,
            if (picking) "close the remove picker" else "choose downloads to remove",
            Modifier.size(15.dp),
            tint = if (picking) p.accent else p.inkSecondary,
        )
    }
}

/** The picker checkbox: the same 24dp box the song picker wears. */
@Composable
internal fun PickBox(checked: Boolean, description: String, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(KleeampShape.tiny))
            .then(
                if (checked) Modifier.background(p.accent)
                else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))
            )
            .microPress(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(KleeampIcons.Check, description, Modifier.size(10.dp), tint = p.onAccent)
    }
}

/** The sheet header line under the title: same words the smart rows wear. */
private fun smartMenuSubtitle(s: Station): String = buildList {    when (s.source) {
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
    progress: Map<String, EpisodeProgress> = emptyMap(),
    showResume: Boolean = false,
    /** Null when the user never picked a sort: downloads read in episode order. */
    sort: PlaylistSort? = null,
    tab: LocalSongsTab = LocalSongsTab.Songs,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
    fetchedBytes: Map<String, Long> = emptyMap(),
    /** Finished files by URL: carries the queue ticket the finished list sorts by. */
    dlEntries: Map<String, DownloadEntry> = emptyMap(),
    /** Active, queued, paused and failed fetches; shown as a section on downloads. */
    queue: List<DownloadQueueItem> = emptyList(),
    onRetryDownload: (Station, Boolean) -> Unit = { _, _ -> },
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onCancelAllDownloads: () -> Unit = {},
    onRemoveDownloads: (Set<String>) -> Unit = {},
    /** Non-null on lists that can grow: renders the section + button. */
    onBeginAdd: (() -> Unit)? = null,
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
) {
    val p = LocalPalette.current
    // Only the on-device smart lists sort; favourites and recent have their
    // own fixed orders (recent is already time-sorted).
    val local = pl.kind == SmartKind.LocalSongs || pl.kind == SmartKind.Downloads
    // Favourites mix local songs, radio stations and podcasts, so they get
    // their own type sub-tabs: all / local / stations / podcasts.
    val isFav = pl.kind == SmartKind.Favorites
    val isDownloads = pl.kind == SmartKind.Downloads
    val visible = remember(members, local, sort, isDownloads, dlEntries, isFav, favScope) {
        val base = when {
            // Downloads always read in episode (queue) order from the
            // first frame; a session override cannot exist without chips.
            isDownloads -> members.sortedByQueueOrder(dlEntries)
            local -> sortedStations(members, sort ?: PlaylistSort.Title)
            else -> members
        }
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
    // Local Songs sub-tabs (songs/albums/artists) switch the list content.
    // The tab row itself lives pinned in the fixed header; sorts live on
    // songs only. Tab switches snap back to the top, sort taps keep position.
    val isLocalSongs = pl.kind == SmartKind.LocalSongs
    var sortOverride by remember(pl.key) { mutableStateOf<PlaylistSort?>(null) }
    // Downloads only: the trash opens a remove picker instead of dropping
    // everything. The all box starts unchecked and follows the rows both
    // ways; done removes the checked files and closes the picker.
    var picking by remember(pl.key) { mutableStateOf(false) }
    var picked by remember(pl.key) { mutableStateOf(setOf<String>()) }
    BackHandler(enabled = picking) {
        picking = false
        picked = emptySet()
    }
    LaunchedEffect(sort) { sortOverride = null }
    // Null until the user picks a sort: every list but downloads reads in
    // title order as before. Downloads always read in episode order (see
    // orderByQueue): sort chips would only reintroduce the title scramble.
    val shownSort: PlaylistSort? = sortOverride ?: sort ?: if (isDownloads) null else PlaylistSort.Title
    // Downloads always read in episode (queue) order, even with a stored
    // sort from before: a tapped title can never trap the list again.
    val orderByQueue = isDownloads
    // One row per file: a repeated URL (re-downloaded episode, MediaStore
    // duplicate) used to crash the list on a duplicate key. First wins.
    val shown = remember(visible, query) { visible.matching(query).distinctBy { it.url } }
    // Same tap-frame ordering as user playlists — but only the on-device
    // lists wear sorts; favourites and recent keep their fixed orders.
    // Re-sorting the already-sorted shown list is idempotent on round-trip.
    val ordered = remember(shown, shownSort, local, orderByQueue, dlEntries) {
        when {
            orderByQueue -> shown.sortedByQueueOrder(dlEntries)
            local -> sortedStations(shown, shownSort ?: PlaylistSort.Title)
            else -> shown
        }
    }
    // The picker only ever touches live rows: removals landing mid-pick
    // fall out of the checked set on the next frame by themselves.
    val urls = remember(ordered) { ordered.map { it.url }.toSet() }
    val livePicked = remember(picked, urls) { picked intersect urls }
    val allChecked = urls.isNotEmpty() && livePicked.size == urls.size
    // Album and artist categories group the same folder+query-filtered songs;
    // cheapest last so the flat list never waits for them.
    val albums = remember(ordered, tab, isLocalSongs) {
        if (!isLocalSongs || tab != LocalSongsTab.Albums) emptyList() else albumsOf(ordered)
    }
    val artists = remember(ordered, tab, isLocalSongs) {
        if (!isLocalSongs || tab != LocalSongsTab.Artists) emptyList() else artistsOf(ordered)
    }
    // Songs, albums and artists share one scroll state: the position survives
    // detail navigation, and only a tab tap snaps back to the top — at the
    // header chips above. Sort taps keep their position.
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            CollectionHeader(
                title = pl.label,
                meta = "${members.size} items",
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Always on, even empty: the taps no-op on nothing.
                        CollectionActions(
                            shuffled = shuffled,
                            onPlayAll = { ordered.firstOrNull()?.let { onPlay(it, ordered) } },
                            onShufflePlay = {
                                if (!shuffled) {
                                    onToggleShuffle()
                                    ordered.randomOrNull()?.let { onPlay(it, ordered) }
                                } else {
                                    // Second tap turns it off; the current track keeps playing.
                                    onToggleShuffle()
                                }
                            },
                        )
                        // Downloads only: a trash key beside shuffle that
                        // opens the remove picker; back or a second tap
                        // closes it without removing anything.
                        if (isDownloads && members.isNotEmpty()) {
                            PickDownloadsAction(picking = picking, onClick = {
                                if (picking) {
                                    picking = false
                                    picked = emptySet()
                                } else {
                                    picking = true
                                }
                            })
                        }
                    }
                },
                art = {
                    SeedPlate(
                        key = pl.key,
                        name = pl.label,
                        icon = smartKindIcon(pl.kind),
                        modifier = Modifier.fillMaxSize(),
                        radius = KleeampShape.small,
                    )
                },
            )
        }
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
        // Sorts + folder ride the scrolling rows on top of the filter; the
        // Sorts ride the scrolling row on top of the filter on songs only;
        // categories live pinned in the fixed header. Downloads always read
        // in episode order, so chips there would only reintroduce the title
        // scramble.
        if (isLocalSongs && tab == LocalSongsTab.Songs) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistSort.entries.forEach { t ->
                        Chip(t.label, shownSort == t, onClick = {
                            sortOverride = t
                            onSortChange(t)
                        })
                    }
                }
            }
        }
        item { FilterRow(value = query, onValue = { query = it }) }
        // The downloading view: every active, queued, paused and failed
        // fetch with its status, ahead of the finished files. Retry, pause,
        // resume and cancel ride the rows; cancel all clears the whole queue.
        if (pl.kind == SmartKind.Downloads) {
            downloadQueueSection(
                queue = queue,
                onRetryDownload = onRetryDownload,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onCancelDownload = onCancelDownload,
                onCancelAllDownloads = onCancelAllDownloads,
            )
        }
        if (isLocalSongs && tab == LocalSongsTab.Albums && ordered.isNotEmpty()) {
            items(albums, key = { it.key }) { group ->
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
                            Icon(KleeampIcons.CaretRight, "open", Modifier.size(9.dp), tint = p.inkFaint)
                        }
                    },
                ) {
                    Mono(group.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(group.displayArtist, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        } else if (isLocalSongs && tab == LocalSongsTab.Artists && ordered.isNotEmpty()) {
            items(artists, key = { it.key }) { group ->
                ListRow(
                    onClick = { onOpenArtist(group.name, group.coverSong?.url.orEmpty()) },
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
        } else if (ordered.isEmpty()) {
            // Growable lists keep their + on empty too, like playlists do.
            if (onBeginAdd != null) {
                item {
                    SectionLabel("${pl.label} — 0") {
                        AddSongsButton(onClick = onBeginAdd)
                    }
                }
            }
            item {
                // Scanning wears the spinner; everything else is a plain note.
                val scanning = query.isBlank() && loading &&
                    (pl.kind == SmartKind.LocalSongs || pl.kind == SmartKind.Downloads)
                if (scanning) {
                    LoadingNote("scanning for songs…")
                } else {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Mono(
                            when {
                                query.isNotBlank() -> "nothing matches"
                                pl.kind == SmartKind.Favorites && favScope == FavScope.Local -> "no local favourites yet"
                                pl.kind == SmartKind.Favorites && favScope == FavScope.Stations ->
                                    "no station favourites yet"
                                pl.kind == SmartKind.Favorites && favScope == FavScope.Pods -> "no podcast favourites yet"
                                pl.kind == SmartKind.LocalSongs -> "no songs on the phone yet"
                                pl.kind == SmartKind.Downloads -> "no downloads yet"
                                pl.kind == SmartKind.Favorites -> "no favourites yet"
                                else -> "nothing played recently"
                            },
                            KleeampType.rowSecondary, p.inkFaint,
                        )
                    }
                }
            }
        } else {
            item {
                // Picking swaps the count header for the picker header: an
                // unchecked-until-tapped all box, then the done chip that
                // removes exactly the checked files.
                if (isDownloads && picking) {
                    SectionLabel("remove downloads — ${livePicked.size}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PickBox(
                                checked = allChecked,
                                description = if (allChecked) "uncheck all" else "check all",
                                onToggle = { picked = if (allChecked) emptySet() else urls },
                            )
                            Mono(
                                "all",
                                KleeampType.chip,
                                p.inkTertiary,
                                maxLines = 1,
                                modifier = Modifier.microPress(onClick = {
                                    picked = if (allChecked) emptySet() else urls
                                }),
                            )
                            Chip(
                                "done",
                                selected = livePicked.isNotEmpty(),
                                onClick = {
                                    if (livePicked.isNotEmpty()) onRemoveDownloads(livePicked)
                                    picking = false
                                    picked = emptySet()
                                },
                            )
                        }
                    }
                } else {
                    SectionLabel("${pl.label} — ${ordered.size}") {
                        onBeginAdd?.let { AddSongsButton(onClick = it) }
                    }
                }
            }
            items(ordered, key = { it.url }, contentType = { "local-song" }) { s ->
                val isPicked = s.url in livePicked
                SmartSongRow(
                    s = s,
                    current = current,
                    playing = playing,
                    queue = ordered,
                    onPlay = onPlay,
                    onOpenMenu = onOpenMenu,
                    progress = progress,
                    trackProgress = local && showResume,
                    showBytes = isDownloads,
                    fetchedBytes = fetchedBytes,
                    picking = isDownloads && picking,
                    picked = isPicked,
                    onTogglePick = {
                        picked = if (isPicked) picked - s.url else picked + s.url
                    },
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/**
 * One song row, shared by the flat list and the album/artist sections so both
 * read identically: cover, overflow menu, and the resume/size readouts the
 * flat rows already wear.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
private fun SmartSongRow(
    s: Station,
    current: Station?,
    playing: Boolean,
    queue: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenMenu: (Station) -> Unit,
    progress: Map<String, EpisodeProgress>,
    trackProgress: Boolean,
    showBytes: Boolean,
    fetchedBytes: Map<String, Long>,
    picking: Boolean = false,
    picked: Boolean = false,
    onTogglePick: () -> Unit = {},
) {
    val p = LocalPalette.current
    ListRow(
        rail = current?.url == s.url,
        onClick = { if (picking) onTogglePick() else onPlay(s, queue) },
        verticalPadding = 9.dp,
        // Picking keeps the cover like the real row; the box rides the
        // trailing end where the overflow menu was, and the row tap
        // toggles instead of playing.
        leading = {
            SongCover(s = s, current = current, playing = playing)
        },
        trailing = {
            if (picking) {
                PickBox(
                    checked = picked,
                    description = if (picked) "uncheck ${s.name}" else "check ${s.name}",
                    onToggle = onTogglePick,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OverflowButton({ onOpenMenu(s) }, size = 16)
                }
            }
        },
    ) {
        Mono(s.name, KleeampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
        // Local rows wear their saved position like podcast
        // episodes do, but only while resume is switched on.
        val resumed = if (trackProgress) {
            progress[s.url]?.takeIf { !it.completed && it.positionMs > 0 }
        } else null
        Mono(
            buildList {
                when (s.source) {
                    StationSource.Podcast -> add(s.artist.ifBlank { s.meta.ifBlank { "podcast" } })
                    StationSource.Local, StationSource.Provider -> add(s.artistAlbum.ifBlank { s.meta })
                    else -> {
                        s.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                        s.tagList.take(2).forEach { add(it) }
                    }
                }
                resumed?.let { add("${(it.fraction * 100).toInt()}% in") }
                if (showBytes) {
                    fetchedBytes[s.url]?.let { add(downloadSizeLabel(it)) }
                }
            }.joinToString(" · "),
            KleeampType.rowSecondary, if (resumed != null) p.amber else p.inkTertiary, maxLines = 1,
        )
    }
}
