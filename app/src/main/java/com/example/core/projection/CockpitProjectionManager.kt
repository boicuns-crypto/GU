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
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
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
import com.example.core.input.CarLifeTouchAccessibilityService
import com.example.feature.cockpit.CarLifeAppLauncherScreen
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.MediaControllerRepository
import com.example.protocol.carlife.CarLifeFrameCodec
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
import kotlinx.coroutines.SupervisorJob
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
 * Live telemetry of the VirtualDisplay + Hardware H.264 MediaCodec + Touch Injection pipeline.
 */
data class ProjectionPipelineStats(
    val isRunning: Boolean = false,
    val usingMediaProjection: Boolean = false,
    val virtualDisplayId: Int = Display.DEFAULT_DISPLAY,
    val width: Int = 800,
    val height: Int = 480,
    val fps: Int = 30,
    val bitRateMbps: Int = 1,
    val bitRateKbps: Int = 800,
    val encodedNalFrames: Long = 0L,
    val encodedBytes: Long = 0L,
    val lastIdrBytes: Int = 0,
    val spsPpsConfigured: Boolean = false,
    val injectedTouches: Long = 0L,
    val lastTouchPoint: Pair<Float, Float>? = null,
    val activeCockpitWidget: String = "Лаунчер ГУ CarLife активен",
    val statusMessage: String = "Лаунчер ГУ готов (800×480 @ 800 кбит/с)"
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
    private var cockpitPresentation: CockpitPresentation? = null
    private var encoderDrainJob: Job? = null
    private var canvasFeederJob: Job? = null

    private val projectionMutex = Mutex()
    private val isEncoding = AtomicBoolean(false)

    /**
     * Strict gate for channel `0x0002` video streaming:
     * Remains `false` when `VirtualDisplay` starts or USB AOA connects.
     * Switched to `true` ONLY after `MSG_CMD_VIDEO_ENCODER_INIT (0x00010007)` is received on `0x0001`
     * and `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008)` has been transmitted.
     */
    @Volatile
    var isVideoChannelReadyForStreaming: Boolean = false
        private set

    private val awaitingFirstIdrAfterGateOpen = AtomicBoolean(true)

    @Volatile
    private var cachedSpsPpsAnnexB: ByteArray? = null

    @Volatile
    private var lastKeyFrameAnnexB: ByteArray? = null

    private val activeDownTimeUptimeMs = AtomicLong(0L)

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

    suspend fun startProjection(
        width: Int = 800,
        height: Int = 480,
        frameRate: Int = 30,
        bitRate: Int = 800_000,
        densityDpi: Int = DisplayMetrics.DENSITY_MEDIUM
    ): Boolean = projectionMutex.withLock {
        stopProjectionLocked()

        val safeWidth = if (width in 480..3840) width else 800
        val safeHeight = if (height in 272..2160) height else 480
        val safeFps = if (frameRate in 15..60) frameRate else 30
        val safeBitRate = bitRate.coerceIn(400_000, 12_000_000)
        val effectiveDpi = if (safeWidth <= 1024) DisplayMetrics.DENSITY_MEDIUM else densityDpi

        if (CarLifeLauncherRepository.installedApps.value.isEmpty()) {
            externalScope.launch(Dispatchers.IO) {
                CarLifeLauncherRepository.loadInstalledApps(appContext)
            }
        }

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var vDisplay: VirtualDisplay? = null
        var activeMp: MediaProjection? = null
        var usedMediaProjection = false

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

            // Check if user granted MediaProjection (Step 5)
            val tokenData = MediaProjectionTokenStore.resultData
            val tokenCode = MediaProjectionTokenStore.resultCode
            if (tokenCode == Activity.RESULT_OK && tokenData != null) {
                runCatching {
                    val mp = mediaProjectionManager.getMediaProjection(tokenCode, Intent(tokenData))
                    if (mp != null) {
                        val callback = object : MediaProjection.Callback() {
                            override fun onStop() {
                                Log.w(TAG, "MediaProjection stopped by system")
                                MediaProjectionTokenStore.clearToken()
                            }
                        }
                        mp.registerCallback(callback, mainHandler)
                        mediaProjectionCallback = callback
                        activeMp = mp

                        vDisplay = mp.createVirtualDisplay(
                            VIRTUAL_DISPLAY_NAME,
                            safeWidth,
                            safeHeight,
                            effectiveDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                            inputSurface,
                            null,
                            mainHandler
                        )
                        usedMediaProjection = true
                    }
                }.onFailure { e ->
                    Log.w(TAG, "MediaProjection VirtualDisplay creation failed, switching to Canvas Surface Feeder: ${e.message}")
                    MediaProjectionTokenStore.clearToken()
                }
            }

            if (usedMediaProjection && vDisplay != null) {
                val createdDisplay = vDisplay!!.display
                awaitDisplayRegistered(createdDisplay.displayId)

                // Immediately render CarLife Launcher UI onto the VirtualDisplay via CockpitPresentation
                runCatching {
                    val presentation = showPresentationWithRetry(createdDisplay)
                    withContext(Dispatchers.Main.immediate) {
                        cockpitPresentation = presentation
                        presentation.window?.decorView?.postInvalidate()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Presentation overlay skipped (MediaProjection Auto-Mirror still active): ${e.message}")
                }

                mediaProjection = activeMp
                virtualDisplay = vDisplay

                CarLifeLauncherRepository.markLauncherActiveOnDisplay(
                    displayId = createdDisplay.displayId,
                    width = safeWidth,
                    height = safeHeight,
                    bitrateBps = safeBitRate
                )

                _stats.update {
                    it.copy(
                        isRunning = true,
                        usingMediaProjection = true,
                        virtualDisplayId = createdDisplay.displayId,
                        width = safeWidth,
                        height = safeHeight,
                        fps = safeFps,
                        bitRateMbps = (safeBitRate / 1_000_000).coerceAtLeast(1),
                        bitRateKbps = safeBitRate / 1_000,
                        activeCockpitWidget = "Лаунчер ГУ активен на VD #${createdDisplay.displayId}",
                        statusMessage = "Лаунчер ГУ активен на VD #${createdDisplay.displayId} ($safeWidth×$safeHeight @ ${safeBitRate / 1000} кбит/с)"
                    )
                }

                // Watchdog: if MediaProjection hasn't pushed any frame within 120ms,
                // detach VirtualDisplay from inputSurface and start Canvas Feeder so 90% never hangs!
                externalScope.launch(Dispatchers.IO) {
                    delay(120L)
                    if (isEncoding.get() && cachedSpsPpsAnnexB == null && lastKeyFrameAnnexB == null) {
                        Log.w(TAG, "MediaProjection produced 0 frames in 120ms; activating Canvas Surface Feeder fallback")
                        runCatching { vDisplay?.surface = null }
                        startCanvasFeederLoopIfNeeded(inputSurface, safeWidth, safeHeight, safeFps)
                    }
                }
            } else {
                // Guaranteed 30fps Canvas-to-Surface H.264 generator:
                // Immediately draws the CarLife Launcher directly into MediaCodec's inputSurface
                startCanvasFeederLoopIfNeeded(inputSurface, safeWidth, safeHeight, safeFps)

                CarLifeLauncherRepository.markLauncherActiveOnDisplay(
                    displayId = Display.DEFAULT_DISPLAY,
                    width = safeWidth,
                    height = safeHeight,
                    bitrateBps = safeBitRate
                )

                _stats.update {
                    it.copy(
                        isRunning = true,
                        usingMediaProjection = false,
                        virtualDisplayId = Display.DEFAULT_DISPLAY,
                        width = safeWidth,
                        height = safeHeight,
                        fps = safeFps,
                        bitRateMbps = (safeBitRate / 1_000_000).coerceAtLeast(1),
                        bitRateKbps = safeBitRate / 1_000,
                        activeCockpitWidget = "Лаунчер ГУ активен (Direct Surface)",
                        statusMessage = "Лаунчер ГУ активен ($safeWidth×$safeHeight @ ${safeBitRate / 1000} кбит/с)"
                    )
                }
            }

            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize CockpitProjectionManager", t)
            runCatching { vDisplay?.release() }
            runCatching { activeMp?.stop() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            stopProjectionLocked(updateStatus = false)
            _stats.update {
                it.copy(
                    isRunning = false,
                    usingMediaProjection = false,
                    virtualDisplayId = Display.DEFAULT_DISPLAY,
                    spsPpsConfigured = false,
                    statusMessage = "Projection Init Error: ${t.message}"
                )
            }
            false
        }
    }

    /**
     * Renders the CarLife Cockpit Launcher directly into [surface] (`MediaCodec` input surface)
     * every `1000 / frameRate` ms (~33ms for 30fps) so `MediaCodec` is 100% guaranteed to emit
     * `SPS + PPS` (`BUFFER_FLAG_CODEC_CONFIG`) and `IDR` keyframes immediately after `MSG_CMD_VIDEO_ENCODER_INIT`.
     */
    private fun startCanvasFeederLoopIfNeeded(
        surface: Surface,
        width: Int,
        height: Int,
        frameRate: Int
    ) {
        if (canvasFeederJob?.isActive == true) return
        val frameIntervalMs = (1_000L / frameRate.coerceIn(15, 60)).coerceAtLeast(16L)

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
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

            var frameSeq = 0L
            while (isActive && isEncoding.get() && surface.isValid) {
                val loopStart = SystemClock.uptimeMillis()
                try {
                    val canvas: AndroidCanvas? = surface.lockCanvas(null)
                    if (canvas != null) {
                        try {
                            drawCarLifeLauncherFrame(
                                canvas = canvas,
                                width = width,
                                height = height,
                                frameSeq = frameSeq++,
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

    private suspend fun showPresentationWithRetry(display: Display): CockpitPresentation {
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
                    // Try standard appContext / displayContext first so Presentation attaches cleanly
                    val displayContext = runCatching { appContext.createDisplayContext(display) }.getOrElse { appContext }
                    val presentation = runCatching {
                        CockpitPresentation(
                            outerContext = displayContext,
                            display = display,
                            statsFlow = _stats
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
                            statsFlow = _stats
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
     * Unlocked ONLY after `MSG_CMD_VIDEO_ENCODER_INIT (0x00010007)` is received from the Head Unit
     * and `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008)` is sent on channel `0x0001`.
     *
     * 1. Sets `isVideoChannelReadyForStreaming = true`.
     * 2. Forces `MediaCodec` to generate a fresh sync keyframe (`PARAMETER_KEY_REQUEST_SYNC_FRAME = 0`).
     * 3. If `cachedSpsPpsAnnexB` and `lastKeyFrameAnnexB` are already cached in memory, immediately sends
     *    Packet #1 (`SPS/PPS`) and Packet #2 (`SPS/PPS + IDR`) on channel `0x0002`.
     */
    fun unlockVideoChannelAndRequestSyncFrame() {
        awaitingFirstIdrAfterGateOpen.set(true)
        isVideoChannelReadyForStreaming = true

        val codec = mediaCodec ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }

        val spsPps = cachedSpsPpsAnnexB?.let { CarLifeFrameCodec.ensureAnnexBStartCode(it) }
        val idr = lastKeyFrameAnnexB?.let { CarLifeFrameCodec.ensureAnnexBStartCode(it) }
        if (spsPps != null && idr != null && awaitingFirstIdrAfterGateOpen.compareAndSet(true, false)) {
            val tsMs = SystemClock.uptimeMillis() and 0x7FFF_FFFFL
            // Packet #1 on 0x0002: standalone SPS/PPS
            onEncodedVideoNal(spsPps, tsMs, false)
            // Packet #2 on 0x0002: concatenated SPS/PPS + IDR
            val combinedIdr = spsPps + idr
            onEncodedVideoNal(combinedIdr, tsMs + 1L, true)
            _stats.update {
                it.copy(
                    spsPpsConfigured = true,
                    encodedNalFrames = it.encodedNalFrames + 2L,
                    encodedBytes = it.encodedBytes + spsPps.size + combinedIdr.size,
                    lastIdrBytes = combinedIdr.size
                )
            }
        }
    }

    /**
     * Requests a fresh `IDR` sync frame from `MediaCodec` and sends `SPS/PPS` + `SPS/PPS+IDR`
     * ONLY if `isVideoChannelReadyForStreaming` is `true`.
     */
    fun requestInstantIdrKeyFrame() {
        val codec = mediaCodec ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }
        if (!isVideoChannelReadyForStreaming) return

        val spsPps = cachedSpsPpsAnnexB?.let { CarLifeFrameCodec.ensureAnnexBStartCode(it) }
        val idr = lastKeyFrameAnnexB?.let { CarLifeFrameCodec.ensureAnnexBStartCode(it) }
        val tsMs = SystemClock.uptimeMillis() and 0x7FFF_FFFFL
        if (spsPps != null) {
            onEncodedVideoNal(spsPps, tsMs, false)
        }
        if (idr != null) {
            val combinedIdr = if (spsPps != null) spsPps + idr else idr
            onEncodedVideoNal(combinedIdr, tsMs + 1L, true)
        }
    }

    /**
     * Scales touch coordinates from the CarLife Head Unit stream (`huWidth × huHeight`)
     * into `VirtualDisplay` coordinates (`width × height`) and injects them into:
     * 1. `CarLifeTouchAccessibilityService` (for third-party apps on the `VirtualDisplay`)
     * 2. `CockpitPresentation` window (when active)
     * 3. Canvas Launcher grid hit-testing (when in Direct Surface Generator mode)
     */
    suspend fun injectTouchPacket(packet: CarLifeTouchPacket): Boolean {
        val currentStats = _stats.value
        val targetW = currentStats.width.coerceAtLeast(1)
        val targetH = currentStats.height.coerceAtLeast(1)
        val huW = packet.huWidth.coerceAtLeast(1)
        val huH = packet.huHeight.coerceAtLeast(1)

        val scaledX = ((packet.rawX.coerceIn(0, huW).toFloat() / huW.toFloat()) * targetW.toFloat())
        val scaledY = ((packet.rawY.coerceIn(0, huH).toFloat() / huH.toFloat()) * targetH.toFloat())

        return withContext(Dispatchers.Main.immediate) {
            val vDisplayId = virtualDisplay?.display?.displayId ?: Display.DEFAULT_DISPLAY

            val a11yDispatched = CarLifeTouchAccessibilityService.instance?.handleMotionStream(
                displayId = vDisplayId,
                action = packet.androidAction,
                x = scaledX,
                y = scaledY,
                synthesizeClickUp = packet.synthesizeClickUp
            ) ?: false

            val decorView = cockpitPresentation?.window?.decorView
            var viewDispatched = false
            if (decorView != null) {
                val nowUptime = SystemClock.uptimeMillis()
                val downTime = when (packet.androidAction) {
                    MotionEvent.ACTION_DOWN -> {
                        activeDownTimeUptimeMs.set(nowUptime)
                        nowUptime
                    }
                    else -> {
                        val existing = activeDownTimeUptimeMs.get()
                        if (existing > 0L) existing else nowUptime
                    }
                }

                viewDispatched = dispatchSynthesizedMotionEvent(
                    decorView = decorView,
                    downTime = downTime,
                    eventTime = nowUptime,
                    action = packet.androidAction,
                    x = scaledX,
                    y = scaledY
                )

                if (packet.synthesizeClickUp) {
                    val upTime = SystemClock.uptimeMillis() + 16L
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
                handleCanvasLauncherTap(scaledX, scaledY, targetW.toFloat(), targetH.toFloat(), vDisplayId)
            }

            val zoneHit = if (scaledX < targetW * 0.25f) {
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
                CarLifeTouchAccessibilityService.instance?.performBackAction()
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
            val a11yHandled = CarLifeTouchAccessibilityService.instance
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

    private suspend fun stopProjectionLocked(updateStatus: Boolean = true) {
        isEncoding.set(false)
        canvasFeederJob?.cancel()
        canvasFeederJob = null
        encoderDrainJob?.cancel()
        encoderDrainJob = null

        withContext(Dispatchers.Main.immediate) {
            runCatching { cockpitPresentation?.dismiss() }
            cockpitPresentation = null
        }

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching {
            mediaProjectionCallback?.let { cb ->
                mediaProjection?.unregisterCallback(cb)
            }
            mediaProjection?.stop()
        }
        mediaProjectionCallback = null
        mediaProjection = null

        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null

        runCatching { encoderInputSurface?.release() }
        encoderInputSurface = null
        cachedSpsPpsAnnexB = null
        lastKeyFrameAnnexB = null

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

        // Stage 1: Pure AVCProfileBaseline (0x01) + AVCLevel31 + BITRATE_MODE_VBR + KEY_I_FRAME_INTERVAL = 2 + QP bounds (IDR < 25 KB)
        val baselineQpBoundedFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Prevent initial IDR keyframe spikes > 25 KB on Qualcomm/Exynos hardware encoders
                setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, 28)
                setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, 46)
                setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, 26)
                setInteger(MediaFormat.KEY_VIDEO_QP_P_MAX, 46)
            }
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

        // Stage 2: Pure AVCProfileBaseline (0x01) + AVCLevel31 + BITRATE_MODE_VBR + KEY_I_FRAME_INTERVAL = 2
        val baselineLevel31Format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

        // Stage 3: Strict AVCProfileBaseline + Level 3.1 + KEY_I_FRAME_INTERVAL = 2
        val baselineMinimalLevelFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

        val candidates = listOf(
            "AVCProfileBaseline+Level31+VBR+QPBounded" to baselineQpBoundedFormat,
            "AVCProfileBaseline+Level31+VBR" to baselineLevel31Format,
            "AVCProfileBaseline+Level31" to baselineMinimalLevelFormat
        )

        for ((label, candidate) in candidates) {
            try {
                codec.reset()
                codec.configure(candidate, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "Configured H.264 encoder in $label ($width×$height @ ${frameRate}fps, ${bitRate / 1000}kbps)")
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "H.264 config candidate $label rejected (${e.message}), trying next...")
            }
        }

        // Final fallback if running on a software codec that refuses explicit profile keys
        val fallbackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        codec.reset()
        codec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /**
     * Splits an Annex-B H.264 buffer into individual NAL units, each prefixed with `00 00 00 01`.
     */
    private fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList< Pair<Int, Int> >(8) // (startCodeOffset, payloadOffset)
        var i = 0
        while (i <= data.size - 3) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts.add(i to (i + 4))
                    i += 4
                    continue
                } else if (data[i + 2].toInt() == 1) {
                    starts.add(i to (i + 3))
                    i += 3
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(data)

        val result = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val payloadStart = starts[idx].second
            val payloadEnd = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
            val payloadLen = payloadEnd - payloadStart
            if (payloadLen <= 0) continue
            val normalized = ByteArray(4 + payloadLen)
            normalized[0] = 0x00
            normalized[1] = 0x00
            normalized[2] = 0x00
            normalized[3] = 0x01
            System.arraycopy(data, payloadStart, normalized, 4, payloadLen)
            result.add(normalized)
        }
        return result
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
                        delay(2L)
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        val sps = outFormat.getByteBuffer("csd-0")
                        val pps = outFormat.getByteBuffer("csd-1")
                        val combined = ByteArrayOutputStream(128)
                        if (sps != null) {
                            val spsDup = sps.duplicate()
                            val bytes = ByteArray(spsDup.remaining())
                            spsDup.get(bytes)
                            combined.write(CarLifeFrameCodec.ensureAnnexBStartCode(bytes))
                        }
                        if (pps != null) {
                            val ppsDup = pps.duplicate()
                            val bytes = ByteArray(ppsDup.remaining())
                            ppsDup.get(bytes)
                            combined.write(CarLifeFrameCodec.ensureAnnexBStartCode(bytes))
                        }
                        val spsPpsBytes = combined.toByteArray()
                        if (spsPpsBytes.isNotEmpty()) {
                            cachedSpsPpsAnnexB = CarLifeFrameCodec.ensureAnnexBStartCode(spsPpsBytes)
                            _stats.update {
                                it.copy(spsPpsConfigured = true)
                            }
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

                            if (isCodecConfig) {
                                // Task 2: On BUFFER_FLAG_CODEC_CONFIG, store spsPpsBytes with Annex-B start code 00 00 00 01
                                val normalizedSpsPps = CarLifeFrameCodec.ensureAnnexBStartCode(rawNal)
                                cachedSpsPpsAnnexB = normalizedSpsPps
                                _stats.update {
                                    it.copy(spsPpsConfigured = true)
                                }
                            } else {
                                // Separate any inlined SPS (7) / PPS (8) from video slices (IDR=5 / P=1)
                                val nals = splitAnnexBNals(rawNal)
                                val configOut = ByteArrayOutputStream()
                                val sliceOut = ByteArrayOutputStream(rawNal.size)
                                var containsIdrNal = isKeyFrameFlag

                                for (nal in nals) {
                                    val nalType = if (nal.size > 4) (nal[4].toInt() and 0x1F) else 0
                                    when (nalType) {
                                        7, 8 -> configOut.write(CarLifeFrameCodec.ensureAnnexBStartCode(nal))
                                        5 -> {
                                            containsIdrNal = true
                                            sliceOut.write(CarLifeFrameCodec.ensureAnnexBStartCode(nal))
                                        }
                                        6 -> {
                                            // Drop optional SEI metadata NALs to keep keyframes compact & clean for MQB HU
                                        }
                                        else -> sliceOut.write(CarLifeFrameCodec.ensureAnnexBStartCode(nal))
                                    }
                                }

                                if (configOut.size() > 0) {
                                    cachedSpsPpsAnnexB = CarLifeFrameCodec.ensureAnnexBStartCode(configOut.toByteArray())
                                    _stats.update {
                                        it.copy(spsPpsConfigured = true)
                                    }
                                }

                                val pureSliceBytes = CarLifeFrameCodec.ensureAnnexBStartCode(
                                    if (sliceOut.size() > 0) sliceOut.toByteArray() else rawNal
                                )

                                if (containsIdrNal) {
                                    lastKeyFrameAnnexB = pureSliceBytes
                                }

                                // STRICT GATE: Do NOT send any 0x0002 packets or increment counters
                                // while isVideoChannelReadyForStreaming == false (only cache spsPpsBytes & lastKeyFrameAnnexB in memory).
                                if (!isVideoChannelReadyForStreaming) {
                                    codec.releaseOutputBuffer(outIndex, false)
                                    continue
                                }

                                if (containsIdrNal) {
                                    awaitingFirstIdrAfterGateOpen.set(false)
                                    val spsPps = cachedSpsPpsAnnexB?.let { CarLifeFrameCodec.ensureAnnexBStartCode(it) }
                                    if (spsPps != null) {
                                        // 1. First packet on 0x0002 before IDR: standalone spsPpsBytes
                                        onEncodedVideoNal(spsPps, timestampMs, false)
                                        _stats.update {
                                            it.copy(
                                                spsPpsConfigured = true,
                                                encodedNalFrames = it.encodedNalFrames + 1L,
                                                encodedBytes = it.encodedBytes + spsPps.size
                                            )
                                        }
                                    }
                                    // 2. Second packet on 0x0002: concatenated spsPpsBytes + idrBytes
                                    val idrWithSpsPps = if (spsPps != null) spsPps + pureSliceBytes else pureSliceBytes
                                    onEncodedVideoNal(idrWithSpsPps, timestampMs, true)

                                    // If hardware rate-controller produced an IDR > 22 KB, dynamically lower target bitrate to keep IDR < 25 KB
                                    if (idrWithSpsPps.size > 22_000 && currentDynamicBitrate > 450_000) {
                                        currentDynamicBitrate = (currentDynamicBitrate * 70 / 100).coerceAtLeast(450_000)
                                        runCatching {
                                            val b = Bundle().apply {
                                                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, currentDynamicBitrate)
                                            }
                                            codec.setParameters(b)
                                        }
                                    }

                                    _stats.update {
                                        it.copy(
                                            encodedNalFrames = it.encodedNalFrames + 1L,
                                            encodedBytes = it.encodedBytes + idrWithSpsPps.size,
                                            lastIdrBytes = idrWithSpsPps.size
                                        )
                                    }
                                } else if (!awaitingFirstIdrAfterGateOpen.get()) {
                                    // Non-keyframe P-slice (only sent after the initial SPS/PPS + IDR pair has been transmitted)
                                    onEncodedVideoNal(pureSliceBytes, timestampMs, false)

                                    _stats.update {
                                        it.copy(
                                            encodedNalFrames = it.encodedNalFrames + 1L,
                                            encodedBytes = it.encodedBytes + pureSliceBytes.size
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
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1.0f
                size = 1.0f
            }
        )
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            props,
            coords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
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
    private val statsFlow: StateFlow<ProjectionPipelineStats>
) : Presentation(outerContext, display) {

    private val lifecycleOwner = PresentationLifecycleOwner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        lifecycleOwner.onCreate()

        val decorView = window?.decorView
        decorView?.setViewTreeLifecycleOwner(lifecycleOwner)
        decorView?.setViewTreeViewModelStoreOwner(lifecycleOwner)
        decorView?.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        val targetDisplayId = display.displayId
        val composeView = ComposeView(context).apply {
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
        setContentView(composeView)
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
                CarLifeTouchAccessibilityService.instance?.performBackAction()
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
