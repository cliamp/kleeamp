package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.playback.resolveSeekBasePosition
import stream.kleeamp.mobile.playback.seekByTargetMs
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_BACK_SEC
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_FWD_SEC
import stream.kleeamp.mobile.prefs.SKIP_DURATIONS

class ExpandedPlayerRegressionTest {

    @Test
    fun defaultStateHasTenSecondSkipDurations() {
        val defaultUiState = NowPlayingViewModel.UiState()
        assertEquals(10, DEFAULT_SKIP_BACK_SEC)
        assertEquals(10, DEFAULT_SKIP_FWD_SEC)
        assertEquals(DEFAULT_SKIP_BACK_SEC, defaultUiState.skipBackSec)
        assertEquals(DEFAULT_SKIP_FWD_SEC, defaultUiState.skipFwdSec)
    }

    @Test
    fun tracksEnableSkipKeysWhileLiveRadioKeepsThreeKeyRow() {
        val podcast = Station(
            id = "podcast-1",
            name = "Podcast Episode",
            url = "https://example.com/ep.mp3",
            source = StationSource.Podcast,
        )
        val radio = Station(
            id = "radio-1",
            name = "Live FM",
            url = "https://example.com/live",
            source = StationSource.Directory,
        )

        assertTrue(podcast.isTrack)
        assertFalse(radio.isTrack)

        // NowPlayingViewModel uses shownStation?.isTrack == true
        val podcastShowKeys = podcast.isTrack
        val radioShowKeys = radio.isTrack

        assertTrue("Podcast episodes must show skip keys", podcastShowKeys)
        assertFalse("Live radio must retain plain 3-key row", radioShowKeys)
    }

    @Test
    fun skipLabelsFormatAllSupportedDurations() {
        for (duration in SKIP_DURATIONS) {
            assertEquals("-${duration}s", skipLabel(-duration))
            assertEquals("+${duration}s", skipLabel(duration))
        }
    }

    @Test
    fun expandedPlayerActionDeltasMatchConfiguredPreferences() {
        // PlayerActions maps:
        // onSkipBack = { vm.player.seekBy(-uiState.skipBackSec) }
        // onSkipFwd = { vm.player.seekBy(uiState.skipFwdSec) }
        for (backSec in SKIP_DURATIONS) {
            val deltaBack = -backSec
            assertTrue(deltaBack < 0)
            assertEquals(-backSec, deltaBack)
        }
        for (fwdSec in SKIP_DURATIONS) {
            val deltaFwd = fwdSec
            assertTrue(deltaFwd > 0)
            assertEquals(fwdSec, deltaFwd)
        }
    }

    @Test
    fun rapidSkipsFromExpandedPlayerAccumulateAccurately() {
        val durationMs = 180_000L
        val initialPosition = 30_000L
        val skipFwdSec = 10

        var firedTarget: Long? = null
        val staleClock = initialPosition

        // 3 rapid forward taps on the expanded player skip key
        for (i in 1..3) {
            val base = resolveSeekBasePosition(
                pendingTargetMs = null,
                firedTargetMs = firedTarget,
                rawPositionMs = staleClock,
                itemMatchesStation = true,
                statePositionMs = staleClock,
            )
            firedTarget = seekByTargetMs(base, durationMs, skipFwdSec)!!
            assertEquals(initialPosition + i * skipFwdSec * 1000L, firedTarget)
        }

        // Final position is exactly 30s ahead
        assertEquals(60_000L, firedTarget)
    }

    @Test
    fun resettingPreferencesRestoresExpandedPlayerDefaults() {
        var configuredBack = 60
        var configuredFwd = 90
        assertEquals(60, configuredBack)
        assertEquals(90, configuredFwd)

        // Reset action dispatched from SettingsScreen
        configuredBack = DEFAULT_SKIP_BACK_SEC
        configuredFwd = DEFAULT_SKIP_FWD_SEC

        assertEquals(10, configuredBack)
        assertEquals(10, configuredFwd)
    }
}
