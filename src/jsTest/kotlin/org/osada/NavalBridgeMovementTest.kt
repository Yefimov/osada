package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.getPlayer
import org.osada.model.resetEquipment
import org.osada.rules.MovementRules
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A bridge must not dam a river.
 *
 * `MoveRangeCalculation.resolveNeighborCost` used to take the movement table's ROAD column for any
 * hex carrying a road, exactly as PM does (`openpanzer.js:2157`). The road entry is 255 for all
 * three naval rows, so a river hex with a road -- a bridge -- became impassable to ships and cut
 * the river in two. Reported on `Falciu 1`: the Shtorm TB ran the river freely but could not pass
 * (19,23), `river/road9`.
 *
 * **OG cannot express this rule at all.** Its `TerrainEx.txt` `[terrain-cost]` table is 19 terrain
 * columns with no road column, and `EFILE_BASEKORP`'s Coastal row gives river cost 1 outright.
 */
class NavalBridgeMovementTest {
    private val coastalEqid = 200
    private val deepNavalEqid = 201
    private val truckEqid = 202

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        // `movTable` is a mutable global that scenario loading rewrites, so a sibling test can
        // leave it on frozen/mud -- where Coastal's river entry is 255 and these assertions would
        // fail for a reason that has nothing to do with bridges.
        movTable = movTableDry
        Equipment.resetEquipment()
        listOf(
            coastalEqid to MovMethod.COASTAL.value,
            deepNavalEqid to MovMethod.DEEP_NAVAL.value,
            truckEqid to MovMethod.WHEELED.value,
        ).forEach { (eqid, method) ->
            Equipment.putEquipment(
                eqid,
                EquipmentData().apply {
                    uclass = UnitClass.DESTROYER.value
                    movmethod = method
                    movpoints = 6
                    fuel = 60
                    ammo = 8
                },
            )
        }
    }

    /** A 5x5 map of [terrain], with (1,2) additionally carrying a road -- the bridge under test. */
    private fun buildMap(terrain: Int): GameMap {
        val map =
            GameMap().apply {
                rows = 5
                cols = 5
                allocMap()
            }
        map.addPlayer(
            Player().apply {
                id = 0
                side = 0
                country = 0
            },
        )
        for (r in 0 until 5) {
            for (c in 0 until 5) {
                map.map
                    ?.get(r)
                    ?.get(c)
                    ?.terrain = terrain
            }
        }
        map.map
            ?.get(1)
            ?.get(2)
            ?.road = RoadType.NORTH.value
        return map
    }

    private fun place(
        map: GameMap,
        eqid: Int,
    ): GameUnit {
        val unit =
            GameUnit(eqid).apply {
                owner = 0
                player = map.getPlayer(0)
                strength = 7
            }
        map.map
            ?.get(2)
            ?.get(2)
            ?.setUnit(unit)
        return unit
    }

    private fun reach(
        map: GameMap,
        unit: GameUnit,
    ): Set<Pair<Int, Int>> = MovementRules.getMoveRange(map, unit).map { it.row to it.col }.toSet()

    @Test
    fun aCoastalShipPassesUnderABridgedRiverHex() {
        val map = buildMap(TerrainType.RIVER.value)
        val unit = place(map, coastalEqid)

        assertTrue((1 to 2) in reach(map, unit), "a road over a river must not block a coastal ship")
    }

    @Test
    fun aRiverWithoutABridgeIsStillPassable() {
        val map = buildMap(TerrainType.RIVER.value)
        map.map
            ?.get(1)
            ?.get(2)
            ?.road = RoadType.NONE.value
        val unit = place(map, coastalEqid)

        assertTrue((1 to 2) in reach(map, unit), "control: the same hex without the road")
    }

    /** The fix must not float a blue-water ship up a river: Deep Naval's river entry is 255 with
     *  or without the road, so the fallback to the terrain column still refuses it. */
    @Test
    fun aDeepNavalShipStillCannotEnterARiver() {
        val map = buildMap(TerrainType.RIVER.value)
        val unit = place(map, deepNavalEqid)

        assertFalse((1 to 2) in reach(map, unit), "deep naval has no business on a river, bridge or not")
    }

    /** Land units must keep the road bonus — their road entry is passable, so nothing changes. */
    @Test
    fun aWheeledUnitStillTakesTheRoadColumn() {
        val map = buildMap(TerrainType.CLEAR.value)
        val unit = place(map, truckEqid)

        assertTrue((1 to 2) in reach(map, unit), "the road bonus still applies to a road-using method")
    }
}
