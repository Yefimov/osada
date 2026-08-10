@file:Suppress("UnusedParameter")

package org.osada.multiplayer.transport

import org.osada.multiplayer.model.MultiplayerEndpointConfig
import org.osada.multiplayer.protocol.ClientProtocolMessage
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.protocol.MultiplayerProtocolCodec
import org.osada.multiplayer.protocol.ServerProtocolMessage
import kotlin.js.json

/**
 * WebSocket link to the self-hosted room server (`multiplayer-server/`).
 *
 * The server owns the room roster, the room code and command ordering; the host client remains the
 * game authority. Messages the session understands are handed over under their protocol names, with
 * two translations: the server's `COMMAND_FOR_AUTHORITY` is delivered as the guest's original
 * `COMMAND_PROPOSE`, and a local `START_GAME` request goes out as `START_GAME_PROPOSE`, because
 * only the server may declare a match started.
 */
class OnlineRoomLink(
    private val endpoint: MultiplayerEndpointConfig,
    private val listener: RoomLinkListener,
) : RoomLink {
    private val codec = MultiplayerProtocolCodec()
    private var socket: dynamic = null
    private var currentState = RoomLinkState.IDLE
    private var pendingHandshake: (() -> Unit)? = null

    override val state: RoomLinkState
        get() = currentState

    override fun createRoom(displayName: String) {
        connect { send(MultiplayerMessageType.CREATE_ROOM, json("displayName" to displayName)) }
    }

    override fun joinRoom(
        roomCode: String,
        displayName: String,
        reconnectToken: String?,
    ) {
        connect {
            send(
                MultiplayerMessageType.JOIN_ROOM,
                json(
                    "roomCode" to roomCode,
                    "displayName" to displayName,
                    "reconnectToken" to reconnectToken,
                ),
            )
        }
    }

    override fun post(
        type: String,
        payload: dynamic,
    ) {
        val messageType = messageType(type) ?: return
        val outgoing =
            if (messageType == MultiplayerMessageType.START_GAME) {
                MultiplayerMessageType.START_GAME_PROPOSE
            } else {
                messageType
            }
        send(outgoing, payload)
    }

    override fun close() {
        pendingHandshake = null
        socket?.close(NORMAL_CLOSE_CODE, "Participant left the room")
        socket = null
        setState(RoomLinkState.CLOSED)
    }

    private fun connect(handshake: () -> Unit) {
        check(endpoint.webSocketBaseUrl.isNotEmpty()) { "No multiplayer endpoint for this origin" }
        pendingHandshake = handshake
        setState(RoomLinkState.CONNECTING)
        socket = newWebSocket(endpoint.webSocketBaseUrl)
        socket.onopen = {
            setState(RoomLinkState.CONNECTED)
            pendingHandshake?.invoke()
            pendingHandshake = null
        }
        socket.onclose = { setState(RoomLinkState.CLOSED) }
        socket.onerror = {
            val connected = currentState == RoomLinkState.CONNECTED
            setState(if (connected) RoomLinkState.RECONNECTING else RoomLinkState.CLOSED)
        }
        socket.onmessage = { event: dynamic -> receive(event.data as? String) }
    }

    private fun send(
        type: MultiplayerMessageType,
        payload: dynamic,
    ) {
        val active = socket ?: return
        active.send(codec.encode(ClientProtocolMessage(type, JSON.stringify(payload))))
    }

    private fun receive(raw: String?) {
        if (raw == null) return
        val message =
            runCatching { codec.decodeServer(raw) }
                .getOrNull() as? ServerProtocolMessage ?: return
        val payload = JSON.parse<dynamic>(message.payloadJson)
        when (message.type) {
            MultiplayerMessageType.WELCOME -> listener.onWelcome(welcomeOf(payload))
            MultiplayerMessageType.LOBBY_STATE -> deliverLobby(payload)
            MultiplayerMessageType.ROOM_ERROR ->
                listener.onRoomError(
                    payload.code as? String ?: "INVALID_MESSAGE",
                    payload.message as? String ?: "",
                )

            MultiplayerMessageType.COMMAND_FOR_AUTHORITY ->
                listener.onRoomMessage(
                    MultiplayerMessageType.COMMAND_PROPOSE.name,
                    payload.senderParticipantId as? String ?: "",
                    payload,
                )

            MultiplayerMessageType.HEARTBEAT -> Unit
            else ->
                listener.onRoomMessage(
                    message.type.name,
                    payload.senderParticipantId as? String ?: "",
                    payload,
                )
        }
    }

    private fun deliverLobby(payload: dynamic) {
        val values = payload.participants
        val participants = mutableListOf<RoomLobbyParticipant>()
        if (js("Array.isArray(values)") as Boolean) {
            for (index in 0 until (values.length as Number).toInt()) {
                val value = values[index]
                participants +=
                    RoomLobbyParticipant(
                        participantId = value.participantId as? String ?: continue,
                        displayName = value.displayName as? String ?: "",
                        isHost = value.isHost as? Boolean ?: false,
                        ready = value.isReady as? Boolean ?: false,
                        connected = value.connected as? Boolean ?: true,
                    )
            }
        }
        listener.onLobby(
            payload.hostParticipantId as? String,
            participants,
            payload.scenarioFile as? String,
        )
    }

    private fun welcomeOf(payload: dynamic): RoomWelcome =
        RoomWelcome(
            participantId = payload.participantId as? String ?: "",
            roomCode = payload.roomCode as? String ?: "",
            displayName = payload.displayName as? String ?: "",
            isHost = payload.isHost as? Boolean ?: false,
            started = payload.started as? Boolean ?: false,
            paused = payload.paused as? Boolean ?: false,
            revision = (payload.revision as? Number)?.toLong() ?: 0L,
            scenarioFile = payload.scenarioFile as? String,
            reconnectToken = payload.reconnectToken as? String,
        )

    private fun messageType(name: String): MultiplayerMessageType? =
        MultiplayerMessageType.entries.firstOrNull { it.name == name }

    private fun setState(value: RoomLinkState) {
        if (currentState == value) return
        currentState = value
        listener.onLinkState(value)
    }

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
    }
}

@Suppress("UnusedParameter")
private fun newWebSocket(url: String): dynamic = js("new WebSocket(url)")
