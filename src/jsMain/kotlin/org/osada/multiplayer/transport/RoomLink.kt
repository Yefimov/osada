package org.osada.multiplayer.transport

/**
 * The client's connection to the other commander.
 *
 * Two implementations share one message vocabulary (the `MultiplayerMessageType` names), so the
 * session logic above does not care whether the peer sits in a second browser tab or on the room
 * server:
 *
 *  - [LocalRoomLink] — `BroadcastChannel` between two tabs of one browser profile. There is no
 *    server, so the host client also keeps the lobby roster ([serverManaged] is false).
 *  - [OnlineRoomLink] — WebSocket to the self-hosted room server, which owns the roster, the room
 *    code, revision ordering and the stored snapshot ([serverManaged] is true).
 */
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

interface RoomLink {
    /** True when a room server owns the lobby roster and the client must not build its own. */
    val serverManaged: Boolean

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
