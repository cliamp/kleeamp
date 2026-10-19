package stream.kleeamp.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.PodcastRepository
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

class PlaylistDetailViewModel(
    private val slug: String,
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
    private val repository: RadioRepository,
    private val podcasts: PodcastRepository,
) : ViewModel() {
    data class UiState(
        val playlist: PlaylistStore.Playlist? = null,
        val songIds: List<String> = emptyList(),
        val members: List<Station> = emptyList(),
        val visible: List<Station> = emptyList(),
        val sort: PlaylistSort = PlaylistSort.Title,
        val browse: Boolean = false,
        val localSongs: List<Station> = emptyList(),
        val radioStations: List<Station> = emptyList(),
        val subscribedShows: List<PodcastShow> = emptyList(),
        val showState: ShowState = ShowState(),
        val favorites: List<Station> = emptyList(),
        /** Repeat ids beyond the first occurrence. */
        val dupCount: Int = 0,
        /** Stored ids with nothing playable behind them. */
        val missingCount: Int = 0,
    )

    sealed interface Event {
        data class ToggleMember(val station: Station) : Event
        data class RemoveMember(val station: Station) : Event
        data class ToggleFavorite(val station: Station) : Event
        data class SetCover(val cover: String) : Event
        data class SetSort(val sort: PlaylistSort) : Event
        data class SetBrowse(val browsing: Boolean) : Event
        data class OpenShow(val show: PodcastShow) : Event
        data object Dedupe : Event
        data object PurgeMissing : Event
    }

    private data class CollectionState(
        val songs: List<Station> = emptyList(),
        val all: List<PlaylistStore.Playlist> = emptyList(),
        val subscriptions: List<PodcastShow> = emptyList(),
        val cliamp: List<Station> = emptyList(),
    )

    private data class MetaState(
        val directory: DirectoryState = DirectoryState(),
        val favorites: List<Station> = emptyList(),
        val sort: PlaylistSort = PlaylistSort.Title,
        val browse: Boolean = false,
        val showState: ShowState = ShowState(),
    )

    val state: StateFlow<UiState> = combine(
        combine(
            localLibrary.songs,
            playlists.playlists,
            podcasts.subscriptions,
            repository.cliamp,
            ::CollectionState,
        ),
        combine(
            combine(
                repository.directory,
                prefs.favorites,
                prefs.playlistSort(slug),
                podcasts.show,
            ) { directory, favorites, sort, show ->
                MetaState(directory, favorites, sort, showState = show)
            },
            prefs.playlistBrowse(slug),
        ) { meta, browse -> meta.copy(browse = browse) },
    ) { col, meta ->
        val pl = col.all.firstOrNull { it.station.slug == slug }
        val ids = pl?.songIds.orEmpty()
        val members = if (ids.isEmpty()) emptyList() else playlists.resolveMembers(ids, col.songs)
        val memberIds = members.map { it.id }.toSet()
        val favRadio = meta.favorites.filterNot {
            it.source == StationSource.Local || it.source == StationSource.Podcast
        }
        UiState(
            playlist = pl,
            songIds = ids,
            members = members,
            visible = sortedStations(members, meta.sort),
            sort = meta.sort,
            browse = meta.browse,
            localSongs = col.songs,
            radioStations = (col.cliamp + meta.directory.stations + favRadio).distinctBy { it.id },
            subscribedShows = col.subscriptions,
            showState = meta.showState,
            favorites = meta.favorites,
            dupCount = ids.size - ids.toSet().size,
            missingCount = (ids.toSet() - memberIds).size,
        )
    }.stateInUi(
        viewModelScope,
        UiState(
            sort = prefs.playlistSortValue(slug),
            browse = prefs.playlistBrowseValue(slug),
            localSongs = localLibrary.songs.value,
            showState = podcasts.show.value,
        ),
    )

    fun onEvent(e: Event) {
        when (e) {
            // Direction resolves store-side from fresh state, and the write
            // is not cancellable by navigating away: a tap is user intent
            // that must land, not screen-owned work.
            is Event.ToggleMember -> viewModelScope.launch(NonCancellable) {
                playlists.toggleStation(slug, e.station)
            }
            is Event.RemoveMember -> viewModelScope.launch(NonCancellable) {
                playlists.removeSong(slug, e.station.id)
            }
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
            is Event.SetCover -> viewModelScope.launch { playlists.setCover(slug, e.cover) }
            is Event.SetSort -> prefs.setPlaylistSort(slug, e.sort)
            is Event.SetBrowse -> prefs.setPlaylistBrowse(slug, e.browsing)
            is Event.OpenShow -> podcasts.openShow(e.show)
            Event.Dedupe -> viewModelScope.launch(NonCancellable) {
                val ids = state.value.songIds
                val (kept, _) = dedupeIds(ids)
                if (kept.size != ids.size) playlists.setOrder(slug, kept)
            }
            Event.PurgeMissing -> viewModelScope.launch(NonCancellable) {
                val ids = state.value.songIds
                val live = state.value.members.map { it.id }.toSet()
                val kept = ids.filter { it in live }
                if (kept.size != ids.size) playlists.setOrder(slug, kept)
            }
        }
    }

    /** M3U text for the resolved members, or null when there is nothing to share. */
    suspend fun exportM3u(): String? {
        val members = state.value.members
        if (members.isEmpty()) return null
        return renderM3u(members)
    }
}
