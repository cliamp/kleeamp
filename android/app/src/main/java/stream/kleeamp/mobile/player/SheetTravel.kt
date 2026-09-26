package stream.kleeamp.mobile.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Dismiss only past this share of the sheet's height; anything shorter
 * springs back to full with the sheet's own settle, like canceling a
 * back gesture. */
const val SHEET_DISMISS_FRACTION = 0.25f

/**
 * Remembers the downward finger travel of the last swipe, in pixels.
 * Written by [trackSwipeTravel], read by the sheet's settle veto.
 */
@Composable
fun rememberSwipeTravel(): MutableFloatState = remember { mutableFloatStateOf(0f) }

/**
 * Observes downward travel without consuming a pixel: the sheet's own
 * drag keeps following the finger exactly as before, while the settle
 * veto learns how far the finger actually went. Sees every move even
 * when a scroller or the sheet itself consumes it.
 */
fun Modifier.trackSwipeTravel(travel: MutableFloatState): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var travelPx = 0f
        var lifted = false
        while (!lifted) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                lifted = true
            } else {
                val dy = change.position.y - change.previousPosition.y
                if (dy > 0f) travelPx += dy
            }
        }
        travel.floatValue = travelPx
    }
}
