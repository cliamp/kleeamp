package stream.kleeamp.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * How long a paused live stream keeps the audio buffered before the pause.
 *
 * ExoPlayer keeps reading after a pause, so its buffer fills with audio from
 * the moment of the pause. Resuming inside this window just plays that
 * buffer (a quick pause-resume stays continuous, with no re-join gap); past
 * it the buffer is behind the broadcast and resuming would replay a song the
 * station finished minutes ago.
 */
internal const val LIVE_BUFFER_HOLD_MS = 10_000L

/**
 * Whether a resume should rejoin the live edge instead of replaying the
 * buffer. Only the station that was paused counts (a switch in between must
 * not inherit the hold), and only once the pause outlasts
 * [LIVE_BUFFER_HOLD_MS]. Pure so unit tests can pin the boundary.
 */
internal fun shouldRejoinLive(
    pausedForMs: Long,
    pausedStationId: String?,
    currentMediaId: String?,
): Boolean = !pausedStationId.isNullOrBlank() &&
    pausedStationId == currentMediaId &&
    pausedForMs >= LIVE_BUFFER_HOLD_MS

/**
 * Drops the buffered audio and re-opens the stream at the live edge.
 *
 * A seek cannot do this: progressive radio streams are not seekable, and
 * [Player.replaceMediaItem] keeps the existing MediaSource when the URI is
 * unchanged ("seamless" replacement). Re-setting the playlist with the
 * current item at its default position builds a fresh MediaSource, so the
 * player issues a new request and starts at whatever the station is playing
 * now instead of draining the old socket.
 */
@UnstableApi
internal fun Player.rejoinLiveEdge() {
    if (mediaItemCount == 0) return
    val items = (0 until mediaItemCount).map { getMediaItemAt(it) }
    val index = currentMediaItemIndex.coerceIn(0, items.lastIndex)
    setMediaItems(items, index, C.TIME_UNSET)
    prepare()
    play()
}
