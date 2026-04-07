package com.scrcpyweb.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scrcpyweb.service.MirrorService

/**
 * Transparent Activity that launches the MediaProjection permission dialog
 * on behalf of [MirrorService] when a browser client requests capture.
 *
 * This Activity has no visible UI — it exists solely because
 * [android.media.projection.MediaProjectionManager.createScreenCaptureIntent]
 * requires an Activity context and, on Android 14+, the foreground service
 * with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` must be started from within
 * the activity-result callback.
 *
 * Lifecycle:
 *  1. Launched by [MirrorService.requestCaptureFromBrowser] with [FLAG_ACTIVITY_NEW_TASK].
 *  2. [onCreate] immediately fires the system MediaProjection consent dialog.
 *  3. On result, forwards the token to [MirrorService] (or broadcasts failure).
 *  4. Finishes itself — the user never sees this Activity's window.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_START_CAPTURE
                putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MirrorService.EXTRA_PROJECTION_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            // User denied or dialog was dismissed — notify WebSocket clients.
            MirrorService.instance?.broadcastCaptureFailed("user_denied")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake the screen and show above the lock screen so the system
        // MediaProjection dialog is visible even when the device is locked.
        setTurnScreenOn(true)
        setShowWhenLocked(true)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
