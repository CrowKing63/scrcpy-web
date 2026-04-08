package com.scrcpyweb.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scrcpyweb.service.MirrorService
import com.scrcpyweb.service.TouchInjectionService

/**
 * Transparent Activity that launches the MediaProjection permission dialog
 * on behalf of [MirrorService] when a browser client requests capture.
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
            MirrorService.instance?.broadcastCaptureFailed("user_denied")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        armAutoTap()

        setTurnScreenOn(true)
        setShowWhenLocked(true)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        armAutoTap()
    }

    private fun armAutoTap() {
        val service = TouchInjectionService.instance
        if (service != null) {
            Log.d("ProjectionRequest", "Arming auto-tap")
            service.enableAutoTap()
        } else {
            Log.w("ProjectionRequest", "TouchInjectionService instance is null")
        }
    }
}
