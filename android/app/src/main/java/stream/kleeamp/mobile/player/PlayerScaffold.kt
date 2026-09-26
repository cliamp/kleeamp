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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
    val p = LocalPalette.current
    // No status-bars padding here: the sheet's handle zone already sits
    // below the status bar, and padding twice shrinks the art plate.
    Column(modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackChevron(actions.onBack)
            Spacer(Modifier.weight(1f))
            UpNextButton(model.upNextCount, actions.onOpenUpNext)
        }
        Spacer(Modifier.height(8.dp))
        // The concept's art plate is `flex: 0 1 auto; max-height: 284px`, i.e.
        // it is the first thing to give way. Compose has no shrink factor, so
        // the plate is given whatever height is left once the text block below
        // it has been measured - it shrinks on short frames or large font
        // scales instead of pushing the source line under the meter.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
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
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
            ) {
                // Yesterday's full-screen player always resolved the plate to
                // the 340.dp cap; inside the sheet the leftover height is
                // smaller, so including maxHeight here shrank it. Pin the
                // plate to that size: width still concedes on narrow frames,
                // height never shrinks it.
                BoxWithConstraints(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth, 340.dp)
                    StationArt(
                        station = model.shownStation,
                        modifier = Modifier.size(side),
                    )
                }

                // The station name, stream title and meta line below the
                // plate are gesture-inert: taps and swipes on them (or
                // anywhere around the centre of the expanded player) can
                // never advance or restart the song. Only the small action
                // icons in the strip above stay live.
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    PlayerStatusRow(
                        model = model,
                        actions = actions,
                    )
                    PlayerMeta(model)
                }
            }

            PlayerTransport(model, actions)

            TransportKeys(model, actions)
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * The landscape player: art plate on the left, everything else on the right.
 * The transport keeps its full height of keys instead of conceding them to a
 * short portrait column, and the meter/queue/now-tuned rows pin to the bottom
 * of the right pane while the title block flexes above them.
 */
@Composable
internal fun LandscapePlayer(
    model: PlayerModel,
    actions: PlayerActions,
    frameWidth: androidx.compose.ui.unit.Dp,
    frameHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val side = minOf(frameHeight - 76.dp, (frameWidth - Gutter * 2) * 0.44f).coerceIn(96.dp, 340.dp)
        Column(
            Modifier.weight(0.95f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            // The status badge and keys ride exactly under the cover: same
            // width, same edges, instead of the full column width.
            Column(
                Modifier.width(side),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StationArt(
                    station = model.shownStation,
                    modifier = Modifier.size(side),
                )
                PlayerStatusRow(model, actions)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier
                .weight(1.05f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(actions.onBack)
                Spacer(Modifier.weight(1f))
                UpNextButton(model.upNextCount, actions.onOpenUpNext)
            }
            PlayerMeta(model)
            Spacer(Modifier.weight(1f))
            PlayerTransport(model, actions)
            TransportKeys(model, actions)
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
        ArtGlow(Modifier.fillMaxSize())
    ArtPlate(
        modifier = Modifier
            .fillMaxSize()
            // Soft drop shadow so the plate floats over the page - the
            // premium read, same large radius as the plate itself. A touch
            // lighter on light grounds, where the same elevation reads
            // stronger against the pale ground.
            .shadow(if (p.dark) 26.dp else 20.dp, RoundedCornerShape(KleeampShape.large))
            .graphicsLayer {
                scaleX = breath
                scaleY = breath
            },
        radius = KleeampShape.large,
        caption = if (art == null && preview == null && (seedKey.isEmpty() || station == null)) caption else null,
    ) {
        (art ?: preview)?.let { bmp ->
            // Real album art is square and fills the plate edge to edge. Radio
            // art does not have to be: og:images are typically 1200x630
            // wordmarks, and cropping one to a square cuts it in half, so
            // those stay inset and contained - unless the bitmap itself is
            // square enough (a station logo rather than a wordmark) and big
            // enough not to turn to mush, in which case it fills like album
            // art instead of floating small in the middle of the plate.
            // Podcast artwork is square by Apple's own requirement, so it
            // belongs with the album art that fills the plate, not with the
            // 1200x630 radio wordmarks that have to stay inset.
            val albumArt = station?.source == StationSource.Local ||
                station?.source == StationSource.Provider ||
                station?.source == StationSource.Podcast
            val squarish = run {
                val w = bmp.width.coerceAtLeast(1)
                val h = bmp.height.coerceAtLeast(1)
                val aspect = w.toFloat() / h
                aspect in 0.85f..1.18f && minOf(w, h) >= 96
            }
            val fills = albumArt || squarish
            Image(
                bitmap = bmp,
                contentDescription = station?.name,
                modifier =
                    if (fills) Modifier.fillMaxSize()
                    else Modifier.fillMaxSize().padding(14.dp),
                contentScale = if (fills) ContentScale.Crop else ContentScale.Fit,
            )
        }
        // No cover at all: the seeded plate, wearing the caption line so
        // the hero still names what is playing.
        if (art == null && preview == null) {
            if (station != null && seedKey.isNotEmpty()) {
                SeedPlate(
                    key = seedKey,
                    name = station.name,
                    modifier = Modifier.fillMaxSize(),
                    caption = caption,
                    radius = KleeampShape.large,
                )
            } else station?.let {
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
