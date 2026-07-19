package org.osada.rules

import org.osada.model.Cell
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex

// --- Movement / spotting / deploy (MovementRules) ---

fun GameRules.getMoveRange(
    map: GameMap,
    unit: GameUnit,
): Array<ExtendedCell> = MovementRules.getMoveRange(map, unit)

fun GameRules.getUnitMoveRange(unit: GameUnit): Int = MovementRules.getUnitMoveRange(unit)

fun GameRules.getUnitSpotRange(unit: GameUnit): Int = MovementRules.getUnitSpotRange(unit)

fun GameRules.setZOCRange(
    map: GameMap,
    unit: GameUnit,
    add: Boolean,
) = MovementRules.setZOCRange(map, unit, add)

fun GameRules.setSpotRange(
    map: GameMap,
    unit: GameUnit,
    add: Boolean,
): Int = MovementRules.setSpotRange(map, unit, add)

fun GameRules.getShortestPath(
    start: Cell,
    end: Cell,
    moveRange: List<Cell>,
): List<Cell> = MovementRules.getShortestPath(start, end, moveRange)

fun GameRules.canPassInto(
    map: Array<Array<Hex>>?,
    unit: GameUnit,
    cell: Cell,
): Boolean = MovementRules.canPassInto(map, unit, cell)

fun GameRules.isBridgeForSide(
    hex: Hex?,
    side: Int,
): Boolean = MovementRules.isBridgeForSide(hex, side)

fun GameRules.getEmbarkType(
    map: GameMap,
    unit: GameUnit,
): Int = EmbarkRules.getEmbarkType(map, unit)

fun GameRules.getDisembarkPositions(
    map: GameMap,
    unit: GameUnit,
): List<Cell> = EmbarkRules.getDisembarkPositions(map, unit)

fun GameRules.getReinforcementDeployPositions(
    map: GameMap,
    unit: GameUnit,
    row: Int,
    col: Int,
): Cell? = MovementRules.getReinforcementDeployPositions(map, unit, row, col)
