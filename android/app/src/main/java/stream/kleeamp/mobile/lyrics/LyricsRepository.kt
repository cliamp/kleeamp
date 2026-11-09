package stream.kleeamp.mobile.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.prefs.Prefs
import java.io.File
import java.security.MessageDigest

/**
 * Lyrics in three steps, cheapest first: the disk cache, LRCLib, then NetEase
 * like cliamp.
 *
 * A miss is cached too (for a week) - otherwise every open of the same
 * untagged track re-runs the whole search with backoff. Fresh lookups ride the
 * patient lyrics client and follow the same cellular switch as streams and
 * downloads, while the cache keeps serving on mobile data. Lyrics never
 * change, so results carry no TTL - just a file cap with oldest-first prune.
 */
class LyricsRepository(private val context: Context, private val prefs: Prefs) {

    sealed interface Result {
        /** Synced lines (possibly empty) plus the plain text fallback. */
        data class Lines(val lines: List<LrcLine>, val plain: String) : Result
        data object Instrumental : Result
        /** Searched; no source has anything for this song. */
        data object NotFound : Result
        /** The request itself failed; worth a retry. */
        data object Offline : Result
        /** Cellular is off and the network is metered; nothing was sent. */
        data object Restricted : Result
    }

    private val dir = File(context.cacheDir, "lyrics").apply { mkdirs() }

    /**
     * @param refresh bypass a cached absence and the cellular rail. Only an
     * explicit user retry sets it; a cached *result* is never bypassed since
     * lyrics do not change.
     */
    suspend fun fetch(query: LyricsQuery, refresh: Boolean = false): Result {
        val stored = disk(query)
        when {
            stored == null -> Unit
            stored.absent -> if (!refresh && !stored.absenceExpired()) return Result.NotFound
            else -> return stored.toResult()
        }
        // Fresh lookups follow the cellular switch; the cache above still
        // serves on mobile data, so nothing already fetched is walled off.
        if (!refresh && !prefs.cellular.first() && metered()) return Result.Restricted
        val found = remote(query)
        when (found) {
            is Result.Lines, Result.Instrumental -> save(query, found)
            Result.NotFound -> saveAbsence(query)
            Result.Offline, Result.Restricted -> Unit
        }
        return found
    }

    /**
     * LRCLib first (exact metadata, then free-text search), NetEase after it.
     * Every step is cleaned first: a title wearing `(Official Video)` finds
     * nothing, which is the single most common reason a real song reads as
     * "no lyrics".
     */
    @Suppress("ReturnCount")
    private suspend fun remote(query: LyricsQuery): Result {
        val artist = cleanLookup(query.artist)
        val title = cleanLookup(query.title)
        if (title.isBlank()) return Result.NotFound
        val exact = get(artist, title, cleanLookup(query.album), query.durationSec)
        if (exact is Result.Lines || exact is Result.Instrumental) return exact
        // A dead first call stays dead for the fallbacks too.
        if (exact is Result.Offline) return exact
        // Free text is more forgiving than the structured endpoint; artist may
        // be a channel name or an uploader, so title alone goes second.
        for (text in lookups(artist, title)) {
            val hit = search(text)
            if (hit is Result.Lines || hit is Result.Instrumental) return hit
            if (hit is Result.Offline) return hit
        }
        // LRCLIB had nothing: NetEase carries a lot of the same catalogue,
        // and cliamp already falls back there before giving up.
        return netEase(artist, title)
    }

    /** Artist+title first, then title alone: a wrong tag should not hide lyrics. */
    private fun lookups(artist: String, title: String): List<String> =
        listOf("$artist $title".trim(), title).filter { it.isNotBlank() }.distinct()

    /** Metered networks need the cellular opt-in; unknown state still fetches. */
    private fun metered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun fileFor(query: LyricsQuery): File {
        val name = MessageDigest.getInstance("MD5")
            .digest(query.cacheKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }

    private suspend fun disk(query: LyricsQuery): Stored? = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(query)
            if (!f.isFile) return@runCatching null
            Http.json.decodeFromString<Stored>(f.readText())
        }.getOrNull()
    }

    private fun save(query: LyricsQuery, result: Result) {
        if (result !is Result.Lines && result !is Result.Instrumental) return
        runCatching {
            val stored = when (result) {
                is Result.Lines -> Stored(result.plain, render(result.lines), false)
                Result.Instrumental -> Stored("", "", true)
                Result.NotFound, Result.Offline, Result.Restricted -> return
            }
            fileFor(query).writeText(Http.json.encodeToString(Stored.serializer(), stored))
            prune()
        }
    }

    /** Remembers "nothing there" so the next open answers instantly. */
    private fun saveAbsence(query: LyricsQuery) {
        runCatching {
            val stored = Stored(absentAt = System.currentTimeMillis())
            fileFor(query).writeText(Http.json.encodeToString(Stored.serializer(), stored))
            prune()
        }
    }

    private fun Stored.absenceExpired(now: Long = System.currentTimeMillis()): Boolean =
        now - absentAt > ABSENT_TTL_MS

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

    /**
     * Free-text search. The hit's lyrics arrive inline, so a match costs one
     * request instead of a search plus a follow-up get.
     */
    private suspend fun search(text: String): Result {
        val url = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", text)
            ?.build() ?: return Result.Offline
        return when (val call = call(url.toString())) {
            is Call.Body -> runCatching {
                Http.json.decodeFromString<List<SearchItem>>(call.text)
            }.getOrNull()?.toResult() ?: Result.Offline
            Call.Absent -> Result.NotFound
            Call.Failed -> Result.Offline
        }
    }

    /**
     * NetEase Cloud Music, cliamp's second source. Search takes a plain
     * "artist title" string, then the lyric endpoint takes the first hit's
     * id. Mirrors cliamp's flow, on https so it needs no cleartext exemption.
     */
    @Suppress("ReturnCount")
    private suspend fun netEase(artist: String, title: String): Result {
        val text = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank()) return Result.NotFound
        val search = send(
            Request.Builder()
                .url("$NETEASE/api/cloudsearch/pc")
                .header("Referer", NETEASE)
                .post(
                    FormBody.Builder()
                        .add("s", text)
                        .add("type", "1")
                        .add("limit", "1")
                        .add("offset", "0")
                        .build()
                )
                .build()
        )
        val songs = when (search) {
            is Call.Body -> runCatching {
                Http.json.decodeFromString<NcmSearch>(search.text)
            }.getOrNull()?.result?.songs.orEmpty()
            Call.Absent -> return Result.NotFound
            Call.Failed -> return Result.Offline
        }
        val id = songs.firstOrNull()?.id ?: return Result.NotFound
        val lyricCall = send(
            Request.Builder()
                .url("$NETEASE/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
                .header("Referer", NETEASE)
                .build()
        )
        val lyric = when (lyricCall) {
            is Call.Body -> runCatching {
                Http.json.decodeFromString<NcmLyric>(lyricCall.text)
            }.getOrNull()?.lrc?.lyric.orEmpty()
            Call.Absent -> return Result.NotFound
            Call.Failed -> return Result.Offline
        }
        if (lyric.isBlank()) return Result.NotFound
        val lines = parseLrc(lyric)
        // Unstamped lyric sheets still read fine as plain text.
        return if (lines.isEmpty()) Result.Lines(emptyList(), lyric.trim()) else Result.Lines(lines, lyric)
    }

    /** Transport outcome: a 404 is absence, anything else failing is offline. */
    private sealed interface Call {
        data class Body(val text: String) : Call
        data object Absent : Call
        data object Failed : Call
    }

    private suspend fun call(url: String): Call =
        send(Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build())

    private suspend fun send(request: Request): Call = withContext(Dispatchers.IO) {
        runCatching {
            Http.lyricsClient.newCall(request).execute().use { r ->
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

    private fun List<SearchItem>.toResult(): Result {
        // Prefer a synced hit anywhere in the page, then any plain one.
        firstOrNull { !it.syncedLyrics.isNullOrBlank() }?.let {
            return Result.Lines(parseLrc(it.syncedLyrics.orEmpty()), it.plainLyrics.orEmpty())
        }
        firstOrNull { !it.plainLyrics.isNullOrBlank() }?.let {
            return Result.Lines(emptyList(), it.plainLyrics.orEmpty())
        }
        return Result.NotFound
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
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    @Serializable
    private data class NcmSearch(val result: NcmResult? = null)

    @Serializable
    private data class NcmResult(val songs: List<NcmSong> = emptyList())

    @Serializable
    private data class NcmSong(val id: Long = 0)

    @Serializable
    private data class NcmLyric(val lrc: NcmLrc? = null)

    @Serializable
    private data class NcmLrc(val lyric: String = "")

    @Serializable
    private data class Stored(
        val plain: String = "",
        val synced: String = "",
        val instrumental: Boolean = false,
        /** Epoch ms of a cached "no lyrics" answer; zero for a real result. */
        val absentAt: Long = 0,
    ) {
        val absent: Boolean get() = absentAt > 0

        fun toResult(): Result = when {
            absent -> Result.NotFound
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
        const val NETEASE = "https://music.163.com"
    }
}

/** How long a cached miss stands before the next open tries again. */
private const val ABSENT_TTL_MS = 7L * 24 * 60 * 60 * 1000
