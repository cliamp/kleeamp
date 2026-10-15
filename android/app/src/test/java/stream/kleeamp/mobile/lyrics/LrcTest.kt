package stream.kleeamp.mobile.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun stampedBlankLinesFallAway() {
        assertEquals(emptyList<LrcLine>(), parseLrc("[00:01.00]   \n[00:02.00]"))
    }

    private fun station(source: StationSource, name: String, artist: String = "") = Station(
        id = "id",
        name = name,
        url = "url",
        source = source,
        artist = artist,
    )

    @Test
    fun localUsesArtistAndTitle() {
        val q = lyricsQuery(station(StationSource.Local, "Song", "Singer"), "")
        assertEquals(LyricsQuery("Singer", "Song", "", 0), q)
    }

    @Test
    fun radioSplitsStreamTitle() {
        val q = lyricsQuery(station(StationSource.Directory, "Station"), "Singer - Song")
        assertEquals(LyricsQuery("Singer", "Song", "", 0), q)
    }

    @Test
    fun radioWithoutTitleHasNoQuery() {
        assertNull(lyricsQuery(station(StationSource.Directory, "Station"), ""))
    }

    @Test
    fun plainRadioTitleBecomesTitleOnly() {
        val q = lyricsQuery(station(StationSource.Cliamp, "Station"), "Just words")
        assertEquals(LyricsQuery("", "Just words", "", 0), q)
    }
}
