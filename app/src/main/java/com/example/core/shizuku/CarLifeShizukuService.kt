package com.example.core.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Привилегированный Shizuku UserService (AIDL), выполняющийся в процессе Shizuku (shell/root):
 * - Запускает приложения на целевом `VirtualDisplay` в правой области (`250,0,1280,720`) через
 *   Freeform (`am start --display <displayId> --windowingMode 5`), с автоматическим откатом на
 *   полноэкранный запуск `am start --display <displayId>` (левая панель VW 250px лежит поверх).
 * - Инжектит низколатентные касания (`MotionEvent` + `setDisplayId`) через `InputManager.injectInputEvent(..., 0)`.
 * - Передаёт системные клавиши (`KEYCODE_BACK = 4`, `KEYCODE_HOME = 3`) через `input keyevent --display <displayId> <keyCode>`.
 */
@Keep
class CarLifeShizukuService : ICarLifeShizukuService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarLifeShizukuCmd").apply { isDaemon = true }
    }

    @Volatile
    private var noCutoutOverlayEnabledByService: Boolean = false

    override fun launchAppOnDisplay(
        packageName: String?,
        activityName: String?,
        displayId: Int
    ) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || displayId < Display.DEFAULT_DISPLAY) return
        val act = activityName?.trim().orEmpty()

        commandExecutor.execute {
            runCatching {
                // 1. Try Freeform launch in right area (x = 250..1280, y = 0..720)
                val freeformCmd = buildStartCommand(
                    packageName = pkg,
                    activityName = act,
                    displayId = displayId,
                    useFreeform = true
                )
                val freeformResult = execShellCommandDetailed(freeformCmd)
                if (freeformResult.isSuccess) {
                    // Best-effort task bounds resize to right pane (250, 0, 1280, 720)
                    tryResizeTopTaskOnDisplay(
                        packageName = pkg,
                        displayId = displayId,
                        left = RIGHT_PANE_LEFT_PX,
                        top = 0,
                        right = RIGHT_PANE_RIGHT_PX,
                        bottom = RIGHT_PANE_BOTTOM_PX
                    )
                    return@runCatching
                }

                // 2. Fallback: standard fullscreen launch on VirtualDisplay (left 250px VW panel overlays edge)
                val fullscreenCmd = buildStartCommand(
                    packageName = pkg,
                    activityName = act,
                    displayId = displayId,
                    useFreeform = false
                )
                val primaryResult = execShellCommandDetailed(fullscreenCmd)
                if (!primaryResult.isSuccess && act.isNotEmpty()) {
                    val genericLauncherCmd = buildStartCommand(
                        packageName = pkg,
                        activityName = "",
                        displayId = displayId,
                        useFreeform = false
                    )
                    execShellCommandDetailed(genericLauncherCmd)
                }
            }.onFailure { e ->
                Log.w(TAG, "launchAppOnDisplay failed for $pkg on display $displayId: ${e.message}")
            }
        }
    }

    private fun tryResizeTopTaskOnDisplay(
        packageName: String,
        displayId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        runCatching {
            val dump = execShellCommandDetailed(arrayOf("am", "stack", "list"))
            val taskId = extractTaskIdForPackage(dump.stdout, packageName, displayId) ?: return
            execShellCommandDetailed(
                arrayOf(
                    "am",
                    "task",
                    "resize",
                    taskId.toString(),
                    left.toString(),
                    top.toString(),
                    right.toString(),
                    bottom.toString()
                )
            )
        }
    }

    override fun injectTouch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        displayId: Int
    ) {
        val now = SystemClock.uptimeMillis()
        val safeDownTime = if (downTime > 0L) downTime else now
        val safeEventTime = maxOf(safeDownTime, if (eventTime > 0L) eventTime else now)

        val event = MotionEvent.obtain(
            safeDownTime,
            safeEventTime,
            action,
            x.coerceAtLeast(0f),
            y.coerceAtLeast(0f),
            0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        try {
            if (displayId >= Display.DEFAULT_DISPLAY) {
                applyDisplayId(event, displayId)
            }
            injectInputEventAsync(event)
        } catch (t: Throwable) {
            Log.w(TAG, "injectTouch failed on display $displayId: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    override fun sendKeyEvent(keyCode: Int, displayId: Int) {
        if (keyCode <= 0 || displayId < Display.DEFAULT_DISPLAY) return
        commandExecutor.execute {
            runCatching {
                execShellCommandDetailed(
                    arrayOf(
                        "input",
                        "keyevent",
                        "--display",
                        displayId.toString(),
                        keyCode.toString()
                    )
                )
            }.onFailure { e ->
                Log.w(TAG, "sendKeyEvent($keyCode) failed on display $displayId: ${e.message}")
            }
        }
    }

    @Volatile
    private var customDisplayAspectAppliedByService: Boolean = false

    override fun setNoCutoutOverlayEnabled(enabled: Boolean): Boolean {
        return runCatching {
            if (enabled) {
                val res = execShellCommandDetailed(buildNoCutoutOverlayCommand(enable = true))
                if (res.isSuccess) {
                    noCutoutOverlayEnabledByService = true
                }
                res.isSuccess
            } else {
                val res = execShellCommandDetailed(buildNoCutoutOverlayCommand(enable = false))
                noCutoutOverlayEnabledByService = false
                res.isSuccess
            }
        }.onFailure { e ->
            Log.w(TAG, "setNoCutoutOverlayEnabled($enabled) failed: ${e.message}")
            if (!enabled) {
                noCutoutOverlayEnabledByService = false
            }
        }.getOrDefault(false)
    }

    override fun applyCustomDisplayAspect(targetWidth: Int, targetHeight: Int): Boolean {
        return runCatching {
            val safeTargetLong = maxOf(targetWidth, targetHeight).coerceAtLeast(480)
            val safeTargetShort = minOf(targetWidth, targetHeight).coerceAtLeast(272)
            val query = execShellCommandDetailed(arrayOf("wm", "size"))
            val regex = Regex("""Physical size:\s*(\d+)x(\d+)""")
            val match = regex.find(query.stdout)
            val physW = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1080
            val physH = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 2340
            val physShort = minOf(physW, physH).coerceIn(720, 2160)
            val matchedLong = (((physShort.toLong() * safeTargetLong) / safeTargetShort).toInt() / 2 * 2)
                .coerceIn(physShort, 4096)

            val res = execShellCommandDetailed(arrayOf("wm", "size", "${physShort}x${matchedLong}"))
            if (res.isSuccess) {
                customDisplayAspectAppliedByService = true
            }
            res.isSuccess
        }.onFailure { e ->
            Log.w(TAG, "applyCustomDisplayAspect(${targetWidth}x${targetHeight}) failed: ${e.message}")
        }.getOrDefault(false)
    }

    override fun restoreNativeDisplayAspect(): Boolean {
        return runCatching {
            val res = execShellCommandDetailed(arrayOf("wm", "size", "reset"))
            customDisplayAspectAppliedByService = false
            res.isSuccess
        }.onFailure { e ->
            Log.w(TAG, "restoreNativeDisplayAspect failed: ${e.message}")
            customDisplayAspectAppliedByService = false
        }.getOrDefault(false)
    }

    override fun destroy() {
        if (customDisplayAspectAppliedByService) {
            runCatching {
                execShellCommandDetailed(arrayOf("wm", "size", "reset"))
            }
            customDisplayAspectAppliedByService = false
        }
        if (noCutoutOverlayEnabledByService) {
            runCatching {
                execShellCommandDetailed(buildNoCutoutOverlayCommand(enable = false))
            }
            noCutoutOverlayEnabledByService = false
        }
        runCatching { commandExecutor.shutdownNow() }
        exitProcess(0)
    }

    private data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean
            get() = exitCode == 0 &&
                !stderr.contains("Error", ignoreCase = true) &&
                !stderr.contains("Exception", ignoreCase = true) &&
                !stdout.contains("Error type", ignoreCase = true)
    }

    private fun execShellCommandDetailed(command: Array<String>): ShellResult {
        val process = Runtime.getRuntime().exec(command)
        return try {
            val out = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            val err = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else {
                    process.destroyForcibly()
                    -1
                }
            } else {
                process.waitFor()
            }
            ShellResult(exitCode = code, stdout = out, stderr = err)
        } finally {
            runCatching { process.destroy() }
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
    companion object {
        private const val TAG = "CarLifeShizukuSvc"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val NO_CUTOUT_OVERLAY_PACKAGE = "com.android.internal.display.cutout.emulation.noCutout"
        const val WINDOWING_MODE_FREEFORM = 5
        const val RIGHT_PANE_LEFT_PX = 250
        const val RIGHT_PANE_RIGHT_PX = 1280
        const val RIGHT_PANE_BOTTOM_PX = 720

        internal fun buildNoCutoutOverlayCommand(enable: Boolean): Array<String> = arrayOf(
            "cmd",
            "overlay",
            if (enable) "enable" else "disable",
            NO_CUTOUT_OVERLAY_PACKAGE
        )

        internal fun buildStartCommand(
            packageName: String,
            activityName: String,
            displayId: Int,
            useFreeform: Boolean
        ): Array<String> {
            val args = mutableListOf(
                "am", "start",
                "--display", displayId.toString()
            )
            if (useFreeform) {
                args.add("--windowingMode")
                args.add(WINDOWING_MODE_FREEFORM.toString())
            }
            if (activityName.isNotBlank()) {
                val component = if (activityName.startsWith(".")) {
                    "$packageName$activityName"
                } else {
                    "$packageName/$activityName"
                }
                args.add("-n")
                args.add(component)
            } else {
                args.add("-a")
                args.add("android.intent.action.MAIN")
                args.add("-c")
                args.add("android.intent.category.LAUNCHER")
                args.add(packageName)
            }
            return args.toTypedArray()
        }

        internal fun extractTaskIdForPackage(
            stackDump: String,
            packageName: String,
            displayId: Int
        ): Int? {
            if (stackDump.isBlank() || packageName.isBlank()) return null
            val taskIdRegex = Regex("""taskId=(\d+)""")
            var inTargetDisplay = false
            for (line in stackDump.lineSequence()) {
                if (line.contains("displayId=")) {
                    inTargetDisplay = line.contains("displayId=$displayId")
                }
                if (inTargetDisplay && line.contains(packageName)) {
                    val match = taskIdRegex.find(line)
                    val id = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (id != null) return id
                }
            }
            return null
        }

        private val setDisplayIdMethod: Method? by lazy {
            runCatching {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
            }.recoverCatching {
                InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
            }.recoverCatching {
                MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
            }.getOrNull()
        }

        private val inputManagerHolder: Pair<Any, Method>? by lazy {
            runCatching {
                val getInstance = InputManager::class.java.getMethod("getInstance")
                val instance = getInstance.invoke(null)
                    ?: error("InputManager.getInstance() returned null")
                val injectMethod = instance.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).apply {
                    isAccessible = true
                }
                instance to injectMethod
            }.recoverCatching {
                val globalCls = Class.forName("android.hardware.input.InputManagerGlobal")
                val getInstance = globalCls.getMethod("getInstance")
                val instance = getInstance.invoke(null)
                    ?: error("InputManagerGlobal.getInstance() returned null")
                val injectMethod = instance.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).apply {
                    isAccessible = true
                }
                instance to injectMethod
            }.getOrNull()
        }

        internal fun applyDisplayId(event: MotionEvent, displayId: Int): Boolean {
            val method = setDisplayIdMethod ?: return false
            return runCatching {
                method.invoke(event, displayId)
                true
            }.getOrDefault(false)
        }

        internal fun injectInputEventAsync(event: MotionEvent): Boolean {
            val (instance, method) = inputManagerHolder ?: return false
            return runCatching {
                val res = method.invoke(instance, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                (res as? Boolean) ?: true
            }.getOrDefault(false)
        }
    }
}
