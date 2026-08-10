package org.osada.mpserver

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.osada.mpserver.protocol.Envelope
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire

internal fun testConfig(
    maxRooms: Int = 8,
    disconnectGraceMillis: Long = 60_000,
): ServerConfig =
    ServerConfig(
        host = "127.0.0.1",
        port = 0,
        webRoot = null,
        allowedOrigins = emptySet(),
        maxRooms = maxRooms,
        maxParticipantsPerRoom = 2,
        roomIdleTimeoutMillis = 600_000,
        disconnectGraceMillis = disconnectGraceMillis,
        authorityTimeoutMillis = 30_000,
        maxFrameBytes = 1L shl 20,
    )

internal suspend fun DefaultClientWebSocketSession.sendMessage(
    type: MessageType,
    payload: JsonObject = JsonObject(emptyMap()),
) {
    send(Frame.Text(Wire.encode(type, payload)))
}

/** Reads frames until one of [types] arrives, so unrelated broadcasts cannot desynchronize a test. */
internal suspend fun DefaultClientWebSocketSession.await(vararg types: MessageType): Envelope =
    withTimeout(AWAIT_TIMEOUT_MILLIS) {
        while (true) {
            val frame = incoming.receive() as? Frame.Text ?: continue
            val envelope = Wire.decode(frame.readText())
            if (envelope.type in types) return@withTimeout envelope
        }
        error("unreachable")
    }

internal fun payloadOf(vararg entries: Pair<String, Any>): JsonObject =
    buildJsonObject {
        entries.forEach { (key, value) ->
            put(
                key,
                when (value) {
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    is JsonObject -> value
                    else -> JsonPrimitive(value.toString())
                },
            )
        }
    }

private const val AWAIT_TIMEOUT_MILLIS = 10_000L
