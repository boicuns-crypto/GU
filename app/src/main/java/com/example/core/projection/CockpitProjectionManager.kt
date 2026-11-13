package com.example.core.projection

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
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
import com.example.feature.cockpit.CockpitScreen
import com.example.feature.cockpit.MediaControllerRepository
import com.example.feature.telemetry.VagUdsTelemetryRepository
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
import kotlinx.coroutines.withContext

/**
 * Live telemetry of the VirtualDisplay + Hardware H.264 MediaCodec + Touch Injection pipeline.
 */
data class ProjectionPipelineStats(
    val isRunning: Boolean = false,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitRateMbps: Int = 12,
    val encodedNalFrames: Long = 0L,
    val encodedBytes: Long = 0L,
    val spsPpsConfigured: Boolean = false,
    val injectedTouches: Long = 0L,
    val lastTouchPoint: Pair<Float, Float>? = null,
    val activeCockpitWidget: String = "VAG Digital Cockpit 3-Zone UI",
    val statusMessage: String = "Standby — Ready to start VirtualDisplay + H.264 Surface Encoder"
)

/**
 * Core 2 (`:core:projection`): Self-Contained VirtualDisplay, Hardware H.264 Encoder,
 * `CockpitPresentation` (hosting `CockpitScreen` in Jetpack Compose), and Zero-Privilege Touch Injector.
 */
class CockpitProjectionManager(
    context: Context,
    private val onEncodedVideoNal: (nalBytes: ByteArray, timestampMs: Long, isKeyFrame: Boolean) -> Unit,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var mediaCodec: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var cockpitPresentation: CockpitPresentation? = null
    private var encoderDrainJob: Job? = null

    private val isEncoding = AtomicBoolean(false)
    private var cachedSpsPpsAnnexB: ByteArray? = null

    private val activeDownTimeUptimeMs = AtomicLong(0L)

    private val _stats = MutableStateFlow(ProjectionPipelineStats())
    val stats: StateFlow<ProjectionPipelineStats> = _stats.asStateFlow()

    suspend fun startProjection(
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Int = 60,
        bitRate: Int = 12_000_000,
        densityDpi: Int = DisplayMetrics.DENSITY_XHIGH
    ): Boolean {
        stopProjection()

        return try {
            val codec = configureLowLatencyAvcEncoder(width, height, frameRate, bitRate)
            val inputSurface = codec.createInputSurface()
            codec.start()

            val vDisplay = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                inputSurface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            )

            withContext(Dispatchers.Main.immediate) {
                val presentation = CockpitPresentation(
                    outerContext = appContext,
                    display = vDisplay.display,
                    statsFlow = _stats
                )
                presentation.show()
                cockpitPresentation = presentation
            }

            mediaCodec = codec
            encoderInputSurface = inputSurface
            virtualDisplay = vDisplay
            isEncoding.set(true)

            _stats.update {
                it.copy(
                    isRunning = true,
                    width = width,
                    height = height,
                    fps = frameRate,
                    bitRateMbps = bitRate / 1_000_000,
                    statusMessage = "VirtualDisplay ($width×$height @ ${frameRate}fps) + H.264 Low-Latency Surface Active"
                )
            }

            encoderDrainJob = externalScope.launch(Dispatchers.IO) {
                drainHardwareEncoderLoop(codec)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize CockpitProjectionManager", t)
            _stats.update {
                it.copy(
                    isRunning = false,
                    statusMessage = "Projection Init Error: ${t.message}"
                )
            }
            stopProjection()
            false
        }
    }

    fun requestInstantIdrKeyFrame() {
        val codec = mediaCodec ?: return
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }
    }

    suspend fun injectTouchPacket(packet: CarLifeTouchPacket): Boolean {
        val currentStats = _stats.value
        val targetW = currentStats.width.coerceAtLeast(1)
        val targetH = currentStats.height.coerceAtLeast(1)
        val huW = packet.huWidth.coerceAtLeast(1)
        val huH = packet.huHeight.coerceAtLeast(1)

        val scaledX = ((packet.rawX.coerceIn(0, huW).toFloat() / huW.toFloat()) * targetW.toFloat())
        val scaledY = ((packet.rawY.coerceIn(0, huH).toFloat() / huH.toFloat()) * targetH.toFloat())

        return withContext(Dispatchers.Main.immediate) {
            val decorView = cockpitPresentation?.window?.decorView ?: return@withContext false
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

            val dispatched = dispatchSynthesizedMotionEvent(
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

            val zoneHit = when {
                scaledX < targetW * 0.5f -> "Zone 1: Navigation Map (${scaledX.toInt()}, ${scaledY.toInt()})"
                scaledY < targetH * 0.54f -> "Zone 2: VAG UDS Gauges (${scaledX.toInt()}, ${scaledY.toInt()})"
                else -> "Zone 3: Media Player (${scaledX.toInt()}, ${scaledY.toInt()})"
            }

            _stats.update {
                it.copy(
                    injectedTouches = it.injectedTouches + 1L,
                    lastTouchPoint = scaledX to scaledY,
                    activeCockpitWidget = zoneHit,
                    statusMessage = "Touch Injected -> $zoneHit"
                )
            }
            dispatched
        }
    }

    suspend fun injectHardKeyPacket(packet: CarLifeHardKeyPacket): Boolean {
        if (packet.androidKeyCode == KeyEvent.KEYCODE_UNKNOWN) return false
        return withContext(Dispatchers.Main.immediate) {
            when (packet.androidKeyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> MediaControllerRepository.skipNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaControllerRepository.skipPrevious()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MediaControllerRepository.togglePlayPause()
            }
            val decorView = cockpitPresentation?.window?.decorView ?: return@withContext false
            val now = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, packet.androidKeyCode, 0)
            val upEvent = KeyEvent(now, now + 15L, KeyEvent.ACTION_UP, packet.androidKeyCode, 0)
            val downHandled = decorView.dispatchKeyEvent(downEvent)
            val upHandled = decorView.dispatchKeyEvent(upEvent)
            _stats.update {
                it.copy(
                    statusMessage = "MQB HardKey Injected -> KeyCode=${packet.androidKeyCode}"
                )
            }
            downHandled || upHandled
        }
    }

    suspend fun stopProjection() {
        isEncoding.set(false)
        encoderDrainJob?.cancel()
        encoderDrainJob = null

        withContext(Dispatchers.Main.immediate) {
            runCatching { cockpitPresentation?.dismiss() }
            cockpitPresentation = null
        }

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null

        runCatching { encoderInputSurface?.release() }
        encoderInputSurface = null
        cachedSpsPpsAnnexB = null

        _stats.update {
            it.copy(
                isRunning = false,
                spsPpsConfigured = false,
                statusMessage = "Projection Stopped"
            )
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
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / frameRate)
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
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / frameRate)
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
        private const val VIRTUAL_DISPLAY_NAME = "JettaVS7_CarLife_Cockpit"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
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
 * Jetta VS7 Head Unit). Renders the full 3-zone [CockpitScreen] and accepts direct `MotionEvent`
 * and `KeyEvent` dispatch on `window.decorView`.
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

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                MyApplicationTheme {
                    ProjectedVagCockpitContainer(statsFlow = statsFlow)
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
        lifecycleOwner.onDestroy()
        super.dismiss()
    }
}

@Composable
private fun ProjectedVagCockpitContainer(
    statsFlow: StateFlow<ProjectionPipelineStats>
) {
    val stats by statsFlow.collectAsState()
    val telemetry by VagUdsTelemetryRepository.telemetryState.collectAsState()
    val media by MediaControllerRepository.mediaState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        CockpitScreen(
            telemetry = telemetry,
            mediaState = media,
            onPlayPause = { MediaControllerRepository.togglePlayPause() },
            onNextTrack = { MediaControllerRepository.skipNext() },
            onPreviousTrack = { MediaControllerRepository.skipPrevious() },
            onRequestNotificationAccess = {},
            onTriggerWarmUpSweep = {}
        )

        // Visual touch-injection crosshair feedback on the VirtualDisplay
        stats.lastTouchPoint?.let { (tx, ty) ->
            Canvas(modifier = Modifier.fillMaxSize()) {
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
