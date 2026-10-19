package stream.kleeamp.mobile.radio

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import stream.kleeamp.mobile.db.KleeampDatabase
import stream.kleeamp.mobile.db.CacheDao
import stream.kleeamp.mobile.db.KvCacheEntity
import stream.kleeamp.mobile.net.Http
import java.io.IOException
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.net.retryFetch
import stream.kleeamp.mobile.play.QueueContinuation
import stream.kleeamp.mobile.prefs.Prefs

/** How the directory list is currently ordered or filtered. */
sealed interface DirectoryQuery {
    data object TopVoted : DirectoryQuery
    data object Trending : DirectoryQuery
    data class Search(val text: String) : DirectoryQuery
    data class Tag(val tag: String) : DirectoryQuery
    data class Country(val code: String, val countryName: String) : DirectoryQuery

    val label: String
        get() = when (this) {
            TopVoted -> "top"
            Trending -> "trending"
            is Search -> "\"$text\""
            is Tag -> "#$tag"
            is Country -> countryName.lowercase()
        }

    /**
     * Stable key persisted with the player queue so a restored queue can keep
     * paging the same directory query after a restart. The separator cannot
     * occur in a real search/tag/country value.
     */
    val originKey: String
        get() = when (this) {
            TopVoted -> "top"
            Trending -> "trending"
            is Search -> "search\u0000$text"
            is Tag -> "tag\u0000$tag"
            is Country -> "country\u0000$code"
        }

    companion object {
        /** Parses an [originKey] written by an earlier process, or null. */
        fun fromOriginKey(key: String): DirectoryQuery? {
            val cut = key.indexOf('\u0000')
            if (cut < 0) {
                return when (key) {
                    "top" -> TopVoted
                    "trending" -> Trending
                    else -> null
                }
            }
            val kind = key.substring(0, cut)
            val value = key.substring(cut + 1)
            if (value.isEmpty()) return null
            return when (kind) {
                "search" -> Search(value)
                "tag" -> Tag(value)
                "country" -> Country(value.uppercase(), value)
                else -> null
            }
        }
    }
}

data class DirectoryState(
    val query: DirectoryQuery = DirectoryQuery.TopVoted,
    val stations: List<Station> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
)

/**
 * Holds the two catalogues side by side: cliamp's own channels, which are
 * small enough to keep resident, and the directory, which is paged in 60 at a
 * time and never fully materialised.
 */
class RadioRepository(
    context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val cache: CacheDao = KleeampDatabase.get(context).cache()

    private val _cliamp = MutableStateFlow(CliampRadio.builtin)
    val cliamp: StateFlow<List<Station>> = _cliamp.asStateFlow()

    private val _cliampError = MutableStateFlow<String?>(null)
    val cliampError: StateFlow<String?> = _cliampError.asStateFlow()

    /**
     * cliamp radio channels with their song counts, for opening a channel as
     * its track list. Restored from the last snapshot on launch so the counts
     * paint instantly, then replaced whenever the live list answers.
     */
    private val _cliampChannels = MutableStateFlow<List<CliampChannels.Channel>>(emptyList())
    val cliampChannels: StateFlow<List<CliampChannels.Channel>> = _cliampChannels.asStateFlow()

    private val channelsKey = "cliamp:channels"
    private fun tracksKey(channelId: String) = "cliamp:tracks:$channelId"

    /**
     * Channel track lists loaded so far, across every opened channel. Search
     * reads this; the channel pages keep their own copies for ordering.
     */
    private val _cliampChannelTracks = MutableStateFlow<List<Station>>(emptyList())
    val cliampChannelTracks: StateFlow<List<Station>> = _cliampChannelTracks.asStateFlow()

    /**
     * One channel's tracks: memory first, then the disk snapshot, then the
     * network. A live answer replaces both caches; a failure keeps whatever
     * the snapshot had, so an opened channel still opens offline.
     */
    suspend fun cliampTracks(channelId: String, refresh: Boolean = false): List<Station> {
        val channel = _cliampChannels.value.firstOrNull { it.id == channelId } ?: return emptyList()
        if (!channel.hasTracks) return emptyList()
        if (!refresh) {
            CliampChannels.cachedTracksFor(channelId)?.let { return it }
            val disk = readTracksSnapshot(channelId)
            if (disk.isNotEmpty()) {
                CliampChannels.seedTracks(channelId, disk)
                _cliampChannelTracks.value = CliampChannels.cachedTracks()
                return disk
            }
        }
        val live = runCatching { CliampChannels.fetchTracks(channel, refresh) }.getOrDefault(emptyList())
        if (live.isNotEmpty()) {
            writeTracksSnapshot(channelId, live)
            _cliampChannelTracks.value = CliampChannels.cachedTracks()
            return live
        }
        // Forced refresh against a dead network: fall back to the snapshot
        // rather than emptying a list the user already had.
        if (refresh) {
            val disk = readTracksSnapshot(channelId)
            if (disk.isNotEmpty()) {
                CliampChannels.seedTracks(channelId, disk)
                _cliampChannelTracks.value = CliampChannels.cachedTracks()
                return disk
            }
        }
        return emptyList()
    }

    /** Live listener figures for the cliamp channels; null until fetched. */
    private val _cliampStats = MutableStateFlow<CliampStats?>(null)
    val cliampStats: StateFlow<CliampStats?> = _cliampStats.asStateFlow()

    /** Refreshes the listener figures; failures keep the last good value. */
    fun refreshCliampStats() {
        scope.launch {
            fetchCliampStats()?.let {
                _cliampStats.value = it
                snapshotMeta("meta:cliamp-stats", it)
            }
        }
    }

    private val _directory = MutableStateFlow(DirectoryState())
    val directory: StateFlow<DirectoryState> = _directory.asStateFlow()

    private val _directoryStats = MutableStateFlow<DirectoryStats?>(null)
    val directoryStats: StateFlow<DirectoryStats?> = _directoryStats.asStateFlow()

    private val _tags = MutableStateFlow<List<NameCount>>(emptyList())
    val tags: StateFlow<List<NameCount>> = _tags.asStateFlow()

    private val _countries = MutableStateFlow<List<CountryCount>>(emptyList())
    val countries: StateFlow<List<CountryCount>> = _countries.asStateFlow()

    private val pageLock = Mutex()
    private val pageSize = 60
    private var loadJob: Job? = null

    /**
     * Snapshot rows older than this are never read again; pruned on launch.
     * Search snapshots grow one row per query text and cap separately below.
     */
    private val cacheTtlMs = 14L * 24 * 60 * 60 * 1000
    private val maxSearchSnapshots = 100

    /**
     * Hard bound on one directory load, retries included. Per-call timeouts
     * cannot bound a trickling connection (every byte resets the read
     * clock), so without this a dead network holds [pageLock] with
     * `loading = true` for minutes: the footer spins, taps queue behind the
     * hung fetch, and retry does nothing until it finally lets go.
     */
    private val directoryTimeoutMs = 30_000L

    /**
     * Tag chips when neither the network nor a snapshot has any: the common
     * genres, so filtering stays usable offline. Never snapshotted - the
     * live list replaces these on the next successful fetch.
     */
    private val builtinTags = listOf(
        "pop", "rock", "jazz", "classical", "electronic", "hip-hop",
        "news", "talk", "sports", "chill", "dance", "80s",
    ).map { NameCount(it, 0) }

    fun bootstrap() {
        refreshCliamp()
        // Channel counts before the network: the stations rows and the
        // channel headers read the same flow, so one restore covers both.
        scope.launch { restoreCliampChannels() }
        // Snapshot hygiene, once per launch: rows past their TTL are never
        // read, and search snapshots would otherwise grow one row per query.
        scope.launch {
            runCatching {
                cache.pruneOlderThan(System.currentTimeMillis() - cacheTtlMs)
                cache.trimPrefix("stations:search:", maxSearchSnapshots)
            }
        }
        // Meta first: instant chips and stats from the last snapshot, then the
        // live fetches replace them. Same snapshot pattern as the directories.
        // (refreshCliamp above already re-fetches the listener figures, so a
        // cache hit paints instantly and the live answer replaces it.)
        scope.launch { restoreMeta() }
        scope.launch {
            _directoryStats.value = retryFetch { RadioBrowser.stats() }
            _directoryStats.value?.let { snapshotMeta("meta:stats", it) }
        }
        scope.launch {
            val fetched = runCatching { retryFetch { RadioBrowser.topTags(60) } }.getOrNull().orEmpty()
            if (fetched.isNotEmpty()) {
                _tags.value = fetched
                snapshotMeta("meta:tags", fetched)
            } else if (_tags.value.isEmpty()) {
                // Snapshot restore (running alongside) may have filled these;
                // only fall back when nothing did, so offline still has chips.
                _tags.value = builtinTags
            }
        }
        scope.launch {
            _countries.value = retryFetch { RadioBrowser.topCountries() }
            _countries.value.takeIf { it.isNotEmpty() }?.let { snapshotMeta("meta:countries", it) }
        }
        loadDirectory(DirectoryQuery.TopVoted, reset = true)
    }

    fun refreshCliamp() {
        scope.launch {
            refreshCliampStats()
            // cliamp is its own service; a silent drop used to abort this
            // coroutine uncaught (the loop crashed the app) or fell back to
            // builtins with no explanation. Keep the last good list on screen
            // and let the section say what happened instead.
            _cliampError.value = null
            runCatching { retryFetch { CliampRadio.fetchStations() } }
                .onSuccess {
                    _cliamp.value = it
                    prefetchCovers(it)
                }
                .onFailure { _cliampError.value = it.message ?: "cliamp radio unreachable" }
        }
        // Channel song counts ride alongside: best-effort, and stations keep
        // working when it fails. A live answer replaces the snapshot outright.
        scope.launch {
            runCatching { retryFetch { CliampChannels.fetchChannels() } }
                .onSuccess {
                    _cliampChannels.value = it
                    snapshotChannels(it)
                }
        }
    }

    /** The last channel list, so counts paint before the network answers. */
    private suspend fun restoreCliampChannels() {
        val row = cache.get(channelsKey) ?: return
        val cached = runCatching { Http.json.decodeFromString<List<CliampChannels.Channel>>(row.json) }
            .getOrNull()?.filter { it.id.isNotBlank() } ?: return
        if (cached.isNotEmpty() && _cliampChannels.value.isEmpty()) {
            _cliampChannels.value = cached
        }
    }

    private fun snapshotChannels(channels: List<CliampChannels.Channel>) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = channelsKey,
                        json = Http.json.encodeToString(channels),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    private suspend fun readTracksSnapshot(channelId: String): List<Station> {
        val row = cache.get(tracksKey(channelId)) ?: return emptyList()
        return runCatching { Http.json.decodeFromString<List<Station>>(row.json) }.getOrDefault(emptyList())
    }

    private fun writeTracksSnapshot(channelId: String, tracks: List<Station>) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = tracksKey(channelId),
                        json = Http.json.encodeToString(tracks),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    // Directory load state machine across queries and mirrors.
    @Suppress("CyclomaticComplexMethod")
    fun loadDirectory(query: DirectoryQuery, reset: Boolean) {
        // A new query supersedes whatever is still loading: cancel it so a
        // retap never queues minutes behind a hung fetch.
        if (reset) loadJob?.cancel()
        loadJob = scope.launch {
            pageLock.withLock {
                val cur = _directory.value
                if (!reset && (cur.loading || cur.exhausted || cur.error != null)) return@withLock
                val offset = if (reset) 0 else cur.stations.size
                // A reset never empties what is on screen: the old rows stay
                // put (and keep their scroll position) while the new query
                // loads, then the live page replaces them. Emptying first is
                // what made every filter tap flash and jump.
                _directory.value =
                    if (reset) DirectoryState(query = query, loading = true, stations = cur.stations)
                    else cur.copy(loading = true, error = null)

                // A reset empties nothing the user already has on screen: fill
                // the list from the last snapshot of this query before the
                // network answers, then let the live page replace it.
                if (reset) restore(query)

                val page = runCatching { fetchPage(query, offset) }
                // A superseding tap cancelled this load: leave the state alone,
                // the newer load owns the UI now.
                if (page.exceptionOrNull() is CancellationException) return@withLock

                _directory.value = page.fold(
                    onSuccess = { list ->
                        val base = if (reset) emptyList() else _directory.value.stations
                        val seen = base.mapTo(HashSet()) { it.url }
                        val merged = base + list.filter { seen.add(it.url) }
                        DirectoryState(
                            query = query,
                            stations = merged,
                            loading = false,
                            // Only an empty page is the end. A short page is
                            // usually a slow server trimming the answer, not the
                            // last of the catalogue, and calling it exhausted
                            // froze the directory mid-list.
                            exhausted = list.isEmpty(),
                        ).also { state ->
                            _directory.value = state
                            // A first page is the whole "open the tab" moment;
                            // remember it so a cold start can render it instantly.
                            if (reset) snapshot(query, merged)
                            // Warm art for the first screenful while it is on
                            // screen: rows then hit memory/disk instead of
                            // queueing fresh fetches behind each other.
                            if (reset) prefetchCovers(merged)
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

    /** One page of [query] at [offset], retried inside the directory timeout. */
    internal suspend fun fetchPage(query: DirectoryQuery, offset: Int): List<Station> =
        withTimeoutOrNull(directoryTimeoutMs) {
            retryFetch {
                when (query) {
                    DirectoryQuery.TopVoted -> RadioBrowser.topVoted(offset, pageSize)
                    DirectoryQuery.Trending -> RadioBrowser.trending(offset, pageSize)
                    is DirectoryQuery.Search -> RadioBrowser.searchByName(query.text, offset, pageSize)
                    is DirectoryQuery.Tag -> RadioBrowser.byTag(query.tag, offset, pageSize)
                    is DirectoryQuery.Country -> RadioBrowser.byCountryCode(query.code, offset, pageSize)
                }
            }
        } ?: throw IOException("directory timed out")

    /** The last first-page snapshot of [query], or nothing the first time. */
    private suspend fun restore(query: DirectoryQuery) {
        val row = cache.get(keyOf(query)) ?: return
        val cached = runCatching { Http.json.decodeFromString<List<Station>>(row.json) }.getOrNull() ?: return
        val cur = _directory.value
        // Only a page that is still waiting counts. When a fetch already
        // landed (or the user moved on), the live list wins over a snapshot;
        // loading stays true so the footer still says "loading more…".
        if (cur.query == query && cur.stations.isEmpty()) {
            _directory.value = cur.copy(stations = cached)
        }
    }

    /**
     * Warms small art for the first screenful once a list lands, so rows hit
     * memory/disk instead of queueing fresh fetches behind each other on a
     * cold list. Fire-and-forget on the shared pool; a row that beats its
     * prefetch just does the same fetch it would have anyway.
     */
    private fun prefetchCovers(stations: List<Station>) {
        val head = stations.take(24)
        if (head.isEmpty()) return
        scope.launch {
            runCatching {
                head.map { async { StationArtSource.bitmapForSmall(it) } }.awaitAll()
            }
        }
    }

    private fun snapshot(query: DirectoryQuery, stations: List<Station>) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = keyOf(query),
                        json = Http.json.encodeToString(stations),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /** Last-good chips and stats, so cold tabs render before the network. */
    private suspend fun restoreMeta() {
        cache.get("meta:stats")?.let { row ->
            runCatching { Http.json.decodeFromString<DirectoryStats>(row.json) }.getOrNull()
                ?.let { if (_directoryStats.value == null) _directoryStats.value = it }
        }
        cache.get("meta:cliamp-stats")?.let { row ->
            runCatching { Http.json.decodeFromString<CliampStats>(row.json) }.getOrNull()
                ?.let { if (_cliampStats.value == null) _cliampStats.value = it }
        }
        cache.get("meta:tags")?.let { row ->
            runCatching { Http.json.decodeFromString<List<NameCount>>(row.json) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (_tags.value.isEmpty()) _tags.value = it }
        }
        cache.get("meta:countries")?.let { row ->
            runCatching { Http.json.decodeFromString<List<CountryCount>>(row.json) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (_countries.value.isEmpty()) _countries.value = it }
        }
    }

    private inline fun <reified T> snapshotMeta(key: String, value: T) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = key,
                        json = Http.json.encodeToString(value),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    private fun keyOf(query: DirectoryQuery): String = when (query) {
        DirectoryQuery.TopVoted -> "stations:top"
        DirectoryQuery.Trending -> "stations:trending"
        is DirectoryQuery.Search -> "stations:search:${query.text.trim().lowercase()}"
        is DirectoryQuery.Tag -> "stations:tag:${query.tag.trim().lowercase()}"
        is DirectoryQuery.Country -> "stations:country:${query.code.trim().lowercase()}"
    }

    fun nextPage() = loadDirectory(_directory.value.query, reset = false)

    fun reportPlay(station: Station) {
        if (station.source == StationSource.Directory) {
            scope.launch { RadioBrowser.reportClick(station.uuid) }
        }
        scope.launch { prefs.pushHistory(station) }
        scope.launch { prefs.setLastStation(station) }
    }

}

/**
 * Builds a player-queue continuation for [query] starting at [offset]. The
 * queue owns the offset, not the directory list, so manual queue edits (or a
 * queue restored before the directory finished loading) never skip or repeat
 * a page.
 */
fun RadioRepository.queueContinuation(query: DirectoryQuery, offset: Int): QueueContinuation =
    QueueContinuation(query.originKey, offset) { at -> fetchPage(query, at) }

/** Rebuilds a paged queue source from an origin persisted before a restart. */
fun RadioRepository.queueContinuation(origin: String, offset: Int): QueueContinuation? =
    DirectoryQuery.fromOriginKey(origin)?.let { queueContinuation(it, offset) }
