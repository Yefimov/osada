package org.osada.model

import org.osada.rules.AutoMount
import org.osada.rules.UnitPredicates

internal fun MoveExecutor.resolveUndoContext(): MoveExecutor.UndoContext? {
    val unit = gameMap.undoState.unit
    val saved = gameMap.undoState.savedUnit
    return if (unit != null && saved != null) resolveUndoPositions(unit, saved) else null
}

internal fun MoveExecutor.resolveUndoPositions(
    unit: GameUnit,
    saved: GameUnit,
): MoveExecutor.UndoContext? {
    val from = unit.getPos()
    val savedPos = saved.getPos()
    return if (from != null && savedPos != null) resolveUndoHexes(unit, saved, from, savedPos) else null
}

internal fun MoveExecutor.resolveUndoHexes(
    unit: GameUnit,
    saved: GameUnit,
    from: Cell,
    savedPos: Cell,
): MoveExecutor.UndoContext? {
    val fromHex = gameMap.map?.getOrNull(from.row)?.getOrNull(from.col)
    val savedHex = gameMap.map?.getOrNull(savedPos.row)?.getOrNull(savedPos.col)
    return if (fromHex != null && savedHex != null) {
        MoveExecutor.UndoContext(unit, saved, fromHex, savedHex)
    } else {
        null
    }
}

/**
 * Open General's ride-to-get-there: a destination out of reach on foot but in reach aboard the
 * formation's own transport mounts it first (`rules/AutoMount` explains why this needs no
 * ruleset key and why it is decided here).
 *
 * **Here rather than in the click handler** because this is the one function the local player,
 * the AI and a replayed multiplayer `MoveUnit` all pass through, so no peer can mount while the
 * other walks — and no new multiplayer command is needed for it.
 *
 * Rebuilding the move range afterwards is not cosmetic: the move setup paths over
 * `gameMap.currentMoveRange`, and the route to a hex only the truck can reach has to be costed
 * with the truck's own movement table.
 */
internal fun MoveExecutor.autoMountForMove(
    unit: GameUnit,
    row: Int,
    col: Int,
): Boolean {
    if (!AutoMount.requiresTransport(gameMap, unit, Cell(row, col))) return false
    gameMap.mountUnitHandler(unit)
    gameMap.setMoveRange(unit)
    return true
}

/** Puts a formation back on the ground when the ride turned out to have no route after all —
 *  a mount the player never sees must not be a mount they have to undo. */
internal fun MoveExecutor.revertAutoMount(unit: GameUnit) {
    gameMap.unmountUnitHandler(unit)
    gameMap.setMoveRange(unit)
}

/**
 * A walk that was cut short must not end on a hex another unit of the same layer holds.
 *
 * A route may pass THROUGH a friendly formation's hex (`canPassInto`), but nothing may stop on
 * one. Every stop that only ends the walk -- a zone-of-control contact, an ambush, a hidden enemy
 * blocking the next hex -- could land it exactly there, and `applyMove` would then put two units
 * on one hex (the second overwriting the first in the hex's slot). The walk steps back to the
 * last hex it may end on and returns the cost of the hexes it gave up.
 *
 * The two reactions that already MOVED the unit mid-walk (interception, minefield) are left alone:
 * they resolve against where the unit physically is.
 */
internal fun stepBackOffOccupiedHexes(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    result: MovementResults,
): Int {
    if (result.wasIntercepted || result.hitMinefield) return 0
    var refunded = 0
    while (result.passedCells.size > 1 && heldByAnother(unit, setup, result.passedCells.last())) {
        val dropped = result.passedCells.removeAt(result.passedCells.lastIndex)
        refunded += (dropped as? ExtendedCell)?.cost ?: 1
    }
    return refunded
}

private fun heldByAnother(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
): Boolean {
    val hex = setup.map[cell.row][cell.col]
    val occupant = if (UnitPredicates.isAir(unit)) hex.airunit else hex.unit
    return occupant != null && occupant !== unit
}
