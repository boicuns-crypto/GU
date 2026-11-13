package com.example.feature.cockpit

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.input.CarLifeTouchAccessibilityService
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.TelemetryEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Installed launchable application entry resolved via [PackageManager.queryIntentActivities].
 */
data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val iconBitmap: ImageBitmap?,
    val isAutomotivePriority: Boolean = false
)

/**
 * Repository that queries all installed `CATEGORY_LAUNCHER` applications on the smartphone
 * and launches any selected application onto the target CarLife `VirtualDisplay` (`displayId`).
 */
object CarLifeLauncherRepository {
    private const val TAG = "CarLifeLauncherRepo"

    private val priorityPackages = listOf(
        "ru.yandex.yandexnavi",
        "ru.yandex.yandexmaps",
        "ru.dublgis.dgismobile",
        "com.google.android.apps.maps",
        "ru.yandex.music",
        "com.spotify.music",
        "com.maxmpz.audioplayer",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube",
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.videolan.vlc"
    )

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _lastLaunchStatus = MutableStateFlow(
        "Выберите приложение для запуска на экране ГУ (VirtualDisplay)"
    )
    val lastLaunchStatus: StateFlow<String> = _lastLaunchStatus.asStateFlow()

    suspend fun loadInstalledApps(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val selfPkg = context.packageName
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        mainIntent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(mainIntent, 0)
                }

                val mapped = resolveInfos
                    .mapNotNull { info ->
                        val activityInfo = info.activityInfo ?: return@mapNotNull null
                        val pkg = activityInfo.packageName ?: return@mapNotNull null
                        if (pkg == selfPkg) return@mapNotNull null
                        val name = activityInfo.name ?: return@mapNotNull null
                        val label = info.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
                        val iconDrawable = runCatching { info.loadIcon(pm) }.getOrNull()
                        val iconBmp = iconDrawable?.let { drawableToImageBitmap(it, 112) }
                        val isPriority = priorityPackages.any { pkg.contains(it, ignoreCase = true) }
                        InstalledAppInfo(
                            label = label,
                            packageName = pkg,
                            activityName = name,
                            iconBitmap = iconBmp,
                            isAutomotivePriority = isPriority
                        )
                    }
                    .distinctBy { "${it.packageName}/${it.activityName}" }
                    .sortedWith(
                        compareByDescending<InstalledAppInfo> { it.isAutomotivePriority }
                            .thenBy { it.label.lowercase(Locale.getDefault()) }
                    )

                _installedApps.value = mapped
            }.onFailure { e ->
                Log.w(TAG, "Failed to query installed LAUNCHER apps: ${e.message}")
            }
        }
    }

    /**
     * Launches [app] on the target CarLife `VirtualDisplay` ([targetDisplayId]) using
     * `ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplayId).toBundle()`.
     */
    fun launchAppOnVirtualDisplay(
        context: Context,
        app: InstalledAppInfo,
        targetDisplayId: Int
    ): Boolean {
        return try {
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.activityName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }

            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && targetDisplayId != Display.DEFAULT_DISPLAY) {
                options.setLaunchDisplayId(targetDisplayId)
            }

            context.startActivity(launchIntent, options.toBundle())
            val displayTag = if (targetDisplayId == Display.DEFAULT_DISPLAY) {
                "Default Display (0)"
            } else {
                "VirtualDisplay #$targetDisplayId"
            }
            _lastLaunchStatus.value = "Запущено: ${app.label} (${app.packageName}) -> $displayTag"
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error launching ${app.packageName} on displayId=$targetDisplayId", t)
            _lastLaunchStatus.value = "Ошибка запуска ${app.label}: ${t.message}"
            false
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable, sizePx: Int): ImageBitmap? {
        return runCatching {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return@runCatching drawable.bitmap.asImageBitmap()
            }
            val w = drawable.intrinsicWidth.coerceAtLeast(sizePx).coerceAtMost(192)
            val h = drawable.intrinsicHeight.coerceAtLeast(sizePx).coerceAtMost(192)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * Full-screen CarLife App Mirroring Launcher rendered onto the `VirtualDisplay` (and previewable on phone).
 *
 * Layout:
 * - **Left Side Dock Bar**: Clock, VirtualDisplay ID badge, «Домой / Все приложения» button,
 *   «Назад» button, and compact Media Player widget (`CockpitMediaNotificationListenerService`).
 * - **Main Content Area**: Filterable grid of all installed smartphone applications (`CATEGORY_LAUNCHER`)
 *   that launch onto `virtualDisplayId` via `ActivityOptions.makeBasic().setLaunchDisplayId(virtualDisplayId)`.
 */
@Composable
fun CarLifeAppLauncherScreen(
    virtualDisplayId: Int,
    mediaState: CockpitMediaState,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onHomePressed: () -> Unit,
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val apps by CarLifeLauncherRepository.installedApps.collectAsState()
    val lastLaunchStatus by CarLifeLauncherRepository.lastLaunchStatus.collectAsState()
    val a11yEnabled by CarLifeTouchAccessibilityService.isServiceEnabled.collectAsState()

    var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Priority/Nav & Media

    LaunchedEffect(Unit) {
        CarLifeLauncherRepository.loadInstalledApps(context)
    }

    val filteredApps = remember(apps, selectedFilter) {
        when (selectedFilter) {
            1 -> apps.filter { it.isAutomotivePriority }.ifEmpty { apps }
            else -> apps
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = ObsidianBackground
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            val isWideScreen = maxWidth >= 560.dp

            if (isWideScreen) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Side Dock Bar (Home, Back, Clock, Compact Media Widget)
                    CarLifeSideDockBar(
                        virtualDisplayId = virtualDisplayId,
                        a11yEnabled = a11yEnabled,
                        mediaState = mediaState,
                        onHomeClick = {
                            selectedFilter = 0
                            onHomePressed()
                        },
                        onBackClick = onBackPressed,
                        onPlayPause = onPlayPause,
                        onNextTrack = onNextTrack,
                        onPreviousTrack = onPreviousTrack,
                        onRequestNotificationAccess = onRequestNotificationAccess,
                        modifier = Modifier
                            .width(248.dp)
                            .fillMaxHeight()
                    )

                    // Right Main Area: Installed Apps Grid
                    InstalledAppsGridPanel(
                        apps = filteredApps,
                        totalAppCount = apps.size,
                        virtualDisplayId = virtualDisplayId,
                        selectedFilter = selectedFilter,
                        lastLaunchStatus = lastLaunchStatus,
                        onSelectFilter = { selectedFilter = it },
                        onRefreshApps = {
                            // Reload on demand
                        },
                        onLaunchApp = { app ->
                            CarLifeLauncherRepository.launchAppOnVirtualDisplay(
                                context = context,
                                app = app,
                                targetDisplayId = virtualDisplayId
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            } else {
                // Compact vertical layout when previewed in portrait mode on smartphone screen
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactTopDockBar(
                        virtualDisplayId = virtualDisplayId,
                        a11yEnabled = a11yEnabled,
                        mediaState = mediaState,
                        onHomeClick = {
                            selectedFilter = 0
                            onHomePressed()
                        },
                        onBackClick = onBackPressed,
                        onPlayPause = onPlayPause,
                        onNextTrack = onNextTrack,
                        onPreviousTrack = onPreviousTrack,
                        onRequestNotificationAccess = onRequestNotificationAccess
                    )

                    InstalledAppsGridPanel(
                        apps = filteredApps,
                        totalAppCount = apps.size,
                        virtualDisplayId = virtualDisplayId,
                        selectedFilter = selectedFilter,
                        lastLaunchStatus = lastLaunchStatus,
                        onSelectFilter = { selectedFilter = it },
                        onRefreshApps = {},
                        onLaunchApp = { app ->
                            CarLifeLauncherRepository.launchAppOnVirtualDisplay(
                                context = context,
                                app = app,
                                targetDisplayId = virtualDisplayId
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CarLifeSideDockBar(
    virtualDisplayId: Int,
    a11yEnabled: Boolean,
    mediaState: CockpitMediaState,
    onHomeClick: () -> Unit,
    onBackClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf("12:00") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            currentTime = fmt.format(Date())
            delay(10_000L)
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Clock + VirtualDisplay status header
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentTime,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                    Surface(
                        color = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                            TelemetryEmerald.copy(alpha = 0.16f)
                        } else {
                            ElectricCyan.copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (virtualDisplayId != Display.DEFAULT_DISPLAY) TelemetryEmerald else ElectricCyan
                        )
                    ) {
                        Text(
                            text = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                "VD #$virtualDisplayId"
                            } else {
                                "PREVIEW"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = if (virtualDisplayId != Display.DEFAULT_DISPLAY) TelemetryEmerald else ElectricCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Touch Accessibility Service indicator pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (a11yEnabled) TelemetryEmerald else AmberSignal)
                    )
                    Text(
                        text = if (a11yEnabled) "Touch Injection: ACTIVE" else "Touch A11y: OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (a11yEnabled) TelemetryEmerald else AmberSignal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Dock Navigation Buttons: Home / All Apps & Back
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DockNavigationButton(
                        label = "Домой",
                        icon = CockpitIcons.Home,
                        accent = ElectricCyan,
                        testTag = "dock_home_button",
                        onClick = onHomeClick,
                        modifier = Modifier.weight(1f)
                    )
                    DockNavigationButton(
                        label = "Назад",
                        icon = CockpitIcons.ArrowBack,
                        accent = CyberBlue,
                        testTag = "dock_back_button",
                        onClick = onBackClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Bottom Compact Media Player Widget (CockpitMediaNotificationListenerService)
            DockCompactMediaPlayer(
                mediaState = mediaState,
                onPlayPause = onPlayPause,
                onNextTrack = onNextTrack,
                onPreviousTrack = onPreviousTrack,
                onRequestNotificationAccess = onRequestNotificationAccess
            )
        }
    }
}

@Composable
private fun CompactTopDockBar(
    virtualDisplayId: Int,
    a11yEnabled: Boolean,
    mediaState: CockpitMediaState,
    onHomeClick: () -> Unit,
    onBackClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockNavigationButton(
                    label = "Домой / Все",
                    icon = CockpitIcons.Home,
                    accent = ElectricCyan,
                    testTag = "dock_home_button",
                    onClick = onHomeClick,
                    modifier = Modifier.weight(1f)
                )
                DockNavigationButton(
                    label = "Назад",
                    icon = CockpitIcons.ArrowBack,
                    accent = CyberBlue,
                    testTag = "dock_back_button",
                    onClick = onBackClick,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = ObsidianSurfaceHigh,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (a11yEnabled) TelemetryEmerald else AmberSignal
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (virtualDisplayId != Display.DEFAULT_DISPLAY) "VD #$virtualDisplayId" else "VD READY",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = ElectricCyan
                        )
                        Text(
                            text = if (a11yEnabled) "A11y ON" else "A11y OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (a11yEnabled) TelemetryEmerald else AmberSignal
                        )
                    }
                }
            }

            DockCompactMediaPlayer(
                mediaState = mediaState,
                onPlayPause = onPlayPause,
                onNextTrack = onNextTrack,
                onPreviousTrack = onPreviousTrack,
                onRequestNotificationAccess = onRequestNotificationAccess
            )
        }
    }
}

@Composable
private fun DockNavigationButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = ObsidianSurfaceHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DockCompactMediaPlayer(
    mediaState: CockpitMediaState,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ObsidianSurfaceHigh,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art Thumbnail
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF162238), Color(0xFF0B111E))
                            )
                        )
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = mediaState.albumArtBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Album Art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = CockpitIcons.Album,
                            contentDescription = "Media",
                            tint = ElectricCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaState.playerAppTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mediaState.trackTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mediaState.artistName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!mediaState.hasNotificationAccess) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRequestNotificationAccess)
                        .testTag("grant_media_notif_btn"),
                    color = AmberSignal.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AmberSignal)
                ) {
                    Text(
                        text = "Дать доступ к медиа-сессиям",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberSignal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { mediaState.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = ElectricCyan,
                trackColor = ObsidianBackground
            )

            // Large touch media transport buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPreviousTrack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ObsidianSurface)
                        .testTag("media_prev_button")
                ) {
                    Icon(
                        imageVector = CockpitIcons.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = TextPrimary
                    )
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(ElectricCyan)
                        .testTag("media_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (mediaState.isPlaying) CockpitIcons.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play or Pause",
                        tint = Color(0xFF002227),
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = onNextTrack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ObsidianSurface)
                        .testTag("media_next_button")
                ) {
                    Icon(
                        imageVector = CockpitIcons.SkipNext,
                        contentDescription = "Next Track",
                        tint = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledAppsGridPanel(
    apps: List<InstalledAppInfo>,
    totalAppCount: Int,
    virtualDisplayId: Int,
    selectedFilter: Int,
    lastLaunchStatus: String,
    onSelectFilter: (Int) -> Unit,
    onRefreshApps: () -> Unit,
    onLaunchApp: (InstalledAppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header bar with filter chips and refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterTabChip(
                        label = "Все приложения ($totalAppCount)",
                        selected = selectedFilter == 0,
                        testTag = "filter_all_apps_chip",
                        onClick = { onSelectFilter(0) }
                    )
                    FilterTabChip(
                        label = "В дорогу / Медиа",
                        selected = selectedFilter == 1,
                        testTag = "filter_auto_apps_chip",
                        onClick = { onSelectFilter(1) }
                    )
                }

                IconButton(
                    onClick = {
                        onRefreshApps()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("refresh_apps_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить список приложений",
                        tint = ElectricCyan
                    )
                }
            }

            // Status bar showing target VirtualDisplay ID and last launched app
            Surface(
                color = ObsidianBackground,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Text(
                    text = lastLaunchStatus,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFamily,
                    color = TelemetryEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            if (apps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = CockpitIcons.AppsGrid,
                            contentDescription = "Loading apps",
                            tint = ElectricCyan,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Загрузка установленных приложений смартфона...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("installed_apps_grid"),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = apps,
                        key = { "${it.packageName}/${it.activityName}" }
                    ) { app ->
                        AppLauncherTile(
                            app = app,
                            onClick = { onLaunchApp(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterTabChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (selected) ElectricCyan.copy(alpha = 0.18f) else ObsidianSurfaceHigh,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) ElectricCyan else ObsidianBorder)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) ElectricCyan else TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun AppLauncherTile(
    app: InstalledAppInfo,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("app_tile_${app.packageName}"),
        color = ObsidianSurfaceHigh,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (app.isAutomotivePriority) ElectricCyan.copy(alpha = 0.45f) else ObsidianBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianSurface),
                contentAlignment = Alignment.Center
            ) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = CockpitIcons.AppsGrid,
                        contentDescription = app.label,
                        tint = ElectricCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = app.label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
