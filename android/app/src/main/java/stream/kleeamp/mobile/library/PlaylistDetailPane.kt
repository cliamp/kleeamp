package stream.kleeamp.mobile.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderCatalog
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.servers.ProviderSpec
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.CollectionActions
import stream.kleeamp.mobile.chrome.CollectionHeader
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.MenuAction
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
import stream.kleeamp.mobile.chrome.NoticeBus
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

/** Playlist maintenance: dedupe and missing-file purge. */
@Composable
private fun PlaylistToolsMenu(
    playlistName: String,
    state: PlaylistToolsState,
    onDedupe: () -> Unit,
    onPurgeMissing: () -> Unit,
) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val actions = buildList {
        if (state.dupCount > 0) {
            add(
                MenuAction(
                    id = "dedupe",
                    label = "remove ${state.dupCount} duplicates",
                    subtitle = "keep the first copy of each song",
                    onClick = onDedupe,
                )
            )
        }
        if (state.missingCount > 0) {
            add(
                MenuAction(
                    id = "purge",
                    label = "remove ${state.missingCount} missing",
                    subtitle = "drop files no longer on the phone",
                    onClick = onPurgeMissing,
                )
            )
        }
    }
    if (actions.isEmpty()) return
    OverflowButton({ open = true }, size = 16)
    if (open) {
        ContextMenuSheet(
            title = playlistName,
            subtitle = "playlist tools",
            art = { PlaylistGlyph(KleeampIcons.ListShort, playlistName) },
            actions = actions,
            onDismiss = { open = false },
        )
    }
}

/** Inputs for [PlaylistToolsMenu], read off the detail state. */
private data class PlaylistToolsState(
    val dupCount: Int,
    val missingCount: Int,
)

/** One user playlist as a navigation pane, with add-songs and cover editing. */

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun LibraryPlaylistPane(
    vm: PlaylistDetailViewModel,
    slug: String,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onAddToQueue: (Station) -> Unit = {},
    onInfo: ((Station) -> Unit)? = null,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    /** Opens the add-songs sheet over this page; the sheet writes per tap. */
    onOpenAddSongs: () -> Unit = {},
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by rememberSaveable(slug) { mutableStateOf("") }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    val ui by vm.state.collectAsState()
    val pl = ui.playlist
    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep read access past this process: a GetContent grant dies with
        // it, which is what used to blank the cover on the next launch.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        vm.onEvent(PlaylistDetailViewModel.Event.SetCover(uri.toString()))
    }
    // Exported M3U text waiting for the save picker: generated on tap,
    // written when the user picks a destination.
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(pending.second.toByteArray())
            } ?: error("no stream")
        }.onSuccess { NoticeBus.show("saved ${pending.first}.m3u") }
            .onFailure { NoticeBus.show("export failed", error = true) }
    }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val listState = rememberLazyListState()
        MainLayout(
            title = pl?.station?.name ?: "playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
            chips = if (pl != null) {
                @Composable {
                    Chip("set cover", selected = false, onClick = { coverLauncher.launch(arrayOf("image/*")) })
                    if (ui.members.isNotEmpty()) {
                        Chip(
                            "export",
                            selected = false,
                            onClick = {
                                scope.launch {
                                    val text = vm.exportM3u() ?: return@launch
                                    val name = pl.station.name.ifBlank { "playlist" }
                                        .replace('/', '_')
                                    pendingExport = name to text
                                    exportLauncher.launch("$name.m3u")
                                }
                            },
                        )
                    }
                    PlaylistToolsMenu(
                        playlistName = pl.station.name,
                        state = PlaylistToolsState(
                            dupCount = ui.dupCount,
                            missingCount = ui.missingCount,
                        ),
                        onDedupe = { vm.onEvent(PlaylistDetailViewModel.Event.Dedupe) },
                        onPurgeMissing = { vm.onEvent(PlaylistDetailViewModel.Event.PurgeMissing) },
                    )
                }
            } else null,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pl == null) {
                    CenterNote("playlist gone", p.inkTertiary)
                } else {
                    PlaylistDetailShown(
                        listState = listState,
                        playlist = pl,
                        members = ui.members,
                        visible = remember(ui.visible, query) { ui.visible.matching(query) },
                        query = query,
                        onQuery = { query = it },
                        sort = ui.sort,
                        onSortChange = { vm.onEvent(PlaylistDetailViewModel.Event.SetSort(it)) },
                        browse = ui.browse,
                        onBrowseChange = { vm.onEvent(PlaylistDetailViewModel.Event.SetBrowse(it)) },
                        onOpenArtist = onOpenArtist,
                        current = current,
                        playing = playing,
                        onPlay = onPlay,
                        onOpenMenu = { menuFor = it },
                        onBeginAdd = onOpenAddSongs,
                        shuffled = shuffled,
                        onToggleShuffle = onToggleShuffle,
                    )
                }

                // The row menu as a bottom sheet: favourites, playlists,
                // queue, info where it resolves, and drop from the list.
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
                                destructive = DestructiveAction(
                                    "drop",
                                    "remove from this playlist",
                                    { vm.onEvent(PlaylistDetailViewModel.Event.RemoveMember(s)) },
                                ),
                                onQueue = { onAddToQueue(s) },
                                onToggleFavorite = {
                                    vm.onEvent(PlaylistDetailViewModel.Event.ToggleFavorite(s))
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

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
private fun PlaylistDetailShown(
    listState: LazyListState,
    playlist: PlaylistStore.Playlist,
    members: List<Station>,
    visible: List<Station>,
    query: String,
    onQuery: (String) -> Unit,
    sort: PlaylistSort,
    onSortChange: (PlaylistSort) -> Unit,
    browse: Boolean = false,
    onBrowseChange: (Boolean) -> Unit = {},
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onBeginAdd: () -> Unit = {},
    onOpenMenu: (Station) -> Unit = {},
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()

    // Browse regroups the members under album rows in one flat scroll —
    // no pages, no back handling. Browse and the sorts share one active
    // state with a local override each until the stored value round-trips
    // back, so every chip answers on the tap frame exactly like the rest.
    var sortOverride by remember(playlist.station.slug) { mutableStateOf<PlaylistSort?>(null) }
    var browseOverride by remember(playlist.station.slug) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(sort) { sortOverride = null }
    LaunchedEffect(browse) { browseOverride = null }
    val shownSort = sortOverride ?: sort
    val shownBrowse = browseOverride ?: browse
    // The tapped sort applies on the tap frame: the stored sort lands a
    // beat later through the ViewModel, and waiting for it flashes the old
    // order under the newly lit chip — worst when leaving browse, where
    // the artists give way to the wrong order first. Re-sorting the
    // already-sorted visible list is idempotent once it round-trips.
    val ordered = remember(visible, shownSort) { sortedStations(visible, shownSort) }
    // Browse lists artists only — one row per artist (track or album
    // artist, like the library tab), tap opens their page — cheapest last
    // so the flat list never waits for it.
    val artists = remember(ordered, shownBrowse) {
        if (!shownBrowse) emptyList() else artistsOf(ordered)
    }
    // Artists and songs share one scroll state: the position survives detail
    // navigation, and only a mode switch (at the chips below) snaps back to
    // the top. Sort taps keep their position.

    // Members can be any source now, so they resolve against the live local
    // library plus the persisted snapshot stations (radio/podcast members).
    // Resolved in PlaylistDetailViewModel so the pane stays dumb.
    // Adding lives in the add-songs sheet, never as page content.
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            val cover = rememberArt(station = playlist.station, kind = ArtKind.Full)
            CollectionHeader(
                title = playlist.station.name,
                meta = "${members.size} songs",
                actions = {
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
                },
                art = {
                    if (cover != null) {
                        Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        SeedPlate(
                            key = playlist.station.slug,
                            name = playlist.station.name,
                            modifier = Modifier.fillMaxSize(),
                            radius = KleeampShape.small,
                        )
                    }
                },
            )
        }
        if (members.isEmpty()) {
            item {
                SectionLabel("songs — 0") {
                    AddSongsButton(onClick = onBeginAdd)
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono("empty — tap +", KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            // Picker row scrolls with the list, on top of the filter; only
            // the header is fixed.
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    // Sorts first as one family; browse caps the row as the
                    // mode switch. Every chip taps the same way: it selects
                    // itself and nothing else.
                    PlaylistSort.entries.forEach { t ->
                        Chip(t.label, shownSort == t && !shownBrowse, onClick = {
                            // Leaving browse swaps the whole content, so only
                            // the mode switch snaps back; re-sorting the songs
                            // keeps the position, as does coming back here.
                            if (shownBrowse) scope.launch { listState.scrollToItem(0) }
                            browseOverride = false
                            onBrowseChange(false)
                            sortOverride = t
                            onSortChange(t)
                        })
                    }
                    Chip("browse", shownBrowse, onClick = {
                        if (!shownBrowse) scope.launch { listState.scrollToItem(0) }
                        browseOverride = true
                        onBrowseChange(true)
                    })
                }
            }
            item { FilterRow(value = query, onValue = onQuery) }
            if (shownBrowse && ordered.isNotEmpty()) {
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
            } else {
            item {
                SectionLabel("songs — ${ordered.size}") {
                    AddSongsButton(onClick = onBeginAdd)
                }
            }
            if (ordered.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Mono("nothing matches", KleeampType.rowSecondary, p.inkFaint)
                    }
                }
            }
            items(ordered, key = { it.id }) { s ->
                PlaylistMemberRow(
                    s = s,
                    current = current,
                    playing = playing,
                    queue = ordered,
                    onPlay = onPlay,
                    onOpenMenu = onOpenMenu,
                )
            }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private enum class AddTab(val label: String) { Local("local"), Stations("stations"), Podcasts("podcasts") }

/** The boxed + that opens a song picker, shared by every songs section. */
@Composable
internal fun AddSongsButton(onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(if (p.dark) p.keyFace else p.ground)
            .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KleeampIcons.Plus, "add songs", Modifier.size(16.dp), tint = p.accent)
    }
}

/**
 * A song picker over local songs, radio stations and podcast episodes in
 * their own sub-tabs, with one shared selection carried across all of them.
 * Every tap writes through immediately; leaving (back or another page) keeps
 * everything, so there is no done control.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun AddSongsPicker(
    selected: Set<String>,
    playlistName: String,
    showTitle: Boolean = true,
    localSongs: List<Station>,
    radioStations: List<Station>,
    subscribedShows: List<PodcastShow>,
    showState: ShowState,
    onOpenShow: (PodcastShow) -> Unit,
    onToggle: (Station) -> Unit,
) {
    val p = LocalPalette.current
    var tab by remember { mutableStateOf(AddTab.Local) }
    var query by remember { mutableStateOf("") }
    var openShow by remember { mutableStateOf<PodcastShow?>(null) }

    // Taps carry the song only: direction resolves store-side from fresh
    // state, so a tap can never add-then-remove on a stale snapshot.
    // Checked state still reads the live membership, so rotation or a write
    // from elsewhere can never desync the boxes from the database.

    Column(Modifier.fillMaxSize()) {
        if (showTitle) {
            Mono(
                playlistName, KleeampType.chip, p.accent, maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AddTab.entries.forEach { t -> Chip(t.label, tab == t, onClick = { tab = t; query = "" }) }
        }
        HairlineDivider(region = true)

        when (tab) {
            AddTab.Local -> GroupList(
                items = localSongs.matching(query),
                query = query,
                onQuery = { query = it },
                picked = selected,
                subtitle = { s ->
                    s.artistAlbum.ifBlank { durationLabel(s.durationMs) }
                },
                onToggle = onToggle,
                empty = "no local songs yet",
            )
            AddTab.Stations -> GroupList(
                items = radioStations.matching(query),
                query = query,
                onQuery = { query = it },
                picked = selected,
                subtitle = { s -> s.meta },
                onToggle = onToggle,
                empty = "no stations to add",
            )
            AddTab.Podcasts -> PodcastGroups(
                shows = subscribedShows,
                query = query,
                onQuery = { query = it },
                openShow = openShow,
                onOpenShow = { show ->
                    openShow = show
                    onOpenShow(show)
                },
                onBackToShows = { openShow = null },
                showState = showState,
                picked = selected,
                onToggle = onToggle,
            )
        }
    }
}
