package com.example

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.protocol.carlife.HandshakeTestProfile
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
import com.example.ui.theme.SuccessBadgeBg
import com.example.ui.theme.SuccessBadgeFg
import com.example.ui.theme.TelemetryEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningBadgeBg
import com.example.ui.theme.WarningBadgeFg
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
                        safeStartService(this, stopIntent)
                    },
                    onStartProjection = { width, height, fps ->
                        if (!MediaProjectionTokenStore.hasValidToken.value) {
                            requestScreenCapturePermission()
                        } else {
                            val currentBitrate = BridgeTelemetryBus.runtimeState.value.configuredBitrateBps
                            val intent = Intent(this, BridgeForegroundService::class.java).apply {
                                action = BridgeForegroundService.ACTION_START_PROJECTION
                                putExtra(BridgeForegroundService.EXTRA_WIDTH, width)
                                putExtra(BridgeForegroundService.EXTRA_HEIGHT, height)
                                putExtra(BridgeForegroundService.EXTRA_FPS, fps)
                                putExtra(BridgeForegroundService.EXTRA_BITRATE, currentBitrate)
                            }
                            safeStartService(this, intent, foreground = true)
                        }
                    },
                    onStopProjection = {
                        val intent = Intent(this, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_STOP_PROJECTION
                        }
                        safeStartService(this, intent)
                    },
                    onInjectTouch = { x, y ->
                        val intent = Intent(this, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_INJECT_TEST_TOUCH
                            putExtra(BridgeForegroundService.EXTRA_TOUCH_X, x)
                            putExtra(BridgeForegroundService.EXTRA_TOUCH_Y, y)
                        }
                        safeStartService(this, intent)
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
            BridgeForegroundService.startWithAccessory(this, accessory)

            if (!MediaProjectionTokenStore.hasValidToken.value) {
                requestScreenCapturePermission()
            }
        }
    }
}

private fun safeStartService(
    context: Context,
    intent: Intent,
    foreground: Boolean = false
) {
    runCatching {
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
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

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(pkg) == true

    val overlayGranted = Settings.canDrawOverlays(context)

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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycleOwner = LocalLifecycleOwner.current

    val runtimeState by BridgeTelemetryBus.runtimeState.collectAsStateWithLifecycle()
    val mediaState by MediaControllerRepository.mediaState.collectAsStateWithLifecycle()
    val a11yEnabled by CarLifeTouchAccessibilityService.isServiceEnabled.collectAsStateWithLifecycle()
    val hasMediaProjectionToken by MediaProjectionTokenStore.hasValidToken.collectAsStateWithLifecycle()
    val lastLaunchStatus by CarLifeLauncherRepository.lastLaunchStatus.collectAsState()

    // 3 Tabs:
    // 0 = «Лаунчер ГУ» (Grid/Apps)
    // 1 = «Подключение» (Check/Shield — Setup Checklist)
    // 2 = «Настройки» (Settings — VD Controls, H.264 Stream Config & Expandable AOA/Protocol Lab)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var configuredWidth by rememberSaveable { mutableIntStateOf(800) }
    var configuredHeight by rememberSaveable { mutableIntStateOf(480) }
    var configuredBitrateKbps by rememberSaveable { mutableIntStateOf(800) }

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
        if (!runtimeState.isServiceRunning) {
            BridgeForegroundService.startDefaultProjection(context)
        }
    }

    val navBarColors = NavigationBarItemDefaults.colors(
        selectedIconColor = ElectricCyan,
        selectedTextColor = ElectricCyan,
        indicatorColor = ElectricCyan.copy(alpha = 0.16f),
        unselectedIconColor = TextSecondary,
        unselectedTextColor = TextSecondary
    )

    val navRailColors = NavigationRailItemDefaults.colors(
        selectedIconColor = ElectricCyan,
        selectedTextColor = ElectricCyan,
        indicatorColor = ElectricCyan.copy(alpha = 0.16f),
        unselectedIconColor = TextSecondary,
        unselectedTextColor = TextSecondary
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!isLandscape) {
                NavigationBar(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(64.dp),
                    containerColor = ObsidianSurface,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = navBarColors,
                        modifier = Modifier.testTag("nav_app_launcher"),
                        icon = {
                            Icon(
                                imageVector = CockpitIcons.AppsGrid,
                                contentDescription = "Лаунчер ГУ",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Лаунчер ГУ",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = navBarColors,
                        modifier = Modifier.testTag("nav_setup_checklist"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Подключение",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Подключение",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        colors = navBarColors,
                        modifier = Modifier.testTag("nav_settings"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Настройки",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Настройки",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLandscape) {
                NavigationRail(
                    modifier = Modifier
                        .width(72.dp)
                        .fillMaxHeight()
                        .border(1.dp, ObsidianBorder),
                    containerColor = ObsidianSurface,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    NavigationRailItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = navRailColors,
                        modifier = Modifier.testTag("nav_app_launcher"),
                        icon = {
                            Icon(
                                imageVector = CockpitIcons.AppsGrid,
                                contentDescription = "Лаунчер ГУ",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Лаунчер",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                maxLines = 1
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = navRailColors,
                        modifier = Modifier.testTag("nav_setup_checklist"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Подключение",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Чек-лист",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                maxLines = 1
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        colors = navRailColors,
                        modifier = Modifier.testTag("nav_settings"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Настройки",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Настройки",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                maxLines = 1
                            )
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
                when (selectedTab) {
                    0 -> CarLifeAppLauncherScreen(
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
                        modifier = Modifier.fillMaxSize()
                    )

                    1 -> OnboardingChecklistScreen(
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
                        onOpenLauncherTab = { selectedTab = 0 }
                    )

                    2 -> UnifiedSettingsAndDiagnosticsScreen(
                        state = runtimeState,
                        checklistStatus = checklistStatus,
                        lastLaunchStatus = lastLaunchStatus,
                        configuredWidth = runtimeState.configuredWidth,
                        configuredHeight = runtimeState.configuredHeight,
                        configuredBitrateKbps = runtimeState.configuredBitrateBps / 1_000,
                        onSelectResolution = { w, h ->
                            configuredWidth = w
                            configuredHeight = h
                            val currentBitrate = runtimeState.configuredBitrateBps
                            BridgeTelemetryBus.updateState {
                                it.copy(
                                    configuredWidth = w,
                                    configuredHeight = h,
                                    projectionStats = it.projectionStats.copy(width = w, height = h),
                                    controlSession = it.controlSession.copy(
                                        negotiatedVideoWidth = w,
                                        negotiatedVideoHeight = h
                                    )
                                )
                            }
                            CarLifeLauncherRepository.markLauncherActiveOnDisplay(
                                displayId = runtimeState.projectionStats.virtualDisplayId,
                                width = w,
                                height = h,
                                bitrateBps = currentBitrate
                            )
                            val cfgIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                action = BridgeForegroundService.ACTION_SET_VIDEO_CONFIG
                                putExtra(BridgeForegroundService.EXTRA_WIDTH, w)
                                putExtra(BridgeForegroundService.EXTRA_HEIGHT, h)
                                putExtra(BridgeForegroundService.EXTRA_BITRATE, currentBitrate)
                            }
                            safeStartService(context, cfgIntent)
                        },
                        onSelectBitrate = { kbps ->
                            configuredBitrateKbps = kbps
                            val bps = kbps * 1_000
                            val w = runtimeState.configuredWidth
                            val h = runtimeState.configuredHeight
                            BridgeTelemetryBus.updateState {
                                it.copy(configuredBitrateBps = bps)
                            }
                            CarLifeLauncherRepository.markLauncherActiveOnDisplay(
                                displayId = runtimeState.projectionStats.virtualDisplayId,
                                width = w,
                                height = h,
                                bitrateBps = bps
                            )
                            val cfgIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                action = BridgeForegroundService.ACTION_SET_VIDEO_CONFIG
                                putExtra(BridgeForegroundService.EXTRA_WIDTH, w)
                                putExtra(BridgeForegroundService.EXTRA_HEIGHT, h)
                                putExtra(BridgeForegroundService.EXTRA_BITRATE, bps)
                            }
                            safeStartService(context, cfgIntent)
                        },
                        onToggleProjection = {
                            if (runtimeState.projectionStats.isRunning) {
                                onStopProjection()
                            } else {
                                onStartProjection(runtimeState.configuredWidth, runtimeState.configuredHeight, 30)
                            }
                        },
                        onOpenAccessibilitySettings = {
                            CarLifeTouchAccessibilityService.openAccessibilitySettings(context)
                        },
                        onOpenOverlaySettings = {
                            openOverlayPermissionSettings(context)
                        },
                        onStartOrScanBridge = onStartOrScanBridge,
                        onStopBridge = onStopBridge,
                        onStartProjection = onStartProjection,
                        onStopProjection = onStopProjection,
                        onInjectTouch = onInjectTouch
                    )
                }
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Заголовочная карточка без пустого пространства сверху
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "МАСТЕР НАСТРОЙКИ CARLIFE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = ElectricCyan
                        )
                        Surface(
                            color = if (status.allPrerequisitesReady) SuccessBadgeBg else WarningBadgeBg,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${status.completedMandatorySteps} / 5 выполнено",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.allPrerequisitesReady) SuccessBadgeFg else WarningBadgeFg,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = if (status.allPrerequisitesReady) {
                            "Все 5 разрешений выданы ✅ Видеопоток H.264 (SPS/PPS + IDR) готов к передаче без зависания на 90%."
                        } else {
                            "Выполните 5 шагов подготовки перед подключением USB-кабеля к ГУ автомобиля, чтобы CarLife проходил загрузку 90% → 100%."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    LinearProgressIndicator(
                        progress = { status.completedMandatorySteps / 5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = ElectricCyan,
                        trackColor = ObsidianSurfaceHigh
                    )
                }
            }
        }

        // ШАГ 1: Снятие ограничений батареи
        item {
            ChecklistStepCard(
                title = "Шаг 1. Снятие ограничений батареи (WakeLock & 60fps)",
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
                title = "Шаг 3. Служба специальных возможностей (Тачскрин ГУ)",
                isCompleted = status.step3AccessibilityEnabled,
                description = "Откройте «Специальные возможности» -> «Установленные приложения» -> включите службу управления касаниями CarLife. (Если кнопка неактивна на Android 16: Настройки -> Приложения -> Наше приложение -> Меню ⋮ -> «Разрешить ограниченные настройки»).",
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
                border = BorderStroke(1.dp, ObsidianBorder)
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
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadgePill(isCompleted = status.step5MediaProjectionGranted)
                    }

                    Surface(
                        color = ObsidianSurfaceHigh,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                append("Нажмите «Начать трансляцию». ")
                                withStyle(
                                    SpanStyle(
                                        color = CoralAlert,
                                        fontWeight = FontWeight.SemiBold
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
                            containerColor = if (status.step5MediaProjectionGranted) ObsidianSurfaceHigh else ElectricCyan,
                            contentColor = if (status.step5MediaProjectionGranted) TextPrimary else Color(0xFF001D24)
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
                                "Трансляция «Весь экран» активна (Перезапустить)"
                            } else {
                                "Начать трансляцию (Выбрать «Весь экран»)"
                            },
                            fontWeight = FontWeight.SemiBold
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
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
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
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = if (step6Active) SuccessBadgeBg else WarningBadgeBg,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = when {
                                    status.isUsbConnected && step6Active -> "Подключено ✅"
                                    step6Active -> "Выполнено ✅"
                                    else -> "Настроить"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (step6Active) SuccessBadgeFg else WarningBadgeFg,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF001D24)
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
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenLauncherTab,
                            modifier = Modifier.testTag("step6_open_launcher_btn"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ObsidianBorder)
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
        border = BorderStroke(1.dp, ObsidianBorder)
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
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onPrimaryClick)
                        .testTag(testTag),
                    color = ObsidianSurfaceHigh,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Text(
                        text = primaryButtonText,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            } else {
                Button(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(testTag),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF001D24)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = primaryButtonText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (secondaryButtonText != null && onSecondaryClick != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSecondaryClick)
                        .testTag("${testTag}_secondary"),
                    color = ObsidianSurfaceHigh,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Text(
                        text = secondaryButtonText,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadgePill(isCompleted: Boolean) {
    Surface(
        color = if (isCompleted) SuccessBadgeBg else WarningBadgeBg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = if (isCompleted) "Выполнено ✅" else "Настроить",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold
            ),
            color = if (isCompleted) SuccessBadgeFg else WarningBadgeFg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Tab 3 («Настройки»):
 * 1. Управление виртуальным экраном и статусами (`Start VD`, `Touch ON`, `Overlay OK`, лог запущенного пакета).
 * 2. Настройки H.264 видеопотока (разрешение 1280x720 / 1920x1080, битрейт, 60 FPS).
 * 3. Сворачиваемый раздел (Expandable Card) «Инженерная диагностика (AOA & Protocol Lab)».
 */
@Composable
private fun UnifiedSettingsAndDiagnosticsScreen(
    state: BridgeRuntimeState,
    checklistStatus: SetupChecklistStatus,
    lastLaunchStatus: String,
    configuredWidth: Int,
    configuredHeight: Int,
    configuredBitrateKbps: Int,
    onSelectResolution: (Int, Int) -> Unit,
    onSelectBitrate: (Int) -> Unit,
    onToggleProjection: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStartOrScanBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit,
    onInjectTouch: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localStateMachine = remember { CarLifeControlStateMachine() }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    // Tab 0 (default): «Команды 0x0001 & 0x0006 (RX/TX)»
    // Tab 1: «Видеопоток 0x0002»
    var selectedLogTab by rememberSaveable { mutableIntStateOf(0) }
    var lastDecodedBreakdown by remember {
        mutableStateOf(
            "Выберите тестовый вектор ниже для проверки бинарного кадра через CarLifeFrameCodec и декодер тачскрина."
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
                val tsPart = if (decoded.header.includeOuterTimestamp && rawHeader.size >= 12) {
                    val tsHex = rawHeader.sliceArray(8..11).joinToString(" ") { "%02X".format(it) }
                    "\n• Bytes [8..11] Timestamp: [$tsHex] -> ${decoded.header.timestamp} ms"
                } else if (decoded.channel == CarLifeChannel.VIDEO) {
                    "\n• Video Framing: AOA 20B (8B AOA + 12B 0x00028001) | ts=${decoded.header.timestamp} ms"
                } else {
                    "\n• Timestamp Field: Omitted (8-byte Standard Channel Header)"
                }
                val cmdSub = decoded.parseCommandMessageOrNull()?.let {
                    "\n• Inner Header: len=${it.body.size}B | service=${it.serviceName} (0x${it.serviceType.toUInt().toString(16).uppercase().padStart(8, '0')})"
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
        // 1. Блок управления виртуальным экраном и статусами (Start VD, Touch ON, Overlay OK + лог запуска)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Виртуальный экран ГУ (VirtualDisplay)",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = if (state.projectionStats.isRunning) SuccessBadgeBg else ObsidianSurfaceHigh,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (state.projectionStats.isRunning) {
                                    "VD #${state.projectionStats.virtualDisplayId} АКТИВЕН"
                                } else {
                                    "VD ВЫКЛ"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = if (state.projectionStats.isRunning) SuccessBadgeFg else TextSecondary,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusActionChip(
                            label = if (state.projectionStats.isRunning) "VD ON" else "Start VD",
                            active = state.projectionStats.isRunning,
                            icon = CockpitIcons.Videocam,
                            testTag = "quick_toggle_vd_btn",
                            onClick = onToggleProjection,
                            modifier = Modifier.weight(1f)
                        )
                        StatusActionChip(
                            label = if (checklistStatus.step3AccessibilityEnabled) "Touch ON" else "Touch A11y",
                            active = checklistStatus.step3AccessibilityEnabled,
                            icon = CockpitIcons.TouchApp,
                            testTag = "quick_a11y_settings_btn",
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.weight(1f)
                        )
                        StatusActionChip(
                            label = if (checklistStatus.step2OverlayGranted) "Overlay OK" else "Overlay",
                            active = checklistStatus.step2OverlayGranted,
                            icon = CockpitIcons.AppsGrid,
                            testTag = "quick_overlay_settings_btn",
                            onClick = onOpenOverlaySettings,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Surface(
                        color = ObsidianBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        val vdStatusLine = if (state.projectionStats.isRunning && !lastLaunchStatus.startsWith("Запущено:")) {
                            "Лаунчер ГУ активен на VD #${state.projectionStats.virtualDisplayId} (${state.configuredWidth}×${state.configuredHeight} @ ${state.configuredBitrateBps / 1000} кбит/с)"
                        } else if (!state.projectionStats.isRunning) {
                            "Готов к трансляции: выбрано разрешение ${state.configuredWidth}×${state.configuredHeight} @ ${state.configuredBitrateBps / 1000} кбит/с"
                        } else {
                            lastLaunchStatus
                        }
                        Text(
                            text = vdStatusLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.projectionStats.isRunning) TelemetryEmerald else TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 1.5 Карточка «Тест рукопожатия 0x0001» (4 строгих профиля ответа на MSG_CMD_HU_PROTOCOL_VERSION)
        item {
            val activeProfile = state.handshakeTestProfile
            fun selectHandshakeProfile(profile: HandshakeTestProfile) {
                BridgeTelemetryBus.setHandshakeTestProfile(profile)
                if (state.isServiceRunning) {
                    val intent = Intent(context, BridgeForegroundService::class.java).apply {
                        action = BridgeForegroundService.ACTION_SET_HANDSHAKE_PROFILE
                        putExtra(BridgeForegroundService.EXTRA_HANDSHAKE_PROFILE_ID, profile.id)
                    }
                    safeStartService(context, intent)
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("handshake_lab_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f))
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Тест рукопожатия 0x0001",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Режим ответа на MSG_CMD_HU_PROTOCOL_VERSION (0x00018001) от ГУ Jetta VS7",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = ElectricCyan.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f))
                        ) {
                            Text(
                                text = "ТЕСТ ${activeProfile.id}/4",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = ElectricCyan,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    HandshakeTestProfile.entries.forEach { profile ->
                        val isSelected = activeProfile == profile
                        val rowBg = if (isSelected) {
                            ElectricCyan.copy(alpha = 0.12f)
                        } else {
                            ObsidianBackground
                        }
                        val rowBorder = if (isSelected) {
                            ElectricCyan
                        } else {
                            ObsidianBorder
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(rowBg)
                                .border(1.dp, rowBorder, RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectHandshakeProfile(profile) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("handshake_profile_${profile.id}_btn"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = ElectricCyan,
                                    unselectedColor = TextMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = profile.shortTitle,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) ElectricCyan else TextPrimary
                                )
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Настройки H.264 видеопотока (разрешение 800x480 / 1024x600 / 1280x720, битрейт 1.2 Мбит/с CBR 30fps, Direct 12B по умолчанию)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Параметры видеопотока H.264",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = ObsidianSurfaceHigh,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ObsidianBorder)
                        ) {
                            Text(
                                text = "Baseline CBR • 30fps • I=1s",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Разрешение виртуального дисплея и кодека (800×480 по умолчанию для Jetta VS7)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SelectableOptionButton(
                            label = "800 × 480",
                            selected = configuredWidth == 800,
                            testTag = "res_480p_btn",
                            onClick = { onSelectResolution(800, 480) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableOptionButton(
                            label = "1024 × 600",
                            selected = configuredWidth == 1024,
                            testTag = "res_600p_btn",
                            onClick = { onSelectResolution(1024, 600) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableOptionButton(
                            label = "1280 × 720",
                            selected = configuredWidth == 1280,
                            testTag = "res_720p_btn",
                            onClick = { onSelectResolution(1280, 720) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "Битрейт кодека (AVCProfileBaseline CBR, 30fps, I=1s, 1.2 Мбит/с по умолчанию)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SelectableOptionButton(
                            label = "800 кбит/с",
                            selected = configuredBitrateKbps == 800,
                            testTag = "bitrate_2m_btn",
                            onClick = { onSelectBitrate(800) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableOptionButton(
                            label = "1.2 Мбит/с",
                            selected = configuredBitrateKbps == 1200,
                            testTag = "bitrate_4m_btn",
                            onClick = { onSelectBitrate(1200) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableOptionButton(
                            label = "1.8 Мбит/с",
                            selected = configuredBitrateKbps == 1800,
                            testTag = "bitrate_6m_btn",
                            onClick = { onSelectBitrate(1800) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "Формат шапки видеоканала 0x0002 (Direct 12B + Annex-B по умолчанию)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    fun applyVideoHeaderMode(useAoA20B: Boolean) {
                        CarLifeFrameCodec.useNestedVideoMsgHeader = useAoA20B
                        BridgeTelemetryBus.updateState {
                            it.copy(
                                useNestedVideoMsgHeader = useAoA20B,
                                lastProtocolEvent = if (useAoA20B) {
                                    "Video 0x0002 Framing: Режим 1: AOA 20B (8B AOA + 12B 0x00028001) active"
                                } else {
                                    "Video 0x0002 Framing: Режим 2: Direct 12B ([00 00 00 02][nalSize][ts]) active"
                                }
                            )
                        }
                        if (state.isServiceRunning) {
                            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                                action = BridgeForegroundService.ACTION_TOGGLE_VIDEO_HEADER_MODE
                                putExtra(BridgeForegroundService.EXTRA_USE_NESTED_VIDEO_HEADER, useAoA20B)
                            }
                            safeStartService(context, intent)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SelectableOptionButton(
                            label = "Режим 1: AOA 20B",
                            selected = state.useNestedVideoMsgHeader,
                            testTag = "toggle_video_header_mode_btn",
                            onClick = { applyVideoHeaderMode(true) },
                            modifier = Modifier.weight(1.1f)
                        )
                        SelectableOptionButton(
                            label = "Режим 2: Direct 12B",
                            selected = !state.useNestedVideoMsgHeader,
                            testTag = "video_header_legacy_12b_btn",
                            onClick = { applyVideoHeaderMode(false) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2.5 Зафиксированный рабочий профиль Jetta VS7 (цикл авто-тестера отключён)
        item {
            val autoTest = state.autoTestState
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auto_test_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(width = 1.dp, color = TelemetryEmerald)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Профиль Jetta VS7 (Direct 12B + Audio Init)",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "При получении 0x00018001 отправляются MATCH_STATUS (0x00010002), MD_INFO (0x00010004), Audio-Init (0x0003, 0x0004), INIT_DONE (0x00010008), START_DONE (0x0001000A) и раздельные NAL в Direct 12B.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = SuccessBadgeBg,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, TelemetryEmerald)
                        ) {
                            Text(
                                text = "JETTA VS7",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = SuccessBadgeFg,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        color = TelemetryEmerald.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, TelemetryEmerald.copy(alpha = 0.65f))
                    ) {
                        Text(
                            text = autoTest.statusMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = TelemetryEmerald,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }

                    Button(
                        onClick = {
                            BridgeForegroundService.startDefaultProjection(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("start_auto_test_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF001D24)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Зафиксировать профиль Jetta VS7",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Стартовать VD (800×480 • CBR • 30fps)",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 3. Сворачиваемый раздел (Expandable Card) «Инженерная диагностика (AOA & Protocol Lab)»
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { diagnosticsExpanded = !diagnosticsExpanded }
                            .padding(vertical = 4.dp)
                            .testTag("toggle_engineering_diagnostics_btn"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Инженерная диагностика (AOA & Protocol Lab)",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Статистика USB AOA, счетчики каналов 0x0001..0x0006 и генератор тестовых пакетов",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = if (diagnosticsExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = "Развернуть инженерную диагностику",
                            tint = ElectricCyan
                        )
                    }

                    AnimatedVisibility(visible = diagnosticsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProjectionEngineCard(
                                state = state,
                                onStartProjection = onStartProjection,
                                onStopProjection = onStopProjection,
                                onInjectTouch = onInjectTouch
                            )

                            TransportControlsCard(
                                state = state,
                                onStartOrScanBridge = onStartOrScanBridge,
                                onStopBridge = onStopBridge
                            )

                            ChannelMultiplexerGrid(channelCounts = state.channelFrameCounts)

                            // Protocol Lab test vectors
                            Surface(
                                color = ObsidianSurfaceHigh,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, ObsidianBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Тестовые векторы протокола Baidu CarLife",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ElectricCyan
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
                                                    commandBody = CarlifeVideoEncoderInfoProto(800, 480, 30).encode()
                                                )
                                                runLoopbackCodecTest(versionFrame, "Control 0x0001: MSG_CMD_HU_PROTOCOL_VERSION (v2.0)")
                                                runLoopbackCodecTest(videoInitFrame, "Control 0x0001: MSG_CMD_VIDEO_ENCODER_INIT (800x480@30)")
                                            }
                                        )

                                        CodecTestButton(
                                            title = "2. Video (0x0002)",
                                            subtitle = "H.264 SPS/PPS + IDR",
                                            icon = CockpitIcons.Videocam,
                                            accent = TelemetryEmerald,
                                            testTag = "test_video_frame_btn",
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                val h264SpsPps = byteArrayOf(
                                                    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F,
                                                    0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()
                                                )
                                                val frame = CarLifeFrameCodec.createVideoFrame(
                                                    nalData = h264SpsPps,
                                                    timestampMs = System.currentTimeMillis() and 0x7FFF_FFFFL
                                                )
                                                selectedLogTab = 1
                                                runLoopbackCodecTest(frame, "Video 0x0002: H.264 SPS/PPS NAL")
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
                                                selectedLogTab = 0
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
                                                selectedLogTab = 0
                                                runLoopbackCodecTest(frame, "Touch 0x0006: MSG_TOUCH_CAR_HARD_KEY_CODE (SEEK_ADD)")
                                            }
                                        )
                                    }

                                    Surface(
                                        color = ObsidianBackground,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, ObsidianBorder)
                                    ) {
                                        Text(
                                            text = lastDecodedBreakdown,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFamily,
                                            color = TextPrimary,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Журнал бинарных пакетов",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ElectricCyan,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { BridgeTelemetryBus.clearLogs() },
                                    modifier = Modifier.testTag("clear_logs_button")
                                ) {
                                    Icon(
                                        imageVector = CockpitIcons.ClearAll,
                                        contentDescription = "Очистить логи",
                                        tint = TextSecondary
                                    )
                                }
                            }

                            // Задача 5: Переключатель из 2 независимых вкладок журнала
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SelectableOptionButton(
                                    label = "Команды 0x0001 & 0x0006 (${state.commandPacketLogs.size})",
                                    selected = selectedLogTab == 0,
                                    testTag = "filter_rx_commands_chip",
                                    onClick = { selectedLogTab = 0 },
                                    modifier = Modifier.weight(1.25f)
                                )
                                SelectableOptionButton(
                                    label = "Видеопоток 0x0002 (${state.videoPacketLogs.size})",
                                    selected = selectedLogTab == 1,
                                    testTag = "filter_all_packets_chip",
                                    onClick = { selectedLogTab = 1 },
                                    modifier = Modifier.weight(0.95f)
                                )
                            }

                            if (selectedLogTab == 1) {
                                val videoFramesCount = state.channelFrameCounts[CarLifeChannel.VIDEO] ?: 0L
                                val hdrModeLabel = if (state.useNestedVideoMsgHeader) {
                                    "Режим 1: AOA 20B (8B AOA + 12B 0x00028001)"
                                } else {
                                    "Режим 2: Direct 12B ([0x0002][nalSize][ts])"
                                }
                                val gateStatusLabel = if (state.isVideoChannelReadyForStreaming) {
                                    "Шлюз 0x0002: ОТКРЫТ (поток 0x0002 активен)"
                                } else {
                                    "Шлюз 0x0002: ЗАКРЫТ (ожидание 0x00018001 / 0x00018007 от ГУ)"
                                }
                                Surface(
                                    color = ObsidianSurfaceHigh,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, ObsidianBorder)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Статистика видеоканала 0x0002 • $hdrModeLabel",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TelemetryEmerald
                                        )
                                        Text(
                                            text = "$gateStatusLabel • Кадров 0x0002: $videoFramesCount • Кодек: ${state.projectionStats.width}×${state.projectionStats.height}@${state.projectionStats.fps}fps (${state.configuredBitrateBps / 1000} kbps) • IDR: ${state.projectionStats.lastIdrBytes}B",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFamily,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (diagnosticsExpanded) {
            val displayedLogs = if (selectedLogTab == 0) {
                state.commandPacketLogs
            } else {
                state.videoPacketLogs
            }

            if (displayedLogs.isEmpty()) {
                item {
                    EmptyPacketLogCard()
                }
            } else {
                items(displayedLogs, key = { it.id }) { entry ->
                    PacketLogItemCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun SelectableOptionButton(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (selected) ElectricCyan.copy(alpha = 0.18f) else ObsidianSurfaceHigh,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) ElectricCyan else ObsidianBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) ElectricCyan else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusActionChip(
    label: String,
    active: Boolean,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (active) SuccessBadgeBg else WarningBadgeBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) SuccessBadgeFg else WarningBadgeFg,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (active) SuccessBadgeFg else WarningBadgeFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurfaceHigh,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Конвейер VirtualDisplay + H.264",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = if (proj.spsPpsConfigured) "SPS/PPS LOCKED" else "AVC Baseline",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    value = "${state.configuredWidth}×${state.configuredHeight}",
                    subValue = "${proj.fps} FPS",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!proj.isRunning) {
                    Button(
                        onClick = { onStartProjection(state.configuredWidth, state.configuredHeight, 30) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_projection_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF001D24)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start VirtualDisplay",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Запустить VirtualDisplay + H.264", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    FilledTonalButton(
                        onClick = { onInjectTouch(120, 180) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inject_touch_dock_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Тест тача (Док)")
                    }
                    FilledTonalButton(
                        onClick = { onInjectTouch(680, 360) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inject_touch_launcher_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Тест тача (Сетка)")
                    }
                    OutlinedButton(
                        onClick = onStopProjection,
                        modifier = Modifier.testTag("stop_projection_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text("Стоп", color = CoralAlert)
                    }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurfaceHigh,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Транспорт USB AOA",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = if (state.wakeLockHeld) "WakeLock: ACTIVE" else "DirectBufferPool",
                    style = MaterialTheme.typography.labelSmall,
                    color = TelemetryEmerald
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill(
                    label = "RX КАДРЫ",
                    value = "${state.rxFrames}",
                    subValue = "${state.rxBytes} B",
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "TX КАДРЫ",
                    value = "${state.txFrames}",
                    subValue = "${state.txBytes} B",
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "СТАТУС",
                    value = state.controlSession.phase.name.take(8),
                    subValue = "${state.controlSession.negotiatedFps} FPS",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartOrScanBridge,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_connect_usb_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF001D24)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan USB AOA",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Scan & Attach USB AOA",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state.isServiceRunning) {
                    OutlinedButton(
                        onClick = onStopBridge,
                        modifier = Modifier.testTag("stop_bridge_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Icon(
                            imageVector = CockpitIcons.Stop,
                            contentDescription = "Stop Service",
                            tint = CoralAlert,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Стоп", color = CoralAlert)
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
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianSurface)
            .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
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
            style = MaterialTheme.typography.titleMedium,
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurfaceHigh,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Каналы мультиплексора CarLife (0x0001 .. 0x0006)",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )

            val pairs = CarLifeChannel.entries.chunked(2)
            for (rowChannels in pairs) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (channel in rowChannels) {
                        val count = channelCounts[channel] ?: 0L
                        val badgeColor = if (channel.hasTimestampHeader) TelemetryEmerald else ElectricCyan
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianSurface)
                                .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "0x%04X".format(channel.id),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = badgeColor
                                )
                                Text(
                                    text = "${channel.headerSizeBytes}B HDR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = channel.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "$count пакетов",
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
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = ObsidianSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
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
                modifier = Modifier.fillMaxWidth()
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
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = CockpitIcons.Cable,
                contentDescription = "No packets yet",
                tint = ElectricCyan,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "Нет захваченных кадров CarLife",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = "Нажмите на любой тестовый вектор выше для проверки бинарного протокола.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}
