package org.osada.ui

import org.osada.campaign.HexRef
import org.osada.campaign.ScenarioActionEvaluator
import org.osada.campaign.ScenarioActionRule
import org.osada.campaign.ScenarioEndState
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The second objective phase (`docs/design/action-affordances-and-objectives.md` §9).
 *
 * Two contracts: a stable campaign fact id such as `airfield_held_at_end` must never reach the
 * screen, and previewing must be a pure read — opening the rail cannot record a campaign fact.
 */
class ScenarioObjectiveTextTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
    }

    private fun endState(
        turn: Int = 3,
        coreLosses: Int = 0,
        ownedByPlayer: List<HexRef> = emptyList(),
    ): ScenarioEndState {
        val map =
            GameMap().apply {
                rows = 6
                cols = 6
                allocMap()
            }
        map.addPlayer(
            Player().apply {
                id = 0
                side = 0
            },
        )
        map.addPlayer(
            Player().apply {
                id = 1
                side = 1
            },
        )
        ownedByPlayer.forEach { ref -> map.map!![ref.row][ref.col].owner = 0 }
        return ScenarioEndState(map = map, playerSide = 0, turn = turn, coreLosses = coreLosses)
    }

    // ---- wording -----------------------------------------------------------------------------

    @Test
    fun anAuthoredLabelWinsOverTheGeneratedWording() {
        val rule =
            ScenarioActionRule.CoreLossesAtMost(
                "core_intact",
                maxLosses = 0,
                label = "Bring the brigade home",
            )

        assertEquals("Bring the brigade home", rule.label)
        assertEquals("Lose no core formation at all", ScenarioObjectiveText.describe(rule))
    }

    @Test
    fun aRuleWithNoLabelDescribesItselfAndNeverShowsItsFactId() {
        val rules =
            listOf(
                ScenarioActionRule.HexesHeld("airfield_held_at_end", listOf(HexRef(1, 1)), atLeast = null),
                ScenarioActionRule.HexesNotHeld("bridge_denied", listOf(HexRef(2, 2))),
                ScenarioActionRule.UnitsSurvived("escort_alive", listOf(4, 5), atLeast = 2),
                ScenarioActionRule.FinishedByTurn("quick_work", turn = 8),
                ScenarioActionRule.CoreLossesAtMost("core_losses_light", maxLosses = 2),
                ScenarioActionRule.EventFired("rescue", listOf("prisoners_freed")),
            )

        rules.forEach { rule ->
            val text = ScenarioObjectiveText.describe(rule)
            assertTrue(text.isNotBlank(), "${rule.id} produced no wording")
            assertFalse(text.contains(rule.id), "${rule.id} leaked its fact id into UI copy: $text")
            assertFalse(text.contains("_"), "${rule.id} looks like an id, not a sentence: $text")
        }
    }

    @Test
    fun thePartialAndCompleteHexThresholdsReadDifferently() {
        val hexes = listOf(HexRef(1, 1), HexRef(1, 2), HexRef(1, 3))

        assertEquals(
            "Hold all 3 marked hexes at mission end",
            ScenarioObjectiveText.describe(ScenarioActionRule.HexesHeld("all", hexes, atLeast = null)),
        )
        assertEquals(
            "Hold 2 of 3 marked hexes at mission end",
            ScenarioObjectiveText.describe(ScenarioActionRule.HexesHeld("some", hexes, atLeast = 2)),
        )
    }

    @Test
    fun theFinishedByTurnWordingQuotesTheAuthoredTurn() {
        assertEquals(
            "Finish the mission by turn 8",
            ScenarioObjectiveText.describe(ScenarioActionRule.FinishedByTurn("quick_work", turn = 8)),
        )
    }

    // ---- preview -----------------------------------------------------------------------------

    @Test
    fun thePreviewUsesTheSamePredicatesAsTheEndOfMissionEvaluation() {
        val rule = ScenarioActionRule.HexesHeld("airfield_held_at_end", listOf(HexRef(1, 1)), atLeast = null)
        val notYet = endState()
        val nowHeld = endState(ownedByPlayer = listOf(HexRef(1, 1)))

        assertTrue(ScenarioActionEvaluator.evaluate(listOf(rule), notYet).isEmpty())
        assertEquals(setOf("airfield_held_at_end"), ScenarioActionEvaluator.evaluate(listOf(rule), nowHeld))
    }

    @Test
    fun previewingIsReversibleAndRepeatable() {
        // The rail redraws on every status refresh. A preview that recorded anything would make an
        // objective "complete" the moment the player briefly stood on the hex.
        val rule = ScenarioActionRule.HexesHeld("airfield_held_at_end", listOf(HexRef(1, 1)), atLeast = null)
        val held = endState(ownedByPlayer = listOf(HexRef(1, 1)))

        repeat(3) { assertEquals(setOf("airfield_held_at_end"), ScenarioActionEvaluator.evaluate(listOf(rule), held)) }

        held.map.map!![1][1].owner = 1
        assertTrue(
            ScenarioActionEvaluator.evaluate(listOf(rule), held).isEmpty(),
            "losing the hex again must take the objective back out of the satisfied set",
        )
    }

    @Test
    fun aTurnDeadlineIsDistinguishableFromAConditionThatCanStillChange() {
        val deadline = ScenarioActionRule.FinishedByTurn("quick_work", turn = 8)

        assertEquals(setOf("quick_work"), ScenarioActionEvaluator.evaluate(listOf(deadline), endState(turn = 8)))
        assertTrue(
            ScenarioActionEvaluator.evaluate(listOf(deadline), endState(turn = 9)).isEmpty(),
            "past turn 8 the deadline is gone, not merely unsatisfied",
        )
    }
}
