@file:Suppress("TooManyFunctions")

package org.osada.multiplayer.transport

import org.osada.multiplayer.protocol.ClientProtocolMessage
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.protocol.ServerProtocolMessage
import kotlin.js.Date
import kotlin.js.json

internal object InMemoryRoomServer {
    private data class Member(
        val participantId: String,
        val transport: InMemoryTransport,
        val displayName: String,
        var ready: Boolean = false,
    )

    private data class Room(
        val roomCode: String,
        val members: MutableList<Member> = mutableListOf(),
        var authorityParticipantId: String = "",
        var authorityEpoch: Long = 0,
        var revision: Long = 0,
        var serverSequence: Long = 0,
        var started: Boolean = false,
        var pending: Boolean = false,
        var pendingClientMessageId: String? = null,
        val processedClientMessageIds: MutableSet<String> = mutableSetOf(),
    )

    private val rooms = mutableMapOf<String, Room>()
    private val connected = mutableSetOf<InMemoryTransport>()
    private var nextParticipantId = 1L

    fun connect(transport: InMemoryTransport) {
        connected += transport
    }

    fun disconnect(transport: InMemoryTransport) {
        connected -= transport
        val room =
            rooms.values.firstOrNull { candidate ->
                candidate.members.any { it.transport === transport }
            } ?: return
        val member = room.members.first { it.transport === transport }
        room.members.remove(member)
        if (room.members.isEmpty()) {
            rooms.remove(room.roomCode)
            return
        }
        if (member.participantId == room.authorityParticipantId) {
            room.authorityParticipantId = room.members.first().participantId
            room.authorityEpoch++
            room.pending = false
            broadcast(
                room,
                MultiplayerMessageType.AUTHORITY_CHANGED,
                json(
                    "authorityParticipantId" to room.authorityParticipantId,
                    "authorityEpoch" to room.authorityEpoch.toDouble(),
                    "revision" to room.revision.toDouble(),
                ),
            )
        }
        broadcastLobby(room)
    }

    fun receive(
        transport: InMemoryTransport,
        message: ClientProtocolMessage,
    ) {
        when (message.type) {
            MultiplayerMessageType.CREATE_ROOM -> createRoom(transport)
            MultiplayerMessageType.JOIN_ROOM, MultiplayerMessageType.HELLO -> joinRoom(transport)
            MultiplayerMessageType.SET_READY -> setReady(transport, message.payloadJson)
            MultiplayerMessageType.START_GAME_PROPOSE -> startGame(transport)
            MultiplayerMessageType.COMMAND_PROPOSE -> proposeCommand(transport, message.payloadJson)
            MultiplayerMessageType.COMMAND_COMMIT -> commitCommand(transport, message.payloadJson)
            MultiplayerMessageType.COMMAND_REJECT -> rejectCommand(transport, message.payloadJson)
            MultiplayerMessageType.LEAVE_ROOM -> disconnect(transport)
            MultiplayerMessageType.SNAPSHOT, MultiplayerMessageType.RESYNC_REQUEST ->
                relaySnapshotMessage(transport, message)

            else -> relay(transport, message)
        }
    }

    private fun createRoom(transport: InMemoryTransport) {
        val request = requireNotNull(transport.connectRequest)
        val roomCode = request.roomCode ?: error("Room code is required")
        require(roomCode !in rooms) { "Room already exists" }
        val member = member(transport, request.displayName)
        val room =
            Room(
                roomCode = roomCode,
                members = mutableListOf(member),
                authorityParticipantId = member.participantId,
            )
        rooms[roomCode] = room
        welcome(room, member)
        broadcastLobby(room)
    }

    private fun joinRoom(transport: InMemoryTransport) {
        val request = requireNotNull(transport.connectRequest)
        val room = rooms[request.roomCode] ?: error("Room not found")
        if (room.members.any { it.transport === transport }) return
        require(!room.started) { "Match already started" }
        require(room.members.size < MAX_HUMAN_PARTICIPANTS) { "Room is full" }
        val member = member(transport, request.displayName)
        room.members += member
        welcome(room, member)
        broadcastLobby(room)
    }

    private fun setReady(
        transport: InMemoryTransport,
        payloadJson: String,
    ) {
        val room = roomFor(transport)
        val payload = JSON.parse<dynamic>(payloadJson)
        room.members.first { it.transport === transport }.ready = payload.ready as? Boolean ?: false
        broadcastLobby(room)
    }

    private fun startGame(transport: InMemoryTransport) {
        val room = roomFor(transport)
        require(memberFor(room, transport).participantId == room.authorityParticipantId)
        require(room.members.size == MAX_HUMAN_PARTICIPANTS && room.members.all { it.ready })
        room.started = true
        broadcast(
            room,
            MultiplayerMessageType.START_GAME,
            json("revision" to room.revision.toDouble(), "authorityEpoch" to room.authorityEpoch.toDouble()),
        )
    }

    private fun proposeCommand(
        transport: InMemoryTransport,
        commandJson: String,
    ) {
        val room = roomFor(transport)
        require(room.started)
        val proposal = JSON.parse<dynamic>(commandJson)
        val clientMessageId = proposal.clientMessageId as? String ?: error("Missing clientMessageId")
        if (clientMessageId in room.processedClientMessageIds) return
        val expectedRevision = (proposal.expectedRevision as? Number)?.toLong() ?: error("Missing expectedRevision")
        val authorityEpoch = (proposal.authorityEpoch as? Number)?.toLong() ?: error("Missing authorityEpoch")
        if (expectedRevision != room.revision || authorityEpoch != room.authorityEpoch) {
            transport.deliver(
                ServerProtocolMessage(
                    MultiplayerMessageType.COMMAND_REJECT,
                    JSON.stringify(
                        json(
                            "code" to "STALE_STATE",
                            "message" to "Expected revision $expectedRevision, current revision is ${room.revision}",
                        ),
                    ),
                ),
            )
            return
        }
        require(!room.pending) { "Another command is awaiting authority" }
        room.pending = true
        room.pendingClientMessageId = clientMessageId
        room.serverSequence++
        val proposer = memberFor(room, transport)
        val payload =
            json(
                "clientMessageId" to clientMessageId,
                "serverSequence" to room.serverSequence.toDouble(),
                "participantId" to proposer.participantId,
                "expectedRevision" to room.revision.toDouble(),
                "authorityEpoch" to room.authorityEpoch.toDouble(),
                "command" to proposal.command,
            )
        room.members
            .first { it.participantId == room.authorityParticipantId }
            .transport
            .deliver(ServerProtocolMessage(MultiplayerMessageType.COMMAND_FOR_AUTHORITY, JSON.stringify(payload)))
    }

    private fun commitCommand(
        transport: InMemoryTransport,
        snapshotJson: String,
    ) {
        val room = roomFor(transport)
        require(memberFor(room, transport).participantId == room.authorityParticipantId)
        require(room.pending)
        room.pendingClientMessageId?.let { room.processedClientMessageIds += it }
        room.pending = false
        room.pendingClientMessageId = null
        room.revision++
        broadcast(room, MultiplayerMessageType.COMMAND_COMMIT, JSON.parse<dynamic>(snapshotJson))
    }

    private fun rejectCommand(
        transport: InMemoryTransport,
        payloadJson: String,
    ) {
        val room = roomFor(transport)
        require(memberFor(room, transport).participantId == room.authorityParticipantId)
        room.pendingClientMessageId?.let { room.processedClientMessageIds += it }
        room.pending = false
        room.pendingClientMessageId = null
        broadcast(room, MultiplayerMessageType.COMMAND_REJECT, JSON.parse<dynamic>(payloadJson))
    }

    private fun relaySnapshotMessage(
        transport: InMemoryTransport,
        message: ClientProtocolMessage,
    ) {
        val room = roomFor(transport)
        val authority = room.members.first { it.participantId == room.authorityParticipantId }
        authority.transport.deliver(ServerProtocolMessage(message.type, message.payloadJson))
    }

    private fun relay(
        transport: InMemoryTransport,
        message: ClientProtocolMessage,
    ) {
        val room = roomFor(transport)
        broadcast(room, message.type, JSON.parse<dynamic>(message.payloadJson))
    }

    private fun welcome(
        room: Room,
        member: Member,
    ) {
        member.transport.deliver(
            ServerProtocolMessage(
                MultiplayerMessageType.WELCOME,
                JSON.stringify(
                    json(
                        "participantId" to member.participantId,
                        "authorityParticipantId" to room.authorityParticipantId,
                        "authorityEpoch" to room.authorityEpoch.toDouble(),
                        "revision" to room.revision.toDouble(),
                    ),
                ),
            ),
        )
    }

    private fun broadcastLobby(room: Room) {
        val participants =
            room.members
                .map { member ->
                    json(
                        "participantId" to member.participantId,
                        "displayName" to member.displayName,
                        "isHost" to (member.participantId == room.authorityParticipantId),
                        "isReady" to member.ready,
                    )
                }.toTypedArray()
        broadcast(room, MultiplayerMessageType.LOBBY_STATE, json("participants" to participants))
    }

    private fun broadcast(
        room: Room,
        type: MultiplayerMessageType,
        payload: dynamic,
    ) {
        val message = ServerProtocolMessage(type, JSON.stringify(payload))
        room.members.forEach { it.transport.deliver(message) }
    }

    private fun member(
        transport: InMemoryTransport,
        displayName: String,
    ): Member =
        Member(
            participantId = "p_${nextParticipantId++}_${Date.now().toLong().toString(ID_RADIX)}",
            transport = transport,
            displayName = displayName.trim().take(MAX_DISPLAY_NAME_LENGTH).ifEmpty { "Commander" },
        )

    private fun roomFor(transport: InMemoryTransport): Room =
        rooms.values.firstOrNull { room -> room.members.any { it.transport === transport } }
            ?: error("Transport has not joined a room")

    private fun memberFor(
        room: Room,
        transport: InMemoryTransport,
    ): Member = room.members.first { it.transport === transport }

    private const val MAX_HUMAN_PARTICIPANTS = 2
    private const val MAX_DISPLAY_NAME_LENGTH = 40
    private const val ID_RADIX = 36
}
