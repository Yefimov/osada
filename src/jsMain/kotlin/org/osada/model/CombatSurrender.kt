package org.osada.model

import org.osada.SURRENDER_PRESTIGE_FRACTION
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCostPerStrength
import org.osada.scoreGains

/**
 * Destroys [unit] as surrendered after a required retreat found no legal destination — the
 * encirclement case — and credits [captor]'s side with the remaining strength as kills (the same
 * scoring a normal kill would have produced had the strength been shot away instead).
 *
 * The caller owns the decision: see `CombatResolver.shouldDefenderSurrender`, and
 * `SURRENDER_ON_FAILED_RETREAT` for why this diverges from PM, which left such a unit in place
 * unharmed. Removal from the hex and the dossier entry happen in the usual [updateUnitList] sweep,
 * exactly as for a unit destroyed by damage.
 *
 * Lives here rather than on [CombatApplication] only to keep that class under detekt's
 * per-class function cap; it needs nothing private to it.
 */
fun GameMap.surrenderUnit(
    unit: GameUnit,
    captor: GameUnit,
): Int {
    val remaining = unit.strength
    if (remaining <= 0) return 0
    unit.hit(remaining)
    unit.surrendered = true
    captor.player?.updateScore(scoreGains["damage"] ?: 0, remaining)
    unit.player?.updateScore(
        if (unit.isCore) scoreGains["casualtyCore"] ?: 0 else scoreGains["casualty"] ?: 0,
        remaining,
    )
    // PC2's economic payoff for encirclement: the captor banks the value of the strength still
    // standing, rather than destroying it. See SURRENDER_PRESTIGE_FRACTION.
    val prestige = (GameRules.calculateUnitCostPerStrength(unit) * remaining * SURRENDER_PRESTIGE_FRACTION).toInt()
    val awardedPrestige = captor.player?.awardPrestige(prestige) ?: prestige
    updateUnitList()
    return awardedPrestige
}
