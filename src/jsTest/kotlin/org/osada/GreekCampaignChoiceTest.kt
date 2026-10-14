package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.campaign.CampaignNarrativeSerializer
import org.osada.campaign.ScenarioOutcomeRecord
import org.osada.scenario.Campaign
import org.osada.scenario.CampaignRouting
import org.osada.scenario.Scenario
import org.osada.scenario.nextScenarioRecord
import org.osada.ui.briefing.OwedBriefingDecision
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Briefing answers across successive missions of the shipped Greek campaign (`camp6bn4`), whose
 * every mission calls its choice line `decision` (found in review, 2026-10-14). Answers were keyed
 * by the line alone, so the first mission's answer blocked every later one: no effects, and the
 * Klisura front choice (bn4s03) never committed its route -- a victory went to 7 whatever the player
 * chose. Answers are now scoped by mission and satisfied only by a choice the line offers.
 */
class GreekCampaignChoiceTest {
    private var previousGame: Game? = null

    private fun records(): Array<dynamic> = JSON.parse<Array<dynamic>>(GREEK_CAMPAIGN_EXCERPT_JSON)

    private fun file(index: Int): String = records()[index].scenario as String

    private fun battleAt(index: Int): Game {
        val game = Game()
        GameHolder.instance = game
        game.campaign =
            Campaign(-1, 0) {}.apply {
                file = "camp6bn4.json"
                useScenariosForTests(records(), index)
            }
        game.scenario = Scenario(file(index))
        return game
    }

    /** Answers the battle's owed decision with [choiceId], as the briefing does; returns the commit. */
    private fun Game.answer(choiceId: String): Boolean {
        val line = assertNotNull(OwedBriefingDecision.forCurrentBattle(this), "a decision must be owed")
        val choice = assertNotNull(line.choices.firstOrNull { it.id == choiceId }, "$choiceId on ${line.id}")
        return CampaignNarrative.commitChoice(
            scenario!!.file!!,
            line.id,
            choice.id,
            line.choices.map { it.id },
            choice.effects,
            player = null,
        )
    }

    private fun finish(
        index: Int,
        outcome: String = "victory",
    ): String? {
        val route = CampaignRouting.effectiveRoute(outcome, CampaignNarrative.takeCommittedRoute())
        val next = nextScenarioRecord(records(), index, outcome, route)?.scenario as? String
        CampaignNarrative.state.recordOutcome(ScenarioOutcomeRecord(file(index), outcome, next))
        return next
    }

    @BeforeTest
    fun setup() {
        previousGame = GameHolder.instance
        js("if (typeof window.campaignlist === 'undefined') { window.campaignlist = []; }")
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = [['Test Theater']]; }")
        CampaignNarrative.reset()
    }

    @AfterTest
    fun cleanup() {
        CampaignNarrative.reset()
        GameHolder.instance = previousGame
    }

    @Test
    fun everyMissionsDecisionCommitsAndTheFrontChoiceRoutes() {
        assertTrue(battleAt(PINDUS).answer("hold-the-ridge-line"))
        assertEquals(file(PINDUS + 1), finish(PINDUS))

        val klisura = battleAt(KLISURA)
        assertEquals("decision", OwedBriefingDecision.forCurrentBattle(klisura)?.id, "Pindus's answer is not Klisura's")
        assertTrue(klisura.answer("bn4c01-choice.1"), "the same line id in a later mission must commit")
        assertEquals(KLISURA_ALTERNATIVE, CampaignNarrative.state.route.peek())
        assertTrue(CampaignNarrative.state.hasFlag("camp6bn4_bn4c01_choice_1"))
        assertEquals(file(KLISURA_ALTERNATIVE), finish(KLISURA), "the chosen front, not the authored goto 7")

        assertTrue(battleAt(CRETE_EVACUATION).answer("bring-out-the-hospital"))
        finish(CRETE_EVACUATION)
        val alamein = battleAt(EL_ALAMEIN)
        assertTrue(alamein.answer("bn4c02-choice.1"))
        assertEquals(file(EL_ALAMEIN_ALTERNATIVE), finish(EL_ALAMEIN))

        assertEquals(
            listOf("hold-the-ridge-line", "bn4c01-choice.1", "bring-out-the-hospital", "bn4c02-choice.1"),
            CampaignNarrative.state.choiceRecords.map { it.choiceId },
        )
    }

    @Test
    fun aPreScopingSaveKeepsItsAnswerWhereItWasGivenAndOwesTheLaterOnes() {
        // The old format: one map keyed by line id, holding Pindus's answer only.
        CampaignNarrative.restore(JSON.parse("""{"version":1,"choices":{"decision":"hold-the-ridge-line"}}"""))
        assertNull(OwedBriefingDecision.forCurrentBattle(battleAt(PINDUS)), "the legacy answer is Pindus's")

        val klisura = battleAt(KLISURA)
        assertEquals("decision", OwedBriefingDecision.forCurrentBattle(klisura)?.id)
        assertEquals("decision", klisura.owedRouteDecision("victory")?.id, "its victory asks the front first")
        assertTrue(klisura.answer("bn4c01-choice.0"))

        val restored =
            CampaignNarrativeSerializer.deserialize(
                JSON.parse(JSON.stringify(CampaignNarrativeSerializer.serialize(CampaignNarrative.state))),
            )
        assertEquals(CampaignNarrative.state.choiceRecords, restored.choiceRecords)
        assertEquals(listOf(null, file(KLISURA)), restored.choiceRecords.map { it.scenarioFile })
    }

    @Test
    fun anAnswerTheLineNoLongerOffersIsOwedAndReplaceable() {
        val save =
            """{"version":2,"choiceRecords":[{"scenario":"${file(
                KLISURA,
            )}","line":"decision","choice":"retired-option"}]}"""
        CampaignNarrative.restore(JSON.parse(save))
        val klisura = battleAt(KLISURA)
        assertEquals("decision", OwedBriefingDecision.forCurrentBattle(klisura)?.id)
        assertTrue(klisura.answer("bn4c01-choice.0"), "an obsolete answer must not block the real one")
        assertNull(OwedBriefingDecision.forCurrentBattle(klisura))
        assertEquals(listOf("bn4c01-choice.0"), CampaignNarrative.state.choiceRecords.map { it.choiceId })
        assertFalse(CampaignNarrative.state.chose("retired-option"))
    }

    @Test
    fun theVictorySafeguardLooksPastAnEarlierDecisionWithoutARoute() {
        // Crete finished with every optional action, so El Alamein offers its earned decision first.
        val crete = ScenarioOutcomeRecord(file(CRETE_EVACUATION), "victory", file(EL_ALAMEIN))
        CampaignNarrative.state.recordOutcome(crete)
        listOf("finished_by_6", "sougia_held", "core_losses_light").forEach {
            CampaignNarrative.state.actions.record(file(CRETE_EVACUATION), it)
        }
        val alamein = battleAt(EL_ALAMEIN)
        val first = assertNotNull(alamein.owedRouteDecision("victory"), "a route decision lies past the earned one")
        assertEquals("earned-decision", first.id)
        assertFalse(OwedBriefingDecision.decidesRoute(first))
        assertNull(alamein.owedRouteDecision("lose"))

        assertTrue(alamein.answer("share-the-evacuation-pool"))
        assertEquals("decision", alamein.owedRouteDecision("victory")?.id)
        assertTrue(alamein.answer("bn4c02-choice.0"))
        assertNull(alamein.owedRouteDecision("victory"))
        assertEquals(file(EL_ALAMEIN_FRONT), finish(EL_ALAMEIN))
    }

    private companion object {
        const val PINDUS = 0
        const val KLISURA = 3
        const val KLISURA_ALTERNATIVE = 5
        const val CRETE_EVACUATION = 11
        const val EL_ALAMEIN = 12
        const val EL_ALAMEIN_FRONT = 14
        const val EL_ALAMEIN_ALTERNATIVE = 21
    }
}
