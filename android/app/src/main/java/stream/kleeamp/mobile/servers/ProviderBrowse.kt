package stream.kleeamp.mobile.servers

import kotlinx.coroutines.flow.StateFlow
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.playback.StreamResolver

/**
 * Provider-independent shapes for the browse screen. The API clients (Subsonic,
 * Jellyfin/Emby, ...) each speak their own schema; this is the small vocabulary
 * the screen needs, and each provider's track converts to a [Station] the same
 * way.
 */
data class ProviderAlbum(
    val id: String,
    val name: String,
    val artist: String = "",
    val songCount: Int = 0,
    val year: Int = 0,
)

data class ProviderArtist(val id: String, val name: String, val albumCount: Int = 0)

/** One browsable music library on a multi-library server (a Plex section). */
data class ProviderLibrary(val id: String, val name: String)

/**
 * A provider that keeps its own index of the server rather than asking it a
 * question per screen. Only SSH does: a filesystem has no album endpoint, so
 * the tree is walked once and the answers come out of the database.
 */
data class IndexState(val scanning: Boolean = false, val text: String = "")

data class ProviderTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val codec: String = "",
    val bitrate: Int = 0,
    /** The album's artist when the server sends one; blank lets the account fill it. */
    val albumArtist: String = "",
    val trackNo: Int = 0,
    val discNo: Int = 0,
    /** When the track entered the server's library, epoch seconds; 0 when unknown. */
    val dateAdded: Long = 0,
)

fun ProviderTrack.toStation(
    account: ProviderAccount,
    cover: String = "",
    fallbackAlbum: String = "",
    fallbackAlbumArtist: String = "",
    fallbackTrackNo: Int = 0,
    fallbackDiscNo: Int = 0,
): Station = Station(
    id = "prov:${account.id}:$id",
    name = title.ifBlank { "untitled" },
    url = "${StreamResolver.PROVIDER_SCHEME}${account.id}/$id",
    source = StationSource.Provider,
    artist = artist.ifBlank { fallbackAlbumArtist },
    album = album.ifBlank { fallbackAlbum },
    albumArtist = albumArtist.ifBlank { fallbackAlbumArtist },
    trackNo = trackNo.takeIf { it > 0 } ?: fallbackTrackNo,
    discNo = discNo.takeIf { it > 0 } ?: fallbackDiscNo,
    durationMs = duration * 1000L,
    codec = codec,
    bitrate = bitrate,
    cover = cover,
    dateAdded = dateAdded,
)

/**
 * Parses the ISO-8601 timestamps Subsonic and Jellyfin use for `created` /
 * `DateCreated` into epoch seconds. Anything unreadable counts as unknown
 * rather than failing the whole album load.
 */
internal fun parseIsoEpochSeconds(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0
    return runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }
        .recoverCatching { java.time.Instant.parse(raw).epochSecond }
        .getOrDefault(0)
}

/**
 * The operations the browse screen needs from any provider. Each provider is a
 * thin adapter over its API client; the screen never sees provider types.
 */
interface ProviderBrowseClient {
    /** [style] is a browse root label; providers map it to their own sort. */
    suspend fun albums(style: String): Result<List<ProviderAlbum>>
    suspend fun artists(): Result<List<ProviderArtist>>
    suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>>
    suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>>
    /** Favourite/starred tracks; empty list when the provider has no such concept. */
    suspend fun starred(): Result<List<ProviderTrack>>
    fun trackCover(id: String): String
    /**
     * The account's music libraries for the library picker; empty when the
     * provider has one implicit library. Plex and Audiobookshelf serve
     * several, and browsing only ever saw the first until now.
     */
    suspend fun libraries(): Result<List<ProviderLibrary>> = Result.success(emptyList())

    /**
     * Non-null for providers whose library is a local index, so the screen can
     * say what a scan is doing and offer to run one. An API provider has
     * nothing to scan and leaves both of these alone.
     */
    val index: StateFlow<IndexState>? get() = null

    suspend fun reindex(): Result<Unit> = Result.success(Unit)
}

fun ProviderAccount.browseClient(): ProviderBrowseClient = when (providerKey) {
    "ssh" -> SftpBrowseClient(this)
    "jellyfin", "emby" -> JellyfinBrowseClient(this, jellyfin())
    "plex" -> PlexBrowseClient(plex())
    "abs" -> AbsBrowseClient(audiobookshelf())
    "lyrion" -> LyrionBrowseClient(lyrion())
    else -> SubsonicBrowseClient(this, subsonic())
}

private class SubsonicBrowseClient(
    private val account: ProviderAccount,
    private val client: SubsonicClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> =
        client.albums(subsonicSort(style)).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun artists(): Result<List<ProviderArtist>> =
        client.artists().map { objs -> objs.map { ProviderArtist(it.id, it.name, it.albumCount) } }

    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        client.artistAlbums(artistId).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId).map { objs -> objs.map { it.toProviderTrack() } }

    override suspend fun starred(): Result<List<ProviderTrack>> =
        client.starred().map { objs -> objs.map { it.toProviderTrack() } }

    override fun trackCover(id: String): String = client.coverArtUrl(id)
}

private class JellyfinBrowseClient(
    private val account: ProviderAccount,
    private val client: JellyfinClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> =
        client.albums(style).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun artists(): Result<List<ProviderArtist>> =
        client.artists().map { objs -> objs.map { ProviderArtist(it.id, it.name, 0) } }

    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        client.artistAlbums(artistId).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId).map { objs -> objs.map { it.toProviderTrack() } }

    override suspend fun starred(): Result<List<ProviderTrack>> =
        Result.success(emptyList())

    override fun trackCover(id: String): String = client.coverUrl(id)
}

private fun SubsonicTrack.toProviderTrack(): ProviderTrack = ProviderTrack(
    id = id,
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    codec = suffix,
    bitrate = bitRate,
    trackNo = track,
    discNo = discNumber,
    dateAdded = parseIsoEpochSeconds(created),
)

private fun JellyfinTrack.toProviderTrack(): ProviderTrack = ProviderTrack(
    id = id,
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    codec = suffix,
    bitrate = bitRate,
    albumArtist = albumArtist,
    trackNo = trackNo,
    discNo = discNo,
    dateAdded = dateAdded,
)

private class PlexBrowseClient(
    private val client: PlexClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> = client.albums()
    override suspend fun artists(): Result<List<ProviderArtist>> = client.artists()
    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        client.artistAlbums(artistId)
    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId)
    override suspend fun starred(): Result<List<ProviderTrack>> = Result.success(emptyList())
    override suspend fun libraries(): Result<List<ProviderLibrary>> = client.libraries()
    /**
     * No per-track cover: the track id is the Part stream path, not a metadata
     * ratingKey, so building a thumb URL from it 404s. The row falls back to
     * the play glyph.
     */
    override fun trackCover(id: String): String = ""
}

private class LyrionBrowseClient(
    private val client: LyrionClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> = client.albums()
    override suspend fun artists(): Result<List<ProviderArtist>> = client.artists()
    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        client.artistAlbums(artistId)
    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId)
    override suspend fun starred(): Result<List<ProviderTrack>> = Result.success(emptyList())
    override fun trackCover(id: String): String = ""
}

private class AbsBrowseClient(
    private val client: AudiobookshelfClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> = client.albums()
    override suspend fun artists(): Result<List<ProviderArtist>> = Result.success(emptyList())
    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        Result.success(emptyList())
    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId)
    override suspend fun starred(): Result<List<ProviderTrack>> = Result.success(emptyList())
    override suspend fun libraries(): Result<List<ProviderLibrary>> = client.libraries()
    override fun trackCover(id: String): String = client.coverUrl(id.substringBefore("::"))
}

/**
 * Reads the scanned index rather than the server. Every call makes sure a scan
 * has been asked for, but never waits on one: an unscanned account shows an
 * empty list and a live status line instead of a spinner that could sit there
 * for a minute.
 */
private class SftpBrowseClient(private val account: ProviderAccount) : ProviderBrowseClient {

    override val index: StateFlow<IndexState> = SftpLibrary.status(account.id)

    override suspend fun reindex(): Result<Unit> = SftpLibrary.rescan(account)

    override suspend fun albums(style: String): Result<List<ProviderAlbum>> = runCatching {
        SftpLibrary.awaitIndexed(account)
        SftpLibrary.albums(account.id, style).map {
            ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year)
        }
    }

    override suspend fun artists(): Result<List<ProviderArtist>> = runCatching {
        SftpLibrary.awaitIndexed(account)
        SftpLibrary.artists(account.id).map { ProviderArtist(it.id, it.name, it.albumCount) }
    }

    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> = runCatching {
        SftpLibrary.artistAlbums(account.id, artistId).map {
            ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year)
        }
    }

    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> = runCatching {
        SftpLibrary.albumTracks(account.id, albumId).map {
            ProviderTrack(
                id = it.path,
                title = it.title,
                artist = it.artist,
                album = it.album,
                // No duration until the extractor reads one: getting it here
                // would mean opening every file on the server to find out.
                duration = 0,
                codec = it.ext,
                trackNo = it.track,
                dateAdded = it.mtime / 1000,
            )
        }
    }

    override suspend fun starred(): Result<List<ProviderTrack>> = Result.success(emptyList())

    /** No cover art over SFTP yet; the row falls back to the play glyph. */
    override fun trackCover(id: String): String = ""
}

private fun <T, R> Result<List<T>>.map(t: (List<T>) -> R): Result<R> =
    fold(onSuccess = { Result.success(t(it)) }, onFailure = { Result.failure(it) })

/**
 * The browse screen's root labels mapped onto Subsonic `getAlbumList2` types.
 * The screen speaks "az"; the server only knows "alphabeticalByName".
 */
private fun subsonicSort(style: String): String = when (style) {
    "az" -> "alphabeticalByName"
    "newest" -> "newest"
    "frequent" -> "frequent"
    "recent" -> "recent"
    "starred" -> "starred"
    else -> "alphabeticalByName"
}
