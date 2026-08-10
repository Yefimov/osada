package org.osada.multiplayer.transport

enum class RoomLinkState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    CLOSED,
}

data class RoomWelcome(
    val participantId: String,
    val roomCode: String,
    val displayName: String,
    val isHost: Boolean,
    val started: Boolean,
    val paused: Boolean,
    val revision: Long,
    val scenarioFile: String?,
    val reconnectToken: String?,
)

data class RoomLobbyParticipant(
    val participantId: String,
    val displayName: String,
    val isHost: Boolean,
    val ready: Boolean,
    val connected: Boolean,
)

interface RoomLinkListener {
    fun onLinkState(state: RoomLinkState)

    fun onWelcome(welcome: RoomWelcome)

    fun onLobby(
        hostParticipantId: String?,
        participants: List<RoomLobbyParticipant>,
        scenarioFile: String?,
    )

    /** Everything the session itself interprets: START_GAME, SNAPSHOT, COMMAND_*. */
    fun onRoomMessage(
        type: String,
        senderParticipantId: String,
        payload: dynamic,
    )

    fun onRoomError(
        code: String,
        message: String,
    )
}

/**
 * The client's connection to the other commander.
 *
 * [OnlineRoomLink] is the only implementation: a WebSocket to the self-hosted room server, which
 * owns the roster, the room code, revision ordering and the stored snapshot. The interface stays
 * because the session above is written against it, and a second transport (a relay, a local test
 * double) can be dropped in without touching the session.
 *
 * A `BroadcastChannel` two-tab mode used to live here as well. It was removed once the server was
 * running: it could only ever join two tabs of one browser profile — never two browsers, never two
 * devices — which is not what anyone means by multiplayer, and it made every message path exist in
 * two variants. Point a local build at a server with `?mpEndpoint=ws://host/mp` instead.
 */

interface RoomLink {
    val state: RoomLinkState

    fun createRoom(displayName: String)

    fun joinRoom(
        roomCode: String,
        displayName: String,
        reconnectToken: String?,
    )

    fun post(
        type: String,
        payload: dynamic,
    )

    fun close()
}
