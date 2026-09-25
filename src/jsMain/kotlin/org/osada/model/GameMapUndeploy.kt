package org.osada.model

import org.osada.TerrainType
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

fun GameMap.undeployUnit(unit: GameUnit): Boolean {
    val hex = unit.getHex()
    val registeredCoreUnit = unit.isCore && unit.isDeployed && unit in units
    if (!registeredCoreUnit || hex == null) return false
    GameRules.setZOCRange(this, unit, false)
    GameRules.setSpotRange(this, unit, false)
    // `deployPlayerUnit` embarks a ground formation placed on ocean. Reverse it here, or the
    // formation would stay afloat when it is next placed on land.
    if (!GameRules.isAir(unit) && hex.terrain == TerrainType.OCEAN.value && unit.carrier != 0) unit.carrier = 0
    hex.delUnit(unit)
    units.remove(unit)
    unit.setHex(null)
    unit.isDeployed = false
    if (currentUnit === unit) delCurrentUnit()
    return true
}
