package com.example

import android.view.MotionEvent
import com.example.core.audio.AudioCaptureStats
import com.example.core.audio.CarLifeAudioCaptureManager
import com.example.core.carlife.CarLifePacketBuilder
import com.example.core.carlife.CarLifeProtocolConstants
import com.example.core.carlife.CarLifeTouchPacket
import com.example.core.input.CarLifeAccessibilityService
import com.example.core.projection.AppMode
import com.example.core.projection.CockpitProjectionManager
import com.example.core.projection.ProjectionPipelineStats
import com.example.core.projection.VideoSourceMode
import com.example.core.usb.BridgeRuntimeState
import com.example.core.usb.BridgeTelemetryBus
import com.example.feature.cockpit.AppLaunchMode
import com.example.protocol.carlife.CarLifeChannel
import com.example.protocol.carlife.CarLifeControlStateMachine
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeHardKey
import com.example.protocol.carlife.CarLifeInputEvent
import com.example.protocol.carlife.CarLifeMsgType
import com.example.protocol.carlife.CarLifeSessionPhase
import com.example.protocol.carlife.CarLifeTouchDecoder
import com.example.protocol.carlife.CarLifeTouchEventType
import com.example.protocol.carlife.CarlifeDeviceInfoProto
import com.example.protocol.carlife.CarlifeMusicInitProto
import com.example.protocol.carlife.CarlifeProtocolVersionProto
import com.example.protocol.carlife.CarlifeVideoEncoderInfoProto
import com.example.protocol.carlife.HandshakeTestProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun controlFrame_uses8ByteOuterAnd2ByteInnerLenHeader_andRoundTripsCommand() {
        val rawMatchPacket = CarLifeFrameCodec.encodeAoACmdPacket(
            serviceId = CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            protoBytes = byteArrayOf(0x08, 0x01)
        )
        // Verify exact 18-byte layout: [00 00 00 01][00 00 00 0A][00 02][00 00][00 01 00 02][08 01]
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x0A,
                0x00, 0x02,
                0x00, 0x00,
                0x00, 0x01, 0x00, 0x02,
                0x08, 0x01
            ),
            rawMatchPacket
        )

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
    fun videoAndAudioFrames_supportBothAoA20BAndLegacy12BFraming() {
        val h264Nal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x2A)
        val timestamp = 0x12345678L

        // Verify AOA 20B ([00 00 00 02][payloadLen + 12] + [payloadLen][ts][00 02 00 01]) is the default framing mode for 0x0002
        val defaultFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = timestamp
        )
        val expectedAoA20Bytes = CarLifeFrameCodec.buildAoAVideoPacket(h264Nal, timestamp.toInt())
        assertEquals(8, CarLifeFrameCodec.encodeHeader(defaultFrame.header).size)
        assertEquals(12 + h264Nal.size, defaultFrame.payload.size)
        assertArrayEquals(expectedAoA20Bytes, CarLifeFrameCodec.encodeFrame(defaultFrame))

        // 1. Default Mode: AOA 20B (8B AOA + 12B 0x00020001)
        val aoa20Frame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = timestamp,
            nestedVideoMsgHeader = true
        )
        assertArrayEquals(expectedAoA20Bytes, CarLifeFrameCodec.encodeFrame(aoa20Frame))
        assertTrue(CarLifeFrameCodec.formatHexPreview(aoa20Frame).contains("PAYLOAD[00 00 00 01"))

        val outAoA = ByteArrayOutputStream()
        CarLifeFrameCodec.writeFrame(outAoA, aoa20Frame)
        val decodedAoA = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(outAoA.toByteArray()))
        assertEquals(CarLifeChannel.VIDEO, decodedAoA.channel)
        assertEquals(timestamp, decodedAoA.header.timestamp)
        val parsedVideoMsg = decodedAoA.parseCommandMessageOrNull()
        assertNotNull(parsedVideoMsg)
        assertEquals(CarLifeMsgType.MSG_VIDEO_DATA, parsedVideoMsg!!.serviceType)
        assertArrayEquals(h264Nal, parsedVideoMsg.body)

        // 2. Default Mode: Direct 12B ([00 00 00 02][nalSize][timestamp] + [00 00 00 01...])
        val legacyFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = timestamp,
            nestedVideoMsgHeader = false
        )
        val encodedHeader = CarLifeFrameCodec.encodeHeader(legacyFrame.header)
        assertEquals(12, encodedHeader.size)

        val outLegacy = ByteArrayOutputStream()
        CarLifeFrameCodec.writeFrame(outLegacy, legacyFrame)

        val decodedLegacy = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(outLegacy.toByteArray()))
        assertEquals(CarLifeChannel.VIDEO, decodedLegacy.channel)
        assertEquals(timestamp, decodedLegacy.header.timestamp)
        assertArrayEquals(h264Nal, decodedLegacy.payload)
    }

    @Test
    fun controlStateMachine_negotiatesVersionAndVideoWithJettaVs7() {
        val sm = CarLifeControlStateMachine(
            force60FpsOnMqb = false,
            handshakeTestProfile = HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN
        )

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
        assertEquals(30, step2.updatedState.negotiatedFps)
        assertEquals(1, step2.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            step2.outboundFrames[0].parseCommandMessageOrNull()?.serviceType
        )

        val videoStartFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START
        )
        val step3 = sm.onControlFrame(videoStartFrame)
        assertEquals(CarLifeSessionPhase.VIDEO_STREAMING, step3.updatedState.phase)
        assertEquals(4, step3.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_SCREEN_ON,
            step3.outboundFrames[0].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT,
            step3.outboundFrames[1].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_FOREGROUND,
            step3.outboundFrames[2].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_REQUEST_GO_TO_FOREGROUND,
            step3.outboundFrames[3].parseCommandMessageOrNull()?.serviceType
        )

        // Verify Auth bypass (0x0001000B -> 0x0001800C, 0x00018004 -> 0x00010004)
        val auth0B = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_AUTHEN_REQUEST_0B,
            commandBody = byteArrayOf(0x0A, 0x02, 0x01, 0x02)
        )
        val auth0BResult = sm.onControlFrame(auth0B)
        assertEquals(1, auth0BResult.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_AUTHEN_RESPONSE_0C,
            auth0BResult.outboundFrames[0].parseCommandMessageOrNull()?.serviceType
        )
        assertTrue(auth0BResult.summary.contains("AUTH / UNKNOWN (0x0001000B)"))

        val statsReq = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_STATS_REQUEST
        )
        val statsResult = sm.onControlFrame(statsReq)
        assertEquals(1, statsResult.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_MD_INFO,
            statsResult.outboundFrames[0].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(0, statsResult.outboundFrames[0].parseCommandMessageOrNull()?.body?.size)

        // Verify Module Control, Authen Request, and AES Key Request replies
        val modCtrl = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MODULE_CONTROL,
            commandBody = byteArrayOf(0x08, 0x01, 0x10, 0x01)
        )
        val modResult = sm.onControlFrame(modCtrl)
        assertEquals(1, modResult.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_MODULE_STATUS,
            modResult.outboundFrames.first().parseCommandMessageOrNull()?.serviceType
        )
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
    fun videoFrameCodec_supportsNestedMsgVideoDataHeaderToggle() {
        val h264Nal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte())
        val nestedFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = 1234L,
            nestedVideoMsgHeader = true
        )
        assertEquals(CarLifeChannel.VIDEO, nestedFrame.channel)
        assertEquals(12 + h264Nal.size, nestedFrame.payload.size)
        val cmd = nestedFrame.parseCommandMessageOrNull()
        assertNotNull(cmd)
        assertEquals(CarLifeMsgType.MSG_VIDEO_DATA, cmd!!.serviceType)
        assertArrayEquals(h264Nal, cmd.body)
    }

    @Test
    fun streamAssembler_extractsServiceIdFrom16ByteZeroLengthFrame_andAutoStartsAfterMdInfo() {
        val assembler = com.example.protocol.carlife.CarLifeStreamAssembler()
        // Simulate 16-byte USB AOA read where bytes[4..7] == 00 00 00 00, bytes[8..11] == 00 00 00 00,
        // and bytes[12..15] == 0x00018007 (MSG_CMD_VIDEO_ENCODER_INIT)
        val raw16BytePacket = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // Channel 1
            0x00, 0x00, 0x00, 0x00, // Outer length = 0
            0x00, 0x00, 0x00, 0x00, // Inner protoLen = 0, reserved = 0
            0x00, 0x01, 0x80.toByte(), 0x07  // serviceId = MSG_CMD_VIDEO_ENCODER_INIT (0x00018007)
        )
        assembler.append(raw16BytePacket, 0, raw16BytePacket.size)
        val frames = assembler.drainFrames()
        assertEquals(1, frames.size)
        assertEquals(8, frames[0].payload.size)
        val parsed = frames[0].parseCommandMessageOrNull()
        assertNotNull(parsed)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT, parsed!!.serviceType)

        // Verify 0x0001800A is PAUSE (per CommonParams.smali), 0x0001000A does not exist, and auto-start sequence omits 0x0001000A
        assertEquals("MSG_CMD_VIDEO_ENCODER_PAUSE", CarLifeMsgType.nameOf(0x0001800A))
        assertEquals("AUTH / UNKNOWN (0x0001000A)", CarLifeMsgType.nameOf(0x0001000A))
        assertEquals("AUTH / UNKNOWN (0x00010077)", CarLifeMsgType.nameOf(0x00010077))
        val sm = CarLifeControlStateMachine()
        sm.setPreferredResolution(800, 480)
        val autoStart = sm.buildAutoVideoEncoderInitAndStartFrames(800, 480, 30)
        assertEquals(5, autoStart.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            autoStart.outboundFrames[0].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_SCREEN_ON,
            autoStart.outboundFrames[1].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT,
            autoStart.outboundFrames[2].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_FOREGROUND,
            autoStart.outboundFrames[3].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_REQUEST_GO_TO_FOREGROUND,
            autoStart.outboundFrames[4].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(CarLifeChannel.VIDEO, sm.createHeartbeatFrame(timestampMs = 1000L).channel)
    }

    @Test
    fun jettaVs7LockedProfile_audioInitBytesAndAoA20BVideo_verifySpec() {
        val profiles = com.example.core.usb.BridgeForegroundService.AUTO_TEST_PROFILES
        assertEquals(1, profiles.size)
        assertEquals(1280, profiles[0].width)
        assertEquals(720, profiles[0].height)
        assertEquals(2_000_000, profiles[0].bitrateBps)
        assertEquals(true, profiles[0].useNestedVideoHeader)
        assertEquals(true, profiles[0].forceAudioMultiplexerInit)

        val audioInitFrames = CarLifeFrameCodec.createAudioMultiplexerInitFrames()
        assertEquals(2, audioInitFrames.size)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            ),
            CarLifeFrameCodec.encodeFrame(audioInitFrames[0])
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x04,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            ),
            CarLifeFrameCodec.encodeFrame(audioInitFrames[1])
        )

        // Verify non-zero uptime timestamp is encoded cleanly into Audio Init 12B headers (0x0003 & 0x0004)
        val tsAudioInitFrames = CarLifeFrameCodec.createAudioMultiplexerInitFrames(timestampMs = 0x01020304L)
        assertEquals(0x01020304L, tsAudioInitFrames[0].header.timestamp)
        assertEquals(0x01020304L, tsAudioInitFrames[1].header.timestamp)
        val encodedAudio0 = CarLifeFrameCodec.encodeFrame(tsAudioInitFrames[0])
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), encodedAudio0.copyOfRange(8, 12))

        // Verify multi-NAL MediaCodec buffer ([00 00 00 01 SPS][00 00 00 01 PPS][00 00 00 01 IDR])
        // is split into 3 SEPARATE 0x0002 AOA 20B frames, each with its own 20B header and 00 00 00 01 prefix
        val sps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        val multiNalBuffer = sps + pps + idr
        val splitFrames = CarLifeFrameCodec.encodeAoAVideoFrames(multiNalBuffer, timestampMs = 500L)
        assertEquals(3, splitFrames.size)
        val wire0 = CarLifeFrameCodec.encodeFrame(splitFrames[0])
        val wire1 = CarLifeFrameCodec.encodeFrame(splitFrames[1])
        val wire2 = CarLifeFrameCodec.encodeFrame(splitFrames[2])
        assertEquals(20 + sps.size, wire0.size)
        assertEquals(20 + pps.size, wire1.size)
        assertEquals(20 + idr.size, wire2.size)
        assertArrayEquals(sps, wire0.copyOfRange(20, wire0.size))
        assertArrayEquals(pps, wire1.copyOfRange(20, wire1.size))
        assertArrayEquals(idr, wire2.copyOfRange(20, wire2.size))

        // Verify Samsung 3-byte start codes (00 00 01) and zero-padded start codes (00 00 00 00 01)
        // are normalized to strict 4-byte 00 00 00 01 in separate 20B AOA packets
        val sps3 = byteArrayOf(0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F)
        val pps3 = byteArrayOf(0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr3 = byteArrayOf(0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        val splitFrom3Byte = CarLifeFrameCodec.encodeAoAVideoFrames(sps3 + pps3 + idr3, timestampMs = 500L)
        assertEquals(3, splitFrom3Byte.size)
        assertArrayEquals(sps, CarLifeFrameCodec.encodeFrame(splitFrom3Byte[0]).let { it.copyOfRange(20, it.size) })
        assertArrayEquals(pps, CarLifeFrameCodec.encodeFrame(splitFrom3Byte[1]).let { it.copyOfRange(20, it.size) })
        assertArrayEquals(idr, CarLifeFrameCodec.encodeFrame(splitFrom3Byte[2]).let { it.copyOfRange(20, it.size) })

        val mixedPaddedBuffer = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F) +
            byteArrayOf(0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()) +
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        val splitFromMixed = CarLifeFrameCodec.encodeAoAVideoFrames(mixedPaddedBuffer, timestampMs = 501L)
        assertEquals(3, splitFromMixed.size)
        assertArrayEquals(sps, CarLifeFrameCodec.encodeFrame(splitFromMixed[0]).let { it.copyOfRange(20, it.size) })
        assertArrayEquals(pps, CarLifeFrameCodec.encodeFrame(splitFromMixed[1]).let { it.copyOfRange(20, it.size) })
        assertArrayEquals(idr, CarLifeFrameCodec.encodeFrame(splitFromMixed[2]).let { it.copyOfRange(20, it.size) })

        // Verify AVCC length-prefixed NAL is converted cleanly to Annex-B 00 00 00 01 inside encodeAoAVideoFrame
        val avccIdr = byteArrayOf(0x00, 0x00, 0x00, 0x04, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        val aoaFrame = CarLifeFrameCodec.encodeAoAVideoFrame(avccIdr, timestampMs = 500L)
        val wireBytes = CarLifeFrameCodec.encodeFrame(aoaFrame)
        assertEquals(20 + 8, wireBytes.size)
        // Outer 8B: ch=2, len=20 (8+12)
        assertEquals(0x02, wireBytes[3].toInt())
        assertEquals(20, wireBytes[7].toInt())
        // Inner 12B: len=8, ts=500, service=0x00020001
        assertEquals(8, wireBytes[11].toInt())
        assertEquals(0x00, wireBytes[16].toInt())
        assertEquals(0x02, wireBytes[17].toInt())
        assertEquals(0x00.toByte(), wireBytes[18])
        assertEquals(0x01, wireBytes[19].toInt())
        // Annex-B start code at bytes [20..23]
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01), wireBytes.copyOfRange(20, 24))

        val sm = CarLifeControlStateMachine(
            handshakeTestProfile = HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN
        )
        val versionFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION,
            commandBody = CarlifeProtocolVersionProto(2, 0).encode()
        )
        val step1 = sm.onControlFrame(versionFrame)
        assertEquals(2, step1.outboundFrames.size)
        assertEquals(CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, step1.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_MD_INFO, step1.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)
        val proactive = sm.buildAutoVideoEncoderInitAndStartFrames(800, 480, 30, "Proactive Handshake on 0x00018001")
        assertEquals(CarLifeSessionPhase.VIDEO_STREAMING, proactive.updatedState.phase)
        assertEquals(5, proactive.outboundFrames.size)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, proactive.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_SCREEN_ON, proactive.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT, proactive.outboundFrames[2].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_FOREGROUND, proactive.outboundFrames[3].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_REQUEST_GO_TO_FOREGROUND, proactive.outboundFrames[4].parseCommandMessageOrNull()?.serviceType)

        // Verify Direct 12B splits SPS + PPS + IDR into 3 separate 12-byte header frames
        val direct12Frames = CarLifeFrameCodec.createVideoFrames(
            h264Buffer = sps + pps + idr,
            timestampMs = 777L,
            nestedVideoMsgHeader = false
        )
        assertEquals(3, direct12Frames.size)
        val d0 = CarLifeFrameCodec.encodeFrame(direct12Frames[0])
        val d1 = CarLifeFrameCodec.encodeFrame(direct12Frames[1])
        val d2 = CarLifeFrameCodec.encodeFrame(direct12Frames[2])
        assertEquals(12 + sps.size, d0.size)
        assertEquals(12 + pps.size, d1.size)
        assertEquals(12 + idr.size, d2.size)
        assertArrayEquals(sps, d0.copyOfRange(12, d0.size))
        assertArrayEquals(pps, d1.copyOfRange(12, d1.size))
        assertArrayEquals(idr, d2.copyOfRange(12, d2.size))

        val moduleStatusFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MODULE_STATUS,
            commandBody = byteArrayOf(0x08, 0x01, 0x10, 0x01)
        )
        val transition = sm.onControlFrame(moduleStatusFrame)
        assertTrue(transition.summary.contains("MODULE_STATUS"))
    }

    @Test
    fun handshakeTestProfiles_strictlyMatchAllFourModesAndLogFullRxHex() {
        val versionFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION,
            commandBody = CarlifeProtocolVersionProto(2, 0).encode()
        )

        // Minimal MD_INFO must contain only field 1 (os = "Android"): 0A 07 + "Android" = 9 bytes
        val minimalMdInfo = CarlifeDeviceInfoProto().encodeMinimal()
        assertEquals(9, minimalMdInfo.size)
        assertEquals("Android", CarlifeDeviceInfoProto.decode(minimalMdInfo).os)

        // Test 1: ONLY MATCH_STATUS (0x00010002, body [08 01])
        val sm1 = CarLifeControlStateMachine(handshakeTestProfile = HandshakeTestProfile.TEST_1_MATCH_ONLY)
        val res1 = sm1.onControlFrame(versionFrame)
        assertEquals(1, res1.outboundFrames.size)
        val cmd1 = res1.outboundFrames[0].parseCommandMessageOrNull()!!
        assertEquals(CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, cmd1.serviceType)
        assertArrayEquals(byteArrayOf(0x08, 0x01), cmd1.body)

        // Test 2: MATCH_STATUS + minimal MD_INFO (0x00010004, 9 bytes)
        val sm2 = CarLifeControlStateMachine(handshakeTestProfile = HandshakeTestProfile.TEST_2_MATCH_AND_MIN_MD_INFO)
        val res2 = sm2.onControlFrame(versionFrame)
        assertEquals(2, res2.outboundFrames.size)
        assertEquals(CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, res2.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        val md2 = res2.outboundFrames[1].parseCommandMessageOrNull()!!
        assertEquals(CarLifeMsgType.MSG_CMD_MD_INFO, md2.serviceType)
        assertArrayEquals(minimalMdInfo, md2.body)

        // Test 3: MATCH_STATUS + minimal MD_INFO (0x00010004) + empty BT_OOB_INFO (0x00010006)
        val sm3 = CarLifeControlStateMachine(handshakeTestProfile = HandshakeTestProfile.TEST_3_MATCH_MIN_MD_INFO_BT_OOB)
        val res3 = sm3.onControlFrame(versionFrame)
        assertEquals(3, res3.outboundFrames.size)
        assertEquals(CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, res3.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_MD_INFO, res3.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)
        val bt3 = res3.outboundFrames[2].parseCommandMessageOrNull()!!
        assertEquals(CarLifeMsgType.MSG_CMD_MD_BT_OOB_INFO, bt3.serviceType)
        assertEquals(0, bt3.body.size)

        // Test 4 (Default Working Profile): MATCH_STATUS + full MD_INFO + Audio/Video Init + FOREGROUND + SCREEN_ON
        val sm4 = CarLifeControlStateMachine(handshakeTestProfile = HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN)
        assertEquals(HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN, HandshakeTestProfile.fromId(99))
        val res4 = sm4.onControlFrame(versionFrame)
        assertEquals(2, res4.outboundFrames.size)
        val fullMdCmd = res4.outboundFrames[1].parseCommandMessageOrNull()!!
        assertEquals(CarLifeMsgType.MSG_CMD_MD_INFO, fullMdCmd.serviceType)
        assertTrue(fullMdCmd.body.size > 100)

        val initStartWithFgScreen = sm4.buildAutoVideoEncoderInitAndStartFrames(
            width = 800,
            height = 480,
            fps = 30,
            reason = "Тест 4",
            includeForegroundAndScreenOn = true
        )
        assertEquals(5, initStartWithFgScreen.outboundFrames.size)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, initStartWithFgScreen.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_SCREEN_ON, initStartWithFgScreen.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT, initStartWithFgScreen.outboundFrames[2].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_FOREGROUND, initStartWithFgScreen.outboundFrames[3].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_REQUEST_GO_TO_FOREGROUND, initStartWithFgScreen.outboundFrames[4].parseCommandMessageOrNull()?.serviceType)

        // Exact 16-byte wire format per CommonParams.smali:
        // SCREEN_ON (0x00010018), SCREEN_USERPRESENT (0x0001001A), FOREGROUND (0x0001001B), REQUEST_GO_TO_FOREGROUND (0x00010038)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x08,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x18
            ),
            CarLifeFrameCodec.encodeFrame(initStartWithFgScreen.outboundFrames[1])
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x08,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x1A
            ),
            CarLifeFrameCodec.encodeFrame(initStartWithFgScreen.outboundFrames[2])
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x08,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x1B
            ),
            CarLifeFrameCodec.encodeFrame(initStartWithFgScreen.outboundFrames[3])
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x08,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x38
            ),
            CarLifeFrameCodec.encodeFrame(initStartWithFgScreen.outboundFrames[4])
        )

        // Default video framing is Mode 1: AOA 20B (0x00020001), switchable to Direct 12B on the fly
        assertTrue(CarLifeFrameCodec.useNestedVideoMsgHeader)
        assertTrue(BridgeRuntimeState().useNestedVideoMsgHeader)

        // Full RX HEX without truncation even for >256B packets
        val largeRxPayload = ByteArray(320) { (it and 0xFF).toByte() }
        val largeRxFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_HU_INFO,
            commandBody = largeRxPayload
        )
        val fullHex = CarLifeFrameCodec.formatHexPreview(largeRxFrame, maxPayloadBytes = Int.MAX_VALUE)
        assertFalse(fullHex.contains("… (+"))
        assertTrue(fullHex.endsWith("3E 3F]"))
    }

    @Test
    fun jettaVs7WireDump_directionBit0x8000Inverted_verifiesRxTxConstantsAndTest1() {
        // Verify exact constants per original CommonParams.smali & Jetta VS7 wire dump:
        // RX (HU -> MD): bit 0x8000 set
        assertEquals(0x00018001, CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION)
        assertEquals(0x00018003, CarLifeMsgType.MSG_CMD_HU_INFO)
        assertEquals(0x00018005, CarLifeMsgType.MSG_CMD_HU_BT_OOB_INFO)
        assertEquals(0x00018007, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT)
        assertEquals(0x00018009, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START)
        assertEquals(0x0001800A, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE)
        assertEquals(0x0001800C, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET)
        assertEquals(0x00018025, CarLifeMsgType.MSG_CMD_GO_TO_FOREGROUND)
        assertEquals(0x00018027, CarLifeMsgType.MSG_CMD_STATISTIC_INFO)

        // TX (MD -> HU): bit 0x8000 cleared
        assertEquals(0x00010002, CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS)
        assertEquals(0x00010004, CarLifeMsgType.MSG_CMD_MD_INFO)
        assertEquals(0x00010006, CarLifeMsgType.MSG_CMD_MD_BT_OOB_INFO)
        assertEquals(0x00010008, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE)
        assertEquals(0x00010018, CarLifeMsgType.MSG_CMD_SCREEN_ON)
        assertEquals(0x0001001A, CarLifeMsgType.MSG_CMD_SCREEN_USERPRESENT)
        assertEquals(0x0001001B, CarLifeMsgType.MSG_CMD_FOREGROUND)
        assertEquals(0x00010038, CarLifeMsgType.MSG_CMD_REQUEST_GO_TO_FOREGROUND)

        // Sync CarLifeProtocolConstants as well
        assertEquals(0x00018001, CarLifeProtocolConstants.MSG_CMD_HU_PROTOCOL_VERSION)
        assertEquals(0x00010002, CarLifeProtocolConstants.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS)
        assertEquals(0x00018003, CarLifeProtocolConstants.MSG_CMD_HU_INFO)
        assertEquals(0x00010004, CarLifeProtocolConstants.MSG_CMD_MD_INFO)
        assertEquals(0x00018005, CarLifeProtocolConstants.MSG_CMD_HU_BT_OOB_INFO)
        assertEquals(0x00010006, CarLifeProtocolConstants.MSG_CMD_MD_BT_OOB_INFO)
        assertEquals(0x00018007, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_INIT)
        assertEquals(0x00010008, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_INIT_DONE)
        assertEquals(0x00018009, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_START)
        assertEquals(0x0001800A, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_PAUSE)
        assertEquals(0x0001800C, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_RESET)
        assertEquals(0x00010018, CarLifeProtocolConstants.MSG_CMD_SCREEN_ON)
        assertEquals(0x0001001A, CarLifeProtocolConstants.MSG_CMD_SCREEN_USERPRESENT)
        assertEquals(0x0001001B, CarLifeProtocolConstants.MSG_CMD_FOREGROUND)
        assertEquals(0x00018025, CarLifeProtocolConstants.MSG_CMD_GO_TO_FOREGROUND)
        assertEquals(0x00018027, CarLifeProtocolConstants.MSG_CMD_STATISTIC_INFO)
        assertEquals(0x00010038, CarLifeProtocolConstants.MSG_CMD_REQUEST_GO_TO_FOREGROUND)

        // Exact RX wire dump from Jetta VS7 HU:
        // PAYLOAD[00 04 00 00 00 01 80 01 08 01 10 00] (outer ch=0x00000001, len=12)
        val rawJettaHuVersionPacket = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x0C,
            0x00, 0x04, 0x00, 0x00,
            0x00, 0x01, 0x80.toByte(), 0x01,
            0x08, 0x01, 0x10, 0x00
        )
        val rxFrame = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(rawJettaHuVersionPacket))
        val rxCmd = rxFrame.parseCommandMessageOrNull()!!
        assertEquals(0x00018001, rxCmd.serviceType)
        val huVer = CarlifeProtocolVersionProto.decode(rxCmd.body)
        assertEquals(1, huVer.majorVersion)
        assertEquals(0, huVer.minorVersion)

        // Test 1: Only MATCH_STATUS -> exact TX payload [00 02 00 00 00 01 00 02 08 01]
        val sm = CarLifeControlStateMachine(handshakeTestProfile = HandshakeTestProfile.TEST_1_MATCH_ONLY)
        val step1 = sm.onControlFrame(rxFrame)
        assertEquals(1, step1.outboundFrames.size)
        val txMatchBytes = CarLifeFrameCodec.encodeFrame(step1.outboundFrames[0])
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x0A,
                0x00, 0x02, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x02,
                0x08, 0x01
            ),
            txMatchBytes
        )

        // Incoming RX 0x00018027 (MSG_CMD_STATISTIC_INFO): log only, MUST NOT reply with 0x00010027
        val statFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x00018027,
            commandBody = byteArrayOf(0x08, 0x01)
        )
        val statStep = sm.onControlFrame(statFrame)
        assertTrue(statStep.outboundFrames.isEmpty())
        assertTrue(statStep.summary.contains("MSG_CMD_STATISTIC_INFO"))

        // Subsequent HU_INFO (0x00018003) -> MD_INFO (0x00010004)
        val huInfoFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x00018003,
            commandBody = CarlifeDeviceInfoProto(os = "Linux", manufacturer = "VW", model = "JettaVS7").encode()
        )
        val step2 = sm.onControlFrame(huInfoFrame)
        assertEquals(1, step2.outboundFrames.size)
        assertEquals(0x00010004, step2.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)

        // Subsequent VIDEO_ENCODER_INIT (0x00018007) -> INIT_DONE (0x00010008)
        val videoInitFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x00018007,
            commandBody = CarlifeVideoEncoderInfoProto(800, 480, 30).encode()
        )
        val step3 = sm.onControlFrame(videoInitFrame)
        assertEquals(CarLifeSessionPhase.VIDEO_INITIALIZED, step3.updatedState.phase)
        assertEquals(1, step3.outboundFrames.size)
        assertEquals(0x00010008, step3.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)

        // Subsequent VIDEO_ENCODER_START (0x00018009) -> NEVER sends 0x0001000A!
        // Sends exact 4 commands: SCREEN_ON (0x00010018), SCREEN_USERPRESENT (0x0001001A),
        // FOREGROUND (0x0001001B), REQUEST_GO_TO_FOREGROUND (0x00010038).
        val videoStartFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x00018009
        )
        val step4 = sm.onControlFrame(videoStartFrame)
        assertEquals(CarLifeSessionPhase.VIDEO_STREAMING, step4.updatedState.phase)
        assertEquals(4, step4.outboundFrames.size)
        assertEquals(0x00010018, step4.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(0x0001001A, step4.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)
        assertEquals(0x0001001B, step4.outboundFrames[2].parseCommandMessageOrNull()?.serviceType)
        assertEquals(0x00010038, step4.outboundFrames[3].parseCommandMessageOrNull()?.serviceType)
    }

    @Test
    fun videoChannel0x0002_uses0x00020001WithoutDirectionBit_andBuilds20ByteHeartbeat0x00020002() {
        assertEquals(0x00020001, CarLifeMsgType.MSG_VIDEO_DATA)
        assertEquals(0x00020002, CarLifeMsgType.MSG_VIDEO_HEARTBEAT)
        assertEquals(CarLifeProtocolConstants.MSG_VIDEO_DATA, CarLifeMsgType.MSG_VIDEO_DATA)
        assertEquals(CarLifeProtocolConstants.MSG_VIDEO_HEARTBEAT, CarLifeMsgType.MSG_VIDEO_HEARTBEAT)

        // Default video source mode must be Режим 1: Зеркалирование экрана (MediaProjection)
        assertEquals(VideoSourceMode.SCREEN_MIRRORING, ProjectionPipelineStats().videoSourceMode)
        assertEquals(VideoSourceMode.SCREEN_MIRRORING, BridgeRuntimeState().videoSourceMode)
        assertEquals(VideoSourceMode.SCREEN_MIRRORING, VideoSourceMode.fromId("screen_mirroring"))
        assertEquals(VideoSourceMode.CANVAS_TEST_CARD, VideoSourceMode.fromId("canvas_test_card"))
        assertEquals(VideoSourceMode.VIRTUAL_DISPLAY, VideoSourceMode.fromId("virtual_display"))
        assertEquals(VideoSourceMode.SCREEN_MIRRORING, VideoSourceMode.fromId(null))

        // Direct 12B vs AOA 20B with 0x00020001
        val sampleNal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte())
        val aoa20Bytes = CarLifeFrameCodec.buildAoAVideoPacket(sampleNal, timestampMs = 1234)
        assertEquals(20 + sampleNal.size, aoa20Bytes.size)
        assertArrayEquals(
            byteArrayOf(0x00, 0x02, 0x00, 0x01),
            aoa20Bytes.copyOfRange(16, 20)
        )

        // AOA 20B Video Heartbeat (MSG_VIDEO_HEARTBEAT = 0x00020002):
        // Outer 8B [00 00 00 02][00 00 00 0C] + Inner 12B [len=0][timestamp][00 02 00 02]
        val hbTs = 0x11223344
        val hbFrame = CarLifeFrameCodec.createVideoHeartbeatFrame(timestampMs = hbTs.toLong())
        val hbWire = CarLifeFrameCodec.encodeFrame(hbFrame)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x0C,
                0x00, 0x00, 0x00, 0x00,
                0x11, 0x22, 0x33, 0x44,
                0x00, 0x02, 0x00, 0x02
            ),
            hbWire
        )
        assertArrayEquals(hbWire, CarLifeFrameCodec.buildAoAVideoHeartbeatPacket(hbTs))
        val decodedHb = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(hbWire))
        assertEquals(CarLifeChannel.VIDEO, decodedHb.channel)
        assertEquals(hbTs.toLong(), decodedHb.header.timestamp)
        val parsedHb = decodedHb.parseCommandMessageOrNull()
        assertNotNull(parsedHb)
        assertEquals(0x00020002, parsedHb!!.serviceType)
        assertEquals(0, parsedHb.body.size)

        val direct12Frame = CarLifeFrameCodec.createVideoFrame(
            nalData = sampleNal,
            timestampMs = 1234L,
            nestedVideoMsgHeader = false
        )
        val direct12Bytes = CarLifeFrameCodec.encodeFrame(direct12Frame)
        assertEquals(12 + sampleNal.size, direct12Bytes.size)
        assertArrayEquals(sampleNal, direct12Bytes.copyOfRange(12, direct12Bytes.size))
    }

    @Test
    fun videoOutputThreadSmali_bundlesSpsPpsAndIdrIntoSingleAoA20BPacketWithoutSplitting() {
        // Default framing mode must be Mode 1: AOA 20B (0x00020001)
        assertTrue(CarLifeFrameCodec.useNestedVideoMsgHeader)
        assertTrue(BridgeRuntimeState().useNestedVideoMsgHeader)

        // Simulated BUFFER_FLAG_CODEC_CONFIG (SPS + PPS with mixed 3B/4B start codes)
        val rawCodecConfig = byteArrayOf(
            0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F, 0xE9.toByte(),
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()
        )
        val cachedSpsPps = CarLifeFrameCodec.normalizeConfigBuffer(rawCodecConfig)
        assertTrue(CarLifeFrameCodec.containsSpsAndPps(cachedSpsPps))

        // 1. Standalone IDR frame -> merged into ONE buffer: cachedSpsPps + idrBytes
        val idrOnly = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0x10)
        val combinedKeyFrame = CarLifeFrameCodec.mergeSpsPpsWithIdr(cachedSpsPps, idrOnly)
        assertEquals(cachedSpsPps.size + idrOnly.size, combinedKeyFrame.size)
        assertArrayEquals(cachedSpsPps + idrOnly, combinedKeyFrame)

        // Wrap combined SPS+PPS+IDR in ONE default AOA 20B video packet (0x00020001)
        val tsMs = 0x00001234L
        val singleKeyFramePacket = CarLifeFrameCodec.createVideoFrame(
            nalData = combinedKeyFrame,
            timestampMs = tsMs
        )
        val wireKeyFrame = CarLifeFrameCodec.encodeFrame(singleKeyFramePacket)
        assertEquals(20 + combinedKeyFrame.size, wireKeyFrame.size)
        val hdrBuf = ByteBuffer.wrap(wireKeyFrame, 0, 20).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x00000002, hdrBuf.int)                    // Channel 0x0002
        assertEquals(combinedKeyFrame.size + 12, hdrBuf.int)    // Outer AOA len = payloadLen + 12
        assertEquals(combinedKeyFrame.size, hdrBuf.int)         // Inner video payloadLen
        assertEquals(tsMs.toInt(), hdrBuf.int)                  // Timestamp ms
        assertEquals(0x00020001, hdrBuf.int)                    // MSG_VIDEO_DATA without 0x8000 bit
        assertArrayEquals(combinedKeyFrame, wireKeyFrame.copyOfRange(20, wireKeyFrame.size))

        // 2. If codec already prepended SPS+PPS before IDR, mergeSpsPpsWithIdr must NOT duplicate them
        val alreadyPrepended = cachedSpsPps + idrOnly
        val noDupKeyFrame = CarLifeFrameCodec.mergeSpsPpsWithIdr(cachedSpsPps, alreadyPrepended)
        assertArrayEquals(alreadyPrepended, noDupKeyFrame)

        // 3. Regular P-frame (Slice): sent as ONE packet without splitting
        val pSlice = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte(), 0x22, 0x33, 0x44)
        val singlePFrame = CarLifeFrameCodec.createVideoFrame(nalData = pSlice, timestampMs = tsMs + 33L)
        val wirePFrame = CarLifeFrameCodec.encodeFrame(singlePFrame)
        assertEquals(20 + pSlice.size, wirePFrame.size)
        assertArrayEquals(byteArrayOf(0x00, 0x02, 0x00, 0x01), wirePFrame.copyOfRange(16, 20))
        assertArrayEquals(pSlice, wirePFrame.copyOfRange(20, wirePFrame.size))
    }

    @Test
    fun dynamicTouchScaling_andDefault1280x720Resolution_andHfpTelephonyBlocked() {
        // 1. Default resolution 1280x720@30fps (2 000 000 bps / 2 Mbps), 160 dpi, and huTouchMax 1280x720
        val defaultRuntime = BridgeRuntimeState()
        assertEquals(1280, defaultRuntime.configuredWidth)
        assertEquals(720, defaultRuntime.configuredHeight)
        assertEquals(2_000_000, defaultRuntime.configuredBitrateBps)
        assertEquals(AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE, defaultRuntime.appLaunchMode)
        assertEquals(AppLaunchMode.SCREEN_MIRRORING, AppLaunchMode.fromId("screen_mirroring"))

        val defaultStats = ProjectionPipelineStats()
        assertEquals(1280, defaultStats.width)
        assertEquals(720, defaultStats.height)
        assertEquals(30, defaultStats.fps)
        assertEquals(2000, defaultStats.bitRateKbps)
        assertEquals(160, CockpitProjectionManager.FIXED_VIRTUAL_DISPLAY_DPI)
        assertEquals(2_000_000, CockpitProjectionManager.DEFAULT_BITRATE_BPS)

        assertEquals(1280f, CarLifeTouchDecoder.DEFAULT_HU_TOUCH_MAX_X, 0.01f)
        assertEquals(720f, CarLifeTouchDecoder.DEFAULT_HU_TOUCH_MAX_Y, 0.01f)

        // 2. Dynamic scaling formula: rawX * (currentSurfaceWidth / huTouchMaxX)
        val (sx720, sy720) = CarLifeTouchDecoder.scaleTouchCoordinates(
            rawX = 640,
            rawY = 360,
            currentSurfaceWidth = 1280,
            currentSurfaceHeight = 720
        )
        assertEquals(640f, sx720, 0.01f)
        assertEquals(360f, sy720, 0.01f)

        val (sx480, sy480) = CarLifeTouchDecoder.scaleTouchCoordinates(
            rawX = 640,
            rawY = 360,
            currentSurfaceWidth = 800,
            currentSurfaceHeight = 480
        )
        assertEquals(400f, sx480, 0.01f)
        assertEquals(240f, sy480, 0.01f)

        val (sx600, sy600) = CarLifeTouchDecoder.scaleTouchCoordinates(
            rawX = 320,
            rawY = 180,
            currentSurfaceWidth = 1024,
            currentSurfaceHeight = 600
        )
        assertEquals(256f, sx600, 0.01f)
        assertEquals(150f, sy600, 0.01f)

        val (sx1080, sy1080) = CarLifeTouchDecoder.scaleTouchCoordinates(
            rawX = 1280,
            rawY = 720,
            currentSurfaceWidth = 1920,
            currentSurfaceHeight = 1080
        )
        assertEquals(1920f, sx1080, 0.01f)
        assertEquals(1080f, sy1080, 0.01f)

        // 3. Bluetooth HFP & Telephony commands (0x00010040, 0x0001004F) must NEVER be emitted
        val sm = CarLifeControlStateMachine()
        assertEquals(1280, sm.sessionState.value.negotiatedVideoWidth)
        assertEquals(720, sm.sessionState.value.negotiatedVideoHeight)

        val rxHfp40 = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x00018040,
            commandBody = byteArrayOf(0x08, 0x01)
        )
        val res40 = sm.onControlFrame(rxHfp40)
        assertTrue("0x00010040 must NEVER be sent", res40.outboundFrames.isEmpty())

        val rxTele4F = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x0001804F,
            commandBody = byteArrayOf(0x08, 0x01)
        )
        val res4F = sm.onControlFrame(rxTele4F)
        assertTrue("0x0001004F must NEVER be sent", res4F.outboundFrames.isEmpty())

        val rxHfpMid = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x00018045,
            commandBody = byteArrayOf(0x08, 0x01)
        )
        val resMid = sm.onControlFrame(rxHfpMid)
        assertTrue("HFP/telephony range 0x00010040..0x0001004F must NEVER be sent", resMid.outboundFrames.isEmpty())
    }

    @Test
    fun mediaAudioChannel0x0003_aoa20BInitDataStop_andSilenceSuppression_verifySpec() {
        // 1. Verify CarlifeMusicInitProto wire bytes:
        //    Field 1 (sampleRate=48000, tag 0x08) -> 08 80 F7 02
        //    Field 2 (channelConfig=2, tag 0x10)  -> 10 02
        //    Field 3 (sampleFormat=16, tag 0x18)  -> 18 10
        val musicInit = CarlifeMusicInitProto(sampleRate = 48_000, channelConfig = 2, sampleFormat = 16)
        val protoBytes = musicInit.encode()
        assertArrayEquals(
            byteArrayOf(
                0x08, 0x80.toByte(), 0xF7.toByte(), 0x02,
                0x10, 0x02,
                0x18, 0x10
            ),
            protoBytes
        )
        val decodedInitProto = CarlifeMusicInitProto.decode(protoBytes)
        assertEquals(48_000, decodedInitProto.sampleRate)
        assertEquals(2, decodedInitProto.channelConfig)
        assertEquals(16, decodedInitProto.sampleFormat)

        // 2. Verify MSG_MEDIA_INIT (0x00030001) in AOA 20B Big-Endian format
        val tsInitSec = 1_700_000_000L
        val initFrame = CarLifeFrameCodec.createMediaInitFrame(timestampSec = tsInitSec)
        val initWire = CarLifeFrameCodec.encodeFrame(initFrame)
        val expectedInitWire = CarLifeFrameCodec.buildAoAMediaAudioPacket(
            serviceType = CarLifeMsgType.MSG_MEDIA_INIT,
            payload = protoBytes,
            timestampSec = tsInitSec.toInt()
        )
        assertArrayEquals(expectedInitWire, initWire)
        assertEquals(20 + protoBytes.size, initWire.size)
        val initBuf = ByteBuffer.wrap(initWire).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x00000003, initBuf.int) // 8B AOA channel = 0x0003
        assertEquals(protoBytes.size + 12, initBuf.int) // 8B AOA len = payloadLen + 12
        assertEquals(protoBytes.size, initBuf.int) // 12B audio len = payloadLen
        assertEquals(tsInitSec.toInt(), initBuf.int) // 12B audio timestampSec
        assertEquals(CarLifeMsgType.MSG_MEDIA_INIT, initBuf.int) // 0x00030001

        // Round-trip decode via both decodeFrame() and readNextFrame(InputStream)
        val decodedInitDirect = CarLifeFrameCodec.decodeFrame(initWire)
        assertEquals(CarLifeChannel.MEDIA_AUDIO, decodedInitDirect.channel)
        assertEquals(tsInitSec, decodedInitDirect.header.timestamp)
        assertEquals(CarLifeMsgType.MSG_MEDIA_INIT, decodedInitDirect.parseCommandMessageOrNull()?.serviceType)
        assertArrayEquals(protoBytes, decodedInitDirect.parseCommandMessageOrNull()?.body)

        val decodedInitStream = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(initWire))
        assertEquals(CarLifeChannel.MEDIA_AUDIO, decodedInitStream.channel)
        assertEquals(CarLifeMsgType.MSG_MEDIA_INIT, decodedInitStream.parseCommandMessageOrNull()?.serviceType)
        assertArrayEquals(protoBytes, decodedInitStream.parseCommandMessageOrNull()?.body)
        assertEquals("MSG_MEDIA_INIT", CarLifeMsgType.nameOf(CarLifeMsgType.MSG_MEDIA_INIT))

        // 3. Verify MSG_MEDIA_DATA (0x00030006) with 4096-byte PCM chunk in AOA 20B format
        val pcm4096 = ByteArray(CarLifeAudioCaptureManager.PCM_CHUNK_SIZE_BYTES) { idx ->
            ((idx * 13 + 7) and 0xFF).toByte()
        }
        val tsDataSec = 1_700_000_001L
        val dataFrame = CarLifeFrameCodec.createMediaDataFrame(pcmBytes = pcm4096, timestampSec = tsDataSec)
        val dataWire = CarLifeFrameCodec.encodeFrame(dataFrame)
        assertEquals(20 + 4096, dataWire.size)
        val dataBuf = ByteBuffer.wrap(dataWire).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x00000003, dataBuf.int)
        assertEquals(4096 + 12, dataBuf.int)
        assertEquals(4096, dataBuf.int)
        assertEquals(tsDataSec.toInt(), dataBuf.int)
        assertEquals(CarLifeMsgType.MSG_MEDIA_DATA, dataBuf.int) // 0x00030006

        val decodedDataStream = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(dataWire))
        assertEquals(CarLifeChannel.MEDIA_AUDIO, decodedDataStream.channel)
        assertEquals(tsDataSec, decodedDataStream.header.timestamp)
        val parsedData = decodedDataStream.parseCommandMessageOrNull()!!
        assertEquals(CarLifeMsgType.MSG_MEDIA_DATA, parsedData.serviceType)
        assertArrayEquals(pcm4096, parsedData.body)
        assertEquals("MSG_MEDIA_DATA", CarLifeMsgType.nameOf(CarLifeMsgType.MSG_MEDIA_DATA))

        // 4. Verify MSG_MEDIA_STOP (0x00030002) with empty payload in AOA 20B format
        val tsStopSec = 1_700_000_002L
        val stopFrame = CarLifeFrameCodec.createMediaStopFrame(timestampSec = tsStopSec)
        val stopWire = CarLifeFrameCodec.encodeFrame(stopFrame)
        assertEquals(20, stopWire.size)
        val stopBuf = ByteBuffer.wrap(stopWire).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x00000003, stopBuf.int)
        assertEquals(12, stopBuf.int) // 0 + 12
        assertEquals(0, stopBuf.int) // payloadLen = 0
        assertEquals(tsStopSec.toInt(), stopBuf.int)
        assertEquals(CarLifeMsgType.MSG_MEDIA_STOP, stopBuf.int) // 0x00030002

        val decodedStopStream = CarLifeFrameCodec.readNextFrame(ByteArrayInputStream(stopWire))
        assertEquals(CarLifeMsgType.MSG_MEDIA_STOP, decodedStopStream.parseCommandMessageOrNull()?.serviceType)
        assertEquals(0, decodedStopStream.parseCommandMessageOrNull()?.body?.size)
        assertEquals("MSG_MEDIA_STOP", CarLifeMsgType.nameOf(CarLifeMsgType.MSG_MEDIA_STOP))

        // 5. Verify silence suppression helper
        val silentBuf = ByteArray(4096)
        assertTrue(CarLifeAudioCaptureManager.isBufferSilent(silentBuf, 4096))
        silentBuf[4095] = 0x01
        assertFalse(CarLifeAudioCaptureManager.isBufferSilent(silentBuf, 4096))
        assertTrue(CarLifeAudioCaptureManager.isBufferSilent(silentBuf, 4095))
    }

    @Test
    fun carLifeAccessibilityService_scalesPhysicalScreenCoordinatesAndClassifiesTapVsSwipe() {
        // 1. Exact 16:9 landscape phone (1920x1080) mirrored into 1280x720 -> linear scaling
        val (l16x, l16y) = CarLifeAccessibilityService.mapHuTouchToPhysicalScreen(
            rawX = 640,
            rawY = 360,
            huWidth = 1280f,
            huHeight = 720f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(960f, l16x, 0.5f)
        assertEquals(540f, l16y, 0.5f)

        val (l16CornerX, l16CornerY) = CarLifeAccessibilityService.mapHuTouchToPhysicalScreen(
            rawX = 1280,
            rawY = 720,
            huWidth = 1280f,
            huHeight = 720f,
            screenWidth = 1920,
            screenHeight = 1080
        )
        assertEquals(1919f, l16CornerX, 0.5f)
        assertEquals(1079f, l16CornerY, 0.5f)

        // 2. Ultrawide landscape phone (e.g. S26 Ultra 3120x1440) -> vertical letterbox inside 1280x720
        // scale = min(1280/3120, 720/1440) = 1280/3120 ≈ 0.410256, drawnH ≈ 590.77, offsetY ≈ 64.615
        val (uwCenterX, uwCenterY) = CarLifeAccessibilityService.mapHuTouchToPhysicalScreen(
            rawX = 640,
            rawY = 360,
            huWidth = 1280f,
            huHeight = 720f,
            screenWidth = 3120,
            screenHeight = 1440
        )
        assertEquals(1560f, uwCenterX, 1.0f)
        assertEquals(720f, uwCenterY, 1.0f)

        // Tap in top letterbox bar clamps cleanly to y=0
        val (uwTopX, uwTopY) = CarLifeAccessibilityService.mapHuTouchToPhysicalScreen(
            rawX = 0,
            rawY = 10,
            huWidth = 1280f,
            huHeight = 720f,
            screenWidth = 3120,
            screenHeight = 1440
        )
        assertEquals(0f, uwTopX, 0.5f)
        assertEquals(0f, uwTopY, 0.5f)

        // 3. Portrait phone (1440x3120) -> horizontal pillarbox inside 1280x720
        val (ptCenterX, ptCenterY) = CarLifeAccessibilityService.mapHuTouchToPhysicalScreen(
            rawX = 640,
            rawY = 360,
            huWidth = 1280f,
            huHeight = 720f,
            screenWidth = 1440,
            screenHeight = 3120
        )
        assertEquals(720f, ptCenterX, 1.0f)
        assertEquals(1560f, ptCenterY, 1.0f)

        // 4. Short tap vs map swipe classification
        assertTrue(
            CarLifeAccessibilityService.isShortTapGesture(
                startX = 500f,
                startY = 500f,
                endX = 508f,
                endY = 505f,
                points = listOf(500f to 500f, 504f to 502f, 508f to 505f)
            )
        )
        assertFalse(
            CarLifeAccessibilityService.isShortTapGesture(
                startX = 500f,
                startY = 500f,
                endX = 720f,
                endY = 510f,
                points = listOf(500f to 500f, 610f to 505f, 720f to 510f)
            )
        )

        // 5. Tap duration (40..60ms) & Swipe duration clamping (50..1500ms)
        assertTrue(CarLifeAccessibilityService.DEFAULT_TAP_DURATION_MS in 40L..60L)
        assertEquals(50L, CarLifeAccessibilityService.computeSwipeDurationMs(1000L, 1010L))
        assertEquals(320L, CarLifeAccessibilityService.computeSwipeDurationMs(1000L, 1320L))
        assertEquals(1500L, CarLifeAccessibilityService.computeSwipeDurationMs(1000L, 4500L))
    }

    @Test
    fun appModeDefaults_uiLogGating_andSpeakerMuteDefaults_verifySpec() {
        val defaultRuntime = BridgeRuntimeState()
        assertEquals(AppMode.MIRRORING, defaultRuntime.appMode)
        assertEquals(VideoSourceMode.SCREEN_MIRRORING, defaultRuntime.videoSourceMode)
        assertFalse(defaultRuntime.logPacketsInUi)
        assertTrue(defaultRuntime.mutePhoneSpeakerDuringUsbAudio)
        assertTrue(AudioCaptureStats().mutePhoneSpeakerDuringUsbAudio)
        assertTrue(CockpitProjectionManager.MIRROR_VIRTUAL_DISPLAY_DPI in 160..200)
        assertEquals(AppMode.COCKPIT, AppMode.fromId("cockpit"))
        assertEquals(AppMode.MIRRORING, AppMode.fromId("mirroring"))
        assertEquals(AppMode.MIRRORING, AppMode.fromId(null))

        BridgeTelemetryBus.clearLogs()
        BridgeTelemetryBus.setLogPacketsInUi(false)
        BridgeTelemetryBus.setAppMode(AppMode.MIRRORING)
        assertFalse(BridgeTelemetryBus.runtimeState.value.logPacketsInUi)

        val videoTestFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte()),
            timestampMs = 123L
        )
        // With logPacketsInUi == false, recordFrame must NOT allocate packet log entries
        BridgeTelemetryBus.recordFrame(direction = "TX (S26U -> HU)", frame = videoTestFrame)
        assertEquals(0, BridgeTelemetryBus.runtimeState.value.videoPacketLogs.size)
        assertEquals(1L, BridgeTelemetryBus.runtimeState.value.channelFrameCounts[CarLifeChannel.VIDEO])

        // Manual Protocol Lab vectors with forceLog = true must still log
        BridgeTelemetryBus.recordFrame(direction = "TX (S26U -> HU)", frame = videoTestFrame, forceLog = true)
        assertEquals(1, BridgeTelemetryBus.runtimeState.value.videoPacketLogs.size)

        // Switching to COCKPIT enables VirtualDisplay and keeps UI logging OFF by default unless toggled ON
        BridgeTelemetryBus.setAppMode(AppMode.COCKPIT)
        assertEquals(AppMode.COCKPIT, BridgeTelemetryBus.runtimeState.value.appMode)
        assertEquals(VideoSourceMode.VIRTUAL_DISPLAY, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
        assertFalse(BridgeTelemetryBus.runtimeState.value.logPacketsInUi)

        BridgeTelemetryBus.setAppMode(AppMode.MIRRORING)
        BridgeTelemetryBus.clearLogs()
    }

    @Test
    fun usbWriteQueue_prioritizesAudio0x0003_andDropsStaleVideoPFramesOver5() {
        val queue = com.example.core.usb.CarLifeUsbWriteQueue(maxVideoFrames = 5)

        val idrNal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte())
        fun pFrameWithMarker(marker: Int) = CarLifeFrameCodec.createVideoFrame(
            nalData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, marker.toByte()),
            timestampMs = marker.toLong()
        )
        val idrFrame = CarLifeFrameCodec.createVideoFrame(nalData = idrNal, timestampMs = 1L)

        // Enqueue IDR + 7 P-frames (total 8 video frames > max 5): oldest stale P-frames must drop,
        // keeping max 5 video frames with IDR preserved.
        queue.enqueue(idrFrame, isKeyFrameHint = true)
        for (i in 1..7) {
            queue.enqueue(pFrameWithMarker(10 + i), isKeyFrameHint = false)
        }
        assertEquals(5, queue.videoSize)
        assertEquals(3L, queue.droppedVideoPFrames.get())

        // Now enqueue two 0x0003 audio packets AFTER the buffered video frames:
        // Audio 0x0003 MUST dequeue FIRST in FIFO order before any 0x0002 video frame!
        val audio1 = CarLifeFrameCodec.createMediaDataFrame(byteArrayOf(1, 2, 3, 4), timestampSec = 100L)
        val audio2 = CarLifeFrameCodec.createMediaDataFrame(byteArrayOf(5, 6, 7, 8), timestampSec = 101L)
        queue.enqueue(audio1)
        queue.enqueue(audio2)

        val firstOut = queue.pollNonBlocking()!!
        val secondOut = queue.pollNonBlocking()!!
        assertEquals(CarLifeChannel.MEDIA_AUDIO, firstOut.channel)
        assertEquals(100L, firstOut.header.timestamp)
        assertEquals(CarLifeChannel.MEDIA_AUDIO, secondOut.channel)
        assertEquals(101L, secondOut.header.timestamp)

        // Next 5 frames come from 0x0002 video queue starting with preserved IDR (ts=1), then newest P-frames (14..17)
        val videoTs = (1..5).map { queue.pollNonBlocking()!!.header.timestamp }
        assertEquals(listOf(1L, 14L, 15L, 16L, 17L), videoTs)
        assertEquals(null, queue.pollNonBlocking())
    }

    @Test
    fun shizukuCockpitMode_titlesIdsAndTelemetryMapping_verifySpec() {
        assertEquals("Кокпит (Стандартный)", AppMode.COCKPIT.title)
        assertEquals("Зеркалирование (Экран телефона)", AppMode.MIRRORING.title)
        assertEquals("Кокпит + Shizuku (Второй экран)", AppMode.SHIZUKU_COCKPIT.title)
        assertEquals(AppMode.SHIZUKU_COCKPIT, AppMode.fromId("shizuku_cockpit"))
        assertEquals(AppLaunchMode.SHIZUKU_VIRTUAL_DISPLAY, AppLaunchMode.fromId("shizuku_virtual_display"))

        BridgeTelemetryBus.setAppMode(AppMode.SHIZUKU_COCKPIT)
        assertEquals(AppMode.SHIZUKU_COCKPIT, BridgeTelemetryBus.runtimeState.value.appMode)
        assertEquals(VideoSourceMode.VIRTUAL_DISPLAY, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
        assertEquals(AppLaunchMode.SHIZUKU_VIRTUAL_DISPLAY, BridgeTelemetryBus.runtimeState.value.appLaunchMode)

        BridgeTelemetryBus.setAppMode(AppMode.MIRRORING)
        assertEquals(AppMode.MIRRORING, BridgeTelemetryBus.runtimeState.value.appMode)
        assertEquals(VideoSourceMode.SCREEN_MIRRORING, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
        assertEquals(AppLaunchMode.SCREEN_MIRRORING, BridgeTelemetryBus.runtimeState.value.appLaunchMode)
    }
}



