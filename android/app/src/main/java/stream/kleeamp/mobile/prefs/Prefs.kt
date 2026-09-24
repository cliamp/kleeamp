package stream.kleeamp.mobile.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import stream.kleeamp.mobile.db.KleeampDatabase
import stream.kleeamp.mobile.db.CustomStationEntity
import stream.kleeamp.mobile.db.FavoriteEntity
import stream.kleeamp.mobile.db.HistoryEntity
import stream.kleeamp.mobile.db.toEntity
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.podcasts.DownloadEntry

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("cliamp")

/**
 * How a playlist's songs are ordered. Remembered per playlist so reopening a
 * list keeps the order the user set while adding songs; the "local songs"
 * smart playlist is stored under its own key ("local-songs").
 */
enum class PlaylistSort(val label: String) {
    Title("title"), Artist("artist"), Album("album"), RecentlyAdded("recently added")
}

/** Playlist members sorted by the chosen order; title is the stable tiebreak. */
fun sortedStations(songs: List<Station>, sort: PlaylistSort): List<Station> {
    val title = compareBy<Station> { it.name.lowercase() }
    return when (sort) {
        PlaylistSort.Title -> songs.sortedWith(title)
        PlaylistSort.Artist -> songs.sortedWith(compareBy<Station> { it.artist.lowercase() }.then(title))
        PlaylistSort.Album -> songs.sortedWith(compareBy<Station> { it.album.lowercase() }.then(title))
        PlaylistSort.RecentlyAdded ->
            songs.sortedWith(compareByDescending<Station> { it.dateAdded }.then(title))
    }
}

/**
 * Settings only.
 *
 * The scalars stay here because that is what a preferences store is good at:
 * a dozen values, read as flows, updated atomically. The collections that used
 * to live alongside them as JSON blobs are in SQLite now, because DataStore
 * has no partial write: adding one favourite rewrote the whole file, and a
 * track change did that four times over.
 */
class Prefs(private val context: Context) {

    private val db by lazy { KleeampDatabase.get(context) }

    // Grid/list layout flags live in memory first (so a toggle is instant on
    // the frame it is tapped) and only settle onto the DataStore file for
    // the restore-after-boot source, like the per-playlist sorts below.
    private val subsGridFlag = MutableStateFlow(true)
    private val podDirectoryGridFlag = MutableStateFlow(true)

    private object K {
        val palette = stringPreferencesKey("palette")           // system | oxide | dark | ...
        val haptics = booleanPreferencesKey("haptics")
        val visualizer = stringPreferencesKey("visualizer")     // spectrum | bars | ...
        val outputDevice = intPreferencesKey("output_device")   // -1 = system default
        val cellular = booleanPreferencesKey("cellular")
        val mono = booleanPreferencesKey("mono")
        val bufferSeconds = intPreferencesKey("buffer_seconds")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = stringPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val lastStation = stringPreferencesKey("last_station")
        val volume = floatPreferencesKey("volume")
        val speed = floatPreferencesKey("speed")
        val lyricsOffset = intPreferencesKey("lyrics_offset")
        val lbToken = stringPreferencesKey("lb_token")
        val autoResume = booleanPreferencesKey("auto_resume")
        val autoDownload = booleanPreferencesKey("auto_download")
        val resumeLocal = booleanPreferencesKey("resume_local")
        val wPlaying = booleanPreferencesKey("w_playing")
        val wTrack = stringPreferencesKey("w_track")
        val wSeekable = booleanPreferencesKey("w_seekable")
        val wDuration = longPreferencesKey("w_duration")
        val wNext = stringPreferencesKey("w_next")
        val wSource = stringPreferencesKey("w_source")
        val queue = stringPreferencesKey("queue")
        val queueIndex = intPreferencesKey("queue_index")
        val downloads = stringPreferencesKey("downloads")
        val customTheme = stringPreferencesKey("custom_theme")
        val playlistSorts = stringPreferencesKey("playlist_sorts")  // slug -> PlaylistSort.ordinal
        val subsGrid = booleanPreferencesKey("subs_grid")           // podcasts: subscribed shows as tiles
        val podDirectoryGrid = booleanPreferencesKey("pod_directory_grid") // podcasts: directory as tiles
    }

    val palette: Flow<String> = context.settingsStore.data.map { it[K.palette] ?: "system" }
    val haptics: Flow<Boolean> = context.settingsStore.data.map { it[K.haptics] ?: true }
    val visualizer: Flow<String> = context.settingsStore.data.map { it[K.visualizer] ?: "spectrum" }
    /** Chosen audio output device id, or -1 to follow the system routing. */
    val outputDevice: Flow<Int> = context.settingsStore.data.map { it[K.outputDevice] ?: -1 }
    val cellular: Flow<Boolean> = context.settingsStore.data.map { it[K.cellular] ?: true }
    /** Stereo folds to dual mono; one dead earbud never loses half the mix. */
    val mono: Flow<Boolean> = context.settingsStore.data.map { it[K.mono] ?: false }
    /** ListenBrainz user token, encrypted at rest like provider secrets. Blank until pasted. */
    val listenBrainzToken: Flow<String> = context.settingsStore.data.map { p ->
        p[K.lbToken]?.let { runCatching { SecretStore.decrypt(it) }.getOrNull() }.orEmpty()
    }
    val bufferSeconds: Flow<Int> = context.settingsStore.data.map { it[K.bufferSeconds] ?: 30 }
    val eqEnabled: Flow<Boolean> = context.settingsStore.data.map { it[K.eqEnabled] ?: false }
    val eqPreset: Flow<String> = context.settingsStore.data.map { it[K.eqPreset] ?: "flat" }
    val autoResume: Flow<Boolean> = context.settingsStore.data.map { it[K.autoResume] ?: false }
    /** Latest episodes fetch themselves for subscribed shows. Off by default. */
    val autoDownload: Flow<Boolean> = context.settingsStore.data.map { it[K.autoDownload] ?: false }
    /** Local files reopen where they stopped. Off by default: songs restart. */
    val resumeLocal: Flow<Boolean> = context.settingsStore.data.map { it[K.resumeLocal] ?: false }
    val volume: Flow<Float> = context.settingsStore.data.map { it[K.volume] ?: 1f }
    /** Playback speed multiplier, 0.5–2.0. Applied to every play. */
    val speed: Flow<Float> = context.settingsStore.data.map { (it[K.speed] ?: 1f).coerceIn(0.5f, 2f) }
    /** Synced-lyrics offset in ms, ±10s like cliamp's `[`/`]` keys. */
    val lyricsOffset: Flow<Int> = context.settingsStore.data.map {
        (it[K.lyricsOffset] ?: 0).coerceIn(-10_000, 10_000)
    }

    /** Podcasts subscribed-shows section as a grid. */
    val subsGrid: StateFlow<Boolean> = subsGridFlag.asStateFlow()
    /** Podcasts directory section as a grid. */
    val podDirectoryGrid: StateFlow<Boolean> = podDirectoryGridFlag.asStateFlow()

    val eqBands: Flow<List<Float>> = context.settingsStore.data.map { p ->
        p[K.eqBands]?.let { raw -> runCatching { Http.json.decodeFromString<List<Float>>(raw) }.getOrNull() }
            ?: List(7) { 0f }
    }

    val favorites: Flow<List<Station>> =
        db.favorites().all().map { rows -> rows.map { it.toStation() } }

    /**
     * The widget can be rendered long after the app process died, so anything
     * it draws has to survive on disk - the in-memory PlaybackBus is no use
     * there.
     */
    val widgetPlaying: Flow<Boolean> = context.settingsStore.data.map { it[K.wPlaying] ?: false }
    val widgetTrack: Flow<String> = context.settingsStore.data.map { it[K.wTrack] ?: "" }
    val widgetSeekable: Flow<Boolean> = context.settingsStore.data.map { it[K.wSeekable] ?: false }
    val widgetDuration: Flow<Long> = context.settingsStore.data.map { it[K.wDuration] ?: 0L }

    /**
     * The up-next stations (4 after the current one in the list being played)
     * the widget walks with its prev/next keys. Written by the service
     * whenever the now-playing or the list changes; empty when nothing is
     * loaded.
     */
    val widgetNext: Flow<List<Station>> = context.settingsStore.data.map { p ->
        p[K.wNext]?.let { raw ->
            runCatching { Http.json.decodeFromString<List<Station>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    /**
     * A bounded window of the list being played, centred on the current song,
     * so the widget's prev/next can walk the right domain (local / radio /
     * podcast) without relying on the in-memory PlaybackBus, which is empty on
     * a cold process. Written by PlayerConnection whenever the list changes.
     */
    val widgetSource: Flow<List<Station>> = context.settingsStore.data.map { p ->
        p[K.wSource]?.let { raw ->
            runCatching { Http.json.decodeFromString<List<Station>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    val history: Flow<List<Station>> =
        db.history().recent().map { rows -> rows.map { it.toStation() } }

    /**
     * Last session's Up Next window plus the position inside it, so a cold
     * start opens on the remembered queue instead of an empty one. The window
     * is already bounded (WINDOW=60 in PlayerConnection), the same size class
     * as the widget's persisted lists - the full source list is deliberately
     * not stored, it can be the whole local library.
     */
    val queue: Flow<List<Station>> = context.settingsStore.data.map { p ->
        p[K.queue]?.let { raw ->
            runCatching { Http.json.decodeFromString<List<Station>>(raw) }.getOrNull()
        } ?: emptyList()
    }
    val queueIndex: Flow<Int> = context.settingsStore.data.map { it[K.queueIndex] ?: 0 }

    /** Write the queue window and position in one transaction so cold-boot
     * readers never see a list from one play with another's index. */
    suspend fun setQueue(stations: List<Station>, index: Int) {
        context.settingsStore.edit {
            it[K.queue] = Http.json.encodeToString(stations)
            it[K.queueIndex] = index
        }
    }
    val custom: Flow<List<Station>> =
        db.customStations().all().map { rows -> rows.map { it.toStation() } }

    /**
     * Files the app itself fetched, by remote audio URL. The downloads smart
     * playlist shows exactly these - never the whole local library - so
     * membership means "kleeamp put it here". Each entry carries its path and
     * the station snapshot that plays it, which is how the playlist lists
     * episodes that live nowhere else on the phone.
     */
    val downloads: Flow<Map<String, DownloadEntry>> = context.settingsStore.data.map { p ->
        p[K.downloads]?.let { raw ->
            runCatching { Http.json.decodeFromString<Map<String, DownloadEntry>>(raw) }.getOrNull()
        } ?: emptyMap()
    }

    /** Per-playlist remembered sort, seeded into memory at construction. */
    private val persist = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-playlist sort lives in memory so a chip tap renumbers the list on
    // the same frame and reopening a list starts already ordered; the
    // DataStore file is only the restore-after-boot source.
    private val sortOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    private var sortPersist: Job? = null

    /** The saved theme, read synchronously at startup (see init) so the very
     * first frame already wears it instead of flashing the default. */
    val initialPalette: String

    init {
        // Read the whole preferences file once, on construction (Application
        // startup, before any UI exists) rather than arriving at the values
        // asynchronously after the first frame. The layout flags are what a
        // user sees the moment a tab opens, so a grid that starts default and
        // snaps to their choice a beat later would be visible every launch:
        // they asked for a grid, the app should open already in their grid.
        // The settings file is a few bytes - this blocking read is
        // milliseconds, and it happens before the activity exists.
        val p = runBlocking { context.settingsStore.data.first() }
        initialPalette = p[K.palette] ?: "system"
        sortOverrides.value = p[K.playlistSorts]?.let { raw ->
            runCatching { Http.json.decodeFromString<Map<String, Int>>(raw) }.getOrNull()
        } ?: emptyMap()
        subsGridFlag.value = p[K.subsGrid] ?: true
        podDirectoryGridFlag.value = p[K.podDirectoryGrid] ?: true
    }

    /** The sort choice for one list, defaulting to the current title order. */
    fun playlistSort(slug: String): Flow<PlaylistSort> =
        sortOverrides.map { map ->
            map[slug]?.let { PlaylistSort.entries.getOrNull(it) } ?: PlaylistSort.Title
        }

    /** The sort choice today, for the first frame of a freshly opened list. */
    fun playlistSortValue(slug: String): PlaylistSort =
        sortOverrides.value[slug]?.let { PlaylistSort.entries.getOrNull(it) } ?: PlaylistSort.Title

    val lastStation: Flow<Station?> = context.settingsStore.data.map { p ->
        p[K.lastStation]?.let { raw -> runCatching { Http.json.decodeFromString<Station>(raw) }.getOrNull() }
    }

    suspend fun setPalette(v: String) = put(K.palette, v)
    /** Raw imported theme JSON; blank means none. Validated on the way in. */
    val customTheme: Flow<String> = context.settingsStore.data.map { it[K.customTheme].orEmpty() }
    suspend fun setCustomTheme(v: String) = put(K.customTheme, v)
    suspend fun clearCustomTheme() {
        if (palette.first() == "custom") put(K.palette, "system")
        put(K.customTheme, "")
    }
    suspend fun setHaptics(v: Boolean) = put(K.haptics, v)
    suspend fun setVisualizer(v: String) = put(K.visualizer, v)
    suspend fun setOutputDevice(v: Int) = put(K.outputDevice, v)
    suspend fun setCellular(v: Boolean) = put(K.cellular, v)
    suspend fun setMono(v: Boolean) = put(K.mono, v)
    suspend fun listenBrainzTokenSync(): String = listenBrainzToken.first()
    suspend fun setListenBrainzToken(v: String) = put(K.lbToken, SecretStore.encrypt(v))
    suspend fun setBufferSeconds(v: Int) = put(K.bufferSeconds, v)
    suspend fun setEqEnabled(v: Boolean) = put(K.eqEnabled, v)
    suspend fun setEqPreset(v: String) = put(K.eqPreset, v)
    suspend fun setAutoResume(v: Boolean) = put(K.autoResume, v)
    suspend fun setAutoDownload(v: Boolean) = put(K.autoDownload, v)
    suspend fun setResumeLocal(v: Boolean) = put(K.resumeLocal, v)
    suspend fun setVolume(v: Float) = put(K.volume, v)
    suspend fun setSpeed(v: Float) = put(K.speed, v.coerceIn(0.5f, 2f))
    suspend fun setLyricsOffset(v: Int) = put(K.lyricsOffset, v.coerceIn(-10_000, 10_000))
    suspend fun setSubsGrid(v: Boolean) {
        subsGridFlag.value = v
        put(K.subsGrid, v)
    }
    suspend fun setPodDirectoryGrid(v: Boolean) {
        podDirectoryGridFlag.value = v
        put(K.podDirectoryGrid, v)
    }

    /** Remember a playlist's sort; edits merge so other playlists are untouched. */
    fun setPlaylistSort(slug: String, sort: PlaylistSort) {
        sortOverrides.value = sortOverrides.value + (slug to sort.ordinal)
        sortPersist?.cancel()
        sortPersist = persist.launch {
            context.settingsStore.edit { p ->
                val current = p[K.playlistSorts]?.let { raw ->
                    runCatching { Http.json.decodeFromString<Map<String, Int>>(raw) }.getOrNull()
                } ?: emptyMap()
                p[K.playlistSorts] = Http.json.encodeToString(current + sortOverrides.value)
            }
        }
    }

    suspend fun setWidgetPlaying(v: Boolean) = put(K.wPlaying, v)
    suspend fun setWidgetTrack(v: String) = put(K.wTrack, v)
    suspend fun setWidgetNext(v: List<Station>) = put(K.wNext, Http.json.encodeToString(v))
    suspend fun setWidgetSource(v: List<Station>) = put(K.wSource, Http.json.encodeToString(v))

    /**
     * Write the widget's whole row (state + seekability) in a single
     * DataStore transaction so cold-boot readers see a consistent snapshot.
     */
    suspend fun writeWidgetSnapshot(playing: Boolean, track: String, seekable: Boolean, durationMs: Long) {
        context.settingsStore.edit {
            it[K.wPlaying] = playing
            it[K.wTrack] = track
            it[K.wSeekable] = seekable
            it[K.wDuration] = durationMs
        }
    }

    suspend fun readLastStation(): Station? =
        context.settingsStore.data.first()[K.lastStation]
            ?.let { runCatching { Http.json.decodeFromString<Station>(it) }.getOrNull() }

    suspend fun setEqBands(v: List<Float>) =
        put(K.eqBands, Http.json.encodeToString(v))

    suspend fun setLastStation(s: Station) =
        put(K.lastStation, Http.json.encodeToString(s))

    /**
     * Serializes favourite writes. The check and the write happen inside one
     * lock so rapid taps invert strictly in order instead of racing on a
     * stale membership read and collapsing into a single effect.
     */
    private val favMutex = Mutex()

    suspend fun toggleFavorite(s: Station): Boolean = favMutex.withLock {
        if (db.favorites().contains(s.url)) {
            db.favorites().remove(s.url)
            false
        } else {
            addFavoriteLocked(s)
        }
    }

    /**
     * Ensures [s] is a favourite, leaving an existing one untouched.
     */
    suspend fun addFavorite(s: Station): Boolean = favMutex.withLock { addFavoriteLocked(s) }

    suspend fun removeFavorite(s: Station) = favMutex.withLock { db.favorites().remove(s.url) }

    private suspend fun addFavoriteLocked(s: Station): Boolean {
        if (db.favorites().contains(s.url)) return false
        db.stations().upsert(s.toEntity())
        db.favorites().add(FavoriteEntity(s.url, db.favorites().nextTopPosition()))
        return true
    }

    suspend fun pushHistory(s: Station) {
        db.stations().upsert(s.toEntity())
        db.history().touch(HistoryEntity(s.url, System.currentTimeMillis()))
        // trimming is a DELETE with a subquery, not a list rebuilt in full
        db.history().trim()
    }

    suspend fun clearHistory() = db.history().clear()

    suspend fun addCustom(s: Station) {
        db.stations().upsert(s.toEntity())
        db.customStations().add(CustomStationEntity(s.url, db.customStations().nextTopPosition()))
    }

    suspend fun removeCustom(s: Station) = db.customStations().remove(s.url)

    suspend fun addDownload(entry: DownloadEntry) {
        put(K.downloads, Http.json.encodeToString((downloads.first() + (entry.url to entry))))
    }

    suspend fun removeDownload(url: String) {
        put(K.downloads, Http.json.encodeToString((downloads.first() - url)))
    }

    suspend fun setDownloads(map: Map<String, DownloadEntry>) {
        put(K.downloads, Http.json.encodeToString(map))
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }
}
