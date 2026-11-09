package stream.kleeamp.mobile.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.MenuSheetShell
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * Which list the add-songs sheet grows: a user playlist by slug, or the
 * favourites smart list. The sheet opens over whatever context asked —
 * library list, playlist detail, favourites detail — and dismiss returns in
 * place. Every tap writes through immediately, so there is no done
 * control; the ticked boxes mirror live membership.
 */
sealed interface AddSongsTarget {
    data class Playlist(val slug: String) : AddSongsTarget
    data object Favorites : AddSongsTarget
}

/**
 * The song picker as a bottom sheet: local songs, stations and podcast
 * episodes in sub-tabs with one shared selection. Rides the shared menu
 * shell with a tall fixed body and no shell scroll — the picker's own
 * lists scroll themselves, and a scroll inside a scroll would break them.
 *
 * [onAddFolder] adds the folder-picker action: a whole folder becomes a
 * bulk add of the library tracks inside it. Null hides the action, which is
 * what the favourites sheet does — a smart list is not folder-shaped.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun AddSongsSheet(
    playlistName: String,
    selected: Set<String>,
    localSongs: List<Station>,
    radioStations: List<Station>,
    subscribedShows: List<PodcastShow>,
    showState: ShowState,
    onOpenShow: (PodcastShow) -> Unit,
    onToggle: (Station) -> Unit,
    onAddFolder: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    // One line of feedback for the last folder pick: an add has no visible
    // page behind the sheet to report on, and the notice host sits under it.
    var folderNote by remember { mutableStateOf<String?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val folder = folderPathFor(context, uri)
        if (folder == null) {
            folderNote = "could not read that folder"
            return@rememberLauncherForActivityResult
        }
        val tracks = songsUnder(localSongs, folder)
        val fresh = tracks.count { it.id !in selected }
        folderNote = when {
            tracks.isEmpty() -> "no songs from that folder in your library"
            fresh == 0 -> "everything in that folder is already here"
            else -> {
                onAddFolder?.invoke(folder)
                "added $fresh ${if (fresh == 1) "song" else "songs"} from " +
                    folder.trimEnd('/').substringAfterLast('/')
            }
        }
    }
    MenuSheetShell(
        onDismiss = onDismiss,
        bodyFraction = 0.72f,
        scrollBody = false,
        bodyGutter = 0.dp,
        header = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("add songs", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                    Mono(
                        playlistName.ifBlank { "unknown" },
                        KleeampType.trackTitleCompact,
                        p.ink,
                        maxLines = 1,
                    )
                    folderNote?.let { Mono(it, KleeampType.meta, p.inkTertiary, maxLines = 2) }
                }
                if (onAddFolder != null) {
                    Chip(
                        "add folder",
                        selected = false,
                        onClick = { folderLauncher.launch(null) },
                    )
                }
            }
        },
    ) {
        AddSongsPicker(
            selected = selected,
            playlistName = playlistName,
            showTitle = false,
            localSongs = localSongs,
            radioStations = radioStations,
            subscribedShows = subscribedShows,
            showState = showState,
            onOpenShow = onOpenShow,
            onToggle = onToggle,
        )
    }
}
