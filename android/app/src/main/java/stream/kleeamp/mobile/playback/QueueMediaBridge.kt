package stream.kleeamp.mobile.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.play.QueueModel
import stream.kleeamp.mobile.play.QueuePolicy

/**
 * The Media3 half of the queue: applying the model's windows to the player.
 * The list math lives in [QueueModel]/[QueuePolicy] behind [QueueController];
 * this class never decides WHAT plays, only HOW it reaches Media3 without
 * gaps, rebuffers or re-resolves.
 *
 * Jobs: media work parks on [navJob]/[shuffleJob]/[extending], all on Main
 * with the shared failure handler, so a poller tick can never collide a stale
 * window with a fresh one. Raised while a queue swap is in flight, [swapping]
 * keeps sync out of the bookkeeping until the model and Media3 agree again.
 * The tap-coalescing debounce timer stays with the controller facade.
 */
@UnstableApi
internal class QueueMediaBridge(
    private val scope: CoroutineScope,
    private val model: QueueModel,
    private val controller: () -> MediaController?,
    private val buildItem: suspend (Station) -> MediaItem,
    private val resumeAt: suspend (Station) -> Long,
    private val currentSpeed: () -> Float,
    private val onSync: () -> Unit,
    private val failureHandler: CoroutineExceptionHandler,
) {
    @Volatile
    private var swapping = false
    private var navJob: Job? = null
    // The shuffle rebuild deliberately lives off [navJob]. A toggle followed
    // immediately by prev/next (or an auto-advance) cancels [navJob] to stop
    // navigation racing a stale rebuild; routing the shuffle rebuild through
    // [navJob] meant that same cancel killed the shuffle window before it was
    // applied, snapping playback back to the linear queue. This job is only
    // cancelled by a new explicit play or a new toggle, so a shuffle lands.
    private var shuffleJob: Job? = null
    private var extending: Job? = null

    val isTransitioning: Boolean
        get() = swapping || navJob?.isActive == true || shuffleJob?.isActive == true

    /** Raw swap guard, for the seek-in-place eligibility check. */
    val isSwapping: Boolean
        get() = swapping

    /** Cancels all media work and drops the swap guard. */
    fun cancelMedia() {
        navJob?.cancel()
        shuffleJob?.cancel()
        extending?.cancel()
        extending = null
        swapping = false
    }

    /** Cancels a pending window roll; a manual seek or edit wins over it. */
    fun cancelExtend() {
        extending?.cancel()
        extending = null
    }

    /**
     * Reloads Media3 with exactly [items] at [index]: the undo path, which
     * restores a dropped or reordered window the incremental edits cannot
     * express. The audible item keeps its position when it survives the
     * restore; playback intent (playing or paused) is preserved.
     */
    fun rebuildWindow(items: List<Station>, index: Int) {
        navJob?.cancel()
        swapping = true
        navJob = scope.launch(Dispatchers.Main + failureHandler) {
            val c = controller() ?: return@launch
            try {
                ensureActive()
                if (items.isEmpty()) {
                    c.clearMediaItems()
                    return@launch
                }
                val keep = c.currentMediaItem?.mediaId
                    ?.let { id -> items.getOrNull(index)?.id == id } == true
                val position = if (keep) c.currentPosition.coerceAtLeast(0) else 0L
                val wasPlaying = c.playWhenReady
                val built = items.map { buildItem(it) }
                ensureActive()
                c.setMediaItems(built, index.coerceIn(0, built.lastIndex), position)
                c.prepare()
                if (wasPlaying) c.play() else c.pause()
            } finally {
                swapping = false
            }
            ensureActive()
            onSync()
        }
    }

    /**
     * Replays the audible item from its head without touching the queue:
     * no window rebuild, no re-prepare gap, no resume position. The live
     * Media3 index wins over the model, which can lag an auto-advance.
     */
    fun replayCurrent() {
        val c = controller() ?: return
        if (c.mediaItemCount == 0) return
        c.seekTo(c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1), 0L)
        c.play()
        onSync()
    }

    /** Cancels shuffle/extend work; a new toggle supersedes both. */
    fun cancelShuffleWork() {
        shuffleJob?.cancel()
        shuffleJob = null
        cancelExtend()
    }

    /**
     * Builds the Media3 queue for a fresh play. A window Media3 already holds
     * switches in place by index; anything else slides or loads single.
     */
    fun playNew(station: Station, upNext: List<Station>, start: Int) {
        navJob?.cancel()
        shuffleJob?.cancel()
        swapping = controller() != null
        navJob = scope.launch(Dispatchers.Main + failureHandler) {
            val c = controller() ?: return@launch
            // Any queue of finite tracks is a real playlist, so Media3 plays one
            // after another regardless of where they came from: local files and
            // provider albums alike. A live radio stream has no end to advance
            // from, so it is pushed as ONE Media3 item even though the queue
            // panel still shows the rest of its list as up-next to switch to.
            val allTracks = upNext.all { it.isTrack } && upNext.size > 1
            swapping = true
            try {
                ensureActive()
                if (allTracks) {
                    // If Media3 already holds exactly the window the model now
                    // expects (same ids, same order - the common case of tapping a
                    // song inside a source that is already loaded), switch in place
                    // by index with seekTo instead of tearing the whole window down
                    // and rebuilding it. A full setMediaItems keeps the previous
                    // song audible for a second or two while every item in the new
                    // window is re-resolved and re-published; seekTo is instant.
                    val cq = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
                    val wq = upNext.map { it.id }
                    val alreadyLoaded = cq.size == wq.size && wq.indices.all { cq[it] == wq[it] }
                    if (alreadyLoaded) {
                        val target = model.currentIndex.coerceIn(0, c.mediaItemCount - 1)
                        val resume = upNext.getOrNull(target)?.let { resumeAt(it) } ?: 0L
                        c.seekTo(target, resume)
                    } else {
                        slideWindow(c, start)
                    }
                } else {
                    // Single live stream or lone track: Media3 holds just the
                    // tapped playable. The queue window was already computed
                    // above so the panel shows the neighbours. sync() advances
                    // finite items when they end; live radio waits for Next.
                    // A part-listened episode still opens where it was left.
                    c.setMediaItems(listOf(buildItem(station)), 0, resumeAt(station))
                }
                ensureActive()
                c.prepare()
                c.play()
                c.setPlaybackSpeed(currentSpeed())
            } finally {
                swapping = false
            }
            ensureActive()
            onSync()
        }
    }

    /**
     * Seeks in place when the target is already loaded by Media3 - no
     * setMediaItems, no re-prepare, no decode gap - else slides the window so
     * the target becomes the audible item.
     */
    fun seekOrSlide(station: Station, window: List<Station>, windowIndex: Int, abs: Int) {
        navJob?.cancel()
        swapping = true
        navJob = scope.launch(Dispatchers.Main + failureHandler) {
            val player = controller() ?: return@launch
            // Only seek in place when the target is actually loaded by Media3;
            // a bare seekTo clamps to the last loaded item when the index is
            // out of range, silently freezing playback on that song. Otherwise
            // slide the window so the tapped song becomes the audible item.
            if (player.mediaItemCount == window.size &&
                player.getMediaItemAt(windowIndex).mediaId == station.id
            ) {
                swapping = true
                try {
                    ensureActive()
                    val resume = resumeAt(station)
                    ensureActive()
                    player.seekTo(windowIndex.coerceIn(0, player.mediaItemCount - 1), resume)
                    player.play()
                } finally {
                    swapping = false
                }
                model.setIndex(windowIndex)
            } else {
                slideWindow(player, abs)
                player.prepare()
                player.play()
            }
            ensureActive()
            onSync()
        }
    }

    /**
     * Applies a window edit to Media3: an in-place item move/remove when the
     * player mirrors the old window, else a rebuild that keeps the audible
     * item's position.
     */
    fun applyEdit(
        window: List<Station>,
        index: Int,
        previousUpNext: List<Station>,
        edit: (suspend (MediaController) -> Unit)?,
    ) {
        swapping = controller() != null
        navJob = scope.launch(Dispatchers.Main + failureHandler) {
            val c = controller() ?: return@launch
            try {
                val canEdit = edit != null && window.all { it.isTrack } &&
                    c.mediaItemCount == previousUpNext.size &&
                    previousUpNext.indices.all { c.getMediaItemAt(it).mediaId == previousUpNext[it].id }
                if (window.isEmpty()) {
                    c.clearMediaItems()
                } else if (canEdit) {
                    // Move/remove/insert upcoming items without restarting the
                    // audible item.
                    edit(c)
                } else {
                    // Anchor the rebuild on what Media3 is actually playing:
                    // the model index can lag a slow sync poll, and trusting it
                    // here restarted or skipped the current song.
                    val audibleId = c.currentMediaItem?.mediaId
                    val live = window.indexOfFirst { it.id == audibleId }
                    val at = (if (live >= 0) live else index).coerceIn(0, window.lastIndex)
                    val position = if (live >= 0) c.currentPosition else 0L
                    if (window.size > 1 && window.all { it.isTrack }) {
                        val items = window.map { buildItem(it) }
                        c.setMediaItems(items, at, position)
                        c.prepare()
                    } else if (c.mediaItemCount != 1 || c.currentMediaItem?.mediaId != window[at].id) {
                        c.setMediaItems(listOf(buildItem(window[at])), 0, position)
                        c.prepare()
                    }
                }
            } finally {
                swapping = false
            }
            onSync()
        }
    }

    /**
     * Swaps the Media3 head and tail around the still-playing item, so
     * toggling shuffle never rebuffers or seeks. The neighbours are resolved
     * off the main thread: per-item artwork is what made the old toggle jank.
     */
    fun swapShuffle(slice: List<Station>, idx: Int, anchorId: String) {
        shuffleJob?.cancel()
        swapping = true
        shuffleJob = scope.launch(Dispatchers.Main + failureHandler) {
            try {
                ensureActive()
                val swap = reanchorSwap(anchorId, slice, idx) ?: return@launch
                val p = swap.player
                val pi = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
                // Tail first: the current index is unaffected, so `pi` stays
                // valid for the head swap that follows.
                val tailCount = p.mediaItemCount
                if (pi + 1 < tailCount || swap.tail.isNotEmpty()) {
                    ensureActive()
                    if (pi + 1 < tailCount) p.removeMediaItems(pi + 1, tailCount)
                    ensureActive()
                    if (swap.tail.isNotEmpty()) p.addMediaItems(pi + 1, swap.tail)
                }
                ensureActive()
                // Head second: removing shifts the current item to 0, adding
                // the new head slides it to its shuffled index - playback of
                // the untouched current item continues throughout.
                if (pi > 0 || swap.head.isNotEmpty()) {
                    ensureActive()
                    if (pi > 0) p.removeMediaItems(0, pi)
                    ensureActive()
                    if (swap.head.isNotEmpty()) p.addMediaItems(0, swap.head)
                }
                ensureActive()
                onSync()
            } finally {
                if (shuffleJob === coroutineContext[Job]) swapping = false
                else if (shuffleJob == null) swapping = false
            }
        }
    }

    /** Built swap neighbours plus the slice and index they came from. */
    private data class SwapSides(
        val slice: List<Station>,
        val idx: Int,
        val head: List<MediaItem>,
        val tail: List<MediaItem>,
    )

    /** Re-anchored swap state: the live player plus its fresh neighbours. */
    private data class ShuffleSwap(
        val player: Player,
        val head: List<MediaItem>,
        val tail: List<MediaItem>,
    )

    /**
     * Builds the swap neighbours and re-anchors on what is actually audible:
     * the track may have rolled forward while the tail was building, and
     * swapping a stale tail would wrap it around the wrong song. Null means
     * the track can no longer be followed (or the player drained), so the
     * caller bails without touching Media3.
     */
    private suspend fun CoroutineScope.reanchorSwap(
        anchorId: String,
        slice: List<Station>,
        idx: Int,
    ): ShuffleSwap? {
        val player = controller() ?: return null
        if (rebuildDrained(player, slice, idx, 0L)) return null
        return followAudible(player, anchorId, buildSides(slice, idx))
    }

    /** Swap neighbours, resolved off the main thread. */
    private suspend fun CoroutineScope.buildSides(slice: List<Station>, idx: Int): SwapSides {
        val head = withContext(Dispatchers.Default) {
            slice.subList(0, idx).map { buildItem(it) }
        }
        ensureActive()
        val tail = withContext(Dispatchers.Default) {
            slice.subList(idx + 1, slice.size).map { buildItem(it) }
        }
        ensureActive()
        return SwapSides(slice, idx, head, tail)
    }

    /**
     * Rebuilds the whole slice into a drained player. True when the caller is
     * done: the swap-around below needs an audible item to orbit.
     */
    private suspend fun CoroutineScope.rebuildDrained(
        player: Player,
        slice: List<Station>,
        idx: Int,
        position: Long,
    ): Boolean {
        if (player.mediaItemCount != 0) return false
        val all = withContext(Dispatchers.Default) {
            slice.map { buildItem(it) }
        }
        ensureActive()
        player.setMediaItems(all, idx.coerceIn(0, all.lastIndex), position)
        ensureActive()
        onSync()
        return true
    }

    /**
     * Follows the live item through a possible roll: when the built sides
     * still orbit the anchor they are used as-is, else they are rebuilt once
     * around the live item. Falls through to null when the live item can't
     * be followed.
     */
    private suspend fun CoroutineScope.followAudible(
        player: Player,
        anchorId: String,
        sides: SwapSides,
    ): ShuffleSwap? {
        val pi = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
        if (player.getMediaItemAt(pi).mediaId == anchorId) {
            return ShuffleSwap(player, sides.head, sides.tail)
        }
        val retry = model.source.firstOrNull { it.id == player.getMediaItemAt(pi).mediaId }
        if (retry != null) {
            val abs = model.source.indexOfFirst { it.url == retry.url }.coerceAtLeast(0)
            val window = model.rewindow(abs)
            val fresh = buildSides(window.window, window.index)
            val p = controller()
            if (p != null && !rebuildDrained(p, fresh.slice, fresh.idx, p.currentPosition.coerceAtLeast(0))) {
                val pi2 = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
                if (p.getMediaItemAt(pi2).mediaId == retry.id) {
                    return ShuffleSwap(p, fresh.head, fresh.tail)
                }
            }
        }
        return null
    }

    /** Appends the next window slice when no roll is already running. */
    fun launchExtend(c: Player) {
        if (extending?.isActive == true) return
        extending = scope.launch(Dispatchers.Main + failureHandler) { extendWindow(c) }
    }

    private suspend fun extendWindow(c: Player) {
        val source = model.source
        val oldUpNext = model.currentUpNext
        val oldBase = model.windowBase
        val end = oldBase + oldUpNext.size
        val newEnd = minOf(end + QueuePolicy.WINDOW - 2, source.size)
        if (end >= newEnd || !oldUpNext.all { it.isTrack }) return
        val extra = source.subList(end, newEnd)
        // A mixed queue is advanced by the ended callback, one item at a time.
        if (!extra.all { it.isTrack }) return
        val items = extra.map { buildItem(it) }
        if (model.source !== source || model.currentUpNext != oldUpNext || swapping) return
        val consumed = c.currentMediaItemIndex.coerceIn(0, oldUpNext.lastIndex)
        swapping = true
        try {
            c.addMediaItems(items)
            c.removeMediaItems(0, consumed)
            model.windowBase = oldBase + consumed
            model.setWindow(source.subList(model.windowBase, newEnd), 0)
        } finally {
            swapping = false
        }
        onSync()
    }

    /**
     * Advances the currently-windowed Media3 playlist to [start] in the model
     * source, sliding the window to the matching bounded slice. For short
     * sources (whole list fits) it pushes everything from [start] onward.
     */
    private suspend fun slideWindow(c: Player, start: Int) {
        val src = model.source
        if (src.isEmpty()) return
        val abs = start.coerceIn(0, src.lastIndex)
        val playWindow = model.rewindow(abs)
        val slice = playWindow.window
        val index = playWindow.index
        // Raised around the swap so the half-second poller's sync() can't run
        // between the window being repointed at the new slice and Media3
        // actually switching. Un-guarded, that interleaving resolves the
        // still-playing Media3 item against the fresh window, falls back to
        // the raw index and publishes a station that is not what is audible -
        // "shows one song while playing another". Once Media3 is set, the
        // window and its index are coherent so sync() is safe to resume.
        swapping = true
        try {
            // Tracks ride a real multi-item window so they auto-advance and
            // seek in place. Anything else (live radio, mixed walls) loads
            // exactly the audible item - same as play()'s single-item path -
            // so a next/prev never resolves dozens of streams and artworks it
            // will never play. The window mirror still holds the whole slice,
            // so the panel, the widget ring and the anchors keep walking the
            // full list either way.
            if (slice.all { it.isTrack } && slice.size > 1) {
                val items = slice.map { buildItem(it) }
                val startAt = slice.getOrNull(index)?.let { resumeAt(it) } ?: 0L
                c.setMediaItems(items, index.coerceIn(0, items.lastIndex), startAt)
            } else {
                val target = slice.getOrNull(index) ?: return
                c.setMediaItems(listOf(buildItem(target)), 0, resumeAt(target))
            }
        } finally {
            swapping = false
        }
    }

    /**
     * Rebuilds the window mirror to match exactly what Media3 is holding,
     * item by item, keyed on media id. Used when the window and Media3's
     * queue have drifted out of phase - a near-tail roll that shrank the
     * window to the source tail while Media3 kept the old wider one, or a
     * shuffle realign that moved only the window - so the display always
     * names the same songs Media3 is playing, in the same order. False when
     * Media3 holds no items mappable to the source (a live stream anchor).
     */
    fun mirrorWindowFromMedia3(c: Player): Boolean {
        val src = model.source
        if (src.isEmpty()) return false
        val count = c.mediaItemCount
        if (count == 0) return false
        val pi = c.currentMediaItemIndex.coerceIn(0, count - 1)
        val ids = (0 until count).map { c.getMediaItemAt(it).mediaId }
        fun matches(start: Int) = start >= 0 && start + count <= src.size &&
            ids.indices.all { src[start + it].id == ids[it] }
        val base = if (matches(model.windowBase)) model.windowBase else
            (0..(src.size - count)).firstOrNull(::matches) ?: return false
        val items = src.subList(base, base + count)
        val playerIdx = pi
        model.windowBase = base
        model.setWindow(items, playerIdx)
        return true
    }
}
