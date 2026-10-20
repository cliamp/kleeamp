package stream.kleeamp.mobile

import kotlinx.serialization.Serializable

/**
 * The three tabs are pages of one pager, not three graphs: Home is the single
 * destination that holds them. Swiping between Stations, Podcasts and Library
 * never touches the back stack, so back from any tab exits natively the way
 * the start tab always did.
 */
@Serializable data object Home

@Serializable data object Player
@Serializable data object UpNext
@Serializable data object Scope
@Serializable data object Settings
@Serializable data object Search

@Serializable data class ProviderWizardRoute(val providerKey: String, val accountId: String = "")

@Serializable data object ScrobbleWizard

@Serializable data object CustomStationWizard

@Serializable data class PodcastShowRoute(val podcastId: String)

/** One cliamp radio channel as its track list, by channel id. */
@Serializable data class CliampChannelRoute(val channelId: String)

@Serializable data object LibraryProviders
/** Blank [accountId] shows every account with the picker; set locks to one. */
@Serializable data class LibraryProviderSongs(val accountId: String = "")
@Serializable data class LibrarySmartPlaylist(val kind: String)
@Serializable data class LibraryPlaylist(val slug: String)
/**
 * One artist's tracks, scoped strictly to that list: a playlist slug or
 * "local-songs". [coverUrl] is the row's first song, so the page header wears
 * the same image the artist row did.
 */
@Serializable data class LibraryArtist(val name: String, val context: String, val coverUrl: String = "")
/**
 * One album's tracks, keyed by album + album artist so compilations stay one.
 * [artist] is set when opened from an artist page, narrowing the album to that
 * artist's tracks instead of every library song with the same album tag.
 */
@Serializable data class LibraryAlbum(
    val name: String,
    val albumArtist: String,
    val context: String,
    val artist: String = "",
)
@Serializable data class LibrarySongInfo(val stationUrl: String)
