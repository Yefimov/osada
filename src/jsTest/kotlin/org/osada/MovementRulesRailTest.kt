package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.getPlayer
import org.osada.model.hasRailData
import org.osada.model.resetEquipment
import org.osada.rules.HexGeometry
import org.osada.rules.MovementRules
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A train moves on rail or it does not move.**
 *
 * This class used to guard the opposite: a RAIL unit was confined to `hex.rail` only once the map
 * carried rail data, and on any scenario not yet patched by `add_rails.py` it fell back to
 * cross-country WHEELED movement. That escape hatch was a workaround for missing DATA and it
 * produced the defect it was meant to prevent — an armoured train driving over open steppe, which
 * is what a player reported. The data gap is now closed where a source exists, and the rule stands
 * without the hatch.
 *
 * A formation left off the line is therefore immobile, and that is the intended outcome rather than
 * a casualty of it: cut off from their track, armoured trains of the period were dug in at a
 * terminus or a works siding and fought as fixed firing points.
 *
 * The two reinforcement cases below are the exception that must survive, and they are not the same
 * question: a reinforcement that finds NO position is never added to the map at all, so refusing an
 * off-rail arrival would delete the formation instead of immobilising it.
 */
class MovementRulesRailTest {
    private val trainEqid = 100

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        Equipment.putEquipment(
            trainEqid,
            EquipmentData().apply {
                uclass = UnitClass.ARTILLERY.value
                movmethod = MovMethod.RAIL.value
                movpoints = 6
                fuel = 60
                ammo = 8
            },
        )
    }

    private fun buildMap(): GameMap {
        val map =
            GameMap().apply {
                rows = 5
                cols = 5
                allocMap()
            }
        val player =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        map.addPlayer(player)
        return map
    }

    private fun placeTrain(
        map: GameMap,
        row: Int,
        col: Int,
    ): GameUnit {
        val unit =
            GameUnit(trainEqid).apply {
                owner = 0
                player = map.getPlayer(0)
                strength = 7
            }
        map.map
            ?.get(row)
            ?.get(col)
            ?.setUnit(unit)
        return unit
    }

    @Test
    fun trainConfinedToRailWhenMapHasRailData() {
        val map = buildMap()
        // One rail neighbour of (2,2), the rest plain clear terrain.
        map.map
            ?.get(1)
            ?.get(2)
            ?.rail = RoadType.NORTH.value
        val unit = placeTrain(map, 2, 2)

        val range = MovementRules.getMoveRange(map, unit)
        val reached = range.map { it.row to it.col }.toSet()

        assertTrue((1 to 2) in reached, "train should reach the rail-connected neighbour")
        val offRailNeighbours = HexGeometry.getAdjacentCompat(2, 2).filter { it != (1 to 2) }
        offRailNeighbours.forEach { (r, c) ->
            assertFalse((r to c) in reached, "train should NOT reach off-rail neighbour ($r,$c)")
        }
    }

    /** The behaviour the player reported, inverted: no rail anywhere means the train stays put. */
    @Test
    fun trainCannotMoveAtAllWhenTheMapHasNoRail() {
        val map = buildMap()
        val unit = placeTrain(map, 2, 2)
        assertFalse(map.hasRailData())

        val reached = MovementRules.getMoveRange(map, unit).map { it.row to it.col }.toSet()

        HexGeometry.getAdjacentCompat(2, 2).forEach { (r, c) ->
            assertFalse((r to c) in reached, "a railless map must not let a train onto ($r,$c)")
        }
    }

    /** And the same on a map that HAS rail, for a train the author parked away from it. */
    @Test
    fun trainStandingOffTheLineIsAFixedFiringPoint() {
        val map = buildMap()
        map.map
            ?.get(0)
            ?.get(0)
            ?.rail = RoadType.NORTH.value
        val unit = placeTrain(map, 3, 3)

        val reached = MovementRules.getMoveRange(map, unit).map { it.row to it.col }.toSet()

        assertTrue(map.hasRailData(), "the map does carry rail, just not under this unit")
        HexGeometry.getAdjacentCompat(3, 3).forEach { (r, c) ->
            assertFalse((r to c) in reached, "an off-rail train must not reach ($r,$c)")
        }
    }

    // Regression guard: movTable[RAIL.value] is intentionally all-255 (Constants.kt) as a "never
    // read directly" placeholder. A caller that forgets the WHEELED fallback resolves straight
    // through that row and NEVER finds a passable hex, so a train reinforcement silently and
    // PERMANENTLY vanishes (Game.kt's deployReinforcements only adds the unit when a position is
    // found) instead of merely being confined to rail. Caught via a real Kiev Counteroffensive
    // ("Krytyi Vagon") report where a turn-2 reinforcement never appeared on the map.

    @Test
    fun trainReinforcementNeverVanishesOnUnpatchedMap() {
        val map = buildMap() // no rail data anywhere
        val unit =
            GameUnit(trainEqid).apply {
                owner = 0
                player = map.getPlayer(0)
                strength = 7
            }

        val pos = MovementRules.getReinforcementDeployPositions(map, unit, 2, 2)

        assertTrue(pos != null, "train reinforcement must find a WHEELED-equivalent hex, never vanish")
    }

    @Test
    fun trainReinforcementFallsBackWhenDeclaredHexIsntOnRail() {
        val map = buildMap()
        // The map DOES have rail data (enforceRail engages) but nowhere near the declared spot.
        map.map
            ?.get(4)
            ?.get(4)
            ?.rail = RoadType.NORTH.value
        val unit =
            GameUnit(trainEqid).apply {
                owner = 0
                player = map.getPlayer(0)
                strength = 7
            }

        val pos = MovementRules.getReinforcementDeployPositions(map, unit, 0, 0)

        assertTrue(pos != null, "reinforcement must fall back to a terrain-passable hex rather than vanish")
    }
}

/** [HexGeometry.getAdjacent] returns [Cell]s; tests just want row/col pairs. */
private fun HexGeometry.getAdjacentCompat(
    row: Int,
    col: Int,
): List<Pair<Int, Int>> = getAdjacent(row, col).map { it.row to it.col }
