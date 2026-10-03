package org.osada.mpserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.osada.mpserver.protocol.Wire
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.room.Member
import org.osada.mpserver.room.Room
import org.osada.mpserver.room.RoomConnection
import org.osada.mpserver.room.RoomSideSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RoomSideSelectionTest {
    private val room =
        Room("ABC123", 0).apply {
            hostParticipantId = "host"
            scenarioFile = "bn9s00.xml"
        }
    private val host = attach("host")
    private val guest = attach("guest")

    private fun attach(id: String) =
        RoomConnection(id).also { connection ->
            connection.room = room
            connection.participantId = id
            room.members +=
                Member(id, id, "unused").apply {
                    this.connection = connection
                    ready = true
                }
        }

    private fun patch(
        player: Int,
        file: String = "bn9s00.xml",
    ) = JsonObject(
        mapOf(
            "humanPlayerId" to JsonPrimitive(player),
            "scenarioFile" to JsonPrimitive(file),
        ),
    )

    @Test fun bothRoomModesBroadcastTheChosenArmyAndResetReadiness() {
        listOf(Room.MODE_SHARED, Room.MODE_COOP_COMMANDERS).forEach { mode ->
            room.mode = mode
            room.humanPlayerId = 0
            room.members.forEach { it.ready = true }
            RoomSideSelection.patch(host, room, patch(1))
            assertEquals(1, room.humanPlayerId)
            assertFalse(room.members.any { it.ready })
            assertEquals("1", room.lobbyState()["humanPlayerId"].toString())
        }
    }

    @Test fun guestsAndStaleOrCampaignChoicesAreRejectedWithoutMutation() {
        RoomSideSelection.patch(guest, room, patch(1))
        assertEquals(
            "NOT_ROOM_HOST",
            Wire.decode(guest.outbound.tryReceive().getOrThrow()).payload.stringOrNull("code"),
        )
        RoomSideSelection.patch(host, room, patch(1, "another.xml"))
        assertEquals(0, room.humanPlayerId)
        RoomSideSelection.patch(host, room, patch(-1))
        assertEquals(0, room.humanPlayerId)
        room.campaignFile = "camp6bn5.json"
        RoomSideSelection.patch(host, room, patch(1))
        assertEquals(0, room.humanPlayerId)
    }
}
