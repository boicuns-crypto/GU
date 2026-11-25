package com.example.core.config

import com.example.core.projection.AppMode

/**
 * Identifiers for the 8 device permissions & system privileges managed in the «Подключение» tab.
 */
enum class AppPermissionKey {
    RECORD_AUDIO,
    POST_NOTIFICATIONS,
    FINE_LOCATION,
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    OVERLAY,
    BATTERY_OPTIMIZATION,
    SHIZUKU
}

/**
 * How a permission or MediaProjection step applies to a specific [AppMode].
 */
enum class RequirementRelevance {
    REQUIRED,
    OPTIONAL_FEATURE,
    NOT_NEEDED
}

data class PermissionRequirementSpec(
    val key: AppPermissionKey,
    val title: String,
    val reason: String,
    val relevance: RequirementRelevance,
    val roleBadge: String
)

data class ModeConnectionGuideSpec(
    val mode: AppMode,
    val headline: String,
    val summary: String,
    val mandatoryChecklistNote: String,
    val mediaProjectionRelevance: RequirementRelevance,
    val mediaProjectionBadge: String,
    val mediaProjectionExplanation: String,
    val permissions: List<PermissionRequirementSpec>
) {
    val activePermissions: List<PermissionRequirementSpec>
        get() = permissions.filter { it.relevance != RequirementRelevance.NOT_NEEDED }

    val notNeededPermissions: List<PermissionRequirementSpec>
        get() = permissions.filter { it.relevance == RequirementRelevance.NOT_NEEDED }
}

data class ModeReadinessSummary(
    val mode: AppMode,
    val grantedRequiredCount: Int,
    val totalRequiredCount: Int,
    val missingRequiredLabels: List<String>,
    val mediaProjectionRequired: Boolean,
    val mediaProjectionGranted: Boolean
) {
    val allMandatoryReady: Boolean
        get() = grantedRequiredCount == totalRequiredCount &&
            (!mediaProjectionRequired || mediaProjectionGranted)
}

/**
 * Single source of truth separating startup prerequisites («Подключение») and
 * runtime settings («Настройки») by connection type ([AppMode]).
 */
object ConnectionModeRequirements {

    fun specFor(mode: AppMode): ModeConnectionGuideSpec = when (mode) {
        AppMode.COCKPIT -> ModeConnectionGuideSpec(
            mode = AppMode.COCKPIT,
            headline = "Режим «Кокпит» (панель VW + встроенная карта)",
            summary = "Выводит на экран ГУ левую панель VW с часами/плеером, сетку приложений и встроенные Яндекс Карты.",
            mandatoryChecklistNote = "Спец. возможности и Shizuku не требуются — касания с ГУ работают напрямую внутри Кокпита.",
            mediaProjectionRelevance = RequirementRelevance.OPTIONAL_FEATURE,
            mediaProjectionBadge = "Для звука 0x0003",
            mediaProjectionExplanation = "В режиме «Кокпит» видеопоток рисуется на VirtualDisplay без MediaProjection. Подтвердите «Весь экран» только если хотите передавать системный звук музыки по USB (0x0003).",
            permissions = listOf(
                PermissionRequirementSpec(
                    key = AppPermissionKey.POST_NOTIFICATIONS,
                    title = "Уведомления (POST_NOTIFICATIONS)",
                    reason = "Стабильная работа фонового USB-сервиса без выгрузки системой.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.BATTERY_OPTIMIZATION,
                    title = "Без ограничений батареи",
                    reason = "Не даёт Android усыплять USB-трансляцию в дороге.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.NOTIFICATION_LISTENER,
                    title = "Доступ к медиаплееру (Уведомления)",
                    reason = "Название трека, обложка и кнопки ⏮ ⏯ ⏭ на левой панели Кокпита.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.FINE_LOCATION,
                    title = "Геолокация (ACCESS_FINE_LOCATION)",
                    reason = "Определение позиции и пробок во встроенных Яндекс Картах.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.RECORD_AUDIO,
                    title = "Запись аудио (RECORD_AUDIO)",
                    reason = "Захват звука музыки для передачи в штатные динамики по USB (0x0003).",
                    relevance = RequirementRelevance.OPTIONAL_FEATURE,
                    roleBadge = "Для звука 0x0003"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.ACCESSIBILITY,
                    title = "Специальные возможности (Accessibility)",
                    reason = "Нужно только в режиме «Зеркало» для кликов по всему экрану телефона.",
                    relevance = RequirementRelevance.NOT_NEEDED,
                    roleBadge = "Не требуется"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.OVERLAY,
                    title = "Поверх других окон (Overlay)",
                    reason = "Нужно только в «Shizuku» (панель поверх Навигатора) или для затемнения в «Зеркало».",
                    relevance = RequirementRelevance.NOT_NEEDED,
                    roleBadge = "Не требуется"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.SHIZUKU,
                    title = "Доступ Shizuku",
                    reason = "Нужен только для режима «Кокпит + Shizuku» (второй экран).",
                    relevance = RequirementRelevance.NOT_NEEDED,
                    roleBadge = "Не требуется"
                )
            )
        )

        AppMode.MIRRORING -> ModeConnectionGuideSpec(
            mode = AppMode.MIRRORING,
            headline = "Режим «Зеркало» (полная трансляция экрана 30 FPS)",
            summary = "Зеркалирует весь экран смартфона на дисплей ГУ и передаёт касания/жесты с магнитолы обратно в телефон.",
            mandatoryChecklistNote = "Обязательно выдайте «Весь экран» (MediaProjection) и включите «JETTA Connect (Управление экраном)» в Спец. возможностях.",
            mediaProjectionRelevance = RequirementRelevance.REQUIRED,
            mediaProjectionBadge = "ОБЯЗАТЕЛЬНО • 90%",
            mediaProjectionExplanation = "В окне Samsung обязательно выберите «Весь экран» (Entire Screen), а не «Одно приложение» — иначе загрузка на ГУ зависнет на 90%.",
            permissions = listOf(
                PermissionRequirementSpec(
                    key = AppPermissionKey.POST_NOTIFICATIONS,
                    title = "Уведомления (POST_NOTIFICATIONS)",
                    reason = "Стабильная работа foreground-сервиса захвата экрана и USB.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.BATTERY_OPTIMIZATION,
                    title = "Без ограничений батареи",
                    reason = "Не даёт Android прерывать зеркалирование при длительной поездке.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.ACCESSIBILITY,
                    title = "Специальные возможности (Accessibility)",
                    reason = "Включите «JETTA Connect (Управление экраном)» для тапов и свайпов с ГУ (0x0006).",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.OVERLAY,
                    title = "Поверх других окон (Overlay)",
                    reason = "Нужно для яркости 1% OLED, автоповорота и работы при погашенном экране (KeepAlive).",
                    relevance = RequirementRelevance.OPTIONAL_FEATURE,
                    roleBadge = "Для KeepAlive / 1%"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.RECORD_AUDIO,
                    title = "Запись аудио (RECORD_AUDIO)",
                    reason = "Передача системного звука навигатора и музыки в автомобиль по USB (0x0003).",
                    relevance = RequirementRelevance.OPTIONAL_FEATURE,
                    roleBadge = "Для звука 0x0003"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.SHIZUKU,
                    title = "Доступ Shizuku",
                    reason = "Опция: скрытие области выреза камеры при зеркалировании по USB.",
                    relevance = RequirementRelevance.OPTIONAL_FEATURE,
                    roleBadge = "Опция: Вырез"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.NOTIFICATION_LISTENER,
                    title = "Доступ к медиаплееру (Уведомления)",
                    reason = "Опция: управление плеером кнопками на руле и виджетом в Кокпите.",
                    relevance = RequirementRelevance.NOT_NEEDED,
                    roleBadge = "Опция"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.FINE_LOCATION,
                    title = "Геолокация (ACCESS_FINE_LOCATION)",
                    reason = "Нужна только встроенной карте в режиме «Кокпит»; в «Зеркало» карты используют свой GPS.",
                    relevance = RequirementRelevance.NOT_NEEDED,
                    roleBadge = "Не требуется"
                )
            )
        )

        AppMode.SHIZUKU_COCKPIT -> ModeConnectionGuideSpec(
            mode = AppMode.SHIZUKU_COCKPIT,
            headline = "Режим «Кокпит + Shizuku» (второй виртуальный экран)",
            summary = "Запускает настоящий Яндекс Навигатор или 2ГИС на отдельном виртуальном дисплее и рисует слева панель VW поверх.",
            mandatoryChecklistNote = "Требуется запущенная служба Shizuku (через Отладку по Wi-Fi), Overlay и доступ к медиаплееру. Спец. возможности не нужны.",
            mediaProjectionRelevance = RequirementRelevance.OPTIONAL_FEATURE,
            mediaProjectionBadge = "Для звука 0x0003",
            mediaProjectionExplanation = "Изображение второго экрана захватывается напрямую с VirtualDisplay. Подтвердите «Весь экран» для передачи системного звука по USB (0x0003).",
            permissions = listOf(
                PermissionRequirementSpec(
                    key = AppPermissionKey.POST_NOTIFICATIONS,
                    title = "Уведомления (POST_NOTIFICATIONS)",
                    reason = "Стабильная работа USB-сервиса и виртуального экрана в фоне.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.BATTERY_OPTIMIZATION,
                    title = "Без ограничений батареи",
                    reason = "Защита от закрытия виртуального дисплея в фоновом режиме.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.SHIZUKU,
                    title = "Доступ Shizuku",
                    reason = "Обязателен: запуск Навигатора/2ГИС на втором экране и прямой ввод тача/клавиш.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.OVERLAY,
                    title = "Поверх других окон (Overlay)",
                    reason = "Обязателен: закрепляет левую панель VW (часы, плеер) поверх Навигатора на втором экране.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.NOTIFICATION_LISTENER,
                    title = "Доступ к медиаплееру (Уведомления)",
                    reason = "Виджет музыки (обложка, трек, ⏮ ⏯ ⏭) на левой панели VW.",
                    relevance = RequirementRelevance.REQUIRED,
                    roleBadge = "Обязательно"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.RECORD_AUDIO,
                    title = "Запись аудио (RECORD_AUDIO)",
                    reason = "Захват подсказок навигатора и музыки для передачи по USB (0x0003).",
                    relevance = RequirementRelevance.OPTIONAL_FEATURE,
                    roleBadge = "Для звука 0x0003"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.ACCESSIBILITY,
                    title = "Специальные возможности (Accessibility)",
                    reason = "Не требуется: в этом режиме касания передаются напрямую через Shizuku.",
                    relevance = RequirementRelevance.NOT_NEEDED,
                    roleBadge = "Не требуется"
                ),
                PermissionRequirementSpec(
                    key = AppPermissionKey.FINE_LOCATION,
                    title = "Геолокация (ACCESS_FINE_LOCATION)",
                    reason = "Навигатор и 2ГИС используют собственные права GPS.",
                    relevance = RequirementRelevance.NOT_NEEDED,
                    roleBadge = "Не требуется"
                )
            )
        )
    }

    fun evaluateReadiness(
        mode: AppMode,
        grantedMap: Map<AppPermissionKey, Boolean>,
        hasMediaProjectionToken: Boolean
    ): ModeReadinessSummary {
        val spec = specFor(mode)
        val requiredPermissions = spec.permissions.filter { it.relevance == RequirementRelevance.REQUIRED }
        val grantedRequired = requiredPermissions.count { grantedMap[it.key] == true }
        val missingLabels = buildList {
            for (perm in requiredPermissions) {
                if (grantedMap[perm.key] != true) {
                    add(perm.title)
                }
            }
            if (spec.mediaProjectionRelevance == RequirementRelevance.REQUIRED && !hasMediaProjectionToken) {
                add("Захват экрана («Весь экран»)")
            }
        }
        val mpRequired = spec.mediaProjectionRelevance == RequirementRelevance.REQUIRED
        val totalRequired = requiredPermissions.size + if (mpRequired) 1 else 0
        val grantedTotal = grantedRequired + if (mpRequired && hasMediaProjectionToken) 1 else 0
        return ModeReadinessSummary(
            mode = mode,
            grantedRequiredCount = grantedTotal,
            totalRequiredCount = totalRequired,
            missingRequiredLabels = missingLabels,
            mediaProjectionRequired = mpRequired,
            mediaProjectionGranted = hasMediaProjectionToken
        )
    }
}
