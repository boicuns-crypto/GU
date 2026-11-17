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
    val negotiatedVideoWidth: Int = 800,
    val negotiatedVideoHeight: Int = 480,
    val negotiatedFps: Int = 30,
    val isHuScreenOn: Boolean = true,
    val isHuForeground: Boolean = true,
    val heartbeatCount: Long = 0L,
    val lastControlAction: String = "Awaiting MSG_CMD_HU_PROTOCOL_VERSION (0x00010001)"
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
    private val mobileDeviceInfo: CarlifeDeviceInfoProto = CarlifeDeviceInfoProto(),
    @Volatile var preferredVideoWidth: Int = 800,
    @Volatile var preferredVideoHeight: Int = 480
) {
    private val _sessionState = MutableStateFlow(
        CarLifeControlSessionState(
            negotiatedVideoWidth = preferredVideoWidth,
            negotiatedVideoHeight = preferredVideoHeight
        )
    )
    val sessionState: StateFlow<CarLifeControlSessionState> = _sessionState.asStateFlow()

    fun reset() {
        _sessionState.value = CarLifeControlSessionState(
            negotiatedVideoWidth = preferredVideoWidth,
            negotiatedVideoHeight = preferredVideoHeight
        )
    }

    fun setPreferredResolution(width: Int, height: Int) {
        val safeW = width.coerceIn(480, 3840)
        val safeH = height.coerceIn(272, 2160)
        preferredVideoWidth = safeW
        preferredVideoHeight = safeH
        _sessionState.update {
            it.copy(
                negotiatedVideoWidth = safeW,
                negotiatedVideoHeight = safeH
            )
        }
    }

    /**
     * Auto-starts `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008)`,
     * `MSG_CMD_VIDEO_ENCODER_START_DONE (0x0001800A)`, `MSG_CMD_FOREGROUND (0x00018025)`,
     * and `MSG_CMD_SCREEN_ON (0x00018018)` 150ms after `MSG_CMD_MD_INFO (0x00018003)` or on any
     * subsequent RX packet from the Head Unit.
     */
    fun buildAutoVideoEncoderInitAndStartFrames(
        width: Int = preferredVideoWidth,
        height: Int = preferredVideoHeight,
        fps: Int = 30,
        reason: String = "Auto-Start 150ms after MD_INFO"
    ): ControlTransitionResult {
        val safeW = width.coerceIn(480, 3840)
        val safeH = height.coerceIn(272, 2160)
        val safeFps = fps.coerceIn(15, 60)
        preferredVideoWidth = safeW
        preferredVideoHeight = safeH

        val negotiatedProto = CarlifeVideoEncoderInfoProto(
            width = safeW,
            height = safeH,
            frameRate = safeFps
        )
        val initDoneFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, // 0x00018008
            commandBody = negotiatedProto.encode()
        )
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
        val screenOnFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_SCREEN_ON, // 0x00018018
            commandBody = ByteArray(0)
        )

        val summary = "$reason (${safeW}x${safeH}@${safeFps}fps) -> TX INIT_DONE (0x00018008) + START_DONE (0x0001800A) + FOREGROUND + SCREEN_ON"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_STREAMING,
                negotiatedVideoWidth = safeW,
                negotiatedVideoHeight = safeH,
                negotiatedFps = safeFps,
                isHuForeground = true,
                isHuScreenOn = true,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(initDoneFrame, startDoneFrame, foregroundFrame, screenOnFrame),
            summary = summary
        )
    }

    /**
     * Builds `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008)` (optional) +
     * `MSG_CMD_VIDEO_ENCODER_START_DONE (0x0001800A)` + `FOREGROUND` + `SCREEN_ON`.
     */
    fun buildStartDoneReplyFrames(
        includeInitDone: Boolean = true,
        width: Int = preferredVideoWidth,
        height: Int = preferredVideoHeight,
        fps: Int = 30
    ): List<CarLifeFrame> {
        val safeW = width.coerceIn(480, 3840)
        val safeH = height.coerceIn(272, 2160)
        val safeFps = fps.coerceIn(15, 60)
        val out = ArrayList<CarLifeFrame>(4)
        if (includeInitDone) {
            val negotiatedProto = CarlifeVideoEncoderInfoProto(
                width = safeW,
                height = safeH,
                frameRate = safeFps
            )
            out.add(
                CarLifeFrameCodec.createCommandFrame(
                    channel = CarLifeChannel.CONTROL,
                    serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, // 0x00018008
                    commandBody = negotiatedProto.encode()
                )
            )
        }
        out.add(
            CarLifeFrameCodec.createCommandFrame(
                channel = CarLifeChannel.CONTROL,
                serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_DONE, // 0x0001800A
                commandBody = ByteArray(0)
            )
        )
        out.add(
            CarLifeFrameCodec.createCommandFrame(
                channel = CarLifeChannel.CONTROL,
                serviceType = CarLifeMsgType.MSG_CMD_FOREGROUND, // 0x00018025
                commandBody = ByteArray(0)
            )
        )
        out.add(
            CarLifeFrameCodec.createCommandFrame(
                channel = CarLifeChannel.CONTROL,
                serviceType = CarLifeMsgType.MSG_CMD_SCREEN_ON, // 0x00018018
                commandBody = ByteArray(0)
            )
        )
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_STREAMING,
                negotiatedVideoWidth = safeW,
                negotiatedVideoHeight = safeH,
                negotiatedFps = safeFps,
                isHuForeground = true,
                isHuScreenOn = true
            )
        }
        return out
    }

    /**
     * Processes an incoming `CarLifeChannel.CONTROL` (`0x0001`) frame and returns any immediate
     * response frames required by the Baidu CarLife protocol specification.
     */
    fun onControlFrame(frame: CarLifeFrame): ControlTransitionResult {
        val cmd = frame.parseCommandMessageOrNull()
        if (cmd == null) {
            val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 64)
            val note = "RX Control Frame (${frame.totalFrameBytes}B, Payload: ${frame.payload.size}B) $hex"
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
            CarLifeMsgType.MSG_CMD_MODULE_CONTROL,
            CarLifeMsgType.MSG_CMD_MODULE_CONTROL_ALT -> handleModuleControl(frame, cmd.body)
            CarLifeMsgType.MSG_CMD_MODULE_STATUS,
            CarLifeMsgType.MSG_CMD_MODULE_STATUS_ALT -> {
                val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
                val statusProto = CarlifeModuleStatusProto.decode(cmd.body)
                val note = "RX MSG_CMD_MODULE_STATUS (module=${statusProto.moduleId}, status=${statusProto.statusId}) $hex"
                _sessionState.update { it.copy(lastControlAction = note) }
                ControlTransitionResult(
                    updatedState = _sessionState.value,
                    outboundFrames = emptyList(),
                    summary = note
                )
            }
            CarLifeMsgType.MSG_CMD_HU_AUTHEN_REQUEST,
            CarLifeMsgType.MSG_CMD_HU_AUTHEN_REQUEST_ALT,
            CarLifeMsgType.MSG_CMD_HU_AUTHEN_REQUEST_LEGACY -> handleHuAuthenRequest(frame, cmd.serviceType, cmd.body)
            CarLifeMsgType.MSG_CMD_HU_AUTHEN_RESULT,
            CarLifeMsgType.MSG_CMD_HU_AUTHEN_RESULT_ALT -> handleHuAuthenResult(frame)
            CarLifeMsgType.MSG_CMD_HU_FEATURE_CONFIG_REQUEST,
            CarLifeMsgType.MSG_CMD_MD_FEATURE_CONFIG_REQUEST -> handleFeatureConfigRequest(frame)
            CarLifeMsgType.MSG_CMD_MD_AES_KEY_REQUEST,
            CarLifeMsgType.MSG_CMD_MD_AES_KEY_REQUEST_ALT,
            CarLifeMsgType.MSG_CMD_HU_AES_KEY_REQUEST -> handleAesKeyRequest(frame)
            CarLifeMsgType.MSG_CMD_SCREEN_ON -> updateScreenLifecycle(screenOn = true, eventName = "MSG_CMD_SCREEN_ON")
            CarLifeMsgType.MSG_CMD_SCREEN_OFF -> updateScreenLifecycle(screenOn = false, eventName = "MSG_CMD_SCREEN_OFF")
            CarLifeMsgType.MSG_CMD_FOREGROUND,
            CarLifeMsgType.MSG_CMD_FOREGROUND_ALT,
            CarLifeMsgType.MSG_CMD_FOREGROUND_LEGACY -> updateForegroundLifecycle(foreground = true)
            CarLifeMsgType.MSG_CMD_BACKGROUND -> updateForegroundLifecycle(foreground = false)
            else -> {
                val msgName = CarLifeMsgType.nameOf(cmd.serviceType)
                val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
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
     * Builds a Video Channel (`0x0002`) Heartbeat frame (`MSG_VIDEO_HEARTBEAT = 0x00028002`)
     * rather than sending a `0x0002xxxx` message ID inside Control Channel `0x0001`.
     */
    fun createHeartbeatFrame(): CarLifeFrame {
        _sessionState.update {
            it.copy(
                heartbeatCount = it.heartbeatCount + 1L,
                lastControlAction = "TX Video Heartbeat #${it.heartbeatCount + 1L}"
            )
        }
        val innerHeartbeat = java.nio.ByteBuffer.allocate(12)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt((System.currentTimeMillis() and 0x7FFF_FFFFL).toInt())
            .putInt(CarLifeMsgType.MSG_VIDEO_HEARTBEAT)
            .array()
        return CarLifeFrame(
            header = CarLifeHeader(
                channel = CarLifeChannel.VIDEO,
                rawChannelId = CarLifeChannel.VIDEO.id,
                payloadLength = innerHeartbeat.size,
                timestamp = System.currentTimeMillis() and 0x7FFF_FFFFL,
                includeOuterTimestamp = false
            ),
            payload = innerHeartbeat
        )
    }

    private fun handleProtocolVersion(body: ByteArray): ControlTransitionResult {
        val versionProto = CarlifeProtocolVersionProto.decode(body)
        // 1. MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS (0x00018002) with matchStatus = 1 (08 01)
        val matchReplyProto = CarlifeProtocolVersionMatchStatusProto(
            matchStatus = CarlifeProtocolVersionMatchStatusProto.MATCH_SUCCESS
        )
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, // 0x00018002
            commandBody = matchReplyProto.encode()
        )
        // 2. MSG_CMD_MD_INFO (0x00018003) with filled CarlifeDeviceInfoProto
        val encodedMdInfo = mobileDeviceInfo.encode()
        val mdInfo03Frame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_INFO_03, // 0x00018003
            commandBody = encodedMdInfo
        )

        val summary = "HU Protocol v${versionProto.majorVersion}.${versionProto.minorVersion} -> TX MATCH_STATUS (0x00018002 [08 01]) + MD_INFO (0x00018003)"
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
            outboundFrames = listOf(replyFrame, mdInfo03Frame),
            summary = summary
        )
    }

    private fun handleHuInfo(body: ByteArray): ControlTransitionResult {
        val huInfo = CarlifeDeviceInfoProto.decode(body)
        val encodedMdInfo = mobileDeviceInfo.encode()
        val mdInfo03Frame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_INFO_03, // 0x00018003
            commandBody = encodedMdInfo
        )
        val mdInfo04Frame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_INFO, // 0x00018004
            commandBody = encodedMdInfo
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
            outboundFrames = listOf(mdInfo03Frame, mdInfo04Frame),
            summary = summary
        )
    }

    private fun handleModuleControl(frame: CarLifeFrame, body: ByteArray): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val reqStatus = CarlifeModuleStatusProto.decode(body)
        val replyStatus = CarlifeModuleStatusProto(
            moduleId = reqStatus.moduleId,
            statusId = reqStatus.statusId
        )
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MODULE_STATUS, // 0x00018023
            commandBody = replyStatus.encode()
        )
        val summary = "RX MSG_CMD_MODULE_CONTROL (module=${reqStatus.moduleId}, status=${reqStatus.statusId}) $hex -> TX MSG_CMD_MODULE_STATUS (0x00018023)"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun handleHuAuthenRequest(frame: CarLifeFrame, serviceType: Int, body: ByteArray): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        // Reply with CarlifeAuthenResponse (field 1 string = random/echoed value)
        val replyProto = if (body.isNotEmpty()) body else CarLifeWireProtobuf.Writer(32)
            .writeStringField(1, "CarLife_S26U_Auth_OK")
            .toByteArray()
        val responseServiceId = if (serviceType == CarLifeMsgType.MSG_CMD_HU_AUTHEN_REQUEST_LEGACY) {
            CarLifeMsgType.MSG_CMD_MD_AUTHEN_RESPONSE_LEGACY
        } else {
            CarLifeMsgType.MSG_CMD_MD_AUTHEN_RESPONSE
        }
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = responseServiceId,
            commandBody = replyProto
        )
        val summary = "RX MSG_CMD_HU_AUTHEN_REQUEST $hex -> TX MSG_CMD_MD_AUTHEN_RESPONSE (0x${responseServiceId.toUInt().toString(16).uppercase()})"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun handleHuAuthenResult(frame: CarLifeFrame): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val ackProto = byteArrayOf(0x08, 0x01) // status = 1 (SUCCESS)
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_AUTHEN_RESULT, // 0x0001804B
            commandBody = ackProto
        )
        val summary = "RX MSG_CMD_HU_AUTHEN_RESULT $hex -> TX MSG_CMD_MD_AUTHEN_RESULT (0x0001804B)"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun handleFeatureConfigRequest(frame: CarLifeFrame): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_FEATURE_CONFIG_RESPONSE, // 0x00018052
            commandBody = ByteArray(0)
        )
        val summary = "RX MSG_CMD_HU_FEATURE_CONFIG_REQUEST $hex -> TX MSG_CMD_MD_FEATURE_CONFIG_RESPONSE (0x00018052)"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun handleAesKeyRequest(frame: CarLifeFrame): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val ackProto = byteArrayOf(0x08, 0x01)
        val resp57 = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_AES_KEY_RESPONSE, // 0x00018057
            commandBody = ackProto
        )
        val resp58 = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_AES_REC_RESPONSE, // 0x00018058
            commandBody = ackProto
        )
        val summary = "RX MSG_CMD_MD_AES_KEY_REQUEST $hex -> TX MSG_CMD_HU_AES_REC_RESPONSE (0x00018057/58)"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(resp57, resp58),
            summary = summary
        )
    }

    private fun handleVideoEncoderInit(body: ByteArray): ControlTransitionResult {
        val requested = CarlifeVideoEncoderInfoProto.decode(body)
        val negotiatedWidth = if (force60FpsOnMqb && requested.width in 480..3840) {
            requested.width
        } else {
            preferredVideoWidth.coerceIn(480, 3840)
        }
        val negotiatedHeight = if (force60FpsOnMqb && requested.height in 272..2160) {
            requested.height
        } else {
            preferredVideoHeight.coerceIn(272, 2160)
        }
        val negotiatedFps = if (force60FpsOnMqb) 60 else 30

        val negotiatedProto = CarlifeVideoEncoderInfoProto(
            width = negotiatedWidth,
            height = negotiatedHeight,
            frameRate = negotiatedFps
        )
        val encodedNegotiated = negotiatedProto.encode()

        // Strict Step 2: Send ONLY MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00018008) with 800x480@30fps and WAIT!
        // Do NOT send FOREGROUND/SCREEN_ON or unlock 0x0002 until MSG_CMD_VIDEO_ENCODER_START (0x00010009) arrives.
        val initDoneFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, // 0x00018008
            commandBody = encodedNegotiated
        )

        val summary = "Video Init: ${negotiatedProto.width}x${negotiatedProto.height}@${negotiatedProto.frameRate}fps -> TX INIT_DONE (0x00018008), waiting for VIDEO_ENCODER_START"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_INITIALIZED,
                negotiatedVideoWidth = negotiatedProto.width,
                negotiatedVideoHeight = negotiatedProto.height,
                negotiatedFps = negotiatedProto.frameRate,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(initDoneFrame),
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
        // Strict Step 3: Send START_DONE (0x0001800A) + FOREGROUND (0x00018025) + SCREEN_ON (0x00018018),
        // then immediately unlock 0x0002 and emit H.264 SYNC_FRAME.
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
        val screenOnFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_SCREEN_ON, // 0x00018018
            commandBody = ByteArray(0)
        )
        val summary = "RX MSG_CMD_VIDEO_ENCODER_START (${wireBytes}B) -> TX START_DONE (0x0001800A) + FOREGROUND + SCREEN_ON -> Streaming 0x0002"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_STREAMING,
                isHuForeground = true,
                isHuScreenOn = true,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(startDoneFrame, foregroundFrame, screenOnFrame),
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
