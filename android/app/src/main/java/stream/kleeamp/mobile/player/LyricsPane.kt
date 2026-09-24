package stream.kleeamp.mobile.player

import androidx.activity.compose.BackHandler
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.lyrics.LyricsViewModel
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** Offset step matching cliamp's `[`/`]` keys. */
private const val OFFSET_STEP_MS = 250

private fun offsetLabel(v: Int): String = when {
    v > 0 -> "+${v}ms"
    v < 0 -> "${v}ms"
    else -> "±0ms"
}

/**
 * Full-screen synced lyrics, cliamp's `y` screen as an overlay. The active
 * line follows the player clock plus the persisted offset; scrolling by
 * hand pauses the auto-follow until the next line turns.
 */
@UnstableApi
@Composable
internal fun LyricsOverlay(
    vm: LyricsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(ui.activeIndex) {
        if (!listState.isScrollInProgress && ui.activeIndex >= 0) {
            listState.animateScrollToItem((ui.activeIndex - 2).coerceAtLeast(0))
        }
    }
    BackHandler(onBack = onBack)
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackChevron(onBack)
            Mono("LYRICS", KleeampType.tabLabel, p.inkTertiary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            OffsetKey("−250") { vm.adjustOffset(-OFFSET_STEP_MS) }
            Mono(offsetLabel(ui.offsetMs), KleeampType.meta, p.inkSecondary, maxLines = 1)
            OffsetKey("+250") { vm.adjustOffset(OFFSET_STEP_MS) }
        }
        Spacer(Modifier.height(8.dp))
        when (ui.status) {
            LyricsViewModel.Status.Loading -> CenterLyricNote("finding lyrics…")
            LyricsViewModel.Status.Missing -> CenterLyricNote("no lyrics found")
            LyricsViewModel.Status.Instrumental -> CenterLyricNote("instrumental")
            LyricsViewModel.Status.Ready -> {
                if (ui.lines.isEmpty()) {
                    PlainLyrics(ui.plain)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = Gutter),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item { Spacer(Modifier.height(12.dp)) }
                        itemsIndexed(ui.lines, key = { _, line -> line.timeMs }) { index, line ->
                            val active = index == ui.activeIndex
                            Mono(
                                line.text,
                                if (active) KleeampType.trackTitle else KleeampType.rowPrimary,
                                if (active) p.accent else p.inkSecondary,
                            )
                        }
                        item { Spacer(Modifier.height(48.dp)) }
                    }
                }
            }
        }
    }
}

/** Unsynced plain lyrics: readable and scrollable, nothing to follow. */
@Composable
private fun PlainLyrics(text: String) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        Mono(
            text.ifBlank { "no lyrics found" },
            KleeampType.rowPrimary,
            p.inkSecondary,
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun CenterLyricNote(text: String) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Mono(text, KleeampType.rowPrimary, p.inkFaint, maxLines = 1)
    }
}

/** Small outlined key for the offset stepper. */
@Composable
private fun OffsetKey(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(KleeampShape.small))
            .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Mono(label, KleeampType.meta, p.inkSecondary, maxLines = 1)
    }
}
