package com.example.ui.guide

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.CockpitIcons
import com.example.ui.theme.tabular
import com.example.ui.theme.vwColors
import kotlinx.coroutines.launch

/**
 * Step descriptor for the built-in 6-step VW MIB3 «Инструкция по подключению и настройке».
 */
data class GuideStepSpec(
    val stepNumber: Int,
    val badge: String,
    val title: String,
    val summary: String,
    val highlights: List<String>,
    val drawableName: String
)

object SetupGuideCatalog {
    val steps: List<GuideStepSpec> = listOf(
        GuideStepSpec(
            stepNumber = 1,
            badge = "ШАГ 1 • USB И CARLIFE",
            title = "Подключение к Jetta VS7",
            summary = "Подключите смартфон качественным USB-кабелем к разъёму мультимедиа, откройте на ГУ раздел Baidu CarLife и выберите «Всегда открывать JETTA Connect».",
            highlights = listOf(
                "Перед втыканием кабеля выдайте доступ «Весь экран» во вкладке «Подключение».",
                "При первом запросе USB отметьте «Всегда открывать JETTA Connect» и нажмите «ОК»."
            ),
            drawableName = "guide_step_1"
        ),
        GuideStepSpec(
            stepNumber = 2,
            badge = "ШАГ 2 • ANDROID 14–16",
            title = "Базовые разрешения и «Ограниченные настройки»",
            summary = "На Android 14–16 система блокирует Спец. возможности и доступ к уведомлениям для APK, установленных вручную. Разблокируйте их через меню ⋮ в свойствах приложения.",
            highlights = listOf(
                "Если появится окно «Ограниченный доступ», нажмите «ОК» и откройте «О приложении».",
                "В правом верхнем углу нажмите ⋮ (три точки) → «Разрешить ограниченные настройки»."
            ),
            drawableName = "guide_step_2"
        ),
        GuideStepSpec(
            stepNumber = 3,
            badge = "ШАГ 3 • ТАЧ И МЕДИА",
            title = "Управление экраном и Плеер",
            summary = "Включите службу «JETTA Connect (Управление экраном)» в Специальных возможностях для касаний с ГУ и выдайте доступ к уведомлениям для виджета музыки.",
            highlights = listOf(
                "Специальные возможности → Установленные приложения → «JETTA Connect (Управление экраном)».",
                "Доступ к уведомлениям → включите «JETTA Connect» для треков и кнопок ⏮ ⏯ ⏭."
            ),
            drawableName = "guide_step_3"
        ),
        GuideStepSpec(
            stepNumber = 4,
            badge = "ШАГ 4 • РЕЖИМЫ ЭКРАНА",
            title = "Выбор режима работы",
            summary = "Выберите удобный сценарий работы под вашу поездку: встроенный Кокпит с картой, прямое Зеркалирование смартфона или Второй экран через Shizuku.",
            highlights = listOf(
                "Кокпит: левая панель VW (250 px) + сетка приложений и встроенная Яндекс Карта.",
                "Зеркалирование: полный экран смартфона на ГУ (с опцией 16:9 через Shizuku).",
                "Кокпит + Shizuku: Навигатор или 2ГИС на втором виртуальном экране с панелью VW."
            ),
            drawableName = "guide_step_4"
        ),
        GuideStepSpec(
            stepNumber = 5,
            badge = "ШАГ 5 • SAMSUNG ONE UI",
            title = "Убрать чёрную полосу у камеры",
            summary = "Чтобы Навигатор и 2ГИС не оставляли чёрную полосу возле фронтальной камеры при зеркалировании, включите показ выреза камеры в настройках дисплея Samsung One UI.",
            highlights = listOf(
                "Настройки → Дисплей → Вырез камеры → выберите «Показывать вырез камеры» для Навигатора, 2ГИС и JETTA Connect.",
                "В Настройках приложения также включите «Заполнять область выреза камеры при подключении USB»."
            ),
            drawableName = "guide_step_5"
        ),
        GuideStepSpec(
            stepNumber = 6,
            badge = "ШАГ 6 • SHIZUKU 16:9 И VD",
            title = "Настройка Shizuku (второй экран и 16:9)",
            summary = "Shizuku не требует root-прав и запускается за 1 минуту по «Отладке по Wi-Fi». Это открывает режим «Кокпит + Shizuku» и зеркалирование 16:9 без чёрных рамок.",
            highlights = listOf(
                "Включите «Для разработчиков» → «Отладка по Wi-Fi» и нажмите «Подключение по коду» в Shizuku.",
                "После запуска службы вернитесь в JETTA Connect и выдайте разрешение Shizuku во вкладке «Подключение»."
            ),
            drawableName = "guide_step_6"
        )
    )

    /**
     * Resolves optional user-provided screenshot drawable (`guide_step_1` .. `guide_step_6`) in `res/drawable/`.
     * Returns `0` when absent so the built-in vector/Compose preview mockup is rendered automatically.
     */
    fun resolveOptionalGuideDrawableId(context: Context, stepNumber: Int): Int {
        if (stepNumber !in 1..steps.size) return 0
        return runCatching {
            context.resources.getIdentifier(
                "guide_step_$stepNumber",
                "drawable",
                context.packageName
            )
        }.getOrDefault(0)
    }
}

/**
 * Full-screen modal VW MIB3 setup & onboarding guide with 6 visual preview steps in a [HorizontalPager].
 * Supports both Light (`VW Silver Day`) and Dark (`VW MIB3 Night`) themes and optional PNG overrides
 * (`res/drawable/guide_step_1.png` .. `guide_step_6.png`).
 */
@Composable
fun SetupGuideScreen(
    onDismiss: () -> Unit,
    onOpenConnectionTab: () -> Unit,
    onOpenSettingsTab: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    initialPage: Int = 0
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)

        val context = LocalContext.current
        val colorScheme = MaterialTheme.colorScheme
        val vw = MaterialTheme.vwColors
        val scope = rememberCoroutineScope()
        val steps = SetupGuideCatalog.steps
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, steps.lastIndex),
            pageCount = { steps.size }
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("setup_guide_screen"),
            color = colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Bar inside Guide
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colorScheme.surface
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = CockpitIcons.MenuBook,
                                        contentDescription = "Инструкция JETTA Connect",
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Инструкция JETTA Connect",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Шаг ${pagerState.currentPage + 1} из ${steps.size}",
                                        style = MaterialTheme.typography.labelSmall.tabular(),
                                        color = vw.accentBlue,
                                        modifier = Modifier.testTag("guide_step_counter")
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .testTag("guide_close_btn")
                            ) {
                                Icon(
                                    imageVector = CockpitIcons.Close,
                                    contentDescription = "Закрыть инструкцию",
                                    tint = colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { (pagerState.currentPage + 1) / steps.size.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = vw.accentBlue,
                            trackColor = colorScheme.surfaceVariant
                        )
                    }
                }

                // HorizontalPager with 6 Visual Steps
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .testTag("guide_horizontal_pager")
                ) { pageIndex ->
                    val spec = steps[pageIndex]
                    val optionalDrawableId = remember(spec.stepNumber) {
                        SetupGuideCatalog.resolveOptionalGuideDrawableId(context, spec.stepNumber)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Visual Preview Mockup (or optional PNG screenshot guide_step_N.png)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("guide_step_preview_${spec.stepNumber}"),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = BorderStroke(1.dp, colorScheme.outline)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 215.dp, max = 260.dp)
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (optionalDrawableId != 0) {
                                    Image(
                                        painter = painterResource(id = optionalDrawableId),
                                        contentDescription = spec.title,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    GuideStepVisualMockup(
                                        stepNumber = spec.stepNumber,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        // Explanation Card + Action Buttons
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
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = spec.badge,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    ),
                                    color = vw.accentBlue
                                )
                                Text(
                                    text = spec.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = spec.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    spec.highlights.forEach { bullet ->
                                        Surface(
                                            color = colorScheme.background,
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, colorScheme.outline)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 6.dp)
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(vw.accentBlue)
                                                )
                                                Text(
                                                    text = bullet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }

                                GuideStepActionButtons(
                                    stepNumber = spec.stepNumber,
                                    onOpenConnectionTab = {
                                        onDismiss()
                                        onOpenConnectionTab()
                                    },
                                    onOpenSettingsTab = {
                                        onDismiss()
                                        onOpenSettingsTab()
                                    },
                                    onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                    onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                                    onOpenDisplaySettings = onOpenDisplaySettings,
                                    onOpenShizuku = onOpenShizuku,
                                    onOpenDeveloperSettings = onOpenDeveloperSettings
                                )
                            }
                        }
                    }
                }

                // Bottom Pager Navigation Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colorScheme.surface
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colorScheme.outline)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (pagerState.currentPage > 0) {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    }
                                },
                                enabled = pagerState.currentPage > 0,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("guide_prev_btn"),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, colorScheme.outline)
                            ) {
                                Text("Назад")
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(steps.size) { idx ->
                                    val selected = pagerState.currentPage == idx
                                    val dotColor by animateColorAsState(
                                        targetValue = if (selected) vw.accentBlue else colorScheme.outline,
                                        animationSpec = tween(durationMillis = 180),
                                        label = "guide_dot_color"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .height(8.dp)
                                            .width(if (selected) 22.dp else 8.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                            .clickable {
                                                scope.launch { pagerState.animateScrollToPage(idx) }
                                            }
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (pagerState.currentPage < steps.lastIndex) {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    } else {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("guide_next_btn"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colorScheme.primary,
                                    contentColor = colorScheme.onPrimary
                                )
                            ) {
                                Text(
                                    text = if (pagerState.currentPage < steps.lastIndex) "Далее" else "Готово",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideStepActionButtons(
    stepNumber: Int,
    onOpenConnectionTab: () -> Unit,
    onOpenSettingsTab: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenDeveloperSettings: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    when (stepNumber) {
        1 -> {
            Button(
                onClick = onOpenConnectionTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("guide_action_step1_btn"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = CockpitIcons.Usb,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Перейти во вкладку «Подключение»", fontWeight = FontWeight.SemiBold)
            }
        }

        2 -> {
            Button(
                onClick = onOpenAppDetailsSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("guide_action_step2_btn"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = CockpitIcons.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Открыть свойства приложения", fontWeight = FontWeight.SemiBold)
            }
        }

        3 -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_action_step3_a11y_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = CockpitIcons.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Специальные возможности", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = onOpenNotificationListenerSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_action_step3_media_btn"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.primaryContainer,
                        contentColor = colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = CockpitIcons.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Доступ к уведомлениям (Плеер)", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        4 -> {
            Button(
                onClick = onOpenSettingsTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("guide_action_step4_btn"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = CockpitIcons.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Выбрать режим в Настройках", fontWeight = FontWeight.SemiBold)
            }
        }

        5 -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenDisplaySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_action_step5_display_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = CockpitIcons.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Открыть настройки дисплея", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = onOpenSettingsTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_action_step5_settings_btn"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.primaryContainer,
                        contentColor = colorScheme.onPrimaryContainer
                    )
                ) {
                    Text("Перейти в «Настройки» JETTA Connect", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        6 -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenShizuku,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_action_step6_shizuku_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = CockpitIcons.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Открыть Shizuku", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = onOpenDeveloperSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_action_step6_dev_btn"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.primaryContainer,
                        contentColor = colorScheme.onPrimaryContainer
                    )
                ) {
                    Text("Для разработчиков (Отладка по Wi-Fi)", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun GuideStepVisualMockup(
    stepNumber: Int,
    modifier: Modifier = Modifier
) {
    when (stepNumber) {
        1 -> Step1UsbConnectionMockup(modifier)
        2 -> Step2RestrictedSettingsMockup(modifier)
        3 -> Step3AccessibilityAndMediaMockup(modifier)
        4 -> Step4ModesComparisonMockup(modifier)
        5 -> Step5CameraCutoutMockup(modifier)
        else -> Step6ShizukuWirelessMockup(modifier)
    }
}

/**
 * Step 1 Mockup: Smartphone <-> USB Cable <-> Jetta VS7 Head Unit (Baidu CarLife) + "Always open JETTA Connect".
 */
@Composable
private fun Step1UsbConnectionMockup(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.background)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Diagram row: Phone <-> USB <-> Head Unit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Smartphone mini frame
            Surface(
                modifier = Modifier
                    .width(74.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = colorScheme.surface,
                border = BorderStroke(1.5.dp, vw.accentBlue)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    )
                    Text(
                        text = "JETTA\nConnect",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 11.sp
                        ),
                        textAlign = TextAlign.Center,
                        color = vw.accentBlue
                    )
                    Text(
                        text = "Смартфон",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            // USB Cable arrow canvas
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = vw.infoContainer,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, vw.accentBlue.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = CockpitIcons.Usb,
                            contentDescription = null,
                            tint = vw.accentBlue,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "USB-кабель",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = vw.onInfoContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                ) {
                    val y = size.height / 2f
                    drawLine(
                        color = vw.accentBlue,
                        start = Offset(4f, y),
                        end = Offset(size.width - 4f, y),
                        strokeWidth = 3.5f,
                        cap = StrokeCap.Round
                    )
                    // Left arrow head
                    drawLine(vw.accentBlue, Offset(4f, y), Offset(14f, y - 5f), strokeWidth = 3f, cap = StrokeCap.Round)
                    drawLine(vw.accentBlue, Offset(4f, y), Offset(14f, y + 5f), strokeWidth = 3f, cap = StrokeCap.Round)
                    // Right arrow head
                    drawLine(vw.accentBlue, Offset(size.width - 4f, y), Offset(size.width - 14f, y - 5f), strokeWidth = 3f, cap = StrokeCap.Round)
                    drawLine(vw.accentBlue, Offset(size.width - 4f, y), Offset(size.width - 14f, y + 5f), strokeWidth = 3f, cap = StrokeCap.Round)
                }
            }

            // Head Unit mini frame
            Surface(
                modifier = Modifier
                    .width(104.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = colorScheme.surface,
                border = BorderStroke(1.5.dp, colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(7.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ГУ Jetta VS7",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = colorScheme.onSurface
                    )
                    Surface(
                        color = vw.accentBlue.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, vw.accentBlue)
                    ) {
                        Text(
                            text = "Baidu CarLife",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = vw.accentBlue,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = "1280×720 AOA",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // USB Permission Dialog Preview
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, vw.accentBlue.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "Открыть «JETTA Connect» для USB-аксессуара?",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = vw.statusActiveDot,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Всегда использовать по умолчанию",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = vw.onSuccessContainer
                        )
                    }
                    Surface(
                        color = vw.accentBlue,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "ОК",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 2 Mockup: Android 14-16 "Restricted Setting" warning + App Info ⋮ -> "Allow restricted settings".
 */
@Composable
private fun Step2RestrictedSettingsMockup(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.background)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Simulated App Info Top Bar with ⋮ popup
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outline)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "О приложении • JETTA Connect",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colorScheme.onSurface
                        )
                        // Highlighted 3 dots button
                        Surface(
                            shape = CircleShape,
                            color = vw.accentBlue.copy(alpha = 0.18f),
                            border = BorderStroke(1.5.dp, vw.accentBlue)
                        ) {
                            Text(
                                text = "⋮",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = vw.accentBlue,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Popup menu simulation under ⋮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Curved/straight arrow pointing to menu
                        Canvas(
                            modifier = Modifier
                                .width(34.dp)
                                .height(22.dp)
                        ) {
                            drawLine(
                                color = vw.accentBlue,
                                start = Offset(4f, size.height * 0.5f),
                                end = Offset(size.width - 4f, size.height * 0.5f),
                                strokeWidth = 3.5f,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = vw.accentBlue,
                                start = Offset(size.width - 4f, size.height * 0.5f),
                                end = Offset(size.width - 13f, size.height * 0.5f - 6f),
                                strokeWidth = 3.5f,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = vw.accentBlue,
                                start = Offset(size.width - 4f, size.height * 0.5f),
                                end = Offset(size.width - 13f, size.height * 0.5f + 6f),
                                strokeWidth = 3.5f,
                                cap = StrokeCap.Round
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.5.dp, vw.accentBlue)
                        ) {
                            Text(
                                text = "Разрешить ограниченные настройки",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }

        // System Alert Mockup: Ограниченный доступ
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = vw.warningContainer,
            border = BorderStroke(1.dp, vw.statusWarningDot.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Если видите: «Ограниченный доступ»",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = vw.onWarningContainer
                )
                Text(
                    text = "Нажмите «Открыть свойства приложения» → ⋮ в углу → «Разрешить ограниченные настройки».",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = vw.onWarningContainer
                )
            }
        }
    }
}

/**
 * Step 3 Mockup: Accessibility Services list ("JETTA Connect (Управление экраном)") + Notification Listener.
 */
@Composable
private fun Step3AccessibilityAndMediaMockup(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.background)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Accessibility Settings List Mockup
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Специальные возможности → Установленные",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
                Surface(
                    color = vw.infoContainer,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.5.dp, vw.accentBlue)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "JETTA Connect (Управление экраном)",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = vw.onInfoContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Вкл • Касания и свайпы с ГУ",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = vw.onSuccessContainer
                            )
                        }
                        MockSwitchPill(checked = true)
                    }
                }
            }
        }

        // Notification Listener Mockup
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Доступ к уведомлениям (Медиаплеер)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
                Surface(
                    color = vw.successContainer,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, vw.statusActiveDot.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "JETTA Connect",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = vw.onSuccessContainer
                            )
                            Text(
                                text = "Трек, обложка и кнопки ⏮ ⏯ ⏭",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = vw.onSuccessContainer
                            )
                        }
                        MockSwitchPill(checked = true)
                    }
                }
            }
        }
    }
}

/**
 * Step 4 Mockup: Visual comparison of the 3 Head Unit screen modes (Cockpit / Mirroring / Cockpit + Shizuku).
 */
@Composable
private fun Step4ModesComparisonMockup(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.background)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Cockpit
        ModeMiniPreviewCard(
            title = "Кокпит",
            subtitle = "Панель VW +\nсетка и карта",
            accent = vw.accentBlue,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Left dock + 3x2 grid
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(vw.accentBlue.copy(alpha = 0.25f))
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(2) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colorScheme.surfaceVariant)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Mirroring
        ModeMiniPreviewCard(
            title = "Зеркало",
            subtitle = "Весь экран\nтелефона",
            accent = vw.statusWaitingDot,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(5.dp))
                    .background(vw.accentBlue.copy(alpha = 0.14f))
                    .border(1.dp, vw.accentBlue.copy(alpha = 0.5f), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "16:9",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = vw.accentBlue
                )
            }
        }

        // 3. Cockpit + Shizuku
        ModeMiniPreviewCard(
            title = "Shizuku",
            subtitle = "Навигатор на\n2-м экране",
            accent = vw.statusActiveDot,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(vw.accentBlue.copy(alpha = 0.35f))
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(vw.successContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = CockpitIcons.Navigation,
                        contentDescription = null,
                        tint = vw.statusActiveDot,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeMiniPreviewCard(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    previewContent: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(vertical = 4.dp)
            ) {
                previewContent()
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Step 5 Mockup: Samsung One UI Settings -> Display -> Camera cutout -> "Show camera cutout".
 */
@Composable
private fun Step5CameraCutoutMockup(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp)),
        color = colorScheme.background,
        border = BorderStroke(1.dp, colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Настройки → Дисплей → Вырез камеры",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colorScheme.onSurface.copy(alpha = 0.75f))
                )
            }

            // Highlighted app rows
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CutoutMockRow(
                    appName = "Яндекс Навигатор",
                    optionLabel = "Показывать вырез камеры",
                    accent = vw.accentBlue
                )
                CutoutMockRow(
                    appName = "2ГИС",
                    optionLabel = "Показывать вырез камеры",
                    accent = vw.accentBlue
                )
            }

            // In-app toggle confirmation strip
            Surface(
                color = vw.successContainer,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, vw.statusActiveDot.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Заполнять вырез при подключении USB",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = vw.onSuccessContainer,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MockSwitchPill(checked = true)
                }
            }
        }
    }
}

@Composable
private fun CutoutMockRow(
    appName: String,
    optionLabel: String,
    accent: Color
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface
            )
            Surface(
                color = accent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = optionLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = accent,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/**
 * Step 6 Mockup: Shizuku Wireless Debugging setup flow (Wi-Fi Debugging -> Pairing code -> Running).
 */
@Composable
private fun Step6ShizukuWirelessMockup(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val vw = MaterialTheme.vwColors

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.background)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShizukuFlowTile(
                step = "1",
                title = "Отладка по Wi-Fi",
                detail = "Для разработчиков",
                accent = vw.accentBlue,
                modifier = Modifier.weight(1f)
            )
            Text("→", color = vw.accentBlue, fontWeight = FontWeight.Bold)
            ShizukuFlowTile(
                step = "2",
                title = "Ввод кода",
                detail = "6 цифр в Shizuku",
                accent = vw.accentBlue,
                modifier = Modifier.weight(1f)
            )
            Text("→", color = vw.statusActiveDot, fontWeight = FontWeight.Bold)
            ShizukuFlowTile(
                step = "3",
                title = "Запуск",
                detail = "Служба активна",
                accent = vw.statusActiveDot,
                modifier = Modifier.weight(1f)
            )
        }

        // Result card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = vw.successContainer,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, vw.statusActiveDot.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = vw.statusActiveDot,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Shizuku работает",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = vw.onSuccessContainer
                        )
                        Text(
                            text = "Доступны Навигатор/2ГИС на 2-м экране и 16:9",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = vw.onSuccessContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuFlowTile(
    step: String,
    title: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MockSwitchPill(checked: Boolean) {
    val vw = MaterialTheme.vwColors
    val trackColor = if (checked) vw.accentBlue else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
