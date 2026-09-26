package stream.kleeamp.mobile.servers



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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import stream.kleeamp.mobile.library.LocalLibrary
import stream.kleeamp.mobile.library.PlaylistStore
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.library.PlaylistGlyph
import stream.kleeamp.mobile.library.SongCover
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.library.durationLabel
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.ContextMenuSheet
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


/** One provider album row for the providers drill: name, song count, year. */
private data class ProviderAlbumRow(val name: String, val count: Int, val year: Int)

@Composable
fun ProviderSongsPane(
    vm: ProviderSongsViewModel,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onInfo: ((Station) -> Unit)? = null,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Non-blank locks the pane to one account with no picker row. */
    accountId: String = "",
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    // A locked pane shows that account only, with no picker: the row it came
    // from already named it.
    val accounts = remember(ui.accounts, accountId) {
        if (accountId.isBlank()) ui.accounts
        else ui.accounts.filter { it.id == accountId }
    }
    var selected by rememberSaveable(accountId) { mutableStateOf(accounts.firstOrNull()?.id) }
    // A removed account must not leave the filter pointing at nothing.
    LaunchedEffect(accounts) {
        if (accounts.none { it.id == selected }) selected = accounts.firstOrNull()?.id
    }
    var sort by rememberSaveable { mutableStateOf(PlaylistSort.Title) }
    var query by rememberSaveable { mutableStateOf("") }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    var selectedArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    val pool = ui.songsByAccount[selected].orEmpty()
    val browsingArtists = sort == PlaylistSort.Artist && selectedArtist == null
    val browsingAlbums = sort == PlaylistSort.Artist && selectedArtist != null && selectedAlbum == null
    val artistRows = remember(pool, sort, query) {
        if (sort != PlaylistSort.Artist) emptyList() else {
            val q = query.trim().lowercase()
            pool.groupBy { it.artist.ifBlank { "unknown artist" } }
                .filterKeys { q.isBlank() || it.lowercase().contains(q) }
                .toSortedMap(compareBy { it.lowercase() })
                .map { (name, songs) -> name to songs.size }
        }
    }
    val albumYears = ui.albumYearsByAccount[selected].orEmpty()
    val albumRows = remember(pool, sort, selectedArtist, query, albumYears) {
        val artist = selectedArtist
        if (!browsingAlbums || artist == null) emptyList() else {
            val q = query.trim().lowercase()
            pool.filter { it.artist.ifBlank { "unknown artist" } == artist }
                .groupBy { it.album.ifBlank { "unknown album" } }
                .filterKeys { q.isBlank() || it.lowercase().contains(q) }
                .map { (name, songs) ->
                    ProviderAlbumRow(name, songs.size, albumYears[albumYearKey(artist, name)] ?: 0)
                }
                // Chronological, oldest first; albums with no known year sort
                // last by name rather than pretending to be from year zero.
                .sortedWith(
                    compareBy<ProviderAlbumRow> { it.year <= 0 }
                        .thenBy { it.year }
                        .thenBy { it.name.lowercase() },
                )
        }
    }
    val visible = remember(pool, sort, query, selectedArtist, selectedAlbum) {
        val songsOfAlbum = sort == PlaylistSort.Artist && selectedArtist != null && selectedAlbum != null
        val scoped = when {
            songsOfAlbum ->
                pool.filter {
                    it.artist.ifBlank { "unknown artist" } == selectedArtist &&
                        it.album.ifBlank { "unknown album" } == selectedAlbum
                }
            sort != PlaylistSort.Artist -> pool
            else -> emptyList()
        }
        val q = query.trim().lowercase()
        val matching = if (q.isBlank()) scoped else scoped.filter {
            it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        // An album's tracks are already in the server's track-number order;
        // re-sorting them alphabetically would scramble the album's own
        // running order, so only the other views get a client-side sort.
        if (songsOfAlbum) {
            matching
        } else {
            sortedStations(matching, if (sort == PlaylistSort.Artist) PlaylistSort.Title else sort)
        }
    }
    val favorites = ui.favorites.map { it.url }.toSet()
    val failedLabels = ui.failures.keys.mapNotNull { id ->
        accounts.firstOrNull { it.id == id }?.displayName()?.ifBlank { null }
    }
    fun pop() {
        when {
            selectedAlbum != null -> { selectedAlbum = null; query = "" }
            selectedArtist != null -> { selectedArtist = null; query = "" }
            else -> onBack()
        }
    }
    BackHandler(enabled = selectedArtist != null) { pop() }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        MainLayout(
            title = if (accountId.isBlank()) {
                selectedAlbum ?: selectedArtist ?: "providers"
            } else {
                accounts.firstOrNull()?.displayName()?.ifBlank { "provider" } ?: "provider"
            },
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = { pop() },
            chips = if (accounts.size > 1) {
                @Composable {
                    accounts.forEach { a ->
                        Chip(
                            a.displayName().ifBlank { "provider" },
                            selected == a.id,
                            onClick = {
                                selected = a.id; query = ""; selectedArtist = null; selectedAlbum = null
                            },
                        )
                    }
                }
            } else null,
        ) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                // Picker rows scroll with the list; only the header is
                // fixed. Sort first, rescan trailing for SSH accounts.
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        PlaylistSort.entries.forEach { s ->
                            Chip(
                                s.label, sort == s,
                                onClick = { sort = s; query = ""; selectedArtist = null; selectedAlbum = null },
                            )
                        }
                        // The browse page is gone; its rescan rides the sort row for
                        // SSH accounts, trailing like it always did.
                        val selectedAccount = accounts.firstOrNull { it.id == selected }
                        if (selectedAccount?.providerKey == "ssh") {
                            val indexState by SftpLibrary.status(selectedAccount.id).collectAsState()
                            Chip(
                                if (indexState.scanning) "scanning" else "rescan",
                                selected = false,
                                onClick = { scope.launch { SftpLibrary.rescan(selectedAccount) } },
                            )
                        }
                    }
                }
                item { FilterRow(value = query, onValue = { query = it }) }
                item {
                    // Adding happens on the providers page; this page only
                    // shows what is already connected.
                    SectionLabel(
                        when {
                            browsingArtists -> "artists — ${artistRows.size}"
                            browsingAlbums -> "albums — ${albumRows.size}"
                            else -> "songs — ${visible.size}"
                        },
                    )
                }
                if (browsingArtists) {
                    if (artistRows.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Mono(
                                    if (ui.loading) "loading provider songs…" else "nothing here",
                                    KleeampType.rowSecondary, p.inkFaint,
                                )
                            }
                        }
                    } else {
                        items(artistRows, key = { it.first }, contentType = { "provider-artist" }) { (name, count) ->
                            ListRow(
                                onClick = { selectedArtist = name; selectedAlbum = null; query = "" },
                                verticalPadding = 11.dp,
                                trailing = { Mono("$count", KleeampType.meta, p.inkFaint) },
                            ) {
                                Mono(name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                            }
                        }
                    }
                } else if (browsingAlbums) {
                    if (albumRows.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Mono("nothing here", KleeampType.rowSecondary, p.inkFaint)
                            }
                        }
                    } else {
                        items(albumRows, key = { it.name }, contentType = { "provider-album" }) { row ->
                            ListRow(
                                onClick = { selectedAlbum = row.name; query = "" },
                                verticalPadding = 11.dp,
                                trailing = { Mono("${row.count}", KleeampType.meta, p.inkFaint) },
                            ) {
                                Mono(row.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                                if (row.year > 0) {
                                    Mono("${row.year}", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                                }
                            }
                        }
                    }
                } else if (visible.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Mono(
                                when {
                                    accounts.isEmpty() -> "no providers yet — add one with +"
                                    ui.loading -> "loading provider songs…"
                                    else -> "nothing here"
                                },
                                KleeampType.rowSecondary, p.inkFaint,
                            )
                        }
                    }
                } else {
                    items(visible, key = { it.id }, contentType = { "provider-song" }) { s ->
                        ListRow(
                            rail = current?.url == s.url,
                            onClick = { onPlay(s, visible) },
                            verticalPadding = 9.dp,
                            leading = {
                                SongCover(s = s, current = current, playing = playing)
                            },
                            trailing = {
                                OverflowButton({ menuFor = s }, size = 16)
                            },
                        ) {
                            Mono(
                                s.name,
                                KleeampType.rowPrimary,
                                if (current?.url == s.url) p.accent else p.ink,
                                maxLines = 1,
                            )
                            Mono(
                                s.artistAlbum.ifBlank { s.meta.ifBlank { "provider" } },
                                KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                            )
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
