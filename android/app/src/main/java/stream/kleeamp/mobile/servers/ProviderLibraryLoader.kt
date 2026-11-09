package stream.kleeamp.mobile.servers

import stream.kleeamp.mobile.model.Station

/**
 * Flattens one account's library into the same [Station] shape the local
 * library uses. Every provider goes through the same path: list its albums,
 * then each album's tracks, filling the album title and album artist from the
 * album when the track payload omits them. Album order is kept and missing
 * track numbers get their list index so the local grouping/sorting code can
 * be used unchanged.
 */
suspend fun loadProviderSongs(account: ProviderAccount): List<Station> {
    val client = account.browseClient()
    val albums = client.albums("az").getOrThrow()
    // Station ids key the song list, so they must be unique: servers can
    // repeat a track across overlapping listings, and the same id twice
    // crashes the list outright.
    return albums.flatMap { album ->
        client.albumTracks(album.id).getOrDefault(emptyList())
            .mapIndexed { index, track ->
                track.toStation(
                    account = account,
                    cover = client.trackCover(track.id),
                    fallbackAlbum = album.name,
                    fallbackAlbumArtist = album.artist,
                    fallbackTrackNo = index + 1,
                    fallbackDiscNo = 1,
                )
            }
    }.distinctBy { it.id }
}
