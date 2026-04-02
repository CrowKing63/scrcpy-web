package com.scrcpyweb.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service responsible for injecting remote touch gestures and
 * navigation actions received from the browser over WebSocket.
 *
 * This service does NOT monitor or intercept any accessibility events.
 * Its sole capability is gesture injection via [dispatchGesture].
 *
 * Input coordinates are expected in normalised form (0.0–1.0) and are
 * converted to physical pixels using the current display metrics.
 */
class TouchInjectionService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** No-op: this service does not monitor accessibility events. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** No-op: interrupts are not applicable for a gesture-only service. */
    override fun onInterrupt() = Unit

    // ─────────────────────────────────────────────────────────
    //  Public injection API
    // ─────────────────────────────────────────────────────────

    /**
     * Injects a tap gesture at the given normalised screen coordinates.
     *
     * @param x Normalised horizontal position (0.0 = left, 1.0 = right).
     * @param y Normalised vertical position (0.0 = top, 1.0 = bottom).
     */
    fun injectTap(x: Float, y: Float) {
        val metrics = resources.displayMetrics
        val px = x * metrics.widthPixels
        val py = y * metrics.heightPixels

        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Injects a swipe gesture between two normalised screen positions.
     *
     * @param x1       Normalised start X.
     * @param y1       Normalised start Y.
     * @param x2       Normalised end X.
     * @param y2       Normalised end Y.
     * @param duration Swipe duration in milliseconds (default 300 ms).
     */
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300L) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        val path = Path().apply {
            moveTo(x1 * w, y1 * h)
            lineTo(x2 * w, y2 * h)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Injects a scroll gesture by simulating a short swipe opposite to the scroll direction.
     *
     * @param x  Normalised anchor X of the scroll.
     * @param y  Normalised anchor Y of the scroll.
     * @param dx Horizontal scroll delta (pixels, browser coordinate space).
     * @param dy Vertical scroll delta (pixels, browser coordinate space).
     */
    fun injectScroll(x: Float, y: Float, dx: Float, dy: Float) {
        val scrollScale = 0.3f
        val x2 = (x - dx * scrollScale / resources.displayMetrics.widthPixels).coerceIn(0f, 1f)
        val y2 = (y - dy * scrollScale / resources.displayMetrics.heightPixels).coerceIn(0f, 1f)
        injectSwipe(x, y, x2, y2, duration = 200L)
    }

    /**
     * Performs a global navigation action.
     *
     * @param action One of: "back", "home", "recents", "power", "notifications",
     *               "volumeUp", "volumeDown".
     */
    fun performNavAction(action: String) {
        when (action) {
            "back"          -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home"          -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents"       -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "power"         -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            // volumeUp / volumeDown require key injection — handled via key events
        }
    }

    companion object {
        /** Singleton reference set when the service connects, cleared on unbind. */
        @Volatile
        var instance: TouchInjectionService? = null
    }
}
