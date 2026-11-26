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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.zIndex
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
 * Right-pane content mode in Standard Cockpit (`Presentation`, `x = 250..1280`).
 */
enum class CockpitRightPane {
    APP_GRID,
    EMBEDDED_MAP
}

/**
 * Reactive controller tracking whether the right pane of Standard Cockpit displays
 * the 4×3 App Grid or the persistent embedded Yandex Maps WebView.
 */
object CockpitUiStateController {
    private val _activeRightPane = MutableStateFlow(CockpitRightPane.APP_GRID)
    val activeRightPane: StateFlow<CockpitRightPane> = _activeRightPane.asStateFlow()

    @Volatile
    private var embeddedMapBackHandler: (() -> Boolean)? = null

    fun registerMapBackHandler(handler: (() -> Boolean)?) {
        embeddedMapBackHandler = handler
    }

    fun showGrid() {
        _activeRightPane.value = CockpitRightPane.APP_GRID
    }

    fun showMap() {
        _activeRightPane.value = CockpitRightPane.EMBEDDED_MAP
    }

    fun handleCockpitBack(): Boolean {
        if (_activeRightPane.value != CockpitRightPane.EMBEDDED_MAP) {
            return false
        }
        val navigatedInsideMap = runCatching { embeddedMapBackHandler?.invoke() == true }.getOrDefault(false)
        if (!navigatedInsideMap) {
            _activeRightPane.value = CockpitRightPane.APP_GRID
        }
        return true
    }

    fun toggleMap() {
        _activeRightPane.value = if (_activeRightPane.value == CockpitRightPane.EMBEDDED_MAP) {
            CockpitRightPane.APP_GRID
        } else {
            CockpitRightPane.EMBEDDED_MAP
        }
    }
}

/**
 * Repository that queries all installed `CATEGORY_LAUNCHER` applications on the smartphone
 * and launches any selected application onto the target CarLife `VirtualDisplay` (`displayId`)
 * or directly onto the default display without `ActivityOptions` on fallback / Screen Mirroring.
 */
object CarLifeLauncherRepository {
    private const val TAG = "CarLifeLauncherRepo"

    val PREFERRED_NAV_PACKAGES = listOf(
        "ru.yandex.yandexnavi",
        "ru.dublgis.dgismobile",
        "ru.yandex.yandexmaps"
    )

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

    /**
     * Resolves the highest-priority installed navigation app (Яндекс Навигатор -> 2ГИС -> Яндекс Карты)
     * and launches / brings it to front on [targetDisplayId] via Shizuku.
     *
     * @return `true` if a native navigation app was found and launched via Shizuku, `false` otherwise.
     */
    fun launchPreferredNavigatorOnShizuku(
        context: Context,
        targetDisplayId: Int
    ): Boolean {
        if (targetDisplayId == Display.DEFAULT_DISPLAY) return false
        val pm = (context.applicationContext ?: context).packageManager
        val cached = _installedApps.value

        for (pkg in PREFERRED_NAV_PACKAGES) {
            val fromCache = cached.firstOrNull { it.packageName == pkg }
            val resolvedActivity = fromCache?.activityName
                ?: runCatching { pm.getLaunchIntentForPackage(pkg)?.component?.className }.getOrNull()
            val isInstalled = fromCache != null ||
                resolvedActivity != null ||
                runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

            if (isInstalled) {
                val launched = CarLifeShizukuManager.launchAppOnDisplay(
                    packageName = pkg,
                    activityName = resolvedActivity.orEmpty(),
                    displayId = targetDisplayId
                )
                if (launched) {
                    val label = fromCache?.label ?: when (pkg) {
                        "ru.yandex.yandexnavi" -> "Яндекс Навигатор"
                        "ru.dublgis.dgismobile" -> "2ГИС"
                        else -> "Яндекс Карты"
                    }
                    _lastLaunchStatus.value = "Shizuku Карта: $label ($pkg) -> VD #$targetDisplayId"
                    onShizukuAppLaunchedOnVirtualDisplay?.invoke(targetDisplayId)
                    return true
                }
            }
        }
        return false
    }

    /**
     * Best-effort launch of Яндекс Навигатор / 2ГИС / Яндекс Карты on the default phone display
     * (used when pressing the steering-wheel NAVI button in `MIRRORING` mode).
     */
    fun launchPreferredNavigatorOnDefaultDisplay(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        val pm = appCtx.packageManager
        for (pkg in PREFERRED_NAV_PACKAGES) {
            val launchIntent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                val started = runCatching {
                    appCtx.startActivity(launchIntent)
                    true
                }.getOrDefault(false)
                if (started) {
                    _lastLaunchStatus.value = "Навигация ($pkg) открыта на экране телефона"
                    return true
                }
            }
        }
        return false
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
 * Unified Volkswagen MIB3 / Digital Cockpit Launcher rendered onto the Head Unit `VirtualDisplay`
 * (and previewable on smartphone). Automatically adapts to Light (`VW Silver Day`) and Dark (`VW MIB3 Night`).
 *
 * Layout:
 * - **Left Panel (`250.dp` == `250 px` at `160 dpi`)**: [VwCockpitLeftPanel] with tabular clock,
 *   `JETTA VS7` badge, Day/Night button, «Домой» / «Назад» / «Карта» keys, and VW Music Player (`⏮ ⏯ ⏭`).
 * - **Right Panel (`x = 250..1280`)**:
 *   - Standard Cockpit: instant switching between `4×3` App Grid and persistent embedded Yandex Maps
 *     WebView (`https://yandex.ru/maps/?l=trf`) WITHOUT destroying/recreating the WebView.
 *   - Shizuku Cockpit: `4×3` App Grid launching Yandex Navigator / 2GIS onto `VirtualDisplay`,
 *     while the identical `250 px` Left Panel stays pinned over the left edge via `ShizukuLeftPanelOverlay`.
 */
@Composable
fun CarLifeAppLauncherScreen(
    virtualDisplayId: Int,
    mediaState: MediaState,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onHomePressed: () -> Unit,
    onBackPressed: () -> Unit,
    isShizukuMode: Boolean = false,
    onMapPressed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by CarLifeLauncherRepository.installedApps.collectAsState()
    val activeRightPane by CockpitUiStateController.activeRightPane.collectAsState()
    val mapController = remember { EmbeddedYandexMapsController() }

    var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Priority/Nav & Media
    var hasEverOpenedEmbeddedMap by remember { mutableStateOf(false) }

    LaunchedEffect(activeRightPane) {
        if (activeRightPane == CockpitRightPane.EMBEDDED_MAP) {
            hasEverOpenedEmbeddedMap = true
        }
    }

    DisposableEffect(mapController) {
        CockpitUiStateController.registerMapBackHandler { mapController.goBack() }
        onDispose {
            CockpitUiStateController.registerMapBackHandler(null)
        }
    }

    LaunchedEffect(Unit) {
        CarLifeLauncherRepository.loadInstalledApps(context)
    }

    val filteredApps = remember(apps, selectedFilter) {
        when (selectedFilter) {
            1 -> apps.filter { it.isAutomotivePriority }.ifEmpty { apps }
            else -> apps
        }
    }

    val isEmbeddedMapVisible = activeRightPane == CockpitRightPane.EMBEDDED_MAP

    val handleHomeAction = {
        selectedFilter = 0
        CockpitUiStateController.showGrid()
        onHomePressed()
    }

    val handleBackAction = {
        if (isEmbeddedMapVisible) {
            if (!mapController.goBack()) {
                CockpitUiStateController.showGrid()
            }
        } else {
            onBackPressed()
        }
    }

    val handleMapAction = {
        if (onMapPressed != null) {
            onMapPressed()
        } else if (isShizukuMode && virtualDisplayId != Display.DEFAULT_DISPLAY) {
            val launchedNative = CarLifeLauncherRepository.launchPreferredNavigatorOnShizuku(
                context = context,
                targetDisplayId = virtualDisplayId
            )
            if (!launchedNative) {
                hasEverOpenedEmbeddedMap = true
                CockpitUiStateController.toggleMap()
            }
        } else {
            hasEverOpenedEmbeddedMap = true
            CockpitUiStateController.toggleMap()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val isWideScreen = maxWidth >= 560.dp
            val leftPanelWidthPx = computeVwLeftPanelWidthPx(maxWidth.value.toInt())

            if (isWideScreen) {
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Permanent VW Left Panel (250 px at 1280×720, 220 px at 1024×800/600, 190 px at 800×480)
                    VwCockpitLeftPanel(
                        virtualDisplayId = virtualDisplayId,
                        mediaState = mediaState,
                        isMapActive = isEmbeddedMapVisible,
                        onHomeClick = handleHomeAction,
                        onBackClick = handleBackAction,
                        onMapClick = handleMapAction,
                        onPlayPause = onPlayPause,
                        onNextTrack = onNextTrack,
                        onPreviousTrack = onPreviousTrack,
                        onRequestNotificationAccess = onRequestNotificationAccess,
                        isFloatingOverlay = false,
                        panelWidthDp = leftPanelWidthPx,
                        modifier = Modifier
                            .padding(start = 0.dp, top = 0.dp, bottom = 0.dp)
                            .width(leftPanelWidthPx.dp)
                            .fillMaxHeight()
                    )

                    // Right Area (x = 250..1280): Persistent WebView + 4×3 App Grid
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)
                    ) {
                        if (hasEverOpenedEmbeddedMap || isEmbeddedMapVisible) {
                            EmbeddedYandexMapsPane(
                                isVisible = isEmbeddedMapVisible,
                                controller = mapController,
                                onCloseToGrid = { CockpitUiStateController.showGrid() },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isEmbeddedMapVisible) 2f else 0f)
                                    .alpha(if (isEmbeddedMapVisible) 1f else 0f)
                            )
                        }

                        if (!isEmbeddedMapVisible) {
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
                                    .fillMaxSize()
                                    .zIndex(1f)
                            )
                        }
                    }
                }
            } else {
                // Compact vertical layout for narrow portrait smartphone preview
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactTopDockBar(
                        virtualDisplayId = virtualDisplayId,
                        mediaState = mediaState,
                        isMapActive = isEmbeddedMapVisible,
                        onHomeClick = handleHomeAction,
                        onBackClick = handleBackAction,
                        onMapClick = handleMapAction,
                        onPlayPause = onPlayPause,
                        onNextTrack = onNextTrack,
                        onPreviousTrack = onPreviousTrack,
                        onRequestNotificationAccess = onRequestNotificationAccess
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (hasEverOpenedEmbeddedMap || isEmbeddedMapVisible) {
                            EmbeddedYandexMapsPane(
                                isVisible = isEmbeddedMapVisible,
                                controller = mapController,
                                onCloseToGrid = { CockpitUiStateController.showGrid() },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isEmbeddedMapVisible) 2f else 0f)
                                    .alpha(if (isEmbeddedMapVisible) 1f else 0f)
                            )
                        }

                        if (!isEmbeddedMapVisible) {
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
                                    .fillMaxSize()
                                    .zIndex(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTopDockBar(
    virtualDisplayId: Int,
    mediaState: MediaState,
    isMapActive: Boolean,
    onHomeClick: () -> Unit,
    onBackClick: () -> Unit,
    onMapClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onRequestNotificationAccess: () -> Unit
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
                            text = if (virtualDisplayId != Display.DEFAULT_DISPLAY) {
                                "JETTA Connect • VD #$virtualDisplayId"
                            } else {
                                "JETTA Connect"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            ),
                            color = colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val nextMode = if (vw.isDark) com.example.ui.theme.ThemeMode.LIGHT else com.example.ui.theme.ThemeMode.DARK
                        com.example.ui.theme.ThemePreferences.setThemeMode(nextMode, context)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colorScheme.surfaceVariant)
                        .border(1.dp, colorScheme.outline, RoundedCornerShape(10.dp))
                        .testTag("compact_theme_toggle_button")
                ) {
                    Icon(
                        imageVector = if (vw.isDark) CockpitIcons.LightMode else CockpitIcons.DarkMode,
                        contentDescription = "Сменить тему День/Ночь",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
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
                VwDockNavKey(
                    label = "Карта",
                    icon = CockpitIcons.Map,
                    active = isMapActive,
                    testTag = "dock_map_button",
                    onClick = onMapClick,
                    modifier = Modifier.weight(1f)
                )
            }

            VwCompactMediaPlayerWidget(
                mediaState = mediaState,
                onPlayPause = onPlayPause,
                onNextTrack = onNextTrack,
                onPreviousTrack = onPreviousTrack,
                onRequestNotificationAccess = onRequestNotificationAccess,
                showGrantAccessCta = !mediaState.hasNotificationAccess
            )
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
            // VW MIB3 Header: «JETTA Connect • Приложения» + filter chips + refresh
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "JETTA Connect • Приложения",
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
