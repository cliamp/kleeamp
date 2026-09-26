package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.player.vis.MeterCore
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.model.NowPlaying

/**
 * The signature element. Each column is three layers, all anchored to the
 * bottom so brick phase never shifts as the level animates:
 *
 *  1. unlit grid, full height
 *  2. lit grid, height = level
 *  3. peak cap, one brick tall, floating above and lagging behind
 *
 * Bricks are laid out from the bottom edge upward, which is what keeps the
 * grid phase stable - drawing top-down would make the whole stack shimmer.
 */
@Composable
fun BrickMeter(
    frame: MeterFrame,
    modifier: Modifier = Modifier,
    brick: Dp = 4.dp,
    gap: Dp = 3.dp,
    columnGap: Dp = 3.dp,
    litColor: Color = LocalPalette.current.accent,
    unlitColor: Color = LocalPalette.current.unlit,
    peakColor: Color = LocalPalette.current.peak,
    showPeaks: Boolean = true,
) {
    Canvas(modifier) {
        // reading the counter inside draw is what re-runs this on each frame
        @Suppress("UNUSED_EXPRESSION") frame.frame

        val n = frame.columns
        if (n == 0) return@Canvas
        val brickPx = brick.toPx()
        val gapPx = gap.toPx()
        val colGapPx = columnGap.toPx()
        val step = brickPx + gapPx
        val colW = (size.width - colGapPx * (n - 1)) / n
        if (colW <= 0f) return@Canvas
        val rows = ((size.height + gapPx) / step).toInt().coerceAtLeast(1)

        for (c in 0 until n) {
            val x = c * (colW + colGapPx)
            val level = frame.levels[c].coerceIn(0f, 1f)
            val litRows = (level * rows).toInt()
            for (r in 0 until rows) {
                val y = size.height - (r + 1) * step + gapPx
                drawRect(
                    color = if (r < litRows) litColor else unlitColor,
                    topLeft = Offset(x, y),
                    size = Size(colW, brickPx),
                )
            }
            if (showPeaks) {
                val pkRow = (frame.peaks[c].coerceIn(0f, 1f) * rows).toInt().coerceIn(0, rows - 1)
                drawRect(
                    color = peakColor,
                    topLeft = Offset(x, size.height - (pkRow + 1) * step + gapPx),
                    size = Size(colW, brickPx),
                )
            }
        }
    }
}

/** Column count/geometry presets, straight from the concept. */
enum class MeterSize(val columns: Int, val brick: Dp, val gap: Dp, val height: Dp) {
    NowPlaying(Visualizer.Brick.columns, 4.dp, 3.dp, 98.dp),
    Scope(32, 6.dp, 4.dp, 200.dp),
    Mini(14, 3.dp, 2.dp, 22.dp),
}

/**
 * One meter state source, so the call site never branches between two
 * different `remember` trees - conditional remembers would drop and rebuild
 * the frame loop every time playback started or stopped.
 *
 * When a real spectrum is available it is used directly; otherwise the columns
 * fall back to a synthesised idle animation. Per-column variety comes from
 * staggered period and phase, never from randomised colour.
 *
 * [spectrumProvider] lets small always-composed meters (mini player, Up Next)
 * read the live analyser without subscribing composition to it: a lambda read
 * inside the frame loop costs no recomposition, while a collected State would
 * re-emit the whole caller sixty times a second.
 */
@Composable
fun rememberMeter(
    columns: Int,
    live: Boolean,
    spectrum: State<FloatArray>? = null,
    spectrumProvider: (() -> FloatArray?)? = null,
): MeterFrame {
    val frame = remember(columns) { MeterFrame(columns) }
    val src = spectrumProvider?.invoke() ?: spectrum?.value
    val useReal = live && src != null && src.isNotEmpty()

    LaunchedEffect(columns, live, useReal) {
        if (!live) {
            frame.settle()
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        var last = start
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                last = now
                val real = spectrumProvider?.invoke() ?: spectrum?.value
                if (real != null && real.isNotEmpty()) {
                    frame.push(real, dt)
                } else {
                    val t = (now - start) / 1_000_000_000.0
                    frame.pushIdle(t)
                }
            }
        }
    }
    return frame
}

/**
 * Holds the lit levels and the lagging peak caps, delegating the actual
 * attack/release smoothing to the shared [MeterCore] so the in-app meter and
 * the widget always derive identical values from the same spectrum. Both
 * arrays are mutated in place and read inside a Canvas draw, so the frame
 * counter is what drives recomposition rather than the arrays themselves.
 */
@Stable
class MeterFrame(val columns: Int) {
    private val core = MeterCore(columns)
    val levels get() = core.levels
    val peaks get() = core.peaks
    var frame by mutableIntStateOf(0)
        private set

    fun push(source: FloatArray, dt: Float) {
        core.push(source, dt)
        frame++
    }

    fun pushIdle(t: Double) {
        core.pushIdle(t)
        frame++
    }

    fun settle() {
        core.settle()
        frame++
    }
}
