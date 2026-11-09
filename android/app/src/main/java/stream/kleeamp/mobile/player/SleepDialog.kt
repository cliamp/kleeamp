package stream.kleeamp.mobile.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.MenuSheetShell
import stream.kleeamp.mobile.chrome.SheetOptionRow
import stream.kleeamp.mobile.chrome.clock
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

private val sleepOptions: List<Int?> = listOf(null, 15, 30, 45, 60)

private fun sleepLabel(minutes: Int?): String = when (minutes) {
    null -> "off"
    else -> "$minutes min"
}

/**
 * Sleep timer sheet on the menu chrome: pauses playback when the countdown
 * ends. The timer lives in the player connection (checked on the 2 Hz sync
 * tick), so it fires with the screen off; it is deliberately not persisted.
 */
@Composable
internal fun SleepDialog(
    sleepAtMs: Long?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val armed = sleepAtMs != null
    MenuSheetShell(
        onDismiss = onDismiss,
        header = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        KleeampIcons.Watch,
                        null,
                        Modifier.size(30.dp),
                        tint = if (armed) p.accent else p.inkSecondary,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("Sleep Timer", KleeampType.trackTitleCompact, p.ink, maxLines = 1)
                    Mono(
                        if (armed) {
                            "pauses in " + clock((sleepAtMs - System.currentTimeMillis()).coerceAtLeast(0))
                        } else {
                            "pause playback after"
                        },
                        KleeampType.rowSecondary,
                        if (armed) p.accent else p.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
        },
    ) {
        val activeMinutes = sleepAtMs?.let { (it - System.currentTimeMillis()) / 60_000L }
        for (option in sleepOptions) {
            val selected = when (option) {
                null -> sleepAtMs == null
                else -> activeMinutes != null && activeMinutes in (option - 5)..(option + 1)
            }
            SheetOptionRow(
                label = sleepLabel(option),
                selected = selected,
                onClick = { onPick(option); onDismiss() },
            )
        }
        var customOpen by remember { mutableStateOf(false) }
        var customText by remember { mutableStateOf("") }
        if (!customOpen) {
            SheetOptionRow(
                label = "custom…",
                selected = false,
                onClick = { customOpen = true },
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KleeampTextField(
                    value = customText,
                    onValueChange = { customText = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.weight(1f),
                    placeholder = "minutes",
                    keyboardType = KeyboardType.Number,
                )
                Mono(
                    "SET",
                    KleeampType.chip,
                    p.accent,
                    Modifier
                        .microPress {
                            customText.toIntOrNull()?.takeIf { it in 1..999 }?.let {
                                onPick(it)
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    maxLines = 1,
                )
            }
        }
    }
}
