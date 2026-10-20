package stream.kleeamp.mobile.podcasts

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.db.CacheDao
import stream.kleeamp.mobile.db.EpisodeProgressEntity
import stream.kleeamp.mobile.db.KvCacheEntity
import stream.kleeamp.mobile.db.PodcastDao
import stream.kleeamp.mobile.db.PodcastFeedCacheEntity
import stream.kleeamp.mobile.db.PodcastSubscriptionEntity
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.net.Http

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastRepositoryTest {
    private val showA = PodcastShow("a", "Show A", "https://example.com/a.xml")
    private val showB = PodcastShow("b", "Show B", "https://example.com/b.xml")

    private fun station(url: String, durationMs: Long = 60_000L) = Station(
        id = "pod:test:$url",
        name = url,
        url = url,
        source = StationSource.Podcast,
        durationMs = durationMs,
    )

    @Test
    fun lateSuccessCannotReplaceTheNewerShow() = runTest {
        val oldResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            if (show == showA) withContext(NonCancellable) { oldResponse.await() }
            else Result.success(loaded(showB))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        assertEquals(loadedState(showB), repository.show.value)

        oldResponse.complete(Result.success(loaded(showA)))
        runCurrent()
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun lateFailureCannotReplaceOrStopTheNewerLoadingShow() = runTest {
        val oldResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val newResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            withContext(NonCancellable) {
                if (show == showA) oldResponse.await() else newResponse.await()
            }
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        oldResponse.complete(Result.failure(IOException("old feed failed")))
        runCurrent()
        val stateWhileBLoads = repository.show.value
        newResponse.complete(Result.success(loaded(showB)))
        runCurrent()
        assertEquals(ShowState(show = showB, loading = true), stateWhileBLoads)
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun returningToTheSameFeedRejectsItsEarlierResponse() = runTest {
        val oldResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        var callsToA = 0
        val fresh = loaded(showA, "fresh")
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            if (show == showA && ++callsToA == 1) {
                withContext(NonCancellable) { oldResponse.await() }
            } else Result.success(if (show == showA) fresh else loaded(showB))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        repository.openShow(showA)
        runCurrent()
        oldResponse.complete(Result.success(loaded(showA, "stale")))
        runCurrent()
        assertEquals(ShowState(show = showA, episodes = fresh.episodes), repository.show.value)
    }

    @Test
    fun openingAnotherShowCancelsThePreviousLoad() = runTest {
        val waiting = CompletableDeferred<Unit>()
        var cancelled = false
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            if (show == showA) {
                try {
                    waiting.await()
                } finally {
                    cancelled = true
                }
            }
            Result.success(loaded(show))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun lateCacheReadCannotPopulateAnotherShow() = runTest {
        val oldCache = CompletableDeferred<PodcastFeedCacheEntity?>()
        val newResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val cache = FakeCache().apply {
            readFeed = { url ->
                if (url == showA.feedUrl) withContext(NonCancellable) { oldCache.await() } else null
            }
        }
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), cache) { show ->
            if (show == showB) newResponse.await() else Result.success(loaded(showA))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        oldCache.complete(cached(showA))
        runCurrent()
        val stateWhileBLoads = repository.show.value
        newResponse.complete(Result.success(loaded(showB)))
        runCurrent()
        assertEquals(ShowState(show = showB, loading = true), stateWhileBLoads)
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun currentFeedStillShowsCachedEpisodesWhenNetworkFails() = runTest {
        val response = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val cache = FakeCache().apply { readFeed = { cached(showA) } }
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), cache) { response.await() }
        repository.openShow(showA)
        runCurrent()
        assertEquals(loadedState(showA).copy(loading = true), repository.show.value)

        response.complete(Result.failure(IOException("offline")))
        runCurrent()
        assertEquals(loadedState(showA), repository.show.value)
    }

    @Test
    fun currentFeedFailureWithoutCacheIsStillReported() = runTest {
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) {
            Result.failure(IOException("offline"))
        }
        repository.openShow(showA)
        runCurrent()
        assertEquals("offline", repository.show.value.error)
        assertEquals(showA, repository.show.value.show)
        assertFalse(repository.show.value.loading)
    }

    @Test
    fun emptyNextPagePublishesExhausted() = runTest {
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache(), FakeGateway())
        repository.nextPage()
        runCurrent()
        assertEquals(
            PodcastDirectoryState(
                query = PodcastQuery.Top(),
                shows = emptyList(),
                loading = false,
                exhausted = true,
                error = null,
            ),
            repository.directory.value,
        )
    }

    @Test
    fun primeTopMergesFirstPageAndSnapshotsIt() = runTest {
        val ids = (1..35).map { "id-$it" }
        val gateway = FakeGateway(
            onChartIds = { ids },
            onLookup = { wanted -> wanted.map { show(it.removePrefix("id-").toInt()) } },
        )
        val cache = FakeCache()
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), cache, gateway)
        repository.load(PodcastQuery.Top(), reset = true)
        runCurrent()
        val state = repository.directory.value
        assertEquals((1..30).map { "id-$it" }, state.shows.map { it.id })
        assertFalse(state.loading)
        assertFalse(state.exhausted)
        assertEquals(null, state.error)
        assertEquals(1, cache.kvPuts.size)
        assertEquals("podcasts:top:all", cache.kvPuts.single().key)
        val snapshotted = Http.json.decodeFromString<List<PodcastShow>>(cache.kvPuts.single().json)
        assertEquals(30, snapshotted.size)
    }

    @Test
    fun secondPageDedupsByFeedUrl() = runTest {
        val ids = (1..35).map { "id-$it" }
        val gateway = FakeGateway(
            onChartIds = { ids },
            onLookup = { wanted -> wanted.map { show(it.removePrefix("id-").toInt()) } },
        )
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache(), gateway)
        repository.load(PodcastQuery.Top(), reset = true)
        runCurrent()
        gateway.onLookup = { wanted -> wanted.map { show(it.removePrefix("id-").toInt()) } + show(1) }
        repository.nextPage()
        runCurrent()
        val state = repository.directory.value
        assertEquals(35, state.shows.size)
        assertEquals("id-35", state.shows.last().id)
        assertEquals(35, state.shows.map { it.feedUrl }.toSet().size)
        assertFalse(state.loading)
    }

    @Test
    fun resetCancelsInFlightPrime() = runTest {
        val primeGate = CompletableDeferred<List<String>>()
        val searchGate = CompletableDeferred<List<PodcastShow>>()
        val gateway = FakeGateway(
            onChartIds = { primeGate.await() },
            onSearch = { searchGate.await() },
        )
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache(), gateway)
        repository.load(PodcastQuery.Top(), reset = true)
        runCurrent()
        repository.load(PodcastQuery.Search("x"), reset = true)
        runCurrent()
        searchGate.complete(listOf(show(7)))
        runCurrent()
        assertEquals(PodcastQuery.Search("x"), repository.directory.value.query)
        assertEquals(listOf("id-7"), repository.directory.value.shows.map { it.id })
        // The cancelled prime resolves late and must leave the newer query alone.
        primeGate.complete(listOf("id-1"))
        runCurrent()
        assertEquals(PodcastQuery.Search("x"), repository.directory.value.query)
        assertEquals(listOf("id-7"), repository.directory.value.shows.map { it.id })
    }

    @Test
    fun primeTimeoutSurfacesError() = runTest {
        val gateway = FakeGateway(onChartIds = { awaitCancellation() })
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache(), gateway)
        repository.load(PodcastQuery.Top(), reset = true)
        runCurrent()
        assertTrue(repository.directory.value.loading)
        advanceTimeBy(30_001)
        runCurrent()
        val state = repository.directory.value
        assertFalse(state.loading)
        assertEquals("podcast directory timed out", state.error)
        assertTrue(state.shows.isEmpty())
    }

    @Test
    fun resetRestoresSnapshotWhilePrimeLoads() = runTest {
        val old = listOf(show(1), show(2))
        val cache = FakeCache()
        cache.kv["podcasts:top:all"] = KvCacheEntity(
            key = "podcasts:top:all",
            json = Http.json.encodeToString(old),
            savedAt = 0L,
        )
        val primeGate = CompletableDeferred<List<String>>()
        val gateway = FakeGateway(
            onChartIds = { primeGate.await() },
            onLookup = { wanted -> wanted.map { show(it.removePrefix("id-").toInt()) } },
        )
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), cache, gateway)
        repository.load(PodcastQuery.Top(), reset = true)
        runCurrent()
        // Stale snapshot on screen, footer still loading behind it.
        assertEquals(listOf("id-1", "id-2"), repository.directory.value.shows.map { it.id })
        assertTrue(repository.directory.value.loading)
        primeGate.complete(listOf("id-9"))
        runCurrent()
        val live = repository.directory.value
        assertEquals(listOf("id-9"), live.shows.map { it.id })
        assertFalse(live.loading)
    }

    @Test
    fun searchPrimeUsesSearchGateway() = runTest {
        val gateway = FakeGateway(onSearch = { listOf(show(3)) })
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache(), gateway)
        repository.load(PodcastQuery.Search("x"), reset = true)
        runCurrent()
        assertEquals(listOf("id-3"), repository.directory.value.shows.map { it.id })
        assertFalse(repository.directory.value.loading)
    }

    @Test
    fun categoryPrimeUsesGenreGateway() = runTest {
        val gateway = FakeGateway(onByGenre = { listOf(show(4)) })
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache(), gateway)
        repository.load(PodcastQuery.Category(PodcastGenre(1, "Tech")), reset = true)
        runCurrent()
        assertEquals(listOf("id-4"), repository.directory.value.shows.map { it.id })
        assertFalse(repository.directory.value.loading)
    }

    private fun show(i: Int) = PodcastShow("id-$i", "Show $i", "https://example.com/$i.xml")

    private fun loaded(show: PodcastShow, suffix: String = "episode") = PodcastFeed.Loaded(
        show,
        listOf(PodcastEpisode("${show.id}:$suffix", suffix, "https://example.com/${show.id}/$suffix.mp3")),
    )

    private fun loadedState(show: PodcastShow) = ShowState(show, loaded(show).episodes)

    private fun cached(show: PodcastShow) = PodcastFeedCacheEntity(
        feedUrl = show.feedUrl,
        showJson = Http.json.encodeToString(show),
        episodesJson = Http.json.encodeToString(loaded(show).episodes),
        savedAt = System.currentTimeMillis(),
    )

    private class FakeCache : CacheDao {
        var readFeed: suspend (String) -> PodcastFeedCacheEntity? = { null }
        override suspend fun getFeed(feedUrl: String) = readFeed(feedUrl)
        override suspend fun putFeed(row: PodcastFeedCacheEntity) = Unit
        override suspend fun pruneFeedsOlderThan(cutoff: Long) = 0
        val kv = mutableMapOf<String, KvCacheEntity>()
        val kvPuts = mutableListOf<KvCacheEntity>()
        override suspend fun get(key: String): KvCacheEntity? = kv[key]
        override suspend fun put(row: KvCacheEntity) {
            kv[row.key] = row
            kvPuts += row
        }
        override suspend fun pruneOlderThan(cutoff: Long) = 0
        override suspend fun trimPrefix(prefix: String, keep: Int) = 0
    }

    private class FakeGateway(
        var onChartIds: suspend (String) -> List<String> = { emptyList() },
        var onGenreChartIds: suspend (String, Int) -> List<String> = { _, _ -> emptyList() },
        var onSearch: suspend (String) -> List<PodcastShow> = { emptyList() },
        var onByGenre: suspend (PodcastGenre) -> List<PodcastShow> = { emptyList() },
        var onLookup: suspend (List<String>) -> List<PodcastShow> = { emptyList() },
    ) : PodcastDirectoryGateway {
        override suspend fun chartIds(country: String) = onChartIds(country)
        override suspend fun genreChartIds(country: String, genreId: Int) = onGenreChartIds(country, genreId)
        override suspend fun search(text: String) = onSearch(text)
        override suspend fun byGenre(genre: PodcastGenre) = onByGenre(genre)
        override suspend fun lookup(ids: List<String>) = onLookup(ids)
    }

    private class FakePodcasts : PodcastDao {
        override fun subscriptions() = flowOf(emptyList<PodcastSubscriptionEntity>())
        override fun allProgress() = flowOf(emptyList<EpisodeProgressEntity>())
        override suspend fun isSubscribed(feedUrl: String) = false
        override suspend fun nextTopPosition() = 0
        override suspend fun subscribe(row: PodcastSubscriptionEntity) = Unit
        override suspend fun unsubscribe(feedUrl: String) = Unit
        val savedRows = mutableMapOf<String, EpisodeProgressEntity>()
        override suspend fun progress(url: String): EpisodeProgressEntity? = savedRows[url]
        override suspend fun saveProgress(row: EpisodeProgressEntity) {
            savedRows[row.url] = row
        }
        override suspend fun saveProgress(rows: List<EpisodeProgressEntity>) {
            rows.forEach { savedRows[it.url] = it }
        }
        override suspend fun clearProgress(url: String) {
            savedRows.remove(url)
        }
        override suspend fun clearProgress(urls: List<String>) {
            urls.forEach { savedRows.remove(it) }
        }
        override suspend fun fileUrls(): List<String> =
            savedRows.keys.filter { it.startsWith("file://") }
        override suspend fun rekey(oldUrl: String, newUrl: String) {
            savedRows.remove(oldUrl)?.let { savedRows[newUrl] = it.copy(url = newUrl) }
        }
    }

    @Test
    fun markAllCompletedWritesEveryEpisodeOnce() = runTest {
        val dao = FakePodcasts()
        val repository = PodcastRepository(backgroundScope, dao, FakeCache())
        repository.markAllCompleted(
            listOf(
                station("a", 120_000L),
                station("a", 120_000L),
                station("b", 0L),
            ),
        )
        assertEquals(2, dao.savedRows.size)
        assertEquals(120_000L, dao.savedRows["a"]?.positionMs)
        assertTrue(dao.savedRows["a"]?.completed == true)
        assertEquals(0L, dao.savedRows["b"]?.positionMs)
        assertTrue(dao.savedRows["b"]?.completed == true)
    }

    @Test
    fun clearAllProgressRemovesEveryEpisodeOnce() = runTest {
        val dao = FakePodcasts()
        val repository = PodcastRepository(backgroundScope, dao, FakeCache())
        val stations = listOf(station("a"), station("b"))
        repository.markAllCompleted(stations)
        repository.clearAllProgress(stations + station("a"))
        assertTrue(dao.savedRows.isEmpty())
    }

    @Test
    fun saveDurationCreatesARowWithoutPosition() = runTest {
        val dao = FakePodcasts()
        val repository = PodcastRepository(backgroundScope, dao, FakeCache())
        repository.saveDurationMs("https://example.com/t.mp3", 200_000L)
        assertEquals(200_000L, dao.savedRows["https://example.com/t.mp3"]?.durationMs)
        assertEquals(0L, dao.savedRows["https://example.com/t.mp3"]?.positionMs)
        assertFalse(dao.savedRows["https://example.com/t.mp3"]?.completed == true)
    }

    @Test
    fun saveDurationKeepsTheSavedPosition() = runTest {
        val dao = FakePodcasts()
        dao.savedRows["u"] = EpisodeProgressEntity("u", 60_000L, 0L, false, 0L)
        val repository = PodcastRepository(backgroundScope, dao, FakeCache())
        repository.saveDurationMs("u", 200_000L)
        assertEquals(200_000L, dao.savedRows["u"]?.durationMs)
        assertEquals(60_000L, dao.savedRows["u"]?.positionMs)
    }

    @Test
    fun saveDurationIgnoresGarbage() = runTest {
        val dao = FakePodcasts()
        val repository = PodcastRepository(backgroundScope, dao, FakeCache())
        repository.saveDurationMs("u", 0L)
        assertTrue(dao.savedRows.isEmpty())
    }
}
