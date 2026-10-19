package stream.kleeamp.mobile.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.KleeampApp
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.model.deEchoArtistLine
import stream.kleeamp.mobile.library.durationLabel
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.playback.DurationProbe
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerConnection
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.art.ArtResolve
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.player.vis.VisualizerMeter
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.MarqueeLabel
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampPalette
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalHapticsEnabled
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The always-available Up Next panel: a pinned "now playing" card on top, then
 * the rest as up-next with reorder / remove. Works like a music player's list
 * — a live radio stream is one LIVE item, a local song shows its duration.
 */
@UnstableApi
@Composable
fun UpNextScreen(
    player: PlayerConnection,
    current: Station?,
    playing: Boolean,
    onPlay: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val upNext by player.panelList.collectAsStateWithLifecycle()
    val upNextIndex by player.panelIndex.collectAsStateWithLifecycle()
    val canUndo by player.canUndo.collectAsStateWithLifecycle()
    // The card meter follows the applied visualizer like every other meter;
    // read the setting here so only this screen recomposes when it changes.
    val app = LocalContext.current.applicationContext as KleeampApp
    val visualizer by app.prefs.visualizer.collectAsState(initial = "spectrum")
    val generation by PlaybackBus.generation.collectAsState()
    // Every played position, keyed by URL, carries the probed duration the
    // file itself never told us - channel tracks learn their length here.
    val progress by app.podcasts.progress.collectAsState(initial = emptyMap())
    val savedDurations = remember(progress) { progress.mapValues { it.value.durationMs } }
    val playerState by player.state.collectAsStateWithLifecycle()
    // Fill unknown queue lengths in the background: the server never sends
    // durations, so an unplayed channel track reads unknown until something
    // probes its headers. One fetch per URL, results persist in the progress
    // table, leaving the screen cancels the rest.
    LaunchedEffect(upNext) {
        withContext(Dispatchers.IO) {
            upNext.filter { s ->
                s.isTrack && s.durationMs <= 0 && (savedDurations[s.url] ?: 0L) <= 0 &&
                    (s.url.startsWith("http://") || s.url.startsWith("https://"))
            }.forEach { s ->
                ensureActive()
                val ms = DurationProbe.probeMs(s.url)
                if (ms > 0) app.podcasts.saveDurationMs(s.url, ms)
            }
        }
    }
    UpNextContent(
        upNext = upNext,
        activeIndex = upNextIndex.takeIf { upNext.getOrNull(it)?.url == current?.url } ?: -1,
        current = current,
        playing = playing,
        visualizer = visualizer,
        generation = generation,
        savedDurations = savedDurations,
        liveDurationMs = playerState.durationMs,
        onPlay = { if (player.panelList.value == upNext) onPlay(it) },
        onClear = player::clearUpNext,
        canUndo = canUndo,
        onUndo = player::undo,
        onMove = { from, to ->
            if (player.panelList.value == upNext) player.movePanelEntry(from, to)
        },
        onRemove = { index ->
            if (player.panelList.value == upNext) player.removePanelEntry(index)
        },
        onBack = onBack,
        onLoadMore = player::loadMoreQueue,
    )
}

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun UpNextContent(
    upNext: List<Station>,
    activeIndex: Int,
    current: Station?,
    playing: Boolean,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit = {},
    canUndo: Boolean = false,
    onUndo: () -> Unit = {},
    // Pulls the following catalogue page when the loaded rows run out, so a
    // directory queue keeps filling as the sheet is scrolled to its end.
    onLoadMore: () -> Unit = {},
    visualizer: String = "spectrum",
    generation: Int = 0,
    savedDurations: Map<String, Long> = emptyMap(),
    liveDurationMs: Long = 0L,
) {
    val p = LocalPalette.current
    val listState = rememberLazyListState()
    // Warm small art for the queue head on entry and on queue change, so
    // rows compose onto warm memory.
    val resolver = LocalContext.current.contentResolver
    LaunchedEffect(upNext) {
        ArtResolve.prefetchSmall(upNext, resolver)
    }
    val scope = rememberCoroutineScope()
    // One drag state for the screen's lifetime: queue emissions merge into
    // the preview via sync instead of recreating it mid-drag.
    val drag = remember(listState) { UpNextDragState(listState, scope) }
    SideEffect { drag.sync(upNextEntries(upNext, activeIndex)) }
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, upNext.size) {
        if (nearEnd) onLoadMore()
    }
    val latestOnMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled by rememberUpdatedState(LocalHapticsEnabled.current)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(drag, drag.draggingKey, edge) {
        if (drag.draggingKey == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (drag.draggingKey != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = now
            val speed = drag.scrollSpeed(edge)
            if (speed != 0f) listState.scrollBy(speed * seconds)
            drag.drag(0f)
        }
    }

    Column(Modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        // No status inset here: the sheet's handle zone already sits below
        // the status bar, same as the Now Playing page - insetting twice is
        // what left the empty space on top.
        ScreenHeader(inset = false) {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(onBack, Modifier.offset(y = 2.dp))
                Mono("Up Next", KleeampType.screenTitle, p.ink, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (canUndo) {
                    Mono("UNDO", KleeampType.sectionLabel, p.accent,
                        Modifier.microPress(onClick = onUndo).padding(12.dp))
                }
                if (upNextEntries(upNext, activeIndex).isNotEmpty()) {
                    Mono("CLEAR", KleeampType.sectionLabel, p.inkTertiary,
                        Modifier.microPress(onClick = onClear).padding(12.dp))
                }
            }
        }

        if (current != null && activeIndex >= 0) {
            NowPlayingCard(current, playing, p, visualizer, generation, savedDurations, liveDurationMs)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (upNext.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 28.dp)) {
                        Mono(
                            "nothing up next — play a station and its list follows it here",
                            KleeampType.rowSecondary, p.inkFaint,
                        )
                    }
                }
            }

            if (drag.entries.isNotEmpty()) {
                item(key = "up-next-header") {
                    SectionLabel("up next — ${drag.entries.size}", trailing = {
                        Mono("hold to reorder", KleeampType.meta, p.inkFaint)
                    })
                }
                itemsIndexed(drag.entries, key = { _, entry -> entry.key }) { index, entry ->
                    val dragging = drag.draggingKey == entry.key
                    val settling = drag.settlingKey == entry.key
                    val motion = when {
                        dragging -> Modifier.zIndex(1f).graphicsLayer { translationY = drag.dragOffset }
                        settling -> Modifier.zIndex(1f).graphicsLayer { translationY = drag.settlingOffset.value }
                        else -> Modifier.animateItem()
                    }
                    UpNextRow(
                        s = entry.station,
                        duration = upNextDuration(
                            entry.station,
                            savedDurations,
                            liveDurationMs,
                            entry.upNextIndex == activeIndex,
                        ),
                        dragging = dragging,
                        onPlay = { onPlay(entry.upNextIndex) },
                        onRemove = { onRemove(entry.upNextIndex) },
                        dragModifier = Modifier.pointerInput(drag, entry.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { position ->
                                    if (drag.start(entry.key, position.y) && hapticsEnabled) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag.drag(amount.y)
                                },
                                onDragEnd = { drag.finish(latestOnMove) },
                                onDragCancel = drag::cancel,
                            )
                        },
                        modifier = motion,
                        accessibilityActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") {
                                onMove(entry.upNextIndex, drag.entries[index - 1].upNextIndex)
                                true
                            })
                            if (index < drag.entries.lastIndex) add(CustomAccessibilityAction("Move down") {
                                onMove(entry.upNextIndex, drag.entries[index + 1].upNextIndex)
                                true
                            })
                            add(CustomAccessibilityAction("Remove from Up Next") {
                                onRemove(entry.upNextIndex)
                                true
                            })
                        },
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    s: Station,
    playing: Boolean,
    p: KleeampPalette,
    visualizer: String,
    generation: Int = 0,
    savedDurations: Map<String, Long> = emptyMap(),
    liveDurationMs: Long = 0L,
) {
    Column(Modifier.fillMaxWidth().background(p.panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UpNextArtwork(s, Modifier.size(50.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The applied visualizer at card size - never a hardcoded
                    // brick. Brick geometry matches the old strip exactly.
                    VisualizerMeter(
                        mode = Visualizer.byId(visualizer),
                        columns = 16,
                        live = playing,
                        brick = 2.dp,
                        gap = 2.dp,
                        modifier = Modifier.size(width = 62.dp, height = 18.dp),
                        spectrumProvider = { PlaybackBus.spectrum.value },
                        stereoProvider = { PlaybackBus.stereo.value },
                        generation = generation,
                    )
                    Mono(if (playing) "PLAYING" else "PAUSED", KleeampType.tabLabel,
                        if (playing) p.accent else p.inkTertiary)
                }
                // Same marquee title as Now Playing: long names scroll
                // instead of cutting off mid-word.
                key(s.id) {
                    MarqueeLabel(
                        s.name.ifBlank { "unknown" },
                        KleeampType.trackTitleSmall,
                        p.ink,
                    )
                }
                Mono(
                    if (s.isTrack) {
                        "${sourceSubtitle(s)} · ${upNextDuration(s, savedDurations, liveDurationMs, isLive = true)}"
                    } else {
                        sourceSubtitle(s)
                    },
                    KleeampType.meta, p.inkTertiary, maxLines = 1,
                )
            }
        }
        HairlineDivider(region = true)
    }
}

@Composable
private fun UpNextArtwork(station: Station, modifier: Modifier = Modifier) {
    val art = rememberArt(station = station)
    Box(
        modifier
            .clip(RoundedCornerShape(KleeampShape.small)),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, "Cover art for ${station.name}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // Same seeded plate every other row wears, so the queue reads
            // as one list whether or not items carry real art.
            SeedPlate(
                key = station.id.ifBlank { station.url },
                name = station.name,
                modifier = Modifier.fillMaxSize(),
                radius = KleeampShape.small,
            )
        }
    }
}

@Composable
private fun UpNextRow(
    s: Station,
    duration: String,
    dragging: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    accessibilityActions: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    UpNextSwipeToRemove(onRemove = onRemove, modifier = modifier) {
        ListRow(
            modifier = Modifier
                .background(if (dragging) p.panelRaised else p.ground)
                .then(dragModifier)
                .semantics { customActions = accessibilityActions }
                .microPress(onClick = onPlay),
            rail = dragging,
            verticalPadding = 8.dp,
            leading = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(KleeampIcons.DragHandle, null, Modifier.size(16.dp),
                        tint = if (dragging) p.accent else p.inkFaint)
                    UpNextArtwork(s, Modifier.size(42.dp))
                }
            },
            trailing = {
                if (!s.isTrack) {
                    Spacer(Modifier.width(6.dp))
                    LiveBadge(p)
                } else {
                    Spacer(Modifier.width(12.dp))
                    Mono(duration, KleeampType.timeSmall, p.inkFaint, maxLines = 1)
                }
            },
        ) {
            Mono(s.name.ifBlank { "unknown" }, KleeampType.rowPrimary, p.ink, maxLines = 1)
            Mono(sourceSubtitle(s), KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
    }
}

/** Only live sources need a type badge; finite tracks use their duration. */
@Composable
private fun LiveBadge(p: KleeampPalette) {
    Box(
        Modifier
            .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Mono("LIVE", KleeampType.tabLabel, p.inkTertiary)
    }
}

private fun upNextDuration(
    s: Station,
    saved: Map<String, Long> = emptyMap(),
    liveMs: Long = 0L,
    isLive: Boolean = false,
): String {
    // The file's own length first, then the live probe for what's playing,
    // then a previous play's saved probe - and last the background fill,
    // which lands in that same saved map. A track nobody probed yet reads
    // unknown until its headers answer.
    val ms = when {
        s.durationMs > 0 -> s.durationMs
        isLive && liveMs > 0 -> liveMs
        (saved[s.url] ?: 0L) > 0 -> saved[s.url] ?: 0L
        else -> 0L
    }
    return if (ms > 0) durationLabel(ms) else "–:––"
}

private fun sourceSubtitle(s: Station): String {
    if (!s.isTrack) return "live stream"
    val raw = when {
        s.artist.isNotBlank() -> s.artist
        s.album.isNotBlank() -> s.album
        s.source == StationSource.Local -> "local audio"
        s.source == StationSource.Podcast -> "podcast"
        s.source == StationSource.Provider -> "provider"
        else -> ""
    }
    return deEchoArtistLine(raw.split("·"), 2).ifBlank { "unknown" }
}
