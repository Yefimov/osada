package org.osada.rules

import org.osada.UnitClass

/** Whether an exchange is close combat: an infantry attacker going into close terrain or against
 *  a fortification. The one predicate [AttackCalculation.applyCloseCombat] and the entrenchment
 *  forecast ([EntrenchmentDefence.forecast]) both read. */
internal object CloseCombat {
    fun applies(context: AttackCalculation.CombatContext): Boolean =
        (
            UnitPredicates.isCloseCombatTerrain(context.dTerrain) ||
                context.defenderData.uclass == UnitClass.FORTIFICATION.value
        ) &&
            context.attackerData.uclass == UnitClass.INFANTRY.value
}
