package com.example.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.protocol.carlife.CarLifeChannel
import com.example.protocol.carlife.CarLifeFrame
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeStreamAssembler
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
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
 * Snapshot of the USB AOA Accessory metadata negotiated with the Jetta VS7 (MQB) Head Unit.
 */
data class UsbAccessoryInfo(
    val manufacturer: String,
    val model: String,
    val description: String,
    val version: String,
    val uri: String,
    val serial: String
) {
    companion object {
        fun from(accessory: UsbAccessory): UsbAccessoryInfo = UsbAccessoryInfo(
            manufacturer = accessory.manufacturer.orEmpty(),
            model = accessory.model.orEmpty(),
            description = accessory.description.orEmpty(),
            version = accessory.version.orEmpty(),
            uri = accessory.uri.orEmpty(),
            serial = runCatching { accessory.serial.orEmpty() }.getOrDefault("Restricted")
        )
    }
}

/**
 * Connection states of the USB Open Accessory transport layer.
 */
sealed interface UsbTransportState {
    data object Disconnected : UsbTransportState
    data class WaitingForPermission(val accessory: UsbAccessoryInfo) : UsbTransportState
    data class Connecting(val accessory: UsbAccessoryInfo) : UsbTransportState
    data class Connected(
        val accessory: UsbAccessoryInfo,
        val fdNumber: Int,
        val connectedAtEpochMs: Long
    ) : UsbTransportState
    data class Error(val reason: String, val recoverable: Boolean = true) : UsbTransportState
}

/**
 * High-speed full-duplex USB Open Accessory (AOA) transport for Baidu CarLife on
 * Samsung Galaxy S26 Ultra connected to a Jetta VS7 (MQB) Head Unit.
 *
 * Uses `FileInputStream.channel` / `FileOutputStream.channel` ([FileChannel]) backed by
 * pooled native direct [ByteBuffer]s (`ByteBuffer.allocateDirect`) via [CarLifeFrameCodec.readNextFrameDirect]
 * and [CarLifeFrameCodec.writeFrameDirect] to minimize GC pressure on the 60fps H.264 & Touch hot paths.
 */
class UsbAccessoryTransport(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
) {
    private val appContext = context.applicationContext
    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val lifecycleMutex = Mutex()
    private val isReceiverRegistered = AtomicBoolean(false)
    private val writerRunning = AtomicBoolean(false)

    private var parcelFd: ParcelFileDescriptor? = null
    private var fileInputStream: FileInputStream? = null
    private var fileOutputStream: FileOutputStream? = null
    private var inChannel: FileChannel? = null
    private var outChannel: FileChannel? = null
    private var activeAccessory: UsbAccessory? = null

    private var readJob: Job? = null
    private var writerThread: Thread? = null

    @Volatile
    private var outgoingQueue: LinkedBlockingQueue<CarLifeFrame> = LinkedBlockingQueue(WRITER_QUEUE_CAPACITY)

    private val _state = MutableStateFlow<UsbTransportState>(UsbTransportState.Disconnected)
    val state: StateFlow<UsbTransportState> = _state.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<CarLifeFrame>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val incomingFrames: SharedFlow<CarLifeFrame> = _incomingFrames.asSharedFlow()

    private val _rxBytes = AtomicLong(0L)
    private val _txBytes = AtomicLong(0L)
    private val _rxFrames = AtomicLong(0L)
    private val _txFrames = AtomicLong(0L)

    val rxBytes: Long get() = _rxBytes.get()
    val txBytes: Long get() = _txBytes.get()
    val rxFrames: Long get() = _rxFrames.get()
    val txFrames: Long get() = _txFrames.get()

    private val usbBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val accessory = extractAccessoryFromIntent(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && accessory != null) {
                        Log.i(TAG, "USB AOA permission granted for ${accessory.manufacturer}/${accessory.model}")
                        externalScope.launch { openAccessorySession(accessory) }
                    } else {
                        Log.w(TAG, "USB AOA permission denied by user")
                        _state.value = UsbTransportState.Error(
                            reason = "USB Accessory permission denied for Jetta VS7 / CarLife Head Unit",
                            recoverable = true
                        )
                    }
                }

                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    val detached = extractAccessoryFromIntent(intent)
                    if (detached == null || detached == activeAccessory) {
                        Log.i(TAG, "USB AOA accessory detached from Head Unit")
                        externalScope.launch { closeSession("USB cable / Head Unit disconnected") }
                    }
                }
            }
        }
    }

    init {
        registerUsbReceiverIfNeeded()
    }

    fun findCarLifeAccessory(): UsbAccessory? {
        val list = usbManager.accessoryList ?: return null
        return list.firstOrNull { isCarLifeCompatible(it) } ?: list.firstOrNull()
    }

    fun isCarLifeCompatible(accessory: UsbAccessory): Boolean {
        val manufacturer = accessory.manufacturer.orEmpty().trim()
        val model = accessory.model.orEmpty().trim()
        val matchesManufacturer = KNOWN_MANUFACTURERS.any { it.equals(manufacturer, ignoreCase = true) }
        val matchesModel = KNOWN_MODELS.any { it.equals(model, ignoreCase = true) }
        return matchesManufacturer || matchesModel
    }

    suspend fun connect(accessory: UsbAccessory? = findCarLifeAccessory()): Boolean {
        registerUsbReceiverIfNeeded()
        val target = accessory ?: run {
            _state.value = UsbTransportState.Error(
                reason = "No USB AOA Accessory detected on Type-C port (Connect Jetta VS7 Head Unit)",
                recoverable = true
            )
            return false
        }

        if (!usbManager.hasPermission(target)) {
            val info = UsbAccessoryInfo.from(target)
            _state.value = UsbTransportState.WaitingForPermission(info)
            requestAccessoryPermission(target)
            return false
        }

        return openAccessorySession(target)
    }

    suspend fun openAccessorySession(accessory: UsbAccessory): Boolean = lifecycleMutex.withLock {
        closeResourcesLocked()

        val info = UsbAccessoryInfo.from(accessory)
        _state.value = UsbTransportState.Connecting(info)

        val pfd = withContext(ioDispatcher) {
            runCatching { usbManager.openAccessory(accessory) }.getOrNull()
        }

        if (pfd == null) {
            val msg = "UsbManager.openAccessory() returned null for ${info.manufacturer}/${info.model}"
            Log.e(TAG, msg)
            _state.value = UsbTransportState.Error(reason = msg, recoverable = true)
            return false
        }

        val rawFd = pfd.fileDescriptor
        val fis = FileInputStream(rawFd)
        val fos = FileOutputStream(rawFd)

        parcelFd = pfd
        fileInputStream = fis
        fileOutputStream = fos
        inChannel = fis.channel
        outChannel = fos.channel
        activeAccessory = accessory

        val writeQueue = LinkedBlockingQueue<CarLifeFrame>(WRITER_QUEUE_CAPACITY)
        outgoingQueue = writeQueue
        writerRunning.set(true)

        val fdNum = runCatching { pfd.fd }.getOrDefault(-1)
        _state.value = UsbTransportState.Connected(
            accessory = info,
            fdNumber = fdNum,
            connectedAtEpochMs = System.currentTimeMillis()
        )
        Log.i(TAG, "Opened CarLife Direct-IO USB AOA session: ${info.manufacturer} ${info.model} (fd=$fdNum)")

        readJob = externalScope.launch(ioDispatcher) {
            runDirectReadLoop(fis)
        }
        writerThread = Thread({
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
            runDedicatedUsbWriterLoop(fos, writeQueue)
        }, "CarLifeUsbWriterThread").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /**
     * Enqueues a [CarLifeFrame] into the lock-free [LinkedBlockingQueue] without blocking
     * `MediaCodec` (`0x0002`) or `AudioRecord` (`0x0003`) threads on USB I/O.
     */
    fun enqueueFrameNonBlocking(frame: CarLifeFrame): Boolean {
        if (_state.value !is UsbTransportState.Connected || !writerRunning.get()) return false
        val queue = outgoingQueue
        if (queue.offer(frame)) return true
        // Queue congested: evict one stale queued item so codecs/audio never stall
        queue.poll()
        return queue.offer(frame)
    }

    suspend fun sendFrame(frame: CarLifeFrame): Boolean = enqueueFrameNonBlocking(frame)

    suspend fun sendFrameImmediate(frame: CarLifeFrame): Boolean = enqueueFrameNonBlocking(frame)

    /**
     * Writes any CarLife frame to `/dev/usb_accessory` (`FileOutputStream`) in strict
     * **16,384-byte (16 KB)** chunks on the dedicated high-priority [writerThread] so the Linux kernel
     * `f_accessory` driver never drops or truncates >16KB H.264 IDR keyframes.
     */
    @Throws(IOException::class)
    private fun writeFrameChunked16K(usbOutputStream: FileOutputStream, frame: CarLifeFrame) {
        val fullPacket = CarLifeFrameCodec.encodeFrame(frame)
        var offset = 0
        while (offset < fullPacket.size) {
            val chunk = minOf(MAX_AOA_WRITE_SIZE, fullPacket.size - offset)
            usbOutputStream.write(fullPacket, offset, chunk)
            offset += chunk
        }
    }

    /**
     * Ring-buffer stream read loop over [FileInputStream]:
     * Reads up to [MAX_AOA_WRITE_SIZE] (16,384 bytes) per `fis.read()` syscall and reassembles
     * coalesced or fragmented packets in [CarLifeStreamAssembler] strictly by `8 + payloadLen`
     * (Control `0x0001` / Touch `0x0006`) and `12 + payloadLen` (Video/Audio `0x0002..0x0005`).
     */
    private suspend fun CoroutineScope.runDirectReadLoop(fis: FileInputStream) {
        val readBuffer = ByteArray(MAX_AOA_WRITE_SIZE)
        val streamAssembler = CarLifeStreamAssembler(initialCapacity = 65_536)

        try {
            while (isActive) {
                val bytesRead = fis.read(readBuffer, 0, MAX_AOA_WRITE_SIZE)
                if (bytesRead < 0) {
                    throw EOFException("USB AOA input stream reached EOF")
                }
                if (bytesRead == 0) continue

                val rawSnapshot = readBuffer.copyOf(bytesRead)
                val rawCh = if (bytesRead >= 8) {
                    ByteBuffer.wrap(rawSnapshot, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                } else {
                    0
                }
                val rawLen = if (bytesRead >= 8) {
                    ByteBuffer.wrap(rawSnapshot, 4, 4).order(ByteOrder.BIG_ENDIAN).int
                } else {
                    0
                }
                val hasZeroInnerLenPrefix = bytesRead > 8 &&
                    bytesRead >= 12 &&
                    rawSnapshot[8] == 0.toByte() &&
                    rawSnapshot[9] == 0.toByte() &&
                    rawSnapshot[10] == 0.toByte() &&
                    rawSnapshot[11] == 0.toByte()

                // Fast-path guard against "Payload: 0B" on >8-byte reads where HU sends a no-Protobuf command:
                // bytes[0..3]=ch, bytes[4..7]=0, bytes[8..11]=[00 00 00 00], bytes[12..15]=serviceId
                if (bytesRead > 8 && rawCh in 1..6 && rawLen == 0 && hasZeroInnerLenPrefix && bytesRead >= 16) {
                    streamAssembler.reset()
                    val resolvedChannel = com.example.protocol.carlife.CarLifeChannel.fromId(rawCh)
                        ?: com.example.protocol.carlife.CarLifeChannel.CONTROL
                    val extractedPayload = rawSnapshot.copyOfRange(8, rawSnapshot.size)
                    val noProtoFrame = CarLifeFrame(
                        header = com.example.protocol.carlife.CarLifeHeader(
                            channel = resolvedChannel,
                            rawChannelId = rawCh,
                            payloadLength = extractedPayload.size,
                            timestamp = 0L,
                            includeOuterTimestamp = false
                        ),
                        payload = extractedPayload
                    )
                    _rxFrames.incrementAndGet()
                    _rxBytes.addAndGet(noProtoFrame.totalFrameBytes.toLong())
                    _incomingFrames.emit(noProtoFrame)
                    continue
                }

                streamAssembler.append(readBuffer, 0, bytesRead)
                val frames = streamAssembler.drainFrames()

                if (frames.isEmpty() && bytesRead > 8) {
                    // Fallback if HU sent a raw packet >8B that didn't match standard outer length (e.g. rawLen == 0 or non-standard header)
                    if (rawCh !in 1..6 || rawLen == 0 || hasZeroInnerLenPrefix) {
                        val rawHex = rawSnapshot.take(64).joinToString(" ") { "%02X".format(it) }
                        Log.w(TAG, "USB AOA raw read ($bytesRead B, ch=$rawCh, len=$rawLen): $rawHex")
                        streamAssembler.reset()
                        val fallbackPayload = if (rawCh in 1..6) {
                            rawSnapshot.copyOfRange(8, rawSnapshot.size)
                        } else {
                            rawSnapshot
                        }
                        val fallbackFrame = CarLifeFrame(
                            header = com.example.protocol.carlife.CarLifeHeader(
                                channel = com.example.protocol.carlife.CarLifeChannel.fromId(rawCh)
                                    ?: com.example.protocol.carlife.CarLifeChannel.CONTROL,
                                rawChannelId = if (rawCh in 1..6) rawCh else 1,
                                payloadLength = fallbackPayload.size,
                                timestamp = 0L,
                                includeOuterTimestamp = false
                            ),
                            payload = fallbackPayload
                        )
                        _rxFrames.incrementAndGet()
                        _rxBytes.addAndGet(bytesRead.toLong())
                        _incomingFrames.emit(fallbackFrame)
                        continue
                    }
                } else if (frames.isEmpty() && bytesRead in 4..8 && rawCh !in 1..6) {
                    // Recover split USB AOA transfer (4..8B) carrying [00 00 00 00][serviceId] or [serviceId]
                    val word0 = ByteBuffer.wrap(rawSnapshot, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                    val word4 = if (bytesRead == 8) {
                        ByteBuffer.wrap(rawSnapshot, 4, 4).order(ByteOrder.BIG_ENDIAN).int
                    } else {
                        0
                    }
                    val hasServiceAt4 = bytesRead == 8 && com.example.protocol.carlife.CarLifeMsgType.isCarLifeServiceId(word4)
                    val hasServiceAt0 = com.example.protocol.carlife.CarLifeMsgType.isCarLifeServiceId(word0)
                    if (hasServiceAt4 || hasServiceAt0) {
                        val rawHex = rawSnapshot.joinToString(" ") { "%02X".format(it) }
                        Log.w(TAG, "USB AOA split tail read ($bytesRead B): $rawHex")
                        streamAssembler.reset()
                        val recoveredFrame = CarLifeFrame(
                            header = com.example.protocol.carlife.CarLifeHeader(
                                channel = com.example.protocol.carlife.CarLifeChannel.CONTROL,
                                rawChannelId = 1,
                                payloadLength = rawSnapshot.size,
                                timestamp = 0L,
                                includeOuterTimestamp = false
                            ),
                            payload = rawSnapshot
                        )
                        _rxFrames.incrementAndGet()
                        _rxBytes.addAndGet(bytesRead.toLong())
                        _incomingFrames.emit(recoveredFrame)
                        continue
                    }
                }

                for (frame in frames) {
                    val effectiveFrame = if (frame.payload.isEmpty() && bytesRead > 8) {
                        val rawHex = rawSnapshot.take(64).joinToString(" ") { "%02X".format(it) }
                        Log.w(
                            TAG,
                            "USB AOA RX frame with payloadLen=0 (raw bytesRead=$bytesRead B): [$rawHex]"
                        )
                        val extractedPayload = if (rawCh in 1..6) {
                            rawSnapshot.copyOfRange(8, rawSnapshot.size)
                        } else {
                            rawSnapshot
                        }
                        frame.copy(
                            header = frame.header.copy(payloadLength = extractedPayload.size),
                            payload = extractedPayload
                        )
                    } else {
                        frame
                    }
                    _rxFrames.incrementAndGet()
                    _rxBytes.addAndGet(effectiveFrame.totalFrameBytes.toLong())
                    _incomingFrames.emit(effectiveFrame)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ioe: IOException) {
            Log.w(TAG, "USB AOA bulk IN stream terminated: ${ioe.message}")
            if (isActive) {
                externalScope.launch {
                    closeSession("Head Unit stream closed (${ioe.javaClass.simpleName}: ${ioe.message})")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error in USB AOA read loop", t)
            if (isActive) {
                externalScope.launch {
                    closeSession("Fatal transport error: ${t.message}")
                }
            }
        }
    }

    /**
     * Dedicated high-priority writer loop draining [LinkedBlockingQueue] sequentially into [FileOutputStream]
     * in 16,384-byte chunks without blocking `MediaCodec` or `AudioRecord`.
     */
    private fun runDedicatedUsbWriterLoop(
        fos: FileOutputStream,
        queue: LinkedBlockingQueue<CarLifeFrame>
    ) {
        try {
            while (writerRunning.get() && !Thread.currentThread().isInterrupted) {
                val frame = queue.take()
                if (!writerRunning.get()) break
                writeFrameChunked16K(fos, frame)
                _txFrames.incrementAndGet()
                _txBytes.addAndGet(frame.totalFrameBytes.toLong())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ioe: IOException) {
            Log.w(TAG, "USB AOA bulk OUT stream terminated: ${ioe.message}")
            if (writerRunning.get()) {
                externalScope.launch {
                    closeSession("USB bulk OUT write error: ${ioe.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error in USB writer thread: ${t.message}", t)
            if (writerRunning.get()) {
                externalScope.launch {
                    closeSession("USB writer error: ${t.message}")
                }
            }
        }
    }

    suspend fun closeSession(reason: String = "Session closed by user") {
        lifecycleMutex.withLock {
            closeResourcesLocked()
            _state.value = UsbTransportState.Disconnected
            Log.i(TAG, "USB AOA session closed: $reason")
        }
    }

    suspend fun shutdown() {
        closeSession("Service shutdown")
        if (isReceiverRegistered.compareAndSet(true, false)) {
            runCatching { appContext.unregisterReceiver(usbBroadcastReceiver) }
        }
    }

    private fun closeResourcesLocked() {
        writerRunning.set(false)
        readJob?.cancel()
        readJob = null

        writerThread?.interrupt()
        writerThread = null
        outgoingQueue.clear()

        runCatching { inChannel?.close() }
        runCatching { outChannel?.close() }
        runCatching { fileInputStream?.close() }
        runCatching { fileOutputStream?.close() }
        runCatching { parcelFd?.close() }

        inChannel = null
        outChannel = null
        fileInputStream = null
        fileOutputStream = null
        parcelFd = null
        activeAccessory = null
    }

    private fun requestAccessoryPermission(accessory: UsbAccessory) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(appContext.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            permissionIntent,
            flags
        )
        usbManager.requestPermission(accessory, pendingIntent)
    }

    private fun registerUsbReceiverIfNeeded() {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
            ContextCompat.registerReceiver(
                appContext,
                usbBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun extractAccessoryFromIntent(intent: Intent): UsbAccessory? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
    }

    companion object {
        private const val TAG = "UsbAccessoryTransport"
        const val ACTION_USB_PERMISSION = "com.example.core.usb.ACTION_USB_PERMISSION"
        const val MAX_AOA_CHUNK = 16_384
        const val MAX_AOA_WRITE_SIZE = MAX_AOA_CHUNK
        const val WRITER_QUEUE_CAPACITY = 512

        val KNOWN_MANUFACTURERS = setOf(
            "Baidu",
            "Baidu_CarLife",
            "Baidu Inc.",
            "Baidu CarLife",
            "Analysis",
            "Google, Inc."
        )

        val KNOWN_MODELS = setOf(
            "CarLife",
            "CarLife_Module"
        )
    }
}
