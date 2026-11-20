package com.example.core.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multi-touch pointer coordinate scaled to the target display.
 */
data class ScaledTouchPointer(
    val pointerId: Int,
    val x: Float,
    val y: Float
)

/**
 * AccessibilityService для передачи касаний (тапы, свайпы, перетаскивание карты) с тачскрина ГУ
 * (Baidu CarLife канал `0x0006`) в любые приложения смартфона в режиме зеркалирования экрана
 * (`MediaProjection`) и на `VirtualDisplay`, а также выполнения глобальных действий Back / Home.
 */
class CarLifeAccessibilityService : AccessibilityService() {

    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var touchDownUptimeMs: Long = 0L
    private var isTrackingGesture: Boolean = false
    private val movementPoints = ArrayList<Pair<Float, Float>>(64)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceEnabled.value = true
        Log.i(TAG, "CarLifeAccessibilityService connected (canPerformGestures=true)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window tree inspection is not needed for coordinate gesture dispatch
    }

    override fun onInterrupt() {
        isTrackingGesture = false
        movementPoints.clear()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        _isServiceEnabled.value = false
        isTrackingGesture = false
        movementPoints.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isServiceEnabled.value = false
        isTrackingGesture = false
        movementPoints.clear()
        super.onDestroy()
    }

    /**
     * Returns current physical screen dimensions (`width x height`) via `WindowManager.currentWindowMetrics.bounds`
     * (automatically updated for current portrait / landscape orientation).
     */
    fun getPhysicalScreenSize(): Pair<Int, Int> = computePhysicalScreenSize(this)

    /**
     * Synthesizes a single tap gesture (`40–60 ms`, default `50 ms`) at `(x, y)` on [displayId].
     */
    fun injectTap(
        displayId: Int = Display.DEFAULT_DISPLAY,
        x: Float,
        y: Float,
        durationMs: Long = DEFAULT_TAP_DURATION_MS
    ): Boolean {
        val safeX = x.coerceAtLeast(0f)
        val safeY = y.coerceAtLeast(0f)
        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val clampedDuration = durationMs.coerceIn(MIN_TAP_DURATION_MS, MAX_TAP_DURATION_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
        val builder = GestureDescription.Builder().addStroke(stroke)
        applyTargetDisplayId(builder, displayId)
        return runCatching {
            dispatchGesture(builder.build(), null, null)
        }.getOrDefault(false)
    }

    /**
     * Synthesizes a continuous drag/swipe path gesture (e.g. scrolling lists or dragging 2GIS/Navigator maps)
     * with duration clamped to `50..1500 ms`.
     */
    fun injectPathGesture(
        displayId: Int = Display.DEFAULT_DISPLAY,
        points: List<Pair<Float, Float>>,
        durationMs: Long = 150L
    ): Boolean {
        if (points.isEmpty()) return false
        val first = points.first()
        val path = Path().apply {
            moveTo(first.first.coerceAtLeast(0f), first.second.coerceAtLeast(0f))
            for (i in 1 until points.size) {
                val pt = points[i]
                lineTo(pt.first.coerceAtLeast(0f), pt.second.coerceAtLeast(0f))
            }
        }
        val clampedDuration = durationMs.coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
        val builder = GestureDescription.Builder().addStroke(stroke)
        applyTargetDisplayId(builder, displayId)
        return runCatching {
            dispatchGesture(builder.build(), null, null)
        }.getOrDefault(false)
    }

    /**
     * Synthesizes a multi-touch gesture (e.g. two-finger pinch/zoom on maps) on [displayId].
     */
    fun injectMultiTouchGesture(
        displayId: Int = Display.DEFAULT_DISPLAY,
        startPointers: List<ScaledTouchPointer>,
        endPointers: List<ScaledTouchPointer>,
        durationMs: Long = 160L
    ): Boolean {
        val count = minOf(startPointers.size, endPointers.size).coerceAtMost(GestureDescription.getMaxStrokeCount())
        if (count <= 0) return false
        val builder = GestureDescription.Builder()
        val clampedDuration = durationMs.coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        for (i in 0 until count) {
            val s = startPointers[i]
            val e = endPointers[i]
            val p = Path().apply {
                moveTo(s.x.coerceAtLeast(0f), s.y.coerceAtLeast(0f))
                lineTo(e.x.coerceAtLeast(0f), e.y.coerceAtLeast(0f))
            }
            builder.addStroke(GestureDescription.StrokeDescription(p, 0L, clampedDuration))
        }
        applyTargetDisplayId(builder, displayId)
        return runCatching {
            dispatchGesture(builder.build(), null, null)
        }.getOrDefault(false)
    }

    /**
     * Processes raw CarLife channel `0x0006` (`MSG_TOUCH_ACTION`) stream (`ACTION_DOWN=0`, `ACTION_MOVE=2`,
     * `ACTION_UP=1`) and dispatches:
     * - Short tap (`40–60 ms`) when displacement between `DOWN` and `UP` is negligible,
     * - Accumulated `android.graphics.Path` swipe with real duration `(upTime - downTime).coerceIn(50L, 1500L)`
     *   for scrolling and dragging maps.
     */
    fun handleMotionStream(
        displayId: Int = Display.DEFAULT_DISPLAY,
        action: Int,
        x: Float,
        y: Float,
        synthesizeClickUp: Boolean = false
    ): Boolean {
        if (synthesizeClickUp) {
            return injectTap(displayId = displayId, x = x, y = y, durationMs = DEFAULT_TAP_DURATION_MS)
        }

        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = x
                touchStartY = y
                touchDownUptimeMs = SystemClock.uptimeMillis()
                movementPoints.clear()
                movementPoints.add(x to y)
                isTrackingGesture = true
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTrackingGesture) {
                    touchStartX = x
                    touchStartY = y
                    touchDownUptimeMs = SystemClock.uptimeMillis()
                    movementPoints.clear()
                    isTrackingGesture = true
                }
                val lastPt = movementPoints.lastOrNull()
                val shouldAppend = lastPt == null ||
                    ((x - lastPt.first) * (x - lastPt.first) + (y - lastPt.second) * (y - lastPt.second) >= 4f)
                if (shouldAppend) {
                    if (movementPoints.size >= MAX_PATH_POINTS) {
                        // Keep start point and decimate interior points smoothly for long map drags
                        val compacted = ArrayList<Pair<Float, Float>>(MAX_PATH_POINTS / 2 + 2)
                        compacted.add(movementPoints.first())
                        for (i in 2 until movementPoints.size step 2) {
                            compacted.add(movementPoints[i])
                        }
                        movementPoints.clear()
                        movementPoints.addAll(compacted)
                    }
                    movementPoints.add(x to y)
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!isTrackingGesture) {
                    injectTap(displayId = displayId, x = x, y = y, durationMs = DEFAULT_TAP_DURATION_MS)
                } else {
                    isTrackingGesture = false
                    movementPoints.add(x to y)
                    val upTimeMs = SystemClock.uptimeMillis()
                    val elapsedMs = (upTimeMs - touchDownUptimeMs).coerceAtLeast(0L)
                    val isTap = isShortTapGesture(
                        startX = touchStartX,
                        startY = touchStartY,
                        endX = x,
                        endY = y,
                        points = movementPoints
                    )
                    val result = if (isTap) {
                        injectTap(
                            displayId = displayId,
                            x = x,
                            y = y,
                            durationMs = DEFAULT_TAP_DURATION_MS
                        )
                    } else {
                        val swipeDurationMs = computeSwipeDurationMs(touchDownUptimeMs, upTimeMs)
                        injectPathGesture(
                            displayId = displayId,
                            points = movementPoints.toList(),
                            durationMs = swipeDurationMs
                        )
                    }
                    movementPoints.clear()
                    Log.d(
                        TAG,
                        "0x0006 gesture dispatched (${if (isTap) "TAP 50ms" else "SWIPE ${elapsedMs}ms"}) -> display=$displayId (${x.toInt()},${y.toInt()}) ok=$result"
                    )
                    result
                }
            }

            else -> {
                isTrackingGesture = false
                movementPoints.clear()
                false
            }
        }
    }

    fun performBackAction(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHomeAction(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performRecentsAction(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Maps Head Unit / steering wheel hard keys to Accessibility global actions.
     */
    fun handleSystemHardKey(androidKeyCode: Int): Boolean {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_BACK -> performBackAction()
            KeyEvent.KEYCODE_HOME -> performHomeAction()
            KeyEvent.KEYCODE_APP_SWITCH -> performRecentsAction()
            else -> false
        }
    }

    private fun applyTargetDisplayId(
        builder: GestureDescription.Builder,
        displayId: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY) {
            runCatching { builder.setDisplayId(displayId) }
        }
    }

    companion object {
        private const val TAG = "CarLifeAccessibilitySvc"

        const val DEFAULT_TAP_DURATION_MS = 50L // 40..60 ms short click
        const val MIN_TAP_DURATION_MS = 40L
        const val MAX_TAP_DURATION_MS = 60L
        const val MIN_SWIPE_DURATION_MS = 50L
        const val MAX_SWIPE_DURATION_MS = 1500L
        private const val MAX_PATH_POINTS = 64
        private const val TAP_SLOP_PX_SQ = 256f // 16 px threshold

        @Volatile
        var instance: CarLifeAccessibilityService? = null
            private set

        private val _isServiceEnabled = MutableStateFlow(false)
        val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        fun isEnabledInSettings(context: Context): Boolean {
            if (_isServiceEnabled.value || instance != null) return true
            val pkg = context.packageName
            val expected = ComponentName(pkg, CarLifeAccessibilityService::class.java.name)
            val enabledRaw = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            return enabledRaw.contains(expected.flattenToString(), ignoreCase = true) ||
                enabledRaw.contains(expected.flattenToShortString(), ignoreCase = true) ||
                enabledRaw.contains(CarLifeAccessibilityService::class.java.simpleName, ignoreCase = true)
        }

        /**
         * Reads the real physical smartphone screen bounds (`WindowManager.currentWindowMetrics.bounds`)
         * reflecting the current portrait/landscape orientation.
         */
        fun computePhysicalScreenSize(context: Context): Pair<Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            }
            val dm = context.resources.displayMetrics
            return dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }

        /**
         * Maps Head Unit touch coordinates (`rawX`, `rawY` in `huWidth × huHeight`, default `1280×720`)
         * into real physical smartphone screen coordinates (`screenWidth × screenHeight`), accounting
         * for current orientation (landscape / portrait) and Android `MediaProjection` aspect-preserving
         * centering (letterbox/pillarbox) inside the `1280×720` capture surface.
         */
        fun mapHuTouchToPhysicalScreen(
            rawX: Int,
            rawY: Int,
            huWidth: Float = 1280f,
            huHeight: Float = 720f,
            screenWidth: Int,
            screenHeight: Int
        ): Pair<Float, Float> {
            val safeHuW = huWidth.coerceAtLeast(1f)
            val safeHuH = huHeight.coerceAtLeast(1f)
            val safeScreenW = screenWidth.coerceAtLeast(1).toFloat()
            val safeScreenH = screenHeight.coerceAtLeast(1).toFloat()

            // MediaProjection AUTO_MIRROR fits the phone screen inside huWidth x huHeight preserving aspect ratio.
            val scale = minOf(safeHuW / safeScreenW, safeHuH / safeScreenH)
            val drawnW = (safeScreenW * scale).coerceAtLeast(1f)
            val drawnH = (safeScreenH * scale).coerceAtLeast(1f)
            val offsetX = (safeHuW - drawnW) / 2f
            val offsetY = (safeHuH - drawnH) / 2f

            val normX = ((rawX.toFloat() - offsetX) / drawnW).coerceIn(0f, 1f)
            val normY = ((rawY.toFloat() - offsetY) / drawnH).coerceIn(0f, 1f)

            val physX = (normX * safeScreenW).coerceIn(0f, (safeScreenW - 1f).coerceAtLeast(0f))
            val physY = (normY * safeScreenH).coerceIn(0f, (safeScreenH - 1f).coerceAtLeast(0f))
            return physX to physY
        }

        fun computeSwipeDurationMs(downTimeMs: Long, upTimeMs: Long): Long {
            return (upTimeMs - downTimeMs).coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        }

        fun isShortTapGesture(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            points: List<Pair<Float, Float>>
        ): Boolean {
            val endDx = endX - startX
            val endDy = endY - startY
            if (endDx * endDx + endDy * endDy >= TAP_SLOP_PX_SQ) return false
            for (pt in points) {
                val dx = pt.first - startX
                val dy = pt.second - startY
                if (dx * dx + dy * dy >= TAP_SLOP_PX_SQ) return false
            }
            return true
        }
    }
}
