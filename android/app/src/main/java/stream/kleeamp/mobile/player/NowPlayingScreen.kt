package stream.kleeamp.mobile.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.player.vis.StereoMetrics
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.playback.AudioOutput
import stream.kleeamp.mobile.playback.AudioOutputs
import stream.kleeamp.mobile.playback.OutputKind
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerState
import stream.kleeamp.mobile.prefs.playSectionFor
import stream.kleeamp.mobile.chrome.clock
import stream.kleeamp.mobile.chrome.compact
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.MechKey
import stream.kleeamp.mobile.chrome.MeterSize
import stream.kleeamp.mobile.chrome.MarqueeLabel
import stream.kleeamp.mobile.chrome.Scrubber
import stream.kleeamp.mobile.chrome.StreamingRule
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.ArtPlate
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberAudioOutputs
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.player.vis.VisualizerMeter
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.model.NowPlaying

/** Everything the player screen needs to draw, read once per frame. */
internal data class PlayerModel(
    val state: PlayerState,
    val shownStation: Station?,
    val previousStation: Station?,
    val nextStation: Station?,
    val streamTitle: String,
    val upNextCount: Int,
    val reconnect: Int,
    val error: String?,
    val isFav: Boolean,
    val shuffled: Boolean,
    val visualizer: String,
    val spectrumProvider: () -> FloatArray?,
    val stereoProvider: () -> StereoMetrics?,
    val generation: Int,
    val outputDevice: Int,
    val outputs: List<AudioOutput>,
    val currentOutput: AudioOutput?,
    val boostDb: Int,
    val boostSupported: Boolean,
    val showSkipKeys: Boolean,
    val skipBackSec: Int,
    val skipFwdSec: Int,
)

/** Every control the player screen can take, so both layouts share one set. */
internal data class PlayerActions(
    val onBack: () -> Unit,
    val onOpenUpNext: () -> Unit,
    val onOpenLyrics: () -> Unit,
    val onToggleShuffle: () -> Unit,
    val onOpenSpeed: () -> Unit,
    val onCycleRepeat: () -> Unit,
    val onOpenScope: () -> Unit,
    val onToggleFav: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onOpenBoost: () -> Unit,
    val onPrev: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipFwd: () -> Unit,
    val onOpenSleep: () -> Unit,
    val onSelectOutput: (Int) -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onSelectVisualizer: (String) -> Unit,
)

@UnstableApi
@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    onOpenScope: () -> Unit,
    onOpenUpNext: () -> Unit,
    onOpenLyrics: () -> Unit,
    onBack: () -> Unit,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    var speedOpen by rememberSaveable { mutableStateOf(false) }
    var boostOpen by rememberSaveable { mutableStateOf(false) }
    val uiState by vm.state.collectAsState()
    // The meters read the live analyser straight off the bus in their frame
    // loops: routing spectrum through VM state would recompose this whole
    // screen on every FFT callback and lag the visuals behind the music.
    // Lambdas are not effect keys, so re-creating them never restarts a loop.
    val spectrumProvider: () -> FloatArray? = { PlaybackBus.spectrum.value }
    val stereoProvider: () -> StereoMetrics? = { PlaybackBus.stereo.value }
    val generation by PlaybackBus.generation.collectAsState()
    // The connected sinks and the one the stream is on: "speaker" until a
    // headset or Bluetooth route takes over.
    val context = LocalContext.current
    val outputs = rememberAudioOutputs()
    val currentOutput = remember(outputs, uiState.outputDevice) {
        AudioOutputs.current(context, uiState.outputDevice)
    }

    // Optimistic 3s window anchored on the navigation itself: most slow
    // streams join inside it, so keep the pause glyph and hide buffering /
    // reconnecting instead of blinking. Anchored on the bus timestamp, not
    // composition, so opening the sheet late never restarts the window over
    // a stall. Pause inside the window still reads as paused via wantPlaying.
    val inGrace = rememberNavGraceSince(PlaybackBus.stationChangedAtMs)
    val effState = uiState.playerState.copy(
        playing = effectivePlaying(
            uiState.playerState.playing,
            uiState.playerState.wantPlaying,
            inGrace,
        ),
        buffering = effectiveBuffering(uiState.playerState.buffering, inGrace),
    )
    val effReconnect = effectiveReconnect(uiState.reconnect, inGrace)
    val model = PlayerModel(        state = effState,
        shownStation = uiState.shownStation,
        previousStation = uiState.previousStation,
        nextStation = uiState.nextStation,
        streamTitle = uiState.streamTitle,
        upNextCount = uiState.upNextCount,
        reconnect = effReconnect,
        error = uiState.error,
        isFav = uiState.isFav,
        shuffled = uiState.shuffled,
        visualizer = uiState.visualizer,
        spectrumProvider = spectrumProvider,
        stereoProvider = stereoProvider,
        generation = generation,
        outputDevice = uiState.outputDevice,
        outputs = outputs,
        currentOutput = currentOutput,
        boostDb = uiState.boostDb,
        boostSupported = uiState.boostSupported,
        showSkipKeys = uiState.showSkipKeys,
        skipBackSec = uiState.skipBackSec,
        skipFwdSec = uiState.skipFwdSec,
    )
    val actions = PlayerActions(
        onBack = onBack,
        onOpenUpNext = onOpenUpNext,
        onOpenLyrics = onOpenLyrics,
        onToggleShuffle = { vm.player.toggleShuffle() },
        onOpenSpeed = { speedOpen = true },
        onCycleRepeat = { vm.player.toggleRepeat() },
        onOpenScope = onOpenScope,
        onToggleFav = { vm.onEvent(NowPlayingViewModel.Event.ToggleFavorite) },
        onSeek = { vm.player.seekTo(it) },
        onOpenBoost = { boostOpen = true },
        onPrev = { vm.player.prev() },
        onPlayPause = { vm.player.toggle(uiState.shownStation) },
        onNext = { vm.player.next() },
        onSkipBack = { vm.player.seekBy(-uiState.skipBackSec) },
        onSkipFwd = { vm.player.seekBy(uiState.skipFwdSec) },
        onOpenSleep = { sleepOpen = true },
        onSelectOutput = { vm.onEvent(NowPlayingViewModel.Event.SetOutputDevice(it)) },
        onToggleFullscreen = { fullscreen = true },
        onSelectVisualizer = { vm.onEvent(NowPlayingViewModel.Event.SetVisualizer(it)) },
    )

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Wide frames (landscape phones, tablets on their side) split the
            // player across the frame: art on the left, transport on the right.
            // Portrait keeps the original single-column stack untouched.
            if (maxWidth > maxHeight) {
                LandscapePlayer(
                    model, actions,
                    frameWidth = maxWidth, frameHeight = maxHeight,
                )
            } else {
                PortraitPlayer(model, actions)
            }
        }

        if (fullscreen) {
            FullscreenVisualizer(model) { fullscreen = false }
        }

        if (sleepOpen) {
            SleepDialog(
                sleepAtMs = uiState.playerState.sleepAtMs,
                onPick = { vm.player.setSleepTimer(it) },
                onDismiss = { sleepOpen = false },
            )
        }

        if (speedOpen) {
            SpeedDialog(
                current = uiState.playerState.speed,
                sectionLabel = if (uiState.speedSections) {
                    uiState.shownStation?.let { playSectionFor(it.source).label }.orEmpty()
                } else "",
                onPick = { vm.onEvent(NowPlayingViewModel.Event.SetSpeed(it)) },
                onDismiss = { speedOpen = false },
            )
        }

        if (boostOpen) {
            BoostDialog(
                current = uiState.boostDb,
                sectionLabel = if (uiState.boostSections) {
                    uiState.shownStation?.let { playSectionFor(it.source).label }.orEmpty()
                } else "",
                onPick = { vm.onEvent(NowPlayingViewModel.Event.SetBoostDb(it)) },
                onDismiss = { boostOpen = false },
            )
        }
    }
}

/** Columns the fullscreen meter spreads across a landscape frame. */
internal const val FULLSCREEN_COLUMNS = 48
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

internal fun Modifier.doubleTapToFullscreen(onToggle: () -> Unit): Modifier = pointerInput(onToggle) {
    detectTapGestures(onDoubleTap = { onToggle() })
}
