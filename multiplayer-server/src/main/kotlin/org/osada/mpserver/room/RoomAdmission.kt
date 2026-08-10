package org.osada.mpserver.room

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.osada.mpserver.ServerConfig
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire
import org.osada.mpserver.protocol.stringOrNull

/** Creating a room, joining one and taking a held seat back after a dropped connection. */
class RoomAdmission(
    private val registry: RoomRegistry,
    private val config: ServerConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun createRoom(
        connection: RoomConnection,
        payload: JsonObject,
    ) {
        val room = registry.create()
        if (room == null) {
            connection.sendError(ErrorCode.SERVER_BUSY, "The server is hosting too many rooms.")
            return
        }
        room.mutex.withLock {
            val (member, token) = attach(connection, room, payload.stringOrNull("displayName"))
            room.hostParticipantId = member.participantId
            room.lastActivityAtMillis = clock()
            welcome(connection, room, member, token)
            room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
        }
    }

    suspend fun joinRoom(
        connection: RoomConnection,
        payload: JsonObject,
    ) {
        val code =
            payload
                .stringOrNull("roomCode")
                ?.trim()
                ?.uppercase()
                .orEmpty()
        val room = if (Identifiers.isRoomCode(code)) registry.find(code) else null
        if (room == null) {
            connection.sendError(ErrorCode.ROOM_NOT_FOUND, "Room ${code.ifEmpty { "?" }} was not found or has expired.")
            return
        }
        room.mutex.withLock {
            room.lastActivityAtMillis = clock()
            val seated =
                reattach(connection, room, payload.stringOrNull("reconnectToken"))
                    ?: admit(connection, room, payload)
            if (seated) room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
        }
    }

    /** Null when no reconnect token was offered; false when it was offered and refused. */
    @Suppress("ReturnCount")
    private fun reattach(
        connection: RoomConnection,
        room: Room,
        reconnectToken: String?,
    ): Boolean? {
        val token = reconnectToken?.takeIf { it.isNotBlank() } ?: return null
        val member = room.memberByTokenHash(Identifiers.hashToken(token))
        if (member == null) {
            connection.sendError(ErrorCode.RECONNECT_TOKEN_INVALID, "This seat can no longer be restored.")
            return false
        }
        member.connection?.close()
        member.connection = connection
        member.disconnectedAtMillis = null
        connection.room = room
        connection.participantId = member.participantId
        resumeIfHostReturned(room, member)
        welcome(connection, room, member, token)
        room.latestSnapshot?.let { connection.send(Wire.encodeRaw(MessageType.SNAPSHOT, it)) }
        return true
    }

    private fun resumeIfHostReturned(
        room: Room,
        member: Member,
    ) {
        if (!room.paused || !room.isHost(member.participantId)) return
        room.paused = false
        room.broadcast(
            MessageType.PAUSE_STATE,
            buildJsonObject {
                put("paused", JsonPrimitive(false))
                put("reason", JsonPrimitive("HOST_RECONNECTED"))
            },
        )
    }

    /** False when the join was refused; the caller has already been told why. */
    private fun admit(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ): Boolean {
        val refusal =
            when {
                room.started -> ErrorCode.MATCH_ALREADY_STARTED to "This match has already started."
                room.members.size >= config.maxParticipantsPerRoom -> ErrorCode.ROOM_FULL to "This room is full."
                else -> null
            }
        if (refusal != null) {
            connection.sendError(refusal.first, refusal.second)
            return false
        }
        val (member, token) = attach(connection, room, payload.stringOrNull("displayName"))
        welcome(connection, room, member, token)
        return true
    }

    /** Creates a seat and returns it with the one-time plaintext reconnect token. */
    private fun attach(
        connection: RoomConnection,
        room: Room,
        displayName: String?,
    ): Pair<Member, String> {
        val token = Identifiers.reconnectToken()
        val member =
            Member(
                participantId = Identifiers.participantId(),
                displayName = Identifiers.sanitizeDisplayName(displayName),
                reconnectTokenHash = Identifiers.hashToken(token),
            )
        member.connection = connection
        room.members += member
        connection.room = room
        connection.participantId = member.participantId
        return member to token
    }

    private fun welcome(
        connection: RoomConnection,
        room: Room,
        member: Member,
        token: String,
    ) {
        connection.send(
            MessageType.WELCOME,
            buildJsonObject {
                put("participantId", JsonPrimitive(member.participantId))
                put("roomCode", JsonPrimitive(room.code))
                put("displayName", JsonPrimitive(member.displayName))
                put("reconnectToken", JsonPrimitive(token))
                put("hostParticipantId", JsonPrimitive(room.hostParticipantId))
                put("isHost", JsonPrimitive(room.isHost(member.participantId)))
                put("authorityEpoch", JsonPrimitive(room.authorityEpoch))
                put("revision", JsonPrimitive(room.revision))
                put("started", JsonPrimitive(room.started))
                put("paused", JsonPrimitive(room.paused))
                put("scenarioFile", JsonPrimitive(room.scenarioFile))
            },
        )
    }
}
