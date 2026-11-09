package stream.kleeamp.mobile.servers

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.kleeamp.mobile.BuildConfig
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.playback.ResolvedStream

/**
 * Jellyfin speaks the Emby HTTP API, so this one client also covers emby
 * servers (a [providerKey] flag picks the auth scheme). Tracks are
 * served as files, exactly as [SubsonicClient] does; API calls authenticate
 * with a header while stream and artwork URLs carry the token as a query
 * parameter, because the player and the image loader fetch those without
 * custom headers.
 *
 * Jellyfin and Emby have diverged on auth. Jellyfin 12 removed the legacy
 * methods (`?api_key=`, `X-Emby-Token`) that older servers accept, so
 * Jellyfin uses the modern scheme throughout: an
 * `Authorization: MediaBrowser Token="…"` header on API calls and `?ApiKey=`
 * (capital A) on bare URLs the player and image loader fetch without headers.
 * Both forms also work on older Jellyfin (the header since forever, `ApiKey`
 * since 10.8), while Emby keeps the legacy parameter it has always taken.
 * Branching on [providerKey] rather than probing keeps the first request
 * working against either server with no extra round trip.
 *
 * Two sign-in modes mirror the desktop app:
 *  - an API token, used directly as the auth header;
 *  - a username + password, exchanged once for a token via
 *    `POST /Users/AuthenticateByName` and cached per server for the session.
 *    Jellyfin 12 rejects that exchange without client identification
 *    (`request.App/DeviceId/DeviceName/AppVersion` are all mandatory), so the
 *    login carries the full `MediaBrowser Client=…, Device=…, …` header.
 */
class JellyfinClient(
    rawUrl: String,
    private val token: String,
    private val user: String,
    private val password: String,
    private val providerKey: String,
) {
    private val base = SubsonicClient.normalise(rawUrl)

    /** Jellyfin 12 takes the modern scheme; Emby keeps the legacy parameter. */
    private val modernAuth: Boolean get() = providerKey != "emby"

    /** Query parameter carrying the token on bare URLs (stream, cover art). */
    private val tokenParam: String get() = if (modernAuth) "ApiKey" else "api_key"

    private val authToken: String
        // Pasted keys routinely carry a trailing newline from the dashboard's
        // copy button. OkHttp rejects control chars in header values
        // ("Unexpected char 0x0a …"), so trim here rather than failing the
        // probe on invisible whitespace. Passwords stay byte-exact.
        get() = token.trim().ifBlank { cachedToken(base) }.ifBlank { "" }

    /** Modern header for API calls; empty when there is nothing to send yet. */
    private fun authHeaders(): Map<String, String> {
        val t = authToken
        if (!modernAuth || t.isBlank()) return emptyMap()
        return mapOf("Authorization" to "MediaBrowser Token=\"$t\"")
    }

    /**
     * Client identification for the password exchange. Jellyfin 12 throws
     * when any of these is missing, and the values can only travel in the
     * header - the JSON body carries just the username and password.
     */
    private fun loginHeaders(): Map<String, String> {
        if (!modernAuth) return emptyMap()
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim().replace("\"", "")
        return mapOf(
            "Authorization" to
                "MediaBrowser Client=\"kleeamp\", " +
                "Device=\"$device\", " +
                "DeviceId=\"${Http.deviceId}\", " +
                "Version=\"${BuildConfig.VERSION_NAME}\"",
        )
    }

    /** Returns a short label for the wizard: server name + version. */
    suspend fun ping(): Result<ProviderIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            if (authToken.isBlank()) login()
            // The public info endpoint answers without credentials, so it
            // cannot validate a token. Ping the authenticated `/System/Info`
            // instead: it needs a working credential and still reports the
            // server name and version for the wizard label. (`/Users/Me`
            // looks tempting but API keys carry no user context, so it
            // answers 400 even for a valid key.)
            val body = Http.text(get("System/Info"), authHeaders())
            val info = Http.json.decodeFromString<PublicSystemInfo>(body)
            if (info.serverName.isBlank() && info.version.isNullOrBlank()) {
                error("jellyfin rejected the credentials")
            }
            ProviderIdentity(
                name = info.serverName.ifBlank { providerKey } +
                    " · " + SubsonicClient.shortHost(base),
                detail = info.version.orEmpty(),
            )
        }
    }

    /** [style] is one of the Roots in the browse screen (newest / a-z). */
    suspend fun albums(style: String): Result<List<JellyfinAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val sort = if (style == "newest") "DateCreated,SortName" else "SortName"
            val body = Http.text(
                get(
                    "Items",
                    mapOf(
                        "recursive" to "true",
                        "includeItemTypes" to "MusicAlbum",
                        "sortBy" to sort,
                        "sortOrder" to "Ascending",
                        "limit" to "200",
                    ),
                ),
                authHeaders(),
            )
            Http.json.decodeFromString<ItemsResult<JellyfinAlbumItem>>(body).items.map {
                JellyfinAlbum(
                    id = it.id,
                    name = it.name,
                    artist = it.albumArtist.orEmpty(),
                    songCount = it.childCount.takeIf { it > 0 } ?: 0,
                    year = it.productionYear ?: 0,
                )
            }
        }
    }

    suspend fun artists(): Result<List<JellyfinArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = Http.text(
                get(
                    "Artists",
                    mapOf("limit" to "200", "sortBy" to "SortName", "sortOrder" to "Ascending"),
                ),
                authHeaders(),
            )
            Http.json.decodeFromString<ItemsResult<JellyfinArtistItem>>(body).items.map {
                JellyfinArtist(it.id, it.name, 0)
            }
        }
    }

    suspend fun artistAlbums(artistId: String): Result<List<JellyfinAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = Http.text(
                get(
                    "Items",
                    mapOf(
                        "recursive" to "true",
                        "includeItemTypes" to "MusicAlbum",
                        "artistIds" to artistId,
                        "sortBy" to "SortName",
                        "sortOrder" to "Ascending",
                    ),
                ),
                authHeaders(),
            )
            Http.json.decodeFromString<ItemsResult<JellyfinAlbumItem>>(body).items.map {
                JellyfinAlbum(
                    id = it.id,
                    name = it.name,
                    artist = it.albumArtist.orEmpty(),
                    songCount = it.childCount.takeIf { it > 0 } ?: 0,
                    year = it.productionYear ?: 0,
                )
            }
        }
    }

    suspend fun albumTracks(albumId: String): Result<List<JellyfinTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = Http.text(
                get(
                    "Items",
                    mapOf(
                        "parentId" to albumId,
                        "recursive" to "true",
                        "includeItemTypes" to "Audio",
                        "sortBy" to "ParentIndexNumber,IndexNumber,SortName",
                        "fields" to "RunTimeTicks",
                    ),
                ),
                authHeaders(),
            )
            Http.json.decodeFromString<ItemsResult<JellyfinTrackItem>>(body).items.map {
                JellyfinTrack(
                    id = it.id,
                    title = it.name,
                    artist = it.artists.firstOrNull().orEmpty(),
                    album = it.album.orEmpty(),
                    duration = if (it.runTimeTicks > 0) (it.runTimeTicks / 10_000_000).toInt() else 0,
                    bitRate = it.bitRate,
                    suffix = it.container.orEmpty(),
                    albumArtist = it.albumArtists.firstOrNull().orEmpty(),
                    trackNo = it.indexNumber ?: 0,
                    discNo = it.parentIndexNumber ?: 0,
                    dateAdded = parseIsoEpochSeconds(it.dateCreated),
                )
            }
        }
    }

    /**
     * The stream to play. The token rides the URL because the player fetches
     * it without custom headers - `?ApiKey=` on Jellyfin (the lowercase
     * `api_key` died with the legacy auth in 12), `?api_key=` on Emby, which
     * still takes it. Emby has no `/Items/{id}/Download`: it streams the
     * original file through its audio endpoint instead.
     */
    suspend fun stream(id: String): ResolvedStream {
        ensureAuth()
        val t = authToken
        val q = if (t.isBlank()) "" else "$tokenParam=${enc(t)}"
        val path = if (modernAuth) "$base/Items/$id/Download" else "$base/Audio/$id/stream.mp3?static=true"
        // The token starts the query on Download and extends it on the
        // static Emby path; joining with & unconditionally folds it into
        // the path and the server 404s.
        val sep = if ('?' in path) "&" else "?"
        return ResolvedStream("$path${if (q.isEmpty()) "" else "$sep$q"}")
    }

    fun coverUrl(id: String, size: Int = 512): String {
        val url = "$base/Items/$id/Images/Primary?maxWidth=$size"
        val t = authToken
        // Artwork loads without headers too, so the token goes in the URL -
        // but only where the scheme wants it; Emby's form is untouched.
        return if (modernAuth && t.isNotBlank()) "$url&$tokenParam=${enc(t)}" else url
    }

    private suspend fun ensureAuth() {
        if (authToken.isBlank()) login()
    }

    private suspend fun login() {
        val body = Http.postJson(
            "$base/Users/AuthenticateByName",
            """{"Username":"${esc(user.trim())}","Pw":"${esc(password)}"}""",
            loginHeaders(),
        )
        val res = Http.json.decodeFromString<AuthenticateResult>(body)
        val t = res.accessToken.orEmpty()
        if (t.isBlank()) error("jellyfin rejected the credentials")
        cachedToken[base] = t
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): String {
        val all = buildMap {
            putAll(params)
            // Emby only: Jellyfin carries the token in the header instead.
            if (!modernAuth) authToken.takeIf { it.isNotBlank() }?.let { put("api_key", it) }
        }
        val query = all.entries.joinToString("&") { (k, v) ->
            "$k=" + enc(v)
        }
        return "$base/$path${if (query.isEmpty()) "" else "?$query"}"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    companion object {
        /** Tokens are exchanged for passwords per server and cached for the session. */
        private val cachedToken = java.util.concurrent.ConcurrentHashMap<String, String>()
        private fun cachedToken(base: String): String = cachedToken[base].orEmpty()
    }
}

/** A Jellyfin/Emby item collection: `{ "Items": [...] }`. */
@Serializable
private data class ItemsResult<T>(@SerialName("Items") val items: List<T> = emptyList())

@Serializable
private data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String = "",
    @SerialName("Version") val version: String = "",
)

@Serializable
private data class AuthenticateResult(
    @SerialName("AccessToken") val accessToken: String = "",
)

@Serializable
private data class JellyfinAlbumItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("ChildCount") val childCount: Int = 0,
    @SerialName("ProductionYear") val productionYear: Int? = null,
)

@Serializable
private data class JellyfinArtistItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

@Serializable
private data class JellyfinTrackItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("AlbumArtists") val albumArtists: List<String> = emptyList(),
    @SerialName("Album") val album: String = "",
    @SerialName("RunTimeTicks") val runTimeTicks: Long = 0L,
    @SerialName("BitRate") val bitRate: Int = 0,
    @SerialName("Container") val container: String = "",
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
)

data class JellyfinAlbum(
    val id: String,
    val name: String,
    val artist: String = "",
    val songCount: Int = 0,
    val year: Int = 0,
)

data class JellyfinArtist(val id: String, val name: String, val albumCount: Int = 0)

data class JellyfinTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val bitRate: Int = 0,
    val suffix: String = "",
    val albumArtist: String = "",
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val dateAdded: Long = 0,
)
