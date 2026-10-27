package stream.kleeamp.mobile.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.playback.seekByTargetMs
import stream.kleeamp.mobile.prefs.PlaySection
import stream.kleeamp.mobile.prefs.playSectionFor

class TransportEditTest {

    @Test fun seekByMovesForward() {
        assertEquals(45_000L, seekByTargetMs(15_000L, 120_000L, 30))
        assertEquals(35_000L, seekByTargetMs(15_000L, 120_000L, 20))
        assertEquals(75_000L, seekByTargetMs(15_000L, 120_000L, 60))
        assertEquals(105_000L, seekByTargetMs(15_000L, 120_000L, 90))
    }

    @Test fun seekByMovesBack() {
        assertEquals(5_000L, seekByTargetMs(15_000L, 120_000L, -10))
        assertEquals(10_000L, seekByTargetMs(30_000L, 120_000L, -20))
        assertEquals(30_000L, seekByTargetMs(90_000L, 120_000L, -60))
        assertEquals(10_000L, seekByTargetMs(100_000L, 120_000L, -90))
    }

    @Test fun seekByClampsToZero() {
        assertEquals(0L, seekByTargetMs(5_000L, 120_000L, -30))
    }

    @Test fun seekByClampsShortOfTail() {
        // 250ms end margin: Media3 would auto-advance past it.
        assertEquals(119_750L, seekByTargetMs(110_000L, 120_000L, 30))
    }

    @Test fun seekByRejectsBadInput() {
        assertNull(seekByTargetMs(10_000L, 0L, 30))
        assertNull(seekByTargetMs(-1L, 120_000L, 30))
    }

    @Test fun sectionsSplitBySource() {
        assertEquals(PlaySection.Podcasts, playSectionFor(StationSource.Podcast))
        assertEquals(PlaySection.Library, playSectionFor(StationSource.Local))
        assertEquals(PlaySection.Stations, playSectionFor(StationSource.Cliamp))
        assertEquals(PlaySection.Stations, playSectionFor(StationSource.Directory))
        assertEquals(PlaySection.Stations, playSectionFor(StationSource.Provider))
        assertEquals(PlaySection.Stations, playSectionFor(StationSource.Custom))
    }
}
