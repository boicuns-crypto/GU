package com.example.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-selectable appearance mode applied synchronously to both the smartphone UI
 * and the Head Unit [com.example.core.projection.CockpitPresentation].
 */
enum class ThemeMode(
    val id: String,
    val label: String
) {
    SYSTEM(id = "system", label = "Авто"),
    LIGHT(id = "light", label = "Светлая"),
    DARK(id = "dark", label = "Тёмная");

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * SharedPreferences-backed reactive store for [ThemeMode].
 */
object ThemePreferences {
    private const val PREFS_NAME = "carlife_bridge_prefs"
    private const val KEY_THEME_MODE = "app_theme_mode"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun init(context: Context): ThemeMode {
        val loaded = loadThemeMode(context)
        _themeMode.value = loaded
        return loaded
    }

    fun loadThemeMode(context: Context): ThemeMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ThemeMode.fromId(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.id))
    }

    fun setThemeMode(mode: ThemeMode, context: Context? = null) {
        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_THEME_MODE, mode.id).commit()
        }
        _themeMode.value = mode
    }

    fun clear(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_THEME_MODE).commit()
        _themeMode.value = ThemeMode.SYSTEM
    }
}

/**
 * Extended VW MIB3 semantic colors complementing Material 3 [androidx.compose.material3.ColorScheme].
 */
@Immutable
data class VwExtendedColors(
    val isDark: Boolean,
    val accentBlue: Color,
    val statusActiveDot: Color,
    val statusWaitingDot: Color,
    val statusWarningDot: Color,
    val statusErrorDot: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val errorContainerSoft: Color,
    val onErrorContainerSoft: Color,
    val textMuted: Color,
    val topBarContainer: Color,
    val topBarDivider: Color
)

private val VwDarkExtendedColors = VwExtendedColors(
    isDark = true,
    accentBlue = VwNightPrimary,
    statusActiveDot = VwStatusGreen,
    statusWaitingDot = VwStatusBlue,
    statusWarningDot = VwStatusAmber,
    statusErrorDot = VwStatusRedDark,
    successContainer = VwNightSuccessContainer,
    onSuccessContainer = VwNightOnSuccessContainer,
    infoContainer = VwNightInfoContainer,
    onInfoContainer = VwNightOnInfoContainer,
    warningContainer = VwNightWarningContainer,
    onWarningContainer = VwNightOnWarningContainer,
    errorContainerSoft = VwNightErrorContainer,
    onErrorContainerSoft = VwNightOnErrorContainer,
    textMuted = VwNightTextMuted,
    topBarContainer = VwNightTopBarContainer,
    topBarDivider = VwNightTopBarDivider
)

private val VwLightExtendedColors = VwExtendedColors(
    isDark = false,
    accentBlue = VwDaySecondary,
    statusActiveDot = VwStatusGreen,
    statusWaitingDot = VwStatusBlue,
    statusWarningDot = VwStatusAmber,
    statusErrorDot = VwStatusRedLight,
    successContainer = VwDaySuccessContainer,
    onSuccessContainer = VwDayOnSuccessContainer,
    infoContainer = VwDayInfoContainer,
    onInfoContainer = VwDayOnInfoContainer,
    warningContainer = VwDayWarningContainer,
    onWarningContainer = VwDayOnWarningContainer,
    errorContainerSoft = VwDayErrorContainer,
    onErrorContainerSoft = VwDayOnErrorContainer,
    textMuted = VwDayTextMuted,
    topBarContainer = VwDayTopBarContainer,
    topBarDivider = VwDayTopBarDivider
)

val LocalVwColors = staticCompositionLocalOf { VwDarkExtendedColors }

val MaterialTheme.vwColors: VwExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVwColors.current

val VwDarkColorScheme = darkColorScheme(
    primary = VwNightPrimary,
    onPrimary = VwNightOnPrimary,
    primaryContainer = VwNightPrimaryContainer,
    onPrimaryContainer = VwNightOnPrimaryContainer,
    secondary = VwNightSurfaceVariant,
    onSecondary = VwNightOnBackground,
    secondaryContainer = VwNightSurfaceVariant,
    onSecondaryContainer = VwNightOnBackground,
    tertiary = VwStatusGreen,
    onTertiary = Color(0xFF022C22),
    background = VwNightBackground,
    onBackground = VwNightOnBackground,
    surface = VwNightSurface,
    onSurface = VwNightOnBackground,
    surfaceVariant = VwNightSurfaceVariant,
    onSurfaceVariant = VwNightOnSurfaceVariant,
    surfaceContainer = VwNightTopBarContainer,
    outline = VwNightOutline,
    outlineVariant = VwNightOutline,
    error = VwNightError,
    onError = Color.White,
    errorContainer = VwNightErrorContainer,
    onErrorContainer = VwNightOnErrorContainer
)

val VwLightColorScheme = lightColorScheme(
    primary = VwDayPrimary,
    onPrimary = VwDayOnPrimary,
    primaryContainer = VwDayPrimaryContainer,
    onPrimaryContainer = VwDayOnPrimaryContainer,
    secondary = VwDaySecondary,
    onSecondary = Color.White,
    secondaryContainer = VwDaySurfaceVariant,
    onSecondaryContainer = VwDayPrimary,
    tertiary = VwStatusGreen,
    onTertiary = Color.White,
    background = VwDayBackground,
    onBackground = VwDayOnBackground,
    surface = VwDaySurface,
    onSurface = VwDayOnBackground,
    surfaceVariant = VwDaySurfaceVariant,
    onSurfaceVariant = VwDayOnSurfaceVariant,
    surfaceContainer = VwDayTopBarContainer,
    outline = VwDayOutline,
    outlineVariant = VwDayOutline,
    error = VwDayError,
    onError = Color.White,
    errorContainer = VwDayErrorContainer,
    onErrorContainer = VwDayOnErrorContainer
)

val VwShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun resolveIsDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val persistedMode by ThemePreferences.themeMode.collectAsState()
    val effectiveMode = themeMode ?: persistedMode
    val isDark = resolveIsDarkTheme(effectiveMode)
    val colorScheme = if (isDark) VwDarkColorScheme else VwLightColorScheme
    val extendedColors = if (isDark) VwDarkExtendedColors else VwLightExtendedColors

    CompositionLocalProvider(LocalVwColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = VwShapes,
            content = content
        )
    }
}
