package stream.kleeamp.mobile.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import stream.kleeamp.mobile.net.Http
import java.io.File
import java.security.MessageDigest

/**
 * LRCLib lyrics with a disk cache. Decorative traffic, so it rides the
 * tight-timeout art client: a dead lyrics lookup fails fast into the
 * no-lyrics state instead of holding a row hostage. Lyrics never change,
 * so cached files carry no TTL - just a file cap with oldest-first prune.
 */
class LyricsRepository(context: Context) {

    sealed interface Result {
        /** Synced lines (possibly empty) plus the plain text fallback. */
        data class Lines(val lines: List<LrcLine>, val plain: String) : Result
        data object Instrumental : Result
        /** Searched; the service has nothing for this song. */
        data object NotFound : Result
        /** The request itself failed; worth a retry. */
        data object Offline : Result
    }

    private val dir = File(context.cacheDir, "lyrics").apply { mkdirs() }

    suspend fun fetch(query: LyricsQuery): Result {
        disk(query)?.let { return it }
        val direct = get(query.artist, query.title, query.album, query.durationSec)
        if (direct is Result.Lines || direct is Result.Instrumental) {
            save(query, direct)
            return direct
        }
        // A dead first call stays dead for the fallback too.
        if (direct is Result.Offline) return Result.Offline
        // Absent: the metadata fallback re-fetches by the hit's own
        // artist/title, through the same parse path as a direct get.
        val fallback = search(query)?.let { get(it.first, it.second, "", 0) }
            ?: return Result.NotFound
        if (fallback is Result.Lines || fallback is Result.Instrumental) save(query, fallback)
        return fallback
    }

    private fun fileFor(query: LyricsQuery): File {
        val name = MessageDigest.getInstance("MD5")
            .digest(query.cacheKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }

    private suspend fun disk(query: LyricsQuery): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(query)
            if (!f.isFile) return@runCatching null
            Http.json.decodeFromString<Stored>(f.readText()).toResult()
        }.getOrNull()
    }

    private fun save(query: LyricsQuery, result: Result) {
        if (result !is Result.Lines && result !is Result.Instrumental) return
        runCatching {
            val stored = when (result) {
                is Result.Lines -> Stored(result.plain, render(result.lines), false)
                Result.Instrumental -> Stored("", "", true)
                Result.NotFound, Result.Offline -> return
            }
            fileFor(query).writeText(Http.json.encodeToString(Stored.serializer(), stored))
            prune()
        }
    }

    private fun prune() {
        dir.listFiles()
            ?.sortedBy { it.lastModified() }
            ?.dropLast(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    private suspend fun get(artist: String, title: String, album: String, durationSec: Long): Result {
        val url = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("artist_name", artist)
            ?.addQueryParameter("track_name", title)
            ?.apply {
                if (album.isNotBlank()) addQueryParameter("album_name", album)
                if (durationSec > 0) addQueryParameter("duration", durationSec.toString())
            }
            ?.build() ?: return Result.Offline
        return when (val call = call(url.toString())) {
            is Call.Body -> runCatching {
                Http.json.decodeFromString<GetResponse>(call.text)
            }.getOrNull()?.toResult() ?: Result.Offline
            Call.Absent -> Result.NotFound
            Call.Failed -> Result.Offline
        }
    }

    /** Metadata fallback: the first search hit carrying synced lyrics. */
    private suspend fun search(query: LyricsQuery): Pair<String, String>? {
        val url = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("track_name", query.title)
            ?.apply { if (query.artist.isNotBlank()) addQueryParameter("artist_name", query.artist) }
            ?.build() ?: return null
        val body = when (val call = call(url.toString())) {
            is Call.Body -> call.text
            Call.Absent, Call.Failed -> return null
        }
        val items = runCatching {
            Http.json.decodeFromString<List<SearchItem>>(body)
        }.getOrNull() ?: return null
        val hit = items.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: return null
        return (hit.artistName.orEmpty()) to (hit.trackName.orEmpty())
    }

    /** Transport outcome: a 404 is absence, anything else failing is offline. */
    private sealed interface Call {
        data class Body(val text: String) : Call
        data object Absent : Call
        data object Failed : Call
    }

    private suspend fun call(url: String): Call = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
            Http.lyricsClient.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> Call.Body(r.body.string())
                    r.code == 404 -> Call.Absent
                    else -> Call.Failed
                }
            }
        }.getOrDefault(Call.Failed)
    }

    private fun GetResponse.toResult(): Result = when {
        instrumental -> Result.Instrumental
        !syncedLyrics.isNullOrBlank() ->
            Result.Lines(parseLrc(syncedLyrics), plainLyrics.orEmpty())
        plainLyrics?.isNotBlank() == true -> Result.Lines(emptyList(), plainLyrics)
        else -> Result.NotFound
    }

    private fun render(lines: List<LrcLine>): String =
        lines.joinToString("\n") { "[${it.timeMs}]${it.text}" }

    @Serializable
    private data class GetResponse(
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        val instrumental: Boolean = false,
    )

    @Serializable
    private data class SearchItem(
        val trackName: String? = null,
        val artistName: String? = null,
        val syncedLyrics: String? = null,
    )

    @Serializable
    private data class Stored(
        val plain: String = "",
        val synced: String = "",
        val instrumental: Boolean = false,
    ) {
        fun toResult(): Result = when {
            instrumental -> Result.Instrumental
            synced.isNotBlank() -> Result.Lines(parseStored(synced), plain)
            plain.isNotBlank() -> Result.Lines(emptyList(), plain)
            else -> Result.NotFound
        }

        companion object {
            fun parseStored(rendered: String): List<LrcLine> = rendered.lineSequence().mapNotNull { line ->
                val ms = line.substringAfter('[', "").substringBefore(']', "").toLongOrNull()
                    ?: return@mapNotNull null
                LrcLine(ms, line.substringAfter(']'))
            }.toList()
        }
    }

    private companion object {
        const val MAX_FILES = 200
    }
}
