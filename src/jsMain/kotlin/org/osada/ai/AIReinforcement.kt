package org.osada.ai

import org.osada.ActionType
import org.osada.UnitClass
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCostPerStrength
import org.osada.rules.canReinforce
import org.osada.rules.getReinforceValue

/**
 * [AI]'s reinforce-eligibility and prestige-spend decision. Split out purely to keep [AI] within
 * the project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object AIReinforcement {
    fun cannotReinforce(
        aiUnit: AIUnit,
        unit: GameUnit,
        map: GameMap,
        availablePrestige: Int,
    ): Boolean =
        !GameRules.canReinforce(map, unit) ||
            aiUnit.didResupplyReinforce ||
            aiUnit.didAttack ||
            aiUnit.didMove ||
            unit.unitData().uclass == UnitClass.FORTIFICATION.value ||
            availablePrestige <= 0

    /**
     * Attempts to reinforce [unit] from [availablePrestige]. Returns the (possibly reduced)
     * remaining prestige and whether reinforcement was "handled" -- either an action was queued,
     * or [aiUnit].noReinforce was already set -- so the caller can skip its own fallback check.
     */
    fun attemptReinforce(
        aiUnit: AIUnit,
        unit: GameUnit,
        map: GameMap,
        availablePrestige: Int,
        addAction: (ActionType, Array<dynamic>) -> Unit,
    ): ReinforceOutcome {
        val needsReinforce = (aiUnit.noAttack && aiUnit.noMove) || unit.strength < REINFORCE_STRENGTH_THRESHOLD
        val costPerStrength = if (needsReinforce) GameRules.calculateUnitCostPerStrength(unit) else 0
        return when {
            !needsReinforce -> ReinforceOutcome(availablePrestige, false)
            costPerStrength <= 0 -> {
                aiUnit.noReinforce = true
                ReinforceOutcome(availablePrestige, true)
            }

            else -> applyReinforcement(aiUnit, unit, map, availablePrestige, costPerStrength, addAction)
        }
    }

    private fun applyReinforcement(
        aiUnit: AIUnit,
        unit: GameUnit,
        map: GameMap,
        availablePrestige: Int,
        costPerStrength: Int,
        addAction: (ActionType, Array<dynamic>) -> Unit,
    ): ReinforceOutcome {
        val reinforceValue = GameRules.getReinforceValue(map, unit)
        var amount = availablePrestige / costPerStrength
        if (amount > reinforceValue) amount = reinforceValue
        val canReinforce = reinforceValue > 0 && amount > 0
        if (!canReinforce) return ReinforceOutcome(availablePrestige, false)
        addAction(ActionType.REINFORCE, arrayOf(unit))
        aiUnit.didResupplyReinforce = true
        return ReinforceOutcome(availablePrestige - amount * costPerStrength, true)
    }
}

/** Outcome of [AIReinforcement.attemptReinforce]: updated prestige, and whether it was handled. */
internal data class ReinforceOutcome(
    val availablePrestige: Int,
    val handled: Boolean,
)
