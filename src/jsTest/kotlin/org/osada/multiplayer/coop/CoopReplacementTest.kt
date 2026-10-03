package org.osada.multiplayer.coop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoopReplacementTest {
    private fun state(): CoopState =
        CoopState(
            listOf(
                CoopCommander("c1", 0, "host", "Ada", CoopPortrait.generated(1), emptyList()),
                CoopCommander("c2", 1, "old-guest", "Bo", CoopPortrait.generated(2), emptyList()),
            ),
            0,
        ).also { it.campaignTotal = 6 }

    private fun newcomer(state: CoopState) =
        state.commanders[1].copy(
            id = "c2-m2-n1",
            participantId = "new-guest",
            name = "Cy",
            abilityIds = listOf("coop.marksman", "coop.fire_discipline", "coop.street_fighter"),
        )

    @Test fun successorKeepsCommonAllowanceButNeverInheritsOldIdentityOrCredit() {
        val state = state()
        state.beginNextMission("one.xml")
        state.ledger.perCommander["c2"] =
            CoopCounters().also {
                it.points = 12
                it.destroyed = 1
            }
        state.beginNextMission("two.xml")
        state.beginNextMission("three.xml")
        assertEquals(3, state.slotCap())
        state.assignments[7] = "c2"
        state.formationSuggestions["formation"] = "c2"
        state.done += "c2"
        state.excused += "c2"
        assertTrue(state.replaceAtIntermission("c2", newcomer(state)))
        assertNull(state.commanderOfParticipant("old-guest"))
        assertEquals("Cy", state.commanderOfParticipant("new-guest")?.name)
        assertNull(state.ownerOf(7))
        assertTrue(state.formationSuggestions.isEmpty())
        assertTrue(state.done.isEmpty() && state.excused.isEmpty())
        assertEquals(
            12,
            state.summaries
                .first()
                .perCommander["c2"]
                ?.points,
        )
        assertNull(state.ledger.perCommander["c2-m2-n1"])
        val restored = CoopStateJson.decode(CoopStateJson.encode(state))
        assertEquals("Bo", restored.historicalCommanderById("c2")?.name)
        assertNull(restored.retiredCommanders.single().participantId)
        assertEquals(3, restored.slotCap())
        assertEquals(CoopStateJson.encode(state), CoopStateJson.encode(restored))
    }

    @Test fun replacementIsRejectedDuringBattleOrStandaloneAndCannotRecycleAnIdentity() {
        val state = state()
        state.beginNextMission("one.xml")
        assertFalse(state.replaceAtIntermission("c2", newcomer(state)))
        state.beginNextMission("two.xml")
        state.ordersThisMission = 1
        assertFalse(state.replaceAtIntermission("c2", newcomer(state)))
        state.ordersThisMission = 0
        state.unlocksEarned = 1
        assertFalse(state.replaceAtIntermission("c2", newcomer(state).copy(id = "c1")))
        state.campaignTotal = 0
        assertFalse(state.replaceAtIntermission("c2", newcomer(state)))
        assertEquals("Bo", state.commanderById("c2")?.name)
        assertTrue(state.retiredCommanders.isEmpty())
    }

    @Test fun unfinishedVotesNeedTheNewCommanderAndCommittedChoicesRemainCommitted() {
        val state = state()
        state.beginNextMission("one.xml")
        state.beginNextMission("two.xml")
        state.unlocksEarned = 1
        val briefing = CoopBriefing(listOf(CoopDialogueNode("choice", null, mapOf("a" to null, "b" to null))))
        state.briefing = briefing
        assertTrue(briefing.vote("choice", "a", "c2", state.requiredCommanderIds()))
        assertTrue(state.replaceAtIntermission("c2", newcomer(state)))
        assertFalse("c2" in briefing.votes["choice"].orEmpty())
        assertTrue(briefing.vote("choice", "b", "c2-m2-n1", state.requiredCommanderIds()))
        assertTrue(briefing.vote("choice", "b", "c1", state.requiredCommanderIds()))
        assertTrue(briefing.decide("b", state.requiredCommanderIds()))
        assertEquals(
            "b",
            CoopStateJson
                .decode(CoopStateJson.encode(state))
                .briefing
                ?.decisions
                ?.get("choice"),
        )
    }
}
