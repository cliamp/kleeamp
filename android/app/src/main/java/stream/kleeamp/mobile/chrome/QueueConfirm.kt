package stream.kleeamp.mobile.chrome

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The global "Added to Up Next" confirmation: an accent pill that pops in
 * and fades, driven by [PlaybackBus.queuedCount] so every queue add -
 * menu or otherwise - confirms the same way. Replaces the per-row pill
 * the swipe-to-queue gesture wore; the gesture is gone, the feedback is
 * not. Touch-transparent and decorative to accessibility.
 */
@Composable
fun QueueConfirmHost(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val tick by PlaybackBus.queuedCount.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        visible = true
        delay(1500)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.9f, animationSpec = tween(150)),
        exit = fadeOut(tween(400)),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .background(p.accent)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("Added to Up Next", KleeampType.chip, p.onAccent)
            }
        }
    }
}
