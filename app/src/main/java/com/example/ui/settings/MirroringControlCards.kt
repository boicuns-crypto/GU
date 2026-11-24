package com.example.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.input.TouchSensitivityConfig
import com.example.core.input.TouchSensitivityPreset
import com.example.core.input.TouchSettingsPreferences
import com.example.core.projection.ScreenKeepAlivePreferences
import com.example.core.projection.ScreenKeepAliveState
import com.example.ui.theme.tabular
import com.example.ui.theme.vwColors
import kotlin.math.roundToInt

/**
 * VW MIB3 styled card for real-time Head Unit (`0x0006`) touch sensitivity configuration.
 */
@Composable
fun TouchSensitivitySettingsCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val config by TouchSettingsPreferences.config.collectAsStateWithLifecycle()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("touch_sensitivity_card"),
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
                        text = "Чувствительность тача (0x0006)",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Отклик кликов, свайпов и фильтр тряски на экране ГУ",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                CompactPillBadge(
                    label = config.preset.label,
                    active = true
                )
            }

            // 3-way VW segmented preset bar: Мягкая | Стандарт | Высокая
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.background)
                    .border(1.dp, colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(3.dp)
                    .testTag("touch_preset_segmented_control"),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                TouchSensitivityPreset.selectablePresets.forEach { preset ->
                    val selected = config.preset == preset
                    val tag = when (preset) {
                        TouchSensitivityPreset.SMOOTH -> "touch_preset_smooth_btn"
                        TouchSensitivityPreset.STANDARD -> "touch_preset_standard_btn"
                        TouchSensitivityPreset.HIGH -> "touch_preset_high_btn"
                        TouchSensitivityPreset.CUSTOM -> "touch_preset_custom_btn"
                    }
                    val bgColor by animateColorAsState(
                        targetValue = if (selected) colorScheme.primary else Color.Transparent,
                        animationSpec = tween(durationMillis = 180),
                        label = "touch_preset_bg"
                    )
                    val txtColor by animateColorAsState(
                        targetValue = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 180),
                        label = "touch_preset_txt"
                    )
                    val indicatorColor by animateColorAsState(
                        targetValue = if (selected) {
                            if (vw.isDark) Color.White.copy(alpha = 0.85f) else vw.accentBlue
                        } else {
                            Color.Transparent
                        },
                        animationSpec = tween(durationMillis = 180),
                        label = "touch_preset_line"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(bgColor)
                            .selectable(
                                selected = selected,
                                onClick = { TouchSettingsPreferences.applyPreset(preset, context) },
                                role = Role.RadioButton
                            )
                            .testTag(tag),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = preset.label,
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

            // Preset description
            Text(
                text = config.preset.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )

            // Sliders container
            Surface(
                color = colorScheme.background,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TouchSliderRow(
                        label = "Порог тапа (анти-дрожание)",
                        valueText = "${config.tapSlopPx.roundToInt()} px",
                        helper = "Выше — проще попадать по кнопкам на кочках без случайных свайпов",
                        value = config.tapSlopPx,
                        valueRange = TouchSensitivityConfig.MIN_TAP_SLOP_PX..TouchSensitivityConfig.MAX_TAP_SLOP_PX,
                        testTag = "touch_tap_slop_slider",
                        onValueChange = { v ->
                            TouchSettingsPreferences.updateConfig(context) {
                                it.copy(tapSlopPx = v.roundToInt().toFloat())
                            }
                        }
                    )

                    TouchSliderRow(
                        label = "Длительность клика",
                        valueText = "${config.tapDurationMs} мс",
                        helper = "Короче — быстрее отклик; длиннее — надёжнее в тяжёлых меню",
                        value = config.tapDurationMs.toFloat(),
                        valueRange = TouchSensitivityConfig.MIN_TAP_DURATION_MS.toFloat()..TouchSensitivityConfig.MAX_TAP_DURATION_MS.toFloat(),
                        testTag = "touch_tap_duration_slider",
                        onValueChange = { v ->
                            TouchSettingsPreferences.updateConfig(context) {
                                it.copy(tapDurationMs = (v / 5f).roundToInt() * 5L)
                            }
                        }
                    )

                    TouchSliderRow(
                        label = "Скорость свайпа",
                        valueText = "${"%.2f".format(config.swipeSpeedFactor)}×",
                        helper = "Ускоряет или смягчает прокрутку списков и сдвиг карты",
                        value = config.swipeSpeedFactor,
                        valueRange = TouchSensitivityConfig.MIN_SWIPE_SPEED_FACTOR..TouchSensitivityConfig.MAX_SWIPE_SPEED_FACTOR,
                        testTag = "touch_swipe_speed_slider",
                        onValueChange = { v ->
                            val snapped = ((v * 20f).roundToInt()) / 20f
                            TouchSettingsPreferences.updateConfig(context) {
                                it.copy(swipeSpeedFactor = snapped)
                            }
                        }
                    )

                    TouchSliderRow(
                        label = "Фильтр микро-дрожания (Deadzone)",
                        valueText = "${"%.1f".format(config.moveDeadzonePx)} px",
                        helper = "Отсекает дрожание координат тачскрина ГУ при удержании пальца",
                        value = config.moveDeadzonePx,
                        valueRange = TouchSensitivityConfig.MIN_MOVE_DEADZONE_PX..TouchSensitivityConfig.MAX_MOVE_DEADZONE_PX,
                        testTag = "touch_deadzone_slider",
                        onValueChange = { v ->
                            val snapped = ((v * 2f).roundToInt()) / 2f
                            TouchSettingsPreferences.updateConfig(context) {
                                it.copy(moveDeadzonePx = snapped)
                            }
                        }
                    )

                    TouchSliderRow(
                        label = "Долгое нажатие",
                        valueText = "${config.longPressDurationMs} мс",
                        helper = "Время удержания для контекстных меню и меток на карте",
                        value = config.longPressDurationMs.toFloat(),
                        valueRange = TouchSensitivityConfig.MIN_LONG_PRESS_DURATION_MS.toFloat()..TouchSensitivityConfig.MAX_LONG_PRESS_DURATION_MS.toFloat(),
                        testTag = "touch_long_press_slider",
                        onValueChange = { v ->
                            TouchSettingsPreferences.updateConfig(context) {
                                it.copy(longPressDurationMs = (v / 25f).roundToInt() * 25L)
                            }
                        }
                    )
                }
            }

            // Edge back guard switch
            SettingsSwitchRow(
                title = "Защита краёв One UI",
                subtitle = "Смещает клики у левого/правого края на 14 px внутрь, чтобы не срабатывал жест «Назад»",
                checked = config.edgeBackGuardEnabled,
                testTag = "touch_edge_guard_switch",
                onCheckedChange = { enabled ->
                    TouchSettingsPreferences.updateConfig(
                        context = context,
                        markCustomIfParamsChanged = false
                    ) {
                        it.copy(edgeBackGuardEnabled = enabled)
                    }
                }
            )

            OutlinedButton(
                onClick = { TouchSettingsPreferences.reset(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("touch_reset_btn"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.onSurface)
            ) {
                Text(
                    text = "Сбросить настройки тача (Стандарт)",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * VW MIB3 styled card for Mirroring USB screen automation:
 * - Automatic 1% OLED brightness on USB connect (restores normal screen on USB disconnect)
 * - Automatic landscape-left screen rotation on USB connect (restores normal orientation on USB disconnect)
 * - Screen-off survival (Mirroring + 0x0003 Audio) and keep-awake
 */
@Composable
fun ScreenKeepAliveSettingsCard(
    keepAliveState: ScreenKeepAliveState,
    hasOverlayPermission: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onNotifyServiceConfigChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val config by ScreenKeepAlivePreferences.config.collectAsStateWithLifecycle()

    val badgeLabel = when {
        keepAliveState.isPhoneScreenOff -> "ПОГАШЕН • LIVE"
        keepAliveState.isDimmingActive && keepAliveState.isAutoRotateLeftActive -> "1% • ВЛЕВО"
        keepAliveState.isDimmingActive -> "ЯРКОСТЬ 1%"
        keepAliveState.isAutoRotateLeftActive -> "ВЛЕВО"
        keepAliveState.isKeepAwakeOverlayActive -> "USB АКТИВЕН"
        keepAliveState.isUsbConnected && !keepAliveState.isMirroringMode -> "НЕ ЗЕРКАЛО"
        else -> "ОБЫЧНЫЙ"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("screen_keep_alive_card"),
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
                        text = "Экран при Зеркалировании (USB)",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Яркость 1%, переворот влево и возврат в обычный режим по USB",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                CompactPillBadge(
                    label = badgeLabel,
                    active = keepAliveState.isDimmingActive ||
                        keepAliveState.isAutoRotateLeftActive ||
                        keepAliveState.isKeepAwakeOverlayActive
                )
            }

            // Live status strip
            Surface(
                color = colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
            ) {
                Text(
                    text = keepAliveState.statusSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("screen_keep_alive_status")
                )
            }

            SettingsSwitchRow(
                title = "Яркость 1% при подключении USB",
                subtitle = "Автоматически снижает подсветку смартфона до 1% по USB (на ГУ 100%) и возвращает обычный режим экрана при отключении кабеля",
                checked = config.dimPhoneScreenDuringMirroring,
                testTag = "screen_dim_phone_switch",
                onCheckedChange = { enabled ->
                    ScreenKeepAlivePreferences.updateConfig(context) {
                        it.copy(dimPhoneScreenDuringMirroring = enabled)
                    }
                    onNotifyServiceConfigChanged()
                }
            )

            SettingsSwitchRow(
                title = "Автоповорот экрана влево по USB",
                subtitle = "Автоматически переворачивает экран влево (альбомно) при подключении USB-кабеля и возвращает обычное положение при отключении",
                checked = config.autoRotateLeftOnUsb,
                testTag = "screen_auto_rotate_left_switch",
                onCheckedChange = { enabled ->
                    ScreenKeepAlivePreferences.updateConfig(context) {
                        it.copy(autoRotateLeftOnUsb = enabled)
                    }
                    onNotifyServiceConfigChanged()
                }
            )

            SettingsSwitchRow(
                title = "Не отключать Зеркалирование и звук при гашении",
                subtitle = "Удерживает MediaProjection, H.264 и аудио 0x0003 в фоне (PARTIAL_WAKE_LOCK + Heartbeat) и мгновенно восстанавливает кадр",
                checked = config.keepAliveOnScreenOff,
                testTag = "screen_keep_alive_switch",
                onCheckedChange = { enabled ->
                    ScreenKeepAlivePreferences.updateConfig(context) {
                        it.copy(keepAliveOnScreenOff = enabled)
                    }
                    onNotifyServiceConfigChanged()
                }
            )

            SettingsSwitchRow(
                title = "Не давать экрану уснуть по USB",
                subtitle = "Удерживает экран активным (FLAG_KEEP_SCREEN_ON) во время поездки по USB в Навигаторе, 2ГИС и YouTube",
                checked = config.keepScreenAwakeOverlay,
                testTag = "screen_keep_awake_overlay_switch",
                onCheckedChange = { enabled ->
                    ScreenKeepAlivePreferences.updateConfig(context) {
                        it.copy(keepScreenAwakeOverlay = enabled)
                    }
                    onNotifyServiceConfigChanged()
                }
            )

            SettingsSwitchRow(
                title = "Автопробуждение при случайной блокировке",
                subtitle = "Если случайно нажали кнопку питания во время USB-Зеркалирования — мягко разбудит дисплей, чтобы видео не встало на паузу",
                checked = config.autoWakeOnScreenOff,
                testTag = "screen_auto_wake_switch",
                onCheckedChange = { enabled ->
                    ScreenKeepAlivePreferences.updateConfig(context) {
                        it.copy(autoWakeOnScreenOff = enabled)
                    }
                    onNotifyServiceConfigChanged()
                }
            )

            val needsOverlayPerm = config.keepScreenAwakeOverlay ||
                config.dimPhoneScreenDuringMirroring ||
                config.autoRotateLeftOnUsb
            if (!hasOverlayPermission && needsOverlayPerm) {
                FilledTonalButton(
                    onClick = onOpenOverlaySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("screen_grant_overlay_btn"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = vw.warningContainer,
                        contentColor = vw.onWarningContainer
                    )
                ) {
                    Text(
                        text = "Выдать разрешение Overlay для яркости 1% и автоповорота",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchSliderRow(
    label: String,
    valueText: String,
    helper: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.primary
            )
        }
        Text(
            text = helper,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = colorScheme.primary,
                activeTrackColor = colorScheme.primary,
                inactiveTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
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
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorScheme.onPrimary,
                checkedTrackColor = colorScheme.primary,
                uncheckedThumbColor = colorScheme.onSurfaceVariant,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun CompactPillBadge(
    label: String,
    active: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors
    val bg = if (active) vw.successContainer else colorScheme.surfaceVariant
    val fg = if (active) vw.onSuccessContainer else colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.7f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
