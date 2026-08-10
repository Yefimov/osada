@file:Suppress("UnusedParameter")

package org.osada.multiplayer.transport

import kotlin.js.json

/**
 * Two tabs of the same browser profile talking over `BroadcastChannel`.
 *
 * Kept because it needs no server at all: it is the fastest way to exercise the whole command and
 * commit pipeline while developing, and it still works when the game is opened from a file path.
 */
class LocalRoomLink(
    private val selfParticipantId: String,
    private val listener: RoomLinkListener,
    private val roomCodeFactory: () -> String,
) : RoomLink {
    private var channel: dynamic = null
    private var roomCode: String? = null
    private var currentState = RoomLinkState.IDLE

    override val serverManaged: Boolean = false

    override val state: RoomLinkState
        get() = currentState

    override fun createRoom(displayName: String) {
        val code = roomCodeFactory()
        open(code)
        listener.onWelcome(welcome(code, displayName, isHost = true))
    }

    override fun joinRoom(
        roomCode: String,
        displayName: String,
        reconnectToken: String?,
    ) {
        open(roomCode)
        listener.onWelcome(welcome(roomCode, displayName, isHost = false))
        post(JOIN_ROOM, json("displayName" to displayName))
    }

    override fun post(
        type: String,
        payload: dynamic,
    ) {
        channel?.postMessage(
            json(
                "type" to type,
                "roomCode" to roomCode,
                "senderParticipantId" to selfParticipantId,
                "payload" to payload,
            ),
        )
    }

    override fun close() {
        channel?.close()
        channel = null
        roomCode = null
        setState(RoomLinkState.CLOSED)
    }

    private fun open(code: String) {
        channel?.close()
        roomCode = code
        channel = newBroadcastChannel("osada-mp-$code")
        channel.onmessage = { event: dynamic -> receive(event.data) }
        setState(RoomLinkState.CONNECTED)
    }

    private fun receive(message: dynamic) {
        if (message == null || (message.roomCode as? String) != roomCode) return
        val sender = message.senderParticipantId as? String
        val type = message.type as? String
        // BroadcastChannel does not echo to the sender, but a stale channel from a previous room
        // can still deliver, so both the room and the sender are re-checked here.
        if (sender == null || type == null || sender == selfParticipantId) return
        listener.onRoomMessage(type, sender, message.payload)
    }

    private fun welcome(
        code: String,
        displayName: String,
        isHost: Boolean,
    ): RoomWelcome =
        RoomWelcome(
            participantId = selfParticipantId,
            roomCode = code,
            displayName = displayName,
            isHost = isHost,
            started = false,
            paused = false,
            revision = 0,
            scenarioFile = null,
            reconnectToken = null,
        )

    private fun setState(value: RoomLinkState) {
        if (currentState == value) return
        currentState = value
        listener.onLinkState(value)
    }

    private companion object {
        const val JOIN_ROOM = "JOIN_ROOM"
    }
}

@Suppress("UnusedParameter")
private fun newBroadcastChannel(name: String): dynamic = js("new BroadcastChannel(name)")
