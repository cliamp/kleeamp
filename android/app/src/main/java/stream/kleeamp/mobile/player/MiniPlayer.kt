package stream.kleeamp.mobile.player

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.ArtPlate
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_BACK_SEC
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_FWD_SEC
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** The lockscreen widget's in-app twin: art plate, one key. */
@Composable
@Suppress("LongParameterList")
fun MiniPlayer(
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    buffering: Boolean,
    reconnecting: Int = 0,
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    /** Elapsed/duration ms: paints the position hairline on tracks. */
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    skipBackSec: Int = DEFAULT_SKIP_BACK_SEC,
    skipFwdSec: Int = DEFAULT_SKIP_FWD_SEC,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipFwd: () -> Unit = {},
    onOpenUpNext: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    // The bar never goes away: it shows the current or last-played station,
    // or the empty "nothing playing" state when nothing has played yet.
    val empty = station == null
    // The bar wears the same art-tinted wash as the player screen, quieter
    // so the rows and keys stay legible, and it pulses gently with the
    // music while playing. The nothing-playing state stays plain panel.
    val ambient by rememberPlayerAmbient(station)
    val energy = rememberMusicEnergy(playing) { PlaybackBus.spectrum.value }
    val washGate by animateFloatAsState(if (empty) 0f else 1f, tween(600), label = "miniWash")
    val washAlpha = (0.28f + 0.30f * energy) * washGate

    Column(Modifier.fillMaxWidth()) {
        HairlineDivider(region = true)
        Box(Modifier.fillMaxWidth().background(p.panel)) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to ambient.copy(alpha = washAlpha),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            // Position hairline along the bar's bottom edge on tracks with
            // a known length: overlay only, so the bar measures exactly
            // as before with or without it.
            if (!empty && station.isTrack && durationMs > 0) {
                val done = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(done)
                        .height(2.dp)
                        .background(p.accent),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .microPress(onClick = onOpen)
                    .dragUpToOpen(onOpen)
                    .swipeToStep(onPrev, onNext)
                    .padding(horizontal = Gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            MiniArt(station = station)
            if (empty) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono("nothing playing", KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono("pick a station to start", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono(station.name, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(
                        when {
                            reconnecting > 0 -> "reconnecting · $reconnecting"
                            buffering -> "buffering…"
                            streamTitle.isNotBlank() -> streamTitle
                            else -> artistOrTagLine(station)
                        },
                        KleeampType.rowSecondary,
                        if (buffering || reconnecting > 0) p.amber else p.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            // Queue sits before the transport cluster on the right edge.
            Icon(
                KleeampIcons.UpNextTabLines,
                "up next",
                Modifier
                    .size(16.dp)
                    .microPress(onClick = onOpenUpNext),
                tint = p.ink,
            )
            val isPodcast = station?.source == StationSource.Podcast
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isPodcast) {
                    MiniKey(
                        icon = KleeampIcons.Rewind,
                        label = miniKeyLabel(MiniKeyKind.SkipBack, skipBackSec, skipFwdSec),
                        enabled = true,
                        onClick = onSkipBack,
                        iconModifier = Modifier.size(13.dp),
                    )
                } else {
                    MiniKey(
                        icon = KleeampIcons.Prev,
                        label = miniKeyLabel(MiniKeyKind.Prev, skipBackSec, skipFwdSec),
                        enabled = hasPrev,
                        onClick = onPrev,
                    )
                }
                Box(
                    Modifier
                        .size(38.dp)
                        .microPress(onClick = onToggle)
                        .clip(RoundedCornerShape(KleeampShape.medium))
                        .background(if (p.dark) p.accent else p.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) KleeampIcons.Pause else KleeampIcons.PlayTab,
                        if (playing) "pause" else "play",
                        Modifier.size(if (playing) 13.dp else 15.dp),
                        tint = if (p.dark) p.onAccent else p.ground,
                    )
                }
                if (isPodcast) {
                    MiniKey(
                        icon = KleeampIcons.Forward,
                        label = miniKeyLabel(MiniKeyKind.SkipForward, skipBackSec, skipFwdSec),
                        enabled = true,
                        onClick = onSkipFwd,
                        iconModifier = Modifier.size(13.dp),
                    )
                } else {
                    MiniKey(
                        icon = KleeampIcons.Next,
                        label = miniKeyLabel(MiniKeyKind.Next, skipBackSec, skipFwdSec),
                        enabled = hasNext,
                        onClick = onNext,
                    )
                }
            }
            }
        }
    }
}

/**
 * Dragging the bar upward opens the player, mirroring the sheet's own
 * swipe-down to close. Taps are untouched (no movement, no claim) and
 * drags starting on the keys still belong to them: the tap detectors
 * own the press, this only watches the travel.
 */
private fun Modifier.dragUpToOpen(onOpen: () -> Unit): Modifier = pointerInput(onOpen) {
    val threshold = 64.dp.toPx()
    var travel = 0f
    detectVerticalDragGestures(
        onDragStart = { travel = 0f },
        onVerticalDrag = { _, amount -> travel += amount },
        onDragEnd = { if (travel < -threshold) onOpen() },
        onDragCancel = { travel = 0f },
    )
}

/**
 * Swiping the bar sideways steps the queue: left for next, right for
 * previous. Same travel threshold as the upward open, taps untouched,
 * and a short fling back stays put.
 */
private fun Modifier.swipeToStep(onPrev: () -> Unit, onNext: () -> Unit): Modifier =
    pointerInput(onPrev, onNext) {
        val threshold = 64.dp.toPx()
        var travel = 0f
        detectHorizontalDragGestures(
            onDragStart = { travel = 0f },
            onHorizontalDrag = { _, amount -> travel += amount },
            onDragEnd = {
                if (travel < -threshold) onNext()
                else if (travel > threshold) onPrev()
                travel = 0f
            },
            onDragCancel = { travel = 0f },
        )
    }

/** Kind of key shown on the left/right flanks of the mini bar transport cluster. */
internal enum class MiniKeyKind {
    Prev,
    Next,
    SkipBack,
    SkipForward,
}

/** Pure helper to determine left key type. */
internal fun miniLeftKeyKind(station: Station?): MiniKeyKind =
    if (station?.source == StationSource.Podcast) MiniKeyKind.SkipBack else MiniKeyKind.Prev

/** Pure helper to determine right key type. */
internal fun miniRightKeyKind(station: Station?): MiniKeyKind =
    if (station?.source == StationSource.Podcast) MiniKeyKind.SkipForward else MiniKeyKind.Next

/** Pure helper to format accessible content description. */
internal fun miniKeyLabel(kind: MiniKeyKind, skipBackSec: Int, skipFwdSec: Int): String = when (kind) {
    MiniKeyKind.Prev -> "previous"
    MiniKeyKind.Next -> "next"
    MiniKeyKind.SkipBack -> "skip back $skipBackSec seconds"
    MiniKeyKind.SkipForward -> "skip forward $skipFwdSec seconds"
}

/** Pure helper for seek delta seconds. */
internal fun miniKeySeekDelta(kind: MiniKeyKind, skipBackSec: Int, skipFwdSec: Int): Int? = when (kind) {
    MiniKeyKind.SkipBack -> -skipBackSec
    MiniKeyKind.SkipForward -> skipFwdSec
    MiniKeyKind.Prev, MiniKeyKind.Next -> null
}

/** A small prev/next/skip key for the mini bar transport cluster. */
@Composable
private fun MiniKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier.size(width = 14.dp, height = 11.dp),
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(p.keyFace)
            .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
            .microPress(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            label,
            iconModifier,
            tint = if (enabled) p.ink else p.inkFaint,
        )
    }
}

/**
 * The mini bar's leading slot is always a cover: real art when the station
 * has any, otherwise one of the bundled designs - so the slot is never
 * empty and never needs the meter or the striped plate for a station.
 * Only the nothing-playing state (no station at all) keeps the plate.
 */
/** Synchronous memory peek behind [MiniArt], mirroring its async resolve. The
 * full-size caches are included: the notification path warms those, and the
 * thumbnail downscales them for free - still the new item on frame one. */
private fun peekSmall(station: Station?): androidx.compose.ui.graphics.ImageBitmap? {
    if (station == null) return null
    val bmp = when {
        station.source == StationSource.Local ->
            LocalArt.cachedSmall(station.cover)
                ?: LocalArt.cached(station.cover)
                ?: StationArtSource.cachedSmall(station)
                ?: StationArtSource.cached(station)
        station.cover.startsWith("http") ->
            StationArtSource.cachedUrl(station.cover)
                ?: StationArtSource.cachedSmallUrl(station.cover)
                ?: StationArtSource.cachedSmall(station)
        else ->
            StationArtSource.cachedSmall(station)
                ?: StationArtSource.cached(station)
    }
    return bmp?.asImageBitmap()
}

@Composable
private fun MiniArt(station: Station?) {
    // Same synchronous-first treatment as the player screen: paint a known
    // cover on the first frame instead of flashing the plate on every change.
    // The seeded plate sits underneath from frame one, and the lookup only
    // ever upgrades to real art.
    val seed = remember(station?.id) { peekSmall(station) }
    val art = rememberArt(station = station, kind = ArtKind.Thumb)
    val key = station?.id?.ifBlank { station.url }.orEmpty()
    val bmp = art ?: seed
    if (bmp != null) {
        // Real cover art gets a square thumbnail so the plate reads as a little
        // album square, and so does the generated stand-in.
        Image(
            bitmap = bmp,
            contentDescription = station?.name,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(KleeampShape.medium)),
            contentScale = ContentScale.Crop,
        )
    } else if (station != null && key.isNotEmpty()) {
        // Coverless songs, stations and episodes: a generated plate seeded
        // by the item, never a blank hole.
        SeedPlate(
            key = key,
            name = station.name,
            modifier = Modifier.size(40.dp),
            radius = KleeampShape.medium,
        )
    } else {
        ArtPlate(
            modifier = Modifier.size(40.dp),
            radius = KleeampShape.medium,
        )
    }
}
