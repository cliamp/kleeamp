package stream.kleeamp.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTest {
    private fun station(source: StationSource, id: String) = Station(
        id = id,
        name = "n",
        url = "https://example.com/x",
        source = source,
    )

    @Test fun albumArtistAlbumPrefersAlbumArtist() {
        val song = station(StationSource.Local, "l1").copy(
            artist = "Daft Punk / Gabrielle",
            albumArtist = "Daft Punk",
            album = "Alive 2007",
        )
        assertEquals("Daft Punk · Alive 2007", song.albumArtistAlbum)
        assertEquals("Daft Punk / Gabrielle · Alive 2007", song.artistAlbum)
    }

    @Test fun albumArtistAlbumFallsBackToTrackArtist() {
        val song = station(StationSource.Local, "l1").copy(
            artist = "Track Artist",
            album = "Album",
        )
        assertEquals("Track Artist · Album", song.albumArtistAlbum)
        assertEquals("Track Artist · Album", song.copy(albumArtist = "   ").albumArtistAlbum)
    }

    @Test fun albumArtistAlbumOmitsBlankFieldsAndSeparators() {
        val song = station(StationSource.Local, "l1")
        assertEquals("", song.albumArtistAlbum)
        assertEquals("Album Artist", song.copy(albumArtist = "Album Artist").albumArtistAlbum)
        assertEquals("Track Artist", song.copy(artist = "Track Artist").albumArtistAlbum)
        assertEquals("Album", song.copy(album = "Album").albumArtistAlbum)
        assertEquals("", song.copy(artist = " ", albumArtist = " ", album = " ").albumArtistAlbum)
    }

    @Test fun channelTrackIdIsATrack() {
        val track = station(StationSource.Cliamp, "cliamp:edm:42")
        assertTrue(track.isChannelTrack)
        assertTrue(track.isTrack)
    }

    @Test fun liveCliampStreamIsNotATrack() {
        val live = station(StationSource.Cliamp, "cliamp:edm")
        assertFalse(live.isChannelTrack)
        assertFalse(live.isTrack)
    }

    @Test fun localProviderAndPodcastStayTracks() {
        assertTrue(station(StationSource.Local, "l1").isTrack)
        assertTrue(station(StationSource.Provider, "p1").isTrack)
        assertTrue(station(StationSource.Podcast, "e1").isTrack)
    }

    @Test fun directoryAndCustomStayStreams() {
        assertFalse(station(StationSource.Directory, "d1").isTrack)
        assertFalse(station(StationSource.Custom, "c1").isTrack)
    }
}
