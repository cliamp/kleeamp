package stream.kleeamp.mobile.servers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import stream.kleeamp.mobile.net.Http
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URLEncoder
import stream.kleeamp.mobile.prefs.SecretStore

/**
 * Navidrome speaks the Subsonic API, so this one client also covers Gonic,
 * Airsonic and anything else implementing it.
 *
 * There is no session to establish. Every request carries a fresh salt and
 * `md5(password + salt)`, which is what the Subsonic spec mandates. MD5 here is
 * the protocol, not a security choice on our part, and the consequence worth
 * knowing is that the password itself has to be retrievable to sign each
 * request. It cannot be traded for a token once and forgotten, which is why it
 * goes through [SecretStore] rather than into plain preferences.
 */
class SubsonicClient(
    rawUrl: String,
    private val user: String,
    private val password: String,
) {
    private val base = normalise(rawUrl)

    suspend fun ping(): Result<ProviderIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.text(endpoint("ping.view"))
            val res = Http.json.decodeFromString<SubsonicEnvelope>(body).response
            if (!res.isOk) error(res.error?.message ?: "server refused the credentials")
            ProviderIdentity(
                name = (res.type?.takeIf { it.isNotBlank() } ?: "subsonic") +
                    " · " + shortHost(base),
                detail = res.serverVersion?.takeIf { it.isNotBlank() }.orEmpty(),
            )
        }
    }

    fun streamUrl(id: String): String = endpoint("stream.view", mapOf("id" to id))

    /**
     * Provider albums have real cover art, so the og:image fallback that radio
     * stations need is not wanted here.
     */
    fun coverArtUrl(id: String, size: Int = 512): String =
        endpoint("getCoverArt.view", mapOf("id" to id, "size" to size.toString()))

    /** `type` is one of newest, recent, frequent, alphabeticalByName, starred. */
    suspend fun albums(type: String, offset: Int = 0, size: Int = 100): Result<List<SubsonicAlbum>> =
        call("getAlbumList2.view", mapOf("type" to type, "size" to size.toString(), "offset" to offset.toString())) {
            it.albumList2?.album.orEmpty()
        }

    suspend fun artists(): Result<List<SubsonicArtist>> =
        call("getArtists.view") { env ->
            env.artists?.index?.flatMap { it.artist }.orEmpty()
        }

    suspend fun artistAlbums(artistId: String): Result<List<SubsonicAlbum>> =
        call("getArtist.view", mapOf("id" to artistId)) { it.artist2?.album.orEmpty() }

    suspend fun albumTracks(albumId: String): Result<List<SubsonicTrack>> =
        call("getAlbum.view", mapOf("id" to albumId)) { it.album?.song.orEmpty() }

    suspend fun starred(): Result<List<SubsonicTrack>> =
        call("getStarred2.view") { it.starred2?.song.orEmpty() }

    private suspend fun <T> call(
        view: String,
        params: Map<String, String> = emptyMap(),
        pick: (SubsonicResponse) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.text(endpoint(view, params))
            val res = Http.json.decodeFromString<SubsonicEnvelope>(body).response
            if (!res.isOk) error(res.error?.message ?: "request refused")
            pick(res)
        }
    }

    private fun endpoint(view: String, extra: Map<String, String> = emptyMap()): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex()
        val token = MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray())
            .toHex()
        val params = buildMap {
            put("u", user)
            put("t", token)
            put("s", salt)
            put("v", API_VERSION)
            put("c", CLIENT)
            put("f", "json")
            putAll(extra)
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$base/rest/$view?$query"
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT = "kleeamp"

        /** People paste "music.example.com/" as often as a real URL. */
        fun normalise(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return t
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        /**
         * The host as typed, for account labels: "music.example.com" or
         * "127.0.0.1:8096". Two servers of one kind would otherwise share a
         * label - two "audiobookshelf" rows with nothing to tell them apart.
         */
        fun shortHost(normalisedBase: String): String =
            normalisedBase.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .ifBlank { normalisedBase.trim() }
    }
}

@Serializable
data class SubsonicAlbum(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    val coverArt: String = "",
    val songCount: Int = 0,
    val year: Int = 0,
    val duration: Int = 0,
)

@Serializable
data class SubsonicArtist(
    val id: String = "",
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String = "",
)

@Serializable
data class SubsonicTrack(
    val id: String = "",
    val title: String = "",
    val album: String = "",
    val artist: String = "",
    val albumId: String = "",
    val coverArt: String = "",
    val duration: Int = 0,
    val track: Int = 0,
    val discNumber: Int = 0,
    val suffix: String = "",
    val bitRate: Int = 0,
    /** Navidrome/OpenSubsonic addition timestamp, ISO-8601. */
    val created: String = "",
)

@Serializable
data class SubsonicSearch(
    val artist: List<SubsonicArtist> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val song: List<SubsonicTrack> = emptyList(),
)

@Serializable
private data class AlbumList2(val album: List<SubsonicAlbum> = emptyList())

@Serializable
private data class ArtistIndexes(val index: List<ArtistIndex> = emptyList())

@Serializable
private data class ArtistIndex(val name: String = "", val artist: List<SubsonicArtist> = emptyList())

@Serializable
private data class ArtistDetail(val album: List<SubsonicAlbum> = emptyList())

@Serializable
private data class AlbumDetail(val song: List<SubsonicTrack> = emptyList())

@Serializable
private data class Starred2(val song: List<SubsonicTrack> = emptyList())

@Serializable
private data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse(),
)

@Serializable
private data class SubsonicResponse(
    val status: String = "",
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val error: SubsonicError? = null,
    val albumList2: AlbumList2? = null,
    val artists: ArtistIndexes? = null,
    @SerialName("artist") val artist2: ArtistDetail? = null,
    val album: AlbumDetail? = null,
    val starred2: Starred2? = null,
    val searchResult3: SubsonicSearch? = null,
) {
    val isOk: Boolean get() = status.equals("ok", ignoreCase = true)
}

@Serializable
private data class SubsonicError(val code: Int = 0, val message: String = "")
