package stream.kleeamp.mobile.lyrics

import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/** One synced lyric line. */
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
 * Parses synced LRC into time-ordered lines. A line may carry several
 * stamps (all win); metadata tags (`[ar:…]`) carry none and fall away, as
 * do stamped-but-empty lines.
 */
fun parseLrc(synced: String): List<LrcLine> = synced.lineSequence().flatMap { line ->
    val times = stamp.findAll(line).toList()
    if (times.isEmpty()) return@flatMap emptyList()
    val text = line.substringAfterLast(']').trim()
    if (text.isEmpty()) return@flatMap emptyList()
    times.mapNotNull { m ->
        val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val sec = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        LrcLine(((min * 60 + sec) * 1000).toLong(), text)
    }
}.sortedBy { it.timeMs }.toList()

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
    return LyricsQuery(artist, title, album, station.durationMs / 1000)
}

private fun splitTitle(title: String): Pair<String, String> {
    val i = title.indexOf(" - ")
    return if (i < 0) "" to title.trim()
    else title.substring(0, i).trim() to title.substring(i + 3).trim()
}
