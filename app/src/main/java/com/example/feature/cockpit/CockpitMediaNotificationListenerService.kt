package com.example.feature.cockpit

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Required Android [NotificationListenerService] component that authorizes [MediaControllerRepository]
 * to query `MediaSessionManager.getActiveSessions(...)` across all music players running on
 * the Samsung Galaxy S26 Ultra (Yandex Music, Spotify, Poweramp, YouTube Music).
 */
class CockpitMediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "CockpitMediaNotificationListenerService connected")
        MediaControllerRepository.onNotificationListenerStateChanged(true)
    }

    override fun onListenerDisconnected() {
        MediaControllerRepository.onNotificationListenerStateChanged(false)
        super.onListenerDisconnected()
    }

    companion object {
        private const val TAG = "CockpitMediaNLS"
    }
}
