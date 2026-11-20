package com.example.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.protocol.carlife.CarLifeFrame
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarlifeMusicInitProto
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Live telemetry for USB AOA Channel `0x0003` (`AudioPlaybackCapture` -> PCM 48kHz Stereo 16-bit).
 */
data class AudioCaptureStats(
    val isCapturing: Boolean = false,
    val hasRecordAudioPermission: Boolean = false,
    val mediaInitSent: Boolean = false,
    val sentInitPackets: Long = 0L,
    val sentPcmPackets: Long = 0L,
    val skippedSilentPackets: Long = 0L,
    val sentPcmBytes: Long = 0L,
    val sentStopPackets: Long = 0L,
    val statusMessage: String = "Аудио 0x0003: ожидание MediaProjection и разрешения RECORD_AUDIO (48 кГц Stereo 16-bit PCM)"
)

/**
 * Captures system playback audio (Yandex Music, Navigator, 2GIS, YouTube, etc.) via
 * Android 10+ `AudioPlaybackCapture` and streams raw 48kHz Stereo 16-bit PCM chunks (4096 bytes)
 * over Baidu CarLife USB AOA Channel `0x0003` in strict AOA 20B Big-Endian format:
 * - On start: `MSG_MEDIA_INIT (0x00030001)` with `CarlifeMusicInit(48000, 2, 16)`
 * - Streaming: `MSG_MEDIA_DATA (0x00030006)` with 4096-byte PCM chunks (~21 ms), suppressing pure silence
 * - On stop: `MSG_MEDIA_STOP (0x00030002)` with empty payload and clean `AudioRecord` release
 *
 * Phone calls (HFP) remain strictly on the car's native Bluetooth (`USAGE_VOICE_COMMUNICATION` is NOT captured).
 */
class CarLifeAudioCaptureManager(
    context: Context,
    private val onSendAudioFrame: suspend (frame: CarLifeFrame, isControlMsg: Boolean) -> Boolean,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context.applicationContext
    private val captureMutex = Mutex()
    private val isCapturingFlag = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    private val _stats = MutableStateFlow(
        AudioCaptureStats(hasRecordAudioPermission = hasRecordAudioPermission(appContext))
    )
    val stats: StateFlow<AudioCaptureStats> = _stats.asStateFlow()

    val isCapturing: Boolean
        get() = isCapturingFlag.get()

    fun refreshPermissionState(): Boolean {
        val granted = hasRecordAudioPermission(appContext)
        _stats.update { current ->
            current.copy(
                hasRecordAudioPermission = granted,
                statusMessage = when {
                    current.isCapturing -> current.statusMessage
                    granted -> "Аудио 0x0003 готово (RECORD_AUDIO разрешён • 48 кГц Stereo 16-bit PCM)"
                    else -> "Требуется разрешение RECORD_AUDIO для захвата звука в канал 0x0003"
                }
            )
        }
        return granted
    }

    /**
     * Sends `MSG_MEDIA_INIT (0x00030001)` (`sampleRate=48000, channelConfig=2, sampleFormat=16`)
     * in AOA 20B format even if `AudioRecord` is not yet capturing, so the Head Unit initializes its PCM sink.
     */
    suspend fun sendMediaInitPacket(): Boolean {
        val initFrame = CarLifeFrameCodec.createMediaInitFrame(
            sampleRate = SAMPLE_RATE_HZ,
            channelConfig = CHANNEL_COUNT,
            sampleFormat = BITS_PER_SAMPLE,
            timestampSec = currentTimestampSec()
        )
        val sent = onSendAudioFrame(initFrame, true)
        if (sent) {
            _stats.update { current ->
                current.copy(
                    mediaInitSent = true,
                    sentInitPackets = current.sentInitPackets + 1L
                )
            }
        }
        return sent
    }

    /**
     * Starts `AudioPlaybackCapture` using the active [mediaProjection], immediately transmits
     * `MSG_MEDIA_INIT (0x00030001)`, and streams 4096-byte PCM chunks via `MSG_MEDIA_DATA (0x00030006)`.
     */
    @SuppressLint("MissingPermission")
    suspend fun startCapture(mediaProjection: MediaProjection): Boolean = captureMutex.withLock {
        val hasPerm = hasRecordAudioPermission(appContext)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _stats.update {
                it.copy(
                    isCapturing = false,
                    hasRecordAudioPermission = hasPerm,
                    statusMessage = "AudioPlaybackCapture требует Android 10+"
                )
            }
            return@withLock false
        }

        if (!hasPerm) {
            _stats.update {
                it.copy(
                    isCapturing = false,
                    hasRecordAudioPermission = false,
                    statusMessage = "Нет разрешения RECORD_AUDIO — разрешите доступ к захвату системного звука"
                )
            }
            return@withLock false
        }

        if (isCapturingFlag.get() && captureJob?.isActive == true && audioRecord != null) {
            return@withLock true
        }

        // Clean up any stale instance before recreating
        stopCaptureLocked(sendMediaStop = false)

        val initSent = sendMediaInitPacket()

        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build AudioPlaybackCaptureConfiguration: ${t.message}", t)
            _stats.update {
                it.copy(
                    isCapturing = false,
                    hasRecordAudioPermission = true,
                    statusMessage = "Ошибка AudioPlaybackCapture: ${t.message}"
                )
            }
            return@withLock false
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(PCM_CHUNK_SIZE_BYTES)
        val bufferSizeBytes = maxOf(minBufSize * 2, PCM_CHUNK_SIZE_BYTES * 4)

        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.Builder() failed: ${t.message}", t)
            _stats.update {
                it.copy(
                    isCapturing = false,
                    hasRecordAudioPermission = true,
                    statusMessage = "Ошибка создания AudioRecord: ${t.message}"
                )
            }
            return@withLock false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${recorder.state})")
            runCatching { recorder.release() }
            _stats.update {
                it.copy(
                    isCapturing = false,
                    hasRecordAudioPermission = true,
                    statusMessage = "AudioRecord не инициализирован (state=${recorder.state})"
                )
            }
            return@withLock false
        }

        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.startRecording() failed: ${t.message}", t)
            runCatching { recorder.release() }
            _stats.update {
                it.copy(
                    isCapturing = false,
                    hasRecordAudioPermission = true,
                    statusMessage = "Ошибка старта записи PCM: ${t.message}"
                )
            }
            return@withLock false
        }

        audioRecord = recorder
        isCapturingFlag.set(true)
        _stats.update { current ->
            current.copy(
                isCapturing = true,
                hasRecordAudioPermission = true,
                mediaInitSent = current.mediaInitSent || initSent,
                statusMessage = "Аудио 0x0003 активно: 48 кГц Stereo 16-bit PCM (4096B чанки, звонки HFP на BT)"
            )
        }

        captureJob = externalScope.launch(Dispatchers.IO) {
            val readBuffer = ByteArray(PCM_CHUNK_SIZE_BYTES)
            var sentSinceUiUpdate = 0
            var skippedSinceUiUpdate = 0
            var bytesSinceUiUpdate = 0L

            try {
                while (isActive && isCapturingFlag.get()) {
                    var offset = 0
                    var readError = false
                    while (offset < PCM_CHUNK_SIZE_BYTES && isActive && isCapturingFlag.get()) {
                        val read = recorder.read(
                            readBuffer,
                            offset,
                            PCM_CHUNK_SIZE_BYTES - offset,
                            AudioRecord.READ_BLOCKING
                        )
                        if (read > 0) {
                            offset += read
                        } else if (read < 0) {
                            Log.w(TAG, "AudioRecord.read returned error code $read")
                            readError = true
                            break
                        } else {
                            break
                        }
                    }

                    if (readError) break
                    if (offset <= 0) continue

                    // Suppress pure silence (all-zero buffer) so USB AOA channel isn't saturated when nothing plays
                    if (isBufferSilent(readBuffer, offset)) {
                        skippedSinceUiUpdate++
                        if (skippedSinceUiUpdate >= 25) {
                            val deltaSkipped = skippedSinceUiUpdate.toLong()
                            skippedSinceUiUpdate = 0
                            _stats.update { it.copy(skippedSilentPackets = it.skippedSilentPackets + deltaSkipped) }
                        }
                        continue
                    }

                    val pcmPayload = if (offset == PCM_CHUNK_SIZE_BYTES) {
                        readBuffer.copyOf()
                    } else {
                        readBuffer.copyOf(offset)
                    }

                    val dataFrame = CarLifeFrameCodec.createMediaDataFrame(
                        pcmBytes = pcmPayload,
                        timestampSec = currentTimestampSec()
                    )
                    val ok = onSendAudioFrame(dataFrame, false)
                    if (!ok) {
                        // USB disconnected or write failed; stop capturing cleanly
                        break
                    }

                    sentSinceUiUpdate++
                    bytesSinceUiUpdate += pcmPayload.size.toLong()
                    if (sentSinceUiUpdate >= 10) {
                        val deltaPackets = sentSinceUiUpdate.toLong()
                        val deltaBytes = bytesSinceUiUpdate
                        val deltaSkipped = skippedSinceUiUpdate.toLong()
                        sentSinceUiUpdate = 0
                        bytesSinceUiUpdate = 0L
                        skippedSinceUiUpdate = 0
                        _stats.update { current ->
                            current.copy(
                                sentPcmPackets = current.sentPcmPackets + deltaPackets,
                                sentPcmBytes = current.sentPcmBytes + deltaBytes,
                                skippedSilentPackets = current.skippedSilentPackets + deltaSkipped
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Normal shutdown
            } catch (t: Throwable) {
                Log.w(TAG, "Audio capture loop terminated: ${t.message}")
            } finally {
                val remPackets = sentSinceUiUpdate.toLong()
                val remBytes = bytesSinceUiUpdate
                val remSkipped = skippedSinceUiUpdate.toLong()
                if (remPackets > 0L || remSkipped > 0L) {
                    _stats.update { current ->
                        current.copy(
                            sentPcmPackets = current.sentPcmPackets + remPackets,
                            sentPcmBytes = current.sentPcmBytes + remBytes,
                            skippedSilentPackets = current.skippedSilentPackets + remSkipped
                        )
                    }
                }
                isCapturingFlag.set(false)
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
                runCatching { recorder.release() }
                if (audioRecord === recorder) {
                    audioRecord = null
                }
                _stats.update { current ->
                    current.copy(
                        isCapturing = false,
                        statusMessage = if (hasRecordAudioPermission(appContext)) {
                            "Аудио 0x0003 остановлено (MSG_MEDIA_STOP)"
                        } else {
                            "Требуется разрешение RECORD_AUDIO для захвата звука в канал 0x0003"
                        }
                    )
                }
            }
        }

        true
    }

    /**
     * Stops `AudioRecord`, releases audio resources cleanly, and sends `MSG_MEDIA_STOP (0x00030002)`
     * over channel `0x0003` when [sendMediaStop] is `true`.
     */
    suspend fun stopCapture(sendMediaStop: Boolean = true) = captureMutex.withLock {
        stopCaptureLocked(sendMediaStop = sendMediaStop)
    }

    private suspend fun stopCaptureLocked(sendMediaStop: Boolean) {
        val wasActive = isCapturingFlag.getAndSet(false) || captureJob != null || audioRecord != null || _stats.value.mediaInitSent
        val activeJob = captureJob
        captureJob = null
        val activeRecorder = audioRecord
        audioRecord = null

        // Stop AudioRecord first so blocking read() wakes up immediately
        runCatching {
            if (activeRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                activeRecorder.stop()
            }
        }
        withContext(NonCancellable) {
            runCatching { activeJob?.cancelAndJoin() }
        }
        runCatching { activeRecorder?.release() }

        var stopSent = false
        if (sendMediaStop && wasActive) {
            val stopFrame = CarLifeFrameCodec.createMediaStopFrame(timestampSec = currentTimestampSec())
            stopSent = runCatching { onSendAudioFrame(stopFrame, true) }.getOrDefault(false)
        }

        _stats.update { current ->
            current.copy(
                isCapturing = false,
                mediaInitSent = false,
                hasRecordAudioPermission = hasRecordAudioPermission(appContext),
                sentStopPackets = if (stopSent) current.sentStopPackets + 1L else current.sentStopPackets,
                statusMessage = if (hasRecordAudioPermission(appContext)) {
                    "Аудио 0x0003 остановлено (48 кГц Stereo 16-bit PCM)"
                } else {
                    "Требуется разрешение RECORD_AUDIO для захвата звука в канал 0x0003"
                }
            )
        }
    }

    companion object {
        private const val TAG = "CarLifeAudioCapture"

        const val SAMPLE_RATE_HZ = CarlifeMusicInitProto.DEFAULT_SAMPLE_RATE // 48000
        const val CHANNEL_COUNT = CarlifeMusicInitProto.DEFAULT_CHANNEL_CONFIG // 2 (Stereo)
        const val BITS_PER_SAMPLE = CarlifeMusicInitProto.DEFAULT_SAMPLE_FORMAT // 16-bit PCM
        const val PCM_CHUNK_SIZE_BYTES = 4096 // ~21.3 ms at 48kHz Stereo 16-bit

        fun hasRecordAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun currentTimestampSec(nowMs: Long = System.currentTimeMillis()): Long {
            return (nowMs / 1000L) and 0x7FFF_FFFFL
        }

        /**
         * Returns `true` when all bytes in `buffer[0 until length]` are `0` (pure digital silence).
         */
        fun isBufferSilent(buffer: ByteArray, length: Int = buffer.size): Boolean {
            val safeLen = length.coerceIn(0, buffer.size)
            for (i in 0 until safeLen) {
                if (buffer[i].toInt() != 0) return false
            }
            return true
        }
    }
}
