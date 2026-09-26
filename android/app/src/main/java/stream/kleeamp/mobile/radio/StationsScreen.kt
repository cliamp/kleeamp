package stream.kleeamp.mobile.radio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.art.ArtResolve
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.chrome.compact
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.EmptyNote
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.DestructiveAction
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.RetryNote

import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

private enum class Source(val label: String) {
    All("all"), Cliamp("cliamp"), Directory("directory"), Custom("custom")
}

@Composable
fun StationsScreen(
    vm: StationsViewModel,
    current: Station?,
    playing: Boolean,
    favorites: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    focusDirectory: Boolean = false,
    onDirectoryFocusConsumed: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(Source.All) }
    var addingCustom by rememberSaveable { mutableStateOf(false) }
    val ui by vm.state.collectAsState()
    val cliamp = ui.cliamp
    val cliampError = ui.cliampError
    val custom = ui.custom
    val directory = ui.directory
    val dirStats = ui.directoryStats
    val tags = ui.tags
    val countries = ui.countries

    // Warm small art for the head of every section on entry and on page
    // append, so rows compose onto warm memory instead of each firing a
    // cold lookup as they scroll in.
    val resolver = LocalContext.current.contentResolver
    LaunchedEffect(source, cliamp.size, custom.size, directory.stations.size) {
        ArtResolve.prefetchSmall(cliamp + custom + directory.stations, resolver)
    }

    val listState = rememberLazyListState()
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    // The sheet itself is emitted after the list below.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    // NOTE: no scroll reset on query/source change. A filter keeps its
    // scroll position while the repository swaps content underneath
    // (stale rows stay until the live page lands), and returning from
    // search or the player keeps the position you left.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, directory.stations.size) {
        if (nearEnd && source != Source.Cliamp && source != Source.Custom) vm.onEvent(StationsViewModel.Event.NextPage)
    }

    // A global search in the command/tab writes its temporary query into the
    // shared directory feed. The Stations tab is "every radio" - it must not
    // inherit that, or a leftover search would silently filter the whole list.
    // Whenever this tab is active and the feed is stuck on a search, nudge it
    // back to the default full browse.
    LaunchedEffect(directory.query) {
        if (directory.query is DirectoryQuery.Search) {
            vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.TopVoted, reset = true))
        }
    }

    // A tag tapped in search lands here already filtered, but the list opens
    // at the top - above the favourites and cliamp sections. Slide down to the
    // directory section so the result is actually on screen, then report back
    // so the request is consumed and a later plain tab switch does not re-jump.
    LaunchedEffect(focusDirectory) {
        if (!focusDirectory) return@LaunchedEffect
        val directoryShown = source == Source.All || source == Source.Directory
        if (!directoryShown) return@LaunchedEffect
        var idx = 0
        if (source == Source.All || source == Source.Cliamp) idx += 1 + cliamp.size
        listState.animateScrollToItem(idx)
        onDirectoryFocusConsumed()
    }

    MainLayout(
        title = "Stations",
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onTitleClick = { scope.scrollToTop(listState) },
        chips = {
            Source.entries.forEach { s ->
                Chip(s.label, source == s, onClick = { source = s })
            }
            Spacer(Modifier.width(4.dp))
            val countryQuery = directory.query as? DirectoryQuery.Country
            ChipDropdown(
                label = countryQuery?.countryName ?: "all countries",
                selected = countryQuery != null,
                options = listOf(
                    ChipOption("all countries") {
                        vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.TopVoted, reset = true))
                    },
                ) + countries.map { c ->
                    ChipOption(c.name) {
                        vm.onEvent(
                            StationsViewModel.Event.LoadDirectory(
                                DirectoryQuery.Country(c.iso31661, c.name),
                                reset = true,
                            )
                        )
                    }
                },
            )
        },
    ) {

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            // Page padding exactly as before; rows compensate inside
            // themselves (8dp gutter lands on the 22dp standard), because
            // any list-level vertical gap would float the row hairlines.
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
        ) {

            if (source == Source.All || source == Source.Cliamp) {
                item {
                    SectionLabel("cliamp radio — ${cliamp.size}", gutter = 8.dp) {
                        CliampStatsText(vm)
                    }
                }
                if (cliampError != null) {
                    item {
                        RetryNote(
                            message = "couldn't reach cliamp radio",
                            onRetry = { vm.onEvent(StationsViewModel.Event.RefreshCliamp) },
                        )
                    }
                } else {
                    items(
                        cliamp,
                        key = { "cl:${it.url}" },
                    ) { s ->
                        StationRow(
                            station = s,
                            active = current?.url == s.url,
                            playing = playing && current?.url == s.url,
                            onPlay = { onPlay(s, cliamp) },
                            onOpenMenu = { menuFor = s },
                        )
                    }
                }
            }

            if (source == Source.All || source == Source.Custom) {
                item {
                    SectionLabel("custom — ${custom.size}", gutter = 8.dp) {
                        if (!addingCustom) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    // Nudge to the row end: the shared label
                                    // gutter leaves the key sitting in from
                                    // the edge next to the rows below.
                                    .offset(x = 7.dp)
                                    .clip(RoundedCornerShape(KleeampShape.small))
                                    .background(if (p.dark) p.keyFace else p.ground)
                                    .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
                                    .microPress(onClick = { addingCustom = true }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(KleeampIcons.Plus, "add station", Modifier.size(16.dp), tint = p.accent)
                            }
                        }
                    }
                }
                if (addingCustom) {
                    item {
                        CustomAddForm(
                            onAdd = { name, url ->
                                customStation(name, url)?.let { s ->
                                    vm.onEvent(StationsViewModel.Event.AddCustom(s))
                                }
                                addingCustom = false
                            },
                            onCancel = { addingCustom = false },
                        )
                    }
                }
                items(
                    custom,
                    key = { "cu:${it.url}" },
                ) { s ->
                    CustomStationRow(
                        station = s,
                        active = current?.url == s.url,
                        playing = playing && current?.url == s.url,
                        onPlay = { onPlay(s, custom) },
                        onOpenMenu = { menuFor = s },
                    )
                }
            }

            if (source == Source.All || source == Source.Directory) {
                item {
                    SectionLabel(
                        "directory — " + (dirStats?.playable?.let { "%,d".format(it) }
                            ?: if (directory.error != null) "unreachable" else "loading"),
                        gutter = 8.dp,
                    ) {
                        Mono(directory.query.label, KleeampType.meta, p.inkTertiary)
                    }
                }
                // The directory's filters, one row: the order controls (top /
                // trending) lead it, then the tags that narrow the list.
                // Gutter-compensated like rows and labels: list padding
                // plus this lands exactly on the shared gutter.
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Chip(
                            "top",
                            directory.query == DirectoryQuery.TopVoted,
                            onClick = {
                                vm.onEvent(
                                    StationsViewModel.Event.LoadDirectory(
                                        DirectoryQuery.TopVoted,
                                        reset = true,
                                    )
                                )
                            },
                        )
                        Chip(
                            "trending",
                            directory.query == DirectoryQuery.Trending,
                            onClick = {
                                vm.onEvent(
                                    StationsViewModel.Event.LoadDirectory(
                                        DirectoryQuery.Trending,
                                        reset = true,
                                    )
                                )
                            },
                        )
                        if (tags.isNotEmpty()) {
                            tags.take(24).forEach { t ->
                                val q = directory.query
                                Chip(
                                    t.name,
                                    selected = q is DirectoryQuery.Tag && q.tag == t.name,
                                    onClick = {
                                        vm.onEvent(
                                            StationsViewModel.Event.LoadDirectory(
                                                DirectoryQuery.Tag(t.name),
                                                reset = true,
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
                items(
                    directory.stations,
                    key = { "dir:${it.url}" },
                ) { s ->
                    StationRow(
                        station = s,
                        active = current?.url == s.url,
                        playing = playing && current?.url == s.url,
                        onPlay = { onPlay(s, directory.stations) },
                        onOpenMenu = { menuFor = s },
                    )
                }
                item {
                    when {
                        directory.error != null -> RetryNote(
                            message = "couldn't fetch the directory",
                            prominent = directory.stations.isEmpty(),
                            onRetry = {
                                vm.onEvent(
                                    StationsViewModel.Event.LoadDirectory(directory.query, reset = true)
                                )
                            },
                        )
                        directory.loading -> EmptyNote("loading more…")
                        directory.exhausted -> EmptyNote("end of ${directory.query.label}")
                        else -> Spacer(Modifier.height(8.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }

        // The row menu as a bottom sheet: one reusable component fed by the
        // subject below, so stations never hardcode their own action list.
        // No info entry: radio needs none.
        menuFor?.let { s ->
            val fav = favorites.any { it.url == s.url }
            val isCustom = custom.any { it.url == s.url }
            ContextMenuSheet(
                title = s.name,
                subtitle = stationMenuSubtitle(s),
                art = { StationMenuArt(s) },
                actions = menuActions(
                    MenuSubject(
                        kind = MenuKind.STATION,
                        favorite = fav,
                        infoAvailable = false,
                        destructive = if (isCustom) {
                            DestructiveAction(
                                "remove station",
                                "delete this station",
                                { vm.onEvent(StationsViewModel.Event.RemoveCustom(s)) },
                            )
                        } else {
                            null
                        },
                        onQueue = { onAddToQueue(s) },
                        onToggleFavorite = { vm.onEvent(StationsViewModel.Event.ToggleFavorite(s)) },
                        onAddToPlaylist = { onAddToPlaylist(s) },
                    ),
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

/** The sheet header line under the title: same words the row itself wears. */
private fun stationMenuSubtitle(s: Station): String {
    if (s.source == StationSource.Custom) return "custom station"
    return buildList {
        if (s.source == StationSource.Cliamp) add("cliamp radio")
        s.meta.takeIf { it.isNotBlank() }?.let { add(it) }
        s.tagList.take(2).forEach { add(it) }
    }.joinToString(" · ")
}

@Composable
private fun StationRow(
    station: Station,
    active: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 9.dp,
        gutter = 8.dp,
        railOffset = 14.dp,
        leading = { StationThumb(station, active, playing) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (station.votes > 0) {
                    Mono(compact(station.votes), KleeampType.meta, p.inkFaint)
                }
                OverflowButton(onOpenMenu, size = 16)
            }
        },
    ) {
        Mono(
            station.name,
            KleeampType.rowPrimary,
            if (active) p.accent else p.ink,
            maxLines = 1,
        )
        Mono(
            buildList {
                if (station.source == StationSource.Cliamp) add("cliamp radio")
                station.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                station.tagList.take(2).forEach { add(it) }
            }.joinToString(" · "),
            KleeampType.rowSecondary,
            p.inkTertiary,
            maxLines = 1,
        )
    }
}

/**
 * A station row's leading thumbnail. Loads real cover art through the same
 * source podcast rows use (scraped og:image / favicon, small decode, LRU
 * cache) so any station with a cover shows it, and overlays the play / pause
 * badge when it is the current track. Coverless stations wear a generated
 * plate seeded by the station id.
 */
@Composable
private fun StationThumb(station: Station, active: Boolean, playing: Boolean) {
    val p = LocalPalette.current
    val art = rememberArt(station = station)
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small)),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = station.name,
                modifier = Modifier
                    .fillMaxSize()
                    .background(p.panel),
                contentScale = ContentScale.Crop,
            )
        } else {
            SeedPlate(
                key = station.id.ifBlank { station.url },
                name = station.name,
                modifier = Modifier.fillMaxSize(),
                radius = KleeampShape.small,
            )
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(18.dp)
                    .clip(RoundedCornerShape(KleeampShape.tiny))
                    .background(p.accent.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) KleeampIcons.Pause else KleeampIcons.PlayRow,
                    null,
                    Modifier.size(9.dp),
                    tint = p.onAccent,
                )
            }
        }
    }
}

/**
 * Live "who's listening" line at the right end of the cliamp header, from
 * the same statistics document cliamp.stream renders. Fetches once per
 * screen lifetime; tapping refreshes. Hidden until the first fetch lands.
 */
@Composable
private fun CliampStatsText(vm: StationsViewModel) {
    val p = LocalPalette.current
    val stats by vm.cliampStats.collectAsState()
    LaunchedEffect(Unit) {
        if (stats == null) vm.onEvent(StationsViewModel.Event.RefreshStats)
    }
    stats?.let {
        Mono(
            "${it.activeNow} listening now · peak ${it.peak}",
            KleeampType.meta,
            p.inkFaint,
            Modifier.microPress { vm.onEvent(StationsViewModel.Event.RefreshStats) },
            maxLines = 1,
        )
    }
}

/** A hand-added station: name plus stream URL, playable like anything else. */
fun customStation(name: String, rawUrl: String): Station? {    val url = rawUrl.trim()
    if (url.isBlank()) return null
    val fixed = if ("://" in url) url else "https://$url"
    if (!fixed.startsWith("http://") && !fixed.startsWith("https://")) return null
    val label = name.trim().ifBlank {
        runCatching { java.net.URI(fixed).host }.getOrNull()?.removePrefix("www.") ?: fixed
    }
    return Station(id = "custom:$fixed", name = label, url = fixed, source = StationSource.Custom)
}

/** Name + URL form for a hand-added station, opened by the header plus key.
 * Fields wear the provider wizard's FieldRow styling exactly (label row with
 * optional marker, trackTitleSmall entry, hairline underline that accents on
 * focus); the outer 8dp keeps them on the rows' gutter inside the grid page.
 * One form serves both the All and the Custom views, so both match. */
@Composable
private fun CustomAddForm(onAdd: (String, String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        fun save() {
            if (customStation(name, url) == null) return
            onAdd(name, url)
            name = ""
            url = ""
        }
        CustomField(
            label = "name",
            optional = true,
            value = name,
            onValue = { name = it },
            placeholder = "name",
            keyboard = KeyboardType.Text,
            imeAction = ImeAction.Next,
            autoFocus = false,
            onAction = { focus.moveFocus(FocusDirection.Next) },
        )
        CustomField(
            label = "stream url",
            optional = false,
            value = url,
            onValue = { url = it },
            placeholder = "stream url",
            keyboard = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            autoFocus = false,
            onAction = ::save,
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("cancel", selected = false, onClick = onCancel)
            Chip("save", url.isNotBlank(), onClick = ::save)
        }
    }
}

/** One provider-wizard-style field row: label (+ optional marker), entry,
 * and the hairline underline that accents while focused. */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
private fun CustomField(
    label: String,
    optional: Boolean,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    imeAction: ImeAction,
    autoFocus: Boolean,
    onAction: () -> Unit,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(label, KleeampType.rowSecondary, if (focused) p.accent else p.inkTertiary)
            if (optional) Mono("optional", KleeampType.meta, p.inkFaint)
        }
        KleeampTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = placeholder,
            textStyle = KleeampType.trackTitleSmall,
            keyboardType = keyboard,
            imeAction = imeAction,
            autoFocus = autoFocus,
            onAction = onAction,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 2.dp else 1.dp)
                .background(if (focused) p.accent else p.hairline)
        )
    }
}

/** A custom station row: plays like a directory row, plus remove. */
@Composable
private fun CustomStationRow(
    station: Station,
    active: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 9.dp,
        gutter = 8.dp,
        railOffset = 14.dp,
        leading = { StationThumb(station, active, playing) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverflowButton(onOpenMenu, size = 16)
            }
        },
    ) {
        Mono(station.name, KleeampType.rowPrimary, if (active) p.accent else p.ink, maxLines = 1)
        Mono("custom station", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}
