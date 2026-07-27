package org.osada.rules

import org.osada.Direction
import org.osada.model.Cell
import org.osada.model.ExtendedCell
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
    // facingToAdjacentIndex's cell indices into getAdjacent's 6-neighbour ordering.
    private const val ADJACENT_INDEX_S = 3
    private const val ADJACENT_INDEX_SE = 4
    private const val ADJACENT_INDEX_NE = 5

    /** Hex distance between two axial cells (odd columns are shifted down half a row). */
    fun distance(
        row1: Int,
        col1: Int,
        row2: Int,
        col2: Int,
    ): Int {
        val a = if (col2 % 2 == 1) 2 * row2 + 1 else 2 * row2
        val b = if (col1 % 2 == 1) 2 * row1 + 1 else 2 * row1
        val dx = abs(a - b)
        val dy = abs(col2 - col1)
        return if (dx > dy) ((dx - dy) / 2 + dy) else dy
    }

    /** The six neighbours of a cell, in the original JS ordering (N, NW, SW, S, SE, NE). */
    fun getAdjacent(
        row: Int,
        col: Int,
    ): List<Cell> =
        listOf(
            Cell(row - 1, col),
            Cell(row - 1 + col % 2, col - 1),
            Cell(row + col % 2, col - 1),
            Cell(row + 1, col),
            Cell(row + col % 2, col + 1),
            Cell(row - 1 + col % 2, col + 1),
        )

    /** True when (row2,col2) is one of the six neighbours of (row1,col1). */
    fun isAdjacent(
        row1: Int,
        col1: Int,
        row2: Int,
        col2: Int,
    ): Boolean =
        (row1 - 1 + col1 % 2 == row2 && col1 - 1 == col2) ||
            (row1 + col1 % 2 == row2 && col1 - 1 == col2) ||
            (row1 - 1 == row2 && col1 == col2) ||
            (row1 + 1 == row2 && col1 == col2) ||
            (row1 - 1 + col1 % 2 == row2 && col1 + 1 == col2) ||
            (row1 + col1 % 2 == row2 && col1 + 1 == col2)

    /** Compass-style direction (with diagonal offset) from one cell toward another, or null. */
    fun getDirection(
        fromRow: Int,
        fromCol: Int,
        toRow: Int,
        toCol: Int,
    ): Int? {
        var a = fromRow
        var b = toRow
        if (fromCol % 2 == 1 && a == b) a++
        if (toCol % 2 == 1 && a == b) b++
        val dx = a - b
        val dy = fromCol - toCol
        val offset = directionOffset(dx, dy)
        return when {
            dx > 0 -> directionForPositiveDx(dy, offset)
            dx < 0 -> directionForNegativeDx(dy, offset)
            else -> if (dy >= 0) Direction.W.value else Direction.E.value
        }
    }

    /** Maps a unit facing to the index of the corresponding cell in [getAdjacent]. */
    fun facingToAdjacentIndex(facing: Int): Int =
        when (facing) {
            Direction.N.value -> 0
            Direction.NW.value, Direction.NNW.value, Direction.WNW.value, Direction.W.value -> 1
            Direction.SW.value, Direction.WSW.value, Direction.SSW.value -> 2
            Direction.S.value -> ADJACENT_INDEX_S
            Direction.SE.value, Direction.SSE.value, Direction.ESE.value -> ADJACENT_INDEX_SE
            Direction.NE.value, Direction.E.value, Direction.ENE.value, Direction.NNE.value -> ADJACENT_INDEX_NE
            else -> 0
        }

    /**
     * Returns every cell within hex-distance [radius] of (row,col), excluding the
     * centre. Faithful port of the legacy `s(g,b,m,k,f,d)` ring helper: it walks the
     * centre column then the columns to either side, shrinking the row span by hex
     * parity. (An earlier implementation built horizontal spans and silently dropped
     * same-row neighbours, breaking move/attack range in some directions.)
     */
    internal fun getRing(
        row: Int,
        col: Int,
        radius: Int,
        rows: Int,
        cols: Int,
        extended: Boolean,
    ): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        if (radius <= 0) return result
        var top = row - radius
        var bottom = row + radius
        // Centre column.
        addCenterColumnCells(result, row, col, top, bottom, rows, extended)
        // Columns to the right and left; the row span shrinks as we move outward.
        for (e in 1..radius) {
            if ((col + e) % 2 == 1) {
                if (bottom > 0) bottom--
            } else {
                if (top < rows) top++
            }
            addSideColumnCells(result, row, col, top, bottom, rows, cols, e, extended)
        }
        return result
    }
}

// getDirection's diagonal-vs-straight facing threshold.
private const val SLOPE_DIAGONAL_THRESHOLD = 3

/** Slope-based diagonal offset for [HexGeometry.getDirection]: +1 past the diagonal threshold, -1 when dx<0. */
private fun directionOffset(
    dx: Int,
    dy: Int,
): Int {
    var offset = 0
    var slope = 1
    if (dx != 0) slope = abs(dy / dx)
    if (slope > SLOPE_DIAGONAL_THRESHOLD) offset = 1
    if (dx < 0) offset = -1
    return offset
}

/** [HexGeometry.getDirection] branch for dx > 0 (facing north-ish). */
private fun directionForPositiveDx(
    dy: Int,
    offset: Int,
): Int =
    when {
        dy > 0 -> Direction.NW.value + offset
        dy < 0 -> Direction.NE.value + offset
        else -> Direction.N.value
    }

/** [HexGeometry.getDirection] branch for dx < 0 (facing south-ish). */
private fun directionForNegativeDx(
    dy: Int,
    offset: Int,
): Int =
    when {
        dy > 0 -> Direction.SW.value + offset
        dy < 0 -> Direction.SE.value + offset
        else -> Direction.S.value
    }

/** [HexGeometry.getRing]'s centre-column pass: every in-bounds row at `col`, excluding the centre itself. */
private fun addCenterColumnCells(
    result: MutableList<Cell>,
    row: Int,
    col: Int,
    top: Int,
    bottom: Int,
    rows: Int,
    extended: Boolean,
) {
    var n = top
    while (n <= bottom) {
        if (n in 0 until rows && n != row) {
            result.add(if (extended) ExtendedCell(n, col).also { it.range = abs(row - n) } else Cell(n, col))
        }
        n++
    }
}

/** Appends (n, targetCol) to [result] if [inBounds], mirroring [HexGeometry.getRing]'s cell construction. */
private fun addRingCell(
    result: MutableList<Cell>,
    row: Int,
    col: Int,
    n: Int,
    targetCol: Int,
    inBounds: Boolean,
    extended: Boolean,
) {
    if (!inBounds) return
    result.add(
        if (extended) {
            ExtendedCell(n, targetCol).also { it.range = HexGeometry.distance(row, col, n, targetCol) }
        } else {
            Cell(n, targetCol)
        },
    )
}

/** [HexGeometry.getRing]'s pass over one side-column offset `e`: the right and left columns at that offset. */
private fun addSideColumnCells(
    result: MutableList<Cell>,
    row: Int,
    col: Int,
    top: Int,
    bottom: Int,
    rows: Int,
    cols: Int,
    e: Int,
    extended: Boolean,
) {
    var n = top
    while (n <= bottom) {
        if (n in 0 until rows) {
            val rightCol = col + e
            addRingCell(result, row, col, n, rightCol, rightCol < cols, extended)
            val leftCol = col - e
            // `leftCol >= 0`, NOT PM's `0 < b - E` (`openpanzer.js`, the `s` ring helper). PM bounds
            // the right column with `b + E < f` (correct, exclusive upper) but the left one with
            // `0 < b - E`, which drops column 0 from EVERY ring -- so on every map no unit could
            // move onto, or attack into, column 0. `Falciu 1` has a Rumanian unit parked at (2,0):
            // permanently unattackable. Faithfully ported here, and a real defect either way, so it
            // is fixed rather than preserved (DEFERRED.md's 2026-07-26 framing: OG fidelity beats
            // PM fidelity). Widens move/attack ranges by at most one column, only at the west edge.
            addRingCell(result, row, col, n, leftCol, leftCol >= 0, extended)
        }
        n++
    }
}

/**
 * Dice/random rolls used by the rules engine. Mirrors the legacy `rollDice` helper.
 */
object Dice {
    /** Uniform integer roll in [min, max], matching the original `rollDice` formula. */
    fun roll(
        min: Int,
        max: Int,
    ): Int = (kotlin.random.Random.nextDouble() * (max - min + 1)).toInt() + min
}
