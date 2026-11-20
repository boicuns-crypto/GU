package com.example.protocol.carlife

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Logical multiplexing channels defined by the Baidu CarLife USB AOA specification.
 *
 * - Control (`0x0001`) and Touch (`0x0006`) use an **8-byte binary header**:
 *   `[Channel ID: 4B Big-Endian][Payload Length: 4B Big-Endian]`
 * - Video (`0x0002`) and Audio (`0x0003`..`0x0005`) use a **12-byte binary header**:
 *   `[Channel ID: 4B Big-Endian][Payload Length: 4B Big-Endian][Timestamp: 4B Big-Endian]`
 */
enum class CarLifeChannel(
    val id: Int,
    val label: String,
    val hasTimestampHeader: Boolean
) {
    CONTROL(id = 0x0001, label = "CMD / Control", hasTimestampHeader = false),
    VIDEO(id = 0x0002, label = "H.264 Video", hasTimestampHeader = true),
    MEDIA_AUDIO(id = 0x0003, label = "PCM Media Audio", hasTimestampHeader = true),
    TTS_AUDIO(id = 0x0004, label = "PCM TTS Audio", hasTimestampHeader = true),
    VR_AUDIO(id = 0x0005, label = "VR Mic Audio", hasTimestampHeader = true),
    TOUCH(id = 0x0006, label = "Touch & HardKey", hasTimestampHeader = false);

    val headerSizeBytes: Int
        get() = if (hasTimestampHeader) CarLifeFrameCodec.MEDIA_HEADER_SIZE else CarLifeFrameCodec.STANDARD_HEADER_SIZE

    companion object {
        fun fromId(rawChannelId: Int): CarLifeChannel? {
            val normalized = rawChannelId and 0xFFFF
            return entries.firstOrNull { it.id == normalized }
        }
    }
}

/**
 * Canonical Baidu CarLife Service / Message Type IDs carried inside Control (`0x0001`)
 * and Touch (`0x0006`) channel payloads (Jetta VS7 / MQB Head Unit specification).
 */
object CarLifeMsgType {
    // Control Channel (0x0001) — Jetta VS7 / Baidu CarLife Direction Bit Mapping:
    // HU -> MD (Head Unit to Phone, RX): 0x000180xx (bit 15 = 1)
    // MD -> HU (Phone to Head Unit, TX): 0x000100xx (bit 15 = 0)
    const val MSG_CMD_HU_PROTOCOL_VERSION = 0x00018001
    const val MSG_CMD_HU_PROTOCOL_VERSION_ALT = 0x00010001
    const val MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
    const val MSG_CMD_HU_INFO = 0x00018003
    const val MSG_CMD_MD_INFO = 0x00010004
    const val MSG_CMD_HU_STATS_REQUEST = 0x00018004
    const val MSG_CMD_HU_BT_OOB_INFO = 0x00018005
    const val MSG_CMD_HU_BT_OOB_INFO_ALT = 0x00010005
    const val MSG_CMD_MD_BT_OOB_INFO = 0x00010006
    const val MSG_CMD_VIDEO_ENCODER_INIT = 0x00018007
    const val MSG_CMD_VIDEO_ENCODER_INIT_ALT = 0x00010007
    const val MSG_CMD_VIDEO_ENCODER_INIT_DONE = 0x00010008
    const val MSG_CMD_VIDEO_ENCODER_START = 0x00018009
    const val MSG_CMD_VIDEO_ENCODER_START_ALT = 0x00010009
    const val MSG_CMD_VIDEO_ENCODER_PAUSE = 0x0001800A
    const val MSG_CMD_VIDEO_ENCODER_PAUSE_ALT = 0x0001800B
    const val MSG_CMD_VIDEO_ENCODER_RESET = 0x0001800C
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800D
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000E
    const val MSG_CMD_AUTHEN_REQUEST_0B = 0x0001000B
    const val MSG_CMD_AUTHEN_RESPONSE_0B = 0x0001800B
    const val MSG_CMD_AUTHEN_REQUEST_0C = 0x0001000C
    const val MSG_CMD_AUTHEN_RESPONSE_0C = 0x0001800C
    const val MSG_CMD_AUTHEN_REQUEST_0D = 0x0001000D
    const val MSG_CMD_AUTHEN_RESPONSE_0D = 0x0001800D
    const val MSG_CMD_AUTHEN_RESPONSE_0E = 0x0001800E
    const val MSG_CMD_PAUSE_MEDIA = 0x0001800E
    const val MSG_CMD_CAR_VELOCITY = 0x0001800F
    const val MSG_CMD_CAR_GPS = 0x00018010
    const val MSG_CMD_CAR_GYROSCOPE = 0x00018011
    const val MSG_CMD_CAR_ACCELERATION = 0x00018012
    const val MSG_CMD_CAR_OIL = 0x00018013
    const val MSG_CMD_LAUNCH_MODE_NORMAL = 0x00010014
    const val MSG_CMD_LAUNCH_MODE_PHONE = 0x00010015
    const val MSG_CMD_LAUNCH_MODE_MAP = 0x00010016
    const val MSG_CMD_LAUNCH_MODE_MUSIC = 0x00010017
    const val MSG_CMD_SCREEN_ON = 0x00010018
    const val MSG_CMD_SCREEN_OFF = 0x00010019
    const val MSG_CMD_SCREEN_USERPRESENT = 0x0001001A
    const val MSG_CMD_SCREEN_USER_PRESENT = 0x0001001A
    const val MSG_CMD_FOREGROUND = 0x0001001B
    const val MSG_CMD_BACKGROUND = 0x0001001C
    const val MSG_CMD_GO_TO_DESKTOP = 0x0001001E
    const val MSG_CMD_MIC_RECORD_WAKEUP_START = 0x0001001F
    const val MSG_CMD_MIC_RECORD_END = 0x00010020
    const val MSG_CMD_MIC_RECORD_RECOG_START = 0x00010021
    const val MSG_CMD_MODULE_STATUS = 0x00010023
    const val MSG_CMD_MODULE_STATUS_ALT = 0x00018023
    const val MSG_CMD_STATISTIC_INFO_ALT = 0x00018024
    const val MSG_CMD_GO_TO_FOREGROUND = 0x00018025
    const val MSG_CMD_MODULE_CONTROL = 0x00018026
    const val MSG_CMD_MODULE_CONTROL_ALT = 0x00010026
    const val MSG_CMD_STATISTIC_INFO = 0x00018027
    const val MSG_CMD_REQUEST_GO_TO_FOREGROUND = 0x00010038
    const val MSG_CMD_HU_RSA_PUBLIC_KEY_REQUEST = 0x00018040
    const val MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE = 0x00010040
    const val MSG_CMD_VEHICLE_CERTIFICATE_REQUEST = 0x00018041
    const val MSG_CMD_VEHICLE_CERTIFICATE_RESPONSE = 0x00010041
    const val MSG_CMD_HU_AUTHEN_REQUEST = 0x00018048
    const val MSG_CMD_HU_AUTHEN_REQUEST_ALT = 0x00010048
    const val MSG_CMD_HU_AUTHEN_REQUEST_LEGACY = 0x00018030
    const val MSG_CMD_MD_AUTHEN_RESPONSE = 0x00010049
    const val MSG_CMD_MD_AUTHEN_RESPONSE_LEGACY = 0x00010031
    const val MSG_CMD_HU_AUTHEN_RESULT = 0x0001804A
    const val MSG_CMD_HU_AUTHEN_RESULT_ALT = 0x0001004A
    const val MSG_CMD_MD_AUTHEN_RESULT = 0x0001004B
    const val MSG_CMD_HU_FEATURE_CONFIG_REQUEST = 0x00018051
    const val MSG_CMD_MD_FEATURE_CONFIG_REQUEST = 0x00010050
    const val MSG_CMD_MD_FEATURE_CONFIG_RESPONSE = 0x00010052
    const val MSG_CMD_MD_AES_KEY_REQUEST = 0x00018056
    const val MSG_CMD_MD_AES_KEY_REQUEST_ALT = 0x00010056
    const val MSG_CMD_HU_AES_KEY_REQUEST = 0x00018057
    const val MSG_CMD_MD_AES_KEY_RESPONSE = 0x00010057
    const val MSG_CMD_HU_AES_REC_RESPONSE = 0x00010058

    // Video Channel (0x0002) — H.264 NAL Stream & Heartbeat (MD -> HU: bit 0x8000 cleared -> 0x00020001)
    const val MSG_VIDEO_DATA = 0x00020001
    const val MSG_VIDEO_DATA_ALT = 0x00028001
    const val MSG_VIDEO_HEARTBEAT = 0x00020002
    const val MSG_VIDEO_HEARTBEAT_ALT = 0x00028002

    // Audio Channels (0x0003..0x0005)
    const val MSG_MEDIA_INIT = 0x00030001
    const val MSG_MEDIA_STOP = 0x00030002
    const val MSG_MEDIA_PAUSE = 0x00030003
    const val MSG_MEDIA_RESUME_PLAY = 0x00030004
    const val MSG_MEDIA_DATA = 0x00030006

    // Touch & Key Channel (0x0006)
    const val MSG_TOUCH_ACTION = 0x00068001
    const val MSG_TOUCH_ACTION_DOWN = 0x00068002
    const val MSG_TOUCH_ACTION_UP = 0x00068003
    const val MSG_TOUCH_ACTION_MOVE = 0x00068004
    const val MSG_TOUCH_SINGLE_CLICK = 0x00068005
    const val MSG_TOUCH_DOUBLE_CLICK = 0x00068006
    const val MSG_TOUCH_LONG_PRESS = 0x00068007
    const val MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008
    const val MSG_TOUCH_UI_ACTION_SOUND = 0x00060009

    fun isCarLifeServiceId(candidate: Int): Boolean {
        val high16 = (candidate ushr 16) and 0xFFFF
        val low16 = candidate and 0xFFFF
        return (high16 in 0x0001..0x0006) && (low16 != 0)
    }

    fun nameOf(serviceType: Int): String = when (serviceType) {
        MSG_CMD_HU_PROTOCOL_VERSION,
        MSG_CMD_HU_PROTOCOL_VERSION_ALT -> "MSG_CMD_HU_PROTOCOL_VERSION"
        MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS -> "MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS"
        MSG_CMD_HU_INFO -> "MSG_CMD_HU_INFO"
        MSG_CMD_MD_INFO -> "MSG_CMD_MD_INFO"
        MSG_CMD_HU_STATS_REQUEST -> "AUTH / STATS_REQ (0x00018004)"
        MSG_CMD_HU_BT_OOB_INFO,
        MSG_CMD_HU_BT_OOB_INFO_ALT -> "MSG_CMD_HU_BT_OOB_INFO"
        MSG_CMD_MD_BT_OOB_INFO -> "MSG_CMD_MD_BT_OOB_INFO"
        MSG_CMD_VIDEO_ENCODER_INIT,
        MSG_CMD_VIDEO_ENCODER_INIT_ALT -> "MSG_CMD_VIDEO_ENCODER_INIT"
        MSG_CMD_VIDEO_ENCODER_INIT_DONE -> "MSG_CMD_VIDEO_ENCODER_INIT_DONE"
        MSG_CMD_VIDEO_ENCODER_START,
        MSG_CMD_VIDEO_ENCODER_START_ALT -> "MSG_CMD_VIDEO_ENCODER_START"
        MSG_CMD_VIDEO_ENCODER_PAUSE,
        MSG_CMD_VIDEO_ENCODER_PAUSE_ALT -> "MSG_CMD_VIDEO_ENCODER_PAUSE"
        MSG_CMD_VIDEO_ENCODER_RESET -> "MSG_CMD_VIDEO_ENCODER_RESET"
        MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> "MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE"
        MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE -> "MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE"
        MSG_CMD_SCREEN_ON -> "MSG_CMD_SCREEN_ON"
        MSG_CMD_SCREEN_OFF -> "MSG_CMD_SCREEN_OFF"
        MSG_CMD_SCREEN_USERPRESENT -> "MSG_CMD_SCREEN_USERPRESENT"
        MSG_CMD_FOREGROUND -> "MSG_CMD_FOREGROUND"
        MSG_CMD_BACKGROUND -> "MSG_CMD_BACKGROUND"
        MSG_CMD_GO_TO_DESKTOP -> "MSG_CMD_GO_TO_DESKTOP"
        MSG_CMD_GO_TO_FOREGROUND -> "MSG_CMD_GO_TO_FOREGROUND"
        MSG_CMD_REQUEST_GO_TO_FOREGROUND -> "MSG_CMD_REQUEST_GO_TO_FOREGROUND"
        MSG_CMD_MODULE_STATUS,
        MSG_CMD_MODULE_STATUS_ALT -> "MSG_CMD_MODULE_STATUS"
        MSG_CMD_STATISTIC_INFO,
        MSG_CMD_STATISTIC_INFO_ALT -> "MSG_CMD_STATISTIC_INFO"
        MSG_CMD_MODULE_CONTROL,
        MSG_CMD_MODULE_CONTROL_ALT -> "MSG_CMD_MODULE_CONTROL"
        MSG_CMD_HU_RSA_PUBLIC_KEY_REQUEST -> "AUTH / HU_RSA_PUBLIC_KEY (0x00018040)"
        MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE -> "AUTH / MD_RSA_PUBLIC_KEY_RESP (0x00010040)"
        MSG_CMD_VEHICLE_CERTIFICATE_REQUEST -> "AUTH / VEHICLE_CERT (0x00018041)"
        MSG_CMD_VEHICLE_CERTIFICATE_RESPONSE -> "AUTH / VEHICLE_CERT_RESP (0x00010041)"
        MSG_CMD_HU_AUTHEN_REQUEST,
        MSG_CMD_HU_AUTHEN_REQUEST_ALT,
        MSG_CMD_HU_AUTHEN_REQUEST_LEGACY -> "MSG_CMD_HU_AUTHEN_REQUEST"
        MSG_CMD_MD_AUTHEN_RESPONSE,
        MSG_CMD_MD_AUTHEN_RESPONSE_LEGACY -> "MSG_CMD_MD_AUTHEN_RESPONSE"
        MSG_CMD_HU_AUTHEN_RESULT,
        MSG_CMD_HU_AUTHEN_RESULT_ALT -> "MSG_CMD_HU_AUTHEN_RESULT"
        MSG_CMD_MD_AUTHEN_RESULT -> "MSG_CMD_MD_AUTHEN_RESULT"
        MSG_CMD_HU_FEATURE_CONFIG_REQUEST,
        MSG_CMD_MD_FEATURE_CONFIG_REQUEST -> "MSG_CMD_HU_FEATURE_CONFIG_REQUEST"
        MSG_CMD_MD_FEATURE_CONFIG_RESPONSE -> "MSG_CMD_MD_FEATURE_CONFIG_RESPONSE"
        MSG_CMD_MD_AES_KEY_REQUEST,
        MSG_CMD_MD_AES_KEY_REQUEST_ALT,
        MSG_CMD_HU_AES_KEY_REQUEST -> "MSG_CMD_MD_AES_KEY_REQUEST"
        MSG_CMD_MD_AES_KEY_RESPONSE,
        MSG_CMD_HU_AES_REC_RESPONSE -> "MSG_CMD_HU_AES_REC_RESPONSE"
        MSG_VIDEO_DATA,
        MSG_VIDEO_DATA_ALT -> "MSG_VIDEO_DATA"
        MSG_VIDEO_HEARTBEAT,
        MSG_VIDEO_HEARTBEAT_ALT -> "MSG_VIDEO_HEARTBEAT"
        MSG_TOUCH_ACTION -> "MSG_TOUCH_ACTION"
        MSG_TOUCH_ACTION_DOWN -> "MSG_TOUCH_ACTION_DOWN"
        MSG_TOUCH_ACTION_UP -> "MSG_TOUCH_ACTION_UP"
        MSG_TOUCH_ACTION_MOVE -> "MSG_TOUCH_ACTION_MOVE"
        MSG_TOUCH_SINGLE_CLICK -> "MSG_TOUCH_SINGLE_CLICK"
        MSG_TOUCH_DOUBLE_CLICK -> "MSG_TOUCH_DOUBLE_CLICK"
        MSG_TOUCH_LONG_PRESS -> "MSG_TOUCH_LONG_PRESS"
        MSG_TOUCH_CAR_HARD_KEY_CODE -> "MSG_TOUCH_CAR_HARD_KEY_CODE"
        else -> "AUTH / UNKNOWN (0x${serviceType.toUInt().toString(16).uppercase().padStart(8, '0')})"
    }
}

/**
 * Lock-free pool of native direct [ByteBuffer] instances (`ByteBuffer.allocateDirect`)
 * to eliminate heap allocations and GC pauses during 60fps H.264 NAL & Touch USB AOA transfers.
 */
object DirectBufferPool {
    private const val SMALL_BUFFER_CAPACITY = 64 * 1024 // 64 KiB for control, touch, audio & P-frames
    private const val LARGE_BUFFER_CAPACITY = 512 * 1024 // 512 KiB for 1080p IDR keyframes
    private const val MAX_POOLED_BUFFERS = 16

    private val smallPool = ConcurrentLinkedQueue<ByteBuffer>()
    private val largePool = ConcurrentLinkedQueue<ByteBuffer>()

    fun acquire(minCapacity: Int): ByteBuffer {
        return when {
            minCapacity <= SMALL_BUFFER_CAPACITY -> {
                (smallPool.poll() ?: ByteBuffer.allocateDirect(SMALL_BUFFER_CAPACITY)).apply {
                    clear()
                    order(ByteOrder.BIG_ENDIAN)
                }
            }
            minCapacity <= LARGE_BUFFER_CAPACITY -> {
                (largePool.poll() ?: ByteBuffer.allocateDirect(LARGE_BUFFER_CAPACITY)).apply {
                    clear()
                    order(ByteOrder.BIG_ENDIAN)
                }
            }
            else -> ByteBuffer.allocateDirect(minCapacity).order(ByteOrder.BIG_ENDIAN)
        }
    }

    fun release(buffer: ByteBuffer) {
        if (!buffer.isDirect) return
        buffer.clear()
        when (buffer.capacity()) {
            SMALL_BUFFER_CAPACITY -> if (smallPool.size < MAX_POOLED_BUFFERS) smallPool.offer(buffer)
            LARGE_BUFFER_CAPACITY -> if (largePool.size < MAX_POOLED_BUFFERS) largePool.offer(buffer)
        }
    }
}

/**
 * Parsed binary header of a Baidu CarLife frame.
 */
data class CarLifeHeader(
    val channel: CarLifeChannel,
    val rawChannelId: Int = channel.id,
    val payloadLength: Int,
    val timestamp: Long = 0L,
    val includeOuterTimestamp: Boolean = channel.hasTimestampHeader
) {
    val headerSizeBytes: Int
        get() = if (includeOuterTimestamp) CarLifeFrameCodec.MEDIA_HEADER_SIZE else CarLifeFrameCodec.STANDARD_HEADER_SIZE
}

/**
 * Complete multiplexed Baidu CarLife packet consisting of its 8-byte or 12-byte header and payload.
 */
data class CarLifeFrame(
    val header: CarLifeHeader,
    val payload: ByteArray
) {
    val channel: CarLifeChannel
        get() = header.channel

    val totalFrameBytes: Int
        get() = header.headerSizeBytes + payload.size

    /**
     * Extracts the Control (`0x0001`), Touch (`0x0006`), or AOA 20B Video (`0x0002`) inner message:
     * 1. **12-byte wire frame (`payload.size == 4`)**:
     *    `bytes[8..11]` (`payload[0..3]`) is directly `serviceType` (`protoPayload = ByteArray(0)`).
     * 2. **16+ byte wire frame (`payload.size >= 8`)**:
     *    - Official Baidu CarLife 8-byte Inner Command Header:
     *      - `payload[0..1]` (`wire bytes[8..9]`): `protoLen` (`UInt16` Big-Endian, e.g. `00 04`, `00 02`)
     *      - `payload[2..3]` (`wire bytes[10..11]`): `reserved` (`UInt16` Big-Endian, `00 00`)
     *      - `payload[4..7]` (`wire bytes[12..15]`): `serviceId` (`Int32` Big-Endian, e.g. `0x00010001`, `0x00018002`)
     *      - `payload[8..N]` (`wire bytes[16..N]`): `protoBytes` (`protoLen` bytes)
     *    - AOA 20B Video Message (`channel == VIDEO`, `payload.size >= 12`):
     *      - `payload[0..3]`: `h264Data.size`
     *      - `payload[4..7]`: `timestampMs`
     *      - `payload[8..11]`: `0x00020001` (`MSG_VIDEO_DATA`)
     *      - `payload[12..N]`: `h264Data`
     */
    fun parseCommandMessageOrNull(): CarLifeCommandMessage? {
        if (payload.isEmpty()) return null
        if (payload.size < 4) {
            var padded = 0
            for (b in payload) {
                padded = (padded shl 8) or (b.toInt() and 0xFF)
            }
            return CarLifeCommandMessage(serviceType = padded, body = ByteArray(0))
        }

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val word0 = buf.getInt(0) // wire bytes[8..11]

        // Case 1: 12-byte total wire frame (8B channel header + 4B payload = serviceId)
        if (payload.size in 4..7) {
            val body = if (payload.size > 4) payload.copyOfRange(4, payload.size) else ByteArray(0)
            return CarLifeCommandMessage(
                serviceType = word0,
                body = body
            )
        }

        val u16MsgLen = buf.getShort(0).toInt() and 0xFFFF
        val u16Reserved = buf.getShort(2).toInt() and 0xFFFF
        val word1 = buf.getInt(4) // wire bytes[12..15]
        val word2 = if (payload.size >= 12) buf.getInt(8) else 0 // wire bytes[16..19]

        // Special check for AOA 20B Video Packet: [nalSize:4B][timestampMs:4B][0x00020001 / 0x00020002:4B][h264Data]
        if (channel == CarLifeChannel.VIDEO && payload.size >= 12 &&
            (word2 == CarLifeMsgType.MSG_VIDEO_DATA ||
                word2 == CarLifeMsgType.MSG_VIDEO_DATA_ALT ||
                word2 == CarLifeMsgType.MSG_VIDEO_HEARTBEAT ||
                word2 == CarLifeMsgType.MSG_VIDEO_HEARTBEAT_ALT)
        ) {
            val avail = payload.size - 12
            val safeLen = if (word0 in 0..avail) word0 else avail
            val body = if (safeLen > 0) payload.copyOfRange(12, 12 + safeLen) else ByteArray(0)
            return CarLifeCommandMessage(
                serviceType = word2,
                body = body
            )
        }

        val word0IsService = CarLifeMsgType.isCarLifeServiceId(word0) && u16MsgLen == 0x0001
        val word1IsService = CarLifeMsgType.isCarLifeServiceId(word1)
        val word2IsService = payload.size >= 12 && CarLifeMsgType.isCarLifeServiceId(word2)
        val word3 = if (payload.size >= 16) buf.getInt(12) else 0
        val word3IsService = payload.size >= 16 && CarLifeMsgType.isCarLifeServiceId(word3)

        return when {
            // Subcase 2A (Standard CarLife Inner Header): bytes[8..9]=u16MsgLen, bytes[10..11]=00 00, bytes[12..15]=serviceId
            word1IsService -> {
                val avail = payload.size - 8
                val safeLen = when {
                    u16Reserved == 0 && u16MsgLen in 0..avail -> u16MsgLen
                    word0 in 0..avail -> word0
                    else -> avail
                }
                val body = if (safeLen > 0) payload.copyOfRange(8, 8 + safeLen) else ByteArray(0)
                CarLifeCommandMessage(
                    serviceType = word1,
                    body = body
                )
            }

            // Subcase 2B: bytes[8..11] (word0) is already 0x0001xxxx / 0x0006xxxx serviceId
            word0IsService && !word1IsService -> {
                val body = if (word1 in 0..(payload.size - 8) && (word1 == payload.size - 8 || word1 == 0)) {
                    if (word1 > 0) payload.copyOfRange(8, 8 + word1) else payload.copyOfRange(8, payload.size)
                } else {
                    payload.copyOfRange(4, payload.size)
                }
                CarLifeCommandMessage(
                    serviceType = word0,
                    body = body
                )
            }

            // Subcase 2C: 12-byte inner header where bytes[16..19] (word2) is serviceId
            word2IsService -> {
                val avail = payload.size - 12
                val innerU16Len = buf.getShort(4).toInt() and 0xFFFF
                val safeLen = when {
                    innerU16Len in 0..avail -> innerU16Len
                    word1 in 0..avail -> word1
                    else -> avail
                }
                val body = if (safeLen > 0) payload.copyOfRange(12, 12 + safeLen) else ByteArray(0)
                CarLifeCommandMessage(
                    serviceType = word2,
                    body = body
                )
            }

            // Subcase 2D: Full raw USB buffer captured in payload where bytes[12..15] (word3) is serviceId
            word3IsService -> {
                val avail = payload.size - 16
                val innerU16Len = buf.getShort(8).toInt() and 0xFFFF
                val safeLen = if (innerU16Len in 0..avail) innerU16Len else avail
                val body = if (safeLen > 0) payload.copyOfRange(16, 16 + safeLen) else ByteArray(0)
                CarLifeCommandMessage(
                    serviceType = word3,
                    body = body
                )
            }

            // Fallback: never drop any Control/Touch frame as malformed
            else -> {
                val chosenService = when {
                    word1 != 0 -> word1
                    word2 != 0 -> word2
                    word3 != 0 -> word3
                    else -> word0
                }
                val avail = payload.size - 8
                val safeLen = if (u16MsgLen in 0..avail) u16MsgLen else avail
                val body = if (safeLen > 0) payload.copyOfRange(8, 8 + safeLen) else ByteArray(0)
                CarLifeCommandMessage(
                    serviceType = chosenService,
                    body = body
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CarLifeFrame) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Structured representation of an inner Control (`0x0001`) or Touch (`0x0006`) command packet.
 */
data class CarLifeCommandMessage(
    val serviceType: Int,
    val body: ByteArray = ByteArray(0)
) {
    val serviceName: String
        get() = CarLifeMsgType.nameOf(serviceType)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CarLifeCommandMessage) return false
        return serviceType == other.serviceType && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = serviceType
        result = 31 * result + body.contentHashCode()
        return result
    }
}

class CarLifeProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Binary Frame Codec for the Baidu CarLife USB AOA protocol.
 *
 * Supports both `InputStream`/`OutputStream` and zero-allocation native `DirectByteBuffer` +
 * `FileChannel` I/O for minimal GC pressure on the Galaxy S26 Ultra video/touch hot paths.
 */
object CarLifeFrameCodec {

    const val STANDARD_HEADER_SIZE = 8
    const val MEDIA_HEADER_SIZE = 12
    const val MAX_AOA_WRITE_SIZE = 16_384
    const val MAX_FRAME_PAYLOAD_SIZE = 8 * 1024 * 1024

    fun encodeHeader(header: CarLifeHeader): ByteArray {
        require(header.payloadLength in 0..MAX_FRAME_PAYLOAD_SIZE) {
            "Invalid payloadLength=${header.payloadLength} (must be in 0..$MAX_FRAME_PAYLOAD_SIZE)"
        }
        val size = header.headerSizeBytes
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(header.rawChannelId)
        buffer.putInt(header.payloadLength)
        if (header.includeOuterTimestamp) {
            buffer.putInt((header.timestamp and 0x7FFF_FFFFL).toInt())
        }
        return buffer.array()
    }

    fun writeHeaderToBuffer(header: CarLifeHeader, target: ByteBuffer) {
        target.order(ByteOrder.BIG_ENDIAN)
        target.putInt(header.rawChannelId)
        target.putInt(header.payloadLength)
        if (header.includeOuterTimestamp) {
            target.putInt((header.timestamp and 0x7FFF_FFFFL).toInt())
        }
    }

    fun encodeFrame(frame: CarLifeFrame): ByteArray {
        val normalizedHeader = if (frame.header.payloadLength != frame.payload.size) {
            frame.header.copy(payloadLength = frame.payload.size)
        } else {
            frame.header
        }
        val headerSize = normalizedHeader.headerSizeBytes
        val buffer = ByteBuffer.allocate(headerSize + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
        writeHeaderToBuffer(normalizedHeader, buffer)
        buffer.put(frame.payload)
        return buffer.array()
    }

    @Throws(CarLifeProtocolException::class)
    fun decodeBaseHeader(first8Bytes: ByteArray, offset: Int = 0): CarLifeHeader {
        if (first8Bytes.size - offset < STANDARD_HEADER_SIZE) {
            throw CarLifeProtocolException(
                "Insufficient bytes for base header: required=$STANDARD_HEADER_SIZE, available=${first8Bytes.size - offset}"
            )
        }
        val buffer = ByteBuffer.wrap(first8Bytes, offset, STANDARD_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        return decodeBaseHeaderFromBuffer(buffer)
    }

    @Throws(CarLifeProtocolException::class)
    fun decodeBaseHeaderFromBuffer(buffer: ByteBuffer): CarLifeHeader {
        buffer.order(ByteOrder.BIG_ENDIAN)
        val rawChannelId = buffer.int
        val payloadLength = buffer.int

        val channel = CarLifeChannel.fromId(rawChannelId)
            ?: throw CarLifeProtocolException(
                "Unknown CarLife channel ID: 0x${rawChannelId.toUInt().toString(16).uppercase().padStart(8, '0')}"
            )

        if (payloadLength < 0 || payloadLength > MAX_FRAME_PAYLOAD_SIZE) {
            throw CarLifeProtocolException(
                "Invalid CarLife payload length=$payloadLength on channel=${channel.name}"
            )
        }

        return CarLifeHeader(
            channel = channel,
            rawChannelId = rawChannelId,
            payloadLength = payloadLength,
            timestamp = 0L
        )
    }

    fun parseMediaTimestamp(timestamp4Bytes: ByteArray, offset: Int = 0): Long {
        require(timestamp4Bytes.size - offset >= 4) {
            "Timestamp requires 4 bytes, available=${timestamp4Bytes.size - offset}"
        }
        val rawInt = ByteBuffer.wrap(timestamp4Bytes, offset, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        return rawInt.toLong() and 0xFFFF_FFFFL
    }

    @Throws(CarLifeProtocolException::class)
    fun decodeHeader(bytes: ByteArray, offset: Int = 0): CarLifeHeader {
        val base = decodeBaseHeader(bytes, offset)
        if (!base.channel.hasTimestampHeader) {
            return base
        }
        if (bytes.size - offset < MEDIA_HEADER_SIZE) {
            throw CarLifeProtocolException(
                "Channel ${base.channel.name} requires a 12-byte header (with timestamp), but only ${bytes.size - offset} bytes provided"
            )
        }
        val timestamp = parseMediaTimestamp(bytes, offset + STANDARD_HEADER_SIZE)
        return base.copy(timestamp = timestamp)
    }

    @Throws(CarLifeProtocolException::class)
    fun decodeFrame(rawBytes: ByteArray, offset: Int = 0): CarLifeFrame {
        val base = decodeBaseHeader(rawBytes, offset)
        val availableAfter8 = rawBytes.size - offset - STANDARD_HEADER_SIZE
        // Detect AOA 20B Video frame: 8B outer header + (12 + h264Size) payload where bytes[16..19] == MSG_VIDEO_DATA
        if (base.channel == CarLifeChannel.VIDEO &&
            base.payloadLength >= 12 &&
            availableAfter8 == base.payloadLength
        ) {
            val innerBuf = ByteBuffer.wrap(rawBytes, offset + STANDARD_HEADER_SIZE, 12).order(ByteOrder.BIG_ENDIAN)
            val nalSize = innerBuf.int
            val tsMs = innerBuf.int.toLong() and 0xFFFF_FFFFL
            val serviceId = innerBuf.int
            if (nalSize == base.payloadLength - 12 &&
                (serviceId == CarLifeMsgType.MSG_VIDEO_DATA ||
                    serviceId == CarLifeMsgType.MSG_VIDEO_DATA_ALT ||
                    serviceId == CarLifeMsgType.MSG_VIDEO_HEARTBEAT ||
                    serviceId == CarLifeMsgType.MSG_VIDEO_HEARTBEAT_ALT)
            ) {
                val payload = ByteArray(base.payloadLength)
                System.arraycopy(rawBytes, offset + STANDARD_HEADER_SIZE, payload, 0, base.payloadLength)
                return CarLifeFrame(
                    header = base.copy(timestamp = tsMs, includeOuterTimestamp = false),
                    payload = payload
                )
            }
        }

        val header = decodeHeader(rawBytes, offset)
        val headerSize = header.headerSizeBytes
        val availablePayload = rawBytes.size - offset - headerSize
        if (availablePayload < header.payloadLength) {
            throw CarLifeProtocolException(
                "Truncated CarLife frame on ${header.channel}: expected ${header.payloadLength} payload bytes, got $availablePayload"
            )
        }
        val payload = ByteArray(header.payloadLength)
        System.arraycopy(rawBytes, offset + headerSize, payload, 0, header.payloadLength)
        return CarLifeFrame(header = header, payload = payload)
    }

    /**
     * Zero-allocation native `DirectByteBuffer` reader over a [FileChannel] (from `FileInputStream.channel`).
     * Reuses a caller-supplied 12-byte direct header buffer to avoid per-packet allocations.
     */
    @Throws(IOException::class)
    fun readNextFrameDirect(
        channel: FileChannel,
        directHeaderBuffer: ByteBuffer
    ): CarLifeFrame {
        directHeaderBuffer.clear()
        directHeaderBuffer.limit(STANDARD_HEADER_SIZE)
        readChannelFully(channel, directHeaderBuffer)
        directHeaderBuffer.flip()

        val baseHeader = decodeBaseHeaderFromBuffer(directHeaderBuffer)

        val fullHeader = if (baseHeader.channel.hasTimestampHeader) {
            directHeaderBuffer.clear()
            directHeaderBuffer.limit(4)
            readChannelFully(channel, directHeaderBuffer)
            directHeaderBuffer.flip()
            val ts = directHeaderBuffer.int.toLong() and 0xFFFF_FFFFL
            baseHeader.copy(timestamp = ts)
        } else {
            baseHeader
        }

        val payload = if (fullHeader.payloadLength > 0) {
            val directPayload = DirectBufferPool.acquire(fullHeader.payloadLength)
            try {
                directPayload.limit(fullHeader.payloadLength)
                readChannelFully(channel, directPayload)
                directPayload.flip()
                ByteArray(fullHeader.payloadLength).also { directPayload.get(it) }
            } finally {
                DirectBufferPool.release(directPayload)
            }
        } else {
            ByteArray(0)
        }

        return CarLifeFrame(header = fullHeader, payload = payload)
    }

    /**
     * Zero-allocation native `DirectByteBuffer` writer over a [FileChannel] (from `FileOutputStream.channel`).
     * Strictly chunks every write syscall to [MAX_AOA_WRITE_SIZE] (16,384 bytes) to comply with
     * the Linux kernel `f_accessory` (`/dev/usb_accessory`) bulk buffer limit.
     */
    @Throws(IOException::class)
    fun writeFrameDirect(
        channel: FileChannel,
        frame: CarLifeFrame
    ) {
        val normalizedHeader = if (frame.header.payloadLength != frame.payload.size) {
            frame.header.copy(payloadLength = frame.payload.size)
        } else {
            frame.header
        }
        val totalBytes = normalizedHeader.headerSizeBytes + frame.payload.size
        val directBuf = DirectBufferPool.acquire(totalBytes)
        try {
            writeHeaderToBuffer(normalizedHeader, directBuf)
            directBuf.put(frame.payload)
            directBuf.flip()
            while (directBuf.hasRemaining()) {
                val remaining = directBuf.remaining()
                val savedLimit = directBuf.limit()
                if (remaining > MAX_AOA_WRITE_SIZE) {
                    directBuf.limit(directBuf.position() + MAX_AOA_WRITE_SIZE)
                }
                while (directBuf.hasRemaining()) {
                    val written = channel.write(directBuf)
                    if (written < 0) {
                        throw EOFException("USB AOA FileChannel closed during write")
                    }
                }
                directBuf.limit(savedLimit)
            }
        } finally {
            DirectBufferPool.release(directBuf)
        }
    }

    @Throws(IOException::class)
    fun readNextFrame(
        input: InputStream,
        baseHeaderScratch: ByteArray = ByteArray(STANDARD_HEADER_SIZE),
        timestampScratch: ByteArray = ByteArray(4)
    ): CarLifeFrame {
        readFully(input, baseHeaderScratch, 0, STANDARD_HEADER_SIZE)
        val baseHeader = decodeBaseHeader(baseHeaderScratch, 0)

        if (!baseHeader.channel.hasTimestampHeader) {
            val payload = if (baseHeader.payloadLength > 0) {
                ByteArray(baseHeader.payloadLength).also { buf ->
                    readFully(input, buf, 0, baseHeader.payloadLength)
                }
            } else {
                ByteArray(0)
            }
            return CarLifeFrame(header = baseHeader, payload = payload)
        }

        // For VIDEO / AUDIO channels: read first 4 bytes after the 8-byte base header.
        // In AOA 20B Video mode: bytes[8..11] = nalData.size (equals baseHeader.payloadLength - 12),
        // bytes[12..15] = timestampMs, bytes[16..19] = MSG_VIDEO_DATA (0x00020001).
        // In Legacy 12B Video / Audio mode: bytes[8..11] = timestampMs, followed by payloadLength bytes.
        readFully(input, timestampScratch, 0, 4)
        val wordAt8 = ByteBuffer.wrap(timestampScratch, 0, 4).order(ByteOrder.BIG_ENDIAN).int

        if (baseHeader.channel == CarLifeChannel.VIDEO &&
            baseHeader.payloadLength >= 12 &&
            wordAt8 == baseHeader.payloadLength - 12
        ) {
            val fullPayload = ByteArray(baseHeader.payloadLength)
            System.arraycopy(timestampScratch, 0, fullPayload, 0, 4)
            readFully(input, fullPayload, 4, baseHeader.payloadLength - 4)
            val ts = parseMediaTimestamp(fullPayload, 4)
            val serviceId = ByteBuffer.wrap(fullPayload, 8, 4).order(ByteOrder.BIG_ENDIAN).int
            if (serviceId == CarLifeMsgType.MSG_VIDEO_DATA ||
                serviceId == CarLifeMsgType.MSG_VIDEO_DATA_ALT ||
                serviceId == CarLifeMsgType.MSG_VIDEO_HEARTBEAT ||
                serviceId == CarLifeMsgType.MSG_VIDEO_HEARTBEAT_ALT
            ) {
                return CarLifeFrame(
                    header = baseHeader.copy(timestamp = ts, includeOuterTimestamp = false),
                    payload = fullPayload
                )
            }
        }

        val ts = parseMediaTimestamp(timestampScratch, 0)
        val fullHeader = baseHeader.copy(timestamp = ts, includeOuterTimestamp = true)
        val payload = if (fullHeader.payloadLength > 0) {
            ByteArray(fullHeader.payloadLength).also { buf ->
                readFully(input, buf, 0, fullHeader.payloadLength)
            }
        } else {
            ByteArray(0)
        }

        return CarLifeFrame(header = fullHeader, payload = payload)
    }

    /**
     * Writes a complete [CarLifeFrame] to [output] (`FileOutputStream` for `/dev/usb_accessory`)
     * in strict chunks of [MAX_AOA_WRITE_SIZE] (16,384 bytes / 16 KB) so the kernel `f_accessory`
     * driver never drops or truncates keyframes larger than 16 KB.
     */
    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, frame: CarLifeFrame) {
        val headerWithActualLength = if (frame.header.payloadLength != frame.payload.size) {
            frame.header.copy(payloadLength = frame.payload.size)
        } else {
            frame.header
        }
        val fullPacket = encodeFrame(frame.copy(header = headerWithActualLength))
        var offset = 0
        while (offset < fullPacket.size) {
            val chunk = minOf(MAX_AOA_WRITE_SIZE, fullPacket.size - offset)
            output.write(fullPacket, offset, chunk)
            offset += chunk
        }
        output.flush()
    }

    /**
     * Task 1 canonical builder (`encodeAoACmdPacket` / `buildAoACmdPacket`):
     * Builds a complete USB AOA Control (`0x0001`) binary packet matching `com.baidu.carlife`:
     * - `Bytes [0..3]`: `0x00000001` (Channel ID = 1)
     * - `Bytes [4..7]`: `8 + protoBytes.size` (AOA Payload Length)
     * - `Bytes [8..9]`: `protoBytes.size.toShort()` (Protobuf length, STRICT 2 BYTES UInt16 Big-Endian!)
     * - `Bytes [10..11]`: `0x0000.toShort()` (Reserved, STRICT 2 BYTES `00 00`!)
     * - `Bytes [12..15]`: `serviceId` (Service ID, e.g. `0x00018002`)
     * - `Bytes [16..N]`: `protoBytes`
     */
    fun encodeAoACmdPacket(serviceId: Int, protoBytes: ByteArray = ByteArray(0)): ByteArray {
        val aoaPayloadLen = 8 + protoBytes.size
        return ByteBuffer.allocate(8 + aoaPayloadLen).order(ByteOrder.BIG_ENDIAN).apply {
            // 1. Внешний заголовок транспорта USB AOA (8 байт):
            putInt(0x00000001)                  // Bytes [0..3]: Channel ID = 1 (CMD)
            putInt(aoaPayloadLen)               // Bytes [4..7]: Длина вложенного пакета (8 + protoBytes.size)

            // 2. Внутренний заголовок команды CarLife (8 байт):
            putShort(protoBytes.size.toShort()) // Bytes [8..9]: Длина Protobuf (СТРОГО 2 БАЙТА UInt16 Big-Endian!)
            putShort(0x0000.toShort())          // Bytes [10..11]: Reserved (СТРОГО 00 00)
            putInt(serviceId)                   // Bytes [12..15]: Service ID (4 байта, например 0x00018002)

            // 3. Тело Protobuf:
            if (protoBytes.isNotEmpty()) put(protoBytes)
        }.array()
    }

    fun buildAoACmdPacket(serviceId: Int, protoBytes: ByteArray = ByteArray(0)): ByteArray =
        encodeAoACmdPacket(serviceId, protoBytes)

    /**
     * Task 2 reference builder: Builds a complete USB AOA Video (`0x0002`) binary packet with
     * the 8-byte AOA transport header + 12-byte `CarlifeVideoMessage` header (`MSG_VIDEO_DATA = 0x00020001`):
     * - `Bytes [0..3]`: `0x00000002` (Channel ID = 2)
     * - `Bytes [4..7]`: `12 + h264Data.size` (AOA Payload Length)
     * - `Bytes [8..11]`: `h264Data.size` (Size of H.264 NAL data)
     * - `Bytes [12..15]`: `timestampMs` (Timestamp in ms)
     * - `Bytes [16..19]`: `0x00020001` (`MSG_VIDEO_DATA`)
     * - `Bytes [20..N]`: `h264Data` (Raw H.264 Annex-B `00 00 00 01 ...`)
     */
    fun buildAoAVideoPacket(h264Data: ByteArray, timestampMs: Int): ByteArray {
        val annexB = ensureAnnexBStartCode(h264Data)
        val innerLen = 12 + annexB.size
        return ByteBuffer.allocate(8 + innerLen).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x00000002)                    // Bytes [0..3]: Channel ID = 2 (VIDEO)
            putInt(innerLen)                      // Bytes [4..7]: AOA Payload Length (12 + annexB.size)
            putInt(annexB.size)                   // Bytes [8..11]: Size of H.264 NAL data
            putInt(timestampMs)                   // Bytes [12..15]: Timestamp in ms
            putInt(CarLifeMsgType.MSG_VIDEO_DATA) // Bytes [16..19]: Service ID = MSG_VIDEO_DATA (0x00020001)
            put(annexB)                           // Bytes [20..N]: Raw H.264 Annex-B (00 00 00 01 ...)
        }.array()
    }

    /**
     * Builds a 20-byte USB AOA Video Heartbeat frame (`MSG_VIDEO_HEARTBEAT = 0x00020002`):
     * - Outer 8B AOA Header: `[00 00 00 02][00 00 00 0C]`
     * - Inner 12B Video Header: `[00 00 00 00 (len=0)][timestampMs (4B)][00 02 00 02]`
     */
    fun createVideoHeartbeatFrame(
        timestampMs: Long = (android.os.SystemClock.uptimeMillis().takeIf { it > 0L } ?: System.currentTimeMillis()) and 0x7FFF_FFFFL
    ): CarLifeFrame {
        val safeTs = timestampMs and 0x7FFF_FFFFL
        val innerHeartbeat = ByteBuffer.allocate(12)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt(safeTs.toInt())
            .putInt(CarLifeMsgType.MSG_VIDEO_HEARTBEAT)
            .array()
        return CarLifeFrame(
            header = CarLifeHeader(
                channel = CarLifeChannel.VIDEO,
                rawChannelId = CarLifeChannel.VIDEO.id,
                payloadLength = innerHeartbeat.size,
                timestamp = safeTs,
                includeOuterTimestamp = false
            ),
            payload = innerHeartbeat
        )
    }

    fun buildAoAVideoHeartbeatPacket(timestampMs: Int): ByteArray =
        encodeFrame(createVideoHeartbeatFrame(timestampMs.toLong() and 0x7FFF_FFFFL))

    /**
     * Packs a Control (`0x0001`) or Touch (`0x0006`) frame using the exact Baidu CarLife wire layout:
     * - Outer Channel Header (8 bytes):
     *   - `[0..3]` = `channel.id` (Big-Endian Int32, e.g. `0x00000001`)
     *   - `[4..7]` = `8 + commandBody.size` (Big-Endian Int32 total channel payload length)
     * - Inner Command Header (8 bytes):
     *   - `[8..9]`   = `(commandBody.size).toShort()` (STRICT 2-BYTE Big-Endian UInt16 Protobuf length, e.g. `00 02`, `00 F2`)
     *   - `[10..11]` = `0x0000.toShort()` (STRICT 2-BYTE Reserved `00 00`)
     *   - `[12..15]` = `serviceType` (4-BYTE Big-Endian Int32 Service ID, e.g. `0x00018002`)
     * - Protobuf Payload (`commandBody.size` bytes):
     *   - `[16..N]`  = `commandBody`
     */
    fun createCommandFrame(
        channel: CarLifeChannel = CarLifeChannel.CONTROL,
        serviceType: Int,
        commandBody: ByteArray = ByteArray(0)
    ): CarLifeFrame {
        require(!channel.hasTimestampHeader) {
            "createCommandFrame is intended for 8-byte non-media channels (CONTROL or TOUCH), got $channel"
        }
        val innerPayload = ByteBuffer.allocate(8 + commandBody.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(commandBody.size.toShort())
            .putShort(0x0000.toShort())
            .putInt(serviceType)
            .put(commandBody)
            .array()

        return CarLifeFrame(
            header = CarLifeHeader(
                channel = channel,
                rawChannelId = channel.id,
                payloadLength = innerPayload.size,
                timestamp = 0L,
                includeOuterTimestamp = false
            ),
            payload = innerPayload
        )
    }

    /**
     * Runtime toggle for Baidu CarLife Video Channel (`0x0002`) payload framing (applied identically to SPS/PPS and IDR):
     * - `false` (**Direct 12B — default**):
     *   12B Header (`[00 00 00 02 (4B)][nalSize (4B)][timestamp (4B)]`) + `[00 00 00 01... h264Bytes]`.
     * - `true` (**AOA 20B**):
     *   Outer AOA 8B (`[00 00 00 02][12 + h264.size]`) + Inner 12B (`[h264.size (4B)][timestampMs (4B)][0x00020001 (4B)]`) + `[00 00 00 01 ... h264Bytes]`.
     */
    @Volatile
    var useNestedVideoMsgHeader: Boolean = false

    /**
     * Ensures an H.264 buffer starts with the 4-byte Annex-B start code `00 00 00 01`.
     * Also converts a single AVCC length-prefixed NAL (`[4B length][NAL...]`) to Annex-B if encountered.
     */
    fun ensureAnnexBStartCode(nalData: ByteArray): ByteArray {
        if (nalData.size >= 4 &&
            nalData[0] == 0x00.toByte() &&
            nalData[1] == 0x00.toByte() &&
            nalData[2] == 0x00.toByte() &&
            nalData[3] == 0x01.toByte()
        ) {
            return nalData
        }
        if (nalData.size >= 3 &&
            nalData[0] == 0x00.toByte() &&
            nalData[1] == 0x00.toByte() &&
            nalData[2] == 0x01.toByte()
        ) {
            val fixed = ByteArray(nalData.size + 1)
            fixed[0] = 0x00
            fixed[1] = 0x00
            fixed[2] = 0x00
            fixed[3] = 0x01
            System.arraycopy(nalData, 3, fixed, 4, nalData.size - 3)
            return fixed
        }
        // Check if encoder emitted a 4-byte AVCC length prefix: [nalSize (4B)][NAL byte...]
        if (nalData.size > 4) {
            val avccLen = ((nalData[0].toInt() and 0xFF) shl 24) or
                ((nalData[1].toInt() and 0xFF) shl 16) or
                ((nalData[2].toInt() and 0xFF) shl 8) or
                (nalData[3].toInt() and 0xFF)
            val nalHdr = nalData[4].toInt() and 0xFF
            val nalType = nalHdr and 0x1F
            if (avccLen == nalData.size - 4 && (nalHdr and 0x80) == 0 && nalType in 1..12) {
                val fixed = nalData.copyOf()
                fixed[0] = 0x00
                fixed[1] = 0x00
                fixed[2] = 0x00
                fixed[3] = 0x01
                return fixed
            }
        }
        val prefixed = ByteArray(4 + nalData.size)
        prefixed[0] = 0x00
        prefixed[1] = 0x00
        prefixed[2] = 0x00
        prefixed[3] = 0x01
        System.arraycopy(nalData, 0, prefixed, 4, nalData.size)
        return prefixed
    }

    /**
     * Parses an H.264 buffer (`h264Buffer`) by Annex-B start codes (`00 00 00 01` or `00 00 01`)
     * — or length-prefixed AVCC blocks if no Annex-B start codes are present — and splits it into
     * individual NAL units (`SPS`, `PPS`, `IDR`, `Slice`), each normalized to start strictly with
     * the 4-byte Annex-B start code `00 00 00 01`.
     */
    fun splitAnnexBNalUnits(h264Buffer: ByteArray): List<ByteArray> {
        if (h264Buffer.isEmpty()) return emptyList()

        val starts = ArrayList<Pair<Int, Int>>(8) // (startCodeOffset, payloadOffset)
        var i = 0
        while (i <= h264Buffer.size - 3) {
            if (h264Buffer[i] == 0x00.toByte() && h264Buffer[i + 1] == 0x00.toByte()) {
                if (h264Buffer[i + 2] == 0x01.toByte()) {
                    // 3-byte Annex-B start code: 00 00 01 (forcibly expanded to 00 00 00 01 below)
                    starts.add(i to (i + 3))
                    i += 3
                    continue
                } else if (h264Buffer[i + 2] == 0x00.toByte()) {
                    // 4-byte (00 00 00 01) or zero-padded Annex-B start code (00 00 00+ 01)
                    var j = i + 3
                    while (j < h264Buffer.size && h264Buffer[j] == 0x00.toByte()) {
                        j++
                    }
                    if (j < h264Buffer.size && h264Buffer[j] == 0x01.toByte()) {
                        starts.add(i to (j + 1))
                        i = j + 1
                        continue
                    } else {
                        i = j
                        continue
                    }
                }
            }
            i++
        }

        if (starts.isNotEmpty()) {
            val result = ArrayList<ByteArray>(starts.size)
            for (idx in starts.indices) {
                val payloadStart = starts[idx].second
                val payloadEnd = if (idx + 1 < starts.size) starts[idx + 1].first else h264Buffer.size
                val payloadLen = payloadEnd - payloadStart
                if (payloadLen <= 0) continue
                val nal = ByteArray(4 + payloadLen)
                nal[0] = 0x00
                nal[1] = 0x00
                nal[2] = 0x00
                nal[3] = 0x01
                System.arraycopy(h264Buffer, payloadStart, nal, 4, payloadLen)
                result.add(nal)
            }
            if (result.isNotEmpty()) return result
        }

        // Check for one or more concatenated AVCC length-prefixed NALs: [4B len][NAL...]
        val avccResult = ArrayList<ByteArray>(4)
        var offset = 0
        var validAvcc = h264Buffer.size > 4
        while (offset < h264Buffer.size && validAvcc) {
            if (offset + 4 >= h264Buffer.size) {
                validAvcc = false
                break
            }
            val avccLen = ((h264Buffer[offset].toInt() and 0xFF) shl 24) or
                ((h264Buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((h264Buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (h264Buffer[offset + 3].toInt() and 0xFF)
            val nalHdr = h264Buffer[offset + 4].toInt() and 0xFF
            val nalType = nalHdr and 0x1F
            if (avccLen <= 0 || offset + 4 + avccLen > h264Buffer.size || (nalHdr and 0x80) != 0 || nalType !in 1..12) {
                validAvcc = false
                break
            }
            val nal = ByteArray(4 + avccLen)
            nal[0] = 0x00
            nal[1] = 0x00
            nal[2] = 0x00
            nal[3] = 0x01
            System.arraycopy(h264Buffer, offset + 4, nal, 4, avccLen)
            avccResult.add(nal)
            offset += 4 + avccLen
        }
        if (validAvcc && offset == h264Buffer.size && avccResult.isNotEmpty()) {
            return avccResult
        }

        return listOf(ensureAnnexBStartCode(h264Buffer))
    }

    /**
     * Canonical Jetta VS7 USB AOA `0x0002` Video frame encoder (`AOA 20B` + `0x00020001` + `Annex-B`):
     * - Outer 8B AOA Header: `[00 00 00 02][len + 12]`
     * - Inner 12B Video Header: `[len][timestampMs][00 02 00 01]`
     * - Payload: `[00 00 00 01 ... (H.264 Annex-B)]`
     * Used strictly for each individual `SPS`, `PPS`, `IDR`, and `P-slice` NAL unit.
     */
    fun encodeAoAVideoFrame(
        h264Data: ByteArray,
        timestampMs: Long = (android.os.SystemClock.uptimeMillis().takeIf { it > 0L } ?: System.currentTimeMillis()) and 0x7FFF_FFFFL
    ): CarLifeFrame {
        val annexBData = ensureAnnexBStartCode(h264Data)
        val safeTs = timestampMs and 0x7FFF_FFFFL
        val innerVideoMsg = ByteBuffer.allocate(12 + annexBData.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(annexBData.size)
            .putInt(safeTs.toInt())
            .putInt(CarLifeMsgType.MSG_VIDEO_DATA)
            .put(annexBData)
            .array()
        return CarLifeFrame(
            header = CarLifeHeader(
                channel = CarLifeChannel.VIDEO,
                rawChannelId = CarLifeChannel.VIDEO.id,
                payloadLength = innerVideoMsg.size,
                timestamp = safeTs,
                includeOuterTimestamp = false
            ),
            payload = innerVideoMsg
        )
    }

    /**
     * Splits an arbitrary `MediaCodec` H.264 output buffer (`h264Buffer`) by Annex-B `00 00 00 01`
     * start codes and wraps **every individual NAL unit** (`SPS`, `PPS`, `IDR`, `Slice`) into its
     * own separate 20-byte `encodeAoAVideoFrame` packet so hardware decoders like Jetta VS7 never
     * receive concatenated `SPS+PPS+IDR` frames in a single CarLife packet.
     */
    fun encodeAoAVideoFrames(
        h264Buffer: ByteArray,
        timestampMs: Long = (android.os.SystemClock.uptimeMillis().takeIf { it > 0L } ?: System.currentTimeMillis()) and 0x7FFF_FFFFL
    ): List<CarLifeFrame> {
        val nals = splitAnnexBNalUnits(h264Buffer)
        if (nals.isEmpty()) return emptyList()
        return nals.map { singleNal ->
            encodeAoAVideoFrame(h264Data = singleNal, timestampMs = timestampMs)
        }
    }

    /**
     * Splits `h264Buffer` by Annex-B `00 00 00 01` start codes and returns raw wire packets
     * (`[Header 20B][00 00 00 01 NAL]`) ready for USB AOA transmission.
     */
    fun buildAoAVideoPackets(h264Buffer: ByteArray, timestampMs: Int): List<ByteArray> =
        encodeAoAVideoFrames(h264Buffer, timestampMs.toLong() and 0x7FFF_FFFFL).map { encodeFrame(it) }

    fun createVideoFrame(
        nalData: ByteArray,
        timestampMs: Long = (android.os.SystemClock.uptimeMillis().takeIf { it > 0L } ?: System.currentTimeMillis()) and 0x7FFF_FFFFL,
        nestedVideoMsgHeader: Boolean = useNestedVideoMsgHeader
    ): CarLifeFrame {
        return if (nestedVideoMsgHeader) {
            encodeAoAVideoFrame(h264Data = nalData, timestampMs = timestampMs)
        } else {
            val annexBData = ensureAnnexBStartCode(nalData)
            val safeTs = timestampMs and 0x7FFF_FFFFL
            // Mode 2 (Direct 12B): 12B Header ([0x00000002][annexBData.size][ts]) + [annexBData]
            CarLifeFrame(
                header = CarLifeHeader(
                    channel = CarLifeChannel.VIDEO,
                    rawChannelId = CarLifeChannel.VIDEO.id,
                    payloadLength = annexBData.size,
                    timestamp = safeTs,
                    includeOuterTimestamp = true
                ),
                payload = annexBData
            )
        }
    }

    fun createVideoFrames(
        h264Buffer: ByteArray,
        timestampMs: Long = (android.os.SystemClock.uptimeMillis().takeIf { it > 0L } ?: System.currentTimeMillis()) and 0x7FFF_FFFFL,
        nestedVideoMsgHeader: Boolean = useNestedVideoMsgHeader
    ): List<CarLifeFrame> {
        val nals = splitAnnexBNalUnits(h264Buffer)
        if (nals.isEmpty()) return emptyList()
        return nals.map { singleNal ->
            createVideoFrame(
                nalData = singleNal,
                timestampMs = timestampMs,
                nestedVideoMsgHeader = nestedVideoMsgHeader
            )
        }
    }

    fun createAudioFrame(
        audioChannel: CarLifeChannel = CarLifeChannel.MEDIA_AUDIO,
        pcmData: ByteArray,
        timestampMs: Long = System.currentTimeMillis() and 0xFFFF_FFFFL
    ): CarLifeFrame {
        require(audioChannel.hasTimestampHeader && audioChannel != CarLifeChannel.VIDEO) {
            "Audio channel must be MEDIA_AUDIO, TTS_AUDIO, or VR_AUDIO"
        }
        return CarLifeFrame(
            header = CarLifeHeader(
                channel = audioChannel,
                rawChannelId = audioChannel.id,
                payloadLength = pcmData.size,
                timestamp = timestampMs and 0xFFFF_FFFFL
            ),
            payload = pcmData
        )
    }

    /**
     * Builds empty 12-byte initialization packets (`12B header + empty PCM buffer`) for
     * `0x0003 (Media Audio)` and `0x0004 (TTS Audio)`:
     * - `[00 00 00 03][00 00 00 00 (size)][00 00 00 00 (timestamp)]`
     * - `[00 00 00 04][00 00 00 00 (size)][00 00 00 00 (timestamp)]`
     */
    fun createAudioMultiplexerInitFrames(
        timestampMs: Long = 0L
    ): List<CarLifeFrame> {
        val safeTs = timestampMs and 0x7FFF_FFFFL
        return listOf(
            createAudioFrame(
                audioChannel = CarLifeChannel.MEDIA_AUDIO,
                pcmData = ByteArray(0),
                timestampMs = safeTs
            ),
            createAudioFrame(
                audioChannel = CarLifeChannel.TTS_AUDIO,
                pcmData = ByteArray(0),
                timestampMs = safeTs
            )
        )
    }

    fun formatHexPreview(frame: CarLifeFrame, maxPayloadBytes: Int = 16): String {
        val headerBytes = encodeHeader(frame.header)
        // If Video frame is in Mode 1 (AOA 20B: 8B outer header + 12B inner video header [h264.size][ts][0x00020001]),
        // include all 20 header bytes in HDR[...] so PAYLOAD[...] always begins at the H.264 Annex-B start code (00 00 00 01)!
        if (frame.channel == CarLifeChannel.VIDEO &&
            !frame.header.includeOuterTimestamp &&
            frame.payload.size >= 12
        ) {
            val outerHex = headerBytes.joinToString(" ") { "%02X".format(it) }
            val inner12Hex = frame.payload.take(12).joinToString(" ") { "%02X".format(it) }
            val h264Size = frame.payload.size - 12
            val sliceLen = minOf(h264Size, maxPayloadBytes)
            val h264Hex = if (sliceLen > 0) {
                frame.payload.drop(12).take(sliceLen).joinToString(" ") { "%02X".format(it) } +
                    if (h264Size > sliceLen) " … (+${h264Size - sliceLen}B)" else ""
            } else {
                "<empty>"
            }
            return "HDR[$outerHex | $inner12Hex] | PAYLOAD[$h264Hex]"
        }

        val headerHex = headerBytes.joinToString(" ") { "%02X".format(it) }
        // Always output full HEX dump for Control (0x0001) and Touch (0x0006) frames (including 12B frames)
        val effectiveMaxPayload = if (frame.channel == CarLifeChannel.CONTROL || frame.channel == CarLifeChannel.TOUCH) {
            maxOf(maxPayloadBytes, 256)
        } else {
            maxPayloadBytes
        }
        val sliceLen = minOf(frame.payload.size, effectiveMaxPayload)
        val payloadHex = if (sliceLen > 0) {
            frame.payload.take(sliceLen).joinToString(" ") { "%02X".format(it) } +
                if (frame.payload.size > sliceLen) " … (+${frame.payload.size - sliceLen}B)" else ""
        } else {
            "<empty>"
        }
        return "HDR[$headerHex] | PAYLOAD[$payloadHex]"
    }

    @Throws(IOException::class)
    private fun readChannelFully(channel: FileChannel, dst: ByteBuffer) {
        while (dst.hasRemaining()) {
            val remaining = dst.remaining()
            val savedLimit = dst.limit()
            if (remaining > MAX_AOA_WRITE_SIZE) {
                dst.limit(dst.position() + MAX_AOA_WRITE_SIZE)
            }
            while (dst.hasRemaining()) {
                val read = channel.read(dst)
                if (read < 0) {
                    throw EOFException("USB AOA FileChannel closed while reading frame")
                }
            }
            dst.limit(savedLimit)
        }
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val chunkLen = minOf(MAX_AOA_WRITE_SIZE, length - totalRead)
            val read = input.read(buffer, offset + totalRead, chunkLen)
            if (read < 0) {
                throw EOFException(
                    "USB AOA stream closed while reading CarLife frame ($totalRead / $length bytes read)"
                )
            }
            totalRead += read
        }
    }
}

/**
 * Streaming ring/sliding buffer assembler for USB Open Accessory (`FileInputStream`).
 *
 * Handles:
 * 1. Two or more CarLife frames coalesced into a single `usbInputStream.read(buffer)` transfer.
 * 2. Large frames split across multiple `usbInputStream.read(buffer)` transfers.
 * 3. 12-byte Control (`0x0001`) frames (`8B header + 4B serviceId`, or `rawLength == 0` / `rawLength == 12`
 *    followed by a 4-byte `0x0001xxxx` parameterless command).
 */
class CarLifeStreamAssembler(initialCapacity: Int = 65_536) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(16_384))
    private var readPos = 0
    private var writePos = 0

    val availableBytes: Int
        get() = writePos - readPos

    fun reset() {
        readPos = 0
        writePos = 0
    }

    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (length <= 0) return
        ensureCapacity(length)
        System.arraycopy(data, offset, buf, writePos, length)
        writePos += length
    }

    fun drainFrames(): List<CarLifeFrame> {
        if (availableBytes < CarLifeFrameCodec.STANDARD_HEADER_SIZE) return emptyList()
        val out = ArrayList<CarLifeFrame>(4)

        while (availableBytes >= CarLifeFrameCodec.STANDARD_HEADER_SIZE) {
            // Re-align stream if header is not a valid CarLife channel (0x00000001 .. 0x00000006)
            val rawChannelId = peekIntBigEndian(readPos)
            val rawLength = peekIntBigEndian(readPos + 4)
            val isValidChannelHeader =
                (rawChannelId in 1..6) && (rawLength in 0..CarLifeFrameCodec.MAX_FRAME_PAYLOAD_SIZE)

            if (!isValidChannelHeader) {
                readPos++
                continue
            }

            val channel = CarLifeChannel.fromId(rawChannelId) ?: run {
                readPos++
                continue
            }

            val headerSize = channel.headerSizeBytes // 8 for 0x0001 & 0x0006; 12 for 0x0002..0x0005
            var effectivePayloadLen = rawLength

            // Special handling for 12-byte and 16-byte Control (0x0001) & Touch (0x0006) frames from MQB Head Unit,
            // including firmwares where bytes[4..7] == 00 00 00 00 (or bytes[4..7] == protoLen) followed by
            // [protoLen:2B][00 00:2B][serviceId:4B][protoBytes]!
            if (!channel.hasTimestampHeader && availableBytes > 8) {
                val wordAt8 = if (availableBytes >= 12) peekIntBigEndian(readPos + 8) else 0
                val wordAt8IsServiceId = availableBytes >= 12 && CarLifeMsgType.isCarLifeServiceId(wordAt8)
                val u16ProtoLen = if (availableBytes >= 10) peekUInt16BigEndian(readPos + 8) else 0
                val u16Reserved = if (availableBytes >= 12) peekUInt16BigEndian(readPos + 10) else 0
                val wordAt12 = if (availableBytes >= 16) peekIntBigEndian(readPos + 12) else 0
                val wordAt12IsServiceId = availableBytes >= 16 && CarLifeMsgType.isCarLifeServiceId(wordAt12)

                if (rawLength == 0) {
                    when {
                        // Case A: [00 00 00 01][00 00 00 00][u16ProtoLen:2B][00 00:2B][serviceId:4B][proto...]
                        availableBytes >= 16 && (wordAt12IsServiceId || (u16Reserved == 0 && wordAt12 != 0)) -> {
                            val protoCount = if (u16Reserved == 0 && u16ProtoLen in 0..(availableBytes - 16)) {
                                u16ProtoLen
                            } else {
                                (availableBytes - 16).coerceAtLeast(0)
                            }
                            effectivePayloadLen = 8 + protoCount
                        }
                        // Case B: [00 00 00 01][00 00 00 00][serviceId:4B] (12-byte frame)
                        availableBytes >= 12 && wordAt8IsServiceId -> {
                            effectivePayloadLen = 4
                        }
                        // Case C: Any other non-header trailing bytes in the same USB read when bytes[4..7] == 0
                        wordAt8 !in 1..6 -> {
                            effectivePayloadLen = availableBytes - 8
                        }
                    }
                } else if (availableBytes >= 16 && wordAt12IsServiceId && u16Reserved == 0) {
                    // Check if HU wrote protoLen (instead of 8 + protoLen) or totalFrameLen (16 + protoLen) in bytes[4..7]
                    if (rawLength == u16ProtoLen && availableBytes >= 16 + u16ProtoLen) {
                        effectivePayloadLen = 8 + u16ProtoLen
                    } else if (rawLength == 16 + u16ProtoLen && availableBytes == 16 + u16ProtoLen) {
                        effectivePayloadLen = 8 + u16ProtoLen
                    }
                } else if (rawLength == 12 && wordAt8IsServiceId) {
                    // Check if HU wrote total packet length (12) instead of payload length (4)
                    val nextHeaderStartsAt12 = availableBytes >= 20 &&
                        (peekIntBigEndian(readPos + 12) in 1..6) &&
                        (peekIntBigEndian(readPos + 16) in 0..4096)
                    val isNoArgStartOrLifecycleCmd = (availableBytes == 12) && when (wordAt8) {
                        CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START,
                        CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_ALT,
                        CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE,
                        CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE_ALT,
                        CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET,
                        CarLifeMsgType.MSG_CMD_SCREEN_ON,
                        CarLifeMsgType.MSG_CMD_SCREEN_OFF,
                        CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT,
                        CarLifeMsgType.MSG_CMD_FOREGROUND,
                        CarLifeMsgType.MSG_CMD_BACKGROUND,
                        CarLifeMsgType.MSG_CMD_GO_TO_DESKTOP,
                        CarLifeMsgType.MSG_CMD_GO_TO_FOREGROUND,
                        CarLifeMsgType.MSG_CMD_REQUEST_GO_TO_FOREGROUND -> true
                        else -> false
                    }
                    if (nextHeaderStartsAt12 || isNoArgStartOrLifecycleCmd) {
                        effectivePayloadLen = 4
                    }
                }
            }

            val totalFrameBytes = headerSize + effectivePayloadLen
            if (availableBytes < totalFrameBytes) {
                // Wait for remaining bytes of this packet from next usbInputStream.read()
                break
            }

            val timestamp = if (channel.hasTimestampHeader) {
                peekIntBigEndian(readPos + 8).toLong() and 0xFFFF_FFFFL
            } else {
                0L
            }

            val payload = if (effectivePayloadLen > 0) {
                buf.copyOfRange(readPos + headerSize, readPos + totalFrameBytes)
            } else {
                ByteArray(0)
            }

            readPos += totalFrameBytes
            if (readPos == writePos) {
                readPos = 0
                writePos = 0
            }

            out.add(
                CarLifeFrame(
                    header = CarLifeHeader(
                        channel = channel,
                        rawChannelId = rawChannelId,
                        payloadLength = payload.size,
                        timestamp = timestamp
                    ),
                    payload = payload
                )
            )
        }

        if (readPos > 32_768 && availableBytes > 0) {
            val rem = availableBytes
            System.arraycopy(buf, readPos, buf, 0, rem)
            readPos = 0
            writePos = rem
        } else if (availableBytes == 0) {
            readPos = 0
            writePos = 0
        }

        return out
    }

    private fun peekUInt16BigEndian(offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or
            (buf[offset + 1].toInt() and 0xFF)
    }

    private fun peekIntBigEndian(offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun ensureCapacity(additional: Int) {
        if (writePos + additional <= buf.size) return
        val rem = availableBytes
        if (readPos > 0 && rem + additional <= buf.size) {
            System.arraycopy(buf, readPos, buf, 0, rem)
            readPos = 0
            writePos = rem
            return
        }
        var newCap = buf.size * 2
        while (newCap < rem + additional) {
            newCap *= 2
        }
        val newBuf = ByteArray(newCap)
        if (rem > 0) {
            System.arraycopy(buf, readPos, newBuf, 0, rem)
        }
        buf = newBuf
        readPos = 0
        writePos = rem
    }
}

