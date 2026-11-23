package com.example.core.projection

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.feature.cockpit.MediaPlayerController
import com.example.feature.cockpit.VW_LEFT_PANEL_WIDTH_PX
import com.example.feature.cockpit.VwCockpitLeftPanel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemePreferences

private class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Persistent `250 × 720` VW MIB3 Left Sidebar rendered as a non-focusable `WindowManager` overlay
 * directly inside the `virtualDisplay.display` context during `SHIZUKU_COCKPIT` mode while a
 * third-party navigation app (Яндекс Навигатор / 2ГИС) is running in the right area.
 *
 * Uses `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN` so it never steals focus or keyboard input
 * from the active Navigator while keeping «Домой», «Назад», «Карта», theme toggle, and music
 * controls (`⏮`, `⏯`, `⏭`) permanently visible and responsive to `x < 250` touches.
 */
class ShizukuLeftPanelOverlay(
    private val appContext: Context,
    private val onHomeClick: () -> Unit,
    private val onBackClick: () -> Unit,
    private val onMapClick: () -> Unit
) {
    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var attachedDisplayId: Int = Display.DEFAULT_DISPLAY

    val isShowing: Boolean
        get() = composeView != null

    val rootView: View?
        get() = composeView

    fun show(
        display: Display,
        panelWidthPx: Int = VW_LEFT_PANEL_WIDTH_PX,
        displayHeightPx: Int = CockpitProjectionManager.DEFAULT_HEIGHT
    ): Boolean {
        if (composeView != null && attachedDisplayId == display.displayId) {
            return true
        }
        dismiss()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "Cannot show ShizukuLeftPanelOverlay: SYSTEM_ALERT_WINDOW not granted")
            return false
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        return runCatching {
            val baseDisplayCtx = appContext.createDisplayContext(display)
            val fixedConfig = Configuration(baseDisplayCtx.resources.configuration).apply {
                densityDpi = CockpitProjectionManager.FIXED_VIRTUAL_DISPLAY_DPI
                screenWidthDp = panelWidthPx
                screenHeightDp = displayHeightPx
                smallestScreenWidthDp = minOf(panelWidthPx, displayHeightPx)
            }
            val configCtx = runCatching {
                baseDisplayCtx.createConfigurationContext(fixedConfig)
            }.getOrElse { baseDisplayCtx }

            val windowCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    configCtx.createWindowContext(display, overlayType, null)
                }.getOrElse { configCtx }
            } else {
                configCtx
            }

            val wm = windowCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val owner = OverlayLifecycleOwner().apply { start() }
            val targetDisplayId = display.displayId

            val view = ComposeView(windowCtx).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    val activeTheme by ThemePreferences.themeMode.collectAsState()
                    val media by MediaPlayerController.mediaState.collectAsState()
                    MyApplicationTheme(themeMode = activeTheme) {
                        VwCockpitLeftPanel(
                            virtualDisplayId = targetDisplayId,
                            mediaState = media,
                            isMapActive = true,
                            onHomeClick = onHomeClick,
                            onBackClick = onBackClick,
                            onMapClick = onMapClick,
                            onPlayPause = { MediaPlayerController.playPause() },
                            onNextTrack = { MediaPlayerController.next() },
                            onPreviousTrack = { MediaPlayerController.previous() },
                            isFloatingOverlay = true
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                panelWidthPx,
                displayHeightPx,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                title = "JettaVS7_LeftPanel_Overlay"
            }

            wm.addView(view, params)
            windowManager = wm
            composeView = view
            lifecycleOwner = owner
            attachedDisplayId = targetDisplayId
            Log.i(TAG, "ShizukuLeftPanelOverlay shown (${panelWidthPx}x$displayHeightPx) on VD #$targetDisplayId")
            true
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach ShizukuLeftPanelOverlay on VD #${display.displayId}: ${e.message}")
            dismiss()
        }.getOrDefault(false)
    }

    fun dismiss() {
        val view = composeView
        val wm = windowManager
        val owner = lifecycleOwner
        composeView = null
        windowManager = null
        lifecycleOwner = null
        attachedDisplayId = Display.DEFAULT_DISPLAY
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        runCatching { owner?.destroy() }
    }

    companion object {
        private const val TAG = "ShizukuLeftOverlay"
    }
}
