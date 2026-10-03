package org.osada.multiplayer.coop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoopBriefingTest {
    private val required = listOf("c1", "c2", "c3", "c4")

    private fun briefing() =
        CoopBriefing(
            listOf(
                CoopDialogueNode("opening", "choice", emptyMap()),
                CoopDialogueNode("choice", "end", linkedMapOf("a" to "branch-a", "b" to "end", "c" to "end")),
                CoopDialogueNode("branch-a", "end", linkedMapOf("next" to "end")),
                CoopDialogueNode("end", null, emptyMap()),
            ),
        )

    @Test fun unanimousDecisionWaitsForAllVotesAndSurvivesJson() {
        val state = briefing()
        assertFalse(state.vote("branch-a", "next", "c1", required))
        assertFalse(state.vote("choice", "invalid", "c1", required))
        assertFalse(state.vote("choice", "a", "spoofed", required))
        required.take(3).forEach { assertTrue(state.vote("choice", "a", it, required)) }
        assertTrue(state.winners(required).isEmpty())
        assertFalse(state.decide("a", required))
        val restored = CoopBriefingJson.decode(CoopBriefingJson.encode(state), required)
        assertTrue(restored.vote("choice", "a", "c4", required))
        assertEquals(setOf("a"), restored.winners(required))
        assertTrue(restored.decide("a", required))
        assertEquals("branch-a", restored.pending()?.id)
        assertFalse(restored.vote("choice", "b", "c1", required))
        assertFalse(restored.markReady("c1", required))
    }

    @Test fun tiedVotesFreezeUntilHostResolvesLeadingOption() {
        val state = briefing()
        required.forEachIndexed { index, who -> state.vote("choice", if (index < 2) "a" else "b", who, required) }
        assertEquals(setOf("a", "b"), state.winners(required))
        assertFalse(state.vote("choice", "b", "c1", required))
        assertFalse(state.decide("c", required))
        assertTrue(state.decide("b", required))
        assertNull(state.pending())
        required.take(3).forEach { assertTrue(state.markReady(it, required)) }
        assertTrue(state.open)
        assertTrue(state.markReady("c4", required))
        assertFalse(state.open)
        assertFalse(state.markReady("c4", required))
    }

    @Test fun excusedSeatIsExcludedWithoutLosingItsRecordedVote() {
        val state = briefing()
        state.vote("choice", "a", "c1", required)
        state.vote("choice", "b", "c2", required)
        state.vote("choice", "b", "c3", required)
        assertEquals(setOf("b"), state.winners(required.take(3)))
        assertTrue(state.decide("b", required.take(3)))
        assertEquals("a", state.votes["choice"]?.get("c1"))
    }
}
