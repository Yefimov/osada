@file:Suppress("UnusedParameter")

package org.osada.multiplayer.room

import org.osada.multiplayer.model.ControlScope
import org.osada.multiplayer.model.MultiplayerEndpointConfig
import org.osada.multiplayer.model.MultiplayerParticipant
import org.osada.multiplayer.model.MultiplayerRoomConfig
import org.osada.multiplayer.model.MultiplayerSession
import org.osada.multiplayer.model.PlayerControlPlan
import org.osada.multiplayer.model.PlayerExecution
import org.osada.multiplayer.protocol.ClientProtocolMessage
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.protocol.ServerMessage
import org.osada.multiplayer.protocol.ServerProtocolMessage
import org.osada.multiplayer.transport.ConnectRequest
import org.osada.multiplayer.transport.MultiplayerTransport
import org.osada.multiplayer.transport.Subscription

data class CreateRoomRequest(
    val config: MultiplayerRoomConfig,
    val displayName: String,
)

data class JoinRoomRequest(
    val roomCode: String,
    val inviteToken: String?,
    val reconnectToken: String?,
    val displayName: String,
)

@Suppress("TooManyFunctions")
class MultiplayerRoomClient(
    private val transport: MultiplayerTransport,
    private val endpoint: MultiplayerEndpointConfig,
) {
    private val participants = mutableListOf<MultiplayerParticipant>()
    private var roomConfig: MultiplayerRoomConfig? = null
    private var multiplayerSession: MultiplayerSession? = null
    private var subscription: Subscription? = null

    fun createRoom(request: CreateRoomRequest) {
        roomConfig = request.config
        connect(request.config.roomId, null, null, request.displayName)
        multiplayerSession = buildSession(request.config.createdByParticipantId, request.config)
        send(MultiplayerMessageType.CREATE_ROOM, roomConfigPayload(request.config))
    }

    fun joinRoom(request: JoinRoomRequest) {
        connect(request.roomCode, request.inviteToken, request.reconnectToken, request.displayName)
        send(
            MultiplayerMessageType.JOIN_ROOM,
            """{"roomCode":${JSON.stringify(request.roomCode)},"displayName":${JSON.stringify(request.displayName)}}""",
        )
    }

    fun reconnect(reconnectToken: String) {
        require(reconnectToken.isNotBlank())
        val config = roomConfig ?: error("Reconnect requires known room configuration")
        connect(config.roomId, null, reconnectToken, "")
        send(MultiplayerMessageType.HELLO, """{"reconnectToken":${JSON.stringify(reconnectToken)}}""")
    }

    fun startGame() {
        send(MultiplayerMessageType.START_GAME_PROPOSE, "{}")
    }

    fun setReady(ready: Boolean) {
        send(MultiplayerMessageType.SET_READY, """{"ready":$ready}""")
    }

    fun leaveRoom() {
        if (transport.state == org.osada.multiplayer.transport.TransportState.CONNECTED) {
            send(MultiplayerMessageType.LEAVE_ROOM, "{}")
        }
        subscription?.unsubscribe()
        subscription = null
        transport.close("Participant left the room")
        participants.clear()
        multiplayerSession = null
    }

    fun participants(): List<MultiplayerParticipant> = participants.toList()

    fun session(): MultiplayerSession? = multiplayerSession

    private fun connect(
        roomCode: String,
        inviteToken: String?,
        reconnectToken: String?,
        displayName: String,
    ) {
        subscription?.unsubscribe()
        subscription = transport.onMessage(::handleMessage)
        transport.connect(
            ConnectRequest(
                endpoint = endpoint,
                roomCode = roomCode,
                inviteToken = inviteToken,
                reconnectToken = reconnectToken,
                displayName = displayName,
            ),
        )
    }

    private fun send(
        type: MultiplayerMessageType,
        payload: String,
    ) {
        transport.send(ClientProtocolMessage(type, payload))
    }

    private fun handleMessage(message: ServerMessage) {
        if (message !is ServerProtocolMessage) return
        when (message.type) {
            MultiplayerMessageType.WELCOME -> handleWelcome(message.payloadJson)
            MultiplayerMessageType.LOBBY_STATE -> handleLobbyState(message.payloadJson)
            else -> Unit
        }
    }

    private fun handleWelcome(payloadJson: String) {
        val payload = JSON.parse<dynamic>(payloadJson)
        val participantId = payload.participantId as? String ?: return
        val config = roomConfig ?: return
        multiplayerSession = buildSession(participantId, config)
    }

    private fun handleLobbyState(payloadJson: String) {
        val payload = JSON.parse<dynamic>(payloadJson)
        val values = payload.participants
        if (!(js("Array.isArray(values)") as Boolean)) return
        participants.clear()
        for (index in 0 until (values.length as Number).toInt()) {
            val value = values[index]
            participants +=
                MultiplayerParticipant(
                    participantId = value.participantId as? String ?: continue,
                    displayName = value.displayName as? String ?: "",
                    seatId = value.seatId as? String,
                    isHost = value.isHost as? Boolean ?: false,
                    isReady = value.isReady as? Boolean ?: false,
                    connectionState = org.osada.multiplayer.model.ParticipantConnectionState.CONNECTED,
                )
        }
    }

    private fun buildSession(
        participantId: String,
        config: MultiplayerRoomConfig,
    ): MultiplayerSession {
        val playerIds =
            config.seats
                .filter { it.participantId == participantId }
                .flatMap { it.controlledPlayerIds }
                .toSet()
        val execution =
            config.seats
                .flatMap { seat ->
                    seat.controlledPlayerIds.map { playerId ->
                        playerId to
                            if (seat.participantId == participantId) {
                                PlayerExecution.LOCAL_HUMAN
                            } else {
                                PlayerExecution.REMOTE_HUMAN
                            }
                    }
                }.toMap()
        return MultiplayerSession(
            participantId,
            config,
            PlayerControlPlan(execution, mapOf(participantId to ControlScope(playerIds))),
        )
    }

    private fun roomConfigPayload(config: MultiplayerRoomConfig): String =
        """{"roomId":${JSON.stringify(config.roomId)},"mode":${JSON.stringify(config.mode.name)}}"""
}
