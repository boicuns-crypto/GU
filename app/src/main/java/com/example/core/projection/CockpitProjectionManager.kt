package com.example.core.projection

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
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
import com.example.feature.cockpit.MediaControllerRepository
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MyApplicationTheme
import java.io.ByteArrayOutputStream
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
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitRateMbps: Int = 12,
    val encodedNalFrames: Long = 0L,
    val encodedBytes: Long = 0L,
    val spsPpsConfigured: Boolean = false,
    val injectedTouches: Long = 0L,
    val lastTouchPoint: Pair<Float, Float>? = null,
    val activeCockpitWidget: String = "CarLife App Launcher & Dock",
    val statusMessage: String = "Standby — Ready to start VirtualDisplay + H.264 Surface Encoder"
)

/**
 * Core 2 (`:core:projection`): Self-Contained VirtualDisplay (supporting both `MediaProjection`
 * for full screen/app mirroring and fallback `DisplayManager` virtual display), Hardware H.264
 * Encoder with immediate SPS/PPS + IDR burst to pass the CarLife 90% loading threshold,
 * `CockpitPresentation`, and Multi-Touch Injection via `CarLifeTouchAccessibilityService`.
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

    private val projectionMutex = Mutex()
    private val isEncoding = AtomicBoolean(false)

    @Volatile
    private var cachedSpsPpsAnnexB: ByteArray? = null

    @Volatile
    private var lastKeyFrameAnnexB: ByteArray? = null

    private val activeDownTimeUptimeMs = AtomicLong(0L)

    private val _stats = MutableStateFlow(ProjectionPipelineStats())
    val stats: StateFlow<ProjectionPipelineStats> = _stats.asStateFlow()

    suspend fun startProjection(
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Int = 60,
        bitRate: Int = 12_000_000,
        densityDpi: Int = DisplayMetrics.DENSITY_XHIGH
    ): Boolean = projectionMutex.withLock {
        stopProjectionLocked()

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var vDisplay: VirtualDisplay? = null
        var activeMp: MediaProjection? = null
        var usedMediaProjection = false

        return@withLock try {
            codec = configureLowLatencyAvcEncoder(width, height, frameRate, bitRate)
            inputSurface = codec.createInputSurface()
            codec.start()

            // 1. Prefer MediaProjection VirtualDisplay when user completed Step 5 (required for full app mirroring)
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
                        // Android 14–16 (SDK 34–36) strictly requires registering a callback BEFORE createVirtualDisplay
                        mp.registerCallback(callback, mainHandler)
                        mediaProjectionCallback = callback
                        activeMp = mp

                        vDisplay = mp.createVirtualDisplay(
                            VIRTUAL_DISPLAY_NAME,
                            width,
                            height,
                            densityDpi,
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
                    Log.w(TAG, "MediaProjection VirtualDisplay creation failed, falling back to DisplayManager: ${e.message}")
                    MediaProjectionTokenStore.clearToken()
                }
            }

            // 2. Fallback to DisplayManager Presentation VirtualDisplay if MediaProjection wasn't used
            if (vDisplay == null) {
                vDisplay = displayManager.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    inputSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
            }

            val createdDisplay = vDisplay!!.display
            awaitDisplayRegistered(createdDisplay.displayId)

            val presentation = showPresentationWithRetry(createdDisplay)

            withContext(Dispatchers.Main.immediate) {
                cockpitPresentation = presentation
            }
            mediaProjection = activeMp
            mediaCodec = codec
            encoderInputSurface = inputSurface
            virtualDisplay = vDisplay
            isEncoding.set(true)

            val modeLabel = if (usedMediaProjection) "MediaProjection Full Screen" else "Presentation VirtualDisplay"
            _stats.update {
                it.copy(
                    isRunning = true,
                    usingMediaProjection = usedMediaProjection,
                    virtualDisplayId = createdDisplay.displayId,
                    width = width,
                    height = height,
                    fps = frameRate,
                    bitRateMbps = bitRate / 1_000_000,
                    statusMessage = "$modeLabel #${createdDisplay.displayId} ($width×$height @ ${frameRate}fps) Active"
                )
            }

            encoderDrainJob = externalScope.launch(Dispatchers.IO) {
                drainHardwareEncoderLoop(codec)
            }

            // Request an immediate IDR sync frame so the Head Unit passes 90% without waiting
            requestInstantIdrKeyFrame()
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
        var lastError: WindowManager.InvalidDisplayException? = null
        val isPrivateDisplay = (display.flags and Display.FLAG_PRIVATE) != 0
        val windowType = if (isPrivateDisplay) {
            WINDOW_TYPE_PRIVATE_PRESENTATION
        } else {
            WINDOW_TYPE_PRESENTATION
        }

        for (attempt in 0 until 4) {
            try {
                return withContext(Dispatchers.Main.immediate) {
                    val displayContext = appContext.createDisplayContext(display)
                    val presentationContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            displayContext.createWindowContext(
                                display,
                                windowType,
                                null
                            )
                        }.getOrElse { displayContext }
                    } else {
                        displayContext
                    }
                    val presentation = CockpitPresentation(
                        outerContext = presentationContext,
                        display = display,
                        statsFlow = _stats
                    )
                    presentation.show()
                    presentation
                }
            } catch (e: WindowManager.InvalidDisplayException) {
                lastError = e
                Log.w(
                    TAG,
                    "Presentation.show() attempt ${attempt + 1} got InvalidDisplayException " +
                        "(displayId=${display.displayId}, flags=0x${display.flags.toString(16)})"
                )
                delay(75L * (attempt + 1))
            }
        }
        throw lastError ?: WindowManager.InvalidDisplayException("Unable to show Presentation on display ${display.displayId}")
    }

    /**
     * Triggered when `MSG_CMD_VIDEO_ENCODER_START` arrives from the Head Unit (at the 90% screen).
     * Immediately re-transmits cached `SPS + PPS` and last `IDR` keyframe if already encoded,
     * and forces the hardware encoder to emit a fresh sync IDR frame right away.
     */
    fun requestInstantIdrKeyFrame() {
        val codec = mediaCodec ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }

        val spsPps = cachedSpsPpsAnnexB
        val idr = lastKeyFrameAnnexB
        if (spsPps != null) {
            val tsMs = System.currentTimeMillis() and 0xFFFF_FFFFL
            onEncodedVideoNal(spsPps, tsMs, true)
            if (idr != null) {
                val merged = ByteArray(spsPps.size + idr.size)
                System.arraycopy(spsPps, 0, merged, 0, spsPps.size)
                System.arraycopy(idr, 0, merged, spsPps.size, idr.size)
                onEncodedVideoNal(merged, tsMs + 1L, true)
            }
        }
    }

    /**
     * Scales touch coordinates from the CarLife Head Unit stream (`huWidth × huHeight`)
     * into `VirtualDisplay` coordinates (`width × height`) and injects them into both
     * `CarLifeTouchAccessibilityService` (for third-party apps running on the `VirtualDisplay`)
     * and the `CockpitPresentation` window.
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

            // 1. Dispatch gesture via AccessibilityService onto the target VirtualDisplay (controls 3rd-party apps)
            val a11yDispatched = CarLifeTouchAccessibilityService.instance?.handleMotionStream(
                displayId = vDisplayId,
                action = packet.androidAction,
                x = scaledX,
                y = scaledY,
                synthesizeClickUp = packet.synthesizeClickUp
            ) ?: false

            // 2. Also dispatch directly to the Launcher Presentation decorView when active
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
            }

            val zoneHit = if (scaledX < 250f) {
                "Dock Bar (${scaledX.toInt()}, ${scaledY.toInt()}) on VD #$vDisplayId"
            } else {
                "App Mirroring / Launcher (${scaledX.toInt()}, ${scaledY.toInt()}) on VD #$vDisplayId"
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
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel42
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // Repeat previous frame every 16.6ms so MediaCodec immediately emits H.264 frames even on static UI
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / frameRate.coerceAtLeast(1))
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            val fallbackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / frameRate.coerceAtLeast(1))
            }
            codec.reset()
            codec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return codec
    }

    private suspend fun CoroutineScope.drainHardwareEncoderLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

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
                            combined.write(bytes)
                        }
                        if (pps != null) {
                            val ppsDup = pps.duplicate()
                            val bytes = ByteArray(ppsDup.remaining())
                            ppsDup.get(bytes)
                            combined.write(bytes)
                        }
                        val spsPpsBytes = combined.toByteArray()
                        if (spsPpsBytes.isNotEmpty()) {
                            cachedSpsPpsAnnexB = spsPpsBytes
                            val tsMs = System.currentTimeMillis() and 0xFFFF_FFFFL
                            onEncodedVideoNal(spsPpsBytes, tsMs, true)
                            _stats.update {
                                it.copy(
                                    spsPpsConfigured = true,
                                    encodedNalFrames = it.encodedNalFrames + 1L,
                                    encodedBytes = it.encodedBytes + spsPpsBytes.size
                                )
                            }
                        }
                    }

                    outIndex >= 0 -> {
                        val encodedBuffer = codec.getOutputBuffer(outIndex)
                        if (encodedBuffer != null && bufferInfo.size > 0) {
                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val rawNal = ByteArray(bufferInfo.size)
                            encodedBuffer.get(rawNal)

                            if (isCodecConfig) {
                                cachedSpsPpsAnnexB = rawNal
                            } else if (isKeyFrame) {
                                lastKeyFrameAnnexB = rawNal
                            }

                            val payloadToSend = if (isKeyFrame && !isCodecConfig && cachedSpsPpsAnnexB != null) {
                                val spsPps = cachedSpsPpsAnnexB!!
                                ByteArray(spsPps.size + rawNal.size).also { merged ->
                                    System.arraycopy(spsPps, 0, merged, 0, spsPps.size)
                                    System.arraycopy(rawNal, 0, merged, spsPps.size, rawNal.size)
                                }
                            } else {
                                rawNal
                            }

                            val timestampMs = (bufferInfo.presentationTimeUs / 1_000L) and 0xFFFF_FFFFL
                            onEncodedVideoNal(payloadToSend, timestampMs, isKeyFrame)

                            _stats.update {
                                it.copy(
                                    encodedNalFrames = it.encodedNalFrames + 1L,
                                    encodedBytes = it.encodedBytes + payloadToSend.size
                                )
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
        // AOSP WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION = 2030 / TYPE_PRESENTATION = 2037
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
 * Android [Presentation] window rendered onto the `VirtualDisplay` (and encoded into H.264 for the
 * Head Unit). Hosts the [CarLifeAppLauncherScreen] (App Grid + Side Dock + Media Controller).
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

    // Continuous 60fps surface pulse so Hardware H.264 MediaCodec Surface always emits SPS/PPS + IDR frames
    // immediately after MSG_CMD_VIDEO_ENCODER_START (preventing the Head Unit from stalling at 90%)
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

        // Subtle live indicator + touch crosshair on VirtualDisplay to keep Surface active at 60fps
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
