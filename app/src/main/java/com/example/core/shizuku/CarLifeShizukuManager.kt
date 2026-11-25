package com.example.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku

/**
 * Текущее состояние службы Shizuku и привязанного UserService (`ICarLifeShizukuService`).
 */
data class ShizukuStatusState(
    val isBinderAlive: Boolean = false,
    val hasPermission: Boolean = false,
    val isUserServiceBound: Boolean = false,
    val isShizukuAppInstalled: Boolean = false,
    val statusLabel: String = "Не запущен",
    val detailMessage: String = "Запустите службу Shizuku и нажмите «Запросить права Shizuku»"
)

/**
 * Управляет правами Shizuku (`requestPermission`), слушателями жизненного цикла биндера
 * и привязкой [CarLifeShizukuService] (`Shizuku.bindUserService`) для режима «Кокпит + Shizuku».
 */
object CarLifeShizukuManager {
    private const val TAG = "CarLifeShizukuMgr"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 2606
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    private val _status = MutableStateFlow(ShizukuStatusState())
    val status: StateFlow<ShizukuStatusState> = _status.asStateFlow()

    @Volatile
    var userService: ICarLifeShizukuService? = null
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var autoBindWhenReady: Boolean = false

    @Volatile
    private var testingOverrideActive: Boolean = false

    @Volatile
    var noCutoutOverlayEnabledByBridge: Boolean = false
        private set

    @Volatile
    var mirroringAspect16by9ModifiedByBridge: Boolean = false
        private set

    private val listenersInitialized = AtomicBoolean(false)
    private val bindingInProgress = AtomicBoolean(false)
    private val asyncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarLifeShizukuClient").apply { isDaemon = true }
    }

    private var currentUserServiceArgs: Shizuku.UserServiceArgs? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val ctx = appContext
        refreshStatus(ctx)
        if (autoBindWhenReady && ctx != null) {
            bindUserServiceIfReady(ctx)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        bindingInProgress.set(false)
        _status.update {
            it.copy(
                isBinderAlive = false,
                isUserServiceBound = false,
                statusLabel = "Не запущен",
                detailMessage = "Служба Shizuku остановлена или перезагружается"
            )
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                val ctx = appContext
                refreshStatus(ctx)
                if (granted && ctx != null) {
                    bindUserServiceIfReady(ctx)
                }
            }
        }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bindingInProgress.set(false)
            val stub = service?.let { ICarLifeShizukuService.Stub.asInterface(it) }
            userService = stub
            val ctx = appContext
            refreshStatus(ctx)
            Log.i(TAG, "CarLifeShizukuService connected (stub=${stub != null})")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindingInProgress.set(false)
            userService = null
            refreshStatus(appContext)
            Log.w(TAG, "CarLifeShizukuService disconnected")
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        if (listenersInitialized.compareAndSet(false, true)) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            }.onFailure { e ->
                Log.w(TAG, "Shizuku listener init skipped: ${e.message}")
            }
        }
        refreshStatus(ctx)
    }

    fun refreshStatus(context: Context? = appContext): ShizukuStatusState {
        if (testingOverrideActive) {
            return _status.value
        }
        val ctx = context?.applicationContext ?: context ?: appContext
        if (ctx != null) {
            appContext = ctx
        }
        val appInstalled = if (ctx != null) {
            runCatching {
                ctx.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
                true
            }.getOrDefault(false)
        } else {
            _status.value.isShizukuAppInstalled
        }

        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val hasPerm = if (binderAlive) {
            runCatching {
                !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }

        val bound = userService?.let { svc ->
            runCatching { svc.asBinder()?.isBinderAlive == true }.getOrDefault(false)
        } ?: false
        if (!bound && !testingOverrideActive) {
            userService = null
        }

        val label = when {
            !binderAlive -> "Не запущен"
            !hasPerm -> "Нет прав"
            else -> "Работает"
        }

        val detail = when {
            !binderAlive && !appInstalled ->
                "Shizuku не обнаружен — установите и запустите Shizuku"
            !binderAlive ->
                "Служба Shizuku не запущена (включите её в приложении Shizuku)"
            !hasPerm ->
                "Служба Shizuku активна — нажмите «Запросить права Shizuku»"
            bound ->
                "Shizuku работает • UserService (AIDL) подключён к input & am"
            else ->
                "Права Shizuku выданы • UserService готов к привязке"
        }

        val snapshot = ShizukuStatusState(
            isBinderAlive = binderAlive,
            hasPermission = hasPerm,
            isUserServiceBound = bound,
            isShizukuAppInstalled = appInstalled,
            statusLabel = label,
            detailMessage = detail
        )
        _status.value = snapshot
        return snapshot
    }

    fun requestPermission(context: Context): Boolean {
        val ctx = context.applicationContext ?: context
        init(ctx)
        val current = refreshStatus(ctx)
        if (!current.isBinderAlive) {
            if (current.isShizukuAppInstalled) {
                runCatching {
                    ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)?.let { intent ->
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }
                }
            }
            return false
        }

        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        if (preV11) {
            _status.update {
                it.copy(detailMessage = "Требуется Shizuku v11+ (13.x)")
            }
            return false
        }

        if (current.hasPermission) {
            return bindUserServiceIfReady(ctx)
        }

        return runCatching {
            autoBindWhenReady = true
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            true
        }.onFailure { e ->
            Log.w(TAG, "Shizuku.requestPermission failed: ${e.message}")
        }.getOrDefault(false)
    }

    fun bindUserServiceIfReady(context: Context): Boolean {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        autoBindWhenReady = true

        val existing = userService
        if (existing != null && runCatching { existing.asBinder()?.isBinderAlive == true }.getOrDefault(false)) {
            refreshStatus(ctx)
            return true
        }

        val snapshot = refreshStatus(ctx)
        if (!snapshot.isBinderAlive || !snapshot.hasPermission) {
            return false
        }
        if (!bindingInProgress.compareAndSet(false, true)) {
            return true
        }

        return runCatching {
            val args = getOrCreateUserServiceArgs(ctx)
            Shizuku.bindUserService(args, userServiceConnection)
            true
        }.onFailure { e ->
            bindingInProgress.set(false)
            Log.w(TAG, "Shizuku.bindUserService failed: ${e.message}")
        }.getOrDefault(false)
    }

    fun unbindUserService(context: Context? = appContext) {
        autoBindWhenReady = false
        bindingInProgress.set(false)
        val ctx = context?.applicationContext ?: context ?: appContext
        val args = currentUserServiceArgs ?: ctx?.let { getOrCreateUserServiceArgs(it) }
        if (args != null) {
            runCatching {
                Shizuku.unbindUserService(args, userServiceConnection, true)
            }
        }
        if (!testingOverrideActive) {
            userService = null
        }
        refreshStatus(ctx)
    }

    fun launchAppOnDisplay(
        packageName: String,
        activityName: String,
        displayId: Int
    ): Boolean {
        val service = userService ?: return false
        return runCatching {
            service.launchAppOnDisplay(packageName, activityName, displayId)
            true
        }.onFailure { e ->
            Log.w(TAG, "launchAppOnDisplay IPC failed: ${e.message}")
            handleIpcFailure()
        }.getOrDefault(false)
    }

    fun injectTouch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        displayId: Int
    ): Boolean {
        val service = userService ?: return false
        return runCatching {
            service.injectTouch(action, x, y, downTime, eventTime, displayId)
            true
        }.onFailure { e ->
            Log.w(TAG, "injectTouch IPC failed: ${e.message}")
            handleIpcFailure()
        }.getOrDefault(false)
    }

    fun sendKeyEvent(keyCode: Int, displayId: Int): Boolean {
        val service = userService ?: return false
        return runCatching {
            service.sendKeyEvent(keyCode, displayId)
            true
        }.onFailure { e ->
            Log.w(TAG, "sendKeyEvent IPC failed: ${e.message}")
            handleIpcFailure()
        }.getOrDefault(false)
    }

    fun sendKeyEventAsync(keyCode: Int, displayId: Int) {
        if (userService == null) return
        asyncExecutor.execute {
            sendKeyEvent(keyCode, displayId)
        }
    }

    fun setNoCutoutOverlayEnabled(enabled: Boolean): Boolean {
        if (!enabled && !noCutoutOverlayEnabledByBridge) {
            return true
        }
        val service = userService ?: run {
            if (!enabled) {
                noCutoutOverlayEnabledByBridge = false
            }
            return false
        }
        return runCatching {
            val ok = service.setNoCutoutOverlayEnabled(enabled)
            noCutoutOverlayEnabledByBridge = if (enabled) ok else false
            ok
        }.onFailure { e ->
            Log.w(TAG, "setNoCutoutOverlayEnabled($enabled) IPC failed: ${e.message}")
            if (!enabled) {
                noCutoutOverlayEnabledByBridge = false
            }
            handleIpcFailure()
        }.getOrDefault(false)
    }

    fun setNoCutoutOverlayEnabledAsync(enabled: Boolean) {
        if (!enabled && !noCutoutOverlayEnabledByBridge) return
        if (userService == null) {
            if (!enabled) noCutoutOverlayEnabledByBridge = false
            return
        }
        asyncExecutor.execute {
            setNoCutoutOverlayEnabled(enabled)
        }
    }

    fun setMirroringAspect16by9(onApplied: (() -> Unit)? = null): Boolean {
        if (mirroringAspect16by9ModifiedByBridge) {
            onApplied?.invoke()
            return true
        }
        val service = userService ?: return false
        return runCatching {
            service.setMirroringAspect16by9()
            mirroringAspect16by9ModifiedByBridge = true
            onApplied?.invoke()
            true
        }.onFailure { e ->
            Log.w(TAG, "setMirroringAspect16by9 IPC failed: ${e.message}")
            handleIpcFailure()
        }.getOrDefault(false)
    }

    fun setMirroringAspect16by9Async(onApplied: (() -> Unit)? = null) {
        if (mirroringAspect16by9ModifiedByBridge) {
            onApplied?.invoke()
            return
        }
        if (userService == null) return
        asyncExecutor.execute {
            setMirroringAspect16by9(onApplied)
        }
    }

    fun resetDisplaySize(force: Boolean = false, onReset: (() -> Unit)? = null): Boolean {
        if (!force && !mirroringAspect16by9ModifiedByBridge) {
            return true
        }
        val service = userService ?: run {
            if (!force) {
                mirroringAspect16by9ModifiedByBridge = false
            }
            return false
        }
        return runCatching {
            service.resetDisplaySize()
            mirroringAspect16by9ModifiedByBridge = false
            onReset?.invoke()
            true
        }.onFailure { e ->
            Log.w(TAG, "resetDisplaySize IPC failed: ${e.message}")
            if (!force) {
                mirroringAspect16by9ModifiedByBridge = false
            }
            handleIpcFailure()
        }.getOrDefault(false)
    }

    fun resetDisplaySizeAsync(force: Boolean = false, onReset: (() -> Unit)? = null) {
        if (!force && !mirroringAspect16by9ModifiedByBridge) return
        if (userService == null) {
            if (!force) mirroringAspect16by9ModifiedByBridge = false
            return
        }
        asyncExecutor.execute {
            resetDisplaySize(force = force, onReset = onReset)
        }
    }

    fun cleanupAndResetThenUnbind(context: Context? = appContext) {
        val ctx = context?.applicationContext ?: context ?: appContext
        if (userService == null) {
            noCutoutOverlayEnabledByBridge = false
            mirroringAspect16by9ModifiedByBridge = false
            unbindUserService(ctx)
            return
        }
        asyncExecutor.execute {
            runCatching { setNoCutoutOverlayEnabled(false) }
            runCatching { resetDisplaySize(force = false) }
            unbindUserService(ctx)
        }
    }

    private fun handleIpcFailure() {
        val ctx = appContext
        val binder = runCatching { userService?.asBinder() }.getOrNull()
        if (binder == null || !runCatching { binder.isBinderAlive }.getOrDefault(false)) {
            if (!testingOverrideActive) {
                userService = null
            }
            refreshStatus(ctx)
            if (autoBindWhenReady && ctx != null) {
                bindUserServiceIfReady(ctx)
            }
        }
    }

    private fun getOrCreateUserServiceArgs(context: Context): Shizuku.UserServiceArgs {
        val existing = currentUserServiceArgs
        if (existing != null) return existing
        val created = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, CarLifeShizukuService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        currentUserServiceArgs = created
        return created
    }

    @VisibleForTesting
    internal fun setServiceForTesting(
        service: ICarLifeShizukuService?,
        hasPermission: Boolean = service != null,
        binderAlive: Boolean = service != null
    ) {
        testingOverrideActive = true
        userService = service
        val label = when {
            !binderAlive -> "Не запущен"
            !hasPermission -> "Нет прав"
            else -> "Работает"
        }
        _status.value = ShizukuStatusState(
            isBinderAlive = binderAlive,
            hasPermission = hasPermission,
            isUserServiceBound = service != null,
            isShizukuAppInstalled = true,
            statusLabel = label,
            detailMessage = if (service != null) {
                "Shizuku работает • UserService (AIDL) подключён (Test)"
            } else {
                label
            }
        )
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        testingOverrideActive = false
        userService = null
        autoBindWhenReady = false
        noCutoutOverlayEnabledByBridge = false
        mirroringAspect16by9ModifiedByBridge = false
        bindingInProgress.set(false)
        _status.value = ShizukuStatusState()
    }
}
