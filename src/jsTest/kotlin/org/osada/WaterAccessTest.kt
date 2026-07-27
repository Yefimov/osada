package org.osada

import org.osada.model.GameMap
import org.osada.model.allocMap
import org.osada.model.hasOpenWaterAccess
import org.osada.model.hasWaterAccess
import org.osada.model.invalidateWaterAccessCache
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `hasWaterAccess` / `hasOpenWaterAccess` gate the Purchase list's ship entries. Both used to test
 * a hardcoded terrain set taken from PM's shared movement table; they now ask the table in force,
 * so a per-efile `[terrain-cost]` is honoured. The case that motivated the change is `Falciu 1`,
 * whose Prut is drawn entirely in RIVER and IMPASSABLE_RIVER with no Ocean or Port hex anywhere:
 * under PM's table no blue-water ship could ever move there, under BASEKORP's NAVAL row
 * (IMPASSABLE_RIVER = 1) one can.
 */
class WaterAccessTest {
    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        movTable = movTableDry
    }

    @AfterTest
    fun cleanup() {
        movTable = movTableDry
    }

    /** A 2x2 map entirely of [terrain]. */
    private fun mapOfTerrain(terrain: Int): GameMap =
        GameMap()
            .apply {
                rows = 2
                cols = 2
                allocMap()
            }.also { map ->
                for (r in 0 until 2) {
                    for (c in 0 until 2) {
                        map.map!![r][c].terrain = terrain
                    }
                }
            }

    /** BASEKORP's NAVAL row, dry: ocean/port/impassable-river passable, ordinary river NOT. */
    private fun withBasekorpNavalRow() {
        movTable =
            movTableDry.mapIndexed { method, row ->
                if (method == MovMethod.NAVAL.value) {
                    row.toMutableList().also { it[TerrainType.IMPASSABLE_RIVER.value] = 1 }
                } else {
                    row
                }
            }
    }

    @Test
    fun aLandLockedMapHasNoWaterAccessAtAll() {
        val map = mapOfTerrain(TerrainType.CLEAR.value)

        assertFalse(map.hasWaterAccess())
        assertFalse(map.hasOpenWaterAccess())
    }

    @Test
    fun anOceanMapHasBoth() {
        val map = mapOfTerrain(TerrainType.OCEAN.value)

        assertTrue(map.hasWaterAccess())
        assertTrue(map.hasOpenWaterAccess())
    }

    /** The 2026-07-15 report: Operation Uranus is all Don-river hexes and must not offer
     *  submarines/destroyers, because ordinary RIVER is 255 for DEEP_NAVAL and NAVAL. */
    @Test
    fun aRiverOnlyMapIsCoastalOnly() {
        val map = mapOfTerrain(TerrainType.RIVER.value)

        assertTrue(map.hasWaterAccess(), "a coastal gunboat can work this map")
        assertFalse(map.hasOpenWaterAccess(), "a destroyer cannot")
    }

    @Test
    fun pmsTableLeavesAnImpassableRiverMapCompletelyUnusable() {
        val map = mapOfTerrain(TerrainType.IMPASSABLE_RIVER.value)

        assertFalse(map.hasWaterAccess())
        assertFalse(map.hasOpenWaterAccess())
    }

    @Test
    fun anEfileThatOpensImpassableRiverToNavalMakesItOpenWater() {
        withBasekorpNavalRow()
        val map = mapOfTerrain(TerrainType.IMPASSABLE_RIVER.value)

        assertTrue(map.hasOpenWaterAccess(), "BASEKORP NAVAL crosses terrain 15")
    }

    /** The answers are cached, and the table is swapped by weather. `Scenario.setMoveTable` calls
     *  [invalidateWaterAccessCache] for exactly this reason. */
    @Test
    fun swappingTheTableWithoutInvalidatingKeepsTheStaleAnswerUntilInvalidated() {
        val map = mapOfTerrain(TerrainType.IMPASSABLE_RIVER.value)
        assertFalse(map.hasOpenWaterAccess(), "cached under PM's table")

        withBasekorpNavalRow()
        assertFalse(map.hasOpenWaterAccess(), "still the cached answer")

        map.invalidateWaterAccessCache()
        assertTrue(map.hasOpenWaterAccess(), "recomputed against the new table")
    }
}
