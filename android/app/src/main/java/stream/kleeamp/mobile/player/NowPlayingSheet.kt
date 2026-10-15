package stream.kleeamp.mobile.player

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi

/**
 * The expanded player as a bottom sheet: a full-height sheet over the
 * list, which stays composed behind and shows through instead of an empty
 * page; back dismisses through [onDismiss]. Opening Up Next
 * or Scope on top hides the sheet until back returns.
 */
@UnstableApi
@Composable
fun NowPlayingSheet(
    vm: NowPlayingViewModel,
    onOpenScope: () -> Unit,
    onOpenUpNext: () -> Unit,
    onOpenLyrics: () -> Unit,
    onDismiss: () -> Unit,
) {
    FullSheetShell(onDismiss = onDismiss, inlineHandle = true) {
        NowPlayingScreen(
            vm = vm,
            onOpenScope = onOpenScope,
            onOpenUpNext = onOpenUpNext,
            onOpenLyrics = onOpenLyrics,
            onBack = onDismiss,
        )
    }
}
