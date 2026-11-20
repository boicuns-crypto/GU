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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.input.CarLifeTouchAccessibilityService
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.SuccessBadgeBg
import com.example.ui.theme.SuccessBadgeFg
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningBadgeBg
import com.example.ui.theme.WarningBadgeFg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Selectable launch strategy for apps clicked inside «CarLife Кокпит».
 */
enum class AppLaunchMode(
    val id: String,
    val title: String,
    val subtitle: String
) {
    VIRTUAL_DISPLAY_IF_AVAILABLE(
        id = "virtual_display",
        title = "На VirtualDisplay (если доступно)",
        subtitle = "Сначала запуск через ActivityOptions(launchDisplayId); при SecurityException — мгновенный запасной запуск без ActivityOptions."
    ),
    SCREEN_MIRRORING(
        id = "screen_mirroring",
        title = "Зеркалирование экрана (MediaProjection)",
        subtitle = "Запуск приложений сразу на основном экране телефона без ActivityOptions для зеркалирования через MediaProjection."
    );

    companion object {
        fun fromId(id: String?): AppLaunchMode =
            entries.firstOrNull { it.id == id } ?: VIRTUAL_DISPLAY_IF_AVAILABLE
    }
}

/**
 * Repository that queries all installed `CATEGORY_LAUNCHER` applications on the smartphone
 * and launches any selected application onto the target CarLife `VirtualDisplay` (`displayId`)
 * or directly onto the default display without `ActivityOptions` on fallback / Screen Mirroring.
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
        "Лаунчер ГУ активен — готов к выводу на VirtualDisplay автомобиля"
    )
    val lastLaunchStatus: StateFlow<String> = _lastLaunchStatus.asStateFlow()

    private val _appLaunchMode = MutableStateFlow(AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE)
    val appLaunchMode: StateFlow<AppLaunchMode> = _appLaunchMode.asStateFlow()

    @Volatile
    var onFallbackToPhoneScreen: (() -> Unit)? = null

    fun setAppLaunchMode(mode: AppLaunchMode) {
        _appLaunchMode.value = mode
        _lastLaunchStatus.value = "Режим запуска: ${mode.title}"
    }

    fun markLauncherActiveOnDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        bitrateBps: Int
    ) {
        val displayLabel = if (displayId == Display.DEFAULT_DISPLAY) {
            "Surface (VD #0)"
        } else {
            "VD #$displayId"
        }
        _lastLaunchStatus.value =
            "Лаунчер ГУ отрисован на $displayLabel (${width}×${height}, ${bitrateBps / 1000} кбит/с)"
    }

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

    private fun showLaunchToast(context: Context, message: String) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
            } else {
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun buildLaunchIntent(context: Context, app: InstalledAppInfo): Intent? {
        val pm = (context.applicationContext ?: context).packageManager
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: if (app.activityName.isNotBlank()) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(app.packageName, app.activityName)
                }
            } else {
                null
            }
        return intent?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    private fun resolveDefaultDisplayContext(context: Context): Context {
        val isNonDefaultDisplayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display?.displayId }.getOrNull().let {
                it != null && it != Display.DEFAULT_DISPLAY
            }
        } else {
            false
        }
        return if (isNonDefaultDisplayContext) {
            context.applicationContext ?: context
        } else {
            context
        }
    }

    private fun startOnDefaultDisplayWithoutOptions(
        context: Context,
        launchIntent: Intent
    ) {
        val primaryFallbackCtx = resolveDefaultDisplayContext(context)
        try {
            primaryFallbackCtx.startActivity(launchIntent)
        } catch (sec: SecurityException) {
            val appCtx = context.applicationContext
            if (appCtx != null && appCtx !== primaryFallbackCtx) {
                appCtx.startActivity(launchIntent)
            } else {
                throw sec
            }
        }
    }

    /**
     * Launches [app] using `packageManager.getLaunchIntentForPackage(pkg)` with
     * `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`.
     *
     * - In [AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE]:
     *   1. First tries `context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplayId).toBundle())`.
     *   2. On `SecurityException` (e.g. Android 16 Permission Denial) or any launch failure, does NOT stop at Toast:
     *      immediately runs fallback `startActivity(intent)` WITHOUT `ActivityOptions` on the default display.
     * - In [AppLaunchMode.SCREEN_MIRRORING]:
     *   Launches directly on the phone's default display without `ActivityOptions` for MediaProjection mirroring.
     */
    fun launchAppOnVirtualDisplay(
        context: Context,
        app: InstalledAppInfo,
        targetDisplayId: Int
    ): Boolean {
        val launchIntent = buildLaunchIntent(context, app)
        if (launchIntent == null) {
            val reason = "Не найден LaunchIntent для ${app.label} (${app.packageName})"
            Log.w(TAG, reason)
            _lastLaunchStatus.value = reason
            showLaunchToast(context, reason)
            return false
        }

        val currentMode = _appLaunchMode.value
        val shouldTryVirtualDisplay =
            currentMode == AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                targetDisplayId != Display.DEFAULT_DISPLAY

        if (shouldTryVirtualDisplay) {
            try {
                val options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(targetDisplayId)
                    .toBundle()
                context.startActivity(launchIntent, options)
                _lastLaunchStatus.value = "Запущено: ${app.label} (${app.packageName}) -> VirtualDisplay #$targetDisplayId"
                return true
            } catch (secErr: SecurityException) {
                val vdReason = secErr.message ?: "Permission Denial"
                Log.w(
                    TAG,
                    "SecurityException launching ${app.packageName} on VD #$targetDisplayId ($vdReason) — executing immediate fallback startActivity(intent) without ActivityOptions",
                    secErr
                )
                return try {
                    onFallbackToPhoneScreen?.invoke()
                    startOnDefaultDisplayWithoutOptions(context, launchIntent)
                    val fallbackMsg = "VD #$targetDisplayId отклонён (Permission Denial): ${app.label} запущено на экране телефона"
                    _lastLaunchStatus.value = fallbackMsg
                    showLaunchToast(context, fallbackMsg)
                    true
                } catch (fallbackError: Throwable) {
                    val errMsg = "Ошибка запасного запуска ${app.label}: ${fallbackError.message ?: fallbackError.javaClass.simpleName}"
                    Log.e(TAG, "Fallback launch failed for ${app.packageName}", fallbackError)
                    _lastLaunchStatus.value = errMsg
                    showLaunchToast(context, errMsg)
                    false
                }
            } catch (vdError: Throwable) {
                val vdReason = vdError.message ?: vdError.javaClass.simpleName
                Log.w(
                    TAG,
                    "VirtualDisplay #$targetDisplayId launch failed for ${app.packageName} ($vdReason) — executing fallback startActivity(intent) without ActivityOptions",
                    vdError
                )
                return try {
                    onFallbackToPhoneScreen?.invoke()
                    startOnDefaultDisplayWithoutOptions(context, launchIntent)
                    val fallbackMsg = "VD #$targetDisplayId недоступен ($vdReason): ${app.label} запущено на экране телефона"
                    _lastLaunchStatus.value = fallbackMsg
                    showLaunchToast(context, fallbackMsg)
                    true
                } catch (fallbackError: Throwable) {
                    val errMsg = "Ошибка запуска ${app.label}: ${fallbackError.message ?: fallbackError.javaClass.simpleName}"
                    Log.e(TAG, "Fallback launch failed for ${app.packageName}", fallbackError)
                    _lastLaunchStatus.value = errMsg
                    showLaunchToast(context, errMsg)
                    false
                }
            }
        }

        return try {
            startOnDefaultDisplayWithoutOptions(context, launchIntent)
            val modeSuffix = if (currentMode == AppLaunchMode.SCREEN_MIRRORING) {
                "Зеркалирование экрана (MediaProjection)"
            } else {
                "Default Display (0)"
            }
            _lastLaunchStatus.value = "Запущено: ${app.label} (${app.packageName}) -> $modeSuffix"
            true
        } catch (t: Throwable) {
            val errMsg = "Ошибка запуска ${app.label}: ${t.message ?: t.javaClass.simpleName}"
            Log.e(TAG, "Error launching ${app.packageName} on Default Display", t)
            _lastLaunchStatus.value = errMsg
            showLaunchToast(context, errMsg)
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
 * - **Left Column (240.dp)**: Clock, «Домой» and «Назад» buttons, and compact Media Player widget
 *   (`padding = 12.dp`) so transport controls fit completely without vertical clipping.
 * - **Right Column**: `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 96.dp))` with `100.dp` tile
 *   height, `48.dp` icon, and `12.sp` single-line app label.
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
    val scope = rememberCoroutineScope()
    val apps by CarLifeLauncherRepository.installedApps.collectAsState()
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
            val isWideScreen = maxWidth >= 460.dp
            val sideDockWidth = if (maxWidth < 640.dp) 196.dp else 240.dp

            if (isWideScreen) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left Column: Clock + Home/Back + Media Player
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
                            .width(sideDockWidth)
                            .fillMaxHeight()
                    )

                    // Right Column: Installed Apps Grid
                    InstalledAppsGridPanel(
                        apps = filteredApps,
                        totalAppCount = apps.size,
                        selectedFilter = selectedFilter,
                        onSelectFilter = { selectedFilter = it },
                        onRefreshApps = {
                            scope.launch {
                                CarLifeLauncherRepository.loadInstalledApps(context)
                            }
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
                // Compact vertical layout for narrow portrait preview
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
                        selectedFilter = selectedFilter,
                        onSelectFilter = { selectedFilter = it },
                        onRefreshApps = {
                            scope.launch {
                                CarLifeLauncherRepository.loadInstalledApps(context)
                            }
                        },
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section: Clock, Status Badge, and Home / Back buttons
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentTime,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextPrimary
                    )
                    Surface(
                        color = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                            SuccessBadgeBg
                        } else {
                            ObsidianSurfaceHigh
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (a11yEnabled) SuccessBadgeFg else WarningBadgeFg)
                            )
                            Text(
                                text = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                    "VD #$virtualDisplayId"
                                } else {
                                    "КОКПИТ"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                    SuccessBadgeFg
                                } else {
                                    ElectricCyan
                                }
                            )
                        }
                    }
                }

                // Dock Navigation Buttons: Home & Back
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
                        accent = TextPrimary,
                        testTag = "dock_back_button",
                        onClick = onBackClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Bottom Compact Media Player Widget
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
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
                    accent = TextPrimary,
                    testTag = "dock_back_button",
                    onClick = onBackClick,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (a11yEnabled) SuccessBadgeBg else WarningBadgeBg,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                            "VD #$virtualDisplayId"
                        } else if (a11yEnabled) {
                            "Touch ON"
                        } else {
                            "Touch OFF"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (a11yEnabled) SuccessBadgeFg else WarningBadgeFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
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
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = ObsidianSurfaceHigh,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
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
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium
                ),
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
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art Thumbnail
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF1E2636), Color(0xFF0B0E14))
                            )
                        )
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp)),
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
                            modifier = Modifier.size(20.dp)
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
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold
                        ),
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
                    color = WarningBadgeBg,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Доступ к уведомлениям плеера",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningBadgeFg,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 5.dp, horizontal = 6.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { mediaState.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = ElectricCyan,
                trackColor = ObsidianBackground
            )

            // Media transport buttons sized so they never clip at the bottom of the dock
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
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ElectricCyan)
                        .testTag("media_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (mediaState.isPlaying) CockpitIcons.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play or Pause",
                        tint = Color(0xFF001D24),
                        modifier = Modifier.size(24.dp)
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
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
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
    selectedFilter: Int,
    onSelectFilter: (Int) -> Unit,
    onRefreshApps: () -> Unit,
    onLaunchApp: (InstalledAppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
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
                        label = "Все ($totalAppCount)",
                        selected = selectedFilter == 0,
                        testTag = "filter_all_apps_chip",
                        onClick = { onSelectFilter(0) }
                    )
                    FilterTabChip(
                        label = "Навигация и Медиа",
                        selected = selectedFilter == 1,
                        testTag = "filter_auto_apps_chip",
                        onClick = { onSelectFilter(1) }
                    )
                }

                IconButton(
                    onClick = onRefreshApps,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("refresh_apps_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить список приложений",
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
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
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Загрузка приложений смартфона...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("installed_apps_grid"),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (selected) ElectricCyan.copy(alpha = 0.18f) else ObsidianSurfaceHigh,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) ElectricCyan else ObsidianBorder)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) ElectricCyan else TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
            .height(100.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("app_tile_${app.packageName}"),
        color = ObsidianSurfaceHigh,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = ObsidianBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (app.iconBitmap != null) {
                Image(
                    bitmap = app.iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = CockpitIcons.AppsGrid,
                        contentDescription = app.label,
                        tint = ElectricCyan,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = app.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 14.sp
                ),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
