package com.example

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material3.OutlinedButton
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import com.example.core.audio.CarLifeAudioCaptureManager
import com.example.core.config.AppEditionConfig
import com.example.core.config.AppPermissionKey
import com.example.core.config.ConnectionModeRequirements
import com.example.core.config.ModeConnectionGuideSpec
import com.example.core.config.PermissionRequirementSpec
import com.example.core.config.RequirementRelevance
import com.example.core.input.CarLifeAccessibilityService
import com.example.core.input.TouchSettingsPreferences
import com.example.core.projection.AppMode
import com.example.core.projection.MediaProjectionTokenStore
import com.example.core.projection.ScreenKeepAliveManager
import com.example.core.projection.ScreenKeepAlivePreferences
import com.example.core.shizuku.CarLifeShizukuManager
import com.example.core.shizuku.ShizukuStatusState
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeRuntimeState
import com.example.core.usb.BridgeTelemetryBus
import com.example.core.usb.PacketLogEntry
import com.example.core.usb.UsbTransportState
import com.example.feature.cockpit.CarLifeAppLauncherScreen
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.MediaPlayerController
import com.example.feature.cockpit.MediaState
import com.example.protocol.carlife.CarLifeChannel
import com.example.ui.guide.SetupGuideScreen
import com.example.ui.settings.ScreenKeepAliveSettingsCard
import com.example.ui.settings.TouchSensitivitySettingsCard
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.theme.resolveIsDarkTheme
import com.example.ui.theme.tabular
import com.example.ui.theme.vwColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>

    private var originalCutoutMode: Int? = null
    private var isCutoutFillApplied: Boolean = false
    private var usbDetachReceiverRegistered: Boolean = false

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                restoreDefaultWindowCutoutAndBars()
                CarLifeShizukuManager.setNoCutoutOverlayEnabledAsync(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        ThemePreferences.init(this)
        TouchSettingsPreferences.init(this)
        ScreenKeepAlivePreferences.init(this)
        BridgeTelemetryBus.restoreSavedAppMode(this)
        registerUsbDetachReceiver()

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

        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* WebView Geolocation picks up granted state immediately */ }

        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            CarLifeAccessibilityService.disarmAutoConfirmMediaProjection()
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

        if (!AppEditionConfig.isMirrorOnly) {
            CarLifeShizukuManager.init(this)
            MediaPlayerController.initialize(this)
        }
        handleUsbAccessoryIntent(intent)

        setContent {
            val activeThemeMode by ThemePreferences.themeMode.collectAsState()
            val runtimeState by BridgeTelemetryBus.runtimeState.collectAsStateWithLifecycle()
            val keepAliveConfig by ScreenKeepAlivePreferences.config.collectAsStateWithLifecycle()
            val isDarkTheme = resolveIsDarkTheme(activeThemeMode)
            val shouldFillCutout = ScreenKeepAliveManager.shouldApplyDisplayCutoutFill(
                fillCameraCutoutOnUsb = keepAliveConfig.fillCameraCutoutOnUsb,
                isUsbConnected = runtimeState.transportState is UsbTransportState.Connected,
                isStreamingOrProjecting = runtimeState.isVideoChannelReadyForStreaming ||
                    runtimeState.projectionStats.isRunning
            )

            LaunchedEffect(shouldFillCutout) {
                applyDisplayCutoutOnUsbState(shouldFillCutout)
            }

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
                    onRequestLocationPermission = {
                        requestLocationPermission()
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
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!AppEditionConfig.isMirrorOnly) {
            MediaPlayerController.refreshSessions()
            val snapshot = CarLifeShizukuManager.refreshStatus(this)
            val appMode = BridgeTelemetryBus.runtimeState.value.appMode
            val cfg = ScreenKeepAlivePreferences.config.value
            val needShizukuForMirroring = appMode == AppMode.MIRRORING &&
                cfg.fillCameraCutoutOnUsb
            if ((appMode == AppMode.SHIZUKU_COCKPIT || needShizukuForMirroring) &&
                snapshot.isBinderAlive &&
                snapshot.hasPermission
            ) {
                CarLifeShizukuManager.bindUserServiceIfReady(this)
            }
        }
    }

    override fun onDestroy() {
        restoreDefaultWindowCutoutAndBars()
        if (usbDetachReceiverRegistered) {
            runCatching { unregisterReceiver(usbDetachReceiver) }
            usbDetachReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAccessoryIntent(intent)
    }

    private fun registerUsbDetachReceiver() {
        if (usbDetachReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                this,
                usbDetachReceiver,
                IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            usbDetachReceiverRegistered = true
        }
    }

    private fun applyDisplayCutoutOnUsbState(enableFullCutout: Boolean) {
        if (enableFullCutout) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val attrs = window.attributes
                    if (originalCutoutMode == null) {
                        originalCutoutMode = attrs.layoutInDisplayCutoutMode
                    }
                    val targetMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    if (attrs.layoutInDisplayCutoutMode != targetMode) {
                        attrs.layoutInDisplayCutoutMode = targetMode
                        window.attributes = attrs
                    }
                }
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                isCutoutFillApplied = true
            }
        } else if (isCutoutFillApplied) {
            restoreDefaultWindowCutoutAndBars()
        }
    }

    private fun restoreDefaultWindowCutoutAndBars() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                val restoreMode =
                    originalCutoutMode ?: WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                if (attrs.layoutInDisplayCutoutMode != restoreMode) {
                    attrs.layoutInDisplayCutoutMode = restoreMode
                    window.attributes = attrs
                }
            }
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            isCutoutFillApplied = false
        }
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
            if (ScreenKeepAlivePreferences.config.value.autoConfirmMediaProjection) {
                CarLifeAccessibilityService.armAutoConfirmMediaProjection()
            } else {
                CarLifeAccessibilityService.disarmAutoConfirmMediaProjection()
            }
            mediaProjectionLauncher.launch(captureIntent)
        }.onFailure { e ->
            CarLifeAccessibilityService.disarmAutoConfirmMediaProjection()
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

    private fun requestLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
    val totalMandatorySteps: Int
        get() = if (AppEditionConfig.isMirrorOnly) 4 else 5

    val completedMandatorySteps: Int
        get() = if (AppEditionConfig.isMirrorOnly) {
            listOf(
                step1BatteryUnrestricted,
                step2OverlayGranted,
                step3AccessibilityEnabled,
                step5MediaProjectionGranted
            ).count { it }
        } else {
            listOf(
                step1BatteryUnrestricted,
                step2OverlayGranted,
                step3AccessibilityEnabled,
                step4NotificationAccessGranted,
                step5MediaProjectionGranted
            ).count { it }
        }

    val allPrerequisitesReady: Boolean
        get() = step1BatteryUnrestricted &&
            step2OverlayGranted &&
            step3AccessibilityEnabled &&
            (AppEditionConfig.isMirrorOnly || step4NotificationAccessGranted) &&
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

internal data class AppPermissionsSnapshot(
    val recordAudioGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val fineLocationGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationListenerGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val shizukuBinderAlive: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val shizukuAppInstalled: Boolean = false
) {
    val shizukuGranted: Boolean
        get() = shizukuBinderAlive && shizukuPermissionGranted

    val grantedCount: Int
        get() = listOf(
            recordAudioGranted,
            postNotificationsGranted,
            fineLocationGranted,
            accessibilityEnabled,
            notificationListenerGranted,
            overlayGranted,
            batteryOptimizationIgnored,
            shizukuGranted
        ).count { it }

    val totalCount: Int
        get() = 8

    val allGranted: Boolean
        get() = grantedCount == totalCount

    fun isGranted(key: AppPermissionKey): Boolean = when (key) {
        AppPermissionKey.RECORD_AUDIO -> recordAudioGranted
        AppPermissionKey.POST_NOTIFICATIONS -> postNotificationsGranted
        AppPermissionKey.FINE_LOCATION -> fineLocationGranted
        AppPermissionKey.ACCESSIBILITY -> accessibilityEnabled
        AppPermissionKey.NOTIFICATION_LISTENER -> notificationListenerGranted
        AppPermissionKey.OVERLAY -> overlayGranted
        AppPermissionKey.BATTERY_OPTIMIZATION -> batteryOptimizationIgnored
        AppPermissionKey.SHIZUKU -> shizukuGranted
    }

    fun toGrantedMap(): Map<AppPermissionKey, Boolean> =
        AppPermissionKey.entries.associateWith { isGranted(it) }

    fun missingRuntimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (!recordAudioGranted) {
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !postNotificationsGranted) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!fineLocationGranted) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missingRuntimePermissionsFor(
        modeSpec: ModeConnectionGuideSpec,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): List<String> = buildList {
        val activeKeys = modeSpec.activePermissions.map { it.key }.toSet()
        if (AppPermissionKey.POST_NOTIFICATIONS in activeKeys &&
            sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            !postNotificationsGranted
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (AppPermissionKey.FINE_LOCATION in activeKeys && !fineLocationGranted) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (AppPermissionKey.RECORD_AUDIO in activeKeys && !recordAudioGranted) {
            add(Manifest.permission.RECORD_AUDIO)
        }
    }
}

internal fun evaluateAppPermissions(
    context: Context,
    a11yRealtimeFlag: Boolean,
    shizukuStatus: ShizukuStatusState,
    sdkInt: Int = Build.VERSION.SDK_INT
): AppPermissionsSnapshot {
    val pkg = context.packageName

    val recordAudio = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val postNotifications = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val fineLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val accessibilityEnabled = a11yRealtimeFlag ||
        CarLifeAccessibilityService.isEnabledInSettings(context)

    val notificationGranted = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(pkg)
    }.getOrDefault(false)

    val overlayGranted = Settings.canDrawOverlays(context)

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(pkg) == true

    return AppPermissionsSnapshot(
        recordAudioGranted = recordAudio,
        postNotificationsGranted = postNotifications,
        fineLocationGranted = fineLocation,
        accessibilityEnabled = accessibilityEnabled,
        notificationListenerGranted = notificationGranted,
        overlayGranted = overlayGranted,
        batteryOptimizationIgnored = batteryUnrestricted,
        shizukuBinderAlive = shizukuStatus.isBinderAlive,
        shizukuPermissionGranted = shizukuStatus.hasPermission,
        shizukuAppInstalled = shizukuStatus.isShizukuAppInstalled
    )
}

@Composable
fun CarLifeBridgeApp(
    onRequestMediaProjection: () -> Unit,
    onRequestRecordAudioPermission: () -> Unit = {},
    onRequestLocationPermission: () -> Unit = {},
    onStartOrScanBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycleOwner = LocalLifecycleOwner.current

    val runtimeState by BridgeTelemetryBus.runtimeState.collectAsStateWithLifecycle()
    val mediaState by MediaPlayerController.mediaState.collectAsStateWithLifecycle()
    val a11yEnabled by CarLifeAccessibilityService.isServiceEnabled.collectAsStateWithLifecycle()
    val hasMediaProjectionToken by MediaProjectionTokenStore.hasValidToken.collectAsStateWithLifecycle()
    val shizukuStatus by CarLifeShizukuManager.status.collectAsStateWithLifecycle()

    val isMirrorOnly = AppEditionConfig.isMirrorOnly
    // Full Edition: 0 = «Лаунчер», 1 = «Подключение», 2 = «Настройки»
    // Mirror-Only Edition: 1 = «Подключение» (default), 2 = «Настройки»
    var selectedTab by rememberSaveable { mutableIntStateOf(if (isMirrorOnly) 1 else 0) }
    val effectiveSelectedTab = if (isMirrorOnly && selectedTab == 0) 1 else selectedTab
    var showSetupGuide by rememberSaveable { mutableStateOf(false) }

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

    var permissionsSnapshot by remember {
        mutableStateOf(
            evaluateAppPermissions(
                context = context,
                a11yRealtimeFlag = a11yEnabled,
                shizukuStatus = shizukuStatus
            )
        )
    }

    fun refreshAllPermissionsAndChecklist() {
        val latestShizuku = if (!isMirrorOnly) {
            CarLifeShizukuManager.refreshStatus(context)
        } else {
            shizukuStatus
        }
        checklistStatus = evaluateSetupChecklist(
            context = context,
            a11yRealtimeFlag = a11yEnabled,
            hasMediaProjectionToken = hasMediaProjectionToken,
            transportState = runtimeState.transportState
        )
        permissionsSnapshot = evaluateAppPermissions(
            context = context,
            a11yRealtimeFlag = a11yEnabled,
            shizukuStatus = latestShizuku
        )
    }

    DisposableEffect(lifecycleOwner, a11yEnabled, hasMediaProjectionToken, runtimeState.transportState, shizukuStatus) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAllPermissionsAndChecklist()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(selectedTab, a11yEnabled, hasMediaProjectionToken, runtimeState.transportState, shizukuStatus) {
        while (isActive) {
            val latest = evaluateSetupChecklist(
                context = context,
                a11yRealtimeFlag = a11yEnabled,
                hasMediaProjectionToken = hasMediaProjectionToken,
                transportState = runtimeState.transportState
            )
            val latestPerms = evaluateAppPermissions(
                context = context,
                a11yRealtimeFlag = a11yEnabled,
                shizukuStatus = shizukuStatus
            )
            checklistStatus = latest
            permissionsSnapshot = latestPerms
            // Avoid unnecessary 1s Binder IPC polling when setup is complete and user is on Launcher/Settings tabs
            if (selectedTab != 1 && latest.allPrerequisitesReady && latestPerms.allGranted) {
                break
            }
            delay(if (selectedTab == 1) 1_000L else 3_000L)
        }
    }

    val runtimePermissionsBatchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        refreshAllPermissionsAndChecklist()
        if (results[Manifest.permission.RECORD_AUDIO] == true && runtimeState.isServiceRunning) {
            val audioIntent = Intent(context, BridgeForegroundService::class.java).apply {
                action = BridgeForegroundService.ACTION_START_AUDIO_CAPTURE
            }
            safeStartService(context, audioIntent)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                runtimePermissionsBatchLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        if (!isMirrorOnly) {
            CarLifeLauncherRepository.loadInstalledApps(context)
        }
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
        topBar = {
            VwPhoneTopBar(
                isUsbConnected = runtimeState.transportState is UsbTransportState.Connected,
                isMirrorOnly = isMirrorOnly
            )
        },
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
                            if (!isMirrorOnly) {
                                VwBottomNavItem(
                                    label = "Лаунчер",
                                    icon = CockpitIcons.AppsGrid,
                                    selected = effectiveSelectedTab == 0,
                                    testTag = "nav_app_launcher",
                                    onClick = { selectedTab = 0 },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            VwBottomNavItem(
                                label = "Подключение",
                                icon = Icons.Default.CheckCircle,
                                selected = effectiveSelectedTab == 1,
                                testTag = "nav_setup_checklist",
                                onClick = { selectedTab = 1 },
                                modifier = Modifier.weight(1f)
                            )
                            VwBottomNavItem(
                                label = "Настройки",
                                icon = Icons.Default.Settings,
                                selected = effectiveSelectedTab == 2,
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
                            if (!isMirrorOnly) {
                                VwRailNavItem(
                                    label = "Лаунчер",
                                    icon = CockpitIcons.AppsGrid,
                                    selected = effectiveSelectedTab == 0,
                                    testTag = "nav_app_launcher",
                                    onClick = { selectedTab = 0 }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            VwRailNavItem(
                                label = "Подключ.",
                                icon = Icons.Default.CheckCircle,
                                selected = effectiveSelectedTab == 1,
                                testTag = "nav_setup_checklist",
                                onClick = { selectedTab = 1 }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            VwRailNavItem(
                                label = "Настройки",
                                icon = Icons.Default.Settings,
                                selected = effectiveSelectedTab == 2,
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
                when (effectiveSelectedTab) {
                    0 -> CarLifeAppLauncherScreen(
                        virtualDisplayId = runtimeState.projectionStats.virtualDisplayId,
                        mediaState = mediaState,
                        onPlayPause = { MediaPlayerController.playPause() },
                        onNextTrack = { MediaPlayerController.next() },
                        onPreviousTrack = { MediaPlayerController.previous() },
                        onRequestNotificationAccess = {
                            MediaPlayerController.openNotificationListenerSettings(context)
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
                        isShizukuMode = runtimeState.appMode == AppMode.SHIZUKU_COCKPIT,
                        modifier = Modifier.fillMaxSize()
                    )

                    1 -> OnboardingChecklistScreen(
                        status = checklistStatus,
                        permissions = permissionsSnapshot,
                        runtimeState = runtimeState,
                        onSelectAppMode = { mode ->
                            if (!isMirrorOnly) {
                                BridgeTelemetryBus.setAppMode(mode, context)
                                if (mode == AppMode.SHIZUKU_COCKPIT) {
                                    CarLifeShizukuManager.init(context)
                                    CarLifeShizukuManager.bindUserServiceIfReady(context)
                                }
                                if (runtimeState.isServiceRunning) {
                                    val intent = Intent(context, BridgeForegroundService::class.java).apply {
                                        action = BridgeForegroundService.ACTION_SET_APP_MODE
                                        putExtra(BridgeForegroundService.EXTRA_APP_MODE, mode.id)
                                    }
                                    safeStartService(context, intent)
                                }
                            }
                        },
                        onGrantBasicRuntimePermissions = { missing ->
                            if (missing.isNotEmpty()) {
                                runtimePermissionsBatchLauncher.launch(missing.toTypedArray())
                            }
                        },
                        onRequestSingleRuntimePermission = { perm ->
                            runtimePermissionsBatchLauncher.launch(arrayOf(perm))
                        },
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
                            MediaPlayerController.openNotificationListenerSettings(context)
                        },
                        onRequestOrOpenShizuku = {
                            val latestShizuku = CarLifeShizukuManager.refreshStatus(context)
                            if (latestShizuku.isBinderAlive) {
                                CarLifeShizukuManager.requestPermission(context)
                            } else {
                                openShizukuAppOrPage(context)
                            }
                            refreshAllPermissionsAndChecklist()
                        },
                        onRequestMediaProjection = onRequestMediaProjection,
                        onScanAndAttachUsb = onStartOrScanBridge,
                        onOpenLauncherTab = { selectedTab = 0 },
                        onOpenSetupGuide = { showSetupGuide = true }
                    )

                    2 -> UnifiedSettingsAndDiagnosticsScreen(
                        state = runtimeState,
                        mediaState = mediaState,
                        checklistStatus = checklistStatus,
                        permissions = permissionsSnapshot,
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
                        onOpenOverlaySettings = {
                            openOverlayPermissionSettings(context)
                        },
                        onStartOrScanBridge = onStartOrScanBridge,
                        onStopBridge = onStopBridge,
                        onStartProjection = onStartProjection,
                        onStopProjection = onStopProjection,
                        onRequestMediaProjection = onRequestMediaProjection,
                        onOpenConnectionTab = { selectedTab = 1 }
                    )
                }
            }
        }
    }

    if (showSetupGuide) {
        SetupGuideScreen(
            onDismiss = { showSetupGuide = false },
            onOpenConnectionTab = { selectedTab = 1 },
            onOpenSettingsTab = { selectedTab = 2 },
            onOpenAppDetailsSettings = { openAppDetailsSettings(context) },
            onOpenAccessibilitySettings = {
                CarLifeAccessibilityService.openAccessibilitySettings(context)
            },
            onOpenNotificationListenerSettings = {
                MediaPlayerController.openNotificationListenerSettings(context)
            },
            onOpenDisplaySettings = { openDisplaySettings(context) },
            onOpenShizuku = { openShizukuAppOrPage(context) },
            onOpenDeveloperSettings = { openDeveloperSettings(context) }
        )
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

private fun openShizukuAppOrPage(context: Context) {
    val launched = runCatching {
        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } ?: false
    }.getOrDefault(false)
    if (!launched) {
        runCatching {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://shizuku.rikka.app/")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}

private fun openDisplaySettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

private fun openDeveloperSettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

@Composable
private fun VwPhoneTopBar(
    isUsbConnected: Boolean,
    isMirrorOnly: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vw_phone_top_bar"),
        color = colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isUsbConnected) vw.statusActiveDot else vw.accentBlue)
                    )
                    Text(
                        text = "JETTA Connect",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.2.sp
                        ),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("topbar_app_title")
                    )
                    if (isMirrorOnly) {
                        VwStatusBadge(
                            label = "Mirror",
                            tone = VwBadgeTone.WAITING
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorScheme.outline)
            )
        }
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

private fun AppMode.shortConnectionLabel(): String = when (this) {
    AppMode.COCKPIT -> "Кокпит"
    AppMode.MIRRORING -> "Зеркало"
    AppMode.SHIZUKU_COCKPIT -> "Shizuku"
}

@Composable
private fun OnboardingChecklistScreen(
    status: SetupChecklistStatus,
    permissions: AppPermissionsSnapshot,
    runtimeState: BridgeRuntimeState,
    onSelectAppMode: (AppMode) -> Unit = {},
    onGrantBasicRuntimePermissions: (List<String>) -> Unit,
    onRequestSingleRuntimePermission: (String) -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestOrOpenShizuku: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onScanAndAttachUsb: () -> Unit,
    onOpenLauncherTab: () -> Unit,
    onOpenSetupGuide: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val isMirrorOnly = AppEditionConfig.isMirrorOnly
    val activeMode = if (isMirrorOnly) AppMode.MIRRORING else runtimeState.appMode
    val modeSpec = remember(activeMode) { ConnectionModeRequirements.specFor(activeMode) }
    val readiness = remember(activeMode, permissions, status.step5MediaProjectionGranted) {
        ConnectionModeRequirements.evaluateReadiness(
            mode = activeMode,
            grantedMap = permissions.toGrantedMap(),
            hasMediaProjectionToken = status.step5MediaProjectionGranted
        )
    }
    val modeShortTitle = activeMode.shortConnectionLabel()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Заголовочная карточка мастера подключения + выбор типа подключения
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
                                text = if (isMirrorOnly) {
                                    "JETTA Connect • ЗЕРКАЛИРОВАНИЕ"
                                } else {
                                    "JETTA Connect • ПОДКЛЮЧЕНИЕ"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = vw.accentBlue
                            )
                            Text(
                                text = "Подготовка к подключению ($modeShortTitle)",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = "${readiness.grantedRequiredCount} / ${readiness.totalRequiredCount}",
                            tone = if (readiness.allMandatoryReady) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
                        )
                    }

                    Text(
                        text = if (readiness.allMandatoryReady) {
                            "Все обязательные доступы для типа подключения «$modeShortTitle» готовы. Можно подключать USB-кабель к ГУ автомобиля."
                        } else {
                            "Для типа подключения «$modeShortTitle» выдайте обязательные разрешения ниже (${readiness.missingRequiredLabels.size} ост.) перед подключением USB."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )

                    val progressFraction = if (readiness.totalRequiredCount > 0) {
                        readiness.grantedRequiredCount / readiness.totalRequiredCount.toFloat()
                    } else {
                        1f
                    }
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = colorScheme.primary,
                        trackColor = colorScheme.surfaceVariant
                    )

                    if (!isMirrorOnly) {
                        Text(
                            text = "Выберите тип подключения к ГУ:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorScheme.background)
                                .border(1.dp, colorScheme.outline, RoundedCornerShape(12.dp))
                                .padding(3.dp)
                                .testTag("connection_mode_selector"),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            AppMode.entries.forEach { mode ->
                                val isSelected = activeMode == mode
                                val tag = when (mode) {
                                    AppMode.COCKPIT -> "connection_mode_cockpit_btn"
                                    AppMode.MIRRORING -> "connection_mode_mirroring_btn"
                                    AppMode.SHIZUKU_COCKPIT -> "connection_mode_shizuku_btn"
                                }
                                val segmentBg by animateColorAsState(
                                    targetValue = if (isSelected) colorScheme.primary else Color.Transparent,
                                    animationSpec = tween(durationMillis = 180),
                                    label = "conn_mode_seg_bg"
                                )
                                val segmentText by animateColorAsState(
                                    targetValue = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                    animationSpec = tween(durationMillis = 180),
                                    label = "conn_mode_seg_txt"
                                )
                                val bottomLine by animateColorAsState(
                                    targetValue = if (isSelected) {
                                        if (vw.isDark) Color.White.copy(alpha = 0.85f) else vw.accentBlue
                                    } else {
                                        Color.Transparent
                                    },
                                    animationSpec = tween(durationMillis = 180),
                                    label = "conn_mode_seg_line"
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(segmentBg)
                                        .selectable(
                                            selected = isSelected,
                                            onClick = { onSelectAppMode(mode) },
                                            role = Role.RadioButton
                                        )
                                        .testTag(tag),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = mode.shortConnectionLabel(),
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
                    }

                    // Разъяснение: что именно нужно включить для выбранного типа подключения
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connection_mode_explanation_card"),
                        color = colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = modeSpec.headline,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = modeSpec.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            Surface(
                                color = vw.infoContainer,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, vw.accentBlue.copy(alpha = 0.28f))
                            ) {
                                Text(
                                    text = modeSpec.mandatoryChecklistNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = vw.onInfoContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = onOpenSetupGuide,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("connection_guide_btn"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colorScheme.primaryContainer,
                            contentColor = colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = CockpitIcons.MenuBook,
                            contentDescription = "Инструкция по подключению и настройке",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Инструкция по подключению и настройке (6 шагов)",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 2. РАЗРЕШЕНИЯ ДЛЯ ВЫБРАННОГО ТИПА ПОДКЛЮЧЕНИЯ
        item {
            UnifiedAppPermissionsCard(
                permissions = permissions,
                modeSpec = modeSpec,
                onGrantBasicRuntimePermissions = onGrantBasicRuntimePermissions,
                onRequestSingleRuntimePermission = onRequestSingleRuntimePermission,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                onRequestOrOpenShizuku = onRequestOrOpenShizuku
            )
        }

        // 3. ЗАХВАТ ЭКРАНА (MediaProjection) — обязателен для «Зеркало», для звука 0x0003 в остальных
        item {
            val mpRequired = modeSpec.mediaProjectionRelevance == RequirementRelevance.REQUIRED
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(
                    1.dp,
                    if (mpRequired && !status.step5MediaProjectionGranted) {
                        colorScheme.error.copy(alpha = 0.45f)
                    } else {
                        colorScheme.outline
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Захват экрана (MediaProjection)",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = modeSpec.mediaProjectionBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (mpRequired) colorScheme.error else vw.accentBlue
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadgePill(isCompleted = status.step5MediaProjectionGranted)
                    }

                    Surface(
                        color = if (mpRequired) colorScheme.surfaceVariant else vw.infoContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (mpRequired) colorScheme.outline else vw.accentBlue.copy(alpha = 0.25f)
                        )
                    ) {
                        Text(
                            text = if (mpRequired) {
                                buildAnnotatedString {
                                    append("Нажмите «Начать трансляцию». ")
                                    withStyle(
                                        SpanStyle(
                                            color = colorScheme.error,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    ) {
                                        append(modeSpec.mediaProjectionExplanation)
                                    }
                                }
                            } else {
                                buildAnnotatedString {
                                    append(modeSpec.mediaProjectionExplanation)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (mpRequired) colorScheme.onSurface else vw.onInfoContainer,
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
                            text = when {
                                status.step5MediaProjectionGranted ->
                                    "Захват «Весь экран» активен (Перезапустить)"
                                mpRequired ->
                                    "Начать трансляцию (Выбрать «Весь экран»)"
                                else ->
                                    "Разрешить «Весь экран» (для звука 0x0003)"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 4. ПОДКЛЮЧЕНИЕ USB-КАБЕЛЯ К ГУ
        item {
            val step6Active = readiness.allMandatoryReady
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
                            text = "Подключение USB-кабеля к ГУ автомобиля",
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
                        text = if (step6Active) {
                            "Подключите телефон кабелем к USB-порту автомобиля и нажмите на всплывающем окне «ОК» (всегда открывать JETTA Connect при подключении)."
                        } else {
                            "Для запуска режима «$modeShortTitle» осталось выдать: ${readiness.missingRequiredLabels.joinToString(", ")}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step6Active) colorScheme.onSurfaceVariant else colorScheme.error
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

                        if (!isMirrorOnly) {
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
}

@Composable
private fun UnifiedAppPermissionsCard(
    permissions: AppPermissionsSnapshot,
    modeSpec: ModeConnectionGuideSpec,
    onGrantBasicRuntimePermissions: (List<String>) -> Unit,
    onRequestSingleRuntimePermission: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onRequestOrOpenShizuku: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val modeShortTitle = modeSpec.mode.shortConnectionLabel()

    val requiredSpecs = modeSpec.activePermissions.filter { it.relevance == RequirementRelevance.REQUIRED }
    val requiredGrantedCount = requiredSpecs.count { permissions.isGranted(it.key) }
    val allModeRequiredGranted = requiredGrantedCount == requiredSpecs.size
    val activeSpecs = modeSpec.activePermissions
    val activeGrantedCount = activeSpecs.count { permissions.isGranted(it.key) }
    val allModeActiveGranted = activeGrantedCount == activeSpecs.size

    var expandedWhenAllGranted by rememberSaveable(modeSpec.mode.id) { mutableStateOf(false) }
    var notNeededExpanded by rememberSaveable(modeSpec.mode.id) { mutableStateOf(false) }
    val showActiveList = !allModeActiveGranted || expandedWhenAllGranted
    val missingRuntimeForMode = permissions.missingRuntimePermissionsFor(modeSpec)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unified_permissions_card"),
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
                        text = "Разрешения для режима «$modeShortTitle»",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "Обязательные: $requiredGrantedCount из ${requiredSpecs.size} • Всего для режима: $activeGrantedCount из ${activeSpecs.size}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = if (allModeRequiredGranted) vw.onSuccessContainer else colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("unified_permissions_counter")
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                VwStatusBadge(
                    label = "$requiredGrantedCount / ${requiredSpecs.size}",
                    tone = if (allModeRequiredGranted) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
                )
            }

            if (allModeActiveGranted) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { expandedWhenAllGranted = !expandedWhenAllGranted }
                        .testTag("unified_permissions_collapsed_row"),
                    color = vw.successContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, vw.statusActiveDot.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Все разрешения режима выданы",
                                tint = vw.statusActiveDot,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Все доступы режима «$modeShortTitle» выданы ($activeGrantedCount/${activeSpecs.size})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = vw.onSuccessContainer
                            )
                        }
                        Icon(
                            imageVector = if (expandedWhenAllGranted) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = "Показать список разрешений",
                            tint = vw.onSuccessContainer
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showActiveList) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (missingRuntimeForMode.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { onGrantBasicRuntimePermissions(missingRuntimeForMode) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("perm_grant_basic_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colorScheme.primaryContainer,
                                contentColor = colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text(
                                text = "Выдать базовые для «$modeShortTitle» (${missingRuntimeForMode.size})",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    activeSpecs.forEach { spec ->
                        PermissionSpecRow(
                            spec = spec,
                            permissions = permissions,
                            onRequestSingleRuntimePermission = onRequestSingleRuntimePermission,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                            onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                            onRequestOrOpenShizuku = onRequestOrOpenShizuku
                        )
                    }
                }
            }

            if (modeSpec.notNeededPermissions.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { notNeededExpanded = !notNeededExpanded }
                        .testTag("perm_not_needed_toggle"),
                    color = colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Не требуются в режиме «$modeShortTitle» (${modeSpec.notNeededPermissions.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (notNeededExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = "Показать необязательные разрешения",
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                }

                AnimatedVisibility(visible = notNeededExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        modeSpec.notNeededPermissions.forEach { spec ->
                            PermissionSpecRow(
                                spec = spec,
                                permissions = permissions,
                                onRequestSingleRuntimePermission = onRequestSingleRuntimePermission,
                                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                                onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                                onRequestOverlayPermission = onRequestOverlayPermission,
                                onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                                onRequestOrOpenShizuku = onRequestOrOpenShizuku
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionSpecRow(
    spec: PermissionRequirementSpec,
    permissions: AppPermissionsSnapshot,
    onRequestSingleRuntimePermission: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onRequestOrOpenShizuku: () -> Unit
) {
    val granted = permissions.isGranted(spec.key)
    val subtitle = when (spec.key) {
        AppPermissionKey.POST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                spec.reason
            } else {
                "${spec.reason} (выдано по умолчанию на Android < 13)"
            }
        AppPermissionKey.SHIZUKU -> when {
            permissions.shizukuGranted -> spec.reason
            permissions.shizukuBinderAlive -> "${spec.reason} • служба активна, нужны права"
            else -> "${spec.reason} • Служба не запущена"
        }
        else -> spec.reason
    }

    val actionLabel = when (spec.key) {
        AppPermissionKey.RECORD_AUDIO,
        AppPermissionKey.POST_NOTIFICATIONS,
        AppPermissionKey.FINE_LOCATION -> "Выдать"
        AppPermissionKey.SHIZUKU -> if (permissions.shizukuBinderAlive) "Выдать" else "Открыть Shizuku"
        AppPermissionKey.ACCESSIBILITY,
        AppPermissionKey.NOTIFICATION_LISTENER,
        AppPermissionKey.OVERLAY,
        AppPermissionKey.BATTERY_OPTIMIZATION -> "Настроить"
    }

    val testTag = when (spec.key) {
        AppPermissionKey.RECORD_AUDIO -> "perm_item_record_audio_btn"
        AppPermissionKey.POST_NOTIFICATIONS -> "perm_item_notifications_btn"
        AppPermissionKey.FINE_LOCATION -> "perm_item_location_btn"
        AppPermissionKey.ACCESSIBILITY -> "perm_item_accessibility_btn"
        AppPermissionKey.NOTIFICATION_LISTENER -> "perm_item_notification_listener_btn"
        AppPermissionKey.OVERLAY -> "perm_item_overlay_btn"
        AppPermissionKey.BATTERY_OPTIMIZATION -> "perm_item_battery_btn"
        AppPermissionKey.SHIZUKU -> "perm_item_shizuku_btn"
    }

    val onActionClick: () -> Unit = when (spec.key) {
        AppPermissionKey.RECORD_AUDIO -> {
            { onRequestSingleRuntimePermission(Manifest.permission.RECORD_AUDIO) }
        }
        AppPermissionKey.POST_NOTIFICATIONS -> {
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    onRequestSingleRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        AppPermissionKey.FINE_LOCATION -> {
            { onRequestSingleRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION) }
        }
        AppPermissionKey.ACCESSIBILITY -> onOpenAccessibilitySettings
        AppPermissionKey.NOTIFICATION_LISTENER -> onOpenNotificationListenerSettings
        AppPermissionKey.OVERLAY -> onRequestOverlayPermission
        AppPermissionKey.BATTERY_OPTIMIZATION -> onRequestBatteryUnrestricted
        AppPermissionKey.SHIZUKU -> onRequestOrOpenShizuku
    }

    val secondaryLinkLabel = if (spec.key == AppPermissionKey.ACCESSIBILITY && !granted) {
        "О приложении (Android 16: Разрешить ограниченные настройки)"
    } else {
        null
    }

    PermissionChecklistItemRow(
        title = spec.title,
        subtitle = subtitle,
        roleBadge = spec.roleBadge,
        relevance = spec.relevance,
        granted = granted,
        actionLabel = actionLabel,
        testTag = testTag,
        useWaitingToneWhenMissing = (spec.key == AppPermissionKey.SHIZUKU && !permissions.shizukuBinderAlive) ||
            spec.relevance != RequirementRelevance.REQUIRED,
        onActionClick = onActionClick,
        secondaryLinkLabel = secondaryLinkLabel,
        onSecondaryLinkClick = if (secondaryLinkLabel != null) onOpenAppDetailsSettings else null
    )
}

@Composable
private fun PermissionChecklistItemRow(
    title: String,
    subtitle: String,
    roleBadge: String,
    relevance: RequirementRelevance,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    useWaitingToneWhenMissing: Boolean = false,
    onActionClick: () -> Unit,
    secondaryLinkLabel: String? = null,
    onSecondaryLinkClick: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val indicatorColor = when {
        granted -> vw.statusActiveDot
        relevance == RequirementRelevance.NOT_NEEDED -> vw.textMuted
        useWaitingToneWhenMissing -> vw.statusWaitingDot
        else -> colorScheme.error
    }
    val roleBg = when {
        granted -> vw.successContainer
        relevance == RequirementRelevance.REQUIRED -> colorScheme.errorContainer
        relevance == RequirementRelevance.OPTIONAL_FEATURE -> vw.infoContainer
        else -> colorScheme.surfaceVariant
    }
    val roleFg = when {
        granted -> vw.onSuccessContainer
        relevance == RequirementRelevance.REQUIRED -> colorScheme.onErrorContainer
        relevance == RequirementRelevance.OPTIONAL_FEATURE -> vw.onInfoContainer
        else -> colorScheme.onSurfaceVariant
    }

    Surface(
        color = colorScheme.background,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (granted || relevance == RequirementRelevance.NOT_NEEDED) {
                colorScheme.outline
            } else {
                indicatorColor.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurface
                        )
                        Surface(
                            color = roleBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (granted) "$roleBadge • Выдано" else roleBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = roleFg,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (granted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Выдано",
                        tint = vw.statusActiveDot,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    FilledTonalButton(
                        onClick = onActionClick,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(testTag),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colorScheme.primaryContainer,
                            contentColor = colorScheme.onPrimaryContainer
                        )
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            softWrap = false
                        )
                    }
                }
            }

            if (secondaryLinkLabel != null && onSecondaryLinkClick != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSecondaryLinkClick)
                        .testTag("${testTag}_secondary"),
                    color = colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colorScheme.outline)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = secondaryLinkLabel,
                            style = MaterialTheme.typography.labelSmall,
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
 * Tab 3 («Настройки») — только рабочие настройки при использовании приложения:
 * 1. Общие настройки (Оформление Авто/Светлая/Тёмная, заполнение выреза камеры по USB).
 * 2. Режим работы (Кокпит | Зеркало | Shizuku + диагностика трансляции).
 * 3. Чувствительность тача (0x0006).
 * 4. Экран и питание при Зеркалировании (KeepAlive + 1% OLED + 16:9 Shizuku).
 * 5. Звук по USB AOA (0x0003 PCM + приглушение динамика).
 * 6. Параметры видеопотока H.264 (разрешение и битрейт).
 * 7. Сворачиваемый раздел «Лог пакетов и диагностика USB AOA» (без тестовых генераторов).
 */
@Composable
private fun UnifiedSettingsAndDiagnosticsScreen(
    state: BridgeRuntimeState,
    mediaState: MediaState,
    checklistStatus: SetupChecklistStatus,
    permissions: AppPermissionsSnapshot = AppPermissionsSnapshot(),
    configuredWidth: Int,
    configuredHeight: Int,
    configuredBitrateKbps: Int,
    onSelectResolution: (Int, Int) -> Unit,
    onSelectBitrate: (Int) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStartOrScanBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit,
    onRequestMediaProjection: () -> Unit = {},
    onOpenConnectionTab: () -> Unit = {}
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val isMirrorOnly = AppEditionConfig.isMirrorOnly
    val activeAppMode = if (isMirrorOnly) AppMode.MIRRORING else state.appMode
    val activeModeSpec = remember(activeAppMode) { ConnectionModeRequirements.specFor(activeAppMode) }
    val activeReadiness = remember(activeAppMode, permissions, checklistStatus.step5MediaProjectionGranted) {
        ConnectionModeRequirements.evaluateReadiness(
            mode = activeAppMode,
            grantedMap = permissions.toGrantedMap(),
            hasMediaProjectionToken = checklistStatus.step5MediaProjectionGranted
        )
    }
    val activeThemeMode by ThemePreferences.themeMode.collectAsState()
    val keepAliveConfig by ScreenKeepAlivePreferences.config.collectAsStateWithLifecycle()
    val steeringWheelConfig by TouchSettingsPreferences.steeringWheelConfig.collectAsStateWithLifecycle()
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var mirroringDiagExpanded by rememberSaveable { mutableStateOf(false) }
    // Tab 0 (default): «Команды 0x0001 & 0x0006 (RX/TX)»
    // Tab 1: «Видеопоток 0x0002»
    var selectedLogTab by rememberSaveable { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Общие настройки — Оформление + Доступ к медиаплееру + Геолокация Яндекс Карт
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Общие настройки",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = if (isMirrorOnly) {
                                    "Оформление VW MIB3 и область камеры • Mirror Only"
                                } else {
                                    "Оформление VW MIB3 и отображение области выреза камеры"
                                },
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

                    // Переключатель «Оформление»: Авто | Светлая | Тёмная
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

                    // Тумблер «Заполнять область выреза камеры при подключении USB»
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
                                text = "Заполнять область выреза камеры при подключении USB",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = if (keepAliveConfig.fillCameraCutoutOnUsb) {
                                    "Вкл — скрывает системные панели, расширяет окно на вырез (а в «Зеркале» включает оверлей Shizuku) и полностью возвращает штатный экран после отключения USB"
                                } else {
                                    "Выкл — окно и системные панели сохраняют стандартный отступ у камеры"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = keepAliveConfig.fillCameraCutoutOnUsb,
                            onCheckedChange = { enabled ->
                                ScreenKeepAlivePreferences.updateConfig(context) {
                                    it.copy(fillCameraCutoutOnUsb = enabled)
                                }
                                if (state.isServiceRunning) {
                                    val updateIntent = Intent(context, BridgeForegroundService::class.java).apply {
                                        action = BridgeForegroundService.ACTION_UPDATE_SCREEN_KEEP_ALIVE
                                    }
                                    safeStartService(context, updateIntent)
                                }
                            },
                            modifier = Modifier.testTag("fill_camera_cutout_usb_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colorScheme.onPrimary,
                                checkedTrackColor = colorScheme.primary,
                                uncheckedThumbColor = colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = colorScheme.surfaceVariant
                            )
                        )
                    }

                    // Раздел «Кнопки на руле» — инверсия треков Вперёд / Назад (0x00060008)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.background)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("steering_wheel_settings_section"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Кнопки на руле (0x00060008)",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = colorScheme.onSurface
                            )
                            VwStatusBadge(
                                label = if (steeringWheelConfig.swapTrackButtons) "ИНВЕРСИЯ" else "ШТАТНО",
                                tone = if (steeringWheelConfig.swapTrackButtons) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
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
                                    text = "Поменять местами Вперёд / Назад",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = if (steeringWheelConfig.swapTrackButtons) {
                                        "Вкл — коды 15 (0x0F) / 7 и 16 (0x10) / 6 поменяны местами без пересборки"
                                    } else {
                                        "Выкл — 15 (0x0F) / 7 = Предыдущий трек, 16 (0x10) / 6 = Следующий трек, 20 (0x14) = OK / Пауза"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = steeringWheelConfig.swapTrackButtons,
                                onCheckedChange = { enabled ->
                                    TouchSettingsPreferences.setSwapSteeringWheelTrackButtons(enabled, context)
                                },
                                modifier = Modifier.testTag("swap_steering_wheel_tracks_switch"),
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
        }

        // 2. Тип подключения и параметры режима работы
        item {
            val shizukuStatus by CarLifeShizukuManager.status.collectAsStateWithLifecycle()
            val mpIsActive by MediaProjectionTokenStore.isProjectionActive.collectAsStateWithLifecycle()
            val mpHasPending by MediaProjectionTokenStore.hasPendingToken.collectAsStateWithLifecycle()
            val mpError by MediaProjectionTokenStore.lastError.collectAsStateWithLifecycle()
            val mpDetail by MediaProjectionTokenStore.statusDetail.collectAsStateWithLifecycle()

            if (!isMirrorOnly) {
                LaunchedEffect(activeAppMode) {
                    CarLifeShizukuManager.refreshStatus(context)
                    if (activeAppMode == AppMode.SHIZUKU_COCKPIT) {
                        CarLifeShizukuManager.bindUserServiceIfReady(context)
                    }
                }
            }

            fun selectAppMode(mode: AppMode) {
                if (isMirrorOnly) return
                BridgeTelemetryBus.setAppMode(mode, context)
                when (mode) {
                    AppMode.SHIZUKU_COCKPIT -> {
                        CarLifeShizukuManager.init(context)
                        CarLifeShizukuManager.bindUserServiceIfReady(context)
                    }
                    AppMode.MIRRORING -> {
                        if (!state.isServiceRunning) {
                            val keepAliveCfg = ScreenKeepAlivePreferences.config.value
                            val snap = CarLifeShizukuManager.refreshStatus(context)
                            if (keepAliveCfg.fillCameraCutoutOnUsb &&
                                snap.isBinderAlive &&
                                snap.hasPermission
                            ) {
                                CarLifeShizukuManager.init(context)
                                CarLifeShizukuManager.bindUserServiceIfReady(context)
                            } else {
                                CarLifeShizukuManager.cleanupAndResetThenUnbind(context)
                            }
                        }
                        if (!MediaProjectionTokenStore.hasValidToken.value) {
                            onRequestMediaProjection()
                        }
                    }
                    AppMode.COCKPIT -> {
                        if (!state.isServiceRunning) {
                            CarLifeShizukuManager.cleanupAndResetThenUnbind(context)
                        }
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
                                text = if (isMirrorOnly) {
                                    "Jetta Mirror • Только Зеркалирование"
                                } else {
                                    "Режим работы"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isMirrorOnly) {
                                    "Облегчённая сборка: прямой захват экрана 30 FPS + звук 0x0003 без лаунчера и Shizuku"
                                } else {
                                    "Кокпит с картой, Зеркалирование экрана или Shizuku-навигатор"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        VwStatusBadge(
                            label = if (isMirrorOnly) {
                                "MIRROR ONLY"
                            } else {
                                when (activeAppMode) {
                                    AppMode.COCKPIT -> "Кокпит"
                                    AppMode.MIRRORING -> "Зеркало"
                                    AppMode.SHIZUKU_COCKPIT -> "Shizuku"
                                }
                            },
                            tone = VwBadgeTone.ACTIVE
                        )
                    }

                    if (!isMirrorOnly) {
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

                        // Описание активного типа подключения и его рабочих параметров
                        Surface(
                            color = colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = activeModeSpec.headline,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    VwStatusBadge(
                                        label = "${activeReadiness.grantedRequiredCount}/${activeReadiness.totalRequiredCount}",
                                        tone = if (activeReadiness.allMandatoryReady) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING
                                    )
                                }
                                Text(
                                    text = activeModeSpec.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when (activeAppMode) {
                                        AppMode.COCKPIT ->
                                            "В дороге: панель VW 250 px слева, сетка приложений и встроенная карта; тач работает напрямую, ниже доступны настройки звука 0x0003 и видео H.264."
                                        AppMode.MIRRORING ->
                                            "В дороге: полная трансляция экрана 30 FPS; ниже активны настройки чувствительности тача Accessibility и KeepAlive / 1% OLED."
                                        AppMode.SHIZUKU_COCKPIT ->
                                            "В дороге: Навигатор/2ГИС на втором дисплее + левая панель VW поверх; ниже отображаются параметры Shizuku, звука 0x0003 и видео H.264."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = vw.onInfoContainer
                                )
                                if (!activeReadiness.allMandatoryReady) {
                                    Text(
                                        text = "Не хватает для старта: ${activeReadiness.missingRequiredLabels.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    // Диагностика Зеркалирования по 5 узлам (открыта в режиме Зеркало или при ошибке, сворачиваема в остальных)
                    val projStats = state.projectionStats
                    val audioStatsForDiag = state.audioCaptureStats
                    val hasRecordAudioForDiag = remember(audioStatsForDiag.hasRecordAudioPermission, checklistStatus) {
                        CarLifeAudioCaptureManager.hasRecordAudioPermission(context)
                    }
                    val effectiveDiagnosticError = mpError ?: projStats.lastMediaProjectionError
                    val showMirroringDetails = activeAppMode == AppMode.MIRRORING ||
                        effectiveDiagnosticError != null ||
                        audioStatsForDiag.isSilenceWarningActive ||
                        mirroringDiagExpanded

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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { mirroringDiagExpanded = !mirroringDiagExpanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Состояние трансляции (5 узлов)",
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
                                        else -> if (projStats.isRunning) "АКТИВЕН" else "ГОТОВ"
                                    },
                                    tone = when {
                                        effectiveDiagnosticError != null -> VwBadgeTone.ERROR
                                        projStats.usingMediaProjection || mpIsActive || projStats.isRunning -> VwBadgeTone.ACTIVE
                                        else -> VwBadgeTone.WAITING
                                    }
                                )
                                Icon(
                                    imageVector = if (showMirroringDetails) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = "Показать диагностику трансляции",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            AnimatedVisibility(visible = showMirroringDetails) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Узел 1: Режим
                                    MirroringNodeStatusRow(
                                        index = "1",
                                        label = "Режим",
                                        statusBadge = when (activeAppMode) {
                                            AppMode.COCKPIT -> "КОКПИТ"
                                            AppMode.MIRRORING -> "ЗЕРКАЛО"
                                            AppMode.SHIZUKU_COCKPIT -> "SHIZUKU"
                                        },
                                        badgePositive = true,
                                        detail = activeAppMode.title +
                                            if (activeAppMode == AppMode.MIRRORING) " • откат в Кокпит заблокирован" else ""
                                    )

                                    // Узел 2: FGS + MediaProjection (видео в Зеркале / звук 0x0003 в остальных режимах)
                                    val fgsMpOk = projStats.usingMediaProjection || mpIsActive
                                    val mpRequiredForMode = activeAppMode == AppMode.MIRRORING
                                    val fgsBadge = when {
                                        fgsMpOk && projStats.isMediaProjectionFgsActive -> "FGS+MP ОК"
                                        fgsMpOk -> "MP LIVE"
                                        !mpRequiredForMode && projStats.isRunning -> "ВИДЕО ОК"
                                        !mpRequiredForMode -> "ЗВУК ОПЦ."
                                        projStats.isMediaProjectionFgsActive -> "FGS ОК"
                                        mpHasPending -> "ТОКЕН ЖДЁТ"
                                        else -> "ОЖИД. MP"
                                    }
                                    MirroringNodeStatusRow(
                                        index = "2",
                                        label = if (mpRequiredForMode) "FGS + MediaProjection" else "Захват экрана / звука",
                                        statusBadge = fgsBadge,
                                        badgePositive = fgsMpOk || !mpRequiredForMode,
                                        detail = if (mpRequiredForMode) {
                                            "${projStats.mediaProjectionState} • FGS=${if (projStats.isMediaProjectionFgsActive) "ДА" else "НЕТ"} ($mpDetail)"
                                        } else {
                                            "Видеопоток без MP • $mpDetail"
                                        }
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
                                        "VD #${projStats.virtualDisplayId} (${projStats.width}×${projStats.height})"
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

                                    // Узел 4: Тач 0x0006 (зависит от типа подключения)
                                    val a11yOk = checklistStatus.step3AccessibilityEnabled
                                    val shizukuTouchOk = shizukuStatus.isBinderAlive && shizukuStatus.hasPermission
                                    MirroringNodeStatusRow(
                                        index = "4",
                                        label = when (activeAppMode) {
                                            AppMode.COCKPIT -> "Тач 0x0006 (Кокпит)"
                                            AppMode.MIRRORING -> "Тач 0x0006 (A11y)"
                                            AppMode.SHIZUKU_COCKPIT -> "Тач 0x0006 (Shizuku)"
                                        },
                                        statusBadge = when (activeAppMode) {
                                            AppMode.COCKPIT -> "ПРЯМОЙ"
                                            AppMode.MIRRORING -> if (a11yOk) "ВКЛ" else "ВЫКЛ"
                                            AppMode.SHIZUKU_COCKPIT -> if (shizukuTouchOk) "SHIZUKU" else "НЕТ SHIZUKU"
                                        },
                                        badgePositive = when (activeAppMode) {
                                            AppMode.COCKPIT -> true
                                            AppMode.MIRRORING -> a11yOk
                                            AppMode.SHIZUKU_COCKPIT -> shizukuTouchOk
                                        },
                                        detail = projStats.lastTouchDiagnostic
                                    )

                                    // Узел 5: Аудио 0x0003
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

                                    val needsConnectionSetup = !activeReadiness.allMandatoryReady ||
                                        (activeAppMode == AppMode.MIRRORING && (!projStats.usingMediaProjection || !effectiveDiagnosticError.isNullOrBlank()))
                                    if (needsConnectionSetup) {
                                        FilledTonalButton(
                                            onClick = onOpenConnectionTab,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("settings_open_connection_tab_btn"),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = colorScheme.primaryContainer,
                                                contentColor = colorScheme.onPrimaryContainer
                                            )
                                        ) {
                                            Text(
                                                text = "Настроить доступы для «${activeAppMode.shortConnectionLabel()}» в «Подключении»",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3 & 4. Рабочие параметры, специфичные для выбранного типа подключения
        when (activeAppMode) {
            AppMode.MIRRORING -> {
                // Чувствительность тача (0x0006) — актуальна только для режима «Зеркало»
                item {
                    TouchSensitivitySettingsCard()
                }

                // Экран и питание — KeepAlive + 1% OLED для режима «Зеркало»
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
            }

            AppMode.COCKPIT -> {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cockpit_mode_runtime_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
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
                                    text = "Параметры режима «Кокпит»",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                VwStatusBadge(label = "КОКПИТ", tone = VwBadgeTone.ACTIVE)
                            }
                            Text(
                                text = "Левая панель VW (250 px) и встроенная Яндекс Карта работают на отдельном VirtualDisplay. Касания экрана ГУ и клавиши на руле (HOME, BACK, NAVI, SEEK±, OK) обрабатываются напрямую.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            Surface(
                                color = colorScheme.background,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, colorScheme.outline)
                            ) {
                                Text(
                                    text = "Настройки чувствительности Accessibility-тача и затемнения 1% OLED скрыты, так как они используются только в типе подключения «Зеркало».",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            AppMode.SHIZUKU_COCKPIT -> {
                item {
                    val shizukuStatus by CarLifeShizukuManager.status.collectAsStateWithLifecycle()
                    val shizukuOk = shizukuStatus.isBinderAlive && shizukuStatus.hasPermission
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shizuku_mode_runtime_card"),
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
                                        text = "Параметры режима «Кокпит + Shizuku»",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Виртуальный экран #${state.projectionStats.virtualDisplayId} • оверлей панели VW 250 px",
                                        style = MaterialTheme.typography.labelSmall.tabular(),
                                        color = colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                VwStatusBadge(
                                    label = shizukuStatus.statusLabel,
                                    tone = if (shizukuOk) VwBadgeTone.ACTIVE else VwBadgeTone.WAITING,
                                    modifier = Modifier.testTag("shizuku_status_badge")
                                )
                            }

                            Text(
                                text = "${shizukuStatus.statusLabel} • ${shizukuStatus.detailMessage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (shizukuOk) vw.onSuccessContainer else colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("shizuku_status_detail")
                            )

                            FilledTonalButton(
                                onClick = {
                                    if (shizukuOk) {
                                        CarLifeShizukuManager.bindUserServiceIfReady(context)
                                    } else {
                                        onOpenConnectionTab()
                                    }
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
                                        shizukuOk && shizukuStatus.isUserServiceBound ->
                                            "Shizuku подключён (Переподключить UserService)"
                                        shizukuOk ->
                                            "Подключить Shizuku UserService"
                                        else ->
                                            "Включить Shizuku во вкладке «Подключение»"
                                    },
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Surface(
                                color = colorScheme.background,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, colorScheme.outline)
                            ) {
                                Text(
                                    text = "Навигатор и 2ГИС запускаются на отдельном виртуальном экране, а рулевые клавиши HOME/BACK и тач идут напрямую через Shizuku. Настройки Accessibility-тача и затемнения 1% OLED скрыты (только для «Зеркало»).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Звук по USB AOA (канал 0x0003, общий для всех типов подключения)
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
                                text = "Общий параметр для всех режимов • 48 кГц Stereo 16-bit PCM • Требует RECORD_AUDIO + MediaProjection",
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

                    // Тумблер «Глушить динамик телефона при USB-звуке»
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
                                    "Динамик заглушен (сохр. громкость: ${audioStats.savedPhoneMusicVolume ?: 0}); вернётся при отключении USB"
                                } else {
                                    "Сохраняет STREAM_MUSIC, ставит 0 и восстанавливает при остановке/отключении USB"
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
                                !hasRecordAudioPerm || !checklistStatus.step5MediaProjectionGranted ->
                                    onOpenConnectionTab()
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
                                !hasRecordAudioPerm || !checklistStatus.step5MediaProjectionGranted ->
                                    "Настроить доступ во вкладке «Подключение»"
                                audioStats.isCapturing -> "Остановить звук 0x0003"
                                else -> "Запустить захват звука 0x0003"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 6. Параметры видеопотока H.264 (только разрешение и битрейт, без экспериментальных шапок)
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
                        text = "Разрешение экрана ГУ (по умолчанию 1280×720 • 160 dpi)",
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
                        text = "Битрейт кодека (рекомендуется 2.0 Мбит/с для чёткой карты)",
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
                }
            }
        }

        // 7. Сворачиваемый раздел «Лог пакетов и диагностика USB AOA» (живой лог без тестовых кнопок)
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
                                text = "Лог пакетов и диагностика USB AOA",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Счётчики каналов 0x0001..0x0006, статус USB и журнал пакетов",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.7f))
                            ) {
                                Text(
                                    text = "Последняя клавиша руля: ${state.lastSteeringWheelKeyEvent}",
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    fontFamily = JetBrainsMonoFamily,
                                    color = vw.accentBlue,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("last_steering_wheel_key_text")
                                )
                            }
                        }
                        Icon(
                            imageVector = if (diagnosticsExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = "Развернуть лог пакетов и диагностику USB AOA",
                            tint = colorScheme.primary
                        )
                    }

                    // Тумблер «Показывать коды клавиш руля (Toast)» (включён по умолчанию)
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
                                text = "Показывать коды клавиш руля (Toast)",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = if (steeringWheelConfig.showKeyCodesToast) {
                                    "Вкл — при нажатии на руле всплывает «Руль: code=XX (0x..) → Действие»"
                                } else {
                                    "Выкл — коды клавиш 0x00060008 записываются только в строку «Последняя клавиша руля»"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = steeringWheelConfig.showKeyCodesToast,
                            onCheckedChange = { enabled ->
                                TouchSettingsPreferences.setShowSteeringWheelKeyCodesToast(enabled, context)
                            },
                            modifier = Modifier.testTag("steering_wheel_toast_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colorScheme.onPrimary,
                                checkedTrackColor = colorScheme.primary,
                                uncheckedThumbColor = colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = colorScheme.surfaceVariant
                            )
                        )
                    }

                    AnimatedVisibility(visible = diagnosticsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProjectionEngineCard(
                                state = state,
                                onStartProjection = onStartProjection,
                                onStopProjection = onStopProjection
                            )

                            TransportControlsCard(
                                state = state,
                                onStartOrScanBridge = onStartOrScanBridge,
                                onStopBridge = onStopBridge
                            )

                            ChannelMultiplexerGrid(channelCounts = state.channelFrameCounts)

                            // Тумблер «Логировать пакеты в UI» прямо над журналом
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
                                            "Вкл — живые пакеты 0x0001/0x0002/0x0006 пишутся в журнал ниже"
                                        } else {
                                            "Выкл — ноль лишних перерисовок Compose для максимальной плавности 30 FPS"
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
                                val gateStatusLabel = if (state.isVideoChannelReadyForStreaming) {
                                    "Шлюз 0x0002: ОТКРЫТ (поток активен)"
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
                                            text = "Статистика видеоканала 0x0002 • AOA 20B (0x00020001)",
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
private fun ProjectionEngineCard(
    state: BridgeRuntimeState,
    onStartProjection: (Int, Int, Int) -> Unit,
    onStopProjection: () -> Unit
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
                    text = when (state.appMode) {
                        AppMode.MIRRORING -> "Видеопоток: Зеркалирование (MediaProjection) + H.264"
                        AppMode.SHIZUKU_COCKPIT -> "Видеопоток: Shizuku VirtualDisplay + H.264"
                        AppMode.COCKPIT -> "Видеопоток: Кокпит VirtualDisplay + H.264"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (proj.spsPpsConfigured) "SPS/PPS OK" else "AVC Baseline",
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
                        AppMode.COCKPIT -> "VD #${proj.virtualDisplayId}"
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

            if (!proj.isRunning) {
                Button(
                    onClick = { onStartProjection(state.configuredWidth, state.configuredHeight, 30) },
                    modifier = Modifier
                        .fillMaxWidth()
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
                        contentDescription = "Запустить видеопоток",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Запустить видеопоток H.264",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onStopProjection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("stop_projection_btn"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.55f))
                ) {
                    Text("Остановить видеопоток", color = colorScheme.error, fontWeight = FontWeight.SemiBold)
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
                text = "Подключите USB-кабель к ГУ и включите «Логировать пакеты в UI» выше для просмотра живого трафика CarLife.",
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


