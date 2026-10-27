package stream.kleeamp.mobile.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekTargetTest {

    @Test
    fun midFractionScalesWithDuration() {
        assertEquals(60_000L, seekTargetMs(240_000L, 0.25f))
        assertEquals(120_000L, seekTargetMs(240_000L, 0.5f))
    }

    @Test
    fun endSeekClampsShortOfTailInsteadOfDropping() {
        // A fling to the edge must land and play out, never snap back.
        assertEquals(239_750L, seekTargetMs(240_000L, 1f))
        assertEquals(239_750L, seekTargetMs(240_000L, 2f))
        assertEquals(239_750L, seekTargetMs(240_000L, 0.9999f))
    }

    @Test
    fun fractionClampsBothWays() {
        assertEquals(0L, seekTargetMs(240_000L, 0f))
        assertEquals(0L, seekTargetMs(240_000L, -1f))
    }

    @Test
    fun noSaneTargetWithoutDuration() {
        assertNull(seekTargetMs(0L, 0.5f))
        assertNull(seekTargetMs(-5L, 0.5f))
        assertNull(seekTargetMs(C.TIME_UNSET, 0.5f))
    }

    @Test
    fun tinyDurationNeverGoesNegative() {
        assertEquals(0L, seekTargetMs(100L, 1f))
        assertEquals(0L, seekTargetMs(100L, 0.5f))
    }

    @Test
    fun primedAndFiredTargetsBeatASettlingClock() {
        // A fresh touch owns the clock from the tap, not the binder trip.
        assertEquals(120_000L, resolveKeptPosition(10_000L, true, true, 10_000L, 120_000L, null))
        // A fired seek keeps it until the player lands on it.
        assertEquals(120_000L, resolveKeptPosition(10_000L, true, true, 10_000L, null, 120_000L))
    }

    @Test
    fun staleWindowNeverLendsItsClock() {
        // New episode tapped while the old item still fills the window:
        // the old 30min clock must not show under the new title.
        assertEquals(0L, resolveKeptPosition(1_800_000L, false, false, 1_800_000L, null, null))
        // Same station through IDLE still carries the last tick.
        assertEquals(10_000L, resolveKeptPosition(0L, true, true, 10_000L, null, null))
        // Live player clock wins once it matches the station.
        assertEquals(10_000L, resolveKeptPosition(10_000L, true, true, 5_000L, null, null))
    }

    @Test
    fun resolveSeekBasePositionPrioritizesPendingTarget() {
        val base = resolveSeekBasePosition(
            pendingTargetMs = 45_000L,
            firedTargetMs = 30_000L,
            rawPositionMs = 15_000L,
            itemMatchesStation = true,
            statePositionMs = 15_000L,
        )
        assertEquals(45_000L, base)
    }

    @Test
    fun resolveSeekBasePositionPrioritizesFiredTargetOverStaleClock() {
        // Rapid skip tap: fired target must beat player's settling/stale clock
        val base = resolveSeekBasePosition(
            pendingTargetMs = null,
            firedTargetMs = 30_000L,
            rawPositionMs = 10_000L,
            itemMatchesStation = true,
            statePositionMs = 10_000L,
        )
        assertEquals(30_000L, base)
    }

    @Test
    fun resolveSeekBasePositionUsesRawPositionWhenItemMatches() {
        val base = resolveSeekBasePosition(
            pendingTargetMs = null,
            firedTargetMs = null,
            rawPositionMs = 15_000L,
            itemMatchesStation = true,
            statePositionMs = 0L,
        )
        assertEquals(15_000L, base)

        // Even at position zero
        val atZero = resolveSeekBasePosition(
            pendingTargetMs = null,
            firedTargetMs = null,
            rawPositionMs = 0L,
            itemMatchesStation = true,
            statePositionMs = 50_000L,
        )
        assertEquals(0L, atZero)
    }

    @Test
    fun resolveSeekBasePositionIgnoresStaleItemClockOnTransition() {
        // Station changed, player still holds previous item
        val base = resolveSeekBasePosition(
            pendingTargetMs = null,
            firedTargetMs = null,
            rawPositionMs = 50_000L,
            itemMatchesStation = false,
            statePositionMs = 0L,
        )
        assertEquals(0L, base)
    }

    @Test
    fun resolveSeekBasePositionNeverReturnsNegative() {
        assertEquals(0L, resolveSeekBasePosition(null, null, -5L, true, -10L))
        assertEquals(0L, resolveSeekBasePosition(-5L, null, null, false, 0L))
        assertEquals(0L, resolveSeekBasePosition(null, -10L, null, false, 0L))
    }

    @Test
    fun multipleRapidForwardSkipsAccumulateCorrectly() {
        // Start at 10s into a 120s episode
        val durationMs = 120_000L
        val rawPlayerClock = 10_000L // Stale player clock doesn't change during rapid taps
        var firedTarget: Long? = null

        // Tap 1 (+10s)
        val base1 = resolveSeekBasePosition(null, firedTarget, rawPlayerClock, true, rawPlayerClock)
        val target1 = seekByTargetMs(base1, durationMs, 10)!!
        assertEquals(20_000L, target1)
        firedTarget = target1

        // Tap 2 (+10s, rawPlayerClock still 10_000L)
        val base2 = resolveSeekBasePosition(null, firedTarget, rawPlayerClock, true, rawPlayerClock)
        val target2 = seekByTargetMs(base2, durationMs, 10)!!
        assertEquals(30_000L, target2)
        firedTarget = target2

        // Tap 3 (+10s, rawPlayerClock still 10_000L)
        val base3 = resolveSeekBasePosition(null, firedTarget, rawPlayerClock, true, rawPlayerClock)
        val target3 = seekByTargetMs(base3, durationMs, 10)!!
        assertEquals(40_000L, target3)

        // 3x 10s forward skips target exactly 30s ahead
        assertEquals(30_000L, target3 - 10_000L)
    }

    @Test
    fun multipleRapidBackwardSkipsAccumulateCorrectly() {
        val durationMs = 120_000L
        val initialPos = 50_000L
        val rawPlayerClock = initialPos
        var firedTarget: Long? = null

        // Tap 1 (-10s)
        val base1 = resolveSeekBasePosition(null, firedTarget, rawPlayerClock, true, rawPlayerClock)
        val target1 = seekByTargetMs(base1, durationMs, -10)!!
        assertEquals(40_000L, target1)
        firedTarget = target1

        // Tap 2 (-10s, rawPlayerClock still 50_000L)
        val base2 = resolveSeekBasePosition(null, firedTarget, rawPlayerClock, true, rawPlayerClock)
        val target2 = seekByTargetMs(base2, durationMs, -10)!!
        assertEquals(30_000L, target2)
        firedTarget = target2

        // Tap 3 (-10s, rawPlayerClock still 50_000L)
        val base3 = resolveSeekBasePosition(null, firedTarget, rawPlayerClock, true, rawPlayerClock)
        val target3 = seekByTargetMs(base3, durationMs, -10)!!
        assertEquals(20_000L, target3)

        // 3x 10s backward skips target exactly 30s behind
        assertEquals(30_000L, initialPos - target3)
    }

    @Test
    fun mixedRapidForwardAndBackwardSkips() {
        val durationMs = 120_000L
        val initialPos = 30_000L
        val staleClock = initialPos
        var firedTarget: Long? = null

        // Tap 1: +15s -> 45s
        var base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, 15)!!
        assertEquals(45_000L, firedTarget)

        // Tap 2: +20s -> 65s
        base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, 20)!!
        assertEquals(65_000L, firedTarget)

        // Tap 3: -10s -> 55s
        base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, -10)!!
        assertEquals(55_000L, firedTarget)

        // Tap 4: -30s -> 25s
        base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, -30)!!
        assertEquals(25_000L, firedTarget)
    }

    @Test
    fun seekingNearBeginningClampsAtZeroAndRecovers() {
        val durationMs = 120_000L
        val staleClock = 8_000L
        var firedTarget: Long? = null

        // Tap 1: -10s from 8s -> clamps to 0s
        var base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, -10)!!
        assertEquals(0L, firedTarget)

        // Tap 2: another -10s rapid tap while at 0s -> stays at 0s
        base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, -10)!!
        assertEquals(0L, firedTarget)

        // Tap 3: +10s forward from 0s -> advances to 10s
        base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, 10)!!
        assertEquals(10_000L, firedTarget)
    }

    @Test
    fun seekingNearEndClampsShortOfTailAndRecovers() {
        val durationMs = 100_000L
        val tailLimit = 100_000L - SEEK_END_MARGIN_MS // 99_750L
        val staleClock = 95_000L
        var firedTarget: Long? = null

        // Tap 1: +10s from 95s -> clamps to 99_750L
        var base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, 10)!!
        assertEquals(tailLimit, firedTarget)

        // Tap 2: another +10s rapid tap -> stays clamped at 99_750L
        base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, 10)!!
        assertEquals(tailLimit, firedTarget)

        // Tap 3: -10s back from clamped end -> moves to 89_750L
        base = resolveSeekBasePosition(null, firedTarget, staleClock, true, staleClock)
        firedTarget = seekByTargetMs(base, durationMs, -10)!!
        assertEquals(tailLimit - 10_000L, firedTarget)
    }

    @Test
    fun unknownDurationReturnsNullAndDoesNotSeek() {
        // Unknown or invalid duration must safely return null
        assertNull(seekByTargetMs(10_000L, 0L, 10))
        assertNull(seekByTargetMs(10_000L, -1L, 10))
        assertNull(seekByTargetMs(10_000L, C.TIME_UNSET, 10))
        assertNull(seekTargetMs(0L, 0.5f))
        assertNull(seekTargetMs(-100L, 0.5f))
        assertNull(seekTargetMs(C.TIME_UNSET, 0.5f))
    }

    @Test
    fun bufferingPendingSeeksAccumulate() {
        // When player is unready/buffering, pending target is kept and accumulates
        val durationMs = 120_000L
        val staleClock = 0L
        var pendingTarget: Long? = null

        // Tap 1 (+10s while buffering)
        var base = resolveSeekBasePosition(pendingTarget, null, staleClock, false, 0L)
        pendingTarget = seekByTargetMs(base, durationMs, 10)!!
        assertEquals(10_000L, pendingTarget)

        // Tap 2 (+10s while still buffering)
        base = resolveSeekBasePosition(pendingTarget, null, staleClock, false, 0L)
        pendingTarget = seekByTargetMs(base, durationMs, 10)!!
        assertEquals(20_000L, pendingTarget)

        // Tap 3 (+10s while still buffering)
        base = resolveSeekBasePosition(pendingTarget, null, staleClock, false, 0L)
        pendingTarget = seekByTargetMs(base, durationMs, 10)!!
        assertEquals(30_000L, pendingTarget)
    }
}
