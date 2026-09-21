package org.osada.model

import org.osada.rules.GameRules
import org.osada.rules.UnitConcealment
import org.osada.rules.canInitiateAttack

private fun isAttackable(
    attacker: GameUnit,
    target: GameUnit?,
    spotted: Boolean,
): Boolean {
    if (target == null) return false
    // `Forest Camouflage` hides a unit whose HEX is spotted, which the reference-counted fog cannot
    // express on its own -- see [UnitConcealment] for why it is a layer over the counters and not a
    // change to them. A concealed unit cannot be targeted by anything: this predicate is what the
    // player's click, the attack overlay and the AI all resolve through.
    val visible = (spotted || target.tempSpotted) && !UnitConcealment.isConcealed(target, attacker.player?.side ?: -1)
    return visible && GameRules.canInitiateAttack(attacker, target)
}

/**
 * Any target on this hex [attacker] could legally engage, on EITHER occupancy layer.
 *
 * This is the only targeting question the game asks -- the AI, the attack-range pass
 * ([org.osada.rules.CombatPositioning.getUnitAttackCells]) and the player's own click all resolve
 * through it, so none of them can disagree about what is shootable.
 *
 * [airMode] is a PREFERENCE, not a filter. On a hex holding both a ground/naval occupant and an
 * aircraft it decides which of the two an otherwise-ambiguous attack hits: regular mode takes the
 * ground occupant, Air Mode takes the aircraft. When the preferred layer holds nothing this
 * attacker can engage -- an aircraft over an enemy ground unit, which is the ordinary way a bomber
 * attacks -- the other layer is used. Air Mode is a SELECTION layer (it is re-derived from the
 * selected unit after every click, `MapInputController.finishClick`), so letting it veto a legal
 * attack made every ground attack by an aircraft impossible.
 */
fun Hex.getAttackableUnit(
    attacker: GameUnit,
    airMode: Boolean,
): GameUnit? {
    val attackerSide = attacker.player?.side ?: return null
    val spotted = isSpotted(attackerSide)
    val primary = getUnit(airMode)
    val primaryId = primary?.id ?: -1
    val secondary = getUnit(!airMode)
    val isSecondaryDistinct = secondary != null && secondary.id != primaryId
    return when {
        isAttackable(attacker, primary, spotted) -> primary
        isSecondaryDistinct && isAttackable(attacker, secondary, spotted) -> secondary
        else -> null
    }
}

/**
 * Whether the installation on this hex is WORKING — i.e. it is one and it has not been wrecked.
 *
 * OG's own statement of what a barrage or a demolition leaves behind is *"reduce a City,
 * Airfield, Bridge, or Port to rubble, **making them unusable until Repaired**"* (`tips1.txt`,
 * and Open General School theme 5). Until 2026-08-27 [rubble] cost movement and nothing else,
 * so a shelled port still berthed ships, a shelled airfield still refuelled aircraft and a
 * shelled city still resupplied the formation standing in it — *"unusable"* meant "slower to
 * walk through".
 *
 * Every rule that asks *"is there a working city / port / airfield here?"* goes through this,
 * so none of them can disagree about a wreck: air basing (`MovementRules.hasAirfield`),
 * automatic ground resupply (`SupplyRules`), the deploy zone and the purchase anchor
 * (`GameMapDeployZone`, `GameMapGrid.ownsSupplyHex`).
 *
 * **Inert unless something can actually wreck a hex.** Only `rules/Barrage` sets [rubble] and
 * only Repair clears it, so with both of those rules off no hex is ever wrecked and this is
 * `terrain == what` exactly as it was before.
 */
fun Hex.isWorking(what: Int): Boolean = terrain == what && !rubble
