package com.example.core.carlife

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Callback listener interface allowing [CockpitProjectionManager] to react to
 * Jetta VS7 (MQB) Head Unit lifecycle events and incoming touch / steering wheel packets.
 */
interface CarLifeEngineCallbacks {
    fun onVideoSizeNegotiated(width: Int, height: Int, frameRate: Int)
    fun onVideoStartRequested()
    fun onVideoPauseRequested()
    fun onVideoResetRequested()
    fun onTouchPacketReceived(packet: CarLifeTouchPacket)
    fun onHardKeyPacketReceived(packet: CarLifeHardKeyPacket)
}

/**
 * High-performance USB AOA Transport & Baidu CarLife Multiplexer Engine (`:core:carlife`).
 *
 * - Captures [UsbAccessory] on Samsung Galaxy S26 Ultra (`UsbManager.openAccessory`).
 * - Performs non-blocking / dedicated-thread `FileChannel` I/O using direct `ByteBuffer`
 *   in `ByteOrder.BIG_ENDIAN`.
 * - Automatically completes the Jetta VS7 (MQB) Control Channel Handshake:
 *   1. `MSG_CMD_HU_PROTOCOL_VERSION (0x00018001)` -> `MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS (0x00010002)` + `MSG_CMD_MD_INFO (0x00010004)`
 *   2. `MSG_CMD_HU_INFO (0x00018003)` -> `MSG_CMD_MD_INFO (0x00010004)`
 *   3. `MSG_CMD_VIDEO_ENCODER_INIT (0x00018007)` -> `MSG_CMD_VIDEO_ENCODER_INIT_DONE (0x00010008)`
 *   4. `MSG_CMD_VIDEO_ENCODER_START (0x00018009)` -> starts H.264 NAL streaming
 * - Runs a periodic 1000 ms Heartbeat coroutine (`MSG_VIDEO_HEARTBEAT = 0x00020002`) so the
 *   Head Unit never drops the USB link.
 */
class CarLifeUsbEngine(
    context: Context,
    private val callbacks: CarLifeEngineCallbacks? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val sessionMutex = Mutex()
    private val writeMutex = Mutex()
    private val receiverRegistered = AtomicBoolean(false)

    private var parcelFd: ParcelFileDescriptor? = null
    private var fileIn: FileInputStream? = null
    private var fileOut: FileOutputStream? = null
    private var inChannel: FileChannel? = null
    private var outChannel: FileChannel? = null
    private var activeAccessory: UsbAccessory? = null

    private var readLoopJob: Job? = null
    private var writeLoopJob: Job? = null
    private var heartbeatJob: Job? = null

    private var outgoingVideoQueue = Channel<CarLifeRawPacket>(
        capacity = 180,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _negotiatedWidth = MutableStateFlow(CarLifeProtocolConstants.DEFAULT_PROJECTION_WIDTH)
    val negotiatedWidth: StateFlow<Int> = _negotiatedWidth.asStateFlow()

    private val _negotiatedHeight = MutableStateFlow(CarLifeProtocolConstants.DEFAULT_PROJECTION_HEIGHT)
    val negotiatedHeight: StateFlow<Int> = _negotiatedHeight.asStateFlow()

    private val _negotiatedFps = MutableStateFlow(CarLifeProtocolConstants.DEFAULT_PROJECTION_FPS)
    val negotiatedFps: StateFlow<Int> = _negotiatedFps.asStateFlow()

    private val _touchEvents = MutableSharedFlow<CarLifeTouchPacket>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val touchEvents: SharedFlow<CarLifeTouchPacket> = _touchEvents.asSharedFlow()

    private val _hardKeyEvents = MutableSharedFlow<CarLifeHardKeyPacket>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val hardKeyEvents: SharedFlow<CarLifeHardKeyPacket> = _hardKeyEvents.asSharedFlow()

    val rxPacketCount = AtomicLong(0L)
    val txPacketCount = AtomicLong(0L)
    val rxByteCount = AtomicLong(0L)
    val txByteCount = AtomicLong(0L)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val accessory = extractAccessory(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && accessory != null) {
                        engineScope.launch { openAccessory(accessory) }
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    val detached = extractAccessory(intent)
                    if (detached == null || detached == activeAccessory) {
                        engineScope.launch { closeSession() }
                    }
                }
            }
        }
    }

    init {
        registerReceiverIfNeeded()
    }

    /**
     * Scans `UsbManager.accessoryList` and attaches to the Jetta VS7 Baidu CarLife accessory.
     */
    suspend fun attachOrScan(explicitAccessory: UsbAccessory? = null): Boolean {
        registerReceiverIfNeeded()
        val target = explicitAccessory ?: usbManager.accessoryList?.firstOrNull() ?: return false
        if (!usbManager.hasPermission(target)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                flags
            )
            usbManager.requestPermission(target, pi)
            return false
        }
        return openAccessory(target)
    }

    /**
     * Opens the USB AOA [ParcelFileDescriptor] and starts the full-duplex reader, writer,
     * and Heartbeat coroutines on [Dispatchers.IO].
     */
    suspend fun openAccessory(accessory: UsbAccessory): Boolean = sessionMutex.withLock {
        closeInternalLocked()

        val pfd = withContext(ioDispatcher) {
            runCatching { usbManager.openAccessory(accessory) }.getOrNull()
        } ?: return false

        val fis = FileInputStream(pfd.fileDescriptor)
        val fos = FileOutputStream(pfd.fileDescriptor)

        parcelFd = pfd
        fileIn = fis
        fileOut = fos
        inChannel = fis.channel
        outChannel = fos.channel
        activeAccessory = accessory

        outgoingVideoQueue = Channel(
            capacity = 180,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        _isConnected.value = true
        Log.i(TAG, "CarLifeUsbEngine attached to ${accessory.manufacturer}/${accessory.model}")

        readLoopJob = engineScope.launch(ioDispatcher) {
            runPacketReaderLoop(fis.channel)
        }
        writeLoopJob = engineScope.launch(ioDispatcher) {
            runVideoWriterLoop(fos.channel)
        }
        heartbeatJob = engineScope.launch(ioDispatcher) {
            runHeartbeatLoop()
        }
        return true
    }

    /**
     * Enqueues an H.264 Annex-B NAL unit (`SPS/PPS` or `IDR/P-Frame`) for transmission on
     * `CHANNEL_VIDEO (0x0002)` with a 12-byte Big-Endian header.
     */
    fun enqueueVideoNalUnit(nalBytes: ByteArray, timestampMs: Long = System.currentTimeMillis()) {
        if (!_isConnected.value) return
        val packet = CarLifePacketBuilder.buildVideoNalPacket(
            nalBytes = nalBytes,
            timestampMs = timestampMs
        )
        outgoingVideoQueue.trySend(packet)
    }

    /**
     * Immediately writes a high-priority Control (`0x0001`) or SPS/PPS configuration packet
     * to the USB AOA bulk OUT `FileChannel`.
     */
    suspend fun sendPacketImmediate(packet: CarLifeRawPacket): Boolean = withContext(ioDispatcher) {
        val channel = outChannel ?: return@withContext false
        writeMutex.withLock {
            try {
                writePacketToFileChannel(channel, packet)
                txPacketCount.incrementAndGet()
                txByteCount.addAndGet((packet.headerSizeBytes + packet.payload.size).toLong())
                true
            } catch (e: IOException) {
                Log.e(TAG, "USB AOA write error", e)
                engineScope.launch { closeSession() }
                false
            }
        }
    }

    /**
     * Binary packet reader loop over the USB AOA bulk IN [FileChannel].
     *
     * Reads:
     * - First 8 bytes (`[ChannelID: 4B Big-Endian][PayloadLength: 4B Big-Endian]`).
     * - If `isMediaTimestampChannel(channelId)` is `true` (`0x0002`..`0x0005`), reads an additional
     *   4 bytes (`[Timestamp: 4B Big-Endian]`) to complete the 12-byte media header.
     * - Reads `payloadLength` bytes of payload and dispatches to the channel handler.
     */
    private suspend fun CoroutineScope.runPacketReaderLoop(channel: FileChannel) {
        val headerBuffer = ByteBuffer.allocateDirect(CarLifeProtocolConstants.HEADER_SIZE_MEDIA_STREAM)
            .order(ByteOrder.BIG_ENDIAN)

        try {
            while (isActive) {
                headerBuffer.clear()
                headerBuffer.limit(CarLifeProtocolConstants.HEADER_SIZE_CONTROL_TOUCH)
                readFullyFromChannel(channel, headerBuffer)
                headerBuffer.flip()

                val rawChannelId = headerBuffer.int
                val payloadLength = headerBuffer.int

                if (payloadLength < 0 || payloadLength > CarLifeProtocolConstants.MAX_PACKET_PAYLOAD_BYTES) {
                    throw IOException("Invalid CarLife payloadLength=$payloadLength on channel=$rawChannelId")
                }

                val timestampMs = if (CarLifeProtocolConstants.isMediaTimestampChannel(rawChannelId)) {
                    headerBuffer.clear()
                    headerBuffer.limit(4)
                    readFullyFromChannel(channel, headerBuffer)
                    headerBuffer.flip()
                    headerBuffer.int.toLong() and 0xFFFF_FFFFL
                } else {
                    0L
                }

                val payload = if (payloadLength > 0) {
                    val payloadBuffer = ByteBuffer.allocate(payloadLength)
                    readFullyFromChannel(channel, payloadBuffer)
                    payloadBuffer.array()
                } else {
                    ByteArray(0)
                }

                val packet = CarLifeRawPacket(
                    channelId = rawChannelId and 0xFFFF,
                    payloadLength = payloadLength,
                    timestampMs = timestampMs,
                    payload = payload
                )

                rxPacketCount.incrementAndGet()
                rxByteCount.addAndGet((packet.headerSizeBytes + payloadLength).toLong())

                demultiplexIncomingPacket(packet)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            Log.w(TAG, "USB AOA bulk IN loop ended: ${e.message}")
            if (isActive) {
                engineScope.launch { closeSession() }
            }
        }
    }

    /**
     * Dedicated writer loop draining H.264 NAL packets from [outgoingVideoQueue] to the USB AOA bulk OUT channel.
     */
    private suspend fun CoroutineScope.runVideoWriterLoop(channel: FileChannel) {
        try {
            for (packet in outgoingVideoQueue) {
                if (!isActive) break
                writeMutex.withLock {
                    writePacketToFileChannel(channel, packet)
                }
                txPacketCount.incrementAndGet()
                txByteCount.addAndGet((packet.headerSizeBytes + packet.payload.size).toLong())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            Log.w(TAG, "USB AOA video writer loop ended: ${e.message}")
            if (isActive) {
                engineScope.launch { closeSession() }
            }
        }
    }

    /**
     * Periodic Heartbeat coroutine sending `MSG_VIDEO_HEARTBEAT (0x00020002)` every 1000 ms
     * so the Jetta VS7 Head Unit never times out the session.
     */
    private suspend fun CoroutineScope.runHeartbeatLoop() {
        while (isActive && _isConnected.value) {
            delay(1_000L)
            if (_isConnected.value) {
                sendPacketImmediate(CarLifePacketBuilder.buildHeartbeatPacket())
            }
        }
    }

    /**
     * Routes incoming packets across Channel 1 (Control) and Channel 6 (Touch & Hardkeys).
     */
    internal suspend fun demultiplexIncomingPacket(packet: CarLifeRawPacket) {
        when (packet.channelId) {
            CarLifeProtocolConstants.CHANNEL_CONTROL -> {
                val (msgType, body) = packet.extractInnerCommandOrNull() ?: return
                handleControlCommand(msgType, body)
            }

            CarLifeProtocolConstants.CHANNEL_TOUCH -> {
                val (msgType, body) = packet.extractInnerCommandOrNull() ?: return
                val parsed = CarLifePacketBuilder.parseTouchOrKeyPayload(
                    messageType = msgType,
                    body = body,
                    defaultHuWidth = _negotiatedWidth.value,
                    defaultHuHeight = _negotiatedHeight.value
                )
                when (parsed) {
                    is CarLifeTouchPacket -> {
                        _touchEvents.tryEmit(parsed)
                        callbacks?.onTouchPacketReceived(parsed)
                    }
                    is CarLifeHardKeyPacket -> {
                        _hardKeyEvents.tryEmit(parsed)
                        callbacks?.onHardKeyPacketReceived(parsed)
                    }
                }
            }
        }
    }

    private suspend fun handleControlCommand(messageType: Int, body: ByteArray) {
        when (messageType) {
            CarLifeProtocolConstants.MSG_CMD_HU_PROTOCOL_VERSION -> {
                // 1. Reply with Version Match Status (1 = MATCH) and Mobile Device Info
                sendPacketImmediate(CarLifePacketBuilder.buildProtocolVersionMatchPacket())
                sendPacketImmediate(CarLifePacketBuilder.buildMobileDeviceInfoPacket())
            }

            CarLifeProtocolConstants.MSG_CMD_HU_INFO -> {
                sendPacketImmediate(CarLifePacketBuilder.buildMobileDeviceInfoPacket())
            }

            CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_INIT -> {
                val (reqW, reqH, _) = CarLifePacketBuilder.parseVideoEncoderInitRequest(body)
                val targetFps = 60
                _negotiatedWidth.value = reqW
                _negotiatedHeight.value = reqH
                _negotiatedFps.value = targetFps

                callbacks?.onVideoSizeNegotiated(reqW, reqH, targetFps)
                sendPacketImmediate(
                    CarLifePacketBuilder.buildVideoEncoderInitDonePacket(
                        width = reqW,
                        height = reqH,
                        frameRate = targetFps
                    )
                )
            }

            CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_START -> {
                callbacks?.onVideoStartRequested()
            }

            CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_PAUSE -> {
                callbacks?.onVideoPauseRequested()
            }

            CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_RESET -> {
                callbacks?.onVideoResetRequested()
                sendPacketImmediate(
                    CarLifePacketBuilder.buildVideoEncoderInitDonePacket(
                        width = _negotiatedWidth.value,
                        height = _negotiatedHeight.value,
                        frameRate = _negotiatedFps.value
                    )
                )
            }

            CarLifeProtocolConstants.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                sendPacketImmediate(
                    CarLifePacketBuilder.buildVideoFrameRateChangeDonePacket(_negotiatedFps.value)
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun writePacketToFileChannel(channel: FileChannel, packet: CarLifeRawPacket) {
        val headerSize = packet.headerSizeBytes
        val totalSize = headerSize + packet.payload.size
        val buffer = ByteBuffer.allocateDirect(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(packet.channelId)
        buffer.putInt(packet.payload.size)
        if (packet.isMediaChannel) {
            buffer.putInt((packet.timestampMs and 0xFFFF_FFFFL).toInt())
        }
        buffer.put(packet.payload)
        buffer.flip()

        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written < 0) {
                throw EOFException("USB AOA FileChannel closed during write")
            }
        }
    }

    @Throws(IOException::class)
    private fun readFullyFromChannel(channel: FileChannel, dst: ByteBuffer) {
        while (dst.hasRemaining()) {
            val read = channel.read(dst)
            if (read < 0) {
                throw EOFException("USB AOA FileChannel closed during read")
            }
        }
    }

    suspend fun closeSession() = sessionMutex.withLock {
        closeInternalLocked()
        _isConnected.value = false
    }

    suspend fun shutdown() {
        closeSession()
        if (receiverRegistered.compareAndSet(true, false)) {
            runCatching { appContext.unregisterReceiver(usbReceiver) }
        }
    }

    private fun closeInternalLocked() {
        readLoopJob?.cancel()
        writeLoopJob?.cancel()
        heartbeatJob?.cancel()
        readLoopJob = null
        writeLoopJob = null
        heartbeatJob = null

        outgoingVideoQueue.close()

        runCatching { inChannel?.close() }
        runCatching { outChannel?.close() }
        runCatching { fileIn?.close() }
        runCatching { fileOut?.close() }
        runCatching { parcelFd?.close() }

        inChannel = null
        outChannel = null
        fileIn = null
        fileOut = null
        parcelFd = null
        activeAccessory = null
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
            ContextCompat.registerReceiver(
                appContext,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun extractAccessory(intent: Intent): UsbAccessory? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
    }

    companion object {
        private const val TAG = "CarLifeUsbEngine"
        const val ACTION_USB_PERMISSION = "com.example.core.carlife.ACTION_USB_PERMISSION"
    }
}
