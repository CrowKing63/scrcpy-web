package com.scrcpyweb.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.scrcpyweb.server.StreamSession
import org.json.JSONObject

/**
 * Service that listens for incoming notifications and forwards them to the web dashboard.
 */
class NotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return

        val packageName = sbn.packageName
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
        val timestamp = sbn.postTime

        Log.d(TAG, "Notification received from $packageName: $title - $text")

        val json = JSONObject().apply {
            put("type", "notification")
            put("packageName", packageName)
            put("title", title)
            put("text", text)
            put("subText", subText)
            put("timestamp", timestamp)
        }

        val mirrorService = MirrorService.instance
        if (mirrorService != null) {
            mirrorService.broadcastStatus(json.toString())
        } else {
            Log.w(TAG, "MirrorService instance is null, cannot broadcast notification")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "NotificationService"
        var instance: NotificationService? = null
            private set
    }
}
