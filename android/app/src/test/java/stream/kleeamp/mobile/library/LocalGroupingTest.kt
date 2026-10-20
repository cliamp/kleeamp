package stream.kleeamp.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

class LocalGroupingTest {

    private fun song(
        name: String,
        artist: String = "artist",
        album: String = "album",
        albumArtist: String = "",
        track: Int = 0,
        disc: Int = 0,
    ) = Station(
        id = "local:$name",
        name = name,
        url = "file:///x/$name",
        source = StationSource.Local,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        trackNo = track,
        discNo = disc,
    )

    @Test fun decodePackedTrackSplitsDisc() {
        assertEquals(1 to 3, decodeDiscTrack(1003, 0))
    }

    @Test fun decodeRealDiscWins() {
        assertEquals(2 to 5, decodeDiscTrack(5, 2))
    }

    @Test fun decodeMissingStaysZero() {
        assertEquals(0 to 0, decodeDiscTrack(0, 0))
    }

    @Test fun libraryProjectionFallsBackToGuaranteedColumns() {
        val full = libraryProjection(full = true).toSet()
        val base = libraryProjection(full = false).toSet()
        assertTrue(full.contains("disc_number"))
        assertTrue(full.containsAll(base))
        assertFalse(base.contains("disc_number"))
    }

    @Test fun albumSortUsesDiscThenTrack() {
        val songs = listOf(
            song("b-title", track = 2, disc = 1),
            song("a-title", track = 10, disc = 1),
            song("disc2-track1", track = 1, disc = 2),
        )
        val ordered = sortedStations(songs, PlaylistSort.Album).map { it.name }
        assertEquals(listOf("b-title", "a-title", "disc2-track1"), ordered)
    }

    @Test fun compilationStaysOneAlbumViaAlbumArtist() {
        val songs = listOf(
            song("t1", artist = "Daft Punk", album = "Alive 2007", albumArtist = "Daft Punk", track = 2),
            song("t2", artist = "Daft Punk", album = "Alive 2007", albumArtist = "Daft Punk", track = 1),
            song("t3", artist = "Gabrielle", album = "Alive 2007", albumArtist = "Daft Punk", track = 3),
        )
        val groups = albumsOf(songs)
        assertEquals(1, groups.size)
        assertEquals("Daft Punk", groups.single().displayArtist)
        assertEquals(listOf("t2", "t1", "t3"), groups.single().songs.map { it.name })
    }

    @Test fun sameNameSplitsByAlbumArtist() {
        val songs = listOf(
            song("a1", artist = "x", album = "Hits", albumArtist = "A"),
            song("b1", artist = "y", album = "Hits", albumArtist = "B"),
        )
        assertEquals(2, albumsOf(songs).size)
    }

    @Test fun untaggedFallsBackToAlbumName() {
        val songs = listOf(
            song("t1", artist = "a1", album = "Mix"),
            song("t2", artist = "a2", album = "Mix"),
        )
        val groups = albumsOf(songs)
        assertEquals(1, groups.size)
        assertEquals("Various Artists", groups.single().displayArtist)
    }

    @Test fun untaggedLoneArtistShowsThatArtist() {
        val songs = listOf(song("t1", artist = "Rush", album = "2112"))
        assertEquals("Rush", albumsOf(songs).single().displayArtist)
    }

    @Test fun artistsGroupAlphabetically() {
        val songs = listOf(song("t1", artist = "zebra"), song("t2", artist = "apple"))
        assertEquals(listOf("apple", "zebra"), artistsOf(songs).map { it.name })
    }

    @Test fun browseArtistNamePrefersAlbumArtist() {
        val tagged = song("t1", artist = "Alan Walker, Ava Max", albumArtist = "Alan Walker")
        assertEquals("Alan Walker", tagged.browseArtistName())
        assertEquals("Ava Max", song("t2", artist = "Ava Max", albumArtist = " ").browseArtistName())
        assertEquals("unknown artist", song("t3", artist = "", albumArtist = "").browseArtistName())
    }

    @Test fun browseArtistNameSplitsFeaturedCreditsWithoutAlbumArtist() {
        assertEquals(
            "Alan Walker",
            song("t1", artist = "Alan Walker, Ava Max").browseArtistName(),
        )
        assertEquals(
            "Alan Walker",
            song("t2", artist = "Alan Walker , K-391 , Emelie Hollow").browseArtistName(),
        )
        assertEquals(
            "Amir Tataloo",
            song("t3", artist = "Amir Tataloo Ft Reza Pishro & Ho3ein").browseArtistName(),
        )
    }

    @Test fun browseArtistNameKeepsBandNamesWhole() {
        assertEquals("Earth, Wind & Fire", song("t1", artist = "Earth, Wind & Fire").browseArtistName())
        assertEquals("Simon & Garfunkel", song("t2", artist = "Simon & Garfunkel").browseArtistName())
    }

    @Test fun artistsCollapseFeaturedCreditsWithoutAlbumArtist() {
        val songs = listOf(
            song("t1", artist = "Alan Walker"),
            song("t2", artist = "Alan Walker , Ava Max"),
            song("t3", artist = "Alan Walker , K-391 , Emelie Hollow"),
        )
        val groups = artistsOf(songs)
        assertEquals(listOf("Alan Walker"), groups.map { it.name })
        assertEquals(3, groups.single().songs.size)
    }

    @Test fun artistsGroupByAlbumArtistNotContributingArtists() {
        val songs = listOf(
            song("t1", artist = "Alan Walker", album = "Different World", albumArtist = "Alan Walker"),
            song("t2", artist = "Alan Walker, Ava Max", album = "Different World", albumArtist = "Alan Walker"),
            song("t3", artist = "Alan Walker, K-391, Emelie Hollow", album = "Different World", albumArtist = "Alan Walker"),
        )
        val groups = artistsOf(songs)
        assertEquals(listOf("Alan Walker"), groups.map { it.name })
        assertEquals(listOf("t1", "t2", "t3"), groups.single().songs.map { it.name })
    }

    @Test fun artistsFallBackToTrackArtistWhenAlbumArtistBlank() {
        val songs = listOf(
            song("t1", artist = "Alan Walker", albumArtist = ""),
            song("t2", artist = "Ava Max", albumArtist = "   "),
        )
        assertEquals(listOf("Alan Walker", "Ava Max"), artistsOf(songs).map { it.name })
    }

    @Test fun artistsMergeAlbumArtistCaseVariants() {
        val songs = listOf(
            song("t1", artist = "x", albumArtist = "alan walker"),
            song("t2", artist = "y", albumArtist = "Alan Walker"),
        )
        val groups = artistsOf(songs)
        assertEquals(1, groups.size)
        assertEquals("alan walker", groups.single().name)
        assertEquals(2, groups.single().songs.size)
    }

    @Test fun albumArtistOnlyGetsOwnRow() {
        val songs = listOf(
            song("t1", artist = "", album = "Mix", albumArtist = "DJ Mix"),
        )
        val groups = artistsOf(songs)
        assertEquals(listOf("DJ Mix"), groups.map { it.name })
    }

    @Test fun artistCoverIsFirstTrackInAlbumOrder() {
        val songs = listOf(
            song("later-title", album = "B", track = 1),
            song("second-track", album = "A", track = 2),
            song("first-track", album = "A", track = 1),
        )
        val group = artistsOf(songs).single()
        assertEquals("first-track", group.coverSong?.name)
        assertEquals(
            "first-track",
            artistHeaderSongs(group.songs, group.coverSong?.url.orEmpty()).first().name,
        )
    }

    @Test fun artistHeaderLeadsWithRowCoverSong() {
        val songs = listOf(song("a"), song("b"), song("c"))
        val header = artistHeaderSongs(songs, songs[1].url)
        assertEquals(listOf("b", "a", "c"), header.map { it.name })
    }

    @Test fun artistHeaderKeepsOrderWithoutCoverSong() {
        val songs = listOf(song("a"), song("b"))
        assertEquals(listOf("a", "b"), artistHeaderSongs(songs, "").map { it.name })
        assertEquals(listOf("a", "b"), artistHeaderSongs(songs, "local:missing").map { it.name })
    }

    @Test fun albumMatchScopesToArtistWhenGiven() {
        val alan = song("t1", artist = "Alan Walker", album = "Music", albumArtist = "")
        val sia = song("t2", artist = "Sia", album = "Music", albumArtist = "")
        assertTrue(alan.matchesAlbum("Music", "", "Alan Walker"))
        assertFalse(sia.matchesAlbum("Music", "", "Alan Walker"))
        assertTrue(alan.matchesAlbum("Music", ""))
        assertTrue(sia.matchesAlbum("Music", ""))
    }

    @Test fun artistsMergeCaseVariants() {
        val songs = listOf(
            song("t1", artist = "daft punk"),
            song("t2", artist = "Daft Punk"),
        )
        val groups = artistsOf(songs)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().songs.size)
    }

    @Test fun albumsMergeCaseVariants() {
        val songs = listOf(
            song("t1", album = "hits", albumArtist = "a"),
            song("t2", album = "Hits", albumArtist = "A"),
        )
        val groups = albumsOf(songs)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().songs.size)
    }

    @Test fun albumsDedupeRepeatedIds() {
        val dup = song("t1", album = "Hits", albumArtist = "A")
        val groups = albumsOf(listOf(dup, dup))
        assertEquals(1, groups.single().songs.size)
    }
}
