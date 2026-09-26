package stream.kleeamp.mobile.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.SheetDragHandle
import stream.kleeamp.mobile.chrome.SheetStatusBarIcons
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The scope (visualizer plus equalizer) as a bottom sheet stacked over
 * the player sheet: the menu's exact animation, scrim, corners and
 * handle at full height. The sheets underneath stay open in place -
 * nothing closes just to reopen on back; back peels one layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeSheet(
    prefs: Prefs,
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    SheetStatusBarIcons()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.ground,
        contentColor = p.ink,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        // Padded below the status bar: the handle must never sit under
        // the time and notification icons.
        dragHandle = {
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(bottom = 2.dp),
            ) {
                SheetDragHandle()
            }
        },
        // The screen pads itself for the navigation bar; the sheet must
        // not pad twice.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        ScopeScreen(
            prefs = prefs,
            station = station,
            streamTitle = streamTitle,
            playing = playing,
            onBack = onDismiss,
        )
    }
}
