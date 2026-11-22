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
 * - Запускает приложения на целевом `VirtualDisplay` через `am start --display <displayId>` в обход `Permission Denial` на Android 16.
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
                val primaryCmd = if (act.isNotEmpty()) {
                    val component = if (act.startsWith(".")) "$pkg$act" else "$pkg/$act"
                    arrayOf("am", "start", "--display", displayId.toString(), "-n", component)
                } else {
                    arrayOf(
                        "am", "start",
                        "--display", displayId.toString(),
                        "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER",
                        pkg
                    )
                }

                val primaryExit = execShellCommand(primaryCmd)
                if (primaryExit != 0 && act.isNotEmpty()) {
                    val fallbackCmd = arrayOf(
                        "am", "start",
                        "--display", displayId.toString(),
                        "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER",
                        pkg
                    )
                    execShellCommand(fallbackCmd)
                }
            }.onFailure { e ->
                Log.w(TAG, "launchAppOnDisplay failed for $pkg on display $displayId: ${e.message}")
            }
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
                execShellCommand(
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

    override fun destroy() {
        runCatching { commandExecutor.shutdownNow() }
        exitProcess(0)
    }

    private fun execShellCommand(command: Array<String>): Int {
        val process = Runtime.getRuntime().exec(command)
        return try {
            runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            runCatching { process.errorStream.bufferedReader().use { it.readText() } }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else {
                    process.destroyForcibly()
                    -1
                }
            } else {
                process.waitFor()
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
    companion object {
        private const val TAG = "CarLifeShizukuSvc"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

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
