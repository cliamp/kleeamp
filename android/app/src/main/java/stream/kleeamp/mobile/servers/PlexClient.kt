package stream.kleeamp.mobile.servers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.playback.ResolvedStream

/**
 * Plex is different from the Emby-family servers: it keys off a single
 * `X-Plex-Token` and its library is discovered through `/library/sections`.
 * Its stream URLs anyway want the token in the URL (`?X-Plex-Token=`), so this
 * client bakes it into the signed URL and, like Jellyfin, the default playback
 * data source needs nothing extra.
 *
 * Plex serves several music sections, but the browse screen shows one: the
 * stored [library] section wins when it still exists, otherwise the first
 * music section found (the legacy behaviour).
 */
class PlexClient(
    rawUrl: String,
    private val token: String,
    private val library: String = "",
) {
    private val base = SubsonicClient.normalise(rawUrl)
    private val auth = "X-Plex-Token=$token"

    // Plex answers XML unless told otherwise. The models below are JSON, so
    // every request asks for JSON up front rather than parsing XML.
    private val jsonHeaders = mapOf("Accept" to "application/json")

    @Volatile private var musicSection: String? = null

    private suspend fun sectionKey(): String {
        musicSection?.let { return it }
        val dirs = sections()
        val music = dirs.filter { it.type == "artist" }.ifEmpty { dirs }
        val key = music.firstOrNull { it.key == library }?.key
            ?: music.firstOrNull()?.key.orEmpty()
        if (key.isBlank()) error("no plex music library found")
        musicSection = key
        return key
    }

    /** Every music section, for the library picker. Empty when none exist. */
    suspend fun libraries(): Result<List<ProviderLibrary>> = withContext(Dispatchers.IO) {
        runCatching {
            val dirs = sections()
            val music = dirs.filter { it.type == "artist" }.ifEmpty { dirs }
            music.map { ProviderLibrary(it.key, it.title.ifBlank { "library ${it.key}" }) }
        }
    }

    private suspend fun sections(): List<Section> {
        val body = Http.text(get("library/sections"), jsonHeaders)
        return Http.json.decodeFromString<SectionContainer>(body).mediaContainer.directory
    }

    suspend fun ping(): Result<ProviderIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.text(get("", emptyMap()), jsonHeaders)
            val title = Http.json.decodeFromString<RootContainer>(body).mediaContainer.title.orEmpty()
            ProviderIdentity(
                name = title.ifBlank { "plex" } + " · " + SubsonicClient.shortHost(base),
            )
        }
    }

    suspend fun albums(): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = sectionKey()
            val body = Http.text(get("library/sections/$key/all", mapOf("type" to "9")), jsonHeaders)
            Http.json.decodeFromString<MetadataContainer<AlbumMeta>>(body).mediaContainer.metadata.map {
                ProviderAlbum(
                    id = it.ratingKey,
                    name = it.title.orEmpty(),
                    artist = it.parentTitle.orEmpty(),
                    songCount = 0,
                    year = it.year ?: 0,
                )
            }
        }
    }

    suspend fun artists(): Result<List<ProviderArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = sectionKey()
            val body = Http.text(get("library/sections/$key/all", mapOf("type" to "8")), jsonHeaders)
            Http.json.decodeFromString<MetadataContainer<ArtistMeta>>(body).mediaContainer.metadata.map {
                ProviderArtist(it.ratingKey, it.title.orEmpty(), 0)
            }
        }
    }

    suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = sectionKey()
            val body = Http.text(
                get("library/sections/$key/all", mapOf("type" to "9", "artist.id" to artistId)),
                jsonHeaders,
            )
            Http.json.decodeFromString<MetadataContainer<AlbumMeta>>(body).mediaContainer.metadata.map {
                ProviderAlbum(
                    id = it.ratingKey,
                    name = it.title.orEmpty(),
                    artist = it.parentTitle.orEmpty(),
                    songCount = 0,
                    year = it.year ?: 0,
                )
            }
        }
    }

    suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.text(get("library/metadata/$albumId/children"), jsonHeaders)
            Http.json.decodeFromString<MetadataContainer<TrackMeta>>(body).mediaContainer.metadata.map { t ->
                val part = t.media.firstOrNull()?.part.orEmpty()
                ProviderTrack(
                    // The opaque ref is the relative Part path, so the resolver
                    // can rebuild a signed stream URL at play time.
                    id = part.firstOrNull()?.key.orEmpty().ifBlank { "/library/metadata/$albumId/unknown" },
                    title = t.title.orEmpty(),
                    artist = t.originalTitle.orEmpty().ifBlank { t.grandparentTitle.orEmpty() },
                    album = t.parentTitle.orEmpty(),
                    duration = t.duration?.div(1000) ?: 0,
                    codec = part.firstOrNull()?.container.orEmpty(),
                    albumArtist = t.grandparentTitle.orEmpty(),
                    trackNo = t.index ?: 0,
                    discNo = t.parentIndex ?: 0,
                    dateAdded = t.addedAt ?: 0,
                )
            }
        }
    }

    fun stream(partPath: String): ResolvedStream =
        ResolvedStream("$base$partPath?$auth")

    fun coverUrl(ratingKey: String): String =
        "$base/library/metadata/$ratingKey/thumb?$auth"

    private fun get(path: String, params: Map<String, String> = emptyMap()): String {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val base2 = "$base/$path"
        return if (query.isEmpty()) "$base2?$auth" else "$base2?$query&$auth"
    }
}

@Serializable
private data class SectionContainer(@SerialName("MediaContainer") val mediaContainer: Sections)

@Serializable
private data class Sections(@SerialName("Directory") val directory: List<Section> = emptyList())

@Serializable
private data class Section(val key: String = "", val type: String = "", val title: String = "")

@Serializable
private data class RootContainer(@SerialName("MediaContainer") val mediaContainer: Root)

@Serializable
private data class Root(val title: String = "")

@Serializable
private data class MetadataContainer<T>(@SerialName("MediaContainer") val mediaContainer: Metadata<T>)

@Serializable
private data class Metadata<T>(@SerialName("Metadata") val metadata: List<T> = emptyList())

@Serializable
private data class AlbumMeta(
    val ratingKey: String = "",
    val title: String? = null,
    val parentTitle: String? = null,
    val year: Int? = null,
)

@Serializable
private data class ArtistMeta(val ratingKey: String = "", val title: String? = null)

@Serializable
private data class TrackMeta(
    val ratingKey: String = "",
    val title: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val originalTitle: String? = null,
    val duration: Int? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val addedAt: Long? = null,
    @SerialName("Media") val media: List<MediaMeta> = emptyList(),
)

@Serializable
private data class MediaMeta(@SerialName("Part") val part: List<PartMeta> = emptyList())

@Serializable
private data class PartMeta(val key: String = "", val container: String = "")
