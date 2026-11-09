package stream.kleeamp.mobile.library

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import stream.kleeamp.mobile.db.KleeampDatabase
import stream.kleeamp.mobile.db.PlaylistEntity
import stream.kleeamp.mobile.db.PlaylistMemberEntity
import stream.kleeamp.mobile.db.toEntity
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * Persists user playlists. A playlist is a small [Station] (source Local): its
 * `slug` is the stable id, `name` the title, `cover` an artwork URI, and `meta`
 * a human "N songs" line. Members can be any source: local songs are remembered
 * as `local:<id>` strings (surviving a MediaStore re-scan, since URIs move),
 * while radio and podcast members keep a snapshot in the shared `stations`
 * table so they resolve later without a network round-trip. The ordered list
 * for a playlist is resolved against the live library when you open it.
 */
class PlaylistStore(private val context: Context) {

    data class Playlist(
        val station: Station,
        /** Ordered member ids (`local:<id>`), matching [LocalLibrary] ids. */
        val songIds: List<String>,
    )

    private val db by lazy { KleeampDatabase.get(context) }
    private val dao by lazy { db.playlists() }
    private val stationsDao by lazy { db.stations() }

    /**
     * Rows and their members, joined in memory from two flows.
     *
     * This replaced three JSON blobs kept in step by hand: a playlist list, a
     * slug-to-members map and a pinned set. Deleting a playlist now takes its
     * members with it by foreign key, which the blobs had to remember to do.
     */
    val playlists: Flow<List<Playlist>> =
        combine(dao.playlists(), dao.members()) { lists, members ->
            val bySlug = members.groupBy { it.slug }
            lists.map { pl ->
                Playlist(
                    station = pl.toStation(),
                    songIds = bySlug[pl.slug].orEmpty().sortedBy { it.position }.map { it.songId },
                )
            }
        }

    val pinnedSlugs: Flow<Set<String>> =
        dao.playlists().map { rows -> rows.filter { it.pinned }.map { it.slug }.toSet() }

    /**
     * Creates a playlist, returning its slug (or null for a blank name). An
     * already-taken name returns the existing playlist's slug instead of a
     * duplicate, so callers can select what the name points at either way.
     */
    suspend fun create(name: String, cover: String = ""): String? {
        val clean = name.trim().ifEmpty { return null }
        val slug = slugify(clean)
        if (dao.find(slug) != null) return slug
        dao.upsert(PlaylistEntity(slug = slug, name = clean, cover = cover))
        return slug
    }

    suspend fun rename(slug: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        dao.rename(slug, clean)
    }

    suspend fun delete(slug: String) = dao.delete(slug)

    suspend fun setPinned(slug: String, pinned: Boolean) = dao.setPinned(slug, pinned)

    suspend fun setCover(slug: String, cover: String) = dao.setCover(slug, cover)

    suspend fun addSong(slug: String, songId: String): Boolean {
        if (dao.hasSong(slug, songId)) return false
        dao.addMember(PlaylistMemberEntity(slug, songId, dao.nextMemberPosition(slug)))
        return true
    }

    /**
     * Adds every id not already a member, in order, and returns how many
     * landed. Folder imports use this: a bulk add must never toggle an
     * existing member back out the way a picker tap would.
     */
    suspend fun addSongs(slug: String, songIds: List<String>): Int = writeMutex.withLock {
        var added = 0
        for (id in songIds.distinct()) {
            if (dao.hasSong(slug, id)) continue
            dao.addMember(PlaylistMemberEntity(slug, id, dao.nextMemberPosition(slug)))
            added++
        }
        added
    }

    /**
     * Flips one membership, reading fresh state inside the lock. Checkbox
     * taps resolve direction here — not from the composition-time snapshot
     * that may predate an in-flight write — so rapid taps invert strictly in
     * order instead of collapsing into a single effect. Returns true when
     * the song ends up in the playlist.
     */
    private val writeMutex = Mutex()

    suspend fun toggleStation(slug: String, station: Station): Boolean = writeMutex.withLock {
        if (dao.hasSong(slug, station.id)) {
            dao.removeMember(slug, station.id)
            false
        } else {
            if (station.source != StationSource.Local) stationsDao.upsert(station.toEntity())
            dao.addMember(PlaylistMemberEntity(slug, station.id, dao.nextMemberPosition(slug)))
            true
        }
    }

    /**
     * Adds any station to a playlist. Local songs the same as [addSong]; for
     * radio and podcast members the full [Station] is snapshot into the shared
     * `stations` table (the row favourites/history already use) so the member
     * can be rebuilt by [resolveMembers] without re-fetching its feed.
     */
    suspend fun addStation(slug: String, station: Station): Boolean {
        if (station.source != StationSource.Local) stationsDao.upsert(station.toEntity())
        return addSong(slug, station.id)
    }

    suspend fun removeSong(slug: String, songId: String) = dao.removeMember(slug, songId)

    suspend fun setOrder(slug: String, songIds: List<String>) = dao.replaceMembers(slug, songIds)

    /**
     * Imports already-matched stations as a new playlist, skipping
     * duplicates. Returns the slug, or null when nothing matched.
     */
    suspend fun importPlaylist(name: String, stations: List<Station>): String? {
        if (stations.isEmpty()) return null
        val slug = create(name) ?: return null
        val seen = LinkedHashSet<String>()
        for (s in stations) {
            if (!seen.add(s.id)) continue
            addStation(slug, s)
        }
        return slug
    }

    /**
     * A persisted non-local station by playback URL. This is how the
     * add-to-playlist page re-finds a song that is only reachable as a
     * playlist member snapshot (a podcast episode or a provider track that was
     * never favourited or played): local songs come from the live library
     * instead, since they are never snapshotted here.
     */
    suspend fun snapshotByUrl(url: String): Station? =
        runCatching { stationsDao.byUrl(url)?.toStation() }.getOrNull()

    /**
     * Resolves ordered playlist member ids to their playable stations, against
     * the live local library plus any snapshot stations (radio/podcast). Local
     * members are matched by `local:<id>`; everything else by station id.
     */
    suspend fun resolveMembers(songIds: List<String>, localSongs: List<Station>): List<Station> {
        if (songIds.isEmpty()) return emptyList()
        val localById = localSongs.associateBy { it.id }
        val snapshotIds = songIds.filterNot { it.startsWith("local:") }
        val snapshotById = if (snapshotIds.isEmpty()) emptyMap()
        else stationsDao.byStationIds(snapshotIds).associateBy { it.stationId }
        return songIds.mapNotNull { id -> localById[id] ?: snapshotById[id]?.toStation() }
    }
}

private fun PlaylistEntity.toStation() = Station(
    id = "playlist:$slug",
    name = name,
    url = "cliamp-playlist://$slug",
    source = StationSource.Custom,
    slug = slug,
    cover = cover,
)

private fun slugify(name: String): String =
    name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("").trim('-').replace(Regex("-+"), "-")
        .ifEmpty { "playlist-" + System.currentTimeMillis() }
