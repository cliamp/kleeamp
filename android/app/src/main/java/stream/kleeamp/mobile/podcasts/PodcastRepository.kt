package stream.kleeamp.mobile.podcasts

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import stream.kleeamp.mobile.db.CacheDao
import stream.kleeamp.mobile.db.KleeampDatabase
import stream.kleeamp.mobile.db.EpisodeProgressEntity
import stream.kleeamp.mobile.db.KvCacheEntity
import stream.kleeamp.mobile.db.PodcastDao
import stream.kleeamp.mobile.db.PodcastFeedCacheEntity
import stream.kleeamp.mobile.db.toEntity
import stream.kleeamp.mobile.net.Http
import java.io.IOException
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.net.retryFetch

private const val PAGE = 30

/** How the podcast directory is currently ordered or filtered. */
sealed interface PodcastQuery {
    /** An empty [country] is "all countries", resolved as Apple's global chart. */
    data class Top(val country: String = "") : PodcastQuery
    data class Search(val text: String) : PodcastQuery
    data class Category(val genre: PodcastGenre) : PodcastQuery

    val label: String
        get() = when (this) {
            is Top -> "top shows"
            is Search -> "\"$text\""
            is Category -> genre.name.lowercase()
        }
}

data class PodcastDirectoryState(
    val query: PodcastQuery = PodcastQuery.Top(),
    val shows: List<PodcastShow> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
)

/** One show's feed, as the episode list sees it. */
data class ShowState(
    val show: PodcastShow? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The directory's network edge, seam-tested through fakes. The default
 * delegates to [PodcastDirectory]; behaviour is identical either way.
 */
interface PodcastDirectoryGateway {
    suspend fun chartIds(country: String): List<String>
    suspend fun genreChartIds(country: String, genreId: Int): List<String>
    suspend fun search(text: String): List<PodcastShow>
    suspend fun byGenre(genre: PodcastGenre): List<PodcastShow>
    suspend fun lookup(ids: List<String>): List<PodcastShow>
}

private object RealPodcastDirectoryGateway : PodcastDirectoryGateway {
    override suspend fun chartIds(country: String): List<String> =
        PodcastDirectory.chartIds(country)

    override suspend fun genreChartIds(country: String, genreId: Int): List<String> =
        PodcastDirectory.genreChartIds(country, genreId)

    override suspend fun search(text: String): List<PodcastShow> =
        PodcastDirectory.search(text)

    override suspend fun byGenre(genre: PodcastGenre): List<PodcastShow> =
        PodcastDirectory.byGenre(genre)

    override suspend fun lookup(ids: List<String>): List<PodcastShow> =
        PodcastDirectory.lookup(ids)
}

/**
 * Podcasts, held the way [RadioRepository] holds radio: a small resident list you
 * own (subscriptions, where radio has cliamp's channels and favourites) beside
 * a large directory that is paged and never fully materialised.
 *
 * The paging differs, and it has to. Radio-browser takes an offset, so a page
 * is a request. Apple's search takes neither an offset nor more than a hundred
 * results, so a query is one request and a page is a slice of what came back;
 * the chart is the other way round, arriving as bare ids that need resolving,
 * so there a page is a [PodcastDirectory.lookup] of the next [PAGE] of them.
 * Every single chart is capped small, so once the global one is spent the
 * queue moves on to each genre's chart - the directory keeps loading the way
 * the radio one does, with Apple's famous shows up front. Either way the
 * screen sees the same growing list and the same exhausted flag.
 */
class PodcastRepository internal constructor(
    private val scope: CoroutineScope,
    private val dao: PodcastDao,
    private val cache: CacheDao,
    private val directoryGateway: PodcastDirectoryGateway = RealPodcastDirectoryGateway,
    private val loadFeed: suspend (PodcastShow) -> Result<PodcastFeed.Loaded> = PodcastFeed::load,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        dao = KleeampDatabase.get(context).podcasts(),
        cache = KleeampDatabase.get(context).cache(),
    )

    private val _directory = MutableStateFlow(PodcastDirectoryState())
    val directory: StateFlow<PodcastDirectoryState> = _directory.asStateFlow()

    private val _show = MutableStateFlow(ShowState())
    val show: StateFlow<ShowState> = _show.asStateFlow()

    // openShow is called from the UI, while appScope loads on Dispatchers.Default.
    // Selection and result publication must share a lock: checking the feed URL
    // alone misses A -> B -> A, and cancellation cannot stop every blocking read.
    private val showLock = Any()
    private var showRequest: Any? = null
    private var showJob: Job? = null

    // Subscriptions come straight from Room: a toggle or a feed add writes the
    // row and the invalidation tracker re-emits the list, so the subscribing
    // sections track the table without an in-memory copy to keep in sync.
    val subscriptions: Flow<List<PodcastShow>> =
        dao.subscriptions().map { rows -> rows.map { it.toShow() } }

    /** Every saved position, keyed by episode URL, for badging a list at once. */
    val progress: Flow<Map<String, EpisodeProgress>> =
        dao.allProgress().map { rows -> rows.associate { it.url to it.toProgress() } }

    private val pageLock = Mutex()
    private var loadJob: Job? = null

    /**
     * Hard bound on one directory load, retries included - the same trickle
     * protection the radio directory has. Per-call timeouts cannot bound a
     * trickling connection (every byte resets the read clock), so without
     * this a dead network holds [pageLock] with `loading = true` and the
     * footer spins until it finally lets go.
     */
    private val directoryTimeoutMs = 30_000L

/** Chart ids not yet resolved to shows, in chart order. */
private var chartCursor: List<String> = emptyList()

    /** The next charts, fetched lazily as the current one runs out. Any single
     * chart is capped, so the directory pages past the global chart into each
     * genre's to keep going the way the radio directory does. Each entry is a
     * fetch of that chart's ids, resolved a page at a time on arrival. */
    private val chartQueue = mutableListOf<suspend () -> List<String>>()

    /** Search or category results fetched but not yet shown. */
    private var pending: List<PodcastShow> = emptyList()

    fun bootstrap() {
        load(PodcastQuery.Top(), reset = true)
        // Feed rows past their TTL are never painted; drop them on launch.
        scope.launch {
            runCatching { cache.pruneFeedsOlderThan(System.currentTimeMillis() - FEED_TTL) }
        }
    }

    // Directory load pipeline; covered by PodcastRepositoryTest.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun load(query: PodcastQuery, reset: Boolean) {
        // A new query supersedes whatever is still loading: cancel it so a
        // retap never queues behind a hung fetch, mirroring radio loads.
        if (reset) loadJob?.cancel()
        loadJob = scope.launch {
            pageLock.withLock {
                val cur = _directory.value
                if (!reset && (cur.loading || cur.exhausted || cur.error != null)) return@withLock
                // Same stale-while-revalidate as radio: a reset keeps the old
                // rows on screen (and their scroll position) instead of
                // flashing empty while the new query loads.
                _directory.value =
                    if (reset) PodcastDirectoryState(query = query, loading = true, shows = cur.shows)
                    else cur.copy(loading = true, error = null)

                if (reset) {
                    chartCursor = emptyList()
                    pending = emptyList()
                    chartQueue.clear()
                    _directory.value = PodcastDirectoryState(query = query, loading = true, shows = cur.shows)
                    // Fill the screen from the last snapshot of this query while
                    // the network answers; the first live page replaces it.
                    restore(query)
                    val primed = runCatching {
                        withTimeoutOrNull(directoryTimeoutMs) {
                            retryFetch {
                                when (query) {
                                    is PodcastQuery.Top -> {
                                        chartCursor = directoryGateway.chartIds(
                                            country = query.country.ifEmpty { "us" },
                                        )
                                        PodcastDirectory.genres.forEach { g ->
                                            chartQueue.add {
                                                directoryGateway.genreChartIds(query.country.ifEmpty { "us" }, g.id)
                                            }
                                        }
                                    }
                                    is PodcastQuery.Search -> pending = directoryGateway.search(query.text)
                                    is PodcastQuery.Category -> pending = directoryGateway.byGenre(query.genre)
                                }
                            }
                        } ?: throw IOException("podcast directory timed out")
                    }
                    // A superseding tap cancelled this load: leave the state
                    // alone, the newer load owns the UI now.
                    if (primed.exceptionOrNull() is CancellationException) return@withLock
                    primed.exceptionOrNull()?.let { e ->
                        val cur = _directory.value
                        // A snapshot already on screen is better than an error;
                        // the failure can retry silently next visit.
                        _directory.value = if (cur.shows.isNotEmpty()) cur.copy(loading = false)
                        else PodcastDirectoryState(
                            query = query,
                            loading = false,
                            error = e.message ?: "directory unreachable",
                        )
                        return@withLock
                    }
                }

                val base = if (reset) emptyList() else _directory.value.shows
                val next = runCatching {
                    withTimeoutOrNull(directoryTimeoutMs) {
                        if (chartCursor.isNotEmpty() || chartQueue.isNotEmpty()) {
                            if (chartCursor.isEmpty()) {
                                // The current chart ran out; move on to the next
                                // (a genre chart) so the directory keeps going
                                // instead of quietly ending at Apple's cap.
                                chartCursor = retryFetch { chartQueue.removeAt(0)() }
                            }
                            val ids = chartCursor.take(PAGE)
                            val shows = retryFetch { directoryGateway.lookup(ids) }
                            // Only consume the ids once they have actually resolved:
                            // a page that fails (connection dropped, Apple rate
                            // limit) must be retried, not silently skipped.
                            chartCursor = chartCursor.drop(ids.size)
                            shows
                        } else {
                            val slice = pending.take(PAGE)
                            pending = pending.drop(slice.size)
                            slice
                        }
                    } ?: throw IOException("podcast directory timed out")
                }
                if (next.exceptionOrNull() is CancellationException) return@withLock

                _directory.value = next.fold(
                    onSuccess = { list ->
                        val seen = base.mapTo(HashSet()) { it.feedUrl }
                        val merged = base + list.filter { seen.add(it.feedUrl) }
                        PodcastDirectoryState(
                            query = query,
                            shows = merged,
                            loading = false,
                            exhausted = chartCursor.isEmpty() && chartQueue.isEmpty() && pending.isEmpty(),
                        ).also { state ->
                            _directory.value = state
                            if (reset) snapshot(query, merged)
                        }
                    },
                    onFailure = { e ->
                        _directory.value.copy(
                            loading = false,
                            error = e.message ?: "directory unreachable",
                        )
                    },
                )
            }
        }
    }

    fun nextPage() = load(_directory.value.query, reset = false)

    /** The last first-page snapshot of [query], or nothing the first time. */
    private suspend fun restore(query: PodcastQuery) {
        val row = cache.get(keyOf(query)) ?: return
        val cached = runCatching { Http.json.decodeFromString<List<PodcastShow>>(row.json) }.getOrNull() ?: return
        val cur = _directory.value
        // Only a page that is still waiting counts; a live list already
        // fetched (or another query) wins. Loading stays true so the footer
        // keeps saying "loading more…" until the fresh page lands.
        if (cur.query == query && cur.shows.isEmpty()) {
            _directory.value = cur.copy(shows = cached)
        }
    }

    private fun snapshot(query: PodcastQuery, shows: List<PodcastShow>) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = keyOf(query),
                        json = Http.json.encodeToString(shows),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    private fun keyOf(query: PodcastQuery): String = when (query) {
        is PodcastQuery.Top -> "podcasts:top:${query.country.ifEmpty { "all" }.trim().lowercase()}"
        is PodcastQuery.Search -> "podcasts:search:${query.text.trim().lowercase()}"
        is PodcastQuery.Category -> "podcasts:cat:${query.genre.id}"
    }

    /**
     * Loads [show]'s feed. The show is published before the fetch so the
     * episode screen can draw its header and artwork immediately, the same way
     * a tapped station is published before its stream resolves. A feed
     * snapshot from the last visit fills the list while the network answers, so
     * re-opening a show is instant instead of another multi-megabyte download.
     */
    fun openShow(show: PodcastShow, force: Boolean = false) {
        val job = synchronized(showLock) {
            val held = _show.value
            // Re-entering a show already in hand should be instant, unless
            // this is an explicit refresh which must re-fetch.
            if (!force && held.show?.feedUrl == show.feedUrl && held.episodes.isNotEmpty() && !held.loading) return
            val request = Any()
            showRequest = request
            showJob?.cancel()
            _show.value = ShowState(show = show, loading = true)
            // Assign before starting, including when the supplied scope runs inline.
            val nextJob = scope.launch(start = CoroutineStart.LAZY) {
                restoreFeed(show.feedUrl, request)
                currentCoroutineContext().ensureActive()
                val loaded = retryFetch { loadFeed(show) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    publishShow(request) { cur ->
                        // Keep this request's cached episodes on a network failure.
                        if (cur.episodes.isNotEmpty()) cur.copy(loading = false)
                        else ShowState(show = show, error = e.message ?: "feed unreachable")
                    }
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (!publishShow(request) {
                    ShowState(show = loaded.show, episodes = loaded.episodes)
                }) return@launch
                snapshotFeed(loaded.show, loaded.episodes)
                // A subscription keeps whatever the feed knows that the
                // directory did not, so the list stops looking half-filled.
                if (dao.isSubscribed(loaded.show.feedUrl)) {
                    dao.subscribe(loaded.show.toEntity(dao.nextTopPosition() + 1))
                }
            }
            showJob = nextJob
            nextJob
        }
        job.start()
    }

    // Cancellation is cooperative: another selection can arrive after ensureActive.
    // The identity check and write are atomic with openShow, so only its request wins.
    private fun publishShow(request: Any, update: (ShowState) -> ShowState): Boolean =
        synchronized(showLock) {
            if (showRequest !== request) return@synchronized false
            _show.value = update(_show.value)
            true
        }

    /** The last snapshot of [feedUrl]'s feed, or nothing when it has gone stale.
     * The caller refreshes regardless, so freshness only decides whether the
     * cached list is worth a first paint (a feed a month old still beats a
     * spinner, but why re-fetch it every open). */
    private suspend fun restoreFeed(feedUrl: String, request: Any) {
        val row = cache.getFeed(feedUrl) ?: return
        if (System.currentTimeMillis() - row.savedAt > FEED_TTL) return
        val cached = runCatching {
            val show = Http.json.decodeFromString<PodcastShow>(row.showJson)
            val episodes = Http.json.decodeFromString<List<PodcastEpisode>>(row.episodesJson)
            show to episodes
        }.getOrNull() ?: return
        currentCoroutineContext().ensureActive()
        publishShow(request) { cur ->
            if (cur.episodes.isEmpty()) cur.copy(show = cached.first, episodes = cached.second)
            else cur
        }
    }

    private fun snapshotFeed(show: PodcastShow, episodes: List<PodcastEpisode>) {
        episodeIndex = null
        scope.launch {
            runCatching {
                cache.putFeed(
                    PodcastFeedCacheEntity(
                        feedUrl = show.feedUrl,
                        showJson = Http.json.encodeToString(show),
                        episodesJson = Http.json.encodeToString(episodes),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    fun refreshShow() = _show.value.show?.let { openShow(it, force = true) }

    /** Returns the new state, so a row can toggle without re-reading. */
    suspend fun toggleSubscription(show: PodcastShow): Boolean {
        episodeIndex = null
        if (dao.isSubscribed(show.feedUrl)) {
            dao.unsubscribe(show.feedUrl)
            return false
        }
        dao.subscribe(show.toEntity(dao.nextTopPosition()))
        return true
    }

    /**
     * Every cached episode of every subscribed show, as (show, episode)
     * pairs. Feeds snapshot on open, so this is the offline episode index
     * search reads: flattened once, then held in memory until a snapshot or
     * a subscription changes it. A show never opened contributes nothing -
     * global episode search needs a server index that does not exist.
     */
    @Volatile private var episodeIndex: List<Pair<PodcastShow, PodcastEpisode>>? = null

    suspend fun subscribedEpisodes(): List<Pair<PodcastShow, PodcastEpisode>> {
        episodeIndex?.let { return it }
        val subs = subscriptions.first()
        if (subs.isEmpty()) return emptyList()
        val out = ArrayList<Pair<PodcastShow, PodcastEpisode>>()
        for (show in subs) {
            out += cachedEpisodes(show)
        }
        episodeIndex = out
        return out
    }

    /**
     * Every episode of [show]: the cached snapshot when opened before, else
     * the live feed. Backs the show menu's Download-all, so one tap queues
     * the whole show even when its feed was never opened.
     */
    suspend fun allEpisodes(show: PodcastShow): List<PodcastEpisode> {
        cachedEpisodes(show).map { it.second }.takeIf { it.isNotEmpty() }?.let { return it }
        return retryFetch { loadFeed(show) }.getOrNull()?.episodes ?: emptyList()
    }

    /** Cached episodes only; no network, for deciding a show-menu toggle. */
    suspend fun cachedEpisodesFor(show: PodcastShow): List<PodcastEpisode> =
        cachedEpisodes(show).map { it.second }

    /** One subscribed show's cached episodes, empty on any miss or bad payload. */
    private suspend fun cachedEpisodes(show: PodcastShow): List<Pair<PodcastShow, PodcastEpisode>> {
        val row = cache.getFeed(show.feedUrl) ?: return emptyList()
        val episodes = runCatching {
            Http.json.decodeFromString<List<PodcastEpisode>>(row.episodesJson)
        }.getOrDefault(emptyList())
        if (episodes.isEmpty()) return emptyList()
        val liveShow = runCatching {
            Http.json.decodeFromString<PodcastShow>(row.showJson)
        }.getOrDefault(show)
        return episodes.map { liveShow to it }
    }

    /**
     * Where playback should start. Anything within [NEAR_END] of the end counts
     * as finished and restarts from zero, so a completed episode replayed does
     * not open two seconds from its own credits. Keyed by URL, so local and
     * provider tracks resume exactly like episodes; live radio is not a track
     * and always starts at zero.
     */
    suspend fun resumePosition(station: Station): Long {
        val progress = if (station.isTrack) dao.progress(station.url) else null
        if (progress == null || progress.completed) return 0L
        if (progress.durationMs > 0 && progress.positionMs >= progress.durationMs - NEAR_END) return 0L
        return progress.positionMs.coerceAtLeast(0L)
    }

    /** True when the position counts as listened (within [NEAR_END] of the end). */
    suspend fun saveProgress(station: Station, positionMs: Long, durationMs: Long): Boolean {
        if (!station.isTrack) return false
        if (positionMs <= 0) return false
        val done = durationMs > 0 && positionMs >= durationMs - NEAR_END
        dao.saveProgress(
            EpisodeProgressEntity(
                url = station.url,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = done,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return done
    }

    suspend fun markCompleted(station: Station) {
        dao.saveProgress(
            EpisodeProgressEntity(
                url = station.url,
                positionMs = station.durationMs,
                durationMs = station.durationMs,
                completed = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Finish every episode of one show at once. The feed's episode list is the
     * caller's, so subscriptions that were never opened cost no fetch here.
     */
    suspend fun markAllCompleted(stations: List<Station>) {
        val now = System.currentTimeMillis()
        val rows = stations.asSequence()
            .filter { it.isTrack && it.url.isNotBlank() }
            .distinctBy { it.url }
            .map {
                EpisodeProgressEntity(
                    url = it.url,
                    positionMs = it.durationMs,
                    durationMs = it.durationMs,
                    completed = true,
                    updatedAt = now,
                )
            }
            .toList()
        if (rows.isNotEmpty()) dao.saveProgress(rows)
    }

    /** Clear the finished mark for every episode of one show at once. */
    suspend fun clearAllProgress(stations: List<Station>) {
        val urls = stations.asSequence()
            .filter { it.isTrack && it.url.isNotBlank() }
            .map { it.url }
            .distinct()
            .toList()
        if (urls.isNotEmpty()) dao.clearProgress(urls)
    }

    suspend fun clearProgress(station: Station) = dao.clearProgress(station.url)

    /**
     * A probed file length with no listening position behind it. Keeps any
     * saved position, so background duration fills never reset resume.
     */
    suspend fun saveDurationMs(url: String, durationMs: Long) {
        if (durationMs <= 0) return
        val now = System.currentTimeMillis()
        val cur = dao.progress(url)
        dao.saveProgress(
            cur?.copy(durationMs = durationMs, updatedAt = now)
                ?: EpisodeProgressEntity(url, 0L, durationMs, false, now)
        )
    }

    private companion object {
        /** Close enough to the end to call it listened. */
        const val NEAR_END = 30_000L

        /** How long a cached feed is worth showing while it refreshes. */
        const val FEED_TTL = 12L * 60 * 60 * 1000
    }
}
