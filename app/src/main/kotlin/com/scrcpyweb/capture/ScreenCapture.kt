package com.scrcpyweb.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface

/**
 * Manages MediaProjection lifecycle for screen capture.
 * Wraps VirtualDisplay creation and teardown.
 *
 * @param context Application context used to obtain system services.
 */
class ScreenCapture(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** Called when the system stops the projection (e.g. user revokes permission). */
    var onStopped: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection = null
            onStopped?.invoke()
        }
    }

    /**
     * Launches the system screen capture permission dialog.
     *
     * @param activity The activity used to start the permission intent.
     */
    fun requestPermission(activity: Activity) {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    /**
     * Starts screen capture, outputting frames to the provided [surface].
     *
     * @param resultCode  The result code from the MediaProjection permission result.
     * @param data        The intent data from the MediaProjection permission result.
     * @param surface     The Surface that will receive captured frames (from VideoEncoder).
     * @param width       Capture width in pixels.
     * @param height      Capture height in pixels.
     * @param dpi         Screen density in DPI.
     */
    fun start(resultCode: Int, data: Intent, surface: Surface, width: Int, height: Int, dpi: Int) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)
        virtualDisplay = projection.createVirtualDisplay(
            "ScrcpyWebCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    /**
     * Stops screen capture and releases all associated resources.
     */
    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    companion object {
        const val REQUEST_CODE = 1001
    }
}
