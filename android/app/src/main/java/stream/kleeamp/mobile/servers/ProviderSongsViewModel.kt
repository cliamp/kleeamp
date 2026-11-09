package stream.kleeamp.mobile.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station

/**
 * The "providers" playlist: every connected account's songs, flat, with a
 * chip per account to narrow it down.
 *
 * Each account loads the way the browse screen does - its A-Z albums, then
 * each album's tracks - converted to [Station]s with the provider's own
 * cover URLs. Accounts load concurrently and fail independently: one
 * unreachable server leaves the others' songs in place and records its own
 * failure line rather than failing the whole list. Loaded songs are kept
 * per account id, so adding or removing an account only (re)loads what
 * changed instead of refetching every library.
 *
 * Each album's tracks are kept in the server's own order (track number),
 * never re-sorted here, so the artist drill can play an album back exactly
 * as it was released; the pane groups them without disturbing that order.
 */
class ProviderSongsViewModel(
    private val store: ProviderStore,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val accounts: List<ProviderAccount> = emptyList(),
        val songsByAccount: Map<String, List<Station>> = emptyMap(),
        val librariesByAccount: Map<String, List<ProviderLibrary>> = emptyMap(),
        val loading: Boolean = false,
        val failures: Map<String, String> = emptyMap(),
        val favorites: List<Station> = emptyList(),
    ) {
        val totalSongs: Int get() = songsByAccount.values.sumOf { it.size }
    }

    sealed interface Event {
        data object Refresh : Event
        data class ToggleFavorite(val station: Station) : Event
        data class SetLibrary(val accountId: String, val libraryId: String) : Event
    }

    private val songsByAccount = MutableStateFlow<Map<String, List<Station>>>(emptyMap())
    private val librariesByAccount = MutableStateFlow<Map<String, List<ProviderLibrary>>>(emptyMap())
    private val failures = MutableStateFlow<Map<String, String>>(emptyMap())
    private val loading = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        combine(
            store.accounts,
            songsByAccount,
            failures,
            loading,
            ::SongsState,
        ),
        librariesByAccount,
        prefs.favorites,
    ) { songs, libraries, favorites ->
        UiState(
            accounts = songs.accounts,
            songsByAccount = songs.songs,
            librariesByAccount = libraries,
            loading = songs.loading,
            failures = songs.failures,
            favorites = favorites,
        )
    }.stateInUi(
        viewModelScope,
        UiState(),
    )

    private data class SongsState(
        val accounts: List<ProviderAccount> = emptyList(),
        val songs: Map<String, List<Station>> = emptyMap(),
        val failures: Map<String, String> = emptyMap(),
        val loading: Boolean = false,
    )

    init {
        viewModelScope.launch {
            store.accounts.collectLatest { accounts -> load(accounts) }
        }
    }

    fun onEvent(e: Event) {
        when (e) {
            Event.Refresh -> viewModelScope.launch { load(store.read()) }
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
            // Evict before saving: the accounts flow re-triggers a load on
            // the save, which must find an empty cache to refetch.
            is Event.SetLibrary -> viewModelScope.launch {
                val account = store.read().firstOrNull { it.id == e.accountId } ?: return@launch
                songsByAccount.value = songsByAccount.value - e.accountId
                failures.value = failures.value - e.accountId
                store.save(account.copy(values = account.values + ("library" to e.libraryId)))
            }
        }
    }

    private suspend fun load(accounts: List<ProviderAccount>) {
        // Forget removed accounts; keep the rest so only new or changed
        // accounts fetch.
        val ids = accounts.map { it.id }.toSet()
        songsByAccount.value = songsByAccount.value.filterKeys { it in ids }
        librariesByAccount.value = librariesByAccount.value.filterKeys { it in ids }
        failures.value = failures.value.filterKeys { it in ids }
        val missing = accounts.filter { it.id !in songsByAccount.value }
        val missingLibraries = accounts
            .filter { it.providerKey == "plex" || it.providerKey == "abs" }
            .filter { it.id !in librariesByAccount.value }
        if (missing.isEmpty() && missingLibraries.isEmpty()) return
        loading.value = true
        try {
            supervisorScope {
                (missing + missingLibraries).distinct().map { account ->
                    async {
                        if (account in missing) {
                            runCatching { loadProviderSongs(account) }
                                .onSuccess { songs ->
                                    songsByAccount.value = songsByAccount.value + (account.id to songs)
                                    failures.value = failures.value - account.id
                                }
                                .onFailure { t ->
                                    failures.value = failures.value +
                                        (account.id to (t.message ?: "could not load"))
                                }
                        }
                        if (account.providerKey == "plex" || account.providerKey == "abs") {
                            runCatching { account.browseClient().libraries().getOrThrow() }
                                .onSuccess { libs ->
                                    librariesByAccount.value =
                                        librariesByAccount.value + (account.id to libs)
                                }
                        }
                    }
                }.forEach { it.await() }
            }
        } finally {
            loading.value = false
        }
    }
}
