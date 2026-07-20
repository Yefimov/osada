package org.osada.ui.briefing

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skip takes the player to the operational briefing, but an unanswered decision travels with them
 * and gates BEGIN — it is never silently forfeited.
 *
 * Skip originally called `showOrders()` directly, dropping every choice the player had not
 * reached; dialogue choices commit real consequences (`CampaignNarrative.commitChoice` — prestige,
 * resupply, campaign routing), so skipping the ceremony must not decide by omission.
 *
 * Runs headlessly: with no `view` attached, `renderCurrentStage` and the focus calls are all
 * null-safe, so only the navigation state (`stage`, `path`) is exercised.
 */
class BriefingSkipTest {
    private fun load(raw: dynamic) {
        val parsed = BriefingParser.parse(scenarioTitle = "Test", rawData = raw)
        ScenarioBriefingController.briefing = parsed
        ScenarioBriefingController.stage = BriefingStage.DIALOGUE
        ScenarioBriefingController.path.clear()
        parsed.dialogue.firstOrNull()?.let {
            ScenarioBriefingController.path += ScenarioBriefingController.DialogueStep(it.id)
        }
    }

    @BeforeTest
    fun setup() {
        ScenarioBriefingController.view = null
    }

    @AfterTest
    fun teardown() {
        ScenarioBriefingController.briefing = null
        ScenarioBriefingController.path.clear()
        ScenarioBriefingController.stage = BriefingStage.ORDERS
    }

    /** Skip lands on the briefing, with the unanswered decision carried there and pending. */
    @Test
    fun skipReachesTheBriefingCarryingThePendingDecision() {
        load(
            js(
                """({
                act: 'ACT I', location: 'Kiel',
                player: { speaker: 'Commander', role: 'Field Command', side: 'left' },
                dialogue: [
                  { id: 'a', speaker: 'S', text: 'Chatter one.' },
                  { id: 'b', speaker: 'S', text: 'Chatter two.' },
                  { id: 'c', speaker: 'S', text: 'Decide.', choices: [
                      { id: 'yes', text: 'Yes.', next: 'd' },
                      { id: 'no',  text: 'No.',  next: 'd' } ] },
                  { id: 'd', speaker: 'S', text: 'After.' }
                ], orders: []})""",
            ),
        )

        ScenarioBriefingController.skipToNextChoiceOrOrders()

        assertEquals(
            BriefingStage.ORDERS,
            ScenarioBriefingController.stage,
            "skip goes to the briefing",
        )
        assertEquals(
            "c",
            ScenarioBriefingController.pendingChoiceLine()?.id,
            "the unanswered decision must follow the player to the briefing",
        )
    }

    /** Answering from the briefing clears the gate when no further decision remains. */
    @Test
    fun answeringFromTheBriefingClearsThePendingDecision() {
        load(
            js(
                """({
                act: 'ACT I', location: 'Kiel',
                player: { speaker: 'Commander', role: 'Field Command', side: 'left' },
                dialogue: [
                  { id: 'c', speaker: 'S', text: 'Decide.', choices: [
                      { id: 'yes', text: 'Yes.', next: 'd' },
                      { id: 'no',  text: 'No.',  next: 'd' } ] },
                  { id: 'd', speaker: 'S', text: 'After.' }
                ], orders: []})""",
            ),
        )
        ScenarioBriefingController.skipToNextChoiceOrOrders()
        assertEquals("c", ScenarioBriefingController.pendingChoiceLine()?.id, "precondition: pending")

        ScenarioBriefingController.chooseFromOrders("yes")

        assertNull(
            ScenarioBriefingController.pendingChoiceLine(),
            "once answered, nothing should gate BEGIN",
        )
        assertEquals(
            BriefingStage.ORDERS,
            ScenarioBriefingController.stage,
            "answering from the briefing keeps the player on the briefing",
        )
    }

    /** Several decisions in one conversation surface one after another, not all at once. */
    @Test
    fun secondPendingDecisionSurfacesAfterTheFirstIsAnswered() {
        load(
            js(
                """({
                act: 'ACT I', location: 'Kiel',
                player: { speaker: 'Commander', role: 'Field Command', side: 'left' },
                dialogue: [
                  { id: 'c1', speaker: 'S', text: 'First.', choices: [
                      { id: 'a', text: 'A.', next: 'c2' } ] },
                  { id: 'c2', speaker: 'S', text: 'Second.', choices: [
                      { id: 'b', text: 'B.', next: 'end' } ] },
                  { id: 'end', speaker: 'S', text: 'Done.' }
                ], orders: []})""",
            ),
        )
        ScenarioBriefingController.skipToNextChoiceOrOrders()
        assertEquals("c1", ScenarioBriefingController.pendingChoiceLine()?.id)

        ScenarioBriefingController.chooseFromOrders("a")

        assertEquals(
            "c2",
            ScenarioBriefingController.pendingChoiceLine()?.id,
            "the next decision must gate BEGIN in turn",
        )
    }

    /** With nothing left to decide, skip behaves as before and goes to the briefing. */
    @Test
    fun skipGoesToOrdersWhenNoChoicesRemain() {
        load(
            js(
                """({
                act: 'ACT I', location: 'Kiel',
                player: { speaker: 'Commander', role: 'Field Command', side: 'left' },
                dialogue: [
                  { id: 'a', speaker: 'S', text: 'Chatter one.' },
                  { id: 'b', speaker: 'S', text: 'Chatter two.' }
                ], orders: []})""",
            ),
        )

        ScenarioBriefingController.skipToNextChoiceOrOrders()

        assertEquals(
            BriefingStage.ORDERS,
            ScenarioBriefingController.stage,
            "a conversation with no pending decision skips straight to the briefing",
        )
    }

    /** Once answered, a choice is no longer a stop — skip runs on to the next one or the end. */
    @Test
    fun skipRunsPastAnAlreadyAnsweredChoice() {
        load(
            js(
                """({
                act: 'ACT I', location: 'Kiel',
                player: { speaker: 'Commander', role: 'Field Command', side: 'left' },
                dialogue: [
                  { id: 'c', speaker: 'S', text: 'Decide.', choices: [
                      { id: 'yes', text: 'Yes.', next: 'd' } ] },
                  { id: 'd', speaker: 'S', text: 'After.' }
                ], orders: []})""",
            ),
        )
        val chosen =
            ScenarioBriefingController.briefing
                ?.lineById("c")
                ?.choices
                ?.first()
        ScenarioBriefingController.path.last().selectedChoice = chosen

        ScenarioBriefingController.skipToNextChoiceOrOrders()

        assertEquals(
            BriefingStage.ORDERS,
            ScenarioBriefingController.stage,
            "an answered choice must not trap skip",
        )
    }

    /** A `next` pointing backwards must not hang the UI — the step budget breaks the cycle. */
    @Test
    fun skipTerminatesOnACyclicDialogue() {
        load(
            js(
                """({
                act: 'ACT I', location: 'Kiel',
                player: { speaker: 'Commander', role: 'Field Command', side: 'left' },
                dialogue: [
                  { id: 'a', speaker: 'S', text: 'Loop.', next: 'b' },
                  { id: 'b', speaker: 'S', text: 'Back.', next: 'a' }
                ], orders: []})""",
            ),
        )

        ScenarioBriefingController.skipToNextChoiceOrOrders()

        assertTrue(
            ScenarioBriefingController.stage == BriefingStage.ORDERS,
            "a cyclic dialogue must still terminate at the orders stage",
        )
    }
}
