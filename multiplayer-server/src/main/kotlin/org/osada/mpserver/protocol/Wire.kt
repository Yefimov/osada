package org.osada.mpserver.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire vocabulary shared with the Kotlin/JS client.
 *
 * This enum must stay identical to `org.osada.multiplayer.protocol.MultiplayerMessageType`
 * in `src/jsMain`; `WireCompatibilityTest` fails when the two drift apart.
 */
enum class MessageType {
    HELLO,
    WELCOME,
    CREATE_ROOM,
    JOIN_ROOM,
    LOBBY_PATCH_PROPOSE,
    LOBBY_STATE,
    SET_READY,
    START_GAME_PROPOSE,
    START_GAME,
    LEAVE_ROOM,
    KICK_PARTICIPANT,
    COMMAND_PROPOSE,
    COMMAND_FOR_AUTHORITY,
    COMMAND_COMMIT,
    COMMAND_REJECT,
    SNAPSHOT,
    SNAPSHOT_ACK,
    RESYNC_REQUEST,
    PAUSE_STATE,
    AUTHORITY_CHANGED,
    AUTHORITY_READY,
    MATCH_ENDED,
    ROOM_ERROR,
    PRESENCE_SELECT_UNIT,
    PRESENCE_MAP_PING,
    PRESENCE_VIEWPORT,
    PRESENCE_TYPING,
    HEARTBEAT,
}

/** Machine-readable failure codes. Mirrors the client's `MultiplayerErrorCode`. */
enum class ErrorCode {
    STALE_STATE,
    NOT_YOUR_CONTROL_SCOPE,
    NOT_ACTIVE_PLAYER,
    UNIT_ALREADY_ACTED,
    INSUFFICIENT_PRESTIGE,
    CONTENT_MISMATCH,
    AUTHORITY_TIMEOUT,
    ROOM_EXPIRED,
    SNAPSHOT_INVALID,
    PROTOCOL_MISMATCH,
    INVALID_MESSAGE,
    ROOM_FULL,
    ROOM_NOT_FOUND,
    MATCH_ALREADY_STARTED,
    MATCH_NOT_STARTED,
    RECONNECT_TOKEN_INVALID,
    COMMAND_PENDING,
    NOT_ROOM_HOST,
    RATE_LIMITED,
    SERVER_BUSY,
}

data class Envelope(
    val type: MessageType,
    val payload: JsonObject,
)

class WireFormatException(
    message: String,
) : IllegalArgumentException(message)

/** Envelope codec. The shape is fixed by the client's `MultiplayerProtocolCodec`. */
object Wire {
    const val PROTOCOL_VERSION = 1

    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Each guard names the exact field at fault, which is what makes a PROTOCOL_MISMATCH reply
    // actionable; collapsing them into one throw would lose that.
    @Suppress("ThrowsCount")
    fun decode(raw: String): Envelope {
        val root =
            runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw WireFormatException("Malformed multiplayer message") }
        val version =
            root["protocolVersion"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: throw WireFormatException("Missing protocolVersion")
        if (version != PROTOCOL_VERSION) throw WireFormatException("Unsupported protocol version $version")
        val typeName =
            root["type"]?.jsonPrimitive?.content
                ?: throw WireFormatException("Missing message type")
        val type =
            MessageType.entries.firstOrNull { it.name == typeName }
                ?: throw WireFormatException("Unknown message type $typeName")
        val payload =
            runCatching { root["payload"]?.jsonObject }
                .getOrNull()
                ?: throw WireFormatException("Missing message payload")
        return Envelope(type, payload)
    }

    fun encode(
        type: MessageType,
        payload: JsonObject,
    ): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("protocolVersion", JsonPrimitive(PROTOCOL_VERSION))
                put("type", JsonPrimitive(type.name))
                put("sentAt", JsonPrimitive(System.currentTimeMillis()))
                put("payload", payload)
            },
        )

    /**
     * Envelope around a payload that is already JSON text.
     *
     * Used for stored snapshots: a committed game state is by far the largest thing the server
     * handles, and keeping it as text rather than a parsed tree is what keeps a full room registry
     * inside the 192 MB heap this host allows.
     */
    fun encodeRaw(
        type: MessageType,
        payloadJson: String,
    ): String =
        """{"protocolVersion":$PROTOCOL_VERSION,"type":"${type.name}",""" +
            """"sentAt":${System.currentTimeMillis()},"payload":$payloadJson}"""

    fun error(
        code: ErrorCode,
        message: String,
    ): String =
        encode(
            MessageType.ROOM_ERROR,
            buildJsonObject {
                put("code", JsonPrimitive(code.name))
                put("message", JsonPrimitive(message))
            },
        )
}
