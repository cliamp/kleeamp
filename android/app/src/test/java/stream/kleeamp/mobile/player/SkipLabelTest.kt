package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipLabelTest {
    @Test
    fun backNamesSecondsWithMinus() {
        assertEquals("-10s", skipLabel(-10))
        assertEquals("-15s", skipLabel(-15))
        assertEquals("-20s", skipLabel(-20))
        assertEquals("-30s", skipLabel(-30))
        assertEquals("-60s", skipLabel(-60))
        assertEquals("-90s", skipLabel(-90))
    }

    @Test
    fun forwardNamesSecondsWithPlus() {
        assertEquals("+10s", skipLabel(10))
        assertEquals("+15s", skipLabel(15))
        assertEquals("+20s", skipLabel(20))
        assertEquals("+30s", skipLabel(30))
        assertEquals("+60s", skipLabel(60))
        assertEquals("+90s", skipLabel(90))
    }

    @Test
    fun zeroReadsExplicit() {
        assertEquals("+0s", skipLabel(0))
    }
}
