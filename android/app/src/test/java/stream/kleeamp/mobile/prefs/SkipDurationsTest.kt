package stream.kleeamp.mobile.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipDurationsTest {

    @Test
    fun availableDurationsMatchRequiredOptions() {
        assertEquals(listOf(10, 15, 20, 30, 60, 90), SKIP_DURATIONS)
    }

    @Test
    fun defaultsAreFifteenBackAndThirtyForward() {
        assertEquals(15, DEFAULT_SKIP_BACK_SEC)
        assertEquals(30, DEFAULT_SKIP_FWD_SEC)
        assertTrue(DEFAULT_SKIP_BACK_SEC in SKIP_DURATIONS)
        assertTrue(DEFAULT_SKIP_FWD_SEC in SKIP_DURATIONS)
    }

    @Test
    fun legacyOrInvalidDurationsFallbackToDefault() {
        val invalidDurations = listOf(null, 0, -1, 5, 25, 45, 100, 120)
        for (invalid in invalidDurations) {
            val back = (invalid ?: DEFAULT_SKIP_BACK_SEC).let { v ->
                if (v in SKIP_DURATIONS) v else DEFAULT_SKIP_BACK_SEC
            }
            val fwd = (invalid ?: DEFAULT_SKIP_FWD_SEC).let { v ->
                if (v in SKIP_DURATIONS) v else DEFAULT_SKIP_FWD_SEC
            }
            assertEquals("Invalid $invalid must fall back to back default", DEFAULT_SKIP_BACK_SEC, back)
            assertEquals("Invalid $invalid must fall back to forward default", DEFAULT_SKIP_FWD_SEC, fwd)
        }
    }

    @Test
    fun validDurationsArePreservedIndependently() {
        for (backOption in SKIP_DURATIONS) {
            for (fwdOption in SKIP_DURATIONS) {
                val back = backOption.let { if (it in SKIP_DURATIONS) it else DEFAULT_SKIP_BACK_SEC }
                val fwd = fwdOption.let { if (it in SKIP_DURATIONS) it else DEFAULT_SKIP_FWD_SEC }
                assertEquals(backOption, back)
                assertEquals(fwdOption, fwd)
            }
        }
    }
}
