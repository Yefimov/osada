package org.osada.mpserver.room

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.osada.mpserver.ServerConfig
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType

/** All live rooms, keyed by their public code. */
class RoomRegistry(
    private val config: ServerConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val rooms = linkedMapOf<String, Room>()

    suspend fun roomCount(): Int = mutex.withLock { rooms.size }

    suspend fun find(code: String): Room? = mutex.withLock { rooms[code.uppercase()] }

    suspend fun create(): Room? =
        mutex.withLock {
            if (rooms.size >= config.maxRooms) return@withLock null
            var code = Identifiers.roomCode()
            var attempts = 0
            while (rooms.containsKey(code) && attempts < MAX_CODE_ATTEMPTS) {
                code = Identifiers.roomCode()
                attempts++
            }
            if (rooms.containsKey(code)) return@withLock null
            Room(code, clock()).also { rooms[code] = it }
        }

    suspend fun remove(code: String) {
        mutex.withLock { rooms.remove(code) }
    }

    private suspend fun snapshotRooms(): List<Room> = mutex.withLock { rooms.values.toList() }

    /**
     * One sweep of housekeeping: drop seats whose grace period expired, close idle rooms and
     * un-stick a match whose authority never answered a proposed command.
     */
    suspend fun sweep() {
        val now = clock()
        for (room in snapshotRooms()) {
            val closable =
                room.mutex.withLock {
                    expireDisconnectedMembers(room, now)
                    expirePendingCommand(room, now)
                    room.members.isEmpty() || now - room.lastActivityAtMillis > config.roomIdleTimeoutMillis
                }
            if (closable) closeRoom(room)
        }
    }

    private fun expireDisconnectedMembers(
        room: Room,
        now: Long,
    ) {
        val expired =
            room.members.filter { member ->
                val since = member.disconnectedAtMillis ?: return@filter false
                now - since > config.disconnectGraceMillis
            }
        if (expired.isEmpty()) return
        room.members.removeAll(expired)
        if (room.members.isEmpty()) return
        if (room.member(room.hostParticipantId) == null) {
            // The host is gone for good. Before the match starts the remaining player simply
            // becomes the new host; afterwards the room stays paused, because only the original
            // authority client holds the game rules for the running match.
            if (room.started) {
                room.paused = true
                room.broadcast(
                    MessageType.PAUSE_STATE,
                    buildJsonObject {
                        put("paused", JsonPrimitive(true))
                        put("reason", JsonPrimitive(ErrorCode.AUTHORITY_TIMEOUT.name))
                    },
                )
            } else {
                promoteHost(room)
            }
        }
        room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
    }

    private fun promoteHost(room: Room) {
        val successor = room.members.firstOrNull() ?: return
        room.hostParticipantId = successor.participantId
        room.authorityEpoch++
        room.pendingCommandId = null
        room.broadcast(
            MessageType.AUTHORITY_CHANGED,
            buildJsonObject {
                put("authorityParticipantId", JsonPrimitive(successor.participantId))
                put("authorityEpoch", JsonPrimitive(room.authorityEpoch))
                put("revision", JsonPrimitive(room.revision))
            },
        )
    }

    private fun expirePendingCommand(
        room: Room,
        now: Long,
    ) {
        val pending = room.pendingCommandId ?: return
        if (now - room.pendingSinceMillis <= config.authorityTimeoutMillis) return
        val proposer = room.pendingParticipantId
        room.pendingCommandId = null
        room.pendingParticipantId = null
        room.broadcast(
            MessageType.COMMAND_REJECT,
            buildJsonObject {
                // The client only reacts to a rejection addressed to it, so the proposer is named
                // here; without it a timed-out order would stay "pending" on their screen forever.
                put("targetParticipantId", JsonPrimitive(proposer))
                put("clientMessageId", JsonPrimitive(pending))
                put("code", JsonPrimitive(ErrorCode.AUTHORITY_TIMEOUT.name))
                put("message", JsonPrimitive("The host did not confirm the order in time."))
            },
        )
    }

    private suspend fun closeRoom(room: Room) {
        room.mutex.withLock {
            room.members.forEach { it.connection?.close() }
            room.members.clear()
        }
        remove(room.code)
    }

    private companion object {
        const val MAX_CODE_ATTEMPTS = 16
    }
}
