package stream.kleeamp.mobile.player

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.lyrics.LyricsQuery
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
 * Synced lyrics as sheet content, like the Up Next sheet: a pinned header
 * with the offset stepper, then the lines. The active line follows the
 * player clock plus the persisted offset; scrolling by hand pauses the
 * auto-follow until the next line turns.
 */
@UnstableApi
@Composable
internal fun LyricsScreen(
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
    Column(modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        // No status inset here: the sheet's handle zone already sits below
        // the status bar, same as the Up Next page.
        ScreenHeader(inset = false) {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(onBack, Modifier.offset(y = 2.dp))
                Mono("Lyrics", KleeampType.screenTitle, p.ink, maxLines = 1)
                Spacer(Modifier.weight(1f))
                OffsetKey("−250") { vm.adjustOffset(-OFFSET_STEP_MS) }
                Mono(offsetLabel(ui.offsetMs), KleeampType.meta, p.inkSecondary, maxLines = 1)
                OffsetKey("+250") { vm.adjustOffset(OFFSET_STEP_MS) }
            }
        }
        when (ui.status) {
            LyricsViewModel.Status.Loading -> {
                val looking = queryLabel(ui.query)
                CenterLyricNote(if (looking.isBlank()) "finding lyrics…" else "looking up $looking…")
            }
            LyricsViewModel.Status.NoSong -> CenterLyricNote(
                "no lyrics",
                "play something, or wait for the stream to name it",
            )
            LyricsViewModel.Status.NotFound -> CenterLyricNote(
                "no lyrics found",
                queryLabel(ui.query),
                actionLabel = "retry",
                onAction = vm::retry,
            )
            LyricsViewModel.Status.Offline -> CenterLyricNote(
                "couldn't reach lyrics",
                "check the connection and retry",
                actionLabel = "retry",
                onAction = vm::retry,
            )
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
private fun CenterLyricNote(
    text: String,
    sub: String = "",
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val p = LocalPalette.current
    Box(
        Modifier.fillMaxSize().padding(horizontal = Gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Mono(
                text,
                KleeampType.rowPrimary.copy(textAlign = TextAlign.Center),
                p.inkFaint,
                maxLines = 2,
            )
            if (sub.isNotBlank()) {
                Mono(
                    sub,
                    KleeampType.rowSecondary.copy(textAlign = TextAlign.Center),
                    p.inkFaint,
                    maxLines = 2,
                )
            }
            if (actionLabel != null) OffsetKey(actionLabel, onClick = onAction)
        }
    }
}

/** "Singer – Song" for the lookup states; blank when there is nothing. */
private fun queryLabel(query: LyricsQuery?): String {
    if (query == null) return ""
    return listOf(query.artist, query.title)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" – ")
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
