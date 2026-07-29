@file:Suppress("UnusedParameter")

package org.osada.multiplayer.protocol

import kotlin.js.json

enum class MultiplayerMessageType {
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
    PRESENCE_SELECT_UNIT,
    PRESENCE_MAP_PING,
    PRESENCE_VIEWPORT,
    PRESENCE_TYPING,
    HEARTBEAT,
}

enum class MultiplayerErrorCode {
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
    MATCH_ALREADY_STARTED,
    RECONNECT_TOKEN_INVALID,
}

data class MessageEnvelope<T>(
    val protocolVersion: Int,
    val type: MultiplayerMessageType,
    val roomId: String?,
    val participantId: String?,
    val clientMessageId: String?,
    val serverSequence: Long?,
    val authorityEpoch: Long?,
    val expectedRevision: Long?,
    val sentAt: Long,
    val payload: T,
)

interface ClientMessage {
    val type: MultiplayerMessageType
}

interface ServerMessage {
    val type: MultiplayerMessageType
}

data class ClientProtocolMessage(
    override val type: MultiplayerMessageType,
    val payloadJson: String,
) : ClientMessage

data class ServerProtocolMessage(
    override val type: MultiplayerMessageType,
    val payloadJson: String,
) : ServerMessage

class MultiplayerProtocolCodec {
    fun encode(message: ClientMessage): String {
        require(message is ClientProtocolMessage) { "Unsupported client message implementation" }
        val payload = parsePayload(message.payloadJson)
        return JSON.stringify(
            json(
                "protocolVersion" to PROTOCOL_VERSION,
                "type" to message.type.name,
                "sentAt" to kotlin.js.Date.now(),
                "payload" to payload,
            ),
        )
    }

    fun decodeClient(json: String): ClientMessage {
        val envelope = parseEnvelope(json)
        return ClientProtocolMessage(envelope.first, envelope.second)
    }

    fun decodeServer(json: String): ServerMessage {
        val envelope = parseEnvelope(json)
        return ServerProtocolMessage(envelope.first, envelope.second)
    }

    fun validateEnvelope(json: String): Boolean =
        runCatching {
            parseEnvelope(json)
            true
        }.getOrDefault(false)

    private fun parseEnvelope(source: String): Pair<MultiplayerMessageType, String> {
        require(source.length <= MAX_MESSAGE_SIZE) { "Multiplayer message exceeds size limit" }
        val value = JSON.parse<dynamic>(source)
        val version = (value.protocolVersion as? Number)?.toInt()
        require(version == PROTOCOL_VERSION) { "Unsupported multiplayer protocol version" }
        val typeName = value.type as? String ?: error("Missing multiplayer message type")
        val type =
            MultiplayerMessageType.entries.firstOrNull { it.name == typeName }
                ?: error("Unknown multiplayer message type")
        require(value.sentAt is Number) { "Missing multiplayer message timestamp" }
        val payload = value.payload
        require(payload != undefined && payload != null) { "Missing multiplayer message payload" }
        return type to JSON.stringify(payload)
    }

    private fun parsePayload(payloadJson: String): dynamic {
        require(payloadJson.length <= MAX_MESSAGE_SIZE)
        return JSON.parse<dynamic>(payloadJson)
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_MESSAGE_SIZE = 1_048_576
    }
}
