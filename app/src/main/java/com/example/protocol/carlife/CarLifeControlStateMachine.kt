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
    VIDEO_STREAMING("Streaming H.264 @ 30fps"),
    VIDEO_PAUSED("Video Encoder Paused")
}

/**
 * 4 strict Handshake Lab profiles selectable on the Settings tab to diagnose which
 * `0x00018001` reply triggers the next RX packet from the Jetta VS7 Head Unit.
 */
enum class HandshakeTestProfile(
    val id: Int,
    val shortTitle: String,
    val description: String
) {
    TEST_1_MATCH_ONLY(
        id = 1,
        shortTitle = "Тест 1: Только MATCH_STATUS (Чистый минимум)",
        description = "Отправить ТОЛЬКО 0x00010002 [08 01] и ждать RX от ГУ (без MD_INFO, Audio, Video, Screen)."
    ),
    TEST_2_MATCH_AND_MIN_MD_INFO(
        id = 2,
        shortTitle = "Тест 2: MATCH_STATUS + минимальный MD_INFO",
        description = "Отправить 0x00010002 + короткий MD_INFO 0x00010004 (только os=\"Android\", ~9B), без Audio/Screen."
    ),
    TEST_3_MATCH_MIN_MD_INFO_BT_OOB(
        id = 3,
        shortTitle = "Тест 3: MATCH_STATUS + MD_INFO + BT_OOB_INFO",
        description = "Отправить 0x00010002 + короткий MD_INFO (0x00010004) + пустой 0x00010006, без Audio/FG/Screen."
    ),
    TEST_4_AUDIO_VIDEO_NO_SCREEN(
        id = 4,
        shortTitle = "Тест 4: Полный запуск (Audio + Video + FG + ScreenOn)",
        description = "Рабочий профиль по умолчанию: полный MD_INFO (0x00010004) + AUDIO_INIT (0x0003/0x0004) + INIT_DONE (0x00010008) + SCREEN_ON (0x00010018) + USERPRESENT (0x0001001A) + FOREGROUND (0x0001001B) + REQ_FG (0x00010038)."
    );

    companion object {
        fun fromId(id: Int): HandshakeTestProfile =
            entries.firstOrNull { it.id == id } ?: TEST_4_AUDIO_VIDEO_NO_SCREEN
    }
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
    private val mobileDeviceInfo: CarlifeDeviceInfoProto = CarlifeDeviceInfoProto(),
    @Volatile var preferredVideoWidth: Int = 800,
    @Volatile var preferredVideoHeight: Int = 480,
    @Volatile var handshakeTestProfile: HandshakeTestProfile = HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN
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
     * Builds the exact 4 foreground/screen commands per original Baidu CarLife `CommonParams.smali`:
     * 1. `MSG_CMD_SCREEN_ON (0x00010018)`
     * 2. `MSG_CMD_SCREEN_USERPRESENT (0x0001001A)`
     * 3. `MSG_CMD_FOREGROUND (0x0001001B)`
     * 4. `MSG_CMD_REQUEST_GO_TO_FOREGROUND (0x00010038)`
     */
    fun buildForegroundAndScreenOnFrames(): List<CarLifeFrame> = listOf(
        CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_SCREEN_ON, // 0x00010018
            commandBody = ByteArray(0)
        ),
        CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT, // 0x0001001A
            commandBody = ByteArray(0)
        ),
        CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_FOREGROUND, // 0x0001001B
            commandBody = ByteArray(0)
        ),
        CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_REQUEST_GO_TO_FOREGROUND, // 0x00010038
            commandBody = ByteArray(0)
        )
    )

    /**
     * Auto-starts `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00010008)` followed by
     * `SCREEN_ON (0x00010018)`, `SCREEN_USERPRESENT (0x0001001A)`,
     * `FOREGROUND (0x0001001B)`, and `REQUEST_GO_TO_FOREGROUND (0x00010038)`
     * after `MSG_CMD_MD_INFO (0x00010004)`.
     * Never sends non-existent `0x0001000A` (`0x0001800A` is `PAUSE`).
     */
    fun buildAutoVideoEncoderInitAndStartFrames(
        width: Int = preferredVideoWidth,
        height: Int = preferredVideoHeight,
        fps: Int = 30,
        reason: String = "Proactive Handshake on 0x00018001",
        includeForegroundAndScreenOn: Boolean = true
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
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, // 0x00010008
            commandBody = negotiatedProto.encode()
        )
        val outbound = ArrayList<CarLifeFrame>(5).apply {
            add(initDoneFrame)
            if (includeForegroundAndScreenOn) {
                addAll(buildForegroundAndScreenOnFrames())
            }
        }

        val suffix = if (includeForegroundAndScreenOn) {
            " + SCREEN_ON (0x00010018) + USERPRESENT (0x0001001A) + FOREGROUND (0x0001001B) + REQ_FG (0x00010038)"
        } else {
            ""
        }
        val summary = "$reason (${safeW}x${safeH}@${safeFps}fps) -> TX INIT_DONE (0x00010008)$suffix"
        _sessionState.update {
            it.copy(
                phase = CarLifeSessionPhase.VIDEO_STREAMING,
                negotiatedVideoWidth = safeW,
                negotiatedVideoHeight = safeH,
                negotiatedFps = safeFps,
                isHuForeground = includeForegroundAndScreenOn,
                isHuScreenOn = includeForegroundAndScreenOn,
                lastControlAction = summary
            )
        }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = outbound,
            summary = summary
        )
    }

    /**
     * Builds optional `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00010008)` +
     * `SCREEN_ON (0x00010018)` + `SCREEN_USERPRESENT (0x0001001A)` +
     * `FOREGROUND (0x0001001B)` + `REQUEST_GO_TO_FOREGROUND (0x00010038)`.
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
        val out = ArrayList<CarLifeFrame>(5)
        if (includeInitDone) {
            val negotiatedProto = CarlifeVideoEncoderInfoProto(
                width = safeW,
                height = safeH,
                frameRate = safeFps
            )
            out.add(
                CarLifeFrameCodec.createCommandFrame(
                    channel = CarLifeChannel.CONTROL,
                    serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, // 0x00010008
                    commandBody = negotiatedProto.encode()
                )
            )
        }
        out.addAll(buildForegroundAndScreenOnFrames())
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
            CarLifeMsgType.MSG_CMD_HU_INFO -> handleHuInfo(cmd.body)
            CarLifeMsgType.MSG_CMD_HU_STATS_REQUEST -> handleStatsRequest(frame)
            CarLifeMsgType.MSG_CMD_HU_BT_OOB_INFO,
            CarLifeMsgType.MSG_CMD_HU_BT_OOB_INFO_ALT -> handleBtOobInfo()
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT,
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_ALT -> handleVideoEncoderInit(cmd.body)
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START,
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_ALT -> handleVideoEncoderStart(frame.totalFrameBytes)
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE,
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE_ALT -> handleVideoEncoderPause()
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET -> handleVideoEncoderReset()
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> handleFrameRateChange(cmd.body)
            CarLifeMsgType.MSG_CMD_AUTHEN_REQUEST_0B ->
                handleMockAuthBypass(frame, cmd.serviceType, CarLifeMsgType.MSG_CMD_AUTHEN_RESPONSE_0C)
            CarLifeMsgType.MSG_CMD_AUTHEN_REQUEST_0C ->
                handleMockAuthBypass(frame, cmd.serviceType, CarLifeMsgType.MSG_CMD_AUTHEN_RESPONSE_0D)
            CarLifeMsgType.MSG_CMD_AUTHEN_REQUEST_0D ->
                handleMockAuthBypass(frame, cmd.serviceType, CarLifeMsgType.MSG_CMD_AUTHEN_RESPONSE_0E)
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
            CarLifeMsgType.MSG_CMD_STATISTIC_INFO,
            CarLifeMsgType.MSG_CMD_STATISTIC_INFO_ALT -> {
                // RX 0x00018027 (MSG_CMD_STATISTIC_INFO): log only, NEVER reply with non-existent 0x00010027.
                val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = Int.MAX_VALUE)
                val idHex = "0x${cmd.serviceType.toUInt().toString(16).uppercase().padStart(8, '0')}"
                val note = "RX MSG_CMD_STATISTIC_INFO ($idHex, ${frame.payload.size}B) $hex (logged only)"
                _sessionState.update { it.copy(lastControlAction = note) }
                ControlTransitionResult(
                    updatedState = _sessionState.value,
                    outboundFrames = emptyList(),
                    summary = note
                )
            }
            CarLifeMsgType.MSG_CMD_HU_RSA_PUBLIC_KEY_REQUEST ->
                handleMockAuthBypass(frame, cmd.serviceType, CarLifeMsgType.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE)
            CarLifeMsgType.MSG_CMD_VEHICLE_CERTIFICATE_REQUEST ->
                handleMockAuthBypass(frame, cmd.serviceType, CarLifeMsgType.MSG_CMD_VEHICLE_CERTIFICATE_RESPONSE)
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
            CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT -> updateScreenLifecycle(screenOn = true, eventName = "MSG_CMD_SCREEN_USERPRESENT")
            CarLifeMsgType.MSG_CMD_FOREGROUND,
            CarLifeMsgType.MSG_CMD_GO_TO_FOREGROUND -> updateForegroundLifecycle(foreground = true)
            CarLifeMsgType.MSG_CMD_BACKGROUND -> updateForegroundLifecycle(foreground = false)
            else -> handleUnknownOrAuthCommand(frame, cmd.serviceType)
        }
    }

    /**
     * Builds a 20-byte AOA Video Channel (`0x0002`) Heartbeat frame (`MSG_VIDEO_HEARTBEAT = 0x00020002`):
     * Outer 8B (`[00 00 00 02][00 00 00 0C]`) + Inner 12B (`[00 00 00 00][timestampMs][00 02 00 02]`).
     */
    fun createHeartbeatFrame(
        timestampMs: Long = (android.os.SystemClock.uptimeMillis().takeIf { it > 0L } ?: System.currentTimeMillis()) and 0x7FFF_FFFFL
    ): CarLifeFrame {
        _sessionState.update {
            it.copy(
                heartbeatCount = it.heartbeatCount + 1L,
                lastControlAction = "TX MSG_VIDEO_HEARTBEAT (0x00020002) #${it.heartbeatCount + 1L}"
            )
        }
        return CarLifeFrameCodec.createVideoHeartbeatFrame(timestampMs = timestampMs)
    }

    private fun handleProtocolVersion(body: ByteArray): ControlTransitionResult {
        val versionProto = CarlifeProtocolVersionProto.decode(body)
        // 1. MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS (0x00010002) with matchStatus = 1 (08 01)
        val matchReplyProto = CarlifeProtocolVersionMatchStatusProto(
            matchStatus = CarlifeProtocolVersionMatchStatusProto.MATCH_SUCCESS
        )
        val matchStatusFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, // 0x00010002
            commandBody = matchReplyProto.encode()
        )

        val outbound = when (handshakeTestProfile) {
            HandshakeTestProfile.TEST_1_MATCH_ONLY -> listOf(matchStatusFrame)

            HandshakeTestProfile.TEST_2_MATCH_AND_MIN_MD_INFO -> {
                val minMdInfoFrame = CarLifeFrameCodec.createCommandFrame(
                    channel = CarLifeChannel.CONTROL,
                    serviceType = CarLifeMsgType.MSG_CMD_MD_INFO, // 0x00010004
                    commandBody = mobileDeviceInfo.encodeMinimal()
                )
                listOf(matchStatusFrame, minMdInfoFrame)
            }

            HandshakeTestProfile.TEST_3_MATCH_MIN_MD_INFO_BT_OOB -> {
                val minMdInfoFrame = CarLifeFrameCodec.createCommandFrame(
                    channel = CarLifeChannel.CONTROL,
                    serviceType = CarLifeMsgType.MSG_CMD_MD_INFO, // 0x00010004
                    commandBody = mobileDeviceInfo.encodeMinimal()
                )
                val emptyBtOobFrame = CarLifeFrameCodec.createCommandFrame(
                    channel = CarLifeChannel.CONTROL,
                    serviceType = CarLifeMsgType.MSG_CMD_MD_BT_OOB_INFO, // 0x00010006
                    commandBody = ByteArray(0)
                )
                listOf(matchStatusFrame, minMdInfoFrame, emptyBtOobFrame)
            }

            HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN -> {
                val mdInfoFrame = CarLifeFrameCodec.createCommandFrame(
                    channel = CarLifeChannel.CONTROL,
                    serviceType = CarLifeMsgType.MSG_CMD_MD_INFO, // 0x00010004
                    commandBody = mobileDeviceInfo.encode()
                )
                listOf(matchStatusFrame, mdInfoFrame)
            }
        }

        val summary = when (handshakeTestProfile) {
            HandshakeTestProfile.TEST_1_MATCH_ONLY ->
                "HU Protocol v${versionProto.majorVersion}.${versionProto.minorVersion} [Тест 1] -> TX ONLY MATCH_STATUS (0x00010002 [08 01])"
            HandshakeTestProfile.TEST_2_MATCH_AND_MIN_MD_INFO ->
                "HU Protocol v${versionProto.majorVersion}.${versionProto.minorVersion} [Тест 2] -> TX MATCH_STATUS (0x00010002) + min MD_INFO (0x00010004, ${mobileDeviceInfo.encodeMinimal().size}B)"
            HandshakeTestProfile.TEST_3_MATCH_MIN_MD_INFO_BT_OOB ->
                "HU Protocol v${versionProto.majorVersion}.${versionProto.minorVersion} [Тест 3] -> TX MATCH_STATUS (0x00010002) + min MD_INFO (0x00010004) + empty BT_OOB (0x00010006)"
            HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN ->
                "HU Protocol v${versionProto.majorVersion}.${versionProto.minorVersion} [Тест 4] -> TX MATCH_STATUS (0x00010002) + full MD_INFO (0x00010004) + Audio/Video + FG + ScreenOn"
        }

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
            outboundFrames = outbound,
            summary = summary
        )
    }

    private fun handleHuInfo(body: ByteArray): ControlTransitionResult {
        val huInfo = CarlifeDeviceInfoProto.decode(body)
        val encodedMdInfo = mobileDeviceInfo.encode()
        val mdInfoFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_INFO, // 0x00010004
            commandBody = encodedMdInfo
        )
        val summary = "RX HU_INFO (0x00018003: ${huInfo.manufacturer} ${huInfo.model}, OS=${huInfo.os}) -> TX MD_INFO (0x00010004: ${mobileDeviceInfo.model})"
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

    private fun handleModuleControl(frame: CarLifeFrame, body: ByteArray): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val reqStatus = CarlifeModuleStatusProto.decode(body)
        val replyStatus = CarlifeModuleStatusProto(
            moduleId = reqStatus.moduleId,
            statusId = reqStatus.statusId
        )
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MODULE_STATUS, // 0x00010023
            commandBody = replyStatus.encode()
        )
        val summary = "RX MSG_CMD_MODULE_CONTROL (module=${reqStatus.moduleId}, status=${reqStatus.statusId}) $hex -> TX MSG_CMD_MODULE_STATUS (0x00010023)"
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
            serviceType = CarLifeMsgType.MSG_CMD_MD_AUTHEN_RESULT, // 0x0001004B
            commandBody = ackProto
        )
        val summary = "RX MSG_CMD_HU_AUTHEN_RESULT $hex -> TX MSG_CMD_MD_AUTHEN_RESULT (0x0001004B)"
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
            serviceType = CarLifeMsgType.MSG_CMD_MD_FEATURE_CONFIG_RESPONSE, // 0x00010052
            commandBody = ByteArray(0)
        )
        val summary = "RX MSG_CMD_HU_FEATURE_CONFIG_REQUEST $hex -> TX MSG_CMD_MD_FEATURE_CONFIG_RESPONSE (0x00010052)"
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
            serviceType = CarLifeMsgType.MSG_CMD_MD_AES_KEY_RESPONSE, // 0x00010057
            commandBody = ackProto
        )
        val resp58 = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_AES_REC_RESPONSE, // 0x00010058
            commandBody = ackProto
        )
        val summary = "RX MSG_CMD_MD_AES_KEY_REQUEST $hex -> TX MSG_CMD_HU_AES_REC_RESPONSE (0x00010057/58)"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(resp57, resp58),
            summary = summary
        )
    }

    private fun handleStatsRequest(frame: CarLifeFrame): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MD_INFO, // 0x00010004
            commandBody = ByteArray(0)
        )
        val summary = "AUTH / STATS_REQ (0x00018004) $hex -> TX Empty Response (0x00010004)"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun handleMockAuthBypass(
        frame: CarLifeFrame,
        reqServiceId: Int,
        respServiceId: Int
    ): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val reqHex = "0x${reqServiceId.toUInt().toString(16).uppercase().padStart(8, '0')}"
        val respHex = "0x${respServiceId.toUInt().toString(16).uppercase().padStart(8, '0')}"
        val ackProto = byteArrayOf(0x08, 0x01) // status = true / 1
        val replyFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = respServiceId,
            commandBody = ackProto
        )
        val summary = "AUTH / UNKNOWN ($reqHex) $hex -> TX Mock Auth ($respHex [08 01])"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = listOf(replyFrame),
            summary = summary
        )
    }

    private fun handleUnknownOrAuthCommand(frame: CarLifeFrame, serviceId: Int): ControlTransitionResult {
        val hex = CarLifeFrameCodec.formatHexPreview(frame, maxPayloadBytes = 256)
        val serviceHex = "0x${serviceId.toUInt().toString(16).uppercase().padStart(8, '0')}"
        val isPassiveTelemetry = serviceId in CarLifeMsgType.MSG_CMD_CAR_VELOCITY..CarLifeMsgType.MSG_CMD_CAR_OIL ||
            serviceId == CarLifeMsgType.MSG_CMD_STATISTIC_INFO ||
            serviceId == CarLifeMsgType.MSG_CMD_STATISTIC_INFO_ALT ||
            serviceId == CarLifeMsgType.MSG_CMD_PAUSE_MEDIA
        val isHuRequest = ((serviceId ushr 16) == 0x0001) && ((serviceId and 0x8000) != 0) && !isPassiveTelemetry

        val outbound = if (isHuRequest) {
            val mockReplyId = serviceId and 0x00008000.inv()
            listOf(
                CarLifeFrameCodec.createCommandFrame(
                    channel = CarLifeChannel.CONTROL,
                    serviceType = mockReplyId,
                    commandBody = byteArrayOf(0x08, 0x01)
                )
            )
        } else {
            emptyList()
        }

        val replyNote = if (outbound.isNotEmpty()) {
            val replyHex = "0x${(serviceId and 0x00008000.inv()).toUInt().toString(16).uppercase().padStart(8, '0')}"
            " -> TX Mock Ack ($replyHex [08 01])"
        } else {
            ""
        }
        val summary = "AUTH / UNKNOWN ($serviceHex, ${frame.payload.size}B) $hex$replyNote"
        _sessionState.update { it.copy(lastControlAction = summary) }
        return ControlTransitionResult(
            updatedState = _sessionState.value,
            outboundFrames = outbound,
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

        // Reply with MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00010008);
        // SPS/PPS + IDR streaming begins upon VIDEO_ENCODER_INIT / VIDEO_ENCODER_START.
        val initDoneFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, // 0x00010008
            commandBody = encodedNegotiated
        )

        val summary = "Video Init (0x00018007): ${negotiatedProto.width}x${negotiatedProto.height}@${negotiatedProto.frameRate}fps -> TX INIT_DONE (0x00010008)"
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
            serviceType = CarLifeMsgType.MSG_CMD_MD_BT_OOB_INFO, // 0x00010006
            commandBody = ByteArray(0)
        )
        val summary = "RX MSG_CMD_HU_BT_OOB_INFO (0x00018005) -> TX MSG_CMD_MD_BT_OOB_INFO (0x00010006)"
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
        // Per CommonParams.smali: NEVER send non-existent 0x0001000A (0x0001800A is PAUSE).
        // Immediately unlock video channel 0x0002 and send:
        // 1. MSG_CMD_SCREEN_ON (0x00010018)
        // 2. MSG_CMD_SCREEN_USERPRESENT (0x0001001A)
        // 3. MSG_CMD_FOREGROUND (0x0001001B)
        // 4. MSG_CMD_REQUEST_GO_TO_FOREGROUND (0x00010038)
        val outbound = buildForegroundAndScreenOnFrames()
        val summary = "RX MSG_CMD_VIDEO_ENCODER_START (0x00018009, ${wireBytes}B) -> TX SCREEN_ON (0x00010018) + USERPRESENT (0x0001001A) + FOREGROUND (0x0001001B) + REQ_FG (0x00010038) -> Streaming 0x0002"
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
            outboundFrames = outbound,
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
