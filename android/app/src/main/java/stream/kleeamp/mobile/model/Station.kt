package stream.kleeamp.mobile.model

import kotlinx.serialization.Serializable

/** Where a station came from. Drives both grouping and the accent it gets. */
enum class StationSource { Cliamp, Directory, Custom, Local, Provider, Podcast }

@Serializable
data class Station(
    val id: String,
    val name: String,
    val url: String,
    val source: StationSource,
    val slug: String = "",
    val tags: String = "",
    val country: String = "",
    val countryCode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val votes: Int = 0,
    val homepage: String = "",
    val favicon: String = "",
    val uuid: String = "",
    /** Artwork URI for local songs and playlists (a content:// or file:// string). */
    val cover: String = "",
    /** Local-file metadata, empty for streams. */
    val artist: String = "",
    val album: String = "",
    /** Album artist tag (compilations keep one); empty when untagged. */
    val albumArtist: String = "",
    /** Track number within its disc (MediaStore TRACK), 0 when unknown. */
    val trackNo: Int = 0,
    /** Disc number within its album (MediaStore DISC_NUMBER), 0 when unknown. */
    val discNo: Int = 0,
    val durationMs: Long = 0L,
    /** When the file first entered the local media store, seconds since epoch. */
    val dateAdded: Long = 0L,
) {
    /** `mp3 · 128k · Germany`, skipping whatever the directory did not know. */
    val meta: String
        get() = buildList {
            if (codec.isNotBlank()) add(codec.lowercase())
            if (bitrate > 0) add("${bitrate}k")
            if (country.isNotBlank()) add(country.lowercase())
        }.joinToString(" · ")

    /** `artist · album` for local files, blank if neither is known. */
    val artistAlbum: String
        get() = listOf(artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /** `album artist · album`, falling back to track artist when album artist is blank. */
    val albumArtistAlbum: String
        get() = listOf(albumArtist.ifBlank { artist }, album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /**
     * A finite thing with an end, as opposed to a live stream. Decides whether
     * the queue is handed to the player as a real playlist it can advance
     * through, and it is source-based rather than duration-based because the
     * decision has to be made before anything has been probed. Channel tracks
     * count: they are files from a channel's track list, unlike the
     * channel's live stream.
     */
    val isTrack: Boolean
        get() = source == StationSource.Local ||
            source == StationSource.Provider ||
            source == StationSource.Podcast ||
            isChannelTrack

    /**
     * A cliamp channel's track: a seekable file from a channel's track list,
     * not the channel's live stream. The track id carries both
     * (`cliamp:channel:track`), while a live stream is just `cliamp:slug`.
     */
    val isChannelTrack: Boolean
        get() = source == StationSource.Cliamp && id.count { it == ':' } >= 2

    val tagList: List<String>
        get() = tags.split(',', ' ')
            .map { it.trim().lowercase() }
            .filter { it.length > 1 }
            .distinct()
}

/**
 * The one-line fallback under a station's name, per source. The mini player
 * shows this as its artist line when there is no live stream title.
 */
internal val Station.sourceLine: String
    get() = when (source) {
        StationSource.Cliamp -> "cliamp radio"
        StationSource.Local -> artistAlbum.ifBlank { "local audio" }
        StationSource.Podcast -> artist.ifBlank { "podcast" }
        StationSource.Provider -> artistAlbum.ifBlank { meta.ifBlank { "provider" } }
        else -> meta.ifBlank { "live stream" }
    }

/**
 * Segments that name the origin, never the music. Artist slots (player
 * row, mini bar, notification) drop any segment echoing these instead
 * of repeating what the source badge already says.
 */
internal val SourceEchoes = setOf(
    "cliamp radio",
    "directory",
    "on device",
    "provider",
    "podcast",
    "custom",
    "local audio",
    "live stream",
    "my music",
)

/** Join the first [take] segments minus any source echo. Pure for tests. */
internal fun deEchoArtistLine(segments: List<String>, take: Int): String = segments
    .map { it.trim() }
    .filter { it.isNotEmpty() && it.lowercase() !in SourceEchoes }
    .take(take)
    .joinToString(" · ")

/**
 * The [count] stations after index [i] of this list, wrapping around the end.
 * Radio-style lists play as a ring, and the widget's up-next row is exactly
 * this: the four stations that come next in the list being played. Kotlin's
 * `%` keeps the dividend's sign, so this positive-modulo form stays in range
 * for any [i].
 */
internal fun List<Station>.wrapNext(i: Int, count: Int = 4): List<Station> =
    (1..count).mapNotNull { k -> this[(i + k) % size] }

/** What the player is currently doing, projected out of Media3. */
data class NowPlaying(
    val station: Station? = null,
    val streamTitle: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
)
