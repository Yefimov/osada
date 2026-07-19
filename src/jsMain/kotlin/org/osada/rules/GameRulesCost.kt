package org.osada.rules

import org.osada.model.GameUnit

// --- Costs (CostCalculator) ---

fun GameRules.calculateUnitCosts(
    eqid: Int,
    transportEqid: Int,
): Int = CostCalculator.calculateUnitCosts(eqid, transportEqid)

fun GameRules.calculateUpgradeCosts(
    unit: GameUnit,
    newEqid: Int,
    transportEqid: Int,
): Int = CostCalculator.calculateUpgradeCosts(unit, newEqid, transportEqid)

fun GameRules.calculateUnitCostPerStrength(unit: GameUnit): Int = CostCalculator.calculateUnitCostPerStrength(unit)

fun GameRules.calculateUnitSellCost(unit: GameUnit): Int = CostCalculator.calculateUnitSellCost(unit)
