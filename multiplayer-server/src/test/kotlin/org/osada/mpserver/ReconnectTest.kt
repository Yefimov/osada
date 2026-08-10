package org.osada.mpserver

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.osada.mpserver.protocol.ErrorCode
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.longOrNull
import org.osada.mpserver.protocol.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReconnectTest {
    @Test
    fun `a returning guest keeps its seat and receives the stored snapshot`() =
        testApplication {
            application { roomServerModule(testConfig()) }
            val client = createClient { install(WebSockets) }

            client.webSocket("/mp") {
                val host = this
                val code = createRoom(host, "Ilya")
                var reconnectToken = ""

                client.webSocket("/mp") {
                    reconnectToken = assertNotNull(joinRoom(this, code, "Guest").stringOrNull("reconnectToken"))
                    startMatch(host, this)
                    host.sendMessage(
                        MessageType.SNAPSHOT,
                        payloadOf("revision" to 7, "stateHash" to "sha256:stored"),
                    )
                    await(MessageType.SNAPSHOT)
                }

                // The guest socket is gone; its seat is held until the grace period expires.
                client.webSocket("/mp") {
                    sendMessage(
                        MessageType.JOIN_ROOM,
                        payloadOf(
                            "roomCode" to code,
                            "displayName" to "Guest",
                            "reconnectToken" to reconnectToken,
                        ),
                    )
                    val welcome = await(MessageType.WELCOME)
                    assertTrue(welcome.payload["started"].toString().toBoolean())
                    assertEquals(7L, welcome.payload.longOrNull("revision"))

                    val snapshot = await(MessageType.SNAPSHOT)
                    assertEquals("sha256:stored", snapshot.payload.stringOrNull("stateHash"))
                }
            }
        }

    @Test
    fun `an unknown reconnect token is refused instead of silently seating a stranger`() =
        testApplication {
            application { roomServerModule(testConfig()) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/mp") {
                val code = createRoom(this, "Ilya")
                client.webSocket("/mp") {
                    sendMessage(
                        MessageType.JOIN_ROOM,
                        payloadOf("roomCode" to code, "displayName" to "Impostor", "reconnectToken" to "nope"),
                    )
                    val error = await(MessageType.ROOM_ERROR)
                    assertEquals(ErrorCode.RECONNECT_TOKEN_INVALID.name, error.payload.stringOrNull("code"))
                }
            }
        }

    @Test
    fun `the match pauses when the host drops and resumes when it returns`() =
        testApplication {
            application { roomServerModule(testConfig()) }
            val client = createClient { install(WebSockets) }
            var code = ""
            var hostToken = ""

            client.webSocket("/mp") {
                val guest = this
                client.webSocket("/mp") {
                    // This inner socket is the host: it creates the room, the outer one joins.
                    sendMessage(MessageType.CREATE_ROOM, payloadOf("displayName" to "Ilya"))
                    val welcome = await(MessageType.WELCOME).payload
                    code = assertNotNull(welcome.stringOrNull("roomCode"))
                    hostToken = assertNotNull(welcome.stringOrNull("reconnectToken"))
                    joinRoom(guest, code, "Guest")
                    startMatch(this, guest)
                }

                val paused = guest.await(MessageType.PAUSE_STATE)
                assertTrue(paused.payload["paused"].toString().toBoolean())

                client.webSocket("/mp") {
                    sendMessage(
                        MessageType.JOIN_ROOM,
                        payloadOf("roomCode" to code, "displayName" to "Ilya", "reconnectToken" to hostToken),
                    )
                    await(MessageType.WELCOME)
                    val resumed = guest.await(MessageType.PAUSE_STATE)
                    assertEquals(false, resumed.payload["paused"].toString().toBoolean())
                }
            }
        }
}
