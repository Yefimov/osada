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

    /** The commander profile a cooperative lobby card shows, as the JSON the member sent (size- and
     *  shape-checked on arrival). The server stores and relays it; it never interprets it. */
    var profile: JsonObject? = null
    var connection: RoomConnection? = null
    var disconnectedAtMillis: Long? = null
    var lastMapPingAt: Long? = null

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
@Suppress("TooManyFunctions")
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
    var humanPlayerId: Int = 0

    /** A cooperative room may play a whole campaign instead of one battle; null means a battle. */
    var campaignFile: String? = null
    var difficulty: Int = 0
    var recovery: JsonObject? = null

    /** [MODE_SHARED] is the original two-human room; [MODE_COOP_COMMANDERS] the 2-4 player mode. */
    var mode: String = MODE_SHARED

    val isCoop: Boolean
        get() = mode == MODE_COOP_COMMANDERS

    /** Seats this room admits. Distinct from [minimumToStart]. */
    fun capacity(config: org.osada.mpserver.ServerConfig): Int =
        if (isCoop) config.maxCoopParticipants else config.maxParticipantsPerRoom

    /** Connected, ready humans needed before the host may start. A legacy room needs every seat. */
    fun minimumToStart(config: org.osada.mpserver.ServerConfig): Int =
        if (isCoop) org.osada.mpserver.ServerConfig.MIN_COOP_PARTICIPANTS else config.maxParticipantsPerRoom

    /**
     * Latest snapshot payload as JSON text, exactly as the host client serialized it.
     *
     * Deliberately not a retained parsed [JsonObject]: normal commits only need their revision.
     * Recovery bootstrap also checks the envelope and authenticated seat bindings, without game
     * rule evaluation. A parsed tree costs several times the text; retaining one text snapshot per
     * room keeps the shared VPS heap bounded.
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
            recovery?.let { put("recovery", it) }
            put("hostParticipantId", JsonPrimitive(hostParticipantId))
            put("started", JsonPrimitive(started))
            put("paused", JsonPrimitive(paused))
            put("revision", JsonPrimitive(revision))
            put("scenarioFile", JsonPrimitive(scenarioFile))
            put("campaignFile", JsonPrimitive(campaignFile))
            put("difficulty", JsonPrimitive(difficulty))
            put("humanPlayerId", JsonPrimitive(humanPlayerId))
            put("mode", JsonPrimitive(mode))
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
                                member.profile?.let { put("profile", it) }
                            },
                        )
                    }
                },
            )
        }

    companion object {
        const val MODE_SHARED = "SHARED"
        const val MODE_COOP_COMMANDERS = "COOP_COMMANDERS"
        val MODES = setOf(MODE_SHARED, MODE_COOP_COMMANDERS)
        private const val MAX_PROCESSED_IDS = 512
    }
}
