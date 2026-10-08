package org.osada.rules

import org.osada.Game
import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.model.ALL_VICTORY_TIERS
import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.getEliminationWinner
import org.osada.model.moveUnit
import org.osada.model.setHex
import org.osada.model.setMoveRange
import org.osada.restoreVictoryMetadata
import org.osada.scenario.CampaignRouting
import org.osada.scenario.Scenario
import org.osada.scenario.objectiveReport
import org.osada.scenario.recordCaptureGoalSides
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG's end-of-scenario holding conditions switch off the immediate all-objectives capture victory
 * for the side that carries them (`scenario/HoldVictoryMode.kt`).
 *
 * `forward15` is the case that exposed it: both sides hold-graded, deadlines 13/13/13, and the
 * campaign's +20% allowance moving only the last one to 16 -- so taking every objective on turns
 * 14-16 ended the battle as a tactical victory the tooltip never mentioned.
 */
class HoldVictoryCaptureTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
        ruleset()
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    // ---- the capture no longer ends a hold-graded battle --------------------------------------

    @Test
    fun aHoldGradedSideTakingEveryObjectiveFightsOnToTheTurnLimit() {
        val map = world()
        publish(map) { victoryHoldCounts = listOf(2, 1, 1) }
        objective(map, 4, 4, hostile, goalSide = 0)
        objective(map, 6, 6, hostile, goalSide = 0)
        map.recordCaptureGoalSides()

        assertEquals(-1, moveOnto(map, 4, 3, 4, 4, friendly), "one of two")
        assertEquals(-1, moveOnto(map, 6, 5, 6, 6, friendly), "every objective, but holding decides")

        assertEquals(friendly.id, map.map!![4][4].owner, "the capture itself still happened")
        assertEquals(friendly.id, map.map!![6][6].owner)
        assertTrue(map.sidesVictoryHexes[0].isEmpty(), "objective tracking still moves")
        assertEquals(2, map.sidesVictoryHexes[1].size, "and hands the hexes to the enemy to retake")
    }

    /** The difficulty allowance moved only the last deadline; a capture inside it ended forward15. */
    @Test
    fun capturesDuringDifficultyAddedTurnsDoNotEndIt() {
        val map = world()
        val scenario = publish(map) { victoryHoldCounts = listOf(2, 1, 1) }
        map.victoryTurns.addAll(listOf(13, 13, 16))
        map.maxTurns = 16
        map.turn = 15
        objective(map, 4, 4, hostile, goalSide = 0)
        objective(map, 6, 6, hostile, goalSide = 0)
        map.recordCaptureGoalSides()

        assertEquals(-1, moveOnto(map, 4, 3, 4, 4, friendly))
        assertEquals(-1, moveOnto(map, 6, 5, 6, 6, friendly), "turn 15 of 16 is not an early tactical win")

        map.turn = 16
        friendly.playedTurn = 16
        assertEquals("briliant", scenario.checkTimedOutcome(0, humanSides = 0), "graded by holding at the limit")
    }

    /** The model's own answer agrees, not only the move hand-off. */
    @Test
    fun captureHexReportsNoWinForAHoldGradedSide() {
        val map = world()
        publish(map) { victoryHoldCounts = listOf(2, 1, 1) }
        objective(map, 4, 4, hostile, goalSide = 0)
        map.recordCaptureGoalSides()

        val capture = map.combatApplication.captureHex(map.map!![4][4], place(map, infantryEqid, 0, 0, 0))

        assertEquals(false, capture["isWin"])
        assertEquals(true, capture["isCapture"])
    }

    // ---- what must keep working ---------------------------------------------------------------

    @Test
    fun anOrdinaryCaptureScenarioStillEndsOnTheLastObjective() {
        val map = world()
        publish(map) {}
        objective(map, 4, 4, hostile, goalSide = 0)
        objective(map, 6, 6, hostile, goalSide = 0)
        map.recordCaptureGoalSides()

        assertEquals(-1, moveOnto(map, 4, 3, 4, 4, friendly))
        assertEquals(0, moveOnto(map, 6, 5, 6, 6, friendly))
    }

    /** Per side: the hold-graded side fights on, its capture-graded opponent still wins at once. */
    @Test
    fun mixedModesAreDecidedPerSide() {
        val map = world()
        val scenario = publish(map) { victoryHoldCounts = listOf(2, 1, 1) }
        objective(map, 4, 4, hostile, goalSide = 0)
        objective(map, 2, 2, friendly, goalSide = 1)
        map.recordCaptureGoalSides()
        assertEquals(listOf(0, 1), map.captureGoalSides)

        assertTrue(scenario.objectiveReport(0, revealHidden = false).gradedByHoldCount)
        assertFalse(scenario.objectiveReport(1, revealHidden = false).gradedByHoldCount)
        assertEquals(
            1,
            scenario.objectiveReport(0, revealHidden = false).enemyNeeds.size,
            "side 0 is warned: its capture-graded enemy can still win on the spot",
        )
        assertTrue(
            scenario.objectiveReport(1, revealHidden = false).enemyNeeds.isEmpty(),
            "side 1 is not: a hold-graded enemy cannot",
        )

        // Side 0's capture puts (4,4) on side 1's list too, so side 1 is checked on a fresh map.
        assertEquals(-1, moveOnto(map, 4, 3, 4, 4, friendly), "hold-graded side takes its only goal")

        val other = world()
        publish(other) { victoryHoldCounts = listOf(2, 1, 1) }
        objective(other, 4, 4, hostile, goalSide = 0)
        objective(other, 2, 2, friendly, goalSide = 1)
        other.recordCaptureGoalSides()
        other.currentPlayer = hostile
        assertEquals(1, moveOnto(other, 2, 1, 2, 2, hostile), "the other side still wins by capture")
    }

    /** A completed typed tier is a capture completion too, and must not end a hold-graded battle. */
    @Test
    fun aCompletedTypedTierDoesNotEndAHoldGradedBattle() {
        val map = world()
        publish(map) {
            typedVictoryHexes = true
            victoryHoldCounts = listOf(2, 1, 1)
        }
        map.victoryTurns.addAll(listOf(5, 8, 10))
        objective(map, 4, 4, hostile, goalSide = 0, tiers0 = TypedVictoryHexes.BRILLIANT)
        objective(map, 6, 6, hostile, goalSide = 0, tiers0 = TypedVictoryHexes.VICTORY)
        map.recordCaptureGoalSides()

        assertEquals(-1, moveOnto(map, 4, 3, 4, 4, friendly))
        assertEquals(0, TypedVictoryHexes.completedTier(GameHolder.instance!!.scenario!!, map, 0), "tier is done")
    }

    /** A save carries the hold counts and `captureGoalSides`; the restored battle answers the same. */
    @Test
    fun aRestoredBattleKeepsTheHoldRule() {
        val source =
            Scenario(null).apply {
                map = world()
                victoryHoldCountsSide1 = listOf(5, 4, 3)
                map.captureGoalSides = listOf(0, 1)
            }
        val payload = reparse(GameStateSerializer.serializeScenario(source))
        val map = world()
        val restored = Scenario(null).apply { this.map = map }
        restoreVictoryMetadata(restored, payload)
        val goals = payload.captureGoalSides
        map.captureGoalSides = (0 until goals.length as Int).map { goals[it].toString().toInt() }
        GameHolder.instance = Game().also { it.scenario = restored }
        objective(map, 2, 2, friendly, goalSide = 1)

        assertEquals(listOf(5, 4, 3), restored.victoryHoldCountsSide1)
        assertEquals(listOf(0, 1), map.captureGoalSides, "persisted capture goals still name side 1")
        map.currentPlayer = hostile
        assertEquals(-1, moveOnto(map, 2, 1, 2, 2, hostile), "but its hold counts withhold the capture win")
    }

    @Test
    fun independentEndConditionsStillWork() {
        val map = world()
        val scenario =
            publish(map) {
                victoryHoldCounts = listOf(2, 1, 1)
                retreatUnitsPerSide = listOf(1, 0)
            }
        val exit = map.map!![5][5].apply { escapeGround = true }

        ExtendedVictory.withdraw(scenario, place(map, infantryEqid, 5, 5, 0), exit)
        assertEquals(0, ExtendedVictory.satisfiedSide(scenario, map), "retreat still wins")

        place(map, infantryEqid, 1, 1, 0)
        assertEquals(0, map.getEliminationWinner(), "and so does wiping the enemy out")
    }

    // ---- forward15's new grading: 5 / 4 / 3 for the Soviet side 1 -----------------------------

    @Test
    fun forward15GradesTheSovietSideOnItsOwnThresholds() {
        val expected = mapOf(5 to "briliant", 4 to "victory", 3 to "tactical", 2 to "lose")
        expected.forEach { (held, outcome) ->
            val map = world()
            val scenario =
                publish(map) {
                    // The German side 0 keeps 5/5/5; reading it for side 1 would grade 4 and 3 as defeats.
                    victoryHoldCounts = listOf(5, 5, 5)
                    victoryHoldCountsSide1 = listOf(5, 4, 3)
                }
            FIVE_OBJECTIVES.forEachIndexed { index, (r, c) ->
                val owner = if (index < held) hostile else friendly
                objective(map, r, c, owner, goalSide = 1 - owner.side)
            }
            map.maxTurns = 16
            map.turn = 16
            hostile.playedTurn = 16

            assertEquals(outcome, scenario.checkTimedOutcome(1, humanSides = 1), "$held objectives held")
            val report = scenario.objectiveReport(1, revealHidden = false)
            assertEquals(listOf(5, 4, 3), report.holdThresholds.map { it.count }, "the rail shows side 1's triple")
            assertEquals(held, report.victoryHeld)
            // forward.json: every winning grade -> the chosen front (here South, 38); lose -> 16.
            val next = CampaignRouting.effectiveRoute(outcome, committedRoute = 38) ?: FORWARD15_GOTO.getValue(outcome)
            assertEquals(if (held >= 3) 38 else 16, next, "$held objectives route onward only with 3+")
        }
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {
        const val FLAG_BASE = 7
        val FIVE_OBJECTIVES = listOf(0 to 2, 2 to 4, 4 to 6, 6 to 2, 7 to 7)

        /** `campaigns/data/forward.json` scenario 15's authored gotos (23 = Army Group Centre). */
        val FORWARD15_GOTO = mapOf("briliant" to 23, "victory" to 23, "tactical" to 23, "lose" to 16)
    }

    private fun publish(
        map: GameMap,
        configure: Scenario.() -> Unit,
    ): Scenario =
        Scenario(null).apply {
            this.map = map
            configure()
            GameHolder.instance = Game().also { game -> game.scenario = this }
        }

    private fun objective(
        map: GameMap,
        row: Int,
        col: Int,
        owner: Player,
        goalSide: Int,
        tiers0: Int = ALL_VICTORY_TIERS,
    ) {
        map.map!![row][col].apply {
            this.owner = owner.id
            // A visible flag, so the objective report lists it (the harness players carry no country).
            flag = FLAG_BASE + owner.id
            victorySide = goalSide
            victoryTiersSide0 = tiers0
        }
        map.setHex(row, col)
    }

    /** Moves a fresh infantry unit of [player] from (fromRow, fromCol) onto the objective; returns
     *  the side the move hands to the victory handler, -1 for none. */
    private fun moveOnto(
        map: GameMap,
        fromRow: Int,
        fromCol: Int,
        row: Int,
        col: Int,
        player: Player,
    ): Int {
        val unit = place(map, infantryEqid, fromRow, fromCol, player.side)
        map.setMoveRange(unit)
        return map.moveUnit(unit, row, col).isVictorySide
    }
}
