package stream.kleeamp.mobile.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * "Who's listening": the cliamp.stream stats section rebuilt for the app.
 * Real listener data drives the header totals, the globe markers and the
 * ranked leaderboard; with no live listeners the ranking falls back to
 * all-time sessions exactly like the website. There is no keyboard section
 * here - this feature is glanceable counts plus an explorable globe.
 */
@Composable
fun ListenerAnalytics(
    stats: CliampStats?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /** True once the user has spun the globe: the hint stays hidden. */
    hintSeen: Boolean = false,
    /** Fired once on the first real spin, to persist the hint dismissal. */
    onFirstSpin: () -> Unit = {},
    /** Fired on every country-list tap so the host can scroll the globe into view. */
    onPickCountry: () -> Unit = {},
) {
    val geometry = rememberWorldGeometry()
    var selectedCode by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = stats?.countries.orEmpty()
    val selected = rows.firstOrNull { it.code == selectedCode }
    if (selectedCode != null && selected == null) selectedCode = null
    // Fly-to target for the globe: set on leaderboard taps so the camera
    // finds the country. The nonce retriggers flights to the same country.
    // Plain remember: a flight is transient, nothing to restore.
    var flyTo by remember { mutableStateOf<FlyTo?>(null) }
    fun select(code: String?, fly: Boolean) {
        selectedCode = code
        if (fly && code != null) {
            flyTo = FlyTo(code, (flyTo?.nonce ?: 0L) + 1L)
        }
    }

    // The website polls while visible; a slow first fetch (or offline start)
    // lands whenever it lands and the status flips loading -> live itself.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000)
            onRefresh()
        }
    }

    Column(modifier.fillMaxWidth()) {
        SectionLabel("who's listening — live", gutter = 8.dp)
        // Header totals, like the website's LISTENERS / ON PLAYLISTS / COUNTRIES.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            StatBlock("listeners", stats?.activeNow?.let { "%,d".format(it) } ?: "–", true)
            StatBlock("on playlists", stats?.onPlaylists?.let { "%,d".format(it) } ?: "–", false)
            StatBlock("countries", rows.size.takeIf { stats?.isLive == true }?.let { "%,d".format(it) } ?: "–", false)
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlobeCard(
                        stats = stats,
                        geometry = geometry,
                        selectedCode = selectedCode,
                        onSelect = { select(it, fly = false) },
                        flyTo = flyTo,
                        showHint = !hintSeen,
                        onFirstSpin = onFirstSpin,
                        modifier = Modifier.weight(1f),
                    )
                    CountrySide(
                        rows = rows,
                        loading = stats == null,
                        selectedCode = selectedCode,
                        onSelect = {
                            select(it, fly = it != null)
                            onPickCountry()
                        },
                        modifier = Modifier.weight(0.55f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlobeCard(
                        stats = stats,
                        geometry = geometry,
                        selectedCode = selectedCode,
                        onSelect = { select(it, fly = false) },
                        flyTo = flyTo,
                        showHint = !hintSeen,
                        onFirstSpin = onFirstSpin,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CountrySide(
                        rows = rows,
                        loading = stats == null,
                        selectedCode = selectedCode,
                        onSelect = {
                            select(it, fly = it != null)
                            onPickCountry()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // Breathing room before the next section (cliamp radio): the list
        // ends flush, so without this the rows stick to its header.
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatBlock(label: String, value: String, primary: Boolean) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Mono(value, KleeampType.trackTitleSmall, if (primary) p.accent else p.ink)
        Mono(label.uppercase(), KleeampType.meta, p.inkSecondary)
    }
}

/** Status pill: loading while no data has arrived, live/all-time after. */
@Composable
private fun LiveIndicator(stats: CliampStats?) {
    val p = LocalPalette.current
    when {
        stats == null -> Mono("loading..", KleeampType.meta, p.inkFaint)
        stats.isLive -> Mono("● live", KleeampType.meta, p.accent)
        else -> Mono("○ all-time", KleeampType.meta, p.inkFaint)
    }
}

@Composable
private fun GlobeCard(
    stats: CliampStats?,
    geometry: List<CountryGeometry>?,
    selectedCode: String?,
    onSelect: (String?) -> Unit,
    flyTo: FlyTo?,
    showHint: Boolean,
    onFirstSpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(p.panel)
            .border(1.dp, p.hairline, RoundedCornerShape(KleeampShape.small)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("$ cliamp radio --stats --globe", KleeampType.meta, p.inkSecondary)
            LiveIndicator(stats)
        }
        Box(
            Modifier.fillMaxWidth().height(340.dp)
                .background(p.ground, RoundedCornerShape(KleeampShape.small)),
            contentAlignment = Alignment.Center,
        ) {
            // The globe always renders: geography alone while loading,
            // markers and counts layered on once data arrives.
            if (geometry == null) {
                Mono("loading world map…", KleeampType.meta, p.inkFaint)
            } else {
                ListenerGlobe(
                    rows = stats?.countries.orEmpty(),
                    geometry = geometry,
                    selectedCode = selectedCode,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    flyTo = flyTo,
                    onFirstSpin = onFirstSpin,
                )
            }
            // Hold-to-spin hint, centered on the globe until the first real
            // spin. Plain text never consumes touches, so hold-to-drag and
            // taps pass straight through to the globe underneath.
            if (showHint && geometry != null) {
                Mono(
                    "hold and drag to spin · tap a country for its count",
                    KleeampType.meta,
                    p.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(KleeampShape.small))
                        .background(p.panel.copy(alpha = 0.88f))
                        .border(1.dp, p.hairline, RoundedCornerShape(KleeampShape.small))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Ranked country list, always visible like cliamp.stream: name over an
 * accent progress bar, count right-aligned. Tapping a row flies the globe
 * to that country (touch has no hover tooltip); the selected name wears the
 * accent so the detail below reads as linked.
 */
@Composable
private fun CountrySide(
    rows: List<CountryListeners>,
    loading: Boolean,
    selectedCode: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    // Shut by default so the globe and station list stay in reach; the
    // header taps open the ranking.
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(KleeampShape.tiny))
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (open) "hide top countries" else "show top countries",
                    onClick = { open = !open },
                )
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(
                "TOP COUNTRIES",
                KleeampType.meta,
                p.inkSecondary,
            )
            // Disclosure caret: right while shut, down once open. The row's
            // onClickLabel already names the action, so the glyph is decor.
            Icon(
                if (open) KleeampIcons.CaretDown else KleeampIcons.CaretRight,
                null,
                Modifier.size(10.dp),
                tint = p.inkFaint,
            )
        }
        if (open) {
            if (rows.isEmpty()) {
                Mono(
                    if (loading) "loading.." else "nobody is tuned in right now",
                    KleeampType.rowSecondary,
                    p.inkFaint,
                )
            } else {
            val top = rows.take(10)
            val max = top.firstOrNull()?.listeners?.coerceAtLeast(1) ?: 1
            top.forEach { c ->
                val selected = selectedCode == c.code
                // Name above; the bar and the count share one row centered
                // together, so the number rides the bar's line whatever the
                // font metrics are — no optical offset to tune.
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(KleeampShape.tiny))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (selected) "deselect ${c.name}" else "show ${c.name}",
                            onClick = { onSelect(if (selected) null else c.code) },
                        )
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Mono(
                        c.name,
                        KleeampType.rowSecondary,
                        if (selected) p.accent else p.ink,
                        maxLines = 1,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.weight(1f).height(4.dp)
                                .background(p.hairline, RoundedCornerShape(2.dp)),
                        ) {
                            val width = (c.listeners.toFloat() / max).coerceIn(0.02f, 1f)
                            Box(
                                Modifier.fillMaxWidth(width).fillMaxHeight()
                                    .background(p.accent, RoundedCornerShape(2.dp)),
                            )
                        }
                        Box(
                            Modifier.widthIn(min = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Mono(
                                "%,d".format(c.listeners),
                                KleeampType.rowSecondary,
                                p.inkSecondary,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}
