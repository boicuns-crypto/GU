package com.example.core.usb

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.carlife.CarLifeHardKeyPacket
import com.example.core.carlife.CarLifeTouchPacket
import com.example.core.projection.CockpitProjectionManager
import com.example.core.projection.MediaProjectionTokenStore
import com.example.core.projection.ProjectionPipelineStats
import com.example.protocol.carlife.CarLifeChannel
import com.example.protocol.carlife.CarLifeControlSessionState
import com.example.protocol.carlife.CarLifeControlStateMachine
import com.example.protocol.carlife.CarLifeFrame
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeInputEvent
import com.example.protocol.carlife.CarLifeMsgType
import com.example.protocol.carlife.CarLifeTouchDecoder
import com.example.protocol.carlife.CarLifeTouchEventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Diagnostic log entry representing a captured or injected CarLife binary packet.
 */
data class PacketLogEntry(
    val id: Long,
    val timestampFormatted: String,
    val direction: String,
    val channel: CarLifeChannel,
    val headerSizeBytes: Int,
    val payloadSizeBytes: Int,
    val mediaTimestampMs: Long,
    val serviceName: String?,
    val hexPreview: String
)

/**
 * Profile candidate tested by the Auto-Tester loop to unlock the Head Unit 90% screen.
 */
data class AutoTestProfile(
    val stepNumber: Int,
    val label: String,
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val useNestedVideoHeader: Boolean = false,
    val forceAudioMultiplexerInit: Boolean = false
)

/**
 * Live progress state of the Auto-Tester (`Автоподбор параметров ГУ`).
 */
data class AutoTestState(
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val activeProfileLabel: String = "800×480 • 1.2 Мбит/с • CBR 30fps • Direct 12B + Audio Init",
    val statusMessage: String = "Авто-тестер отключён: зафиксирован профиль Jetta VS7 (800×480 • 1.2 Мбит/с • CBR 30fps • Direct 12B + Audio Init)",
    val matchedProfileLabel: String? = "800×480 • 1.2 Мбит/с • CBR 30fps • Direct 12B + Audio Init",
    val audioInitForced: Boolean = true
)

/**
 * Real-time state exposed by [BridgeForegroundService] to the diagnostic UI and protocol modules.
 */
data class BridgeRuntimeState(
    val isServiceRunning: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val transportState: UsbTransportState = UsbTransportState.Disconnected,
    val controlSession: CarLifeControlSessionState = CarLifeControlSessionState(),
    val projectionStats: ProjectionPipelineStats = ProjectionPipelineStats(),
    val isVideoChannelReadyForStreaming: Boolean = false,
    val useNestedVideoMsgHeader: Boolean = false,
    val configuredWidth: Int = 800,
    val configuredHeight: Int = 480,
    val configuredBitrateBps: Int = 1_200_000,
    val autoTestState: AutoTestState = AutoTestState(),
    val lastTouchOrKeyEvent: String = "None — Waiting for Touch/Hardkey Channel (0x0006)",
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxFrames: Long = 0L,
    val txFrames: Long = 0L,
    val channelFrameCounts: Map<CarLifeChannel, Long> = CarLifeChannel.entries.associateWith { 0L },
    val commandPacketLogs: List<PacketLogEntry> = emptyList(),
    val videoPacketLogs: List<PacketLogEntry> = emptyList(),
    val packetLogs: List<PacketLogEntry> = emptyList(),
    val lastProtocolEvent: String = "Idle — Waiting for Jetta VS7 (MQB) Baidu CarLife USB AOA attachment"
)

/**
 * Process-wide reactive telemetry bus so [MainActivity] and protocol engines can observe
 * live USB AOA state, `CockpitProjectionManager` H.264 stats, Touch/Hardkey events, and binary frame dumps.
 */
object BridgeTelemetryBus {
    private val _runtimeState = MutableStateFlow(BridgeRuntimeState())
    val runtimeState: StateFlow<BridgeRuntimeState> = _runtimeState.asStateFlow()

    private var logSequence = 0L

    fun updateState(transform: (BridgeRuntimeState) -> BridgeRuntimeState) {
        _runtimeState.update(transform)
    }

    fun recordFrame(
        direction: String,
        frame: CarLifeFrame,
        rxBytesTotal: Long? = null,
        txBytesTotal: Long? = null,
        rxFramesTotal: Long? = null,
        txFramesTotal: Long? = null,
        protocolNote: String? = null,
        serviceNameOverride: String? = null
    ) {
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val cmdMsg = frame.parseCommandMessageOrNull()
        val isVideo = frame.channel == CarLifeChannel.VIDEO
        val isAoA20BVideo = isVideo && !frame.header.includeOuterTimestamp && frame.payload.size >= 12
        val effectiveHeaderBytes = if (isAoA20BVideo) 20 else frame.header.headerSizeBytes
        val effectivePayloadBytes = if (isAoA20BVideo) (frame.payload.size - 12) else frame.payload.size
        val hexMaxBytes = if (!isVideo || direction.startsWith("RX")) 256 else 24

        val entry = PacketLogEntry(
            id = ++logSequence,
            timestampFormatted = timeFmt,
            direction = direction,
            channel = frame.channel,
            headerSizeBytes = effectiveHeaderBytes,
            payloadSizeBytes = effectivePayloadBytes,
            mediaTimestampMs = frame.header.timestamp,
            serviceName = serviceNameOverride ?: cmdMsg?.serviceName,
            hexPreview = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = hexMaxBytes)
        )

        _runtimeState.update { current ->
            val updatedChannelCounts = current.channelFrameCounts.toMutableMap()
            updatedChannelCounts[frame.channel] = (updatedChannelCounts[frame.channel] ?: 0L) + 1L

            // Task 5: Strictly separate Tab 1 (Commands 0x0001 & 0x0006 RX/TX, up to 200 items, NO video)
            // from Tab 2 (Video stream 0x0002, up to 60 items).
            val updatedCommandLogs = if (!isVideo) {
                (listOf(entry) + current.commandPacketLogs).take(200)
            } else {
                current.commandPacketLogs
            }

            val updatedVideoLogs = if (isVideo) {
                (listOf(entry) + current.videoPacketLogs).take(60)
            } else {
                current.videoPacketLogs
            }

            val updatedCombined = (listOf(entry) + current.packetLogs).take(200)

            current.copy(
                rxBytes = rxBytesTotal ?: current.rxBytes,
                txBytes = txBytesTotal ?: current.txBytes,
                rxFrames = rxFramesTotal ?: current.rxFrames,
                txFrames = txFramesTotal ?: current.txFrames,
                channelFrameCounts = updatedChannelCounts,
                commandPacketLogs = updatedCommandLogs,
                videoPacketLogs = updatedVideoLogs,
                packetLogs = updatedCombined,
                lastProtocolEvent = protocolNote ?: current.lastProtocolEvent
            )
        }
    }

    fun recordVideoFrameCountersOnly(
        rxBytesTotal: Long? = null,
        txBytesTotal: Long? = null,
        rxFramesTotal: Long? = null,
        txFramesTotal: Long? = null
    ) {
        _runtimeState.update { current ->
            val updatedChannelCounts = current.channelFrameCounts.toMutableMap()
            updatedChannelCounts[CarLifeChannel.VIDEO] =
                (updatedChannelCounts[CarLifeChannel.VIDEO] ?: 0L) + 1L
            current.copy(
                rxBytes = rxBytesTotal ?: current.rxBytes,
                txBytes = txBytesTotal ?: current.txBytes,
                rxFrames = rxFramesTotal ?: current.rxFrames,
                txFrames = txFramesTotal ?: current.txFrames,
                channelFrameCounts = updatedChannelCounts
            )
        }
    }

    fun clearLogs() {
        _runtimeState.update {
            it.copy(
                commandPacketLogs = emptyList(),
                videoPacketLogs = emptyList(),
                packetLogs = emptyList(),
                channelFrameCounts = CarLifeChannel.entries.associateWith { 0L },
                lastProtocolEvent = "Packet log cleared"
            )
        }
    }
}

/**
 * Foreground Service (`connectedDevice|microphone` type) with `PARTIAL_WAKE_LOCK` orchestrating:
 * - **Core 1**: [UsbAccessoryTransport] + [CarLifeControlStateMachine] (USB AOA + Control Handshake + Heartbeat)
 * - **Core 2**: [CockpitProjectionManager] (`MediaCodec` H.264 + `VirtualDisplay` + `CockpitPresentation` + `dispatchTouchEvent`)
 */
class BridgeForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): BridgeForegroundService = this@BridgeForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoTxDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarLifeVideoTxFifo").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var usbTransport: UsbAccessoryTransport
        private set

    lateinit var projectionManager: CockpitProjectionManager
        private set

    private data class QueuedVideoNal(
        val nalBytes: ByteArray,
        val timestampMs: Long,
        val isKeyFrame: Boolean
    )

    private val videoTxQueue = Channel<QueuedVideoNal>(capacity = Channel.UNLIMITED)
    private val controlStateMachine = CarLifeControlStateMachine()
    private val videoPacketCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Strict gate for channel `0x0002` H.264 streaming:
     * MUST be `false` on `VirtualDisplay` start and on USB AOA attachment.
     * Switched to `true` ONLY after `MSG_CMD_VIDEO_ENCODER_INIT (0x00010007)` arrives on `0x0001`
     * and `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008)` is transmitted in response.
     */
    @Volatile
    var isVideoChannelReadyForStreaming: Boolean = false
        private set

    @Volatile
    private var activeWidth: Int = 800

    @Volatile
    private var activeHeight: Int = 480

    @Volatile
    private var activeBitrateBps: Int = 1_200_000

    @Volatile
    private var hasSentMdInfoInSession: Boolean = false

    @Volatile
    private var hasSentAudioInitInSession: Boolean = false

    @Volatile
    private var forceAudioInitForSession: Boolean = true

    private val huConfirmationSeq = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var lastHuConfirmationSource: String = ""

    @Volatile
    private var lastVideoTxElapsedMs: Long = 0L

    private var mdInfoAutoStartJob: kotlinx.coroutines.Job? = null
    private var autoTestJob: kotlinx.coroutines.Job? = null

    private fun clearPendingVideoTxQueue() {
        while (videoTxQueue.tryReceive().isSuccess) {
            // Drain stale video NALs from closed/reset sessions
        }
    }

    private fun setVideoStreamingReady(ready: Boolean) {
        isVideoChannelReadyForStreaming = ready
        if (!ready) {
            clearPendingVideoTxQueue()
        }
        if (::projectionManager.isInitialized) {
            projectionManager.setVideoChannelReady(ready)
        }
        BridgeTelemetryBus.updateState {
            it.copy(isVideoChannelReadyForStreaming = ready)
        }
    }

    private fun resetVideoLogFlags() {
        videoPacketCounter.set(0L)
        clearPendingVideoTxQueue()
    }

    /**
     * Wraps a SINGLE H.264 NAL unit (`SPS`, `PPS`, `IDR`, or `P-slice`) normalized to 4-byte
     * Annex-B `00 00 00 01` into its own 20-byte CarLife `0x0002` video frame (`[20B header][00 00 00 01 NAL]`)
     * and transmits it over USB AOA. Never merges `SPS`, `PPS`, and `IDR` into a single packet.
     */
    private suspend fun sendVideoFrame(
        nalBytes: ByteArray,
        timestampMs: Long,
        isKeyFrame: Boolean = false
    ): Boolean {
        if (!isVideoChannelReadyForStreaming || usbTransport.state.value !is UsbTransportState.Connected) {
            return false
        }
        val annexB4Nal = CarLifeFrameCodec.ensureAnnexBStartCode(nalBytes)
        val nalType = if (annexB4Nal.size > 4) (annexB4Nal[4].toInt() and 0x1F) else 0
        val isConfigNal = nalType == 7 || nalType == 8
        val isIdr = !isConfigNal && (isKeyFrame || nalType == 5)
        val pktIndex = videoPacketCounter.incrementAndGet()

        val videoFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = annexB4Nal,
            timestampMs = timestampMs
        )
        val sentOk = usbTransport.sendFrameImmediate(videoFrame)
        if (!sentOk) return false
        lastVideoTxElapsedMs = SystemClock.elapsedRealtime()

        // Log all SPS/PPS, all IDR keyframes, and every 15th Slice to the dedicated Video Tab (0x0002)
        val shouldLogToVideoTab = isConfigNal || isIdr || pktIndex <= 6L || (pktIndex % 15L == 0L)
        if (shouldLogToVideoTab) {
            val nalLabel = when (nalType) {
                7 -> "SPS #$pktIndex (NAL=7)"
                8 -> "PPS #$pktIndex (NAL=8)"
                5 -> "IDR KeyFrame #$pktIndex (NAL=5)"
                else -> if (isIdr) "IDR KeyFrame #$pktIndex" else "H.264 Slice #$pktIndex"
            }
            val hdrMode = if (CarLifeFrameCodec.useNestedVideoMsgHeader) "Режим 1: AOA 20B" else "Режим 2: Direct 12B"
            BridgeTelemetryBus.recordFrame(
                direction = "TX (S26U -> HU)",
                frame = videoFrame,
                rxBytesTotal = usbTransport.rxBytes,
                txBytesTotal = usbTransport.txBytes,
                rxFramesTotal = usbTransport.rxFrames,
                txFramesTotal = usbTransport.txFrames,
                protocolNote = "TX H.264 Video (0x0002 $hdrMode) $nalLabel (${annexB4Nal.size}B, ts=${timestampMs}ms)",
                serviceNameOverride = nalLabel
            )
        } else {
            BridgeTelemetryBus.recordVideoFrameCountersOnly(
                rxBytesTotal = usbTransport.rxBytes,
                txBytesTotal = usbTransport.txBytes,
                rxFramesTotal = usbTransport.rxFrames,
                txFramesTotal = usbTransport.txFrames
            )
        }
        return true
    }

    /**
     * Mandatory Jetta VS7 Audio-Init before video streaming:
     * Immediately after `MSG_CMD_MD_INFO (0x00018003)`, sends empty 12-byte audio frames
     * with a valid `SystemClock.uptimeMillis()` timestamp:
     * - `0x0003` (Media Audio): `[00 00 00 03][00 00 00 00][ts (4B)]`
     * - `0x0004` (TTS Audio): `[00 00 00 04][00 00 00 00][ts (4B)]`
     */
    private suspend fun sendAudioMultiplexerInitPackets() {
        if (usbTransport.state.value !is UsbTransportState.Connected) return
        val ts = (SystemClock.uptimeMillis() and 0x7FFF_FFFFL).toInt()
        val audioInitFrames = CarLifeFrameCodec.createAudioMultiplexerInitFrames(timestampMs = ts.toLong())
        for (audioFrame in audioInitFrames) {
            if (usbTransport.sendFrameImmediate(audioFrame)) {
                BridgeTelemetryBus.recordFrame(
                    direction = "TX (S26U -> HU)",
                    frame = audioFrame,
                    rxBytesTotal = usbTransport.rxBytes,
                    txBytesTotal = usbTransport.txBytes,
                    rxFramesTotal = usbTransport.rxFrames,
                    txFramesTotal = usbTransport.txFrames,
                    protocolNote = "Mandatory Audio-Init (0x%04X): [12B HDR | size=0, ts=${ts}ms]".format(audioFrame.channel.id),
                    serviceNameOverride = "AUDIO_INIT (0x%04X)".format(audioFrame.channel.id)
                )
            }
        }
        hasSentAudioInitInSession = true
    }

    private fun scheduleMdInfoAutoStartWatchdog(reason: String) {
        mdInfoAutoStartJob?.cancel()
        mdInfoAutoStartJob = serviceScope.launch {
            kotlinx.coroutines.delay(120L)
            if (usbTransport.state.value is UsbTransportState.Connected && !isVideoChannelReadyForStreaming) {
                triggerAutoVideoEncoderStart(reason)
            }
        }
    }

    /**
     * Auto-start chain triggered ~120ms after `MSG_CMD_MD_INFO (0x00018003)`:
     * 1. Mandatory `AUDIO_INIT (0x0003 & 0x0004)` with valid `SystemClock.uptimeMillis()` timestamp.
     * 2. `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008, 800x480@30fps)` & `MSG_CMD_VIDEO_ENCODER_START_DONE (0x0001800A)`.
     * 3. `MSG_CMD_FOREGROUND (0x00018025)` & `MSG_CMD_SCREEN_ON (0x00018018)`.
     * 4. Sets `isVideoChannelReadyForStreaming = true` and requests `SYNC_FRAME` from `MediaCodec`.
     */
    private suspend fun triggerAutoVideoEncoderStart(reason: String) {
        if (usbTransport.state.value !is UsbTransportState.Connected) return

        val targetW = activeWidth.coerceIn(480, 3840)
        val targetH = activeHeight.coerceIn(272, 2160)
        val targetFps = 30

        controlStateMachine.setPreferredResolution(targetW, targetH)
        val autoTransition = controlStateMachine.buildAutoVideoEncoderInitAndStartFrames(
            width = targetW,
            height = targetH,
            fps = targetFps,
            reason = reason
        )

        val projStats = projectionManager.stats.value
        if (!projStats.isRunning || projStats.width != targetW || projStats.height != targetH) {
            projectionManager.startProjection(
                width = targetW,
                height = targetH,
                frameRate = targetFps,
                bitRate = activeBitrateBps
            )
        }

        if (!hasSentAudioInitInSession || forceAudioInitForSession) {
            sendAudioMultiplexerInitPackets()
        }

        for (reply in autoTransition.outboundFrames) {
            if (usbTransport.sendFrameImmediate(reply)) {
                BridgeTelemetryBus.recordFrame(
                    direction = "TX (S26U -> HU)",
                    frame = reply,
                    rxBytesTotal = usbTransport.rxBytes,
                    txBytesTotal = usbTransport.txBytes,
                    rxFramesTotal = usbTransport.rxFrames,
                    txFramesTotal = usbTransport.txFrames,
                    protocolNote = "Auto-Start: ${reply.parseCommandMessageOrNull()?.serviceName ?: "Frame"} ($targetW×$targetH@${targetFps}fps)"
                )
            }
        }

        resetVideoLogFlags()
        setVideoStreamingReady(true)
        projectionManager.unlockVideoChannelAndRequestSyncFrame()
    }

    /**
     * Auto-Tester loop is disabled: enforces the verified Jetta VS7 working profile
     * (`800×480`, `30fps`, `800 kbps CBR`, `AOA 20B` + mandatory Audio Init `0x0003`/`0x0004`)
     * without cycling or overriding settings.
     */
    private fun startAutoTestCycle() {
        autoTestJob?.cancel()
        autoTestJob = null
        mdInfoAutoStartJob?.cancel()

        CarLifeFrameCodec.useNestedVideoMsgHeader = false
        activeWidth = 800
        activeHeight = 480
        activeBitrateBps = 1_200_000
        forceAudioInitForSession = true
        controlStateMachine.setPreferredResolution(800, 480)

        BridgeTelemetryBus.updateState { current ->
            current.copy(
                configuredWidth = 800,
                configuredHeight = 480,
                configuredBitrateBps = 1_200_000,
                useNestedVideoMsgHeader = false,
                autoTestState = AutoTestState(
                    isRunning = false,
                    currentStep = 0,
                    totalSteps = 0,
                    activeProfileLabel = "800×480 • 1.2 Мбит/с • CBR 30fps • Direct 12B + Audio Init",
                    statusMessage = "Авто-тестер отключён: зафиксирован профиль Jetta VS7 (800×480 • 1.2 Мбит/с • CBR 30fps • Direct 12B + Audio Init)",
                    matchedProfileLabel = "800×480 • 1.2 Мбит/с • CBR 30fps • Direct 12B + Audio Init",
                    audioInitForced = true
                ),
                lastProtocolEvent = "Locked Jetta VS7 Profile: 800×480 @ 1.2 Mbps CBR 30fps, Direct 12B + Audio Init"
            )
        }

        serviceScope.launch {
            projectionManager.startProjection(
                width = 800,
                height = 480,
                frameRate = 30,
                bitRate = 1_200_000
            )
            if (usbTransport.state.value is UsbTransportState.Connected) {
                sendAudioMultiplexerInitPackets()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        // Default Jetta VS7 Mode 2: Direct 12B ([00 00 00 02][nalSize][ts] + [00 00 00 01 Annex-B])
        CarLifeFrameCodec.useNestedVideoMsgHeader = false
        isVideoChannelReadyForStreaming = false

        usbTransport = UsbAccessoryTransport(
            context = applicationContext,
            ioDispatcher = Dispatchers.IO,
            externalScope = serviceScope
        )

        // Single sequential FIFO consumer for 0x0002 packets so SPS -> PPS -> IDR never reorder
        serviceScope.launch(videoTxDispatcher) {
            for (queued in videoTxQueue) {
                if (!isVideoChannelReadyForStreaming || usbTransport.state.value !is UsbTransportState.Connected) {
                    continue
                }
                sendVideoFrame(
                    nalBytes = queued.nalBytes,
                    timestampMs = queued.timestampMs,
                    isKeyFrame = queued.isKeyFrame
                )
            }
        }

        projectionManager = CockpitProjectionManager(
            context = applicationContext,
            onEncodedVideoNal = { nalBytes, timestampMs, isKeyFrame ->
                // STRICT GATE: While isVideoChannelReadyForStreaming == false, NEVER send 0x0002 packets
                // to usbOutputStream and NEVER increment 0x0002 packet counters!
                if (!isVideoChannelReadyForStreaming || usbTransport.state.value !is UsbTransportState.Connected) {
                    return@CockpitProjectionManager
                }

                // Manually parse Annex-B start codes (00 00 00 01 and 00 00 01 -> 00 00 00 01) and enqueue
                // EACH individual NAL unit (SPS, PPS, IDR, Slice) for separate sendVideoFrame() wrapping.
                val singleNalUnits = CarLifeFrameCodec.splitAnnexBNalUnits(nalBytes)
                for (singleNal in singleNalUnits) {
                    val nalType = if (singleNal.size > 4) (singleNal[4].toInt() and 0x1F) else 0
                    val isIdr = nalType == 5 || (nalType != 7 && nalType != 8 && isKeyFrame)
                    videoTxQueue.trySend(
                        QueuedVideoNal(
                            nalBytes = singleNal,
                            timestampMs = timestampMs,
                            isKeyFrame = isIdr
                        )
                    )
                }
            },
            externalScope = serviceScope
        )
        projectionManager.setVideoChannelReady(false)

        BridgeTelemetryBus.updateState {
            it.copy(
                isServiceRunning = true,
                wakeLockHeld = wakeLock?.isHeld == true,
                isVideoChannelReadyForStreaming = false,
                useNestedVideoMsgHeader = false,
                configuredWidth = activeWidth,
                configuredHeight = activeHeight,
                configuredBitrateBps = activeBitrateBps,
                lastProtocolEvent = "BridgeForegroundService active (800×480 @ 1.2Mbps CBR 30fps, Direct 12B; 0x0002 gated until MD_INFO+AudioInit)"
            )
        }

        // Immediately start VirtualDisplay + H.264 MediaCodec (800x480, 30fps, 800 kbps CBR, AVCProfileBaseline)
        // while keeping 0x0002 streaming locked until USB AOA handshake completes.
        serviceScope.launch {
            projectionManager.startProjection(
                width = activeWidth,
                height = activeHeight,
                frameRate = 30,
                bitRate = activeBitrateBps
            )
        }

        // Observe Projection Pipeline stats
        serviceScope.launch {
            projectionManager.stats.collect { projStats ->
                BridgeTelemetryBus.updateState { current ->
                    current.copy(projectionStats = projStats)
                }
            }
        }

        // Observe Control Channel State Machine
        serviceScope.launch {
            controlStateMachine.sessionState.collect { session ->
                BridgeTelemetryBus.updateState { current ->
                    current.copy(controlSession = session)
                }
            }
        }

        // Observe USB transport state changes
        serviceScope.launch {
            usbTransport.state.collect { newState ->
                // On any new USB AOA connection or disconnect, lock 0x0002 streaming until 0x0001 handshake + AudioInit complete!
                mdInfoAutoStartJob?.cancel()
                mdInfoAutoStartJob = null
                hasSentMdInfoInSession = false
                hasSentAudioInitInSession = false
                setVideoStreamingReady(false)
                resetVideoLogFlags()
                if (newState is UsbTransportState.Disconnected || newState is UsbTransportState.Connecting) {
                    controlStateMachine.reset()
                    controlStateMachine.setPreferredResolution(activeWidth, activeHeight)
                }
                BridgeTelemetryBus.updateState { current ->
                    current.copy(
                        transportState = newState,
                        isVideoChannelReadyForStreaming = false,
                        rxBytes = usbTransport.rxBytes,
                        txBytes = usbTransport.txBytes,
                        rxFrames = usbTransport.rxFrames,
                        txFrames = usbTransport.txFrames,
                        lastProtocolEvent = when (newState) {
                            is UsbTransportState.Connected ->
                                "AOA Connected: ${newState.accessory.manufacturer} ${newState.accessory.model} (fd=${newState.fdNumber}) — Awaiting 0x0001 Handshake"
                            is UsbTransportState.Connecting ->
                                "Opening Direct-IO FileChannel for ${newState.accessory.manufacturer} ${newState.accessory.model}..."
                            is UsbTransportState.WaitingForPermission ->
                                "Waiting for USB AOA permission: ${newState.accessory.manufacturer} ${newState.accessory.model}"
                            is UsbTransportState.Error ->
                                "AOA Status: ${newState.reason}"
                            UsbTransportState.Disconnected ->
                                "USB AOA Disconnected (0x0002 Video Gate Locked)"
                        }
                    )
                }
                updateForegroundNotification(newState)
            }
        }

        // Demultiplex incoming CarLife frames from Jetta VS7 Head Unit
        serviceScope.launch {
            usbTransport.incomingFrames.collect { frame ->
                handleIncomingCarLifeFrame(frame)
            }
        }

        // Periodic 1,000ms Heartbeat check ONLY when 0x0002 is unlocked and idle (>=2s since last video NAL TX)
        serviceScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                val nowElapsed = SystemClock.elapsedRealtime()
                val isVideoIdle = lastVideoTxElapsedMs == 0L || (nowElapsed - lastVideoTxElapsedMs) >= 2_000L
                if (usbTransport.state.value is UsbTransportState.Connected &&
                    isVideoChannelReadyForStreaming &&
                    isVideoIdle
                ) {
                    val hb = controlStateMachine.createHeartbeatFrame()
                    usbTransport.sendFrameImmediate(hb)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(usbTransport.state.value)

        when (intent?.action) {
            ACTION_ATTACH_ACCESSORY -> {
                val accessory = extractAccessory(intent)
                serviceScope.launch {
                    usbTransport.connect(accessory)
                }
            }

            ACTION_SCAN_AND_CONNECT -> {
                serviceScope.launch {
                    usbTransport.connect(usbTransport.findCarLifeAccessory())
                }
            }

            ACTION_GRANT_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val requestedW = intent.getIntExtra(EXTRA_WIDTH, activeWidth).coerceIn(480, 3840)
                val requestedH = intent.getIntExtra(EXTRA_HEIGHT, activeHeight).coerceIn(272, 2160)
                val requestedBitrate = intent.getIntExtra(EXTRA_BITRATE, activeBitrateBps).coerceIn(400_000, 12_000_000)
                activeWidth = requestedW
                activeHeight = requestedH
                activeBitrateBps = requestedBitrate
                controlStateMachine.setPreferredResolution(requestedW, requestedH)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    MediaProjectionTokenStore.saveToken(resultCode, resultData)
                    promoteToForeground(usbTransport.state.value)
                    val targetW = activeWidth
                    val targetH = activeHeight
                    val targetFps = 30
                    val wasHandshakeReady = (usbTransport.state.value is UsbTransportState.Connected) &&
                        (isVideoChannelReadyForStreaming ||
                            controlStateMachine.sessionState.value.phase == com.example.protocol.carlife.CarLifeSessionPhase.VIDEO_STREAMING)
                    if (!wasHandshakeReady) {
                        setVideoStreamingReady(false)
                    }
                    serviceScope.launch {
                        resetVideoLogFlags()
                        projectionManager.setVideoChannelReady(wasHandshakeReady)
                        projectionManager.startProjection(
                            width = targetW,
                            height = targetH,
                            frameRate = targetFps,
                            bitRate = activeBitrateBps
                        )
                        if (wasHandshakeReady) {
                            projectionManager.unlockVideoChannelAndRequestSyncFrame()
                        }
                    }
                    BridgeTelemetryBus.updateState {
                        it.copy(
                            configuredWidth = targetW,
                            configuredHeight = targetH,
                            configuredBitrateBps = activeBitrateBps,
                            lastProtocolEvent = if (wasHandshakeReady) {
                                "MediaProjection granted — Streaming 0x0002 ($targetW×$targetH @ ${activeBitrateBps / 1000} kbps)"
                            } else {
                                "MediaProjection granted ($targetW×$targetH) — SPS/PPS cached in memory"
                            }
                        )
                    }
                }
            }

            ACTION_SET_VIDEO_CONFIG -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, activeWidth).coerceIn(480, 3840)
                val height = intent.getIntExtra(EXTRA_HEIGHT, activeHeight).coerceIn(272, 2160)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, activeBitrateBps).coerceIn(400_000, 12_000_000)
                activeWidth = width
                activeHeight = height
                activeBitrateBps = bitrate
                controlStateMachine.setPreferredResolution(width, height)
                BridgeTelemetryBus.updateState {
                    it.copy(
                        configuredWidth = width,
                        configuredHeight = height,
                        configuredBitrateBps = bitrate,
                        lastProtocolEvent = "Video Config Set: ${width}×${height} @ ${bitrate / 1000} kbps"
                    )
                }
                if (projectionManager.stats.value.isRunning) {
                    val wasReady = isVideoChannelReadyForStreaming
                    serviceScope.launch {
                        projectionManager.startProjection(
                            width = width,
                            height = height,
                            frameRate = 30,
                            bitRate = bitrate
                        )
                        if (wasReady && usbTransport.state.value is UsbTransportState.Connected) {
                            triggerAutoVideoEncoderStart("Resolution/Bitrate Updated (${width}×${height})")
                        }
                    }
                }
            }

            ACTION_START_PROJECTION -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, activeWidth).coerceIn(480, 3840)
                val height = intent.getIntExtra(EXTRA_HEIGHT, activeHeight).coerceIn(272, 2160)
                val fps = intent.getIntExtra(EXTRA_FPS, 30)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, activeBitrateBps).coerceIn(400_000, 12_000_000)
                activeWidth = width
                activeHeight = height
                activeBitrateBps = bitrate
                controlStateMachine.setPreferredResolution(width, height)
                val wasHandshakeReady = (usbTransport.state.value is UsbTransportState.Connected) &&
                    (isVideoChannelReadyForStreaming ||
                        controlStateMachine.sessionState.value.phase == com.example.protocol.carlife.CarLifeSessionPhase.VIDEO_STREAMING)
                if (!wasHandshakeReady) {
                    setVideoStreamingReady(false)
                }
                BridgeTelemetryBus.updateState {
                    it.copy(
                        configuredWidth = width,
                        configuredHeight = height,
                        configuredBitrateBps = bitrate
                    )
                }
                serviceScope.launch {
                    resetVideoLogFlags()
                    projectionManager.setVideoChannelReady(wasHandshakeReady)
                    projectionManager.startProjection(
                        width = width,
                        height = height,
                        frameRate = fps,
                        bitRate = bitrate
                    )
                    if (wasHandshakeReady) {
                        projectionManager.unlockVideoChannelAndRequestSyncFrame()
                    }
                }
            }

            ACTION_STOP_PROJECTION -> {
                setVideoStreamingReady(false)
                serviceScope.launch {
                    projectionManager.stopProjection()
                }
            }

            ACTION_INJECT_TEST_TOUCH -> {
                val x = intent.getIntExtra(EXTRA_TOUCH_X, 240)
                val y = intent.getIntExtra(EXTRA_TOUCH_Y, 240)
                val w = intent.getIntExtra(EXTRA_WIDTH, 800)
                val h = intent.getIntExtra(EXTRA_HEIGHT, 480)
                serviceScope.launch {
                    val pkt = CarLifeTouchPacket(
                        androidAction = MotionEvent.ACTION_DOWN,
                        rawX = x,
                        rawY = y,
                        huWidth = w,
                        huHeight = h,
                        synthesizeClickUp = true
                    )
                    projectionManager.injectTouchPacket(pkt)
                }
            }

            ACTION_SEND_VERSION_MATCH -> {
                serviceScope.launch {
                    val hb = controlStateMachine.createHeartbeatFrame()
                    if (usbTransport.sendFrameImmediate(hb)) {
                        BridgeTelemetryBus.recordFrame(
                            direction = "TX (S26U -> HU)",
                            frame = hb,
                            rxBytesTotal = usbTransport.rxBytes,
                            txBytesTotal = usbTransport.txBytes,
                            rxFramesTotal = usbTransport.rxFrames,
                            txFramesTotal = usbTransport.txFrames,
                            protocolNote = "TX MSG_VIDEO_HEARTBEAT"
                        )
                    }
                }
            }

            ACTION_SEND_VIDEO_HEARTBEAT -> {
                serviceScope.launch {
                    val hbFrame = CarLifeFrameCodec.createVideoFrame(
                        nalData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x09, 0x10),
                        timestampMs = System.currentTimeMillis() and 0xFFFF_FFFFL
                    )
                    if (usbTransport.sendFrameImmediate(hbFrame)) {
                        BridgeTelemetryBus.recordFrame(
                            direction = "TX (S26U -> HU)",
                            frame = hbFrame,
                            rxBytesTotal = usbTransport.rxBytes,
                            txBytesTotal = usbTransport.txBytes,
                            rxFramesTotal = usbTransport.rxFrames,
                            txFramesTotal = usbTransport.txFrames,
                            protocolNote = "Sent 12-byte Video NAL Access Unit Delimiter"
                        )
                    }
                }
            }

            ACTION_TOGGLE_VIDEO_HEADER_MODE -> {
                val newMode = if (intent.hasExtra(EXTRA_USE_NESTED_VIDEO_HEADER)) {
                    intent.getBooleanExtra(EXTRA_USE_NESTED_VIDEO_HEADER, false)
                } else {
                    !CarLifeFrameCodec.useNestedVideoMsgHeader
                }
                CarLifeFrameCodec.useNestedVideoMsgHeader = newMode
                resetVideoLogFlags()
                if (isVideoChannelReadyForStreaming) {
                    projectionManager.requestInstantIdrKeyFrame()
                }
                BridgeTelemetryBus.updateState {
                    it.copy(
                        useNestedVideoMsgHeader = newMode,
                        lastProtocolEvent = if (newMode) {
                            "Video 0x0002 Framing: Режим 1: AOA 20B (8B AOA + 12B 0x00028001) active"
                        } else {
                            "Video 0x0002 Framing: Режим 2: Direct 12B ([00 00 00 02][nalSize][ts]) active"
                        }
                    )
                }
            }

            ACTION_START_AUTO_TEST -> {
                startAutoTestCycle()
            }

            ACTION_STOP_AUTO_TEST -> {
                autoTestJob?.cancel()
                autoTestJob = null
                BridgeTelemetryBus.updateState { current ->
                    current.copy(
                        autoTestState = current.autoTestState.copy(
                            isRunning = false,
                            statusMessage = "Автоподбор остановлен пользователем (Шаг ${current.autoTestState.currentStep})"
                        )
                    )
                }
            }

            ACTION_DISCONNECT_STOP -> {
                autoTestJob?.cancel()
                autoTestJob = null
                setVideoStreamingReady(false)
                serviceScope.launch {
                    projectionManager.stopProjection()
                    usbTransport.closeSession("Stopped by user")
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.launch {
            projectionManager.stopProjection()
            usbTransport.shutdown()
        }
        BridgeTelemetryBus.updateState {
            it.copy(
                isServiceRunning = false,
                wakeLockHeld = false,
                transportState = UsbTransportState.Disconnected,
                lastProtocolEvent = "BridgeForegroundService stopped"
            )
        }
        runCatching { videoTxQueue.close() }
        serviceScope.cancel()
        runCatching { videoTxDispatcher.close() }
        super.onDestroy()
    }

    private suspend fun handleIncomingCarLifeFrame(frame: CarLifeFrame) {
        when (frame.channel) {
            CarLifeChannel.CONTROL -> {
                val cmd = frame.parseCommandMessageOrNull()
                val transition = controlStateMachine.onControlFrame(frame)
                BridgeTelemetryBus.recordFrame(
                    direction = "RX (HU -> S26U)",
                    frame = frame,
                    rxBytesTotal = usbTransport.rxBytes,
                    txBytesTotal = usbTransport.txBytes,
                    rxFramesTotal = usbTransport.rxFrames,
                    txFramesTotal = usbTransport.txFrames,
                    protocolNote = transition.summary
                )

                // Step 2 (VIDEO_ENCODER_INIT) & fallback guard: send mandatory Audio-Init (0x0003 & 0x0004)
                // BEFORE INIT_DONE (0x00018008) or START_DONE (0x0001800A).
                if ((cmd?.serviceType == CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT ||
                        cmd?.serviceType == CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_ALT ||
                        cmd?.serviceType == CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET ||
                        cmd?.serviceType == CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET_ALT ||
                        cmd?.serviceType == CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START ||
                        cmd?.serviceType == CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_ALT) &&
                    !hasSentAudioInitInSession
                ) {
                    sendAudioMultiplexerInitPackets()
                }

                for (reply in transition.outboundFrames) {
                    if (usbTransport.sendFrameImmediate(reply)) {
                        BridgeTelemetryBus.recordFrame(
                            direction = "TX (S26U -> HU)",
                            frame = reply,
                            rxBytesTotal = usbTransport.rxBytes,
                            txBytesTotal = usbTransport.txBytes,
                            rxFramesTotal = usbTransport.rxFrames,
                            txFramesTotal = usbTransport.txFrames,
                            protocolNote = "Auto-Reply: ${reply.parseCommandMessageOrNull()?.serviceName ?: "Frame"}"
                        )
                    }
                }

                val cmdName = cmd?.serviceName ?: "Control(${frame.payload.size}B)"
                lastHuConfirmationSource = "0x0001 $cmdName"
                huConfirmationSeq.incrementAndGet()
                when (cmd?.serviceType) {
                    CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION,
                    CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION_ALT -> {
                        // Step 1: MATCH_STATUS (0x00018002) + MD_INFO (0x00018003) already sent above.
                        // Lock 0x0002 for 120ms, then automatically execute:
                        // AUDIO_INIT (0x0003/0x0004) -> INIT_DONE + START_DONE -> FOREGROUND + SCREEN_ON -> unlock 0x0002 + SYNC_FRAME!
                        setVideoStreamingReady(false)
                        hasSentMdInfoInSession = true
                        scheduleMdInfoAutoStartWatchdog("Auto-Start 120ms after MSG_CMD_MD_INFO (0x00018003)")
                    }

                    CarLifeMsgType.MSG_CMD_HU_INFO,
                    CarLifeMsgType.MSG_CMD_HU_INFO_ALT -> {
                        hasSentMdInfoInSession = true
                        mdInfoAutoStartJob?.cancel()
                        mdInfoAutoStartJob = null
                        if (!isVideoChannelReadyForStreaming) {
                            triggerAutoVideoEncoderStart("Auto-Start after MSG_CMD_HU_INFO")
                        } else {
                            projectionManager.requestInstantIdrKeyFrame()
                        }
                    }

                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT,
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_ALT,
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET,
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET_ALT -> {
                        mdInfoAutoStartJob?.cancel()
                        mdInfoAutoStartJob = null
                        val targetW = activeWidth.coerceIn(480, 3840)
                        val targetH = activeHeight.coerceIn(272, 2160)
                        controlStateMachine.setPreferredResolution(targetW, targetH)
                        resetVideoLogFlags()
                        val projStats = projectionManager.stats.value
                        if (!projStats.isRunning ||
                            projStats.width != targetW ||
                            projStats.height != targetH
                        ) {
                            projectionManager.startProjection(
                                width = targetW,
                                height = targetH,
                                frameRate = 30,
                                bitRate = activeBitrateBps
                            )
                        }
                        setVideoStreamingReady(true)
                        projectionManager.unlockVideoChannelAndRequestSyncFrame()
                    }

                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START,
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_ALT -> {
                        mdInfoAutoStartJob?.cancel()
                        mdInfoAutoStartJob = null
                        val targetW = activeWidth.coerceIn(480, 3840)
                        val targetH = activeHeight.coerceIn(272, 2160)
                        controlStateMachine.setPreferredResolution(targetW, targetH)
                        if (!projectionManager.stats.value.isRunning ||
                            projectionManager.stats.value.width != targetW ||
                            projectionManager.stats.value.height != targetH
                        ) {
                            projectionManager.startProjection(
                                width = targetW,
                                height = targetH,
                                frameRate = 30,
                                bitRate = activeBitrateBps
                            )
                        }
                        if (!isVideoChannelReadyForStreaming) {
                            setVideoStreamingReady(true)
                            projectionManager.unlockVideoChannelAndRequestSyncFrame()
                        } else {
                            projectionManager.requestInstantIdrKeyFrame()
                        }
                    }

                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE -> {
                        mdInfoAutoStartJob?.cancel()
                        mdInfoAutoStartJob = null
                        setVideoStreamingReady(false)
                    }

                    CarLifeMsgType.MSG_CMD_MODULE_STATUS,
                    CarLifeMsgType.MSG_CMD_MODULE_STATUS_ALT,
                    CarLifeMsgType.MSG_CMD_SCREEN_ON,
                    CarLifeMsgType.MSG_CMD_SCREEN_OFF,
                    CarLifeMsgType.MSG_CMD_SCREEN_USER_PRESENT,
                    CarLifeMsgType.MSG_CMD_FOREGROUND,
                    CarLifeMsgType.MSG_CMD_FOREGROUND_ALT,
                    CarLifeMsgType.MSG_CMD_FOREGROUND_LEGACY,
                    CarLifeMsgType.MSG_CMD_BACKGROUND,
                    CarLifeMsgType.MSG_CMD_GO_TO_DESKTOP,
                    CarLifeMsgType.MSG_CMD_GO_TO_FOREGROUND,
                    CarLifeMsgType.MSG_CMD_STATISTIC_INFO,
                    CarLifeMsgType.MSG_CMD_CAR_VELOCITY,
                    CarLifeMsgType.MSG_CMD_CAR_GPS,
                    CarLifeMsgType.MSG_CMD_CAR_GYROSCOPE,
                    CarLifeMsgType.MSG_CMD_CAR_ACCELERATION,
                    CarLifeMsgType.MSG_CMD_CAR_OIL -> {
                        // Passive notification from HU: if MD_INFO ran and 0x0002 is still locked, let the 120ms auto-start or this trigger unlock it; never loop when already streaming.
                        if (hasSentMdInfoInSession && !isVideoChannelReadyForStreaming) {
                            mdInfoAutoStartJob?.cancel()
                            mdInfoAutoStartJob = null
                            triggerAutoVideoEncoderStart("Auto-Start on HU ${cmd.serviceName}")
                        }
                    }

                    else -> {
                        if (hasSentMdInfoInSession && !isVideoChannelReadyForStreaming) {
                            mdInfoAutoStartJob?.cancel()
                            mdInfoAutoStartJob = null
                            triggerAutoVideoEncoderStart(
                                "Auto-Start on RX Control Packet (${cmd?.serviceName ?: "Payload:${frame.payload.size}B"})"
                            )
                        }
                    }
                }
            }

            CarLifeChannel.TOUCH -> {
                lastHuConfirmationSource = "0x0006 Touch/HardKey"
                huConfirmationSeq.incrementAndGet()
                val session = controlStateMachine.sessionState.value
                val decodedEvent = CarLifeTouchDecoder.decodeTouchChannelFrame(
                    frame = frame,
                    targetWidth = session.negotiatedVideoWidth,
                    targetHeight = session.negotiatedVideoHeight
                )
                when (decodedEvent) {
                    is CarLifeInputEvent.Touch -> {
                        val isClick = decodedEvent.type == CarLifeTouchEventType.SINGLE_CLICK ||
                            decodedEvent.type == CarLifeTouchEventType.DOUBLE_CLICK
                        projectionManager.injectTouchPacket(
                            CarLifeTouchPacket(
                                androidAction = if (isClick) MotionEvent.ACTION_DOWN else decodedEvent.type.androidMotionAction,
                                rawX = decodedEvent.rawX,
                                rawY = decodedEvent.rawY,
                                huWidth = decodedEvent.huWidth,
                                huHeight = decodedEvent.huHeight,
                                synthesizeClickUp = isClick
                            )
                        )
                    }
                    is CarLifeInputEvent.HardKey -> {
                        projectionManager.injectHardKeyPacket(
                            CarLifeHardKeyPacket(
                                carLifeKeyCode = decodedEvent.rawKeyCode,
                                androidKeyCode = decodedEvent.androidKeyCode
                            )
                        )
                    }
                    null -> Unit
                }

                val eventSummary = when (decodedEvent) {
                    is CarLifeInputEvent.Touch ->
                        "Touch ${decodedEvent.type.label}: HU(${decodedEvent.rawX},${decodedEvent.rawY}) -> VirtualDisplay(${decodedEvent.scaledX.toInt()},${decodedEvent.scaledY.toInt()})"
                    is CarLifeInputEvent.HardKey ->
                        "HardKey: ${decodedEvent.mappedKey?.label ?: "0x${decodedEvent.rawKeyCode.toString(16)}"} (Android KeyCode=${decodedEvent.androidKeyCode})"
                    null -> "Raw Touch Frame (${frame.payload.size}B)"
                }
                BridgeTelemetryBus.updateState {
                    it.copy(lastTouchOrKeyEvent = eventSummary)
                }
                BridgeTelemetryBus.recordFrame(
                    direction = "RX (HU -> S26U)",
                    frame = frame,
                    rxBytesTotal = usbTransport.rxBytes,
                    txBytesTotal = usbTransport.txBytes,
                    rxFramesTotal = usbTransport.rxFrames,
                    txFramesTotal = usbTransport.txFrames,
                    protocolNote = eventSummary
                )
            }

            CarLifeChannel.VIDEO,
            CarLifeChannel.MEDIA_AUDIO,
            CarLifeChannel.TTS_AUDIO,
            CarLifeChannel.VR_AUDIO -> {
                BridgeTelemetryBus.recordFrame(
                    direction = "RX (HU -> S26U)",
                    frame = frame,
                    rxBytesTotal = usbTransport.rxBytes,
                    txBytesTotal = usbTransport.txBytes,
                    rxFramesTotal = usbTransport.rxFrames,
                    txFramesTotal = usbTransport.txFrames,
                    protocolNote = "RX ${frame.channel.label}: ts=${frame.header.timestamp}ms, len=${frame.payload.size}B"
                )
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CarLifeBridge::UsbAoaStreamingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun promoteToForeground(state: UsbTransportState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasMpToken = MediaProjectionTokenStore.hasValidToken.value
            val desiredType = if (hasMpToken) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, desiredType)
            } catch (se: SecurityException) {
                Log.w(TAG, "FGS promotion with type=$desiredType failed (${se.message}); falling back to connectedDevice")
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                }.onFailure {
                    runCatching { startForeground(NOTIFICATION_ID, notification) }
                }
            }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun updateForegroundNotification(state: UsbTransportState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: UsbTransportState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_DISCONNECT_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = when (state) {
            is UsbTransportState.Connected ->
                "Connected to ${state.accessory.manufacturer} ${state.accessory.model} (FD #${state.fdNumber})"
            is UsbTransportState.Connecting ->
                "Opening AOA Direct-IO FileChannel (${state.accessory.manufacturer} ${state.accessory.model})..."
            is UsbTransportState.WaitingForPermission ->
                "Awaiting USB AOA permission for ${state.accessory.model}"
            is UsbTransportState.Error ->
                state.reason
            UsbTransportState.Disconnected ->
                "Standby — Ready for Jetta VS7 (MQB) Baidu CarLife Head Unit"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_usb_notification)
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(statusText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_Action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun extractAccessory(intent: Intent): UsbAccessory? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
    }

    companion object {
        private const val TAG = "BridgeForegroundService"
        const val NOTIFICATION_CHANNEL_ID = "carlife_usb_aoa_bridge_channel"
        const val NOTIFICATION_ID = 2601

        const val ACTION_ATTACH_ACCESSORY = "com.example.core.usb.action.ATTACH_ACCESSORY"
        const val ACTION_SCAN_AND_CONNECT = "com.example.core.usb.action.SCAN_AND_CONNECT"
        const val ACTION_GRANT_MEDIA_PROJECTION = "com.example.core.usb.action.GRANT_MEDIA_PROJECTION"
        const val ACTION_SET_VIDEO_CONFIG = "com.example.core.usb.action.SET_VIDEO_CONFIG"
        const val ACTION_START_PROJECTION = "com.example.core.usb.action.START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.example.core.usb.action.STOP_PROJECTION"
        const val ACTION_INJECT_TEST_TOUCH = "com.example.core.usb.action.INJECT_TEST_TOUCH"
        const val ACTION_SEND_VERSION_MATCH = "com.example.core.usb.action.SEND_VERSION_MATCH"
        const val ACTION_SEND_VIDEO_HEARTBEAT = "com.example.core.usb.action.SEND_VIDEO_HEARTBEAT"
        const val ACTION_TOGGLE_VIDEO_HEADER_MODE = "com.example.core.usb.action.TOGGLE_VIDEO_HEADER_MODE"
        const val ACTION_START_AUTO_TEST = "com.example.core.usb.action.START_AUTO_TEST"
        const val ACTION_STOP_AUTO_TEST = "com.example.core.usb.action.STOP_AUTO_TEST"
        const val ACTION_DISCONNECT_STOP = "com.example.core.usb.action.DISCONNECT_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_TOUCH_X = "extra_touch_x"
        const val EXTRA_TOUCH_Y = "extra_touch_y"
        const val EXTRA_USE_NESTED_VIDEO_HEADER = "extra_use_nested_video_header"

        const val AUTO_TEST_STEP_TIMEOUT_MS = 1_500L
        const val AUTO_TEST_STEP_POLL_MS = 50L
        const val AUTO_TEST_STEP_POLL_COUNT = (AUTO_TEST_STEP_TIMEOUT_MS / AUTO_TEST_STEP_POLL_MS).toInt()

        val AUTO_TEST_PROFILES: List<AutoTestProfile> = listOf(
            AutoTestProfile(
                stepNumber = 1,
                label = "800×480 • 1.2 Мбит/с • CBR 30fps • Direct 12B + Audio Init (0x0003/0x0004)",
                width = 800,
                height = 480,
                bitrateBps = 1_200_000,
                useNestedVideoHeader = false,
                forceAudioMultiplexerInit = true
            )
        )

        fun startDefaultProjection(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_WIDTH, 800)
                putExtra(EXTRA_HEIGHT, 480)
                putExtra(EXTRA_FPS, 30)
                putExtra(EXTRA_BITRATE, 1_200_000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun grantMediaProjection(context: Context, resultCode: Int, resultData: Intent) {
            MediaProjectionTokenStore.saveToken(resultCode, resultData)
            val currentState = BridgeTelemetryBus.runtimeState.value
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_GRANT_MEDIA_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_WIDTH, currentState.configuredWidth)
                putExtra(EXTRA_HEIGHT, currentState.configuredHeight)
                putExtra(EXTRA_BITRATE, currentState.configuredBitrateBps)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startWithAccessory(context: Context, accessory: UsbAccessory?) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = if (accessory != null) ACTION_ATTACH_ACCESSORY else ACTION_SCAN_AND_CONNECT
                if (accessory != null) {
                    putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
