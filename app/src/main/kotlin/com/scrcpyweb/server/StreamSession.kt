package com.scrcpyweb.server

import com.scrcpyweb.service.TouchInjectionService
import io.ktor.websocket.*
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
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    /** The latest fMP4 init segment. New clients receive this immediately on connect. */
    @Volatile
    var initSegment: ByteArray? = null

    /** Frame queue: a channel-based approach is used inside each session coroutine. */
    private val frameListeners = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    /**
     * Handles a single incoming WebSocket session.
     *
     * Sends the current init segment on connect, then concurrently:
     *  1. Forwards fMP4 media segments received via [sendFrameToAll] to this client.
     *  2. Receives and processes JSON control messages from the client.
     *
     * @param session The Ktor [DefaultWebSocketServerSession] for this connection.
     */
    suspend fun handleSession(session: DefaultWebSocketServerSession) {
        val id = System.nanoTime().toString()
        sessions[id] = session

        // Send init segment immediately so MSE can initialise the SourceBuffer
        initSegment?.let { init ->
            val header = buildHeader(0x01, init.size)
            session.send(Frame.Binary(true, header + init))
        }

        // Per-session frame channel: avoids head-of-line blocking between clients
        val frameChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 60)
        frameListeners[id] = { frame -> frameChannel.trySend(frame) }

        try {
            coroutineScope {
                // Sender coroutine: relay encoded frames to this client
                val senderJob = launch {
                    for (frame in frameChannel) {
                        val header = buildHeader(0x02, frame.size)
                        session.send(Frame.Binary(true, header + frame))
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
     * Broadcasts an fMP4 media segment to all connected clients.
     * Should be called from the encoder callback thread.
     *
     * @param frameData Raw fMP4 segment bytes (moof + mdat).
     */
    fun sendFrameToAll(frameData: ByteArray) {
        frameListeners.values.forEach { listener -> listener(frameData) }
    }

    /**
     * Updates the stored init segment and broadcasts it to all existing clients.
     * Called when a new encoding session starts (e.g. after screen rotation).
     *
     * @param segment New fMP4 init segment (ftyp + moov).
     */
    suspend fun updateInitSegment(segment: ByteArray) {
        initSegment = segment
        val header = buildHeader(0x01, segment.size)
        val frame = Frame.Binary(true, header + segment)
        sessions.values.forEach { session ->
            runCatching { session.send(frame) }
        }
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
    private suspend fun handleTextMessage(text: String, session: DefaultWebSocketServerSession) {
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
     *  - byte 0: message type (0x01 = init_segment, 0x02 = media_segment)
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
