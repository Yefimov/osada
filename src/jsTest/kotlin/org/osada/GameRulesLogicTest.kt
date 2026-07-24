package org.osada

import org.osada.rules.Dice
import org.osada.rules.HexGeometry
import org.osada.rules.UnitPredicates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for pure-logic GameRules helpers that do not require loaded equipment data.
 */
class GameRulesLogicTest {
    @Test
    fun distanceOnHexGrid() {
        // Same cell
        assertEquals(0, HexGeometry.distance(5, 5, 5, 5))
        // Adjacent column
        assertEquals(1, HexGeometry.distance(0, 0, 1, 0))
        assertEquals(1, HexGeometry.distance(1, 0, 0, 0))
        // Adjacent diagonal
        assertEquals(1, HexGeometry.distance(0, 0, 0, 1))
        // A bit further
        assertTrue(HexGeometry.distance(0, 0, 2, 2) > 0)
    }

    @Test
    fun getAdjacentReturnsSixCells() {
        val adjacent = HexGeometry.getAdjacent(3, 4)
        assertEquals(6, adjacent.size)

        // Check that adjacent cells surround the origin (Cell has no value equality)
        fun hasCell(
            row: Int,
            col: Int,
        ) = adjacent.any { it.row == row && it.col == col }
        assertTrue(hasCell(2, 4), "expected Cell(2,4) in adjacent")
        assertTrue(hasCell(4, 4), "expected Cell(4,4) in adjacent")
        assertTrue(hasCell(2, 3), "expected Cell(2,3) in adjacent")
    }

    @Test
    fun closeCombatTerrainRecognition() {
        assertTrue(UnitPredicates.isCloseCombatTerrain(TerrainType.CITY.value))
        assertTrue(UnitPredicates.isCloseCombatTerrain(TerrainType.FOREST.value))
        assertTrue(UnitPredicates.isCloseCombatTerrain(TerrainType.MOUNTAIN.value))
        assertTrue(UnitPredicates.isCloseCombatTerrain(TerrainType.FORTIFICATION.value))
        assertFalse(UnitPredicates.isCloseCombatTerrain(TerrainType.CLEAR.value))
        assertFalse(UnitPredicates.isCloseCombatTerrain(TerrainType.OCEAN.value))
    }

    @Test
    fun facingToAdjacentIndexMapsDirections() {
        assertEquals(0, HexGeometry.facingToAdjacentIndex(Direction.N.value))
        assertEquals(3, HexGeometry.facingToAdjacentIndex(Direction.S.value))
    }

    @Test
    fun diceRollsAreInRange() {
        repeat(20) {
            val roll = Dice.roll(1, 20)
            assertTrue(roll in 1..20, "roll should be in 1..20, got $roll")
        }
    }

    /**
     * A radius-1 ring must contain exactly the six hex-adjacent cells. This is the
     * invariant the old horizontal-span getRing violated (it dropped same-row
     * neighbours), which broke move/attack range in certain directions.
     */
    @Test
    fun getRingRadiusOneEqualsAdjacency() {
        for ((cr, cc) in listOf(10 to 10, 10 to 11)) { // even and odd centre columns
            val ring =
                HexGeometry
                    .getRing(cr, cc, 1, 21, 21, false)
                    .map { it.row to it.col }
                    .toSet()
            val adjacent =
                HexGeometry
                    .getAdjacent(cr, cc)
                    .map { it.row to it.col }
                    .toSet()
            assertEquals(adjacent, ring, "radius-1 ring at ($cr,$cc) must equal adjacency")
        }
    }

    /**
     * For an interior centre, the ring must equal the brute-force hex-distance disk
     * (every cell with 1 <= distance <= radius, none beyond).
     */
    @Test
    fun getRingMatchesDistanceDisk() {
        val rows = 21
        val cols = 21
        for (radius in 1..3) {
            for ((cr, cc) in listOf(10 to 10, 10 to 11)) {
                val ring =
                    HexGeometry
                        .getRing(cr, cc, radius, rows, cols, false)
                        .map { it.row to it.col }
                        .toSet()
                val expected = distanceDisk(cr, cc, radius, rows, cols)
                assertEquals(expected, ring, "ring radius $radius at ($cr,$cc) must equal distance disk")
            }
        }
    }

    /** Brute-force set of all cells whose hex distance from (cr,cc) is in 1..radius. */
    private fun distanceDisk(
        cr: Int,
        cc: Int,
        radius: Int,
        rows: Int,
        cols: Int,
    ): Set<Pair<Int, Int>> {
        val expected = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val d = HexGeometry.distance(cr, cc, r, c)
                if (d in 1..radius) expected.add(r to c)
            }
        }
        return expected
    }
}
