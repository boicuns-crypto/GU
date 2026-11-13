package com.example

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.input.CarLifeTouchAccessibilityService
import com.example.core.projection.MediaProjectionTokenStore
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeRuntimeState
import com.example.core.usb.BridgeTelemetryBus
import com.example.core.usb.PacketLogEntry
import com.example.core.usb.UsbTransportState
import com.example.feature.cockpit.CarLifeAppLauncherScreen
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.MediaControllerRepository
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
import com.example.ui.theme.CockpitIcons
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                BridgeForegroundService.grantMediaProjection(
                    context = this,
                    resultCode = result.resultCode,
                    resultData = data
                )
            }
        }

        MediaControllerRepository.initialize(this)
        handleUsbAccessoryIntent(intent)

        setContent {
            MyApplicationTheme {
                CarLifeBridgeApp(
                    onRequestMediaProjection = {
                        requestScreenCapturePermission()
                    },
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
                        if (!MediaProjectionTokenStore.hasValidToken.value) {
                            requestScreenCapturePermission()
                        } else {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAccessoryIntent(intent)
    }

    private fun requestScreenCapturePermission() {
        runCatching {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    private fun handleUsbAccessoryIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            // Start Foreground Service safely (uses FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE first,
            // and upgrades to FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION once token is available).
            BridgeForegroundService.startWithAccessory(this, accessory)

            // If user hasn't granted MediaProjection yet, prompt immediately so CarLife passes 90%!
            if (!MediaProjectionTokenStore.hasValidToken.value) {
                requestScreenCapturePermission()
            }
        }
    }
}

/**
 * Snapshot of the 6 onboarding checklist steps required for 100% CarLife initialization on Android 16.
 */
data class SetupChecklistStatus(
    val step1BatteryUnrestricted: Boolean = false,
    val step2OverlayGranted: Boolean = false,
    val step3AccessibilityEnabled: Boolean = false,
    val step4NotificationAccessGranted: Boolean = false,
    val step5MediaProjectionGranted: Boolean = false,
    val isUsbConnected: Boolean = false
) {
    val completedMandatorySteps: Int
        get() = listOf(
            step1BatteryUnrestricted,
            step2OverlayGranted,
            step3AccessibilityEnabled,
            step4NotificationAccessGranted,
            step5MediaProjectionGranted
        ).count { it }

    val allPrerequisitesReady: Boolean
        get() = step1BatteryUnrestricted &&
            step2OverlayGranted &&
            step3AccessibilityEnabled &&
            step4NotificationAccessGranted &&
            step5MediaProjectionGranted
}

private fun evaluateSetupChecklist(
    context: Context,
    a11yRealtimeFlag: Boolean,
    hasMediaProjectionToken: Boolean,
    transportState: UsbTransportState
): SetupChecklistStatus {
    val pkg = context.packageName

    // Step 1: PowerManager.isIgnoringBatteryOptimizations(packageName)
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(pkg) == true

    // Step 2: Settings.canDrawOverlays(context)
    val overlayGranted = Settings.canDrawOverlays(context)

    // Step 3: AccessibilityService in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    val expectedComponent = ComponentName(pkg, CarLifeTouchAccessibilityService::class.java.name)
    val enabledServicesRaw = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    }.getOrNull().orEmpty()
    val a11yInSecureSettings = enabledServicesRaw.contains(expectedComponent.flattenToString(), ignoreCase = true) ||
        enabledServicesRaw.contains(expectedComponent.flattenToShortString(), ignoreCase = true) ||
        enabledServicesRaw.contains(CarLifeTouchAccessibilityService::class.java.simpleName, ignoreCase = true)
    val accessibilityEnabled = a11yRealtimeFlag || a11yInSecureSettings

    // Step 4: NotificationManagerCompat.getEnabledListenerPackages(context).contains(packageName)
    val notificationGranted = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(pkg)
    }.getOrDefault(false)

    return SetupChecklistStatus(
        step1BatteryUnrestricted = batteryUnrestricted,
        step2OverlayGranted = overlayGranted,
        step3AccessibilityEnabled = accessibilityEnabled,
        step4NotificationAccessGranted = notificationGranted,
        step5MediaProjectionGranted = hasMediaProjectionToken,
        isUsbConnected = transportState is UsbTransportState.Connected
    )
}

@Composable
fun CarLifeBridgeApp(
    onRequestMediaProjection: () -> Unit,
    onStartOrScanBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit,
    onInjectTouch: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtimeState by BridgeTelemetryBus.runtimeState.collectAsStateWithLifecycle()
    val mediaState by MediaControllerRepository.mediaState.collectAsStateWithLifecycle()
    val a11yEnabled by CarLifeTouchAccessibilityService.isServiceEnabled.collectAsStateWithLifecycle()
    val hasMediaProjectionToken by MediaProjectionTokenStore.hasValidToken.collectAsStateWithLifecycle()

    // Default to Tab 0 (Мастер настройки / Setup Checklist) so the user can verify all 6 steps first
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var checklistStatus by remember {
        mutableStateOf(
            evaluateSetupChecklist(
                context = context,
                a11yRealtimeFlag = a11yEnabled,
                hasMediaProjectionToken = hasMediaProjectionToken,
                transportState = runtimeState.transportState
            )
        )
    }

    // Refresh checklist whenever Lifecycle resumes or reactive flags change
    DisposableEffect(lifecycleOwner, a11yEnabled, hasMediaProjectionToken, runtimeState.transportState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checklistStatus = evaluateSetupChecklist(
                    context = context,
                    a11yRealtimeFlag = a11yEnabled,
                    hasMediaProjectionToken = hasMediaProjectionToken,
                    transportState = runtimeState.transportState
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Periodic 1s real-time status check so toggles update immediately
    LaunchedEffect(a11yEnabled, hasMediaProjectionToken, runtimeState.transportState) {
        while (isActive) {
            checklistStatus = evaluateSetupChecklist(
                context = context,
                a11yRealtimeFlag = a11yEnabled,
                hasMediaProjectionToken = hasMediaProjectionToken,
                transportState = runtimeState.transportState
            )
            delay(1_000L)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Handled gracefully */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notificationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        CarLifeLauncherRepository.loadInstalledApps(context)
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
                    modifier = Modifier.testTag("nav_setup_checklist"),
                    icon = {
                        Icon(
                            imageVector = if (checklistStatus.allPrerequisitesReady) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Warning
                            },
                            contentDescription = "Мастер настройки (Чек-лист)"
                        )
                    },
                    label = {
                        Text(
                            if (checklistStatus.allPrerequisitesReady) {
                                "Чек-лист ✅"
                            } else {
                                "Настройка (${checklistStatus.completedMandatorySteps}/5)"
                            }
                        )
                    }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("nav_app_launcher"),
                    icon = {
                        Icon(
                            imageVector = CockpitIcons.AppsGrid,
                            contentDescription = "CarLife App Launcher"
                        )
                    },
                    label = { Text("Лаунчер ГУ") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
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
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
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
                0 -> OnboardingChecklistScreen(
                    status = checklistStatus,
                    runtimeState = runtimeState,
                    onRequestBatteryUnrestricted = {
                        requestIgnoreBatteryOptimizations(context)
                    },
                    onRequestOverlayPermission = {
                        openOverlayPermissionSettings(context)
                    },
                    onOpenAccessibilitySettings = {
                        CarLifeTouchAccessibilityService.openAccessibilitySettings(context)
                    },
                    onOpenAppDetailsSettings = {
                        openAppDetailsSettings(context)
                    },
                    onOpenNotificationListenerSettings = {
                        MediaControllerRepository.openNotificationListenerSettings(context)
                    },
                    onRequestMediaProjection = onRequestMediaProjection,
                    onScanAndAttachUsb = onStartOrScanBridge,
                    onOpenLauncherTab = { selectedTab = 1 }
                )

                1 -> Column(modifier = Modifier.fillMaxSize()) {
                    // Top Quick-Setup Strip for Touch Accessibility, Overlay & VirtualDisplay Projection
                    MirroringQuickControlStrip(
                        isProjectionRunning = runtimeState.projectionStats.isRunning,
                        virtualDisplayId = runtimeState.projectionStats.virtualDisplayId,
                        a11yEnabled = checklistStatus.step3AccessibilityEnabled,
                        overlayGranted = checklistStatus.step2OverlayGranted,
                        onToggleProjection = {
                            if (runtimeState.projectionStats.isRunning) {
                                onStopProjection()
                            } else {
                                onStartProjection(1280, 720, 60)
                            }
                        },
                        onOpenAccessibilitySettings = {
                            CarLifeTouchAccessibilityService.openAccessibilitySettings(context)
                        },
                        onOpenOverlaySettings = {
                            openOverlayPermissionSettings(context)
                        }
                    )

                    // CarLife VirtualDisplay App Launcher & Dock Bar
                    CarLifeAppLauncherScreen(
                        virtualDisplayId = runtimeState.projectionStats.virtualDisplayId,
                        mediaState = mediaState,
                        onPlayPause = { MediaControllerRepository.togglePlayPause() },
                        onNextTrack = { MediaControllerRepository.skipNext() },
                        onPreviousTrack = { MediaControllerRepository.skipPrevious() },
                        onRequestNotificationAccess = {
                            MediaControllerRepository.openNotificationListenerSettings(context)
                        },
                        onHomePressed = {},
                        onBackPressed = {
                            CarLifeTouchAccessibilityService.instance?.performBackAction()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                2 -> UsbTransportConsoleScreen(
                    state = runtimeState,
                    onStartOrScanBridge = onStartOrScanBridge,
                    onStopBridge = onStopBridge,
                    onStartProjection = onStartProjection,
                    onStopProjection = onStopProjection,
                    onInjectTouch = onInjectTouch
                )

                3 -> CodecLaboratoryScreen(
                    state = runtimeState
                )
            }
        }
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}

private fun openOverlayPermissionSettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun openAppDetailsSettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
private fun OnboardingChecklistScreen(
    status: SetupChecklistStatus,
    runtimeState: BridgeRuntimeState,
    onRequestBatteryUnrestricted: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onScanAndAttachUsb: () -> Unit,
    onOpenLauncherTab: () -> Unit
) {
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
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(
                    1.5.dp,
                    if (status.allPrerequisitesReady) TelemetryEmerald else AmberSignal
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "МАСТЕР НАСТРОЙКИ CARLIFE (БЕЗ ЗАВИСАНИЯ НА 90%)",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (status.allPrerequisitesReady) TelemetryEmerald else ElectricCyan
                        )
                        Surface(
                            color = if (status.allPrerequisitesReady) {
                                TelemetryEmerald.copy(alpha = 0.18f)
                            } else {
                                AmberSignal.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${status.completedMandatorySteps} / 5 выполнено",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.allPrerequisitesReady) TelemetryEmerald else AmberSignal,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = if (status.allPrerequisitesReady) {
                            "Все 5 обязательных разрешений активны ✅ Видеоканал H.264 (SPS/PPS + IDR) готов к мгновенной отдаче — зависание на 90% устранено."
                        } else {
                            "Выполните все шаги ниже перед подключением USB-кабеля к ГУ автомобиля, чтобы загрузка Baidu CarLife не останавливалась на 90%."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )

                    LinearProgressIndicator(
                        progress = { status.completedMandatorySteps / 5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (status.allPrerequisitesReady) TelemetryEmerald else ElectricCyan,
                        trackColor = ObsidianSurfaceHigh
                    )
                }
            }
        }

        // ШАГ 1: Снятие ограничений батареи
        item {
            ChecklistStepCard(
                stepNumber = 1,
                title = "Шаг 1. Снятие ограничений батареи (WakeLock & Unthrottled 60fps)",
                isCompleted = status.step1BatteryUnrestricted,
                description = "Отключите оптимизацию батареи, чтобы система One UI не усыпляла видеопоток при выключенном экране телефона.",
                primaryButtonText = if (status.step1BatteryUnrestricted) {
                    "Оптимизация отключена"
                } else {
                    "Отключить оптимизацию батареи"
                },
                testTag = "step1_battery_btn",
                onPrimaryClick = onRequestBatteryUnrestricted
            )
        }

        // ШАГ 2: Разрешение «Поверх других окон» (Overlay)
        item {
            ChecklistStepCard(
                stepNumber = 2,
                title = "Шаг 2. Разрешение «Поверх других окон» (Overlay)",
                isCompleted = status.step2OverlayGranted,
                description = "Разрешите показ поверх других окон — это необходимо для запуска полноценных приложений на экране ГУ из фонового сервиса.",
                primaryButtonText = if (status.step2OverlayGranted) {
                    "Разрешение Overlay выдано"
                } else {
                    "Разрешить «Поверх других окон»"
                },
                testTag = "step2_overlay_btn",
                onPrimaryClick = onRequestOverlayPermission
            )
        }

        // ШАГ 3: Служба специальных возможностей (Accessibility Service)
        item {
            ChecklistStepCard(
                stepNumber = 3,
                title = "Шаг 3. Служба специальных возможностей (Тачскрин ГУ)",
                isCompleted = status.step3AccessibilityEnabled,
                description = "Откройте «Специальные возможности» -> «Установленные приложения» -> включите службу управления касаниями CarLife. (Если кнопка неактивна на Android 16: зайдите в Настройки -> Приложения -> Наше приложение -> Меню (три точки в углу) -> «Разрешить ограниченные настройки»).",
                primaryButtonText = if (status.step3AccessibilityEnabled) {
                    "Служба касаний активна"
                } else {
                    "Открыть Специальные возможности"
                },
                testTag = "step3_accessibility_btn",
                onPrimaryClick = onOpenAccessibilitySettings,
                secondaryButtonText = "О приложении (Разрешить ограниченные настройки)",
                onSecondaryClick = onOpenAppDetailsSettings
            )
        }

        // ШАГ 4: Доступ к уведомлениям
        item {
            ChecklistStepCard(
                stepNumber = 4,
                title = "Шаг 4. Доступ к уведомлениям (Для виджета музыки на ГУ)",
                isCompleted = status.step4NotificationAccessGranted,
                description = "Разрешите чтение уведомлений для отображения обложек треков и управления Яндекс.Музыкой / Spotify с экрана ГУ.",
                primaryButtonText = if (status.step4NotificationAccessGranted) {
                    "Доступ к уведомлениям открыт"
                } else {
                    "Разрешить доступ к уведомлениям"
                },
                testTag = "step4_notifications_btn",
                onPrimaryClick = onOpenNotificationListenerSettings
            )
        }

        // ШАГ 5: Захват экрана (MediaProjection) — КРИТИЧНО ДЛЯ ПРОХОЖДЕНИЯ 90%
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(
                    width = if (status.step5MediaProjectionGranted) 1.dp else 2.dp,
                    color = if (status.step5MediaProjectionGranted) TelemetryEmerald else CoralAlert
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Шаг 5. Захват экрана (MediaProjection) — КРИТИЧНО ДЛЯ 90%",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadgePill(isCompleted = status.step5MediaProjectionGranted)
                    }

                    Surface(
                        color = CoralAlert.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CoralAlert.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                append("Нажмите «Начать трансляцию». ")
                                withStyle(
                                    SpanStyle(
                                        color = CoralAlert,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                ) {
                                    append(
                                        "ВНИМАНИЕ: В системном окне Samsung обязательно выберите «Весь экран» (Entire Screen), а НЕ «Одно приложение», иначе загрузка на ГУ зависнет на 90%!"
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    Button(
                        onClick = onRequestMediaProjection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("step5_mediaprojection_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (status.step5MediaProjectionGranted) TelemetryEmerald else CoralAlert,
                            contentColor = if (status.step5MediaProjectionGranted) Color(0xFF002411) else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = CockpitIcons.Videocam,
                            contentDescription = "Начать трансляцию",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (status.step5MediaProjectionGranted) {
                                "Трансляция «Весь экран» активна ✅ (Перезапустить)"
                            } else {
                                "Начать трансляцию (Выбрать «Весь экран»)"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ШАГ 6: Подключение USB-кабеля к ГУ
        item {
            val step6Active = status.allPrerequisitesReady
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (step6Active) ObsidianSurface else ObsidianSurface.copy(alpha = 0.55f)
                ),
                border = BorderStroke(
                    1.5.dp,
                    when {
                        status.isUsbConnected && step6Active -> TelemetryEmerald
                        step6Active -> ElectricCyan
                        else -> ObsidianBorder
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Шаг 6. Подключение USB-кабеля к ГУ автомобиля",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (step6Active) TextPrimary else TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = when {
                                status.isUsbConnected && step6Active -> TelemetryEmerald.copy(alpha = 0.2f)
                                step6Active -> ElectricCyan.copy(alpha = 0.2f)
                                else -> ObsidianSurfaceHigh
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                when {
                                    status.isUsbConnected && step6Active -> TelemetryEmerald
                                    step6Active -> ElectricCyan
                                    else -> ObsidianBorder
                                }
                            )
                        ) {
                            Text(
                                text = when {
                                    status.isUsbConnected && step6Active -> "Подключено ✅"
                                    step6Active -> "Готово ✅"
                                    else -> "Ожидает шаги 1–5 ⚠️"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    status.isUsbConnected && step6Active -> TelemetryEmerald
                                    step6Active -> ElectricCyan
                                    else -> AmberSignal
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Подключите телефон кабелем к USB-порту автомобиля и нажмите на всплывающем окне «ОК» (всегда открывать при подключении CarLife).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step6Active) TextSecondary else TextMuted
                    )

                    Text(
                        text = "Статус канала: ${runtimeState.lastProtocolEvent}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (step6Active) ElectricCyan else TextMuted
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onScanAndAttachUsb,
                            enabled = step6Active,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("step6_connect_usb_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TelemetryEmerald,
                                contentColor = Color(0xFF002411)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = CockpitIcons.Usb,
                                contentDescription = "Подключить USB AOA",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Подключить USB AOA",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenLauncherTab,
                            modifier = Modifier.testTag("step6_open_launcher_btn"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ElectricCyan)
                        ) {
                            Text("Лаунчер ГУ", color = ElectricCyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistStepCard(
    stepNumber: Int,
    title: String,
    isCompleted: Boolean,
    description: String,
    primaryButtonText: String,
    testTag: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(
            1.dp,
            if (isCompleted) TelemetryEmerald.copy(alpha = 0.8f) else AmberSignal.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadgePill(isCompleted = isCompleted)
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            if (isCompleted) {
                OutlinedButton(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(testTag),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TelemetryEmerald.copy(alpha = 0.5f))
                ) {
                    Text(primaryButtonText, color = TelemetryEmerald)
                }
            } else {
                Button(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(testTag),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF002227)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = primaryButtonText,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (secondaryButtonText != null && onSecondaryClick != null) {
                OutlinedButton(
                    onClick = onSecondaryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTag}_secondary"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AmberSignal.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = secondaryButtonText,
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberSignal
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadgePill(isCompleted: Boolean) {
    Surface(
        color = if (isCompleted) TelemetryEmerald.copy(alpha = 0.18f) else AmberSignal.copy(alpha = 0.18f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (isCompleted) TelemetryEmerald else AmberSignal)
    ) {
        Text(
            text = if (isCompleted) "Выполнено ✅" else "Настроить ⚠️",
            style = MaterialTheme.typography.labelSmall,
            color = if (isCompleted) TelemetryEmerald else AmberSignal,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MirroringQuickControlStrip(
    isProjectionRunning: Boolean,
    virtualDisplayId: Int,
    a11yEnabled: Boolean,
    overlayGranted: Boolean,
    onToggleProjection: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = ObsidianSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isProjectionRunning) TelemetryEmerald else ElectricCyan)
                )
                Text(
                    text = if (isProjectionRunning) {
                        "VirtualDisplay #$virtualDisplayId Active (60fps H.264)"
                    } else {
                        "CarLife App Mirroring Ready"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusActionChip(
                    label = if (isProjectionRunning) "VD ON" else "Start VD",
                    active = isProjectionRunning,
                    icon = CockpitIcons.Videocam,
                    testTag = "quick_toggle_vd_btn",
                    onClick = onToggleProjection
                )
                StatusActionChip(
                    label = if (a11yEnabled) "Touch ON" else "Touch A11y",
                    active = a11yEnabled,
                    icon = CockpitIcons.TouchApp,
                    testTag = "quick_a11y_settings_btn",
                    onClick = onOpenAccessibilitySettings
                )
                StatusActionChip(
                    label = if (overlayGranted) "Overlay OK" else "Overlay",
                    active = overlayGranted,
                    icon = CockpitIcons.AppsGrid,
                    testTag = "quick_overlay_settings_btn",
                    onClick = onOpenOverlaySettings
                )
            }
        }
    }
}

@Composable
private fun StatusActionChip(
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
        color = if (active) TelemetryEmerald.copy(alpha = 0.18f) else ObsidianSurfaceHigh,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (active) TelemetryEmerald else AmberSignal.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) TelemetryEmerald else AmberSignal,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) TelemetryEmerald else AmberSignal
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
                    subValue = "VD #${proj.virtualDisplayId}",
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
                        onClick = { onInjectTouch(120, 180) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inject_touch_dock_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Touch Dock Zone")
                    }
                    FilledTonalButton(
                        onClick = { onInjectTouch(680, 360) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inject_touch_launcher_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Touch App Grid")
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

            // One-tap Video Framing Header Toggle for Head Unit compatibility
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
            else "IDLE • READY FOR BAIDU CARLIFE HEAD UNIT" to TextSecondary
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
                contentDescription = "CarLife USB AOA App Mirroring HUD",
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
                    text = "CarLife App Mirroring & Launcher",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "60fps H.264 VirtualDisplay • Full Android Apps + Multi-Touch Injection",
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
                            "HardKey ${inputEvent.mappedKey?.label} -> Android KeyCode ${inputEvent.androidKeyCode}"
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
                        text = "BAIDU CARLIFE USB AOA PROTOCOL LAB",
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
                            title = "4. HardKey",
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
