package org.osada.rules

import org.osada.model.Cell

// --- Geometry (HexGeometry) ---

fun GameRules.getDirection(
    fromRow: Int,
    fromCol: Int,
    toRow: Int,
    toCol: Int,
): Int? = HexGeometry.getDirection(fromRow, fromCol, toRow, toCol)

fun GameRules.distance(
    row1: Int,
    col1: Int,
    row2: Int,
    col2: Int,
): Int = HexGeometry.distance(row1, col1, row2, col2)

fun GameRules.getAdjacent(
    row: Int,
    col: Int,
): List<Cell> = HexGeometry.getAdjacent(row, col)

fun GameRules.isAdjacent(
    row1: Int,
    col1: Int,
    row2: Int,
    col2: Int,
): Boolean = HexGeometry.isAdjacent(row1, col1, row2, col2)

fun GameRules.facingToAdjacentIndex(facing: Int): Int = HexGeometry.facingToAdjacentIndex(facing)

internal fun GameRules.getRing(
    row: Int,
    col: Int,
    radius: Int,
    rows: Int,
    cols: Int,
    extended: Boolean,
): MutableList<Cell> = HexGeometry.getRing(row, col, radius, rows, cols, extended)
