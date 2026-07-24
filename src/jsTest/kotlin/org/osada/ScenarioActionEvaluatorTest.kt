package org.osada

import org.osada.campaign.HexRef
import org.osada.campaign.ScenarioActionEvaluator
import org.osada.campaign.ScenarioActionParser
import org.osada.campaign.ScenarioActionRule
import org.osada.campaign.ScenarioEndState
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.getUnitById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Optional-objective resolution from REAL end-of-scenario map state.
 *
 * The point of these tests is the distinctions the campaign narrative depends on: held-at-end vs
 * lost, complete vs partial vs total escort failure. A rule may only credit an objective the map
 * actually supports — a false positive would let dialogue claim a success the player never had.
 */
class ScenarioActionEvaluatorTest {
    private companion object {
        const val PLAYER_SIDE = 0
        const val ENEMY_SIDE = 1
        const val PLAYER_ID = 0
        const val ENEMY_ID = 1
        const val MAP_SIZE = 6
        const val AIRFIELD_ROW = 2
        const val AIRFIELD_COL = 3
    }

    /** A 6x6 map with a player and an enemy registered, every hex initially unowned. */
    private fun buildMap(): GameMap {
        val map =
            GameMap().apply {
                rows = MAP_SIZE
                cols = MAP_SIZE
            }
        map.allocMap()
        map.addPlayer(
            Player().apply {
                id = PLAYER_ID
                side = PLAYER_SIDE
            },
        )
        map.addPlayer(
            Player().apply {
                id = ENEMY_ID
                side = ENEMY_SIDE
            },
        )
        return map
    }

    private fun own(
        map: GameMap,
        row: Int,
        col: Int,
        ownerId: Int,
    ) {
        map.map!![row][col].owner = ownerId
    }

    private fun endState(
        map: GameMap,
        turn: Int = 5,
        coreLosses: Int = 0,
    ) = ScenarioEndState(map = map, playerSide = PLAYER_SIDE, turn = turn, coreLosses = coreLosses)

    private fun airfield() =
        ScenarioActionRule.HexesHeld(
            id = "airfield_held_at_end",
            hexes = listOf(HexRef(AIRFIELD_ROW, AIRFIELD_COL)),
            atLeast = null,
        )

    // ------------------------------------------------------- held / lost / never taken

    @Test
    fun airfieldHeldAtEndIsCredited() {
        val map = buildMap()
        own(map, AIRFIELD_ROW, AIRFIELD_COL, PLAYER_ID)
        assertEquals(setOf("airfield_held_at_end"), ScenarioActionEvaluator.evaluate(listOf(airfield()), endState(map)))
    }

    @Test
    fun airfieldCapturedButLostIsNotCreditedAsHeld() {
        val map = buildMap()
        // Taken during the battle, then retaken by the enemy before the end.
        own(map, AIRFIELD_ROW, AIRFIELD_COL, PLAYER_ID)
        own(map, AIRFIELD_ROW, AIRFIELD_COL, ENEMY_ID)
        assertTrue(
            ScenarioActionEvaluator.evaluate(listOf(airfield()), endState(map)).isEmpty(),
            "end-state ownership is the truth: holding it earlier does not count as holding it at the end",
        )
    }

    @Test
    fun neverCapturedAndLostAfterCaptureAreBothDistinguishedFromHeld() {
        val map = buildMap()
        own(map, AIRFIELD_ROW, AIRFIELD_COL, ENEMY_ID)
        val rules =
            listOf(
                airfield(),
                ScenarioActionRule.HexesNotHeld("airfield_in_enemy_hands", listOf(HexRef(AIRFIELD_ROW, AIRFIELD_COL))),
            )
        assertEquals(setOf("airfield_in_enemy_hands"), ScenarioActionEvaluator.evaluate(rules, endState(map)))
    }

    @Test
    fun unownedObjectiveCountsAsNotHeld() {
        val map = buildMap()
        assertTrue(ScenarioActionEvaluator.evaluate(listOf(airfield()), endState(map)).isEmpty())
    }

    // ------------------------------------------------------------- partial success

    @Test
    fun partialAndCompleteObjectiveSetsAreDistinguished() {
        val map = buildMap()
        val hexes = listOf(HexRef(1, 1), HexRef(1, 2), HexRef(1, 3))
        val all = ScenarioActionRule.HexesHeld("industry_fully_saved", hexes, atLeast = null)
        val some = ScenarioActionRule.HexesHeld("industry_partly_saved", hexes, atLeast = 2)

        own(map, 1, 1, PLAYER_ID)
        own(map, 1, 2, PLAYER_ID)
        val result = ScenarioActionEvaluator.evaluate(listOf(all, some), endState(map))
        assertEquals(setOf("industry_partly_saved"), result, "2 of 3 is partial, not complete")

        own(map, 1, 3, PLAYER_ID)
        assertEquals(
            setOf("industry_fully_saved", "industry_partly_saved"),
            ScenarioActionEvaluator.evaluate(listOf(all, some), endState(map)),
        )
    }

    @Test
    fun escortSurvivalDistinguishesAllSomeAndNone() {
        val map = buildMap()
        // addUnit assigns the id itself, so the convoy's real ids are read back after registration
        // — exactly how a campaign author would source them from the scenario file.
        val refugees =
            List(3) {
                val unit = GameUnit(0)
                map.addUnit(unit)
                unit.id
            }
        val allRescued = ScenarioActionRule.UnitsSurvived("refugees_all_rescued", refugees, atLeast = 3)
        val someRescued = ScenarioActionRule.UnitsSurvived("refugees_partially_rescued", refugees, atLeast = 1)
        val rules = listOf(allRescued, someRescued)

        assertEquals(
            setOf("refugees_all_rescued", "refugees_partially_rescued"),
            ScenarioActionEvaluator.evaluate(rules, endState(map)),
        )

        map.getUnitById(refugees[1])!!.destroyed = true
        assertEquals(
            setOf("refugees_partially_rescued"),
            ScenarioActionEvaluator.evaluate(rules, endState(map)),
            "losing one convoy unit must downgrade complete rescue to partial",
        )

        refugees.forEach { map.getUnitById(it)!!.destroyed = true }
        assertTrue(
            ScenarioActionEvaluator.evaluate(rules, endState(map)).isEmpty(),
            "a destroyed convoy credits nothing",
        )
    }

    // ------------------------------------------------------------- turn and losses

    @Test
    fun turnAndCoreLossThresholdsResolveFromRealEndState() {
        val map = buildMap()
        val fast = ScenarioActionRule.FinishedByTurn("objective_taken_before_turn_8", 8)
        val cheap = ScenarioActionRule.CoreLossesAtMost("core_losses_below_threshold", 2)

        assertEquals(
            setOf("objective_taken_before_turn_8", "core_losses_below_threshold"),
            ScenarioActionEvaluator.evaluate(listOf(fast, cheap), endState(map, turn = 6, coreLosses = 1)),
        )
        assertTrue(
            ScenarioActionEvaluator.evaluate(listOf(fast, cheap), endState(map, turn = 12, coreLosses = 5)).isEmpty(),
        )
    }

    // ------------------------------------------------------------------ fail-safe

    @Test
    fun rulesReferencingMissingHexesAreNotCredited() {
        val map = buildMap()
        val offMap = ScenarioActionRule.HexesHeld("bad", listOf(HexRef(999, 999)), atLeast = null)
        assertTrue(
            ScenarioActionEvaluator.evaluate(listOf(offMap), endState(map)).isEmpty(),
            "a missing hex must resolve to NOT achieved, never to achieved",
        )
    }

    @Test
    fun emptyHexListNeverCreditsAnObjective() {
        val map = buildMap()
        val empty = ScenarioActionRule.HexesHeld("empty", emptyList(), atLeast = null)
        assertTrue(ScenarioActionEvaluator.evaluate(listOf(empty), endState(map)).isEmpty())
    }

    // -------------------------------------------------------------------- parsing

    @Test
    fun actionRulesParseFromCampaignJson() {
        val rules =
            ScenarioActionParser.parseList(
                JSON.parse(
                    """[
                      {"id":"airfield_held_at_end","type":"hexesHeld","hexes":[{"row":2,"col":3}]},
                      {"id":"refugees_all_rescued","type":"unitsSurvived","unitIds":[101,102],"atLeast":2},
                      {"id":"quick","type":"finishedByTurn","turn":8}
                    ]""",
                ),
            )
        assertEquals(3, rules.size)
        assertEquals("airfield_held_at_end", rules[0].id)
        assertEquals(2, (rules[1] as ScenarioActionRule.UnitsSurvived).atLeast)
        assertEquals(8, (rules[2] as ScenarioActionRule.FinishedByTurn).turn)
    }

    @Test
    fun malformedActionRulesDropIndividually() {
        val rules =
            ScenarioActionParser.parseList(
                JSON.parse(
                    """[
                      {"id":"good","type":"finishedByTurn","turn":8},
                      {"type":"finishedByTurn","turn":8},
                      {"id":"unknown","type":"summonKraken"},
                      {"id":"noTurn","type":"finishedByTurn"}
                    ]""",
                ),
            )
        assertEquals(1, rules.size)
        assertEquals("good", rules.single().id)
    }

    @Test
    fun absentActionsArrayYieldsNoRules() {
        assertTrue(ScenarioActionParser.parseList(null).isEmpty())
        assertFalse(ScenarioActionParser.parseList(JSON.parse("""{"not":"an array"}""")).isNotEmpty())
    }
}
