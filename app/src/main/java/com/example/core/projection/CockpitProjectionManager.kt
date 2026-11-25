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
import android.os.Process
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.example.core.input.TouchSettingsPreferences
import com.example.core.shizuku.CarLifeShizukuManager
import com.example.feature.cockpit.AppLaunchMode
import com.example.feature.cockpit.CarLifeAppLauncherScreen
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.CockpitUiStateController
import com.example.feature.cockpit.MediaControllerRepository
import com.example.feature.cockpit.MediaPlayerController
import com.example.feature.cockpit.VW_LEFT_PANEL_WIDTH_PX
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeHardKey
import com.example.protocol.carlife.CarLifeTouchDecoder
import com.example.protocol.carlife.SteeringWheelAction
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemePreferences
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
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
import kotlinx.coroutines.yield

/**
 * Holds the single-use [MediaProjection] consent result (`resultCode` + `data` [Intent])
 * from Android 14–16 screen capture prompt as well as active projection lifecycle & diagnostic state.
 *
 * On Android 14+, the `Intent` token is strictly single-use: [consumeToken] clears `resultData`
 * immediately upon calling `mediaProjectionManager.getMediaProjection(...)`.
 */
object MediaProjectionTokenStore {
    @Volatile
    var resultCode: Int = Activity.RESULT_CANCELED
        private set

    @Volatile
    var resultData: Intent? = null
        private set

    private val _hasPendingToken = MutableStateFlow(false)
    val hasPendingToken: StateFlow<Boolean> = _hasPendingToken.asStateFlow()

    private val _isProjectionActive = MutableStateFlow(false)
    val isProjectionActive: StateFlow<Boolean> = _isProjectionActive.asStateFlow()

    private val _hasValidToken = MutableStateFlow(false)
    val hasValidToken: StateFlow<Boolean> = _hasValidToken.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _statusDetail = MutableStateFlow("Разрешение MediaProjection («Весь экран») не выдано")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    @Synchronized
    fun saveToken(code: Int, data: Intent?) {
        if (code == Activity.RESULT_OK && data != null) {
            resultCode = code
            resultData = Intent(data)
            _hasPendingToken.value = true
            _hasValidToken.value = true
            _lastError.value = null
            _statusDetail.value = "Одноразовый токен «Весь экран» получен"
        }
    }

    /**
     * Atomically extracts and clears the single-use `resultData` [Intent] so it is NEVER reused
     * on Android 14–16 after calling `getMediaProjection(...)`.
     */
    @Synchronized
    fun consumeToken(): Pair<Int, Intent>? {
        val code = resultCode
        val data = resultData
        if (code != Activity.RESULT_OK || data == null) {
            return null
        }
        val copy = Intent(data)
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        _hasPendingToken.value = false
        _hasValidToken.value = _isProjectionActive.value
        _statusDetail.value = "Одноразовый токен передан в MediaProjection"
        return code to copy
    }

    @Synchronized
    fun setActiveProjection(
        active: Boolean,
        detail: String? = null,
        error: String? = null
    ) {
        _isProjectionActive.value = active
        _hasValidToken.value = active || (_hasPendingToken.value && resultData != null)
        if (error != null) {
            _lastError.value = error
        } else if (active) {
            _lastError.value = null
        }
        _statusDetail.value = detail ?: if (active) {
            "MediaProjection активен (Весь экран)"
        } else {
            error ?: "MediaProjection не активен"
        }
    }

    @Synchronized
    fun recordError(errorMessage: String) {
        _lastError.value = errorMessage
        _statusDetail.value = errorMessage
    }

    @Synchronized
    fun clearToken(reason: String? = null) {
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        _hasPendingToken.value = false
        _isProjectionActive.value = false
        _hasValidToken.value = false
        if (reason != null) {
            _lastError.value = reason
            _statusDetail.value = reason
        } else {
            _statusDetail.value = "Токен MediaProjection сброшен"
        }
    }
}

/**
 * Persistent SharedPreferences store for the selected [AppMode] so reconnecting USB AOA or restarting
 * the Activity/Service never silently resets `MIRRORING` or `SHIZUKU_COCKPIT` back to `COCKPIT`.
 */
object AppModePreferences {
    private const val PREFS_NAME = "carlife_bridge_prefs"
    private const val KEY_APP_MODE = "selected_app_mode"

    fun saveAppMode(context: Context, mode: AppMode) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP_MODE, mode.id)
                .commit()
        }
    }

    fun loadAppMode(context: Context): AppMode {
        if (com.example.core.config.AppEditionConfig.isMirrorOnly) {
            return AppMode.MIRRORING
        }
        val savedId = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_MODE, null)
        }.getOrNull()
        return AppMode.fromId(savedId)
    }

    fun clear(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_APP_MODE)
                .commit()
        }
    }
}

/**
 * Главный режим работы приложения:
 * - [COCKPIT]: Виртуальный экран (`VirtualDisplay` + `CockpitPresentation`), тач `0x0006` идёт строго в `decorView.dispatchTouchEvent`.
 * - [MIRRORING]: Зеркалирование экрана телефона (`MediaProjection` без `Presentation`), тач `0x0006` идёт строго через `CarLifeAccessibilityService`.
 * - [SHIZUKU_COCKPIT]: Второй экран (`VirtualDisplay` 1280×720@30fps VBR 160 dpi) с запуском любых приложений через Shizuku (`am start --display`) и тачем через `InputManager`.
 */
enum class AppMode(
    val id: String,
    val title: String,
    val subtitle: String
) {
    COCKPIT(
        id = "cockpit",
        title = "Кокпит (Стандартный)",
        subtitle = "Лаунчер ГУ на VirtualDisplay (Presentation). Тач 0x0006 идёт строго в Presentation.decorView без AccessibilityService."
    ),
    MIRRORING(
        id = "mirroring",
        title = "Зеркалирование (Экран телефона)",
        subtitle = "Прямой захват экрана смартфона (MediaProjection, 1280×720@30fps). Тач 0x0006 идёт строго через CarLifeAccessibilityService."
    ),
    SHIZUKU_COCKPIT(
        id = "shizuku_cockpit",
        title = "Кокпит + Shizuku (Второй экран)",
        subtitle = "VirtualDisplay (1280×720@30fps, 160 dpi) + запуск 2ГИС/Навигатора/YouTube через Shizuku (--display) и прямой тач через InputManager."
    );

    companion object {
        fun fromId(id: String?): AppMode =
            entries.firstOrNull { it.id == id } ?: MIRRORING
    }
}

/**
 * Selectable video input source for the H.264 `MediaCodec` pipeline:
 * - [SCREEN_MIRRORING]: Direct `MediaProjection` mirroring of the smartphone screen without `Presentation`.
 * - [VIRTUAL_DISPLAY]: Captures `VirtualDisplay` / `MediaProjection` + CarLife Launcher UI (`CockpitPresentation`).
 * - [CANVAS_TEST_CARD]: Draws a bright 30fps synthetic test card directly onto `MediaCodec.createInputSurface()` without `VirtualDisplay`.
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
    val appMode: AppMode = AppMode.MIRRORING,
    val videoSourceMode: VideoSourceMode = VideoSourceMode.SCREEN_MIRRORING,
    val usingMediaProjection: Boolean = false,
    val isMediaProjectionFgsActive: Boolean = false,
    val virtualDisplayId: Int = Display.DEFAULT_DISPLAY,
    val virtualDisplayDpi: Int = CockpitProjectionManager.MIRROR_VIRTUAL_DISPLAY_DPI,
    val mediaProjectionState: String = "Ожидает разрешения («Весь экран»)",
    val lastMediaProjectionError: String? = null,
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val actualFps: Float = 0f,
    val idrPerSec: Float = 0f,
    val activeEncoderProfile: String = "AVCProfileBaseline+Level3+VBR",
    val isLowLatencyEncoderActive: Boolean = false,
    val isPhoneScreenOff: Boolean = false,
    val droppedVideoPFrames: Long = 0L,
    val phoneScreenWidth: Int = 0,
    val phoneScreenHeight: Int = 0,
    val isPhoneLandscape: Boolean = false,
    val bitRateMbps: Int = 2,
    val bitRateKbps: Int = 2000,
    val encodedNalFrames: Long = 0L,
    val encodedBytes: Long = 0L,
    val lastIdrBytes: Int = 0,
    val spsPpsConfigured: Boolean = false,
    val injectedTouches: Long = 0L,
    val lastTouchPoint: Pair<Float, Float>? = null,
    val lastTouchDiagnostic: String = "Ожидание касаний 0x0006",
    val activeCockpitWidget: String = "Зеркалирование экрана (MediaProjection) готово",
    val statusMessage: String = "Зеркалирование экрана (1280×720 @ 2.0 Мбит/с, VBR 30fps, AOA 20B)"
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
    private val onMediaProjectionStopped: () -> Unit = {},
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
    private var virtualDisplayFlags: Int = 0
    private var cockpitPresentation: CockpitPresentation? = null
    private var shizukuLeftPanelOverlay: ShizukuLeftPanelOverlay? = null
    @Volatile
    private var isShizukuLauncherGridVisible: Boolean = true
    private var shizukuTouchDownInLeftPanel: Boolean = false
    @Volatile
    internal var shizukuLeftPanelViewForTesting: android.view.View? = null
    private var encoderDrainJob: Job? = null
    private var canvasFeederJob: Job? = null

    private val projectionMutex = Mutex()
    private val isEncoding = AtomicBoolean(false)
    private val lastCongestionIdrRequestMs = AtomicLong(0L)
    private val isDisplayListenerRegistered = AtomicBoolean(false)

    // Dedicated high-priority H.264 drain thread so encoder frames never wait behind Dispatchers.IO tasks
    private val encoderDrainDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            runnable.run()
        }, "CarLifeH264DrainThread").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }.asCoroutineDispatcher()

    @Volatile
    private var lastKnownScreenSize: Pair<Int, Int>? = null

    @Volatile
    private var lastMirrorTouchStatsUpdateMs: Long = 0L

    @Volatile
    private var isPhoneScreenOffFlag: Boolean = false

    @Volatile
    private var lastConfiguredLowLatency: Boolean = false

    private var lastCockpitMoveX: Float = Float.NaN
    private var lastCockpitMoveY: Float = Float.NaN
    private var lastShizukuMoveX: Float = Float.NaN
    private var lastShizukuMoveY: Float = Float.NaN

    @Volatile
    var appMode: AppMode = AppMode.MIRRORING

    @Volatile
    var videoSourceMode: VideoSourceMode = VideoSourceMode.SCREEN_MIRRORING

    @Volatile
    var appLaunchMode: AppLaunchMode = AppLaunchMode.SCREEN_MIRRORING
        private set

    /**
     * Android 14–16 FGS gate: must be `true` (set by `BridgeForegroundService.promoteToForeground()`)
     * before consuming the single-use `resultData` Intent in `getMediaProjection(...)`.
     * Defaults to `true` for standalone unit tests; `BridgeForegroundService` initializes it to `false`
     * until `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` succeeds.
     */
    @Volatile
    var isMediaProjectionFgsReady: Boolean = true
        private set

    @Volatile
    private var lastConfiguredEncoderProfile: String = "AVCProfileBaseline+Level3+VBR"

    private val defaultDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || appMode != AppMode.MIRRORING) return
            refreshPhysicalScreenOrientation(requestSyncOnChange = true)
        }
    }

    private fun registerDisplayListenerIfNeeded() {
        if (isDisplayListenerRegistered.compareAndSet(false, true)) {
            runCatching {
                displayManager.registerDisplayListener(defaultDisplayListener, mainHandler)
            }.onFailure {
                isDisplayListenerRegistered.set(false)
            }
        }
    }

    private fun unregisterDisplayListenerIfNeeded() {
        if (isDisplayListenerRegistered.compareAndSet(true, false)) {
            runCatching { displayManager.unregisterDisplayListener(defaultDisplayListener) }
        }
    }

    fun recalculatePhysicalScreenBounds(requestSyncOnChange: Boolean = true) {
        lastKnownScreenSize = null
        refreshPhysicalScreenOrientation(requestSyncOnChange = requestSyncOnChange)
    }

    private fun refreshPhysicalScreenOrientation(requestSyncOnChange: Boolean) {
        val currentSize = CarLifeAccessibilityService.instance?.getPhysicalScreenSize()
            ?: CarLifeAccessibilityService.computePhysicalScreenSize(appContext)
        val prev = lastKnownScreenSize
        if (prev != currentSize) {
            lastKnownScreenSize = currentSize
            _stats.update {
                it.copy(
                    phoneScreenWidth = currentSize.first,
                    phoneScreenHeight = currentSize.second,
                    isPhoneLandscape = currentSize.first > currentSize.second
                )
            }
            if (requestSyncOnChange && prev != null && isVideoChannelReadyForStreaming) {
                requestEncoderSyncFrameOnly()
            }
        }
    }

    private fun requestEncoderSyncFrameOnly() {
        val codec = mediaCodec ?: return
        runCatching {
            val syncParams = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(syncParams)
        }
    }

    fun setMediaProjectionFgsReady(ready: Boolean) {
        isMediaProjectionFgsReady = ready
        _stats.update { it.copy(isMediaProjectionFgsActive = ready) }
    }

    init {
        CarLifeLauncherRepository.onFallbackToPhoneScreen = {
            if (_stats.value.usingMediaProjection && appMode == AppMode.MIRRORING) {
                mainHandler.post {
                    runCatching { cockpitPresentation?.dismiss() }
                    cockpitPresentation = null
                }
            }
        }
        CarLifeLauncherRepository.onShizukuAppLaunchedOnVirtualDisplay = {
            if (appMode == AppMode.SHIZUKU_COCKPIT) {
                mainHandler.post {
                    onExternalAppShownInShizukuMode()
                }
            }
        }
    }

    private fun onExternalAppShownInShizukuMode() {
        isShizukuLauncherGridVisible = false
        runCatching { cockpitPresentation?.hide() }
        val display = virtualDisplay?.display ?: return
        val heightPx = _stats.value.height.coerceAtLeast(DEFAULT_HEIGHT)
        val overlay = shizukuLeftPanelOverlay ?: ShizukuLeftPanelOverlay(
            appContext = appContext,
            onHomeClick = {
                mainHandler.post { showShizukuLauncherGrid() }
            },
            onBackClick = {
                val vdId = virtualDisplay?.display?.displayId ?: _stats.value.virtualDisplayId
                CarLifeShizukuManager.sendKeyEventAsync(KeyEvent.KEYCODE_BACK, vdId)
            },
            onMapClick = {
                val vdId = virtualDisplay?.display?.displayId ?: _stats.value.virtualDisplayId
                openShizukuNavigatorOrFallbackMap(vdId)
            }
        ).also { shizukuLeftPanelOverlay = it }
        overlay.show(
            display = display,
            panelWidthPx = VW_LEFT_PANEL_WIDTH_PX,
            displayHeightPx = heightPx
        )
    }

    private fun showShizukuLauncherGrid() {
        shizukuLeftPanelOverlay?.dismiss()
        isShizukuLauncherGridVisible = true
        CockpitUiStateController.showGrid()
        runCatching { cockpitPresentation?.show() }
        cockpitPresentation?.window?.decorView?.postInvalidate()
    }

    private fun openShizukuNavigatorOrFallbackMap(displayId: Int) {
        val launchedNative = CarLifeLauncherRepository.launchPreferredNavigatorOnShizuku(
            context = appContext,
            targetDisplayId = displayId
        )
        if (!launchedNative) {
            mainHandler.post {
                shizukuLeftPanelOverlay?.dismiss()
                isShizukuLauncherGridVisible = true
                CockpitUiStateController.showMap()
                runCatching { cockpitPresentation?.show() }
            }
        }
    }

    fun setAppLaunchMode(mode: AppLaunchMode) {
        appLaunchMode = mode
        CarLifeLauncherRepository.setAppLaunchMode(mode)
        if (mode == AppLaunchMode.SCREEN_MIRRORING && appMode == AppMode.MIRRORING) {
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

    private fun createMediaProjectionCallback(targetMp: MediaProjection): MediaProjection.Callback {
        return object : MediaProjection.Callback() {
            override fun onStop() {
                if (mediaProjection !== targetMp && mediaProjection != null) {
                    Log.d(TAG, "Ignoring stale MediaProjection.Callback.onStop()")
                    return
                }
                Log.w(TAG, "MediaProjection stopped by system (onStop)")
                val stopReason = "MediaProjection остановлен системой (onStop)"
                MediaProjectionTokenStore.setActiveProjection(
                    active = false,
                    detail = stopReason,
                    error = stopReason
                )
                runCatching { onMediaProjectionStopped() }
                externalScope.launch {
                    projectionMutex.withLock {
                        if (mediaProjection === targetMp) {
                            mediaProjection = null
                            mediaProjectionCallback = null
                        }
                        runCatching { virtualDisplay?.surface = null }
                        runCatching { virtualDisplay?.release() }
                        virtualDisplay = null
                        virtualDisplayWidth = 0
                        virtualDisplayHeight = 0
                        virtualDisplayDpi = 0
                        virtualDisplayIsMirrorOnly = false
                        virtualDisplayFlags = 0
                        if (appMode == AppMode.MIRRORING) {
                            dismissPresentationSafely()
                        }
                        _stats.update {
                            it.copy(
                                usingMediaProjection = false,
                                virtualDisplayId = Display.DEFAULT_DISPLAY,
                                virtualDisplayDpi = 0,
                                mediaProjectionState = "Остановлен системой (onStop)",
                                lastMediaProjectionError = stopReason,
                                statusMessage = if (appMode == AppMode.MIRRORING) {
                                    "Зеркалирование остановлено системой — требуется «Весь экран» (откат в Кокпит ЗАПРЕЩЁН)"
                                } else {
                                    stopReason
                                }
                            )
                        }
                        val surface = encoderInputSurface
                        if (isEncoding.get() && surface != null && surface.isValid) {
                            startCanvasFeederLoopIfNeeded(
                                surface = surface,
                                width = _stats.value.width,
                                height = _stats.value.height,
                                frameRate = _stats.value.fps,
                                sourceMode = videoSourceMode,
                                targetAppMode = appMode,
                                diagnosticError = stopReason
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the active [MediaProjection], or consumes the single-use [MediaProjectionTokenStore]
     * intent token once to instantiate it so `AudioPlaybackCapture` works even while video is in `CANVAS_TEST_CARD` mode.
     */
    suspend fun getOrCreateMediaProjectionForAudio(): MediaProjection? = projectionMutex.withLock {
        val currentMp = mediaProjection
        if (currentMp != null && !MediaProjectionTokenStore.hasPendingToken.value) {
            return@withLock currentMp
        }
        if (!isMediaProjectionFgsReady) {
            Log.w(TAG, "Skipping getMediaProjection(audio) until FGS FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is active")
            return@withLock currentMp
        }
        val (tokenCode, tokenData) = MediaProjectionTokenStore.consumeToken()
            ?: return@withLock currentMp

        return@withLock runCatching {
            val mp = mediaProjectionManager.getMediaProjection(tokenCode, tokenData)
            if (mp != null) {
                if (currentMp != null && currentMp !== mp) {
                    runCatching {
                        mediaProjectionCallback?.let { cb -> currentMp.unregisterCallback(cb) }
                        currentMp.stop()
                    }
                    mediaProjectionCallback = null
                }
                val callback = createMediaProjectionCallback(mp)
                mp.registerCallback(callback, mainHandler)
                mediaProjectionCallback = callback
                mediaProjection = mp
                MediaProjectionTokenStore.setActiveProjection(
                    active = true,
                    detail = "MediaProjection активен (аудио/видео)"
                )
            }
            mp
        }.onFailure { e ->
            val err = "Ошибка getMediaProjection (audio): ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, err)
            MediaProjectionTokenStore.setActiveProjection(
                active = false,
                detail = err,
                error = err
            )
            _stats.update {
                it.copy(
                    mediaProjectionState = "Ошибка",
                    lastMediaProjectionError = err
                )
            }
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

    /**
     * Stops ONLY the active video encoder, canvas feeder, presentation, and virtual display
     * while preserving any granted [MediaProjection] instance so switching between [AppMode.COCKPIT]
     * and [AppMode.MIRRORING] never invalidates the single-use Android 14+ MediaProjection token.
     */
    suspend fun stopVideoSourceForModeSwitch() = projectionMutex.withLock {
        stopVideoSourceLocked(stopMediaProjection = false, updateStatus = true)
    }

    suspend fun ensureProjectionStarted(
        width: Int = 1280,
        height: Int = 720,
        frameRate: Int = 30,
        bitRate: Int = DEFAULT_BITRATE_BPS,
        densityDpi: Int = FIXED_VIRTUAL_DISPLAY_DPI,
        sourceMode: VideoSourceMode = this.videoSourceMode,
        targetAppMode: AppMode = this.appMode
    ): Boolean {
        val safeWidth = if (width in 480..3840) width else 1280
        val safeHeight = if (height in 272..2160) height else 720
        val safeFps = if (frameRate in 15..60) frameRate else 30
        val safeBitRate = bitRate.coerceIn(400_000, 12_000_000)

        val usesMpSource = sourceMode == VideoSourceMode.SCREEN_MIRRORING ||
            sourceMode == VideoSourceMode.VIRTUAL_DISPLAY
        val alreadyActive = projectionMutex.withLock {
            val current = _stats.value
            val needsFreshMpAttach = usesMpSource &&
                !current.usingMediaProjection &&
                (MediaProjectionTokenStore.hasPendingToken.value || mediaProjection != null)
            isEncoding.get() &&
                mediaCodec != null &&
                !needsFreshMpAttach &&
                current.appMode == targetAppMode &&
                current.videoSourceMode == sourceMode &&
                current.width == safeWidth &&
                current.height == safeHeight &&
                current.fps == safeFps &&
                current.bitRateKbps == (safeBitRate / 1_000)
        }
        if (alreadyActive) return true
        val effectiveDpi = if (targetAppMode == AppMode.MIRRORING || sourceMode == VideoSourceMode.SCREEN_MIRRORING) {
            MIRROR_VIRTUAL_DISPLAY_DPI
        } else {
            FIXED_VIRTUAL_DISPLAY_DPI
        }
        return startProjection(safeWidth, safeHeight, safeFps, safeBitRate, effectiveDpi, sourceMode, targetAppMode)
    }

    suspend fun startProjection(
        width: Int = 1280,
        height: Int = 720,
        frameRate: Int = 30,
        bitRate: Int = DEFAULT_BITRATE_BPS,
        densityDpi: Int = FIXED_VIRTUAL_DISPLAY_DPI,
        sourceMode: VideoSourceMode = this.videoSourceMode,
        targetAppMode: AppMode = when (sourceMode) {
            VideoSourceMode.SCREEN_MIRRORING -> AppMode.MIRRORING
            VideoSourceMode.VIRTUAL_DISPLAY, VideoSourceMode.CANVAS_TEST_CARD ->
                if (appMode == AppMode.SHIZUKU_COCKPIT) AppMode.SHIZUKU_COCKPIT else AppMode.COCKPIT
        }
    ): Boolean = projectionMutex.withLock {
        val modeChanged = appMode != targetAppMode || videoSourceMode != sourceMode
        appMode = targetAppMode
        videoSourceMode = sourceMode

        if (modeChanged) {
            // Stop previous video source (encoder, canvas feeder, presentation, old VirtualDisplay)
            // cleanly when switching modes while keeping MediaProjection token intact.
            stopVideoSourceLocked(stopMediaProjection = false, updateStatus = false)
        } else {
            stopEncoderOnlyLocked()
        }

        val safeWidth = if (width in 480..3840) width else 1280
        val safeHeight = if (height in 272..2160) height else 720
        val safeFps = if (frameRate in 15..60) frameRate else 30
        val safeBitRate = bitRate.coerceIn(400_000, 12_000_000)
        // Use 160 dpi for VirtualDisplay Cockpit / Shizuku and 180 dpi (inside 160–200) for MediaProjection Mirroring
        // so Android SurfaceFlinger does not render excessive QHD+ pixels from S26 Ultra.
        val effectiveDpi = if (targetAppMode == AppMode.MIRRORING || sourceMode == VideoSourceMode.SCREEN_MIRRORING) {
            densityDpi.coerceIn(160, 200).let { if (it == FIXED_VIRTUAL_DISPLAY_DPI) MIRROR_VIRTUAL_DISPLAY_DPI else it }
        } else {
            FIXED_VIRTUAL_DISPLAY_DPI
        }

        if (sourceMode == VideoSourceMode.VIRTUAL_DISPLAY && CarLifeLauncherRepository.installedApps.value.isEmpty()) {
            externalScope.launch(Dispatchers.IO) {
                CarLifeLauncherRepository.loadInstalledApps(appContext)
            }
        }

        if (sourceMode == VideoSourceMode.CANVAS_TEST_CARD) {
            // Canvas mode: Strictly DO NOT create or keep a VirtualDisplay; render 30fps test card directly to MediaCodec inputSurface.
            dismissPresentationSafely()
            runCatching { virtualDisplay?.surface = null }
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            virtualDisplayWidth = 0
            virtualDisplayHeight = 0
            virtualDisplayDpi = 0
            virtualDisplayIsMirrorOnly = false
            virtualDisplayFlags = 0
        } else if (targetAppMode == AppMode.MIRRORING || sourceMode == VideoSourceMode.SCREEN_MIRRORING) {
            // Screen Mirroring mode: Strictly FORBIDDEN to create/show CockpitPresentation even on MediaProjection errors.
            dismissPresentationSafely()
            presentationRefreshJob?.cancel()
            presentationRefreshJob = null
        }

        val usesMediaProjectionSource =
            sourceMode == VideoSourceMode.SCREEN_MIRRORING || sourceMode == VideoSourceMode.VIRTUAL_DISPLAY
        val wantMirrorOnlyFlags = targetAppMode == AppMode.MIRRORING || sourceMode == VideoSourceMode.SCREEN_MIRRORING
        val targetVdFlags = when {
            wantMirrorOnlyFlags -> DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            targetAppMode == AppMode.SHIZUKU_COCKPIT ->
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            else ->
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        }

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var vDisplay: VirtualDisplay? = if (usesMediaProjectionSource) virtualDisplay else null
        var activeMp: MediaProjection? = if (usesMediaProjectionSource) mediaProjection else null
        val hasFreshPendingToken = usesMediaProjectionSource && MediaProjectionTokenStore.hasPendingToken.value
        if (hasFreshPendingToken && vDisplay != null) {
            // User explicitly re-granted a fresh MediaProjection token: release stale VD/MP so the new token takes over cleanly.
            dismissPresentationSafely()
            runCatching { vDisplay.surface = null }
            runCatching { vDisplay.release() }
            vDisplay = null
            virtualDisplay = null
            virtualDisplayWidth = 0
            virtualDisplayHeight = 0
            virtualDisplayDpi = 0
            virtualDisplayIsMirrorOnly = false
            virtualDisplayFlags = 0
        }
        var usedMediaProjection = (usesMediaProjectionSource && vDisplay != null && activeMp != null && !hasFreshPendingToken)
        var mpErrorMessage: String? = MediaProjectionTokenStore.lastError.value

        return@withLock try {
            codec = configureLowLatencyAvcEncoder(safeWidth, safeHeight, safeFps, safeBitRate)
            inputSurface = codec.createInputSurface()
            codec.start()

            mediaCodec = codec
            encoderInputSurface = inputSurface
            isEncoding.set(true)

            // Event-driven orientation tracking via DisplayListener (zero Binder IPC inside encoder drain loop)
            registerDisplayListenerIfNeeded()
            refreshPhysicalScreenOrientation(requestSyncOnChange = false)

            // Start draining MediaCodec immediately on dedicated high-priority thread
            encoderDrainJob = externalScope.launch(encoderDrainDispatcher) {
                drainHardwareEncoderLoop(codec, safeBitRate)
            }

            if (sourceMode == VideoSourceMode.CANVAS_TEST_CARD) {
                startCanvasFeederLoopIfNeeded(
                    surface = inputSurface,
                    width = safeWidth,
                    height = safeHeight,
                    frameRate = safeFps,
                    sourceMode = VideoSourceMode.CANVAS_TEST_CARD,
                    targetAppMode = targetAppMode
                )

                _stats.update {
                    it.copy(
                        isRunning = true,
                        appMode = targetAppMode,
                        videoSourceMode = VideoSourceMode.CANVAS_TEST_CARD,
                        usingMediaProjection = false,
                        virtualDisplayId = Display.DEFAULT_DISPLAY,
                        virtualDisplayDpi = 0,
                        mediaProjectionState = if (MediaProjectionTokenStore.isProjectionActive.value) "Активен (в фоне)" else "Не требуется (Canvas)",
                        lastMediaProjectionError = null,
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
                    val flagsMatch =
                        virtualDisplayIsMirrorOnly == wantMirrorOnlyFlags &&
                            virtualDisplayFlags == targetVdFlags
                    if (flagsMatch) {
                        // Android 14–16 best practice: NEVER release+recreate VirtualDisplay when only surface/size/dpi changed.
                        // Resize in-place if dimensions or DPI changed, then attach the new MediaCodec inputSurface.
                        val dimensionsChanged =
                            virtualDisplayWidth != safeWidth ||
                                virtualDisplayHeight != safeHeight ||
                                virtualDisplayDpi != effectiveDpi
                        runCatching {
                            if (dimensionsChanged) {
                                vDisplay!!.resize(safeWidth, safeHeight, effectiveDpi)
                                virtualDisplayWidth = safeWidth
                                virtualDisplayHeight = safeHeight
                                virtualDisplayDpi = effectiveDpi
                            }
                            vDisplay!!.surface = inputSurface
                        }.onFailure { e ->
                            Log.w(TAG, "VirtualDisplay resize/setSurface failed (${e.message}), recreating cleanly")
                            runCatching { vDisplay?.release() }
                            vDisplay = null
                            virtualDisplayWidth = 0
                            virtualDisplayHeight = 0
                            virtualDisplayDpi = 0
                            virtualDisplayIsMirrorOnly = false
                            virtualDisplayFlags = 0
                            usedMediaProjection = false
                        }
                    } else {
                        // Mirroring vs Presentation flags changed: release old VirtualDisplay cleanly.
                        dismissPresentationSafely()
                        runCatching { vDisplay?.surface = null }
                        runCatching { vDisplay?.release() }
                        vDisplay = null
                        virtualDisplayWidth = 0
                        virtualDisplayHeight = 0
                        virtualDisplayDpi = 0
                        virtualDisplayIsMirrorOnly = false
                        virtualDisplayFlags = 0
                        usedMediaProjection = false
                    }
                }

                // If no existing VirtualDisplay, obtain MediaProjection from a newly consumed single-use token or reuse live activeMp
                if (!usedMediaProjection) {
                    if (!isMediaProjectionFgsReady && activeMp == null && MediaProjectionTokenStore.hasPendingToken.value) {
                        // CRITICAL for Android 14–16: DO NOT consume/burn the single-use Intent token before FGS mediaProjection is active!
                        mpErrorMessage = "Ожидание FGS mediaProjection (одноразовый токен сохранён)"
                        Log.w(TAG, "Deferred MediaProjection creation: FGS FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION not yet active")
                    } else {
                        val consumedToken = if (isMediaProjectionFgsReady) {
                            MediaProjectionTokenStore.consumeToken()
                        } else {
                            null
                        }
                        if (consumedToken != null || activeMp != null) {
                            val reusedWithoutFreshToken = (consumedToken == null && activeMp != null)
                            runCatching {
                                val mp: MediaProjection? = if (consumedToken != null) {
                                    // Stop any stale MediaProjection before exchanging the fresh single-use Intent
                                    activeMp?.let { oldMp ->
                                        runCatching {
                                            mediaProjectionCallback?.let { cb -> oldMp.unregisterCallback(cb) }
                                            oldMp.stop()
                                        }
                                        mediaProjectionCallback = null
                                        mediaProjection = null
                                    }
                                    mediaProjectionManager.getMediaProjection(consumedToken.first, consumedToken.second)
                                } else {
                                    activeMp
                                }

                                if (mp != null) {
                                    if (mediaProjectionCallback == null || mp !== activeMp) {
                                        val callback = createMediaProjectionCallback(mp)
                                        mp.registerCallback(callback, mainHandler)
                                        mediaProjectionCallback = callback
                                    }
                                    activeMp = mp
                                    mediaProjection = mp
                                    MediaProjectionTokenStore.setActiveProjection(
                                        active = true,
                                        detail = "MediaProjection активен (${if (wantMirrorOnlyFlags) "Зеркало" else "VD"})"
                                    )

                                    var createdFlags = targetVdFlags
                                    vDisplay = runCatching {
                                        mp.createVirtualDisplay(
                                            VIRTUAL_DISPLAY_NAME,
                                            safeWidth,
                                            safeHeight,
                                            effectiveDpi,
                                            createdFlags,
                                            inputSurface,
                                            null,
                                            mainHandler
                                        )
                                    }.getOrElse { firstErr ->
                                        if (targetAppMode == AppMode.SHIZUKU_COCKPIT) {
                                            createdFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                                            mp.createVirtualDisplay(
                                                VIRTUAL_DISPLAY_NAME,
                                                safeWidth,
                                                safeHeight,
                                                effectiveDpi,
                                                createdFlags,
                                                inputSurface,
                                                null,
                                                mainHandler
                                            )
                                        } else {
                                            throw firstErr
                                        }
                                    }
                                    virtualDisplayWidth = safeWidth
                                    virtualDisplayHeight = safeHeight
                                    virtualDisplayDpi = effectiveDpi
                                    virtualDisplayIsMirrorOnly = wantMirrorOnlyFlags
                                    virtualDisplayFlags = createdFlags
                                    usedMediaProjection = true
                                    mpErrorMessage = null
                                } else {
                                    mpErrorMessage = "getMediaProjection() вернул null — требуется повторно выдать «Весь экран»"
                                    MediaProjectionTokenStore.setActiveProjection(
                                        active = false,
                                        detail = mpErrorMessage,
                                        error = mpErrorMessage
                                    )
                                }
                            }.onFailure { e ->
                                mpErrorMessage = if (reusedWithoutFreshToken &&
                                    (e is SecurityException || e is IllegalStateException)
                                ) {
                                    "Android 14–16 требует повторно выдать «Весь экран» после смены режима VirtualDisplay (${e.javaClass.simpleName})"
                                } else {
                                    "${e.javaClass.simpleName}: ${e.message ?: "Ошибка MediaProjection"}"
                                }
                                Log.w(TAG, "MediaProjection / VirtualDisplay failed ($mpErrorMessage)")
                                runCatching {
                                    mediaProjectionCallback?.let { cb -> activeMp?.unregisterCallback(cb) }
                                    activeMp?.stop()
                                }
                                mediaProjectionCallback = null
                                mediaProjection = null
                                activeMp = null
                                vDisplay = null
                                virtualDisplay = null
                                virtualDisplayWidth = 0
                                virtualDisplayHeight = 0
                                virtualDisplayDpi = 0
                                virtualDisplayIsMirrorOnly = false
                                virtualDisplayFlags = 0
                                MediaProjectionTokenStore.setActiveProjection(
                                    active = false,
                                    detail = mpErrorMessage,
                                    error = mpErrorMessage
                                )
                            }
                        } else {
                            mpErrorMessage = MediaProjectionTokenStore.lastError.value
                                ?: "Токен MediaProjection отсутствует — выдайте «Весь экран»"
                        }
                    }
                }

                if (usedMediaProjection && vDisplay != null) {
                    val createdDisplay = vDisplay!!.display
                    awaitDisplayRegistered(createdDisplay.displayId)

                    // Strictly attach CockpitPresentation in COCKPIT / SHIZUKU_COCKPIT mode, and NEVER in MIRRORING mode
                    val shouldSuppressPresentation =
                        targetAppMode == AppMode.MIRRORING ||
                            sourceMode == VideoSourceMode.SCREEN_MIRRORING

                    if (shouldSuppressPresentation) {
                        dismissPresentationSafely()
                    } else if (
                        cockpitPresentation == null ||
                        cockpitPresentation?.display?.displayId != createdDisplay.displayId ||
                        cockpitPresentation?.targetWidth != safeWidth ||
                        cockpitPresentation?.targetHeight != safeHeight
                    ) {
                        runCatching {
                            dismissPresentationSafely()
                            val presentation = showPresentationWithRetry(
                                display = createdDisplay,
                                targetWidth = safeWidth,
                                targetHeight = safeHeight
                            )
                            withContext(Dispatchers.Main.immediate) {
                                shizukuLeftPanelOverlay?.dismiss()
                                isShizukuLauncherGridVisible = true
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

                    val (livePhoneW, livePhoneH) = CarLifeAccessibilityService.instance?.getPhysicalScreenSize()
                        ?: CarLifeAccessibilityService.computePhysicalScreenSize(appContext)

                    _stats.update {
                        it.copy(
                            isRunning = true,
                            appMode = targetAppMode,
                            videoSourceMode = sourceMode,
                            usingMediaProjection = true,
                            isMediaProjectionFgsActive = isMediaProjectionFgsReady,
                            virtualDisplayId = createdDisplay.displayId,
                            virtualDisplayDpi = effectiveDpi,
                            mediaProjectionState = "Активен (Весь экран)",
                            lastMediaProjectionError = null,
                            width = safeWidth,
                            height = safeHeight,
                            fps = safeFps,
                            activeEncoderProfile = lastConfiguredEncoderProfile,
                            phoneScreenWidth = livePhoneW,
                            phoneScreenHeight = livePhoneH,
                            isPhoneLandscape = livePhoneW > livePhoneH,
                            bitRateMbps = (safeBitRate / 1_000_000).coerceAtLeast(1),
                            bitRateKbps = safeBitRate / 1_000,
                            activeCockpitWidget = when {
                                wantMirrorOnlyFlags ->
                                    "Зеркалирование экрана активно ($safeWidth×$safeHeight @ ${safeFps}fps, ${effectiveDpi}dpi)"
                                targetAppMode == AppMode.SHIZUKU_COCKPIT ->
                                    "Shizuku Второй экран на VD #${createdDisplay.displayId} ($safeWidth×$safeHeight @ ${effectiveDpi}dpi)"
                                else ->
                                    "Кокпит активен на VD #${createdDisplay.displayId} ($safeWidth×$safeHeight)"
                            },
                            statusMessage = when {
                                wantMirrorOnlyFlags ->
                                    "Зеркалирование экрана (MediaProjection): $safeWidth×$safeHeight @ ${safeBitRate / 1000} кбит/с, ${safeFps}fps, ${effectiveDpi}dpi"
                                targetAppMode == AppMode.SHIZUKU_COCKPIT ->
                                    "Кокпит + Shizuku (VD #${createdDisplay.displayId}): $safeWidth×$safeHeight @ ${safeBitRate / 1000} кбит/с, ${safeFps}fps, ${effectiveDpi}dpi"
                                else ->
                                    "Кокпит (VD #${createdDisplay.displayId}): $safeWidth×$safeHeight @ ${safeBitRate / 1000} кбит/с, ${effectiveDpi}dpi"
                            }
                        )
                    }

                    // Watchdog for COCKPIT VirtualDisplay Presentation mode ONLY: never detach live Screen Mirroring VirtualDisplay!
                    if (!wantMirrorOnlyFlags && sourceMode == VideoSourceMode.VIRTUAL_DISPLAY) {
                        externalScope.launch(Dispatchers.IO) {
                            delay(350L)
                            if (isEncoding.get() &&
                                appMode == AppMode.COCKPIT &&
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
                                    sourceMode = VideoSourceMode.VIRTUAL_DISPLAY,
                                    targetAppMode = targetAppMode
                                )
                            }
                        }
                    }
                } else {
                    // MediaProjection missing or failed:
                    // STRICT NO-FALLBACK RULE: When MIRRORING is selected, NEVER show CockpitPresentation or drawCarLifeLauncherFrame!
                    // Instead keep appMode = MIRRORING and feed a dedicated Mirroring standby/error diagnostic frame so USB 0x0002 stays alive.
                    if (targetAppMode == AppMode.MIRRORING) {
                        dismissPresentationSafely()
                    }
                    startCanvasFeederLoopIfNeeded(
                        surface = inputSurface,
                        width = safeWidth,
                        height = safeHeight,
                        frameRate = safeFps,
                        sourceMode = sourceMode,
                        targetAppMode = targetAppMode,
                        diagnosticError = mpErrorMessage
                    )
                    val mpStateLabel = if (MediaProjectionTokenStore.lastError.value != null ||
                        (mpErrorMessage != null && !mpErrorMessage!!.startsWith("Токен MediaProjection отсутствует"))
                    ) {
                        "Ошибка MediaProjection"
                    } else {
                        "Ожидает разрешения («Весь экран»)"
                    }
                    _stats.update {
                        it.copy(
                            isRunning = true,
                            appMode = targetAppMode,
                            videoSourceMode = sourceMode,
                            usingMediaProjection = false,
                            virtualDisplayId = Display.DEFAULT_DISPLAY,
                            virtualDisplayDpi = 0,
                            mediaProjectionState = mpStateLabel,
                            lastMediaProjectionError = mpErrorMessage,
                            width = safeWidth,
                            height = safeHeight,
                            fps = safeFps,
                            bitRateMbps = (safeBitRate / 1_000_000).coerceAtLeast(1),
                            bitRateKbps = safeBitRate / 1_000,
                            activeCockpitWidget = if (targetAppMode == AppMode.MIRRORING) {
                                mpErrorMessage ?: "Ожидание MediaProjection («Весь экран») — откат в Кокпит отключён"
                            } else {
                                "Ожидание MediaProjection («Весь экран»)"
                            },
                            statusMessage = if (targetAppMode == AppMode.MIRRORING) {
                                "Зеркалирование: ${mpErrorMessage ?: "ожидание разрешения «Весь экран»"} (откат в Кокпит запрещён)"
                            } else {
                                "Ожидание разрешения MediaProjection («Весь экран») — резервный H.264 поток активен"
                            }
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
                    appMode = targetAppMode,
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
        sourceMode: VideoSourceMode,
        targetAppMode: AppMode = this.appMode,
        diagnosticError: String? = null
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
                            when {
                                sourceMode == VideoSourceMode.CANVAS_TEST_CARD -> {
                                    drawTestCardFrame(
                                        canvas = canvas,
                                        width = width,
                                        height = height,
                                        frameSeq = currentSeq,
                                        timeWithMsString = testTimeFmt.format(Date()),
                                        paint = testCardPaint
                                    )
                                }
                                targetAppMode == AppMode.MIRRORING || sourceMode == VideoSourceMode.SCREEN_MIRRORING -> {
                                    // Strict No-Fallback rule: never render Cockpit Launcher UI in MIRRORING mode
                                    drawMirroringWaitingOrErrorFrame(
                                        canvas = canvas,
                                        width = width,
                                        height = height,
                                        frameSeq = currentSeq,
                                        timeString = testTimeFmt.format(Date()),
                                        diagnosticError = diagnosticError ?: _stats.value.lastMediaProjectionError,
                                        paint = testCardPaint
                                    )
                                }
                                else -> {
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
     * Dedicated diagnostic frame rendered ONLY when [AppMode.MIRRORING] is selected and
     * `MediaProjection` has not yet been granted or threw an error, so the HU never receives
     * Cockpit Launcher UI by mistake and H.264 keep-alive stays active.
     */
    fun drawMirroringWaitingOrErrorFrame(
        canvas: AndroidCanvas,
        width: Int,
        height: Int,
        frameSeq: Long,
        timeString: String,
        diagnosticError: String?,
        paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    ) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val scale = (h / 720f).coerceIn(0.6f, 1.4f)

        paint.style = Paint.Style.FILL
        paint.setShadowLayer(0f, 0f, 0f, AndroidColor.TRANSPARENT)
        paint.color = AndroidColor.parseColor("#0B0E14")
        canvas.drawRect(0f, 0f, w, h, paint)

        val cardRect = RectF(w * 0.08f, h * 0.14f, w * 0.92f, h * 0.86f)
        paint.color = AndroidColor.parseColor("#151A24")
        canvas.drawRoundRect(cardRect, 24f * scale, 24f * scale, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * scale
        paint.color = if (diagnosticError != null) AndroidColor.parseColor("#F59E0B") else AndroidColor.parseColor("#00B4D8")
        canvas.drawRoundRect(cardRect, 24f * scale, 24f * scale, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = AndroidColor.parseColor("#00B4D8")
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 36f * scale
        canvas.drawText(
            "РЕЖИМ ЗЕРКАЛИРОВАНИЯ ЭКРАНА",
            w * 0.5f,
            h * 0.34f,
            paint
        )

        paint.color = if (diagnosticError != null) AndroidColor.parseColor("#F59E0B") else AndroidColor.WHITE
        paint.textSize = 26f * scale
        val mainMsg = if (diagnosticError != null) {
            "MediaProjection: ${diagnosticError.take(64)}"
        } else {
            "Подтвердите захват «Весь экран» на телефоне"
        }
        canvas.drawText(mainMsg, w * 0.5f, h * 0.48f, paint)

        paint.color = AndroidColor.parseColor("#94A3B8")
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 20f * scale
        canvas.drawText(
            "Откат в Кокпит отключён • $timeString • Кадр #$frameSeq",
            w * 0.5f,
            h * 0.62f,
            paint
        )

        val barWidth = (cardRect.width() - 80f * scale).coerceAtLeast(40f)
        val progress = ((frameSeq % 30L) / 30f) * barWidth
        paint.color = AndroidColor.parseColor("#00B4D8")
        canvas.drawRect(
            cardRect.left + 40f * scale,
            cardRect.bottom - 48f * scale,
            cardRect.left + 40f * scale + progress.coerceAtLeast(16f),
            cardRect.bottom - 40f * scale,
            paint
        )
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
            "JETTA Connect • Приложения",
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
     * Rate-limited recovery IDR request invoked when [CarLifeUsbWriteQueue] drops stale P-frames
     * under USB congestion so the Jetta VS7 decoder heals immediately without IDR storms.
     */
    fun requestRecoveryIdrOnCongestion() {
        if (!isVideoChannelReadyForStreaming || !isEncoding.get()) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastCongestionIdrRequestMs.get()
        if (now - prev < CONGESTION_IDR_COOLDOWN_MS) return
        if (lastCongestionIdrRequestMs.compareAndSet(prev, now)) {
            requestEncoderSyncFrameOnly()
        }
    }

    /**
     * Updates the observed dropped P-frame counter from USB transport for live diagnostics.
     */
    fun updateDroppedVideoPFrames(droppedCount: Long) {
        if (_stats.value.droppedVideoPFrames != droppedCount) {
            _stats.update { it.copy(droppedVideoPFrames = droppedCount) }
        }
    }

    /**
     * Notifies the projection pipeline when the physical phone display turns off or back on:
     * - On screen OFF: NEVER stops `MediaProjection` or `MediaCodec`; keeps CarLife `0x0002` alive.
     * - On screen ON: immediately requests a crisp IDR keyframe so the Head Unit resumes live video without delay.
     */
    fun onPhoneScreenStateChanged(isScreenOff: Boolean) {
        isPhoneScreenOffFlag = isScreenOff
        _stats.update { it.copy(isPhoneScreenOff = isScreenOff) }
        if (!isScreenOff && isVideoChannelReadyForStreaming && isEncoding.get()) {
            requestInstantIdrKeyFrame()
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
     * Isolated channel `0x0006` (`MSG_TOUCH_ACTION`) routing:
     * - **[AppMode.MIRRORING]:** dispatches **STRICTLY** through [CarLifeAccessibilityService] (`dispatchGesture`),
     *   mapping Head Unit `(1280×720)` coordinates into real smartphone screen bounds (`WindowManager.currentWindowMetrics.bounds`).
     * - **[AppMode.SHIZUKU_COCKPIT]:** scales `(1280×720)`, preserves `lastDownTime` from `ACTION_DOWN` to `ACTION_UP`,
     *   and forwards directly to [CarLifeShizukuManager.injectTouch] (`ICarLifeShizukuService`) with `virtualDisplayId`.
     * - **[AppMode.COCKPIT]:** dispatches **STRICTLY** into `presentation.window.decorView.dispatchTouchEvent(event)`
     *   with `lastDownTime` preserved from `ACTION_DOWN` to `ACTION_UP` and `1280×720` scaling. NEVER invokes
     *   [CarLifeAccessibilityService] in Cockpit mode.
     */
    suspend fun injectTouchPacket(packet: CarLifeTouchPacket): Boolean {
        val currentStats = _stats.value
        val touchCfg = TouchSettingsPreferences.config.value
        val huTouchMaxX = packet.huWidth.takeIf { it > 0 }?.toFloat()
            ?: CarLifeTouchDecoder.DEFAULT_HU_TOUCH_MAX_X
        val huTouchMaxY = packet.huHeight.takeIf { it > 0 }?.toFloat()
            ?: CarLifeTouchDecoder.DEFAULT_HU_TOUCH_MAX_Y

        return withContext(Dispatchers.Main.immediate) {
            if (appMode == AppMode.MIRRORING) {
                val a11yService = CarLifeAccessibilityService.instance
                val cachedSize = lastKnownScreenSize
                val (screenW, screenH) = if (cachedSize != null && packet.androidAction != MotionEvent.ACTION_DOWN) {
                    cachedSize
                } else {
                    (a11yService?.getPhysicalScreenSize()
                        ?: CarLifeAccessibilityService.computePhysicalScreenSize(appContext)).also {
                        lastKnownScreenSize = it
                    }
                }
                val mapped = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
                    rawX = packet.rawX,
                    rawY = packet.rawY,
                    huWidth = huTouchMaxX,
                    huHeight = huTouchMaxY,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    applyEdgeGuard = touchCfg.edgeBackGuardEnabled
                )
                val orientLabel = if (mapped.isLandscape) "Landscape" else "Portrait"

                if (!mapped.isInsideActiveArea) {
                    if (packet.androidAction != MotionEvent.ACTION_MOVE) {
                        val skippedLabel =
                            "Тач в чёрном поле HU(${packet.rawX},${packet.rawY}) пропущен ($screenW×$screenH $orientLabel)"
                        _stats.update {
                            it.copy(
                                phoneScreenWidth = screenW,
                                phoneScreenHeight = screenH,
                                isPhoneLandscape = mapped.isLandscape,
                                lastTouchDiagnostic = skippedLabel
                            )
                        }
                    }
                    return@withContext false
                }

                val a11yDispatched = a11yService?.handleMotionStream(
                    displayId = Display.DEFAULT_DISPLAY,
                    action = packet.androidAction,
                    x = mapped.x,
                    y = mapped.y,
                    synthesizeClickUp = packet.synthesizeClickUp,
                    isDoubleClick = packet.isDoubleClick,
                    isLongPress = packet.isLongPress
                ) ?: false

                val nowMs = SystemClock.elapsedRealtime()
                val shouldPublishStats = packet.androidAction != MotionEvent.ACTION_MOVE ||
                    a11yService == null ||
                    (nowMs - lastMirrorTouchStatsUpdateMs) >= 250L
                if (shouldPublishStats) {
                    lastMirrorTouchStatsUpdateMs = nowMs
                    val gestureTag = when {
                        packet.isDoubleClick -> "2xTAP"
                        packet.isLongPress -> "LONG"
                        else -> "TOUCH"
                    }
                    val statusLabel = if (a11yService != null) {
                        "Mirror $gestureTag HU(${packet.rawX},${packet.rawY}) -> (${mapped.x.toInt()},${mapped.y.toInt()}) / ${screenW}×${screenH} $orientLabel"
                    } else {
                        "Touch 0x0006 получен, но CarLifeAccessibilityService ВЫКЛ!"
                    }

                    _stats.update {
                        it.copy(
                            injectedTouches = it.injectedTouches + 1L,
                            lastTouchPoint = mapped.x to mapped.y,
                            phoneScreenWidth = screenW,
                            phoneScreenHeight = screenH,
                            isPhoneLandscape = mapped.isLandscape,
                            lastTouchDiagnostic = statusLabel,
                            activeCockpitWidget = statusLabel,
                            statusMessage = statusLabel
                        )
                    }
                }
                return@withContext a11yDispatched
            }

            if (appMode == AppMode.SHIZUKU_COCKPIT) {
                val vDisplayId = virtualDisplay?.display?.displayId ?: currentStats.virtualDisplayId
                val targetWidth = currentStats.width.coerceAtLeast(1)
                val targetHeight = currentStats.height.coerceAtLeast(1)
                val (scaledX, scaledY) = CarLifeTouchDecoder.scaleTouchCoordinates(
                    rawX = packet.rawX,
                    rawY = packet.rawY,
                    currentSurfaceWidth = targetWidth,
                    currentSurfaceHeight = targetHeight,
                    huTouchMaxX = huTouchMaxX,
                    huTouchMaxY = huTouchMaxY
                )

                // Suppress sub-deadzone coordinate micro-jitter on ACTION_MOVE
                if (packet.androidAction == MotionEvent.ACTION_MOVE && !lastShizukuMoveX.isNaN()) {
                    val dx = scaledX - lastShizukuMoveX
                    val dy = scaledY - lastShizukuMoveY
                    if (dx * dx + dy * dy < touchCfg.moveDeadzonePxSq) {
                        return@withContext true
                    }
                }
                if (packet.androidAction == MotionEvent.ACTION_DOWN || packet.androidAction == MotionEvent.ACTION_MOVE) {
                    lastShizukuMoveX = scaledX
                    lastShizukuMoveY = scaledY
                } else if (packet.androidAction == MotionEvent.ACTION_UP || packet.androidAction == MotionEvent.ACTION_CANCEL) {
                    lastShizukuMoveX = Float.NaN
                    lastShizukuMoveY = Float.NaN
                }

                val nowUptime = SystemClock.uptimeMillis()
                val downTime = when (packet.androidAction) {
                    MotionEvent.ACTION_DOWN -> {
                        lastDownTime.set(nowUptime)
                        shizukuTouchDownInLeftPanel = scaledX < VW_LEFT_PANEL_WIDTH_PX.toFloat()
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
                val routeToLeftPanel = shizukuTouchDownInLeftPanel ||
                    (packet.androidAction == MotionEvent.ACTION_DOWN && scaledX < VW_LEFT_PANEL_WIDTH_PX.toFloat())
                if (packet.androidAction == MotionEvent.ACTION_UP || packet.androidAction == MotionEvent.ACTION_CANCEL) {
                    shizukuTouchDownInLeftPanel = false
                }

                // Smart touch routing for SHIZUKU_COCKPIT:
                // 1) x < 250 -> Left VW Panel (Overlay when Navigator/2GIS is open, or Presentation when Launcher Grid is open)
                // 2) x >= 250 -> Presentation decorView if Launcher Grid is active, otherwise Shizuku injectTouch -> VirtualDisplay
                val leftPanelTargetView = if (routeToLeftPanel) {
                    shizukuLeftPanelViewForTesting
                        ?: shizukuLeftPanelOverlay?.rootView
                        ?: cockpitPresentation?.window?.decorView
                } else {
                    null
                }
                val launcherRightTargetView = if (!routeToLeftPanel &&
                    isShizukuLauncherGridVisible &&
                    cockpitPresentation?.isShowing == true
                ) {
                    cockpitPresentation?.window?.decorView
                } else {
                    null
                }

                val directPanelView = leftPanelTargetView ?: launcherRightTargetView
                if (directPanelView != null) {
                    val dispatched = dispatchSynthesizedMotionEvent(
                        decorView = directPanelView,
                        downTime = downTime,
                        eventTime = eventTime,
                        action = packet.androidAction,
                        x = scaledX,
                        y = scaledY
                    )
                    if (packet.synthesizeClickUp) {
                        val clickDur = touchCfg.tapDurationMs.coerceIn(16L, 120L)
                        val upTime = maxOf(downTime + clickDur, SystemClock.uptimeMillis())
                        dispatchSynthesizedMotionEvent(
                            decorView = directPanelView,
                            downTime = downTime,
                            eventTime = upTime,
                            action = MotionEvent.ACTION_UP,
                            x = scaledX,
                            y = scaledY
                        )
                    }
                    val zoneLabel = if (routeToLeftPanel) {
                        "VW Left Panel (${scaledX.toInt()}, ${scaledY.toInt()})"
                    } else {
                        "Shizuku Launcher Grid (${scaledX.toInt()}, ${scaledY.toInt()})"
                    }
                    _stats.update {
                        it.copy(
                            injectedTouches = it.injectedTouches + 1L,
                            lastTouchPoint = scaledX to scaledY,
                            activeCockpitWidget = zoneLabel,
                            statusMessage = zoneLabel
                        )
                    }
                    return@withContext dispatched
                }

                val shizukuDispatched = CarLifeShizukuManager.injectTouch(
                    action = packet.androidAction,
                    x = scaledX,
                    y = scaledY,
                    downTime = downTime,
                    eventTime = eventTime,
                    displayId = vDisplayId
                )
                if (shizukuDispatched && packet.synthesizeClickUp) {
                    val clickDur = touchCfg.tapDurationMs.coerceIn(16L, 120L)
                    val upTime = maxOf(downTime + clickDur, SystemClock.uptimeMillis())
                    CarLifeShizukuManager.injectTouch(
                        action = MotionEvent.ACTION_UP,
                        x = scaledX,
                        y = scaledY,
                        downTime = downTime,
                        eventTime = upTime,
                        displayId = vDisplayId
                    )
                }

                val statusLabel = if (shizukuDispatched) {
                    "Shizuku Touch (${scaledX.toInt()}, ${scaledY.toInt()}) -> VD #$vDisplayId"
                } else {
                    "Touch 0x0006 получен, но Shizuku UserService (${CarLifeShizukuManager.status.value.statusLabel})"
                }

                _stats.update {
                    it.copy(
                        injectedTouches = it.injectedTouches + 1L,
                        lastTouchPoint = scaledX to scaledY,
                        activeCockpitWidget = statusLabel,
                        statusMessage = statusLabel
                    )
                }
                return@withContext shizukuDispatched
            }

            // AppMode.COCKPIT: Strictly dispatch to presentation.window.decorView.dispatchTouchEvent(event)
            // preserving lastDownTime from ACTION_DOWN to ACTION_UP. Never invoke CarLifeAccessibilityService.
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

            // Suppress sub-deadzone coordinate micro-jitter on ACTION_MOVE
            if (packet.androidAction == MotionEvent.ACTION_MOVE && !lastCockpitMoveX.isNaN()) {
                val dx = scaledX - lastCockpitMoveX
                val dy = scaledY - lastCockpitMoveY
                if (dx * dx + dy * dy < touchCfg.moveDeadzonePxSq) {
                    return@withContext true
                }
            }
            if (packet.androidAction == MotionEvent.ACTION_DOWN || packet.androidAction == MotionEvent.ACTION_MOVE) {
                lastCockpitMoveX = scaledX
                lastCockpitMoveY = scaledY
            } else if (packet.androidAction == MotionEvent.ACTION_UP || packet.androidAction == MotionEvent.ACTION_CANCEL) {
                lastCockpitMoveX = Float.NaN
                lastCockpitMoveY = Float.NaN
            }

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
                    val clickDur = touchCfg.tapDurationMs.coerceIn(16L, 120L)
                    val upTime = maxOf(downTime + clickDur, SystemClock.uptimeMillis())
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
                // Canvas Test Card hit-test for app launch and media controls
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
                    statusMessage = "Cockpit Touch -> $zoneHit"
                )
            }
            viewDispatched
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
                runCatching { cockpitPresentation?.show() }
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
        val code = packet.carLifeKeyCode
        val androidKey = packet.androidKeyCode
        val swapTracks = TouchSettingsPreferences.steeringWheelConfig.value.swapTrackButtons
        val resolvedAction = CarLifeHardKey.resolveAction(code, swapTrackButtons = swapTracks).let { action ->
            if (action != SteeringWheelAction.UNASSIGNED) {
                action
            } else {
                when (androidKey) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                        if (swapTracks) SteeringWheelAction.NEXT_TRACK else SteeringWheelAction.PREVIOUS_TRACK
                    KeyEvent.KEYCODE_MEDIA_NEXT ->
                        if (swapTracks) SteeringWheelAction.PREVIOUS_TRACK else SteeringWheelAction.NEXT_TRACK
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> SteeringWheelAction.PLAY_PAUSE
                    KeyEvent.KEYCODE_VOICE_ASSIST -> SteeringWheelAction.VOICE_ASSISTANT
                    KeyEvent.KEYCODE_NAVIGATE_IN -> SteeringWheelAction.NAVIGATION
                    KeyEvent.KEYCODE_HOME -> SteeringWheelAction.HOME
                    KeyEvent.KEYCODE_BACK -> SteeringWheelAction.BACK
                    else -> SteeringWheelAction.UNASSIGNED
                }
            }
        }

        return withContext(Dispatchers.Main.immediate) {
            // 1. Media & Tracks (works across ALL 3 modes via MediaPlayerController + AudioManager fallback)
            when (resolvedAction) {
                SteeringWheelAction.PREVIOUS_TRACK -> {
                    MediaPlayerController.previous()
                    _stats.update {
                        it.copy(statusMessage = "HardKey PREV (code=$code, swap=$swapTracks) -> MediaPlayerController.previous()")
                    }
                    return@withContext true
                }

                SteeringWheelAction.NEXT_TRACK -> {
                    MediaPlayerController.next()
                    _stats.update {
                        it.copy(statusMessage = "HardKey NEXT (code=$code, swap=$swapTracks) -> MediaPlayerController.next()")
                    }
                    return@withContext true
                }

                SteeringWheelAction.PLAY_PAUSE -> {
                    MediaPlayerController.playPause()
                    _stats.update {
                        it.copy(statusMessage = "HardKey PLAY_PAUSE (code=$code) -> MediaPlayerController.playPause()")
                    }
                    return@withContext true
                }

                // 2. Voice Assistant (33 / 0x21 KEYCODE_VR_START)
                SteeringWheelAction.VOICE_ASSISTANT -> {
                    val launched = runCatching {
                        val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appContext.startActivity(voiceIntent)
                        true
                    }.getOrDefault(false)
                    _stats.update {
                        it.copy(
                            statusMessage = if (launched) {
                                "HardKey VR_START (code=$code) -> Voice Assistant"
                            } else {
                                "HardKey VR_START (code=$code) -> Voice Assistant не найден"
                            }
                        )
                    }
                    return@withContext launched
                }

                // 3. NAVI (13 / 0x0D)
                SteeringWheelAction.NAVIGATION -> {
                    val handled = when (appMode) {
                        AppMode.COCKPIT -> {
                            CockpitUiStateController.showMap()
                            runCatching { cockpitPresentation?.show() }
                            cockpitPresentation?.window?.decorView?.postInvalidate()
                            true
                        }
                        AppMode.SHIZUKU_COCKPIT -> {
                            val vDisplayId = virtualDisplay?.display?.displayId ?: _stats.value.virtualDisplayId
                            openShizukuNavigatorOrFallbackMap(vDisplayId)
                            true
                        }
                        AppMode.MIRRORING -> {
                            CarLifeLauncherRepository.launchPreferredNavigatorOnDefaultDisplay(appContext)
                        }
                    }
                    _stats.update {
                        it.copy(statusMessage = "HardKey NAVI (code=$code) -> ${appMode.title}")
                    }
                    return@withContext handled
                }

                // 4. HOME (1 / 0x01)
                SteeringWheelAction.HOME -> {
                    return@withContext when (appMode) {
                        AppMode.MIRRORING -> {
                            val ok = CarLifeAccessibilityService.instance?.performHomeAction() == true
                            _stats.update {
                                it.copy(statusMessage = "Mirror HardKey -> HOME (KeyCode=${KeyEvent.KEYCODE_HOME})")
                            }
                            ok
                        }
                        AppMode.SHIZUKU_COCKPIT -> {
                            val vDisplayId = virtualDisplay?.display?.displayId ?: _stats.value.virtualDisplayId
                            showShizukuLauncherGrid()
                            val sent = CarLifeShizukuManager.sendKeyEvent(KeyEvent.KEYCODE_HOME, vDisplayId)
                            _stats.update {
                                it.copy(statusMessage = "Shizuku HardKey -> HOME KeyCode=${KeyEvent.KEYCODE_HOME} (VD #$vDisplayId)")
                            }
                            sent || isShizukuLauncherGridVisible
                        }
                        AppMode.COCKPIT -> {
                            CockpitUiStateController.showGrid()
                            runCatching { cockpitPresentation?.show() }
                            cockpitPresentation?.window?.decorView?.postInvalidate()
                            _stats.update {
                                it.copy(statusMessage = "Cockpit HardKey -> HOME (сетка приложений)")
                            }
                            true
                        }
                    }
                }

                // 5. BACK (14 / 0x0E)
                SteeringWheelAction.BACK -> {
                    return@withContext when (appMode) {
                        AppMode.MIRRORING -> {
                            val ok = CarLifeAccessibilityService.instance?.performBackAction() == true
                            _stats.update {
                                it.copy(statusMessage = "Mirror HardKey -> BACK (GLOBAL_ACTION_BACK)")
                            }
                            ok
                        }
                        AppMode.SHIZUKU_COCKPIT -> {
                            val vDisplayId = virtualDisplay?.display?.displayId ?: _stats.value.virtualDisplayId
                            val sent = CarLifeShizukuManager.sendKeyEvent(KeyEvent.KEYCODE_BACK, vDisplayId)
                            _stats.update {
                                it.copy(statusMessage = "Shizuku HardKey -> BACK KeyCode=${KeyEvent.KEYCODE_BACK} (VD #$vDisplayId)")
                            }
                            sent
                        }
                        AppMode.COCKPIT -> {
                            val handledInCockpit = CockpitUiStateController.handleCockpitBack()
                            val decorHandled = if (!handledInCockpit) {
                                val decorView = cockpitPresentation?.window?.decorView
                                if (decorView != null) {
                                    val now = SystemClock.uptimeMillis()
                                    val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
                                    val upEvent = KeyEvent(now, now + 15L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
                                    decorView.dispatchKeyEvent(downEvent) || decorView.dispatchKeyEvent(upEvent)
                                } else {
                                    false
                                }
                            } else {
                                true
                            }
                            _stats.update {
                                it.copy(statusMessage = "Cockpit HardKey -> BACK")
                            }
                            decorHandled
                        }
                    }
                }

                SteeringWheelAction.UNASSIGNED -> Unit
            }

            if (androidKey == KeyEvent.KEYCODE_UNKNOWN) {
                return@withContext false
            }

            if (appMode == AppMode.MIRRORING) {
                val a11yHandled = CarLifeAccessibilityService.instance
                    ?.handleSystemHardKey(androidKey) ?: false
                _stats.update {
                    it.copy(statusMessage = "Mirror HardKey -> KeyCode=$androidKey")
                }
                return@withContext a11yHandled
            }
            if (appMode == AppMode.SHIZUKU_COCKPIT) {
                val vDisplayId = virtualDisplay?.display?.displayId ?: _stats.value.virtualDisplayId
                val sent = CarLifeShizukuManager.sendKeyEvent(androidKey, vDisplayId)
                _stats.update {
                    it.copy(statusMessage = "Shizuku HardKey -> KeyCode=$androidKey (VD #$vDisplayId)")
                }
                return@withContext sent
            }
            val decorView = cockpitPresentation?.window?.decorView ?: return@withContext false
            val now = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, androidKey, 0)
            val upEvent = KeyEvent(now, now + 15L, KeyEvent.ACTION_UP, androidKey, 0)
            val downHandled = decorView.dispatchKeyEvent(downEvent)
            val upHandled = decorView.dispatchKeyEvent(upEvent)
            _stats.update {
                it.copy(
                    statusMessage = "Cockpit HardKey -> KeyCode=$androidKey"
                )
            }
            downHandled || upHandled
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

    private fun dismissPresentationSafely() {
        val existingOverlay = shizukuLeftPanelOverlay
        shizukuLeftPanelOverlay = null
        isShizukuLauncherGridVisible = true
        val existing = cockpitPresentation
        cockpitPresentation = null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { existingOverlay?.dismiss() }
            runCatching { existing?.dismiss() }
        } else {
            mainHandler.post {
                runCatching { existingOverlay?.dismiss() }
                runCatching { existing?.dismiss() }
            }
        }
    }

    private suspend fun stopVideoSourceLocked(
        stopMediaProjection: Boolean,
        updateStatus: Boolean
    ) {
        stopEncoderOnlyLocked()

        dismissPresentationSafely()

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        virtualDisplayWidth = 0
        virtualDisplayHeight = 0
        virtualDisplayDpi = 0
        virtualDisplayIsMirrorOnly = false
        virtualDisplayFlags = 0

        if (stopMediaProjection) {
            runCatching {
                mediaProjectionCallback?.let { cb ->
                    mediaProjection?.unregisterCallback(cb)
                }
                mediaProjection?.stop()
            }
            mediaProjectionCallback = null
            mediaProjection = null
            MediaProjectionTokenStore.setActiveProjection(
                active = false,
                detail = "Проекция остановлена"
            )
        }

        if (updateStatus) {
            _stats.update {
                it.copy(
                    isRunning = false,
                    usingMediaProjection = false,
                    virtualDisplayId = Display.DEFAULT_DISPLAY,
                    virtualDisplayDpi = 0,
                    mediaProjectionState = if (stopMediaProjection) "Остановлена" else it.mediaProjectionState,
                    spsPpsConfigured = false,
                    statusMessage = "Projection Stopped"
                )
            }
        }
    }

    private suspend fun stopProjectionLocked(updateStatus: Boolean = true) {
        unregisterDisplayListenerIfNeeded()
        stopVideoSourceLocked(stopMediaProjection = true, updateStatus = updateStatus)
    }

    fun shutdown() {
        unregisterDisplayListenerIfNeeded()
        runCatching { encoderDrainDispatcher.close() }
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
        val supportsBaselineLevel3 = caps?.profileLevels?.any {
            it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline &&
                it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel3
        } ?: true
        val supportsBaselineLevel31 = caps?.profileLevels?.any {
            it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline &&
                it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel31
        } ?: true
        val supportsVbr = caps?.encoderCapabilities?.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        ) ?: true

        val candidates = buildList {
            if (supportsBaseline && supportsBaselineLevel3 && supportsVbr) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(
                        "AVCProfileBaseline+Level3+VBR+LowLatency+SamsungQP" to buildPreferredAvcMediaFormat(
                            width = width,
                            height = height,
                            frameRate = frameRate,
                            bitRate = bitRate,
                            includeBaselineProfile = true,
                            targetLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel3,
                            includeVbr = true,
                            includeVendorTimingKeys = true,
                            includeSamsungQpMax = true,
                            includeLowLatency = true
                        )
                    )
                }
                add(
                    "AVCProfileBaseline+Level3+VBR+SamsungQP" to buildPreferredAvcMediaFormat(
                        width = width,
                        height = height,
                        frameRate = frameRate,
                        bitRate = bitRate,
                        includeBaselineProfile = true,
                        targetLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel3,
                        includeVbr = true,
                        includeVendorTimingKeys = true,
                        includeSamsungQpMax = true
                    )
                )
                add(
                    "AVCProfileBaseline+Level3+VBR" to buildPreferredAvcMediaFormat(
                        width = width,
                        height = height,
                        frameRate = frameRate,
                        bitRate = bitRate,
                        includeBaselineProfile = true,
                        targetLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel3,
                        includeVbr = true,
                        includeVendorTimingKeys = true,
                        includeSamsungQpMax = false
                    )
                )
            }
            if (supportsBaseline && supportsBaselineLevel31 && supportsVbr) {
                add(
                    "AVCProfileBaseline+Level31+VBR" to buildPreferredAvcMediaFormat(
                        width = width,
                        height = height,
                        frameRate = frameRate,
                        bitRate = bitRate,
                        includeBaselineProfile = true,
                        targetLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                        includeVbr = true,
                        includeVendorTimingKeys = true,
                        includeSamsungQpMax = true
                    )
                )
            }
            if (supportsBaseline && supportsVbr) {
                add(
                    "AVCProfileBaseline+VBR" to buildPreferredAvcMediaFormat(
                        width = width,
                        height = height,
                        frameRate = frameRate,
                        bitRate = bitRate,
                        includeBaselineProfile = true,
                        targetLevel = null,
                        includeVbr = true,
                        includeVendorTimingKeys = true,
                        includeSamsungQpMax = false
                    )
                )
            }
            if (supportsVbr) {
                add(
                    "GenericAVC+VBR" to buildPreferredAvcMediaFormat(
                        width = width,
                        height = height,
                        frameRate = frameRate,
                        bitRate = bitRate,
                        includeBaselineProfile = false,
                        targetLevel = null,
                        includeVbr = true,
                        includeVendorTimingKeys = true,
                        includeSamsungQpMax = false
                    )
                )
            }
            add(
                "GenericAVC+DefaultBitrateMode" to buildPreferredAvcMediaFormat(
                    width = width,
                    height = height,
                    frameRate = frameRate,
                    bitRate = bitRate,
                    includeBaselineProfile = false,
                    targetLevel = null,
                    includeVbr = false,
                    includeVendorTimingKeys = true,
                    includeSamsungQpMax = false
                )
            )
            add(
                "MinimalAVC+30fpsCap" to buildPreferredAvcMediaFormat(
                    width = width,
                    height = height,
                    frameRate = frameRate,
                    bitRate = bitRate,
                    includeBaselineProfile = false,
                    targetLevel = null,
                    includeVbr = false,
                    includeVendorTimingKeys = false,
                    includeSamsungQpMax = false,
                    includeFpsLimitKeys = true
                )
            )
            add(
                "MinimalAVC" to buildPreferredAvcMediaFormat(
                    width = width,
                    height = height,
                    frameRate = frameRate,
                    bitRate = bitRate,
                    includeBaselineProfile = false,
                    targetLevel = null,
                    includeVbr = false,
                    includeVendorTimingKeys = false,
                    includeSamsungQpMax = false,
                    includeFpsLimitKeys = false
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
                lastConfiguredEncoderProfile = label
                lastConfiguredLowLatency = label.contains("LowLatency", ignoreCase = true)
                _stats.update {
                    it.copy(
                        activeEncoderProfile = label,
                        isLowLatencyEncoderActive = lastConfiguredLowLatency
                    )
                }
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
        var pendingSliceFrames = 0L
        var pendingSliceBytes = 0L
        var fpsWindowStartMs = SystemClock.elapsedRealtime()
        var fpsWindowFrames = 0
        var fpsWindowIdrs = 0
        var lastScreenOffKeepAliveIdrMs = 0L

        fun maybePublishFps(nowMs: Long) {
            val elapsedFpsMs = nowMs - fpsWindowStartMs
            if (elapsedFpsMs >= 1000L) {
                val measuredFps = (fpsWindowFrames * 1000f) / elapsedFpsMs.coerceAtLeast(1L)
                val measuredIdrRate = (fpsWindowIdrs * 1000f) / elapsedFpsMs.coerceAtLeast(1L)
                fpsWindowStartMs = nowMs
                fpsWindowFrames = 0
                fpsWindowIdrs = 0
                _stats.update {
                    it.copy(
                        actualFps = measuredFps,
                        idrPerSec = measuredIdrRate,
                        activeEncoderProfile = lastConfiguredEncoderProfile,
                        isLowLatencyEncoderActive = lastConfiguredLowLatency
                    )
                }
            }
        }

        try {
            while (isActive && isEncoding.get()) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                val loopNowMs = SystemClock.elapsedRealtime()
                maybePublishFps(loopNowMs)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        val nowElapsed = loopNowMs
                        // Per VideoOutputThread.smali: if no frame arrives from MediaCodec for >200 ms,
                        // send an empty MSG_VIDEO_HEARTBEAT (0x00020002, payloadLen = 0) instead of standalone SPS/PPS.
                        if (isVideoChannelReadyForStreaming &&
                            lastCodecFrameElapsedMs > 0L &&
                            (nowElapsed - lastCodecFrameElapsedMs) >= 200L
                        ) {
                            lastCodecFrameElapsedMs = nowElapsed
                            val keepAliveTs = SystemClock.uptimeMillis() and 0x7FFF_FFFFL
                            onVideoIdleHeartbeat(keepAliveTs)
                            if (isPhoneScreenOffFlag && (nowElapsed - lastScreenOffKeepAliveIdrMs) >= 2_000L) {
                                // While phone display is off, push a cached IDR every 2s so the HU stays locked on CarLife
                                val cachedIdr = lastKeyFrameAnnexB
                                if (cachedIdr != null) {
                                    lastScreenOffKeepAliveIdrMs = nowElapsed
                                    emitCombinedSpsPpsAndIdr(idrBytes = cachedIdr, timestampMs = keepAliveTs)
                                }
                            }
                            if (awaitingFirstIdrAfterGateOpen.get()) {
                                requestEncoderSyncFrameOnly()
                            }
                        }
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

                            // Zero-allocation leading NAL header scan (only splits on rare inline SPS/PPS or non-Annex-B buffers)
                            val leading = CarLifeFrameCodec.inspectLeadingNalHeader(rawNal)
                            var containsIdrNal = isKeyFrameFlag || leading.hasIdr
                            if (leading.hasSps || leading.hasPps || !leading.hasAnnexBStartCode) {
                                cacheConfigNalUnits(rawNal)
                                if (!containsIdrNal && !leading.hasAnnexBStartCode) {
                                    containsIdrNal = CarLifeFrameCodec.splitAnnexBNalUnits(rawNal).any { nal ->
                                        nal.size > 4 && (nal[4].toInt() and 0x1F) == 5
                                    }
                                }
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
                                fpsWindowFrames++
                                fpsWindowIdrs++
                                lastCodecFrameElapsedMs = SystemClock.elapsedRealtime()
                                awaitingFirstIdrAfterGateOpen.set(false)
                                if (pendingSliceFrames > 0L) {
                                    val flushFrames = pendingSliceFrames
                                    val flushBytes = pendingSliceBytes
                                    pendingSliceFrames = 0L
                                    pendingSliceBytes = 0L
                                    _stats.update {
                                        it.copy(
                                            encodedNalFrames = it.encodedNalFrames + flushFrames,
                                            encodedBytes = it.encodedBytes + flushBytes
                                        )
                                    }
                                }
                                // Send ONE combined packet: cachedSpsPps + idrBytes (unless already prepended)
                                emitCombinedSpsPpsAndIdr(
                                    idrBytes = rawNal,
                                    timestampMs = timestampMs
                                )
                            } else if (!awaitingFirstIdrAfterGateOpen.get()) {
                                fpsWindowFrames++
                                lastCodecFrameElapsedMs = SystemClock.elapsedRealtime()
                                // Regular P-frame (Slice): send as-is in ONE packet without splitting
                                val slicePacket = CarLifeFrameCodec.ensureAnnexBStartCode(rawNal)
                                onEncodedVideoNal(slicePacket, timestampMs, false)
                                pendingSliceFrames++
                                pendingSliceBytes += slicePacket.size.toLong()
                                if (pendingSliceFrames >= 15L) {
                                    val flushFrames = pendingSliceFrames
                                    val flushBytes = pendingSliceBytes
                                    pendingSliceFrames = 0L
                                    pendingSliceBytes = 0L
                                    _stats.update {
                                        it.copy(
                                            encodedNalFrames = it.encodedNalFrames + flushFrames,
                                            encodedBytes = it.encodedBytes + flushBytes
                                        )
                                    }
                                }
                            } else {
                                // Waiting for initial IDR after gate open: request sync frame and skip stale P-slice
                                requestEncoderSyncFrameOnly()
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
        private const val CONGESTION_IDR_COOLDOWN_MS = 200L
        private const val WINDOW_TYPE_PRIVATE_PRESENTATION = 2030
        private const val WINDOW_TYPE_PRESENTATION = 2037
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_BITRATE_BPS = 2_000_000
        const val FIXED_VIRTUAL_DISPLAY_DPI = DisplayMetrics.DENSITY_MEDIUM // 160 dpi
        const val MIRROR_VIRTUAL_DISPLAY_DPI = 180 // 160..200 dpi for smooth QHD+ -> 720p mirroring
        const val SAMSUNG_QP_P_MAX_KEY = "video-qp-p-max"
        const val SAMSUNG_QP_P_MAX_VALUE = 36

        /**
         * Builds the preferred H.264 [MediaFormat] matching `MediaFormatUtils.smali` with strict
         * 30 FPS input capping for 120 Hz Samsung displays:
         * `1280×720`, VBR (`BITRATE_MODE_VBR = 1`), `AVCProfileBaseline (1)`, `AVCLevel3 (0x100)`,
         * `KEY_FRAME_RATE = 30`, `KEY_MAX_FPS_TO_ENCODER = 30f`, `KEY_CAPTURE_RATE = 30`,
         * `KEY_I_FRAME_INTERVAL = 2`, `KEY_OPERATING_RATE = 120`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100_000L`,
         * `KEY_PRIORITY = 0` (realtime), optional `KEY_LOW_LATENCY = 1` (API 30+),
         * and Samsung `video-qp-p-max = 36`.
         */
        internal fun buildPreferredAvcMediaFormat(
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            frameRate: Int = 30,
            bitRate: Int = DEFAULT_BITRATE_BPS,
            includeBaselineProfile: Boolean = true,
            targetLevel: Int? = MediaCodecInfo.CodecProfileLevel.AVCLevel3,
            includeVbr: Boolean = true,
            includeVendorTimingKeys: Boolean = true,
            includeSamsungQpMax: Boolean = true,
            includeFpsLimitKeys: Boolean = true,
            includeLowLatency: Boolean = false
        ): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            if (includeFpsLimitKeys) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
                }
                setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate)
            }
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (includeBaselineProfile) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                if (targetLevel != null) {
                    setInteger(MediaFormat.KEY_LEVEL, targetLevel)
                }
            }
            if (includeVbr) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            }
            if (includeVendorTimingKeys) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = realtime priority
                    setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (includeLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                if (includeSamsungQpMax) {
                    setInteger(SAMSUNG_QP_P_MAX_KEY, SAMSUNG_QP_P_MAX_VALUE)
                }
            }
        }
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
                val activeThemeMode by ThemePreferences.themeMode.collectAsState()
                MyApplicationTheme(themeMode = activeThemeMode) {
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
    val media by MediaPlayerController.mediaState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

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
            onPlayPause = { MediaPlayerController.playPause() },
            onNextTrack = { MediaPlayerController.next() },
            onPreviousTrack = { MediaPlayerController.previous() },
            onRequestNotificationAccess = {},
            isShizukuMode = stats.appMode == AppMode.SHIZUKU_COCKPIT,
            onHomePressed = {
                CockpitUiStateController.showGrid()
                if (stats.appMode == AppMode.SHIZUKU_COCKPIT) {
                    CarLifeShizukuManager.sendKeyEventAsync(KeyEvent.KEYCODE_HOME, virtualDisplayId)
                }
                onBringLauncherToFront()
            },
            onBackPressed = {
                when (stats.appMode) {
                    AppMode.SHIZUKU_COCKPIT ->
                        CarLifeShizukuManager.sendKeyEventAsync(KeyEvent.KEYCODE_BACK, virtualDisplayId)
                    AppMode.MIRRORING ->
                        CarLifeAccessibilityService.instance?.performBackAction()
                    AppMode.COCKPIT ->
                        CockpitUiStateController.showGrid()
                }
            },
            onMapPressed = {
                if (stats.appMode == AppMode.SHIZUKU_COCKPIT) {
                    val launchedNative = CarLifeLauncherRepository.launchPreferredNavigatorOnShizuku(
                        context = context,
                        targetDisplayId = virtualDisplayId
                    )
                    if (!launchedNative) {
                        CockpitUiStateController.toggleMap()
                    }
                } else {
                    CockpitUiStateController.toggleMap()
                }
            }
        )

        val primaryAccent = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = primaryAccent.copy(alpha = pulsePhase * 0.25f),
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
                    color = primaryAccent.copy(alpha = 0.28f),
                    radius = 24.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = Color.White,
                    radius = 24.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 4.dp.toPx(),
                    center = center
                )
            }
        }
    }
}
