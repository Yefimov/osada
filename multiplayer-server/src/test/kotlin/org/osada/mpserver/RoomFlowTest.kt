package org.osada.mpserver

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomFlowTest {
    @Test
    fun `host creates a room and a guest joins it`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            assertTrue(code.length == 6, "room code should be six characters, was '$code'")

            joinRoom(guest, code, "Guest")
            // The host already saw a one-seat lobby when it created the room.
            val participants = awaitLobby(host) { it.size == 2 }
            assertEquals("Ilya", participants[0].jsonObject.stringOrNull("displayName"))
            assertEquals(true, participants[0].jsonObject["isHost"].toString().toBoolean())
            assertEquals("Guest", participants[1].jsonObject.stringOrNull("displayName"))
        }

    @Test
    fun `a guest command reaches the host and the commit reaches the guest`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            startMatch(host, guest)

            guest.sendMessage(
                MessageType.COMMAND_PROPOSE,
                payloadOf("clientMessageId" to "cmd-1", "expectedRevision" to 0),
            )
            val forAuthority = host.await(MessageType.COMMAND_FOR_AUTHORITY)
            assertEquals("cmd-1", forAuthority.payload.stringOrNull("clientMessageId"))
            assertEquals(0L, forAuthority.payload.longOrNull("expectedRevision"))

            host.sendMessage(MessageType.COMMAND_COMMIT, payloadOf("revision" to 1, "stateHash" to "sha256:test"))
            val commit = guest.await(MessageType.COMMAND_COMMIT)
            assertEquals(1L, commit.payload.longOrNull("revision"))
        }

    @Test
    fun `a proposal against an old revision is rejected without bothering the host`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            startMatch(host, guest)

            host.sendMessage(MessageType.SNAPSHOT, payloadOf("revision" to 4, "stateHash" to "sha256:test"))
            guest.await(MessageType.SNAPSHOT)

            guest.sendMessage(
                MessageType.COMMAND_PROPOSE,
                payloadOf("clientMessageId" to "stale-1", "expectedRevision" to 0),
            )
            val rejection = guest.await(MessageType.COMMAND_REJECT)
            assertEquals(ErrorCode.STALE_STATE.name, rejection.payload.stringOrNull("code"))
            assertEquals("stale-1", rejection.payload.stringOrNull("clientMessageId"))
        }

    @Test
    fun `only one command may be outstanding at a time`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            startMatch(host, guest)

            guest.sendMessage(
                MessageType.COMMAND_PROPOSE,
                payloadOf("clientMessageId" to "cmd-1", "expectedRevision" to 0),
            )
            host.await(MessageType.COMMAND_FOR_AUTHORITY)
            guest.sendMessage(
                MessageType.COMMAND_PROPOSE,
                payloadOf("clientMessageId" to "cmd-2", "expectedRevision" to 0),
            )
            val rejection = guest.await(MessageType.COMMAND_REJECT)
            assertEquals(ErrorCode.COMMAND_PENDING.name, rejection.payload.stringOrNull("code"))
        }

    @Test
    fun `a third participant is refused`() =
        testApplication {
            application { roomServerModule(testConfig()) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/mp") {
                val code = createRoom(this, "Ilya")
                client.webSocket("/mp") {
                    joinRoom(this, code, "Guest")
                    client.webSocket("/mp") {
                        sendMessage(MessageType.JOIN_ROOM, payloadOf("roomCode" to code, "displayName" to "Third"))
                        val error = await(MessageType.ROOM_ERROR)
                        assertEquals(ErrorCode.ROOM_FULL.name, error.payload.stringOrNull("code"))
                    }
                }
            }
        }

    @Test
    fun `a guest cannot start the match`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            guest.sendMessage(MessageType.START_GAME_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            val error = guest.await(MessageType.ROOM_ERROR)
            assertEquals(ErrorCode.NOT_ROOM_HOST.name, error.payload.stringOrNull("code"))
        }

    @Test
    fun `joining an unknown room reports a specific error`() =
        testApplication {
            application { roomServerModule(testConfig()) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/mp") {
                sendMessage(MessageType.JOIN_ROOM, payloadOf("roomCode" to "ZZZZZZ", "displayName" to "Guest"))
                val error = await(MessageType.ROOM_ERROR)
                assertEquals(ErrorCode.ROOM_NOT_FOUND.name, error.payload.stringOrNull("code"))
            }
        }
}

internal typealias RoomTestBody =
    suspend ApplicationTestBuilder.(DefaultClientWebSocketSession, DefaultClientWebSocketSession) -> Unit

/** Runs [block] with two connected sockets: the first becomes the host, the second the guest. */
internal fun roomTest(block: RoomTestBody) =
    testApplication {
        application { roomServerModule(testConfig()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/mp") {
            val host = this
            client.webSocket("/mp") {
                block(host, this)
            }
        }
    }

internal suspend fun createRoom(
    session: DefaultClientWebSocketSession,
    displayName: String,
): String {
    session.sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to displayName))
    val welcome = session.await(MessageType.WELCOME)
    return assertNotNull(welcome.payload.stringOrNull("roomCode"))
}

internal suspend fun joinRoom(
    session: DefaultClientWebSocketSession,
    code: String,
    displayName: String,
): JsonObject {
    session.sendMessage(MessageType.JOIN_ROOM, payloadOf("roomCode" to code, "displayName" to displayName))
    return session.await(MessageType.WELCOME).payload
}

internal suspend fun startMatch(
    host: DefaultClientWebSocketSession,
    guest: DefaultClientWebSocketSession,
) {
    host.sendMessage(MessageType.SET_READY, payloadOf("ready" to true))
    guest.sendMessage(MessageType.SET_READY, payloadOf("ready" to true))
    // The two SET_READY messages arrive on independent sockets, so wait for the lobby the server
    // actually settled on instead of assuming the second one has been applied.
    awaitBothReady(host)
    host.sendMessage(MessageType.START_GAME_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
    host.await(MessageType.START_GAME)
    guest.await(MessageType.START_GAME)
}

private suspend fun awaitBothReady(session: DefaultClientWebSocketSession) {
    awaitLobby(session) { participants ->
        participants.size == 2 && participants.all { it.jsonObject["isReady"].toString().toBoolean() }
    }
}

/** Reads LOBBY_STATE broadcasts until one satisfies [predicate], skipping earlier snapshots of it. */
internal suspend fun awaitLobby(
    session: DefaultClientWebSocketSession,
    predicate: (JsonArray) -> Boolean,
): JsonArray {
    while (true) {
        val participants = session.await(MessageType.LOBBY_STATE).payload["participants"]!!.jsonArray
        if (predicate(participants)) return participants
    }
}

class OriginEnforcementTest {
    @Test
    fun `a socket from a foreign origin is closed before it can create a room`() =
        testApplication {
            application { roomServerModule(testConfig().copy(allowedOrigins = setOf("http://osada.example"))) }
            val client = createClient { install(WebSockets) }
            var refused = false
            try {
                client.webSocket("/mp", request = { header(HttpHeaders.Origin, "https://evil.example") }) {
                    sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to "Stranger"))
                    await(MessageType.WELCOME)
                }
            } catch (_: Throwable) {
                refused = true
            }
            assertTrue(refused, "a foreign Origin must not be able to open a room")
        }

    @Test
    fun `the configured origin is still accepted`() =
        testApplication {
            application { roomServerModule(testConfig().copy(allowedOrigins = setOf("http://osada.example"))) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/mp", request = { header(HttpHeaders.Origin, "http://osada.example") }) {
                sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to "Ilya"))
                assertEquals(MessageType.WELCOME, await(MessageType.WELCOME, MessageType.ROOM_ERROR).type)
            }
        }
}

class ScenarioChoiceTest {
    @Test
    fun `the host picks the scenario and both commanders see it`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            awaitLobby(host) { it.size == 2 }

            host.sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf("scenarioFile" to "bn9s00.xml"),
            )
            // The guest already received the lobby it joined into, which carried no scenario yet.
            var scenario: String? = null
            while (scenario == null) {
                scenario = guest.await(MessageType.LOBBY_STATE).payload.stringOrNull("scenarioFile")
            }
            assertEquals("bn9s00.xml", scenario)
        }

    @Test
    fun `changing the scenario clears readiness`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            host.sendMessage(MessageType.SET_READY, payloadOf("ready" to true))
            guest.sendMessage(MessageType.SET_READY, payloadOf("ready" to true))
            awaitLobby(host) { participants ->
                participants.size == 2 && participants.all { it.jsonObject["isReady"].toString().toBoolean() }
            }

            host.sendMessage(MessageType.LOBBY_PATCH_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            val participants =
                awaitLobby(host) { rows ->
                    rows.none { it.jsonObject["isReady"].toString().toBoolean() }
                }
            assertEquals(2, participants.size)
        }

    @Test
    fun `a guest cannot change the scenario`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            guest.sendMessage(MessageType.LOBBY_PATCH_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            val error = guest.await(MessageType.ROOM_ERROR)
            assertEquals(ErrorCode.NOT_ROOM_HOST.name, error.payload.stringOrNull("code"))
        }

    @Test
    fun `a scenario reference that is not a scenario file is refused`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            host.sendMessage(MessageType.LOBBY_PATCH_PROPOSE, payloadOf("scenarioFile" to "../../etc/passwd"))
            val error = host.await(MessageType.ROOM_ERROR)
            assertEquals(ErrorCode.INVALID_MESSAGE.name, error.payload.stringOrNull("code"))
        }

    @Test
    fun `the match starts on the scenario chosen in the lobby`() =
        roomTest { host, guest ->
            val code = createRoom(host, "Ilya")
            joinRoom(guest, code, "Guest")
            // Choose first and let the choice land: picking a scenario clears readiness, so a
            // ready sent alongside the patch could be wiped by it.
            host.sendMessage(MessageType.LOBBY_PATCH_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            while (host.await(MessageType.LOBBY_STATE).payload.stringOrNull("scenarioFile") == null) {
                // keep reading until the patched lobby arrives
            }
            host.sendMessage(MessageType.SET_READY, payloadOf("ready" to true))
            guest.sendMessage(MessageType.SET_READY, payloadOf("ready" to true))
            awaitLobby(host) { participants ->
                participants.size == 2 && participants.all { it.jsonObject["isReady"].toString().toBoolean() }
            }
            // No scenarioFile in the start message: the server falls back to the lobby's choice.
            host.sendMessage(MessageType.START_GAME_PROPOSE, payloadOf("hostParticipantId" to "x"))
            val started = guest.await(MessageType.START_GAME, MessageType.ROOM_ERROR)
            assertEquals(MessageType.START_GAME, started.type)
            assertEquals("bn9s00.xml", started.payload.stringOrNull("scenarioFile"))
        }
}
