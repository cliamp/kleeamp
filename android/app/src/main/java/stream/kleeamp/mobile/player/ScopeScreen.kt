package stream.kleeamp.mobile.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.player.vis.StereoMetrics
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.playback.EqPresets
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.KleeampToggle
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.MechSliderVertical
import stream.kleeamp.mobile.chrome.MeterSize
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.player.vis.VisualizerMeter
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

private val bandLabels = listOf("60", "150", "400", "1k", "3k", "8k", "16k")
private val rulerLabels = listOf("32", "125", "500", "2k", "8k", "20k")

@OptIn(FlowPreview::class)
@Composable
fun ScopeScreen(
    prefs: Prefs,
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()

    // The peak readout below only needs a slow sample: collecting the raw
    // analyser flow would recompose this whole screen on every FFT callback
    // and starve the meter loop into stutter. The meter itself reads the bus
    // directly through provider lambdas (zero recomposition, zero lag).
    // The sample operator is hoisted out of composition so a recomposition
    // never rebuilds the sampling pipeline.
    val sampledSpectrum = remember { PlaybackBus.spectrum.sample(500) }
    val peakBands by sampledSpectrum.collectAsState(initial = FloatArray(0))
    val spectrumProvider: () -> FloatArray? = { PlaybackBus.spectrum.value }
    val stereoProvider: () -> StereoMetrics? = { PlaybackBus.stereo.value }
    val visualizer by prefs.visualizer.collectAsState(initial = "spectrum")
    val mode = Visualizer.byId(visualizer)
    val spectrumLive by PlaybackBus.spectrumLive.collectAsState()
    val eqEnabled by prefs.eqEnabled.collectAsState(initial = false)
    val eqBands by prefs.eqBands.collectAsState(initial = List(7) { 0f })
    val eqPreset by prefs.eqPreset.collectAsState(initial = "flat")

    Column(
        Modifier
            .fillMaxSize()
            .background(p.groundScope)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(onBack)
            Mono(
                when {
                    visualizer == "off" -> "VISUALIZER OFF"
                    spectrumLive -> "${mode.label.uppercase()} · LIVE"
                    playing -> "${mode.label.uppercase()} · SIMULATED"
                    else -> "${mode.label.uppercase()} · IDLE"
                },
                KleeampType.sectionLabel,
                if (spectrumLive) p.accent else p.inkTertiary,
            )
        }

        // Explicit visualizer switch: every family, then off, on the same
        // setting the player and mini player read. Sits above the meter it
        // controls.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("Visualizer", KleeampType.rowPrimaryMedium, p.ink)
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Visualizer.selectable.forEach { m ->
                    Chip(
                        m.label,
                        visualizer == m.id,
                        onClick = { scope.launch { prefs.setVisualizer(m.id) } },
                    )
                }
                Chip(
                    "off",
                    visualizer == "off",
                    onClick = { scope.launch { prefs.setVisualizer("off") } },
                )
            }
        }

        // The meter is the visualizer: when the setting is off it is removed
        // entirely (no frame loop, no grid, no peak readout), leaving just the
        // equalizer on this screen.
        if (visualizer != "off") {
            // Peak level in dBFS, read off the slow sample: a twice-a-second
            // number needs no FFT-rate recomposition.
            val peakDb = peakBands.maxOrNull()?.let { -48f + it * 48f } ?: -48f

            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
                // The peak datum breathes with the meter while the spectrum is
                // live, quieting to a solid value the moment it is not.
                val peakTransition = rememberInfiniteTransition(label = "peak")
                val peakAlpha by peakTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "peakAlpha",
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Mono("PEAK", KleeampType.sectionLabel, p.inkFaint)
                    Mono(
                        "%.0f dB".format(peakDb.coerceIn(-48f, 0f)),
                        KleeampType.datum,
                        p.accent.copy(alpha = if (spectrumLive) peakAlpha else 1f),
                    )
                }
                VisualizerMeter(
                    mode = mode,
                    columns = MeterSize.Scope.columns,
                    live = playing,
                    spectrumProvider = spectrumProvider,
                    stereoProvider = stereoProvider,
                    brick = MeterSize.Scope.brick,
                    gap = MeterSize.Scope.gap,
                    modifier = Modifier.fillMaxWidth().height(MeterSize.Scope.height),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                rulerLabels.forEach { Mono(it, KleeampType.meta, p.inkFaint) }
            }
        }

        HairlineDivider(region = true)

        Column(
            Modifier.padding(horizontal = Gutter, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Mono(station?.name ?: "nothing tuned", KleeampType.trackTitleSmall, p.ink, maxLines = 1)
            Mono(
                streamTitle.ifBlank { station?.meta ?: "" },
                KleeampType.rowSecondary,
                p.inkTertiary,
                maxLines = 1,
            )
        }

        HairlineDivider(region = true)

        SectionLabel("equalizer — 7 band") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono(eqPreset, KleeampType.meta, p.inkSecondary)
                KleeampToggle(eqEnabled, onChange = { scope.launch { prefs.setEqEnabled(it) } })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            bandLabels.forEachIndexed { i, label ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MechSliderVertical(
                        value = eqBands.getOrElse(i) { 0f },
                        onValueChange = { v ->
                            scope.launch {
                                val next = eqBands.toMutableList().also { it[i] = v }
                                prefs.setEqBands(next)
                                prefs.setEqPreset("custom")
                                if (!eqEnabled) prefs.setEqEnabled(true)
                            }
                        },
                        modifier = Modifier.height(150.dp),
                    )
                    Mono(label, KleeampType.meta, p.inkTertiary)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EqPresets.names.forEach { name ->
                Chip(name, eqPreset == name, onClick = {
                    scope.launch {
                        EqPresets.byName(name)?.let { prefs.setEqBands(it) }
                        prefs.setEqPreset(name)
                        prefs.setEqEnabled(true)
                    }
                })
            }
            Chip("reset", false, onClick = {
                scope.launch {
                    prefs.setEqBands(EqPresets.flat)
                    prefs.setEqPreset("flat")
                    prefs.setEqEnabled(false)
                }
            })
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp)) {
            Mono(
                "eq and spectrum attach to the decoder output. they need the record-audio permission, " +
                    "which android uses to gate the visualizer api even with no microphone involved. " +
                    "eq off is flat with the dsp left on: same sound, no pop from removing the effect",
                KleeampType.meta,
                p.inkFaint,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
