package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import com.example.ui.theme.CockpitIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeRuntimeState
import com.example.core.usb.BridgeTelemetryBus
import com.example.core.usb.PacketLogEntry
import com.example.core.usb.UsbTransportState
import com.example.feature.cockpit.CockpitScreen
import com.example.feature.cockpit.MediaControllerRepository
import com.example.feature.telemetry.VagUdsTelemetryRepository
import com.example.feature.telemetry.VagUdsTelemetryService
import com.example.protocol.carlife.CarLifeChannel
import com.example.protocol.carlife.CarLifeControlStateMachine
import com.example.protocol.carlife.CarLifeFrame
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeHardKey
import com.example.protocol.carlife.CarLifeInputEvent
import com.example.protocol.carlife.CarLifeMsgType
import com.example.protocol.carlife.CarLifeTouchDecoder
import com.example.protocol.carlife.CarlifeProtocolVersionProto
import com.example.protocol.carlife.CarlifeVideoEncoderInfoProto
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.TelemetryEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var vagUdsTelemetryService: VagUdsTelemetryService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        MediaControllerRepository.initialize(this)
        vagUdsTelemetryService = VagUdsTelemetryService(this).also {
            it.startDemoSimulator(triggerColdStartSweep = false)
        }

        handleUsbAccessoryIntent(intent)

        setContent {
            MyApplicationTheme {
                CarLifeBridgeApp(
                    telemetryService = vagUdsTelemetryService,
                    onStartOrScanBridge = {
                        BridgeForegroundService.startWithAccessory(this, null)
                    },
                    onStopBridge = {
                        val stopIntent = Intent(this, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_DISCONNECT_STOP
                        }
                        startService(stopIntent)
                    },
                    onStartProjection = { width, height, fps ->
                        val intent = Intent(this, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_START_PROJECTION
                            putExtra(BridgeForegroundService.EXTRA_WIDTH, width)
                            putExtra(BridgeForegroundService.EXTRA_HEIGHT, height)
                            putExtra(BridgeForegroundService.EXTRA_FPS, fps)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    },
                    onStopProjection = {
                        val intent = Intent(this, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_STOP_PROJECTION
                        }
                        startService(intent)
                    },
                    onInjectTouch = { x, y ->
                        val intent = Intent(this, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_INJECT_TEST_TOUCH
                            putExtra(BridgeForegroundService.EXTRA_TOUCH_X, x)
                            putExtra(BridgeForegroundService.EXTRA_TOUCH_Y, y)
                        }
                        startService(intent)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MediaControllerRepository.refreshSessions()
    }

    override fun onDestroy() {
        if (::vagUdsTelemetryService.isInitialized) {
            vagUdsTelemetryService.stopSession()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAccessoryIntent(intent)
    }

    private fun handleUsbAccessoryIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            BridgeForegroundService.startWithAccessory(this, accessory)
        }
    }
}

@Composable
fun CarLifeBridgeApp(
    telemetryService: VagUdsTelemetryService,
    onStartOrScanBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit,
    onInjectTouch: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val runtimeState by BridgeTelemetryBus.runtimeState.collectAsStateWithLifecycle()
    val vagTelemetry by VagUdsTelemetryRepository.telemetryState.collectAsStateWithLifecycle()
    val mediaState by MediaControllerRepository.mediaState.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions handled gracefully */ }

    val bluetoothConnectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted) {
            telemetryService.connectBluetoothSpp()
        } else {
            telemetryService.startDemoSimulator()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = ObsidianSurface,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("nav_vag_cockpit"),
                    icon = {
                        Icon(
                            imageVector = CockpitIcons.DirectionsCar,
                            contentDescription = "VAG Digital Cockpit"
                        )
                    },
                    label = { Text("VAG Cockpit") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("nav_usb_transport"),
                    icon = {
                        Icon(
                            imageVector = CockpitIcons.Usb,
                            contentDescription = "USB AOA & H.264 Engine"
                        )
                    },
                    label = { Text("AOA & H.264") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.testTag("nav_codec_inspector"),
                    icon = {
                        Icon(
                            imageVector = CockpitIcons.SettingsInputComponent,
                            contentDescription = "CarLife Protocol Lab"
                        )
                    },
                    label = { Text("Protocol Lab") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            when (selectedTab) {
                0 -> Column(modifier = Modifier.fillMaxSize()) {
                    // Top UDS 0x22 Adapter & Mode Control Strip
                    UdsAdapterControlStrip(
                        telemetryState = vagTelemetry,
                        onConnectBluetoothObd = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val hasBt = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasBt) {
                                    telemetryService.connectBluetoothSpp()
                                } else {
                                    bluetoothConnectLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                            Manifest.permission.BLUETOOTH_SCAN
                                        )
                                    )
                                }
                            } else {
                                telemetryService.connectBluetoothSpp()
                            }
                        },
                        onConnectWifiObd = {
                            telemetryService.connectWifiObd()
                        },
                        onStartDemo = {
                            telemetryService.startDemoSimulator(triggerColdStartSweep = false)
                        }
                    )

                    // 3-Zone VAG Digital Cockpit Screen (:feature:cockpit-ui)
                    CockpitScreen(
                        telemetry = vagTelemetry,
                        mediaState = mediaState,
                        onPlayPause = { MediaControllerRepository.togglePlayPause() },
                        onNextTrack = { MediaControllerRepository.skipNext() },
                        onPreviousTrack = { MediaControllerRepository.skipPrevious() },
                        onRequestNotificationAccess = {
                            MediaControllerRepository.openNotificationListenerSettings(context)
                        },
                        onTriggerWarmUpSweep = {
                            telemetryService.triggerThermalWarmUpSweep()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                1 -> UsbTransportConsoleScreen(
                    state = runtimeState,
                    onStartOrScanBridge = onStartOrScanBridge,
                    onStopBridge = onStopBridge,
                    onStartProjection = onStartProjection,
                    onStopProjection = onStopProjection,
                    onInjectTouch = onInjectTouch
                )

                2 -> CodecLaboratoryScreen(
                    state = runtimeState
                )
            }
        }
    }
}

@Composable
private fun UdsAdapterControlStrip(
    telemetryState: com.example.feature.telemetry.VagTelemetryState,
    onConnectBluetoothObd: () -> Unit,
    onConnectWifiObd: () -> Unit,
    onStartDemo: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = ObsidianSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (telemetryState.isDemoMode) ElectricCyan else TelemetryEmerald)
                    )
                    Text(
                        text = telemetryState.connectionStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallModeChip(
                        label = "Demo",
                        active = telemetryState.isDemoMode,
                        icon = CockpitIcons.Speed,
                        testTag = "uds_mode_demo_btn",
                        onClick = onStartDemo
                    )
                    SmallModeChip(
                        label = "BT SPP",
                        active = !telemetryState.isDemoMode && !telemetryState.adapterName.contains("Wi-Fi"),
                        icon = CockpitIcons.Bluetooth,
                        testTag = "uds_mode_bt_btn",
                        onClick = onConnectBluetoothObd
                    )
                    SmallModeChip(
                        label = "Wi-Fi",
                        active = !telemetryState.isDemoMode && telemetryState.adapterName.contains("Wi-Fi"),
                        icon = CockpitIcons.Wifi,
                        testTag = "uds_mode_wifi_btn",
                        onClick = onConnectWifiObd
                    )
                }
            }

            Text(
                text = telemetryState.lastUdsExchange,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = TelemetryEmerald,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SmallModeChip(
    label: String,
    active: Boolean,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (active) ElectricCyan.copy(alpha = 0.2f) else ObsidianSurfaceHigh,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (active) ElectricCyan else ObsidianBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) ElectricCyan else TextSecondary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) ElectricCyan else TextSecondary
            )
        }
    }
}

@Composable
private fun UsbTransportConsoleScreen(
    state: BridgeRuntimeState,
    onStartOrScanBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit,
    onInjectTouch: (Int, Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeroBannerCard(state = state)
        }

        item {
            ProjectionEngineCard(
                state = state,
                onStartProjection = onStartProjection,
                onStopProjection = onStopProjection,
                onInjectTouch = onInjectTouch
            )
        }

        item {
            TransportControlsCard(
                state = state,
                onStartOrScanBridge = onStartOrScanBridge,
                onStopBridge = onStopBridge
            )
        }

        item {
            ChannelMultiplexerGrid(channelCounts = state.channelFrameCounts)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE WIRE FRAME LOG (${state.packetLogs.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricCyan
                )
                IconButton(
                    onClick = { BridgeTelemetryBus.clearLogs() },
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = CockpitIcons.ClearAll,
                        contentDescription = "Clear packet logs",
                        tint = TextSecondary
                    )
                }
            }
        }

        if (state.packetLogs.isEmpty()) {
            item {
                EmptyPacketLogCard()
            }
        } else {
            items(state.packetLogs, key = { it.id }) { entry ->
                PacketLogItemCard(entry = entry)
            }
        }
    }
}

@Composable
private fun ProjectionEngineCard(
    state: BridgeRuntimeState,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit,
    onInjectTouch: (Int, Int) -> Unit
) {
    val proj = state.projectionStats
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, if (proj.isRunning) TelemetryEmerald else ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CORE 2: VIRTUAL DISPLAY + H.264 + TOUCH (:core:projection)",
                    style = MaterialTheme.typography.labelLarge,
                    color = TelemetryEmerald
                )
                Text(
                    text = if (proj.spsPpsConfigured) "SPS/PPS LOCKED" else "AVC Baseline",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricPill(
                    label = "H.264 NALS",
                    value = "${proj.encodedNalFrames}",
                    subValue = "${proj.encodedBytes / 1024} KiB",
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "TOUCH INJECT",
                    value = "${proj.injectedTouches}",
                    subValue = "decorView",
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "SURFACE",
                    value = "${proj.width}×${proj.height}",
                    subValue = "${proj.fps} FPS",
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Last Touch Target: ${proj.activeCockpitWidget}",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = proj.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!proj.isRunning) {
                    Button(
                        onClick = { onStartProjection(1280, 720, 60) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_projection_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryEmerald,
                            contentColor = Color(0xFF002411)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start VirtualDisplay",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start VirtualDisplay + H.264", fontWeight = FontWeight.Bold)
                    }
                } else {
                    FilledTonalButton(
                        onClick = { onInjectTouch(300, 360) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inject_touch_nav_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Touch Map Zone")
                    }
                    FilledTonalButton(
                        onClick = { onInjectTouch(980, 560) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inject_touch_media_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Touch Media Zone")
                    }
                    OutlinedButton(
                        onClick = onStopProjection,
                        modifier = Modifier.testTag("stop_projection_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CoralAlert.copy(alpha = 0.6f))
                    ) {
                        Text("Stop", color = CoralAlert)
                    }
                }
            }

            // One-tap Video Framing Header Toggle (for Jetta VS7 / MQB Head Unit compatibility)
            val context = LocalContext.current
            OutlinedButton(
                onClick = {
                    val newMode = !CarLifeFrameCodec.useNestedVideoMsgHeader
                    CarLifeFrameCodec.useNestedVideoMsgHeader = newMode
                    BridgeTelemetryBus.updateState {
                        it.copy(
                            useNestedVideoMsgHeader = newMode,
                            lastProtocolEvent = if (newMode) {
                                "Video Framing: Nested MSG_VIDEO_DATA (0x00020001) + 12B Header enabled"
                            } else {
                                "Video Framing: Standard 12B Media Header (Raw Annex-B NAL) enabled"
                            }
                        )
                    }
                    if (state.isServiceRunning) {
                        val intent = Intent(context, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_TOGGLE_VIDEO_HEADER_MODE
                        }
                        context.startService(intent)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("toggle_video_header_mode_btn"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (state.useNestedVideoMsgHeader) AmberSignal else ElectricCyan.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = if (state.useNestedVideoMsgHeader) {
                        "Video Packet Mode: Nested MSG_VIDEO_DATA (0x00020001) [Tap to Switch]"
                    } else {
                        "Video Packet Mode: Standard 12B Header + Raw H.264 NAL [Tap to Switch]"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.useNestedVideoMsgHeader) AmberSignal else ElectricCyan
                )
            }
        }
    }
}

@Composable
private fun HeroBannerCard(state: BridgeRuntimeState) {
    val (statusLabel, statusColor) = when (val ts = state.transportState) {
        is UsbTransportState.Connected -> "DIRECT-IO AOA LINK ACTIVE • FD #${ts.fdNumber}" to TelemetryEmerald
        is UsbTransportState.Connecting -> "OPENING FILECHANNEL DIRECT-IO..." to AmberSignal
        is UsbTransportState.WaitingForPermission -> "AWAITING USB AOA PERMISSION" to AmberSignal
        is UsbTransportState.Error -> "STANDBY • NO AOA ACCESSORY" to AmberSignal
        UsbTransportState.Disconnected ->
            if (state.projectionStats.isRunning) "VIRTUALDISPLAY H.264 PROJECTION ACTIVE" to TelemetryEmerald
            else if (state.isServiceRunning) "FGS + WAKELOCK ACTIVE • LISTENING" to ElectricCyan
            else "IDLE • READY FOR JETTA VS7 (MQB)" to TextSecondary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_carlife_hero_1794551034086),
                contentDescription = "CarLife USB AOA Bridge HUD",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ObsidianBackground.copy(alpha = 0.35f),
                                ObsidianSurface.copy(alpha = 0.96f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                Text(
                    text = "Jetta VS7 Custom Car Cockpit",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Samsung Galaxy S26 Ultra • VirtualDisplay + H.264 + CarLife AOA",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = ObsidianBackground.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Text(
                        text = state.lastProtocolEvent,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportControlsCard(
    state: BridgeRuntimeState,
    onStartOrScanBridge: () -> Unit,
    onStopBridge: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CORE 1: CARLIFE USB AOA ENGINE (:core:carlife)",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Text(
                    text = if (state.wakeLockHeld) "WakeLock: HELD" else "DirectBufferPool",
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryEmerald
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricPill(
                    label = "RX FRAMES",
                    value = "${state.rxFrames}",
                    subValue = "${state.rxBytes} B",
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "TX FRAMES",
                    value = "${state.txFrames}",
                    subValue = "${state.txBytes} B",
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "HANDSHAKE",
                    value = state.controlSession.phase.name.take(8),
                    subValue = "${state.controlSession.negotiatedFps} FPS",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartOrScanBridge,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_connect_usb_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF002227)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan USB AOA",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Scan & Attach USB AOA",
                        fontWeight = FontWeight.Bold
                    )
                }

                if (state.isServiceRunning) {
                    OutlinedButton(
                        onClick = onStopBridge,
                        modifier = Modifier.testTag("stop_bridge_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CoralAlert.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = CockpitIcons.Stop,
                            contentDescription = "Stop Service",
                            tint = CoralAlert,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", color = CoralAlert)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianSurfaceHigh)
            .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Text(
            text = subValue,
            style = MaterialTheme.typography.labelSmall,
            color = ElectricCyan
        )
    }
}

@Composable
private fun ChannelMultiplexerGrid(
    channelCounts: Map<CarLifeChannel, Long>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CARLIFE MULTIPLEXER CHANNELS (0x0001 .. 0x0006)",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            val pairs = CarLifeChannel.entries.chunked(2)
            for (rowChannels in pairs) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (channel in rowChannels) {
                        val count = channelCounts[channel] ?: 0L
                        val badgeColor = if (channel.hasTimestampHeader) TelemetryEmerald else ElectricCyan
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ObsidianSurfaceHigh)
                                .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "0x%04X".format(channel.id),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = badgeColor
                                )
                                Text(
                                    text = "${channel.headerSizeBytes}B HDR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = channel.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "$count packets",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (count > 0) badgeColor else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodecLaboratoryScreen(
    state: BridgeRuntimeState
) {
    val scope = rememberCoroutineScope()
    val localStateMachine = remember { CarLifeControlStateMachine() }
    var lastDecodedBreakdown by remember {
        mutableStateOf(
            "Select a test vector below to run a real binary stream round-trip through CarLifeFrameCodec, CarLifeControlStateMachine, or CarLifeTouchDecoder."
        )
    }

    fun runLoopbackCodecTest(frame: CarLifeFrame, description: String) {
        scope.launch {
            val breakdown = withContext(Dispatchers.IO) {
                val pipedOut = PipedOutputStream()
                val pipedIn = PipedInputStream(pipedOut, 64 * 1024)
                CarLifeFrameCodec.writeFrame(pipedOut, frame)
                pipedOut.close()

                val decoded = CarLifeFrameCodec.readNextFrame(pipedIn)
                pipedIn.close()

                var extraAnalysis = ""
                if (decoded.channel == CarLifeChannel.CONTROL) {
                    val transition = localStateMachine.onControlFrame(decoded)
                    BridgeTelemetryBus.updateState {
                        it.copy(controlSession = transition.updatedState)
                    }
                    extraAnalysis = "\n• State Machine Transition: ${transition.summary}"
                    for (reply in transition.outboundFrames) {
                        BridgeTelemetryBus.recordFrame(
                            direction = "TX (S26U -> HU)",
                            frame = reply,
                            protocolNote = transition.summary
                        )
                    }
                } else if (decoded.channel == CarLifeChannel.TOUCH) {
                    val inputEvent = CarLifeTouchDecoder.decodeTouchChannelFrame(
                        frame = decoded,
                        targetWidth = 1920,
                        targetHeight = 1080,
                        defaultHuWidth = 1280,
                        defaultHuHeight = 720
                    )
                    val evStr = when (inputEvent) {
                        is CarLifeInputEvent.Touch ->
                            "${inputEvent.type.label} Raw(${inputEvent.rawX},${inputEvent.rawY} @ ${inputEvent.huWidth}x${inputEvent.huHeight}) -> Scaled(${inputEvent.scaledX.toInt()},${inputEvent.scaledY.toInt()} @ 1920x1080)"
                        is CarLifeInputEvent.HardKey ->
                            "MQB HardKey ${inputEvent.mappedKey?.label} -> Android KeyCode ${inputEvent.androidKeyCode}"
                        null -> "None"
                    }
                    BridgeTelemetryBus.updateState {
                        it.copy(lastTouchOrKeyEvent = evStr)
                    }
                    extraAnalysis = "\n• Touch/Key Decoded: $evStr"
                }

                BridgeTelemetryBus.recordFrame(
                    direction = "RX (HU -> S26U)",
                    frame = decoded,
                    protocolNote = "Verified ${decoded.header.headerSizeBytes}B header on ${decoded.channel.label}: $description"
                )

                val rawHeader = CarLifeFrameCodec.encodeHeader(decoded.header)
                val chHex = rawHeader.sliceArray(0..3).joinToString(" ") { "%02X".format(it) }
                val lenHex = rawHeader.sliceArray(4..7).joinToString(" ") { "%02X".format(it) }
                val tsPart = if (decoded.channel.hasTimestampHeader) {
                    val tsHex = rawHeader.sliceArray(8..11).joinToString(" ") { "%02X".format(it) }
                    "\n• Bytes [8..11] Timestamp: [$tsHex] -> ${decoded.header.timestamp} ms"
                } else {
                    "\n• Timestamp Field: Omitted (8-byte Standard Channel Header)"
                }
                val cmdSub = decoded.parseCommandMessageOrNull()?.let {
                    "\n• Inner Cmd Header: len=${it.body.size}B | service=${it.serviceName} (0x${it.serviceType.toUInt().toString(16).uppercase().padStart(8, '0')})"
                }.orEmpty()

                buildString {
                    appendLine("[$description]")
                    appendLine("• Header Size: ${decoded.header.headerSizeBytes} bytes | Total Wire Size: ${decoded.totalFrameBytes} bytes")
                    appendLine("• Bytes [0..3] Channel ID: [$chHex] -> ${decoded.channel.name} (0x%04X)".format(decoded.channel.id))
                    append("• Bytes [4..7] Payload Len: [$lenHex] -> ${decoded.header.payloadLength} bytes")
                    append(tsPart)
                    append(cmdSub)
                    append(extraAnalysis)
                }
            }
            lastDecodedBreakdown = breakdown
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "JETTA VS7 (MQB) CARLIFE PROTOCOL LAB",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                    Text(
                        text = "Test byte-level Protobuf & binary headers across Control (0x0001), Video (0x0002), Audio (0x0003), and Touch/Hardkey (0x0006) channels.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CodecTestButton(
                            title = "1. Handshake",
                            subtitle = "HU v2.0 + Video Init",
                            icon = CockpitIcons.Memory,
                            accent = ElectricCyan,
                            testTag = "test_control_frame_btn",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val versionFrame = CarLifeFrameCodec.createCommandFrame(
                                    channel = CarLifeChannel.CONTROL,
                                    serviceType = CarLifeMsgType.MSG_CMD_HU_PROTOCOL_VERSION,
                                    commandBody = CarlifeProtocolVersionProto(2, 0).encode()
                                )
                                val videoInitFrame = CarLifeFrameCodec.createCommandFrame(
                                    channel = CarLifeChannel.CONTROL,
                                    serviceType = CarLifeMsgType.MSG_CMD_VIDEO_ENCODER_INIT,
                                    commandBody = CarlifeVideoEncoderInfoProto(1920, 1080, 60).encode()
                                )
                                runLoopbackCodecTest(versionFrame, "Control 0x0001: MSG_CMD_HU_PROTOCOL_VERSION (v2.0)")
                                runLoopbackCodecTest(videoInitFrame, "Control 0x0001: MSG_CMD_VIDEO_ENCODER_INIT (1920x1080@60)")
                            }
                        )

                        CodecTestButton(
                            title = "2. Video (12B+TS)",
                            subtitle = "0x0002 H.264 SPS/IDR",
                            icon = CockpitIcons.Videocam,
                            accent = TelemetryEmerald,
                            testTag = "test_video_frame_btn",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val h264SpsPps = byteArrayOf(
                                    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x2A,
                                    0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()
                                )
                                val frame = CarLifeFrameCodec.createVideoFrame(
                                    nalData = h264SpsPps,
                                    timestampMs = System.currentTimeMillis() and 0xFFFF_FFFFL
                                )
                                runLoopbackCodecTest(frame, "Video 0x0002: H.264 SPS/PPS NAL @ 60fps")
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CodecTestButton(
                            title = "3. Touch Scale",
                            subtitle = "1280x720 -> 1920x1080",
                            icon = CockpitIcons.TouchApp,
                            accent = AmberSignal,
                            testTag = "test_touch_frame_btn",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val frame = CarLifeTouchDecoder.createTouchActionFrame(
                                    action = 0,
                                    x = 640,
                                    y = 360,
                                    huWidth = 1280,
                                    huHeight = 720
                                )
                                runLoopbackCodecTest(frame, "Touch 0x0006: MSG_TOUCH_ACTION (640,360 on 1280x720)")
                            }
                        )

                        CodecTestButton(
                            title = "4. MQB HardKey",
                            subtitle = "Steering Wheel NEXT",
                            icon = CockpitIcons.GraphicEq,
                            accent = CyberBlue,
                            testTag = "test_audio_frame_btn",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val frame = CarLifeTouchDecoder.createHardKeyFrame(CarLifeHardKey.SEEK_ADD)
                                runLoopbackCodecTest(frame, "Touch 0x0006: MSG_TOUCH_CAR_HARD_KEY_CODE (SEEK_ADD)")
                            }
                        )
                    }

                    Surface(
                        color = ObsidianBackground,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = lastDecodedBreakdown,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }
            }
        }

        items(state.packetLogs, key = { it.id }) { entry ->
            PacketLogItemCard(entry = entry)
        }
    }
}

@Composable
private fun CodecTestButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = ObsidianSurfaceHigh,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
        }
    }
}

@Composable
private fun PacketLogItemCard(entry: PacketLogEntry) {
    val accent = if (entry.channel.hasTimestampHeader) TelemetryEmerald else ElectricCyan
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.direction,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent
                    )
                    Text(
                        text = "• ${entry.channel.label} (0x%04X)".format(entry.channel.id),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary
                    )
                }
                Text(
                    text = entry.timestampFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Header: ${entry.headerSizeBytes}B",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "Payload: ${entry.payloadSizeBytes}B",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (entry.channel.hasTimestampHeader) {
                    Text(
                        text = "TS: ${entry.mediaTimestampMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = TelemetryEmerald
                    )
                }
                entry.serviceName?.let { service ->
                    Text(
                        text = service,
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberSignal
                    )
                }
            }

            Text(
                text = entry.hexPreview,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = TextMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun EmptyPacketLogCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = CockpitIcons.Cable,
                contentDescription = "No packets yet",
                tint = ElectricCyan,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "No CarLife Frames Captured Yet",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = "Start the VirtualDisplay + H.264 Encoder above or open the 'Protocol Lab' tab to test real binary frames.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}
