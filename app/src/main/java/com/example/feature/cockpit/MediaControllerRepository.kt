package com.example.feature.cockpit

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Compatibility snapshot mirroring [MediaState] for existing Cockpit callers.
 */
data class CockpitMediaState(
    val hasNotificationAccess: Boolean = false,
    val activePackageName: String = "",
    val playerAppTitle: String = "Медиаплеер",
    val trackTitle: String = "Нет активного трека",
    val artistName: String = "Запустите музыку на телефоне",
    val albumName: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val albumArtBitmap: Bitmap? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

private fun MediaState.toCockpitMediaState(): CockpitMediaState = CockpitMediaState(
    hasNotificationAccess = hasNotificationAccess,
    activePackageName = packageName,
    playerAppTitle = playerAppTitle,
    trackTitle = title,
    artistName = artist,
    albumName = "",
    isPlaying = isPlaying,
    positionMs = positionMs,
    durationMs = durationMs,
    albumArtBitmap = albumArt
)

/**
 * Facade delegating to [MediaPlayerController] so all existing callers (hard keys,
 * Canvas fallback, onboarding checklist) automatically share the single [MediaSessionManager] controller.
 */
object MediaControllerRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val mediaState: StateFlow<CockpitMediaState> = MediaPlayerController.mediaState
        .map { it.toCockpitMediaState() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = MediaPlayerController.mediaState.value.toCockpitMediaState()
        )

    fun initialize(context: Context) = MediaPlayerController.initialize(context)

    fun onNotificationListenerStateChanged(connected: Boolean) =
        MediaPlayerController.onNotificationListenerStateChanged(connected)

    fun refreshSessions() = MediaPlayerController.refreshSessions()

    fun togglePlayPause() = MediaPlayerController.playPause()

    fun skipNext() = MediaPlayerController.next()

    fun skipPrevious() = MediaPlayerController.previous()

    fun openNotificationListenerSettings(context: Context) =
        MediaPlayerController.openNotificationListenerSettings(context)
}
