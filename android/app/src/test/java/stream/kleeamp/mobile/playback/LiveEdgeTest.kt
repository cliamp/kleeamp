package stream.kleeamp.mobile.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEdgeTest {

    @Test
    fun shortPauseKeepsTheBuffer() {
        // A quick pause-resume plays the buffered seconds: no re-join gap.
        assertFalse(shouldRejoinLive(LIVE_BUFFER_HOLD_MS - 1, "st:1", "st:1"))
    }

    @Test
    fun pausePastTheHoldRejoinsLive() {
        assertTrue(shouldRejoinLive(LIVE_BUFFER_HOLD_MS, "st:1", "st:1"))
        assertTrue(shouldRejoinLive(60 * 60_000L, "st:1", "st:1"))
    }

    @Test
    fun aDifferentItemNeverInheritsTheHold() {
        // Station switched while paused: the new item starts on its own.
        assertFalse(shouldRejoinLive(60_000, "st:1", "st:2"))
        assertFalse(shouldRejoinLive(60_000, null, "st:1"))
        assertFalse(shouldRejoinLive(60_000, "", null))
        assertFalse(shouldRejoinLive(60_000, "st:1", null))
    }
}
