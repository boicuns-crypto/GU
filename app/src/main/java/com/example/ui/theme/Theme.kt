package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CockpitColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF001D24),
    primaryContainer = Color(0xFF003543),
    onPrimaryContainer = Color(0xFFB8F1FF),
    secondary = ObsidianSurfaceHigh,
    onSecondary = TextPrimary,
    secondaryContainer = ObsidianSurfaceHigh,
    onSecondaryContainer = TextPrimary,
    tertiary = TelemetryEmerald,
    onTertiary = Color(0xFF022C22),
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
