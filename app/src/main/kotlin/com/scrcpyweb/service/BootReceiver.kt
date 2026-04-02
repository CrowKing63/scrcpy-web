package com.scrcpyweb.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Broadcast receiver that automatically starts [MirrorService] when the
 * device finishes booting.
 *
 * The service will start the web server so the device is immediately reachable
 * over the local network. Screen capture remains off until the user explicitly
 * grants MediaProjection permission through the [com.scrcpyweb.ui.MainActivity].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, MirrorService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
