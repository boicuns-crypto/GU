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
    // Control Channel (0x0001) — Handshake, Device Info, Video Negotiation & Lifecycle
    const val MSG_CMD_HU_PROTOCOL_VERSION = 0x00018001
    const val MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
    const val MSG_CMD_HU_INFO = 0x00018003
    const val MSG_CMD_MD_INFO = 0x00010004
    const val MSG_CMD_HU_BT_OOB_INFO = 0x00018005
    const val MSG_CMD_MD_BT_OOB_INFO = 0x00010006
    const val MSG_CMD_VIDEO_ENCODER_INIT = 0x00018007
    const val MSG_CMD_VIDEO_ENCODER_INIT_DONE = 0x00010008
    const val MSG_CMD_VIDEO_ENCODER_START = 0x00018009
    const val MSG_CMD_VIDEO_ENCODER_PAUSE = 0x0001800A
    const val MSG_CMD_VIDEO_ENCODER_RESET = 0x0001800B
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C
    const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000D
    const val MSG_CMD_PAUSE_MEDIA = 0x0001800E
    const val MSG_CMD_CAR_VELOCITY = 0x0001800F
    const val MSG_CMD_CAR_GPS = 0x00018010
    const val MSG_CMD_CAR_GYROSCOPE = 0x00018011
    const val MSG_CMD_CAR_ACCELERATION = 0x00018012
    const val MSG_CMD_CAR_OIL = 0x00018013
    const val MSG_CMD_LAUNCH_MODE_NORMAL = 0x00018014
    const val MSG_CMD_LAUNCH_MODE_PHONE = 0x00018015
    const val MSG_CMD_LAUNCH_MODE_MAP = 0x00018016
    const val MSG_CMD_LAUNCH_MODE_MUSIC = 0x00018017
    const val MSG_CMD_SCREEN_ON = 0x00018018
    const val MSG_CMD_SCREEN_OFF = 0x00018019
    const val MSG_CMD_SCREEN_USER_PRESENT = 0x0001801A
    const val MSG_CMD_FOREGROUND = 0x0001801B
    const val MSG_CMD_BACKGROUND = 0x0001801C
    const val MSG_CMD_GO_TO_DESKTOP = 0x0001801E
    const val MSG_CMD_MIC_RECORD_WAKEUP_START = 0x0001801F
    const val MSG_CMD_MIC_RECORD_END = 0x00018020
    const val MSG_CMD_MIC_RECORD_RECOG_START = 0x00018021
    const val MSG_CMD_GO_TO_FOREGROUND = 0x00018022
    const val MSG_CMD_MODULE_STATUS = 0x00018023
    const val MSG_CMD_STATISTIC_INFO = 0x00018024
    const val MSG_CMD_MODULE_CONTROL = 0x00018025

    // Video Channel (0x0002) — H.264 NAL Stream & Heartbeat
    const val MSG_VIDEO_DATA = 0x00020001
    const val MSG_VIDEO_HEARTBEAT = 0x00020002

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

    fun nameOf(serviceType: Int): String = when (serviceType) {
        MSG_CMD_HU_PROTOCOL_VERSION -> "MSG_CMD_HU_PROTOCOL_VERSION"
        MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS -> "MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS"
        MSG_CMD_HU_INFO -> "MSG_CMD_HU_INFO"
        MSG_CMD_MD_INFO -> "MSG_CMD_MD_INFO"
        MSG_CMD_HU_BT_OOB_INFO -> "MSG_CMD_HU_BT_OOB_INFO"
        MSG_CMD_MD_BT_OOB_INFO -> "MSG_CMD_MD_BT_OOB_INFO"
        MSG_CMD_VIDEO_ENCODER_INIT -> "MSG_CMD_VIDEO_ENCODER_INIT"
        MSG_CMD_VIDEO_ENCODER_INIT_DONE -> "MSG_CMD_VIDEO_ENCODER_INIT_DONE"
        MSG_CMD_VIDEO_ENCODER_START -> "MSG_CMD_VIDEO_ENCODER_START"
        MSG_CMD_VIDEO_ENCODER_PAUSE -> "MSG_CMD_VIDEO_ENCODER_PAUSE"
        MSG_CMD_VIDEO_ENCODER_RESET -> "MSG_CMD_VIDEO_ENCODER_RESET"
        MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> "MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE"
        MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE -> "MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE"
        MSG_CMD_SCREEN_ON -> "MSG_CMD_SCREEN_ON"
        MSG_CMD_SCREEN_OFF -> "MSG_CMD_SCREEN_OFF"
        MSG_CMD_FOREGROUND -> "MSG_CMD_FOREGROUND"
        MSG_CMD_BACKGROUND -> "MSG_CMD_BACKGROUND"
        MSG_CMD_GO_TO_DESKTOP -> "MSG_CMD_GO_TO_DESKTOP"
        MSG_CMD_GO_TO_FOREGROUND -> "MSG_CMD_GO_TO_FOREGROUND"
        MSG_VIDEO_DATA -> "MSG_VIDEO_DATA"
        MSG_VIDEO_HEARTBEAT -> "MSG_VIDEO_HEARTBEAT"
        MSG_TOUCH_ACTION -> "MSG_TOUCH_ACTION"
        MSG_TOUCH_ACTION_DOWN -> "MSG_TOUCH_ACTION_DOWN"
        MSG_TOUCH_ACTION_UP -> "MSG_TOUCH_ACTION_UP"
        MSG_TOUCH_ACTION_MOVE -> "MSG_TOUCH_ACTION_MOVE"
        MSG_TOUCH_SINGLE_CLICK -> "MSG_TOUCH_SINGLE_CLICK"
        MSG_TOUCH_DOUBLE_CLICK -> "MSG_TOUCH_DOUBLE_CLICK"
        MSG_TOUCH_LONG_PRESS -> "MSG_TOUCH_LONG_PRESS"
        MSG_TOUCH_CAR_HARD_KEY_CODE -> "MSG_TOUCH_CAR_HARD_KEY_CODE"
        else -> "0x${serviceType.toUInt().toString(16).uppercase().padStart(8, '0')}"
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
    val timestamp: Long = 0L
) {
    val headerSizeBytes: Int
        get() = channel.headerSizeBytes
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
     * Extracts the inner Control/Touch command sub-header if present:
     * `[InnerMsgDataLength: 4B Big-Endian][ServiceType: 4B Big-Endian][ProtobufBody: N bytes]`
     */
    fun parseCommandMessageOrNull(): CarLifeCommandMessage? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val innerDataLen = buf.int
        val serviceType = buf.int
        if (innerDataLen < 0 || innerDataLen > payload.size - 8) {
            return null
        }
        val body = if (innerDataLen > 0) {
            ByteArray(innerDataLen).also { buf.get(it) }
        } else {
            ByteArray(0)
        }
        return CarLifeCommandMessage(
            serviceType = serviceType,
            body = body
        )
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
    const val MAX_FRAME_PAYLOAD_SIZE = 8 * 1024 * 1024

    fun encodeHeader(header: CarLifeHeader): ByteArray {
        require(header.payloadLength in 0..MAX_FRAME_PAYLOAD_SIZE) {
            "Invalid payloadLength=${header.payloadLength} (must be in 0..$MAX_FRAME_PAYLOAD_SIZE)"
        }
        val size = header.headerSizeBytes
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(header.rawChannelId)
        buffer.putInt(header.payloadLength)
        if (header.channel.hasTimestampHeader) {
            buffer.putInt((header.timestamp and 0xFFFF_FFFFL).toInt())
        }
        return buffer.array()
    }

    fun writeHeaderToBuffer(header: CarLifeHeader, target: ByteBuffer) {
        target.order(ByteOrder.BIG_ENDIAN)
        target.putInt(header.rawChannelId)
        target.putInt(header.payloadLength)
        if (header.channel.hasTimestampHeader) {
            target.putInt((header.timestamp and 0xFFFF_FFFFL).toInt())
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
                val written = channel.write(directBuf)
                if (written < 0) {
                    throw EOFException("USB AOA FileChannel closed during write")
                }
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

        val fullHeader = if (baseHeader.channel.hasTimestampHeader) {
            readFully(input, timestampScratch, 0, 4)
            val ts = parseMediaTimestamp(timestampScratch, 0)
            baseHeader.copy(timestamp = ts)
        } else {
            baseHeader
        }

        val payload = if (fullHeader.payloadLength > 0) {
            ByteArray(fullHeader.payloadLength).also { buf ->
                readFully(input, buf, 0, fullHeader.payloadLength)
            }
        } else {
            ByteArray(0)
        }

        return CarLifeFrame(header = fullHeader, payload = payload)
    }

    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, frame: CarLifeFrame) {
        val headerWithActualLength = if (frame.header.payloadLength != frame.payload.size) {
            frame.header.copy(payloadLength = frame.payload.size)
        } else {
            frame.header
        }

        if (frame.payload.size <= 16_384) {
            val packetBytes = encodeFrame(frame.copy(header = headerWithActualLength))
            output.write(packetBytes)
        } else {
            val headerBytes = encodeHeader(headerWithActualLength)
            output.write(headerBytes)
            output.write(frame.payload)
        }
        output.flush()
    }

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
            .putInt(commandBody.size)
            .putInt(serviceType)
            .put(commandBody)
            .array()

        return CarLifeFrame(
            header = CarLifeHeader(
                channel = channel,
                rawChannelId = channel.id,
                payloadLength = innerPayload.size,
                timestamp = 0L
            ),
            payload = innerPayload
        )
    }

    /**
     * Runtime toggle for Baidu CarLife Video Channel (`0x0002`) payload framing:
     * - `false` (default): Raw H.264 Annex-B NAL payload immediately follows the 12-byte media header.
     * - `true`: Prepends an 8-byte inner message header `[NAL_Length(4B)][MSG_VIDEO_DATA = 0x00020001(4B)]`
     *   inside the `0x0002` payload (required by certain MIB/MQB CarLife head unit firmwares).
     */
    @Volatile
    var useNestedVideoMsgHeader: Boolean = false

    fun createVideoFrame(
        nalData: ByteArray,
        timestampMs: Long = System.currentTimeMillis() and 0xFFFF_FFFFL,
        nestedVideoMsgHeader: Boolean = useNestedVideoMsgHeader
    ): CarLifeFrame {
        val framePayload = if (nestedVideoMsgHeader) {
            ByteBuffer.allocate(8 + nalData.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(nalData.size)
                .putInt(CarLifeMsgType.MSG_VIDEO_DATA)
                .put(nalData)
                .array()
        } else {
            nalData
        }
        return CarLifeFrame(
            header = CarLifeHeader(
                channel = CarLifeChannel.VIDEO,
                rawChannelId = CarLifeChannel.VIDEO.id,
                payloadLength = framePayload.size,
                timestamp = timestampMs and 0xFFFF_FFFFL
            ),
            payload = framePayload
        )
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

    fun formatHexPreview(frame: CarLifeFrame, maxPayloadBytes: Int = 16): String {
        val headerBytes = encodeHeader(frame.header)
        val headerHex = headerBytes.joinToString(" ") { "%02X".format(it) }
        val sliceLen = minOf(frame.payload.size, maxPayloadBytes)
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
            val read = channel.read(dst)
            if (read < 0) {
                throw EOFException("USB AOA FileChannel closed while reading frame")
            }
        }
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(buffer, offset + totalRead, length - totalRead)
            if (read < 0) {
                throw EOFException(
                    "USB AOA stream closed while reading CarLife frame ($totalRead / $length bytes read)"
                )
            }
            totalRead += read
        }
    }
}
