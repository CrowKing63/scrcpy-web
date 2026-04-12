package com.scrcpyweb.server

import com.scrcpyweb.service.MirrorService
import com.scrcpyweb.service.TouchInjectionService
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages all active WebSocket client sessions for the video stream.
 *
 * Responsibilities:
 *  - Relays fMP4 init and media segments to every connected client.
 *  - Parses incoming JSON control messages and dispatches them to
 *    [TouchInjectionService] for gesture/navigation injection.
 *  - Maintains a thread-safe map of active sessions.
 *
 * @param service MirrorService instance for capture state tracking.
 */
class StreamSession(private val service: MirrorService) {

    /** All currently connected WebSocket sessions, keyed by a unique session ID. */
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    /** The latest fMP4 init segment. New clients receive this immediately on connect. */
    @Volatile
    var initSegment: ByteArray? = null

    /**
     * Read-only capture state from MirrorService (SSOT).
     */
    val isCapturing: Boolean get() = service.isCapturing

    /**
     * Called when a new client connects so the caller can request an IDR frame.
     *
     * The boolean parameter is `true` when this is the first client (the session
     * was previously empty), allowing the caller to reset muxer state so the
     * browser receives a contiguous fMP4 sequence starting from 1.
     */
    var onClientConnected: ((isFirstClient: Boolean) -> Unit)? = null

    /**
     * Called when a browser client requests capture restart via WebSocket
     * (`{"type":"restart_capture"}`).  Returns `true` if capture was
     * successfully restarted using the saved MediaProjection token.
     */
    var onRestartCapture: (() -> Boolean)? = null

    /**
     * Called when a browser client requests a fresh capture via WebSocket
     * (`{"type":"request_capture"}`).  Unlike [onRestartCapture], this
     * launches the full permission flow including the transparent Activity
     * and auto-tap when the saved token is unavailable.
     */
    var onRequestCapture: (() -> Unit)? = null

    /** Per-session frame channels for ordered binary delivery. */
    private val frameListeners = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    /** Per-session status channels for JSON text delivery (e.g. capture_stopped). */
    private val statusListeners = ConcurrentHashMap<String, (String) -> Unit>()

    /** Lock for session management. */
    private val sessionLock = Any()

    /**
     * Handles a single incoming WebSocket session.
     *
     * Sends the current init segment on connect, then concurrently:
     *  1. Forwards fMP4 media segments received via [sendFrameToAll] to this client.
     *  2. Receives and processes JSON control messages from the client.
     *
     * @param session The Ktor [WebSocketSession] for this connection.
     */
    suspend fun handleSession(session: WebSocketSession) {
        val id = System.nanoTime().toString()
        val isFirstClient: Boolean
        
        synchronized(sessionLock) {
            isFirstClient = sessions.isEmpty()
            sessions[id] = session
        }

        // Per-session channels carry payloads in insertion order so that
        // init segments (0x01) and media frames (0x02/0x03) are never reordered.
        val frameChannel  = Channel<ByteArray>(capacity = 64)
        val statusChannel = Channel<String>(capacity = 4)

        // Notify the pipeline BEFORE enqueuing so the muxer sequence can be
        // reset for the first client.  This ensures the segments that follow
        // the init segment start at sequence 1.
        onClientConnected?.invoke(isFirstClient)

        // Register listeners BEFORE enqueuing the init segment so that any
        // concurrent updateInitSegment() call also goes through the channel.
        var clientReceivedLiveIdr = false
        val frameListener: (ByteArray) -> Unit = { rawFrame ->
            val type = rawFrame[0].toInt()
            if (type == 0x03) {
                clientReceivedLiveIdr = true
            }
            // Init segments (0x01) always pass through so that a concurrent
            // updateInitSegment() call is not silently dropped while waiting
            // for the first live IDR.
            if (clientReceivedLiveIdr || type == 0x01) {
                frameChannel.trySend(rawFrame)
            }
        }
        val statusListener: (String) -> Unit = { msg -> statusChannel.trySend(msg) }

        synchronized(sessionLock) {
            frameListeners[id] = frameListener
            statusListeners[id] = statusListener
        }

        // Always enqueue the cached init segment (if any) so new clients can
        // set up the MSE SourceBuffer immediately.
        initSegment?.let { init ->
            frameChannel.trySend(buildHeader(0x01, init.size) + init)
        }
        
        // If capture is not active, also notify the client so the UI can
        // prompt the user to restart rather than waiting on a black screen.
        if (!isCapturing) {
            statusChannel.trySend("""{"type":"capture_stopped"}""")
        } else {
            // Already capturing, join the stream immediately.
            statusChannel.trySend("""{"type":"capture_started"}""")
        }

        // Per-session pointer tracking: pointerId → (startX, startY, startTimeMs)
        val pointerStates = HashMap<Int, Triple<Float, Float, Long>>()

        try {
            coroutineScope {
                // Sender: multiplex binary frames and text status messages to client.
                val senderJob = launch {
                    launch {
                        for (msg in statusChannel) {
                            try { session.send(Frame.Text(msg)) } catch (_: Exception) { }
                        }
                    }
                    for (rawFrame in frameChannel) {
                        session.send(Frame.Binary(true, rawFrame))
                    }
                }

                // Receiver: handle incoming client messages.
                val receiverJob = launch {
                    for (incoming in session.incoming) {
                        when (incoming) {
                            is Frame.Text -> handleTextMessage(
                                incoming.readText(), session, pointerStates
                            )
                            else -> Unit
                        }
                    }
                }

                receiverJob.join()
                senderJob.cancel()
            }
        } catch (_: ClosedReceiveChannelException) {
            // Normal disconnect
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            synchronized(sessionLock) {
                sessions.remove(id)
                frameListeners.remove(id)
                statusListeners.remove(id)
            }
            frameChannel.close()
            statusChannel.close()
        }
    }

    /**
     * Broadcasts an fMP4 media segment to all connected clients.
     *
     * Keyframes (IDR) are sent with type 0x03 so clients can gate playback
     * until the first IDR arrives.
     *
     * @param frameData  Raw fMP4 segment bytes (moof + mdat).
     * @param isKeyFrame Whether this frame is an IDR (keyframe).
     */
    fun sendFrameToAll(frameData: ByteArray, isKeyFrame: Boolean = false) {
        val type   = if (isKeyFrame) 0x03 else 0x02
        val header = buildHeader(type, frameData.size)
        val packet = header + frameData
        frameListeners.values.forEach { it(packet) }
    }

    /**
     * Updates the stored init segment and broadcasts it to all existing clients.
     *
     * @param segment New fMP4 init segment (ftyp + moov).
     */
    fun updateInitSegment(segment: ByteArray) {
        initSegment = segment
        val packet  = buildHeader(0x01, segment.size) + segment
        frameListeners.values.forEach { it(packet) }
    }

    /**
     * Broadcasts a capture-state change notification as a JSON text frame to
     * all connected clients. Called by [com.scrcpyweb.service.MirrorService]
     * when capture starts or stops (e.g. due to screen lock).
     *
     * @param isCapturing True if capture just started, false if it stopped.
     */
    fun broadcastCaptureState(isCapturing: Boolean) {
        val msg = if (isCapturing) """{"type":"capture_started"}""" else """{"type":"capture_stopped"}"""
        statusListeners.values.forEach { it(msg) }
    }

    /**
     * Broadcasts a capture failure notification to all connected WebSocket clients.
     *
     * @param reason Short machine-readable reason string (e.g. "user_denied").
     */
    fun broadcastCaptureFailed(reason: String) {
        val msg = """{"type":"capture_failed","reason":"$reason"}"""
        statusListeners.values.forEach { it(msg) }
    }

    /**
     * Broadcasts a generic status message (JSON) to all connected WebSocket clients.
     * Used for notifications and periodic device status updates.
     */
    fun broadcastStatus(json: String) {
        synchronized(sessionLock) {
            statusListeners.values.forEach { it(json) }
        }
    }

    /**
     * Returns the number of currently connected WebSocket clients.
     */
    fun clientCount(): Int = sessions.size

    // ─────────────────────────────────────────────────────────
    //  Message handling
    // ─────────────────────────────────────────────────────────

    /**
     * Parses a JSON control message and dispatches the appropriate action.
     *
     * @param text          Raw JSON string from the client.
     * @param session       The originating session (used for error replies).
     * @param pointerStates Per-session pointer state map for tap-vs-swipe tracking.
     */
    private suspend fun handleTextMessage(
        text: String,
        session: WebSocketSession,
        pointerStates: HashMap<Int, Triple<Float, Float, Long>>
    ) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type")) {
                "config" -> { /* no-op: config is applied via SharedPreferences in MirrorService */ }
                "get_capture_status" -> {
                    broadcastCaptureState(isCapturing)
                }
                "get_notifications" -> {
                    try {
                        val active = com.scrcpyweb.service.NotificationService.instance?.activeNotifications
                        active?.forEach { sbn ->
                            val notification = sbn.notification ?: return@forEach
                            val extras = notification.extras ?: return@forEach
                            val jsonNotif = JSONObject().apply {
                                put("type", "notification")
                                put("packageName", sbn.packageName)
                                put("title", extras.getCharSequence("android.title")?.toString() ?: "")
                                put("text", extras.getCharSequence("android.text")?.toString() ?: "")
                                put("subText", extras.getCharSequence("android.subText")?.toString() ?: "")
                                put("timestamp", sbn.postTime)
                            }
                            session.send(Frame.Text(jsonNotif.toString()))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                "restart_capture" -> {
                    val ok = onRestartCapture?.invoke() ?: false
                    if (ok) {
                        session.send(Frame.Text("""{"type":"restart_capture_result","success":true}"""))
                    } else {
                        // MediaProjection token is single-use; once the projection is stopped
                        // the saved resultCode/data cannot create a new one.  Tell the browser
                        // to show the permission screen so the user knows to tap Allow again.
                        session.send(Frame.Text("""{"type":"permission_needed"}"""))
                    }
                }
                "request_capture" -> {
                    session.send(Frame.Text("""{"type":"capture_starting"}"""))
                    onRequestCapture?.invoke()
                }
                "key" -> {
                    // Key injection via AccessibilityService (requires canRetrieveWindowContent).
                    // Silently ignored if the service is unavailable.
                    TouchInjectionService.instance?.injectKey(
                        json.optInt("keyCode", 0),
                        json.optInt("metaState", 0)
                    )
                }
                "pin_gesture" -> { /* removed — coordinate-based PIN injection does not work on the lock screen */ }
                else -> {
                    val service = TouchInjectionService.instance
                    if (service == null) {
                        session.send(Frame.Text("""{"type":"error","message":"Accessibility service not enabled"}"""))
                        return
                    }
                    when (type) {
                        "touch"  -> handleTouchMessage(json, service, pointerStates)
                        "nav"    -> handleNavMessage(json, service)
                        "scroll" -> handleScrollMessage(json, service)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Handles pointer events with proper tap-vs-swipe discrimination.
     *
     * - "down": records the start position and time.
     * - "move": updates the current position (used for computing displacement).
     * - "up":   if displacement ≥ [SWIPE_THRESHOLD] → swipe; otherwise → tap.
     * - "long_press": injects a 600 ms stationary press (simulates long-tap / context menu).
     *
     * @param json          Parsed JSON message.
     * @param service       Active [TouchInjectionService].
     * @param pointerStates Mutable map of pointerId → (startX, startY, startTimeMs).
     */
    private fun handleTouchMessage(
        json: JSONObject,
        service: TouchInjectionService,
        pointerStates: HashMap<Int, Triple<Float, Float, Long>>
    ) {
        val action = json.optString("action")
        val x      = json.optDouble("x", 0.5).toFloat()
        val y      = json.optDouble("y", 0.5).toFloat()
        val id     = json.optInt("id", 0)

        when (action) {
            "down" -> {
                pointerStates[id] = Triple(x, y, System.currentTimeMillis())
            }
            "move" -> {
                // Track the latest position without changing the recorded start.
                // Currently used only for displacement check on "up".
            }
            "up" -> {
                val state = pointerStates.remove(id)
                if (state != null) {
                    val (startX, startY, startTime) = state
                    val dx           = x - startX
                    val dy           = y - startY
                    val displacement = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    val duration     = System.currentTimeMillis() - startTime

                    if (displacement >= SWIPE_THRESHOLD) {
                        // Swipe/drag — duration determines speed naturally.
                        service.injectSwipe(
                            startX, startY, x, y,
                            duration.coerceIn(100L, 1000L)
                        )
                    } else {
                        service.injectTap(x, y)
                    }
                } else {
                    // No matching "down" recorded; fall back to tap.
                    service.injectTap(x, y)
                }
            }
            "long_press" -> service.injectLongPress(x, y)
        }
    }

    private fun handleNavMessage(json: JSONObject, service: TouchInjectionService) {
        service.performNavAction(json.optString("action"))
    }

    private fun handleScrollMessage(json: JSONObject, service: TouchInjectionService) {
        val x  = json.optDouble("x", 0.5).toFloat()
        val y  = json.optDouble("y", 0.5).toFloat()
        val dx = json.optDouble("dx", 0.0).toFloat()
        val dy = json.optDouble("dy", 0.0).toFloat()
        service.injectScroll(x, y, dx, dy)
    }

    // ─────────────────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────────────────

    /**
     * Builds the 5-byte binary frame header.
     *
     * Layout:
     *  - byte 0:   message type (0x01 = init, 0x02 = P-frame, 0x03 = keyframe)
     *  - bytes 1–4: payload length as uint32 big-endian
     */
    private fun buildHeader(type: Int, dataLength: Int): ByteArray = byteArrayOf(
        type.toByte(),
        (dataLength shr 24 and 0xFF).toByte(),
        (dataLength shr 16 and 0xFF).toByte(),
        (dataLength shr 8  and 0xFF).toByte(),
        (dataLength        and 0xFF).toByte()
    )

    companion object {
        /**
         * Minimum normalised displacement (fraction of screen width/height) to
         * classify a pointer up event as a swipe rather than a tap.
         * 0.02 = 2% of the screen dimension.
         */
        private const val SWIPE_THRESHOLD = 0.02f
    }
}
