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
 * This is the range/eligibility question — "is there anything here to shoot at?" — and it is the
 * one the AI and [org.osada.rules.CombatPositioning.getUnitAttackCells] ask. It deliberately does
 * not care which layer the human player is looking at.
 *
 * A player's click asks a different question; see [getActiveLayerTarget].
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
 * The target a PLAYER click on this hex resolves to: the ACTIVE layer only
 * (`docs/design/action-affordances-and-objectives.md` §7).
 *
 * On a hex holding one ground/naval unit and one aircraft, regular mode engages the ground/naval
 * occupant and Air Mode engages the aircraft — attack intent stays deterministic instead of
 * depending on which of the two happened to be eligible. On an unstacked hex `getUnit` returns the
 * sole occupant for either mode, so nothing changes there.
 *
 * The other layer is not silently attacked and the mode is never silently toggled; use
 * [inactiveLayerEnemy] to offer inspection and the mode-switch hint instead.
 */
fun Hex.getActiveLayerTarget(
    attacker: GameUnit,
    airMode: Boolean,
): GameUnit? {
    val attackerSide = attacker.player?.side ?: return null
    val target = getUnit(airMode)
    return if (isAttackable(attacker, target, isSpotted(attackerSide))) target else null
}

/**
 * A visible enemy sitting on the layer the player is NOT currently commanding, when the active
 * layer offers no target of its own. This is what turns "my click did nothing" into an explanation.
 *
 * Returns null on an unstacked hex: there is no other layer to point at.
 */
fun Hex.inactiveLayerEnemy(
    attacker: GameUnit,
    airMode: Boolean,
): GameUnit? {
    val attackerSide = attacker.player?.side ?: return null
    val active = getUnit(airMode)
    val other = getUnit(!airMode)
    val distinctEnemy =
        other != null &&
            other.id != (active?.id ?: -1) &&
            other.player?.side != attackerSide &&
            (isSpotted(attackerSide) || other.tempSpotted) &&
            !UnitConcealment.isConcealed(other, attackerSide)
    return if (distinctEnemy) other else null
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
