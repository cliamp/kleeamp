package stream.kleeamp.mobile.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import stream.kleeamp.mobile.R

/** Editorial face: titles, artists, albums, playlists, buttons, tabs, settings. */
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

/**
 * Numeric face for the readouts that tick: elapsed/remaining clocks, buffered
 * seconds, bitrate, sample rate. JetBrains Mono uses tabular figures by
 * design, so a changing seconds column never shifts the minutes next to it.
 */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun mono(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
    lineHeight: Double = 1.35,
    family: FontFamily = Poppins,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = tracking.em,
    lineHeight = (size * lineHeight).sp,
    lineHeightStyle = trim,
)

/** Sizes lifted verbatim from the concept's type scale. */
@Immutable
object KleeampType {
    // Titles: semibold-to-bold sans. Artists, albums and playlists sit a step
    // down in regular, with medium for emphasis rows.
    val screenTitle = mono(24, FontWeight.Bold, -0.02, 1.15)
    val trackTitle = mono(28, FontWeight.Bold, -0.015, 1.14)
    val trackTitleCompact = mono(19, FontWeight.Bold, -0.012, 1.2)
    val trackTitleSmall = mono(16, FontWeight.Bold, -0.01, 1.2)
    val rowPrimary = mono(15, FontWeight.Normal, 0.0, 1.3)
    val rowPrimaryMedium = mono(15, FontWeight.Medium, 0.0, 1.3)
    val rowSecondary = mono(12, FontWeight.Normal, 0.0, 1.35)
    val meta = mono(11, FontWeight.Normal, 0.0, 1.35)
    val body = mono(13, FontWeight.Normal, 0.0, 1.6)

    // Lyrics are the content of their page, not a row in a list: a step above
    // body, with room to breathe. Both weights stay the same size so turning
    // the active line never re-measures the list.
    val lyricLine = mono(16, FontWeight.Normal, 0.0, 1.45)
    val lyricActive = mono(16, FontWeight.Medium, 0.0, 1.45)

    // UI chrome: buttons, tabs and settings rows sit at medium so they read a
    // touch heavier than the data lines around them.
    val sectionLabel = mono(11, FontWeight.Medium, 0.14, 1.3)
    val chip = mono(11, FontWeight.Medium, 0.08, 1.2)
    val tabLabel = mono(10, FontWeight.Medium, 0.10, 1.2)
    val nowPlayingLabel = mono(11, FontWeight.Medium, 0.16, 1.2)

    // Numeric readouts in the tabular mono face. `time` is the big elapsed and
    // remaining clocks; `timeSmall` the buffered seconds and podcast remaining
    // badge; `datum` the technical figures (bitrate, sample rate).
    val time = mono(13, FontWeight.Medium, 0.0, 1.1, JetBrainsMono)
    val timeSmall = mono(11, FontWeight.Normal, 0.0, 1.1, JetBrainsMono)
    val datum = mono(11, FontWeight.Normal, 0.0, 1.35, JetBrainsMono)
}
