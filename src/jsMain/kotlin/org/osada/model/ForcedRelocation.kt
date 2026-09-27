package org.osada.model

import org.osada.rules.GameRules
import org.osada.rules.Minefields
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/** Why a unit was moved without being ordered to. */
enum class ForcedMoveCause {
    /** Pushed out of its hex by combat losses (`CombatResolver.shouldDefenderRetreat`). */
    COMBAT_RETREAT,

    /** Slipped the attack -- OG's `Evade` (`rules/Evade`). */
    EVASION,
}

/**
 * A forced relocation that happened: who, from where, to where and why, plus what the destination
 * did to the unit. The UI turns it into a map notice and a log line; the model has already applied it.
 */
class ForcedMove(
    val unit: GameUnit,
    val from: Cell,
    val to: Cell,
    val cause: ForcedMoveCause,
    val minefieldLosses: Int = 0,
    val minefieldWasHidden: Boolean = false,
)

/**
 * Moves [unit] one hex to [to] because combat forced it -- a retreat or an evasion -- as an ATOMIC
 * relocation rather than an ordinary move (`docs/design/combat-movement-heroes-weather-audit.md` §5).
 *
 * Both used to go through `GameMap.moveUnit`, which treated the unit as if it had been ordered there:
 * an `Overwatch` commander (the attacker's own, often) could spring an ambush on the retreating unit
 * and leave it permanently surprised, the retreat captured the flag it landed on, and a trigger hex
 * paid out -- none of which the caller handled or reported.
 *
 * The policy, which is OSADA's choice rather than proven OG behaviour:
 *
 * - **minefields act**: an undetected field strikes and is revealed; a known one does no damage. The
 *   unit's own movement allowance is untouched, as it always was for a retreat;
 * - **no flag is captured** and **no trigger / escape hex fires** -- those are rewards for arriving
 *   somewhere on purpose;
 * - **no reaction fires**: no ambush, no interception;
 * - the scenario's own CONDITIONS are still re-evaluated after the positions change -- that is the
 *   caller's job, done once the combat that caused the move has been presented.
 *
 * Occupancy, zones of control and spotting are maintained exactly as a move maintains them: the
 * remove/add pairs keep the reference counts balanced.
 */
internal fun GameMap.relocateForced(
    unit: GameUnit,
    to: Cell,
    cause: ForcedMoveCause,
): ForcedMove? {
    val from = unit.getPos()
    val fromHex = unit.getHex()
    val toHex = map?.getOrNull(to.row)?.getOrNull(to.col)
    val side = unit.player?.side ?: -1
    if (from == null || fromHex == null || toHex == null) return null
    GameRules.setZOCRange(this, unit, false)
    GameRules.setSpotRange(this, unit, false)
    fromHex.delUnit(unit)
    toHex.setUnit(unit)
    GameRules.setZOCRange(this, unit, true)
    GameRules.setSpotRange(this, unit, true)
    rebuildSpottingForSightBlocker(unit)
    val mined = Minefields.threatens(toHex, side)
    val hidden = mined && !Minefields.isDetectedBy(toHex, side)
    val losses = if (hidden) Minefields.strikeDamage(unit) else 0
    if (mined) Minefields.markDetected(toHex, side)
    if (losses > 0) unit.hit(losses)
    return ForcedMove(unit, from, Cell(to.row, to.col), cause, losses, hidden)
}
