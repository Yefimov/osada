package org.osada.mpserver

import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.room.MapPingRelay
import org.osada.mpserver.room.Member
import org.osada.mpserver.room.Room
import org.osada.mpserver.room.RoomConnection
import org.osada.mpserver.room.validatedMapPing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapPingTest {
    @Test
    fun senderIsAuthenticatedAndOnlyCoordinatesAndMissionAreRelayed() {
        val clean =
            assertNotNull(
                validatedMapPing(
                    payloadOf(
                        "x" to 1234,
                        "y" to 5678,
                        "mission" to "0:bn9s00.xml",
                        "senderParticipantId" to "forged",
                        "name" to "forged",
                        "unitId" to 25,
                    ),
                    "actual",
                ),
            )
        assertEquals("actual", clean.stringOrNull("senderParticipantId"))
        assertEquals(setOf("x", "y", "mission", "senderParticipantId"), clean.keys)
    }

    @Test
    fun malformedCoordinatesAndMissionAreDropped() {
        for (bad in listOf(-1, 10001, 0.5, "500", true, payloadOf())) {
            assertNull(validatedMapPing(payloadOf("x" to bad, "y" to 5, "mission" to "m"), "a"))
        }
        for (bad in listOf("", "x".repeat(161), "a\nb")) {
            assertNull(validatedMapPing(payloadOf("x" to 0, "y" to 10000, "mission" to bad), "a"))
        }
        assertNotNull(validatedMapPing(payloadOf("x" to 0, "y" to 10000, "mission" to "m"), "a"))
    }

    @Test
    fun pingIsRoomLocalRateLimitedAndDoesNotChangeCanonicalState() {
        var now = 0L
        val relay = MapPingRelay { now }
        val room = Room("ABCDEF", 0)
        val otherRoom = Room("OTHER1", 0)
        val a = seat(room, "a")
        val b = seat(room, "b")
        val stranger = seat(otherRoom, "c")
        val payload = payloadOf("x" to 2500, "y" to 7500, "mission" to "0:bn9s00.xml")
        relay.relay(a, room, payload)
        assertTrue(b.outbound.tryReceive().isFailure, "No lobby pings")
        room.started = true
        room.revision = 42
        room.latestSnapshot = "unchanged"
        relay.relay(a, room, payload)
        assertEquals(
            MessageType.PRESENCE_MAP_PING,
            Wire.decode(assertNotNull(a.outbound.tryReceive().getOrNull())).type,
        )
        assertEquals(
            "a",
            Wire.decode(assertNotNull(b.outbound.tryReceive().getOrNull())).payload.stringOrNull("senderParticipantId"),
        )
        assertTrue(stranger.outbound.tryReceive().isFailure)
        relay.relay(a, room, payload)
        assertTrue(b.outbound.tryReceive().isFailure, "Repeated ping inside cooldown is dropped")
        now = 1000
        relay.relay(a, room, payload)
        assertFalse(b.outbound.tryReceive().isFailure)
        assertEquals(42L, room.revision)
        assertEquals("unchanged", room.latestSnapshot)
    }

    private fun seat(
        room: Room,
        id: String,
    ): RoomConnection =
        RoomConnection(id).also {
            it.room = room
            it.participantId = id
            room.members += Member(id, id, id).apply { connection = it }
        }
}
