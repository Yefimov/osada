package org.osada.mpserver.room

import kotlinx.serialization.json.JsonObject
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull

internal object RoomSideSelection {
    fun patch(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        val player = payload.longOrNull("humanPlayerId")
        val refusal =
            when {
                !room.isHost(connection.participantId) -> ErrorCode.NOT_ROOM_HOST
                room.campaignFile != null ||
                    room.recovery != null ||
                    room.scenarioFile == null ||
                    payload.stringOrNull("scenarioFile") != room.scenarioFile ||
                    player == null ||
                    player !in 0..15 -> ErrorCode.INVALID_MESSAGE
                else -> null
            }
        if (refusal != null) {
            connection.sendError(refusal, "Invalid side selection for this room setup.")
        } else if (room.humanPlayerId != player?.toInt()) {
            room.humanPlayerId = requireNotNull(player).toInt()
            room.members.forEach { it.ready = false }
            room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
        }
    }
}
