package stream.kleeamp.mobile.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class LrcTest {

    @Test
    fun parsesStampsAndSorts() {
        val lines = parseLrc("[00:12.00]second\n[00:01.50]first\n[ar:someone]\nplain\n")
        assertEquals(2, lines.size)
        assertEquals(LrcLine(1500, "first"), lines[0])
        assertEquals(LrcLine(12000, "second"), lines[1])
    }

    @Test
    fun oneLineWithManyStampsRepeats() {
        val lines = parseLrc("[00:01.00][00:03.00]echo")
        assertEquals(listOf(LrcLine(1000, "echo"), LrcLine(3000, "echo")), lines)
    }

    @Test
    fun stampedBlankLinesSurviveAsStanzaGaps() {
        // The verse break is stamped and empty: dropping it glued every stanza
        // into one block, so it is kept and drawn as a gap.
        val lines = parseLrc("[00:01.00]   \n[00:02.00]\n[00:03.00]verse")
        assertEquals(3, lines.size)
        assertTrue(lines[0].text.isBlank())
        assertEquals(LrcLine(3000, "verse"), lines[2])
    }

    @Test
    fun activeLineSkipsGapsButNotSilence() {
        val lines = listOf(
            LrcLine(1_000, "one"),
            LrcLine(5_000, ""),
            LrcLine(5_000, "two"),
            LrcLine(9_000, ""),
        )
        // Before the first line nothing is lit.
        assertEquals(-1, activeLineIndex(lines, 900))
        assertEquals(0, activeLineIndex(lines, 1_000))
        assertEquals(0, activeLineIndex(lines, 2_000))
        // Inside the gap between verse and chorus the previous line stays lit.
        assertEquals(0, activeLineIndex(lines, 4_000))
        // A trailing gap does not unlatch the last sung line.
        assertEquals(2, activeLineIndex(lines, 30_000))
        assertEquals(-1, activeLineIndex(emptyList(), 1_000))
    }

    @Test
    fun manyStampsKeepMillisecondPrecision() {
        val lines = parseLrc("[00:03.123]kept\n[01:02.5]later")
        assertEquals(listOf(3_123L, 62_500L), lines.map { it.timeMs })
    }

    @Test
    fun cleanLookupStripsLabelsWithoutEatingSongNames() {
        assertEquals("Song", cleanLookup("Song [Official Video]"))
        assertEquals("Song", cleanLookup("Song (Lyric Video)"))
        assertEquals("Song", cleanLookup("Song - Official Audio"))
        assertEquals("Song", cleanLookup("Song - Remastered 2011"))
        assertEquals("Song", cleanLookup("Song feat. Guest"))
        assertEquals("Song", cleanLookup("Song (feat. Guest)"))
        assertEquals("Artist - Song", cleanLookup("Artist - Song - Live at Wembley"))
        // A word that only looks like a label must survive.
        assertEquals("Video Games", cleanLookup("Video Games"))
        assertEquals("Videotape", cleanLookup("Videotape"))
        assertEquals("Audioslave", cleanLookup("Audioslave"))
    }

    @Test
    fun localUsesArtistAndTitle() {
        val q = lyricsQuery(station(StationSource.Local, "Song", "Singer"), "")
        assertEquals(LyricsQuery("Singer", "Song", "", 0), q)
    }

    @Test
    fun localCarriesAlbumAndDurationForTheLookup() {
        val local = Station(
            id = "id",
            name = "Dreams",
            url = "file:///music/dreams.mp3",
            source = StationSource.Local,
            artist = "Fleetwood Mac",
            album = "Rumours",
            durationMs = 257_000,
        )
        val q = lyricsQuery(local, "ignored stream title")
        assertEquals("Rumours", q?.album)
        assertEquals(257L, q?.durationSec)
        assertTrue(q?.cacheKey?.contains("Rumours") == true)
    }

    @Test
    fun radioSplitsStreamTitle() {
        val q = lyricsQuery(station(StationSource.Directory, "Station"), "Singer - Song")
        assertEquals(LyricsQuery("Singer", "Song", "", 0), q)
    }

    @Test
    fun radioWithoutTitleHasNoQuery() {
        assertNull(lyricsQuery(station(StationSource.Directory, "Station"), ""))
        assertNull(lyricsQuery(null, "Singer - Song"))
    }

    @Test
    fun plainRadioTitleBecomesTitleOnly() {
        val q = lyricsQuery(station(StationSource.Cliamp, "Station"), "Just words")
        assertEquals(LyricsQuery("", "Just words", "", 0), q)
    }

    private fun station(source: StationSource, name: String, artist: String = "") = Station(
        id = "id",
        name = name,
        url = "url",
        source = source,
        artist = artist,
    )
}
