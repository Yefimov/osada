package org.osada.campaign

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A scenario the campaign enters more than once keeps every visit's result (docs/README.md C2c).
 * Results used to be keyed by file alone, so the replay of a lost mission (`rcampdfr`'s `rcampper`)
 * or a loop back to an earlier operation (`forward` 43 -> 22) recorded nothing: no outcome, no
 * optional objectives, no queued outcome effects, no trace on the campaign map.
 */
class ScenarioVisitsTest {
    @BeforeTest
    fun setup() = CampaignNarrative.reset()

    @AfterTest
    fun cleanup() = CampaignNarrative.reset()

    private fun complete(
        outcome: String,
        next: String? = "b.xml",
    ) = CampaignNarrative.recordScenarioCompletion("a.xml", outcome, next, emptyList(), null)

    private fun roundTrip() =
        CampaignNarrative.restore(
            JSON.parse(JSON.stringify(CampaignNarrativeSerializer.serialize(CampaignNarrative.state))),
        )

    @Test
    fun aReplayRecordsItsOwnResultAndADuplicateCompletionDoesNot() {
        assertEquals(ScenarioVisit("a.xml", 1), CampaignNarrative.beginVisit("a.xml"))
        assertTrue(complete("lose", next = "a.xml"))
        assertFalse(complete("victory"), "the same battle completing twice is still one result")

        assertEquals(ScenarioVisit("a.xml", 2), CampaignNarrative.beginVisit("a.xml"))
        assertTrue(complete("victory"), "the replay is a new visit")

        val state = CampaignNarrative.state
        assertEquals(listOf("lose" to 1, "victory" to 2), state.scenarioOutcomes.map { it.outcome to it.visit })
        assertEquals("victory", state.outcomeOf("a.xml"), "a mission won after a loss reads as won")
        assertEquals(1, state.countOutcomes("victory"))
        assertEquals(1, state.countOutcomes("lose"))
    }

    @Test
    fun aRestoredBattleStaysTheVisitItsSavePinned() {
        CampaignNarrative.beginVisit("a.xml")
        assertTrue(complete("lose", next = "a.xml"))
        CampaignNarrative.beginVisit("a.xml")
        // A save inside the replay (or its Restart Mission checkpoint), then a reload.
        roundTrip()
        assertEquals(ScenarioVisit("a.xml", 2), CampaignNarrative.state.visits.current)
        assertTrue(complete("victory"))
        roundTrip()
        assertFalse(complete("victory"), "finishing the restored battle again is a duplicate")
        assertEquals(listOf(1, 2), CampaignNarrative.state.scenarioOutcomes.map { it.visit })
    }

    @Test
    fun aSaveFromBeforeVisitsKeepsFirstResultWinsUntilTheNextBattleBegins() {
        // Written before 2026-10-14: no `visit` on the records, no pinned visit.
        CampaignNarrative.restore(
            JSON.parse("""{"version":2,"outcomes":[{"scenario":"a.xml","outcome":"lose","next":"a.xml"}]}"""),
        )
        assertEquals(
            1,
            CampaignNarrative.state.scenarioOutcomes
                .single()
                .visit,
        )
        assertFalse(complete("victory"), "unpinned, the battle in play joins the recorded visit")

        assertEquals(ScenarioVisit("a.xml", 2), CampaignNarrative.beginVisit("a.xml"), "a fresh battle counts past it")
        assertTrue(complete("victory"))
    }

    @Test
    fun malformedVisitDataDegradesToAnUnpinnedFirstVisit() {
        CampaignNarrative.restore(
            JSON.parse(
                """{"outcomes":[{"scenario":"a.xml","outcome":"lose","visit":-3}],"visit":{"scenario":"","n":"x"}}""",
            ),
        )
        assertEquals(
            1,
            CampaignNarrative.state.scenarioOutcomes
                .single()
                .visit,
        )
        assertEquals(null, CampaignNarrative.state.visits.current)
    }

    @Test
    fun aPinnedVisitAloneIsWorthASaveBlock() {
        CampaignNarrative.beginVisit("a.xml")
        assertFalse(CampaignNarrative.state.isEmpty, "the first battle's visit must survive a reload inside it")
    }
}
