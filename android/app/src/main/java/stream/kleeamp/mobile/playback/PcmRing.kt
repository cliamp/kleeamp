package stream.kleeamp.mobile.playback

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free mono PCM ring fed by the Media3 audio tap.
 *
 * The tap runs on ExoPlayer's audio thread (playback priority). Writes must
 * never block, allocate, or touch the UI. Reads happen on the analyzer
 * coroutine (~30 Hz) which copies the latest window for FFT/waveform. The
 * producer publishes its absolute cursor with an atomic write after copying,
 * so the reader never takes a monitor the audio thread can contend on.
 *
 * This is a best-effort snapshot, not a lossless transport: if the analyzer
 * stalls for longer than [CAPACITY] samples the producer may overwrite history
 * while it is being copied. That is acceptable for visualization (and matches
 * the old synchronized ring's overwrite policy), and it keeps the audio thread
 * free of locks.
 *
 * Contents are mono mix in -1..1 (stereo averaged, mono passed through).
 * This is the SAME PCM that reaches playback: local songs, podcast files,
 * podcast streams, radio (MP3/AAC/HLS), provider tracks (Navidrome, Jellyfin,
 * Emby, Plex, Audiobookshelf, Lyrion), SSH/SFTP, M3U/M3U8/PLS — anything
 * Media3 decodes flows through the audio sink chain, so visualization is
 * source-agnostic by construction. No duration or seek position is consulted,
 * so live/unknown-duration streams visualize continuously.
 */
object PcmRing {
    const val CAPACITY = 16_384

    private val buf = FloatArray(CAPACITY)
    private val writePos = AtomicLong(0L)

    @Volatile var sampleRateHz: Int = 44_100
        private set

    @Volatile var channels: Int = 2
        private set

    fun onFormat(sampleRate: Int, channelCount: Int) {
        if (sampleRate > 0) sampleRateHz = sampleRate
        if (channelCount > 0) channels = channelCount
    }

    /** Write mono samples; called on the audio thread. Never allocates. */
    fun writeMono(samples: FloatArray, count: Int) {
        var n = count.coerceAtMost(samples.size)
        var src = 0
        var pos = writePos.get()
        while (n > 0) {
            val idx = (pos % CAPACITY).toInt()
            val room = CAPACITY - idx
            val chunk = minOf(n, room)
            samples.copyInto(buf, idx, src, src + chunk)
            src += chunk
            n -= chunk
            pos += chunk
        }
        // Publish only after the samples are visible to the reader.
        writePos.set(pos)
    }

    /** Total samples ever written; for staleness detection. */
    fun written(): Long = writePos.get()

    /**
     * Copy [count] mono samples ending at absolute position [end]
     * (exclusive) into [dst] (oldest-first). Anything before position 0 is
     * zero-filled; [end] is clamped to [written]. This is the wall-clock
     * window read: the analyzer advances [end] with elapsed time (like
     * cliamp's WaveformSamplesInto anchoring to the audible position), so
     * the waveform slides continuously every tick instead of freezing
     * between decoder bursts and then jumping.
     */
    fun readWindow(end: Long, dst: FloatArray, count: Int): Int {
        val want = count.coerceAtMost(dst.size)
        if (want <= 0) return 0
        val written = writePos.get()
        val clampedEnd = end.coerceIn(0L, written)
        val start = clampedEnd - want
        val zeros = (-start).coerceAtLeast(0L).toInt()
        if (zeros > 0) dst.fill(0f, 0, zeros)
        var copied = 0
        var srcPos = maxOf(0L, start)
        var dstPos = zeros
        var remaining = want - zeros
        while (remaining > 0 && srcPos < written) {
            val idx = (srcPos % CAPACITY).toInt()
            val chunk = minOf(remaining, CAPACITY - idx, (written - srcPos).toInt())
            if (chunk <= 0) break
            buf.copyInto(dst, dstPos, idx, idx + chunk)
            dstPos += chunk
            srcPos += chunk
            remaining -= chunk
            copied += chunk
        }
        if (remaining > 0) dst.fill(0f, dstPos, dstPos + remaining)
        return copied
    }

    /**
     * Copy the latest [count] mono samples into [dst] (oldest-first).
     * Missing history is zero-filled. Returns samples actually copied from
     * the ring (rest are zeros).
     */
    fun readLatest(dst: FloatArray, count: Int): Int {
        val want = count.coerceAtMost(dst.size)
        if (want <= 0) return 0
        val written = writePos.get()
        val available = minOf(written, CAPACITY.toLong()).toInt()
        val have = minOf(want, available)
        val zeros = want - have
        if (zeros > 0) dst.fill(0f, 0, zeros)
        if (have > 0) {
            val start = written - have
            var dstPos = zeros
            var remaining = have
            var srcPos = start
            while (remaining > 0) {
                val idx = (srcPos % CAPACITY).toInt()
                val chunk = minOf(remaining, CAPACITY - idx)
                buf.copyInto(dst, dstPos, idx, idx + chunk)
                dstPos += chunk
                srcPos += chunk
                remaining -= chunk
            }
        }
        return have
    }

    /**
     * Resets the cursor for tests and a full service restart. Not called on
     * media transitions: rewinding the cursor there would erase the first
     * samples of the new track if the audio thread wrote them concurrently.
     */
    fun clear() {
        writePos.set(0L)
    }
}
