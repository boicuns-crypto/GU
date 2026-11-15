package com.example.protocol.carlife

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Protocol session states of the Baidu CarLife Control Channel (`0x0001`)
 * when connected to a Jetta VS7 (MQB) Head Unit.
 */
enum class CarLifeSessionPhase(val label: String) {
    IDLE("Idle / Waiting for HU Handshake"),
    VERSION_MATCHED("Protocol v2.0 Matched"),
    DEVICE_INFO_EXCHANGED("Jetta VS7 HU <-> S26 Ultra MD Info Exchanged"),
    VIDEO_INITIALIZED("Video Encoder Negotiated"),
    VIDEO_STREAMING("Streaming H.264 @ 60fps"),
    VIDEO_PAUSED("Video Encoder Paused")
}

/**
 * Negotiated session configuration between the Jetta VS7 Head Unit and Galaxy S26 Ultra.
 */
data class CarLifeControlSessionState(
    val phase: CarLifeSessionPhase = CarLifeSessionPhase.IDLE,
    val huProtocolMajor: Int = 0,
    val huProtocolMinor: Int = 0,
    val huDeviceInfo: CarlifeDeviceInfoProto? = null,
    val negotiatedVideoWidth: Int = 1280,
    val negotiatedVideoHeight: Int = 720,
    val negotiatedFps: Int = 30,
    val isHuScreenOn: Boolean = true,
    val isHuForeground: Boolean = true,
    val heartbeatCount: Long = 0L,
    val lastControlAction: String = "Awaiting MSG_CMD_HU_PROTOCOL_VERSION (0x00018001)"
)

/**
 * Result of processing an incoming Control (`0x0001`) or Video Heartbeat (`0x0002`) frame.
 */
data class ControlTransitionResult(
    val updatedState: CarLifeControlSessionState,
    val outboundFrames: List<CarLifeFrame> = emptyList(),
    val summary: String
)

/**
 * Deterministic State Machine for the Baidu CarLife Control Channel (`0x0001`).
 */
class CarLifeControlStateMachine(
    private val force60FpsOnMqb: Boolean = false,
    private val mobileDeviceInfo: CarlifeDeviceInfoProto = CarlifeDeviceInfoProto()
) {
    private val _sessionState = MutableStateFlow(CarLifeControlSessionState())
    val sessionState: StateFlow<CarLifeControlSessionState> = _sessionState.asStateFlow()

    fun reset() {
        _sessionState.value = CarLifeControlSessionState()
    }

    /**
     * Processes an incoming `CarLifeChannel.CONTROL` (`0x0001`) frame and returns any immediate
     * response frames required by the Baidu CarLife protocol specification.
     */
    fun onControlFrame(frame: CarLifeFrame): ControlTransitionResult {
        val cmd = frame.parseCommandMessageOrNull()
        if (cmd == null) {
            val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 64)
            val note = "RX Control Frame (${frame.totalFrameBytes}B) $hex"
            _sessionState.update { it.copy(lastControlAction = note) }
            return ControlTransitionResult(
                updatedState = _sessionState.value,
                outboundFrames = emptyList(),
                summary = note
            )
        }

        return when (cmd.serviceType) {
            CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION,
            CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION_ALT -> handleProtocolVersion(cmd.body)
            CarLifeMsgType.MSG_CMD_HU_INFO,
            CarLifeMsgType.MSG_CMD_HU_INFO_ALT -> handleHuInfo(cmd.body)
            CarLifeMsgType.MSG_CMD_HU_BT_OOB_INFO,
            CarLifeMsgType.MSG_CMD_HU_BT_OOB_INFO_ALT -> handleBtOobInfo()
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT,
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_ALT -> handleVideoEncoderInit(cmd.body)
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START,
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_ALT -> handleVideoEncoderStart(frame.totalFrameBytes)
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE -> handleVideoEncoderPause()
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET,
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET_ALT -> handleVideoEncoderReset()
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE,
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_ALT -> handleFrameRateChange(cmd.body)
            CarLifeMsgType.MSG_CMD_SCREEN_ON -> updateScreenLifecycle(screenOn = true, eventName = "MSG_CMD_SCREEN_ON")
            CarLifeMsgType.MSG_CMD_SCREEN_OFF -> updateScreenLifecycle(screenOn = false, eventName = "MSG_CMD_SCREEN_OFF")
            CarLifeMsgType.MSG_CMD_FOREGROUND,
            CarLifeMsgType.MSG_CMD_FOREGROUND_ALT,
            CarLifeMsgType.MSG_CMD_FOREGROUND_LEGACY -> updateForegroundLifecycle(foreground = true)
            CarLifeMsgType.MSG_CMD_BACKGROUND -> updateForegroundLifecycle(foreground = false)
            else -> {
                val msgName = CarLifeMsgType.nameOf(cmd.serviceType)
                val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 64)
                val note = "RX Control $msgName (${frame.totalFrameBytes}B wire / ${cmd.body.size}B proto) $hex"
                _sessionState.update { it.copy(lastControlAction = note) }
                ControlTransitionResult(
                    updatedState = _sessionState.value,
                    outboundFrames = emptyList(),
                    summary = note
                )
            }
        }
    }

    /**
     * Builds a periodic Heartbeat frame (`MSG_VIDEO_HEARTBEAT = 0x00020002` or Control keep-alive)
     * to prevent Jetta VS7 Head Unit watchdog disconnects.
     */
    fun createHeartbeatFrame(): CarLifeFrame {
        _sessionState.update {
            it.copy(
                heartbeatCount = it.heartbeatCount + 1L,
                lastControlAction = "TX Heartbeat #${it.heartbeatCount + 1L}"
            )
        }
        return CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_VIDEO_HEARTBEAT,
            commandBody = ByteArray(0)
        )
    }

    private fun handleProtocolVersion(body: ByteArray): ControlTransitionResult {
        val versionProto = CarlifeProtocolVersionProto.decode(body)
        val matchReplyProto = CarlifeProtocolVersionMatchStatusProto(
            matchStatus = CarlifeProtocolVersionMatchStatusProto.MATCH_SUCCESS
        )
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            commandBody = matchReplyProto.encode()
        )
        val mdInfoFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_INFO,
            commandBody = mobileDeviceInfo.encode()
        )

        val summary = "HU Protocol v${versionProto.majorVersion}.${versionProto.minorVersion} -> TX MATCH_STATUS(1) + MD_INFO(S26 Ultra)"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VERSION_MATCHED,
                huProtocolMajor = versionProto.majorVersion,
                huProtocolMinor = versionProto.minorVersion,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame, mdInfoFrame),
            summary = summary
        )
    }

    private fun handleHuInfo(body: ByteArray): ControlTransitionResult {
        val huInfo = CarlifeDeviceInfoProto.decode(body)
        val mdInfoFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_INFO,
            commandBody = mobileDeviceInfo.encode()
        )
        val summary = "RX HU_INFO (${huInfo.manufacturer} ${huInfo.model}, OS=${huInfo.os}) -> TX MD_INFO (${mobileDeviceInfo.model})"
        _sessionState.update {
            it.copy(
                phase = if (it.phase == CarLifeSessionPhase.IDLE) CarLifeSessionPhase.DEVICE_INFO_EXCHANGED else it.phase,
                huDeviceInfo = huInfo,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(mdInfoFrame),
            summary = summary
        )
    }

    private fun handleVideoEncoderInit(body: ByteArray): ControlTransitionResult {
        val requested = CarlifeVideoEncoderInfoProto.decode(body)
        val negotiatedWidth = if (requested.width in 480..3840) requested.width else 1280
        val negotiatedHeight = if (requested.height in 272..2160) requested.height else 720
        val negotiatedFps = if (force60FpsOnMqb) 60 else if (requested.frameRate in 15..60) requested.frameRate else 30

        val negotiatedProto = CarlifeVideoEncoderInfoProto(
            width = negotiatedWidth,
            height = negotiatedHeight,
            frameRate = negotiatedFps
        )
        val encodedNegotiated = negotiatedProto.encode()

        // 1. Official MD -> HU MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008) with [00 08][00 00][00 01 80 08]
        val initDoneFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            commandBody = encodedNegotiated
        )
        // 2. MSG_CMD_FOREGROUND (0x00018025, msgLen = 0) to notify HU that video source is active
        val foregroundFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_FOREGROUND,
            commandBody = ByteArray(0)
        )

        val summary = "Video Init: ${negotiatedProto.width}x${negotiatedProto.height}@${negotiatedProto.frameRate}fps -> TX INIT_DONE (0x00018008) + FOREGROUND + H.264 Stream"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_STREAMING,
                negotiatedVideoWidth = negotiatedProto.width,
                negotiatedVideoHeight = negotiatedProto.height,
                negotiatedFps = negotiatedProto.frameRate,
                isHuForeground = true,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(initDoneFrame, foregroundFrame),
            summary = summary
        )
    }

    private fun handleBtOobInfo(): ControlTransitionResult {
        val btReply = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_BT_OOB_INFO, // 0x00018006
            commandBody = ByteArray(0)
        )
        val summary = "RX MSG_CMD_HU_BT_OOB_INFO -> TX MSG_CMD_MD_BT_OOB_INFO (0x00018006)"
        _sessionState.update {
            it.copy(lastControlAction = summary)
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(btReply),
            summary = summary
        )
    }

    private fun handleVideoEncoderStart(wireBytes: Int = 12): ControlTransitionResult {
        // Send 0x0001800A (MSG_CMD_VIDEO_ENCODER_START_DONE) + 0x00018025 (MSG_CMD_FOREGROUND)
        val startDoneFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_DONE, // 0x0001800A
            commandBody = ByteArray(0)
        )
        val foregroundFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_FOREGROUND, // 0x00018025
            commandBody = ByteArray(0)
        )
        val summary = "RX MSG_CMD_VIDEO_ENCODER_START (${wireBytes}B) -> TX START_DONE (0x0001800A) + SPS/PPS + IDR"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_STREAMING,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(startDoneFrame, foregroundFrame),
            summary = summary
        )
    }

    private fun handleVideoEncoderPause(): ControlTransitionResult {
        val summary = "RX MSG_CMD_VIDEO_ENCODER_PAUSE -> H.264 Streaming Paused"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_PAUSED,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = emptyList(),
            summary = summary
        )
    }

    private fun handleVideoEncoderReset(): ControlTransitionResult {
        val current = _sessionState.value
        val initDoneProto = CarlifeVideoEncoderInfoProto(
            width = current.negotiatedVideoWidth,
            height = current.negotiatedVideoHeight,
            frameRate = current.negotiatedFps
        )
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            commandBody = initDoneProto.encode()
        )
        val summary = "RX MSG_CMD_VIDEO_ENCODER_RESET -> Re-sent INIT_DONE (${initDoneProto.width}x${initDoneProto.height}@${initDoneProto.frameRate}fps)"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_INITIALIZED,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun handleFrameRateChange(body: ByteArray): ControlTransitionResult {
        val req = CarlifeVideoFrameRateProto.decode(body)
        val targetFps = if (force60FpsOnMqb) 60 else req.frameRate
        val doneProto = CarlifeVideoFrameRateProto(frameRate = targetFps)
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE,
            commandBody = doneProto.encode()
        )
        val summary = "RX FRAME_RATE_CHANGE(${req.frameRate}fps) -> TX DONE(${targetFps}fps)"
        _sessionState.update {
            it.copy(
                negotiatedFps = targetFps,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun updateScreenLifecycle(screenOn: Boolean, eventName: String): ControlTransitionResult {
        _sessionState.update {
            it.copy(
                isHuScreenOn = screenOn,
                lastControlAction = "RX $eventName (HU ScreenOn=$screenOn)"
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = emptyList(),
            summary = "HU ScreenOn=$screenOn"
        )
    }

    private fun updateForegroundLifecycle(foreground: Boolean): ControlTransitionResult {
        val eventName = if (foreground) "MSG_CMD_FOREGROUND" else "MSG_CMD_BACKGROUND"
        _sessionState.update {
            it.copy(
                isHuForeground = foreground,
                lastControlAction = "RX $eventName (HU Foreground=$foreground)"
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = emptyList(),
            summary = "HU Foreground=$foreground"
        )
    }
}
