package org.osada.mpserver

import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire
import org.osada.mpserver.protocol.WireFormatException
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.room.Identifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireTest {
    @Test
    fun `round trips an envelope`() {
        val encoded = Wire.encode(MessageType.LOBBY_STATE, payloadOf("roomCode" to "AB2C34"))
        val decoded = Wire.decode(encoded)
        assertEquals(MessageType.LOBBY_STATE, decoded.type)
        assertEquals("AB2C34", decoded.payload.stringOrNull("roomCode"))
    }

    @Test
    fun `rejects a different protocol version`() {
        val failure =
            assertFailsWith<WireFormatException> {
                Wire.decode("""{"protocolVersion":99,"type":"HEARTBEAT","sentAt":1,"payload":{}}""")
            }
        assertTrue(failure.message.orEmpty().contains("99"))
    }

    @Test
    fun `rejects unknown message types instead of guessing`() {
        assertFailsWith<WireFormatException> {
            Wire.decode("""{"protocolVersion":1,"type":"DROP_TABLE","sentAt":1,"payload":{}}""")
        }
    }

    @Test
    fun `rejects malformed json and missing payloads`() {
        assertFailsWith<WireFormatException> { Wire.decode("not json") }
        assertFailsWith<WireFormatException> {
            Wire.decode("""{"protocolVersion":1,"type":"HEARTBEAT","sentAt":1}""")
        }
    }

    @Test
    fun `a raw payload is embedded without being reparsed`() {
        // Stored snapshots travel back out as text; the envelope must still decode as one message.
        val payloadJson = """{"revision":12,"gameState":{"fmt":2},"stateHash":"sha256:x"}"""
        val decoded = Wire.decode(Wire.encodeRaw(MessageType.SNAPSHOT, payloadJson))
        assertEquals(MessageType.SNAPSHOT, decoded.type)
        assertEquals("sha256:x", decoded.payload.stringOrNull("stateHash"))
    }

    @Test
    fun `error frames carry a machine readable code`() {
        val decoded = Wire.decode(Wire.error(ErrorCode.ROOM_FULL, "This room is full."))
        assertEquals(MessageType.ROOM_ERROR, decoded.type)
        assertEquals("ROOM_FULL", decoded.payload.stringOrNull("code"))
    }
}

class IdentifiersTest {
    @Test
    fun `room codes use the unambiguous alphabet`() {
        repeat(200) {
            val code = Identifiers.roomCode()
            assertTrue(Identifiers.isRoomCode(code), "unexpected room code '$code'")
        }
    }

    @Test
    fun `display names are trimmed, bounded and stripped of control characters`() {
        assertEquals("Commander", Identifiers.sanitizeDisplayName(null))
        assertEquals("Commander", Identifiers.sanitizeDisplayName("   "))
        assertEquals("Ilya", Identifiers.sanitizeDisplayName(" Ilya "))
        assertEquals(
            Identifiers.MAX_DISPLAY_NAME_LENGTH,
            Identifiers.sanitizeDisplayName("x".repeat(200)).length,
        )
    }

    @Test
    fun `reconnect tokens are only ever compared by hash`() {
        val token = Identifiers.reconnectToken()
        assertEquals(Identifiers.hashToken(token), Identifiers.hashToken(token))
        assertFalse(Identifiers.hashToken(token) == token)
    }

    @Test
    fun `scenario references cannot be anything but a scenario file`() {
        assertTrue(Identifiers.isScenarioFile("bn9s00.xml"))
        assertTrue(Identifiers.isScenarioFile("mp_shared_coop_smoke.xml"))
        assertFalse(Identifiers.isScenarioFile("../../etc/passwd"))
        assertFalse(Identifiers.isScenarioFile("..xml"))
        assertFalse(Identifiers.isScenarioFile("/etc/passwd"))
        assertFalse(Identifiers.isScenarioFile("bn9s00.xml.sh"))
        assertFalse(Identifiers.isScenarioFile("bn9s00"))
        assertFalse(Identifiers.isScenarioFile(""))
        assertFalse(Identifiers.isScenarioFile("a".repeat(200) + ".xml"))
    }
}

class OriginTest {
    @Test
    fun `an empty allowlist accepts any origin`() {
        assertTrue(originAllowed("https://example.invalid", testConfig()))
        assertTrue(originAllowed(null, testConfig()))
    }

    @Test
    fun `a configured allowlist rejects everything else`() {
        val config = testConfig().copy(allowedOrigins = setOf("https://osada.example"))
        assertTrue(originAllowed("https://osada.example", config))
        assertTrue(originAllowed("https://osada.example/", config))
        assertFalse(originAllowed("https://evil.example", config))
        assertFalse(originAllowed(null, config))
    }
}
