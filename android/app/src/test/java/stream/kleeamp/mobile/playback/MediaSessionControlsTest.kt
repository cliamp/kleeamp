package stream.kleeamp.mobile.playback

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_BACK_SEC
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_FWD_SEC
import stream.kleeamp.mobile.prefs.SKIP_DURATIONS

@UnstableApi
class MediaSessionControlsTest {

    private fun station(
        source: StationSource,
        id: String = "test-id",
        name: String = "test-station",
        url: String = "http://example.com/audio",
    ) = Station(
        id = id,
        name = name,
        url = url,
        source = source,
    )

    private val testIconUriProvider: (Int) -> Uri? = { null }

    @Test
    fun secondsToMsConvertsAllSupportedDurations() {
        for (sec in SKIP_DURATIONS) {
            assertEquals(sec * 1000L, secondsToMs(sec))
        }
        assertEquals(0L, secondsToMs(0))
        assertEquals(10_000L, secondsToMs(10))
        assertEquals(15_000L, secondsToMs(15))
        assertEquals(20_000L, secondsToMs(20))
        assertEquals(30_000L, secondsToMs(30))
        assertEquals(60_000L, secondsToMs(60))
        assertEquals(90_000L, secondsToMs(90))
    }

    @Test
    fun defaultSkipIncrementsMatchDefaults() {
        assertEquals(10_000L, secondsToMs(DEFAULT_SKIP_BACK_SEC))
        assertEquals(10_000L, secondsToMs(DEFAULT_SKIP_FWD_SEC))
    }

    @Test
    fun podcastExposesSeekBackAndSeekForwardCommandButtons() {
        val podcast = station(StationSource.Podcast)
        val buttons = buildMediaButtons(
            station = podcast,
            isFavourite = false,
            shuffled = false,
            iconUriProvider = testIconUriProvider,
        )

        assertEquals(4, buttons.size)

        // Left button: Skip back
        val left = buttons[0]
        assertEquals(CommandButton.ICON_REWIND, left.icon)
        assertEquals("Skip back", left.displayName)
        assertEquals(Player.COMMAND_SEEK_BACK, left.playerCommand)
        assertNull(left.sessionCommand)

        // Right button: Skip forward
        val right = buttons[3]
        assertEquals(CommandButton.ICON_FAST_FORWARD, right.icon)
        assertEquals("Skip forward", right.displayName)
        assertEquals(Player.COMMAND_SEEK_FORWARD, right.playerCommand)
        assertNull(right.sessionCommand)
    }

    @Test
    fun radioExposesPreviousAndNextSessionCommandButtons() {
        val radio = station(StationSource.Directory)
        val buttons = buildMediaButtons(
            station = radio,
            isFavourite = false,
            shuffled = false,
            iconUriProvider = testIconUriProvider,
        )

        assertEquals(4, buttons.size)

        // Left button: Previous
        val left = buttons[0]
        assertEquals(CommandButton.ICON_PREVIOUS, left.icon)
        assertEquals("Previous", left.displayName)
        assertNotNull(left.sessionCommand)
        assertEquals(PlaybackService.CMD_PREV_STATION, left.sessionCommand?.customAction)

        // Right button: Next
        val right = buttons[3]
        assertEquals(CommandButton.ICON_NEXT, right.icon)
        assertEquals("Next", right.displayName)
        assertNotNull(right.sessionCommand)
        assertEquals(PlaybackService.CMD_NEXT_STATION, right.sessionCommand?.customAction)
    }

    @Test
    fun nonPodcastSourcesRetainPreviousAndNextButtons() {
        val nonPodcastSources = listOf(
            StationSource.Directory,
            StationSource.Custom,
            StationSource.Local,
            StationSource.Provider,
            StationSource.Cliamp,
        )

        for (source in nonPodcastSources) {
            val buttons = buildMediaButtons(
                station = station(source),
                isFavourite = false,
                shuffled = false,
                iconUriProvider = testIconUriProvider,
            )
            assertEquals("Source $source must have Previous left button",
                PlaybackService.CMD_PREV_STATION, buttons[0].sessionCommand?.customAction)
            assertEquals("Source $source must have Next right button",
                PlaybackService.CMD_NEXT_STATION, buttons[3].sessionCommand?.customAction)
        }
    }

    @Test
    fun nullStationRetainsPreviousAndNextButtonsSafely() {
        val buttons = buildMediaButtons(
            station = null,
            isFavourite = false,
            shuffled = false,
            iconUriProvider = testIconUriProvider,
        )

        assertEquals(4, buttons.size)
        assertEquals(PlaybackService.CMD_PREV_STATION, buttons[0].sessionCommand?.customAction)
        assertEquals(PlaybackService.CMD_NEXT_STATION, buttons[3].sessionCommand?.customAction)
    }

    @Test
    fun favouriteAndShuffleFlagsReflectedAccurately() {
        val unfavUnshuffled = buildMediaButtons(
            station = station(StationSource.Podcast),
            isFavourite = false,
            shuffled = false,
            iconUriProvider = testIconUriProvider,
        )
        assertEquals(CommandButton.ICON_STAR_UNFILLED, unfavUnshuffled[1].icon)
        assertEquals("Favourite", unfavUnshuffled[1].displayName)
        assertEquals(PlaybackService.CMD_FAVOURITE, unfavUnshuffled[1].sessionCommand?.customAction)
        assertEquals(CommandButton.ICON_SHUFFLE_OFF, unfavUnshuffled[2].icon)
        assertEquals("Shuffle", unfavUnshuffled[2].displayName)
        assertEquals(PlaybackService.CMD_SHUFFLE, unfavUnshuffled[2].sessionCommand?.customAction)

        val favShuffled = buildMediaButtons(
            station = station(StationSource.Directory),
            isFavourite = true,
            shuffled = true,
            iconUriProvider = testIconUriProvider,
        )
        assertEquals(CommandButton.ICON_STAR_FILLED, favShuffled[1].icon)
        assertEquals("Remove favourite", favShuffled[1].displayName)
        assertEquals(PlaybackService.CMD_FAVOURITE, favShuffled[1].sessionCommand?.customAction)
        assertEquals(CommandButton.ICON_SHUFFLE_ON, favShuffled[2].icon)
        assertEquals("Stop shuffling", favShuffled[2].displayName)
        assertEquals(PlaybackService.CMD_SHUFFLE, favShuffled[2].sessionCommand?.customAction)
    }

    @Test
    fun stationTransitionTogglesBetweenSkipAndStepButtons() {
        val radioButtons = buildMediaButtons(station(StationSource.Directory), false, false, testIconUriProvider)
        assertEquals(PlaybackService.CMD_PREV_STATION, radioButtons[0].sessionCommand?.customAction)
        assertEquals(PlaybackService.CMD_NEXT_STATION, radioButtons[3].sessionCommand?.customAction)

        val podcastButtons = buildMediaButtons(station(StationSource.Podcast), false, false, testIconUriProvider)
        assertEquals(Player.COMMAND_SEEK_BACK, podcastButtons[0].playerCommand)
        assertEquals(Player.COMMAND_SEEK_FORWARD, podcastButtons[3].playerCommand)

        val localButtons = buildMediaButtons(station(StationSource.Local), false, false, testIconUriProvider)
        assertEquals(PlaybackService.CMD_PREV_STATION, localButtons[0].sessionCommand?.customAction)
        assertEquals(PlaybackService.CMD_NEXT_STATION, localButtons[3].sessionCommand?.customAction)
    }

    @Test
    fun bindSeekIncrementsUpdatesDynamically() = runTest {
        val backFlow = MutableStateFlow(10)
        val fwdFlow = MutableStateFlow(10)
        val updates = mutableListOf<Pair<Long, Long>>()

        val job = bindSeekIncrements(backFlow, fwdFlow, this) { b, f ->
            updates.add(b to f)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(1, updates.size)
        assertEquals(10_000L to 10_000L, updates.last())

        // Change skip backward to 30s
        backFlow.value = 30
        testScheduler.advanceUntilIdle()
        assertEquals(2, updates.size)
        assertEquals(30_000L to 10_000L, updates.last())

        // Change skip forward to 60s
        fwdFlow.value = 60
        testScheduler.advanceUntilIdle()
        assertEquals(3, updates.size)
        assertEquals(30_000L to 60_000L, updates.last())

        // Scope cancellation stops updates
        job.cancel()
        backFlow.value = 90
        testScheduler.advanceUntilIdle()
        assertEquals(3, updates.size)
    }

    @Test
    fun bindSeekIncrementsHandlesRapidPreferenceUpdates() = runTest {
        val backFlow = MutableStateFlow(10)
        val fwdFlow = MutableStateFlow(10)
        val updates = mutableListOf<Pair<Long, Long>>()

        val job = bindSeekIncrements(backFlow, fwdFlow, this) { b, f ->
            updates.add(b to f)
        }
        testScheduler.advanceUntilIdle()

        // Rapid emissions across all durations
        for (duration in SKIP_DURATIONS) {
            backFlow.value = duration
            fwdFlow.value = duration
        }
        testScheduler.advanceUntilIdle()

        assertEquals(90_000L to 90_000L, updates.last())
        job.cancel()
    }
}
