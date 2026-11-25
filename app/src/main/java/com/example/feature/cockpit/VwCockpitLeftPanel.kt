package com.example.feature.cockpit

import android.view.Display
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.theme.tabular
import com.example.ui.theme.vwColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Exact width of the permanent VW MIB3 Left Panel in `1280×720 @ 160 dpi` coordinates (`250 px == 250 dp`).
 */
const val VW_LEFT_PANEL_WIDTH_PX = 250

/**
 * Permanent Volkswagen MIB3 Left Sidebar (`250.dp` wide out of `1280.dp`) shared across:
 * 1. Standard Cockpit mode (`Presentation`, right pane switches between 4×3 Grid and Yandex Maps WebView).
 * 2. Cockpit + Shizuku mode (rendered inside `Presentation` on Launcher Grid AND inside
 *    `ShizukuLeftPanelOverlay` floating on `x = 0..250` above Yandex Navigator / 2GIS).
 *
 * Top-to-bottom layout:
 * 1. Large `tnum` clock + `JETTA VS7` badge + compact Day/Night theme toggle button.
 * 2. Navigation keys: «Домой», «Назад», «Карта».
 * 3. Bottom: Compact VW MIB3 Music Player widget (`⏮`, `⏯`, `⏭`).
 */
@Composable
fun VwCockpitLeftPanel(
    virtualDisplayId: Int,
    mediaState: MediaState,
    isMapActive: Boolean,
    onHomeClick: () -> Unit,
    onBackClick: () -> Unit,
    onMapClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit = {},
    isFloatingOverlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    var currentTime by remember { mutableStateOf("12:00") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            currentTime = fmt.format(Date())
            delay(10_000L)
        }
    }

    val panelShape = if (isFloatingOverlay) {
        RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(14.dp)
    }

    Card(
        modifier = modifier
            .width(VW_LEFT_PANEL_WIDTH_PX.dp)
            .fillMaxHeight()
            .testTag("vw_cockpit_left_panel"),
        shape = panelShape,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top block: Crisp tabular clock + Day/Night button, with JETTA Connect badge underneath
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentTime,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = colorScheme.onSurface,
                        modifier = Modifier.testTag("dock_clock_text")
                    )

                    // Compact Day/Night theme switcher (48.dp touch target for easy driving tap)
                    IconButton(
                        onClick = {
                            val nextMode = if (vw.isDark) ThemeMode.LIGHT else ThemeMode.DARK
                            ThemePreferences.setThemeMode(nextMode, context)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.surfaceVariant)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                            .testTag("dock_theme_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (vw.isDark) CockpitIcons.LightMode else CockpitIcons.DarkMode,
                            contentDescription = if (vw.isDark) "Переключить на дневную тему" else "Переключить на ночную тему",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier.testTag("dock_brand_badge"),
                    color = colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                        vw.statusActiveDot
                                    } else {
                                        vw.accentBlue
                                    }
                                )
                        )
                        Text(
                            text = "JETTA Connect",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            ),
                            color = colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Thin VW Electric Blue accent line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    vw.accentBlue,
                                    vw.accentBlue.copy(alpha = 0.14f)
                                )
                            )
                        )
                )

                // 2. Navigation Keys: «Домой», «Назад», «Карта»
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VwDockNavKey(
                            label = "Домой",
                            icon = CockpitIcons.Home,
                            active = !isMapActive,
                            testTag = "dock_home_button",
                            onClick = onHomeClick,
                            modifier = Modifier.weight(1f)
                        )
                        VwDockNavKey(
                            label = "Назад",
                            icon = CockpitIcons.ArrowBack,
                            active = false,
                            testTag = "dock_back_button",
                            onClick = onBackClick,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    VwDockNavKey(
                        label = if (isMapActive) "Карта • Активна" else "Карта",
                        icon = CockpitIcons.Map,
                        active = isMapActive,
                        testTag = "dock_map_button",
                        onClick = onMapClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 3. Bottom block: Compact VW MIB3 Music Player Widget
            VwCompactMediaPlayerWidget(
                mediaState = mediaState,
                onPlayPause = onPlayPause,
                onNextTrack = onNextTrack,
                onPreviousTrack = onPreviousTrack,
                onRequestNotificationAccess = onRequestNotificationAccess,
                showGrantAccessCta = !isFloatingOverlay && !mediaState.hasNotificationAccess
            )
        }
    }
}

@Composable
internal fun VwDockNavKey(
    label: String,
    icon: ImageVector,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (active) colorScheme.primary else colorScheme.surfaceVariant
    val contentColor = if (active) colorScheme.onPrimary else colorScheme.onSurface
    val borderColor = if (active) colorScheme.primary else colorScheme.outline

    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Compact VW MIB3 Music Player widget with album cover / music note, marquee track title & artist,
 * and three large driving-safe transport buttons (`⏮`, `⏯`, `⏭`).
 */
@Composable
fun VwCompactMediaPlayerWidget(
    mediaState: MediaState,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit = {},
    showGrantAccessCta: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("vw_media_player_widget"),
        color = colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Signature VW MIB3 top accent stripe
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(if (mediaState.isPlaying) colorScheme.primary else colorScheme.outline)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mini album art or Music Note fallback
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = mediaState.albumArt
                        if (bmp != null && !bmp.isRecycled) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Обложка трека",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = CockpitIcons.MusicNote,
                                contentDescription = "Медиаплеер",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaState.playerAppTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = vw.accentBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = mediaState.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Text(
                            text = mediaState.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                }

                if (showGrantAccessCta) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRequestNotificationAccess)
                            .testTag("grant_media_notif_btn"),
                        color = vw.warningContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Включить доступ к плееру",
                            style = MaterialTheme.typography.labelSmall,
                            color = vw.onWarningContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 5.dp, horizontal = 6.dp)
                        )
                    }
                }

                // 3 large driving-safe transport buttons (>= 48.dp touch target)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPreviousTrack,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outline, CircleShape)
                            .testTag("media_prev_button")
                    ) {
                        Icon(
                            imageVector = CockpitIcons.SkipPrevious,
                            contentDescription = "Предыдущий трек",
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary)
                            .testTag("media_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) CockpitIcons.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Воспроизвести или пауза",
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = onNextTrack,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outline, CircleShape)
                            .testTag("media_next_button")
                    ) {
                        Icon(
                            imageVector = CockpitIcons.SkipNext,
                            contentDescription = "Следующий трек",
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
