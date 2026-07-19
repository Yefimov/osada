package org.osada.model

/** Combat/movement delegation for [GameMap] (to [CombatApplication]/[MoveExecutor]), split out
 *  to keep its function count in bounds. */
fun GameMap.attackUnit(
    attacker: GameUnit,
    defender: GameUnit,
    supportFire: Boolean,
    isOverrun: Boolean = false,
): CombatResults = combatApplication.attackUnit(attacker, defender, supportFire, isOverrun)

fun GameMap.retreatUnit(
    unit: GameUnit,
    to: Cell,
): MovementResults = combatApplication.retreatUnit(unit, to)

fun GameMap.captureHex(
    hex: Hex,
    unit: GameUnit,
): dynamic = combatApplication.captureHex(hex, unit)

fun GameMap.moveUnit(
    unit: GameUnit,
    row: Int,
    col: Int,
): MovementResults = moveExecutor.moveUnit(unit, row, col)

fun GameMap.undoLastMove() = moveExecutor.undoLastMove()

fun GameMap.canUndoMove(unit: GameUnit): Boolean = moveExecutor.canUndoMove(unit)
