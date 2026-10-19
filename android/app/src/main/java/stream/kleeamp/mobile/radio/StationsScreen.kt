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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.art.ArtResolve
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.play.QueueContinuation
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.chrome.compact
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.CountrySheet
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.EmptyNote
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.LoadingNote
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
import kotlinx.coroutines.launch
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
    onPlay: (Station, List<Station>, QueueContinuation?) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    focusDirectory: Boolean = false,
    onDirectoryFocusConsumed: () -> Unit = {},
    /** A cliamp channel with songs opens as its track list instead of playing. */
    onOpenChannel: (Station) -> Unit = {},
    /** The header plus key opens the add wizard as a page, never inline. */
    onAddCustom: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(Source.All) }
    // Local favorites filter for the directory: the list is already in
    // memory, so no query, no paging, no network. Any real query (trending,
    // tag, country) switches it back off.
    var favOnly by rememberSaveable { mutableStateOf(false) }
    val ui by vm.state.collectAsState()
    // Favorites render the saved stations themselves, never the loaded
    // page: a favorite on an unloaded page, another query or a reloaded
    // list is still a favorite. Live rows win when present, so votes and
    // meta stay fresh; the snapshot covers the rest. Cliamp and custom
    // favorites belong here too, the same stations scope the library wears.
    val favStations = remember(favorites, ui.directory.stations) {
        val liveByUrl = ui.directory.stations.associateBy { it.url }
        favorites.filter {
            it.source == StationSource.Cliamp ||
                it.source == StationSource.Directory ||
                it.source == StationSource.Custom
        }.map { liveByUrl[it.url] ?: it }.distinctBy { it.url }
    }
    val cliamp = ui.cliamp
    val cliampError = ui.cliampError
    // Live listener figures from the same documents cliamp.stream renders,
    // fetched once per screen lifetime. Informational only: nothing here
    // navigates, scrolls or focuses anywhere.
    val stats by vm.cliampStats.collectAsState()
    LaunchedEffect(Unit) {
        if (stats == null) vm.onEvent(StationsViewModel.Event.RefreshStats)
    }
    // The channel currently on air: a live stream or one of its track-list
    // files. Tracks carry their channel in slug, so the group row lights up
    // together with the track itself.
    val liveSlug = if (playing) {
        current?.takeIf { it.source == StationSource.Cliamp }?.slug?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    // Optimistic counts: the server only learns about this listener on the
    // next poll, so the app adds one immediately and drops it on stop or
    // switch. Null while nothing has landed yet; zero reads as quiet.
    fun boostedCount(slug: String): Int? {
        if (stats == null && slug != liveSlug) return null
        return (stats?.listenersFor(slug) ?: 0) + (if (slug == liveSlug) 1 else 0)
    }
    // Optimistic location: the device country gains one in the ranking and
    // on the globe, or joins them when absent. Leaving clears it back to
    // server truth everywhere at once.
    val liveCountry = if (liveSlug != null) deviceCountryCode() else null
    val serverStats = stats
    val viewStats = remember(serverStats, liveSlug, liveCountry) {
        if (serverStats != null) {
            serverStats.copy(
                activeNow = serverStats.activeNow + (if (liveSlug != null) 1 else 0),
                countries = boostCountryRows(serverStats.countries, liveCountry),
            )
        } else if (liveSlug != null) {
            CliampStats(
                activeNow = 1,
                peak = 0,
                countries = boostCountryRows(emptyList(), liveCountry),
                perStation = mapOf(liveSlug to 1),
                isLive = true,
            )
        } else {
            null
        }
    }
    // Channels with songs open as track lists; the rest play the stream.
    // Matched by slug, which is the channel id on both sides.
    val channelBySlug = remember(ui.cliampChannels) {
        ui.cliampChannels.associateBy { it.id }
    }
    val custom = ui.custom
    val directory = ui.directory
    val dirStats = ui.directoryStats
    val tags = ui.tags
    val countries = ui.countries

    // Viewport-first warm: only the visible window + small overscan, keyed
    // by the visible index range so scrolling cancels the previous run.
    // A blind head-of-list prefetch queued 40 homepage scrapes ahead of the
    // visible rows; this warms small art only, never full.
    val resolver = LocalContext.current.contentResolver
    val listState = rememberLazyListState()
    val displayStations: List<Station> = remember(source, cliamp, custom, directory.stations) {
        when (source) {
            Source.All -> cliamp + custom + directory.stations
            Source.Cliamp -> cliamp
            Source.Custom -> custom
            Source.Directory -> directory.stations
        }
    }
    val visibleRange by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: first
            first..last
        }
    }
    LaunchedEffect(visibleRange, source, displayStations.size) {
        // Lazy indices include section headers, so shift back slightly and
        // clamp; the +8 overscan absorbs the offset either way.
        val start = (visibleRange.first - 2).coerceAtLeast(0)
        val end = (visibleRange.last + 8).coerceAtMost(displayStations.size)
        if (start >= end) return@LaunchedEffect
        val window = displayStations.subList(start, end)
        ArtResolve.prefetchSmall(window, resolver, limit = 24)
        // Hero warm, strictly after the thumbs and tightly bounded: full
        // decodes are heavy, so only the head of the visible window warms
        // them, and scrolling cancels the run via the effect key. Tapping a
        // warmed row then opens the player onto cached full art.
        ArtResolve.prefetchFull(window, resolver, limit = 4)
    }

    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    // The sheet itself is emitted after the list below.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    // The country picker: a bottom sheet, not the old dropdown menu.
    var countrySheet by remember { mutableStateOf(false) }
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
    LaunchedEffect(nearEnd, directory.stations.size, favOnly) {
        // The favorites filter is local: no pages to fetch behind it.
        if (nearEnd && !favOnly && source != Source.Cliamp && source != Source.Custom) {
            vm.onEvent(StationsViewModel.Event.NextPage)
        }
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
        // Straight to the hero in one landing-exact flight: the pixel
        // estimator undershoots past the tall globe card and then snaps,
        // which reads as scroll-then-jump.
        onTitleClick = { scope.launch { listState.animateScrollToItem(0) } },
        chips = {
            Source.entries.forEach { s ->
                Chip(s.label, source == s, onClick = { source = s })
            }
            Spacer(Modifier.width(4.dp))
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

            // Page hero: the live listener globe sits above the cliamp
            // section on All and Cliamp only — it renders cliamp stats, so
            // Directory and Custom hide it. The setting toggles the whole
            // section - globe and top countries together.
            if (ui.listenerGlobe && (source == Source.All || source == Source.Cliamp)) {
                item {
                    ListenerAnalytics(
                        stats = viewStats,
                        hintSeen = ui.globeHintSeen,
                        onFirstSpin = { vm.onEvent(StationsViewModel.Event.MarkGlobeHintSeen) },
                        onRefresh = { vm.onEvent(StationsViewModel.Event.RefreshStats) },
                        // The section is the list head: a country tap glides
                        // back to the globe on the tweened flight (no snap
                        // past the tall card) so the fly-to is on screen.
                        onPickCountry = { scope.scrollToTop(listState) },
                    )
                }
            }

            if (source == Source.All || source == Source.Cliamp) {
                item {
                    SectionLabel("cliamp radio — ${cliamp.size}", gutter = 8.dp) {
                        CliampStatsText(vm, extra = if (liveSlug != null) 1 else 0)
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
                        val channel = channelBySlug[s.slug]
                        // A channel stays lit while any of its tracks plays,
                        // so the row always shows where the music belongs.
                        val rowActive = current?.url == s.url ||
                            (current?.isChannelTrack == true && current.slug == s.slug)
                        StationRow(
                            station = s,
                            trackCount = if (channel?.hasTracks == true) channel.trackCount else 0,
                            listenerCount = boostedCount(s.slug),
                            active = rowActive,
                            playing = playing && rowActive,
                            onPlay = {
                                if (channel?.hasTracks == true) onOpenChannel(s)
                                else onPlay(s, cliamp, null)
                            },
                            onOpenMenu = { menuFor = s },
                        )
                    }
                }
            }

            if (source == Source.All || source == Source.Custom) {
                item {
                    SectionLabel("custom — ${custom.size}", gutter = 8.dp) {
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
                                .microPress(onClick = onAddCustom),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KleeampIcons.Plus, "add station", Modifier.size(16.dp), tint = p.accent)
                        }
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
                        onPlay = { onPlay(s, custom, null) },
                        onOpenMenu = { menuFor = s },
                    )
                }
            }

            if (source == Source.All || source == Source.Directory) {
                val dirStations = if (favOnly) favStations else directory.stations
                // Directory rows keep catalogue paging alive in the player:
                // next at the end of the loaded page asks this continuation for
                // the following page, so playback never stops at one page.
                val dirMore: QueueContinuation? =
                    if (favOnly) null else vm.continuation(directory.query, dirStations.size)
                item {
                    SectionLabel(
                        if (favOnly) {
                            "favorites — ${dirStations.size}"
                        } else {
                            "directory — " + (dirStats?.playable?.let { "%,d".format(it) }
                                ?: if (directory.error != null) "unreachable" else "loading")
                        },
                        gutter = 8.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // The active filter already reads lit in the chip
                            // row below; repeating it here only clutters.
                            val countryQuery = directory.query as? DirectoryQuery.Country
                            Chip(
                                countryQuery?.countryName ?: "all countries",
                                selected = countryQuery != null,
                                onClick = { countrySheet = true },
                                caret = true,
                            )
                        }
                    }
                }
                // The directory's filters, one row: trending leads it, then
                // the tags that narrow the list. Top is the default order
                // and stays reachable through "all countries".
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
                            directory.query == DirectoryQuery.TopVoted && !favOnly,
                            onClick = {
                                favOnly = false
                                // Already there: refetching would flash loading
                                // and reshuffle the list the user just left.
                                if (directory.query != DirectoryQuery.TopVoted) {
                                    vm.onEvent(
                                        StationsViewModel.Event.LoadDirectory(
                                            DirectoryQuery.TopVoted,
                                            reset = true,
                                        )
                                    )
                                }
                            },
                        )
                        Chip(
                            "trending",
                            directory.query == DirectoryQuery.Trending && !favOnly,
                            onClick = {
                                favOnly = false
                                // Already there: refetching would flash loading
                                // and reshuffle the list the user just left.
                                if (directory.query != DirectoryQuery.Trending) {
                                    vm.onEvent(
                                        StationsViewModel.Event.LoadDirectory(
                                            DirectoryQuery.Trending,
                                            reset = true,
                                        )
                                    )
                                }
                            },
                        )
                        Chip(
                            "favorites",
                            favOnly,
                            onClick = { favOnly = !favOnly },
                        )
                        if (tags.isNotEmpty()) {
                            tags.take(24).forEach { t ->
                                val q = directory.query
                                Chip(
                                    t.name,
                                    selected = q is DirectoryQuery.Tag && q.tag == t.name && !favOnly,
                                    onClick = {
                                        favOnly = false
                                        if (q != DirectoryQuery.Tag(t.name)) {
                                            vm.onEvent(
                                                StationsViewModel.Event.LoadDirectory(
                                                    DirectoryQuery.Tag(t.name),
                                                    reset = true,
                                                )
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
                items(
                    dirStations,
                    key = { "dir:${it.url}" },
                ) { s ->
                    StationRow(
                        station = s,
                        active = current?.url == s.url,
                        playing = playing && current?.url == s.url,
                        onPlay = { onPlay(s, dirStations, dirMore) },
                        onOpenMenu = { menuFor = s },
                    )
                }
                item {
                    when {
                        favOnly && dirStations.isEmpty() -> EmptyNote("no favourite stations yet")
                        favOnly -> Spacer(Modifier.height(8.dp))
                        directory.error != null -> RetryNote(
                            message = "couldn't fetch the directory",
                            prominent = directory.stations.isEmpty(),
                            onRetry = {
                                vm.onEvent(
                                    StationsViewModel.Event.LoadDirectory(directory.query, reset = true)
                                )
                            },
                        )
                        directory.loading -> LoadingNote("loading more…")
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
        if (countrySheet) {
            CountrySheet(
                countries = countries,
                selectedIso = (directory.query as? DirectoryQuery.Country)?.code?.takeIf { it.isNotEmpty() },
                onPick = { c ->
                    favOnly = false
                    val target = if (c == null) {
                        DirectoryQuery.TopVoted
                    } else {
                        DirectoryQuery.Country(c.iso31661, c.name)
                    }
                    // Same query re-picked: keep the list, skip the reload.
                    if (directory.query != target) {
                        vm.onEvent(StationsViewModel.Event.LoadDirectory(target, reset = true))
                    }
                },
                onDismiss = { countrySheet = false },
            )
        }
        menuFor?.let { s ->
            val fav = favorites.any { it.url == s.url }
            val isCustom = custom.any { it.url == s.url }
            val menuTracks = channelBySlug[s.slug]?.takeIf { it.hasTracks }?.trackCount ?: 0
            // A live channel queues nothing: its tracks do, from inside.
            val liveChannel = s.source == StationSource.Cliamp && !s.isChannelTrack
            ContextMenuSheet(
                title = s.name,
                subtitle = stationMenuSubtitle(s, menuTracks),
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
                        onQueue = if (liveChannel) null else ({ onAddToQueue(s) }),
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
private fun stationMenuSubtitle(s: Station, trackCount: Int = 0): String {
    if (s.source == StationSource.Custom) return "custom station"
    return buildList {
        if (trackCount > 0) add("$trackCount tracks")
        else {
            if (s.source != StationSource.Cliamp) {
                s.meta.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            s.tagList.take(2).forEach { add(it) }
        }
    }.joinToString(" · ")
}

@Composable
private fun StationRow(
    station: Station,
    active: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit,
    /** Song count when this channel opens as a track list; 0 plays the stream. */
    trackCount: Int = 0,
    /**
     * Live listeners on this channel, or null before anything lands.
     * Rendered in the trailing slot, vertically centered: no click handler,
     * no button semantics, no navigation. Peak is never shown in rows.
     */
    listenerCount: Int? = null,
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
                // Live count in the trailing slot, vertically centered like
                // directory vote counts. Plain text: no handler, no button
                // semantics, no navigation. Peak is never shown in rows.
                if (listenerCount != null) {
                    Mono(
                        if (listenerCount > 0) "● $listenerCount listening now"
                        else "○ quiet right now",
                        KleeampType.meta,
                        if (listenerCount > 0) p.accent else p.inkFaint,
                        maxLines = 1,
                    )
                }
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
                // A channel with songs is just its count - the section
                // header already says cliamp radio, and the codec adds
                // nothing. Other rows keep their meta line.
                if (trackCount > 0) add("$trackCount tracks")
                else {
                    if (station.source != StationSource.Cliamp) {
                        station.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    station.tagList.take(2).forEach { add(it) }
                }
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
 * the same statistics document cliamp.stream renders, plus this device's
 * own optimistic listener. Informational only: no tap handler, and peak is
 * never shown in rows. Hidden before anything lands; quiet at zero.
 */
@Composable
private fun CliampStatsText(vm: StationsViewModel, extra: Int = 0) {
    val p = LocalPalette.current
    val stats by vm.cliampStats.collectAsState()
    val total = (stats?.activeNow ?: 0) + extra
    if (stats == null && extra == 0) return
    Mono(
        if (total > 0) "$total listening now" else "○ quiet right now",
        KleeampType.meta,
        p.inkFaint,
        maxLines = 1,
    )
}

/** A hand-added station: name plus stream URL, playable like anything else. */
fun customStation(name: String, rawUrl: String): Station? {    val url = rawUrl.trim()
    if (url.isBlank()) return null
    val fixed = if ("://" in url) url else "https://$url"
    if (!fixed.startsWith("http://") && !fixed.startsWith("https://")) return null
    // Bare words ("just some words") otherwise gain https:// and save as a
    // dead row; the wizard's probe would catch it, but failing here keeps
    // the message about the url instead of a network error.
    if (fixed.any { it.isWhitespace() }) return null
    val label = name.trim().ifBlank {
        runCatching { java.net.URI(fixed).host }.getOrNull()?.removePrefix("www.") ?: fixed
    }
    return Station(id = "custom:$fixed", name = label, url = fixed, source = StationSource.Custom)
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
