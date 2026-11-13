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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Real-time state exposed by [BridgeForegroundService] to the diagnostic UI and protocol modules.
 */
data class BridgeRuntimeState(
    val isServiceRunning: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val transportState: UsbTransportState = UsbTransportState.Disconnected,
    val controlSession: CarLifeControlSessionState = CarLifeControlSessionState(),
    val projectionStats: ProjectionPipelineStats = ProjectionPipelineStats(),
    val useNestedVideoMsgHeader: Boolean = CarLifeFrameCodec.useNestedVideoMsgHeader,
    val lastTouchOrKeyEvent: String = "None — Waiting for Touch/Hardkey Channel (0x0006)",
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxFrames: Long = 0L,
    val txFrames: Long = 0L,
    val channelFrameCounts: Map<CarLifeChannel, Long> = CarLifeChannel.entries.associateWith { 0L },
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
        protocolNote: String? = null
    ) {
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val cmdMsg = frame.parseCommandMessageOrNull()
        val entry = PacketLogEntry(
            id = ++logSequence,
            timestampFormatted = timeFmt,
            direction = direction,
            channel = frame.channel,
            headerSizeBytes = frame.header.headerSizeBytes,
            payloadSizeBytes = frame.payload.size,
            mediaTimestampMs = frame.header.timestamp,
            serviceName = cmdMsg?.serviceName,
            hexPreview = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 16)
        )

        _runtimeState.update { current ->
            val updatedChannelCounts = current.channelFrameCounts.toMutableMap()
            updatedChannelCounts[frame.channel] = (updatedChannelCounts[frame.channel] ?: 0L) + 1L
            val updatedLogs = (listOf(entry) + current.packetLogs).take(60)
            current.copy(
                rxBytes = rxBytesTotal ?: current.rxBytes,
                txBytes = txBytesTotal ?: current.txBytes,
                rxFrames = rxFramesTotal ?: current.rxFrames,
                txFrames = txFramesTotal ?: current.txFrames,
                channelFrameCounts = updatedChannelCounts,
                packetLogs = updatedLogs,
                lastProtocolEvent = protocolNote ?: current.lastProtocolEvent
            )
        }
    }

    fun clearLogs() {
        _runtimeState.update {
            it.copy(
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

    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var usbTransport: UsbAccessoryTransport
        private set

    lateinit var projectionManager: CockpitProjectionManager
        private set

    val controlStateMachine = CarLifeControlStateMachine()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        usbTransport = UsbAccessoryTransport(
            context = applicationContext,
            ioDispatcher = Dispatchers.IO,
            externalScope = serviceScope
        )

        projectionManager = CockpitProjectionManager(
            context = applicationContext,
            onEncodedVideoNal = { nalBytes, timestampMs, _ ->
                // Forward H.264 NAL units directly into the active USB AOA transport
                val videoFrame = CarLifeFrameCodec.createVideoFrame(nalBytes, timestampMs)
                serviceScope.launch {
                    usbTransport.sendFrame(videoFrame)
                }
            },
            externalScope = serviceScope
        )

        BridgeTelemetryBus.updateState {
            it.copy(
                isServiceRunning = true,
                wakeLockHeld = wakeLock?.isHeld == true,
                lastProtocolEvent = "BridgeForegroundService active (Core 1 USB + Core 2 Projection ready)"
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
                if (newState is UsbTransportState.Disconnected) {
                    controlStateMachine.reset()
                }
                BridgeTelemetryBus.updateState { current ->
                    current.copy(
                        transportState = newState,
                        rxBytes = usbTransport.rxBytes,
                        txBytes = usbTransport.txBytes,
                        rxFrames = usbTransport.rxFrames,
                        txFrames = usbTransport.txFrames,
                        lastProtocolEvent = when (newState) {
                            is UsbTransportState.Connected ->
                                "AOA Connected: ${newState.accessory.manufacturer} ${newState.accessory.model} (fd=${newState.fdNumber})"
                            is UsbTransportState.Connecting ->
                                "Opening Direct-IO FileChannel for ${newState.accessory.manufacturer} ${newState.accessory.model}..."
                            is UsbTransportState.WaitingForPermission ->
                                "Waiting for USB AOA permission: ${newState.accessory.manufacturer} ${newState.accessory.model}"
                            is UsbTransportState.Error ->
                                "AOA Status: ${newState.reason}"
                            UsbTransportState.Disconnected ->
                                "USB AOA Disconnected"
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

        // Periodic 1,000ms Heartbeat loop to prevent Jetta VS7 (MQB) Head Unit watchdog disconnect
        serviceScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                if (usbTransport.state.value is UsbTransportState.Connected) {
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
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    MediaProjectionTokenStore.saveToken(resultCode, resultData)
                    promoteToForeground(usbTransport.state.value)
                    val session = controlStateMachine.sessionState.value
                    val targetW = session.negotiatedVideoWidth.coerceAtLeast(1280)
                    val targetH = session.negotiatedVideoHeight.coerceAtLeast(720)
                    val targetFps = session.negotiatedFps.coerceIn(30, 60)
                    serviceScope.launch {
                        if (projectionManager.stats.value.isRunning) {
                            projectionManager.stopProjection()
                        }
                        projectionManager.startProjection(
                            width = targetW,
                            height = targetH,
                            frameRate = targetFps
                        )
                        projectionManager.requestInstantIdrKeyFrame()
                    }
                    BridgeTelemetryBus.updateState {
                        it.copy(
                            lastProtocolEvent = "MediaProjection (Entire Screen) granted — H.264 SPS/PPS + IDR active"
                        )
                    }
                }
            }

            ACTION_START_PROJECTION -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
                val fps = intent.getIntExtra(EXTRA_FPS, 60)
                serviceScope.launch {
                    projectionManager.startProjection(
                        width = width,
                        height = height,
                        frameRate = fps
                    )
                }
            }

            ACTION_STOP_PROJECTION -> {
                serviceScope.launch {
                    projectionManager.stopProjection()
                }
            }

            ACTION_INJECT_TEST_TOUCH -> {
                val x = intent.getIntExtra(EXTRA_TOUCH_X, 320)
                val y = intent.getIntExtra(EXTRA_TOUCH_Y, 360)
                val w = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val h = intent.getIntExtra(EXTRA_HEIGHT, 720)
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
                val newMode = !CarLifeFrameCodec.useNestedVideoMsgHeader
                CarLifeFrameCodec.useNestedVideoMsgHeader = newMode
                projectionManager.requestInstantIdrKeyFrame()
                BridgeTelemetryBus.updateState {
                    it.copy(
                        useNestedVideoMsgHeader = newMode,
                        lastProtocolEvent = if (newMode) {
                            "Video Framing: Nested MSG_VIDEO_DATA (0x00020001) + 12B Header enabled"
                        } else {
                            "Video Framing: Standard 12B Media Header (Raw Annex-B NAL) enabled"
                        }
                    )
                }
            }

            ACTION_DISCONNECT_STOP -> {
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
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleIncomingCarLifeFrame(frame: CarLifeFrame) {
        when (frame.channel) {
            CarLifeChannel.CONTROL -> {
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

                val cmd = frame.parseCommandMessageOrNull()
                when (cmd?.serviceType) {
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT,
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET -> {
                        val s = transition.updatedState
                        projectionManager.startProjection(
                            width = s.negotiatedVideoWidth,
                            height = s.negotiatedVideoHeight,
                            frameRate = s.negotiatedFps
                        )
                    }
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START -> {
                        val s = transition.updatedState
                        if (!projectionManager.stats.value.isRunning) {
                            projectionManager.startProjection(
                                width = s.negotiatedVideoWidth,
                                height = s.negotiatedVideoHeight,
                                frameRate = s.negotiatedFps
                            )
                        }
                        projectionManager.requestInstantIdrKeyFrame()
                    }
                    CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE -> {
                        projectionManager.stopProjection()
                    }
                }
            }

            CarLifeChannel.TOUCH -> {
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
        const val ACTION_START_PROJECTION = "com.example.core.usb.action.START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.example.core.usb.action.STOP_PROJECTION"
        const val ACTION_INJECT_TEST_TOUCH = "com.example.core.usb.action.INJECT_TEST_TOUCH"
        const val ACTION_SEND_VERSION_MATCH = "com.example.core.usb.action.SEND_VERSION_MATCH"
        const val ACTION_SEND_VIDEO_HEARTBEAT = "com.example.core.usb.action.SEND_VIDEO_HEARTBEAT"
        const val ACTION_TOGGLE_VIDEO_HEADER_MODE = "com.example.core.usb.action.TOGGLE_VIDEO_HEADER_MODE"
        const val ACTION_DISCONNECT_STOP = "com.example.core.usb.action.DISCONNECT_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_TOUCH_X = "extra_touch_x"
        const val EXTRA_TOUCH_Y = "extra_touch_y"

        fun grantMediaProjection(context: Context, resultCode: Int, resultData: Intent) {
            MediaProjectionTokenStore.saveToken(resultCode, resultData)
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_GRANT_MEDIA_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
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
