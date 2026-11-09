package stream.kleeamp.mobile.lyrics

import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * One synced lyric line. A blank [text] is a stanza gap, not a line: LRC
 * writers stamp the verse break so the reader sees paragraphs instead of one
 * solid wall, and the UI renders those as a spacer.
 */
data class LrcLine(val timeMs: Long, val text: String)

/** Normalized lookup: artist/title plus duration for precision. */
data class LyricsQuery(
    val artist: String,
    val title: String,
    val album: String = "",
    val durationSec: Long = 0,
) {
    /** Stable cache identity. */
    val cacheKey: String get() = "$artist\n$title\n$album\n$durationSec"
}

private val stamp = Regex("""\[(\d{1,3}):(\d{2}(?:\.\d{1,3})?)\]""")

/**
 * Parses synced LRC into time-ordered lines. A line may carry several stamps
 * (all win); metadata tags (`[ar:…]`) carry none and fall away. Stamped
 * blank lines are kept: they are the stanza breaks.
 */
fun parseLrc(synced: String): List<LrcLine> = synced.lineSequence().flatMap { line ->
    val times = stamp.findAll(line).toList()
    if (times.isEmpty()) return@flatMap emptyList()
    val text = line.substringAfterLast(']').trim()
    times.mapNotNull { m ->
        val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val sec = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        LrcLine(((min * 60 + sec) * 1000).toLong(), text)
    }
}.sortedBy { it.timeMs }.toList()

/**
 * The line being sung at [cursor]: the last one at or before it. Stanza gaps
 * are skipped backwards, so the previous line stays lit through an
 * instrumental break instead of nothing being lit.
 */
fun activeLineIndex(lines: List<LrcLine>, cursor: Long): Int {
    if (lines.isEmpty()) return -1
    var index = lines.indexOfLast { it.timeMs <= cursor }
    while (index >= 0 && lines[index].text.isBlank()) index--
    return index
}

/**
 * Lookup for what is audible: local/provider/podcast items name their
 * artist, radio names the stream title (`Artist - Title`) when it has one.
 * Null when there is nothing to search with.
 */
fun lyricsQuery(station: Station?, streamTitle: String): LyricsQuery? {
    if (station == null) return null
    val (artist, title, album) = when (station.source) {
        StationSource.Local, StationSource.Provider, StationSource.Podcast ->
            Triple(station.artist, station.name, station.album)
        else -> {
            if (streamTitle.isBlank()) return null
            val (a, t) = splitTitle(streamTitle)
            Triple(a, t, "")
        }
    }
    if (title.isBlank()) return null
    return LyricsQuery(
        artist = artist,
        title = title,
        album = album,
        durationSec = station.durationMs / 1000,
    )
}

private fun splitTitle(title: String): Pair<String, String> {
    val i = title.indexOf(" - ")
    return if (i < 0) "" to title.trim()
    else title.substring(0, i).trim() to title.substring(i + 3).trim()
}

/**
 * Strips the noise real titles wear before a lookup, mirroring cliamp's
 * `cleanQuery`: bracket and paren asides, trailing `- Official Video` style
 * labels, featured credits and remaster phrases.
 *
 * Conservative by design - a trailing "video"/"audio" only counts as a label
 * when it is the last word, so "Video Games" and "Videotape" survive intact.
 * The result is only ever a search string; what plays is never rewritten.
 */
fun cleanLookup(raw: String): String = raw
    .replace(bracketed, " ")
    .replace(trailingLabel, "")
    .replace(trailingCredit, "")
    .replace(trailingPhrase, "")
    .replace(whitespace, " ")
    .trim()
    .trim('-', '–', '—', '·', ',', '.')
    .trim()

/** `[Official Video]`, `(Lyric Video)`, `(feat. X)` at any position. */
private val bracketed = Regex("""\[[^\]]*]|\([^)]*\)""")

/** `Song - Official Video`, `Song - Remastered 2011`, `Song - Live at X`. */
private val trailingLabel =
    Regex("""(?i)\s+[-–—]\s+(?:official|lyric|audio|video|visuali[sz]er|remaster|live|radio|album|mono|stereo).*$""")

/** A bare trailing credit: `Song feat. X`, `Song ft. X`. */
private val trailingCredit = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$""")

/** A bare trailing label word: `Song Official Video`, `Song Remastered`. */
private val trailingPhrase = Regex(
    """(?i)\s+(?:official(?:\s+music)?\s+)?""" +
        """(?:video|audio|lyrics?\s+video|visuali[sz]er""" +
        """|remaster(?:ed)?(?:\s+\d{4})?|radio\s+edit|album\s+version|mono|stereo)\s*$""",
)

private val whitespace = Regex("""\s+""")
