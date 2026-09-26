package stream.kleeamp.mobile.podcasts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.art.ArtResolve
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.EmptyNote
import stream.kleeamp.mobile.chrome.RetryNote
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One show's episodes.
 *
 * Playing an episode hands the whole list to the player as the queue, so it
 * rolls on to the next one - the same thing an album does from the provider
 * browser. That works because an episode reports [Station.isTrack], which a
 * live stream does not: radio queues stay one item long by design.
 */
@Composable
fun PodcastShowScreen(
    vm: PodcastShowViewModel,
    current: Station?,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToUpNext: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val ui by vm.state.collectAsState()
    val dlStates = ui.dlStates
    val dlEntries = ui.dlEntries
    val autoOn = ui.autoDownload
    val state = ui.showState
    val progress = ui.progress
    val subscriptions = ui.subscriptions

    val show = state.show
    val subscribed = remember(subscriptions, show?.feedUrl) {
        show != null && subscriptions.any { it.feedUrl == show.feedUrl }
    }

    // Mapped once per feed load, not per row: a 300 episode list would
    // otherwise rebuild every Station on every recomposition.
    val queue = remember(state.episodes, show?.feedUrl) {
        show?.let { s -> state.episodes.map { it.toStation(s) } } ?: emptyList()
    }
    // Warm episode thumbs (URL-keyed, exactly as EpisodeRow reads them).
    LaunchedEffect(queue) {
        ArtResolve.prefetchSmallUrls(queue.mapNotNull { it.cover.takeIf { u -> u.startsWith("http") } })
    }
    val listState = rememberLazyListState()
    // The episode menu's index into the parallel queue/episodes lists, set
    // by the ⋮ trigger and cleared on dismiss.
    var menuIndex by remember { mutableStateOf<Int?>(null) }

    // Subscribed + auto-download on: the latest episodes fetch themselves
    // whenever the feed lands. Idempotent, so refreshes re-firing it cost
    // nothing; the toggle being off keeps this dead.
    LaunchedEffect(show?.feedUrl, subscribed, state.episodes, autoOn) {
        val s = show
        if (s != null && subscribed && autoOn && state.episodes.isNotEmpty()) {
            vm.onEvent(PodcastShowViewModel.Event.AutoDownload)
        }
    }

    MainLayout(
        title = "Podcast",
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onTitleClick = { scope.scrollToTop(listState) },
        onBack = onBack,
    ) {

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item {
                ShowHeader(
                    show = show,
                    subscribed = subscribed,
                    onToggleSubscribe = {
                        show?.let { s -> vm.onEvent(PodcastShowViewModel.Event.ToggleSubscription(s)) }
                    },
                )
            }

            state.error?.let {
                item {
                    RetryNote(
                        message = "couldn't read the feed",
                        prominent = true,
                        onRetry = { vm.onEvent(PodcastShowViewModel.Event.RefreshShow) },
                    )
                }
            }

            // The loading note only shows on an empty list: with episodes on
            // screen a refresh runs silently behind them instead of pushing
            // a row in above and shifting everything when it lands.
            if (state.loading && queue.isEmpty()) {
                item { EmptyNote("reading the feed…") }
            } else if (queue.isEmpty() && state.error == null) {
                item { EmptyNote("no episodes in this feed") }
            }

            if (queue.isNotEmpty()) {
                item {
                    SectionLabel("episodes — ${queue.size}") {
                        Mono(
                            "refresh",
                            KleeampType.meta,
                            p.inkTertiary,
                            Modifier.microPress { vm.onEvent(PodcastShowViewModel.Event.RefreshShow) },
                        )
                    }
                }
                items(queue.indices.toList(), key = { i -> "ep:${queue[i].url}" }) { i ->
                    val station = queue[i]
                    val dl = dlStates[station.url] ?: DownloadState.Idle
                    val fetched = dlEntries[station.url]
                    EpisodeRow(
                        episode = state.episodes[i],
                        station = station,
                        progress = progress[station.url],
                        active = current?.url == station.url,
                        playing = playing && current?.url == station.url,
                        dlState = dl,
                        downloadedBytes = fetched?.bytes ?: 0L,
                        onPlay = { onPlay(station, queue) },
                        onOpenMenu = { menuIndex = i },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }

        // The episode menu as a bottom sheet: play-next, queue,
        // favourites, playlists plus the download and played rows for
        // the episode's current state. No info entry - episodes are
        // not resolvable by the info pane.
        menuIndex?.let { mi ->
            queue.getOrNull(mi)?.let { s ->
                val mdl = dlStates[s.url] ?: DownloadState.Idle
                val done = progress[s.url]?.completed == true
                ContextMenuSheet(
                    title = s.name,
                    subtitle = listOfNotNull(
                        shortDate(state.episodes.getOrNull(mi)?.publishedAt ?: 0L),
                        clock(s.durationMs),
                    ).joinToString(" · "),
                    art = { StationMenuArt(s) },
                    actions = menuActions(
                        MenuSubject(
                            kind = MenuKind.EPISODE,
                            favorite = s.url in ui.favorites,
                            downloaded = (dlEntries[s.url]?.bytes ?: 0L) > 0L,
                            downloading = mdl is DownloadState.Active,
                            downloadFailed = mdl is DownloadState.Failed,
                            playedDone = done,
                            onPlayNext = { onPlayNext(s) },
                            onQueue = { onAddToUpNext(s) },
                            onToggleFavorite = {
                                vm.onEvent(PodcastShowViewModel.Event.ToggleFavorite(s))
                            },
                            onAddToPlaylist = { onAddToPlaylist(s) },
                            onDownload = {
                                vm.onEvent(PodcastShowViewModel.Event.Download(s))
                            },
                            onCancelDownload = {
                                vm.onEvent(PodcastShowViewModel.Event.CancelDownload(s.url))
                            },
                            onRemoveDownload = {
                                vm.onEvent(PodcastShowViewModel.Event.RemoveDownload(s.url))
                            },
                            onMarkPlayed = {
                                vm.onEvent(PodcastShowViewModel.Event.MarkCompleted(s))
                            },
                            onForgetPlayed = {
                                vm.onEvent(PodcastShowViewModel.Event.ClearProgress(s))
                            },
                        ),
                    ),
                    onDismiss = { menuIndex = null },
                )
            }
        }
    }
}

@Composable
private fun ShowHeader(
    show: PodcastShow?,
    subscribed: Boolean,
    onToggleSubscribe: () -> Unit,
) {
    val p = LocalPalette.current
    if (show == null) return
    // Full decode with a memory peek, so reopening a show never flashes
    // the plate on a cover the cache already holds.
    val art = rememberArt(url = show.artwork, kind = ArtKind.Full)
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .border(1.dp, p.frameBorder, RoundedCornerShape(KleeampShape.small)),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    SeedPlate(
                        key = show.feedUrl.ifBlank { show.id },
                        name = show.title,
                        modifier = Modifier.fillMaxSize(),
                        radius = KleeampShape.small,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Mono(show.title, KleeampType.rowPrimaryMedium, p.ink, maxLines = 2)
                if (show.author.isNotBlank()) {
                    Mono(show.author, KleeampType.rowSecondary, p.inkSecondary, maxLines = 1)
                }
                if (show.meta.isNotBlank()) {
                    Mono(show.meta, KleeampType.meta, p.inkTertiary, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                Chip(
                    if (subscribed) "subscribed" else "subscribe",
                    selected = subscribed,
                    onClick = onToggleSubscribe,
                )
            }
        }
        if (show.description.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 14.dp)) {
                Mono(show.description, KleeampType.rowSecondary, p.inkTertiary, maxLines = 4)
            }
        }
        HairlineDivider()
    }
}

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
private fun EpisodeRow(
    episode: PodcastEpisode,
    station: Station,
    progress: EpisodeProgress?,
    active: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit = {},
    dlState: DownloadState = DownloadState.Idle,
    downloadedBytes: Long = 0L,
) {
    val p = LocalPalette.current
    val done = progress?.completed == true
    val fetched = downloadedBytes > 0L
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 11.dp,
        leading = {
            // The resolved station cover already falls back to the show's own
            // artwork when the episode has none (see toStation), so only a
            // show without any cover at all lands on the generated plate.
            val artUrl = station.cover.takeIf { it.startsWith("http") }
                ?: episode.artwork.takeIf { it.startsWith("http") }
            val thumb = rememberArt(url = artUrl)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .then(
                        if (active) Modifier.border(1.dp, p.accent, RoundedCornerShape(KleeampShape.small))
                        else Modifier.border(1.dp, p.frameBorder, RoundedCornerShape(KleeampShape.small))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    SeedPlate(
                        key = station.id.ifBlank { station.url },
                        name = station.name,
                        modifier = Modifier.fillMaxSize(),
                        radius = KleeampShape.small,
                    )
                }
                if (active || done) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(17.dp)
                            .clip(CircleShape)
                            .background(if (active) p.accent else p.chipBorder),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when {
                                playing -> KleeampIcons.Pause
                                done -> KleeampIcons.Check
                                else -> KleeampIcons.PlayRow
                            },
                            null,
                            Modifier.size(9.dp),
                            tint = if (active) p.onAccent else p.inkFaint,
                        )
                    }
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Fetch actions live in the ⋮ menu now; the subtitle below
                // still names the fetch state (offline · size, fetching %).
                OverflowButton(onOpenMenu)
            }
        },
    ) {
        Mono(
            station.name,
            KleeampType.rowPrimary,
            when {
                active -> p.accent
                done -> p.inkTertiary
                else -> p.ink
            },
            maxLines = 2,
        )
        Mono(
            buildList {
                if (!episode.isFull) add(episode.type.lowercase())
                shortDate(episode.publishedAt)?.let { add(it) }
                clock(episode.durationMs)?.let { add(it) }
                if (fetched) add("offline · ${downloadSizeLabel(downloadedBytes)}")
                else when (val d = dlState) {
                    is DownloadState.Active -> add(
                        if (d.indeterminate) "fetching ${downloadSizeLabel(d.bytesRead)}"
                        else "fetching ${(d.fraction * 100).toInt()}%"
                    )
                    is DownloadState.Failed -> add(d.reason)
                    is DownloadState.Idle -> {}
                }
                progress?.takeIf { !it.completed && it.positionMs > 0 }?.let {
                    add("${(it.fraction * 100).toInt()}% in")
                }
                if (done) add("played")
            }.joinToString(" · "),
            KleeampType.rowSecondary,
            if (progress != null && !done) p.amber else p.inkTertiary,
            maxLines = 1,
        )
    }
}

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/** `3 sep` this year, `3 sep 2024` before that. Null when the feed omitted it. */
private fun shortDate(epochMillis: Long): String? {
    if (epochMillis <= 0) return null
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val thisYear = Instant.now().atZone(zone).year
    val f = if (date.year == thisYear) dayMonth else dayMonthYear
    return date.format(f).lowercase()
}

/** `1h 35m` / `28m`. Null when the feed had no duration. */
private fun clock(durationMs: Long): String? {
    if (durationMs <= 0) return null
    val total = durationMs / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
