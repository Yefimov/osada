package org.osada.ui

import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.allocMap
import org.osada.rules.HexGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DestroyedBridgeAppearanceTest {
    @Test
    fun recordedRoadWinsEvenWhenAdjacentBanksSuggestAnotherAxis() {
        val map = world()
        val masks =
            listOf(
                RoadType.NORTH.value or RoadType.SOUTH.value,
                RoadType.NORTHWEST.value or RoadType.SOUTHEAST.value,
                RoadType.SOUTHWEST.value or RoadType.NORTHEAST.value,
            )
        // Both column parities: screen direction must not follow the offset row number.
        for (col in listOf(2, 3)) {
            val hex = map.map!![2][col]
            val neighbours = HexGeometry.getAdjacent(2, col)
            masks.forEachIndexed { axis, mask ->
                neighbours.forEach { map.map!![it.row][it.col].terrain = TerrainType.CLEAR.value }
                for (index in listOf(axis, axis + 3)) {
                    val end = neighbours[index]
                    map.map!![end.row][end.col].terrain = TerrainType.RIVER.value
                }
                hex.blownRoad = mask
                assertEquals(axis, DestroyedBridgeAppearance.axis(map, hex))
            }
        }
    }

    @Test
    fun allDirectionBridgeUsesBanksRatherThanRunningDownTheRiver() {
        val map = world()
        val hex = map.map!![2][2]
        hex.blownRoad = 255 // Sapper-built bridges carry all directions, not an authored span.
        val neighbours = HexGeometry.getAdjacent(2, 2)
        neighbours.forEach { map.map!![it.row][it.col].terrain = TerrainType.IMPASSABLE_RIVER.value }
        map.map!![neighbours[1].row][neighbours[1].col].terrain = TerrainType.CLEAR.value
        map.map!![neighbours[4].row][neighbours[4].col].terrain = TerrainType.CLEAR.value

        assertEquals(1, DestroyedBridgeAppearance.axis(map, hex), "NW-SE joins the two dry banks")
    }

    @Test
    fun mapEdgeAndOceanAreNotBanks() {
        val map = world()
        val hex = map.map!![0][0]
        hex.blownRoad = 255
        map.map!![1][0].terrain = TerrainType.OCEAN.value
        map.map!![0][1].terrain = TerrainType.CLEAR.value

        assertEquals(1, DestroyedBridgeAppearance.axis(map, hex), "only SE reaches a known bank")
    }

    @Test
    fun wreckShowsUntilTheCrossingIsRestoredAndIgnoresFog() {
        val hex = world().map!![2][2]
        hex.terrain = TerrainType.RIVER.value
        hex.blownRoad = RoadType.NORTH.value or RoadType.SOUTH.value
        assertTrue(DestroyedBridgeAppearance.isVisible(hex), "an unspotted wreck is still terrain")

        hex.road = hex.blownRoad
        hex.blownRoad = 0
        assertFalse(DestroyedBridgeAppearance.isVisible(hex), "repaired")
    }

    private fun world(): GameMap =
        GameMap().apply {
            rows = 5
            cols = 5
            allocMap()
        }
}
