package stream.kleeamp.mobile

import android.app.Application
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.servers.ProviderStore
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.servers.ProviderRouter
import stream.kleeamp.mobile.playback.StreamResolver
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.podcasts.DownloadStore
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.CoverIo
import stream.kleeamp.mobile.library.LocalLibrary
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.library.PlaylistStore
import stream.kleeamp.mobile.podcasts.PodcastRepository
import stream.kleeamp.mobile.radio.RadioBrowser
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.radio.queueContinuation
import stream.kleeamp.mobile.settings.Scrobbler
import stream.kleeamp.mobile.playback.PlayerConnection
import stream.kleeamp.mobile.widget.WidgetRenderer

/**
 * Manual DI. The graph is four objects deep; a framework would cost more
 * lines than it saves.
 */
class KleeampApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: Prefs by lazy { Prefs(this) }
    val providers: ProviderStore by lazy { ProviderStore(this) }
    val radio: RadioRepository by lazy { RadioRepository(this, prefs, appScope) }
    val podcasts: PodcastRepository by lazy { PodcastRepository(this, appScope) }
    val localLibrary: LocalLibrary by lazy { LocalLibrary(this, appScope) }
    val playlists: PlaylistStore by lazy { PlaylistStore(this) }
    // PlayerConnection is Media3-unstable: only this getter and factory opt
    // in, so the app type itself stays clean for every cast and lambda.
    // A by-lazy delegate cannot carry markers (neither opt-in nor lint
    // suppressions apply to it), hence the explicit synchronized memo.
    @get:UnstableApi
    val player: PlayerConnection
        get() = synchronized(playerLock) {
            playerField ?: buildPlayer().also { playerField = it }
        }

    private val playerLock = Any()
    @UnstableApi
    private var playerField: PlayerConnection? = null

    /**
     * Episodes already swept for delete-after-listening this process. The
     * progress sink fires every tick inside the finished window, so without
     * this each tick would queue another delete of an already gone file.
     */
    private val cleanedUp = mutableSetOf<String>()
    private val cleanupLock = Any()

    @UnstableApi
    private fun buildPlayer(): PlayerConnection =
        PlayerConnection(
            this,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            streamResolver,
            resumeLookup = { station -> podcasts.resumePosition(station) },
            progressSink = { station, position, duration ->
                if (podcasts.saveProgress(station, position, duration)) {
                    maybeCleanupDownload(station.url)
                }
            },
            scrobbleTick = { station, playing, durationMs, streamTitle ->
                scrobbler.onTick(station, playing, durationMs, streamTitle)
            },
            autoResume = prefs.autoResume,
            resumeLocal = prefs.resumeLocal,
            resumePodcasts = prefs.resumePodcasts,
            continuationForOrigin = { origin, offset -> radio.queueContinuation(origin, offset) },
        )

    /** Delete-after-listening for a naturally finished episode, per scope. */
    private suspend fun maybeCleanupDownload(url: String) {
        if (synchronized(cleanupLock) { url in cleanedUp }) return
        val entry = downloads.entries.value[url] ?: return
        if (!prefs.cleanupPlayed.first().covers(entry.auto)) return
        synchronized(cleanupLock) { cleanedUp.add(url) }
        downloads.remove(url)
    }
    val downloads: DownloadStore by lazy { DownloadStore(this, prefs, appScope) }
    val scrobbler: Scrobbler by lazy { Scrobbler(this, prefs, appScope) }
    val providerRouter: ProviderRouter by lazy { ProviderRouter(ProviderRouter.defaults()) }

    /**
     * Provider stream URLs are signed per request, so they are resolved
     * here at play time rather than stored. Episode positions are the one
     * thing the player cannot work out for itself — wired here for the same
     * reason: playback should not be holding a database.
     */
    val streamResolver: StreamResolver by lazy {
        StreamResolver(
            providerResolver = resolve@ { accountId, trackId ->
                val account = providers.read().firstOrNull { it.id == accountId } ?: return@resolve null
                providerRouter.stream(account, trackId)
            },
            // A fetched episode plays from its file instead of the network.
            downloadLookup = { url -> downloads.localPath(url) },
        )
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        RadioBrowser.init(this)

        StationArtSource.init(this)
        SftpLibrary.init(this, providers, appScope)
        // The disk cache only TTL-checks on read, so prune stale + excess
        // files once per launch instead of growing forever.
        appScope.launch { StationArtSource.pruneDisk() }

        radio.bootstrap()
        podcasts.bootstrap()

        // Local songs power search, smart playlists and resume as well as
        // the Library tab, so the scan starts here at launch instead of
        // waiting for a first Library visit. Needs the audio permission
        // MainActivity asks for; without it the Library screen picks the
        // scan up when the permission is granted.
        if (LocalLibrary.hasAudioPermission(this)) localLibrary.refresh()

        // The widget reads the palette and the visualizer family too, and a
        // paused widget gets no nudge from the playback service, so watch
        // both settings directly: a theme or visualizer change re-renders
        // even with nothing playing. Merged, not combined - combine would
        // wait for both to emit, so toggling one alone would never fire.
        appScope.launch {
            merge(
                prefs.palette.distinctUntilChanged().drop(1),
                prefs.visualizer.distinctUntilChanged().drop(1),
            ).collect {
                WidgetRenderer.refresh(this@KleeampApp)
            }
        }

        player.onReady = {
            // The last station is restored but never auto-played unless
            // asked: a radio app that starts making noise on launch is a bad
            // neighbour.
            appScope.launch {
                if (prefs.autoResume.first()) {
                    prefs.lastStation.first()?.let { s -> player.play(s) }
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        StationArtSource.onTrimMemory(level)
        LocalArt.onTrimMemory(level)
    }
}
