package org.osada.scenario

import kotlinx.browser.document
import org.osada.Game
import org.osada.GameHolder
import org.osada.campaign.CampaignNarrative
import org.osada.continueCampaign
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.Player
import org.osada.scenario.CampaignTransition.CampaignEnd
import org.osada.scenario.CampaignTransition.InvalidData
import org.osada.scenario.CampaignTransition.NeedsDecision
import org.osada.scenario.CampaignTransition.NextScenario
import org.osada.scenario.CampaignTransitionProblem.Kind
import org.osada.ui.ConfirmCard
import org.osada.ui.MessageDialogs
import org.osada.ui.byId
import org.osada.ui.makeHidden
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one campaign transition resolver (docs/README.md C2c). Before it the engine, the narrative's
 * next-scenario peek and the campaign map each read `goto` on their own: a negative target set a
 * negative scenario index in `loadNextScenario` while the others called it the end, and any target
 * past the list silently ended the campaign.
 */
class CampaignTransitionsTest {
    private var previousGame: Game? = null

    /** Three records; record 0's grades go to [gotos] (brilliant/victory/tactical/lose). */
    private fun records(
        gotos: String = "1/1/1/255",
        briefing: String = "null",
    ): Array<dynamic> {
        val g = gotos.split('/')
        return JSON.parse(
            """[{"scenario":"a.xml","briefing":$briefing,"outcome":{
                "briliant":{"goto":${g[0]},"prestige":100},"victory":{"goto":${g[1]},"prestige":100},
                "tactical":{"goto":${g[2]},"prestige":100},"lose":{"goto":${g[3]},"prestige":0}}},
            {"scenario":"b.xml","outcome":{"briliant":{"goto":2},"victory":{"goto":2},
                "tactical":{"goto":2},"lose":{"goto":255}}},
            {"scenario":"c.xml","outcome":{"briliant":{"goto":254},"victory":{"goto":254},
                "tactical":{"goto":254},"lose":{"goto":255}}}]""",
        )
    }

    private fun element(id: String): HTMLElement =
        byId(id) ?: (document.createElement("div") as HTMLElement).also {
            it.id = id
            document.body?.appendChild(it)
        }

    @BeforeTest
    fun setup() {
        previousGame = GameHolder.instance
        installEnglishUiBundleForTests()
        js("if (typeof window.campaignlist === 'undefined') { window.campaignlist = []; }")
        listOf("ui-message", "uiokbut", "title", "message").forEach { element(it) }
        CampaignNarrative.reset()
    }

    @AfterTest
    fun cleanup() {
        ConfirmCard.close()
        MessageDialogs.clearDynamicMessages()
        makeHidden("ui-message")
        CampaignNarrative.reset()
        GameHolder.instance = previousGame
    }

    @Test
    fun aRecordIndexContinuesAndASentinelEndsByTheGradeNotTheSentinel() {
        assertEquals(NextScenario(1), CampaignTransitions.resolve(3, 0, "victory", 1))
        assertEquals(NextScenario(0), CampaignTransitions.resolve(3, 0, "lose", 0), "a replay is a transition too")
        assertEquals(CampaignEnd(defeat = false), CampaignTransitions.resolve(3, 0, "victory", 255))
        // camp6's rcampx18 ends a LOSS with 254: the grade decides, as continueCampaign shows it.
        assertEquals(CampaignEnd(defeat = true), CampaignTransitions.resolve(3, 0, "lose", 254))
    }

    @Test
    fun targetsThatNameNoScenarioAreInvalidNotTheEnd() {
        fun kind(goto: Int?) = assertIs<InvalidData>(CampaignTransitions.resolve(3, 0, "victory", goto)).problem.kind
        assertEquals(Kind.NEGATIVE_TARGET, kind(-1))
        assertEquals(Kind.UNKNOWN_TARGET, kind(3), "one past the list")
        assertEquals(Kind.UNKNOWN_TARGET, kind(253))
        assertEquals(Kind.UNKNOWN_TARGET, kind(256))
        assertEquals(Kind.NO_BRANCH, kind(null))
        assertEquals(
            Kind.NO_CURRENT_SCENARIO,
            assertIs<InvalidData>(CampaignTransitions.resolve(3, 3, "victory", 1)).problem.kind,
        )
        assertEquals(
            Kind.NO_CURRENT_SCENARIO,
            assertIs<InvalidData>(CampaignTransitions.resolve(3, -1, "victory", 1)).problem.kind,
        )
    }

    @Test
    fun aCommittedRouteReplacesTheWinsOnlyAndIsValidatedToo() {
        assertEquals(NextScenario(2), CampaignTransitions.resolve(3, 0, "victory", 1, committedRoute = 2))
        assertEquals(CampaignEnd(defeat = true), CampaignTransitions.resolve(3, 0, "lose", 255, committedRoute = 2))
        val bad = assertIs<InvalidData>(CampaignTransitions.resolve(3, 0, "victory", 1, committedRoute = -4))
        assertEquals(Kind.NEGATIVE_TARGET, bad.problem.kind)
        assertTrue(bad.problem.fromRoute)
    }

    @Test
    fun anOwedDecisionHoldsAWinButNeverALossOrAnAnsweredRoute() {
        assertEquals(
            NeedsDecision(NextScenario(1)),
            CampaignTransitions.resolve(3, 0, "victory", 1, decisionOwed = true),
        )
        assertEquals(CampaignEnd(defeat = true), CampaignTransitions.resolve(3, 0, "lose", 255, decisionOwed = true))
        assertEquals(
            NextScenario(2),
            CampaignTransitions.resolve(3, 0, "victory", 1, committedRoute = 2, decisionOwed = true),
        )
        val brokenFallback =
            assertIs<NeedsDecision>(CampaignTransitions.resolve(3, 0, "victory", -1, decisionOwed = true))
        assertIs<InvalidData>(brokenFallback.withoutDecision, "still asked: the answer may route somewhere valid")
    }

    @Test
    fun validationFindsEveryBrokenBranchAndRouteChoice() {
        assertTrue(CampaignTransitions.validate(records()).isEmpty())
        val briefing =
            """{"dialogue":[{"id":"q","choices":[{"id":"c",
            "effects":[{"id":"r","type":"route","scenarioIndex":9}]}]}]}"""
        val problems = CampaignTransitions.validate(records("1/-1/7/255", briefing))
        assertEquals(
            listOf(
                "victory" to Kind.NEGATIVE_TARGET,
                "tactical" to Kind.UNKNOWN_TARGET,
                "victory" to Kind.UNKNOWN_TARGET,
            ),
            problems.map { it.outcome to it.kind },
        )
        assertEquals(listOf(false, false, true), problems.map { it.fromRoute })
        assertEquals(9, problems.last().target)
    }

    @Test
    fun loadNextScenarioNeverMovesToAnIndexTheCampaignDoesNotHave() {
        val campaign = Campaign(-1, 0) {}.apply { useScenariosForTests(records("-1/7/2/255"), 0) }
        assertNull(campaign.loadNextScenario("briliant"))
        assertNull(campaign.loadNextScenario("victory"))
        assertEquals(0, campaign.currentScenarioIndex, "a refused transition leaves the campaign where it was")
        assertEquals("c.xml", campaign.loadNextScenario("tactical").scenario as String)
        assertEquals(2, campaign.currentScenarioIndex)
    }

    @Test
    fun aSaveIndexTheCampaignDoesNotHaveIsIgnored() {
        val campaign = Campaign(-1, 0) {}.apply { useScenariosForTests(records(), 1) }
        campaign.setScenarioById(-1)
        campaign.setScenarioById(3)
        assertEquals(1, campaign.currentScenarioIndex)
        assertEquals("Unknown", campaign.getScenarioNameFromId(7))
    }

    @Test
    fun theNarrativePeekAgreesWithTheTransition() {
        assertEquals("b.xml", nextScenarioRecord(records(), 0, "victory")?.scenario as? String)
        assertNull(nextScenarioRecord(records("1/-1/1/255"), 0, "victory"), "a refused target continues nowhere")
        assertEquals("c.xml", nextScenarioRecord(records(), 0, "victory", routeOverride = 2)?.scenario as? String)
    }

    /** Nothing is committed on bad data: no result, no prestige, no advance, and the battle stops. */
    @Test
    fun continueCampaignCommitsNothingOnAnInvalidTransition() {
        val game = Game()
        GameHolder.instance = game
        game.campaign = Campaign(-1, 0) {}.apply { useScenariosForTests(records("1/-1/1/255"), 0) }
        game.campaignPlayer = Player()
        game.gameStarted = true

        game.continueCampaign("victory")

        assertEquals(0, game.campaign!!.currentScenarioIndex)
        assertTrue(CampaignNarrative.state.scenarioOutcomes.isEmpty())
        assertEquals(0, game.campaignPlayer!!.prestige)
        assertTrue(game.gameEnded, "the battle cannot be played on into a second attempt")
        assertNull(game.nextScenarioData)
        game.gameStarted = false
    }
}
