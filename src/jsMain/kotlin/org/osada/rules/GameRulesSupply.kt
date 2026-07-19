package org.osada.rules

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Supply

fun GameRules.canEmbark(
    map: GameMap,
    unit: GameUnit,
): Boolean = EmbarkRules.canEmbark(map, unit)

fun GameRules.canDisembark(
    map: GameMap,
    unit: GameUnit,
): Boolean = EmbarkRules.canDisembark(map, unit)

// --- Supply / reinforce (SupplyRules) ---

fun GameRules.getResupplyValue(
    map: GameMap,
    unit: GameUnit,
    full: Boolean = false,
): Supply = SupplyRules.getResupplyValue(map, unit, full)

fun GameRules.getReinforceValue(
    map: GameMap,
    unit: GameUnit,
    overStrength: Boolean = false,
): Int = SupplyRules.getReinforceValue(map, unit, overStrength)

fun GameRules.canResupply(
    map: GameMap,
    unit: GameUnit,
): Boolean = SupplyRules.canResupply(map, unit)

fun GameRules.canReinforce(
    map: GameMap,
    unit: GameUnit,
    overStrength: Boolean = false,
): Boolean = SupplyRules.canReinforce(map, unit, overStrength)
