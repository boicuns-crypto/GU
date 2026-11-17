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
    fun controlFrame_uses8ByteOuterAnd2ByteInnerLenHeader_andRoundTripsCommand() {
        val rawMatchPacket = CarLifeFrameCodec.encodeAoACmdPacket(
            serviceId = CarLifeMsgType.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
            protoBytes = byteArrayOf(0x08, 0x01)
        )
        // Verify exact 18-byte layout: [00 00 00 01][00 00 00 0A][00 02][00 00][00 01 80 02][08 01]
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x0A,
                0x00, 0x02,
                0x00, 0x00,
                0x00, 0x01, 0x80.toByte(), 0x02,
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

        // Verify AOA 20B (8B AOA + 12B 0x00028001 + Annex-B) is the default framing mode for 0x0002
        val defaultFrame = CarLifeFrameCodec.createVideoFrame(
            nalData = h264Nal,
            timestampMs = timestamp
        )
        assertEquals(8, CarLifeFrameCodec.encodeHeader(defaultFrame.header).size)
        assertEquals(12 + h264Nal.size, defaultFrame.payload.size)
        assertArrayEquals(
            CarLifeFrameCodec.encodeFrame(CarLifeFrameCodec.encodeAoAVideoFrame(h264Nal, timestamp)),
            CarLifeFrameCodec.encodeFrame(defaultFrame)
        )

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
        val sm = CarLifeControlStateMachine(force60FpsOnMqb = false)

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
        assertEquals(CarLifeSessionPhase.VIDEO_STREAMING, step2.updatedState.phase)
        assertEquals(800, step2.updatedState.negotiatedVideoWidth)
        assertEquals(480, step2.updatedState.negotiatedVideoHeight)
        assertEquals(30, step2.updatedState.negotiatedFps)
        assertEquals(4, step2.outboundFrames.size)

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
        // and bytes[12..15] == 0x00010007 (MSG_CMD_VIDEO_ENCODER_INIT)
        val raw16BytePacket = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // Channel 1
            0x00, 0x00, 0x00, 0x00, // Outer length = 0
            0x00, 0x00, 0x00, 0x00, // Inner protoLen = 0, reserved = 0
            0x00, 0x01, 0x00, 0x07  // serviceId = MSG_CMD_VIDEO_ENCODER_INIT (0x00010007)
        )
        assembler.append(raw16BytePacket, 0, raw16BytePacket.size)
        val frames = assembler.drainFrames()
        assertEquals(1, frames.size)
        assertEquals(8, frames[0].payload.size)
        val parsed = frames[0].parseCommandMessageOrNull()
        assertNotNull(parsed)
        assertEquals(CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT, parsed!!.serviceType)

        // Verify auto-start after MD_INFO builds INIT_DONE + START_DONE + FOREGROUND + SCREEN_ON with synced resolution
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
    }

    @Test
    fun jettaVs7LockedProfile_audioInitBytesAndAoA20BVideo_verifySpec() {
        val profiles = com.example.core.usb.BridgeForegroundService.AUTO_TEST_PROFILES
        assertEquals(1, profiles.size)
        assertEquals(800, profiles[0].width)
        assertEquals(480, profiles[0].height)
        assertEquals(800_000, profiles[0].bitrateBps)
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
        // Inner 12B: len=8, ts=500, service=0x00028001
        assertEquals(8, wireBytes[11].toInt())
        assertEquals(0x00, wireBytes[16].toInt())
        assertEquals(0x02, wireBytes[17].toInt())
        assertEquals(0x80.toByte(), wireBytes[18])
        assertEquals(0x01, wireBytes[19].toInt())
        // Annex-B start code at bytes [20..23]
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01), wireBytes.copyOfRange(20, 24))

        val sm = CarLifeControlStateMachine()
        val moduleStatusFrame = CarLifeFrameCodec.createCommandFrame(
            channel = CarLifeChannel.CONTROL,
            serviceType = CarLifeMsgType.MSG_CMD_MODULE_STATUS,
            commandBody = byteArrayOf(0x08, 0x01, 0x10, 0x01)
        )
        val transition = sm.onControlFrame(moduleStatusFrame)
        assertTrue(transition.summary.contains("MODULE_STATUS"))
    }
}

