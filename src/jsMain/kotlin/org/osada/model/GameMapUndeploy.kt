package org.osada.model

import org.osada.TerrainType
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/**
 * Lifts a deployed formation off the map into its owner's reserve tray.
 *
 * A core formation is already on the roster the tray reads. An auxiliary -- a unit this battle's
 * scenario lent the player (`keepScenarioUnitsAsAuxiliary`) -- joins that list here, still not
 * core (`Player.addCoreUnit`), so it can be placed again on another deploy hex and is still left
 * behind at the end of the battle.
 */
fun GameMap.undeployUnit(unit: GameUnit): Boolean {
    val hex = unit.getHex()
    val registeredUnit = (unit.isCore || unit.isTemporaryBorrowed) && unit.isDeployed && unit in units
    if (!registeredUnit || hex == null) return false
    GameRules.setZOCRange(this, unit, false)
    GameRules.setSpotRange(this, unit, false)
    // `deployPlayerUnit` embarks a ground formation placed on ocean. Reverse it here, or the
    // formation would stay afloat when it is next placed on land.
    if (!GameRules.isAir(unit) && hex.terrain == TerrainType.OCEAN.value && unit.carrier != 0) unit.carrier = 0
    hex.delUnit(unit)
    units.remove(unit)
    unit.setHex(null)
    unit.isDeployed = false
    if (unit.isTemporaryBorrowed) unit.player?.addCoreUnit(unit)
    if (currentUnit === unit) delCurrentUnit()
    return true
}
