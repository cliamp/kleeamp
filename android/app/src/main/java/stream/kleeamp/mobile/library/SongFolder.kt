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
 * Narrows stations by name, artist or album for the playlist filter boxes.
 * Blank query passes the list through untouched.
 */
fun List<Station>.matching(query: String): List<Station> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return this
    return filter {
        it.name.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.album.lowercase().contains(q) ||
            it.albumArtist.lowercase().contains(q)
    }
}

/**
 * Puts an artist row's cover song at the front of the artist page header
 * list, so the header wears the same image the row did. A blank or absent
 * [coverUrl] leaves the list untouched.
 */
fun artistHeaderSongs(songs: List<Station>, coverUrl: String): List<Station> =
    if (coverUrl.isBlank()) songs else songs.sortedByDescending { it.url == coverUrl }

/** Album/disc/track order shared by artist pages and the row cover pick. */
val artistTrackOrder: Comparator<Station> =
    compareBy<Station> { it.album.lowercase() }
        .thenBy { it.discNo }
        .thenBy { it.trackNo }
        .thenBy { it.name.lowercase() }

/** One artist and their songs for the browse view, artists by name. */
data class ArtistGroup(val name: String, val songs: List<Station>) {
    /** Stable key for LazyColumn: artist name, lowercased. */
    val key: String get() = name.lowercase()

    /**
     * The artist's first track in album order: the cover both the browse row
     * and the artist page header wear, so the two never disagree.
     */
    val coverSong: Station? get() = songs.minWithOrNull(artistTrackOrder)
}

/**
 * Collaboration markers in a track-artist string. Only used when no album
 * artist is tagged, and split at the first marker so "Artist A, Artist B"
 * files under "Artist A" instead of becoming its own combination row.
 */
private val artistCreditSplit = Regex(
    """\s*[,;]\s*|\s+(?:feat\.?|ft\.?|featuring)\s+""",
    RegexOption.IGNORE_CASE,
)

/**
 * The first credited artist from a combined track-artist string. Bands whose
 * names contain "&" or "+" are left whole, because those are names rather
 * than a featured-artist credit.
 */
private fun String.primaryArtistCredit(): String {
    val credit = trim()
    if (credit.isEmpty()) return credit
    if (('&' in credit || '+' in credit) &&
        !Regex("""\b(?:feat\.?|ft\.?|featuring)\b""", RegexOption.IGNORE_CASE).containsMatchIn(credit)
    ) {
        return credit
    }
    return credit.split(artistCreditSplit, limit = 2).first().trim().ifBlank { credit }
}

/**
 * The artist a song is filed under for browse: the album-artist tag when set,
 * otherwise the primary track artist, otherwise "unknown artist". Using the
 * album artist keeps a compilation on one row instead of one row per
 * featured-artist combination; the primary-credit fallback does the same for
 * files whose album-artist tag is missing.
 */
fun Station.browseArtistName(): String =
    albumArtist.trim().ifBlank { artist.primaryArtistCredit() }.ifBlank { "unknown artist" }

/**
 * Groups songs by artist for browse. One row per artist, so a song never
 * shows twice; songs keep the incoming order. Case variants merge under the
 * first-seen spelling, and an untagged album artist falls back to the track
 * artist before "unknown artist".
 */
fun artistsOf(songs: List<Station>): List<ArtistGroup> {
    val byKey = LinkedHashMap<String, MutableList<Station>>()
    val display = LinkedHashMap<String, String>()
    songs.forEach { s ->
        val name = s.browseArtistName()
        val key = name.lowercase()
        display.putIfAbsent(key, name)
        val list = byKey.getOrPut(key) { mutableListOf() }
        if (list.none { it.id == s.id }) list += s
    }
    return byKey
        .map { (key, list) -> ArtistGroup(display.getValue(key), list) }
        .sortedBy { it.name.lowercase() }
}

/** Local-songs sub-tabs: the flat list plus album and artist categories. */
enum class LocalSongsTab(val label: String) {
    Songs("songs"), Albums("albums"), Artists("artists"),
}

/** One album and its songs, keyed by album + album artist. */
data class AlbumGroup(
    val name: String,
    /** Raw album-artist tag; blank when untagged. */
    val albumArtist: String,
    val songs: List<Station>,
) {
    /** Stable key for LazyColumn: album + album artist, lowercased. */
    val key: String get() = "${albumArtist.lowercase()}\u0001${name.lowercase()}"

    /**
     * Shown under the album: the album artist, the lone track artist when
     * untagged, or Various Artists for untagged multi-artist sets.
     */
    val displayArtist: String get() = albumDisplayArtist(albumArtist, songs)
}

/**
 * Display artist for an album: the album-artist tag wins; untagged albums
 * show their lone track artist, or Various Artists when those differ.
 */
fun albumDisplayArtist(albumArtist: String, songs: List<Station>): String = when {
    albumArtist.isNotBlank() -> albumArtist
    songs.map { it.artist.ifBlank { "unknown artist" } }.toSet().size == 1 ->
        songs.first().artist.ifBlank { "unknown artist" }
    else -> "Various Artists"
}

/**
 * Groups songs by album for the albums tab: compilations stay one album via
 * the album-artist tag instead of shattering per track artist. Blank tags
 * merge by album name. Same-named albums from different album artists stay
 * apart. Songs inside play in disc/track order.
 */
fun albumsOf(songs: List<Station>): List<AlbumGroup> {
    val byKey = LinkedHashMap<String, MutableList<Station>>()
    val meta = LinkedHashMap<String, Pair<String, String>>()
    songs.forEach { s ->
        val album = s.album.ifBlank { "unknown album" }
        val albumArtist = s.albumArtist
        val key = "${albumArtist.lowercase()}\u0001${album.lowercase()}"
        val list = byKey.getOrPut(key) { mutableListOf() }
        if (list.none { it.id == s.id }) list += s
        meta.putIfAbsent(key, album to albumArtist)
    }
    return byKey
        .map { (key, list) ->
            val (album, albumArtist) = meta.getValue(key)
            AlbumGroup(
                name = album,
                albumArtist = albumArtist,
                songs = list.sortedWith(
                    compareBy<Station> { it.discNo }.thenBy { it.trackNo }.thenBy { it.name.lowercase() },
                ),
            )
        }
        .sortedWith(compareBy<AlbumGroup> { it.name.lowercase() }.thenBy { it.displayArtist.lowercase() })
}

/**
 * The LIB tab's local half. Reads the phone's audio library via [LocalLibrary]
 * and lets the user play any song (through the normal Station pipeline, so
 * queue/prev/next/artwork all just work) and build playlists with covers.
 *
 * This renders the list only. Detail pages (providers, smart playlist,
 * playlist, song info) are separate navigation destinations
 * ([LibraryProvidersPane], [LibrarySmartPlaylistPane], [LibraryPlaylistPane],
 * [LibrarySongInfoPane]) so the back gesture animates them with the native
 * slide+scale transition while the mini player stays visible beneath.
 */
