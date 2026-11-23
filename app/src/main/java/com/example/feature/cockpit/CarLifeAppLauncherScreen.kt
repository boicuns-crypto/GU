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
import com.example.core.input.CarLifeAccessibilityService
import com.example.core.shizuku.CarLifeShizukuManager
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.tabular
import com.example.ui.theme.vwColors
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
    ),
    SHIZUKU_VIRTUAL_DISPLAY(
        id = "shizuku_virtual_display",
        title = "Через Shizuku на VirtualDisplay",
        subtitle = "Запуск через Shizuku UserService (am start --display <id>) на второй экран в обход ограничений Android 16."
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

    @Volatile
    var onShizukuAppLaunchedOnVirtualDisplay: ((Int) -> Unit)? = null

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
        if (currentMode == AppLaunchMode.SHIZUKU_VIRTUAL_DISPLAY) {
            if (targetDisplayId == Display.DEFAULT_DISPLAY) {
                val msg = "VirtualDisplay #0 не готов для Shizuku — разрешите MediaProjection («Весь экран»)"
                Log.w(TAG, msg)
                _lastLaunchStatus.value = msg
                showLaunchToast(context, msg)
                return false
            }
            val launched = CarLifeShizukuManager.launchAppOnDisplay(
                packageName = app.packageName,
                activityName = app.activityName,
                displayId = targetDisplayId
            )
            if (launched) {
                _lastLaunchStatus.value =
                    "Shizuku: ${app.label} (${app.packageName}) -> VirtualDisplay #$targetDisplayId"
                onShizukuAppLaunchedOnVirtualDisplay?.invoke(targetDisplayId)
                return true
            } else {
                val shizukuLabel = CarLifeShizukuManager.status.value.statusLabel
                val errMsg = "Shizuku ($shizukuLabel): не удалось запустить ${app.label} на VD #$targetDisplayId"
                Log.w(TAG, errMsg)
                _lastLaunchStatus.value = errMsg
                showLaunchToast(context, errMsg)
                return false
            }
        }

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
 * Full-screen Volkswagen MIB3 / Digital Cockpit Launcher rendered onto the Head Unit `VirtualDisplay`
 * (and previewable on smartphone). Automatically adapts to Light (`VW Silver Day`) and Dark (`VW MIB3 Night`).
 *
 * Layout:
 * - **Left Panel**: Large `36.sp` SemiBold tabular clock, compact `JETTA VS7` badge + `LIVE` dot,
 *   strict rectangular «Домой» / «Назад» buttons (`1.dp` border), and compact current track widget
 *   with a `primary` VW accent stripe.
 * - **Right Panel**: Header `Jetta VS7 • Приложения` underlined by a thin VW Blue accent line,
 *   followed by a clean `4×3` grid of uniform `14.dp` app tiles with icons and clear unclipped labels.
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
    val a11yEnabled by CarLifeAccessibilityService.isServiceEnabled.collectAsState()

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
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val isWideScreen = maxWidth >= 460.dp
            val sideDockWidth = if (maxWidth < 640.dp) 210.dp else 256.dp

            if (isWideScreen) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Panel: VW Digital Cockpit Clock + JETTA VS7/LIVE + Home/Back + Media Widget
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

                    // Right Panel: Jetta VS7 • Приложения + 4x3 App Grid
                    InstalledAppsGridPanel(
                        apps = filteredApps,
                        totalAppCount = apps.size,
                        selectedFilter = selectedFilter,
                        isWideScreen = true,
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
                        isWideScreen = false,
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

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section: Large 36.sp Clock, JETTA VS7 + LIVE badge, and strict Home / Back keys
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Brand badge + LIVE indicator row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        Text(
                            text = "JETTA VS7",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.9.sp
                            ),
                            color = colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    val isLive = virtualDisplayId != Display.DEFAULT_DISPLAY || a11yEnabled
                    Surface(
                        color = if (isLive) vw.successContainer else vw.infoContainer,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isLive) vw.statusActiveDot.copy(alpha = 0.35f) else vw.statusWaitingDot.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isLive) vw.statusActiveDot else vw.statusWaitingDot)
                            )
                            Text(
                                text = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                    "LIVE • #$virtualDisplayId"
                                } else {
                                    "LIVE"
                                },
                                style = MaterialTheme.typography.labelSmall.tabular().copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = if (isLive) vw.onSuccessContainer else vw.onInfoContainer
                            )
                        }
                    }
                }

                // Crisp 36.sp tabular digital clock
                Text(
                    text = currentTime,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = colorScheme.onSurface
                )

                // Thin VW Blue accent divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    vw.accentBlue,
                                    vw.accentBlue.copy(alpha = 0.12f)
                                )
                            )
                        )
                )

                // Strict rectangular «Домой» & «Назад» buttons with 1.dp border
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DockNavigationButton(
                        label = "Домой",
                        icon = CockpitIcons.Home,
                        accent = colorScheme.primary,
                        testTag = "dock_home_button",
                        onClick = onHomeClick,
                        modifier = Modifier.weight(1f)
                    )
                    DockNavigationButton(
                        label = "Назад",
                        icon = CockpitIcons.ArrowBack,
                        accent = colorScheme.onSurface,
                        testTag = "dock_back_button",
                        onClick = onBackClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Bottom-left Compact Media Player Widget with primary accent stripe
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentTime,
                        style = MaterialTheme.typography.headlineMedium.tabular().copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = colorScheme.onSurface
                    )
                    Surface(
                        color = colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, colorScheme.outline)
                    ) {
                        Text(
                            text = "JETTA VS7",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            ),
                            color = colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                val isLive = virtualDisplayId != Display.DEFAULT_DISPLAY || a11yEnabled
                Surface(
                    color = if (isLive) vw.successContainer else vw.infoContainer,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        if (isLive) vw.statusActiveDot.copy(alpha = 0.35f) else vw.statusWaitingDot.copy(alpha = 0.35f)
                    )
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
                                .background(if (isLive) vw.statusActiveDot else vw.statusWaitingDot)
                        )
                        Text(
                            text = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                "VD #$virtualDisplayId"
                            } else if (a11yEnabled) {
                                "LIVE"
                            } else {
                                "ОЖИДАНИЕ"
                            },
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = if (isLive) vw.onSuccessContainer else vw.onInfoContainer
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockNavigationButton(
                    label = "Домой",
                    icon = CockpitIcons.Home,
                    accent = colorScheme.primary,
                    testTag = "dock_home_button",
                    onClick = onHomeClick,
                    modifier = Modifier.weight(1f)
                )
                DockNavigationButton(
                    label = "Назад",
                    icon = CockpitIcons.ArrowBack,
                    accent = colorScheme.onSurface,
                    testTag = "dock_back_button",
                    onClick = onBackClick,
                    modifier = Modifier.weight(1f)
                )
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
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
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
                color = colorScheme.onSurface,
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
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Signature VW MIB3 primary accent stripe at top of track widget
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(colorScheme.primary)
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
                    // Album Art Thumbnail
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = mediaState.albumArtBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Обложка трека",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = CockpitIcons.Album,
                                contentDescription = "Медиаплеер",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(20.dp)
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
                            text = mediaState.trackTitle,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = mediaState.artistName,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
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
                        color = vw.warningContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Доступ к уведомлениям плеера",
                            style = MaterialTheme.typography.labelSmall,
                            color = vw.onWarningContainer,
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
                    color = colorScheme.primary,
                    trackColor = colorScheme.outline
                )

                // Media transport buttons (>= 48.dp touch targets)
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
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outline, CircleShape)
                            .testTag("media_prev_button")
                    ) {
                        Icon(
                            imageVector = CockpitIcons.SkipPrevious,
                            contentDescription = "Предыдущий трек",
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary)
                            .testTag("media_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) CockpitIcons.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Воспроизвести или пауза",
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = onNextTrack,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outline, CircleShape)
                            .testTag("media_next_button")
                    ) {
                        Icon(
                            imageVector = CockpitIcons.SkipNext,
                            contentDescription = "Следующий трек",
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
    isWideScreen: Boolean,
    onSelectFilter: (Int) -> Unit,
    onRefreshApps: () -> Unit,
    onLaunchApp: (InstalledAppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // VW MIB3 Header: «Jetta VS7 • Приложения» + filter chips + refresh
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Jetta VS7 • Приложения",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterTabChip(
                            label = "Все ($totalAppCount)",
                            selected = selectedFilter == 0,
                            testTag = "filter_all_apps_chip",
                            onClick = { onSelectFilter(0) }
                        )
                        FilterTabChip(
                            label = "Авто",
                            selected = selectedFilter == 1,
                            testTag = "filter_auto_apps_chip",
                            onClick = { onSelectFilter(1) }
                        )
                        IconButton(
                            onClick = onRefreshApps,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("refresh_apps_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Обновить список приложений",
                                tint = vw.accentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Signature thin VW Blue accent line below header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    vw.accentBlue,
                                    vw.accentBlue.copy(alpha = 0.45f),
                                    colorScheme.outline.copy(alpha = 0.25f)
                                )
                            )
                        )
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
                            contentDescription = "Загрузка приложений",
                            tint = vw.accentBlue,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Загрузка приложений смартфона...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Clean 4x3 grid on Head Unit landscape (3 columns on narrow portrait preview)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isWideScreen) 4 else 3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("installed_apps_grid"),
                    contentPadding = PaddingValues(2.dp),
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
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) colorScheme.primary else colorScheme.outline)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.tabular().copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun AppLauncherTile(
    app: InstalledAppInfo,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("app_tile_${app.packageName}"),
        color = colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (app.iconBitmap != null) {
                Image(
                    bitmap = app.iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = CockpitIcons.AppsGrid,
                        contentDescription = app.label,
                        tint = vw.accentBlue,
                        modifier = Modifier.size(24.dp)
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
                color = colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
