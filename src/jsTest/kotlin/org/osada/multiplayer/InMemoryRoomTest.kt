package org.osada.multiplayer

import org.osada.multiplayer.model.MultiplayerEndpointConfig
import org.osada.multiplayer.protocol.ClientProtocolMessage
import org.osada.multiplayer.protocol.MultiplayerMessageType
import org.osada.multiplayer.transport.ConnectRequest
import org.osada.multiplayer.transport.InMemoryTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryRoomTest {
    private val endpoint = MultiplayerEndpointConfig("test", "", "")

    @Test
    fun twoClientsJoinAndReceiveLobbyState() {
        val host = InMemoryTransport()
        val guest = InMemoryTransport()
        val hostMessages = mutableListOf<MultiplayerMessageType>()
        val guestMessages = mutableListOf<MultiplayerMessageType>()
        host.onMessage { hostMessages += it.type }
        guest.onMessage { guestMessages += it.type }

        host.connect(connectRequest("room-${kotlin.js.Date.now()}", "Host"))
        host.send(ClientProtocolMessage(MultiplayerMessageType.CREATE_ROOM, "{}"))
        val roomCode = host.connectRequestForTest()

        guest.connect(connectRequest(roomCode, "Guest"))
        guest.send(ClientProtocolMessage(MultiplayerMessageType.JOIN_ROOM, "{}"))

        assertTrue(MultiplayerMessageType.WELCOME in hostMessages)
        assertTrue(MultiplayerMessageType.WELCOME in guestMessages)
        assertEquals(2, hostMessages.count { it == MultiplayerMessageType.LOBBY_STATE })

        host.close()
        guest.close()
    }

    private fun connectRequest(
        roomCode: String,
        displayName: String,
    ): ConnectRequest =
        ConnectRequest(
            endpoint = endpoint,
            roomCode = roomCode,
            inviteToken = null,
            reconnectToken = null,
            displayName = displayName,
        )

    private fun InMemoryTransport.connectRequestForTest(): String = requireNotNull(connectRequest?.roomCode)
}
