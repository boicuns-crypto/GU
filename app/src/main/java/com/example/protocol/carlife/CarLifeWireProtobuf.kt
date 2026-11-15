package com.example.protocol.carlife

import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Zero-dependency binary Protobuf Wire Format encoder/decoder for Baidu CarLife messages.
 *
 * Implements standard Protobuf wire types:
 * - Wire Type `0` (`VARINT`): int32, int64, uint32, uint64, bool, enum
 * - Wire Type `2` (`LENGTH_DELIMITED`): string, bytes, embedded messages
 */
object CarLifeWireProtobuf {

    const val WIRE_TYPE_VARINT = 0
    const val WIRE_TYPE_FIXED64 = 1
    const val WIRE_TYPE_LENGTH_DELIMITED = 2
    const val WIRE_TYPE_FIXED32 = 5

    class Reader(private val data: ByteArray, private var pos: Int = 0, private val limit: Int = data.size) {
        var lastFieldNumber: Int = 0
            private set
        var lastWireType: Int = 0
            private set

        fun hasRemaining(): Boolean = pos < limit

        fun readTag(): Boolean {
            if (pos >= limit) return false
            val tag = readRawVarint32()
            if (tag == 0) return false
            lastFieldNumber = tag ushr 3
            lastWireType = tag and 0x07
            return true
        }

        fun readVarint32(): Int = readRawVarint32()

        fun readVarint64(): Long = readRawVarint64()

        fun readString(): String {
            val len = readRawVarint32()
            if (len <= 0 || pos + len > limit) return ""
            val str = String(data, pos, len, StandardCharsets.UTF_8)
            pos += len
            return str
        }

        fun readBytes(): ByteArray {
            val len = readRawVarint32()
            if (len <= 0 || pos + len > limit) return ByteArray(0)
            val out = ByteArray(len)
            System.arraycopy(data, pos, out, 0, len)
            pos += len
            return out
        }

        fun skipField() {
            when (lastWireType) {
                WIRE_TYPE_VARINT -> readRawVarint64()
                WIRE_TYPE_FIXED64 -> pos = (pos + 8).coerceAtMost(limit)
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val len = readRawVarint32()
                    if (len > 0) pos = (pos + len).coerceAtMost(limit)
                }
                WIRE_TYPE_FIXED32 -> pos = (pos + 4).coerceAtMost(limit)
                else -> pos = limit
            }
        }

        private fun readRawVarint32(): Int {
            var result = 0
            var shift = 0
            while (shift < 32 && pos < limit) {
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        private fun readRawVarint64(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64 && pos < limit) {
                val b = data[pos++].toLong()
                result = result or ((b and 0x7FL) shl shift)
                if ((b and 0x80L) == 0L) return result
                shift += 7
            }
            return result
        }
    }

    class Writer(initialCapacity: Int = 64) {
        private val out = ByteArrayOutputStream(initialCapacity)

        fun writeVarintField(fieldNumber: Int, value: Int): Writer {
            writeTag(fieldNumber, WIRE_TYPE_VARINT)
            writeRawVarint32(value)
            return this
        }

        fun writeStringField(fieldNumber: Int, value: String): Writer {
            if (value.isEmpty()) return this
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
            writeRawVarint32(bytes.size)
            out.write(bytes)
            return this
        }

        fun writeBytesField(fieldNumber: Int, value: ByteArray): Writer {
            if (value.isEmpty()) return this
            writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
            writeRawVarint32(value.size)
            out.write(value)
            return this
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeTag(fieldNumber: Int, wireType: Int) {
            writeRawVarint32((fieldNumber shl 3) or wireType)
        }

        private fun writeRawVarint32(value: Int) {
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

/**
 * `CarlifeProtocolVersion` (`MSG_CMD_HU_PROTOCOL_VERSION = 0x00018001`)
 * - Field 1: `int32 majorVersion`
 * - Field 2: `int32 minorVersion`
 */
data class CarlifeProtocolVersionProto(
    val majorVersion: Int = 2,
    val minorVersion: Int = 0
) {
    fun encode(): ByteArray = CarLifeWireProtobuf.Writer(16)
        .writeVarintField(1, majorVersion)
        .writeVarintField(2, minorVersion)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CarlifeProtocolVersionProto {
            var major = 1
            var minor = 0
            val reader = CarLifeWireProtobuf.Reader(bytes)
            while (reader.readTag()) {
                when (reader.lastFieldNumber) {
                    1 -> major = reader.readVarint32()
                    2 -> minor = reader.readVarint32()
                    else -> reader.skipField()
                }
            }
            return CarlifeProtocolVersionProto(major, minor)
        }
    }
}

/**
 * `CarlifeProtocolVersionMatchStatus` (`MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS = 0x00010002`)
 * - Field 1: `int32 matchStatus` (`1` = MATCH, `2` = NOT_MATCH)
 */
data class CarlifeProtocolVersionMatchStatusProto(
    val matchStatus: Int = MATCH_SUCCESS
) {
    fun encode(): ByteArray = CarLifeWireProtobuf.Writer(8)
        .writeVarintField(1, matchStatus)
        .toByteArray()

    companion object {
        const val MATCH_SUCCESS = 1
        const val MATCH_FAILED = 2

        fun decode(bytes: ByteArray): CarlifeProtocolVersionMatchStatusProto {
            var status = MATCH_SUCCESS
            val reader = CarLifeWireProtobuf.Reader(bytes)
            while (reader.readTag()) {
                when (reader.lastFieldNumber) {
                    1 -> status = reader.readVarint32()
                    else -> reader.skipField()
                }
            }
            return CarlifeProtocolVersionMatchStatusProto(status)
        }
    }
}

/**
 * `CarlifeDeviceInfo` (`MSG_CMD_HU_INFO = 0x00018003` / `MSG_CMD_MD_INFO = 0x00010004`)
 * Carries OS, hardware, and Bluetooth OOB address between the Jetta VS7 Head Unit and Galaxy S26 Ultra.
 */
data class CarlifeDeviceInfoProto(
    val os: String = "Android",
    val board: String = "sun",
    val bootloader: String = "S948BXXU1",
    val brand: String = "samsung",
    val cpuAbi: String = "arm64-v8a",
    val cpuAbi2: String = "",
    val device: String = "e3q",
    val display: String = "UP1A.260205.001",
    val fingerprint: String = "samsung/e3qxeea/e3q:16/UP1A/S948B:user/release-keys",
    val hardware: String = "qcom",
    val host: String = "SWDD6501",
    val cid: String = "S26U-CARLIFE",
    val manufacturer: String = "samsung",
    val model: String = "SM-S948B",
    val product: String = "e3qxeea",
    val serial: String = "R5CW90S26U",
    val codename: String = "REL",
    val incremental: String = "S948BXXU1",
    val release: String = Build.VERSION.RELEASE ?: "16",
    val sdk: String = Build.VERSION.SDK_INT.toString(),
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val token: String = "",
    val btaddress: String = "00:11:22:33:FF:EE"
) {
    fun encode(): ByteArray = CarLifeWireProtobuf.Writer(256)
        .writeStringField(1, os)
        .writeStringField(2, board)
        .writeStringField(3, bootloader)
        .writeStringField(4, brand)
        .writeStringField(5, cpuAbi)
        .writeStringField(6, cpuAbi2)
        .writeStringField(7, device)
        .writeStringField(8, display)
        .writeStringField(9, fingerprint)
        .writeStringField(10, hardware)
        .writeStringField(11, host)
        .writeStringField(12, cid)
        .writeStringField(13, manufacturer)
        .writeStringField(14, model)
        .writeStringField(15, product)
        .writeStringField(16, serial)
        .writeStringField(17, codename)
        .writeStringField(18, incremental)
        .writeStringField(19, release)
        .writeStringField(20, sdk)
        .writeVarintField(21, sdkInt)
        .writeStringField(22, token)
        .writeStringField(23, btaddress)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CarlifeDeviceInfoProto {
            var os = ""
            var board = ""
            var brand = ""
            var device = ""
            var manufacturer = ""
            var model = ""
            var release = ""
            var sdkInt = 0
            var btaddress = ""
            val reader = CarLifeWireProtobuf.Reader(bytes)
            while (reader.readTag()) {
                when (reader.lastFieldNumber) {
                    1 -> os = reader.readString()
                    2 -> board = reader.readString()
                    4 -> brand = reader.readString()
                    7 -> device = reader.readString()
                    13 -> manufacturer = reader.readString()
                    14 -> model = reader.readString()
                    19 -> release = reader.readString()
                    21 -> sdkInt = reader.readVarint32()
                    23 -> btaddress = reader.readString()
                    else -> reader.skipField()
                }
            }
            return CarlifeDeviceInfoProto(
                os = os.ifEmpty { "Linux-MQB" },
                board = board.ifEmpty { "Jetta-VS7-MIB" },
                brand = brand,
                device = device,
                manufacturer = manufacturer.ifEmpty { "FAW-VW / Jetta" },
                model = model.ifEmpty { "VS7 Head Unit" },
                release = release,
                sdkInt = sdkInt,
                btaddress = btaddress
            )
        }
    }
}

/**
 * `CarlifeVideoEncoderInfo` (`MSG_CMD_VIDEO_ENCODER_INIT = 0x00018007` & `MSG_CMD_VIDEO_ENCODER_INIT_DONE = 0x00010008`)
 * - Field 1: `int32 width` (e.g. 1280 or 1920)
 * - Field 2: `int32 height` (e.g. 720 or 1080)
 * - Field 3: `int32 frameRate` (e.g. 30 or 60)
 */
data class CarlifeVideoEncoderInfoProto(
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30
) {
    fun encode(): ByteArray = CarLifeWireProtobuf.Writer(24)
        .writeVarintField(1, width)
        .writeVarintField(2, height)
        .writeVarintField(3, frameRate)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CarlifeVideoEncoderInfoProto {
            var width = 0
            var height = 0
            var fps = 0
            val reader = CarLifeWireProtobuf.Reader(bytes)
            while (reader.readTag()) {
                when (reader.lastFieldNumber) {
                    1 -> width = reader.readVarint32()
                    2 -> height = reader.readVarint32()
                    3 -> fps = reader.readVarint32()
                    else -> reader.skipField()
                }
            }
            val validWidth = if (width in 480..3840) width else 1280
            val validHeight = if (height in 272..2160) height else 720
            val validFps = if (fps in 15..60) fps else 30
            return CarlifeVideoEncoderInfoProto(
                width = validWidth,
                height = validHeight,
                frameRate = validFps
            )
        }
    }
}

/**
 * `CarlifeVideoFrameRate` (`MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C` / `DONE = 0x0001000D`)
 * - Field 1: `int32 frameRate`
 */
data class CarlifeVideoFrameRateProto(
    val frameRate: Int = 30
) {
    fun encode(): ByteArray = CarLifeWireProtobuf.Writer(8)
        .writeVarintField(1, frameRate)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CarlifeVideoFrameRateProto {
            var fps = 30
            val reader = CarLifeWireProtobuf.Reader(bytes)
            while (reader.readTag()) {
                when (reader.lastFieldNumber) {
                    1 -> fps = reader.readVarint32()
                    else -> reader.skipField()
                }
            }
            return CarlifeVideoFrameRateProto(if (fps in 15..60) fps else 30)
        }
    }
}
