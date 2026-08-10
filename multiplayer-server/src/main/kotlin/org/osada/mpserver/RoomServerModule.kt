package org.osada.mpserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.Wire
import org.osada.mpserver.protocol.WireFormatException
import org.osada.mpserver.room.RoomConnection
import org.osada.mpserver.room.RoomRegistry
import org.osada.mpserver.room.RoomRouter
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("org.osada.mpserver")
private val connectionCounter = AtomicLong()

/** Ktor module: the game's static build, a health endpoint and the `/mp` room socket. */
fun Application.roomServerModule(config: ServerConfig = ServerConfig.fromEnvironment()) {
    val registry = RoomRegistry(config)
    val router = RoomRouter(registry, config)
    val startedAt = System.currentTimeMillis()

    install(WebSockets) {
        pingPeriodMillis = PING_PERIOD_MILLIS
        timeoutMillis = SOCKET_TIMEOUT_MILLIS
        maxFrameSize = config.maxFrameBytes
        masking = false
    }

    install(Compression) {
        gzip { minimumSize(COMPRESSION_MINIMUM_BYTES) }
        deflate { minimumSize(COMPRESSION_MINIMUM_BYTES) }
    }

    launch {
        while (isActive) {
            delay(SWEEP_INTERVAL)
            runCatching { registry.sweep() }
                .onFailure { logger.warn("Room sweep failed", it) }
        }
    }

    routing {
        get("/healthz") {
            call.respondText(
                """{"status":"ok","rooms":${registry.roomCount()},"uptimeMillis":${
                    System.currentTimeMillis() - startedAt
                },"protocolVersion":${Wire.PROTOCOL_VERSION}}""",
                ContentType.Application.Json,
            )
        }

        webSocket("/mp") {
            if (!originAllowed(call.request.header(HttpHeaders.Origin), config)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                return@webSocket
            }
            serveRoomSocket(router)
        }

        val webRoot = config.webRoot
        if (webRoot != null && webRoot.isDirectory) {
            staticFiles("/", webRoot) { default("index.html") }
        } else {
            get("/") {
                call.respondText(
                    "OSADA multiplayer room server is running. Static game build is not deployed here.",
                    status = HttpStatusCode.OK,
                )
            }
        }
    }
}

internal fun originAllowed(
    origin: String?,
    config: ServerConfig,
): Boolean =
    when {
        config.allowedOrigins.isEmpty() -> true
        origin == null -> false
        else -> origin.trimEnd('/') in config.allowedOrigins
    }

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.serveRoomSocket(router: RoomRouter) {
    val connection = RoomConnection("c_${connectionCounter.incrementAndGet()}")
    val writer =
        launch {
            for (raw in connection.outbound) {
                send(Frame.Text(raw))
            }
            close(CloseReason(CloseReason.Codes.NORMAL, "Room closed the connection"))
        }
    try {
        for (frame in incoming) {
            if (!handleFrame(connection, router, frame)) break
        }
    } catch (_: ClosedReceiveChannelException) {
        // Normal client disconnect.
    } finally {
        router.handleDisconnect(connection)
        connection.close()
        writer.cancel()
    }
}

/**
 * Returns false when the socket should be closed (only a flooding client triggers that).
 *
 * The early exits are the point: a non-text frame, a rate-limited client and an unparsable envelope
 * each end the frame's life in a different way, and folding them together would obscure that.
 */
@Suppress("ReturnCount")
private suspend fun handleFrame(
    connection: RoomConnection,
    router: RoomRouter,
    frame: Frame,
): Boolean {
    val text = (frame as? Frame.Text)?.readText() ?: return true
    if (!connection.allowMessage(System.currentTimeMillis())) {
        connection.sendError(ErrorCode.RATE_LIMITED, "Too many messages.")
        return false
    }
    val envelope =
        try {
            Wire.decode(text)
        } catch (failure: WireFormatException) {
            connection.sendError(ErrorCode.PROTOCOL_MISMATCH, failure.message.orEmpty())
            return true
        }
    router.handle(connection, envelope)
    return true
}

private val PING_PERIOD_MILLIS = 20.seconds.inWholeMilliseconds
private val SOCKET_TIMEOUT_MILLIS = 60.seconds.inWholeMilliseconds
private val SWEEP_INTERVAL = 15.seconds
private const val COMPRESSION_MINIMUM_BYTES = 1024L
