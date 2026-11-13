package com.example.core.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multi-touch pointer coordinate scaled to the target `VirtualDisplay`.
 */
data class ScaledTouchPointer(
    val pointerId: Int,
    val x: Float,
    val y: Float
)

/**
 * AccessibilityService для проброса касаний (Touch Down / Move / Up / Multi-touch)
 * с тачскрина ГУ (Baidu CarLife канал 0x0006) внутрь сторонних Android-приложений
 * (Яндекс.Навигатор, 2ГИС, браузеры, видеоплееры), запущенных на `VirtualDisplay`.
 */
class CarLifeTouchAccessibilityService : AccessibilityService() {

    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var touchLastX: Float = 0f
    private var touchLastY: Float = 0f
    private var touchDownTimestampMs: Long = 0L
    private var isTrackingGesture: Boolean = false
    private val movementPoints = ArrayList<Pair<Float, Float>>(32)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceEnabled.value = true
        Log.i(TAG, "CarLifeTouchAccessibilityService connected for VirtualDisplay touch injection")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window content inspection is not required for coordinate touch injection
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
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isServiceEnabled.value = false
        super.onDestroy()
    }

    /**
     * Synthesizes a single tap gesture on the target [displayId] (`VirtualDisplay.display.displayId`).
     */
    fun injectTap(
        displayId: Int,
        x: Float,
        y: Float,
        durationMs: Long = 40L
    ): Boolean {
        val safeX = x.coerceAtLeast(0f)
        val safeY = y.coerceAtLeast(0f)
        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(10L, 500L))
        val builder = GestureDescription.Builder().addStroke(stroke)
        applyTargetDisplayId(builder, displayId)
        return runCatching {
            dispatchGesture(builder.build(), null, null)
        }.getOrDefault(false)
    }

    /**
     * Synthesizes a continuous path/drag/swipe gesture on the target [displayId].
     */
    fun injectPathGesture(
        displayId: Int,
        points: List<Pair<Float, Float>>,
        durationMs: Long = 120L
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
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(25L, 1500L))
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
        displayId: Int,
        startPointers: List<ScaledTouchPointer>,
        endPointers: List<ScaledTouchPointer>,
        durationMs: Long = 160L
    ): Boolean {
        val count = minOf(startPointers.size, endPointers.size).coerceAtMost(GestureDescription.getMaxStrokeCount())
        if (count <= 0) return false
        val builder = GestureDescription.Builder()
        val clampedDuration = durationMs.coerceIn(30L, 1200L)
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
     * Processes raw `ACTION_DOWN` / `ACTION_MOVE` / `ACTION_UP` touch stream from CarLife channel `0x0006`
     * and dispatches either a tap or a full trajectory path gesture onto [displayId].
     */
    fun handleMotionStream(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        synthesizeClickUp: Boolean
    ): Boolean {
        if (synthesizeClickUp) {
            return injectTap(displayId = displayId, x = x, y = y, durationMs = 35L)
        }

        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = x
                touchStartY = y
                touchLastX = x
                touchLastY = y
                touchDownTimestampMs = System.currentTimeMillis()
                movementPoints.clear()
                movementPoints.add(x to y)
                isTrackingGesture = true
                true
            }

            MotionEvent.ACTION_MOVE -> {
                touchLastX = x
                touchLastY = y
                if (movementPoints.size < 32) {
                    movementPoints.add(x to y)
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!isTrackingGesture) {
                    injectTap(displayId = displayId, x = x, y = y, durationMs = 35L)
                } else {
                    isTrackingGesture = false
                    movementPoints.add(x to y)
                    val dx = x - touchStartX
                    val dy = y - touchStartY
                    val distanceSq = dx * dx + dy * dy
                    val duration = (System.currentTimeMillis() - touchDownTimestampMs).coerceIn(35L, 900L)
                    if (distanceSq < 144f) {
                        injectTap(displayId = displayId, x = x, y = y, durationMs = 35L)
                    } else {
                        injectPathGesture(
                            displayId = displayId,
                            points = movementPoints.toList(),
                            durationMs = duration
                        )
                    }
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
     * Maps Head Unit hard keys to Accessibility global actions when applicable.
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
        private const val TAG = "CarLifeTouchA11ySvc"

        @Volatile
        var instance: CarLifeTouchAccessibilityService? = null
            private set

        private val _isServiceEnabled = MutableStateFlow(false)
        val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
