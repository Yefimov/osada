package org.osada

import kotlinx.browser.document
import org.osada.hero.HeroId
import org.osada.hero.HeroPromotionAnnouncement
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.ui.ConfirmCard
import org.osada.ui.HeroPromotionPresenter
import org.osada.ui.MessageDialogs
import org.osada.ui.PauseMenu
import org.osada.ui.byId
import org.osada.ui.isVisible
import org.osada.ui.keyboard.CommandRouter
import org.osada.ui.makeHidden
import org.osada.ui.makeVisible
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Battle dialogs against the pause menu, Escape and scenario teardown (review, 2026-10-14):
 * Escape closed the defeat question without answering it, the pause menu paused nothing, the
 * promotion dialog opened over it, and a load or new battle inherited the old battle's open
 * question, its waiting flag and its pending campaign transition.
 */
class BattleDialogLifecycleTest {
    private var previousGame: Game? = null
    private var routerStandIn: EventListener? = null

    private fun element(id: String): HTMLElement =
        byId(id) ?: (document.createElement("div") as HTMLElement).also {
            it.id = id
            document.body?.appendChild(it)
        }

    private fun promotion(name: String) =
        HeroPromotionAnnouncement(
            heroId = HeroId("hero-$name"),
            formationName = "1st $name",
            heroName = name,
            newRankId = "captain",
            choices = listOf(HeroPromotionAnnouncement.Choice("steady", "Steady", "+1", "Earned")),
        )

    private fun escape() {
        val init = KeyboardEventInit(key = "Escape", code = "Escape", bubbles = true, cancelable = true)
        document.dispatchEvent(KeyboardEvent("keydown", init))
    }

    @BeforeTest
    fun setup() {
        previousGame = GameHolder.instance
        installEnglishUiBundleForTests()
        element("mainbody").style.display = ""
        listOf("ui-message", "startmenu", "smSettings").forEach { makeHidden(element(it).id) }
        element("uiokbut")
        element("title")
        element("message")
    }

    @AfterTest
    fun cleanup() {
        routerStandIn?.let { document.removeEventListener("keydown", it) }
        routerStandIn = null
        ConfirmCard.close()
        HeroPromotionPresenter.discard()
        MessageDialogs.clearDynamicMessages()
        listOf("ui-message", "startmenu", "smSettings").forEach { makeHidden(it) }
        GameHolder.instance = previousGame
    }

    @Test
    fun escapeThroughTheGameplayRouterAnswersTheDefeatQuestionOnce() {
        // The router's document listener is installed at startup, before any card opens: it runs
        // first, and used to close the card -- removing the card's own listener mid-dispatch.
        val standIn = EventListener { event: Event -> if (CommandRouter.closeTopmostModal()) event.preventDefault() }
        routerStandIn = standIn
        document.addEventListener("keydown", standIn)
        var accepted = 0
        var restarted = 0
        ConfirmCard.openChoice("Defeat", "Lost.", "Restart mission", "Accept defeat", { accepted++ }) { restarted++ }

        escape()

        assertFalse(ConfirmCard.isOpen())
        assertEquals(1, accepted, "Escape must run the card's answer -- it releases awaitingDefeatChoice")
        assertEquals(0, restarted)
        escape()
        assertEquals(1, accepted, "a closed card answers nothing")
    }

    @Test
    fun closingACardWithoutAnswerRunsNeitherAction() {
        var ran = false
        ConfirmCard.openChoice("Defeat", "Lost.", "Restart", "Accept", { ran = true }) { ran = true }
        ConfirmCard.close()
        ConfirmCard.cancel()
        assertFalse(ran)
    }

    @Test
    fun aTeardownForgetsTheOldBattlesQuestionsWithoutAnsweringThem() {
        val game = Game()
        GameHolder.instance = game
        game.awaitingDefeatChoice = true
        game.defeatAccepted = true
        game.awaitingRouteDecision = true
        game.nextScenarioData = js("({ scenario: 'next.xml' })")
        game.continueCampaignFlag = true
        var answered = false
        ConfirmCard.openChoice("Defeat", "Lost.", "Restart", "Accept", { answered = true }) { answered = true }
        HeroPromotionPresenter.present(listOf(promotion("Petrov")))
        assertTrue(HeroPromotionPresenter.isOpen())
        var callbackRan = false
        MessageDialogs.message("Outcome", "Old battle.") { callbackRan = true }
        assertTrue(isVisible("ui-message"))

        game.cleanup()

        assertFalse(game.awaitingOutcomeAnswer, "a loaded battle must not inherit the wait")
        assertFalse(game.defeatAccepted)
        assertNull(game.nextScenarioData, "a loaded battle must not be carried into the old campaign's next mission")
        assertFalse(game.continueCampaignFlag)
        assertFalse(ConfirmCard.isOpen())
        assertFalse(answered, "neither Restart nor Accept may act on whatever is loaded next")
        assertFalse(HeroPromotionPresenter.isOpen())
        assertFalse(isVisible("ui-message"))
        (byId("uiokbut") as? HTMLElement)?.click()
        assertFalse(callbackRan, "the old message's callback is gone")
    }

    @Test
    fun theMenuHoldsAPromotionBackAndReturnsItAfterwards() {
        PauseMenu.opened()
        HeroPromotionPresenter.present(listOf(promotion("Ivanov")))
        assertFalse(HeroPromotionPresenter.isOpen(), "no promotion opens over the pause menu")
        PauseMenu.closed()
        assertTrue(HeroPromotionPresenter.isOpen(), "the decision is kept and shown on return")

        PauseMenu.opened()
        assertFalse(HeroPromotionPresenter.isOpen(), "an open one is taken off the screen")
        PauseMenu.closed()
        val box = assertNotNull(byId("uiHeroPromotionBox"))
        assertTrue(box.textContent.orEmpty().contains("Ivanov"), "and it is the same decision")
    }

    @Test
    fun aCommittedCampaignResultIsNotCommittedAgainUnderItsOutcomeMessage() {
        val game = Game()
        GameHolder.instance = game
        js("if (typeof window.campaignlist === 'undefined') { window.campaignlist = []; }")
        val records: dynamic =
            JSON.parse(
                """[{"scenario":"a.xml","outcome":{"victory":{"goto":1,"prestige":100}}},
                {"scenario":"b.xml","outcome":{"victory":{"goto":2,"prestige":100}}},
                {"scenario":"c.xml","outcome":{"victory":{"goto":255,"prestige":100}}}]""",
            )
        game.campaign =
            org.osada.scenario
                .Campaign(-1, 0) {}
                .apply { useScenariosForTests(records, 1) }
        game.campaignPlayer = org.osada.model.Player()
        game.gameStarted = true
        org.osada.campaign.CampaignNarrative
            .reset()
        // The first result was committed: the campaign already stands on the next mission.
        game.continueCampaignFlag = true
        game.nextScenarioData = records[1]
        assertTrue(game.battleSettled, "the map and End Turn are closed under the outcome message")

        game.continueCampaign("victory")

        assertEquals(1, game.campaign!!.currentScenarioIndex, "no second advance skips a mission")
        assertTrue(
            org.osada.campaign.CampaignNarrative.state.scenarioOutcomes
                .isEmpty(),
        )
        assertEquals(0, game.campaignPlayer!!.prestige, "no second prestige award")
        game.gameStarted = false
    }

    @Test
    fun theTurnLoopStandsStillUnderThePauseMenu() {
        val game = Game()
        GameHolder.instance = game
        game.gameStarted = true
        game.uiMessageClicked = true
        game.continueCampaignFlag = true
        val pending: dynamic = js("({ scenario: 'next.xml' })")
        game.nextScenarioData = pending

        makeVisible("startmenu")
        assertTrue(PauseMenu.isPausing())
        game.processTurn()
        assertTrue(game.nextScenarioData === pending, "no mission transition starts under the menu")

        makeHidden("startmenu")
        makeVisible("smSettings")
        assertTrue(PauseMenu.isPausing(), "Settings opened from the menu pauses too")
        game.processTurn()
        assertTrue(game.nextScenarioData === pending)
        game.gameStarted = false
    }
}
