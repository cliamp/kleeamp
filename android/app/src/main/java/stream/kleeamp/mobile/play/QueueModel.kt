package stream.kleeamp.mobile.play

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import stream.kleeamp.mobile.model.Station

/**
 * The queue's data: source lists, window position and play flags, plus the
 * model halves of play, slide and shuffle. No MediaController, no Context,
 * no scopes - transitions are plain mutations on this holder, covered by
 * QueueModelTest. The Media3 halves live behind
 * [stream.kleeamp.mobile.playback.QueueController].
 */
class QueueModel {
    /**
     * The full list a play originated from. When that list is huge - hundreds
     * of local songs - the window only holds a bounded run starting at the
     * tapped track, so touching a song queues a short "up next" run instead
     * of dumping the whole library in. [source] keeps the whole list so prev
     * / next and the auto-roll-forward can keep walking it. For a short list
     * [source] and the window are the same list.
     */
    var source: List<Station> = emptyList()

    /** The linear list a play originated from, before any shuffle reordering.
     * Shuffle reorders a copy of it into [source]; turning shuffle off restores
     * [source] to this so prev / next walk the natural playlist order again.
     */
    var baseSource: List<Station> = emptyList()

    /**
     * List used for prev / next before anything has played this session (e.g. at
     * launch, when the mini player shows the last-played song from history).
     * The root supplies recent history so stepping works from that shown song.
     */
    var fallback: List<Station> = emptyList()

    /**
     * Logical index (into [source]) of the first item held in the window. When
     * a queue is huge the tapped track plus a small tail are queued instead of
     * the whole thing, so play starts instantly; this offset says where that
     * window begins inside the source list. For a short queue it is 0.
     */
    var windowBase: Int = 0

    /**
     * While navigation is walking the launch-seeded fallback (nothing has been
     * played from a real list this session), prev/next run it as a ring - the
     * same wrap the widget uses - so both keys always have somewhere to go from
     * the restored song. Cleared the moment the user plays from an actual list.
     */
    var ringFallback: Boolean = false

    private val _upNext = MutableStateFlow<List<Station>>(emptyList())
    val upNext: StateFlow<List<Station>> = _upNext.asStateFlow()
    private val _upNextIndex = MutableStateFlow(-1)
    val upNextIndex: StateFlow<Int> = _upNextIndex.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _repeat = MutableStateFlow(RepeatMode.Off)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    val currentUpNext: List<Station> get() = _upNext.value
    val currentIndex: Int get() = _upNextIndex.value

    /**
     * What the Up Next screen lists. Finite track lists (local files,
     * episodes, provider albums, channel tracks) show the whole source so
     * nothing queued reads as lost; live-mixed lists keep the bounded
     * player window. The player itself always walks the window.
     */
    private val _panelList = MutableStateFlow<List<Station>>(emptyList())
    val panelList: StateFlow<List<Station>> = _panelList.asStateFlow()
    private val _panelIndex = MutableStateFlow(-1)
    val panelIndex: StateFlow<Int> = _panelIndex.asStateFlow()
    val shuffleOn: Boolean get() = _shuffle.value
    val repeatMode: RepeatMode get() = _repeat.value

    fun setShuffled(on: Boolean) {
        _shuffle.value = on
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeat.value = mode
    }

    fun setFallbackSource(list: List<Station>) {
        fallback = list
    }

    fun setWindow(window: List<Station>, index: Int) {
        _upNext.value = window
        _upNextIndex.value = index
        publishPanel()
    }

    fun setIndex(index: Int) {
        _upNextIndex.value = index
        publishPanel()
    }

    /**
     * Refreshes the panel list and the position inside it after any
     * window or source change. Emits only on real change, so the hot
     * 2 Hz reconcile tick that re-sets an identical index costs nothing.
     */
    fun publishPanel() {
        val window = _upNext.value
        val (list, idx) =
            if (source.isNotEmpty()) {
                val abs = (windowBase + _upNextIndex.value)
                    .takeIf { _upNextIndex.value in window.indices && it in source.indices }
                    ?: -1
                source to abs
            } else {
                window to _upNextIndex.value
            }
        _panelList.value = list
        _panelIndex.value = idx
    }

    /** Where play-next lands: right after current, or appended when nothing is current. */
    fun playNextInsertAt(): Int =
        if (currentIndex in 0 until currentUpNext.size) currentIndex + 1 else currentUpNext.size

    fun stationForMediaId(id: String): Station? =
        _upNext.value.firstOrNull { it.id == id } ?: source.firstOrNull { it.id == id }

    /**
     * Applies a computed window: the base offset plus the visible list and
     * the index inside it. Returns the window for the Media3 half.
     */
    fun rewindow(absIndex: Int): QueuePolicy.PlayWindow {
        val w = QueuePolicy.playWindow(source, absIndex)
        windowBase = w.base
        _upNext.value = w.window
        _upNextIndex.value = w.index
        publishPanel()
        return w
    }

    /** Replaces the walking order, e.g. after a shuffle rebuild. */
    fun replaceOrder(newSource: List<Station>, newBase: List<Station>) {
        source = newSource
        baseSource = newBase
    }

    /**
     * Restores a persisted window as the whole walking order: the full
     * source list is deliberately not persisted, so navigation walks the
     * restored window until the next real play rebases it.
     */
    fun restoreWindow(window: List<Station>, index: Int) {
        setWindow(window, index)
        source = window
        baseSource = window
        windowBase = 0
        publishPanel()
    }

    /**
     * Cold-start persist encoding for [window]: the window alone starts at
     * the tapped track, so up to [prevKeep] predecessors from the source
     * ride in front of it - linear and clamped, never wrapped, so a finite
     * list still reads in its own order. Returns the combined list and the
     * index inside it. Callers skip empty windows, as before.
     */
    fun persistWindow(window: List<Station>, prevKeep: Int = QueuePolicy.PREV_KEEP): Pair<List<Station>, Int> {
        val idx = currentIndex.coerceIn(0, window.lastIndex)
        val runUp = source.takeIf { it.size > window.size }
            ?.subList((windowBase - prevKeep).coerceAtLeast(0), windowBase)
            ?: emptyList()
        return Pair(runUp + window, runUp.size + idx)
    }

    /**
     * Model half of a list tap: the tap hands navigation over to that list
     * (linear, not the launch fallback ring), capped to a bounded window
     * around the tapped track. The active order is linear, or shuffled if
     * shuffle is on with the tapped track kept first.
     */
    fun playFromList(
        station: Station,
        from: List<Station>,
        preserveOrder: Boolean,
        sourceIndex: Int?,
    ) {
        // A play tapped on a real screen hands navigation over to that list
        // (linear, not the launch fallback ring).
        if (!preserveOrder) ringFallback = false
        // Cap the queued list to a bounded window around the tapped track so
        // a huge source (the whole local library) doesn't flood the queue.
        // The active order is linear, or shuffled if shuffle is on; the
        // tapped track keeps playing first, so it heads the shuffled list.
        // Navigation (step) passes preserveOrder so it rides the already
        // shuffled list instead of re-randomising - and restarting - on
        // every prev/next.
        val order = if (!preserveOrder && _shuffle.value && from.size > 1) {
            QueuePolicy.shuffledKeepFirst(from, station)
        } else {
            from
        }
        // A fresh play from a list rebases the shuffle bookkeeping on that
        // list's own order - already the user's chosen sort from the screen
        // the tap came from. Without this rebase a stale base from an
        // earlier play survives, so a later shuffle toggle rebuilds - and
        // toggling off restores - the *previous* list instead of this one.
        // Navigation (preserveOrder) rides the already-active order, so it
        // must leave that bookkeeping untouched.
        if (!preserveOrder) {
            baseSource = from
        }
        source = order
        val srcIdx = sourceIndex?.takeIf { it in order.indices }
            ?: order.indexOfFirst { it.url == station.url }.coerceAtLeast(0)
        rewindow(srcIdx)
    }

    /**
     * Model half of a play with no source list: a lone station becomes a
     * one-item queue, otherwise the current window is kept (re-shuffled
     * when shuffle is on) and the station is looked up inside it.
     */
    fun playFromWindow(station: Station) {
        val q = _upNext.value
        if (q.none { it.url == station.url }) {
            baseSource = listOf(station)
            source = listOf(station)
            _upNext.value = listOf(station)
            _upNextIndex.value = 0
            windowBase = 0
        } else {
            baseSource = q
            val order = if (_shuffle.value && q.size > 1) QueuePolicy.shuffledKeepFirst(q, station) else q
            source = order
            _upNext.value = order
            _upNextIndex.value = order.indexOfFirst { it.url == station.url }
            windowBase = 0
        }
        publishPanel()
    }
}
