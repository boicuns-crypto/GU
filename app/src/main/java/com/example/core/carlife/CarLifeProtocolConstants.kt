package com.example.core.carlife

import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Baidu CarLife USB AOA Protocol Constants, Binary Frame Structures, and Protobuf Wire Codecs
 * tailored for Jetta VS7 (FAW-VW MQB platform) Head Unit + Samsung Galaxy S26 Ultra.
 *
 * ### Binary Wire Format (Always `ByteOrder.BIG_ENDIAN`):
 *
 * 1. **Standard Channels (`CHANNEL_CONTROL = 1`, `CHANNEL_TOUCH = 6`) — 8-Byte Outer Header + 8-Byte Inner Message Header**:
 *    - Outer AOA Multiplexer Header (8 bytes):
 *      - `Bytes [0..3]`: `Channel ID` (`Int32`, Big-Endian: `0x00000001` or `0x00000006`)
 *      - `Bytes [4..7]`: `Outer Payload Length` (`Int32`, Big-Endian: equals `8 + InnerProtobufBody.size`)
 *    - Inner Command Header (8 bytes inside outer payload):
 *      - `Bytes [8..11]`: `Inner Protobuf Body Length` (`Int32`, Big-Endian)
 *      - `Bytes [12..15]`: `Message Type ID` (`Int32`, Big-Endian, e.g. `0x00018001`)
 *      - `Bytes [16..N]`: `Protobuf Wire Payload`
 *
 * 2. **Streaming Media Channels (`CHANNEL_VIDEO = 2`, `CHANNEL_MEDIA_AUDIO = 3`, `CHANNEL_TTS_AUDIO = 4`, `CHANNEL_VR_AUDIO = 5`) — 12-Byte Header**:
 *    - `Bytes [0..3]`: `Channel ID` or `Message ID` (`Int32`, Big-Endian: `0x00000002` / `0x00020001`)
 *    - `Bytes [4..7]`: `Payload Length` (`Int32`, Big-Endian: length of H.264 NAL or PCM data)
 *    - `Bytes [8..11]`: `Timestamp` (`UInt32`, Big-Endian: presentation timestamp in milliseconds)
 *    - `Bytes [12..N]`: Raw H.264 Annex-B NAL unit (`00 00 00 01 ...`) or PCM audio samples
 */
object CarLifeProtocolConstants {

    // =========================================================================
    // 1. LOGICAL MULTIPLEXED CHANNEL IDs (AOA Bulk Endpoint Multiplexing)
    // =========================================================================
    const val CHANNEL_CONTROL: Int = 0x00000001
    const val CHANNEL_VIDEO: Int = 0x00000002
    const val CHANNEL_MEDIA_AUDIO: Int = 0x00000003
    const val CHANNEL_TTS_AUDIO: Int = 0x00000004
    const val CHANNEL_VR_AUDIO: Int = 0x00000005
    const val CHANNEL_TOUCH: Int = 0x00000006

    // Header sizes in bytes
    const val HEADER_SIZE_CONTROL_TOUCH: Int = 8
    const val HEADER_SIZE_MEDIA_STREAM: Int = 12
    const val INNER_CMD_HEADER_SIZE: Int = 8
    const val MAX_PACKET_PAYLOAD_BYTES: Int = 8 * 1024 * 1024 // 8 MiB safety bound

    // =========================================================================
    // 2. CHANNEL 1 (CONTROL / CMD) MESSAGE IDs
    // Direction bit 0x8000: HU -> MD (RX) = 0x000180xx, MD -> HU (TX) = 0x000100xx
    // =========================================================================
    const val MSG_CMD_HU_PROTOCOL_VERSION: Int = 0x00018001
    const val MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS: Int = 0x00010002
    const val MSG_CMD_HU_INFO: Int = 0x00018003
    const val MSG_CMD_MD_INFO: Int = 0x00010004
    const val MSG_CMD_HU_BT_OOB_INFO: Int = 0x00018005
    const val MSG_CMD_MD_BT_OOB_INFO: Int = 0x00010006
    const val MSG_CMD_VIDEO_ENCODER_INIT: Int = 0x00018007
    const val MSG_CMD_VIDEO_ENCODER_INIT_DONE: Int = 0x00010008
    const val MSG_CMD_VIDEO_ENCODER_START: Int = 0x00018009
    const val MSG_CMD_VIDEO_ENCODER_PAUSE: Int = 0x0001800A
    const val MSG_CMD_VIDEO_ENCODER_PAUSE_ALT: Int = 0x0001800B
    const val MSG_CMD_VIDEO_ENCODER_RESET: Int = 0x0001800C
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE: Int = 0x0001800D
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE: Int = 0x0001000E
    const val MSG_CMD_PAUSE_MEDIA: Int = 0x0001800E
    const val MSG_CMD_CAR_VELOCITY: Int = 0x0001800F
    const val MSG_CMD_CAR_GPS: Int = 0x00018010
    const val MSG_CMD_CAR_GYROSCOPE: Int = 0x00018011
    const val MSG_CMD_CAR_ACCELERATION: Int = 0x00018012
    const val MSG_CMD_CAR_OIL: Int = 0x00018013
    const val MSG_CMD_LAUNCH_MODE_NORMAL: Int = 0x00010014
    const val MSG_CMD_LAUNCH_MODE_PHONE: Int = 0x00010015
    const val MSG_CMD_LAUNCH_MODE_MAP: Int = 0x00010016
    const val MSG_CMD_LAUNCH_MODE_MUSIC: Int = 0x00010017
    const val MSG_CMD_SCREEN_ON: Int = 0x00010018
    const val MSG_CMD_SCREEN_OFF: Int = 0x00010019
    const val MSG_CMD_SCREEN_USERPRESENT: Int = 0x0001001A
    const val MSG_CMD_SCREEN_USER_PRESENT: Int = 0x0001001A
    const val MSG_CMD_FOREGROUND: Int = 0x0001001B
    const val MSG_CMD_BACKGROUND: Int = 0x0001001C
    const val MSG_CMD_GO_TO_DESKTOP: Int = 0x0001001E
    const val MSG_CMD_MIC_RECORD_WAKEUP_START: Int = 0x0001001F
    const val MSG_CMD_MIC_RECORD_END: Int = 0x00010020
    const val MSG_CMD_MIC_RECORD_RECOG_START: Int = 0x00010021
    const val MSG_CMD_MODULE_STATUS: Int = 0x00010023
    const val MSG_CMD_GO_TO_FOREGROUND: Int = 0x00018025
    const val MSG_CMD_MODULE_CONTROL: Int = 0x00018026
    const val MSG_CMD_STATISTIC_INFO: Int = 0x00018027
    const val MSG_CMD_REQUEST_GO_TO_FOREGROUND: Int = 0x00010038

    // =========================================================================
    // 3. CHANNEL 2 (VIDEO) MESSAGE IDs
    // =========================================================================
    const val MSG_VIDEO_DATA: Int = 0x00020001
    const val MSG_VIDEO_HEARTBEAT: Int = 0x00020002

    // =========================================================================
    // 4. CHANNEL 3..5 (AUDIO / TTS / VR) MESSAGE IDs
    // =========================================================================
    const val MSG_MEDIA_INIT: Int = 0x00030001
    const val MSG_MEDIA_STOP: Int = 0x00030002
    const val MSG_MEDIA_PAUSE: Int = 0x00030003
    const val MSG_MEDIA_RESUME_PLAY: Int = 0x00030004
    const val MSG_MEDIA_SEEK_TO: Int = 0x00030005
    const val MSG_MEDIA_DATA: Int = 0x00030006

    const val MSG_TTS_INIT: Int = 0x00040001
    const val MSG_TTS_STOP: Int = 0x00040002
    const val MSG_TTS_DATA: Int = 0x00040003

    const val MSG_VR_INIT: Int = 0x00050001
    const val MSG_VR_STOP: Int = 0x00050002
    const val MSG_VR_DATA: Int = 0x00050003

    // =========================================================================
    // 5. CHANNEL 6 (TOUCH & STEERING WHEEL HARDKEYS) MESSAGE IDs
    // =========================================================================
    const val MSG_TOUCH_ACTION: Int = 0x00068001
    const val MSG_TOUCH_ACTION_DOWN: Int = 0x00068002
    const val MSG_TOUCH_ACTION_UP: Int = 0x00068003
    const val MSG_TOUCH_ACTION_MOVE: Int = 0x00068004
    const val MSG_TOUCH_SINGLE_CLICK: Int = 0x00068005
    const val MSG_TOUCH_DOUBLE_CLICK: Int = 0x00068006
    const val MSG_TOUCH_LONG_PRESS: Int = 0x00068007
    const val MSG_TOUCH_CAR_HARD_KEY_CODE: Int = 0x00068008
    const val MSG_TOUCH_CAR_HARD_KEY_CODE_ALT: Int = 0x00060008
    const val MSG_TOUCH_UI_ACTION_SOUND: Int = 0x00060009

    // =========================================================================
    // 6. JETTA VS7 (MQB) STEERING WHEEL & MIB ROTARY HARDKEY CODES
    // =========================================================================
    const val KEYCODE_CARLIFE_HOME: Int = 0x00000001
    const val KEYCODE_CARLIFE_PHONE_CALL: Int = 0x00000002
    const val KEYCODE_CARLIFE_PHONE_END: Int = 0x00000003
    const val KEYCODE_CARLIFE_VR_START_LEGACY: Int = 0x00000009
    const val KEYCODE_CARLIFE_VR_STOP: Int = 0x0000000A
    const val KEYCODE_CARLIFE_SELECTOR_NEXT: Int = 0x0000000B
    const val KEYCODE_CARLIFE_SELECTOR_PREVIOUS: Int = 0x0000000C
    const val KEYCODE_CARLIFE_NAVI: Int = 0x0000000D // 13
    const val KEYCODE_CARLIFE_SELECTOR_OK: Int = 0x0000000D
    const val KEYCODE_CARLIFE_BACK: Int = 0x0000000E // 14
    const val KEYCODE_CARLIFE_SEEK_SUB: Int = 0x00000010 // 16
    const val KEYCODE_CARLIFE_SEEK_ADD: Int = 0x00000011 // 17
    const val KEYCODE_CARLIFE_VR_START: Int = 0x00000013 // 19
    const val KEYCODE_CARLIFE_SEEK_SUB_LEGACY: Int = 0x00000015
    const val KEYCODE_CARLIFE_SEEK_ADD_LEGACY: Int = 0x00000016
    const val KEYCODE_CARLIFE_PLAY_PAUSE_ALT: Int = 0x00000017 // 23
    const val KEYCODE_CARLIFE_MUTE: Int = 0x00000017
    const val KEYCODE_CARLIFE_PLAY_PAUSE: Int = 0x00000018 // 24

    // Protocol Match Status
    const val PROTOCOL_MATCH_SUCCESS: Int = 1
    const val PROTOCOL_MATCH_FAIL: Int = 2

    // Default Jetta VS7 (MQB) Projection Target
    const val DEFAULT_PROJECTION_WIDTH: Int = 1920
    const val DEFAULT_PROJECTION_HEIGHT: Int = 1080
    const val DEFAULT_PROJECTION_FPS: Int = 60
    const val DEFAULT_PROJECTION_BITRATE: Int = 12_000_000 // 12 Mbps Low-Latency H.264

    fun isMediaTimestampChannel(channelId: Int): Boolean {
        val norm = channelId and 0xFFFF
        return norm == CHANNEL_VIDEO ||
            norm == CHANNEL_MEDIA_AUDIO ||
            norm == CHANNEL_TTS_AUDIO ||
            norm == CHANNEL_VR_AUDIO
    }

    fun mapHardKeyToAndroidKeyCode(carLifeKeyCode: Int): Int = when (carLifeKeyCode) {
        KEYCODE_CARLIFE_HOME -> KeyEvent.KEYCODE_HOME
        KEYCODE_CARLIFE_PHONE_CALL -> KeyEvent.KEYCODE_CALL
        KEYCODE_CARLIFE_PHONE_END -> KeyEvent.KEYCODE_ENDCALL
        KEYCODE_CARLIFE_VR_START,
        KEYCODE_CARLIFE_VR_START_LEGACY -> KeyEvent.KEYCODE_VOICE_ASSIST
        KEYCODE_CARLIFE_VR_STOP -> KeyEvent.KEYCODE_ESCAPE
        KEYCODE_CARLIFE_SELECTOR_NEXT -> KeyEvent.KEYCODE_DPAD_RIGHT
        KEYCODE_CARLIFE_SELECTOR_PREVIOUS -> KeyEvent.KEYCODE_DPAD_LEFT
        KEYCODE_CARLIFE_NAVI -> KeyEvent.KEYCODE_NAVIGATE_IN
        KEYCODE_CARLIFE_BACK -> KeyEvent.KEYCODE_BACK
        KEYCODE_CARLIFE_SEEK_SUB,
        KEYCODE_CARLIFE_SEEK_SUB_LEGACY -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        KEYCODE_CARLIFE_SEEK_ADD,
        KEYCODE_CARLIFE_SEEK_ADD_LEGACY -> KeyEvent.KEYCODE_MEDIA_NEXT
        KEYCODE_CARLIFE_PLAY_PAUSE_ALT,
        KEYCODE_CARLIFE_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> KeyEvent.KEYCODE_UNKNOWN
    }
}

/**
 * Decoded touch packet from Channel 6 (`CHANNEL_TOUCH = 0x0006`).
 *
 * @property androidAction One of [MotionEvent.ACTION_DOWN], [MotionEvent.ACTION_MOVE], [MotionEvent.ACTION_UP].
 * @property rawX X coordinate in the Head Unit's touch coordinate system.
 * @property rawY Y coordinate in the Head Unit's touch coordinate system.
 * @property huWidth Width of the Head Unit's touch coordinate space (e.g. 1280 or 1920).
 * @property huHeight Height of the Head Unit's touch coordinate space (e.g. 720 or 1080).
 * @property synthesizeClickUp If `true` (e.g. `MSG_TOUCH_SINGLE_CLICK`), injects both `ACTION_DOWN` and `ACTION_UP`.
 */
data class CarLifeTouchPacket(
    val androidAction: Int,
    val rawX: Int,
    val rawY: Int,
    val huWidth: Int,
    val huHeight: Int,
    val synthesizeClickUp: Boolean = false,
    val isDoubleClick: Boolean = false,
    val isLongPress: Boolean = false
)

/**
 * Decoded steering wheel or rotary knob event from Channel 6 (`MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008`).
 */
data class CarLifeHardKeyPacket(
    val carLifeKeyCode: Int,
    val androidKeyCode: Int
)

/**
 * Raw multiplexed packet on the Baidu CarLife USB AOA wire.
 */
data class CarLifeRawPacket(
    val channelId: Int,
    val payloadLength: Int,
    val timestampMs: Long = 0L,
    val payload: ByteArray
) {
    val isMediaChannel: Boolean
        get() = CarLifeProtocolConstants.isMediaTimestampChannel(channelId)

    val headerSizeBytes: Int
        get() = if (isMediaChannel) {
            CarLifeProtocolConstants.HEADER_SIZE_MEDIA_STREAM
        } else {
            CarLifeProtocolConstants.HEADER_SIZE_CONTROL_TOUCH
        }

    /**
     * Parses the 8-byte inner command header (`[InnerLen: 2B][Reserved: 2B (00 00)][MsgType: 4B][Body: N bytes]`)
     * used on `CHANNEL_CONTROL (1)` and `CHANNEL_TOUCH (6)`.
     */
    fun extractInnerCommandOrNull(): Pair<Int, ByteArray>? {
        if (payload.size < CarLifeProtocolConstants.INNER_CMD_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val u16Len = buf.getShort(0).toInt() and 0xFFFF
        val u16Reserved = buf.getShort(2).toInt() and 0xFFFF
        val word0 = buf.getInt(0)
        val messageType = buf.getInt(4)
        val avail = payload.size - 8
        val bodyLength = when {
            u16Reserved == 0 && u16Len in 0..avail -> u16Len
            word0 in 0..avail -> word0
            else -> return null
        }
        val body = if (bodyLength > 0) {
            payload.copyOfRange(8, 8 + bodyLength)
        } else {
            ByteArray(0)
        }
        return messageType to body
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CarLifeRawPacket) return false
        return channelId == other.channelId &&
            payloadLength == other.payloadLength &&
            timestampMs == other.timestampMs &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = channelId
        result = 31 * result + payloadLength
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Binary packet builder & Protobuf serializer for Baidu CarLife Control, Video, Audio, and Touch packets.
 */
object CarLifePacketBuilder {

    /**
     * Wraps a Control (`0x0001`) or Touch (`0x0006`) command into a `CarLifeRawPacket`
     * with an 8-byte outer AOA header and an 8-byte inner `[BodyLen: 2B][Reserved: 2B][MsgType: 4B]` header.
     */
    fun buildCommandPacket(
        channelId: Int = CarLifeProtocolConstants.CHANNEL_CONTROL,
        messageType: Int,
        protobufBody: ByteArray = ByteArray(0)
    ): CarLifeRawPacket {
        val innerPayload = ByteBuffer.allocate(8 + protobufBody.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(protobufBody.size.toShort())
            .putShort(0x0000.toShort())
            .putInt(messageType)
            .put(protobufBody)
            .array()

        return CarLifeRawPacket(
            channelId = channelId,
            payloadLength = innerPayload.size,
            timestampMs = 0L,
            payload = innerPayload
        )
    }

    /**
     * Wraps a raw H.264 Annex-B NAL unit (`SPS/PPS` or `IDR/P-Slice`) into a 12-byte header
     * `CHANNEL_VIDEO (0x0002)` packet with a 32-bit Big-Endian presentation timestamp.
     */
    fun buildVideoNalPacket(
        nalBytes: ByteArray,
        timestampMs: Long = System.currentTimeMillis() and 0xFFFF_FFFFL
    ): CarLifeRawPacket {
        return CarLifeRawPacket(
            channelId = CarLifeProtocolConstants.CHANNEL_VIDEO,
            payloadLength = nalBytes.size,
            timestampMs = timestampMs and 0xFFFF_FFFFL,
            payload = nalBytes
        )
    }

    /**
     * Builds `MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS (0x00010002)` response packet.
     */
    fun buildProtocolVersionMatchPacket(
        matchStatus: Int = CarLifeProtocolConstants.PROTOCOL_MATCH_SUCCESS
    ): CarLifeRawPacket {
        val proto = ProtoWireWriter(8)
            .writeVarintField(1, matchStatus)
            .toByteArray()
        return buildCommandPacket(
            channelId = CarLifeProtocolConstants.CHANNEL_CONTROL,
            messageType = CarLifeProtocolConstants.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            protobufBody = proto
        )
    }

    /**
     * Builds `MSG_CMD_MD_INFO (0x00010004)` identifying the phone as Samsung Galaxy S26 Ultra (`SM-S948B`).
     */
    fun buildMobileDeviceInfoPacket(): CarLifeRawPacket {
        val proto = ProtoWireWriter(256)
            .writeStringField(1, "Android")
            .writeStringField(2, Build.BOARD.ifEmpty { "sun" })
            .writeStringField(3, Build.BOOTLOADER.ifEmpty { "S948BXXU1" })
            .writeStringField(4, "samsung")
            .writeStringField(5, "arm64-v8a")
            .writeStringField(7, "e3q")
            .writeStringField(8, Build.DISPLAY.ifEmpty { "UP1A.260205.001" })
            .writeStringField(10, "qcom")
            .writeStringField(13, "samsung")
            .writeStringField(14, "SM-S948B")
            .writeStringField(15, "e3qxeea")
            .writeStringField(19, Build.VERSION.RELEASE ?: "16")
            .writeStringField(20, Build.VERSION.SDK_INT.toString())
            .writeVarintField(21, Build.VERSION.SDK_INT)
            .toByteArray()

        return buildCommandPacket(
            channelId = CarLifeProtocolConstants.CHANNEL_CONTROL,
            messageType = CarLifeProtocolConstants.MSG_CMD_MD_INFO,
            protobufBody = proto
        )
    }

    /**
     * Builds `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00010008)` confirming H.264 encoder resolution & frame rate.
     */
    fun buildVideoEncoderInitDonePacket(
        width: Int,
        height: Int,
        frameRate: Int = 60
    ): CarLifeRawPacket {
        val proto = ProtoWireWriter(24)
            .writeVarintField(1, width)
            .writeVarintField(2, height)
            .writeVarintField(3, frameRate)
            .toByteArray()

        return buildCommandPacket(
            channelId = CarLifeProtocolConstants.CHANNEL_CONTROL,
            messageType = CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            protobufBody = proto
        )
    }

    /**
     * Builds `MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE (0x0001000D)`.
     */
    fun buildVideoFrameRateChangeDonePacket(frameRate: Int = 60): CarLifeRawPacket {
        val proto = ProtoWireWriter(8)
            .writeVarintField(1, frameRate)
            .toByteArray()

        return buildCommandPacket(
            channelId = CarLifeProtocolConstants.CHANNEL_CONTROL,
            messageType = CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE,
            protobufBody = proto
        )
    }

    /**
     * Builds a Heartbeat packet (`MSG_VIDEO_HEARTBEAT = 0x00020002`) so the Jetta VS7 Head Unit
     * watchdog timer never resets the USB AOA session.
     */
    fun buildHeartbeatPacket(): CarLifeRawPacket {
        return buildCommandPacket(
            channelId = CarLifeProtocolConstants.CHANNEL_CONTROL,
            messageType = CarLifeProtocolConstants.MSG_VIDEO_HEARTBEAT,
            protobufBody = ByteArray(0)
        )
    }

    /**
     * Parses `MSG_CMD_VIDEO_ENCODER_INIT (0x00018007)` payload `(width, height, frameRate)`.
     */
    fun parseVideoEncoderInitRequest(
        body: ByteArray,
        defaultWidth: Int = 1280,
        defaultHeight: Int = 720
    ): Triple<Int, Int, Int> {
        var width = defaultWidth
        var height = defaultHeight
        var fps = 60
        val reader = ProtoWireReader(body)
        while (reader.readTag()) {
            when (reader.fieldNumber) {
                1 -> width = reader.readVarint32()
                2 -> height = reader.readVarint32()
                3 -> fps = reader.readVarint32()
                else -> reader.skipField()
            }
        }
        return Triple(
            width.coerceIn(480, 3840),
            height.coerceIn(272, 2160),
            fps.coerceIn(15, 60)
        )
    }

    /**
     * Parses a Channel 6 (`CHANNEL_TOUCH = 0x0006`) command payload into a [CarLifeTouchPacket]
     * or [CarLifeHardKeyPacket].
     */
    fun parseTouchOrKeyPayload(
        messageType: Int,
        body: ByteArray,
        defaultHuWidth: Int = 1280,
        defaultHuHeight: Int = 720
    ): Any? {
        return when (messageType) {
            CarLifeProtocolConstants.MSG_TOUCH_ACTION -> {
                var action = 0
                var x = 0
                var y = 0
                var w = defaultHuWidth
                var h = defaultHuHeight
                val reader = ProtoWireReader(body)
                while (reader.readTag()) {
                    when (reader.fieldNumber) {
                        1 -> action = reader.readVarint32()
                        2 -> x = reader.readVarint32()
                        3 -> y = reader.readVarint32()
                        4 -> w = reader.readVarint32().takeIf { it > 0 } ?: defaultHuWidth
                        5 -> h = reader.readVarint32().takeIf { it > 0 } ?: defaultHuHeight
                        else -> reader.skipField()
                    }
                }
                val motionAction = when (action) {
                    0 -> MotionEvent.ACTION_DOWN
                    1 -> MotionEvent.ACTION_UP
                    2 -> MotionEvent.ACTION_MOVE
                    else -> MotionEvent.ACTION_DOWN
                }
                CarLifeTouchPacket(
                    androidAction = motionAction,
                    rawX = x,
                    rawY = y,
                    huWidth = w,
                    huHeight = h,
                    synthesizeClickUp = false
                )
            }

            CarLifeProtocolConstants.MSG_TOUCH_ACTION_DOWN,
            CarLifeProtocolConstants.MSG_TOUCH_ACTION_MOVE,
            CarLifeProtocolConstants.MSG_TOUCH_ACTION_UP,
            CarLifeProtocolConstants.MSG_TOUCH_SINGLE_CLICK,
            CarLifeProtocolConstants.MSG_TOUCH_DOUBLE_CLICK,
            CarLifeProtocolConstants.MSG_TOUCH_LONG_PRESS -> {
                val (x, y) = parseSinglePointCoordinates(body)
                val motionAction = when (messageType) {
                    CarLifeProtocolConstants.MSG_TOUCH_ACTION_DOWN,
                    CarLifeProtocolConstants.MSG_TOUCH_LONG_PRESS -> MotionEvent.ACTION_DOWN
                    CarLifeProtocolConstants.MSG_TOUCH_ACTION_MOVE -> MotionEvent.ACTION_MOVE
                    CarLifeProtocolConstants.MSG_TOUCH_ACTION_UP -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_DOWN
                }
                val isClick = messageType == CarLifeProtocolConstants.MSG_TOUCH_SINGLE_CLICK ||
                    messageType == CarLifeProtocolConstants.MSG_TOUCH_DOUBLE_CLICK
                CarLifeTouchPacket(
                    androidAction = motionAction,
                    rawX = x,
                    rawY = y,
                    huWidth = defaultHuWidth,
                    huHeight = defaultHuHeight,
                    synthesizeClickUp = isClick
                )
            }

            CarLifeProtocolConstants.MSG_TOUCH_CAR_HARD_KEY_CODE,
            CarLifeProtocolConstants.MSG_TOUCH_CAR_HARD_KEY_CODE_ALT -> {
                var keyCode = 0
                if (body.isNotEmpty() && (body[0].toInt() and 0xFF) == 0x08) {
                    val reader = ProtoWireReader(body)
                    while (reader.readTag()) {
                        if (reader.fieldNumber == 1) keyCode = reader.readVarint32()
                        else reader.skipField()
                    }
                } else if (body.size >= 4) {
                    keyCode = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).int
                }
                CarLifeHardKeyPacket(
                    carLifeKeyCode = keyCode,
                    androidKeyCode = CarLifeProtocolConstants.mapHardKeyToAndroidKeyCode(keyCode)
                )
            }

            else -> null
        }
    }

    private fun parseSinglePointCoordinates(body: ByteArray): Pair<Int, Int> {
        var x = 0
        var y = 0
        if (body.isNotEmpty() && body[0].toInt() == 0x08) {
            val reader = ProtoWireReader(body)
            while (reader.readTag()) {
                when (reader.fieldNumber) {
                    1 -> x = reader.readVarint32()
                    2 -> y = reader.readVarint32()
                    else -> reader.skipField()
                }
            }
        } else if (body.size >= 12) {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            buf.int // action
            x = buf.int
            y = buf.int
        } else if (body.size >= 8) {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            x = buf.int
            y = buf.int
        }
        return x to y
    }

    private class ProtoWireReader(private val bytes: ByteArray) {
        private var pos = 0
        var fieldNumber = 0
            private set
        var wireType = 0
            private set

        fun readTag(): Boolean {
            if (pos >= bytes.size) return false
            val tag = readVarint32()
            if (tag == 0) return false
            fieldNumber = tag ushr 3
            wireType = tag and 0x07
            return true
        }

        fun readVarint32(): Int {
            var res = 0
            var shift = 0
            while (shift < 32 && pos < bytes.size) {
                val b = bytes[pos++].toInt()
                res = res or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return res
                shift += 7
            }
            return res
        }

        fun skipField() {
            when (wireType) {
                0 -> readVarint32()
                1 -> pos = (pos + 8).coerceAtMost(bytes.size)
                2 -> {
                    val len = readVarint32()
                    if (len > 0) pos = (pos + len).coerceAtMost(bytes.size)
                }
                5 -> pos = (pos + 4).coerceAtMost(bytes.size)
                else -> pos = bytes.size
            }
        }
    }

    private class ProtoWireWriter(initialSize: Int) {
        private val out = ByteArrayOutputStream(initialSize)

        fun writeVarintField(fieldNum: Int, value: Int): ProtoWireWriter {
            writeRawVarint((fieldNum shl 3) or 0)
            writeRawVarint(value)
            return this
        }

        fun writeStringField(fieldNum: Int, value: String): ProtoWireWriter {
            if (value.isEmpty()) return this
            val data = value.toByteArray(StandardCharsets.UTF_8)
            writeRawVarint((fieldNum shl 3) or 2)
            writeRawVarint(data.size)
            out.write(data)
            return this
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeRawVarint(value: Int) {
            var v = value
            while (true) {
                if ((v and 0x7F.inv()) == 0) {
                    out.write(v)
                    return
                } else {
                    out.write((v and 0x7F) or 0x80)
                    v = v ushr 7
                }
            }
        }
    }
}
