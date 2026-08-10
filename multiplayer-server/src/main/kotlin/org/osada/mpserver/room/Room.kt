package org.osada.mpserver.room

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire

/** A seat in a room. Survives a dropped connection until the grace period expires. */
class Member(
    val participantId: String,
    var displayName: String,
    val reconnectTokenHash: String,
) {
    var ready: Boolean = false
    var connection: RoomConnection? = null
    var disconnectedAtMillis: Long? = null

    val connected: Boolean
        get() = connection != null
}

/**
 * Server-side state of one match.
 *
 * The server is the room authority (membership, ordering, revisions, stored snapshots); the *game*
 * authority stays on the host client, which owns the rules and produces every snapshot. See
 * docs/multiplayer-server-deployment.md for why the split is drawn here.
 */
class Room(
    val code: String,
    createdAtMillis: Long,
) {
    val mutex = Mutex()
    val members = mutableListOf<Member>()

    var hostParticipantId: String? = null
    var authorityEpoch: Long = 0
    var revision: Long = 0
    var serverSequence: Long = 0
    var started: Boolean = false
    var paused: Boolean = false
    var scenarioFile: String? = null

    /**
     * Latest snapshot payload as JSON text, exactly as the host client serialized it.
     *
     * Deliberately not a parsed [JsonObject]: the server never looks inside a snapshot beyond the
     * revision it reads once on arrival, and a parsed tree of a full game state costs several times
     * the text. With one retained snapshot per room that difference decides whether a busy server
     * fits in its heap.
     */
    var latestSnapshot: String? = null

    var pendingCommandId: String? = null

    /** Who proposed [pendingCommandId]; a timeout has to be reported back to that client. */
    var pendingParticipantId: String? = null
    var pendingSinceMillis: Long = 0
    var lastActivityAtMillis: Long = createdAtMillis

    private val processedCommandIds = LinkedHashSet<String>()

    fun member(participantId: String?): Member? = members.firstOrNull { it.participantId == participantId }

    fun memberByTokenHash(tokenHash: String): Member? = members.firstOrNull { it.reconnectTokenHash == tokenHash }

    fun isHost(participantId: String?): Boolean = participantId != null && participantId == hostParticipantId

    fun markProcessed(clientMessageId: String): Boolean {
        if (!processedCommandIds.add(clientMessageId)) return false
        if (processedCommandIds.size > MAX_PROCESSED_IDS) {
            val oldest = processedCommandIds.first()
            processedCommandIds.remove(oldest)
        }
        return true
    }

    fun wasProcessed(clientMessageId: String): Boolean = clientMessageId in processedCommandIds

    fun broadcast(
        type: MessageType,
        payload: JsonObject,
        exceptParticipantId: String? = null,
    ) {
        // Encoded once rather than per recipient: for a committed snapshot that is the difference
        // between serializing the whole game state once and doing it for every commander.
        broadcastRaw(Wire.encode(type, payload), exceptParticipantId)
    }

    fun broadcastRaw(
        raw: String,
        exceptParticipantId: String? = null,
    ) {
        members
            .filter { it.participantId != exceptParticipantId }
            .forEach { it.connection?.send(raw) }
    }

    fun sendTo(
        participantId: String?,
        type: MessageType,
        payload: JsonObject,
    ) {
        member(participantId)?.connection?.send(type, payload)
    }

    fun sendRawTo(
        participantId: String?,
        raw: String,
    ) {
        member(participantId)?.connection?.send(raw)
    }

    fun lobbyState(): JsonObject =
        buildJsonObject {
            put("roomCode", JsonPrimitive(code))
            put("hostParticipantId", JsonPrimitive(hostParticipantId))
            put("started", JsonPrimitive(started))
            put("paused", JsonPrimitive(paused))
            put("revision", JsonPrimitive(revision))
            put("scenarioFile", JsonPrimitive(scenarioFile))
            put(
                "participants",
                buildJsonArray {
                    members.forEach { member ->
                        add(
                            buildJsonObject {
                                put("participantId", JsonPrimitive(member.participantId))
                                put("displayName", JsonPrimitive(member.displayName))
                                put("isHost", JsonPrimitive(member.participantId == hostParticipantId))
                                put("isReady", JsonPrimitive(member.ready))
                                put("connected", JsonPrimitive(member.connected))
                            },
                        )
                    }
                },
            )
        }

    private companion object {
        const val MAX_PROCESSED_IDS = 512
    }
}
