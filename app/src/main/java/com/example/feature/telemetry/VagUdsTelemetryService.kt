package com.example.feature.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thermal / operating zone classification for VAG Digital Cockpit color-coded gauges.
 */
enum class ThermalZone(val label: String) {
    COLD("COLD"),
    WARMING("WARM-UP"),
    OPTIMAL("OPTIMAL"),
    WARNING("HOT WARN")
}

/**
 * Complete real-time VAG / MQB vehicle telemetry snapshot for Jetta VS7
 * (EA211 1.4 TSI Engine `7E0 -> 7E8` & Aisin AQ250/09G 6AT Gearbox `7E1 -> 7E9`).
 */
data class VagTelemetryState(
    val isDemoMode: Boolean = true,
    val connectionStatus: String = "Demo Simulator Active (EA211 1.4 TSI + AQ250)",
    val adapterName: String = "MQB Bench Simulator",
    val oilTempC: Float = 96.0f,
    val gearboxTempC: Float = 82.0f,
    val coolantTempC: Float = 90.0f,
    val intakeAirTempC: Float = 29.0f,
    val boostBar: Float = 0.45f,
    val peakBoostBar: Float = 1.38f,
    val voltageV: Float = 14.2f,
    val engineRpm: Int = 2150,
    val speedKmh: Int = 78,
    val currentGear: String = "D6",
    val knockRetardDeg: Float = 0.0f,
    val polledFramesCount: Long = 0L,
    val lastUdsExchange: String = "TX [7E0] 22 F4 5C -> RX [7E8] 62 F4 5C 88 (96.0°C)"
) {
    val oilThermalZone: ThermalZone
        get() = when {
            oilTempC < 60f -> ThermalZone.COLD
            oilTempC < 80f -> ThermalZone.WARMING
            oilTempC <= 112f -> ThermalZone.OPTIMAL
            else -> ThermalZone.WARNING
        }

    val gearboxThermalZone: ThermalZone
        get() = when {
            gearboxTempC < 50f -> ThermalZone.COLD
            gearboxTempC < 68f -> ThermalZone.WARMING
            gearboxTempC <= 105f -> ThermalZone.OPTIMAL
            else -> ThermalZone.WARNING
        }
}

/**
 * Descriptor of a single VAG UDS (`Service 0x22 ReadDataByIdentifier`) parameter in the polling queue.
 *
 * @property ecuName Human-readable ECU name (`01-Engine` or `02-Gearbox`).
 * @property txHeader CAN 11-bit physical request ID (`"7E0"` for Engine, `"7E1"` for Gearbox).
 * @property rxHeader CAN 11-bit physical response ID (`"7E8"` for Engine, `"7E9"` for Gearbox).
 * @property did UDS 16-bit Data Identifier (e.g. `0xF45C`, `0x11E4`, `0xF442`, `0x101E`).
 * @property label Short telemetry label.
 */
data class VagUdsDidRequest(
    val ecuName: String,
    val txHeader: String,
    val rxHeader: String,
    val did: Int,
    val label: String
) {
    val commandHex: String
        get() = "22%04X".format(did and 0xFFFF)
}

/**
 * Core 3 (`:feature:telemetry`): VAG / MQB UDS (`Service 0x22 ReadDataByIdentifier`) Telemetry Engine.
 *
 * Supports:
 * 1. **Bluetooth Classic SPP (`00001101-0000-1000-8000-00805F9B34FB`)** for ELM327 / STN1170 / Vgate iCar Pro / OBDLink.
 * 2. **Wi-Fi TCP Socket (`192.168.0.10:35000`)** for Wi-Fi OBD-II / CAN bridges.
 * 3. **Interactive Bench Demo Mode (`isDemoMode = true`)** with Cold-Start Warm-Up simulation and
 *    authentic UDS `0x22 -> 0x62` frame generation & parsing so the entire Compose dashboard can be
 *    tested at home without plugging into the car.
 */
object VagUdsTelemetryRepository {
    private val _telemetryState = MutableStateFlow(VagTelemetryState())
    val telemetryState: StateFlow<VagTelemetryState> = _telemetryState.asStateFlow()

    internal fun update(transform: (VagTelemetryState) -> VagTelemetryState) {
        _telemetryState.update(transform)
    }
}

class VagUdsTelemetryService(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
) {
    private val appContext = context.applicationContext

    private var pollingJob: Job? = null
    private var btSocket: BluetoothSocket? = null
    private var tcpSocket: Socket? = null
    private val isRunning = AtomicBoolean(false)
    private val isColdStartSimulationRequested = AtomicBoolean(false)

    val telemetryState: StateFlow<VagTelemetryState> = VagUdsTelemetryRepository.telemetryState

    /**
     * Canonical UDS `0x22 ReadDataByIdentifier` polling queue for Jetta VS7 (MQB):
     * - `7E0 -> 7E8` (`01-Engine` EA211 1.4 TSI)
     * - `7E1 -> 7E9` (`02-Gearbox` Aisin AQ250 / 09G 6AT)
     */
    val udsPollingQueue: List<VagUdsDidRequest> = listOf(
        VagUdsDidRequest(
            ecuName = "01-Engine (EA211)",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = DID_ENGINE_OIL_TEMP,
            label = "Engine Oil Temp"
        ),
        VagUdsDidRequest(
            ecuName = "01-Engine (EA211)",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = DID_TURBO_BOOST_PRESSURE,
            label = "Turbo Boost Pressure"
        ),
        VagUdsDidRequest(
            ecuName = "01-Engine (EA211)",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = DID_BATTERY_VOLTAGE,
            label = "Terminal 30 Voltage"
        ),
        VagUdsDidRequest(
            ecuName = "02-Gearbox (AQ250)",
            txHeader = "7E1",
            rxHeader = "7E9",
            did = DID_GEARBOX_ATF_TEMP,
            label = "AQ250 ATF Temp"
        ),
        VagUdsDidRequest(
            ecuName = "02-Gearbox (AQ250)",
            txHeader = "7E1",
            rxHeader = "7E9",
            did = DID_GEARBOX_SELECTED_GEAR,
            label = "AQ250 Active Gear"
        ),
        VagUdsDidRequest(
            ecuName = "01-Engine (EA211)",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = DID_ENGINE_RPM,
            label = "Engine RPM"
        ),
        VagUdsDidRequest(
            ecuName = "01-Engine (EA211)",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = DID_VEHICLE_SPEED,
            label = "Vehicle Speed"
        ),
        VagUdsDidRequest(
            ecuName = "01-Engine (EA211)",
            txHeader = "7E0",
            rxHeader = "7E8",
            did = DID_INTAKE_AIR_TEMP,
            label = "Intake Air Temp"
        )
    )

    /**
     * Starts the bench Demo Simulator mode so gauges animate smoothly through cold-start warm-up,
     * turbo boost transients, and gear shifts while running real UDS `0x62` byte decoders.
     */
    fun startDemoSimulator(triggerColdStartSweep: Boolean = false) {
        stopSession()
        isColdStartSimulationRequested.set(triggerColdStartSweep)
        isRunning.set(true)

        VagUdsTelemetryRepository.update {
            it.copy(
                isDemoMode = true,
                connectionStatus = if (triggerColdStartSweep) {
                    "Demo Mode: Cold-Start Warm-Up Sweep (40°C -> 102°C)"
                } else {
                    "Demo Mode: Dynamic Driving Telemetry (7E0/7E1 UDS 0x22)"
                },
                adapterName = "VAG MQB UDS 0x22 Simulator"
            )
        }

        pollingJob = serviceScope.launch(ioDispatcher) {
            runDemoSimulationLoop()
        }
    }

    /**
     * Triggers a cold-start warm-up cycle (`38°C -> 114°C`) in Demo Mode so the user can visually
     * verify the Cold Blue -> Warm-Up Cyan -> Optimal Emerald -> Warning Red color transitions.
     */
    fun triggerThermalWarmUpSweep() {
        startDemoSimulator(triggerColdStartSweep = true)
    }

    /**
     * Connects to a paired Bluetooth Classic SPP ELM327 / STN / VAG CAN adapter and starts the
     * real UDS `0x22` polling queue on `7E0 -> 7E8` and `7E1 -> 7E9`.
     */
    @SuppressLint("MissingPermission")
    fun connectBluetoothSpp(deviceAddress: String? = null) {
        stopSession()
        isRunning.set(true)

        pollingJob = serviceScope.launch(ioDispatcher) {
            try {
                val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter: BluetoothAdapter? = btManager?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    fallbackToDemo("Bluetooth disabled or unavailable — Running Demo Simulator")
                    return@launch
                }

                val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
                val targetDevice: BluetoothDevice? = if (!deviceAddress.isNullOrBlank()) {
                    bonded.firstOrNull { it.address.equals(deviceAddress, ignoreCase = true) }
                } else {
                    bonded.firstOrNull { dev ->
                        val name = dev.name.orEmpty().uppercase()
                        name.contains("OBD") || name.contains("ELM") || name.contains("VGATE") ||
                            name.contains("ICAR") || name.contains("VAG") || name.contains("CARISTA")
                    } ?: bonded.firstOrNull()
                }

                if (targetDevice == null) {
                    fallbackToDemo("No paired Bluetooth OBD/ELM327 adapter found — Demo Mode Active")
                    return@launch
                }

                VagUdsTelemetryRepository.update {
                    it.copy(
                        isDemoMode = false,
                        connectionStatus = "Connecting SPP to ${targetDevice.name ?: targetDevice.address}...",
                        adapterName = targetDevice.name ?: targetDevice.address
                    )
                }

                val socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket = socket
                socket.connect()

                runHardwareElm327UdsLoop(
                    adapterLabel = targetDevice.name ?: targetDevice.address,
                    input = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII)),
                    output = socket.outputStream
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth SPP connection failed, switching to Demo Simulator: ${e.message}")
                fallbackToDemo("BT OBD unreachable (${e.message?.take(32)}) — Demo Simulator Active")
            }
        }
    }

    /**
     * Connects to a Wi-Fi ELM327 / STN TCP gateway (default `192.168.0.10:35000`).
     */
    fun connectWifiObd(host: String = "192.168.0.10", port: Int = 35000) {
        stopSession()
        isRunning.set(true)

        pollingJob = serviceScope.launch(ioDispatcher) {
            try {
                VagUdsTelemetryRepository.update {
                    it.copy(
                        isDemoMode = false,
                        connectionStatus = "Connecting TCP to $host:$port...",
                        adapterName = "Wi-Fi OBD ($host:$port)"
                    )
                }
                val socket = Socket()
                tcpSocket = socket
                socket.connect(InetSocketAddress(host, port), 2_500)
                socket.soTimeout = 2_000

                runHardwareElm327UdsLoop(
                    adapterLabel = "Wi-Fi OBD ($host:$port)",
                    input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)),
                    output = socket.getOutputStream()
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                fallbackToDemo("Wi-Fi OBD ($host:$port) unreachable — Demo Simulator Active")
            }
        }
    }

    fun stopSession() {
        isRunning.set(false)
        pollingJob?.cancel()
        pollingJob = null
        runCatching { btSocket?.close() }
        runCatching { tcpSocket?.close() }
        btSocket = null
        tcpSocket = null
    }

    /**
     * Executes the ELM327 / STN initialization sequence and continuously polls the UDS `0x22` queue
     * across `7E0 -> 7E8` (Engine) and `7E1 -> 7E9` (Gearbox).
     */
    private suspend fun CoroutineScope.runHardwareElm327UdsLoop(
        adapterLabel: String,
        input: BufferedReader,
        output: OutputStream
    ) {
        // Initialize ELM327 / STN1170 for VAG MQB ISO 15765-4 CAN (11-bit ID, 500 kbaud)
        val initCommands = listOf(
            "ATZ",      // Reset adapter
            "ATE0",     // Echo off
            "ATL0",     // Linefeeds off
            "ATS0",     // Spaces off
            "ATH0",     // Headers off in response payload (only ISO-TP data bytes)
            "ATSP6",    // Protocol 6: ISO 15765-4 CAN (11-bit ID, 500 kbaud)
            "ATAT1"     // Adaptive timing auto1
        )
        for (cmd in initCommands) {
            sendElmCommand(output, input, cmd)
        }

        VagUdsTelemetryRepository.update {
            it.copy(
                isDemoMode = false,
                connectionStatus = "Live UDS 0x22 Polling (7E0 Engine / 7E1 Gearbox)",
                adapterName = adapterLabel
            )
        }

        var activeTxHeader = ""
        var activeRxHeader = ""
        var queueIndex = 0
        var activeGearboxAtfDid = DID_GEARBOX_ATF_TEMP

        while (isActive && isRunning.get()) {
            val baseReq = udsPollingQueue[queueIndex % udsPollingQueue.size]
            queueIndex++

            val req = if (baseReq.did == DID_GEARBOX_ATF_TEMP && activeGearboxAtfDid != DID_GEARBOX_ATF_TEMP) {
                baseReq.copy(did = activeGearboxAtfDid)
            } else {
                baseReq
            }

            // Switch CAN physical addressing when changing between 01-Engine (7E0) and 02-Gearbox (7E1)
            if (req.txHeader != activeTxHeader || req.rxHeader != activeRxHeader) {
                sendElmCommand(output, input, "ATSH${req.txHeader}")
                sendElmCommand(output, input, "ATCRA${req.rxHeader}")
                activeTxHeader = req.txHeader
                activeRxHeader = req.rxHeader
            }

            val rawResponse = sendElmCommand(output, input, req.commandHex)
            val responseBytes = parseHexResponseToBytes(rawResponse)

            if (req.txHeader == "7E1" &&
                (req.did == DID_GEARBOX_ATF_TEMP ||
                    req.did == DID_GEARBOX_ATF_TEMP_FALLBACK_F446 ||
                    req.did == DID_GEARBOX_ATF_TEMP_FALLBACK_2021) &&
                isNegativeUdsResponseOrNoData(rawResponse, responseBytes)
            ) {
                // Fallback chain for Aisin AQ250 (7E1 -> 7E9): 0x101E -> 0xF446 -> 0x2021
                val fallbackCandidates = listOf(
                    DID_GEARBOX_ATF_TEMP_FALLBACK_F446,
                    DID_GEARBOX_ATF_TEMP_FALLBACK_2021,
                    DID_GEARBOX_ATF_TEMP
                ).filter { it != req.did }

                for (candidateDid in fallbackCandidates) {
                    val fallbackReq = req.copy(
                        did = candidateDid,
                        label = "AQ250 ATF Temp (0x%04X)".format(candidateDid)
                    )
                    val fbRaw = sendElmCommand(output, input, fallbackReq.commandHex)
                    val fbBytes = parseHexResponseToBytes(fbRaw)
                    if (!isNegativeUdsResponseOrNoData(fbRaw, fbBytes)) {
                        activeGearboxAtfDid = candidateDid
                        applyUdsResponseBytes(fallbackReq, fbBytes)
                        break
                    }
                }
            } else if (responseBytes.isNotEmpty()) {
                applyUdsResponseBytes(req, responseBytes)
            }
            delay(35L)
        }
    }

    /**
     * Parses a UDS `0x62` Positive Response (`[0x62, DID_HI, DID_LO, Data0, Data1, ...]`)
     * using exact VAG MQB scaling formulas and updates [VagUdsTelemetryRepository].
     */
    fun applyUdsResponseBytes(request: VagUdsDidRequest, responseBytes: ByteArray) {
        VagUdsTelemetryRepository.update { current ->
            decodeUdsResponseBytes(current, request, responseBytes)
        }
    }

    /**
     * Realistic physics-based Demo Mode loop that synthesizes raw UDS `0x62` responses
     * and feeds them into [applyUdsResponseBytes] so the exact UDS byte formulas are verified live.
     */
    private suspend fun CoroutineScope.runDemoSimulationLoop() {
        var tick = 0L
        while (isActive && isRunning.get()) {
            val phase = tick * 0.08
            val coldStartSweep = isColdStartSimulationRequested.get()

            // Calculate simulated physical values
            val simOilC = if (coldStartSweep) {
                // Sweeps from 42°C (Cold Blue) -> 72°C (Warm-Up Cyan) -> 98°C (Optimal Green) -> 116°C (Hot Warning)
                42f + ((tick % 160) * 0.48f)
            } else {
                96f + (sin(phase * 0.25) * 4.5).toFloat()
            }

            val simAtfC = if (coldStartSweep) {
                36f + ((tick % 160) * 0.44f)
            } else {
                83f + (sin(phase * 0.2) * 3.5).toFloat()
            }

            val simBoostBar = (0.35 + sin(phase * 1.1) * 0.95).toFloat().coerceIn(-0.65f, 1.45f)
            val simVoltageMv = (14_200 + (sin(phase * 0.5) * 180).toInt()).coerceIn(12_400, 14_600)
            val simRpm = (2100 + (sin(phase * 0.9) * 1150).toInt()).coerceIn(780, 5800)
            val simSpeed = (76 + (sin(phase * 0.4) * 32).toInt()).coerceIn(0, 180)
            val simGearCode = when {
                simSpeed < 15 -> 11 // D1
                simSpeed < 32 -> 12 // D2
                simSpeed < 50 -> 13 // D3
                simSpeed < 68 -> 14 // D4
                simSpeed < 88 -> 15 // D5
                else -> 16          // D6
            }

            // Encode simulated physical values into authentic UDS 0x62 byte arrays and decode via applyUdsResponseBytes
            val oilByte = (simOilC.roundToInt() + 40).coerceIn(0, 255).toByte()
            applyUdsResponseBytes(
                udsPollingQueue[0],
                byteArrayOf(0x62, 0xF4.toByte(), 0x5C, oilByte)
            )

            val absHpaRaw = (((simBoostBar * 1000f) + 1013.25f) * 2.0f).roundToInt().coerceIn(400, 6000)
            applyUdsResponseBytes(
                udsPollingQueue[1],
                byteArrayOf(
                    0x62,
                    0x11,
                    0xE4.toByte(),
                    ((absHpaRaw ushr 8) and 0xFF).toByte(),
                    (absHpaRaw and 0xFF).toByte()
                )
            )

            applyUdsResponseBytes(
                udsPollingQueue[2],
                byteArrayOf(
                    0x62,
                    0xF4.toByte(),
                    0x42,
                    ((simVoltageMv ushr 8) and 0xFF).toByte(),
                    (simVoltageMv and 0xFF).toByte()
                )
            )

            val atfByte = (simAtfC.roundToInt() + 40).coerceIn(0, 255).toByte()
            applyUdsResponseBytes(
                udsPollingQueue[3],
                byteArrayOf(0x62, 0x10, 0x1E, atfByte)
            )

            applyUdsResponseBytes(
                udsPollingQueue[4],
                byteArrayOf(0x62, 0x10, 0x2A, simGearCode.toByte())
            )

            val rpmRaw = simRpm * 4
            applyUdsResponseBytes(
                udsPollingQueue[5],
                byteArrayOf(
                    0x62,
                    0xF4.toByte(),
                    0x0C,
                    ((rpmRaw ushr 8) and 0xFF).toByte(),
                    (rpmRaw and 0xFF).toByte()
                )
            )

            applyUdsResponseBytes(
                udsPollingQueue[6],
                byteArrayOf(0x62, 0xF4.toByte(), 0x0D, simSpeed.toByte())
            )

            tick++
            delay(180L)
        }
    }

    private suspend fun fallbackToDemo(reason: String) {
        VagUdsTelemetryRepository.update {
            it.copy(
                isDemoMode = true,
                connectionStatus = reason,
                adapterName = "VAG MQB Demo Simulator"
            )
        }
        if (isRunning.get()) {
            withContext(ioDispatcher) {
                runDemoSimulationLoop()
            }
        }
    }

    private fun sendElmCommand(output: OutputStream, input: BufferedReader, cmd: String): String {
        output.write("$cmd\r".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch == -1 || ch.toChar() == '>') break
            val c = ch.toChar()
            if (c != '\r' && c != '\n') {
                sb.append(c)
            } else {
                sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TAG = "VagUdsTelemetrySvc"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // VAG / MQB UDS 0x22 Data Identifiers (DIDs)
        const val DID_ENGINE_COOLANT_TEMP: Int = 0xF405
        const val DID_ENGINE_RPM: Int = 0xF40C
        const val DID_VEHICLE_SPEED: Int = 0xF40D
        const val DID_INTAKE_AIR_TEMP: Int = 0xF40F
        const val DID_BATTERY_VOLTAGE: Int = 0xF442
        const val DID_ENGINE_OIL_TEMP: Int = 0xF45C
        const val DID_TURBO_BOOST_PRESSURE: Int = 0x11E4
        const val DID_GEARBOX_ATF_TEMP: Int = 0x101E
        const val DID_GEARBOX_ATF_TEMP_FALLBACK_F446: Int = 0xF446
        const val DID_GEARBOX_ATF_TEMP_FALLBACK_2021: Int = 0x2021
        const val DID_GEARBOX_SELECTED_GEAR: Int = 0x102A

        /**
         * Detects `NODATA` or UDS Negative Response `7F 22 xx` (e.g. `7F 22 31` RequestOutOfRange).
         */
        fun isNegativeUdsResponseOrNoData(rawHex: String, responseBytes: ByteArray): Boolean {
            val upper = rawHex.uppercase()
            if (responseBytes.isEmpty() || upper.contains("NODATA") || upper.contains("NO DATA") || upper.contains("ERROR")) {
                return true
            }
            for (i in 0 until responseBytes.size - 1) {
                val b0 = responseBytes[i].toInt() and 0xFF
                val b1 = responseBytes[i + 1].toInt() and 0xFF
                if (b0 == 0x7F && b1 == 0x22) {
                    return true
                }
            }
            return false
        }

        fun parseHexResponseToBytes(rawHex: String): ByteArray {
            val upper = rawHex.uppercase()
            if (upper.contains("NODATA") ||
                upper.contains("NO DATA") ||
                upper.contains("ERROR") ||
                upper.contains("UNABLE") ||
                upper.contains("STOPPED")
            ) {
                return ByteArray(0)
            }
            // Strip non-hex ELM327 status words ("SEARCHING...", "BUS INIT", "OK") and ISO-TP frame indices ("0:", "1:")
            val stripped = upper
                .replace("SEARCHING...", "")
                .replace("SEARCHING", "")
                .replace("BUSINIT", "")
                .replace("BUS INIT", "")
                .replace("OK", "")
                .replace(Regex("[0-9A-F]:"), "")
                .replace(" ", "")
                .replace("\r", "")
                .replace("\n", "")

            val validHex = stripped.filter { it in '0'..'9' || it in 'A'..'F' }
            val pairCount = validHex.length / 2
            if (pairCount == 0) return ByteArray(0)
            val out = ByteArray(pairCount)
            for (i in 0 until pairCount) {
                val hi = Character.digit(validHex[i * 2], 16)
                val lo = Character.digit(validHex[i * 2 + 1], 16)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        /**
         * Pure VAG MQB UDS `0x62` Positive Response decoder for unit testing and state updates.
         *
         * Handles `ATH0` / `ATS0` single-frame responses (even if an ELM327 clone prepends a PCI
         * length byte or appends CAN 8-byte `0x55`/`0xAA`/`0x00` padding).
         */
        fun decodeUdsResponseBytes(
            current: VagTelemetryState,
            request: VagUdsDidRequest,
            responseBytes: ByteArray
        ): VagTelemetryState {
            val expectedDidHi = (request.did ushr 8) and 0xFF
            val expectedDidLo = request.did and 0xFF

            // Locate exact 3-byte positive UDS response header: [0x62, DID_HI, DID_LO]
            var serviceIdx = -1
            for (i in 0..(responseBytes.size - 4)) {
                val b0 = responseBytes[i].toInt() and 0xFF
                val b1 = responseBytes[i + 1].toInt() and 0xFF
                val b2 = responseBytes[i + 2].toInt() and 0xFF
                if (b0 == 0x62 && b1 == expectedDidHi && b2 == expectedDidLo) {
                    serviceIdx = i
                    break
                }
            }
            if (serviceIdx < 0) return current

            val respDid = request.did
            val dataOffset = serviceIdx + 3
            val dataLen = responseBytes.size - dataOffset
            if (dataLen <= 0) return current

            val a = responseBytes[dataOffset].toInt() and 0xFF
            val b = if (dataLen >= 2) responseBytes[dataOffset + 1].toInt() and 0xFF else 0

            val hexRx = responseBytes.sliceArray(serviceIdx until responseBytes.size)
                .joinToString(" ") { "%02X".format(it) }
            val exchangeTrace = "TX [${request.txHeader}] 22 %02X %02X -> RX [${request.rxHeader}] $hexRx"
                .format(expectedDidHi, expectedDidLo)

            val nextCount = current.polledFramesCount + 1L
            return when (respDid) {
                DID_ENGINE_OIL_TEMP -> {
                    // 0xF45C is standard 1-byte UDS/OBD temperature (A - 40°C), immune to trailing CAN padding bytes
                    val oilC = (a - 40).toFloat()
                    current.copy(
                        oilTempC = ((oilC * 10f).roundToInt() / 10f).coerceIn(-40f, 160f),
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace (${ "%.1f".format(oilC) }°C)"
                    )
                }

                DID_GEARBOX_ATF_TEMP,
                DID_GEARBOX_ATF_TEMP_FALLBACK_F446,
                DID_GEARBOX_ATF_TEMP_FALLBACK_2021 -> {
                    // 0xF446 is always 1-byte (A - 40°C).
                    // For 0x101E / 0x2021, check if 2-byte 0.1K value is in realisticKelvinRange (230.0K..430.0K)
                    // and not CAN DLC padding (0x00, 0x55, 0xAA, 0xFF).
                    val isLikelyCanPadding = b == 0x00 || b == 0x55 || b == 0xAA || b == 0xFF
                    val kelvinCandidateC = (((a shl 8) or b) * 0.1f) - 273.15f
                    val atfC = if (
                        respDid != DID_GEARBOX_ATF_TEMP_FALLBACK_F446 &&
                        dataLen >= 2 &&
                        !isLikelyCanPadding &&
                        a in 9..16 &&
                        kelvinCandidateC in -35f..155f
                    ) {
                        kelvinCandidateC
                    } else {
                        (a - 40).toFloat()
                    }
                    current.copy(
                        gearboxTempC = ((atfC * 10f).roundToInt() / 10f).coerceIn(-40f, 160f),
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace (${ "%.1f".format(atfC) }°C)"
                    )
                }

                DID_TURBO_BOOST_PRESSURE -> {
                    val absHpa = ((a shl 8) or b) * 0.5f
                    val relBar = ((absHpa - 1013.25f) / 1000.0f).coerceIn(-0.85f, 1.80f)
                    val roundedBar = (relBar * 100f).roundToInt() / 100f
                    current.copy(
                        boostBar = roundedBar,
                        peakBoostBar = maxOf(current.peakBoostBar, roundedBar),
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace (${ "%+.2f".format(roundedBar) } Bar)"
                    )
                }

                DID_BATTERY_VOLTAGE -> {
                    val volts = (((a shl 8) or b) / 1000.0f).coerceIn(9.0f, 16.8f)
                    val roundedV = (volts * 10f).roundToInt() / 10f
                    current.copy(
                        voltageV = roundedV,
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace (${ "%.1f".format(roundedV) } V)"
                    )
                }

                DID_ENGINE_COOLANT_TEMP -> {
                    val coolantC = (a - 40).toFloat()
                    current.copy(
                        coolantTempC = coolantC,
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace (${coolantC.toInt()}°C)"
                    )
                }

                DID_INTAKE_AIR_TEMP -> {
                    val iatC = (a - 40).toFloat()
                    current.copy(
                        intakeAirTempC = iatC,
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace (IAT ${iatC.toInt()}°C)"
                    )
                }

                DID_ENGINE_RPM -> {
                    val rpm = (((a shl 8) or b) / 4).coerceIn(0, 8000)
                    current.copy(
                        engineRpm = rpm,
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace ($rpm RPM)"
                    )
                }

                DID_VEHICLE_SPEED -> {
                    val kmh = a.coerceIn(0, 260)
                    current.copy(
                        speedKmh = kmh,
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace ($kmh km/h)"
                    )
                }

                DID_GEARBOX_SELECTED_GEAR -> {
                    val gearStr = when (a) {
                        0 -> "P"
                        1 -> "R"
                        2 -> "N"
                        in 11..16 -> "D${a - 10}"
                        in 21..26 -> "S${a - 20}"
                        else -> "D6"
                    }
                    current.copy(
                        currentGear = gearStr,
                        polledFramesCount = nextCount,
                        lastUdsExchange = "$exchangeTrace (Gear $gearStr)"
                    )
                }

                else -> current
            }
        }
    }
}

