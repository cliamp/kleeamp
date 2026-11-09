package stream.kleeamp.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.distinctUntilChanged
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.MechSlider
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.lyrics.LrcLine
import stream.kleeamp.mobile.lyrics.LyricsQuery
import stream.kleeamp.mobile.lyrics.LyricsViewModel
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import kotlin.math.abs
import kotlin.math.roundToInt

/** Same clamp the view model and the pref use. */
private const val MAX_OFFSET_MS = 10_000

/** A row's minimum height; the tap target, not a layout anchor. */
private val LINE_HEIGHT = 36.dp
private val LINE_GAP = 4.dp

/** A stanza break has no words, so it is worth a little more air. */
private val STANZA_GAP = 12.dp

/** How far a jump has to be before the list snaps instead of animating. */
private const val SNAP_DISTANCE = 8

private fun offsetLabel(v: Int): String = when {
    v > 0 -> "+${v}ms"
    v < 0 -> "${v}ms"
    else -> "±0ms"
}

/**
 * Lyrics as sheet content, like the Up Next sheet: a pinned header with the
 * track and a sync control, then the lines.
 *
 * The list is top-anchored, one line at a time: the active line sits at the
 * head with a single line of context above it, so the page reads like a sheet
 * of words instead of drifting through an empty middle. A drag hands
 * scrolling to the user - the follow stays off until the active line is back
 * on screen or `back to line` is tapped - a tap on a line seeks, and a long
 * press syncs the offset to it.
 */
@UnstableApi
@Composable
internal fun LyricsScreen(
    vm: LyricsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val station by PlaybackBus.station.collectAsState()
    var syncOpen by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn()

    Column(modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        ScreenHeader(inset = false) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BackChevron(onBack, Modifier.offset(y = 2.dp))
                    Mono("Lyrics", KleeampType.screenTitle, p.ink, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    SyncBadge(
                        offsetMs = ui.offsetMs,
                        open = syncOpen,
                        onClick = { syncOpen = !syncOpen },
                    )
                }
                // The track as it is named, noise included: a miss usually
                // means the name itself is the problem, so show it.
                val label = queryLabel(ui.query)
                if (label.isNotBlank()) {
                    Mono(
                        label,
                        KleeampType.meta,
                        p.inkTertiary,
                        Modifier.padding(start = 2.dp),
                        maxLines = 1,
                    )
                }
            }
            AnimatedVisibility(
                visible = syncOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                SyncPanel(
                    offsetMs = ui.offsetMs,
                    onSet = vm::setOffset,
                    onReset = vm::resetOffset,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.status) {
                LyricsViewModel.Status.Loading -> LyricsSkeleton()
                LyricsViewModel.Status.NoSong -> LyricsEmpty(
                    station = null,
                    icon = KleeampIcons.MusicNote,
                    title = "nothing playing",
                    sub = "start a track and its words show up here",
                )
                LyricsViewModel.Status.NotFound -> LyricsEmpty(
                    station = station,
                    icon = KleeampIcons.Lyrics,
                    title = "no lyrics found",
                    sub = queryLabel(ui.query).ifBlank { "no artist or title to search with" },
                    action = LyricAction("try again", vm::retry),
                )
                LyricsViewModel.Status.Offline -> LyricsEmpty(
                    station = station,
                    icon = KleeampIcons.SignalBars,
                    title = "couldn't reach the lyrics service",
                    sub = "the track may still have lyrics - check the connection",
                    action = LyricAction("try again", vm::retry),
                )
                LyricsViewModel.Status.Restricted -> LyricsEmpty(
                    station = station,
                    icon = KleeampIcons.SignalBars,
                    title = "lyrics need data",
                    sub = "cellular is off in settings; cached lyrics still show",
                    action = LyricAction("look up anyway", vm::retry),
                )
                LyricsViewModel.Status.Instrumental -> LyricsEmpty(
                    station = station,
                    icon = KleeampIcons.MusicNote,
                    title = "instrumental",
                    sub = "no vocals to follow on this one",
                )
                LyricsViewModel.Status.Ready -> {
                    if (ui.lines.isEmpty()) {
                        PlainLyrics(ui.plain)
                    } else {
                        SyncedLyrics(vm = vm, ui = ui)
                    }
                }
            }
        }
    }
}

/**
 * Sync key: the same outlined badge the player's page keys wear, carrying the
 * offset it is set to. At zero it is the hourglass alone; any live offset puts
 * the value beside it, so a shift the user set is never invisible.
 */
@Composable
private fun SyncBadge(
    offsetMs: Int,
    open: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val live = open || offsetMs != 0
    Box(
        Modifier
            .height(40.dp)
            .semantics { role = Role.Button }
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .border(
                    0.5.dp,
                    if (live) p.accent else p.chipBorder,
                    RoundedCornerShape(KleeampShape.small),
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                KleeampIcons.Sync,
                "lyrics sync",
                Modifier.size(16.dp),
                tint = if (live) p.accent else p.inkSecondary,
            )
            if (offsetMs != 0) {
                Mono(offsetLabel(offsetMs), KleeampType.chip, p.ink, maxLines = 1)
            }
        }
    }
}

/**
 * The sync panel: the offset as a value you can see and drag, the way speed
 * and boost are set elsewhere in the player - a big readout, one slider, a
 * reset. Long-press on a line is still the fast path; this is the precise one.
 */
@Composable
private fun SyncPanel(
    offsetMs: Int,
    onSet: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.panel)
            .padding(horizontal = Gutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .background(p.panelRaised)
                    .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Mono(
                    offsetLabel(offsetMs),
                    KleeampType.time,
                    if (offsetMs != 0) p.accent else p.ink,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Mono("Lyrics offset", KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                Mono(
                    if (offsetMs == 0) "in step with the track" else "long-press a line to snap it",
                    KleeampType.rowSecondary,
                    p.inkTertiary,
                    maxLines = 1,
                )
            }
            if (offsetMs != 0) {
                LyricChip("reset", onClick = onReset)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Mono("−10s", KleeampType.meta, p.inkFaint, maxLines = 1)
            MechSlider(
                value = offsetMs.toFloat(),
                onValueChange = { onSet(snapOffset(it)) },
                range = -MAX_OFFSET_MS.toFloat()..MAX_OFFSET_MS.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Mono("+10s", KleeampType.meta, p.inkFaint, maxLines = 1)
        }
    }
}

/** Slider granularity: 50ms steps, so the value is always a readable figure. */
private fun snapOffset(v: Float): Int =
    ((v / 50f).roundToInt() * 50).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

/**
 * The synced lines, top-anchored. The active line is followed to the head of
 * the list with one line of context above it, so the eye always starts at the
 * same place and the words move up past it.
 */
@UnstableApi
@Composable
private fun SyncedLyrics(
    vm: LyricsViewModel,
    ui: LyricsViewModel.UiState,
) {
    val listState = rememberLazyListState()
    var follow by remember(ui.query) { mutableStateOf(true) }

    // A finger on the list always wins. Only a drag disarms the follow, so
    // the programmatic scroll that keeps the line at the head never does.
    LaunchedEffect(listState, ui.query) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow = false
        }
    }
    // Hand control back as soon as the active line is on screen again: either
    // the user scrolled back to it or they tapped the chip.
    val active by rememberUpdatedState(ui.activeIndex)
    LaunchedEffect(listState, ui.query) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            active >= 0 && visible.any { it.index == active }
        }.distinctUntilChanged().collect { seen -> if (seen) follow = true }
    }
    LaunchedEffect(ui.activeIndex, follow, ui.query) {
        if (!follow || ui.activeIndex < 0) return@LaunchedEffect
        // One line of context above the active one, so it is never the very
        // first thing against the header.
        val target = (ui.activeIndex - 1).coerceAtLeast(0)
        // A seek lands far away: jump, so the list does not sail through
        // every verse in between.
        if (abs(target - listState.firstVisibleItemIndex) > SNAP_DISTANCE) {
            listState.scrollToItem(target)
        } else {
            listState.animateScrollToItem(target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = Gutter),
            state = listState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(LINE_GAP),
        ) {
            itemsIndexed(ui.lines) { index, line ->
                if (line.text.isBlank()) {
                    // Stanza break: a gap instead of a missing line.
                    Spacer(Modifier.height(STANZA_GAP))
                } else {
                    LyricLine(
                        line = line,
                        index = index,
                        activeIndex = ui.activeIndex,
                        onSeek = { vm.seekTo(line.timeMs) },
                        onSync = { vm.syncTo(line.timeMs) },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !follow && ui.activeIndex >= 0,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Gutter, bottom = 14.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LyricChip("back to line", onClick = { follow = true })
        }
    }
}

/**
 * One line: accent when it is the one being sung, dimmer with distance. No
 * size change and no underline - a lyrics page should look like text, not
 * like a list with effects on it.
 */
@Composable
private fun LyricLine(
    line: LrcLine,
    index: Int,
    activeIndex: Int,
    onSeek: () -> Unit,
    onSync: () -> Unit,
) {
    val p = LocalPalette.current
    val active = index == activeIndex
    val color by animateColorAsState(
        targetValue = when {
            active -> p.accent
            abs(index - activeIndex) <= 2 -> p.inkSecondary
            else -> p.inkFaint
        },
        animationSpec = tween(durationMillis = 220),
        label = "lyricColor",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = LINE_HEIGHT)
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { onSeek() },
                    onLongPress = { onSync() },
                )
            }
            .semantics {
                role = Role.Button
                onClick(label = "seek to line") {
                    onSeek()
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Mono(
            line.text,
            if (active) KleeampType.lyricActive else KleeampType.lyricLine,
            color,
        )
    }
}

/** Unsynced plain lyrics: same rhythm as the synced page, nothing to follow. */
@Composable
private fun PlainLyrics(text: String) {
    val p = LocalPalette.current
    val lines = remember(text) { text.lines().map { it.trim() } }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Gutter),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(LINE_GAP),
    ) {
        itemsIndexed(lines) { _, line ->
            if (line.isBlank()) {
                Spacer(Modifier.height(STANZA_GAP))
            } else {
                Mono(line, KleeampType.lyricLine, p.inkSecondary)
            }
        }
    }
}

/** Placeholder lines at the page's own rhythm: no jump when words land. */
@Composable
private fun LyricsSkeleton() {
    val p = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "lyricsSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lyricsPulse",
    )
    val widths = remember { listOf(0.72f, 0.44f, 0.86f, 0.61f, 0.35f, 0.9f, 0.52f) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        widths.forEach { width ->
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .height(15.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(p.inkFaint.copy(alpha = pulse)),
            )
        }
    }
}

/**
 * The page when there is nothing to read: the cover stays present behind the
 * note, and every state says what happened and offers the one useful action.
 */
@Composable
private fun LyricsEmpty(
    station: Station?,
    icon: ImageVector,
    title: String,
    sub: String = "",
    action: LyricAction? = null,
) {
    val p = LocalPalette.current
    val art = rememberArt(station = station, kind = ArtKind.Full)
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(48.dp).alpha(0.16f),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = Gutter * 2),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = p.inkFaint)
            Spacer(Modifier.height(14.dp))
            Mono(
                title,
                KleeampType.rowPrimary.copy(textAlign = TextAlign.Center),
                p.ink,
                maxLines = 2,
            )
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Mono(
                    sub,
                    KleeampType.rowSecondary.copy(textAlign = TextAlign.Center),
                    p.inkFaint,
                    maxLines = 3,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(18.dp))
                LyricChip(action.label, primary = true, onClick = action.onClick)
            }
        }
    }
}

/** Outlined key: the sync stepper, the absence action, the follow chip. */
@Composable
private fun LyricChip(
    label: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .border(
                1.dp,
                if (primary) p.accent else p.chipBorder,
                RoundedCornerShape(KleeampShape.small),
            )
            .semantics { role = Role.Button }
            .microPress(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Mono(
            label,
            KleeampType.meta,
            if (primary) p.accent else p.inkSecondary,
            maxLines = 1,
        )
    }
}

/** A line longer than the phone sleeps; a lyric page is a reading page. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
}

private data class LyricAction(val label: String, val onClick: () -> Unit)

/** "Singer – Song" for the lookup states; blank when there is nothing. */
private fun queryLabel(query: LyricsQuery?): String {
    if (query == null) return ""
    return listOf(query.artist, query.title)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" – ")
}
