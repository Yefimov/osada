package org.osada.rules

import org.osada.GameStateSerializer
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.applySerializedScenarioProperties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scenario author's AI orders — OpenSuite's "Unit settings" panel.
 *
 * `rules/AiOrders` carries the decision these tests enforce: the author supplies CONSTRAINTS and
 * OBJECTIVES, OSADA's planner keeps command, and none of it reaches a human's buttons or the combat
 * resolver. [anAnchoredFormationStillFiresAndStillRetreats] is the test that says so.
 */
class AuthoredAiOrdersTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun teardown() = clearTestWorld()

    // ---- Anchored and hold-until ------------------------------------------------------------------

    /** 469,632 formations carry no order and must plan exactly as they always did. */
    @Test
    fun aFormationWithNoOrdersMayAlwaysMove() {
        val map = world()
        val unit = place(map, infantryEqid, 3, 3, 0)
        assertTrue(AiOrders.mayMove(unit, turn = 1))
        assertTrue(AiOrders.mayMove(unit, turn = 20))
        assertNull(AiOrders.objectiveOf(map, unit))
    }

    /** *"unit is fixed in place"* — for the planner, on every turn. */
    @Test
    fun anAnchoredFormationNeverMoves() {
        val map = world()
        val unit = place(map, infantryEqid, 3, 3, 0).apply { aiAnchored = true }
        assertFalse(AiOrders.mayMove(unit, turn = 1))
        assertFalse(AiOrders.mayMove(unit, turn = 30))
    }

    /**
     * *"unit does not move before turn N"* — and it becomes movable ON the authored turn, not after
     * it. `@56` is the turn the hold expires.
     */
    @Test
    fun aHeldFormationBecomesMovableOnTheAuthoredTurn() {
        val map = world()
        val unit = place(map, infantryEqid, 3, 3, 0).apply { aiHoldUntilTurn = 4 }
        assertFalse(AiOrders.mayMove(unit, turn = 3))
        assertTrue(AiOrders.mayMove(unit, turn = 4), "movable ON turn 4, not after it")
        assertTrue(AiOrders.mayMove(unit, turn = 5))
    }

    /**
     * The constraint the backlog names explicitly: an anchored artillery battery fires, refits and
     * is still pushed off its hex by a forced retreat. Nothing in `AiOrders` reaches those paths, so
     * this asserts the ABSENCE of coupling.
     */
    @Test
    fun anAnchoredFormationStillFiresAndStillRetreats() {
        val map = world()
        val battery = place(map, gunEqid, 3, 3, 0).apply { aiAnchored = true }
        val target = place(map, infantryEqid, 3, 5, 1)

        assertTrue(
            GameRules.canInitiateAttack(battery, target),
            "an anchored battery is a firing unit like any other",
        )
        assertFalse(AiOrders.mayMove(battery, turn = 1), "and only the AI planner is constrained")

        // A forced relocation is applied by the engine, not chosen by the planner, so nothing here
        // may refuse it.
        map.map!![3][3].delUnit(battery)
        map.map!![4][3].setUnit(battery)
        assertEquals(Cell(4, 3).row, assertNotNull(battery.getPos()).row)
    }

    // ---- Fearless -----------------------------------------------------------------------------------

    /** A scoring input, and only for the formation that carries it. */
    @Test
    fun fearlessIsAPerFormationScoringInput() {
        val map = world()
        val bold = place(map, infantryEqid, 3, 3, 0).apply { aiFearless = true }
        val ordinary = place(map, infantryEqid, 4, 4, 0)
        assertTrue(AiOrders.ignoresOwnLosses(bold))
        assertFalse(AiOrders.ignoresOwnLosses(ordinary))
        assertFalse(AiOrders.ignoresOwnLosses(null))
    }

    // ---- Objective hex ------------------------------------------------------------------------------

    /** The authored objective, as authored. */
    @Test
    fun anAuthoredObjectiveIsReportedAsItsHex() {
        val map = world()
        val unit = orderedTo(map, 3, 3, row = 6, col = 7)
        val objective = assertNotNull(AiOrders.objectiveOf(map, unit))
        assertEquals(6, objective.row)
        assertEquals(7, objective.col)
    }

    /**
     * *"free OH when closer than N"*: once the formation is within the authored distance the order
     * is spent and the planner's ordinary scoring takes over — which is what stops a unit standing
     * on its objective from being pinned there while the battle moves on.
     */
    @Test
    fun theObjectiveIsReleasedInsideTheAuthoredDistance() {
        val map = world()
        val unit = orderedTo(map, 3, 3, row = 3, col = 6).apply { aiFreeObjectiveDistance = 2 }
        assertNotNull(AiOrders.objectiveOf(map, unit), "three hexes away, still ordered")

        map.map!![3][3].delUnit(unit)
        map.map!![3][5].setUnit(unit)
        assertNull(AiOrders.objectiveOf(map, unit), "one hex away, released")
    }

    /** `@62` names another formation of the same player and means "take that one's objective". */
    @Test
    fun anInheritedObjectiveComesFromTheNamedFormation() {
        val map = world()
        val leader = orderedTo(map, 2, 2, row = 6, col = 7).apply { aiOrdinal = 4 }
        val follower = place(map, infantryEqid, 3, 3, 0).apply { aiObjectiveFromOrdinal = 4 }

        val objective = assertNotNull(AiOrders.objectiveOf(map, follower))
        assertEquals(6, objective.row)
        assertEquals(7, objective.col)
        assertEquals(4, leader.aiOrdinal)
    }

    /** `@50` bit 5 points at where the named formation IS, not where it was told to go. */
    @Test
    fun followUnitPositionTakesTheNamedFormationsCurrentHex() {
        val map = world()
        orderedTo(map, 2, 2, row = 6, col = 7).apply { aiOrdinal = 4 }
        val follower =
            place(map, infantryEqid, 3, 3, 0).apply {
                aiObjectiveFromOrdinal = 4
                aiFollowsObjectiveUnit = true
            }

        val objective = assertNotNull(AiOrders.objectiveOf(map, follower))
        assertEquals(2, objective.row, "the leader's own hex")
        assertEquals(2, objective.col)
    }

    /** Six corpus `@62` values name an ordinal no formation has; the order falls back, never crashes. */
    @Test
    fun anInheritedObjectiveNamingNobodyFallsBackToTheUnitsOwn() {
        val map = world()
        val unit = orderedTo(map, 3, 3, row = 5, col = 5).apply { aiObjectiveFromOrdinal = 99 }
        val objective = assertNotNull(AiOrders.objectiveOf(map, unit))
        assertEquals(5, objective.row)
    }

    /** Ordinals are PER PLAYER, so an enemy formation with the same ordinal is not the source. */
    @Test
    fun inheritanceNeverCrossesToTheOtherSide() {
        val map = world()
        orderedTo(map, 2, 2, row = 6, col = 7, side = 1).apply { aiOrdinal = 4 }
        val follower = place(map, infantryEqid, 3, 3, 0).apply { aiObjectiveFromOrdinal = 4 }
        assertNull(AiOrders.objectiveOf(map, follower), "no own-side formation carries ordinal 4")
    }

    // ---- Persistence ---------------------------------------------------------------------------------

    /** A restore never re-reads the scenario XML, so the enemy's authored plan has to be saved. */
    @Test
    fun ordersSurviveASaveRoundTrip() {
        val map = world()
        val unit =
            orderedTo(map, 3, 3, row = 6, col = 7).apply {
                aiAnchored = true
                aiHoldUntilTurn = 5
                aiFearless = true
                aiFreeObjectiveDistance = 2
                aiObjectiveFromOrdinal = 3
                aiFollowsObjectiveUnit = true
                aiOrdinal = 9
            }

        val restored = GameUnit(infantryEqid)
        restored.applySerializedScenarioProperties(reparse(GameStateSerializer.serializeUnit(unit)))

        assertTrue(restored.aiAnchored)
        assertEquals(5, restored.aiHoldUntilTurn)
        assertTrue(restored.aiFearless)
        assertEquals(7, restored.aiObjectiveCol)
        assertEquals(6, restored.aiObjectiveRow)
        assertEquals(2, restored.aiFreeObjectiveDistance)
        assertEquals(3, restored.aiObjectiveFromOrdinal)
        assertTrue(restored.aiFollowsObjectiveUnit)
        assertEquals(9, restored.aiOrdinal)
    }

    /** An unordered formation's save keeps exactly the shape it had before the panel was imported. */
    @Test
    fun anUnorderedFormationWritesNoneOfTheNewKeys() {
        val map = world()
        val saved = reparse(GameStateSerializer.serializeUnit(place(map, infantryEqid, 3, 3, 0)))
        assertTrue(saved.aiAnchored == undefined)
        assertTrue(saved.aiHoldUntil == undefined)
        assertTrue(saved.aiObjCol == undefined)

        val restored = GameUnit(infantryEqid)
        restored.applySerializedScenarioProperties(saved)
        assertEquals(-1, restored.aiObjectiveCol, "absent is 'no objective', not hex 0")
        assertEquals(-1, restored.aiObjectiveRow)
    }

    private fun orderedTo(
        map: org.osada.model.GameMap,
        atRow: Int,
        atCol: Int,
        row: Int,
        col: Int,
        side: Int = 0,
    ): GameUnit =
        place(map, infantryEqid, atRow, atCol, side).apply {
            aiObjectiveRow = row
            aiObjectiveCol = col
        }
}
