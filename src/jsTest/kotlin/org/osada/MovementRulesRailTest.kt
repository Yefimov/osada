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
 * Guards the Perekop Bronevagon fix: a RAIL-movmethod unit must be confined to hex.rail once the
 * map actually carries rail data, but must fall back to its old (pre-fix, WHEELED-equivalent)
 * cross-country behaviour on any scenario not yet re-patched with rail= attributes (the
 * mapHasRail safety guard in MovementRules.getMoveRange / GameMap.hasRailData).
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

    @Test
    fun trainFallsBackToCrossCountryWhenMapHasNoRailData() {
        val map = buildMap()
        // No hex anywhere has rail > 0 -- GameMap.hasRailData() must report false.
        val unit = placeTrain(map, 2, 2)
        assertFalse(map.hasRailData())

        val range = MovementRules.getMoveRange(map, unit)
        val reached = range.map { it.row to it.col }.toSet()

        // Same as a WHEELED unit on clear terrain: every adjacent hex is reachable.
        HexGeometry.getAdjacentCompat(2, 2).forEach { (r, c) ->
            assertTrue((r to c) in reached, "unpatched map: train should move like WHEELED onto ($r,$c)")
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
