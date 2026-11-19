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
import com.example.protocol.carlife.CarlifeDeviceInfoProto
import com.example.protocol.carlife.CarlifeProtocolVersionProto
import com.example.protocol.carlife.CarlifeVideoEncoderInfoProto
import com.example.protocol.carlife.HandshakeTestProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

        // Verify Direct 12B ([00 00 00 02][nalSize][timestamp] + Annex-B) is the default framing mode for 0x0002
        val defaultFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = timestamp
        )
        assertEquals(12, CarLifeFrameCodec.encodeHeader(defaultFrame.header).size)
        assertEquals(h264Nal.size, defaultFrame.payload.size)
        assertArrayEquals(h264Nal, defaultFrame.payload)

        // 1. Optional Mode: AOA 20B (8B AOA + 12B 0x00028001)
        val aoa20Frame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = timestamp,
            nestedVideoMsgHeader = true
        )
        val expectedAoA20Bytes = CarLifeFrameCodec.buildAoAVideoPacket(h264Nal, timestamp.toInt())
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
            commandBody = CarlifeVideoEncoderInfoProto(width = 800, height = 480, frameRate = 30).encode()
        )
        val step2 = sm.onControlFrame(videoInitFrame)
        assertEquals(CarLifeSessionPhase.VIDEO_INITIALIZED, step2.updatedState.phase)
        assertEquals(800, step2.updatedState.negotiatedVideoWidth)
        assertEquals(480, step2.updatedState.negotiatedVideoHeight)
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
        assertEquals(3, step3.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_DONE,
            step3.outboundFrames[0].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_FOREGROUND,
            step3.outboundFrames[1].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_SCREEN_ON,
            step3.outboundFrames[2].parseCommandMessageOrNull()?.serviceType
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

        // Verify START_DONE (0x0001000A) vs unknown (0x0001800A) and auto-start sequence
        assertEquals("MSG_CMD_VIDEO_ENCODER_START_DONE", CarLifeMsgType.nameOf(0x0001000A))
        assertEquals("AUTH / UNKNOWN (0x0001800A)", CarLifeMsgType.nameOf(0x0001800A))
        assertEquals("AUTH / UNKNOWN (0x00010077)", CarLifeMsgType.nameOf(0x00010077))
        val sm = CarLifeControlStateMachine()
        sm.setPreferredResolution(800, 480)
        val autoStart = sm.buildAutoVideoEncoderInitAndStartFrames(800, 480, 30)
        assertEquals(4, autoStart.outboundFrames.size)
        assertEquals(
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            autoStart.outboundFrames[0].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_DONE,
            autoStart.outboundFrames[1].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_FOREGROUND,
            autoStart.outboundFrames[2].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(
            CarLifeMsgType.MSG_CMD_SCREEN_ON,
            autoStart.outboundFrames[3].parseCommandMessageOrNull()?.serviceType
        )
        assertEquals(CarLifeChannel.VIDEO, sm.createHeartbeatFrame().channel)
    }

    @Test
    fun jettaVs7LockedProfile_audioInitBytesAndAoA20BVideo_verifySpec() {
        val profiles = com.example.core.usb.BridgeForegroundService.AUTO_TEST_PROFILES
        assertEquals(1, profiles.size)
        assertEquals(800, profiles[0].width)
        assertEquals(480, profiles[0].height)
        assertEquals(1_200_000, profiles[0].bitrateBps)
        assertEquals(false, profiles[0].useNestedVideoHeader)
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
        // Inner 12B: len=8, ts=500, service=0x00028001
        assertEquals(8, wireBytes[11].toInt())
        assertEquals(0x00, wireBytes[16].toInt())
        assertEquals(0x02, wireBytes[17].toInt())
        assertEquals(0x80.toByte(), wireBytes[18])
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
        assertEquals(4, proactive.outboundFrames.size)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, proactive.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_DONE, proactive.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_FOREGROUND, proactive.outboundFrames[2].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_SCREEN_ON, proactive.outboundFrames[3].parseCommandMessageOrNull()?.serviceType)

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

        // Test 4: MATCH_STATUS + MD_INFO + Audio/Video Init WITHOUT FOREGROUND / SCREEN_ON
        val sm4 = CarLifeControlStateMachine(handshakeTestProfile = HandshakeTestProfile.TEST_4_AUDIO_VIDEO_NO_SCREEN)
        val res4 = sm4.onControlFrame(versionFrame)
        assertEquals(2, res4.outboundFrames.size)
        val initStartNoScreen = sm4.buildAutoVideoEncoderInitAndStartFrames(
            width = 800,
            height = 480,
            fps = 30,
            reason = "Тест 4",
            includeForegroundAndScreenOn = false
        )
        assertEquals(2, initStartNoScreen.outboundFrames.size)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE, initStartNoScreen.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_DONE, initStartNoScreen.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)

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
        // Verify exact constants per Jetta VS7 wire specification:
        // RX (HU -> MD): bit 0x8000 set
        assertEquals(0x00018001, CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION)
        assertEquals(0x00018003, CarLifeMsgType.MSG_CMD_HU_INFO)
        assertEquals(0x00018005, CarLifeMsgType.MSG_CMD_HU_BT_OOB_INFO)
        assertEquals(0x00018007, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT)
        assertEquals(0x00018009, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START)
        assertEquals(0x0001800B, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_PAUSE)
        assertEquals(0x0001800C, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_RESET)

        // TX (MD -> HU): bit 0x8000 cleared
        assertEquals(0x00010002, CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS)
        assertEquals(0x00010004, CarLifeMsgType.MSG_CMD_MD_INFO)
        assertEquals(0x00010006, CarLifeMsgType.MSG_CMD_MD_BT_OOB_INFO)
        assertEquals(0x00010008, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT_DONE)
        assertEquals(0x0001000A, CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_START_DONE)
        assertEquals(0x00010018, CarLifeMsgType.MSG_CMD_SCREEN_ON)
        assertEquals(0x00010025, CarLifeMsgType.MSG_CMD_FOREGROUND)

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
        assertEquals(0x0001000A, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_START_DONE)
        assertEquals(0x0001800B, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_PAUSE)
        assertEquals(0x0001800C, CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_RESET)
        assertEquals(0x00010018, CarLifeProtocolConstants.MSG_CMD_SCREEN_ON)
        assertEquals(0x00010025, CarLifeProtocolConstants.MSG_CMD_FOREGROUND)

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

        // Subsequent VIDEO_ENCODER_START (0x00018009) -> START_DONE (0x0001000A) + FOREGROUND (0x00010025) + SCREEN_ON (0x00010018)
        val videoStartFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = 0x00018009
        )
        val step4 = sm.onControlFrame(videoStartFrame)
        assertEquals(CarLifeSessionPhase.VIDEO_STREAMING, step4.updatedState.phase)
        assertEquals(3, step4.outboundFrames.size)
        assertEquals(0x0001000A, step4.outboundFrames[0].parseCommandMessageOrNull()?.serviceType)
        assertEquals(0x00010025, step4.outboundFrames[1].parseCommandMessageOrNull()?.serviceType)
        assertEquals(0x00010018, step4.outboundFrames[2].parseCommandMessageOrNull()?.serviceType)
    }
}


