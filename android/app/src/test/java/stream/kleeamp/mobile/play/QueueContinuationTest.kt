package stream.kleeamp.mobile.play

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.radio.DirectoryQuery

class QueueContinuationTest {
    private fun station(i: Int) = Station(
        id = "id-$i",
        name = "s$i",
        url = "url-$i",
        source = StationSource.Directory,
    )

    @Test
    fun loadWalksPagesAndAdvancesOffset() = runBlocking {
        val pages = mapOf(
            0 to listOf(station(0), station(1)),
            2 to listOf(station(2)),
        )
        val continuation = QueueContinuation("top", 0) { offset -> pages[offset].orEmpty() }

        assertEquals(listOf("url-0", "url-1"), continuation.load().map { it.url })
        assertEquals(2, continuation.offset)
        assertEquals(listOf("url-2"), continuation.load().map { it.url })
        assertEquals(3, continuation.offset)
        assertTrue(continuation.load().isEmpty())
        // An empty page is the end of the catalogue: the offset stays put.
        assertEquals(3, continuation.offset)
    }

    @Test
    fun restoredOffsetResumesMidCatalogue() = runBlocking {
        val asked = mutableListOf<Int>()
        val continuation = QueueContinuation("country\u0000us", startOffset = 60) { offset ->
            asked += offset
            emptyList()
        }
        continuation.load()
        assertEquals(listOf(60), asked)
    }

    @Test
    fun directoryOriginKeysRoundTrip() {
        val queries = listOf(
            DirectoryQuery.TopVoted,
            DirectoryQuery.Trending,
            DirectoryQuery.Search("jazz fm"),
            DirectoryQuery.Tag("80s"),
        )
        for (query in queries) {
            assertEquals(query, DirectoryQuery.fromOriginKey(query.originKey))
        }
        // A country keeps its code; the display name is rebuilt as the code.
        val country = DirectoryQuery.fromOriginKey(
            DirectoryQuery.Country("us", "United States").originKey,
        ) as DirectoryQuery.Country
        assertEquals("US", country.code)
        assertEquals(DirectoryQuery.Country("US", "US").originKey, country.originKey)
        assertEquals(null, DirectoryQuery.fromOriginKey("nonsense"))
        assertEquals(null, DirectoryQuery.fromOriginKey("search\u0000"))
    }
}
