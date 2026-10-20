package stream.kleeamp.mobile.podcasts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.art.ArtResolve
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.CountrySheet
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.EmptyNote
import stream.kleeamp.mobile.chrome.LoadingNote
import stream.kleeamp.mobile.chrome.GridListToggle
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.ShowMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.RetryNote
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * Podcasts, laid out as the Stations tab is: the lists you own on top, the
 * directory below, one flat scroll, paged as it runs out.
 *
 * Where radio has favourites this has two owned lists, because a show and an
 * episode are different things: subscriptions are shows, and "continue" is
 * episodes with a saved position. The second is the one that earns its place at
 * the top - a half-finished episode is what you almost always came back for.
 */
@Composable
fun PodcastsScreen(
    vm: PodcastsViewModel,
    onOpenShow: (PodcastShow) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val ui by vm.state.collectAsState()
    val playedShows by vm.playedShows.collectAsState()
    val subsGrid = ui.subsGrid
    val podDirectoryGrid = ui.podDirectoryGrid

    val countryList = ui.countries

    val directory = ui.directory
    val subscriptions = ui.subscriptions

    val listState = rememberLazyGridState()
    // NOTE: no scroll reset on query change, same reasoning as Stations.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 16
        }
    }
    LaunchedEffect(nearEnd, directory.shows.size) {
        if (nearEnd) vm.onEvent(PodcastsViewModel.Event.NextPage)
    }

    val subscribedFeeds = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.feedUrl } }
    // The show menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuShow by remember { mutableStateOf<PodcastShow?>(null) }
    // Refresh the toggle's state from cached episodes whenever the menu opens.
    LaunchedEffect(menuShow?.feedUrl) {
        menuShow?.let { vm.onEvent(PodcastsViewModel.Event.LoadShowPlayed(it)) }
    }
    // The country picker: a bottom sheet, not the old dropdown menu.
    var countrySheet by remember { mutableStateOf(false) }

    // Warm catalogue art on entry and on page append: small for rows, full
    // for the first tiles (which decode full-res). Full downloads share
    // their bytes on disk, so the small path then decodes without network.
    LaunchedEffect(subscriptions.size, directory.shows.size) {
        val urls = subscriptions.map { it.artwork } + directory.shows.map { it.artwork }
        ArtResolve.prefetchSmallUrls(urls)
        ArtResolve.prefetchFullUrls(urls)
    }

    MainLayout(
        title = "Podcasts",
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onTitleClick = { scope.scrollToTop(listState) },
    ) {

        // Same known androidx LazyGrid span-flip crash guard as Stations:
        // remount the grid when either mode toggle changes so measured item
        // spans never flip in place while the directory is appending pages.
        key(subsGrid, podDirectoryGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                // Same split as Stations: page padding and column gaps from
                // the grid itself, row rhythm from the rows, tile rhythm
                // from the tiles - grid mode pixel-identical to before.
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {

                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel("subscribed — ${subscriptions.size}", gutter = 8.dp) {
                        GridListToggle(subsGrid) { vm.onEvent(PodcastsViewModel.Event.ToggleSubsGrid) }
                    }
                }
                if (subscriptions.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyNote("nothing subscribed — open a show and subscribe")
                    }
                } else {
                    items(
                        subscriptions,
                        key = { "sub:${it.feedUrl}" },
                        span = { GridItemSpan(if (subsGrid) 1 else maxLineSpan) },
                    ) { show ->
                        if (subsGrid) {
                            ShowTile(
                                show = show,
                                subscribed = true,
                                onOpen = { onOpenShow(show) },
                                onOpenMenu = { menuShow = show },
                            )
                        } else {
                            ShowRow(
                                show = show,
                                subscribed = true,
                                onOpen = { onOpenShow(show) },
                                onOpenMenu = { menuShow = show },
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel("directory", gutter = 8.dp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // The default top chart needs no announcement;
                            // every other query names itself.
                            if (directory.query !is PodcastQuery.Top) {
                                Mono(directory.query.label, KleeampType.meta, p.inkTertiary)
                            }
                            val topQuery = directory.query as? PodcastQuery.Top
                            Chip(
                                topQuery?.country?.takeIf { it.isNotEmpty() }?.let { c ->
                                    countryList.firstOrNull { it.iso31661.equals(c, ignoreCase = true) }?.name ?: c
                                } ?: "all countries",
                                selected = topQuery != null && topQuery.country.isNotEmpty(),
                                onClick = { countrySheet = true },
                                caret = true,
                            )
                            GridListToggle(podDirectoryGrid) {
                                vm.onEvent(PodcastsViewModel.Event.TogglePodDirectoryGrid)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        // The genres narrow the chart; the country chart
                        // rides the section header by the grid toggle.
                        PodcastDirectory.genres.forEach { g ->
                            val q = directory.query
                            Chip(
                                g.name.lowercase(),
                                selected = q is PodcastQuery.Category && q.genre.id == g.id,
                                onClick = { vm.onEvent(PodcastsViewModel.Event.Load(PodcastQuery.Category(g))) },
                            )
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(6.dp)) }

                items(
                    directory.shows,
                    key = { "dir:${it.feedUrl}" },
                    span = { GridItemSpan(if (podDirectoryGrid) 1 else maxLineSpan) },
                ) { show ->
                    if (podDirectoryGrid) {
                        ShowTile(
                            show = show,
                            subscribed = show.feedUrl in subscribedFeeds,
                            onOpen = { onOpenShow(show) },
                            onOpenMenu = { menuShow = show },
                        )
                    } else {
                        ShowRow(
                            show = show,
                            subscribed = show.feedUrl in subscribedFeeds,
                            onOpen = { onOpenShow(show) },
                            onOpenMenu = { menuShow = show },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        directory.error != null -> RetryNote(
                            message = "couldn't fetch the directory",
                            prominent = directory.shows.isEmpty(),
                            onRetry = { vm.onEvent(PodcastsViewModel.Event.Load(directory.query)) },
                        )
                        directory.loading -> LoadingNote("loading more…")
                        directory.exhausted -> EmptyNote("end of ${directory.query.label}")
                        else -> Spacer(Modifier.height(8.dp))
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(20.dp)) }
            }

            // The show menu as a bottom sheet: the subscription alone, since
            // shows are feeds - favourites, playlists, queue and info live
            // on episode rows instead.
            if (countrySheet) {
                CountrySheet(
                    countries = countryList,
                    selectedIso = (directory.query as? PodcastQuery.Top)?.country?.takeIf { it.isNotEmpty() },
                    showCounts = false,
                    onPick = { c ->
                        vm.onEvent(PodcastsViewModel.Event.Load(PodcastQuery.Top(c?.iso31661.orEmpty())))
                    },
                    onDismiss = { countrySheet = false },
                )
            }
            menuShow?.let { show ->
                val subscribed = show.feedUrl in subscribedFeeds
                ContextMenuSheet(
                    title = show.title,
                    subtitle = show.meta,
                    art = { ShowMenuArt(show.artwork, show.feedUrl.ifBlank { show.id }, show.title) },
                    actions = menuActions(
                        MenuSubject(
                            kind = MenuKind.SHOW,
                            subscribed = subscribed,
                            playedAll = show.feedUrl in playedShows,
                            onToggleSubscribe = {
                                vm.onEvent(PodcastsViewModel.Event.ToggleSubscription(show))
                            },
                            onDownloadAll = {
                                vm.onEvent(PodcastsViewModel.Event.DownloadAll(show))
                            },
                            onToggleAllPlayed = {
                                vm.onEvent(
                                    PodcastsViewModel.Event.SetAllPlayed(
                                        show,
                                        show.feedUrl !in playedShows,
                                    ),
                                )
                            },
                        ),
                    ),
                    onDismiss = { menuShow = null },
                )
            }
        }
    }
}

/** A show: artwork, title, author and count, with the subscribe menu. */
@Composable
private fun ShowRow(
    show: PodcastShow,
    subscribed: Boolean,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        onClick = onOpen,
        verticalPadding = 9.dp,
        gutter = 8.dp,
        leading = { Artwork(show, subscribed) },
        trailing = { OverflowButton(onOpenMenu, size = 16) },
    ) {
        Mono(show.title, KleeampType.rowPrimary, p.ink, maxLines = 1)
        Mono(show.meta, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

/**
 * Show artwork, or its generated plate when there is none yet. A row icon
 * only ever sits at thumbnails size, so it decodes small like a station's
 * row thumbnail does; the grid tile decodes full-res separately.
 */
@Composable
private fun Artwork(show: PodcastShow, subscribed: Boolean) {
    val p = LocalPalette.current
    val art = rememberArt(url = show.artwork)
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small)),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            SeedPlate(
                key = show.feedUrl.ifBlank { show.id },
                name = show.title,
                modifier = Modifier.fillMaxSize(),
                radius = KleeampShape.small,
            )
        }
        if (subscribed) {
            SubscriptionBadge(
                boxDp = 14,
                glyphDp = 9,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
            )
        }
    }
}

/** Accent tick over artwork: subscribed, without adding a trailing word to the row. */
@Composable
private fun SubscriptionBadge(
    modifier: Modifier = Modifier,
    boxDp: Int = 15,
    glyphDp: Int = 9,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .size(boxDp.dp)
            .clip(CircleShape)
            .background(p.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KleeampIcons.Check, "subscribed", Modifier.size(glyphDp.dp), tint = p.onAccent)
    }
}

/** A show as a small square tile: artwork (or its plate), menu,
 * and title/author on a scrim. The grid layout's cell. */
@Composable
private fun ShowTile(
    show: PodcastShow,
    subscribed: Boolean,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val p = LocalPalette.current
    // Tiles are large: full decode with a memory peek, so grid scrolls do
    // not flash the plate on every rebind.
    val art = rememberArt(url = show.artwork, kind = ArtKind.Full)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .microPress(onClick = onOpen)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(KleeampShape.medium))
                .background(p.panel)
                .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.medium)),
        ) {
            if (art != null) {
                Image(art, show.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                SeedPlate(
                    key = show.feedUrl.ifBlank { show.id },
                    name = show.title,
                    modifier = Modifier.fillMaxSize(),
                    radius = KleeampShape.medium,
                )
            }
            if (subscribed) {
                SubscriptionBadge(
                    boxDp = 18,
                    glyphDp = 10,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                )
            }
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                OverflowButton(onOpenMenu, size = 15)
            }
        }
        Spacer(Modifier.height(7.dp))
        Column(Modifier.padding(horizontal = 2.dp)) {
            Mono(show.title, KleeampType.rowPrimaryMedium, p.ink, maxLines = 2)
            Mono(show.meta, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
    }
}
