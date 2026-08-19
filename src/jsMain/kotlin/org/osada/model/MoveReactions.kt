package org.osada.model

import org.osada.LeaderType
import org.osada.rules.AAInterception
import org.osada.rules.GameRules
import org.osada.rules.Minefields
import org.osada.rules.OverwatchFire
import org.osada.rules.UnitPredicates
import org.osada.rules.canPassInto
import org.osada.rules.setSpotRange

/*
 * Everything that can interrupt a move after its first hex, as [MoveExecutor] extensions.
 *
 * Moved out of [MoveExecutor] itself (2026-08-18) when `Overwatch` opportunity fire and minefields
 * joined AA interception: the walk in `traversePath` was carrying five interruption branches inline
 * and had outgrown both the class's function budget and its complexity budget. The split is along a
 * real seam -- `MoveExecutor` walks a path and commits a move; this file owns the list of things
 * that stop it.
 *
 * Three of the four relocate the unit onto the triggering cell before resolving, for the same reason
 * in each case: the combat (or the mine) must resolve against where the unit ACTUALLY is, and the
 * destroyed-unit sweep must find it where it fell rather than at its stale start-of-move hex.
 */

/**
 * Resolves every reaction to [unit] entering [cell], in the order they can fire, and answers whether
 * the walk must stop here.
 *
 * Order is deliberate and is the order OG's own rules imply: an aircraft is intercepted before
 * anything else can happen to it; overwatch answers a unit that is still moving; a minefield is
 * underfoot at the moment the unit arrives; an unseen enemy ZOC is the last thing checked because it
 * stops the unit without hurting it.
 *
 * [overwatchSpent] carries the ids of commanders that have already answered this move, so each fires
 * once per moving formation rather than once per hex.
 */
internal fun MoveExecutor.reactionStoppingMove(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    index: Int,
    overwatchSpent: MutableSet<Int>,
    result: MovementResults,
): Boolean {
    val blocked = !GameRules.canPassInto(setup.map, unit, cell)
    if (blocked) markSurprise(unit, cell, result)
    // `||` short-circuits, which is exactly the semantics wanted: each reaction only runs when no
    // earlier one has already stopped the move. Written as one expression rather than a ladder of
    // early returns so the order stays readable as a list.
    val stoppedBeforeZoc =
        blocked ||
            applyAAInterception(unit, setup, cell, index == setup.path.lastIndex, result) ||
            runOverwatchAndCheckDeath(unit, setup, cell, overwatchSpent, result) ||
            applyMinefield(unit, setup, cell, result)
    if (stoppedBeforeZoc) return true
    val stopped = stoppedByUnseenZoc(unit, setup.map, setup.side, cell, 1 - setup.side)
    if (stopped) result.stoppedByUnseenEnemy = true
    return stopped
}

/** Overwatch fire never stops a move by itself, but killing the mover does. Wrapped so it can sit in
 *  [reactionStoppingMove]'s short-circuit chain as one "did this end the move?" term. */
private fun MoveExecutor.runOverwatchAndCheckDeath(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    overwatchSpent: MutableSet<Int>,
    result: MovementResults,
): Boolean {
    applyOverwatchFire(unit, setup, cell, overwatchSpent, result)
    return unit.destroyed
}

/** AA interception of a moving aircraft (DEFERRED.md §1.1). Checks the cell [unit] just entered; if
 *  any enemy AA fires, relocates [unit] there and applies one-sided damage. Returns true when
 *  interception fired -- the walk must stop at this cell regardless of whether the plane survived
 *  (docs/design/aa-interception.md §3.4). */
internal fun MoveExecutor.applyAAInterception(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    isDestination: Boolean,
    result: MovementResults,
): Boolean {
    val interceptors =
        if (UnitPredicates.isAir(unit)) {
            AAInterception.interceptorsFor(gameMap, unit, cell, isDestination)
        } else {
            emptyList()
        }
    if (interceptors.isEmpty()) return false
    relocateTo(unit, setup, cell)
    result.interceptions.addAll(AAInterception.applyInterception(gameMap, unit, interceptors))
    result.wasIntercepted = true
    return true
}

/** Opportunity fire by enemy `Overwatch` commanders on the cell [unit] just entered
 *  (`rules/OverwatchFire`). Does NOT stop the walk -- see that object for why the two reactions
 *  differ there. */
internal fun MoveExecutor.applyOverwatchFire(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    overwatchSpent: MutableSet<Int>,
    result: MovementResults,
) {
    val watchers = OverwatchFire.watchersFor(gameMap, unit, cell, overwatchSpent)
    if (watchers.isEmpty()) return
    relocateTo(unit, setup, cell)
    watchers.forEach { overwatchSpent.add(it.id) }
    result.interceptions.addAll(OverwatchFire.applyOverwatch(gameMap, unit, watchers))
    result.wasFiredOnWhileMoving = true
}

/**
 * OG 9.9's minefield entry rules, for the cell [unit] just walked into.
 *
 * *"Entering a detected minefield consumes all remaining movement. Entering an undetected one
 * suffers some damage and ends its movement."* Both outcomes stop the walk here; only the undetected
 * one costs strength, and it reveals the field in the same instant so the loss is never unexplained.
 *
 * A DETECTED field is normally never reached by this path at all -- the move overlay already charges
 * it `MoveRangeCalculation`'s ZOC sentinel, so a route cannot continue through one. The check is kept
 * anyway because a path may be walked from a stale overlay, and stopping is correct either way.
 */
internal fun MoveExecutor.applyMinefield(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    result: MovementResults,
): Boolean {
    val hex = setup.map[cell.row][cell.col]
    if (!Minefields.threatens(hex, setup.side)) return false
    val wasHidden = !Minefields.isDetectedBy(hex, setup.side)
    relocateTo(unit, setup, cell)
    Minefields.markDetected(hex, setup.side)
    result.hitMinefield = true
    result.minefieldWasHidden = wasHidden
    if (wasHidden) {
        unit.hit(Minefields.UNDETECTED_MINE_DAMAGE)
        result.minefieldLosses = Minefields.UNDETECTED_MINE_DAMAGE
    }
    return true
}

internal fun MoveExecutor.markSurprise(
    unit: GameUnit,
    cell: Cell,
    result: MovementResults,
) {
    if (!Leaders.unitHasLeader(unit, LeaderType.BATTLEFIELD_INTELLIGENCE) &&
        !Leaders.unitHasLeader(unit, LeaderType.SKILLED_ASSAULT)
    ) {
        unit.isSurprised = true
        result.surpriseCell.add(cell)
    }
}

/** Moves [unit] onto [cell] mid-walk, keeping the spotting reference counts balanced -- the remove
 *  and the add must always come in that order and in a pair, or the fog stays permanently lifted
 *  over the hexes the unmatched add touched (`Hex.clearSpotted`). */
private fun MoveExecutor.relocateTo(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
) {
    GameRules.setSpotRange(gameMap, unit, false)
    unit.getHex()?.delUnit(unit)
    setup.map[cell.row][cell.col].setUnit(unit)
    GameRules.setSpotRange(gameMap, unit, true)
}
