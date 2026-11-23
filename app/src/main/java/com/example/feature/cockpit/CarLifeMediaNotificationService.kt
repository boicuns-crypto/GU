package com.example.feature.cockpit

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * [NotificationListenerService] granting [MediaPlayerController] access to
 * `MediaSessionManager.getActiveSessions(componentName)` across all installed music players
 * (Яндекс Музыка, Spotify, VK Музыка, Poweramp, YouTube Music и др.).
 */
class CarLifeMediaNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "CarLifeMediaNotificationService connected")
        MediaPlayerController.onNotificationListenerStateChanged(true)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "CarLifeMediaNotificationService disconnected")
        MediaPlayerController.onNotificationListenerStateChanged(false)
        super.onListenerDisconnected()
    }

    companion object {
        private const val TAG = "CarLifeMediaNLS"
    }
}
