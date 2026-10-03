package org.osada.mpserver.room

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.stringOrNull

/** Ephemeral teammate communication: never a game command, revision or retained snapshot. */
internal class MapPingRelay(
    private val clock: () -> Long,
) {
    @Suppress("ReturnCount") // Reject invalid or rate-limited presence before broadcasting.
    fun relay(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        val member = room.member(connection.participantId) ?: return
        if (!room.started || room.mode !in setOf(Room.MODE_SHARED, Room.MODE_COOP_COMMANDERS)) return
        val clean = validatedMapPing(payload, member.participantId) ?: return
        val now = clock()
        if (member.lastMapPingAt?.let { now - it < COOLDOWN_MILLIS } == true) return
        member.lastMapPingAt = now
        // Echo to the sender too: everyone displays only a ping accepted by the room server.
        room.broadcast(MessageType.PRESENCE_MAP_PING, clean)
    }

    private companion object {
        const val COOLDOWN_MILLIS = 1000L
    }
}

/** Coordinates are integers in 0..10000 of the full minimap, independent of viewport and zoom. */
@Suppress("ReturnCount") // Fail closed on each untrusted field before constructing a relay payload.
internal fun validatedMapPing(
    payload: JsonObject,
    participantId: String,
): JsonObject? {
    val x = (payload["x"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
    val y = (payload["y"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
    val mission = payload.stringOrNull("mission") ?: return null
    if (x !in 0L..PING_COORDINATE_SCALE || y !in 0L..PING_COORDINATE_SCALE) return null
    if (mission.isBlank() || mission.length > MAX_MISSION_KEY_LENGTH || mission.any { it.isISOControl() }) return null
    return buildJsonObject {
        put("x", JsonPrimitive(x))
        put("y", JsonPrimitive(y))
        put("mission", JsonPrimitive(mission))
        put("senderParticipantId", JsonPrimitive(participantId))
    }
}

private const val PING_COORDINATE_SCALE = 10_000L
private const val MAX_MISSION_KEY_LENGTH = 160
