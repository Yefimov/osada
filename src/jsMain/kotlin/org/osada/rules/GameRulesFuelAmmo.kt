package org.osada.rules

import org.osada.model.GameUnit
import org.osada.model.Transport

// --- Fuel / ammo predicates (UnitPredicates) ---

fun GameRules.unitUsesFuel(unit: GameUnit): Boolean = UnitPredicates.unitUsesFuel(unit)

fun GameRules.unitUsesFuel(transport: Transport): Boolean = UnitPredicates.unitUsesFuel(transport)

fun GameRules.unitLowFuel(
    unit: GameUnit,
    threshold: Int,
): Boolean = UnitPredicates.unitLowFuel(unit, threshold)

fun GameRules.unitUsesAmmo(unit: GameUnit): Boolean = UnitPredicates.unitUsesAmmo(unit)

fun GameRules.unitLowAmmo(
    unit: GameUnit,
    threshold: Int,
): Boolean = UnitPredicates.unitLowAmmo(unit, threshold)
