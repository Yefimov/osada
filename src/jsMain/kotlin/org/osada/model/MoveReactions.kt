package org.osada.model

import org.osada.rules.AAInterception
import org.osada.rules.AttackEligibility
import org.osada.rules.GameRules
import org.osada.rules.Minefields
import org.osada.rules.MoveAmbush
import org.osada.rules.UnitPredicates
import org.osada.rules.ZoneOfControlContact
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
 * anything else can happen to it; an ambush (`Overwatch`, Camouflage Expert) is sprung on a unit
 * that is still moving; a minefield is underfoot at the moment the unit arrives; an enemy zone of
 * control is the last thing checked because it stops the unit without hurting it.
 */
internal fun MoveExecutor.reactionStoppingMove(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    index: Int,
    result: MovementResults,
): Boolean {
    // `||` short-circuits, which is exactly the semantics wanted: each reaction only runs when no
    // earlier one has already stopped the move. Written as one expression rather than a ladder of
    // early returns so the order stays readable as a list. An occupied hex is not in this list:
    // it stops the walk BEFORE the cell is entered ([stoppedByOccupant]), these stop it inside.
    //
    // An enemy's surface zone of control ends the walk short of the destination -- including a
    // zone discovered only now (`rules/ZoneOfControlContact`, OG manual 6.13.1). It comes after
    // the reactions, so an ambush or a minefield on the same step still takes effect.
    return applyAAInterception(unit, setup, cell, index == setup.path.lastIndex, result) ||
        ambushedAt(unit, cell, result) ||
        applyMinefield(unit, setup, cell, result) ||
        stoppedByZoneOfControl(unit, setup, cell, index, result)
}

/**
 * The walk entered an enemy's surface zone of control short of its destination
 * (`rules/ZoneOfControlContact`): it ends here, with no combat. Checked LAST, so a leader reaction
 * or a minefield on the same step has already had its turn.
 *
 * A zone the mover's side could see was already a route terminal, so in practice this fires on a
 * NEWLY DISCOVERED enemy -- reported as contact. A stale route through a known zone stops here too,
 * which is correct either way. The destination itself is exempt: stopping there is arriving.
 */
private fun MoveExecutor.stoppedByZoneOfControl(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    index: Int,
    result: MovementResults,
): Boolean {
    val applies = index != setup.path.lastIndex && ZoneOfControlContact.appliesTo(unit)
    val projectors = if (applies) ZoneOfControlContact.projectors(setup.map, cell, setup.side) else emptyList()
    if (projectors.isNotEmpty()) {
        result.stoppedByContact = projectors.none { ZoneOfControlContact.isKnownTo(it, setup.side) }
    }
    return projectors.isNotEmpty()
}

/**
 * Whether [cell] holds a unit [unit] cannot pass -- a hidden enemy the optimistic path went
 * through. The walk then ends on the hex BEFORE it ([MoveExecutor]'s `traversePath` checks this
 * ahead of adding the cell), and the mover is surprised unless a leader protects it.
 */
internal fun MoveExecutor.stoppedByOccupant(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    cell: Cell,
    result: MovementResults,
): Boolean {
    if (GameRules.canPassInto(setup.map, unit, cell)) return false
    markSurprise(unit, cell, result)
    return true
}

/** An enemy `Overwatch` or Camouflage Expert commander springs its ambush on [unit] entering
 *  [cell] (`rules/MoveAmbush`): the walk stops there, the mover is surprised, and the ambusher is
 *  recorded for the combat the move-finish fights. Spent at once, so a second mover this turn
 *  finds it used. */
private fun MoveExecutor.ambushedAt(
    unit: GameUnit,
    cell: Cell,
    result: MovementResults,
): Boolean {
    val ambusher = MoveAmbush.ambusherFor(gameMap, unit, cell) ?: return false
    ambusher.hasAmbushedThisTurn = true
    unit.isSurprised = true
    result.ambusher = ambusher
    return true
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
        // "Up to three strength points, depending on experience and Engineer status" -- graded per
        // formation rather than flat since 2026-08-28 (`Minefields.strikeDamage`).
        val damage = Minefields.strikeDamage(unit)
        unit.hit(damage)
        result.minefieldLosses = damage
    }
    return true
}

internal fun MoveExecutor.markSurprise(
    unit: GameUnit,
    cell: Cell,
    result: MovementResults,
) {
    if (MoveAmbush.canBeSurprised(unit)) {
        unit.isSurprised = true
        result.surpriseCell.add(cell)
    }
}

/**
 * The hidden enemy a surprised move ran into, if either of them can hurt the other -- the combat that surprise
 * forces, as the mover's own attack while [GameUnit.isSurprised] is still up.
 *
 * Both engines this port answers to fight here. Panzer Marshal's move-finish handler calls its
 * attack routine on the surprise cell before it clears the flag (`openpanzer.js`, the
 * `f.isSurprised && (..., q(b.unit, k.row, k.col), f.isSurprised = !1)` step, which the port
 * dropped), and OG's combat page lists it as one of the ways a Normal Combat starts: *"when it meets
 * unspotted enemy unit while moving (Surprised)"*. The fight itself is OG's Rugged mode
 * (`RuggedCombat.applyRuggedCombat`: the defender fires first, +4/+4, no retreat), plus no
 * support fire and no overrun. Before 2026-09-26 none of it ever ran, because the flag was cleared
 * before any attack could read it.
 *
 * **The mover need not be able to shoot.** OG's surprise is a Rugged combat, and its only zero-attack
 * abort is for a combat that is NOT Rugged, so a column riding its trucks into a hidden enemy is
 * still fired on (the defender shoots first) and answers with whatever it has -- nothing, for a
 * truck (`RuggedCombat.resolveExchange`). Null only when neither side can hurt the other, or the
 * hex holds no enemy on the mover's own layer. The layer is the MOVER'S rather than the player's
 * Air Mode toggle: a UI selection preference must not decide a combat the multiplayer host and the
 * AI also resolve.
 */
internal fun GameMap.surpriseTarget(
    unit: GameUnit,
    result: MovementResults,
): GameUnit? =
    result.ambusher?.takeIf { !it.destroyed && unit.isSurprised && !unit.destroyed }
        ?: bumpedInto(unit, result)

/** The walked-into half of [surpriseTarget]: the hidden enemy on the hex the walk refused. */
private fun GameMap.bumpedInto(
    unit: GameUnit,
    result: MovementResults,
): GameUnit? =
    result.surpriseCell
        .firstOrNull()
        ?.takeIf { unit.isSurprised && !unit.destroyed }
        ?.let { cell -> map?.getOrNull(cell.row)?.getOrNull(cell.col) }
        ?.getUnit(UnitPredicates.isAir(unit))
        ?.takeIf { enemy ->
            UnitPredicates.isEnemy(unit, enemy) &&
                !enemy.destroyed &&
                (AttackEligibility.canFire(enemy, unit) || AttackEligibility.canFire(unit, enemy))
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
    gameMap.rebuildSpottingForSightBlocker(unit)
}
