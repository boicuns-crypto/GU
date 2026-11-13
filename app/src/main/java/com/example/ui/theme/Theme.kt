package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CockpitColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF00262C),
    primaryContainer = Color(0xFF003D47),
    onPrimaryContainer = Color(0xFF9CF2FF),
    secondary = CyberBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF142B52),
    onSecondaryContainer = Color(0xFFD3E3FD),
    tertiary = TelemetryEmerald,
    onTertiary = Color(0xFF002911),
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = ObsidianBorder,
    error = CoralAlert
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CockpitColorScheme,
        typography = Typography,
        content = content
    )
}
