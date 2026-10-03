package org.osada.multiplayer.coop

import org.osada.GameStateSerializer
import org.osada.multiplayer.model.MatchStatus
import org.osada.multiplayer.model.MultiplayerRuntimeState
import org.osada.multiplayer.sync.CanonicalStateHasher
import org.osada.multiplayer.sync.MultiplayerSnapshot
import org.osada.multiplayer.sync.MultiplayerSnapshotValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoopCheckpointTest {
    private fun snapshot(): MultiplayerSnapshot {
        val state =
            CoopState(
                listOf(
                    CoopCommander("c1", 0, "old-host", "Same", CoopPortrait.generated(3), listOf("coop.marksman")),
                    CoopCommander("c2", 1, "old-peer", "Same", CoopPortrait.generated(8), emptyList()),
                ),
                0,
            )
        state.missionFile = "bn9s00.xml"
        state.turnKey = "4:0"
        state.claim(12, "c1")
        state.requestTransfer(12, "c1", "c2")
        state.setDone("c1", true)
        state.ledger.beginRevision(9)
        state.ledger.recordObjective("2,2", "c1")
        state.ledger.recordLoss("c2", 1, true, 5)
        state.ledger.recordDamage(88, "c1", 3)
        val runtime =
            MultiplayerRuntimeState(
                MatchStatus.RUNNING,
                9,
                "old-host",
                2,
                setOf("old-host", "old-peer"),
                emptyMap(),
                emptyMap(),
                null,
                CoopStateJson.encode(state),
            )
        return hash(
            MultiplayerSnapshot(
                1,
                GameStateSerializer.SAVE_FORMAT_VERSION,
                1,
                org.osada.VERSION,
                "scenario:bn9s00.xml",
                "room:OLD123",
                2,
                9,
                1,
                """{"fmt":4,"scenario":{"file":"bn9s00.xml","turn":4},"players":[],"campaign":null,""" +
                    """"randomSeed":"17","randomCursor":"12"}""",
                runtime,
                "",
            ),
        )
    }

    private fun hash(snapshot: MultiplayerSnapshot): MultiplayerSnapshot =
        snapshot.copy(stateHash = CanonicalStateHasher().hash(snapshot))

    @Test
    fun exportRemovesConnectionIdentitiesAndRebindPreservesProgress() {
        val original = snapshot()
        val checkpoint = CoopCheckpoint.decode(CoopCheckpoint.fromSnapshot(original).encode())
        assertFalse(checkpoint.encode().contains("old-host"))
        assertFalse(checkpoint.encode().contains("old-peer"))
        checkpoint.state.commanders.forEach { assertNull(it.participantId) }
        val rebound =
            checkpoint.bind(
                "NEW123",
                "new-host",
                mapOf("c1" to "new-peer", "c2" to "new-host"),
                setOf("new-host", "new-peer"),
            )
        assertTrue(MultiplayerSnapshotValidator().validate(rebound).valid)
        assertEquals(original.gameStateJson, rebound.gameStateJson)
        assertEquals(9L, rebound.revision)
        assertEquals(0L, rebound.authorityEpoch)
        val saved = CoopStateJson.decode(rebound.multiplayerState.coopJson!!)
        assertEquals("c2", saved.commanderOfParticipant("new-host")?.id)
        assertEquals(
            original.multiplayerState.coopJson!!
                .replace("old-host", "new-peer")
                .replace("old-peer", "new-host"),
            CoopStateJson.encode(saved),
        )
        assertFalse(saved.ledger.recordObjective("2,2", "c1"), "deduplication survives recovery")
        assertEquals(6, saved.ledger.counters("c2").strengthLost)
    }

    @Test
    fun everySeatNeedsAnExplicitUniqueAuthenticatedBindingEvenForIdenticalNames() {
        val checkpoint = CoopCheckpoint.fromSnapshot(snapshot())
        val members = setOf("new-host", "new-peer")
        listOf(
            emptyMap(),
            mapOf("c1" to "new-host"),
            mapOf("c1" to "new-host", "c2" to "new-host"),
            mapOf("c1" to "new-host", "c2" to "old-peer"),
            mapOf("c1" to "new-host", "unknown" to "new-peer"),
        ).forEach {
            assertFails { checkpoint.bind("NEW123", "new-host", it, members) }
        }
    }

    @Test
    fun corruptedAndIncompatibleCheckpointsAreRefused() {
        val original = snapshot()
        assertFails { CoopCheckpoint.fromSnapshot(original.copy(gameStateJson = "{}")) }
        assertFails { CoopCheckpoint.fromSnapshot(hash(original.copy(gameVersion = "future"))) }
        assertFails { CoopCheckpoint.fromSnapshot(hash(original.copy(gameSaveFormatVersion = 3))) }
        assertFails { CoopCheckpoint.fromSnapshot(hash(original.copy(protocolVersion = 2))) }
        assertFails {
            CoopCheckpoint.fromSnapshot(
                hash(original.copy(multiplayerState = original.multiplayerState.copy(coopJson = null))),
            )
        }
        val badCatalogue =
            original.multiplayerState.coopJson!!.replace(
                "\"catalogueVersion\":1",
                "\"catalogueVersion\":99",
            )
        assertFails {
            CoopCheckpoint.fromSnapshot(
                hash(original.copy(multiplayerState = original.multiplayerState.copy(coopJson = badCatalogue))),
            )
        }
        assertFails { CoopCheckpoint.decode("{}") }
    }

    @Test
    fun unknownScoringPortraitVersionsAndUnknownAssignmentsAreRefusedRatherThanLost() {
        val original = snapshot()
        val source = original.multiplayerState.coopJson!!
        val invalid =
            listOf(
                source.replace("\"scoringVersion\":1", "\"scoringVersion\":99"),
                source.replace("\"generatorVersion\":1", "\"generatorVersion\":99"),
                source.replace("\"12\":\"c1\"", "\"12\":\"missing\""),
            )
        invalid.forEach { coop ->
            assertFails {
                CoopCheckpoint.fromSnapshot(
                    hash(original.copy(multiplayerState = original.multiplayerState.copy(coopJson = coop))),
                )
            }
        }
    }
}
