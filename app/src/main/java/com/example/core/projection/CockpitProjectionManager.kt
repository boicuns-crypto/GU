package com.example.core.projection

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.content.res.Configuration
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.core.carlife.CarLifeHardKeyPacket
import com.example.core.carlife.CarLifeTouchPacket
import com.example.core.input.CarLifeAccessibilityService
import com.example.feature.cockpit.AppLaunchMode
import com.example.feature.cockpit.CarLifeAppLauncherScreen
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.MediaControllerRepository
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeTouchDecoder
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MyApplicationTheme
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the user-granted [MediaProjection] consent result (`resultCode` + `data` [Intent])
 * from Step 5 of the Onboarding Checklist so `BridgeForegroundService` can promote its FGS type
 * to `mediaProjection` and create a full-screen `VirtualDisplay` without hanging at 90%.
 */
object MediaProjectionTokenStore {
    @Volatile
    var resultCode: Int = Activity.RESULT_CANCELED
        private set

    @Volatile
    var resultData: Intent? = null
        private set

    private val _hasValidToken = MutableStateFlow(false)
    val hasValidToken: StateFlow<Boolean> = _hasValidToken.asStateFlow()

    fun saveToken(code: Int, data: Intent?) {
        if (code == Activity.RESULT_OK && data != null) {
            resultCode = code
            resultData = Intent(data)
            _hasValidToken.value = true
        }
    }

    fun clearToken() {
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        _hasValidToken.value = false
    }
}

/**
 * Selectable video input source for the H.264 `MediaCodec` pipeline:
 * - [CANVAS_TEST_CARD]: Draws a bright 30fps synthetic test card directly onto `MediaCodec.createInputSurface()` without `VirtualDisplay`.
 * - [VIRTUAL_DISPLAY]: Captures `VirtualDisplay` / `MediaProjection` + CarLife Launcher UI.
 */
enum class VideoSourceMode(
    val id: String,
    val title: String,
    val subtitle: String
) {
    SCREEN_MIRRORING(
        id = "screen_mirroring",
        title = "Зеркалирование экрана (MediaProjection)",
        subtitle = "Прямой захват реального экрана смартфона (1280×720@30fps, 2000 kbps) без Presentation + тач через AccessibilityService"
    ),
    VIRTUAL_DISPLAY(
        id = "virtual_display",
        title = "Виртуальный экран (VD + Лаунчер)",
        subtitle = "Захват VirtualDisplay / MediaProjection + оверлей лаунчера CarLife (Presentation)"
    ),
    CANVAS_TEST_CARD(
        id = "canvas_test_card",
        title = "Тест-карта (Canvas)",
        subtitle = "Прямая отрисовка 30 fps в inputSurface без VirtualDisplay (JETTA VS7 TEST + время + FRAME #)"
    );

    companion object {
        fun fromId(id: String?): VideoSourceMode =
            entries.firstOrNull { it.id == id } ?: SCREEN_MIRRORING
    }
}

/**
 * Live telemetry of the Video Source + Hardware H.264 MediaCodec + Touch Injection pipeline.
 */
data class ProjectionPipelineStats(
    val isRunning: Boolean = false,
    val videoSourceMode: VideoSourceMode = VideoSourceMode.SCREEN_MIRRORING,
    val usingMediaProjection: Boolean = false,
    val virtualDisplayId: Int = Display.DEFAULT_DISPLAY,
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitRateMbps: Int = 2,
    val bitRateKbps: Int = 2000,
    val encodedNalFrames: Long = 0L,
    val encodedBytes: Long = 0L,
    val lastIdrBytes: Int = 0,
    val spsPpsConfigured: Boolean = false,
    val injectedTouches: Long = 0L,
    val lastTouchPoint: Pair<Float, Float>? = null,
    val activeCockpitWidget: String = "Зеркалирование экрана (MediaProjection) готово",
    val statusMessage: String = "Зеркалирование экрана (1280×720 @ 2.0 Мбит/с, CBR 30fps, AOA 20B)"
)

/**
 * Core 2 (`:core:projection`):
 * - Hardware H.264 `MediaCodec` (`COLOR_FormatSurface`) that immediately emits Packet #1 (`SPS+PPS`
 *   with `BUFFER_FLAG_CODEC_CONFIG`) followed by Packet #2 (`SPS+PPS+IDR` keyframe) into channel `0x0002`.
 * - Supports both `MediaProjection` full-screen mirroring and a guaranteed 33ms (30fps)
 *   `Canvas`-to-`Surface` Launcher generator so the Head Unit NEVER hangs at "Connecting... 90%"
 *   even if `MediaProjection` has not yet been started or `VirtualDisplay` has not rendered a frame.
 */
class CockpitProjectionManager(
    context: Context,
    private val onEncodedVideoNal: (nalBytes: ByteArray, timestampMs: Long, isKeyFrame: Boolean) -> Unit,
    private val onVideoIdleHeartbeat: (timestampMs: Long) -> Unit = {},
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mediaProjectionManager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var mediaCodec: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayWidth: Int = 0
    private var virtualDisplayHeight: Int = 0
    private var virtualDisplayDpi: Int = 0
    private var virtualDisplayIsMirrorOnly: Boolean = false
    private var cockpitPresentation: CockpitPresentation? = null
    private var encoderDrainJob: Job? = null
    private var canvasFeederJob: Job? = null

    private val projectionMutex = Mutex()
    private val isEncoding = AtomicBoolean(false)

    @Volatile
    var videoSourceMode: VideoSourceMode = VideoSourceMode.SCREEN_MIRRORING

    @Volatile
    var appLaunchMode: AppLaunchMode = CarLifeLauncherRepository.appLaunchMode.value
        private set

    init {
        CarLifeLauncherRepository.onFallbackToPhoneScreen = {
            if (_stats.value.usingMediaProjection) {
                mainHandler.post {
                    runCatching { cockpitPresentation?.dismiss() }
                    cockpitPresentation = null
                }
            }
        }
    }

    fun setAppLaunchMode(mode: AppLaunchMode) {
        appLaunchMode = mode
        CarLifeLauncherRepository.setAppLaunchMode(mode)
        if (mode == AppLaunchMode.SCREEN_MIRRORING) {
            mainHandler.post {
                runCatching { cockpitPresentation?.dismiss() }
                cockpitPresentation = null
            }
        }
    }

    /**
     * Active [MediaProjection] instance (when granted by the user) shared with
     * `CarLifeAudioCaptureManager` for Android 10+ `AudioPlaybackCapture`.
     */
    val activeMediaProjection: MediaProjection?
        get() = mediaProjection

    /**
     * Returns the active [MediaProjection], or lazily instantiates it from [MediaProjectionTokenStore]
     * so `AudioPlaybackCapture` works even while video is in `CANVAS_TEST_CARD` mode.
     */
    suspend fun getOrCreateMediaProjectionForAudio(): MediaProjection? = projectionMutex.withLock {
        mediaProjection?.let { return@withLock it }
        val tokenCode = MediaProjectionTokenStore.resultCode
        val tokenData = MediaProjectionTokenStore.resultData
        if (tokenCode != Activity.RESULT_OK || tokenData == null) {
            return@withLock null
        }
        return@withLock runCatching {
            val mp = mediaProjectionManager.getMediaProjection(tokenCode, Intent(tokenData))
            if (mp != null) {
                if (mediaProjectionCallback == null) {
                    val callback = object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped by system")
                            MediaProjectionTokenStore.clearToken()
                        }
                    }
                    mp.registerCallback(callback, mainHandler)
                    mediaProjectionCallback = callback
                }
                mediaProjection = mp
            }
            mp
        }.onFailure { e ->
            Log.w(TAG, "Unable to create MediaProjection for audio capture: ${e.message}")
        }.getOrNull()
    }

    /**
     * Gate for channel `0x0002` video streaming:
     * Remains `false` until USB AOA connects and receives `MSG_CMD_HU_PROTOCOL_VERSION (0x00018001)`.
     * Force-unlocked immediately upon proactive handshake completion (`unlockVideoChannelAndRequestSyncFrame`).
     */
    @Volatile
    var isVideoChannelReadyForStreaming: Boolean = false
        private set

    private val awaitingFirstIdrAfterGateOpen = AtomicBoolean(true)

    @Volatile
    private var cachedSpsPps: ByteArray? = null

    @Volatile
    private var cachedSpsAnnexB: ByteArray? = null

    @Volatile
    private var cachedPpsAnnexB: ByteArray? = null

    @Volatile
    private var lastKeyFrameAnnexB: ByteArray? = null

    @Volatile
    private var lastCodecFrameElapsedMs: Long = 0L

    private val lastDownTime = AtomicLong(0L)

    private val _stats = MutableStateFlow(ProjectionPipelineStats())
    val stats: StateFlow<ProjectionPipelineStats> = _stats.asStateFlow()

    fun setVideoChannelReady(ready: Boolean) {
        if (!ready) {
            isVideoChannelReadyForStreaming = false
            awaitingFirstIdrAfterGateOpen.set(true)
        } else {
            awaitingFirstIdrAfterGateOpen.set(true)
            isVideoChannelReadyForStreaming = true
        }
    }

    private var presentationRefreshJob: Job? = null

    suspend fun ensureProjectionStarted(
        width: Int = 1280,
        height: Int = 720,
        frameRate: Int = 30,
        bitRate: Int = DEFAULT_BITRATE_BPS,
        densityDpi: Int = FIXED_VIRTUAL_DISPLAY_DPI,
        sourceMode: VideoSourceMode = this.videoSourceMode
    ): Boolean {
        val safeWidth = if (width in 480..3840) width else 1280
        val safeHeight = if (height in 272..2160) height else 720
        val safeFps = if (frameRate in 15..60) frameRate else 30
        val safeBitRate = bitRate.coerceIn(400_000, 12_000_000)

        val alreadyActive = projectionMutex.withLock {
            val current = _stats.value
            isEncoding.get() &&
                mediaCodec != null &&
                current.videoSourceMode == sourceMode &&
                current.width == safeWidth &&
                current.height == safeHeight &&
                current.fps == safeFps &&
                current.bitRateKbps == (safeBitRate / 1_000)
        }
        if (alreadyActive) return true
        return startProjection(safeWidth, safeHeight, safeFps, safeBitRate, FIXED_VIRTUAL_DISPLAY_DPI, sourceMode)
    }

    suspend fun startProjection(
        width: Int = 1280,
        height: Int = 720,
        frameRate: Int = 30,
        bitRate: Int = DEFAULT_BITRATE_BPS,
        densityDpi: Int = FIXED_VIRTUAL_DISPLAY_DPI,
        sourceMode: VideoSourceMode = this.videoSourceMode
    ): Boolean = projectionMutex.withLock {
        videoSourceMode = sourceMode
        // Stop only the previous MediaCodec / Surface (while preserving MediaProjection & VirtualDisplay in VD mode
        // ONLY if its exact dimensions and 160 dpi already match — zero intermediate resizes).
        stopEncoderOnlyLocked()

        val safeWidth = if (width in 480..3840) width else 1280
        val safeHeight = if (height in 272..2160) height else 720
        val safeFps = if (frameRate in 15..60) frameRate else 30
        val safeBitRate = bitRate.coerceIn(400_000, 12_000_000)
        // Hard-lock VirtualDisplay density to 160 dpi (DisplayMetrics.DENSITY_MEDIUM) for sharp 1280×720 proportions.
        val effectiveDpi = FIXED_VIRTUAL_DISPLAY_DPI

        if (sourceMode == VideoSourceMode.VIRTUAL_DISPLAY && CarLifeLauncherRepository.installedApps.value.isEmpty()) {
            externalScope.launch(Dispatchers.IO) {
                CarLifeLauncherRepository.loadInstalledApps(appContext)
            }
        }

        if (sourceMode == VideoSourceMode.CANVAS_TEST_CARD) {
            // Canvas mode: Strictly DO NOT create or keep a VirtualDisplay; render 30fps test card directly to MediaCodec inputSurface.
            withContext(Dispatchers.Main.immediate) {
                runCatching { cockpitPresentation?.dismiss() }
                cockpitPresentation = null
            }
            runCatching { virtualDisplay?.surface = null }
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            virtualDisplayWidth = 0
            virtualDisplayHeight = 0
            virtualDisplayDpi = 0
            virtualDisplayIsMirrorOnly = false
        } else if (sourceMode == VideoSourceMode.SCREEN_MIRRORING) {
            // Screen Mirroring mode: Strictly DO NOT show Presentation so the real smartphone screen goes directly into inputSurface.
            withContext(Dispatchers.Main.immediate) {
                runCatching { cockpitPresentation?.dismiss() }
                cockpitPresentation = null
            }
        }

        val usesMediaProjectionSource =
            sourceMode == VideoSourceMode.SCREEN_MIRRORING || sourceMode == VideoSourceMode.VIRTUAL_DISPLAY
        val wantMirrorOnlyFlags = sourceMode == VideoSourceMode.SCREEN_MIRRORING

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var vDisplay: VirtualDisplay? = if (usesMediaProjectionSource) virtualDisplay else null
        var activeMp: MediaProjection? = if (usesMediaProjectionSource) mediaProjection else null
        var usedMediaProjection = (usesMediaProjectionSource && vDisplay != null && activeMp != null)

        return@withLock try {
            codec = configureLowLatencyAvcEncoder(safeWidth, safeHeight, safeFps, safeBitRate)
            inputSurface = codec.createInputSurface()
            codec.start()

            mediaCodec = codec
            encoderInputSurface = inputSurface
            isEncoding.set(true)

            // Start draining MediaCodec immediately so SPS/PPS + IDR are captured as soon as the first frame lands
            encoderDrainJob = externalScope.launch(Dispatchers.IO) {
                drainHardwareEncoderLoop(codec, safeBitRate)
            }

            if (sourceMode == VideoSourceMode.CANVAS_TEST_CARD) {
                startCanvasFeederLoopIfNeeded(
                    surface = inputSurface,
                    width = safeWidth,
                    height = safeHeight,
                    frameRate = safeFps,
                    sourceMode = VideoSourceMode.CANVAS_TEST_CARD
                )

                _stats.update {
                    it.copy(
                        isRunning = true,
                        videoSourceMode = VideoSourceMode.CANVAS_TEST_CARD,
                        usingMediaProjection = false,
                        virtualDisplayId = Display.DEFAULT_DISPLAY,
                        width = safeWidth,
                        height = safeHeight,
                        fps = safeFps,
                        bitRateMbps = (safeBitRate / 1_000_000).coerceAtLeast(1),
                        bitRateKbps = safeBitRate / 1_000,
                        activeCockpitWidget = "Тест-карта Canvas (${safeWidth}×${safeHeight} @ ${safeFps}fps)",
                        statusMessage = "Тест-карта (Canvas) активна (${safeWidth}×${safeHeight} @ ${safeBitRate / 1000} кбит/с, ${safeFps} fps)"
                    )
                }
            } else {
                if (usedMediaProjection && vDisplay != null) {
                    val exactConfigMatches =
                        virtualDisplayWidth == safeWidth &&
                            virtualDisplayHeight == safeHeight &&
                            virtualDisplayDpi == effectiveDpi &&
                            virtualDisplayIsMirrorOnly == wantMirrorOnlyFlags
                    if (exactConfigMatches) {
                        // Reuse VirtualDisplay ONLY when exact (width, height, 160 dpi, flags) already match — zero intermediate resize.
                        runCatching {
                            vDisplay!!.surface = inputSurface
                        }.onFailure { e ->
                            Log.w(TAG, "VirtualDisplay setSurface failed (${e.message}), recreating cleanly")
                            runCatching { vDisplay?.release() }
                            vDisplay = null
                            virtualDisplayWidth = 0
                            virtualDisplayHeight = 0
                            virtualDisplayDpi = 0
                            virtualDisplayIsMirrorOnly = false
                            usedMediaProjection = false
                        }
                    } else {
                        // Dimensions, DPI, or mirroring flags changed: recreate VirtualDisplay cleanly.
                        withContext(Dispatchers.Main.immediate) {
                            runCatching { cockpitPresentation?.dismiss() }
                            cockpitPresentation = null
                        }
                        runCatching { vDisplay?.surface = null }
                        runCatching { vDisplay?.release() }
                        vDisplay = null
                        virtualDisplayWidth = 0
                        virtualDisplayHeight = 0
                        virtualDisplayDpi = 0
                        virtualDisplayIsMirrorOnly = false
                        usedMediaProjection = false
                    }
                }

                // If no existing VirtualDisplay, check if user granted MediaProjection (Step 5)
                if (!usedMediaProjection) {
                    val tokenData = MediaProjectionTokenStore.resultData
                    val tokenCode = MediaProjectionTokenStore.resultCode
                    if (tokenCode == Activity.RESULT_OK && tokenData != null) {
                        runCatching {
                            val mp = activeMp ?: mediaProjectionManager.getMediaProjection(tokenCode, Intent(tokenData))
                            if (mp != null) {
                                if (mediaProjectionCallback == null) {
                                    val callback = object : MediaProjection.Callback() {
                                        override fun onStop() {
                                            Log.w(TAG, "MediaProjection stopped by system")
                                            MediaProjectionTokenStore.clearToken()
                                        }
                                    }
                                    mp.registerCallback(callback, mainHandler)
                                    mediaProjectionCallback = callback
                                }
                                activeMp = mp

                                val vdFlags = if (wantMirrorOnlyFlags) {
                                    // Direct screen mirroring onto MediaCodec inputSurface (no Presentation overlay)
                                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                                } else {
                                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                                }

                                vDisplay = mp.createVirtualDisplay(
                                    VIRTUAL_DISPLAY_NAME,
                                    safeWidth,
                                    safeHeight,
                                    effectiveDpi,
                                    vdFlags,
                                    inputSurface,
                                    null,
                                    mainHandler
                                )
                                virtualDisplayWidth = safeWidth
                                virtualDisplayHeight = safeHeight
                                virtualDisplayDpi = effectiveDpi
                                virtualDisplayIsMirrorOnly = wantMirrorOnlyFlags
                                usedMediaProjection = true
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "MediaProjection VirtualDisplay creation failed, switching to Canvas Surface Feeder: ${e.message}")
                            MediaProjectionTokenStore.clearToken()
                        }
                    }
                }

                if (usedMediaProjection && vDisplay != null) {
                    val createdDisplay = vDisplay!!.display
                    awaitDisplayRegistered(createdDisplay.displayId)

                    // Strictly DO NOT attach CockpitPresentation in SCREEN_MIRRORING mode (or SCREEN_MIRRORING launch mode);
                    // the real smartphone screen goes directly into MediaCodec inputSurface.
                    val shouldSuppressPresentation =
                        sourceMode == VideoSourceMode.SCREEN_MIRRORING ||
                            appLaunchMode == AppLaunchMode.SCREEN_MIRRORING

                    if (shouldSuppressPresentation) {
                        withContext(Dispatchers.Main.immediate) {
                            runCatching { cockpitPresentation?.dismiss() }
                            cockpitPresentation = null
                        }
                    } else if (
                        cockpitPresentation == null ||
                        cockpitPresentation?.display?.displayId != createdDisplay.displayId ||
                        cockpitPresentation?.targetWidth != safeWidth ||
                        cockpitPresentation?.targetHeight != safeHeight
                    ) {
                        runCatching {
                            withContext(Dispatchers.Main.immediate) {
                                runCatching { cockpitPresentation?.dismiss() }
                                cockpitPresentation = null
                            }
                            val presentation = showPresentationWithRetry(
                                display = createdDisplay,
                                targetWidth = safeWidth,
                                targetHeight = safeHeight
                            )
                            withContext(Dispatchers.Main.immediate) {
                                cockpitPresentation = presentation
                                presentation.window?.decorView?.postInvalidate()
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "Presentation overlay skipped (MediaProjection Auto-Mirror still active): ${e.message}")
                        }
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            cockpitPresentation?.window?.decorView?.requestLayout()
                            cockpitPresentation?.window?.decorView?.postInvalidate()
                        }
                    }

                    mediaProjection = activeMp
                    virtualDisplay = vDisplay

                    if (!shouldSuppressPresentation) {
                        // Periodically invalidate CockpitPresentation so VirtualDisplay continuously feeds MediaCodec
                        startPresentationRefreshLoop()
                    } else {
                        presentationRefreshJob?.cancel()
                        presentationRefreshJob = null
                    }

                    CarLifeLauncherRepository.markLauncherActiveOnDisplay(
                        displayId = createdDisplay.displayId,
                        width = safeWidth,
                        height = safeHeight,
                        bitrateBps = safeBitRate
                    )

                    _stats.update {
                        it.copy(
                            isRunning = true,
                            videoSourceMode = sourceMode,
                            usingMediaProjection = true,
                            virtualDisplayId = createdDisplay.displayId,
                            width = safeWidth,
                            height = safeHeight,
                            fps = safeFps,
                            bitRateMbps = (safeBitRate / 1_000_000).coerceAtLeast(1),
                            bitRateKbps = safeBitRate / 1_000,
                            activeCockpitWidget = if (sourceMode == VideoSourceMode.SCREEN_MIRRORING) {
                                "Зеркалирование экрана активно ($safeWidth×$safeHeight @ ${safeFps}fps)"
                            } else {
                                "Лаунчер ГУ активен на VD #${createdDisplay.displayId} ($safeWidth×$safeHeight)"
                            },
                            statusMessage = if (sourceMode == VideoSourceMode.SCREEN_MIRRORING) {
                                "Зеркалирование экрана (MediaProjection): $safeWidth×$safeHeight @ ${safeBitRate / 1000} кбит/с, ${safeFps}fps"
                            } else {
                                "Лаунчер ГУ активен на VD #${createdDisplay.displayId} ($safeWidth×$safeHeight @ ${safeBitRate / 1000} кбит/с)"
                            }
                        )
                    }

                    // Watchdog for Presentation mode ONLY: never detach live Screen Mirroring VirtualDisplay!
                    if (sourceMode == VideoSourceMode.VIRTUAL_DISPLAY) {
                        externalScope.launch(Dispatchers.IO) {
                            delay(350L)
                            if (isEncoding.get() &&
                                videoSourceMode == VideoSourceMode.VIRTUAL_DISPLAY &&
                                lastKeyFrameAnnexB == null
                            ) {
                                Log.w(TAG, "MediaProjection produced 0 frames in 350ms; activating Canvas Surface Feeder fallback")
                                runCatching { vDisplay?.surface = null }
                                startCanvasFeederLoopIfNeeded(
                                    surface = inputSurface,
                                    width = safeWidth,
                                    height = safeHeight,
                                    frameRate = safeFps,
                                    sourceMode = VideoSourceMode.VIRTUAL_DISPLAY
                                )
                            }
                        }
                    }
                } else {
                    // MediaProjection not yet granted: run Canvas Surface Feeder so H.264 stream stays live until user approves
                    startCanvasFeederLoopIfNeeded(
                        surface = inputSurface,
                        width = safeWidth,
                        height = safeHeight,
                        frameRate = safeFps,
                        sourceMode = sourceMode
                    )
                    _stats.update {
                        it.copy(
                            isRunning = true,
                            videoSourceMode = sourceMode,
                            usingMediaProjection = false,
                            virtualDisplayId = Display.DEFAULT_DISPLAY,
                            width = safeWidth,
                            height = safeHeight,
                            fps = safeFps,
                            bitRateMbps = (safeBitRate / 1_000_000).coerceAtLeast(1),
                            bitRateKbps = safeBitRate / 1_000,
                            activeCockpitWidget = "Ожидание MediaProjection («Весь экран»)",
                            statusMessage = "Ожидание разрешения MediaProjection («Весь экран») — резервный H.264 поток активен"
                        )
                    }
                }
            }

            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize CockpitProjectionManager", t)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            stopEncoderOnlyLocked()
            _stats.update {
                it.copy(
                    isRunning = false,
                    videoSourceMode = sourceMode,
                    usingMediaProjection = false,
                    virtualDisplayId = Display.DEFAULT_DISPLAY,
                    spsPpsConfigured = false,
                    statusMessage = "Projection Init Error: ${t.message}"
                )
            }
            false
        }
    }

    private fun startPresentationRefreshLoop() {
        presentationRefreshJob?.cancel()
        presentationRefreshJob = externalScope.launch(Dispatchers.Main) {
            while (isActive && isEncoding.get()) {
                cockpitPresentation?.window?.decorView?.postInvalidate()
                delay(100L)
            }
        }
    }

    /**
     * Renders either the bright 30fps Synthetic Test Card ([VideoSourceMode.CANVAS_TEST_CARD])
     * or the CarLife Cockpit Launcher ([VideoSourceMode.VIRTUAL_DISPLAY] fallback) directly into
     * [surface] (`mediaCodec.createInputSurface()`) every ~33ms (`30 fps`) so `MediaCodec`
     * continuously emits live `SPS + PPS` and changing `IDR / P` frames and never goes idle.
     */
    private fun startCanvasFeederLoopIfNeeded(
        surface: Surface,
        width: Int,
        height: Int,
        frameRate: Int,
        sourceMode: VideoSourceMode
    ) {
        if (canvasFeederJob?.isActive == true) return
        val frameIntervalMs = if (frameRate == 30) {
            33L
        } else {
            (1_000L / frameRate.coerceIn(15, 60)).coerceAtLeast(16L)
        }

        canvasFeederJob = externalScope.launch(Dispatchers.Default) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#0B0E14")
                style = Paint.Style.FILL
            }
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#151A24")
                style = Paint.Style.FILL
            }
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#1E2636")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#222938")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#00B4D8")
                style = Paint.Style.FILL
            }
            val emeraldBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#064E3B")
                style = Paint.Style.FILL
            }
            val emeraldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#10B981")
                textSize = 20f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#F1F5F9")
                textSize = 46f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#F1F5F9")
                textSize = 24f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#94A3B8")
                textSize = 18f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val appLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#F1F5F9")
                textSize = 18f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val testCardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val testTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

            var frameSeq = 0L
            while (isActive && isEncoding.get() && surface.isValid) {
                val loopStart = SystemClock.uptimeMillis()
                try {
                    val canvas: AndroidCanvas? = surface.lockCanvas(null)
                    if (canvas != null) {
                        try {
                            val currentSeq = frameSeq++
                            if (sourceMode == VideoSourceMode.CANVAS_TEST_CARD) {
                                drawTestCardFrame(
                                    canvas = canvas,
                                    width = width,
                                    height = height,
                                    frameSeq = currentSeq,
                                    timeWithMsString = testTimeFmt.format(Date()),
                                    paint = testCardPaint
                                )
                            } else {
                                drawCarLifeLauncherFrame(
                                    canvas = canvas,
                                    width = width,
                                    height = height,
                                    frameSeq = currentSeq,
                                    timeString = timeFmt.format(Date()),
                                    bgPaint = bgPaint,
                                    cardPaint = cardPaint,
                                    tilePaint = tilePaint,
                                    borderPaint = borderPaint,
                                    accentPaint = accentPaint,
                                    emeraldBadgeBgPaint = emeraldBadgeBgPaint,
                                    emeraldTextPaint = emeraldTextPaint,
                                    clockPaint = clockPaint,
                                    titlePaint = titlePaint,
                                    subtitlePaint = subtitlePaint,
                                    appLabelPaint = appLabelPaint
                                )
                            }
                        } finally {
                            surface.unlockCanvasAndPost(canvas)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Canvas surface feeder stopped: ${t.message}")
                    break
                }

                val elapsed = SystemClock.uptimeMillis() - loopStart
                val sleepMs = (frameIntervalMs - elapsed).coerceAtLeast(4L)
                delay(sleepMs)
            }
        }
    }

    /**
     * Bright 30fps synthetic Test Card rendered onto `MediaCodec.createInputSurface()`:
     * - Vibrant blue & green split backdrop + 7-color SMPTE bars at top and bottom
     * - Moving horizontal progress bar driven by [frameSeq] so every single frame changes pixels
     * - Large centered white text: `"JETTA VS7 TEST"`, current timestamp, and `"FRAME #<seq>"`.
     */
    fun drawTestCardFrame(
        canvas: AndroidCanvas,
        width: Int,
        height: Int,
        frameSeq: Long,
        timeWithMsString: String,
        paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    ) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val scale = (h / 480f).coerceIn(0.6f, 2.5f)

        // 1. Bright split background: deep cobalt blue (left) + vivid emerald green (right)
        paint.style = Paint.Style.FILL
        paint.setShadowLayer(0f, 0f, 0f, AndroidColor.TRANSPARENT)
        paint.color = AndroidColor.rgb(0, 82, 204) // Bright blue
        canvas.drawRect(0f, 0f, w * 0.5f, h, paint)
        paint.color = AndroidColor.rgb(0, 148, 86) // Bright green
        canvas.drawRect(w * 0.5f, 0f, w, h, paint)

        // 2. Color bars (top 16% and bottom 16%)
        val barColors = intArrayOf(
            AndroidColor.WHITE,
            AndroidColor.rgb(255, 214, 0),   // Yellow
            AndroidColor.rgb(0, 229, 255),   // Cyan
            AndroidColor.rgb(0, 230, 118),   // Bright Green
            AndroidColor.rgb(255, 0, 229),   // Magenta
            AndroidColor.rgb(255, 23, 68),   // Red
            AndroidColor.rgb(41, 121, 255)   // Blue
        )
        val topBandH = h * 0.16f
        val bottomBandTop = h * 0.84f
        val barW = w / barColors.size.toFloat()
        for (i in barColors.indices) {
            paint.color = barColors[i]
            val left = i * barW
            val right = if (i == barColors.lastIndex) w else (i + 1) * barW
            canvas.drawRect(left, 0f, right, topBandH, paint)
            canvas.drawRect(
                left,
                bottomBandTop,
                right,
                h,
                paint.apply { color = barColors[barColors.lastIndex - i] }
            )
        }

        // 3. Moving frame sweep bar across top & bottom transitions so every 33ms frame has motion
        val sweepPos = ((frameSeq % 60L).toFloat() / 60f) * w
        val sweepW = (w * 0.12f).coerceAtLeast(24f)
        paint.color = AndroidColor.YELLOW
        canvas.drawRect(sweepPos, topBandH, (sweepPos + sweepW).coerceAtMost(w), topBandH + 10f * scale, paint)
        paint.color = AndroidColor.CYAN
        val revPos = w - sweepPos - sweepW
        canvas.drawRect(revPos.coerceAtLeast(0f), bottomBandTop - 10f * scale, (revPos + sweepW).coerceAtMost(w), bottomBandTop, paint)

        // 4. Crisp outer frame border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * scale
        paint.color = AndroidColor.WHITE
        canvas.drawRect(2f * scale, 2f * scale, w - 2f * scale, h - 2f * scale, paint)

        // 5. Semi-opaque dark center badge for maximum contrast of white text
        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.argb(175, 8, 16, 32)
        val boxMarginX = w * 0.10f
        val boxTop = topBandH + 22f * scale
        val boxBottom = bottomBandTop - 22f * scale
        canvas.drawRoundRect(
            RectF(boxMarginX, boxTop, w - boxMarginX, boxBottom),
            24f * scale,
            24f * scale,
            paint
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * scale
        paint.color = AndroidColor.WHITE
        canvas.drawRoundRect(
            RectF(boxMarginX, boxTop, w - boxMarginX, boxBottom),
            24f * scale,
            24f * scale,
            paint
        )

        // 6. Centered bold white text: "JETTA VS7 TEST", current time, and FRAME #N
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = AndroidColor.WHITE
        paint.setShadowLayer(4f * scale, 2f * scale, 2f * scale, AndroidColor.BLACK)

        val centerX = w * 0.5f
        val centerY = h * 0.5f

        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 54f * scale
        canvas.drawText("JETTA VS7 TEST", centerX, centerY - 46f * scale, paint)

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 32f * scale
        canvas.drawText("TIME $timeWithMsString", centerX, centerY + 12f * scale, paint)

        paint.color = AndroidColor.rgb(255, 245, 157) // High-contrast bright warm white/yellow
        paint.textSize = 42f * scale
        canvas.drawText("FRAME #$frameSeq", centerX, centerY + 66f * scale, paint)

        paint.color = AndroidColor.WHITE
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 18f * scale
        canvas.drawText(
            "CANVAS INPUT SURFACE • ${width}x${height} • 30 FPS CBR",
            centerX,
            centerY + 104f * scale,
            paint
        )
        paint.setShadowLayer(0f, 0f, 0f, AndroidColor.TRANSPARENT)
    }

    private fun drawCarLifeLauncherFrame(
        canvas: AndroidCanvas,
        width: Int,
        height: Int,
        frameSeq: Long,
        timeString: String,
        bgPaint: Paint,
        cardPaint: Paint,
        tilePaint: Paint,
        borderPaint: Paint,
        accentPaint: Paint,
        emeraldBadgeBgPaint: Paint,
        emeraldTextPaint: Paint,
        clockPaint: Paint,
        titlePaint: Paint,
        subtitlePaint: Paint,
        appLabelPaint: Paint
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = (h / 720f).coerceIn(0.62f, 1.2f)
        clockPaint.textSize = 44f * scale
        titlePaint.textSize = 23f * scale
        subtitlePaint.textSize = 17f * scale
        appLabelPaint.textSize = 17f * scale
        emeraldTextPaint.textSize = 18f * scale

        // 1. Background (#0B0E14)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Left Dock Card
        val dockRight = (w * 0.25f).coerceIn(190f, 320f)
        val margin = 12f * scale
        val dockRect = RectF(margin, margin, dockRight, h - margin)
        canvas.drawRoundRect(dockRect, 20f * scale, 20f * scale, cardPaint)
        canvas.drawRoundRect(dockRect, 20f * scale, 20f * scale, borderPaint)

        // Clock & Live H.264 Badge
        val clockBaseline = margin + 48f * scale
        canvas.drawText(timeString, dockRect.left + 16f * scale, clockBaseline, clockPaint)
        val badgeW = 78f * scale
        val badgeH = 30f * scale
        val badgeRect = RectF(
            dockRight - badgeW - 12f * scale,
            margin + 18f * scale,
            dockRight - 12f * scale,
            margin + 18f * scale + badgeH
        )
        canvas.drawRoundRect(badgeRect, 10f * scale, 10f * scale, emeraldBadgeBgPaint)
        canvas.drawText("LIVE", badgeRect.left + 18f * scale, badgeRect.centerY() + 6f * scale, emeraldTextPaint)

        // Home & Back buttons in Dock
        val btnTop = margin + 68f * scale
        val btnHeight = 52f * scale
        val btnGap = 10f * scale
        val btnWidth = (dockRect.width() - 24f * scale - btnGap) / 2f
        val homeRect = RectF(dockRect.left + 12f * scale, btnTop, dockRect.left + 12f * scale + btnWidth, btnTop + btnHeight)
        val backRect = RectF(homeRect.right + btnGap, btnTop, dockRight - 12f * scale, btnTop + btnHeight)
        canvas.drawRoundRect(homeRect, 12f * scale, 12f * scale, tilePaint)
        canvas.drawRoundRect(homeRect, 12f * scale, 12f * scale, borderPaint)
        canvas.drawRoundRect(backRect, 12f * scale, 12f * scale, tilePaint)
        canvas.drawRoundRect(backRect, 12f * scale, 12f * scale, borderPaint)
        canvas.drawText("Домой", homeRect.centerX(), homeRect.centerY() + 6f * scale, appLabelPaint)
        canvas.drawText("Назад", backRect.centerX(), backRect.centerY() + 6f * scale, appLabelPaint)

        // Media Widget Card in Dock
        val mediaState = MediaControllerRepository.mediaState.value
        val mediaTop = (btnTop + btnHeight + 14f * scale).coerceAtLeast(h - 195f * scale)
        val mediaRect = RectF(dockRect.left + 10f * scale, mediaTop, dockRight - 10f * scale, h - margin - 10f * scale)
        canvas.drawRoundRect(mediaRect, 16f * scale, 16f * scale, tilePaint)
        canvas.drawRoundRect(mediaRect, 16f * scale, 16f * scale, borderPaint)
        canvas.drawText(
            mediaState.playerAppTitle.take(20),
            mediaRect.left + 12f * scale,
            mediaRect.top + 26f * scale,
            emeraldTextPaint
        )
        canvas.drawText(
            mediaState.trackTitle.take(16),
            mediaRect.left + 12f * scale,
            mediaRect.top + 52f * scale,
            titlePaint
        )
        canvas.drawText(
            mediaState.artistName.take(20),
            mediaRect.left + 12f * scale,
            mediaRect.top + 76f * scale,
            subtitlePaint
        )
        // Play button circle
        canvas.drawCircle(mediaRect.centerX(), mediaRect.bottom - 30f * scale, 20f * scale, accentPaint)

        // 3. Right App Grid Panel
        val gridPanelRect = RectF(dockRight + margin, margin, w - margin, h - margin)
        canvas.drawRoundRect(gridPanelRect, 20f * scale, 20f * scale, cardPaint)
        canvas.drawRoundRect(gridPanelRect, 20f * scale, 20f * scale, borderPaint)

        canvas.drawText(
            "CarLife Кокпит • Приложения смартфона",
            gridPanelRect.left + 18f * scale,
            gridPanelRect.top + 36f * scale,
            titlePaint
        )

        // Subtle animated heartbeat indicator
        val pulseOffset = ((frameSeq % 60L) / 60f) * (gridPanelRect.width() - 36f * scale)
        canvas.drawCircle(gridPanelRect.right - 24f * scale, gridPanelRect.top + 28f * scale, 6f * scale, accentPaint)
        canvas.drawRect(
            gridPanelRect.left + 18f * scale,
            gridPanelRect.top + 48f * scale,
            gridPanelRect.left + 18f * scale + pulseOffset.coerceAtLeast(10f),
            gridPanelRect.top + 51f * scale,
            accentPaint
        )

        // Draw installed apps in a 4x3 grid
        val apps = CarLifeLauncherRepository.installedApps.value.take(12)
        val cols = 4
        val rows = 3
        val gridGap = 12f * scale
        val gridLeft = gridPanelRect.left + 18f * scale
        val gridTop = gridPanelRect.top + 64f * scale
        val availW = gridPanelRect.width() - 36f * scale
        val availH = gridPanelRect.height() - 80f * scale
        val cellW = (availW - (cols - 1) * gridGap) / cols
        val cellH = (availH - (rows - 1) * gridGap) / rows
        val iconSize = (cellH * 0.46f).toInt().coerceIn(32, 64)

        for (index in apps.indices) {
            val app = apps[index]
            val col = index % cols
            val row = index / cols
            val left = gridLeft + col * (cellW + gridGap)
            val top = gridTop + row * (cellH + gridGap)
            val cellRect = RectF(left, top, left + cellW, top + cellH)
            canvas.drawRoundRect(cellRect, 14f * scale, 14f * scale, tilePaint)
            canvas.drawRoundRect(cellRect, 14f * scale, 14f * scale, borderPaint)

            val iconBmp = runCatching { app.iconBitmap?.asAndroidBitmap() }.getOrNull()
            val iconLeft = (cellRect.centerX() - iconSize / 2f).toInt()
            val iconTop = (cellRect.top + 12f * scale).toInt()
            if (iconBmp != null && !iconBmp.isRecycled) {
                val dstRect = Rect(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                canvas.drawBitmap(iconBmp, null, dstRect, null)
            } else {
                canvas.drawCircle(cellRect.centerX(), iconTop + iconSize / 2f, iconSize * 0.42f, accentPaint)
            }

            val label = if (app.label.length > 13) app.label.take(12) + "…" else app.label
            canvas.drawText(
                label,
                cellRect.centerX(),
                cellRect.bottom - 12f * scale,
                appLabelPaint
            )
        }

        // Draw last touch feedback ring if present
        _stats.value.lastTouchPoint?.let { (tx, ty) ->
            canvas.drawCircle(tx, ty, 18f * scale, accentPaint)
        }
    }

    private suspend fun awaitDisplayRegistered(displayId: Int) {
        repeat(12) { attempt ->
            val resolved = displayManager.getDisplay(displayId)
            if (resolved != null && resolved.isValid) {
                if (attempt > 0) {
                    Log.d(TAG, "VirtualDisplay id=$displayId became ready after ${attempt * 25}ms")
                }
                return
            }
            delay(25L)
        }
    }

    private suspend fun showPresentationWithRetry(
        display: Display,
        targetWidth: Int,
        targetHeight: Int
    ): CockpitPresentation {
        var lastError: Throwable? = null
        val isPrivateDisplay = (display.flags and Display.FLAG_PRIVATE) != 0
        val windowType = if (isPrivateDisplay) {
            WINDOW_TYPE_PRIVATE_PRESENTATION
        } else {
            WINDOW_TYPE_PRESENTATION
        }

        for (attempt in 0 until 3) {
            try {
                return withContext(Dispatchers.Main.immediate) {
                    val baseDisplayContext = runCatching { appContext.createDisplayContext(display) }.getOrElse { appContext }
                    // Synchronize Presentation densityDpi = 160 and exact targetWidth x targetHeight (1280x720)
                    val fixedConfig = Configuration(baseDisplayContext.resources.configuration).apply {
                        densityDpi = FIXED_VIRTUAL_DISPLAY_DPI
                        screenWidthDp = targetWidth
                        screenHeightDp = targetHeight
                        smallestScreenWidthDp = minOf(targetWidth, targetHeight)
                    }
                    val displayContext = runCatching {
                        baseDisplayContext.createConfigurationContext(fixedConfig)
                    }.getOrElse { baseDisplayContext }

                    val presentation = runCatching {
                        CockpitPresentation(
                            outerContext = displayContext,
                            display = display,
                            statsFlow = _stats,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight
                        ).also { it.show() }
                    }.getOrElse {
                        val winContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            runCatching {
                                displayContext.createWindowContext(display, windowType, null)
                            }.getOrElse { displayContext }
                        } else {
                            displayContext
                        }
                        CockpitPresentation(
                            outerContext = winContext,
                            display = display,
                            statsFlow = _stats,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight
                        ).also { it.show() }
                    }
                    presentation
                }
            } catch (e: Throwable) {
                lastError = e
                delay(50L * (attempt + 1))
            }
        }
        throw (lastError as? WindowManager.InvalidDisplayException)
            ?: WindowManager.InvalidDisplayException("Unable to show Presentation on display ${display.displayId}: ${lastError?.message}")
    }

    /**
     * Force-unlocked immediately after `MSG_CMD_HU_PROTOCOL_VERSION (0x00018001)` / `VIDEO_ENCODER_START (0x00018009)`.
     *
     * 1. Sets `isVideoChannelReadyForStreaming = true`.
     * 2. Forces `MediaCodec` to generate a fresh sync keyframe (`PARAMETER_KEY_REQUEST_SYNC_FRAME = 0`).
     * 3. If `cachedSpsPps` and `lastKeyFrameAnnexB` are already cached in memory, immediately sends
     *    ONE combined `0x0002` packet (`cachedSpsPps + idrBytes`) per `VideoOutputThread.smali`.
     */
    fun unlockVideoChannelAndRequestSyncFrame() {
        awaitingFirstIdrAfterGateOpen.set(true)
        lastCodecFrameElapsedMs = SystemClock.elapsedRealtime()
        isVideoChannelReadyForStreaming = true
        mainHandler.post {
            cockpitPresentation?.window?.decorView?.postInvalidate()
        }

        val codec = mediaCodec ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }

        val idr = lastKeyFrameAnnexB
        if (cachedSpsPps != null &&
            idr != null &&
            awaitingFirstIdrAfterGateOpen.compareAndSet(true, false)
        ) {
            val tsMs = SystemClock.uptimeMillis() and 0x7FFF_FFFFL
            emitCombinedSpsPpsAndIdr(
                idrBytes = idr,
                timestampMs = tsMs
            )
        }
    }

    /**
     * Requests a fresh `IDR` sync frame from `MediaCodec` and sends ONE combined `SPS+PPS+IDR`
     * packet on `0x0002` ONLY if `isVideoChannelReadyForStreaming` is `true`.
     */
    fun requestInstantIdrKeyFrame() {
        mainHandler.post {
            cockpitPresentation?.window?.decorView?.postInvalidate()
        }
        val codec = mediaCodec ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }
        if (!isVideoChannelReadyForStreaming) return

        val idr = lastKeyFrameAnnexB
        if (idr != null) {
            val tsMs = SystemClock.uptimeMillis() and 0x7FFF_FFFFL
            emitCombinedSpsPpsAndIdr(
                idrBytes = idr,
                timestampMs = tsMs
            )
        }
    }

    /**
     * Matches original `VideoOutputThread.smali` (`outputSpsPpsAndFirstFrame` / `firstSendEncodeFrame`):
     * merges `cachedSpsPps + idrBytes` (avoiding duplicate SPS/PPS if the codec already prepended them)
     * and dispatches the entire keyframe in ONE `onEncodedVideoNal` call (ONE `sendVideoFrame` header).
     */
    private fun emitCombinedSpsPpsAndIdr(
        idrBytes: ByteArray,
        timestampMs: Long
    ): Int {
        if (idrBytes.isEmpty()) return 0
        val combinedPacket = CarLifeFrameCodec.mergeSpsPpsWithIdr(
            cachedSpsPps = cachedSpsPps,
            idrBytes = idrBytes
        )
        if (combinedPacket.isEmpty()) return 0

        onEncodedVideoNal(combinedPacket, timestampMs, true)
        _stats.update {
            it.copy(
                spsPpsConfigured = (cachedSpsPps != null || it.spsPpsConfigured),
                encodedNalFrames = it.encodedNalFrames + 1L,
                encodedBytes = it.encodedBytes + combinedPacket.size.toLong(),
                lastIdrBytes = combinedPacket.size
            )
        }
        return combinedPacket.size
    }

    /**
     * Scales touch coordinates from the CarLife Head Unit stream (`huWidth × huHeight`, base `1280×720`)
     * and injects gestures into:
     * 1. **Screen Mirroring (`MediaProjection`)**: maps `(rawX, rawY)` to real physical smartphone screen
     *    coordinates (`WindowManager.currentWindowMetrics.bounds`, orientation-aware) and dispatches tap/swipe
     *    `Path` gestures via [CarLifeAccessibilityService] onto `Display.DEFAULT_DISPLAY`.
     * 2. **VirtualDisplay (`Presentation`)**: dispatches into [CarLifeAccessibilityService] and `CockpitPresentation`.
     * 3. **Canvas Test Card**: runs fallback grid hit-testing.
     */
    suspend fun injectTouchPacket(packet: CarLifeTouchPacket): Boolean {
        val currentStats = _stats.value
        val huTouchMaxX = packet.huWidth.takeIf { it > 0 }?.toFloat()
            ?: CarLifeTouchDecoder.DEFAULT_HU_TOUCH_MAX_X
        val huTouchMaxY = packet.huHeight.takeIf { it > 0 }?.toFloat()
            ?: CarLifeTouchDecoder.DEFAULT_HU_TOUCH_MAX_Y

        return withContext(Dispatchers.Main.immediate) {
            val isMirroringRealScreen =
                videoSourceMode == VideoSourceMode.SCREEN_MIRRORING ||
                    (cockpitPresentation == null && currentStats.usingMediaProjection)

            if (isMirroringRealScreen) {
                val (screenW, screenH) = CarLifeAccessibilityService.computePhysicalScreenSize(appContext)
                val (physX, physY) = CarLifeAccessibilityService.mapHuTouchToPhysicalScreen(
                    rawX = packet.rawX,
                    rawY = packet.rawY,
                    huWidth = huTouchMaxX,
                    huHeight = huTouchMaxY,
                    screenWidth = screenW,
                    screenHeight = screenH
                )
                val a11yService = CarLifeAccessibilityService.instance
                val a11yDispatched = a11yService?.handleMotionStream(
                    displayId = Display.DEFAULT_DISPLAY,
                    action = packet.androidAction,
                    x = physX,
                    y = physY,
                    synthesizeClickUp = packet.synthesizeClickUp
                ) ?: false

                val statusLabel = if (a11yService != null) {
                    "Mirror Touch (${physX.toInt()},${physY.toInt()}) / ${screenW}×${screenH}"
                } else {
                    "Touch 0x0006 получен, но CarLifeAccessibilityService ВЫКЛ!"
                }

                _stats.update {
                    it.copy(
                        injectedTouches = it.injectedTouches + 1L,
                        lastTouchPoint = physX to physY,
                        activeCockpitWidget = statusLabel,
                        statusMessage = statusLabel
                    )
                }
                return@withContext a11yDispatched
            }

            val vDisplayId = virtualDisplay?.display?.displayId ?: Display.DEFAULT_DISPLAY
            val decorView = cockpitPresentation?.window?.decorView
            val currentSurfaceWidth = decorView?.width?.takeIf { it > 0 }
                ?: currentStats.width.coerceAtLeast(1)
            val currentSurfaceHeight = decorView?.height?.takeIf { it > 0 }
                ?: currentStats.height.coerceAtLeast(1)

            val (scaledX, scaledY) = CarLifeTouchDecoder.scaleTouchCoordinates(
                rawX = packet.rawX,
                rawY = packet.rawY,
                currentSurfaceWidth = currentSurfaceWidth,
                currentSurfaceHeight = currentSurfaceHeight,
                huTouchMaxX = huTouchMaxX,
                huTouchMaxY = huTouchMaxY
            )

            val a11yDispatched = CarLifeAccessibilityService.instance?.handleMotionStream(
                displayId = vDisplayId,
                action = packet.androidAction,
                x = scaledX,
                y = scaledY,
                synthesizeClickUp = packet.synthesizeClickUp
            ) ?: false

            var viewDispatched = false
            if (decorView != null) {
                val nowUptime = SystemClock.uptimeMillis()
                val downTime = when (packet.androidAction) {
                    MotionEvent.ACTION_DOWN -> {
                        lastDownTime.set(nowUptime)
                        nowUptime
                    }
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val saved = lastDownTime.get()
                        if (saved > 0L) saved else nowUptime
                    }
                    else -> {
                        val saved = lastDownTime.get()
                        if (saved > 0L) saved else nowUptime
                    }
                }
                val eventTime = maxOf(downTime, nowUptime)

                viewDispatched = dispatchSynthesizedMotionEvent(
                    decorView = decorView,
                    downTime = downTime,
                    eventTime = eventTime,
                    action = packet.androidAction,
                    x = scaledX,
                    y = scaledY
                )

                if (packet.synthesizeClickUp) {
                    val upTime = maxOf(downTime + 16L, SystemClock.uptimeMillis())
                    dispatchSynthesizedMotionEvent(
                        decorView = decorView,
                        downTime = downTime,
                        eventTime = upTime,
                        action = MotionEvent.ACTION_UP,
                        x = scaledX,
                        y = scaledY
                    )
                }
            } else if (packet.androidAction == MotionEvent.ACTION_UP || packet.synthesizeClickUp) {
                // Canvas mode hit-test for app launch and media controls
                handleCanvasLauncherTap(
                    scaledX,
                    scaledY,
                    currentSurfaceWidth.toFloat(),
                    currentSurfaceHeight.toFloat(),
                    vDisplayId
                )
            }

            val zoneHit = if (scaledX < currentSurfaceWidth * 0.25f) {
                "Dock Bar (${scaledX.toInt()}, ${scaledY.toInt()}) on VD #$vDisplayId"
            } else {
                "App Grid (${scaledX.toInt()}, ${scaledY.toInt()}) on VD #$vDisplayId"
            }

            _stats.update {
                it.copy(
                    injectedTouches = it.injectedTouches + 1L,
                    lastTouchPoint = scaledX to scaledY,
                    activeCockpitWidget = zoneHit,
                    statusMessage = "Touch Injected -> $zoneHit"
                )
            }
            viewDispatched || a11yDispatched
        }
    }

    private fun handleCanvasLauncherTap(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        vDisplayId: Int
    ) {
        val dockRight = (w * 0.24f).coerceIn(260f, 340f)
        if (x <= dockRight) {
            if (y >= h - 100f) {
                MediaControllerRepository.togglePlayPause()
            } else if (y in 90f..175f && x > dockRight / 2f) {
                CarLifeAccessibilityService.instance?.performBackAction()
            }
            return
        }

        val apps = CarLifeLauncherRepository.installedApps.value.take(12)
        if (apps.isEmpty()) return
        val gridLeft = dockRight + 40f
        val gridTop = 100f
        val availW = (w - 16f) - dockRight - 64f
        val availH = (h - 32f) - 104f
        if (x < gridLeft || y < gridTop) return
        val col = (((x - gridLeft) / availW) * 4f).toInt().coerceIn(0, 3)
        val row = (((y - gridTop) / availH) * 3f).toInt().coerceIn(0, 2)
        val index = row * 4 + col
        if (index in apps.indices) {
            CarLifeLauncherRepository.launchAppOnVirtualDisplay(
                context = appContext,
                app = apps[index],
                targetDisplayId = vDisplayId
            )
        }
    }

    suspend fun injectHardKeyPacket(packet: CarLifeHardKeyPacket): Boolean {
        if (packet.androidKeyCode == KeyEvent.KEYCODE_UNKNOWN) return false
        return withContext(Dispatchers.Main.immediate) {
            when (packet.androidKeyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> MediaControllerRepository.skipNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaControllerRepository.skipPrevious()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MediaControllerRepository.togglePlayPause()
                KeyEvent.KEYCODE_HOME -> {
                    runCatching { cockpitPresentation?.show() }
                }
            }
            val a11yHandled = CarLifeAccessibilityService.instance
                ?.handleSystemHardKey(packet.androidKeyCode) ?: false
            val decorView = cockpitPresentation?.window?.decorView ?: return@withContext a11yHandled
            val now = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, packet.androidKeyCode, 0)
            val upEvent = KeyEvent(now, now + 15L, KeyEvent.ACTION_UP, packet.androidKeyCode, 0)
            val downHandled = decorView.dispatchKeyEvent(downEvent)
            val upHandled = decorView.dispatchKeyEvent(upEvent)
            _stats.update {
                it.copy(
                    statusMessage = "HardKey Injected -> KeyCode=${packet.androidKeyCode}"
                )
            }
            downHandled || upHandled || a11yHandled
        }
    }

    suspend fun stopProjection() = projectionMutex.withLock {
        stopProjectionLocked(updateStatus = true)
    }

    private suspend fun stopEncoderOnlyLocked() {
        isEncoding.set(false)
        presentationRefreshJob?.cancel()
        presentationRefreshJob = null
        val feeder = canvasFeederJob
        canvasFeederJob = null
        val drainer = encoderDrainJob
        encoderDrainJob = null

        withContext(NonCancellable) {
            runCatching { feeder?.cancelAndJoin() }
            runCatching { drainer?.cancelAndJoin() }
        }

        runCatching { virtualDisplay?.surface = null }

        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null

        runCatching { encoderInputSurface?.release() }
        encoderInputSurface = null
        cachedSpsPps = null
        cachedSpsAnnexB = null
        cachedPpsAnnexB = null
        lastKeyFrameAnnexB = null
    }

    private suspend fun stopProjectionLocked(updateStatus: Boolean = true) {
        stopEncoderOnlyLocked()

        withContext(Dispatchers.Main.immediate) {
            runCatching { cockpitPresentation?.dismiss() }
            cockpitPresentation = null
        }

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        virtualDisplayWidth = 0
        virtualDisplayHeight = 0
        virtualDisplayDpi = 0
        virtualDisplayIsMirrorOnly = false

        runCatching {
            mediaProjectionCallback?.let { cb ->
                mediaProjection?.unregisterCallback(cb)
            }
            mediaProjection?.stop()
        }
        mediaProjectionCallback = null
        mediaProjection = null

        if (updateStatus) {
            _stats.update {
                it.copy(
                    isRunning = false,
                    usingMediaProjection = false,
                    virtualDisplayId = Display.DEFAULT_DISPLAY,
                    spsPpsConfigured = false,
                    statusMessage = "Projection Stopped"
                )
            }
        }
    }

    private fun configureLowLatencyAvcEncoder(
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int
    ): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = runCatching {
            codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }.getOrNull()

        val supportsBaseline = caps?.profileLevels?.any {
            it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        } ?: true
        val supportsBaselineLevel31 = caps?.profileLevels?.any {
            it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline &&
                it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel31
        } ?: true
        val supportsCbr = caps?.encoderCapabilities?.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        ) ?: true

        fun createBaseFormat(
            includeBaselineProfile: Boolean,
            includeLevel31: Boolean,
            includeCbr: Boolean,
            includeOptionalTimingKeys: Boolean
        ): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (includeBaselineProfile) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                if (includeLevel31) {
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel31
                    )
                }
            }
            if (includeCbr) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
            // Note: Do NOT set KEY_PREPEND_HEADER_TO_SYNC_FRAMES — many CCodec encoders reject it with
            // err 0xffffffea (BAD_VALUE). SPS/PPS are cached into cachedSpsPps from csd-0/csd-1 and
            // BUFFER_FLAG_CODEC_CONFIG and merged into ONE packet (cachedSpsPps + idrBytes) in emitCombinedSpsPpsAndIdr().
            if (includeOptionalTimingKeys) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
        }

        val candidates = buildList {
            if (supportsBaseline && supportsBaselineLevel31 && supportsCbr) {
                add(
                    "AVCProfileBaseline+Level31+CBR" to createBaseFormat(
                        includeBaselineProfile = true,
                        includeLevel31 = true,
                        includeCbr = true,
                        includeOptionalTimingKeys = true
                    )
                )
            }
            if (supportsBaseline && supportsCbr) {
                add(
                    "AVCProfileBaseline+CBR" to createBaseFormat(
                        includeBaselineProfile = true,
                        includeLevel31 = false,
                        includeCbr = true,
                        includeOptionalTimingKeys = true
                    )
                )
            }
            if (supportsCbr) {
                add(
                    "GenericAVC+CBR" to createBaseFormat(
                        includeBaselineProfile = false,
                        includeLevel31 = false,
                        includeCbr = true,
                        includeOptionalTimingKeys = true
                    )
                )
            }
            add(
                "GenericAVC+DefaultBitrateMode" to createBaseFormat(
                    includeBaselineProfile = false,
                    includeLevel31 = false,
                    includeCbr = false,
                    includeOptionalTimingKeys = true
                )
            )
            add(
                "MinimalAVC" to createBaseFormat(
                    includeBaselineProfile = false,
                    includeLevel31 = false,
                    includeCbr = false,
                    includeOptionalTimingKeys = false
                )
            )
        }

        var lastError: Exception? = null
        candidates.forEachIndexed { index, (label, candidate) ->
            try {
                if (index > 0) {
                    codec.reset()
                }
                codec.configure(candidate, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "Configured H.264 encoder in $label ($width×$height @ ${frameRate}fps, ${bitRate / 1000}kbps)")
                return codec
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "H.264 config candidate $label rejected (${e.message}), trying next...")
            }
        }

        runCatching { codec.release() }
        throw lastError ?: IllegalStateException("Failed to configure H.264 MediaCodec encoder")
    }

    private fun rebuildCachedSpsPps(fallbackConfig: ByteArray? = null) {
        val sps = cachedSpsAnnexB
        val pps = cachedPpsAnnexB
        cachedSpsPps = when {
            sps != null && pps != null -> {
                ByteArray(sps.size + pps.size).apply {
                    System.arraycopy(sps, 0, this, 0, sps.size)
                    System.arraycopy(pps, 0, this, sps.size, pps.size)
                }
            }
            fallbackConfig != null && fallbackConfig.isNotEmpty() -> {
                CarLifeFrameCodec.normalizeConfigBuffer(fallbackConfig)
            }
            sps != null -> sps
            pps != null -> pps
            else -> null
        }
        _stats.update {
            it.copy(spsPpsConfigured = cachedSpsPps != null)
        }
    }

    private fun cacheConfigNalUnits(buffer: ByteArray, saveWholeBufferAsFallback: Boolean = false) {
        if (buffer.isEmpty()) return
        val nals = CarLifeFrameCodec.splitAnnexBNalUnits(buffer)
        var updated = false
        for (nal in nals) {
            val nalType = if (nal.size > 4) (nal[4].toInt() and 0x1F) else 0
            when (nalType) {
                7 -> {
                    cachedSpsAnnexB = nal
                    updated = true
                }
                8 -> {
                    cachedPpsAnnexB = nal
                    updated = true
                }
            }
        }
        if (updated || saveWholeBufferAsFallback) {
            rebuildCachedSpsPps(fallbackConfig = if (saveWholeBufferAsFallback) buffer else null)
        }
    }

    private suspend fun CoroutineScope.drainHardwareEncoderLoop(
        codec: MediaCodec,
        initialBitRate: Int
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var currentDynamicBitrate = initialBitRate

        try {
            while (isActive && isEncoding.get()) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        val nowElapsed = SystemClock.elapsedRealtime()
                        // Per VideoOutputThread.smali: if no frame arrives from MediaCodec for >200 ms,
                        // send an empty MSG_VIDEO_HEARTBEAT (0x00020002, payloadLen = 0) instead of standalone SPS/PPS.
                        if (isVideoChannelReadyForStreaming &&
                            lastCodecFrameElapsedMs > 0L &&
                            (nowElapsed - lastCodecFrameElapsedMs) >= 200L
                        ) {
                            lastCodecFrameElapsedMs = nowElapsed
                            val keepAliveTs = SystemClock.uptimeMillis() and 0x7FFF_FFFFL
                            onVideoIdleHeartbeat(keepAliveTs)
                            if (awaitingFirstIdrAfterGateOpen.get()) {
                                runCatching {
                                    val syncParams = Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    }
                                    codec.setParameters(syncParams)
                                }
                            }
                        }
                        delay(2L)
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        val sps = outFormat.getByteBuffer("csd-0")
                        val pps = outFormat.getByteBuffer("csd-1")
                        if (sps != null) {
                            val spsDup = sps.duplicate()
                            val bytes = ByteArray(spsDup.remaining())
                            spsDup.get(bytes)
                            cacheConfigNalUnits(bytes)
                        }
                        if (pps != null) {
                            val ppsDup = pps.duplicate()
                            val bytes = ByteArray(ppsDup.remaining())
                            ppsDup.get(bytes)
                            cacheConfigNalUnits(bytes)
                        }
                    }

                    outIndex >= 0 -> {
                        val encodedBuffer = codec.getOutputBuffer(outIndex)
                        if (encodedBuffer != null && bufferInfo.size > 0) {
                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrameFlag = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val rawNal = ByteArray(bufferInfo.size)
                            encodedBuffer.get(rawNal)

                            val timestampMs = SystemClock.uptimeMillis() and 0x7FFF_FFFFL

                            // Per VideoOutputThread.smali: cache BUFFER_FLAG_CODEC_CONFIG (SPS+PPS) into cachedSpsPps
                            // and NEVER transmit it as a standalone packet.
                            if (isCodecConfig && !isKeyFrameFlag) {
                                cacheConfigNalUnits(rawNal, saveWholeBufferAsFallback = true)
                                codec.releaseOutputBuffer(outIndex, false)
                                continue
                            }

                            // Inspect NAL types without splitting outbound frames so we keep cachedSpsPps up to date
                            // and detect IDR frames even on encoders that omit BUFFER_FLAG_KEY_FRAME.
                            val scannedNals = CarLifeFrameCodec.splitAnnexBNalUnits(rawNal)
                            var containsIdrNal = isKeyFrameFlag
                            var sawConfigInFrame = false
                            for (nal in scannedNals) {
                                val nalType = if (nal.size > 4) (nal[4].toInt() and 0x1F) else 0
                                when (nalType) {
                                    7 -> {
                                        cachedSpsAnnexB = nal
                                        sawConfigInFrame = true
                                    }
                                    8 -> {
                                        cachedPpsAnnexB = nal
                                        sawConfigInFrame = true
                                    }
                                    5 -> {
                                        containsIdrNal = true
                                    }
                                }
                            }
                            if (sawConfigInFrame) {
                                rebuildCachedSpsPps()
                            }

                            if (containsIdrNal) {
                                lastKeyFrameAnnexB = CarLifeFrameCodec.ensureAnnexBStartCode(rawNal)
                            }

                            // Pure config-only buffer without any video slice: never send standalone.
                            if (isCodecConfig && !containsIdrNal) {
                                codec.releaseOutputBuffer(outIndex, false)
                                continue
                            }

                            // STRICT GATE: Do NOT send any 0x0002 packets or increment counters
                            // while isVideoChannelReadyForStreaming == false (only cache SPS+PPS and last IDR).
                            if (!isVideoChannelReadyForStreaming) {
                                codec.releaseOutputBuffer(outIndex, false)
                                continue
                            }

                            if (containsIdrNal) {
                                lastCodecFrameElapsedMs = SystemClock.elapsedRealtime()
                                awaitingFirstIdrAfterGateOpen.set(false)
                                // Send ONE combined packet: cachedSpsPps + idrBytes (unless already prepended)
                                // Keep steady configured CBR bitrate (2 000 000 bps default) for crisp 1280×720 clarity.
                                emitCombinedSpsPpsAndIdr(
                                    idrBytes = rawNal,
                                    timestampMs = timestampMs
                                )
                            } else if (!awaitingFirstIdrAfterGateOpen.get()) {
                                lastCodecFrameElapsedMs = SystemClock.elapsedRealtime()
                                // Regular P-frame (Slice): send as-is in ONE packet without splitting
                                val slicePacket = CarLifeFrameCodec.ensureAnnexBStartCode(rawNal)
                                onEncodedVideoNal(slicePacket, timestampMs, false)
                                _stats.update {
                                    it.copy(
                                        encodedNalFrames = it.encodedNalFrames + 1L,
                                        encodedBytes = it.encodedBytes + slicePacket.size.toLong()
                                    )
                                }
                            } else {
                                // Waiting for initial IDR after gate open: request sync frame and skip stale P-slice
                                runCatching {
                                    val b = Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    }
                                    codec.setParameters(b)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Hardware H.264 encoder drain loop ended: ${t.message}")
        }
    }

    private fun dispatchSynthesizedMotionEvent(
        decorView: android.view.View,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): Boolean {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            x,
            y,
            0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            decorView.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    companion object {
        private const val TAG = "CockpitProjectionMgr"
        private const val VIRTUAL_DISPLAY_NAME = "CarLife_AppMirroring_Display"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val WINDOW_TYPE_PRIVATE_PRESENTATION = 2030
        private const val WINDOW_TYPE_PRESENTATION = 2037
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_BITRATE_BPS = 2_000_000
        const val FIXED_VIRTUAL_DISPLAY_DPI = DisplayMetrics.DENSITY_MEDIUM // 160 dpi
    }
}

private class PresentationLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Android [Presentation] window rendered onto the `VirtualDisplay` when `MediaProjection` is active.
 */
class CockpitPresentation(
    outerContext: Context,
    display: Display,
    private val statsFlow: StateFlow<ProjectionPipelineStats>,
    val targetWidth: Int = CockpitProjectionManager.DEFAULT_WIDTH,
    val targetHeight: Int = CockpitProjectionManager.DEFAULT_HEIGHT
) : Presentation(outerContext, display) {

    private val lifecycleOwner = PresentationLifecycleOwner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setLayout(targetWidth, targetHeight)
        }

        lifecycleOwner.onCreate()

        val decorView = window?.decorView
        decorView?.setViewTreeLifecycleOwner(lifecycleOwner)
        decorView?.setViewTreeViewModelStoreOwner(lifecycleOwner)
        decorView?.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        val targetDisplayId = display.displayId
        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(targetWidth, targetHeight)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                MyApplicationTheme {
                    ProjectedCarLifeLauncherContainer(
                        virtualDisplayId = targetDisplayId,
                        statsFlow = statsFlow,
                        onBringLauncherToFront = {
                            runCatching { show() }
                        }
                    )
                }
            }
        }
        setContentView(composeView, ViewGroup.LayoutParams(targetWidth, targetHeight))
        window?.setLayout(targetWidth, targetHeight)
    }

    override fun onStart() {
        super.onStart()
        lifecycleOwner.onStart()
    }

    override fun onStop() {
        lifecycleOwner.onStop()
        super.onStop()
    }

    override fun dismiss() {
        super.dismiss()
        lifecycleOwner.onDestroy()
    }
}

@Composable
private fun ProjectedCarLifeLauncherContainer(
    virtualDisplayId: Int,
    statsFlow: StateFlow<ProjectionPipelineStats>,
    onBringLauncherToFront: () -> Unit
) {
    val stats by statsFlow.collectAsState()
    val media by MediaControllerRepository.mediaState.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "h264_surface_keepalive")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        CarLifeAppLauncherScreen(
            virtualDisplayId = virtualDisplayId,
            mediaState = media,
            onPlayPause = { MediaControllerRepository.togglePlayPause() },
            onNextTrack = { MediaControllerRepository.skipNext() },
            onPreviousTrack = { MediaControllerRepository.skipPrevious() },
            onRequestNotificationAccess = {},
            onHomePressed = onBringLauncherToFront,
            onBackPressed = {
                CarLifeAccessibilityService.instance?.performBackAction()
            }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = ElectricCyan.copy(alpha = pulsePhase * 0.25f),
                radius = 3.dp.toPx(),
                center = Offset(size.width - 8.dp.toPx(), 8.dp.toPx())
            )

            stats.lastTouchPoint?.let { (tx, ty) ->
                val normX = (tx / stats.width.coerceAtLeast(1).toFloat()) * size.width
                val normY = (ty / stats.height.coerceAtLeast(1).toFloat()) * size.height
                val center = Offset(
                    normX.coerceIn(0f, size.width),
                    normY.coerceIn(0f, size.height)
                )
                drawCircle(
                    color = AmberSignal.copy(alpha = 0.35f),
                    radius = 28.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = ElectricCyan,
                    radius = 16.dp.toPx(),
                    center = center,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
