package com.scrcpyweb.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.AudioManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service responsible for injecting remote touch gestures,
 * navigation actions, and key input received from the browser over WebSocket.
 *
 * This service does NOT monitor or intercept any accessibility events.
 * Its capabilities are gesture injection ([dispatchGesture]) and node-level
 * actions for text-field interaction ([injectKey]).
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

        val path    = Path().apply { moveTo(px, py) }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Injects a long-press gesture at the given normalised screen coordinates.
     *
     * A 600 ms stationary press triggers the Android long-click / context-menu
     * behaviour in most apps, equivalent to a right-click on a connected mouse.
     *
     * @param x Normalised horizontal position (0.0 = left, 1.0 = right).
     * @param y Normalised vertical position (0.0 = top, 1.0 = bottom).
     */
    fun injectLongPress(x: Float, y: Float) {
        val metrics = resources.displayMetrics
        val px = x * metrics.widthPixels
        val py = y * metrics.heightPixels

        val path    = Path().apply { moveTo(px, py) }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 600L)
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
        val stroke  = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Injects a scroll gesture by simulating a short swipe opposite to the scroll direction.
     *
     * The normalisation converts browser-pixel scroll deltas to a proportional
     * fraction of the Android screen: 600 browser pixels maps to a full-screen swipe.
     *
     * @param x  Normalised anchor X of the scroll.
     * @param y  Normalised anchor Y of the scroll.
     * @param dx Horizontal scroll delta (CSS pixels, browser coordinate space).
     * @param dy Vertical scroll delta (CSS pixels, browser coordinate space).
     */
    fun injectScroll(x: Float, y: Float, dx: Float, dy: Float) {
        // 600 CSS-pixel scroll delta ≈ one full screen swipe.
        val scrollNorm = 1f / 600f
        val x2 = (x - dx * scrollNorm).coerceIn(0f, 1f)
        val y2 = (y - dy * scrollNorm).coerceIn(0f, 1f)
        injectSwipe(x, y, x2, y2, duration = 200L)
    }

    /**
     * Injects a key event into the currently focused input field.
     *
     * Uses [AccessibilityNodeInfo.ACTION_SET_TEXT] to append a character, or
     * [AccessibilityNodeInfo.ACTION_CUT] / paste / selectAll for edit actions.
     * Requires [android.content.res.Configuration] `canRetrieveWindowContent = true`
     * in the accessibility service configuration.
     *
     * @param keyCode   Android key code (e.g. 67 = Backspace, 66 = Enter, 7–16 = 0–9).
     * @param metaState Modifier-key bitmask (currently unused; reserved for shift/caps).
     */
    fun injectKey(keyCode: Int, metaState: Int = 0) {
        val root         = rootInActiveWindow ?: return
        val focusedNode  = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val hasShift = (metaState and META_SHIFT_MASK) != 0
            when (keyCode) {
                KEYCODE_BACKSPACE -> {
                    val text = focusedNode.text?.toString() ?: ""
                    if (text.isNotEmpty()) setNodeText(focusedNode, text.dropLast(1))
                }
                KEYCODE_ENTER -> {
                    // Append a newline — works for multi-line fields.
                    // Single-line fields typically handle \n as a submit.
                    val text = focusedNode.text?.toString() ?: ""
                    setNodeText(focusedNode, "$text\n")
                }
                else -> {
                    val ch = keycodeToChar(keyCode, hasShift) ?: run {
                        focusedNode.recycle()
                        root.recycle()
                        return
                    }
                    val text = focusedNode.text?.toString() ?: ""
                    setNodeText(focusedNode, text + ch)
                }
            }
            focusedNode.recycle()
        }
        root.recycle()
    }

    /**
     * Performs a global navigation action or an edit action on the focused node.
     *
     * Volume adjustment is handled via [AudioManager] and does not require the
     * device to have a focused input node.
     *
     * @param action One of: "back", "home", "recents", "power", "notifications",
     *               "volumeUp", "volumeDown", "copy", "cut", "paste", "selectAll".
     */
    fun performNavAction(action: String) {
        when (action) {
            "back"          -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home"          -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents"       -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "power"         -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "volumeUp"      -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volumeDown"    -> adjustVolume(AudioManager.ADJUST_LOWER)
            "copy", "cut", "paste", "selectAll" -> performNodeEditAction(action)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────

    /** Adjusts the media stream volume using [AudioManager]. */
    private fun adjustVolume(direction: Int) {
        (getSystemService(AUDIO_SERVICE) as? AudioManager)
            ?.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
    }

    /**
     * Performs a clipboard edit action on the currently focused input node.
     *
     * @param action One of "copy", "cut", "paste", "selectAll".
     */
    private fun performNodeEditAction(action: String) {
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null) {
            when (action) {
                "copy"      -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                "cut"       -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                "paste"     -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                "selectAll" -> node.performAction(0x00100000) // ACTION_SELECT_ALL
            }
            node.recycle()
        }
        root.recycle()
    }

    /**
     * Updates the text of an [AccessibilityNodeInfo] using ACTION_SET_TEXT.
     *
     * @param node Target node (caller is responsible for recycling).
     * @param text Replacement text.
     */
    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val bundle = Bundle()
        bundle.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    /**
     * Maps an Android key code to a printable character.
     *
     * Handles digits (KEYCODE_0–KEYCODE_9), letters (KEYCODE_A–KEYCODE_Z), and space.
     * Returns null for unmapped codes.
     *
     * @param keyCode  Android key code.
     * @param hasShift Whether the Shift modifier is active (uppercases letters).
     */
    private fun keycodeToChar(keyCode: Int, hasShift: Boolean): Char? {
        return when (keyCode) {
            // Digits: KEYCODE_0 = 7, KEYCODE_9 = 16
            7  -> '0'; 8  -> '1'; 9  -> '2'; 10 -> '3'; 11 -> '4'
            12 -> '5'; 13 -> '6'; 14 -> '7'; 15 -> '8'; 16 -> '9'
            // Letters: KEYCODE_A = 29, KEYCODE_Z = 54
            29 -> if (hasShift) 'A' else 'a'
            30 -> if (hasShift) 'B' else 'b'
            31 -> if (hasShift) 'C' else 'c'
            32 -> if (hasShift) 'D' else 'd'
            33 -> if (hasShift) 'E' else 'e'
            34 -> if (hasShift) 'F' else 'f'
            35 -> if (hasShift) 'G' else 'g'
            36 -> if (hasShift) 'H' else 'h'
            37 -> if (hasShift) 'I' else 'i'
            38 -> if (hasShift) 'J' else 'j'
            39 -> if (hasShift) 'K' else 'k'
            40 -> if (hasShift) 'L' else 'l'
            41 -> if (hasShift) 'M' else 'm'
            42 -> if (hasShift) 'N' else 'n'
            43 -> if (hasShift) 'O' else 'o'
            44 -> if (hasShift) 'P' else 'p'
            45 -> if (hasShift) 'Q' else 'q'
            46 -> if (hasShift) 'R' else 'r'
            47 -> if (hasShift) 'S' else 's'
            48 -> if (hasShift) 'T' else 't'
            49 -> if (hasShift) 'U' else 'u'
            50 -> if (hasShift) 'V' else 'v'
            51 -> if (hasShift) 'W' else 'w'
            52 -> if (hasShift) 'X' else 'x'
            53 -> if (hasShift) 'Y' else 'y'
            54 -> if (hasShift) 'Z' else 'z'
            // Space
            62 -> ' '
            else -> null
        }
    }

    companion object {
        /** Singleton reference set when the service connects, cleared on unbind. */
        @Volatile
        var instance: TouchInjectionService? = null

        private const val KEYCODE_BACKSPACE = 67
        private const val KEYCODE_ENTER     = 66
        /** Combined Shift-key bitmask (left, right, or caps-lock). */
        private const val META_SHIFT_MASK   = 0x41  // META_SHIFT_ON | META_CAPS_LOCK_ON
    }
}
