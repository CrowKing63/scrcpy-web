package com.scrcpyweb.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.scrcpyweb.R
import com.scrcpyweb.service.MirrorService
import com.scrcpyweb.service.TouchInjectionService

/**
 * Main entry point activity.
 *
 * Handles:
 *  - Starting / stopping [MirrorService]
 *  - Launching the MediaProjection permission dialog and forwarding the result
 *    to [MirrorService.startCapture]
 *  - Opening the Accessibility Settings page for [TouchInjectionService]
 *  - Displaying the current Wi-Fi IP address and server status
 *  - Persisting user settings (resolution, bitrate, FPS) via SharedPreferences
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateUiFromService()
            handler.postDelayed(this, UI_POLL_INTERVAL_MS)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ActivityResultLauncher for MediaProjection permission
    // ─────────────────────────────────────────────────────────

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // On Android 14+, the foreground service with mediaProjection type must be
            // started (via startForegroundService) from within the activity result callback.
            // We pass the projection data via intent so MirrorService.onStartCommand() can
            // call startForeground(MEDIA_PROJECTION) and getMediaProjection() in that context.
            val intent = Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_START_CAPTURE
                putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MirrorService.EXTRA_PROJECTION_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupButtons()
        setupSliders()
        loadPreferences()
    }

    override fun onResume() {
        super.onResume()
        handler.post(uiUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiUpdateRunnable)
    }

    // ─────────────────────────────────────────────────────────
    //  Button setup
    // ─────────────────────────────────────────────────────────

    private fun setupButtons() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartServer)
            .setOnClickListener { startMirrorService() }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStopServer)
            .setOnClickListener { stopMirrorService() }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGrantPermission)
            .setOnClickListener { requestMediaProjectionPermission() }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAccessibility)
            .setOnClickListener { openAccessibilitySettings() }
    }

    private fun startMirrorService() {
        val intent = Intent(this, MirrorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        handler.postDelayed({ updateUiFromService() }, 500)
    }

    private fun stopMirrorService() {
        MirrorService.instance?.stopCapture()
        stopService(Intent(this, MirrorService::class.java))
        updateUiFromService()
    }

    private fun requestMediaProjectionPermission() {
        val service = MirrorService.instance ?: run {
            startMirrorService()
            handler.postDelayed({ requestMediaProjectionPermission() }, 800)
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ─────────────────────────────────────────────────────────
    //  Sliders
    // ─────────────────────────────────────────────────────────

    private fun setupSliders() {
        val prefs = getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)

        findViewById<Slider>(R.id.sliderResolution).addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.edit().putFloat("scale", value).apply()
        }

        findViewById<Slider>(R.id.sliderBitrate).addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.edit().putInt("bitrate", (value * 1_000_000).toInt()).apply()
        }

        findViewById<Slider>(R.id.sliderFps).addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.edit().putInt("fps", value.toInt()).apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)
        findViewById<Slider>(R.id.sliderResolution).value = prefs.getFloat("scale", 0.75f)
        findViewById<Slider>(R.id.sliderBitrate).value =
            (prefs.getInt("bitrate", 4_000_000) / 1_000_000f).coerceIn(1f, 8f)
        findViewById<Slider>(R.id.sliderFps).value =
            prefs.getInt("fps", 30).toFloat().coerceIn(15f, 60f)
    }

    // ─────────────────────────────────────────────────────────
    //  UI state update
    // ─────────────────────────────────────────────────────────

    /**
     * Polls [MirrorService] state and refreshes the UI accordingly.
     * Called on a 1-second interval while the activity is resumed.
     */
    private fun updateUiFromService() {
        val service = MirrorService.instance
        val isRunning = service != null
        val isCapturing = service?.isCapturing == true
        val isAccessibilityEnabled = TouchInjectionService.instance != null

        val statusDot = findViewById<android.view.View>(R.id.statusDot)
        val statusText = findViewById<android.widget.TextView>(R.id.statusText)
        val ipText = findViewById<android.widget.TextView>(R.id.ipAddressText)
        val btnStart = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartServer)
        val btnStop = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStopServer)
        val btnAccessibility = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAccessibility)

        when {
            isCapturing -> {
                statusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF76FF03.toInt())
                statusText.text = getString(R.string.status_streaming)
            }
            isRunning -> {
                statusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF00BCD4.toInt())
                statusText.text = getString(R.string.status_waiting)
            }
            else -> {
                statusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF9E9E9E.toInt())
                statusText.text = getString(R.string.status_idle)
            }
        }

        val ip = service?.getWifiIpAddress() ?: "—"
        ipText.text = "http://$ip:8080"

        btnStart.visibility = if (isRunning) android.view.View.GONE else android.view.View.VISIBLE
        btnStop.visibility = if (isRunning) android.view.View.VISIBLE else android.view.View.GONE

        btnAccessibility.text = if (isAccessibilityEnabled)
            "Accessibility Service: Enabled" else "Enable Accessibility Service"
        val accessColor = if (isAccessibilityEnabled) 0xFF1A3A1A.toInt() else 0xFF2A2A3E.toInt()
        btnAccessibility.backgroundTintList =
            android.content.res.ColorStateList.valueOf(accessColor)
    }

    companion object {
        private const val UI_POLL_INTERVAL_MS = 1000L
    }
}
