package stream.kleeamp.mobile.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.radio.DirectoryStats
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_BACK_SEC
import stream.kleeamp.mobile.prefs.DEFAULT_SKIP_FWD_SEC
import stream.kleeamp.mobile.podcasts.CleanupScope
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.theme.KleeampPalette
import stream.kleeamp.mobile.theme.decodeCustomThemeOrNull
import stream.kleeamp.mobile.theme.customThemeNameOrNull
import stream.kleeamp.mobile.theme.parseCustomTheme

class SettingsViewModel(
    private val prefs: Prefs,
    private val repository: RadioRepository,
    private val scrobbler: Scrobbler,
) : ViewModel() {
    data class UiState(
        val palette: String = "dark",
        val custom: KleeampPalette? = null,
        val customName: String? = null,
        val importError: String? = null,
        val haptics: Boolean = true,
        val visualizer: String = "spectrum",
        val cellular: Boolean = true,
        val mono: Boolean = false,
        val buffer: Int = 20,
        val autoResume: Boolean = false,
        val resumePodcasts: Boolean = true,
        val autoDownload: Boolean = false,
        val autoCellular: Boolean = false,
        val cleanupPlayed: CleanupScope = CleanupScope.Off,
        val parallelDownloads: Int = 3,
        val resumeLocal: Boolean = false,
        val speedPerSection: Boolean = true,
        val boostPerSection: Boolean = true,
        val skipBackSec: Int = DEFAULT_SKIP_BACK_SEC,
        val skipFwdSec: Int = DEFAULT_SKIP_FWD_SEC,
        val listenBrainzOn: Boolean = false,
        val scrobbleRadio: Boolean = false,
        val scrobblePending: Int = 0,
        val scrobbleError: String? = null,
        val favoritesCount: Int = 0,
        val historyCount: Int = 0,
        val directoryStats: DirectoryStats? = null,
        val listenerGlobe: Boolean = true,
    )

    sealed interface Event {
        data class SetPalette(val key: String) : Event
        data class SetAutoResume(val v: Boolean) : Event
        data class SetResumePodcasts(val v: Boolean) : Event
        data class SetResumeLocal(val v: Boolean) : Event
        data class SetAutoDownload(val v: Boolean) : Event
        data class SetAutoCellular(val v: Boolean) : Event
        data class SetCleanupPlayed(val v: CleanupScope) : Event
        data class SetParallelDownloads(val v: Int) : Event
        data class SetCellular(val v: Boolean) : Event
        data class SetMono(val v: Boolean) : Event
        data class SetBuffer(val seconds: Int) : Event
        data class SetSkipBackSec(val seconds: Int) : Event
        data class SetSkipFwdSec(val seconds: Int) : Event
        data class SetSpeedPerSection(val v: Boolean) : Event
        data class SetBoostPerSection(val v: Boolean) : Event
        data class SetHaptics(val v: Boolean) : Event
        data class SetListenerGlobe(val v: Boolean) : Event
        data class SetVisualizer(val v: String) : Event
        data class ImportTheme(val raw: String?) : Event
        data object ClearCustomTheme : Event
        data object ClearHistory : Event
        data class SetScrobbleRadio(val v: Boolean) : Event
    }

    private val importError = MutableStateFlow<String?>(null)

    /** Theme, feel and import state. */
    private data class Appearance(
        val palette: String = "dark",
        val customJson: String = "",
        val importError: String? = null,
        val haptics: Boolean = true,
        val visualizer: String = "spectrum",
        val listenerGlobe: Boolean = true,
    )

    /** Playback behaviour prefs. */
    private data class Playback(
        val cellular: Boolean = true,
        val mono: Boolean = false,
        val buffer: Int = 20,
        val autoResume: Boolean = false,
        val resumePodcasts: Boolean = true,
        val autoDownload: Boolean = false,
        val autoCellular: Boolean = false,
        val cleanupPlayed: CleanupScope = CleanupScope.Off,
        val parallelDownloads: Int = 3,
        val speedPerSection: Boolean = true,
        val boostPerSection: Boolean = true,
        val skipBackSec: Int = DEFAULT_SKIP_BACK_SEC,
        val skipFwdSec: Int = DEFAULT_SKIP_FWD_SEC,
    )

    /** Library counts, scrobble flag and directory stats. */
    private data class Library(
        val resumeLocal: Boolean = false,
        val listenBrainzToken: String = "",
        val favorites: List<Station> = emptyList(),
        val history: List<Station> = emptyList(),
        val directoryStats: DirectoryStats? = null,
    )

    /** Radio opt-in, unsent scrobbles and the last send failure. */
    private data class ScrobbleBox(
        val radio: Boolean = false,
        val pending: Int = 0,
        val error: String? = null,
    )

    val state: StateFlow<UiState> = combine(
        combine(
            combine(
                prefs.palette,
                prefs.customTheme,
                importError,
                prefs.haptics,
                prefs.visualizer,
                ::Appearance,
            ),
            prefs.listenerGlobe,
        ) { appearance, globe ->
            appearance.copy(listenerGlobe = globe)
        },
        combine(
            combine(
                prefs.cellular,
                prefs.mono,
                prefs.bufferSeconds,
                prefs.autoResume,
                prefs.autoDownload,
                ::Playback,
            ),
            prefs.autoCellular,
            prefs.cleanupPlayed,
            prefs.parallelDownloads,
            prefs.speedPerSection,
        ) { playback, cell, clean, parallel, sections ->
            playback.copy(
                autoCellular = cell,
                cleanupPlayed = clean,
                parallelDownloads = parallel,
                speedPerSection = sections,
            )
        }.combine(
            combine(prefs.skipBackSec, prefs.skipFwdSec) { b, f -> b to f },
        ) { playback, skip ->
            playback.copy(skipBackSec = skip.first, skipFwdSec = skip.second)
        }.combine(
            prefs.resumePodcasts,
        ) { playback, resume ->
            playback.copy(resumePodcasts = resume)
        }.combine(
            prefs.boostPerSection,
        ) { playback, per ->
            playback.copy(boostPerSection = per)
        },
        combine(
            prefs.resumeLocal,
            prefs.listenBrainzToken,
            prefs.favorites,
            prefs.history,
            repository.directoryStats,
            ::Library,
        ),
        combine(
            prefs.scrobbleRadio,
            scrobbler.pendingCount,
            scrobbler.lastError,
            ::ScrobbleBox,
        ),
    ) { appearance, playback, library, scrobbleBox ->
        UiState(
            palette = appearance.palette,
            custom = decodeCustomThemeOrNull(appearance.customJson),
            customName = customThemeNameOrNull(appearance.customJson),
            importError = appearance.importError,
            haptics = appearance.haptics,
            visualizer = appearance.visualizer,
            listenerGlobe = appearance.listenerGlobe,
            cellular = playback.cellular,
            mono = playback.mono,
            buffer = playback.buffer,
            autoResume = playback.autoResume,
            resumePodcasts = playback.resumePodcasts,
            autoDownload = playback.autoDownload,
            autoCellular = playback.autoCellular,
            cleanupPlayed = playback.cleanupPlayed,
            parallelDownloads = playback.parallelDownloads,
            speedPerSection = playback.speedPerSection,
            boostPerSection = playback.boostPerSection,
            skipBackSec = playback.skipBackSec,
            skipFwdSec = playback.skipFwdSec,
            resumeLocal = library.resumeLocal,
            listenBrainzOn = library.listenBrainzToken.isNotBlank(),
            scrobbleRadio = scrobbleBox.radio,
            scrobblePending = scrobbleBox.pending,
            scrobbleError = scrobbleBox.error,
            favoritesCount = library.favorites.size,
            historyCount = library.history.size,
            directoryStats = library.directoryStats,
        )
    }.stateInUi(viewModelScope, UiState())

    // Flat event dispatcher; every branch is one settings delegation.
    @Suppress("CyclomaticComplexMethod")
    fun onEvent(e: Event) {
        when (e) {
            is Event.SetPalette -> viewModelScope.launch { prefs.setPalette(e.key) }
            is Event.SetAutoResume -> viewModelScope.launch { prefs.setAutoResume(e.v) }
            is Event.SetResumePodcasts -> viewModelScope.launch { prefs.setResumePodcasts(e.v) }
            is Event.SetResumeLocal -> viewModelScope.launch { prefs.setResumeLocal(e.v) }
            is Event.SetAutoDownload -> viewModelScope.launch { prefs.setAutoDownload(e.v) }
            is Event.SetAutoCellular -> viewModelScope.launch { prefs.setAutoCellular(e.v) }
            is Event.SetCleanupPlayed -> viewModelScope.launch { prefs.setCleanupPlayed(e.v) }
            is Event.SetParallelDownloads -> viewModelScope.launch { prefs.setParallelDownloads(e.v) }
            is Event.SetCellular -> viewModelScope.launch { prefs.setCellular(e.v) }
            is Event.SetMono -> viewModelScope.launch { prefs.setMono(e.v) }
            is Event.SetBuffer -> viewModelScope.launch { prefs.setBufferSeconds(e.seconds) }
            is Event.SetSkipBackSec -> viewModelScope.launch { prefs.setSkipBackSec(e.seconds) }
            is Event.SetSkipFwdSec -> viewModelScope.launch { prefs.setSkipFwdSec(e.seconds) }
            is Event.SetSpeedPerSection -> viewModelScope.launch { prefs.setSpeedPerSection(e.v) }
            is Event.SetBoostPerSection -> viewModelScope.launch { prefs.setBoostPerSection(e.v) }
            is Event.SetHaptics -> viewModelScope.launch { prefs.setHaptics(e.v) }
            is Event.SetListenerGlobe -> viewModelScope.launch { prefs.setListenerGlobe(e.v) }
            is Event.SetVisualizer -> viewModelScope.launch { prefs.setVisualizer(e.v) }
            is Event.SetScrobbleRadio -> viewModelScope.launch { prefs.setScrobbleRadio(e.v) }
            is Event.ImportTheme -> viewModelScope.launch {
                val raw = e.raw
                if (raw.isNullOrBlank()) {
                    importError.value = "could not read that file"
                    return@launch
                }
                parseCustomTheme(raw).fold(
                    onSuccess = {
                        importError.value = null
                        prefs.setCustomTheme(raw)
                        prefs.setPalette("custom")
                    },
                    onFailure = { importError.value = it.message ?: "not a theme file" },
                )
            }
            Event.ClearCustomTheme -> viewModelScope.launch { prefs.clearCustomTheme() }
            Event.ClearHistory -> viewModelScope.launch { prefs.clearHistory() }
        }
    }
}
