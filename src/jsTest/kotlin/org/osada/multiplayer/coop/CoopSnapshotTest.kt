package org.osada.multiplayer.coop

import org.osada.multiplayer.model.MatchStatus
import org.osada.multiplayer.model.MultiplayerRuntimeState
import org.osada.multiplayer.sync.CanonicalStateHasher
import org.osada.multiplayer.sync.MultiplayerSnapshot
import org.osada.multiplayer.sync.MultiplayerSnapshotJson
import org.osada.multiplayer.sync.MultiplayerSnapshotValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cooperative state rides every snapshot, is part of its hash and is restored exactly. */
class CoopSnapshotTest {
    private fun coop(): CoopState {
        val portrait = CoopPortrait.generated(3)
        val state =
            CoopState(
                listOf(
                    CoopCommander("c1", 0, "pa", "Ada", portrait, listOf("coop.marksman")),
                    CoopCommander("c2", 1, "pb", "Bo", portrait, emptyList()),
                ),
                humanPlayerId = 0,
            )
        state.claim(12, "c1")
        state.ledger.recordObjective("2,2", "c1")
        return state
    }

    private fun snapshot(coopJson: String?): MultiplayerSnapshot {
        val runtime =
            MultiplayerRuntimeState(
                status = MatchStatus.RUNNING,
                revision = 5,
                authorityParticipantId = "pa",
                authorityEpoch = 0,
                readyParticipantIds = emptySet(),
                sharedPrestigeAccounts = emptyMap(),
                unitAssignments = emptyMap(),
                pendingCommand = null,
                coopJson = coopJson,
            )
        val unhashed =
            MultiplayerSnapshot(
                snapshotFormatVersion = 1,
                gameSaveFormatVersion = 1,
                protocolVersion = 1,
                gameVersion = "test",
                contentManifestHash = "scenario:x",
                roomConfigHash = "room:y",
                authorityEpoch = 0,
                revision = 5,
                createdAt = 1,
                gameStateJson = "{\"a\":1}",
                multiplayerState = runtime,
                stateHash = "",
            )
        return unhashed.copy(stateHash = CanonicalStateHasher().hash(unhashed))
    }

    @Test
    fun theCooperativeStateSurvivesTheSnapshotWire() {
        val text = CoopStateJson.encode(coop())
        val decoded = MultiplayerSnapshotJson.decode(MultiplayerSnapshotJson.encode(snapshot(text)))
        assertEquals(
            CoopStateJson.encode(CoopStateJson.decode(text)),
            CoopStateJson.encode(CoopStateJson.decode(decoded.multiplayerState.coopJson!!)),
        )
        assertTrue(
            MultiplayerSnapshotValidator().validate(decoded).valid,
            "the hash still matches after the round trip",
        )
    }

    @Test
    fun aRoomWithoutCooperativeStateCarriesNone() {
        val decoded = MultiplayerSnapshotJson.decode(MultiplayerSnapshotJson.encode(snapshot(null)))
        assertNull(decoded.multiplayerState.coopJson)
        assertTrue(MultiplayerSnapshotValidator().validate(decoded).valid)
    }

    @Test
    fun theHashCoversAssignmentsScoresAndReadiness() {
        val base = coop()
        val hash = CanonicalStateHasher().hash(snapshot(CoopStateJson.encode(base)))
        base.claim(13, "c2")
        assertFalse(
            hash == CanonicalStateHasher().hash(snapshot(CoopStateJson.encode(base))),
            "a new claim changes the hash",
        )
        val scored = coop().also { it.ledger.recordReveal(99, "c1") }
        assertFalse(
            hash == CanonicalStateHasher().hash(snapshot(CoopStateJson.encode(scored))),
            "a new score changes the hash",
        )
        val ready = coop().also { it.setDone("c1", true) }
        assertFalse(
            hash == CanonicalStateHasher().hash(snapshot(CoopStateJson.encode(ready))),
            "readiness changes the hash",
        )
    }

    @Test
    fun aPeerWhoseCopyWasTamperedWithFailsValidation() {
        val genuine = snapshot(CoopStateJson.encode(coop()))
        val forgedState = coop().also { it.claim(14, "c2") }
        val tampered =
            genuine.copy(
                multiplayerState = genuine.multiplayerState.copy(coopJson = CoopStateJson.encode(forgedState)),
            )
        assertFalse(MultiplayerSnapshotValidator().validate(tampered).valid)
    }
}
