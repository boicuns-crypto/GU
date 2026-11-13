package com.example

import android.view.MotionEvent
import com.example.core.carlife.CarLifePacketBuilder
import com.example.core.carlife.CarLifeProtocolConstants
import com.example.core.carlife.CarLifeTouchPacket
import com.example.protocol.carlife.CarLifeChannel
import com.example.protocol.carlife.CarLifeControlStateMachine
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeHardKey
import com.example.protocol.carlife.CarLifeInputEvent
import com.example.protocol.carlife.CarLifeMsgType
import com.example.protocol.carlife.CarLifeSessionPhase
import com.example.protocol.carlife.CarLifeTouchDecoder
import com.example.protocol.carlife.CarLifeTouchEventType
import com.example.protocol.carlife.CarlifeProtocolVersionProto
import com.example.protocol.carlife.CarlifeVideoEncoderInfoProto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun controlFrame_uses8ByteHeader_andRoundTripsCommand() {
        val protoPayload = CarlifeProtocolVersionProto(majorVersion = 2, minorVersion = 0).encode()
        val frame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION,
            commandBody = protoPayload
        )

        val encodedHeader = CarLifeFrameCodec.encodeHeader(frame.header)
        assertEquals(8, encodedHeader.size)

        val out = ByteArrayOutputStream()
        CarLifeFrameCodec.writeFrame(out, frame)

        val decoded = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(CarLifeChannel.CONTROL, decoded.channel)
        assertEquals(0L, decoded.header.timestamp)

        val cmd = decoded.parseCommandMessageOrNull()
        assertNotNull(cmd)
        assertEquals(CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION, cmd!!.serviceType)
        assertArrayEquals(protoPayload, cmd.body)
    }

    @Test
    fun videoAndAudioFrames_use12ByteHeaderWithTimestamp() {
        val h264Nal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x2A)
        val timestamp = 0x12345678L
        val videoFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = timestamp
        )

        val encodedHeader = CarLifeFrameCodec.encodeHeader(videoFrame.header)
        assertEquals(12, encodedHeader.size)

        val out = ByteArrayOutputStream()
        CarLifeFrameCodec.writeFrame(out, videoFrame)

        val decoded = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(CarLifeChannel.VIDEO, decoded.channel)
        assertEquals(timestamp, decoded.header.timestamp)
        assertArrayEquals(h264Nal, decoded.payload)
    }

    @Test
    fun controlStateMachine_negotiatesVersionAndVideoWithJettaVs7() {
        val sm = CarLifeControlStateMachine(force60FpsOnMqb = true)

        val versionFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION,
            commandBody = CarlifeProtocolVersionProto(2, 0).encode()
        )
        val step1 = sm.onControlFrame(versionFrame)
        assertEquals(CarLifeSessionPhase.VERSION_MATCHED, step1.updatedState.phase)
        assertEquals(2, step1.outboundFrames.size)

        val videoInitFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT,
            commandBody = CarlifeVideoEncoderInfoProto(width = 1280, height = 720, frameRate = 30).encode()
        )
        val step2 = sm.onControlFrame(videoInitFrame)
        assertEquals(CarLifeSessionPhase.VIDEO_INITIALIZED, step2.updatedState.phase)
        assertEquals(1280, step2.updatedState.negotiatedVideoWidth)
        assertEquals(720, step2.updatedState.negotiatedVideoHeight)
        assertEquals(60, step2.updatedState.negotiatedFps)
    }

    @Test
    fun touchDecoder_scalesCoordinatesAndMapsMqbHardKeys() {
        val touchFrame = CarLifeTouchDecoder.createTouchActionFrame(
            action = 0,
            x = 640,
            y = 360,
            huWidth = 1280,
            huHeight = 720
        )
        val decodedTouch = CarLifeTouchDecoder.decodeTouchChannelFrame(
            frame = touchFrame,
            targetWidth = 1920,
            targetHeight = 1080
        )
        assertTrue(decodedTouch is CarLifeInputEvent.Touch)
        val touch = decodedTouch as CarLifeInputEvent.Touch
        assertEquals(CarLifeTouchEventType.DOWN, touch.type)
        assertEquals(960f, touch.scaledX, 0.5f)
        assertEquals(540f, touch.scaledY, 0.5f)

        val keyFrame = CarLifeTouchDecoder.createHardKeyFrame(CarLifeHardKey.SEEK_ADD)
        val decodedKey = CarLifeTouchDecoder.decodeTouchChannelFrame(keyFrame)
        assertTrue(decodedKey is CarLifeInputEvent.HardKey)
        assertEquals(CarLifeHardKey.SEEK_ADD, (decodedKey as CarLifeInputEvent.HardKey).mappedKey)
    }

    @Test
    fun carLifeProtocolConstants_andPacketBuilder_verifyHeadersAndTouch() {
        val initDone = CarLifePacketBuilder.buildVideoEncoderInitDonePacket(1920, 1080, 60)
        assertEquals(CarLifeProtocolConstants.CHANNEL_CONTROL, initDone.channelId)
        assertEquals(8, initDone.headerSizeBytes)
        val (msgType, body) = initDone.extractInnerCommandOrNull()!!
        assertEquals(CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_INIT_DONE, msgType)
        val (w, h, fps) = CarLifePacketBuilder.parseVideoEncoderInitRequest(body)
        assertEquals(1920, w)
        assertEquals(1080, h)
        assertEquals(60, fps)

        val videoNal = CarLifePacketBuilder.buildVideoNalPacket(
            nalBytes = byteArrayOf(0, 0, 0, 1, 0x65),
            timestampMs = 999L
        )
        assertEquals(CarLifeProtocolConstants.CHANNEL_VIDEO, videoNal.channelId)
        assertEquals(12, videoNal.headerSizeBytes)
        assertEquals(999L, videoNal.timestampMs)

        val touchFrame = CarLifeTouchDecoder.createTouchActionFrame(
            action = 0,
            x = 500,
            y = 250,
            huWidth = 1280,
            huHeight = 720
        )
        val rawTouch = com.example.core.carlife.CarLifeRawPacket(
            channelId = CarLifeProtocolConstants.CHANNEL_TOUCH,
            payloadLength = touchFrame.payload.size,
            payload = touchFrame.payload
        )
        val (touchMsgType, touchBody) = rawTouch.extractInnerCommandOrNull()!!
        val parsedTouch = CarLifePacketBuilder.parseTouchOrKeyPayload(touchMsgType, touchBody) as CarLifeTouchPacket
        assertEquals(MotionEvent.ACTION_DOWN, parsedTouch.rawX.let { parsedTouch.androidAction })
        assertEquals(500, parsedTouch.rawX)
        assertEquals(250, parsedTouch.rawY)
    }

    @Test
    fun vagUdsTelemetryService_decodesEngine7E8_andGearbox7E9_positiveResponses() {
        val initial = com.example.feature.telemetry.VagTelemetryState()

        // 1. Engine Oil Temp 0xF45C: A = 136 -> 136 - 40 = 96.0°C (OPTIMAL)
        val oilReq = com.example.feature.telemetry.VagUdsDidRequest(
            ecuName = "01-Engine",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = com.example.feature.telemetry.VagUdsTelemetryService.DID_ENGINE_OIL_TEMP,
            label = "Oil Temp"
        )
        val afterOil = com.example.feature.telemetry.VagUdsTelemetryService.decodeUdsResponseBytes(
            current = initial,
            request = oilReq,
            responseBytes = byteArrayOf(0x62, 0xF4.toByte(), 0x5C, 136.toByte())
        )
        assertEquals(96f, afterOil.oilTempC, 0.01f)
        assertEquals(com.example.feature.telemetry.ThermalZone.OPTIMAL, afterOil.oilThermalZone)

        // 2. Gearbox AQ250 ATF Temp 0x101E: A = 122 -> 122 - 40 = 82.0°C (OPTIMAL)
        val atfReq = com.example.feature.telemetry.VagUdsDidRequest(
            ecuName = "02-Gearbox",
            txHeader = "7E1",
            rxHeader = "7E9",
            did = com.example.feature.telemetry.VagUdsTelemetryService.DID_GEARBOX_ATF_TEMP,
            label = "ATF Temp"
        )
        val afterAtf = com.example.feature.telemetry.VagUdsTelemetryService.decodeUdsResponseBytes(
            current = afterOil,
            request = atfReq,
            responseBytes = byteArrayOf(0x62, 0x10, 0x1E, 122.toByte())
        )
        assertEquals(82f, afterAtf.gearboxTempC, 0.01f)
        assertEquals(com.example.feature.telemetry.ThermalZone.OPTIMAL, afterAtf.gearboxThermalZone)

        // 3. Turbo Boost 0x11E4: raw 3700 -> 3700 * 0.5 = 1850 hPa -> (1850 - 1013.25) / 1000 = +0.84 Bar
        val boostReq = com.example.feature.telemetry.VagUdsDidRequest(
            ecuName = "01-Engine",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = com.example.feature.telemetry.VagUdsTelemetryService.DID_TURBO_BOOST_PRESSURE,
            label = "Boost"
        )
        val afterBoost = com.example.feature.telemetry.VagUdsTelemetryService.decodeUdsResponseBytes(
            current = afterAtf,
            request = boostReq,
            responseBytes = byteArrayOf(0x62, 0x11, 0xE4.toByte(), 0x0E, 0x74)
        )
        assertEquals(0.84f, afterBoost.boostBar, 0.02f)

        // 4. Control Module Voltage 0xF442: 14200 mV (0x3778) -> 14.2 V
        val voltReq = com.example.feature.telemetry.VagUdsDidRequest(
            ecuName = "01-Engine",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = com.example.feature.telemetry.VagUdsTelemetryService.DID_BATTERY_VOLTAGE,
            label = "Voltage"
        )
        val afterVoltage = com.example.feature.telemetry.VagUdsTelemetryService.decodeUdsResponseBytes(
            current = afterBoost,
            request = voltReq,
            responseBytes = byteArrayOf(0x62, 0xF4.toByte(), 0x42, 0x37, 0x78)
        )
        assertEquals(14.2f, afterVoltage.voltageV, 0.01f)
    }
}


