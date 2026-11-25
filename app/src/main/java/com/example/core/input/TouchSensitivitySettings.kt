package com.example.core.input

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Presets for Head Unit (`0x0006`) touch sensitivity and anti-jitter filtering.
 */
enum class TouchSensitivityPreset(
    val id: String,
    val label: String,
    val subtitle: String
) {
    SMOOTH(
        id = "smooth",
        label = "Мягкая",
        subtitle = "В движении: сглаживает тряску, крупнее допуск тапа"
    ),
    STANDARD(
        id = "standard",
        label = "Стандарт",
        subtitle = "Оптимальный баланс точности клика и плавности свайпов"
    ),
    HIGH(
        id = "high",
        label = "Высокая",
        subtitle = "Мгновенный клик и максимально резвый отклик карт"
    ),
    CUSTOM(
        id = "custom",
        label = "Своя",
        subtitle = "Индивидуальные параметры тапа, свайпа и подавления дрожания"
    );

    companion object {
        val selectablePresets: List<TouchSensitivityPreset> = listOf(SMOOTH, STANDARD, HIGH)

        fun fromId(raw: String?): TouchSensitivityPreset =
            entries.firstOrNull { it.id == raw } ?: STANDARD
    }
}

/**
 * Real-time configurable touch sensitivity parameters applied across Mirroring
 * (`CarLifeAccessibilityService`), Cockpit, and Shizuku touch injection (`0x0006`).
 */
data class TouchSensitivityConfig(
    val preset: TouchSensitivityPreset = TouchSensitivityPreset.STANDARD,
    /**
     * Maximum finger movement (in physical screen px) still recognized as a TAP instead of a SWIPE.
     * Higher values prevent bumps on the road from turning button taps into accidental micro-swipes.
     */
    val tapSlopPx: Float = DEFAULT_TAP_SLOP_PX,
    /**
     * Duration of a synthesized tap gesture in milliseconds (`25..120 ms`).
     */
    val tapDurationMs: Long = DEFAULT_TAP_DURATION_MS,
    /**
     * Multiplier for swipe speed (`0.6..1.6`). Higher values make map drags and scrolling snappier.
     */
    val swipeSpeedFactor: Float = DEFAULT_SWIPE_SPEED_FACTOR,
    /**
     * Minimum pointer delta (in target display px) required to append/dispatch an `ACTION_MOVE` event.
     * Suppresses resistive/capacitive Head Unit coordinate jitter.
     */
    val moveDeadzonePx: Float = DEFAULT_MOVE_DEADZONE_PX,
    /**
     * Long-press gesture duration in milliseconds (`350..900 ms`).
     */
    val longPressDurationMs: Long = DEFAULT_LONG_PRESS_DURATION_MS,
    /**
     * Guards left/right edges against accidentally triggering Samsung One UI system back gestures.
     */
    val edgeBackGuardEnabled: Boolean = true
) {
    val tapSlopPxSq: Float
        get() = tapSlopPx * tapSlopPx

    val moveDeadzonePxSq: Float
        get() = moveDeadzonePx * moveDeadzonePx

    fun sanitized(): TouchSensitivityConfig = copy(
        tapSlopPx = tapSlopPx.coerceIn(MIN_TAP_SLOP_PX, MAX_TAP_SLOP_PX),
        tapDurationMs = tapDurationMs.coerceIn(MIN_TAP_DURATION_MS, MAX_TAP_DURATION_MS),
        swipeSpeedFactor = swipeSpeedFactor.coerceIn(MIN_SWIPE_SPEED_FACTOR, MAX_SWIPE_SPEED_FACTOR),
        moveDeadzonePx = moveDeadzonePx.coerceIn(MIN_MOVE_DEADZONE_PX, MAX_MOVE_DEADZONE_PX),
        longPressDurationMs = longPressDurationMs.coerceIn(MIN_LONG_PRESS_DURATION_MS, MAX_LONG_PRESS_DURATION_MS)
    )

    companion object {
        const val MIN_TAP_SLOP_PX = 8f
        const val MAX_TAP_SLOP_PX = 64f
        const val DEFAULT_TAP_SLOP_PX = 24f

        const val MIN_TAP_DURATION_MS = 25L
        const val MAX_TAP_DURATION_MS = 120L
        const val DEFAULT_TAP_DURATION_MS = 50L

        const val MIN_SWIPE_SPEED_FACTOR = 0.6f
        const val MAX_SWIPE_SPEED_FACTOR = 1.6f
        const val DEFAULT_SWIPE_SPEED_FACTOR = 1.0f

        const val MIN_MOVE_DEADZONE_PX = 1f
        const val MAX_MOVE_DEADZONE_PX = 10f
        const val DEFAULT_MOVE_DEADZONE_PX = 3f

        const val MIN_LONG_PRESS_DURATION_MS = 350L
        const val MAX_LONG_PRESS_DURATION_MS = 900L
        const val DEFAULT_LONG_PRESS_DURATION_MS = 550L

        fun forPreset(
            preset: TouchSensitivityPreset,
            edgeBackGuardEnabled: Boolean = true
        ): TouchSensitivityConfig = when (preset) {
            TouchSensitivityPreset.SMOOTH -> TouchSensitivityConfig(
                preset = TouchSensitivityPreset.SMOOTH,
                tapSlopPx = 42f,
                tapDurationMs = 70L,
                swipeSpeedFactor = 0.85f,
                moveDeadzonePx = 5f,
                longPressDurationMs = 650L,
                edgeBackGuardEnabled = edgeBackGuardEnabled
            )
            TouchSensitivityPreset.STANDARD -> TouchSensitivityConfig(
                preset = TouchSensitivityPreset.STANDARD,
                tapSlopPx = DEFAULT_TAP_SLOP_PX,
                tapDurationMs = DEFAULT_TAP_DURATION_MS,
                swipeSpeedFactor = DEFAULT_SWIPE_SPEED_FACTOR,
                moveDeadzonePx = DEFAULT_MOVE_DEADZONE_PX,
                longPressDurationMs = DEFAULT_LONG_PRESS_DURATION_MS,
                edgeBackGuardEnabled = edgeBackGuardEnabled
            )
            TouchSensitivityPreset.HIGH -> TouchSensitivityConfig(
                preset = TouchSensitivityPreset.HIGH,
                tapSlopPx = 12f,
                tapDurationMs = 35L,
                swipeSpeedFactor = 1.35f,
                moveDeadzonePx = 1.5f,
                longPressDurationMs = 450L,
                edgeBackGuardEnabled = edgeBackGuardEnabled
            )
            TouchSensitivityPreset.CUSTOM -> TouchSensitivityConfig(
                preset = TouchSensitivityPreset.CUSTOM,
                edgeBackGuardEnabled = edgeBackGuardEnabled
            )
        }
    }
}

/**
 * Persistent storage and reactive state holder for Head Unit touch sensitivity settings.
 */
object TouchSettingsPreferences {
    private const val PREFS_NAME = "carlife_bridge_prefs"
    private const val KEY_PRESET = "touch_sensitivity_preset"
    private const val KEY_TAP_SLOP_PX = "touch_tap_slop_px"
    private const val KEY_TAP_DURATION_MS = "touch_tap_duration_ms"
    private const val KEY_SWIPE_SPEED_FACTOR = "touch_swipe_speed_factor"
    private const val KEY_MOVE_DEADZONE_PX = "touch_move_deadzone_px"
    private const val KEY_LONG_PRESS_MS = "touch_long_press_ms"
    private const val KEY_EDGE_GUARD = "touch_edge_back_guard"
    private const val KEY_SWAP_STEERING_WHEEL_TRACKS = "steering_wheel_swap_track_buttons"
    private const val KEY_SHOW_STEERING_WHEEL_TOAST = "steering_wheel_show_key_codes_toast"

    private val _config = MutableStateFlow(TouchSensitivityConfig())
    val config: StateFlow<TouchSensitivityConfig> = _config.asStateFlow()

    private val _steeringWheelConfig = MutableStateFlow(SteeringWheelConfig())
    val steeringWheelConfig: StateFlow<SteeringWheelConfig> = _steeringWheelConfig.asStateFlow()

    fun init(context: Context): TouchSensitivityConfig {
        val loaded = load(context)
        _config.value = loaded
        _steeringWheelConfig.value = loadSteeringWheelConfig(context)
        return loaded
    }

    fun loadSteeringWheelConfig(context: Context): SteeringWheelConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SteeringWheelConfig(
            swapTrackButtons = prefs.getBoolean(KEY_SWAP_STEERING_WHEEL_TRACKS, false),
            showKeyCodesToast = prefs.getBoolean(KEY_SHOW_STEERING_WHEEL_TOAST, true)
        )
    }

    fun setSwapSteeringWheelTrackButtons(
        swap: Boolean,
        context: Context? = null
    ): SteeringWheelConfig {
        val next = _steeringWheelConfig.value.copy(swapTrackButtons = swap)
        _steeringWheelConfig.value = next
        if (context != null) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SWAP_STEERING_WHEEL_TRACKS, swap)
                .apply()
        }
        return next
    }

    fun setShowSteeringWheelKeyCodesToast(
        showToast: Boolean,
        context: Context? = null
    ): SteeringWheelConfig {
        val next = _steeringWheelConfig.value.copy(showKeyCodesToast = showToast)
        _steeringWheelConfig.value = next
        if (context != null) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SHOW_STEERING_WHEEL_TOAST, showToast)
                .apply()
        }
        return next
    }

    fun load(context: Context): TouchSensitivityConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PRESET) && !prefs.contains(KEY_TAP_SLOP_PX)) {
            return TouchSensitivityConfig()
        }
        val preset = TouchSensitivityPreset.fromId(prefs.getString(KEY_PRESET, TouchSensitivityPreset.STANDARD.id))
        val defaults = TouchSensitivityConfig.forPreset(preset)
        return TouchSensitivityConfig(
            preset = preset,
            tapSlopPx = prefs.getFloat(KEY_TAP_SLOP_PX, defaults.tapSlopPx),
            tapDurationMs = prefs.getLong(KEY_TAP_DURATION_MS, defaults.tapDurationMs),
            swipeSpeedFactor = prefs.getFloat(KEY_SWIPE_SPEED_FACTOR, defaults.swipeSpeedFactor),
            moveDeadzonePx = prefs.getFloat(KEY_MOVE_DEADZONE_PX, defaults.moveDeadzonePx),
            longPressDurationMs = prefs.getLong(KEY_LONG_PRESS_MS, defaults.longPressDurationMs),
            edgeBackGuardEnabled = prefs.getBoolean(KEY_EDGE_GUARD, defaults.edgeBackGuardEnabled)
        ).sanitized()
    }

    fun applyPreset(
        preset: TouchSensitivityPreset,
        context: Context? = null
    ): TouchSensitivityConfig {
        val currentGuard = _config.value.edgeBackGuardEnabled
        val next = TouchSensitivityConfig.forPreset(
            preset = preset,
            edgeBackGuardEnabled = currentGuard
        ).sanitized()
        _config.value = next
        if (context != null) {
            save(context, next)
        }
        return next
    }

    fun updateConfig(
        context: Context? = null,
        markCustomIfParamsChanged: Boolean = true,
        transform: (TouchSensitivityConfig) -> TouchSensitivityConfig
    ): TouchSensitivityConfig {
        val prev = _config.value
        var next = transform(prev).sanitized()
        if (markCustomIfParamsChanged &&
            next.preset == prev.preset &&
            (next.tapSlopPx != prev.tapSlopPx ||
                next.tapDurationMs != prev.tapDurationMs ||
                next.swipeSpeedFactor != prev.swipeSpeedFactor ||
                next.moveDeadzonePx != prev.moveDeadzonePx ||
                next.longPressDurationMs != prev.longPressDurationMs)
        ) {
            next = next.copy(preset = TouchSensitivityPreset.CUSTOM)
        }
        _config.value = next
        if (context != null) {
            save(context, next)
        }
        return next
    }

    fun reset(context: Context? = null): TouchSensitivityConfig {
        val defaults = TouchSensitivityConfig.forPreset(TouchSensitivityPreset.STANDARD, edgeBackGuardEnabled = true)
        _config.value = defaults
        if (context != null) {
            save(context, defaults)
        }
        return defaults
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PRESET)
            .remove(KEY_TAP_SLOP_PX)
            .remove(KEY_TAP_DURATION_MS)
            .remove(KEY_SWIPE_SPEED_FACTOR)
            .remove(KEY_MOVE_DEADZONE_PX)
            .remove(KEY_LONG_PRESS_MS)
            .remove(KEY_EDGE_GUARD)
            .remove(KEY_SWAP_STEERING_WHEEL_TRACKS)
            .remove(KEY_SHOW_STEERING_WHEEL_TOAST)
            .apply()
        _config.value = TouchSensitivityConfig()
        _steeringWheelConfig.value = SteeringWheelConfig()
    }

    private fun save(context: Context, cfg: TouchSensitivityConfig) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESET, cfg.preset.id)
            .putFloat(KEY_TAP_SLOP_PX, cfg.tapSlopPx)
            .putLong(KEY_TAP_DURATION_MS, cfg.tapDurationMs)
            .putFloat(KEY_SWIPE_SPEED_FACTOR, cfg.swipeSpeedFactor)
            .putFloat(KEY_MOVE_DEADZONE_PX, cfg.moveDeadzonePx)
            .putLong(KEY_LONG_PRESS_MS, cfg.longPressDurationMs)
            .putBoolean(KEY_EDGE_GUARD, cfg.edgeBackGuardEnabled)
            .apply()
    }
}

/**
 * Steering wheel (`0x00060008`) runtime configuration:
 * - [swapTrackButtons]: swaps `15`/`7` (`previous()`) and `16`/`6` (`next()`) without rebuilding (default `false`).
 * - [showKeyCodesToast]: displays a short diagnostic `Toast` upon receiving any `0x00060008` packet (default `true`).
 */
data class SteeringWheelConfig(
    val swapTrackButtons: Boolean = false,
    val showKeyCodesToast: Boolean = true
)
