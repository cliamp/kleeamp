package stream.kleeamp.mobile.player

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.lyrics.LyricsViewModel

/**
 * Lyrics as a bottom sheet stacked over the player sheet, like Up Next:
 * its previous page is always Now Playing. Back dismisses through
 * [onDismiss]; the player sheet underneath stays open.
 */
@UnstableApi
@Composable
fun LyricsSheet(
    vm: LyricsViewModel,
    onDismiss: () -> Unit,
) {
    FullSheetShell(onDismiss = onDismiss) {
        LyricsScreen(
            vm = vm,
            onBack = onDismiss,
        )
    }
}
