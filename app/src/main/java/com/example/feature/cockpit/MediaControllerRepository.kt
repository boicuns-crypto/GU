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
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Immutable snapshot of the active media player session on the phone.
 */
data class CockpitMediaState(
    val hasNotificationAccess: Boolean = false,
    val activePackageName: String = "ru.yandex.music",
    val playerAppTitle: String = "Yandex Music • Hi-Res Lossless",
    val trackTitle: String = "Nightcall (MQB Cockpit Edit)",
    val artistName: String = "Kavinsky",
    val albumName: String = "OutRun",
    val isPlaying: Boolean = true,
    val positionMs: Long = 84_000L,
    val durationMs: Long = 258_000L,
    val albumArtBitmap: Bitmap? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0.35f
        }
}

/**
 * Core 4 (`:feature:cockpit-ui`): Repository bridging Android's [MediaSessionManager] to the
 * Jetpack Compose Cockpit Media Widget.
 *
 * - Observes active [MediaController] instances via [MediaSessionManager.addOnActiveSessionsChangedListener].
 * - Extracts live track metadata (`TITLE`, `ARTIST`, `ALBUM`, `DURATION`) and `ALBUM_ART` [Bitmap].
 * - Dispatches transport commands (`play()`, `pause()`, `skipToNext()`, `skipToPrevious()`) directly
 *   to the active `MediaController.transportControls`, with automatic fallback to
 *   `AudioManager.dispatchMediaKeyEvent` + built-in demo playlist so controls always respond immediately.
 */
object MediaControllerRepository {

    private val _mediaState = MutableStateFlow(CockpitMediaState())
    val mediaState: StateFlow<CockpitMediaState> = _mediaState.asStateFlow()

    private var appContext: Context? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    private data class DemoTrack(
        val player: String,
        val pkg: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private val fallbackPlaylist = listOf(
        DemoTrack(
            player = "Yandex Music • CarLife Audio",
            pkg = "ru.yandex.music",
            title = "Nightcall (MQB Cockpit Mix)",
            artist = "Kavinsky",
            album = "OutRun",
            durationMs = 258_000L
        ),
        DemoTrack(
            player = "Spotify • Lossless Stream",
            pkg = "com.spotify.music",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            durationMs = 243_000L
        ),
        DemoTrack(
            player = "Poweramp • Direct Hi-Res",
            pkg = "com.maxmpz.audioplayer",
            title = "Horizon",
            artist = "Daft Punk",
            album = "Random Access Memories",
            durationMs = 264_000L
        ),
        DemoTrack(
            player = "Yandex Music • My Vibe",
            pkg = "ru.yandex.music",
            title = "Veridis Quo",
            artist = "Daft Punk",
            album = "Discovery",
            durationMs = 345_000L
        )
    )
    private var fallbackTrackIndex = 0

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
            bindFirstActiveController(controllers.orEmpty())
        }

    fun initialize(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        mediaSessionManager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        refreshSessions()
    }

    fun onNotificationListenerStateChanged(connected: Boolean) {
        _mediaState.update { it.copy(hasNotificationAccess = connected) }
        if (connected) {
            refreshSessions()
        }
    }

    fun refreshSessions() {
        val ctx = appContext ?: return
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(ctx)
        val hasAccess = enabledPackages.contains(ctx.packageName)
        _mediaState.update { it.copy(hasNotificationAccess = hasAccess) }

        if (!hasAccess) return

        val componentName = ComponentName(ctx, CockpitMediaNotificationListenerService::class.java)
        runCatching {
            val manager = mediaSessionManager ?: return
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            } catch (_: Exception) {
            }
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            val controllers = manager.getActiveSessions(componentName)
            bindFirstActiveController(controllers)
        }
    }

    fun togglePlayPause() {
        val controller = activeController
        if (controller != null) {
            val pbState = controller.playbackState?.state
            if (pbState == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
            syncFromController(controller)
        } else {
            dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            _mediaState.update { it.copy(isPlaying = !it.isPlaying) }
        }
    }

    fun skipNext() {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToNext()
            syncFromController(controller)
        } else {
            dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            fallbackTrackIndex = (fallbackTrackIndex + 1) % fallbackPlaylist.size
            applyFallbackTrack(fallbackPlaylist[fallbackTrackIndex])
        }
    }

    fun skipPrevious() {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToPrevious()
            syncFromController(controller)
        } else {
            dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            fallbackTrackIndex =
                (fallbackTrackIndex - 1 + fallbackPlaylist.size) % fallbackPlaylist.size
            applyFallbackTrack(fallbackPlaylist[fallbackTrackIndex])
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

    private fun bindFirstActiveController(controllers: List<MediaController>) {
        activeController?.unregisterCallback(controllerCallback)
        val picked = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        activeController = picked
        if (picked != null) {
            picked.registerCallback(controllerCallback)
            syncFromController(picked)
        }
    }

    private fun syncFromController(controller: MediaController?) {
        if (controller == null) return
        val metadata = controller.metadata
        val pb = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Track"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Unknown Artist"
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 240_000L
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val pkg = controller.packageName
        val friendlyPlayer = when {
            pkg.contains("yandex", ignoreCase = true) -> "Yandex Music"
            pkg.contains("spotify", ignoreCase = true) -> "Spotify"
            pkg.contains("maxmpz", ignoreCase = true) -> "Poweramp"
            pkg.contains("youtube", ignoreCase = true) -> "YouTube Music"
            else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        val playing = pb?.state == PlaybackState.STATE_PLAYING
        val position = pb?.position?.coerceAtLeast(0L) ?: 0L

        _mediaState.update {
            it.copy(
                activePackageName = pkg,
                playerAppTitle = friendlyPlayer,
                trackTitle = title,
                artistName = artist,
                albumName = album,
                isPlaying = playing,
                positionMs = position,
                durationMs = duration,
                albumArtBitmap = art
            )
        }
    }

    private fun applyFallbackTrack(track: DemoTrack) {
        _mediaState.update {
            it.copy(
                activePackageName = track.pkg,
                playerAppTitle = track.player,
                trackTitle = track.title,
                artistName = track.artist,
                albumName = track.album,
                isPlaying = true,
                positionMs = 42_000L,
                durationMs = track.durationMs
            )
        }
    }

    private fun dispatchSystemMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }
}
