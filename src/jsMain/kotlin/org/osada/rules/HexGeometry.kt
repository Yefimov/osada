package org.osada.rules

import org.osada.Direction
import org.osada.model.Cell
import org.osada.model.ExtendedCell
import org.osada.rules.HexGeometry.getAdjacent
import kotlin.math.abs

/**
 * Pure hex-grid geometry: distance, adjacency, direction and ring enumeration.
 *
 * Stateless math with no dependency on game state, extracted from the former
 * `GameRules` god-object so the spatial primitives can be reasoned about and
 * tested in isolation. Faithful port of the corresponding helpers in
 * `osada.js` (`distance`, `getAdjacent`, `isAdjacent`, the `s` ring helper).
 */
object HexGeometry {

    /** Hex distance between two axial cells (odd columns are shifted down half a row). */
    fun distance(row1: Int, col1: Int, row2: Int, col2: Int): Int {
        val a = if (col2 % 2 == 1) 2 * row2 + 1 else 2 * row2
        val b = if (col1 % 2 == 1) 2 * row1 + 1 else 2 * row1
        val dx = abs(a - b)
        val dy = abs(col2 - col1)
        return if (dx > dy) ((dx - dy) / 2 + dy) else dy
    }

    /** The six neighbours of a cell, in the original JS ordering (N, NW, SW, S, SE, NE). */
    fun getAdjacent(row: Int, col: Int): List<Cell> = listOf(
        Cell(row - 1, col),
        Cell(row - 1 + col % 2, col - 1),
        Cell(row + col % 2, col - 1),
        Cell(row + 1, col),
        Cell(row + col % 2, col + 1),
        Cell(row - 1 + col % 2, col + 1),
    )

    /** True when (row2,col2) is one of the six neighbours of (row1,col1). */
    fun isAdjacent(row1: Int, col1: Int, row2: Int, col2: Int): Boolean =
        (row1 - 1 + col1 % 2 == row2 && col1 - 1 == col2) ||
            (row1 + col1 % 2 == row2 && col1 - 1 == col2) ||
            (row1 - 1 == row2 && col1 == col2) ||
            (row1 + 1 == row2 && col1 == col2) ||
            (row1 - 1 + col1 % 2 == row2 && col1 + 1 == col2) ||
            (row1 + col1 % 2 == row2 && col1 + 1 == col2)

    /** Compass-style direction (with diagonal offset) from one cell toward another, or null. */
    fun getDirection(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int): Int? {
        var a = fromRow
        var b = toRow
        if (fromCol % 2 == 1 && a == b) a++
        if (toCol % 2 == 1 && a == b) b++
        var dx = a - b
        val dy = fromCol - toCol
        var offset = 0
        var slope = 1
        if (dx != 0) slope = abs(dy / dx)
        if (slope > 3) offset = 1
        if (dx < 0) offset = -1
        return when {
            dx > 0 -> when {
                dy > 0 -> Direction.NW.value + offset
                dy < 0 -> Direction.NE.value + offset
                else -> Direction.N.value
            }
            dx < 0 -> when {
                dy > 0 -> Direction.SW.value + offset
                dy < 0 -> Direction.SE.value + offset
                else -> Direction.S.value
            }
            else -> when {
                dy >= 0 -> Direction.W.value
                else -> Direction.E.value
            }
        }
    }

    /** Maps a unit facing to the index of the corresponding cell in [getAdjacent]. */
    fun facingToAdjacentIndex(facing: Int): Int = when (facing) {
        Direction.N.value -> 0
        Direction.NW.value, Direction.NNW.value, Direction.WNW.value, Direction.W.value -> 1
        Direction.SW.value, Direction.WSW.value, Direction.SSW.value -> 2
        Direction.S.value -> 3
        Direction.SE.value, Direction.SSE.value, Direction.ESE.value -> 4
        Direction.NE.value, Direction.E.value, Direction.ENE.value, Direction.NNE.value -> 5
        else -> 0
    }

    /**
     * Returns every cell within hex-distance [radius] of (row,col), excluding the
     * centre. Faithful port of the legacy `s(g,b,m,k,f,d)` ring helper: it walks the
     * centre column then the columns to either side, shrinking the row span by hex
     * parity. (An earlier implementation built horizontal spans and silently dropped
     * same-row neighbours, breaking move/attack range in some directions.)
     */
    internal fun getRing(row: Int, col: Int, radius: Int, rows: Int, cols: Int, extended: Boolean): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        if (radius <= 0) return result
        var top = row - radius
        var bottom = row + radius
        // Centre column.
        var n = top
        while (n <= bottom) {
            if (n in 0 until rows && n != row) {
                result.add(if (extended) ExtendedCell(n, col).also { it.range = abs(row - n) } else Cell(n, col))
            }
            n++
        }
        // Columns to the right and left; the row span shrinks as we move outward.
        for (e in 1..radius) {
            if ((col + e) % 2 == 1) {
                if (bottom > 0) bottom--
            } else {
                if (top < rows) top++
            }
            n = top
            while (n <= bottom) {
                if (n in 0 until rows) {
                    val rightCol = col + e
                    if (rightCol < cols) {
                        result.add(
                            if (extended) {
                                ExtendedCell(n, rightCol).also {
                                    it.range =
                                        distance(row, col, n, rightCol)
                                }
                            } else {
                                Cell(n, rightCol)
                            },
                        )
                    }
                    val leftCol = col - e
                    if (leftCol > 0) {
                        result.add(
                            if (extended) {
                                ExtendedCell(n, leftCol).also {
                                    it.range =
                                        distance(row, col, n, leftCol)
                                }
                            } else {
                                Cell(n, leftCol)
                            },
                        )
                    }
                }
                n++
            }
        }
        return result
    }
}

/**
 * Dice/random rolls used by the rules engine. Mirrors the legacy `rollDice` helper.
 */
object Dice {
    /** Uniform integer roll in [min, max], matching the original `rollDice` formula. */
    fun roll(min: Int, max: Int): Int = (kotlin.random.Random.nextDouble() * (max - min + 1)).toInt() + min
}
