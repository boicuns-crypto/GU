package com.example.core.projection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent settings controlling display keep-awake, AMOLED physical screen dimming,
 * and screen-off survival for Mirroring (`MediaProjection`) and USB Audio (`0x0003`).
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
     * `FLAG_KEEP_SCREEN_ON` so the display never times out even when third-party apps
     * (Yandex Navigator, 2GIS, YouTube) are open in Mirroring mode.
     */
    val keepScreenAwakeOverlay: Boolean = true,
    /**
     * Dims ONLY the physical phone OLED panel (`screenBrightness = 0.01f`) via the `1×1 px`
     * overlay window while Mirroring/USB is active — keeping 100% full-brightness video
     * captured by `MediaProjection` on the Jetta VS7 Head Unit.
     */
    val dimPhoneScreenDuringMirroring: Boolean = false,
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
    val isKeepAwakeOverlayActive: Boolean = false,
    val isDimmingActive: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val screenOffCount: Int = 0,
    val statusSummary: String = "Экран активен"
)

/**
 * SharedPreferences-backed reactive store for [ScreenKeepAliveConfig].
 */
object ScreenKeepAlivePreferences {
    private const val PREFS_NAME = "carlife_bridge_prefs"
    private const val KEY_KEEP_ALIVE_SCREEN_OFF = "screen_keep_alive_on_off"
    private const val KEY_KEEP_AWAKE_OVERLAY = "screen_keep_awake_overlay"
    private const val KEY_DIM_PHONE_SCREEN = "screen_dim_phone_during_mirroring"
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
            dimPhoneScreenDuringMirroring = prefs.getBoolean(KEY_DIM_PHONE_SCREEN, false),
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
            .remove(KEY_DIM_PHONE_SCREEN)
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
            .putBoolean(KEY_DIM_PHONE_SCREEN, cfg.dimPhoneScreenDuringMirroring)
            .putBoolean(KEY_AUTO_WAKE_SCREEN_OFF, cfg.autoWakeOnScreenOff)
            .apply()
    }
}

/**
 * Manages screen-off survival for Mirroring & `0x0003` audio, cross-app `FLAG_KEEP_SCREEN_ON`,
 * and AMOLED-safe hardware panel dimming (`1×1 px` `TYPE_APPLICATION_OVERLAY` window with
 * `screenBrightness = 0.01f` — never a fullscreen black overlay, so `MediaProjection` stays
 * 100% vivid on the Jetta VS7 Head Unit).
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
    private var isMirroringOrUsbActive: Boolean = false

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
                        "ACTION_SCREEN_OFF received (keepAlive=${cfg.keepAliveOnScreenOff}, autoWake=${cfg.autoWakeOnScreenOff}, active=$isMirroringOrUsbActive)"
                    )
                    updateStateSnapshot(isScreenOff = true)
                    if (cfg.keepAliveOnScreenOff) {
                        onScreenOff()
                    }
                    if (cfg.autoWakeOnScreenOff && isMirroringOrUsbActive) {
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
        isMirroringOrUsbActive: Boolean = false
    ) {
        this.isServiceSessionActive = isSessionActive
        this.isMirroringOrUsbActive = isMirroringOrUsbActive
        if (isStarted.compareAndSet(false, true)) {
            registerScreenReceiverIfNeeded()
        }
        applyCurrentConfig()
    }

    fun updateSessionState(
        isSessionActive: Boolean,
        isMirroringOrUsbActive: Boolean
    ) {
        this.isServiceSessionActive = isSessionActive
        this.isMirroringOrUsbActive = isMirroringOrUsbActive
        applyCurrentConfig()
    }

    fun applyCurrentConfig() {
        val cfg = ScreenKeepAlivePreferences.config.value
        val hasOverlayPerm = canDrawOverlaysSafe()
        val wantsKeepAwake = isServiceSessionActive && cfg.keepScreenAwakeOverlay
        val wantsDim = isServiceSessionActive && isMirroringOrUsbActive && cfg.dimPhoneScreenDuringMirroring
        val shouldAttachOverlay = hasOverlayPerm && (wantsKeepAwake || wantsDim)

        runOnMain {
            if (!shouldAttachOverlay) {
                removeOverlayWindowLocked()
            } else {
                attachOrUpdateOverlayWindowLocked(
                    keepScreenOn = wantsKeepAwake,
                    dimScreen = wantsDim
                )
            }
            updateStateSnapshot(
                isScreenOff = !(powerManager?.isInteractive ?: true),
                hasOverlayPerm = hasOverlayPerm,
                overlayActive = keepAliveView != null && wantsKeepAwake,
                dimActive = keepAliveView != null && wantsDim
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
        isMirroringOrUsbActive = false
        isStarted.set(false)
        if (isReceiverRegistered.compareAndSet(true, false)) {
            runCatching { appContext.unregisterReceiver(screenReceiver) }
        }
        runOnMain {
            removeOverlayWindowLocked()
            updateStateSnapshot(
                isScreenOff = !(powerManager?.isInteractive ?: true),
                overlayActive = false,
                dimActive = false
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

    private fun attachOrUpdateOverlayWindowLocked(
        keepScreenOn: Boolean,
        dimScreen: Boolean
    ) {
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
        if (keepScreenOn) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        val targetBrightness = if (dimScreen) {
            MIN_OLED_BRIGHTNESS
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        val existingView = keepAliveView
        val existingParams = currentOverlayParams
        if (existingView != null && existingParams != null) {
            if (existingParams.flags != flags || existingParams.screenBrightness != targetBrightness) {
                existingParams.flags = flags
                existingParams.screenBrightness = targetBrightness
                runCatching {
                    wm.updateViewLayout(existingView, existingParams)
                }.onFailure { e ->
                    Log.w(TAG, "updateViewLayout failed for 1x1 keep-awake overlay: ${e.message}")
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
            alpha = 0.01f
            screenBrightness = targetBrightness
        }

        val view = View(appContext).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        runCatching {
            wm.addView(view, params)
            keepAliveView = view
            currentOverlayParams = params
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach 1x1 keep-awake overlay: ${e.message}")
            keepAliveView = null
            currentOverlayParams = null
        }
    }

    private fun removeOverlayWindowLocked() {
        val wm = windowManager ?: return
        val view = keepAliveView ?: return
        runCatching {
            wm.removeViewImmediate(view)
        }.onFailure {
            runCatching { wm.removeView(view) }
        }
        keepAliveView = null
        currentOverlayParams = null
    }

    private fun updateStateSnapshot(
        isScreenOff: Boolean,
        hasOverlayPerm: Boolean = canDrawOverlaysSafe(),
        overlayActive: Boolean = keepAliveView != null && ScreenKeepAlivePreferences.config.value.keepScreenAwakeOverlay,
        dimActive: Boolean = keepAliveView != null &&
            isMirroringOrUsbActive &&
            ScreenKeepAlivePreferences.config.value.dimPhoneScreenDuringMirroring
    ) {
        val cfg = ScreenKeepAlivePreferences.config.value
        val summary = when {
            isScreenOff && cfg.keepAliveOnScreenOff ->
                "Экран погашен • Зеркало/звук удерживаются (KeepAlive)"
            isScreenOff ->
                "Экран погашен"
            dimActive ->
                "1% яркость телефона (на ГУ 100%) • Экран не уснёт"
            overlayActive ->
                "Экран не уснёт поверх приложений (1×1 Overlay)"
            !hasOverlayPerm && (cfg.keepScreenAwakeOverlay || cfg.dimPhoneScreenDuringMirroring) ->
                "Выдайте разрешение Overlay для удержания экрана поверх приложений"
            else ->
                "Экран активен • Защита от отключения при гашении ${if (cfg.keepAliveOnScreenOff) "ВКЛ" else "ВЫКЛ"}"
        }
        _state.value = ScreenKeepAliveState(
            isPhoneScreenOff = isScreenOff,
            isKeepAwakeOverlayActive = overlayActive,
            isDimmingActive = dimActive,
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
    }
}
