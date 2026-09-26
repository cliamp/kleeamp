package stream.kleeamp.mobile.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.chrome.SheetDragHandle
import stream.kleeamp.mobile.chrome.SheetStatusBarIcons
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.playback.PlayerConnection
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Up Next as a bottom sheet stacked over the player sheet: the menu's
 * exact animation, scrim, corners and handle at full height, so its
 * previous page is always Now Playing. Back dismisses through
 * [onDismiss]; the player sheet underneath stays open.
 *
 * Swipe-down is cancelable by distance: the sheet follows the finger,
 * but only a pull past half the height may settle away - anything
 * shorter, slow drag or fling, springs back to full. Dismissal otherwise
 * stays on back, the chevron and the scrim tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun UpNextSheet(
    player: PlayerConnection,
    current: Station?,
    playing: Boolean,
    onPlay: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    SheetStatusBarIcons()
    // Dismiss only past half the height: a shorter pull always settles
    // back, however fast the finger was moving on release.
    val travel = rememberSwipeTravel()
    val dismissPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx() * SHEET_DISMISS_FRACTION
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                target != SheetValue.Hidden || travel.floatValue > dismissPx
            },
        ),
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
        // Travel observer only: consumes nothing, the sheet keeps its own
        // finger follow.
        Box(Modifier.fillMaxSize().trackSwipeTravel(travel)) {
            UpNextScreen(
                player = player,
                current = current,
                playing = playing,
                onPlay = onPlay,
                onBack = onDismiss,
            )
        }
    }
}
