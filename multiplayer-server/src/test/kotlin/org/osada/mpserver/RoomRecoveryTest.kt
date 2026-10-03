package org.osada.mpserver

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.osada.mpserver.protocol.Envelope
import org.osada.mpserver.protocol.MessageType
import org.osada.mpserver.protocol.Wire
import org.osada.mpserver.protocol.stringOrNull
import org.osada.mpserver.room.Member
import org.osada.mpserver.room.Room
import org.osada.mpserver.room.RoomConnection
import org.osada.mpserver.room.RoomRegistry
import org.osada.mpserver.room.RoomRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomRecoveryTest {
    private val room =
        Room("NEW123", 0).apply {
            mode = "COOP_COMMANDERS"
            hostParticipantId = "new-host"
        }
    private val host = attach("new-host")
    private val peer = attach("new-peer")
    private val router = RoomRouter(RoomRegistry(testConfig()), testConfig())
    private val plan =
        parse(
            """{
                "snapshotHash":"sha256:saved", "scenarioFile":"bn9s00.xml", "campaignFile":null,
                "difficulty":0, "turn":4, "revision":9,
                "commanders":[{"id":"c1","name":"Same"},{"id":"c2","name":"Same"}],
                "bindings":{"c1":"new-peer","c2":"new-host"}
            }""",
        )
    private val snapshot =
        parse(
            """{
                "snapshotFormatVersion":1, "protocolVersion":1, "roomConfigHash":"room:NEW123",
                "revision":9, "authorityEpoch":0, "stateHash":"sha256:rebound",
                "gameState":{"scenario":{"file":"bn9s00.xml"}},
                "multiplayerState":{
                    "status":"RUNNING", "revision":9, "authorityEpoch":0,
                    "authorityParticipantId":"new-host", "readyParticipantIds":["new-host","new-peer"],
                    "coop":{"commanders":[
                        {"id":"c1","participantId":"new-peer"},{"id":"c2","participantId":"new-host"}
                    ]}
                }
            }""",
        )

    private fun attach(id: String): RoomConnection =
        RoomConnection(id).also { connection ->
            connection.room = room
            connection.participantId = id
            room.members +=
                Member(id, "Same", "unused").apply {
                    ready = true
                    profile = parse("{}")
                    this.connection = connection
                }
        }

    private fun parse(source: String): JsonObject = Wire.json.parseToJsonElement(source).jsonObject

    private fun frames(connection: RoomConnection): List<Envelope> =
        buildList {
            while (true) add(Wire.decode(connection.outbound.tryReceive().getOrNull() ?: break))
        }

    private suspend fun setup() {
        router.handle(host, Envelope(MessageType.LOBBY_PATCH_PROPOSE, JsonObject(mapOf("recovery" to plan))))
        room.members.forEach { it.ready = true }
        frames(host)
        frames(peer)
    }

    @Test
    fun onlyTheHostMayApproveSavedCommanderBindingsOrStartRecovery() =
        runBlocking {
            router.handle(peer, Envelope(MessageType.LOBBY_PATCH_PROPOSE, JsonObject(mapOf("recovery" to plan))))
            assertEquals("NOT_ROOM_HOST", frames(peer).last().payload.stringOrNull("code"))
            assertNull(room.recovery)
            setup()
            router.handle(
                peer,
                Envelope(MessageType.START_GAME_PROPOSE, JsonObject(mapOf("recoverySnapshot" to snapshot))),
            )
            assertFalse(room.started)
            assertEquals("NOT_ROOM_HOST", frames(peer).last().payload.stringOrNull("code"))
        }

    @Test
    fun duplicateStaleAndDisconnectedBindingsAreRefusedWithoutMutation() =
        runBlocking {
            val invalid =
                listOf(plan.toString().replace("new-peer", "new-host"), plan.toString().replace("new-peer", "old-peer"))
            invalid.forEach {
                router.handle(
                    host,
                    Envelope(MessageType.LOBBY_PATCH_PROPOSE, JsonObject(mapOf("recovery" to parse(it)))),
                )
                assertEquals("INVALID_MESSAGE", frames(host).last().payload.stringOrNull("code"))
                assertNull(room.recovery)
            }
            room.member("new-peer")!!.connection = null
            router.handle(host, Envelope(MessageType.LOBBY_PATCH_PROPOSE, JsonObject(mapOf("recovery" to plan))))
            assertEquals("INVALID_MESSAGE", frames(host).last().payload.stringOrNull("code"))
            assertNull(room.recovery)
        }

    @Test
    fun invalidBootstrapDoesNotStartOrStoreAPartialRoom() =
        runBlocking {
            setup()
            listOf(
                snapshot.toString().replace("new-peer", "old-peer"),
                snapshot.toString().replace("room:NEW123", "room:OLD123"),
                snapshot.toString().replace("\"revision\":9", "\"revision\":0"),
            ).forEach {
                router.handle(
                    host,
                    Envelope(
                        MessageType.START_GAME_PROPOSE,
                        JsonObject(
                            mapOf(
                                "recoverySnapshot" to parse(it),
                            ),
                        ),
                    ),
                )
                assertEquals("INVALID_MESSAGE", frames(host).last().payload.stringOrNull("code"))
                assertFalse(room.started)
                assertNull(room.latestSnapshot)
            }
        }

    @Test
    fun startBroadcastsTheCheckpointToEveryoneAndResumedCommandsUseTheSavedRevision() =
        runBlocking {
            setup()
            router.handle(
                host,
                Envelope(MessageType.START_GAME_PROPOSE, JsonObject(mapOf("recoverySnapshot" to snapshot))),
            )
            assertTrue(room.started)
            assertEquals(9L, room.revision)
            listOf(host, peer).forEach {
                val received = frames(it)
                assertEquals(listOf(MessageType.START_GAME, MessageType.SNAPSHOT), received.map { frame -> frame.type })
                assertEquals(snapshot, received.last().payload)
            }
            router.handle(
                peer,
                Envelope(
                    MessageType.COMMAND_PROPOSE,
                    parse(
                        """{"clientMessageId":"after-restart","expectedRevision":9,""" +
                            """"participantId":"spoof","command":{}}""",
                    ),
                ),
            )
            assertEquals("new-peer", frames(host).last().payload.stringOrNull("senderParticipantId"))
            router.handle(
                host,
                Envelope(MessageType.COMMAND_COMMIT, JsonObject(snapshot + ("revision" to JsonPrimitive(10)))),
            )
            assertEquals(10L, room.revision)
            assertEquals(MessageType.COMMAND_COMMIT, frames(peer).last().type)
            val receipt = frames(host).last()
            assertEquals(MessageType.SNAPSHOT_ACK, receipt.type)
            assertNull(receipt.payload["gameState"], "receipts do not replay the host's state")
        }
}
