package stream.kleeamp.mobile.library

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderCatalog
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.servers.ProviderSpec
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

/**
 * A playlist row's leading thumbnail: the item's exact cover when it has one,
 * with a small play/pause badge overlaid when it is the current track. The
 * cover is resolved the way each source's home screen resolves it - embedded
 * art for local files, the known artwork URL for episodes and provider
 * tracks, branding discovery for radio - so a row never shows a
 * generic note where its home shows real art. Coverless rows wear their
 * home placeholder: the show's PodRow, the themed plate (note for local
 * files and provider tracks, broadcast mark for live stations).
 *
 * Shared with folder rows so they wear exactly what list rows wear.
 */

@Composable
internal fun SongCover(s: Station, current: Station?, playing: Boolean) {
    val p = LocalPalette.current
    val art = rememberStationThumbnail(s)
    val active = current?.url == s.url
    // Coverless rows wear a generated plate seeded by the item - the same
    // plate its home screen shows - instead of a faint outline box, so the
    // fallback follows the theme like everything else.
    if (art == null) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            SeedPlate(
                key = s.id.ifBlank { s.url },
                name = s.name,
                modifier = Modifier.fillMaxSize(),
                radius = KleeampShape.small,
            )
            if (active) CoverBadge(playing)
        }
        return
    }
    // Real art from here on: coverless took the SeedPlate return above.
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(p.panel),
        contentAlignment = Alignment.Center,
    ) {
        Image(art, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (active) CoverBadge(playing)
    }
}

/** The small accent play/pause badge worn over a current track's cover. */
@Composable
private fun CoverBadge(playing: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier
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

/**
 * One tile per song, up to four: a single song fills the tile, two split
 * it, three take a half plus a stacked pair, four grid it. Each cell wears
 * its song's cover, or that song's plate when it has none. The picks are
 * keyed by song id, so a changing group never mixes hook counts between
 * compositions.
 */
@Composable
internal fun CoverCollage(stations: List<Station>, modifier: Modifier = Modifier) {
    val picks = remember(stations) { pickCollage(stations) }
    key(picks.map { it.id }) {
        Box(
            modifier.clip(RoundedCornerShape(KleeampShape.small)),
            contentAlignment = Alignment.Center,
        ) {
            when (picks.size) {
                1 -> CollageCell(picks[0], Modifier.fillMaxSize())
                2 -> Row(Modifier.fillMaxSize()) {
                    picks.forEach { CollageCell(it, Modifier.weight(1f).fillMaxHeight()) }
                }
                3 -> Row(Modifier.fillMaxSize()) {
                    CollageCell(picks[0], Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        CollageCell(picks[1], Modifier.weight(1f).fillMaxWidth())
                        CollageCell(picks[2], Modifier.weight(1f).fillMaxWidth())
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        CollageCell(picks[0], Modifier.weight(1f).fillMaxHeight())
                        CollageCell(picks[1], Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        CollageCell(picks[2], Modifier.weight(1f).fillMaxHeight())
                        CollageCell(picks[3], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageCell(station: Station, modifier: Modifier = Modifier) {
    val art = rememberStationThumbnail(station)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (art != null) {
            Image(art, station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // Square inner corners butt cleanly; the collage clips the edge.
            SeedPlate(
                key = station.id.ifBlank { station.url },
                name = station.name,
                modifier = Modifier.fillMaxSize(),
                radius = 0.dp,
            )
        }
    }
}

/**
 * A fixed 2x2 tile of the first four songs' covers for browse artist rows.
 * Always four cells: covers where songs have them, the song's own plate
 * where it has no cover, and a letterless plate where no song fills the
 * cell, so the tile never shows flat empty ground.
 */
@Composable
internal fun QuadCollage(stations: List<Station>, modifier: Modifier = Modifier) {
    val picks = remember(stations) { stations.take(4) }
    val seed = remember(picks) {
        picks.firstOrNull()?.id?.ifBlank { picks.firstOrNull()?.url }.orEmpty()
    }
    key(picks.map { it.id }) {
        Column(
            modifier.clip(RoundedCornerShape(KleeampShape.small)),
        ) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                QuadCell(picks.getOrNull(0), Modifier.weight(1f).fillMaxHeight(), "$seed-0")
                QuadCell(picks.getOrNull(1), Modifier.weight(1f).fillMaxHeight(), "$seed-1")
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                QuadCell(picks.getOrNull(2), Modifier.weight(1f).fillMaxHeight(), "$seed-2")
                QuadCell(picks.getOrNull(3), Modifier.weight(1f).fillMaxHeight(), "$seed-3")
            }
        }
    }
}

@Composable
private fun QuadCell(station: Station?, modifier: Modifier = Modifier, fallbackKey: String = "") {
    if (station == null) {
        // No song for this cell: letterless plate, never flat ground.
        SeedPlate(
            key = fallbackKey,
            name = null,
            modifier = modifier,
            radius = 0.dp,
        )
        return
    }
    val art = rememberStationThumbnail(station)
    if (art != null) {
        Box(
            modifier,
            contentAlignment = Alignment.Center,
        ) {
            Image(art, station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        return
    }
    // Song without cover: its own plate, like CollageCell and SongCover.
    // Square inner corners butt cleanly; the collage clips the edge.
    SeedPlate(
        key = station.id.ifBlank { station.url },
        name = station.name,
        modifier = modifier,
        radius = 0.dp,
    )
}

/**
 * One group's primary artwork: the first song's cover filling the tile, or
 * the group's own plate when nothing has a cover. Album and artist rows and
 * headers share this so one group reads as one image everywhere, never a
 * grid of the same cover four times.
 */
@Composable
internal fun GroupArt(
    songs: List<Station>,
    modifier: Modifier = Modifier,
    fallbackKey: String,
    fallbackName: String?,
) {
    val first = songs.firstOrNull()
    if (first == null) {
        SeedPlate(
            key = fallbackKey,
            name = null,
            modifier = modifier,
            radius = KleeampShape.small,
        )
        return
    }
    val art = rememberStationThumbnail(first)
    Box(
        modifier.clip(RoundedCornerShape(KleeampShape.small)),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, first.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            SeedPlate(
                key = first.id.ifBlank { first.url },
                name = fallbackName,
                modifier = Modifier.fillMaxSize(),
                radius = 0.dp,
            )
        }
    }
}

/**
 * The songs a collage shows: everything when four or fewer, otherwise four
 * with distinct covers first so one album's art cannot fill the tile alone.
 * Pure for JVM tests.
 */
internal fun pickCollage(songs: List<Station>, max: Int = 4): List<Station> {
    if (songs.size <= max) return songs.toList()
    val picks = ArrayList<Station>(max)
    val seen = HashSet<String>()
    for (s in songs) {
        if (picks.size == max) break
        if (s.cover.isNotBlank() && seen.add(s.cover)) picks += s
    }
    if (picks.size < max) {
        val ids = picks.map { it.id }.toHashSet()
        for (s in songs) {
            if (picks.size == max) break
            if (s.id !in ids) picks += s
        }
    }
    return picks
}

/**
 * One song row for playlists and artist pages: cover, overflow menu and
 * the subtitle every list row wears.
 */
@Composable
internal fun PlaylistMemberRow(
    s: Station,
    current: Station?,
    playing: Boolean,
    queue: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenMenu: (Station) -> Unit,
    subtitle: String = s.artistAlbum,
) {
    val p = LocalPalette.current
    ListRow(
        rail = current?.url == s.url,
        onClick = { onPlay(s, queue) },
        verticalPadding = 9.dp,
        leading = {
            SongCover(s = s, current = current, playing = playing)
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OverflowButton({ onOpenMenu(s) }, size = 16)
            }
        },
    ) {
        Mono(s.name, KleeampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
        Mono(
            subtitle.ifBlank {
                when {
                    s.source == StationSource.Local -> durationLabel(s.durationMs)
                    s.source == StationSource.Podcast -> "podcast"
                    else -> s.meta
                }
            },
            KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
        )
    }
}
