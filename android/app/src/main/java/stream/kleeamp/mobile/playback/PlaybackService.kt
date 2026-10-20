package stream.kleeamp.mobile.playback

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.annotation.DrawableRes
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.CommandButton
import androidx.core.app.NotificationCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.KleeampApp
import stream.kleeamp.mobile.MainActivity
import stream.kleeamp.mobile.R
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.model.deEchoArtistLine
import stream.kleeamp.mobile.model.wrapNext
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.widget.WidgetRenderer
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.prefs.MaxBoostDb
import stream.kleeamp.mobile.prefs.playSectionFor

/**
 * Owns the player, the session and the audio effects. Everything the phone
 * shows outside the app - lockscreen, notification, Bluetooth, Android Auto -
 * comes from the MediaSession this service publishes.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val fx = AudioFx(bands = SPECTRUM_BANDS)
    /** Extra gain for quiet content; attaches wherever the EQ does. */
    private val boost = VolumeBoost()

    /**
     * Centralized PCM analysis: Tee tap → PcmRing → 4096-pt FFT → 64 bands.
     * Source-agnostic (same decoded PCM that produces sound, any Media3
     * source), duration-independent (live radio visualizes continuously),
     * ~30 Hz analysis with animation reading the latest frame at display
     * rate. Replaces the old Visualizer-API FFT path.
     */
    private val analyzer = AudioAnalyzer(bands = SPECTRUM_BANDS)

    /**
     * Mono downmix through Media3's own channel mixer - no hand-rolled buffer
     * math. Stereo folds to dual mono (the average in both ears) so channel
     * counts never change and the AudioTrack never reconfigures; anything
     * else wears a next-after-unity diagonal that behaves as passthrough. It
     * is not an exact identity on purpose: Media3 treats identity as
     * "processor inactive" and does not re-run onConfigure when only a matrix
     * changes, so an exact identity would make the mono key a no-op until the
     * next track. The one-ULP scale keeps the mixer live, so the key flips
     * mid-track with no rebuild and no gap.
     */
    private val mixer = ChannelMixingAudioProcessor().also { m ->
        for (n in 1..8) m.putChannelMixingMatrix(passthroughMatrix(n))
    }

    private val stereoTap = StereoMeterTap()

    /** Chosen output device id, -1 for system default. Mirrored from [prefs0]. */
    private var outputPreference = -1

    private val outputCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = applyOutputPreference()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyOutputPreference()
    }

    /** Re-expresses the preference after a device arrives or leaves. */
    private fun applyOutputPreference() {
        player.setPreferredAudioDevice(AudioOutputs.find(this, outputPreference))
    }

    /** Last channel count seen, so the toggle reapplies without a format event. */
    @Volatile private var mixerChannels = 2

    /** The settings key, mirrored here because the format listener is sync. */
    @Volatile private var monoOn = false

    /** Stereo averages to dual mono while on; everything else is passthrough. */
    private fun applyMonoMatrix() {
        val n = mixerChannels.coerceIn(1, 8)
        mixer.putChannelMixingMatrix(
            if (monoOn && n == 2) ChannelMixingMatrix(2, 2, floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))
            else passthroughMatrix(n)
        )
    }

    /**
     * The stock audio sink plus the mixer. Byte-for-byte the default chain
     * otherwise - verified against DefaultRenderersFactory.buildAudioSink,
     * which is exactly Builder + these two flags.
     */
    private fun renderersFactory(): DefaultRenderersFactory {
        val sinkMixer = mixer
        return object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(stereoTap, sinkMixer))
                .build()
        }
    }
    @Volatile private var spectrumWanted = true
    private var artworkJob: kotlinx.coroutines.Job? = null
    private var reconnector: Reconnector? = null
    private lateinit var prefs0: stream.kleeamp.mobile.prefs.Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Linear service wiring sequence; order matters, splitting hides it.
    @Suppress("LongMethod")
    @ExperimentalApi
    override fun onCreate() {
        super.onCreate()

        val http = OkHttpDataSource.Factory(Http.streamClient)
            .setUserAgent(Http.USER_AGENT)
            // without this header the server never interleaves song titles
            .setDefaultRequestProperties(mapOf(IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_NAME to "1"))

        val sources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(KleeampDataSourceFactory(DefaultDataSource.Factory(this, http)))

        // Buffer tuning has to serve two very different cases in one player.
        // Live radio cannot re-buffer from the past, so it wants a deep ceiling
        // and a long recovery after a re-buffer. On-demand files (local songs,
        // provider tracks) can re-buffer from anywhere; waiting on a deep
        // pre-roll was what made every song take 1-2s to *start* - the old 30s
        // minimum and 5s play-start threshold forced several seconds of reading
        // plus decoding before a single sample reached the speaker. Lower the
        // floor and play-start bar so local tracks begin in under a second, and
        // keep the generous ceiling so radio can still hold a deep buffer.
        // Media3 requires bufferForPlaybackAfterRebufferMs <= minBufferMs, so
        // both sit at 2s and radio's blip-tolerance comes from the deep ceiling
        // (radio streams that truly die are re-armed by the Reconnector anyway).
        prefs0 = (application as KleeampApp).prefs
        val targetBufferMs = runBlocking { prefs0.bufferSeconds.first() } * 1_000
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,                                          // sustain on ~2s
                (targetBufferMs * 4).coerceIn(60_000, 180_000), // radio depth ceiling
                500,                                            // start as soon as ~0.5s in
                2_000,                                          // must be <= minBufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory())
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            // Media3 1.11 per-stream media progression (issue #3122): renderers
            // for the subsequent playlist item are enabled/primed as the current
            // item finishes, instead of only at the transition. On the Samsung
            // A56 this is what collapses the per-track deep-buffer AudioTrack
            // re-prime that cost ~1.2-1.8s between local songs.
            .enablePerStreamMediaProgression(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()

        player.addListener(PlayerEvents())
        player.addAnalyticsListener(FormatEvents())

        // The output choice is a preference, not a command: the UI writes it
        // and the player follows. Re-applied when devices come and go, so a
        // headset that reconnects picks its stream back up.
        getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(
            outputCallback,
            Handler(Looper.getMainLooper()),
        )
        scope.launch {
            prefs0.outputDevice.collect {
                outputPreference = it
                applyOutputPreference()
            }
        }

        // The mono key flips a matrix, not the chain; the format listener
        // below re-anchors it per stream so exotic channel counts stay safe.
        scope.launch {
            prefs0.mono.collect {
                monoOn = it
                applyMonoMatrix()
            }
        }

        // Extra gain for quiet recordings: follows the stored boost like the
        // EQ follows its bands, re-attaching wherever the session does. Each
        // section keeps its own gain when switched on, mirroring pace.
        scope.launch {
            combine(
                prefs0.boostDb,
                prefs0.boostSections,
                prefs0.boostPerSection,
                PlaybackBus.station,
            ) { global, sections, scoped, station ->
                effectiveBoostDb(global, sections, scoped, station)
            }.collect { boost.setBoostDb(it) }
        }

        reconnector = Reconnector(this, player, scope) { retrying, attempt ->
            PlaybackBus.publishReconnect(if (retrying) attempt else 0)
            if (retrying) PlaybackBus.publishError(null)
        }.also { it.attach() }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(SessionCallback())
            .build()

        // A single-item queue means Media3 offers no prev/next, so the default
        // notification is one lonely play button. These put station stepping and
        // favouriting on the lockscreen where they belong.
        scope.launch {
            val conn = (application as KleeampApp).player
            combine(prefs0.favorites, conn.shuffle) { favs, shuffled ->
                val url = PlaybackBus.station.value?.url
                session?.setMediaButtonPreferences(buttons(favs.any { it.url == url }, shuffled))
            }.collect { }
        }

        setMediaNotificationProvider(
            OxideNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        val prefs = prefs0
        // The analyzer runs off the tap regardless of the visualizer setting
        // so mode switches are instant and no attach races the audio session.
        // spectrumWanted only gates publishing liveness, never PCM flow.
        analyzer.start(scope)
        // Repeat-one rides the player's own repeat mode so the current item
        // loops natively - including mid-queue, where no end event ever
        // fires for our queue watcher to catch. Explicit next/prev still
        // step, and anything else stays repeat-off. This follows the
        // queue's live repeat flow (the same source the icon reads), never
        // the persisted copy, so the two can never disagree the way a
        // lost prefs write once made the icon lie.
        scope.launch {
            (application as KleeampApp).player.repeat.collect {
                player.repeatMode = repeatPlayerMode(it)
            }
        }
        scope.launch {
            combine(prefs.visualizer, prefs.eqEnabled, prefs.eqBands) { vis, eqOn, bands ->
                Triple(vis, eqOn, bands)
            }.collect { (vis, eqOn, bands) ->
                spectrumWanted = vis != "off"
                fx.attach(player.audioSessionId, spectrumWanted)
                boost.attach(player.audioSessionId)
                fx.setEqEnabled(eqOn)
                // Pretend-off: DSP stays engaged, OFF means flat (no pops
                // from removing the effect; battery cost is negligible vs
                // the visualizer). Stored bands are kept so ON restores them.
                fx.applyEffectiveBands(eqOn, bands)
                if (!spectrumWanted) PlaybackBus.publishSpectrumLive(false)
            }
        }

        // Push whatever state exists to the widget host on cold start. Without
        // this, a service launch that never changes state (nothing playing, no
        // event) leaves the widget sitting on its bare "kleeamp" placeholder,
        // because publishWidgetState is otherwise only driven by player events.
        scope.launch { publishWidgetState() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private fun buttons(
        isFavourite: Boolean,
        shuffled: Boolean,
    ): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous")
            .setIconUri(iconUri(R.drawable.ic_w_prev))
            .setSessionCommand(SessionCommand(CMD_PREV_STATION, Bundle.EMPTY))
            .build(),
        CommandButton.Builder(
            if (isFavourite) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED
        )
            .setDisplayName(if (isFavourite) "Remove favourite" else "Favourite")
            // Belt and braces: the provider resolves the resource id while
            // controllers that load artwork-style URIs take the content URI.
            .setIconResId(if (isFavourite) R.drawable.ic_w_heart_filled else R.drawable.ic_w_heart)
            .setIconUri(iconUri(if (isFavourite) R.drawable.ic_w_heart_filled else R.drawable.ic_w_heart))
            .setSessionCommand(SessionCommand(CMD_FAVOURITE, Bundle.EMPTY))
            .build(),
        CommandButton.Builder(
            if (shuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName(if (shuffled) "Stop shuffling" else "Shuffle")
            // Same Shuffle glyph as the in-app icon; the on variant carries
            // the filled dot under it, mirroring the hero ShuffleAction.
            .setIconResId(if (shuffled) R.drawable.ic_w_shuffle_on else R.drawable.ic_w_shuffle)
            .setIconUri(iconUri(if (shuffled) R.drawable.ic_w_shuffle_on else R.drawable.ic_w_shuffle))
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next")
            .setIconUri(iconUri(R.drawable.ic_w_next))
            .setSessionCommand(SessionCommand(CMD_NEXT_STATION, Bundle.EMPTY))
            .build(),
    )

    /** android.resource URIs resolve cross-process in SystemUI, like res ids did. */
    private fun iconUri(@DrawableRes resId: Int): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${packageName}/$resId")

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Positional seek is not in the default player set: without these
            // the app's own MediaController.seekTo() is dropped session-side
            // ("Controller isn't allowed"), so the bar holds the dragged
            // spot and snaps back - glaring on long podcasts. Player codes
            // travel in Player.Commands, not SessionCommands. This also
            // lights up the lockscreen seek bar.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .build()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PREV_STATION, Bundle.EMPTY))
                .add(SessionCommand(CMD_NEXT_STATION, Bundle.EMPTY))
                .add(SessionCommand(CMD_FAVOURITE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(playerCommands)
                .setMediaButtonPreferences(buttons(false, false))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val conn = (application as KleeampApp).player
            when (customCommand.customAction) {
                CMD_PREV_STATION -> scope.launch { conn.prev() }
                CMD_NEXT_STATION -> scope.launch { conn.next() }
                // Shuffle must only flip the flag / reorder, never jump to a
                // random station - that is exactly what the old button did.
                CMD_SHUFFLE -> scope.launch { conn.toggleShuffle() }
                CMD_FAVOURITE -> scope.launch {
                    PlaybackBus.station.value?.let { prefs0.toggleFavorite(it) }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * Station branding arrives over the network, so it must not sit between the
     * tap and the audio. The item starts with the locally drawn plate and this
     * swaps in the real thing whenever it turns up, reusing the same
     * replaceMediaItem trick the ICY title uses.
     *
     * A known cover URL is preferred over discovery. Podcast episodes and
     * provider tracks arrive with real artwork already in [Station.cover], and
     * bitmapFor would never find it: that path scrapes the homepage for an
     * og:image and then falls back to the favicon, which is the right order for
     * radio and simply misses a field it does not read.
     */
    private fun loadArtwork(station: Station) {
        artworkJob?.cancel()
        artworkJob = scope.launch {
            // Bounded retries: a transient miss on the track change used to
            // leave the lockscreen artless for the whole episode. The job is
            // cancelled on the next change, so retries never leak across
            // tracks.
            var art: android.graphics.Bitmap? = null
            for (attempt in 0 until 3) {
                art = station.cover.takeIf { it.startsWith("http") }
                    ?.let { StationArtSource.bitmapForUrl(it) }
                    ?: StationArtSource.bitmapFor(station)
                if (art != null) break
                if (attempt < 2) kotlinx.coroutines.delay(2_000)
            }
            val cover = art ?: return@launch
            swapArtwork(station, cover)
        }
    }

    /**
     * Stamps fetched art onto its item. replaceMediaItem keeps the item's
     * current position, so this must not seek afterwards: a seek right after
     * an item transition pushes the audio renderer back through BUFFERING and
     * leaves the first moments of the new track silent, which is especially
     * audible over Bluetooth and wired headphones.
     */
    private fun swapArtwork(station: Station, cover: android.graphics.Bitmap) {
        // The bus may still name the previous track this early, so the
        // freshness check below reads the player, not the bus: only stamp
        // art onto the item it was decoded for.
        val item = player.currentMediaItem ?: return
        if (item.mediaId != station.id) return
        val bytes = StationArtwork.withArt(this@PlaybackService, station, cover)
        val fresh = player.currentMediaItem
            ?.takeIf { it.mediaId == station.id } ?: return
        if (fresh.mediaMetadata.artworkData?.contentEquals(bytes) == true) return
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            fresh.buildUpon()
                .setMediaMetadata(
                    fresh.mediaMetadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                )
                .build(),
        )
    }

    /**
     * Same walk the widget does: the list currently playing (local songs, the
     * source that was tapped - favourites, a provider album, a directory) -
     * falling back to favourites, then cliamp's channels, if nothing is loaded.
     */
    /**
     * Anything the widget or tile draws has to be written down, not held in
     * RAM. Icecast sends a metadata block roughly once a second, so this is
     * deduped: without the guard it wrote two preferences and woke every
     * widget several times a second for state that had not changed.
     */
    private var lastWidgetState: WidgetSig? = null

    /** Identity of the last list published, so next-up rewrites when the list or current song moves. */
    private var lastWidgetSourceKey: String? = null

    /**
     * Preview the active queue's next occurrences. A cold widget launch can
     * still fall back to its persisted radio-style source.
     */
    private fun widgetUpNext(source: List<Station>, station: Station?): List<Station> {
        val connection = (application as KleeampApp).player
        if (connection.currentUpNext.isNotEmpty()) return connection.upcomingStations()
        if (source.isEmpty() || station == null) return emptyList()
        val i = source.indexOfFirst { it.url == station.url }
        if (i < 0) return emptyList()
        return source.wrapNext(i)
    }

    /**
     * Push widget state only when something actually changed. The widget renders
     * a static row (title, artist, controls), so continuous writes would just
     * be launcher churn; the metadata dedup guards a quiet session.
     */
    private fun publishWidgetState() {
        val station = PlaybackBus.station.value
        val source = PlaybackBus.source.value
        // Intent, not audibility: isPlaying is false while buffering /
        // reconnecting, so using it flips the widget to the play glyph every
        // stall and right after tune (before READY). playWhenReady flips on
        // the tap itself, which is what the toggle icon must mirror.
        val playing = player.playWhenReady && player.mediaItemCount > 0
        val seekable = player.isCurrentMediaItemSeekable
        val duration = player.duration.takeIf { it > 0 } ?: station?.durationMs ?: 0L
        // Seekability and duration are part of the identity: they arrive
        // later than the tap (duration is only known at READY), and without
        // them here the "same triple" early return would swallow the very
        // publish that flips the widget from the streaming rule to the seek
        // row. Position stays out - the one-second ticker owns it.
        val next = WidgetSig(
            playing = playing,
            track = PlaybackBus.streamTitle.value,
            url = station?.url.orEmpty(),
            seekable = seekable,
            durationMs = duration,
        )
        // Both halves of the cache update synchronously. lastWidgetSourceKey
        // used to be assigned inside the launch below (and only when upNext
        // was non-empty), so after a cold start it stayed null forever, the
        // early return never fired, and every tap emitted 2-3 writes +
        // updateAlls - concurrent RemoteViews the launcher can apply out of
        // order, leaving the glyph stuck on a stale frame.
        val sourceKey = (source.map { it.url } + (station?.url.orEmpty()) +
            (application as KleeampApp).player.upNextIndex.value.toString()).joinToString("|")
        val upNext = widgetUpNext(source, station)
        if (next == lastWidgetState && sourceKey == lastWidgetSourceKey) return
        lastWidgetState = next
        val sourceChanged = sourceKey != lastWidgetSourceKey
        lastWidgetSourceKey = sourceKey
        // Pixels first: the widget renders this exact row with no disk read
        // on the path. The writes below are persistence for cold boot (and
        // the tile fallback) and never gate what is on screen.
        WidgetRenderer.push(
            this, station, next.track, next.playing,
            seekable = next.seekable,
            durationMs = next.durationMs,
            positionMs = player.currentPosition.coerceAtLeast(0),
        )
        scope.launch {
            val favs = prefs0.favorites.first()
            val shuffled = (application as KleeampApp).player.shuffle.value
            session?.setMediaButtonPreferences(
                buttons(
                    favs.any { it.url == station?.url },
                    shuffled,
                )
            )
            // Preserve the persisted fallback only when no session source exists.
            if (sourceChanged && source.isNotEmpty()) {
                prefs0.setWidgetNext(upNext)
            }
            // One transaction for the whole widget row, so cold-boot readers
            // see a consistent snapshot.
            prefs0.writeWidgetSnapshot(
                playing = next.playing,
                track = next.track,
                seekable = next.seekable,
                durationMs = next.durationMs,
            )
        }
    }

    /** Identity of one widget publish; duration/seekability included because
     * they arrive after the tap that the other three fields describe. */
    private data class WidgetSig(
        val playing: Boolean,
        val track: String,
        val url: String,
        val seekable: Boolean,
        val durationMs: Long,
    )

    /**
     * Ticks the widget twice a second while anything plays: the seek row
     * (elapsed/bar, partial update, skips ticks where the clock second did
     * not move). Paused, empty or failed playback stops the ticker; the
     * last frame already shows the resting state.
     */
    private var progressJob: Job? = null

    /** Last analyser re-attach attempt; see the ticker loop. */
    private var lastFxRetryMs = 0L

    private fun syncProgressTicker() {
        val want = player.playWhenReady && player.mediaItemCount > 0
        if (want && progressJob?.isActive == true) return
        progressJob?.cancel()
        progressJob = if (want) scope.launch {
            while (true) {
                val dur = player.duration.takeIf { it > 0 } ?: 0L
                if (player.isCurrentMediaItemSeekable && dur > 0) {
                    WidgetRenderer.pushProgress(
                        this@PlaybackService,
                        player.currentPosition.coerceAtLeast(0),
                        dur,
                    )
                }
                // Self-healing analyser attach; see reattachAnalyserIfStalled.
                reattachAnalyserIfStalled()
                delay(500)
            }
        } else null
        if (!want) {
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            if (player.isCurrentMediaItemSeekable && duration > 0) {
                WidgetRenderer.pushProgress(
                    this,
                    player.currentPosition.coerceAtLeast(0),
                    duration,
                )
            }
        }
    }

    /**
     * Self-healing EQ attach: the audio session id only becomes valid once the
     * renderer is up, so a session that was not ready fails attach once. The
     * PCM tap needs no healing (it is in the sink chain from the start), but
     * the EQ does — rechecked twice a second while playback is wanted. A
     * session whose effect creation failed is remembered, so unsupported
     * devices are not re-probed every tick.
     */
    private fun reattachAnalyserIfStalled() {
        val now = System.currentTimeMillis()
        if (!player.isPlaying || now - lastFxRetryMs <= FX_RETRY_MS) {
            return
        }
        lastFxRetryMs = now
        fx.attach(player.audioSessionId, spectrumWanted)
        boost.attach(player.audioSessionId)
        // The tap flows even while buffering/reconnecting; mark live once
        // PCM has been seen so meters never freeze permanently after a stall.
        if (spectrumWanted && PcmRing.written() > 0 && !PlaybackBus.spectrumLive.value) {
            PlaybackBus.publishSpectrumLive(true)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A radio app that keeps playing after the task is swiped is a bug, not
        // a feature - unless it is actually playing, in which case leave it be.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(outputCallback)
        reconnector?.detach()
        analyzer.stop()
        fx.release()
        boost.release()
        scope.cancel()
        // the session must go first; releasing the player under a live
        // session leaves the notification pointing at a dead controller
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo) {
                    val title = entry.title?.trim().orEmpty()
                    if (title.isNotEmpty() && isCurrentItem(PlaybackBus.station.value)) {
                        PlaybackBus.publishStreamTitle(title)
                        pushToSession(title)
                        publishWidgetState()
                    }
                }
            }
        }

        /**
         * Generation check: an ICY packet racing a station switch belongs to
         * the old stream. The player has already moved on when its current
         * item no longer names the published station, so the title is stale
         * and publishing it would stamp last song's name under the new one.
         */
        private fun isCurrentItem(station: Station?): Boolean {
            if (station == null) return false
            return player.currentMediaItem?.mediaId == station.id
        }

        /**
         * Media id of the last item transition we processed. replaceMediaItem
         * (how the ICY title and the artwork reach the notification) keeps
         * the same media id and surfaces here too; clearing the title on
         * that would fight the very update that caused it. A real
         * navigation lands on a different id, so only same-id transitions
         * are exempt.
         */
        private var lastTransitionId: String? = null
        private var icyRefreshId: String? = null

        /**
         * The notification reads MediaItem.mediaMetadata, not the combined
         * in-stream metadata, so the ICY title never reaches the lockscreen on
         * its own. replaceMediaItem with an identical URI is the supported way
         * to change metadata without re-preparing the stream.
         */
        private fun pushToSession(songTitle: String) {
            val item = player.currentMediaItem ?: return
            val station = PlaybackBus.station.value ?: return
            val current = item.mediaMetadata
            if (current.artist?.toString() == songTitle) return
            icyRefreshId = item.mediaId
            // replaceMediaItem also preserves the current position, so an ICY
            // refresh never restarts an episode or a finite track.
            val index = player.currentMediaItemIndex
            player.replaceMediaItem(
                index,
                item.buildUpon()
                    .setMediaMetadata(
                        current.buildUpon()
                            .setTitle(station.name)
                            .setArtist(songTitle)
                            .build()
                    )
                    .build(),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // the reconnector decides whether this is fatal; only say so once
            // it has given up, otherwise the UI flashes red mid-retry
            if (reconnector?.isRetrying != true) {
                PlaybackBus.publishError(
                    error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                PlaybackBus.publishError(null)
                if (spectrumWanted && PcmRing.written() > 0) {
                    PlaybackBus.publishSpectrumLive(true)
                }
            } else {
                // Pause: freeze gracefully — the analyzer decays to silence on
                // its next tick and frames settle; liveness stays so resume is
                // instant with no re-attach dance.
            }
            publishWidgetState()
            syncProgressTicker()
            // the session id only becomes valid once the audio renderer is up,
            // so this is the attach that usually wins - it must still respect
            // the user's setting rather than force the visualizer back on
            fx.attach(player.audioSessionId, spectrumWanted)
            boost.attach(player.audioSessionId)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // isPlaying only flips once audio actually flows (READY); intent
            // flips on the tap. Without this, resume sits on the play glyph
            // through the whole buffering stall until first audio.
            publishWidgetState()
            syncProgressTicker()
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Duration is only known once the source is ready; without this
            // the seek row would wait for the next tap to learn it.
            if (state == Player.STATE_READY) {
                publishWidgetState()
                syncProgressTicker()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek from the app lands here; mirror it now rather than at
            // the next one-second tick.
            if (player.isCurrentMediaItemSeekable && player.duration > 0) {
                WidgetRenderer.pushProgress(
                    this@PlaybackService,
                    player.currentPosition.coerceAtLeast(0),
                    player.duration,
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                // Resolve what is audible from the event's own item: on an
                // auto-advance the published station still names the finished
                // track here, and loading art for that would stamp the old
                // cover onto the new item (or be thrown away by the guard).
                // Unresolvable ids are skipped - the item keeps the plate it
                // was built with rather than risking a wrong cover.
                val station = mediaItem?.mediaId
                    ?.let { (application as KleeampApp).player.stationForMediaId(it) }
                if (station != null) loadArtwork(station)
            }
            // replaceMediaItem (how the ICY title and the artwork reach the
            // notification) surfaces here too with the same media id;
            // clearing the title on that would fight the very update that
            // caused it. A real navigation lands on a different item, so
            // only a changed id clears.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                val id = mediaItem?.mediaId
                val refreshed = id != null && (id == icyRefreshId || id == lastTransitionId)
                icyRefreshId = null
                if (!refreshed) {
                    PlaybackBus.publishStreamTitle("")
                    // Real source change: drop stale analyzer + peak state so
                    // the new station never wears the old one's caps. Metadata
                    // refreshes (same id) skip this — animation continues
                    // across title and artwork updates.
                    analyzer.reset()
                }
                lastTransitionId = id
            }
            publishWidgetState()
            syncProgressTicker()
        }
    }

    private inner class FormatEvents : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            format.channelCount.takeIf { it > 0 }?.let {
                mixerChannels = it
                applyMonoMatrix()
            }
            PlaybackBus.publishFormat(
                StreamFormat(
                    bitrateKbps = if (format.bitrate != Format.NO_VALUE) format.bitrate / 1000 else 0,
                    sampleRateHz = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 0,
                    codec = format.sampleMimeType
                        ?.substringAfter('/')
                        ?.removePrefix("mpeg")
                        ?.ifBlank { "mp3" }
                        .orEmpty(),
                )
            )
        }
    }

    companion object {
        /** Player repeat mode mirroring the live repeat setting. */
        fun repeatPlayerMode(mode: stream.kleeamp.mobile.play.RepeatMode): Int =
            if (mode == stream.kleeamp.mobile.play.RepeatMode.One) Player.REPEAT_MODE_ONE
            else Player.REPEAT_MODE_OFF

        const val SPECTRUM_BANDS = 64

        /** Minimum gap between analyser re-attach attempts; see the ticker. */
        const val FX_RETRY_MS = 2000L
        const val CMD_PREV_STATION = "stream.kleeamp.mobile.PREV_STATION"
        const val CMD_NEXT_STATION = "stream.kleeamp.mobile.NEXT_STATION"
        const val CMD_SHUFFLE = "stream.kleeamp.mobile.SHUFFLE"
        const val CMD_FAVOURITE = "stream.kleeamp.mobile.FAVOURITE"

        /** Metadata the notification and lockscreen read. */
        fun mediaItem(context: Context, station: Station, resolved: ResolvedStream): MediaItem =
            MediaItem.Builder()
                .setMediaId(station.id)
                .setUri(resolved.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(notificationArtist(station))
                        .setStation(station.name)
                        .setArtworkData(
                            StationArtwork.forStation(context, station),
                            MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                        )
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(mediaType(station))
                        .build()
                )
                .build()

        private fun mediaType(station: Station): Int = when {
            station.source == StationSource.Podcast -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
            station.isTrack -> MediaMetadata.MEDIA_TYPE_MUSIC
            else -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
        }

        /**
         * The artist/subtitle line the notification reads, per source. Real
         * metadata only, minus any source echo; channel tracks name their
         * artist like any song, and the line never reads empty.
         */
        fun notificationArtist(station: Station): String {
            val raw = when (station.source) {
                StationSource.Local -> deEchoArtistLine(station.artistAlbum.split("·"), 2)
                StationSource.Podcast -> deEchoArtistLine(listOf(station.artist), 1)
                StationSource.Provider -> deEchoArtistLine(station.artistAlbum.split("·"), 2)
                StationSource.Cliamp ->
                    if (station.isChannelTrack) deEchoArtistLine(station.artistAlbum.split("·"), 2)
                    else station.meta
                else -> station.meta
            }
            return raw.ifBlank { "unknown" }
        }
    }
}

/**
 * The media notification in kleeamp's own red.
 *
 * [DefaultMediaNotificationProvider.createNotification] is final, so the colour
 * cannot be set by wrapping it - but [addNotificationActions] is protected and
 * is handed the NotificationCompat.Builder on the way through, which is the one
 * place the notification is still open for editing.
 *
 * The tint is the oxide accent rather than the #7F2117 the launcher tile uses.
 * That red sits at oklch lightness 0.40 and would tint an icon at about 3.3:1
 * against a dark notification shade; the accent is the same red lifted to the
 * lightness the app already uses for text and icons, and clears 5:1 on either
 * shade. Fixed rather than following the chosen theme, because this is the
 * app's identity outside the app - same argument as the launcher icon.
 */
@UnstableApi
private class OxideNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        builder.setColor(ACCENT).setColorized(true)
        return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
    }

    private companion object {
        /** OxidePalette.accent. */
        const val ACCENT = 0xFFD15D4D.toInt()
    }
}

/**
 * An N-to-N diagonal a single float ULP above unity. It is inaudible
 * (+0.0000009 dB) but not [ChannelMixingMatrix.isIdentity], which keeps
 * Media3's mixer active so [PlaybackService.applyMonoMatrix] can change the
 * live mono matrix without rebuilding the sink. Using next-after-unity (not
 * next-below) also avoids truncating PCM16 samples by one LSB.
 */
@UnstableApi
private fun passthroughMatrix(n: Int): ChannelMixingMatrix =
    ChannelMixingMatrix(n, n, FloatArray(n * n) { i -> if (i % (n + 1) == 0) 1.0000001f else 0f })

/**
 * Gain for the audible station: its section's remembered boost when
 * per-section memory is on, else the one app-wide value. Pure so unit
 * tests can pin the selection.
 */
internal fun effectiveBoostDb(
    global: Int,
    sections: Map<String, Int>,
    scoped: Boolean,
    station: Station?,
): Int {
    if (!scoped || station == null) return global.coerceIn(0, MaxBoostDb)
    return (sections[playSectionFor(station.source).name] ?: global).coerceIn(0, MaxBoostDb)
}
