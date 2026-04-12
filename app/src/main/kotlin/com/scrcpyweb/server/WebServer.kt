package com.scrcpyweb.server

import android.content.res.AssetManager
import com.scrcpyweb.service.MirrorService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.seconds

/**
 * Embedded Ktor/Netty HTTP + WebSocket server.
 *
 * Serves the bundled web frontend from assets and exposes REST and WebSocket
 * endpoints for device control and video streaming.
 *
 * @param port         TCP port to listen on (default 8080).
 * @param assetManager Android AssetManager used to read files from assets/web/.
 * @param service      MirrorService instance for capture state tracking.
 */
class WebServer(
    private val port: Int = 8080,
    private val assetManager: AssetManager,
    private val service: MirrorService
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Callback to retrieve current device info as a JSON-serialisable map. */
    var onDeviceInfoRequest: (() -> Map<String, Any>)? = null

    /** Callback invoked when the browser requests capture start. Returns success flag. */
    var onStartCapture: (() -> Boolean)? = null

    /** Callback invoked when the browser requests capture stop. */
    var onStopCapture: (() -> Unit)? = null

    /** The shared session manager used by the WebSocket route. */
    val streamSession = StreamSession(service)

    /**
     * Starts the embedded Netty server. Non-blocking — server runs on IO threads.
     */
    fun start() {
        server = embeddedServer(Netty, port = port) {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 30.seconds
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                configureRoutes()
            }
        }.also { it.start(wait = false) }
    }

    /**
     * Gracefully stops the embedded server and cancels the coroutine scope.
     */
    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        server = null
        scope.cancel()
    }

    private fun Routing.configureRoutes() {
        // ── Static web frontend ──────────────────────────────────────────────

        get("/") {
            serveAsset(call, "web/index.html")
        }

        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            serveAsset(call, "web/$path")
        }

        // ── REST API ─────────────────────────────────────────────────────────

        get("/api/device-info") {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            val info = onDeviceInfoRequest?.invoke()?.toMutableMap() ?: mutableMapOf()
            info["isCapturing"] = com.scrcpyweb.service.MirrorService.instance?.isCapturing ?: false
            info["isAccessibilityEnabled"] = com.scrcpyweb.service.TouchInjectionService.instance != null
            info["isNotificationEnabled"] = (service as? android.content.Context)?.let { ctx ->
                val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                flat?.contains(ctx.packageName) == true
            } ?: false
            call.respondText(
                mapToJson(info),
                ContentType.Application.Json
            )
        }

        post("/api/start-capture") {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            val success = onStartCapture?.invoke() ?: false
            call.respondText(
                """{"success":$success}""",
                ContentType.Application.Json
            )
        }

        post("/api/stop-capture") {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            onStopCapture?.invoke()
            call.respondText("""{"success":true}""", ContentType.Application.Json)
        }

        // ── WebSocket stream ─────────────────────────────────────────────────

        webSocket("/stream") {
            streamSession.handleSession(this)
        }
    }

    /**
     * Reads a file from the Android assets folder and writes it to the HTTP response.
     * Detects Content-Type from the file extension.
     *
     * @param call The Ktor ApplicationCall to respond to.
     * @param assetPath Path within the assets directory (e.g. "web/index.html").
     */
    private suspend fun serveAsset(call: ApplicationCall, assetPath: String) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        try {
            val bytes = assetManager.open(assetPath).use { it.readBytes() }
            val contentType = detectContentType(assetPath)
            call.respondBytes(bytes, contentType)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.NotFound, "Not found: $assetPath")
        }
    }

    /**
     * Maps a file path to its corresponding [ContentType] based on extension.
     *
     * @param path File path with extension.
     * @return Detected [ContentType], defaulting to [ContentType.Application.OctetStream].
     */
    private fun detectContentType(path: String): ContentType {
        return when {
            path.endsWith(".html") -> ContentType.Text.Html
            path.endsWith(".js")   -> ContentType.Application.JavaScript
            path.endsWith(".css")  -> ContentType.Text.CSS
            path.endsWith(".svg")  -> ContentType.Image.SVG
            path.endsWith(".png")  -> ContentType.Image.PNG
            path.endsWith(".ico")  -> ContentType("image", "x-icon")
            else                   -> ContentType.Application.OctetStream
        }
    }

    /**
     * Minimal Map-to-JSON serialiser for device info (avoids reflection / Gson dependency).
     *
     * @param map String-keyed map with Any values (String, Int, Long, Boolean supported).
     * @return JSON string representation.
     */
    private fun mapToJson(map: Map<String, Any>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            val jsonValue = when (v) {
                is String  -> "\"${v.replace("\"", "\\\"")}\""
                is Boolean -> v.toString()
                else       -> v.toString()
            }
            "\"$k\":$jsonValue"
        }
        return "{$entries}"
    }
}
