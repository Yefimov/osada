package org.osada.mpserver.room

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.osada.mpserver.protocol.Envelope
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.protocol.withSender

/**
 * In-match messages. Every method runs with the room mutex already held by [RoomRouter].
 *
 * The server does not evaluate game rules: it orders proposals, enforces one outstanding command,
 * tracks the committed revision and keeps the newest snapshot so a returning player can resync.
 * Validating and applying the command remains the host client's job (host-authoritative MVP).
 */
class RoomCommandHandler(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun handle(
        connection: RoomConnection,
        room: Room,
        envelope: Envelope,
    ) {
        val participantId = connection.participantId ?: return
        when (envelope.type) {
            MessageType.COMMAND_PROPOSE -> propose(room, participantId, envelope.payload)
            MessageType.COMMAND_COMMIT -> commit(room, participantId, envelope.payload)
            MessageType.COMMAND_REJECT -> reject(room, participantId, envelope.payload)
            MessageType.SNAPSHOT -> snapshot(room, participantId, envelope.payload)
            MessageType.RESYNC_REQUEST -> resync(room, participantId)
            else -> Unit
        }
    }

    @Suppress("ReturnCount")
    private fun propose(
        room: Room,
        participantId: String,
        payload: JsonObject,
    ) {
        val clientMessageId = payload.stringOrNull("clientMessageId")
        if (clientMessageId == null) {
            room.sendTo(participantId, MessageType.ROOM_ERROR, errorPayload(ErrorCode.INVALID_MESSAGE))
            return
        }
        // A resent proposal must not reach the authority twice; the original commit already stands.
        if (room.wasProcessed(clientMessageId)) return
        val blocked = proposalBlocker(room, payload)
        if (blocked != null) {
            room.sendTo(participantId, MessageType.COMMAND_REJECT, rejection(participantId, clientMessageId, blocked))
            return
        }
        room.pendingCommandId = clientMessageId
        room.pendingParticipantId = participantId
        room.pendingSinceMillis = clock()
        room.serverSequence++
        room.sendTo(
            room.hostParticipantId,
            MessageType.COMMAND_FOR_AUTHORITY,
            buildJsonObject {
                put("clientMessageId", JsonPrimitive(clientMessageId))
                put("serverSequence", JsonPrimitive(room.serverSequence))
                put("senderParticipantId", JsonPrimitive(participantId))
                put("participantId", JsonPrimitive(participantId))
                put("expectedRevision", JsonPrimitive(room.revision))
                put("authorityEpoch", JsonPrimitive(room.authorityEpoch))
                payload["command"]?.let { put("command", it) }
            },
        )
    }

    /** Why this proposal cannot go to the authority right now, or null when it may. */
    private fun proposalBlocker(
        room: Room,
        payload: JsonObject,
    ): ErrorCode? {
        val expectedRevision = payload.longOrNull("expectedRevision")
        return when {
            !room.started -> ErrorCode.MATCH_NOT_STARTED
            room.paused -> ErrorCode.AUTHORITY_TIMEOUT
            room.member(room.hostParticipantId)?.connected != true -> ErrorCode.AUTHORITY_TIMEOUT
            room.pendingCommandId != null -> ErrorCode.COMMAND_PENDING
            expectedRevision == null -> ErrorCode.INVALID_MESSAGE
            expectedRevision != room.revision -> ErrorCode.STALE_STATE
            else -> null
        }
    }

    private fun commit(
        room: Room,
        participantId: String,
        payload: JsonObject,
    ) {
        if (!room.isHost(participantId)) {
            room.sendTo(participantId, MessageType.ROOM_ERROR, errorPayload(ErrorCode.NOT_ROOM_HOST))
            return
        }
        room.pendingCommandId?.let(room::markProcessed)
        room.pendingCommandId = null
        room.pendingParticipantId = null
        room.revision = payload.longOrNull("revision") ?: (room.revision + 1)
        room.latestSnapshot = payload.toString()
        room.broadcastRaw(
            Wire.encodeRaw(MessageType.COMMAND_COMMIT, room.latestSnapshot.orEmpty()),
            exceptParticipantId = participantId,
        )
    }

    private fun reject(
        room: Room,
        participantId: String,
        payload: JsonObject,
    ) {
        if (!room.isHost(participantId)) return
        payload.stringOrNull("clientMessageId")?.let(room::markProcessed)
        room.pendingCommandId = null
        room.pendingParticipantId = null
        room.broadcast(
            MessageType.COMMAND_REJECT,
            withSender(payload, participantId),
            exceptParticipantId = participantId,
        )
    }

    private fun snapshot(
        room: Room,
        participantId: String,
        payload: JsonObject,
    ) {
        if (!room.isHost(participantId)) {
            room.sendTo(participantId, MessageType.ROOM_ERROR, errorPayload(ErrorCode.NOT_ROOM_HOST))
            return
        }
        room.revision = payload.longOrNull("revision") ?: room.revision
        room.latestSnapshot = payload.toString()
        room.broadcastRaw(
            Wire.encodeRaw(MessageType.SNAPSHOT, room.latestSnapshot.orEmpty()),
            exceptParticipantId = participantId,
        )
    }

    private fun resync(
        room: Room,
        participantId: String,
    ) {
        val snapshot = room.latestSnapshot
        if (snapshot == null) {
            room.sendTo(participantId, MessageType.ROOM_ERROR, errorPayload(ErrorCode.MATCH_NOT_STARTED))
            return
        }
        room.sendRawTo(participantId, Wire.encodeRaw(MessageType.SNAPSHOT, snapshot))
    }

    private fun rejection(
        targetParticipantId: String,
        clientMessageId: String,
        code: ErrorCode,
    ): JsonObject =
        buildJsonObject {
            put("targetParticipantId", JsonPrimitive(targetParticipantId))
            put("clientMessageId", JsonPrimitive(clientMessageId))
            put("code", JsonPrimitive(code.name))
        }

    private fun errorPayload(code: ErrorCode): JsonObject =
        buildJsonObject {
            put("code", JsonPrimitive(code.name))
            put("message", JsonPrimitive(code.name))
        }
}
