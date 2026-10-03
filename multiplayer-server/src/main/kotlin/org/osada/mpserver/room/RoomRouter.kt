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
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.protocol.withSender

/** Routes one decoded message from one connection. */
@Suppress("TooManyFunctions")
class RoomRouter(
    registry: RoomRegistry,
    private val config: ServerConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val admission = RoomAdmission(registry, config, clock)
    private val commands = RoomCommandHandler(clock)
    private val mapPings = MapPingRelay(clock)

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
            MessageType.LOBBY_PATCH_PROPOSE -> patchLobby(connection, room, envelope.payload)
            MessageType.START_GAME_PROPOSE -> startGame(connection, room, envelope.payload)
            MessageType.LEAVE_ROOM -> leaveRoom(connection, room)
            MessageType.HEARTBEAT -> connection.send(MessageType.HEARTBEAT, EMPTY)
            MessageType.PRESENCE_MAP_PING -> mapPings.relay(connection, room, envelope.payload)
            MessageType.PRESENCE_SELECT_UNIT,
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

    /**
     * The host choosing which scenario the room will play, before it starts.
     *
     * Readiness is cleared on every change: agreeing to fight one battle is not agreeing to fight
     * whichever one the host picked afterwards.
     */
    @Suppress("ReturnCount")
    private fun patchLobby(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        if (room.started) return
        if (payload.containsKey("recovery")) {
            patchRecovery(connection, room, payload)
            return
        }
        if (payload.containsKey("profile")) {
            patchProfile(connection, room, payload)
            return
        }
        if (payload.containsKey("campaignFile")) {
            patchCampaign(connection, room, payload)
            return
        }
        if (payload.containsKey("humanPlayerId")) {
            RoomSideSelection.patch(connection, room, payload)
            return
        }
        val scenarioFile = payload.stringOrNull("scenarioFile").orEmpty()
        val refusal =
            when {
                !room.isHost(connection.participantId) ->
                    ErrorCode.NOT_ROOM_HOST to "Only the room host can change the setup."

                !Identifiers.isScenarioFile(scenarioFile) ->
                    ErrorCode.INVALID_MESSAGE to "Invalid scenario reference."

                else -> null
            }
        if (refusal != null) {
            connection.sendError(refusal.first, refusal.second)
            return
        }
        if (room.scenarioFile != scenarioFile || room.campaignFile != null) {
            room.scenarioFile = scenarioFile
            room.campaignFile = null
            room.humanPlayerId = 0
            room.members.forEach { it.ready = false }
            room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
        }
    }

    /**
     * The host choosing a whole CAMPAIGN for a cooperative room. A legacy two-player room cannot play
     * one: its campaign flow was never wired to the room, so the request is refused rather than
     * accepted and then broken at the first scenario change.
     */
    private fun patchCampaign(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        val campaignFile = payload.stringOrNull("campaignFile").orEmpty()
        val difficulty = (payload.longOrNull("difficulty") ?: 0L).toInt()
        val refusal =
            when {
                !room.isHost(connection.participantId) ->
                    ErrorCode.NOT_ROOM_HOST to "Only the room host can change the setup."

                !room.isCoop -> ErrorCode.INVALID_MESSAGE to "Campaigns are only available in cooperative rooms."
                !Identifiers.isCampaignFile(campaignFile) -> ErrorCode.INVALID_MESSAGE to "Invalid campaign reference."
                difficulty !in MIN_DIFFICULTY..MAX_DIFFICULTY -> ErrorCode.INVALID_MESSAGE to "Invalid difficulty."
                else -> null
            }
        if (refusal != null) {
            connection.sendError(refusal.first, refusal.second)
            return
        }
        if (room.campaignFile != campaignFile || room.difficulty != difficulty) {
            room.campaignFile = campaignFile
            room.humanPlayerId = 0
            room.difficulty = difficulty
            room.scenarioFile = null
            room.members.forEach { it.ready = false }
            room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
        }
    }

    /**
     * A member editing THEIR OWN commander card. The server keeps the profile as an opaque, size-
     * bounded JSON object and relays it in the lobby state; whether a loadout is legal is the game's
     * rule and is re-validated by the game authority at start, so nothing here interprets it.
     * Changing a loadout withdraws that member's readiness, and only theirs.
     */
    private fun patchProfile(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        val member = room.member(connection.participantId) ?: return
        val profile = payload["profile"] as? JsonObject
        when {
            !room.isCoop -> connection.sendError(ErrorCode.INVALID_MESSAGE, "This room has no commander cards.")
            profile == null || profile.toString().length > MAX_PROFILE_CHARS ->
                connection.sendError(ErrorCode.INVALID_MESSAGE, "Invalid commander profile.")

            else -> {
                member.profile = profile
                member.ready = false
                room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
            }
        }
    }

    /** Why the host may not start the match right now, or null. */
    private fun patchRecovery(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        val plan = payload["recovery"] as? JsonObject
        when {
            !room.isHost(
                connection.participantId,
            ) -> connection.sendError(ErrorCode.NOT_ROOM_HOST, "Only the host can bind saved commanders.")
            plan == null ||
                !RoomRecovery.validPlan(
                    room,
                    plan,
                ) -> connection.sendError(ErrorCode.INVALID_MESSAGE, "Invalid recovery setup or participant binding.")
            room.recovery != plan -> {
                room.recovery = plan
                room.scenarioFile = plan.stringOrNull("scenarioFile")
                room.campaignFile = plan.stringOrNull("campaignFile")
                room.difficulty = plan.longOrNull("difficulty")!!.toInt()
                room.members.forEach { it.ready = false }
                room.broadcast(MessageType.LOBBY_STATE, room.lobbyState())
            }
        }
    }

    private fun startRefusal(
        connection: RoomConnection,
        room: Room,
        scenarioFile: String,
    ): Pair<ErrorCode, String>? =
        when {
            !room.isHost(connection.participantId) ->
                ErrorCode.NOT_ROOM_HOST to "Only the room host can start the match."

            room.members.size < room.minimumToStart(config) ||
                room.members.size > room.capacity(config) ||
                room.members.any { !it.ready || !it.connected } ->
                ErrorCode.INVALID_MESSAGE to
                    if (room.isCoop) {
                        "Two to four commanders must be connected and ready."
                    } else {
                        "Both commanders must be connected and ready."
                    }

            room.isCoop && room.members.any { it.profile == null } ->
                ErrorCode.INVALID_MESSAGE to "Every commander needs a profile."

            room.campaignFile == null && !Identifiers.isScenarioFile(scenarioFile) ->
                ErrorCode.INVALID_MESSAGE to "Invalid scenario reference."

            else -> null
        }

    @Suppress("ReturnCount")
    private fun startGame(
        connection: RoomConnection,
        room: Room,
        payload: JsonObject,
    ) {
        if (room.started) return
        val campaignFile = room.campaignFile
        val scenarioFile =
            if (campaignFile != null) "" else payload.stringOrNull("scenarioFile") ?: room.scenarioFile.orEmpty()
        val refusal = startRefusal(connection, room, scenarioFile)
        if (refusal != null) {
            connection.sendError(refusal.first, refusal.second)
            return
        }
        val recovery = payload["recoverySnapshot"] as? JsonObject
        if (RoomRecovery.invalidBootstrap(room, payload)) {
            connection.sendError(ErrorCode.INVALID_MESSAGE, "Invalid recovery snapshot or participant bindings.")
            return
        }
        room.started = true
        room.scenarioFile =
            if (recovery != null) room.recovery?.stringOrNull("scenarioFile") else scenarioFile.ifEmpty { null }
        room.revision = recovery?.longOrNull("revision") ?: 0
        room.authorityEpoch = 0
        room.latestSnapshot = recovery?.toString()
        room.broadcast(
            MessageType.START_GAME,
            buildJsonObject {
                put("scenarioFile", JsonPrimitive(room.scenarioFile))
                put("campaignFile", JsonPrimitive(campaignFile))
                put("difficulty", JsonPrimitive(room.difficulty))
                put("humanPlayerId", JsonPrimitive(room.humanPlayerId))
                put("mode", JsonPrimitive(room.mode))
                put("hostParticipantId", JsonPrimitive(room.hostParticipantId))
                put("revision", JsonPrimitive(room.revision))
                put("authorityEpoch", JsonPrimitive(room.authorityEpoch))
                put("recovery", JsonPrimitive(recovery != null))
            },
        )
        recovery?.let { room.broadcast(MessageType.SNAPSHOT, it) }
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
        const val MAX_PROFILE_CHARS = 4096
        const val MIN_DIFFICULTY = 0
        const val MAX_DIFFICULTY = 2
    }
}
