package org.osada.rules

import org.osada.MovMethod
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Transport

/** Fuel / ammo consumption predicates, split out of [UnitPredicates] to keep its function count in bounds. */
fun UnitPredicates.unitUsesFuel(unit: GameUnit): Boolean = unitUsesFuelData(unit.unitData())

fun UnitPredicates.unitUsesFuel(transport: Transport): Boolean = unitUsesFuelData(transport.unitData())

private fun unitUsesFuelData(data: EquipmentData): Boolean {
    if (data.fuel == 0) return false
    val method = data.movmethod
    return method != MovMethod.LEG.value &&
        method != MovMethod.TOWED.value &&
        method != MovMethod.ALL_TERRAIN_LEG.value
}

fun UnitPredicates.unitLowFuel(
    unit: GameUnit,
    threshold: Int,
): Boolean {
    if (!unitUsesFuel(unit)) return false
    return if (!unit.isMounted) unit.fuel < threshold else unit.transport?.fuel ?: 0 < threshold
}

fun UnitPredicates.unitUsesAmmo(unit: GameUnit): Boolean = unit.unitData().ammo > 0

fun UnitPredicates.unitLowAmmo(
    unit: GameUnit,
    threshold: Int,
): Boolean {
    if (!unitUsesAmmo(unit)) return false
    return if (!unit.isMounted) unit.ammo < threshold else unit.transport?.ammo ?: 0 < threshold
}
