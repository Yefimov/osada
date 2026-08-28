package org.osada.model

import org.osada.LeaderType
import org.osada.rules.AutoMount
import org.osada.rules.UnitCapabilities
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
 * DEFERRED.md §7.32 item 4: a hidden enemy's zone of control stops a move.
 *
 * `MoveRangeCalculation.resolveNeighborCost` applies the ZOC cost floor only to a hex the mover's
 * own side can see, so a move plotted past an unspotted enemy is costed as if that enemy were not
 * there. OG's Basic Manual §5.2 ends a move on becoming adjacent to an enemy and does not make
 * that conditional on having spotted it -- and `Camouflage Expert`, which fires at the first
 * enemy to enter its ZOC, cannot work at all if hidden units project nothing.
 *
 * **Fixed here rather than in the overlay, deliberately, because the overlay is what the player
 * sees.** Applying unseen ZOC to the move range would draw a short reach around an enemy the
 * player has not found, which reads the hidden unit's position straight off the UI -- the
 * fog-of-war leak the entry warned about. So the overlay stays optimistic and the *walk*
 * terminates on contact: the player commits a move, bumps into something, and stops there.
 * `passedCells.last()` is the arrival hex, so breaking the loop is all that is needed.
 *
 * Deliberately NOT done: no surprise/ambush penalty is applied. OG documents that hidden units
 * "can stop or ambush" a move; the stop is the documented, testable half, and inventing ambush
 * damage would be adding a rule rather than honouring one. [markSurprise] stays reserved for
 * walking into a blocked hex.
 */
internal fun MoveExecutor.stoppedByUnseenZoc(
    unit: GameUnit,
    map: Array<Array<Hex>>,
    moverSide: Int,
    cell: Cell,
    enemySide: Int,
): Boolean {
    val hex = map[cell.row][cell.col]
    // Exactly the predicate the overlay uses to decide it may skip the ZOC floor, negated.
    val overlayChargedForIt = hex.isSpotted(moverSide) || hex.unit?.tempSpotted == true
    // Air units neither project nor feel ZOC (MovementRules.setZOCRange skips them), and Superior
    // Maneuver bypasses it outright -- the same two exemptions MoveRangeCalculation applies.
    // ... and OG's `Partizan` (`attrEx` bit 10, wired 2026-08-27), which is the equipment-level
    // source of the same exemption: "not stopped by adjacent enemies". 720 records, 680 Infantry.
    val subjectToZoc =
        !UnitPredicates.isAir(unit) &&
            !Leaders.unitHasLeader(unit, LeaderType.SUPERIOR_MANEUVER) &&
            !UnitCapabilities.ignoresZoneOfControl(unit.unitData(true))
    return subjectToZoc && !overlayChargedForIt && hex.isZOC(enemySide)
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
