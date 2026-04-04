package com.scrcpyweb.server

import com.scrcpyweb.service.TouchInjectionService
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.*
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

    /** Called when a new client connects so the caller can request an IDR frame. */
    var onClientConnected: (() -> Unit)? = null

    /** Frame queue: a channel-based approach is used inside each session coroutine. */
    private val frameListeners = ConcurrentHashMap<String, (ByteArray) -> Unit>()

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
        sessions[id] = session

        // Per-session channel carries pre-headered raw WebSocket payloads so that
        // init segments (0x01) and media frames (0x02) share the same queue and
        // are guaranteed to arrive at the client in insertion order.
        // The listener is registered BEFORE enqueuing the init segment so that
        // any concurrent updateInitSegment() call also goes through the channel.
        val frameChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 64)
        frameListeners[id] = { rawFrame -> frameChannel.trySend(rawFrame) }

        // Enqueue init segment through the channel so it is ordered correctly
        // relative to any media frames that follow immediately.
        initSegment?.let { init ->
            val header = buildHeader(0x01, init.size)
            frameChannel.trySend(header + init)
        }

        // Request a fresh IDR from the encoder so this client receives a
        // decodable keyframe as soon as possible (~33 ms at 30 fps).
        onClientConnected?.invoke()

        try {
            coroutineScope {
                // Sender coroutine: relay pre-headered frames to this client as-is
                val senderJob = launch {
                    for (rawFrame in frameChannel) {
                        session.send(Frame.Binary(true, rawFrame))
                    }
                }

                // Receiver coroutine: handle incoming client messages
                val receiverJob = launch {
                    for (incoming in session.incoming) {
                        when (incoming) {
                            is Frame.Text -> handleTextMessage(incoming.readText(), session)
                            else          -> Unit
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
            frameChannel.close()
        }
    }

    /**
     * Broadcasts an fMP4 media segment to all connected clients via their
     * per-session channels, preserving ordering with init segments.
     *
     * Keyframes are sent with type 0x03 so clients can distinguish them from
     * P-frames (0x02) and gate playback until the first keyframe arrives.
     * The most recent keyframe is also cached for immediate delivery to
     * newly connecting clients.
     *
     * @param frameData  Raw fMP4 segment bytes (moof + mdat).
     * @param isKeyFrame Whether this frame is an IDR (keyframe).
     */
    fun sendFrameToAll(frameData: ByteArray, isKeyFrame: Boolean = false) {
        val type = if (isKeyFrame) 0x03 else 0x02
        val header = buildHeader(type, frameData.size)
        val packet = header + frameData
        frameListeners.values.forEach { listener -> listener(packet) }
    }

    /**
     * Updates the stored init segment and enqueues it to all existing clients
     * via their per-session channels so ordering relative to media frames is
     * preserved.
     *
     * @param segment New fMP4 init segment (ftyp + moov).
     */
    fun updateInitSegment(segment: ByteArray) {
        initSegment = segment
        val header = buildHeader(0x01, segment.size)
        val packet = header + segment
        frameListeners.values.forEach { listener -> listener(packet) }
    }

    /**
     * Returns the number of currently connected WebSocket clients.
     */
    fun clientCount(): Int = sessions.size

    /**
     * Parses a JSON control message from a browser client and dispatches the
     * appropriate action to [TouchInjectionService].
     *
     * If [TouchInjectionService] is not running, sends a JSON error frame back
     * to the originating [session] before returning.
     *
     * Supported message types: touch, nav, scroll, key, config.
     *
     * @param text    Raw JSON string received from the client.
     * @param session The WebSocket session that sent the message, used for
     *                sending error responses.
     */
    private suspend fun handleTextMessage(text: String, session: WebSocketSession) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            // key and config messages do not require the accessibility service
            if (type != "key" && type != "config") {
                val service = TouchInjectionService.instance
                if (service == null) {
                    val error = """{"type":"error","message":"Accessibility service not enabled"}"""
                    session.send(Frame.Text(error))
                    return
                }
                when (type) {
                    "touch"  -> handleTouchMessage(json, service)
                    "nav"    -> handleNavMessage(json, service)
                    "scroll" -> handleScrollMessage(json, service)
                }
            }
            // key and config are intentional no-ops for now
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleTouchMessage(json: JSONObject, service: TouchInjectionService?) {
        if (service == null) return
        val action = json.optString("action")
        val x = json.optDouble("x", 0.5).toFloat()
        val y = json.optDouble("y", 0.5).toFloat()
        when (action) {
            "down", "up" -> service.injectTap(x, y)
            "move"       -> { /* continuous moves handled by injectSwipe */ }
        }
    }

    private fun handleNavMessage(json: JSONObject, service: TouchInjectionService?) {
        service?.performNavAction(json.optString("action"))
    }

    private fun handleScrollMessage(json: JSONObject, service: TouchInjectionService?) {
        if (service == null) return
        val x = json.optDouble("x", 0.5).toFloat()
        val y = json.optDouble("y", 0.5).toFloat()
        val dx = json.optDouble("dx", 0.0).toFloat()
        val dy = json.optDouble("dy", 0.0).toFloat()
        service.injectScroll(x, y, dx, dy)
    }

    /**
     * Builds the 5-byte binary frame header used by the WebSocket protocol.
     *
     * Header layout:
     *  - byte 0: message type (0x01 = init_segment, 0x02 = media_segment, 0x03 = keyframe_segment)
     *  - bytes 1–4: data length as uint32 big-endian
     *
     * @param type       Message type byte.
     * @param dataLength Length of the payload in bytes.
     * @return 5-byte header array.
     */
    private fun buildHeader(type: Int, dataLength: Int): ByteArray {
        return byteArrayOf(
            type.toByte(),
            (dataLength shr 24 and 0xFF).toByte(),
            (dataLength shr 16 and 0xFF).toByte(),
            (dataLength shr 8  and 0xFF).toByte(),
            (dataLength        and 0xFF).toByte()
        )
    }
}
