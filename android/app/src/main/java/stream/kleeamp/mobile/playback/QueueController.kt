package stream.kleeamp.mobile.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.KleeampApp
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.play.QueueModel
import stream.kleeamp.mobile.play.QueuePolicy
import stream.kleeamp.mobile.play.RepeatMode
import stream.kleeamp.mobile.widget.WidgetRenderer

private const val NAV_DEBOUNCE_MS = 180L
/** Caps the undo stack: walking back fifty edits is plenty, and old sources stay small. */
private const val MAX_UNDO_DEPTH = 50
/**
 * Largest source persisted whole across restarts. A Station serializes to a
 * few hundred bytes, so this caps the queue blob well under a megabyte;
 * bigger lists fall back to the bounded window like before.
 */
private const val FULL_SOURCE_PERSIST_CAP = 500

/**
 * The queue half of playback: the source lists, the bounded Up Next window,
 * shuffle, navigation, edits and persistence. Media3 application (queue
 * builds, slides, swaps, the swap guard and media jobs) lives on
 * [QueueMediaBridge]; Media3 itself stays behind [PlayerConnection], reached
 * through constructor callbacks.
 */
@UnstableApi
    // Function/line count is queue surface (play/next/prev/edits/persist);
    // the Media3 halves moved to QueueMediaBridge. Covered by QueuePolicyTest.
@Suppress("TooManyFunctions", "LargeClass")
internal class QueueController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: () -> MediaController?,
    private val buildItem: suspend (Station) -> MediaItem,
    private val resumeAt: suspend (Station) -> Long,
    private val currentSpeed: () -> Float,
    private val onSync: () -> Unit,
    private val onQueueRestored: (Boolean, Boolean) -> Unit,
) {
    private val model = QueueModel()
    private val history = QueueHistory()


    init {
        val app = context.applicationContext as KleeampApp
        scope.launch {
            val prefs = app.prefs
            val history = prefs.history.first()
            val favs = prefs.favorites.first()
            if (model.fallback.isEmpty()) {
                model.fallback = history.ifEmpty { favs }
            }
            model.setRepeatMode(RepeatMode.of(prefs.repeat.first()))
            restoreQueue(prefs)
        }
    }


    val upNext: StateFlow<List<Station>> get() = model.upNext
    val upNextIndex: StateFlow<Int> get() = model.upNextIndex
    val currentUpNext: List<Station> get() = model.currentUpNext

    /**
     * What the Up Next screen lists: the whole source for finite track
     * lists, the bounded player window otherwise. Indices into it are
     * absolute source positions; the player still walks the window.
     */
    val panelList: StateFlow<List<Station>> get() = model.panelList
    val panelIndex: StateFlow<Int> get() = model.panelIndex

    /**
     * The station behind a Media3 item id (item ids are station ids), or null.
     * Lets observers that fire mid-transition - before the published station
     * has caught up - resolve what is actually audible right now instead of
     * reading a stale bus value.
     */
    fun stationForMediaId(id: String): Station? = model.stationForMediaId(id)

    /** Set by the root with recent history so prev/next work from a fresh launch. */
    fun setFallbackSource(list: List<Station>) = model.setFallbackSource(list)


    /**
     * Cold-start queue restore: the last session's list becomes this
     * session's queue, published paused - never auto-played. Whole sources
     * persisted via [persistQueue] restore whole and re-window onto the
     * remembered position; windows restore as before. The mini player
     * and the Up Next screen render the remembered list from the first
     * DataStore emission instead of flashing empty until something plays.
     * Skipped when a play already landed (e.g. auto-resume won the race).
     */
    private suspend fun restoreQueue(prefs: Prefs) {
        val stored = prefs.queue.first()
        if (stored.isEmpty() || model.currentUpNext.isNotEmpty()) return
        if (prefs.queueFull.first()) {
            val idx = prefs.queueIndex.first().coerceIn(0, stored.lastIndex)
            model.source = stored
            model.baseSource = stored
            model.ringFallback = false
            val w = model.rewindow(idx)
            PlaybackBus.publishStation(w.window.getOrNull(w.index))
            PlaybackBus.publishSource(stored)
            val nav = navAvailability()
            onQueueRestored(nav.hasPrev, nav.hasNext)
            return
        }
        val idx = prefs.queueIndex.first().coerceIn(0, stored.lastIndex)
        // Windows restore as a fresh walking order: the next real play
        // rebases onto its own list as before.
        model.restoreWindow(stored, idx)
        PlaybackBus.publishStation(stored[idx])
        PlaybackBus.publishSource(stored)
        val nav = navAvailability()
        onQueueRestored(nav.hasPrev, nav.hasNext)
    }


    /** Whether shuffled playback is switched on. */
    val shuffle: StateFlow<Boolean> get() = model.shuffle

    /** Repeat mode, cycling off/all/one. */
    val repeat: StateFlow<RepeatMode> get() = model.repeat
    val repeatMode: RepeatMode get() = model.repeatMode

    /** Steps the repeat cycle and persists it, so the mode survives restarts. */
    fun toggleRepeat() {
        val next = model.repeatMode.next()
        model.setRepeatMode(next)
        scope.launch {
            (context.applicationContext as KleeampApp).prefs.setRepeat(next.key)
        }
        onSync()
    }


    /**
     * Coalesces rapid transport taps without lagging a plain tap. Each step
     * keeps an absolute target in [model.source] in [_navPending]. A burst of next /
     * prev fires the FIRST tap immediately (isolated taps thus respond at once)
     * and then restarts a short debounce timer on each follow-up, so the burst
     * settles on the LAST tapped song - the songs in between are never (re)played
     * and playback isn't restarted per tap. [_navTimerJob] only waits and hands
     * off to play(); the media job itself lives on the bridge and is unaffected.
     */
    private var _navTimerJob: Job? = null
    private var _navPending: Int? = null
    private var _lastNavTapMs = 0L


    /**
     * Media jobs park unexpected failure as a playback error instead of
     * reaching the uncaught handler and killing the process. Cancellation
     * bypasses the handler by contract, so cooperative cancel keeps working;
     * the UI clears the error on the next successful play. The message stays
     * generic on purpose: exception text can carry signed stream URLs.
     */
    private val mediaFailureHandler = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("kleeamp/player", "playback job failed", e)
        PlaybackBus.publishError("couldn't play this station")
    }

    // Media3 application half: media jobs, the swap guard, window slides and
    // edits. This facade keeps list math, tap debounce and persistence.
    private val bridge = QueueMediaBridge(
        scope, model, controller, buildItem, resumeAt, currentSpeed, onSync, mediaFailureHandler,
    )


    /** Persist the now-current station and push it to the widget, as a single choke point. */
    private fun persistAndRefresh(station: Station) {
        val prefs = (context.applicationContext as KleeampApp).prefs
        // A fresh play means intent-to-play; the service confirms audibility
        // through its own events. The push carries the in-memory row so the
        // widget never waits on the persistence write beside it.
        WidgetRenderer.push(
            context.applicationContext,
            station,
            PlaybackBus.streamTitle.value,
            true,
            seekable = station.isTrack,
            durationMs = station.durationMs,
            positionMs = 0L,
        )
        scope.launch {
            prefs.setLastStation(station)
            prefs.pushHistory(station)
        }
    }

    private fun persistWidgetWindow(station: Station) {
        val windows = QueuePolicy.widgetWindowIndices(
            model.source,
            model.windowBase,
            model.currentIndex,
            station.url,
            model.ringFallback,
        ) ?: return
        val prefs = (context.applicationContext as KleeampApp).prefs
        scope.launch {
            prefs.setWidgetSource(windows.window)
            prefs.setWidgetNext(windows.next)
        }
    }

    /**
     * The Up Next window plus the position inside it, so a cold start reopens
     * on the remembered queue. Finite track lists persist whole (up to
     * [FULL_SOURCE_PERSIST_CAP]) so the full panel survives a force stop;
     * anything bigger - or any mixed/live list - persists the bounded window
     * with predecessors like before.
     */
    private fun persistQueue() {
        val q = model.currentUpNext
        if (q.isEmpty()) return
        val prefs = (context.applicationContext as KleeampApp).prefs
        val src = model.source
        if (src.isNotEmpty() && src.size <= FULL_SOURCE_PERSIST_CAP &&
            (src.all { it.isTrack } || src.all { !it.isTrack })) {
            val abs = (model.windowBase + model.currentIndex)
                .takeIf { model.currentIndex in q.indices }
                ?.coerceIn(0, src.lastIndex) ?: 0
            scope.launch { prefs.setQueue(src, abs, full = true) }
            return
        }
        val (combined, queueIndex) = model.persistWindow(q)
        scope.launch { prefs.setQueue(combined, queueIndex) }
    }


    /** Widget preview follows the same occurrence and finite tail as the Up Next screen. */
    internal fun upcomingStations(count: Int = 4): List<Station> =
        QueuePolicy.upcomingSlice(model.source, model.windowBase + model.currentIndex, model.ringFallback, count)

    /** An Up Next tap targets this occurrence, including when a URL occurs twice. */
    fun playUpNextEntry(index: Int) {
        if (index !in model.currentUpNext.indices) return
        _navTimerJob?.cancel()
        _navPending = model.windowBase + index
        applyNavigation()
    }

    /**
     * Plays the panel position: an absolute source index for full-list
     * panels, a window index otherwise. The navigation machinery (in-place
     * seek vs window slide) is shared with every other play path.
     */
    fun playPanelEntry(panelPosition: Int) {
        val src = model.source
        val abs = if (src.isNotEmpty() && (src.all { it.isTrack } || src.all { !it.isTrack })) {
            if (panelPosition !in src.indices) return
            panelPosition
        } else {
            if (panelPosition !in model.currentUpNext.indices) return
            model.windowBase + panelPosition
        }
        _navTimerJob?.cancel()
        _navPending = abs
        applyNavigation()
    }

    /**
     * A list tap always starts that list at the tapped item: Up next is
     * rebuilt from the items that follow it in the list, in the list's order.
     * Manual Up next edits survive until the next list tap, never past it.
     */
    fun play(station: Station, from: List<Station> = emptyList()) {
        startPlayback(station, from, preserveOrder = false)
    }

    // Single play choke point: the model swap stays atomic with the bridge
    // handoff, so sync never sees a half-built queue.
    private fun startPlayback(
        station: Station,
        from: List<Station>,
        preserveOrder: Boolean,
        sourceIndex: Int? = null,
    ) {
        cancelPendingPlayback()
        clearUndo()
        if (from.isNotEmpty()) {
            model.playFromList(station, from, preserveOrder, sourceIndex)
        } else {
            model.playFromWindow(station)
        }
        val upNext = model.currentUpNext
        val start = model.windowBase + model.currentIndex.coerceAtLeast(0)

        PlaybackBus.publishStation(station)
        PlaybackBus.publishSource(model.source)
        history.record(station)
        persistWidgetWindow(station)
        persistQueue()
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())

        // Persist what is playing so the widget, the quick tile and a fresh
        // launch all agree on the current station. This is the single play
        // choke point, so it covers app taps, the notification, the widget and
        // auto-advance - not just plays fired through the root's onPlay hook.
        persistAndRefresh(station)

        // The tapped station is published and rendered first, on the calling
        // thread, so the music screen and mini bar update the instant a song is
        // touched; the bridge builds the Media3 queue right after.
        bridge.playNew(station, upNext, start)
    }

    private fun cancelPendingPlayback() {
        _navTimerJob?.cancel()
        _navTimerJob = null
        _navPending = null
        bridge.cancelMedia()
    }

    /**
     * Multi-step undo for queue edits (remove / reorder / clear / insert).
     * Each snapshot captures the whole walking order plus the window, taken
     * before the edit mutates anything; each undo pops one step, so the user
     * can walk every edit back to the start state, where the UNDO key hides
     * itself. A fresh play starts a new history and drops the stack.
     * Restoring rebuilds the Media3 window around the same audible item,
     * keeping its position when it survives the undo. The stack is capped so
     * a long editing session cannot pin old sources in memory.
     */
    private data class QueueSnapshot(
        val source: List<Station>,
        val baseSource: List<Station>,
        val windowBase: Int,
        val window: List<Station>,
        val index: Int,
    )

    private val undoStack = ArrayDeque<QueueSnapshot>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> get() = _canUndo.asStateFlow()

    private fun snapshotForUndo() {
        // Captures the fully pre-edit model. Call at the top of every public
        // edit op, before ANY setWindow/source mutation - never from
        // applyUpNextToPlayer, which runs after callers have mutated.
        undoStack.addLast(
            QueueSnapshot(
                source = model.source,
                baseSource = model.baseSource,
                windowBase = model.windowBase,
                window = model.currentUpNext,
                index = model.currentIndex,
            ),
        )
        if (undoStack.size > MAX_UNDO_DEPTH) undoStack.removeFirst()
        _canUndo.value = true
    }

    private fun clearUndo() {
        undoStack.clear()
        _canUndo.value = false
    }

    /** Restores the queue as it was before the last edit; hides at the start state. */
    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        _canUndo.value = undoStack.isNotEmpty()
        cancelPendingPlayback()
        model.source = snap.source
        model.baseSource = snap.baseSource
        model.windowBase = snap.windowBase
        model.setWindow(snap.window, snap.index)
        model.ringFallback = false
        PlaybackBus.publishSource(model.source)
        PlaybackBus.station.value?.let(::persistWidgetWindow)
        persistQueue()
        bridge.rebuildWindow(snap.window, snap.index)
    }

    /**
     * Toggles shuffled playback of the current list. When switched on, the list
     * the user is playing (local songs, favourites, a provider album, a station
     * wall - whatever [model.source] holds) is re-ordered so the order of the list is
     * shuffled, always keeping the current item first. Toggling off restores
     * the original linear order from [model.baseSource]. On a lone item there is
     * nothing to reorder, so only the flag flips.
     */
    fun toggleShuffle() {
        // Model reorder plus a seamless Media3 head/tail swap. Both the model
        // and the player end up on the new order so sync() never sees a
        // mismatch (a mismatch is what made a cardboard shuffle either only
        // reach the loaded window - "a few random songs" - or show one song
        // while playing another) - but the audible item itself is never
        // touched, so there is no rebuffer or seek either.
        val newOn = !model.shuffleOn
        val c = controller()
        if (c == null || model.source.isEmpty()) { model.setShuffled(newOn); onSync(); return }
        val base = model.baseSource.ifEmpty { model.source }

        // Anchor "current" on Media3's LIVE audible item, not the model's
        // index - sync() reconciles the model on a slow poll, so it can lag a
        // track auto-advance. The live item is the only anchor that cannot
        // drift; the resolution itself is pure policy, unit-tested.
        val audibleId = if (c.mediaItemCount > 0) c.getMediaItemAt(
            c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
        ).mediaId else null
        val anchor = QueuePolicy.shuffleAnchor(
            model.source, base, model.windowBase, model.currentIndex,
            model.currentUpNext.firstOrNull(), audibleId,
        )
        model.setShuffled(newOn)
        if (newOn) {
            if (model.source.size < 2) { onSync(); return }
            model.replaceOrder(QueuePolicy.shuffleReorder(base, anchor.baseIndex, anchor.current), base)
        } else {
            model.replaceOrder(base, base)
        }

        // Re-anchor the model immediately so the panel shows the new order
        // instantly. The Media3 tail is then swapped around the still-playing
        // item by the bridge, without touching it, so no rebuffer or seek.
        bridge.cancelShuffleWork()
        val playWindow = model.rewindow(anchor.baseIndex)
        val slice = playWindow.window
        val idx = playWindow.index
        if (!(slice.all { it.isTrack } && slice.size > 1)) {
            // Anything else keeps the single-item shape play() gave it, and
            // that item is the current station itself: reordering the model
            // IS the shuffle, and swapping Media3 would only rebuffer the
            // same stream (plus resolve dozens of stations for nothing).
            onSync()
            return
        }
        // Track playlist: keep the audible item playing exactly where it is
        // and only swap the items around it. The old code rebuilt the whole
        // Media3 queue, throwing away the current decoder and reloading the
        // same song: an audible gap plus up to WINDOW item resolves on Main.
        bridge.swapShuffle(slice, idx, anchor.current.id)
    }

    /**
     * Next follows the active Up Next list, including edits made after stepping back.
     * A history redo is only a fallback when no active source exists.
     */
    fun next() {
        if (model.source.isNotEmpty()) {
            step(+1)
            return
        }
        // Claimed under lock so the goto's own record sees its tip and no-ops.
        val fwd = history.forward()
        if (fwd != null) {
            gotoHistory(fwd)
            return
        }
        step(+1)
    }

    /**
     * Prev walks the current context backward - the mirror of [next] - so it
     * behaves the same in every source and never leaves the list for another
     * context. Walking back past the first heard song keeps stepping into
     * earlier items with Up Next following. The heard trail is only a
     * fallback when nothing is loaded yet.
     */
    fun prev() {
        if (model.source.isNotEmpty()) {
            step(-1)
            return
        }
        val back = history.back()
        if (back != null) {
            gotoHistory(back)
            return
        }
        step(-1)
    }

    /**
     * Jumps to a history item without recording (the caller pre-stepped, so
     * the record is a duplicate by construction). Reuses [applyNavigation]'s
     * fast path when the item lives in the current context, else starts it
     * as a fresh single - a foreign context, same as tapping it would.
     */
    private fun gotoHistory(station: Station) {
        model.ringFallback = false
        // A manual jump supersedes any coalescing burst still waiting.
        _navTimerJob?.cancel()
        _navTimerJob = null
        val target = history.gotoTarget(station, model.source, model.fallback)
        if (target != null) {
            _navPending = target
            applyNavigation()
        } else {
            play(station)
        }
    }

    /**
     * Moves prev/next by [delta]. Each tap targets exactly one song forward or
     * back from the currently committed track. Rapid taps coalesce on a single
     * pending absolute target so parallel Media3 re-queues can't race each other
     * and skip past the song you actually tapped to.
     */
    // Coalesced tap targeting across pending, model, live and bus positions in one place.
    private fun step(delta: Int) {
        val src = model.source.ifEmpty { model.fallback }
        if (src.isEmpty()) return
        // Base prev/next on the latest pending target (if any) rather than the
        // committed index, so rapid taps stack onto one another instead of all
        // collapsing onto the same song. Either way the value is an absolute
        // index into [model.source].
        val pending = _navPending
        // The model records an explicit selection before Media3 finishes
        // loading it. Prefer that occurrence over a first-URL match, which
        // would rewind duplicate songs. Media3 and the bus are fallbacks for
        // playback restored outside this session's queue.
        val busUrl = PlaybackBus.station.value?.url
        val liveHere = controller()?.let { c ->
            if (c.mediaItemCount > 1) model.windowBase + c.currentMediaItemIndex else null
        }
        val here = QueuePolicy.resolveHere(
            pending,
            QueuePolicy.modelHereIndex(model.windowBase, model.currentIndex, src, busUrl),
            liveHere,
            QueuePolicy.busHereIndex(src, busUrl),
        )
        val mode = if (model.source.isEmpty() && pending == null) {
            val shown = (PlaybackBus.station.value ?: src.firstOrNull())?.let { s ->
                src.indexOfFirst { it.url == s.url }
            } ?: -1
            // Launch fallback: walk history as a ring so the first prev/next
            // from the restored song have somewhere to go.
            QueuePolicy.StepMode.Cold(shown)
        } else {
            QueuePolicy.stepMode(model.source.isEmpty(), pending != null, model.ringFallback, shown = -1)
        }
        if (mode != QueuePolicy.StepMode.Linear) model.ringFallback = true
        val abs = QueuePolicy.stepTarget(mode, src.size, here, delta, model.repeatMode)
        if (abs == here && model.source.isNotEmpty() && pending == null) return

        val now = android.os.SystemClock.uptimeMillis()
        val leadingEdge = now - _lastNavTapMs > NAV_DEBOUNCE_MS
        _lastNavTapMs = now
        _navPending = abs
        if (leadingEdge) {
            // Isolated tap: navigate at once so next / prev feel instant.
            _navTimerJob?.cancel()
            _navTimerJob = null
            applyNavigation()
        } else {
            // Rapid burst: wait out the taps, then land on the final one.
            startNavTimer()
        }
    }

    /**
     * Fires the pending target now (no timer). When the target is already one
     * of the items loaded in Media3's current window we switch to it in place
     * with seekTo, which picks an already-prepared item - no setMediaItems, no
     * re-prepare, no decode gap - so prev / next are effectively instant. Only
     * when the target lies outside the window (or nothing is loaded) do we fall
     * back to [play], which rebuilds the queue.
     */
    private fun applyNavigation() {
        val target = _navPending ?: return
        _navPending = null
        // A manual seek and the auto window-roll both rewrite Media3; if the
        // delayed roll lands on top of a user's seekTo it re-reads the seek's
        // index as a position in the freshly-rolled window and snaps playback
        // to the wrong (repeated) song - the "won't advance / plays same song
        // again" stall. Cancel any pending roll so a manual next/prev wins.
        bridge.cancelExtend()
        val src = model.source.ifEmpty { model.fallback }
        if (src.isEmpty()) return
        val abs = if (model.ringFallback && src.size > 1) ((target % src.size) + src.size) % src.size
        else target.coerceIn(0, src.lastIndex)
        val station = src[abs]
        val targetInWindow = abs in model.windowBase until (model.windowBase + model.currentUpNext.size)
        if (targetInWindow) model.setIndex(abs - model.windowBase)

        // Publish and persist the target exactly as play() would, so the UI and
        // the widget agree the instant the song is tapped - even before Media3
        // has switched over.
        PlaybackBus.publishStation(station)
        PlaybackBus.publishSource(src)
        history.record(station)
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())
        persistWidgetWindow(station)
        persistAndRefresh(station)

        val q = model.currentUpNext
        val c = controller()
        val inWindow = c != null && !bridge.isSwapping && q.isNotEmpty() &&
            abs >= model.windowBase && abs < model.windowBase + q.size
        if (inWindow) {
            bridge.seekOrSlide(station, q, abs - model.windowBase, abs)
        } else {
            startPlayback(station, src, preserveOrder = true, sourceIndex = abs)
        }
    }

    /**
     * Restarts the coalescing timer. When it fires (taps have settled) we
     * navigate ONCE to the last tapped target. The timer only waits and hands
     * off to play(); it never cancels an in-flight media job.
     */
    private fun startNavTimer() {
        _navTimerJob?.cancel()
        _navTimerJob = scope.launch(Dispatchers.Main) {
            try {
                delay(NAV_DEBOUNCE_MS)
            } catch (_: CancellationException) {
                return@launch
            }
            if (_navTimerJob === coroutineContext[Job]) _navTimerJob = null
            applyNavigation()
        }
    }

    /** Apply a window edit without losing the unloaded continuation of a long list. */
    private fun applyUpNextToPlayer(
        previousUpNext: List<Station>,
        edit: ((MediaController) -> Unit)? = null,
    ) {
        cancelPendingPlayback()
        val q = model.currentUpNext
        model.ringFallback = false
        val idx = if (q.isEmpty()) -1 else model.currentIndex.coerceIn(0, q.lastIndex)
        val head = model.source.take(model.windowBase)
        val tail = model.source.drop(model.windowBase + previousUpNext.size)
        model.source = head + q + tail
        model.baseSource = model.source
        model.setIndex(idx)
        PlaybackBus.publishSource(model.source)
        PlaybackBus.station.value?.let(::persistWidgetWindow)
        persistQueue()
        bridge.applyEdit(q, idx, previousUpNext, edit)
    }

    /** Insert without changing the originating list. Append means the full tail, not just its window. */
    fun addToUpNext(station: Station, at: Int = Int.MAX_VALUE) {
        snapshotForUndo()
        val previous = model.currentUpNext
        if (at == Int.MAX_VALUE && model.windowBase + previous.size < model.source.size) {
            bridge.cancelExtend()
            model.source = model.source + station
            model.baseSource = model.source
            model.publishPanel()
            PlaybackBus.publishSource(model.source)
            persistQueue()
            onSync()
            return
        }
        val inserted = QueuePolicy.insert(previous, at, model.currentIndex, station)
        model.setWindow(inserted.queue, inserted.currentIndex)
        applyUpNextToPlayer(previous)
    }

    /** Insert [station] right after the currently-playing item (play next). */
    fun playNext(station: Station) {
        addToUpNext(station, model.playNextInsertAt())
    }

    /** Play [station] with [from] as a brand-new Up Next list, replacing whatever was there. */
    fun replaceUpNext(station: Station, from: List<Station>) {
        play(station, from)
    }

    /** Drop the panel position, keeping the playing item stable. */
    fun removePanelEntry(panelPosition: Int) {
        val src = model.source
        if (src.isNotEmpty() && (src.all { it.isTrack } || src.all { !it.isTrack })) {
            if (panelPosition !in src.indices) return
            val base = model.windowBase
            val size = model.currentUpNext.size
            if (panelPosition in base until base + size) {
                removeFromUpNext(panelPosition - base)
                return
            }
            snapshotForUndo()
            model.source = src.toMutableList().also { it.removeAt(panelPosition) }
            model.baseSource = model.source
            if (panelPosition < base) model.windowBase = base - 1
            model.publishPanel()
            PlaybackBus.publishSource(model.source)
            PlaybackBus.station.value?.let(::persistWidgetWindow)
            persistQueue()
            onSync()
            return
        }
        removeFromUpNext(panelPosition)
    }

    /** Drop the station at [index], keeping the playing item stable. */
    fun removeFromUpNext(index: Int) {
        val q = model.currentUpNext
        if (index !in q.indices) return
        snapshotForUndo()
        val (newQ, newIdx) = QueuePolicy.remove(q, index, model.currentIndex)
        model.setWindow(newQ, newIdx)
        applyUpNextToPlayer(previousUpNext = q, edit = { it.removeMediaItem(index) })
    }

    /** Move the station at [from] to [to], keeping the playing item stable. */
    fun reorderUpNext(from: Int, to: Int) {
        val q = model.currentUpNext
        if (from !in q.indices || to !in q.indices || from == to) return
        snapshotForUndo()
        val qi = model.currentIndex
        val item = q[from]
        val moved = q.toMutableList().apply {
            removeAt(from)
            add(to, item)
        }
        model.setWindow(
            moved,
            // the playing station follows its item through the move
            upNextIndexAfterMove(qi, from, to),
        )
        applyUpNextToPlayer(previousUpNext = q, edit = { it.moveMediaItem(from, to) })
    }

    /**
     * Moves the panel position, keeping the playing item stable. Inside the
     * player window this is the seamless in-place move; across the window
     * edge the source reorders and the window rebuilds around the audible
     * item, keeping its position when it survives.
     */
    fun movePanelEntry(fromAbs: Int, toAbs: Int) {
        val src = model.source
        if (src.isEmpty() || !(src.all { it.isTrack } || src.all { !it.isTrack })) {
            val base = model.windowBase
            val size = model.currentUpNext.size
            if (fromAbs in base until base + size && toAbs in base until base + size) {
                reorderUpNext(fromAbs - base, toAbs - base)
            }
            return
        }
        if (fromAbs !in src.indices || toAbs !in src.indices || fromAbs == toAbs) return
        val base = model.windowBase
        val size = model.currentUpNext.size
        if (fromAbs in base until base + size && toAbs in base until base + size) {
            reorderUpNext(fromAbs - base, toAbs - base)
            return
        }
        snapshotForUndo()
        val reordered = src.toMutableList()
        val item = reordered.removeAt(fromAbs)
        reordered.add(toAbs.coerceIn(0, reordered.size), item)
        model.source = reordered
        model.baseSource = reordered
        model.ringFallback = false
        val audibleId = model.currentUpNext.getOrNull(model.currentIndex)?.id
        val abs = reordered.indexOfFirst { it.id == audibleId }.coerceAtLeast(0)
        val w = model.rewindow(abs)
        PlaybackBus.publishSource(reordered)
        PlaybackBus.station.value?.let(::persistWidgetWindow)
        persistQueue()
        bridge.rebuildWindow(w.window, w.index)
    }

    /** Clear only pending playback; keep the audible item and its source identity. */
    fun clearUpNext() {
        snapshotForUndo()
        val previous = model.currentUpNext
        val currentIndex = model.currentIndex
        val (kept, keptIdx) = QueuePolicy.clearPending(previous, currentIndex)
        model.setWindow(kept, keptIdx)
        model.source = kept
        model.windowBase = 0
        applyUpNextToPlayer(previousUpNext = previous, edit = { player ->
            player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
            if (currentIndex > 0) player.removeMediaItems(0, currentIndex)
        })
    }

    internal val isTransitioning: Boolean
        get() = bridge.isTransitioning

    /** Prev/next availability for the transport state; mirrors the nav math sync reads. */
    internal fun navAvailability(): QueuePolicy.NavAvailability {
        val nav = model.source.ifEmpty { model.fallback }
        // Repeat-all walks every list as a ring, so both keys always have
        // somewhere to go.
        if (model.repeatMode == RepeatMode.All && nav.size > 1) {
            return QueuePolicy.NavAvailability(hasPrev = true, hasNext = true)
        }
        val busUrl = PlaybackBus.station.value?.url ?: nav.firstOrNull()?.url
        return QueuePolicy.navAvailability(
            model.source,
            model.fallback,
            model.ringFallback,
            model.windowBase + model.currentIndex,
            QueuePolicy.busHereIndex(nav, busUrl),
            QueuePolicy.Trail(history.size, history.index),
        )
    }

    /**
     * Read-only preview of what prev/next would land on: the same list, the
     * same anchor and the same walk [step] uses, without stepping, debouncing
     * or publishing. Null per side exactly when that key would be a no-op.
     * Cover previews read this, never the bounded Up Next window: the window
     * holds one item for lone plays and a capped run for huge lists, while
     * navigation walks the whole source, the launch fallback ring and the
     * heard trail.
     */
    internal fun peekAdjacent(): Pair<Station?, Station?> {
        if (model.source.isNotEmpty()) {
            val src = model.source
            val busUrl = PlaybackBus.station.value?.url
            val liveHere = controller()?.let { c ->
                if (c.mediaItemCount > 1) model.windowBase + c.currentMediaItemIndex else null
            }
            val here = QueuePolicy.resolveHere(
                null,
                QueuePolicy.modelHereIndex(model.windowBase, model.currentIndex, src, busUrl),
                liveHere,
                QueuePolicy.busHereIndex(src, busUrl),
            )
            val mode = QueuePolicy.stepMode(
                sourceEmpty = false,
                hasPending = false,
                ringFallback = model.ringFallback,
                shown = -1,
            )
            val (prevIdx, nextIdx) = QueuePolicy.peekTargets(src.size, here, mode, sourceNonEmpty = true)
            // Same settle applyNavigation applies, so the preview is the
            // landed index even where the mode and the settle disagree.
            fun settle(i: Int?): Station? = i?.let {
                if (model.ringFallback && src.size > 1) ((it % src.size) + src.size) % src.size
                else it.coerceIn(0, src.lastIndex)
            }?.let(src::get)
            return settle(prevIdx) to settle(nextIdx)
        }
        // Cold: the keys try the heard trail first, then the fallback ring.
        val histPrev = history.peek(-1)
        val histNext = history.peek(1)
        val src = model.fallback
        if (src.isEmpty()) return histPrev to histNext
        val shown = (PlaybackBus.station.value ?: src.firstOrNull())?.let { s ->
            src.indexOfFirst { it.url == s.url }
        } ?: -1
        val (prevIdx, nextIdx) = QueuePolicy.peekTargets(
            src.size, here = 0, QueuePolicy.StepMode.Cold(shown), sourceNonEmpty = false,
        )
        return (histPrev ?: prevIdx?.let(src::get)) to (histNext ?: nextIdx?.let(src::get))
    }

    /**
     * Reconciles the Up Next window against what Media3 is actually holding,
     * publishing when the audible item genuinely changed. Called from sync.
     * Resolution is by id, never by stored index: a stored index is what
     * caused "shows one song while playing another" when the window and
     * Media3's queue drifted out of phase.
     */
    internal fun reconcileMediaWindow(c: Player, changingPlayback: Boolean) {
        val q = model.currentUpNext
        if (!changingPlayback && c.mediaItemCount > 0 && q.isNotEmpty()) {
            val playerIndex = c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
            // Radio (or a lone track) is pushed as a single Media3 item while the
            // panel still shows a full window around it, so Media3's index can't
            // be mapped straight onto the window - that would repoint the active
            // item back to the head of the list. Only read back the index when
            // Media3 actually holds the whole queue.
            val oneToOne = c.mediaItemCount == q.size
            val curId = c.getMediaItemAt(playerIndex).mediaId
            val resolvedIndex: Int? = if (oneToOne &&
                q.getOrNull(playerIndex)?.id == curId
            ) {
                playerIndex
            } else if (c.mediaItemCount == 1) {
                QueuePolicy.singleItemIndex(q, model.currentIndex, curId)
            } else if (bridge.mirrorWindowFromMedia3(c)) {
                playerIndex
            } else {
                null
            }
            if (resolvedIndex != null) {
                val resolved = model.currentUpNext.getOrNull(resolvedIndex)
                model.setIndex(resolvedIndex)
                if (resolved != null) publishResolved(resolved)
            }

            // Append the next window and discard only played entries. Never seek
            // to the next song early or restart the audible item at a boundary.
            if (QueuePolicy.shouldExtend(
                    oneToOne, model.windowBase, q.size, model.source.size, model.currentIndex,
                )
            ) {
                bridge.launchExtend(c)
            }
        }
    }

    /**
     * Publishes a reconciled item when it genuinely differs from the bus - by
     * id, not by queue index, so a re-mirrored window can't freeze the title
     * on a stale song. The heard trail learns Media3-side moves here, never
     * from raw transitions: a superseded seek's late transition would fork
     * the trail with a ghost entry, and Prev would jump and loop.
     */
    private fun publishResolved(resolved: Station) {
        if (resolved.id != PlaybackBus.station.value?.id) {
            PlaybackBus.publishStation(resolved)
            history.record(resolved)
        }
    }

    /**
     * Advances when a finite item plays to its end before the next window
     * could be appended: repeat-one replays the audible item, repeat-all
     * wraps past the tail, otherwise the next item plays. Called from sync.
     * Live streams never end on their own, so repeat-one cannot stall one;
     * anything actually reporting ended is finite and may repeat.
     */
    internal fun advanceOnEnded(changingPlayback: Boolean, ended: Boolean) {
        if (changingPlayback || !ended) return
        if (model.currentUpNext.getOrNull(model.currentIndex) == null) return
        when (model.repeatMode) {
            RepeatMode.One -> bridge.replayCurrent()
            RepeatMode.All -> {
                _navTimerJob?.cancel()
                val abs = model.windowBase + model.currentIndex
                _navPending = if (abs >= model.source.lastIndex) 0 else abs + 1
                applyNavigation()
            }
            RepeatMode.Off -> {
                if (model.windowBase + model.currentIndex < model.source.lastIndex) {
                    _navTimerJob?.cancel()
                    _navPending = model.windowBase + model.currentIndex + 1
                    applyNavigation()
                }
            }
        }
    }
}
