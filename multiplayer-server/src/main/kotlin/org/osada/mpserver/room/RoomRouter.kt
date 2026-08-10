package org.osada.mpserver.room

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.osada.mpserver.ServerConfig
import org.osada.mpserver.protocol.Envelope
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.booleanOrNull
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.protocol.withSender

/** Routes one decoded message from one connection. */
class RoomRouter(
    registry: RoomRegistry,
    private val config: ServerConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val admission = RoomAdmission(registry, config, clock)
    private val commands = RoomCommandHandler(clock)

    suspend fun handle(
        connection: RoomConnection,
        envelope: Envelope,
    ) {
        val room = connection.room
        if (room == null) {
            handleUnjoined(connection, envelope)
            return
        }
        room.mutex.withLock {
            room.lastActivityAtMillis = clock()
            handleJoined(connection, room, envelope)
        }
    }

    private suspend fun handleUnjoined(
        connection: RoomConnection,
        envelope: Envelope,
    ) {
        when (envelope.type) {
            MessageType.CREATE_ROOM -> admission.createRoom(connection, envelope.payload)
            MessageType.JOIN_ROOM, MessageType.HELLO -> admission.joinRoom(connection, envelope.payload)
            MessageType.HEARTBEAT -> connection.send(MessageType.HEARTBEAT, EMPTY)
            else -> connection.sendError(ErrorCode.INVALID_MESSAGE, "Create or join a room first.")
        }
    }

    private fun handleJoined(
        connection: RoomConnection,
        room: Room,
        envelope: Envelope,
    ) {
        when (envelope.type) {
            MessageType.SET_READY -> setReady(connection, room, envelope.payload)
            MessageType.START_GAME_PROPOSE -> startGame(connection, room, envelope.payload)
            MessageType.LEAVE_ROOM -> leaveRoom(connection, room)
            MessageType.HEARTBEAT -> connection.send(MessageType.HEARTBEAT, EMPTY)
            MessageType.PRESENCE_SELECT_UNIT,
            MessageType.PRESENCE_MAP_PING,
            MessageType.PRESENCE_VIEWPORT,
            MessageType.PRESENCE_TYPING,
            -> relayPresence(connection, room, envelope)

            MessageType.COMMAND_PROPOSE,
            MessageType.COMMAND_COMMIT,
            MessageType.COMMAND_REJECT,
            MessageType.SNAPSHOT,
            MessageType.RESYNC_REQUEST,
            -> commands.handle(connection, room, envelope)

            else -> connection.sendError(ErrorCode.INVALID_MESSAGE, "Unsupported message ${envelope.type}.")
        }
    }

    private fun setReady(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        val member = room.member(connection.participantId) ?: return
        member.ready = payload.booleanOrNull("ready") ?: false
        room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
    }

    private fun startGame(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        if (room.started) return
        val scenarioFile = payload.stringOrNull("scenarioFile").orEmpty()
        val refusal =
            when {
                !room.isHost(connection.participantId) ->
                    ErrorCode.NOT_ROOM_HOST to "Only the room host can start the match."

                room.members.size < config.maxParticipantsPerRoom ||
                    room.members.any { !it.ready || !it.connected } ->
                    ErrorCode.INVALID_MESSAGE to "Both commanders must be connected and ready."

                !Identifiers.isScenarioFile(scenarioFile) ->
                    ErrorCode.INVALID_MESSAGE to "Invalid scenario reference."

                else -> null
            }
        if (refusal != null) {
            connection.sendError(refusal.first, refusal.second)
            return
        }
        room.started = true
        room.scenarioFile = scenarioFile
        room.revision = 0
        room.authorityEpoch = 0
        room.latestSnapshot = null
        room.broadcast(
            MessageType.START_GAME,
            buildJsonObject {
                put("scenarioFile", JsonPrimitive(scenarioFile))
                put("hostParticipantId", JsonPrimitive(room.hostParticipantId))
                put("revision", JsonPrimitive(room.revision))
                put("authorityEpoch", JsonPrimitive(room.authorityEpoch))
            },
        )
    }

    private fun leaveRoom(
        connection: RoomConnection,
        room: Room,
    ) {
        val member = room.member(connection.participantId) ?: return
        room.members.remove(member)
        connection.room = null
        connection.participantId = null
        connection.close()
        room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
    }

    private fun relayPresence(
        connection: RoomConnection,
        room: Room,
        envelope: Envelope,
    ) {
        val participantId = connection.participantId ?: return
        room.broadcast(
            envelope.type,
            withSender(envelope.payload, participantId),
            exceptParticipantId = participantId,
        )
    }

    /** A socket died. The seat is kept until the grace period expires so the player can return. */
    suspend fun handleDisconnect(connection: RoomConnection) {
        val room = connection.room ?: return
        room.mutex.withLock {
            val member = room.member(connection.participantId)
            if (member != null && member.connection === connection) {
                member.connection = null
                member.disconnectedAtMillis = clock()
                pauseIfAuthorityLeft(room, member)
                room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
            }
        }
        connection.close()
    }

    private fun pauseIfAuthorityLeft(
        room: Room,
        member: Member,
    ) {
        if (!room.started || !room.isHost(member.participantId)) return
        room.paused = true
        room.broadcast(
            MessageType.PAUSE_STATE,
            buildJsonObject {
                put("paused", JsonPrimitive(true))
                put("reason", JsonPrimitive(ErrorCode.AUTHORITY_TIMEOUT.name))
            },
        )
    }

    private companion object {
        val EMPTY: JsonObject = JsonObject(emptyMap())
    }
}
