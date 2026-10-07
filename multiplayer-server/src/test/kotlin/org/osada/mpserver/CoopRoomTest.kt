package org.osada.mpserver

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The 2-4 player cooperative-commanders room (`docs/multiplayer/cooperative-commanders.md` §11). */
class CoopRoomTest {
    @Test
    fun `a cooperative room admits four and refuses a fifth`() =
        coopTest(seats = 5) { sessions ->
            val code = createCoop(sessions[0], "A")
            (1..3).forEach { joinRoom(sessions[it], code, "P$it") }
            sessions[4].sendMessage(MessageType.JOIN_ROOM, payloadOf("roomCode" to code, "displayName" to "P4"))
            val error = sessions[4].await(MessageType.ROOM_ERROR)
            assertEquals(ErrorCode.ROOM_FULL.name, error.payload.stringOrNull("code"))
        }

    @Test
    fun `a legacy room still holds exactly two`() =
        coopTest(seats = 3) { sessions ->
            sessions[0].sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to "A"))
            val code = sessions[0].await(MessageType.WELCOME).payload.stringOrNull("roomCode")!!
            joinRoom(sessions[1], code, "B")
            sessions[2].sendMessage(MessageType.JOIN_ROOM, payloadOf("roomCode" to code, "displayName" to "C"))
            assertEquals(
                ErrorCode.ROOM_FULL.name,
                sessions[2].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
        }

    @Test
    fun `two ready commanders can start a four seat room without filler`() =
        coopTest(seats = 2) { sessions ->
            val code = createCoop(sessions[0], "A")
            joinRoom(sessions[1], code, "B")
            sessions.forEach { it.sendMessage(MessageType.LOBBY_PATCH_PROPOSE, profile("p")) }
            sessions.forEach { it.sendMessage(MessageType.SET_READY, payloadOf("ready" to true)) }
            awaitLobby(sessions[0]) { rows -> rows.size == 2 && rows.all { it.ready() } }
            sessions[0].sendMessage(MessageType.START_GAME_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            val started = sessions[1].await(MessageType.START_GAME, MessageType.ROOM_ERROR)
            assertEquals(MessageType.START_GAME, started.type)
            assertEquals("COOP_COMMANDERS", started.payload.stringOrNull("mode"))
        }

    @Test
    fun `one commander alone cannot start`() =
        coopTest(seats = 1) { sessions ->
            createCoop(sessions[0], "A")
            sessions[0].sendMessage(MessageType.LOBBY_PATCH_PROPOSE, profile("p"))
            sessions[0].sendMessage(MessageType.SET_READY, payloadOf("ready" to true))
            awaitLobby(sessions[0]) { rows -> rows.all { it.ready() } }
            sessions[0].sendMessage(MessageType.START_GAME_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[0].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
        }

    @Test
    fun `a commander without a profile blocks the start`() =
        coopTest(seats = 2) { sessions ->
            val code = createCoop(sessions[0], "A")
            joinRoom(sessions[1], code, "B")
            sessions[0].sendMessage(MessageType.LOBBY_PATCH_PROPOSE, profile("p"))
            sessions.forEach { it.sendMessage(MessageType.SET_READY, payloadOf("ready" to true)) }
            awaitLobby(sessions[0]) { rows -> rows.size == 2 && rows.all { it.ready() } }
            sessions[0].sendMessage(MessageType.START_GAME_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[0].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
        }

    @Test
    fun `a profile is relayed to the room and withdraws only its owner's readiness`() =
        coopTest(seats = 2) { sessions ->
            val code = createCoop(sessions[0], "A")
            joinRoom(sessions[1], code, "B")
            sessions.forEach { it.sendMessage(MessageType.SET_READY, payloadOf("ready" to true)) }
            awaitLobby(sessions[0]) { rows -> rows.size == 2 && rows.all { it.ready() } }

            sessions[1].sendMessage(MessageType.LOBBY_PATCH_PROPOSE, profile("changed"))
            val rows = awaitLobby(sessions[0]) { r -> r.any { it.jsonObject["profile"] != null } }
            val b = rows.first { it.jsonObject.stringOrNull("displayName") == "B" }.jsonObject
            assertEquals("changed", b["profile"]!!.jsonObject.stringOrNull("name"))
            assertEquals(false, b["isReady"].toString().toBoolean())
            val a = rows.first { it.jsonObject.stringOrNull("displayName") == "A" }.jsonObject
            assertEquals(true, a["isReady"].toString().toBoolean())
        }

    @Test
    fun `an oversized or non object profile is refused`() =
        coopTest(seats = 1) { sessions ->
            createCoop(sessions[0], "A")
            sessions[0].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf("profile" to "x".repeat(10)),
            )
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[0].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
            sessions[0].sendMessage(MessageType.LOBBY_PATCH_PROPOSE, profile("x".repeat(5000)))
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[0].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
        }

    @Test
    fun `a legacy room has no commander cards`() =
        coopTest(seats = 1) { sessions ->
            sessions[0].sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to "A"))
            sessions[0].await(MessageType.WELCOME)
            sessions[0].sendMessage(MessageType.LOBBY_PATCH_PROPOSE, profile("p"))
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[0].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
        }

    @Test
    fun `an unknown mode falls back to the legacy room`() =
        coopTest(seats = 1) { sessions ->
            sessions[0].sendMessage(
                MessageType.CREATE_ROOM,
                payloadOf("displayName" to "A", "mode" to "TOTALLY_NEW"),
            )
            val welcome = sessions[0].await(MessageType.WELCOME).payload
            assertEquals("SHARED", welcome.stringOrNull("mode"))
        }

    @Test
    fun `welcome announces mode and capacity`() =
        coopTest(seats = 1) { sessions ->
            sessions[0].sendMessage(
                MessageType.CREATE_ROOM,
                payloadOf("displayName" to "A", "mode" to "COOP_COMMANDERS"),
            )
            val welcome = sessions[0].await(MessageType.WELCOME).payload
            assertEquals("COOP_COMMANDERS", welcome.stringOrNull("mode"))
            assertNotNull(welcome["capacity"])
            assertNull(welcome.stringOrNull("nothing"))
        }

    @Test
    fun theHostChoosesACampaignAndTheLobbyShowsIt() =
        coopTest(seats = 2) { sessions ->
            val code = createCoop(sessions[0], "A")
            joinRoom(sessions[1], code, "B")
            sessions[0].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf("campaignFile" to "camp6bn9.json", "difficulty" to 2),
            )
            var lobby = sessions[1].await(MessageType.LOBBY_STATE).payload
            while (lobby.stringOrNull("campaignFile") ==
                null
            ) {
                lobby = sessions[1].await(MessageType.LOBBY_STATE).payload
            }
            assertEquals("camp6bn9.json", lobby.stringOrNull("campaignFile"))
            assertEquals(2L, lobby.longOrNull("difficulty"))
            assertNull(lobby.stringOrNull("scenarioFile"), "a campaign replaces the single battle")
        }

    @Test
    fun choosingABattleAfterwardsDropsTheCampaign() =
        coopTest(seats = 1) { sessions ->
            createCoop(sessions[0], "A")
            sessions[0].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf(
                    "campaignFile" to "camp6bn9.json",
                    "difficulty" to 1,
                ),
            )
            sessions[0].sendMessage(MessageType.LOBBY_PATCH_PROPOSE, payloadOf("scenarioFile" to "bn9s00.xml"))
            var lobby = sessions[0].await(MessageType.LOBBY_STATE).payload
            while (lobby.stringOrNull("scenarioFile") ==
                null
            ) {
                lobby = sessions[0].await(MessageType.LOBBY_STATE).payload
            }
            assertNull(lobby.stringOrNull("campaignFile"))
        }

    @Test
    fun aLegacyRoomGuestAndABadCampaignNameAreRefused() =
        coopTest(seats = 2) { sessions ->
            sessions[0].sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to "A"))
            sessions[0].await(MessageType.WELCOME)
            sessions[0].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf(
                    "campaignFile" to "camp6bn9.json",
                    "difficulty" to 1,
                ),
            )
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[0].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )

            val code = createCoop(sessions[1], "C")
            sessions[1].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf(
                    "campaignFile" to "../../etc/passwd.json",
                    "difficulty" to 1,
                ),
            )
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[1].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
            sessions[1].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf(
                    "campaignFile" to "camp6bn9.json",
                    "difficulty" to 9,
                ),
            )
            assertEquals(
                ErrorCode.INVALID_MESSAGE.name,
                sessions[1].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
            assertNotNull(code)
        }

    @Test
    fun aGuestCannotChooseTheCampaign() =
        coopTest(seats = 2) { sessions ->
            val code = createCoop(sessions[0], "A")
            joinRoom(sessions[1], code, "B")
            sessions[1].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf(
                    "campaignFile" to "camp6bn9.json",
                    "difficulty" to 1,
                ),
            )
            assertEquals(
                ErrorCode.NOT_ROOM_HOST.name,
                sessions[1].await(MessageType.ROOM_ERROR).payload.stringOrNull("code"),
            )
        }

    @Test
    fun aCampaignRoomStartsAndTellsEveryoneWhichCampaign() =
        coopTest(seats = 2) { sessions ->
            val code = createCoop(sessions[0], "A")
            joinRoom(sessions[1], code, "B")
            sessions[0].sendMessage(
                MessageType.LOBBY_PATCH_PROPOSE,
                payloadOf(
                    "campaignFile" to "camp6bn9.json",
                    "difficulty" to 2,
                ),
            )
            sessions.forEach { it.sendMessage(MessageType.LOBBY_PATCH_PROPOSE, profile("p")) }
            // Readiness is cleared by every setup change, so ready up only once the choices have landed.
            awaitLobby(sessions[0]) { rows -> rows.size == 2 && rows.all { it.jsonObject["profile"] != null } }
            sessions.forEach { it.sendMessage(MessageType.SET_READY, payloadOf("ready" to true)) }
            awaitLobby(sessions[0]) { rows -> rows.size == 2 && rows.all { it.ready() } }
            sessions[0].sendMessage(MessageType.START_GAME_PROPOSE, payloadOf())
            val started = sessions[1].await(MessageType.START_GAME, MessageType.ROOM_ERROR)
            assertEquals(MessageType.START_GAME, started.type)
            assertEquals("camp6bn9.json", started.payload.stringOrNull("campaignFile"))
            assertEquals(2L, started.payload.longOrNull("difficulty"))
        }

    private fun profile(name: String): JsonObject =
        buildJsonObject {
            put("profile", buildJsonObject { put("name", JsonPrimitive(name)) })
        }

    private suspend fun createCoop(
        session: DefaultClientWebSocketSession,
        name: String,
    ): String {
        session.sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to name, "mode" to "COOP_COMMANDERS"))
        return assertNotNull(session.await(MessageType.WELCOME).payload.stringOrNull("roomCode"))
    }

    private fun kotlinx.serialization.json.JsonElement.ready(): Boolean = jsonObject["isReady"].toString().toBoolean()

    private fun coopTest(
        seats: Int,
        block: suspend (List<DefaultClientWebSocketSession>) -> Unit,
    ) = testApplication {
        application { roomServerModule(testConfig()) }
        val client = createClient { install(WebSockets) }

        suspend fun open(
            remaining: Int,
            opened: List<DefaultClientWebSocketSession>,
        ) {
            if (remaining == 0) {
                block(opened)
                return
            }
            client.webSocket("/mp") { open(remaining - 1, opened + this) }
        }
        open(seats, emptyList())
    }
}
