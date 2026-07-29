package org.osada.model

import org.osada.rules.GameRules
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

fun GameMap.undeployUnit(unit: GameUnit): Boolean {
    val hex = unit.getHex()
    val registeredCoreUnit = unit.isCore && unit.isDeployed && unit in units
    if (!registeredCoreUnit || hex == null) return false
    GameRules.setZOCRange(this, unit, false)
    GameRules.setSpotRange(this, unit, false)
    hex.delUnit(unit)
    units.remove(unit)
    unit.setHex(null)
    unit.isDeployed = false
    if (currentUnit === unit) delCurrentUnit()
    return true
}
