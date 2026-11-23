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
import android.media.projection.MediaProjectionConfig
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Brush
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import com.example.core.input.CarLifeAccessibilityService
import com.example.core.input.TouchSettingsPreferences
import com.example.core.projection.AppMode
import com.example.core.projection.MediaProjectionTokenStore
import com.example.core.projection.ScreenKeepAlivePreferences
import com.example.core.projection.VideoSourceMode
import com.example.core.shizuku.CarLifeShizukuManager
import com.example.ui.settings.ScreenKeepAliveSettingsCard
import com.example.ui.settings.TouchSensitivitySettingsCard
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeRuntimeState
import com.example.core.usb.BridgeTelemetryBus
import com.example.core.usb.PacketLogEntry
import com.example.core.usb.UsbTransportState
import com.example.feature.cockpit.AppLaunchMode
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
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.theme.resolveIsDarkTheme
import com.example.ui.theme.tabular
import com.example.ui.theme.vwColors
import java.io.PipedInputStream
import com.example.core.audio.CarLifeAudioCaptureManager
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        ThemePreferences.init(this)
        TouchSettingsPreferences.init(this)
        ScreenKeepAlivePreferences.init(this)
        BridgeTelemetryBus.restoreSavedAppMode(this)

        recordAudioPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                val audioIntent = Intent(this, BridgeForegroundService::class.java).apply {
                    action = BridgeForegroundService.ACTION_START_AUDIO_CAPTURE
                }
                safeStartService(this, audioIntent)
            }
        }

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
                if (!CarLifeAudioCaptureManager.hasRecordAudioPermission(this)) {
                    requestRecordAudioPermission()
                }
            } else {
                val deniedMsg = "Разрешение MediaProjection («Весь экран») отклонено пользователем (code=${result.resultCode})"
                MediaProjectionTokenStore.recordError(deniedMsg)
                BridgeTelemetryBus.updateState { current ->
                    current.copy(
                        lastProtocolEvent = deniedMsg,
                        projectionStats = current.projectionStats.copy(
                            mediaProjectionState = "Отклонено",
                            lastMediaProjectionError = deniedMsg
                        )
                    )
                }
            }
        }

        CarLifeShizukuManager.init(this)
        MediaControllerRepository.initialize(this)
        handleUsbAccessoryIntent(intent)

        setContent {
            val activeThemeMode by ThemePreferences.themeMode.collectAsState()
            val isDarkTheme = resolveIsDarkTheme(activeThemeMode)
            SideEffect {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !isDarkTheme
                insetsController.isAppearanceLightNavigationBars = !isDarkTheme
            }
            MyApplicationTheme(themeMode = activeThemeMode) {
                CarLifeBridgeApp(
                    onRequestMediaProjection = {
                        requestScreenCapturePermission()
                    },
                    onRequestRecordAudioPermission = {
                        requestRecordAudioPermission()
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
        val snapshot = CarLifeShizukuManager.refreshStatus(this)
        if (BridgeTelemetryBus.runtimeState.value.appMode == AppMode.SHIZUKU_COCKPIT &&
            snapshot.isBinderAlive &&
            snapshot.hasPermission
        ) {
            CarLifeShizukuManager.bindUserServiceIfReady(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAccessoryIntent(intent)
    }

    private fun requestScreenCapturePermission() {
        runCatching {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                mpManager.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(captureIntent)
        }.onFailure { e ->
            val errMsg = "Не удалось открыть запрос MediaProjection («Весь экран»): ${e.message}"
            MediaProjectionTokenStore.recordError(errMsg)
            BridgeTelemetryBus.updateState { current ->
                current.copy(
                    lastProtocolEvent = errMsg,
                    projectionStats = current.projectionStats.copy(
                        mediaProjectionState = "Ошибка запуска запроса",
                        lastMediaProjectionError = errMsg
                    )
                )
            }
        }
    }

    private fun requestRecordAudioPermission() {
        if (CarLifeAudioCaptureManager.hasRecordAudioPermission(this)) {
            val audioIntent = Intent(this, BridgeForegroundService::class.java).apply {
                action = BridgeForegroundService.ACTION_START_AUDIO_CAPTURE
            }
            safeStartService(this, audioIntent)
        } else {
            runCatching {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
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

    val accessibilityEnabled = a11yRealtimeFlag ||
        CarLifeAccessibilityService.isEnabledInSettings(context)

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
    onRequestRecordAudioPermission: () -> Unit = {},
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
    val a11yEnabled by CarLifeAccessibilityService.isServiceEnabled.collectAsStateWithLifecycle()
    val hasMediaProjectionToken by MediaProjectionTokenStore.hasValidToken.collectAsStateWithLifecycle()
    val lastLaunchStatus by CarLifeLauncherRepository.lastLaunchStatus.collectAsState()

    // 3 Tabs:
    // 0 = «Лаунчер ГУ» (Grid/Apps)
    // 1 = «Подключение» (Check/Shield — Setup Checklist)
    // 2 = «Настройки» (Settings — VD Controls, H.264 Stream Config & Expandable AOA/Protocol Lab)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var configuredWidth by rememberSaveable { mutableIntStateOf(1280) }
    var configuredHeight by rememberSaveable { mutableIntStateOf(720) }
    var configuredBitrateKbps by rememberSaveable { mutableIntStateOf(2000) }

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

    LaunchedEffect(selectedTab, a11yEnabled, hasMediaProjectionToken, runtimeState.transportState) {
        while (isActive) {
            val latest = evaluateSetupChecklist(
                context = context,
                a11yRealtimeFlag = a11yEnabled,
                hasMediaProjectionToken = hasMediaProjectionToken,
                transportState = runtimeState.transportState
            )
            checklistStatus = latest
            // Avoid unnecessary 1s Binder IPC polling when setup is complete and user is on Launcher/Settings tabs
            if (selectedTab != 1 && latest.allPrerequisitesReady) {
                break
            }
            delay(if (selectedTab == 1) 1_000L else 3_000L)
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

    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!isLandscape) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colorScheme.surface,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colorScheme.outline)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VwBottomNavItem(
                                label = "Лаунчер",
                                icon = CockpitIcons.AppsGrid,
                                selected = selectedTab == 0,
                                testTag = "nav_app_launcher",
                                onClick = { selectedTab = 0 },
                                modifier = Modifier.weight(1f)
                            )
                            VwBottomNavItem(
                                label = "Подключение",
                                icon = Icons.Default.CheckCircle,
                                selected = selectedTab == 1,
                                testTag = "nav_setup_checklist",
                                onClick = { selectedTab = 1 },
                                modifier = Modifier.weight(1f)
                            )
                            VwBottomNavItem(
                                label = "Настройки",
                                icon = Icons.Default.Settings,
                                selected = selectedTab == 2,
                                testTag = "nav_settings",
                                onClick = { selectedTab = 2 },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
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
                Surface(
                    modifier = Modifier
                        .width(76.dp)
                        .fillMaxHeight(),
                    color = colorScheme.surface,
                    shadowElevation = 0.dp
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            VwRailNavItem(
                                label = "Лаунчер",
                                icon = CockpitIcons.AppsGrid,
                                selected = selectedTab == 0,
                                testTag = "nav_app_launcher",
                                onClick = { selectedTab = 0 }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            VwRailNavItem(
                                label = "Подключ.",
                                icon = Icons.Default.CheckCircle,
                                selected = selectedTab == 1,
                                testTag = "nav_setup_checklist",
                                onClick = { selectedTab = 1 }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            VwRailNavItem(
                                label = "Настройки",
                                icon = Icons.Default.Settings,
                                selected = selectedTab == 2,
                                testTag = "nav_settings",
                                onClick = { selectedTab = 2 }
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(colorScheme.outline)
                        )
                    }
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
                        onHomePressed = {
                            if (runtimeState.appMode == AppMode.SHIZUKU_COCKPIT) {
                                CarLifeShizukuManager.sendKeyEventAsync(
                                    KeyEvent.KEYCODE_HOME,
                                    runtimeState.projectionStats.virtualDisplayId
                                )
                            } else {
                                CarLifeAccessibilityService.instance?.performHomeAction()
                            }
                        },
                        onBackPressed = {
                            if (runtimeState.appMode == AppMode.SHIZUKU_COCKPIT) {
                                CarLifeShizukuManager.sendKeyEventAsync(
                                    KeyEvent.KEYCODE_BACK,
                                    runtimeState.projectionStats.virtualDisplayId
                                )
                            } else {
                                CarLifeAccessibilityService.instance?.performBackAction()
                            }
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
                            CarLifeAccessibilityService.openAccessibilitySettings(context)
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
                            CarLifeAccessibilityService.openAccessibilitySettings(context)
                        },
                        onOpenOverlaySettings = {
                            openOverlayPermissionSettings(context)
                        },
                        onStartOrScanBridge = onStartOrScanBridge,
                        onStopBridge = onStopBridge,
                        onStartProjection = onStartProjection,
                        onStopProjection = onStopProjection,
                        onInjectTouch = onInjectTouch,
                        onRequestMediaProjection = onRequestMediaProjection,
                        onRequestRecordAudioPermission = onRequestRecordAudioPermission
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
private fun VwBottomNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "vw_nav_item_color"
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "vw_nav_indicator_color"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(indicatorColor)
        )
    }
}

@Composable
private fun VwRailNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "vw_rail_item_color"
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "vw_rail_indicator_color"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(indicatorColor)
        )
    }
}

private enum class VwBadgeTone {
    ACTIVE,
    WAITING,
    WARNING,
    ERROR,
    NEUTRAL
}

@Composable
private fun VwStatusBadge(
    label: String,
    tone: VwBadgeTone,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val (bg, fg, dot) = when (tone) {
        VwBadgeTone.ACTIVE -> Triple(vw.successContainer, vw.onSuccessContainer, vw.statusActiveDot)
        VwBadgeTone.WAITING -> Triple(vw.infoContainer, vw.onInfoContainer, vw.statusWaitingDot)
        VwBadgeTone.WARNING -> Triple(vw.warningContainer, vw.onWarningContainer, vw.statusWarningDot)
        VwBadgeTone.ERROR -> Triple(vw.errorContainerSoft, vw.onErrorContainerSoft, vw.statusErrorDot)
        VwBadgeTone.NEUTRAL -> Triple(colorScheme.surfaceVariant, colorScheme.onSurfaceVariant, colorScheme.onSurfaceVariant)
    }

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, dot.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.tabular().copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = fg,
                softWrap = false,
                maxLines = 1
            )
        }
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
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Заголовочная карточка мастера настройки Jetta VS7
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                text = "JETTA VS7 • ПОДКЛЮЧЕНИЕ",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = vw.accentBlue
                            )
                            Text(
                                text = "Мастер настройки CarLife",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = "${status.completedMandatorySteps} / 5",
                            tone = if (status.allPrerequisitesReady) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
                        )
                    }

                    Text(
                        text = if (status.allPrerequisitesReady) {
                            "Все 5 разрешений выданы. Видеопоток H.264 (SPS/PPS + IDR) готов к передаче без зависания на 90%."
                        } else {
                            "Выполните 5 шагов подготовки перед подключением USB-кабеля к ГУ автомобиля, чтобы CarLife проходил загрузку 90% → 100%."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )

                    LinearProgressIndicator(
                        progress = { status.completedMandatorySteps / 5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = colorScheme.primary,
                        trackColor = colorScheme.surfaceVariant
                    )
                }
            }
        }

        // ШАГ 1: Снятие ограничений батареи
        item {
            ChecklistStepCard(
                title = "Шаг 1. Снятие ограничений батареи (WakeLock & 30fps)",
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
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadgePill(isCompleted = status.step5MediaProjectionGranted)
                    }

                    Surface(
                        color = colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                append("Нажмите «Начать трансляцию». ")
                                withStyle(
                                    SpanStyle(
                                        color = colorScheme.error,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) {
                                    append(
                                        "ВНИМАНИЕ: В системном окне Samsung обязательно выберите «Весь экран» (Entire Screen), а НЕ «Одно приложение», иначе загрузка на ГУ зависнет на 90%!"
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    val mpDetail by MediaProjectionTokenStore.statusDetail.collectAsStateWithLifecycle()
                    val mpError by MediaProjectionTokenStore.lastError.collectAsStateWithLifecycle()
                    val effectiveMpError = mpError ?: runtimeState.projectionStats.lastMediaProjectionError

                    Surface(
                        color = colorScheme.background,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (effectiveMpError != null) colorScheme.error.copy(alpha = 0.7f) else colorScheme.outline
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Диагностика MP: ${runtimeState.projectionStats.mediaProjectionState} • $mpDetail",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = if (status.step5MediaProjectionGranted) vw.onSuccessContainer else vw.onInfoContainer
                            )
                            if (!effectiveMpError.isNullOrBlank()) {
                                Text(
                                    text = "Ошибка: $effectiveMpError",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.error
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onRequestMediaProjection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("step5_mediaprojection_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (status.step5MediaProjectionGranted) colorScheme.surfaceVariant else colorScheme.primary,
                            contentColor = if (status.step5MediaProjectionGranted) colorScheme.onSurface else colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
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
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                            color = if (step6Active) colorScheme.onSurface else vw.textMuted,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = when {
                                status.isUsbConnected && step6Active -> "Подключено"
                                step6Active -> "Готово"
                                else -> "Ожидание"
                            },
                            tone = if (step6Active) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
                        )
                    }

                    Text(
                        text = "Подключите телефон кабелем к USB-порту автомобиля и нажмите на всплывающем окне «ОК» (всегда открывать при подключении CarLife).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step6Active) colorScheme.onSurfaceVariant else vw.textMuted
                    )

                    Text(
                        text = "Статус канала: ${runtimeState.lastProtocolEvent}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = if (step6Active) vw.accentBlue else vw.textMuted
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
                                .heightIn(min = 48.dp)
                                .testTag("step6_connect_usb_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.primary,
                                contentColor = colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("step6_open_launcher_btn"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, colorScheme.outline)
                        ) {
                            Text("Лаунчер", color = colorScheme.primary)
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
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
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
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadgePill(isCompleted = isCompleted)
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )

            if (isCompleted) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onPrimaryClick)
                        .testTag(testTag),
                    color = colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colorScheme.outline)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = primaryButtonText,
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurface
                        )
                    }
                }
            } else {
                Button(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(testTag),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary
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
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSecondaryClick)
                        .testTag("${testTag}_secondary"),
                    color = colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colorScheme.outline)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = secondaryButtonText,
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadgePill(isCompleted: Boolean) {
    VwStatusBadge(
        label = if (isCompleted) "Активно" else "Настроить",
        tone = if (isCompleted) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
    )
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
    onInjectTouch: (Int, Int) -> Unit,
    onRequestMediaProjection: () -> Unit = {},
    onRequestRecordAudioPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val activeThemeMode by ThemePreferences.themeMode.collectAsState()
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
                            protocolNote = transition.summary,
                            forceLog = true
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
                    protocolNote = "Verified ${decoded.header.headerSizeBytes}B header on ${decoded.channel.label}: $description",
                    forceLog = true
                )

                val rawHeader = CarLifeFrameCodec.encodeHeader(decoded.header)
                val chHex = rawHeader.sliceArray(0..3).joinToString(" ") { "%02X".format(it) }
                val lenHex = rawHeader.sliceArray(4..7).joinToString(" ") { "%02X".format(it) }
                val tsPart = if (decoded.header.includeOuterTimestamp && rawHeader.size >= 12) {
                    val tsHex = rawHeader.sliceArray(8..11).joinToString(" ") { "%02X".format(it) }
                    "\n• Bytes [8..11] Timestamp: [$tsHex] -> ${decoded.header.timestamp} ms"
                } else if (decoded.channel == CarLifeChannel.VIDEO) {
                    "\n• Video Framing: AOA 20B (8B AOA + 12B 0x00020001) | ts=${decoded.header.timestamp} ms"
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0.0 Общие настройки — Оформление (Светлая / Тёмная / Авто, синхронно на смартфоне и ГУ)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("theme_mode_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                text = "Оформление",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "VW MIB3 • Применяется на смартфоне и в Кокпите ГУ",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = activeThemeMode.label,
                            tone = VwBadgeTone.WAITING
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.background)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(12.dp))
                            .padding(3.dp)
                            .testTag("theme_mode_segmented_control"),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val selected = activeThemeMode == mode
                            val tag = when (mode) {
                                ThemeMode.SYSTEM -> "theme_mode_system_btn"
                                ThemeMode.LIGHT -> "theme_mode_light_btn"
                                ThemeMode.DARK -> "theme_mode_dark_btn"
                            }
                            val bgColor by animateColorAsState(
                                targetValue = if (selected) colorScheme.primary else Color.Transparent,
                                animationSpec = tween(durationMillis = 180),
                                label = "theme_seg_bg"
                            )
                            val txtColor by animateColorAsState(
                                targetValue = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                animationSpec = tween(durationMillis = 180),
                                label = "theme_seg_txt"
                            )
                            val indicatorColor by animateColorAsState(
                                targetValue = if (selected) {
                                    if (vw.isDark) Color.White.copy(alpha = 0.85f) else vw.accentBlue
                                } else {
                                    Color.Transparent
                                },
                                animationSpec = tween(durationMillis = 180),
                                label = "theme_seg_line"
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(bgColor)
                                    .clickable {
                                        ThemePreferences.setThemeMode(mode, context)
                                    }
                                    .testTag(tag),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = txtColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(2.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(indicatorColor)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 0. Главный переключатель режима работы: 3 независимых режима (VW Segmented Control)
        item {
            val activeAppMode = state.appMode
            val shizukuStatus by CarLifeShizukuManager.status.collectAsStateWithLifecycle()
            val mpIsActive by MediaProjectionTokenStore.isProjectionActive.collectAsStateWithLifecycle()
            val mpHasPending by MediaProjectionTokenStore.hasPendingToken.collectAsStateWithLifecycle()
            val mpError by MediaProjectionTokenStore.lastError.collectAsStateWithLifecycle()
            val mpDetail by MediaProjectionTokenStore.statusDetail.collectAsStateWithLifecycle()

            LaunchedEffect(activeAppMode) {
                CarLifeShizukuManager.refreshStatus(context)
                if (activeAppMode == AppMode.SHIZUKU_COCKPIT) {
                    CarLifeShizukuManager.bindUserServiceIfReady(context)
                }
            }

            fun selectAppMode(mode: AppMode) {
                BridgeTelemetryBus.setAppMode(mode, context)
                when (mode) {
                    AppMode.SHIZUKU_COCKPIT -> {
                        CarLifeShizukuManager.init(context)
                        CarLifeShizukuManager.bindUserServiceIfReady(context)
                        if (!MediaProjectionTokenStore.hasValidToken.value) {
                            onRequestMediaProjection()
                        }
                    }
                    AppMode.MIRRORING -> {
                        CarLifeShizukuManager.unbindUserService(context)
                        if (!MediaProjectionTokenStore.hasValidToken.value) {
                            onRequestMediaProjection()
                        }
                    }
                    AppMode.COCKPIT -> {
                        CarLifeShizukuManager.unbindUserService(context)
                    }
                }
                if (state.isServiceRunning) {
                    val intent = Intent(context, BridgeForegroundService::class.java).apply {
                        action = BridgeForegroundService.ACTION_SET_APP_MODE
                        putExtra(BridgeForegroundService.EXTRA_APP_MODE, mode.id)
                    }
                    safeStartService(context, intent)
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_mode_switcher_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                text = "Режим работы",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Видеопоток H.264 и маршрутизация тача (0x0006)",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = when (activeAppMode) {
                                AppMode.COCKPIT -> "Кокпит"
                                AppMode.MIRRORING -> "Зеркало"
                                AppMode.SHIZUKU_COCKPIT -> "Shizuku"
                            },
                            tone = VwBadgeTone.ACTIVE
                        )
                    }

                    // Единый горизонтальный сегментированный контрол VW MIB3 (Кокпит | Зеркало | Shizuku)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.background)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(12.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        AppMode.entries.forEach { mode ->
                            val isSelected = activeAppMode == mode
                            val tag = when (mode) {
                                AppMode.COCKPIT -> "app_mode_cockpit_btn"
                                AppMode.MIRRORING -> "app_mode_mirroring_btn"
                                AppMode.SHIZUKU_COCKPIT -> "app_mode_shizuku_cockpit_btn"
                            }
                            val shortLabel = when (mode) {
                                AppMode.COCKPIT -> "Кокпит"
                                AppMode.MIRRORING -> "Зеркало"
                                AppMode.SHIZUKU_COCKPIT -> "Shizuku"
                            }
                            val segmentBg by animateColorAsState(
                                targetValue = if (isSelected) colorScheme.primary else Color.Transparent,
                                animationSpec = tween(durationMillis = 180),
                                label = "mode_seg_bg"
                            )
                            val segmentText by animateColorAsState(
                                targetValue = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                animationSpec = tween(durationMillis = 180),
                                label = "mode_seg_text"
                            )
                            val bottomLine by animateColorAsState(
                                targetValue = if (isSelected) {
                                    if (vw.isDark) Color.White.copy(alpha = 0.85f) else vw.accentBlue
                                } else {
                                    Color.Transparent
                                },
                                animationSpec = tween(durationMillis = 180),
                                label = "mode_seg_line"
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(segmentBg)
                                    .selectable(
                                        selected = isSelected,
                                        onClick = { selectAppMode(mode) },
                                        role = Role.RadioButton
                                    )
                                    .testTag(tag),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = shortLabel,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = segmentText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .width(26.dp)
                                        .height(2.5.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(bottomLine)
                                )
                            }
                        }
                    }

                    // Описание активного режима
                    Surface(
                        color = colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = activeAppMode.title,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = activeAppMode.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Карточка статуса Shizuku (Второй экран)
                    val shizukuOk = shizukuStatus.isBinderAlive && shizukuStatus.hasPermission
                    Surface(
                        color = colorScheme.background,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Служба Shizuku (Второй экран)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                VwStatusBadge(
                                    label = shizukuStatus.statusLabel,
                                    tone = if (shizukuOk) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING,
                                    modifier = Modifier.testTag("shizuku_status_badge")
                                )
                            }
                            Text(
                                text = "Статус Shizuku: ${shizukuStatus.statusLabel} • ${shizukuStatus.detailMessage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (shizukuOk) vw.onSuccessContainer else colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("shizuku_status_detail")
                            )
                            FilledTonalButton(
                                onClick = {
                                    CarLifeShizukuManager.requestPermission(context)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("request_shizuku_permission_btn"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (shizukuOk) vw.successContainer else colorScheme.primaryContainer,
                                    contentColor = if (shizukuOk) vw.onSuccessContainer else colorScheme.onPrimaryContainer
                                )
                            ) {
                                Text(
                                    text = when {
                                        shizukuStatus.isBinderAlive && shizukuStatus.hasPermission && shizukuStatus.isUserServiceBound ->
                                            "Shizuku подключён (Обновить)"
                                        shizukuStatus.isBinderAlive && shizukuStatus.hasPermission ->
                                            "Подключить Shizuku UserService"
                                        else ->
                                            "Запросить права Shizuku"
                                    },
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Единая панель диагностики Зеркалирования по 5 узлам (Android 14–16)
                    val projStats = state.projectionStats
                    val audioStatsForDiag = state.audioCaptureStats
                    val hasRecordAudioForDiag = remember(audioStatsForDiag.hasRecordAudioPermission, checklistStatus) {
                        CarLifeAudioCaptureManager.hasRecordAudioPermission(context)
                    }
                    val effectiveDiagnosticError = mpError ?: projStats.lastMediaProjectionError
                    val diagBorderColor = when {
                        effectiveDiagnosticError != null -> colorScheme.error.copy(alpha = 0.7f)
                        audioStatsForDiag.isSilenceWarningActive -> vw.statusWarningDot.copy(alpha = 0.7f)
                        else -> colorScheme.outline
                    }
                    val disableSpeakerMuteNow = {
                        BridgeTelemetryBus.setMutePhoneSpeakerDuringUsbAudio(false, context)
                        if (state.isServiceRunning) {
                            val muteIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                action = BridgeForegroundService.ACTION_SET_MUTE_PHONE_SPEAKER
                                putExtra(BridgeForegroundService.EXTRA_MUTE_PHONE_SPEAKER, false)
                            }
                            safeStartService(context, muteIntent)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mediaprojection_diagnostic_panel"),
                        color = colorScheme.background,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, diagBorderColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .testTag("mirroring_health_panel"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Диагностика Зеркалирования (5 узлов)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                VwStatusBadge(
                                    label = when {
                                        projStats.usingMediaProjection || mpIsActive -> "MP LIVE"
                                        mpHasPending -> "ТОКЕН ГОТОВ"
                                        effectiveDiagnosticError != null -> "ОШИБКА MP"
                                        else -> "НЕТ ТОКЕНА"
                                    },
                                    tone = when {
                                        effectiveDiagnosticError != null -> VwBadgeTone.ERROR
                                        projStats.usingMediaProjection || mpIsActive -> VwBadgeTone.ACTIVE
                                        else -> VwBadgeTone.WAITING
                                    }
                                )
                            }

                            // Узел 1: Режим и запрет тихого отката
                            MirroringNodeStatusRow(
                                index = "1",
                                label = "Режим",
                                statusBadge = if (activeAppMode == AppMode.MIRRORING) "ЗЕРКАЛО" else activeAppMode.name,
                                badgePositive = true,
                                detail = "${activeAppMode.title} • ${state.videoSourceMode.title}" +
                                    if (activeAppMode == AppMode.MIRRORING) " • откат в Кокпит заблокирован" else ""
                            )

                            // Узел 2: FGS + MediaProjection
                            val fgsMpOk = projStats.usingMediaProjection || mpIsActive
                            val fgsBadge = when {
                                fgsMpOk && projStats.isMediaProjectionFgsActive -> "FGS+MP ОК"
                                fgsMpOk -> "MP LIVE"
                                projStats.isMediaProjectionFgsActive -> "FGS ОК"
                                mpHasPending -> "ТОКЕН ЖДЁТ"
                                else -> "ОЖИД. MP"
                            }
                            MirroringNodeStatusRow(
                                index = "2",
                                label = "FGS + MediaProjection",
                                statusBadge = fgsBadge,
                                badgePositive = fgsMpOk,
                                detail = "${projStats.mediaProjectionState} • FGS mediaProjection=${if (projStats.isMediaProjectionFgsActive) "ДА" else "НЕТ"} ($mpDetail)"
                            )

                            // Узел 3: Видео H.264, 30 FPS и ориентация экрана
                            val orientationLabel = when {
                                projStats.phoneScreenWidth > 0 && projStats.phoneScreenHeight > 0 ->
                                    "${if (projStats.isPhoneLandscape) "Альбомная" else "Портретная"} ${projStats.phoneScreenWidth}×${projStats.phoneScreenHeight}"
                                else -> "Ожидание экрана"
                            }
                            val vdLabel = if (projStats.usingMediaProjection && projStats.virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                "VD #${projStats.virtualDisplayId} (${projStats.virtualDisplayDpi} dpi)"
                            } else if (activeAppMode == AppMode.MIRRORING) {
                                "VD не создан (ожидание MP)"
                            } else {
                                "Surface ${projStats.width}×${projStats.height}"
                            }
                            val fpsBadgeText = if (projStats.actualFps > 0f) {
                                "${"%.1f".format(projStats.actualFps)} FPS"
                            } else if (projStats.isRunning) {
                                "${projStats.fps} FPS"
                            } else {
                                "СТОП"
                            }
                            val lowLatencyTag = if (projStats.isLowLatencyEncoderActive) " • LowLatency" else ""
                            val droppedTotal = maxOf(projStats.droppedVideoPFrames, state.droppedVideoPFrames)
                            val dropTag = if (droppedTotal > 0L) " • сброс P: $droppedTotal" else " • 0 потерь"
                            val screenTag = if (projStats.isPhoneScreenOff || state.screenKeepAliveState.isPhoneScreenOff) {
                                " • Экран погашен (KeepAlive)"
                            } else {
                                ""
                            }
                            MirroringNodeStatusRow(
                                index = "3",
                                label = "Видео H.264 / 30 FPS",
                                statusBadge = fpsBadgeText,
                                badgePositive = projStats.isRunning && (projStats.actualFps == 0f || projStats.actualFps <= 35f),
                                detail = "${projStats.width}×${projStats.height} • ${projStats.idrPerSec} IDR/с$lowLatencyTag$dropTag$screenTag • $vdLabel • $orientationLabel"
                            )

                            // Узел 4: Тач 0x0006 (CarLifeAccessibilityService)
                            val a11yOk = checklistStatus.step3AccessibilityEnabled
                            MirroringNodeStatusRow(
                                index = "4",
                                label = "Тач 0x0006 (A11y)",
                                statusBadge = if (a11yOk) "ВКЛ" else "ВЫКЛ",
                                badgePositive = a11yOk,
                                detail = projStats.lastTouchDiagnostic
                            )

                            // Узел 5: Аудио 0x0003 (AudioPlaybackCapture + динамик)
                            val audioOk = audioStatsForDiag.isCapturing && !audioStatsForDiag.isSilenceWarningActive
                            val audioBadge = when {
                                audioStatsForDiag.isSilenceWarningActive -> "ТИШИНА"
                                audioStatsForDiag.isCapturing -> "PCM ON"
                                !hasRecordAudioForDiag -> "НЕТ ПРАВ"
                                else -> "ГОТОВ"
                            }
                            val speakerNote = if (audioStatsForDiag.isPhoneSpeakerMutedByBridge) {
                                "динамик 0 (сохр. ${audioStatsForDiag.savedPhoneMusicVolume ?: 0})"
                            } else {
                                "громкость=${audioStatsForDiag.currentMusicVolume ?: "—"}"
                            }
                            MirroringNodeStatusRow(
                                index = "5",
                                label = "Аудио 0x0003 PCM",
                                statusBadge = audioBadge,
                                badgePositive = audioOk || (hasRecordAudioForDiag && !audioStatsForDiag.isSilenceWarningActive),
                                detail = "PCM: ${audioStatsForDiag.sentPcmPackets} пак. • тишина: ${audioStatsForDiag.skippedSilentPackets} • $speakerNote"
                            )

                            if (!effectiveDiagnosticError.isNullOrBlank()) {
                                Text(
                                    text = "Причина / ошибка: $effectiveDiagnosticError",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.error,
                                    modifier = Modifier.testTag("mediaprojection_diagnostic_error")
                                )
                            }

                            if (audioStatsForDiag.isSilenceWarningActive && !audioStatsForDiag.silenceWarningMessage.isNullOrBlank()) {
                                Surface(
                                    color = vw.warningContainer,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, vw.statusWarningDot.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("mute_silence_warning_banner")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = audioStatsForDiag.silenceWarningMessage,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = vw.onWarningContainer
                                        )
                                        FilledTonalButton(
                                            onClick = disableSpeakerMuteNow,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("disable_mute_silence_warning_btn"),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = vw.statusWarningDot.copy(alpha = 0.22f),
                                                contentColor = vw.onWarningContainer
                                            )
                                        ) {
                                            Text(
                                                text = "Отключить приглушение динамика",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }

                            if (activeAppMode != AppMode.COCKPIT &&
                                (!projStats.usingMediaProjection || !effectiveDiagnosticError.isNullOrBlank())
                            ) {
                                FilledTonalButton(
                                    onClick = onRequestMediaProjection,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("request_mediaprojection_diag_btn"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.primaryContainer,
                                        contentColor = colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Text(
                                        text = "Выдать MediaProjection («Весь экран»)",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (activeAppMode == AppMode.MIRRORING && !a11yOk) {
                                OutlinedButton(
                                    onClick = onOpenAccessibilitySettings,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("mirroring_open_a11y_btn"),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, vw.statusWarningDot.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = vw.onWarningContainer)
                                ) {
                                    Text(
                                        text = "Включить службу тача (Спец. возможности)",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Тумблер «Логировать пакеты в UI» (выкл. по умолчанию в режиме Зеркалирования для плавных 30 fps)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.background)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Логировать пакеты в UI",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = if (state.logPacketsInUi) {
                                    "Вкл — пакеты 0x0001/0x0002 пишутся в журнал (может нагружать UI)"
                                } else {
                                    "Выкл — ноль лишних перерисовок Compose при 30 fps и аудио PCM"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.logPacketsInUi,
                            onCheckedChange = { enabled ->
                                BridgeTelemetryBus.setLogPacketsInUi(enabled)
                                if (state.isServiceRunning) {
                                    val intent = Intent(context, BridgeForegroundService::class.java).apply {
                                        action = BridgeForegroundService.ACTION_SET_LOG_PACKETS_IN_UI
                                        putExtra(BridgeForegroundService.EXTRA_LOG_PACKETS_IN_UI, enabled)
                                    }
                                    safeStartService(context, intent)
                                }
                            },
                            modifier = Modifier.testTag("log_packets_ui_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colorScheme.onPrimary,
                                checkedTrackColor = colorScheme.primary,
                                uncheckedThumbColor = colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }

        // 0.1 Чувствительность тача (0x0006) — пресеты, слайдеры и защита краёв One UI
        item {
            TouchSensitivitySettingsCard()
        }

        // 0.2 Экран и питание — работа Зеркалирования и звука 0x0003 при погашенном экране + 1% OLED
        item {
            ScreenKeepAliveSettingsCard(
                keepAliveState = state.screenKeepAliveState,
                hasOverlayPermission = checklistStatus.step2OverlayGranted,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onNotifyServiceConfigChanged = {
                    if (state.isServiceRunning) {
                        val intent = Intent(context, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_UPDATE_SCREEN_KEEP_ALIVE
                        }
                        safeStartService(context, intent)
                    }
                }
            )
        }

        // 1. Блок управления виртуальным экраном и статусами (Start VD, Touch ON, Overlay OK + лог запуска)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                        val sourceMode = state.videoSourceMode
                        Text(
                            text = when (sourceMode) {
                                VideoSourceMode.SCREEN_MIRRORING -> "Зеркалирование экрана (MediaProjection)"
                                VideoSourceMode.VIRTUAL_DISPLAY -> "Виртуальный экран ГУ (VirtualDisplay)"
                                VideoSourceMode.CANVAS_TEST_CARD -> "Источник H.264: Тест-карта (Canvas)"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = when {
                                state.projectionStats.isRunning && sourceMode == VideoSourceMode.SCREEN_MIRRORING ->
                                    if (state.projectionStats.usingMediaProjection) "MIRROR ON" else "ЖДЁТ MP"
                                state.projectionStats.isRunning && sourceMode == VideoSourceMode.CANVAS_TEST_CARD -> "CANVAS 30FPS"
                                state.projectionStats.isRunning -> "VD #${state.projectionStats.virtualDisplayId}"
                                sourceMode == VideoSourceMode.SCREEN_MIRRORING -> "MIRROR ВЫКЛ"
                                sourceMode == VideoSourceMode.CANVAS_TEST_CARD -> "CANVAS ВЫКЛ"
                                else -> "VD ВЫКЛ"
                            },
                            tone = if (state.projectionStats.isRunning) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val sourceMode = state.videoSourceMode
                        StatusActionChip(
                            label = when {
                                state.projectionStats.isRunning && sourceMode == VideoSourceMode.SCREEN_MIRRORING -> "Mirror ON"
                                sourceMode == VideoSourceMode.SCREEN_MIRRORING -> "Start Mirror"
                                state.projectionStats.isRunning && sourceMode == VideoSourceMode.CANVAS_TEST_CARD -> "Canvas ON"
                                sourceMode == VideoSourceMode.CANVAS_TEST_CARD -> "Start Canvas"
                                state.projectionStats.isRunning -> "VD ON"
                                else -> "Start VD"
                            },
                            active = state.projectionStats.isRunning,
                            icon = CockpitIcons.Videocam,
                            testTag = "quick_toggle_vd_btn",
                            onClick = onToggleProjection,
                            modifier = Modifier.weight(1f)
                        )
                        StatusActionChip(
                            label = if (checklistStatus.step3AccessibilityEnabled) "Тач: Вкл" else "Тач: Выкл",
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
                        color = colorScheme.background,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        val vdStatusLine = if (state.projectionStats.isRunning && !lastLaunchStatus.startsWith("Запущено:")) {
                            when (state.videoSourceMode) {
                                VideoSourceMode.SCREEN_MIRRORING ->
                                    if (state.projectionStats.usingMediaProjection) {
                                        "Зеркалирование экрана активно (${state.configuredWidth}×${state.configuredHeight} @ ${state.configuredBitrateBps / 1000} кбит/с, 30 fps, ${state.projectionStats.virtualDisplayDpi} dpi, без Presentation)"
                                    } else {
                                        val err = state.projectionStats.lastMediaProjectionError
                                        if (!err.isNullOrBlank()) {
                                            "Ошибка Зеркалирования: $err (откат в Кокпит запрещён)"
                                        } else {
                                            "Ожидание MediaProjection («Весь экран») — откат в Кокпит запрещён"
                                        }
                                    }
                                VideoSourceMode.CANVAS_TEST_CARD ->
                                    "Тест-карта (Canvas) активна на Surface (${state.configuredWidth}×${state.configuredHeight} @ ${state.configuredBitrateBps / 1000} кбит/с, 30 fps)"
                                VideoSourceMode.VIRTUAL_DISPLAY ->
                                    "Лаунчер ГУ активен на VD #${state.projectionStats.virtualDisplayId} (${state.configuredWidth}×${state.configuredHeight} @ ${state.configuredBitrateBps / 1000} кбит/с)"
                            }
                        } else if (!state.projectionStats.isRunning) {
                            "Готов к трансляции: выбрано разрешение ${state.configuredWidth}×${state.configuredHeight} @ ${state.configuredBitrateBps / 1000} кбит/с"
                        } else {
                            lastLaunchStatus
                        }
                        Text(
                            text = vdStatusLine,
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = when {
                                state.videoSourceMode == VideoSourceMode.SCREEN_MIRRORING &&
                                    !state.projectionStats.usingMediaProjection -> vw.onWarningContainer
                                state.projectionStats.isRunning -> vw.onSuccessContainer
                                else -> colorScheme.onSurfaceVariant
                            },
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

        // 1.15 Карточка «Звук по USB AOA (канал 0x0003, AudioPlaybackCapture PCM)»
        item {
            val audioStats = state.audioCaptureStats
            val hasRecordAudioPerm = remember(audioStats.hasRecordAudioPermission, checklistStatus) {
                CarLifeAudioCaptureManager.hasRecordAudioPermission(context)
            }
            val audioBadgeText = when {
                audioStats.isCapturing -> "PCM 48КГЦ"
                !hasRecordAudioPerm -> "НЕТ ПРАВ"
                !checklistStatus.step5MediaProjectionGranted -> "НЕТ ЭКРАНА"
                else -> "ГОТОВ"
            }
            val audioBadgeTone = when {
                audioStats.isCapturing -> VwBadgeTone.ACTIVE
                !hasRecordAudioPerm -> VwBadgeTone.WARNING
                else -> VwBadgeTone.WAITING
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("usb_audio_0x0003_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                text = "Звук по USB AOA (канал 0x0003)",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "48 кГц Stereo 16-bit PCM (4096B) • Звонки HFP на Bluetooth авто",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = audioBadgeText,
                            tone = audioBadgeTone
                        )
                    }

                    Text(
                        text = "Системный звук (Я.Музыка, Навигатор, 2ГИС, YouTube) захватывается через AudioPlaybackCapture и передаётся в канал 0x0003 (AOA 20B). Паузы тишины пропускаются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )

                    Surface(
                        color = colorScheme.background,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = audioStats.statusMessage,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (audioStats.isCapturing) vw.onSuccessContainer else colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "INIT: ${audioStats.sentInitPackets} • PCM: ${audioStats.sentPcmPackets} пак. (${audioStats.sentPcmBytes / 1024} КБ) • Тишина пропущена: ${audioStats.skippedSilentPackets}",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = vw.textMuted
                            )
                        }
                    }

                    // Тумблер «Глушить динамик телефона при USB-звуке» (по умолчанию ВКЛ)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.background)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Глушить динамик телефона при USB-звуке",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = if (audioStats.isPhoneSpeakerMutedByBridge) {
                                    "Динамик телефона заглушен (сохранена громкость: ${audioStats.savedPhoneMusicVolume ?: 0}); восстановится при отключении USB"
                                } else {
                                    "При старте захвата 0x0003 сохраняет STREAM_MUSIC, ставит 0 и возвращает при остановке/отключении USB"
                                },
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = if (audioStats.isPhoneSpeakerMutedByBridge) vw.onSuccessContainer else colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.mutePhoneSpeakerDuringUsbAudio,
                            onCheckedChange = { enabled ->
                                BridgeTelemetryBus.setMutePhoneSpeakerDuringUsbAudio(enabled, context)
                                if (state.isServiceRunning) {
                                    val muteIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                        action = BridgeForegroundService.ACTION_SET_MUTE_PHONE_SPEAKER
                                        putExtra(BridgeForegroundService.EXTRA_MUTE_PHONE_SPEAKER, enabled)
                                    }
                                    safeStartService(context, muteIntent)
                                }
                            },
                            modifier = Modifier.testTag("mute_speaker_usb_audio_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colorScheme.onPrimary,
                                checkedTrackColor = vw.statusActiveDot,
                                uncheckedThumbColor = colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = colorScheme.surfaceVariant
                            )
                        )
                    }

                    if (audioStats.isSilenceWarningActive && !audioStats.silenceWarningMessage.isNullOrBlank()) {
                        Surface(
                            color = vw.warningContainer,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, vw.statusWarningDot.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = audioStats.silenceWarningMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = vw.onWarningContainer
                                )
                                FilledTonalButton(
                                    onClick = {
                                        BridgeTelemetryBus.setMutePhoneSpeakerDuringUsbAudio(false, context)
                                        if (state.isServiceRunning) {
                                            val muteIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                                action = BridgeForegroundService.ACTION_SET_MUTE_PHONE_SPEAKER
                                                putExtra(BridgeForegroundService.EXTRA_MUTE_PHONE_SPEAKER, false)
                                            }
                                            safeStartService(context, muteIntent)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("audio_card_disable_mute_btn"),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = vw.statusWarningDot.copy(alpha = 0.22f),
                                        contentColor = vw.onWarningContainer
                                    )
                                ) {
                                    Text(
                                        text = "Отключить приглушение динамика",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            when {
                                !hasRecordAudioPerm -> onRequestRecordAudioPermission()
                                !checklistStatus.step5MediaProjectionGranted -> onRequestMediaProjection()
                                audioStats.isCapturing -> {
                                    val stopAudioIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                        action = BridgeForegroundService.ACTION_STOP_AUDIO_CAPTURE
                                    }
                                    safeStartService(context, stopAudioIntent)
                                }
                                else -> {
                                    val startAudioIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                        action = BridgeForegroundService.ACTION_START_AUDIO_CAPTURE
                                    }
                                    safeStartService(context, startAudioIntent)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("audio_0x0003_action_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (audioStats.isCapturing) colorScheme.error else colorScheme.primary,
                            contentColor = if (audioStats.isCapturing) colorScheme.onError else colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = when {
                                !hasRecordAudioPerm -> "Разрешить захват звука (RECORD_AUDIO)"
                                !checklistStatus.step5MediaProjectionGranted -> "Выдать MediaProjection («Весь экран»)"
                                audioStats.isCapturing -> "Остановить звук 0x0003 (MSG_MEDIA_STOP)"
                                else -> "Запустить захват звука 0x0003"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 1.20 Карточка «Служба тача (Спец. возможности)» — CarLifeAccessibilityService для канала 0x0006
        item {
            val isTouchServiceOn = checklistStatus.step3AccessibilityEnabled
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("accessibility_touch_service_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                text = "Служба тача (Спец. возможности)",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "CarLifeAccessibilityService • Тапы, свайпы карт и Назад/Домой (канал 0x0006)",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = if (isTouchServiceOn) "Вкл" else "Выкл",
                            tone = if (isTouchServiceOn) VwBadgeTone.ACTIVE else VwBadgeTone.WARNING
                        )
                    }

                    Text(
                        text = "Переводит касания с экрана ГУ (1280×720) в реальные координаты смартфона с учётом ориентации и выполняет тапы (50 мс) и свайпы карт в любых приложениях (2ГИС, Яндекс.Навигатор, YouTube).",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("enable_accessibility_touch_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTouchServiceOn) colorScheme.surfaceVariant else colorScheme.primary,
                            contentColor = if (isTouchServiceOn) colorScheme.onSurface else colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = CockpitIcons.TouchApp,
                            contentDescription = "Включить службу тача",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTouchServiceOn) {
                                "Служба тача включена (Открыть Спец. возможности)"
                            } else {
                                "Включить службу тача (Спец. возможности)"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 1.25 Переключатель «Источник видео» (Зеркалирование экрана MediaProjection по умолчанию vs VD vs Canvas)
        item {
            val activeSource = state.videoSourceMode
            fun selectVideoSource(mode: VideoSourceMode) {
                BridgeTelemetryBus.setVideoSourceMode(mode, context)
                if (state.isServiceRunning) {
                    val intent = Intent(context, BridgeForegroundService::class.java).apply {
                        action = BridgeForegroundService.ACTION_SET_VIDEO_SOURCE_MODE
                        putExtra(BridgeForegroundService.EXTRA_VIDEO_SOURCE_MODE, mode.id)
                    }
                    safeStartService(context, intent)
                }
                if (mode == VideoSourceMode.SCREEN_MIRRORING && !MediaProjectionTokenStore.hasValidToken.value) {
                    onRequestMediaProjection()
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video_source_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                text = "Источник видео",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Выбор входа для H.264 MediaCodec (1280×720@30fps, 2000 kbps, AOA 20B)",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = when (activeSource) {
                                VideoSourceMode.SCREEN_MIRRORING -> "MIRROR"
                                VideoSourceMode.VIRTUAL_DISPLAY -> "VD"
                                VideoSourceMode.CANVAS_TEST_CARD -> "CANVAS"
                            },
                            tone = VwBadgeTone.ACTIVE
                        )
                    }

                    VideoSourceMode.entries.forEach { mode ->
                        val isSelected = activeSource == mode
                        val rowBg = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.45f) else colorScheme.background
                        val rowBorder = if (isSelected) colorScheme.primary else colorScheme.outline
                        val tag = when (mode) {
                            VideoSourceMode.SCREEN_MIRRORING -> "video_source_mirror_btn"
                            VideoSourceMode.VIRTUAL_DISPLAY -> "video_source_vd_btn"
                            VideoSourceMode.CANVAS_TEST_CARD -> "video_source_canvas_btn"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(rowBg)
                                .border(1.dp, rowBorder, RoundedCornerShape(10.dp))
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectVideoSource(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag(tag),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colorScheme.primary,
                                    unselectedColor = vw.textMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface
                                )
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1.35 Карточка «Режим запуска приложений» (На VirtualDisplay / Зеркалирование экрана MediaProjection)
        item {
            val activeLaunchMode = state.appLaunchMode
            fun selectAppLaunchMode(mode: AppLaunchMode) {
                BridgeTelemetryBus.setAppLaunchMode(mode)
                if (state.isServiceRunning) {
                    val intent = Intent(context, BridgeForegroundService::class.java).apply {
                        action = BridgeForegroundService.ACTION_SET_APP_LAUNCH_MODE
                        putExtra(BridgeForegroundService.EXTRA_APP_LAUNCH_MODE, mode.id)
                    }
                    safeStartService(context, intent)
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_launch_mode_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                text = "Режим запуска приложений",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "При SecurityException на VD выполняется мгновенный запасной запуск без ActivityOptions",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = when (activeLaunchMode) {
                                AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE -> "VD"
                                AppLaunchMode.SCREEN_MIRRORING -> "MIRROR"
                                AppLaunchMode.SHIZUKU_VIRTUAL_DISPLAY -> "SHIZUKU"
                            },
                            tone = VwBadgeTone.WAITING
                        )
                    }

                    AppLaunchMode.entries.forEach { mode ->
                        val isSelected = activeLaunchMode == mode
                        val rowBg = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.45f) else colorScheme.background
                        val rowBorder = if (isSelected) colorScheme.primary else colorScheme.outline
                        val tag = when (mode) {
                            AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE -> "launch_mode_vd_btn"
                            AppLaunchMode.SCREEN_MIRRORING -> "launch_mode_mirror_btn"
                            AppLaunchMode.SHIZUKU_VIRTUAL_DISPLAY -> "launch_mode_shizuku_btn"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(rowBg)
                                .border(1.dp, rowBorder, RoundedCornerShape(10.dp))
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectAppLaunchMode(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag(tag),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colorScheme.primary,
                                    unselectedColor = vw.textMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface
                                )
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Режим ответа на MSG_CMD_HU_PROTOCOL_VERSION (0x00018001) от ГУ Jetta VS7",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = "ТЕСТ ${activeProfile.id}/4",
                            tone = VwBadgeTone.WAITING
                        )
                    }

                    HandshakeTestProfile.entries.forEach { profile ->
                        val isSelected = activeProfile == profile
                        val rowBg = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.45f) else colorScheme.background
                        val rowBorder = if (isSelected) colorScheme.primary else colorScheme.outline
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(rowBg)
                                .border(1.dp, rowBorder, RoundedCornerShape(10.dp))
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
                                    selectedColor = colorScheme.primary,
                                    unselectedColor = vw.textMuted
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
                                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface
                                )
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Настройки H.264 видеопотока (разрешение 800x480 / 1024x600 / 1280x720, битрейт 2.0 Мбит/с VBR 30fps, AOA 20B)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = "30 FPS • VBR",
                            tone = VwBadgeTone.WAITING
                        )
                    }

                    Text(
                        text = "Разрешение виртуального дисплея, Presentation и кодека (1280×720 • 160 dpi по умолчанию)",
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = colorScheme.onSurfaceVariant
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
                        text = "Битрейт кодека (AVCProfileBaseline L3 VBR, QP≤36, 30fps, I=1s, 2.0 Мбит/с)",
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = colorScheme.onSurfaceVariant
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
                            label = "2.0 Мбит/с",
                            selected = configuredBitrateKbps == 2000,
                            testTag = "bitrate_6m_btn",
                            onClick = { onSelectBitrate(2000) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "Формат шапки видеоканала 0x0002 (Режим 1: AOA 20B с 0x00020001 по умолчанию)",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )

                    fun applyVideoHeaderMode(useAoA20B: Boolean) {
                        CarLifeFrameCodec.useNestedVideoMsgHeader = useAoA20B
                        BridgeTelemetryBus.updateState {
                            it.copy(
                                useNestedVideoMsgHeader = useAoA20B,
                                lastProtocolEvent = if (useAoA20B) {
                                    "Video 0x0002 Framing: Режим 1: AOA 20B (8B AOA + 12B 0x00020001) active"
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
                            label = "Режим 1: AOA 20B (0x00020001)",
                            selected = state.useNestedVideoMsgHeader,
                            testTag = "toggle_video_header_mode_btn",
                            onClick = { applyVideoHeaderMode(true) },
                            modifier = Modifier.weight(1.25f)
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
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(width = 1.dp, color = colorScheme.outline)
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
                                text = "Профиль Jetta VS7 (AOA 20B 0x00020001 + Audio Init)",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "После 0x00018001 / 0x00018003 отправляются MATCH_STATUS (0x00010002), полный MD_INFO (0x00010004), Audio-Init (0x0003, 0x0004), INIT_DONE (0x00010008), SCREEN_ON (0x00010018), USERPRESENT (0x0001001A), FOREGROUND (0x0001001B), REQ_FG (0x00010038) и видеопоток 0x0002 (AOA 20B 0x00020001, единый пакет SPS+PPS+IDR, при простое >200 мс — HEARTBEAT 0x00020002).",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = "JETTA VS7",
                            tone = VwBadgeTone.ACTIVE
                        )
                    }

                    Surface(
                        color = vw.successContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, vw.statusActiveDot.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = autoTest.statusMessage,
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = vw.onSuccessContainer,
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
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Зафиксировать профиль Jetta VS7",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Стартовать VD (1280×720 • 2.0 Мбит/с • 160 dpi)",
                            style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.SemiBold),
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
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
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
                            .heightIn(min = 48.dp)
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
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Статистика USB AOA, счетчики каналов 0x0001..0x0006 и генератор тестовых пакетов",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (diagnosticsExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = "Развернуть инженерную диагностику",
                            tint = colorScheme.primary
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
                                color = colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, colorScheme.outline)
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
                                        color = vw.accentBlue
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CodecTestButton(
                                            title = "1. Handshake",
                                            subtitle = "HU v2.0 + Video Init",
                                            icon = CockpitIcons.Memory,
                                            accent = vw.accentBlue,
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
                                            accent = vw.statusActiveDot,
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
                                            accent = vw.statusWarningDot,
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
                                            accent = colorScheme.primary,
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
                                        color = colorScheme.background,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, colorScheme.outline)
                                    ) {
                                        Text(
                                            text = lastDecodedBreakdown,
                                            style = MaterialTheme.typography.labelSmall.tabular(),
                                            fontFamily = JetBrainsMonoFamily,
                                            color = colorScheme.onSurface,
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
                                    color = vw.accentBlue,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { BridgeTelemetryBus.clearLogs() },
                                    modifier = Modifier.testTag("clear_logs_button")
                                ) {
                                    Icon(
                                        imageVector = CockpitIcons.ClearAll,
                                        contentDescription = "Очистить логи",
                                        tint = colorScheme.onSurfaceVariant
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
                                    "Режим 1: AOA 20B (8B AOA + 12B 0x00020001)"
                                } else {
                                    "Режим 2: Direct 12B ([0x0002][nalSize][ts])"
                                }
                                val gateStatusLabel = if (state.isVideoChannelReadyForStreaming) {
                                    "Шлюз 0x0002: ОТКРЫТ (поток 0x0002 активен)"
                                } else {
                                    "Шлюз 0x0002: ЗАКРЫТ (ожидание 0x00018001 / 0x00018007 от ГУ)"
                                }
                                Surface(
                                    color = colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, colorScheme.outline)
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
                                            color = vw.onSuccessContainer
                                        )
                                        Text(
                                            text = "$gateStatusLabel • Кадров 0x0002: $videoFramesCount • Кодек: ${state.projectionStats.width}×${state.projectionStats.height}@${state.projectionStats.fps}fps (${state.configuredBitrateBps / 1000} kbps) • IDR: ${state.projectionStats.lastIdrBytes}B",
                                            style = MaterialTheme.typography.labelSmall.tabular(),
                                            fontFamily = JetBrainsMonoFamily,
                                            color = colorScheme.onSurfaceVariant
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
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) colorScheme.primary else colorScheme.outline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.tabular().copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) colorScheme.onPrimary else colorScheme.onSurface,
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
    val vw = MaterialTheme.vwColors
    val bg = if (active) vw.successContainer else vw.infoContainer
    val fg = if (active) vw.onSuccessContainer else vw.onInfoContainer
    val dot = if (active) vw.statusActiveDot else vw.statusWaitingDot

    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, dot.copy(alpha = 0.3f))
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
                tint = fg,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold
                ),
                color = fg,
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
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val proj = state.projectionStats
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, colorScheme.outline)
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
                    text = when (state.videoSourceMode) {
                        VideoSourceMode.SCREEN_MIRRORING -> "Конвейер Зеркалирование экрана (MediaProjection) + H.264"
                        VideoSourceMode.VIRTUAL_DISPLAY -> "Конвейер VirtualDisplay + H.264"
                        VideoSourceMode.CANVAS_TEST_CARD -> "Конвейер Тест-карта (Canvas) + H.264"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (proj.spsPpsConfigured) "SPS/PPS LOCKED" else "AVC Baseline",
                    style = MaterialTheme.typography.labelSmall,
                    color = vw.accentBlue
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
                    subValue = when (state.appMode) {
                        AppMode.MIRRORING -> "MIRROR A11Y"
                        AppMode.SHIZUKU_COCKPIT -> "SHIZUKU #${proj.virtualDisplayId}"
                        AppMode.COCKPIT -> if (state.videoSourceMode == VideoSourceMode.CANVAS_TEST_CARD) "CANVAS" else "VD #${proj.virtualDisplayId}"
                    },
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "SURFACE",
                    value = "${state.configuredWidth}×${state.configuredHeight}",
                    subValue = "${proj.fps} FPS • ${proj.virtualDisplayDpi}dpi",
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Режим: ${state.appMode.title} • MP: ${proj.mediaProjectionState}" +
                    (proj.lastMediaProjectionError?.let { " • $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = if (proj.lastMediaProjectionError != null) colorScheme.error else colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!proj.isRunning) {
                    Button(
                        onClick = { onStartProjection(state.configuredWidth, state.configuredHeight, 30) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("start_projection_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start Video Pipeline",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (state.videoSourceMode) {
                                VideoSourceMode.SCREEN_MIRRORING -> "Запустить Зеркалирование (MediaProjection)"
                                VideoSourceMode.VIRTUAL_DISPLAY -> "Запустить VirtualDisplay + H.264"
                                VideoSourceMode.CANVAS_TEST_CARD -> "Запустить Тест-карту (Canvas) + H.264"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = { onInjectTouch(120, 180) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("inject_touch_dock_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Тест тача (Док)")
                    }
                    FilledTonalButton(
                        onClick = { onInjectTouch(680, 360) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("inject_touch_launcher_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Тест тача (Сетка)")
                    }
                    OutlinedButton(
                        onClick = onStopProjection,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("stop_projection_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.55f))
                    ) {
                        Text("Стоп", color = colorScheme.error)
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
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, colorScheme.outline)
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
                    color = colorScheme.onSurface
                )
                Text(
                    text = if (state.wakeLockHeld) "WakeLock: ACTIVE" else "DirectBufferPool",
                    style = MaterialTheme.typography.labelSmall,
                    color = vw.onSuccessContainer
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
                        .heightIn(min = 48.dp)
                        .testTag("scan_connect_usb_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary
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
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("stop_bridge_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            imageVector = CockpitIcons.Stop,
                            contentDescription = "Stop Service",
                            tint = colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Стоп", color = colorScheme.error)
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
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = vw.textMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.tabular(),
            color = colorScheme.onSurface
        )
        Text(
            text = subValue,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = vw.accentBlue
        )
    }
}

@Composable
private fun ChannelMultiplexerGrid(
    channelCounts: Map<CarLifeChannel, Long>
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, colorScheme.outline)
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
                color = colorScheme.onSurface
            )

            val pairs = CarLifeChannel.entries.chunked(2)
            for (rowChannels in pairs) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (channel in rowChannels) {
                        val count = channelCounts[channel] ?: 0L
                        val badgeColor = if (channel.hasTimestampHeader) vw.onSuccessContainer else vw.accentBlue
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colorScheme.surface)
                                .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "0x%04X".format(channel.id),
                                    style = MaterialTheme.typography.labelMedium.tabular(),
                                    color = badgeColor
                                )
                                Text(
                                    text = "${channel.headerSizeBytes}B HDR",
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = vw.textMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = channel.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "$count пакетов",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = if (count > 0) badgeColor else colorScheme.onSurfaceVariant
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
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
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
                    color = colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = accent
                )
            }
        }
    }
}

@Composable
private fun PacketLogItemCard(entry: PacketLogEntry) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val accent = if (entry.channel.hasTimestampHeader) vw.onSuccessContainer else vw.accentBlue
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
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
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colorScheme.onSurface
                    )
                }
                Text(
                    text = entry.timestampFormatted,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = vw.textMuted
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Header: ${entry.headerSizeBytes}B",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Payload: ${entry.payloadSizeBytes}B",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colorScheme.onSurfaceVariant
                )
                if (entry.channel.hasTimestampHeader) {
                    Text(
                        text = "TS: ${entry.mediaTimestampMs}ms",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = vw.onSuccessContainer
                    )
                }
                entry.serviceName?.let { service ->
                    Text(
                        text = service,
                        style = MaterialTheme.typography.labelSmall,
                        color = vw.onWarningContainer
                    )
                }
            }

            Text(
                text = entry.hexPreview,
                style = MaterialTheme.typography.labelSmall.tabular(),
                fontFamily = JetBrainsMonoFamily,
                color = vw.textMuted,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyPacketLogCard() {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
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
                tint = vw.accentBlue,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "Нет захваченных кадров CarLife",
                style = MaterialTheme.typography.titleSmall,
                color = colorScheme.onSurface
            )
            Text(
                text = "Нажмите на любой тестовый вектор выше для проверки бинарного протокола.",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MirroringNodeStatusRow(
    index: String,
    label: String,
    statusBadge: String,
    badgePositive: Boolean,
    detail: String
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                Surface(
                    color = colorScheme.primaryContainer,
                    shape = CircleShape
                ) {
                    Text(
                        text = index,
                        style = MaterialTheme.typography.labelSmall.tabular().copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            VwStatusBadge(
                label = statusBadge,
                tone = if (badgePositive) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}


