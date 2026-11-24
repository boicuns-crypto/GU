package com.example.core.projection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent settings controlling display keep-awake, automatic 1% AMOLED physical brightness
 * on USB connect, automatic left-landscape rotation on USB connect, and screen-off survival
 * for Mirroring (`MediaProjection`) and USB Audio (`0x0003`).
 */
data class ScreenKeepAliveConfig(
    /**
     * Never stop `MediaProjection`, H.264 encoder, or `AudioPlaybackCapture` (`0x0003`)
     * when the physical phone display turns off; maintain CarLife keepalive heartbeat
     * and instantly recover video/audio on screen-on.
     */
    val keepAliveOnScreenOff: Boolean = true,
    /**
     * Maintains a tiny `1×1 px` non-touchable `TYPE_APPLICATION_OVERLAY` window with
     * `FLAG_KEEP_SCREEN_ON` while USB Mirroring is active so the display never times out
     * when third-party apps (Yandex Navigator, 2GIS, YouTube) are open.
     */
    val keepScreenAwakeOverlay: Boolean = true,
    /**
     * Automatically dims ONLY the physical phone OLED panel (`screenBrightness = 0.01f`)
     * when the USB cable connects in Mirroring mode, and restores normal brightness
     * immediately when the USB cable is disconnected.
     */
    val dimPhoneScreenDuringMirroring: Boolean = true,
    /**
     * Automatically rotates the phone screen left (`ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`)
     * when the USB cable connects in Mirroring mode, and restores normal system orientation
     * (`ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`) when the USB cable is disconnected.
     */
    val autoRotateLeftOnUsb: Boolean = true,
    /**
     * Optional safety pulse: if the user accidentally presses the Power button during active
     * USB Mirroring, briefly wakes the screen so SurfaceFlinger continues rendering frames.
     */
    val autoWakeOnScreenOff: Boolean = false
)

/**
 * Live runtime status of [ScreenKeepAliveManager] shown in diagnostics UI.
 */
data class ScreenKeepAliveState(
    val isPhoneScreenOff: Boolean = false,
    val isUsbConnected: Boolean = false,
    val isMirroringMode: Boolean = false,
    val isKeepAwakeOverlayActive: Boolean = false,
    val isDimmingActive: Boolean = false,
    val isAutoRotateLeftActive: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val screenOffCount: Int = 0,
    val statusSummary: String = "USB отключён • Обычный режим экрана"
)

/**
 * Pure computed overlay parameters for deterministic testing and window updates.
 */
internal data class ScreenOverlayPolicy(
    val shouldAttachOverlay: Boolean,
    val keepScreenOn: Boolean,
    val dimScreen: Boolean,
    val rotateLeft: Boolean,
    val targetBrightness: Float,
    val targetOrientation: Int
)

/**
 * SharedPreferences-backed reactive store for [ScreenKeepAliveConfig].
 */
object ScreenKeepAlivePreferences {
    private const val PREFS_NAME = "carlife_bridge_prefs"
    private const val KEY_KEEP_ALIVE_SCREEN_OFF = "screen_keep_alive_on_off"
    private const val KEY_KEEP_AWAKE_OVERLAY = "screen_keep_awake_overlay"
    private const val KEY_DIM_PHONE_SCREEN_USB = "screen_auto_dim_1pct_on_usb_v2"
    private const val KEY_AUTO_ROTATE_LEFT_USB = "screen_auto_rotate_left_on_usb"
    private const val KEY_AUTO_WAKE_SCREEN_OFF = "screen_auto_wake_on_off"

    private val _config = MutableStateFlow(ScreenKeepAliveConfig())
    val config: StateFlow<ScreenKeepAliveConfig> = _config.asStateFlow()

    fun init(context: Context): ScreenKeepAliveConfig {
        val loaded = load(context)
        _config.value = loaded
        return loaded
    }

    fun load(context: Context): ScreenKeepAliveConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ScreenKeepAliveConfig(
            keepAliveOnScreenOff = prefs.getBoolean(KEY_KEEP_ALIVE_SCREEN_OFF, true),
            keepScreenAwakeOverlay = prefs.getBoolean(KEY_KEEP_AWAKE_OVERLAY, true),
            dimPhoneScreenDuringMirroring = prefs.getBoolean(KEY_DIM_PHONE_SCREEN_USB, true),
            autoRotateLeftOnUsb = prefs.getBoolean(KEY_AUTO_ROTATE_LEFT_USB, true),
            autoWakeOnScreenOff = prefs.getBoolean(KEY_AUTO_WAKE_SCREEN_OFF, false)
        )
    }

    fun updateConfig(
        context: Context? = null,
        transform: (ScreenKeepAliveConfig) -> ScreenKeepAliveConfig
    ): ScreenKeepAliveConfig {
        val next = transform(_config.value)
        _config.value = next
        if (context != null) {
            save(context, next)
        }
        return next
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_KEEP_ALIVE_SCREEN_OFF)
            .remove(KEY_KEEP_AWAKE_OVERLAY)
            .remove(KEY_DIM_PHONE_SCREEN_USB)
            .remove(KEY_AUTO_ROTATE_LEFT_USB)
            .remove(KEY_AUTO_WAKE_SCREEN_OFF)
            .apply()
        _config.value = ScreenKeepAliveConfig()
    }

    private fun save(context: Context, cfg: ScreenKeepAliveConfig) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_ALIVE_SCREEN_OFF, cfg.keepAliveOnScreenOff)
            .putBoolean(KEY_KEEP_AWAKE_OVERLAY, cfg.keepScreenAwakeOverlay)
            .putBoolean(KEY_DIM_PHONE_SCREEN_USB, cfg.dimPhoneScreenDuringMirroring)
            .putBoolean(KEY_AUTO_ROTATE_LEFT_USB, cfg.autoRotateLeftOnUsb)
            .putBoolean(KEY_AUTO_WAKE_SCREEN_OFF, cfg.autoWakeOnScreenOff)
            .apply()
    }
}

/**
 * Manages screen-off survival for Mirroring & `0x0003` audio, cross-app `FLAG_KEEP_SCREEN_ON`,
 * automatic AMOLED-safe 1% hardware brightness (`screenBrightness = 0.01f`), and automatic
 * left-landscape rotation (`ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`) on USB cable connect —
 * restoring normal brightness and orientation immediately when the USB cable is disconnected.
 */
class ScreenKeepAliveManager(
    context: Context,
    private val onScreenOff: () -> Unit = {},
    private val onScreenOn: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val isStarted = AtomicBoolean(false)
    private val isReceiverRegistered = AtomicBoolean(false)

    @Volatile
    private var isServiceSessionActive: Boolean = false

    @Volatile
    private var isUsbConnected: Boolean = false

    @Volatile
    private var isMirroringMode: Boolean = false

    private var keepAliveView: View? = null
    private var currentOverlayParams: WindowManager.LayoutParams? = null
    private var screenOffCounter: Int = 0

    private val _state = MutableStateFlow(
        ScreenKeepAliveState(
            isPhoneScreenOff = !(powerManager?.isInteractive ?: true),
            hasOverlayPermission = canDrawOverlaysSafe()
        )
    )
    val state: StateFlow<ScreenKeepAliveState> = _state.asStateFlow()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffCounter++
                    val cfg = ScreenKeepAlivePreferences.config.value
                    Log.i(
                        TAG,
                        "ACTION_SCREEN_OFF received (keepAlive=${cfg.keepAliveOnScreenOff}, autoWake=${cfg.autoWakeOnScreenOff}, usb=$isUsbConnected, mirror=$isMirroringMode)"
                    )
                    updateStateSnapshot(isScreenOff = true)
                    if (cfg.keepAliveOnScreenOff) {
                        onScreenOff()
                    }
                    if (cfg.autoWakeOnScreenOff && isUsbConnected && isMirroringMode) {
                        triggerBriefWakePulse()
                    }
                }

                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "${intent.action} received — resuming live video/audio check")
                    updateStateSnapshot(isScreenOff = false)
                    onScreenOn()
                    applyCurrentConfig()
                }
            }
        }
    }

    fun start(
        isSessionActive: Boolean = true,
        isUsbConnected: Boolean = false,
        isMirroringMode: Boolean = false
    ) {
        this.isServiceSessionActive = isSessionActive
        this.isUsbConnected = isUsbConnected
        this.isMirroringMode = isMirroringMode
        if (isStarted.compareAndSet(false, true)) {
            registerScreenReceiverIfNeeded()
        }
        applyCurrentConfig()
    }

    fun updateSessionState(
        isSessionActive: Boolean,
        isUsbConnected: Boolean,
        isMirroringMode: Boolean
    ) {
        this.isServiceSessionActive = isSessionActive
        this.isUsbConnected = isUsbConnected
        this.isMirroringMode = isMirroringMode
        applyCurrentConfig()
    }

    fun applyCurrentConfig() {
        val cfg = ScreenKeepAlivePreferences.config.value
        val hasOverlayPerm = canDrawOverlaysSafe()
        val policy = computeOverlayPolicy(
            config = cfg,
            isSessionActive = isServiceSessionActive,
            isUsbConnected = isUsbConnected,
            isMirroringMode = isMirroringMode,
            hasOverlayPermission = hasOverlayPerm
        )

        runOnMain {
            if (!policy.shouldAttachOverlay) {
                removeOverlayWindowLocked()
            } else {
                attachOrUpdateOverlayWindowLocked(policy)
            }
            val attached = keepAliveView != null
            updateStateSnapshot(
                isScreenOff = !(powerManager?.isInteractive ?: true),
                hasOverlayPerm = hasOverlayPerm,
                overlayActive = attached && policy.keepScreenOn,
                dimActive = attached && policy.dimScreen,
                rotateLeftActive = attached && policy.rotateLeft
            )
        }
    }

    @Suppress("DEPRECATION")
    fun triggerBriefWakePulse() {
        val pm = powerManager ?: return
        runCatching {
            val wakePulse = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CarLifeBridge::MirrorAutoWakePulse"
            )
            wakePulse.setReferenceCounted(false)
            wakePulse.acquire(1_500L)
            mainHandler.postDelayed({
                runCatching {
                    if (wakePulse.isHeld) wakePulse.release()
                }
            }, 1_600L)
        }.onFailure { e ->
            Log.w(TAG, "Auto-wake pulse unavailable: ${e.message}")
        }
    }

    fun stop() {
        isServiceSessionActive = false
        isUsbConnected = false
        isMirroringMode = false
        isStarted.set(false)
        if (isReceiverRegistered.compareAndSet(true, false)) {
            runCatching { appContext.unregisterReceiver(screenReceiver) }
        }
        runOnMain {
            removeOverlayWindowLocked()
            updateStateSnapshot(
                isScreenOff = !(powerManager?.isInteractive ?: true),
                overlayActive = false,
                dimActive = false,
                rotateLeftActive = false
            )
        }
    }

    private fun registerScreenReceiverIfNeeded() {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    screenReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.onFailure { e ->
                isReceiverRegistered.set(false)
                Log.w(TAG, "Failed to register screen state receiver: ${e.message}")
            }
        }
    }

    private fun attachOrUpdateOverlayWindowLocked(policy: ScreenOverlayPolicy) {
        val wm = windowManager ?: return
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (policy.keepScreenOn) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        val existingView = keepAliveView
        val existingParams = currentOverlayParams
        if (existingView != null && existingParams != null) {
            if (existingParams.flags != flags ||
                existingParams.screenBrightness != policy.targetBrightness ||
                existingParams.screenOrientation != policy.targetOrientation
            ) {
                existingParams.flags = flags
                existingParams.screenBrightness = policy.targetBrightness
                existingParams.screenOrientation = policy.targetOrientation
                runCatching {
                    wm.updateViewLayout(existingView, existingParams)
                }.onFailure { e ->
                    Log.w(TAG, "updateViewLayout failed for 1x1 screen overlay: ${e.message}")
                }
            }
            return
        }

        val params = WindowManager.LayoutParams(
            1,
            1,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Transparent 1x1 view with alpha=1f guarantees WindowManager respects
            // both screenBrightness (0.01f) and screenOrientation (LANDSCAPE) on Android 14-16
            // while remaining 100% invisible on both phone and MediaProjection stream.
            alpha = 1.0f
            screenBrightness = policy.targetBrightness
            screenOrientation = policy.targetOrientation
        }

        val view = View(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        runCatching {
            wm.addView(view, params)
            keepAliveView = view
            currentOverlayParams = params
            Log.i(
                TAG,
                "Attached 1x1 USB Mirroring overlay (dim=${policy.dimScreen}, rotateLeft=${policy.rotateLeft}, keepOn=${policy.keepScreenOn})"
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach 1x1 screen overlay: ${e.message}")
            keepAliveView = null
            currentOverlayParams = null
        }
    }

    private fun removeOverlayWindowLocked() {
        val wm = windowManager ?: return
        val view = keepAliveView ?: return
        val params = currentOverlayParams
        // Explicitly flush restored brightness & orientation before removal so One UI
        // snaps back to normal screen brightness & portrait/auto-rotate immediately.
        if (params != null) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            runCatching { wm.updateViewLayout(view, params) }
        }
        runCatching {
            wm.removeViewImmediate(view)
        }.onFailure {
            runCatching { wm.removeView(view) }
        }
        keepAliveView = null
        currentOverlayParams = null
        Log.i(TAG, "Removed 1x1 USB Mirroring overlay — normal brightness and orientation restored")
    }

    private fun updateStateSnapshot(
        isScreenOff: Boolean,
        hasOverlayPerm: Boolean = canDrawOverlaysSafe(),
        overlayActive: Boolean = keepAliveView != null &&
            isUsbConnected &&
            isMirroringMode &&
            ScreenKeepAlivePreferences.config.value.keepScreenAwakeOverlay,
        dimActive: Boolean = keepAliveView != null &&
            isUsbConnected &&
            isMirroringMode &&
            ScreenKeepAlivePreferences.config.value.dimPhoneScreenDuringMirroring,
        rotateLeftActive: Boolean = keepAliveView != null &&
            isUsbConnected &&
            isMirroringMode &&
            ScreenKeepAlivePreferences.config.value.autoRotateLeftOnUsb
    ) {
        val cfg = ScreenKeepAlivePreferences.config.value
        val needsOverlay = cfg.keepScreenAwakeOverlay ||
            cfg.dimPhoneScreenDuringMirroring ||
            cfg.autoRotateLeftOnUsb
        val summary = when {
            isScreenOff && cfg.keepAliveOnScreenOff ->
                "Экран погашен • Зеркало и звук 0x0003 удерживаются (KeepAlive)"
            isScreenOff ->
                "Экран погашен"
            !hasOverlayPerm && needsOverlay ->
                "Выдайте разрешение Overlay для автояркости 1% и автоповорота влево по USB"
            dimActive && rotateLeftActive ->
                "USB подключён • Яркость телефона 1% (на ГУ 100%) • Экран повёрнут влево"
            dimActive ->
                "USB подключён • Яркость телефона 1% (на ГУ 100%)"
            rotateLeftActive ->
                "USB подключён • Экран повёрнут влево (альбомно)"
            overlayActive ->
                "USB подключён • Экран удерживается активным"
            isUsbConnected && !isMirroringMode ->
                "USB подключён • Обычный режим экрана (активно только в режиме «Зеркало»)"
            else ->
                "USB отключён • Обычный режим экрана и ориентации"
        }
        _state.value = ScreenKeepAliveState(
            isPhoneScreenOff = isScreenOff,
            isUsbConnected = isUsbConnected,
            isMirroringMode = isMirroringMode,
            isKeepAwakeOverlayActive = overlayActive,
            isDimmingActive = dimActive,
            isAutoRotateLeftActive = rotateLeftActive,
            hasOverlayPermission = hasOverlayPerm,
            screenOffCount = screenOffCounter,
            statusSummary = summary
        )
    }

    private fun canDrawOverlaysSafe(): Boolean =
        runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "ScreenKeepAliveMgr"
        const val MIN_OLED_BRIGHTNESS = 0.01f

        @VisibleForTesting
        internal fun computeOverlayPolicy(
            config: ScreenKeepAliveConfig,
            isSessionActive: Boolean,
            isUsbConnected: Boolean,
            isMirroringMode: Boolean,
            hasOverlayPermission: Boolean
        ): ScreenOverlayPolicy {
            val activeUsbMirroring = isSessionActive && isUsbConnected && isMirroringMode
            val keepScreenOn = activeUsbMirroring && config.keepScreenAwakeOverlay
            val dimScreen = activeUsbMirroring && config.dimPhoneScreenDuringMirroring
            val rotateLeft = activeUsbMirroring && config.autoRotateLeftOnUsb
            val shouldAttach = hasOverlayPermission && (keepScreenOn || dimScreen || rotateLeft)
            return ScreenOverlayPolicy(
                shouldAttachOverlay = shouldAttach,
                keepScreenOn = keepScreenOn,
                dimScreen = dimScreen,
                rotateLeft = rotateLeft,
                targetBrightness = if (dimScreen) {
                    MIN_OLED_BRIGHTNESS
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                },
                targetOrientation = if (rotateLeft) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            )
        }
    }
}
