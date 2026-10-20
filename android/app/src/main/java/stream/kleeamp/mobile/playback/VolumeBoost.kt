package stream.kleeamp.mobile.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Extra gain over the volume slider for quiet recordings, via the platform
 * [LoudnessEnhancer] on the ExoPlayer audio session — the same attach
 * pattern as [AudioFx]'s equalizer, and a no-op on devices without the
 * effect. 0 dB means off; positive gain never touches the stored volume.
 */
class VolumeBoost {

    private var enhancer: LoudnessEnhancer? = null
    private var sessionId = 0
    private var boostDb = 0

    /** Session whose effect creation already failed; see [AudioFx]. */
    private var failedSessionId = 0

    /** True when the device ships a loudness-enhancer effect at all. */
    fun isSupported(): Boolean = runCatching {
        AudioEffect.queryEffects()?.any { it.type == AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER }
    }.getOrDefault(false) == true

    /**
     * Re-attaches on audio-session change like [AudioFx.attach]; the stored
     * gain re-applies to the fresh effect.
     */
    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        if (audioSessionId == sessionId && enhancer != null) return
        if (audioSessionId == failedSessionId) return
        release()
        sessionId = audioSessionId
        runCatching {
            enhancer = LoudnessEnhancer(audioSessionId).apply {
                enabled = true
                setTargetGain(boostDb * 100)
            }
            failedSessionId = 0
        }.onFailure {
            Log.w(TAG, "loudness enhancer unavailable: ${it.message}")
            failedSessionId = audioSessionId
            enhancer = null
        }
    }

    /** Gain in dB, clamped to [stream.kleeamp.mobile.prefs.MaxBoostDb]. */
    fun setBoostDb(db: Int) {
        boostDb = db.coerceAtLeast(0)
        runCatching { enhancer?.setTargetGain(boostDb * 100) }
    }

    fun release() {
        runCatching { enhancer?.release() }
        enhancer = null
        sessionId = 0
        failedSessionId = 0
    }

    private companion object {
        const val TAG = "kleeamp/boost"
    }
}
