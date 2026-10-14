package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.campaign.CampaignNarrativeSerializer
import org.osada.campaign.CampaignNarrativeState
import org.osada.campaign.ScenarioOutcomeRecord
import org.osada.scenario.Campaign
import org.osada.scenario.CampaignRouting
import org.osada.scenario.Scenario
import org.osada.scenario.nextScenarioRecord
import org.osada.ui.RestoredResume
import org.osada.ui.briefing.BriefingIntroTracker
import org.osada.ui.briefing.BriefingParser
import org.osada.ui.briefing.BriefingSource
import org.osada.ui.briefing.OwedBriefingDecision
import org.osada.ui.briefing.ScenarioBriefingController
import org.osada.ui.briefing.ScenarioFacts
import org.osada.ui.briefing.advanceToNextPendingChoice
import org.osada.ui.briefing.pendingChoiceLine
import org.osada.ui.restoredResume
import org.osada.ui.settleRestoredBattle
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Forward, Comrade!'s front choice (reported 2026-10-14: no choice before or after Kharkov, and the
 * campaign went straight to Smolensk). The intro tracker's "seen" marker lives outside the save, so
 * loading an earlier save kept it while the narrative lost the choice; the seen fast path skipped
 * the briefing and the victory took the authored `goto: 23`.
 *
 * The records below mirror `resources/campaigns/data/forward.json` for Kharkov (15) and
 * Prokhorovka (16): their outcome gotos and their one-line briefing with the Centre / South / North
 * route choices (23 / 38 / 18). Karma cannot serve the resource, so update them with the JSON.
 */
class ForwardFrontChoiceTest {
    private val campaignFile = "forward.json"
    private var previousGame: Game? = null

    private fun frontBriefing(prefix: String): dynamic {
        fun choice(
            index: Int,
            text: String,
            target: Int,
        ) = """{"id":"$prefix-choice.$index","text":"$text","effects":[
            {"id":"$prefix-choice.$index.route","type":"route","scenarioIndex":$target},
            {"id":"$prefix-choice.$index.flag","type":"setFlag","flag":"forward_${prefix}_choice_$index"}]}"""
        return JSON.parse(
            """{"dialogue":[{"id":"$prefix-choice","speaker":"General Staff","role":"Path selection",
            "side":"right","text":"Choose where.","choices":[
            ${choice(0, "Army Group Centre", CENTRE)},${choice(1, "Army Group South", SOUTH)},
            ${choice(2, "Army Group North", NORTH)}]}]}""",
        )
    }

    private fun outcomes(
        win: Int,
        lose: Int,
    ): String =
        listOf("briliant", "victory", "tactical").joinToString(",") { """"$it":{"goto":$win,"prestige":0}""" } +
            ""","lose":{"goto":$lose,"prestige":0}"""

    private fun forwardRecords(): Array<dynamic> {
        val records: dynamic = js("[]")
        for (index in 0 until RECORD_COUNT) {
            val record: dynamic =
                when (index) {
                    KHARKOV -> JSON.parse("""{"outcome":{${outcomes(CENTRE, PROKHOROVKA)}}}""")
                    PROKHOROVKA -> JSON.parse("""{"outcome":{${outcomes(CENTRE, CAMPAIGN_DEFEAT)}}}""")
                    else -> JSON.parse("""{"outcome":{${outcomes(CAMPAIGN_DEFEAT, CAMPAIGN_DEFEAT)}}}""")
                }
            record.id = index
            record.scenario = file(index)
            if (index == KHARKOV || index == PROKHOROVKA) record.briefing = frontBriefing("forward$index")
            records.push(record)
        }
        return records.unsafeCast<Array<dynamic>>()
    }

    private fun file(index: Int) = "forward$index.xml"

    /** A battle of the campaign under way at [index], as a restore leaves it. */
    private fun battleAt(index: Int): Game {
        val game = Game()
        GameHolder.instance = game
        game.campaign =
            Campaign(-1, 0) {}.apply {
                file = campaignFile
                useScenariosForTests(forwardRecords(), index)
            }
        game.scenario = Scenario(file(index))
        return game
    }

    /** The narrative of a save written in the battles before Kharkov: no front chosen yet. */
    private fun earlierSave(): dynamic {
        val state = CampaignNarrativeState()
        state.recordOutcome(ScenarioOutcomeRecord(file(KHARKOV - 1), "victory", file(KHARKOV)))
        return JSON.parse(JSON.stringify(CampaignNarrativeSerializer.serialize(state)))
    }

    private fun Game.owedHere() = OwedBriefingDecision.forCurrentBattle(this)

    /** Answers the owed decision as the briefing's decision block does. */
    private fun Game.answer(choiceIndex: Int) {
        val line = assertNotNull(owedHere(), "a decision must be owed before it is answered")
        val choice = line.choices[choiceIndex]
        val offered = line.choices.map { it.id }
        val file = scenario!!.file!!
        assertTrue(CampaignNarrative.commitChoice(file, line.id, choice.id, offered, choice.effects, player = null))
    }

    /** Where `Game.continueCampaign` routes [outcome] from the battle at [index]. */
    private fun routedFrom(
        index: Int,
        outcome: String,
    ): String? {
        val route = CampaignRouting.effectiveRoute(outcome, CampaignNarrative.takeCommittedRoute())
        return nextScenarioRecord(forwardRecords(), index, outcome, route)?.scenario as? String
    }

    @BeforeTest
    fun setup() {
        previousGame = GameHolder.instance
        js("if (typeof window.campaignlist === 'undefined') { window.campaignlist = []; }")
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = [['Test Theater']]; }")
        CampaignNarrative.reset()
        BriefingIntroTracker.reset()
    }

    @AfterTest
    fun cleanup() {
        CampaignNarrative.reset()
        BriefingIntroTracker.reset()
        if (ScenarioBriefingController.isVisible()) ScenarioBriefingController.close(runCallback = false)
        ScenarioBriefingController.clearLast()
        ScenarioBriefingController.briefing = null
        ScenarioBriefingController.currentSource = null
        ScenarioBriefingController.path.clear()
        GameHolder.instance = previousGame
    }

    @Test
    fun anEarlierSaveLoadedAfterTheBriefingWasSeenOffersTheFrontChoiceAgain() {
        val game = battleAt(KHARKOV)
        val save = earlierSave()
        CampaignNarrative.restore(save)
        // First entry: the briefing opens (marked seen) and the player picks South.
        BriefingIntroTracker.markSeen(campaignFile, file(KHARKOV))
        game.answer(1)
        assertTrue(BriefingIntroTracker.maySkip(campaignFile, file(KHARKOV), game.owedHere()))

        // The earlier save comes back; the marker does not go with it.
        CampaignNarrative.restore(save)
        assertTrue(BriefingIntroTracker.isSeen(campaignFile, file(KHARKOV)), "the marker outlives the load")
        val owed = assertNotNull(game.owedHere(), "the restored narrative has no front choice")
        assertEquals("forward15-choice", owed.id)
        assertFalse(
            BriefingIntroTracker.maySkip(campaignFile, file(KHARKOV), owed),
            "a seen briefing must not be skipped past a decision the save does not hold",
        )

        // Answered again in the ceremony, the fast path is back and the new answer routes.
        game.answer(2)
        assertTrue(BriefingIntroTracker.maySkip(campaignFile, file(KHARKOV), game.owedHere()))
        assertEquals(file(NORTH), routedFrom(KHARKOV, "victory"))
    }

    @Test
    fun aRestoredBattleWithoutItsRouteChoiceOwesItBeforeAVictoryIsRouted() {
        listOf(KHARKOV, PROKHOROVKA).forEach { index ->
            CampaignNarrative.restore(earlierSave())
            val game = battleAt(index)
            listOf("briliant", "victory", "tactical").forEach { outcome ->
                assertEquals(
                    "forward$index-choice",
                    game.owedRouteDecision(outcome)?.id,
                    "a $outcome at forward$index must ask for the front first",
                )
            }
            assertNull(game.owedRouteDecision("lose"), "a loss keeps its authored transition and never asks")
            // What the guard prevents: with no route the victory takes the authored goto (Centre).
            assertEquals(file(CENTRE), routedFrom(index, "victory"))

            game.answer(1)
            assertNull(game.owedRouteDecision("victory"), "answered once, the victory routes without asking")
            assertEquals(file(SOUTH), routedFrom(index, "victory"))
        }
    }

    @Test
    fun aVictoryReachedBeforeTheRestoredMapLoadsKeepsItsOwnPrompt() {
        CampaignNarrative.restore(earlierSave())
        val game = battleAt(KHARKOV)
        // Before the map loads: the owed decision holds the turn loop (`UI.setNewScenario`).
        assertEquals(RestoredResume.ASK_OWED, game.restoredResume())

        // A victory arrives first and opens its prompt (`offerOwedRouteDecision`).
        assertNotNull(game.owedRouteDecision("victory"))
        game.awaitingRouteDecision = true
        assertEquals(
            RestoredResume.OUTCOME_PENDING,
            game.restoredResume(),
            "the map-loaded step must not replace the victory prompt and drop its callback",
        )

        // Answering that prompt commits the route; nothing is owed any more.
        game.answer(1)
        game.awaitingRouteDecision = false
        assertEquals(RestoredResume.RESUME, game.restoredResume())
        assertEquals(file(SOUTH), routedFrom(KHARKOV, "victory"))
    }

    @Test
    fun theRestoredMapLoadingDoesNotCancelAVictoryPromptStillLoadingItsTranslations(): Promise<Unit> {
        CampaignNarrative.restore(earlierSave())
        val game = battleAt(KHARKOV)
        game.awaitingRouteDecision = true
        // A briefing domain no earlier test loaded: its translations arrive asynchronously, as in a
        // fresh browser, so the prompt is still pending when the map finishes loading.
        val uncached = "forward15-uncached-${js("Date.now()")}.xml"
        val facts = ScenarioFacts("Kharkov", "", "", "")
        ScenarioBriefingController.show(
            campaignFile,
            uncached,
            facts,
            frontBriefing("forward15"),
            owedBeginKey = "briefing.decision.continue.label",
        ) {}
        assertFalse(ScenarioBriefingController.isVisible(), "the prompt must still be loading for this test")

        game.settleRestoredBattle(
            askOwed = { fail("an outcome prompt is pending; the restore must not ask again") },
            resume = { fail("an outcome prompt is pending; the battle must not resume") },
        )

        return Promise { resolve, reject ->
            val started = js("Date.now()") as Double
            lateinit var poll: () -> Unit
            poll = {
                when {
                    ScenarioBriefingController.isVisible() -> {
                        try {
                            assertEquals("forward15-choice", ScenarioBriefingController.pendingChoiceLine()?.id)
                            resolve(Unit)
                        } catch (error: Throwable) {
                            reject(error)
                        }
                    }
                    (js("Date.now()") as Double) - started > PROMPT_WAIT_MS ->
                        reject(AssertionError("the victory prompt never appeared after the map loaded"))
                    else -> js("setTimeout")(poll, POLL_MS)
                }
            }
            poll()
        }
    }

    @Test
    fun anAlreadyCommittedRouteIsRetainedThroughASaveAndNeverReapplied() {
        val game = battleAt(KHARKOV)
        CampaignNarrative.restore(earlierSave())
        BriefingIntroTracker.markSeen(campaignFile, file(KHARKOV))
        game.answer(1)
        val battleSave = JSON.parse<dynamic>(JSON.stringify(CampaignNarrative.snapshot()))

        CampaignNarrative.restore(battleSave)
        assertNull(game.owedHere(), "the committed front is in the save")
        assertNull(game.owedRouteDecision("victory"))
        assertTrue(BriefingIntroTracker.maySkip(campaignFile, file(KHARKOV), game.owedHere()))
        assertEquals(SOUTH, CampaignNarrative.state.route.peek())

        // Re-answering (a reopened briefing, a double click) is a no-op: no second route or flag.
        val line = BriefingParser.parse("", frontBriefing("forward15")).dialogue.single()
        val north = line.choices[2]
        val offered = line.choices.map { it.id }
        assertFalse(
            CampaignNarrative.commitChoice(file(KHARKOV), line.id, north.id, offered, north.effects, player = null),
        )
        assertEquals(SOUTH, CampaignNarrative.state.route.peek())
        assertEquals(setOf("forward_forward15_choice_1"), CampaignNarrative.state.flags)
        assertEquals(file(SOUTH), routedFrom(KHARKOV, "briliant"))
    }

    @Test
    fun everyFrontRoutesEveryVictoryAndDefeatsKeepTheirAuthoredTransitions() {
        val fronts = listOf(CENTRE, SOUTH, NORTH)
        listOf(KHARKOV, PROKHOROVKA).forEach { index ->
            fronts.forEachIndexed { choiceIndex, target ->
                listOf("briliant", "victory", "tactical").forEach { outcome ->
                    CampaignNarrative.restore(earlierSave())
                    battleAt(index).answer(choiceIndex)
                    assertEquals(file(target), routedFrom(index, outcome), "forward$index $outcome, front $choiceIndex")
                }
                CampaignNarrative.restore(earlierSave())
                battleAt(index).answer(choiceIndex)
                val afterLoss = routedFrom(index, "lose")
                if (index == KHARKOV) {
                    assertEquals(file(PROKHOROVKA), afterLoss, "losing Kharkov leads to Prokhorovka, any front")
                } else {
                    assertNull(afterLoss, "losing Prokhorovka ends the campaign, whatever the front")
                }
                assertNull(CampaignNarrative.state.route.peek(), "the route is consumed by the loss, not kept")
            }
        }
        // Losing Kharkov with no front chosen still goes to Prokhorovka, which then asks.
        CampaignNarrative.restore(earlierSave())
        assertNull(battleAt(KHARKOV).owedRouteDecision("lose"))
        assertEquals(file(PROKHOROVKA), routedFrom(KHARKOV, "lose"))
        assertEquals("forward16-choice", battleAt(PROKHOROVKA).owedRouteDecision("victory")?.id)
    }

    @Test
    fun theOwedSheetOpensOnTheFirstUnansweredDecisionPastCommittedOnes() {
        val parsed =
            BriefingParser.parse(
                "",
                JSON.parse(
                    """{"dialogue":[
                    {"id":"intro","speaker":"Staff","text":"Listen."},
                    {"id":"first","speaker":"Staff","text":"Which?","choices":[
                      {"id":"first.a","text":"A","next":"second"},{"id":"first.b","text":"B","next":"end"}]},
                    {"id":"second","speaker":"Staff","text":"And then?","choices":[
                      {"id":"second.a","text":"A"},{"id":"second.b","text":"B"}]},
                    {"id":"end","speaker":"Staff","text":"Go."}]}""",
                ),
            )
        val state = CampaignNarrative.state
        val firstOffered = listOf("first.a", "first.b")
        assertEquals("first", OwedBriefingDecision.firstOwed(parsed, state, SHEET)?.id)

        state.recordChoice(SHEET, "first", "first.b", firstOffered)
        assertNull(OwedBriefingDecision.firstOwed(parsed, state, SHEET), "B's branch holds no further decision")

        CampaignNarrative.reset()
        CampaignNarrative.state.recordChoice(SHEET, "first", "first.a", firstOffered)
        assertEquals("second", OwedBriefingDecision.firstOwed(parsed, CampaignNarrative.state, SHEET)?.id)

        // The orders sheet walks past the committed decision to the owed one.
        ScenarioBriefingController.briefing = parsed
        ScenarioBriefingController.currentSource = BriefingSource(campaignFile, SHEET, "", null)
        ScenarioBriefingController.path.clear()
        ScenarioBriefingController.path += ScenarioBriefingController.DialogueStep("intro")
        ScenarioBriefingController.advanceToNextPendingChoice()
        assertEquals("second", ScenarioBriefingController.pendingChoiceLine()?.id)
    }

    private companion object {
        const val RECORD_COUNT = 44
        const val KHARKOV = 15
        const val PROKHOROVKA = 16
        const val NORTH = 18
        const val CENTRE = 23
        const val SOUTH = 38
        const val CAMPAIGN_DEFEAT = 255
        const val SHEET = "sheet.xml"
        const val PROMPT_WAIT_MS = 1500.0
        const val POLL_MS = 25
    }
}
