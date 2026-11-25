package com.example.protocol.carlife

import android.view.KeyEvent
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Normalized touch motion action decoded from Baidu CarLife Channel `0x0006`.
 */
enum class CarLifeTouchEventType(val androidMotionAction: Int, val label: String) {
    DOWN(MotionEvent.ACTION_DOWN, "ACTION_DOWN"),
    MOVE(MotionEvent.ACTION_MOVE, "ACTION_MOVE"),
    UP(MotionEvent.ACTION_UP, "ACTION_UP"),
    SINGLE_CLICK(MotionEvent.ACTION_UP, "SINGLE_CLICK"),
    DOUBLE_CLICK(MotionEvent.ACTION_UP, "DOUBLE_CLICK"),
    LONG_PRESS(MotionEvent.ACTION_DOWN, "LONG_PRESS")
}

/**
 * High-level steering wheel action resolved from `MSG_TOUCH_CAR_HARD_KEY_CODE (0x00060008)`,
 * supporting runtime track button inversion (`15`/`7` <-> `16`/`6`).
 */
enum class SteeringWheelAction(
    val actionName: String,
    val androidKeyCode: Int
) {
    PREVIOUS_TRACK("Пред. трек", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
    NEXT_TRACK("След. трек", KeyEvent.KEYCODE_MEDIA_NEXT),
    PLAY_PAUSE("Пауза / Воспр.", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    BACK("Назад", KeyEvent.KEYCODE_BACK),
    HOME("Домой", KeyEvent.KEYCODE_HOME),
    VOICE_ASSISTANT("Голосовой ассистент", KeyEvent.KEYCODE_VOICE_ASSIST),
    NAVIGATION("Навигация", KeyEvent.KEYCODE_NAVIGATE_IN),
    UNASSIGNED("Не назначено", KeyEvent.KEYCODE_UNKNOWN)
}

/**
 * Steering wheel & MIB rotary knob hardkeys sent by Jetta VS7 (MQB) over
 * `MSG_TOUCH_CAR_HARD_KEY_CODE (0x00060008 / 0x00068008)`.
 *
 * Code mapping table:
 * - Previous track (`previous()`): `15` (`0x0F`, `KEYCODE_SEEK_SUB`), `7` (`0x07`, `KEYCODE_SELECTOR_PREVIOUS`)
 * - Next track (`next()`): `16` (`0x10`, `KEYCODE_SEEK_ADD`), `6` (`0x06`, `KEYCODE_SELECTOR_NEXT`)
 * - Play / Pause (`playPause()`): `20` (`0x14`, `KEYCODE_OK`), `31` (`0x1F`, `KEYCODE_MEDIA_START`),
 *   `32` (`0x20`, `KEYCODE_MEDIA_STOP`), `19` (`0x13`, `KEYCODE_MUTE`), `22` (`0x16`), `23` (`0x17`), `24` (`0x18`)
 * - Back: `14` (`0x0E`, `KEYCODE_BACK`)
 * - Home: `1` (`0x01`, `KEYCODE_HOME`)
 * - Voice Assistant: `33` (`0x21`, `KEYCODE_VR_START`)
 */
enum class CarLifeHardKey(
    val carLifeCode: Int,
    val androidKeyCode: Int,
    val label: String
) {
    HOME(0x00000001, KeyEvent.KEYCODE_HOME, "HOME (1)"),
    PHONE_CALL(0x00000002, KeyEvent.KEYCODE_CALL, "PHONE_CALL (2)"),
    PHONE_END(0x00000003, KeyEvent.KEYCODE_ENDCALL, "PHONE_END (3)"),
    SELECTOR_NEXT(0x00000006, KeyEvent.KEYCODE_MEDIA_NEXT, "KEYCODE_SELECTOR_NEXT (6)"),
    SELECTOR_PREVIOUS(0x00000007, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "KEYCODE_SELECTOR_PREVIOUS (7)"),
    VR_STOP(0x0000000A, KeyEvent.KEYCODE_ESCAPE, "VR_STOP (10)"),
    NAVI(0x0000000D, KeyEvent.KEYCODE_NAVIGATE_IN, "KEYCODE_NAVI (13)"),
    BACK(0x0000000E, KeyEvent.KEYCODE_BACK, "KEYCODE_BACK (14)"),
    SEEK_SUB(0x0000000F, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "KEYCODE_SEEK_SUB (15)"),
    SEEK_ADD(0x00000010, KeyEvent.KEYCODE_MEDIA_NEXT, "KEYCODE_SEEK_ADD (16)"),
    MUTE(0x00000013, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "KEYCODE_MUTE (19)"),
    OK(0x00000014, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "KEYCODE_OK (20)"),
    PLAY_PAUSE_22(0x00000016, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE (22)"),
    PLAY_PAUSE_ALT(0x00000017, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE (23)"),
    PLAY_PAUSE(0x00000018, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE (24)"),
    MEDIA_START(0x0000001F, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "KEYCODE_MEDIA_START (31)"),
    MEDIA_STOP(0x00000020, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "KEYCODE_MEDIA_STOP (32)"),
    VR_START(0x00000021, KeyEvent.KEYCODE_VOICE_ASSIST, "KEYCODE_VR_START (33)");

    companion object {
        fun fromCode(code: Int): CarLifeHardKey? =
            entries.firstOrNull { it.carLifeCode == code }

        fun resolveAction(
            code: Int,
            swapTrackButtons: Boolean = false
        ): SteeringWheelAction = when (code) {
            15, 7 -> if (swapTrackButtons) {
                SteeringWheelAction.NEXT_TRACK
            } else {
                SteeringWheelAction.PREVIOUS_TRACK
            }
            16, 6 -> if (swapTrackButtons) {
                SteeringWheelAction.PREVIOUS_TRACK
            } else {
                SteeringWheelAction.NEXT_TRACK
            }
            20, 31, 32, 19, 22, 23, 24 -> SteeringWheelAction.PLAY_PAUSE
            14 -> SteeringWheelAction.BACK
            1 -> SteeringWheelAction.HOME
            33 -> SteeringWheelAction.VOICE_ASSISTANT
            13 -> SteeringWheelAction.NAVIGATION
            else -> SteeringWheelAction.UNASSIGNED
        }

        fun formatDiagnosticToast(
            keycode: Int,
            swapTrackButtons: Boolean = false
        ): String {
            val actionName = resolveAction(keycode, swapTrackButtons).actionName
            return "Руль: code=$keycode (0x${keycode.toString(16)}) → $actionName"
        }
    }
}

/**
 * Decoded input event emitted by [CarLifeTouchDecoder] from Channel `0x0006`.
 */
sealed interface CarLifeInputEvent {
    /**
     * Touch event mapped from the Jetta VS7 Head Unit screen space (`huWidth x huHeight`)
     * into the target `VirtualDisplay` or Android Auto projection surface (`scaledX`, `scaledY`).
     */
    data class Touch(
        val type: CarLifeTouchEventType,
        val rawX: Int,
        val rawY: Int,
        val huWidth: Int,
        val huHeight: Int,
        val scaledX: Float,
        val scaledY: Float,
        val targetWidth: Int,
        val targetHeight: Int
    ) : CarLifeInputEvent

    /**
     * Steering wheel button or rotary encoder key event from the Jetta VS7 MQB CAN-bus gateway.
     */
    data class HardKey(
        val rawKeyCode: Int,
        val mappedKey: CarLifeHardKey?,
        val androidKeyCode: Int
    ) : CarLifeInputEvent
}

/**
 * Byte-level parser for the Baidu CarLife Touch & Hardkey Channel (`0x0006`).
 *
 * Supports both Protobuf-encoded payloads (`CarlifeTouchAction`, `CarlifeTouchSinglePoint`,
 * `CarlifeCarHardKeyCode`) and raw 12-byte Big-Endian binary payloads used by certain MQB firmware builds.
 */
object CarLifeTouchDecoder {

    const val DEFAULT_HU_TOUCH_MAX_X: Float = 1280f
    const val DEFAULT_HU_TOUCH_MAX_Y: Float = 720f

    /**
     * Dynamically scales Head Unit touch coordinates (`rawX`, `rawY`) into any target surface resolution:
     * `scaledX = rawX * (currentSurfaceWidth.toFloat() / huTouchMaxX)`
     * `scaledY = rawY * (currentSurfaceHeight.toFloat() / huTouchMaxY)`
     */
    fun scaleTouchCoordinates(
        rawX: Int,
        rawY: Int,
        currentSurfaceWidth: Int,
        currentSurfaceHeight: Int,
        huTouchMaxX: Float = DEFAULT_HU_TOUCH_MAX_X,
        huTouchMaxY: Float = DEFAULT_HU_TOUCH_MAX_Y
    ): Pair<Float, Float> {
        val safeSurfaceW = currentSurfaceWidth.coerceAtLeast(1).toFloat()
        val safeSurfaceH = currentSurfaceHeight.coerceAtLeast(1).toFloat()
        val safeHuMaxX = if (huTouchMaxX > 0f) huTouchMaxX else DEFAULT_HU_TOUCH_MAX_X
        val safeHuMaxY = if (huTouchMaxY > 0f) huTouchMaxY else DEFAULT_HU_TOUCH_MAX_Y

        val scaledX = (rawX.toFloat() * (safeSurfaceW / safeHuMaxX)).coerceIn(0f, safeSurfaceW)
        val scaledY = (rawY.toFloat() * (safeSurfaceH / safeHuMaxY)).coerceIn(0f, safeSurfaceH)
        return scaledX to scaledY
    }

    /**
     * Decodes a `CarLifeChannel.TOUCH (0x0006)` frame and maps coordinates to `[targetWidth] x [targetHeight]`.
     */
    fun decodeTouchChannelFrame(
        frame: CarLifeFrame,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        defaultHuWidth: Int = 1280,
        defaultHuHeight: Int = 720
    ): CarLifeInputEvent? {
        if (frame.channel != CarLifeChannel.TOUCH) return null
        val cmd = frame.parseCommandMessageOrNull() ?: return null

        return when (cmd.serviceType) {
            CarLifeMsgType.MSG_TOUCH_ACTION -> decodeTouchActionProto(
                body = cmd.body,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                defaultHuWidth = defaultHuWidth,
                defaultHuHeight = defaultHuHeight
            )

            CarLifeMsgType.MSG_TOUCH_ACTION_DOWN -> decodeSinglePoint(
                type = CarLifeTouchEventType.DOWN,
                body = cmd.body,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                huWidth = defaultHuWidth,
                huHeight = defaultHuHeight
            )

            CarLifeMsgType.MSG_TOUCH_ACTION_MOVE -> decodeSinglePoint(
                type = CarLifeTouchEventType.MOVE,
                body = cmd.body,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                huWidth = defaultHuWidth,
                huHeight = defaultHuHeight
            )

            CarLifeMsgType.MSG_TOUCH_ACTION_UP -> decodeSinglePoint(
                type = CarLifeTouchEventType.UP,
                body = cmd.body,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                huWidth = defaultHuWidth,
                huHeight = defaultHuHeight
            )

            CarLifeMsgType.MSG_TOUCH_SINGLE_CLICK -> decodeSinglePoint(
                type = CarLifeTouchEventType.SINGLE_CLICK,
                body = cmd.body,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                huWidth = defaultHuWidth,
                huHeight = defaultHuHeight
            )

            CarLifeMsgType.MSG_TOUCH_DOUBLE_CLICK -> decodeSinglePoint(
                type = CarLifeTouchEventType.DOUBLE_CLICK,
                body = cmd.body,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                huWidth = defaultHuWidth,
                huHeight = defaultHuHeight
            )

            CarLifeMsgType.MSG_TOUCH_LONG_PRESS -> decodeSinglePoint(
                type = CarLifeTouchEventType.LONG_PRESS,
                body = cmd.body,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                huWidth = defaultHuWidth,
                huHeight = defaultHuHeight
            )

            CarLifeMsgType.MSG_TOUCH_CAR_HARD_KEY_CODE,
            CarLifeMsgType.MSG_TOUCH_CAR_HARD_KEY_CODE_ALT -> decodeHardKeyCode(cmd.body)

            else -> null
        }
    }

    /**
     * Encodes a `CarlifeTouchAction` (`MSG_TOUCH_ACTION = 0x00068001`) frame for testing or loopback injection.
     *
     * Protobuf schema (`CarlifeTouchAction`):
     * - Field 1 (`int32 action`): `0` = DOWN, `1` = UP, `2` = MOVE
     * - Field 2 (`int32 x`)
     * - Field 3 (`int32 y`)
     * - Field 4 (`int32 width`)
     * - Field 5 (`int32 height`)
     */
    fun createTouchActionFrame(
        action: Int,
        x: Int,
        y: Int,
        huWidth: Int = 1280,
        huHeight: Int = 720
    ): CarLifeFrame {
        val protoBytes = CarLifeWireProtobuf.Writer(32)
            .writeVarintField(1, action)
            .writeVarintField(2, x)
            .writeVarintField(3, y)
            .writeVarintField(4, huWidth)
            .writeVarintField(5, huHeight)
            .toByteArray()

        return CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.TOUCH,
            serviceType = CarLifeMsgType.MSG_TOUCH_ACTION,
            commandBody = protoBytes
        )
    }

    /**
     * Encodes a steering wheel hardkey (`MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008` / `0x00060008`) frame.
     */
    fun createHardKeyFrame(
        key: CarLifeHardKey,
        serviceType: Int = CarLifeMsgType.MSG_TOUCH_CAR_HARD_KEY_CODE
    ): CarLifeFrame {
        val protoBytes = CarLifeWireProtobuf.Writer(12)
            .writeVarintField(1, key.carLifeCode)
            .toByteArray()

        return CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.TOUCH,
            serviceType = serviceType,
            commandBody = protoBytes
        )
    }

    /**
     * Encodes an arbitrary integer hardkey code for diagnostics / unit testing.
     */
    fun createRawHardKeyFrame(
        rawCode: Int,
        serviceType: Int = CarLifeMsgType.MSG_TOUCH_CAR_HARD_KEY_CODE_ALT
    ): CarLifeFrame {
        val protoBytes = CarLifeWireProtobuf.Writer(12)
            .writeVarintField(1, rawCode)
            .toByteArray()

        return CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.TOUCH,
            serviceType = serviceType,
            commandBody = protoBytes
        )
    }

    private fun decodeTouchActionProto(
        body: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        defaultHuWidth: Int,
        defaultHuHeight: Int
    ): CarLifeInputEvent.Touch {
        var actionCode = MotionEvent.ACTION_DOWN
        var x = 0
        var y = 0
        var width = defaultHuWidth
        var height = defaultHuHeight

        val reader = CarLifeWireProtobuf.Reader(body)
        while (reader.readTag()) {
            when (reader.lastFieldNumber) {
                1 -> actionCode = reader.readVarint32()
                2 -> x = reader.readVarint32()
                3 -> y = reader.readVarint32()
                4 -> width = reader.readVarint32().takeIf { it > 0 } ?: defaultHuWidth
                5 -> height = reader.readVarint32().takeIf { it > 0 } ?: defaultHuHeight
                else -> reader.skipField()
            }
        }

        val eventType = when (actionCode) {
            0 -> CarLifeTouchEventType.DOWN
            1 -> CarLifeTouchEventType.UP
            2 -> CarLifeTouchEventType.MOVE
            else -> CarLifeTouchEventType.DOWN
        }

        return buildScaledTouchEvent(
            type = eventType,
            rawX = x,
            rawY = y,
            huWidth = width,
            huHeight = height,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    private fun decodeSinglePoint(
        type: CarLifeTouchEventType,
        body: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        huWidth: Int,
        huHeight: Int
    ): CarLifeInputEvent.Touch {
        var x = 0
        var y = 0

        // Check if encoded as Protobuf (tag byte 0x08 for field 1) or raw 8/12-byte Big-Endian ints
        if (body.isNotEmpty() && body[0].toInt() == 0x08) {
            val reader = CarLifeWireProtobuf.Reader(body)
            while (reader.readTag()) {
                when (reader.lastFieldNumber) {
                    1 -> x = reader.readVarint32()
                    2 -> y = reader.readVarint32()
                    else -> reader.skipField()
                }
            }
        } else if (body.size >= 12) {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            buf.int // skip action field
            x = buf.int
            y = buf.int
        } else if (body.size >= 8) {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            x = buf.int
            y = buf.int
        }

        return buildScaledTouchEvent(
            type = type,
            rawX = x,
            rawY = y,
            huWidth = huWidth,
            huHeight = huHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    private fun decodeHardKeyCode(body: ByteArray): CarLifeInputEvent.HardKey {
        var keyCode = 0
        var parsedProto = false
        if (body.isNotEmpty()) {
            // Try Protobuf field #1 (tag 0x08, varint) even if another tag precedes it
            if ((body[0].toInt() and 0xFF) == 0x08 || body.any { (it.toInt() and 0xFF) == 0x08 }) {
                val reader = CarLifeWireProtobuf.Reader(body)
                while (reader.readTag()) {
                    when (reader.lastFieldNumber) {
                        1 -> {
                            keyCode = reader.readVarint32()
                            parsedProto = true
                        }
                        else -> reader.skipField()
                    }
                }
            }
            if (!parsedProto && body.size >= 4) {
                keyCode = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).int
            }
        }
        val mapped = CarLifeHardKey.fromCode(keyCode)
        return CarLifeInputEvent.HardKey(
            rawKeyCode = keyCode,
            mappedKey = mapped,
            androidKeyCode = mapped?.androidKeyCode ?: KeyEvent.KEYCODE_UNKNOWN
        )
    }

    private fun buildScaledTouchEvent(
        type: CarLifeTouchEventType,
        rawX: Int,
        rawY: Int,
        huWidth: Int,
        huHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): CarLifeInputEvent.Touch {
        val safeHuW = huWidth.takeIf { it > 0 } ?: DEFAULT_HU_TOUCH_MAX_X.toInt()
        val safeHuH = huHeight.takeIf { it > 0 } ?: DEFAULT_HU_TOUCH_MAX_Y.toInt()
        val (scaledX, scaledY) = scaleTouchCoordinates(
            rawX = rawX,
            rawY = rawY,
            currentSurfaceWidth = targetWidth,
            currentSurfaceHeight = targetHeight,
            huTouchMaxX = safeHuW.toFloat(),
            huTouchMaxY = safeHuH.toFloat()
        )

        return CarLifeInputEvent.Touch(
            type = type,
            rawX = rawX,
            rawY = rawY,
            huWidth = safeHuW,
            huHeight = safeHuH,
            scaledX = scaledX,
            scaledY = scaledY,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }
}
