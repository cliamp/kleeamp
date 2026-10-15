package stream.kleeamp.mobile.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.art.ArtResolve
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.player.vis.StereoMetrics
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.playback.AudioOutput
import stream.kleeamp.mobile.playback.AudioOutputs
import stream.kleeamp.mobile.playback.OutputKind
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerState
import stream.kleeamp.mobile.chrome.clock
import stream.kleeamp.mobile.chrome.compact
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.MechKey
import stream.kleeamp.mobile.chrome.MeterSize
import stream.kleeamp.mobile.chrome.SheetDragHandle
import stream.kleeamp.mobile.chrome.MarqueeLabel
import stream.kleeamp.mobile.chrome.Scrubber
import stream.kleeamp.mobile.chrome.StreamingRule
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.ArtPlate
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.chrome.rememberAudioOutputs
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.player.vis.VisualizerMeter
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.model.NowPlaying


/**
 * The visualizer alone, edge to edge. Entering flips the activity to sensor
 * landscape and hides the bars; leaving puts both back. A double tap or Back
 * is the way out.
 */
@Composable
internal fun FullscreenVisualizer(model: PlayerModel, onExit: () -> Unit) {
    val p = LocalPalette.current
    FullscreenEffect(onExit)
    Box(
        Modifier
            .fillMaxSize()
            .background(p.groundScope)
            .pointerInput(onExit) { detectTapGestures(onDoubleTap = { onExit() }) },
    ) {
        VisualizerMeter(
            mode = Visualizer.byId(model.visualizer),
            columns = FULLSCREEN_COLUMNS,
            live = model.state.playing,
            spectrumProvider = model.spectrumProvider,
            stereoProvider = model.stereoProvider,
            brick = 5.dp,
            gap = 4.dp,
            modifier = Modifier.fillMaxSize(),
            generation = model.generation,
        )
        Mono(
            "double tap to exit",
            KleeampType.meta,
            p.inkFaint,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
internal fun FullscreenEffect(onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousOrientation = activity.requestedOrientation
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    BackHandler(onBack = onExit)
}


/** The portrait player: art plate over text, meter, then the transport. */
@Composable
internal fun PortraitPlayer(
    model: PlayerModel,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The rows below the plate indent to the plate's own edges. The plate
    // is centred and height-bound; the pager viewport runs edge to edge
    // so sliding plates are cut at the screen edge, never mid-screen.
    // Plate size is known after layout: SideEffect below publishes it
    // once placed, then the text, meter and keys inset by the plate's
    // frame margin. Each row carries its own Gutter explicitly.
    // The plate size never depends on sibling padding (height-bound off
    // the flex remainder, width-bound off the frame), so the inset is
    // stable and cannot feed back into the plate size.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    var plateWidthPx by remember { mutableIntStateOf(0) }
    val edge = if (plateWidthPx > 0) {
        // Shaved a little: the rows sit just outside the plate edges so
        // the sides don't read as empty.
        (((screenWidth - with(density) { plateWidthPx.toDp() }) / 2 - Gutter - 4.dp))
            .coerceAtLeast(0.dp)
    } else {
        0.dp
    }
    // No status-bars padding on the rows: the handle zone below already
    // sits below the status bar, and padding twice shrinks the art plate.
    // Full-bleed: the backdrop paints the whole sheet surface (clipped to
    // its rounded top like the container was), and the drag handle rides
    // inside the content over the wash - so no flat container strip ever
    // shows above the handle or below the transport.
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        val ambient by rememberPlayerAmbient(model.shownStation)
        PlayerBackdrop(
            ambient = ambient,
            playing = model.state.playing,
            spectrum = model.spectrumProvider,
            modifier = Modifier.fillMaxSize(),
        )
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            SheetDragHandle()
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter + edge).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackChevron(actions.onBack)
            Spacer(Modifier.weight(1f))
            LyricsBadge(actions.onOpenLyrics)
            UpNextBadge(model.upNextCount, actions.onOpenUpNext)
        }
        // Tight: buys room back for the plate (see pinned text lines).
        Spacer(Modifier.height(2.dp))
        // The concept's art plate is `flex: 0 1 auto; max-height: 284px`, i.e.
        // it is the first thing to give way. Compose has no shrink factor, so
        // the plate is given whatever height is left once the text block below
        // it has been measured - it shrinks on short frames or large font
        // scales instead of pushing the source line under the meter.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            // The art plate and the text block below it share a flexed block
            // that absorbs however tall a long station name or stream title
            // grows, so the meter and the transport beneath stay pinned and
            // never shrink or shift when the names change length. Pinned to
            // the top (not centred) so the cover sits higher and the source
            // line under the artist can never be pushed off the bottom.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
            ) {
                // One fixed plate: the full column width capped at 340dp,
                // centred with even margins both sides - the v0.4.0 recipe.
                // minOf with maxHeight keeps it square, never squeezed,
                // on short frames. Switching stations never moves the
                // text, meter or keys.
                BoxWithConstraints(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    // Height-bound: leave a small breather above and below the plate.
                    // The viewport itself is edge to edge (maxWidth is the
                    // screen now); plates glide over ground and are cut at
                    // the screen edge instead of diving under background
                    // mid-screen.
                    val side = minOf(maxWidth, maxHeight - 12.dp, 340.dp)
                    SideEffect {
                        plateWidthPx = with(density) { side.roundToPx() }
                    }
                    CoverSkip(
                        current = model.shownStation,
                        previous = model.previousStation,
                        next = model.nextStation,
                        canSkipPrevious = model.state.hasPrev,
                        canSkipNext = model.state.hasNext,
                        onSkipPrevious = actions.onPrev,
                        onSkipNext = actions.onNext,
                        modifier = Modifier.fillMaxWidth().height(side),
                    )
                }

                // The station name, stream title and meta line below the
                // plate are gesture-inert: taps and swipes on them (or
                // anywhere around the centre of the expanded player) can
                // never advance or restart the song. Only the small action
                // icons in the strip above stay live.
                Column(
                    Modifier.padding(horizontal = Gutter + edge),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PlayerStatusRow(
                        model = model,
                        actions = actions,
                    )
                    PlayerMeta(model)
                }
            }

            PlayerTransport(
                model,
                actions,
                Modifier.padding(horizontal = Gutter + edge),
                meterHeight = 90.dp,
            )

            TransportKeys(
                model,
                actions,
                Modifier.padding(horizontal = Gutter + edge),
            )
        }
        Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * The landscape player: art plate on the left, everything else on the right.
 * Short frames cannot fit the portrait stack, so the right pane runs a
 * compact meter with tighter gaps and both panes scroll instead of pushing
 * the transport keys off the bottom: every control stays reachable on any
 * height, and the keys ride directly under the transport with any slack
 * pooling below them.
 */
@Composable
internal fun LandscapePlayer(
    model: PlayerModel,
    actions: PlayerActions,
    frameWidth: androidx.compose.ui.unit.Dp,
    frameHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // Full-bleed like portrait: the backdrop owns the whole surface and
    // the handle rides inside it, so the container never shows.
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        val ambient by rememberPlayerAmbient(model.shownStation)
        PlayerBackdrop(
            ambient = ambient,
            playing = model.state.playing,
            spectrum = model.spectrumProvider,
            modifier = Modifier.fillMaxSize(),
        )
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            SheetDragHandle()
        }
    Row(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val side = minOf(frameHeight - 76.dp, (frameWidth - Gutter * 2) * 0.44f).coerceIn(96.dp, 360.dp)
        Column(
            Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            // The status badge and keys ride exactly under the cover: same
            // width, same edges, instead of the full column width.
            Column(
                Modifier.width(side),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CoverSkip(
                    current = model.shownStation,
                    previous = model.previousStation,
                    next = model.nextStation,
                    canSkipPrevious = model.state.hasPrev,
                    canSkipNext = model.state.hasNext,
                    onSkipPrevious = actions.onPrev,
                    onSkipNext = actions.onNext,
                    modifier = Modifier.size(side),
                )
                PlayerStatusRow(model, actions)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier
                .weight(1.05f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // No back chevron in landscape: system back covers it, and the
            // row belongs to the title. Up Next rides at the title's right
            // edge; the weighted meta never runs underneath it.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The end padding is the gap: long titles scroll inside
                // their lane and stop here instead of sticking to the badge.
                Box(Modifier.weight(1f).padding(end = 12.dp)) {
                    PlayerMeta(model)
                }
                LyricsBadge(actions.onOpenLyrics)
                UpNextBadge(model.upNextCount, actions.onOpenUpNext)
            }
            Spacer(Modifier.weight(1f))
            PlayerTransport(model, actions, meterHeight = 56.dp)
            TransportKeys(model, actions)
        }
        }
        }
    }
}

/** Synchronous memory peek behind [StationArt]: the same branches as its async
 * resolve, but LRU gets only, so a known cover paints on the first frame. */
internal fun peekArt(station: Station?): ImageBitmap? {
    if (station == null) return null
    val bmp = when {
        station.source == StationSource.Local ->
            stream.kleeamp.mobile.art.LocalArt.cached(station.cover)
                ?: StationArtSource.cached(station)
        station.cover.startsWith("http") ->
            StationArtSource.cachedUrl(station.cover)
                ?: StationArtSource.cached(station)
        else -> StationArtSource.cached(station)
    }
    return bmp?.asImageBitmap()
}

/**
 * Art is never invented, but it is not always absent either. Directory stations
 * usually publish an og:image on their homepage, and that is the station's own
 * branding rather than something we made up, so it is shown when it exists and
 * the striped plate stands in when it does not.
 *
 * The logo is contained, not cropped: most og:images are 1200x630 wordmarks and
 * a square centre crop cuts them in half.
 */
@Composable
internal fun StationArt(
    station: Station?,
    modifier: Modifier = Modifier,
    /** The halo wash - the cover pager disables it. */
    glow: Boolean = true,
    /** The slow swell - the cover pager disables it so pages match. */
    breathe: Boolean = true,
) {
    val p = LocalPalette.current
    // Paint what memory already holds synchronously, so a song change shows
    // its cover on the first frame instead of flashing the empty plate while
    // the async lookup below re-resolves what is already known. The row
    // thumbnail doubles as a progressive preview: soft for a frame or two,
    // then replaced by the full art - still the new item, never empty.
    val seed = remember(station?.id) { peekArt(station) }
    val art = rememberArt(station = station, kind = ArtKind.Full) ?: seed
    val preview = station?.let { rememberStationThumbnail(it) }
    // No cover of its own: a generated plate seeded by the station id, so
    // the hero never reshuffles and never needs the network or the disk.
    val seedKey = station?.id?.ifBlank { station.url }.orEmpty()
    val caption = when {
        station == null -> "[ no station tuned ]"
        station.source == StationSource.Local ->
            if (station.album.isNotBlank()) "[ ${station.album.lowercase()} · ${station.artist.lowercase()} ]"
            else "[ local file ]"
        station.source == StationSource.Provider ->
            if (station.album.isNotBlank()) "[ ${station.album.lowercase()} · ${station.artist.lowercase()} ]"
            else "[ provider ]"
        station.source == StationSource.Podcast ->
            if (station.artist.isNotBlank()) "[ ${station.artist.lowercase()} · podcast ]"
            else "[ podcast ]"
        station.source == StationSource.Cliamp -> "[ ${station.slug} · cliamp radio ]"
        station.countryCode.isNotBlank() -> "[ ${station.countryCode.lowercase()} · live stream ]"
        else -> "[ live stream ]"
    }
    // The plate breathes very slowly while it is up - a 1% swell over several
    // seconds, in place, so the artwork feels alive rather than printed.
    val breathTransition = rememberInfiniteTransition(label = "artBreath")
    val breath by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "artBreathScale",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = art ?: preview
        // The caption names the plate only while it stands in for missing
        // art; a real cover or its thumbnail preview speaks for itself.
        val plateCaption =
            if (bmp == null && (seedKey.isEmpty() || station == null)) caption else null
        if (glow) ArtGlow(Modifier.fillMaxSize())
        // One plate per branch, all on the same clipped layer: a coverless
        // track swaps pixels, never structure or scale.
        val plateModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val swell = if (breathe) breath else 1f
                scaleX = swell
                scaleY = swell
                // Clip the layer itself to the plate shape: without this
                // the layer composites square and gray corners leak.
                clip = true
                shape = RoundedCornerShape(KleeampShape.large)
            }
        if (bmp != null) {
            ArtPlate(
                modifier = plateModifier,
                radius = KleeampShape.large,
            ) {
                // The art swaps instantly between tracks: the warmer keeps the
                // covers in memory, so the new pixels are already there and no
                // dissolve is needed. A cold cover still pops in when it lands -
                // one hard cut instead of a slow fade staircase.
                // Fixed square, always filled, art never cut: square covers
                // draw straight through; anything wider or taller keeps the
                // whole image centred on a blurred, cropped copy of itself
                // that fills the bands, so no dead space and no lost edges.
                val w = bmp.width.coerceAtLeast(1)
                val h = bmp.height.coerceAtLeast(1)
                val aspect = w.toFloat() / h
                if (aspect in 0.9f..1.12f) {
                    Image(
                        bitmap = bmp,
                        contentDescription = station?.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().blur(28.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Box(Modifier.fillMaxSize().background(p.ground.copy(alpha = 0.25f)))
                        Image(
                            bitmap = bmp,
                            contentDescription = station?.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        } else if (station != null && seedKey.isNotEmpty()) {
            // No cover at all: the seeded plate, wearing the caption line so
            // the hero still names what is playing.
            SeedPlate(
                key = seedKey,
                name = station.name,
                modifier = plateModifier,
                caption = caption,
                radius = KleeampShape.large,
            )
        } else {
            ArtPlate(
                modifier = plateModifier,
                radius = KleeampShape.large,
                caption = plateCaption,
            ) {
                station?.let {
                    Icon(
                        KleeampIcons.MusicNote,
                        it.name,
                        Modifier.align(Alignment.Center).size(64.dp),
                        tint = p.accent,
                    )
                }
            }
        }
    }
}
