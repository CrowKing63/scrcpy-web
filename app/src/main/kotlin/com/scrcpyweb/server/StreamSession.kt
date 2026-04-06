package com.scrcpyweb.server

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
 */
class StreamSession {

    /** All currently connected WebSocket sessions, keyed by a unique session ID. */
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    /** The latest fMP4 init segment. New clients receive this immediately on connect. */
    @Volatile
    var initSegment: ByteArray? = null

    /**
     * Whether screen capture is currently active.
     * Used to inform clients that connect after capture has stopped so they
     * receive a [capture_stopped] notification rather than waiting forever for
     * frames that will never arrive.
     */
    @Volatile
    var isCapturing: Boolean = false

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

    /** Per-session frame channels for ordered binary delivery. */
    private val frameListeners = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    /** Per-session status channels for JSON text delivery (e.g. capture_stopped). */
    private val statusListeners = ConcurrentHashMap<String, (String) -> Unit>()

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
        val isFirstClient = sessions.isEmpty()
        sessions[id] = session

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
        frameListeners[id]  = { rawFrame -> frameChannel.trySend(rawFrame) }
        statusListeners[id] = { msg      -> statusChannel.trySend(msg) }

        // When capture is active, enqueue the cached init segment so it arrives
        // before any media frames.  When capture has already stopped, send a
        // capture_stopped status instead — the client's UI will prompt the user
        // to restart rather than waiting forever on a black screen.
        if (isCapturing) {
            initSegment?.let { init ->
                frameChannel.trySend(buildHeader(0x01, init.size) + init)
            }
        } else {
            statusChannel.trySend("""{"type":"capture_stopped"}""")
        }

        // Do NOT send the cached lastKeyframe here.
        //
        // Sending a stale IDR_N followed by live P-frames causes a reference-
        // frame gap at the decoder: P-frames between IDR_N and the current
        // encoder position reference frames the client never received.
        // On Safari / visionOS MSE this causes a SourceBuffer error from which
        // the client cannot recover without a full reconnect.
        //
        // requestKeyframe() (invoked above via onClientConnected) delivers a
        // fresh IDR in ~33 ms — fast enough to remove the need for a cached one.

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
            sessions.remove(id)
            frameListeners.remove(id)
            statusListeners.remove(id)
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
        frameListeners.values.forEach { listener -> listener(packet) }
    }

    /**
     * Updates the stored init segment and broadcasts it to all existing clients.
     *
     * @param segment New fMP4 init segment (ftyp + moov).
     */
    fun updateInitSegment(segment: ByteArray) {
        initSegment = segment
        val packet  = buildHeader(0x01, segment.size) + segment
        frameListeners.values.forEach { listener -> listener(packet) }
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
                "restart_capture" -> {
                    val ok = onRestartCapture?.invoke() ?: false
                    session.send(Frame.Text("""{"type":"restart_capture_result","success":$ok}"""))
                }
                "key" -> {
                    // Key injection via AccessibilityService (requires canRetrieveWindowContent).
                    // Silently ignored if the service is unavailable.
                    TouchInjectionService.instance?.injectKey(
                        json.optInt("keyCode", 0),
                        json.optInt("metaState", 0)
                    )
                }
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
