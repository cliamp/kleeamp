package stream.kleeamp.mobile.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import stream.kleeamp.mobile.KleeampApp
import stream.kleeamp.mobile.library.LocalLibrary
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.prefs.playSectionFor
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.model.wrapNext
import stream.kleeamp.mobile.play.QueueContinuation
import stream.kleeamp.mobile.play.QueuePolicy
import stream.kleeamp.mobile.play.RepeatMode
import stream.kleeamp.mobile.player.shouldReportBuffering
import stream.kleeamp.mobile.player.shouldWantPlaying
import stream.kleeamp.mobile.widget.WidgetRenderer
import java.io.IOException

    /** How often a playing episode's position reaches the database. */
private const val PROGRESS_INTERVAL = 5_000L

/**
 * End-seek margin: seeking into the last [SEEK_END_MARGIN_MS] of an item
 * makes Media3 auto-advance at once, which reads as "just changed the
 * song". Targets there clamp to just short of the tail instead of being
 * dropped, so a fling to the end lands and plays out instead of snapping
 * back to where it started.
 */
internal const val SEEK_END_MARGIN_MS = 250L

/** How long a fired seek owns the clock until the player lands on it. */
private const val FIRED_SEEK_HOLD_MS = 2_000L

/** Close enough to the fired target to call the seek landed. */
private const val SEEK_LANDED_TOLERANCE_MS = 1_500L

/**
 * Which clock the UI reads this tick. Pure so unit tests can pin the
 * priority: a primed/fired seek target wins over the player's still-settling
 * clock (which otherwise flashes the old spot on every seek), a stale window
 * mid-nav never lends its clock to the new station, and only then does the
 * previous tick carry through IDLE.
 */
internal fun resolveKeptPosition(
    rawPositionMs: Long,
    itemMatchesStation: Boolean,
    sameStationAsPrev: Boolean,
    prevPositionMs: Long,
    pendingTargetMs: Long?,
    firedTargetMs: Long?,
): Long = when {
    pendingTargetMs != null -> pendingTargetMs
    firedTargetMs != null -> firedTargetMs
    rawPositionMs > 0 && itemMatchesStation -> rawPositionMs
    sameStationAsPrev -> prevPositionMs
    else -> 0L
}

/**
 * Seek target in ms for [fraction] of [durationMs], or null when there is
 * nothing sane to seek to. Pure so it can be unit-tested on the JVM.
 */
internal fun seekTargetMs(durationMs: Long, fraction: Float): Long? {
    if (durationMs <= 0) return null
    val target = (durationMs * fraction.coerceIn(0f, 1f)).toLong()
    return minOf(target, durationMs - SEEK_END_MARGIN_MS).coerceAtLeast(0L)
}

/**
 * Seek target in ms for [deltaSec] from [positionMs], clamped to the item
 * (short of the tail, where Media3 would auto-advance at once). Pure so it
 * can be unit-tested on the JVM.
 */
internal fun seekByTargetMs(positionMs: Long, durationMs: Long, deltaSec: Int): Long? {
    if (durationMs <= 0 || positionMs < 0) return null
    val end = (durationMs - SEEK_END_MARGIN_MS).coerceAtLeast(0L)
    return (positionMs + deltaSec * 1000L).coerceIn(0L, end)
}

/**
 * The UI's handle on playback. Transport goes through a MediaController rather
 * than straight to the ExoPlayer, so the app, the notification and any
 * Bluetooth remote all drive the same state machine.
 */
@UnstableApi
    // Transport shell over QueueController; every member is a delegate or a Media3 callback.
@Suppress("TooManyFunctions")
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val streams: StreamResolver = StreamResolver(),
    /**
     * Where a podcast episode should start, in millis. Passed in from the
     * application: playback needs the answer but has no business holding a
     * database handle to get it. Returns 0 for everything else, which is
     * every station radio ever plays.
     */
    private val resumeLookup: (suspend (Station) -> Long)? = null,
    /** Receives (station, position, duration) for episodes as they play. */
    private val progressSink: (suspend (Station, Long, Long) -> Unit)? = null,
    /** Receives (station, playing, duration, stream title) twice a second for scrobbling. */
    private val scrobbleTick: ((Station?, Boolean, Long, String) -> Unit)? = null,
    /** Master resume switch and local-files opt-in, collected below. */
    autoResume: Flow<Boolean> = flowOf(false),
    resumeLocal: Flow<Boolean> = flowOf(false),
    /** Episode resume switch, on by default. */
    resumePodcasts: Flow<Boolean> = flowOf(true),
    /** Rebuilds a paged queue source from its persisted directory origin. */
    private val continuationForOrigin: ((origin: String, offset: Int) -> QueueContinuation?)? = null,
) {
    private var controller: MediaController? = null

    private val queue = QueueController(
        context = context,
        scope = scope,
        controller = { controller },
        buildItem = ::buildItem,
        resumeAt = ::resumeAt,
        currentSpeed = { _speed.value },
        onSync = ::sync,
        onQueueRestored = { hasPrev, hasNext ->
            _state.value = _state.value.copy(hasPrev = hasPrev, hasNext = hasNext)
        },
        continuationForOrigin = continuationForOrigin ?: { _, _ -> null },
    )

    val upNext: StateFlow<List<Station>> get() = queue.upNext
    val upNextIndex: StateFlow<Int> get() = queue.upNextIndex
    val currentUpNext: List<Station> get() = queue.currentUpNext
    /** Full panel list + absolute index for the Up Next screen. */
    val panelList: StateFlow<List<Station>> get() = queue.panelList
    val panelIndex: StateFlow<Int> get() = queue.panelIndex
    val shuffle: StateFlow<Boolean> get() = queue.shuffle
    val repeat: StateFlow<RepeatMode> get() = queue.repeat
    fun setFallbackSource(list: List<Station>) = queue.setFallbackSource(list)
    fun stationForMediaId(id: String): Station? = queue.stationForMediaId(id)
    internal fun upcomingStations(count: Int = 4): List<Station> = queue.upcomingStations(count)
    /** Read-only preview of what prev/next would land on; null where a key would no-op. */
    internal fun peekAdjacent(): Pair<Station?, Station?> = queue.peekAdjacent()
    fun playUpNextEntry(index: Int) = queue.playUpNextEntry(index)
    fun playPanelEntry(panelPosition: Int) = queue.playPanelEntry(panelPosition)
    fun play(
        station: Station,
        from: List<Station> = emptyList(),
        continuation: QueueContinuation? = null,
    ) {
        // Each section keeps its own pace when switched on: apply it
        // synchronously so the media job (and every later transition) reads
        // the right speed. Off leaves the one app-wide speed alone.
        if (_speedSectionsOn.value) {
            val s = _sectionSpeeds.value[playSectionFor(station.source).name]
                ?.coerceIn(0.25f, 2f) ?: _speed.value
            _speed.value = s
            controller?.setPlaybackSpeed(s)
        }
        queue.play(station, from, continuation)
    }
    fun loadMoreQueue() = queue.loadMore()
    fun toggleShuffle() = queue.toggleShuffle()
    fun toggleRepeat() = queue.toggleRepeat()
    fun next() = queue.next()
    fun prev() = queue.prev()
    fun addToUpNext(station: Station, at: Int = Int.MAX_VALUE) {
        queue.addToUpNext(station, at)
        // Fires the root confirmation pill, naming the queued song so the
        // add reads even when it lands at the end of a long list.
        PlaybackBus.publishQueued("Queued · ${station.name}")
    }
    fun playNext(station: Station) {
        queue.playNext(station)
        // Play Next queues too, under its own name - it never confirmed
        // anything before, so its adds read as silently doing nothing.
        PlaybackBus.publishQueued("Up next · ${station.name}")
    }
    fun replaceUpNext(station: Station, from: List<Station>) = queue.replaceUpNext(station, from)
    fun removeFromUpNext(index: Int) = queue.removeFromUpNext(index)
    fun removePanelEntry(panelPosition: Int) = queue.removePanelEntry(panelPosition)
    fun reorderUpNext(from: Int, to: Int) = queue.reorderUpNext(from, to)
    fun movePanelEntry(fromAbs: Int, toAbs: Int) = queue.movePanelEntry(fromAbs, toAbs)
    fun clearUpNext() = queue.clearUpNext()
    val canUndo: StateFlow<Boolean> get() = queue.canUndo
    fun undo() = queue.undo()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()


    /**
     * Runs [action] once the controller is connected: immediately when warm,
     * chained behind any existing onReady hook when cold (a share arriving
     * at a dead process must not play into the void, nor clobber
     * auto-resume's hook).
     */
    fun doWhenReady(action: () -> Unit) {
        if (controller != null) action()
        else {
            val prev = onReady
            onReady = {
                prev?.invoke()
                action()
            }
        }
    }

    // If the app is opened straight into the notification (or nothing has UI-composed
    // yet), there is no root to seed the fallback list, so prime it from history and
    // favourites ourselves. The root's seed wins when it arrives.

    /** Playback speed multiplier, 0.25–2.0. Persisted; re-applied on every play. */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /**
     * Remembered speeds by section, kept in memory so play() applies the
     * right pace synchronously (no disk read racing the media job). The
     * stored map already falls back to the legacy global per section.
     */
    private val _sectionSpeeds = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Master switch for per-section pace; off means one app-wide speed. */
    private val _speedSectionsOn = MutableStateFlow(true)

    /**
     * Sleep timer deadline (epoch ms), or null when off. Checked on every
     * sync tick, so it fires even with the screen off; deliberately not
     * persisted - a deadline makes no sense after a restart.
     */
    private val _sleepAtMs = MutableStateFlow<Long?>(null)
    val sleepAtMs: StateFlow<Long?> = _sleepAtMs.asStateFlow()

    /** Arms the sleep timer for [minutes], or disarms it when null. */
    fun setSleepTimer(minutes: Int?) {
        _sleepAtMs.value = minutes?.takeIf { it > 0 }
            ?.let { System.currentTimeMillis() + it * 60_000L }
        sync()
    }

    /** Whether radio channel tracks resume; provider tracks always do. */
    private val _autoResume = MutableStateFlow(false)

    /** Whether local files resume; episodes keep their own switch below. */
    private val _resumeLocal = MutableStateFlow(false)

    /** Whether episodes resume. On by default. */
    private val _resumePodcasts = MutableStateFlow(true)
    private var pollJob: Job? = null

    init {
        scope.launch { autoResume.collect { _autoResume.value = it } }
        scope.launch { resumeLocal.collect { _resumeLocal.value = it } }
        scope.launch { resumePodcasts.collect { _resumePodcasts.value = it } }
        scope.launch {
            runCatching {
                (context.applicationContext as KleeampApp).prefs.speedSections.collect {
                    _sectionSpeeds.value = it
                }
            }
        }
        scope.launch {
            runCatching {
                (context.applicationContext as KleeampApp).prefs.speedPerSection.collect {
                    _speedSectionsOn.value = it
                }
            }
        }
    }

    /** The list prev/next walks. Set whenever the user plays from a list. */

    /**
     * The station behind a Media3 item id (item ids are station ids), or null.
     * Lets observers that fire mid-transition - before the published station
     * has caught up - resolve what is actually audible right now instead of
     * reading a stale bus value.
     */

    /** Index of the currently-playing station in [upNext], or -1. */


    /** Called once the controller is live, if the user asked for auto-resume. */
    var onReady: (() -> Unit)? = null

    private var lastProgressWrite = 0L
    /** A slow sink must not stack: one write per tick, never overlapping. */
    private var progressWrite: Job? = null
    /** Station the last sync reconciled, so IDLE keeps its timeline. */
    private var prevStationId: String? = null
    /** Seek fraction primed before the duration is known; applied on READY. */
    private var pendingSeekFraction: Float? = null
    private var pendingSeekStationId: String? = null
    /** Last fired seek target, held until the player lands on it. */
    private var firedSeekTargetMs: Long? = null
    private var firedSeekStationId: String? = null
    private var firedSeekAtMs = 0L

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
                // Deliberately no onMediaItemTransition hook: committed
                // navigations record synchronously, and a superseded seek's
                // late transition would fork the heard trail with a ghost
                // entry - Prev jumps and loops from there. Media3-side moves
                // (auto-advance) are learned with their index in sync().
            })
            c.setPlaybackSpeed(_speed.value)
            sync()
            onReady?.invoke()
        }, MoreExecutors.directExecutor())

        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(500)
                sync()
            }
        }
    }

    /** Progress is writable only while a resumable track is audibly playing. */
    private fun shouldWriteProgress(playingNow: Station, playing: Boolean): Boolean =
        playingNow.isTrack && playing &&
            (playingNow.source != StationSource.Local || _resumeLocal.value) &&
            (!playingNow.isChannelTrack || _autoResume.value)

    // Best-effort reconciliation: any failure is logged, never thrown, so the
    // hot poller survives unknown player states. Cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    private fun sync() {
        // Best-effort reconciliation: runs on a hot poller, Media3 callbacks
        // and at the end of nav jobs, so it must never take its caller down.
        // A throw here used to either kill the poller silently or crash the
        // app (and, inside a nav job, publish a bogus playback error).
        // Cancellation still propagates.
        try {
            syncInternal()
            checkSleepTimer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("kleeamp/player", "sync failed", e)
        }
    }

    /**
     * Fires an expired sleep timer: pause and disarm. Runs inside sync, so
     * the 2 Hz poller carries it with the screen off; the next tick
     * publishes the paused state everywhere (UI, widget, notification).
     */
    private fun checkSleepTimer() {
        val at = _sleepAtMs.value ?: return
        if (System.currentTimeMillis() < at) return
        _sleepAtMs.value = null
        controller?.pause()
    }

    // Single 2 Hz reconciliation choke point; splitting risks publish-order bugs.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun syncInternal() {
        val c = controller ?: return

        val changingPlayback = queue.isTransitioning
        queue.reconcileMediaWindow(c, changingPlayback)

        val navAvailability = queue.navAvailability()
        val prev = _state.value
        val stationNow = PlaybackBus.station.value
        // Keep the timeline up during reconnecting/IDLE so the seek bar
        // never vanishes for tracks: ExoPlayer reports no duration and
        // not-seekable while parked in IDLE, which would flip scrubbable
        // false and swap the bar for the streaming rule mid-retry.
        val rawDuration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val keptDuration = when {
            rawDuration > 0 -> rawDuration
            stationNow?.id == prevStationId && prev.durationMs > 0 -> prev.durationMs
            else -> stationNow?.durationMs ?: 0L
        }
        val rawSeekable = c.isCurrentMediaItemSeekable
        val keptSeekable = rawSeekable || (
            stationNow?.isTrack == true && keptDuration > 0 &&
                (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_BUFFERING)
            )
        val rawPosition = c.currentPosition.coerceAtLeast(0)
        val itemMatches = stationNow != null && c.currentMediaItem?.mediaId == stationNow.id
        val sameStation = stationNow?.id == prevStationId
        // A primed touch owns the clock from the tap, not from the binder
        // round-trip: without this every seek first flashes the old spot in
        // the time readouts (the bar already holds its committed target).
        val pendingTarget = pendingSeekFraction
            ?.takeIf { pendingSeekStationId == stationNow?.id }
            ?.let { seekTargetMs(keptDuration, it) }
        // A fired seek keeps owning the clock until the player lands on it
        // or the window expires (mirrors the scrubber's 2s committed hold,
        // which only covers the bar, never the clocks).
        val nowMs = System.currentTimeMillis()
        if (firedSeekTargetMs != null &&
            (firedSeekStationId != stationNow?.id || nowMs - firedSeekAtMs >= FIRED_SEEK_HOLD_MS)
        ) {
            firedSeekTargetMs = null
            firedSeekStationId = null
        }
        var firedTarget = firedSeekTargetMs
        if (firedTarget != null && kotlin.math.abs(rawPosition - firedTarget) < SEEK_LANDED_TOLERANCE_MS) {
            firedTarget = null
            firedSeekTargetMs = null
            firedSeekStationId = null
        }
        val keptPosition = resolveKeptPosition(
            rawPosition,
            itemMatches,
            sameStation,
            prev.positionMs,
            pendingTarget,
            firedTarget,
        )
        prevStationId = stationNow?.id
        // Intent covers a nav still building its queue (no items yet), and
        // IDLE-with-items plus a nav in flight count as loading: ExoPlayer
        // reports neither BUFFERING nor playing there, which used to read as
        // PAUSED once the optimistic grace expired.
        val transitioning = changingPlayback
        val want = shouldWantPlaying(
            c.playWhenReady,
            c.mediaItemCount,
            transitioning,
        )
        val bufferingNow = shouldReportBuffering(
            c.playbackState == Player.STATE_BUFFERING,
            c.playbackState == Player.STATE_IDLE,
            c.mediaItemCount,
            c.playWhenReady,
            transitioning,
            c.isPlaying,
        )
        _state.value = PlayerState(
            playing = c.isPlaying,
            buffering = bufferingNow,
            idle = c.playbackState == Player.STATE_IDLE && c.mediaItemCount == 0,
            wantPlaying = want,
            positionMs = keptPosition,
            bufferedMs = (c.bufferedPosition - c.currentPosition).coerceAtLeast(0),
            volume = c.volume,
            durationMs = keptDuration,
            seekable = keptSeekable,
            live = c.isCurrentMediaItemLive && stationNow?.isTrack != true,
            speed = c.playbackParameters.speed,
            sleepAtMs = _sleepAtMs.value,
            repeat = queue.repeatMode,
            hasPrev = navAvailability.hasPrev,
            hasNext = navAvailability.hasNext,
        )

        // A seek primed before load lands here: once the player leaves IDLE
        // with a known duration the track jumps to the touched point
        // instead of starting at 0. Station-scoped so a skip in between
        // drops the stale prime.
        applyPendingSeek(c)

        // Track positions are written from here because this is the only
        // place that already holds both the station and the player's clock.
        // Throttled to [PROGRESS_INTERVAL]: sync runs twice a second, and a
        // track does not need committing to disk twenty times a minute.
        val playingNow = PlaybackBus.station.value
        if (playingNow != null && shouldWriteProgress(playingNow, c.isPlaying)) {
            val now = System.currentTimeMillis()
            val position = c.currentPosition
            if (position > 0 && now - lastProgressWrite >= PROGRESS_INTERVAL) {
                lastProgressWrite = now
                val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    ?: playingNow.durationMs
                // Coalesced: a sink slower than the tick does not stack writes,
                // the next tick covers the newer position instead.
                if (progressWrite?.isActive != true) {
                    progressWrite = progressSink?.let { sink ->
                        scope.launch { sink(playingNow, position, duration) }
                    }
                }
            }
        }

        // Scrobble clock: same choke point, unthrottled - the scrobbler keeps
        // its own heard-time and fires once per play.
        scrobbleTick?.invoke(
            playingNow,
            c.isPlaying,
            c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: playingNow?.durationMs ?: 0L,
            PlaybackBus.streamTitle.value,
        )

        queue.advanceOnEnded(changingPlayback, c.playbackState == Player.STATE_ENDED)
    }
    private suspend fun resumeAt(station: Station): Long {
        if (!station.isTrack) return 0L
        // Local files only resume when the user asked them to, episodes keep
        // their own switch (on by default), radio channel tracks only when
        // auto-resume is on; provider tracks always do.
        val gated = (station.source == StationSource.Local && !_resumeLocal.value) ||
            (station.source == StationSource.Podcast && !_resumePodcasts.value) ||
            (station.isChannelTrack && !_autoResume.value)
        return if (gated) 0L else resumeLookup?.invoke(station) ?: 0L
    }

    private suspend fun buildItem(station: Station): MediaItem =
        withContext(Dispatchers.Default) {
            // Bounded as one unit with the resolve inside: window builds map
            // this over a slice, so an unbounded item multiplies into an
            // unbounded queue swap.
            withTimeoutOrNull(StreamResolver.RESOLVE_TIMEOUT_MS) {
                val resolved = streams.resolve(station.url)
                val url = if (station.source == StationSource.Local && resolved.url.startsWith("file://")) {
                    // Legacy rows cached before the content-uri switch: map
                    // once through the index instead of handing ExoPlayer a
                    // raw path scoped storage will refuse. Unresolvable rows
                    // play as-is and fail exactly once (no retry loop).
                    withContext(Dispatchers.IO) {
                        LocalLibrary.contentUriForLegacyFile(context, resolved.url)?.toString()
                    } ?: resolved.url
                } else resolved.url
                PlaybackService.mediaItem(context, station, ResolvedStream(url, resolved.headers))
            } ?: throw IOException("stream resolve timed out")
        }

    fun toggle(fallback: Station? = null) {
        val c = controller ?: return
        // Pause on intent, not just audibility: isPlaying is false through
        // the whole buffering stall, so tapping pause there used to fall
        // into the play branch and restart the load instead of pausing it.
        if (c.isPlaying || c.playWhenReady) c.pause()
        else {
            if (c.mediaItemCount == 0) {
                // Nothing loaded this session. Prefer the live bus station, but
                // accept a caller-supplied fallback (e.g. the last-played station
                // shown from history) so the transport can start playback even
                // before anything has been tuned.
                (PlaybackBus.station.value ?: fallback)?.let { play(it) }
            } else {
                // a stalled live stream has to be re-primed, not resumed
                c.prepare()
                c.play()
                c.setPlaybackSpeed(_speed.value)
            }
        }
        // The widget mirrors tap intent, not audibility: read playWhenReady
        // straight off the controller instead of waiting on the service's
        // next player event.
        WidgetRenderer.push(
            context.applicationContext,
            PlaybackBus.station.value,
            PlaybackBus.streamTitle.value,
            c.playWhenReady && c.mediaItemCount > 0,
        )
        sync()
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
        PlaybackBus.publishStreamTitle("")
        sync()
    }

    /**
     * Seeks by fraction rather than milliseconds so the caller does not need to
     * know the duration, and so a drag on a 3 minute track and a 3 hour one
     * behave the same. Works for every finite track (local, podcast,
     * provider, cliamp channel tracks) using the player duration first and
     * the station row as fallback while the extractor is still probing;
     * live streams ignore it. Touching the bar before load primes the
     * position: the fraction is remembered and applied once the player
     * leaves IDLE with a known duration, so the track loads from the
     * seeked time instead of starting at 0.
     */
    fun seekTo(fraction: Float) {
        val station = PlaybackBus.station.value ?: return
        if (!station.isTrack) return
        primeSeek(station, fraction.coerceIn(0f, 1f))
    }

    /** Skips by whole seconds, clamped to the item; live streams ignore it. */
    fun seekBy(seconds: Int) {
        val station = PlaybackBus.station.value ?: return
        if (!station.isTrack) return
        val c = controller
        val d = c?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: station.durationMs.takeIf { it > 0 }
            ?: run {
                // No duration yet: prime the middle so a skip still wakes
                // the track instead of no-oping; sync refines nothing here.
                primeSeek(station, 0.5f)
                return
            }
        val pos = (c?.currentPosition?.takeIf { it > 0 } ?: _state.value.positionMs)
            .coerceAtLeast(0)
        val target = seekByTargetMs(pos, d, seconds) ?: return
        primeSeek(station, target.toFloat() / d)
    }

    /**
     * Records the wanted fraction and fires it when the player can take it.
     * Seeks against an IDLE player are ignored by ExoPlayer, so the prime
     * is kept until sync sees READY/BUFFERING with a known duration; a
     * station change in between drops the stale prime.
     */
    private fun primeSeek(station: Station, fraction: Float) {
        pendingSeekFraction = fraction
        pendingSeekStationId = station.id
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            play(station)
            return
        }
        if (!applyPendingSeek(c)) sync()
    }

    /** Applies a primed seek when the player can take it. True when fired. */
    @Suppress("ReturnCount")
    private fun applyPendingSeek(c: Player): Boolean {
        val pending = pendingSeekFraction ?: return false
        val pendingId = pendingSeekStationId ?: return false
        val stationNow = PlaybackBus.station.value ?: return false
        if (pendingId != stationNow.id) {
            pendingSeekFraction = null
            pendingSeekStationId = null
            return false
        }
        // IDLE ignores seeks: hold the prime through reconnect retries
        // instead of firing into the void and clearing it (which read as
        // "tap, then back to zero" on slow podcast/local loads).
        if (c.playbackState == Player.STATE_IDLE) {
            return false
        }
        // A seek fired before the item reports seekable is dropped by
        // ExoPlayer yet looks sent here (seekTo returns void), clearing the
        // prime and snapping a long podcast back to zero once the UI's
        // 2s hold releases. Keep priming until the player can take it.
        if (!c.isCurrentMediaItemSeekable) {
            return false
        }
        if (!c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            return false
        }
        val d = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: stationNow.durationMs.takeIf { it > 0 }
            ?: return false
        val target = seekTargetMs(d, pending)
        if (target == null) {
            pendingSeekFraction = null
            pendingSeekStationId = null
            return false
        }
        val ok = runCatching { c.seekTo(target) }.isSuccess
        if (ok) {
            pendingSeekFraction = null
            pendingSeekStationId = null
            firedSeekTargetMs = target
            firedSeekStationId = stationNow.id
            firedSeekAtMs = System.currentTimeMillis()
        }
        return ok
    }

    fun setVolume(v: Float) {
        controller?.volume = v.coerceIn(0f, 1f)
        sync()
    }

    /**
     * Speed applies live and persists under the playing station's section
     * when per-section memory is on, so podcasts reopen at podcast pace
     * while radio stays 1x. Off writes the one app-wide speed.
     */
    fun setSpeed(v: Float) {
        val s = v.coerceIn(0.25f, 2f)
        _speed.value = s
        controller?.setPlaybackSpeed(s)
        scope.launch {
            val prefs = (context.applicationContext as KleeampApp).prefs
            val station = PlaybackBus.station.value
            if (_speedSectionsOn.value && station != null) {
                prefs.setSpeedFor(playSectionFor(station.source), s)
            } else {
                prefs.setSpeed(s)
            }
        }
        sync()
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
        controller?.release()
        controller = null
    }
}

data class PlayerState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val idle: Boolean = true,
    /** Play intent: a tap is in flight even before first audio. */
    val wantPlaying: Boolean = false,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val volume: Float = 1f,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    /** 0 when the source has no known length, which is the live-stream case. */
    val durationMs: Long = 0L,
    val seekable: Boolean = false,
    val live: Boolean = false,
    val speed: Float = 1f,
    /** Sleep timer deadline (epoch ms), null when off. */
    val sleepAtMs: Long? = null,
    val repeat: RepeatMode = RepeatMode.Off,
) {
    /** A scrubber is only honest when there is a length to scrub through. */
    val scrubbable: Boolean get() = seekable && !live && durationMs > 0
}
