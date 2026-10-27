package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.playback.resolveSeekBasePosition
import stream.kleeamp.mobile.playback.seekByTargetMs
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_BACK_SEC
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_FWD_SEC
import stream.kleeamp.mobile.prefs.SKIP_DURATIONS

class MiniPlayerControlsTest {

    private fun stationWith(source: StationSource) = Station(
        id = "station-id",
        name = "Station Name",
        url = "https://example.com/audio",
        source = source,
    )

    @Test
    fun podcastRendersSkipControls() {
        val podcast = stationWith(StationSource.Podcast)
        assertEquals(MiniKeyKind.SkipBack, miniLeftKeyKind(podcast))
        assertEquals(MiniKeyKind.SkipForward, miniRightKeyKind(podcast))
    }

    @Test
    fun radioRendersPreviousAndNextControls() {
        val radioDirectory = stationWith(StationSource.Directory)
        val radioCliamp = stationWith(StationSource.Cliamp)
        val radioCustom = stationWith(StationSource.Custom)

        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(radioDirectory))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(radioDirectory))

        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(radioCliamp))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(radioCliamp))

        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(radioCustom))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(radioCustom))
    }

    @Test
    fun localMusicAndProviderTracksRetainPreviousAndNextControls() {
        val local = stationWith(StationSource.Local)
        val provider = stationWith(StationSource.Provider)

        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(local))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(local))

        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(provider))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(provider))
    }

    @Test
    fun nullStationRetainsPreviousAndNextControls() {
        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(null))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(null))
    }

    @Test
    fun backwardAndForwardCallbacksUseCorrectSignsAndConfiguredValues() {
        for (back in SKIP_DURATIONS) {
            val deltaBack = miniKeySeekDelta(MiniKeyKind.SkipBack, back, DEFAULT_SKIP_FWD_SEC)
            assertEquals(-back, deltaBack)
        }

        for (fwd in SKIP_DURATIONS) {
            val deltaFwd = miniKeySeekDelta(MiniKeyKind.SkipForward, DEFAULT_SKIP_BACK_SEC, fwd)
            assertEquals(fwd, deltaFwd)
        }

        assertNull(miniKeySeekDelta(MiniKeyKind.Prev, DEFAULT_SKIP_BACK_SEC, DEFAULT_SKIP_FWD_SEC))
        assertNull(miniKeySeekDelta(MiniKeyKind.Next, DEFAULT_SKIP_BACK_SEC, DEFAULT_SKIP_FWD_SEC))
    }

    @Test
    fun labelsAccuratelyCommunicateDirectionAndDuration() {
        for (back in SKIP_DURATIONS) {
            val label = miniKeyLabel(MiniKeyKind.SkipBack, back, DEFAULT_SKIP_FWD_SEC)
            assertEquals("skip back $back seconds", label)
        }

        for (fwd in SKIP_DURATIONS) {
            val label = miniKeyLabel(MiniKeyKind.SkipForward, DEFAULT_SKIP_BACK_SEC, fwd)
            assertEquals("skip forward $fwd seconds", label)
        }

        assertEquals("previous", miniKeyLabel(MiniKeyKind.Prev, 10, 10))
        assertEquals("next", miniKeyLabel(MiniKeyKind.Next, 10, 10))
    }

    @Test
    fun switchingSourcesUpdatesControlsDynamically() {
        var currentStation: Station? = stationWith(StationSource.Podcast)
        assertEquals(MiniKeyKind.SkipBack, miniLeftKeyKind(currentStation))
        assertEquals(MiniKeyKind.SkipForward, miniRightKeyKind(currentStation))

        // Switch to live radio
        currentStation = stationWith(StationSource.Directory)
        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(currentStation))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(currentStation))

        // Switch to local music
        currentStation = stationWith(StationSource.Local)
        assertEquals(MiniKeyKind.Prev, miniLeftKeyKind(currentStation))
        assertEquals(MiniKeyKind.Next, miniRightKeyKind(currentStation))

        // Switch back to podcast
        currentStation = stationWith(StationSource.Podcast)
        assertEquals(MiniKeyKind.SkipBack, miniLeftKeyKind(currentStation))
        assertEquals(MiniKeyKind.SkipForward, miniRightKeyKind(currentStation))
    }

    @Test
    fun rapidSkipAccumulationThroughMiniPlayerDeltas() {
        val durationMs = 120_000L
        val initialPosition = 20_000L
        val skipFwdSec = 10
        val delta = miniKeySeekDelta(MiniKeyKind.SkipForward, DEFAULT_SKIP_BACK_SEC, skipFwdSec)!!

        var firedTarget: Long? = null
        val staleClock = initialPosition

        for (i in 1..3) {
            val base = resolveSeekBasePosition(
                pendingTargetMs = null,
                firedTargetMs = firedTarget,
                rawPositionMs = staleClock,
                itemMatchesStation = true,
                statePositionMs = staleClock,
            )
            firedTarget = seekByTargetMs(base, durationMs, delta)!!
            assertEquals(initialPosition + i * skipFwdSec * 1000L, firedTarget)
        }

        assertEquals(50_000L, firedTarget)
    }
}
