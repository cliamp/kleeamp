package stream.kleeamp.mobile.servers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.playback.ResolvedStream

/**
 * Audiobookshelf serves books and podcasts, but the browse screen treats a
 * library and its items the way it treats albums and tracks, which reads fine:
 * items of a music library are albums, and the audio files of an item are its
 * tracks. Auth is an API key or a bearer token from a password login, and - like
 * Plex - the stream URL carries the token (`?token=`), so playback needs no
 * custom headers.
 */
class AudiobookshelfClient(
    rawUrl: String,
    private val token: String,
    private val user: String,
    private val password: String,
    /**
     * The picked library id. Blank keeps the legacy behaviour: the first
     * library the server lists, whatever kind it is.
     */
    private val library: String = "",
) {
    private val base = SubsonicClient.normalise(rawUrl)

    /** The bearer token is a config API key or a login-session token. */
    @Volatile private var bearer: String = token

    /**
     * A fresh client at play time has never logged in, so a password-mode
     * account would stream with an empty token and get a 401. Like
     * Jellyfin, fall back to the session's exchanged token for this server.
     */
    private val auth: String get() = bearer.ifBlank { cachedToken(base) }

    suspend fun ping(): Result<ProviderIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            if (bearer.isBlank()) login()
            val body = Http.text("$base/api/libraries", headers())
            val libs = Http.json.decodeFromString<Libraries>(body).libraries
            ProviderIdentity(
                name = "audiobookshelf · " + SubsonicClient.shortHost(base),
                detail = libs.joinToString(" · ") { it.name }.takeIf { it.isNotBlank() }.orEmpty(),
            )
        }
    }

    suspend fun albums(): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val lib = resolveLibrary()
            val body = Http.text(
                "$base/api/libraries/$lib/items?limit=200&page=0&sort=media.metadata.title",
                headers(),
            )
            Http.json.decodeFromString<LibraryItems>(body).results.map { item ->
                val meta = item.media?.metadata
                ProviderAlbum(
                    id = item.id,
                    name = meta?.title.orEmpty().ifBlank { "untitled" },
                    artist = meta?.authorName.orEmpty(),
                    songCount = item.media?.audioFiles?.size ?: 0,
                    year = meta?.year ?: 0,
                )
            }
        }
    }

    suspend fun albumTracks(itemId: String): Result<List<ProviderTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = Http.text("$base/api/items/$itemId?expanded=1", headers())
            val item = Http.json.decodeFromString<ItemDetail>(body)
            val meta = item.media?.metadata
            val files = item.media?.audioFiles.orEmpty()
            files.map { f ->
                ProviderTrack(
                    // The stream needs both the item and the file inode.
                    id = "$itemId::${f.ino}",
                    title = f.metadata?.locTitle.orEmpty().ifBlank { meta?.title.orEmpty() },
                    artist = meta?.authorName.orEmpty(),
                    album = meta?.albumTitle.orEmpty().ifBlank { meta?.title.orEmpty() },
                    duration = (f.duration ?: 0).toInt(),
                    codec = f.metadata?.ext.orEmpty(),
                    albumArtist = meta?.authorName.orEmpty(),
                )
            }
        }
    }

    suspend fun stream(ref: String): ResolvedStream {
        ensureAuth()
        val itemId = ref.substringBefore("::")
        val ino = ref.substringAfter("::", "")
        return ResolvedStream("$base/api/items/$itemId/file/$ino?token=${enc(auth)}")
    }

    fun coverUrl(itemId: String): String {
        val t = auth.ifBlank { token }
        return if (t.isBlank()) "" else "$base/api/items/$itemId/cover?token=${enc(t)}"
    }

    private suspend fun ensureAuth() {
        if (auth.isBlank()) login()
    }

    private fun headers(): Map<String, String> =
        if (auth.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $auth")

    private suspend fun login() {
        val body = Http.postJson(
            "$base/login",
            """{"username":"${esc(user)}","password":"${esc(password)}"}""",
        )
        val res = Http.json.decodeFromString<LoginResult>(body)
        val t = res.user?.token.orEmpty()
        if (t.isBlank()) error("audiobookshelf rejected the credentials")
        bearer = t
        cachedToken[base] = t
    }

    /** Every library, for the library picker. Empty when none exist. */
    suspend fun libraries(): Result<List<ProviderLibrary>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            fetchLibraries().map { ProviderLibrary(it.id, it.name.ifBlank { "library" }) }
        }
    }

    private suspend fun resolveLibrary(): String {
        val libs = fetchLibraries()
        return libs.firstOrNull { it.id == library }?.id
            ?: libs.firstOrNull()?.id.orEmpty()
                .takeIf { it.isNotBlank() } ?: error("no audiobookshelf library found")
    }

    private suspend fun fetchLibraries(): List<LibraryBrief> {
        val body = Http.text("$base/api/libraries", headers())
        return Http.json.decodeFromString<Libraries>(body).libraries
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

@Serializable
private data class Libraries(val libraries: List<LibraryBrief> = emptyList())

@Serializable
private data class LibraryBrief(val id: String = "", val name: String = "", val mediaType: String = "")

@Serializable
private data class LoginResult(val user: LoginUser? = null)

@Serializable
private data class LoginUser(val token: String = "")

@Serializable
private data class LibraryItems(val results: List<ItemBrief> = emptyList())

@Serializable
private data class ItemBrief(
    val id: String = "",
    val media: ItemMedia? = null,
)

@Serializable
private data class ItemMedia(
    val metadata: ItemMetadata? = null,
    val audioFiles: List<AudioFile> = emptyList(),
)

@Serializable
private data class ItemMetadata(
    val title: String? = null,
    val authorName: String? = null,
    val albumTitle: String? = null,
    val year: Int? = null,
)

@Serializable
private data class ItemDetail(
    val id: String = "",
    val media: ItemMedia? = null,
)

@Serializable
private data class AudioFile(
    val ino: String = "",
    val duration: Double? = null,
    val metadata: FileMetadata? = null,
)

@Serializable
private data class FileMetadata(val locTitle: String? = null, val ext: String? = null)
