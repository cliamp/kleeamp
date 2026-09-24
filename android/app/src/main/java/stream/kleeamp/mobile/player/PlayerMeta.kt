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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import stream.kleeamp.mobile.chrome.OutputMenu
import stream.kleeamp.mobile.chrome.Scrubber
import stream.kleeamp.mobile.chrome.StreamingRule
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.ArtPlate
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberAudioOutputs
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.player.vis.VisualizerMeter
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.model.NowPlaying


/** Compact outlined header control, with a full-height touch target. */
@Composable
internal fun LyricsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(48.dp)
            .semantics { role = Role.Button }
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mono("LYRICS", KleeampType.chip, p.ink, maxLines = 1)
        }
    }
}

/** Compact outlined header control, with a full-height touch target. */
@Composable
internal fun UpNextButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(48.dp)
            .semantics { role = Role.Button }
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(KleeampIcons.UpNextTabLines, null, Modifier.size(14.dp), tint = p.accent)
            Mono("UP NEXT", KleeampType.chip, p.ink, maxLines = 1)
            Mono(count.toString(), KleeampType.chip, p.inkFaint, maxLines = 1)
        }
    }
}

/** The status strip: ON AIR / BUFFERING badge, then the shuffle-scope-fav keys. */
@Composable
internal fun PlayerStatusRow(
    model: PlayerModel,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    // LIVE: the little signal mark breathes in and out while the stream runs.
    val onAir = model.state.playing && model.reconnect == 0 && model.error == null
    val onAirTransition = rememberInfiniteTransition(label = "onAir")
    val onAirAlpha by onAirTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "onAirAlpha",
    )
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            KleeampIcons.PlayTiny,
            null,
            Modifier.size(width = 9.dp, height = 10.dp),
            tint = p.accent.copy(alpha = if (onAir) onAirAlpha else 1f),
        )
        Mono(
            statusLabel(model),
            KleeampType.nowPlayingLabel,
            statusColor(model),
            modifier = Modifier.consumeAllGestures(),
        )
        Spacer(
            Modifier
                .weight(1f)
                .height(18.dp)
                .consumeAllGestures(),
        )
        OutputAction(
            current = model.currentOutput,
            outputs = model.outputs,
            selectedId = model.outputDevice,
            onSelect = actions.onSelectOutput,
        )
        SmallAction(
            KleeampIcons.Shuffle,
            if (model.shuffled) "stop shuffling" else "shuffle",
            tint = if (model.shuffled) p.accent else p.inkSecondary,
        ) { actions.onToggleShuffle() }
        SpeedAction(speed = model.state.speed) { actions.onCycleSpeed() }
        SmallAction(KleeampIcons.MeterSmall, "scope and equaliser", onClick = actions.onOpenScope)
        SmallAction(
            if (model.isFav) KleeampIcons.StarFilled else KleeampIcons.Star,
            if (model.isFav) "remove favourite" else "favourite",
            tint = if (model.isFav) p.accent else p.inkTertiary,
        ) { actions.onToggleFav() }
    }
}

/**
 * The output key: one tap names the sink the stream is on and offers the other
 * connected ones. The icon tells the route at a glance, and it picks up the
 * accent when audio is not on the phone's own speaker.
 */
@Composable
internal fun OutputAction(
    current: AudioOutput?,
    outputs: List<AudioOutput>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val external = current != null && current.kind != OutputKind.Speaker
    OutputMenu(
        trigger = { onOpen ->
            SmallAction(
                outputIcon(current?.kind),
                "audio output · ${current?.name ?: "system default"}",
                tint = if (external) p.accent else p.inkSecondary,
                onClick = onOpen,
            )
        },
        currentName = current?.name,
        outputs = outputs,
        selectedId = selectedId,
        onSelect = onSelect,
    )
}

internal fun outputIcon(kind: OutputKind?): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    OutputKind.Headphones -> KleeampIcons.Headphones
    OutputKind.Bluetooth -> KleeampIcons.Bluetooth
    OutputKind.Usb -> KleeampIcons.Usb
    else -> KleeampIcons.Speaker
}

/** The station name, stream title and source meta line. All gesture-inert. */
@Composable
internal fun PlayerMeta(
    model: PlayerModel,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        MarqueeLabel(
            model.shownStation?.name ?: "pick a station",
            KleeampType.trackTitle,
            p.ink,
            modifier = Modifier.consumeAllGestures(),
        )
        MarqueeLabel(
            model.streamTitle.ifBlank { model.error ?: artistOrTagLine(model.shownStation) },
            KleeampType.rowPrimary,
            if (model.error != null && model.streamTitle.isBlank()) p.destructiveInk else p.inkSecondary,
            modifier = Modifier.consumeAllGestures(),
        )
        Mono(
            sourceLine(model.shownStation),
            KleeampType.body,
            p.inkTertiary,
            modifier = Modifier.consumeAllGestures(),
            maxLines = 1,
        )
    }
}


/** The "cliamp radio · france · 123 votes" line under the stream title. */
internal fun sourceLine(shownStation: Station?): String {
    val parts = buildList {
        shownStation?.let { s ->
            add(
                when (s.source) {
                    StationSource.Cliamp -> "cliamp radio"
                    StationSource.Directory -> "directory"
                    StationSource.Local -> "on device"
                    StationSource.Provider -> "provider"
                    StationSource.Podcast -> "podcast"
                    StationSource.Custom -> "custom"
                }
            )
            if (s.country.isNotBlank() && s.source != StationSource.Cliamp) add(s.country.lowercase())
            if (s.votes > 0) add("${compact(s.votes)} votes")
        }
    }
    return parts.joinToString(" · ").ifBlank { "15 cliamp channels · 50k+ directory" }
}


/** A 15dp icon in a 28dp tap target, sized for a secondary action. */
@Composable
internal fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(15.dp), tint = tint ?: p.inkSecondary)
    }
}

internal fun speedLabel(v: Float): String {
    val s = if (v % 1f == 0f) v.toInt().toString() else v.toString().trimEnd('0')
    return "${s}×"
}

/** Playback speed as a terse mono key: taps step through the ladder. */
@Composable
internal fun SpeedAction(speed: Float, onClick: () -> Unit) {    val p = LocalPalette.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mono(
            speedLabel(speed),
            KleeampType.meta,
            if (speed != 1f) p.accent else p.inkSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The line beneath the track title in the expanded player: the artist for
 * local files (and podcasts), the stream title normally, and the station tags
 * as a last resort - mirroring the mini player's artist line so the artist is
 * always named under the song.
 */
internal fun artistOrTagLine(station: Station?): String {
    if (station == null) return ""
    return when (station.source) {
        StationSource.Local -> station.artistAlbum
        StationSource.Podcast -> station.artist
        else -> station.tagList.take(3).joinToString(" · ")
    }.ifBlank { station.tagList.take(3).joinToString(" · ") }
}
