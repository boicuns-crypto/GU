package com.example.feature.cockpit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live state of the active media session detected via [MediaSessionManager].
 */
data class MediaState(
    val title: String = "Нет активного трека",
    val artist: String = "Запустите музыку на телефоне",
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val packageName: String = "",
    val hasNotificationAccess: Boolean = false,
    val playerAppTitle: String = "Медиаплеер",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val hasActiveSession: Boolean
        get() = packageName.isNotBlank()

    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Universal media session controller backed by [MediaSessionManager.getActiveSessions].
 * Works with any player publishing an Android MediaSession (Яндекс Музыка, Spotify, VK, Poweramp, YouTube Music).
 */
object MediaPlayerController {

    private const val TAG = "MediaPlayerController"

    private val _mediaState = MutableStateFlow(MediaState())
    val mediaState: StateFlow<MediaState> = _mediaState.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var mediaSessionManager: MediaSessionManager? = null

    @Volatile
    private var activeController: MediaController? = null

    @Volatile
    private var isListenerConnected: Boolean = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncFromController(activeController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncFromController(activeController)
        }

        override fun onSessionDestroyed() {
            refreshSessions()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindBestActiveController(controllers.orEmpty())
        }

    fun initialize(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        mediaSessionManager =
            ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        refreshSessions(ctx)
    }

    fun hasNotificationListenerAccess(context: Context? = appContext): Boolean {
        if (isListenerConnected) return true
        val ctx = context?.applicationContext ?: context ?: return _mediaState.value.hasNotificationAccess
        return runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        }.getOrDefault(false)
    }

    fun onNotificationListenerStateChanged(connected: Boolean) {
        isListenerConnected = connected
        _mediaState.update { state ->
            state.copy(
                hasNotificationAccess = connected,
                artist = if (!connected && !state.hasActiveSession) {
                    "Выдайте доступ к уведомлениям"
                } else {
                    state.artist
                }
            )
        }
        if (connected) {
            refreshSessions()
        }
    }

    fun refreshSessions(context: Context? = appContext) {
        val ctx = context?.applicationContext ?: context ?: appContext ?: return
        appContext = ctx
        if (mediaSessionManager == null) {
            mediaSessionManager =
                ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }

        val hasAccess = hasNotificationListenerAccess(ctx)
        _mediaState.update { state ->
            state.copy(
                hasNotificationAccess = hasAccess,
                artist = if (!hasAccess && !state.hasActiveSession) {
                    "Включите доступ к уведомлениям"
                } else if (hasAccess && !state.hasActiveSession) {
                    "Запустите музыку на телефоне"
                } else {
                    state.artist
                }
            )
        }
        if (!hasAccess) return

        val componentName = ComponentName(ctx, CarLifeMediaNotificationService::class.java)
        runCatching {
            val manager = mediaSessionManager ?: return
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            val controllers = manager.getActiveSessions(componentName)
            bindBestActiveController(controllers)
        }.onFailure { e ->
            Log.w(TAG, "Failed to query active media sessions: ${e.message}")
        }
    }

    fun playPause() {
        val controller = activeController
        if (controller != null) {
            runCatching {
                val pbState = controller.playbackState?.state
                if (pbState == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
                syncFromController(controller)
            }.onFailure {
                dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        } else {
            dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            refreshSessions()
        }
    }

    fun next() {
        val controller = activeController
        if (controller != null) {
            runCatching {
                controller.transportControls.skipToNext()
                syncFromController(controller)
            }.onFailure {
                dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
        } else {
            dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            refreshSessions()
        }
    }

    fun previous() {
        val controller = activeController
        if (controller != null) {
            runCatching {
                controller.transportControls.skipToPrevious()
                syncFromController(controller)
            }.onFailure {
                dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        } else {
            dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            refreshSessions()
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun bindBestActiveController(controllers: List<MediaController>) {
        runCatching { activeController?.unregisterCallback(controllerCallback) }
        val picked = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.metadata != null
        } ?: controllers.firstOrNull()

        activeController = picked
        if (picked != null) {
            runCatching { picked.registerCallback(controllerCallback) }
            syncFromController(picked)
        } else {
            val hasAccess = hasNotificationListenerAccess()
            _mediaState.value = MediaState(
                title = "Нет активного трека",
                artist = if (hasAccess) "Запустите музыку на телефоне" else "Включите доступ к уведомлениям",
                albumArt = null,
                isPlaying = false,
                packageName = "",
                hasNotificationAccess = hasAccess,
                playerAppTitle = "Медиаплеер"
            )
        }
    }

    private fun syncFromController(controller: MediaController?) {
        if (controller == null) return
        val metadata = controller.metadata
        val pb = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Без названия"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: friendlyPlayerName(controller.packageName)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val pkg = controller.packageName.orEmpty()
        val playing = pb?.state == PlaybackState.STATE_PLAYING
        val position = pb?.position?.coerceAtLeast(0L) ?: 0L

        _mediaState.update {
            it.copy(
                title = title,
                artist = artist,
                albumArt = art,
                isPlaying = playing,
                packageName = pkg,
                hasNotificationAccess = true,
                playerAppTitle = friendlyPlayerName(pkg),
                positionMs = position,
                durationMs = duration
            )
        }
    }

    internal fun friendlyPlayerName(pkg: String): String = when {
        pkg.contains("yandex.music", ignoreCase = true) -> "Яндекс Музыка"
        pkg.contains("yandex", ignoreCase = true) -> "Яндекс"
        pkg.contains("spotify", ignoreCase = true) -> "Spotify"
        pkg.contains("vkontakte", ignoreCase = true) || pkg.contains("vk.music", ignoreCase = true) -> "VK Музыка"
        pkg.contains("maxmpz", ignoreCase = true) -> "Poweramp"
        pkg.contains("youtube.music", ignoreCase = true) -> "YouTube Music"
        pkg.contains("youtube", ignoreCase = true) -> "YouTube"
        pkg.contains("zvuk", ignoreCase = true) -> "Звук"
        pkg.contains("mts", ignoreCase = true) -> "МТС Музыка"
        pkg.isBlank() -> "Медиаплеер"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun dispatchSystemMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    @VisibleForTesting
    internal fun setStateForTesting(state: MediaState) {
        _mediaState.value = state
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        isListenerConnected = false
        activeController = null
        _mediaState.value = MediaState()
    }
}
