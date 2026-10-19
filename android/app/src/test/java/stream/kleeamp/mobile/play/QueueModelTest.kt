package stream.kleeamp.mobile.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class QueueModelTest {
    private fun station(i: Int) = Station(
        id = "id-$i",
        name = "s$i",
        url = "url-$i",
        source = StationSource.Local,
    )

    private fun list(n: Int) = (0 until n).map(::station)

    @Test
    fun playFromListWindowsFromTappedIndex() {
        val model = QueueModel()
        val from = list(10)
        model.playFromList(from[4], from, preserveOrder = false, sourceIndex = null)
        assertEquals(from, model.source)
        assertEquals(from, model.baseSource)
        assertEquals(from, model.currentUpNext)
        assertEquals(4, model.currentIndex)
        assertEquals(0, model.windowBase)
        assertFalse(model.ringFallback)
    }

    @Test
    fun playFromListCapsHugeSources() {
        val model = QueueModel()
        val from = list(200)
        model.playFromList(from[100], from, preserveOrder = false, sourceIndex = null)
        assertEquals(200, model.source.size)
        assertEquals(60, model.currentUpNext.size)
        assertEquals(from[100], model.currentUpNext.first())
        assertEquals(from[159], model.currentUpNext.last())
        assertEquals(100, model.windowBase)
        assertEquals(0, model.currentIndex)
    }

    @Test
    fun playFromListRebasesShuffleBookkeeping() {
        val model = QueueModel()
        val first = list(5)
        model.playFromList(first[0], first, preserveOrder = false, sourceIndex = null)
        val second = listOf(station(9), station(8), station(7))
        model.playFromList(second[0], second, preserveOrder = false, sourceIndex = null)
        // The stale base from the first play is gone; toggling shuffle off
        // later restores the second list, not the first.
        assertEquals(second.map { it.url }, model.baseSource.map { it.url })
    }

    @Test
    fun playFromWindowSingleBecomesLoneQueue() {
        val model = QueueModel()
        val s = station(3)
        model.playFromWindow(s)
        assertEquals(listOf(s), model.source)
        assertEquals(listOf(s), model.currentUpNext)
        assertEquals(0, model.currentIndex)
    }

    @Test
    fun playFromWindowKeepsKnownWindow() {
        val model = QueueModel()
        val from = list(6)
        model.playFromList(from[0], from, preserveOrder = false, sourceIndex = null)
        model.playFromWindow(from[4])
        assertEquals(from, model.source)
        assertEquals(from, model.currentUpNext)
        assertEquals(4, model.currentIndex)
    }

    @Test
    fun rewindowSlicesHugeSource() {
        val model = QueueModel()
        model.source = list(200)
        val w = model.rewindow(190)
        assertEquals(190, model.windowBase)
        assertEquals(10, model.currentUpNext.size)
        assertEquals(0, model.currentIndex)
        assertEquals(190, w.base)
    }

    @Test
    fun appendSourceExtendsWalkingOrderAndPanel() {
        val model = QueueModel()
        val first = list(3)
        model.playFromList(first[0], first, preserveOrder = false, sourceIndex = null)
        val added = listOf(station(3), station(4))
        assertTrue(model.appendSource(added))
        assertEquals((first + added).map { it.url }, model.source.map { it.url })
        assertEquals(model.source.map { it.url }, model.baseSource.map { it.url })
        assertEquals(model.source.map { it.url }, model.panelList.value.map { it.url })
    }

    @Test
    fun appendSourceDropsDuplicatesAndRejectsEmpty() {
        val model = QueueModel()
        val first = list(3)
        model.playFromList(first[0], first, preserveOrder = false, sourceIndex = null)
        assertFalse(model.appendSource(emptyList()))
        assertFalse(model.appendSource(listOf(station(2))))
        assertTrue(model.appendSource(listOf(station(2), station(3))))
        assertEquals(listOf("url-0", "url-1", "url-2", "url-3"), model.source.map { it.url })
    }

    @Test
    fun appendSourceExtendsShuffledBaseOrderToo() {
        val model = QueueModel()
        val first = list(4)
        model.playFromList(first[0], first, preserveOrder = false, sourceIndex = null)
        model.setShuffled(true)
        model.replaceOrder(listOf(first[2], first[0], first[3], first[1]), first)
        assertTrue(model.appendSource(listOf(station(4), station(5))))
        assertEquals(6, model.source.size)
        // Turning shuffle off must still walk every paged station in the
        // catalogue's natural order.
        assertEquals(
            (first + listOf(station(4), station(5))).map { it.url },
            model.baseSource.map { it.url },
        )
    }

    @Test
    fun replaceOrderSwapsWalkingLists() {
        val model = QueueModel()
        val base = list(4)
        model.replaceOrder(listOf(base[3], base[0]), base)
        assertEquals(listOf("url-3", "url-0"), model.source.map { it.url })
        assertEquals(base.map { it.url }, model.baseSource.map { it.url })
    }

    @Test
    fun stationForMediaIdPrefersWindowThenSource() {
        val model = QueueModel()
        model.source = list(10)
        model.rewindow(5)
        assertEquals("url-5", model.stationForMediaId("id-5")?.url)
        assertEquals("url-1", model.stationForMediaId("id-1")?.url)
        assertEquals(null, model.stationForMediaId("nope"))
    }

    @Test
    fun shuffleFlagRoundTrips() {
        val model = QueueModel()
        assertFalse(model.shuffleOn)
        model.setShuffled(true)
        assertTrue(model.shuffleOn)
        assertTrue(model.shuffle.value)
    }

    @Test
    fun repeatModeRoundTrips() {
        val model = QueueModel()
        assertEquals(RepeatMode.Off, model.repeatMode)
        model.setRepeatMode(RepeatMode.One)
        assertEquals(RepeatMode.One, model.repeatMode)
        assertEquals(RepeatMode.One, model.repeat.value)
    }

    @Test
    fun fallbackSourceIsSettable() {
        val model = QueueModel()
        model.setFallbackSource(list(3))
        assertEquals(3, model.fallback.size)
    }

    @Test
    fun playNextInsertsRightAfterCurrent() {
        val model = QueueModel()
        model.setWindow(list(5), 2)
        assertEquals(3, model.playNextInsertAt())
        // Nothing current: appends.
        model.setWindow(list(5), -1)
        assertEquals(5, model.playNextInsertAt())
        model.setWindow(emptyList(), -1)
        assertEquals(0, model.playNextInsertAt())
    }

    @Test
    fun persistWindowPrependsPredecessorsAndOffsetsIndex() {
        val model = QueueModel()
        val source = list(200)
        model.playFromList(source[50], source, preserveOrder = false, sourceIndex = null)
        model.setIndex(5)
        val (combined, idx) = model.persistWindow(model.currentUpNext)
        assertEquals(68, combined.size)
        assertEquals(source[42], combined.first())
        assertEquals(13, idx)
    }

    @Test
    fun persistWindowOnShortSourceKeepsWindow() {
        val model = QueueModel()
        val source = list(10)
        model.playFromList(source[3], source, preserveOrder = false, sourceIndex = null)
        val (combined, idx) = model.persistWindow(model.currentUpNext)
        assertEquals(source, combined)
        assertEquals(3, idx)
    }
}
