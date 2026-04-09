package com.scrcpyweb.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.IOException

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

    private val autoTapHandler = Handler(Looper.getMainLooper())

    /**
     * When true, [onAccessibilityEvent] will attempt to auto-tap through the
     * MediaProjection consent dialog. Resets to false after the final
     * confirmation button is clicked or after a 10-second timeout.
     */
    @Volatile
    private var autoTapEnabled = false

    /** Timestamp of the last auto-tap processing attempt, used for debouncing. */
    private var lastAutoTapAttempt = 0L

    /**
     * Tracks whether the full-screen share mode option has been selected in the
     * Samsung One UI MediaProjection consent dialog. Reset each time [enableAutoTap]
     * is called so that each new mirroring session starts fresh.
     */
    @Volatile
    private var fullScreenModeSelected = false

    /**
     * Tracks whether the share-mode dropdown has been opened in the
     * Samsung One UI MediaProjection consent dialog (Android 16+).
     * Reset each time [enableAutoTap] is called.
     */
    @Volatile
    private var dropDownOpened = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        autoTapHandler.removeCallbacksAndMessages(null)
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * Detects the MediaProjection consent dialog and auto-taps through it.
     *
     * Instead of matching specific positive-button texts (which vary by OEM,
     * Android version, locale, and dialog step), this uses the **cancel button
     * as an anchor** and clicks its sibling — the positive action button.
     * Cancel/deny button text is highly consistent across all variants.
     *
     * Handles multi-step dialogs (e.g. Samsung One UI: select share mode →
     * "Next" → "Allow") by staying active until the 10-second timeout expires.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return

        // COORDINATE_TESTER: Log clicked nodes for debugging
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            event.source?.let { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val metrics = resources.displayMetrics
                val nx = rect.centerX().toFloat() / metrics.widthPixels
                val ny = rect.centerY().toFloat() / metrics.heightPixels
                Log.d(TAG, "COORDINATE_TESTER: Clicked '${node.text ?: node.contentDescription ?: "null"}' at ($nx, $ny)")
                node.recycle()
            }
        }

        if (!autoTapEnabled) return
        
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val now = System.currentTimeMillis()
        // Samsung UI transitions require instant response. Reduced debounce for content changes.
        val debounce = if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) 50L else 150L
        if (now - lastAutoTapAttempt < debounce) return
        lastAutoTapAttempt = now

        val roots = collectWindowRoots()
        if (roots.isEmpty()) return

        try {
            // Priority: If full screen mode is already selected, focus on clicking the positive button ASAP.
            if (fullScreenModeSelected) {
                if (attemptClickPositiveButton(roots)) return
            }

            // Step 1: Samsung One UI Share Mode Selection
            if (!fullScreenModeSelected) {
                var justSelected = false
                for (root in roots) {
                    if (trySelectFullScreenMode(root)) {
                        fullScreenModeSelected = true
                        justSelected = true
                        Log.d(TAG, "Selected full-screen mode. Trying to click start button immediately...")
                        break
                    }
                }

                if (justSelected) {
                    // Try immediate click with current roots for maximum speed
                    if (attemptClickPositiveButton(roots)) return
                }

                if (!fullScreenModeSelected && !dropDownOpened) {
                    for (root in roots) {
                        if (tryOpenShareModeDropdown(root)) {
                            dropDownOpened = true
                            Log.d(TAG, "Triggered share mode dropdown")
                            return 
                        }
                    }
                }
            }

            // Step 2: Click the positive (confirm / next / allow / start) button.
            if (attemptClickPositiveButton(roots)) return

        } catch (e: Exception) {
            Log.e(TAG, "Error in auto-tap: ${e.message}")
        } finally {
            roots.forEach { it.recycle() }
        }
    }

    /**
     * Attempts to find and click the positive action button using IDs, text, or relative position.
     * Prioritizes Samsung-specific IDs and exact text matches.
     */
    private fun attemptClickPositiveButton(roots: List<AccessibilityNodeInfo>): Boolean {
        for (root in roots) {
            // 1. Try known Samsung/Android positive button IDs (High priority)
            for (id in POSITIVE_BUTTON_IDS) {
                if (tryClickButtonById(root, id)) {
                    Log.d(TAG, "Clicked positive button via ID: $id")
                    return true
                }
            }

            // 2. Try exact text match for "화면 공유" etc.
            if (tryClickButtonByExactText(root, KNOWN_POSITIVE_TEXTS)) {
                Log.d(TAG, "Clicked positive button via exact text")
                return true
            }

            // 3. Last resort: Cancel-anchor sibling matching
            if (tryClickPositiveButton(root)) {
                Log.d(TAG, "Clicked positive button via cancel-anchor")
                return true
            }
        }
        return false
    }

    /**
     * Collects root [AccessibilityNodeInfo] nodes from all interactive windows.
     *
     * Returns the active window's root first (most likely to contain the dialog),
     * followed by roots from other interactive windows. Uses the [windows] API
     * (requires [flagRetrieveInteractiveWindows][android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS]).
     */
    private fun collectWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        
        // Root in active window is the most reliable start.
        rootInActiveWindow?.let { roots.add(it) }

        try {
            // Get all windows to ensure we find dropdowns/popups.
            // Requires FLAG_RETRIEVE_INTERACTIVE_WINDOWS in service XML/config.
            for (window in windows) {
                val root = window.root ?: continue
                
                // If we already have a root for this window ID, skip it.
                if (roots.any { it.windowId == window.id }) {
                    root.recycle()
                    continue
                }
                roots.add(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate windows: ${e.message}")
        }
        
        Log.d(TAG, "Collected ${roots.size} window roots")
        return roots
    }

    /**
     * Attempts to find and click a numeric button (0-9) on the lock screen.
     * Uses multiple strategies: Resource ID, Text search, and Recursive tree traversal.
     */
    private fun tryClickDigitButton(root: AccessibilityNodeInfo, digit: Char): Boolean {
        // 1. Try common Resource ID patterns
        val digitIds = listOf(
            "com.android.systemui:id/key$digit",
            "com.android.keyguard:id/key$digit",
            "com.android.systemui:id/digit_$digit",
            "com.android.keyguard:id/digit_$digit",
            "com.samsung.android.systemui:id/key$digit",
            "key$digit",
            "digit$digit"
        )
        for (id in digitIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (performClickOnNode(node)) {
                        nodes.forEach { it.recycle() }
                        return true
                    }
                    node.recycle()
                }
            }
        }

        // 2. Try exact text or content description
        if (tryClickButtonByExactText(root, listOf(digit.toString()))) {
            return true
        }

        // 3. Recursive deep search (Final fallback)
        val resultNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesRecursive(root, digit.toString(), resultNodes)
        for (node in resultNodes) {
            val clicked = performClickOnNode(node)
            node.recycle()
            if (clicked) {
                resultNodes.forEach { try { it.recycle() } catch(e: Exception) {} }
                return true
            }
        }

        return false
    }

    /**
     * Recursively traverses the view tree to find nodes matching the target text, description, or ID.
     */
    private fun findNodesRecursive(node: AccessibilityNodeInfo, target: String, results: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (text.equals(target, ignoreCase = true) || 
            desc.equals(target, ignoreCase = true) || 
            id.endsWith("/key$target") || 
            id.endsWith("/digit_$target") ||
            id.endsWith("/key_$target")) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesRecursive(child, target, results)
            child.recycle()
        }
    }

    /**
     * Helper to perform a click on a node or its clickable parent.
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        val clickable = findClickableAncestor(node)
        if (clickable != null) {
            val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickable.recycle()
            return success
        }
        return false
    }

    /**
     * Attempts to find and click a button by its exact text match.
     */
    private fun tryClickButtonByExactText(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes != null) {
                for (node in nodes) {
                    val nodeText = node.text?.toString()?.trim() ?: ""
                    val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
                    if (nodeText.equals(text, ignoreCase = true) || nodeDesc.equals(text, ignoreCase = true)) {
                        val clickable = findClickableAncestor(node)
                        if (clickable != null) {
                            val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (success) {
                                clickable.recycle()
                                node.recycle()
                                nodes.forEach { it.recycle() }
                                return true
                            }
                            clickable.recycle()
                        }
                    }
                    node.recycle()
                }
            }
        }
        return false
    }

    /**
     * Attempts to click the "Done" or "OK" button on the lock screen by ID.
     */
    private fun tryClickLockScreenDoneButton(root: AccessibilityNodeInfo): Boolean {
        val commonIds = listOf(
            "com.android.systemui:id/key_enter",
            "com.android.systemui:id/key_ok",
            "com.android.keyguard:id/key_enter",
            "com.android.keyguard:id/ok",
            "com.android.systemui:id/done_button"
        )
        for (id in commonIds) {
            if (tryClickButtonById(root, id)) return true
        }
        return false
    }

    /**
     * Attempts to find and click a button by its resource ID.
     */
    private fun tryClickButtonById(root: AccessibilityNodeInfo, resId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(resId)
        for (node in nodes) {
            val clickable = findClickableAncestor(node)
            if (clickable != null) {
                val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    clickable.recycle()
                    nodes.forEach { it.recycle() }
                    return true
                }
                clickable.recycle()
            }
        }
        nodes.forEach { it.recycle() }
        return false
    }

    /**
     * Checks if two [AccessibilityNodeInfo] objects represent the same node
     * by comparing their window and internal IDs.
     */
    private fun isSameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        return a.windowId == b.windowId && a == b
    }

    override fun onInterrupt() = Unit

    // ─────────────────────────────────────────────────────────
    //  Auto-tap API
    // ─────────────────────────────────────────────────────────

    /**
     * Enables auto-tap mode for the MediaProjection consent dialog.
     *
     * The service will detect system dialog windows and auto-tap the
     * positive button by locating the cancel button and clicking its
     * sibling. Handles multi-step dialogs by staying active until the
     * 10-second safety timeout expires.
     */
    fun enableAutoTap() {
        Log.d(TAG, "enableAutoTap: armed for ${AUTO_TAP_TIMEOUT_MS}ms")
        autoTapEnabled = true
        fullScreenModeSelected = false
        dropDownOpened = false
        autoTapHandler.removeCallbacksAndMessages(null)
        autoTapHandler.postDelayed({
            autoTapEnabled = false
            Log.d(TAG, "enableAutoTap: timeout expired")
        }, AUTO_TAP_TIMEOUT_MS)
    }

    /**
      * Finds the cancel/deny button in the dialog and clicks its sibling —
      * the positive action button. This approach is resilient to OEM and locale
      * variations in positive button text ("Next", "Allow", "화면 공유", etc.).
      *
      * The cancel button text is highly consistent across Android variants,
      * making it a reliable anchor to locate the button pair.
      *
      * @param root Root [AccessibilityNodeInfo] of the active window.
      * @return True if a positive button was found and clicked.
      */
    private fun tryClickPositiveButton(root: AccessibilityNodeInfo): Boolean {
        for (cancelText in CANCEL_BUTTON_TEXTS) {
            val cancelNodes = root.findAccessibilityNodeInfosByText(cancelText)
            for (cancelNode in cancelNodes) {
                val cancelBtn = findClickableAncestor(cancelNode)
                if (cancelBtn != null) {
                    val cancelRect = Rect()
                    cancelBtn.getBoundsInScreen(cancelRect)
                    Log.d(TAG, "Found cancel button: '$cancelText' at ${cancelRect}")
                    
                    // Search the entire tree for a clickable non-cancel node
                    // vertically aligned with the cancel button (same row).
                    // This reliably skips dropdowns, toggles, and other UI
                    // elements above the button bar.
                    val positive = findClickableAlignedWith(root, cancelRect)
                    if (positive != null) {
                        val positiveRect = Rect()
                        positive.getBoundsInScreen(positiveRect)
                        Log.d(TAG, "Found positive button at y=${positiveRect.top}, " +
                                "cancel at y=${cancelRect.top}")
                        val clicked = positive.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK
                        )
                        positive.recycle()
                        cancelBtn.recycle()
                        cancelNodes.forEach { it.recycle() }
                        return clicked
                    } else {
                        Log.d(TAG, "No aligned positive button found for cancel at ${cancelRect}")
                        // Fallback: try to find any clickable button nearby
                        val fallbackPositive = findAnyClickableButtonNearby(root, cancelRect)
                        if (fallbackPositive != null) {
                            Log.d(TAG, "Using fallback positive button")
                            val clicked = fallbackPositive.performAction(
                                    AccessibilityNodeInfo.ACTION_CLICK
                            )
                            fallbackPositive.recycle()
                            cancelBtn.recycle()
                            cancelNodes.forEach { it.recycle() }
                            return clicked
                        }
                    }
                    cancelBtn.recycle()
                }
            }
            cancelNodes.forEach { it.recycle() }
        }
        return false
    }

    /**
     * Attempts to open the share-mode dropdown in the Samsung One UI
     * MediaProjection consent dialog (Android 16+).
     *
     * On Android 16+, Samsung changed the share mode selector from a
     * radio-button list to a collapsed dropdown. The dropdown shows the
     * currently selected option ("앱 하나 공유") by default. Tapping this
     * text opens the dropdown to reveal all options.
     *
     * @param root Root [AccessibilityNodeInfo] to search in.
     * @return True if the dropdown trigger was found and clicked.
     */
    private fun tryOpenShareModeDropdown(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByText(root, DEFAULT_SHARE_MODE_TEXTS)
        if (node != null) {
            val clickable = findClickableAncestor(node)
            node.recycle()
            if (clickable != null) {
                Log.d(TAG, "Opening share mode dropdown via node with text '${clickable.text}'")
                val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickable.recycle()
                return clicked
            }
        }
        return false
    }

    /**
     * Attempts to click the "전체 화면 공유" (full screen) list item in the
     * Samsung One UI MediaProjection share-mode dialog.
     *
     * @param root Root [AccessibilityNodeInfo] to search in.
     * @return True if the full-screen option was found and clicked.
     */
    private fun trySelectFullScreenMode(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByText(root, FULL_SCREEN_SHARE_TEXTS, exact = true)
        if (node != null) {
            val clickable = findClickableAncestor(node)
            node.recycle()
            if (clickable != null) {
                Log.d(TAG, "Selecting full-screen mode via node with text '${clickable.text}'")
                val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickable.recycle()
                return clicked
            }
        }
        return false
    }

    /**
     * Recursively searches for a node that matches any of the given texts.
     * @param exact If true, uses exact string equality. If false, uses contains.
     */
    private fun findNodeByText(
        node: AccessibilityNodeInfo, 
        texts: List<String>, 
        exact: Boolean = false
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.trim() ?: ""
        val match = if (exact) {
            texts.any { nodeText.equals(it, ignoreCase = true) }
        } else {
            texts.any { nodeText.contains(it, ignoreCase = true) }
        }

        if (match) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, texts, exact)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
      * Recursively searches the node tree for a clickable, non-cancel node
      * whose vertical centre is within one button-height of [cancelRect]'s
      * vertical centre. This ensures only elements in the same button row
      * are matched, skipping dropdowns and other controls above the buttons.
      *
      * @param node       Current node in the traversal (not recycled).
      * @param cancelRect Screen bounds of the cancel button.
      * @return A matching clickable node, or null.
      */
    private fun findClickableAlignedWith(
        node: AccessibilityNodeInfo,
        cancelRect: Rect
    ): AccessibilityNodeInfo? {
        val cancelCenterY = cancelRect.centerY()
        val tolerance = cancelRect.height()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable && !matchesCancelText(child)) {
                val bounds = Rect()
                child.getBoundsInScreen(bounds)
                if (Math.abs(bounds.centerY() - cancelCenterY) <= tolerance) {
                    return child
                }
            }
            val result = findClickableAlignedWith(child, cancelRect)
            if (result != null) { child.recycle(); return result }
            child.recycle()
        }
        return null
    }

    /**
      * Fallback method to find any clickable button near the cancel button
      * when the aligned search fails. Looks for clickable nodes within
      * a reasonable distance (2x button height) of the cancel button.
      *
      * @param root       Root node to search in.
      * @param cancelRect Screen bounds of the cancel button.
      * @return A clickable button node, or null.
      */
    private fun findAnyClickableButtonNearby(
        root: AccessibilityNodeInfo,
        cancelRect: Rect
    ): AccessibilityNodeInfo? {
        val cancelCenterY = cancelRect.centerY().toFloat()
        val tolerance = cancelRect.height() * 2.0f // Increased tolerance for fallback

        return findClickableNodeWithinTolerance(root, cancelCenterY, tolerance)
    }

    /**
      * Helper function to find a clickable node within vertical tolerance of a center Y.
      *
      * @param node       Current node in the traversal.
      * @param targetCenterY Target Y coordinate to match against.
      * @param tolerance  Vertical tolerance in pixels.
      * @return A matching clickable node, or null.
      */
    private fun findClickableNodeWithinTolerance(
        node: AccessibilityNodeInfo,
        targetCenterY: Float,
        tolerance: Float
    ): AccessibilityNodeInfo? {
        // Check current node
        if (node.isClickable && !matchesCancelText(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (Math.abs(bounds.centerY() - targetCenterY) <= tolerance) {
                return node
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNodeWithinTolerance(child, targetCenterY, tolerance)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    /**
     * Returns the node itself if clickable, otherwise walks up the parent chain
     * to find the nearest clickable ancestor.
     *
     * @param node Starting node.
     * @return The clickable node, or null if none found.
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            val grandparent = parent.parent
            parent.recycle()
            parent = grandparent
        }
        return null
    }

    /**
     * Checks whether a node or any of its immediate children contain cancel/deny
     * text, used to distinguish the cancel button from the positive action button.
     */
    private fun matchesCancelText(node: AccessibilityNodeInfo): Boolean {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if (CANCEL_BUTTON_TEXTS.any { nodeText.contains(it, true) || nodeDesc.contains(it, true) }) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = child.text?.toString() ?: ""
            child.recycle()
            if (CANCEL_BUTTON_TEXTS.any { childText.contains(it, true) }) return true
        }
        return false
    }

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

        val path    = Path().apply {
            moveTo(px, py)
            lineTo(px + 1f, py + 1f)
        }
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
        val roots = collectWindowRoots()
        if (roots.isEmpty()) {
            // No window roots available — fall back to shell key event.
            Log.w(TAG, "injectKey: no window roots, trying shell fallback for keyCode=$keyCode")
            tryShellKeyEvent(keyCode)
            return
        }

        // 1. Try to find a focused input in ANY window.
        var focusedNode: AccessibilityNodeInfo? = null
        for (root in roots) {
            focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) break
        }

        if (focusedNode != null) {
            val hasShift = (metaState and META_SHIFT_MASK) != 0
            when (keyCode) {
                KEYCODE_BACKSPACE -> {
                    val text = focusedNode.text?.toString() ?: ""
                    if (text.isNotEmpty()) setNodeText(focusedNode, text.dropLast(1))
                }
                KEYCODE_ENTER -> {
                    val text = focusedNode.text?.toString() ?: ""
                    setNodeText(focusedNode, "$text\n")
                }
                else -> {
                    val ch = keycodeToChar(keyCode, hasShift)
                    if (ch != null) {
                        val text = focusedNode.text?.toString() ?: ""
                        setNodeText(focusedNode, text + ch)
                    }
                }
            }
            focusedNode.recycle()
        } else {
            // 2. No focused input (e.g., Lock Screen). Try to find and click matching buttons in ALL windows.
            val ch = keycodeToChar(keyCode, false)
            val nodeSuccess = if (ch != null && ch.isDigit()) {
                roots.any { tryClickDigitButton(it, ch) }
            } else if (keyCode == KEYCODE_ENTER) {
                // Try Lock Screen Done button IDs first
                if (roots.any { tryClickLockScreenDoneButton(it) }) {
                    Log.d(TAG, "Injected Enter via lock screen button ID")
                    true
                } else {
                    val enterTexts = listOf("OK", "Enter", "완료", "확인", "Done", "Next", "다음", "입력", "Check")
                    roots.any { tryClickButtonByExactText(it, enterTexts) }
                }
            } else if (keyCode == KEYCODE_BACKSPACE) {
                val deleteTexts = listOf("Delete", "Clear", "삭제", "지우기", "Back")
                roots.any { tryClickButtonByExactText(it, deleteTexts) }
            } else false

            if (nodeSuccess) {
                Log.d(TAG, "Injected key $keyCode via node click")
            } else {
                // 3. Node-based approach failed — fall back to shell key event.
                Log.d(TAG, "Node-based key injection failed for keyCode=$keyCode, trying shell fallback")
                tryShellKeyEvent(keyCode)
            }
        }
        roots.forEach { it.recycle() }
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
     * Recursively dumps the node tree for debugging.
     */
    private fun dumpNodeTree(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        Log.d(TAG, "${indent}Node: text='${node.text}', desc='${node.contentDescription}', class=${node.className}, " +
                "clickable=${node.isClickable}, bounds=$bounds, id=${node.viewIdResourceName}")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            dumpNodeTree(child, depth + 1)
            child?.recycle()
        }
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

    /**
     * Attempts to inject a key event via the system shell command `input keyevent`.
     *
     * This runs as the app's UID and may fail with a SecurityException on
     * most non-rooted devices (the `input` command requires INJECT_EVENTS
     * permission). However, it's worth attempting as some device configurations
     * permit it.
     *
     * Runs asynchronously on a background thread to avoid blocking.
     *
     * @param keyCode Android key code to inject.
     */
    private fun tryShellKeyEvent(keyCode: Int) {
        Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.d(TAG, "Shell keyevent SUCCESS: keyCode=$keyCode")
                } else {
                    val error = process.errorStream.bufferedReader().readText().trim()
                    Log.w(TAG, "Shell keyevent FAILED (exit=$exitCode): $error")
                }
            } catch (e: IOException) {
                Log.w(TAG, "Shell keyevent IOException: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Shell keyevent SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Shell keyevent error: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val TAG = "TouchInjection"

        /** Singleton reference set when the service connects, cleared on unbind. */
        @Volatile
        var instance: TouchInjectionService? = null

        private const val KEYCODE_BACKSPACE = 67
        private const val KEYCODE_ENTER     = 66
        /** Combined Shift-key bitmask (left, right, or caps-lock). */
        private const val META_SHIFT_MASK   = 0x41  // META_SHIFT_ON | META_CAPS_LOCK_ON

        /** Auto-tap safety timeout: disables after 10 seconds to prevent stale taps. */
        private const val AUTO_TAP_TIMEOUT_MS = 10_000L

        /** Minimum interval between auto-tap processing attempts. */
        private const val AUTO_TAP_DEBOUNCE_MS = 300L

        /**
         * Known Samsung/Android positive button IDs.
         * button_primary and button_start are prioritized for One UI 6.1+.
         */
        private val POSITIVE_BUTTON_IDS = listOf(
            "com.samsung.android.systemui:id/button_primary",
            "com.samsung.android.systemui:id/button_start",
            "com.android.systemui:id/button_start",
            "android:id/button1",
            "com.android.systemui:id/ok"
        )

        /**
         * Full-screen share mode option texts in the Samsung One UI MediaProjection
         * consent dialog. The dialog shows a share-mode list with "앱 하나 공유"
         * pre-selected; clicking one of these texts switches to full-screen mode.
         */
        private val FULL_SCREEN_SHARE_TEXTS = listOf(
            "전체 화면 공유", "전체화면 공유", "Entire screen", "Full screen"
        )

        /**
         * Default share-mode option texts shown in the collapsed dropdown
         * of the Samsung One UI MediaProjection consent dialog (Android 16+).
         * Tapping this text opens the dropdown to reveal all share mode options.
         */
        private val DEFAULT_SHARE_MODE_TEXTS = listOf(
            "앱 하나 공유", "Single app", "App only"
        )

        /**
         * Cancel/deny button texts used as anchors to locate the positive
         * action button. These are highly consistent across OEMs and locales.
         */
        private val CANCEL_BUTTON_TEXTS = listOf(
            "취소", "Cancel", "거부", "Deny", "Don't allow"
        )

        /**
         * Known positive button texts. "화면 공유" is prioritized for Samsung One UI.
         */
        private val KNOWN_POSITIVE_TEXTS = listOf(
            "화면 공유", "지금 시작", "시작", "허용", "다음",
            "Share screen", "Start now", "Start", "Allow", "Next"
        )
    }
}
