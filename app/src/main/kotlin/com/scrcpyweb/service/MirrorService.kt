package com.scrcpyweb.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.scrcpyweb.ui.ProjectionRequestActivity
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import com.scrcpyweb.capture.FMP4Muxer
import com.scrcpyweb.capture.ScreenCapture
import com.scrcpyweb.capture.VideoEncoder
import com.scrcpyweb.server.WebServer
import com.scrcpyweb.ui.MainActivity
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service that orchestrates the entire screen mirroring pipeline.
 *
 * Lifecycle:
 *  1. [onCreate] — starts the Ktor web server and posts a persistent notification.
 *  2. [startCapture] — wires ScreenCapture → VideoEncoder → FMP4Muxer → WebSocket.
 *  3. [stopCapture]  — tears down the capture pipeline in reverse order.
 *  4. [onDestroy]   — stops the web server and cleans up all resources.
 */
class MirrorService : Service() {

    private var webServer: WebServer? = null
    private var screenCapture: ScreenCapture? = null
    private var videoEncoder: VideoEncoder? = null
    private var fmp4Muxer: FMP4Muxer? = null

    /**
     * Partial wake lock acquired while capturing to keep the CPU running if the
     * screen dims.  A full screen-on lock is not used here because on Android 14+
     * the deprecated SCREEN_DIM_WAKE_LOCK is silently downgraded to PARTIAL by
     * the platform.  Keeping the CPU alive at minimum ensures the Ktor server and
     * encoder threads continue processing even with the display off.
     *
     * To prevent the screen from locking while mirroring (and thus stopping
     * MediaProjection on Android 14+), the MainActivity sets FLAG_KEEP_SCREEN_ON
     * on its window while [isCapturing] is true.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /** True while the screen capture pipeline is active. */
    var isCapturing: Boolean = false
        private set

    /** Saved MediaProjection result code for restarting capture after rotation. */
    private var savedProjectionResultCode: Int = 0

    /** Saved MediaProjection result intent data for restarting capture after rotation. */
    private var savedProjectionData: android.content.Intent? = null

    // ─────────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Start foreground with DATA_SYNC type — safe to call from any context.
        // The mediaProjection type is added later in onStartCommand when capture is requested.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getWifiIpAddress()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        startWebServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_CAPTURE) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            if (resultCode != 0 && projectionData != null) {
                // Upgrade the foreground type to include mediaProjection.
                // This call is valid because startForegroundService() was invoked from
                // within the MediaProjection activity-result callback (Android 14+ requirement).
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getWifiIpAddress()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                startCapture(resultCode, projectionData)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        webServer?.stop()
        webServer = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────
    //  Web server
    // ─────────────────────────────────────────────────────────

    private fun startWebServer() {
        webServer = WebServer(port = 8080, assetManager = assets).also { server ->
            server.onDeviceInfoRequest = { getDeviceInfo() }
            server.onStartCapture = {
                // Browser requested capture start — delegate to the full
                // request flow which tries the saved token first, then falls
                // back to launching the transparent permission Activity.
                requestCaptureFromBrowser()
                true
            }
            server.onStopCapture = { stopCapture() }
            server.streamSession.onRestartCapture = {
                val rc = savedProjectionResultCode
                val pd = savedProjectionData
                if (rc != 0 && pd != null) {
                    // Restart even if isCapturing is true: the encoder may be stalled
                    // (e.g. static screen content after a long idle period with no clients).
                    // startCapture() always calls stopCapture() first, so this is safe.
                    try { startCapture(rc, pd); true }
                    catch (e: Exception) { e.printStackTrace(); false }
                } else false
            }
            server.streamSession.onRequestCapture = {
                requestCaptureFromBrowser()
            }
            server.start()
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Browser-initiated capture request
    // ─────────────────────────────────────────────────────────

    /**
     * Handles a capture request originating from a browser client.
     *
     * Attempts to reuse the saved MediaProjection token first. If no valid
     * token exists (or it has been invalidated), wakes the screen if necessary
     * and launches [ProjectionRequestActivity] to obtain fresh consent.
     * The [TouchInjectionService] auto-tap is enabled so the "Allow" button
     * on the system consent dialog is tapped automatically.
     */
    fun requestCaptureFromBrowser() {
        if (isCapturing) return

        // Try the saved token first — avoids showing the permission dialog
        // if the token is still valid (e.g. capture stopped but process alive).
        val rc = savedProjectionResultCode
        val pd = savedProjectionData
        if (rc != 0 && pd != null) {
            try {
                startCapture(rc, pd)
                return
            } catch (_: Exception) {
                // Token expired or invalidated — fall through to fresh request.
            }
        }

        // Wake the screen if it is off so the system permission dialog is visible.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "scrcpyweb:wakeForProjection"
            )
            wl.acquire(5000L)
        }

        // Enable auto-tap so the AccessibilityService taps "Allow" automatically.
        TouchInjectionService.instance?.enableAutoTap()

        // Launch the transparent Activity that fires createScreenCaptureIntent().
        val intent = Intent(this, ProjectionRequestActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Broadcasts a capture failure message to all connected WebSocket clients.
     *
     * Called by [ProjectionRequestActivity] when the user denies the
     * MediaProjection consent dialog.
     *
     * @param reason Short machine-readable reason string (e.g. "user_denied").
     */
    fun broadcastCaptureFailed(reason: String) {
        webServer?.streamSession?.broadcastCaptureFailed(reason)
    }

    // ─────────────────────────────────────────────────────────
    //  Capture pipeline
    // ─────────────────────────────────────────────────────────

    /**
     * Starts the screen capture → encode → mux → stream pipeline.
     *
     * @param resultCode MediaProjection permission result code.
     * @param data       MediaProjection permission result intent data.
     */
    fun startCapture(resultCode: Int, data: Intent) {
        // Always tear down the previous pipeline — even when isCapturing is false
        // (e.g. after an onStopped callback that only partially cleaned up).
        stopCapture()
        savedProjectionResultCode = resultCode
        savedProjectionData = data

        // Acquire a partial wake lock to keep the CPU alive while mirroring.
        // This prevents the Ktor server and encoder threads from being suspended
        // if the display dims.  The screen itself is kept on by FLAG_KEEP_SCREEN_ON
        // set on MainActivity's window (see updateUiFromService).
        // No timeout — the lock is explicitly released in stopCapture().
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { it.acquire() }

        val metrics = getScreenMetrics()
        val scale = getPreferredScale()
        val width = (metrics.widthPixels * scale).toInt().roundToEven()
        val height = (metrics.heightPixels * scale).toInt().roundToEven()
        val dpi = metrics.densityDpi
        val bitrate = getPreferredBitrate()
        val fps = getPreferredFps()

        videoEncoder = VideoEncoder(width, height, bitrate, fps).also { encoder ->
            encoder.onSpsAvailable = { sps, pps ->
                fmp4Muxer = FMP4Muxer(width, height, sps, pps, fps)
                val initSegment = fmp4Muxer!!.generateInitSegment()
                webServer?.streamSession?.updateInitSegment(initSegment)
            }

            encoder.onEncodedFrame = { buffer, info ->
                val muxer = fmp4Muxer
                if (muxer != null) {
                    val frameData = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(frameData)
                    val isKeyFrame = (info.flags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        || containsIdrNalu(frameData)
                    val segment = muxer.muxFrame(frameData, isKeyFrame, info.presentationTimeUs, info.presentationTimeUs)
                    webServer?.streamSession?.sendFrameToAll(segment, isKeyFrame)
                }
            }

            encoder.start()

            webServer?.streamSession?.onClientConnected = { isFirstClient ->
                // When the first client connects after a period with no viewers,
                // reset the muxer sequence so the browser receives segments
                // starting at sequence 1 right after the init segment.
                if (isFirstClient) {
                    fmp4Muxer?.resetSequence()
                }
                encoder.requestKeyframe()
            }

            screenCapture = ScreenCapture(this).also { capture ->
                capture.onStopped = {
                    // MediaProjection was revoked (e.g. screen lock on Android 14+).
                    // Perform a full pipeline teardown so encoder/muxer do not
                    // linger in a zombie state.  stopCapture() is safe to call
                    // here because the projection is already stopped and its
                    // fields were nulled by the ScreenCapture callback.
                    stopCapture()
                }
                capture.start(resultCode, data, encoder.getInputSurface(), width, height, dpi)
            }
        }

        isCapturing = true
        webServer?.streamSession?.isCapturing = true
        updateNotification()
        webServer?.streamSession?.broadcastCaptureState(true)
    }

    /**
     * Stops the capture pipeline and releases all resources in reverse init order.
     */
    fun stopCapture() {
        webServer?.streamSession?.isCapturing = false
        webServer?.streamSession?.broadcastCaptureState(false)
        screenCapture?.stop()
        screenCapture = null
        videoEncoder?.stop()
        videoEncoder = null
        fmp4Muxer = null
        wakeLock?.release()
        wakeLock = null
        isCapturing = false
        updateNotification()
    }

    /**
     * Handles a screen configuration change (e.g. rotation) by restarting the
     * capture pipeline with fresh screen dimensions.
     *
     * No-op if no valid MediaProjection token has been saved yet.
     */
    fun handleConfigChange() {
        val resultCode = savedProjectionResultCode
        val data = savedProjectionData ?: return
        if (resultCode == 0) return
        stopCapture()
        startCapture(resultCode, data)
    }

    // ─────────────────────────────────────────────────────────
    //  Device info
    // ─────────────────────────────────────────────────────────

    /**
     * Collects current device information for the REST API response.
     *
     * @return Map of device metadata: model, OS version, screen dimensions,
     *         battery level, and the current Wi-Fi IP address.
     */
    fun getDeviceInfo(): Map<String, Any> {
        val metrics = getScreenMetrics()
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        return mapOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE,
            "screenWidth" to metrics.widthPixels,
            "screenHeight" to metrics.heightPixels,
            "batteryLevel" to batteryLevel,
            "ipAddress" to getWifiIpAddress()
        )
    }

    /**
     * Attempts to resolve the device's IPv4 address on the active Wi-Fi or
     * Ethernet interface. Falls back to "0.0.0.0" if no address is found.
     *
     * @return IPv4 address string (e.g. "192.168.1.42").
     */
    fun getWifiIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Notification helpers
    // ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.scrcpyweb.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(ip: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (isCapturing) {
            getString(com.scrcpyweb.R.string.notification_text_active,
                webServer?.streamSession?.clientCount() ?: 0)
        } else {
            getString(com.scrcpyweb.R.string.notification_text_idle, "http://$ip:8080")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.scrcpyweb.R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(getWifiIpAddress()))
    }

    // ─────────────────────────────────────────────────────────
    //  Preference helpers
    // ─────────────────────────────────────────────────────────

    private fun getPreferredScale(): Float {
        return getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)
            .getFloat("scale", 0.75f)
    }

    private fun getPreferredBitrate(): Int {
        return getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)
            .getInt("bitrate", 4_000_000)
    }

    private fun getPreferredFps(): Int {
        return getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)
            .getInt("fps", 30)
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        return dm
    }

    // ─────────────────────────────────────────────────────────
    //  Companion
    // ─────────────────────────────────────────────────────────

    companion object {
        /** Singleton reference to the running service, or null if not running. */
        @Volatile
        var instance: MirrorService? = null

        /** Intent action to start screen capture from a MediaProjection result callback. */
        const val ACTION_START_CAPTURE = "com.scrcpyweb.action.START_CAPTURE"

        /** Intent extra key for the MediaProjection result code. */
        const val EXTRA_RESULT_CODE = "extra_result_code"

        /** Intent extra key for the MediaProjection result intent data. */
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        private const val CHANNEL_ID     = "scrcpy_web_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG   = "scrcpyweb:mirror"
    }
}

/** Rounds an Int up to the nearest even number (required by some H264 encoders). */
private fun Int.roundToEven(): Int = if (this % 2 == 0) this else this + 1

/**
 * Returns true if [data] (Annex B format) contains at least one IDR slice NALU (type 5).
 *
 * Used as a fallback when [android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME] is not
 * reliably set by some hardware encoders — a known quirk on certain Android devices.
 */
private fun containsIdrNalu(data: ByteArray): Boolean {
    var i = 0
    while (i < data.size) {
        if (i + 3 < data.size &&
            data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
            data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
        ) {
            val naluStart = i + 4
            if (naluStart < data.size && (data[naluStart].toInt() and 0x1F) == 5) return true
            i = naluStart
            continue
        }
        if (i + 2 < data.size &&
            data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
            data[i + 2] == 1.toByte()
        ) {
            val naluStart = i + 3
            if (naluStart < data.size && (data[naluStart].toInt() and 0x1F) == 5) return true
            i = naluStart
            continue
        }
        i++
    }
    return false
}
