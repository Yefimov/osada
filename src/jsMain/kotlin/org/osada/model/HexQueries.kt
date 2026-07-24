package org.osada.model

import org.osada.rules.GameRules
import org.osada.rules.canInitiateAttack

private fun isAttackable(
    attacker: GameUnit,
    target: GameUnit?,
    spotted: Boolean,
): Boolean {
    if (target == null) return false
    val visible = spotted || target.tempSpotted
    return visible && GameRules.canInitiateAttack(attacker, target)
}

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
